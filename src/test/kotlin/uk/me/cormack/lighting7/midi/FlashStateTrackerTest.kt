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

class FlashStateTrackerTest {

    @Test
    fun `pressed returns true on first press and false on retrigger`() {
        val t = FlashStateTracker()
        assertTrue(t.pressed(1))
        assertFalse(t.pressed(1))
    }

    @Test
    fun `clearPress returns true when binding was active`() {
        val t = FlashStateTracker()
        t.pressed(1)
        assertTrue(t.clearPress(1))
        assertFalse(t.clearPress(1))
    }

    @Test
    fun `overlapping presses are tracked independently`() {
        val t = FlashStateTracker()
        t.pressed(1)
        t.pressed(2)
        assertEquals(2, t.activeCount)
        t.clearPress(1)
        assertEquals(1, t.activeCount)
        assertFalse(t.isActive(1))
        assertTrue(t.isActive(2))
    }

    @Test
    fun `clearAll drops all presses`() {
        val t = FlashStateTracker()
        t.pressed(1)
        t.pressed(2)
        t.pressed(3)
        t.clearAll()
        assertEquals(0, t.activeCount)
        assertFalse(t.isActive(1))
    }

    @Test
    fun `changes flow emits on press and release edges only`() = runBlocking {
        val t = FlashStateTracker()
        val emitted = mutableListOf<FlashStateTracker.FlashChange>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { t.changes.collect { emitted += it } }
        yield()
        t.pressed(1)
        t.pressed(1)  // retrigger — no flow emission
        t.clearPress(1)
        t.clearPress(1)  // already released — no emission
        yield()
        assertEquals(
            listOf(
                FlashStateTracker.FlashChange(1, true),
                FlashStateTracker.FlashChange(1, false),
            ),
            emitted,
        )
        job.cancel()
        scope.cancel()
    }

    @Test
    fun `clearAll emits release events for every held binding`() = runBlocking {
        val t = FlashStateTracker()
        val emitted = mutableListOf<FlashStateTracker.FlashChange>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val job = scope.launch { t.changes.collect { emitted += it } }
        yield()
        t.pressed(1)
        t.pressed(2)
        emitted.clear()
        t.clearAll()
        yield()
        assertEquals(setOf(1, 2), emitted.map { it.bindingId }.toSet())
        assertTrue(emitted.all { !it.pressed })
        job.cancel()
        scope.cancel()
    }
}
