package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.InMemoryCredentialStore
import uk.me.cormack.lighting7.testsupport.FadeTiming.assertLike
import uk.me.cormack.lighting7.testsupport.FadeTiming.fade
import uk.me.cormack.lighting7.testsupport.FakeCloud
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The cloud is never on the show's path (NFR-SAFE-1, cloud-p0 plan session 5): a fade's output is
 * the same with the cloud connected, stopping reading, dropping the connection or refusing every
 * reconnect as with no connector at all. Wall clock, through [uk.me.cormack.lighting7.testsupport.FadeTiming].
 */
class CloudOutageOutputTimingTest {

    @get:Rule
    val timeout: Timeout = Timeout.seconds(120)

    private lateinit var state: State
    private lateinit var fake: FakeCloud
    private var service: CloudService? = null

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        fake = FakeCloud().apply { heartbeatS = 1 }
    }

    @After
    fun tearDown() {
        service?.let { runCatching { it.close() } }
        runCatching { fake.close() }
        runCatching { state.shutdown() }
    }

    private fun await(what: String, predicate: (CloudStateDto) -> Boolean) = runBlocking {
        val s = service!!
        try {
            withTimeout(15.seconds) { s.state.first(predicate) }
        } catch (e: Exception) {
            throw AssertionError("Timed out waiting for $what; state is ${s.state.value}", e)
        }
    }

    @Test
    fun `a fade runs as it does with no connector while the cloud fails`() {
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
        s.start()
        fake.decision = FakeCloud.Decision.APPROVE
        runBlocking { s.startAdoption(adminId) }
        await("online") { it.connection == CloudConnectionStatus.ONLINE }

        assertLike(baseline, fade(), "connected")

        assertLike(baseline, fade { fake.stopReading = true }, "the cloud stopped reading")
        fake.releaseChannel()

        assertLike(baseline, fade { fake.dropChannel() }, "the connection dropped")
        await("online again") { it.connection == CloudConnectionStatus.ONLINE }

        val attempts = fake.channelAttempts.get()
        assertLike(baseline, fade { fake.refuseChannel = true; fake.dropChannel() }, "every reconnect refused")
        await("a refused reconnect") { it.lastError?.code == CloudErrorCodes.CONNECT_FAILED }
        assertTrue(fake.channelAttempts.get() > attempts, "the desk tried to reconnect")
    }
}
