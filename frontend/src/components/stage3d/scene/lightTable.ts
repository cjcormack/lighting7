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
 * Four RGBA texels a light, in three.js space:
 *
 * | texel | rgb | a |
 * |---|---|---|
 * | 0 | origin | focal distance (m; < 0 is always sharp) |
 * | 1 | axis (unit) | cos of the half-angle |
 * | 2 | colour × level | edge hardness 0..1 |
 * | 3 | the reach plane's normal, towards the light | the plane's offset `n · p` |
 *
 * Texel 3 is the axial reach ([`beamReach.ts`](./beamReach.ts)): a fragment behind the plane of the
 * first surface on the axis is not lit. A light that reaches nothing carries a zero normal and a
 * negative offset, which lights everything in its cone.
 *
 * Pure and three.js-free, so the packing is pinned by a node test.
 */

export const LIGHT_TEXELS = 4
const FLOATS = LIGHT_TEXELS * 4

/** The texture's rows — the most any budget can ask for. */
export const MAX_LIGHT_BUDGET = 256
/** The budgets the View menu offers. */
export const LIGHT_BUDGETS: readonly number[] = [32, 64, 128, 256]
/** The design record's "up to 64" (§"5. Light that lands on things"); measured in §10's pass. */
export const DEFAULT_LIGHT_BUDGET = 64

/** A light whose colour × level peaks below this lands nothing worth drawing. */
const DARK = 1e-3

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

  /**
   * Write slot [i]. [r], [g], [b] are the colour already scaled by the level. A [hit] of null is a
   * beam that reaches nothing.
   */
  set(
    i: number,
    ox: number,
    oy: number,
    oz: number,
    dx: number,
    dy: number,
    dz: number,
    cosHalf: number,
    r: number,
    g: number,
    b: number,
    edge: number,
    focusDist: number,
    hit: { nx: number; ny: number; nz: number; px: number; py: number; pz: number } | null,
  ): void {
    if (i < 0 || i >= this.capacity) return
    const o = i * FLOATS
    const s = this.staged
    s[o] = ox
    s[o + 1] = oy
    s[o + 2] = oz
    s[o + 3] = focusDist
    s[o + 4] = dx
    s[o + 5] = dy
    s[o + 6] = dz
    s[o + 7] = cosHalf
    s[o + 8] = r
    s[o + 9] = g
    s[o + 10] = b
    s[o + 11] = edge
    if (hit == null) {
      s[o + 12] = 0
      s[o + 13] = 0
      s[o + 14] = 0
      s[o + 15] = -1
    } else {
      s[o + 12] = hit.nx
      s[o + 13] = hit.ny
      s[o + 14] = hit.nz
      s[o + 15] = hit.nx * hit.px + hit.ny * hit.py + hit.nz * hit.pz
    }
    const w = Math.max(r, g, b)
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
