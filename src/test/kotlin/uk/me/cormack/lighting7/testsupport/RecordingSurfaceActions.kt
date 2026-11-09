package uk.me.cormack.lighting7.testsupport

import uk.me.cormack.lighting7.midi.ActionOutcome
import uk.me.cormack.lighting7.midi.ColourAxis
import uk.me.cormack.lighting7.midi.FanAxis
import uk.me.cormack.lighting7.midi.Subject
import uk.me.cormack.lighting7.midi.SurfaceActions
import uk.me.cormack.lighting7.models.CueTargetDto
import java.util.UUID

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

    /** The write origin each level write ran under. */
    val origins = mutableListOf<uk.me.cormack.lighting7.history.WriteOrigin?>()

    override fun writeLevel(on: Subject, attribute: String, midiValue7Bit: UByte, colourAxis: ColourAxis?) {
        calls += RecordedCall.WriteLevel(on, attribute, midiValue7Bit, colourAxis)
        origins += uk.me.cormack.lighting7.history.WriteOrigin.current()
    }

    override fun flashPress(on: Subject, attribute: String, percent: Int): (() -> Unit) {
        calls += RecordedCall.FlashPress(on, attribute, percent)
        return { calls += RecordedCall.FlashRelease(on, attribute) }
    }

    override fun cueStackGo(stack: UUID): ActionOutcome = answer(RecordedCall.CueStackGo(stack))
    override fun cueStackBack(stack: UUID): ActionOutcome = answer(RecordedCall.CueStackBack(stack))
    override fun cueStackPause(stack: UUID): ActionOutcome = answer(RecordedCall.CueStackPause(stack))
    override fun cueStackGoTo(entry: UUID): ActionOutcome = answer(RecordedCall.CueStackGoTo(entry))
    override fun cueStackStandby(entry: UUID): ActionOutcome = answer(RecordedCall.CueStackStandby(entry))
    override fun cueStackStop(stack: UUID): ActionOutcome = answer(RecordedCall.CueStackStop(stack))
    override fun fireCue(cue: UUID): ActionOutcome = answer(RecordedCall.FireCue(cue))
    override fun looseCueSet(cue: UUID, on: Boolean): ActionOutcome = answer(RecordedCall.LooseCueSet(cue, on))
    override fun lookSet(look: UUID, on: Boolean): ActionOutcome = answer(RecordedCall.LookSet(look, on))
    override fun applyLook(look: UUID): ActionOutcome = answer(RecordedCall.ApplyLook(look))
    override fun setStackFxAmount(stack: UUID, amount: Double): ActionOutcome = answer(RecordedCall.SetStackFxAmount(stack, amount))
    override fun writeStackFxAmount(stack: UUID, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteStackFxAmount(stack, midiValue7Bit)
    }

    override fun writeFan(on: Subject, axis: FanAxis, midiValue7Bit: UByte) { calls += RecordedCall.WriteFan(axis, midiValue7Bit, on) }
    override fun writeFxSpeed(on: Subject, midiValue7Bit: UByte, encoder: Boolean) {
        calls += RecordedCall.WriteFxSpeed(midiValue7Bit, encoder, on)
    }

    override fun selectTarget(target: CueTargetDto, replace: Boolean) {
        calls += RecordedCall.SelectTarget(target, replace)
    }
    override fun clearSelection() { calls += RecordedCall.ClearSelection }
    override fun locate(on: Subject) { calls += RecordedCall.Locate(on) }

    /** Whether [setBlind] last left the programmer Blind. */
    @Volatile
    var blind = false
    override fun clearProgrammer(fadeMs: Long) { calls += RecordedCall.ClearProgrammer(fadeMs) }
    override fun isBlind(): Boolean = blind
    override fun setBlind(blind: Boolean, fadeMs: Long): ActionOutcome {
        this.blind = blind
        return answer(RecordedCall.SetBlind(blind, fadeMs))
    }
    override fun release(on: Subject, fadeMs: Long): ActionOutcome = answer(RecordedCall.Release(on, fadeMs))
    override fun record(stack: UUID): ActionOutcome = answer(RecordedCall.Record(stack))
    override fun update(): ActionOutcome = answer(RecordedCall.Update)
    override fun toggleBlackout(): Boolean { calls += RecordedCall.ToggleBlackout; return true }
    override fun toggleGrandMaster(): Boolean { calls += RecordedCall.ToggleGrandMaster; return true }
    override fun writeGrandMaster(midiValue7Bit: UByte) { calls += RecordedCall.WriteGrandMaster(midiValue7Bit) }
    override fun setBlackout(on: Boolean): ActionOutcome = answer(RecordedCall.SetBlackout(on))
    override fun setGrandMasterLevel(percent: Double): ActionOutcome = answer(RecordedCall.SetGrandMasterLevel(percent))

    override fun writeSpeedMasterBpm(master: UUID?, minBpm: Double, maxBpm: Double, midiValue7Bit: UByte) {
        calls += RecordedCall.WriteSpeedMasterBpm(master, minBpm, maxBpm, midiValue7Bit)
    }
    override fun tapSpeedMaster(master: UUID?): ActionOutcome = answer(RecordedCall.TapSpeedMaster(master))
    override fun setSpeedMasterBpm(master: UUID?, bpm: Double): ActionOutcome = answer(RecordedCall.SetSpeedMasterBpm(master, bpm))
    override fun scaleSpeedMaster(master: UUID?, factor: Double) {
        calls += RecordedCall.ScaleSpeedMaster(master, factor)
    }

    override fun pressPalette(palette: UUID) { calls += RecordedCall.PressPalette(palette) }
    override fun pressPad(pad: UUID): ActionOutcome = answer(RecordedCall.PressPad(pad))
    override fun pickUpPad(pad: UUID) { calls += RecordedCall.PickUpPad(pad) }
    override fun handPlaceInBank(bank: UUID) { calls += RecordedCall.HandPlaceInBank(bank) }
    override fun handDrop() { calls += RecordedCall.HandDrop }
    override fun historyMove(source: uk.me.cormack.lighting7.history.HistorySource, undo: Boolean, programmerOnly: Boolean) {
        calls += RecordedCall.HistoryMove(source.key, undo, programmerOnly)
    }
    override fun buskPageStep(delta: Int) { calls += RecordedCall.BuskPageStep(delta) }
    override fun buskPageSet(page: UUID): ActionOutcome = answer(RecordedCall.BuskPageSet(page))

    val windowCommands = mutableListOf<String>()
    val subselects = mutableListOf<uk.me.cormack.lighting7.state.SubselectMode>()
    override fun buskFocusSet(windowName: String, focus: String) { windowCommands += "focus:$windowName:$focus" }
    override fun buskSheetToggle(windowName: String) { windowCommands += "sheet:$windowName" }
    override fun subselect(mode: uk.me.cormack.lighting7.state.SubselectMode) { subselects += mode }
}

sealed class RecordedCall {
    data class WriteLevel(val on: Subject, val prop: String, val value: UByte, val axis: ColourAxis? = null) : RecordedCall()
    data class FlashPress(val on: Subject, val prop: String, val percent: Int) : RecordedCall()
    data class FlashRelease(val on: Subject, val prop: String) : RecordedCall()
    data class WriteFan(val axis: FanAxis, val value: UByte, val on: Subject = Subject.Selection) : RecordedCall()
    data class CueStackGo(val stack: UUID) : RecordedCall()
    data class CueStackBack(val stack: UUID) : RecordedCall()
    data class CueStackPause(val stack: UUID) : RecordedCall()
    data class CueStackGoTo(val entry: UUID) : RecordedCall()
    data class CueStackStandby(val entry: UUID) : RecordedCall()
    data class CueStackStop(val stack: UUID) : RecordedCall()
    data class FireCue(val cue: UUID) : RecordedCall()
    data class LooseCueSet(val cue: UUID, val on: Boolean) : RecordedCall()
    data class LookSet(val look: UUID, val on: Boolean) : RecordedCall()
    data class ApplyLook(val look: UUID) : RecordedCall()
    data class SetStackFxAmount(val stack: UUID, val amount: Double) : RecordedCall()
    data class WriteStackFxAmount(val stack: UUID, val value: UByte) : RecordedCall()
    data class SelectTarget(val target: CueTargetDto, val replace: Boolean) : RecordedCall()
    data object ClearSelection : RecordedCall()
    data class Locate(val on: Subject = Subject.Selection) : RecordedCall()
    data class ClearProgrammer(val fadeMs: Long) : RecordedCall()
    data class SetBlind(val blind: Boolean, val fadeMs: Long) : RecordedCall()
    data class Release(val on: Subject, val fadeMs: Long) : RecordedCall()
    data class Record(val stack: UUID) : RecordedCall()
    data object Update : RecordedCall()
    data object ToggleBlackout : RecordedCall()
    data object ToggleGrandMaster : RecordedCall()
    data class WriteGrandMaster(val value: UByte) : RecordedCall()
    data class PressPalette(val palette: UUID) : RecordedCall()
    data class PressPad(val pad: UUID) : RecordedCall()
    data class PickUpPad(val pad: UUID) : RecordedCall()
    data class HandPlaceInBank(val bank: UUID) : RecordedCall()
    data object HandDrop : RecordedCall()
    data class HistoryMove(val sourceKey: String, val undo: Boolean, val programmerOnly: Boolean) : RecordedCall()
    data class BuskPageStep(val delta: Int) : RecordedCall()
    data class BuskPageSet(val page: UUID) : RecordedCall()
    data class WriteSpeedMasterBpm(val master: UUID?, val minBpm: Double, val maxBpm: Double, val value: UByte) : RecordedCall()
    data class TapSpeedMaster(val master: UUID?) : RecordedCall()
    data class SetSpeedMasterBpm(val master: UUID?, val bpm: Double) : RecordedCall()
    data class SetBlackout(val on: Boolean) : RecordedCall()
    data class SetGrandMasterLevel(val percent: Double) : RecordedCall()
    data class ScaleSpeedMaster(val master: UUID?, val factor: Double) : RecordedCall()
    data class WriteFxSpeed(val value7Bit: UByte, val encoder: Boolean, val on: Subject = Subject.Selection) : RecordedCall()
}
