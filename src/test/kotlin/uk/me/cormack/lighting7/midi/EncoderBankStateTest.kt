package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Mirrors `ActiveBankStateTest`; the encoder bank is the same store with a selection in it. */
@OptIn(ExperimentalCoroutinesApi::class)
class EncoderBankStateTest {

    private val key = "x-touch-compact-standard"

    @Test
    fun `an unset device is on the default attribute`() {
        val state = EncoderBankState()
        assertEquals(EncoderBankState.DEFAULT, state.selectionFor(key))
        assertEquals("dimmer", EncoderBankState.DEFAULT_ATTRIBUTE)
        assertEquals(EncoderBankSelection("dimmer"), EncoderBankState.DEFAULT)
        assertTrue(state.selections.value.isEmpty())
    }

    @Test
    fun `setAttribute reports whether it changed anything`() {
        val state = EncoderBankState()
        assertTrue(state.setAttribute(key, "colour"))
        assertEquals(EncoderBankSelection("colour"), state.selectionFor(key))
        assertFalse(state.setAttribute(key, "colour"))
        // Setting a device back to the default drops it from the map rather than storing it.
        assertTrue(state.setAttribute(key, "dimmer"))
        assertTrue(state.selections.value.isEmpty())
        assertFalse(state.setAttribute(key, "dimmer"))
    }

    @Test
    fun `devices are independent`() {
        val state = EncoderBankState()
        state.setAttribute(key, "pan")
        state.setAttribute("other-device", "tilt")
        assertEquals("pan", state.selectionFor(key).attribute)
        assertEquals("tilt", state.selectionFor("other-device").attribute)
    }

    @Test
    fun `a change is emitted with its previous attribute`() = runTest {
        val state = EncoderBankState()
        val collected = mutableListOf<EncoderBankState.EncoderBankChange>()
        val job = launch { state.changes.collect { collected += it } }
        yield()

        state.setAttribute(key, "colour")
        state.setAttribute(key, "colour")  // no-op, emits nothing
        state.setAttribute(key, "pan")
        yield()
        job.cancel()

        assertEquals(
            listOf(
                EncoderBankState.EncoderBankChange(key, EncoderBankSelection("dimmer"), EncoderBankSelection("colour")),
                EncoderBankState.EncoderBankChange(key, EncoderBankSelection("colour"), EncoderBankSelection("pan")),
            ),
            collected,
        )
    }

    @Test
    fun `clearAll wipes every device back to the default`() = runTest {
        val state = EncoderBankState()
        state.setAttribute(key, "colour")
        state.setAttribute("other-device", "tilt")

        state.clearAll()

        assertEquals(EncoderBankState.DEFAULT, state.selectionFor(key))
        assertEquals(EncoderBankState.DEFAULT, state.selectionFor("other-device"))
        assertTrue(state.selections.first().isEmpty())
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `another axis of the same attribute is another bank`() = runTest {
        val state = EncoderBankState()
        val collected = mutableListOf<EncoderBankState.EncoderBankChange>()
        val job = launch { state.changes.collect { collected += it } }
        yield()

        assertTrue(state.set(key, EncoderBankSelection("rgbColour")))
        assertTrue(state.set(key, EncoderBankSelection("rgbColour", ColourAxis.SATURATION)), "same property, new axis: a change")
        assertEquals(EncoderBankSelection("rgbColour", ColourAxis.SATURATION), state.selectionFor(key))
        assertFalse(state.set(key, EncoderBankSelection("rgbColour", ColourAxis.SATURATION)))
        yield()
        job.cancel()

        assertEquals(
            EncoderBankState.EncoderBankChange(
                key, EncoderBankSelection("rgbColour"), EncoderBankSelection("rgbColour", ColourAxis.SATURATION),
            ),
            collected.last(),
            "the change carries both selections, axis included",
        )
    }

    @Test
    fun `an explicit hue is the same bank as no axis`() {
        val state = EncoderBankState()
        assertTrue(state.set(key, EncoderBankSelection("rgbColour")))
        assertFalse(state.set(key, EncoderBankSelection("rgbColour", ColourAxis.HUE)), "null and HUE are one bank")
        assertTrue(state.setAttribute(key, "rgbColour", ColourAxis.HUE_FINE))
        assertEquals(ColourAxis.HUE_FINE, state.selectionFor(key).colourAxis)
    }

    @Test
    fun `an explicit hue is canonicalised away before it is stored`() {
        val state = EncoderBankState()
        // Reached from another bank, so the early "no change" return cannot be what hides it.
        assertTrue(state.set(key, EncoderBankSelection("pan")))
        assertTrue(state.set(key, EncoderBankSelection("rgbColour", ColourAxis.HUE)))
        assertEquals(
            EncoderBankSelection("rgbColour"),
            state.selectionFor(key),
            "the stored form is the one every hue binding carries",
        )
        // The default reached with an explicit hue drops the device from the map, as `dimmer` does.
        assertTrue(state.set(key, EncoderBankSelection("dimmer", ColourAxis.HUE)))
        assertTrue(state.selections.value.isEmpty())
        assertEquals(EncoderBankState.DEFAULT, state.selectionFor(key))
    }
}
