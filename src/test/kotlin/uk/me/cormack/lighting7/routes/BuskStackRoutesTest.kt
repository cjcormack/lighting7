package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.CODE_CUE_STACK_INVALID
import uk.me.cormack.lighting7.models.CODE_CUE_STACK_IS_PLAYHEAD
import uk.me.cormack.lighting7.models.CODE_CUE_STACK_NOT_IN_SHOW
import uk.me.cormack.lighting7.models.DaoCueAttributeAssignment
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.plugins.HISTORY_ENTRY_HEADER
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.show.FixturesChangeListener
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Busk stacks and the running order through the routes (busk-stack plan session 1, D1–D4, D6): the
 * stack settings and their refusals, the show's playhead skipping busk stacks in either direction, and
 * the stop that fades.
 */
class BuskStackRoutesTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private suspend fun HttpClient.send(method: String, path: String, body: String = "{}"): HttpResponse {
        val build: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return when (method) {
            "POST" -> post("$base$path", build)
            "PUT" -> put("$base$path", build)
            else -> error(method)
        }
    }

    private suspend fun HttpClient.createStack(name: String, extra: String = ""): CueStackDetails {
        val resp = send("POST", "/cue-stacks", """{"name":"$name"$extra}""")
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.stack(id: Int): CueStackDetails = get("$base/cue-stacks/$id").body()

    /** A cue in [stackId] lighting hex-1's dimmer, so a stop has rows to fade. */
    private suspend fun HttpClient.createCue(name: String, stackId: Int): Int {
        val resp = send("POST", "/cues", """{"name":"$name","cueStackId":$stackId}""")
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val id = resp.body<CueDetails>().id
        transaction(state.database) {
            DaoCueAttributeAssignment.new {
                cue = DaoCue[id]
                targetType = "fixture"; targetKey = "hex-1"; attribute = "dimmer"; value = "200"; sortOrder = 0
            }
        }
        return id
    }

    private suspend fun HttpResponse.code(): String? =
        Json.parseToJsonElement(bodyAsText()).jsonObject["code"]?.jsonPrimitive?.content

    private fun playhead(): Int? = transaction(state.database) { DaoProject[projectId].activeStackId }

    @Test
    fun `a new stack is in the running order, and a busk stack is made with Release unless it loops`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val act = client.createStack("Act 1")
        assertTrue(act.inRunningOrder)
        assertFalse(act.releaseAtEnd)
        assertEquals(2000, act.releaseTimeMs)

        val song = client.createStack("Song", ""","inRunningOrder":false""")
        assertFalse(song.inRunningOrder)
        assertTrue(song.releaseAtEnd, "a busk stack releases at its end")

        val chase = client.createStack("Chase", ""","inRunningOrder":false,"loop":true""")
        assertTrue(chase.loop)
        assertFalse(chase.releaseAtEnd, "unless it loops")

        val stay = client.createStack("Stay", ""","inRunningOrder":false,"releaseAtEnd":false""")
        assertFalse(stay.releaseAtEnd, "or the request says otherwise")
    }

    @Test
    fun `a PUT keeps what it does not name, and one turning the switch off sets Release`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val id = client.createStack("Act 1", ""","releaseTimeMs":3500""").id

        val renamed = client.send("PUT", "/cue-stacks/$id", """{"name":"Act One"}""").body<CueStackDetails>()
        assertEquals(3500, renamed.releaseTimeMs, "kept")
        assertTrue(renamed.inRunningOrder)

        val busk = client.send("PUT", "/cue-stacks/$id", """{"name":"Act One","inRunningOrder":false}""").body<CueStackDetails>()
        assertFalse(busk.inRunningOrder)
        assertTrue(busk.releaseAtEnd)

        val stays = client.send("PUT", "/cue-stacks/$id", """{"name":"Act One","releaseAtEnd":false}""").body<CueStackDetails>()
        assertFalse(stays.releaseAtEnd, "once a busk stack, Release is its own setting")
        assertFalse(stays.inRunningOrder)
    }

    @Test
    fun `Loop with Release and a release time outside 0-60 s are refused together`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val both = client.send("POST", "/cue-stacks", """{"name":"X","loop":true,"releaseAtEnd":true,"releaseTimeMs":61000}""")
        assertEquals(HttpStatusCode.BadRequest, both.status)
        assertEquals(CODE_CUE_STACK_INVALID, both.code())
        val text = both.bodyAsText()
        assertTrue("loop" in text && "releaseTimeMs" in text, "every problem named: $text")

        // Judged on the merged result: Release stored, Loop asked for.
        val id = client.createStack("Song", ""","inRunningOrder":false""").id
        val loop = client.send("PUT", "/cue-stacks/$id", """{"name":"Song","loop":true}""")
        assertEquals(HttpStatusCode.BadRequest, loop.status)
        assertEquals(CODE_CUE_STACK_INVALID, loop.code())
        // A separator has no run settings, so a body naming them is not judged on them.
        val separator = client.send("POST", "/cue-stacks", """{"name":"Interval","type":"SEPARATOR","loop":true,"releaseAtEnd":true}""")
        assertEquals(HttpStatusCode.Created, separator.status, separator.bodyAsText())
    }

    @Test
    fun `the playhead stack cannot leave the running order, and go-to refuses a busk stack`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val act = client.createStack("Act 1").id
        val song = client.createStack("Song", ""","inRunningOrder":false""").id
        client.createCue("a1", act)
        client.createCue("s1", song)
        client.send("POST", "/show/activate")
        assertEquals(act, playhead())

        val leave = client.send("PUT", "/cue-stacks/$act", """{"name":"Act 1","inRunningOrder":false}""")
        assertEquals(HttpStatusCode.Conflict, leave.status)
        assertEquals(CODE_CUE_STACK_IS_PLAYHEAD, leave.code())
        assertTrue(client.stack(act).inRunningOrder, "nothing written")

        val goTo = client.send("POST", "/show/go-to", """{"stackId":$song}""")
        assertEquals(HttpStatusCode.Conflict, goTo.status)
        assertEquals(CODE_CUE_STACK_NOT_IN_SHOW, goTo.code())
        assertEquals(act, playhead())
    }

    @Test
    fun `the show's advance skips busk stacks in both directions, and activate starts past one`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val songA = client.createStack("Song A", ""","inRunningOrder":false""").id
        val act1 = client.createStack("Act 1").id
        val songB = client.createStack("Song B", ""","inRunningOrder":false""").id
        val act2 = client.createStack("Act 2").id
        for ((name, id) in listOf("sa" to songA, "a1" to act1, "sb" to songB, "a2" to act2)) client.createCue(name, id)

        assertEquals(act1, client.send("POST", "/show/activate").body<ShowActivateResponse>().activeStackId)
        val forward = client.send("POST", "/show/advance", """{"direction":"FORWARD"}""").body<ShowActivateResponse>()
        assertEquals(act2, forward.activeStackId, "Song B is skipped")
        val back = client.send("POST", "/show/advance", """{"direction":"BACKWARD"}""").body<ShowActivateResponse>()
        assertEquals(act1, back.activeStackId, "and on the way back")
        assertEquals(HttpStatusCode.BadRequest, client.send("POST", "/show/advance", """{"direction":"BACKWARD"}""").status, "Song A is no stack before Act 1")
        assertNull(client.stack(songB).activeCueId, "a busk stack is never started by the show")
    }

    @Test
    fun `the REST deactivate fades over the release time, and snap lets go at once`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val id = client.createStack("Act 1", ""","releaseTimeMs":800""").id
        val cue = client.createCue("a1", id)
        val layer = state.show.fxEngine.cueLayer

        client.send("POST", "/cue-stacks/$id/activate")
        assertEquals(HttpStatusCode.OK, client.send("POST", "/cue-stacks/$id/deactivate").status)
        assertNull(client.stack(id).activeCueId, "stopped at once")
        assertTrue(layer.hasAssignments(cue), "its light still fading")
        delay(1_100)
        assertFalse(layer.hasAssignments(cue))

        client.send("POST", "/cue-stacks/$id/activate")
        client.send("POST", "/cue-stacks/$id/deactivate", """{"snap":true}""")
        assertFalse(layer.hasAssignments(cue), "snapped")
    }

    @Test
    fun `a GO into the next stack during a release leaves the release running, and a delete cuts it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val act1 = client.createStack("Act 1", ""","releaseTimeMs":1500""").id
        val act2 = client.createStack("Act 2").id
        val a1 = client.createCue("a1", act1)
        client.createCue("a2", act2)
        val layer = state.show.fxEngine.cueLayer

        client.send("POST", "/show/activate")
        client.send("POST", "/cue-stacks/$act1/deactivate")
        delay(300)
        val before = layer.fadeWeightOf(a1)
        client.send("POST", "/show/advance", """{"direction":"FORWARD"}""")
        assertTrue(layer.hasAssignments(a1), "the advance leaves a stopped stack's release alone")
        assertTrue(layer.fadeWeightOf(a1) <= before + 0.02, "still falling, not snapped back")

        client.send("POST", "/show/go-to", """{"stackId":$act1}""")
        client.send("POST", "/cue-stacks/$act1/deactivate")
        assertTrue(layer.hasAssignments(a1))
        assertEquals(HttpStatusCode.OK, client.delete("$base/cue-stacks/$act1").status)
        assertFalse(layer.hasAssignments(a1), "a delete lets go at once")
    }

    @Test
    fun `the show's advance still tears down a stack stopped with nothing releasing`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val act1 = client.createStack("Act 1", ""","releaseTimeMs":0""").id
        val act2 = client.createStack("Act 2").id
        val a1 = client.createCue("a1", act1)
        val a2 = client.createCue("a2", act1)
        client.createCue("b1", act2)
        client.send("POST", "/show/activate")
        client.send("POST", "/cue-stacks/$act1/deactivate")
        val armed = state.entryIdOf(a2)
        client.send("POST", "/cue-stacks/$act1/standby", """{"entryId":$armed}""")
        assertEquals(armed, state.show.cueStackManager.runState.getStandbyEntryId(act1))

        client.send("POST", "/show/advance", """{"direction":"FORWARD"}""")
        assertNull(state.show.cueStackManager.runState.getStandbyEntryId(act1), "the standby goes with the stack the show left")

        client.send("POST", "/show/advance", """{"direction":"BACKWARD"}""")
        assertEquals(a1, client.stack(act1).activeCueId, "so coming back starts it at its first cue")
    }

    @Test
    fun `an undo that leaves the playhead on a busk stack clears the playhead`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        state.ensureHistory(projectId)
        val id = client.createStack("Song", ""","inRunningOrder":false""").id
        client.createCue("s1", id)
        val joined = client.send("PUT", "/cue-stacks/$id", """{"name":"Song","inRunningOrder":true}""")
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        val entry = state.history.entries(projectId).single { joined.headers[HISTORY_ENTRY_HEADER] in it.requestIds }
        client.send("POST", "/show/activate")
        assertEquals(id, playhead())

        val heard = mutableListOf<Int?>()
        val listener = object : FixturesChangeListener {
            override fun showChanged(projectId: Int, activeStackId: Int?, activeStackName: String?) { heard += activeStackId }
        }
        state.show.fixtures.registerListener(listener)
        val undo = try {
            client.send("POST", "/history/undo", """{"entryId":"${entry.id}","backToHere":true,"confirm":true}""")
        } finally {
            state.show.fixtures.unregisterListener(listener)
        }
        assertEquals(HttpStatusCode.OK, undo.status, undo.bodyAsText())

        assertFalse(client.stack(id).inRunningOrder)
        assertNull(playhead(), "a busk stack is never the playhead")
        assertEquals(listOf<Int?>(null), heard, "and every window is told the playhead moved")
        assertEquals(
            uk.me.cormack.lighting7.fx.StackRanks.Band.BUSK,
            state.show.cueStackManager.holds.ranks.placeOf(id)?.band,
            "and it runs on in the busk band",
        )
    }

    @Test
    fun `Over the show is a busk stack's, kept on a PUT, and Adds once back in the running order`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val inOrder = client.send("POST", "/cue-stacks", """{"name":"Act 1","overShow":"REPLACES"}""")
        assertEquals(HttpStatusCode.BadRequest, inOrder.status)
        assertEquals(CODE_CUE_STACK_INVALID, inOrder.code())
        assertTrue("overShow" in inOrder.bodyAsText())
        val unknown = client.send("POST", "/cue-stacks", """{"name":"Act 1","inRunningOrder":false,"overShow":"SOMETIMES"}""")
        assertEquals(HttpStatusCode.BadRequest, unknown.status)

        val act = client.createStack("Act 1")
        assertEquals("ADDS", act.overShow)
        val cara = client.createStack("Carabossy", ""","inRunningOrder":false,"overShow":"REPLACES"""")
        assertEquals("REPLACES", cara.overShow)
        val kept = client.send("PUT", "/cue-stacks/${cara.id}", """{"name":"Carabossy","releaseTimeMs":3000}""").body<CueStackDetails>()
        assertEquals("REPLACES", kept.overShow, "a PUT naming none keeps it")

        val refused = client.send("PUT", "/cue-stacks/${cara.id}", """{"name":"Carabossy","inRunningOrder":true,"overShow":"REPLACES"}""")
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(CODE_CUE_STACK_INVALID, refused.code())

        val back = client.send("PUT", "/cue-stacks/${cara.id}", """{"name":"Carabossy","inRunningOrder":true}""")
        assertEquals(HttpStatusCode.OK, back.status, back.bodyAsText())
        val rejoined = back.body<CueStackDetails>()
        assertTrue(rejoined.inRunningOrder)
        assertEquals("ADDS", rejoined.overShow, "back in the running order it adds")
    }

    @Test
    fun `a live stack turned into a busk stack takes the busk band's top`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val act = client.createStack("Act 1")
        val song = client.createStack("Song")
        client.createCue("Q1", act.id)
        client.createCue("S1", song.id)
        val manager = state.show.cueStackManager
        manager.activateAtFirstCue(state, song.id)
        assertEquals(uk.me.cormack.lighting7.fx.StackRanks.Band.SHOW, manager.holds.ranks.placeOf(song.id)?.band)

        val busk = client.send("PUT", "/cue-stacks/${song.id}", """{"name":"Song","inRunningOrder":false}""")
        assertEquals(HttpStatusCode.OK, busk.status, busk.bodyAsText())

        assertEquals(
            uk.me.cormack.lighting7.fx.StackRanks.Place(uk.me.cormack.lighting7.fx.StackRanks.Band.BUSK, 1),
            manager.holds.ranks.placeOf(song.id),
        )
    }
}
