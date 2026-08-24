package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.FxOutputType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The authoring-time half of sweep item A11: `POST`/`PUT /fx/definitions` must not let a user
 * definition advertise a property its `outputType` cannot drive, because the apply would discard
 * the output silently.
 *
 * The normalisation tests are the more important half. Validation alone would have been a trap:
 * `compatibleProperties` is on no editing surface, and the FX edit sheet sends only
 * `{id, name, script}`, so rejecting a *merged* declaration would have made a script-only edit of
 * any pre-existing position definition fail forever with nothing the operator could do about it.
 */
class FxDefinitionCompatibilityValidationTest {

    // ─── Validation ─────────────────────────────────────────────────────────

    @Test
    fun `a POSITION effect may only advertise the position pair`() {
        assertNull(validateCompatibleProperties(FxOutputType.POSITION, listOf("position")))
        assertNull(validateCompatibleProperties(FxOutputType.POSITION, listOf("Position")))

        // The A11 shape itself. Reachable only by hand now that normalisation runs first, but the
        // rule is what makes the normalisation safe to be silent.
        assertNotNull(validateCompatibleProperties(FxOutputType.POSITION, listOf("pan")))
        assertNotNull(validateCompatibleProperties(FxOutputType.POSITION, listOf("dimmer")))
    }

    @Test
    fun `a COLOUR effect may only advertise the colour bundle`() {
        for (alias in listOf("rgbColour", "colour", "color", "rgbcolour")) {
            assertNull(validateCompatibleProperties(FxOutputType.COLOUR, listOf(alias)), alias)
        }
        assertNotNull(validateCompatibleProperties(FxOutputType.COLOUR, listOf("dimmer")))
        assertNotNull(validateCompatibleProperties(FxOutputType.COLOUR, listOf("position")))
    }

    @Test
    fun `a SLIDER effect may advertise any single-value property but not a pair or a bundle`() {
        // Arbitrary named sliders and settings are legitimate and unresolvable from the name
        // alone, so they pass — as do the frontend's two sentinels.
        for (ok in listOf("dimmer", "uv", "zoom", "focus", "setting", "slider")) {
            assertNull(validateCompatibleProperties(FxOutputType.SLIDER, listOf(ok)), ok)
        }

        assertNotNull(validateCompatibleProperties(FxOutputType.SLIDER, listOf("position")))
        // Both of these resolve to a ColourTarget, which discards an FxOutput.Slider. Category =
        // Colour with the default Output Type = Slider used to write exactly this.
        assertNotNull(validateCompatibleProperties(FxOutputType.SLIDER, listOf("rgbColour")))
        assertNotNull(validateCompatibleProperties(FxOutputType.SLIDER, listOf("colour")))
    }

    @Test
    fun `the offending property is named in the message`() {
        val message = validateCompatibleProperties(FxOutputType.POSITION, listOf("position", "tilt"))
        assertNotNull(message)
        kotlin.test.assertTrue("tilt" in message, "message should name the bad entry: $message")
    }

    // ─── Normalisation ──────────────────────────────────────────────────────

    @Test
    fun `a POSITION definition stored with the axes heals on save`() {
        // What FxLibrary's new-definition form wrote for every position definition before A11.
        // Without this, a script-only edit of one would 400 forever: the merged declaration is
        // re-validated against a stored list no surface can edit.
        val healed = normaliseCompatibleProperties(FxOutputType.POSITION, listOf("pan", "tilt"))
        assertEquals(listOf("position"), healed)
        assertNull(validateCompatibleProperties(FxOutputType.POSITION, healed))
    }

    @Test
    fun `normalisation leaves everything else alone`() {
        // Only pan/tilt under POSITION has no reading under which the operator meant it. A wrong
        // declaration of any other shape is rejected out loud rather than silently rewritten.
        assertEquals(
            listOf("dimmer", "uv"),
            normaliseCompatibleProperties(FxOutputType.SLIDER, listOf("dimmer", "uv")),
        )
        assertEquals(
            listOf("pan"),
            normaliseCompatibleProperties(FxOutputType.SLIDER, listOf("pan")),
        )
        assertEquals(
            listOf("rgbColour"),
            normaliseCompatibleProperties(FxOutputType.COLOUR, listOf("rgbColour")),
        )
        assertEquals(
            listOf("dimmer"),
            normaliseCompatibleProperties(FxOutputType.POSITION, listOf("dimmer")),
        )
    }
}
