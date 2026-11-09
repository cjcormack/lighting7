package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import uk.me.cormack.lighting7.history.HistorySource
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.testsupport.RecordedCall
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * [SurfaceDispatch], the dispatch MIDI and OSC share (OSC plan D2): what a binding answers, how each
 * [How] becomes an operation, and the per-input guard that keeps a collector alive when an action
 * throws (`FU-MIDI-SHOW-GUARD`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SurfaceDispatchTest {
    private val actions = RecordingSurfaceActions()
    private val dispatch = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState())
    private val osc = SurfaceDispatch.Origin("osc", HistorySource.osc(1, "QLab"))

    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val entry = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val cue = UUID.fromString("33333333-3333-4333-8333-333333333333")
    private val look = UUID.fromString("44444444-4444-4444-8444-444444444444")
    private val wash = CueTargetDto("group", "front-wash")

    private fun record(kind: RecordKind, uuid: UUID) = Subject.Record(kind, uuid)

    private fun surface(displayKey: String = "x-touch", controlId: String = "btn-1", bindingId: Int = 1) =
        SurfaceDispatch.Origin(
            "x-touch-compact-standard",
            HistorySource.surface(displayKey),
            SurfaceDispatch.HoldKey(displayKey, controlId, ControlPart.MAIN),
            bindingId,
        )

    private fun press(binding: Binding, origin: SurfaceDispatch.Origin = osc) =
        dispatch.dispatch(binding, SurfaceDispatch.Input.Press, origin)

    @Test
    fun `record bindings reach their actions by uuid, and an outcome comes back`() {
        assertEquals(ActionOutcome.DONE, press(Binding(Does.GoTo, record(RecordKind.ENTRY, entry))))
        assertEquals(ActionOutcome.DONE, press(Binding(Does.Standby, record(RecordKind.ENTRY, entry))))
        assertEquals(ActionOutcome.DONE, press(Binding(Does.Stop, record(RecordKind.STACK, stack))))
        assertEquals(
            ActionOutcome.DONE,
            dispatch.dispatch(Binding(Does.FxAmount, record(RecordKind.STACK, stack)), SurfaceDispatch.Input.Set(0.5), osc),
        )
        assertEquals(
            listOf(
                RecordedCall.CueStackGoTo(entry), RecordedCall.CueStackStandby(entry), RecordedCall.CueStackStop(stack),
                RecordedCall.SetStackFxAmount(stack, 0.5),
            ),
            actions.calls,
        )
        actions.outcome = { ActionOutcome.NotFound("No stack") }
        assertEquals(ActionOutcome.NotFound("No stack"), press(Binding(Does.Go, record(RecordKind.STACK, stack))))
    }

    @Test
    fun `a binding given the wrong kind of input, or a subject its spec does not take, is refused`() {
        assertIs<ActionOutcome.Refused>(press(Binding(Does.FxAmount, record(RecordKind.STACK, stack))))
        assertIs<ActionOutcome.Refused>(press(Binding(Does.Fan(FanAxis.PAN), Subject.Selection)))
        assertIs<ActionOutcome.Refused>(
            dispatch.dispatch(Binding(Does.Go, record(RecordKind.STACK, stack)), SurfaceDispatch.Input.Set(1.0), osc),
        )
        assertIs<ActionOutcome.Refused>(
            dispatch.dispatch(Binding(Does.GoTo, record(RecordKind.ENTRY, entry)), SurfaceDispatch.Input.Move(64u), osc),
        )
        val wrongSubject = assertIs<ActionOutcome.Refused>(press(Binding(Does.Go, record(RecordKind.CUE, cue))))
        assertEquals(ActionOutcome.WRONG_SUBJECT, wrongSubject.code)
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun `Toggle presses the function's toggle`() {
        press(Binding(Does.Look, record(RecordKind.LOOK, look)))
        press(Binding(Does.Cue, record(RecordKind.CUE, cue), How.TOGGLE))
        press(Binding(Does.Blackout))
        assertEquals(
            listOf(RecordedCall.ApplyLook(look), RecordedCall.FireCue(cue), RecordedCall.ToggleBlackout),
            actions.calls,
        )
    }

    @Test
    fun `On and Off set the state, and a release does nothing`() {
        press(Binding(Does.Look, record(RecordKind.LOOK, look), How.ON))
        press(Binding(Does.Look, record(RecordKind.LOOK, look), How.OFF))
        press(Binding(Does.Cue, record(RecordKind.CUE, cue), How.ON))
        dispatch.dispatch(Binding(Does.Cue, record(RecordKind.CUE, cue), How.ON), SurfaceDispatch.Input.Release, surface())
        press(Binding(Does.Blackout, how = How.OFF))
        assertEquals(
            listOf(
                RecordedCall.LookSet(look, true), RecordedCall.LookSet(look, false),
                RecordedCall.LooseCueSet(cue, true), RecordedCall.SetBlackout(false),
            ),
            actions.calls,
        )
    }

    @Test
    fun `While held presses on and its release undoes exactly what the press wrote`() {
        val flash = Binding(Does.Flash("dimmer", 80), Subject.Fixture("hex-1"))
        val origin = surface(bindingId = 7)

        dispatch.dispatch(flash, SurfaceDispatch.Input.Press, origin)
        assertTrue(dispatch.holds.isHeldBinding(7))
        assertEquals(ActionOutcome.Done("held"), dispatch.dispatch(flash, SurfaceDispatch.Input.Press, origin), "a retrigger")

        dispatch.dispatch(flash, SurfaceDispatch.Input.Release, origin)
        dispatch.dispatch(flash, SurfaceDispatch.Input.Release, origin)

        assertEquals(
            listOf(
                RecordedCall.FlashPress(Subject.Fixture("hex-1"), "dimmer", 80),
                RecordedCall.FlashRelease(Subject.Fixture("hex-1"), "dimmer"),
            ),
            actions.calls,
        )
        assertFalse(dispatch.holds.isHeldBinding(7))
    }

    @Test
    fun `While held on a function with no release of its own sends off on release`() {
        val blackout = Binding(Does.Blackout, how = How.WHILE_HELD)
        dispatch.dispatch(blackout, SurfaceDispatch.Input.Press, surface())
        dispatch.dispatch(blackout, SurfaceDispatch.Input.Release, surface())
        assertEquals(listOf<RecordedCall>(RecordedCall.SetBlackout(true), RecordedCall.SetBlackout(false)), actions.calls)
    }

    @Test
    fun `Toggle-hold-replaces toggles on press and replaces once held past the hold`() = runTest {
        val held = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState(), scope = { this }, selectHoldMs = 500L)
        val select = Binding(Does.Select, Subject.Group("front-wash"))

        held.dispatch(select, SurfaceDispatch.Input.Press, surface())
        advanceTimeBy(100L)
        held.dispatch(select, SurfaceDispatch.Input.Release, surface())
        advanceTimeBy(5_000L)
        assertEquals(listOf<RecordedCall>(RecordedCall.SelectTarget(wash, replace = false)), actions.calls, "a short press")

        actions.calls.clear()
        held.dispatch(select, SurfaceDispatch.Input.Press, surface())
        advanceTimeBy(600L)
        held.dispatch(select, SurfaceDispatch.Input.Release, surface())
        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(wash, replace = false), RecordedCall.SelectTarget(wash, replace = true)),
            actions.calls,
            "held past the hold",
        )

        actions.calls.clear()
        held.dispatch(select, SurfaceDispatch.Input.Press, surface("left"))
        held.releaseDevice("left")
        advanceTimeBy(5_000L)
        assertEquals(
            listOf<RecordedCall>(RecordedCall.SelectTarget(wash, replace = false)),
            actions.calls,
            "a device unplugged mid-hold replaces nothing",
        )
    }

    @Test
    fun `Replace replaces on press and arms no hold`() = runTest {
        val held = SurfaceDispatch(actions, ActiveBankState(), EncoderBankState(), scope = { this }, selectHoldMs = 500L)
        held.dispatch(Binding(Does.Select, Subject.Group("front-wash"), How.REPLACE), SurfaceDispatch.Input.Press, surface())
        advanceTimeBy(5_000L)
        assertEquals(listOf<RecordedCall>(RecordedCall.SelectTarget(wash, replace = true)), actions.calls)
    }

    @Test
    fun `a layer and an encoder page act on the device the press came from`() {
        val bankState = ActiveBankState()
        val encoderBankState = EncoderBankState()
        val desk = SurfaceDispatch(actions, bankState, encoderBankState)
        desk.dispatch(Binding(Does.Layer("layer-b")), SurfaceDispatch.Input.Press, surface())
        desk.dispatch(Binding(Does.EncoderPage("rgbColour", ColourAxis.SATURATION)), SurfaceDispatch.Input.Press, surface())
        assertEquals("layer-b", bankState.bankFor("x-touch-compact-standard"))
        assertEquals(EncoderBankSelection("rgbColour", ColourAxis.SATURATION), encoderBankState.selectionFor("x-touch-compact-standard"))
        assertEquals(emptyList(), actions.calls)
    }

    @Test
    fun `the guard turns a throw into a failed outcome, so the input after it still runs`() {
        val go = Binding(Does.Go, record(RecordKind.STACK, stack))
        actions.outcome = { throw IllegalStateException("No show: a project switch is in flight") }
        val failed = SurfaceDispatch.guarded("test") { press(go) }
        assertEquals(ActionOutcome.Failed("No show: a project switch is in flight"), failed)
        actions.outcome = { ActionOutcome.DONE }
        assertEquals(ActionOutcome.DONE, SurfaceDispatch.guarded("test") { press(go) })
    }
}
