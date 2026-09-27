import { Euler, MathUtils, Object3D, Quaternion, Vector3 } from "three"
import type { FixturePatch } from "../api/patchApi"
import type { RiggingDto } from "../api/riggingApi"
import type { SliderPropertyDescriptor } from "../store/fixtures"

// Lighting coords are Z-up, FOH-relative (X = stage right, Y = upstage, Z = up).
// R3F's default scene is Y-up, with camera looking down -Z. The swizzle below
// keeps default-up Three.js everywhere else and avoids reframing the camera.
//
//   R3F X = lighting X
//   R3F Y = lighting Z
//   R3F Z = -lighting Y    (downstage is +Z, toward the camera)

export function toThree(stageX: number, stageY: number, stageZ: number, target = new Vector3()): Vector3 {
  return target.set(stageX, stageZ, -stageY)
}

// Inverse of `toThree` — convert R3F (X, Y, Z) back to lighting (X, Y, Z).
export function fromThree(v: Vector3): { x: number; y: number; z: number } {
  return { x: v.x, y: -v.z, z: v.y }
}

// Build a unit beam direction (in R3F space) from *signed* pan and tilt angles
// — angles about each axis's own mechanical centre, as produced by
// `dmxToSignedDegrees`. Not raw travel positions, and not base orientation:
// `baseYawDeg`/`basePitchDeg` are the body's mount pose and are carried by the
// fixture's body group, so folding them in here would apply them twice.
//
// Rest is +Y: at pan 0 / tilt 0 the head points straight up its own body axis,
// away from its base. That is where a yoke sits at mid-travel, so DMX-centre
// tilt lands on the fixture's own axis. A hung mover is basePitchDeg = 180.
//
// 'YXZ' is intrinsic, so the vector tilts about X first and then pans about Y —
// head-inside-yoke, which is the order the metal actually moves in.
//
//   tilt  +90 ⇒ downstage (+Z)   tilt -90 ⇒ upstage (−Z)   tilt 180 ⇒ down
//   pan   +90 ⇒ swings a downstage beam toward stage right (+X)
//
// `target`/`scratchEuler` let per-frame callers pre-allocate to keep the
// useFrame hot path free of per-call Vector3/Euler allocations.
const SCRATCH_EULER = new Euler()
export function panTiltToDir(
  panDeg: number,
  tiltDeg: number,
  target = new Vector3(),
  scratchEuler: Euler = SCRATCH_EULER,
): Vector3 {
  scratchEuler.set(MathUtils.degToRad(tiltDeg), MathUtils.degToRad(panDeg), 0, "YXZ")
  return target.set(0, 1, 0).applyEuler(scratchEuler)
}

// Quaternion variant of panTiltToDir, for a body with no separate yoke node
// that must carry pan and tilt on one object. Equivalent to composing a yoke
// pan about Y with a head tilt about X — pinned by a test in stageCoords.test
// so the split-node and single-node drive paths can't drift apart.
const SCRATCH_QUAT_EULER = new Euler()
export function headQuaternionFor(
  panDeg: number,
  tiltDeg: number,
  target = new Quaternion(),
  scratchEuler: Euler = SCRATCH_QUAT_EULER,
): Quaternion {
  scratchEuler.set(MathUtils.degToRad(tiltDeg), MathUtils.degToRad(panDeg), 0, "YXZ")
  return target.setFromEuler(scratchEuler)
}

// Build the YXZ Euler that maps the rig's lighting-coord pitch/yaw/roll to a
// three.js rotation. YXZ is intrinsic: yaw applies first (around lighting Z =
// R3F Y), then pitch (lighting X = R3F X), then roll (lighting Y = R3F -Z).
// Pass `target` to keep per-frame callers allocation-free.
export function rigEuler(
  rig: { pitchDeg: number | null; yawDeg: number | null; rollDeg: number | null },
  target: Euler = new Euler(),
): Euler {
  target.set(
    MathUtils.degToRad(rig.pitchDeg ?? 0),
    MathUtils.degToRad(rig.yawDeg ?? 0),
    MathUtils.degToRad(rig.rollDeg ?? 0),
    "YXZ",
  )
  return target
}

// `dmxToDegrees` lives in `lib/axisDegrees.ts` beside its inverse, so the position editor and
// this view read one mapping; re-exported here for the 3D callers that always imported it from
// this module.
import { dmxToDegrees } from './axisDegrees'
export { dmxToDegrees }

// Mechanical centre of a pan/tilt axis, in travel degrees. Because degMin/degMax
// describe travel rather than a signed angle, the DMX-centre position is the
// midpoint — never 0, and never the 270° that used to be hardcoded here (that
// was only ever correct for the 0–540 pan movers, and wrong for tilt on all of
// them, for Scantastic 4's 0–180 pan and for Varytec's 0–630).
export function axisCentreDeg(slider: SliderPropertyDescriptor): number | null {
  if (slider.degMin == null || slider.degMax == null) return null
  return (slider.degMin + slider.degMax) / 2
}

// Travel position expressed as a signed angle about the axis centre — what the
// scene graph wants. Mid-DMX ⇒ 0 ⇒ head sitting on the fixture's own axis.
//
// Centring is a subtraction applied after the interpolation, so a fractional
// coarse value from `combineFine` (16-bit pan/tilt) survives untouched.
export function dmxToSignedDegrees(
  dmx: number,
  slider: SliderPropertyDescriptor,
): number | null {
  const travel = dmxToDegrees(dmx, slider)
  if (travel == null) return null
  // Non-null whenever dmxToDegrees was: both read the same two bounds.
  return travel - axisCentreDeg(slider)!
}

// Normalise an angle in degrees to (-180, 180]. The backend validates
// basePitchDeg to ±180 (projectPatches.kt), so a hung mover sitting at 180 would
// otherwise have the very next rotate-gizmo nudge rejected with a 400.
// Range is (-180, 180] rather than [-180, 180) so that a deliberate 180 — the
// canonical "hung upside down" pitch — survives a round trip as 180.
export function normaliseSignedDeg(deg: number): number {
  const wrapped = ((deg % 360) + 360) % 360
  return wrapped > 180 ? wrapped - 360 : wrapped
}

// Resolve a patch's world position in R3F space. When `riggingUuid` matches a
// known rigging, stage* is treated as an offset in the rigging's local lighting
// frame and composed with the rig's full pose (position + yaw/pitch/roll).
// Otherwise stage* is a free-space world coordinate.
const SCRATCH_RIG_EULER = new Euler()
const SCRATCH_OFFSET = new Vector3()
export function worldPositionFor(
  patch: FixturePatch,
  riggings: RiggingDto[],
  target = new Vector3(),
): Vector3 {
  const sx = patch.stageX ?? 0
  const sy = patch.stageY ?? 0
  const sz = patch.stageZ ?? 0

  if (patch.riggingUuid) {
    const rig = riggings.find((r) => r.uuid === patch.riggingUuid)
    if (rig) {
      // Swizzle stage offset into R3F-local, rotate by the rig's pose, then
      // translate by the rig's R3F world position. rigEuler matches the YXZ
      // Euler that RiggingMeshes applies to the visual mesh.
      SCRATCH_OFFSET.set(sx, sz, -sy).applyEuler(rigEuler(rig, SCRATCH_RIG_EULER))
      toThree(rig.positionX ?? 0, rig.positionY ?? 0, rig.positionZ ?? 0, target)
      return target.add(SCRATCH_OFFSET)
    }
  }

  return toThree(sx, sy, sz, target)
}

// Lighting-coords variant of worldPositionFor — same composition logic but
// returns the FOH-relative (X = stage right, Y = upstage, Z = up) triple
// instead of the R3F swizzle. Used by the 2D top-down fallback panel which
// reasons in stage metres rather than R3F space.
export function worldPositionLighting(
  // Only the four placement fields, so an extra placement (a paired dimmer's other lantern) is
  // composed through its rigging by the same arithmetic as the patch it belongs to.
  patch: Pick<FixturePatch, 'stageX' | 'stageY' | 'stageZ' | 'riggingUuid'>,
  riggings: RiggingDto[],
): { x: number; y: number; z: number } | null {
  const sx = patch.stageX
  const sy = patch.stageY
  if (sx == null || sy == null) return null
  const sz = patch.stageZ ?? 0

  if (patch.riggingUuid) {
    const rig = riggings.find((r) => r.uuid === patch.riggingUuid)
    if (rig) {
      // Apply the rig's pose to the offset by going through R3F space (where
      // rigEuler is defined), then swizzle back to lighting coords.
      SCRATCH_OFFSET.set(sx, sz, -sy).applyEuler(rigEuler(rig, SCRATCH_RIG_EULER))
      return {
        x: (rig.positionX ?? 0) + SCRATCH_OFFSET.x,
        y: (rig.positionY ?? 0) - SCRATCH_OFFSET.z,
        z: (rig.positionZ ?? 0) + SCRATCH_OFFSET.y,
      }
    }
  }

  return { x: sx, y: sy, z: sz }
}

export interface RigPlacement {
  riggingUuid: string | null
  stageX: number | null
  stageY: number | null
  stageZ: number | null
}

/**
 * Inverse of `worldPositionLighting`, with the destination frame named
 * **explicitly** rather than read off a patch. Pass `rig = null` to get
 * free-space world coordinates.
 *
 * Use this — never `patchPlacementFromWorld` — whenever the fixture may be
 * changing rigs. `patchPlacementFromWorld` resolves the frame from
 * `patch.riggingUuid`, so calling it while re-parenting yields offsets in the
 * *old* rig's frame that then get stored against the *new* rig's uuid. The
 * numbers stay inside the server's ±500 m bound, the write succeeds, and the
 * fixture is drawn somewhere plausible but wrong — nothing ever surfaces an
 * error. Naming the rig is what makes that mistake unrepresentable.
 *
 * Note `stageZ` is up-positive in the rig's local frame even though the patch
 * form labels it "drop": local +Z maps to R3F +Y. A "drop 0.5 m" control must
 * therefore emit `stageZ = -0.5`.
 */
const SCRATCH_OBJ = new Object3D()
const SCRATCH_WORLDPOS = new Vector3()
export function placementFromWorldLighting(
  world: { x: number; y: number; z: number },
  rig: RiggingDto | null,
): { stageX: number; stageY: number; stageZ: number } {
  if (!rig) return { stageX: world.x, stageY: world.y, stageZ: world.z }

  SCRATCH_OBJ.position.set(rig.positionX ?? 0, rig.positionZ ?? 0, -(rig.positionY ?? 0))
  rigEuler(rig, SCRATCH_OBJ.rotation)
  SCRATCH_OBJ.updateMatrixWorld()
  const local = SCRATCH_OBJ.worldToLocal(
    toThree(world.x, world.y, world.z, SCRATCH_WORLDPOS),
  )
  return { stageX: local.x, stageY: -local.z, stageZ: local.y }
}

/**
 * Unit vector, in world lighting coords, of one of a rig's local axes:
 * 'x' = along the truss, 'y' = out from it, 'z' = up. Used to constrain drags
 * to the bar and to lay fixtures out along it.
 */
const SCRATCH_AXIS = new Vector3()
export function rigAxisLighting(
  rig: RiggingDto,
  axis: 'x' | 'y' | 'z',
): { x: number; y: number; z: number } {
  // Build the axis in the rig's *lighting*-local frame, swizzle to R3F (which is
  // where rigEuler is defined), rotate, then swizzle back.
  const l = axis === 'x' ? [1, 0, 0] : axis === 'y' ? [0, 1, 0] : [0, 0, 1]
  toThree(l[0], l[1], l[2], SCRATCH_AXIS).applyEuler(rigEuler(rig, SCRATCH_RIG_EULER))
  return fromThree(SCRATCH_AXIS)
}

// Inverse of `worldPositionFor`: project an R3F world point into a patch's
// rig-local frame and recover stage* offsets in lighting coords. With no
// rigging the world point is treated as a free-space lighting position.
//
// Delegates to `placementFromWorldLighting` so exactly one inverse exists and
// the two cannot drift. **Cannot re-parent** — see that function's note.
export function patchPlacementFromWorld(
  patch: FixturePatch,
  worldR3F: Vector3,
  riggings: RiggingDto[],
): RigPlacement {
  const rig = patch.riggingUuid
    ? riggings.find((r) => r.uuid === patch.riggingUuid)
    : undefined

  // A dangling riggingUuid (rig deleted elsewhere) has no frame to project
  // into, so leave the stored offsets alone rather than reinterpreting them.
  if (patch.riggingUuid && !rig) {
    return {
      riggingUuid: patch.riggingUuid,
      stageX: patch.stageX,
      stageY: patch.stageY,
      stageZ: patch.stageZ,
    }
  }

  return {
    riggingUuid: patch.riggingUuid,
    ...placementFromWorldLighting(fromThree(worldR3F), rig ?? null),
  }
}
