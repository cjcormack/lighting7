package uk.me.cormack.lighting7.fx

import kotlin.math.floor

/**
 * An FX member's phase as a pure function of one reading of its clock, its dynamics and its N-shot
 * anchor: what [FxInstance]'s phase functions answer on every pass, and what [FxSampler] answers for
 * a moment the stage has not reached. The instance keeps its own writes — anchoring an N-shot,
 * [FxInstance.lastPhase] — outside these, and hears an N-shot member's runs through [NShotTally].
 */
internal object FxPhase {
    /**
     * A beat FX's member phase at master tick [ticks], or NaN while the member holds ([FxInstance.isHolding]).
     * [anchorTick] is where an N-shot's count began, read only when [FxDynamics.nShot] is set. Integer
     * arithmetic, as [MasterClock.phaseForDivision] always was, so a forever effect's phase is unchanged.
     */
    fun beatPhaseAt(
        ticks: Long,
        anchorTick: Long,
        division: Double,
        groupSize: Int,
        dynamics: FxDynamics,
        distributionOffset: Double,
        tally: NShotTally? = null,
    ): Double {
        val ticksPerCycle = beatCycleTicks(division)
        if (ticksPerCycle <= 0) return 0.0
        val elapsed = if (dynamics.nShot == null) ticks else ticks - anchorTick
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
        tally: NShotTally? = null,
    ): Double {
        val cycleDurationMs = wallClockCycleMs(division)
        if (cycleDurationMs <= 0.0) return 0.0
        val elapsed = if (dynamics.nShot == null) scaledMs else scaledMs - anchorMs
        return shape(
            cycles = floor(elapsed / cycleDurationMs).toLong(),
            basePhase = (elapsed % cycleDurationMs) / cycleDurationMs,
            dynamics = dynamics,
            distributionOffset = distributionOffset,
            groupSize = groupSize,
            tally = tally,
        )
    }

    /** A beat cycle of [division] beats in master ticks; at or below 0 the phase is always 0. */
    fun beatCycleTicks(division: Double): Long = (MasterClock.TICKS_PER_BEAT * division).toLong()

    /** A wall-clock cycle of [division] seconds in milliseconds; at or below 0 the phase is always 0. */
    fun wallClockCycleMs(division: Double): Double = division * 1000.0

    /**
     * The cycle length a member pass runs on: [division], scaled for a step-timed (Timing *Per head*)
     * effect by the number of distinct slots after the spread's shape (effects plan §3.3), so it is
     * the time per step rather than the whole cycle, and a chase over Blocks or Groups has no empty steps.
     */
    fun memberDivision(division: Double, dynamics: FxDynamics, groupSize: Int): Double =
        if (dynamics.stepTiming && groupSize > 1) division * dynamics.distinctSlots(groupSize) else division

    /**
     * The member-phase shaping shared by the beat and wall-clock forms, which differ only in where
     * the clock comes from: [cycles] whole cycles and [basePhase] of the current one.
     *
     * In order (effects plan §3.3): the distribution's own shaping and offset — the offset already
     * shaped by Spread, Wings, Blocks and Groups (steps 2–5, in [DistributionPlan]); then N-shot
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
        tally: NShotTally?,
    ): Double {
        // PING_PONG: apply triangle wave remap to the base clock phase so that
        // ALL effects (not just static ones) sweep forward then backward.
        // Scale to [0, (N-1)/N] to match the LINEAR offset range and avoid
        // wrapping artifacts at the turnaround points. N is the Order's own slot count, never the
        // spread's shape: Spread, Wings, Blocks and Groups set the members' offsets, as under every
        // other Order, and leave the sweep alone (effects plan §3.3) — so the sweep is never scaled
        // to nothing, and an effect that names no shape bounces bit for bit as before (P6).
        var shaped = basePhase
        if (dynamics.distributionStrategy.usesTrianglePhase && groupSize > 1) {
            val slots = dynamics.distributionStrategy.distinctSlots(groupSize)
            val tri = if (shaped < 0.5) shaped * 2.0 else 2.0 * (1.0 - shaped)
            shaped = tri * (slots - 1.0) / slots
        }

        // Subtract distribution offset so that higher-offset members are *behind*
        // in the cycle, making the visual sweep flow in the natural direction
        // (element 0 → element N for LINEAR, etc.).
        val phase = ((shaped + dynamics.phaseOffset - distributionOffset) % 1.0 + 1.0) % 1.0

        // The member's own clock, in cycles: the effect's, less this member's offset.
        val own = cycles + basePhase - distributionOffset
        dynamics.nShot?.let { runs ->
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
 * spent N-shot ([FxInstance.nShotPending]); the sampler passes none.
 */
internal fun interface NShotTally {
    fun note(pending: Boolean)
}
