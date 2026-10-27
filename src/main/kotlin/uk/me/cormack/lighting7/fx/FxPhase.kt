package uk.me.cormack.lighting7.fx

import kotlin.math.floor
import uk.me.cormack.lighting7.models.Timing

/**
 * An FX member's phase as a pure function of one reading of its clock, its dynamics and its N-shot
 * anchor: what [FxInstance]'s phase functions answer on every pass, and what [FxSampler] answers for
 * a moment the stage has not reached. The instance keeps its own writes — anchoring an N-shot,
 * [FxInstance.lastPhase] — outside these, and hears an N-shot member's runs through [RunsTally].
 */
internal object FxPhase {
    /**
     * A beat FX's member phase at master tick [ticks], or NaN while the member holds ([FxInstance.isHolding]).
     * [anchorTick] is where an N-shot's count began, read only when [FxDynamics.runs] is set. Integer
     * arithmetic, as [MasterClock.phaseForDivision] always was, so a forever effect's phase is unchanged.
     */
    fun beatPhaseAt(
        ticks: Long,
        anchorTick: Long,
        division: Double,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
        tally: RunsTally? = null,
    ): Double {
        val ticksPerCycle = beatCycleTicks(division)
        if (ticksPerCycle <= 0) return 0.0
        val elapsed = elapsed(ticks, anchorTick, dynamics)
        return shape(
            cycles = elapsed / ticksPerCycle,
            basePhase = (elapsed % ticksPerCycle).toDouble() / ticksPerCycle,
            dynamics = dynamics,
            distributionOffset = distributionOffset,
            groupSize = groupSize,
            tally = tally,
        )
    }

    /** [beatPhaseAt]'s wall-clock twin, over [scaledMs] of the FX's scaled clock and its anchor [anchorMs]. */
    fun wallClockPhaseAt(
        scaledMs: Double,
        anchorMs: Double,
        division: Double,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
        tally: RunsTally? = null,
    ): Double {
        val cycleDurationMs = wallClockCycleMs(division)
        if (cycleDurationMs <= 0.0) return 0.0
        val elapsed = elapsed(scaledMs, anchorMs, dynamics)
        return shape(
            cycles = floor(elapsed / cycleDurationMs).toLong(),
            basePhase = (elapsed % cycleDurationMs) / cycleDurationMs,
            dynamics = dynamics,
            distributionOffset = distributionOffset,
            groupSize = groupSize,
            tally = tally,
        )
    }

    /**
     * A beat FX member's own clock at master tick [ticks], in cycles — the FX's, less the member's
     * offset, plus its Phase: the clock [beatPhaseAt] counts Bounce on (Runs counts it less Phase), so
     * the member's own cycle begins each time this crosses a whole number — under a triangle Order
     * (Ping-pong) the sweep's turnarounds fall elsewhere. 0 where the cycle is empty.
     */
    fun beatOwnCyclesAt(ticks: Long, anchorTick: Long, division: Double, dynamics: FxDynamics, distributionOffset: Double): Double {
        val ticksPerCycle = beatCycleTicks(division)
        if (ticksPerCycle <= 0) return 0.0
        val elapsed = elapsed(ticks, anchorTick, dynamics)
        val basePhase = (elapsed % ticksPerCycle).toDouble() / ticksPerCycle
        return own(elapsed / ticksPerCycle, basePhase, distributionOffset) + dynamics.phaseOffset
    }

    /** [beatOwnCyclesAt]'s wall-clock twin. */
    fun wallClockOwnCyclesAt(scaledMs: Double, anchorMs: Double, division: Double, dynamics: FxDynamics, distributionOffset: Double): Double {
        val cycleDurationMs = wallClockCycleMs(division)
        if (cycleDurationMs <= 0.0) return 0.0
        val elapsed = elapsed(scaledMs, anchorMs, dynamics)
        val basePhase = (elapsed % cycleDurationMs) / cycleDurationMs
        return own(floor(elapsed / cycleDurationMs).toLong(), basePhase, distributionOffset) + dynamics.phaseOffset
    }

    /** An N-shot counts from its anchor; a forever FX from the clock's zero. */
    private fun elapsed(ticks: Long, anchorTick: Long, dynamics: FxDynamics): Long =
        if (dynamics.runs == null) ticks else ticks - anchorTick

    private fun elapsed(scaledMs: Double, anchorMs: Double, dynamics: FxDynamics): Double =
        if (dynamics.runs == null) scaledMs else scaledMs - anchorMs

    /** The member's own clock, in cycles: the FX's, less this member's offset. */
    private fun own(cycles: Long, basePhase: Double, distributionOffset: Double): Double = cycles + basePhase - distributionOffset

    /** A beat cycle of [division] beats in master ticks; at or below 0 the phase is always 0. */
    fun beatCycleTicks(division: Double): Long = (MasterClock.TICKS_PER_BEAT * division).toLong()

    /** A wall-clock cycle of [division] seconds in milliseconds; at or below 0 the phase is always 0. */
    fun wallClockCycleMs(division: Double): Double = division * 1000.0

    /**
     * The cycle length a member pass runs on: [division], scaled for a step-timed (*One cycle for: Each head*)
     * effect by the number of distinct slots after the spread's shape (effects plan §3.3), so it is
     * the time per step rather than the whole cycle, and a chase over Segments or Parts has no empty steps.
     */
    fun memberDivision(division: Double, dynamics: FxDynamics, groupSize: Int): Double =
        if (dynamics.timing == Timing.HEAD && groupSize > 1) division * dynamics.distinctSlots(groupSize) else division

    /**
     * The member-phase shaping shared by the beat and wall-clock forms, which differ only in where
     * the clock comes from: [cycles] whole cycles and [basePhase] of the current one.
     *
     * In order (effects plan §3.3): the distribution's own shaping and offset — the offset already
     * shaped by Spread, Wings, Segments and Parts (steps 2–5, in [DistributionPlan]); then N-shot
     * (D9), whose member runs while its own clock — the effect's less its offset — is in `[0, N)`,
     * holding underneath before and after (NaN); then direction (D5, step 6), Bounce counting
     * cycles on that same own clock.
     */
    private fun shape(
        cycles: Long,
        basePhase: Double,
        dynamics: FxDynamics,
        distributionOffset: Double,
        groupSize: Int,
        tally: RunsTally?,
    ): Double {
        // PING_PONG: apply triangle wave remap to the base clock phase so that
        // ALL effects (not just static ones) sweep forward then backward.
        // Scale to [0, (N-1)/N] to match the NORMAL offset range and avoid
        // wrapping artifacts at the turnaround points. N is the Order's own slot count, never the
        // spread's shape: Spread, Wings, Segments and Parts set the members' offsets, as under every
        // other Order, and leave the sweep alone (effects plan §3.3) — so the sweep is never scaled
        // to nothing, and an effect that names no shape bounces bit for bit as before (P6).
        var shaped = basePhase
        if (dynamics.order.usesTrianglePhase && groupSize > 1) {
            val slots = dynamics.order.distinctSlots(groupSize)
            val tri = if (shaped < 0.5) shaped * 2.0 else 2.0 * (1.0 - shaped)
            shaped = tri * (slots - 1.0) / slots
        }

        // Subtract distribution offset so that higher-offset members are *behind*
        // in the cycle, making the visual sweep flow in the natural direction
        // (element 0 → element N for NORMAL, etc.).
        val phase = ((shaped + dynamics.phaseOffset - distributionOffset) % 1.0 + 1.0) % 1.0

        val own = own(cycles, basePhase, distributionOffset)
        dynamics.runs?.let { runs ->
            tally?.note(pending = own < runs)
            if (own < 0.0 || own >= runs) return Double.NaN
        }
        val ownCycle = floor(own + dynamics.phaseOffset).toLong()
        return dynamics.direction.apply(phase, ownCycle)
    }
}

/**
 * Hears an N-shot member's phase being computed (D9): [note] with whether it has runs still to finish
 * — running, or waiting for its offset to come round. The engine's instance counts them to end a
 * spent N-shot ([FxInstance.runsPending]); the sampler passes none.
 */
internal fun interface RunsTally {
    fun note(pending: Boolean)
}
