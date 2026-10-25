package uk.me.cormack.lighting7.routes

import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.StackFxAmountEntry
import uk.me.cormack.lighting7.plugins.StackFxAmountSetInMessage
import uk.me.cormack.lighting7.plugins.StackFxAmountStateOutMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A stack's FX amount over REST and WS (effects plan session 5, §3.7). */
class StackFxAmountRoutesTest : RouteIntegrationTest() {

    private val sizes get() = state.show.cueStackManager.fxAmounts

    private fun stack(projectId: Int = this.projectId): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject.findById(projectId)!!
            name = "S"; loop = false
            type = CueStackType.STACK.name; sortOrder = 0
        }.id.value
    }

    @Test
    fun `REST reads and sets a stack's size, and refuses one outside 0 to 1 or another project's stack`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val s = stack()
        val path = "/api/rest/projects/$projectId/cue-stacks/$s/fx-amount"

        assertEquals(StackFxAmountDto(1.0), TestJson.decodeFromString(client.get(path).bodyAsText()))

        val set = client.put(path) { contentType(ContentType.Application.Json); setBody("""{"amount":0.6}""") }
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals(StackFxAmountDto(0.6), TestJson.decodeFromString(set.bodyAsText()))
        assertEquals(0.6, sizes.of(s))

        val over = client.put(path) { contentType(ContentType.Application.Json); setBody("""{"amount":1.5}""") }
        assertEquals(HttpStatusCode.BadRequest, over.status)
        assertTrue(over.bodyAsText().contains(CODE_STACK_FX_AMOUNT_INVALID), over.bodyAsText())
        assertEquals(0.6, sizes.of(s))

        val other = transaction(state.database) {
            DaoProject.new { name = "Other"; description = ""; isCurrent = false }.id.value
        }
        val elsewhere = stack(other)
        val notOurs = client.put("/api/rest/projects/$projectId/cue-stacks/$elsewhere/fx-amount") {
            contentType(ContentType.Application.Json); setBody("""{"amount":0.2}""")
        }
        assertEquals(HttpStatusCode.NotFound, notOurs.status)
        val notLive = client.put("/api/rest/projects/$other/cue-stacks/$elsewhere/fx-amount") {
            contentType(ContentType.Application.Json); setBody("""{"amount":0.2}""")
        }
        assertEquals(HttpStatusCode.Conflict, notLive.status)
        assertEquals(1.0, sizes.of(elsewhere))
    }

    @Test
    fun `WS sets a stack's size and streams every stack below full`() = testApplication {
        mountTestApp(state)
        val s = stack()
        createWsClient().webSocket("/api") {
            assertEquals(emptyList(), awaitOfType<StackFxAmountStateOutMessage>().stacks)
            sendSerialized<InMessage>(StackFxAmountSetInMessage(s, 0.25))
            val moved = awaitOfType<StackFxAmountStateOutMessage> { it.stacks.isNotEmpty() }
            assertEquals(projectId, moved.projectId)
            assertEquals(listOf(StackFxAmountEntry(s, 0.25)), moved.stacks)

            // A stack the live project does not have is dropped, and nothing is sent.
            sendSerialized<InMessage>(StackFxAmountSetInMessage(s + 1000, 0.5))
            sendSerialized<InMessage>(StackFxAmountSetInMessage(s, 1.0))
            assertEquals(emptyList(), awaitOfType<StackFxAmountStateOutMessage> { it.stacks.isEmpty() }.stacks)
        }
        assertEquals(1.0, sizes.of(s))
        assertTrue(sizes.snapshot().isEmpty())
    }

    @Test
    fun `an operator may move a stack's size on a venue`() {
        assertNull(projectWriteRefusal(ProjectKind.VENUE, isAdmin = false, rest = listOf("cue-stacks", "4", "fx-amount")))
    }
}
