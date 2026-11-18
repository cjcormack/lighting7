package uk.me.cormack.lighting7.dmx

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Writers racing on one conflated channel queue must all get their write settled, whichever wins. */
class ConcurrentChannelWriteHangTest {

    @Test
    fun `concurrent writers to one ArtNet channel all return`() {
        val controller = ArtNetController(Universe(0, 0), transport = SilentArtNetTransport)
        try {
            assertAllReturn(hammer(channels = listOf(1)) { channel, value, fadeMs ->
                controller.setValue(channel, ChannelChange(value, fadeMs))
            })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `concurrent writers to one test-controller channel all return`() {
        val controller = AsyncTestDmxController()
        try {
            assertAllReturn(hammer(channels = listOf(1)) { channel, value, _ ->
                controller.setValue(channel, ChannelChange(value, 0L))
            })
        } finally {
            controller.close()
        }
    }

    @Test
    fun `a failed changer strands no writer and stops only its own channel`() {
        val controller = ArtNetController(Universe(0, 0), transport = SilentArtNetTransport)
        try {
            controller.onWriteTaken = { channel -> if (channel == 1) error("changer fault") }
            assertAllReturn(hammer(channels = listOf(1, 2)) { channel, value, fadeMs ->
                controller.setValue(channel, ChannelChange(value, fadeMs))
            })

            controller.setValue(2, ChannelChange(42u, 0L))
            assertEquals(42u, controller.currentValues[2], "channel 2 still takes writes")
        } finally {
            controller.close()
        }
    }

    private fun hammer(channels: List<Int>, write: (channel: Int, value: UByte, fadeMs: Long) -> Unit): List<Thread> =
        (1..4).map { w ->
            thread(name = "writer-$w", isDaemon = true) {
                repeat(WRITES_PER_THREAD) { i ->
                    write(channels[(i + w) % channels.size], ((i + w) % 256).toUByte(), if (i % 5 == 0) 30L else 0L)
                }
            }
        }

    private fun assertAllReturn(writers: List<Thread>) {
        val deadline = System.nanoTime() + DEADLINE_MS * 1_000_000
        for (t in writers) t.join(((deadline - System.nanoTime()) / 1_000_000).coerceAtLeast(1))
        val stuck = writers.filter { it.isAlive }
        assertTrue(stuck.isEmpty(), stuck.joinToString("\n", "${stuck.size} writer(s) never had a write settled:\n") { t ->
            t.name + "\n" + t.stackTrace.take(12).joinToString("\n") { "  at $it" }
        })
    }

    private companion object {
        const val WRITES_PER_THREAD = 5_000
        const val DEADLINE_MS = 10_000L
    }
}
