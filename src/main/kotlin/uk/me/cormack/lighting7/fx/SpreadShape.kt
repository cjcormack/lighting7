package uk.me.cormack.lighting7.fx

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The spread's shape (effects plan D4, §3.3 steps 2–5): how the ranks an effect's Order gives its
 * members become phase offsets. Stored on every effect home beside the Order (`distribution`), and an
 * [FxDynamics] field edited in place, phase kept.
 *
 * Every field's default is today's spread, so an effect that names none of them runs exactly as it
 * did before session 2 (P6) — [DistributionPlan.of] does not even run the shaping for [DEFAULT].
 *
 * @property spread How much of the cycle the members cover, 0.0–2.0 (0–200 %); 1.0 is today's.
 * @property wings The members split into this many wings, alternate wings mirrored; 1 is none. Only
 *   under an asymmetric Order: Centre out and Centre in are two wings already, so wings under
 *   them are refused at the write boundary ([FxSpecCoercion.Strict.spreadProblem]) and ignored if
 *   a stored row holds one anyway.
 * @property segments This many neighbouring members share one phase; 1 is none.
 * @property parts The spread repeats every this many members; 0 is off.
 */
data class SpreadShape(
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = SpreadShape()

        /** Spread's ceiling, 200 % (§3.1). */
        const val MAX_SPREAD = 2.0

        /** The ceiling on Wings, Segments and Parts: a universe of single-channel heads. */
        const val MAX_COUNT = 512
    }
}

/**
 * Steps 2–5 of §3.3, over ranks the Order (step 1) has already given — the one place the desk says
 * what Spread, Wings, Segments and Parts do (P2). `frontend/src/lib/spreadShape.ts` is the editor's
 * copy, for its dial preview only, and `src/test/resources/fx/spreadShape.fixture.json` pins the two
 * together.
 */
object SpreadShaping {
    /** Each member's offset, in the members' order, and the rank count left after step 4. */
    class Result(val offsets: DoubleArray, val slots: Int)

    /**
     * The offset ladder (FX editor plan D9): each member's rank, and the rank count, after each of
     * steps 1–4 — [shape] fills it as it goes, so the ladder is the shaping itself, not a copy of it.
     */
    class Ladder(members: Int) {
        /** Indexed by [Step]: each member's rank after that step. */
        val ranks: Array<DoubleArray> = Array(Step.entries.size) { DoubleArray(members) }

        /** Indexed by [Step]: the rank count after that step. */
        val counts: IntArray = IntArray(Step.entries.size)

        internal fun record(step: Step, r: DoubleArray, count: Int) {
            r.copyInto(ranks[step.ordinal])
            counts[step.ordinal] = count
        }
    }

    /** A rung of the [Ladder]: steps 1–4 of §3.3. Step 5, Spread, is the offsets themselves. */
    enum class Step { ORDER, SEGMENTS, WINGS, PARTS }

    /**
     * Shape [ranks] (step 1's, one per member) over [rankCount] ranks. They are whole numbers whenever
     * Segments, Wings or Parts act ([countsRanks]) — `POSITIONAL`'s position is snapped to a slot
     * first — and continuous only under Spread alone, which scales them as they are.
     *
     * 2. **Segments** `S`: `r ← floor(r / S)`, over `ceil(R / S)` ranks — `ceil(n / S)` under an
     *    asymmetric Order, whose rank count is the member count.
     * 3. **Wings** `W`, only when [wingsApply]: the ranks split into `W` runs of `m = ceil(R / W)`;
     *    an odd run is mirrored within its own length (`r ← len − 1 − r`, `len` being `m` for every
     *    run but a short last one), so the outermost member of each wing pair shares a rank; over `m`.
     * 4. **Parts** `P`: `r ← r mod P`, over `P` ranks — when `P` is below the rank count; otherwise
     *    the ranks stand, so the slot count never exceeds what the members can fill.
     * 5. **Spread** `s`: `offset = s × r / R`.
     *
     * [Result.slots] is `R` after step 4: a step chase's window is `1 / slots` and its step timing
     * scales by it, so neither leaves a gap.
     */
    fun shape(ranks: DoubleArray, rankCount: Int, shape: SpreadShape, wingsApply: Boolean, ladder: Ladder? = null): Result {
        val n = ranks.size
        var count = max(rankCount, 1)
        val r = ranks.copyOf()
        ladder?.record(Step.ORDER, r, count)

        if (shape.segments > 1) {
            val s = shape.segments
            count = ceilDiv(count, s)
            for (i in 0 until n) r[i] = min(floor(r[i] / s), (count - 1).toDouble())
        }
        ladder?.record(Step.SEGMENTS, r, count)

        if (wingsApply && shape.wings > 1) {
            val m = ceilDiv(count, shape.wings)
            for (i in 0 until n) {
                val run = floor(r[i] / m).toInt().coerceIn(0, shape.wings - 1)
                val local = r[i] - run.toDouble() * m
                if (run % 2 == 1) {
                    val len = min(m, count - run * m).coerceAtLeast(1)
                    r[i] = max(0.0, (len - 1) - local)
                } else {
                    r[i] = local
                }
            }
            count = m
        }
        ladder?.record(Step.WINGS, r, count)

        if (shape.parts in 1 until count) {
            val p = shape.parts.toDouble()
            for (i in 0 until n) r[i] = r[i] - p * floor(r[i] / p)
            count = shape.parts
        }
        ladder?.record(Step.PARTS, r, count)

        val offsets = DoubleArray(n) { shape.spread * r[it] / count }
        return Result(offsets, count)
    }

    /**
     * [Result.slots] without the ranks — what the step timing and ping-pong's turnaround read per pass
     * from [rankCount] (the Order's own slot count), in constant time, over the four fields as
     * [FxDynamics] holds them so a tick allocates no [SpreadShape].
     */
    fun slots(rankCount: Int, wings: Int, segments: Int, parts: Int, wingsApply: Boolean): Int {
        var count = max(rankCount, 1)
        if (segments > 1) count = ceilDiv(count, segments)
        if (wingsApply && wings > 1) count = ceilDiv(count, wings)
        if (parts in 1 until count) count = parts
        return count
    }

    /**
     * Whether Segments, Wings or Parts act on [rankCount] ranks — the steps that count in whole ranks,
     * so a continuous Order's ranks (Position's) are snapped to whole slots before them
     * ([uk.me.cormack.lighting7.fx.group.SpreadOrder.wholeRanks]). Spread alone does not.
     */
    fun countsRanks(rankCount: Int, shape: SpreadShape, wingsApply: Boolean): Boolean {
        if (shape.segments > 1) return true
        if (wingsApply && shape.wings > 1) return true
        return shape.parts in 1 until max(rankCount, 1)
    }

    private fun ceilDiv(a: Int, b: Int): Int = ceil(a.toDouble() / b).toInt()
}
