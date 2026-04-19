package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SoftTakeoverStateMachineTest {
    private val dev = "dev-1"
    private val ctl = "fader-1"

    @Test
    fun `IMMEDIATE policy accepts every inbound event and stays engaged`() {
        val m = SoftTakeoverStateMachine()
        assertTrue(m.acceptInboundFader(dev, ctl, 50u, BindingTakeoverPolicy.IMMEDIATE))
        assertTrue(m.acceptInboundFader(dev, ctl, 0u, BindingTakeoverPolicy.IMMEDIATE))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `PICKUP with no prior physical value engages on first event within tolerance`() {
        val m = SoftTakeoverStateMachine()
        m.setLogical(dev, ctl, 64u, BindingTakeoverPolicy.PICKUP)
        // No prior physical → AWAITING_PICKUP. First inbound event becomes the physical
        // position and we only engage if it crosses the target.
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
        // 64 is exactly the target, so it engages.
        assertTrue(m.acceptInboundFader(dev, ctl, 64u, BindingTakeoverPolicy.PICKUP))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `PICKUP rejects events that don't cross the target`() {
        val m = SoftTakeoverStateMachine()
        // Establish physical position at 20.
        m.acceptInboundFader(dev, ctl, 20u, BindingTakeoverPolicy.PICKUP)
        // Logical jumps to 100 (e.g. cue load).
        m.setLogical(dev, ctl, 100u, BindingTakeoverPolicy.PICKUP)
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
        // User moves fader to 50 — still below target.
        assertFalse(m.acceptInboundFader(dev, ctl, 50u, BindingTakeoverPolicy.PICKUP))
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
        // User continues past target — pickup achieved.
        assertTrue(m.acceptInboundFader(dev, ctl, 110u, BindingTakeoverPolicy.PICKUP))
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `PICKUP crossing from above also engages`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 120u, BindingTakeoverPolicy.PICKUP)
        m.setLogical(dev, ctl, 30u, BindingTakeoverPolicy.PICKUP)
        assertFalse(m.acceptInboundFader(dev, ctl, 80u, BindingTakeoverPolicy.PICKUP))
        assertTrue(m.acceptInboundFader(dev, ctl, 25u, BindingTakeoverPolicy.PICKUP))
    }

    @Test
    fun `setLogical within tolerance keeps engaged when previously engaged`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 64u, BindingTakeoverPolicy.PICKUP)
        // The fader drove the value — logical and physical both at 64. A subsequent setLogical
        // of 64 should not enter pickup.
        m.setLogical(dev, ctl, 64u, BindingTakeoverPolicy.PICKUP)
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor(dev, ctl).state)
        assertNull(m.stateFor(dev, ctl).target)
    }

    @Test
    fun `forcePickup always transitions regardless of current physical`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader(dev, ctl, 64u, BindingTakeoverPolicy.PICKUP)
        // Even if we're already at the value, forcePickup makes the fader pick up again —
        // used on bank switches where we deliberately require re-engagement.
        m.forcePickup(dev, ctl, 64u)
        assertEquals(SoftTakeoverStateMachine.State.AWAITING_PICKUP, m.stateFor(dev, ctl).state)
    }

    @Test
    fun `changes flow emits on state transition`() = runBlocking {
        val m = SoftTakeoverStateMachine()
        val emitted = mutableListOf<SoftTakeoverStateMachine.PickupStateChange>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { m.changes.collect { emitted += it } }
        yield()
        m.acceptInboundFader(dev, ctl, 20u, BindingTakeoverPolicy.PICKUP)
        m.setLogical(dev, ctl, 100u, BindingTakeoverPolicy.PICKUP)
        yield()
        assertTrue(emitted.any { it.state == SoftTakeoverStateMachine.State.AWAITING_PICKUP })
        m.acceptInboundFader(dev, ctl, 110u, BindingTakeoverPolicy.PICKUP)
        yield()
        assertTrue(emitted.any { it.state == SoftTakeoverStateMachine.State.ENGAGED })
        job.cancel()
    }

    @Test
    fun `clearDevice removes entries for one device only`() {
        val m = SoftTakeoverStateMachine()
        m.acceptInboundFader("dev-a", ctl, 50u, BindingTakeoverPolicy.PICKUP)
        m.acceptInboundFader("dev-b", ctl, 50u, BindingTakeoverPolicy.PICKUP)
        m.clearDevice("dev-a")
        // After clear, stateFor returns the default entry.
        assertEquals(SoftTakeoverStateMachine.State.ENGAGED, m.stateFor("dev-a", ctl).state)
        assertNull(m.stateFor("dev-a", ctl).lastPhysical)
        // dev-b still has its state.
        assertEquals(50.toUByte(), m.stateFor("dev-b", ctl).lastPhysical)
    }
}

