package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for the in-memory resolver inside [ControlSurfaceBindingService]. Uses the
 * [ControlSurfaceBindingService.seedCacheForTest] seam to populate the cache directly
 * without requiring a database.
 */
class ControlSurfaceBindingResolverTest {

    private fun service(): ControlSurfaceBindingService =
        ControlSurfaceBindingService(database = FakeDatabase.instance)

    private fun resolved(
        id: Int,
        deviceTypeKey: String = "x-touch-compact-standard",
        controlId: String = "fader-1",
        bank: String? = null,
        target: BindingTarget = BindingTarget.FixtureProperty("hex-1", "dimmer"),
        projectId: Int = 1,
    ) = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = deviceTypeKey,
        controlId = controlId,
        bank = bank,
        target = target,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
    )

    @Test
    fun `resolve returns null when nothing is cached`() {
        val svc = service()
        svc.seedCacheForTest(projectId = 1, bindings = emptyList())
        assertNull(svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = null))
    }

    @Test
    fun `bank-specific binding wins over bank-agnostic when active bank matches`() {
        val svc = service()
        val global = resolved(id = 1, bank = null)
        val bankA = resolved(id = 2, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(global, bankA))

        val hit = svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-a")
        assertNotNull(hit)
        assertEquals(2, hit.id)
    }

    @Test
    fun `bank-agnostic binding is returned when no bank-specific match exists`() {
        val svc = service()
        val global = resolved(id = 1, bank = null)
        val bankB = resolved(id = 2, bank = "layer-b")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(global, bankB))

        val hit = svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-a")
        assertNotNull(hit)
        assertEquals(1, hit.id)
    }

    @Test
    fun `bank-scoped binding does not resolve when the other bank is active`() {
        val svc = service()
        val bankA = resolved(id = 1, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(bankA))
        assertNull(svc.resolve(1, "x-touch-compact-standard", "fader-1", activeBank = "layer-b"))
    }

    @Test
    fun `list returns bindings in insertion order`() {
        val svc = service()
        val first = resolved(id = 3, bank = null)
        val second = resolved(id = 7, bank = "layer-a")
        svc.seedCacheForTest(projectId = 1, bindings = listOf(first, second))

        val list = svc.list(1)
        assertEquals(listOf(3, 7), list.map { it.id })
    }

    @Test
    fun `get returns null for unknown id and the ResolvedBinding for known id`() {
        val svc = service()
        svc.seedCacheForTest(projectId = 1, bindings = listOf(resolved(id = 5)))
        assertNull(svc.get(projectId = 1, bindingId = 99))
        assertNotNull(svc.get(projectId = 1, bindingId = 5))
    }
}
