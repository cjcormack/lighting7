package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.models.ElementStates
import uk.me.cormack.lighting7.models.SceneryChangeDto
import uk.me.cormack.lighting7.models.sceneryStateOf
import uk.me.cormack.lighting7.plugins.toMessage
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Record, Include and Update for the programmer's scenery (scenery-programmer plan session 3, D7,
 * D8, D10): a held piece goes into a cue only where the cue would not show it anyway, a Look takes
 * every held state, Include loads an owner's own rows (a cue row with its clock) and Update writes
 * them back, and `scenery: false` writes none of it.
 */
class ProgrammerSceneryRecordTest : RouteIntegrationTest() {

    private suspend fun element(client: HttpClient, body: JsonObject): String {
        val resp = client.post("/api/rest/projects/$projectId/stage-elements") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body<StageElementDto>().uuid
    }

    /** House tabs (drawn at base) and the moon (in at 3 m, out — its base — at 7 m). */
    private suspend fun tabsAndMoon(client: HttpClient): Pair<String, String> {
        val tabs = element(client, buildJsonObject {
            put("name", "House tabs"); put("kind", "DRAPE"); put("layer", "VENUE")
            put("widthM", 8.0); put("depthM", 0.1); put("heightM", 5.0)
            putJsonObject("params") {
                put("role", "TABS"); put("operation", "DRAW")
                putJsonObject("states") { put("open", 1.0) }
            }
        })
        val moon = element(client, buildJsonObject {
            put("name", "Moon"); put("kind", "OBJECT"); put("layer", "SET")
            put("positionZ", 3.0); put("widthM", 1.0); put("depthM", 0.05); put("heightM", 1.0)
            putJsonObject("params") {
                put("shape", "DISC"); put("flies", true)
                putJsonObject("states") { put("trimM", 7.0) }
            }
        })
        return tabs to moon
    }

    private suspend fun cue(client: HttpClient, stackId: Int, name: String): Int =
        ProgrammerRouteTestSupport.createCue(client, projectId, name, stackId = stackId)

    /** A cue's whole scenery list, as the editor writes it: `(element, state, transitionMs?)`. */
    private suspend fun putCueScenery(client: HttpClient, cueId: Int, vararg items: Triple<String, JsonObject, Long?>) {
        val resp = client.put("/api/rest/projects/$projectId/cues/$cueId/scenery") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                putJsonArray("scenery") {
                    items.forEach { (uuid, state, ms) ->
                        add(buildJsonObject { put("elementUuid", uuid); put("state", state); ms?.let { put("transitionMs", it) } })
                    }
                }
            })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
    }

    private suspend fun cueScenery(client: HttpClient, cueId: Int): List<SceneryChangeDto> =
        client.get("/api/rest/projects/$projectId/cues/$cueId").body<CueDetails>().scenery

    private suspend fun lookScenery(client: HttpClient, lookId: Int): List<SceneryChangeDto> =
        client.get("/api/rest/projects/$projectId/looks/$lookId").body<LookDetails>().scenery

    private suspend fun HttpClient.record(request: ProgrammerRecordRequest): ProgrammerRecordResponse {
        val resp = post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertTrue(resp.status.isSuccess(), resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.recordLook(request: ProgrammerRecordLookRequest): ProgrammerRecordLookResponse {
        val resp = post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.include(body: JsonObject): ProgrammerIncludeResponse {
        val resp = post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.update(): ProgrammerUpdateResponse {
        val resp = post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("projectId", projectId.toString()) })
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun hold(uuid: String, state: JsonObject) =
        assertEquals(emptyList(), this.state.programmerScenery.set(UUID.fromString(uuid), state, null))

    private fun open(v: Double) = buildJsonObject { put("open", v) }
    private fun trim(v: Double) = buildJsonObject { put("trimM", v) }
    private fun visible(v: Boolean) = buildJsonObject { put("visible", v) }

    private fun SceneryChangeDto.states(): ElementStates = sceneryStateOf(state)

    private fun changedSinceInclude(): Int? = state.programmerScenery.flow.value.toMessage().changedSinceInclude

    @Test
    fun `CREATE writes a row only for a held state the new cue would not track`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = cue(client, stackId, "Q1")
        putCueScenery(client, q1, Triple(moon, trim(3.0), null))

        // The moon is held where Q1 left it — the new cue tracks it there, so nothing is written for
        // it. The tabs are held closed, which nothing before the new cue says: one row.
        hold(moon, trim(3.0))
        hold(tabs, open(0.0))
        val recorded = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, name = "Q2"))

        assertTrue(recorded.created)
        assertEquals(1, recorded.sceneryWritten)
        assertEquals(1, recorded.sceneryAlreadyTracked)
        val row = recorded.cue.scenery.single()
        assertEquals(tabs, row.elementUuid)
        assertEquals(ElementStates(open = 0.0), row.states())
        assertNull(row.transitionMs, "a piece held from the band moves with the cue's fade")

        // A held state equal to the piece's base is tracked too: the moon out, the tabs drawn.
        hold(moon, trim(7.0))
        hold(tabs, open(1.0))
        val fresh = ProgrammerRouteTestSupport.createStack(client, projectId, "Other")
        val none = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = fresh))
        assertEquals(0, none.sceneryWritten)
        assertEquals(2, none.sceneryAlreadyTracked)
        assertTrue(none.cue.scenery.isEmpty())
    }

    @Test
    fun `MERGE replaces the cue's row per held element, and REMOVE deletes it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = cue(client, stackId, "Q1")
        putCueScenery(client, q1, Triple(moon, trim(3.0), 4000L), Triple(tabs, open(0.0), null))

        // The moon re-held out, which is also its base: the cue's own row is replaced, not dropped,
        // and keeps its clock. The tabs are not held, so their row is left alone.
        hold(moon, trim(7.0))
        val merged = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "MERGE", cueId = q1))
        assertEquals(1, merged.sceneryWritten)
        val afterMerge = cueScenery(client, q1).associateBy { it.elementUuid }
        assertEquals(ElementStates(trimM = 7.0), afterMerge.getValue(moon).states())
        assertEquals(4000L, afterMerge.getValue(moon).transitionMs)
        assertEquals(ElementStates(open = 0.0), afterMerge.getValue(tabs).states())

        // UPDATE_EXISTING is MERGE for scenery: a held piece the cue has no row for is added.
        clearProgrammerCompletely(state)
        hold(tabs, visible(false))
        hold(moon, trim(5.0))
        client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "UPDATE_EXISTING", cueId = q1))
        val afterReplace = cueScenery(client, q1).associateBy { it.elementUuid }
        assertEquals(ElementStates(visible = false), afterReplace.getValue(tabs).states(), "a row is replaced whole")
        assertEquals(ElementStates(trimM = 5.0), afterReplace.getValue(moon).states())

        // REMOVE deletes the rows for the held pieces, whatever they hold.
        clearProgrammerCompletely(state)
        hold(moon, trim(3.0))
        val removed = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "REMOVE", cueId = q1))
        assertEquals(1, removed.sceneryRemoved)
        assertEquals(listOf(tabs), cueScenery(client, q1).map { it.elementUuid })
    }

    @Test
    fun `a Look takes every held state, and one recorded with only scenery is a scenery Look`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)

        // The tabs drawn is their base — a cue would skip it, a Look asserts it.
        hold(moon, trim(3.0))
        hold(tabs, open(1.0))
        val recorded = client.recordLook(ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "CREATE", name = "Night"))

        assertTrue(recorded.created)
        assertEquals(0, recorded.rowsWritten, "nothing but scenery was held")
        assertEquals(2, recorded.sceneryWritten)
        val lookId = recorded.look.id
        assertEquals(
            mapOf(moon to ElementStates(trimM = 3.0), tabs to ElementStates(open = 1.0)),
            lookScenery(client, lookId).associate { it.elementUuid to it.states() },
        )

        // The library row carries it for the Scenery read-out.
        val listed = client.get("/api/rest/projects/$projectId/looks").body<List<LookDto>>().single { it.id == lookId }
        assertEquals(listOf("Moon", "House tabs"), listed.scenery.map { it.elementName })
        assertEquals(3.0, listed.scenery.first().state["trimM"]!!.jsonPrimitive.double)

        // MERGE replaces per element; REMOVE deletes the held pieces' rows.
        clearProgrammerCompletely(state)
        hold(moon, trim(7.0))
        client.recordLook(ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "MERGE", lookId = lookId))
        assertEquals(ElementStates(trimM = 7.0), lookScenery(client, lookId).single { it.elementUuid == moon }.states())
        val removed = client.recordLook(ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "REMOVE", lookId = lookId))
        assertEquals(1, removed.sceneryRemoved)
        assertEquals(listOf(tabs), lookScenery(client, lookId).map { it.elementUuid })
    }

    @Test
    fun `Include then Update round-trips a cue's rows, times included`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q14 = cue(client, stackId, "Q14")
        val q15 = cue(client, stackId, "Q15")
        putCueScenery(client, q14, Triple(tabs, open(0.5), null))
        putCueScenery(client, q15, Triple(moon, trim(3.0), 4000L))

        // Something held on the moon before Include is replaced by the cue's own row, not merged.
        hold(moon, visible(false))
        val included = client.include(buildJsonObject { put("projectId", projectId.toString()); put("cueId", q15) })
        assertEquals(1, included.sceneryIncluded)
        assertTrue(included.warnings.isEmpty(), "a cue that only moves scenery has something to include: ${included.warnings}")
        val held = state.programmerScenery.flow.value.elements
        assertEquals(setOf(UUID.fromString(moon)), held.keys, "only the cue's own rows — never Q14's tracked tabs")
        assertEquals(ElementStates(trimM = 3.0), held.getValue(UUID.fromString(moon)).state)
        assertEquals(4000L, held.getValue(UUID.fromString(moon)).transitionMs)
        assertEquals(0, changedSinceInclude())

        // Move the tabs: one change since Include, and Update writes it back beside the moon.
        hold(tabs, open(0.0))
        assertEquals(1, changedSinceInclude())
        val updated = client.update()
        assertTrue(updated.applied)
        assertEquals(1, updated.results.single().sceneryWritten, "the moon is the cue's already; only the tabs are new")
        val rows = cueScenery(client, q15).associateBy { it.elementUuid }
        assertEquals(setOf(moon, tabs), rows.keys)
        assertEquals(ElementStates(trimM = 3.0), rows.getValue(moon).states())
        assertEquals(4000L, rows.getValue(moon).transitionMs, "the included row's own clock comes back")
        assertEquals(ElementStates(open = 0.0), rows.getValue(tabs).states())
        assertEquals(0, changedSinceInclude())

        // Including a cue that stages nothing leaves the target on Q15, and the baseline with it.
        val empty = cue(client, stackId, "Q16")
        val nothing = client.include(buildJsonObject { put("projectId", projectId.toString()); put("cueId", empty) })
        assertTrue(nothing.warnings.isNotEmpty())
        assertEquals(q15, state.show.programmerStore.lastIncludedTarget?.cueId)
        assertEquals(0, changedSinceInclude(), "Q15's baseline still stands")
    }

    @Test
    fun `Clear after Including a cue for its scenery alone drops the include target`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q15 = cue(client, stackId, "Q15")
        putCueScenery(client, q15, Triple(moon, trim(3.0), null))

        client.include(buildJsonObject { put("projectId", projectId.toString()); put("cueId", q15) })
        assertEquals(q15, state.show.programmerStore.lastIncludedTarget?.cueId, "a cue that only moves scenery is something to include")
        // No value is held, so the writer's own clear never reaches the store: the target went
        // with every client's copy of it, and stayed on the desk.
        clearProgrammerCompletely(state)
        assertNull(state.show.programmerStore.lastIncludedTarget)
        assertTrue(state.programmerScenery.flow.value.elements.isEmpty())
    }

    @Test
    fun `Include then Update round-trips a Look's scenery`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        hold(moon, trim(3.0))
        val lookId = client.recordLook(ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "CREATE", name = "Night")).look.id
        clearProgrammerCompletely(state)

        val included = client.include(buildJsonObject { put("projectId", projectId.toString()); put("lookId", lookId) })
        assertEquals(1, included.sceneryIncluded)
        assertTrue(included.warnings.isEmpty(), "a scenery Look stages something: ${included.warnings}")
        hold(tabs, open(0.0))
        val updated = client.update()
        assertTrue(updated.applied)
        assertEquals(1, updated.lookResult!!.sceneryWritten, "the moon is the Look's already; only the tabs are new")
        assertEquals(
            mapOf(moon to ElementStates(trimM = 3.0), tabs to ElementStates(open = 0.0)),
            lookScenery(client, lookId).associate { it.elementUuid to it.states() },
        )
    }

    @Test
    fun `scenery false writes nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (tabs, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        val q1 = cue(client, stackId, "Q1")
        putCueScenery(client, q1, Triple(tabs, open(0.0), null))
        hold(moon, trim(3.0))
        hold(tabs, open(0.5))

        val created = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, scenery = false))
        assertTrue(created.cue.scenery.isEmpty())
        assertEquals(0, created.sceneryWritten)
        val removed = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "REMOVE", cueId = q1, scenery = false))
        assertEquals(0, removed.sceneryRemoved)
        assertEquals(listOf(tabs), cueScenery(client, q1).map { it.elementUuid }, "REMOVE without scenery leaves the cue's rows")
        assertEquals(2, changedSinceInclude(), "a Record told to leave the scenery out leaves the cue saying none of it")

        val look = client.recordLook(ProgrammerRecordLookRequest(projectId = projectId.toString(), mode = "CREATE", name = "Lit only", scenery = false))
        assertEquals(0, look.sceneryWritten)
        assertTrue(lookScenery(client, look.look.id).isEmpty())

        // The AI's record_cue takes the same switch, and defaults it on.
        val tools = AiTools(state)
        val off = tools.executeTool("record_cue", buildJsonObject { put("cueStackId", stackId); put("scenery", false) })
        assertTrue(off.success, off.description)
        assertEquals(0, Json.parseToJsonElement(off.result).jsonObject["sceneryWritten"]!!.jsonPrimitive.content.toInt())
        val on = tools.executeTool("record_cue", buildJsonObject { put("cueStackId", stackId) })
        assertTrue(on.success, on.description)
        assertEquals(2, Json.parseToJsonElement(on.result).jsonObject["sceneryWritten"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a marker records no scenery and says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (_, moon) = tabsAndMoon(client)
        val stackId = ProgrammerRouteTestSupport.createStack(client, projectId, "Main")
        hold(moon, trim(3.0))
        val marker = client.record(ProgrammerRecordRequest(projectId = projectId.toString(), mode = "CREATE", cueStackId = stackId, cueType = "MARKER"))
        assertTrue(marker.cue.scenery.isEmpty())
        assertTrue(marker.warnings.any { "marker" in it.lowercase() }, marker.warnings.toString())
    }
}
