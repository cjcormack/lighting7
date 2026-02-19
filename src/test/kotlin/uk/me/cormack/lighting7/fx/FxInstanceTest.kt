package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import kotlin.test.Test
import kotlin.test.assertEquals

class FxInstanceTest {

    /** Use a real SliderTarget — the target type doesn't affect phase calculation. */
    private val stubTarget = SliderTarget(FxTargetRef.FixtureRef("test"), "dimmer")

    private fun memberInfo(index: Int, groupSize: Int) = object : DistributionMemberInfo {
        override val index = index
        override val normalizedPosition = if (groupSize <= 1) 0.0 else index.toDouble() / (groupSize - 1)
    }

    private fun makeClock() = MasterClock()

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
    fun `stepTiming false - beat division is total cycle time`() {
        val clock = makeClock()
        val instance = FxInstance(
            effect = SineWave(),
            target = stubTarget,
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        instance.stepTiming = false
        instance.distributionStrategy = DistributionStrategy.LINEAR

        val groupSize = 4
        // One beat = 24 ticks. With stepTiming=false, one full cycle = 24 ticks.
        val phaseAt0 = instance.calculatePhaseForMember(tick(0), clock, memberInfo(0, groupSize), groupSize)
        val phaseAt12 = instance.calculatePhaseForMember(tick(12), clock, memberInfo(0, groupSize), groupSize)

        // At tick 12 of 24-tick cycle, base phase should be 0.5
        // Member 0 with LINEAR has offset 0, so phase ≈ 0.5
        assertEquals(0.5, phaseAt12, 0.01)
        assertEquals(0.0, phaseAt0, 0.01)
    }

    @Test
    fun `stepTiming true - beat division scales by distinct slots`() {
        val clock = makeClock()
        val instance = FxInstance(
            effect = StaticValue(value = 200u),
            target = stubTarget,
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        instance.stepTiming = true
        instance.distributionStrategy = DistributionStrategy.LINEAR

        val groupSize = 4
        // With stepTiming=true and LINEAR (4 distinct slots):
        // effectiveDivision = 1.0 * 4 = 4.0 beats = 96 ticks per cycle
        // At tick 24 (1 beat into 4-beat cycle), base phase = 24/96 = 0.25
        val phaseAt24 = instance.calculatePhaseForMember(tick(24), clock, memberInfo(0, groupSize), groupSize)
        assertEquals(0.25, phaseAt24, 0.01)

        // Compare with stepTiming=false: same tick should give phase 0.0 (24 % 24 = 0)
        instance.stepTiming = false
        val phaseAt24NoStep = instance.calculatePhaseForMember(tick(24), clock, memberInfo(0, groupSize), groupSize)
        assertEquals(0.0, phaseAt24NoStep, 0.01)
    }

    @Test
    fun `stepTiming true with symmetric distribution uses distinctSlots not groupSize`() {
        val clock = makeClock()
        val instance = FxInstance(
            effect = StaticValue(value = 200u),
            target = stubTarget,
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        instance.stepTiming = true
        instance.distributionStrategy = DistributionStrategy.CENTER_OUT

        val groupSize = 4
        // CENTER_OUT with 4 members has ceil(4/2) = 2 distinct slots
        val distinctSlots = DistributionStrategy.CENTER_OUT.distinctSlots(groupSize)
        assertEquals(2, distinctSlots)

        // effectiveDivision = 1.0 * 2 = 2.0 beats = 48 ticks per cycle
        // Use member 1 (center member, offset=0) for clean assertion
        // At tick 24 (1 beat into 2-beat cycle), base phase = 24/48 = 0.5
        // phase = (0.5 + 0.0 - 0.0 + 1.0) % 1.0 = 0.5
        val phaseAt24 = instance.calculatePhaseForMember(tick(24), clock, memberInfo(1, groupSize), groupSize)
        assertEquals(0.5, phaseAt24, 0.01)

        // Compare: with LINEAR (4 slots), effectiveDivision = 4.0 beats = 96 ticks
        // At tick 24, base phase = 24/96 = 0.25
        instance.distributionStrategy = DistributionStrategy.LINEAR
        val phaseLinear = instance.calculatePhaseForMember(tick(24), clock, memberInfo(0, groupSize), groupSize)
        assertEquals(0.25, phaseLinear, 0.01)
    }

    @Test
    fun `stepTiming has no effect for single-element groups`() {
        val clock = makeClock()
        val instance = FxInstance(
            effect = SineWave(),
            target = stubTarget,
            timing = FxTiming(beatDivision = BeatDivision.QUARTER),
        )
        instance.distributionStrategy = DistributionStrategy.LINEAR

        val groupSize = 1
        val member = memberInfo(0, groupSize)

        instance.stepTiming = true
        val phaseWithStep = instance.calculatePhaseForMember(tick(12), clock, member, groupSize)

        instance.stepTiming = false
        val phaseWithoutStep = instance.calculatePhaseForMember(tick(12), clock, member, groupSize)

        assertEquals(phaseWithStep, phaseWithoutStep, 0.001)
    }

    @Test
    fun `defaultStepTiming is respected by FxInstance`() {
        val staticEffect = StaticValue(value = 200u)
        val sineEffect = SineWave()

        val staticInstance = FxInstance(effect = staticEffect, target = stubTarget, timing = FxTiming())
        val sineInstance = FxInstance(effect = sineEffect, target = stubTarget, timing = FxTiming())

        assertEquals(true, staticInstance.stepTiming)
        assertEquals(false, sineInstance.stepTiming)
    }
}
