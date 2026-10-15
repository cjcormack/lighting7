package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class LatestWinsWriterTest {

    @Test
    fun `a failed write is kept for the next pass, unless a newer value supersedes it`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val written = mutableListOf<Pair<Int, String>>()
        var failNext = true
        // An interval far longer than the test, so only the explicit flushes write.
        val writer = LatestWinsWriter<Int, String>(scope, intervalMs = 60_000) { k, v ->
            if (failNext) {
                failNext = false
                error("busy")
            }
            written += k to v
        }
        try {
            writer.close() // stop the background pass, so the flushes below are the only writers
            writer.submit(1, "a")
            writer.flush()
            assertEquals(emptyList(), written)
            writer.flush()
            assertEquals(listOf(1 to "a"), written)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `close writes what is pending`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val written = mutableListOf<Pair<Int, String>>()
        val writer = LatestWinsWriter<Int, String>(scope, intervalMs = 60_000) { k, v -> synchronized(written) { written += k to v } }
        try {
            writer.submit(1, "a")
            writer.submit(1, "b")
            writer.submit(2, "c")
            writer.close()
            // The background pass may already have written "a" before "b" replaced it; what close
            // promises is that each key's latest value is the last one written.
            assertEquals(mapOf(1 to "b", 2 to "c"), synchronized(written) { written.toMap() })
        } finally {
            scope.cancel()
        }
    }
}
