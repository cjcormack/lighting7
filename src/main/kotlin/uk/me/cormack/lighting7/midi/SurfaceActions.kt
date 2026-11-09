package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fx.toggleLooseCue
import uk.me.cormack.lighting7.fx.takeLooseCue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.me.cormack.lighting7.history.refusal
import uk.me.cormack.lighting7.history.HistoryMove
import uk.me.cormack.lighting7.history.HistoryMoveResult
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.history.RecordRestorer
import uk.me.cormack.lighting7.history.WriteOrigin
import uk.me.cormack.lighting7.history.moveHistory
import kotlin.coroutines.EmptyCoroutineContext
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntries
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LayerSourceKind
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.AddPadOutcome
import uk.me.cormack.lighting7.routes.BuskPressService
import uk.me.cormack.lighting7.routes.HandService
import uk.me.cormack.lighting7.routes.familyOf
import uk.me.cormack.lighting7.routes.isGenericPalette
import uk.me.cormack.lighting7.routes.pressWouldRelease
import uk.me.cormack.lighting7.routes.PalettePressLog
import uk.me.cormack.lighting7.routes.paletteOutsideMaskMessage
import uk.me.cormack.lighting7.routes.layerSource
import uk.me.cormack.lighting7.routes.toggleLocate
import uk.me.cormack.lighting7.routes.CODE_VENUE_RECORD_OFF
import uk.me.cormack.lighting7.routes.RecordCoreResult
import uk.me.cormack.lighting7.routes.RecordMode
import uk.me.cormack.lighting7.routes.RecordSource
import uk.me.cormack.lighting7.routes.UpdateCoreResult
import uk.me.cormack.lighting7.routes.clearProgrammerCompletely
import uk.me.cormack.lighting7.routes.performProgrammerRecord
import uk.me.cormack.lighting7.routes.performProgrammerUpdate
import uk.me.cormack.lighting7.routes.setProgrammerBlind
import uk.me.cormack.lighting7.plugins.ProgrammerClearTargetInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerHandler
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.routes.isVenueLive
import uk.me.cormack.lighting7.history.historyLabel
import kotlinx.coroutines.CancellationException
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.state.SubselectMode
import uk.me.cormack.lighting7.state.WindowRegistry
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Port between [SurfaceDispatch] and the rest of the application: what a surface function does,
 * in the words of the show's services. Production wires this to [DefaultSurfaceActions]; tests
 * supply a recording fake so dispatch can be exercised without a running show.
 *
 * Records are named by uuid only (surface-setup plan D2): a uuid that resolves to nothing in the
 * current project drops the press. The actions an OSC address reaches answer an [ActionOutcome],
 * which the router logs and the OSC log shows; the rest are fire-and-forget.
 */
interface SurfaceActions {
    /**
     * Write a continuous value (0..127) to [attribute] on every head [on] reaches ([Heads.of]) — a
     * programmer entry, owner `SURFACE`. [colourAxis] is which axis of a colour the value moves (null
     * is hue). An empty selection drops the write; it is never widened to "everything".
     */
    fun writeLevel(on: Subject, attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis? = null)

    /**
     * Flash [attribute] on the heads [on] reaches at [percent] of each head's range, in the momentary
     * owner. Answers the release that clears exactly those heads, or null when it wrote nothing.
     */
    fun flashPress(on: Subject, attribute: String, percent: Int): (() -> Unit)?

    fun cueStackGo(stack: UUID): ActionOutcome
    fun cueStackBack(stack: UUID): ActionOutcome
    fun cueStackPause(stack: UUID): ActionOutcome

    /** GO TO [entry], starting its stack if stopped; nothing if that entry is already live (D4). */
    fun cueStackGoTo(entry: UUID): ActionOutcome

    /** Arm [entry] as its stack's next GO. */
    fun cueStackStandby(entry: UUID): ActionOutcome

    /**
     * Stop [stack] as an operator does, its cues fading over its release time (busk-stack plan D6),
     * as the REST deactivate does. It drops the desk's arm, running or stopped, except on a stack whose
     * release is still running, which the press leaves as it is.
     */
    fun cueStackStop(stack: UUID): ActionOutcome

    /** Toggle [cue]'s loose hold. */
    fun fireCue(cue: UUID): ActionOutcome

    /** Take or let go of [cue]'s loose hold; nothing if it already is so (D4). */
    fun looseCueSet(cue: UUID, on: Boolean): ActionOutcome

    /**
     * Fire one tube of a one-shot fixture (stage-view plan session 9): through the effects service,
     * which needs the desk's arm and announces a refused press. A default so a test double that does
     * not care about cannons need not say so.
     */
    fun fireTrigger(fixtureKey: String, trigger: String) {}

    fun toggleBlackout(): Boolean
    fun toggleGrandMaster(): Boolean

    /** Set blackout [on] or off; already so is done, D4's no-op. */
    fun setBlackout(on: Boolean): ActionOutcome

    /** Set the grand master's level taken whole, [percent] 0–100 (OSC plan D10). */
    fun setGrandMasterLevel(percent: Double): ActionOutcome

    /** Set the grand master's level from a continuous control, 0..127 across 0–100 %. */
    fun writeGrandMaster(midiValue7Bit: UByte)

    /** Set [stack]'s FX amount from a continuous control, 0..127 across 0–100 %. */
    fun writeStackFxAmount(stack: UUID, midiValue7Bit: UByte) {}

    /** [stack]'s FX amount taken whole, 0–1, from a sender with floats (OSC plan D10). */
    fun setStackFxAmount(stack: UUID, amount: Double): ActionOutcome

    /**
     * Set a speed master's tempo from a continuous control, scaling 0..127 across
     * [minBpm]..[maxBpm]. [master] null → master 1.
     */
    fun writeSpeedMasterBpm(master: UUID?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte)

    /** Tap a speed master's tempo ([master] null → master 1). */
    fun tapSpeedMaster(master: UUID?): ActionOutcome

    /**
     * Set a speed master's tempo taken whole ([master] null → master 1), 20–300 BPM: a typed
     * tempo's write, so on a held master it moves the hold, and a follower refuses it.
     */
    fun setSpeedMasterBpm(master: UUID?, bpm: Double): ActionOutcome

    /** Halve or double a speed master's tempo ([master] null → master 1), fx-speed plan D12. */
    fun scaleSpeedMaster(master: UUID?, factor: Double) {}

    /** One turn of a fan encoder on [axis] over [on], the selection or a group — see [SelectionFan]. */
    fun writeFan(on: Subject, axis: FanAxis, midiValue7Bit: UByte)

    /**
     * One move of an *FX speed* control over the Local FX painting [on]'s heads — see [SelectionFxSpeed].
     * [encoder] is whether the control is an encoder (detents) or a fader (a position).
     */
    fun writeFxSpeed(on: Subject, midiValue7Bit: UByte, encoder: Boolean) {}

    /** Toggle [target] in, or with [replace] replace the selection with it. */
    fun selectTarget(target: CueTargetDto, replace: Boolean)

    fun clearSelection()

    /**
     * Locate every target [on] names — the selection's, or the one group or fixture — or release
     * them all when every one is already located: the toggle `POST /locate/toggle` makes, once per
     * target.
     */
    fun locate(on: Subject)

    /** Clear the whole programmer over [fadeMs], as its Clear does. */
    fun clearProgrammer(fadeMs: Long)

    /** Whether the programmer is Blind. */
    fun isBlind(): Boolean

    /** Put the programmer into Blind or take it out, over [fadeMs]; already so is done (D4). */
    fun setBlind(blind: Boolean, fadeMs: Long): ActionOutcome

    /** Release everything the programmer holds on each target [on] names, as its Release does, over [fadeMs]. */
    fun release(on: Subject, fadeMs: Long): ActionOutcome

    /**
     * Record the programmer as a new cue at the end of [stack], with the Record sheet's defaults:
     * every touched value, all attributes, FX, held scenery and held tempos. On the serial lane.
     */
    fun record(stack: UUID): ActionOutcome

    /** Write the programmer back into what Include loaded (Update's Mode A), on the serial lane; refused when nothing is included. */
    fun update(): ActionOutcome

    /** Press a Look, whole — never onto the selection (D6; absolute-looks plan D1). */
    fun applyLook(look: UUID): ActionOutcome

    /** A Look on whole, or every layer of it off; nothing if it already is so (D4). */
    fun lookSet(look: UUID, on: Boolean): ActionOutcome

    /**
     * Press a palette onto the **desk selection**. A generic palette with nothing selected is
     * dropped, exactly as its busk pad refuses; a per-fixture one presses on its own heads. The
     * family mask is derived from the palette's rows, never sent.
     */
    fun pressPalette(palette: UUID)

    /** Press a busk pad — its own bank's plan, solo siblings included, on the desk selection. */
    fun pressPad(pad: UUID): ActionOutcome

    /**
     * Put the record on a busk pad into the desk's hand, replacing whatever it held
     * (multi-screen plan §3.5).
     */
    fun pickUpPad(pad: UUID)

    /**
     * Place whatever the hand holds as a pad on one bank, then let go. An empty hand is a dropped
     * press. The append is the same one `POST /busk/banks/{bankId}/pads` makes — a place is an
     * *existing* mutation whichever door makes it (D12).
     */
    fun handPlaceInBank(bank: UUID)

    /** Let go of whatever the hand holds; a no-op on an empty hand. */
    fun handDrop()

    /**
     * The undo or redo key as [source], the surface's own (undo plan §4.8), or the programmer's top
     * step alone with [programmerOnly]. A refusal moves nothing and is logged.
     */
    fun historyMove(source: HistorySource, undo: Boolean, programmerOnly: Boolean = false)

    /** Move the desk's showing busk page by [delta], wrapping. */
    fun buskPageStep(delta: Int)

    /** Show one named busk page. */
    fun buskPageSet(page: UUID): ActionOutcome

    /**
     * Set the busk focus of every connected window named [windowName] through one
     * `windows.viewOptions` per row (busk-further plan D14). No row is a logged no-op.
     */
    fun buskFocusSet(windowName: String, focus: String)

    /** Fold or open the busk side sheet of every connected window named [windowName]. */
    fun buskSheetToggle(windowName: String)

    /** Rewrite the desk selection's targets by [mode] (`DeskSelection.subselect`). */
    fun subselect(mode: SubselectMode)
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

        /**
         * The serial lane: every surface's undo, redo, Record and Update, one at a time in press
         * order, off the input's thread, since each opens transactions or reloads.
         */
        private val historyDispatcher = Dispatchers.IO.limitedParallelism(1)
        private val historyMutex = Mutex()

        /** D4's no-op answers: the state asked for already held. */
        const val ALREADY_LIVE = "already live"
        const val ALREADY_ON = "already on"
        const val ALREADY_OFF = "already off"
        const val STAGED_BLIND = "staged: Blind"

        /** The busk view's option keys and the one verb, as the client's `buskWindow.ts` reads them. */
        const val VIEW_OPTION_FOCUS = "focus"
        const val VIEW_OPTION_SHEET = "sheet"
        const val VIEW_OPTION_TOGGLE = "toggle"
    }

    private val fixtures: Fixtures get() = state.show.fixtures
    private val fxEngine get() = state.show.fxEngine
    private val cueStackManager: CueStackManager get() = state.show.cueStackManager
    private val globalScalerState: GlobalScalerState get() = state.show.globalScalerState
    private val speedMasters: SpeedMasterBank get() = state.show.speedMasterBank

    /** The heads [on] reaches for [attribute], the desk selection read now. */
    private fun headsOf(on: Subject, attribute: String): List<Heads.Head> =
        Heads.of(fixtures, on, attribute) { state.deskSelection.state.value.targets }

    override fun writeLevel(on: Subject, attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        // The selection's attribute mask is ignored here on purpose: the control names its own
        // attribute, as a cell edit is not masked by a marquee elsewhere (multi-screen plan §3.3).
        val heads = headsOf(on, attribute)
        if (heads.isEmpty()) {
            logger.debug("Surface write of '{}' on {} dropped: no head", attribute, on)
            return
        }
        // Per head: sliders scale through each head's own min..max, and a colour axis or the other
        // position axis builds on what the head reads back as — Local over the cue layer, the staged
        // value in Blind (D6) — so a move lands where the control showed it.
        val resolver = state.show.layerResolver
        val writes = heads.mapNotNull { head ->
            val current = AttributeChannelResolver.composedTarget(head.fixture, attribute)
                ?.let { resolver.localOver(it, head.fixture, head.fixture.targetKey) }
            AttributeChannelResolver.surfaceWrite(head.fixture, attribute, midiValue7Bit, current, colourAxis)?.let {
                ProgrammerWriter.AttributeWrite(head.fixture, it.attribute, it.value, sourceGroup = head.sourceGroup)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.programmer.writeAttributes(ProgrammerOwner.SURFACE, writes)
    }

    override fun flashPress(on: Subject, attribute: String, percent: Int): (() -> Unit)? {
        val writes = headsOf(on, attribute).mapNotNull { head ->
            AttributeChannelResolver.flashAttributeValue(head.fixture, attribute, percent)?.let {
                ProgrammerWriter.AttributeWrite(head.fixture, attribute, it, sourceGroup = head.sourceGroup)
            }
        }
        if (writes.isEmpty()) return null
        // Momentary owner: don't absorb the sideband — release must reveal what was under it.
        fxEngine.programmer.writeAttributes(ProgrammerOwner.FLASH, writes, absorbSideband = false)
        // The release pops only the FLASH slot of the heads this press wrote, whatever the subject
        // reaches by then: each cascades to the owner underneath, then the cue layer, then baseline.
        // Heads and programmer are looked up again on release, which a reload may have replaced.
        val written = writes.map { it.fixture.targetKey to it.attribute }
        return {
            val clears = written.mapNotNull { (key, attribute) ->
                runCatching { fixtures.untypedGroupableFixture(key) }.getOrNull()?.let { it to attribute }
            }
            fxEngine.programmer.clearAttributes(ProgrammerOwner.FLASH, clears)
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGo(stack: UUID): ActionOutcome {
        val ref = resolveStack(stack) ?: return stackNotFound(stack)
        val result = try {
            cueStackManager.go(state, ref.id, GlobalScope)
        } catch (e: IllegalArgumentException) {
            return ActionOutcome.Refused(ActionOutcome.GO_REFUSED, "${ref.name}: ${e.message}")
        }
        if (result == null) return ActionOutcome.Refused(ActionOutcome.STACK_EMPTY, "${ref.name} has no cue to GO to")
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackBack(stack: UUID): ActionOutcome {
        val ref = resolveStack(stack) ?: return stackNotFound(stack)
        val result = try {
            cueStackManager.advanceStack(state, ref.id, CueStackManager.AdvanceDirection.BACKWARD, GlobalScope)
        } catch (e: IllegalArgumentException) {
            return ActionOutcome.Refused(ActionOutcome.GO_REFUSED, "${ref.name}: ${e.message}")
        }
        if (result == null) return ActionOutcome.Done("${ref.name} is not running")
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    override fun cueStackPause(stack: UUID): ActionOutcome {
        val ref = resolveStack(stack) ?: return stackNotFound(stack)
        return if (cueStackManager.pauseAutoAdvance(state, ref.id)) ActionOutcome.DONE
        else ActionOutcome.Done("no follow was running")
    }

    override fun fireCue(cue: UUID): ActionOutcome {
        val cueId = resolveCueId(cue) ?: return cueNotFound(cue)
        // A press toggles the cue's loose hold (cue model plan D6, D11), as a busk cue pad does:
        // it names the cue and no stack, and a second press lets it go.
        val (on, _) = cueStackManager.holds.toggleLooseCue(state, cueId) ?: return cueNotFound(cue)
        return ActionOutcome.Done(if (on) "on" else "off")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGoTo(entry: UUID): ActionOutcome {
        val (stack, entryId) = resolveEntry(entry).let { it.second ?: return it.first!! }
        if (cueStackManager.getActiveEntryId(stack.id) == entryId) return ActionOutcome.Done(ALREADY_LIVE)
        cueStackManager.goToEntry(state, stack.id, entryId, GlobalScope)
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    override fun cueStackStandby(entry: UUID): ActionOutcome {
        val (stack, entryId) = resolveEntry(entry).let { it.second ?: return it.first!! }
        cueStackManager.runState.setStandby(state, stack.id, entryId)
        return ActionOutcome.DONE
    }

    override fun cueStackStop(stack: UUID): ActionOutcome {
        val ref = resolveStack(stack) ?: return stackNotFound(stack)
        val running = cueStackManager.isStackActive(ref.id)
        cueStackManager.stopStack(state, ref.id)
        state.show.fixtures.cueStackListChanged()
        return if (running) ActionOutcome.DONE else ActionOutcome.Done("was not running")
    }

    override fun looseCueSet(cue: UUID, on: Boolean): ActionOutcome {
        val cueId = resolveCueId(cue) ?: return cueNotFound(cue)
        val holds = cueStackManager.holds
        if (holds.looseHeld(cueId) == on) return ActionOutcome.Done(if (on) ALREADY_ON else ALREADY_OFF)
        if (on) {
            holds.takeLooseCue(state, cueId) ?: return cueNotFound(cue)
        } else {
            holds.dropLoose(state, cueId)
        }
        return ActionOutcome.DONE
    }

    override fun fireTrigger(fixtureKey: String, trigger: String) {
        try {
            state.effectsService.fireFromSurface(fixtureKey, trigger)
        } catch (e: Exception) {
            logger.warn("Surface FIRE TRIGGER failed for $fixtureKey/$trigger: ${e.message}")
        }
    }

    override fun toggleBlackout(): Boolean = globalScalerState.toggleBlackout()
    override fun toggleGrandMaster(): Boolean = globalScalerState.toggleGrandMaster()

    override fun setBlackout(on: Boolean): ActionOutcome =
        if (globalScalerState.setBlackout(on)) ActionOutcome.Done(if (on) "on" else "off")
        else ActionOutcome.Done(if (on) "already on" else "already off")

    override fun setGrandMasterLevel(percent: Double): ActionOutcome {
        val scaler = globalScalerState
        scaler.setGrandMasterLevel(percent.coerceIn(0.0, 100.0))
        return ActionOutcome.Done("${scaler.grandMasterLevel.value.roundToInt()} %")
    }

    override fun writeGrandMaster(midiValue7Bit: UByte) =
        globalScalerState.setGrandMasterLevel(Scale7Bit.PERCENT.of7(midiValue7Bit))

    // A fader moves at control rate, so a stack it cannot find is not logged per move: health says so.
    override fun writeStackFxAmount(stack: UUID, midiValue7Bit: UByte) {
        val resolved = cueStackManager.stackIdForUuid(state.database, stack.toString()) ?: return
        cueStackManager.setFxAmount(resolved, Scale7Bit.UNIT.of7(midiValue7Bit))
    }

    override fun setStackFxAmount(stack: UUID, amount: Double): ActionOutcome {
        val resolved = cueStackManager.stackIdForUuid(state.database, stack.toString()) ?: return stackNotFound(stack)
        val held = cueStackManager.setFxAmount(resolved, amount.coerceIn(0.0, 1.0))
        return ActionOutcome.Done("${(held * 100).roundToInt()} %")
    }

    /** A stack this action names: its id and its name, for an outcome's words. */
    private data class StackRef(val id: Int, val name: String)

    /** [cue]'s id in the current project, else null. One indexed read per press — button rate, not fader rate. */
    private fun resolveCueId(cue: UUID): Int? {
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            DaoCue.find { (DaoCues.uuid eq cue) and (DaoCues.project eq projectId) }.firstOrNull()?.id?.value
        }
    }

    /** [stack]'s stack in the current project, else null. */
    private fun resolveStack(stack: UUID): StackRef? {
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            DaoCueStack.find { (DaoCueStacks.uuid eq stack) and (DaoCueStacks.project eq projectId) }.firstOrNull()
                ?.let { StackRef(it.id.value, it.name) }
        }
    }

    /** A cue entry by its uuid, with its stack in the current project, or the outcome that says why there is none. */
    private fun resolveEntry(entry: UUID): Pair<ActionOutcome?, Pair<StackRef, Int>?> {
        val projectId = currentProjectId() ?: return ActionOutcome.NotFound("No current project") to null
        val found = transaction(state.database) {
            DaoCueStackEntry.find { DaoCueStackEntries.uuid eq entry }.firstOrNull()
                ?.takeIf { it.stack.project.id.value == projectId }
                ?.let { Triple(StackRef(it.stackId, it.stack.name), it.id.value, it.isMarker) }
        } ?: return ActionOutcome.NotFound("No cue entry $entry in this show") to null
        val (stack, entryId, marker) = found
        if (marker) return ActionOutcome.Refused(ActionOutcome.NOT_A_CUE, "That entry of ${stack.name} is a marker") to null
        return null to (stack to entryId)
    }

    private fun stackNotFound(stack: UUID): ActionOutcome {
        logger.warn("Surface stack action dropped: no stack {} in the current project", stack)
        return ActionOutcome.NotFound("No stack $stack in this show")
    }

    private fun cueNotFound(cue: UUID): ActionOutcome {
        logger.warn("Surface cue action dropped: no cue {} in the current project", cue)
        return ActionOutcome.NotFound("No cue $cue in this show")
    }

    private fun currentProjectId(): Int? = try {
        state.projectManager.currentProject.id.value
    } catch (e: Exception) {
        logger.debug("Surface cue action dropped: no current project ({})", e.message)
        null
    }

    override fun writeFan(on: Subject, axis: FanAxis, midiValue7Bit: UByte) = state.selectionFan.turn(axis, midiValue7Bit, on)

    override fun writeFxSpeed(on: Subject, midiValue7Bit: UByte, encoder: Boolean) =
        state.selectionFxSpeed.turn(midiValue7Bit, encoder, on)

    override fun selectTarget(target: CueTargetDto, replace: Boolean) {
        // A toggle adds a head under whatever mask is standing; a replace has no column axis to speak
        // with, so it clears the mask by passing none (multi-screen plan D2).
        if (replace) state.deskSelection.set(listOf(target), source = SelectionSource.SURFACE)
        else state.deskSelection.toggle(target, SelectionSource.SURFACE)
    }

    override fun clearSelection() = state.deskSelection.clear()

    /** The targets [on] names: the desk selection's now, or the one group or fixture. */
    private fun targetsOf(on: Subject): List<CueTargetDto> = on.targets { state.deskSelection.state.value.targets }

    override fun locate(on: Subject) {
        val targets = targetsOf(on).mapNotNull { TargetRef.ofOrNull(it.type, it.key) }
        if (targets.isEmpty()) {
            logger.debug("Surface locate dropped: nothing to locate on {}", on)
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
     * A Look, whole, through the same `ProgrammerLayerStack.toggle` the toggle route uses: on puts
     * one layer of it on, off takes every layer of it off. A failure is logged: a MIDI press has no
     * reply channel, so the log line is the only trace the operator gets.
     */
    override fun applyLook(look: UUID): ActionOutcome {
        val source = lookSource(look) ?: return lookNotFound(look)
        val outcome = state.show.programmerLayerStack.toggle(source = source, targets = emptyList())
        return ActionOutcome.Done(if (outcome.action == "applied") withBlind("on") else "off")
    }

    override fun lookSet(look: UUID, on: Boolean): ActionOutcome {
        val source = lookSource(look) ?: return lookNotFound(look)
        val layers = state.show.programmerLayerStack
        val applied = state.show.programmerStore.layers.any { it.source.uuid == source.uuid && it.source.kind == LayerSourceKind.LOOK }
        if (applied == on) return ActionOutcome.Done(if (on) ALREADY_ON else ALREADY_OFF)
        if (on) layers.toggle(source = source, targets = emptyList()) else layers.release(setOf(source.uuid))
        return if (on && fxEngine.programmerStore.blind) ActionOutcome.Done(STAGED_BLIND) else ActionOutcome.DONE
    }

    /** The Look [look] names in the current project, as the layer source a press puts on. */
    private fun lookSource(look: UUID): LayerSource? {
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            DaoLook.find { (DaoLooks.uuid eq look) and (DaoLooks.project eq projectId) }.firstOrNull()?.layerSource()
        }
    }

    private fun lookNotFound(look: UUID): ActionOutcome {
        logger.warn("Surface Look press dropped: no Look {} in the current project", look)
        return ActionOutcome.NotFound("No Look $look in this show")
    }

    /** A programmer write in Blind is staged, not sent — which the operator reading the log needs to know. */
    private fun withBlind(detail: String): String =
        if (fxEngine.programmerStore.blind) "$detail, $STAGED_BLIND" else detail

    /**
     * A template onto the desk selection, through `toggle` with the template's own derived family
     * mask — the server derives it because which family a template layer belongs to is a fact about
     * the template, not about the press (the press's rule, `frontend/CLAUDE.md` §"Applying to a selection").
     * No solo siblings: a button is not in a bank (the family replacement is `toggle`'s own). Under the desk selection's attribute mask the same
     * rule as every other door: a template whose family is outside it is refused by name, and the
     * refusal is a log line because a button has no reply channel (multi-screen plan D5).
     */
    override fun pressPalette(palette: UUID) {
        val projectId = currentProjectId() ?: return
        val press = transaction(state.database) {
            DaoPalette.find { (DaoPalettes.uuid eq palette) and (DaoPalettes.project eq projectId) }
                .firstOrNull()
                ?.let { t ->
                    Triple(
                        LayerSource.palette(t.id.value, t.uuid, t.name),
                        t.familyOf(),
                        t.isGenericPalette(),
                    )
                }
        }
        if (press == null) {
            logger.warn("Surface pressPalette dropped: no palette {} in project {}", palette, projectId)
            return
        }
        val (source, family, isGeneric) = press
        val selection = state.deskSelection.state.value
        val targets = selection.targets
        if (isGeneric && targets.isEmpty()) {
            logger.debug("Surface pressPalette of '{}' dropped: nothing selected", source.name)
            return
        }
        val mask = selection.families
        // The on arm only: an off press comes off under any mask (`pressWouldRelease`).
        if (mask != null && !maskAllows(mask, family) && !pressWouldRelease(state, source.uuid, targets)) {
            logger.warn("Surface pressPalette dropped: {}", paletteOutsideMaskMessage(source.name, family, mask))
            return
        }
        runCatching {
            state.show.programmerLayerStack.toggle(source = source, targets = targets, attributeMask = family?.name)
        }.onSuccess { outcome ->
            // The hardware door of the press log — pressing from the desk's own buttons counts, or
            // the programmer's recents row would disagree with the surface the operator is using.
            // The **on** arm only: a release is not a press (`PalettePressLog`). The two refusals
            // above (no palette / no such template, and a generic template with nothing selected) have
            // already returned, and a `toggle` that threw lands in `onFailure`.
            if (outcome.action == "applied") {
                PalettePressLog.record(state, projectId, source.id)
            }
        }.onFailure { logger.warn("Surface pressPalette failed: {}", it.message) }
    }

    /**
     * A busk pad, through [BuskPressService] — the same press the busk view makes, so the solo
     * rules, the empty-selection refusals, the mask rules and the cue toggle cannot diverge between
     * the two. The desk selection's targets *and* mask go in together: the button acts on the desk
     * fact, which is the pair a following window sends (multi-screen plan D4).
     */
    override fun pressPad(pad: UUID): ActionOutcome {
        val projectId = currentProjectId() ?: return ActionOutcome.NotFound("No current project")
        val selection = state.deskSelection.state.value
        return when (val outcome = BuskPressService.pressByUuid(state, projectId, pad, selection.targets, selection.families)) {
            // A stack pad on a stack with no cues started nothing, as the stack's own GO refuses it.
            is BuskPressService.Outcome.Pressed if outcome.action == "empty" ->
                ActionOutcome.Refused(ActionOutcome.STACK_EMPTY, "That stack pad's stack has no cue to GO to")
            is BuskPressService.Outcome.Pressed ->
                ActionOutcome.Done(if (outcome.action == "applied") withBlind(outcome.action) else outcome.action)
            is BuskPressService.Outcome.Conflict -> {
                logger.warn("Surface pressPad dropped: {}", outcome.message)
                ActionOutcome.Refused(outcome.code ?: ActionOutcome.GO_REFUSED, outcome.message)
            }
            is BuskPressService.Outcome.Refused -> {
                logger.warn("Surface pressPad dropped: {}", outcome.message)
                ActionOutcome.Refused(outcome.code ?: "PRESS_REFUSED", outcome.message)
            }
            is BuskPressService.Outcome.TargetMissing -> {
                logger.warn("Surface pressPad dropped: {}", outcome.message)
                ActionOutcome.Refused("TARGET_MISSING", outcome.message)
            }
            BuskPressService.Outcome.NotFound -> {
                logger.warn("Surface pressPad dropped: no busk pad {} in project {}", pad, projectId)
                ActionOutcome.NotFound("No busk pad $pad in this show")
            }
        }
    }

    /**
     * The hand's surface door. Resolves the pad in the current project and picks up whatever it
     * presses, stamped [SelectionSource.SURFACE] — to a chip every surface is "the desk", the same
     * reading a selection write from a surface gets.
     */
    override fun pickUpPad(pad: UUID) {
        val projectId = currentProjectId() ?: return
        val record = HandService.resolveByPadUuid(state, projectId, pad, SelectionSource.SURFACE)
        if (record == null) {
            logger.warn("Surface pickUpPad dropped: no busk pad {} in project {}", pad, projectId)
            return
        }
        // Null once the desk is closing; a press racing teardown is dropped rather than arming a
        // timer on a State that is going away.
        if (state.handState.pickUp(record) == null) {
            logger.debug("Surface pickUpPad dropped: the desk is shutting down")
        }
    }

    /**
     * Place the held record on one bank and let go — the only place a binding can make, because a
     * bank is the only place target with a uuid to carry.
     *
     * The drop is **conditional on this hold still being the hand's**, through
     * `HandState.dropIfHolding`, which checks and clears under one lock. Reading `held.value` here
     * and calling the unconditional `drop()` afterwards would be two steps with a gap, and a
     * pick-up landing in that gap would have this press clear an item it never touched — on the
     * two-screen case the hand exists for. It is also conditional on the append succeeding: a
     * refused place leaves the item in the hand so the operator can put it somewhere else, which is
     * the point of holding it.
     *
     * What is deliberately **not** guarded is the read at the top. If the hold expires, or another
     * window drops it, between that read and the transaction, the pad is still appended. That is
     * the right answer rather than a hole: the press is the operator saying *put this here*, and a
     * timeout landing in the same instant should not swallow the gesture. The guard exists to stop
     * this press clearing somebody else's item, not to stop it finishing its own.
     */
    override fun handPlaceInBank(bank: UUID) {
        val held = state.handState.held.value ?: run {
            logger.debug("Surface handPlaceInBank dropped: the hand is empty")
            return
        }
        val projectId = currentProjectId() ?: return
        when (val outcome = HandService.placeInBank(state, projectId, bank, held)) {
            AddPadOutcome.NotFound ->
                logger.warn("Surface handPlaceInBank dropped: no busk bank {} in project {}", bank, projectId)
            is AddPadOutcome.Invalid ->
                logger.warn("Surface handPlaceInBank dropped: {}", outcome.message)
            is AddPadOutcome.Ref -> logger.warn(
                "Surface handPlaceInBank dropped: the hand holds a {} ({}) that is not in project {}",
                outcome.what, outcome.id, projectId,
            )
            // The hand holds no stack, so this cannot arise from it; logged rather than assumed away.
            is AddPadOutcome.InRunningOrder ->
                logger.warn("Surface handPlaceInBank dropped: '{}' is in the running order", outcome.name)
            is AddPadOutcome.Added -> {
                state.show.fixtures.buskLayoutChanged(listOf(outcome.pageId))
                state.handState.dropIfHolding(held.holdId)
            }
        }
    }

    override fun handDrop() = state.handState.drop()

    // At once rather than on the serial lane: it opens no transaction and reloads nothing, and a Clear
    // queued behind a busy lane would wipe what was written after the press.
    override fun clearProgrammer(fadeMs: Long) {
        clearProgrammerCompletely(state, fadeMs)
    }

    override fun isBlind(): Boolean = state.show.programmerStore.blind

    override fun setBlind(blind: Boolean, fadeMs: Long): ActionOutcome =
        if (setProgrammerBlind(state, blind, fadeMs)) ActionOutcome.Done(if (blind) "on" else "off")
        else ActionOutcome.Done(if (blind) ALREADY_ON else ALREADY_OFF)

    override fun release(on: Subject, fadeMs: Long): ActionOutcome {
        val targets = targetsOf(on)
        if (targets.isEmpty()) return ActionOutcome.Done("nothing selected")
        var values = 0
        // A Local FX over several of the targets, which each target's own Release leaves as partial.
        var fx = if (targets.size > 1) {
            ProgrammerHandler.releaseLocalFxWithin(state, targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) }, fadeMs)
        } else {
            0
        }
        for (target in targets) {
            val cleared = ProgrammerHandler.clearTarget(state, ProgrammerClearTargetInMessage(target.type, target.key, fadeMs))
            cleared.error?.let { logger.warn("Surface release of {} '{}': {}", target.type, target.key, it) }
            values += cleared.values
            fx += cleared.fx
        }
        return ActionOutcome.Done("$values values, $fx FX")
    }

    override fun record(stack: UUID): ActionOutcome {
        val projectId = currentProjectId() ?: return ActionOutcome.NotFound("No current project")
        if (inVenue()) return recordOffInVenue("Record")
        val ref = resolveStack(stack) ?: return stackNotFound(stack)
        onSerialLane("record") {
            if (currentProjectId() != projectId) return@onSerialLane
            historyLabel("Record")
            val project = state.projectManager.currentProject
            val result = performProgrammerRecord(
                state, project,
                mode = RecordMode.CREATE,
                source = RecordSource.TOUCHED,
                cueType = CueType.STANDARD,
                cueStackId = ref.id,
                cueId = null,
                mask = null,
                includeFx = true,
                name = null,
                cueNumber = null,
                sortOrder = null,
                targets = null,
            )
            when (result) {
                is RecordCoreResult.Ok -> logger.info("Surface Record into {}: {}", ref.name, result.details.name)
                is RecordCoreResult.Failure -> logger.warn("Surface Record into {} refused: {}", ref.name, result.message)
                is RecordCoreResult.NumberRefused -> logger.warn("Surface Record into {} refused: {}", ref.name, result.refusal.message)
            }
        }
        return ActionOutcome.DONE
    }

    override fun update(): ActionOutcome {
        val projectId = currentProjectId() ?: return ActionOutcome.NotFound("No current project")
        if (inVenue()) return recordOffInVenue("Update")
        if (state.show.programmerStore.lastIncludedTarget == null) {
            logger.info("Surface Update refused: nothing is included")
            return ActionOutcome.Refused(ActionOutcome.NOTHING_INCLUDED, "Nothing is included to update")
        }
        onSerialLane("update") {
            if (currentProjectId() != projectId) return@onSerialLane
            // Checked again here: a Clear queued ahead of this press may have dropped the include target.
            if (state.show.programmerStore.lastIncludedTarget == null) {
                logger.info("Surface Update refused: nothing is included")
                return@onSerialLane
            }
            val project = state.projectManager.currentProject
            when (val result = performProgrammerUpdate(state, project, targets = null, mask = null, preview = false, includeFx = true)) {
                is UpdateCoreResult.CuesUpdated -> logger.info("Surface Update: {}", result.results.joinToString { it.cueName })
                is UpdateCoreResult.LookUpdated -> logger.info("Surface Update of a Look: wrote {}", result.result.wroteAnything)
                is UpdateCoreResult.IncludeTargetGone -> logger.warn("Surface Update refused: {}", result.message)
                is UpdateCoreResult.Failure -> logger.warn("Surface Update refused: {}", result.message)
                is UpdateCoreResult.Checklist -> logger.warn("Surface Update refused: nothing is included")
            }
        }
        return ActionOutcome.DONE
    }

    /** Whether Record and Update are off here: the live project is a venue, or its kind cannot be read. */
    private fun inVenue(): Boolean = runCatching { isVenueLive(state) }.getOrElse { e ->
        logger.warn("Surface Record refused: the project's kind could not be read: {}", e.toString())
        true
    }

    private fun recordOffInVenue(what: String): ActionOutcome {
        logger.info("Surface {} refused: Record is off in a venue", what)
        return ActionOutcome.Refused(CODE_VENUE_RECORD_OFF, "Record is off in a venue: the programmer here is for testing and focus")
    }

    /**
     * Run [block] on the serial lane, under the input's origin so its writes are the surface's in the
     * history. A failure is logged: a button has no reply channel.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun onSerialLane(what: String, block: suspend () -> Unit): Job =
        GlobalScope.launch(historyDispatcher + (WriteOrigin.current() ?: EmptyCoroutineContext)) {
            historyMutex.withLock {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Surface {} failed: {}", what, e.toString())
                }
            }
        }

    override fun historyMove(source: HistorySource, undo: Boolean, programmerOnly: Boolean) {
        launchHistoryMove(source, HistoryMove(undo, programmerOnly = programmerOnly))
    }

    /**
     * Run one press off the input's thread, since putting records back reloads what they touch, and in
     * press order: each decides what it takes only once the one before has finished.
     */
    @OptIn(DelicateCoroutinesApi::class)
    internal fun launchHistoryMove(source: HistorySource, move: HistoryMove): Job? {
        val projectId = currentProjectId() ?: return null
        return GlobalScope.launch(historyDispatcher + (WriteOrigin.current() ?: EmptyCoroutineContext)) {
            historyMutex.withLock { surfaceHistoryMove(projectId, source, move) }
        }
    }

    /**
     * One surface undo or redo on [projectId], at no programmer fade, as a surface press has none. A
     * surface cannot confirm, so an undo that would move a running show is refused like a conflict.
     */
    internal suspend fun surfaceHistoryMove(projectId: Int, source: HistorySource, move: HistoryMove): HistoryMoveResult {
        val what = if (move.undo) "undo" else "redo"
        val result = try {
            moveHistory(state, projectId, source, move)
        } catch (e: Exception) {
            logger.warn("Surface {} failed for {}: {}", what, source.name, e.message)
            return HistoryMoveResult.Empty
        }
        val refusal = result.refusal(move.undo, move.entryId)
        if (refusal != null) {
            logger.info("Surface {} by {} not done: {}", what, source.name, refusal.message)
        } else {
            val moved = when (result) {
                is HistoryMoveResult.Steps -> result.steps.map { it.label }
                is HistoryMoveResult.Show -> (result.outcome as? RecordRestorer.Outcome.Done)?.entries?.map { it.label }.orEmpty()
                else -> emptyList()
            }
            logger.info("Surface {} by {}: {}", what, source.name, moved.joinToString())
        }
        return result
    }

    override fun buskPageStep(delta: Int) = state.buskPageState.step(delta)

    override fun buskPageSet(page: UUID): ActionOutcome =
        if (state.buskPageState.setByUuid(page)) ActionOutcome.DONE
        else ActionOutcome.NotFound("No busk page $page in this show")

    override fun buskFocusSet(windowName: String, focus: String) =
        viewOptionsToWindows(windowName, mapOf(VIEW_OPTION_FOCUS to focus))

    override fun buskSheetToggle(windowName: String) =
        viewOptionsToWindows(windowName, mapOf(VIEW_OPTION_SHEET to VIEW_OPTION_TOGGLE))

    override fun subselect(mode: SubselectMode) = state.deskSelection.subselect(mode, SelectionSource.SURFACE)

    /**
     * One `windows.viewOptions` to **every** connected row named [windowName] (duplicate names are
     * two windows, multi-screen plan D9), each addressed by its own socket-minted id and carrying
     * the **view that row announced**, so a window not on the busk view receives a frame for its
     * own view with keys it does not contribute and ignores it. No row → log and no-op; the
     * binding's `missingWindow` health has already said so.
     */
    private fun viewOptionsToWindows(windowName: String, options: Map<String, String>) {
        val rows = state.windowRegistry.windows.value.filter { it.name == windowName }
        if (rows.isEmpty()) {
            logger.info("Surface window command dropped: no connected window named '{}'", windowName)
            return
        }
        rows.forEach { row ->
            state.windowRegistry.command(WindowRegistry.Command.ViewOptions(row.id, row.view, options))
        }
    }

    override fun writeSpeedMasterBpm(
        master: UUID?,
        minBpm: Double,
        maxBpm: Double,
        midiValue7Bit: UByte,
    ) {
        withSpeedMasterTarget(master) { uuid ->
            val bpm = Scale7Bit(minBpm, maxBpm).of7(midiValue7Bit)
            // MasterClock.setBpm coerces into 20..300 itself; the binding's window is a
            // sub-range on top of that, not a replacement for it.
            speedMasters.setBpm(uuid, bpm, SpeedMasterSource.MANUAL)
        }
    }

    override fun tapSpeedMaster(master: UUID?): ActionOutcome =
        withSpeedMasterTarget(master) { speedMasters.tap(it) }

    override fun setSpeedMasterBpm(master: UUID?, bpm: Double): ActionOutcome =
        withSpeedMasterTarget(master) { speedMasters.setBpm(it, bpm, SpeedMasterSource.MANUAL) }
            .let { if (it is ActionOutcome.Done) ActionOutcome.Done("${"%.1f".format(java.util.Locale.ROOT, bpm)} BPM") else it }

    override fun scaleSpeedMaster(master: UUID?, factor: Double) {
        withSpeedMasterTarget(master) { speedMasters.scale(it, factor) }
    }

    /**
     * Resolve a tempo-write target: null means master 1, and a uuid the bank does not hold is
     * dropped by the bank's own write resolution. A malformed uuid never gets this far — it fails
     * the binding's decode.
     *
     * Every dropped or refused write is logged — this surface has no reply channel, so the
     * log line is the only trace a MIDI-only operator gets of why their TAP/knob did nothing.
     * Throttled: a knob bound to a follower sends CC at ~100 msg/s, and one line per message
     * would flood the log for as long as the operator keeps turning it.
     */
    private inline fun withSpeedMasterTarget(
        raw: UUID?,
        write: (UUID?) -> SpeedMasterBank.TempoWriteOutcome,
    ): ActionOutcome {
        return when (val outcome = write(raw)) {
            is SpeedMasterBank.TempoWriteOutcome.Applied -> ActionOutcome.DONE

            is SpeedMasterBank.TempoWriteOutcome.UnknownMaster -> {
                warnTempoWriteDropped(
                    "Surface tempo write dropped: no speed master with uuid '$raw' — " +
                        "re-learn the binding if the master was deleted",
                )
                ActionOutcome.NotFound("No speed master $raw in this show")
            }

            is SpeedMasterBank.TempoWriteOutcome.RefusedFollower -> {
                warnTempoWriteDropped("Surface tempo write refused: ${outcome.describe}")
                ActionOutcome.Refused(ActionOutcome.SPEED_MASTER_FOLLOWER, outcome.describe)
            }

            is SpeedMasterBank.TempoWriteOutcome.OutOfRange -> {
                warnTempoWriteDropped("Surface tempo write refused: ${outcome.describe}")
                ActionOutcome.Refused(ActionOutcome.SPEED_MASTER_RANGE, outcome.describe)
            }
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

