package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `park_channel` / `unpark_channel`, and the parked list `get_current_state` and `describe_rig`
 * report. Park itself (transmit-time override, the unpark hand-down) is `dmx/`'s to test; this
 * pins that the tools reach the same [uk.me.cormack.lighting7.dmx.ParkManager] the Channels view
 * does, refuse what they should, and that a model can read back what is parked and what it drives.
 */
class McpParkToolsTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    private fun parkedList() =
        call("get_current_state", """{"include":["parked"]}""").json()["parked"]!!.jsonArray.map { it.jsonObject }

    /** A generic dimmer on universe 0 channel 7, so a parked address has a fixture to name. */
    private fun patchDimmerAt7() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[{"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":7}]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    @Test
    fun `the MCP server offers the park tools, and they are not read-only`() {
        val protocol = McpProtocol(state)
        val listed = runBlocking {
            protocol.handle(Json.parseToJsonElement("""{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""), user)
        }!!["result"]!!.jsonObject["tools"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        for (name in listOf("park_channel", "unpark_channel")) {
            val tool = listed[name]?.jsonObject
            assertTrue(tool != null, "$name is offered")
            assertNull(tool["annotations"], "$name writes, so it carries no readOnlyHint")
        }
        val stateEnum = listed.getValue("get_current_state").jsonObject["inputSchema"]!!.jsonObject["properties"]!!
            .jsonObject["include"]!!.jsonObject["items"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue("parked" in stateEnum, "get_current_state can be asked for the parked list alone")
    }

    @Test
    fun `park_channel parks, names what it drives, and holds the output`() {
        patchDimmerAt7()
        assertEquals(emptyList(), parkedList(), "nothing is parked to begin with, and the key is still there")

        val parked = call("park_channel", """{"universe":0,"channel":7,"value":200}""")
        assertTrue(parked.success, parked.result)
        val body = parked.json()
        assertEquals(200, body["value"]!!.jsonPrimitive.int)
        assertEquals("foh-1", body["fixtureKey"]!!.jsonPrimitive.content)
        assertEquals("FOH 1", body["fixtureName"]!!.jsonPrimitive.content)
        assertNull(body["previousValue"], "it was not parked before")

        assertEquals(200u.toUByte(), state.show.parkManager.getParkedValue(0, 7))
        assertEquals(200u.toUByte(), state.show.fixtures.controller(Universe(0, 0)).getValue(7), "park reaches the output")

        // Re-parking changes the value and says what it was.
        val repark = call("park_channel", """{"universe":0,"channel":7,"value":0}""")
        assertTrue(repark.success, repark.result)
        assertEquals(200, repark.json()["previousValue"]!!.jsonPrimitive.int)
        assertEquals(0u.toUByte(), state.show.parkManager.getParkedValue(0, 7))

        // An unpatched address parks too — a hazer on a raw channel — and says nothing drives it.
        val raw = call("park_channel", """{"universe":0,"channel":300,"value":64}""")
        assertTrue(raw.success, raw.result)
        assertNull(raw.json()["fixtureKey"])

        val listed = parkedList()
        assertEquals(listOf(7, 300), listed.map { it["channel"]!!.jsonPrimitive.int }, "in address order")
        assertEquals("foh-1", listed[0]["fixtureKey"]!!.jsonPrimitive.content)
        assertEquals(0, listed[0]["value"]!!.jsonPrimitive.int)
        assertNull(listed[1]["fixtureKey"])
    }

    @Test
    fun `park_channel refuses an address or value outside the show, and writes nothing`() {
        for (arguments in listOf(
            """{"universe":0,"channel":0,"value":10}""",
            """{"universe":0,"channel":513,"value":10}""",
            """{"universe":0,"channel":1,"value":256}""",
            """{"universe":0,"channel":1,"value":-1}""",
            """{"universe":0,"channel":1,"value":"full"}""",
            """{"universe":0,"channel":1}""",
            """{"channel":1,"value":10}""",
            // The show outputs universe 0 only: another console's "universe 1" is a miss, not a park.
            """{"universe":1,"channel":1,"value":10}""",
        )) {
            val result = call("park_channel", arguments)
            assertFalse(result.success, "$arguments is refused")
            // The answer is JSON a model can read, whatever the message holds.
            assertTrue(result.json()["error"]!!.jsonPrimitive.content.isNotBlank(), result.result)
        }
        assertTrue(
            call("park_channel", """{"universe":1,"channel":1,"value":10}""").json()["error"]!!.jsonPrimitive.content
                .contains("its universes: 0"),
            "names the universes it could have meant",
        )
        assertTrue(state.show.parkManager.getAllParked().isEmpty())
    }

    @Test
    fun `unpark_channel releases the channel at its parked value, and says when nothing was parked`() {
        patchDimmerAt7()
        assertTrue(call("park_channel", """{"universe":0,"channel":7,"value":180}""").success)

        val unparked = call("unpark_channel", """{"universe":0,"channel":7}""")
        assertTrue(unparked.success, unparked.result)
        val body = unparked.json()
        assertTrue(body["wasParked"]!!.jsonPrimitive.boolean)
        assertEquals(180, body["parkedValue"]!!.jsonPrimitive.int)
        assertEquals("foh-1", body["fixtureKey"]!!.jsonPrimitive.content)
        assertFalse(state.show.parkManager.isParked(0, 7))
        assertEquals(
            180u.toUByte(), state.show.fixtures.controller(Universe(0, 0)).getValue(7),
            "the unpark hand-down applies — the output does not jump",
        )
        assertEquals(emptyList(), parkedList())

        val again = call("unpark_channel", """{"universe":0,"channel":7}""")
        assertTrue(again.success, again.result)
        assertFalse(again.json()["wasParked"]!!.jsonPrimitive.boolean)

        assertFalse(call("unpark_channel", """{"universe":0}""").success, "a channel is required")
    }

    @Test
    fun `describe_rig lists parked channels only while something is parked`() {
        patchDimmerAt7()
        val briefing = RigBriefing(state)
        assertFalse("Parked Channels" in briefing.describeRig())

        assertTrue(call("park_channel", """{"universe":0,"channel":7,"value":255}""").success)
        assertTrue(call("park_channel", """{"universe":0,"channel":12,"value":0}""").success)
        val rig = briefing.describeRig()
        assertTrue("## Parked Channels" in rig, rig)
        assertTrue("universe 0, channel 7 at 255 — FOH 1 (key=`foh-1`)" in rig, rig)
        assertTrue("universe 0, channel 12 at 0 — not patched" in rig, rig)

        assertTrue(call("unpark_channel", """{"universe":0,"channel":7}""").success)
        assertTrue(call("unpark_channel", """{"universe":0,"channel":12}""").success)
        assertFalse("Parked Channels" in briefing.describeRig())
    }
}
