package uk.me.cormack.lighting7.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assume.assumeFalse
import org.junit.Test
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.testsupport.StandInProcess
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [LinkProcess]'s supervision (cloud-p1 plan D11): its backoff under virtual time, and the orphan sweep. */
// advanceTimeBy and runCurrent are still experimental.
@OptIn(ExperimentalCoroutinesApi::class)
class LinkProcessTest {

    /** Launches that the test ends and starts by hand. */
    private class FakeLaunches(private val scope: TestScope, val supervisor: LinkProcess.Supervisor) : SidecarLaunches {
        val startedAt = mutableListOf<Long>()
        val endedAt = mutableListOf<Long>()
        var failNext = 0
        var stopped = false
        override var running = false

        override fun start(): Boolean {
            startedAt += scope.testScheduler.currentTime
            if (failNext > 0) {
                failNext--
                return false
            }
            return true
        }

        override fun stop() {
            stopped = true
        }

        fun serve() {
            running = true
            supervisor.serving(true)
        }

        fun exit() {
            endedAt += scope.testScheduler.currentTime
            running = false
            supervisor.serving(false)
            supervisor.ended()
        }
    }

    private val launched = CopyOnWriteArrayList<Process>()

    @After
    fun tearDown() {
        launched.forEach { it.destroyForcibly() }
    }

    private fun TestScope.process(): Pair<LinkProcess<FakeLaunches>, MutableList<LinkStatus>> {
        val statuses = mutableListOf<LinkStatus>()
        lateinit var p: LinkProcess<FakeLaunches>
        p = LinkProcess(scope = backgroundScope, onStatus = { statuses += p.status }, now = { testScheduler.currentTime }) {
            FakeLaunches(this, it)
        }
        return p to statuses
    }

    @Test
    fun `a sidecar that keeps exiting is restarted after 1 s, doubling to 30 s`() = runTest {
        val (p, statuses) = process()
        p.start()
        repeat(8) {
            runCurrent()
            p.sidecar.serve()
            p.sidecar.exit()
            advanceTimeBy(LinkProcess.restartBackoffMs(it) + 1)
        }
        runCurrent()
        val waits = p.sidecar.endedAt.zip(p.sidecar.startedAt.drop(1)) { ended, next -> next - ended }
        assertEquals(listOf(1_000L, 2_000, 4_000, 8_000, 16_000, 30_000, 30_000, 30_000), waits)
        assertEquals(
            listOf(LinkStatus.STARTING, LinkStatus.RUNNING, LinkStatus.RESTARTING, LinkStatus.STARTING),
            statuses.take(4),
        )
        p.stop()
    }

    @Test
    fun `nothing starts before the backoff is over`() = runTest {
        val (p, _) = process()
        p.start()
        p.sidecar.serve()
        p.sidecar.exit()
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, p.sidecar.startedAt.size)
        assertEquals(LinkStatus.RESTARTING, p.status)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(2, p.sidecar.startedAt.size)
        assertEquals(LinkStatus.STARTING, p.status)
        p.stop()
    }

    @Test
    fun `a launch that served for a minute resets the backoff, and one that served less does not`() = runTest {
        val (p, _) = process()
        p.start()
        repeat(3) {
            p.sidecar.serve()
            p.sidecar.exit()
            advanceTimeBy(LinkProcess.restartBackoffMs(it) + 1)
        }
        // The fourth launch serves 59 s: the next wait is still 8 s.
        p.sidecar.serve()
        advanceTimeBy(59_000)
        p.sidecar.exit()
        advanceTimeBy(8_001)
        runCurrent()
        assertEquals(8_000L, p.sidecar.startedAt.last() - p.sidecar.endedAt.last())

        // The fifth serves 60 s: the next wait is 1 s again.
        p.sidecar.serve()
        advanceTimeBy(60_000)
        p.sidecar.exit()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(1_000L, p.sidecar.startedAt.last() - p.sidecar.endedAt.last())
        p.stop()
    }

    @Test
    fun `a process that cannot be started is tried again with the backoff`() = runTest {
        val (p, _) = process()
        p.sidecar.failNext = 2
        p.start()
        advanceTimeBy(1_001)
        runCurrent()
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(listOf(0L, 1_000L, 3_000L), p.sidecar.startedAt)
        assertEquals(LinkStatus.STARTING, p.status)
        p.sidecar.serve()
        assertEquals(LinkStatus.RUNNING, p.status)
        p.stop()
    }

    @Test
    fun `a stop during the backoff starts nothing more`() = runTest {
        val (p, statuses) = process()
        p.start()
        p.sidecar.serve()
        p.sidecar.exit()
        p.stop()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, p.sidecar.startedAt.size)
        assertTrue(p.sidecar.stopped)
        assertEquals(LinkStatus.OFF, p.status)
        assertEquals(LinkStatus.OFF, statuses.last())
        p.sidecar.exit()
        assertEquals(LinkStatus.OFF, p.status, "an end after the stop changes nothing")
    }

    private fun sidecarBinary(dir: Path): Path {
        val binary = StandInProcess.install(dir.resolve("lighting7-link"))
        assumeFalse(binary == null)
        return binary!!
    }

    @Test
    fun `a launch records its PID, and its exit deletes the file only while it names that launch`() = runTest {
        val dir = Files.createTempDirectory("link-process")
        val binary = sidecarBinary(dir)
        val pidFile = PidFile(dir.resolve("link/lighting7-link.pid"), "a test sidecar")
        fun supervised() = LinkProcess(scope = backgroundScope, onStatus = {}, pidFile = pidFile) { supervisor ->
            object : SidecarLaunches {
                override val running = false
                override fun start(): Boolean {
                    launched += supervisor.launch(StandInProcess.command(binary))
                    return true
                }
                override fun stop() {}
            }
        }
        val older = supervised().also { it.start() }
        val first = launched.last()
        assertEquals(first.pid().toString(), pidFile.path.readLines().first())

        // Remote sessions off and on: a second supervisor's launch, while the first's is still exiting.
        val newer = supervised().also { it.start() }
        val second = launched.last()
        assertEquals(second.pid().toString(), pidFile.path.readLines().first())
        first.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        Thread.sleep(200)
        assertEquals(second.pid().toString(), pidFile.path.readLines().first(), "the older launch's exit left the newer's file")

        second.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        val deleted = (1..50).any { Thread.sleep(20); !Files.exists(pidFile.path) }
        assertTrue(deleted, "the PID file goes with its process")
        older.stop()
        newer.stop()
    }

    @Test
    fun `a sweep stops a sidecar left running, and records nothing more`() {
        val dir = Files.createTempDirectory("link-process")
        val binary = sidecarBinary(dir)
        val orphan = ProcessBuilder(StandInProcess.command(binary)).start().also { launched += it }
        val pidFile = PidFile(dir.resolve("lighting7-link.pid"), "a test sidecar")
        pidFile.write(orphan, binary)
        pidFile.sweepOrphan(binary)
        assertTrue(orphan.waitFor(5, TimeUnit.SECONDS), "the orphan was stopped")
        assertFalse(Files.exists(pidFile.path))
    }

    @Test
    fun `a PID that is no longer the sidecar's is left alone`() {
        val dir = Files.createTempDirectory("link-process")
        val binary = sidecarBinary(dir)
        val sleep = listOf("/bin/sleep", "/usr/bin/sleep").first { Files.isExecutable(Path.of(it)) }
        val stranger = ProcessBuilder(sleep, "60").start().also { launched += it }
        val pidFile = PidFile(dir.resolve("lighting7-link.pid"), "a test sidecar")

        pidFile.path.writeText("${stranger.pid()}\n$binary\n")
        pidFile.sweepOrphan(binary)
        assertTrue(stranger.isAlive, "another binary's process")

        // The sidecar's binary, but started at another time: the PID was reused.
        val reused = ProcessBuilder(StandInProcess.command(binary)).start().also { launched += it }
        pidFile.path.writeText("${reused.pid()}\n$binary\n2001-01-01T00:00:00Z\n")
        pidFile.sweepOrphan(binary)
        assertTrue(reused.isAlive, "a process the file did not record")
        assertFalse(Files.exists(pidFile.path))
    }
}
