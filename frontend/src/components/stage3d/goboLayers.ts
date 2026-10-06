import { GOBO_BLUR_LEVELS } from './goboAtlas'
import { GOBO_SLOT_COUNT } from './goboPatterns'

/**
 * A beam's **gobo layers** (fixture-optics plan session 4): up to two patterns in series — the Robe
 * ColorSpot 575's static wheel and its rotating one, or a single wheel's one pattern with an empty
 * second layer. The haze and the surfaces both read them, through one packing and one sampler, so a
 * gobo lands on a wall exactly as the air draws it.
 *
 * **One float for both layers** ([packGobos]), low bits first: the turned layer's **angle** in
 * [GOBO_ANGLE_STEPS] steps of a turn (13 bits), **which** layer it turns (1 bit), then layer B's and
 * layer A's pattern in the atlas (`goboAtlas.ts`; 5 bits each, 0 open). 24 bits, every integer a
 * float32 holds, and every division the decode makes is by a power of two, so the GPU unpacks exactly
 * what the director packed. One angle is enough because a type has one gobo rotation channel
 * (`findGoboRotationProperty`), so one wheel turns (`goboRotationWheel`) and the other — the Robe's
 * static wheel — holds still in the frame, which the gate rotation already turns. The angle's step is
 * 0.044°: a 128 px pattern moves 0.05 of a texel at its rim a step, so a spinning gobo never visibly
 * steps.
 *
 * The float rides the haze's `aBeamFx.y` (where the old layer was) and the light table's texel 4 `.z`
 * (`scene/lightTable.ts`), so neither the sixteen vertex attributes nor the six texels grew.
 *
 * Each layer multiplies the beam (`goboPair`): two gobos in series pass only what both pass. A layer
 * is sampled in the beam's own frame — `g`, the field edge at 1 along the head's right axis and the
 * one at right angles to it, the frame `beamMask` cuts in — the turned one turned by the angle, at a
 * blur level that is the focus blur or the pixel's footprint, whichever is wider ([goboLod]). The
 * levels are the atlas's own layers (`goboAtlas.ts`), each a Gaussian blur of the pattern at full
 * size, and a fractional level is the blend of the two either side — so a gobo racked through focus
 * softens steadily, never in steps.
 *
 * Pure but for the atlas's layout, so the packing is pinned by a node test, and the GLSL has a
 * TypeScript twin ([goboPair], [goboLod], [atlasSampler]) that the test samples the real atlas
 * data through.
 */

/** Angle steps per turn: the low 13 bits. */
export const GOBO_ANGLE_STEPS = 8192
/** Above the angle: the bit that says layer B, not A, is the turned one. */
const TURNED_BASE = GOBO_ANGLE_STEPS
/** Above that: layer B's pattern, then layer A's, 5 bits each. */
const LAYER_B_BASE = TURNED_BASE * 2
const LAYER_A_BASE = LAYER_B_BASE * 32
const TAU = Math.PI * 2

/** The most layers a beam carries (a static wheel and a rotating one). */
export const MAX_GOBO_LAYERS = 2

/**
 * How many times the pixel's footprint a pattern far away is blurred by. A blur level's Gaussian is
 * a quarter of its width in texels (`goboLevelSigma`), and a pattern seen at `p` texels a pixel needs
 * one about half `p` wide not to alias.
 */
export const GOBO_FOOTPRINT_FILTER = 2

function layerCode(layer: number): number {
  const l = Math.round(layer)
  return l > 0 && l < GOBO_SLOT_COUNT ? l : 0
}

/**
 * A beam's two gobo layers as one float: layer A's and layer B's pattern (0 open, else an atlas
 * layer), which of them [turned] turns (0 or 1; anything else turns neither), and its [angleRad],
 * wrapped to one turn. 0 when both are open.
 */
export function packGobos(layerA: number, layerB: number, turned: number, angleRad: number): number {
  const a = layerCode(layerA)
  const b = layerCode(layerB)
  if (a === 0 && b === 0) return 0
  const turnedLayer = turned === 0 ? a : turned === 1 ? b : 0
  let code = 0
  if (turnedLayer > 0) {
    const turns = Number.isFinite(angleRad) ? angleRad / TAU : 0
    code = Math.round((turns - Math.floor(turns)) * GOBO_ANGLE_STEPS) % GOBO_ANGLE_STEPS
  }
  return a * LAYER_A_BASE + b * LAYER_B_BASE + (turned === 1 && code > 0 ? TURNED_BASE : 0) + code
}

export interface UnpackedGobos {
  layerA: number
  layerB: number
  /** Layer A's angle and layer B's: the turned one's, the other 0. */
  angleA: number
  angleB: number
}

/** The packed float back to its layers and their angles — the decode the GLSL makes. Null for open. */
export function unpackGobos(packed: number): UnpackedGobos | null {
  if (!(packed >= 0.5)) return null
  const layerA = Math.floor(packed / LAYER_A_BASE)
  let rest = packed - layerA * LAYER_A_BASE
  const layerB = Math.floor(rest / LAYER_B_BASE)
  rest -= layerB * LAYER_B_BASE
  const turnedB = Math.floor(rest / TURNED_BASE)
  const angle = (rest - turnedB * TURNED_BASE) * (TAU / GOBO_ANGLE_STEPS)
  return { layerA, layerB, angleA: turnedB ? 0 : angle, angleB: turnedB ? angle : 0 }
}

/**
 * The GLSL both the haze and the surfaces sample gobos through; it expects `uniform sampler2DArray
 * uGobo` declared before it. `goboRots` decodes the packed float once into each layer's `(cos, sin,
 * atlas layer, live)` — one `cos` and one `sin` for both — so a caller hoists it out of a loop;
 * `goboSample` reads one decoded layer at `g` (the field edge at 1), and `goboPair` multiplies the
 * two. `goboLod` is the blur level for a focus blur and a pixel footprint, both in field radii.
 */
export const GOBO_LAYERS_GLSL = /* glsl */ `
  void goboRots(float packed, out vec4 rotA, out vec4 rotB) {
    rotA = vec4(1.0, 0.0, 0.0, 0.0);
    rotB = vec4(1.0, 0.0, 0.0, 0.0);
    if (packed < 0.5) return;
    float layerA = floor(packed / ${LAYER_A_BASE}.0);
    float rest = packed - layerA * ${LAYER_A_BASE}.0;
    float layerB = floor(rest / ${LAYER_B_BASE}.0);
    rest -= layerB * ${LAYER_B_BASE}.0;
    float turnedB = floor(rest / ${TURNED_BASE}.0);
    float a = (rest - turnedB * ${TURNED_BASE}.0) * ${(TAU / GOBO_ANGLE_STEPS).toExponential(10)};
    vec2 cs = vec2(cos(a), sin(a));
    rotA = vec4(turnedB > 0.5 ? vec2(1.0, 0.0) : cs, layerA, layerA > 0.5 ? 1.0 : 0.0);
    rotB = vec4(turnedB > 0.5 ? cs : vec2(1.0, 0.0), layerB, layerB > 0.5 ? 1.0 : 0.0);
  }

  // The pattern's blur levels are consecutive layers; between two, a blend of both.
  float goboSample(vec2 g, vec4 rot, float lod) {
    if (rot.w < 0.5) return 1.0;
    vec2 uv = vec2(rot.x * g.x - rot.y * g.y, rot.y * g.x + rot.x * g.y) * 0.5 + 0.5;
    float level = floor(lod);
    float layer = rot.z * ${GOBO_BLUR_LEVELS}.0 + level;
    float a = textureLod(uGobo, vec3(uv, layer), 0.0).r;
    float t = lod - level;
    return t > 0.0 ? mix(a, textureLod(uGobo, vec3(uv, layer + 1.0), 0.0).r, t) : a;
  }

  float goboPair(vec2 g, vec4 rotA, vec4 rotB, float lod) {
    return goboSample(g, rotA, lod) * goboSample(g, rotB, lod);
  }

  float goboLod(float blur, float footprint, float texelsPerRadius, float lodMax) {
    return clamp(log2(1.0 + texelsPerRadius * max(blur, ${GOBO_FOOTPRINT_FILTER.toFixed(1)} * footprint)), 0.0, lodMax);
  }
`

/**
 * The TypeScript twin of `goboLod`: the blur level a gobo is read at for a focus [blur] and a pixel
 * [footprint], both in field radii, with [texelsPerRadius] atlas texels to a field radius. The
 * wider of the two wins — a defocused pattern is soft however close the camera, and a sharp one
 * far away is filtered rather than aliased ([GOBO_FOOTPRINT_FILTER]).
 */
export function goboLod(blur: number, footprint: number, texelsPerRadius: number, lodMax: number): number {
  return Math.min(lodMax, Math.max(0, Math.log2(1 + texelsPerRadius * Math.max(blur, GOBO_FOOTPRINT_FILTER * footprint))))
}

/** Reads one pattern at texture coordinates `(u, v)` and a blur level: the twin of `goboSample`'s reads. */
export type GoboSampler = (layer: number, u: number, v: number, lod: number) => number

function sampleLayer(gx: number, gy: number, layer: number, angle: number, lod: number, sample: GoboSampler): number {
  if (layer <= 0) return 1
  const c = Math.cos(angle)
  const s = Math.sin(angle)
  return sample(layer, (c * gx - s * gy) * 0.5 + 0.5, (s * gx + c * gy) * 0.5 + 0.5, lod)
}

/** The TypeScript twin of `goboRots` then `goboPair`: both layers of [packed] at `(gx, gy)` in the beam's frame. */
export function goboPair(gx: number, gy: number, packed: number, lod: number, sample: GoboSampler): number {
  const g = unpackGobos(packed)
  if (!g) return 1
  return sampleLayer(gx, gy, g.layerA, g.angleA, lod, sample) * sampleLayer(gx, gy, g.layerB, g.angleB, lod, sample)
}

/**
 * A [GoboSampler] over the atlas's own bytes (`buildGoboAtlasData`), for the twin's tests: as the
 * GPU reads it — bilinear between texel centres, the edge clamped — at the two blur levels either
 * side of `lod`, blended by its fraction, as `goboSample` does.
 */
export function atlasSampler(data: Uint8Array, size: number): GoboSampler {
  const bilinear = (layer: number, u: number, v: number) => {
    const x = u * size - 0.5
    const y = v * size - 0.5
    const x0 = Math.floor(x)
    const y0 = Math.floor(y)
    const fx = x - x0
    const fy = y - y0
    const at = (xi: number, yi: number) =>
      data[layer * size * size + Math.min(size - 1, Math.max(0, yi)) * size + Math.min(size - 1, Math.max(0, xi))]
    const top = at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx
    const bottom = at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx
    return (top * (1 - fy) + bottom * fy) / 255
  }
  return (slot, u, v, lod) => {
    const level = Math.floor(lod)
    const layer = slot * GOBO_BLUR_LEVELS + level
    const a = bilinear(layer, u, v)
    const t = lod - level
    return t > 0 ? a + (bilinear(layer + 1, u, v) - a) * t : a
  }
}
