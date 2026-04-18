package uk.me.cormack.lighting7.fx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
