package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard for sweep item **A11**: every built-in effect's `compatibleAttributes` must name
 * properties that resolve to an [FxTarget] able to apply that effect's [FxOutputType].
 *
 * Why this needs a test rather than review: a mismatched [FxOutput] is *silently dropped* by
 * [FxTarget.applyValueToFixture] — no light, no error, no log. The seven position effects declared
 * `[pan, tilt]` against a `POSITION` output for the desk's whole life, and every frontend picker
 * duly posted `attribute: "pan"`, which resolves to a [SliderTarget] that discards an
 * [FxOutput.Position]. Nothing in the suite noticed, because nothing applied a position effect
 * through a target resolver. This test is that missing coverage; before the fix it goes red on all
 * seven.
 *
 * Cost: metadata only. It parses each `.fx.kts` frontmatter via [FxFileLoader.parseFxFile] and
 * never compiles a body — `FxRegistryTest` already pays for the full built-in compile, and a
 * second one would be a minute of suite time for nothing.
 */
class FxRegistrationTargetCompatibilityTest {

    private val universe = Universe(0, 0)

    /**
     * A fixture that actually has each property a built-in can name, so the resolver's fallback
     * branch (which reflects on the fixture to tell a slider from a setting) reaches the same
     * answer it would on a real rig. Bare fixtures with no controller transaction suffice — the
     * resolver reads only the static patch, as in `LocateValueResolverTest`.
     */
    private val representatives: Map<String, Fixture> = run {
        val hex = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)
        val spot = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)
        val revolution = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        val robe = RobeColorSpot575Fixture.Mode2Ch(universe, "robe-1", "Robe 1", 1)
        mapOf(
            "dimmer" to hex,
            "strobe" to hex,
            "uv" to hex,
            "rgbColour" to hex,
            "position" to spot,
            "pan" to spot,
            "tilt" to spot,
            "zoom" to revolution,
            "focus" to revolution,
            "iris" to robe,
            "frost" to robe,
            "prism" to spot,
            // The sentinel Slot Steps is offered through (effects plan §3.6): the client turns it
            // into the setting chosen, so here it resolves to a setting target like any setting.
            "setting" to robe,
        )
    }

    private data class BuiltIn(
        val path: String,
        val id: String,
        val category: String,
        val fxMode: String,
        val outputType: FxOutputType,
        val compatibleAttributes: List<String>,
    )

    private fun builtIns(): List<BuiltIn> {
        val loader = FxFileLoader::class.java.classLoader
        val index = loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(index.size >= 14, "fx/index.txt should list at least 14 effects, got ${index.size}")

        return index.map { path ->
            val text = loader.getResource("fx/$path")!!.readText()
            val (metadata, _) = FxFileLoader.parseFxFile(text)
            BuiltIn(
                path = path,
                id = metadata.id,
                category = metadata.family,
                fxMode = metadata.fxMode,
                outputType = FxOutputType.valueOf(metadata.outputType),
                compatibleAttributes = metadata.compatibleAttributes,
            )
        }
    }

    @Test
    fun `every built-in advertises only attributes its output type can drive`() {
        val failures = mutableListOf<String>()

        for (effect in builtIns()) {
            assertTrue(
                effect.compatibleAttributes.isNotEmpty(),
                "fx/${effect.path} declares no compatibleAttributes, so no surface can offer it",
            )

            for (prop in effect.compatibleAttributes) {
                val fixture = representatives[prop]
                // Not a skip: an unknown name means either a typo in the frontmatter or a new
                // property this guard hasn't been taught, and both need a human.
                if (fixture == null) {
                    failures += "fx/${effect.path} names property '$prop', which this test has no " +
                        "representative fixture for — add one to `representatives`"
                    continue
                }

                // outputType = null withholds FxTargetFactory's pan/tilt coercion on purpose.
                // That coercion exists to repair rows *already persisted* with attribute
                // "pan"; it is not a licence for dishonest metadata. The frontend resolves a
                // property name with no such help — `ConfigureFxSheet` takes
                // compatibleAttributes[0] outright — so the name must stand on its own.
                val target = FxTargetFactory.forFixture(
                    fixture.key, prop, outputType = null, fixture = fixture,
                )
                if (!target.accepts(effect.outputType)) {
                    failures += "fx/${effect.path} (${effect.id}) outputs ${effect.outputType} but " +
                        "advertises '$prop', which resolves to ${target::class.simpleName} " +
                        "accepting ${target.acceptedOutputType} — the output would be discarded"
                }
            }
        }

        assertTrue(failures.isEmpty(), "Incompatible compatibleAttributes:\n" + failures.joinToString("\n"))
    }

    /**
     * The library by family (terminology plan §4.2): the renamed ids, no Breathe, Lightning Strike a
     * standard Intensity FX, and the two Steps FX one family. `dimmer` and `palette` are Intensity's
     * and Steps' stored spellings.
     */
    @Test
    fun `the library's families hold today's ids`() {
        val byFamily = builtIns().groupBy({ it.category }, { it.id }).mapValues { it.value.toSet() }
        assertEquals(
            mapOf(
                "intensity" to setOf("Wave", "Flicker", "CandleFlicker", "FluorescentFlicker", "LightningStrike"),
                "colour" to setOf("ColourCycle", "RainbowCycle", "ColourStrobe", "ColourWave", "ColourFade", "ColourFlicker"),
                "position" to setOf("Path"),
                STEPS_CATEGORY to setOf(PALETTE_STEPS_ID, SLOT_STEPS_ID),
            ),
            byFamily,
        )
        assertTrue(builtIns().all { it.fxMode == "STANDARD" || it.fxMode == "STATEFUL" }, "the composite mode is gone")
    }

    // ─── The coercion itself ────────────────────────────────────────────────

    @Test
    fun `a POSITION effect on the pan axis resolves to the position pair`() {
        val spot = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)

        for (axis in listOf("pan", "tilt", "Pan", "TILT")) {
            val target = FxTargetFactory.forFixture(spot.key, axis, FxOutputType.POSITION, spot)
            assertEquals(
                PositionTarget(FxTargetRef.fixture(spot.key)), target,
                "'$axis' on a POSITION effect must canonicalise to the synthetic position property",
            )
        }
    }

    @Test
    fun `a SLIDER effect on the pan axis stays a slider on that axis`() {
        val spot = Fusion100SpotMkIIFixture.Mode15Ch(universe, "spot-1", "Spot 1", 1)

        // The coercion must not swallow this: a sine wave on pan alone is a legitimate ask, and
        // a PositionTarget here would move tilt as well.
        val target = FxTargetFactory.forFixture(spot.key, "pan", FxOutputType.SLIDER, spot)
        assertEquals(SliderTarget(spot.key, "pan"), target)
        assertEquals(FxOutputType.SLIDER, target.acceptedOutputType)
    }

    @Test
    fun `colour aliases and case all reach the colour target`() {
        val hex = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)

        // lightFx.kt used to match these case-sensitively and accept only "rgbColour"/"colour";
        // unifying on FxTargetFactory made every surface take the same superset.
        for (name in listOf("colour", "Colour", "color", "rgbColour", "rgbcolour")) {
            val target = FxTargetFactory.forFixture(hex.key, name, FxOutputType.COLOUR, hex)
            assertEquals(
                ColourTarget(FxTargetRef.fixture(hex.key)), target,
                "'$name' should resolve to the colour target",
            )
        }
    }

    @Test
    fun `an unresolvable attribute name degrades to a setting target rather than throwing`() {
        // Cue, Look and Include spawn all route through FxTargetFactory at fire time. A recorded
        // row naming a property the fixture has since lost must not take the show down.
        val hex = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)
        val target = FxTargetFactory.forFixture(hex.key, "nonexistent", FxOutputType.SLIDER, hex)
        assertEquals(SettingTarget(hex.key, "nonexistent"), target)
    }
}
