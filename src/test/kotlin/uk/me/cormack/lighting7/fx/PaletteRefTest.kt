package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fixture.PropertyCategory
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaletteRefTest {

    private val uuid = UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")

    @Test
    fun `ref value round-trips`() {
        assertEquals("ref:$uuid", paletteRefValue(uuid))
        assertEquals(uuid, parsePaletteRef(paletteRefValue(uuid)))
        assertTrue(isPaletteRefValue(paletteRefValue(uuid)))
    }

    @Test
    fun `parsing tolerates whitespace and prefix case`() {
        assertEquals(uuid, parsePaletteRef("  ref:$uuid  "))
        assertEquals(uuid, parsePaletteRef("REF:$uuid"))
        assertEquals(uuid, parsePaletteRef("Ref: $uuid"))
    }

    @Test
    fun `malformed refs answer null rather than throwing`() {
        // A corrupt stored row must degrade to "doesn't resolve" (a skip with health), never take
        // down a whole cue apply.
        assertNull(parsePaletteRef("ref:"))
        assertNull(parsePaletteRef("ref:not-a-uuid"))
        assertNull(parsePaletteRef("ref:12"))
        assertNull(parsePaletteRef("reference:$uuid"))
    }

    @Test
    fun `the positional palette grammar is not a named-palette ref`() {
        // `P1` / `P2` / `P*` index the ordered colour list (Effect.kt) and must keep working
        // untouched — the two systems coexist.
        listOf("P1", "P12", "P*", "#ff8800", "200", "120,64", "red").forEach {
            assertFalse(isPaletteRefValue(it), "'$it' must not read as a named-palette ref")
        }
        assertTrue(isPaletteRef("P1"), "the positional parser still recognises P1")
    }

    @Test
    fun `a raw ref reaching the literal colour parser would silently produce white`() {
        // This is why `resolveAssignmentValueForFixture` intercepts `ref:` *before* calling
        // parseAssignmentValue, and why that ordering is called out in its KDoc. The literal
        // parser answers white for unrecognised input rather than failing, so an unintercepted
        // ref would light the fixture white instead of reporting a dead reference.
        val parsed = Layer3Resolver.parseAssignmentValue(
            PropertyCategory.COLOUR, "colour", paletteRefValue(uuid),
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Colour(parseExtendedColour("#ffffff")), parsed,
            "if this ever starts failing instead, the ordering guarantee can relax — until then it cannot",
        )
    }

    @Test
    fun `serialize never emits a ref-shaped string`() {
        // The inverse direction: nothing the canonical serialiser produces can be mistaken for a
        // reference, so the two forms cannot collide in the value column.
        val values = listOf(
            Layer3Resolver.PropertyValue.Slider(200u),
            Layer3Resolver.PropertyValue.Setting(12u),
            Layer3Resolver.PropertyValue.Colour(parseExtendedColour("#ff8800;w5")),
            Layer3Resolver.PropertyValue.Position(120u, 64u),
        )
        values.forEach { assertFalse(isPaletteRefValue(it.serialize()), "${it.serialize()} reads as a ref") }
    }
}
