package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.MoverHead
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A FOCUS slider's declared range (`@FixtureProperty(focusNearM =, focusFarM =, inverted =)`) over
 * its DMX `min..max` — the facts both directions of the focus mapping are made from.
 *
 * The forward direction is the Stage view's `resolveDeclaredFocusDistance`
 * (`frontend/src/components/stage3d/beamOptics.ts`): DMX runs **linearly in 1 / distance** between
 * the ends, because a focus motor drives the lens linearly and the lens's travel from its infinity
 * position is very nearly proportional to 1 / distance. [inverted] puts the far end at DMX min.
 * `src/test/resources/stage/focusInverse.fixture.json` pins this file and the view against one
 * vector, so a head focused here is drawn sharp at the distance it was focused for.
 */
data class FocusRange(
    val nearM: Double,
    val farM: Double,
    val inverted: Boolean,
    val dmxMin: Int,
    val dmxMax: Int,
) {
    /** A range a lens could have, over a slider that moves; anything else focuses nothing. */
    val usable: Boolean get() = nearM > 0.0 && farM > nearM && dmxMax > dmxMin

    /** The focal distance, metres from the aperture, at a (fractional) DMX [level]. */
    fun distanceAt(level: Double): Double {
        val p = ((level - dmxMin) / (dmxMax - dmxMin)).coerceIn(0.0, 1.0)
        val t = if (inverted) 1.0 - p else p
        return 1.0 / (1.0 / nearM + t * (1.0 / farM - 1.0 / nearM))
    }

    /**
     * The exact (fractional) DMX level that focuses at [distanceM] — [distanceAt]'s inverse — or
     * null when the distance is outside the range, which no level reaches.
     */
    fun levelFor(distanceM: Double): Double? {
        if (!usable || !distanceM.isFinite()) return null
        if (distanceM < nearM * (1 - RANGE_EPSILON) || distanceM > farM * (1 + RANGE_EPSILON)) return null
        val t = ((1.0 / distanceM - 1.0 / nearM) / (1.0 / farM - 1.0 / nearM)).coerceIn(0.0, 1.0)
        val p = if (inverted) 1.0 - t else t
        return dmxMin + p * (dmxMax - dmxMin)
    }

    /** [levelFor], rounded to the slider's own DMX step and kept inside its range. */
    fun dmxFor(distanceM: Double): Int? = levelFor(distanceM)?.roundToInt()?.coerceIn(dmxMin, dmxMax)

    companion object {
        /** Slack on the range's ends, for a distance computed to land exactly on one. */
        private const val RANGE_EPSILON = 1e-9
    }
}

/**
 * The middle of a declared range, by distance — the arithmetic mean of its ends (21 m on a
 * Revolution's 2–40 m) — as a DMX level. What Locate parks a focus at: mid-DMX would be 3.8 m on
 * the same lens, since DMX is linear in 1 / distance and so crowds the far half of the range into
 * the top of the fader.
 */
fun FocusRange.middleDmx(): Int = dmxFor((nearM + farM) / 2)
    ?: ((dmxMin + dmxMax + 1) / 2)

/**
 * The range a FOCUS property declares over [slider], or null where it declares none (or one no lens
 * has) — such a slider keeps racking over the throw in the view, and nothing solves for it.
 */
fun Fixture.Property.focusRange(slider: DmxSlider): FocusRange? {
    val near = focusNearM ?: return null
    val far = focusFarM ?: return null
    return FocusRange(near, far, inverted, slider.min.toInt(), slider.max.toInt()).takeIf { it.usable }
}

/**
 * Where a moving head's lens sits, as the Stage view draws it (fixture-optics session 1): the head
 * pivots [pivotM] up the body's own axis from the placement point (`bodies/bodyGeometry.ts`'s
 * `moverSize`, 0.6 of the unit's height) and its lens sits [lensM] along the beam from the pivot
 * (`bodies/archetype.ts`'s mover cell, half the head's length). The view measures focus from that
 * lens, so the desk does too. `src/test/resources/stage/focusInverse.fixture.json`'s `heads` pins
 * both sides.
 */
data class MoverLens(val pivotM: Double, val lensM: Double) {
    companion object {
        /** The view's default mover height, for a type whose height is not a positive number. */
        private const val DEFAULT_HEIGHT_M = 0.45

        fun of(heightM: Double, head: MoverHead): MoverLens {
            val h = heightM.takeIf { it.isFinite() && it > 0.0 } ?: DEFAULT_HEIGHT_M
            val headLength = h * when (head) {
                MoverHead.WASH -> 0.34
                MoverHead.BAR -> 0.2
                else -> 0.52
            }
            return MoverLens(pivotM = h * 0.6, lensM = headLength / 2)
        }
    }
}

/**
 * The distance from a moving head's lens to [point], for a head whose beam runs through the point —
 * *Focus here*'s point is where the drawn axis lands, and an aimed head points at its aim point. The
 * pivot is the placement [from] moved [MoverLens.pivotM] up the body's axis through its base
 * orientation (the view's mount rotation, `Ry(yaw) · Rx(pitch) · Rz(roll)`, [beamDirection] at pan
 * and tilt 0); the lens is [MoverLens.lensM] from the pivot along the beam, so the distance is the
 * pivot's less that.
 */
fun lensDistance(
    from: StagePoint,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    baseRollDeg: Double?,
    lens: MoverLens,
    point: StagePoint,
): Double {
    val axis = beamDirection(baseYawDeg, basePitchDeg, 0.0, 0.0, baseRollDeg)
    val dx = point.x - (from.x + axis.x * lens.pivotM)
    val dy = point.y - (from.y + axis.y * lens.pivotM)
    val dz = point.z - (from.z + axis.z * lens.pivotM)
    return sqrt(dx * dx + dy * dy + dz * dz) - lens.lensM
}
