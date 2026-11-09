package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.ShehdsLed19RgbwFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.ExtendedColour
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.LayerResolver
import uk.me.cormack.lighting7.fx.ProgrammerOwner
import uk.me.cormack.lighting7.fx.ProgrammerStore
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [Heads], the heads a surface write reaches, and what [AttributeChannelResolver.surfaceWrite] writes on
 * each from the head's readback (`LayerResolver.localOver`) — together what
 * `DefaultSurfaceActions.writeLevel` sends. A function of the patch, the selection, the programmer
 * and the cue layer, so built without a show.
 */
class HeadsTest {

    private val universe = Universe(0, 0)

    private fun fixtures(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val h1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val h2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13, maxDimmerLevel = 200u))
            addFixture(HexFixture(universe, "hex-3", "Hex 3", firstChannel = 25))
            createGroup<HexFixture>("front-wash") { addSpread(listOf(h1, h2)) }
            // Its dimmer and pan/tilt on the unit, its colour on three zones only.
            val wash = addFixture(ShehdsLed19RgbwFixture.Mode24Ch(universe, "wash", "Wash", firstChannel = 101))
            createGroup<GroupableFixture>("wash-zones") { addSpread(listOf(wash.elements[0], wash.elements[2])) }
            // Two warm/cold cells: CH205–206 and CH207–208.
            addFixture(China2CellLedBlinderFixture(universe, "blinder", "Blinder", firstChannel = 201))
        }
        return fixtures
    }

    private fun selection(vararg targets: Pair<String, String>): () -> List<CueTargetDto> =
        { targets.map { (type, key) -> CueTargetDto(type, key) } }

    private fun List<Heads.Head>.keys(): List<Pair<String, String?>> = map { it.fixture.targetKey to it.sourceGroup }

    /** The programmer and the cue layer under it, as the show composes them. */
    private class Layers {
        val store = ProgrammerStore()
        val resolver = LayerResolver(CueAssignmentResolver(), store)

        fun hold(key: String, attribute: String, value: CueAssignmentResolver.AttributeValue) =
            store.put(ProgrammerOwner.WEB, key, attribute, value)

        fun cue(key: String, attribute: String, category: AttributeCategory, value: CueAssignmentResolver.AttributeValue) =
            resolver.applyAssignments(
                listOf(
                    CueAssignmentResolver.Assignment(
                        cueId = 1, priority = 1, fadeWeight = 1.0, targetKey = key, targetIsGroup = false,
                        attribute = attribute, category = category, value = value,
                    ),
                ),
            )
    }

    private fun colour(colour: Color, white: UByte = 0u, amber: UByte = 0u, uv: UByte = 0u) =
        CueAssignmentResolver.AttributeValue.Colour(ExtendedColour(colour, white, amber, uv))

    /** Each head's key and what a move on [attribute] writes there, as the surface's level write makes it. */
    private fun writes(
        fixtures: Fixtures,
        subject: Subject,
        attribute: String,
        value: UByte,
        axis: ColourAxis? = null,
        layers: Layers = Layers(),
    ): List<Pair<String, AttributeChannelResolver.SurfaceWrite>> =
        Heads.of(fixtures, subject, attribute).mapNotNull { head ->
            val current = AttributeChannelResolver.composedTarget(head.fixture, attribute)
                ?.let { layers.resolver.localOver(it, head.fixture, head.fixture.targetKey) }
            AttributeChannelResolver.surfaceWrite(head.fixture, attribute, value, current, axis)?.let { head.fixture.targetKey to it }
        }

    private fun colourOf(write: AttributeChannelResolver.SurfaceWrite): Color =
        assertIs<CueAssignmentResolver.AttributeValue.Colour>(write.value).value.color

    // --- Heads.of ---

    @Test
    fun `a fixture is itself, and a group its members with the group as their source`() {
        val fixtures = fixtures()
        assertEquals(listOf("hex-1" to null), Heads.of(fixtures, Subject.Fixture("hex-1"), "dimmer").keys())
        assertEquals(
            listOf("hex-1" to "front-wash", "hex-2" to "front-wash"),
            Heads.of(fixtures, Subject.Group("front-wash"), "dimmer").keys(),
        )
    }

    @Test
    fun `the selection is each of its targets, no head twice and the first spelling winning`() {
        val heads = Heads.of(
            fixtures(), Subject.Selection, "dimmer",
            selection("fixture" to "hex-1", "group" to "front-wash", "fixture" to "hex-3"),
        )
        assertEquals(listOf("hex-1" to null, "hex-2" to "front-wash", "hex-3" to null), heads.keys())
        assertTrue(Heads.of(fixtures(), Subject.Selection, "dimmer", selection()).isEmpty(), "never widened to everything")
    }

    @Test
    fun `a target that does not resolve, or a subject that names no heads, reaches nothing`() {
        val fixtures = fixtures()
        assertTrue(Heads.of(fixtures, Subject.Selection, "dimmer", selection("fixture" to "hex-9", "group" to "gone")).isEmpty())
        assertTrue(Heads.of(fixtures, Subject.Fixture("hex-9"), "dimmer").isEmpty())
        assertTrue(Heads.of(fixtures, Subject.None, "dimmer").isEmpty())
        assertTrue(Heads.of(fixtures, Subject.Record(RecordKind.LOOK, UUID.randomUUID()), "dimmer").isEmpty())
    }

    @Test
    fun `a selected cell is its own head`() {
        val fixtures = fixtures()
        assertEquals(
            listOf("wash.zone-2" to null),
            Heads.of(fixtures, Subject.Selection, "rgbColour", selection("fixture" to "wash.zone-2")).keys(),
        )
        assertEquals(listOf("wash.zone-2" to null), Heads.of(fixtures, Subject.Fixture("wash.zone-2"), "rgbColour").keys())
    }

    @Test
    fun `a parent whose attribute lives only on its cells stands for every cell that carries it`() {
        val fixtures = fixtures()
        assertEquals(
            listOf("wash.zone-1", "wash.zone-2", "wash.zone-3"),
            Heads.of(fixtures, Subject.Fixture("wash"), "rgbColour").map { it.fixture.targetKey },
        )
        assertEquals(listOf("wash"), Heads.of(fixtures, Subject.Fixture("wash"), "dimmer").map { it.fixture.targetKey })
        assertEquals(
            listOf("blinder.cell-1", "blinder.cell-2"),
            Heads.of(fixtures, Subject.Fixture("blinder"), "rgbColour").map { it.fixture.targetKey },
        )
    }

    @Test
    fun `a cell named beside its parent is dropped, the parent covering it`() {
        val fixtures = fixtures()
        val both = selection("fixture" to "wash.zone-1", "fixture" to "wash")
        assertEquals(listOf("wash"), Heads.of(fixtures, Subject.Selection, "dimmer", both).map { it.fixture.targetKey })
        assertEquals(
            listOf("wash.zone-1", "wash.zone-2", "wash.zone-3"),
            Heads.of(fixtures, Subject.Selection, "rgbColour", both).map { it.fixture.targetKey },
            "zone 1 once, as one of the parent's cells",
        )
    }

    @Test
    fun `a group holding cells reaches each cell, with the group as its source`() {
        assertEquals(
            listOf("wash.zone-1" to "wash-zones", "wash.zone-3" to "wash-zones"),
            Heads.of(fixtures(), Subject.Group("wash-zones"), "rgbColour").keys(),
        )
    }

    // --- What each head takes ---

    @Test
    fun `a level scales through each head's own range`() {
        val written = writes(fixtures(), Subject.Group("front-wash"), "dimmer", 127u)
        assertEquals(listOf("hex-1", "hex-2"), written.map { it.first })
        assertTrue(written.all { it.second.attribute == "dimmer" })
        assertEquals(255u.toUByte(), assertIs<CueAssignmentResolver.AttributeValue.Slider>(written[0].second.value).value)
        assertEquals(200u.toUByte(), assertIs<CueAssignmentResolver.AttributeValue.Slider>(written[1].second.value).value)
    }

    @Test
    fun `a head without the attribute takes nothing`() {
        val fixtures = fixtures()
        assertEquals(listOf("hex-1"), Heads.of(fixtures, Subject.Fixture("hex-1"), "tilt").map { it.fixture.targetKey })
        assertTrue(writes(fixtures, Subject.Fixture("hex-1"), "tilt", 64u).isEmpty(), "no Hex has a tilt")
        assertIs<CueAssignmentResolver.AttributeValue.Colour>(
            writes(fixtures, Subject.Fixture("hex-1"), "rgbColour", 64u).single().second.value,
        )
    }

    @Test
    fun `a colour write is a hue on each head's own current colour`() {
        val layers = Layers()
        // A dim blue held in Local, and a bright half-saturated cyan from the cue under it.
        layers.hold("hex-1", "rgbColour", colour(Color(0, 0, 200)))
        layers.cue("hex-2", "rgbColour", AttributeCategory.COLOUR, colour(Color(128, 255, 255)))
        val written = writes(fixtures(), Subject.Group("front-wash"), "rgbColour", 0u, layers = layers)
        assertEquals(listOf("hex-1", "hex-2"), written.map { it.first })
        assertEquals(Color(200, 0, 0), colourOf(written[0].second), "dim and saturated stays so, at the new hue")
        assertEquals(Color(255, 128, 128), colourOf(written[1].second), "bright and pastel stays so, at the new hue")
    }

    @Test
    fun `a colour axis lands on each head's own current colour, and a slider under an axis takes nothing`() {
        val fixtures = fixtures()
        val layers = Layers()
        // hex-1 a dim blue, hex-2 a bright pastel cyan — saturation 0 leaves each a white at its own level.
        layers.hold("hex-1", "rgbColour", colour(Color(0, 0, 200)))
        layers.hold("hex-2", "rgbColour", colour(Color(128, 255, 255)))
        val written = writes(fixtures, Subject.Group("front-wash"), "rgbColour", 0u, ColourAxis.SATURATION, layers)
        assertEquals(listOf("hex-1", "hex-2"), written.map { it.first })
        assertEquals(Color(200, 200, 200), colourOf(written[0].second))
        assertEquals(Color(255, 255, 255), colourOf(written[1].second))

        assertTrue(
            writes(fixtures, Subject.Group("front-wash"), "dimmer", 64u, ColourAxis.SATURATION).isEmpty(),
            "a dimmer has no saturation: every head is skipped",
        )
    }

    @Test
    fun `a hue turn on an element keeps the white bundled with its colour`() {
        val layers = Layers()
        // Zone 1 red, and its white at 90 held on its own key.
        layers.hold("wash.zone-1", "rgbColour", colour(Color(255, 0, 0)))
        layers.hold("wash.zone-1", "white", CueAssignmentResolver.AttributeValue.Slider(90u))
        val written = writes(fixtures(), Subject.Group("wash-zones"), "rgbColour", 40u, ColourAxis.HUE, layers)
        assertEquals("wash.zone-1", written.first().first)
        val colour = assertIs<CueAssignmentResolver.AttributeValue.Colour>(written.first().second.value).value
        assertEquals(90u.toUByte(), colour.white)
    }

    @Test
    fun `a white-mix cell takes a colour move, from the colour its own two whites make`() {
        val layers = Layers()
        // Cell 1 at full warm white; cell 2 holds nothing.
        layers.hold("blinder.cell-1", "rgbColour", colour(China2CellLedBlinderFixture.WHITE_MIX.colourOf(255u, 0u)))
        val written = writes(fixtures(), Subject.Fixture("blinder"), "rgbColour", 64u, ColourAxis.BRIGHTNESS, layers)
        assertEquals(listOf("blinder.cell-1", "blinder.cell-2"), written.map { it.first })
        assertTrue(written.all { it.second.attribute == "rgbColour" })
        assertEquals(
            AttributeChannelResolver.colourAtBrightness(64u, China2CellLedBlinderFixture.WHITE_MIX.colourOf(255u, 0u)),
            colourOf(written[0].second),
            "cell 1's warm white, at half its value",
        )
        val dark = colourOf(written[1].second)
        assertTrue(dark.red == dark.green && dark.green == dark.blue && dark.red > 0, "a dark cell comes up grey: $dark")
    }

    @Test
    fun `a pan or tilt move writes the head's position, keeping the other axis`() {
        val fixtures = fixtures()
        val layers = Layers()
        layers.cue("wash", "position", AttributeCategory.PAN, CueAssignmentResolver.AttributeValue.Position(200u, 77u))
        fun write(attribute: String, value: UByte, axis: ColourAxis? = null) =
            writes(fixtures, Subject.Fixture("wash"), attribute, value, axis, layers).singleOrNull()?.second
        assertEquals(
            AttributeChannelResolver.SurfaceWrite("position", CueAssignmentResolver.AttributeValue.Position(255u, 77u)),
            write("pan", 127u),
            "the tilt the cue holds is kept",
        )
        assertEquals(
            AttributeChannelResolver.SurfaceWrite("position", CueAssignmentResolver.AttributeValue.Position(200u, 0u)),
            write("tilt", 0u),
        )
        assertEquals(
            AttributeChannelResolver.SurfaceWrite("panFine", CueAssignmentResolver.AttributeValue.Slider(255u)),
            write("panFine", 127u),
            "a fine channel is its own attribute",
        )
        assertNull(write("pan", 64u, ColourAxis.HUE), "a pan has no colour axis")
    }

    @Test
    fun `the other axis is Local's position over the cue's, so Blind keeps a staged tilt`() {
        val layers = Layers()
        layers.cue("wash", "position", AttributeCategory.PAN, CueAssignmentResolver.AttributeValue.Position(50u, 77u))
        layers.store.blind = true
        layers.hold("wash", "position", CueAssignmentResolver.AttributeValue.Position(10u, 200u))
        assertEquals(
            listOf("wash" to AttributeChannelResolver.SurfaceWrite("position", CueAssignmentResolver.AttributeValue.Position(255u, 200u))),
            writes(fixtures(), Subject.Fixture("wash"), "pan", 127u, layers = layers),
        )
    }

    @Test
    fun `a colour turn in Blind keeps the staged white, amber and UV, not the stage's`() {
        val fixtures = fixtures()
        val layers = Layers()
        // The cue on stage: a red with a little white. The programmer, blind, stages a blue with its own W/A/UV.
        layers.cue("hex-1", "rgbColour", AttributeCategory.COLOUR, colour(Color(255, 0, 0), white = 50u))
        layers.store.blind = true
        layers.hold("hex-1", "rgbColour", colour(Color(0, 0, 200), white = 200u, amber = 10u, uv = 30u))
        val hex = fixtures.untypedFixture("hex-1")
        val target = assertNotNull(AttributeChannelResolver.composedTarget(hex, "rgbColour"))
        val onStage = assertIs<FxOutput.Colour>(layers.resolver.fallbackFor(target, hex, "hex-1")).color
        assertEquals(50u.toUByte(), onStage.white, "output, in Blind, is the cue's")

        for (axis in ColourAxis.entries) {
            val written = assertIs<CueAssignmentResolver.AttributeValue.Colour>(
                writes(fixtures, Subject.Fixture("hex-1"), "rgbColour", 40u, axis, layers).single().second.value,
            ).value
            assertEquals(200u.toUByte(), written.white, "$axis keeps the staged white")
            assertEquals(10u.toUByte(), written.amber, "$axis keeps the staged amber")
            assertEquals(30u.toUByte(), written.uv, "$axis keeps the staged UV")
        }
    }

    @Test
    fun `a colour turn keeps a cue's own white, amber and UV rows, which output keeps off the colour key`() {
        val fixtures = fixtures()
        val layers = Layers()
        layers.resolver.applyAssignments(
            listOf(
                "white" to (AttributeCategory.WHITE to 120u.toUByte()),
                "uv" to (AttributeCategory.UV to 30u.toUByte()),
            ).map { (attribute, cv) ->
                CueAssignmentResolver.Assignment(
                    cueId = 1, priority = 1, fadeWeight = 1.0, targetKey = "hex-1", targetIsGroup = false,
                    attribute = attribute, category = cv.first, value = CueAssignmentResolver.AttributeValue.Slider(cv.second),
                )
            },
        )
        for (axis in ColourAxis.entries) {
            val written = assertIs<CueAssignmentResolver.AttributeValue.Colour>(
                writes(fixtures, Subject.Fixture("hex-1"), "rgbColour", 40u, axis, layers).single().second.value,
            ).value
            assertEquals(120u.toUByte(), written.white, "$axis keeps the cue's white")
            assertEquals(30u.toUByte(), written.uv, "$axis keeps the cue's UV")
            assertEquals(0u.toUByte(), written.amber, "an emitter the cue does not set stays dark")
        }
    }
}
