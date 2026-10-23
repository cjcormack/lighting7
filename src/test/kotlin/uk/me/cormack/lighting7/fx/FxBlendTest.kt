package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.VarytecEasymoveXl60SpotFixture
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.fixture.trait.WithDimmer
import uk.me.cormack.lighting7.fixture.trait.WithPosition
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/** [FxBlend] per target kind, against fixed expected values and against what each target writes through it. */
class FxBlendTest {

    private val modes = BlendMode.entries
    private val weights = listOf(1.0, 0.5, 0.25)

    /** Before 200, output 100: one row per weight, one column per [BlendMode], in declaration order. */
    private val slider = listOf(
        listOf(100, 255, 78, 200, 100),
        listOf(150, 228, 139, 200, 150),
        listOf(175, 214, 170, 200, 175),
    )

    /** As [slider], but ADDITIVE moves about the centre, 128. */
    private val axis = listOf(
        listOf(100, 172, 78, 200, 100),
        listOf(150, 186, 139, 200, 150),
        listOf(175, 193, 170, 200, 175),
    )

    /** Before (200, 40, 10), output (100, 250, 30), each channel weighed on its own. */
    private val rgb = listOf(
        listOf(Color(100, 250, 30), Color(255, 255, 40), Color(78, 39, 1), Color(200, 250, 30), Color(100, 40, 10)),
        listOf(Color(150, 145, 20), Color(228, 148, 25), Color(139, 40, 6), Color(200, 145, 20), Color(150, 40, 10)),
    )

    @Test
    fun `a slider blends then weighs`() {
        for ((w, weight) in weights.withIndex()) {
            for ((m, mode) in modes.withIndex()) {
                assertEquals(slider[w][m], FxBlend.slider(200u, 100u, mode, weight).toInt(), "$mode at $weight")
            }
        }
    }

    @Test
    fun `a position axis is Around the centre, then weighed`() {
        for ((w, weight) in weights.withIndex()) {
            for ((m, mode) in modes.withIndex()) {
                assertEquals(axis[w][m], FxBlend.positionAxis(200u, 100u, mode, weight).toInt(), "$mode at $weight")
            }
        }
    }

    @Test
    fun `RGB blends then weighs each channel`() {
        for ((w, weight) in weights.take(2).withIndex()) {
            for ((m, mode) in modes.withIndex()) {
                assertEquals(rgb[w][m], FxBlend.rgb(Color(200, 40, 10), Color(100, 250, 30), mode, weight), "$mode at $weight")
            }
        }
    }

    @Test
    fun `a setting takes the FX's level from half weight up, whatever the blend`() {
        assertEquals(200, FxBlend.setting(200u, 100u, 0.49).toInt())
        assertEquals(100, FxBlend.setting(200u, 100u, 0.5).toInt())
        assertEquals(100, FxBlend.setting(200u, 100u, 1.0).toInt())
    }

    @Test
    fun `weight is Amount times envelope times the stack's FX amount, within 0 and 1`() {
        assertEquals(0.25, FxBlend.weight(0.5, 1.0, 0.5))
        assertEquals(1.0, FxBlend.weight(1.0, 1.0, 1.0))
        assertEquals(0.0, FxBlend.weight(-1.0, 1.0, 1.0))
    }

    @Test
    fun `each target writes what FxBlend answers`() {
        val universe = Universe(0, 0)
        val fixtures = Fixtures()
        fixtures.register {
            addController(MockDmxController(universe))
            addFixture(HexFixture(universe, "hex", "Hex", 1))
            addFixture(VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "vary", "Vary", 21))
        }
        for ((w, weight) in weights.withIndex()) {
            for ((m, mode) in modes.withIndex()) {
                val tx = ControllerTransaction(fixtures.controllers)
                val hex = fixtures.withTransaction(tx).untypedGroupableFixture("hex")
                val vary = fixtures.withTransaction(tx).untypedGroupableFixture("vary")

                (hex as WithDimmer).dimmer.value = 200u
                SliderTarget("hex", "dimmer").applyValueToFixture(hex, FxOutput.Slider(100u), mode, weight)
                assertEquals(slider[w][m], hex.dimmer.value!!.toInt(), "slider $mode at $weight")

                (vary as WithPosition).pan.value = 200u
                vary.tilt.value = 200u
                PositionTarget("vary").applyValueToFixture(vary, FxOutput.Position(100u, 100u), mode, weight)
                assertEquals(axis[w][m] to axis[w][m], vary.pan.value!!.toInt() to vary.tilt.value!!.toInt(), "position $mode at $weight")

                if (w < rgb.size) {
                    (hex as WithColour).rgbColour.value = Color(200, 40, 10)
                    ColourTarget("hex").applyValueToFixture(hex, FxOutput.Colour(ExtendedColour(Color(100, 250, 30))), mode, weight)
                    assertEquals(rgb[w][m], hex.rgbColour.value, "colour $mode at $weight")
                }
            }
        }
    }
}
