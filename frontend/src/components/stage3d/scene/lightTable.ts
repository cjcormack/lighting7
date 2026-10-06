/**
 * The live lights the surface shader reads (stage-view plan session 3): one row per light in a
 * float **data texture**, not a uniform array. A uniform array has a size fixed at compile time and
 * eats the program's uniform budget — the prototype's 48-light ceiling. A texture has neither: the
 * shader loops over however many rows this frame packed.
 *
 * Every beam lobe and every washing pixel of the rig has a slot here (`StageEmitters` sizes the
 * table from the emitter layout, lobes first, then wash pixels). The directors write their slot each
 * frame; [LightTable.pack] then copies the **budget**'s worth of the brightest lit ones into the
 * texture's rows. The budget is the surface shader's cost knob — pixels × lights — and it is the
 * viewer's to set (`sceneView.ts`); a light it drops still draws its beam, it just lands on
 * nothing this frame.
 *
 * Six RGBA texels a light, in three.js space. A light is a beam leaving an **aperture** (stage-view
 * plan session 6): its cone's apex sits behind the aperture, at its radius over the tangent of the
 * half-field, so a lantern's apex is its lamp and a wide LED face's is far behind it.
 *
 * | texel | rgb | a |
 * |---|---|---|
 * | 0 | the apex | focal distance from the aperture and depth of field, packed ([packFocus]; < 0 is always sharp) |
 * | 1 | axis (unit) | cos of the bounding half-angle — the field, or a segment's corner |
 * | 2 | colour × level | edge hardness and iris, packed ([packEdgeIris]) |
 * | 3 | where the beam lands, two planes packed (`landing.ts`'s `packLanding`) | |
 * | 4 | the frame's `u` in the axis's own basis, `(cos, sin)` ([frameInBasis]); the gobo layers (`goboLayers.ts`'s `packGobos`) | tan of the half-field along `u` |
 * | 5 | apex → aperture distance, the (top, bottom) blades, aspect (0 a disc, > 0 a segment's depth over its width, < 0 an oval's narrow over wide) | the (left, right) blades |
 *
 * Texels 4 and 5 are what the surface shader's `beamMask` reads, as the haze's does, so a soft edge,
 * an iris and a shutter shape the pool exactly as they shape the air — and texel 4 the gobos it
 * samples in the same frame.
 *
 * **Still six texels** (stage-view plan session 7). The four blades ride texel 5 packed two to a
 * float (`beamMask.ts`'s `packBlades`), which texel 5's free slot and the iris's old one hold, and
 * the iris moved in beside the edge hardness in texel 2, both quantised to 1/1023. The gate
 * rotation and a PAR's lamp rotation turn the frame in texel 4 before it is written, and an
 * oval is a negative aspect. A seventh texel would have cost every surface fragment a fetch per
 * light, which the plan says to measure first — and the packing costs nothing measurable.
 *
 * **And still six** (fixture-optics plan session 1, D9): the blur is now the relative focus error
 * times the type's **depth of field**, a number per light the table had no slot for, so it rides
 * texel 0's alpha beside the focal distance — the distance to the centimetre in the low 15 bits, the
 * depth of field in twentieths above them ([packFocus]). 24 bits, every integer a float32 holds.
 *
 * **And still six** (fixture-optics plan session 4, D10): gobos land on surfaces, so a light carries
 * its gobo layers — two patterns and the turned one's angle, packed into one float (`packGobos`: 5 + 5
 * bits of pattern, 1 of which turns, 13 of angle). No packed float had 24 bits to spare (texel 2's
 * alpha has four), but texel 4 spent three floats on two numbers' worth. The frame's `u` was the
 * head's right axis, and the shader only ever used its direction **at right angles to the beam** (it
 * took out the component along the axis and normalised): given the axis, that is a direction in a
 * plane — a unit 2-vector. So texel 4 now holds it as `(cos, sin)` in a basis the shader builds from
 * the axis alone ([BEAM_FRAME_GLSL], Duff et al.'s branchless orthonormal basis), and the float it
 * freed holds the gobos. An angle in one float would have freed two, and cost a `cos` and a `sin`
 * per lit fragment and light — measured on SwiftShader at about 5 % of a frame with no gobo in it, so
 * the pair was chosen over it: building the basis is a reciprocal and a few multiply-adds, as the
 * normalise and projection it replaced were. [frameInBasis] builds the basis from the axis exactly
 * as the GPU will read it (float32), so the two agree on which side of the basis's seam a beam
 * pointing straight across the stage lies. The measurements are in `frontend/docs/stage-vis-engineering.md`
 * §"Gobos on surfaces".
 *
 * Texel 3 is where the beam lands ([`landing.ts`](./landing.ts)): the face its axis hit
 * ([`beamReach.ts`](./beamReach.ts)) and, for a beam split across an edge, the face the rest of it
 * lands on. The haze cuts at the same planes; the surfaces read them only for a light whose cone
 * reaches more colliders than its list holds (`occlusion.ts`), and are otherwise shadowed by the
 * colliders themselves. Neither cuts a light that reaches nothing.
 *
 * Pure and three.js-free, so the packing is pinned by a node test.
 */

import { packLanding, type LandingFace } from './landing'

export const LIGHT_TEXELS = 6
const FLOATS = LIGHT_TEXELS * 4

/** The texture's rows — the most any budget can ask for. */
export const MAX_LIGHT_BUDGET = 256
/** The budgets the View menu offers. */
export const LIGHT_BUDGETS: readonly number[] = [32, 64, 128, 256]
/** The design record's "up to 64" (§"5. Light that lands on things"); measured in §10's pass. */
export const DEFAULT_LIGHT_BUDGET = 64

/** A light whose colour × level peaks below this lands nothing worth drawing. */
const DARK = 1e-3

/** One light as the directors write it (a scratch object, reused per frame). */
export interface LightRow {
  /** The apex. */
  ax: number
  ay: number
  az: number
  /** The axis, unit. */
  dx: number
  dy: number
  dz: number
  cosBound: number
  /** Colour already scaled by the level. */
  r: number
  g: number
  b: number
  edge: number
  focusDist: number
  /** The depth of field the blur is scaled by (`beamMask.ts`'s `focusBlur`). */
  dof: number
  hit: LandingFace | null
  /** The second face a beam split across an edge lands on; null for one face, or none. */
  edgeHit: LandingFace | null
  /** The head's right axis — the beam frame's `u`, written as `(cos, sin)` in the axis's basis ([frameInBasis]). */
  rx: number
  ry: number
  rz: number
  tanHalf: number
  /** Apex → aperture. */
  near: number
  iris: number
  aspect: number
  /** The blades, [packBlades]' two floats; 0, 0 for none. */
  bladesA: number
  bladesB: number
  /** The gobo layers, `goboLayers.ts`'s `packGobos`; 0 for open. Read only where the aspect is ≤ 0. */
  gobos: number
}

/**
 * Texel 0's alpha: the focal distance in **centimetres** in the low [FOCUS_CM_BASE] (15 bits, to
 * 327.67 m — past any throw the view draws) and the depth of field in **twentieths** above it (9 bits,
 * to 25.55 — past any constant a family or a type declares). Negative is "always sharp", no focus.
 */
export const FOCUS_CM_BASE = 32768
export const DOF_STEPS = 20
const DOF_MAX_CODE = 511

/** A focal distance and a depth of field as texel 0's one float — exact in a float32 (24 bits). */
export function packFocus(focusDist: number, dof: number): number {
  if (!(focusDist >= 0)) return -1
  const cm = Math.round(Math.min(FOCUS_CM_BASE - 1, focusDist * 100))
  const dq = Math.round(Math.min(DOF_MAX_CODE, Math.max(0, Number.isFinite(dof) ? dof * DOF_STEPS : 0)))
  return cm + FOCUS_CM_BASE * dq
}

/** The GLSL that unpacks [packFocus]: `vec2(focusDist, dof)`, a negative distance for none. */
export const UNPACK_FOCUS_GLSL = /* glsl */ `
  vec2 unpackFocus(float packed) {
    if (packed < 0.0) return vec2(-1.0, 0.0);
    float dq = floor(packed / ${FOCUS_CM_BASE}.0);
    return vec2((packed - dq * ${FOCUS_CM_BASE}.0) / 100.0, dq / ${DOF_STEPS}.0);
  }
`

/** Quantisation of the edge and iris in texel 2's alpha: 1023 steps each, the iris above. */
export const EDGE_IRIS_STEPS = 1023
const EDGE_IRIS_BASE = 1024

/** Edge hardness and iris, both 0..1, as texel 2's one float — exact in a float32 (20 bits). */
export function packEdgeIris(edge: number, iris: number): number {
  const q = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * EDGE_IRIS_STEPS)
  return q(edge) + EDGE_IRIS_BASE * q(iris)
}

/** The GLSL that unpacks [packEdgeIris]: `vec2(edge, iris)`. Division by a power of two is exact. */
export const UNPACK_EDGE_IRIS_GLSL = /* glsl */ `
  vec2 unpackEdgeIris(float packed) {
    float iq = floor(packed / ${EDGE_IRIS_BASE}.0);
    return vec2(packed - iq * ${EDGE_IRIS_BASE}.0, iq) / ${EDGE_IRIS_STEPS}.0;
  }
`

/**
 * The basis the frame is written in, built from the beam's axis alone: Duff et al.'s branchless
 * orthonormal basis ("Building an Orthonormal Basis, Revisited", JCGT 2017), the same arithmetic as
 * [BEAM_FRAME_GLSL]'s `beamBasis`. Its seam is the axis crossing z = 0, where the sign flips; a beam
 * pointing straight across the stage sits on it, so [frameInBasis] reads the axis as the GPU does —
 * rounded to float32, `>= 0` for the sign — and both sides pick the same basis.
 */
function beamBasis(nx: number, ny: number, nz: number, out: Float64Array): void {
  const s = nz >= 0 ? 1 : -1
  const a = -1 / (s + nz)
  const b = nx * ny * a
  out[0] = 1 + s * nx * nx * a
  out[1] = s * b
  out[2] = -s * nx
  out[3] = b
  out[4] = s + ny * ny * a
  out[5] = -ny
}

const BASIS = new Float64Array(6)

/**
 * The frame's `u` — the head's right axis, as far as it lies at right angles to the beam — as a unit
 * `(cos, sin)` in [beamBasis], written into [out] (texel 4's `.xy`). A right axis along the beam
 * (degenerate; never written by a director) gives `(1, 0)`.
 */
export function frameInBasis(
  dx: number, dy: number, dz: number,
  rx: number, ry: number, rz: number,
  out: { [i: number]: number }, o = 0,
): void {
  beamBasis(Math.fround(dx), Math.fround(dy), Math.fround(dz), BASIS)
  const x = rx * BASIS[0] + ry * BASIS[1] + rz * BASIS[2]
  const y = rx * BASIS[3] + ry * BASIS[4] + rz * BASIS[5]
  const len = Math.hypot(x, y)
  out[o] = len > 0 ? x / len : 1
  out[o + 1] = len > 0 ? y / len : 0
}

/**
 * The frame back from its `(cos, sin)` — the twin of [BEAM_FRAME_GLSL]'s `beamFrame`, for the test:
 * `u` and `v = axis × u`, as `[ux, uy, uz, vx, vy, vz]`.
 */
export function frameFromBasis(dx: number, dy: number, dz: number, c: number, s: number): number[] {
  beamBasis(Math.fround(dx), Math.fround(dy), Math.fround(dz), BASIS)
  const ux = c * BASIS[0] + s * BASIS[3]
  const uy = c * BASIS[1] + s * BASIS[4]
  const uz = c * BASIS[2] + s * BASIS[5]
  return [ux, uy, uz, dy * uz - dz * uy, dz * ux - dx * uz, dx * uy - dy * ux]
}

/**
 * The GLSL that turns texel 4's `(cos, sin)` back into the frame: `beamFrame(axis, cs, bx, by)`,
 * `bx` the frame's `u` and `by = axis × bx`, as [frameFromBasis]. No `cos` or `sin`: the pair is
 * stored, not an angle.
 */
export const BEAM_FRAME_GLSL = /* glsl */ `
  void beamBasis(vec3 n, out vec3 b1, out vec3 b2) {
    float s = n.z >= 0.0 ? 1.0 : -1.0;
    float a = -1.0 / (s + n.z);
    float b = n.x * n.y * a;
    b1 = vec3(1.0 + s * n.x * n.x * a, s * b, -s * n.x);
    b2 = vec3(b, s + n.y * n.y * a, -n.y);
  }

  void beamFrame(vec3 axis, vec2 cs, out vec3 bx, out vec3 by) {
    vec3 b1;
    vec3 b2;
    beamBasis(axis, b1, b2);
    bx = cs.x * b1 + cs.y * b2;
    by = cross(axis, bx);
  }
`

/** A fresh row, for a caller's scratch. */
export function makeLightRow(): LightRow {
  return {
    ax: 0, ay: 0, az: 0, dx: 0, dy: -1, dz: 0, cosBound: 1, r: 0, g: 0, b: 0, edge: 0, focusDist: -1, dof: 0,
    hit: null, edgeHit: null, rx: 1, ry: 0, rz: 0, tanHalf: 0, near: 0, iris: 1, aspect: 0, bladesA: 0, bladesB: 0,
    gobos: 0,
  }
}

export class LightTable {
  readonly capacity: number
  /** Every slot's row, in slot order. */
  readonly staged: Float32Array
  /** Each slot's weight — its brightest channel — or 0 while it is dark or unwritten. */
  readonly weight: Float32Array
  private readonly order: Int32Array
  /** Set by a write or a clear; [pack] runs only when it is. */
  dirty = true

  constructor(capacity: number) {
    this.capacity = Math.max(0, Math.floor(capacity))
    this.staged = new Float32Array(this.capacity * FLOATS)
    this.weight = new Float32Array(this.capacity)
    this.order = new Int32Array(this.capacity)
  }

  /** Write slot [i]. A [LightRow.hit] of null is a beam that reaches nothing. */
  set(i: number, row: LightRow): void {
    if (i < 0 || i >= this.capacity) return
    const o = i * FLOATS
    const s = this.staged
    s[o] = row.ax
    s[o + 1] = row.ay
    s[o + 2] = row.az
    s[o + 3] = packFocus(row.focusDist, row.dof)
    s[o + 4] = row.dx
    s[o + 5] = row.dy
    s[o + 6] = row.dz
    s[o + 7] = row.cosBound
    s[o + 8] = row.r
    s[o + 9] = row.g
    s[o + 10] = row.b
    s[o + 11] = packEdgeIris(row.edge, row.iris)
    packLanding(row.hit, row.edgeHit, s, o + 12)
    frameInBasis(row.dx, row.dy, row.dz, row.rx, row.ry, row.rz, s, o + 16)
    s[o + 18] = row.gobos
    s[o + 19] = row.tanHalf
    s[o + 20] = row.near
    s[o + 21] = row.bladesA
    s[o + 22] = row.aspect
    s[o + 23] = row.bladesB
    const w = Math.max(row.r, row.g, row.b)
    this.weight[i] = w > DARK ? w : 0
    this.dirty = true
  }

  /** Slot [i] lands nothing — its fixture is dark, hidden, or has no such lobe this frame. */
  clear(i: number): void {
    if (i < 0 || i >= this.capacity || this.weight[i] === 0) return
    this.weight[i] = 0
    this.dirty = true
  }

  /**
   * Copy the lit slots into [out]'s rows — all of them while they fit the [budget], else the
   * [budget] brightest — and answer how many. Rows keep slot order among equals, so a light does not
   * hop rows while nothing about it changes.
   */
  pack(budget: number, out: Float32Array): number {
    const cap = Math.min(Math.max(0, Math.floor(budget)), Math.floor(out.length / FLOATS))
    let lit = 0
    for (let i = 0; i < this.capacity; i++) if (this.weight[i] > 0) this.order[lit++] = i
    let n = lit
    if (lit > cap) {
      const weight = this.weight
      const view = this.order.subarray(0, lit)
      view.sort((a, b) => weight[b] - weight[a] || a - b)
      n = cap
      // Back into slot order, so the kept lights' rows are stable while the set is.
      this.order.subarray(0, n).sort()
    }
    for (let k = 0; k < n; k++) {
      const from = this.order[k] * FLOATS
      out.set(this.staged.subarray(from, from + FLOATS), k * FLOATS)
    }
    this.dirty = false
    return n
  }

  /** How many slots are lit now, whatever the budget — for the View menu's read-out. */
  litCount(): number {
    let n = 0
    for (let i = 0; i < this.capacity; i++) if (this.weight[i] > 0) n++
    return n
  }
}
