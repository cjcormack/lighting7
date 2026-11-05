package uk.me.cormack.lighting7.fx

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rings move without a layer moving (palette-references plan D12): the store's references
 * revision moves for a write or a release that touches a reference, and only then, so a fader
 * does not say the layer frame again on every move.
 */
class ProgrammerStoreReferenceRevisionTest {

    private val palette = UUID.nameUUIDFromBytes("Half".toByteArray())
    private val level = CueAssignmentResolver.AttributeValue.Slider(128u)

    @Test
    fun `a literal write leaves the revision alone, and a reference's write, replacement and release each move it`() {
        val store = ProgrammerStore()
        val start = store.referencesRevision.value

        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", level)
        assertEquals(start, store.referencesRevision.value)

        store.putValue(ProgrammerOwner.WEB, "hex-2", "dimmer", ProgrammerValue.Reference(palette, level))
        val written = store.referencesRevision.value
        assertTrue(written > start)

        store.put(ProgrammerOwner.WEB, "hex-2", "dimmer", level)
        val replaced = store.referencesRevision.value
        assertTrue(replaced > written, "a literal over a reference moves it")

        store.putValue(ProgrammerOwner.WEB, "hex-2", "dimmer", ProgrammerValue.Reference(palette, level))
        val again = store.referencesRevision.value
        store.clear(ProgrammerOwner.WEB, "hex-2", "dimmer")
        assertTrue(store.referencesRevision.value > again, "a release moves it")

        val cleared = store.referencesRevision.value
        store.clear(ProgrammerOwner.WEB, "hex-1", "dimmer")
        assertEquals(cleared, store.referencesRevision.value, "releasing a literal does not")
    }

    @Test
    fun `a value over a buried reference leaves the revision alone, and its release, uncovering it, moves it`() {
        val store = ProgrammerStore()
        store.putValue(ProgrammerOwner.INCLUDE, "hex-1", "dimmer", ProgrammerValue.Reference(palette, level))
        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", level)
        val covered = store.referencesRevision.value

        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", CueAssignmentResolver.AttributeValue.Slider(10u))
        assertEquals(covered, store.referencesRevision.value, "a fader over a buried reference")

        store.clear(ProgrammerOwner.WEB, "hex-1", "dimmer")
        assertTrue(store.referencesRevision.value > covered, "the reference is on top again")
    }

    @Test
    fun `referencing an Included value goes over it as yours, so Update sees the change`() {
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.INCLUDE, "hex-1", "dimmer", level)

        assertTrue(store.referenceInPlace("hex-1", "dimmer", palette))
        val slots = store.slotsFor("hex-1", "dimmer")
        assertEquals(listOf(ProgrammerOwner.WEB, ProgrammerOwner.INCLUDE), slots.map { it.owner })
        assertEquals(ProgrammerValue.Reference(palette, level), slots.first().value)
        assertEquals(ProgrammerValue.Hard(level), slots.last().value, "the Include baseline is kept")
    }

    @Test
    fun `referencing a value in place keeps its bytes, owner and seq`() {
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.SURFACE, "hex-1", "dimmer", level, sourceGroup = "wash")
        val before = store.get("hex-1", "dimmer")!!

        assertTrue(store.referenceInPlace("hex-1", "dimmer", palette))
        val after = store.get("hex-1", "dimmer")!!
        assertEquals(ProgrammerValue.Reference(palette, level), after.value)
        assertEquals(before.owner, after.owner)
        assertEquals(before.seq, after.seq)
        assertEquals("wash", after.sourceGroup)
        assertTrue(!store.referenceInPlace("hex-1", "dimmer", palette), "already a reference to it")
    }
}
