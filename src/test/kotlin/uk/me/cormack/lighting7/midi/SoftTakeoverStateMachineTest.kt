package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.midi.SoftTakeoverStateMachine.Verdict
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The jump rule (surface-setup plan D7): one rule for every fader and ring, three arms. */
class SoftTakeoverStateMachineTest {
    private val dev = "dev-1"
    private val ctl = "fader-1"
    private val j = SoftTakeoverStateMachine.JUMP_STEPS

    // --- Which arm a control is on ---

    @Test
    fun `a motor fader and a ring encoder are driven, others undriven, and IMMEDIATE jumps`() {
        val motor = FaderDescriptor("fader-1", "Fader 1", cc = 1, hasMotor = true, motorCc = 1)
        val plain = FaderDescriptor("fader-2", "Fader 2", cc = 2)
        val ring = EncoderDescriptor("enc-1", "Encoder 1", cc = 10, ringCc = 10)
        val bare = EncoderDescriptor("enc-2", "Encoder 2", cc = 11)
        assertEquals(JumpPolicy.DRIVEN, JumpPolicy.of(motor, BindingTakeoverPolicy.PICKUP))
        assertEquals(JumpPolicy.DRIVEN, JumpPolicy.of(motor, null), "PICKUP is the default")
        assertEquals(JumpPolicy.DRIVEN, JumpPolicy.of(ring, null))
        assertEquals(JumpPolicy.UNDRIVEN, JumpPolicy.of(plain, BindingTakeoverPolicy.PICKUP))
        assertEquals(JumpPolicy.UNDRIVEN, JumpPolicy.of(bare, null))
        for (control in listOf(motor, plain, ring, bare)) {
            assertEquals(JumpPolicy.JUMP, JumpPolicy.of(control, BindingTakeoverPolicy.IMMEDIATE), control.controlId)
        }
    }

    // --- JUMP ---

    @Test
    fun `JUMP takes every move and stays engaged`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 100u, JumpPolicy.JUMP)
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 0u, JumpPolicy.JUMP))
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 127u, JumpPolicy.JUMP))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    // --- DRIVEN ---

    @Test
    fun `a driven control takes a move within J of what it shows and reshows one beyond`() {
        val m = SoftTakeoverStateMachine()
        assertEquals(64u.toUByte(), m.setLogical(dev, ctl, 64u, JumpPolicy.DRIVEN))
        assertEquals(Verdict.RESHOW, m.acceptInboundFader(dev, ctl, (64 + j + 1).toUByte(), JumpPolicy.DRIVEN))
        assertEquals(Verdict.RESHOW, m.acceptInboundFader(dev, ctl, (64 - j - 1).toUByte(), JumpPolicy.DRIVEN))
        assertEquals(64u.toUByte(), m.stateFor(dev, ctl).shown, "a reshown move leaves what the control shows")
        assertEquals((64 - j - 1).toUByte(), m.stateFor(dev, ctl).lastPhysical, "but records where it physically is")

        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, (64 + j).toUByte(), JumpPolicy.DRIVEN))
        assertEquals((64 + j).toUByte(), m.stateFor(dev, ctl).shown, "a taken move is what the control shows now")
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state, "a driven control never waits to pick up")
    }

    @Test
    fun `a driven control is followed by a drag, a step at a time`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 0u, JumpPolicy.DRIVEN)
        for (v in 0..127 step 4) assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, v.toUByte(), JumpPolicy.DRIVEN), "at $v")
    }

    @Test
    fun `a driven control takes any move while touched`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 10u, JumpPolicy.DRIVEN)
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 120u, JumpPolicy.DRIVEN, touched = true))
        assertEquals(Verdict.RESHOW, m.acceptInboundFader(dev, ctl, 10u, JumpPolicy.DRIVEN), "untouched, a jump back is stale")
    }

    @Test
    fun `a driven control showing nothing takes any move`() {
        val m = SoftTakeoverStateMachine()
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 127u, JumpPolicy.DRIVEN), "never shown anything")
        m.setLogical(dev, ctl, 20u, JumpPolicy.DRIVEN)
        assertNull(m.setLogical(dev, ctl, null, JumpPolicy.DRIVEN), "mixed: nothing to show")
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 120u, JumpPolicy.DRIVEN), "a mixed value accepts")
    }

    // --- UNDRIVEN ---

    @Test
    fun `an undriven fader with no physical position waits, and takes the first move on its value`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 64u, JumpPolicy.UNDRIVEN)
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
        assertEquals(64u.toUByte(), m.stateFor(dev, ctl).target)
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 64u, JumpPolicy.UNDRIVEN))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `an undriven fader ignores moves until it crosses, from below or above`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 20u, JumpPolicy.UNDRIVEN)
        m.setLogical(dev, ctl, 100u, JumpPolicy.UNDRIVEN)
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
        assertEquals(Verdict.IGNORE, m.acceptInboundFader(dev, ctl, 50u, JumpPolicy.UNDRIVEN), "an undriven fader is never reshown")
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 110u, JumpPolicy.UNDRIVEN))

        m.acceptInboundFader("dev-2", ctl, 120u, JumpPolicy.UNDRIVEN)
        m.setLogical("dev-2", ctl, 30u, JumpPolicy.UNDRIVEN)
        assertEquals(Verdict.IGNORE, m.acceptInboundFader("dev-2", ctl, 80u, JumpPolicy.UNDRIVEN))
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader("dev-2", ctl, 25u, JumpPolicy.UNDRIVEN))
    }

    @Test
    fun `an undriven fader already at its value stays engaged`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 64u, JumpPolicy.UNDRIVEN)
        m.setLogical(dev, ctl, 65u, JumpPolicy.UNDRIVEN)
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
        assertNull(m.stateFor(dev, ctl).target)
    }

    @Test
    fun `a mixed value disarms a waiting undriven fader, and says so once`() = runBlocking {
        val m = SoftTakeoverStateMachine()
        val seen = mutableListOf<SoftTakeoverStateMachine.PickupStateChange>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch { m.changes.collect { seen += it } }
        m.acceptInboundFader(dev, ctl, 10u, JumpPolicy.UNDRIVEN)
        m.setLogical(dev, ctl, 100u, JumpPolicy.UNDRIVEN)
        yield()
        assertEquals(1, seen.size)
        assertEquals(Verdict.IGNORE, m.acceptInboundFader(dev, ctl, 20u, JumpPolicy.UNDRIVEN))

        assertNull(m.setLogical(dev, ctl, null, JumpPolicy.UNDRIVEN))
        yield()
        assertEquals(2, seen.size)
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, seen.last().state)
        assertNull(seen.last().target)
        assertEquals(20u.toUByte(), m.stateFor(dev, ctl).lastPhysical, "the physical position is kept")
        assertEquals(Verdict.ACCEPT, m.acceptInboundFader(dev, ctl, 30u, JumpPolicy.UNDRIVEN))

        m.setLogical(dev, ctl, null, JumpPolicy.UNDRIVEN)
        m.setLogical(dev, ctl, null, JumpPolicy.UNDRIVEN)
        yield()
        assertEquals(2, seen.size, "a second mixed reading changes nothing and emits nothing")
        job.cancel()
    }

    // --- The echo, and rearm ---

    @Test
    fun `after a taken move the control keeps showing it while the function reads back within tolerance`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 60u, JumpPolicy.DRIVEN)
        m.acceptInboundFader(dev, ctl, 66u, JumpPolicy.DRIVEN)
        assertEquals(66u.toUByte(), m.setLogical(dev, ctl, 67u, JumpPolicy.DRIVEN), "one step of rounding is the move's own echo")
        assertEquals(66u.toUByte(), m.setLogical(dev, ctl, 65u, JumpPolicy.DRIVEN))
        assertEquals(66u.toUByte(), m.setLogical(dev, ctl, 69u, JumpPolicy.DRIVEN, tolerance = 3), "within the reading's tolerance")
        assertEquals(70u.toUByte(), m.setLogical(dev, ctl, 70u, JumpPolicy.DRIVEN, tolerance = 3), "beyond it the function moved")
        assertEquals(72u.toUByte(), m.setLogical(dev, ctl, 72u, JumpPolicy.DRIVEN), "the echo is gone once the value moved off it")
    }

    @Test
    fun `a forced re-show shows the function's value over the position last moved to`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 84u, JumpPolicy.DRIVEN)
        m.acceptInboundFader(dev, ctl, 85u, JumpPolicy.DRIVEN)
        assertEquals(85u.toUByte(), m.setLogical(dev, ctl, 84u, JumpPolicy.DRIVEN), "a reading one step off is the move's echo")
        assertEquals(84u.toUByte(), m.setLogical(dev, ctl, 84u, JumpPolicy.DRIVEN, reshow = true), "held at a clamp, the ring is told where the function is")
        assertEquals(84u.toUByte(), m.setLogical(dev, ctl, 84u, JumpPolicy.DRIVEN), "and the echo is gone")
    }

    @Test
    fun `an undriven fader's own echo does not set it waiting`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 40u, JumpPolicy.UNDRIVEN)
        assertEquals(40u.toUByte(), m.setLogical(dev, ctl, 41u, JumpPolicy.UNDRIVEN))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `rearm shows the value and sets an undriven fader waiting, even on its own position`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 64u, JumpPolicy.UNDRIVEN)
        assertEquals(64u.toUByte(), m.setLogical(dev, ctl, 64u, JumpPolicy.UNDRIVEN, rearm = true))
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state, "the physical position is stale")

        m.setLogical("d2", ctl, 60u, JumpPolicy.DRIVEN)
        m.acceptInboundFader("d2", ctl, 62u, JumpPolicy.DRIVEN)
        assertEquals(61u.toUByte(), m.setLogical("d2", ctl, 61u, JumpPolicy.DRIVEN, rearm = true), "rearm drops the echo")
    }

    @Test
    fun `changes flow emits on a pickup transition`() = runBlocking {
        val m = SoftTakeoverStateMachine()
        val emitted = mutableListOf<SoftTakeoverStateMachine.PickupStateChange>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { m.changes.collect { emitted += it } }
        yield()
        m.acceptInboundFader(dev, ctl, 20u, JumpPolicy.UNDRIVEN)
        m.setLogical(dev, ctl, 100u, JumpPolicy.UNDRIVEN)
        yield()
        assertEquals(SoftTakeoverStateMachine.PickupStateChange(dev, ctl, SoftTakeoverStateMachine.State.AWAITING_PICKUP, 100u), emitted.last())
        m.acceptInboundFader(dev, ctl, 110u, JumpPolicy.UNDRIVEN)
        yield()
        assertEquals(SoftTakeoverStateMachine.PickupStateChange(dev, ctl, SoftTakeoverStateMachine.State.ENGAGED, null), emitted.last())
        job.cancel()
    }

    @Test
    fun `clearDevice removes entries for one device only`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader("dev-a", ctl, 50u, JumpPolicy.UNDRIVEN)
        m.acceptInboundFader("dev-b", ctl, 50u, JumpPolicy.UNDRIVEN)
        m.clearDevice("dev-a")
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor("dev-a", ctl).state)
        assertNull(m.stateFor("dev-a", ctl).lastPhysical)
        assertEquals(50.toUByte(), m.stateFor("dev-b", ctl).lastPhysical)
    }
}
