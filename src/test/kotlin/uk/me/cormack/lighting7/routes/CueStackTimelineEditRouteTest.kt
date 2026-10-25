package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.CueLayerDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoCueAdHocFxEntity
import uk.me.cormack.lighting7.models.DaoCueAdHocFx
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.models.SceneryChangeDto
import uk.me.cormack.lighting7.models.PaletteRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `POST /cue-stacks/{id}/timeline/edit` (stack-timeline plan §3.6): the Timeline's every gesture as
 * one batch of ops over the stack's stored cues — one transaction, all or nothing, sending nothing to
 * the stage — and `restore`, the Undo that puts a cue back exactly as an answer's `before` holds it.
 */
class CueStackTimelineEditRouteTest : RouteIntegrationTest() {

    private val base get() = "/api/rest/projects/$projectId"

    private fun seedRig() {
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        LocateTestSupport.seedGroup(state, projectId, "wash", "hex-1", "hex-2")
        state.show.fixtures.patchListChanged()
    }

    private suspend fun HttpClient.palette(name: String, value: String = "#8f7bff", attribute: String = "rgbColour"): Int =
        post("$base/palettes") {
            contentType(ContentType.Application.Json)
            setBody(PaletteInput(name = name, rows = listOf(PaletteRowDto(DEFERRED_TARGET_TYPE, "", attribute, value))))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<PaletteDto>().id

    private suspend fun HttpClient.look(name: String): Int =
        post("$base/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = name, rows = listOf(LookRowDto("fixture", "hex-1", "dimmer", "200"))))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<LookDetails>().id

    private suspend fun HttpClient.stack(name: String): Int =
        post("$base/cue-stacks") {
            contentType(ContentType.Application.Json)
            setBody(NewCueStack(name = name))
        }.body<CueStackDetails>().id

    private suspend fun HttpClient.cue(
        stackId: Int?,
        name: String,
        layers: List<CueLayerDto> = emptyList(),
        effects: List<CueAdHocFxDto> = emptyList(),
    ): Int = post("$base/cues") {
        contentType(ContentType.Application.Json)
        setBody(NewCue(name = name, cueStackId = stackId, layers = layers, adHocFx = effects))
    }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }.body<CueDetails>().id

    private suspend fun HttpClient.drape(name: String): String =
        post("$base/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("name", name); put("kind", "DRAPE"); put("layer", "VENUE")
                put("widthM", 8.0); put("depthM", 0.1); put("heightM", 5.0)
                putJsonObject("params") { put("role", "TABS"); put("operation", "DRAW"); putJsonObject("states") { put("open", 1.0) } }
            })
        }.body<StageElementDto>().uuid

    private suspend fun HttpClient.editRaw(stackId: Int, ops: List<JsonObject>, dryRun: Boolean = false): HttpResponse =
        post("$base/cue-stacks/$stackId/timeline/edit") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("ops", JsonArray(ops))
                put("dryRun", dryRun)
            })
        }

    private suspend fun HttpClient.edit(stackId: Int, vararg ops: JsonObject, dryRun: Boolean = false): TimelineEditResponse {
        val resp = editRaw(stackId, ops.toList(), dryRun)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun source(kind: String, id: Int) = buildJsonObject { put("kind", kind); put("id", id) }
    private fun target(key: String, type: String = "fixture") = buildJsonObject { put("type", type); put("key", key) }

    private fun layerOn(cueId: Int, kind: String, id: Int, key: String?, settingsFrom: Int? = null) = buildJsonObject {
        put("op", "layerOn"); put("cueId", cueId); put("source", source(kind, id))
        if (key != null) put("target", target(key))
        if (settingsFrom != null) put("settingsFrom", settingsFrom)
    }

    private fun layerOff(cueId: Int, kind: String, id: Int, key: String?) = buildJsonObject {
        put("op", "layerOff"); put("cueId", cueId); put("source", source(kind, id))
        if (key != null) put("target", target(key))
    }

    private suspend fun HttpClient.details(cueId: Int): CueDetails = get("$base/cues/$cueId").body()

    /** The cue's stored layers as `name: targets`, bottom first. */
    private suspend fun HttpClient.layers(cueId: Int): List<String> =
        details(cueId).layers.map { layer -> "${layer.source?.name}: ${layer.targets.joinToString(",") { it.key }}" }

    @Test
    fun `layerOn puts a palette on one cell as a new layer on top, with the settings of settingsFrom`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val warm = client.look("Warm")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", layers = listOf(
            CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.6, attributeMask = "COLOUR"),
        ))
        val q2 = client.cue(act, "Q2", layers = listOf(CueLayerDto(lookId = warm)))
        val from = client.details(q1).layers.single().id!!

        val answer = client.edit(act, layerOn(q2, "PALETTE", lavender, "hex-1", settingsFrom = from))

        assertEquals(listOf(q2), answer.changed)
        assertEquals(listOf("Warm: ", "Lavender: hex-1"), client.layers(q2), "on top")
        assertEquals(0.6, client.details(q2).layers.last().amount)
        assertEquals(listOf(q2), answer.before.map { it.cueId })
        assertEquals(listOf("Warm"), answer.before.single().layers.map { it.source?.name })

        // Without settingsFrom, a fresh press's defaults.
        client.edit(act, layerOn(q2, "PALETTE", lavender, "hex-2"))
        assertEquals(listOf("Warm: ", "Lavender: hex-1", "Lavender: hex-2"), client.layers(q2), "0.6 is not a fresh press's: no widening")
        assertEquals(1.0, client.details(q2).layers.last().amount)
    }

    @Test
    fun `a layer widened by layerOn keeps its own identity and settings`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", layers = listOf(
            CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.6, attributeMask = "COLOUR"),
        ))
        val stored = client.details(q1).layers.single()

        client.edit(act, layerOn(q1, "PALETTE", lavender, "hex-2", settingsFrom = stored.id))

        val widened = client.details(q1).layers.single()
        assertEquals(stored.uuid, widened.uuid)
        assertEquals(listOf("hex-1", "hex-2"), widened.targets.map { it.key })
        assertEquals(0.6, widened.amount)
    }

    @Test
    fun `a palette on takes its family's other palettes off the cell, and says so in released`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val amber = client.palette("Amber", "#ffb000")
        val act = client.stack("Act 1")
        val q5 = client.cue(act, "Q5", layers = listOf(
            CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))),
        ))

        val answer = client.edit(act, layerOn(q5, "PALETTE", amber, "hex-1"))

        assertEquals(listOf("Lavender: hex-2", "Amber: hex-1"), client.layers(q5))
        val released = answer.released.single()
        assertEquals(q5, released.cueId)
        assertEquals("Lavender", released.source.name)
        assertEquals(listOf("hex-1"), released.targets.map { it.key })
    }

    @Test
    fun `layerOff takes the cell off, and an op over a cell already in that state changes nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val warm = client.look("Warm")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", layers = listOf(
            CueLayerDto(lookId = warm),
            CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1"), CueTargetDto("fixture", "hex-2"))),
        ))

        assertEquals(emptyList(), client.edit(act, layerOn(q1, "PALETTE", lavender, "hex-1")).changed, "already on")
        client.edit(act, layerOff(q1, "PALETTE", lavender, "hex-1"))
        assertEquals(listOf("Warm: ", "Lavender: hex-2"), client.layers(q1))
        client.edit(act, layerOff(q1, "LOOK", warm, null))
        assertEquals(listOf("Lavender: hex-2"), client.layers(q1))
        assertEquals(emptyList(), client.edit(act, layerOff(q1, "LOOK", warm, null)).changed, "already off")
    }

    @Test
    fun `a Look goes on a cue whole, and off takes every layer of it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm")
        val lavender = client.palette("Lavender")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")

        client.edit(act, layerOn(q1, "LOOK", warm, null))
        assertEquals(listOf("Warm: "), client.layers(q1), "a Look layer names no target")
        client.edit(act, layerOn(q1, "PALETTE", lavender, "hex-2"))
        client.edit(act, layerOn(q1, "LOOK", warm, null))
        assertEquals(listOf("Warm: ", "Lavender: hex-2"), client.layers(q1), "the Look was on already: no second layer")

        // A second layer of it, as an older write could have left one.
        transaction(state.database) {
            uk.me.cormack.lighting7.models.DaoCueLayer.new {
                cue = uk.me.cormack.lighting7.models.DaoCue[q1]
                look = uk.me.cormack.lighting7.models.DaoLook[warm]
                targets = emptyList()
                sortOrder = 2
            }
        }
        assertEquals(listOf("Warm: ", "Lavender: hex-2", "Warm: "), client.layers(q1))

        client.edit(act, layerOff(q1, "LOOK", warm, null))
        assertEquals(listOf("Lavender: hex-2"), client.layers(q1))
    }

    @Test
    fun `an op giving a Look's cell a target is refused by name`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm")
        val lavender = client.palette("Lavender")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", layers = listOf(CueLayerDto(lookId = warm)))
        val before = client.edit(act, layerOff(q1, "LOOK", warm, null), dryRun = true).before.single()
        val targeted = before.copy(layers = before.layers.map { it.copy(targets = listOf(CueTargetDto("fixture", "hex-1"))) })

        val refused = client.editRaw(act, listOf(
            layerOn(q1, "LOOK", warm, "hex-2"),
            layerOff(q1, "LOOK", warm, "hex-1"),
            buildJsonObject {
                put("op", "replace")
                putJsonArray("cueIds") { add(JsonPrimitive(q1)) }
                putJsonObject("from") { put("source", source("LOOK", warm)) }
                putJsonObject("to") { put("source", source("PALETTE", lavender)); put("target", target("hex-1")) }
            },
            buildJsonObject {
                put("op", "replace")
                putJsonArray("cueIds") { add(JsonPrimitive(q1)) }
                putJsonObject("from") { put("source", source("LOOK", warm)); put("target", target("hex-1")) }
                putJsonObject("to") { put("source", source("PALETTE", lavender)); put("target", target("hex-1")) }
            },
            restoreOf(targeted),
        ))
        assertEquals(HttpStatusCode.BadRequest, refused.status, refused.bodyAsText())
        val problems = refused.body<TimelineEditRefusal>().problems
        assertEquals(listOf(0, 1, 3, 4), problems.map { it.op }, "the replace from the Look's own cell is fine")
        assertTrue(problems.all { "LOOK_LAYER_TARGETED" in it.message }, problems.toString())
        assertEquals(listOf("Warm: "), client.layers(q1), "nothing was written")
    }

    @Test
    fun `layerSettings writes a layer's fields and names every bad one`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val warm = client.look("Warm")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", layers = listOf(CueLayerDto(lookId = warm)))
        val layer = client.details(q1).layers.single()

        client.edit(act, buildJsonObject {
            put("op", "layerSettings"); put("cueId", q1); put("layerId", layer.id!!)
            putJsonObject("fields") { put("amount", 0.5); put("blendMode", "ADDITIVE"); put("attributeMask", "COLOUR"); put("stomp", true); put("delayMs", 500) }
        })
        val written = client.details(q1).layers.single()
        assertEquals(layer.uuid, written.uuid)
        assertEquals(0.5, written.amount)
        assertEquals("ADDITIVE", written.blendMode)
        assertEquals("COLOUR", written.attributeMask)
        assertTrue(written.stomp)
        assertEquals(500L, written.delayMs)

        val refused = client.editRaw(act, listOf(buildJsonObject {
            put("op", "layerSettings"); put("cueId", q1); put("layerId", layer.id!!)
            putJsonObject("fields") { put("amount", 2.0); put("blendMode", "SIDEWAYS"); put("colour", "red") }
        }))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val problems = refused.body<TimelineEditRefusal>()
        assertEquals(CODE_TIMELINE_EDIT_INVALID, problems.code)
        assertEquals(3, problems.problems.size, problems.problems.toString())
        assertTrue(problems.problems.all { it.op == 0 })
    }

    @Test
    fun `replace swaps a record in place where the cue lacks the replacement, and takes it off where it has it`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val amber = client.palette("Amber", "#ffb000")
        val warm = client.look("Warm")
        val act = client.stack("Act 1")
        val lavenderOnHex1 = CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1")), amount = 0.6, attributeMask = "COLOUR")
        val q2 = client.cue(act, "Q2", layers = listOf(lavenderOnHex1, CueLayerDto(lookId = warm)))
        val q5 = client.cue(act, "Q5", layers = listOf(
            CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1"))),
            CueLayerDto(paletteId = amber, targets = listOf(CueTargetDto("fixture", "hex-1"))),
        ))

        val answer = client.edit(act, buildJsonObject {
            put("op", "replace")
            putJsonArray("cueIds") { add(JsonPrimitive(q2)); add(JsonPrimitive(q5)) }
            putJsonObject("from") { put("source", source("PALETTE", lavender)); put("target", target("hex-1")) }
            putJsonObject("to") { put("source", source("PALETTE", amber)); put("target", target("hex-1")) }
            put("keepSettings", true)
        })

        assertEquals(setOf(q2, q5), answer.changed.toSet())
        assertEquals(listOf("Amber: hex-1", "Warm: "), client.layers(q2), "rank kept")
        assertEquals(0.6, client.details(q2).layers.first().amount, "settings kept")
        assertEquals(listOf("Amber: hex-1"), client.layers(q5), "Q5 held Amber already: Lavender came off")
    }

    @Test
    fun `scenerySet writes a cue's change, sceneryClear hands the cue back to tracking, and a bad state is refused`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val tabs = client.drape("House tabs")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")

        client.edit(act, buildJsonObject {
            put("op", "scenerySet"); put("cueId", q1); put("elementUuid", tabs)
            putJsonObject("state") { put("open", 0.0) }; put("transitionMs", 4000)
        })
        val change = client.details(q1).scenery.single()
        assertEquals(tabs, change.elementUuid)
        assertEquals(4000L, change.transitionMs)

        val refused = client.editRaw(act, listOf(buildJsonObject {
            put("op", "scenerySet"); put("cueId", q1); put("elementUuid", tabs)
            putJsonObject("state") { put("trimM", 3.0) }
        }))
        assertEquals(HttpStatusCode.BadRequest, refused.status, "a drawn drape has no trim")

        client.edit(act, buildJsonObject { put("op", "sceneryClear"); put("cueId", q1); put("elementUuid", tabs) })
        assertTrue(client.details(q1).scenery.isEmpty())
    }

    @Test
    fun `a batch that fails on its last op rolls back its first, naming every problem`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val act = client.stack("Act 1")
        val other = client.stack("Act 2")
        val q1 = client.cue(act, "Q1")
        val elsewhere = client.cue(other, "Q9")

        val refused = client.editRaw(act, listOf(
            layerOn(q1, "PALETTE", lavender, "hex-1"),
            layerOn(elsewhere, "PALETTE", lavender, "hex-1"),
            layerOn(q1, "PALETTE", 99999, "hex-1"),
        ))

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(listOf(1, 2), refused.body<TimelineEditRefusal>().problems.map { it.op })
        assertTrue(client.layers(q1).isEmpty(), "the first op was rolled back")
    }

    @Test
    fun `dryRun answers the batch and writes nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")

        val answer = client.edit(act, layerOn(q1, "PALETTE", lavender, "hex-1"), dryRun = true)

        assertEquals(listOf(q1), answer.changed)
        assertTrue(client.layers(q1).isEmpty())
    }

    @Test
    fun `restore returns a cue to its before exactly, uuids and run keys included`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val lavender = client.palette("Lavender")
        val amber = client.palette("Amber", "#ffb000")
        val warm = client.look("Warm")
        val tabs = client.drape("House tabs")
        val act = client.stack("Act 1")
        val q1 = client.cue(
            act, "Q1",
            layers = listOf(
                CueLayerDto(lookId = warm, amount = 0.8, sortOrder = 0),
                CueLayerDto(paletteId = lavender, targets = listOf(CueTargetDto("fixture", "hex-1")), attributeMask = "COLOUR", sortOrder = 1),
            ),
            effects = listOf(
                CueAdHocFxDto(
                    targetType = "group", targetKey = "wash", fxType = "Pulse", family = "intensity", attribute = "dimmer",
                    cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL",
                ),
            ),
        )
        val run = UUID.randomUUID()
        transaction(state.database) { DaoCueAdHocFxEntity.find { DaoCueAdHocFx.cue eq q1 }.single().runUuid = run }
        client.edit(act, buildJsonObject {
            put("op", "scenerySet"); put("cueId", q1); put("elementUuid", tabs); putJsonObject("state") { put("open", 0.0) }
        })
        val found = client.details(q1)

        val answer = client.edit(
            act,
            layerOn(q1, "PALETTE", amber, "hex-1"),
            layerOff(q1, "LOOK", warm, null),
            buildJsonObject { put("op", "sceneryClear"); put("cueId", q1); put("elementUuid", tabs) },
        )
        assertEquals(listOf("Amber: hex-1"), client.layers(q1))
        val before = answer.before.single()

        client.edit(act, restoreOf(before))

        val restored = client.details(q1)
        assertEquals(found.layers.map { it.copy(id = null) }, restored.layers.map { it.copy(id = null) })
        assertEquals(found.adHocFx, restored.adHocFx)
        assertEquals(run.toString(), restored.adHocFx.single().runUuid)
        assertEquals(found.scenery.map(SceneryChangeDto::uuid), restored.scenery.map(SceneryChangeDto::uuid))
        assertEquals(found.scenery.map { it.state }, restored.scenery.map { it.state })
    }

    @Test
    fun `an edit to a live cue marks it edited since GO and sends nothing to the stage`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val half = client.palette("Half", "pct:50", attribute = "dimmer")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")
        val entry = client.details(q1).entries.single().entryId
        state.show.cueStackManager.activateEntry(state, act, entry)
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val before = controller.currentValues.toMap()
        val effectsBefore = state.show.fxEngine.getActiveFx().map { it.id }

        val answer = client.edit(act, layerOn(q1, "PALETTE", half, "hex-1"))

        assertEquals(listOf(q1), answer.edited)
        assertTrue(state.show.cueStackManager.holds.isEdited(q1))
        assertEquals(before, controller.currentValues.toMap(), "no channel moved")
        assertTrue(state.show.programmerStore.layers.isEmpty(), "the programmer was not touched")
        assertEquals(effectsBefore, state.show.fxEngine.getActiveFx().map { it.id }, "no effect started or stopped")
    }

    @Test
    fun `a dry run on a live cue marks nothing`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val half = client.palette("Half", "pct:50", attribute = "dimmer")
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1")
        state.show.cueStackManager.activateEntry(state, act, client.details(q1).entries.single().entryId)

        assertEquals(listOf(q1), client.edit(act, layerOn(q1, "PALETTE", half, "hex-1"), dryRun = true).edited)
        assertFalse(state.show.cueStackManager.holds.isEdited(q1))
    }

    @Test
    fun `another project's stack is not found, and a project that is not current is refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (otherProject, elsewhere) = transaction(state.database) {
            val other = DaoProject.new { name = "other"; isCurrent = false }
            other.id.value to DaoCueStack.new { name = "Theirs"; project = other }.id.value
        }
        assertEquals(HttpStatusCode.NotFound, client.editRaw(elsewhere, emptyList()).status)
        val offRig = client.post("/api/rest/projects/$otherProject/cue-stacks/$elsewhere/timeline/edit") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("ops", JsonArray(emptyList())) })
        }
        assertEquals(HttpStatusCode.Conflict, offRig.status)
    }

    // ─── Effect cells and runs (session 5) ───────────────────────────────────────────────────────

    private fun pulse(fixture: String = "hex-1", fxType: String = "Pulse") = CueAdHocFxDto(
        targetType = "fixture", targetKey = fixture, fxType = fxType, family = "intensity", attribute = "dimmer",
        cycle = 1.0, blendMode = "OVERRIDE", order = "NORMAL",
    )

    private fun fxOp(op: String, cueId: Int, fxUuid: String, entryId: Int? = null) = buildJsonObject {
        put("op", op); put("cueId", cueId); put("fxUuid", fxUuid)
        if (entryId != null) put("entryId", entryId)
    }

    private fun fxOn(cueId: Int, from: String) = buildJsonObject { put("op", "fxOn"); put("cueId", cueId); put("fromFxUuid", from) }

    private suspend fun HttpClient.fx(cueId: Int): List<CueAdHocFxDto> = details(cueId).adHocFx

    /** The run key of each cue's one effect, in [cues]' order. */
    private suspend fun HttpClient.keys(vararg cues: Int): List<String?> = cues.map { fx(it).single().runUuid }

    private suspend fun HttpClient.marker(stackId: Int, label: String) {
        post("$base/cue-stacks/$stackId/markers") {
            contentType(ContentType.Application.Json)
            setBody(MarkerEntryRequest(label = label))
        }.also { assertEquals(HttpStatusCode.Created, it.status, it.bodyAsText()) }
    }

    private fun setKey(cueId: Int, key: UUID?) = transaction(state.database) {
        DaoCueAdHocFxEntity.find { DaoCueAdHocFx.cue eq cueId }.forEach { it.runUuid = key }
    }

    @Test
    fun `fxOn from an unkeyed effect copies it into the cue and keys both, and fxOff deletes the row`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse().copy(cycle = 0.5, amount = 0.6)))
        val q2 = client.cue(act, "Q2", effects = listOf(pulse("hex-2", "SineWave")))
        val source = client.fx(q1).single()
        assertEquals(null, source.runUuid)

        val answer = client.edit(act, fxOn(q2, source.uuid!!))

        assertEquals(listOf(q2, q1).toSet(), answer.changed.toSet(), "the source took the key too")
        val copy = client.fx(q2).last()
        assertEquals(listOf("SineWave", "Pulse"), client.fx(q2).map { it.fxType }, "the copy goes on top")
        assertEquals(0.5, copy.cycle)
        assertEquals(0.6, copy.amount)
        assertTrue(copy.uuid != source.uuid, "a row of its own")
        val key = client.fx(q1).single().runUuid
        assertTrue(key != null, "the source was keyed")
        assertEquals(key, copy.runUuid, "and the copy joins its run")
        assertEquals(null, client.fx(q2).first().runUuid, "the cue's other effect is untouched")
        assertEquals(null, answer.before.single { it.cueId == q1 }.adHocFx.single().runUuid, "Undo puts the source back unkeyed")

        // A keyed source lends its key; a cue already filling the column is left alone.
        val q3 = client.cue(act, "Q3")
        client.edit(act, fxOn(q3, copy.uuid!!))
        assertEquals(key, client.fx(q3).single().runUuid)
        assertEquals(emptyList(), client.edit(act, fxOn(q3, source.uuid)).changed, "already on")

        client.edit(act, fxOp("fxOff", q2, copy.uuid))
        assertEquals(listOf("SineWave"), client.fx(q2).map { it.fxType })

        val refused = client.editRaw(act, listOf(fxOp("fxOff", q2, UUID.randomUUID().toString()), fxOn(q2, "not-a-uuid")))
        assertEquals(HttpStatusCode.BadRequest, refused.status)
        assertEquals(listOf(0, 1), refused.body<TimelineEditRefusal>().problems.map { it.op })
    }

    @Test
    fun `breakRun and joinRun walk a run down the stack past a marker`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2", effects = listOf(pulse()))
        client.marker(act, "Scene 2")
        val q3 = client.cue(act, "Q3", effects = listOf(pulse()))
        val q4 = client.cue(act, "Q4", effects = listOf(pulse()))
        val q5 = client.cue(act, "Q5", effects = listOf(pulse()))
        val run = UUID.randomUUID()
        listOf(q1, q2, q3, q4).forEach { setKey(it, run) }
        val other = UUID.randomUUID()
        setKey(q5, other)

        // Break at Q2: Q2, and Q3 and Q4 past the marker, take one fresh key; Q1 keeps the run's, Q5 its own.
        client.edit(act, fxOp("breakRun", q2, client.fx(q2).single().uuid!!))
        val (k1, k2, k3, k4, k5) = client.keys(q1, q2, q3, q4, q5)
        assertEquals(run.toString(), k1)
        assertTrue(k2 != null && k2 != k1)
        assertEquals(listOf(k2, k2), listOf(k3, k4), "the run below, past the marker")
        assertEquals(other.toString(), k5, "a run that only met this one is not part of it")

        // Join at Q2: the key of Q1, for Q2 and the rest of its run below.
        client.edit(act, fxOp("joinRun", q2, client.fx(q2).single().uuid!!))
        assertEquals(List(4) { run.toString() } + other.toString(), client.keys(q1, q2, q3, q4, q5))

        // An unkeyed cell joins too (C5): the cell above it is given a key, and the two share it.
        setKey(q1, null); setKey(q2, null)
        client.edit(act, fxOp("joinRun", q2, client.fx(q2).single().uuid!!))
        val joined = client.keys(q1, q2)
        assertTrue(joined[0] != null && joined[0] == joined[1], joined.toString())
        assertEquals(run.toString(), client.keys(q3).single(), "Q2 was in no run, so nothing below it moved")

        val refused = client.editRaw(act, listOf(fxOp("joinRun", q1, client.fx(q1).single().uuid!!)))
        assertEquals(HttpStatusCode.BadRequest, refused.status, "nothing above Q1 to join")

        // A run joins neighbours only: across an empty cue there is nothing to join.
        client.cue(act, "Q6")
        val q7 = client.cue(act, "Q7", effects = listOf(pulse()))
        val gap = client.editRaw(act, listOf(fxOp("joinRun", q7, client.fx(q7).single().uuid!!)))
        assertEquals(HttpStatusCode.BadRequest, gap.status, gap.bodyAsText())
        assertEquals(listOf(null), client.keys(q7))
    }

    @Test
    fun `a cue placed twice joins the run above the place an op names`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val x = client.cue(act, "X", effects = listOf(pulse()))
        val y = client.cue(act, "Y", effects = listOf(pulse()))
        client.post("$base/cue-stacks/$act/add-cue") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("cueId", x) })
        }.also { assertEquals(HttpStatusCode.OK, it.status, it.bodyAsText()) }
        val key = UUID.randomUUID()
        setKey(y, key)
        val places = client.details(x).entries.map { it.entryId }
        assertEquals(2, places.size)
        val row = client.fx(x).single().uuid!!

        assertEquals(HttpStatusCode.BadRequest, client.editRaw(act, listOf(fxOp("joinRun", x, row))).status, "nothing above its first place")
        client.edit(act, fxOp("joinRun", x, row, entryId = places[1]))
        assertEquals(listOf(key.toString(), key.toString()), client.keys(x, y))

        val wrong = client.editRaw(act, listOf(fxOp("breakRun", x, row, entryId = client.details(y).entries.single().entryId)))
        assertEquals(HttpStatusCode.BadRequest, wrong.status, "an entry of another cue")
    }

    @Test
    fun `an op that would give a cue one run key on two effects is refused`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2", effects = listOf(pulse(fxType = "SineWave")))
        val key = UUID.randomUUID()
        setKey(q1, key)
        setKey(q2, key)

        val refused = client.editRaw(act, listOf(fxOn(q2, client.fx(q1).single().uuid!!)))

        assertEquals(HttpStatusCode.BadRequest, refused.status)
        val problem = refused.body<TimelineEditRefusal>().problems.single()
        assertEquals(0, problem.op)
        assertTrue("run key" in problem.message, problem.message)
        assertEquals(listOf("SineWave"), client.fx(q2).map { it.fxType }, "nothing written")

        // A key two effects already shared as the batch found the cue is not the op's to refuse.
        transaction(state.database) {
            DaoCueAdHocFxEntity.new {
                cue = uk.me.cormack.lighting7.models.DaoCue[q2]
                targetType = "fixture"; targetKey = "hex-2"
                fxType = "Pulse"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"; parameters = emptyMap()
                sortOrder = 1; runUuid = key
            }
        }
        val q3 = client.cue(act, "Q3", effects = listOf(pulse("hex-1", "SquareWave")))
        client.edit(act, fxOn(q2, client.fx(q3).single().uuid!!))
        assertEquals(listOf("SineWave", "Pulse", "SquareWave"), client.fx(q2).map { it.fxType })
    }

    @Test
    fun `breaking or joining a live cue's run does not mark it edited, and nothing reaches the stage`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2", effects = listOf(pulse()))
        val run = UUID.randomUUID()
        setKey(q1, run); setKey(q2, run)
        state.show.cueStackManager.activateEntry(state, act, client.details(q2).entries.single().entryId)
        val effectsBefore = state.show.fxEngine.getActiveFx().map { it.id }

        val broken = client.edit(act, fxOp("breakRun", q2, client.fx(q2).single().uuid!!))
        val joined = client.edit(act, fxOp("joinRun", q2, client.fx(q2).single().uuid!!))

        assertEquals(listOf(q2), broken.changed)
        assertEquals(emptyList(), broken.edited, "a run key is not stage content (P4)")
        assertEquals(emptyList(), joined.edited)
        assertFalse(state.show.cueStackManager.holds.isEdited(q2))
        assertEquals(effectsBefore, state.show.fxEngine.getActiveFx().map { it.id }, "the running effect runs on")
        assertEquals(run, state.show.fxEngine.getActiveFx().single { it.cueId == q2 }.runUuid, "and carries the key it has again")

        // Taking the effect off is stage content.
        val off = client.edit(act, fxOp("fxOff", q2, client.fx(q2).single().uuid!!))
        assertEquals(listOf(q2), off.edited)
    }

    @Test
    fun `a run made in the timeline continues across a GO with its phase kept`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2")
        client.edit(act, fxOn(q2, client.fx(q1).single().uuid!!))
        val manager = state.show.cueStackManager
        manager.activateEntry(state, act, client.details(q1).entries.single().entryId)
        val pulse = state.show.fxEngine.getActiveFx().single { it.cueId == q1 }
        val (id, started) = pulse.id to pulse.startedAtMs

        manager.advanceStack(state, act, uk.me.cormack.lighting7.fx.CueStackManager.AdvanceDirection.FORWARD)

        val running = state.show.fxEngine.getActiveFx().single { it.cueId == q2 }
        assertEquals(id, running.id, "the same effect runs on")
        assertEquals(started, running.startedAtMs, "phase kept")
        assertEquals(client.fx(q2).single().uuid, running.layerFxUuid.toString(), "stamped with Q2's row")
    }

    @Test
    fun `a run key written to a live cue reaches its running effect, so the next GO follows the grid`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2", effects = listOf(pulse()))
        val q3 = client.cue(act, "Q3", effects = listOf(pulse()))
        val run = UUID.randomUUID()
        listOf(q1, q2, q3).forEach { setKey(it, run) }
        val manager = state.show.cueStackManager
        manager.activateEntry(state, act, client.details(q2).entries.single().entryId)

        // Break at the live Q2: Q2 and Q3 are one run under a new key, which the running Pulse takes.
        client.edit(act, fxOp("breakRun", q2, client.fx(q2).single().uuid!!))
        val pulse = state.show.fxEngine.getActiveFx().single { it.cueId == q2 }
        assertEquals(client.fx(q2).single().runUuid, pulse.runUuid.toString())
        manager.advanceStack(state, act, uk.me.cormack.lighting7.fx.CueStackManager.AdvanceDirection.FORWARD)
        assertEquals(pulse.id, state.show.fxEngine.getActiveFx().single { it.cueId == q3 }.id, "handed on at the GO")
    }

    @Test
    fun `an effect keyed by fxOn while live is handed on at the next GO`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val act = client.stack("Act 1")
        val q1 = client.cue(act, "Q1", effects = listOf(pulse()))
        val q2 = client.cue(act, "Q2")
        val manager = state.show.cueStackManager
        manager.activateEntry(state, act, client.details(q1).entries.single().entryId)
        val pulse = state.show.fxEngine.getActiveFx().single { it.cueId == q1 }
        assertEquals(null, pulse.runUuid)

        client.edit(act, fxOn(q2, client.fx(q1).single().uuid!!))
        manager.advanceStack(state, act, uk.me.cormack.lighting7.fx.CueStackManager.AdvanceDirection.FORWARD)

        val running = state.show.fxEngine.getActiveFx().single { it.cueId == q2 }
        assertEquals(pulse.id, running.id, "the same effect runs on")
        assertEquals(pulse.startedAtMs, running.startedAtMs, "phase kept")
    }

    private fun restoreOf(snapshot: TimelineCueSnapshotDto): JsonObject {
        val encoded = Json.encodeToJsonElement(snapshot).jsonObject
        return JsonObject(encoded + ("op" to JsonPrimitive("restore")))
    }
}
