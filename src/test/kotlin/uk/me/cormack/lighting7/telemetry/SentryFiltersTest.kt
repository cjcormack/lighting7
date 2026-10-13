package uk.me.cormack.lighting7.telemetry

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.BadRequestException
import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.SentryLogLevel
import io.sentry.protocol.Message
import io.sentry.protocol.SentryException
import io.sentry.protocol.SentryId
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.serialization.SerializationException
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthenticationException
import uk.me.cormack.lighting7.auth.AuthorizationException
import uk.me.cormack.lighting7.mcp.RemoteScriptsDisabledException
import uk.me.cormack.lighting7.venue.VenueException
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SentryFiltersTest {

    private fun eventFor(thrown: Throwable) = SentryEvent(thrown)

    private fun requestEventFor(thrown: Throwable) = SentryEvent(thrown).apply { setTag(LISTENER_TAG, "local") }

    private val clientErrors = listOf(
        AuthenticationException("Not signed in"),
        AuthorizationException("Administrator access required"),
        RemoteScriptsDisabledException(),
        BadRequestException("Malformed request"),
        SerializationException("Unexpected JSON token"),
        VenueException.conflict("VENUE_OWNED", "That record is the venue's"),
    )

    @Test
    fun `disconnects and cancellations are dropped`() {
        val dropped = listOf(
            CancellationException("call cancelled"),
            kotlinx.coroutines.CancellationException("job cancelled"),
            ClosedChannelException(),
            ClosedSendChannelException("socket closed"),
            IOException("Connection reset by peer"),
            IOException("Broken pipe"),
            IOException("An existing connection was forcibly closed by the remote host"),
            RuntimeException("write failed", IOException("recvAddress(..) failed: Connection reset by peer")),
        )
        for (thrown in dropped) assertNull(SentryFilters.beforeSend(eventFor(thrown)), "expected a drop: $thrown")
    }

    @Test
    fun `a client error raised by a request is dropped, and the same exception from background work is kept`() {
        for (thrown in clientErrors) {
            assertNull(SentryFilters.beforeSend(requestEventFor(thrown)), "expected a drop: $thrown")
            assertNotNull(SentryFilters.beforeSend(eventFor(thrown)), "expected a keep: $thrown")
        }
    }

    @Test
    fun `desk faults are kept`() {
        val kept = listOf(
            IllegalStateException("Show not initialized"),
            IOException("No space left on device"),
            NullPointerException(),
            VenueException(HttpStatusCode.InternalServerError, "VENUE_BROKEN", "Snapshot unreadable"),
        )
        for (thrown in kept) assertNotNull(SentryFilters.beforeSend(requestEventFor(thrown)), "expected a keep: $thrown")
        assertNotNull(SentryFilters.beforeSend(SentryEvent()), "an error logged without a throwable is kept")
    }

    @Test
    fun `a user-script error is a warning tagged by origin`() {
        val event = SentryEvent(RuntimeException("Unresolved reference: fixture"))
        event.level = SentryLevel.ERROR
        event.setExtra("marker", "USER_SCRIPT")
        val sent = assertNotNull(SentryFilters.beforeSend(event))
        assertEquals(SentryLevel.WARNING, sent.level)
        assertEquals("user-script", sent.getTag("origin"))
    }

    @Test
    fun `a desk error keeps its level and has no origin tag`() {
        val event = SentryEvent(IllegalStateException("boom")).apply { level = SentryLevel.ERROR }
        val sent = assertNotNull(SentryFilters.beforeSend(event))
        assertEquals(SentryLevel.ERROR, sent.level)
        assertNull(sent.getTag("origin"))
    }

    @Test
    fun `an event is scrubbed of tokens`() {
        val event = SentryEvent(IllegalStateException("x")).apply {
            message = Message().apply {
                formatted = "Unhandled exception on /api/rest/auth/reset/AbC123"
                message = "Unhandled exception on {}"
                params = listOf("/api/rest/auth/reset/AbC123")
            }
            exceptions = listOf(SentryException().apply { value = "GitHub said 401 for ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8" })
            breadcrumbs = listOf(Breadcrumb().apply { message = "Authorization: Bearer abc.def" })
        }
        val sent = assertNotNull(SentryFilters.beforeSend(event))
        assertEquals("Unhandled exception on /api/rest/auth/reset/[redacted]", sent.message?.formatted)
        assertEquals(listOf("/api/rest/auth/reset/[redacted]"), sent.message?.params)
        assertEquals("GitHub said 401 for [redacted]", sent.exceptions?.single()?.value)
        assertEquals("Authorization: Bearer [redacted]", sent.breadcrumbs?.single()?.message)
    }

    @Test
    fun `a log is scrubbed in its body and its string attributes`() {
        val log = SentryLogEvent(SentryId(), 0.0, "pushing with ghp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8", SentryLogLevel.INFO)
        log.setAttribute("sentry.message.parameter.0", SentryLogEventAttributeValue("string", "Bearer abc123"))
        log.setAttribute("count", SentryLogEventAttributeValue("integer", 3))
        val sent = SentryFilters.beforeSendLog(log)
        assertEquals("pushing with [redacted]", sent.body)
        assertEquals("Bearer [redacted]", sent.attributes?.get("sentry.message.parameter.0")?.value)
        assertEquals(3, sent.attributes?.get("count")?.value)
    }

    @Test
    fun `a breadcrumb is scrubbed in its message and string data`() {
        val crumb = Breadcrumb().apply {
            message = "GET /api/rest/auth/device/Zz9"
            setData("url", "/oauth/callback?code=abc")
            setData("status", 200)
        }
        val sent = SentryFilters.beforeBreadcrumb(crumb)
        assertEquals("GET /api/rest/auth/device/[redacted]", sent.message)
        assertEquals("/oauth/callback?code=[redacted]", sent.getData("url"))
        assertEquals(200, sent.getData("status"))
    }

    @Test
    fun `Sentry's own logging and this package's stay local`() {
        assertTrue(SentryRuntime.LocalOnlyFilter.isLocalOnly("io.sentry.transport.AsyncHttpTransport"))
        assertTrue(SentryRuntime.LocalOnlyFilter.isLocalOnly("uk.me.cormack.lighting7.telemetry.SentryRuntime"))
        assertFalse(SentryRuntime.LocalOnlyFilter.isLocalOnly("error-handling"))
        assertFalse(SentryRuntime.LocalOnlyFilter.isLocalOnly("uk.me.cormack.lighting7.fx.FxEngine"))
    }

    @Test
    fun `the SDK's errors are breadcrumbs, and nothing below ERROR is taken`() {
        assertTrue(SentryRuntime.SdkErrorsAsBreadcrumbs.isEnabled(SentryLevel.ERROR))
        assertFalse(SentryRuntime.SdkErrorsAsBreadcrumbs.isEnabled(SentryLevel.WARNING))
        // Sentry is not running in the suite, so this must be a harmless no-op.
        SentryRuntime.SdkErrorsAsBreadcrumbs.log(SentryLevel.ERROR, "Request failed, API returned %s", 429)
    }
}
