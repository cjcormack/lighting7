package uk.me.cormack.lighting7.dmx

import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertTrue

class ConcurrentChannelWriteHangTest {

    @Test
    fun `concurrent writers to one channel all return`() {
        val controller = ArtNetController(Universe(0, 0), transport = SilentArtNetTransport)
        try {
            val writers = (1..4).map { w ->
                thread(name = "writer-$w", isDaemon = true) {
                    repeat(20_000) { i ->
                        controller.setValue(1, ChannelChange(((i + w) % 256).toUByte(), if (i % 3 == 0) 500L else 0L))
                    }
                }
            }
            writers.forEach { it.join(20_000) }
            val stuck = writers.filter { it.isAlive }
            stuck.forEach { t -> println("${t.name} stuck:\n" + t.stackTrace.take(12).joinToString("\n") { "  at $it" }) }
            assertTrue(stuck.isEmpty(), "${stuck.size} writer(s) never got their ack")
        } finally {
            controller.close()
        }
    }
}
