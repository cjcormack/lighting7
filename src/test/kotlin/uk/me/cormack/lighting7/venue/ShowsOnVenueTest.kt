package uk.me.cormack.lighting7.venue

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
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoBuskRigTile
import uk.me.cormack.lighting7.models.DaoCueEvent
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoRiggings
import uk.me.cormack.lighting7.models.DaoStageElement
import uk.me.cormack.lighting7.models.DaoStageElements
import uk.me.cormack.lighting7.models.DaoStageRegions
import uk.me.cormack.lighting7.models.DaoStageViewpoints
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.RigOwner
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.StageElementKind
import uk.me.cormack.lighting7.models.StageElementLayer
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.routes.CODE_VENUE_IN_USE
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.testsupport.RICH_PROJECT_NAME
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedRichProject
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Venue plan session 3: shows on a venue. The effective rig (own rows plus the pinned version's, less
 * hides and Preset seating), Extract, the key namespace, and the writes a show is refused.
 */
class ShowsOnVenueTest : RouteIntegrationTest() {

    private fun seedShow(): Int = seedVenueReadyShow(state)

    private fun extract(showId: Int, dryRun: Boolean = false, include: Set<ExtractKind> = ExtractKind.entries.toSet()) =
        runBlocking { state.venueExtract.extract(showId, "Commemoration Hall", include, dryRun, "Test") }

    /** What a project's rig loads: every channel's fixture and property, and every group's members. */
    private fun loadedRig(projectId: Int): Pair<Map<Int, Map<Int, Fixtures.ChannelMapping>>, Map<String, List<String>>> {
        val fixtures = Fixtures()
        DbFixtureLoader.loadFixtures(projectId, fixtures, state.database)
        val groups = fixtures.groups.associate { g -> g.name to g.flatten().map { it.targetKey } }
        return fixtures.getChannelMappings() to groups
    }

    /** The effective rig's record uuids, by kind: what the scope reads. */
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

    @Test
    fun `extract moves exactly its rows, and the show loads the same fixtures at the same addresses`() {
        val showId = seedShow()
        val rigBefore = loadedRig(showId)
        val scopeBefore = scopeUuids(showId)
        val u0 = transaction(state.database) {
            DaoUniverseConfig.find { (DaoUniverseConfigs.project eq showId) and (DaoUniverseConfigs.universe eq 0) }.single().uuid
        }

        val result = extract(showId)
        val venueId = assertNotNull(result.venueId)
        assertEquals(1, result.version)
        assertEquals(listOf("Cabaret tables"), result.copiedPresetSeating)
        assertTrue(result.overridesMoved >= 1, "the universe address moved with its universe")

        // The same fixtures, on the same channels, in the same groups — read through the pin now.
        assertEquals(rigBefore, loadedRig(showId))
        assertEquals(scopeBefore, scopeUuids(showId))

        transaction(state.database) {
            val show = DaoProject[showId]
            val venue = DaoProject[venueId]
            assertEquals(ProjectKind.VENUE, venue.kind)
            assertEquals(venue.uuid, show.venueUuid)
            assertEquals(1, show.venueVersion)

            // Exactly its rows: the show keeps no patch, universe, rigging, region or group, its SET
            // elements and its Preset seating stay, and the camera and Fixed-seat views went.
            val own = RigScope.of(showId).own
            assertEquals(0, own.patches().count())
            assertEquals(0, own.universeConfigs().count())
            assertEquals(0, own.riggings().count())
            assertEquals(0, own.stageRegions().count())
            assertEquals(0, own.groups().count())
            assertEquals(0, own.stageViewpoints().count())
            val ownElements = own.stageElements().map { it.name }.toSet()
            assertEquals(setOf("Thrust deck", "Day/night cloth", "Cottage flat", "Garden arch", "Cabaret tables"), ownElements)
            assertTrue(own.stageElements().all { it.layer == StageElementLayer.SET.name || it.kind == StageElementKind.SEATING.name })

            // The venue's draft holds what moved, and the Preset block's copy under the same uuid.
            val draft = RigScope.of(venueId)
            assertEquals(scopeBefore["patches"], draft.patches().map { it.uuid }.toSet())
            assertEquals(setOf("Stalls", "House tabs", "Cabaret tables"), draft.stageElements().map { it.name }.toSet())
            val cabaret = DaoStageElement.find { DaoStageElements.name eq "Cabaret tables" }.map { it.uuid }.toSet()
            assertEquals(1, cabaret.size, "the copy keeps its uuid")

            // Every show link into the rig points at v1 now, none at the draft.
            val snapshot = RigScope.of(showId).venue!!.snapshotId
            DaoCueEvent.all().filter { it.cue.project.id.value == showId }.forEach {
                assertEquals(snapshot, it.patch.project.id, "a cue event fires v1's patch")
            }
            DaoBuskRigTile.all().filter { it.row.project.id.value == showId }.forEach { tile ->
                tile.patch?.let { assertEquals(snapshot, it.project.id) }
                tile.group?.let { assertEquals(snapshot, it.project.id) }
            }
        }

        // The universe's machine-local address survived, keyed by the venue (P4), and the show reads it.
        transaction(state.database) {
            assertEquals("10.0.0.1", Overrides.resolveUniverseAddress(venueId, u0))
            assertNull(Overrides.resolveUniverseAddress(showId, u0))
        }
        testApplication {
            mountTestApp(state)
            val client = jsonClient()
            val universes = client.get("/api/rest/projects/$showId/universe-configs").array()
            val zero = universes.first { it.jsonObject["universe"]!!.jsonPrimitive.int == 0 }.jsonObject
            assertEquals("10.0.0.1", zero["address"]!!.jsonPrimitive.content)
            assertEquals("VENUE", zero["owner"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a dry run answers the move and keeps nothing`() {
        val showId = seedShow()
        val projectsBefore = transaction(state.database) { DaoProject.all().count() }
        val dry = extract(showId, dryRun = true)
        assertTrue(dry.dryRun)
        assertNull(dry.venueId)
        assertEquals(4 + 5, dry.moved.patches.size, "every patch: 4 hexes and 5 more")
        assertTrue("Stalls" in dry.moved.elements && "House tabs" in dry.moved.elements)
        assertTrue("Thrust deck" !in dry.moved.elements, "a SET element stays")
        assertTrue(dry.relinked.isNotEmpty(), "the show's cue events and tiles would be re-pointed")

        transaction(state.database) {
            assertEquals(projectsBefore, DaoProject.all().count())
            val show = DaoProject[showId]
            assertNull(show.venueUuid)
            assertEquals(dry.moved.patches.size, RigScope.of(showId).own.patches().count().toInt())
            assertTrue(DaoProject.find { DaoProjects.uuid eq UUID.fromString(dry.venueUuid) }.empty())
        }
        assertFalse(Files.exists(state.sceneImages.projectDir(dry.venueUuid)), "no venue store is left behind")

        // And the real run after it answers the same.
        val real = extract(showId)
        assertEquals(dry.moved, real.moved)
        assertEquals(dry.relinked, real.relinked)
    }

    @Test
    fun `an extract that would split the rig is refused before anything moves`() {
        val showId = seedShow()
        val refused = runCatching { extract(showId, include = setOf(ExtractKind.PATCHES)) }.exceptionOrNull()
        assertEquals(VenueExtract.CODE_EXTRACT_INCOMPLETE, (refused as VenueException).code)
        transaction(state.database) {
            assertNull(DaoProject[showId].venueUuid)
            assertTrue(RigScope.of(showId).own.patches().count() > 0)
        }
        val again = runCatching { extract(showId); extract(showId) }.exceptionOrNull()
        assertEquals(VenueExtract.CODE_EXTRACT_REFUSED, (again as VenueException).code, "a show already on a venue")
    }

    @Test
    fun `the effective rig across hides, additions, keys, groups and seating`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedShow()
        val venueId = extract(showId).venueId!!
        val base = "/api/rest/projects/$showId"

        val patches = client.get("$base/patches").array().map { it.jsonObject }
        assertTrue(patches.all { it["owner"]!!.jsonPrimitive.content == "VENUE" })
        val hex1 = patches.first { it.key() == "hex-1" }
        val hex1Id = hex1["id"]!!.jsonPrimitive.int

        // A visible venue key is taken; so is a venue head number.
        val clash = client.post("$base/patches") { json(patchBody("hex-1", 500)) }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE, clash)
        val headClash = client.post("$base/patches") { json(patchBody("spot-1", 500, headNumber = 104)) }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE, headClash)

        // Hide it: gone from the rig, the venue group and the load; offered back on its own list.
        assertEquals(HttpStatusCode.OK, client.put("$base/patches/$hex1Id/hidden") { json("""{"hidden":true}""") }.status)
        assertTrue(client.get("$base/patches").array().none { it.jsonObject.key() == "hex-1" })
        assertEquals(listOf("hex-1"), client.get("$base/patches/hidden").array().map { it.jsonObject.key() })
        val (mappings, groups) = loadedRig(showId)
        assertTrue(mappings.values.flatMap { it.values }.none { it.fixtureKey == "hex-1" })
        assertTrue(groups.values.none { "hex-1" in it }, "a venue group's member is filtered while hidden")
        val hides = client.get("$base/hides").array().map { it.jsonObject }
        assertEquals(listOf("PATCH" to "hex-1"), hides.map { it["kind"]!!.jsonPrimitive.content to it["key"]!!.jsonPrimitive.content })

        // Its key and its channels are free for the show's own unit now.
        val added = client.post("$base/patches") { json(patchBody("hex-1", 10, headNumber = 104)) }
        assertEquals(HttpStatusCode.Created, added.status, added.bodyAsText())
        assertEquals("SHOW", TestJson.parseToJsonElement(added.bodyAsText()).jsonObject["owner"]!!.jsonPrimitive.content)
        assertTrue(loadedRig(showId).first.values.flatMap { it.values }.any { it.fixtureKey == "hex-1" })

        // And bringing the venue's back is refused while the show's holds the key.
        val back = client.put("$base/patches/$hex1Id/hidden") { json("""{"hidden":false}""") }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_KEY_TAKEN_BY_SHOW, back)

        // Rekeyed and renumbered, the show's unit still sits on the hidden one's channels: still refused.
        val addedId = TestJson.parseToJsonElement(added.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        val rekeyed = client.put("$base/patches/$addedId") { json("""{"key":"my-hex","headNumber":199}""") }
        assertEquals(HttpStatusCode.OK, rekeyed.status, rekeyed.bodyAsText())
        val stillBack = client.put("$base/patches/$hex1Id/hidden") { json("""{"hidden":false}""") }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_KEY_TAKEN_BY_SHOW, stillBack)
        assertTrue("sits on its channels" in stillBack.bodyAsText(), stillBack.bodyAsText())
        assertEquals(HttpStatusCode.OK, client.put("$base/patches/$addedId") { json("""{"key":"hex-1","headNumber":104}""") }.status)

        // A show group may hold a venue patch (D12); a venue group's name is the venue's.
        val hex2Id = patches.first { it.key() == "hex-2" }["id"]!!.jsonPrimitive.int
        val joined = client.put("$base/patches/$hex2Id") { json("""{"addToGroup":"panto-specials"}""") }
        assertEquals(HttpStatusCode.OK, joined.status, joined.bodyAsText())
        val showGroups = client.get("$base/patch-groups").array().map { it.jsonObject }
        val specials = showGroups.first { it["name"]!!.jsonPrimitive.content == "panto-specials" }
        assertEquals("SHOW", specials["owner"]!!.jsonPrimitive.content)
        assertEquals(1, specials["memberCount"]!!.jsonPrimitive.int)
        assertTrue(showGroups.any { it["name"]!!.jsonPrimitive.content == "front-wash" && it["owner"]!!.jsonPrimitive.content == "VENUE" })
        assertTrue("panto-specials" in loadedRig(showId).second.keys)
        val intoVenueGroup = client.put("$base/patches/$hex2Id") { json("""{"addToGroup":"front-wash"}""") }
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, intoVenueGroup)

        // Seating per block: the Fixed balcony is the venue's and loads; the Preset is the show's copy.
        val elements = client.get("$base/stage-elements").array().map { it.jsonObject["name"]!!.jsonPrimitive.content }
        assertEquals(1, elements.count { it == "Cabaret tables" }, "the venue's Preset copy is left out")
        assertTrue("Stalls" in elements)
        transaction(state.database) {
            val rig = RigScope.of(showId)
            val stalls = rig.stageElements(DaoStageElements.name eq "Stalls").single()
            assertEquals(RigOwner.VENUE, rig.ownerOf(stalls.project.id))
            val cabaret = rig.stageElements(DaoStageElements.name eq "Cabaret tables").single()
            assertEquals(RigOwner.SHOW, rig.ownerOf(cabaret.project.id))
        }

        // Stage bounds come from the venue's version.
        transaction(state.database) { DaoProject[showId].stageWidthM = 99.0 }
        val detail = TestJson.parseToJsonElement(client.get("/api/rest/projects/$showId").bodyAsText()).jsonObject
        assertEquals(12.0, detail["stageWidthM"]!!.jsonPrimitive.content.toDouble())
        val venue = detail["venue"]!!.jsonObject
        assertEquals("Commemoration Hall", venue["name"]!!.jsonPrimitive.content)
        assertEquals(1, venue["version"]!!.jsonPrimitive.int)
        assertEquals(1, venue["installed"]!!.jsonPrimitive.int)
        assertEquals(venueId, venue["id"]!!.jsonPrimitive.int)

        // A record something in the show still uses cannot hide: the cannon fires on a cue.
        val cannonId = patches.first { it.key() == "cannon-1" }["id"]!!.jsonPrimitive.int
        val inUse = client.put("$base/patches/$cannonId/hidden") { json("""{"hidden":true}""") }
        assertCode(HttpStatusCode.Conflict, ShowHides.CODE_HIDE_IN_USE, inUse)
        // Nor a rigging a visible fixture hangs on; but the generic route hides a viewpoint.
        val foh = transaction(state.database) { RigScope.of(showId).riggings(DaoRiggings.name eq "FOH Truss").single().uuid }
        assertCode(HttpStatusCode.Conflict, ShowHides.CODE_HIDE_IN_USE, client.put("$base/hides/RIGGING/$foh") { json("""{"hidden":true}""") })
        val desk = transaction(state.database) { RigScope.of(showId).stageViewpoints(DaoStageViewpoints.name eq "Balcony desk").single().uuid }
        assertEquals(HttpStatusCode.OK, client.put("$base/hides/VIEWPOINT/$desk") { json("""{"hidden":true}""") }.status)
        assertTrue(client.get("$base/stage-viewpoints").array().none { it.jsonObject["name"]!!.jsonPrimitive.content == "Balcony desk" })
    }

    @Test
    fun `every write to a venue row through a show is refused with VENUE_OWNED`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedShow()
        extract(showId)
        val base = "/api/rest/projects/$showId"
        val ids = transaction(state.database) {
            val rig = RigScope.of(showId)
            mapOf(
                "patch" to rig.patches(DaoFixturePatches.key eq "hex-1").single().id.value,
                "rigging" to rig.riggings().first().id.value,
                "region" to rig.stageRegions().first().id.value,
                "element" to rig.stageElements(DaoStageElements.name eq "House tabs").single().id.value,
                "viewpoint" to rig.stageViewpoints().first().id.value,
                "group" to rig.groups(DaoFixtureGroups.name eq "front-wash").single().id.value,
                "universe" to rig.universeConfigs(DaoUniverseConfigs.universe eq 1).single().id.value,
            )
        }
        val writes: List<suspend () -> HttpResponse> = listOf(
            { client.put("$base/patches/${ids["patch"]}") { json("""{"displayName":"Mine"}""") } },
            { client.delete("$base/patches/${ids["patch"]}") },
            {
                client.put("$base/patches/placements") {
                    json("""{"updates":[{"patchId":${ids["patch"]},"stageX":1.0}],"atomic":true}""")
                }
            },
            { client.put("$base/riggings/${ids["rigging"]}") { json("""{"name":"Mine"}""") } },
            { client.delete("$base/riggings/${ids["rigging"]}") },
            { client.put("$base/stage-regions/${ids["region"]}") { json("""{"name":"Mine"}""") } },
            { client.delete("$base/stage-regions/${ids["region"]}") },
            { client.put("$base/stage-elements/${ids["element"]}") { json("""{"name":"Mine"}""") } },
            { client.delete("$base/stage-elements/${ids["element"]}") },
            { client.put("$base/stage-viewpoints/${ids["viewpoint"]}") { json("""{"name":"Mine"}""") } },
            { client.delete("$base/stage-viewpoints/${ids["viewpoint"]}") },
            { client.put("$base/patch-groups/${ids["group"]}") { json("""{"name":"Mine"}""") } },
            { client.put("$base/patch-groups/${ids["group"]}") { json("""{"memberOrder":[${ids["patch"]}]}""") } },
            { client.delete("$base/patch-groups/${ids["group"]}") },
            { client.delete("$base/universe-configs/${ids["universe"]}") },
            { client.put("$base/universe-configs/${ids["universe"]}") { json("""{"controllerType":"ARTNET"}""") } },
            { client.put("/api/rest/projects/$showId") { json("""{"stageWidthM":4.0}""") } },
        )
        for (write in writes) {
            val response = write()
            assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, response)
        }
        // A name the venue holds is taken for the show's own rows too.
        assertCode(
            HttpStatusCode.Conflict, VenueRigGuard.CODE_KEY_TAKEN_BY_VENUE,
            client.post("$base/riggings") { json("""{"name":"FOH Truss"}""") },
        )
        // Reads by id answer a venue row.
        assertEquals(HttpStatusCode.OK, client.get("$base/patches/${ids["patch"]}").status)
        assertEquals(HttpStatusCode.OK, client.get("$base/stage-elements/${ids["element"]}").status)
        // And a universe's machine-local address is set through the show, under the venue's id (P4).
        val address = client.put("$base/universe-configs/${ids["universe"]}") { json("""{"address":"10.0.0.9"}""") }
        assertEquals(HttpStatusCode.OK, address.status, address.bodyAsText())
        val venueId = transaction(state.database) { RigScope.of(showId).venue!!.venueProjectId.value }
        val u1 = transaction(state.database) { DaoUniverseConfig[ids.getValue("universe")].uuid }
        assertEquals("10.0.0.9", transaction(state.database) { Overrides.resolveUniverseAddress(venueId, u1) })
    }

    @Test
    fun `a show on a venue syncs, exports and clones with its pin, and its venue cannot be deleted`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedShow()
        val venueId = extract(showId).venueId!!

        // Venue plan session 4: nothing about the pin refuses sync or export any more.
        assertEquals(HttpStatusCode.OK, client.get("/api/rest/projects/$showId/sync/config").status)
        val exported = client.post("/api/rest/projects/$showId/export") { json("{}") }
        assertEquals(HttpStatusCode.OK, exported.status, exported.bodyAsText())

        // A clone is a show on the same version, loading the same rig.
        val cloned = client.post("/api/rest/projects/$showId/clone") { json("""{"name":"Copy"}""") }
        assertEquals(HttpStatusCode.Created, cloned.status, cloned.bodyAsText())
        val cloneId = TestJson.parseToJsonElement(cloned.bodyAsText()).jsonObject["project"]!!.jsonObject["id"]!!.jsonPrimitive.content.toInt()
        transaction(state.database) {
            val show = DaoProject[showId]
            val clone = DaoProject[cloneId]
            assertEquals(show.venueUuid, clone.venueUuid)
            assertEquals(show.venueVersion, clone.venueVersion)
        }
        assertEquals(loadedRig(showId), loadedRig(cloneId))

        assertCode(HttpStatusCode.Conflict, CODE_VENUE_IN_USE, client.delete("/api/rest/projects/$venueId"))
        transaction(state.database) { assertNotNull(DaoProject.findById(venueId)) }
    }

    @Test
    fun `a new show sits on a venue's installed version, and History names it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val showId = seedShow()
        val venueId = extract(showId).venueId!!

        val created = client.post("/api/rest/projects") { json("""{"name":"Panto 2027","venueId":$venueId,"stageWidthM":3.0}""") }
        assertEquals(HttpStatusCode.Created, created.status, created.bodyAsText())
        val panto = TestJson.parseToJsonElement(created.bodyAsText()).jsonObject
        assertEquals(1, panto["venue"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(12.0, panto["stageWidthM"]!!.jsonPrimitive.content.toDouble(), "the venue's stage, not the request's")
        val pantoId = panto["id"]!!.jsonPrimitive.int
        assertEquals(
            loadedRig(showId).first.mapValues { (_, m) -> m.mapValues { it.value.fixtureKey } },
            loadedRig(pantoId).first.mapValues { (_, m) -> m.mapValues { it.value.fixtureKey } },
            "a new show on the venue loads the venue's rig",
        )

        val history = state.venueVersions.history(venueId)
        assertEquals(listOf("Panto 2027", RICH_PROJECT_NAME).sorted(), history.shows.map { it.name })
        assertEquals(setOf(showId, pantoId), history.shows.map { it.id }.toSet())
        assertTrue(history.shows.all { it.version == 1 })

        val list = client.get("/api/rest/projects").array().map { it.jsonObject }
        assertEquals(1, list.first { it["id"]!!.jsonPrimitive.int == pantoId }["venue"]!!.jsonObject["version"]!!.jsonPrimitive.int)

        // A venue with nothing published, and a project that is no venue, are refused.
        val empty = client.post("/api/rest/projects") { json("""{"name":"Empty","kind":"VENUE"}""") }
        val emptyId = TestJson.parseToJsonElement(empty.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.int
        assertCode(
            HttpStatusCode.Conflict, VenueVersions.CODE_NOTHING_PUBLISHED,
            client.post("/api/rest/projects") { json("""{"name":"Nope","venueId":$emptyId}""") },
        )
        assertCode(
            HttpStatusCode.Conflict, VenueVersions.CODE_NOT_A_VENUE,
            client.post("/api/rest/projects") { json("""{"name":"Nope","venueId":$showId}""") },
        )
    }

    @Test
    fun `extract is an admin's`() = testApplication {
        mountTestApp(state)
        seedUser(state, "admin", role = UserRole.ADMIN)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val admin = client.loginCookieHeader("admin")
        val showId = seedShow()

        val refused = client.post("/api/rest/projects/$showId/venue/extract") { cookie(op); json("""{"name":"Hall"}""") }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        val dry = client.post("/api/rest/projects/$showId/venue/extract") { cookie(admin); json("""{"name":"Hall","dryRun":true}""") }
        assertEquals(HttpStatusCode.OK, dry.status, dry.bodyAsText())
        val real = client.post("/api/rest/projects/$showId/venue/extract") { cookie(admin); json("""{"name":"Hall"}""") }
        assertEquals(HttpStatusCode.Created, real.status, real.bodyAsText())
        val venueId = TestJson.parseToJsonElement(real.bodyAsText()).jsonObject["venueId"]!!.jsonPrimitive.int
        assertNotNull(state.venueVersions.history(venueId).versions.single().publishedBy, "v1 is signed by the admin")
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun patchBody(key: String, start: Int, headNumber: Int? = null) =
        """{"universe":0,"fixtureTypeKey":"uv","key":"$key","name":"$key","startChannel":$start""" +
            (headNumber?.let { ""","headNumber":$it""" } ?: "") + "}"

    private fun JsonObject.key() = this["key"]!!.jsonPrimitive.content

    private suspend fun HttpResponse.array(): JsonArray = TestJson.parseToJsonElement(bodyAsText()).jsonArray

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
