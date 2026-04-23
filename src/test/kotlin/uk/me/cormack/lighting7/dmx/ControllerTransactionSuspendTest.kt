package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the [ControllerTransaction.applySuspend] contract: commit every pending change
 * across every universe, and return the same per-universe value map the blocking
 * [ControllerTransaction.apply] would return.
 */
class ControllerTransactionSuspendTest {

    @Test
    fun `applySuspend commits pending changes on every universe`() = runBlocking {
        val u0 = Universe(0, 0)
        val u1 = Universe(0, 1)
        val c0 = MockDmxController(u0)
        val c1 = MockDmxController(u1)

        val tx = ControllerTransaction(listOf(c0, c1))
        tx.setValue(u0, 1, 100u)
        tx.setValue(u0, 2, 200u)
        tx.setValue(u1, 1, 50u)

        val result = tx.applySuspend()

        assertEquals(100u.toUByte(), c0.getValue(1))
        assertEquals(200u.toUByte(), c0.getValue(2))
        assertEquals(50u.toUByte(), c1.getValue(1))
        assertEquals(mapOf(1 to 100u.toUByte(), 2 to 200u.toUByte()), result[u0])
        assertEquals(mapOf(1 to 50u.toUByte()), result[u1])
    }

    @Test
    fun `applySuspend with no pending writes still returns maps`() = runBlocking {
        val u = Universe(0, 0)
        val c = MockDmxController(u)
        val tx = ControllerTransaction(listOf(c))

        val result = tx.applySuspend()

        assertTrue(result.containsKey(u))
        assertTrue(result.getValue(u).isEmpty())
        assertTrue(c.writeLog.isEmpty())
    }
}
