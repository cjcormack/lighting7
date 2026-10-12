package uk.me.cormack.lighting7.mcp

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.Test
import uk.me.cormack.lighting7.ai.AiTools
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffect
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.LookRowDto
import uk.me.cormack.lighting7.testsupport.RouteIntegrationTest
import org.jetbrains.exposed.v1.core.eq
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `create_look`'s `values`: a Look of plain values, authored without an effect. Each value is an
 * intent resolved per head into the literal a Look row stores, and the Look then plays through the
 * same paths a desk-authored one does — pressed into the programmer, layered in a cue.
 */
class McpLookValuesTest : RouteIntegrationTest() {

    private val tools by lazy { AiTools(state) }

    private fun call(name: String, arguments: String): ToolExecutionResult =
        runBlocking { tools.executeTool(name, Json.parseToJsonElement(arguments).jsonObject) }

    private fun ToolExecutionResult.json(): JsonObject = Json.parseToJsonElement(result).jsonObject

    /** Two RGB pars and a wheel head in one group, a hazer, and a conventional on channel 70. */
    private fun patchRig() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"hex-1","name":"Hex 1","fixtureTypeKey":"hex","universe":0,"startChannel":1,"groups":["Wash","Mixed"]},
                        {"key":"hex-2","name":"Hex 2","fixtureTypeKey":"hex","universe":0,"startChannel":20,"groups":["Wash","Mixed"]},
                        {"key":"mac-1","name":"MAC 1","fixtureTypeKey":"martin-mac-250-mode-4","universe":0,"startChannel":40,"groups":["Mixed"]},
                        {"key":"haze","name":"Hazer","fixtureTypeKey":"hazer","universe":0,"startChannel":60,"groups":["Mixed"]},
                        {"key":"foh-1","name":"FOH 1","fixtureTypeKey":"generic-dimmer","universe":0,"startChannel":70}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    private fun storedRows(name: String): List<LookRowDto> = transaction(state.database) {
        DaoLook.find { DaoLooks.name eq name }.single().rows.sortedBy { it.sortOrder }.map {
            LookRowDto(it.targetType, it.targetKey, it.propertyName, it.value, elementKey = it.elementKey)
        }
    }

    private fun lookCount() = transaction(state.database) { DaoLook.all().count() }

    @Test
    fun `values are resolved per head into literals, a group kept whole while its members agree`() {
        patchRig()
        val created = call(
            "create_look",
            """{"name":"Pre-show","values":[
                {"target":{"type":"group","key":"Wash"},"propertyName":"dimmer","value":"pct:70"},
                {"target":{"type":"fixture","key":"haze"},"propertyName":"fanSpeed","value":"dmx:20"},
                {"target":{"type":"fixture","key":"haze"},"propertyName":"pumpControl","value":"pct:50"},
                {"target":{"type":"fixture","key":"mac-1"},"propertyName":"gobo","value":"cone"},
                {"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"dmx:200"}
            ]}""",
        )
        assertTrue(created.success, created.result)
        assertEquals(5, created.json()["valueCount"]!!.jsonPrimitive.int)
        assertEquals(0, created.json()["effectCount"]!!.jsonPrimitive.int)

        val rows = storedRows("Pre-show")
        assertEquals(
            listOf(
                LookRowDto("group", "Wash", "dimmer", "179"),
                LookRowDto("fixture", "haze", "fanSpeed", "20"),
                LookRowDto("fixture", "haze", "pumpControl", "128"),
                LookRowDto("fixture", "mac-1", "gobo", "10"),
                LookRowDto("fixture", "foh-1", "dimmer", "200"),
            ),
            rows,
        )
    }

    @Test
    fun `a colour value keeps the emitter levels it names, where the head has them`() {
        patchRig()
        val created = call(
            "create_look",
            """{"name":"Warm","values":[
                {"target":{"type":"group","key":"Wash"},"propertyName":"rgbColour","value":"#ffc080;policy=extract;w200;a30"},
                {"target":{"type":"fixture","key":"mac-1"},"propertyName":"rgbColour","value":"#ff0000;uv50"}
            ]}""",
        )
        assertTrue(created.success, created.result)
        assertEquals(
            listOf(
                // EXTRACT moves the neutral into white; the named white then replaces it.
                LookRowDto("group", "Wash", "rgbColour", "#7f4000;w200;a30"),
                LookRowDto("fixture", "mac-1", "colourWheel", "176"),
            ),
            storedRows("Warm"),
        )
    }

    @Test
    fun `a group whose members land differently becomes a row per head, and a head that cannot take it is reported`() {
        patchRig()
        val created = call(
            "create_look",
            """{"name":"Red","values":[
                {"target":{"type":"group","key":"Mixed"},"propertyName":"rgbColour","value":"#ff0000"},
                {"target":{"type":"group","key":"Mixed"},"propertyName":"dimmer","value":"pct:100"}
            ]}""",
        )
        assertTrue(created.success, created.result)
        val rows = storedRows("Red")

        val colour = rows.filter { it.propertyName != "dimmer" }
        assertEquals(listOf("hex-1", "hex-2", "mac-1"), colour.map { it.targetKey }, "per head, the hazer has no colour")
        assertTrue(colour.take(2).all { it.propertyName == "rgbColour" && it.value.startsWith("#ff0000") }, "$colour")
        assertEquals(LookRowDto("fixture", "mac-1", "colourWheel", "176"), colour[2], "the wheel head lands on RED")

        assertEquals(
            LookRowDto("group", "Mixed", "dimmer", "255"),
            rows.single { it.propertyName == "dimmer" },
            "every head that has a dimmer agrees, so the group stays one row",
        )
        val skipped = created.json()["skipped"]!!.jsonArray.map { it.jsonObject["fixture"]!!.jsonPrimitive.content to it.jsonObject["propertyName"]!!.jsonPrimitive.content }
        assertTrue("haze" to "rgbColour" in skipped && "haze" to "dimmer" in skipped, "$skipped")
    }

    @Test
    fun `a value no head can take, a malformed one, an unknown target or a taken name refuses the whole call`() {
        patchRig()
        assertTrue(call("create_look", """{"name":"Taken","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"pct:10"}]}""").success)
        val before = lookCount()

        for ((arguments, expected) in listOf(
            """{"name":"A","values":[{"target":{"type":"fixture","key":"haze"},"propertyName":"gobo","value":"cone"}]}""" to "no head of haze",
            """{"name":"A","values":[{"target":{"type":"fixture","key":"mac-1"},"propertyName":"gobo","value":"spiral"}]}""" to "no option 'spiral'",
            """{"name":"A","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"#ff0000"}]}""" to "is not a value dimmer takes",
            """{"name":"A","values":[{"target":{"type":"fixture","key":"hex-1"},"propertyName":"rgbColour","value":"pct:50"}]}""" to "is not a value rgbColour takes",
            """{"name":"A","values":[{"target":{"type":"fixture","key":"nope"},"propertyName":"dimmer","value":"pct:50"}]}""" to "no fixture 'nope'",
            """{"name":"A","values":[{"target":{"type":"group","key":"Nope"},"propertyName":"dimmer","value":"pct:50"}]}""" to "no group 'Nope'",
            """{"name":"A","values":[{"propertyName":"dimmer","value":"pct:50"}]}""" to "target must be",
            """{"name":"Taken","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"pct:10"}]}""" to "already exists",
            """{"name":"A"}""" to "Give 'values'",
        )) {
            val refused = call("create_look", arguments)
            assertFalse(refused.success, arguments)
            assertTrue(expected in refused.description, "$arguments → ${refused.description}")
            // The answer stays valid JSON whatever the message holds.
            assertNotNull(refused.json()["error"])
        }
        assertEquals(before, lookCount(), "nothing was written")
    }

    @Test
    fun `a Look of values presses onto its own fixtures and plays through a cue layer with no targets`() {
        patchRig()
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val created = call(
            "create_look",
            """{"name":"FOH","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"dmx:200"}],"apply":true}""",
        )
        assertTrue(created.success, created.result)
        assertEquals("applied", created.json()["action"]!!.jsonPrimitive.content)
        assertEquals(200, controller.getEffectiveValue(70).toInt(), "pressed onto its own fixture")
        val lookId = created.json()["lookId"]!!.jsonPrimitive.int

        val off = call("apply_look", """{"lookId":$lookId}""")
        assertTrue(off.success, off.result)
        assertEquals("removed", off.json()["action"]!!.jsonPrimitive.content)
        assertEquals(0, controller.getEffectiveValue(70).toInt())

        val cue = call("create_cue", """{"name":"Q1","layers":[{"lookId":$lookId}]}""")
        assertTrue(cue.success, cue.result)
        val applied = call("apply_cue", """{"cueId":${cue.json()["cueId"]!!.jsonPrimitive.int}}""")
        assertTrue(applied.success, applied.result)
        assertEquals(200, controller.getEffectiveValue(70).toInt(), "a layer with no targets lands every value")
    }

    @Test
    fun `an option named Off is that option, not the first slot`() {
        patchRig()
        val created = call(
            "create_look",
            """{"name":"Steady","values":[{"target":{"type":"fixture","key":"hex-1"},"propertyName":"dimmerMode","value":"off"}]}""",
        )
        assertTrue(created.success, created.result)
        assertEquals(listOf(LookRowDto("fixture", "hex-1", "dimmerMode", "52")), storedRows("Steady"))
    }

    @Test
    fun `a member's own value is kept beside its group's, and the same value twice is refused`() {
        patchRig()
        val created = call(
            "create_look",
            """{"name":"Split","values":[
                {"target":{"type":"group","key":"Mixed"},"propertyName":"rgbColour","value":"#0000ff"},
                {"target":{"type":"fixture","key":"hex-1"},"propertyName":"rgbColour","value":"#ff0000"}
            ]}""",
        )
        assertTrue(created.success, created.result)
        val colour = storedRows("Split").filter { it.targetKey == "hex-1" }
        assertEquals(1, colour.size, "the split group leaves hex-1 to its own value: $colour")
        assertTrue(colour.single().value.startsWith("#ff0000"), "$colour")

        val before = lookCount()
        val twice = call(
            "create_look",
            """{"name":"Twice","values":[
                {"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"pct:10"},
                {"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"pct:90"}
            ]}""",
        )
        assertFalse(twice.success)
        assertTrue("already set by values[0]" in twice.description, twice.description)
        assertEquals(before, lookCount())
    }

    @Test
    fun `effects with nowhere to run are refused before anything is written`() {
        patchRig()
        val pulse = """{"effectType":"Pulse","category":"dimmer","beatDivision":1.0,"blendMode":"OVERRIDE","parameters":{}}"""
        val before = lookCount()
        val refused = call("create_look", """{"name":"Throb","effects":[$pulse],"apply":true}""")
        assertFalse(refused.success)
        assertTrue("applyToTargets" in refused.description, refused.description)
        assertEquals(before, lookCount())

        val look = call("create_look", """{"name":"Throb","effects":[$pulse]}""")
        assertTrue(look.success, look.result)
        val lookId = look.json()["lookId"]!!.jsonPrimitive.int
        val cue = call("create_cue", """{"name":"Q","layers":[{"lookId":$lookId}]}""")
        assertFalse(cue.success)
        assertTrue("give targets" in cue.description, cue.description)
        assertTrue(call("create_cue", """{"name":"Q","layers":[{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}]}""").success)
    }

    @Test
    fun `a cue whose Look names an effect no longer registered still goes, without that effect`() {
        patchRig()
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val created = call(
            "create_look",
            """{"name":"Old","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"dmx:150"}]}""",
        )
        assertTrue(created.success, created.result)
        val lookId = created.json()["lookId"]!!.jsonPrimitive.int
        transaction(state.database) {
            DaoLookEffect.new {
                look = DaoLook[lookId]
                targetType = DEFERRED_TARGET_TYPE
                targetKey = ""
                effectType = "StaticValue"
                category = "dimmer"
                propertyName = "dimmer"
                beatDivision = 1.0
                blendMode = "OVERRIDE"
                distribution = "UNIFIED"
                parameters = mapOf("value" to "255")
            }
        }
        state.show.fixtures.lookListChanged()

        val cue = call("create_cue", """{"name":"Q","layers":[{"lookId":$lookId,"targets":[{"type":"fixture","key":"foh-1"}]}]}""")
        assertTrue(cue.success, cue.result)
        val applied = call("apply_cue", """{"cueId":${cue.json()["cueId"]!!.jsonPrimitive.int}}""")
        assertTrue(applied.success, applied.result)
        assertEquals(150, controller.getEffectiveValue(70).toInt(), "the value lands; the dead effect is skipped")
    }

    @Test
    fun `list_fixture_types names each type's settable properties when asked`() {
        val listed = runBlocking {
            SetupTools(state, tools).executeTool("list_fixture_types", Json.parseToJsonElement("""{"query":"mac-250","properties":true}""").jsonObject)
        }
        assertTrue(listed.success, listed.result)
        val properties = listed.json()["fixtureTypes"]!!.jsonArray.single().jsonObject["properties"]!!.jsonArray
            .associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
        val gobo = properties.getValue("gobo").jsonObject
        assertEquals("setting", gobo["kind"]!!.jsonPrimitive.content)
        assertTrue("CONE" in gobo["options"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("slider", properties.getValue("dimmer").jsonObject["kind"]!!.jsonPrimitive.content)

        val plain = runBlocking {
            SetupTools(state, tools).executeTool("list_fixture_types", Json.parseToJsonElement("""{"query":"mac-250"}""").jsonObject)
        }
        assertEquals(null, plain.json()["fixtureTypes"]!!.jsonArray.single().jsonObject["properties"], "off by default")
    }
}
