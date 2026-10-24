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
 * `compatibleAttributes` is on no editing surface, and the FX edit sheet sends only
 * `{id, name, script}`, so rejecting a *merged* declaration would have made a script-only edit of
 * any pre-existing position definition fail forever with nothing the operator could do about it.
 */
class FxDefinitionCompatibilityValidationTest {

    // ─── Validation ─────────────────────────────────────────────────────────

    @Test
    fun `a POSITION effect may only advertise the position pair`() {
        assertNull(validateCompatibleAttributes(FxOutputType.POSITION, listOf("position")))
        assertNull(validateCompatibleAttributes(FxOutputType.POSITION, listOf("Position")))

        // The A11 shape itself. Reachable only by hand now that normalisation runs first, but the
        // rule is what makes the normalisation safe to be silent.
        assertNotNull(validateCompatibleAttributes(FxOutputType.POSITION, listOf("pan")))
        assertNotNull(validateCompatibleAttributes(FxOutputType.POSITION, listOf("dimmer")))
    }

    @Test
    fun `a COLOUR effect may only advertise the colour bundle`() {
        for (alias in listOf("rgbColour", "colour", "color", "rgbcolour")) {
            assertNull(validateCompatibleAttributes(FxOutputType.COLOUR, listOf(alias)), alias)
        }
        assertNotNull(validateCompatibleAttributes(FxOutputType.COLOUR, listOf("dimmer")))
        assertNotNull(validateCompatibleAttributes(FxOutputType.COLOUR, listOf("position")))
    }

    @Test
    fun `a SLIDER effect may advertise any single-value attribute but not a pair or a bundle`() {
        // Arbitrary named sliders and settings are legitimate and unresolvable from the name
        // alone, so they pass — as do the frontend's two sentinels.
        for (ok in listOf("dimmer", "uv", "zoom", "focus", "setting", "slider")) {
            assertNull(validateCompatibleAttributes(FxOutputType.SLIDER, listOf(ok)), ok)
        }

        assertNotNull(validateCompatibleAttributes(FxOutputType.SLIDER, listOf("position")))
        // Both of these resolve to a ColourTarget, which discards an FxOutput.Slider. Category =
        // Colour with the default Output Type = Slider used to write exactly this.
        assertNotNull(validateCompatibleAttributes(FxOutputType.SLIDER, listOf("rgbColour")))
        assertNotNull(validateCompatibleAttributes(FxOutputType.SLIDER, listOf("colour")))
    }

    @Test
    fun `no custom effect has a STEPS output, so neither step built-in can be forked`() {
        // Effects plan §3.6: a step list names the desk's palettes and settings, resolved per head,
        // and a definition syncs to desks that predate the output type.
        for (props in listOf(emptyList(), listOf("dimmer"), listOf("setting"), listOf("rgbColour", "position"))) {
            val message = validateCompatibleAttributes(FxOutputType.STEPS, props)
            assertNotNull(message, props.toString())
            assert("cannot be forked" in message)
        }
    }

    @Test
    fun `the offending attribute is named in the message`() {
        val message = validateCompatibleAttributes(FxOutputType.POSITION, listOf("position", "tilt"))
        assertNotNull(message)
        kotlin.test.assertTrue("tilt" in message, "message should name the bad entry: $message")
    }

    @Test
    fun `a retired built-in id may not name a definition`() {
        // An archive's old FX use it in the same field, and the importer renames it (terminology plan P3).
        for (id in listOf("Triangle", "Strobe", "Colour Pulse", "zoom_breathe", "Breathe")) assertNotNull(retiredIdProblem(id), id)
        assertNull(retiredIdProblem("MyStrobe"))
    }

    // ─── Normalisation ──────────────────────────────────────────────────────

    @Test
    fun `a POSITION definition stored with the axes heals on save`() {
        // What FxLibrary's new-definition form wrote for every position definition before A11.
        // Without this, a script-only edit of one would 400 forever: the merged declaration is
        // re-validated against a stored list no surface can edit.
        val healed = normaliseCompatibleAttributes(FxOutputType.POSITION, listOf("pan", "tilt"))
        assertEquals(listOf("position"), healed)
        assertNull(validateCompatibleAttributes(FxOutputType.POSITION, healed))
    }

    @Test
    fun `normalisation leaves everything else alone`() {
        // Only pan/tilt under POSITION has no reading under which the operator meant it. A wrong
        // declaration of any other shape is rejected out loud rather than silently rewritten.
        assertEquals(
            listOf("dimmer", "uv"),
            normaliseCompatibleAttributes(FxOutputType.SLIDER, listOf("dimmer", "uv")),
        )
        assertEquals(
            listOf("pan"),
            normaliseCompatibleAttributes(FxOutputType.SLIDER, listOf("pan")),
        )
        assertEquals(
            listOf("rgbColour"),
            normaliseCompatibleAttributes(FxOutputType.COLOUR, listOf("rgbColour")),
        )
        assertEquals(
            listOf("dimmer"),
            normaliseCompatibleAttributes(FxOutputType.POSITION, listOf("dimmer")),
        )
    }
}
