package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.fixture.PropertyCategory
import java.awt.Color
import kotlin.test.assertEquals

/**
 * `CueAssignmentResolver.reconcileBundledEmitters`: when a target composes both a colour and a
 * `bundleWithColour` emitter, the emitter's own value replaces the colour's copy of it, so the two
 * keys that drive one W/A/UV channel carry one value and publish order cannot decide it.
 */
class BundledEmitterComposeTest {

    private val resolver = CueAssignmentResolver()

    private fun colour(
        value: ExtendedColour,
        cueId: Int = 1,
        priority: Int = 10,
        weight: Double = 1.0,
        name: String = "rgbColour",
        role: CueAssignmentResolver.BundleRole? = CueAssignmentResolver.BundleRole.COLOUR,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId, priority = priority, fadeWeight = weight,
        targetKey = "hex-1", targetIsGroup = false, propertyName = name,
        category = PropertyCategory.COLOUR,
        value = CueAssignmentResolver.PropertyValue.Colour(value),
        bundleRole = role,
    )

    private fun emitter(
        name: String,
        category: PropertyCategory,
        level: Int,
        cueId: Int = 1,
        priority: Int = 10,
        weight: Double = 1.0,
        bundled: Boolean = true,
    ) = CueAssignmentResolver.Assignment(
        cueId = cueId, priority = priority, fadeWeight = weight,
        targetKey = "hex-1", targetIsGroup = false, propertyName = name,
        category = category,
        value = CueAssignmentResolver.PropertyValue.Slider(level.toUByte()),
        bundleRole = if (bundled) CueAssignmentResolver.BundleRole.EMITTER else null,
    )

    private fun composedColour(rows: List<CueAssignmentResolver.Assignment>, weights: CueAssignmentResolver.FadeWeights = CueAssignmentResolver.FadeWeights.NONE): ExtendedColour =
        (resolver.compose(resolver.cook(rows), weights)["hex-1"]!!["rgbColour"] as CueAssignmentResolver.PropertyValue.Colour).value

    @Test
    fun `the emitter's own value replaces the colour's copy, in either row order`() {
        val red = colour(ExtendedColour(Color(255, 0, 0), white = 50u))
        val white = emitter("white", PropertyCategory.WHITE, 200)

        assertEquals(200u.toUByte(), composedColour(listOf(red, white)).white)
        assertEquals(200u.toUByte(), composedColour(listOf(white, red)).white)
        assertEquals(Color(255, 0, 0), composedColour(listOf(white, red)).color, "RGB is the colour's own")
    }

    @Test
    fun `each emitter replaces only its own component`() {
        val rows = listOf(
            colour(ExtendedColour(Color.BLUE, white = 10u, amber = 20u, uv = 30u)),
            emitter("amber", PropertyCategory.AMBER, 120),
            emitter("uv", PropertyCategory.UV, 0),
        )
        val composed = composedColour(rows)
        assertEquals(10u.toUByte(), composed.white, "no white row — the colour keeps its own")
        assertEquals(120u.toUByte(), composed.amber)
        assertEquals(0u.toUByte(), composed.uv, "an emitter at 0 still wins — it is asserted")
    }

    @Test
    fun `the emitter wins outright across cues, whichever ranks higher`() {
        // The colour from a higher-priority cue, the white from a lower one.
        val rows = listOf(
            colour(ExtendedColour(Color.RED, white = 0u), cueId = 2, priority = 20),
            emitter("white", PropertyCategory.WHITE, 200, cueId = 1, priority = 10),
        )
        assertEquals(200u.toUByte(), composedColour(rows).white)
    }

    @Test
    fun `a crossfading emitter hands the colour its blended value`() {
        val rows = listOf(
            colour(ExtendedColour(Color.RED, white = 0u), cueId = 3),
            emitter("white", PropertyCategory.WHITE, 0, cueId = 1, priority = 10),
            emitter("white", PropertyCategory.WHITE, 200, cueId = 2, priority = 20),
        )
        val halfway = CueAssignmentResolver.FadeWeights(mapOf(1 to 0.5, 2 to 0.5))
        val cooked = resolver.cook(rows)
        val composed = resolver.compose(cooked, halfway)["hex-1"]!!
        val slider = (composed["white"] as CueAssignmentResolver.PropertyValue.Slider).value
        assertEquals(100u.toUByte(), slider, "precondition: the white key is mid-fade")
        assertEquals(slider, (composed["rgbColour"] as CueAssignmentResolver.PropertyValue.Colour).value.white)
    }

    @Test
    fun `a slider that is not a bundled emitter leaves the colour alone`() {
        val rows = listOf(
            colour(ExtendedColour(Color.RED, white = 50u)),
            emitter("white", PropertyCategory.WHITE, 200, bundled = false),
        )
        assertEquals(50u.toUByte(), composedColour(rows).white)
    }

    @Test
    fun `only the bundle's own colour takes the emitter, not another colour-valued property`() {
        // A colour macro is COLOUR-category too, and parses to a Colour — but it is not the property
        // whose ExtendedColour carries the emitters, so it takes no part.
        val macro = colour(ExtendedColour(Color.GREEN, white = 5u), name = "colourMacro", role = null)
        val composed = resolver.compose(
            resolver.cook(listOf(macro, colour(ExtendedColour(Color.RED)), emitter("white", PropertyCategory.WHITE, 200))),
            CueAssignmentResolver.FadeWeights.NONE,
        )["hex-1"]!!
        assertEquals(200u.toUByte(), (composed["rgbColour"] as CueAssignmentResolver.PropertyValue.Colour).value.white)
        assertEquals(5u.toUByte(), (composed["colourMacro"] as CueAssignmentResolver.PropertyValue.Colour).value.white)
    }

    @Test
    fun `a target with an emitter and no colour composes unchanged`() {
        val composed = resolver.resolve(listOf(emitter("white", PropertyCategory.WHITE, 200)))
        assertEquals(
            mapOf(CueAssignmentResolver.Key.fixture("hex-1", "white") to CueAssignmentResolver.PropertyValue.Slider(200u)),
            composed,
        )
    }
}
