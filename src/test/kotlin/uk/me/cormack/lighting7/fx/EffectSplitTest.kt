package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.EffectTargetRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The split's engine half (apply-model plan §3.4): an effect spread over several heads rewritten as
 * one instance per head ([FxEngine.headCopies], swapped in by [FxEngine.replaceEffects]) goes on
 * painting **exactly** what it painted, tick for tick — each copy is the member it was. Checked the
 * strong way: the same effect on two rigs, one split mid-run, every channel compared on every tick.
 * The step-timed and ping-pong cases are the cycle and sweep Chris asked the split to carry.
 */
class EffectSplitTest {

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val engine: FxEngine)

    private val pars = (0 until 4).map { "par-$it" }
    private val groupMembers = (0 until 4).map { "g-$it" }

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            for ((i, key) in pars.withIndex()) addFixture(HexFixture(universe, key, key, 1 + i * 12))
            val members = groupMembers.mapIndexed { i, key -> addFixture(HexFixture(universe, key, key, 97 + i * 12)) }
            createGroup<HexFixture>("four") { addSpread(members) }
            val bar = addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar", "Bar", 200))
            // A hex first, so a colour effect takes the members' branch and paints nothing on the bar.
            createGroup<GroupableFixture>("mixed") { addSpread(listOf(members[0], bar, members[1])) }
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, engine)
    }

    /** Paints its phase, never 0. */
    private class PhaseSlider : Effect {
        override val name = "Phase Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: EffectContext): FxOutput =
            FxOutput.Slider((10 + (phase * 200).toInt()).toUByte())
    }

    /** Paints where it is in the spread, so a lost context shows: its slot count and its member index. */
    private class ContextSlider : Effect {
        override val name = "Context Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: EffectContext): FxOutput =
            FxOutput.Slider((context.numDistinctSlots * 20 + context.memberIndex * 3 + (phase * 10).toInt()).toUByte())
    }

    /** Full level, whatever the phase. */
    private class Full : Effect {
        override val name = "Full"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: EffectContext): FxOutput = FxOutput.Slider(255u)
    }

    /** A colour that moves with the phase. */
    private class PhaseColour : Effect {
        override val name = "Phase Colour"
        override val outputType = FxOutputType.COLOUR
        override fun calculate(phase: Double, context: EffectContext): FxOutput =
            FxOutput.Colour(ExtendedColour(java.awt.Color((phase * 255).toInt(), 40, 255 - (phase * 255).toInt())))
    }

    private fun dimmerOver(target: FxTargetRef, effect: Effect = PhaseSlider(), configure: FxInstance.() -> Unit = {}) =
        FxInstance(effect, SliderTarget(target, "dimmer"), FxTiming(beatDivision = 1.0), BlendMode.OVERRIDE).apply(configure)

    private val eight = FxTargetRef.SelectionRef(
        EffectTargetRef.Selection(pars.map { TargetRef.Fixture(it) } + TargetRef.Group("four")),
    )

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    private fun Rig.frame(): List<Int> = (1..250).map { controller.getValue(it).toInt() }

    /**
     * Run [make]'s effect on two rigs for [ticks] ticks, splitting it into its heads on the second at
     * [splitAt], and assert every channel matches on every tick. Answers the copies.
     */
    private fun assertSplitPaintsTheSame(
        ticks: Long = 120,
        splitAt: Long = 17,
        after: () -> List<FxInstance> = { emptyList() },
        make: () -> FxInstance,
    ): List<FxInstance> {
        val whole = newRig()
        val split = newRig()
        whole.engine.addEffect(make())
        after().forEach { whole.engine.addEffect(it) }
        val parent = make()
        split.engine.addEffect(parent)
        after().forEach { split.engine.addEffect(it) }
        var copies = emptyList<FxInstance>()
        for (n in 0 until ticks) {
            if (n == splitAt) {
                copies = split.engine.headCopies(parent)
                assertEquals(setOf(parent.id), split.engine.replaceEffects(mapOf(parent to copies)))
            }
            whole.engine.processBeatTick(tick(n))
            split.engine.processBeatTick(tick(n))
            assertEquals(whole.frame(), split.frame(), "tick $n")
        }
        return copies
    }

    @Test
    fun `split into its heads, a spread effect paints exactly what it painted`() {
        val copies = assertSplitPaintsTheSame { dimmerOver(eight) }
        assertEquals(pars + groupMembers, copies.map { it.target.targetKey })
        assertTrue(copies.all { it.target.targetRef is FxTargetRef.FixtureRef }, "one fixture each")
        assertEquals(listOf(0.0, 1.0 / 8, 2.0 / 8, 3.0 / 8), copies.take(4).map { assertNotNull(it.splitMember).offset })
    }

    @Test
    fun `a step-timed chase keeps its set's cycle when split`() {
        val copies = assertSplitPaintsTheSame(ticks = 240) {
            dimmerOver(FxTargetRef.GroupRef("four")) { stepTiming = true }
        }
        assertEquals(List(4) { 4 }, copies.map { assertNotNull(it.splitMember).groupSize })
    }

    @Test
    fun `a ping-pong keeps its sweep when split`() {
        assertSplitPaintsTheSame(ticks = 240) {
            dimmerOver(eight) { distributionStrategy = DistributionStrategy.PING_PONG }
        }
    }

    @Test
    fun `a step-timed ping-pong under blocks keeps its cycle and sweep together`() {
        assertSplitPaintsTheSame(ticks = 240) {
            dimmerOver(eight) {
                distributionStrategy = DistributionStrategy.PING_PONG
                stepTiming = true
                updateDynamics { it.copy(blocks = 2) }
            }
        }
    }

    @Test
    fun `an N-shot runs its count out on each head as the member did, bouncing the same way`() {
        assertSplitPaintsTheSame(ticks = 160, splitAt = 30) {
            dimmerOver(eight) { updateDynamics { it.copy(nShot = 2, direction = EffectDirection.BOUNCE) } }
        }
    }

    @Test
    fun `a split copy hands the effect its member's context`() {
        assertSplitPaintsTheSame { dimmerOver(eight, ContextSlider()) { distributionStrategy = DistributionStrategy.CENTER_OUT } }
    }

    @Test
    fun `a fixture's cells split into one copy per cell, per fixture or flat`() {
        for (mode in ElementMode.entries) {
            val copies = assertSplitPaintsTheSame {
                FxInstance(PhaseColour(), ColourTarget(FxTargetRef.fixture("bar")), FxTiming(beatDivision = 1.0)).apply {
                    elementMode = mode
                }
            }
            assertEquals((0 until 12).map { "bar.pixel-$it" }, copies.map { it.target.targetKey }, "$mode")
        }
    }

    @Test
    fun `an element filter's left-out cells paint nothing, so no copy is made for them`() {
        val copies = assertSplitPaintsTheSame {
            FxInstance(PhaseColour(), ColourTarget(FxTargetRef.fixture("bar")), FxTiming(beatDivision = 1.0)).apply {
                elementFilter = ElementFilter.ODD
            }
        }
        assertEquals((0 until 12 step 2).map { "bar.pixel-$it" }, copies.map { it.target.targetKey })
        assertTrue(copies.all { it.dynamics.elementFilter == ElementFilter.ALL }, "a copy is one cell")
    }

    @Test
    fun `a copy keeps the parent's phase offset, and what stores it folds the member's offset in`() {
        val rig = newRig()
        val parent = dimmerOver(eight) { phaseOffset = 0.5 }
        rig.engine.addEffect(parent)
        val copies = rig.engine.headCopies(parent)

        assertTrue(copies.all { it.phaseOffset == 0.5 }, "one phase offset, so one edit reaches every copy alike")
        val third = copies[2]
        assertEquals(0.5 - 2.0 / 8, third.storedPhaseOffset(), 1e-12)
        assertEquals(0.5 - 2.0 / 8, third.lookEffectSettings().phaseOffset, 1e-12)
        assertEquals(0.5, parent.storedPhaseOffset(), "an effect that is not a copy stores its own")
    }

    @Test
    fun `a copy takes the parent's clocks, masters and band`() {
        val rig = newRig()
        val master = java.util.UUID.randomUUID()
        val parent = dimmerOver(eight) {
            priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
            accumulatedScaledMs = 1234.5
            nShotStartTick = 48
            speedMasterUuid = master
            intensityMultiplier = 0.25
            registrationId = "PhaseSlider"
        }
        rig.engine.addEffect(parent)
        for (copy in rig.engine.headCopies(parent)) {
            assertEquals(1234.5, copy.accumulatedScaledMs)
            assertEquals(48, copy.nShotStartTick)
            assertEquals(master, copy.speedMasterUuid)
            assertEquals(0.25, copy.intensityMultiplier)
            assertEquals(parent.startedAtMs, copy.startedAtMs)
            assertEquals(parent.priority, copy.priority, "its place among the Local FX too")
            assertEquals("PhaseSlider", copy.registrationId)
            assertTrue(copy.isLocalEffect)
        }
    }

    @Test
    fun `heads named in drop get no copy`() {
        val rig = newRig()
        val parent = dimmerOver(eight)
        rig.engine.addEffect(parent)
        val copies = rig.engine.headCopies(parent, drop = setOf("par-1", "g-2"))
        assertEquals(listOf("par-0", "par-2", "par-3", "g-0", "g-1", "g-3"), copies.map { it.target.targetKey })
    }

    @Test
    fun `an effect another request stopped first is not brought back by its copies`() {
        val rig = newRig()
        val parent = dimmerOver(eight)
        rig.engine.addEffect(parent)
        val copies = rig.engine.headCopies(parent)
        rig.engine.removeEffect(parent.id)

        assertEquals(emptySet(), rig.engine.replaceEffects(mapOf(parent to copies)))
        assertTrue(rig.engine.getActiveEffects().isEmpty())
    }

    @Test
    fun `a copy survives an edit's swap still the member it was`() {
        val rig = newRig()
        val parent = dimmerOver(eight)
        rig.engine.addEffect(parent)
        val copies = rig.engine.headCopies(parent)
        rig.engine.replaceEffects(mapOf(parent to copies))

        val swapped = assertNotNull(rig.engine.updateEffect(copies[3].id, newBlendMode = BlendMode.MAX))
        assertEquals(copies[3].splitMember, swapped.splitMember)
        assertNull(parent.splitMember)
    }

    @Test
    fun `copies keep the parent's place in the order, under an effect started after it`() {
        // A full-level dimmer on par-3 started after the chase, at the same priority: it wins par-3
        // before the split, and must still win it after.
        val copies = assertSplitPaintsTheSame(after = { listOf(dimmerOver(FxTargetRef.fixture("par-3"), Full())) }) {
            dimmerOver(eight)
        }
        assertTrue(copies.isNotEmpty())
    }

    @Test
    fun `a heterogeneous group's member that paints nothing gets no copy, and stays dark`() {
        val copies = assertSplitPaintsTheSame {
            FxInstance(PhaseColour(), ColourTarget(FxTargetRef.GroupRef("mixed")), FxTiming(beatDivision = 1.0))
        }
        assertEquals(listOf("g-0", "g-1"), copies.map { it.target.targetKey })
    }

    @Test
    fun `a pause that reaches the parent after its copies were built is kept`() {
        val rig = newRig()
        val parent = dimmerOver(eight)
        rig.engine.addEffect(parent)
        val copies = rig.engine.headCopies(parent)
        rig.engine.pauseEffect(parent.id)

        rig.engine.replaceEffects(mapOf(parent to copies))
        assertTrue(copies.none { it.isRunning })
    }

    @Test
    fun `an edit to a split effect's id finds nothing and adds nothing`() {
        val rig = newRig()
        val parent = dimmerOver(eight)
        rig.engine.addEffect(parent)
        rig.engine.replaceEffects(mapOf(parent to rig.engine.headCopies(parent)))

        assertNull(rig.engine.updateEffect(parent.id, newBlendMode = BlendMode.MAX))
        assertNull(rig.engine.getEffect(parent.id))
        assertEquals(8, rig.engine.getActiveEffects().size)
    }

    @Test
    fun `a wall-clock copy computes its phase exactly as its member did`() {
        val rig = newRig()
        val parent = dimmerOver(eight) {
            timingSource = TimingSource.WALL_CLOCK
            distributionStrategy = DistributionStrategy.PING_PONG
            stepTiming = true
            updateDynamics { it.copy(nShot = 3, direction = EffectDirection.BOUNCE) }
        }
        rig.engine.addEffect(parent)
        val heads = rig.engine.paintedHeads(parent)
        val copies = rig.engine.headCopies(parent)
        for (elapsed in listOf(0.0, 137.0, 999.0, 2_500.0, 7_777.0, 12_345.0, 30_000.0)) {
            parent.accumulatedScaledMs = elapsed
            copies.forEach { it.accumulatedScaledMs = elapsed }
            for ((head, copy) in heads.zip(copies)) {
                val member = parent.calculateWallClockPhaseForMember(head.groupSize, parent.dynamics, head.offset)
                val own = copy.calculateWallClockPhase(copy.dynamics)
                assertTrue(member == own || (member.isNaN() && own.isNaN()), "${head.key} at $elapsed: $member vs $own")
            }
        }
    }
}
