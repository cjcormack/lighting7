package uk.me.cormack.lighting7.fx.effects

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.fx.Effect
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import kotlin.math.*

/**
 * Circular pan/tilt movement.
 *
 * Traces a circle centered at the specified position.
 *
 * @param panCenter Center pan position (default 128)
 * @param tiltCenter Center tilt position (default 128)
 * @param panRadius Radius of pan movement (default 64)
 * @param tiltRadius Radius of tilt movement (default 64)
 */
data class Circle(
    val panCenter: UByte = 128u,
    val tiltCenter: UByte = 128u,
    val panRadius: UByte = 64u,
    val tiltRadius: UByte = 64u
) : Effect {
    override val name = "Circle"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val angle = phase * 2 * PI
        val pan = (panCenter.toInt() + panRadius.toInt() * cos(angle))
            .toInt().coerceIn(0, 255).toUByte()
        val tilt = (tiltCenter.toInt() + tiltRadius.toInt() * sin(angle))
            .toInt().coerceIn(0, 255).toUByte()
        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Figure-8 movement pattern.
 *
 * Traces a figure-8 (lemniscate) shape.
 *
 * @param panCenter Center pan position (default 128)
 * @param tiltCenter Center tilt position (default 128)
 * @param panRadius Horizontal extent of the figure-8 (default 64)
 * @param tiltRadius Vertical extent of the figure-8 (default 32)
 */
data class Figure8(
    val panCenter: UByte = 128u,
    val tiltCenter: UByte = 128u,
    val panRadius: UByte = 64u,
    val tiltRadius: UByte = 32u
) : Effect {
    override val name = "Figure 8"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val angle = phase * 2 * PI
        // sin for horizontal, sin(2x) for vertical creates figure-8
        val pan = (panCenter.toInt() + panRadius.toInt() * sin(angle))
            .toInt().coerceIn(0, 255).toUByte()
        val tilt = (tiltCenter.toInt() + tiltRadius.toInt() * sin(2 * angle))
            .toInt().coerceIn(0, 255).toUByte()
        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Linear sweep between two positions.
 *
 * Moves from start to end position, optionally returning.
 *
 * @param startPan Starting pan position
 * @param startTilt Starting tilt position
 * @param endPan Ending pan position
 * @param endTilt Ending tilt position
 * @param curve Easing curve for movement (default SINE_IN_OUT)
 * @param pingPong If true, returns to start (default true)
 */
data class Sweep(
    val startPan: UByte,
    val startTilt: UByte,
    val endPan: UByte,
    val endTilt: UByte,
    val curve: EasingCurve = EasingCurve.SINE_IN_OUT,
    val pingPong: Boolean = true
) : Effect {
    override val name = "Sweep"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val effectivePhase = if (pingPong && phase > 0.5) {
            1.0 - (phase - 0.5) * 2
        } else if (pingPong) {
            phase * 2
        } else {
            phase
        }

        val easedPhase = curve.apply(effectivePhase)

        val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase)
            .toInt().coerceIn(0, 255).toUByte()
        val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase)
            .toInt().coerceIn(0, 255).toUByte()

        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Pan-only sweep.
 *
 * Horizontal sweep while maintaining a fixed tilt.
 *
 * @param startPan Starting pan position
 * @param endPan Ending pan position
 * @param tilt Fixed tilt position (default 128)
 * @param curve Easing curve for movement (default SINE_IN_OUT)
 * @param pingPong If true, returns to start (default true)
 */
data class PanSweep(
    val startPan: UByte,
    val endPan: UByte,
    val tilt: UByte = 128u,
    val curve: EasingCurve = EasingCurve.SINE_IN_OUT,
    val pingPong: Boolean = true
) : Effect {
    override val name = "Pan Sweep"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val effectivePhase = if (pingPong && phase > 0.5) {
            1.0 - (phase - 0.5) * 2
        } else if (pingPong) {
            phase * 2
        } else {
            phase
        }

        val easedPhase = curve.apply(effectivePhase)
        val pan = (startPan.toInt() + (endPan.toInt() - startPan.toInt()) * easedPhase)
            .toInt().coerceIn(0, 255).toUByte()

        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Tilt-only sweep.
 *
 * Vertical sweep while maintaining a fixed pan.
 *
 * @param startTilt Starting tilt position
 * @param endTilt Ending tilt position
 * @param pan Fixed pan position (default 128)
 * @param curve Easing curve for movement (default SINE_IN_OUT)
 * @param pingPong If true, returns to start (default true)
 */
data class TiltSweep(
    val startTilt: UByte,
    val endTilt: UByte,
    val pan: UByte = 128u,
    val curve: EasingCurve = EasingCurve.SINE_IN_OUT,
    val pingPong: Boolean = true
) : Effect {
    override val name = "Tilt Sweep"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        val effectivePhase = if (pingPong && phase > 0.5) {
            1.0 - (phase - 0.5) * 2
        } else if (pingPong) {
            phase * 2
        } else {
            phase
        }

        val easedPhase = curve.apply(effectivePhase)
        val tilt = (startTilt.toInt() + (endTilt.toInt() - startTilt.toInt()) * easedPhase)
            .toInt().coerceIn(0, 255).toUByte()

        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Random position movement.
 *
 * Produces pseudo-random but deterministic position changes.
 *
 * @param panCenter Center pan position (default 128)
 * @param tiltCenter Center tilt position (default 128)
 * @param panRange Range of pan movement (default 64)
 * @param tiltRange Range of tilt movement (default 64)
 */
data class RandomPosition(
    val panCenter: UByte = 128u,
    val tiltCenter: UByte = 128u,
    val panRange: UByte = 64u,
    val tiltRange: UByte = 64u
) : Effect {
    override val name = "Random Position"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        // Pseudo-random but deterministic based on phase
        val panOffset = (sin(phase * 127.0) * panRange.toInt()).toInt()
        val tiltOffset = (sin(phase * 211.0) * tiltRange.toInt()).toInt()

        val pan = (panCenter.toInt() + panOffset).coerceIn(0, 255).toUByte()
        val tilt = (tiltCenter.toInt() + tiltOffset).coerceIn(0, 255).toUByte()

        return FxOutput.Position(pan, tilt)
    }
}

/**
 * Static position - no movement.
 *
 * Holds a fixed position, useful as a base or placeholder.
 *
 * @param pan Pan position
 * @param tilt Tilt position
 */
data class StaticPosition(
    val pan: UByte,
    val tilt: UByte
) : Effect {
    override val name = "Static Position"
    override val outputType = FxOutputType.POSITION

    override fun calculate(phase: Double): FxOutput {
        return FxOutput.Position(pan, tilt)
    }
}
