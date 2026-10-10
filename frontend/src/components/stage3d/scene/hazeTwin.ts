import { boxCollider, type Collider } from './beamReach'
import { hazeBeamReach, hazeEyeCrossings, hazeSampleShare, makeHazeEyeCrossings, type HazePlaneList } from './hazePlanes'
import { TWIN_MASK_IMAGE } from './occlusionTwin'
import { SCRIM_THREAD_SHARE, scrimShare } from './scrimOpen'

/**
 * **A fixed scene the haze's two halves are pinned on** (scrim plan session 5), as
 * `occlusionTwin.ts` is for the shadows': three planes — a sharkstooth gauze, the same gathered
 * twice over, and a cut cloth holed down its stage-right half (the occlusion twin's atlas) — and
 * march samples through them, each with the share it should keep. `hazePlanes.test.ts` runs
 * `hazeSampleShare` (TypeScript) over it, and the occlusion bench's `?only=haze`
 * (`occlusionBench.ts`) runs `HAZE_PLANES_GLSL`'s `hazeEyeCrossings` and `hazeSampleShare` over the
 * same cases on a real GPU and prints both.
 */

export interface HazeTwinCase {
  name: string
  /** Where the view ray starts and its unit direction, three.js space. */
  o: [number, number, number]
  d: [number, number, number]
  /** How far along it the sample lies. */
  t: number
  /** The beam: its apex, its unit axis and the apex → aperture distance. */
  apex: [number, number, number]
  axis: [number, number, number]
  near: number
  /** The planes the beam's row names. */
  planes: number
  want: number
}

const R = SCRIM_THREAD_SHARE.SHARKSTOOTH

/** The scene's planes, upstage of the setting line: a gauze at 1 m, one gathered twice at 3 m, a cut cloth at 5 m. */
export function hazeTwinScene(): Collider[] {
  const net = (z: number, gather: number) => {
    const c = boxCollider(0, 2, z, 3, 2, 0.005, 0, 0.04, 0.04)
    c.transmit = { kind: 'angle', r: R, gather }
    return c
  }
  const cut = boxCollider(0, 2, -5, 3, 2, 0.005, 0, 0.04, 0.04)
  cut.transmit = { kind: 'mask', image: TWIN_MASK_IMAGE, uv: { u0: 0, u1: 1, v0: 0, v1: 1 } }
  return [net(-1, 1), net(-3, 2), cut]
}

/** The eye the list is filled from: 5 m out front, at head height. Its list holds all three, nearest first. */
export const HAZE_TWIN_EYE: [number, number, number] = [0, 2, 5]
const ALL = 0b111

/** A sample at ([x], 2, [z]) seen from [from]: the view ray and how far along it. */
function look(x: number, z: number, from: [number, number, number] = HAZE_TWIN_EYE): Pick<HazeTwinCase, 'o' | 'd' | 't'> {
  const dx = x - from[0]
  const dy = 2 - from[1]
  const dz = z - from[2]
  const t = Math.hypot(dx, dy, dz)
  return { o: from, d: [dx / t, dy / t, dz / t], t }
}

/** A back light 12 m up the stage at [x], shining square at the house. */
function backLight(x: number): Pick<HazeTwinCase, 'apex' | 'axis' | 'near'> {
  return { apex: [x, 2, -12], axis: [0, 0, 1], near: 0.1 }
}

/** A net's share crossed along [d] (every plane faces the house): `open(θ)^gather`. */
const net = (d: readonly number[], gather = 1) => scrimShare(Math.abs(d[2]), R, gather)

export function hazeTwinCases(): HazeTwinCase[] {
  const square = [0, 0, 1]
  const front = look(0, 0)
  const behindOne = look(0, -2)
  const behindTwo = look(0, -4)
  const atHole = look(-1.5, -6)
  const oblique = look(-1, -2, [6, 2, 5])
  const between = look(-1.5, -2)
  return [
    { name: 'in front of every plane', ...front, ...backLight(0), planes: 0, want: 1 },
    { name: 'behind the gauze', ...behindOne, ...backLight(0), planes: 0, want: net(behindOne.d) },
    { name: 'behind both gauzes', ...behindTwo, ...backLight(0), planes: 0, want: net(behindTwo.d) * net(behindTwo.d, 2) },
    // Past the cloth, through a hole in it: the cloth passes it whole, the gauzes in front do not.
    { name: 'behind the cut cloth, at a hole', ...atHole, ...backLight(-1.5), planes: 0, want: net(atHole.d) * net(atHole.d, 2) },
    { name: 'behind the cut cloth, at its cloth', ...look(1.5, -6), ...backLight(1.5), planes: 0, want: 0 },
    { name: 'the gauze from 45°', ...oblique, ...backLight(0), planes: 0, want: net(oblique.d) },
    { name: 'the back light, in front of every plane', ...look(-1.5, 0), ...backLight(-1.5), planes: ALL, want: net(square) * net(square, 2) },
    { name: 'the back light, through the cloth', ...look(1.5, 0), ...backLight(1.5), planes: ALL, want: 0 },
    { name: 'the back light, its row naming none', ...look(-1.5, 0), ...backLight(-1.5), planes: 0, want: 1 },
    { name: 'between the gauzes, from both sides', ...between, ...backLight(-1.5), planes: ALL, want: net(between.d) * net(square, 2) },
  ]
}

/**
 * Case [c] through the TypeScript twin, exactly as the march takes a sample: the eye's crossings of
 * [list] along the view ray, then the share at `o + d·t` with the segment towards the apex — the
 * march's `-lightDir` — run to the aperture's plane.
 */
export function hazeTwinShare(list: HazePlaneList, c: HazeTwinCase, atlas: Uint8Array): number {
  const crossings = hazeEyeCrossings(list, ...c.o, ...c.d, makeHazeEyeCrossings(), atlas)
  const px = c.o[0] + c.d[0] * c.t
  const py = c.o[1] + c.d[1] * c.t
  const pz = c.o[2] + c.d[2] * c.t
  const rx = px - c.apex[0]
  const ry = py - c.apex[1]
  const rz = pz - c.apex[2]
  const relLen = Math.max(Math.hypot(rx, ry, rz), 1e-4)
  const cosAngle = (rx * c.axis[0] + ry * c.axis[1] + rz * c.axis[2]) / relLen
  const reach = hazeBeamReach(relLen, cosAngle, c.near)
  return hazeSampleShare(list, crossings, c.t, px, py, pz, -rx / relLen, -ry / relLen, -rz / relLen, reach, c.planes, atlas)
}
