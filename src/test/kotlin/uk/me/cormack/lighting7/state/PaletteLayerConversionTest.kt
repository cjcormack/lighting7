package uk.me.cormack.lighting7.state

import org.junit.Test
import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.state.PaletteLayerConversion.Cue
import uk.me.cormack.lighting7.state.PaletteLayerConversion.Layer
import uk.me.cormack.lighting7.state.PaletteLayerConversion.Look
import uk.me.cormack.lighting7.state.PaletteLayerConversion.LookRow
import uk.me.cormack.lighting7.state.PaletteLayerConversion.OwnRow
import uk.me.cormack.lighting7.state.PaletteLayerConversion.Palette
import uk.me.cormack.lighting7.state.PaletteLayerConversion.PaletteRow
import uk.me.cormack.lighting7.state.PaletteLayerConversion.decide
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** [PaletteLayerConversion.decide] over plain data: which palette layers become rows, and why the rest stay. */
class PaletteLayerConversionTest {

    private val rig = object : PaletteLayerConversion.Rig {
        override fun members(group: String): List<String>? = when (group) {
            "front" -> listOf("hex-1", "hex-2")
            "back" -> listOf("hex-2", "hex-3")
            else -> null
        }

        override fun familyOf(head: String, attribute: String): AttributeFamily? = when (attribute) {
            "gobo" -> AttributeFamily.BEAM
            "colourWheel" -> AttributeFamily.COLOUR
            else -> null
        }
    }

    private val red = Palette(UUID.randomUUID(), "Red", isFx = false, rows = listOf(PaletteRow(null, "rgbColour")))
    private val redWhite = Palette(UUID.randomUUID(), "Red white", isFx = false, rows = listOf(PaletteRow(null, "rgbColour"), PaletteRow(null, "white")))
    private val focus = Palette(
        UUID.randomUUID(), "DSR Focus", isFx = false,
        rows = listOf(PaletteRow("spot-1", "position"), PaletteRow("spot-2", "position")),
    )
    private val chase = Palette(UUID.randomUUID(), "Chase", isFx = true, rows = emptyList())

    private var nextId = 1

    private fun layer(palette: Palette? = null, look: Look? = null, sortOrder: Int = 0, vararg targets: TargetRef, change: Layer.() -> Layer = { this }) =
        Layer(nextId++, UUID.randomUUID(), sortOrder, look, palette, targets = targets.toList()).change()

    private fun cue(vararg layers: Layer, own: List<OwnRow> = emptyList()) = Cue("Q", layers.toList(), own, nextSortOrder = 7)

    private val front = TargetRef.Group("front")
    private fun fixture(key: String) = TargetRef.Fixture(key)

    @Test
    fun `a plain layer becomes one reference row per target and attribute, after the cue's rows`() {
        val plain = layer(redWhite, null, 0, front, fixture("hex-3"))
        val decision = decide(cue(plain), rig)
        assertEquals(listOf(plain.uuid), decision.converted)
        assertEquals(
            listOf(front to "rgbColour", front to "white", fixture("hex-3") to "rgbColour", fixture("hex-3") to "white"),
            decision.rows.map { it.target to it.attribute },
        )
        assertTrue(decision.rows.all { it.value == "pal:${redWhite.uuid}" })
        assertEquals(listOf(7, 8, 9, 10), decision.rows.map { it.sortOrder })
        assertEquals(decision.rows.map { it.uuid }, decide(cue(plain), rig).rows.map { it.uuid }, "a row's uuid is named, never random")
    }

    @Test
    fun `a layer with any setting a row cannot say stays, saying why`() {
        val cases = mapOf(
            "it is disabled" to layer(red, null, 0, front) { copy(enabled = false) },
            "its amount is 50 %" to layer(red, null, 0, front) { copy(amount = 0.5) },
            "it blends ADDITIVE" to layer(red, null, 0, front) { copy(blendMode = "ADDITIVE") },
            "it stomps the layers below" to layer(red, null, 0, front) { copy(stomp = true) },
            "it is timed" to layer(red, null, 0, front) { copy(randomWindow = true) },
            "it is masked to POSITION" to layer(red, null, 0, front) { copy(mask = "POSITION") },
            "its mask 'SPARKLE' does not parse" to layer(red, null, 0, front) { copy(mask = "SPARKLE") },
            "it lands nothing" to layer(red, null, 0),
        )
        for ((reason, l) in cases) {
            val decision = decide(cue(l), rig)
            assertEquals(emptyList(), decision.converted, reason)
            assertEquals(listOf(reason), decision.kept.map { it.reason })
        }
        assertEquals(1, decide(cue(layer(red, null, 0, front) { copy(mask = "COLOUR,BEAM") }), rig).converted.size, "a mask holding its family is no mask")
    }

    @Test
    fun `a Look above asserting the family on a head the layer reaches keeps it, any other family does not`() {
        val warm = Look("Warm", listOf(LookRow(fixture("hex-2"), null, "rgbColour")))
        val bright = Look("Bright", listOf(LookRow(fixture("hex-2"), null, "dimmer"), LookRow(fixture("hex-1"), null, "gobo")))
        val elsewhere = Look("Elsewhere", listOf(LookRow(fixture("hex-3"), null, "rgbColour")))
        val wheel = Look("Wheel", listOf(LookRow(fixture("hex-1"), null, "colourWheel")))
        val unknown = Look("Odd", listOf(LookRow(fixture("hex-1"), null, "mystery")))

        fun under(look: Look, mask: String? = null) = decide(
            cue(layer(red, null, 0, front), layer(null, look, 1) { copy(mask = mask) }),
            rig,
        )
        assertEquals(listOf("Look 'Warm' above it asserts colour on the heads it reaches"), under(warm).kept.map { it.reason })
        assertEquals(1, under(bright).converted.size, "intensity and beam rows leave a colour layer free")
        assertEquals(1, under(elsewhere).converted.size, "a head the layer does not reach")
        assertEquals(1, under(warm, mask = "INTENSITY").converted.size, "a Look masked off the family asserts none of it")
        assertEquals(0, under(wheel).converted.size, "a colour wheel is the family")
        assertEquals(0, under(unknown).converted.size, "an attribute whose family is unknown might be it")
        assertEquals(1, decide(cue(layer(red, null, 1, front), layer(null, warm, 0)), rig).converted.size, "a Look below is beaten by rows as by the layer")
        assertEquals(1, decide(cue(layer(red, null, 0, front), layer(null, warm, 1) { copy(enabled = false) }), rig).converted.size, "a disabled Look asserts nothing")
    }

    @Test
    fun `a palette layer above, kept or converted, keeps an overlapping one below`() {
        val lower = layer(red, null, 0, front)
        val upperPlain = layer(redWhite, null, 1, fixture("hex-2"))
        val decision = decide(cue(lower, upperPlain), rig)
        assertEquals(listOf(upperPlain.uuid), decision.converted)
        assertEquals(listOf("palette 'Red white' above it asserts colour on the heads it reaches"), decision.kept.map { it.reason })

        val upperKept = layer(redWhite, null, 1, TargetRef.Group("back")) { copy(amount = 0.5) }
        val second = decide(cue(layer(red, null, 0, front), upperKept), rig)
        assertEquals(emptyList(), second.converted)

        val apart = decide(cue(layer(red, null, 0, fixture("hex-1")), layer(redWhite, null, 1, fixture("hex-3"))), rig)
        assertEquals(2, apart.converted.size)
        assertEquals(listOf(fixture("hex-1"), fixture("hex-3"), fixture("hex-3")), apart.rows.map { it.target }, "the lower layer's rows first, so a later row is a higher layer")

        assertEquals(1, decide(cue(layer(red, null, 0, front), layer(chase, null, 1, front)), rig).converted.size, "an FX palette asserts no value")
    }

    @Test
    fun `the cue's own row on a target and attribute takes that row out, and one it would beat elsewhere keeps the layer`() {
        val l = layer(redWhite, null, 0, front)
        val same = decide(cue(l, own = listOf(OwnRow(front, "white"))), rig)
        assertEquals(listOf(front to "rgbColour"), same.rows.map { it.target to it.attribute })

        val member = decide(cue(layer(redWhite, null, 0, front), own = listOf(OwnRow(fixture("hex-1"), "white"))), rig)
        assertEquals(1, member.converted.size, "a member's own row still beats the group's")

        val group = decide(cue(layer(red, null, 0, fixture("hex-1")), own = listOf(OwnRow(front, "rgbColour"))), rig)
        assertEquals(listOf("the cue's own front rgbColour row would no longer beat it on hex-1"), group.kept.map { it.reason })

        val twoGroups = decide(cue(layer(red, null, 0, TargetRef.Group("back")), own = listOf(OwnRow(front, "rgbColour"))), rig)
        assertEquals(0, twoGroups.converted.size, "of two group rows the later wins")

        val wheel = decide(cue(layer(red, null, 0, fixture("hex-1")), own = listOf(OwnRow(fixture("hex-1"), "colourWheel"))), rig)
        assertEquals(0, wheel.converted.size, "a colour row may land on the head's wheel")

        val other = decide(cue(layer(red, null, 0, front), own = listOf(OwnRow(fixture("hex-1"), "dimmer"))), rig)
        assertEquals(1, other.converted.size)

        val everything = decide(cue(layer(red, null, 0, front), own = listOf(OwnRow(front, "colour"))), rig)
        assertEquals(1, everything.converted.size, "a layer the cue's rows beat everywhere goes")
        assertEquals(emptyList(), everything.rows)
    }

    @Test
    fun `a per-fixture palette layer naming no targets lands on its rows' targets, and one naming targets on those it covers`() {
        val bare = decide(cue(layer(focus, null, 0)), rig)
        assertEquals(listOf(fixture("spot-1") to "position", fixture("spot-2") to "position"), bare.rows.map { it.target to it.attribute })

        val narrowed = decide(cue(layer(focus, null, 0, fixture("spot-2"), fixture("hex-1"))), rig)
        assertEquals(listOf(fixture("spot-2") to "position"), narrowed.rows.map { it.target to it.attribute })
    }

    @Test
    fun `a group the patch does not know overlaps everything`() {
        val warm = Look("Warm", listOf(LookRow(fixture("hex-9"), null, "rgbColour")))
        val decision = decide(cue(layer(red, null, 0, TargetRef.Group("venue-wash")), layer(null, warm, 1)), rig)
        assertEquals(0, decision.converted.size)
        assertEquals(1, decide(cue(layer(red, null, 0, TargetRef.Group("venue-wash"))), rig).converted.size)
    }

    @Test
    fun `a cell and its fixture are one light`() {
        val warm = Look("Warm", listOf(LookRow(fixture("bar-1"), "bar-1.zone-2", "rgbColour")))
        assertEquals(0, decide(cue(layer(red, null, 0, fixture("bar-1")), layer(null, warm, 1)), rig).converted.size)
        assertEquals(1, decide(cue(layer(red, null, 0, fixture("bar-10")), layer(null, warm, 1)), rig).converted.size)
    }
}
