import type { StageElementDto } from '../../../api/stageElementApi'

/**
 * A drape's **pleats**: the one description of the fold that the drawn cloth (`partGeometry` in
 * `StageSceneElements.tsx`), its collider (`partBox` in `beamReach.ts`) and the surface shader's fold
 * shadow (`surfaceShader.ts`) all read, so the box a beam lands on always contains the cloth it
 * lights and the shadow falls where the drawn crest is. The cloth is a sine across its width, its
 * depth front to back, centred on the element's plane.
 *
 * The element's `depthM` is the fold's depth crest to trough, and sets its **fullness** — how much
 * cloth hangs in a metre of width — between [FULLNESS_MIN] and [FULLNESS_MAX], the 50–100 % a stage
 * drape is hung at; the pitch is whatever a sine of that depth and fullness has. The pitch then
 * wanders by a noise seeded from the element's uuid, so no two drapes, and no two pleats, are alike.
 * A cyc is stretched rather than hung, so its role gives it a shallow, slow ripple whatever its depth.
 *
 * The fold is `z = A · sin φ(x)`, with `φ(x) = ω·x + Σ aᵢ · sin(kᵢ·x + θᵢ)`: a phase, not a pitch,
 * wanders, which keeps the curve one smooth sine whose crests all reach `A` — what makes the
 * collider exact and the fold shadow ([foldLight]) a closed form.
 *
 * Pure and three.js-free.
 */

/** The shallowest and deepest fold `depthM` draws, crest to trough. */
export const PLEAT_DEPTH_MIN_M = 0.02
export const PLEAT_DEPTH_MAX_M = 0.3
/** Fullness at and below [FULLNESS_DEPTH_LOW_M] of depth, and at and above [FULLNESS_DEPTH_HIGH_M]. */
export const FULLNESS_MIN = 1.5
export const FULLNESS_MAX = 2
const FULLNESS_DEPTH_LOW_M = 0.05
const FULLNESS_DEPTH_HIGH_M = 0.2
/** A cyc's ripple: stretched cloth, a fold no deeper than this and barely any fullness. */
export const CYC_DEPTH_MAX_M = 0.02
const CYC_FULLNESS = 1.01
/** How far the local pitch wanders either side of its mean: ±30 %. */
export const PITCH_WANDER = 0.3
/** The warp's wavelengths, in mean pitches: slow beside a pleat, so a shadow sees a sine. */
const WARP_MIN_PITCHES = 3
const WARP_SPAN_PITCHES = 4
/** The warp's terms: as many as the shader's `uPleatWarp`. */
export const PLEAT_WARP_TERMS = 3

/** One term of the phase's warp: `a · sin(k·x + θ)`. */
export interface PleatWarp {
  a: number
  k: number
  theta: number
}

export interface PleatShape {
  /** The mean crest-to-crest distance along the cloth's width. */
  pitchM: number
  /** Half the depth: how far every crest and trough sits from the cloth's plane. */
  amplitudeM: number
  /** Cloth hung per metre of width, the drawn sine's own: 1 is flat. */
  fullness: number
  /** The phase's wander, [PLEAT_WARP_TERMS] terms. */
  warp: PleatWarp[]
}

/** The arc length of `sin` over one period at slope scale [k], per unit of width. */
function sineFullness(k: number): number {
  const n = 64
  let s = 0
  for (let i = 0; i < n; i++) s += Math.sqrt(1 + k * k * Math.cos(((i + 0.5) / n) * 2 * Math.PI) ** 2)
  return s / n
}

/** The slope scale `2πA / pitch` a sine has at [fullness]. */
function slopeForFullness(fullness: number): number {
  let lo = 0
  let hi = 8
  for (let i = 0; i < 40; i++) {
    const mid = (lo + hi) / 2
    if (sineFullness(mid) < fullness) lo = mid
    else hi = mid
  }
  return (lo + hi) / 2
}

/** FNV-1a, then mulberry32: a deterministic stream of [0, 1) from a string. */
function seededRandom(seed: string): () => number {
  let h = 0x811c9dc5
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i)
    h = Math.imul(h, 0x01000193)
  }
  let state = h >>> 0
  return () => {
    state = (state + 0x6d2b79f5) >>> 0
    let t = state
    t = Math.imul(t ^ (t >>> 15), t | 1)
    t ^= t + Math.imul(t ^ (t >>> 7), t | 61)
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296
  }
}

/**
 * The pleats of [element]'s cloth. [part] tells apart two pieces of one element — the halves of a
 * pair of tabs — so each wanders its own way.
 */
export function pleatShape(
  element: Pick<StageElementDto, 'uuid' | 'depthM' | 'params'>,
  part = '',
): PleatShape {
  const cyc = String(element.params.role ?? '').trim().toUpperCase() === 'CYC'
  const raw = Number.isFinite(element.depthM) ? element.depthM : 0
  const depth = Math.min(cyc ? CYC_DEPTH_MAX_M : PLEAT_DEPTH_MAX_M, Math.max(PLEAT_DEPTH_MIN_M, raw))
  const t = Math.min(1, Math.max(0, (depth - FULLNESS_DEPTH_LOW_M) / (FULLNESS_DEPTH_HIGH_M - FULLNESS_DEPTH_LOW_M)))
  const fullness = cyc ? CYC_FULLNESS : FULLNESS_MIN + (FULLNESS_MAX - FULLNESS_MIN) * t
  const amplitudeM = depth / 2
  const pitchM = (2 * Math.PI * amplitudeM) / slopeForFullness(fullness)
  const random = seededRandom(`${element.uuid}:${part}`)
  const weights = Array.from({ length: PLEAT_WARP_TERMS }, () => 0.2 + random())
  const total = weights.reduce((s, w) => s + w, 0)
  const warp = weights.map((w) => {
    const wavelength = pitchM * (WARP_MIN_PITCHES + WARP_SPAN_PITCHES * random())
    // a·k at most its share of the wander, so the phase's rate stays within ±PITCH_WANDER of ω.
    return {
      a: ((PITCH_WANDER * (w / total)) * wavelength) / pitchM,
      k: (2 * Math.PI) / wavelength,
      theta: 2 * Math.PI * random(),
    }
  })
  return { pitchM, amplitudeM, fullness, warp }
}

/** The fold's phase [x] metres across the cloth from its centre. */
export function pleatPhase(x: number, pleat: PleatShape): number {
  let phi = (x / pleat.pitchM) * 2 * Math.PI
  for (const w of pleat.warp) phi += w.a * Math.sin(w.k * x + w.theta)
  return phi
}

/** How fast the phase turns at [x], per metre: never less than `(1 − PITCH_WANDER) · ω`. */
export function pleatRate(x: number, pleat: PleatShape): number {
  let rate = (2 * Math.PI) / pleat.pitchM
  for (const w of pleat.warp) rate += w.a * w.k * Math.cos(w.k * x + w.theta)
  return rate
}

/** Which edge of a piece of cloth its folds are measured from, where it is not its centre. */
export type PleatAnchor = 'left' | 'right'

/**
 * What to add to a point's x across a [w]-wide piece, measured from its centre, to measure it from
 * its [anchor] edge instead — the edge a drawn half gathers towards, which stays put while the half
 * narrows, so its folds stay with the cloth.
 */
export function pleatShift(w: number, anchor: PleatAnchor | undefined): number {
  return anchor === 'left' ? w / 2 : anchor === 'right' ? -w / 2 : 0
}

/** How far in front of its plane the cloth sits [x] metres across it. */
export function pleatOffset(x: number, pleat: PleatShape): number {
  return Math.sin(pleatPhase(x, pleat)) * pleat.amplitudeM
}

/** The cloth's slope `dz/dx` at [x]. */
export function pleatSlope(x: number, pleat: PleatShape): number {
  return pleat.amplitudeM * pleatRate(x, pleat) * Math.cos(pleatPhase(x, pleat))
}

/** The fold shadow's soft edge, in metres of clearance over the crest's shoulder. */
export const FOLD_SHADOW_SOFT_M = 0.004

/**
 * **The fold shadow** (stage-light plan D7): how much of a lamp at ([lx], [lz]) — in the cloth's own
 * frame, x across and z out of its plane — reaches the cloth [x] metres across, 1 lit and 0 in a
 * crest's shadow. The cloth is a sine extruded along its height, so the question is two-dimensional
 * and has a closed form.
 *
 * Seen from the point, the ray to the lamp rises at a slope `s` out of the cloth; the fold blocks it
 * where the cloth stands above the ray. Along the way the cloth's lead over the ray is largest where
 * the cloth's slope equals the ray's on a flank rising to a crest — the crest's shoulder — and the
 * first shoulder is the largest, since the ray climbs `s` per metre and every crest is the same
 * height. So the point is shadowed when the cloth at the first shoulder ahead stands above the ray.
 * A ray steeper than the steepest flank (`s ≥ Aω`) clears every crest: front light shows no fold
 * shadow, raking light bands the cloth.
 *
 * The phase's rate at the point stands in for the rate to the shoulder, under a pleat away: the
 * warp is several pitches long. A point facing away from the lamp is the caller's facing test, and
 * the lamp may be on either side of the cloth (it is drawn from both).
 */
export function foldLight(x: number, lx: number, lz: number, pleat: PleatShape): number {
  const a = pleat.amplitudeM
  let phi = pleatPhase(x, pleat)
  const rate = pleatRate(x, pleat)
  let z = a * Math.sin(phi)
  let dz = lz - z
  // A lamp behind the cloth sees the cloth mirrored: z → −z is the phase moved by half a turn.
  if (dz < 0) {
    phi += Math.PI
    z = -z
    dz = -dz
  }
  const dx = lx - x
  const run = Math.abs(dx)
  if (run < 1e-6 || a <= 0) return 1
  const slope = dz / run
  const c = slope / (a * rate)
  if (c >= 1) return 1
  // The shoulder's phase on the way to the next crest (at π/2), walking with the phase or against it.
  const shoulder = dx > 0 ? Math.acos(c) : Math.PI - Math.acos(c)
  const turn = (dx > 0 ? shoulder - phi : phi - shoulder)
  const ahead = ((turn % (2 * Math.PI)) + 2 * Math.PI) % (2 * Math.PI)
  const along = ahead / rate
  if (along >= run) return 1
  const lead = a * Math.sqrt(1 - c * c) - z - slope * along
  return 1 - smoothstep(-FOLD_SHADOW_SOFT_M, FOLD_SHADOW_SOFT_M, lead)
}

/**
 * Whether a face of the cloth can see a lamp [lz] metres out of the cloth's plane at all — the
 * [front] face the +z side, the back the −z. The cloth is one sheet: a face sees only its own side
 * of it, and every point of the sheet lies within the amplitude of its plane, so a lamp further
 * than that on the other side is behind the sheet wherever the point is. The back of a flank can
 * face a raking lamp in front, and [foldLight], which reasons from the lamp's side, darkens most of
 * that but not its soft edge at a crest's shoulder or where it reads a wandering pitch from the
 * point; the cloth's own box lets a point within its skin through, so nothing else stops it.
 */
export function pleatFaceSeesLamp(lz: number, front: boolean, pleat: PleatShape): boolean {
  return front ? lz > -pleat.amplitudeM : lz < pleat.amplitudeM
}

function smoothstep(e0: number, e1: number, x: number): number {
  const t = Math.min(1, Math.max(0, (x - e0) / (e1 - e0)))
  return t * t * (3 - 2 * t)
}

/** How dark a trough's bottom is to the room's light, at the steepest fold: its ambient × (1 − this). */
export const TROUGH_DARKEN = 0.6

/**
 * The share of the room's light the fold lets reach [x], seen from the [front] or the back: 1 on a
 * crest, down to `1 − TROUGH_DARKEN` in the bottom of a steep trough. A trough sees less of the room
 * the steeper its walls, `Aω`, the fold's slope scale.
 */
export function troughAmbient(x: number, front: boolean, pleat: PleatShape): number {
  const height = Math.sin(pleatPhase(x, pleat)) * (front ? 1 : -1)
  const steep = Math.min(1, (pleat.amplitudeM * pleatRate(x, pleat)) / 2)
  return 1 - TROUGH_DARKEN * steep * (1 - height) * 0.5
}

/**
 * The same, for the surface shader: the fold in its own frame from `uPleat` (`x` the mean
 * rate ω, `y` the amplitude, `z` the [pleatShift] to the edge it is measured from) and `uPleatWarp`
 * (`a`, `k`, `θ` per term). The twin is above; a change to one is a change to both.
 */
export const PLEAT_GLSL = /* glsl */ `
  #ifndef PI
  #define PI 3.141592653589793
  #endif
  #define FOLD_SHADOW_SOFT ${FOLD_SHADOW_SOFT_M.toFixed(4)}
  #define TROUGH_DARKEN ${TROUGH_DARKEN.toFixed(4)}
  uniform vec4 uPleat;
  uniform vec3 uPleatWarp[${PLEAT_WARP_TERMS}];

  float pleatPhase(float x) {
    float phi = uPleat.x * x;
    for (int i = 0; i < ${PLEAT_WARP_TERMS}; i++) phi += uPleatWarp[i].x * sin(uPleatWarp[i].y * x + uPleatWarp[i].z);
    return phi;
  }

  float pleatRate(float x) {
    float rate = uPleat.x;
    for (int i = 0; i < ${PLEAT_WARP_TERMS}; i++) rate += uPleatWarp[i].x * uPleatWarp[i].y * cos(uPleatWarp[i].y * x + uPleatWarp[i].z);
    return rate;
  }

  float foldLight(float x, float lx, float lz) {
    float a = uPleat.y;
    float phi = pleatPhase(x);
    float rate = pleatRate(x);
    float z = a * sin(phi);
    float dz = lz - z;
    if (dz < 0.0) {
      phi += PI;
      z = -z;
      dz = -dz;
    }
    float dx = lx - x;
    float run = abs(dx);
    if (run < 1e-6 || a <= 0.0) return 1.0;
    float slope = dz / run;
    float c = slope / (a * rate);
    if (c >= 1.0) return 1.0;
    float shoulder = dx > 0.0 ? acos(c) : PI - acos(c);
    float turn = dx > 0.0 ? shoulder - phi : phi - shoulder;
    float ahead = mod(turn, 2.0 * PI);
    float along = ahead / rate;
    if (along >= run) return 1.0;
    float lead = a * sqrt(1.0 - c * c) - z - slope * along;
    return 1.0 - smoothstep(-FOLD_SHADOW_SOFT, FOLD_SHADOW_SOFT, lead);
  }

  bool pleatFaceSeesLamp(float lz, bool front) {
    return front ? lz > -uPleat.y : lz < uPleat.y;
  }

  float troughAmbient(float x, bool front) {
    float height = sin(pleatPhase(x)) * (front ? 1.0 : -1.0);
    float steep = min(1.0, uPleat.y * pleatRate(x) / 2.0);
    return 1.0 - TROUGH_DARKEN * steep * (1.0 - height) * 0.5;
  }
`

/** [pleat] as `PLEAT_GLSL`'s uniforms. */
export function pleatUniformValues(pleat: PleatShape): { pleat: [number, number, number, number]; warp: number[] } {
  return {
    pleat: [(2 * Math.PI) / pleat.pitchM, pleat.amplitudeM, 0, 0],
    warp: pleat.warp.flatMap((w) => [w.a, w.k, w.theta]),
  }
}
