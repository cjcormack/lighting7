import type { Vector3 } from 'three'

/**
 * A beam's lobes and what they can reach: the prism lobe splay, and the cone-vs-region cull that
 * says which regions a lobe's volume must shadow-test. Where a beam *lands* is no longer placed
 * here — the receiver cookies went with stage-view plan session 3; each lobe is a row of the light
 * table, and every surface lights itself (`scene/surfaceShader.ts`).
 *
 * Peer to `beamOptics`, and pure in the same sense — every function is a total function of
 * the vectors and scalars handed to it, with no renderer, no React and no clock. It takes
 * `three` *types* only (`Vector3` as a type import), so nothing here drags in fiber or drei
 * and the tests run in the default node environment.
 *
 * These are called from `useFrame`, once per fixture per lobe per frame, so they are
 * deliberately allocation-free: every one answers a number or writes into a caller supplied `out`
 * vector. Do not make them return fresh objects.
 */

/**
 * Direction of prism lobe at `angle` around the splay circle: the beam axis
 * tipped `splayRad` toward the beam-local (bx, by) basis. Pure and
 * allocation-free (writes into `out`).
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

/**
 * Conservative cone-vs-sphere reach test behind [regionShadowMask]. Conservative so a region never
 * drops out of a lobe's shadow tests while the cone still touches its bounding sphere — the
 * shader's per-fragment tests handle the exact silhouette.
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
 * The bits of the regions a lobe can reach (bit i set = region i is within [beamLength] and inside
 * the slacked cone), stored as the beam's `BeamWrite.shadowMask`: the volume shader shadow-tests only
 * those, so the common case runs 0–2 ray-OBB tests instead of 16.
 */
export function regionShadowMask(
  origin: Vector3,
  dir: Vector3,
  beamLength: number,
  cosCone: number,
  sinCone: number,
  regions: ReadonlyArray<{ boundingCenter: Vector3; boundingRadius: number }>,
): number {
  let mask = 0
  const n = Math.min(regions.length, 32)
  for (let i = 0; i < n; i++) {
    const r = regions[i]
    if (coneReachesSphere(origin, dir, beamLength, cosCone, sinCone, r.boundingCenter, r.boundingRadius)) {
      mask |= 1 << i
    }
  }
  return mask
}
