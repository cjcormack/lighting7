package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.DirectWriteStore
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Port between [SurfaceInputRouter] and the rest of the application. Production wires this
 * to [DefaultSurfaceActions], which delegates into [Fixtures], [DirectWriteStore],
 * [CueStackManager], and [GlobalScalerState]. Tests can supply a recording fake so the
 * router can be exercised without a running show.
 *
 * All methods are fire-and-forget from the router's perspective — implementations handle
 * their own thread coordination (DirectWriteStore is lock-free; cue stack activation is
 * dispatched onto [GlobalScope] by [DefaultSurfaceActions]).
 */
interface SurfaceActions {
    /** Write a continuous value (0..127 MIDI 7-bit) to a fixture property. */
    fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte)

    /** Write a continuous value (0..127 MIDI 7-bit) to every member of a group. */
    fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte)

    /** Flash press: store at 0..255 [max] on the property's channels. */
    fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte)
    fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte)

    /** Flash release: clear direct-write entries for the property's channels. */
    fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String)
    fun flashGroupPropertyRelease(groupName: String, propertyName: String)

    fun cueStackGo(stackId: Int)
    fun cueStackBack(stackId: Int)
    fun cueStackPause(stackId: Int)
    fun fireCue(cueId: Int)

    fun toggleBlackout(): Boolean
    fun toggleGrandMaster(): Boolean
}

/**
 * Production [SurfaceActions] implementation. Wraps the show's services and writes through
 * to both the [DirectWriteStore] (Layer 4) and the DMX controller so values show up live.
 *
 * All dependencies are resolved through [state] on every call, so project switches that
 * swap the [uk.me.cormack.lighting7.show.Show] instance automatically route subsequent
 * surface events to the new show's fixtures / cue manager / scaler.
 */
class DefaultSurfaceActions(
    private val state: uk.me.cormack.lighting7.state.State,
) : SurfaceActions {

    companion object {
        private val logger = LoggerFactory.getLogger(DefaultSurfaceActions::class.java)
    }

    private val fixtures: Fixtures get() = state.show.fixtures
    private val directWriteStore: DirectWriteStore get() = state.show.directWriteStore
    private val cueStackManager: CueStackManager get() = state.show.cueStackManager
    private val globalScalerState: GlobalScalerState get() = state.show.globalScalerState

    override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte) {
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) {
            logger.debug("Surface write: fixture '{}' not found", fixtureKey)
            return
        }
        val writes = directWriteStore.putProperty(fixture, propertyName, midiValue7Bit)
        pushToControllers(writes)
    }

    override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte) {
        val group = try {
            fixtures.untypedGroup(groupName)
        } catch (_: Exception) {
            logger.debug("Surface write: group '{}' not found", groupName)
            return
        }
        val writes = directWriteStore.putGroupProperty(group, propertyName, midiValue7Bit)
        pushToControllers(writes)
    }

    override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        val writes = buildFlashWrites(fixture, propertyName, max)
        for (w in writes) directWriteStore.put(w.universe.universe, w.channel, w.value)
        pushToControllers(writes)
    }

    override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        val all = mutableListOf<PropertyChannelResolver.ChannelWrite>()
        for (member in group.fixtures) {
            if (member is Fixture) {
                val memberWrites = buildFlashWrites(member, propertyName, max)
                all += memberWrites
                for (c in memberWrites) directWriteStore.put(c.universe.universe, c.channel, c.value)
            }
        }
        pushToControllers(all)
    }

    private fun buildFlashWrites(
        fixture: Fixture,
        propertyName: String,
        max: UByte,
    ): List<PropertyChannelResolver.ChannelWrite> {
        // Resolve channels once with a throwaway value; we override value below.
        val channels = PropertyChannelResolver.resolveFixtureProperty(fixture, propertyName, 127u)
        if (channels.isEmpty()) return emptyList()
        val sliderMax = fixtureSliderMaxFor(fixture, propertyName)
        val clamped = if (sliderMax != null) minOf(max, sliderMax) else max
        return channels.map { it.copy(value = clamped) }
    }

    override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        val cleared = directWriteStore.clearProperty(fixture, propertyName)
        // Write zero through the controllers so the immediate output reverts; the composition
        // resolver will take over on the next tick.
        for (w in cleared) {
            try {
                fixtures.controller(w.universe).setValue(w.channel, 0u, 0)
            } catch (_: Exception) { /* controller may have been removed */ }
        }
    }

    override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        val cleared = directWriteStore.clearGroupProperty(group, propertyName)
        for (w in cleared) {
            try {
                fixtures.controller(w.universe).setValue(w.channel, 0u, 0)
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGo(stackId: Int) {
        try {
            val result = if (cueStackManager.isStackActive(stackId)) {
                cueStackManager.advanceStack(state, stackId, CueStackManager.AdvanceDirection.FORWARD, GlobalScope)
            } else {
                cueStackManager.activateAtFirstCue(state, stackId, GlobalScope)
            }
            if (result != null) state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface GO failed for stack $stackId: ${e.message}")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackBack(stackId: Int) {
        try {
            val result = cueStackManager.advanceStack(state, stackId, CueStackManager.AdvanceDirection.BACKWARD, GlobalScope)
            if (result != null) state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface BACK failed for stack $stackId: ${e.message}")
        }
    }

    override fun cueStackPause(stackId: Int) {
        try {
            cueStackManager.pauseAutoAdvance(stackId)
        } catch (e: Exception) {
            logger.warn("Surface PAUSE failed for stack $stackId: ${e.message}")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun fireCue(cueId: Int) {
        try {
            cueStackManager.fireCue(state, cueId, GlobalScope)
            state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface FIRE CUE failed for $cueId: ${e.message}")
        }
    }

    override fun toggleBlackout(): Boolean = globalScalerState.toggleBlackout()
    override fun toggleGrandMaster(): Boolean = globalScalerState.toggleGrandMaster()

    private fun pushToControllers(writes: List<PropertyChannelResolver.ChannelWrite>) {
        if (writes.isEmpty()) return
        val byUniverse = writes.groupBy { it.universe }
        for ((universe, group) in byUniverse) {
            val controller = try {
                fixtures.controller(universe)
            } catch (_: Exception) {
                continue
            }
            for (w in group) controller.setValue(w.channel, w.value, 0)
        }
    }

    /**
     * Look up the DmxSlider's `max` for a fixture property, returning null if the property
     * isn't a slider (e.g. colour). Used by Flash press to avoid writing past a dimmer's
     * configured cap.
     */
    private fun fixtureSliderMaxFor(fixture: Fixture, propertyName: String): UByte? {
        val property = fixture.fixtureProperty(propertyName) ?: return null
        val raw = try {
            property.classProperty.call(fixture)
        } catch (_: Exception) {
            return null
        }
        return (raw as? uk.me.cormack.lighting7.fixture.dmx.DmxSlider)?.max
    }
}

// --- Small helpers that turn the existing throwing lookups into nullable returns.

private fun Fixtures.tryUntypedFixture(key: String): Fixture? = try {
    untypedFixture(key)
} catch (_: Exception) { null }

private fun Fixtures.tryUntypedGroup(name: String): uk.me.cormack.lighting7.fixture.group.FixtureGroup<*>? = try {
    untypedGroup(name)
} catch (_: Exception) { null }
