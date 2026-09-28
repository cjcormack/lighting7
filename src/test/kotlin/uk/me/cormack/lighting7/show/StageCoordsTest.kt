package uk.me.cormack.lighting7.show

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * [worldPosition] against the frontend's `worldPositionLighting`. The expected values were
 * computed independently of the Kotlin — from three.js's own `Matrix4.makeRotationFromEuler`
 * formula for order `YXZ`, applied to the swizzled offset — so a sign or order slip in either
 * the port or the swizzle shows here rather than as a fixture the model thinks is somewhere else.
 */
class StageCoordsTest {

    private fun assertPoint(expected: StagePoint, actual: StagePoint?) {
        assertNotNull(actual)
        assertEquals(expected.x, actual.x, 1e-9, "x")
        assertEquals(expected.y, actual.y, 1e-9, "y")
        assertEquals(expected.z, actual.z, 1e-9, "z")
    }

    private fun rig(x: Double, y: Double, z: Double, yaw: Double = 0.0, pitch: Double = 0.0, roll: Double = 0.0) =
        RiggingPose(x, y, z, yaw, pitch, roll)

    @Test
    fun `an unplaced placement has no world position`() {
        assertNull(worldPosition(null, 1.0, 2.0, null))
        assertNull(worldPosition(1.0, null, 2.0, null))
        assertNull(worldPosition(null, null, null, rig(1.0, 2.0, 3.0)), "a rigging does not place what has no offset")
    }

    @Test
    fun `without a rigging the offsets are the world position, and a missing z is the deck`() {
        assertPoint(StagePoint(1.5, -2.0, 3.0), worldPosition(1.5, -2.0, 3.0, null))
        assertPoint(StagePoint(1.5, -2.0, 0.0), worldPosition(1.5, -2.0, null, null))
    }

    @Test
    fun `a rigging with no rotation translates the offset`() {
        assertPoint(StagePoint(-1.5, -6.0, 6.8), worldPosition(-1.5, 0.0, -0.2, rig(0.0, -6.0, 7.0)))
        // A pose with every field unset reads as the origin, unrotated.
        assertPoint(StagePoint(1.0, 2.0, 3.0), worldPosition(1.0, 2.0, 3.0, RiggingPose(null, null, null, null, null, null)))
    }

    @Test
    fun `yaw 90 runs the rigging upstage`() {
        // set_stage's own words: "90 = running up/downstage (a side truss or boom arm)".
        assertPoint(StagePoint(2.0, 4.0, 6.0), worldPosition(1.0, 0.0, 0.0, rig(2.0, 3.0, 6.0, yaw = 90.0)))
    }

    @Test
    fun `roll raises one end of the rigging`() {
        assertPoint(StagePoint(1.7320508075688774, 0.0, 6.0), worldPosition(2.0, 0.0, 0.0, rig(0.0, 0.0, 5.0, roll = 30.0)))
    }

    @Test
    fun `pitch turns the rigging's own y and z`() {
        assertPoint(
            StagePoint(0.0, -5.646446609406726, 7.070710678118655),
            worldPosition(0.0, 0.3, -0.2, rig(0.0, -6.0, 7.0, pitch = 45.0)),
        )
    }

    @Test
    fun `a full pose composes yaw, pitch and roll in the frontend's order`() {
        assertPoint(
            StagePoint(2.554262256276481, 2.3491861517125847, 5.842399650487387),
            worldPosition(1.5, -0.5, -0.25, rig(1.0, 2.0, 6.0, yaw = 30.0, pitch = 20.0, roll = 10.0)),
        )
    }
}
