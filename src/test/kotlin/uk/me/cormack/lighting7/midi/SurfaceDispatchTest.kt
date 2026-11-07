package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [SurfaceDispatch], the dispatch MIDI and OSC share (OSC plan D2): what a target answers, and the
 * per-input guard that keeps a collector alive when an action throws (`FU-MIDI-SHOW-GUARD`).
 */
class SurfaceDispatchTest {
    private val actions = RecordingSurfaceActions()
    private val dispatch = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState(), FlashStateTracker())
    private val origin = SurfaceDispatch.Origin("osc", -1, HistorySource.osc(1, "QLab"))

    @Test
    fun `the new targets reach their actions, and an outcome comes back`() {
        assertEquals(ActionOutcome.DONE, dispatch.press(BindingTarget.CueStackGoTo("s", "e"), origin))
        assertEquals(ActionOutcome.DONE, dispatch.press(BindingTarget.CueStackStandby("s", "e"), origin))
        assertEquals(ActionOutcome.DONE, dispatch.press(BindingTarget.CueStackStop("s"), origin))
        assertEquals(ActionOutcome.DONE, dispatch.press(BindingTarget.LooseCueSet("c", on = true), origin))
        assertEquals(ActionOutcome.DONE, dispatch.press(BindingTarget.LookSet("l", on = false), origin))
        assertEquals(ActionOutcome.DONE, dispatch.level(BindingTarget.StackFxAmount(3, "s"), 0.5))
        assertEquals(
            listOf(
                RecordedCall.CueStackGoTo("s", "e"), RecordedCall.CueStackStandby("s", "e"), RecordedCall.CueStackStop("s"),
                RecordedCall.LooseCueSet("c", true), RecordedCall.LookSet("l", false), RecordedCall.SetStackFxAmount(3, "s", 0.5),
            ),
            actions.calls,
        )
        actions.outcome = { ActionOutcome.NotFound("No stack") }
        assertEquals(ActionOutcome.NotFound("No stack"), dispatch.press(BindingTarget.CueStackGo(1, "s"), origin))
    }

    @Test
    fun `a target pressed with the wrong kind of input is refused, not dropped silently`() {
        assertIs<ActionOutcome.Refused>(dispatch.press(BindingTarget.GrandMasterFader, origin))
        assertIs<ActionOutcome.Refused>(dispatch.level(BindingTarget.CueStackGo(1, "s"), 1.0))
        assertIs<ActionOutcome.Refused>(dispatch.continuous(BindingTarget.CueStackGoTo("s", "e"), 64u))
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun `the guard turns a throw into a failed outcome, so the input after it still runs`() {
        actions.outcome = { throw IllegalStateException("No show: a project switch is in flight") }
        val failed = SurfaceDispatch.guarded("test") { dispatch.press(BindingTarget.CueStackGo(1, "s"), origin) }
        assertEquals(ActionOutcome.Failed("No show: a project switch is in flight"), failed)
        actions.outcome = { ActionOutcome.DONE }
        assertEquals(ActionOutcome.DONE, SurfaceDispatch.guarded("test") { dispatch.press(BindingTarget.CueStackGo(1, "s"), origin) })
    }
}
