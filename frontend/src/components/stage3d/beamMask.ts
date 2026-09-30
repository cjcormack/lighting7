/**
 * A beam's **cross-section** — the one function the pool on every surface and the haze in the air
 * both shape a beam by (stage-view plan session 6; the design record's item 10, "a frame, shared by
 * pool and haze"). So a soft edge, an iris and, from session 7, a shutter cut the pool and the beam
 * along the same line.
 *
 * `uv` is where a point sits in the beam's frame — the head's own right axis and the one at right
 * angles to it round the beam's axis — normalised so the **field edge is at 1**: a disc's radius,
 * a segment's half-width along `u` and half-depth along `v` (the caller divides `v` by the aspect).
 * The frame is the head's, so the cut turns with the lantern.
 *
 * - `aspect` 0 is a round aperture (a lens); above 0 a segment's depth over its width, whose
 *   cross-section is a rectangle;
 * - `iris` is the open fraction of the field, 1 fully open;
 * - `soft` is the edge, 0 a profile's hard gate edge to 1 a flood's feathered one, and a soft beam
 *   is a little brighter at its middle, as a wash is.
 *
 * Session 7 adds the four blades and the gate rotation as arguments in this frame.
 *
 * The GLSL and the TypeScript twin are written side by side from the same constants, and
 * `beamMask.test.ts` pins the twin; the twin exists for that test and for nothing else.
 */

/** The width of the edge's roll-off, in field units, at soft 0 and at soft 1. */
export const MASK_EDGE_HARD = 0.03
export const MASK_EDGE_SOFT = 0.55
/** How much brighter a fully soft beam's middle is than its edge's shoulder. */
export const MASK_SOFT_CENTRE = 0.35
/** An iris's edge is this fraction of the field edge's roll-off. */
const IRIS_EDGE = 0.6

const f = (v: number) => v.toFixed(4)

export const BEAM_MASK_GLSL = /* glsl */ `
  float beamMask(vec2 uv, float aspect, float iris, float soft) {
    float s = clamp(soft, 0.0, 1.0);
    float r = aspect > 0.0 ? max(abs(uv.x), abs(uv.y)) : length(uv);
    float w = mix(${f(MASK_EDGE_HARD)}, ${f(MASK_EDGE_SOFT)}, s);
    float m = 1.0 - smoothstep(1.0 - w, 1.0, r);
    if (iris < 0.999) m *= 1.0 - smoothstep(iris - w * ${f(IRIS_EDGE)}, iris + 0.005, r);
    m *= 1.0 - ${f(MASK_SOFT_CENTRE)} * s * min(r * r, 1.0);
    return max(m, 0.0);
  }
`

/**
 * A beam's edge **hardness** at a point `defocus` metres from its focal plane: the family's own
 * hardness (1 − softness, frost already folded in), capped by how far the point sits from the focal
 * plane where the fixture has a focus channel (`focusDist` ≥ 0) — so a frosted beam stays soft even at
 * its focus, and an unfrosted one sharpens only there. Shared by the surface shader and the haze.
 */
export const BEAM_HARDNESS_GLSL = /* glsl */ `
  float beamHardness(float baseHard, float focusDist, float defocus, float softRange) {
    if (focusDist < 0.0) return baseHard;
    return min(baseHard, 1.0 - smoothstep(0.0, softRange, defocus));
  }
`

/** The TypeScript twin of [BEAM_HARDNESS_GLSL], for its test. */
export function beamHardness(baseHard: number, focusDist: number, defocus: number, softRange: number): number {
  if (focusDist < 0) return baseHard
  return Math.min(baseHard, 1 - smoothstep(0, softRange, defocus))
}

function smoothstep(e0: number, e1: number, x: number): number {
  const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)))
  return t * t * (3 - 2 * t)
}

/** The TypeScript twin of [BEAM_MASK_GLSL], for its test. */
export function beamMask(u: number, v: number, aspect: number, iris: number, soft: number): number {
  const s = Math.max(0, Math.min(1, soft))
  const r = aspect > 0 ? Math.max(Math.abs(u), Math.abs(v)) : Math.hypot(u, v)
  const w = MASK_EDGE_HARD + (MASK_EDGE_SOFT - MASK_EDGE_HARD) * s
  let m = 1 - smoothstep(1 - w, 1, r)
  if (iris < 0.999) m *= 1 - smoothstep(iris - w * IRIS_EDGE, iris + 0.005, r)
  m *= 1 - MASK_SOFT_CENTRE * s * Math.min(r * r, 1)
  return Math.max(m, 0)
}
