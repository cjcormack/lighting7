package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.fx.BeatDivision
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
class McpEffectTimingTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private data class StoredEffect(val beatDivision: Double, val speedMasterUuid: String?, val seconds: Boolean)

    private fun storedEffect(look: String): StoredEffect = transaction(state.database) {
        val effect = DaoLook.find { DaoLooks.name eq look }.single().effects.single()
        StoredEffect(effect.cycle, effect.speedMasterUuid?.toString(), effect.seconds)
    }

    private fun effect(type: String, category: String, extra: String = "") =
        """{"targetType":"fixture","targetKey":"foh-1","effectType":"$type","category":"$category","blendMode":"OVERRIDE","parameters":{}$extra}"""

    @Test
    fun `a new effect is one bar on its family's usage master, else master 1`() {
        val colourMaster = assertIs<CreateSpeedMasterOutcome.Created>(
            createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Colour", usage = "colour")),
        ).dto.uuid

        val cycle = call("create_look", """{"name":"Cycle","effects":[${effect("ColourCycle", "colour")}]}""")
        assertTrue(cycle.success, cycle.result)
        assertEquals(StoredEffect(BeatDivision.ONE_BAR, colourMaster, false), storedEffect("Cycle"))

        // No master is tagged intensity, so an intensity effect stays on master 1 — null, as everywhere.
        val pulse = call("create_look", """{"name":"Pulse","effects":[${effect("Pulse", "intensity")}]}""")
        assertTrue(pulse.success, pulse.result)
        assertEquals(StoredEffect(BeatDivision.ONE_BAR, null, false), storedEffect("Pulse"))
    }

    @Test
    fun `a Beam master takes a new beam effect`() {
        val beamMaster = assertIs<CreateSpeedMasterOutcome.Created>(
            createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Beam", usage = "beam")),
        ).dto.uuid

        val zoom = call("create_look", """{"name":"Zoom","effects":[${effect("ZoomWave", "beam")}]}""")
        assertTrue(zoom.success, zoom.result)
        assertEquals(StoredEffect(BeatDivision.ONE_BAR, beamMaster, false), storedEffect("Zoom"))
    }

    @Test
    fun `an effect on Seconds follows no master, and asking for both is refused`() {
        createSpeedMaster(state, state.projectManager.currentProject, CreateSpeedMasterRequest(name = "Dim", usage = "intensity"))

        val breathe = call(
            "create_look",
            """{"name":"Breathe","effects":[${effect("Pulse", "intensity", ""","beatDivision":12,"seconds":true""")}]}""",
        )
        assertTrue(breathe.success, breathe.result)
        val stored = storedEffect("Breathe")
        assertTrue(stored.seconds)
        assertEquals(12.0, stored.beatDivision)
        assertNull(stored.speedMasterUuid, "the usage stamp is for an effect on a master, not one on Seconds")

        val master = state.show.fxEngine.speedMasters.masterStates().first().uuid.toString()
        val both = call(
            "create_look",
            """{"name":"Both","effects":[${effect("Pulse", "intensity", ""","seconds":true,"speedMasterUuid":"$master"""")}]}""",
        )
        assertFalse(both.success, "Seconds beside a master is two answers to one question")
        assertTrue(both.description.contains("seconds") || both.result.contains("seconds"), both.toString())
    }
}
