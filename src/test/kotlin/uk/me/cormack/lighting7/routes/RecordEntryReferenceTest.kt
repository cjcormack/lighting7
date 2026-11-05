package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.AttributeFamily
import uk.me.cormack.lighting7.fx.CueAssignmentResolver.AttributeValue
import uk.me.cormack.lighting7.fx.PaletteReferences
import uk.me.cormack.lighting7.fx.ProgrammerValue
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** How Record and Update read a programmer reference (palette-references plan D16). */
class RecordEntryReferenceTest {

    private val half = UUID.nameUUIDFromBytes("Half".toByteArray())
    private val full = UUID.nameUUIDFromBytes("Full".toByteArray())
    private fun level(v: Int) = AttributeValue.Slider(v.toUByte())
    private fun entry(value: AttributeValue, reference: UUID? = null, reconciledFrom: AttributeValue? = null) =
        RecordEntry("hex-1", "dimmer", value, null, AttributeFamily.INTENSITY, reconciledFrom, reference)

    @Test
    fun `a reference records as itself, unless its bundle partner rewrote what is on stage`() {
        assertEquals(PaletteReferences.valueOf(half), entry(level(128), half).storedValue())
        assertEquals("0", entry(level(0), half, reconciledFrom = level(128)).storedValue())
        assertEquals("40", entry(level(40)).storedValue())
    }

    @Test
    fun `two references to one palette are the same whatever each resolves to, and a literal never is one`() {
        assertTrue(entry(level(200), half).sameAs(ProgrammerValue.Reference(half, level(128))), "a palette edit moves both")
        assertFalse(entry(level(128), half).sameAs(ProgrammerValue.Reference(full, level(128))))
        assertFalse(entry(level(128), half).sameAs(ProgrammerValue.Hard(level(128))), "a press over a literal it equals is a change")
        assertFalse(entry(level(128)).sameAs(ProgrammerValue.Reference(half, level(128))))
        assertTrue(entry(level(128)).sameAs(ProgrammerValue.Hard(level(128))))
    }
}
