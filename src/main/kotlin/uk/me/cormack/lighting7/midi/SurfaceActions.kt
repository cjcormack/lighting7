package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.BuskPressService
import uk.me.cormack.lighting7.routes.familyOf
import uk.me.cormack.lighting7.routes.isGenericTemplate
import uk.me.cormack.lighting7.routes.LookTargetResolution
import uk.me.cormack.lighting7.routes.resolveLookToggleTargets
import uk.me.cormack.lighting7.routes.toggleSource
import uk.me.cormack.lighting7.routes.toggleLocate
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID

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

    /**
     * Cue and stack actions take the binding's uuid beside its int. A uuid, when present, is the
     * only thing consulted — it is what survives a clone — and a uuid that resolves to nothing in
     * the current project **drops** the press rather than falling back to an int that may name
     * another project's row. Only a pre-v11 row with no uuid is dispatched by int.
     */
    fun cueStackGo(stackId: Int, stackUuid: String? = null)
    fun cueStackBack(stackId: Int, stackUuid: String? = null)
    fun cueStackPause(stackId: Int, stackUuid: String? = null)
    fun fireCue(cueId: Int, cueUuid: String? = null)

    fun toggleBlackout(): Boolean
    fun toggleGrandMaster(): Boolean

    /**
     * Set a speed master's tempo from a continuous control, scaling 0..127 across
     * [minBpm]..[maxBpm]. [masterUuid] null → master 1.
     */
    fun writeSpeedMasterBpm(masterUuid: String?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte)

    /** Tap a speed master's tempo ([masterUuid] null → master 1). */
    fun tapSpeedMaster(masterUuid: String?)

    /**
     * Write a continuous value to [propertyName] on every target in the desk selection
     * ([uk.me.cormack.lighting7.state.DeskSelection]) — a group fanned to its members. An empty
     * selection drops the write with a debug log; it is never widened to "everything".
     */
    fun writeSelectionProperty(propertyName: String, midiValue7Bit: UByte)

    /** Toggle [target] in, or replace the selection with it, per [mode]. */
    fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode)

    fun clearSelection()

    /**
     * Locate every selected target, or release them all when every one is already located —
     * the same toggle `POST /locate/toggle` makes, once per target.
     */
    fun locateSelection()

    /**
     * Press a Look onto **its own fixtures** — never the selection (D6). A Look that has gained a
     * deferred effect since it was bound has no own targets, so the press is dropped; health has
     * already marked it and the router's dead gate drops it before this is reached.
     */
    fun applyLook(lookUuid: String)

    /**
     * Press a template onto the **desk selection**. A generic template with nothing selected is
     * dropped, exactly as its busk pad refuses; a per-fixture one presses on its own heads. The
     * family mask is derived from the template's rows, never sent.
     */
    fun pressTemplate(templateUuid: String)

    /** Press a busk pad — its own bank's plan, solo siblings included, on the desk selection. */
    fun pressPad(padUuid: String)

    /** Move the desk's showing busk page by one, wrapping. */
    fun buskPageStep(delta: Int)

    /** Show one named busk page. */
    fun buskPageSet(pageUuid: String)
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
        fxEngine.programmer.writeProperty(ProgrammerOwner.SURFACE, fixture, propertyName, value)
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
                ProgrammerWriter.PropertyWrite(member, propertyName, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.programmer.writeProperties(ProgrammerOwner.SURFACE, writes)
    }

    override fun flashFixturePropertyPress(fixtureKey: String, propertyName: String, max: UByte) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        val value = PropertyChannelResolver.flashPropertyValue(fixture, propertyName, max) ?: return
        // Momentary owner: don't absorb the sideband — release must reveal what was under it.
        fxEngine.programmer.writeProperty(
            ProgrammerOwner.FLASH, fixture, propertyName, value, absorbSideband = false,
        )
    }

    override fun flashGroupPropertyPress(groupName: String, propertyName: String, max: UByte) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        // Clamp per member — slider max can differ across heterogeneous group members.
        val writes = group.fixtures.filterIsInstance<Fixture>().mapNotNull { member ->
            PropertyChannelResolver.flashPropertyValue(member, propertyName, max)?.let {
                ProgrammerWriter.PropertyWrite(member, propertyName, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.programmer.writeProperties(ProgrammerOwner.FLASH, writes, absorbSideband = false)
    }

    override fun flashFixturePropertyRelease(fixtureKey: String, propertyName: String) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        // Release pops only the FLASH slot: the property cascades to the surviving owner
        // underneath (fader/busk level), then the cue layer, then baseline — in one
        // transaction, skipping keys a running effect covers.
        fxEngine.programmer.clearProperty(ProgrammerOwner.FLASH, fixture, propertyName)
    }

    override fun flashGroupPropertyRelease(groupName: String, propertyName: String) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        fxEngine.programmer.clearGroupProperty(ProgrammerOwner.FLASH, group, propertyName)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGo(stackId: Int, stackUuid: String?) {
        val stackId = resolveStackId(stackId, stackUuid) ?: return
        try {
            val result = cueStackManager.go(state, stackId, GlobalScope)
            if (result != null) state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface GO failed for stack $stackId: ${e.message}")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackBack(stackId: Int, stackUuid: String?) {
        val stackId = resolveStackId(stackId, stackUuid) ?: return
        try {
            val result = cueStackManager.advanceStack(state, stackId, CueStackManager.AdvanceDirection.BACKWARD, GlobalScope)
            if (result != null) state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface BACK failed for stack $stackId: ${e.message}")
        }
    }

    override fun cueStackPause(stackId: Int, stackUuid: String?) {
        val stackId = resolveStackId(stackId, stackUuid) ?: return
        try {
            cueStackManager.pauseAutoAdvance(state, stackId)
        } catch (e: Exception) {
            logger.warn("Surface PAUSE failed for stack $stackId: ${e.message}")
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun fireCue(cueId: Int, cueUuid: String?) {
        val cueId = resolveCueId(cueId, cueUuid) ?: return
        try {
            cueStackManager.fireCue(state, cueId, GlobalScope)
            state.show.fixtures.cueStackListChanged()
        } catch (e: Exception) {
            logger.warn("Surface FIRE CUE failed for $cueId: ${e.message}")
        }
    }

    override fun toggleBlackout(): Boolean = globalScalerState.toggleBlackout()
    override fun toggleGrandMaster(): Boolean = globalScalerState.toggleGrandMaster()

    /**
     * Uuid first, and never the int when a uuid is present: a binding cloned from another project
     * carries that project's int, and the health evaluator already marks a uuid that resolves to
     * nothing as dead. The lookup is one indexed read per press — button rate, not fader rate.
     */
    private fun resolveCueId(cueId: Int, cueUuid: String?): Int? {
        if (cueUuid == null) return cueId
        val uuid = uuidOrNull(cueUuid) ?: run {
            logger.warn("Surface FIRE CUE dropped: binding cue uuid '$cueUuid' is not a uuid")
            return null
        }
        val projectId = currentProjectId() ?: return null
        val resolved = transaction(state.database) {
            DaoCue.find { (DaoCues.uuid eq uuid) and (DaoCues.project eq projectId) }.firstOrNull()?.id?.value
        }
        if (resolved == null) logger.warn("Surface FIRE CUE dropped: no cue $cueUuid in project $projectId")
        return resolved
    }

    private fun resolveStackId(stackId: Int, stackUuid: String?): Int? {
        if (stackUuid == null) return stackId
        val uuid = uuidOrNull(stackUuid) ?: run {
            logger.warn("Surface stack action dropped: binding stack uuid '$stackUuid' is not a uuid")
            return null
        }
        val projectId = currentProjectId() ?: return null
        val resolved = transaction(state.database) {
            DaoCueStack.find { (DaoCueStacks.uuid eq uuid) and (DaoCueStacks.project eq projectId) }
                .firstOrNull()?.id?.value
        }
        if (resolved == null) logger.warn("Surface stack action dropped: no stack $stackUuid in project $projectId")
        return resolved
    }

    private fun currentProjectId(): Int? = try {
        state.projectManager.currentProject.id.value
    } catch (e: Exception) {
        logger.debug("Surface cue action dropped: no current project ({})", e.message)
        null
    }

    private fun uuidOrNull(raw: String): UUID? = try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        null
    }

    override fun writeSelectionProperty(propertyName: String, midiValue7Bit: UByte) {
        val targets = state.deskSelection.targets.value
        if (targets.isEmpty()) {
            logger.debug("Surface selection write of '{}' dropped: nothing selected", propertyName)
            return
        }
        val writes = SelectionWrites.forTargets(fixtures, targets, propertyName, midiValue7Bit)
        if (writes.isEmpty()) return
        fxEngine.programmer.writeProperties(ProgrammerOwner.SURFACE, writes)
    }

    override fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode) {
        when (mode) {
            BindingTarget.SelectMode.TOGGLE -> state.deskSelection.toggle(target)
            BindingTarget.SelectMode.REPLACE -> state.deskSelection.set(listOf(target))
        }
    }

    override fun clearSelection() = state.deskSelection.clear()

    override fun locateSelection() {
        val targets = state.deskSelection.targets.value.mapNotNull { TargetRef.ofOrNull(it.type, it.key) }
        if (targets.isEmpty()) {
            logger.debug("Surface locate dropped: nothing selected")
            return
        }
        val located = state.show.locateManager.activeTargets.value
        val allLocated = targets.all { it in located }
        for (target in targets) {
            // All on → all off; otherwise bring the unlocated ones up and leave the rest lit.
            if (allLocated || target !in located) toggleLocate(state, target)
        }
    }

    /**
     * A Look onto its own fixtures, through the same `ProgrammerLayerStack.toggle` the toggle route
     * uses — with **no targets**, which is exactly what makes `resolveLookToggleTargets` fall back
     * to the Look's own. A refusal there (a deferred effect, or nothing patched left) is logged: a
     * MIDI press has no reply channel, so the log line is the only trace the operator gets.
     */
    override fun applyLook(lookUuid: String) {
        val uuid = uuidOrNull(lookUuid) ?: run {
            logger.warn("Surface applyLook dropped: '{}' is not a uuid", lookUuid)
            return
        }
        val projectId = currentProjectId() ?: return
        val source = transaction(state.database) {
            DaoLook.find { (DaoLooks.uuid eq uuid) and (DaoLooks.project eq projectId) }
                .firstOrNull()
                ?.toggleSource(state.show.fixtures)
        }
        if (source == null) {
            logger.warn("Surface applyLook dropped: no Look {} in project {}", lookUuid, projectId)
            return
        }
        when (val resolved = resolveLookToggleTargets(emptyList(), source)) {
            is LookTargetResolution.Refused ->
                logger.warn("Surface applyLook dropped: {}", resolved.message)
            is LookTargetResolution.Targets -> runCatching {
                state.show.programmerLayerStack.toggle(source = source.source, targets = resolved.targets)
            }.onFailure { logger.warn("Surface applyLook failed: {}", it.message) }
        }
    }

    /**
     * A template onto the desk selection, through `toggle` with the template's own derived family
     * mask — the server derives it because which family a template layer belongs to is a fact about
     * the template, not about the press (the ⌥click rule, `CLAUDE.md` §The two apply gestures).
     * Siblingless: a button is not in a bank.
     */
    override fun pressTemplate(templateUuid: String) {
        val uuid = uuidOrNull(templateUuid) ?: run {
            logger.warn("Surface pressTemplate dropped: '{}' is not a uuid", templateUuid)
            return
        }
        val projectId = currentProjectId() ?: return
        val press = transaction(state.database) {
            DaoTemplate.find { (DaoTemplates.uuid eq uuid) and (DaoTemplates.project eq projectId) }
                .firstOrNull()
                ?.let { t ->
                    Triple(
                        LayerSource.template(t.id.value, t.uuid, t.name),
                        t.familyOf()?.name,
                        t.isGenericTemplate(),
                    )
                }
        }
        if (press == null) {
            logger.warn("Surface pressTemplate dropped: no template {} in project {}", templateUuid, projectId)
            return
        }
        val (source, family, isGeneric) = press
        val targets = state.deskSelection.targets.value
        if (isGeneric && targets.isEmpty()) {
            logger.debug("Surface pressTemplate of '{}' dropped: nothing selected", source.name)
            return
        }
        runCatching {
            state.show.programmerLayerStack.toggle(source = source, targets = targets, propertyMask = family)
        }.onFailure { logger.warn("Surface pressTemplate failed: {}", it.message) }
    }

    /**
     * A busk pad, through [BuskPressService] — the same press the busk view makes, so the solo
     * rules, the empty-selection refusals and the cue toggle cannot diverge between the two.
     */
    override fun pressPad(padUuid: String) {
        val uuid = uuidOrNull(padUuid) ?: run {
            logger.warn("Surface pressPad dropped: '{}' is not a uuid", padUuid)
            return
        }
        val projectId = currentProjectId() ?: return
        when (val outcome = BuskPressService.pressByUuid(state, projectId, uuid, state.deskSelection.targets.value)) {
            is BuskPressService.Outcome.Pressed -> {}
            is BuskPressService.Outcome.Refused -> logger.warn("Surface pressPad dropped: {}", outcome.message)
            is BuskPressService.Outcome.TargetMissing -> logger.warn("Surface pressPad dropped: {}", outcome.message)
            BuskPressService.Outcome.NotFound ->
                logger.warn("Surface pressPad dropped: no busk pad {} in project {}", padUuid, projectId)
        }
    }

    override fun buskPageStep(delta: Int) = state.buskPageState.step(delta)

    override fun buskPageSet(pageUuid: String) {
        val uuid = uuidOrNull(pageUuid) ?: run {
            logger.warn("Surface buskPageSet dropped: '{}' is not a uuid", pageUuid)
            return
        }
        state.buskPageState.setByUuid(uuid)
    }

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
     *
     * Every dropped or refused write is logged — this surface has no reply channel, so the
     * log line is the only trace a MIDI-only operator gets of why their TAP/knob did nothing.
     * Throttled: a knob bound to a follower sends CC at ~100 msg/s, and one line per message
     * would flood the log for as long as the operator keeps turning it.
     */
    private inline fun withSpeedMasterTarget(
        raw: String?,
        write: (java.util.UUID?) -> SpeedMasterBank.TempoWriteOutcome,
    ) {
        val outcome = if (raw == null) {
            write(null)
        } else {
            val uuid = speedMasterUuidOrNull(raw)
            if (uuid == null) {
                warnTempoWriteDropped("Surface tempo write dropped: binding target '$raw' is not a uuid")
                return
            }
            write(uuid)
        }
        when (outcome) {
            is SpeedMasterBank.TempoWriteOutcome.Applied -> {}

            is SpeedMasterBank.TempoWriteOutcome.UnknownMaster -> warnTempoWriteDropped(
                "Surface tempo write dropped: no speed master with uuid '$raw' — " +
                    "re-learn the binding if the master was deleted",
            )

            is SpeedMasterBank.TempoWriteOutcome.RefusedFollower -> warnTempoWriteDropped(
                "Surface tempo write refused: ${outcome.describe}",
            )
        }
    }

    @Volatile
    private var lastTempoDropWarnMs = 0L

    /** One WARN per [TEMPO_DROP_WARN_INTERVAL_MS] window, not one per MIDI message. */
    private fun warnTempoWriteDropped(message: String) {
        val now = System.currentTimeMillis()
        if (now - lastTempoDropWarnMs < TEMPO_DROP_WARN_INTERVAL_MS) return
        lastTempoDropWarnMs = now
        logger.warn(message)
    }
}

private const val TEMPO_DROP_WARN_INTERVAL_MS = 5_000L

// --- Small helpers that turn the existing throwing lookups into nullable returns.

private fun Fixtures.tryUntypedFixture(key: String): Fixture? = try {
    untypedFixture(key)
} catch (_: Exception) { null }

private fun Fixtures.tryUntypedGroup(name: String): uk.me.cormack.lighting7.fixture.group.FixtureGroup<*>? = try {
    untypedGroup(name)
} catch (_: Exception) { null }
