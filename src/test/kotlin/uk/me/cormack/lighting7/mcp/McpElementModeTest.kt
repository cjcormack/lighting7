package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MCP's FX tools write a new FX on Fixtures (FX editor follow-ups plan D8) and take every mode. */
class McpElementModeTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun createLook(name: String, extra: String) {
        val fx = """{"targetType":"fixture","targetKey":"foh-1","fxType":"ColourCycle","family":"colour","blendMode":"OVERRIDE","parameters":{}$extra}"""
        val result = runBlocking { tools.executeTool("create_look", Json.parseToJsonElement("""{"name":"$name","fx":[$fx]}""").jsonObject) }
        assertTrue(result.success, result.result)
    }

    private fun storedMode(look: String): String? = transaction(state.database) {
        DaoLook.find { DaoLooks.name eq look }.single().effects.single().elementMode
    }

    @Test
    fun `an FX naming no element mode is written on Fixtures, and a named one is kept`() {
        createLook("Absent", "")
        createLook("Whole", ""","elementMode":"WHOLE"""")
        createLook("Flat", ""","elementMode":"FLAT"""")

        assertEquals("WHOLE", storedMode("Absent"))
        assertEquals("WHOLE", storedMode("Whole"))
        assertEquals("FLAT", storedMode("Flat"))
    }
}
