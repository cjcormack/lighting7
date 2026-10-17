package uk.me.cormack.lighting7.venue

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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroupMembers
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoShowHide
import uk.me.cormack.lighting7.models.DaoShowHides
import uk.me.cormack.lighting7.models.DaoShowOverride
import uk.me.cormack.lighting7.models.DaoShowOverrides
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.VenueLinks
import uk.me.cormack.lighting7.routes.CODE_VENUE_ADMIN_ONLY
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.VenueShowFixture
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * *Detach* (venue plan D9, session 7): a show on a venue flattened into a standalone show — the
 * effective rig at its pin written into its own rows, overrides applied and hides left out, every link
 * re-pointed onto them, the venue's machine overrides copied, the pin cleared — and the venue and its
 * versions untouched.
 */
class VenueDetachTest : RouteIntegrationTest() {

    @Test
    fun `a detached show loads the same fixtures at the same addresses, its overrides written in`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val rigBefore = loadedRig(f.showId)
        val scopeBefore = scopeUuids(f.showId)
        val venueBefore = venueRows(f)
        val (u0, addressBefore, stageBefore) = transaction(state.database) {
            val rig = RigScope.of(f.showId)
            val u0 = rig.universeConfigs(DaoUniverseConfigs.universe eq 0).single()
            Triple(u0.uuid, Overrides.resolveUniverseAddress(rig.overrideProjectId(u0.project.id), u0.uuid), rig.stageBounds())
        }
        assertEquals("10.0.0.1", addressBefore, "the fixture's universe address, resolved against the venue's id")
        val snapshots = transaction(state.database) { VenueVersions.versionsOf(f.venueId).map { it.snapshotProject.id } }

        val detached = client.post("/api/rest/projects/${f.showId}/detach") { json("{}") }
        assertEquals(HttpStatusCode.OK, detached.status, detached.bodyAsText())
        val result = detached.obj()
        assertFalse(result.bool("dryRun"))
        assertEquals(1, result["version"]!!.jsonPrimitive.int, "flattened at the pin")
        assertTrue(result.list("overridesApplied").single().contains("address"), "$result")
        // The pin becomes the show's origin (venue-attach plan D1).
        val venueUuid = transaction(state.database) { DaoProject[f.venueId].uuid.toString() }
        val detachedFrom = result["detachedFrom"]!!.jsonObject
        assertEquals(venueUuid, detachedFrom["venueUuid"]!!.jsonPrimitive.content)
        assertEquals(1, detachedFrom["version"]!!.jsonPrimitive.int)
        assertEquals(f.venueId, detachedFrom["venueId"]!!.jsonPrimitive.int)

        // The same fixtures on the same channels, in the same groups — read from the show's own rows now.
        assertEquals(rigBefore, loadedRig(f.showId))
        assertEquals(scopeBefore, scopeUuids(f.showId), "every record keeps its uuid")

        transaction(state.database) {
            val show = DaoProject[f.showId]
            assertNull(show.venueUuid)
            assertNull(show.venueVersion)
            assertNull(show.venueRepo)
            assertNull(RigScope.of(show).venue, "the rig is the show's own rows")
            assertEquals(stageBefore, RigScope.of(show).stageBounds(), "the stage it read from the version is its own")

            // The overridden patch carries its overrides as its own fields.
            val patch = DaoFixturePatch.find { (DaoFixturePatches.project eq f.showId) and (DaoFixturePatches.uuid eq f.overriddenUuid) }.single()
            assertEquals(470, patch.startChannel)
            assertEquals("L202", patch.gelCode)
            assertEquals(f.boomUuid, patch.rigging?.uuid)
            assertEquals(f.showId, patch.rigging!!.project.id.value)
            assertEquals(15.0, patch.baseYawDeg)

            // Hides and overrides are gone with the venue; the hidden record was not copied.
            assertEquals(0, DaoShowHide.find { DaoShowHides.project eq f.showId }.count())
            assertEquals(0, DaoShowOverride.find { DaoShowOverrides.project eq f.showId }.count())
            assertFalse(f.hiddenUuid in ownUuids(f.showId, f.hideKind), "the hidden ${f.hideKind} stays the venue's")

            // P4: the universe address resolves against the show's id now, and the venue keeps its own.
            assertEquals("10.0.0.1", Overrides.resolveUniverseAddress(f.showId, u0))
            assertEquals("10.0.0.1", Overrides.resolveUniverseAddress(f.venueId, u0))
        }

        // Nothing the show owns points into any snapshot, and every link names a row of the show's.
        for (snapshot in snapshots) {
            assertTrue(linksInto(f.showId, snapshot).values.all { it == 0 }, "links left in $snapshot: ${linksInto(f.showId, snapshot)}")
        }
        transaction(state.database) {
            for (link in VenueLinks.ALL) {
                val targets = link.table.select(link.column).where { link.owner.ownedBy(DaoProject[f.showId].id) }
                    .mapNotNull { it[link.column] }
                val owners = link.target.select(link.target.id, link.targetProjectColumn()).where { link.target.id inList targets }
                    .associate { it[link.target.id] to it[link.targetProjectColumn()].value }
                assertEquals(targets.toSet(), owners.keys, "${link.name} names a row that is gone")
                assertTrue(owners.values.all { it == f.showId }, "${link.name} links out of the show: $owners")
            }
        }

        // The venue, its draft and every version are exactly as they were.
        assertEquals(venueBefore, venueRows(f))
        assertEquals(listOf(2, 1), state.venueVersions.history(f.venueId).versions.map { it.number })
        assertTrue(state.venueVersions.history(f.venueId).shows.isEmpty(), "the venue no longer lists the show")
        val listed = client.get("/api/rest/projects/${f.showId}").obj()
        assertTrue(listed["venue"] == null || listed["venue"] is JsonNull, "a standalone show: $listed")
        assertEquals(detachedFrom, listed["detachedFrom"], "the project's detail reads the origin")
        val row = client.get("/api/rest/projects").let { TestJson.parseToJsonElement(it.bodyAsText()).jsonArray }
            .map { it.jsonObject }.single { it["id"]!!.jsonPrimitive.int == f.showId }
        assertEquals(detachedFrom, row["detachedFrom"], "and so does the project list")
        assertEquals(VenueBehind.NONE, ShowBehind.judge(state, f.showId).behind)
    }

    @Test
    fun `a dry run answers the detach and keeps nothing`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val scopeBefore = scopeUuids(f.showId)
        val ownBefore = ownCounts(f.showId)

        val dry = client.post("/api/rest/projects/${f.showId}/detach") { json("""{"dryRun":true}""") }
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        val result = dry.obj()
        assertTrue(result.bool("dryRun"))
        val patches = result["copied"]!!.jsonObject["patches"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(patches.isNotEmpty() && "panto-follow" !in patches, "the venue's patches, not the show's addition: $patches")
        assertTrue(result["relinked"]!!.jsonObject.isNotEmpty(), "$result")

        transaction(state.database) {
            assertEquals(1, DaoProject[f.showId].venueVersion, "the pin stays")
            assertNull(DaoProject[f.showId].detachedFromVenueUuid, "no origin is written by a dry run")
            assertEquals(1, DaoShowHide.find { DaoShowHides.project eq f.showId }.count())
            assertEquals(3, DaoShowOverride.find { DaoShowOverrides.project eq f.showId }.count())
        }
        assertEquals(ownBefore, ownCounts(f.showId), "no row was written")
        assertEquals(scopeBefore, scopeUuids(f.showId))
    }

    @Test
    fun `a show group's member that the show hides goes, and the rest of the group comes over`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        // A venue patch the show hides, still a member of the show's own group (filtered, not refused).
        val hiddenKey = transaction(state.database) {
            val show = DaoProject[f.showId]
            val rig = RigScope.of(show)
            val snapshot = rig.venue!!.snapshotId
            val used = DaoFixtureGroupMember.all().map { it.fixturePatch.id }.toSet()
            val spare = DaoFixturePatch.find { DaoFixturePatches.project eq snapshot }
                .filter { it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid && it.id !in used }
                .first { ShowHides.hideRefusal(show, HideKind.PATCH, it.uuid) == null }
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq f.showId) and (DaoFixtureGroups.name eq "Panto specials") }.single()
            assertTrue(ShowHides.setHiddenByUuid(show, HideKind.PATCH, spare.uuid, hidden = true))
            DaoFixtureGroupMember.new {
                this.group = group
                fixturePatch = spare
                sortOrder = 9
            }
            spare.key
        }
        val groupsBefore = loadedRig(f.showId).second

        val detached = client.post("/api/rest/projects/${f.showId}/detach") { json("{}") }
        assertEquals(HttpStatusCode.OK, detached.status, detached.bodyAsText())
        assertTrue(detached.obj().list("dropped").isNotEmpty(), detached.bodyAsText())

        assertEquals(groupsBefore, loadedRig(f.showId).second, "every group holds what it held as the rig saw it")
        transaction(state.database) {
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq f.showId) and (DaoFixtureGroups.name eq "Panto specials") }.single()
            val members = DaoFixtureGroupMember.find { DaoFixtureGroupMembers.group eq group.id }.map { it.fixturePatch }
            assertEquals(2, members.size)
            assertTrue(members.all { it.project.id.value == f.showId })
            assertFalse(DaoFixturePatch.find { (DaoFixturePatches.project eq f.showId) and (DaoFixturePatches.key eq hiddenKey) }.any())
        }
    }

    @Test
    fun `a held show detaches at its pin, and its hold goes`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val f = seedShowOnVenue(state)
        val free = freeHex(f)
        val (start, truss) = transaction(state.database) {
            val rig = RigScope.of(f.showId)
            rig.patches(DaoFixturePatches.key eq free).single().startChannel to
                rig.riggings().orderBy(DaoRiggings.sortOrder to org.jetbrains.exposed.v1.core.SortOrder.ASC).first().positionX
        }
        transaction(state.database) {
            DaoFixturePatch.find { (DaoFixturePatches.project eq f.venueId) and (DaoFixturePatches.key eq free) }.single().startChannel = 260
        }
        runBlocking { state.venueVersions.publish(f.venueId, "Re-address $free", "Test") }
        runBlocking { state.projectManager.switchProject(f.showId) }
        assertTrue(state.venueHold.status.value.held, "behind on output: held")
        assertTrue("Output is held" in RigBriefing(state).describeRig(), "describe_rig tells a model the rig is dark")
        val rigBefore = loadedRig(f.showId)

        val dry = client.post("/api/rest/projects/${f.showId}/detach") { json("""{"dryRun":true}""") }.obj()
        assertEquals("OUTPUT", dry["behind"]!!.jsonPrimitive.content)
        assertEquals(1, dry["version"]!!.jsonPrimitive.int)
        assertEquals(3, dry["installed"]!!.jsonPrimitive.int)

        val detached = client.post("/api/rest/projects/${f.showId}/detach") { json("{}") }
        assertEquals(HttpStatusCode.OK, detached.status, detached.bodyAsText())
        assertEquals(rigBefore, loadedRig(f.showId), "what it ran at v1, not the hall's v3")
        transaction(state.database) {
            val rig = RigScope.of(f.showId)
            assertEquals(start, rig.patches(DaoFixturePatches.key eq free).single().startChannel)
            assertEquals(truss, rig.riggings().orderBy(DaoRiggings.sortOrder to org.jetbrains.exposed.v1.core.SortOrder.ASC).first().positionX)
        }
        assertFalse(state.venueHold.status.value.held, "a standalone show is never behind")
        assertFalse("Output is held" in RigBriefing(state).describeRig())
        assertTrue(state.show.fixtures.controllers.none { it.held })
    }

    @Test
    fun `detach is refused for anything but a show on a venue, and either role may detach`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val admin = client.loginCookieHeader("admin")
        val f = seedShowOnVenue(state)
        val standalone = transaction(state.database) {
            DaoProject.new { name = "Studio test"; kind = ProjectKind.SHOW }.id.value
        }
        val snapshot = transaction(state.database) { VenueVersions.versionsOf(f.venueId).first().snapshotProject.id.value }

        assertCode(HttpStatusCode.Conflict, VenueDetach.CODE_DETACH_NOT_A_SHOW, client.post("/api/rest/projects/$standalone/detach") { cookie(admin); json("{}") })
        assertCode(HttpStatusCode.Conflict, VenueDetach.CODE_DETACH_NOT_A_SHOW, client.post("/api/rest/projects/${f.venueId}/detach") { cookie(admin); json("{}") })
        assertCode(HttpStatusCode.Forbidden, CODE_VENUE_ADMIN_ONLY, client.post("/api/rest/projects/${f.venueId}/detach") { cookie(op); json("{}") })
        assertEquals(HttpStatusCode.Conflict, client.post("/api/rest/projects/$snapshot/detach") { cookie(admin); json("{}") }.status)

        // A pin this desk does not hold names the venue and asks for its repo.
        transaction(state.database) { DaoProject[f.showId].venueVersion = 9 }
        assertCode(HttpStatusCode.Conflict, VenuePins.CODE_VENUE_MISSING, client.post("/api/rest/projects/${f.showId}/detach") { cookie(op); json("{}") })
        transaction(state.database) { DaoProject[f.showId].venueVersion = 1 }

        // Detach changes only the show: an operator's to make, as Update is.
        val detached = client.post("/api/rest/projects/${f.showId}/detach") { cookie(op); json("{}") }
        assertEquals(HttpStatusCode.OK, detached.status, detached.bodyAsText())
        assertCode(HttpStatusCode.Conflict, VenueDetach.CODE_DETACH_NOT_A_SHOW, client.post("/api/rest/projects/${f.showId}/detach") { cookie(op); json("{}") })
        // Its venue is free to go now.
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/rest/projects/${f.venueId}") { cookie(admin) }.status)
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** What a project's rig loads: every channel's fixture and property, and every group's members. */
    private fun loadedRig(projectId: Int): Pair<Map<Int, Map<Int, Fixtures.ChannelMapping>>, Map<String, List<String>>> {
        val fixtures = Fixtures()
        DbFixtureLoader.loadFixtures(projectId, fixtures, state.database)
        val groups = fixtures.groups.associate { g -> g.name to g.flatten().map { it.targetKey } }
        return fixtures.getChannelMappings() to groups
    }

    /** The effective rig's record uuids, by kind. */
    private fun scopeUuids(projectId: Int): Map<String, Set<UUID>> = transaction(state.database) {
        val rig = RigScope.of(projectId)
        mapOf(
            "patches" to rig.patches().map { it.uuid }.toSet(),
            "universes" to rig.universeConfigs().map { it.uuid }.toSet(),
            "riggings" to rig.riggings().map { it.uuid }.toSet(),
            "regions" to rig.stageRegions().map { it.uuid }.toSet(),
            "elements" to rig.stageElements().map { it.uuid }.toSet(),
            "viewpoints" to rig.stageViewpoints().map { it.uuid }.toSet(),
            "groups" to rig.groups().map { it.uuid }.toSet(),
        )
    }

    /** How many rows of each rig table [projectId] owns. */
    private fun ownCounts(projectId: Int): Map<String, Int> = transaction(state.database) {
        val own = RigScope.of(projectId).own
        mapOf(
            "patches" to own.patches().count().toInt(),
            "universes" to own.universeConfigs().count().toInt(),
            "riggings" to own.riggings().count().toInt(),
            "elements" to own.stageElements().count().toInt(),
            "groups" to own.groups().count().toInt(),
        )
    }

    private fun ownUuids(projectId: Int, kind: HideKind): Set<UUID> = transaction(state.database) {
        val own = RigScope.of(projectId).own
        when (kind) {
            HideKind.PATCH -> own.patches().map { it.uuid }
            HideKind.RIGGING -> own.riggings().map { it.uuid }
            HideKind.ELEMENT -> own.stageElements().map { it.uuid }
            HideKind.REGION -> own.stageRegions().map { it.uuid }
            HideKind.GROUP -> own.groups().map { it.uuid }
            HideKind.VIEWPOINT -> own.stageViewpoints().map { it.uuid }
        }.toSet()
    }

    /** The venue's draft and every version, record by record, with each row's patch address. */
    private fun venueRows(f: VenueShowFixture): Map<Int, Map<String, Set<String>>> = transaction(state.database) {
        val projects = listOf(f.venueId) + VenueVersions.versionsOf(f.venueId).map { it.snapshotProject.id.value }
        projects.associateWith { id ->
            val rig = RigScope.of(id)
            mapOf(
                "patches" to rig.patches().map { "${it.uuid}@${it.universeConfig.universe}:${it.startChannel}" }.toSet(),
                "universes" to rig.universeConfigs().map { it.uuid.toString() }.toSet(),
                "riggings" to rig.riggings().map { "${it.uuid}@${it.positionX}" }.toSet(),
                "elements" to rig.stageElements().map { it.uuid.toString() }.toSet(),
                "groups" to rig.groups().map { g -> "${g.uuid}:${rig.members(g).map { it.fixturePatch.uuid }.sorted()}" }.toSet(),
            )
        }
    }

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
        DaoFixtureGroups -> DaoFixtureGroups.project
        DaoStageElements -> DaoStageElements.project
        else -> error("no project column for ${target.tableName}")
    }

    /** A venue hex the show neither hides nor overrides. */
    private fun freeHex(f: VenueShowFixture): String = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.project eq f.venueId }
            .filter { it.fixtureTypeKey == "hex" && it.uuid != f.hiddenUuid && it.uuid != f.overriddenUuid }
            .minBy { it.key }.key
    }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.bool(name: String): Boolean = this[name]?.jsonPrimitive?.content == "true"
    private fun JsonObject.list(name: String) = this[name]?.jsonArray?.map { it.toString() }.orEmpty()

    private suspend fun assertCode(status: HttpStatusCode, code: String, response: HttpResponse) {
        val body = response.bodyAsText()
        assertEquals(status, response.status, body)
        assertEquals(code, TestJson.parseToJsonElement(body).jsonObject["code"]?.jsonPrimitive?.content, body)
    }

    private fun HttpRequestBuilder.json(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun HttpRequestBuilder.cookie(value: String) = header(HttpHeaders.Cookie, value)
}
