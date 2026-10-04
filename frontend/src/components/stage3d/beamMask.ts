/**
 * A beam's **cross-section** — the one function the pool on every surface and the haze in the air
 * both shape a beam by (stage-view plan sessions 6–7; the design record's item 10, "a frame, shared
 * by pool and haze"). So a soft edge, an iris and a shutter cut the pool and the beam along the same
 * line.
 *
 * `uv` is where a point sits in the beam's frame — the head's own right axis and the one at right
 * angles to it round the beam's axis — normalised so the **field edge is at 1**: a disc's radius,
 * a segment's half-width along `u` and half-depth along `v` (the caller divides `v` by the aspect).
 * The frame is the head's, so the cut turns with the lantern.
 *
 * - `aspect` 0 is a round aperture (a lens); above 0 a segment's depth over its width, whose
 *   cross-section is a rectangle; **below 0 an oval** — a PAR lamp's — whose narrow axis over its
 *   wide one is `−aspect` (the caller divides `v` by `|aspect|` either way, so the field edge is
 *   at 1 on both axes, and the oval's wide axis is `u`);
 * - `iris` is the open fraction of the field, 1 fully open;
 * - `soft` is the edge, 0 a profile's hard gate edge to 1 a flood's feathered one, and a soft beam
 *   is a little brighter at its middle, as a wash is.
 *
 * - `blades` is the four shutters (or barn doors), **packed** two to a float ([packBlades]) so a light
 *   carries them in the light table's six texels without a seventh. Each blade is a straight edge in
 *   this frame: its depth moves it in from the field edge and its angle turns it about its own
 *   middle. They come from one of two sources, never both (`FixtureModel.tsx`'s director): a
 *   lantern's focus, packed once per body spec, or a DMX head's framing-shutter channels, packed
 *   every frame (fixture-optics plan D5).
 *
 * The **gate rotation** and a PAR's **lamp rotation** are not arguments: they turn the frame itself.
 * The director turns the head's right axis about the beam before it writes `u`, so every blade (and
 * an oval's wide axis) turns with it, in the surfaces and in the air alike.
 *
 * Which way the frame faces: `u` is the head's right axis and `v = axis × u`, which for a level
 * lantern is **up** and makes `u` the **left** of someone behind the lantern looking along its beam.
 * The blades are named for the edge of the light they cut as that person sees it (the design record:
 * "nobody thinks 'top shutter to cut the bottom of the beam'"), so top pushes in from `+v`, left from
 * `+u`. `FixtureModel.test.tsx` pins that orientation against the head rig.
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
/** A blade's edge is in the gate, as the iris is, so it rolls off as the iris's does. */
const BLADE_EDGE = 0.6

/**
 * A blade's code, 12 bits: its depth in [BLADE_DEPTH_STEPS] steps above its angle's six bits
 * ([BLADE_ANGLE_STEP_DEG] steps, offset by [BLADE_ANGLE_OFFSET]). Two codes make a float of 24 bits
 * — every integer a float32 holds exactly — and every division the decode makes is by a power of
 * two, so the GPU unpacks exactly what the director packed. Depth 0 is a blade that is out, whatever
 * its angle.
 *
 * The angle reaches ±[MAX_PACKED_BLADE_ANGLE_DEG] — a DMX framing shutter's ±45° (the Source Four
 * Revolution's) — in 1.5° steps: 61 values, still six bits. A seventh bit would push two blades past
 * the float's 24-bit mantissa (fixture-optics plan D5). A lantern's blades are stored to ±30°
 * (`MAX_BLADE_ANGLE_DEG` in `lib/lanterns.ts`, the Focus card's slider and `LanternFocus`'s
 * validation); only the drawing's resolution changed for them, from 1° to 1.5°.
 */
// Even, so a blade half in — the field's centre, a DMX framing shutter's `depthMax` 0.5 — is a step
// exactly rather than half a step past it.
export const BLADE_DEPTH_STEPS = 62
export const BLADE_ANGLE_OFFSET = 32
export const BLADE_ANGLE_STEP_DEG = 1.5
export const MAX_PACKED_BLADE_ANGLE_DEG = 45
const CODE = 64
const PAIR = 4096
const MAX_ANGLE_STEPS = MAX_PACKED_BLADE_ANGLE_DEG / BLADE_ANGLE_STEP_DEG

/** The four blades' normals in the beam's frame: top, bottom, left, right — the wire's order. */
export const BLADE_NORMALS: ReadonlyArray<readonly [number, number]> = [
  [0, 1],
  [0, -1],
  [1, 0],
  [-1, 0],
]

const f = (v: number) => v.toFixed(4)

export const BEAM_MASK_GLSL = /* glsl */ `
  // One blade: cuts past a straight edge whose middle sits 1 − 2·depth along the blade's normal n,
  // turned by its angle about that middle. A code of depth 0 is a blade that is out.
  float bladeCut(vec2 uv, float code, vec2 n, float w) {
    float dq = floor(code / ${CODE}.0);
    if (dq < 0.5) return 1.0;
    float a = radians((code - dq * ${CODE}.0 - ${BLADE_ANGLE_OFFSET}.0) * ${f(BLADE_ANGLE_STEP_DEG)});
    vec2 p0 = n * (1.0 - 2.0 * dq / ${BLADE_DEPTH_STEPS}.0);
    float c = cos(a);
    float s = sin(a);
    vec2 nr = vec2(c * n.x - s * n.y, s * n.x + c * n.y);
    return 1.0 - smoothstep(-w, 0.005, dot(uv - p0, nr));
  }

  // The four blades, packed two to a float: (top, bottom) and (left, right).
  float beamBlades(vec2 uv, vec2 packed, float w) {
    if (packed.x < 0.5 && packed.y < 0.5) return 1.0;
    float top = floor(packed.x / ${PAIR}.0);
    float left = floor(packed.y / ${PAIR}.0);
    return bladeCut(uv, top, vec2(0.0, 1.0), w)
      * bladeCut(uv, packed.x - top * ${PAIR}.0, vec2(0.0, -1.0), w)
      * bladeCut(uv, left, vec2(1.0, 0.0), w)
      * bladeCut(uv, packed.y - left * ${PAIR}.0, vec2(-1.0, 0.0), w);
  }

  float beamMask(vec2 uv, float aspect, float iris, float soft, vec2 blades) {
    float s = clamp(soft, 0.0, 1.0);
    float r = aspect > 0.0 ? max(abs(uv.x), abs(uv.y)) : length(uv);
    float w = mix(${f(MASK_EDGE_HARD)}, ${f(MASK_EDGE_SOFT)}, s);
    float m = 1.0 - smoothstep(1.0 - w, 1.0, r);
    if (iris < 0.999) m *= 1.0 - smoothstep(iris - w * ${f(IRIS_EDGE)}, iris + 0.005, r);
    m *= beamBlades(uv, blades, w * ${f(BLADE_EDGE)});
    m *= 1.0 - ${f(MASK_SOFT_CENTRE)} * s * min(r * r, 1.0);
    return max(m, 0.0);
  }
`

/** A blade's angle as the packing holds it: clamped to ±[MAX_PACKED_BLADE_ANGLE_DEG], in whole steps. */
function angleSteps(angleDeg: number): number {
  const a = Number.isFinite(angleDeg) ? angleDeg : 0
  return Math.round(Math.min(MAX_ANGLE_STEPS, Math.max(-MAX_ANGLE_STEPS, a / BLADE_ANGLE_STEP_DEG)))
}

/**
 * One blade's code ([BLADE_DEPTH_STEPS] depth steps × [BLADE_ANGLE_STEP_DEG] steps), 0 for a blade
 * that is out.
 */
export function packBlade(depth: number, angleDeg: number): number {
  const d = Math.round(Math.min(1, Math.max(0, Number.isFinite(depth) ? depth : 0)) * BLADE_DEPTH_STEPS)
  if (d === 0) return 0
  return d * CODE + angleSteps(angleDeg) + BLADE_ANGLE_OFFSET
}

/** A code back to its blade — the decode the GLSL makes, for the twin and its test. Null for out. */
export function unpackBlade(code: number): { depth: number; angleDeg: number } | null {
  const dq = Math.floor(code / CODE)
  if (dq < 0.5) return null
  return { depth: dq / BLADE_DEPTH_STEPS, angleDeg: (code - dq * CODE - BLADE_ANGLE_OFFSET) * BLADE_ANGLE_STEP_DEG }
}

/**
 * The four blades as two floats — (top, bottom) and (left, right) — for the light table's texel 5
 * and the haze's `aBeamGate.zw`. `[0, 0]` for none, which the mask skips outright.
 */
export function packBlades(
  blades: ReadonlyArray<{ depth: number; angleDeg: number }> | null | undefined,
  out: [number, number] = [0, 0],
): [number, number] {
  if (!blades || blades.length !== 4) {
    out[0] = 0
    out[1] = 0
    return out
  }
  out[0] = packBlade(blades[0].depth, blades[0].angleDeg) * PAIR + packBlade(blades[1].depth, blades[1].angleDeg)
  out[1] = packBlade(blades[2].depth, blades[2].angleDeg) * PAIR + packBlade(blades[3].depth, blades[3].angleDeg)
  return out
}

/**
 * A blade's edge in the beam's frame — where its middle sits and which way it faces (the side it
 * cuts). The Focus card draws its preview from this, so the picture and the pool agree: its angle is
 * the packing's, in [BLADE_ANGLE_STEP_DEG] steps to ±[MAX_PACKED_BLADE_ANGLE_DEG]. A lantern's own
 * blades never reach past ±30° (the card's slider, and `LanternFocus`'s validation), so only the
 * step changes what the preview shows for one.
 */
export function bladeLine(index: number, depth: number, angleDeg: number): { px: number; py: number; nx: number; ny: number } {
  const [bx, by] = BLADE_NORMALS[index]
  const d = Math.min(1, Math.max(0, depth))
  const a = (angleSteps(angleDeg) * BLADE_ANGLE_STEP_DEG * Math.PI) / 180
  const c = Math.cos(a)
  const s = Math.sin(a)
  return { px: bx * (1 - 2 * d), py: by * (1 - 2 * d), nx: c * bx - s * by, ny: s * bx + c * by }
}

/**
 * How far out of focus a beam is `d` metres from its aperture: the **blur**, in field radii, for a
 * lens focused `focusDist` metres out, as the **relative** focus error `|f − d| / f` times the type's
 * **depth of field** `dof` (fixture-optics plan D9) — the type's declared `depthOfField`, else its
 * family's (`DEPTH_OF_FIELD` in `bodies/archetype.ts`); 0 without a focus channel (`focusDist` < 0).
 *
 * It used to be the blur circle of a lens of radius `a`, `2a·|1 − d/f|` over the field's radius
 * `a·(near + d)/near` — which scales with a lens radius nothing declares (the Revolution's was
 * guessed from its kind's default size), and over a long throw stays under the edge's hard limit
 * across the whole far end of the range, so a 24 m wall drew the same edge from DMX ~140 to 255. A
 * relative error is the same at every throw, and the constant is what was tuned by eye against that
 * wall (`profileHarness.ts`'s focus scene): a 24 m throw visibly soft 3 m either side, sharp on it.
 *
 * A beam's edge **hardness** is then what the optics allow (`resolveEdgeHardness` in
 * `beamOptics.ts`: with a focus channel only frost caps it — the family's softness lifts, since the
 * blur now models what it stood in for — and without one the family's, frost folded in), capped by
 * that blur where the fixture has a focus channel (`focusDist` ≥ 0). So on the focal plane an
 * unfrosted edge is as hard as the mask draws one, a frosted beam stays soft even there, and off
 * the plane the edge softens with the blur, fully soft at `softBlur`. Shared by the surface shader
 * and the haze.
 */
export const BEAM_HARDNESS_GLSL = /* glsl */ `
  float focusBlur(float d, float focusDist, float dof) {
    if (focusDist < 0.0) return 0.0;
    float f = max(focusDist, 1e-3);
    return max(dof, 0.0) * abs(f - max(d, 0.0)) / f;
  }

  float beamHardness(float baseHard, float focusDist, float blur, float softBlur) {
    if (focusDist < 0.0) return baseHard;
    return min(baseHard, 1.0 - smoothstep(0.0, softBlur, blur));
  }
`

/** The TypeScript twin of [BEAM_HARDNESS_GLSL]'s `focusBlur`, for its test. */
export function focusBlur(d: number, focusDist: number, dof: number): number {
  if (focusDist < 0) return 0
  const f = Math.max(focusDist, 1e-3)
  return (Math.max(dof, 0) * Math.abs(f - Math.max(d, 0))) / f
}

/** The TypeScript twin of [BEAM_HARDNESS_GLSL]'s `beamHardness`, for its test. */
export function beamHardness(baseHard: number, focusDist: number, blur: number, softBlur: number): number {
  if (focusDist < 0) return baseHard
  return Math.min(baseHard, 1 - smoothstep(0, softBlur, blur))
}

function smoothstep(e0: number, e1: number, x: number): number {
  const t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)))
  return t * t * (3 - 2 * t)
}

function bladeCut(u: number, v: number, code: number, nx: number, ny: number, w: number): number {
  const blade = unpackBlade(code)
  if (!blade) return 1
  const a = (blade.angleDeg * Math.PI) / 180
  const k = 1 - 2 * blade.depth
  const c = Math.cos(a)
  const s = Math.sin(a)
  const rx = c * nx - s * ny
  const ry = s * nx + c * ny
  return 1 - smoothstep(-w, 0.005, (u - nx * k) * rx + (v - ny * k) * ry)
}

function beamBlades(u: number, v: number, a: number, b: number, w: number): number {
  if (a < 0.5 && b < 0.5) return 1
  const top = Math.floor(a / PAIR)
  const left = Math.floor(b / PAIR)
  return (
    bladeCut(u, v, top, 0, 1, w) *
    bladeCut(u, v, a - top * PAIR, 0, -1, w) *
    bladeCut(u, v, left, 1, 0, w) *
    bladeCut(u, v, b - left * PAIR, -1, 0, w)
  )
}

/** The TypeScript twin of [BEAM_MASK_GLSL], for its test. [bladesA]/[bladesB] are [packBlades]'. */
export function beamMask(
  u: number,
  v: number,
  aspect: number,
  iris: number,
  soft: number,
  bladesA = 0,
  bladesB = 0,
): number {
  const s = Math.max(0, Math.min(1, soft))
  const r = aspect > 0 ? Math.max(Math.abs(u), Math.abs(v)) : Math.hypot(u, v)
  const w = MASK_EDGE_HARD + (MASK_EDGE_SOFT - MASK_EDGE_HARD) * s
  let m = 1 - smoothstep(1 - w, 1, r)
  if (iris < 0.999) m *= 1 - smoothstep(iris - w * IRIS_EDGE, iris + 0.005, r)
  m *= beamBlades(u, v, bladesA, bladesB, w * BLADE_EDGE)
  m *= 1 - MASK_SOFT_CENTRE * s * Math.min(r * r, 1)
  return Math.max(m, 0)
}
