package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.StateFlow
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.TransmitModifier
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixturePropertyCatalogue
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxWhiteMixColour
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.models.GRAND_MASTER_FULL
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * The two global scalers on DMX output, applied at transmit time:
 *
 *  - **Blackout** — when enabled, every scaled channel and every strobe channel outputs 0. Either
 *    blackout does it: the persisted one the MIDI `Blackout` target toggles, or *Make rig safe*'s
 *    runtime one ([GlobalScalerStateHolder.safeBlackout]).
 *  - **Grand Master** — a level, 0–100 %, multiplying every scaled channel. A strobe channel holds
 *    a rate rather than a level, so it passes through unchanged above 0 % and is cut at 0 %.
 *
 * Which channels are scaled (venue-visit plan D12): every DIMMER and UV channel, and, on a head
 * with no dimmer, its colour-mix channels — a `DmxColour`'s red, green and blue, a
 * `DmxWhiteMixColour`'s two whites, and its WHITE and AMBER sliders — so pulling the grand master
 * down dims the whole rig. Each channel is in the scaled set once, however many properties cover it. A head is a fixture, or one
 * of a [MultiElementFixture]'s elements; an element is dimmerless only when neither it nor its
 * parent has a dimmer, so a zone under a master dimmer is left to that dimmer. Colour wheels and
 * presets are never scaled: they select a colour, they do not mix one. Position, beam and setting
 * channels pass through.
 *
 * Applied after composition and FX but before park, which wins on a parked channel. Every
 * listener on the controller — the channel stream, the Stage view, a motor fader's feedback —
 * sees the scaled output.
 *
 * The *state* lives in a project-scoped [GlobalScalerStateHolder] supplied by construction. This
 * instance is show-scoped: it owns the channel classification and controller wiring for the
 * *current* show. On project switch a new [GlobalScalerState] is constructed against the same
 * holder, so operator intent survives the show tear-down and rebuild — see Phase 9 of
 * docs/plans/completed/control-surface-plan.md.
 *
 * Implementation:
 *   - Classifies every DMX channel by walking [Fixtures] at fixture-change time and reading the
 *     `@FixtureProperty.category` annotations. The classification is two sets of packed
 *     `(universe << 20) | channel` keys held in an [AtomicReference] for lock-free lookup on the
 *     hot path.
 *   - Registers itself as a [TransmitModifier] on every controller in [Fixtures] when
 *     [attach] is called, and refreshes on `fixturesChanged`.
 */
class GlobalScalerState(
    private val fixtures: Fixtures,
    private val holder: GlobalScalerStateHolder = GlobalScalerStateHolder(),
) : TransmitModifier {

    /** True while blackout is active — scaled and strobe channels output 0. */
    val blackoutEnabled: StateFlow<Boolean> get() = holder.blackoutEnabled

    /** The grand master read as on/off: true while its level is above 0. */
    val grandMasterEnabled: StateFlow<Boolean> get() = holder.grandMasterEnabled

    /** The grand master, 0–100 %. */
    val grandMasterLevel: StateFlow<Double> get() = holder.grandMasterLevel

    /** Packed `(universe << 20) | channel` keys, by how the scalers treat them. */
    private class Classification(val scaled: Set<Long>, val strobe: Set<Long>)

    private val classification = AtomicReference(Classification(emptySet(), emptySet()))

    private val controllers = mutableListOf<DmxController>()

    private val listener = object : FixturesChangeListener {
        override fun controllersChanged() { reattach() }
        override fun fixturesChanged() { refreshClassification() }
    }

    /**
     * Attach to the given fixtures registry: register as a transmit modifier on every
     * current controller, install the fixture-change listener, and build the initial
     * channel classification.
     */
    fun attach() {
        fixtures.registerListener(listener)
        reattach()
        refreshClassification()
    }

    fun detach() {
        fixtures.unregisterListener(listener)
        synchronized(controllers) {
            for (c in controllers) c.removeTransmitModifier(this)
            controllers.clear()
        }
    }

    private fun reattach() {
        synchronized(controllers) {
            for (c in controllers) c.removeTransmitModifier(this)
            controllers.clear()
            for (c in fixtures.controllers) {
                c.addTransmitModifier(this)
                controllers += c
            }
        }
    }

    /** Rebuild the channel classification from the current fixtures registry. */
    fun refreshClassification() {
        val scaled = HashSet<Long>()
        val strobe = HashSet<Long>()
        for (fixture in fixtures.fixtures) {
            if (fixture !is DmxFixture) continue
            val universe = fixture.universe.universe
            val own = fixture.fixtureProperties.map { property ->
                property.category to runCatching { property.classProperty.call(fixture) }.getOrNull()
            }
            val fixtureDimmed = own.any { (category, _) -> category == PropertyCategory.DIMMER }
            classify(universe, own, dimmerless = !fixtureDimmed, scaled, strobe)
            if (fixture is MultiElementFixture<*>) {
                for (element in fixture.elements) {
                    val elementOwn = elementProperties(element)
                    val elementDimmed = elementOwn.any { (category, _) -> category == PropertyCategory.DIMMER }
                    classify(universe, elementOwn, dimmerless = !fixtureDimmed && !elementDimmed, scaled, strobe)
                }
            }
        }
        classification.set(Classification(scaled, strobe))
    }

    private fun elementProperties(element: FixtureElement<*>): List<Pair<PropertyCategory, Any?>> =
        FixturePropertyCatalogue.of(element::class).all.map { property ->
            @Suppress("UNCHECKED_CAST")
            val read = property.classProperty as kotlin.reflect.KProperty1<Any, *>
            property.category to runCatching { read.call(element) }.getOrNull()
        }

    private fun classify(
        universe: Int,
        properties: List<Pair<PropertyCategory, Any?>>,
        dimmerless: Boolean,
        scaled: MutableSet<Long>,
        strobe: MutableSet<Long>,
    ) {
        for ((category, value) in properties) {
            if (value == null) continue
            when (category) {
                PropertyCategory.DIMMER, PropertyCategory.UV -> addValueChannels(universe, value, scaled)
                PropertyCategory.STROBE -> addValueChannels(universe, value, strobe)
                PropertyCategory.COLOUR ->
                    if (dimmerless && (value is DmxColour || value is DmxWhiteMixColour)) addValueChannels(universe, value, scaled)
                PropertyCategory.WHITE, PropertyCategory.AMBER ->
                    if (dimmerless && value is DmxSlider) addValueChannels(universe, value, scaled)
                else -> Unit
            }
        }
    }

    private fun addValueChannels(universe: Int, value: Any, into: MutableSet<Long>) {
        when (value) {
            is DmxSlider -> into += packChannelKey(universe, value.channelNo)
            is DmxColour -> {
                into += packChannelKey(universe, value.redSlider.channelNo)
                into += packChannelKey(universe, value.greenSlider.channelNo)
                into += packChannelKey(universe, value.blueSlider.channelNo)
            }
            is DmxWhiteMixColour -> {
                into += packChannelKey(universe, value.warm.channelNo)
                into += packChannelKey(universe, value.cold.channelNo)
            }
        }
    }

    override fun modify(universe: Universe, channel: Int, value: UByte): UByte {
        val blackout = holder.blackoutEnabled.value || holder.safeBlackout.value
        val level = holder.grandMasterLevel.value
        if (!blackout && level >= GRAND_MASTER_FULL) return value
        val key = packChannelKey(universe.universe, channel)
        val classes = classification.get()
        return when {
            key in classes.scaled -> if (blackout) 0u else scaleLevel(value, level)
            key in classes.strobe -> if (blackout || level <= 0.0) 0u else value
            else -> value
        }
    }

    /** Toggle blackout. Returns the new state. */
    fun toggleBlackout(): Boolean = holder.toggleBlackout()

    /** Toggle the grand master between 0 and its last level. Returns the new on/off. */
    fun toggleGrandMaster(): Boolean = holder.toggleGrandMaster()

    /** Explicitly set the blackout state (exposed for WS `surfaceScaler.set`). */
    fun setBlackout(enabled: Boolean) {
        holder.setBlackout(enabled)
    }

    /** Turn the grand master off (level 0) or back on (its last level). */
    fun setGrandMaster(enabled: Boolean) {
        holder.setGrandMaster(enabled)
    }

    /** Set the grand master's level, 0–100 %, clamped. */
    fun setGrandMasterLevel(level: Double) {
        holder.setGrandMasterLevel(level)
    }

    /**
     * Test seam: mark the given `(universe, channel)` pairs as intensity channels
     * without walking fixtures. Hides the packed-key encoding from tests.
     */
    internal fun seedIntensityChannelsForTest(pairs: Set<Pair<Int, Int>>) {
        classification.set(Classification(pairs.mapTo(HashSet()) { (u, c) -> packChannelKey(u, c) }, emptySet()))
    }
}

/** A control's 0..127 as a grand-master level, 0–100 %. */
fun grandMasterLevelOf7Bit(value7Bit: UByte): Double = value7Bit.toInt() * GRAND_MASTER_FULL / 127.0

/** A grand-master level as a control's 0..127: [grandMasterLevelOf7Bit]'s inverse, rounded. */
fun grandMasterLevelTo7Bit(level: Double): UByte =
    (level / GRAND_MASTER_FULL * 127.0).roundToInt().coerceIn(0, 127).toUByte()

/** [value] scaled by [level] percent, rounded. */
internal fun scaleLevel(value: UByte, level: Double): UByte =
    (value.toInt() * level / GRAND_MASTER_FULL).roundToInt().coerceIn(0, 255).toUByte()
