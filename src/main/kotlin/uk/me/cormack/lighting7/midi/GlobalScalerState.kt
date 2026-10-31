package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.StateFlow
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.TransmitModifier
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fx.HeadIntensity
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
 *    a rate rather than a level, so it passes through unchanged above 0 % and is cut at 0 %. So
 *    does an LED ring's effect channel (RING): it selects a band, 0 being off, and the dimmer does
 *    not reach it.
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
 *     `@FixtureAttribute.category` annotations. The classification is two sets of packed
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

    /**
     * Rebuild the channel classification from the current fixtures registry: every head's own
     * levels are scaled, its strobe and ring channels cut. Read from [HeadIntensity.classify], which
     * Move in dark reads too, so the two never disagree about what lights a head.
     */
    fun refreshClassification() {
        val scaled = HashSet<Long>()
        val strobe = HashSet<Long>()
        for (head in HeadIntensity.classify(fixtures).values) {
            for (channel in head.ownLevels) scaled += packChannelKey(head.universe, channel)
            for (channel in head.cuts) strobe += packChannelKey(head.universe, channel)
        }
        classification.set(Classification(scaled, strobe))
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

/** A control's 0..127 as a stack FX amount, 0–1 (effects plan D10). */
fun stackFxAmountOf7Bit(value7Bit: UByte): Double = value7Bit.toInt() / 127.0

/** A stack FX amount as a control's 0..127: [stackFxAmountOf7Bit]'s inverse, rounded. */
fun stackFxAmountTo7Bit(amount: Double): UByte = (amount * 127.0).roundToInt().coerceIn(0, 127).toUByte()

/** [value] scaled by [level] percent, rounded. */
internal fun scaleLevel(value: UByte, level: Double): UByte =
    (value.toInt() * level / GRAND_MASTER_FULL).roundToInt().coerceIn(0, 255).toUByte()
