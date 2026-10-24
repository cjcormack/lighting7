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
import uk.me.cormack.lighting7.ai.RigBriefing
import uk.me.cormack.lighting7.ai.SetupTools
import uk.me.cormack.lighting7.ai.ToolExecutionResult
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookFxEntity
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
            LookRowDto(it.targetType, it.targetKey, it.attribute, it.value, elementKey = it.elementKey)
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
        assertEquals(0, created.json()["fxCount"]!!.jsonPrimitive.int)

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
    fun `a position takes both units — pos centre-relative, a stored deg through the head's own range`() {
        patchRig()
        // Venue-visit plan D3, P3: `pos:` is coarse DMX about 128 on any head; `deg:` still resolves
        // through the MAC 250's annotated 540° pan and 257° tilt.
        assertTrue(call("create_look", look("Centre", "pos:-20,40")).success)
        assertEquals(listOf(LookRowDto("fixture", "mac-1", "position", "108,168")), storedRows("Centre"))
        assertTrue(call("create_look", look("Degrees", "deg:270,0")).success)
        assertEquals(listOf(LookRowDto("fixture", "mac-1", "position", "128,0")), storedRows("Degrees"))
    }

    private fun look(name: String, position: String) =
        """{"name":"$name","values":[{"target":{"type":"fixture","key":"mac-1"},"propertyName":"position","value":"$position"}]}"""

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
    fun `apply_look lands the Look whole, and refuses targets by name`() {
        patchRig()
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val created = call(
            "create_look",
            """{"name":"FOH","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"dmx:200"}]}""",
        )
        assertTrue(created.success, created.result)
        val lookId = created.json()["lookId"]!!.jsonPrimitive.int

        val targeted = call("apply_look", """{"lookId":$lookId,"targets":[{"type":"fixture","key":"hex-1"}]}""")
        assertFalse(targeted.success)
        assertTrue("takes no targets" in targeted.result, targeted.result)

        val on = call("apply_look", """{"lookId":$lookId}""")
        assertTrue(on.success, on.result)
        assertEquals("applied", on.json()["action"]!!.jsonPrimitive.content)
        assertEquals(200, controller.getEffectiveValue(70).toInt(), "on its own fixture, not the one named")
        assertTrue(state.show.programmerStore.layers.single().targets.isEmpty())
    }

    @Test
    fun `apply_look with asValues writes the values with no layer, and is not a toggle`() {
        patchRig()
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val created = call(
            "create_look",
            """{"name":"FOH","values":[{"target":{"type":"fixture","key":"foh-1"},"propertyName":"dimmer","value":"dmx:200"}]}""",
        )
        assertTrue(created.success, created.result)
        val lookId = created.json()["lookId"]!!.jsonPrimitive.int

        repeat(2) {
            val applied = call("apply_look", """{"lookId":$lookId,"asValues":true}""")
            assertTrue(applied.success, applied.result)
            assertEquals(1, applied.json()["written"]!!.jsonPrimitive.int)
            assertEquals(200, controller.getEffectiveValue(70).toInt())
        }
        assertTrue(state.show.programmerStore.layers.isEmpty(), "no layer went on")
        assertEquals(uk.me.cormack.lighting7.fx.ProgrammerOwner.WEB, state.show.programmerStore.get("foh-1", "dimmer")?.owner)
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

    /** A 24-channel Shehds at 80: its colour is on its three zones only, zone n's red at 88 + 4(n − 1). */
    private fun patchShehds() {
        val patched = runBlocking {
            SetupTools(state, tools).executeTool(
                "patch_fixtures",
                Json.parseToJsonElement(
                    """{"fixtures":[
                        {"key":"shehds-1","name":"Shehds 1","fixtureTypeKey":"shehds-led19-rgbw-24ch","universe":0,"startChannel":80,"groups":["Spots"]},
                        {"key":"hex-9","name":"Hex 9","fixtureTypeKey":"hex","universe":0,"startChannel":110,"groups":["Spots"]}
                    ]}""",
                ).jsonObject,
            )
        }
        assertTrue(patched.success, patched.result)
    }

    @Test
    fun `a colour on a fixture whose colour is per zone lands on every zone, and plays`() {
        patchShehds()
        val controller = state.show.fixtures.controllerOrNull(Universe(0, 0)) as MockDmxController
        val created = call(
            "create_look",
            """{"name":"Shehds red","values":[
                {"target":{"type":"fixture","key":"shehds-1"},"propertyName":"rgbColour","value":"#ff0000;w40"},
                {"target":{"type":"fixture","key":"shehds-1"},"propertyName":"dimmer","value":"pct:100"}
            ],"apply":true}""",
        )
        assertTrue(created.success, created.result)
        assertEquals(
            listOf(
                LookRowDto("fixture", "shehds-1", "rgbColour", "#ff0000;w40", elementKey = "shehds-1.zone-1"),
                LookRowDto("fixture", "shehds-1", "rgbColour", "#ff0000;w40", elementKey = "shehds-1.zone-2"),
                LookRowDto("fixture", "shehds-1", "rgbColour", "#ff0000;w40", elementKey = "shehds-1.zone-3"),
                // The fixture has a dimmer of its own, so that stays one fixture row.
                LookRowDto("fixture", "shehds-1", "dimmer", "255"),
            ),
            storedRows("Shehds red"),
        )
        assertTrue(created.json()["skipped"]?.jsonArray.isNullOrEmpty(), created.result)
        for (red in listOf(88, 92, 96)) {
            assertEquals(255, controller.getEffectiveValue(red).toInt(), "zone red at $red")
            assertEquals(40, controller.getEffectiveValue(red + 3).toInt(), "zone white at ${red + 3}")
        }
    }

    @Test
    fun `a zone named by its own key beats the fixture's colour, and a group reaches the zones too`() {
        patchShehds()
        val created = call(
            "create_look",
            """{"name":"Two tone","values":[
                {"target":{"type":"group","key":"Spots"},"propertyName":"rgbColour","value":"#00ff00"},
                {"target":{"type":"fixture","key":"shehds-1"},"propertyName":"rgbColour","value":"#ff0000"},
                {"target":{"type":"fixture","key":"shehds-1.zone-2"},"propertyName":"rgbColour","value":"#0000ff"}
            ]}""",
        )
        assertTrue(created.success, created.result)
        val rows = storedRows("Two tone")
        assertEquals(
            listOf("hex-9" to null),
            rows.filter { it.value.startsWith("#00ff00") }.map { it.targetKey to it.elementKey },
            "the group's green reaches only the head nothing names more closely: $rows",
        )
        assertEquals(
            listOf("shehds-1.zone-1", "shehds-1.zone-3"),
            rows.filter { it.value.startsWith("#ff0000") }.map { it.elementKey },
            "the fixture's red leaves zone 2 to its own value",
        )
        assertEquals(
            listOf(LookRowDto("fixture", "shehds-1", "rgbColour", "#0000ff", elementKey = "shehds-1.zone-2")),
            rows.filter { it.value.startsWith("#0000ff") },
        )
    }

    @Test
    fun `describe_rig names a multi-head fixture's cells by key`() {
        patchShehds()
        val rig = RigBriefing(state).describeRig()
        assertTrue("cells=3 (`shehds-1.zone-1` … `shehds-1.zone-3`)" in rig, rig)
    }

    @Test
    fun `create_look and update_look refuse an effect with no target, and write one with a target`() {
        patchRig()
        val settings = """"fxType":"Pulse","family":"intensity","cycle":1.0,"blendMode":"OVERRIDE","parameters":{}"""
        fun storedTargets() = transaction(state.database) {
            DaoLook.find { DaoLooks.name eq "Throb" }.single().effects.map { it.targetType to it.targetKey }
        }
        val before = lookCount()
        val refused = call("create_look", """{"name":"Throb","fx":[{$settings}]}""")
        assertFalse(refused.success)
        assertTrue("fx[0] is missing 'targetType'" in refused.description, refused.description)
        assertEquals(before, lookCount(), "nothing is written")

        // A Look effect names its own target, so the Look needs no selection to apply it on.
        val look = call("create_look", """{"name":"Throb","fx":[{"targetType":"fixture","targetKey":"foh-1",$settings}],"apply":true}""")
        assertTrue(look.success, look.result)
        assertEquals("applied", look.json()["action"]!!.jsonPrimitive.content)
        assertEquals(listOf("fixture" to "foh-1"), storedTargets())
        val lookId = look.json()["lookId"]!!.jsonPrimitive.int

        val bare = call("update_look", """{"lookId":$lookId,"fx":[{$settings}]}""")
        assertFalse(bare.success)
        assertTrue("fx[0] is missing 'targetType'" in bare.description, bare.description)
        assertEquals(listOf("fixture" to "foh-1"), storedTargets(), "the refused edit wrote nothing")

        val targets = """[{"type":"fixture","key":"foh-1"},{"type":"fixture","key":"foh-2"}]"""
        val selection = call("update_look", """{"lookId":$lookId,"fx":[{"targets":$targets,$settings}]}""")
        assertTrue(selection.success, selection.result)
        assertEquals("selection", storedTargets().single().first)

        assertTrue(call("create_cue", """{"name":"Q","layers":[{"lookId":$lookId}]}""").success, "a layer naming no targets runs it")
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
            DaoLookFxEntity.new {
                look = DaoLook[lookId]
                targetType = "fixture"
                targetKey = "foh-1"
                fxType = "StaticValue"
                family = "intensity"
                attribute = "dimmer"
                cycle = 1.0
                blendMode = "OVERRIDE"
                order = "UNIFIED"
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

    @Test
    fun `list_fixture_types lists a blinder's cell colour and its whites`() {
        val listed = runBlocking {
            SetupTools(state, tools).executeTool("list_fixture_types", Json.parseToJsonElement("""{"query":"china-2-cell","properties":true}""").jsonObject)
        }
        assertTrue(listed.success, listed.result)
        val type = listed.json()["fixtureTypes"]!!.jsonArray.single().jsonObject
        assertTrue("colour" in type["capabilities"]!!.jsonArray.map { it.jsonPrimitive.content })
        val colour = type["cellProperties"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == "rgbColour" }
        assertEquals("colour", colour["category"]!!.jsonPrimitive.content)
        assertTrue("3000 K" in colour["whites"]!!.jsonPrimitive.content)
    }
}
