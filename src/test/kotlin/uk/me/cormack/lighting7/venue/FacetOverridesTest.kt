package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.PatchFacet
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Venue plan session 5: a show's facet overrides of its venue's patches. Each facet through the route
 * and nothing outside it moving; placements following their facet; an Address override output on the
 * new channels; drift appearing when the pin moves under a changed value and clearing three ways; the
 * refusals; and how overrides sit with hides, a rigging delete and a universe delete.
 */
class FacetOverridesTest : RouteIntegrationTest() {

    /** A show on a venue at v1: the rich fixture extracted into "Commemoration Hall". */
    private fun showOnVenue(): Pair<Int, Int> {
        val showId = seedVenueReadyShow(state)
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!
        return showId to venueId
    }

    @Test
    fun `each facet round-trips through the route and nothing outside it moves`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val before = client.patches(showId)
        val foh = rigging(showId, "FOH Truss")

        // Address: universe 1 from channel 200.
        val hex1 = before.getValue("hex-1")
        val address = client.facet(showId, hex1.id(), "ADDRESS", """{"universe":1,"startChannel":200}""")
        assertEquals(HttpStatusCode.OK, address.status, address.bodyAsText())
        val afterAddress = address.obj()["patch"]!!.jsonObject
        assertEquals(1, afterAddress.int("universe"))
        assertEquals(200, afterAddress.int("startChannel"))
        assertEquals(listOf("ADDRESS"), afterAddress.facets().map { it.str("facet") })

        // Position: hex-3 off its boom and onto the FOH truss.
        val hex3 = before.getValue("hex-3")
        val position = client.facet(
            showId, hex3.id(), "POSITION",
            """{"riggingUuid":"$foh","stageX":2.5,"stageY":4.5,"stageZ":-0.5,"baseYawDeg":10}""",
        )
        assertEquals(HttpStatusCode.OK, position.status, position.bodyAsText())

        // Focus: the profile zoomed and stopped down, its paired PAR's lamp turned.
        val profile = before.getValue("adv2-1")
        val par = profile.placements().single()
        val focus = client.facet(
            showId, profile.id(), "FOCUS",
            """{"lanternType":"s4-zoom-25-50","zoomDeg":40,"iris":0.5,"beamAngleDeg":30,
                "placements":[{"placementUuid":"${par.str("uuid")}","lanternType":"par64-cp62","lampRotationDeg":90}]}""",
        )
        assertEquals(HttpStatusCode.OK, focus.status, focus.bodyAsText())

        // Media: the Revolution's patch re-gelled; its placement named nowhere, so it keeps the venue's.
        val rev = before.getValue("rev-1")
        val media = client.facet(
            showId, rev.id(), "MEDIA",
            """{"gelCode":"L106","media":{"slots":{"gelScroller":{"L201_FULL_CT_BLUE":{"gel":"R02"}}}}}""",
        )
        assertEquals(HttpStatusCode.OK, media.status, media.bodyAsText())

        val after = client.patches(showId)
        // Each facet's fields are the show's…
        assertEquals(200, after.getValue("hex-1").int("startChannel"))
        assertEquals(foh, after.getValue("hex-3").str("riggingUuid"))
        assertEquals(2.5, after.getValue("hex-3").dbl("stageX"))
        assertEquals(10.0, after.getValue("hex-3").dbl("baseYawDeg"))
        assertNull(after.getValue("hex-3")["basePitchDeg"]?.takeIf { it !is kotlinx.serialization.json.JsonNull })
        assertEquals(40.0, after.getValue("adv2-1").dbl("zoomDeg"))
        assertEquals(0.5, after.getValue("adv2-1").dbl("iris"))
        assertEquals(30, after.getValue("adv2-1").int("beamAngleDeg"))
        val parAfter = after.getValue("adv2-1").placements().single()
        assertEquals(90.0, parAfter.dbl("lampRotationDeg"))
        assertEquals("par64-cp62", parAfter.str("lanternType"))
        assertEquals("L106", after.getValue("rev-1").str("gelCode"))
        assertEquals(
            "R02",
            after.getValue("rev-1")["media"]!!.jsonObject["slots"]!!.jsonObject["gelScroller"]!!.jsonObject["L201_FULL_CT_BLUE"]!!.jsonObject.str("gel"),
        )
        assertEquals(rev.placements().single()["media"], after.getValue("rev-1").placements().single()["media"], "an unnamed placement keeps the venue's")

        // …and nothing outside a facet moved: identity, the other facets, the other patches.
        for ((key, was) in before) {
            val now = after.getValue(key)
            for (field in IDENTITY) assertEquals(was[field], now[field], "$key's $field is the venue's")
        }
        for (field in POSITION_FIELDS + FOCUS_FIELDS + MEDIA_FIELDS) assertEquals(before.getValue("hex-1")[field], after.getValue("hex-1")[field], "hex-1's $field")
        for (field in ADDRESS_FIELDS + FOCUS_FIELDS + MEDIA_FIELDS) assertEquals(before.getValue("hex-3")[field], after.getValue("hex-3")[field], "hex-3's $field")
        for (field in ADDRESS_FIELDS + POSITION_FIELDS + MEDIA_FIELDS) assertEquals(before.getValue("adv2-1")[field], after.getValue("adv2-1")[field], "adv2-1's $field")
        for (field in ADDRESS_FIELDS + POSITION_FIELDS + FOCUS_FIELDS) assertEquals(before.getValue("rev-1")[field], after.getValue("rev-1")[field], "rev-1's $field")
        for (key in before.keys - setOf("hex-1", "hex-3", "adv2-1", "rev-1")) assertEquals(before.getValue(key), after.getValue(key), "$key is untouched")

        // The venue's draft and every other show are untouched.
        assertEquals(0, state.venueVersions.draftChanges(venueId))
        val other = client.post("/api/rest/projects") { json("""{"name":"Second show","venueId":$venueId}""") }
        assertEquals(HttpStatusCode.Created, other.status, other.bodyAsText())
        val otherId = other.obj()["id"]!!.jsonPrimitive.int
        assertEquals(stripFacets(before), stripFacets(client.patches(otherId)))

        // A revert puts every field back as the venue has it.
        for ((key, facet) in listOf("hex-1" to "ADDRESS", "hex-3" to "POSITION", "adv2-1" to "FOCUS", "rev-1" to "MEDIA")) {
            val reverted = client.facet(showId, before.getValue(key).id(), facet, """null""")
            assertEquals(HttpStatusCode.OK, reverted.status, reverted.bodyAsText())
            assertTrue(reverted.obj()["changed"]!!.jsonPrimitive.boolean)
        }
        assertEquals(before, client.patches(showId))
        transaction(state.database) { assertTrue(DaoShowOverride.find { DaoShowOverrides.project eq showId }.empty()) }
    }

    @Test
    fun `placements follow their facet`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val hex2 = client.patches(showId).getValue("hex-2")
        val (sr, deck) = hex2.placements()
        val response = client.facet(
            showId, hex2.id(), "POSITION",
            """{"riggingUuid":"${hex2.str("riggingUuid")}","stageX":0.5,"stageY":4.5,
                "placements":[{"placementUuid":"${sr.str("uuid")}","stageX":-3.25,"stageY":0.25}]}""",
        )
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val after = response.obj()["patch"]!!.jsonObject.placements()
        assertEquals(listOf(sr.str("uuid"), deck.str("uuid")), after.map { it.str("uuid") }, "the venue's placements, in its order")
        assertEquals(-3.25, after[0].dbl("stageX"))
        assertNull(after[0]["riggingUuid"]?.takeIf { it is JsonPrimitive && it.isString }, "the facet is whole: SR's rigging is what the value says")
        assertEquals("SR", after[0].str("label"), "a label is identity, the venue's")
        assertEquals(deck, after[1], "a placement the value does not name keeps the venue's")

        // A placement the patch does not have is refused, named.
        val stray = client.facet(showId, hex2.id(), "POSITION", """{"placements":[{"placementUuid":"${java.util.UUID.randomUUID()}"}]}""")
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, stray)
        assertTrue("not one of 'hex-2''s placements" in stray.bodyAsText(), stray.bodyAsText())
    }

    @Test
    fun `an Address override outputs on its new channels, and an overlap is allowed and said`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val patches = client.patches(showId)
        val hex1 = patches.getValue("hex-1")
        assertEquals("hex-1", loadedChannels(showId)[0]?.get(10)?.fixtureKey)

        client.facet(showId, hex1.id(), "ADDRESS", """{"universe":1,"startChannel":200}""")
        val channels = loadedChannels(showId)
        assertEquals("hex-1", channels[1]?.get(200)?.fixtureKey, "the loader builds it at the show's address")
        assertTrue(channels[0]?.get(10)?.fixtureKey != "hex-1", "and no longer at the venue's")

        // Onto hex-2's channels: a decision (P8), allowed and reported.
        val overlap = client.facet(showId, hex1.id(), "ADDRESS", """{"universe":0,"startChannel":${patches.getValue("hex-2").int("startChannel")}}""")
        assertEquals(HttpStatusCode.OK, overlap.status, overlap.bodyAsText())
        val warnings = overlap.obj()["warnings"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(warnings.any { "hex-2" in it }, "$warnings")
    }

    @Test
    fun `drift appears when the pin moves under a changed value, and clears with keep mine, take venue's or a revert`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val hex1 = client.patches(showId).getValue("hex-1")
        client.facet(showId, hex1.id(), "MEDIA", """{"gelCode":"L203"}""")
        assertFalse(client.mediaState(showId).bool("drifted"), "the base is v1's L201, which v1 still says")

        // The venue re-gels it and publishes; only an Update moves a pin (session 6), so re-pin here.
        regelAndRepin(venueId, showId, "L202", version = 2)
        val drifted = client.mediaState(showId)
        assertTrue(drifted.bool("drifted"))
        assertEquals("L201", drifted["base"]!!.jsonObject.str("gelCode"))
        assertEquals("L202", drifted["venue"]!!.jsonObject.str("gelCode"))
        assertEquals("L203", client.patches(showId).getValue("hex-1").str("gelCode"), "the show's is kept")
        transaction(state.database) {
            val drift = ShowOverrides.drift(DaoProject[showId]).single()
            assertTrue(drift.drifted)
            assertEquals(PatchFacet.MEDIA, drift.facet)
        }

        // The pin moved, so the venue's patch is v2's row now — the same uuid, another id.
        val hex1v2 = client.patches(showId).getValue("hex-1")
        assertEquals(hex1.str("uuid"), hex1v2.str("uuid"))

        // Editing the value keeps the base: the venue's change is still unanswered.
        client.facet(showId, hex1v2.id(), "MEDIA", """{"gelCode":"L205"}""")
        assertTrue(client.mediaState(showId).bool("drifted"))

        // Keep mine: the base becomes the venue's current value; the show's gel stays.
        val keep = client.post("/api/rest/projects/$showId/patches/${hex1v2.id()}/facets/media/keep-mine")
        assertEquals(HttpStatusCode.OK, keep.status, keep.bodyAsText())
        assertFalse(client.mediaState(showId).bool("drifted"))
        assertEquals("L205", client.patches(showId).getValue("hex-1").str("gelCode"))

        // Take venue's: a revert.
        regelAndRepin(venueId, showId, "L204", version = 3)
        assertTrue(client.mediaState(showId).bool("drifted"))
        val hex1v3 = client.patches(showId).getValue("hex-1")
        client.facet(showId, hex1v3.id(), "MEDIA", "null")
        val taken = client.patches(showId).getValue("hex-1")
        assertEquals("L204", taken.str("gelCode"))
        assertTrue(taken.facets().isEmpty())

        // Keep mine with nothing overridden is refused, named.
        assertCode(
            HttpStatusCode.Conflict, ShowOverrides.CODE_NOT_OVERRIDDEN,
            client.post("/api/rest/projects/$showId/patches/${hex1v3.id()}/facets/MEDIA/keep-mine"),
        )
    }

    @Test
    fun `facet writes are refused where they do not belong`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val patches = client.patches(showId)
        val hex1 = patches.getValue("hex-1")

        // A show's own patch: edit it.
        val own = client.post("/api/rest/projects/$showId/patches") {
            json("""{"universe":0,"fixtureTypeKey":"uv","key":"own-uv","name":"Own UV","startChannel":480}""")
        }
        assertEquals(HttpStatusCode.Created, own.status, own.bodyAsText())
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_NOT_A_VENUE_RECORD, client.facet(showId, own.obj().id(), "MEDIA", """{"gelCode":"L1"}"""))
        // A standalone show, and the venue itself.
        val standalone = client.post("/api/rest/projects") { json("""{"name":"Standalone"}""") }.obj()["id"]!!.jsonPrimitive.int
        val standalonePatch = client.post("/api/rest/projects/$standalone/patches") {
            json("""{"universe":0,"fixtureTypeKey":"uv","key":"lone-uv","name":"Lone UV","startChannel":1}""")
        }.obj()
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_NOT_A_VENUE_RECORD, client.facet(standalone, standalonePatch.id(), "MEDIA", """{"gelCode":"L1"}"""))
        val draftPatch = client.patches(venueId).getValue("hex-1")
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_NOT_A_VENUE_RECORD, client.facet(venueId, draftPatch.id(), "MEDIA", """{"gelCode":"L1"}"""))
        // A version, which refuses every write.
        val snapshot = transaction(state.database) { RigScope.of(showId).venue!!.snapshotId.value }
        assertCode(HttpStatusCode.Conflict, "VENUE_VERSION_READ_ONLY", client.facet(snapshot, hex1.id(), "MEDIA", """{"gelCode":"L1"}"""))

        // A bad facet, a missing value, and a value with every problem named at once.
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_UNKNOWN, client.facet(showId, hex1.id(), "colour", """{}"""))
        assertCode(
            HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID,
            client.put("/api/rest/projects/$showId/patches/${hex1.id()}/facets/MEDIA") { json("{}") },
        )
        val bad = client.facet(showId, hex1.id(), "ADDRESS", """{"universe":9,"startChannel":600}""")
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, bad)
        assertTrue("universe 9" in bad.bodyAsText() && "512" in bad.bodyAsText(), bad.bodyAsText())
        val position = client.facet(showId, hex1.id(), "POSITION", """{"stageX":900,"baseYawDeg":"left","gelCode":"L1","lengthM":2}""")
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, position)
        listOf("stageX", "baseYawDeg", "gelCode", "lengthM").forEach { assertTrue(it in position.bodyAsText(), "names $it: ${position.bodyAsText()}") }
        val focus = client.facet(showId, hex1.id(), "FOCUS", """{"iris":0.5}""")
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, focus)
        assertTrue("lantern" in focus.bodyAsText(), "a hex takes no lantern: ${focus.bodyAsText()}")

        // A hidden patch takes no new override; reverting is still allowed.
        client.put("/api/rest/projects/$showId/patches/${hex1.id()}/hidden") { json("""{"hidden":true}""") }
        assertCode(HttpStatusCode.Conflict, ShowOverrides.CODE_PATCH_HIDDEN, client.facet(showId, hex1.id(), "MEDIA", """{"gelCode":"L1"}"""))
        assertEquals(HttpStatusCode.OK, client.facet(showId, hex1.id(), "MEDIA", "null").status)

        // The patch PUT still refuses a venue patch, and names the facet route.
        val put = client.put("/api/rest/projects/$showId/patches/${patches.getValue("hex-2").id()}") { json("""{"gelCode":"L1"}""") }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, put)
        assertTrue("/facets/" in put.bodyAsText(), put.bodyAsText())
    }

    @Test
    fun `a Focus override refuses a lantern an unnamed inheriting placement's zoom cannot take`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        // The profile's paired lantern takes the patch's lantern and is zoomed within it.
        transaction(state.database) {
            val snapshot = RigScope.of(showId).venue!!.snapshotId
            val profile = DaoFixturePatch.find { (DaoFixturePatches.project eq snapshot) and (DaoFixturePatches.key eq "adv2-1") }.single()
            uk.me.cormack.lighting7.models.extraPlacementsOf(profile).single().apply { lanternType = null; zoomDeg = 44.0 }
        }
        val profile = client.patches(showId).getValue("adv2-1")
        val refused = client.facet(showId, profile.id(), "FOCUS", """{"lanternType":"par64-cp62"}""")
        assertCode(HttpStatusCode.BadRequest, ShowOverrides.CODE_FACET_INVALID, refused)
        assertTrue("placement 'SR'" in refused.bodyAsText(), refused.bodyAsText())
        // Naming the placement whole, without its zoom, is accepted.
        val par = profile.placements().single().str("uuid")
        val named = client.facet(showId, profile.id(), "FOCUS", """{"lanternType":"par64-cp62","placements":[{"placementUuid":"$par"}]}""")
        assertEquals(HttpStatusCode.OK, named.status, named.bodyAsText())
    }

    @Test
    fun `an override naming a rigging the rig lacks is dormant, and says so`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val hex1 = client.patches(showId).getValue("hex-1")
        transaction(state.database) {
            val show = DaoProject[showId]
            ShowOverrides.put(show, ShowOverrides.target(show, hex1.id()), PatchFacet.POSITION, uk.me.cormack.lighting7.models.PositionFacet(riggingUuid = java.util.UUID.randomUUID().toString(), stageX = 9.0))
        }
        val after = client.patches(showId).getValue("hex-1")
        assertEquals(hex1.dbl("stageX"), after.dbl("stageX"), "the venue's position stands")
        assertTrue(after.facets().single().bool("dormant"))
        transaction(state.database) { assertTrue(ShowOverrides.drift(DaoProject[showId]).single().dormant) }
    }

    @Test
    fun `an operator may override a facet, as they may edit a patch`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val (showId, _) = showOnVenue()
        val hex1 = client.patches(showId, op).getValue("hex-1")
        val response = client.put("/api/rest/projects/$showId/patches/${hex1.id()}/facets/MEDIA") {
            header(HttpHeaders.Cookie, op)
            json("""{"value":{"gelCode":"L106"}}""")
        }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
    }

    @Test
    fun `overrides sit with hides, a rigging delete and a universe delete`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val patches = client.patches(showId)
        val hex1 = patches.getValue("hex-1")

        // Hiding keeps a patch's overrides dormant, and showing it brings them back.
        client.facet(showId, hex1.id(), "ADDRESS", """{"universe":1,"startChannel":200}""")
        client.put("/api/rest/projects/$showId/patches/${hex1.id()}/hidden") { json("""{"hidden":true}""") }
        assertFalse("hex-1" in client.patches(showId))
        assertTrue(loadedChannels(showId)[1]?.get(200) == null, "a hidden unit outputs nowhere")
        client.put("/api/rest/projects/$showId/patches/${hex1.id()}/hidden") { json("""{"hidden":false}""") }
        assertEquals(200, client.patches(showId).getValue("hex-1").int("startChannel"))

        // A venue rigging a Position override hangs a unit on cannot be hidden; one it moved a unit off can.
        val boom = rigging(showId, "Boom-SL")
        val hex3 = patches.getValue("hex-3")
        assertEquals(boom, hex3.str("riggingUuid"))
        client.facet(showId, hex3.id(), "POSITION", """{"stageX":3.0,"stageY":1.0}""")
        val hideBoom = client.put("/api/rest/projects/$showId/hides/RIGGING/$boom") { json("""{"hidden":true}""") }
        assertEquals(HttpStatusCode.OK, hideBoom.status, hideBoom.bodyAsText())
        client.put("/api/rest/projects/$showId/hides/RIGGING/$boom") { json("""{"hidden":false}""") }
        client.facet(showId, patches.getValue("hex-4").id(), "POSITION", """{"riggingUuid":"$boom","stageX":0.5}""")
        val refused = client.put("/api/rest/projects/$showId/hides/RIGGING/$boom") { json("""{"hidden":true}""") }
        assertCode(HttpStatusCode.Conflict, ShowHides.CODE_HIDE_IN_USE, refused)
        assertTrue("hex-4" in refused.bodyAsText(), refused.bodyAsText())

        // Deleting the show's own rigging detaches an override hung on it, offsets kept.
        val ownBoom = transaction(state.database) {
            DaoRigging.new { project = DaoProject[showId]; name = "Panto boom"; kind = "BOOM"; positionX = -4.0 }.let { it.id.value to it.uuid }
        }
        client.facet(showId, patches.getValue("hex-4").id(), "POSITION", """{"riggingUuid":"${ownBoom.second}","stageX":0.5,"stageZ":1.75}""")
        assertEquals(ownBoom.second.toString(), client.patches(showId).getValue("hex-4").str("riggingUuid"))
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$showId/riggings/${ownBoom.first}").status)
        val detached = client.patches(showId).getValue("hex-4")
        assertNull(detached["riggingUuid"]?.takeIf { it is JsonPrimitive && it.isString })
        assertEquals(1.75, detached.dbl("stageZ"))

        // Deleting the show's own universe takes an Address override onto it back to the venue's address.
        val ownPatch = client.post("/api/rest/projects/$showId/patches") {
            json("""{"universe":7,"fixtureTypeKey":"uv","key":"own-uv","name":"Own UV","startChannel":1}""")
        }
        assertEquals(HttpStatusCode.Created, ownPatch.status, ownPatch.bodyAsText())
        client.facet(showId, patches.getValue("hex-4").id(), "ADDRESS", """{"universe":7,"startChannel":100}""")
        assertEquals(7, client.patches(showId).getValue("hex-4").int("universe"))
        val universe7 = client.get("/api/rest/projects/$showId/universe-configs").let { r ->
            TestJson.parseToJsonElement(r.bodyAsText()).jsonArray.map { it.jsonObject }.first { it.int("universe") == 7 }
        }
        assertEquals(2, universe7.int("patchCount"), "the count is the rig's: the show's unit and the venue unit it moved there")
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/$showId/universe-configs/${universe7.int("id")}").status)
        val back = client.patches(showId).getValue("hex-4")
        assertEquals(patches.getValue("hex-4").int("universe"), back.int("universe"))
        assertEquals(patches.getValue("hex-4").int("startChannel"), back.int("startChannel"))
        transaction(state.database) {
            assertTrue(DaoShowOverride.find { (DaoShowOverrides.project eq showId) and (DaoShowOverrides.facet eq PatchFacet.ADDRESS) }
                .none { it.recordUuid == DaoFixturePatch.findById(patches.getValue("hex-4").id())!!.uuid })
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** The venue re-gels hex-1 in its draft, publishes it as v[version], and the show is pinned there. */
    private fun regelAndRepin(venueId: Int, showId: Int, gel: String, version: Int) {
        transaction(state.database) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq "hex-1") }.single().gelCode = gel
        }
        val published = runBlocking { state.venueVersions.publish(venueId, "Re-gel hex-1 $gel", "Test") }
        assertEquals(version, published.number)
        transaction(state.database) { DaoProject[showId].venueVersion = version }
    }

    private fun rigging(showId: Int, name: String): String = transaction(state.database) {
        RigScope.of(showId).riggings().single { it.name == name }.uuid.toString()
    }

    private fun loadedChannels(projectId: Int): Map<Int, Map<Int, Fixtures.ChannelMapping>> {
        val fixtures = Fixtures()
        DbFixtureLoader.loadFixtures(projectId, fixtures, state.database)
        return fixtures.getChannelMappings()
    }

    private suspend fun HttpClient.patches(projectId: Int, cookie: String? = null): Map<String, JsonObject> {
        val response = get("/api/rest/projects/$projectId/patches") { cookie?.let { header(HttpHeaders.Cookie, it) } }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it.str("key") }
    }

    private suspend fun HttpClient.facet(projectId: Int, patchId: Int, facet: String, value: String): HttpResponse =
        put("/api/rest/projects/$projectId/patches/$patchId/facets/$facet") { json("""{"value":$value}""") }

    private suspend fun HttpClient.mediaState(showId: Int): JsonObject =
        patches(showId).getValue("hex-1").facets().single { it.str("facet") == "MEDIA" }

    private fun stripFacets(patches: Map<String, JsonObject>): Map<String, JsonObject> =
        patches.mapValues { (_, p) -> JsonObject(p.filterKeys { it != "id" && it != "facets" && it != "groups" }) }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.id(): Int = int("id")
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.int
    private fun JsonObject.dbl(name: String): Double = this[name]!!.jsonPrimitive.double
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false
    private fun JsonObject.facets(): List<JsonObject> = (this["facets"] as? JsonArray).orEmpty().map { it.jsonObject }
    private fun JsonObject.placements(): List<JsonObject> = (this["extraPlacements"] as? JsonArray).orEmpty().map { it.jsonObject }

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private companion object {
        val IDENTITY = listOf("uuid", "key", "displayName", "headNumber", "fixtureTypeKey", "owner", "stageHidden", "infrastructure")
        val ADDRESS_FIELDS = listOf("universe", "subnet", "startChannel", "universeConfigUuid")
        val POSITION_FIELDS = listOf("riggingUuid", "stageX", "stageY", "stageZ", "baseYawDeg", "basePitchDeg", "baseRollDeg", "lengthM")
        val FOCUS_FIELDS = listOf("lanternType", "beamAngleDeg", "zoomDeg", "lampRotationDeg", "shutters", "gateRotationDeg", "iris", "focusSoftness", "kindOverride")
        val MEDIA_FIELDS = listOf("gelCode", "media")
    }
}
