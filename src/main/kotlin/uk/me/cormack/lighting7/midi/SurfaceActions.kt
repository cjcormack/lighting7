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
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.ProgrammerWriter
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.fx.SpeedMasterBank
import uk.me.cormack.lighting7.fx.maskAllows
import uk.me.cormack.lighting7.fx.speedMasterUuidOrNull
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
import uk.me.cormack.lighting7.state.SelectionSource
import uk.me.cormack.lighting7.state.SubselectMode
import uk.me.cormack.lighting7.state.WindowRegistry
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Port between [SurfaceInputRouter] and the rest of the application. Production wires this
 * to [DefaultSurfaceActions], which delegates into [Fixtures], the programmer layer
 * ([ProgrammerStore] via [FxEngine]), [CueStackManager], and [GlobalScalerState]. Tests can
 * supply a recording fake so the router can be exercised without a running show.
 *
 * Implementations handle their own thread coordination (the programmer store is lock-free; cue
 * stack activation is dispatched onto [GlobalScope] by [DefaultSurfaceActions]). The actions an OSC
 * address reaches answer an [ActionOutcome], which the router logs and the OSC log shows; the rest
 * are fire-and-forget.
 */
interface SurfaceActions {
    /**
     * Write a continuous value (0..127 MIDI 7-bit) to a fixture property. The production
     * implementation writes a programmer entry — a cue is read-only from a surface, and is
     * edited by Include / Update like every other authoring path. [colourAxis] is which axis of
     * a colour property the value moves — null is hue — and means nothing on a slider.
     */
    fun writeFixtureAttribute(
        fixtureKey: String,
        attribute: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis? = null,
    )

    /** Group variant of [writeFixtureAttribute], fanned out per member. */
    fun writeGroupAttribute(
        groupName: String,
        attribute: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis? = null,
    )

    /** Flash press: store at 0..255 [max] on the property's channels. */
    fun flashFixtureAttributePress(fixtureKey: String, attribute: String, max: UByte)
    fun flashGroupAttributePress(groupName: String, attribute: String, max: UByte)

    /** Flash release: clear direct-write entries for the property's channels. */
    fun flashFixtureAttributeRelease(fixtureKey: String, attribute: String)
    fun flashGroupAttributeRelease(groupName: String, attribute: String)

    /**
     * Cue and stack actions take the binding's uuid beside its int. A uuid, when present, is the
     * only thing consulted — it is what survives a clone — and a uuid that resolves to nothing in
     * the current project **drops** the press rather than falling back to an int that may name
     * another project's row. Only a pre-v11 row with no uuid is dispatched by int.
     */
    fun cueStackGo(stackId: Int, stackUuid: String? = null): ActionOutcome
    fun cueStackBack(stackId: Int, stackUuid: String? = null): ActionOutcome
    fun cueStackPause(stackId: Int, stackUuid: String? = null): ActionOutcome
    fun fireCue(cueId: Int, cueUuid: String? = null): ActionOutcome

    /** GO TO [entryUuid] of [stackUuid], starting it if stopped; nothing if that entry is already live (D4). */
    fun cueStackGoTo(stackUuid: String, entryUuid: String): ActionOutcome

    /** Arm [entryUuid] as [stackUuid]'s next GO. */
    fun cueStackStandby(stackUuid: String, entryUuid: String): ActionOutcome

    /** Stop [stackUuid], which drops the desk's arm as every stop does — running or not, as the REST deactivate does. */
    fun cueStackStop(stackUuid: String): ActionOutcome

    /** Take or let go of [cueUuid]'s loose hold; nothing if it already is so (D4). */
    fun looseCueSet(cueUuid: String, on: Boolean): ActionOutcome

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

    /**
     * Set a cue stack's FX amount from a continuous control, 0..127 across 0–100 %, the stack
     * resolved as the stack buttons resolve theirs. A default so a test double that does not care
     * about stack sizes need not say so.
     */
    fun writeStackFxAmount(stackId: Int, stackUuid: String?, midiValue7Bit: UByte) {}

    /** A stack's FX amount taken whole, 0–1, from a sender with floats (OSC plan D10). */
    fun setStackFxAmount(stackId: Int, stackUuid: String?, amount: Double): ActionOutcome

    /**
     * Set a speed master's tempo from a continuous control, scaling 0..127 across
     * [minBpm]..[maxBpm]. [masterUuid] null → master 1.
     */
    fun writeSpeedMasterBpm(masterUuid: String?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte)

    /** Tap a speed master's tempo ([masterUuid] null → master 1). */
    fun tapSpeedMaster(masterUuid: String?): ActionOutcome

    /**
     * Set a speed master's tempo taken whole ([masterUuid] null → master 1), 20–300 BPM: a typed
     * tempo's write, so on a held master it moves the hold, and a follower refuses it.
     */
    fun setSpeedMasterBpm(masterUuid: String?, bpm: Double): ActionOutcome

    /** Halve or double a speed master's tempo ([masterUuid] null → master 1), fx-speed plan D12. */
    fun scaleSpeedMaster(masterUuid: String?, factor: Double) {}

    /**
     * Write a continuous value to [attribute] on every target in the desk selection
     * ([uk.me.cormack.lighting7.state.DeskSelection]) — a group fanned to its members. An empty
     * selection drops the write with a debug log; it is never widened to "everything".
     * [colourAxis] as on [writeFixtureAttribute].
     */
    fun writeSelectionAttribute(attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis? = null)

    /** One turn of a fan encoder on [axis] over the desk selection — see [SelectionFan]. */
    fun writeSelectionFan(axis: FanAxis, midiValue7Bit: UByte)

    /**
     * One move of an *FX speed* control over the desk selection's Local FX — see [SelectionFxSpeed].
     * [encoder] is whether the control is an encoder (detents) or a fader (a position).
     */
    fun writeFxSpeed(midiValue7Bit: UByte, encoder: Boolean) {}

    /** Toggle [target] in, or replace the selection with it, per [mode]. */
    fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode)

    fun clearSelection()

    /**
     * Locate every selected target, or release them all when every one is already located —
     * the same toggle `POST /locate/toggle` makes, once per target.
     */
    fun locateSelection()

    /** Press a Look, whole — never onto the selection (D6; absolute-looks plan D1). */
    fun applyLook(lookUuid: String): ActionOutcome

    /** A Look on whole, or every layer of it off; nothing if it already is so (D4). */
    fun lookSet(lookUuid: String, on: Boolean): ActionOutcome

    /**
     * Press a template onto the **desk selection**. A generic template with nothing selected is
     * dropped, exactly as its busk pad refuses; a per-fixture one presses on its own heads. The
     * family mask is derived from the template's rows, never sent.
     */
    fun pressPalette(paletteUuid: String)

    /** Press a busk pad — its own bank's plan, solo siblings included, on the desk selection. */
    fun pressPad(padUuid: String): ActionOutcome

    /**
     * Put the record on a busk pad into the desk's hand, replacing whatever it held
     * (multi-screen plan §3.5).
     */
    fun pickUpPad(padUuid: String)

    /**
     * Place whatever the hand holds as a pad on one bank, then let go. An empty hand is a dropped
     * press. The append is the same one `POST /busk/banks/{bankId}/pads` makes — a place is an
     * *existing* mutation whichever door makes it (D12).
     */
    fun handPlaceInBank(bankUuid: String)

    /** Let go of whatever the hand holds; a no-op on an empty hand. */
    fun handDrop()

    /**
     * The undo or redo key as [source], the surface's own (undo plan §4.8), or the programmer's top
     * step alone with [programmerOnly]. A refusal moves nothing and is logged.
     */
    fun historyMove(source: HistorySource, undo: Boolean, programmerOnly: Boolean = false)

    /** Move the desk's showing busk page by one, wrapping. */
    fun buskPageStep(delta: Int)

    /** Show one named busk page. */
    fun buskPageSet(pageUuid: String): ActionOutcome

    /**
     * Set the busk focus of every connected window named [windowName] through one
     * `windows.viewOptions` per row (busk-further plan D14). No row is a logged no-op.
     */
    fun buskFocusSet(windowName: String, focus: String)

    /** Fold or open the busk side sheet of every connected window named [windowName]. */
    fun buskSheetToggle(windowName: String)

    /** Step the whole desk selection one place along rig order — `+1` next, `−1` previous. */
    fun selectionStep(delta: Int)

    /** Rewrite the desk selection's targets by [mode] (`DeskSelection.subselect`). */
    fun selectionCells(mode: SubselectMode)
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

        /** Every surface's undo and redo presses, one at a time in press order. */
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

    override fun writeFixtureAttribute(
        fixtureKey: String,
        attribute: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis?,
    ) {
        val fixture = try {
            fixtures.untypedFixture(fixtureKey)
        } catch (_: Exception) {
            logger.debug("Surface write: fixture '{}' not found", fixtureKey)
            return
        }
        val value = AttributeChannelResolver.toAttributeValue(
            fixture, attribute, midiValue7Bit, AttributeChannelResolver.channelReader(fixtures), colourAxis,
        ) ?: run {
            logger.debug("Surface write: property '{}' on '{}' not fader-writable", attribute, fixtureKey)
            return
        }
        fxEngine.programmer.writeAttribute(ProgrammerOwner.SURFACE, fixture, attribute, value)
    }

    override fun writeGroupAttribute(
        groupName: String,
        attribute: String,
        midiValue7Bit: UByte,
        colourAxis: ColourAxis?,
    ) {
        val group = try {
            fixtures.untypedGroup(groupName)
        } catch (_: Exception) {
            logger.debug("Surface write: group '{}' not found", groupName)
            return
        }
        // Convert per member — sliders scale through each member's own min..max sub-range, and
        // a colour axis lands on each member's own current colour.
        val read = AttributeChannelResolver.channelReader(fixtures)
        val writes = group.fixtures.filterIsInstance<Fixture>().mapNotNull { member ->
            AttributeChannelResolver.toAttributeValue(member, attribute, midiValue7Bit, read, colourAxis)?.let {
                ProgrammerWriter.AttributeWrite(member, attribute, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.programmer.writeAttributes(ProgrammerOwner.SURFACE, writes)
    }

    override fun flashFixtureAttributePress(fixtureKey: String, attribute: String, max: UByte) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        val value = AttributeChannelResolver.flashAttributeValue(fixture, attribute, max) ?: return
        // Momentary owner: don't absorb the sideband — release must reveal what was under it.
        fxEngine.programmer.writeAttribute(
            ProgrammerOwner.FLASH, fixture, attribute, value, absorbSideband = false,
        )
    }

    override fun flashGroupAttributePress(groupName: String, attribute: String, max: UByte) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        // Clamp per member — slider max can differ across heterogeneous group members.
        val writes = group.fixtures.filterIsInstance<Fixture>().mapNotNull { member ->
            AttributeChannelResolver.flashAttributeValue(member, attribute, max)?.let {
                ProgrammerWriter.AttributeWrite(member, attribute, it, sourceGroup = groupName)
            }
        }
        if (writes.isEmpty()) return
        fxEngine.programmer.writeAttributes(ProgrammerOwner.FLASH, writes, absorbSideband = false)
    }

    override fun flashFixtureAttributeRelease(fixtureKey: String, attribute: String) {
        val fixture = fixtures.tryUntypedFixture(fixtureKey) ?: return
        // Release pops only the FLASH slot: the property cascades to the surviving owner
        // underneath (fader/busk level), then the cue layer, then baseline — in one
        // transaction, skipping keys a running effect covers.
        fxEngine.programmer.clearAttribute(ProgrammerOwner.FLASH, fixture, attribute)
    }

    override fun flashGroupAttributeRelease(groupName: String, attribute: String) {
        val group = fixtures.tryUntypedGroup(groupName) ?: return
        fxEngine.programmer.clearGroupAttribute(ProgrammerOwner.FLASH, group, attribute)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGo(stackId: Int, stackUuid: String?): ActionOutcome {
        val stack = resolveStack(stackId, stackUuid) ?: return stackNotFound(stackUuid ?: "#$stackId")
        val result = try {
            cueStackManager.go(state, stack.id, GlobalScope)
        } catch (e: IllegalArgumentException) {
            return ActionOutcome.Refused(ActionOutcome.GO_REFUSED, "${stack.name}: ${e.message}")
        }
        if (result == null) return ActionOutcome.Refused(ActionOutcome.STACK_EMPTY, "${stack.name} has no cue to GO to")
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackBack(stackId: Int, stackUuid: String?): ActionOutcome {
        val stack = resolveStack(stackId, stackUuid) ?: return stackNotFound(stackUuid ?: "#$stackId")
        val result = try {
            cueStackManager.advanceStack(state, stack.id, CueStackManager.AdvanceDirection.BACKWARD, GlobalScope)
        } catch (e: IllegalArgumentException) {
            return ActionOutcome.Refused(ActionOutcome.GO_REFUSED, "${stack.name}: ${e.message}")
        }
        if (result == null) return ActionOutcome.Done("${stack.name} is not running")
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    override fun cueStackPause(stackId: Int, stackUuid: String?): ActionOutcome {
        val stack = resolveStack(stackId, stackUuid) ?: return stackNotFound(stackUuid ?: "#$stackId")
        return if (cueStackManager.pauseAutoAdvance(state, stack.id)) ActionOutcome.DONE
        else ActionOutcome.Done("no follow was running")
    }

    override fun fireCue(cueId: Int, cueUuid: String?): ActionOutcome {
        val cue = resolveCueId(cueId, cueUuid) ?: return cueNotFound(cueUuid ?: "#$cueId")
        // A press toggles the cue's loose hold (cue model plan D6, D11), as a busk cue pad does:
        // it names the cue and no stack, and a second press lets it go.
        val (on, _) = cueStackManager.holds.toggleLooseCue(state, cue) ?: return cueNotFound(cueUuid ?: "#$cueId")
        return ActionOutcome.Done(if (on) "on" else "off")
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun cueStackGoTo(stackUuid: String, entryUuid: String): ActionOutcome {
        val (stack, entryId) = resolveEntry(stackUuid, entryUuid).let { it.second ?: return it.first!! }
        if (cueStackManager.getActiveEntryId(stack.id) == entryId) return ActionOutcome.Done(ALREADY_LIVE)
        cueStackManager.goToEntry(state, stack.id, entryId, GlobalScope)
        state.show.fixtures.cueStackListChanged()
        return ActionOutcome.DONE
    }

    override fun cueStackStandby(stackUuid: String, entryUuid: String): ActionOutcome {
        val (stack, entryId) = resolveEntry(stackUuid, entryUuid).let { it.second ?: return it.first!! }
        cueStackManager.runState.setStandby(state, stack.id, entryId)
        return ActionOutcome.DONE
    }

    override fun cueStackStop(stackUuid: String): ActionOutcome {
        val stack = resolveStack(0, stackUuid) ?: return stackNotFound(stackUuid)
        val running = cueStackManager.isStackActive(stack.id)
        cueStackManager.deactivateStack(stack.id, state)
        state.show.fixtures.cueStackListChanged()
        return if (running) ActionOutcome.DONE else ActionOutcome.Done("was not running")
    }

    override fun looseCueSet(cueUuid: String, on: Boolean): ActionOutcome {
        val cue = resolveCueId(0, cueUuid) ?: return cueNotFound(cueUuid)
        val holds = cueStackManager.holds
        if (holds.looseHeld(cue) == on) return ActionOutcome.Done(if (on) ALREADY_ON else ALREADY_OFF)
        if (on) {
            holds.takeLooseCue(state, cue) ?: return cueNotFound(cueUuid)
        } else {
            holds.dropLoose(state, cue)
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
        globalScalerState.setGrandMasterLevel(grandMasterLevelOf7Bit(midiValue7Bit))

    // A fader moves at control rate, so a stack it cannot find is not logged per move: health says so.
    override fun writeStackFxAmount(stackId: Int, stackUuid: String?, midiValue7Bit: UByte) {
        val resolved = resolveFaderStackId(stackId, stackUuid) ?: return
        cueStackManager.setFxAmount(resolved, stackFxAmountOf7Bit(midiValue7Bit))
    }

    override fun setStackFxAmount(stackId: Int, stackUuid: String?, amount: Double): ActionOutcome {
        val resolved = resolveFaderStackId(stackId, stackUuid) ?: return stackNotFound(stackUuid ?: "#$stackId")
        val held = cueStackManager.setFxAmount(resolved, amount.coerceIn(0.0, 1.0))
        return ActionOutcome.Done("${(held * 100).roundToInt()} %")
    }

    /** A stack button's rule — the uuid wins, the int only without one — through the show's kept lookup. */
    private fun resolveFaderStackId(stackId: Int, stackUuid: String?): Int? =
        if (stackUuid == null) stackId else cueStackManager.stackIdForUuid(state.database, stackUuid)

    /** A stack this action names: its id and its name, for an outcome's words. */
    private data class StackRef(val id: Int, val name: String)

    /**
     * Uuid first, and never the int when a uuid is present: a binding cloned from another project
     * carries that project's int, and the health evaluator already marks a uuid that resolves to
     * nothing as dead. The lookup is one indexed read per press — button rate, not fader rate.
     */
    private fun resolveCueId(cueId: Int, cueUuid: String?): Int? {
        if (cueUuid == null) return cueId
        val uuid = uuidOrNull(cueUuid) ?: return null
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            DaoCue.find { (DaoCues.uuid eq uuid) and (DaoCues.project eq projectId) }.firstOrNull()?.id?.value
        }
    }

    /** [stackUuid]'s stack in the current project, else [stackId]'s for a row that has no uuid. */
    private fun resolveStack(stackId: Int, stackUuid: String?): StackRef? {
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            val stack = if (stackUuid == null) {
                DaoCueStack.findById(stackId)?.takeIf { it.project.id.value == projectId }
            } else {
                val uuid = uuidOrNull(stackUuid) ?: return@transaction null
                DaoCueStack.find { (DaoCueStacks.uuid eq uuid) and (DaoCueStacks.project eq projectId) }.firstOrNull()
            }
            stack?.let { StackRef(it.id.value, it.name) }
        }
    }

    /** A cue entry of [stackUuid] by its uuid, or the outcome that says why there is none. */
    private fun resolveEntry(stackUuid: String, entryUuid: String): Pair<ActionOutcome?, Pair<StackRef, Int>?> {
        val stack = resolveStack(0, stackUuid) ?: return stackNotFound(stackUuid) to null
        val uuid = uuidOrNull(entryUuid) ?: return ActionOutcome.NotFound("No cue $entryUuid in ${stack.name}") to null
        val entry = transaction(state.database) {
            DaoCueStackEntry.find { DaoCueStackEntries.uuid eq uuid }.firstOrNull()
                ?.takeIf { it.stackId == stack.id }
                ?.let { it.id.value to it.isMarker }
        } ?: return ActionOutcome.NotFound("No cue $entryUuid in ${stack.name}") to null
        if (entry.second) return ActionOutcome.Refused(ActionOutcome.NOT_A_CUE, "That entry of ${stack.name} is a marker") to null
        return null to (stack to entry.first)
    }

    private fun stackNotFound(what: String): ActionOutcome {
        logger.warn("Surface stack action dropped: no stack {} in the current project", what)
        return ActionOutcome.NotFound("No stack $what in this show")
    }

    private fun cueNotFound(what: String): ActionOutcome {
        logger.warn("Surface cue action dropped: no cue {} in the current project", what)
        return ActionOutcome.NotFound("No cue $what in this show")
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

    override fun writeSelectionAttribute(attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        // The selection's attribute mask is ignored here on purpose: the control names its own
        // attribute, as a cell edit is not masked by a marquee elsewhere (multi-screen plan §3.3).
        val targets = state.deskSelection.state.value.targets
        if (targets.isEmpty()) {
            logger.debug("Surface selection write of '{}' dropped: nothing selected", attribute)
            return
        }
        val writes = SelectionWrites.forTargets(fixtures, targets, attribute, midiValue7Bit, colourAxis)
        if (writes.isEmpty()) return
        fxEngine.programmer.writeAttributes(ProgrammerOwner.SURFACE, writes)
    }

    override fun writeSelectionFan(axis: FanAxis, midiValue7Bit: UByte) = state.selectionFan.turn(axis, midiValue7Bit)

    override fun writeFxSpeed(midiValue7Bit: UByte, encoder: Boolean) = state.selectionFxSpeed.turn(midiValue7Bit, encoder)

    override fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode) {
        when (mode) {
            // A toggle adds a head under whatever mask is standing; a replace has no column axis
            // to speak with, so it clears the mask by passing none (multi-screen plan D2).
            BindingTarget.SelectMode.TOGGLE -> state.deskSelection.toggle(target, SelectionSource.SURFACE)
            BindingTarget.SelectMode.REPLACE -> state.deskSelection.set(listOf(target), source = SelectionSource.SURFACE)
        }
    }

    override fun clearSelection() = state.deskSelection.clear()

    override fun locateSelection() {
        val targets = state.deskSelection.state.value.targets.mapNotNull { TargetRef.ofOrNull(it.type, it.key) }
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
     * A Look, whole, through the same `ProgrammerLayerStack.toggle` the toggle route uses: on puts
     * one layer of it on, off takes every layer of it off. A failure is logged: a MIDI press has no
     * reply channel, so the log line is the only trace the operator gets.
     */
    override fun applyLook(lookUuid: String): ActionOutcome {
        val source = lookSource(lookUuid) ?: return lookNotFound(lookUuid)
        val outcome = state.show.programmerLayerStack.toggle(source = source, targets = emptyList())
        return ActionOutcome.Done(if (outcome.action == "applied") withBlind("on") else "off")
    }

    override fun lookSet(lookUuid: String, on: Boolean): ActionOutcome {
        val source = lookSource(lookUuid) ?: return lookNotFound(lookUuid)
        val layers = state.show.programmerLayerStack
        val applied = state.show.programmerStore.layers.any { it.source.uuid == source.uuid && it.source.kind == LayerSourceKind.LOOK }
        if (applied == on) return ActionOutcome.Done(if (on) ALREADY_ON else ALREADY_OFF)
        if (on) layers.toggle(source = source, targets = emptyList()) else layers.release(setOf(source.uuid))
        return if (on && fxEngine.programmerStore.blind) ActionOutcome.Done(STAGED_BLIND) else ActionOutcome.DONE
    }

    /** The Look [lookUuid] names in the current project, as the layer source a press puts on. */
    private fun lookSource(lookUuid: String): LayerSource? {
        val uuid = uuidOrNull(lookUuid) ?: return null
        val projectId = currentProjectId() ?: return null
        return transaction(state.database) {
            DaoLook.find { (DaoLooks.uuid eq uuid) and (DaoLooks.project eq projectId) }.firstOrNull()?.layerSource()
        }
    }

    private fun lookNotFound(lookUuid: String): ActionOutcome {
        logger.warn("Surface Look press dropped: no Look {} in the current project", lookUuid)
        return ActionOutcome.NotFound("No Look $lookUuid in this show")
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
    override fun pressPalette(paletteUuid: String) {
        val uuid = uuidOrNull(paletteUuid) ?: run {
            logger.warn("Surface pressPalette dropped: '{}' is not a uuid", paletteUuid)
            return
        }
        val projectId = currentProjectId() ?: return
        val press = transaction(state.database) {
            DaoPalette.find { (DaoPalettes.uuid eq uuid) and (DaoPalettes.project eq projectId) }
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
            logger.warn("Surface pressPalette dropped: no template {} in project {}", paletteUuid, projectId)
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
            // above (no uuid / no such template, and a generic template with nothing selected) have
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
    override fun pressPad(padUuid: String): ActionOutcome {
        val uuid = uuidOrNull(padUuid) ?: return ActionOutcome.NotFound("No busk pad $padUuid in this show")
        val projectId = currentProjectId() ?: return ActionOutcome.NotFound("No current project")
        val selection = state.deskSelection.state.value
        return when (val outcome = BuskPressService.pressByUuid(state, projectId, uuid, selection.targets, selection.families)) {
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
                logger.warn("Surface pressPad dropped: no busk pad {} in project {}", padUuid, projectId)
                ActionOutcome.NotFound("No busk pad $padUuid in this show")
            }
        }
    }

    /**
     * The hand's surface door. Resolves the pad in the current project and picks up whatever it
     * presses, stamped [SelectionSource.SURFACE] — to a chip every surface is "the desk", the same
     * reading a selection write from a surface gets.
     */
    override fun pickUpPad(padUuid: String) {
        val uuid = uuidOrNull(padUuid) ?: run {
            logger.warn("Surface pickUpPad dropped: '{}' is not a uuid", padUuid)
            return
        }
        val projectId = currentProjectId() ?: return
        val record = HandService.resolveByPadUuid(state, projectId, uuid, SelectionSource.SURFACE)
        if (record == null) {
            logger.warn("Surface pickUpPad dropped: no busk pad {} in project {}", padUuid, projectId)
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
    override fun handPlaceInBank(bankUuid: String) {
        val uuid = uuidOrNull(bankUuid) ?: run {
            logger.warn("Surface handPlaceInBank dropped: '{}' is not a uuid", bankUuid)
            return
        }
        val held = state.handState.held.value ?: run {
            logger.debug("Surface handPlaceInBank dropped: the hand is empty")
            return
        }
        val projectId = currentProjectId() ?: return
        when (val outcome = HandService.placeInBank(state, projectId, uuid, held)) {
            AddPadOutcome.NotFound ->
                logger.warn("Surface handPlaceInBank dropped: no busk bank {} in project {}", bankUuid, projectId)
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

    override fun buskPageSet(pageUuid: String): ActionOutcome {
        val uuid = uuidOrNull(pageUuid) ?: return ActionOutcome.NotFound("No busk page $pageUuid in this show")
        return if (state.buskPageState.setByUuid(uuid)) ActionOutcome.DONE
        else ActionOutcome.NotFound("No busk page $pageUuid in this show")
    }

    override fun buskFocusSet(windowName: String, focus: String) =
        viewOptionsToWindows(windowName, mapOf(VIEW_OPTION_FOCUS to focus))

    override fun buskSheetToggle(windowName: String) =
        viewOptionsToWindows(windowName, mapOf(VIEW_OPTION_SHEET to VIEW_OPTION_TOGGLE))

    override fun selectionStep(delta: Int) =
        state.deskSelection.subselect(if (delta >= 0) SubselectMode.NEXT else SubselectMode.PREV, SelectionSource.SURFACE)

    override fun selectionCells(mode: SubselectMode) = state.deskSelection.subselect(mode, SelectionSource.SURFACE)

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
        masterUuid: String?,
        minBpm: Double,
        maxBpm: Double,
        midiValue7Bit: UByte,
    ) {
        withSpeedMasterTarget(masterUuid) { uuid ->
            val bpm = minBpm + (maxBpm - minBpm) * (midiValue7Bit.toInt() / 127.0)
            // MasterClock.setBpm coerces into 20..300 itself; the binding's window is a
            // sub-range on top of that, not a replacement for it.
            speedMasters.setBpm(uuid, bpm, SpeedMasterSource.MANUAL)
        }
    }

    override fun tapSpeedMaster(masterUuid: String?): ActionOutcome =
        withSpeedMasterTarget(masterUuid) { speedMasters.tap(it) }

    override fun setSpeedMasterBpm(masterUuid: String?, bpm: Double): ActionOutcome =
        withSpeedMasterTarget(masterUuid) { speedMasters.setBpm(it, bpm, SpeedMasterSource.MANUAL) }
            .let { if (it is ActionOutcome.Done) ActionOutcome.Done("${"%.1f".format(java.util.Locale.ROOT, bpm)} BPM") else it }

    override fun scaleSpeedMaster(masterUuid: String?, factor: Double) {
        withSpeedMasterTarget(masterUuid) { speedMasters.scale(it, factor) }
    }

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
    ): ActionOutcome {
        val outcome = if (raw == null) {
            write(null)
        } else {
            val uuid = speedMasterUuidOrNull(raw)
            if (uuid == null) {
                warnTempoWriteDropped("Surface tempo write dropped: binding target '$raw' is not a uuid")
                return ActionOutcome.NotFound("No speed master $raw in this show")
            }
            write(uuid)
        }
        return when (outcome) {
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

// --- Small helpers that turn the existing throwing lookups into nullable returns.

private fun Fixtures.tryUntypedFixture(key: String): Fixture? = try {
    untypedFixture(key)
} catch (_: Exception) { null }

private fun Fixtures.tryUntypedGroup(name: String): uk.me.cormack.lighting7.fixture.group.FixtureGroup<*>? = try {
    untypedGroup(name)
} catch (_: Exception) { null }
