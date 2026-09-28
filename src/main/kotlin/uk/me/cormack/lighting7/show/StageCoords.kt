package uk.me.cormack.lighting7.show

import kotlin.math.cos
import kotlin.math.sin

/** A point in stage coordinates: metres, FOH-relative, Z-up (x audience-right, y upstage, z up). */
data class StagePoint(val x: Double, val y: Double, val z: Double)

/** The parts of a rigging's pose that place what hangs on it. A null field reads as 0. */
data class RiggingPose(
    val x: Double?, val y: Double?, val z: Double?,
    val yawDeg: Double?, val pitchDeg: Double?, val rollDeg: Double?,
)

/**
 * Where a placement actually is on stage — the backend's copy of the frontend's
 * `worldPositionLighting` (`frontend/src/lib/stageCoords.ts`), which is what the Stage view draws
 * from. The two must agree: a model reading this is told the position the operator sees.
 *
 * Null when x or y is unset — the placement is unplaced, and the Stage view draws it nowhere. A
 * null z is the deck (0). With a [rigging], x/y/z are an offset in the rigging's own frame (x
 * along its length), composed with its full pose; without one they are already world coordinates.
 *
 * The frontend rotates in three.js space (Y-up, `z = −lighting y`) with a `YXZ` Euler of
 * (pitch, yaw, roll), i.e. `Ry(yaw) · Rx(pitch) · Rz(roll)` applied to the swizzled offset
 * `(x, z, −y)`. That is done here literally, in the same space and order, rather than re-derived
 * in lighting coordinates, so the two can be compared term for term.
 */
fun worldPosition(x: Double?, y: Double?, z: Double?, rigging: RiggingPose?): StagePoint? {
    if (x == null || y == null) return null
    val sz = z ?: 0.0
    if (rigging == null) return StagePoint(x, y, sz)

    val pitch = Math.toRadians(rigging.pitchDeg ?: 0.0)
    val yaw = Math.toRadians(rigging.yawDeg ?: 0.0)
    val roll = Math.toRadians(rigging.rollDeg ?: 0.0)

    // The offset in three.js space, then Rz(roll), Rx(pitch) and Ry(yaw) in turn.
    val x0 = x
    val y0 = sz
    val z0 = -y
    val x1 = x0 * cos(roll) - y0 * sin(roll)
    val y1 = x0 * sin(roll) + y0 * cos(roll)
    val z1 = z0
    val y2 = y1 * cos(pitch) - z1 * sin(pitch)
    val z2 = y1 * sin(pitch) + z1 * cos(pitch)
    val x3 = x1 * cos(yaw) + z2 * sin(yaw)
    val z3 = -x1 * sin(yaw) + z2 * cos(yaw)
    // Back to lighting coordinates, translated by the rigging's own position.
    return StagePoint(
        x = (rigging.x ?: 0.0) + x3,
        y = (rigging.y ?: 0.0) - z3,
        z = (rigging.z ?: 0.0) + y2,
    )
}
