/**
 * A drape's **pleats**: the one description of the fold that the drawn cloth (`partGeometry` in
 * `StageSceneElements.tsx`) and its collider (`partBox` in `beamReach.ts`) both read, so the box a
 * beam lands on always contains the cloth it lights. The cloth is a sine across its width, its
 * depth front to back, centred on the element's plane.
 *
 * Pure and three.js-free.
 */

/** Pleats a metre: a cloth's fullness drawn as a ripple across its width. */
export const PLEATS_PER_M = 7
/** Crest to trough. */
export const PLEAT_DEPTH_M = 0.05

export interface PleatShape {
  /** Crest to crest, along the cloth's width. */
  pitchM: number
  /** Half the depth: how far a crest or a trough sits from the cloth's plane. */
  amplitudeM: number
}

/** A cloth's pleats: every drape's are the same until its depth drives them. */
export function pleatShape(): PleatShape {
  return { pitchM: 1 / PLEATS_PER_M, amplitudeM: PLEAT_DEPTH_M / 2 }
}

/** How far in front of its plane the cloth sits [x] metres across it. */
export function pleatOffset(x: number, pleat: PleatShape): number {
  return Math.sin((x / pleat.pitchM) * Math.PI * 2) * pleat.amplitudeM
}
