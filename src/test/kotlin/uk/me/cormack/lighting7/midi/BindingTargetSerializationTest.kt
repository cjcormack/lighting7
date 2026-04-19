package uk.me.cormack.lighting7.midi

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
}
