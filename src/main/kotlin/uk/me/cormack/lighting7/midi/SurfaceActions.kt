package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.show.Fixtures

/**
 * Port between [SurfaceInputRouter] and the rest of the application. Production wires this
 * to [DefaultSurfaceActions], which delegates into [Fixtures], the programmer layer
 * ([ProgrammerStore] via [FxEngine]), [CueStackManager], and [GlobalScalerState]. Tests can
 * supply a recording fake so the router can be exercised without a running show.
 *
 * All methods are fire-and-forget from the router's perspective — implementations handle
 * their own thread coordination (the programmer store is lock-free; cue stack activation is
 * dispatched onto [GlobalScope] by [DefaultSurfaceActions]).
 */
interface SurfaceActions {
    /**
     * Write a continuous value (0..127 MIDI 7-bit) to a fixture property. The production
     * implementation writes a programmer entry — a cue is read-only from a surface, and is
     * edited by Include / Update like every other authoring path.
     */
    fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte)

    /** Group variant of [writeFixtureProperty], fanned out per member. */
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

    /**
     * Set a speed master's tempo from a continuous control, scaling 0..127 across
     * [minBpm]..[maxBpm]. [masterUuid] null → master 1.
     */
    fun writeSpeedMasterBpm(masterUuid: String?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte)

    /** Tap a speed master's tempo ([masterUuid] null → master 1). */
    fun tapSpeedMaster(masterUuid: String?)
}

/**
 * Production [SurfaceActions] implementation. Wraps the show's services and writes through
 * the FX engine's programmer API, which stores the entry and publishes the composed cascade
 * to the DMX controller in one step — there is no separate raw controller write.
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
    private val fxEngine get() = state.show.fxEngine
    private val cueStackManager: CueStackManager get() = state.show.cueStackManager
    private val globalScalerState: GlobalScalerState get() = state.show.globalScalerState
    private val speedMasters: SpeedMasterBank get() = state.show.speedMasterBank

    override fun writeFixtureProperty(fixtureKey: String, propertyName: String, midiValue7Bit: UByte) {
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) {
            logger.debug("Surface write: fixture '{}' not found", fixtureKey)
            return
        }
        val value = PropertyChannelResolver.toPropertyValue(fixture, propertyName, midiValue7Bit) ?: run {
            logger.debug("Surface write: property '{}' on '{}' not fader-writable", propertyName, fixtureKey)
            return
        }
        fxEngine.writeProgrammerProperty(ProgrammerOwner.SURFACE, fixture, propertyName, value)
    }

    override fun writeGroupProperty(groupName: String, propertyName: String, midiValue7Bit: UByte) {
        val group = try {
            fixtures.untypedGroup(groupName)
        } catch (_: Exception) {
            logger.debug("Surface write: group '{}' not found", groupName)
            return
        }
        // Convert per member — sliders scale through each member's own min..max sub-range.
        val writes = group.fixtures.filterIsInstance<Fixture>().mapNotNull { member ->
            PropertyChannelResolver.toPropertyValue(member, propertyName, midiValue7Bit)?.let {
                FxEngine.ProgrammerPropertyWrite(member, propertyName, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.writeProgrammerProperties(ProgrammerOwner.SURFACE, writes)
    }

    override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        val value = PropertyChannelResolver.flashPropertyValue(fixture, propertyName, max) ?: return
        // Momentary owner: don't absorb the sideband — release must reveal what was under it.
        fxEngine.writeProgrammerProperty(
            ProgrammerOwner.FLASH, fixture, propertyName, value, absorbSideband = false,
        )
    }

    override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        // Clamp per member — slider max can differ across heterogeneous group members.
        val writes = group.fixtures.filterIsInstance<Fixture>().mapNotNull { member ->
            PropertyChannelResolver.flashPropertyValue(member, propertyName, max)?.let {
                FxEngine.ProgrammerPropertyWrite(member, propertyName, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.writeProgrammerProperties(ProgrammerOwner.FLASH, writes, absorbSideband = false)
    }

    override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        // Release pops only the FLASH slot: the property cascades to the surviving owner
        // underneath (fader/busk level), then the cue layer, then baseline — in one
        // transaction, skipping keys a running effect covers.
        fxEngine.clearProgrammerProperty(ProgrammerOwner.FLASH, fixture, propertyName)
    }

    override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        fxEngine.clearProgrammerGroupProperty(ProgrammerOwner.FLASH, group, propertyName)
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
            cueStackManager.pauseAutoAdvance(state, stackId)
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

    override fun writeSpeedMasterBpm(
        masterUuid: String?,
        minBpm: Double,
        maxBpm: Double,
        midiValue7Bit: UByte,
    ) = withSpeedMasterTarget(masterUuid) { uuid ->
        val bpm = minBpm + (maxBpm - minBpm) * (midiValue7Bit.toInt() / 127.0)
        // MasterClock.setBpm coerces into 20..300 itself; the binding's window is a
        // sub-range on top of that, not a replacement for it.
        speedMasters.setBpm(uuid, bpm, SpeedMasterSource.MANUAL)
    }

    override fun tapSpeedMaster(masterUuid: String?) =
        withSpeedMasterTarget(masterUuid) { speedMasters.tap(it) }

    /**
     * Resolve a tempo-write target, mirroring `SpeedMasterSocket.withWriteTarget`: null or
     * absent means master 1, while a present-but-malformed uuid DROPS the write rather than
     * degrading to master 1 — a corrupt binding payload must not be able to retune the
     * global tempo. (An unknown-but-well-formed uuid is dropped one layer down, by the
     * bank's own write resolution.)
     */
    private inline fun withSpeedMasterTarget(raw: String?, write: (java.util.UUID?) -> Unit) {
        if (raw == null) {
            write(null)
            return
        }
        speedMasterUuidOrNull(raw)?.let(write)
    }
}

// --- Small helpers that turn the existing throwing lookups into nullable returns.

private fun Fixtures.tryUntypedFixture(key: String): Fixture? = try {
    untypedFixture(key)
} catch (_: Exception) { null }

private fun Fixtures.tryUntypedGroup(name: String): uk.me.cormack.lighting7.fixture.group.FixtureGroup<*>? = try {
    untypedGroup(name)
} catch (_: Exception) { null }
