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
        holds.hold(key("left")) { released++ }
        assertTrue(holds.isHeld(key("left")))

        holds.release(key("left"))
        holds.release(key("left"))

        assertEquals(1, released)
        assertFalse(holds.isHeld(key("left")))
    }

    @Test
    fun `one binding held on two devices is held per device`() {
        val holds = SurfaceHolds()
        holds.hold(key("left")) {}
        holds.hold(key("right")) {}

        holds.release(key("left"))
        assertTrue(holds.isHeld(key("right")), "the right-hand device still holds its own")
        assertFalse(holds.isHeld(key("left")))

        holds.release(key("right"))
        assertFalse(holds.isHeld(key("right")))
    }

    @Test
    fun `each control part's hold has its own on and off edge`() {
        val holds = SurfaceHolds()
        val emitted = changesDuring(holds) {
            holds.hold(key("left")) {}
            holds.hold(key("right")) {}
            holds.release(key("left"))
            holds.release(key("right"))
            // A second release of a part no longer held says nothing.
            holds.release(key("right"))
        }
        assertEquals(
            listOf(
                SurfaceHolds.HoldChange(key("left"), true),
                SurfaceHolds.HoldChange(key("right"), true),
                SurfaceHolds.HoldChange(key("left"), false),
                SurfaceHolds.HoldChange(key("right"), false),
            ),
            emitted,
        )
    }

    @Test
    fun `releaseDevice lets go of every hold on that device alone, past a release that throws`() {
        val holds = SurfaceHolds()
        val released = mutableListOf<String>()
        holds.hold(key("left", "btn-1")) { throw IllegalStateException("show not ready") }
        holds.hold(key("left", "enc-1", ControlPart.PUSH)) { released += "left enc-1" }
        holds.hold(key("right", "btn-1")) { released += "right btn-1" }

        holds.releaseDevice("left")

        assertEquals(listOf("left enc-1"), released)
        assertFalse(holds.isHeld(key("left", "btn-1")), "a release that threw still ends its hold")
        assertFalse(holds.isHeld(key("left", "enc-1", ControlPart.PUSH)))
        assertTrue(holds.isHeld(key("right", "btn-1")))
    }

    @Test
    fun `a release that throws still announces its hold's end`() {
        val holds = SurfaceHolds()
        val emitted = changesDuring(holds) {
            holds.hold(key("left")) { throw IllegalStateException("show not ready") }
            runCatching { holds.release(key("left")) }
        }
        assertEquals(listOf(SurfaceHolds.HoldChange(key("left"), true), SurfaceHolds.HoldChange(key("left"), false)), emitted)
    }

    @Test
    fun `clearAll forgets every hold and lights every part off without undoing a press`() {
        val holds = SurfaceHolds()
        var released = 0
        val emitted = changesDuring(holds) {
            holds.hold(key("left")) { released++ }
            holds.hold(key("right", "btn-2")) { released++ }
            holds.clearAll()
        }
        assertEquals(0, released, "the project the presses wrote into has gone")
        assertFalse(holds.isHeld(key("left")))
        assertFalse(holds.isHeld(key("right", "btn-2")))
        assertEquals(setOf(key("left"), key("right", "btn-2")), emitted.filter { !it.held }.map { it.key }.toSet())
    }
}
