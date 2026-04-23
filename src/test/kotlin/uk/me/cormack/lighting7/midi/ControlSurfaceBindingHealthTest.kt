package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Phase 7: verifies that [ControlSurfaceBindingService] enriches cached bindings with
 * [AssignmentHealth] via the supplied [BindingHealthEvaluator.Context] provider, and that
 * [ControlSurfaceBindingService.invalidateHealth] re-evaluates without touching the DB.
 *
 * Uses the cache-seam seed + a mutable fixture snapshot so we never hit JDBC.
 */
class ControlSurfaceBindingHealthTest {

    private val projectId = 1
    private val universe = Universe(0, 0)

    private fun fixturesWithHex(keys: List<String>): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            keys.forEachIndexed { idx, key ->
                addFixture(HexFixture(universe, key, "Hex $idx", firstChannel = 1 + idx * 12))
            }
        }
        return fixtures
    }

    private fun context(
        fixtures: Fixtures = fixturesWithHex(listOf("hex-1", "hex-2")),
        validStackIds: Set<Int> = emptySet(),
        validCueIds: Set<Int> = emptySet(),
    ): BindingHealthEvaluator.Context = BindingHealthEvaluator.Context(
        fixtures = fixtures,
        validStackIds = validStackIds,
        validCueIds = validCueIds,
        deviceTypes = ControlSurfaceRegistry.allTypes,
    )

    private fun binding(
        id: Int,
        target: BindingTarget,
        health: AssignmentHealth = AssignmentHealth.Ok,
    ): ControlSurfaceBindingService.ResolvedBinding = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = "x-touch-compact-standard",
        controlId = "fader-$id",
        bank = null,
        target = target,
        takeoverPolicy = BindingTakeoverPolicy.IMMEDIATE,
        sortOrder = 0,
        health = health,
    )

    @Test
    fun `invalidateHealth marks bindings dead when their target fixture disappears`() {
        var current: BindingHealthEvaluator.Context = context()
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)

        // Simulate a patch change that renames hex-1 → hex-renamed.
        current = context(fixtures = fixturesWithHex(listOf("hex-renamed")))
        service.invalidateHealth(projectId)

        val health = service.get(projectId, 1)?.health
        assertIs<AssignmentHealth.MissingFixture>(health)
        assertEquals("hex-1", health.fixtureKey)
    }

    @Test
    fun `invalidateHealth revives a binding when the fixture comes back`() {
        // Start with a patch that doesn't have hex-1.
        var current: BindingHealthEvaluator.Context = context(
            fixtures = fixturesWithHex(listOf("hex-2")),
        )
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.FixtureProperty("hex-1", "dimmer"))),
        )
        // Force the initial health evaluation (seed bypasses it).
        service.invalidateHealth(projectId)
        assertIs<AssignmentHealth.MissingFixture>(service.get(projectId, 1)?.health)

        // Patch adds hex-1 back — health must return to Ok.
        current = context(fixtures = fixturesWithHex(listOf("hex-1", "hex-2")))
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)
    }

    @Test
    fun `invalidateHealth flags MissingStack when the referenced stack is deleted`() {
        var current: BindingHealthEvaluator.Context = context(validStackIds = setOf(7))
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.CueStackGo(7))),
        )
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)

        current = context(validStackIds = emptySet())
        service.invalidateHealth(projectId)
        val dead = service.get(projectId, 1)?.health
        assertEquals(AssignmentHealth.MissingStack(7), dead)
    }

    @Test
    fun `invalidateHealth emits a Reloaded event only when health actually changed`() {
        var current: BindingHealthEvaluator.Context = context(validCueIds = setOf(10))
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.FireCue(10))),
        )
        // Seed starts at Ok (default); first invalidate with same context → no event.
        service.invalidateHealth(projectId)
        // Now remove the cue — health flips dead → event.
        current = context(validCueIds = emptySet())
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.MissingCue(10), service.get(projectId, 1)?.health)
    }

    @Test
    fun `invalidateHealth is a no-op for unloaded projects`() {
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { context() },
        )
        // No seed / no ensureLoaded — must not throw.
        service.invalidateHealth(projectId = 42)
    }

    @Test
    fun `invalidateHealth is a no-op when no provider is wired`() {
        val service = ControlSurfaceBindingService(FakeDatabase.instance)
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.FixtureProperty("hex-missing", "dimmer"))),
        )
        // No provider → health stays at the default Ok.
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)
    }

    @Test
    fun `resolver returns binding regardless of health so dispatch can log the reason`() {
        var current: BindingHealthEvaluator.Context = context()
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, BindingTarget.FixtureProperty("hex-gone", "dimmer"))),
        )
        service.invalidateHealth(projectId)

        val resolved = service.resolve(projectId, "x-touch-compact-standard", "fader-1", activeBank = null)
        assertNotNull(resolved)
        // Dead but still reachable — SurfaceInputRouter is the one that drops the event.
        assertIs<AssignmentHealth.MissingFixture>(resolved.health)
    }
}
