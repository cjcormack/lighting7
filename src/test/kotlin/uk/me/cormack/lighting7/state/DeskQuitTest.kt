package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class DeskQuitTest {
    @Test
    fun `the wait ends with the backup, a skip or the cap`() = runBlocking {
        val done = Job().apply { complete() }
        assertEquals(QuitWaitEnd.ENDED, awaitQuitBackup(done, CompletableDeferred(), 5.seconds))
        assertEquals(QuitWaitEnd.SKIPPED, awaitQuitBackup(Job(), CompletableDeferred(Unit), 5.seconds))
        assertEquals(QuitWaitEnd.CAPPED, awaitQuitBackup(Job(), CompletableDeferred(), 50.milliseconds))
    }

    private fun quit(backup: () -> Job?, exited: CountDownLatch, cap: Duration = 30.seconds) =
        DeskQuit(backup, { exited.countDown() }, backupCap = cap, graceAfterBackup = 1.milliseconds, grace = 1.milliseconds)

    private fun DeskQuit.go(skipBackup: Boolean): Boolean = quit(skipBackup).also { it.proceed() }.backingUp

    @Test
    fun `nothing exits until the quit proceeds`() {
        val exited = CountDownLatch(1)
        val start = quit({ null }, exited).quit(skipBackup = false)
        assertFalse(start.backingUp)
        assertFalse(exited.await(200, TimeUnit.MILLISECONDS))
        start.proceed()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `it waits for the backup, and a second call reports it and starts nothing`() {
        val exited = CountDownLatch(1)
        val job = Job()
        val starts = AtomicInteger()
        val q = quit({ starts.incrementAndGet(); job }, exited)
        assertTrue(q.go(skipBackup = false))
        assertTrue(q.go(skipBackup = false), "still backing up")
        assertEquals(1, starts.get())
        assertFalse(exited.await(200, TimeUnit.MILLISECONDS))
        job.complete()
        assertTrue(exited.await(5, TimeUnit.SECONDS))
        assertFalse(q.go(skipBackup = false))
    }

    @Test
    fun `Quit without waiting ends the wait`() {
        val exited = CountDownLatch(1)
        val q = quit({ Job() }, exited)
        assertTrue(q.go(skipBackup = false))
        assertFalse(q.go(skipBackup = true))
        assertTrue(exited.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `the cap ends the wait`() {
        val exited = CountDownLatch(1)
        val q = quit({ Job() }, exited, cap = 100.milliseconds)
        assertTrue(q.go(skipBackup = false))
        assertTrue(exited.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a backup that fails to start never stops the quit`() {
        val exited = CountDownLatch(1)
        val q = quit({ error("no credential store") }, exited)
        assertFalse(q.go(skipBackup = false))
        assertTrue(exited.await(5, TimeUnit.SECONDS))
    }
}
