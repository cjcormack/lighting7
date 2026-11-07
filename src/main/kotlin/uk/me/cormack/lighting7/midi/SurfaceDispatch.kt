package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.history.HistorySource

/**
 * One resolved [BindingTarget] into [SurfaceActions]: the dispatch a MIDI surface's router and the
 * OSC collector share (OSC plan D2), so a button and an OSC message that name one target cannot do
 * different things. Every call answers an [ActionOutcome].
 *
 * Callers run each input inside [guarded]: the actions read `state.show`, which throws while a
 * project switch is in flight, and a throw out of an input collector would end the collector.
 */
class SurfaceDispatch(
    private val actions: SurfaceActions,
    private val bankState: ActiveBankState,
    private val encoderBankState: EncoderBankState,
    private val flashTracker: FlashStateTracker,
) {
    /**
     * Where a press came from: the device profile ([deviceTypeKey]) an encoder bank applies to, the
     * binding row a flash is tracked by, and the history source an undo key acts as.
     */
    data class Origin(val deviceTypeKey: String, val bindingId: Int, val source: HistorySource)

    /** A fader or encoder move, 0..127. [isEncoder] is asked only by an *FX speed* target. */
    fun continuous(target: BindingTarget, value7Bit: UByte, isEncoder: () -> Boolean = { false }): ActionOutcome =
        when (target) {
            is BindingTarget.FixtureAttribute ->
                done { actions.writeFixtureAttribute(target.fixtureKey, target.attribute, value7Bit, target.colourAxis) }
            is BindingTarget.GroupAttribute ->
                done { actions.writeGroupAttribute(target.groupName, target.attribute, value7Bit, target.colourAxis) }
            is BindingTarget.SpeedMasterBpm ->
                done { actions.writeSpeedMasterBpm(target.masterUuid, target.minBpm, target.maxBpm, value7Bit) }
            BindingTarget.GrandMasterFader -> done { actions.writeGrandMaster(value7Bit) }
            is BindingTarget.StackFxAmount -> done { actions.writeStackFxAmount(target.stackId, target.stackUuid, value7Bit) }
            is BindingTarget.SelectionAttribute ->
                done { actions.writeSelectionAttribute(target.attribute, value7Bit, target.colourAxis) }
            is BindingTarget.SelectionFan -> done { actions.writeSelectionFan(target.axis, value7Bit) }
            BindingTarget.FxSpeed -> done { actions.writeFxSpeed(value7Bit, isEncoder()) }
            is BindingTarget.Strip -> {
                logger.warn("Strip target reached continuous dispatch — resolve should have derived it")
                wrongKind(target)
            }
            else -> wrongKind(target)
        }

    /**
     * A continuous target's value taken whole (OSC plan D10), rather than through the 7-bit arm a
     * MIDI fader goes through: a stack's FX amount, 0–1.
     */
    fun level(target: BindingTarget, value: Double): ActionOutcome = when (target) {
        is BindingTarget.StackFxAmount -> actions.setStackFxAmount(target.stackId, target.stackUuid, value)
        else -> wrongKind(target)
    }

    /** A button press. */
    fun press(target: BindingTarget, origin: Origin): ActionOutcome = when (target) {
        is BindingTarget.CueStackGo -> actions.cueStackGo(target.stackId, target.stackUuid)
        is BindingTarget.CueStackBack -> actions.cueStackBack(target.stackId, target.stackUuid)
        is BindingTarget.CueStackPause -> actions.cueStackPause(target.stackId, target.stackUuid)
        is BindingTarget.CueStackGoTo -> actions.cueStackGoTo(target.stackUuid, target.entryUuid)
        is BindingTarget.CueStackStandby -> actions.cueStackStandby(target.stackUuid, target.entryUuid)
        is BindingTarget.CueStackStop -> actions.cueStackStop(target.stackUuid)
        is BindingTarget.FireCue -> actions.fireCue(target.cueId, target.cueUuid)
        is BindingTarget.LooseCueSet -> actions.looseCueSet(target.cueUuid, target.on)
        // Needs the arm, like every fire; an unarmed press is refused and announced there.
        is BindingTarget.FireTrigger -> done { actions.fireTrigger(target.fixtureKey, target.trigger) }
        is BindingTarget.SelectTarget -> done { actions.selectTarget(target.target, target.mode) }
        is BindingTarget.ClearSelection -> done { actions.clearSelection() }
        is BindingTarget.LocateSelection -> done { actions.locateSelection() }
        // Health already marks it dead, so the router drops it before it gets here.
        is BindingTarget.Unknown -> wrongKind(target)
        is BindingTarget.Blackout -> done { actions.toggleBlackout() }
        is BindingTarget.GrandMasterToggle -> done { actions.toggleGrandMaster() }
        is BindingTarget.SetBank -> done { bankState.setBank(target.deviceTypeKey, target.bank) }
        // Applies to the device the button is on, so the payload names no device and a
        // button can never be stranded pointing at a surface that isn't attached.
        is BindingTarget.EncoderBankSet ->
            done { encoderBankState.set(origin.deviceTypeKey, EncoderBankSelection(target.attribute, target.colourAxis)) }
        // Never dispatched: resolve() derives a strip to the target of the control the event
        // arrived on. Reaching here means the strip arm failed to fire.
        is BindingTarget.Strip -> {
            logger.warn("Strip binding {} reached button dispatch — resolve should have derived it", origin.bindingId)
            wrongKind(target)
        }
        is BindingTarget.SpeedMasterTap -> done { actions.tapSpeedMaster(target.masterUuid) }
        is BindingTarget.SpeedMasterScale -> done { actions.scaleSpeedMaster(target.masterUuid, target.factor) }
        // Records: one behaviour each (D6). The Look whole, the palette onto the selection, the pad
        // through its own bank's plan — never any of the three onto what the other two would choose.
        is BindingTarget.ApplyLook -> actions.applyLook(target.lookUuid)
        is BindingTarget.LookSet -> actions.lookSet(target.lookUuid, target.on)
        is BindingTarget.PressPalette -> done { actions.pressPalette(target.paletteUuid) }
        is BindingTarget.PressPad -> actions.pressPad(target.padUuid)
        // The hand (multi-screen plan §3.5): what a pick-up reads and what a place appends live in
        // `HandService`, which the window's own doors share.
        is BindingTarget.PickUpPad -> done { actions.pickUpPad(target.padUuid) }
        is BindingTarget.HandPlaceInBank -> done { actions.handPlaceInBank(target.bankUuid) }
        BindingTarget.HandDrop -> done { actions.handDrop() }
        // The surface is a source of its own (undo plan D1): its key takes its own entries.
        BindingTarget.Undo -> done { actions.historyMove(origin.source, undo = true) }
        BindingTarget.Redo -> done { actions.historyMove(origin.source, undo = false) }
        BindingTarget.ProgrammerUndo -> done { actions.historyMove(origin.source, undo = true, programmerOnly = true) }
        BindingTarget.BuskPageNext -> done { actions.buskPageStep(1) }
        BindingTarget.BuskPagePrev -> done { actions.buskPageStep(-1) }
        is BindingTarget.BuskPageSet -> actions.buskPageSet(target.pageUuid)
        // A window by name and the sub-selection (busk-further plan D12, D14).
        is BindingTarget.BuskFocusSet -> done { actions.buskFocusSet(target.windowName, target.focus) }
        is BindingTarget.BuskSheetToggle -> done { actions.buskSheetToggle(target.windowName) }
        BindingTarget.SelectionNext -> done { actions.selectionStep(1) }
        BindingTarget.SelectionPrev -> done { actions.selectionStep(-1) }
        is BindingTarget.SelectionCells -> done { actions.selectionCells(target.mode) }
        is BindingTarget.SpeedMasterBpm,
        BindingTarget.GrandMasterFader,
        is BindingTarget.StackFxAmount,
        is BindingTarget.SelectionFan,
        BindingTarget.FxSpeed,
            -> wrongKind(target)
        is BindingTarget.Flash -> {
            if (!flashTracker.pressed(origin.bindingId)) {
                ActionOutcome.Done("held") // a retrigger while held
            } else {
                val max = target.max.toUByte()
                when (val inner = target.target) {
                    is BindingTarget.FixtureAttribute ->
                        done { actions.flashFixtureAttributePress(inner.fixtureKey, inner.attribute, max) }
                    is BindingTarget.GroupAttribute ->
                        done { actions.flashGroupAttributePress(inner.groupName, inner.attribute, max) }
                    // Forbidden by Flash's init block.
                    else -> wrongKind(inner)
                }
            }
        }
        // A button press on a continuous attribute writes it full, with no release step: Flash is
        // the binding for momentary.
        is BindingTarget.FixtureAttribute ->
            done { actions.writeFixtureAttribute(target.fixtureKey, target.attribute, 127u, target.colourAxis) }
        is BindingTarget.GroupAttribute ->
            done { actions.writeGroupAttribute(target.groupName, target.attribute, 127u, target.colourAxis) }
        is BindingTarget.SelectionAttribute ->
            done { actions.writeSelectionAttribute(target.attribute, 127u, target.colourAxis) }
    }

    /** A button release: only a [BindingTarget.Flash] does anything with one. */
    fun release(target: BindingTarget, bindingId: Int): ActionOutcome {
        if (target !is BindingTarget.Flash || !flashTracker.clearPress(bindingId)) return ActionOutcome.DONE
        return when (val inner = target.target) {
            is BindingTarget.FixtureAttribute -> done { actions.flashFixtureAttributeRelease(inner.fixtureKey, inner.attribute) }
            is BindingTarget.GroupAttribute -> done { actions.flashGroupAttributeRelease(inner.groupName, inner.attribute) }
            else -> ActionOutcome.DONE
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SurfaceDispatch::class.java)

        private inline fun done(block: () -> Unit): ActionOutcome {
            block()
            return ActionOutcome.DONE
        }

        private fun wrongKind(target: BindingTarget): ActionOutcome =
            ActionOutcome.Refused(ActionOutcome.WRONG_KIND, "${target::class.simpleName} does not take this kind of input")

        /**
         * Run one input, turning any throw but a cancellation into [ActionOutcome.Failed] so the
         * collector it runs in carries on (`FU-MIDI-SHOW-GUARD`).
         */
        inline fun guarded(what: String, block: () -> ActionOutcome): ActionOutcome = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(what, e)
        }

        @PublishedApi
        internal fun failed(what: String, e: Exception): ActionOutcome {
            logger.warn("{} failed: {}", what, e.toString())
            return ActionOutcome.Failed(e.message ?: e::class.simpleName ?: "error")
        }
    }
}
