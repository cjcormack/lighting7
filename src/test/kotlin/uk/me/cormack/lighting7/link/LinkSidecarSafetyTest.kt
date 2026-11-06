package uk.me.cormack.lighting7.link

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.CloudConfig
import uk.me.cormack.lighting7.cloud.CloudConnectionStatus
import uk.me.cormack.lighting7.cloud.CloudService
import uk.me.cormack.lighting7.cloud.CloudStateDto
import uk.me.cormack.lighting7.cloud.protocol.IceCandidate
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FadeTiming.assertLike
import uk.me.cormack.lighting7.testsupport.FadeTiming.fade
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.FakeSidecar
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The sidecar is never on the show's path (NFR-SAFE-2, cloud-p1 plan session 5): a fade's output is
 * the same with the real sidecar running, killed mid-fade or hung on its stdin, and with one flooding
 * its stdout or its stderr, as with no sidecar at all. Wall clock, through
 * [uk.me.cormack.lighting7.testsupport.FadeTiming].
 */
class LinkSidecarSafetyTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(180)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private var service: CloudService? = null
    private val record: File = File.createTempFile("fake-sidecar", ".log").apply { deleteOnExit() }

    /** The host's build of lighting7-link, which the build passes as `-Dlighting7.link`. */
    private val binary: String? = System.getProperty("lighting7.link")

    @Before
    fun setUp() {
        assumeTrue("needs -Dlighting7.link, which ./gradlew test passes", binary != null && File(binary).canExecute())
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud().apply { heartbeatS = 1 }
    }

    @After
    fun tearDown() {
        service?.let { runCatching { it.close() } }
        if (::fake.isInitialized) runCatching { fake.close() }
        if (::state.isInitialized) runCatching { state.shutdown() }
    }

    private fun await(what: String, predicate: (CloudStateDto) -> Boolean) = runBlocking {
        val s = service!!
        try {
            withTimeout(30.seconds) { s.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${s.state.value}", e)
        }
    }

    private fun link(): LinkProcess<LinkSidecar> = assertNotNull(service!!.link)

    /** Wait for a launch other than [previous] to serve, and answer its pid. */
    private fun servingAgain(previous: Long?): Long {
        val deadline = System.nanoTime() + 30_000_000_000L
        while (System.nanoTime() < deadline) {
            val l = service!!.link
            val pid = l?.pid
            if (l?.sidecar?.running == true && pid != null && pid != previous) return pid
            Thread.sleep(20)
        }
        throw AssertionError("No new launch served; state is ${service!!.state.value}")
    }

    /** Run [command]'s sidecar in place of whatever runs, and wait for it to serve. */
    private fun runSidecar(command: (String, String) -> List<String>): Long {
        val s = service!!
        s.setSwitches(remoteAccess = false)
        await("no sidecar") { it.link == LinkStatus.OFF }
        s.linkCommand = command
        s.setSwitches(remoteAccess = true)
        return servingAgain(null)
    }

    @Test
    fun `a fade runs as it does with no sidecar while the sidecar is killed, hangs and floods`() {
        val baseline = fade()

        val adminId = seedUser(state, "admin").userId
        val store = InMemoryCredentialStore()
        val s = CloudService(
            database = state.database,
            config = CloudConfig(fake.baseUrl, true),
            credentialStore = { store },
            apiFactory = { CloudApi(it, Duration.ofSeconds(5)) },
        ).also { service = it }
        s.pollDelay = { 20.milliseconds }
        s.linkCommand = { target, build -> listOf(binary!!, "-target", target, "-build", build) }
        s.publicListenerBound("127.0.0.1", 8414)
        s.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { s.startAdoption(adminId) }
        await("online") { it.connection == CloudConnectionStatus.ONLINE }
        s.setSwitches(remoteAccess = true)
        val first = servingAgain(null)

        assertLike(baseline, fade(), "the sidecar running")

        assertLike(baseline, fade { ProcessHandle.of(first).ifPresent { it.destroyForcibly() } }, "the sidecar killed")
        val second = servingAgain(first)

        // SIGSTOP is POSIX's.
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) {
            assertLike(
                baseline,
                fade {
                    // Stopped, it reads nothing more: its pipe fills, then its queue, and it is taken as hung.
                    ProcessBuilder("kill", "-STOP", second.toString()).start().waitFor(5, TimeUnit.SECONDS)
                    val candidate = IceCandidate("candidate:" + "z".repeat(1000))
                    var refused = false
                    repeat(LinkSidecar.STDIN_LINES * 8) { if (!refused && !link().sidecar.send(LinkIn.Ice("nobody", candidate))) refused = true }
                    assertTrue(refused, "the stdin queue never filled")
                },
                "the sidecar hung on its stdin",
            )
            assertNotEquals(second, servingAgain(second))
            ProcessHandle.of(second).ifPresent { it.onExit().get(10, TimeUnit.SECONDS) }
        }

        runSidecar { _, _ -> FakeSidecar.command("flood-stdout", record) }
        assertLike(baseline, fade(), "a sidecar flooding its stdout")

        runSidecar { _, _ -> FakeSidecar.command("flood-stderr", record) }
        assertLike(baseline, fade(), "a sidecar flooding its stderr")
    }
}
