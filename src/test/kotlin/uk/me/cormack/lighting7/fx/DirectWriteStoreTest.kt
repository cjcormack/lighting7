package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DirectWriteStoreTest {

    @Test
    fun `put then get returns the stored value`() {
        val store = DirectWriteStore()
        store.put(universe = 0, channel = 42, value = 180u)
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
        store.put(universe = 0, channel = 10, value = 100u)
        store.put(universe = 1, channel = 10, value = 200u)
        assertEquals(100u.toUByte(), store.get(0, 10))
        assertEquals(200u.toUByte(), store.get(1, 10))
    }

    @Test
    fun `overwriting put replaces previous value`() {
        val store = DirectWriteStore()
        store.put(universe = 0, channel = 5, value = 10u)
        store.put(universe = 0, channel = 5, value = 99u)
        assertEquals(99u.toUByte(), store.get(0, 5))
    }

    @Test
    fun `clear removes the value`() {
        val store = DirectWriteStore()
        store.put(universe = 0, channel = 5, value = 42u)
        store.clear(universe = 0, channel = 5)
        assertNull(store.get(0, 5))
    }

    @Test
    fun `clearAll removes everything`() {
        val store = DirectWriteStore()
        store.put(0, 1, 10u); store.put(0, 2, 20u); store.put(1, 1, 30u)
        store.clearAll()
        assertEquals(0, store.size)
        assertNull(store.get(0, 1))
    }

    @Test
    fun `high universe numbers and channel 512 pack correctly`() {
        val store = DirectWriteStore()
        store.put(universe = 32767, channel = 512, value = 255u)
        assertEquals(255u.toUByte(), store.get(32767, 512))
    }

    @Test
    fun `putProperty fans out to slider channel with 7-bit scaling`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        val writes = store.putProperty(hex, "dimmer", midiValue7Bit = 127u)
        assertEquals(1, writes.size)
        assertEquals(255u.toUByte(), store.get(0, 1))
        assertEquals(255u.toUByte(), writes.single().value)
    }

    @Test
    fun `putProperty for rgbColour writes all three channels`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        val writes = store.putProperty(hex, "rgbColour", midiValue7Bit = 64u)
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
        val writes = store.putProperty(hex, "bogus", 50u)
        assertTrue(writes.isEmpty())
        assertEquals(0, store.size)
    }

    @Test
    fun `clearProperty wipes every backing channel`() {
        val store = DirectWriteStore()
        val hex = HexFixture(Universe(0, 0), "hex-1", "Hex 1", firstChannel = 1)
        store.putProperty(hex, "rgbColour", 127u)
        assertEquals(3, store.size)
        val cleared = store.clearProperty(hex, "rgbColour")
        assertEquals(3, cleared.size)
        assertEquals(0, store.size)
    }

    @Test
    fun `concurrent writes survive`() {
        val store = DirectWriteStore()
        // Simulate rapid-fire writes from multiple sources. Not a thread-safety proof (that's
        // ConcurrentHashMap's job) — just a smoke check that packing / unpacking doesn't clash.
        repeat(1000) { i ->
            val u = i % 4
            val ch = (i % 512) + 1
            store.put(u, ch, (i % 256).toUByte())
        }
        // Spot-check one: i=7 → u=3, ch=8, v=7.
        assertEquals(7u.toUByte(), store.get(3, 8))
    }
}
