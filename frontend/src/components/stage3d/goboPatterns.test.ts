import { describe, it, expect } from 'vitest'
import {
  GOBO_PATTERNS,
  GOBO_SLOT_COUNT,
  PATTERN_GENERATORS,
  goboLayerFor,
} from './goboPatterns'
import { GOBO_TILE_PX, buildGoboAtlasData } from './goboAtlas'

describe('gobo pattern registry', () => {
  it('keeps layer 0 as the open invariant', () => {
    expect(GOBO_PATTERNS[0]).toBe('open')
    expect(goboLayerFor('open')).toBe(0)
  })

  it('covers every name with a generator and a resolvable layer', () => {
    expect(GOBO_SLOT_COUNT).toBe(GOBO_PATTERNS.length)
    GOBO_PATTERNS.forEach((name, i) => {
      expect(goboLayerFor(name), name).toBe(i)
      expect(typeof PATTERN_GENERATORS[name], name).toBe('function')
    })
  })

  it('keeps the original seven layers at their original indices', () => {
    // Pre-registry builds hard-coded these positions; unannotated wheels
    // (index fallback) and any stored screenshots rely on them not moving.
    expect(GOBO_PATTERNS.slice(0, 8)).toEqual([
      'open', 'dots', 'breakup', 'spokes', 'triple', 'rings', 'starburst', 'bars',
    ])
  })

  it('is undefined for unknown or missing names', () => {
    expect(goboLayerFor('hyperspace_vortex')).toBeUndefined()
    expect(goboLayerFor(undefined)).toBeUndefined()
  })

  it('generators stay in 0..1 across the disc', () => {
    for (const name of GOBO_PATTERNS) {
      const gen = PATTERN_GENERATORS[name]
      for (let r = 0; r <= 1; r += 0.1) {
        for (let a = -Math.PI; a <= Math.PI; a += Math.PI / 7) {
          const v = gen(r, a)
          expect(v, `${name} at r=${r} a=${a.toFixed(2)}`).toBeGreaterThanOrEqual(0)
          expect(v, `${name} at r=${r} a=${a.toFixed(2)}`).toBeLessThanOrEqual(1)
        }
      }
    }
  })

  it('bakes pairwise-distinct layers — a copy-pasted generator would collide', () => {
    const data = buildGoboAtlasData()
    const layerBytes = GOBO_TILE_PX * GOBO_TILE_PX
    // A coarse per-layer signature: sampled bytes at a fixed stride.
    const signatures = GOBO_PATTERNS.map((_, i) => {
      const sig: number[] = []
      for (let o = 0; o < layerBytes; o += 331) {
        sig.push(data[i * layerBytes + o])
      }
      return sig.join(',')
    })
    expect(new Set(signatures).size).toBe(GOBO_PATTERNS.length)
  })
})
