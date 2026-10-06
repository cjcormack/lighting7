/**
 * **Where a beam lands**, as the haze reads it: up to two planes, and nothing is hazed **behind
 * both**. The surfaces are shadowed by the colliders themselves (`occlusion.ts`, stage-light plan
 * session 3) and read the planes only for a light whose cone reaches more colliders than its list
 * holds. One plane is the face the beam's axis hit (`beamReach.ts`). The second
 * is there for a beam split across a convex edge, such as a follow spot aimed at the front of a stage:
 * the axis hits the riser, the half above it carries on to the deck, and the deck's plane is the
 * second. Behind both planes is the inside of the stage, the only part of the beam the edge stops.
 *
 * Both planes ride one vec4: the beam's `aBeamLand` attribute and the light table's texel 3, which
 * have no room for a second (sixteen attributes; six texels). A collider turns about y only, so a
 * face's normal is straight up, straight down or level, and one float holds it ([landNormalCode]).
 *
 * Pure and three.js-free, so the packing is pinned by a node test.
 */

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
