import { coneReachesSphere } from '../beamLobes'

/**
 * **Whether a beam's cone reaches an oriented box**, conservatively, as [coneReachesSphere] is for
 * a sphere, but tighter for a big box: a box whose bounding sphere is wide for its distance from the
 * apex is halved along its longest axis and each half tried in turn, until a piece the cone reaches
 * is small enough to trust or no piece is left. A room's wall is 18 m long, and the sphere round it
 * reaches into every cone pointed anywhere near it.
 *
 * Two callers ask it: each light's collider list (`occlusion.ts`'s `cullLightColliders`) and each
 * beam's haze planes (`landing.ts`'s `hazePlanesCrossed`). A leaf of its own, because `landing.ts`
 * cannot import `occlusion.ts` (which reaches it back through `lightTable.ts`).
 *
 * Pure and allocation-free: the scratch below is module-level.
 */

/**
 * A sphere at most this wide for its distance from the apex is tight enough to trust: past it, a
 * box is halved along its longest axis and each half tried.
 */
const SPLIT_ANGULAR_RADIUS = 0.05
/** How many halvings a box may take before its last sphere is trusted: 2¹⁰ pieces at most. */
const SPLIT_DEPTH = 10
/** The halving's stack: depth + 1 entries of a centre in the box's frame and half-extents. */
const SPLIT_STACK = new Float64Array((SPLIT_DEPTH + 1) * 7)
const SIGNS = [-1, 1] as const

const APEX = { x: 0, y: 0, z: 0 }
const AXIS = { x: 0, y: 0, z: 0 }
const CENTRE = { x: 0, y: 0, z: 0 }

/**
 * Whether the cone from ([ax], [ay], [az]) along the unit ([dx], [dy], [dz]), [length] long and of
 * half-angle cosine [cosCone] and sine [sinCone], reaches the box centred at ([cx], [cy], [cz]),
 * turned about y by the angle whose cosine and sine are [cos] and [sin], of half-extents [hx],
 * [hy], [hz].
 */
export function coneReachesBox(
  ax: number, ay: number, az: number,
  dx: number, dy: number, dz: number,
  length: number, cosCone: number, sinCone: number,
  cx: number, cy: number, cz: number,
  cos: number, sin: number,
  hx: number, hy: number, hz: number,
): boolean {
  APEX.x = ax
  APEX.y = ay
  APEX.z = az
  AXIS.x = dx
  AXIS.y = dy
  AXIS.z = dz
  const stack = SPLIT_STACK
  stack[0] = 0
  stack[1] = 0
  stack[2] = 0
  stack[3] = hx
  stack[4] = hy
  stack[5] = hz
  stack[6] = 0
  let top = 1
  while (top > 0) {
    top--
    const o = top * 7
    const lx = stack[o]
    const ly = stack[o + 1]
    const lz = stack[o + 2]
    const px = stack[o + 3]
    const py = stack[o + 4]
    const pz = stack[o + 5]
    const depth = stack[o + 6]
    // Out of the box's frame by +yaw, as `beamReach` turns a face's normal.
    CENTRE.x = cx + cos * lx + sin * lz
    CENTRE.y = cy + ly
    CENTRE.z = cz - sin * lx + cos * lz
    const r = Math.hypot(px, py, pz)
    if (!coneReachesSphere(APEX, AXIS, length, cosCone, sinCone, CENTRE, r)) continue
    const dist = Math.hypot(CENTRE.x - ax, CENTRE.y - ay, CENTRE.z - az)
    if (depth >= SPLIT_DEPTH || r <= SPLIT_ANGULAR_RADIUS * dist) return true
    // Halve along the longest axis: two pieces, depth-first, the stack never deeper than SPLIT_DEPTH.
    const axis = px >= py && px >= pz ? 0 : py >= pz ? 1 : 2
    for (const sign of SIGNS) {
      const p = top * 7
      stack[p] = axis === 0 ? lx + (sign * px) / 2 : lx
      stack[p + 1] = axis === 1 ? ly + (sign * py) / 2 : ly
      stack[p + 2] = axis === 2 ? lz + (sign * pz) / 2 : lz
      stack[p + 3] = axis === 0 ? px / 2 : px
      stack[p + 4] = axis === 1 ? py / 2 : py
      stack[p + 5] = axis === 2 ? pz / 2 : pz
      stack[p + 6] = depth + 1
      top++
    }
  }
  return false
}
