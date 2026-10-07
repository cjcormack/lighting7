package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueLayer
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerClearSceneryInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerErrorOutMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSceneryStateOutMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetSceneryInMessage
import uk.me.cormack.lighting7.plugins.SceneryStateOutMessage
import uk.me.cormack.lighting7.state.SceneryService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The programmer's scenery on a real desk (scenery-programmer plan session 1): `move_scenery` holding
 * a piece above a pressed Look and above a cue, each entry's `source`, Blind staging a move and
 * leaving Blind landing it, Clear flying it home on its fade, a project switch dropping it, the
 * pieces' `travelS`, and the `programmer.*Scenery` frames over a socket.
 */
class ProgrammerSceneryRoutesTest : RouteIntegrationTest() {

    private suspend fun element(client: HttpClient, body: JsonObject): String {
        val resp = client.post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<StageElementDto>().uuid
    }

    /** House tabs (drawn at base, 4 s closed to drawn) and the moon (in at 3 m, out at 7 m, 8 s). */
    private suspend fun tabsAndMoon(client: HttpClient): Pair<String, String> {
        val tabs = element(client, buildJsonObject {
            put("name", "House tabs"); put("kind", "DRAPE"); put("layer", "VENUE")
            put("widthM", 8.0); put("depthM", 0.1); put("heightM", 5.0)
            putJsonObject("params") {
                put("role", "TABS"); put("operation", "DRAW"); put("travelS", 4.0)
                putJsonObject("states") { put("open", 1.0) }
            }
        })
        val moon = element(client, buildJsonObject {
            put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
            put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
            putJsonObject("params") {
                put("shape", "DISC"); put("flies", true); put("travelS", 8.0)
                putJsonObject("states") { put("trimM", 7.0) }
            }
        })
        return tabs to moon
    }

    private suspend fun look(client: HttpClient, name: String): Int =
        client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("name", name) })
        }.body<JsonObject>()["id"]!!.jsonPrimitive.content.toInt()

    private suspend fun putScenery(client: HttpClient, path: String, vararg items: Pair<String, JsonObject>) {
        val resp = client.put("/api/rest/projects/$projectId/$path/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") {
                    items.forEach { (uuid, state) -> add(buildJsonObject { put("elementUuid", uuid); put("state", state) }) }
                }
            })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private fun press(lookId: Int) {
        val look = transaction(state.database) { DaoLook[lookId].let { LayerSource.look(it.id.value, it.uuid, it.name) } }
        state.show.programmerLayerStack.add(look)
    }

    private fun setBlind(blind: Boolean) {
        // What `programmer.setBlind` does on the socket.
        state.show.fxEngine.programmer.setBlind(blind)
        state.sceneryService.onBlindChanged()
    }

    private fun open(v: Double) = buildJsonObject { put("open", v) }
    private fun trim(v: Double) = buildJsonObject { put("trimM", v) }
    private fun visible(v: Boolean) = buildJsonObject { put("visible", v) }

    /** The frame once it matches: the overlay and the layers reach the service through flows, on a coroutine. */
    private fun awaitFrame(predicate: (SceneryService.Frame) -> Boolean): SceneryService.Frame = runBlocking {
        repeat(150) {
            state.sceneryService.awaitIdle()
            val frame = state.sceneryService.frame.value
            if (predicate(frame)) return@runBlocking frame
            delay(20)
        }
        error("the scenery frame never matched: ${state.sceneryService.frame.value}")
    }

    private fun awaitEntry(uuid: String, predicate: (SceneryService.Entry) -> Boolean): SceneryService.Entry =
        awaitFrame { f -> f.entries.any { it.elementUuid.toString() == uuid && predicate(it) } }
            .entries.single { it.elementUuid.toString() == uuid }

    @Test
    fun `move_scenery holds a piece above a pressed Look at its travel, and release flies it home`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val night = look(client, "Night")
        putScenery(client, "looks/$night", moon to trim(5.0))
        press(night)
        val pressed = awaitEntry(moon) { it.state.trimM == 5.0 }
        assertEquals(SceneryService.Holder("programmerLook", lookId = night, name = "Night"), pressed.source)
        assertEquals(4000L, pressed.durationMs, "7 m to 5 m is half the moon's 4 m travel: half its 8 s")

        val tools = AiTools(state)
        val flown = tools.executeTool("move_scenery", buildJsonObject { put("element", "moon"); put("trimM", 3.0) })
        assertTrue(flown.success, flown.description)
        val held = awaitEntry(moon) { it.state.trimM == 3.0 }
        assertEquals(SceneryService.Holder("programmer"), held.source)
        assertEquals(4000L, held.durationMs, "5 m to 3 m, on the travel: no fade was given")

        val faded = tools.executeTool("move_scenery", buildJsonObject { put("element", "Moon"); put("trimM", 4.0); put("fadeSeconds", 1.5) })
        assertTrue(faded.success, faded.description)
        assertEquals(1500L, awaitEntry(moon) { it.state.trimM == 4.0 }.durationMs, "the fade overrides the travel")

        val current = tools.executeTool("get_current_state", buildJsonObject { putJsonArray("include") { add(kotlinx.serialization.json.JsonPrimitive("programmer")) } })
        val scenery = kotlinx.serialization.json.Json.parseToJsonElement(current.result).jsonObject["programmer"]!!.jsonObject["scenery"]!!.jsonArray
        assertEquals("Moon", scenery.single().jsonObject["element"]!!.jsonPrimitive.content)
        assertEquals(4.0, scenery.single().jsonObject["trimM"]!!.jsonPrimitive.content.toDouble())

        for ((input, expected) in listOf(
            buildJsonObject { put("element", "Moon"); put("open", 0.5) } to "open is a drawn drape's",
            buildJsonObject { put("element", "Sun"); put("visible", true) } to "no stage element named 'Sun' (elements: House tabs, Moon)",
            buildJsonObject { put("element", "Moon") } to "give visible, open or trimM",
            buildJsonObject { put("element", "Moon"); put("trimM", 3.0); put("release", true) } to "give states or release, not both",
            buildJsonObject { put("element", "Moon"); put("trimM", 3.0); put("fadeSeconds", 900) } to "fadeSeconds must be between 0 and 600",
            buildJsonObject { put("element", "Moon"); put("visible", true); put("trim", 3.0) } to "unknown field 'trim' (known: element, fadeSeconds, open, release, trimM, visible)",
        )) {
            val refused = tools.executeTool("move_scenery", input)
            assertTrue(!refused.success && expected in refused.description, "'$expected' in ${refused.description}")
        }
        assertEquals(4.0, state.programmerScenery.flow.value.elements.values.single().state.trimM, "a refusal holds nothing new")

        val released = tools.executeTool("move_scenery", buildJsonObject { put("element", moon); put("release", true) })
        assertTrue(released.success, released.description)
        val home = awaitEntry(moon) { it.state.trimM == 5.0 }
        assertEquals("programmerLook", home.source.kind, "back to the Look under it")
        assertEquals(2000L, home.durationMs, "4 m to 5 m, a quarter of the travel")
        assertTrue(state.programmerScenery.flow.value.elements.isEmpty())
    }

    @Test
    fun `the programmer sits above a cue, and every entry names what holds it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val sofa = element(client, buildJsonObject {
            put("name", "Sofa"); put("kind", "OBJECT"); put("layer", "SET"); put("widthM", 2.0); put("depthM", 0.9); put("heightM", 0.8)
        })
        val legs = element(client, buildJsonObject {
            put("name", "Legs"); put("kind", "DRAPE"); put("layer", "VENUE"); put("widthM", 1.0); put("depthM", 0.1); put("heightM", 5.0)
            putJsonObject("params") { put("role", "LEG") }
        })
        val cloth = element(client, buildJsonObject {
            put("name", "Cloth"); put("kind", "FLAT"); put("layer", "SET"); put("widthM", 4.0); put("depthM", 0.1); put("heightM", 3.0)
        })
        val stackId = client.post("/api/rest/projects/$projectId/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = "Act 1"))
        }.body<CueStackDetails>().id
        val q1 = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Q1", cueStackId = stackId, cueNumber = "1"))
        }.body<CueDetails>().id
        putScenery(client, "cue-stacks/$stackId", sofa to visible(false))
        putScenery(client, "cues/$q1", moon to trim(3.0))
        val night = look(client, "Night")
        putScenery(client, "looks/$night", tabs to open(0.25))
        val pad = look(client, "Pad")
        putScenery(client, "looks/$pad", legs to visible(false))
        val unused = look(client, "Unused")
        putScenery(client, "looks/$unused", cloth to visible(false))
        transaction(state.database) {
            DaoCueLayer.new {
                cue = DaoCue[q1]; look = DaoLook[night]
                targets = emptyList<CueTargetDto>()
            }
        }
        state.sceneryService.awaitIdle()

        client.post("/api/rest/projects/$projectId/cue-stacks/$stackId/activate") {
            contentType(ContentType.Application.Json)
            setBody(ActivateCueStackRequest(cueId = q1))
        }
        press(pad)
        val frame = awaitFrame { f -> f.entries.any { it.elementUuid.toString() == legs && it.source.kind == "programmerLook" } }
        val by = frame.entries.associate { it.elementName to it.source }
        assertEquals(SceneryService.Holder("base"), by["Cloth"])
        assertEquals(SceneryService.Holder("set", stackId = stackId, name = "Act 1"), by["Sofa"])
        assertEquals(SceneryService.Holder("cue", stackId = stackId, cueId = q1, label = "1"), by["Moon"])
        assertEquals(SceneryService.Holder("cueLook", stackId = stackId, lookId = night, name = "Night"), by["House tabs"])
        assertEquals(SceneryService.Holder("programmerLook", lookId = pad, name = "Pad"), by["Legs"])

        assertEquals(emptyList(), state.programmerScenery.set(java.util.UUID.fromString(moon), trim(6.0), null))
        val held = awaitEntry(moon) { it.state.trimM == 6.0 }
        assertEquals(SceneryService.Holder("programmer"), held.source, "the programmer over Q1's own change")
        assertEquals(6000L, held.durationMs, "3 m to 6 m is three quarters of 8 s")
    }

    @Test
    fun `Blind stages a move without drawing it, and leaving Blind lands it on its travel`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        // A Look naming the moon puts it under the show's control, so live carries it at its base.
        putScenery(client, "looks/${look(client, "Night")}", moon to trim(5.0))
        awaitEntry(moon) { it.state.trimM == 7.0 }

        setBlind(true)
        assertNull(awaitFrame { true }.staged, "blind holding nothing stages nothing")
        assertEquals(emptyList(), state.programmerScenery.set(java.util.UUID.fromString(moon), trim(7.0), null))
        assertNull(awaitFrame { f -> f.entries.any { it.elementUuid.toString() == moon } }.staged, "a held state equal to live stages nothing")

        val tools = AiTools(state)
        val moved = tools.executeTool("move_scenery", buildJsonObject { put("element", "Moon"); put("trimM", 3.0) })
        assertTrue(moved.success && "staged" in moved.description, moved.description)
        val staged = awaitFrame { it.staged != null }
        assertEquals(7.0, staged.entries.single { it.elementUuid.toString() == moon }.state.trimM, "live is untouched")
        val stagedMoon = staged.staged!!.single()
        assertEquals(moon, stagedMoon.elementUuid.toString())
        assertEquals(3.0 to 7.0, stagedMoon.state.trimM to stagedMoon.from.trimM)
        assertEquals(8000L, stagedMoon.durationMs)
        assertTrue(staged.staged.none { it.elementUuid.toString() == tabs })

        setBlind(false)
        val landed = awaitFrame { f -> f.staged == null && f.entries.any { it.elementUuid.toString() == moon && it.state.trimM == 3.0 } }
        val entry = landed.entries.single { it.elementUuid.toString() == moon }
        assertEquals(8000L, entry.durationMs, "out to in: the full travel")
        assertEquals(SceneryService.Holder("programmer"), entry.source)
    }

    @Test
    fun `Clear flies held scenery home on the Clear fade, and a piece only the programmer held leaves the frame`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, _) = tabsAndMoon(client)
        assertEquals(emptyList(), state.programmerScenery.set(java.util.UUID.fromString(tabs), open(0.0), fadeMs = 500))
        val closing = awaitEntry(tabs) { it.state.open == 0.0 }
        assertEquals(500L, closing.durationMs)

        clearProgrammerCompletely(state, fadeMs = 3000)
        assertTrue(state.programmerScenery.flow.value.elements.isEmpty())
        val opening = awaitEntry(tabs) { it.state.open == 1.0 }
        assertEquals(3000L, opening.durationMs, "the Clear's fade, not the tabs' 4 s")
        assertEquals(SceneryService.Holder("base"), opening.source)

        // Landed back on its base with nothing naming it, the next recompute lets it go.
        runBlocking { delay(3100) }
        state.sceneryService.recompute()
        awaitFrame { f -> f.entries.none { it.elementUuid.toString() == tabs } }
    }

    @Test
    fun `a project switch drops the programmer's scenery`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        assertEquals(emptyList(), state.programmerScenery.set(java.util.UUID.fromString(moon), trim(3.0), null))
        val other = seedMinimalProject(state, projectName = "Second project", universe = 1)
        state.projectManager.switchProject(other)
        runBlocking {
            repeat(150) {
                if (state.programmerScenery.flow.value.projectId == other) return@runBlocking
                delay(20)
            }
        }
        assertEquals(other, state.programmerScenery.flow.value.projectId)
        assertTrue(state.programmerScenery.flow.value.elements.isEmpty())
        val refused = AiTools(state).executeTool("move_scenery", buildJsonObject { put("element", "Moon"); put("trimM", 3.0) })
        assertTrue(!refused.success && "no stage element named 'Moon'" in refused.description, refused.description)
    }

    @Test
    fun `the programmer's scenery frames over a socket — snapshot, a write, a refusal, a release`() = testApplication {
        mountTestApp(state)
        val (_, moon) = tabsAndMoon(jsonClient())
        val client = createWsClient()
        client.webSocket("/api") {
            val snapshot = awaitOfType<ProgrammerSceneryStateOutMessage>()
            assertEquals(projectId, snapshot.projectId)
            assertEquals(emptyList(), snapshot.elements)

            sendSerialized<InMessage>(ProgrammerSetSceneryInMessage(moon, trim(3.0), fadeMs = 1000))
            val held = awaitOfType<ProgrammerSceneryStateOutMessage> { it.elements.isNotEmpty() }
            assertEquals(moon, held.elements.single().elementUuid)
            assertEquals(3.0, held.elements.single().state["trimM"]!!.jsonPrimitive.content.toDouble())
            val drawn = awaitOfType<SceneryStateOutMessage> { m -> m.elements.any { it.elementUuid == moon && it.source.kind == "programmer" } }
            assertEquals(1000L, drawn.elements.single { it.elementUuid == moon }.durationMs)
            assertNull(drawn.staged)

            sendSerialized<InMessage>(ProgrammerSetSceneryInMessage(moon, open(0.5)))
            val refused = awaitOfType<ProgrammerErrorOutMessage>()
            assertTrue("open is a drawn drape's" in refused.message && "'Moon'" in refused.message, refused.message)
            sendSerialized<InMessage>(ProgrammerClearSceneryInMessage(elementUuid = "not-a-uuid"))
            assertTrue("elementUuid must be a uuid" in awaitOfType<ProgrammerErrorOutMessage>().message)

            sendSerialized<InMessage>(ProgrammerClearSceneryInMessage())
            assertEquals(emptyList(), awaitOfType<ProgrammerSceneryStateOutMessage> { it.elements.isEmpty() }.elements)
        }
    }
}
