package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.models.CueAdHocFxDto
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.LookFxDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import org.jetbrains.exposed.v1.core.eq
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Apply order kept (FX editor plan D4, §3.5's stored half): Record writes a home's FX in the order
 * the engine applies them, and the home spawns them back in that order. Two Local FX on one dimmer,
 * a *Replace* Sine Wave at 200 and a *Within* Square Wave at half, read 200 with the Replace on top
 * and 100 with the Within on top, so the committed dimmer says which order the stage folds them in.
 * Two types, because a write pairs a home's FX by target, attribute and type.
 */
class FxApplyOrderKeptTest : RouteIntegrationTest() {

    private val fx get() = state.show.fxEngine

    private val hex = listOf(CueTargetDto(TargetRef.Fixture.TYPE, "hex-1"))

    /** Replace on top: the Within under it is overwritten. */
    private val replaceOnTop = 200

    /** Within on top: 200 scaled by 128/255. */
    private val withinOnTop = 100

    private fun seedRig() = LocateTestSupport.seedHex(state, projectId, "hex-1", 1)

    private fun dimmer(): Int = (state.show.fixtures.controllers.first().currentValues[1] ?: 0u).toInt()

    /** The compose pass runs on its own clock; wait for it to commit [expected]. */
    private fun awaitDimmer(expected: Int, why: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (dimmer() != expected && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(expected, dimmer(), why)
    }

    /** One level whatever the phase: a Wave or a Flicker whose two levels agree. */
    private fun level(fxType: String, value: Int, blendMode: String, stack: Boolean) = ProgrammerFxRequest(
        fxType = fxType, attribute = "dimmer", targets = hex,
        parameters = flat(value), blendMode = blendMode, stack = stack,
    )

    private fun flat(value: Int) = mapOf("low" to "$value", "high" to "$value")

    private suspend fun HttpClient.press(request: ProgrammerFxRequest): FxInstance {
        val response = post("/api/rest/projects/$projectId/programmer/fx") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return assertNotNull(fx.getFx(response.body<ProgrammerFxResponse>().fxId))
    }

    /** The two Local FX, Within pressed last so on top; moved under the Replace when [moved]. */
    private suspend fun HttpClient.stackTwo(moved: Boolean): Pair<FxInstance, FxInstance> {
        val replace = press(level("Wave", 200, "OVERRIDE", stack = false))
        val within = press(level("Flicker", 128, "MULTIPLY", stack = true))
        awaitDimmer(withinOnTop, "pressed last, the Within applies on top")
        if (moved) {
            assertTrue(fx.moveLocalFx(within.id, aboveId = null) is FxEngine.LocalMoveOutcome.Moved)
            awaitDimmer(replaceOnTop, "moved to the bottom, the Within applies under the Replace")
        }
        return replace to within
    }

    private fun expected(moved: Boolean) = if (moved) replaceOnTop else withinOnTop

    /** Bottom first: the blends a home's FX spawn in. */
    private fun expectedBlends(moved: Boolean) = if (moved) listOf("MULTIPLY", "OVERRIDE") else listOf("OVERRIDE", "MULTIPLY")

    private fun cueFx(cueId: Int): List<Pair<String, Int>> = transaction(state.database) {
        DaoCue.findById(cueId)!!.adHocFxInOrder().map { it.blendMode to it.sortOrder }
    }

    private fun lookFx(lookId: Int): List<Pair<String, Int>> = transaction(state.database) {
        DaoLook.findById(lookId)!!.fxInOrder().map { it.blendMode to it.sortOrder }
    }

    private fun clearStage(why: String) {
        fx.clearAllFx()
        awaitDimmer(0, why)
    }

    private suspend fun HttpClient.record(mode: String, cueId: Int? = null, name: String? = null): ProgrammerRecordResponse {
        val response = post("/api/rest/programmer/record") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordRequest(projectId = projectId.toString(), mode = mode, cueId = cueId, name = name))
        }
        assertEquals(if (mode == "CREATE") HttpStatusCode.Created else HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.recordLook(name: String, fxIds: List<Long>, mode: String = "CREATE"): LookDetails {
        val response = post("/api/rest/programmer/record-look") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerRecordLookRequest(projectId.toString(), mode, name = name, fxIds = fxIds))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body<ProgrammerRecordLookResponse>().look
    }

    private suspend fun HttpClient.include(cueId: Int? = null, lookId: Int? = null) {
        val response = post("/api/rest/programmer/include") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerIncludeRequest(projectId = projectId.toString(), cueId = cueId, lookId = lookId))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.update(): ProgrammerUpdateResponse {
        val response = post("/api/rest/programmer/update") {
            contentType(ContentType.Application.Json)
            setBody(ProgrammerUpdateRequest(projectId = projectId.toString()))
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body()
    }

    private suspend fun HttpClient.applyCue(cueId: Int) {
        val response = post("/api/rest/projects/$projectId/cues/$cueId/apply")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    private suspend fun HttpClient.pressLook(lookId: Int) {
        val response = post("/api/rest/projects/$projectId/looks/$lookId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleLookRequest())
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    // ── A Look ──────────────────────────────────────────────────────────────────────────────

    private fun lookKeepsOrder(moved: Boolean) = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (replace, within) = client.stackTwo(moved)

        // Asked for top first, which is neither order: the Look is written in apply order whatever.
        val look = client.recordLook("Stacked", listOf(within.id, replace.id).let { if (moved) it.reversed() else it })
        assertEquals(expectedBlends(moved).mapIndexed { i, blend -> blend to i }, lookFx(look.id))
        assertEquals(expectedBlends(moved), look.fx.map { it.blendMode }, "the Look's detail reads them bottom first")
        awaitDimmer(0, "Record moves a Look's FX out of the programmer")

        client.pressLook(look.id)
        awaitDimmer(expected(moved), "the press spawns them in the order they applied")
    }

    @Test
    fun `a Look recorded from two stacked Local FX spawns them in their apply order`() = lookKeepsOrder(moved = false)

    @Test
    fun `a Look recorded after a move spawns them in the moved order`() = lookKeepsOrder(moved = true)

    @Test
    fun `Update writes a moved order back into the included Look`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (replace, within) = client.stackTwo(moved = false)
        val look = client.recordLook("Stacked", listOf(replace.id, within.id))
        assertEquals(expectedBlends(moved = false), lookFx(look.id).map { it.first })
        awaitDimmer(0, "recorded out of the programmer")

        client.include(lookId = look.id)
        awaitDimmer(withinOnTop, "Include runs them in the Look's order")
        val included = fx.getActiveFx().single { it.blendMode.name == "MULTIPLY" }
        fx.moveLocalFx(included.id, aboveId = null)
        awaitDimmer(replaceOnTop, "moved in the programmer")

        val updated = client.update()
        assertEquals(2, assertNotNull(updated.lookResult).fxWritten, "both took a new place")
        assertEquals(expectedBlends(moved = true).mapIndexed { i, blend -> blend to i }, lookFx(look.id))

        clearStage("cleared")
        client.pressLook(look.id)
        awaitDimmer(replaceOnTop, "the Look spawns them in the order Update wrote")
    }

    // ── A cue ───────────────────────────────────────────────────────────────────────────────

    private fun cueRecordKeepsOrder(moved: Boolean) = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        client.stackTwo(moved)

        val cueId = client.record("CREATE", name = "Stacked").cue.id
        assertEquals(expectedBlends(moved).mapIndexed { i, blend -> blend to i }, cueFx(cueId))

        clearStage("the programmer let go")
        client.applyCue(cueId)
        awaitDimmer(expected(moved), "the cue spawns them in the order they applied")
    }

    @Test
    fun `a cue recorded from two stacked Local FX spawns them in their apply order`() = cueRecordKeepsOrder(moved = false)

    @Test
    fun `a cue recorded after a move spawns them in the moved order`() = cueRecordKeepsOrder(moved = true)

    /** Record CREATE unmoved, then the same two FX moved and recorded into the cue under [mode]. */
    private fun cueRerecordTakesMovedOrder(mode: String) = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val (replace, within) = client.stackTwo(moved = false)
        val cueId = client.record("CREATE", name = "Stacked").cue.id
        assertEquals(expectedBlends(moved = false), cueFx(cueId).map { it.first })
        val uuids = transaction(state.database) { DaoCue.findById(cueId)!!.adHocFxInOrder().map { it.uuid } }

        fx.moveLocalFx(within.id, aboveId = replace.id)
        awaitDimmer(replaceOnTop, "moved in the programmer")
        val recorded = client.record(mode, cueId = cueId)
        assertEquals(2, recorded.fxWritten, "both took a new place")
        assertEquals(expectedBlends(moved = true).mapIndexed { i, blend -> blend to i }, cueFx(cueId))
        assertEquals(
            uuids.reversed(),
            transaction(state.database) { DaoCue.findById(cueId)!!.adHocFxInOrder().map { it.uuid } },
            "the same rows, moved",
        )

        clearStage("the programmer let go")
        client.applyCue(cueId)
        awaitDimmer(replaceOnTop, "the cue spawns them in the moved order")
    }

    @Test
    fun `Record MERGE writes the moved order into the cue`() = cueRerecordTakesMovedOrder("MERGE")

    @Test
    fun `Record UPDATE_EXISTING writes the moved order into the cue`() = cueRerecordTakesMovedOrder("UPDATE_EXISTING")

    @Test
    fun `Record MERGE puts the recorded FX above the cue's own, in apply order`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val uv = client.press(
            ProgrammerFxRequest(fxType = "Wave", attribute = "uv", targets = hex),
        )
        val cueId = client.record("CREATE", name = "Stacked").cue.id
        fx.removeFx(uv.id)
        client.stackTwo(moved = true)

        client.record("MERGE", cueId = cueId)
        assertEquals(listOf("OVERRIDE", "MULTIPLY", "OVERRIDE"), cueFx(cueId).map { it.first })
        assertEquals(listOf(0, 1, 2), cueFx(cueId).map { it.second })
        val attributes = transaction(state.database) { DaoCue.findById(cueId)!!.adHocFxInOrder().map { it.attribute } }
        assertEquals(listOf("uv", "dimmer", "dimmer"), attributes, "the cue's own first, the recording's above it")
    }

    @Test
    fun `Update writes a moved order back into the included cue`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        client.stackTwo(moved = false)
        val cueId = client.record("CREATE", name = "Stacked").cue.id
        clearStage("the programmer let go")

        client.include(cueId = cueId)
        awaitDimmer(withinOnTop, "Include runs them in the cue's order")
        val included = fx.getActiveFx().single { it.blendMode.name == "MULTIPLY" }
        fx.moveLocalFx(included.id, aboveId = null)
        awaitDimmer(replaceOnTop, "moved in the programmer")

        val updated = client.update()
        assertEquals(2, updated.results.single().fxWritten, "both took a new place")
        assertEquals(expectedBlends(moved = true).mapIndexed { i, blend -> blend to i }, cueFx(cueId))

        clearStage("cleared")
        client.applyCue(cueId)
        awaitDimmer(replaceOnTop, "the cue spawns them in the order Update wrote")
    }

    // ── An Update that moved nothing ────────────────────────────────────────────────────────

    /**
     * Two dimmer FX and a UV FX: Include ranks the second dimmer FX above the first and the UV FX at
     * the bottom of its own lane, so by priority alone they apply as [dimmer, uv, dimmer]. Only the
     * two dimmer FX interact, and they kept their order, so Update writes nothing — not even to close
     * the gaps in their stored orders.
     */
    private val twoLanes = listOf(
        lookFx("Wave", 200, "OVERRIDE").copy(sortOrder = 0),
        lookFx("Flicker", 128, "MULTIPLY").copy(sortOrder = 5),
        lookFx("Wave", 0, "OVERRIDE").copy(attribute = "uv", parameters = emptyMap(), sortOrder = 9),
    )

    @Test
    fun `an Update of an included Look that moved nothing writes no FX`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val created = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Lanes", fx = twoLanes))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val lookId = created.body<LookDetails>().id
        val stored = lookFx(lookId)
        assertEquals(listOf(0, 5, 9), stored.map { it.second })

        client.include(lookId = lookId)
        assertEquals(3, fx.getActiveFx().size)
        assertEquals(0, assertNotNull(client.update().lookResult).fxWritten)
        assertEquals(stored, lookFx(lookId))
    }

    @Test
    fun `an Update of an included cue that moved nothing writes no FX`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val effects = twoLanes.map {
            CueAdHocFxDto(
                // The family Record infers, so the round trip changes nothing but what the test is about.
                targetType = it.targetType, targetKey = it.targetKey, fxType = it.fxType,
                family = categoryFromAttributeName(it.attribute!!),
                attribute = it.attribute, cycle = it.cycle, blendMode = it.blendMode,
                order = it.order, parameters = it.parameters, sortOrder = it.sortOrder,
            )
        }
        val created = client.post("/api/rest/projects/$projectId/cues") {
            contentType(ContentType.Application.Json)
            setBody(NewCue(name = "Lanes", adHocFx = effects))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val cueId = created.body<CueDetails>().id
        val stored = cueFx(cueId)
        assertEquals(listOf(0, 5, 9), stored.map { it.second })

        client.include(cueId = cueId)
        assertEquals(3, fx.getActiveFx().size)
        assertEquals(0, client.update().results.single().fxWritten)
        assertEquals(stored, cueFx(cueId))
    }

    // ── Explicit lists ──────────────────────────────────────────────────────────────────────

    private fun lookFx(fxType: String, value: Int, blendMode: String) = LookFxDto(
        targetType = TargetRef.Fixture.TYPE, targetKey = "hex-1", fxType = fxType, family = "intensity",
        attribute = "dimmer", cycle = 4.0, blendMode = blendMode, order = "NORMAL",
        parameters = flat(value),
    )

    @Test
    fun `the Look REST body keeps its list's order, on create and on a reordering PUT`() = testApplication {
        mountTestApp(state)
        seedRig()
        val client = jsonClient()
        val replace = lookFx("Wave", 200, "OVERRIDE")
        val within = lookFx("Flicker", 128, "MULTIPLY")

        val created = client.post("/api/rest/projects/$projectId/looks") {
            contentType(ContentType.Application.Json)
            setBody(CreateLookRequest(name = "Listed", fx = listOf(within, replace)))
        }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val lookId = created.body<LookDetails>().id
        assertEquals(listOf("MULTIPLY", "OVERRIDE"), lookFx(lookId).map { it.first })
        client.pressLook(lookId)
        awaitDimmer(replaceOnTop, "listed Within first, the Replace applies on top")
        client.pressLook(lookId)
        awaitDimmer(0, "off")

        val uuids = transaction(state.database) { DaoLook.findById(lookId)!!.fxInOrder().map { it.uuid } }
        val put = client.put("/api/rest/projects/$projectId/looks/$lookId") {
            contentType(ContentType.Application.Json)
            setBody(mapOf("fx" to listOf(replace, within)))
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(listOf("OVERRIDE" to 0, "MULTIPLY" to 1), lookFx(lookId))
        assertEquals(
            uuids.reversed(),
            transaction(state.database) { DaoLook.findById(lookId)!!.fxInOrder().map { it.uuid } },
            "the same rows, in the new list's order",
        )
        client.pressLook(lookId)
        awaitDimmer(withinOnTop, "listed Within last, it applies on top")
    }

    @Test
    fun `create_look keeps its list's order`() {
        seedRig()
        val json = Json { encodeDefaults = false }
        val effects = listOf(lookFx("Flicker", 128, "MULTIPLY"), lookFx("Wave", 200, "OVERRIDE"))
            .joinToString(",", "[", "]") { json.encodeToString(LookFxDto.serializer(), it) }
        val result = runBlocking {
            AiTools(state).executeTool("create_look", Json.parseToJsonElement("""{"name":"Tool","fx":$effects}""").jsonObject)
        }
        assertTrue(result.success, result.result)
        val stored = transaction(state.database) {
            DaoLook.find { DaoLooks.name eq "Tool" }.single().fxInOrder().map { it.blendMode }
        }
        assertEquals(listOf("MULTIPLY", "OVERRIDE"), stored)
    }
}
