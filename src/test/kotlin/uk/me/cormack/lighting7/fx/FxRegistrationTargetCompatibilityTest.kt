package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guard for sweep item **A11**: every built-in effect's `compatibleProperties` must name
 * properties that resolve to an [FxTarget] able to apply that effect's [FxOutputType].
 *
 * Why this needs a test rather than review: a mismatched [FxOutput] is *silently dropped* by
 * [FxTarget.applyValueToFixture] — no light, no error, no log. The seven position effects declared
 * `[pan, tilt]` against a `POSITION` output for the desk's whole life, and every frontend picker
 * duly posted `propertyName: "pan"`, which resolves to a [SliderTarget] that discards an
 * [FxOutput.Position]. Nothing in the suite noticed, because nothing applied a position effect
 * through a target resolver. This test is that missing coverage; before the fix it goes red on all
 * seven.
 *
 * Cost: metadata only. It parses each `.fx.kts` frontmatter via [FxFileLoader.parseFxFile] and
 * never compiles a body — `FxRegistryTest` already pays for the full 28-effect compile, and a
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
        mapOf(
            "dimmer" to hex,
            "uv" to hex,
            "rgbColour" to hex,
            "position" to spot,
            "pan" to spot,
            "tilt" to spot,
        )
    }

    private data class BuiltIn(
        val path: String,
        val id: String,
        val outputType: FxOutputType,
        val compatibleProperties: List<String>,
    )

    private fun builtIns(): List<BuiltIn> {
        val loader = FxFileLoader::class.java.classLoader
        val index = loader.getResource("fx/index.txt")!!.readText().lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(index.size >= 25, "fx/index.txt should list at least 25 effects, got ${index.size}")

        return index.map { path ->
            val text = loader.getResource("fx/$path")!!.readText()
            val (metadata, _) = FxFileLoader.parseFxFile(text)
            BuiltIn(
                path = path,
                id = metadata.id,
                outputType = FxOutputType.valueOf(metadata.outputType),
                compatibleProperties = metadata.compatibleProperties,
            )
        }
    }

    @Test
    fun `every built-in advertises only properties its output type can drive`() {
        val failures = mutableListOf<String>()

        for (effect in builtIns()) {
            assertTrue(
                effect.compatibleProperties.isNotEmpty(),
                "fx/${effect.path} declares no compatibleProperties, so no surface can offer it",
            )

            for (prop in effect.compatibleProperties) {
                val fixture = representatives[prop]
                // Not a skip: an unknown name means either a typo in the frontmatter or a new
                // property this guard hasn't been taught, and both need a human.
                if (fixture == null) {
                    failures += "fx/${effect.path} names property '$prop', which this test has no " +
                        "representative fixture for — add one to `representatives`"
                    continue
                }

                // outputType = null withholds FxTargetFactory's pan/tilt coercion on purpose.
                // That coercion exists to repair rows *already persisted* with propertyName
                // "pan"; it is not a licence for dishonest metadata. The frontend resolves a
                // property name with no such help — `ConfigureEffectSheet` takes
                // compatibleProperties[0] outright — so the name must stand on its own.
                val target = FxTargetFactory.forFixture(
                    fixture.key, prop, outputType = null, fixture = fixture,
                )
                if (target.acceptedOutputType != effect.outputType) {
                    failures += "fx/${effect.path} (${effect.id}) outputs ${effect.outputType} but " +
                        "advertises '$prop', which resolves to ${target::class.simpleName} " +
                        "accepting ${target.acceptedOutputType} — the output would be discarded"
                }
            }
        }

        assertTrue(failures.isEmpty(), "Incompatible compatibleProperties:\n" + failures.joinToString("\n"))
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
    fun `an unresolvable property name degrades to a setting target rather than throwing`() {
        // Cue, Look and Include spawn all route through FxTargetFactory at fire time. A recorded
        // row naming a property the fixture has since lost must not take the show down.
        val hex = HexFixture(universe, key = "hex-1", fixtureName = "Hex 1", firstChannel = 1)
        val target = FxTargetFactory.forFixture(hex.key, "nonexistent", FxOutputType.SLIDER, hex)
        assertEquals(SettingTarget(hex.key, "nonexistent"), target)
    }
}
