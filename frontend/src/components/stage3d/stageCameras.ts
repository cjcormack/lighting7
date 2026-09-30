import type { OrthoCamera } from '../../lib/stageViewpoint'
import type { EyePose, OrbitPose, Vec3 } from '../../lib/stageCameraPoses'
import type { LightingPoint } from '../../lib/stageProjection'
import type { StageRegionDto } from '../../api/stageRegionApi'
import type { RiggingDto } from '../../api/riggingApi'

/**
 * The Stage view's camera maths, pure and three.js-free so it can be pinned without a canvas
 * (stage-view plan session 1). Positions are the desk's **three.js space** throughout
 * (`lib/stageCoords.ts`: x right, y up, z towards the house — lighting X, Z, −Y), except where a
 * name says `Lighting`.
 *
 * The prototype (`stage-view-design/prototype.html`, §"Cameras") proved the maths; this is the
 * desk's own statement of it, not a port.
 */

/** A lighting-space axis-aligned box: X across, Y upstage, Z up. */
export interface LightingBounds {
  min: LightingPoint
  max: LightingPoint
}

/**
 * How far outside the rig an orthographic section's plane sits, and the margin a fit leaves round
 * what it frames. Half a metre keeps a truss's label and a hung body's yoke inside the cut.
 */
export const SECTION_MARGIN_M = 0.5
const FIT_MARGIN_M = 1

/**
 * Everything the Stage view draws, as one box: the stage envelope, every rigging's reach, every
 * region (which **hangs down from `centerZ`**, its top surface), and every placed fixture. The
 * orthographic sections are cut and fitted to it, so nothing the rig holds is cut away.
 */
export function sceneBoundsLighting(
  stage: { width: number; depth: number; height: number },
  riggings: readonly RiggingDto[],
  regions: readonly StageRegionDto[],
  fixtures: readonly LightingPoint[],
): LightingBounds {
  const min = { x: -stage.width / 2, y: 0, z: 0 }
  const max = { x: stage.width / 2, y: stage.depth, z: stage.height }
  const grow = (x: number, y: number, z: number, rx = 0, ry = rx) => {
    min.x = Math.min(min.x, x - rx)
    max.x = Math.max(max.x, x + rx)
    min.y = Math.min(min.y, y - ry)
    max.y = Math.max(max.y, y + ry)
    min.z = Math.min(min.z, z)
    max.z = Math.max(max.z, z)
  }
  for (const rig of riggings) {
    if (rig.positionX == null || rig.positionY == null) continue
    // A bar's ends, whatever its yaw: half its length either way on both axes is a box that holds
    // it at any angle, and a section a little wide costs nothing.
    const reach = (rig.lengthM ?? 0) / 2
    grow(rig.positionX, rig.positionY, rig.positionZ ?? 0, reach)
  }
  for (const region of regions) {
    const w = region.widthM ?? 0
    const d = region.depthM ?? 0
    const top = region.centerZ ?? 0
    const reach = Math.hypot(w, d) / 2
    grow(region.centerX ?? 0, region.centerY ?? 0, top, reach)
    grow(region.centerX ?? 0, region.centerY ?? 0, top - (region.heightM ?? 0), reach)
  }
  for (const p of fixtures) grow(p.x, p.y, p.z)
  return { min, max }
}

/**
 * One orthographic section: where the camera stands (on the section plane), what it looks at, which
 * way is up on screen, how deep it sees, and the width × height in metres a fit must show.
 *
 * **The camera stands on the section plane**, just outside the rig on its own side, with a near
 * plane of a centimetre — so what lies between the section and the camera is nothing by
 * construction, and anything beyond it on the camera's side is clipped by the frustum. That is the
 * cut: ceilings above the plan, the house in front of the front elevation, the stage-left wall
 * beside the side one. Today the desk models only the stage and the rig, all of which is inside
 * the bounds, so nothing of it is cut; the venue (session 3) is what these planes will cut through.
 */
export interface OrthoSection {
  position: Vec3
  target: Vec3
  up: Vec3
  far: number
  width: number
  height: number
}

/**
 * The three sections, matching `lib/stageProjection.ts`'s screen conventions so a surface drawn by
 * the SVG plot and by the camera agree: **Plan** looks down with upstage at the top and +X to the
 * right; **Front** looks upstage from the house with +X to the right; **Side** looks from +X —
 * audience right, which is stage left in actor terms (lighting7 `docs/fixtures-engineering.md`'s
 * axis table) — with the house to the left and upstage to the right.
 */
export function orthoSection(view: OrthoCamera, b: LightingBounds): OrthoSection {
  const cx = (b.min.x + b.max.x) / 2
  const cy = (b.min.y + b.max.y) / 2
  const cz = (b.min.z + b.max.z) / 2
  const spanX = b.max.x - b.min.x
  const spanY = b.max.y - b.min.y
  const spanZ = b.max.z - b.min.z
  const m = SECTION_MARGIN_M
  switch (view) {
    case 'plan': {
      const eyeZ = b.max.z + m
      return {
        position: [cx, eyeZ, -cy],
        target: [cx, b.min.z, -cy],
        // Screen-up is three's −z, which is lighting +Y: upstage at the top.
        up: [0, 0, -1],
        far: eyeZ - b.min.z + m,
        width: spanX + 2 * FIT_MARGIN_M,
        height: spanY + 2 * FIT_MARGIN_M,
      }
    }
    case 'front': {
      const eyeY = b.min.y - m
      return {
        position: [cx, cz, -eyeY],
        target: [cx, cz, -b.max.y],
        up: [0, 1, 0],
        far: b.max.y - eyeY + m,
        width: spanX + 2 * FIT_MARGIN_M,
        height: spanZ + 2 * FIT_MARGIN_M,
      }
    }
    case 'side': {
      const eyeX = b.max.x + m
      return {
        position: [eyeX, cz, -cy],
        target: [b.min.x, cz, -cy],
        up: [0, 1, 0],
        far: eyeX - b.min.x + m,
        width: spanY + 2 * FIT_MARGIN_M,
        height: spanZ + 2 * FIT_MARGIN_M,
      }
    }
  }
}

/**
 * The orthographic zoom (pixels per metre, drei's `OrthographicCamera` frustum being the canvas in
 * pixels) that fits `width × height` metres into a `pixelW × pixelH` canvas.
 */
export function fitZoom(width: number, height: number, pixelW: number, pixelH: number): number {
  if (!(width > 0) || !(height > 0) || !(pixelW > 0) || !(pixelH > 0)) return 1
  return Math.min(pixelW / width, pixelH / height)
}

/** The orbit camera's pose before the operator has moved it: in front of and above the stage. */
export function defaultOrbitPose(stage: { width: number; depth: number; height: number }): OrbitPose {
  const distance = Math.max(stage.width, stage.depth) * 1.4
  return { position: [0, stage.height * 0.7, distance], target: [0, stage.height / 4, 0] }
}

// — the eye ————————————————————————————————————————————————————————————

export const EYE_DEFAULT_FOV_DEG = 50
export const EYE_FOV_RANGE_DEG: readonly [number, number] = [15, 90]
/** The head tilts short of straight up or down, where yaw stops meaning anything. */
export const EYE_PITCH_LIMIT_RAD = (85 * Math.PI) / 180

/** Yaw 0 faces upstage (three's −z); positive yaw turns towards +x. Pitch is up from level. */
export function lookDirection(yaw: number, pitch: number): Vec3 {
  const c = Math.cos(pitch)
  return [Math.sin(yaw) * c, Math.sin(pitch), -Math.cos(yaw) * c]
}

/** The yaw and pitch that face `to` from `from`; level and upstage when the two coincide. */
export function lookAngles(from: Vec3, to: Vec3): { yaw: number; pitch: number } {
  const dx = to[0] - from[0]
  const dy = to[1] - from[1]
  const dz = to[2] - from[2]
  const len = Math.hypot(dx, dy, dz)
  if (len < 1e-9) return { yaw: 0, pitch: 0 }
  return { yaw: Math.atan2(dx, -dz), pitch: clampPitch(Math.asin(dy / len)) }
}

export function clampPitch(pitch: number): number {
  return Math.max(-EYE_PITCH_LIMIT_RAD, Math.min(EYE_PITCH_LIMIT_RAD, pitch))
}

export function clampFov(fov: number): number {
  return Math.max(EYE_FOV_RANGE_DEG[0], Math.min(EYE_FOV_RANGE_DEG[1], fov))
}

/** The eye seeded from an orbit pose: standing where the orbit camera is, facing its target. */
export function eyeFromOrbit(pose: OrbitPose): EyePose {
  return { position: pose.position, ...lookAngles(pose.position, pose.target), fov: EYE_DEFAULT_FOV_DEG }
}

/** Where the eye is looking, a fixed distance ahead — what `camera.lookAt` is given. */
export function eyeTarget(pose: Pick<EyePose, 'position' | 'yaw' | 'pitch'>, distance = 6): Vec3 {
  const d = lookDirection(pose.yaw, pose.pitch)
  return [pose.position[0] + d[0] * distance, pose.position[1] + d[1] * distance, pose.position[2] + d[2] * distance]
}

// — framing the selection ——————————————————————————————————————————————————

/**
 * The smallest radius a frame is fitted to: one fixture is a point, and a camera fitted to a point
 * would dive into its lens. Two metres shows a head and the bar it hangs on.
 */
export const MIN_FRAME_RADIUS_M = 2

/** The centre of a set of points and the radius of the sphere about it that holds them. */
export function boundingSphere(points: readonly Vec3[]): { centre: Vec3; radius: number } | null {
  if (points.length === 0) return null
  const lo = [Infinity, Infinity, Infinity]
  const hi = [-Infinity, -Infinity, -Infinity]
  for (const p of points) {
    for (let i = 0; i < 3; i++) {
      lo[i] = Math.min(lo[i], p[i])
      hi[i] = Math.max(hi[i], p[i])
    }
  }
  const centre: Vec3 = [(lo[0] + hi[0]) / 2, (lo[1] + hi[1]) / 2, (lo[2] + hi[2]) / 2]
  const radius = Math.max(MIN_FRAME_RADIUS_M, Math.hypot(hi[0] - lo[0], hi[1] - lo[1], hi[2] - lo[2]) / 2)
  return { centre, radius }
}

/**
 * The orbit pose that frames a sphere: the same direction from target to camera the operator had,
 * pulled in or out until the sphere fills the lens — grandMA3's *Auto* camera, and the prototype's.
 * The lens's narrower half-angle decides: `fovDeg` is vertical, and on a canvas narrower than it is
 * tall ([aspect] below 1 — a phone, a squeezed panel) the horizontal field is the tighter one.
 */
export function framedOrbitPose(
  pose: OrbitPose,
  centre: Vec3,
  radius: number,
  fovDeg: number,
  aspect = 1,
): OrbitPose {
  let dx = pose.position[0] - pose.target[0]
  let dy = pose.position[1] - pose.target[1]
  let dz = pose.position[2] - pose.target[2]
  const len = Math.hypot(dx, dy, dz)
  if (len < 1e-9) {
    dx = 0
    dy = 0.5
    dz = 1
  }
  const n = Math.hypot(dx, dy, dz)
  const halfV = ((fovDeg * Math.PI) / 180) / 2
  const halfH = Math.atan(Math.tan(halfV) * (aspect > 0 ? aspect : 1))
  const distance = (radius / Math.sin(Math.min(halfV, halfH))) * 1.1
  return {
    position: [centre[0] + (dx / n) * distance, centre[1] + (dy / n) * distance, centre[2] + (dz / n) * distance],
    target: centre,
  }
}

/**
 * An orthographic frame: the camera slides **within its section plane** until the sphere's centre
 * is in the middle — the plane stays where it was, so a frame never changes what is cut — and the
 * zoom fits the sphere's diameter.
 */
export function framedOrthoPosition(
  position: Vec3,
  target: Vec3,
  centre: Vec3,
): { position: Vec3; target: Vec3 } {
  // The view axis: the component of the offset along it is kept, the rest moves to the centre.
  const ax = target[0] - position[0]
  const ay = target[1] - position[1]
  const az = target[2] - position[2]
  const n = Math.hypot(ax, ay, az) || 1
  const f: Vec3 = [ax / n, ay / n, az / n]
  const delta: Vec3 = [centre[0] - target[0], centre[1] - target[1], centre[2] - target[2]]
  const along = delta[0] * f[0] + delta[1] * f[1] + delta[2] * f[2]
  const shift: Vec3 = [delta[0] - along * f[0], delta[1] - along * f[1], delta[2] - along * f[2]]
  return {
    position: [position[0] + shift[0], position[1] + shift[1], position[2] + shift[2]],
    target: [target[0] + shift[0], target[1] + shift[1], target[2] + shift[2]],
  }
}
