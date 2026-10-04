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
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.fixture.MoverHead
import uk.me.cormack.lighting7.show.FocusRange
import uk.me.cormack.lighting7.show.MoverLens
import uk.me.cormack.lighting7.show.lensDistance
import uk.me.cormack.lighting7.show.StagePoint
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestFocusHead
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `POST /projects/{id}/programmer/focus` — *Focus here* (fixture-optics plan D11) — and
 * `aim_fixtures`' `focus: true` over it: each head's focus level for a point lands as a programmer
 * entry, solved through its declared range from where it hangs; `write: false` writes nothing; every
 * head that cannot be focused is skipped by name.
 */
class ProgrammerFocusRouteTest : RouteIntegrationTest() {

    private fun focus() = "/api/rest/projects/$projectId/programmer/focus"

    private val tools by lazy { AiTools(state) }

    private fun place(key: String, x: Double, y: Double, z: Double, pitchDeg: Double? = 180.0) {
        transaction(state.database) {
            val patch = DaoFixturePatch.find { DaoFixturePatches.key eq key }.first()
            patch.stageX = x
            patch.stageY = y
            patch.stageZ = z
            patch.basePitchDeg = pitchDeg
        }
    }

    /** The back wall of the Commemoration Hall, 24 m from the balcony Revolutions. */
    private val wall = StagePoint(0.0, 6.7, 2.8)

    private val revLens = MoverLens.of(0.856, MoverHead.PROFILE)
    private val macLens = MoverLens.of(0.45, MoverHead.SPOT)

    /** rev-1 hangs at (0, −17.3, 2.8): its lens's distance to the wall point, and that as DMX. */
    private val wallM = lensDistance(StagePoint(0.0, -17.3, 2.8), null, 180.0, null, revLens, wall)
    private val wallDmx = FocusRange(2.0, 40.0, false, 0, 255).dmxFor(wallM)!!

    private fun seed() {
        LocateTestSupport.seedFixture(state, projectId, "etc-source4-revolution-base-frame", "rev-1", 1)
        LocateTestSupport.seedFixture(state, projectId, "etc-source4-revolution-base-frame", "rev-unplaced", 40)
        LocateTestSupport.seedFixture(state, projectId, "martin-mac-250-mode-4", "mac", 80)
        LocateTestSupport.seedFixture(state, projectId, "generic-dimmer", "par-1", 100)
        LocateTestSupport.seedGroup(state, projectId, "balcony", "rev-1", "mac")
        place("rev-1", 0.0, -17.3, 2.8)
        place("mac", 3.0, 4.0, 6.0)
        place("par-1", -3.0, 0.0, 6.0, pitchDeg = 45.0)
        LocateTestSupport.reloadFixtures(state, projectId)
        state.show.fixtures.patchListChanged()
    }

    private fun fixture(key: String) = CueTargetDto("fixture", key)

    private suspend fun HttpClient.post(body: String): HttpResponse =
        post(focus()) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.run(request: FocusRequest): FocusResponse {
        val resp = post(focus()) {
            contentType(ContentType.Application.Json)
            setBody(request)
        }
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private fun entry(key: String, property: String): CueAssignmentResolver.PropertyValue? =
        state.show.programmerStore.entries()
            .firstOrNull { it.fixtureKey == key && it.propertyName == property }
            ?.slots?.firstOrNull()?.value?.resolved

    private fun at(p: StagePoint) = FocusPointDto(p.x, p.y, p.z)

    @Test
    fun `a Revolution on the balcony focuses on the back wall 24 m away`() = testApplication {
        seed()
        mountTestApp(state)
        val out = jsonClient().run(FocusRequest(targets = listOf(fixture("rev-1")), point = at(wall)))

        val written = out.written.single()
        assertEquals("rev-1", written.target.key)
        // Measured from the lens: 24 m from the placement, 23.78 from the lens below and ahead of
        // it — still DMX 246 on 2–40 m at that throw.
        assertEquals(23.78, written.distanceM)
        assertEquals(246, wallDmx)
        assertEquals("246", written.value)
        assertTrue(out.skipped.isEmpty(), out.skipped.toString())
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(246u), entry("rev-1", "focus"))
    }

    @Test
    fun `a group focuses every member from where each hangs, an inverted range included`() = testApplication {
        seed()
        mountTestApp(state)
        val deck = StagePoint(3.0, 4.0, 0.0)
        val out = jsonClient().run(FocusRequest(targets = listOf(CueTargetDto("group", "balcony")), point = at(deck)))

        assertEquals(listOf("rev-1", "mac"), out.written.map { it.target.key })
        // The MAC hangs 6 m straight above the point: its lens 5.61 m above it, below its pivot. Its
        // range runs far → near up the fader.
        val mac = out.written.single { it.target.key == "mac" }
        val macM = 6.0 - macLens.pivotM - macLens.lensM
        assertEquals(5.61, mac.distanceM)
        val expected = FocusRange(2.0, 40.0, true, 0, 255).dmxFor(macM)!!
        assertEquals(expected.toString(), mac.value)
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(expected.toUByte()), entry("mac", "focus"))
    }

    @Test
    fun `write false answers what would land and writes nothing`() = testApplication {
        seed()
        mountTestApp(state)
        val out = jsonClient().run(FocusRequest(targets = listOf(fixture("rev-1")), point = at(wall), write = false))
        assertEquals("246", out.written.single().value)
        assertTrue(state.show.programmerStore.entries().isEmpty(), "write: false writes nothing")
    }

    @Test
    fun `everything that cannot be focused is skipped by name`() = testApplication {
        seed()
        mountTestApp(state)
        // 1.4 m below the MAC's placement, 1 m below its lens: nearer than its 2 m near end.
        val close = StagePoint(3.0, 4.0, 4.6)
        val out = jsonClient().run(
            FocusRequest(
                targets = listOf(fixture("par-1"), fixture("rev-unplaced"), fixture("mac"), fixture("nope"), fixture("rev-1")),
                point = at(close),
            ),
        )
        val reasons = out.skipped.associate { it.target.key to it.reason }
        assertEquals(setOf("par-1", "rev-unplaced", "mac", "nope"), reasons.keys)
        assertEquals("no focus channel", reasons.getValue("par-1"))
        assertEquals("not placed on the stage", reasons.getValue("rev-unplaced"))
        assertTrue(reasons.getValue("mac").startsWith("out of focus range"), reasons.toString())
        assertTrue(reasons.getValue("mac").contains("1 m away") && reasons.getValue("mac").contains("2–40 m"), reasons.toString())
        assertEquals("not patched", reasons.getValue("nope"))
        assertEquals(listOf("rev-1"), out.written.map { it.target.key })
        assertNull(entry("mac", "focus"))
    }

    @Test
    fun `a focus with no declared range is skipped by name`() {
        // No library type has one (FocusRangeTest), so the head is the test-only type, solved directly.
        val head = TestFocusHead(Universe(0, 0), "head", 1)
        val placed = AimPlacement(StagePoint(0.0, 0.0, 6.0), null, 180.0, null)
        val skip = assertIs<HeadFocus.Skip>(focusHead(head, placed, StagePoint(0.0, 0.0, 0.0)))
        assertEquals("focus declares no range", skip.reason)
    }

    @Test
    fun `no targets, no point or an off-stage point is refused`() = testApplication {
        seed()
        mountTestApp(state)
        val client = jsonClient()
        val empty = client.post("""{"targets":[],"point":{"x":0,"y":0,"z":0}}""")
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertTrue(empty.bodyAsText().contains(CODE_FOCUS_NEEDS_SELECTION))
        val missing = client.post("""{"targets":[{"type":"fixture","key":"rev-1"}]}""")
        assertEquals(HttpStatusCode.BadRequest, missing.status)
        assertTrue(missing.bodyAsText().contains(CODE_FOCUS_INVALID), missing.bodyAsText())
        val far = client.post("""{"targets":[{"type":"fixture","key":"rev-1"}],"point":{"x":0,"y":0,"z":1000}}""")
        assertEquals(HttpStatusCode.BadRequest, far.status)
        assertTrue(far.bodyAsText().contains(CODE_FOCUS_INVALID))
        // Nothing to focus is the more fundamental refusal, whatever else is wrong.
        val both = client.post("""{"targets":[]}""")
        assertTrue(both.bodyAsText().contains(CODE_FOCUS_NEEDS_SELECTION), both.bodyAsText())
        assertTrue(state.show.programmerStore.entries().isEmpty())
    }

    // ─── aim_fixtures focus ────────────────────────────────────────────────

    private fun call(arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool("aim_fixtures", Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    @Test
    fun `aim_fixtures focus true focuses on the aim point, and dryRun writes neither`() {
        seed()
        val args = """"targets":[{"type":"fixture","key":"rev-1"},{"type":"fixture","key":"par-1"}],"x":0,"y":6.7,"z":2.8,"focus":true"""
        val dry = call("{$args,\"dryRun\":true}")
        assertTrue(dry.success, dry.result)
        assertTrue(dry.json()["dryRun"]!!.jsonPrimitive.boolean)
        val focused = dry.json()["focused"]!!.jsonArray.single().jsonObject
        assertEquals("rev-1", focused["fixture"]!!.jsonPrimitive.content)
        assertEquals("246", focused["focus"]!!.jsonPrimitive.content)
        assertEquals(23.78, focused["distanceM"]!!.jsonPrimitive.content.toDouble())
        // The par is not a mover, so aim skipped it — and focus does not take a head aim skipped.
        val focusSkipped = dry.json()["focusSkipped"]!!.jsonArray.single().jsonObject
        assertEquals("par-1", focusSkipped["target"]!!.jsonPrimitive.content)
        assertTrue(focusSkipped["reason"]!!.jsonPrimitive.content.startsWith("not aimed, so not focused"), focusSkipped.toString())
        assertTrue(state.show.programmerStore.entries().isEmpty(), "a dry run writes nothing")

        val wet = call("{$args}")
        assertTrue(wet.success, wet.result)
        assertFalse(wet.json()["dryRun"]!!.jsonPrimitive.boolean)
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(246u), entry("rev-1", "focus"))
        assertIs<CueAssignmentResolver.PropertyValue.Position>(entry("rev-1", "position"))
    }

    @Test
    fun `aim_fixtures focus takes only the heads aim pointed at the point`() {
        seed()
        // The MAC is unplaced here, so aim skips it; it has a ranged focus, but is not focused.
        transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.key eq "mac" }.first().apply { stageX = null; stageY = null; stageZ = null }
        }
        LocateTestSupport.reloadFixtures(state, projectId)
        val result = call("""{"targets":[{"type":"group","key":"balcony"}],"x":0,"y":6.7,"z":2.8,"focus":true}""")
        assertTrue(result.success, result.result)
        assertEquals(listOf("rev-1"), result.json()["focused"]!!.jsonArray.map { it.jsonObject["fixture"]!!.jsonPrimitive.content })
        val skipped = result.json()["focusSkipped"]!!.jsonArray.single().jsonObject
        assertEquals("mac", skipped["target"]!!.jsonPrimitive.content)
        assertTrue(skipped["reason"]!!.jsonPrimitive.content.contains("not placed"), skipped.toString())
        assertNull(entry("mac", "focus"))
    }

    @Test
    fun `aim_fixtures without focus leaves focus alone`() {
        seed()
        val result = call("""{"targets":[{"type":"fixture","key":"rev-1"}],"x":0,"y":6.7,"z":2.8}""")
        assertTrue(result.success, result.result)
        assertNull(result.json()["focused"])
        assertNull(entry("rev-1", "focus"))
    }
}
