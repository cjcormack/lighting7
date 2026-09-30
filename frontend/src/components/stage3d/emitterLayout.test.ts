import { describe, it, expect } from 'vitest'
import {
  MAX_PRISM_LOBES,
  MAX_SLOT_LOBES,
  beamCapacity,
  beamInstanceIndex,
  buildEmitterLayout,
  lightRowIndex,
  lightsFor,
  lobesFor,
} from './emitterLayout'

// A par, a prism mover, a 12-cell bar (a lobe a cell, four lights), a par again.
const RIG = buildEmitterLayout([
  { lobes: 1, lights: 1 },
  { lobes: MAX_PRISM_LOBES, lights: MAX_PRISM_LOBES },
  { lobes: 12, lights: 4 },
  { lobes: 1, lights: 1 },
])

describe('emitter layout', () => {
  it('gives each slot a contiguous, non-overlapping lobe block sized to its needs', () => {
    expect(beamInstanceIndex(RIG, 0, 0)).toBe(0)
    expect(beamInstanceIndex(RIG, 1, 0)).toBe(1)
    expect(beamInstanceIndex(RIG, 1, MAX_PRISM_LOBES - 1)).toBe(MAX_PRISM_LOBES)
    expect(beamInstanceIndex(RIG, 2, 0)).toBe(1 + MAX_PRISM_LOBES)
    expect(beamInstanceIndex(RIG, 3, 0)).toBe(1 + MAX_PRISM_LOBES + 12)
    expect(RIG.totalLobes).toBe(14 + MAX_PRISM_LOBES)
  })

  it('gives each slot its own block of lights — one a lobe, or one a run of cells', () => {
    expect(lightsFor(RIG, 0)).toBe(1)
    expect(lightsFor(RIG, 1)).toBe(MAX_PRISM_LOBES)
    expect(lightsFor(RIG, 2)).toBe(4)
    expect(lightRowIndex(RIG, 2, 0)).toBe(1 + MAX_PRISM_LOBES)
    // The bar's four lights, not its twelve cells, come before the last par's.
    expect(lightRowIndex(RIG, 3, 0)).toBe(1 + MAX_PRISM_LOBES + 4)
    expect(RIG.totalLights).toBe(6 + MAX_PRISM_LOBES)
  })

  it('sizes capacities by what the rig has, not by fixtures × the worst case', () => {
    // 45 plain fixtures: the stage-view record's finding was 45 × 6 beam instances (and the region
    // cookies × 16 on top, gone since session 3), nearly all of them invisible.
    const plain = buildEmitterLayout(Array.from({ length: 45 }, () => ({ lobes: 1, lights: 1 })))
    expect(beamCapacity(plain)).toBe(45)
    expect(plain.totalLights).toBe(45)
  })

  it('clamps a slot to the per-slot caps, and never gives it more lights than lobes', () => {
    const l = buildEmitterLayout([
      { lobes: 99, lights: 99 },
      { lobes: -1, lights: NaN },
      { lobes: 2, lights: 5 },
    ])
    expect(lobesFor(l, 0)).toBe(MAX_SLOT_LOBES)
    expect(lightsFor(l, 0)).toBe(MAX_PRISM_LOBES)
    expect(lobesFor(l, 1)).toBe(0)
    expect(lightsFor(l, 1)).toBe(0)
    expect(lightsFor(l, 2)).toBe(2)
  })

  it('answers 0 for a slot it does not know', () => {
    expect(lobesFor(RIG, -1)).toBe(0)
    expect(lobesFor(RIG, 4)).toBe(0)
    expect(lightsFor(RIG, 99)).toBe(0)
  })

  it('never sizes a zero-capacity buffer', () => {
    const empty = buildEmitterLayout([])
    expect(beamCapacity(empty)).toBeGreaterThan(0)
  })

  it('gives equal needs an equal signature, and different needs a different one', () => {
    const a = buildEmitterLayout([{ lobes: 1, lights: 1 }, { lobes: 8, lights: 4 }])
    const b = buildEmitterLayout([{ lobes: 1, lights: 1 }, { lobes: 8, lights: 4 }])
    const c = buildEmitterLayout([{ lobes: 8, lights: 4 }, { lobes: 1, lights: 1 }])
    expect(a.signature).toBe(b.signature)
    expect(a.signature).not.toBe(c.signature)
  })
})
