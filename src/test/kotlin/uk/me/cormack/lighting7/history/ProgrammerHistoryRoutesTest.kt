package uk.me.cormack.lighting7.history

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.sendSerialized
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.mcp.McpProtocol
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.plugins.ProgrammerKeyStackInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerKeyStackOutMessage
import uk.me.cormack.lighting7.plugins.InMessage
import uk.me.cormack.lighting7.plugins.ProgrammerClearAllInMessage
import uk.me.cormack.lighting7.plugins.RemoveFxInMessage
import uk.me.cormack.lighting7.plugins.UpdateChannelInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetColourInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetInMessage
import uk.me.cormack.lighting7.plugins.ProgrammerSetSceneryInMessage
import uk.me.cormack.lighting7.routes.CreateLookRequest
import uk.me.cormack.lighting7.routes.HistoryRequest
import uk.me.cormack.lighting7.routes.LookDetails
import uk.me.cormack.lighting7.routes.StageElementDto
import uk.me.cormack.lighting7.routes.ToggleLookRequest
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.awaitOfType
import uk.me.cormack.lighting7.testsupport.createWsClient
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedMinimalProject
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The programmer's history through its doors (undo plan session 1): a gesture from the socket, a REST
 * route, Claude or a surface is one step; undo puts the store, layers, Local FX, Locate and scenery back
 * as they were, and redo takes them forward again.
 */
class ProgrammerHistoryRoutesTest : RouteIntegrationTest() {

    private val store get() = state.show.programmerStore
    private val engine get() = state.show.fxEngine
    private val history get() = state.show.programmerHistory
    private val controller get() = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController

    private suspend fun HttpClient.undo(entryId: String? = null, fadeMs: Long = 0): HttpResponse =
        post("/api/rest/projects/$projectId/history/undo") {
            contentType(ContentType.Application.Json)
            setBody(HistoryRequest(entryId = entryId, programmerOnly = true, fadeMs = fadeMs))
        }

    private suspend fun HttpClient.redo(): HttpResponse =
        post("/api/rest/projects/$projectId/history/redo") {
            contentType(ContentType.Application.Json)
            setBody(HistoryRequest(programmerOnly = true))
        }

    private suspend fun HttpClient.ok(response: suspend HttpClient.() -> HttpResponse) {
        val answer = response()
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
    }

    /**
     * Send [message], then a key-stack read, whose answer — matched by its id, unlike the connect
     * snapshot — comes once the socket has handled the gesture, and check the history then holds [steps].
     */
    private suspend fun DefaultClientWebSocketSession.gesture(message: InMessage, steps: Int) {
        sendSerialized(message)
        val barrier = UUID.randomUUID().toString()
        sendSerialized<InMessage>(ProgrammerKeyStackInMessage("fixture", "hex-1", "dimmer", requestId = barrier))
        awaitOfType<ProgrammerKeyStackOutMessage> { it.requestId == barrier }
        assertEquals(steps, history.frame().undo.size, "steps after $message")
    }

    private fun set(key: String, attribute: String, value: String) = ProgrammerSetInMessage("fixture", key, attribute, value)

    /** What a store holds, slot by slot, without the slots' ages and sequence numbers. */
    private fun ProgrammerStore.StoreSnapshot.shape() = Triple(
        attributes.mapValues { (_, byAttribute) ->
            byAttribute.mapValues { (_, slots) -> slots.map { listOf(it.owner.id, it.value.resolved, it.touched, it.sourceGroup) } }
        },
        channels.mapValues { (_, slots) -> slots.map { listOf(it.owner.id, it.value.resolved) } },
        listOf(layers, includedLayerSnapshot, lastIncludedTarget),
    )

    private suspend fun HttpClient.createLook(name: String): LookDetails =
        post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(
                CreateLookRequest(
                    name = name,
                    rows = listOf(LookRowDto("fixture", "hex-1", "rgbColour", "#ff8000")),
                    fx = listOf(
                        LookFxDto(
                            targetType = "fixture", targetKey = "hex-1", fxType = "Wave", family = "intensity",
                            attribute = "dimmer", cycle = 0.5, blendMode = "OVERRIDE", order = "NORMAL",
                        ),
                    ),
                ),
            )
        }.body()

    private suspend fun HttpClient.moon(): String =
        post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
                put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
                putJsonObject("params") {
                    put("shape", "DISC"); put("flies", true)
                    putJsonObject("states") { put("trimM", 7.0) }
                }
            })
        }.body<StageElementDto>().uuid

    @Test
    fun `a socket gesture is one step, undo puts the store back, and redo takes it forward`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val empty = store.snapshot().shape()
        createWsClient().webSocket("/api") {
            gesture(set("hex-1", "dimmer", "200"), steps = 1)
            gesture(ProgrammerSetColourInMessage("fixture", "hex-1", r = 255u, g = 0u, b = 0u), steps = 2)
        }
        val steps = history.frame().undo
        assertEquals(listOf("hex-1 colour", "hex-1 dimmer"), steps.map { it.label })
        assertEquals(HistorySource.DESK, steps[0].source, "a desk with no accounts writes as the desk")
        val afterBoth = store.snapshot().shape()

        client.ok { undo() }
        assertEquals(listOf("dimmer"), store.entries().map { it.attribute })
        client.ok { undo() }
        assertEquals(empty, store.snapshot().shape())
        assertEquals(0, controller.getEffectiveValue(1).toInt(), "the dimmer released on stage")
        val refused = client.undo()
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("HISTORY_EMPTY" in refused.bodyAsText())

        client.ok { redo() }
        client.ok { redo() }
        assertEquals(afterBoth, store.snapshot().shape())
        assertEquals(200, controller.getEffectiveValue(1).toInt())
        assertEquals(2, history.frame().undo.size, "a restore takes no step of its own")
    }

    @Test
    fun `a fader drag is one step`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        createWsClient().webSocket("/api") {
            for (level in listOf(10, 40, 90, 160, 220)) sendSerialized<InMessage>(set("hex-1", "dimmer", "$level"))
            gesture(set("hex-1", "uv", "80"), steps = 2)
        }
        assertEquals(listOf("hex-1 uv", "hex-1 dimmer"), history.frame().undo.map { it.label })

        val client = jsonClient()
        client.ok { undo() }
        assertEquals(220, controller.getEffectiveValue(1).toInt(), "the drag's last value stays")
        client.ok { undo() }
        assertTrue(store.entries().isEmpty(), "and the whole drag goes in one undo")
    }

    @Test
    fun `undoing a Clear brings back values, layers, Local FX at their phase, Locate and scenery`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 20)
        val client = jsonClient()
        val look = client.createLook("Amber pulse")
        val moon = client.moon()

        client.ok {
            post("/api/rest/projects/$projectId/looks/${look.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleLookRequest())
            }
        }
        client.ok {
            post("/api/rest/projects/$projectId/programmer/fx") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("fxType", "Wave"); put("attribute", "uv")
                    put("targets", Json.parseToJsonElement("""[{"type":"fixture","key":"hex-1"}]"""))
                })
            }
        }
        assertEquals(HttpStatusCode.OK, LocateTestSupport.toggleLocate(client, "fixture", "hex-2").status)
        createWsClient().webSocket("/api") {
            gesture(set("hex-1", "white", "120"), steps = 4)
            gesture(ProgrammerSetSceneryInMessage(moon, buildJsonObject { put("trimM", 2.0) }), steps = 5)
        }
        assertEquals(
            listOf("Move scenery", "hex-1 white", "Locate", "Add FX", "Press Look"),
            history.frame().undo.map { it.label },
        )

        val storeBefore = store.snapshot().shape()
        val local = engine.getActiveFx().single { it.isLocalFx }
        val layerFx = engine.getActiveFx().count { it.programmerLayerFxKey != null }
        val located = state.show.locateManager.activeTargets.value
        val scenery = state.programmerScenery.flow.value.elements.mapValues { it.value.state }
        assertEquals(1, layerFx)

        createWsClient().webSocket("/api") { gesture(ProgrammerClearAllInMessage(fadeMs = 0), steps = 6) }
        assertTrue(store.entries().isEmpty() && store.layers.isEmpty())
        assertTrue(engine.getActiveFx().none { it.isLocalFx || it.programmerLayerFxKey != null })

        client.ok { undo() }
        assertEquals(storeBefore, store.snapshot().shape())
        val back = assertNotNull(engine.getActiveFx().singleOrNull { it.isLocalFx }, "the Local FX came back")
        assertEquals(local.id, back.id, "under its own id")
        assertEquals(local.startedAtMs, back.startedAtMs, "at its phase")
        assertEquals(local.dynamics, back.dynamics)
        assertEquals(1, engine.getActiveFx().count { it.programmerLayerFxKey != null }, "the layer's FX respawned")
        assertEquals(located, state.show.locateManager.activeTargets.value)
        assertEquals(scenery, state.programmerScenery.flow.value.elements.mapValues { it.value.state })
        assertEquals(5, history.frame().undo.size)
        assertEquals("Clear", history.frame().redo.single().label)
    }

    @Test
    fun `a REST door's FX comes off on undo and back under its id on redo`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        client.ok {
            post("/api/rest/projects/$projectId/programmer/fx") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("fxType", "Wave"); put("attribute", "dimmer")
                    put("targets", Json.parseToJsonElement("""[{"type":"fixture","key":"hex-1"}]"""))
                })
            }
        }
        val fx = engine.getActiveFx().single { it.isLocalFx }
        assertEquals("Add FX", history.frame().undo.single().label)

        client.ok { undo() }
        assertTrue(engine.getActiveFx().none { it.isLocalFx })
        client.ok { redo() }
        assertEquals(fx.id, engine.getActiveFx().single { it.isLocalFx }.id)
    }

    @Test
    fun `a Claude tool call is one step of Claude's`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val claude = AuthenticatedUser(1, UUID.randomUUID(), "chris", "Chris", UserRole.ADMIN, "cloud-grant:7")
        val answer = runBlocking {
            McpProtocol(state).handle(
                Json.parseToJsonElement(
                    """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"set_programmer","arguments":
                       {"targets":[{"type":"fixture","key":"hex-1"}],"values":[{"attribute":"dimmer","value":"pct:50"}]}}}""",
                ),
                claude,
            )
        }
        assertEquals("false", answer!!["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content, "$answer")
        val step = history.frame().undo.single()
        assertEquals("Claude: set_programmer", step.label)
        assertEquals(HistorySource.mcp("cloud-grant:7"), step.source)

        jsonClient().ok { undo() }
        assertTrue(store.entries().isEmpty())
    }

    @Test
    fun `a reload keeps the history and a project switch drops it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        createWsClient().webSocket("/api") { gesture(set("hex-1", "dimmer", "150"), steps = 1) }
        val kept = history

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }
        assertSame(kept, history, "the reload carried it")
        assertEquals(1, history.frame().undo.size, "and took no step of its own")
        jsonClient().ok { undo() }
        assertTrue(store.entries().isEmpty(), "and its step still undoes the carried store")

        createWsClient().webSocket("/api") { gesture(set("hex-1", "dimmer", "90"), steps = 1) }
        val other = seedMinimalProject(state, projectName = "Other")
        runBlocking {
            state.projectManager.switchProject(other)
            state.projectManager.switchProject(projectId)
        }
        assertTrue(history.frame().undo.isEmpty(), "a switch starts an empty history")
        assertEquals(projectId, state.programmerHistoryFrame.value.projectId)
        assertTrue(state.programmerHistoryFrame.value.undo.isEmpty())
    }

    @Test
    fun `under Blind an undo is staged`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        createWsClient().webSocket("/api") { gesture(set("hex-1", "dimmer", "200"), steps = 1) }
        engine.programmer.setBlind(true)
        assertEquals(0, controller.getEffectiveValue(1).toInt())
        createWsClient().webSocket("/api") { gesture(ProgrammerClearAllInMessage(), steps = 2) }

        jsonClient().ok { undo() }
        assertEquals("dimmer", store.entries().single().attribute, "the value is back in the programmer")
        assertEquals(0, controller.getEffectiveValue(1).toInt(), "but not on stage")
        engine.programmer.setBlind(false)
        assertEquals(200, controller.getEffectiveValue(1).toInt())
    }

    @Test
    fun `a new step drops redo, and undo through a step goes back to before it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        createWsClient().webSocket("/api") {
            gesture(set("hex-1", "dimmer", "100"), steps = 1)
            gesture(set("hex-1", "uv", "100"), steps = 2)
            gesture(set("hex-1", "white", "100"), steps = 3)
        }
        val oldest = history.frame().undo.last()

        val answer = client.undo(entryId = oldest.id)
        assertEquals(HttpStatusCode.OK, answer.status, answer.bodyAsText())
        assertTrue(store.entries().isEmpty(), "every step down to it went")
        assertEquals(3, history.frame().redo.size)
        assertEquals("hex-1 dimmer", history.frame().redo.first().label, "the oldest is redone first")

        client.ok { redo() }
        assertEquals(listOf("dimmer"), store.entries().map { it.attribute })
        createWsClient().webSocket("/api") { gesture(set("hex-1", "strobe", "10"), steps = 2) }
        assertTrue(history.frame().redo.isEmpty(), "a new step drops what was undone")

        assertEquals(HttpStatusCode.NotFound, client.undo(entryId = "p-nope").status)
    }

    @Test
    fun `a write with no origin takes no step`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        uk.me.cormack.lighting7.plugins.ProgrammerHandler.setTyped(
            state, TargetRef.Fixture("hex-1"), "dimmer",
            uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue.Slider(50u), fadeMs = 0,
        )
        assertTrue(history.frame().undo.isEmpty())
    }

    @Test
    fun `a Clear with nothing to clear takes no step, and a channel drag is one step`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        createWsClient().webSocket("/api") {
            gesture(ProgrammerClearAllInMessage(), steps = 0)
            for (level in listOf(10, 60, 120, 200)) sendSerialized<InMessage>(UpdateChannelInMessage(0, 300, level.toUByte(), fadeTime = 0))
            gesture(set("hex-1", "uv", "90"), steps = 2)
        }
        assertEquals(listOf("hex-1 uv", "Channel 0/300"), history.frame().undo.map { it.label })
    }

    @Test
    fun `after a reload an undo brings a stopped Local FX back beside the carried one, under an id of its own`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        suspend fun addFx(attribute: String) = client.ok {
            post("/api/rest/projects/$projectId/programmer/fx") {
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("fxType", "Wave"); put("attribute", attribute)
                    put("targets", Json.parseToJsonElement("""[{"type":"fixture","key":"hex-1"}]"""))
                })
            }
        }
        addFx("dimmer")
        addFx("uv")
        val carried = engine.getActiveFx().single { it.isLocalFx && it.target.attribute == "dimmer" }
        val stopped = engine.getActiveFx().single { it.isLocalFx && it.target.attribute == "uv" }
        createWsClient().webSocket("/api") { gesture(RemoveFxInMessage(stopped.id), steps = 3) }

        runBlocking { state.projectManager.switchProject(projectId, reload = true) }
        client.ok { undo() }
        val local = engine.getActiveFx().filter { it.isLocalFx }
        assertEquals(setOf("dimmer", "uv"), local.map { it.target.attribute }.toSet())
        assertSame(carried, local.single { it.target.attribute == "dimmer" }, "the carried FX is left as it runs")
        assertEquals(2, local.map { it.id }.toSet().size)

        addFx("strobe")
        assertTrue(engine.getActiveFx().any { it.isLocalFx && it.target.attribute == "uv" }, "and no later add takes its id")
    }

    @Test
    fun `undoing a Look's delete does not bring back a layer naming it`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()
        val look = client.createLook("Doomed")
        client.ok {
            post("/api/rest/projects/$projectId/looks/${look.id}/toggle") {
                contentType(ContentType.Application.Json)
                setBody(ToggleLookRequest())
            }
        }
        assertEquals(1, store.layers.size)
        val deleted = client.delete("/api/rest/projects/$projectId/looks/${look.id}?force=true")
        assertTrue(deleted.status.isSuccess(), deleted.bodyAsText())
        assertTrue(store.layers.isEmpty())

        client.ok { undo() }
        assertTrue(store.layers.isEmpty(), "a layer naming a deleted Look names nothing")
    }

    @Test
    fun `storing a locate re-asserts a located head without a step`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac-1", 1)
        val client = jsonClient()
        assertEquals(HttpStatusCode.OK, LocateTestSupport.toggleLocate(client, "fixture", "mac-1").status)
        assertEquals(1, history.frame().undo.size)
        client.ok {
            post("/api/rest/projects/$projectId/locate/store") {
                contentType(ContentType.Application.Json)
                setBody(Json.parseToJsonElement("""{"targets":[{"type":"fixture","key":"mac-1"}]}"""))
            }
        }
        assertEquals(1, history.frame().undo.size)
    }
}
