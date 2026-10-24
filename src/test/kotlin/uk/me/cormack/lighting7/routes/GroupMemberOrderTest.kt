package uk.me.cormack.lighting7.routes

import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.models.DaoFixtureGroup
import uk.me.cormack.lighting7.models.DaoFixtureGroupMember
import uk.me.cormack.lighting7.models.DaoFixtureGroups
import uk.me.cormack.lighting7.models.DaoFixturePatch
import uk.me.cormack.lighting7.models.DaoFixturePatches
import uk.me.cormack.lighting7.models.reorderGroupMembers
import uk.me.cormack.lighting7.testsupport.LocateTestSupport
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import uk.me.cormack.lighting7.testsupport.TestJson
import uk.me.cormack.lighting7.testsupport.jsonClient
import uk.me.cormack.lighting7.testsupport.mountTestApp
import kotlin.test.assertEquals

/**
 * A group's member order (venue-visit plan S7): [reorderGroupMembers] fills the named members'
 * places and leaves the rest where they are, the patch-group PUT writes it, and the runtime group
 * answers it — `memberKeys` on the list, the members and each strategy's `orderSteps` on
 * the detail.
 */
class GroupMemberOrderTest : RouteIntegrationTest() {

    private fun seed(vararg keys: String) {
        keys.forEachIndexed { i, key -> LocateTestSupport.seedHex(state, projectId, key, 1 + 12 * i) }
        LocateTestSupport.seedGroup(state, projectId, "wash", *keys)
        state.show.fixtures.patchListChanged()
    }

    private fun patchId(key: String) = transaction(state.database) {
        DaoFixturePatch.find { DaoFixturePatches.key eq key }.single().id.value
    }

    private fun groupId() = transaction(state.database) {
        DaoFixtureGroup.find { DaoFixtureGroups.name eq "wash" }.single().id.value
    }

    private fun storedOrder(): List<Pair<String, Int>> = transaction(state.database) {
        val group = DaoFixtureGroup.find { DaoFixtureGroups.name eq "wash" }.single()
        group.members.sortedBy { it.sortOrder }.map { it.fixturePatch.key to it.sortOrder }
    }

    @Test
    fun `the members named take the places they held and the rest keep theirs`() {
        seed("a", "b", "c", "d")
        transaction(state.database) {
            val group = DaoFixtureGroup.find { DaoFixtureGroups.name eq "wash" }.single()
            // b and d trade places; a and c, not named (a hidden venue patch, say), stay put.
            reorderGroupMembers(group, listOf(patchId("d"), patchId("b"), 9999, patchId("d")))
        }
        assertEquals(listOf("a" to 0, "d" to 1, "c" to 2, "b" to 3), storedOrder())
    }

    @Test
    fun `a tie in the stored order is broken by the order the members were added`() {
        seed("a", "b", "c")
        transaction(state.database) {
            DaoFixtureGroupMember.all().forEach { it.sortOrder = 0 }
            val group = DaoFixtureGroup.find { DaoFixtureGroups.name eq "wash" }.single()
            reorderGroupMembers(group, listOf(patchId("c")))
        }
        assertEquals(listOf("a" to 0, "b" to 1, "c" to 2), storedOrder())
    }

    @Test
    fun `the PUT writes the order and the runtime group answers it`() = testApplication {
        seed("a", "b", "c", "d", "e")
        mountTestApp(state)
        val client = jsonClient()
        val put = client.put("/api/rest/projects/$projectId/patch-groups/${groupId()}") {
            contentType(ContentType.Application.Json)
            setBody(UpdatePatchGroupRequest(memberOrder = listOf("e", "a", "b", "c", "d").map(::patchId)))
        }
        assertEquals(HttpStatusCode.OK, put.status, put.bodyAsText())
        assertEquals(listOf("e", "a", "b", "c", "d"), put.bodyAsText().let { TestJson.parseToJsonElement(it) }
            .jsonObject["members"]!!.jsonArray.map { it.jsonObject["fixtureKey"]!!.jsonPrimitive.content })

        val list = TestJson.parseToJsonElement(client.get("/api/rest/groups").bodyAsText()).jsonArray
        val wash = list.single { it.jsonObject["name"]!!.jsonPrimitive.content == "wash" }.jsonObject
        assertEquals(listOf("e", "a", "b", "c", "d"), wash["memberKeys"]!!.jsonArray.map { it.jsonPrimitive.content })

        val detail = TestJson.parseToJsonElement(client.get("/api/rest/groups/wash").bodyAsText()).jsonObject
        assertEquals(listOf("e", "a", "b", "c", "d"), detail["members"]!!.jsonArray.map { it.jsonObject["fixtureKey"]!!.jsonPrimitive.content })
        val steps = detail["orderSteps"]!!.jsonObject
        assertEquals(listOf(3, 2, 1, 2, 3), steps["CENTRE_OUT"]!!.jsonArray.map { it.jsonPrimitive.int })
        assertEquals(listOf(1, 2, 3, 4, 5), steps["NORMAL"]!!.jsonArray.map { it.jsonPrimitive.int })
    }
}
