/**
 * Instance-index and capacity arithmetic for the shared beam emitters.
 *
 * The beam meshes in `StageEmitters` and its light table address their slots through these
 * functions, so the off-by-ones live here as node-tested pure math instead of being discovered on
 * screen as one fixture's gobo bleeding into the next fixture's beam.
 *
 * Layout: each fixture *slot* owns a block of consecutive beam instances ("lobes"), and a block of
 * rows of the light table, sized by what that fixture can actually draw ([SlotNeeds]).
 *
 * - **A body with one cell** (a lantern, a mover) has lobe 0 as its beam and, where it has a prism,
 *   lobes 1+ for the extra prism images, parked otherwise; each lobe lands as its own light.
 * - **A body with several cells** (a batten, a blinder, a bar of heads — stage-view plan session 6)
 *   has one lobe per cell and at most [MAX_LIGHTS_PER_FIXTURE] lights, each averaging a run of
 *   cells, so a 12-pixel bar does not take 12 of the surface shader's lights.
 *
 * The blocks were a fixed `MAX_PRISM_LOBES` lobes and 16 washing pixels for every slot before
 * session 0, and until session 6 a pixel strip had a wash block of one light per pixel and no beams.
 */

/** Region OBBs the beam shaders can shadow-test per fragment. */
export const MAX_BEAM_REGIONS = 16

/**
 * Lobes (displaced whole-beam images) a prism fixture's slot carries. Every
 * real prism in the library is 3-facet; the headroom is for exotic wheels, and
 * `resolvePrismFacets` clamps to it.
 */
export const MAX_PRISM_LOBES = 6

/** A body with more cells than this draws the first this many (`bodies/archetype.ts`); each is its own beam. */
export const MAX_CELLS = 16

/** Lights a body of several cells lands on the surfaces, each averaging a run of its cells. */
export const MAX_LIGHTS_PER_FIXTURE = 4

/** The most beam instances one slot can hold: a prism's lobes or a body's cells. */
export const MAX_SLOT_LOBES = Math.max(MAX_PRISM_LOBES, MAX_CELLS)

/**
 * How far a beam is drawn in the air: to the first surface on its axis, or this far, whichever is
 * nearer. The desk's stylised length — the light itself reaches further ([MAX_THROW_M]).
 */
export const BEAM_LENGTH = 8

/** How far a light reaches a surface: a hall's length. */
export const MAX_THROW_M = 40

/** What one fixture slot can draw, and so how many instances it is given. */
export interface SlotNeeds {
  /** Beam instances: 0 for no beam, 1 for a plain beam, `MAX_PRISM_LOBES` with a prism, or one per cell. */
  lobes: number
  /** Rows of the light table: one per lobe for a single cell, else one per run of cells (≤ 4). */
  lights: number
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
  readonly lightBase: Int32Array
  readonly lightCount: Int32Array
  readonly totalLights: number
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
  const lightBase = new Int32Array(n)
  const lightCount = new Int32Array(n)
  let lobes = 0
  let lights = 0
  const parts: string[] = []
  for (let i = 0; i < n; i++) {
    const l = clampInt(needs[i].lobes, 0, MAX_SLOT_LOBES)
    // A light per lobe at most, and never more than a prism's worth.
    const w = clampInt(needs[i].lights, 0, Math.min(l, Math.max(MAX_PRISM_LOBES, MAX_LIGHTS_PER_FIXTURE)))
    lobeBase[i] = lobes
    lobeCount[i] = l
    lightBase[i] = lights
    lightCount[i] = w
    lobes += l
    lights += w
    parts.push(`${l}.${w}`)
  }
  return {
    slotCount: n,
    lobeBase,
    lobeCount,
    totalLobes: lobes,
    lightBase,
    lightCount,
    totalLights: lights,
    signature: parts.join(','),
  }
}

/** Lobes this slot may write; 0 for a slot the layout does not know (a stale slot index). */
export function lobesFor(layout: EmitterLayout, slot: number): number {
  return slot >= 0 && slot < layout.slotCount ? layout.lobeCount[slot] : 0
}

/** Lights this slot may write; 0 for a slot the layout does not know. */
export function lightsFor(layout: EmitterLayout, slot: number): number {
  return slot >= 0 && slot < layout.slotCount ? layout.lightCount[slot] : 0
}

/**
 * Instance index of a (slot, lobe) on the beam mesh. The caller must have checked
 * `lobe < lobesFor(layout, slot)`; past it this is another slot's.
 */
export function beamInstanceIndex(layout: EmitterLayout, slot: number, lobe: number): number {
  return layout.lobeBase[slot] + lobe
}

/** Row of a (slot, light) in the light table. Bounds as [beamInstanceIndex], against [lightsFor]. */
export function lightRowIndex(layout: EmitterLayout, slot: number, light: number): number {
  return layout.lightBase[slot] + light
}

// Buffer capacities must be ≥1 even when nothing draws yet — Three.js' WebGL
// backend can't bind zero-sized instance buffers. Draw counts come from
// mesh.count, which can still be 0.
export function beamCapacity(layout: EmitterLayout): number {
  return Math.max(layout.totalLobes, 1)
}
