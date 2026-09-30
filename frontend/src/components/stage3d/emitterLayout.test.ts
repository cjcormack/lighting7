import { describe, it, expect } from 'vitest'
import {
  MAX_PRISM_LOBES,
  MAX_WASH_PIXELS,
  beamCapacity,
  beamInstanceIndex,
  buildEmitterLayout,
  lobesFor,
  washPixelIndex,
  washPixelsFor,
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

  it('keeps wash blocks per-pixel, only for the slots that wash', () => {
    expect(washPixelsFor(RIG, 0)).toBe(0)
    expect(washPixelsFor(RIG, 2)).toBe(12)
    expect(washPixelIndex(RIG, 2, 0)).toBe(0)
    expect(washPixelIndex(RIG, 2, 11)).toBe(11)
    expect(RIG.totalWashPixels).toBe(12)
  })

  it('sizes capacities by what the rig has, not by fixtures × the worst case', () => {
    // 45 plain fixtures: the stage-view record's finding was 45 × 6 beam instances (and the region
    // cookies × 16 on top, gone since session 3), nearly all of them invisible.
    const plain = buildEmitterLayout(Array.from({ length: 45 }, () => ({ lobes: 1, washPixels: 0 })))
    expect(beamCapacity(plain)).toBe(45)
    expect(plain.totalWashPixels).toBe(0)
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
  })

  it('gives equal needs an equal signature, and different needs a different one', () => {
    const a = buildEmitterLayout([{ lobes: 1, washPixels: 0 }, { lobes: 0, washPixels: 8 }])
    const b = buildEmitterLayout([{ lobes: 1, washPixels: 0 }, { lobes: 0, washPixels: 8 }])
    const c = buildEmitterLayout([{ lobes: 0, washPixels: 8 }, { lobes: 1, washPixels: 0 }])
    expect(a.signature).toBe(b.signature)
    expect(a.signature).not.toBe(c.signature)
  })
})
