package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.GenericDimmerFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.ImgStageLineWash42LedFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fixture.dmx.SlenderBeamBarQuadFixture
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.dmx.WhexFixture
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [LocateValueResolver]. The resolver reads only the static DMX patch off
 * each fixture (channel numbers, slider ranges, setting tables), so bare fixtures without
 * a controller transaction suffice — same setup as [PropertyChannelWriterTest].
 */
class LocateValueResolverTest {

    private val universe = Universe(0, 0)

    // ─── Moving head: the full locate recipe ────────────────────────────────

    @Test
    fun `moving head locates to centre, open white beam, mid focus`() {
        val fixture = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)
        val assignments = LocateValueResolver.resolve(fixture)

        assertTrue(assignments.all { it.target === fixture }, "single fixture — no element targets")

        val byName = assignments.associate { it.propertyName to it.value }
        assertEquals(
            Layer3Resolver.PropertyValue.Position(128u, 128u), byName["position"],
            "pan/tilt via the synthetic position property, centred",
        )
        assertEquals(Layer3Resolver.PropertyValue.Slider(0u), byName["panFine"])
        assertEquals(Layer3Resolver.PropertyValue.Slider(0u), byName["tiltFine"])
        assertEquals(Layer3Resolver.PropertyValue.Slider(255u), byName["dimmer"])
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(246u), byName["strobe"],
            "BandedStrobeChannel fullOnValue — the 246-255 'LED on' band, not 255 blindly",
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Setting(0u), byName["colour"],
            "colour wheel OPEN_WHITE (#FFFFFF preview)",
        )
        assertEquals(Layer3Resolver.PropertyValue.Setting(0u), byName["gobo"], "gobo wheel OPEN_WHITE")
        assertEquals(Layer3Resolver.PropertyValue.Setting(0u), byName["prism"], "prism OPEN (facets == null)")
        assertEquals(Layer3Resolver.PropertyValue.Slider(128u), byName["focus"], "focus to mid-range")
        assertEquals(9, assignments.size, "speed, rotation and macro channels are left alone")

        assertNull(byName["pan"], "coarse axes are folded into the position write")
        assertNull(byName["tilt"], "coarse axes are folded into the position write")
    }

    // ─── PAR: white-light chain only ────────────────────────────────────────

    @Test
    fun `RGB par locates to full white with shutter open`() {
        val fixture = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }

        assertEquals(Layer3Resolver.PropertyValue.Slider(255u), byName["dimmer"])
        assertEquals(
            Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color.WHITE, white = 255u)),
            byName["rgbColour"],
            "full RGB and full white — the colour fan-out also zeroes amber/uv",
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(0u), byName["strobe"],
            "Hex shutter open is 0 (strobe band starts at 10)",
        )
        assertEquals(
            3, byName.size,
            "amber/white/uv ride along with the colour write; mode/speed settings are untouched",
        )
    }

    @Test
    fun `dimmer-only fixture locates to full`() {
        val fixture = GenericDimmerFixture(universe, key = "dim-1", fixtureName = "Dim 1", firstChannel = 1)
        val assignments = LocateValueResolver.resolve(fixture)
        assertEquals(1, assignments.size)
        assertEquals("dimmer", assignments.single().propertyName)
        assertEquals(Layer3Resolver.PropertyValue.Slider(255u), assignments.single().value)
    }

    // ─── Multi-element: parent masters plus per-head writes ─────────────────

    @Test
    fun `multi-element fixture locates parent masters and every head`() {
        val fixture = SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-1", "Bar 1", 1)
        val assignments = LocateValueResolver.resolve(fixture)

        val parent = assignments.filter { it.target === fixture }
        val parentByName = parent.associate { it.propertyName to it.value }
        assertEquals(Layer3Resolver.PropertyValue.Slider(255u), parentByName["dimmer"], "master dimmer")
        assertEquals(Layer3Resolver.PropertyValue.Slider(0u), parentByName["strobe"], "master shutter open at 0")
        assertEquals(2, parent.size)

        for (idx in 0 until 4) {
            val headKey = "bar-1.head-$idx"
            val head = assignments.filter { it.target.targetKey == headKey }
            val headByName = head.associate { it.propertyName to it.value }
            assertEquals(
                Layer3Resolver.PropertyValue.Position(128u, 128u), headByName["position"],
                "$headKey centred",
            )
            assertEquals(
                Layer3Resolver.PropertyValue.Setting(58u), headByName["colour"],
                "$headKey colour preset WHITE (#FFFFFF), not the level-0 BLACKOUT slot",
            )
            assertEquals(2, head.size)
        }
        assertEquals(10, assignments.size)
    }

    @Test
    fun `locating a single element also raises the parent masters`() {
        val fixture = SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-1", "Bar 1", 1)
        val assignments = LocateValueResolver.resolve(fixture.head(0))

        val parent = assignments.filter { it.target === fixture }
        val parentByName = parent.associate { it.propertyName to it.value }
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(255u), parentByName["dimmer"],
            "a centred head behind a closed master dimmer would stay dark",
        )
        assertEquals(Layer3Resolver.PropertyValue.Slider(0u), parentByName["strobe"])
        assertEquals(2, parent.size, "only the masters — not the other heads")

        val head = assignments.filter { it.target.targetKey == "bar-1.head-0" }
        assertEquals(2, head.size, "the head's own position + colour")
    }

    // ─── White/amber/UV engines without an RGB property ─────────────────────

    @Test
    fun `UV-only fixture locates its dimmer to full`() {
        val fixture = UVFixture(universe, key = "uv-1", fixtureName = "UV 1", firstChannel = 1)
        val assignments = LocateValueResolver.resolve(fixture)
        assertEquals(1, assignments.size, "the UV-category dimmer is the fixture's whole output")
        assertEquals("dimmer", assignments.single().propertyName)
        assertEquals(Layer3Resolver.PropertyValue.Slider(255u), assignments.single().value)
    }

    @Test
    fun `white-only blinder cells locate to full`() {
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
        val assignments = LocateValueResolver.resolve(fixture)

        for (cell in listOf("blinder-1.cell-1", "blinder-1.cell-2")) {
            val byName = assignments.filter { it.target.targetKey == cell }
                .associate { it.propertyName to it.value }
            assertEquals(
                Layer3Resolver.PropertyValue.Slider(255u), byName["warmWhite"],
                "$cell warm white — no RGB engine exists to fan out from",
            )
            assertEquals(Layer3Resolver.PropertyValue.Slider(255u), byName["coldWhite"])
        }
    }

    // ─── Strobe backings beyond BandedStrobeChannel ──────────────────────────

    @Test
    fun `plain DmxSlider strobe still opens the shutter`() {
        val fixture = WhexFixture(universe, key = "whex-1", fixtureName = "Whex 1", firstChannel = 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(0u), byName["strobe"],
            "Whex's DmxStrobe opens at 0 — a running strobe must not survive locate",
        )
    }

    // ─── Shared dimmer/strobe channel ────────────────────────────────────────

    @Test
    fun `shared dimmer-strobe channel gets exactly one write, the shutter-open value`() {
        // Mode13Ch models one channel (firstChannel + 5) as both dimmer (0-134) and
        // strobe (full-on band 240-255, which overrides the dimmer).
        val fixture = ImgStageLineWash42LedFixture.Mode13Ch(universe, "wash-1", "Wash 1", 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(
            Layer3Resolver.PropertyValue.Slider(240u), byName["strobe"],
            "the 240-255 band is max steady brightness on this fixture",
        )
        assertNull(byName["dimmer"], "the dimmer write would fight the full-on band on the same channel")
    }

    // ─── Colour macro wheel coexisting with an RGB engine ────────────────────

    @Test
    fun `colour macro wheel is disengaged so the RGB white wins`() {
        val fixture = LedLightbar12PixelFixture.Mode12Ch(universe, "bar12-1", "Bar12 1", 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(
            Layer3Resolver.PropertyValue.Setting(0u), byName["colorPreset"],
            "the OFF slot — a 'white' preset level would override the RGB engine",
        )
        assertEquals(
            Layer3Resolver.PropertyValue.Colour(ExtendedColour(Color.WHITE, white = 255u)),
            byName["rgbColour"],
        )
        assertNull(byName["white"], "the white slider rides along with the colour fan-out")
    }
}
