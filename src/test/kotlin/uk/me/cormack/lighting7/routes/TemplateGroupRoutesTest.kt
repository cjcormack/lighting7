package uk.me.cormack.lighting7.routes

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.TemplateRowDto
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Template groups at route level: CRUD, the layout write (`POST /templates/reorder`), the
 * one-family rule at each of the three places it is enforced, and the busk press's exclusivity.
 *
 * The exclusivity tests are the ones worth reading first. The stack's own tests
 * (`ProgrammerLayerStackTest`) prove `releaseSiblings` does what it says; these prove the route
 * *resolves* the siblings from the group — the half a client cannot see.
 */
class TemplateGroupRoutesTest : RouteIntegrationTest() {

    private val json = Json { ignoreUnknownKeys = true }

    private fun templates() = "/api/rest/projects/$projectId/templates"
    private fun groups() = "/api/rest/projects/$projectId/template-groups"

    private fun colourRow(value: String) = TemplateRowDto(DEFERRED_TARGET_TYPE, "", "rgbColour", value)
    private fun dimmerRow(value: String = "pct:50") = TemplateRowDto(DEFERRED_TARGET_TYPE, "", "dimmer", value)

    private suspend fun HttpClient.createTemplate(name: String, row: TemplateRowDto, groupId: Int? = null): TemplateDto {
        val resp = post(templates()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = name, rows = listOf(row), groupId = groupId))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.createGroup(name: String): TemplateGroupDto {
        val resp = post(groups()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateGroupInput(name = name))
        }
        assertEquals(HttpStatusCode.Created, resp.status, resp.bodyAsText())
        return resp.body()
    }

    private suspend fun HttpClient.reorder(vararg entries: TemplateLayoutEntry) =
        post("${templates()}/reorder") {
            contentType(ContentType.Application.Json)
            setBody(ReorderTemplatesRequest(entries.toList()))
        }

    private fun template(id: Int) = TemplateLayoutEntry(templateId = id)
    private fun group(id: Int, vararg members: Int) = TemplateLayoutEntry(groupId = id, templateIds = members.toList())

    // ─── CRUD ───────────────────────────────────────────────────────────

    @Test
    fun `create, list, rename and delete a group`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        assertEquals("Keys", keys.name)
        assertNull(keys.family, "an empty group has no family")

        assertEquals(listOf("Keys"), client.get(groups()).body<List<TemplateGroupDto>>().map { it.name })

        val renamed = client.put("${groups()}/${keys.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateGroupInput(name = "Warm Keys"))
        }.body<TemplateGroupDto>()
        assertEquals("Warm Keys", renamed.name)

        val clash = client.post(groups()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateGroupInput(name = "Warm Keys"))
        }
        assertEquals(HttpStatusCode.Conflict, clash.status)

        assertEquals(HttpStatusCode.NoContent, client.delete("${groups()}/${keys.id}").status)
        assertTrue(client.get(groups()).body<List<TemplateGroupDto>>().isEmpty())
    }

    @Test
    fun `a group's family is derived from its members`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        client.createTemplate("amber", colourRow("#FF9D4A"), groupId = keys.id)

        val listed = client.get(groups()).body<List<TemplateGroupDto>>().single()
        assertEquals("COLOUR", listed.family)
    }

    // ─── Positions ──────────────────────────────────────────────────────

    @Test
    fun `create assigns the next top-level position across templates and groups`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val amber = client.createTemplate("amber", colourRow("#FF9D4A"))
        val keys = client.createGroup("Keys")
        val blue = client.createTemplate("blue", colourRow("#0000FF"))
        // Into the group: its own sequence, starting from zero.
        val inGroup = client.createTemplate("steel", colourRow("#8899AA"), groupId = keys.id)

        assertEquals(0, amber.sortOrder)
        assertEquals(1, keys.sortOrder, "a group takes the next slot in the sequence the templates use")
        assertEquals(2, blue.sortOrder, "a template after a group takes the slot after the group")
        assertEquals(0, inGroup.sortOrder, "a grouped template is numbered within its group")
        assertEquals(keys.id, inGroup.groupId)
    }

    @Test
    fun `reorder rewrites top-level order and group membership in one call`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val amber = client.createTemplate("amber", colourRow("#FF9D4A"))
        val blue = client.createTemplate("blue", colourRow("#0000FF"))
        val steel = client.createTemplate("steel", colourRow("#8899AA"))
        val keys = client.createGroup("Keys")

        // Keys first holding blue then amber; steel alone after it.
        val resp = client.reorder(group(keys.id, blue.id, amber.id), template(steel.id))
        assertEquals(HttpStatusCode.OK, resp.status, resp.bodyAsText())

        val listed = client.get(templates()).body<List<TemplateDto>>().associateBy { it.name }
        assertEquals(keys.id, listed.getValue("blue").groupId)
        assertEquals(0, listed.getValue("blue").sortOrder)
        assertEquals(keys.id, listed.getValue("amber").groupId)
        assertEquals(1, listed.getValue("amber").sortOrder)
        assertNull(listed.getValue("steel").groupId)
        assertEquals(1, listed.getValue("steel").sortOrder)
        assertEquals(0, client.get(groups()).body<List<TemplateGroupDto>>().single().sortOrder)

        // And back out again: naming amber at top level is what takes it out of the group.
        client.reorder(template(amber.id), group(keys.id, blue.id), template(steel.id))
        val after = client.get(templates()).body<List<TemplateDto>>().associateBy { it.name }
        assertNull(after.getValue("amber").groupId, "a template named at top level leaves its group")
        assertEquals(0, after.getValue("amber").sortOrder)
        assertEquals(2, after.getValue("steel").sortOrder)
    }

    @Test
    fun `reorder refuses an incomplete layout, a duplicate, and an unknown id`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val amber = client.createTemplate("amber", colourRow("#FF9D4A"))
        val blue = client.createTemplate("blue", colourRow("#0000FF"))
        val keys = client.createGroup("Keys")

        val incomplete = client.reorder(template(amber.id), group(keys.id))
        assertEquals(HttpStatusCode.BadRequest, incomplete.status, "blue is missing")
        assertTrue(incomplete.bodyAsText().contains("${blue.id}"), incomplete.bodyAsText())

        val duplicate = client.reorder(template(amber.id), group(keys.id, amber.id), template(blue.id))
        assertEquals(HttpStatusCode.BadRequest, duplicate.status, "amber twice")

        val unknown = client.reorder(template(amber.id), template(blue.id), group(keys.id), template(999_999))
        assertEquals(HttpStatusCode.BadRequest, unknown.status)

        val malformed = client.reorder(
            TemplateLayoutEntry(templateId = amber.id, groupId = keys.id),
            template(blue.id),
        )
        assertEquals(HttpStatusCode.BadRequest, malformed.status, "exactly one of templateId / groupId")

        // Nothing moved on any refusal.
        val listed = client.get(templates()).body<List<TemplateDto>>().associateBy { it.name }
        assertNull(listed.getValue("amber").groupId)
        assertEquals(0, listed.getValue("amber").sortOrder)
        assertEquals(1, listed.getValue("blue").sortOrder)
    }

    @Test
    fun `deleting a group inlines its members where it sat and deletes no template`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val first = client.createTemplate("first", colourRow("#111111"))
        val keys = client.createGroup("Keys")
        val last = client.createTemplate("last", colourRow("#222222"))
        val amber = client.createTemplate("amber", colourRow("#FF9D4A"))
        val blue = client.createTemplate("blue", colourRow("#0000FF"))
        client.reorder(template(first.id), group(keys.id, blue.id, amber.id), template(last.id))

        assertEquals(HttpStatusCode.NoContent, client.delete("${groups()}/${keys.id}").status)

        val listed = client.get(templates()).body<List<TemplateDto>>()
        assertEquals(listOf("first", "blue", "amber", "last"), listed.map { it.name }, "members keep their order, in the group's place")
        assertEquals(listOf(0, 1, 2, 3), listed.map { it.sortOrder }, "renumbered densely")
        assertTrue(listed.all { it.groupId == null })
        assertTrue(client.get(groups()).body<List<TemplateGroupDto>>().isEmpty())
    }

    // ─── One family per group ───────────────────────────────────────────

    @Test
    fun `a group holds one family, at every surface that could break it`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        val amber = client.createTemplate("amber", colourRow("#FF9D4A"), groupId = keys.id)
        val half = client.createTemplate("half", dimmerRow())

        // 1. Create into the group.
        val create = client.post(templates()) {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "full", rows = listOf(dimmerRow("pct:100")), groupId = keys.id))
        }
        assertEquals(HttpStatusCode.Conflict, create.status, create.bodyAsText())
        assertEquals(CODE_TEMPLATE_GROUP_FAMILY, create.body<ErrorResponse>().code)

        // 2. PUT a groupId.
        val move = client.put("${templates()}/${half.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(groupId = keys.id, groupIdPresent = true))
        }
        assertEquals(HttpStatusCode.Conflict, move.status, move.bodyAsText())
        assertEquals(CODE_TEMPLATE_GROUP_FAMILY, move.body<ErrorResponse>().code)

        // 3. Reorder.
        val reorder = client.reorder(group(keys.id, amber.id, half.id))
        assertEquals(HttpStatusCode.Conflict, reorder.status, reorder.bodyAsText())
        assertEquals(CODE_TEMPLATE_GROUP_FAMILY, reorder.body<ErrorResponse>().code)

        // 4. Re-contenting a grouped template into another family, behind the group's back.
        val blue = client.createTemplate("blue", colourRow("#0000FF"), groupId = keys.id)
        val flip = client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(rows = listOf(dimmerRow())))
        }
        assertEquals(HttpStatusCode.Conflict, flip.status, flip.bodyAsText())
        assertEquals(CODE_TEMPLATE_GROUP_FAMILY, flip.body<ErrorResponse>().code)

        // The lone member of a group *can* change family — nothing is left to disagree with.
        client.reorder(group(keys.id, blue.id), template(amber.id), template(half.id))
        val lone = client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(rows = listOf(dimmerRow())))
        }
        assertEquals(HttpStatusCode.OK, lone.status, lone.bodyAsText())
        assertEquals("INTENSITY", lone.body<TemplateDto>().family)

        // Nothing above moved anything it refused.
        val listed = client.get(templates()).body<List<TemplateDto>>().associateBy { it.name }
        assertNull(listed.getValue("half").groupId)
        assertNull(listed.getValue("amber").groupId)
    }

    @Test
    fun `PUT with a groupId moves the template to the group's end, and null takes it out`() = testApplication {
        mountTestApp(state)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        client.createTemplate("amber", colourRow("#FF9D4A"), groupId = keys.id)
        val blue = client.createTemplate("blue", colourRow("#0000FF"))

        val moved = client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(groupId = keys.id, groupIdPresent = true))
        }.body<TemplateDto>()
        assertEquals(keys.id, moved.groupId)
        assertEquals(1, moved.sortOrder, "appended after amber")

        val out = client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(groupId = null, groupIdPresent = true))
        }.body<TemplateDto>()
        assertNull(out.groupId)
        assertEquals(1, out.sortOrder, "appended after the group at top level")

        // A body without the flag leaves membership alone — a rename is not a move.
        client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(groupId = keys.id, groupIdPresent = true))
        }
        val renamed = client.put("${templates()}/${blue.id}") {
            contentType(ContentType.Application.Json)
            setBody(TemplateInput(name = "steel blue"))
        }.body<TemplateDto>()
        assertEquals(keys.id, renamed.groupId)
    }

    // ─── Exclusivity ────────────────────────────────────────────────────

    private suspend fun HttpClient.toggle(templateId: Int, vararg fixtureKeys: String): ToggleTemplateResponse =
        post("${templates()}/$templateId/toggle") {
            contentType(ContentType.Application.Json)
            setBody(ToggleTemplateRequest(targets = fixtureKeys.map { TemplateTargetDto("fixture", it) }))
        }.body()

    private fun liveTemplateNames(): List<String> = transaction(state.database) {
        state.show.programmerStore.layers.map { layer ->
            DaoTemplate.findById(layer.source.id)!!.name to layer.targets.map { it.key }
        }.map { (name, keys) -> "$name@${keys.joinToString("+")}" }
    }

    @Test
    fun `toggling a grouped template releases its siblings on the same targets`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        val amber = client.createTemplate("amber", colourRow("#FF9D4A"), groupId = keys.id)
        val blue = client.createTemplate("blue", colourRow("#0000FF"), groupId = keys.id)

        val first = client.toggle(amber.id, "hex-1")
        assertEquals("applied", first.action)
        assertEquals(0, first.released, "nothing to release yet")

        val second = client.toggle(blue.id, "hex-1")
        assertEquals("applied", second.action)
        assertEquals(1, second.released, "amber's layer on the same targets is released")
        assertEquals(listOf("blue@hex-1"), liveTemplateNames())

        // Off again: a remove never touches a sibling — and there is none left to touch anyway.
        val off = client.toggle(blue.id, "hex-1")
        assertEquals("removed", off.action)
        assertEquals(0, off.released)
        assertTrue(state.show.programmerStore.layers.isEmpty())
    }

    @Test
    fun `siblings on other targets survive a grouped press`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        LocateTestSupport.seedHex(state, projectId, "hex-2", 13)
        val client = jsonClient()

        val keys = client.createGroup("Keys")
        val amber = client.createTemplate("amber", colourRow("#FF9D4A"), groupId = keys.id)
        val blue = client.createTemplate("blue", colourRow("#0000FF"), groupId = keys.id)

        client.toggle(amber.id, "hex-1")
        client.toggle(amber.id, "hex-2")
        val press = client.toggle(blue.id, "hex-1")

        assertEquals(1, press.released, "only the hex-1 layer matches the press's targets")
        assertEquals(setOf("amber@hex-2", "blue@hex-1"), liveTemplateNames().toSet())
    }

    @Test
    fun `an ungrouped template releases nothing, and a group's siblings are read at press time`() = testApplication {
        mountTestApp(state)
        LocateTestSupport.seedHex(state, projectId, "hex-1", 1)
        val client = jsonClient()

        val amber = client.createTemplate("amber", colourRow("#FF9D4A"))
        val blue = client.createTemplate("blue", colourRow("#0000FF"))

        client.toggle(amber.id, "hex-1")
        val loose = client.toggle(blue.id, "hex-1")
        assertEquals(0, loose.released, "no group, no siblings")
        assertEquals(2, state.show.programmerStore.layers.size)

        // Group them *after* both are live: the next press reads the group as it is now.
        val keys = client.createGroup("Keys")
        client.reorder(group(keys.id, amber.id, blue.id))
        client.toggle(blue.id, "hex-1") // off
        val grouped = client.toggle(blue.id, "hex-1") // on again, now with a sibling
        assertEquals(1, grouped.released)
        assertEquals(listOf("blue@hex-1"), liveTemplateNames())
    }

    // ─── Wire shape ─────────────────────────────────────────────────────

    @Test
    fun `ReorderTemplatesRequest serialization round-trips`() {
        val request = ReorderTemplatesRequest(
            listOf(
                TemplateLayoutEntry(templateId = 3),
                TemplateLayoutEntry(groupId = 7, templateIds = listOf(5, 1)),
                TemplateLayoutEntry(groupId = 8),
            ),
        )
        val serialized = json.encodeToString(request)
        val deserialized = json.decodeFromString<ReorderTemplatesRequest>(serialized)
        assertEquals(request, deserialized)
        assertTrue(deserialized.entries[2].templateIds.isEmpty(), "an empty group is a group with no members")
    }
}
