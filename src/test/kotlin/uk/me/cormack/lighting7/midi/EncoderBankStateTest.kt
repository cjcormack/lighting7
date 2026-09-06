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

/** Mirrors `ActiveBankStateTest`; the encoder bank is the same store with a property name in it. */
@OptIn(ExperimentalCoroutinesApi::class)
class EncoderBankStateTest {

    private val key = "x-touch-compact-standard"

    @Test
    fun `an unset device is on the default property`() {
        val state = EncoderBankState()
        assertEquals(EncoderBankState.DEFAULT_PROPERTY, state.propertyFor(key))
        assertEquals("dimmer", EncoderBankState.DEFAULT_PROPERTY)
        assertTrue(state.properties.value.isEmpty())
    }

    @Test
    fun `setProperty reports whether it changed anything`() {
        val state = EncoderBankState()
        assertTrue(state.setProperty(key, "colour"))
        assertEquals("colour", state.propertyFor(key))
        assertFalse(state.setProperty(key, "colour"))
        // Setting a device back to the default drops it from the map rather than storing it.
        assertTrue(state.setProperty(key, "dimmer"))
        assertTrue(state.properties.value.isEmpty())
        assertFalse(state.setProperty(key, "dimmer"))
    }

    @Test
    fun `devices are independent`() {
        val state = EncoderBankState()
        state.setProperty(key, "pan")
        state.setProperty("other-device", "tilt")
        assertEquals("pan", state.propertyFor(key))
        assertEquals("tilt", state.propertyFor("other-device"))
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
                EncoderBankState.EncoderBankChange(key, "dimmer", "colour"),
                EncoderBankState.EncoderBankChange(key, "colour", "pan"),
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

        assertEquals("dimmer", state.propertyFor(key))
        assertEquals("dimmer", state.propertyFor("other-device"))
        assertTrue(state.properties.first().isEmpty())
    }
}
