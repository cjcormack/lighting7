/**
 * Instance-index and capacity arithmetic for the shared beam emitters.
 *
 * Every InstancedMesh in `StageEmitters` addresses its instances through these
 * functions, so the off-by-ones live here as node-tested pure math instead of
 * being discovered on screen as one fixture's gobo bleeding into the next
 * fixture's cookies.
 *
 * Layout: each fixture *slot* owns a block of consecutive beam instances
 * ("lobes") on each beam mesh, and a block of wash instances on each wash
 * mesh, sized by what that fixture can actually draw ([SlotNeeds]). Lobe 0 is
 * the primary beam and is all a fixture uses until a prism engages; lobes 1+
 * carry the extra prism images and stay parked (zero-scale / invisible)
 * otherwise. Wash meshes are per-pixel rather than per-lobe.
 *
 * The blocks used to be a fixed `MAX_PRISM_LOBES` lobes and `MAX_WASH_PIXELS`
 * pixels for every slot, so a 45-fixture rig with 16 regions drew 4,320
 * region-cookie and 11,520 wash-region instances whether or not a single prism
 * or pixel bar was hung — almost all of them invisible, all of them run through
 * the vertex shader every frame (stage-view plan, session 0). Now a par gets one
 * lobe and no wash block, a fixture with no beam gets neither, and only a prism
 * fixture pays for six.
 */

/** Region OBBs the shaders can shadow-test per fragment. */
export const MAX_BEAM_REGIONS = 16

/**
 * Lobes (displaced whole-beam images) a prism fixture's slot carries. Every
 * real prism in the library is 3-facet; the headroom is for exotic wheels, and
 * `resolvePrismFacets` clamps to it.
 */
export const MAX_PRISM_LOBES = 6

/** Per-fixture cap on independently-washed pixels (strip/bar fixtures). */
export const MAX_WASH_PIXELS = 16

export const BEAM_LENGTH = 8
export const COOKIE_LIFT_M = 0.001

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
 * Instance index of a (slot, lobe) on the cone / volume / floor / wall meshes. The caller must
 * have checked `lobe < lobesFor(layout, slot)`; past it this is another slot's instance.
 */
export function beamInstanceIndex(layout: EmitterLayout, slot: number, lobe: number): number {
  return layout.lobeBase[slot] + lobe
}

/** Instance index of a (slot, lobe, region) on the region receiver mesh. */
export function regionInstanceIndex(
  layout: EmitterLayout,
  slot: number,
  lobe: number,
  regionCount: number,
  regionIdx: number,
): number {
  return beamInstanceIndex(layout, slot, lobe) * regionCount + regionIdx
}

/** Instance index of a (slot, pixel) on the wash floor mesh. Bounds as [beamInstanceIndex]. */
export function washPixelIndex(layout: EmitterLayout, slot: number, pixelIdx: number): number {
  return layout.washBase[slot] + pixelIdx
}

/** Instance index of a (slot, pixel, region) on the wash region mesh. */
export function washRegionInstanceIndex(
  layout: EmitterLayout,
  slot: number,
  pixelIdx: number,
  regionCount: number,
  regionIdx: number,
): number {
  return washPixelIndex(layout, slot, pixelIdx) * regionCount + regionIdx
}

// Buffer capacities must be ≥1 even when nothing draws yet — Three.js' WebGL
// backend can't bind zero-sized instance buffers. Draw counts come from
// mesh.count, which can still be 0.
export function beamCapacity(layout: EmitterLayout): number {
  return Math.max(layout.totalLobes, 1)
}

export function regionDivisor(regionCount: number): number {
  return Math.max(regionCount, 1)
}

export function regionCapacity(layout: EmitterLayout, regionCount: number): number {
  return beamCapacity(layout) * regionDivisor(regionCount)
}

export function washFloorCapacity(layout: EmitterLayout): number {
  return Math.max(layout.totalWashPixels, 1)
}

export function washRegionCapacity(layout: EmitterLayout, regionCount: number): number {
  return washFloorCapacity(layout) * regionDivisor(regionCount)
}
