package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.models.Timing
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.testsupport.SineSlider
import uk.me.cormack.lighting7.testsupport.WindowedSlider
import kotlin.test.Test
import kotlin.test.assertEquals

class FxInstanceTest {

    /** Use a real SliderTarget — the target type doesn't affect phase calculation. */
    private val stubTarget = SliderTarget(FxTargetRef.FixtureRef("test"), "dimmer")

    private fun memberInfo(index: Int, groupSize: Int) = object : DistributionMemberInfo {
        override val index = index
        override val normalizedPosition = if (groupSize <= 1) 0.0 else index.toDouble() / (groupSize - 1)
    }

    /**
     * What the engine's member loop does per member: snapshot the dynamics, derive the
     * distribution offset from that snapshot, and hand both to the phase calculation.
     */
    private fun FxInstance.phaseForMember(
        tick: MasterClock.ClockTick,
        memberInfo: DistributionMemberInfo,
        groupSize: Int,
    ): Double {
        val dyn = dynamics
        val offset = dyn.order.calculateOffset(memberInfo, groupSize)
        return calculatePhaseForMember(tick, groupSize, dyn, offset)
    }

    /**
     * Build a ClockTick at a specific tick number.
     * No actual clock running needed — we just need the tick for phaseForDivision.
     */
    private fun tick(tickNumber: Long) = MasterClock.ClockTick(
        tickNumber = tickNumber,
        beatNumber = tickNumber / MasterClock.TICKS_PER_BEAT,
        tickInBeat = (tickNumber % MasterClock.TICKS_PER_BEAT).toInt(),
        phase = (tickNumber % MasterClock.TICKS_PER_BEAT).toDouble() / MasterClock.TICKS_PER_BEAT,
        timestampMs = 0L,
    )

    @Test
    fun `timing false - beat division is total cycle time`() {
        val instance = FxInstance(
            effect = SineSlider(),
            target = stubTarget,
            timing = FxTiming(cycle = CycleLength.ONE_BEAT),
        )
        instance.updateDynamics { it.copy(timing = Timing.CYCLE) }
        instance.order = SpreadOrder.NORMAL

        val groupSize = 4
        // One beat = 24 ticks. With timing=false, one full cycle = 24 ticks.
        val phaseAt0 = instance.phaseForMember(tick(0), memberInfo(0, groupSize), groupSize)
        val phaseAt12 = instance.phaseForMember(tick(12), memberInfo(0, groupSize), groupSize)

        // At tick 12 of 24-tick cycle, base phase should be 0.5
        // Member 0 with NORMAL has offset 0, so phase ≈ 0.5
        assertEquals(0.5, phaseAt12, 0.01)
        assertEquals(0.0, phaseAt0, 0.01)
    }

    @Test
    fun `timing true - beat division scales by distinct slots`() {
        val instance = FxInstance(
            effect = WindowedSlider(value = 200u),
            target = stubTarget,
            timing = FxTiming(cycle = CycleLength.ONE_BEAT),
        )
        instance.updateDynamics { it.copy(timing = Timing.HEAD) }
        instance.order = SpreadOrder.NORMAL

        val groupSize = 4
        // With timing=true and NORMAL (4 distinct slots):
        // effectiveDivision = 1.0 * 4 = 4.0 beats = 96 ticks per cycle
        // At tick 24 (1 beat into 4-beat cycle), base phase = 24/96 = 0.25
        val phaseAt24 = instance.phaseForMember(tick(24), memberInfo(0, groupSize), groupSize)
        assertEquals(0.25, phaseAt24, 0.01)

        // Compare with timing=false: same tick should give phase 0.0 (24 % 24 = 0)
        instance.updateDynamics { it.copy(timing = Timing.CYCLE) }
        val phaseAt24NoStep = instance.phaseForMember(tick(24), memberInfo(0, groupSize), groupSize)
        assertEquals(0.0, phaseAt24NoStep, 0.01)
    }

    @Test
    fun `timing true with symmetric distribution uses distinctSlots not groupSize`() {
        val instance = FxInstance(
            effect = WindowedSlider(value = 200u),
            target = stubTarget,
            timing = FxTiming(cycle = CycleLength.ONE_BEAT),
        )
        instance.updateDynamics { it.copy(timing = Timing.HEAD) }
        instance.order = SpreadOrder.CENTRE_OUT

        val groupSize = 4
        // CENTRE_OUT with 4 members has ceil(4/2) = 2 distinct slots
        val distinctSlots = SpreadOrder.CENTRE_OUT.distinctSlots(groupSize)
        assertEquals(2, distinctSlots)

        // effectiveDivision = 1.0 * 2 = 2.0 beats = 48 ticks per cycle
        // Use member 1 (center member, offset=0) for clean assertion
        // At tick 24 (1 beat into 2-beat cycle), base phase = 24/48 = 0.5
        // phase = (0.5 + 0.0 - 0.0 + 1.0) % 1.0 = 0.5
        val phaseAt24 = instance.phaseForMember(tick(24), memberInfo(1, groupSize), groupSize)
        assertEquals(0.5, phaseAt24, 0.01)

        // Compare: with NORMAL (4 slots), effectiveDivision = 4.0 beats = 96 ticks
        // At tick 24, base phase = 24/96 = 0.25
        instance.order = SpreadOrder.NORMAL
        val phaseLinear = instance.phaseForMember(tick(24), memberInfo(0, groupSize), groupSize)
        assertEquals(0.25, phaseLinear, 0.01)
    }

    @Test
    fun `timing has no effect for single-element groups`() {
        val instance = FxInstance(
            effect = SineSlider(),
            target = stubTarget,
            timing = FxTiming(cycle = CycleLength.ONE_BEAT),
        )
        instance.order = SpreadOrder.NORMAL

        val groupSize = 1
        val member = memberInfo(0, groupSize)

        instance.updateDynamics { it.copy(timing = Timing.HEAD) }
        val phaseWithStep = instance.phaseForMember(tick(12), member, groupSize)

        instance.updateDynamics { it.copy(timing = Timing.CYCLE) }
        val phaseWithoutStep = instance.phaseForMember(tick(12), member, groupSize)

        assertEquals(phaseWithStep, phaseWithoutStep, 0.001)
    }

    @Test
    fun `defaultTiming is respected by FxInstance`() {
        val staticFx = WindowedSlider(value = 200u)
        val sineFx = SineSlider()

        val staticInstance = FxInstance(effect = staticFx, target = stubTarget, timing = FxTiming())
        val sineInstance = FxInstance(effect = sineFx, target = stubTarget, timing = FxTiming())

        assertEquals(Timing.HEAD, staticInstance.dynamics.timing)
        assertEquals(Timing.CYCLE, sineInstance.dynamics.timing)
    }
}
