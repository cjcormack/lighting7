package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.China2CellLedBlinderFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.SubselectMode
import java.util.UUID
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
    private val stack = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val cue = UUID.fromString("22222222-2222-4222-8222-222222222222")

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
        validStackUuids: Set<UUID> = emptySet(),
        validCueUuids: Set<UUID> = emptySet(),
        connectedWindowNames: Set<String> = emptySet(),
    ): BindingHealthEvaluator.Context = BindingHealthEvaluator.Context(
        fixtures = fixtures,
        validStackUuids = validStackUuids,
        validCueUuids = validCueUuids,
        deviceTypes = ControlSurfaceRegistry.allTypes,
        connectedWindowNames = connectedWindowNames,
    )

    /**
     * The window-addressed targets (busk-further plan D14): dead while no connected window carries
     * the name, alive the moment one does — through the same `invalidateHealth` the publisher's
     * registry collector fires, so a binding made before its screen was opened recovers.
     */
    @Test
    fun `a window-addressed binding is missingWindow until a window of that name connects`() {
        var current: BindingHealthEvaluator.Context = context()
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(
                binding(1, Binding(Does.WindowFocus("Screen 2", "rig"))),
                binding(2, Binding(Does.WindowSheet("Screen 2"))),
                binding(3, Binding(Does.SelectionStep(SelectionMove.NEXT))),
                binding(4, Binding(Does.SelectionStep(SelectionMove.of(SubselectMode.EVEN)))),
            ),
        )
        // The seed installs rows as given; the first evaluation is the one a load or a registry
        // change runs.
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.MissingWindow("Screen 2"), service.get(projectId, 1)?.health)
        assertEquals(AssignmentHealth.MissingWindow("Screen 2"), service.get(projectId, 2)?.health)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 3)?.health, "selection stepping needs no window")
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 4)?.health)

        // Screen 2 announces.
        current = context(connectedWindowNames = setOf("Screen 1", "Screen 2"))
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 2)?.health)

        // …and closes again.
        current = context(connectedWindowNames = setOf("Screen 1"))
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.MissingWindow("Screen 2"), service.get(projectId, 1)?.health)
    }

    @Test
    fun `a binding on the blinder's colour is healthy, so the router dispatches it`() {
        val fixtures = Fixtures()
        fixtures.register { addFixture(China2CellLedBlinderFixture(universe, "blinder", "Blinder", firstChannel = 201)) }
        val service = ControlSurfaceBindingService(database = FakeDatabase.instance, healthContextProvider = { context(fixtures) })
        service.seedCacheForTest(projectId, listOf(binding(1, Binding(Does.Level("rgbColour", ColourAxis.BRIGHTNESS), Subject.Fixture("blinder")))))
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)
    }

    private fun binding(
        id: Int,
        binding: Binding,
        health: AssignmentHealth = AssignmentHealth.Ok,
    ): ControlSurfaceBindingService.ResolvedBinding = ControlSurfaceBindingService.ResolvedBinding(
        id = id,
        projectId = projectId,
        deviceTypeKey = "x-touch-compact-standard",
        controlId = "fader-$id",
        bank = null,
        binding = binding,
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
            listOf(binding(1, Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))),
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
            listOf(binding(1, Binding(Does.Level("dimmer"), Subject.Fixture("hex-1")))),
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
        var current: BindingHealthEvaluator.Context = context(validStackUuids = setOf(stack))
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, Binding(Does.Go, Subject.Record(RecordKind.STACK, stack)))),
        )
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.Ok, service.get(projectId, 1)?.health)

        current = context(validStackUuids = emptySet())
        service.invalidateHealth(projectId)
        val dead = service.get(projectId, 1)?.health
        assertEquals(AssignmentHealth.MissingStack(stackUuid = stack.toString()), dead)
    }

    @Test
    fun `invalidateHealth emits a Reloaded event only when health actually changed`() {
        var current: BindingHealthEvaluator.Context = context(validCueUuids = setOf(cue))
        val service = ControlSurfaceBindingService(
            database = FakeDatabase.instance,
            healthContextProvider = { current },
        )
        service.seedCacheForTest(
            projectId,
            listOf(binding(1, Binding(Does.Cue, Subject.Record(RecordKind.CUE, cue)))),
        )
        // Seed starts at Ok (default); first invalidate with same context → no event.
        service.invalidateHealth(projectId)
        // Now remove the cue — health flips dead → event.
        current = context(validCueUuids = emptySet())
        service.invalidateHealth(projectId)
        assertEquals(AssignmentHealth.MissingCue(cueUuid = cue.toString()), service.get(projectId, 1)?.health)
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
            listOf(binding(1, Binding(Does.Level("dimmer"), Subject.Fixture("hex-missing")))),
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
            listOf(binding(1, Binding(Does.Level("dimmer"), Subject.Fixture("hex-gone")))),
        )
        service.invalidateHealth(projectId)

        val resolved = service.resolve(projectId, "x-touch-compact-standard", "fader-1", activeBank = null, encoderBank = EncoderBankSelection("dimmer"))
        assertNotNull(resolved)
        // Dead but still reachable — SurfaceInputRouter is the one that drops the event.
        assertIs<AssignmentHealth.MissingFixture>(resolved.health)
    }
}
