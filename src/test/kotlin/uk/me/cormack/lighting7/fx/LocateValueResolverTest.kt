package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Blade
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.TestStrobeShutterHead
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.GenericDimmerFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.ImgStageLineWash42LedFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fixture.dmx.SlenderBeamBarQuadFixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.dmx.WhexFixture
import uk.me.cormack.lighting7.fixture.locate.StoredLocate
import uk.me.cormack.lighting7.fixture.locate.UnitLocate
import uk.me.cormack.lighting7.fixture.media.FittedMedia
import uk.me.cormack.lighting7.fixture.media.FittedSlot
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
            CueAssignmentResolver.PropertyValue.Position(128u, 128u), byName["position"],
            "pan/tilt via the synthetic position property, centred",
        )
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), byName["panFine"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), byName["tiltFine"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), byName["dimmer"])
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(246u), byName["strobe"],
            "BandedStrobeChannel fullOnValue — the 246-255 'LED on' band, not 255 blindly",
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Setting(0u), byName["colourWheel"],
            "colour wheel OPEN_WHITE (#FFFFFF preview)",
        )
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(0u), byName["gobo"], "gobo wheel OPEN_WHITE")
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(0u), byName["prism"], "prism OPEN (facets == null)")
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(243u), byName["focus"],
            "focus to the middle distance of its declared 2–40 m (21 m), not mid-DMX (3.8 m)",
        )
        assertEquals(9, assignments.size, "speed, rotation and macro channels are left alone")

        assertNull(byName["pan"], "coarse axes are folded into the position write")
        assertNull(byName["tilt"], "coarse axes are folded into the position write")
    }

    // ─── PAR: white-light chain only ────────────────────────────────────────

    @Test
    fun `RGB par locates to full white with shutter open`() {
        val fixture = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }

        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), byName["dimmer"])
        assertEquals(
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color.WHITE, white = 255u)),
            byName["rgbColour"],
            "full RGB and full white — the colour fan-out also zeroes amber/uv",
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(0u), byName["strobe"],
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
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), assignments.single().value)
    }

    // ─── Multi-element: parent masters plus per-head writes ─────────────────

    @Test
    fun `multi-element fixture locates parent masters and every head`() {
        val fixture = SlenderBeamBarQuadFixture.Mode14Ch(universe, "bar-1", "Bar 1", 1)
        val assignments = LocateValueResolver.resolve(fixture)

        val parent = assignments.filter { it.target === fixture }
        val parentByName = parent.associate { it.propertyName to it.value }
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), parentByName["dimmer"], "master dimmer")
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), parentByName["strobe"], "master shutter open at 0")
        assertEquals(2, parent.size)

        for (idx in 0 until 4) {
            val headKey = "bar-1.head-$idx"
            val head = assignments.filter { it.target.targetKey == headKey }
            val headByName = head.associate { it.propertyName to it.value }
            assertEquals(
                CueAssignmentResolver.PropertyValue.Position(128u, 128u), headByName["position"],
                "$headKey centred",
            )
            assertEquals(
                CueAssignmentResolver.PropertyValue.Setting(58u), headByName["colourWheel"],
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
            CueAssignmentResolver.PropertyValue.Slider(255u), parentByName["dimmer"],
            "a centred head behind a closed master dimmer would stay dark",
        )
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), parentByName["strobe"])
        assertEquals(2, parent.size, "only the masters — not the other heads")

        val head = assignments.filter { it.target.targetKey == "bar-1.head-0" }
        assertEquals(2, head.size, "the head's own position + colour")
    }

    // ─── Stored locate (venue-visit plan D6, D17) ────────────────────────────

    @Test
    fun `a stored locate points the unit at its own pan and tilt and leaves every other property alone`() {
        val fixture = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)
        val centred = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        val stored = LocateValueResolver.resolve(
            fixture, locate = listOf(UnitLocate(head = null, pan = 40, tilt = 200, panFine = 17, tiltFine = 230)),
        ).associate { it.propertyName to it.value }

        assertEquals(CueAssignmentResolver.PropertyValue.Position(40u, 200u), stored["position"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(17u), stored["panFine"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(230u), stored["tiltFine"])
        // D17: pan and tilt only — the rest is the category default, exactly as without one.
        assertEquals(
            centred - setOf("position", "panFine", "tiltFine"),
            stored - setOf("position", "panFine", "tiltFine"),
        )
    }

    @Test
    fun `a stored locate with no fine bytes writes the fine channels 0, and a head's entry is not the fixture's`() {
        val fixture = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)
        val coarseOnly = LocateValueResolver.resolve(fixture, locate = listOf(UnitLocate(pan = 10, tilt = 20)))
            .associate { it.propertyName to it.value }
        assertEquals(CueAssignmentResolver.PropertyValue.Position(10u, 20u), coarseOnly["position"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), coarseOnly["panFine"])

        val headOnly = LocateValueResolver.resolve(fixture, locate = listOf(UnitLocate(head = 0, pan = 10, tilt = 20)))
            .associate { it.propertyName to it.value }
        assertEquals(CueAssignmentResolver.PropertyValue.Position(128u, 128u), headOnly["position"], "head 0's entry is not the fixture's own")
    }

    @Test
    fun `each head of a bar takes its own stored locate, whether the bar or one head is located`() {
        val fixture = SlenderBeamBarQuadFixture.Mode27Ch(universe, "bar-1", "Bar 1", 1)
        val locate = listOf(UnitLocate(head = 1, pan = 60, tilt = 70, panFine = 5, tiltFine = 6), UnitLocate(head = 3, pan = 200, tilt = 10))

        val whole = LocateValueResolver.resolve(fixture, locate = locate)
        fun positionOf(assignments: List<LocateValueResolver.LocateAssignment>, key: String) =
            assignments.single { it.target.targetKey == key && it.propertyName == "position" }.value
        assertEquals(CueAssignmentResolver.PropertyValue.Position(128u, 128u), positionOf(whole, "bar-1.head-0"))
        assertEquals(CueAssignmentResolver.PropertyValue.Position(60u, 70u), positionOf(whole, "bar-1.head-1"))
        assertEquals(CueAssignmentResolver.PropertyValue.Position(200u, 10u), positionOf(whole, "bar-1.head-3"))
        val head1Fine = whole.filter { it.target.targetKey == "bar-1.head-1" }.associate { it.propertyName to it.value }
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(5u), head1Fine["panFine"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(6u), head1Fine["tiltFine"])

        val oneHead = LocateValueResolver.resolve(fixture.head(1), locate = locate)
        assertEquals(CueAssignmentResolver.PropertyValue.Position(60u, 70u), positionOf(oneHead, "bar-1.head-1"))
    }

    @Test
    fun `the units a type can store a locate on are its own and its heads that pan and tilt`() {
        assertEquals(mapOf<Int?, StoredLocate.LocateUnit>(null to StoredLocate.LocateUnit(true, true)), StoredLocate.unitsOf("martin-mac-250-mode-4"))
        val bar = StoredLocate.unitsOf("slender-beam-bar-quad-27ch")
        assertEquals(setOf<Int?>(0, 1, 2, 3), bar.keys)
        assertTrue(bar.values.all { it.panFine && it.tiltFine })
        assertTrue(StoredLocate.unitsOf("slender-beam-bar-quad-12ch").values.none { it.panFine }, "basic heads have no fine channels")
        assertTrue(StoredLocate.unitsOf("hex").isEmpty())
        assertTrue(StoredLocate.unitsOf("no-such-type").isEmpty())
    }

    @Test
    fun `a stored locate is checked against its type and fitted to a new one`() {
        val ok = StoredLocate.parse(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"head":2,"pan":1,"tilt":2},{"pan":3,"tilt":4}]"""),
            typeKey = "slender-beam-bar-quad-12ch",
        )
        assertTrue(ok.isFailure, "the 12-channel bar's body does not pan: ${ok.exceptionOrNull()?.message}")

        val parsed = StoredLocate.parse(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"head":2,"pan":1,"tilt":2},{"head":0,"pan":3,"tilt":4}]"""),
            typeKey = "slender-beam-bar-quad-27ch",
        ).getOrThrow()
        assertEquals(listOf(0, 2), parsed.map { it.head }, "canonical: by head")

        val problems = StoredLocate.parse(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"pan":300,"tilt":2},{"pan":1},{"head":0,"pan":1,"tilt":1,"panFine":2}]"""),
            typeKey = "slender-beam-bar-quad-12ch",
        ).exceptionOrNull()!!.message!!
        assertTrue("locate[0].pan must be a DMX value" in problems, problems)
        assertTrue("locate[1].tilt is required" in problems, problems)
        assertTrue("locate: head 1 has no fine pan channel" in problems || "locate[1].tilt is required" in problems, problems)
        val typed = StoredLocate.parse(
            kotlinx.serialization.json.Json.parseToJsonElement("""[{"head":0,"pan":1,"tilt":1,"panFine":2},{"head":9,"pan":1,"tilt":1}]"""),
            typeKey = "slender-beam-bar-quad-12ch",
        ).exceptionOrNull()!!.message!!
        assertTrue("locate: head 1 has no fine pan channel" in typed, typed)
        assertTrue("locate: head 10 does not pan and tilt on this type" in typed, typed)

        val fitted = StoredLocate.fitTo(
            "slender-beam-bar-quad-12ch",
            listOf(UnitLocate(head = null, pan = 1, tilt = 1), UnitLocate(head = 0, pan = 9, tilt = 8, panFine = 7, tiltFine = 6)),
        )
        assertEquals(listOf(UnitLocate(head = 0, pan = 9, tilt = 8)), fitted)
    }

    // ─── White/amber/UV engines without an RGB property ─────────────────────

    @Test
    fun `UV-only fixture locates its dimmer to full`() {
        val fixture = UVFixture(universe, key = "uv-1", fixtureName = "UV 1", firstChannel = 1)
        val assignments = LocateValueResolver.resolve(fixture)
        assertEquals(1, assignments.size, "its one channel is its dimmer, the fixture's whole output")
        assertEquals("dimmer", assignments.single().propertyName)
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), assignments.single().value)
    }

    @Test
    fun `white-only blinder cells locate to full`() {
        val fixture = China2CellLedBlinderFixture(universe, "blinder-1", "Blinder 1", 1)
        val assignments = LocateValueResolver.resolve(fixture)

        for (cell in listOf("blinder-1.cell-1", "blinder-1.cell-2")) {
            val byName = assignments.filter { it.target.targetKey == cell }
                .associate { it.propertyName to it.value }
            assertEquals(
                CueAssignmentResolver.PropertyValue.Slider(255u), byName["warmWhite"],
                "$cell warm white — no RGB engine exists to fan out from",
            )
            assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), byName["coldWhite"])
        }
    }

    // ─── Strobe backings beyond BandedStrobeChannel ──────────────────────────

    @Test
    fun `plain DmxSlider strobe still opens the shutter`() {
        val fixture = WhexFixture(universe, key = "whex-1", fixtureName = "Whex 1", firstChannel = 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(
            CueAssignmentResolver.PropertyValue.Slider(0u), byName["strobe"],
            "Whex's DmxStrobe opens at 0 — a running strobe must not survive locate",
        )
    }

    @Test
    fun `a setting-backed strobe opens at its OPEN position`() {
        val fixture = TestStrobeShutterHead(universe, "shutter-1", 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(
            CueAssignmentResolver.PropertyValue.Setting(64u), byName["shutter"],
            "a closed shutter must not survive locate, whichever form its channel takes",
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
            CueAssignmentResolver.PropertyValue.Slider(240u), byName["strobe"],
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
            CueAssignmentResolver.PropertyValue.Setting(0u), byName["colorPreset"],
            "the OFF slot — a 'white' preset level would override the RGB engine",
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(Color.WHITE, white = 255u)),
            byName["rgbColour"],
        )
        assertNull(byName["white"], "the white slider rides along with the colour fan-out")
    }

    // ─── Fitted media ────────────────────────────────────────────────────────

    @Test
    fun `a scroller locates to the frame that is open on this unit`() {
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        assertEquals(
            CueAssignmentResolver.PropertyValue.Setting(Source4RevolutionFixture.GelFrame.OPEN_LEADER.level),
            LocateValueResolver.resolve(fixture).single { it.propertyName == "gelScroller" }.value,
            "stock: the open leader",
        )
        // A venue string with a gel in the leader: the trailer is the unit's open frame.
        val leaderGelled = FittedMedia(mapOf("gelScroller" to mapOf("OPEN_LEADER" to FittedSlot(gel = "R02"))))
        assertEquals(
            CueAssignmentResolver.PropertyValue.Setting(Source4RevolutionFixture.GelFrame.OPEN_TRAILER.level),
            LocateValueResolver.resolve(fixture, leaderGelled).single { it.propertyName == "gelScroller" }.value,
        )
        // And an empty frame fitted mid-string is open too.
        val bothGelled = FittedMedia(
            mapOf(
                "gelScroller" to mapOf(
                    "OPEN_LEADER" to FittedSlot(gel = "R02"),
                    "R54_SPECIAL_LAVENDER" to FittedSlot(),
                    "OPEN_TRAILER" to FittedSlot(gel = "R80"),
                ),
            ),
        )
        assertEquals(
            CueAssignmentResolver.PropertyValue.Setting(Source4RevolutionFixture.GelFrame.R54_SPECIAL_LAVENDER.level),
            LocateValueResolver.resolve(fixture, bothGelled).single { it.propertyName == "gelScroller" }.value,
        )
    }

    // ─── Framing shutters ────────────────────────────────────────────────────

    @Test
    fun `framing shutters are taken out and squared`() {
        val fixture = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        for (n in 1..4) {
            assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), byName["frame${n}Pos"], "frame $n out")
            assertEquals(
                CueAssignmentResolver.PropertyValue.Slider(128u), byName["frame${n}Rot"],
                "frame $n square: 0° on −45°…+45° is DMX 127.5, rounded up as ChamSys locates it",
            )
        }
    }

    @Test
    fun `an inverted shutter is out at DMX max, and a rotation is squared through its own range`() {
        val fixture = TestShutterHead(universe, "shutters-1", 1)
        val byName = LocateValueResolver.resolve(fixture).associate { it.propertyName to it.value }
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(255u), byName["inverted"], "inverted: depth 0 at DMX max")
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), byName["plain"])
        // −30°…+60°: 0° is a third of the way along — DMX 85 — and two thirds when inverted, DMX 170.
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(85u), byName["lopsided"])
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(170u), byName["invertedRotation"])
        // A range that never reaches 0° parks at its nearer end.
        assertEquals(CueAssignmentResolver.PropertyValue.Slider(0u), byName["offSquare"])
    }
}

/** Framing shutters no library type has: inverted, a lopsided range, a range that misses 0°. */
@FixtureType("test-shutter-head")
internal class TestShutterHead(
    universe: Universe,
    key: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 5, key, key) {

    override fun withTransaction(transaction: ControllerTransaction): TestShutterHead =
        TestShutterHead(universe, key, firstChannel, transaction)

    @FixtureProperty("Plain", category = PropertyCategory.SHUTTER, blade = Blade.TOP, depthMax = 0.5)
    val plain = DmxSlider(transaction, universe, firstChannel)

    @FixtureProperty("Inverted", category = PropertyCategory.SHUTTER, blade = Blade.BOTTOM, depthMax = 0.5,
        inverted = true)
    val inverted = DmxSlider(transaction, universe, firstChannel + 1)

    @FixtureProperty("Lopsided", category = PropertyCategory.SHUTTER_ROTATION, blade = Blade.TOP,
        degMin = -30.0, degMax = 60.0)
    val lopsided = DmxSlider(transaction, universe, firstChannel + 2)

    @FixtureProperty("Inverted rotation", category = PropertyCategory.SHUTTER_ROTATION, blade = Blade.BOTTOM,
        degMin = -30.0, degMax = 60.0, inverted = true)
    val invertedRotation = DmxSlider(transaction, universe, firstChannel + 3)

    @FixtureProperty("Off square", category = PropertyCategory.SHUTTER_ROTATION, blade = Blade.LEFT,
        degMin = 10.0, degMax = 40.0)
    val offSquare = DmxSlider(transaction, universe, firstChannel + 4)
}
