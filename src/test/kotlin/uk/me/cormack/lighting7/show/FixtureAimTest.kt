package uk.me.cormack.lighting7.show

import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * [aimAt] and [beamDirection]. The pinned vectors are the Stage view's own (`panTiltToDir`'s
 * documented directions in `frontend/src/lib/stageCoords.ts`, and the `YXZ` base rotation
 * `FixtureModel` gives the body), so a head the desk aims is drawn pointing where it was aimed.
 */
class FixtureAimTest {

    private val pan540 = AimAxis(0.0, 540.0)
    private val tilt270 = AimAxis(0.0, 270.0)

    private fun assertNear(expected: StagePoint, actual: StagePoint, tolerance: Double = 1e-9, message: String = "") {
        val ok = abs(expected.x - actual.x) < tolerance && abs(expected.y - actual.y) < tolerance && abs(expected.z - actual.z) < tolerance
        assertTrue(ok, "$message expected $expected, was $actual")
    }

    private fun unit(from: StagePoint, to: StagePoint): StagePoint {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dz = to.z - from.z
        val l = sqrt(dx * dx + dy * dy + dz * dz)
        return StagePoint(dx / l, dy / l, dz / l)
    }

    @Test
    fun `beamDirection agrees with the Stage view's panTiltToDir on a standing head`() {
        // Rest is up the body; tilt +90 is downstage (−y); tilt −90 upstage; tilt 180 down;
        // pan +90 swings a downstage beam toward audience-right (+x).
        assertNear(StagePoint(0.0, 0.0, 1.0), beamDirection(0.0, 0.0, 0.0, 0.0))
        assertNear(StagePoint(0.0, -1.0, 0.0), beamDirection(0.0, 0.0, 0.0, 90.0))
        assertNear(StagePoint(0.0, 1.0, 0.0), beamDirection(0.0, 0.0, 0.0, -90.0))
        assertNear(StagePoint(0.0, 0.0, -1.0), beamDirection(0.0, 0.0, 0.0, 180.0))
        assertNear(StagePoint(1.0, 0.0, 0.0), beamDirection(0.0, 0.0, 90.0, 90.0))
    }

    @Test
    fun `a hung head rests pointing straight down`() {
        assertNear(StagePoint(0.0, 0.0, -1.0), beamDirection(0.0, 180.0, 0.0, 0.0))
    }

    @Test
    fun `a hung head aimed at the deck beneath it sits at the centre of both axes`() {
        val aim = aimAt(StagePoint(1.0, 4.0, 6.0), 0.0, 180.0, StagePoint(1.0, 4.0, 0.0), pan540, tilt270)
        assertIs<AimSolution.Aimed>(aim)
        assertEquals(270.0, aim.panDeg, 1e-9)
        assertEquals(135.0, aim.tiltDeg, 1e-9)
    }

    @Test
    fun `a standing head aimed downstage level with it tilts toward the audience, not the long way round`() {
        val aim = aimAt(StagePoint(0.0, 5.0, 1.0), 0.0, 0.0, StagePoint(0.0, 0.0, 1.0), pan540, tilt270)
        assertIs<AimSolution.Aimed>(aim)
        // Signed pan 0 / tilt +90 costs 90; tilt −90 with pan ±180 costs 270.
        assertEquals(270.0, aim.panDeg, 1e-9)
        assertEquals(135.0 + 90.0, aim.tiltDeg, 1e-9)
    }

    @Test
    fun `the aimed pan and tilt point the drawn beam at the point, whatever the mount`() {
        val random = Random(20260928)
        var aimed = 0
        repeat(2000) {
            val from = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(0.0, 9.0))
            val to = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(-1.0, 4.0))
            val yaw = random.nextDouble(-180.0, 180.0)
            val pitch = random.nextDouble(-180.0, 180.0)
            val direction = unit(from, to)
            when (val aim = aimAt(from, yaw, pitch, to, pan540, tilt270)) {
                is AimSolution.Aimed -> {
                    aimed++
                    val beam = beamDirection(yaw, pitch, aim.panDeg - pan540.centreDeg, aim.tiltDeg - tilt270.centreDeg)
                    assertNear(direction, beam, 1e-9, "yaw $yaw pitch $pitch from $from to $to:")
                }
                // Pan turns about the body axis, so it never changes how far off that axis a
                // direction is; a ±135° tilt misses only the 45° cone behind the base.
                is AimSolution.OutOfReach -> {
                    val axis = beamDirection(yaw, pitch, 0.0, 0.0)
                    val cosOffAxis = axis.x * direction.x + axis.y * direction.y + axis.z * direction.z
                    assertTrue(cosOffAxis < cos(Math.toRadians(135.0)) + 1e-9, "only the cone behind the base is out of reach: $to from $from")
                }
                AimSolution.AtFixture -> fail("random points are not the fixture")
            }
        }
        assertTrue(aimed > 1500, "most directions are in reach, was $aimed")
    }

    @Test
    fun `a rolled mount is aimed through its roll too`() {
        val random = Random(20260929)
        var aimed = 0
        repeat(2000) {
            val from = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(0.0, 9.0))
            val to = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(-1.0, 4.0))
            val yaw = random.nextDouble(-180.0, 180.0)
            val pitch = random.nextDouble(-180.0, 180.0)
            val roll = random.nextDouble(-180.0, 180.0)
            val aim = aimAt(from, yaw, pitch, to, pan540, tilt270, roll)
            if (aim is AimSolution.Aimed) {
                aimed++
                val beam = beamDirection(yaw, pitch, aim.panDeg - pan540.centreDeg, aim.tiltDeg - tilt270.centreDeg, roll)
                assertNear(unit(from, to), beam, 1e-9, "yaw $yaw pitch $pitch roll $roll from $from to $to:")
            }
        }
        assertTrue(aimed > 1500, "most directions are in reach, was $aimed")
    }

    @Test
    fun `roll turns the body about three-js Z before pitch and yaw`() {
        // A standing head (beam up its body +Y, i.e. stage +z) rolled 90° lies on its side: three.js
        // Rz(90°) takes +Y to −X, which is stage audience-left.
        assertNear(StagePoint(-1.0, 0.0, 0.0), beamDirection(0.0, 0.0, 0.0, 0.0, 90.0))
        // Then yawed 90° (Ry takes three.js −X to +Z, i.e. stage −y): pointing at the audience.
        assertNear(StagePoint(0.0, -1.0, 0.0), beamDirection(90.0, 0.0, 0.0, 0.0, 90.0))
        // Roll 0 is exactly the unrolled mount.
        assertNear(beamDirection(30.0, 40.0, 50.0, 60.0), beamDirection(30.0, 40.0, 50.0, 60.0, 0.0))
    }

    @Test
    fun `the answer nearest the centre of travel wins, so a head is never sent round the long way`() {
        val random = Random(7)
        repeat(500) {
            val from = StagePoint(0.0, 5.0, 6.0)
            val to = StagePoint(random.nextDouble(-6.0, 6.0), random.nextDouble(0.0, 8.0), 0.0)
            val aim = aimAt(from, 0.0, 180.0, to, pan540, tilt270) as AimSolution.Aimed
            val panSigned = aim.panDeg - pan540.centreDeg
            val tiltSigned = aim.tiltDeg - tilt270.centreDeg
            // Anything on the deck below a hung head is less than 90° off its axis, and pan need
            // never pass half a turn to face it.
            assertTrue(abs(tiltSigned) < 90.0, "tilt $tiltSigned")
            assertTrue(abs(panSigned) <= 180.0 + 1e-9, "pan $panSigned")
        }
    }

    @Test
    fun `a point beyond the tilt's travel is out of reach, and says how far out`() {
        // A 0–180° tilt reaches ±90° from the body axis: a standing wash cannot look at the floor below it.
        val aim = aimAt(StagePoint(0.0, 0.0, 1.0), 0.0, 0.0, StagePoint(0.0, 0.0, 0.0), pan540, AimAxis(0.0, 180.0))
        assertIs<AimSolution.OutOfReach>(aim)
        assertEquals(180.0, abs(aim.tiltSignedDeg), 1e-9)
    }

    @Test
    fun `a head aimed behind itself tilts back rather than panning half a turn`() {
        // A scanner-like 0–180° pan (±90): pan 180 with tilt forward would be out anyway, and tilt
        // back with pan at centre is the nearer answer.
        val aim = aimAt(StagePoint(0.0, 0.0, 1.0), 0.0, 0.0, StagePoint(0.0, 5.0, 1.0), AimAxis(0.0, 180.0), tilt270)
        assertIs<AimSolution.Aimed>(aim)
        assertEquals(90.0, aim.panDeg, 1e-9)
        assertEquals(135.0 - 90.0, aim.tiltDeg, 1e-9)
    }

    @Test
    fun `a point at the fixture has no direction`() {
        val at = StagePoint(1.0, 2.0, 3.0)
        assertEquals(AimSolution.AtFixture, aimAt(at, 0.0, 180.0, at, pan540, tilt270))
    }

    @Test
    fun `a point straight along the body axis leaves pan at its centre`() {
        val aim = aimAt(StagePoint(0.0, 0.0, 0.0), 0.0, 0.0, StagePoint(0.0, 0.0, 5.0), pan540, tilt270)
        assertIs<AimSolution.Aimed>(aim)
        assertEquals(270.0, aim.panDeg, 1e-9)
        assertEquals(135.0, aim.tiltDeg, 1e-9)
    }
}
