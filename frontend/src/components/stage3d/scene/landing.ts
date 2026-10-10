/**
 * **Where a beam lands**, as the haze reads it: up to two planes, and nothing is hazed **behind
 * both**. The surfaces are shadowed by the colliders themselves (`occlusion.ts`, stage-light plan
 * session 3) and read the planes only for a light whose cone reaches more colliders than its list
 * holds. One plane is the face the beam's axis hit (`beamReach.ts`). The second
 * is there for a beam split across a convex edge, such as a follow spot aimed at the front of a stage:
 * the axis hits the riser, the half above it carries on to the deck, and the deck's plane is the
 * second. Behind both planes is the inside of the stage, the only part of the beam the edge stops.
 *
 * **A transmitting collider is never a landing plane** (scrim plan D7, session 5): a landing cast
 * skips a net and a painted cloth whose mask cuts holes (`beamReach.ts`'s `isLandingSurface`), so a
 * beam through a gauze or at a cut cloth lands on the set behind and its air runs on to there. What
 * stops it at the cloth is the haze's own list of planes (`hazePlanes.ts`): each beam's row names the
 * planes of that list it crosses ([hazePlanesCrossed]), and its march multiplies by their shares. The
 * surfaces that fall back to these planes multiply by the transmitting colliders their light's list
 * still names (`occlusion.ts`'s `lightTransmit`).
 *
 * Both planes ride one vec4: the beam's `aBeamLand` attribute and the light table's texel 3, which
 * have no room for a second (sixteen attributes; six texels). A collider turns about y only, so a
 * face's normal is straight up, straight down or level, and one float holds it ([landNormalCode]).
 *
 * Pure and three.js-free, so the packing is pinned by a node test.
 */

import { coneReachesBox } from './coneBox'

/**
 * How far behind a landing plane a fragment may sit and still be lit, and the skin of a collider
 * whose face is its drawn surface (`beamReach.ts`).
 */
export const REACH_EPS_M = 0.03

/**
 * A landed face, in three.js space: a point on it, its normal towards the light, and the hit
 * collider's [skin] — how far behind the face its drawn surface can lie.
 */
export interface LandingFace {
  px: number
  py: number
  pz: number
  nx: number
  ny: number
  nz: number
  skin: number
}

/** The codes for a normal straight up, straight down, and none; anything in ±π is a level heading. */
export const LAND_UP = 5
export const LAND_DOWN = -5
export const LAND_NONE = 8

/** A face's normal as one float: [LAND_UP] / [LAND_DOWN], or a level normal's heading `atan2(nz, nx)`. */
export function landNormalCode(nx: number, ny: number, nz: number): number {
  if (ny > 0.5) return LAND_UP
  if (ny < -0.5) return LAND_DOWN
  return Math.atan2(nz, nx)
}

/**
 * Pack [first] and [second] into [out] at [o]: `(code, offset)` for each, the offset being `n · p`
 * — with [skins], moved back by the face's skin beyond [REACH_EPS_M], so the plane the surfaces cut
 * at lies that far behind the face and a pleat's troughs are lit with its crests. The haze packs
 * without them: its plane is the face it hit, because a hull drawn behind a pleat's crests is
 * outside the cloth on its far side, and a march from there sums the beam through it. The landed
 * point itself is not moved. No first face (open air) is a plane nothing is behind; no second one a
 * plane everything is behind, so the first alone decides.
 */
export function packLanding(
  first: LandingFace | null,
  second: LandingFace | null,
  out: { [i: number]: number },
  o: number,
  skins = true,
): void {
  if (first == null) {
    out[o] = LAND_NONE
    out[o + 1] = -1
    out[o + 2] = LAND_NONE
    out[o + 3] = -1
    return
  }
  out[o] = landNormalCode(first.nx, first.ny, first.nz)
  out[o + 1] = planeOffset(first, skins)
  if (second == null) {
    out[o + 2] = LAND_NONE
    out[o + 3] = 1
  } else {
    out[o + 2] = landNormalCode(second.nx, second.ny, second.nz)
    out[o + 3] = planeOffset(second, skins)
  }
}

function planeOffset(face: LandingFace, skins: boolean): number {
  const n = face.nx * face.px + face.ny * face.py + face.nz * face.pz
  return skins ? n - Math.max(0, face.skin - REACH_EPS_M) : n
}

/**
 * The GLSL that unpacks [packLanding]: `landPlane(code, offset)` is the plane as `(n, n · p)`, a
 * point `x` behind it where `dot(n, x) < offset`; `behindLanding` is a point behind both by more
 * than `eps`.
 */
export const LANDING_GLSL = /* glsl */ `
  vec4 landPlane(float code, float offset) {
    vec3 n = code > ${LAND_NONE - 1}.5 ? vec3(0.0)
      : code > ${LAND_UP - 1}.0 ? vec3(0.0, 1.0, 0.0)
      : code < ${LAND_DOWN + 1}.0 ? vec3(0.0, -1.0, 0.0)
      : vec3(cos(code), 0.0, sin(code));
    return vec4(n, offset);
  }

  bool behindLanding(vec4 packed, vec3 p, float eps) {
    vec4 a = landPlane(packed.x, packed.y);
    if (dot(a.xyz, p) - a.w >= -eps) return false;
    vec4 b = landPlane(packed.z, packed.w);
    return dot(b.xyz, p) - b.w < -eps;
  }
`

/**
 * The GLSL twins of [planeReach] and [landingReach], over planes [LANDING_GLSL]'s `landPlane` has
 * unpacked.
 */
export const LANDING_REACH_GLSL = /* glsl */ `
  float planeReach(float apexSide, float side, float eps) {
    return side < eps && apexSide > eps ? (apexSide - eps) / (apexSide - side) : 1.0;
  }

  float landingReach(vec3 p, vec3 apex, vec4 a, vec4 b, float eps) {
    float pa = dot(a.xyz, p) - a.w;
    float pb = dot(b.xyz, p) - b.w;
    if (pa >= eps || pb >= eps) return 1.0;
    float s = 0.0;
    float oa = dot(a.xyz, apex) - a.w;
    float ob = dot(b.xyz, apex) - b.w;
    if (oa > eps) s = max(s, planeReach(oa, pa, eps));
    if (ob > eps) s = max(s, planeReach(ob, pb, eps));
    return s > 0.0 ? s : 1.0;
  }
`

/** [LANDING_GLSL]'s `landPlane`: the plane as `[nx, ny, nz, n · p]`, a zero normal for [LAND_NONE]. */
export function landPlane(code: number, offset: number): [number, number, number, number] {
  if (code > LAND_NONE - 0.5) return [0, 0, 0, offset]
  if (code > LAND_UP - 1) return [0, 1, 0, offset]
  if (code < LAND_DOWN + 1) return [0, -1, 0, offset]
  return [Math.cos(code), 0, Math.sin(code), offset]
}

/**
 * How far along the line from an apex to a point a plane lets a beam's hull reach, as a fraction of
 * the way: where the line comes within [eps] of the plane, given how far in front of it the apex
 * ([apexSide]) and the point ([side]) lie. 1 for a point [eps] or more in front, and for a plane the
 * apex is not in front of, which never stops it.
 */
export function planeReach(apexSide: number, side: number, eps: number): number {
  return side < eps && apexSide > eps ? (apexSide - eps) / (apexSide - side) : 1
}

/**
 * How far along the line from the [apex] to a beam hull's vertex [p] the hull may reach, as a
 * fraction of the way, with nothing of it behind both landed planes ([packed], as [packLanding]
 * wrote them at [o]): to where the line last comes within [eps] of one of them, or all the way for a
 * vertex [eps] in front of either. Every vertex of a hull lies on such a line, so moved there the
 * hull keeps its shape and loses only what lies behind the surface it landed on.
 */
export function landingReach(
  p: readonly [number, number, number],
  apex: readonly [number, number, number],
  packed: { readonly [i: number]: number },
  o: number,
  eps: number,
): number {
  const side = (plane: [number, number, number, number], q: readonly [number, number, number]) =>
    plane[0] * q[0] + plane[1] * q[1] + plane[2] * q[2] - plane[3]
  const a = landPlane(packed[o], packed[o + 1])
  const b = landPlane(packed[o + 2], packed[o + 3])
  const pa = side(a, p)
  const pb = side(b, p)
  if (pa >= eps || pb >= eps) return 1
  const oa = side(a, apex)
  const ob = side(b, apex)
  let s = 0
  if (oa > eps) s = Math.max(s, planeReach(oa, pa, eps))
  if (ob > eps) s = Math.max(s, planeReach(ob, pb, eps))
  return s > 0 ? s : 1
}

/**
 * Which of the haze's planes a beam crosses (scrim plan D10): bit k for the list's k-th plane, which
 * the beam's row carries (`aBeamFx.z`) so its march tests only those on its own side. [planes] holds
 * [count] planes [stride] floats apart, each a packed collider's texels (`hazePlanes.ts`'s list):
 * its centre and its turn's folded cosine, then its half-extents. The beam is its cull cone — from
 * the apex ([ax], [ay], [az]) along the unit ([dx], [dy], [dz]), [length] long, half-angle cosine
 * [cosCone] and sine [sinCone] — tested against each box as conservatively as a light's colliders
 * are (`coneBox.ts`), so a plane the beam might cross is named and the march's own test decides.
 * Nothing for a beam not drawn ([length] 0).
 */
export function hazePlanesCrossed(
  planes: ArrayLike<number>,
  count: number,
  stride: number,
  ax: number, ay: number, az: number,
  dx: number, dy: number, dz: number,
  length: number, cosCone: number, sinCone: number,
): number {
  if (!(length > 0)) return 0
  let bits = 0
  for (let k = 0; k < count; k++) {
    const o = k * stride
    const cos = planes[o + 3]
    // The turn is folded into [0, π] (`occlusion.ts`'s `packColliders`), so its sine is ≥ 0.
    const sin = Math.sqrt(Math.max(0, 1 - cos * cos))
    if (coneReachesBox(ax, ay, az, dx, dy, dz, length, cosCone, sinCone, planes[o], planes[o + 1], planes[o + 2], cos, sin, planes[o + 4], planes[o + 5], planes[o + 6])) {
      bits |= 1 << k
    }
  }
  return bits
}
