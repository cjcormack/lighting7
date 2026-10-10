/**
 * **How much of a scrim is open** (scrim plan D3): the share of light a net passes at an angle,
 * `open(θ) = (1 − r)·max(0, 1 − r/cos θ)`, θ from the cloth's normal and r the thread's share of
 * the pitch. Square on, a beam passes everything between the threads, `(1 − r)²` of it; tilted, the
 * round threads hide the holes behind them, until past `cos θ = r` nothing passes at all. One
 * function decides what a beam passes (`occlusion.ts`'s `segmentTransmit`, beam reach skipping the
 * cloth) and, since session 4, what an eye sees through (`seeThrough.ts`'s `scrimCover`).
 *
 * **Gathered net stacks** (D3): a drawn net gathered into less width than it has holds `c` layers
 * of itself, its width over the width it is gathered into ([ScenePart]'s `gather`, the half's
 * fullness), and passes `open^c`.
 *
 * Pure and three.js-free; [SCRIM_OPEN_GLSL] is its twin, pinned against it by `scrimOpen.test.ts`
 * as `beamMask.ts`'s GLSL is against its own.
 */

/**
 * The thread's share of the pitch for each net (D3). **Estimates**, judged by eye in the scrim
 * harness (session 6): sharkstooth's threads are about a third of its pitch, bobbinet's hexagonal
 * mesh is finer and more open.
 */
export const SCRIM_THREAD_SHARE = {
  SHARKSTOOTH: 0.3,
  BOBBINET: 0.15,
} as const

/** A cosine this small is taken as this: grazing, and never a division by zero. */
const MIN_COS = 1e-6

/**
 * The share a net with thread share [r] passes at an angle whose cosine from the cloth's normal is
 * [cosTheta] (its absolute value: a net passes the same from either side).
 */
export function scrimOpen(cosTheta: number, r: number): number {
  const c = Math.max(Math.abs(cosTheta), MIN_COS)
  return (1 - r) * Math.max(0, 1 - r / c)
}

/** [scrimOpen] through [gather] layers of the net: `open^c`. 0 wherever one layer passes nothing. */
export function scrimShare(cosTheta: number, r: number, gather: number): number {
  const open = scrimOpen(cosTheta, r)
  return open > 0 ? Math.pow(open, Math.max(1, gather)) : 0
}

/** The GLSL twins of [scrimOpen] and [scrimShare]. */
export const SCRIM_OPEN_GLSL = /* glsl */ `
  float scrimOpen(float cosTheta, float r) {
    float c = max(abs(cosTheta), ${MIN_COS.toExponential(1)});
    return (1.0 - r) * max(0.0, 1.0 - r / c);
  }

  float scrimShare(float cosTheta, float r, float gather) {
    float open = scrimOpen(cosTheta, r);
    return open > 0.0 ? pow(open, max(1.0, gather)) : 0.0;
  }
`
