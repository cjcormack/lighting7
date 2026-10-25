package uk.me.cormack.lighting7.fixture.attribute

import java.awt.Color
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * **A warm/cold white pair as a colour** (venue-visit plan D8): the small colour-temperature model
 * that lets a head with two whites and no RGB take a colour, and say what colour it is making.
 *
 * Each white is the black body at its declared colour temperature, seen against a 5000 K white
 * ([adaptedRgb], the white the Stage view draws lamps against), so `#ffffff` lands between a warm
 * and a cold white rather than on whichever is nearer daylight. Their mix lies on the straight line
 * between the two in chromaticity, so:
 *
 *  - [levelsFor] projects a colour's chromaticity onto that line — the nearest mix, a saturated
 *    colour landing on the end nearer its hue (red the warm white, blue the cold) — and folds the
 *    colour's level in by setting the brighter white to the colour's brightest channel. Black is
 *    both whites off.
 *  - [colourOf] is the inverse: the colour two levels make, its brightest channel the brighter
 *    level. What a head's colour reads back as, whoever set the sliders.
 *
 * A colour already on the line survives the round trip to the byte. Colour values are drive
 * levels, as they are on an RGB head (`#ff0000` is the red emitter at full), so there is no
 * gamma here. [kelvinOf] names a mix by the mired-weighted temperature between the two whites —
 * a label, not a colorimetric CCT.
 *
 * `src/test/resources/fixture/whiteMix.fixture.json` pins this and `frontend/src/lib/whiteMix.ts`
 * to one answer.
 */
class WhiteMix(val warmKelvin: Int, val coldKelvin: Int) {
    init {
        require(warmKelvin < coldKelvin) { "warm white ($warmKelvin K) must be warmer than cold ($coldKelvin K)" }
    }

    /** The two whites' channel levels. */
    data class Levels(val warm: UByte, val cold: UByte)

    private val warm = adaptedRgb(warmKelvin.toDouble())
    private val cold = adaptedRgb(coldKelvin.toDouble())
    private val warmSum = warm.sum()
    private val coldSum = cold.sum()

    // The line between the two whites in chromaticity, fixed for the pair.
    private val warmX = warm[0] / warmSum
    private val warmY = warm[1] / warmSum
    private val lineX = cold[0] / coldSum - warmX
    private val lineY = cold[1] / coldSum - warmY
    private val lineLengthSquared = lineX * lineX + lineY * lineY

    /** The nearest mix to [colour], its brighter white at [colour]'s brightest channel. */
    fun levelsFor(colour: Color): Levels {
        val peak = maxOf(colour.red, colour.green, colour.blue) / 255.0
        if (peak == 0.0) return Levels(0u, 0u)
        val target = doubleArrayOf(colour.red / 255.0, colour.green / 255.0, colour.blue / 255.0)
        return levelsForShare(coldShareOf(target), peak)
    }

    /** The colour [warmLevel] and [coldLevel] make. */
    fun colourOf(warmLevel: UByte, coldLevel: UByte): Color {
        val a = warmLevel.toDouble() / 255.0
        val c = coldLevel.toDouble() / 255.0
        val peak = max(a, c)
        if (peak == 0.0) return Color(0, 0, 0)
        val mixed = DoubleArray(3) { a * warm[it] + c * cold[it] }
        val brightest = mixed.max()
        val (r, g, b) = mixed.map { byte(it / brightest * peak) }
        return Color(r, g, b)
    }

    /**
     * The temperature a mix is labelled with, or null when both whites are off. Read from the two
     * bytes, so it is coarse when they are low: at a few percent one step moves it by tens of kelvin.
     */
    fun kelvinOf(levels: Levels): Int? {
        val a = levels.warm.toDouble()
        val c = levels.cold.toDouble()
        if (a == 0.0 && c == 0.0) return null
        val share = c * coldSum / (a * warmSum + c * coldSum)
        val mired = (1 - share) * MIRED / warmKelvin + share * MIRED / coldKelvin
        return (MIRED / mired).roundToInt()
    }

    /**
     * Where [target]'s chromaticity falls between the warm white (0) and the cold (1), clamped to
     * the line: the cold white's share of the mix's chromaticity.
     */
    private fun coldShareOf(target: DoubleArray): Double {
        val sum = target.sum()
        val along = ((target[0] / sum - warmX) * lineX + (target[1] / sum - warmY) * lineY) / lineLengthSquared
        return along.coerceIn(0.0, 1.0)
    }

    /**
     * The levels whose mix sits at [share] along the line, the brighter at [peak]. A white
     * weighs into the mix's chromaticity by its level times its channel sum, so the levels are
     * in proportion to each side's share over that sum.
     */
    private fun levelsForShare(share: Double, peak: Double): Levels {
        val a = (1 - share) / warmSum
        val c = share / coldSum
        val scale = peak / max(a, c)
        return Levels(byte(a * scale).toUByte(), byte(c * scale).toUByte())
    }

    private fun byte(x: Double): Int = (x * 255).roundToInt().coerceIn(0, 255)

    companion object {
        private const val MIRED = 1_000_000.0

        /** The adapted white, `ADAPTED_WHITE_K` in `frontend/src/lib/lamp.ts`. */
        private const val ADAPTED_WHITE_KELVIN = 5000.0
        private val adaptedWhite = kelvinToLinearRgb(ADAPTED_WHITE_KELVIN)

        /** [kelvin] against [ADAPTED_WHITE_KELVIN], linear, brightest channel 1 — the view's `lampLinearRgb`. */
        private fun adaptedRgb(kelvin: Double): DoubleArray {
            val c = kelvinToLinearRgb(kelvin)
            val adapted = DoubleArray(3) { c[it] / adaptedWhite[it] }
            val brightest = adapted.max()
            return DoubleArray(3) { adapted[it] / brightest }
        }

        /**
         * The black body at [kelvin] (1000–25000 K) in **linear** sRGB, its brightest channel 1 —
         * Kim et al.'s cubic fit to the Planckian locus in CIE 1931, then XYZ at Y = 1 to linear
         * sRGB, a channel below zero clamped. The same fit as the Stage view's lamps
         * (`frontend/src/lib/lamp.ts`, `kelvinToLinearRgb`).
         */
        internal fun kelvinToLinearRgb(kelvin: Double): DoubleArray {
            val t = kelvin.coerceIn(1000.0, 25000.0)
            val x = if (t <= 4000) {
                -0.2661239e9 / t.pow(3) - 0.2343589e6 / t.pow(2) + 0.8776956e3 / t + 0.17991
            } else {
                -3.0258469e9 / t.pow(3) + 2.1070379e6 / t.pow(2) + 0.2226347e3 / t + 0.24039
            }
            val y = when {
                t <= 2222 -> -1.1063814 * x.pow(3) - 1.3481102 * x.pow(2) + 2.18555832 * x - 0.20219683
                t <= 4000 -> -0.9549476 * x.pow(3) - 1.37418593 * x.pow(2) + 2.09137015 * x - 0.16748867
                else -> 3.081758 * x.pow(3) - 5.8733867 * x.pow(2) + 3.75112997 * x - 0.37001483
            }
            val bigX = x / y
            val bigZ = (1 - x - y) / y
            val r = max(0.0, 3.2404542 * bigX - 1.5371385 - 0.4985314 * bigZ)
            val g = max(0.0, -0.969266 * bigX + 1.8760108 + 0.041556 * bigZ)
            val b = max(0.0, 0.0556434 * bigX - 0.2040259 + 1.0572252 * bigZ)
            val brightest = maxOf(r, g, b)
            return doubleArrayOf(r / brightest, g / brightest, b / brightest)
        }
    }
}
