package uk.me.cormack.lighting7.midi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BindingTargetSerializationTest {

    @Test
    fun `FixtureProperty round trips with type discriminator`() {
        val target: BindingTarget = BindingTarget.FixtureProperty(fixtureKey = "hex-1", propertyName = "dimmer")
        val encoded = BindingTargetJson.encodeToString(target)
        val tree = BindingTargetJson.parseToJsonElement(encoded) as JsonObject
        assertEquals("fixtureProperty", tree["type"]?.jsonPrimitive?.content)
        val decoded = BindingTargetJson.decodeFromString<BindingTarget>(encoded)
        assertEquals(target, decoded)
    }

    @Test
    fun `GroupProperty round trips`() {
        val target: BindingTarget = BindingTarget.GroupProperty(groupName = "front-wash", propertyName = "rgbColour")
        val encoded = BindingTargetJson.encodeToString(target)
        assertEquals(target, BindingTargetJson.decodeFromString<BindingTarget>(encoded))
    }

    @Test
    fun `CueStackGo Back Pause encode distinct discriminators`() {
        val go: BindingTarget = BindingTarget.CueStackGo(stackId = 7)
        val back: BindingTarget = BindingTarget.CueStackBack(stackId = 7)
        val pause: BindingTarget = BindingTarget.CueStackPause(stackId = 7)
        assertEquals("cueStackGo", go.discriminator())
        assertEquals("cueStackBack", back.discriminator())
        assertEquals("cueStackPause", pause.discriminator())
        assertEquals(go, BindingTargetJson.decodeFromString<BindingTarget>(BindingTargetJson.encodeToString(go)))
        assertEquals(back, BindingTargetJson.decodeFromString<BindingTarget>(BindingTargetJson.encodeToString(back)))
        assertEquals(pause, BindingTargetJson.decodeFromString<BindingTarget>(BindingTargetJson.encodeToString(pause)))
    }

    @Test
    fun `FireCue round trips`() {
        val target: BindingTarget = BindingTarget.FireCue(cueId = 42)
        val encoded = BindingTargetJson.encodeToString(target)
        val decoded = BindingTargetJson.decodeFromString<BindingTarget>(encoded)
        assertEquals(target, decoded)
    }

    @Test
    fun `Flash wraps a FixtureProperty target`() {
        val flash: BindingTarget = BindingTarget.Flash(
            target = BindingTarget.FixtureProperty("hex-1", "dimmer"),
            max = 200,
        )
        val encoded = BindingTargetJson.encodeToString(flash)
        val decoded = BindingTargetJson.decodeFromString<BindingTarget>(encoded)
        val round = assertIs<BindingTarget.Flash>(decoded)
        assertIs<BindingTarget.FixtureProperty>(round.target)
        assertEquals(200, round.max)
    }

    @Test
    fun `Flash rejects a CueStackGo payload`() {
        assertFailsWith<IllegalArgumentException> {
            BindingTarget.Flash(target = BindingTarget.CueStackGo(stackId = 1))
        }
    }

    @Test
    fun `Flash rejects an out-of-range max`() {
        assertFailsWith<IllegalArgumentException> {
            BindingTarget.Flash(
                target = BindingTarget.FixtureProperty("hex-1", "dimmer"),
                max = 300,
            )
        }
    }

    @Test
    fun `Blackout and GrandMasterToggle serialize as objects`() {
        val blackout: BindingTarget = BindingTarget.Blackout
        val grandMaster: BindingTarget = BindingTarget.GrandMasterToggle
        val encodedBlackout = BindingTargetJson.encodeToString(blackout)
        val encodedGm = BindingTargetJson.encodeToString(grandMaster)
        assertTrue(encodedBlackout.contains("\"type\":\"blackout\""))
        assertTrue(encodedGm.contains("\"type\":\"grandMasterToggle\""))
        assertEquals(blackout, BindingTargetJson.decodeFromString<BindingTarget>(encodedBlackout))
        assertEquals(grandMaster, BindingTargetJson.decodeFromString<BindingTarget>(encodedGm))
    }

    @Test
    fun `SetBank round trips`() {
        val target: BindingTarget = BindingTarget.SetBank(deviceTypeKey = "x-touch-compact-standard", bank = "layer-b")
        val encoded = BindingTargetJson.encodeToString(target)
        assertEquals(target, BindingTargetJson.decodeFromString<BindingTarget>(encoded))
    }

    @Test
    fun `speed-master targets round trip, keyed and unkeyed`() {
        val keyed: BindingTarget = BindingTarget.SpeedMasterBpm(
            masterUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2", minBpm = 90.0, maxBpm = 150.0,
        )
        val encoded = BindingTargetJson.encodeToString(keyed)
        assertTrue(encoded.contains("\"type\":\"speedMasterBpm\""))
        assertEquals(keyed, BindingTargetJson.decodeFromString<BindingTarget>(encoded))

        val tap: BindingTarget = BindingTarget.SpeedMasterTap("7d444840-9dc0-11d1-b245-5ffdce74fad2")
        val encodedTap = BindingTargetJson.encodeToString(tap)
        assertTrue(encodedTap.contains("\"type\":\"speedMasterTap\""))
        assertEquals(tap, BindingTargetJson.decodeFromString<BindingTarget>(encodedTap))
    }

    /**
     * An omitted uuid means master 1, matching the `speedMasters.*` WS family — and an
     * omitted range means the default window, so a payload written before the range existed
     * still decodes.
     */
    @Test
    fun `speed-master BPM defaults to master 1 and the default range`() {
        val decoded = BindingTargetJson.decodeFromString<BindingTarget>("""{"type":"speedMasterBpm"}""")
        val bpm = assertIs<BindingTarget.SpeedMasterBpm>(decoded)
        assertNull(bpm.masterUuid)
        assertEquals(BindingTarget.SpeedMasterBpm.DEFAULT_MIN_BPM, bpm.minBpm)
        assertEquals(BindingTarget.SpeedMasterBpm.DEFAULT_MAX_BPM, bpm.maxBpm)

        val tap = assertIs<BindingTarget.SpeedMasterTap>(
            BindingTargetJson.decodeFromString<BindingTarget>("""{"type":"speedMasterTap"}""")
        )
        assertNull(tap.masterUuid)
    }

    @Test
    fun `speed-master BPM rejects an inverted or out-of-clock-range window`() {
        assertFailsWith<IllegalArgumentException> {
            BindingTarget.SpeedMasterBpm(minBpm = 150.0, maxBpm = 90.0)
        }
        // The clock itself only accepts 20..300; a window outside that could never be reached.
        assertFailsWith<IllegalArgumentException> {
            BindingTarget.SpeedMasterBpm(minBpm = 10.0, maxBpm = 400.0)
        }
    }

    @Test
    fun `FireCue carries its uuid beside the int and omits it when null`() {
        val bare: BindingTarget = BindingTarget.FireCue(cueId = 42)
        assertEquals("""{"type":"fireCue","cueId":42}""", BindingTargetJson.encodeToString(bare))
        val withUuid: BindingTarget = BindingTarget.FireCue(cueId = 42, cueUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2")
        val encoded = BindingTargetJson.encodeToString(withUuid)
        assertTrue(encoded.contains(""""cueUuid":"7d444840-9dc0-11d1-b245-5ffdce74fad2""""))
        assertEquals(withUuid, BindingTargetJson.decodeFromString<BindingTarget>(encoded))
        val go: BindingTarget = BindingTarget.CueStackGo(stackId = 3, stackUuid = "7d444840-9dc0-11d1-b245-5ffdce74fad2")
        assertEquals(go, BindingTargetJson.decodeFromString<BindingTarget>(BindingTargetJson.encodeToString(go)))
    }

    @Test
    fun `selection variants round trip with their discriminators`() {
        val cases = mapOf(
            "selectionProperty" to BindingTarget.SelectionProperty("dimmer"),
            "selectTarget" to BindingTarget.SelectTarget(
                uk.me.cormack.lighting7.models.CueTargetDto("group", "front-wash"),
                BindingTarget.SelectMode.REPLACE,
            ),
            "clearSelection" to BindingTarget.ClearSelection,
            "locateSelection" to BindingTarget.LocateSelection,
        )
        for ((type, target) in cases) {
            val encoded = BindingTargetJson.encodeToString<BindingTarget>(target)
            val tree = BindingTargetJson.parseToJsonElement(encoded) as JsonObject
            assertEquals(type, tree["type"]?.jsonPrimitive?.content)
            assertEquals(type, target.discriminator())
            assertEquals(target, BindingTargetJson.decodeFromString<BindingTarget>(encoded))
        }
        // SelectTarget's default mode is omitted on the wire and restored on read.
        val toggle = BindingTargetJson.encodeToString<BindingTarget>(
            BindingTarget.SelectTarget(uk.me.cormack.lighting7.models.CueTargetDto("fixture", "hex-1")),
        )
        assertTrue(!toggle.contains("mode"), toggle)
        assertEquals(
            BindingTarget.SelectMode.TOGGLE,
            (BindingTargetJson.decodeFromString<BindingTarget>(toggle) as BindingTarget.SelectTarget).mode,
        )
    }

    @Test
    fun `Unknown names the type it was written with and re-encodes its bytes verbatim`() {
        val unknown = BindingTarget.Unknown(targetType = "fromTheFuture", rawPayload = """{"type":"fromTheFuture","x":1}""")
        assertEquals("fromTheFuture", unknown.discriminator())
        assertEquals("""{"type":"fromTheFuture","x":1}""", unknown.encodePayload())
        // The codec itself still refuses the discriminator: tolerance lives in the row decode.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            BindingTargetJson.decodeFromString<BindingTarget>("""{"type":"fromTheFuture","x":1}""")
        }
    }
}
