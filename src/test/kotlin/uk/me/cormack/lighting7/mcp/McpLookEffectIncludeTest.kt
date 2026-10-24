package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.fx.FxEngine
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.routes.UpdateEffectRequest
import uk.me.cormack.lighting7.routes.applyEffectUpdate
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Include and Update for a Look's **effects**: Include runs each in the programmer band on its own
 * target, and Update writes an edit back onto the effect it came from. Before this, a Look of effects
 * alone included nothing and its effects could be neither seen nor changed.
 */
class McpLookEffectIncludeTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ok(name: String, arguments: String): JsonObject {
        val result = call(name, arguments)
        assertTrue(result.success, "$name: ${result.result}")
        return Json.parseToJsonElement(result.result).jsonObject
    }

    /** Two dimmers. */
    private fun patchRig() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":1},
                        {"key":"foh-2","name":"FOH 2","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":2}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    /** A Look of one Pulse on [fixture], and its id. */
    private fun pulseLook(name: String = "Throb", fixture: String = "foh-1"): Int {
        val pulse = """{"targetType":"fixture","targetKey":"$fixture","effectType":"Pulse","category":"intensity","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""
        return ok("create_look", """{"name":"$name","effects":[$pulse]}""")["lookId"]!!.jsonPrimitive.int
    }

    private fun included(lookId: Int): List<FxInstance> =
        state.show.fxEngine.getActiveEffects().filter { it.includedLookEffect?.lookId == lookId }

    private fun storedEffect(name: String): DaoLookEffect = transaction(state.database) {
        DaoLook.find { DaoLooks.name eq name }.single().effects.single()
    }

    private fun warnings(result: JsonObject) = result["warnings"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun `an effects-only Look's effect runs on its own target, and Update writes the edit back`() {
        patchRig()
        val lookId = pulseLook()

        val include = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(1, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertTrue(warnings(include).isEmpty(), "an effect was staged, so there is nothing to warn about: $include")
        val running = included(lookId).single()
        assertEquals("foh-1", running.target.targetKey)
        assertTrue(FxEngine.isProgrammerFxPriority(running.priority) && running.isLocalEffect, "a programmer-band effect Clear takes")
        assertFalse(state.show.fxEngine.effectDto(running).editedSinceInclude, "nothing edited yet")

        // Nothing changed: Update writes no effect.
        val untouched = ok("update_from_programmer", "{}")
        assertEquals(0, untouched["effectsWritten"]!!.jsonPrimitive.int, "$untouched")

        applyEffectUpdate(state, running.id, UpdateEffectRequest(beatDivision = 2.0, blendMode = "MULTIPLY"))
        assertTrue(state.show.fxEngine.effectDto(state.show.fxEngine.getEffect(running.id)!!).editedSinceInclude)

        val update = ok("update_from_programmer", "{}")
        assertEquals(1, update["effectsWritten"]!!.jsonPrimitive.int, "$update")
        val stored = storedEffect("Throb")
        transaction(state.database) {
            assertEquals(2.0, stored.cycle)
            assertEquals("MULTIPLY", stored.blendMode)
            assertEquals("fixture" to "foh-1", stored.targetType to stored.targetKey, "the edit is how it runs, not where")
        }

        // Written is the new baseline: a second Update writes nothing again.
        assertEquals(0, ok("update_from_programmer", "{}")["effectsWritten"]!!.jsonPrimitive.int)
        assertFalse(state.show.fxEngine.effectDto(state.show.fxEngine.getEffect(running.id)!!).editedSinceInclude)

        ok("clear_programmer", "{}")
        assertTrue(included(lookId).isEmpty(), "Clear takes the included effects with the values")
    }

    @Test
    fun `Update writes only what was edited, and keeps a setting the included instance cannot show`() {
        patchRig()
        val lookId = pulseLook()
        // An element mode is a group effect's: on a fixture, the instance reports none.
        transaction(state.database) { storedEffect("Throb").elementMode = "FLAT" }
        state.show.lookRegistry.invalidateAll()

        ok("include_into_programmer", """{"lookId":$lookId}""")
        val instance = included(lookId).single()
        applyEffectUpdate(state, instance.id, UpdateEffectRequest(beatDivision = 4.0))
        assertTrue(state.show.fxEngine.fxStateFlow.value.effectStates.getValue(instance.id).editedSinceInclude)

        assertEquals(1, ok("update_from_programmer", "{}")["effectsWritten"]!!.jsonPrimitive.int)
        transaction(state.database) {
            val stored = storedEffect("Throb")
            assertEquals(4.0, stored.cycle, "the edit lands")
            assertEquals("FLAT", stored.elementMode, "a field the operator did not move keeps what the row says")
            assertEquals("foh-1", stored.targetKey)
        }
        // And every client is told the edit is written: the reported state is re-emitted.
        assertFalse(
            state.show.fxEngine.fxStateFlow.value.effectStates.getValue(instance.id).editedSinceInclude,
            "the active-effect report no longer offers an Update with nothing to write",
        )
    }

    @Test
    fun `two instances of one effect write it back once, one instance's edit whole`() {
        patchRig()
        val lookId = pulseLook()
        ok("include_into_programmer", """{"lookId":$lookId}""")
        val first = included(lookId).single()
        // A changed type is no longer the Look's running effect to Include, so a second Include
        // spawns another instance of the same row.
        applyEffectUpdate(state, first.id, UpdateEffectRequest(effectType = "SineWave"))
        ok("include_into_programmer", """{"lookId":$lookId}""")
        val second = included(lookId).single { it.id != first.id }
        applyEffectUpdate(state, second.id, UpdateEffectRequest(beatDivision = 4.0))

        assertEquals(1, ok("update_from_programmer", "{}")["effectsWritten"]!!.jsonPrimitive.int)
        val stored = transaction(state.database) { storedEffect("Throb").let { it.fxType to it.cycle } }
        assertTrue(stored in setOf("SineWave" to 1.0, "Pulse" to 4.0), "one instance's edit, never the two merged: $stored")
    }

    @Test
    fun `Include runs a Look's bound effects only, never on the desk selection, and never twice`() {
        patchRig()
        val lookId = pulseLook()
        // One an older database left deferred, written past the write boundary: it is not run.
        transaction(state.database) {
            val look = DaoLook.findById(lookId)!!
            DaoLookEffect.new {
                this.look = look
                targetType = DEFERRED_TARGET_TYPE; targetKey = ""
                fxType = "SineWave"; family = "intensity"; attribute = "dimmer"
                cycle = 1.0; blendMode = "OVERRIDE"; order = "NORMAL"
                parameters = emptyMap()
                sortOrder = 1
            }
        }
        state.show.lookRegistry.invalidateAll()
        state.deskSelection.set(listOf(CueTargetDto("fixture", "foh-2")))

        val include = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(1, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertEquals(listOf("foh-1" to "Pulse"), included(lookId).map { it.target.targetKey to it.registrationId })
        assertTrue(warnings(include).isEmpty(), "$include")

        // Included again over itself: the first Include's instance is already running it.
        val again = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(0, again["fxSpawned"]!!.jsonPrimitive.int)
        assertEquals(1, included(lookId).size, "not doubled")
    }

    @Test
    fun `a Look already live on stage is not run a second time by Include, and says so`() {
        patchRig()
        val lookId = pulseLook()
        ok("apply_look", """{"lookId":$lookId}""")

        val include = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(0, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertTrue(included(lookId).isEmpty())
        assertTrue(warnings(include).single().contains("already running where it is live"), "$include")
    }

    @Test
    fun `includeFx false leaves the effects out`() {
        patchRig()
        val lookId = pulseLook()

        val withoutFx = ok("include_into_programmer", """{"lookId":$lookId,"includeFx":false}""")
        assertEquals(0, withoutFx["fxSpawned"]!!.jsonPrimitive.int)
        assertTrue(included(lookId).isEmpty())

        val include = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(1, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertEquals(listOf("foh-1"), included(lookId).map { it.target.targetKey })
    }
}
