package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.midi.ActionOutcome
import uk.me.cormack.lighting7.midi.BindingTarget
import uk.me.cormack.lighting7.midi.ColourAxis
import uk.me.cormack.lighting7.midi.FanAxis
import uk.me.cormack.lighting7.midi.SurfaceActions
import uk.me.cormack.lighting7.models.CueTargetDto

/**
 * A [SurfaceActions] that records every call — what a MIDI surface's router and the OSC collector
 * dispatch — so both packages' tests can drive a dispatch without a running show. Every action that
 * answers an outcome answers [outcome]'s.
 */
open class RecordingSurfaceActions : SurfaceActions {
    val calls: MutableList<RecordedCall> = java.util.Collections.synchronizedList(mutableListOf())

    /** What every outcome-answering action answers; a test sets a refusal or a throw here. */
    @Volatile
    var outcome: () -> ActionOutcome = { ActionOutcome.DONE }

    /** The write origin each outcome-answering action ran under, in call order. */
    val answerOrigins: MutableList<uk.me.cormack.lighting7.history.WriteOrigin?> = java.util.Collections.synchronizedList(mutableListOf())

    private fun answer(call: RecordedCall): ActionOutcome {
        calls += call
        answerOrigins += uk.me.cormack.lighting7.history.WriteOrigin.current()
        return outcome()
    }

    override fun cueStackGoTo(stackUuid: String, entryUuid: String): ActionOutcome =
        answer(RecordedCall.CueStackGoTo(stackUuid, entryUuid))
    override fun cueStackStandby(stackUuid: String, entryUuid: String): ActionOutcome =
        answer(RecordedCall.CueStackStandby(stackUuid, entryUuid))
    override fun cueStackStop(stackUuid: String): ActionOutcome = answer(RecordedCall.CueStackStop(stackUuid))
    override fun looseCueSet(cueUuid: String, on: Boolean): ActionOutcome = answer(RecordedCall.LooseCueSet(cueUuid, on))
    override fun lookSet(lookUuid: String, on: Boolean): ActionOutcome = answer(RecordedCall.LookSet(lookUuid, on))
    override fun setStackFxAmount(stackId: Int, stackUuid: String?, amount: Double): ActionOutcome =
        answer(RecordedCall.SetStackFxAmount(stackId, stackUuid, amount))

    /** The write origin each fixture write ran under. */
    val origins = mutableListOf<uk.me.cormack.lighting7.history.WriteOrigin?>()
    override fun writeFixtureAttribute(fixtureKey: String, attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteFixture(fixtureKey, attribute, midiValue7Bit, colourAxis)
        origins += uk.me.cormack.lighting7.history.WriteOrigin.current()
    }
    override fun writeGroupAttribute(groupName: String, attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteGroup(groupName, attribute, midiValue7Bit, colourAxis)
    }
    override fun flashFixtureAttributePress(fixtureKey: String, attribute: String, max: UByte) {
        calls += RecordedCall.FlashFixturePress(fixtureKey, attribute, max)
    }
    override fun flashGroupAttributePress(groupName: String, attribute: String, max: UByte) {
        calls += RecordedCall.FlashGroupPress(groupName, attribute, max)
    }
    override fun flashFixtureAttributeRelease(fixtureKey: String, attribute: String) {
        calls += RecordedCall.FlashFixtureRelease(fixtureKey, attribute)
    }
    override fun flashGroupAttributeRelease(groupName: String, attribute: String) {
        calls += RecordedCall.FlashGroupRelease(groupName, attribute)
    }
    override fun cueStackGo(stackId: Int, stackUuid: String?): ActionOutcome = answer(RecordedCall.CueStackGo(stackId, stackUuid))
    override fun cueStackBack(stackId: Int, stackUuid: String?): ActionOutcome = answer(RecordedCall.CueStackBack(stackId, stackUuid))
    override fun cueStackPause(stackId: Int, stackUuid: String?): ActionOutcome = answer(RecordedCall.CueStackPause(stackId, stackUuid))
    override fun fireCue(cueId: Int, cueUuid: String?): ActionOutcome = answer(RecordedCall.FireCue(cueId, cueUuid))
    override fun writeSelectionAttribute(attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteSelection(attribute, midiValue7Bit, colourAxis)
    }
    override fun writeSelectionFan(axis: FanAxis, midiValue7Bit: UByte) { calls += RecordedCall.WriteFan(axis, midiValue7Bit) }
    override fun selectTarget(target: CueTargetDto, mode: BindingTarget.SelectMode) {
        calls += RecordedCall.SelectTarget(target, mode)
    }
    override fun clearSelection() { calls += RecordedCall.ClearSelection }
    override fun locateSelection() { calls += RecordedCall.LocateSelection }
    override fun toggleBlackout(): Boolean { calls += RecordedCall.ToggleBlackout; return true }
    override fun toggleGrandMaster(): Boolean { calls += RecordedCall.ToggleGrandMaster; return true }
    override fun writeGrandMaster(midiValue7Bit: UByte) { calls += RecordedCall.WriteGrandMaster(midiValue7Bit) }
    override fun writeStackFxAmount(stackId: Int, stackUuid: String?, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteStackFxAmount(stackId, stackUuid, midiValue7Bit)
    }
    override fun writeSpeedMasterBpm(masterUuid: String?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteSpeedMasterBpm(masterUuid, minBpm, maxBpm, midiValue7Bit)
    }
    override fun tapSpeedMaster(masterUuid: String?) {
        calls += RecordedCall.TapSpeedMaster(masterUuid)
    }
    override fun scaleSpeedMaster(masterUuid: String?, factor: Double) {
        calls += RecordedCall.ScaleSpeedMaster(masterUuid, factor)
    }
    override fun writeFxSpeed(midiValue7Bit: UByte, encoder: Boolean) {
        calls += RecordedCall.WriteFxSpeed(midiValue7Bit, encoder)
    }
    override fun applyLook(lookUuid: String): ActionOutcome = answer(RecordedCall.ApplyLook(lookUuid))
    override fun pressPalette(paletteUuid: String) { calls += RecordedCall.PressPalette(paletteUuid) }
    override fun pressPad(padUuid: String): ActionOutcome = answer(RecordedCall.PressPad(padUuid))
    override fun pickUpPad(padUuid: String) { calls += RecordedCall.PickUpPad(padUuid) }
    override fun handPlaceInBank(bankUuid: String) { calls += RecordedCall.HandPlaceInBank(bankUuid) }
    override fun handDrop() { calls += RecordedCall.HandDrop }
    override fun historyMove(source: uk.me.cormack.lighting7.history.HistorySource, undo: Boolean, programmerOnly: Boolean) {
        calls += RecordedCall.HistoryMove(source.key, undo, programmerOnly)
    }
    override fun buskPageStep(delta: Int) { calls += RecordedCall.BuskPageStep(delta) }
    override fun buskPageSet(pageUuid: String): ActionOutcome = answer(RecordedCall.BuskPageSet(pageUuid))

    val windowCommands = mutableListOf<String>()
    val subselects = mutableListOf<uk.me.cormack.lighting7.state.SubselectMode>()
    val selectionSteps = mutableListOf<Int>()
    override fun buskFocusSet(windowName: String, focus: String) { windowCommands += "focus:$windowName:$focus" }
    override fun buskSheetToggle(windowName: String) { windowCommands += "sheet:$windowName" }
    override fun selectionStep(delta: Int) { selectionSteps += delta }
    override fun selectionCells(mode: uk.me.cormack.lighting7.state.SubselectMode) { subselects += mode }
}


sealed class RecordedCall {
    data class WriteFan(val axis: FanAxis, val value: UByte) : RecordedCall()
    data class WriteFixture(val fixtureKey: String, val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class WriteGroup(val groupName: String, val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class FlashFixturePress(val fixtureKey: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashGroupPress(val groupName: String, val prop: String, val max: UByte) : RecordedCall()
    data class FlashFixtureRelease(val fixtureKey: String, val prop: String) : RecordedCall()
    data class FlashGroupRelease(val groupName: String, val prop: String) : RecordedCall()
    data class CueStackGo(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class CueStackBack(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class CueStackPause(val stackId: Int, val stackUuid: String? = null) : RecordedCall()
    data class FireCue(val cueId: Int, val cueUuid: String? = null) : RecordedCall()
    data class CueStackGoTo(val stackUuid: String, val entryUuid: String) : RecordedCall()
    data class CueStackStandby(val stackUuid: String, val entryUuid: String) : RecordedCall()
    data class CueStackStop(val stackUuid: String) : RecordedCall()
    data class LooseCueSet(val cueUuid: String, val on: Boolean) : RecordedCall()
    data class LookSet(val lookUuid: String, val on: Boolean) : RecordedCall()
    data class SetStackFxAmount(val stackId: Int, val stackUuid: String?, val amount: Double) : RecordedCall()
    data class WriteSelection(val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class SelectTarget(val target: CueTargetDto, val mode: BindingTarget.SelectMode) : RecordedCall()
    data object ClearSelection : RecordedCall()
    data object LocateSelection : RecordedCall()
    data object ToggleBlackout : RecordedCall()
    data object ToggleGrandMaster : RecordedCall()
    data class WriteGrandMaster(val value: UByte) : RecordedCall()
    data class WriteStackFxAmount(val stackId: Int, val stackUuid: String?, val value: UByte) : RecordedCall()
    data class ApplyLook(val lookUuid: String) : RecordedCall()
    data class PressPalette(val paletteUuid: String) : RecordedCall()
    data class PressPad(val padUuid: String) : RecordedCall()
    data class PickUpPad(val padUuid: String) : RecordedCall()
    data class HandPlaceInBank(val bankUuid: String) : RecordedCall()
    data object HandDrop : RecordedCall()
    data class HistoryMove(val sourceKey: String, val undo: Boolean, val programmerOnly: Boolean) : RecordedCall()
    data class BuskPageStep(val delta: Int) : RecordedCall()
    data class BuskPageSet(val pageUuid: String) : RecordedCall()
    data class WriteSpeedMasterBpm(
        val masterUuid: String?,
        val minBpm: Double,
        val maxBpm: Double,
        val value: UByte,
    ) : RecordedCall()
    data class TapSpeedMaster(val masterUuid: String?) : RecordedCall()
    data class ScaleSpeedMaster(val masterUuid: String?, val factor: Double) : RecordedCall()
    data class WriteFxSpeed(val value7Bit: UByte, val encoder: Boolean) : RecordedCall()
}
