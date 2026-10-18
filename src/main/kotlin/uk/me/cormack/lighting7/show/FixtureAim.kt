package uk.me.cormack.lighting7.show

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One pan or tilt axis's **travel**, in degrees, as its `@FixtureProperty(degMin =, degMax =)`
 * declares it — a mover's pan 0–540, tilt 0–270. The mechanical centre (the DMX midpoint) is the
 * middle of the range. [zeroDeg] is where the head sits at a signed angle of 0, exactly as the
 * Stage view's `axisZeroDeg` reads it: the declared `forwardDeg`, else the centre.
 */
data class AimAxis(val degMin: Double, val degMax: Double, val forwardDeg: Double? = null) {
    val centreDeg: Double get() = (degMin + degMax) / 2
    val halfSpanDeg: Double get() = abs(degMax - degMin) / 2
    val zeroDeg: Double get() = forwardDeg ?: centreDeg

    /** How far a signed angle about [zeroDeg] lies outside the travel; 0 inside it. */
    fun excessDeg(signedDeg: Double): Double {
        val travel = zeroDeg + signedDeg
        return (minOf(degMin, degMax) - travel).coerceAtLeast(0.0) + (travel - maxOf(degMin, degMax)).coerceAtLeast(0.0)
    }
}

/** What aiming a head at a point came to. Degrees are **travel** degrees, the axis's own scale. */
sealed interface AimSolution {
    data class Aimed(val panDeg: Double, val tiltDeg: Double) : AimSolution

    /**
     * No pan/tilt inside the travel reaches the point. The nearest candidate is given as signed
     * angles about each axis's centre, so a message can say how far out it is.
     */
    data class OutOfReach(val panSignedDeg: Double, val tiltSignedDeg: Double) : AimSolution

    /** The point is where the fixture is — there is no direction to aim in. */
    data object AtFixture : AimSolution
}

/**
 * The pan and tilt that point a moving head at [target] — the inverse of the Stage view's own
 * drawing of a head, so a head aimed here is drawn with its beam through the point.
 *
 * The Stage view (`frontend/src/components/stage3d/FixtureModel.tsx`) builds a head as: the body
 * at the fixture's world position, rotated by the patch's base orientation — `Euler(pitch, yaw, roll,
 * 'YXZ')`, i.e. `Ry(yaw) · Rx(pitch) · Rz(roll)` in three.js space — then a yoke panning about the body's
 * own +Y and a head tilting about X inside it (`panTiltToDir` / `headQuaternionFor` in
 * `lib/stageCoords.ts`). At signed pan 0 / tilt 0 (each axis's [AimAxis.zeroDeg], DMX mid-travel
 * unless the type declares a front) the beam runs up the body's +Y axis; a hung mover is
 * `basePitchDeg = 180`. A rigging's pose places the body (through
 * [worldPosition]) but does **not** rotate it — the view draws it that way, so so does this.
 *
 * So the beam direction is `Ry(yaw) · Rx(pitch) · Rz(roll) · Ry(pan) · Rx(tilt) · (0, 1, 0)`, and this
 * solves it backwards in the same space, term for term, as [worldPosition] does: bring the
 * direction into the body's frame, where it is `(sin t · sin p, cos t, sin t · cos p)`.
 *
 * That has two families of answers — tilt one way and pan to it, or tilt the other way and pan
 * half a turn further — and pan repeats every 360°. Every candidate inside both axes' travel is
 * considered and the one nearest each axis's zero (least `|pan| + |tilt|`) wins — the centre of
 * travel, or a pan's declared front, where Locate leaves the head — so the answer is the same for
 * the same rig every time and the switch between the two families never falls on the front. Straight
 * along the body axis, pan is arbitrary and is left at its zero.
 *
 * [near] scores the candidates against a signed pan and tilt instead of the zero, and is where pan
 * is left straight along the axis: [jogAim] passes where the head already is, so a head moved a
 * little at a time never jumps to the other family half a turn away.
 *
 * Aimed from the fixture's placement point. The drawn head pivots a few centimetres from it along
 * the body axis, so the drawn beam passes that close to the point rather than exactly through it;
 * a real head is only as close as the placement was measured.
 */
fun aimAt(
    from: StagePoint,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    target: StagePoint,
    pan: AimAxis,
    tilt: AimAxis,
    baseRollDeg: Double? = null,
    near: SignedAim? = null,
): AimSolution {
    // The direction in three.js space: (x, z, −y).
    var dx = target.x - from.x
    var dy = target.z - from.z
    var dz = -(target.y - from.y)
    val length = sqrt(dx * dx + dy * dy + dz * dz)
    if (length < MIN_AIM_DISTANCE_M) return AimSolution.AtFixture
    dx /= length; dy /= length; dz /= length

    // Into the body's frame: Rz(−roll) · Rx(−pitch) · Ry(−yaw).
    val yaw = Math.toRadians(baseYawDeg ?: 0.0)
    val pitch = Math.toRadians(basePitchDeg ?: 0.0)
    val roll = Math.toRadians(baseRollDeg ?: 0.0)
    val x1 = dx * cos(yaw) - dz * sin(yaw)
    val z1 = dx * sin(yaw) + dz * cos(yaw)
    val y1 = dy
    val y2 = y1 * cos(pitch) + z1 * sin(pitch)
    val lz = -y1 * sin(pitch) + z1 * cos(pitch)
    val lx = x1 * cos(roll) + y2 * sin(roll)
    val ly = -x1 * sin(roll) + y2 * cos(roll)

    val tilt0 = Math.toDegrees(acos(ly.coerceIn(-1.0, 1.0)))
    val alongAxis = sqrt(lx * lx + lz * lz) < 1e-9

    // Signed degrees about each axis's zero, the geometry's own reference.
    val nearPan = near?.panDeg ?: 0.0
    val nearTilt = near?.tiltDeg ?: 0.0
    data class Candidate(val panSigned: Double, val tiltSigned: Double) {
        val cost: Double get() = abs(panSigned - nearPan) + abs(tiltSigned - nearTilt)
        val excess: Double get() = pan.excessDeg(panSigned) + tilt.excessDeg(tiltSigned)
    }

    val candidates = buildList {
        for (sign in intArrayOf(1, -1)) {
            val t = sign * tilt0
            val p0 = if (alongAxis) nearPan else Math.toDegrees(atan2(sign * lx, sign * lz))
            for (turns in -PAN_TURNS..PAN_TURNS) add(Candidate(p0 + 360.0 * turns, t))
        }
    }
    val reachable = candidates.filter { it.excess <= RANGE_EPSILON_DEG }
    val best = reachable.minByOrNull { it.cost }
        ?: return candidates.minBy { it.excess }.let {
            AimSolution.OutOfReach(it.panSigned + pan.zeroDeg - pan.centreDeg, it.tiltSigned + tilt.zeroDeg - tilt.centreDeg)
        }

    return AimSolution.Aimed(
        panDeg = (pan.zeroDeg + best.panSigned).coerceIn(minOf(pan.degMin, pan.degMax), maxOf(pan.degMin, pan.degMax)),
        tiltDeg = (tilt.zeroDeg + best.tiltSigned).coerceIn(minOf(tilt.degMin, tilt.degMax), maxOf(tilt.degMin, tilt.degMax)),
    )
}

/** A pan and tilt as signed degrees about each axis's [AimAxis.zeroDeg]. */
data class SignedAim(val panDeg: Double, val tiltDeg: Double)

/**
 * The beam direction, as a unit vector in stage coordinates, of a head with base orientation
 * [baseYawDeg] / [basePitchDeg] / [baseRollDeg] at **signed** pan and tilt (degrees about each axis's [AimAxis.zeroDeg]) —
 * the forward half of [aimAt], and the Stage view's `panTiltToDir` composed with the body's base
 * rotation.
 */
fun beamDirection(
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    panSignedDeg: Double,
    tiltSignedDeg: Double,
    baseRollDeg: Double? = null,
): StagePoint {
    val p = Math.toRadians(panSignedDeg)
    val t = Math.toRadians(tiltSignedDeg)
    // Rx(tilt) · (0, 1, 0), then Ry(pan).
    val hx0 = 0.0
    val hy0 = cos(t)
    val hz0 = sin(t)
    val hxp = hx0 * cos(p) + hz0 * sin(p)
    val hz1 = -hx0 * sin(p) + hz0 * cos(p)
    // Rz(roll), then Rx(pitch), then Ry(yaw).
    val roll = Math.toRadians(baseRollDeg ?: 0.0)
    val hx1 = hxp * cos(roll) - hy0 * sin(roll)
    val hy1 = hxp * sin(roll) + hy0 * cos(roll)
    val pitch = Math.toRadians(basePitchDeg ?: 0.0)
    val yaw = Math.toRadians(baseYawDeg ?: 0.0)
    val y2 = hy1 * cos(pitch) - hz1 * sin(pitch)
    val z2 = hy1 * sin(pitch) + hz1 * cos(pitch)
    val x3 = hx1 * cos(yaw) + z2 * sin(yaw)
    val z3 = -hx1 * sin(yaw) + z2 * cos(yaw)
    // Back to stage coordinates from three.js's (x, z, −y).
    return StagePoint(x = x3, y = -z3, z = y2)
}

/**
 * Where a head's beam lands for a jog: on the horizontal working plane at [planeZ] when the beam
 * goes down onto it from above within [JOG_MAX_LANDING_M], else a point on the beam ([onPlane]
 * false) — [JOG_MAX_LANDING_M] along it for a beam just below level, whose landing would be so far
 * off that a drag barely turned it, and [JOG_FAR_M] for a head below the plane or one pointing at or
 * above the horizon, so every head still has a point to move.
 */
data class JogLanding(val point: StagePoint, val onPlane: Boolean)

/** [jogLanding] for a head at [from] turned [base] (signed pan and tilt) from its zero. */
fun jogLanding(
    from: StagePoint,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    base: SignedAim,
    planeZ: Double,
    baseRollDeg: Double? = null,
): JogLanding {
    val d = beamDirection(baseYawDeg, basePitchDeg, base.panDeg, base.tiltDeg, baseRollDeg)
    fun along(k: Double) = StagePoint(from.x + k * d.x, from.y + k * d.y, from.z + k * d.z)
    if (d.z < 0.0 && from.z > planeZ) {
        val k = (planeZ - from.z) / d.z
        return if (k <= JOG_MAX_LANDING_M) {
            JogLanding(StagePoint(from.x + k * d.x, from.y + k * d.y, planeZ), onPlane = true)
        } else {
            JogLanding(along(JOG_MAX_LANDING_M), onPlane = false)
        }
    }
    return JogLanding(along(JOG_FAR_M), onPlane = false)
}

/** What a jog came to: the aim, and the point it was aimed at. */
data class JogResult(val solution: AimSolution, val target: JogLanding)

/**
 * A **stage-relative jog**: the head's landing point at [base] ([jogLanding]) moved by [dx] (+x,
 * audience-right — stage left) and [dy] (+y, upstage) metres, and the head aimed at the moved point
 * by [aimAt], choosing the candidate nearest [near] — the previous step of the gesture — else
 * nearest [base]. So every head's landing point moves by the same distance whatever its mount, yaw
 * or declared front, and a drag across a hung head's front never flips it half a turn.
 */
fun jogAim(
    from: StagePoint,
    baseYawDeg: Double?,
    basePitchDeg: Double?,
    base: SignedAim,
    dx: Double,
    dy: Double,
    planeZ: Double,
    pan: AimAxis,
    tilt: AimAxis,
    near: SignedAim? = null,
    baseRollDeg: Double? = null,
): JogResult {
    val start = jogLanding(from, baseYawDeg, basePitchDeg, base, planeZ, baseRollDeg)
    val target = StagePoint(start.point.x + dx, start.point.y + dy, start.point.z)
    val solution = aimAt(from, baseYawDeg, basePitchDeg, target, pan, tilt, baseRollDeg, near ?: base)
    return JogResult(solution, JogLanding(target, start.onPlane))
}

/** The working plane a jog moves landing points across when none is given: head height. */
const val JOG_DEFAULT_PLANE_Z = 1.7

/** How far along the beam a jog's point is taken when the beam does not land on the plane. */
const val JOG_FAR_M = 12.0

/** The farthest a jog lands a beam on the plane — a long throw from a balcony is well inside it. */
const val JOG_MAX_LANDING_M = 40.0

/** Closer than this, a point is the fixture itself. */
private const val MIN_AIM_DISTANCE_M = 1e-3

/** Slack on the travel limits, for a point exactly at one end of an axis. */
private const val RANGE_EPSILON_DEG = 1e-6

/** How many whole turns either way pan is tried at — enough for any real pan (the widest is 630°). */
private const val PAN_TURNS = 2
