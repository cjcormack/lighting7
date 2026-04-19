package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.TransmitModifier
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.dmx.packChannelKey
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import java.util.concurrent.atomic.AtomicReference
import kotlin.reflect.full.memberProperties

/**
 * Two global toggles that suppress DMX output at transmit time:
 *
 *  - **Blackout** — when enabled, intensity-like channels (DIMMER / UV / STROBE) output 0.
 *  - **Grand Master** — a binary kill switch on the same set of channels. v1 is a toggle
 *    (`enabled` = 1.0 scale, `disabled` = 0.0 scale); continuous Grand Master control is
 *    left for a future phase when a fader is bound.
 *
 * Both scalers affect only intensity-category properties. Colour, position, and setting
 * channels pass through unchanged — this matches the proposal in Open Question 2/3 of
 * docs/control-surface-plan.md and the behaviour of most professional consoles.
 *
 * Implementation:
 *   - Classifies every DMX channel by walking [Fixtures] at fixture-change time and
 *     reading the `@FixtureProperty.category` annotations. The classification is a set of
 *     packed `(universe << 20) | channel` keys held in an [AtomicReference] for lock-free
 *     lookup on the hot path.
 *   - Registers itself as a [TransmitModifier] on every controller in [Fixtures] when
 *     [attachToControllers] is called, and refreshes on `fixturesChanged`.
 *   - On toggle, calls [DmxController.requestTransmit] on every attached controller so the
 *     change shows up immediately instead of waiting up to 25 ms for the next frame.
 */
class GlobalScalerState(
    private val fixtures: Fixtures,
) : TransmitModifier {

    private val _blackoutEnabled = MutableStateFlow(false)
    /** True while blackout is active — intensity channels output 0. */
    val blackoutEnabled: StateFlow<Boolean> = _blackoutEnabled.asStateFlow()

    private val _grandMasterEnabled = MutableStateFlow(true)
    /** True while Grand Master is "on" (normal output). False kills intensity channels. */
    val grandMasterEnabled: StateFlow<Boolean> = _grandMasterEnabled.asStateFlow()

    /** Packed `(universe << 20) | channel` keys for every intensity-category channel. */
    private val intensityChannels = AtomicReference<Set<Long>>(emptySet())

    private val controllers = mutableListOf<DmxController>()

    private val listener = object : FixturesChangeListener {
        override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {}
        override fun controllersChanged() { reattach() }
        override fun fixturesChanged() { refreshClassification() }
        override fun presetListChanged() {}
        override fun cueListChanged() {}
        override fun cueStackListChanged() {}
        override fun cueSlotListChanged() {}
        override fun patchListChanged() {}
        override fun showEntriesChanged() {}
        override fun showChanged(
            projectId: Int,
            activeEntryId: Int?,
            activatedStackId: Int?,
            activatedStackName: String?,
        ) {}
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

    /** Rebuild the intensity-channel set from the current fixtures registry. */
    fun refreshClassification() {
        val keys = HashSet<Long>()
        for (fixture in fixtures.fixtures) {
            if (fixture !is DmxFixture) continue
            collectIntensityChannels(fixture, keys)
            if (fixture is MultiElementFixture<*>) {
                for (element in fixture.elements) {
                    collectElementIntensityChannels(fixture.universe.universe, element, keys)
                }
            }
        }
        intensityChannels.set(keys)
    }

    private fun collectIntensityChannels(fixture: DmxFixture, into: MutableSet<Long>) {
        for (property in fixture.fixtureProperties) {
            if (!property.category.isIntensityLike()) continue
            val value = try {
                property.classProperty.call(fixture)
            } catch (_: Exception) {
                null
            } ?: continue
            addValueChannels(fixture.universe.universe, value, into)
        }
    }

    private fun collectElementIntensityChannels(
        universe: Int,
        element: FixtureElement<*>,
        into: MutableSet<Long>,
    ) {
        for (classProperty in element::class.memberProperties) {
            val annotation = classProperty.annotations
                .filterIsInstance<uk.me.cormack.lighting7.fixture.FixtureProperty>()
                .firstOrNull() ?: continue
            if (!annotation.category.isIntensityLike()) continue
            @Suppress("UNCHECKED_CAST")
            val raw = (classProperty as kotlin.reflect.KProperty1<Any, *>).call(element) ?: continue
            addValueChannels(universe, raw, into)
        }
    }

    private fun addValueChannels(universe: Int, value: Any, into: MutableSet<Long>) {
        when (value) {
            is DmxSlider -> into += packChannelKey(universe, value.channelNo)
            is DmxColour -> {
                // Colour isn't strictly intensity-like by default; but if a fixture annotated its
                // `rgbColour` as DIMMER (unusual but possible), the caller ends up here. Include
                // all three channels so behaviour is consistent.
                into += packChannelKey(universe, value.redSlider.channelNo)
                into += packChannelKey(universe, value.greenSlider.channelNo)
                into += packChannelKey(universe, value.blueSlider.channelNo)
            }
        }
    }

    override fun modify(universe: Universe, channel: Int, value: UByte): UByte {
        if (!isKilled()) return value
        val key = packChannelKey(universe.universe, channel)
        return if (key in intensityChannels.get()) 0u else value
    }

    private fun isKilled(): Boolean =
        _blackoutEnabled.value || !_grandMasterEnabled.value

    /** Toggle blackout. Returns the new state. */
    fun toggleBlackout(): Boolean {
        val next = !_blackoutEnabled.value
        _blackoutEnabled.value = next
        requestTransmit()
        return next
    }

    /** Toggle Grand Master. Returns the new state. */
    fun toggleGrandMaster(): Boolean {
        val next = !_grandMasterEnabled.value
        _grandMasterEnabled.value = next
        requestTransmit()
        return next
    }

    /** Explicitly set the blackout state (exposed for WS `surfaceScaler.set`). */
    fun setBlackout(enabled: Boolean) {
        if (_blackoutEnabled.value == enabled) return
        _blackoutEnabled.value = enabled
        requestTransmit()
    }

    /** Explicitly set the Grand Master state. */
    fun setGrandMaster(enabled: Boolean) {
        if (_grandMasterEnabled.value == enabled) return
        _grandMasterEnabled.value = enabled
        requestTransmit()
    }

    private fun requestTransmit() {
        synchronized(controllers) {
            for (c in controllers) {
                try {
                    c.requestTransmit()
                } catch (_: Exception) {
                    // best-effort — a controller may be mid-shutdown.
                }
            }
        }
    }

    /**
     * Test seam: mark the given `(universe, channel)` pairs as intensity channels
     * without walking fixtures. Hides the packed-key encoding from tests.
     */
    internal fun seedIntensityChannelsForTest(pairs: Set<Pair<Int, Int>>) {
        intensityChannels.set(pairs.mapTo(HashSet()) { (u, c) -> packChannelKey(u, c) })
    }
}

/**
 * Categories the global scalers affect: intensity-like properties (dimmer / UV / strobe).
 * Rationale in docs/control-surface-plan.md Open Questions 2 and 3.
 */
private fun PropertyCategory.isIntensityLike(): Boolean = when (this) {
    PropertyCategory.DIMMER, PropertyCategory.UV, PropertyCategory.STROBE -> true
    else -> false
}
