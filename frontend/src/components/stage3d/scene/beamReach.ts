import type { StageElementDto } from '../../../api/stageElementApi'
import { REACH_EPS_M } from './landing'
import { pleatShape } from './pleat'
import { elementBaseZ, type ElementBuild, type PartGeometry } from './sceneParts'

/**
 * **Axial beam reach** (stage-view plan session 3): the first surface on a beam's axis, which
 * stands in for occlusion until the quality tier's shadow maps (`FU-STAGE-QUALITY-TIER`).
 *
 * Every surface a beam can stop at is a **collider** — an oriented box in the desk's three.js space
 * (`lib/stageCoords.ts`: x right, y up, z towards the house), turned about y only, as the region
 * OBBs the beam shaders already shadow-test are — which [`landing.ts`](./landing.ts) relies on to
 * pack a face's normal in one float. A wall is a thin slab behind its face, a deck its whole box.
 * [beamReach] casts the beam's axis against them and answers the nearest hit and the **plane** of
 * the face it hit. The director draws the beam's cone to the hit, and the surface shader lights
 * nothing behind that plane (behind it and a second face, for a beam split across an edge) — so a
 * pool on the floor lands whole, however oblique the beam, while the floor under a deck the beam has
 * landed on stays dark.
 *
 * Pure and three.js-free: numbers in, numbers out, allocation-free on the per-frame path.
 */

export interface Collider {
  /** Centre, three.js space. */
  cx: number
  cy: number
  cz: number
  /** Half-extents along the box's own axes (x across, y up, z towards the house before the turn). */
  hx: number
  hy: number
  hz: number
  /** cos and sin of the box's turn about y — the region shaders' `(cos yaw, sin yaw)` pair. */
  cos: number
  sin: number
  /**
   * How far behind a level face (one whose normal is level) the drawn surface can lie: a pleat's
   * depth for cloth, the radius for a cylinder, [REACH_EPS_M] for a box whose faces are its surface.
   * The landing plane is moved back by it (`landing.ts`'s `packLanding`), so the whole lit side of
   * what the beam hit is lit.
   */
  skin: number
  /**
   * The same for the top and bottom faces: [skin], except a cylinder's, whose flat top is its
   * surface ([REACH_EPS_M]), and a cone's, whose sloped side faces them (its height).
   */
  capSkin: number
}

/** Where a beam's axis stops, written by [beamReach] into a caller's object. */
export interface BeamHit {
  /** Distance along the (unit) axis to the surface. */
  t: number
  /** The hit face's normal, towards the light. */
  nx: number
  ny: number
  nz: number
  /** The hit face's skin: the collider's [Collider.capSkin] for its top or bottom, else its [Collider.skin]. */
  skin: number
  /** The collider hit, whose edges `edgeLanding` draws a shadow from. */
  collider: Collider | null
}

/** How thick a quad's slab is: enough for the slab test to be stable, far thinner than anything built. */
const SLAB_M = 0.02
/** A hit this close to the lens is the fixture's own mount, not a surface in front of it. */
export const MIN_REACH_M = 0.05

/**
 * The nearest collider the ray from [ox, oy, oz] along the unit [dx, dy, dz] meets within [maxT],
 * written into [out]; false when it meets none. A box the ray starts inside is skipped — a head
 * inside a deck's box is mounted on it, not blocked by it.
 */
export function beamReach(
  ox: number,
  oy: number,
  oz: number,
  dx: number,
  dy: number,
  dz: number,
  colliders: readonly Collider[],
  maxT: number,
  out: BeamHit,
): boolean {
  let best = maxT
  let found = false
  for (let i = 0; i < colliders.length; i++) {
    const b = colliders[i]
    const rx = ox - b.cx
    const ry = oy - b.cy
    const rz = oz - b.cz
    const c = b.cos
    const s = b.sin
    // Into the box's frame by −yaw — the shaders' `rayObbT` rotation.
    const lox = c * rx - s * rz
    const loz = s * rx + c * rz
    const ldx = c * dx - s * dz
    const ldz = s * dx + c * dz
    let tNear = -Infinity
    let tFar = Infinity
    let axis = -1
    let sign = 0
    // x
    if (Math.abs(ldx) < 1e-12) {
      if (Math.abs(lox) > b.hx) continue
    } else {
      const t1 = (-b.hx - lox) / ldx
      const t2 = (b.hx - lox) / ldx
      const lo = Math.min(t1, t2)
      const hi = Math.max(t1, t2)
      if (lo > tNear) {
        tNear = lo
        axis = 0
        sign = ldx > 0 ? -1 : 1
      }
      if (hi < tFar) tFar = hi
    }
    // y
    if (Math.abs(dy) < 1e-12) {
      if (Math.abs(ry) > b.hy) continue
    } else {
      const t1 = (-b.hy - ry) / dy
      const t2 = (b.hy - ry) / dy
      const lo = Math.min(t1, t2)
      const hi = Math.max(t1, t2)
      if (lo > tNear) {
        tNear = lo
        axis = 1
        sign = dy > 0 ? -1 : 1
      }
      if (hi < tFar) tFar = hi
    }
    // z
    if (Math.abs(ldz) < 1e-12) {
      if (Math.abs(loz) > b.hz) continue
    } else {
      const t1 = (-b.hz - loz) / ldz
      const t2 = (b.hz - loz) / ldz
      const lo = Math.min(t1, t2)
      const hi = Math.max(t1, t2)
      if (lo > tNear) {
        tNear = lo
        axis = 2
        sign = ldz > 0 ? -1 : 1
      }
      if (hi < tFar) tFar = hi
    }
    if (tNear > tFar || tFar < 0 || tNear < MIN_REACH_M || tNear >= best || axis < 0) continue
    best = tNear
    found = true
    out.skin = axis === 1 ? b.capSkin : b.skin
    out.collider = b
    // The face's normal, back out of the box's frame by +yaw.
    if (axis === 1) {
      out.nx = 0
      out.ny = sign
      out.nz = 0
    } else {
      const lx = axis === 0 ? sign : 0
      const lz = axis === 2 ? sign : 0
      out.nx = c * lx + s * lz
      out.ny = 0
      out.nz = -s * lx + c * lz
    }
  }
  if (found) out.t = best
  return found
}

/** A box collider from a centre and half-extents in three.js space, turned [yawRad] about y. */
export function boxCollider(
  cx: number,
  cy: number,
  cz: number,
  hx: number,
  hy: number,
  hz: number,
  yawRad = 0,
  skin = REACH_EPS_M,
  capSkin = skin,
): Collider {
  return { cx, cy, cz, hx, hy, hz, cos: Math.cos(yawRad), sin: Math.sin(yawRad), skin, capSkin }
}

/** A part's collider in its element's lighting frame: centre offset, half-extents and skins. */
export interface PartBox {
  ox: number
  oy: number
  oz: number
  hx: number
  hy: number
  hz: number
  skin: number
  capSkin: number
}

/**
 * A part's box in its element's lighting frame — for a quad, the thin slab behind its face; for a
 * pleat, just deep enough to hold the cloth's folds ([pleatShape]).
 */
export function partBox(geometry: PartGeometry): PartBox {
  const b = shapeBox(geometry)
  if (geometry.shape !== 'cylinder') return { ...b, capSkin: b.skin }
  // A straight cylinder's top is flat; a cone's sloped side faces up or down, so a beam landing on
  // its top lights it all the way down.
  return { ...b, capSkin: geometry.rTop === geometry.rBottom ? REACH_EPS_M : geometry.h }
}

function shapeBox(geometry: PartGeometry): Omit<PartBox, 'capSkin'> {
  const t = SLAB_M / 2
  const e = REACH_EPS_M
  switch (geometry.shape) {
    case 'box':
      return { ox: 0, oy: 0, oz: 0, hx: geometry.w / 2, hy: geometry.d / 2, hz: geometry.h / 2, skin: e }
    case 'cylinder': {
      const r = Math.max(geometry.rTop, geometry.rBottom)
      return { ox: 0, oy: 0, oz: 0, hx: r, hy: r, hz: geometry.h / 2, skin: r }
    }
    case 'disc':
      return { ox: 0, oy: 0, oz: 0, hx: geometry.r, hy: geometry.d / 2, hz: geometry.r, skin: e }
    case 'pleat': {
      const a = pleatShape().amplitudeM
      return { ox: 0, oy: 0, oz: 0, hx: geometry.w / 2, hy: a, hz: geometry.h / 2, skin: 2 * a + e }
    }
    case 'quad':
      switch (geometry.facing) {
        case 'up':
          return { ox: 0, oy: 0, oz: -t, hx: geometry.w / 2, hy: geometry.h / 2, hz: t, skin: e }
        case 'down':
          return { ox: 0, oy: 0, oz: t, hx: geometry.w / 2, hy: geometry.h / 2, hz: t, skin: e }
        case 'upstage':
          return { ox: 0, oy: -t, oz: 0, hx: geometry.w / 2, hy: t, hz: geometry.h / 2, skin: e }
        case 'downstage':
          return { ox: 0, oy: t, oz: 0, hx: geometry.w / 2, hy: t, hz: geometry.h / 2, skin: e }
        case 'left':
          return { ox: -t, oy: 0, oz: 0, hx: t, hy: geometry.w / 2, hz: geometry.h / 2, skin: e }
        case 'right':
          return { ox: t, oy: 0, oz: 0, hx: t, hy: geometry.w / 2, hz: geometry.h / 2, skin: e }
      }
  }
}

/**
 * The colliders of one built element, placed by its pose: each colliding part's box, turned by the
 * element's yaw about its origin and moved to its origin — its base, a platform's top, or a flown
 * piece's trim.
 */
export function elementColliders(
  element: Pick<StageElementDto, 'kind' | 'params' | 'positionX' | 'positionY' | 'positionZ' | 'yawDeg'>,
  build: ElementBuild,
): Collider[] {
  const out: Collider[] = []
  const yaw = (element.yawDeg * Math.PI) / 180
  const c = Math.cos(yaw)
  const s = Math.sin(yaw)
  const baseZ = elementBaseZ(element)
  for (const part of build.parts) {
    if (!part.collides) continue
    const b = partBox(part.geometry)
    // The part's centre in the element's lighting frame, turned by +yaw (anticlockwise from above).
    const lx = part.at.x + b.ox
    const ly = part.at.y + b.oy
    const wx = element.positionX + c * lx - s * ly
    const wy = element.positionY + s * lx + c * ly
    const wz = baseZ + part.at.z + b.oz
    // Lighting → three: (x, z, −y); lighting (hx, hy, hz) → three (hx, hz, hy).
    out.push(boxCollider(wx, wz, -wy, b.hx, b.hz, b.hy, yaw, b.skin, b.capSkin))
  }
  return out
}
