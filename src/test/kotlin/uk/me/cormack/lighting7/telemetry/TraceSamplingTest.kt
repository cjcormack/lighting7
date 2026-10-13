package uk.me.cormack.lighting7.telemetry

import io.sentry.SamplingContext
import io.sentry.SpanId
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.protocol.SentryId
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionInfo
import kotlinx.serialization.serializer
import org.junit.Test
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.UNMATCHED_ROUTE
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceSamplingTest {

    private fun leaves(type: KClass<*>): List<KClass<*>> = if (type.isSealed) type.sealedSubclasses.flatMap(::leaves) else listOf(type)

    private val inboundTypes = leaves(InMessage::class).map { serializer(it.java).descriptor.serialName }

    private fun rate(op: String, name: String, configured: Double = 0.2) =
        TraceSampling.sample(SamplingContext(TransactionContext(name, op), null, 0.5, emptyMap()), configured)

    @Test
    fun `every inbound frame type has a row, and the table names no frame that does not exist`() {
        val unlisted = inboundTypes.filter { it !in TraceSampling.WS_MESSAGES }
        assertTrue(unlisted.isEmpty(), "classify these in TraceSampling.WS_MESSAGES: $unlisted")
        val stale = TraceSampling.WS_MESSAGES.keys - inboundTypes.toSet()
        assertTrue(stale.isEmpty(), "no such inbound frame: $stale")
    }

    @Test
    fun `continuous streams and snapshot requests are never traced`() {
        for (type in listOf("updateChannel", "programmer.set", "programmer.setColour", "programmer.setPosition", "windows.announce", "speedMasters.tap", "surfaceScaler.setGrandMaster", "channelState")) {
            assertFalse(TraceSampling.isTracedWsMessage(type), type)
            assertEquals(0.0, rate(TraceSampling.OP_WS_MESSAGE, type, configured = 1.0), type)
        }
        for ((type, kind) in TraceSampling.WS_MESSAGES) {
            assertEquals(kind == TraceSampling.WsKind.GESTURE, TraceSampling.isTracedWsMessage(type), type)
        }
        assertFalse(TraceSampling.isTracedWsMessage("someFutureFrame"))
    }

    @Test
    fun `the sampler keeps boot, cue GO and sync runs, and samples the rest at the configured rate`() {
        assertEquals(1.0, rate("app.boot", "app.boot"))
        assertEquals(1.0, rate("cue.go", "cue.go"))
        assertEquals(1.0, rate("sync.run", "sync.run"))
        assertEquals(0.2, rate("http.server", "GET /api/rest/projects"))
        assertEquals(0.2, rate(TraceSampling.OP_WS_MESSAGE, "selection.set"))
        assertEquals(0.0, rate(TraceSampling.OP_WS_MESSAGE, "updateChannel"))
        assertEquals(0.2, rate("project.switch", "project.switch"))
    }

    @Test
    fun `an idle auto-sync run is kept at the sample rate, and every other run always`() {
        assertFalse(TraceSampling.keepSyncRun("AUTO", "NO_OP", rate = 0.2, draw = 0.5))
        assertTrue(TraceSampling.keepSyncRun("AUTO", "NO_OP", rate = 0.2, draw = 0.1))
        assertTrue(TraceSampling.keepSyncRun("AUTO", "FAST_FORWARDED", rate = 0.0, draw = 0.9))
        assertTrue(TraceSampling.keepSyncRun("MANUAL", "NO_OP", rate = 0.0, draw = 0.9))
        assertTrue(TraceSampling.keepSyncRun("AUTO", null, rate = 0.0, draw = 0.9), "a run that threw has no outcome")

        fun run(trigger: String, outcome: String) = transaction("sync.run").apply {
            contexts.setTrace(io.sentry.SpanContext("sync.run").apply {
                setData("sync.trigger", trigger)
                setData("sync.outcome", outcome)
            })
        }
        assertNull(SentryFilters.beforeSendTransaction(run("AUTO", "NO_OP"), 0.2, draw = 0.5))
        assertNotNull(SentryFilters.beforeSendTransaction(run("AUTO", "PUSHED"), 0.2, draw = 0.5))
    }

    private fun span(description: String, data: Map<String, Any> = emptyMap()) = SentrySpan(
        0.0, 1.0, SentryId(), SpanId(), SpanId(), "http.client", description, SpanStatus.OK, "manual",
        emptyMap(), emptyMap(), data,
    )

    private fun transaction(name: String, vararg spans: SentrySpan) =
        SentryTransaction(name, 0.0, 1.0, spans.toMutableList(), mutableMapOf(), TransactionInfo("route"))

    @Test
    fun `transactions are scrubbed, and an unmatched or token-bearing name is never sent`() {
        val token = "ghp_" + "a".repeat(36)
        assertNull(SentryFilters.beforeSendTransaction(transaction("GET $UNMATCHED_ROUTE"), 1.0))
        assertNull(SentryFilters.beforeSendTransaction(transaction("GET /api/rest/auth/reset/x?code=abc"), 1.0))

        val sent = assertNotNull(
            SentryFilters.beforeSendTransaction(
                transaction(
                    "POST /api/rest/projects/{projectId}/sync/run",
                    span("git fetch https://$token@github.com/o/r"),
                    span("GET https://api.github.com/x", mapOf("http.query" to "code=secret", "count" to 3)),
                    span("SELECT 1"),
                ),
                1.0,
            ),
        )
        assertEquals("POST /api/rest/projects/{projectId}/sync/run", sent.transaction)
        val descriptions = sent.spans.map { it.description!! }
        assertFalse(descriptions.any { token in it }, descriptions.toString())
        assertEquals("SELECT 1", descriptions[2])
        assertEquals(3, sent.spans[1].data!!["count"])
        assertFalse("secret" in sent.spans[1].data!!["http.query"].toString())
    }
}
