package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A blocking controller write made under a lock from every `Dispatchers.Default` worker at once — what
 * the crossfade ticks do through `CascadePublisher`'s publish lock — must still finish: the changers it
 * waits on cannot need a `Default` worker.
 */
class DefaultDispatcherStarvationTest {

    @Test
    fun `blocking writes under a lock from every Default worker all finish`() {
        val controller = ArtNetController(Universe(0, 0), transport = SilentArtNetTransport)
        val lock = Any()
        val workers = Runtime.getRuntime().availableProcessors() * 2
        val writes = 200
        val done = CountDownLatch(workers)
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            repeat(workers) { w ->
                scope.launch {
                    repeat(writes) { i ->
                        synchronized(lock) { controller.setValue(1 + (w % 8), ChannelChange(((i + w) % 256).toUByte(), 0L)) }
                    }
                    done.countDown()
                }
            }
            assertTrue(done.await(10, TimeUnit.SECONDS), "${done.count} of $workers workers never finished")
        } finally {
            scope.cancel()
            controller.close()
        }
    }
}
