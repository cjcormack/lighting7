package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve

/**
 * An effect's **envelope** (effects plan §3.1, §3.5): the runtime factor of its weight that a fade
 * moves — a cue's GO and a programmer gesture (D1, D13). Never stored, never recorded.
 *
 * A function of the clock rather than a value something ticks: a fade is one immutable ramp, from
 * [from] to [to] over [durationMs] on [curve], read wherever the weight is read
 * ([FxInstance.intensityMultiplier]). So a fade needs no driver of its own and cannot be left behind
 * by one, an edit that swaps the instance ([FxEngine.updateFx]) carries the ramp by carrying the
 * object, and every change of fade is one compare-and-set on the instance ([FxInstance.updateEnvelope]).
 *
 * A [leaving] envelope belongs to an instance that is on its way out: its owner — a cue whose last
 * hold went, a programmer layer released — has let it go, and nothing but the fade holds it. It is
 * removed once it has run to 0 ([FxEngine.retireFinishedLeaving]), or at once by whatever takes its
 * owner down without a fade. [RETIRED] marks one removed that way, so a cue brought back on in the
 * same instant cannot reclaim an instance the engine has already let go of.
 */
class FxEnvelope private constructor(
    val from: Double,
    val to: Double,
    val startMs: Long,
    val durationMs: Long,
    val curve: EasingCurve,
    val leaving: Boolean,
) {
    /** True when this envelope reads [to] at every instant — no ramp is running. */
    val isConstant: Boolean get() = durationMs <= 0 || from == to

    /** The level at [nowMs], in `[0, 1]`. */
    fun at(nowMs: Long): Double {
        if (isConstant) return to
        val progress = ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        return (from + (to - from) * curve.apply(progress)).coerceIn(0.0, 1.0)
    }

    /** True once the ramp has run its course. */
    fun finished(nowMs: Long): Boolean = isConstant || nowMs - startMs >= durationMs

    /** A leaving envelope that has reached 0: the instance can go. */
    fun spentAt(nowMs: Long): Boolean = leaving && to <= 0.0 && finished(nowMs)

    override fun toString(): String =
        if (this === RETIRED) "FxEnvelope(RETIRED)"
        else "FxEnvelope($from→$to over ${durationMs}ms, ${curve.name}${if (leaving) ", leaving" else ""})"

    companion object {
        /** Full, the envelope every instance starts at. */
        val FULL: FxEnvelope = constant(1.0)

        /** The mark of an instance taken out of the engine as leaving; never reclaimed. */
        val RETIRED: FxEnvelope = FxEnvelope(0.0, 0.0, 0, 0, EasingCurve.LINEAR, leaving = true)

        /** A level that does not move. */
        fun constant(level: Double, leaving: Boolean = false): FxEnvelope {
            val l = level.coerceIn(0.0, 1.0)
            return FxEnvelope(l, l, 0, 0, EasingCurve.LINEAR, leaving)
        }

        /** From [from] to [to] over [durationMs] on [curve], starting at [nowMs]; a 0 ms fade lands on [to]. */
        fun ramp(
            from: Double,
            to: Double,
            durationMs: Long,
            curve: EasingCurve,
            nowMs: Long = System.currentTimeMillis(),
            leaving: Boolean = false,
        ): FxEnvelope =
            if (durationMs <= 0) constant(to, leaving)
            else FxEnvelope(from.coerceIn(0.0, 1.0), to.coerceIn(0.0, 1.0), nowMs, durationMs, curve, leaving)
    }
}
