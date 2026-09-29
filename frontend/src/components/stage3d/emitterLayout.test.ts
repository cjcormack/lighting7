import { describe, it, expect } from 'vitest'
import {
  MAX_PRISM_LOBES,
  MAX_WASH_PIXELS,
  beamCapacity,
  beamInstanceIndex,
  buildEmitterLayout,
  lobesFor,
  regionCapacity,
  regionInstanceIndex,
  washFloorCapacity,
  washPixelIndex,
  washPixelsFor,
  washRegionCapacity,
  washRegionInstanceIndex,
} from './emitterLayout'

// A par, a prism mover, a pixel bar with no beam, a par again.
const RIG = buildEmitterLayout([
  { lobes: 1, washPixels: 0 },
  { lobes: MAX_PRISM_LOBES, washPixels: 0 },
  { lobes: 0, washPixels: 12 },
  { lobes: 1, washPixels: 0 },
])

describe('emitter layout', () => {
  it('gives each slot a contiguous, non-overlapping lobe block sized to its needs', () => {
    expect(beamInstanceIndex(RIG, 0, 0)).toBe(0)
    expect(beamInstanceIndex(RIG, 1, 0)).toBe(1)
    expect(beamInstanceIndex(RIG, 1, MAX_PRISM_LOBES - 1)).toBe(MAX_PRISM_LOBES)
    // The beamless bar takes no lobes, so the next par starts straight after the prism block.
    expect(beamInstanceIndex(RIG, 3, 0)).toBe(1 + MAX_PRISM_LOBES)
    expect(RIG.totalLobes).toBe(2 + MAX_PRISM_LOBES)
    expect(lobesFor(RIG, 2)).toBe(0)
  })

  it('lays region instances out lobe-major within a slot', () => {
    const R = 16
    expect(regionInstanceIndex(RIG, 0, 0, R, 0)).toBe(0)
    expect(regionInstanceIndex(RIG, 0, 0, R, R - 1)).toBe(R - 1)
    expect(regionInstanceIndex(RIG, 1, 0, R, 0)).toBe(R)
    expect(regionInstanceIndex(RIG, 1, 3, R, 5)).toBe((1 + 3) * R + 5)
  })

  it('keeps wash blocks per-pixel, only for the slots that wash', () => {
    expect(washPixelsFor(RIG, 0)).toBe(0)
    expect(washPixelsFor(RIG, 2)).toBe(12)
    expect(washPixelIndex(RIG, 2, 0)).toBe(0)
    expect(washPixelIndex(RIG, 2, 11)).toBe(11)
    expect(washRegionInstanceIndex(RIG, 2, 3, 16, 7)).toBe(3 * 16 + 7)
    expect(RIG.totalWashPixels).toBe(12)
  })

  it('sizes capacities by what the rig has, not by fixtures × the worst case', () => {
    // 45 plain fixtures and 16 regions: the stage-view record's finding was 45 × 6 × 16 region
    // cookies and 45 × 16 × 16 wash-region instances, nearly all of them invisible.
    const plain = buildEmitterLayout(Array.from({ length: 45 }, () => ({ lobes: 1, washPixels: 0 })))
    expect(beamCapacity(plain)).toBe(45)
    expect(regionCapacity(plain, 16)).toBe(45 * 16)
    expect(washFloorCapacity(plain)).toBe(1)
    expect(washRegionCapacity(plain, 16)).toBe(16)
  })

  it('clamps a slot to the per-slot caps', () => {
    const l = buildEmitterLayout([{ lobes: 99, washPixels: 99 }, { lobes: -1, washPixels: NaN }])
    expect(lobesFor(l, 0)).toBe(MAX_PRISM_LOBES)
    expect(washPixelsFor(l, 0)).toBe(MAX_WASH_PIXELS)
    expect(lobesFor(l, 1)).toBe(0)
    expect(washPixelsFor(l, 1)).toBe(0)
  })

  it('answers 0 for a slot it does not know', () => {
    expect(lobesFor(RIG, -1)).toBe(0)
    expect(lobesFor(RIG, 4)).toBe(0)
    expect(washPixelsFor(RIG, 99)).toBe(0)
  })

  it('never sizes a zero-capacity buffer', () => {
    const empty = buildEmitterLayout([])
    expect(beamCapacity(empty)).toBeGreaterThan(0)
    expect(regionCapacity(empty, 0)).toBeGreaterThan(0)
    expect(washFloorCapacity(empty)).toBeGreaterThan(0)
    expect(washRegionCapacity(empty, 0)).toBeGreaterThan(0)
  })

  it('gives equal needs an equal signature, and different needs a different one', () => {
    const a = buildEmitterLayout([{ lobes: 1, washPixels: 0 }, { lobes: 0, washPixels: 8 }])
    const b = buildEmitterLayout([{ lobes: 1, washPixels: 0 }, { lobes: 0, washPixels: 8 }])
    const c = buildEmitterLayout([{ lobes: 0, washPixels: 8 }, { lobes: 1, washPixels: 0 }])
    expect(a.signature).toBe(b.signature)
    expect(a.signature).not.toBe(c.signature)
  })
})
