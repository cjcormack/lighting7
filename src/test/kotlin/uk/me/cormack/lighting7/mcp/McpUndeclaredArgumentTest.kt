package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import uk.me.cormack.lighting7.ai.CHECKS_OWN_ARGUMENTS
import uk.me.cormack.lighting7.ai.overrideVenueFixturesTool
import uk.me.cormack.lighting7.ai.undeclaredArgument
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.models.UserRole
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A client holding an old tool list (terminology plan P5) is refused by name rather than having
 * the old field dropped and being told the call succeeded — over the real MCP path.
 */
class McpUndeclaredArgumentTest : RouteIntegrationTest() {

    private val user = AuthenticatedUser(1, UUID.randomUUID(), "tester", "Tester", UserRole.ADMIN, "test")

    private fun callTool(name: String, arguments: String): JsonObject = runBlocking {
        McpProtocol(state).handle(
            Json.parseToJsonElement(
                """{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"$name","arguments":$arguments}}""",
            ),
            user,
        )!!["result"]!!.jsonObject
    }

    private fun JsonObject.text() = this["content"]!!.jsonArray.first().jsonObject["text"]!!.jsonPrimitive.content
    private fun JsonObject.isError() = this["isError"]!!.jsonPrimitive.boolean

    @Test
    fun `an old top-level argument is refused by name, naming what the tool takes`() {
        for ((tool, args, path) in listOf(
            Triple("create_cue", """{"name":"Q1","adHocEffects":[]}""", "adHocEffects"),
            Triple("record_look", """{"name":"L","effectIds":[1]}""", "effectIds"),
            Triple("clear_fx", """{"effectId":1}""", "effectId"),
        )) {
            val result = callTool(tool, args)
            assertTrue(result.isError(), "$tool: ${result.text()}")
            assertTrue("'$path' is not an argument of $tool; it takes " in result.text(), result.text())
            assertTrue("reconnect" in result.text(), result.text())
        }
        assertTrue("it takes targets." in callTool("clear_fx", """{"effectId":1}""").text(), "the declared arguments are named")
    }

    @Test
    fun `an old field inside an FX item is refused with its path`() {
        val fx = """{"targetType":"fixture","targetKey":"foh-1","fxType":"Pulse","family":"intensity","attribute":"dimmer","cycle":1.0,"blendMode":"OVERRIDE","parameters":{},"size":0.5}"""
        val result = callTool("create_look", """{"name":"Throb","fx":[$fx]}""")
        assertTrue(result.isError(), result.text())
        assertTrue("'fx[0].size' is not an argument of create_look" in result.text(), result.text())
    }

    @Test
    fun `a retired argument is answered with what replaced it`() {
        for ((tool, args, hint) in listOf(
            Triple("add_cue_to_stack", """{"stackId":1,"cueId":1,"sortOrder":0}""", "after ({entryId} or {number})"),
            Triple("activate_cue_stack", """{"stackId":1,"cueId":1}""", "by entryId or by its number"),
            Triple("set_standby", """{"stackId":1,"cueId":1}""", "by entryId or by its number"),
            Triple("apply_palette", """{"paletteName":"Half","mode":"LITERALS"}""", "asValues: true"),
            Triple("apply_look", """{"lookId":1,"targets":[]}""", "takes no targets"),
            Triple("mark_up_prompt_book", """{"anchors":[{"cueId":1,"at":{"pdfPage":1,"y":0.5}}]}""", "cueId names no place"),
        )) {
            val result = callTool(tool, args)
            assertTrue(result.isError(), "$tool: ${result.text()}")
            assertTrue(hint in result.text(), "$tool → ${result.text()}")
            assertFalse("reconnect" in result.text(), "$tool: a retired argument is no sign of a stale tool list")
        }
    }

    @Test
    fun `an old attribute argument is refused, naming what replaced it`() {
        for ((tool, args, path, now) in listOf(
            Quad("set_programmer", """{"targets":[{"type":"fixture","key":"foh-1"}],"values":[{"propertyName":"dimmer","value":"200"}]}""", "values[0].propertyName", "attribute"),
            Quad("list_fixture_types", """{"properties":true}""", "properties", "attributes"),
        )) {
            val result = callTool(tool, args)
            assertTrue(result.isError(), "$tool: ${result.text()}")
            assertTrue("'$path' is not an argument of $tool: it is '$now' now" in result.text(), "$tool → ${result.text()}")
        }
    }

    private data class Quad(val tool: String, val args: String, val path: String, val now: String)

    @Test
    fun `a tool left to check its own arguments does, every problem at once`() {
        for (tool in CHECKS_OWN_ARGUMENTS) {
            val args = if (tool == "move_scenery") """{"element":"Tabs","notAnArgument":1}""" else """{"notAnArgument":1}"""
            val result = callTool(tool, args)
            assertTrue(result.isError(), "$tool: ${result.text()}")
            assertTrue("notAnArgument" in result.text(), "$tool → ${result.text()}")
            assertFalse("is not an argument of" in result.text(), "$tool: refused by the tool, not the generic check")
        }
    }

    @Test
    fun `a map argument takes any key, and a declared call passes the check`() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"fx":{"type":"array","items":{"type":"object","properties":{
               "parameters":{"type":"object","additionalProperties":{"type":"string"}}}}}}}""",
        ).jsonObject
        assertNull(undeclaredArgument(schema, Json.parseToJsonElement("""{"fx":[{"parameters":{"anything":"1"}}]}""")))
        assertEquals("fx[1].size", undeclaredArgument(schema, Json.parseToJsonElement("""{"fx":[{},{"size":1}]}"""))?.path)
        assertFalse(callTool("get_current_state", """{"include":["bpm"]}""").isError())
    }

    @Test
    fun `a facet named in any case passes the check, as the tool reads it`() {
        val input = Json.parseToJsonElement("""{"fixtures":[{"key":"foh-1","facets":{"position":"SHOW","Focus":"VENUE"}}]}""")
        assertNull(undeclaredArgument(overrideVenueFixturesTool.inputSchema, input))
    }

    @Test
    fun `a get_scene row is sent back to set_scene as it stands`() {
        val hall = callTool(
            "set_scene",
            """{"template":"proscenium-hall","templateParams":{"hallWidthM":8.6,"hallDepthM":18.4,"hallHeightM":4.55,
               "stageWidthM":6.3,"stageDepthM":11,"prosWidthM":5.1,"prosHeightM":2.9,"deckHeightM":0.95,"apronM":0.35,
               "rows":12,"seatsPerRow":12,"rowPitchM":0.95,"seatPitchM":0.52,"firstRowM":2.4,
               "balconyDepthM":2.2,"balconyHeightM":1.85}}""",
        )
        assertFalse(hall.isError(), hall.text())
        assertFalse(callTool("set_scene", """{"viewpoints":[{"name":"Row F centre","kind":"SEAT","seat":"F6"}]}""").isError())
        val scene = Json.parseToJsonElement(callTool("get_scene", "{}").text()).jsonObject
        val stalls = scene["elements"]!!.jsonArray.single { it.jsonObject["name"]!!.jsonPrimitive.content == "Stalls" }
        val rowF = scene["viewpoints"]!!.jsonArray.single()
        assertTrue("seats" in stalls.jsonObject && "seatedEye" in rowF.jsonObject, "the row carries read-only fields")
        val resent = callTool("set_scene", """{"elements":[$stalls],"viewpoints":[$rowF]}""")
        assertFalse(resent.isError(), resent.text())
    }
}
