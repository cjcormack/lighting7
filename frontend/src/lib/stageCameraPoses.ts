/**
 * Where this window's Stage cameras were left, per tab in `sessionStorage` (stage-view plan §3.2).
 *
 * The camera used to reset every time the canvas mounted — a route change, a reload, and the
 * context-loss *Restore*, which remounts the canvas on purpose. Now the orbit pose (and the eye's,
 * while on Eye) is written as the camera moves and read back on mount, so a remount lands where the
 * operator left it. Per tab for `lib/stageViewpoint.ts`'s reason; plain arrays in the desk's
 * **three.js space** (`lib/stageCoords.ts`: x right, y up, z towards the house), because the only
 * readers are the camera rig and a round trip through lighting coordinates would be two
 * conversions for nothing.
 *
 * Nothing subscribes: the rig reads once on mount and writes as it moves, so a plain get/set with
 * the storage failures swallowed is the whole module. Three.js-free, so `lib/stageViewpoint.ts` —
 * read on every route by the windows bridge — can clear the eye pose without pulling the 3D chunk.
 */

export type Vec3 = readonly [number, number, number]

export interface OrbitPose {
  position: Vec3
  target: Vec3
}

/** A person standing at `position`, head turned by `yaw` (radians, 0 = facing upstage) and `pitch`. */
export interface EyePose {
  position: Vec3
  yaw: number
  pitch: number
  fov: number
}

export const ORBIT_POSE_KEY = 'stage.orbitPose'
export const EYE_POSE_KEY = 'stage.eyePose'

function isVec3(value: unknown): value is Vec3 {
  return (
    Array.isArray(value) &&
    value.length === 3 &&
    value.every((n) => typeof n === 'number' && Number.isFinite(n))
  )
}

function isFiniteNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value)
}

function read(key: string): unknown {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.sessionStorage.getItem(key)
    return raw == null ? null : JSON.parse(raw)
  } catch {
    return null
  }
}

function write(key: string, value: unknown): void {
  if (typeof window === 'undefined') return
  try {
    if (value == null) window.sessionStorage.removeItem(key)
    else window.sessionStorage.setItem(key, JSON.stringify(value))
  } catch {
    // Quota exhausted or storage unavailable — the camera still works, it just forgets.
  }
}

/**
 * The orbit pose as the camera last moved, in memory. Storage is written at most every 150 ms, so
 * a camera switched to Eye in that window — a remote `windows.viewOptions`, a quick click — would
 * seed the eye from where the orbit camera *was*. The eye's seed is read in the render that mounts
 * it, before the orbit rig's unmount has flushed its pending write, so the live pose has to be here
 * rather than there. Per tab like the storage it shadows: a module is one per window.
 */
let liveOrbitPose: OrbitPose | null = null

/**
 * The eye's pose as it last moved, in memory — the orbit's reason, for *Save this view…*: storage
 * trails the head by up to 150 ms, and a save must record where the eye looks now.
 */
let liveEyePose: EyePose | null = null

/** Note where the orbit camera is now, without touching storage. */
export function noteOrbitPose(pose: OrbitPose): void {
  liveOrbitPose = pose
}

export function readOrbitPose(): OrbitPose | null {
  if (liveOrbitPose != null) return liveOrbitPose
  const value = read(ORBIT_POSE_KEY) as Partial<OrbitPose> | null
  if (value == null || !isVec3(value.position) || !isVec3(value.target)) return null
  return { position: value.position, target: value.target }
}

export function writeOrbitPose(pose: OrbitPose): void {
  liveOrbitPose = pose
  write(ORBIT_POSE_KEY, pose)
}

/** Test seam: forget the in-memory poses, so the next read is storage's. */
export function resetLiveOrbitPose(): void {
  liveOrbitPose = null
  liveEyePose = null
}

/** Note where the eye is now, without touching storage. */
export function noteEyePose(pose: EyePose): void {
  liveEyePose = pose
}

export function readEyePose(): EyePose | null {
  if (liveEyePose != null) return liveEyePose
  const value = read(EYE_POSE_KEY) as Partial<EyePose> | null
  if (
    value == null ||
    !isVec3(value.position) ||
    !isFiniteNumber(value.yaw) ||
    !isFiniteNumber(value.pitch) ||
    !isFiniteNumber(value.fov)
  ) {
    return null
  }
  return { position: value.position, yaw: value.yaw, pitch: value.pitch, fov: value.fov }
}

export function writeEyePose(pose: EyePose | null): void {
  liveEyePose = pose
  write(EYE_POSE_KEY, pose)
}
