import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import {
  BufferAttribute,
  BufferGeometry,
  DoubleSide,
  Group,
  Line,
  LineDashedMaterial,
  Matrix4,
  Mesh,
  MeshBasicMaterial,
  Quaternion,
  RingGeometry,
  Vector3,
} from 'three'
import { StageLabel } from './StageLabel'
import { useStageInvalidate } from './stageInvalidate'
import { NO_RAYCAST } from './raycast'
import { markBadge, markCounting, markRingRadiusM, type MoveInDarkMark } from './moveInDarkOverlay'

/**
 * One dark head's **Move in dark** mark on the Stage view (lighting7 move-in-dark plan D11, design
 * §5.5): a dashed line from its aperture along where the head points **now** — as drawn, travel
 * and all — to the first surface it meets, a ring where the move's pan and tilt land, and a badge
 * naming the cue it is for (`moveInDarkOverlay.ts`). The head's own swing is the beam director's
 * (`lib/travel.ts`); this only draws over it.
 *
 * Drawn by the fixture's beam director: [register] hands it an updater that the director calls each
 * frame right after it turns the yoke and head, so the line follows the head in the frame that
 * moves it. Both landings are [land]'s — the beam's own cast, `FixtureModel`'s `landBeam`, which the
 * pool lands by — and a moved ring asks for one more frame, so its badge is laid out where it is.
 */

/** Where a cast landed, in three.js world space: the point, the face's normal towards the light, the throw. */
export interface MarkLanding {
  point: Vector3
  normal: Vector3
  length: number
}

export type MarkLand = (origin: Vector3, dir: Vector3, out: MarkLanding) => boolean

/** Called by the beam director once per frame with the head it has just turned. */
export type MarkUpdater = (head: Group) => void

/** How far the line runs, and where the ring sits, when the beam meets nothing. */
const OPEN_AIR_M = 6
/** The ring sits this far off the face it lands on, so the surface never covers it. */
const RING_LIFT_M = 0.02
/** The badge's height above the ring. */
const BADGE_OFFSET: [number, number, number] = [0, 0.35, 0]
/** A ring moved less than this asks for no frame. */
const MOVED_EPS_M = 0.001
const COUNTDOWN_MS = 100

const MARK_COLOUR = '#818cf8'
const RING_NORMAL = new Vector3(0, 0, 1)

interface MoveInDarkMarkerProps {
  mark: MoveInDarkMark
  /** The fixture's placement group (the line and ring are drawn in its frame) and its mount. */
  rootRef: React.RefObject<Group | null>
  mountRef: React.RefObject<Group | null>
  /** The first cell's aperture in the head's frame, and which way along y the beam leaves. */
  aperture: { x: number; y: number; z: number }
  emitAxis: 1 | -1
  /** The move's pan and tilt, decoded as the director decodes the DMX. */
  panRad: number
  tiltRad: number
  /** The beam's angle, which sizes the ring as the pool would be. */
  beamDeg: number
  land: MarkLand
  register: (updater: MarkUpdater | null) => void
}

/**
 * The head's world matrix with the yoke panned to [panRad] and the head tilted to [tiltRad] — how
 * the beam director turns them (`yoke.rotation = (0, pan, 0)`, the head at [headPosition] in the
 * yoke and `head.rotation = (tilt, 0, 0)`) — under the mount's [mountWorld]. Writes [out].
 */
export function markTargetMatrix(
  mountWorld: Matrix4,
  headPosition: Vector3,
  panRad: number,
  tiltRad: number,
  out: Matrix4,
  step = new Matrix4(),
): Matrix4 {
  out.copy(mountWorld)
  out.multiply(step.makeRotationY(panRad))
  out.multiply(step.makeTranslation(headPosition.x, headPosition.y, headPosition.z))
  return out.multiply(step.makeRotationX(tiltRad))
}

export function MoveInDarkMarker({ mark, rootRef, mountRef, aperture, emitAxis, panRad, tiltRad, beamDeg, land, register }: MoveInDarkMarkerProps) {
  const invalidate = useStageInvalidate()
  const ringGroupRef = useRef<Group>(null)

  const line = useMemo(() => {
    const geometry = new BufferGeometry()
    geometry.setAttribute('position', new BufferAttribute(new Float32Array(6), 3))
    // Written in place each frame: `computeLineDistances` would allocate a fresh attribute every call.
    geometry.setAttribute('lineDistance', new BufferAttribute(new Float32Array(2), 1))
    const material = new LineDashedMaterial({ color: MARK_COLOUR, dashSize: 0.18, gapSize: 0.12, transparent: true, opacity: 0.9 })
    const l = new Line(geometry, material)
    l.raycast = NO_RAYCAST
    l.frustumCulled = false
    return l
  }, [])
  const ring = useMemo(() => {
    const mesh = new Mesh(
      new RingGeometry(0.88, 1, 48),
      new MeshBasicMaterial({ color: MARK_COLOUR, side: DoubleSide, transparent: true, opacity: 0.85, depthWrite: false }),
    )
    mesh.raycast = NO_RAYCAST
    return mesh
  }, [])
  useEffect(
    () => () => {
      line.geometry.dispose()
      ;(line.material as LineDashedMaterial).dispose()
      ring.geometry.dispose()
      ;(ring.material as MeshBasicMaterial).dispose()
    },
    [line, ring],
  )

  // Read by the updater through a ref: the director's frame callback outlives this render.
  const argsRef = useRef({ aperture, emitAxis, panRad, tiltRad, beamDeg, land })
  argsRef.current = { aperture, emitAxis, panRad, tiltRad, beamDeg, land }

  useEffect(() => {
    const scratch = {
      origin: new Vector3(),
      dir: new Vector3(),
      end: new Vector3(),
      target: new Matrix4(),
      step: new Matrix4(),
      quat: new Quaternion(),
      landing: { point: new Vector3(), normal: new Vector3(), length: 0 } satisfies MarkLanding,
      ringAt: new Vector3(),
      last: new Vector3(NaN, NaN, NaN),
    }
    const updater: MarkUpdater = (head) => {
      const root = rootRef.current
      const mount = mountRef.current
      const ringGroup = ringGroupRef.current
      if (root == null || mount == null || ringGroup == null) return
      const a = argsRef.current
      const { origin, dir, end, target, step, landing } = scratch
      root.updateMatrixWorld()

      // Where the head points now.
      origin.set(a.aperture.x, a.aperture.y, a.aperture.z).applyMatrix4(head.matrixWorld)
      dir.set(0, a.emitAxis, 0).transformDirection(head.matrixWorld)
      if (a.land(origin, dir, landing)) end.copy(landing.point)
      else end.copy(origin).addScaledVector(dir, OPEN_AIR_M)
      const positions = line.geometry.getAttribute('position') as BufferAttribute
      const distances = line.geometry.getAttribute('lineDistance') as BufferAttribute
      root.worldToLocal(origin)
      root.worldToLocal(end)
      positions.setXYZ(0, origin.x, origin.y, origin.z)
      positions.setXYZ(1, end.x, end.y, end.z)
      positions.needsUpdate = true
      distances.setX(1, origin.distanceTo(end))
      distances.needsUpdate = true
      line.geometry.computeBoundingSphere()

      markTargetMatrix(mount.matrixWorld, head.position, a.panRad, a.tiltRad, target, step)
      origin.set(a.aperture.x, a.aperture.y, a.aperture.z).applyMatrix4(target)
      dir.set(0, a.emitAxis, 0).transformDirection(target)
      const ringAt = scratch.ringAt
      if (a.land(origin, dir, landing)) {
        ringAt.copy(landing.point).addScaledVector(landing.normal, RING_LIFT_M)
        ring.quaternion.copy(scratch.quat.setFromUnitVectors(RING_NORMAL, landing.normal))
        ring.scale.setScalar(markRingRadiusM(landing.length, a.beamDeg))
        ring.visible = true
      } else {
        ringAt.copy(origin).addScaledVector(dir, OPEN_AIR_M)
        ring.visible = false
      }
      root.worldToLocal(ringAt)
      ringGroup.position.copy(ringAt)
      if (!(scratch.last.distanceTo(ringAt) < MOVED_EPS_M)) {
        scratch.last.copy(ringAt)
        invalidate()
      }
    }
    register(updater)
    invalidate()
    return () => register(null)
  }, [invalidate, line, mountRef, register, ring, rootRef])

  // A new mark — another cue, another target — draws in the next frame.
  useEffect(() => {
    invalidate()
  }, [invalidate, mark, panRad, tiltRad])

  // The badge counts down while the delay or the move runs.
  const [nowMs, setNowMs] = useState(() => Date.now())
  const counting = markCounting(mark, nowMs)
  useEffect(() => {
    if (!counting) return
    const timer = setInterval(() => setNowMs(Date.now()), COUNTDOWN_MS)
    return () => clearInterval(timer)
  }, [counting])
  // A new mark counts from now, not from the last tick, which may be long past.
  useLayoutEffect(() => {
    setNowMs(Date.now())
  }, [mark])

  return (
    <>
      <primitive object={line} />
      <group ref={ringGroupRef}>
        <primitive object={ring} />
        <StageLabel position={BADGE_OFFSET} kind="moveInDark">
          {markBadge(mark, nowMs)}
        </StageLabel>
      </group>
    </>
  )
}
