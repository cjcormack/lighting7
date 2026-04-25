package uk.me.cormack.lighting7.testsupport

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.rules.Timeout
import uk.me.cormack.lighting7.moduleWithState
import uk.me.cormack.lighting7.state.State

/** Matches the server's `ContentNegotiation { json() }` install; tolerant on decode. */
val TestJson: Json = Json { ignoreUnknownKeys = true }

/**
 * Mount the test application over an externally-provided [state]. Sets the test
 * [ApplicationConfig][io.ktor.server.config.ApplicationConfig] and invokes
 * [moduleWithState] — skipping the heavy `state.initializeShow()` bootstrap that
 * production `module()` runs. The test's `@Before` owns the show lifecycle.
 */
fun ApplicationTestBuilder.mountTestApp(state: State) {
    environment { config = testAppConfig() }
    application { moduleWithState(state) }
}

/** Shared JSON-only client for pure-HTTP tests. WS-enabled tests build their own. */
fun ApplicationTestBuilder.jsonClient(): HttpClient =
    createClient { install(ContentNegotiation) { json(TestJson) } }

/**
 * Base class for tests that drive real REST routes through [testApplication].
 * Spins up one project-and-show per test against the shared [EmbeddedTestPostgres],
 * resetting the schema each time for hermetic isolation.
 */
abstract class RouteIntegrationTest {

    /**
     * Per-test wall-clock cap so a hung integration test fails loudly instead of
     * dragging `gradlew test` out for the worker idle timeout (~30 min on this host).
     * 60 s is well above the slowest legitimate route round-trip in this suite. See
     * `FU-TEST-COREMIDI-INIT-DEADLOCK`.
     */
    @get:Rule
    val testTimeout: Timeout = Timeout.seconds(60)

    protected lateinit var state: State
    protected var projectId: Int = 0

    @Before
    fun setUpIntegrationTest() {
        Assume.assumeTrue("Embedded Postgres unavailable — skipping", EmbeddedTestPostgres.isAvailable())
        EmbeddedTestPostgres.resetSchema()
        state = State(testAppConfig())
        projectId = seedMinimalProject(state)
        state.initializeShow()
        state.show.start()
    }

    @After
    fun tearDownIntegrationTest() {
        if (::state.isInitialized) {
            runCatching { state.shutdown() }
        }
    }
}
