import { boxCollider, type Collider } from './beamReach'
import { MASK_ATLAS_LAYERS, MASK_SIZE } from './sceneMasks'
import { SCRIM_THREAD_SHARE } from './scrimOpen'

/**
 * **A fixed scene the occlusion test's two halves are pinned on** (scrim plan session 3): each case a
 * collider, a segment from a fragment towards a lamp and the share it should keep. `occlusion.test.ts`
 * runs [segmentTransmit] (TypeScript) over it, and the occlusion bench's `?only=twin`
 * (`occlusionBench.ts`) runs `OCCLUSION_GLSL`'s `boxTransmit` over the same cases on a real GPU and
 * prints both — the GLSL and its twin, compared number for number.
 *
 * Every case crosses a 2 × 2 m box centred 2 m down −z from its fragment's lamp side, its face
 * square to z: a solid one, a sharkstooth scrim crossed at 0°, 45° and 80° from its normal, the same
 * scrim gathered two deep, and a cut cloth crossed through a hole and through cloth.
 */

export interface TwinCase {
  name: string
  collider: Collider
  /** The fragment, three.js space. */
  p: [number, number, number]
  /** Towards the lamp, unit. */
  d: [number, number, number]
  tMax: number
  /** The share the segment should keep. */
  want: number
}

/** The cut cloth's mask: layer 0, holes in its left half (u < ½), cloth in its right. */
export const TWIN_MASK_LAYER = 0
export const TWIN_MASK_IMAGE = 'e'.repeat(64)

/** The atlas [twinCases] samples: layer 0 cut down its middle, every other layer cloth. */
export function twinAtlas(): Uint8Array {
  const atlas = new Uint8Array(MASK_ATLAS_LAYERS * MASK_SIZE * MASK_SIZE).fill(255)
  for (let j = 0; j < MASK_SIZE; j++) {
    for (let i = 0; i < MASK_SIZE / 2; i++) atlas[TWIN_MASK_LAYER * MASK_SIZE * MASK_SIZE + j * MASK_SIZE + i] = 0
  }
  return atlas
}

const R = SCRIM_THREAD_SHARE.SHARKSTOOTH
const CENTRE: [number, number, number] = [0, 0, -2]

/** A segment that crosses the box's centre at [deg] from its normal, from a metre before it. */
function across(deg: number, x = 0): { p: [number, number, number]; d: [number, number, number] } {
  const a = (deg * Math.PI) / 180
  const d: [number, number, number] = [Math.sin(a), 0, -Math.cos(a)]
  return { p: [CENTRE[0] + x - d[0], CENTRE[1] - d[1], CENTRE[2] - d[2]], d }
}

function scrim(gather: number): Collider {
  const c = boxCollider(...CENTRE, 1, 1, 0.005, 0, 0.04, 0.04)
  c.transmit = { kind: 'angle', r: R, gather }
  return c
}

function cutCloth(): Collider {
  const c = boxCollider(...CENTRE, 1, 1, 0.005, 0, 0.04, 0.04)
  c.transmit = { kind: 'mask', image: TWIN_MASK_IMAGE, uv: { u0: 0, u1: 1, v0: 0, v1: 1 } }
  return c
}

const open = (deg: number) => (1 - R) * Math.max(0, 1 - R / Math.cos((deg * Math.PI) / 180))

export function twinCases(): TwinCase[] {
  return [
    { name: 'solid', collider: boxCollider(...CENTRE, 1, 1, 0.05), ...across(0), tMax: 2, want: 0 },
    { name: 'scrim 0°', collider: scrim(1), ...across(0), tMax: 2, want: open(0) },
    { name: 'scrim 45°', collider: scrim(1), ...across(45), tMax: 2, want: open(45) },
    { name: 'scrim 80°', collider: scrim(1), ...across(80), tMax: 2, want: 0 },
    { name: 'scrim 0° gathered 2', collider: scrim(2), ...across(0), tMax: 2, want: open(0) ** 2 },
    { name: 'mask hole', collider: cutCloth(), ...across(0, -0.5), tMax: 2, want: 1 },
    { name: 'mask cloth', collider: cutCloth(), ...across(0, 0.5), tMax: 2, want: 0 },
    { name: 'scrim, the fragment on it', collider: scrim(1), p: [0, 0, -2 + 0.004], d: [0, 0, -1], tMax: 2, want: 1 },
  ]
}

/** The layer each case's mask packs into: the cut cloth's is [TWIN_MASK_LAYER]. */
export const TWIN_MASK_LAYERS = { layerOf: (hash: string) => (hash === TWIN_MASK_IMAGE ? TWIN_MASK_LAYER : -1) }
