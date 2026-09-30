/**
 * Instance-index and capacity arithmetic for the shared beam emitters.
 *
 * The beam meshes in `StageEmitters` and its light table address their slots through these
 * functions, so the off-by-ones live here as node-tested pure math instead of being discovered on
 * screen as one fixture's gobo bleeding into the next fixture's beam.
 *
 * Layout: each fixture *slot* owns a block of consecutive beam instances ("lobes"), and a block of
 * washing pixels, sized by what that fixture can actually draw ([SlotNeeds]). Lobe 0 is the primary
 * beam and is all a fixture uses until a prism engages; lobes 1+ carry the extra prism images and
 * stay parked (zero-scale, off the light table) otherwise. The light table has one row per lobe and
 * then one per washing pixel.
 *
 * The blocks used to be a fixed `MAX_PRISM_LOBES` lobes and `MAX_WASH_PIXELS` pixels for every slot
 * (stage-view plan session 0), and until session 3 each lobe and pixel was multiplied again by the
 * region count in cookie instances; those are gone with the cookies. A par gets one lobe and no
 * wash block, a fixture with no beam gets neither, and only a prism fixture pays for six.
 */

/** Region OBBs the beam shaders can shadow-test per fragment. */
export const MAX_BEAM_REGIONS = 16

/**
 * Lobes (displaced whole-beam images) a prism fixture's slot carries. Every
 * real prism in the library is 3-facet; the headroom is for exotic wheels, and
 * `resolvePrismFacets` clamps to it.
 */
export const MAX_PRISM_LOBES = 6

/** Per-fixture cap on independently-washed pixels (strip/bar fixtures). */
export const MAX_WASH_PIXELS = 16

/**
 * How far a beam is drawn in the air: to the first surface on its axis, or this far, whichever is
 * nearer. The desk's stylised length — the light itself reaches further ([MAX_THROW_M]).
 */
export const BEAM_LENGTH = 8

/** How far a light reaches a surface: a hall's length. */
export const MAX_THROW_M = 40

/** What one fixture slot can draw, and so how many instances it is given. */
export interface SlotNeeds {
  /** Beam lobes: 0 for a fixture with no beam, 1 for a plain beam, `MAX_PRISM_LOBES` with a prism. */
  lobes: number
  /** Wash pixels: 0 unless the fixture is a pixel strip, then its pixel count (capped). */
  washPixels: number
}

/**
 * Where each slot's blocks start and how long they are. Built once per rig (per change of
 * [SlotNeeds]), so the per-frame index maths is two array reads.
 */
export interface EmitterLayout {
  readonly slotCount: number
  readonly lobeBase: Int32Array
  readonly lobeCount: Int32Array
  readonly totalLobes: number
  readonly washBase: Int32Array
  readonly washCount: Int32Array
  readonly totalWashPixels: number
  /**
   * The needs, spelled out — equal for two layouts that address identically. What the emitters
   * key their rebuild on, since a fresh `needs` array arrives with every render.
   */
  readonly signature: string
}

function clampInt(v: number, lo: number, hi: number): number {
  if (!Number.isFinite(v)) return lo
  return Math.max(lo, Math.min(hi, Math.floor(v)))
}

export function buildEmitterLayout(needs: ReadonlyArray<SlotNeeds>): EmitterLayout {
  const n = needs.length
  const lobeBase = new Int32Array(n)
  const lobeCount = new Int32Array(n)
  const washBase = new Int32Array(n)
  const washCount = new Int32Array(n)
  let lobes = 0
  let wash = 0
  const parts: string[] = []
  for (let i = 0; i < n; i++) {
    const l = clampInt(needs[i].lobes, 0, MAX_PRISM_LOBES)
    const w = clampInt(needs[i].washPixels, 0, MAX_WASH_PIXELS)
    lobeBase[i] = lobes
    lobeCount[i] = l
    washBase[i] = wash
    washCount[i] = w
    lobes += l
    wash += w
    parts.push(`${l}.${w}`)
  }
  return {
    slotCount: n,
    lobeBase,
    lobeCount,
    totalLobes: lobes,
    washBase,
    washCount,
    totalWashPixels: wash,
    signature: parts.join(','),
  }
}

/** Lobes this slot may write; 0 for a slot the layout does not know (a stale slot index). */
export function lobesFor(layout: EmitterLayout, slot: number): number {
  return slot >= 0 && slot < layout.slotCount ? layout.lobeCount[slot] : 0
}

/** Wash pixels this slot may write; 0 for a slot the layout does not know. */
export function washPixelsFor(layout: EmitterLayout, slot: number): number {
  return slot >= 0 && slot < layout.slotCount ? layout.washCount[slot] : 0
}

/**
 * Instance index of a (slot, lobe) on the cone and volume meshes and its row of the light table.
 * The caller must have checked `lobe < lobesFor(layout, slot)`; past it this is another slot's.
 */
export function beamInstanceIndex(layout: EmitterLayout, slot: number, lobe: number): number {
  return layout.lobeBase[slot] + lobe
}

/** Index of a (slot, pixel) among the washing pixels — its light-table row after every lobe's. Bounds as [beamInstanceIndex]. */
export function washPixelIndex(layout: EmitterLayout, slot: number, pixelIdx: number): number {
  return layout.washBase[slot] + pixelIdx
}

// Buffer capacities must be ≥1 even when nothing draws yet — Three.js' WebGL
// backend can't bind zero-sized instance buffers. Draw counts come from
// mesh.count, which can still be 0.
export function beamCapacity(layout: EmitterLayout): number {
  return Math.max(layout.totalLobes, 1)
}
