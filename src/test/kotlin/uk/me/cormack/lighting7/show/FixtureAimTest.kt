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

    private val pan540Front180 = AimAxis(0.0, 540.0, forwardDeg = 180.0)

    @Test
    fun `a declared front is the same head yawed the other way, hung`() {
        // The Shehds: hung, it behaves as if yawed −90°. Declaring its front at 180° of 540° says the
        // same thing in the type, so every travel position points where the yawed head would.
        val random = Random(20261018)
        repeat(200) {
            val pan = random.nextDouble(0.0, 540.0)
            val tilt = random.nextDouble(0.0, 270.0)
            assertNear(
                beamDirection(-90.0, 180.0, pan - pan540.zeroDeg, tilt - tilt270.zeroDeg),
                beamDirection(0.0, 180.0, pan - pan540Front180.zeroDeg, tilt - tilt270.zeroDeg),
                1e-9,
                "pan $pan tilt $tilt:",
            )
        }
    }

    @Test
    fun `a head with a declared front is aimed about its front`() {
        val random = Random(20261019)
        var aimed = 0
        repeat(2000) {
            val from = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(0.0, 9.0))
            val to = StagePoint(random.nextDouble(-8.0, 8.0), random.nextDouble(-2.0, 10.0), random.nextDouble(-1.0, 4.0))
            val yaw = random.nextDouble(-180.0, 180.0)
            val pitch = random.nextDouble(-180.0, 180.0)
            when (val aim = aimAt(from, yaw, pitch, to, pan540Front180, tilt270)) {
                is AimSolution.Aimed -> {
                    aimed++
                    val beam = beamDirection(yaw, pitch, aim.panDeg - pan540Front180.zeroDeg, aim.tiltDeg - tilt270.zeroDeg)
                    assertNear(unit(from, to), beam, 1e-9, "yaw $yaw pitch $pitch from $from to $to:")
                    assertTrue(aim.panDeg in 0.0..540.0, "pan ${aim.panDeg}")
                }
                else -> {}
            }
        }
        assertTrue(aimed > 1500, "most directions are in reach, was $aimed")
    }

    @Test
    fun `a point along the body axis leaves a declared-front pan at its front, where Locate puts it`() {
        val aim = aimAt(StagePoint(0.0, 0.0, 0.0), 0.0, 0.0, StagePoint(0.0, 0.0, 5.0), pan540Front180, tilt270)
        assertIs<AimSolution.Aimed>(aim)
        assertEquals(180.0, aim.panDeg, 1e-9)
    }

    @Test
    fun `aims either side of a declared front stay on one answer rather than flipping half a turn`() {
        // Hung, the front's tilt plane runs up and downstage: points just either side of it are one move apart.
        for (y in listOf(0.0, 8.0)) {
            val left = aimAt(StagePoint(0.0, 4.0, 6.0), 0.0, 180.0, StagePoint(-0.01, y, 1.7), pan540Front180, tilt270)
            val right = aimAt(StagePoint(0.0, 4.0, 6.0), 0.0, 180.0, StagePoint(0.01, y, 1.7), pan540Front180, tilt270)
            assertIs<AimSolution.Aimed>(left)
            assertIs<AimSolution.Aimed>(right)
            assertTrue(abs(left.panDeg - right.panDeg) < 1.0, "y $y: pan ${left.panDeg} vs ${right.panDeg}")
            assertTrue(abs(left.tiltDeg - right.tiltDeg) < 1.0, "y $y: tilt ${left.tiltDeg} vs ${right.tiltDeg}")
        }
    }

    // ─── jogAim ─────────────────────────────────────────────────────────────

    private class JogHead(val from: StagePoint, val yaw: Double, val pitch: Double, val pan: AimAxis, val start: StagePoint)

    /** Signed pan and tilt of an [AimSolution.Aimed] about each axis's zero. */
    private fun signed(aim: AimSolution, pan: AimAxis, tilt: AimAxis = tilt270): SignedAim {
        assertIs<AimSolution.Aimed>(aim)
        return SignedAim(aim.panDeg - pan.zeroDeg, aim.tiltDeg - tilt.zeroDeg)
    }

    @Test
    fun `a jog moves every head's landing point by exactly dx and dy`() {
        val plane = JOG_DEFAULT_PLANE_Z
        val rig = listOf(
            JogHead(StagePoint(-3.0, 2.5, 6.0), 0.0, 180.0, pan540Front180, StagePoint(-2.0, 2.2, plane)), // hung Shehds
            JogHead(StagePoint(0.0, 5.0, 6.5), 0.0, 180.0, pan540, StagePoint(0.0, 4.2, plane)), // hung mover
            JogHead(StagePoint(0.0, -3.0, 7.0), 0.0, 180.0, pan540, StagePoint(0.6, 1.4, plane)), // FOH spot
            JogHead(StagePoint(4.0, 3.0, 5.5), 35.0, 160.0, pan540, StagePoint(2.5, 2.0, plane)), // yawed and tipped
        )
        for (head in rig) {
            val base = signed(aimAt(head.from, head.yaw, head.pitch, head.start, head.pan, tilt270), head.pan)
            val before = jogLanding(head.from, head.yaw, head.pitch, base, plane)
            assertTrue(before.onPlane, "${head.from} lands on the plane")
            for ((dx, dy) in listOf(1.5 to 0.0, -1.5 to 0.0, 0.0 to 0.8, -0.7 to -0.6)) {
                val jog = jogAim(head.from, head.yaw, head.pitch, base, dx, dy, plane, head.pan, tilt270)
                val after = jogLanding(head.from, head.yaw, head.pitch, signed(jog.solution, head.pan), plane)
                assertTrue(after.onPlane)
                assertNear(StagePoint(before.point.x + dx, before.point.y + dy, plane), after.point, 1e-6, "${head.from} by ($dx, $dy):")
            }
        }
    }

    @Test
    fun `a drag across a declared front never flips pan half a turn`() {
        // A hung Shehds (front at 180° of 540°), dragged across the stage and up and down it along
        // lines just beside the deck beneath it, so the beam's bearing sweeps half a turn under the
        // head — across its front on one of them, wherever the front faces.
        val from = StagePoint(-3.0, 2.5, 6.0)
        val plane = JOG_DEFAULT_PLANE_Z
        var flippedWithoutNear = false
        for ((along, offset) in listOf(1.0 to 0.0, 0.0 to 1.0).flatMap { a -> listOf(a to -0.3, a to 0.3) }) {
            val (ax, ay) = along
            val start = StagePoint(from.x - 4.0 * ax + offset * ay, from.y - 4.0 * ay + offset * ax, plane)
            val base = signed(aimAt(from, 0.0, 180.0, start, pan540Front180, tilt270), pan540Front180)
            var previous = base
            var previousZeroScored = base
            var t = 0.0
            while (t <= 8.0) {
                val jog = jogAim(from, 0.0, 180.0, base, t * ax, t * ay, plane, pan540Front180, tilt270, near = previous)
                val step = signed(jog.solution, pan540Front180)
                assertTrue(abs(step.panDeg - previous.panDeg) < 30.0, "along $along offset $offset at $t: pan ${previous.panDeg} → ${step.panDeg}")
                assertTrue(abs(step.tiltDeg - previous.tiltDeg) < 30.0, "along $along offset $offset at $t: tilt ${previous.tiltDeg} → ${step.tiltDeg}")
                previous = step

                val zeroScored = signed(aimAt(from, 0.0, 180.0, jog.target.point, pan540Front180, tilt270), pan540Front180)
                if (abs(zeroScored.panDeg - previousZeroScored.panDeg) > 90.0) flippedWithoutNear = true
                previousZeroScored = zeroScored
                t += 0.05
            }
        }
        assertTrue(flippedWithoutNear, "scored against the zero, one of the same drags flips — which is what near is for")
    }

    @Test
    fun `a beam that misses the plane moves a point twelve metres along it`() {
        // A standing floor mover pointing up and upstage: its beam never comes down to head height.
        val from = StagePoint(-4.2, 6.6, 0.3)
        val base = SignedAim(25.0, 55.0)
        val landing = jogLanding(from, 0.0, 0.0, base, JOG_DEFAULT_PLANE_Z)
        assertTrue(!landing.onPlane)
        val beam = beamDirection(0.0, 0.0, base.panDeg, base.tiltDeg)
        assertNear(StagePoint(from.x + JOG_FAR_M * beam.x, from.y + JOG_FAR_M * beam.y, from.z + JOG_FAR_M * beam.z), landing.point, 1e-9)

        val jog = jogAim(from, 0.0, 0.0, base, 1.0, 0.0, JOG_DEFAULT_PLANE_Z, pan540, tilt270)
        assertTrue(!jog.target.onPlane)
        assertNear(StagePoint(landing.point.x + 1.0, landing.point.y, landing.point.z), jog.target.point, 1e-9)
        val moved = signed(jog.solution, pan540)
        assertNear(unit(from, jog.target.point), beamDirection(0.0, 0.0, moved.panDeg, moved.tiltDeg), 1e-9)
    }

    @Test
    fun `near is where pan is left straight along the axis`() {
        val aim = aimAt(StagePoint(0.0, 0.0, 0.0), 0.0, 0.0, StagePoint(0.0, 0.0, 5.0), pan540, tilt270, near = SignedAim(40.0, 0.0))
        assertIs<AimSolution.Aimed>(aim)
        assertEquals(270.0 + 40.0, aim.panDeg, 1e-9)
    }

    @Test
    fun `a beam just below level lands no farther than the cap, on the beam`() {
        // 8 m up, 1.5° below level: on the plane it would land some 240 m off.
        val from = StagePoint(0.0, -3.0, 8.0)
        val tilt = SignedAim(0.0, 90.0 + 1.5)
        val beam = beamDirection(0.0, 0.0, tilt.panDeg, tilt.tiltDeg)
        assertTrue(beam.z < 0.0 && beam.z > -0.03, "the beam dips just below level: $beam")
        val landing = jogLanding(from, 0.0, 0.0, tilt, JOG_DEFAULT_PLANE_Z)
        assertTrue(!landing.onPlane)
        assertNear(
            StagePoint(from.x + JOG_MAX_LANDING_M * beam.x, from.y + JOG_MAX_LANDING_M * beam.y, from.z + JOG_MAX_LANDING_M * beam.z),
            landing.point,
            1e-9,
        )
    }
}
