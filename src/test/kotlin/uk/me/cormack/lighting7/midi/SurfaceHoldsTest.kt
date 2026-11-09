package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceHoldsTest {

    private fun key(displayKey: String, controlId: String = "btn-1", part: ControlPart = ControlPart.MAIN) =
        SurfaceDispatch.HoldKey(displayKey, controlId, part)

    /** Collect [holds]' changes while [block] runs. */
    private fun changesDuring(holds: SurfaceHolds, block: () -> Unit): List<SurfaceHolds.HoldChange> = runBlocking {
        val emitted = mutableListOf<SurfaceHolds.HoldChange>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { holds.changes.collect { emitted += it } }
        yield()
        block()
        yield()
        job.cancel()
        scope.cancel()
        emitted
    }

    @Test
    fun `a release undoes its hold once`() {
        val holds = SurfaceHolds()
        var released = 0
        holds.hold(key("left"), 1) { released++ }
        assertTrue(holds.isHeld(key("left")))

        holds.release(key("left"))
        holds.release(key("left"))

        assertEquals(1, released)
        assertFalse(holds.isHeld(key("left")))
    }

    @Test
    fun `one binding held on two parts stays held until both let go`() {
        val holds = SurfaceHolds()
        holds.hold(key("left"), 1) {}
        holds.hold(key("right"), 1) {}

        holds.release(key("left"))
        assertTrue(holds.isHeldBinding(1), "the right-hand device still holds it")
        assertFalse(holds.isHeld(key("left")))

        holds.release(key("right"))
        assertFalse(holds.isHeldBinding(1))
    }

    @Test
    fun `a binding's off edge comes when its last hold goes`() {
        val holds = SurfaceHolds()
        val emitted = changesDuring(holds) {
            holds.hold(key("left"), 1) {}
            holds.hold(key("right"), 1) {}
            holds.release(key("left"))
            holds.release(key("right"))
            holds.release(key("right"))
            // A hold with no binding row lights nothing.
            holds.hold(key("left", "btn-2"), null) {}
        }
        assertEquals(
            listOf(SurfaceHolds.HoldChange(1, true), SurfaceHolds.HoldChange(1, true), SurfaceHolds.HoldChange(1, false)),
            emitted,
        )
    }

    @Test
    fun `releaseDevice lets go of every hold on that device alone, past a release that throws`() {
        val holds = SurfaceHolds()
        val released = mutableListOf<String>()
        holds.hold(key("left", "btn-1"), 1) { throw IllegalStateException("show not ready") }
        holds.hold(key("left", "enc-1", ControlPart.PUSH), 2) { released += "left enc-1" }
        holds.hold(key("right", "btn-1"), 1) { released += "right btn-1" }

        holds.releaseDevice("left")

        assertEquals(listOf("left enc-1"), released)
        assertFalse(holds.isHeld(key("left", "btn-1")), "a release that threw still ends its hold")
        assertTrue(holds.isHeld(key("right", "btn-1")))
        assertTrue(holds.isHeldBinding(1))
        assertFalse(holds.isHeldBinding(2))
    }

    @Test
    fun `clearAll forgets every hold and lights every binding off without undoing a press`() {
        val holds = SurfaceHolds()
        var released = 0
        val emitted = changesDuring(holds) {
            holds.hold(key("left"), 1) { released++ }
            holds.hold(key("right", "btn-2"), 2) { released++ }
            holds.clearAll()
        }
        assertEquals(0, released, "the project the presses wrote into has gone")
        assertFalse(holds.isHeld(key("left")))
        assertFalse(holds.isHeldBinding(2))
        assertEquals(setOf(1, 2), emitted.filter { !it.held }.map { it.bindingId }.toSet())
    }
}
