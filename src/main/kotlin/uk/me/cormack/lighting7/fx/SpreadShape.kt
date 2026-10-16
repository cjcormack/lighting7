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
 *   under an asymmetric Order: Centre out, Edges in and Split are two wings already, so wings under
 *   them are refused at the write boundary ([EffectSpecCoercion.Strict.spreadProblem]) and ignored if
 *   a stored row holds one anyway.
 * @property blocks This many neighbouring members share one phase; 1 is none.
 * @property groups The spread repeats every this many members; 0 is off.
 */
data class SpreadShape(
    val spread: Double = 1.0,
    val wings: Int = 1,
    val blocks: Int = 1,
    val groups: Int = 0,
) {
    val isDefault: Boolean get() = this == DEFAULT

    companion object {
        val DEFAULT = SpreadShape()

        /** Spread's ceiling, 200 % (§3.1). */
        const val MAX_SPREAD = 2.0

        /** The ceiling on Wings, Blocks and Groups: a universe of single-channel heads. */
        const val MAX_COUNT = 512
    }
}

/**
 * Steps 2–5 of §3.3, over ranks the Order (step 1) has already given — the one place the desk says
 * what Spread, Wings, Blocks and Groups do (P2). `frontend/src/lib/spreadShape.ts` is the editor's
 * copy, for its dial preview only, and `src/test/resources/fx/spreadShape.fixture.json` pins the two
 * together.
 */
object SpreadShaping {
    /** Each member's offset, in the members' order, and the rank count left after step 4. */
    class Result(val offsets: DoubleArray, val slots: Int)

    /**
     * Shape [ranks] (step 1's, one per member; whole numbers except under `POSITIONAL`, whose rank is
     * its position along the group) over [rankCount] ranks.
     *
     * 2. **Blocks** `B`: `r ← floor(r / B)`, over `ceil(R / B)` ranks — `ceil(n / B)` under an
     *    asymmetric Order, whose rank count is the member count.
     * 3. **Wings** `W`, only when [wingsApply]: the ranks split into `W` runs of `m = ceil(R / W)`;
     *    an odd run is mirrored within its own length (`r ← len − 1 − r`, `len` being `m` for every
     *    run but a short last one), so the outermost member of each wing pair shares a rank; over `m`.
     * 4. **Groups** `G`: `r ← r mod G`, over `G` ranks — when `G` is below the rank count; otherwise
     *    the ranks stand, so the slot count never exceeds what the members can fill.
     * 5. **Spread** `s`: `offset = s × r / R`.
     *
     * [Result.slots] is `R` after step 4: a step chase's window is `1 / slots` and its step timing
     * scales by it, so neither leaves a gap.
     */
    fun shape(ranks: DoubleArray, rankCount: Int, shape: SpreadShape, wingsApply: Boolean): Result {
        val n = ranks.size
        var count = max(rankCount, 1)
        val r = ranks.copyOf()

        if (shape.blocks > 1) {
            val b = shape.blocks
            count = ceilDiv(count, b)
            for (i in 0 until n) r[i] = min(floor(r[i] / b), (count - 1).toDouble())
        }

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

        if (shape.groups in 1 until count) {
            val g = shape.groups.toDouble()
            for (i in 0 until n) r[i] = r[i] - g * floor(r[i] / g)
            count = shape.groups
        }

        val offsets = DoubleArray(n) { shape.spread * r[it] / count }
        return Result(offsets, count)
    }

    /**
     * [Result.slots] without the ranks — what the step timing and ping-pong's turnaround read per pass
     * from [rankCount] (the Order's own slot count), in constant time.
     */
    fun slots(rankCount: Int, shape: SpreadShape, wingsApply: Boolean): Int =
        slots(rankCount, shape.wings, shape.blocks, shape.groups, wingsApply)

    /** [slots] over the four fields as [FxDynamics] holds them, so a tick allocates no [SpreadShape]. */
    fun slots(rankCount: Int, wings: Int, blocks: Int, groups: Int, wingsApply: Boolean): Int {
        var count = max(rankCount, 1)
        if (blocks > 1) count = ceilDiv(count, blocks)
        if (wingsApply && wings > 1) count = ceilDiv(count, wings)
        if (groups in 1 until count) count = groups
        return count
    }

    private fun ceilDiv(a: Int, b: Int): Int = ceil(a.toDouble() / b).toInt()
}
