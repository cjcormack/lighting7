package uk.me.cormack.lighting7.routes

import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
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
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.fx.CueHold
import uk.me.cormack.lighting7.fx.CueStackManager
import uk.me.cormack.lighting7.fx.takeLooseCue
import uk.me.cormack.lighting7.models.CueSpeedMasterDto
import uk.me.cormack.lighting7.models.CueStackType
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStackEntry
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSpeedMaster
import uk.me.cormack.lighting7.models.DaoSpeedMasters
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.ensureDefaultSpeedMasters
import uk.me.cormack.lighting7.models.nextOrdinal
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.entryIdOf
import uk.me.cormack.lighting7.testsupport.inStack
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A cue's speed masters (venue-visit plan S16, D15, D20): set on every GO into one of its entries —
 * GO TO, GO BACK, auto-advance and a cue another stack already holds included — and on every loose
 * press, never on a reload's re-GO; a tap wins until the next cue; a follower or a deleted master is
 * skipped without failing the GO. The list is written whole by the cue's POST, PUT and PATCH, and
 * copies carry it.
 */
class CueSpeedMastersTest : RouteIntegrationTest() {

    private val manager get() = state.show.cueStackManager

    /** The project's four default masters' uuids, by index, the bank loaded with them. */
    private fun masters(): Map<Int, String> {
        val byIndex = transaction(state.database) {
            ensureDefaultSpeedMasters(DaoProject[projectId]).associate { it.masterIndex to it.uuid.toString() }
        }
        state.show.reloadSpeedMasters()
        return byIndex
    }

    private fun bpm(uuid: String): Double =
        state.show.speedMasterBank.masterStates().single { it.uuid.toString() == uuid }.bpm

    private fun tap(uuid: String, bpm: Double) {
        state.show.speedMasterBank.setBpm(java.util.UUID.fromString(uuid), bpm, SpeedMasterSource.TAP)
    }

    private fun stack(name: String): Int = transaction(state.database) {
        DaoCueStack.new {
            project = DaoProject[projectId]; this.name = name; loop = false
            type = CueStackType.STACK.name; sortOrder = 0
        }.id.value
    }

    /** A cue setting [tempos] (master uuid to BPM), placed at the end of [stackId] when given. */
    private fun cue(name: String, stackId: Int?, vararg tempos: Pair<String, Double>): Int = transaction(state.database) {
        val cue = DaoCue.new {
            project = DaoProject[projectId]; this.name = name
            speedMasters = tempos.map { (uuid, bpm) -> CueSpeedMasterDto(uuid, bpm) }
        }
        stackId?.let { cue.inStack(DaoCueStack[it]) }
        cue.id.value
    }

    /** A second entry for [cueId], at the end of [stackId]: the cue shared. */
    private fun share(cueId: Int, stackId: Int): Int = transaction(state.database) {
        val stack = DaoCueStack[stackId]
        DaoCueStackEntry.new { this.stack = stack; cue = DaoCue[cueId]; ordinal = nextOrdinal(stack) }.id.value
    }

    private fun entriesOf(cueId: Int): List<Int> = transaction(state.database) {
        DaoCueStackEntry.all().filter { it.cue?.id?.value == cueId }.map { it.id.value }
    }

    @Test
    fun `GO, GO TO, GO BACK and auto-advance each set the cue's masters, and a tap wins until the next cue`() = runBlocking {
        val m = masters()
        val s = stack("Act 1")
        val q1 = cue("Q1", s, m.getValue(2) to 100.0)
        val q2 = cue("Q2", s, m.getValue(2) to 140.0, m.getValue(1) to 96.0)
        val q3 = cue("Q3", s)

        manager.goToEntry(state, s, state.entryIdOf(q1))
        assertEquals(100.0, bpm(m.getValue(2)), "GO TO")
        manager.go(state, s)
        assertEquals(140.0, bpm(m.getValue(2)), "GO")
        assertEquals(96.0, bpm(m.getValue(1)))

        // A tap wins until the next cue that sets that master; Q3 sets none.
        tap(m.getValue(2), 90.0)
        manager.go(state, s)
        assertEquals(90.0, bpm(m.getValue(2)), "a cue that sets no master leaves the tap")

        manager.advanceStack(state, s, CueStackManager.AdvanceDirection.BACKWARD)
        assertEquals(140.0, bpm(m.getValue(2)), "GO BACK onto Q2")

        // Auto-advance: Q1 follows into Q2 after 50 ms.
        transaction(state.database) {
            DaoCueStackEntry[state.entryIdOf(q1)].apply { autoAdvance = true; autoAdvanceDelay = Duration.ofMillis(50) }
        }
        tap(m.getValue(2), 60.0)
        manager.goToEntry(state, s, state.entryIdOf(q1))
        assertEquals(100.0, bpm(m.getValue(2)))
        withTimeout(3_000) { while (manager.getActiveCueId(s) != q2) delay(10) }
        assertEquals(140.0, bpm(m.getValue(2)), "the follow into Q2 set its tempo")
        assertEquals(q3, transaction(state.database) { DaoCue[q3].id.value })
    }

    @Test
    fun `a GO into a cue another stack already holds still sets them, and a shared cue sets the same tempo from either stack`() = runBlocking {
        val m = masters()
        val a = stack("A")
        val b = stack("B")
        val shared = cue("Shared", a, m.getValue(3) to 132.0)
        share(shared, b)
        val (onA, onB) = entriesOf(shared).let { it[0] to it[1] }

        manager.goToEntry(state, a, onA)
        assertEquals(132.0, bpm(m.getValue(3)))
        tap(m.getValue(3), 70.0)
        // B's GO into the cue A already holds brings nothing on, and still sets the tempo.
        manager.goToEntry(state, b, onB)
        assertEquals(132.0, bpm(m.getValue(3)), "the second stack's GO re-asserts it")
        assertEquals(listOf(a, b).sorted(), state.cueHoldsFrame.value.cues.single { it.cueId == shared }.holds.filterIsInstance<CueHold.Stack>().map { it.stackId }.sorted())
    }

    @Test
    fun `a loose press sets them, and a reload's re-GO and re-press set none`() = runBlocking {
        val m = masters()
        val s = stack("Act 1")
        val q1 = cue("Q1", s, m.getValue(2) to 150.0)
        val loose = cue("Loose", null, m.getValue(4) to 110.0)

        manager.holds.takeLooseCue(state, loose)
        assertEquals(110.0, bpm(m.getValue(4)), "a loose press")
        manager.goToEntry(state, s, state.entryIdOf(q1))
        assertEquals(150.0, bpm(m.getValue(2)))

        // The operator taps both after the cues came on; a reload carries the cues across and sets neither.
        tap(m.getValue(2), 75.0)
        tap(m.getValue(4), 55.0)
        state.projectManager.switchProject(projectId, reload = true)
        assertEquals(q1, state.show.cueStackManager.getActiveCueId(s), "the stack's cue came back")
        assertTrue(state.show.cueStackManager.holds.looseHeld(loose), "the loose hold came back")
        assertEquals(75.0, bpm(m.getValue(2)), "a reload's re-GO sets no tempo")
        assertEquals(55.0, bpm(m.getValue(4)), "a reload's re-press sets no tempo")
    }

    @Test
    fun `a follower or a deleted master is skipped without failing the GO`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val m = masters()
        transaction(state.database) {
            DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }.single { it.masterIndex == 3 }.apply { followNum = 1; followDen = 2 }
        }
        state.show.reloadSpeedMasters()
        val s = stack("Act 1")
        val q1 = cue("Q1", s, m.getValue(3) to 99.0, m.getValue(4) to 123.0, m.getValue(2) to 150.0)

        // Master 4 is in use by the cue's tempo: refused, then deleted anyway.
        val master4 = transaction(state.database) { DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }.single { it.masterIndex == 4 }.id.value }
        val refused = client.delete("/api/rest/projects/$projectId/speed-masters/$master4")
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue("setting its tempo" in refused.bodyAsText(), refused.bodyAsText())
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$projectId/speed-masters/$master4?force=true").status)

        val follower = bpm(m.getValue(3))
        manager.goToEntry(state, s, state.entryIdOf(q1))
        assertEquals(q1, manager.getActiveCueId(s), "the GO went")
        assertEquals(150.0, bpm(m.getValue(2)), "the rest of the list was set")
        assertEquals(follower, bpm(m.getValue(3)), "the follower kept its derived tempo")
    }

    @Test
    fun `the list is a whole write on the cue's POST, PUT and PATCH, every problem at once`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val m = masters()
        suspend fun send(method: String, path: String, body: String) = when (method) {
            "POST" -> client.post("/api/rest/projects/$projectId/$path") { contentType(ContentType.Application.Json); setBody(body) }
            "PUT" -> client.put("/api/rest/projects/$projectId/$path") { contentType(ContentType.Application.Json); setBody(body) }
            else -> client.patch("/api/rest/projects/$projectId/$path") { contentType(ContentType.Application.Json); setBody(body) }
        }

        val created = send("POST", "cues", """{"name":"Q1","speedMasters":[{"speedMasterUuid":"${m[2]}","bpm":128}]}""")
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val id = created.body<CueDetails>().id
        assertEquals(listOf(CueSpeedMasterDto(m.getValue(2), 128.0)), created.body<CueDetails>().speedMasters)

        val patched = send("PATCH", "cues/$id", """{"speedMasters":[{"speedMasterUuid":"${m[3]}","bpm":90.5},{"speedMasterUuid":"${m[1]}","bpm":120}]}""")
        assertEquals(HttpStatusCode.OK, patched.status, patched.bodyAsText())
        assertEquals(listOf(m.getValue(3) to 90.5, m.getValue(1) to 120.0), patched.body<CueDetails>().speedMasters.map { it.speedMasterUuid to it.bpm })
        // A PATCH without the list leaves it.
        send("PATCH", "cues/$id", """{"notes":"n"}""")
        assertEquals(2, client.get("/api/rest/projects/$projectId/cues/$id").body<CueDetails>().speedMasters.size)

        val bad = send(
            "PATCH", "cues/$id",
            """{"speedMasters":[{"speedMasterUuid":"${java.util.UUID.randomUUID()}","bpm":100},{"speedMasterUuid":"${m[2]}","bpm":500},""" +
                """{"speedMasterUuid":"${m[1]}","bpm":100},{"speedMasterUuid":"${m[1]}","bpm":110},{"bpm":100,"tempo":1}]}""",
        )
        assertEquals(HttpStatusCode.BadRequest, bad.status)
        val error = Json.parseToJsonElement(bad.bodyAsText()).jsonObject["error"].toString()
        for (problem in listOf("speedMasters[0].speedMasterUuid names no speed master", "bpm must be between 20 and 300", "names a master twice", "speedMasters[4].speedMasterUuid is required", "unknown field 'tempo'")) {
            assertTrue(problem in error, "$problem in $error")
        }
        assertEquals(2, client.get("/api/rest/projects/$projectId/cues/$id").body<CueDetails>().speedMasters.size, "nothing written")

        val put = send("PUT", "cues/$id", """{"name":"Q1","speedMasters":[{"speedMasterUuid":"${m[4]}","bpm":64}]}""")
        assertEquals(listOf(m.getValue(4)), put.body<CueDetails>().speedMasters.map { it.speedMasterUuid })
        assertEquals(emptyList(), send("PATCH", "cues/$id", """{"speedMasters":[]}""").body<CueDetails>().speedMasters)
    }

    @Test
    fun `Duplicate, the cross-project copy and copy_to_project carry the list, a master mapped by index`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val m = masters()
        val s = stack("Act 1")
        val q1 = cue("Q1", s, m.getValue(2) to 128.0, m.getValue(4) to 64.0)
        val (other, otherMasters) = transaction(state.database) {
            val project = DaoProject.new { name = "Other"; description = "copy target"; isCurrent = false }
            // Three masters only: the copy has nowhere to put master 4's tempo.
            project.id.value to listOf(1, 2, 3).associateWith { i -> DaoSpeedMaster.new { this.project = project; masterIndex = i; name = "Other $i" }.uuid.toString() }
        }

        val dup = client.post("/api/rest/projects/$projectId/cues/$q1/duplicate") { contentType(ContentType.Application.Json); setBody(DuplicateCueRequest(s)) }
        val dupId = dup.body<DuplicateCueResponse>().cueId
        assertEquals(listOf(m.getValue(2) to 128.0, m.getValue(4) to 64.0), speedMastersOf(dupId))

        val copied = client.post("/api/rest/projects/$projectId/cues/$q1/copy") { contentType(ContentType.Application.Json); setBody(CopyCueRequest(other)) }
        assertEquals(HttpStatusCode.Created, copied.status, copied.bodyAsText())
        assertEquals(listOf(otherMasters.getValue(2) to 128.0), speedMastersOf(copied.body<CopyCueResponse>().cueId))

        val tools = SetupTools(state, AiTools(state))
        val result = runBlocking {
            tools.executeTool("copy_to_project", Json.parseToJsonElement("""{"fromProjectId":$projectId,"toProjectId":$other,"cueStacks":[$s]}""").jsonObject, caller = "Tester")
        }
        assertTrue(result.success, result.result)
        assertTrue("speed master 4 is not in project $other" in result.result, result.result)
        val copiedQ1 = transaction(state.database) {
            DaoCue.find { (DaoCues.project eq other) and (DaoCues.name eq "Q1") }.toList().maxBy { it.id.value }.id.value
        }
        assertEquals(listOf(otherMasters.getValue(2) to 128.0), speedMastersOf(copiedQ1))
    }

    @Test
    fun `create_cue and build_cue_stack take the list and answer it, every problem at once`() = runBlocking {
        val m = masters()
        val created = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q9","speedMasters":[{"speedMasterUuid":"${m[2]}","bpm":132}]}""").jsonObject,
        )
        assertTrue(created.success, created.result)
        assertTrue("\"speedMasters\"" in created.result && "132" in created.result, created.result)
        val q9 = Json.parseToJsonElement(created.result).jsonObject["cueId"].toString().toInt()
        assertEquals(listOf(m.getValue(2) to 132.0), speedMastersOf(q9))

        val refused = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q10","speedMasters":[{"speedMasterUuid":"${m[2]}","bpm":5},{"speedMasterUuid":"nope","bpm":100}]}""").jsonObject,
        )
        assertTrue(!refused.success && "bpm must be between" in refused.result && "is not a uuid" in refused.result, refused.result)

        val built = SetupTools(state, AiTools(state)).executeTool(
            "build_cue_stack",
            Json.parseToJsonElement(
                """{"stackName":"Act 3","cues":[{"number":"1","name":"Dawn","speedMasters":[{"speedMasterUuid":"${m[3]}","bpm":72}]}]}""",
            ).jsonObject,
            caller = "Tester",
        )
        assertTrue(built.success, built.result)
        val dawn = transaction(state.database) { DaoCue.find { (DaoCues.project eq projectId) and (DaoCues.name eq "Dawn") }.single().id.value }
        assertEquals(listOf(m.getValue(3) to 72.0), speedMastersOf(dawn))
    }

    @Test
    fun `the tools name a master by its index, in a project that is not current too`() = runBlocking {
        val m = masters()
        val created = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q11","speedMasters":[{"masterIndex":2,"bpm":140}]}""").jsonObject,
        )
        assertTrue(created.success, created.result)
        val q11 = Json.parseToJsonElement(created.result).jsonObject["cueId"].toString().toInt()
        assertEquals(listOf(m.getValue(2) to 140.0), speedMastersOf(q11))

        val refused = AiTools(state).executeTool(
            "create_cue",
            Json.parseToJsonElement("""{"name":"Q12","speedMasters":[{"masterIndex":9,"bpm":100},{"masterIndex":1,"speedMasterUuid":"${m[1]}","bpm":100},{"bpm":100}]}""").jsonObject,
        )
        assertTrue(!refused.success, refused.result)
        for (problem in listOf("masterIndex names no speed master", "not both", "needs a master")) {
            assertTrue(problem in refused.result, "$problem in ${refused.result}")
        }

        val (other, otherMasters) = transaction(state.database) {
            val project = DaoProject.new { name = "Other"; description = "build target"; isCurrent = false }
            project.id.value to ensureDefaultSpeedMasters(project).associate { it.masterIndex to it.uuid.toString() }
        }
        val built = SetupTools(state, AiTools(state)).executeTool(
            "build_cue_stack",
            Json.parseToJsonElement(
                """{"projectId":$other,"stackName":"Act 1","cues":[{"number":"1","name":"Dusk","speedMasters":[{"masterIndex":3,"bpm":84}]}]}""",
            ).jsonObject,
            caller = "Tester",
        )
        assertTrue(built.success, built.result)
        val dusk = transaction(state.database) { DaoCue.find { (DaoCues.project eq other) and (DaoCues.name eq "Dusk") }.single().id.value }
        assertEquals(listOf(otherMasters.getValue(3) to 84.0), speedMastersOf(dusk))
    }

    @Test
    fun `a PATCH naming a list on a cue that is not there is a 404, not a 400 about the list`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val response = client.patch("/api/rest/projects/$projectId/cues/99999") {
            contentType(ContentType.Application.Json)
            setBody("""{"speedMasters":[{"speedMasterUuid":"${java.util.UUID.randomUUID()}","bpm":120}]}""")
        }
        assertEquals(HttpStatusCode.NotFound, response.status, response.bodyAsText())
    }

    @Test
    fun `a master already at the cue's tempo is left as it is`() = runBlocking {
        val m = masters()
        val s = stack("Act 1")
        val q1 = cue("Q1", s, m.getValue(2) to 128.0)
        tap(m.getValue(2), 128.0)
        manager.goToEntry(state, s, state.entryIdOf(q1))
        val master = state.show.speedMasterBank.masterStates().single { it.uuid.toString() == m[2] }
        assertEquals(128.0, master.bpm)
        assertEquals(SpeedMasterSource.TAP, master.source, "no write: the tap's tempo was already the cue's")
    }

    private fun speedMastersOf(cueId: Int): List<Pair<String, Double>> =
        transaction(state.database) { DaoCue[cueId].speedMasters.map { it.speedMasterUuid to it.bpm } }
}
