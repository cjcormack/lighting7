package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Phase 7: unit tests for [BindingHealthEvaluator]. Verifies each [BindingTarget] variant
 * maps onto the expected [AssignmentHealth], and that fixture / group checks delegate to
 * [uk.me.cormack.lighting7.fx.PersistedFixtureReferenceValidator] (covered by that
 * validator's own suite; here we just assert the integration).
 */
class BindingHealthEvaluatorTest {

    private val universe = Universe(0, 0)

    private fun fixturesWithHex(): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", firstChannel = 1))
            val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", firstChannel = 13))
            createGroup<HexFixture>("front-wash") {
                addSpread(listOf(hex1, hex2))
            }
        }
        return fixtures
    }

    private val liveMaster: UUID = UUID.fromString("7d444840-9dc0-11d1-b245-5ffdce74fad2")

    private fun context(
        fixtures: Fixtures = fixturesWithHex(),
        validStackIds: Set<Int> = setOf(1, 2),
        validCueIds: Set<Int> = setOf(10, 20),
        validSpeedMasterUuids: Set<UUID> = setOf(liveMaster),
    ): BindingHealthEvaluator.Context = BindingHealthEvaluator.Context(
        fixtures = fixtures,
        validStackIds = validStackIds,
        validCueIds = validCueIds,
        deviceTypes = ControlSurfaceRegistry.allTypes,
        validSpeedMasterUuids = validSpeedMasterUuids,
    )

    @Test
    fun `Blackout and GrandMasterToggle always resolve to Ok`() {
        val ctx = context()
        assertEquals(AssignmentHealth.Ok, BindingHealthEvaluator.evaluate(BindingTarget.Blackout, ctx))
        assertEquals(AssignmentHealth.Ok, BindingHealthEvaluator.evaluate(BindingTarget.GrandMasterToggle, ctx))
    }

    @Test
    fun `FixtureProperty with live fixture + valid property is Ok`() {
        val health = BindingHealthEvaluator.evaluate(
            BindingTarget.FixtureProperty("hex-1", "dimmer"),
            context(),
        )
        assertEquals(AssignmentHealth.Ok, health)
    }

    @Test
    fun `FixtureProperty with renamed fixture returns MissingFixture`() {
        val health = BindingHealthEvaluator.evaluate(
            BindingTarget.FixtureProperty("hex-renamed", "dimmer"),
            context(),
        )
        val missing = assertIs<AssignmentHealth.MissingFixture>(health)
        assertEquals("hex-renamed", missing.fixtureKey)
    }

    @Test
    fun `GroupProperty with unknown group returns MissingGroup`() {
        val health = BindingHealthEvaluator.evaluate(
            BindingTarget.GroupProperty("vanished", "dimmer"),
            context(),
        )
        val missing = assertIs<AssignmentHealth.MissingGroup>(health)
        assertEquals("vanished", missing.groupName)
    }

    @Test
    fun `GroupProperty with removed property returns MissingProperty`() {
        val health = BindingHealthEvaluator.evaluate(
            BindingTarget.GroupProperty("front-wash", "doesNotExist"),
            context(),
        )
        val missing = assertIs<AssignmentHealth.MissingProperty>(health)
        assertEquals("front-wash", missing.targetKey)
        assertEquals("doesNotExist", missing.propertyName)
    }

    @Test
    fun `CueStackGo Back and Pause resolve against validStackIds`() {
        val ctx = context(validStackIds = setOf(7))
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.CueStackGo(7), ctx),
        )
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.CueStackBack(7), ctx),
        )
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.CueStackPause(7), ctx),
        )
        val dead = BindingHealthEvaluator.evaluate(BindingTarget.CueStackGo(999), ctx)
        assertEquals(AssignmentHealth.MissingStack(999), dead)
    }

    @Test
    fun `FireCue resolves against validCueIds`() {
        val ctx = context(validCueIds = setOf(42))
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.FireCue(42), ctx),
        )
        val dead = BindingHealthEvaluator.evaluate(BindingTarget.FireCue(99), ctx)
        assertEquals(AssignmentHealth.MissingCue(99), dead)
    }

    @Test
    fun `SetBank validates against registered device profiles`() {
        // The X-Touch Compact Standard profile declares layer-a and layer-b banks.
        val ctx = context()
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(
                BindingTarget.SetBank("x-touch-compact-standard", "layer-a"),
                ctx,
            ),
        )
        val unknownBank = BindingHealthEvaluator.evaluate(
            BindingTarget.SetBank("x-touch-compact-standard", "layer-z"),
            ctx,
        )
        assertEquals(
            AssignmentHealth.UnknownBank("x-touch-compact-standard", "layer-z"),
            unknownBank,
        )
        val unknownDevice = BindingHealthEvaluator.evaluate(
            BindingTarget.SetBank("not-a-real-device", "layer-a"),
            ctx,
        )
        assertEquals(
            AssignmentHealth.UnknownBank("not-a-real-device", "layer-a"),
            unknownDevice,
        )
    }

    @Test
    fun `Flash recurses on the inner target — dead inner propagates`() {
        val flashOk = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-1", "dimmer"))
        assertEquals(AssignmentHealth.Ok, BindingHealthEvaluator.evaluate(flashOk, context()))

        val flashDead = BindingTarget.Flash(BindingTarget.FixtureProperty("hex-gone", "dimmer"))
        val health = BindingHealthEvaluator.evaluate(flashDead, context())
        assertIs<AssignmentHealth.MissingFixture>(health)
    }

    @Test
    fun `an unkeyed speed-master binding means master 1 and is always Ok`() {
        val ctx = context(validSpeedMasterUuids = emptySet())
        assertEquals(AssignmentHealth.Ok, BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterBpm(), ctx))
        assertEquals(AssignmentHealth.Ok, BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterTap(), ctx))
    }

    @Test
    fun `a speed-master binding naming a live master is Ok`() {
        val ctx = context()
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterBpm(liveMaster.toString()), ctx),
        )
        assertEquals(
            AssignmentHealth.Ok,
            BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterTap(liveMaster.toString()), ctx),
        )
    }

    /**
     * Unlike an *effect*, which degrades to master 1 when its master vanishes, a binding
     * reports itself dead: silently retuning the global tempo instead of the master the
     * operator picked would be worse than doing nothing visible.
     */
    @Test
    fun `a speed-master binding naming a deleted master is MissingSpeedMaster`() {
        val gone = UUID.randomUUID().toString()
        val health = BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterBpm(gone), context())
        assertEquals(AssignmentHealth.MissingSpeedMaster(gone), health)

        val tapHealth = BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterTap(gone), context())
        assertEquals(AssignmentHealth.MissingSpeedMaster(gone), tapHealth)
    }

    @Test
    fun `a malformed speed-master uuid is reported dead rather than parsed leniently`() {
        val health = BindingHealthEvaluator.evaluate(BindingTarget.SpeedMasterTap("not-a-uuid"), context())
        assertEquals(AssignmentHealth.MissingSpeedMaster("not-a-uuid"), health)
    }
}
