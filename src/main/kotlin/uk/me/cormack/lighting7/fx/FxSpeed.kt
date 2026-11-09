package uk.me.cormack.lighting7.fx

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * An FX's **Speed** (fx-speed plan D1, D4) as the desk computes it: `4 ÷ cycle` on a beat FX (cycle
 * in beats), `2 ÷ cycle` on a wall-clock one (cycle in seconds), ×1 one bar at the running tempo on
 * either. The editor's `frontend/src/lib/fxSpeed.ts` speaks the same rules, and
 * `src/test/resources/fx/fxSpeed.fixture.json` pins the two together (P2).
 *
 * The surface's *FX speed* (D13) is the only caller that writes a Speed here: an encoder's detent
 * ([stepSpeed], read against [ringPosition]) and a fader's position ([faderSpeed]), each written as
 * the cycle [cycleOf] answers.
 */
object FxSpeed {
    /** How far a Speed may go (D1): a 2048-beat cycle to ⅛ beat. */
    const val SPEED_MIN = 1.0 / 512
    const val SPEED_MAX = 32.0

    /** A fader's travel (D13): ×¼ to ×4. */
    const val FADER_MIN = 0.25
    const val FADER_MAX = 4.0

    /** How close to a power of two, in octaves, a drag sticks to it: the slider's, and a fader's. */
    private const val STICK_OCTAVES = 0.12

    private const val TICKS_PER_BEAT = MasterClock.TICKS_PER_BEAT

    fun speedOf(cycle: Double, wallClock: Boolean): Double =
        if (cycle > 0.0) (if (wallClock) 2.0 else 4.0) / cycle else 1.0

    fun clampSpeed(speed: Double): Double = speed.coerceIn(SPEED_MIN, SPEED_MAX)

    /**
     * The cycle written for [speed]: a beat cycle rounded to whole ticks, which is what runs
     * ([FxPhase.beatCycleTicks]); a wall-clock one to the millisecond.
     */
    fun cycleOf(speed: Double, wallClock: Boolean): Double {
        val s = clampSpeed(speed)
        if (wallClock) return max(0.001, (2.0 / s * 1000).roundToLong() / 1000.0)
        return max(1L, (4.0 / s * TICKS_PER_BEAT).roundToLong()).toDouble() / TICKS_PER_BEAT
    }

    /** The Speed that runs for [cycle]: a beat cycle's whole ticks, so ×5 runs at ×5.05. */
    fun runningSpeed(cycle: Double, wallClock: Boolean): Double {
        if (wallClock) return speedOf(cycle, true)
        val ticks = FxPhase.beatCycleTicks(cycle)
        return if (ticks > 0) speedOf(ticks.toDouble() / TICKS_PER_BEAT, false) else speedOf(cycle, false)
    }

    /** [speed] stuck to the nearest power of two when within [STICK_OCTAVES] of it. */
    fun stickSpeed(speed: Double): Double {
        if (speed <= 0.0) return speed
        val exponent = log2(speed)
        val nearest = round(exponent)
        return if (abs(exponent - nearest) <= STICK_OCTAVES) 2.0.pow(nearest) else speed
    }

    /**
     * [speed] moved [quarters] quarter-octaves — an encoder's detents (D13), the slider's arrow keys —
     * stopping at a power of two it would pass, so every snap is reached from either side.
     */
    fun stepSpeed(speed: Double, quarters: Int): Double {
        if (quarters == 0 || speed <= 0.0) return speed
        val from = log2(speed)
        val to = from + quarters / 4.0
        val between = if (quarters > 0) floor(to - 1e-9) else ceil(to + 1e-9)
        val passes = if (quarters > 0) between > from + 1e-9 && between < to else between < from - 1e-9 && between > to
        return clampSpeed(2.0.pow(if (passes) between else to))
    }

    /** The Speed a fader at [value7Bit] sets (D13): ×¼ to ×4 on a log scale, stuck at each power of two. */
    fun faderSpeed(value7Bit: Int): Double {
        val lo = log2(FADER_MIN)
        val span = log2(FADER_MAX) - lo
        return stickSpeed(2.0.pow(lo + value7Bit.coerceIn(0, 127) / 127.0 * span))
    }

    /** Where a fader shows [speed]: its 7-bit position, at the nearer end when outside ×¼–×4. */
    fun faderPosition(speed: Double): Int {
        if (speed <= 0.0) return 0
        val p = (log2(speed) - log2(FADER_MIN)) / (log2(FADER_MAX) - log2(FADER_MIN))
        return (min(1.0, max(0.0, p)) * 127).roundToInt()
    }

    /** The ring's position at ×1. */
    const val RING_CENTRE = 64

    /**
     * Where an encoder's ring shows [speed]: one 7-bit step a quarter of an octave, ×1 at
     * [RING_CENTRE], so a detent moves the device's own count as far as the turn moves the lead, and
     * the whole ×1/512–×32 range fits (28 to 84).
     */
    fun ringPosition(speed: Double): Int =
        if (speed <= 0.0) 0 else (RING_CENTRE + 4 * log2(clampSpeed(speed))).roundToInt().coerceIn(0, 127)
}
