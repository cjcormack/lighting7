package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatchPlacement
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.VenueLinks
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Update* (venue plan §3.5, P3, P8; session 6): the review, then the update in one transaction —
 * every link re-pointed through `VenueLinks`, links to removed records dropped, dead keys listed,
 * drift kept and lit after, an introduced overlap a decision and a key clash a blocker.
 */
class VenueUpdateTest : RouteIntegrationTest() {

    @Test
    fun `an update re-points every link kind into the new version and leaves none in the old`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        // The two links the fixture lacks: a show patch and one of its placements on venue riggings.
        transaction(state.database) {
            val snapshot = RigScope.of(f.showId).venue!!.snapshotId
            val foh = DaoRigging.find { (DaoRiggings.project eq snapshot) and (DaoRiggings.name eq "FOH Truss") }.single()
            val addition = DaoFixturePatch.find { DaoFixturePatches.uuid eq f.additionUuid }.single()
            addition.rigging = foh
            DaoFixturePatchPlacement.new {
                fixturePatch = addition
                rigging = foh
                label = "Spare"
                stageX = 1.0
                sortOrder = 0
            }
        }
        publish(f, "FOH truss moved again") { draftRigging(f, "FOH Truss").positionX = 3.0 }
        val (v1, v3) = transaction(state.database) { snapshotOf(f, 1) to snapshotOf(f, 3) }

        // Every link the show can hold into a venue, except the two whose owner is their target.
        val ownerIsTarget = setOf("fixture_patch_placements.fixture_patch_id", "fixture_group_members.group_id")
        val before = linksInto(f.showId, v1)
        for (link in VenueLinks.ALL.filter { it.name !in ownerIsTarget }) {
            assertTrue((before[link.name] ?: 0) > 0, "the fixture holds a ${link.name} link into v1: $before")
        }

        val review = client.get("/api/rest/projects/${f.showId}/venue/update").obj()
        assertEquals(1, review.int("from"))
        assertEquals(3, review.int("to"))
        assertTrue(review.list("decisions").isEmpty() && review.list("blockers").isEmpty(), "$review")
        assertEquals(1, pinOf(f.showId), "a review writes nothing")
        assertEquals(before, linksInto(f.showId, v1))

        val update = client.post("/api/rest/projects/${f.showId}/venue/update") { json("""{"to":3,"decisions":[]}""") }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        assertTrue(update.obj().bool("applied"))
        assertEquals(3, pinOf(f.showId))
        assertTrue(linksInto(f.showId, v1).values.all { it == 0 }, "nothing left in v1: ${linksInto(f.showId, v1)}")
        assertEquals(before, linksInto(f.showId, v3), "every link moved, one for one")
        // No dangling foreign key: every link the show holds names a row that exists.
        transaction(state.database) {
            for (link in VenueLinks.ALL) {
                val targets = link.table.select(link.column).where { link.owner.ownedBy(DaoProject[f.showId].id) }
                    .mapNotNull { it[link.column] }
                val existing = link.target.select(link.target.id).where { link.target.id inList targets }.map { it[link.target.id] }.toSet()
                assertEquals(targets.toSet(), existing, "${link.name} names a row that is gone")
            }
        }
        // Hides and overrides follow by uuid.
        transaction(state.database) {
            assertEquals(1, DaoShowHide.find { DaoShowHides.project eq f.showId }.count())
            assertEquals(3, DaoShowOverride.find { DaoShowOverrides.project eq f.showId }.count())
        }
        assertEquals(VenueBehind.NONE, ShowBehind.judge(state, f.showId).behind)
    }

    @Test
    fun `links to removed records drop, and every key that will point at nothing is listed`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val gone = keyOf(f, f.overriddenUuid)
        // The addition hangs on a venue boom the venue then strikes.
        transaction(state.database) {
            val snapshot = RigScope.of(f.showId).venue!!.snapshotId
            DaoFixturePatch.find { DaoFixturePatches.uuid eq f.additionUuid }.single().rigging =
                DaoRigging.find { (DaoRiggings.project eq snapshot) and (DaoRiggings.name eq "Boom-SL") }.single()
        }
        // And hex-3, which the show's Look and busk rig name.
        for (key in listOf(gone, "hex-3")) {
            val id = transaction(state.database) { draftPatch(f, key).id.value }
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/${f.venueId}/patches/$id").status)
        }
        val boomId = transaction(state.database) { draftRigging(f, "Boom-SL").id.value }
        val struck = client.delete("/api/rest/projects/${f.venueId}/riggings/$boomId")
        assertTrue(struck.status.value in 200..299, struck.bodyAsText())
        assertEquals(3, runBlocking { state.venueVersions.publish(f.venueId, "Struck $gone, hex-3 and Boom-SL", "Test") }.number)

        val review = client.get("/api/rest/projects/${f.showId}/venue/update").obj()
        val dropped = review.list("dropped")
        assertEquals(3, dropped.count { it.str("kind") == "OVERRIDE" }, "its three overrides have nothing to apply to: $dropped")
        assertTrue(dropped.any { it.str("kind") == "LINK" && it.str("detail").contains("Panto specials") }, "the group member goes: $dropped")
        assertTrue(dropped.any { it.str("kind") == "LINK" && it.str("detail").contains("Panto follow") }, "the addition comes off the boom: $dropped")
        assertTrue(dropped.any { it.str("kind") == "LINK" && it.str("detail").contains("busk rig") }, "hex-3's busk tile goes: $dropped")
        val dead = review.list("deadReferences").filter { it.str("kind") == "FIXTURE" }
        assertTrue("hex-3" in dead.map { it.str("key") }, "$dead")
        assertTrue(
            dead.single { it.str("key") == "hex-3" }.list("usedBy").any { it.str("kind") == "LOOK" && it.str("name") == "Warm Amber" },
            "the show's Look names it: $dead",
        )
        assertTrue(review.list("output").any { it.str("uuid") == f.overriddenUuid.toString() }, "the hall's change names the strike")

        val update = client.post("/api/rest/projects/${f.showId}/venue/update") { json("""{"decisions":[]}""") }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        transaction(state.database) {
            assertEquals(0, DaoShowOverride.find { DaoShowOverrides.project eq f.showId }.count(), "the dropped overrides went")
            val addition = DaoFixturePatch.find { DaoFixturePatches.uuid eq f.additionUuid }.single()
            assertEquals(1, DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq addition.id }.count(), "the addition keeps its place in the group")
            assertNull(addition.rigging, "off the struck boom")
            assertEquals(1.5, addition.stageX, "keeping its position")
            assertEquals(3, DaoProject[f.showId].venueVersion)
        }
    }

    @Test
    fun `drift shows in the review and on the Patch list after the update`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val key = keyOf(f, f.overriddenUuid)
        publish(f, "Re-gel $key") { draftPatch(f, key).gelCode = "L299" }

        val review = client.get("/api/rest/projects/${f.showId}/venue/update").obj()
        val drift = review.list("drift").single()
        assertEquals("MEDIA", drift.str("facet"))
        assertEquals("L299", drift["venue"]!!.jsonObject.str("gelCode"))
        assertEquals("L202", drift["value"]!!.jsonObject.str("gelCode"))

        // Answered Take venue's, the override would go: shown in a dry run, which keeps nothing.
        val dry = client.post("/api/rest/projects/${f.showId}/venue/update") {
            json("""{"dryRun":true,"decisions":[{"id":"${drift.str("id")}","answer":"TAKE_VENUES"}]}""")
        }
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertEquals("TAKE_VENUES", dry.obj().list("drift").single().str("answer"))
        assertFalse(dry.obj().bool("applied"))
        assertEquals(1, pinOf(f.showId))

        // Kept by default: the Patch list lights the drift.
        val update = client.post("/api/rest/projects/${f.showId}/venue/update") { json("{}") }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        val patch = client.get("/api/rest/projects/${f.showId}/patches").let { r ->
            TestJson.parseToJsonElement(r.bodyAsText()).jsonArray.map { it.jsonObject }.single { it.str("key") == key }
        }
        assertEquals("L202", patch.str("gelCode"), "the show's gel is kept")
        val media = patch.list("facets").single { it.str("facet") == "MEDIA" }
        assertTrue(media.bool("drifted"), "$media")
        assertEquals("L299", media["venue"]!!.jsonObject.str("gelCode"))
    }

    @Test
    fun `an overlap the update introduces blocks until decided, and Keep both lands`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        // The venue hangs one of its hexes on universe 0 from channel 405, across the show's own
        // Panto follow (400–411).
        val free = freeHexOnUniverse(f, 1)
        publish(f, "$free onto universe 0") {
            val hex = draftPatch(f, free)
            hex.universeConfig = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq f.venueId) and (DaoUniverseConfigs.universe eq 0) }.single()
            hex.startChannel = 405
        }
        val review = client.get("/api/rest/projects/${f.showId}/venue/update").obj()
        val decision = review.list("decisions").single()
        assertEquals("panto-follow", decision["mine"]!!.jsonObject.str("key"))
        assertEquals(free, decision["other"]!!.jsonObject.str("key"))
        val answers = (decision["answers"] as JsonArray).map { it.jsonPrimitive.content }
        assertTrue(answers.containsAll(listOf("KEEP_BOTH", "READDRESS_MINE", "REMOVE_MINE")), "$answers")
        // hex-3 is on the show's busk rig, so hiding it would be refused: it is not offered.
        assertFalse("HIDE_VENUES" in answers, "an in-use venue fixture cannot be hidden: $answers")
        assertTrue(decision["suggestedStartChannel"] != null, "a free address is offered")

        assertCode(HttpStatusCode.Conflict, VenueUpdate.CODE_UPDATE_UNDECIDED, client.post("/api/rest/projects/${f.showId}/venue/update") { json("{}") })
        assertEquals(1, pinOf(f.showId), "refused whole")
        assertCode(
            HttpStatusCode.BadRequest, VenueUpdate.CODE_UPDATE_INVALID,
            client.post("/api/rest/projects/${f.showId}/venue/update") { json("""{"decisions":[{"id":"overlap:nope","answer":"KEEP_BOTH"}]}""") },
        )

        // Re-address mine, in a dry run: the suggestion would take.
        val dry = client.post("/api/rest/projects/${f.showId}/venue/update") {
            json("""{"dryRun":true,"decisions":[{"id":"${decision.str("id")}","answer":"READDRESS_MINE"}]}""")
        }
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        assertTrue(dry.obj().list("decisions").single().bool("decided"))

        val kept = client.post("/api/rest/projects/${f.showId}/venue/update") {
            json("""{"decisions":[{"id":"${decision.str("id")}","answer":"KEEP_BOTH"}]}""")
        }
        assertEquals(HttpStatusCode.OK, kept.status, kept.bodyAsText())
        assertEquals(3, pinOf(f.showId))
        transaction(state.database) {
            val rig = RigScope.of(f.showId)
            val starts = rig.effective(rig.patches().toList()).associate { it.key to (it.universeConfig.universe to it.startChannel) }
            assertEquals(0 to 400, starts["panto-follow"])
            assertEquals(0 to 405, starts[free], "both kept")
        }
    }

    @Test
    fun `two units re-addressed in one update are offered, and land on, different channels`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        // A second show unit beside Panto follow (400–411), and the venue hangs a hex across each.
        transaction(state.database) {
            val show = DaoProject[f.showId]
            val u0 = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq RigScope.of(show).venue!!.snapshotId) and (DaoUniverseConfigs.universe eq 0) }.single()
            DaoFixturePatch.new {
                project = show
                universeConfig = u0
                fixtureTypeKey = "hex"
                key = "panto-spot"
                displayName = "Panto spot"
                startChannel = 420
                sortOrder = 41
            }
        }
        val (first, second) = transaction(state.database) {
            DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
                .filter { it.fixtureTypeKey == "hex" && it.universeConfig.universe == 1 && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
                .map { it.key }.sorted().let { it[0] to it[1] }
        }
        publish(f, "$first and $second onto universe 0") {
            val u0 = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq f.venueId) and (DaoUniverseConfigs.universe eq 0) }.single()
            draftPatch(f, first).apply { universeConfig = u0; startChannel = 405 }
            draftPatch(f, second).apply { universeConfig = u0; startChannel = 425 }
        }
        val decisions = client.get("/api/rest/projects/${f.showId}/venue/update").obj().list("decisions")
        val byMine = decisions.associateBy { it["mine"]!!.jsonObject.str("key") }
        assertEquals(setOf("panto-follow", "panto-spot"), byMine.keys, "$decisions")
        val suggested = byMine.mapValues { it.value.int("suggestedStartChannel") }
        assertTrue(
            suggested.getValue("panto-follow") + 11 < suggested.getValue("panto-spot") ||
                suggested.getValue("panto-spot") + 11 < suggested.getValue("panto-follow"),
            "suggestions must not overlap: $suggested",
        )

        val body = decisions.joinToString(",", prefix = "[", postfix = "]") { "{\"id\":\"${it.str("id")}\",\"answer\":\"READDRESS_MINE\"}" }
        val update = client.post("/api/rest/projects/${f.showId}/venue/update") { json("{\"decisions\":$body}") }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        transaction(state.database) {
            val starts = RigScope.of(f.showId).own.patches().associate { it.key to it.startChannel }
            val a = starts.getValue("panto-follow")
            val b = starts.getValue("panto-spot")
            assertTrue(a + 11 < b || b + 11 < a, "the two re-addressed units must not land on each other: $starts")
        }
    }

    @Test
    fun `a key clash blocks the update, and an operator may review and update`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        publish(f, "A follow spot of the hall's own") {
            DaoFixturePatch.new {
                project = DaoProject[f.venueId]
                universeConfig = DaoUniverseConfig.find { (DaoUniverseConfigs.project eq f.venueId) and (DaoUniverseConfigs.universe eq 1) }.single()
                fixtureTypeKey = "hex"
                key = "panto-follow"
                displayName = "House follow"
                startChannel = 300
                sortOrder = 50
            }
        }
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val op = client.loginCookieHeader("op")

        val review = client.get("/api/rest/projects/${f.showId}/venue/update") { header(HttpHeaders.Cookie, op) }
        assertEquals(HttpStatusCode.OK, review.status, review.bodyAsText())
        val blocker = review.obj().list("blockers").single()
        assertEquals("KEY_CLASH", blocker.str("kind"))
        assertCode(
            HttpStatusCode.Conflict, VenueUpdate.CODE_UPDATE_BLOCKED,
            client.post("/api/rest/projects/${f.showId}/venue/update") { header(HttpHeaders.Cookie, op); json("{}") },
        )

        // The show renames its own; the operator updates.
        transaction(state.database) { DaoFixturePatch.find { DaoFixturePatches.uuid eq f.additionUuid }.single().key = "panto-spot" }
        val update = client.post("/api/rest/projects/${f.showId}/venue/update") { header(HttpHeaders.Cookie, op); json("{}") }
        assertEquals(HttpStatusCode.OK, update.status, update.bodyAsText())
        assertEquals(3, pinOf(f.showId))
        assertCode(
            HttpStatusCode.Conflict, VenueUpdate.CODE_UP_TO_DATE,
            client.get("/api/rest/projects/${f.showId}/venue/update") { header(HttpHeaders.Cookie, op) },
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** For each link the show's own rows hold, how many point into project [into]'s rows. */
    private fun linksInto(showId: Int, into: EntityID<Int>): Map<String, Int> = transaction(state.database) {
        val show = DaoProject[showId].id
        VenueLinks.ALL.associate { link ->
            val ids = link.target.select(link.target.id).where { link.targetProjectColumn() eq into }.map { it[link.target.id] }
            link.name to link.table.select(link.table.id).where { link.owner.ownedBy(show) and (link.column inList ids) }.count().toInt()
        }
    }

    private fun VenueLinks.Link.targetProjectColumn() = when (target) {
        DaoFixturePatches -> DaoFixturePatches.project
        DaoUniverseConfigs -> DaoUniverseConfigs.project
        DaoRiggings -> DaoRiggings.project
        uk.me.cormack.lighting7.models.DaoFixtureGroups -> uk.me.cormack.lighting7.models.DaoFixtureGroups.project
        uk.me.cormack.lighting7.models.DaoStageElements -> uk.me.cormack.lighting7.models.DaoStageElements.project
        else -> error("no project column for ${target.tableName}")
    }

    private fun snapshotOf(f: VenueShowFixture, number: Int): EntityID<Int> =
        VenueVersions.versionsOf(f.venueId).single { it.number == number }.snapshotProject.id

    private fun pinOf(showId: Int): Int? = transaction(state.database) { DaoProject[showId].venueVersion }

    private fun publish(f: VenueShowFixture, note: String, change: () -> Unit): Int {
        transaction(state.database) { change() }
        return runBlocking { state.venueVersions.publish(f.venueId, note, "Test") }.number
    }

    private fun draftPatch(f: VenueShowFixture, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.key eq key) }.single()

    private fun draftRigging(f: VenueShowFixture, name: String): DaoRigging =
        DaoRigging.find { (DaoRiggings.project eq f.venueId) and (DaoRiggings.name eq name) }.single()

    private fun keyOf(f: VenueShowFixture, uuid: java.util.UUID): String = transaction(state.database) {
        DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.uuid eq uuid) }.single().key
    }

    /** A venue hex on universe [universe] the show neither hides nor overrides. */
    private fun freeHexOnUniverse(f: VenueShowFixture, universe: Int): String = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
            .filter { it.fixtureTypeKey == "hex" && it.universeConfig.universe == universe && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
            .minBy { it.key }.key
    }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content
    private fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.int
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.boolean ?: false
    private fun JsonObject.list(name: String): List<JsonObject> = (this[name] as? JsonArray).orEmpty().map { it.jsonObject }

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
