package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CueEditSessionRegistryTest {

    private val projectId = 42
    private val otherProjectId = 43

    private fun session(cueId: Int, mode: CueEditMode = CueEditMode.LIVE, cueStackId: Int? = null) =
        CueEditSessionState(
            cueId = cueId,
            mode = mode,
            snapshot = emptyList(),
            cueStackId = cueStackId,
        )

    /** Race-free collection of the next event: open the subscriber before calling the mutator. */
    private suspend fun nextEvent(
        registry: CueEditSessionRegistry,
        trigger: () -> Unit,
    ): CueEditSessionRegistry.Event = withTimeout(1_000) {
        coroutineScope {
            val deferred = async(start = CoroutineStart.UNDISPATCHED) { registry.events.first() }
            trigger()
            deferred.await()
        }
    }

    @Test
    fun `register then activeSession returns the entry`() {
        val registry = CueEditSessionRegistry()
        val handle = Any()
        val s = session(7)
        registry.register(handle, projectId, s)

        val active = registry.activeSession(projectId)
        assertNotNull(active)
        assertEquals(projectId, active.projectId)
        assertSame(s, active.session)
        assertNull(registry.activeSession(otherProjectId))
    }

    @Test
    fun `unregister removes the entry and reports the prior value`() {
        val registry = CueEditSessionRegistry()
        val handle = Any()
        val s = session(11)
        registry.register(handle, projectId, s)

        val removed = registry.unregister(handle)
        assertEquals(projectId, removed?.projectId)
        assertNull(registry.activeSession(projectId))
        assertNull(registry.unregister(handle))
    }

    @Test
    fun `register emits Started on first registration`() = runBlocking {
        val registry = CueEditSessionRegistry()
        val handle = Any()
        val s = session(5)
        val event = nextEvent(registry) { registry.register(handle, projectId, s) }
        assertTrue(event is CueEditSessionRegistry.Event.Started)
        assertEquals(projectId, event.projectId)
        assertEquals(5, event.session.cueId)
    }

    @Test
    fun `register with different mode emits ModeChanged`() = runBlocking {
        val registry = CueEditSessionRegistry()
        val handle = Any()
        registry.register(handle, projectId, session(1, mode = CueEditMode.LIVE))

        val event = nextEvent(registry) {
            registry.register(handle, projectId, session(1, mode = CueEditMode.BLIND))
        }
        assertTrue(event is CueEditSessionRegistry.Event.ModeChanged)
    }

    @Test
    fun `unregister emits Ended`() = runBlocking {
        val registry = CueEditSessionRegistry()
        val handle = Any()
        registry.register(handle, projectId, session(9))

        val event = nextEvent(registry) { registry.unregister(handle) }
        assertTrue(event is CueEditSessionRegistry.Event.Ended)
        assertEquals(9, event.cueId)
    }

    @Test
    fun `notifyAssignmentChanged fires AssignmentChanged event`() = runBlocking {
        val registry = CueEditSessionRegistry()
        val event = nextEvent(registry) {
            registry.notifyAssignmentChanged(projectId, 12, "fixture", "hex-1", "dimmer", "200")
        }
        assertTrue(event is CueEditSessionRegistry.Event.AssignmentChanged)
        assertEquals("hex-1", event.targetKey)
        assertEquals("200", event.value)
    }

    @Test
    fun `notifyAssignmentsReloaded carries the full list`() = runBlocking {
        val registry = CueEditSessionRegistry()
        val list = listOf(
            CuePropertyAssignmentDto(
                targetType = "fixture", targetKey = "hex-1", propertyName = "dimmer", value = "150",
            )
        )
        val event = nextEvent(registry) {
            registry.notifyAssignmentsReloaded(projectId, 1, list)
        }
        assertTrue(event is CueEditSessionRegistry.Event.AssignmentsReloaded)
        assertEquals(list, event.assignments)
    }
}
