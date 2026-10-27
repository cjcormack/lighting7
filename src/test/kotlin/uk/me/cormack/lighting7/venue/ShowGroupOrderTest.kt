package uk.me.cormack.lighting7.venue

import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoShowGroupOrder
import uk.me.cormack.lighting7.models.DaoShowGroupOrders
import uk.me.cormack.lighting7.models.HideKind
import uk.me.cormack.lighting7.models.RigScope
import uk.me.cormack.lighting7.models.ShowGroupOrders
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.models.reorderGroupMembers
import uk.me.cormack.lighting7.models.storedMemberOrder
import uk.me.cormack.lighting7.routes.deleteBuskRigTilesReferencing
import uk.me.cormack.lighting7.routes.deleteFixtureGroupRows
import uk.me.cormack.lighting7.sync.ProjectCloner
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.sync.ProjectImporter
import uk.me.cormack.lighting7.sync.dto.ShowGroupOrderJson
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.loginCookieHeader
import uk.me.cormack.lighting7.testsupport.mountTestApp
import uk.me.cormack.lighting7.testsupport.seedShowOnVenue
import uk.me.cormack.lighting7.testsupport.seedUser
import uk.me.cormack.lighting7.testsupport.seedVenueReadyShow
import uk.me.cormack.lighting7.venue.VenueExtract.ExtractKind
import java.nio.file.Files
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A show's order of a venue group (group-order plan session 1): `show_group_orders` merged over the
 * pinned version's order by `RigScope.members`, written through the group PUT and `edit_groups`, read
 * the same by every reader, and carried through sync, Detach, Attach and Update.
 *
 * The venue's front-wash here holds hex-1, hex-2 and hex-4, in that order.
 */
class ShowGroupOrderTest : RouteIntegrationTest() {

    @get:Rule
    val tmp = TemporaryFolder()

    private val venueOrder = listOf("hex-1", "hex-2", "hex-4")

    // ─── The write ──────────────────────────────────────────────────────

    @Test
    fun `a show orders a venue group through the group PUT, partially, and takes the venue's back`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, venueId) = showOnVenue()
        val front = client.groups(showId).getValue("front-wash")
        assertEquals("VENUE", front.str("orderOwner"))
        assertEquals(venueOrder, client.memberKeys(showId, front.id()))
        val ids = client.patchIds(showId)

        // Partial: hex-4 and hex-1 swap the places they hold; hex-2 keeps its own.
        val set = client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-4")},${ids.getValue("hex-1")}]}""")
        assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
        assertEquals("SHOW", set.obj().str("orderOwner"))
        assertEquals(listOf("hex-4", "hex-2", "hex-1"), set.obj().members().map { it.str("fixtureKey") })
        assertEquals(listOf(0, 1, 2), set.obj().members().map { it["sortOrder"]!!.jsonPrimitive.int }, "the effective index")
        assertEquals("SHOW", client.groups(showId).getValue("front-wash").str("orderOwner"))
        assertEquals(venueOrder, venueStoredOrder(venueId), "the venue's draft is never written")
        assertEquals(venueOrder, versionStoredOrder(showId), "nor its version")

        // A further partial order merges onto the show's.
        val again = client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-1")},${ids.getValue("hex-2")}]}""")
        assertEquals(listOf("hex-4", "hex-1", "hex-2"), again.obj().members().map { it.str("fixtureKey") })

        // An order equal to the venue's leaves no row.
        val equal = client.putGroup(showId, front.id(), """{"memberOrder":[${venueOrder.joinToString { "${ids.getValue(it)}" }}]}""")
        assertEquals("VENUE", equal.obj().str("orderOwner"))
        assertEquals(0, orderRows(showId))

        // With a colour, both are the show's; an explicit null takes the venue's order back, and leaves the colour.
        val both = client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-2")},${ids.getValue("hex-1")}],"colour":"lime"}""")
        assertEquals(HttpStatusCode.OK, both.status, both.bodyAsText())
        assertEquals("SHOW", both.obj().str("orderOwner"))
        assertEquals("SHOW", both.obj().str("colourOwner"))
        val cleared = client.putGroup(showId, front.id(), """{"memberOrder":null}""")
        assertEquals("VENUE", cleared.obj().str("orderOwner"))
        assertEquals("SHOW", cleared.obj().str("colourOwner"))
        assertEquals(venueOrder, cleared.obj().members().map { it.str("fixtureKey") })

        // Anything else of the venue's group stays the venue's.
        assertCode(
            HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED,
            client.putGroup(showId, front.id(), """{"name":"Front","memberOrder":[${ids.getValue("hex-2")}]}"""),
        )
        assertEquals(0, orderRows(showId))
    }

    @Test
    fun `a hidden group's order is dormant, and a hidden member drops out of it and comes back to its place`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        val front = client.groups(showId).getValue("front-wash")
        val ids = client.patchIds(showId)
        client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-4")},${ids.getValue("hex-2")},${ids.getValue("hex-1")}]}""")

        val hex4 = transaction(state.database) { DaoFixturePatch[ids.getValue("hex-4")].uuid }
        hide(showId, HideKind.PATCH, hex4, true)
        assertEquals(listOf("hex-2", "hex-1"), client.memberKeys(showId, front.id()))
        // Reordered while hidden — to what is the venue's visible order — hex-4 keeps its place in the show's.
        val whileHidden = client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-1")},${ids.getValue("hex-2")}]}""")
        assertEquals(listOf("hex-1", "hex-2"), whileHidden.obj().members().map { it.str("fixtureKey") })
        assertEquals("SHOW", whileHidden.obj().str("orderOwner"))
        hide(showId, HideKind.PATCH, hex4, false)
        assertEquals(listOf("hex-4", "hex-1", "hex-2"), client.memberKeys(showId, front.id()))

        // A busk rig tile is the one use that keeps a group from being hidden.
        val groupUuid = transaction(state.database) {
            deleteBuskRigTilesReferencing(groupId = front.id())
            DaoFixtureGroup[front.id()].uuid
        }
        hide(showId, HideKind.GROUP, groupUuid, true)
        assertCode(HttpStatusCode.Conflict, VenueRigGuard.CODE_VENUE_OWNED, client.putGroup(showId, front.id(), """{"memberOrder":null}"""))
        assertEquals(1, orderRows(showId), "dormant, not gone")
        hide(showId, HideKind.GROUP, groupUuid, false)
        assertEquals("SHOW", client.groups(showId).getValue("front-wash").str("orderOwner"))
        assertEquals(listOf("hex-4", "hex-1", "hex-2"), client.memberKeys(showId, front.id()))
    }

    @Test
    fun `either role may order a venue group in a show`() = testApplication {
        mountTestApp(state)
        seedUser(state, "op", role = UserRole.OPERATOR)
        val client = jsonClient()
        val op = client.loginCookieHeader("op")
        val (showId, _) = showOnVenue()
        val front = client.get("/api/rest/projects/$showId/patch-groups") { cookie(op) }.let { r ->
            TestJson.parseToJsonElement(r.bodyAsText()).jsonArray.map { it.jsonObject }.single { it.str("name") == "front-wash" }
        }
        val ids = client.patchIds(showId, op)
        val written = client.put("/api/rest/projects/$showId/patch-groups/${front.id()}") {
            cookie(op)
            json("""{"memberOrder":[${ids.getValue("hex-2")},${ids.getValue("hex-1")}]}""")
        }
        assertEquals(HttpStatusCode.OK, written.status, written.bodyAsText())
        assertEquals("SHOW", written.obj().str("orderOwner"))
    }

    // ─── One read ───────────────────────────────────────────────────────

    @Test
    fun `the loader, GET groups, both group details, get_patch, describe_rig and edit_groups read one order`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()
        val (showId, _) = showOnVenue()
        runBlocking { state.projectManager.switchProject(showId) }
        val front = client.groups(showId).getValue("front-wash")
        val ids = client.patchIds(showId)
        val put = client.putGroup(showId, front.id(), """{"memberOrder":[${ids.getValue("hex-4")},${ids.getValue("hex-2")},${ids.getValue("hex-1")}]}""")
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        val wanted = listOf("hex-4", "hex-2", "hex-1")

        assertEquals(wanted, state.show.fixtures.groups.single { it.name == "front-wash" }.allMembers.map { it.key }, "the live rig, reloaded")
        val summary = client.get("/api/rest/groups").let { r ->
            TestJson.parseToJsonElement(r.bodyAsText()).jsonArray.map { it.jsonObject }.single { it.str("name") == "front-wash" }
        }
        assertEquals(wanted, summary["memberKeys"]!!.jsonArray.map { it.jsonPrimitive.content })
        val detail = client.get("/api/rest/groups/front-wash").let { TestJson.parseToJsonElement(it.bodyAsText()).jsonObject }
        assertEquals(wanted, detail.members().sortedBy { it["index"]!!.jsonPrimitive.int }.map { it.str("fixtureKey") })
        assertEquals(wanted, client.memberKeys(showId, front.id()))

        val patch = ok("get_patch", "{}")
        assertEquals(wanted, patch["groups"]!!.jsonObject["front-wash"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(listOf("front-wash"), patch["showOrderedGroups"]!!.jsonArray.map { it.jsonPrimitive.content })
        val rig = RigBriefing(state).describeRig()
        assertTrue(rig.lines().any { "**front-wash**" in it && "[hex-4, hex-2, hex-1]" in it && "order=show" in it }, rig)
        assertTrue(rig.lines().none { "**back-wash**" in it && "order=show" in it }, rig)

        val edited = ok("edit_groups", """{"groups":[{"name":"front-wash","members":["hex-2","hex-4","hex-1"]}]}""")
        val row = edited["groups"]!!.jsonArray.single().jsonObject
        assertEquals(listOf("hex-2", "hex-4", "hex-1"), row["members"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("show", row["orderOwner"]!!.jsonPrimitive.content)
        assertEquals(listOf("hex-2", "hex-4", "hex-1"), state.show.fixtures.groups.single { it.name == "front-wash" }.allMembers.map { it.key })
    }

    @Test
    fun `edit_groups orders a venue group with the same members, and refuses another set`() {
        val (showId, venueId) = showOnVenue()
        runBlocking { state.projectManager.switchProject(showId) }
        val dry = ok("edit_groups", """{"dryRun":true,"groups":[{"name":"front-wash","members":["hex-4","hex-1","hex-2"]}]}""")
        assertEquals(listOf("hex-4", "hex-1", "hex-2"), dry["groups"]!!.jsonArray.single().jsonObject["members"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(0, orderRows(showId), "a dry run keeps nothing")

        ok("edit_groups", """{"groups":[{"name":"front-wash","members":["hex-4","hex-1","hex-2"]}]}""")
        assertEquals(1, orderRows(showId))
        for (members in listOf("""["hex-4","hex-1"]""", """["hex-4","hex-1","hex-2","hex-3"]""")) {
            val refused = call("edit_groups", """{"groups":[{"name":"front-wash","members":$members}]}""")
            assertFalse(refused.success, refused.result)
            assertEquals(VenueRigGuard.CODE_VENUE_OWNED, refused.json()["code"]?.jsonPrimitive?.content, refused.result)
        }
        // The venue's order takes the venue's back.
        ok("edit_groups", """{"groups":[{"name":"front-wash","members":${venueOrder.joinToString(",", "[", "]") { "\"$it\"" }}}]}""")
        assertEquals(0, orderRows(showId))
        assertEquals(venueOrder, venueStoredOrder(venueId))
    }

    // ─── Sync ───────────────────────────────────────────────────────────

    @Test
    fun `a show's orders round-trip through its archive and a clone keeps them`() {
        val f = seedShowOnVenue(state)
        val (groupUuid, members) = transaction(state.database) {
            DaoShowGroupOrder.find { DaoShowGroupOrders.project eq f.showId }.single().let { it.groupUuid to ShowGroupOrders.decode(it.members) }
        }
        val dir = tmp.newFolder("show").toPath()
        ProjectExporter(state).export(f.showId, dir)
        val exported = Files.list(dir.resolve("showGroupOrders")).toList()
            .map { TestJson.decodeFromString(ShowGroupOrderJson.serializer(), Files.readString(it)) }.single()
        assertEquals(groupUuid.toString(), exported.groupUuid)
        assertEquals(members.map { it.toString() }, exported.members)

        val clone = ProjectCloner(state).clone(f.showId, "cloned-orders", description = null)
        transaction(state.database) {
            val source = DaoShowGroupOrder.find { DaoShowGroupOrders.project eq f.showId }.single()
            val cloned = DaoShowGroupOrder.find { DaoShowGroupOrders.project eq clone.projectId }.single()
            assertEquals(source.groupUuid, cloned.groupUuid, "the same venue group")
            assertEquals(source.members, cloned.members, "the same venue patches, in the same order")
            assertNotEquals(source.uuid, cloned.uuid, "a fresh identity")
            val rig = RigScope.of(clone.projectId)
            assertEquals(members, rig.members(rig.groups().single { it.uuid == groupUuid }).map { it.fixturePatch.uuid })
        }

        // A re-import over the show (a pull) sweeps and re-reads them.
        transaction(state.database) { DaoShowGroupOrder.find { DaoShowGroupOrders.project eq f.showId }.single().members = "[]" }
        ProjectImporter(state).replaceFromWorkingTree(f.showId, dir)
        transaction(state.database) {
            assertEquals(members, ShowGroupOrders.decode(DaoShowGroupOrder.find { DaoShowGroupOrders.project eq f.showId }.single().members))
        }
    }

    // ─── Detach, Attach, Update ─────────────────────────────────────────

    @Test
    fun `Detach writes the show's order into its own group, and Attach makes it an overlay again`() {
        val (showId, venueId) = showOnVenue()
        setOrder(showId, listOf("hex-4", "hex-2", "hex-1"))
        val detached = runBlocking { state.venueDetach.detach(showId, dryRun = false) }
        assertEquals(1, detached.ordersApplied)
        assertEquals(0, orderRows(showId))
        transaction(state.database) {
            val group = RigScope.of(showId).own.groups().single { it.name == "front-wash" }
            val stored = storedMemberOrder(group.members)
            assertEquals(listOf("hex-4", "hex-2", "hex-1"), stored.map { it.fixturePatch.key })
            assertEquals(listOf(0, 1, 2), stored.map { it.sortOrder })
        }

        // Back onto the version it left: the order differs from the origin's, so it is the show's, asked nothing.
        val review = attach(showId, venueId, 1)
        assertTrue(review.decisions.none { it.id.startsWith("group:") }, "${review.decisions}")
        assertTrue(review.drawing.any { "in your order" in it.detail }, "${review.drawing}")
        assertEquals(listOf("hex-4", "hex-2", "hex-1"), effectiveOrder(showId))
        assertEquals(1, orderRows(showId))
    }

    @Test
    fun `Attach takes the venue's reorder when only the venue reordered since the origin`() {
        val (showId, venueId) = showOnVenue()
        runBlocking { state.venueDetach.detach(showId, dryRun = false) }
        reorderVenue(venueId, listOf("hex-4", "hex-1", "hex-2"))
        val review = attach(showId, venueId, 2)
        assertTrue(review.drawing.any { "reordered its group front-wash" in it.detail }, "${review.drawing}")
        assertEquals(0, orderRows(showId))
        assertEquals(listOf("hex-4", "hex-1", "hex-2"), effectiveOrder(showId))
    }

    @Test
    fun `Attach without an origin keeps the show's order as an overlay`() {
        val (showId, venueId) = showOnVenue()
        setOrder(showId, listOf("hex-2", "hex-1", "hex-4"))
        runBlocking { state.venueDetach.detach(showId, dryRun = false) }
        forgetOrigin(showId)
        val review = attach(showId, venueId, 1)
        assertTrue(review.decisions.none { it.id.startsWith("group:") }, "${review.decisions}")
        assertEquals(listOf("hex-2", "hex-1", "hex-4"), effectiveOrder(showId))
        assertEquals(1, orderRows(showId))
    }

    @Test
    fun `Attach still asks about a group whose members differ, whatever their order`() {
        val (showId, venueId) = showOnVenue()
        runBlocking { state.venueDetach.detach(showId, dryRun = false) }
        transaction(state.database) {
            val group = RigScope.of(showId).own.groups().single { it.name == "front-wash" }
            storedMemberOrder(group.members).single { it.fixturePatch.key == "hex-2" }.delete()
            reorderGroupMembers(group, storedMemberOrder(group.members).reversed().map { it.fixturePatch.id.value })
        }
        val review = attach(showId, venueId, 1, dryRun = true)
        assertTrue(review.decisions.any { it.id.startsWith("group:") }, "${review.decisions}")
        assertEquals(0, orderRows(showId))
    }

    @Test
    fun `Update drops the order of a group the version lacks, and asks about a venue reorder`() {
        val (showId, venueId) = showOnVenue()
        setOrder(showId, listOf("hex-4", "hex-2", "hex-1"))

        // Only an added member: no reorder, nothing asked, and the newcomer keeps its venue place.
        transaction(state.database) {
            val group = venueGroup(venueId)
            DaoFixtureGroupMember.new { this.group = group; fixturePatch = venuePatch(venueId, "hex-3"); sortOrder = 1 }
            reorderGroupMembers(group, storedMemberOrder(group.members).map { it.fixturePatch.id.value })
        }
        runBlocking { state.venueVersions.publish(venueId, "Hex 3 joins the front wash", "Test") }
        val added = runBlocking { state.venueUpdate.update(showId, null, emptyList(), dryRun = false) }
        assertEquals(emptyList(), added.orders)
        assertEquals(listOf("hex-4", "hex-2", "hex-3", "hex-1"), effectiveOrder(showId))

        // The venue reorders: asked, kept by default.
        reorderVenue(venueId, listOf("hex-2", "hex-1", "hex-3", "hex-4"))
        val review = runBlocking { state.venueUpdate.review(showId, null) }
        val order = review.orders.single()
        assertEquals("front-wash", order.groupName)
        assertEquals(UpdateAnswer.KEEP_MINE, order.answer)
        assertEquals(listOf("hex-2", "hex-1", "hex-3", "hex-4"), order.venue)
        assertEquals(listOf("hex-4", "hex-2", "hex-3", "hex-1"), order.mine)
        val refused = runCatching {
            runBlocking { state.venueUpdate.update(showId, null, listOf(UpdateDecisionAnswer(order.id, UpdateAnswer.HIDE_VENUES)), dryRun = true) }
        }.exceptionOrNull() as? VenueException
        assertEquals(VenueUpdate.CODE_UPDATE_INVALID, refused?.code)
        val dry = runBlocking { state.venueUpdate.update(showId, null, listOf(UpdateDecisionAnswer(order.id, UpdateAnswer.TAKE_VENUES)), dryRun = true) }
        assertEquals(UpdateAnswer.TAKE_VENUES, dry.orders.single().answer)
        assertEquals(1, orderRows(showId), "a dry run keeps nothing")
        runBlocking { state.venueUpdate.update(showId, null, listOf(UpdateDecisionAnswer(order.id, UpdateAnswer.TAKE_VENUES)), dryRun = false) }
        assertEquals(0, orderRows(showId))
        assertEquals(listOf("hex-2", "hex-1", "hex-3", "hex-4"), effectiveOrder(showId))

        // The venue reorders to exactly the show's order: nothing to ask, and no row left restating it.
        setOrder(showId, listOf("hex-4", "hex-3", "hex-1", "hex-2"))
        reorderVenue(venueId, listOf("hex-4", "hex-3", "hex-1", "hex-2"))
        val same = runBlocking { state.venueUpdate.update(showId, null, emptyList(), dryRun = false) }
        assertEquals(emptyList(), same.orders)
        assertEquals(0, orderRows(showId))
        assertEquals(listOf("hex-4", "hex-3", "hex-1", "hex-2"), effectiveOrder(showId))

        // A kept order of a group the next version removes is dropped, and said.
        setOrder(showId, listOf("hex-4", "hex-3", "hex-2", "hex-1"))
        val groupUuid = transaction(state.database) { venueGroup(venueId).uuid }
        transaction(state.database) { deleteFixtureGroupRows(venueGroup(venueId)) }
        runBlocking { state.venueVersions.publish(venueId, "Struck the front wash", "Test") }
        val updated = runBlocking { state.venueUpdate.update(showId, null, emptyList(), dryRun = false) }
        assertTrue(updated.dropped.any { it.kind == "ORDER" && it.recordUuid == groupUuid.toString() }, "${updated.dropped}")
        assertEquals(0, orderRows(showId))
    }

    @Test
    fun `Attach keeps the show's order when the origin shares too few members to say the venue reordered`() {
        val venueUuid = UUID.randomUUID()
        val (h1, h2, h3) = List(3) { UUID.randomUUID() }
        val patches = listOf(h1, h2, h3).mapIndexed { i, u ->
            VenueAttachPairing.PatchRow(i + 1, u, "hex-${i + 1}", "Hex ${i + 1}", "hex", null, emptyList(), emptyMap())
        }
        val groupUuid = UUID.randomUUID()
        fun rows(vararg members: UUID) = VenueAttachPairing.Rows(
            patches = patches,
            groups = listOf(VenueAttachPairing.GroupRow(1, groupUuid, "front-wash", members.map { VenueAttachPairing.GroupMemberRow(it, 0.0, 0.0) })),
        )
        val plan = VenueAttachPairing.pair(
            show = rows(h3, h2, h1),
            origin = VenueAttachPairing.Version(venueUuid, "Hall", 1, rows(h1)),
            target = VenueAttachPairing.Version(venueUuid, "Hall", 2, rows(h1, h2, h3)),
        )
        assertEquals(listOf(h3, h2, h1), plan.writes.orders[groupUuid], "both added h2 and h3; neither reordered h1 alone")
        assertTrue(plan.drawing.none { "reordered" in it.detail }, "${plan.drawing}")
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private val tools by lazy { SetupTools(state, AiTools(state)) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ok(name: String, arguments: String): JsonObject =
        call(name, arguments).also { assertTrue(it.success, it.result) }.json()

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    /** The rich show with hex-4 added to its front-wash, extracted into a venue it is pinned to at v1. */
    private fun showOnVenue(): Pair<Int, Int> {
        val showId = seedVenueReadyShow(state)
        transaction(state.database) {
            val group = DaoFixtureGroup.find { (DaoFixtureGroups.project eq showId) and (DaoFixtureGroups.name eq "front-wash") }.single()
            val hex4 = DaoFixturePatch.find { (DaoFixturePatches.project eq showId) and (DaoFixturePatches.key eq "hex-4") }.single()
            DaoFixtureGroupMember.new { this.group = group; fixturePatch = hex4; sortOrder = 2 }
        }
        val venueId = runBlocking {
            state.venueExtract.extract(showId, "Commemoration Hall", ExtractKind.entries.toSet(), dryRun = false, publishedBy = "Test")
        }.venueId!!
        return showId to venueId
    }

    private fun venueGroup(venueId: Int): DaoFixtureGroup =
        DaoFixtureGroup.find { (DaoFixtureGroups.project eq venueId) and (DaoFixtureGroups.name eq "front-wash") }.single()

    private fun venuePatch(venueId: Int, key: String): DaoFixturePatch =
        DaoFixturePatch.find { (DaoFixturePatches.project eq venueId) and (DaoFixturePatches.key eq key) }.single()

    private fun venueStoredOrder(venueId: Int): List<String> = transaction(state.database) {
        storedMemberOrder(venueGroup(venueId).members).map { it.fixturePatch.key }
    }

    private fun versionStoredOrder(showId: Int): List<String> = transaction(state.database) {
        storedMemberOrder(venueGroup(RigScope.of(showId).venue!!.snapshotId.value).members).map { it.fixturePatch.key }
    }

    /** Publish the venue's draft with its front-wash in [keys]' order. */
    private fun reorderVenue(venueId: Int, keys: List<String>) {
        transaction(state.database) { reorderGroupMembers(venueGroup(venueId), keys.map { venuePatch(venueId, it).id.value }) }
        runBlocking { state.venueVersions.publish(venueId, "Front wash the other way", "Test") }
    }

    /** The show's order of the venue's front-wash, written as the route writes it. */
    private fun setOrder(showId: Int, keys: List<String>) = transaction(state.database) {
        val rig = RigScope.of(showId)
        val group = rig.groups().single { it.name == "front-wash" }
        val byKey = rig.members(group).associateBy { it.fixturePatch.key }
        ShowGroupOrders.put(DaoProject[showId].id, group.uuid, keys.map { byKey.getValue(it).fixturePatch.uuid }, rig.venueOrder(group).map { it.fixturePatch.uuid })
    }

    private fun effectiveOrder(showId: Int): List<String> = transaction(state.database) {
        val rig = RigScope.of(showId)
        rig.members(rig.groups().single { it.name == "front-wash" }).map { it.fixturePatch.key }
    }

    private fun orderRows(showId: Int): Long = transaction(state.database) {
        DaoShowGroupOrder.find { DaoShowGroupOrders.project eq showId }.count()
    }

    private fun hide(showId: Int, kind: HideKind, uuid: UUID, hidden: Boolean) = transaction(state.database) {
        ShowHides.setHiddenByUuid(DaoProject[showId], kind, uuid, hidden)
    }

    private fun forgetOrigin(showId: Int) = transaction(state.database) {
        DaoProject[showId].apply {
            detachedFromVenueUuid = null
            detachedFromVersion = null
        }
    }

    private fun attach(showId: Int, venueId: Int, to: Int, dryRun: Boolean = false): AttachReviewDto =
        runBlocking { state.venueAttach.attach(showId, venueId, to, emptyList(), dryRun) }.review

    private suspend fun HttpClient.groups(projectId: Int): Map<String, JsonObject> {
        val response = get("/api/rest/projects/$projectId/patch-groups")
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associateBy { it.str("name") }
    }

    private suspend fun HttpClient.memberKeys(projectId: Int, groupId: Int): List<String> =
        get("/api/rest/projects/$projectId/patch-groups/$groupId").obj().members().map { it.str("fixtureKey") }

    private suspend fun HttpClient.patchIds(projectId: Int, cookie: String? = null): Map<String, Int> {
        val response = get("/api/rest/projects/$projectId/patches") { cookie?.let { cookie(it) } }
        return TestJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }.associate { it.str("key") to it.id() }
    }

    private suspend fun HttpClient.putGroup(projectId: Int, groupId: Int, body: String): HttpResponse =
        put("/api/rest/projects/$projectId/patch-groups/$groupId") { json(body) }

    private suspend fun HttpResponse.obj(): JsonObject = TestJson.parseToJsonElement(bodyAsText()).jsonObject
    private fun JsonObject.members(): List<JsonObject> = this["members"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.id(): Int = this["id"]!!.jsonPrimitive.int
    private fun JsonObject.str(name: String): String = this[name]!!.jsonPrimitive.content

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
