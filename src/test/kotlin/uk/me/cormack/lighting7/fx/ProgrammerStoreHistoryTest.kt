package uk.me.cormack.lighting7.fx

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The store's half of the programmer's history: what a step keeps, and when the store asks for one. */
class ProgrammerStoreHistoryTest {

    private val level = { n: Int -> CueAssignmentResolver.AttributeValue.Slider(n.toUByte()) }

    @Test
    fun `a restore leaves a held flash and an unpark's hand-down as they are now`() {
        val store = ProgrammerStore()
        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", level(100))
        val before = store.snapshot()
        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", level(150))
        store.put(ProgrammerOwner.FLASH, "hex-1", "dimmer", level(255))
        store.putChannel(ProgrammerOwner.UNPARK, 0, 40, 77u, touched = false)

        store.restore(before)
        assertEquals(listOf("flash", "web"), store.slotsFor("hex-1", "dimmer").map { it.owner.id })
        assertEquals(level(100), store.slotsFor("hex-1", "dimmer").last().value.resolved)
        assertEquals(77u.toUByte(), store.getChannel(0, 40))

        store.clear(ProgrammerOwner.FLASH, "hex-1", "dimmer")
        store.restore(store.snapshot().copy(attributes = emptyMap()))
        assertNull(store.get("hex-1", "dimmer"))
        assertEquals(77u.toUByte(), store.getChannel(0, 40), "a snapshot never holds the hand-down, and never takes it away")
    }

    @Test
    fun `a clear or a layer change that changes nothing asks for no step`() {
        val store = ProgrammerStore()
        var asked = 0
        store.beforeMutation = { asked++ }
        store.clearAll()
        store.clearOwner(ProgrammerOwner.LOCATE)
        store.mutateLayers { it to Unit }
        store.lastIncludedTarget = null
        store.putChannel(ProgrammerOwner.UNPARK, 0, 1, 10u, touched = false)
        assertEquals(0, asked)
        store.clearAll()
        assertEquals(0, asked, "a hand-down alone is nothing a step keeps")
        store.put(ProgrammerOwner.WEB, "hex-1", "dimmer", level(1))
        assertEquals(1, asked)
    }
}
