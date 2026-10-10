import {
  COLLIDER_SOLID,
  COLLIDER_TEXELS,
  crossingShare,
  emptyBoxFrame,
  emptyPackedCollider,
  segmentCrossing,
  unpackCollider,
  type ColliderSet,
  type PackedCollider,
} from './occlusion'
import { sceneMasks } from './sceneMasks'

/**
 * **The haze splits at a cloth** (scrim plan D10, session 5): one list of [MAX_HAZE_PLANES]
 * transmitting planes — scrims and cut cloths together, nearest the eye first — that the beams'
 * march multiplies by. A sample behind a plane, seen from the eye, keeps the eye's share of it: a
 * gauze its `open(θ_eye)^gather`, as the gauze's own blend covers what is behind it
 * (`seeThrough.ts`'s `scrimCover`), a cut cloth 1 through a hole and 0 behind cloth. And a sample
 * past a plane on the beam's own path keeps the beam's share of it, at the angle the ray from the
 * apex crosses it, or the mask where it crosses — so a shaft through a cut cloth's hole carries on
 * and one through its cloth stops. Each beam's row names the planes it crosses (`landing.ts`'s
 * [hazePlanesCrossed], packed in its `aBeamFx.z`), so a sample tests only those on the beam's side.
 *
 * **A transmitting collider is never a landing plane** (`beamReach.ts`'s `isLandingSurface`), so a
 * beam's air runs on past a gauze or a cut cloth to the next solid surface, and these planes are what
 * stop it. **A ninth** still passes light on the surfaces (its collider is in every light's list),
 * but it is not in the list, so its haze does not split: the air behind it draws whole.
 *
 * **One copy of each rule.** A plane is a packed collider's three texels (`occlusion.ts`'s layout:
 * centre and folded turn, half-extents and skin, kind and its numbers), read by the shadows'
 * `segmentCrossing` and `crossingShare` ([TRANSMIT_GLSL] on the GPU, their twins here) — the same
 * `open(θ)` (`scrimOpen.ts`) and the same mask atlas sampler. [HAZE_PLANES_GLSL] adds only the loop,
 * and [hazeSampleShare] is its twin.
 *
 * **Uniforms, not a define.** The list is filled every frame from the canvas's packed colliders and
 * the camera ([fillHazePlanes], in `StageEmitters`' flush, after the camera has moved and before the
 * frame renders), and reaches the march as `uHazePlaneCount` and `uHazePlanes` — no program is ever
 * recompiled for a light, the camera or the list moving.
 *
 * Pure and three.js-free; `hazePlanes.test.ts` pins the twin.
 */

/** The most planes the haze splits at (D10): scrims and cut cloths, nearest the eye first. */
export const MAX_HAZE_PLANES = 8
/** A plane's floats in [HazePlaneList.data] and the `uHazePlanes` uniform: a packed collider's three texels. */
export const HAZE_PLANE_FLOATS = COLLIDER_TEXELS * 4
/** Where a view ray crosses no plane: beyond any march. */
export const HAZE_NO_CROSSING = 1e9
/** How far along the view ray the eye's crossings are looked for: past any venue. */
export const HAZE_EYE_REACH_M = 1e6

/** The planes of one frame, nearest the eye first. */
export interface HazePlaneList {
  /** [MAX_HAZE_PLANES] × [HAZE_PLANE_FLOATS], the first [count] in use: the `uHazePlanes` uniform's backing array. */
  data: Float32Array
  count: number
  /** Each plane's row in the collider set it was filled from. */
  index: Int32Array
  /** Each plane's distance from the eye, metres: the box's nearest point. */
  dist: Float64Array
  /** Transmitting colliders past the list this frame: drawn and lit, their haze not split. */
  over: number
  /** Moves each time [fillHazePlanes] changes the list, so a reader can tell it is the one it named against. */
  version: number
}

export function makeHazePlaneList(): HazePlaneList {
  return {
    data: new Float32Array(MAX_HAZE_PLANES * HAZE_PLANE_FLOATS),
    count: 0,
    index: new Int32Array(MAX_HAZE_PLANES),
    dist: new Float64Array(MAX_HAZE_PLANES),
    over: 0,
    version: 0,
  }
}

const SCRATCH: PackedCollider = emptyPackedCollider()

/** How far ([ex], [ey], [ez]) lies from the nearest point of packed collider [b]: 0 inside it. */
function distanceToBox(b: PackedCollider, ex: number, ey: number, ez: number): number {
  const rx = ex - b.cx
  const rz = ez - b.cz
  // Into the box's frame by −yaw, as `segmentCrossing` turns a segment.
  const lx = b.cos * rx - b.sin * rz
  const ly = ey - b.cy
  const lz = b.sin * rx + b.cos * rz
  const qx = Math.max(0, Math.abs(lx) - b.hx)
  const qy = Math.max(0, Math.abs(ly) - b.hy)
  const qz = Math.max(0, Math.abs(lz) - b.hz)
  return Math.hypot(qx, qy, qz)
}

/**
 * Fill [out] with the transmitting colliders of [set] nearest the eye at ([ex], [ey], [ez]): at most
 * [cap] (itself at most [MAX_HAZE_PLANES]), nearest first, a tie to the earlier row. A collider packed
 * solid — a cut cloth whose mask is loading, missing, holes none or over the atlas — is not one.
 * Answers whether the list changed: its planes, their order or what any of them holds.
 */
export function fillHazePlanes(
  set: ColliderSet,
  ex: number, ey: number, ez: number,
  out: HazePlaneList,
  cap = MAX_HAZE_PLANES,
): boolean {
  const limit = Math.max(0, Math.min(cap, MAX_HAZE_PLANES))
  const before = out.count
  let changed = false
  // The order before, to tell a reorder from a list that is the same.
  const was = BEFORE
  for (let k = 0; k < before; k++) was[k] = out.index[k]
  let n = 0
  let over = 0
  const d = set.data
  for (let i = 0; i < set.count; i++) {
    if (d[i * HAZE_PLANE_FLOATS + 8] === COLLIDER_SOLID) continue
    const dist = distanceToBox(unpackCollider(d, i, SCRATCH), ex, ey, ez)
    // Where it goes in the list so far: after every plane no further away.
    let at = n
    while (at > 0 && out.dist[at - 1] > dist) at--
    if (at >= limit) {
      over++
      continue
    }
    if (n === limit) over++
    for (let k = Math.min(n, limit - 1); k > at; k--) {
      out.dist[k] = out.dist[k - 1]
      out.index[k] = out.index[k - 1]
    }
    out.dist[at] = dist
    out.index[at] = i
    if (n < limit) n++
  }
  out.count = n
  out.over = over
  if (n !== before) changed = true
  for (let k = 0; k < n; k++) {
    if (k >= before || was[k] !== out.index[k]) changed = true
    const from = out.index[k] * HAZE_PLANE_FLOATS
    const to = k * HAZE_PLANE_FLOATS
    for (let f = 0; f < HAZE_PLANE_FLOATS; f++) {
      const v = d[from + f]
      if (out.data[to + f] !== v) {
        out.data[to + f] = v
        changed = true
      }
    }
  }
  if (changed) out.version++
  return changed
}

const BEFORE = new Int32Array(MAX_HAZE_PLANES)

/** Plane [k] of [list], as the march reads it back. */
export function hazePlane(list: HazePlaneList, k: number, out: PackedCollider = emptyPackedCollider()): PackedCollider {
  return unpackCollider(list.data, k, out)
}

/** Where a view ray crosses each plane of a list, and what it passes there: the march's per-pixel loop. */
export interface HazeEyeCrossings {
  /** Along the view ray, metres; [HAZE_NO_CROSSING] for a plane it does not cross. */
  at: Float64Array
  /** What the eye keeps of what lies behind each plane. */
  share: Float64Array
}

export function makeHazeEyeCrossings(): HazeEyeCrossings {
  return { at: new Float64Array(MAX_HAZE_PLANES), share: new Float64Array(MAX_HAZE_PLANES) }
}

const FRAME = emptyBoxFrame()
const PLANE: PackedCollider = emptyPackedCollider()

/**
 * The view ray from ([ox], [oy], [oz]) along the unit ([dx], [dy], [dz]) against every plane of
 * [list], into [out]: where it crosses each — the middle of its crossing — and the share it keeps
 * there (`occlusion.ts`'s `crossingShare`: a gauze's `open(θ_eye)^gather`, a cut cloth's mask). The
 * twin of [HAZE_PLANES_GLSL]'s `hazeEyeCrossings`, which runs once a pixel, not once a sample.
 */
export function hazeEyeCrossings(
  list: HazePlaneList,
  ox: number, oy: number, oz: number,
  dx: number, dy: number, dz: number,
  out: HazeEyeCrossings,
  atlas: Uint8Array = sceneMasks.atlas,
): HazeEyeCrossings {
  for (let k = 0; k < MAX_HAZE_PLANES; k++) {
    out.at[k] = HAZE_NO_CROSSING
    out.share[k] = 1
    if (k >= list.count) continue
    const b = hazePlane(list, k, PLANE)
    const t = segmentCrossing(ox, oy, oz, dx, dy, dz, HAZE_EYE_REACH_M, b, FRAME)
    if (t < 0) continue
    out.at[k] = t
    out.share[k] = crossingShare(b, FRAME, t, atlas)
  }
  return out
}

/**
 * Whether the segment from ([px], [py], [pz]) along ([ax], [ay], [az]) for [reach] metres spans
 * packed plane [b]'s own z slab — the march's cheap cull before a full crossing test. A segment that
 * crosses the box crosses its slab, so a segment this refuses crosses nothing.
 */
export function hazeSpanReaches(
  b: PackedCollider,
  px: number, py: number, pz: number,
  ax: number, ay: number, az: number,
  reach: number,
): boolean {
  // The box's local z in world space: `segmentCrossing`'s turn, (sin, 0, cos) of the folded yaw.
  const z0 = b.sin * (px - b.cx) + b.cos * (pz - b.cz)
  const z1 = z0 + (b.sin * ax + b.cos * az) * reach
  return Math.min(z0, z1) <= b.hz && Math.max(z0, z1) >= -b.hz
}

/**
 * What the haze keeps at a march sample: [t] along the view ray whose crossings are [eye], at
 * ([px], [py], [pz]). The eye's share of every plane it crossed by [t], times the beam's share
 * of every plane its row names ([planes], bit k for plane k) between the sample and the aperture —
 * the segment from the sample along the unit ([ax], [ay], [az]) towards the apex for [reach] metres,
 * culled first by [hazeSpanReaches]. The twin of [HAZE_PLANES_GLSL]'s `hazeSampleShare`. Each plane
 * is applied once, at its `^gather`.
 */
export function hazeSampleShare(
  list: HazePlaneList,
  eye: HazeEyeCrossings,
  t: number,
  px: number, py: number, pz: number,
  ax: number, ay: number, az: number,
  reach: number,
  planes: number,
  atlas: Uint8Array = sceneMasks.atlas,
): number {
  let keep = 1
  // The eye's side: GLSL's `step(at, t)`, so a sample on a crossing is behind it.
  for (let k = 0; k < MAX_HAZE_PLANES; k++) if (t >= eye.at[k]) keep *= eye.share[k]
  for (let k = 0; k < MAX_HAZE_PLANES; k++) {
    if (!(keep > 0 && k < list.count && (planes & (1 << k)) !== 0)) continue
    const b = hazePlane(list, k, PLANE)
    if (!hazeSpanReaches(b, px, py, pz, ax, ay, az, reach)) continue
    const c = segmentCrossing(px, py, pz, ax, ay, az, reach, b, FRAME)
    if (c >= 0) keep *= crossingShare(b, FRAME, c, atlas)
  }
  return keep
}

/**
 * How far from a march sample its segment towards the apex is tested: to the aperture's plane, an
 * axial [near] from the apex, crossed at `near / cosAngle` along the ray — nothing before the lens
 * is on the beam's path. The twin of the march's `reach`.
 */
export function hazeBeamReach(relLen: number, cosAngle: number, near: number): number {
  return relLen - near / Math.max(cosAngle, 1e-4)
}

/**
 * The march's half (`beamShaders.ts`), over [TRANSMIT_GLSL] (`occlusion.ts`), which the program must
 * include first: the list's uniforms, the per-pixel eye crossings and the per-sample share. No copy of
 * the crossing, of `open(θ)` or of the mask lookup: each is the shadows' own.
 *
 * Shaped for the march, which runs it a dozen times a pixel. The eye's crossings are held four planes
 * to a `vec4`, two of them ([MAX_HAZE_PLANES] is eight, and the GLSL spells out its two halves), so a
 * sample's side of all of them is two `step`s and a product: no array, which SwiftShader — and many a
 * mobile GPU — keeps in memory and would copy into every sample's call. The loops over the planes **break** the moment nothing is
 * left — past the count, past the last plane the row names — rather than testing each plane under an
 * `if`: SwiftShader runs a masked `if`'s body whatever the mask, and a list of none then cost a frame
 * twice what `main` paid (665–685 ms against 302 on the session's scene), where a loop every lane has
 * left ends for all of them. And a plane the beam's row names is tested in full only where the
 * segment's span across the plane's own z reaches the box ([hazeSpanReaches]) — a cull that cannot
 * drop a crossing, since a segment that crosses the box crosses its z slab.
 */
export const HAZE_PLANES_GLSL = /* glsl */ `
  #define MAX_HAZE_PLANES ${MAX_HAZE_PLANES}
  #define HAZE_NO_CROSSING ${HAZE_NO_CROSSING.toExponential(1)}
  uniform int uHazePlaneCount;
  uniform vec4 uHazePlanes[MAX_HAZE_PLANES * 3];

  void hazeEyeCrossings(vec3 o, vec3 d, out vec4 at0, out vec4 at1, out vec4 share0, out vec4 share1) {
    at0 = vec4(HAZE_NO_CROSSING);
    at1 = vec4(HAZE_NO_CROSSING);
    share0 = vec4(1.0);
    share1 = vec4(1.0);
    for (int k = 0; k < MAX_HAZE_PLANES; k++) {
      if (k >= uHazePlaneCount) break;
      vec4 b = uHazePlanes[k * 3 + 1];
      vec3 lo;
      vec3 ld;
      float t = segmentCrossing(o, d, ${HAZE_EYE_REACH_M.toExponential(1)}, uHazePlanes[k * 3], b, lo, ld);
      if (t < 0.0) continue;
      float keep = crossingShare(uHazePlanes[k * 3 + 2], b, lo, ld, t);
      // Written, never blended in: a mix against the sentinel cancels a crossing to 0 in float32.
      if (k < 4) {
        at0[k] = t;
        share0[k] = keep;
      } else {
        at1[k - 4] = t;
        share1[k - 4] = keep;
      }
    }
  }

  float hazeSampleShare(float t, vec3 p, vec3 toApex, float reach, int planes, vec4 at0, vec4 at1, vec4 share0, vec4 share1) {
    vec4 s0 = mix(vec4(1.0), share0, step(at0, vec4(t)));
    vec4 s1 = mix(vec4(1.0), share1, step(at1, vec4(t)));
    float keep = s0.x * s0.y * s0.z * s0.w * s1.x * s1.y * s1.z * s1.w;
    for (int k = 0; k < MAX_HAZE_PLANES; k++) {
      if (k >= uHazePlaneCount || (planes >> k) == 0 || keep <= 0.0) break;
      if ((planes & (1 << k)) == 0) continue;
      vec4 a = uHazePlanes[k * 3];
      vec4 b = uHazePlanes[k * 3 + 1];
      vec3 n = vec3(sqrt(max(0.0, 1.0 - a.w * a.w)), 0.0, a.w);
      float z0 = dot(n, p - a.xyz);
      float z1 = z0 + dot(n, toApex) * reach;
      if (min(z0, z1) > b.z || max(z0, z1) < -b.z) continue;
      vec3 lo;
      vec3 ld;
      float c = segmentCrossing(p, toApex, reach, a, b, lo, ld);
      if (c >= 0.0) keep *= crossingShare(uHazePlanes[k * 3 + 2], b, lo, ld, c);
    }
    return keep;
  }
`
