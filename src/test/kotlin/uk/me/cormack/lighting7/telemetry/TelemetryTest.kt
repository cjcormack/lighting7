package uk.me.cormack.lighting7.telemetry

import ch.qos.logback.classic.LoggerContext
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.config.mergeWith
import io.ktor.server.testing.testApplication
import io.sentry.Sentry
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.InstallChangedOutMessage
import uk.me.cormack.lighting7.routes.TelemetryDto
import uk.me.cormack.lighting7.routes.UpdateTelemetryRequest
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.update.BuildInfo
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The install telemetry switch (`/install/telemetry`) and the promise that the suite never meets Sentry. */
class TelemetryTest : RouteIntegrationTest() {

    private val path = "/api/rest/install/telemetry"

    private fun build(channel: BuildInfo.Channel) = BuildInfo("1.4.0", channel, "0123456789abcdef", null)

    private fun settings(channel: BuildInfo.Channel, config: SentryConfig = SentryConfig("https://k@example/1", true, null, null)) =
        TelemetrySettings(state.database, config, build(channel))

    @Test
    fun `moduleWithState leaves Sentry disabled`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        assertEquals(HttpStatusCode.OK, http.get(path).status)
        assertEquals(HttpStatusCode.OK, http.get("/api/rest/projects").status)
        assertFalse(Sentry.isEnabled())
        val root = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        assertNull(root.getAppender(SentryRuntime.APPENDER_NAME))
    }

    @Test
    fun `an install that has never chosen reports from a dev build and not from a release one`() {
        assertTrue(settings(BuildInfo.Channel.DEV).enabled)
        assertFalse(settings(BuildInfo.Channel.RELEASE).enabled)
    }

    @Test
    fun `an admin's choice is stored and outlives the process`() {
        settings(BuildInfo.Channel.RELEASE).setChoice(true)
        assertTrue(settings(BuildInfo.Channel.RELEASE).enabled)
        settings(BuildInfo.Channel.DEV).setChoice(false)
        assertFalse(settings(BuildInfo.Channel.DEV).enabled)
    }

    @Test
    fun `sentry enabled = false in local conf wins over the choice`() {
        val off = settings(BuildInfo.Channel.DEV, SentryConfig("https://k@example/1", enabled = false, null, null))
        off.setChoice(true)
        assertTrue(off.choice)
        assertFalse(off.enabled)

        // local.conf is merged over application.conf, so its `enabled = false` is the one read.
        val merged = MapApplicationConfig("sentry.enabled" to "true", "sentry.dsn" to "https://k@example/1")
            .mergeWith(MapApplicationConfig("sentry.enabled" to "false"))
        assertFalse(SentryConfig.from(merged).enabled)
    }

    @Test
    fun `release, environment and trace rate derive from the build`() {
        val dev = settings(BuildInfo.Channel.DEV)
        assertEquals("lighting7@1.4.0+0123456", dev.release)
        assertEquals("development", dev.environment)
        assertEquals(1.0, dev.tracesSampleRate)

        val release = settings(BuildInfo.Channel.RELEASE)
        assertEquals("release", release.environment)
        assertEquals(0.2, release.tracesSampleRate)

        val cloud = settings(BuildInfo.Channel.DEV, SentryConfig("https://k@example/1", true, 0.5, "cloud"))
        assertEquals("cloud", cloud.environment)
        assertEquals(0.5, cloud.tracesSampleRate)

        assertEquals("lighting7@0.0.0", TelemetrySettings.releaseName(BuildInfo("0.0.0", BuildInfo.Channel.DEV, null, null)))
        dev.adoptDevCommit("fedcba9")
        assertEquals("lighting7@1.4.0+0123456", dev.release, "a build that carries its commit keeps it")
        val local = TelemetrySettings(state.database, SentryConfig(null, true, null, null), BuildInfo("1.0.0", BuildInfo.Channel.DEV, null, null))
        local.adoptDevCommit("fedcba9")
        assertEquals("lighting7@1.0.0+fedcba9", local.release)
        assertNull(SentryConfig.from(testAppConfig("sentry.tracesSampleRate" to "lots")).tracesSampleRate)

        assertEquals(1.0, dev.idleSyncSampleRate, "empty follows tracesSampleRate")
        assertEquals(0.2, release.idleSyncSampleRate)
        val idle = SentryConfig.from(testAppConfig("sentry.idleSyncSampleRate" to "0.01"))
        assertEquals(0.01, idle.idleSyncSampleRate)
        assertEquals(0.01, settings(BuildInfo.Channel.DEV, idle).idleSyncSampleRate)
        assertNull(SentryConfig.from(testAppConfig("sentry.idleSyncSampleRate" to "2")).idleSyncSampleRate)
    }

    @Test
    fun `replay records every errored page load, and whole sessions only off a release`() {
        val dev = settings(BuildInfo.Channel.DEV)
        assertEquals(0.1, dev.replaysSessionSampleRate)
        assertEquals(1.0, dev.replaysOnErrorSampleRate)

        val release = settings(BuildInfo.Channel.RELEASE)
        assertEquals(0.0, release.replaysSessionSampleRate)
        assertEquals(1.0, release.replaysOnErrorSampleRate)

        val set = SentryConfig.from(
            testAppConfig("sentry.replaysSessionSampleRate" to "0.5", "sentry.replaysOnErrorSampleRate" to "0.25"),
        )
        val overridden = settings(BuildInfo.Channel.RELEASE, set)
        assertEquals(0.5, overridden.replaysSessionSampleRate)
        assertEquals(0.25, overridden.replaysOnErrorSampleRate)
        assertNull(SentryConfig.from(testAppConfig("sentry.replaysOnErrorSampleRate" to "2")).replaysOnErrorSampleRate)
    }

    @Test
    fun `GET answers a signed-out caller, and PUT is an admin's`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", UserRole.ADMIN)
        seedUser(state, "op", UserRole.OPERATOR)
        val http = jsonClient()

        val open = http.get(path)
        assertEquals(HttpStatusCode.OK, open.status)
        val dto = open.body<TelemetryDto>()
        assertEquals(state.telemetry.enabled, dto.enabled)
        assertEquals(state.telemetry.release, dto.release)
        assertEquals(state.telemetry.environment, dto.environment)
        assertEquals(state.telemetry.replaysSessionSampleRate, dto.replaysSessionSampleRate)
        assertEquals(state.telemetry.replaysOnErrorSampleRate, dto.replaysOnErrorSampleRate)

        val signedOut = http.put(path) {
            contentType(ContentType.Application.Json)
            setBody(UpdateTelemetryRequest(false))
        }
        assertEquals(HttpStatusCode.Unauthorized, signedOut.status)

        val operator = http.put(path) {
            header(HttpHeaders.Cookie, http.loginCookieHeader("op"))
            contentType(ContentType.Application.Json)
            setBody(UpdateTelemetryRequest(false))
        }
        assertEquals(HttpStatusCode.Forbidden, operator.status)
        assertEquals(state.telemetry.defaultChoice, state.telemetry.choice)

        var heard: Boolean? = null
        state.telemetry.onChange = { heard = it }
        coroutineScope {
            val installChanged = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5_000) { state.machineEventsFlow.first { it is InstallChangedOutMessage } } }
            val admin = http.put(path) {
                header(HttpHeaders.Cookie, http.loginCookieHeader("admin"))
                contentType(ContentType.Application.Json)
                setBody(UpdateTelemetryRequest(!state.telemetry.defaultChoice))
            }
            assertEquals(HttpStatusCode.OK, admin.status)
            assertEquals(!state.telemetry.defaultChoice, admin.body<TelemetryDto>().enabled)
            assertEquals(!state.telemetry.defaultChoice, heard)
            installChanged.await()
        }
    }

    @Test
    fun `GET answers a signed-out caller on the public listener too`() = testApplication {
        environment { config = testAppConfig() }
        application { publicModule(state) }
        seedUser(state, "admin", UserRole.ADMIN)
        val http = jsonClient()
        assertEquals(HttpStatusCode.OK, http.get(path).status)
        assertEquals(HttpStatusCode.Unauthorized, http.put(path) {
            contentType(ContentType.Application.Json)
            setBody(UpdateTelemetryRequest(false))
        }.status)
    }
}
