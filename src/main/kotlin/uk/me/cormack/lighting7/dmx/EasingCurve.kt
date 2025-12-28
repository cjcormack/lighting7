package uk.me.cormack.lighting7.dmx

import kotlin.math.*

/**
 * Easing curve function that maps normalized time (0.0-1.0) to
 * normalized progress (0.0-1.0).
 *
 * Used by the DMX fading system to apply different interpolation
 * curves to channel value transitions.
 */
enum class EasingCurve {
    /**
     * Linear interpolation - constant rate of change.
     */
    LINEAR {
        override fun apply(t: Double): Double = t
    },

    /**
     * Sine ease-in - starts slow, accelerates.
     */
    SINE_IN {
        override fun apply(t: Double): Double = 1 - cos((t * PI) / 2)
    },

    /**
     * Sine ease-out - starts fast, decelerates.
     */
    SINE_OUT {
        override fun apply(t: Double): Double = sin((t * PI) / 2)
    },

    /**
     * Sine ease-in-out - slow start and end, fast middle.
     */
    SINE_IN_OUT {
        override fun apply(t: Double): Double = -(cos(PI * t) - 1) / 2
    },

    /**
     * Quadratic ease-in - starts slow, accelerates quadratically.
     */
    QUAD_IN {
        override fun apply(t: Double): Double = t * t
    },

    /**
     * Quadratic ease-out - starts fast, decelerates quadratically.
     */
    QUAD_OUT {
        override fun apply(t: Double): Double = 1 - (1 - t) * (1 - t)
    },

    /**
     * Quadratic ease-in-out - slow start and end.
     */
    QUAD_IN_OUT {
        override fun apply(t: Double): Double =
            if (t < 0.5) 2 * t * t else 1 - (-2 * t + 2).pow(2) / 2
    },

    /**
     * Cubic ease-in - starts slow, accelerates cubically.
     */
    CUBIC_IN {
        override fun apply(t: Double): Double = t * t * t
    },

    /**
     * Cubic ease-out - starts fast, decelerates cubically.
     */
    CUBIC_OUT {
        override fun apply(t: Double): Double = 1 - (1 - t).pow(3)
    },

    /**
     * Cubic ease-in-out - slow start and end, cubic acceleration.
     */
    CUBIC_IN_OUT {
        override fun apply(t: Double): Double =
            if (t < 0.5) 4 * t * t * t else 1 - (-2 * t + 2).pow(3) / 2
    },

    /**
     * Step function - instant jump at the end.
     */
    STEP {
        override fun apply(t: Double): Double = if (t < 1.0) 0.0 else 1.0
    },

    /**
     * Step function - instant jump at halfway point.
     */
    STEP_HALF {
        override fun apply(t: Double): Double = if (t < 0.5) 0.0 else 1.0
    };

    /**
     * Apply this easing curve to a normalized time value.
     *
     * @param t Normalized time from 0.0 (start) to 1.0 (end)
     * @return Eased progress value from 0.0 to 1.0
     */
    abstract fun apply(t: Double): Double

    /**
     * Interpolate between start and end values using this curve.
     *
     * @param start The starting value
     * @param end The ending value
     * @param progress Normalized progress from 0.0 to 1.0
     * @return Interpolated value between start and end
     */
    fun interpolate(start: Double, end: Double, progress: Double): Double {
        val clampedProgress = progress.coerceIn(0.0, 1.0)
        val easedProgress = apply(clampedProgress)
        return start + (end - start) * easedProgress
    }

    /**
     * Interpolate between start and end UByte values using this curve.
     *
     * @param start The starting value
     * @param end The ending value
     * @param progress Normalized progress from 0.0 to 1.0
     * @return Interpolated value between start and end, clamped to 0-255
     */
    fun interpolate(start: UByte, end: UByte, progress: Double): UByte {
        return interpolate(start.toDouble(), end.toDouble(), progress)
            .roundToInt()
            .coerceIn(0, 255)
            .toUByte()
    }
}
