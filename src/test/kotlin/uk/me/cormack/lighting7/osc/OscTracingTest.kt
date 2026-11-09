package uk.me.cormack.lighting7.osc

import io.sentry.SpanStatus
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.After
import org.junit.Before
import org.junit.Test
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.EncoderBankState
import uk.me.cormack.lighting7.midi.SurfaceDispatch
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.telemetry.TraceSampling
import uk.me.cormack.lighting7.testsupport.IntegrationTestDb
import uk.me.cormack.lighting7.testsupport.RecordingSurfaceActions
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import uk.me.cormack.lighting7.testsupport.testAppConfig
import uk.me.cormack.lighting7.testsupport.withTestSentry
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each OSC message is an `osc.message` transaction named by the pattern it matched, never by the
 * show's own words, tagged with its outcome — and no more than the handler's budget a second.
 */
class OscTracingTest {
    private lateinit var state: State
    private lateinit var handler: OscHandler
    private val qlab = OscSender(1, "QLab", "127.0.0.1")

    @Before
    fun setUp() {
        IntegrationTestDb.reset()
        state = State(testAppConfig())
        val projectId = seedMinimalProject(state)
        transaction(state.database) { DaoCueStack.new { name = "Act 1"; project = DaoProject[projectId] } }
        handler = OscHandler(
            state.database, { projectId },
            SurfaceDispatch(RecordingSurfaceActions(), ActiveBankState(), EncoderBankState()),
        )
    }

    @After
    fun tearDown() {
        runCatching { state.shutdown() }
    }

    private fun send(address: String) =
        handler.handle(OscInbound(Instant.now(), qlab, OscPacket.Item.Message(OscMessage(address, emptyList()))))

    @Test
    fun `a message is a transaction named by its pattern, with its outcome, resolve and dispatch`() {
        val transactions = withTestSentry { captured ->
            send("/lighting7/stack/act-1/go")
            send("/lighting7/stack/act-9/go")
            send("/lighting7/stack/act-1/jump")
            handler.handle(OscInbound(Instant.now(), qlab, OscPacket.Item.Bad(null, "empty packet")))
            captured.transactions.toList()
        }
        assertTrue(transactions.all { it.contexts.trace?.operation == TraceSampling.OP_OSC_MESSAGE })
        assertEquals(
            listOf("/lighting7/stack/{stack}/go", "/lighting7/stack/{stack}/go", OscHandler.UNKNOWN_ADDRESS, OscHandler.BAD_PACKET),
            transactions.map { it.transaction },
        )
        assertEquals(listOf("done", "not_found", "bad_message", "bad_message"), transactions.map { it.getTag(OscHandler.OUTCOME_TAG) })
        assertEquals(SpanStatus.NOT_FOUND, transactions[1].contexts.trace?.status)
        assertTrue(transactions[0].spans.map { it.op }.containsAll(listOf("osc.resolve", "osc.dispatch", "db.sql.query")))
        assertTrue(transactions.none { "act-1" in (it.transaction ?: "") }, "a name carries no show's words")
    }

    @Test
    fun `a flood is traced only up to the budget`() {
        val transactions = withTestSentry { captured ->
            repeat(OscHandler.TRACE_BUDGET_PER_SECOND * 4) { send("/lighting7/stack/act-1/go") }
            captured.transactions.toList()
        }
        assertTrue(transactions.size <= OscHandler.TRACE_BUDGET_PER_SECOND * 2, "${transactions.size} traced")
        assertTrue(transactions.size >= OscHandler.TRACE_BUDGET_PER_SECOND)
    }
}
