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
    fun `an unset device is on the default property`() {
        val state = EncoderBankState()
        assertEquals(EncoderBankState.DEFAULT, state.selectionFor(key))
        assertEquals("dimmer", EncoderBankState.DEFAULT_PROPERTY)
        assertEquals(EncoderBankSelection("dimmer"), EncoderBankState.DEFAULT)
        assertTrue(state.selections.value.isEmpty())
    }

    @Test
    fun `setProperty reports whether it changed anything`() {
        val state = EncoderBankState()
        assertTrue(state.setProperty(key, "colour"))
        assertEquals(EncoderBankSelection("colour"), state.selectionFor(key))
        assertFalse(state.setProperty(key, "colour"))
        // Setting a device back to the default drops it from the map rather than storing it.
        assertTrue(state.setProperty(key, "dimmer"))
        assertTrue(state.selections.value.isEmpty())
        assertFalse(state.setProperty(key, "dimmer"))
    }

    @Test
    fun `devices are independent`() {
        val state = EncoderBankState()
        state.setProperty(key, "pan")
        state.setProperty("other-device", "tilt")
        assertEquals("pan", state.selectionFor(key).propertyName)
        assertEquals("tilt", state.selectionFor("other-device").propertyName)
    }

    @Test
    fun `a change is emitted with its previous property`() = runTest {
        val state = EncoderBankState()
        val collected = mutableListOf<EncoderBankState.EncoderBankChange>()
        val job = launch { state.changes.collect { collected += it } }
        yield()

        state.setProperty(key, "colour")
        state.setProperty(key, "colour")  // no-op, emits nothing
        state.setProperty(key, "pan")
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
        state.setProperty(key, "colour")
        state.setProperty("other-device", "tilt")

        state.clearAll()

        assertEquals(EncoderBankState.DEFAULT, state.selectionFor(key))
        assertEquals(EncoderBankState.DEFAULT, state.selectionFor("other-device"))
        assertTrue(state.selections.first().isEmpty())
    }

    // ─── Colour axes ────────────────────────────────────────────────────

    @Test
    fun `another axis of the same property is another bank`() = runTest {
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
        assertTrue(state.setProperty(key, "rgbColour", ColourAxis.HUE_FINE))
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
