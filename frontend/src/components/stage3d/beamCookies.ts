import type { Vector3 } from 'three'

/**
 * Where a beam lands: the prism lobe splay and the receiver-cookie placement/cull maths.
 *
 * Peer to `beamOptics`, and pure in the same sense — every function is a total function of
 * the vectors and scalars handed to it, with no renderer, no React and no clock. It takes
 * `three` *types* only (`Vector3` as a type import), so nothing here drags in fiber or drei
 * and the tests run in the default node environment.
 *
 * The emitter argument is typed structurally — each function names only the one write method
 * it calls — rather than importing `EmittersHandle` from `StageEmitters`, which is an R3F
 * component module. That is what keeps this module node-runnable, and it lets a test pass a
 * bare `vi.fn()` recorder.
 *
 * These are called from `useFrame`, once per fixture per lobe per frame, so they are
 * deliberately allocation-free: every one either writes through the emitter or into a caller
 * supplied `out` vector. Do not make them return fresh objects.
 */

/**
 * Direction of prism lobe at `angle` around the splay circle: the beam axis
 * tipped `splayRad` toward the beam-local (bx, by) basis. Pure and
 * allocation-free (writes into `out`), like the cookie helpers.
 */
export function computeLobeDirection(
  dir: Vector3,
  bx: Vector3,
  by: Vector3,
  splayRad: number,
  angle: number,
  out: Vector3,
): Vector3 {
  const sinS = Math.sin(splayRad)
  const cosS = Math.cos(splayRad)
  return out
    .copy(dir)
    .multiplyScalar(cosS)
    .addScaledVector(bx, Math.cos(angle) * sinS)
    .addScaledVector(by, Math.sin(angle) * sinS)
    .normalize()
}

// Resize + reposition the floor cookie to bound the cone's actual floor reach.
// `sinCone` and `side` are precomputed against the same slacked half-angle as
// the region cull so the horizon fade and bounding box share that padding.
export function updateFloorCookie(
  emitters: {
    writeFloorMatrix: (
      slot: number,
      lobe: number,
      visible: boolean,
      cx: number,
      cz: number,
      side: number,
    ) => void
  },
  slot: number,
  lobe: number,
  origin: Vector3,
  dir: Vector3,
  beamLength: number,
  sinCone: number,
  side: number,
): void {
  if (dir.y >= sinCone) {
    emitters.writeFloorMatrix(slot, lobe, false, 0, 0, 0)
    return
  }
  // dir.y near zero would project the centerline to a huge distance; fall
  // back to fixture XZ in that case (lit area starts at origin anyway).
  let cx = origin.x
  let cz = origin.z
  if (dir.y < -1e-3) {
    const t = Math.min(-origin.y / dir.y, beamLength)
    if (t > 0) {
      cx = origin.x + t * dir.x
      cz = origin.z + t * dir.z
    }
  }
  emitters.writeFloorMatrix(slot, lobe, true, cx, cz, side)
}

/**
 * Conservative cone-vs-sphere reach test shared by the region, wash-region and
 * wall culls. Conservative so a cookie never pops out while the cone is still
 * touching its bounding sphere — the shader's per-fragment shadow + cosAngle
 * tests handle the exact silhouette.
 *
 * Exported because the per-pixel wash cull in `FixtureModel` shares it; there is
 * no second implementation of this test anywhere.
 */
export function coneReachesSphere(
  origin: Vector3,
  dir: Vector3,
  beamLength: number,
  cosCone: number,
  sinCone: number,
  center: Vector3,
  radius: number,
): boolean {
  const dx = center.x - origin.x
  const dy = center.y - origin.y
  const dz = center.z - origin.z
  const dist2 = dx * dx + dy * dy + dz * dz
  const reach = beamLength + radius
  if (dist2 > reach * reach) return false
  if (dist2 < radius * radius) return true
  const dist = Math.sqrt(dist2)
  const sinAR = radius / dist
  const cosAR = Math.sqrt(Math.max(0, 1 - sinAR * sinAR))
  const cosBoundary = cosCone * cosAR - sinCone * sinAR
  const cosAngle = (dir.x * dx + dir.y * dy + dir.z * dz) / dist
  return cosAngle >= cosBoundary
}

/**
 * Toggle each region cookie's visibility, and return the same bits as the
 * shadow mask (bit i set = the beam can reach region i) for
 * `EmittersHandle.writeShadowMask` — one cull pass feeds both.
 */
export function cullRegionCookies(
  emitters: {
    writeRegionVisibility: (slot: number, lobe: number, regionIdx: number, visible: boolean) => void
  },
  slot: number,
  lobe: number,
  origin: Vector3,
  dir: Vector3,
  beamLength: number,
  cosCone: number,
  sinCone: number,
  regions: ReadonlyArray<{ cookieCenter: Vector3; cookieBoundingRadius: number }>,
): number {
  let mask = 0
  for (let i = 0; i < regions.length; i++) {
    const r = regions[i]
    const visible = coneReachesSphere(
      origin,
      dir,
      beamLength,
      cosCone,
      sinCone,
      r.cookieCenter,
      r.cookieBoundingRadius,
    )
    emitters.writeRegionVisibility(slot, lobe, i, visible)
    if (visible && i < 32) mask |= 1 << i
  }
  return mask
}

/**
 * Size + place the upstage wall cookie to the beam's footprint on the wall
 * plane, clamped to the wall rectangle (a quad hanging past the wall's edge
 * would glow in mid-air). Mirrors `updateFloorCookie`, with the extra clamp
 * because the wall — unlike the mathematical floor plane — has edges.
 */
export function updateWallCookie(
  emitters: {
    writeWallMatrix: (
      slot: number,
      lobe: number,
      visible: boolean,
      cx: number,
      cy: number,
      sideX: number,
      sideY: number,
    ) => void
  },
  slot: number,
  lobe: number,
  origin: Vector3,
  dir: Vector3,
  beamLength: number,
  sinCone: number,
  side: number,
  wall: { z: number; halfWidth: number; height: number },
): void {
  // No part of the cone points upstage, or the wall is out of reach.
  if (dir.z >= sinCone || origin.z - wall.z > beamLength) {
    emitters.writeWallMatrix(slot, lobe, false, 0, 0, 0, 0)
    return
  }
  let cx = origin.x
  let cy = origin.y
  if (dir.z < -1e-3) {
    const t = Math.min((wall.z - origin.z) / dir.z, beamLength)
    if (t > 0) {
      cx = origin.x + t * dir.x
      cy = origin.y + t * dir.y
    }
  }
  const x0 = Math.max(cx - side / 2, -wall.halfWidth)
  const x1 = Math.min(cx + side / 2, wall.halfWidth)
  const y0 = Math.max(cy - side / 2, 0)
  const y1 = Math.min(cy + side / 2, wall.height)
  if (x1 <= x0 || y1 <= y0) {
    emitters.writeWallMatrix(slot, lobe, false, 0, 0, 0, 0)
    return
  }
  emitters.writeWallMatrix(slot, lobe, true, (x0 + x1) / 2, (y0 + y1) / 2, x1 - x0, y1 - y0)
}
