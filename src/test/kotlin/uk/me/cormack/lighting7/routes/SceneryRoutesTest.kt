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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.SceneryChangeDto
import uk.me.cormack.lighting7.state.SceneryService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scenery on cues, stacks and Looks (stage-view plan session 8), end to end over a real desk: the
 * whole-list `PUT`s and their refusals, a GO through an act moving the tabs on their own clock, GO TO
 * landing the tracked set, a stack stopping, the Next GO preview, a Look pressed in the programmer,
 * the AI's `apply_cue` and `set_scenery`, and an element delete sweeping its changes.
 */
class SceneryRoutesTest : RouteIntegrationTest() {

    private suspend fun element(client: HttpClient, body: JsonObject): Pair<Int, String> {
        val resp = client.post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        val dto = resp.body<StageElementDto>()
        return dto.id to dto.uuid
    }

    private suspend fun tabsAndMoon(client: HttpClient): Pair<String, String> {
        val (_, tabs) = element(client, buildJsonObject {
            put("name", "House tabs"); put("kind", "DRAPE"); put("layer", "VENUE")
            put("widthM", 8.0); put("depthM", 0.1); put("heightM", 5.0)
            putJsonObject("params") { put("role", "TABS"); put("operation", "DRAW"); putJsonObject("states") { put("open", 1.0) } }
        })
        val (_, moon) = element(client, buildJsonObject {
            put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
            put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
            putJsonObject("params") { put("shape", "DISC"); put("flies", true); putJsonObject("states") { put("trimM", 7.0) } }
        })
        return tabs to moon
    }

    private suspend fun stack(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun cue(client: HttpClient, stackId: Int, name: String, fadeMs: Long? = null): Int =
        client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = name, cueStackId = stackId, cueNumber = name.removePrefix("Q"), fadeDurationMs = fadeMs))
        }.body<CueDetails>().id

    private fun change(elementUuid: String, state: JsonObject, transitionMs: Long? = null) = buildJsonObject {
        put("elementUuid", elementUuid)
        put("state", state)
        transitionMs?.let { put("transitionMs", it) }
    }

    private suspend fun putScenery(client: HttpClient, path: String, vararg items: JsonObject): HttpResponse =
        client.put("/api/rest/projects/$projectId/$path/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { putJsonArray("scenery") { items.forEach { add(it) } } })
        }

    private fun open(v: Double) = buildJsonObject { put("open", v) }
    private fun trim(v: Double) = buildJsonObject { put("trimM", v) }

    /** The live entry for [uuid], once every recompute queued so far has run (they run on the service's worker). */
    private fun entry(uuid: String): SceneryService.Entry {
        state.sceneryService.awaitIdle()
        return state.sceneryService.frame.value.entries.single { it.elementUuid.toString() == uuid }
    }

    /** The service recomputes off the programmer's layer flow on a coroutine: wait for it. */
    private fun awaitEntry(uuid: String, predicate: (SceneryService.Entry) -> Boolean) = runBlocking {
        repeat(100) {
            if (state.sceneryService.frame.value.entries.any { it.elementUuid.toString() == uuid && predicate(it) }) return@runBlocking
            delay(20)
        }
        error("scenery for $uuid never matched: ${state.sceneryService.frame.value}")
    }

    @Test
    fun `a write is checked against each element's kind, every problem at once, and touches nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")

        val refused = putScenery(
            client, "cues/$q1",
            change(tabs, trim(3.0)),
            change(moon, open(0.5)),
            change(moon, buildJsonObject { put("visible", false) }),
            change("00000000-0000-0000-0000-000000000000", open(0.0)),
            change(tabs, buildJsonObject { put("open", 2.0) }),
        )
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val text = refused.bodyAsText()
        for (expected in listOf(
            "trimM is a flown piece's",
            "open is a drawn drape's",
            "names an element this list already names",
            "names no stage element in this project",
            "open must be between 0.0 and 1.0",
        )) assertTrue(expected in text, "'$expected' in $text")

        val stackRefused = putScenery(client, "cue-stacks/$s", change(tabs, open(0.0), transitionMs = 4000))
        assertEquals(HttpStatusCode.BadRequest, stackRefused.status)
        assertTrue("a cue stack's scenery has no clock" in stackRefused.bodyAsText())

        val details = client.get("/api/rest/projects/$projectId/cues/$q1").body<CueDetails>()
        assertTrue(details.scenery.isEmpty(), "a refused write wrote nothing")

        val ok = putScenery(client, "cues/$q1", change(tabs, open(0.0), transitionMs = 4000))
        assertEquals(HttpStatusCode.OK, ok.status, ok.bodyAsText())
        val first = ok.body<List<SceneryChangeDto>>().single()
        assertEquals(4000L, first.transitionMs)
        // A re-save keeps the row, so an unchanged list exports byte-for-byte as before.
        val again = putScenery(client, "cues/$q1", change(tabs, open(0.0), transitionMs = 4000)).body<List<SceneryChangeDto>>().single()
        assertEquals(first.uuid, again.uuid)
    }

    @Test
    fun `GO through an act moves the tabs on their own clock, GO TO lands the tracked set, stop lets go`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")
        val q2 = cue(client, s, "Q2", fadeMs = 1500)
        val q3 = cue(client, s, "Q3")
        val q4 = cue(client, s, "Q4")
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q1", change(tabs, open(0.0))).status)
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q2", change(tabs, open(1.0), transitionMs = 4000)).status)
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q3", change(moon, trim(3.0), transitionMs = 6000)).status)

        client.post("/api/rest/projects/$projectId/cue-stacks/$s/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = q1))
        }
        assertEquals(0.0, entry(tabs).state.open, "Q1 closes the tabs")
        assertEquals(7.0, entry(moon).state.trimM, "the moon is out")

        // The Next GO preview: Q2's tabs, drawn over Q2's own 4 s, from where they are now.
        val preview = client.get("/api/rest/projects/$projectId/cue-stacks/$s/preview").body<PreviewCueResponse>()
        val previewTabs = preview.scenery.single { it.elementUuid == tabs }
        assertEquals(1.0, previewTabs.state["open"]!!.jsonPrimitive.content.toDouble())
        assertEquals(0.0, previewTabs.from["open"]!!.jsonPrimitive.content.toDouble())
        assertEquals(4000L, previewTabs.durationMs)
        assertEquals(0.0, entry(tabs).state.open, "a preview moves nothing")

        client.post("/api/rest/projects/$projectId/cue-stacks/$s/advance") {
            contentType(ContentType.Application.Json)
            setBody(AdvanceCueStackRequest(direction = "FORWARD"))
        }
        val drawing = entry(tabs)
        assertEquals(1.0 to 0.0, drawing.state.open to drawing.from.open)
        assertEquals(4000L, drawing.durationMs, "the tabs' own clock, not the cue's 1.5 s fade")

        client.post("/api/rest/projects/$projectId/cue-stacks/$s/go-to") {
            contentType(ContentType.Application.Json)
            setBody(GoToCueRequest(cueId = q4))
        }
        assertEquals(1.0, entry(tabs).state.open, "Q4 leaves the tabs where Q2 put them")
        assertEquals(3.0, entry(moon).state.trimM, "GO TO Q4 lands Q3's moon")
        assertEquals(0L, entry(moon).durationMs, "a change the GO'd cue did not make lands, not flies")

        client.post("/api/rest/projects/$projectId/cue-stacks/$s/deactivate")
        assertEquals(1.0, entry(tabs).state.open, "back to the tabs' base")
        assertEquals(7.0, entry(moon).state.trimM, "back to the moon's base")

        val card = client.get("/api/rest/projects/$projectId/cues/$q4").body<CueDetails>()
        val tracked = card.trackedScenery.associateBy { it.elementName }
        assertEquals("2", tracked.getValue("House tabs").fromCueLabel)
        assertEquals("3", tracked.getValue("Moon").fromCueLabel)
    }

    @Test
    fun `a Look pressed in the programmer flies its piece in over the stack, and out again`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val lookId = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "Night") })
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content.toInt()
        assertEquals(HttpStatusCode.OK, putScenery(client, "looks/$lookId", change(moon, trim(3.0))).status)
        val look = transaction(state.database) { DaoLook[lookId].let { LayerSource.look(it.id.value, it.uuid, it.name) } }

        val (layer, _) = state.show.programmerLayerStack.add(look)
        awaitEntry(moon) { it.state.trimM == 3.0 }
        state.show.programmerLayerStack.remove(layer.layerId)
        awaitEntry(moon) { it.state.trimM == 7.0 }
    }

    @Test
    fun `a Look that only carries scenery presses with no selection, and its pad's second press takes it off`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val lookId = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", "Night") })
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content.toInt()
        assertEquals(HttpStatusCode.OK, putScenery(client, "looks/$lookId", change(moon, trim(3.0))).status)

        suspend fun press(families: List<String>? = null) = client.post("/api/rest/projects/$projectId/looks/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("targets") {}
                families?.let { f -> putJsonArray("families") { f.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
            })
        }
        val on = press(families = listOf("COLOUR"))
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertTrue("applied" in on.bodyAsText())
        awaitEntry(moon) { it.state.trimM == 3.0 }
        val off = press()
        assertTrue("removed" in off.bodyAsText(), off.bodyAsText())
        awaitEntry(moon) { it.state.trimM == 7.0 }
    }

    @Test
    fun `the AI's apply_cue goes through the same hook, and set_scenery writes as the route does`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, _) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")
        val tools = AiTools(state)

        val set = tools.executeTool("set_scenery", buildJsonObject {
            put("cueId", q1)
            putJsonArray("scenery") { addJsonObject { put("element", "house tabs"); put("open", 0.0); put("transitionSeconds", 2.5) } }
        })
        assertTrue(set.success, set.description)
        val refused = tools.executeTool("set_scenery", buildJsonObject {
            put("stackId", s)
            putJsonArray("scenery") { addJsonObject { put("element", "Moon"); put("open", 0.0) } }
        })
        assertTrue(!refused.success && "open is a drawn drape's" in refused.description, refused.description)

        val applied = tools.executeTool("apply_cue", buildJsonObject { put("cueId", q1) })
        assertTrue(applied.success, applied.description)
        assertEquals(0.0, entry(tabs).state.open)
        assertEquals(2500L, entry(tabs).durationMs)

        tools.executeTool("stop_cue", buildJsonObject { put("cueId", q1) })
        assertEquals(1.0, entry(tabs).state.open, "a stopped applied cue lets its scenery go")
    }

    /**
     * Two GOs that land before the worker has read the first — a quick double press, an auto-follow
     * right behind a GO. The first run already sees the second cue live, so it is the run that starts
     * that cue's moves, on that cue's clocks; judged against the first cue, the moon would snap there
     * and the second run would find nothing left to move.
     */
    @Test
    fun `a second GO queued behind the first still flies on its own clock`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")
        val q2 = cue(client, s, "Q2")
        val q3 = cue(client, s, "Q3")
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q2", change(tabs, open(0.0), transitionMs = 4000)).status)
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q3", change(moon, trim(3.0), transitionMs = 6000)).status)
        client.post("/api/rest/projects/$projectId/cue-stacks/$s/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = q1))
        }
        assertEquals(7.0, entry(moon).state.trimM)

        val release = state.sceneryService.holdWorker()
        repeat(2) {
            client.post("/api/rest/projects/$projectId/cue-stacks/$s/advance") {
                contentType(ContentType.Application.Json)
                setBody(AdvanceCueStackRequest(direction = "FORWARD"))
            }
        }
        release()
        val flying = entry(moon)
        assertEquals(3.0 to 7.0, flying.state.trimM to flying.from.trimM)
        assertEquals(6000L, flying.durationMs, "Q3's moon flies on Q3's clock")
        assertEquals(0.0, entry(tabs).state.open, "Q2's tabs, overtaken, land")
    }

    /**
     * A hook fired from inside a transaction — a stack delete calls `deactivateStack` inside its own —
     * while another thread is reading for a preview. The pool is one connection, so a hook that
     * waited on a lock the reader held while the reader waited for that connection would stall both
     * until Hikari gave up. The hook only queues, and the reader holds no lock while it reads.
     */
    @Test
    fun `a hook fired inside a transaction does not wait on a preview reading beside it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, _) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q1", change(tabs, open(0.0))).status)

        val holding = java.util.concurrent.CountDownLatch(1)
        val readerStarted = java.util.concurrent.CountDownLatch(1)
        var hookMs = -1L
        val holder = Thread {
            transaction(state.database) {
                holding.countDown()
                readerStarted.await()
                Thread.sleep(200) // let the reader reach the pool
                val started = System.nanoTime()
                state.sceneryService.onStackStopped(s)
                hookMs = (System.nanoTime() - started) / 1_000_000
            }
        }
        holder.start()
        holding.await()
        val reader = Thread {
            readerStarted.countDown()
            state.sceneryService.preview(s, q1)
        }
        reader.start()
        holder.join(20_000)
        reader.join(20_000)
        assertTrue(hookMs in 0..2000, "the hook returned in ${hookMs} ms")
        assertEquals(1.0, entry(tabs).state.open, "and the recompute it queued still ran")
    }

    @Test
    fun `deleting an element sweeps its scenery`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val s = stack(client, "Act 1")
        val q1 = cue(client, s, "Q1")
        assertEquals(HttpStatusCode.OK, putScenery(client, "cues/$q1", change(moon, trim(3.0))).status)
        assertEquals(HttpStatusCode.OK, putScenery(client, "cue-stacks/$s", change(moon, buildJsonObject { put("visible", false) })).status)

        val moonId = client.get("/api/rest/projects/$projectId/stage-elements").body<List<StageElementDto>>().single { it.uuid == moon }.id
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/stage-elements/$moonId").status)

        assertTrue(client.get("/api/rest/projects/$projectId/cues/$q1").body<CueDetails>().scenery.isEmpty())
        val stackDto = client.get("/api/rest/projects/$projectId/cue-stacks/$s").body<CueStackDetails>()
        assertTrue(stackDto.scenery.isEmpty())
    }
}
