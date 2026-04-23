package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripFixture
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [PropertyChannelWriter]. The writer is stateless and reads only the static
 * DMX patch off each fixture, so no controller transaction is required — the tests
 * instantiate bare fixtures. A single round-trip test drives a `Layer3Resolver.Assignment`
 * through both the cue-apply pipeline and the writer, asserting both paths produce identical
 * DMX bytes on the same fixture.
 */
class PropertyChannelWriterTest {

    private val universe = Universe(0, 0)

    private fun hex(firstChannel: Int = 1): HexFixture =
        HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = firstChannel)

    // ─── Slider ─────────────────────────────────────────────────────────────

    @Test
    fun `slider resolves to single channel write at full range`() {
        val writes = PropertyChannelWriter.resolve(
            hex(),
            "dimmer",
            Layer3Resolver.PropertyValue.Slider(180u),
        )
        assertEquals(1, writes.size)
        val w = writes.single()
        assertEquals(1, w.channel)
        assertEquals(universe, w.universe)
        assertEquals(180u.toUByte(), w.value)
        assertEquals(PropertyCategory.DIMMER, w.category)
    }

    @Test
    fun `slider endpoints pass through unchanged`() {
        val zero = PropertyChannelWriter.resolve(hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(0u)).single()
        val full = PropertyChannelWriter.resolve(hex(), "dimmer", Layer3Resolver.PropertyValue.Slider(255u)).single()
        assertEquals(0u.toUByte(), zero.value, "no min/max scaling — 0 maps to 0")
        assertEquals(255u.toUByte(), full.value, "no min/max scaling — 255 maps to 255")
    }

    @Test
    fun `slider on fixture at offset produces channel at offset`() {
        val write = PropertyChannelWriter.resolve(
            hex(firstChannel = 100),
            "uv",
            Layer3Resolver.PropertyValue.Slider(200u),
        ).single()
        // uv = firstChannel + 6 = 106.
        assertEquals(106, write.channel)
        assertEquals(PropertyCategory.UV, write.category)
        assertEquals(200u.toUByte(), write.value)
    }

    // ─── Setting ────────────────────────────────────────────────────────────

    @Test
    fun `setting resolves to single channel write at raw level`() {
        // HexFixture.mode sits at firstChannel + 9.
        val write = PropertyChannelWriter.resolve(
            hex(),
            "mode",
            Layer3Resolver.PropertyValue.Setting(201u),
        ).single()
        assertEquals(10, write.channel)
        assertEquals(201u.toUByte(), write.value)
        assertEquals(PropertyCategory.SETTING, write.category)
    }

    // ─── Colour ─────────────────────────────────────────────────────────────

    @Test
    fun `colour on RGBWA+UV fixture emits all six channels`() {
        val ext = ExtendedColour(Color(200, 100, 50), white = 128u, amber = 64u, uv = 180u)
        val writes = PropertyChannelWriter.resolve(hex(), "rgbColour", Layer3Resolver.PropertyValue.Colour(ext))
        // HexFixture: R/G/B at firstChannel+1..+3 (2, 3, 4), amber at +4 (5), white at +5 (6), UV at +6 (7).
        val byChannel = writes.associate { it.channel to it.value }
        assertEquals(200u.toUByte(), byChannel[2])
        assertEquals(100u.toUByte(), byChannel[3])
        assertEquals(50u.toUByte(), byChannel[4])
        assertEquals(64u.toUByte(), byChannel[5], "amber via WithAmber")
        assertEquals(128u.toUByte(), byChannel[6], "white via WithWhite")
        assertEquals(180u.toUByte(), byChannel[7], "uv via WithUv")
        assertEquals(6, writes.size, "R/G/B + W + A + UV — full extended colour")
        assertEquals(PropertyCategory.WHITE, writes.single { it.channel == 6 }.category)
        assertEquals(PropertyCategory.AMBER, writes.single { it.channel == 5 }.category)
        assertEquals(PropertyCategory.UV, writes.single { it.channel == 7 }.category)
    }

    @Test
    fun `colour without extended values still writes W A UV channels as zero on trait-bearing fixtures`() {
        // HexFixture implements WithWhite/WithAmber/WithUv. An ExtendedColour with the
        // extended channels defaulted to 0 should still emit writes for each — otherwise
        // a previous sticky value on those channels would linger at Layer 4.
        val ext = ExtendedColour(Color(255, 0, 0))
        val writes = PropertyChannelWriter.resolve(hex(), "rgbColour", Layer3Resolver.PropertyValue.Colour(ext))
        assertEquals(6, writes.size)
        assertEquals(0u.toUByte(), writes.single { it.channel == 5 }.value, "amber zero-write")
        assertEquals(0u.toUByte(), writes.single { it.channel == 6 }.value, "white zero-write")
        assertEquals(0u.toUByte(), writes.single { it.channel == 7 }.value, "uv zero-write")
    }

    @Test
    fun `colour on fixture with WithWhite but no WithAmber drops amber silently`() {
        // LightstripFixture implements WithColour + WithWhite, but not WithAmber or WithUv.
        // Asymmetric-trait case: white lands, amber/uv drop without error.
        val lightstrip = LightstripFixture(universe, key = "strip-1", fixtureName = "Strip 1", firstChannel = 1)
        val ext = ExtendedColour(Color(200, 100, 50), white = 128u, amber = 64u, uv = 180u)
        val writes = PropertyChannelWriter.resolve(lightstrip, "rgbColour", Layer3Resolver.PropertyValue.Colour(ext))
        // LightstripFixture: RGB at 1/2/3, white at 4. No amber / no UV.
        val byChannel = writes.associate { it.channel to it.value }
        assertEquals(200u.toUByte(), byChannel[1])
        assertEquals(100u.toUByte(), byChannel[2])
        assertEquals(50u.toUByte(), byChannel[3])
        assertEquals(128u.toUByte(), byChannel[4], "white via WithWhite")
        assertEquals(4, writes.size, "amber and uv drop silently — traits not implemented")
    }

    // ─── Position ───────────────────────────────────────────────────────────

    @Test
    fun `position on moving head resolves to pan and tilt channels`() {
        val fx = Fusion100SpotMkIIFixture.Mode8Ch(
            universe = universe,
            key = "spot-1",
            fixtureName = "Spot 1",
            firstChannel = 1,
        )
        val writes = PropertyChannelWriter.resolve(
            fx,
            "position",
            Layer3Resolver.PropertyValue.Position(pan = 100u, tilt = 200u),
        )
        assertEquals(2, writes.size)
        val byChannel = writes.associate { it.channel to it.value }
        // Mode8Ch: pan = firstChannel (1), tilt = firstChannel + 1 (2).
        assertEquals(100u.toUByte(), byChannel[1])
        assertEquals(200u.toUByte(), byChannel[2])
        assertEquals(PropertyCategory.PAN, writes.single { it.channel == 1 }.category)
        assertEquals(PropertyCategory.TILT, writes.single { it.channel == 2 }.category)
    }

    @Test
    fun `position on non-moving fixture returns empty list`() {
        val writes = PropertyChannelWriter.resolve(
            hex(),
            "position",
            Layer3Resolver.PropertyValue.Position(pan = 100u, tilt = 200u),
        )
        assertTrue(writes.isEmpty(), "HexFixture does not implement WithPosition")
    }

    // ─── Error cases ────────────────────────────────────────────────────────

    @Test
    fun `unknown property name returns empty list`() {
        val writes = PropertyChannelWriter.resolve(
            hex(),
            "nonesuch",
            Layer3Resolver.PropertyValue.Slider(100u),
        )
        assertTrue(writes.isEmpty())
    }

    @Test
    fun `value kind mismatch with property type returns empty list`() {
        // "mode" is a Setting property; a Slider PropertyValue targeting it should drop.
        val writes = PropertyChannelWriter.resolve(
            hex(),
            "mode",
            Layer3Resolver.PropertyValue.Slider(100u),
        )
        assertTrue(writes.isEmpty())
    }

    // ─── channelsFor (clear-path enumeration) ───────────────────────────────

    @Test
    fun `channelsFor slider returns single channel`() {
        val channels = PropertyChannelWriter.channelsFor(hex(), "dimmer")
        assertEquals(1, channels.size)
        assertEquals(1, channels.single().channel)
    }

    @Test
    fun `channelsFor colour returns R G B plus W A UV`() {
        val channels = PropertyChannelWriter.channelsFor(hex(), "rgbColour")
        // HexFixture: R/G/B (2,3,4) + amber (5) + white (6) + UV (7).
        assertEquals(setOf(2, 3, 4, 5, 6, 7), channels.map { it.channel }.toSet())
    }

    @Test
    fun `channelsFor position returns pan and tilt`() {
        val fx = Fusion100SpotMkIIFixture.Mode8Ch(
            universe = universe,
            key = "spot-1",
            fixtureName = "Spot 1",
            firstChannel = 1,
        )
        val channels = PropertyChannelWriter.channelsFor(fx, "position")
        assertEquals(setOf(1, 2), channels.map { it.channel }.toSet())
    }

    // ─── Round-trip vs cue-apply pipeline ───────────────────────────────────

    @Test
    fun `colour write produces same DMX bytes as Layer 3 cue apply for the same value`() {
        // Rig with FxEngine → MockDmxController so we can observe bytes that the cue-apply
        // path writes when it lands the same PropertyValue.
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
        }
        val directWriteStore = DirectWriteStore()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = MasterClock(),
            directWriteStore = directWriteStore,
            layerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
        )

        // Cue-apply path: assignment → setCueAssignments → publishLayer3ToControllers → controller bytes.
        val ext = ExtendedColour(Color(200, 100, 50), white = 128u, amber = 64u, uv = 180u)
        val assignment = Layer3Resolver.Assignment(
            cueId = 1, priority = 1, fadeWeight = 1.0,
            targetKey = "hex-1", targetIsGroup = false,
            propertyName = "rgbColour",
            category = PropertyCategory.COLOUR,
            value = Layer3Resolver.PropertyValue.Colour(ext),
        )
        engine.setCueAssignments(1, listOf(assignment))
        val cueBytes = mapOf(
            2 to controller.currentValues[2],
            3 to controller.currentValues[3],
            4 to controller.currentValues[4],
            5 to controller.currentValues[5],
            6 to controller.currentValues[6],
            7 to controller.currentValues[7],
        )

        // Writer path: resolve PropertyValue directly.
        val writerBytes = PropertyChannelWriter.resolve(
            HexFixture(universe, "hex-1", "Hex 1", 1),
            "rgbColour",
            Layer3Resolver.PropertyValue.Colour(ext),
        ).associate { it.channel to it.value }

        assertEquals(cueBytes[2], writerBytes[2], "red channel matches cue-apply")
        assertEquals(cueBytes[3], writerBytes[3], "green channel matches cue-apply")
        assertEquals(cueBytes[4], writerBytes[4], "blue channel matches cue-apply")
        assertEquals(cueBytes[5], writerBytes[5], "amber channel matches cue-apply")
        assertEquals(cueBytes[6], writerBytes[6], "white channel matches cue-apply")
        assertEquals(cueBytes[7], writerBytes[7], "uv channel matches cue-apply")
    }

    @Test
    fun `slider write produces same DMX byte as Layer 3 cue apply for the same value`() {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
        }
        val directWriteStore = DirectWriteStore()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = MasterClock(),
            directWriteStore = directWriteStore,
            layerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
        )

        val assignment = Layer3Resolver.Assignment(
            cueId = 1, priority = 1, fadeWeight = 1.0,
            targetKey = "hex-1", targetIsGroup = false,
            propertyName = "dimmer",
            category = PropertyCategory.DIMMER,
            value = Layer3Resolver.PropertyValue.Slider(180u),
        )
        engine.setCueAssignments(1, listOf(assignment))

        val writerWrite = PropertyChannelWriter.resolve(
            HexFixture(universe, "hex-1", "Hex 1", 1),
            "dimmer",
            Layer3Resolver.PropertyValue.Slider(180u),
        ).single()

        assertEquals(controller.currentValues[1], writerWrite.value, "dimmer channel matches cue-apply")
    }
}
