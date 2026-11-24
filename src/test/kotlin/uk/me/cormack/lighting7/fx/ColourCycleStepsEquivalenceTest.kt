package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * wheel-colour-chase plan P3: Colour Cycle on steps (D2) and the script that blends in the script and
 * answers one colour, `fx/legacy/ColourCycleBlended.fx.kts`, land the same colour on an RGB head, to
 * the DMX step, at every phase a head is asked for: each `between`, a grid of `fade`, lists of 0 to 5
 * colours, every Direction on an even and an odd cycle, and a spread over four heads.
 *
 * The new output is landed as the engine lands it, through [ColourTarget.outputColour] on a hex;
 * the old is the colour it answered. An unknown `between` reads as Fade on both (P1).
 */
class ColourCycleStepsEquivalenceTest {

    private companion object {
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }

        val PHASES: List<Double> = (0..96).map { it / 96.0 } + listOf(1e-12, 0.1 - 1e-12, 0.5 - 1e-12, 1.0 - 1e-12)
        val SPREADS = listOf(0.0, 0.5, 1.0, 4.0 / 3.0)
        const val HEADS = 4
        val COLOURS = listOf("#ff0000", "#00ff80;w200", "#0000ff", "#ffffff;a40;u10", "#123456")
        val BETWEENS = listOf("CUT", "FADE", "SMOOTH", "", "WOBBLE")
        val FADES = listOf("0", "0.05", "0.25", "0.5", "1", "1.5", "", "abc")
    }

    private val universe = Universe(0, 0)

    private val hex = run {
        val fixtures = Fixtures()
        fixtures.register {
            addController(MockDmxController(universe))
            addFixture(HexFixture(universe, "hex", "Hex", 1))
        }
        fixtures.withTransaction(ControllerTransaction(fixtures.controllers)).untypedGroupableFixture("hex")
    }

    private val target = ColourTarget(FxTargetRef.fixture("hex"), wheels = true)

    private fun legacy(): FxFactory {
        val text = checkNotNull(javaClass.classLoader.getResource("fx/legacy/ColourCycleBlended.fx.kts")).readText()
        val (metadata, body) = FxFileLoader.parseFxFile(text)
        val compiled = FxScriptCompiler().compile(body, FxMode.valueOf(metadata.fxMode))
        assertTrue(compiled.isSuccess, "the old Colour Cycle compiles: ${compiled.diagnostics}")
        return ScriptFxAdapter.createFactory(
            compiled, metadata.parameters.map(FxFileParameter::toParameterInfo), metadata.name, FxOutputType.valueOf(metadata.outputType),
        )
    }

    private fun grids(): List<Map<String, String>> = buildList {
        for (n in 0..5) {
            val colours = COLOURS.take(n).joinToString(",")
            add(mapOf("colours" to colours))
            for (between in BETWEENS) for (fade in FADES) add(mapOf("colours" to colours, "between" to between, "fade" to fade))
        }
    }

    @Test
    fun `Colour Cycle on steps lands every colour its old script answered, to the DMX step`() {
        val old = legacy()
        val failures = ArrayList<String>()
        for (params in grids()) {
            val before = old(params, null, null)
            val after = registry.createFx("ColourCycle", params)
            for (direction in FxDirection.entries) for (cycle in 0L..1L) for (spread in SPREADS) {
                for (head in 0 until HEADS) for (phase in PHASES) {
                    val member = direction.apply(cyclePhase(phase - spread * head / HEADS), cycle)
                    val expected = assertIs<FxOutput.Colour>(before.calculate(member)).color
                    val output = after.calculate(member)
                    assertIs<FxOutput.Steps>(output)
                    val actual = target.outputColour(hex, output) { ExtendedColour.BLACK }
                    if (expected != actual && failures.size < 20) {
                        failures += "$params at $direction cycle $cycle spread $spread head $head phase $phase: old $expected, new $actual"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `Split holds the two colours' half-way mix for the share fade names`() {
        val split = registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#0000ff", "between" to "SPLIT", "fade" to "0.5"))
        val held = assertIs<FxOutput.Steps>(split.calculate(0.1))
        assertEquals(null, held.split, "the first quarter is red, whole")
        val half = blendExtendedColours(parseExtendedColour("#ff0000"), parseExtendedColour("#0000ff"), 0.5)
        for (phase in listOf(0.26, 0.4, 0.49)) {
            val output = assertIs<FxOutput.Steps>(split.calculate(phase))
            assertEquals(0.5, output.blend)
            assertEquals(half, target.outputColour(hex, output) { ExtendedColour.BLACK }, "the mix is held at $phase")
        }
        assertEquals(0, assertIs<FxOutput.Steps>(split.calculate(0.37)).snapped, "short of the split's middle a slot is the first")
        assertEquals(1, assertIs<FxOutput.Steps>(split.calculate(0.38)).snapped, "past it the second")
    }
}
