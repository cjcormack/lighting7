package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActiveBankStateTest {

    @Test
    fun `bankFor returns null for unknown device`() {
        val state = ActiveBankState()
        assertNull(state.bankFor("x-touch-compact-standard"))
    }

    @Test
    fun `setBank updates the fast-lookup and active state flow`() {
        val state = ActiveBankState()
        assertTrue(state.setBank("x-touch", "layer-a"))
        assertEquals("layer-a", state.bankFor("x-touch"))
        assertEquals(mapOf("x-touch" to "layer-a"), state.active.value)
    }

    @Test
    fun `setBank returns false when value unchanged`() {
        val state = ActiveBankState()
        state.setBank("x-touch", "layer-a")
        assertFalse(state.setBank("x-touch", "layer-a"))
    }

    @Test
    fun `setBank to null removes entry`() {
        val state = ActiveBankState()
        state.setBank("x-touch", "layer-a")
        state.setBank("x-touch", null)
        assertNull(state.bankFor("x-touch"))
        assertTrue(state.active.value.isEmpty())
    }

    @Test
    fun `clearBank clears the entry`() {
        val state = ActiveBankState()
        state.setBank("x-touch", "layer-a")
        assertTrue(state.clearBank("x-touch"))
        assertNull(state.bankFor("x-touch"))
    }

    @Test
    fun `changes flow emits BankChange on set`() = runBlocking {
        val state = ActiveBankState()
        val change = withTimeout(2000L) {
            kotlinx.coroutines.coroutineScope {
                val deferred = async { state.changes.first() }
                kotlinx.coroutines.delay(50)
                state.setBank("x-touch", "layer-a")
                deferred.await()
            }
        }
        assertEquals("x-touch", change.deviceTypeKey)
        assertNull(change.previousBank)
        assertEquals("layer-a", change.newBank)
    }

    @Test
    fun `changes flow emits transition from one bank to another`() = runBlocking {
        val state = ActiveBankState()
        val collected = withTimeout(2000L) {
            kotlinx.coroutines.coroutineScope {
                val deferred = async { state.changes.take(2).toList() }
                kotlinx.coroutines.delay(50)
                state.setBank("x-touch", "layer-a")
                state.setBank("x-touch", "layer-b")
                deferred.await()
            }
        }
        assertEquals(listOf(null, "layer-a"), collected.map { it.previousBank })
        assertEquals(listOf("layer-a", "layer-b"), collected.map { it.newBank })
    }

    @Test
    fun `clearAll wipes everything`() {
        val state = ActiveBankState()
        state.setBank("a", "1")
        state.setBank("b", "2")
        state.clearAll()
        assertTrue(state.active.value.isEmpty())
        assertNull(state.bankFor("a"))
    }
}
