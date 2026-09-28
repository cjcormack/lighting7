import { describe, it, expect } from 'vitest'
import {
  MAX_PRISM_LOBES,
  MAX_WASH_PIXELS,
  beamCapacity,
  beamInstanceIndex,
  regionCapacity,
  regionInstanceIndex,
  washFloorCapacity,
  washPixelIndex,
  washRegionCapacity,
  washRegionInstanceIndex,
} from './emitterLayout'

describe('emitter layout', () => {
  it('gives each slot a contiguous, non-overlapping lobe block', () => {
    expect(beamInstanceIndex(0, 0)).toBe(0)
    expect(beamInstanceIndex(0, MAX_PRISM_LOBES - 1)).toBe(MAX_PRISM_LOBES - 1)
    expect(beamInstanceIndex(1, 0)).toBe(MAX_PRISM_LOBES)
    // Adjacent slots never share an instance.
    expect(beamInstanceIndex(3, MAX_PRISM_LOBES - 1) + 1).toBe(beamInstanceIndex(4, 0))
  })

  it('lays region instances out lobe-major within a slot', () => {
    const R = 16
    expect(regionInstanceIndex(0, 0, R, 0)).toBe(0)
    expect(regionInstanceIndex(0, 0, R, R - 1)).toBe(R - 1)
    expect(regionInstanceIndex(0, 1, R, 0)).toBe(R)
    expect(regionInstanceIndex(2, 3, R, 5)).toBe((2 * MAX_PRISM_LOBES + 3) * R + 5)
  })

  it('keeps wash blocks per-pixel, independent of lobes', () => {
    expect(washPixelIndex(0, 0)).toBe(0)
    expect(washPixelIndex(2, 3)).toBe(2 * MAX_WASH_PIXELS + 3)
    expect(washRegionInstanceIndex(1, 2, 16, 7)).toBe((MAX_WASH_PIXELS + 2) * 16 + 7)
  })

  it('sizes capacities to the harness worst case', () => {
    // 50 fixtures × 16 regions — the profile harness scene.
    expect(beamCapacity(50)).toBe(50 * MAX_PRISM_LOBES)
    expect(regionCapacity(50, 16)).toBe(50 * MAX_PRISM_LOBES * 16)
    expect(washFloorCapacity(50)).toBe(50 * MAX_WASH_PIXELS)
    expect(washRegionCapacity(50, 16)).toBe(50 * MAX_WASH_PIXELS * 16)
  })

  it('never sizes a zero-capacity buffer', () => {
    expect(beamCapacity(0)).toBeGreaterThan(0)
    expect(regionCapacity(0, 0)).toBeGreaterThan(0)
    expect(washFloorCapacity(0)).toBeGreaterThan(0)
    expect(washRegionCapacity(0, 0)).toBeGreaterThan(0)
  })
})
