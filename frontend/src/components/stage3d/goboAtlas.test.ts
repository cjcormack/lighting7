import { describe, it, expect } from 'vitest'
import { GOBO_TILE_PX, buildGoboAtlasData } from './goboAtlas'
import { GOBO_SLOT_COUNT } from './beamOptics'

const SIZE = GOBO_TILE_PX
const LAYER = SIZE * SIZE

function layerOf(data: Uint8Array, i: number): Uint8Array {
  return data.subarray(i * LAYER, (i + 1) * LAYER)
}

describe('buildGoboAtlasData', () => {
  const data = buildGoboAtlasData()

  it('is layer-major and correctly sized', () => {
    expect(data.length).toBe(GOBO_SLOT_COUNT * LAYER)
  })

  it('makes layer 0 fully open', () => {
    const open = layerOf(data, 0)
    expect(open.every((v) => v === 255)).toBe(true)
  })

  it('gives every pattern layer real contrast', () => {
    for (let i = 1; i < GOBO_SLOT_COUNT; i++) {
      const l = layerOf(data, i)
      let min = 255
      let max = 0
      for (const v of l) {
        if (v < min) min = v
        if (v > max) max = v
      }
      expect(min, `layer ${i} has no dark texels`).toBeLessThan(40)
      expect(max, `layer ${i} has no bright texels`).toBeGreaterThan(215)
    }
  })

  it('leaves every pattern layer dark outside the disc', () => {
    // Corners are at r = sqrt(2) > 1, so they must be masked out.
    const corners = [0, SIZE - 1, LAYER - SIZE, LAYER - 1]
    for (let i = 1; i < GOBO_SLOT_COUNT; i++) {
      const l = layerOf(data, i)
      for (const c of corners) {
        expect(l[c], `layer ${i} corner ${c}`).toBe(0)
      }
    }
  })

  it('is symmetric about the vertical axis where the pattern is', () => {
    // Rings (5) and slats (7) don't depend on the sign of x.
    for (const i of [5, 7]) {
      const l = layerOf(data, i)
      for (let py = 0; py < SIZE; py += 7) {
        for (let px = 0; px < SIZE / 2; px += 7) {
          const left = l[py * SIZE + px]
          const right = l[py * SIZE + (SIZE - 1 - px)]
          expect(Math.abs(left - right), `layer ${i} at (${px},${py})`).toBeLessThanOrEqual(1)
        }
      }
    }
  })

  it('is deterministic across calls', () => {
    const again = buildGoboAtlasData()
    expect(again.length).toBe(data.length)
    // Compare a stride rather than every byte — a PRNG leak would show up fast.
    for (let i = 0; i < again.length; i += 997) {
      expect(again[i]).toBe(data[i])
    }
  })

  it('honours a custom slot count and size', () => {
    const small = buildGoboAtlasData(3, 16)
    expect(small.length).toBe(3 * 16 * 16)
  })
})
