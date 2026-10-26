package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.fx.CycleLength
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.routes.CreateSpeedMasterOutcome
import uk.me.cormack.lighting7.routes.CreateSpeedMasterRequest
import uk.me.cormack.lighting7.routes.createSpeedMaster
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP effect tools and venue-visit plan S15: an effect may run on *Seconds* (D7), and a new
 * effect is one bar on the master tagged for its family's usage, else master 1 (D16) — stamped as a
 * uuid when the effect is written, as the effect editor stamps one, so null still means master 1.
 */
class McpFxTimingTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private data class StoredFx(val cycle: Double, val speedMasterUuid: String?, val seconds: Boolean)

    private fun storedFx(look: String): StoredFx = transaction(state.database) {
        val effect = DaoLook.find { DaoLooks.name eq look }.single().effects.single()
        StoredFx(effect.cycle, effect.speedMasterUuid?.toString(), effect.seconds)
    }

    private fun effect(type: String, category: String, extra: String = "") =
        """{"targetType":"fixture","targetKey":"foh-1","fxType":"$type","family":"$category","blendMode":"OVERRIDE","parameters":{}$extra}"""

    @Test
    fun `a new effect is one bar on its family's usage master, else master 1`() {
        val colourMaster = assertIs<CreateSpeedMasterOutcome.Created>(
            createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Colour", family = "colour")),
        ).dto.uuid

        val cycle = call("create_look", """{"name":"Cycle","fx":[${effect("ColourCycle", "colour")}]}""")
        assertTrue(cycle.success, cycle.result)
        assertEquals(StoredFx(CycleLength.ONE_BAR, colourMaster, false), storedFx("Cycle"))

        // No master is tagged intensity, so an intensity effect stays on master 1 — null, as everywhere.
        val pulse = call("create_look", """{"name":"Pulse","fx":[${effect("Wave", "intensity")}]}""")
        assertTrue(pulse.success, pulse.result)
        assertEquals(StoredFx(CycleLength.ONE_BAR, null, false), storedFx("Pulse"))
    }

    @Test
    fun `a Beam master takes a new beam effect`() {
        val beamMaster = assertIs<CreateSpeedMasterOutcome.Created>(
            createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Beam", family = "beam")),
        ).dto.uuid

        val zoom = call("create_look", """{"name":"Zoom","fx":[${effect("Wave", "intensity", ""","attribute":"zoom"""")}]}""")
        assertTrue(zoom.success, zoom.result)
        assertEquals(StoredFx(CycleLength.ONE_BAR, beamMaster, false), storedFx("Zoom"))
    }

    @Test
    fun `an old level wave from a tool runs as Wave, an old Focus Pull at its half-bar Bounce though cycle and direction are null`() {
        val beamMaster = assertIs<CreateSpeedMasterOutcome.Created>(
            createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Beam", family = "beam")),
        ).dto.uuid
        val pull = call("create_look", """{"name":"Pull","fx":[${effect("FocusPull", "beam", ""","cycle":null,"direction":null""")}]}""")
        assertTrue(pull.success, pull.result)
        transaction(state.database) {
            val stored = DaoLook.find { DaoLooks.name eq "Pull" }.single().effects.single()
            assertEquals("Wave", stored.fxType)
            assertEquals("intensity", stored.family)
            assertEquals("focus", stored.attribute)
            assertEquals(2.0, stored.cycle)
            assertEquals("BOUNCE", stored.direction)
            assertEquals(beamMaster, stored.speedMasterUuid?.toString())
            assertEquals(mapOf("shape" to "RAMP_UP", "low" to "64", "high" to "192", "curve" to "SINE_IN_OUT"), stored.parameters)
        }
    }

    @Test
    fun `an effect on Seconds follows no master, and asking for both is refused`() {
        createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Dim", family = "intensity"))

        val breathe = call(
            "create_look",
            """{"name":"Breathe","fx":[${effect("Wave", "intensity", ""","cycle":12,"seconds":true""")}]}""",
        )
        assertTrue(breathe.success, breathe.result)
        val stored = storedFx("Breathe")
        assertTrue(stored.seconds)
        assertEquals(12.0, stored.cycle)
        assertNull(stored.speedMasterUuid, "the usage stamp is for an effect on a master, not one on Seconds")

        val master = state.show.fxEngine.speedMasters.masterStates().first().uuid.toString()
        val both = call(
            "create_look",
            """{"name":"Both","fx":[${effect("Wave", "intensity", ""","seconds":true,"speedMasterUuid":"$master"""")}]}""",
        )
        assertFalse(both.success, "Seconds beside a master is two answers to one question")
        assertTrue(both.description.contains("seconds") || both.result.contains("seconds"), both.toString())
    }
}
