package uk.me.cormack.lighting7.plugins

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SocketMessageScopeTest {

    // A stale compile, simulated: `Stale` is what the root's serializer looked like before
    // `Added` was declared — same serial names, one leaf short — and `Current` is the hierarchy
    // as the classes now declare it.
    @Serializable
    sealed class Current {
        @Serializable
        sealed class Domain : Current()

        @Serializable
        @SerialName("kept")
        data object Kept : Domain()

        @Serializable
        @SerialName("added")
        data class Added(val value: Int) : Domain()

        // Not a frame at all: a leaf nobody made serializable is reported, not thrown.
        data object Bare : Domain()
    }

    @Serializable
    sealed class Stale {
        @Serializable
        @SerialName("kept")
        data object Kept : Stale()
    }

    @Test
    fun `every InMessage and OutMessage leaf is in its root's polymorphic scope`() {
        assertEquals(emptyList(), SocketMessageScope.unregisteredLeaves(OutMessage::class, OutMessage.serializer()))
        assertEquals(emptyList(), SocketMessageScope.unregisteredLeaves(InMessage::class, InMessage.serializer()))
        SocketMessageScope.verify()
    }

    @Test
    fun `a leaf the root's serializer does not know is named, however deep it sits`() {
        val missing = SocketMessageScope.unregisteredLeaves(Current::class, Stale.serializer())

        assertEquals(2, missing.size, "$missing")
        assertTrue(missing.any { "Current.Added" in it && "'added'" in it }, "$missing")
        assertTrue(missing.any { "Current.Bare" in it && "not @Serializable" in it }, "$missing")
    }

    @Test
    fun `the same hierarchy against its own serializer is clean but for the unserializable leaf`() {
        val missing = SocketMessageScope.unregisteredLeaves(Current::class, Current.serializer())

        assertEquals(1, missing.size, "$missing")
        assertTrue("Current.Bare" in missing.single(), "$missing")
    }
}
