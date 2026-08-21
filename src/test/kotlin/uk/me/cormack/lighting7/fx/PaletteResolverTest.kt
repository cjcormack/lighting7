package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Tests for [resolveAssignmentValueForFixture] — the single door both value forms go through.
 */
class PaletteResolverTest {

    private val uuid: UUID = UUID.fromString("2f1c9a54-8d3b-4f7e-9a11-6c0de5b47a02")

    private fun fixtures(): Fixtures {
        val f = Fixtures()
        f.register {
            addFixture(HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1))
        }
        return f
    }

    private fun registry(
        rows: List<LookRowEntry> = listOf(
            LookRowEntry(TargetRef.Fixture("hex-1"), "colour", "#ff8800"),
        ),
    ) = LookRegistry(
        fixtures = ::fixtures,
        loader = {
            LookSnapshot(
                lookId = 1, lookUuid = uuid, name = "Warm Amber",
                editorFixtureType = null, palette = emptyList(), rows = rows, effects = emptyList(),
            )
        },
    )

    @Test
    fun `a literal resolves exactly as the plain parser would`() {
        val raw = "#ff8800"
        val res = resolveAssignmentValueForFixture(
            registry(), "hex-1", "rgbColour", PropertyCategory.COLOUR, raw,
        )
        assertEquals(
            CueAssignmentResolver.parseAssignmentValue(PropertyCategory.COLOUR, "rgbColour", raw), res.value,
            "the literal path must delegate, not reimplement",
        )
        assertNull(res.paletteUuid, "a literal carries no palette identity")
        assertEquals(AssignmentHealth.Ok, res.health)
    }

    @Test
    fun `a positional palette ref still resolves through the literal path`() {
        // The two palette systems coexist: `P1` is not a named ref and must keep indexing the
        // ordered colour list.
        val palette = listOf(parseExtendedColour("#123456"))
        val res = resolveAssignmentValueForFixture(
            registry(), "hex-1", "rgbColour", PropertyCategory.COLOUR, "P1", palette,
        )
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(res.value)
        assertEquals(parseExtendedColour("#123456"), v.value)
        assertNull(res.paletteUuid)
    }

    @Test
    fun `a ref resolves to the palette's literal for that fixture`() {
        val res = resolveAssignmentValueForFixture(
            registry(), "hex-1", "rgbColour", PropertyCategory.COLOUR, paletteRefValue(uuid),
        )
        val v = assertIs<CueAssignmentResolver.PropertyValue.Colour>(res.value)
        assertEquals(parseExtendedColour("#ff8800"), v.value)
        assertEquals(uuid, res.paletteUuid)
        assertEquals(AssignmentHealth.Ok, res.health)
    }

    @Test
    fun `a ref with no registry reports MissingPalette and keeps its identity`() {
        val res = resolveAssignmentValueForFixture(
            null, "hex-1", "rgbColour", PropertyCategory.COLOUR, paletteRefValue(uuid),
        )
        assertNull(res.value)
        assertEquals(uuid, res.paletteUuid, "identity survives an unresolvable ref")
        assertEquals(AssignmentHealth.MissingPalette(uuid.toString()), res.health)
    }

    @Test
    fun `a ref to an uncovered fixture reports MissingPaletteEntry, never a guess`() {
        // Deliberately not falling back to another fixture's entry: a position palette's value for
        // one head is meaningless on another, which is why entries are per-fixture at all.
        val res = resolveAssignmentValueForFixture(
            registry(), "hex-9", "rgbColour", PropertyCategory.COLOUR, paletteRefValue(uuid),
        )
        assertNull(res.value)
        assertEquals(
            AssignmentHealth.MissingPaletteEntry(uuid.toString(), "hex-9", "rgbColour"), res.health,
        )
    }

    @Test
    fun `a wrong-type ref degrades to MissingPaletteEntry with no special case`() {
        // A COLOUR palette referenced from a position row simply has no entry keyed
        // (fixture, position), so the generic uncovered path already handles it.
        val res = resolveAssignmentValueForFixture(
            registry(), "hex-1", "position", PropertyCategory.PAN, paletteRefValue(uuid),
        )
        assertNull(res.value)
        assertEquals(
            AssignmentHealth.MissingPaletteEntry(uuid.toString(), "hex-1", "position"), res.health,
        )
    }

    @Test
    fun `an unparsable stored literal reports the entry as uncovered`() {
        val reg = registry(
            rows = listOf(LookRowEntry(TargetRef.Fixture("hex-1"), "dimmer", "not-a-level")),
        )
        val res = resolveAssignmentValueForFixture(
            reg, "hex-1", "dimmer", PropertyCategory.DIMMER, paletteRefValue(uuid),
        )
        assertNull(res.value)
        assertEquals(
            AssignmentHealth.MissingPaletteEntry(uuid.toString(), "hex-1", "dimmer"), res.health,
        )
    }

    @Test
    fun `a ref never reaches the colour parser, so it cannot silently become white`() {
        // The regression guard for the ordering hazard documented on the resolver: the literal
        // colour parser answers white for junk (pinned in PaletteRefTest), so a ref that slipped
        // past the interception would light the fixture instead of reporting a dead reference.
        val res = resolveAssignmentValueForFixture(
            null, "hex-1", "rgbColour", PropertyCategory.COLOUR, paletteRefValue(uuid),
        )
        assertNull(res.value, "an unresolvable ref must not produce a colour at all")
    }
}
