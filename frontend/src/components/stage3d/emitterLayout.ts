/**
 * Instance-index and capacity arithmetic for the shared beam emitters.
 *
 * Every InstancedMesh in `StageEmitters` addresses its instances through these
 * functions, so the off-by-ones live here as node-tested pure math instead of
 * being discovered on screen as one fixture's gobo bleeding into the next
 * fixture's cookies.
 *
 * Layout: a fixture *slot* owns `MAX_PRISM_LOBES` consecutive beam instances
 * ("lobes") on each beam mesh. Lobe 0 is the primary beam and is all a fixture
 * uses until a prism engages; lobes 1+ carry the extra prism images and stay
 * parked (zero-scale / invisible) otherwise. Wash meshes are per-pixel rather
 * than per-lobe: a strip slot owns `MAX_WASH_PIXELS` consecutive instances.
 */

/** Region OBBs the shaders can shadow-test per fragment. */
export const MAX_BEAM_REGIONS = 16

/**
 * Lobes (displaced whole-beam images) per fixture slot. Every real prism in
 * the library is 3-facet; the headroom is for exotic wheels, and
 * `resolvePrismFacets` clamps to it.
 */
export const MAX_PRISM_LOBES = 6

/** Per-fixture cap on independently-washed pixels (strip/bar fixtures). */
export const MAX_WASH_PIXELS = 16

export const BEAM_LENGTH = 8
export const COOKIE_LIFT_M = 0.001

/** Instance index of a (slot, lobe) on the cone / floor / wall meshes. */
export function beamInstanceIndex(slot: number, lobe: number): number {
  return slot * MAX_PRISM_LOBES + lobe
}

/** Instance index of a (slot, lobe, region) on the region receiver mesh. */
export function regionInstanceIndex(
  slot: number,
  lobe: number,
  regionCount: number,
  regionIdx: number,
): number {
  return beamInstanceIndex(slot, lobe) * regionCount + regionIdx
}

/** Instance index of a (slot, pixel) on the wash floor mesh. */
export function washPixelIndex(slot: number, pixelIdx: number): number {
  return slot * MAX_WASH_PIXELS + pixelIdx
}

/** Instance index of a (slot, pixel, region) on the wash region mesh. */
export function washRegionInstanceIndex(
  slot: number,
  pixelIdx: number,
  regionCount: number,
  regionIdx: number,
): number {
  return washPixelIndex(slot, pixelIdx) * regionCount + regionIdx
}

// Buffer capacities must be ≥1 even when nothing draws yet — Three.js' WebGL
// backend can't bind zero-sized instance buffers. Draw counts come from
// mesh.count, which can still be 0.
export function slotCapacity(fixtureCount: number): number {
  return Math.max(fixtureCount, 1)
}

export function beamCapacity(fixtureCount: number): number {
  return slotCapacity(fixtureCount) * MAX_PRISM_LOBES
}

export function regionDivisor(regionCount: number): number {
  return Math.max(regionCount, 1)
}

export function regionCapacity(fixtureCount: number, regionCount: number): number {
  return beamCapacity(fixtureCount) * regionDivisor(regionCount)
}

export function washFloorCapacity(fixtureCount: number): number {
  return slotCapacity(fixtureCount) * MAX_WASH_PIXELS
}

export function washRegionCapacity(fixtureCount: number, regionCount: number): number {
  return washFloorCapacity(fixtureCount) * regionDivisor(regionCount)
}
