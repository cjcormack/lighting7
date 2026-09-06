package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControlStateTrackerTest {

    private fun collecting(
        tracker: ControlStateTracker,
        scope: CoroutineScope,
    ): Pair<MutableList<ControlStateTracker.Delta>, MutableList<ControlStateTracker.Snapshot>> {
        val deltas = mutableListOf<ControlStateTracker.Delta>()
        val snapshots = mutableListOf<ControlStateTracker.Snapshot>()
        scope.launch { tracker.deltas.collect { deltas += it } }
        scope.launch { tracker.snapshots.collect { snapshots += it } }
        return deltas to snapshots
    }

    @Test
    fun `a burst of writes to one control is one delta carrying the last value`() = runBlocking {
        val tracker = ControlStateTracker()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val (deltas, _) = collecting(tracker, scope)
        try {
            tracker.reset("dev", listOf("fader-1", "fader-2"))
            for (v in 0..20) tracker.setValue("dev", "fader-1", v, RingState.NONE)
            tracker.setTouched("dev", "fader-1", true)
            tracker.flushForTest()
            yield()
            assertEquals(1, deltas.size)
            val row = deltas.single().controls
            assertEquals(setOf("fader-1"), row.keys)
            assertEquals(ControlState(value = 20, touched = true), row.getValue("fader-1"))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the snapshot equals the last delta of every key and a write that changes nothing is not a delta`() = runBlocking {
        val tracker = ControlStateTracker()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val (deltas, _) = collecting(tracker, scope)
        try {
            tracker.reset("dev", listOf("fader-1", "btn-1", "enc-1"))
            tracker.setValue("dev", "fader-1", 100, RingState.NONE)
            tracker.setLed("dev", "btn-1", LedState.ON)
            tracker.setValue("dev", "enc-1", null, RingState.OFF)
            tracker.flushForTest()
            tracker.setLed("dev", "btn-1", LedState.ON)
            tracker.flushForTest()
            yield()
            assertEquals(1, deltas.size, "the repeated LED write changed nothing")
            val merged = deltas.fold(emptyMap<String, ControlState>()) { acc, d -> acc + d.controls }
            val snapshot = assertNotNull(tracker.snapshot("dev"))
            for ((id, state) in merged) assertEquals(state, snapshot.controls[id])
            assertEquals(ControlState(value = null, ring = RingState.OFF), snapshot.controls["enc-1"])
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `reset seeds every control unbound but keeps touch and physical position`() {
        val tracker = ControlStateTracker()
        tracker.reset("dev", listOf("fader-1", "btn-1"))
        tracker.setValue("dev", "fader-1", 90, RingState.NONE)
        tracker.setPhysical("dev", "fader-1", 40)
        tracker.setTouched("dev", "fader-1", true)
        tracker.setLed("dev", "btn-1", LedState.ON)
        tracker.reset("dev", listOf("fader-1", "btn-1", "enc-1"))
        val controls = assertNotNull(tracker.snapshot("dev")).controls
        assertEquals(ControlState(physical = 40, touched = true), controls["fader-1"])
        assertEquals(ControlState.UNBOUND, controls["btn-1"])
        assertEquals(ControlState.UNBOUND, controls["enc-1"])
    }

    @Test
    fun `publishSnapshot emits the device and subsumes its pending delta`() = runBlocking {
        val tracker = ControlStateTracker()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val (deltas, snapshots) = collecting(tracker, scope)
        try {
            tracker.reset("dev", listOf("fader-1"))
            tracker.setValue("dev", "fader-1", 5, RingState.NONE)
            tracker.publishSnapshot("dev")
            tracker.flushForTest()
            yield()
            assertEquals(1, snapshots.size)
            assertEquals(5, snapshots.single().controls.getValue("fader-1").value)
            assertTrue(deltas.isEmpty(), "the snapshot carried the change; no delta follows")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `two devices flush independently and a removed device answers an empty snapshot`() = runBlocking {
        val tracker = ControlStateTracker()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val (deltas, snapshots) = collecting(tracker, scope)
        try {
            tracker.reset("a", listOf("fader-1"))
            tracker.reset("b", listOf("fader-1"))
            tracker.setValue("a", "fader-1", 1, RingState.NONE)
            tracker.setValue("b", "fader-1", 2, RingState.NONE)
            tracker.flushForTest()
            yield()
            assertEquals(setOf("a", "b"), deltas.map { it.displayKey }.toSet())
            tracker.remove("b")
            yield()
            assertEquals(listOf("b"), snapshots.map { it.displayKey })
            assertTrue(snapshots.single().controls.isEmpty())
            assertNull(tracker.snapshot("b"))
            assertEquals(1, tracker.snapshots().size)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the flusher drains on its own after the interval`() = runBlocking {
        val tracker = ControlStateTracker(flushIntervalMs = 10L)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val deltas = java.util.concurrent.CopyOnWriteArrayList<ControlStateTracker.Delta>()
        scope.launch { tracker.deltas.collect { deltas += it } }
        yield()
        try {
            tracker.start(scope)
            tracker.reset("dev", listOf("fader-1"))
            tracker.setValue("dev", "fader-1", 7, RingState.NONE)
            var waited = 0
            while (deltas.isEmpty() && waited < 200) { delay(10); waited += 10 }
            assertEquals(1, deltas.size)
            assertEquals(7, deltas.single().controls.getValue("fader-1").value)
        } finally {
            tracker.stop()
            scope.cancel()
        }
    }
}
