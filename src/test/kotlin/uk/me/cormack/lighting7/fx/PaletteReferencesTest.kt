package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.Gear4MusicSolParty12BFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fixture.dmx.SlenderBeamBarQuadFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.models.CueAttributeAssignmentDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A palette reference resolved per head (palette-references plan D2, D3) against the real fixture
 * classes, and the two readers that build on it: a cue's own rows and a Look's expansion.
 */
class PaletteReferencesTest {

    private val universe = Universe(0, 0)

    private fun rig(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            addFixture(ShehdsLed19RgbwFixture.Mode16Ch(universe, "mover-1", "Mover 1", firstChannel = 30))
            addFixture(MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", firstChannel = 60))
            addFixture(Gear4MusicSolParty12BFixture(universe, "par-1", "PAR 1", firstChannel = 70))
            addFixture(SlenderBeamBarQuadFixture.Mode12Ch(universe, "bar-1", "Bar 1", firstChannel = 90))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(hex1, hex2)) }
            createGroup<HexFixture>("all-wash") { addSpread(listOf(hex1, hex2)) }
        }
        return fixtures
    }

    private fun palette(name: String, vararg rows: PaletteRowEntry) =
        PaletteSnapshot(paletteId = 1, paletteUuid = UUID.randomUUID(), name = name, fadeDurationMs = null, rows = rows.toList())

    private fun generic(attribute: String, value: String) = PaletteRowEntry(null, attribute, value)
    private fun on(key: String, attribute: String, value: String) = PaletteRowEntry(TargetRef.Fixture(key), attribute, value)

    @Test
    fun `a generic palette resolves on a head as the resolver resolves its row`() {
        val fixtures = rig()
        val hex = fixtures.untypedGroupableFixture("hex-1")
        val warm = palette("Warm", generic("rgbColour", "#ff9d4a;policy=extract"))
        val resolved = PaletteReferences.resolveOn(fixtures, hex, "rgbColour", warm)
        val expected = PaletteResolver.resolve(hex, "rgbColour", parsePaletteIntent("#ff9d4a;policy=extract")!!, null).value
        assertEquals(expected, resolved.value)
        assertEquals("rgbColour", resolved.attribute)
        assertNull(resolved.unresolved)
    }

    @Test
    fun `a per-fixture palette resolves its own row on its head, and says so on a head it has none for`() {
        val fixtures = rig()
        val focus = palette("DSR Focus", on("hex-1", "dimmer", "pct:40"), on("hex-2", "dimmer", "pct:80"))
        val one = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-1"), "dimmer", focus)
        val two = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-2"), "dimmer", focus)
        assertTrue(one.value != two.value, "each head takes its own row")
        val other = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("mover-1"), "dimmer", focus)
        assertNull(other.value)
        assertEquals("'DSR Focus' has no row for mover-1", other.unresolved)
    }

    @Test
    fun `a head's own row beats the palette's generic row`() {
        val fixtures = rig()
        val mixed = palette("Mixed", generic("dimmer", "pct:10"), on("hex-1", "dimmer", "pct:90"))
        val own = assertIs<CueAssignmentResolver.AttributeValue.Slider>(
            PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-1"), "dimmer", mixed).value,
        )
        val shared = assertIs<CueAssignmentResolver.AttributeValue.Slider>(
            PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-2"), "dimmer", mixed).value,
        )
        assertTrue(own.value > shared.value)
    }

    @Test
    fun `a colour and its white resolve on an RGBW head and an RGB head`() {
        val fixtures = rig()
        val cool = palette("Cool", generic("rgbColour", "#4080ff"), generic("white", "dmx:120"))
        val hex = fixtures.untypedGroupableFixture("hex-1")
        val colour = assertIs<CueAssignmentResolver.AttributeValue.Colour>(PaletteReferences.resolveOn(fixtures, hex, "rgbColour", cool).value)
        assertEquals(0x40, colour.value.color.red)
        val white = PaletteReferences.resolveOn(fixtures, hex, "white", cool)
        assertEquals(CueAssignmentResolver.AttributeValue.Slider(120u), white.value)

        // A head with no white cannot serve the palette's colour family as a set, so takes none of it.
        val par = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("par-1"), "rgbColour", cool)
        assertNull(par.value)
        assertNotNull(par.unresolved)
    }

    @Test
    fun `a colour reference lands on a wheel head's wheel`() {
        val fixtures = rig()
        val red = palette("Red", generic("rgbColour", "#ff0000"))
        val mac = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("mac-1"), "rgbColour", red)
        assertIs<CueAssignmentResolver.AttributeValue.Setting>(mac.value)
        assertEquals("colourWheel", mac.attribute)
    }

    @Test
    fun `an unmet colour is refused whole`() {
        val fixtures = rig()
        val amber = palette("Amber", generic("rgbColour", "#ffaa00"), generic("amber", "dmx:200"))
        val mover = fixtures.untypedGroupableFixture("mover-1")
        assertNull(PaletteReferences.resolveOn(fixtures, mover, "rgbColour", amber).value, "no amber on the mover")
        assertNotNull(PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-1"), "rgbColour", amber).value)
    }

    @Test
    fun `a reference resolves on one head of a bar`() {
        val fixtures = rig()
        val bar = fixtures.untypedFixture("bar-1") as MultiElementFixture<*>
        val head = bar.elements.first()
        val red = palette("Red", generic("rgbColour", "#ff0000"))
        // The bar's heads carry a colour wheel each, so the reference lands on that head's wheel.
        val resolved = PaletteReferences.resolveOn(fixtures, head, "rgbColour", red)
        assertIs<CueAssignmentResolver.AttributeValue.Setting>(resolved.value)
        assertEquals("colourWheel", resolved.attribute)
    }

    @Test
    fun `an FX palette and a missing one never resolve`() {
        val fixtures = rig()
        val hex = fixtures.untypedGroupableFixture("hex-1")
        assertEquals("names no palette", PaletteReferences.resolveOn(fixtures, hex, "dimmer", null).unresolved)
        val fx = palette("Pulse").copy(effect = StoredFx("Wave", "intensity", "dimmer", 1.0, "OVERRIDE", "NORMAL", 0.0, null, null, null, emptyMap(), null, null))
        assertEquals("'Pulse' is an FX palette", PaletteReferences.resolveOn(fixtures, hex, "dimmer", fx).unresolved)
    }

    @Test
    fun `a reference that no longer resolves keeps its last value, marked`() {
        val fixtures = rig()
        var current = palette("DSR Focus", on("hex-1", "dimmer", "pct:40"))
        val references = PaletteReferences({ fixtures }, { current })
        val hex = fixtures.untypedGroupableFixture("hex-1")
        val before = references.resolve(hex, "dimmer", current.paletteUuid)
        assertNull(before.unresolved)

        current = current.copy(rows = emptyList())
        val after = references.resolve(hex, "dimmer", current.paletteUuid)
        assertEquals(before.value, after.value, "the head stays where it was")
        assertEquals("'DSR Focus' has no row for hex-1", after.unresolved)
    }

    @Test
    fun `a cue's group row referencing a palette resolves per member`() {
        val fixtures = rig()
        val focus = palette("DSR Focus", on("hex-1", "dimmer", "pct:40"), on("hex-2", "dimmer", "pct:80"))
        val references = PaletteReferences({ fixtures }, { focus.takeIf { s -> s.paletteUuid == it } })
        val rows = buildCueAssignmentsForCue(
            fixtures,
            CueApplyData(
                cueId = 7, cueName = "test", adHocFx = emptyList(), cueStackId = 3, sortOrder = 2,
                attributeAssignments = listOf(
                    CueAttributeAssignmentDto("group", "front-wash", "dimmer", PaletteReferences.valueOf(focus.paletteUuid)),
                ),
            ),
            references,
        )
        assertEquals(setOf("hex-1", "hex-2"), rows.map { it.targetKey }.toSet())
        assertTrue(rows.all { it.targetIsGroup && it.reference == focus.paletteUuid })
        assertEquals(2, rows.map { it.value }.toSet().size, "each member takes its own row")
    }

    @Test
    fun `a Look's reference rows expand to literals, a member's own row beating its group's`() {
        val fixtures = rig()
        val warm = palette("Warm", generic("dimmer", "pct:50"))
        val references = PaletteReferences({ fixtures }, { warm.takeIf { s -> s.paletteUuid == it } })
        val look = LookSnapshot(
            lookId = 1, lookUuid = UUID.randomUUID(), name = "Look",
            rows = listOf(
                LookRowEntry(TargetRef.Group("front-wash"), "dimmer", PaletteReferences.valueOf(warm.paletteUuid)),
                LookRowEntry(TargetRef.Fixture("hex-2"), "dimmer", "10"),
            ),
            effects = emptyList(),
        )
        val expanded = LookRegistry.expand(look, fixtures, references)
        val half = PaletteReferences.resolveOn(fixtures, fixtures.untypedGroupableFixture("hex-1"), "dimmer", warm).value!!.serialize()
        assertEquals(half, expanded.literalFor("hex-1", "dimmer"))
        assertEquals("10", expanded.literalFor("hex-2", "dimmer"))
        assertTrue(
            expanded.snapshot.rows.all { !PaletteReferences.isReference(it.value) || it.byHead != null },
            "the cook reads literals: a group reference carries each member's",
        )
        assertEquals(TargetRef.Group("front-wash"), expanded.snapshot.rows.first().target, "the group row stays a group row")
    }

    @Test
    fun `a group reference keeps its place among a Look's group rows`() {
        val fixtures = rig()
        val warm = palette("Warm", generic("dimmer", "pct:50"))
        val references = PaletteReferences({ fixtures }, { warm.takeIf { s -> s.paletteUuid == it } })
        val look = LookSnapshot(
            lookId = 1, lookUuid = UUID.randomUUID(), name = "Look",
            rows = listOf(
                LookRowEntry(TargetRef.Group("all-wash"), "dimmer", PaletteReferences.valueOf(warm.paletteUuid)),
                LookRowEntry(TargetRef.Group("front-wash"), "dimmer", "10"),
            ),
            effects = emptyList(),
        )
        assertEquals("10", LookRegistry.expand(look, fixtures, references).literalFor("hex-1", "dimmer"), "the later group row wins, as for literals")
    }
}
