package uk.me.cormack.lighting7.telemetry

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.mcp.publicModule
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.isTracedPath
import uk.me.cormack.lighting7.routes.ErrorResponse
import uk.me.cormack.lighting7.state.appDataDir
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.update.BuildInfo
import java.net.http.HttpHeaders as JdkHeaders
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readBytes
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `POST /api/sentry` and its spool: what it takes, what it refuses, and how the worker forwards (Sentry plan D9). */
class BrowserTunnelTest : RouteIntegrationTest() {

    override val extraConfig = listOf("sentry.browserDsn" to DSN)

    private val spool: Path get() = appDataDir().resolve("sentry-browser")

    @Before
    fun clearSpool() {
        spool.toFile().deleteRecursively()
    }

    private fun envelope(dsn: String? = DSN, body: String = """{"type":"event"}""") =
        ((if (dsn == null) """{"event_id":"e1"}""" else """{"event_id":"e1","dsn":"$dsn"}""") + "\n{\"type\":\"event\"}\n$body").toByteArray()

    private suspend fun HttpClient.tunnel(bytes: ByteArray, origin: String? = null): HttpResponse = post("/api/sentry") {
        contentType(ContentType.Text.Plain)
        if (origin != null) header(HttpHeaders.Origin, origin)
        setBody(bytes)
    }

    private fun tunnel(spoolDir: Path, enabled: Boolean = true, maxEnvelopes: Int = 300, clock: () -> Long = System::currentTimeMillis): BrowserTunnel {
        val settings = TelemetrySettings(state.database, SentryConfig("https://k@example/1", true, null, null, browserDsn = DSN), BuildInfo("1.4.0", BuildInfo.Channel.DEV, null, null))
        settings.setChoice(enabled)
        return BrowserTunnel(settings, spoolDir, maxEnvelopes = maxEnvelopes, clock = clock)
    }

    @Test
    fun `a signed-out browser's envelope for our DSN is spooled byte for byte`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", UserRole.ADMIN)
        val bytes = envelope()
        assertEquals(HttpStatusCode.OK, jsonClient().tunnel(bytes).status)
        val files = spool.listDirectoryEntries("*.envelope")
        assertEquals(1, files.size)
        assertContentEquals(bytes, files.single().readBytes())
    }

    @Test
    fun `the tunnel refuses another DSN, no DSN, an oversized body and a desk with telemetry off`() = testApplication {
        mountTestApp(state)
        val http = jsonClient()
        suspend fun refused(bytes: ByteArray, status: HttpStatusCode, code: String) {
            val response = http.tunnel(bytes)
            assertEquals(status, response.status)
            assertEquals(code, response.body<ErrorResponse>().code)
        }
        refused(envelope("https://5ccbcddbb005a1b8c562095a04dc25a0@o1.ingest.sentry.io/999"), HttpStatusCode.Forbidden, "ENVELOPE_DSN_REFUSED")
        refused(envelope("https://another@o4512248405164032.ingest.de.sentry.io/4512248441471056"), HttpStatusCode.Forbidden, "ENVELOPE_DSN_REFUSED")
        refused(envelope(dsn = null), HttpStatusCode.BadRequest, "ENVELOPE_INVALID")
        refused("not an envelope".toByteArray(), HttpStatusCode.BadRequest, "ENVELOPE_INVALID")
        refused(envelope(body = "x".repeat(BrowserTunnel.MAX_ENVELOPE_BYTES)), HttpStatusCode.PayloadTooLarge, "ENVELOPE_TOO_LARGE")
        state.telemetry.setChoice(false)
        refused(envelope(), HttpStatusCode.Forbidden, "TELEMETRY_OFF")
        assertTrue(state.browserTunnel.pending().isEmpty())
    }

    @Test
    fun `the public listener takes the same envelope from the desk's own page and refuses a foreign one`() = testApplication {
        environment { config = testAppConfig() }
        application { publicModule(state) }
        seedUser(state, "admin", UserRole.ADMIN)
        val http = jsonClient()
        assertEquals(HttpStatusCode.OK, http.tunnel(envelope(), origin = "http://localhost:${state.mcpConfig.port}").status)
        assertEquals(HttpStatusCode.Forbidden, http.tunnel(envelope(), origin = "https://evil.example").status)
        val foreign = http.tunnel(envelope("https://k@o1.ingest.sentry.io/2"))
        assertEquals("ENVELOPE_DSN_REFUSED", foreign.body<ErrorResponse>().code)
        assertEquals(1, state.browserTunnel.pending().size)
    }

    @Test
    fun `the tunnel is not traced, or every browser envelope would cost a backend transaction`() {
        assertFalse(isTracedPath("/api/sentry"))
        assertTrue(isTracedPath("/api/rest/projects"))
    }

    @Test
    fun `the worker forwards oldest first and keeps what it couldn't send`() {
        val dir = Files.createTempDirectory("tunnel")
        var now = 1_000L
        val t = tunnel(dir, clock = { now++ })
        val first = envelope(body = "first")
        val second = envelope(body = "second")
        assertNull(t.accept(first))
        assertNull(t.accept(second))

        val sent = mutableListOf<ByteArray>()
        var answer: SendResult = SendResult.Failed
        val sender = EnvelopeSender { sent += it; answer }

        assertEquals(BrowserTunnel.INITIAL_BACKOFF_MS, t.drain(sender))
        assertEquals(2 * BrowserTunnel.INITIAL_BACKOFF_MS, t.drain(sender), "a second failure backs off further")
        assertEquals(2, t.pending().size)

        answer = SendResult.Wait(30_000)
        assertEquals(30_000, t.drain(sender))
        answer = SendResult.Wait(0)
        assertEquals(BrowserTunnel.INITIAL_BACKOFF_MS, t.drain(sender), "a wait is never shorter than the first backoff")
        assertEquals(2, t.pending().size)

        sent.clear()
        answer = SendResult.Done
        assertNull(t.drain(sender))
        assertEquals(listOf("first", "second"), sent.map { String(it).substringAfterLast('\n') })
        assertContentEquals(first, sent.first())
        assertTrue(t.pending().isEmpty())

        answer = SendResult.Failed
        t.accept(envelope())
        assertEquals(BrowserTunnel.INITIAL_BACKOFF_MS, t.drain(sender), "a success resets the backoff")
    }

    @Test
    fun `nothing is forwarded while telemetry is off`() {
        val dir = Files.createTempDirectory("tunnel")
        val t = tunnel(dir)
        assertNull(t.accept(envelope()))
        val settingsOff = tunnel(dir, enabled = false)
        val sent = mutableListOf<ByteArray>()
        assertNull(settingsOff.drain { sent += it; SendResult.Done })
        assertTrue(sent.isEmpty())
        assertEquals(1, settingsOff.pending().size)
    }

    @Test
    fun `turning telemetry off deletes what is spooled`() {
        val dir = Files.createTempDirectory("tunnel")
        val t = tunnel(dir)
        assertNull(t.accept(envelope()))
        assertNull(t.accept(envelope()))
        t.onTelemetryChanged(true)
        assertEquals(2, t.pending().size)
        t.onTelemetryChanged(false)
        assertTrue(t.pending().isEmpty())
        assertTrue(dir.listDirectoryEntries().isEmpty())
    }

    @Test
    fun `an unreadable envelope backs the worker off rather than stopping it`() {
        val dir = Files.createTempDirectory("tunnel")
        val t = tunnel(dir)
        assertNull(t.accept(envelope()))
        val spooled = t.pending().single()
        Files.delete(spooled)
        Files.createDirectory(spooled)
        assertEquals(BrowserTunnel.INITIAL_BACKOFF_MS, t.drain { SendResult.Done })
        assertEquals(2 * BrowserTunnel.INITIAL_BACKOFF_MS, t.drain { SendResult.Done })
    }

    @Test
    fun `a spool a crash left half-written is swept, and the spool on disk is read once`() {
        val dir = Files.createTempDirectory("tunnel")
        val stray = Files.writeString(dir.resolve("0000000000001-000001.part"), "half")
        val leftover = envelope(body = "leftover")
        Files.write(dir.resolve("0000000000002-000001.envelope"), leftover)
        val t = tunnel(dir)
        assertEquals(1, t.pending().size)
        assertFalse(Files.exists(stray))
        val sent = mutableListOf<ByteArray>()
        assertNull(t.drain { sent += it; SendResult.Done })
        assertContentEquals(leftover, sent.single())
    }

    @Test
    fun `the spool keeps the newest envelopes past its cap`() {
        val dir = Files.createTempDirectory("tunnel")
        var now = 1_000L
        val t = tunnel(dir, maxEnvelopes = 3, clock = { now++ })
        for (i in 1..5) assertNull(t.accept(envelope(body = "n$i")))
        assertEquals(listOf("n3", "n4", "n5"), t.pending().map { String(it.readBytes()).substringAfterLast('\n') })
    }

    @Test
    fun `a DSN is compared by its parts, and the forward goes to our own project`() {
        val ours = checkNotNull(SentryDsn.parse(DSN))
        assertEquals(ours, SentryDsn.parse(" $DSN "))
        assertEquals("https://o4512248405164032.ingest.de.sentry.io/api/4512248441471056/envelope/?sentry_key=5ccbcddbb005a1b8c562095a04dc25a0&sentry_version=7", ours.envelopeUrl.toString())
        assertNull(SentryDsn.parse("ftp://k@host/1"))
        assertNull(SentryDsn.parse("https://host/1"))
        assertNull(SentryDsn.parse("https://k@host/a/1"))
        assertEquals(ours, BrowserTunnel.dsnNamedBy(envelope()))
        assertNull(BrowserTunnel.dsnNamedBy("""{"dsn":42}""".toByteArray()))
    }

    @Test
    fun `a rate limit waits as long as Sentry says`() {
        fun headers(vararg pairs: Pair<String, String>) = JdkHeaders.of(pairs.associate { it.first to listOf(it.second) }) { _, _ -> true }
        assertEquals(120_000, HttpEnvelopeSender.retryAfterMillis(headers("Retry-After" to "120")))
        assertEquals(30_000, HttpEnvelopeSender.retryAfterMillis(headers("X-Sentry-Rate-Limits" to "30:error:organization")))
        assertEquals(60_000, HttpEnvelopeSender.retryAfterMillis(headers()))
    }

    private companion object {
        const val DSN = "https://5ccbcddbb005a1b8c562095a04dc25a0@o4512248405164032.ingest.de.sentry.io/4512248441471056"
    }
}
