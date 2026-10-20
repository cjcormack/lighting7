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
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.show.JOG_DEFAULT_PLANE_Z
import uk.me.cormack.lighting7.show.RiggingPose
import uk.me.cormack.lighting7.show.SignedAim
import uk.me.cormack.lighting7.show.jogLanding
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
    private fun place(
        key: String, x: Double, y: Double, z: Double, pitchDeg: Double? = 180.0, yawDeg: Double? = null,
        riggingName: String? = null, rollDeg: Double? = null,
    ) {
        transaction(state.database) {
            val patch = DaoFixturePatch.find { DaoFixturePatches.key eq key }.first()
            patch.stageX = x
            patch.stageY = y
            patch.stageZ = z
            patch.basePitchDeg = pitchDeg
            patch.baseYawDeg = yawDeg
            patch.baseRollDeg = rollDeg
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
    private fun spotBeam(yawDeg: Double?, pitchDeg: Double?, panDeg: Double, tiltDeg: Double, rollDeg: Double? = null) =
        beamDirection(yawDeg, pitchDeg, panDeg - 270.0, tiltDeg - 105.0, rollDeg)

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
    fun `a rolled head is aimed through its roll`() = testApplication {
        seed()
        // Hung on a side wall: rolled onto its side, so its body axis runs across the stage.
        place("spot-coarse", 6.0, 4.0, 3.0, pitchDeg = 0.0, rollDeg = 90.0)
        mountTestApp(state)
        val point = StagePoint(-1.0, 2.0, 1.7)
        val out = jsonClient().run(AimRequest(targets = listOf(fixture("spot-coarse")), x = point.x, y = point.y, z = point.z))
        val written = out.written.single()
        assertPointsAt(StagePoint(6.0, 4.0, 3.0), point, spotBeam(null, 0.0, written.panDeg, written.tiltDeg, 90.0), 0.2)
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

    // ─── mode = JOG ────────────────────────────────────────────────────────

    /** Where an answered head lands on the head-height plane, from its answered travel degrees. */
    private fun landing(written: AimWriteDto, from: StagePoint, panZero: Double, tiltZero: Double, pitchDeg: Double = 180.0) =
        jogLanding(from, null, pitchDeg, SignedAim(written.panDeg - panZero, written.tiltDeg - tiltZero), JOG_DEFAULT_PLANE_Z)

    private fun seedJogRig() {
        seed()
        // A hung Shehds, its pan's front declared at 180°, and a spot moved out front of house.
        LocateTestSupport.seedFixture(state, projectId, "shehds-led19-rgbw-24ch", "shehds", 120)
        place("shehds", -3.0, 2.5, 6.0)
        place("spot-coarse", 0.0, -3.0, 7.0)
        LocateTestSupport.reloadFixtures(state, projectId)
        state.show.fixtures.patchListChanged()
    }

    private val jogFrom = mapOf(
        "shehds" to StagePoint(-3.0, 2.5, 6.0),
        "spot-fine" to StagePoint(0.0, 4.0, 6.0),
        "spot-coarse" to StagePoint(0.0, -3.0, 7.0),
    )

    /** Each head's travel zero: the spots' centres, the Shehds' declared pan front. */
    private val jogZero = mapOf("shehds" to (180.0 to 135.0), "spot-fine" to (270.0 to 105.0), "spot-coarse" to (270.0 to 105.0))

    /** Focus the three heads somewhere on the plane, as a jog finds them. */
    private suspend fun HttpClient.jogRig() {
        for ((key, point) in listOf("shehds" to StagePoint(-2.0, 2.2, 1.7), "spot-fine" to StagePoint(0.0, 3.5, 1.7), "spot-coarse" to StagePoint(0.6, 1.4, 1.7))) {
            run(AimRequest(targets = listOf(fixture(key)), x = point.x, y = point.y, z = point.z))
        }
    }

    /**
     * [coarseSlack] is the tolerance for a head with no fine channels when the jog starts from what it
     * was last written: its coarse byte is a 2° step of a 540° pan, up to a fifth of a metre here.
     */
    private fun assertMoved(before: AimResponse, after: AimResponse, dx: Double, dy: Double, coarseSlack: Double = 0.05) {
        val was = before.written.associateBy { it.target.key }
        assertEquals(jogFrom.keys, after.written.map { it.target.key }.toSet(), after.skipped.toString())
        for (written in after.written) {
            val key = written.target.key
            val (panZero, tiltZero) = jogZero.getValue(key)
            val from = jogFrom.getValue(key)
            val start = landing(was.getValue(key), from, panZero, tiltZero)
            val end = landing(written, from, panZero, tiltZero)
            assertTrue(start.onPlane && end.onPlane, key)
            // Answered to a tenth of a degree: a centimetre or so at these throws.
            val tolerance = if (after.base.single { it.target.key == key }.panFine == null) coarseSlack else 0.05
            assertEquals(start.point.x + dx, end.point.x, tolerance, "$key x")
            assertEquals(start.point.y + dy, end.point.y, tolerance, "$key y")
        }
    }

    private fun jog(dx: Double, dy: Double, base: List<AimBaseDto>? = null, near: List<AimNearDto>? = null, write: Boolean = true) =
        AimRequest(targets = jogFrom.keys.map(::fixture), mode = "JOG", dx = dx, dy = dy, base = base, near = near, write = write)

    @Test
    fun `a jog moves every head's landing point the same distance, from where the gesture started`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        client.jogRig()
        val start = client.run(jog(0.0, 0.0))
        assertEquals(jogFrom.keys, start.base.map { it.target.key }.toSet())
        // The Shehds and the 15ch spot have fine channels, and the base carries them.
        assertNotNull(start.base.single { it.target.key == "shehds" }.panFine)
        assertNull(start.base.single { it.target.key == "spot-coarse" }.panFine)

        // Towards SL and upstage: every landing point moves by the same metres.
        val moved = client.run(jog(1.0, 0.5, base = start.base))
        assertMoved(start, moved, 1.0, 0.5)
        assertEquals(start.base, moved.base, "a base sent is the base answered")
        // A later frame of the same gesture is measured from its start, not from the last frame.
        assertMoved(start, client.run(jog(2.0, 1.0, base = start.base)), 2.0, 1.0)
        // With no base the desk reads Local, which the last frame wrote: a new gesture from there.
        assertMoved(start, client.run(jog(-1.0, 0.0)), 1.0, 1.0, coarseSlack = 0.3)
        // Fine channels are written, as an aim writes them.
        assertNotNull(entry("shehds", "panFine"))
    }

    @Test
    fun `near keeps a head on the side it is on`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        client.jogRig()
        val start = client.run(jog(0.0, 0.0))
        val plain = client.run(jog(0.4, 0.0, base = start.base)).written.single { it.target.key == "spot-fine" }
        // The same point, reached from the other family: pan half a turn round and tilt mirrored.
        val other = AimNearDto(fixture("spot-fine"), plain.panDeg + if (plain.panDeg < 270.0) 180.0 else -180.0, 210.0 - plain.tiltDeg)
        val steered = client.run(jog(0.4, 0.0, base = start.base, near = listOf(other))).written.single { it.target.key == "spot-fine" }
        assertEquals(other.panDeg, steered.panDeg, 0.2)
        assertEquals(other.tiltDeg, steered.tiltDeg, 0.2)
        val from = jogFrom.getValue("spot-fine")
        assertEquals(landing(plain, from, 270.0, 105.0).point.x, landing(steered, from, 270.0, 105.0).point.x, 0.05)
    }

    @Test
    fun `write false answers the jog and writes nothing`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        client.jogRig()
        val start = client.run(jog(0.0, 0.0))
        val before = state.show.programmerStore.entries().map { it.fixtureKey to it.slots.firstOrNull()?.value?.resolved }
        val answer = client.run(jog(1.0, 0.0, base = start.base, write = false))
        assertMoved(start, answer, 1.0, 0.0)
        assertEquals(before, state.show.programmerStore.entries().map { it.fixtureKey to it.slots.firstOrNull()?.value?.resolved })
    }

    @Test
    fun `a jog refuses a malformed request and skips what cannot move by name`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        suspend fun refused(request: AimRequest, code: String) {
            val response = client.post(request)
            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(response.bodyAsText().contains(code), response.bodyAsText())
        }
        refused(AimRequest(targets = emptyList(), mode = "JOG", dx = 1.0, dy = 0.0), CODE_AIM_NEEDS_SELECTION)
        refused(AimRequest(targets = listOf(fixture("spot-fine")), mode = "JOG", dx = 1.0), CODE_AIM_INVALID)
        refused(AimRequest(targets = listOf(fixture("spot-fine")), mode = "SIDEWAYS", dx = 1.0, dy = 0.0), CODE_AIM_INVALID)
        refused(
            AimRequest(targets = listOf(fixture("spot-fine")), mode = "JOG", dx = 1.0, dy = 0.0, base = listOf(AimBaseDto(fixture("spot-fine"), "half"))),
            CODE_AIM_INVALID,
        )
        refused(AimRequest(targets = listOf(fixture("spot-fine")), mode = "JOG", dx = 1.0, dy = 0.0, planeZ = 900.0), CODE_AIM_INVALID)
        // A point aim still needs its point.
        refused(AimRequest(targets = listOf(fixture("spot-fine")), x = 1.0), CODE_AIM_INVALID)

        client.jogRig()
        val out = client.run(
            AimRequest(targets = listOf(fixture("par-1"), fixture("spot-unplaced"), fixture("nope"), fixture("spot-fine")), mode = "JOG", dx = 0.5, dy = 0.0),
        )
        val reasons = out.skipped.associate { it.target.key to it.reason }
        assertTrue(reasons.getValue("par-1").contains("no pan or tilt"), reasons.toString())
        assertTrue(reasons.getValue("spot-unplaced").contains("not placed"), reasons.toString())
        assertEquals("not patched", reasons.getValue("nope"))
        assertEquals(listOf("spot-fine"), out.written.map { it.target.key })
        assertTrue(state.show.programmerStore.entries().none { it.fixtureKey == "par-1" })
    }

    @Test
    fun `a jog's start keeps the fine being sent under a coarse-only Local value`() {
        // Local holds 128 (the XY pad writes coarse only); a cue is sending 128 with fine 200.
        assertEquals(128.toUByte() to 200.toUByte(), jogAxisStart(128u, null, 128u, 200u))
        // Local's own fine wins.
        assertEquals(128.toUByte() to 7.toUByte(), jogAxisStart(128u, 7u, 128u, 200u))
        // Mid-fade the coarse being sent is not Local's, so its fine says nothing about Local's.
        assertEquals(128.toUByte() to null, jogAxisStart(128u, null, 120u, 200u))
        assertEquals(120.toUByte() to 200.toUByte(), jogAxisStart(null, null, 120u, 200u))
        assertNull(jogAxisStart(null, null, null, null))
    }

    @Test
    fun `a jog from a group's pick writes with the group as its source`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        client.jogRig()
        fun sourceGroup(key: String) = state.show.programmerStore.entries()
            .first { it.fixtureKey == key && it.propertyName == "position" }.slots.first().sourceGroup
        client.run(AimRequest(targets = listOf(fixture("spot-fine")), mode = "JOG", dx = 0.2, dy = 0.0))
        assertNull(sourceGroup("spot-fine"))
        client.run(AimRequest(targets = listOf(fixture("spot-fine")), mode = "JOG", dx = 0.2, dy = 0.0, groups = listOf("spots")))
        assertEquals("spots", sourceGroup("spot-fine"))
        // A group names no head: spot-coarse, in it, was not moved.
        assertTrue(state.show.programmerStore.entries().none { it.fixtureKey == "spot-coarse" && it.slots.first().sourceGroup == "spots" })
    }

    // ─── a stage fan (POST /programmer/spread, FAN in metres) ──────────────

    private suspend fun HttpClient.spread(request: SpreadRequest): HttpResponse =
        post("/api/rest/projects/$projectId/programmer/spread") {
            contentType(ContentType.Application.Json)
            setBody(request)
        }

    private suspend fun HttpClient.runSpread(request: SpreadRequest): SpreadResponse {
        val resp = spread(request)
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun stageFan(x: Double?, y: Double?, base: List<SpreadBaseDto>? = null, near: List<AimNearDto>? = null) =
        SpreadRequest(
            targets = jogFrom.keys.map(::fixture), property = "position", mode = "FAN",
            fan = SpreadFanDto(x = x, y = y), base = base, near = near,
        )

    /** Each moved head's landing point on the plane, from the travel degrees the answer gives as `near`. */
    private fun landings(answer: SpreadResponse, keys: Set<String> = jogFrom.keys): Map<String, StagePoint> =
        answer.near.associate { n ->
            val key = n.target.key
            val (panZero, tiltZero) = jogZero.getValue(key)
            key to landing(AimWriteDto(n.target, "", n.panDeg, n.tiltDeg), jogFrom.getValue(key), panZero, tiltZero).point
        }.also { assertEquals(keys, answer.written.map { it.target.key }.toSet(), answer.skipped.toString()) }

    @Test
    fun `a stage fan moves each head's landing point by its share of the metres, from where the gesture started`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        client.jogRig()
        // The start, as a jog reads it: where each head lands before any fan.
        val start = client.run(jog(0.0, 0.0))
        val startAt = start.written.associate { w ->
            val (panZero, tiltZero) = jogZero.getValue(w.target.key)
            w.target.key to landing(w, jogFrom.getValue(w.target.key), panZero, tiltZero).point
        }

        val first = client.runSpread(stageFan(2.0, null))
        assertEquals(jogFrom.keys, first.base.map { it.target.key }.toSet())
        assertNotNull(first.base.single { it.target.key == "shehds" }.panFine, "a stage fan's base carries fine bytes")
        val moved = landings(first)
        // Three heads in a line: the ends move the whole amount, in opposite directions, the middle stays.
        val offsets = moved.mapValues { (key, at) -> at.x - startAt.getValue(key).x }
        listOf(-2.0, 0.0, 2.0).zip(offsets.values.sorted()).forEach { (want, got) -> assertEquals(want, got, 0.3, offsets.toString()) }
        for ((key, at) in moved) assertEquals(startAt.getValue(key).y, at.y, 0.3, "$key y")
        assertNotNull(entry("shehds", "panFine"), "fine channels are written")

        // A later frame of the gesture is measured from its start, not from the last frame.
        val later = client.runSpread(stageFan(1.0, null, base = first.base, near = first.near))
        assertEquals(first.base, later.base, "a base sent is the base answered")
        val again = landings(later).mapValues { (key, at) -> at.x - startAt.getValue(key).x }
        for ((key, offset) in offsets) assertEquals(offset / 2, again.getValue(key), 0.3, key)
    }

    @Test
    fun `a stage fan refuses mixed units, cells and an amount off any stage`() = testApplication {
        seedJogRig()
        mountTestApp(state)
        val client = jsonClient()
        suspend fun refused(request: SpreadRequest) {
            val response = client.spread(request)
            assertEquals(HttpStatusCode.BadRequest, response.status, response.bodyAsText())
        }
        refused(stageFan(1.0, null).copy(fan = SpreadFanDto(pan = 10, x = 1.0)))
        refused(stageFan(1.0, null).copy(over = "CELLS"))
        refused(stageFan(900.0, null))
        refused(stageFan(1.0, null, base = listOf(SpreadBaseDto(fixture("spot-fine"), "128,128", panFine = 300))))

        client.jogRig()
        val startAt = client.run(jog(0.0, 0.0)).written.associate { w ->
            val (panZero, tiltZero) = jogZero.getValue(w.target.key)
            w.target.key to landing(w, jogFrom.getValue(w.target.key), panZero, tiltZero).point
        }
        val out = client.runSpread(
            stageFan(1.0, null).copy(targets = listOf(fixture("spot-unplaced"), fixture("spot-fine"), fixture("shehds"))),
        )
        assertTrue(out.skipped.single { it.target.key == "spot-unplaced" }.reason.contains("not placed"), out.skipped.toString())
        // The unplaced head takes no place on the curve: the two that can be aimed are its ends.
        val offsets = landings(out, setOf("spot-fine", "shehds")).map { (key, at) -> at.x - startAt.getValue(key).x }.sorted()
        listOf(-1.0, 1.0).zip(offsets).forEach { (want, got) -> assertEquals(want, got, 0.3, offsets.toString()) }
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
    fun `aim_fixtures saveAsPalette keeps the aims as a position template beside the programmer`() {
        seed()
        val result = call(
            """{"targets":[{"type":"group","key":"spots"}],"x":0,"y":4,"z":0,"saveAsPalette":"Wall focus"}""",
        )
        assertTrue(result.success, result.result)
        val template = result.json()["palette"]!!.jsonObject
        assertEquals("Wall focus", template["name"]!!.jsonPrimitive.content)
        assertEquals(2, template["rowCount"]!!.jsonPrimitive.content.toInt())

        val rows = transaction(state.database) {
            DaoTemplate.find { DaoTemplates.name eq "Wall focus" }.single().rows
                .associate { it.targetKey to (it.propertyName to it.value) }
        }
        // One fixture-specific position row per aimed head, centre-relative (venue-visit plan P3):
        // its own coarse bytes about DMX 128 — spot-fine is at 127/127, a step short of mid-travel.
        assertEquals(setOf("spot-fine", "spot-coarse"), rows.keys)
        assertEquals("position" to "pos:-1,-1", rows.getValue("spot-fine"))
        assertTrue(rows.getValue("spot-coarse").second.startsWith("pos:"), rows.toString())
        // And the programmer was written, as without it.
        assertEquals(CueAssignmentResolver.PropertyValue.Position(127u, 127u), entry("spot-fine", "position"))
    }

    @Test
    fun `aim_fixtures saveAsPalette with a taken name moves nothing`() {
        seed()
        val first = call("""{"targets":[{"type":"fixture","key":"spot-fine"}],"x":0,"y":4,"z":0,"saveAsPalette":"Focus"}""")
        assertTrue(first.success, first.result)
        state.show.programmerStore.clearAll()
        val again = call("""{"targets":[{"type":"fixture","key":"spot-coarse"}],"x":0,"y":4,"z":0,"saveAsPalette":"Focus"}""")
        assertFalse(again.success, again.result)
        assertTrue(again.result.contains("already exists"), again.result)
        assertTrue(state.show.programmerStore.entries().isEmpty(), "a refused save writes no aim either")
    }

    @Test
    fun `aim_fixtures dryRun makes no template`() {
        seed()
        val dry = call("""{"targets":[{"type":"fixture","key":"spot-fine"}],"x":0,"y":4,"z":0,"dryRun":true,"saveAsPalette":"Dry"}""")
        assertTrue(dry.success, dry.result)
        assertNull(dry.json()["palette"])
        assertNotNull(dry.json()["paletteNote"])
        assertTrue(transaction(state.database) { DaoTemplate.find { DaoTemplates.name eq "Dry" }.empty() })
    }

    @Test
    fun `aim_fixtures refuses a point off any stage`() {
        seed()
        val result = call("""{"targets":[{"type":"fixture","key":"spot-fine"}],"x":0,"y":4,"z":900}""")
        assertFalse(result.success)
        assertTrue(result.description.contains("z must be"), result.description)
    }
}
