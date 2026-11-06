package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rate cap on a public-listener socket (cloud-p1 plan D14), under virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteFrameCapTest {

    private class Sent(val atMs: Long, val message: OutMessage)

    private fun TestScope.cap(hz: Int = 20, sent: MutableList<Sent>) =
        RemoteFrameCap(backgroundScope, hz) { sent += Sent(testScheduler.currentTime, it) }

    private fun channels(vararg levels: Triple<Int, Int, Int>, snapshot: Boolean = false) =
        ChannelStateOutMessage(levels.map { (u, c, v) -> ChannelState(u, c, v.toUByte()) }, snapshot)

    private fun provenance(revision: Long) = ProvenanceStateOutMessage(emptyList(), revision)

    private val OutMessage.levels: Map<Pair<Int, Int>, Int>
        get() = (this as ChannelStateOutMessage).channels.associate { (it.universe to it.id) to it.currentLevel.toInt() }

    @Test
    fun `channelState is sent at the rate, every channel that changed merged at its latest level`() = runTest {
        val sent = mutableListOf<Sent>()
        val cap = cap(sent = sent)

        cap.channelState(channels(Triple(0, 1, 10)))
        runCurrent()
        assertEquals(1, sent.size, "the first frame after a quiet spell goes at once")
        assertEquals(0, sent[0].atMs)

        // A fade's deltas at 10 ms: three channels, one moving every frame.
        for (i in 1..9) {
            advanceTimeBy(5)
            cap.channelState(channels(Triple(0, 1, 10 + i), Triple(0, 2 + i % 2, i)))
            advanceTimeBy(5)
        }
        advanceTimeBy(11)
        assertEquals(3, sent.size)
        assertEquals(listOf(0L, 50L, 100L), sent.map { it.atMs }, "no closer than the interval, 1000 / 20 ms")
        val second = sent[1].message.levels
        assertEquals(mapOf((0 to 1) to 15, (0 to 2) to 4, (0 to 3) to 5), second, "the latest of each channel moved in the interval")
        assertEquals(mapOf((0 to 1) to 19, (0 to 2) to 8, (0 to 3) to 9), sent[2].message.levels)
        assertTrue(sent.all { !(it.message as ChannelStateOutMessage).snapshot })

        advanceTimeBy(1_000)
        assertEquals(3, sent.size, "nothing changed, nothing sent")
    }

    @Test
    fun `a snapshot merged with deltas stays a snapshot`() = runTest {
        val sent = mutableListOf<Sent>()
        val cap = cap(sent = sent)
        cap.channelState(channels(Triple(0, 1, 1)))
        runCurrent()

        cap.channelState(channels(Triple(0, 1, 2)))
        cap.channelState(channels(Triple(0, 1, 3), Triple(0, 2, 30), Triple(1, 1, 40), snapshot = true))
        cap.channelState(channels(Triple(0, 2, 31)))
        advanceTimeBy(51)

        assertEquals(2, sent.size)
        val merged = sent[1].message as ChannelStateOutMessage
        assertTrue(merged.snapshot, "a merged frame keeps snapshot if any frame in it had it")
        assertEquals(mapOf((0 to 1) to 3, (0 to 2) to 31, (1 to 1) to 40), sent[1].message.levels)

        cap.channelState(channels(Triple(0, 1, 4)))
        advanceTimeBy(51)
        assertFalse((sent[2].message as ChannelStateOutMessage).snapshot, "the next frame is a delta again")
    }

    @Test
    fun `provenanceState is sent as its latest frame at half the rate`() = runTest {
        val sent = mutableListOf<Sent>()
        val cap = cap(sent = sent)
        cap.provenance(provenance(1))
        runCurrent()
        for (revision in 2L..8L) {
            advanceTimeBy(20)
            cap.provenance(provenance(revision))
        }
        advanceTimeBy(200)

        assertEquals(listOf(0L, 100L, 200L), sent.map { it.atMs }, "1000 / 10 ms apart")
        assertEquals(listOf(1L, 6L, 8L), sent.map { (it.message as ProvenanceStateOutMessage).programmerRevision }, "only the latest of each interval")
    }

    @Test
    fun `channel and provenance frames have intervals of their own`() = runTest {
        val sent = mutableListOf<Sent>()
        val cap = cap(sent = sent)
        cap.provenance(provenance(1))
        cap.channelState(channels(Triple(0, 1, 1)))
        runCurrent()
        assertEquals(2, sent.size, "one of each at once")
    }

    @Test
    fun `a socket on the desk's own listener is never capped, nor one with the cap off`() = runTest {
        assertNull(RemoteFrameCap.forSocket(remote = false, hz = 20, scope = backgroundScope) {})
        assertNull(RemoteFrameCap.forSocket(remote = true, hz = 0, scope = backgroundScope) {})
        assertTrue(RemoteFrameCap.forSocket(remote = true, hz = 20, scope = backgroundScope) {} != null)
    }
}
