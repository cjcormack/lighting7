package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectWriteStoreTest {

    private val busking = DirectWriteOwner.BUSKING
    private val locate = DirectWriteOwner.LOCATE

    @Test
    fun `put then get returns the stored value`() {
        val store = DirectWriteStore()
        store.put(busking, universe = 0, channel = 42, value = 180u)
        assertEquals(180u.toUByte(), store.get(0, 42))
    }

    @Test
    fun `get returns null for absent channel`() {
        val store = DirectWriteStore()
        assertNull(store.get(0, 42))
    }

    @Test
    fun `different universes do not collide`() {
        val store = DirectWriteStore()
        store.put(busking, universe = 0, channel = 10, value = 100u)
        store.put(busking, universe = 1, channel = 10, value = 200u)
        assertEquals(100u.toUByte(), store.get(0, 10))
        assertEquals(200u.toUByte(), store.get(1, 10))
    }

    @Test
    fun `overwriting put replaces previous value for the same owner`() {
        val store = DirectWriteStore()
        store.put(busking, universe = 0, channel = 5, value = 10u)
        store.put(busking, universe = 0, channel = 5, value = 99u)
        assertEquals(99u.toUByte(), store.get(0, 5))
        assertEquals(1, store.size)
    }

    @Test
    fun `clear removes the value`() {
        val store = DirectWriteStore()
        store.put(busking, universe = 0, channel = 5, value = 42u)
        store.clear(busking, universe = 0, channel = 5)
        assertNull(store.get(0, 5))
    }

    @Test
    fun `clearAll removes everything`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 10u); store.put(busking, 0, 2, 20u); store.put(locate, 1, 1, 30u)
        store.clearAll()
        assertEquals(0, store.size)
        assertNull(store.get(0, 1))
    }

    @Test
    fun `high universe numbers and channel 512 pack correctly`() {
        val store = DirectWriteStore()
        store.put(busking, universe = 32767, channel = 512, value = 255u)
        assertEquals(255u.toUByte(), store.get(32767, 512))
    }

    // --- Ownership stacking ---

    @Test
    fun `most recent owner wins on read`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 102u)
        store.put(locate, 0, 1, 255u)
        assertEquals(255u.toUByte(), store.get(0, 1), "locate wrote last")

        store.put(busking, 0, 1, 40u)
        assertEquals(40u.toUByte(), store.get(0, 1), "a fresh busk write moves busking to the top")
    }

    @Test
    fun `clearing one owner falls back to the surviving owner's value`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 102u)
        store.put(locate, 0, 1, 255u)

        store.clear(locate, 0, 1)
        assertEquals(102u.toUByte(), store.get(0, 1), "busked value survives locate release")
        assertEquals(1, store.size)

        store.clear(busking, 0, 1)
        assertNull(store.get(0, 1))
        assertEquals(0, store.size)
    }

    @Test
    fun `clearing an owner that holds no entry is a no-op`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 102u)
        store.clear(locate, 0, 1)
        assertEquals(102u.toUByte(), store.get(0, 1))
        store.clear(locate, 0, 99)
        assertEquals(1, store.size)
    }

    @Test
    fun `three owners release in any order and fall back by recency`() {
        val store = DirectWriteStore()
        val preset = DirectWriteOwner.preset(7)
        store.put(busking, 0, 1, 10u)
        store.put(preset, 0, 1, 20u)
        store.put(locate, 0, 1, 30u)
        assertEquals(30u.toUByte(), store.get(0, 1))

        // Release the middle owner first: top is untouched.
        store.clear(preset, 0, 1)
        assertEquals(30u.toUByte(), store.get(0, 1))

        store.clear(locate, 0, 1)
        assertEquals(10u.toUByte(), store.get(0, 1), "falls past the released preset to busking")
    }

    @Test
    fun `refreshing a buried owner's value does not lose the others`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 10u)
        store.put(locate, 0, 1, 255u)
        store.put(busking, 0, 1, 60u)   // operator busks while located
        store.clear(busking, 0, 1)
        assertEquals(255u.toUByte(), store.get(0, 1), "locate entry still present underneath")
    }

    @Test
    fun `valueFor reads a buried owner's entry`() {
        val store = DirectWriteStore()
        store.put(busking, 0, 1, 102u)
        store.put(locate, 0, 1, 255u)
        assertEquals(102u.toUByte(), store.valueFor(busking, 0, 1))
        assertEquals(255u.toUByte(), store.valueFor(locate, 0, 1))
        assertNull(store.valueFor(DirectWriteOwner.preset(3), 0, 1))
    }

    @Test
    fun `clearOwner sweeps only that owner's entries across all channels`() {
        val store = DirectWriteStore()
        val preset = DirectWriteOwner.preset(7)
        store.put(preset, 0, 1, 100u)
        store.put(preset, 0, 2, 110u)
        store.put(busking, 0, 1, 40u)   // stacked above the preset on channel 1
        store.put(busking, 0, 3, 50u)

        val swept = store.clearOwner(preset)
        assertEquals(2, swept)
        assertEquals(40u.toUByte(), store.get(0, 1), "busking survives the sweep")
        assertNull(store.get(0, 2), "preset-only channel fully released")
        assertEquals(50u.toUByte(), store.get(0, 3))

        // No stranded preset entry can resurface once busking clears.
        store.clear(busking, 0, 1)
        assertNull(store.get(0, 1))
        assertEquals(0, store.clearOwner(preset), "second sweep finds nothing")
    }

    @Test
    fun `distinct preset owners do not collide`() {
        val store = DirectWriteStore()
        store.put(DirectWriteOwner.preset(1), 0, 1, 100u)
        store.put(DirectWriteOwner.preset(2), 0, 1, 200u)
        store.clear(DirectWriteOwner.preset(1), 0, 1)
        assertEquals(200u.toUByte(), store.get(0, 1), "preset 2's write survives preset 1's release")
    }

    // --- Property-level helpers ---

    @Test
    fun `putProperty fans out to slider channel with 7-bit scaling`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        val writes = store.putProperty(busking, hex, "dimmer", midiValue7Bit = 127u)
        assertEquals(1, writes.size)
        assertEquals(255u.toUByte(), store.get(0, 1))
        assertEquals(255u.toUByte(), writes.single().value)
    }

    @Test
    fun `putProperty for rgbColour writes all three channels`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        val writes = store.putProperty(busking, hex, "rgbColour", midiValue7Bit = 64u)
        assertEquals(3, writes.size)
        assertEquals(writes.map { it.channel }.sorted(), listOf(2, 3, 4))
        // All three channels have the same stored value.
        assertEquals(store.get(0, 2), store.get(0, 3))
        assertEquals(store.get(0, 3), store.get(0, 4))
    }

    @Test
    fun `putProperty for unknown property stores nothing`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        val writes = store.putProperty(busking, hex, "bogus", 50u)
        assertTrue(writes.isEmpty())
        assertEquals(0, store.size)
    }

    @Test
    fun `clearProperty wipes every backing channel for that owner only`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        store.putProperty(busking, hex, "rgbColour", 127u)
        assertEquals(3, store.size)
        val cleared = store.clearProperty(busking, hex, "rgbColour")
        assertEquals(3, cleared.size)
        assertEquals(0, store.size)
    }

    @Test
    fun `flash release restores the fader value underneath`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        store.putProperty(DirectWriteOwner.SURFACE, hex, "dimmer", 64u)
        val faderValue = store.get(0, 1)!!
        store.putProperty(DirectWriteOwner.FLASH, hex, "dimmer", 127u)
        assertEquals(255u.toUByte(), store.get(0, 1), "flash on top")
        store.clearProperty(DirectWriteOwner.FLASH, hex, "dimmer")
        assertEquals(faderValue, store.get(0, 1), "fader level survives flash release")
    }

    @Test
    fun `concurrent writes survive`() {
        val store = DirectWriteStore()
        // Simulate rapid-fire writes from multiple sources. Not a thread-safety proof (that's
        // ConcurrentHashMap's job) — just a smoke check that packing / unpacking doesn't clash.
        repeat(1000) { i ->
            val u = i % 4
            val ch = (i % 512) + 1
            store.put(busking, u, ch, (i % 256).toUByte())
        }
        // Spot-check one: i=7 → u=3, ch=8, v=7.
        assertEquals(7u.toUByte(), store.get(3, 8))
    }
}
