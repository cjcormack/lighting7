package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** The channel's reconnect loop on a virtual clock (cloud-p0 plan D9). */
class CloudChannelBackoffTest {

    private class Run(
        attempts: Int,
        online: Set<Int> = emptySet(),
        seed: Int = 7,
        onlineFor: Duration = 60.seconds,
        throwing: Set<Int> = emptySet(),
    ) {
        var now: Instant = Instant.EPOCH
        val waits = mutableListOf<Duration>()
        val statuses = mutableListOf<ChannelStatus>()
        private var calls = 0

        val channel = CloudChannel(
            clock = { now },
            publish = { statuses += it },
            onRevoked = {},
            connectOnce = { onOnline ->
                val call = calls++
                if (call in throwing) throw IllegalStateException("boom")
                if (call in online) {
                    onOnline()
                    now = now.plus(onlineFor.toJavaDuration())
                }
                if (call >= attempts) ChannelEnd.Revoked else ChannelEnd.Closed(CloudErrorDto("X", "x"))
            },
            random = Random(seed),
            pause = { waits += it; now = now.plus(it.toJavaDuration()) },
        )
    }

    @Test
    fun `waits stay between 1 s and 30 s over many attempts`() {
        for (seed in 0 until 20) {
            val run = Run(attempts = 200, seed = seed)
            runBlocking { run.channel.run() }
            assertEquals(200, run.waits.size)
            assertTrue(run.waits.all { it in 1.seconds..30.seconds }, "seed $seed: ${run.waits}")
            assertEquals(1.seconds, run.waits.first(), "the first retry has nowhere to jitter")
            assertTrue(run.waits.drop(10).any { it > 20.seconds }, "the cap is reached: ${run.waits}")
        }
    }

    @Test
    fun `the ceiling doubles from 1 s`() {
        val random = Random(1)
        for (failures in 0..10) {
            val ceiling = minOf(1_000L shl failures.coerceAtMost(5), 30_000L).milliseconds
            repeat(200) {
                val wait = Backoff.delayFor(failures, random)
                assertTrue(wait in 1.seconds..ceiling, "failure $failures waited $wait, ceiling $ceiling")
            }
        }
    }

    @Test
    fun `a connection that went online starts the backoff again`() {
        val run = Run(attempts = 12, online = setOf(8))
        runBlocking { run.channel.run() }
        assertEquals(1.seconds, run.waits[8], "the retry after an online connection is the first one again")
        assertTrue(run.waits[7] > 1.seconds || run.waits[6] > 1.seconds)
    }

    @Test
    fun `a connection closed straight after its welcome keeps backing off`() {
        val run = Run(attempts = 12, online = (0 until 12).toSet(), onlineFor = 1.seconds)
        runBlocking { run.channel.run() }
        assertTrue(run.waits.drop(6).maxOf { it } > 4.seconds, "the backoff grew: ${run.waits}")
    }

    @Test
    fun `an attempt that throws is retried, not the end of the channel`() {
        val run = Run(attempts = 4, throwing = setOf(0, 2))
        runBlocking { run.channel.run() }
        assertEquals(4, run.waits.size)
        assertEquals(CloudErrorCodes.DISCONNECTED, run.statuses.first { it.connection == CloudConnectionStatus.RETRYING }.lastError?.code)
    }

    @Test
    fun `each wait is published as RETRYING with its retry time`() {
        val run = Run(attempts = 3)
        runBlocking { run.channel.run() }
        val retrying = run.statuses.filter { it.connection == CloudConnectionStatus.RETRYING }
        assertEquals(3, retrying.size)
        var at = Instant.EPOCH
        retrying.zip(run.waits).forEach { (status, wait) ->
            assertEquals(at.plus(wait.toJavaDuration()), status.retryAt)
            assertEquals("X", status.lastError?.code)
            at = at.plus(wait.toJavaDuration())
        }
        assertEquals(CloudConnectionStatus.CONNECTING, run.statuses.last().connection)
    }
}
