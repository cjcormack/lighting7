package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import kotlin.math.abs

/**
 * The curve a fan follows along an ordered list of heads — Titan's four fan shapes, named the way
 * Titan names them (busk-further plan §1, D9). Each maps a head's **position** `x ∈ [0, 1]` along the
 * order to a **fraction** `t ∈ [0, 1]` between the two endpoint intents:
 *
 * - [LINE] — `t = x`: the first head at *from*, the last at *to*.
 * - [MIRROR] — `t = |2x − 1|`: the centre at *from*, both ends at *to*.
 * - [ARROW] — `t = 1 − |2x − 1|`: both ends at *from*, the centre at *to*.
 * - [MIRROR_OUT] — **two mirrored fans meeting at the centre**: each wing is a complete fan on its own
 *   heads, *to* at the outer end and *from* at the centre, so eight heads read `1 · .67 · .33 · 0 |
 *   0 · .33 · .67 · 1` and five read `1 · .5 · 0 · .5 · 1` (an odd count's centre head belongs to
 *   both wings). Titan's outward-from-centre reading, chosen 2026-09-17; it is not a function of a
 *   head's position along the whole order, so [FanPlan.fractions] computes it per wing rather
 *   than through [at]. Two heads are two outer ends (`1 · 1`), as under [MIRROR].
 */
enum class FanCurve {
    LINE,
    MIRROR,
    ARROW,
    MIRROR_OUT,
    ;

    /**
     * The fraction at position [x], which is already `[0, 1]`, for the three curves that are a
     * function of position. [MIRROR_OUT] is not — it is computed per wing by [FanPlan.fractions].
     */
    fun at(x: Double): Double = when (this) {
        LINE -> x
        MIRROR -> abs(2.0 * x - 1.0)
        ARROW -> 1.0 - abs(2.0 * x - 1.0)
        MIRROR_OUT -> error("MIRROR_OUT is computed per wing by FanPlan.fractions, not by position")
    }.coerceIn(0.0, 1.0)

    companion object {
        fun byName(name: String): FanCurve? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/** Whether a spread's unit is the fixture or, on a multi-head fixture, each of its cells. */
enum class FanOver {
    HEADS,
    CELLS,
    ;

    companion object {
        fun byName(name: String): FanOver? = entries.firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
    }
}

/**
 * The curve and order maths of a fan, **pure** over an ordered list of heads: which fraction of
 * the way from *from* to *to* each head lands. Everything that knows a fixture — the group's member
 * order, which cells it has, what a colour means on a head with amber — is the route's business
 * (`routes/programmerFan.kt`); this file never sees one, which is what makes its tests a table.
 *
 * **Order is a [SpreadOrder].** The heads arrive already in rig order (`NORMAL`, the
 * desk's `state/BuskRigOrder.kt` — or a lone group's own member order, venue-visit plan S7), and the strategy's own phase offsets say where each sits along
 * the curve: `NORMAL` is `0 … 1` in list order, `REVERSE` runs the other way, `CENTRE_OUT` folds so
 * the two ends share a position, `RANDOM(seed)` is a permutation, `UNIFIED` puts every head at 0.
 * The offsets are normalised to the largest one so the far end always reaches `1.0` — a strategy's
 * phase spacing is `i / n` (the FX tick's need, never landing on a whole cycle) where a spread's is
 * `i / (n − 1)` (the last head must land on *to*).
 *
 * **`runs` splits the order into that many fans.** The heads are cut into `runs` contiguous
 * stretches — the first runs one longer when it does not divide, as a `HALVES` tile is cut — and each
 * run is its own spread from *from* to *to*: `runs = 2` with [FanCurve.LINE] over four heads
 * is `0 · 1 · 0 · 1`, which is what the operator means by "two fans". The order strategy applies
 * within each run.
 */
object FanPlan {
    /** A head's place in the order, for the strategy's phase maths. */
    private class Slot(override val index: Int, override val normalizedPosition: Double) : DistributionMemberInfo

    /**
     * The position `x ∈ [0, 1]` of each of [count] heads under [order]: the strategy's offsets in
     * list order, normalised so the largest is `1.0`. One head is at `0.0`; every head under
     * `UNIFIED` is too.
     */
    fun positions(count: Int, order: SpreadOrder): DoubleArray {
        if (count == 0) return DoubleArray(0)
        val slots = List(count) { i -> Slot(i, if (count > 1) i.toDouble() / (count - 1) else 0.5) }
        val offsets = order.offsets(slots, count)
        val max = offsets.maxOrNull() ?: 0.0
        return if (max <= 0.0) DoubleArray(count) else DoubleArray(count) { (offsets[it] / max).coerceIn(0.0, 1.0) }
    }

    /**
     * The fraction `t ∈ [0, 1]` of each of [count] heads: the order cut into [runs] contiguous runs,
     * each run's [positions] under [order] through [curve]. One head in a run sits at `0.0`.
     */
    fun fractions(count: Int, order: SpreadOrder, curve: FanCurve, runs: Int): DoubleArray {
        require(runs >= 1) { "runs must be at least 1" }
        if (count == 0) return DoubleArray(0)
        val out = DoubleArray(count)
        var at = 0
        for (size in runSizes(count, runs)) {
            if (curve == FanCurve.MIRROR_OUT) {
                wings(size, order).copyInto(out, at)
            } else {
                val positions = positions(size, order)
                for (j in 0 until size) out[at + j] = curve.at(positions[j])
            }
            at += size
        }
        return out
    }

    /**
     * A **fan**'s weights (venue-visit plan S12): `2t − 1` for each [fractions] `t`, so the amount
     * times a head's weight is its offset from its own position. Under [FanCurve.LINE] the fan is
     * symmetric about the middle of each run — the middle head of an odd run stays put, the ends move
     * by the whole amount in opposite directions — and every other curve and order keeps its shape,
     * read against that midline. A run of one head has no fan, so it weighs 0 whatever the curve.
     */
    fun fanWeights(count: Int, order: SpreadOrder, curve: FanCurve, runs: Int): DoubleArray {
        val t = fractions(count, order, curve, runs)
        val out = DoubleArray(count) { 2.0 * t[it] - 1.0 }
        var at = 0
        for (size in runSizes(count, runs)) {
            if (size == 1) out[at] = 0.0
            at += size
        }
        return out
    }

    /** The sizes of the [runs] contiguous runs [count] heads are cut into, the first runs one longer when it does not divide. */
    private fun runSizes(count: Int, runs: Int): List<Int> {
        if (count == 0) return emptyList()
        val n = runs.coerceAtMost(count)
        return List(n) { i -> count / n + if (i < count % n) 1 else 0 }
    }

    /**
     * [FanCurve.MIRROR_OUT] over one run of [size] heads: the left wing is the first `⌈size / 2⌉` heads
     * and the right wing the last `⌈size / 2⌉`, so an odd run's centre head is in both. Each wing is
     * a fan from *from* at the centre to *to* at its outer end, in [order]'s positions counted from
     * the outer end — so `REVERSE` turns both wings inside out together, and a one-head wing is its
     * own outer end at `1.0`.
     */
    private fun wings(size: Int, order: SpreadOrder): DoubleArray {
        val out = DoubleArray(size)
        if (size == 0) return out
        val leftSize = (size + 1) / 2
        val rightStart = size / 2
        val rightSize = size - rightStart
        val left = positions(leftSize, order)
        for (k in 0 until leftSize) out[k] = (1.0 - left[k]).coerceIn(0.0, 1.0)
        val right = positions(rightSize, order)
        for (j in 0 until rightSize) out[rightStart + j] = (1.0 - right[rightSize - 1 - j]).coerceIn(0.0, 1.0)
        return out
    }
}
