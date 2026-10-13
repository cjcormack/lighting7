package uk.me.cormack.lighting7.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.sentry.ISpan
import io.sentry.Sentry
import io.sentry.TracesSamplingDecision
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.logback.SentryAppender
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.Test
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.SelectionClearInMessage
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.traceWsMessage
import uk.me.cormack.lighting7.plugins.withoutSamplingInputs
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.withTestSentry
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backend tracing (Sentry plan session 2). Nothing here leaves the process: see [withTestSentry]. */
class TracingTest : RouteIntegrationTest() {

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "op", "Operator", UserRole.OPERATOR, "hash")

    @Test
    fun `the HTTP plugin and the WS wrapper are inert while Sentry is off`() {
        assertFalse(Sentry.isEnabled())
        var inHandler: ISpan? = null
        var handled = false
        testApplication {
            mountTestApp(state)
            application {
                routing {
                    get("/api/rest/probe") {
                        inHandler = Sentry.getSpan()
                        handled = true
                        call.respondText("ok")
                    }
                }
            }
            assertEquals(HttpStatusCode.OK, jsonClient().get("/api/rest/probe").status)
        }
        assertTrue(handled)
        assertNull(inHandler)

        var ran = false
        runBlocking {
            traceWsMessage(SelectionClearInMessage, user, remote = false) {
                ran = true
                inHandler = Sentry.getSpan()
            }
        }
        assertTrue(ran)
        assertNull(inHandler)
        assertFalse(Sentry.isEnabled())
    }

    @Test
    fun `a call is named by its route template, never its path, and untraced paths send nothing`() {
        val secret = "SECRETtoken123"
        val captured = withTestSentry { captured ->
            testApplication {
                mountTestApp(state)
                val http = jsonClient()
                http.get("/api/rest/auth/reset/$secret")
                http.get("/api/rest/status")
                http.get("/api/rest/no-such-route/$secret")
                http.get("/index.html")
            }
            captured
        }
        val names = captured.transactions.map { it.transaction }
        assertEquals(listOf("GET /api/rest/auth/reset/{token}"), names)
        val tx = captured.transactions.single()
        assertEquals(TraceSampling.OP_HTTP_SERVER, tx.contexts.trace?.operation)
        assertEquals("local", tx.getTag(LISTENER_TAG))
        assertFalse(tx.toString().contains(secret))
        assertTrue(tx.spans.none { secret in (it.description ?: "") })
    }

    @Test
    fun `a caller's trace is continued, but never its sampling number`() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val captured = withTestSentry { captured ->
            testApplication {
                mountTestApp(state)
                jsonClient().get("/api/rest/projects") {
                    header("sentry-trace", "$traceId-0123456789abcdef-1")
                    header("baggage", "sentry-trace_id=$traceId,sentry-sample_rand=0.000001,sentry-sample_rate=0.000002,other=kept")
                }
            }
            captured
        }
        val tx = captured.transactions.single()
        assertEquals(traceId, tx.contexts.trace?.traceId.toString())
        val rand = assertNotNull(tx.samplingDecision?.sampleRand)
        assertTrue(rand != 0.000001 && rand > 0.000002, "the caller chose the sampling number: $rand")
        assertEquals(listOf("sentry-trace_id=$traceId,other=kept"), withoutSamplingInputs(listOf("sentry-trace_id=$traceId, sentry-sample_rand=0.1,other=kept")))
    }

    @Test
    fun `an error StatusPages logs is reported under the call's scope and trace`() {
        val root = (LoggerFactory.getILoggerFactory() as LoggerContext).getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        val captured = withTestSentry { captured ->
            val appender = SentryAppender().apply {
                context = root.loggerContext
                setMinimumEventLevel(Level.ERROR)
                start()
            }
            root.addAppender(appender)
            try {
                testApplication {
                    mountTestApp(state)
                    application { routing { get("/api/rest/boom") { error("boom") } } }
                    assertEquals(HttpStatusCode.InternalServerError, jsonClient().get("/api/rest/boom").status)
                }
            } finally {
                root.detachAppender(appender)
                appender.stop()
            }
            captured
        }
        val event = captured.events.single { it.throwable?.message == "boom" }
        val tx = captured.transactions.single { it.transaction == "GET /api/rest/boom" }
        assertEquals("local", event.getTag(LISTENER_TAG))
        assertEquals(tx.contexts.trace?.traceId, event.contexts.trace?.traceId)
        assertEquals("internal_error", tx.contexts.trace?.status?.apiName())
    }

    @Test
    fun `a gesture frame is a websocket message transaction with its user, a continuous one is nothing`() {
        val captured = withTestSentry { captured ->
            runBlocking {
                traceWsMessage(SelectionClearInMessage, user, remote = false) { assertNotNull(Sentry.getSpan()) }
                traceWsMessage(UpdateChannelInMessage(0, 1, 255u, 0), user, remote = false) { assertNull(Sentry.getSpan()) }
            }
            captured
        }
        val tx = captured.transactions.single()
        assertEquals("selection.clear", tx.transaction)
        assertEquals(TraceSampling.OP_WS_MESSAGE, tx.contexts.trace?.operation)
        assertEquals(user.uuid.toString(), tx.user?.id)
        assertEquals(mapOf("role" to "OPERATOR"), tx.user?.data)
    }

    @Test
    fun `a database transaction waits for its connection first, and its SQL carries no values`() {
        val captured = withTestSentry { captured ->
            val tx = Sentry.startTransaction("db test", "test", TransactionOptions().apply { isBindToScope = true })
            transaction(state.database) {
                DaoProjects.selectAll().where { DaoProjects.name eq "secret-name" }.toList()
                DaoProjects.update({ DaoProjects.name eq "secret-name" }) { it[description] = "secret-description" }
            }
            tx.finish()
            captured
        }
        val tx = captured.transactions.single { it.transaction == "db test" }
        val db = tx.spans.single { it.op == DbTracing.OP_TRANSACTION }
        val children = tx.spans.filter { it.parentSpanId == db.spanId }.sortedBy { it.startTimestamp }
        assertEquals(DbTracing.OP_ACQUIRE, children.first().op)
        val queries = children.filter { it.op == DbTracing.OP_QUERY }
        assertEquals(2, queries.size)
        for (query in queries) {
            val sql = query.description!!
            assertTrue("?" in sql, sql)
            assertFalse("secret" in sql, sql)
        }
        assertEquals(0, queries[1].data!!["db.rows_affected"])
    }

    @Test
    fun `an operation joins a traced request, and one always kept stands alone when the request was not sampled`() {
        val captured = withTestSentry { captured ->
            Tracing.transaction("project.switch", "project.switch") { assertNotNull(it) }

            val sampled = Sentry.startTransaction("GET /api/rest/x", "http.server", TransactionOptions().apply { isBindToScope = true })
            Tracing.transaction("project.switch", "project.switch") { Tracing.span("project.load") { } }
            sampled.finish()

            val unsampled = Sentry.startTransaction(
                TransactionContext("GET /api/rest/y", "http.server", TracesSamplingDecision(false)),
                TransactionOptions().apply { isBindToScope = true },
            )
            Tracing.transaction(TraceSampling.OP_CUE_GO, "cue.go") { }
            unsampled.finish()
            captured
        }
        val names = captured.transactions.map { it.transaction }
        assertEquals(listOf("project.switch", "GET /api/rest/x", "cue.go"), names)
        val request = captured.transactions[1]
        val switch = request.spans.single { it.op == "project.switch" }
        assertTrue(request.spans.any { it.op == "project.load" && it.parentSpanId == switch.spanId })
    }
}
