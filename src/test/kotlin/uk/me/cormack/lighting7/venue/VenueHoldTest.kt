package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.state.VenueHoldService
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.VenueShowFixture
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A show that is behind its venue (venue plan §3.1, P5, D7; session 6): the classification, the hold —
 * zeros on the wire but for a park while the show composes — the refusals of arm, fire and command, *Run
 * against* with the installed version's trigger channels kept idle, and what drops or keeps it.
 */
class VenueHoldTest : RouteIntegrationTest() {

    @Test
    fun `a drawing-only gap is a banner, an output change holds, and the show's layer can stand between`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)

        // v2 moved a truss: drawing only.
        assertEquals(VenueBehind.DRAWING, ShowBehind.judge(state, f.showId).behind)
        assertEquals("DRAWING", client.venueOf(f.showId).str("behind"))

        // v3 re-addresses the venue's patch the show overrides the address of: the show sends it to
        // its own address either way, so still drawing only.
        val overridden = keyOf(f, f.overriddenUuid)
        publish(f, "Re-address $overridden") { draftPatch(f, overridden).startChannel = 480 }
        val third = ShowBehind.judge(state, f.showId)
        assertEquals(VenueBehind.DRAWING, third.behind)
        assertTrue(third.discounted.any { it.uuid == f.overriddenUuid.toString() }, "${third.discounted}")

        // v4 re-addresses one the show uses as the venue has it: behind on output.
        val free = freeHex(f)
        publish(f, "Re-address $free") { draftPatch(f, free).startChannel = 260 }
        val fourth = ShowBehind.judge(state, f.showId)
        assertEquals(VenueBehind.OUTPUT, fourth.behind)
        assertEquals(1, fourth.output.size, "${fourth.output}")
        assertEquals("OUTPUT", client.venueOf(f.showId).str("behind"))
    }

    @Test
    fun `a held show sends zero but for a park and still composes, and arm, fire and command are refused`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val free = freeHex(f)
        publish(f, "Re-address $free") { draftPatch(f, free).startChannel = 260 }
        open(f.showId)

        val status = state.venueHold.status.value
        assertEquals(VenueHoldService.Status(f.showId, held = true, pinned = 1, installed = 3, runAgainst = false, behind = VenueBehind.OUTPUT, venueName = "Commemoration Hall"), status)
        val controllers = state.show.fixtures.controllers
        assertTrue(controllers.isNotEmpty() && controllers.all { it.held }, "every controller is held")

        // The pinned rig composes as it would: a fixture's value reaches the buffer and the readers.
        val hex = state.show.fixtures.untypedFixture(free) as uk.me.cormack.lighting7.fixture.DmxFixture
        val controller = state.show.fixtures.controllerOrNull(hex.universe) as MockDmxController
        controller.setValue(hex.firstChannel, 200u)
        runBlocking { state.show.parkManager.park(hex.universe.universe, hex.firstChannel + 1, 150u) }
        assertEquals(200u.toUByte(), controller.getEffectiveValue(hex.firstChannel), "the show composes and the views draw it")
        assertEquals(150u.toUByte(), controller.getValue(hex.firstChannel + 1), "park still reads through")
        assertEquals(0u.toUByte(), controller.transmittedValue(hex.firstChannel), "held: the wire carries zero")
        assertEquals(150u.toUByte(), controller.transmittedValue(hex.firstChannel + 1), "a park gets through the hold")

        // Arm, fire and command: VENUE_HOLD. A rehearsal is drawn and sends nothing, so it stands.
        awaitEffectsOn(f.showId)
        assertCode(HttpStatusCode.Conflict, VenueHoldService.CODE_VENUE_HOLD, client.post("/api/rest/projects/${f.showId}/effects/arm") { json("""{"on":true}""") })
        val cannon = patchId(f.showId, "cannon-1")
        assertCode(HttpStatusCode.Conflict, VenueHoldService.CODE_VENUE_HOLD, client.post("/api/rest/projects/${f.showId}/patches/$cannon/fire") { json("""{"trigger":"output1"}""") })
        val rehearsed = client.post("/api/rest/projects/${f.showId}/patches/$cannon/fire") { json("""{"trigger":"output1","rehearse":true}""") }
        assertEquals(HttpStatusCode.OK, rehearsed.status, rehearsed.bodyAsText())
        val rev = patchId(f.showId, "rev-1")
        val command = state.show.commandOutput.commandsOf("rev-1").first().name
        assertCode(HttpStatusCode.Conflict, VenueHoldService.CODE_VENUE_HOLD, client.post("/api/rest/projects/${f.showId}/patches/$rev/commands/$command"))
        assertFalse(state.effectsService.armed.value.armed)
    }

    @Test
    fun `running against an older pin keeps the installed version's trigger channels idle`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        // v3 hangs the cannon where v1 has a hex the show drives: the old patch would fire it.
        val free = freeHex(f)
        val (universe, start) = transaction(state.database) {
            val hex = DaoFixturePatch.find { (DaoFixturePatches.project eq RigScope.of(f.showId).venue!!.snapshotId) and (DaoFixturePatches.key eq free) }.single()
            hex.universeConfig.universe to hex.startChannel
        }
        publish(f, "Cannon moved onto $free's channels") {
            val cannon = draftPatch(f, "cannon-1")
            cannon.universeConfig = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq f.venueId) and (DaoUniverseConfigs.universe eq universe) }.single()
            cannon.startChannel = start
        }
        open(f.showId)
        assertTrue(state.venueHold.status.value.held)

        // Not behind on output is refused by name; behind, it lifts the hold.
        val on = client.post("/api/rest/projects/${f.showId}/venue/run-against") { json("""{"on":true}""") }
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertTrue(on.obj().bool("runAgainst"))
        assertFalse(on.obj().bool("held"))
        val controller = state.show.fixtures.controllerOrNull(Universe(0, universe)) as MockDmxController
        assertFalse(controller.held)

        // The old patch writes its hex at full across the cannon's three channels: v3's tubes and arm stay idle.
        for (ch in start until start + 4) controller.setValue(ch, 255u)
        assertEquals(0u.toUByte(), controller.transmittedValue(start), "v3's tube A")
        assertEquals(0u.toUByte(), controller.transmittedValue(start + 1), "v3's tube B")
        assertEquals(0u.toUByte(), controller.transmittedValue(start + 2), "v3's arm")
        assertEquals(255u.toUByte(), controller.transmittedValue(start + 3), "the rest of the hex is live")
        // Still no arm while behind.
        awaitEffectsOn(f.showId)
        assertCode(HttpStatusCode.Conflict, VenueHoldService.CODE_VENUE_HOLD, client.post("/api/rest/projects/${f.showId}/effects/arm") { json("""{"on":true}""") })

        // A reload of the same project at the same pin keeps it.
        open(f.showId)
        assertTrue(state.venueHold.status.value.runAgainst)
        // Another project and back: held again.
        open(projectId)
        open(f.showId)
        assertTrue(state.venueHold.status.value.held)

        // A pin that moves (a pull bringing a peer's partial update to v2) re-applies the hold.
        client.post("/api/rest/projects/${f.showId}/venue/run-against") { json("""{"on":true}""") }
        assertTrue(state.venueHold.status.value.runAgainst)
        transaction(state.database) { DaoProject[f.showId].venueVersion = 2 }
        open(f.showId)
        assertEquals(2, state.venueHold.status.value.pinned)
        assertTrue(state.venueHold.status.value.held, "a moved pin is a new question")

        // Off puts the hold back; a show on its installed version has nothing to lift.
        val off = client.post("/api/rest/projects/${f.showId}/venue/run-against") { json("""{"on":false}""") }
        assertTrue(off.obj().bool("held"))
        transaction(state.database) { DaoProject[f.showId].venueVersion = 3 }
        open(f.showId)
        assertFalse(state.venueHold.status.value.held)
        assertCode(HttpStatusCode.Conflict, VenueHoldService.CODE_NOT_BEHIND, client.post("/api/rest/projects/${f.showId}/venue/run-against") { json("""{"on":true}""") })
    }

    @Test
    fun `a publish that puts the running show behind keeps it live and guarded, and an operator may hold or lift it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        open(f.showId)
        assertFalse(state.venueHold.status.value.held, "v2 is drawing only")
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val admin = client.loginCookieHeader("admin")
        val op = client.loginCookieHeader("op")

        val free = freeHex(f)
        transaction(state.database) { draftPatch(f, free).startChannel = 260 }
        val published = client.post("/api/rest/venues/${f.venueId}/publish") {
            header(HttpHeaders.Cookie, admin)
            json("""{"note":"Re-address $free"}""")
        }
        assertEquals(HttpStatusCode.Created, published.status, published.bodyAsText())
        // Behind on output now, but running: not blacked out mid-run — run against its pin, guarded.
        val after = state.venueHold.status.value
        assertEquals(VenueBehind.OUTPUT, after.behind)
        assertFalse(after.held, "a running show is never blacked out by a publish")
        assertTrue(after.runAgainst)
        assertTrue(state.show.fixtures.controllers.none { it.held })
        assertCode(
            HttpStatusCode.Conflict, VenueHoldService.CODE_VENUE_HOLD,
            client.post("/api/rest/projects/${f.showId}/effects/arm") { header(HttpHeaders.Cookie, op); json("""{"on":true}""") },
        )

        // An operator may put the hold back, and lift it again.
        val off = client.post("/api/rest/projects/${f.showId}/venue/run-against") {
            header(HttpHeaders.Cookie, op)
            json("""{"on":false}""")
        }
        assertEquals(HttpStatusCode.OK, off.status, off.bodyAsText())
        assertTrue(state.venueHold.status.value.held)
        val on = client.post("/api/rest/projects/${f.showId}/venue/run-against") {
            header(HttpHeaders.Cookie, op)
            json("""{"on":true}""")
        }
        assertEquals(HttpStatusCode.OK, on.status, on.bodyAsText())
        assertTrue(state.venueHold.status.value.runAgainst)

        // Opened afresh (another project and back), it opens held.
        open(projectId)
        open(f.showId)
        assertTrue(state.venueHold.status.value.held)
    }

    @Test
    fun `an Update that lifts the hold brings the show up from what the rig showed, not the held composition`() = testApplication {
        mountTestApp(state)
        val f = seedShowOnVenue(state)
        val free = freeHex(f)
        publish(f, "Re-address $free") { draftPatch(f, free).startChannel = 260 }
        open(f.showId)
        assertTrue(state.venueHold.status.value.held)

        // Held: the show composes onto its old addresses, and a park stands on the wire.
        val hex = state.show.fixtures.untypedFixture(free) as uk.me.cormack.lighting7.fixture.DmxFixture
        val old = state.show.fixtures.controllerOrNull(hex.universe) as MockDmxController
        old.setValue(hex.firstChannel, 200u)
        runBlocking { state.show.parkManager.park(hex.universe.universe, 500, 180u) }
        assertEquals(0u.toUByte(), old.transmittedValue(hex.firstChannel))

        val result = runBlocking { state.venueUpdate.update(f.showId, null, emptyList(), dryRun = false) }
        assertFalse(result.dryRun)
        runBlocking { state.projectManager.switchProject(f.showId, reload = true) }

        assertFalse(state.venueHold.status.value.held, "on the installed version: no hold")
        val frozen = checkNotNull(old.frozen) { "the held show left on its last frame" }
        assertEquals(0u.toUByte(), frozen[hex.firstChannel], "the held composition never reached the wire, so it is not carried")
        assertEquals(180u.toUByte(), frozen[500], "the park is carried")
        val fresh = state.show.fixtures.controllerOrNull(hex.universe) as MockDmxController
        assertEquals(180u.toUByte(), fresh.transmittedValue(500))
        assertTrue(
            fresh.writesTo(hex.firstChannel).none { it == 200u.toUByte() },
            "the old patch's level is never written to its old address: ${fresh.writesTo(hex.firstChannel)}",
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun open(projectId: Int) {
        runBlocking { state.projectManager.switchProject(projectId) }
    }

    /** The effects service follows a switch on the project collector: wait for it to reach [showId]. */
    private suspend fun awaitEffectsOn(showId: Int) {
        repeat(200) {
            if (state.effectsService.armed.value.projectId == showId) return
            delay(10)
        }
        error("the effects service never followed the switch to $showId")
    }

    private fun publish(f: VenueShowFixture, note: String, change: () -> Unit): Int {
        transaction(state.database) { change() }
        return runBlocking { state.venueVersions.publish(f.venueId, note, "Test") }.number
    }

    private fun draftPatch(f: VenueShowFixture, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.key eq key) }.single()

    private fun keyOf(f: VenueShowFixture, uuid: java.util.UUID): String = transaction(state.database) {
        DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.uuid eq uuid) }.single().key
    }

    /** A venue hex the show neither hides nor overrides: one it drives exactly as the venue patched it. */
    private fun freeHex(f: VenueShowFixture): String = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
            .filter { it.fixtureTypeKey == "hex" && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
            .minBy { it.key }.key
    }

    private fun patchId(showId: Int, key: String): Int = transaction(state.database) {
        RigScope.of(showId).patches(DaoFixturePatches.key eq key).single().id.value
    }

    private suspend fun HttpClient.venueOf(projectId: Int): JsonObject {
        val response = get("/api/rest/projects/$projectId")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.obj()["venue"]!!.jsonObject
    }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }
}
