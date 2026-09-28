package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.mcp.McpProtocol
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.show.RiggingPose
import uk.me.cormack.lighting7.show.StagePoint
import uk.me.cormack.lighting7.show.beamDirection
import uk.me.cormack.lighting7.show.worldPosition
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import java.util.UUID
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/programmer/aim` and the `aim_fixtures` tool over it: each head's pan/tilt
 * (and fine pan/tilt) land as programmer entries, from its placement composed through its rigging
 * and its mount; everything that cannot be aimed is skipped by name; `dryRun` writes nothing.
 */
class ProgrammerAimRouteTest : RouteIntegrationTest() {

    private fun aim() = "/api/rest/projects/$projectId/programmer/aim"

    private val tools by lazy { AiTools(state) }

    /** Place an already-seeded patch: world coordinates, or an offset on [riggingName]. */
    private fun place(key: String, x: Double, y: Double, z: Double, pitchDeg: Double? = 180.0, yawDeg: Double? = null, riggingName: String? = null) {
        transaction(state.database) {
            val patch = DaoFixturePatch.find { DaoFixturePatches.key eq key }.first()
            patch.stageX = x
            patch.stageY = y
            patch.stageZ = z
            patch.basePitchDeg = pitchDeg
            patch.baseYawDeg = yawDeg
            if (riggingName != null) patch.rigging = DaoRigging.all().first { it.name == riggingName }
        }
    }

    private val rigPose = RiggingPose(x = -2.0, y = 6.0, z = 7.0, yawDeg = 30.0, pitchDeg = 0.0, rollDeg = 0.0)

    private fun seed() {
        // Hung spots: the 15ch has fine pan/tilt, the 5ch has none. 540° pan, 210° tilt both.
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-15ch", "spot-fine", 1)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-coarse", 20)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-truss", 30)
        LocateTestSupport.seedFixture(state, projectId, "fusion-100-spot-mkii-5ch", "spot-unplaced", 40)
        // A floor wash: tilt 0–180°, so it cannot look at the deck underneath itself.
        LocateTestSupport.seedFixture(state, projectId, "imgstageline-wash-42led-13ch", "floor-wash", 60)
        LocateTestSupport.seedFixture(state, projectId, "generic-dimmer", "par-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "spots", "spot-fine", "spot-coarse")
        transaction(state.database) {
            DaoRigging.new {
                project = DaoProject.findById(projectId)!!
                name = "LX1"
                positionX = rigPose.x; positionY = rigPose.y; positionZ = rigPose.z
                yawDeg = rigPose.yawDeg; pitchDeg = rigPose.pitchDeg; rollDeg = rigPose.rollDeg
            }
        }
        place("spot-fine", 0.0, 4.0, 6.0)
        place("spot-coarse", 3.0, 4.0, 6.0)
        place("spot-truss", 1.5, 0.0, -0.2, riggingName = "LX1")
        place("floor-wash", 0.0, 8.0, 0.0, pitchDeg = 0.0)
        place("par-1", -3.0, 0.0, 6.0, pitchDeg = 45.0)
        LocateTestSupport.reloadFixtures(state, projectId)
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)
    private fun group(name: String) = CueTargetDto("group", name)

    private suspend fun HttpClient.post(request: AimRequest): HttpResponse =
        post(aim()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.run(request: AimRequest): AimResponse {
        val resp = post(request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    /** The winning programmer value for (fixture, property), or null. */
    private fun entry(key: String, property: String): CueAssignmentResolver.PropertyValue? =
        state.show.programmerStore.entries()
            .firstOrNull { it.fixtureKey == key && it.propertyName == property }
            ?.slots?.firstOrNull()?.value?.resolved

    /** The drawn beam of a 540°/210° spot aimed at [panDeg] / [tiltDeg] travel, as a stage direction. */
    private fun spotBeam(yawDeg: Double?, pitchDeg: Double?, panDeg: Double, tiltDeg: Double) =
        beamDirection(yawDeg, pitchDeg, panDeg - 270.0, tiltDeg - 105.0)

    private fun assertPointsAt(from: StagePoint, to: StagePoint, beam: StagePoint, degrees: Double) {
        val dx = to.x - from.x; val dy = to.y - from.y; val dz = to.z - from.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        val cos = (beam.x * dx + beam.y * dy + beam.z * dz) / l
        val off = Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1.0, 1.0)))
        assertTrue(off < degrees, "beam $beam is $off° off the line from $from to $to")
    }

    @Test
    fun `a hung spot aimed at the deck beneath it lands at mid-travel, fine channels and all`() = testApplication {
        seed()
        mountTestApp(state)
        val out = jsonClient().run(AimRequest(targets = listOf(fixture("spot-fine")), x = 0.0, y = 4.0, z = 0.0))

        assertEquals(listOf("spot-fine"), out.written.map { it.target.key })
        assertEquals(270.0, out.written.single().panDeg)
        assertEquals(105.0, out.written.single().tiltDeg)
        // Mid-travel is DMX 127.5 on each axis: coarse 127, fine 128 — 16-bit, not rounded to a coarse step.
        assertEquals("127,127", out.written.single().value)
        assertEquals(CueAssignmentResolver.PropertyValue.Position(127u, 127u), entry("spot-fine", "position"))
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(128u), entry("spot-fine", "panFine"))
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(128u), entry("spot-fine", "tiltFine"))
    }

    @Test
    fun `a group aims every member from where each hangs`() = testApplication {
        seed()
        mountTestApp(state)
        val point = StagePoint(-1.0, 2.0, 1.7)
        val out = jsonClient().run(AimRequest(targets = listOf(group("spots")), x = point.x, y = point.y, z = point.z))

        assertEquals(listOf("spot-fine", "spot-coarse"), out.written.map { it.target.key })
        assertTrue(out.skipped.isEmpty(), out.skipped.toString())
        // Degrees are answered to a tenth, so the beam is within a fraction of a degree of the line.
        val coarse = out.written.first { it.target.key == "spot-coarse" }
        assertPointsAt(StagePoint(3.0, 4.0, 6.0), point, spotBeam(null, 180.0, coarse.panDeg, coarse.tiltDeg), 0.2)
        // A head with no fine channel gets no fine entry.
        assertNotNull(entry("spot-coarse", "position"))
        assertNull(entry("spot-coarse", "panFine"))
    }

    @Test
    fun `a head on a rigging is aimed from its world position`() = testApplication {
        seed()
        mountTestApp(state)
        val from = worldPosition(1.5, 0.0, -0.2, rigPose)!!
        // Straight down from where the truss puts it: both axes at mid-travel.
        val out = jsonClient().run(AimRequest(targets = listOf(fixture("spot-truss")), x = from.x, y = from.y, z = 0.0))
        val written = out.written.single()
        assertEquals(270.0, written.panDeg)
        assertEquals(105.0, written.tiltDeg)
    }

    @Test
    fun `everything that cannot be aimed is skipped by name`() = testApplication {
        seed()
        mountTestApp(state)
        val out = jsonClient().run(
            AimRequest(
                targets = listOf(fixture("par-1"), fixture("spot-unplaced"), fixture("floor-wash"), fixture("nope"), fixture("spot-fine")),
                x = 0.0, y = 8.0, z = -0.5,
            ),
        )
        val reasons = out.skipped.associate { it.target.key to it.reason }
        assertEquals(setOf("par-1", "spot-unplaced", "floor-wash", "nope"), reasons.keys)
        assertTrue(reasons.getValue("par-1").contains("no pan or tilt"), reasons.toString())
        assertTrue(reasons.getValue("spot-unplaced").contains("not placed"), reasons.toString())
        assertTrue(reasons.getValue("floor-wash").startsWith("out of reach"), reasons.toString())
        assertEquals("not patched", reasons.getValue("nope"))
        assertEquals(listOf("spot-fine"), out.written.map { it.target.key })
        assertNull(entry("floor-wash", "position"))
        assertNull(entry("par-1", "position"))
    }

    @Test
    fun `no targets or an off-stage point is refused`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val empty = client.post(AimRequest(targets = emptyList(), x = 0.0, y = 0.0, z = 0.0))
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertTrue(empty.bodyAsText().contains(CODE_AIM_NEEDS_SELECTION))
        val far = client.post(AimRequest(targets = listOf(fixture("spot-fine")), x = 0.0, y = 0.0, z = 1000.0))
        assertEquals(HttpStatusCode.BadRequest, far.status)
        assertTrue(far.bodyAsText().contains(CODE_AIM_INVALID))
        // Nothing to aim is the more fundamental refusal, whatever else is wrong with the request.
        val both = client.post(AimRequest(targets = emptyList(), x = 0.0, y = 0.0, z = 1000.0))
        assertEquals(HttpStatusCode.BadRequest, both.status)
        assertTrue(both.bodyAsText().contains(CODE_AIM_NEEDS_SELECTION), both.bodyAsText())
        assertTrue(state.show.programmerStore.entries().isEmpty())
    }

    // ─── aim_fixtures ──────────────────────────────────────────────────────

    private fun call(arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool("aim_fixtures", Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    @Test
    fun `aim_fixtures is offered over MCP and writes`() {
        val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")
        val listed = runBlocking {
            McpProtocol(state).handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val tool = listed["aim_fixtures"]?.jsonObject
        assertNotNull(tool, "aim_fixtures is offered")
        assertNull(tool["annotations"], "aim_fixtures writes, so it carries no readOnlyHint")
    }

    @Test
    fun `aim_fixtures dryRun answers what would land and writes nothing`() {
        seed()
        val dry = call("""{"targets":[{"type":"group","key":"spots"}],"x":0,"y":4,"z":0,"dryRun":true}""")
        assertTrue(dry.success, dry.result)
        assertTrue(dry.json()["dryRun"]!!.jsonPrimitive.boolean)
        val aimed = dry.json()["aimed"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("spot-fine", "spot-coarse"), aimed.map { it["fixture"]!!.jsonPrimitive.content })
        assertEquals("127,127", aimed.first()["position"]!!.jsonPrimitive.content)
        assertTrue(state.show.programmerStore.entries().isEmpty(), "a dry run writes nothing")

        val wet = call("""{"targets":[{"type":"fixture","key":"spot-fine"},{"type":"fixture","key":"par-1"}],"x":0,"y":4,"z":0}""")
        assertTrue(wet.success, wet.result)
        assertFalse(wet.json()["dryRun"]!!.jsonPrimitive.boolean)
        assertEquals("par-1", wet.json()["skipped"]!!.jsonArray.single().jsonObject["target"]!!.jsonPrimitive.content)
        assertEquals(CueAssignmentResolver.PropertyValue.Position(127u, 127u), entry("spot-fine", "position"))
    }

    @Test
    fun `aim_fixtures refuses a point off any stage`() {
        seed()
        val result = call("""{"targets":[{"type":"fixture","key":"spot-fine"}],"x":0,"y":4,"z":900}""")
        assertFalse(result.success)
        assertTrue(result.description.contains("z must be"), result.description)
    }
}
