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
 * Include and Update for a Look's **effects**: Include runs them in the programmer band — a deferred
 * one on the selection it is included onto — and Update writes an edit back onto the effect it came
 * from. Before this, a Look of effects alone included nothing and its effects could be neither seen
 * nor changed.
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

    /** A Look of one deferred Pulse — what `create_look`'s effects always are — and its id. */
    private fun pulseLook(name: String = "Throb"): Int {
        val pulse = """{"effectType":"Pulse","category":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""
        return ok("create_look", """{"name":"$name","effects":[$pulse]}""")["lookId"]!!.jsonPrimitive.int
    }

    private fun included(lookId: Int): List<FxInstance> =
        state.show.fxEngine.getActiveEffects().filter { it.includedLookEffect?.lookId == lookId }

    private fun storedEffect(name: String): DaoLookEffect = transaction(state.database) {
        DaoLook.find { DaoLooks.name eq name }.single().effects.single()
    }

    private fun warnings(result: JsonObject) = result["warnings"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

    @Test
    fun `an effects-only Look's deferred effect runs on the targets it is included onto, and Update writes the edit back`() {
        patchRig()
        val lookId = pulseLook()

        val include = ok(
            "include_into_programmer",
            """{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"},{"type":"fixture","key":"foh-2"}]}""",
        )
        assertEquals(2, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertTrue(warnings(include).isEmpty(), "an effect was staged, so there is nothing to warn about: $include")
        val running = included(lookId)
        assertEquals(setOf("foh-1", "foh-2"), running.map { it.target.targetKey }.toSet())
        assertTrue(running.all { FxEngine.isProgrammerFxPriority(it.priority) && it.isLocalEffect }, "programmer-band effects Clear takes")
        assertTrue(running.none { state.show.fxEngine.effectDto(it).editedSinceInclude }, "nothing edited yet")

        // Nothing changed: Update writes no effect.
        val untouched = ok("update_from_programmer", "{}")
        assertEquals(0, untouched["effectsWritten"]!!.jsonPrimitive.int, "$untouched")

        val edited = running.first()
        applyEffectUpdate(state, edited.id, UpdateEffectRequest(beatDivision = 2.0, blendMode = "MULTIPLY"))
        assertTrue(state.show.fxEngine.effectDto(state.show.fxEngine.getEffect(edited.id)!!).editedSinceInclude)

        val update = ok("update_from_programmer", "{}")
        assertEquals(1, update["effectsWritten"]!!.jsonPrimitive.int, "$update")
        val stored = storedEffect("Throb")
        transaction(state.database) {
            assertEquals(2.0, stored.beatDivision)
            assertEquals("MULTIPLY", stored.blendMode)
            assertEquals(DEFERRED_TARGET_TYPE, stored.targetType, "still runs on whatever applies the Look")
        }

        // Written is the new baseline: a second Update writes nothing again.
        assertEquals(0, ok("update_from_programmer", "{}")["effectsWritten"]!!.jsonPrimitive.int)
        assertFalse(state.show.fxEngine.effectDto(state.show.fxEngine.getEffect(edited.id)!!).editedSinceInclude)

        ok("clear_programmer", "{}")
        assertTrue(included(lookId).isEmpty(), "Clear takes the included effects with the values")
    }

    @Test
    fun `Update writes only what was edited, and keeps a setting the included instance cannot show`() {
        patchRig()
        val lookId = pulseLook()
        // An element mode is a group effect's: included onto fixtures, the instance reports none.
        transaction(state.database) { storedEffect("Throb").elementMode = "FLAT" }
        state.show.lookRegistry.invalidateAll()

        ok("include_into_programmer", """{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}""")
        val instance = included(lookId).single()
        applyEffectUpdate(state, instance.id, UpdateEffectRequest(beatDivision = 4.0))
        assertTrue(state.show.fxEngine.fxStateFlow.value.effectStates.getValue(instance.id).editedSinceInclude)

        assertEquals(1, ok("update_from_programmer", "{}")["effectsWritten"]!!.jsonPrimitive.int)
        transaction(state.database) {
            val stored = storedEffect("Throb")
            assertEquals(4.0, stored.beatDivision, "the edit lands")
            assertEquals("FLAT", stored.elementMode, "a field the operator did not move keeps what the row says")
            assertEquals(DEFERRED_TARGET_TYPE, stored.targetType)
        }
        // And every client is told the edit is written: the reported state is re-emitted.
        assertFalse(
            state.show.fxEngine.fxStateFlow.value.effectStates.getValue(instance.id).editedSinceInclude,
            "the active-effect report no longer offers an Update with nothing to write",
        )
    }

    @Test
    fun `with no targets a deferred effect runs on the desk selection, and with none it says why it did not run`() {
        patchRig()
        val lookId = pulseLook()

        val nowhere = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(0, nowhere["fxSpawned"]!!.jsonPrimitive.int)
        assertTrue(warnings(nowhere).single().contains("select the fixtures"), "$nowhere")

        state.deskSelection.set(listOf(CueTargetDto("fixture", "foh-2")))
        val onSelection = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(1, onSelection["fxSpawned"]!!.jsonPrimitive.int, "$onSelection")
        assertEquals(listOf("foh-2"), included(lookId).map { it.target.targetKey })

        // Included again over itself: the first Include's instance is already running it.
        val again = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(0, again["fxSpawned"]!!.jsonPrimitive.int)
        assertEquals(1, included(lookId).size, "not doubled")
    }

    @Test
    fun `a Look already live on stage is not run a second time by Include, and says so`() {
        patchRig()
        val lookId = pulseLook()
        ok("apply_look", """{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}""")

        val include = ok(
            "include_into_programmer",
            """{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}""",
        )
        assertEquals(0, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertTrue(included(lookId).isEmpty())
        assertTrue(warnings(include).single().contains("already running where it is live"), "$include")
    }

    @Test
    fun `a bound effect runs on its own target, and includeFx false leaves the effects out`() {
        patchRig()
        val lookId = pulseLook()
        transaction(state.database) {
            DaoLook.find { DaoLooks.name eq "Throb" }.single().effects.single().apply {
                targetType = "fixture"
                targetKey = "foh-1"
            }
        }
        state.show.lookRegistry.invalidateAll()

        val withoutFx = ok("include_into_programmer", """{"lookId":$lookId,"includeFx":false}""")
        assertEquals(0, withoutFx["fxSpawned"]!!.jsonPrimitive.int)
        assertTrue(included(lookId).isEmpty())

        val include = ok("include_into_programmer", """{"lookId":$lookId}""")
        assertEquals(1, include["fxSpawned"]!!.jsonPrimitive.int, "$include")
        assertEquals(listOf("foh-1"), included(lookId).map { it.target.targetKey })
    }
}
