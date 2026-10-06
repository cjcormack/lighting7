import { describe, it, expect } from 'vitest'
import {
  GOBO_BLUR_LEVELS,
  GOBO_TILE_PX,
  buildGoboAtlasData,
  gaussianBlur,
  goboLayerIndex,
  goboLevelSigma,
} from './goboAtlas'
import { GOBO_SLOT_COUNT } from './beamOptics'
import { atlasSampler, goboLod } from './goboLayers'
import { goboLayerFor } from './goboPatterns'
import { beamMask } from './beamMask'
import { FOCUS_LOD_MAX, GOBO_BLUR_TEXELS } from './washConfig'

const SIZE = GOBO_TILE_PX
const LAYER = SIZE * SIZE

function layerOf(data: Uint8Array, slot: number, level = 0): Uint8Array {
  const i = goboLayerIndex(slot, level)
  return data.subarray(i * LAYER, (i + 1) * LAYER)
}

describe('buildGoboAtlasData', () => {
  const data = buildGoboAtlasData()

  it('is layer-major, each pattern its blur levels in turn', () => {
    expect(data.length).toBe(GOBO_SLOT_COUNT * GOBO_BLUR_LEVELS * LAYER)
    expect(goboLayerIndex(3, 0)).toBe(3 * GOBO_BLUR_LEVELS)
    expect(goboLayerIndex(3, 2)).toBe(3 * GOBO_BLUR_LEVELS + 2)
    expect(FOCUS_LOD_MAX).toBe(GOBO_BLUR_LEVELS - 1)
    // WebGL 2 guarantees an array texture 256 layers (MAX_ARRAY_TEXTURE_LAYERS); past that the
    // upload fails on a conforming device and every gobo reads black.
    expect(GOBO_SLOT_COUNT * GOBO_BLUR_LEVELS).toBeLessThanOrEqual(256)
  })

  it('makes pattern 0 fully open at every blur', () => {
    for (let level = 0; level < GOBO_BLUR_LEVELS; level++) {
      expect(layerOf(data, 0, level).every((v) => v === 255)).toBe(true)
    }
  })

  it('gives every pattern real contrast when sharp', () => {
    for (let i = 1; i < GOBO_SLOT_COUNT; i++) {
      const l = layerOf(data, i)
      let min = 255
      let max = 0
      for (const v of l) {
        if (v < min) min = v
        if (v > max) max = v
      }
      expect(min, `pattern ${i} has no dark texels`).toBeLessThan(40)
      expect(max, `pattern ${i} has no bright texels`).toBeGreaterThan(215)
    }
  })

  it('leaves every pattern dark outside the disc, but for the blur’s own halo', () => {
    // Corners are at r = sqrt(2) > 1, so they must be masked out; only the widest blurs reach them.
    const corners = [0, SIZE - 1, LAYER - SIZE, LAYER - 1]
    for (let i = 1; i < GOBO_SLOT_COUNT; i++) {
      for (let level = 0; level < GOBO_BLUR_LEVELS; level++) {
        const l = layerOf(data, i, level)
        for (const c of corners) expect(l[c], `pattern ${i} level ${level} corner ${c}`).toBeLessThanOrEqual(level < 5 ? 0 : 16)
      }
    }
  })

  it('is symmetric about the vertical axis where the pattern is', () => {
    // Rings (5) and slats (7) don't depend on the sign of x.
    for (const i of [5, 7]) {
      for (const level of [0, 3]) {
        const l = layerOf(data, i, level)
        for (let py = 0; py < SIZE; py += 7) {
          for (let px = 0; px < SIZE / 2; px += 7) {
            const left = l[py * SIZE + px]
            const right = l[py * SIZE + (SIZE - 1 - px)]
            expect(Math.abs(left - right), `pattern ${i} level ${level} at (${px},${py})`).toBeLessThanOrEqual(1)
          }
        }
      }
    }
  })

  it('keeps a pattern’s light as it blurs, but what the widest blurs carry off the tile', () => {
    // A blur moves light, it makes none: a level sums to its sharp tile's within a percent — what
    // would not hold if a level read another pattern's. Levels 5 and 6 (σ 7 and 15 texels) spread
    // the disc's rim past the tile's edge, where the sampler clamps, and lose up to 8 %: the mush
    // end of a rack, a little dimmer.
    for (let i = 1; i < GOBO_SLOT_COUNT; i++) {
      const sharp = layerOf(data, i).reduce((s, v) => s + v, 0)
      for (let level = 1; level < GOBO_BLUR_LEVELS; level++) {
        const blurred = layerOf(data, i, level).reduce((s, v) => s + v, 0)
        expect(blurred / sharp, `pattern ${i} level ${level}`).toBeLessThan(1.01)
        expect(blurred / sharp, `pattern ${i} level ${level}`).toBeGreaterThan(level < 5 ? 0.99 : 0.9)
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
    expect(small.length).toBe(3 * GOBO_BLUR_LEVELS * 16 * 16)
  })
})

/** A tile lit left of its middle and dark right of it: one straight edge across the field's centre. */
function stepTile(): Float32Array {
  const tile = new Float32Array(LAYER)
  for (let y = 0; y < SIZE; y++) for (let x = 0; x < SIZE / 2; x++) tile[y * SIZE + x] = 1
  return tile
}

/** The profile across [tile]'s middle row as the GPU reads a level: bilinear between texel centres. */
function rowAt(levels: Float32Array[], x: number, lod: number): number {
  const read = (tile: Float32Array) => {
    const t = x - 0.5
    const x0 = Math.floor(t)
    const f = t - x0
    const at = (i: number) => tile[(SIZE / 2) * SIZE + Math.min(SIZE - 1, Math.max(0, i))]
    return at(x0) * (1 - f) + at(x0 + 1) * f
  }
  const level = Math.floor(lod)
  const t = lod - level
  const a = read(levels[level])
  return t > 0 ? a + (read(levels[level + 1]) - a) * t : a
}

/** The 10–90 % width of a falling profile [f] over `[lo, hi]`, searched finely. */
function width1090(f: (x: number) => number, lo: number, hi: number): number {
  const top = f(lo)
  const bottom = f(hi)
  const cross = (level: number) => {
    let a = lo
    let b = hi
    for (let i = 0; i < 60; i++) {
      const m = (a + b) / 2
      if ((f(m) - bottom) / (top - bottom) > level) a = m
      else b = m
    }
    return (a + b) / 2
  }
  return cross(0.1) - cross(0.9)
}

describe('the blur levels', () => {
  const step = stepTile()
  const levels = Array.from({ length: GOBO_BLUR_LEVELS }, (_, l) => gaussianBlur(step, SIZE, goboLevelSigma(l)))
  const texelsPerRadius = GOBO_BLUR_TEXELS

  it('are Gaussians of their σ: the edge’s 10–90 % width is 2.563 σ', () => {
    for (let level = 2; level < GOBO_BLUR_LEVELS; level++) {
      const sigma = goboLevelSigma(level)
      const w = width1090((x) => rowAt(levels, x, level), SIZE / 2 - 6 * sigma - 2, SIZE / 2 + 6 * sigma + 2)
      // Bilinear reading adds a texel's tent; the three boxes past σ 3 are a Gaussian within 5 %.
      const expected = 2.5631 * Math.sqrt(sigma * sigma + 1 / 6)
      expect(Math.abs(w - expected) / expected, `level ${level}`).toBeLessThan(0.05)
    }
  })

  it('blur more at every step of the level, whole or fractional: never back', () => {
    let previous = 0
    for (let lod = 0; lod <= FOCUS_LOD_MAX + 1e-9; lod += 0.25) {
      const w = width1090((x) => rowAt(levels, x, lod), 0, SIZE)
      expect(w, `lod ${lod}`).toBeGreaterThan(previous)
      previous = w
    }
  })

  it('draw a gobo’s edge as wide as the pool’s at the blur that reads them', () => {
    // The pool's focus penumbra is a smoothstep as wide as the blur, over the gate's own hard edge;
    // the blur's share is the difference. The gobo's edge, read at goboLod's level, is as wide.
    const mask = (blur: number) => width1090((u) => beamMask(u, 0, 0, 1, 0, blur), 0.3, 1.7)
    for (const blur of [0.05, 0.1, 0.2, 0.3, 0.45, 0.6]) {
      const lod = goboLod(blur, 0, texelsPerRadius, FOCUS_LOD_MAX)
      const gobo = width1090((x) => rowAt(levels, x, lod), 0, SIZE) / texelsPerRadius
      const pool = mask(blur) - mask(0)
      expect(gobo / pool, `blur ${blur}`).toBeGreaterThan(0.8)
      expect(gobo / pool, `blur ${blur}`).toBeLessThan(1.25)
    }
  })

  /**
   * The worst kink in a falling edge's profile: the largest change of slope from one texel to the
   * next, over the steepest slope. A level magnified from a coarse tile is straight across each of
   * its texels and turns at their edges — blocks; one blurred at full size turns a little at every
   * texel.
   */
  function blockiness(read: (x: number) => number): number {
    const slopes: number[] = []
    for (let x = 8; x < SIZE - 8; x += 1) slopes.push(read(x + 1) - read(x))
    const steepest = Math.max(...slopes.map(Math.abs))
    let kink = 0
    for (let i = 1; i < slopes.length; i++) kink = Math.max(kink, Math.abs(slopes[i] - slopes[i - 1]))
    return kink / steepest
  }

  /** What `generateMipmaps` drew: each level a 2 × 2 box of the last at half the size, read bilinearly. */
  function boxChainRow(level: number): (x: number) => number {
    let tile = step
    let size = SIZE
    for (let l = 0; l < level; l++) {
      const half = size / 2
      const next = new Float32Array(half * half)
      for (let y = 0; y < half; y++) {
        for (let x = 0; x < half; x++) {
          next[y * half + x] = (tile[2 * y * size + 2 * x] + tile[2 * y * size + 2 * x + 1] + tile[(2 * y + 1) * size + 2 * x] + tile[(2 * y + 1) * size + 2 * x + 1]) / 4
        }
      }
      tile = next
      size = half
    }
    const coarse = tile
    const n = size
    return (x) => {
      const t = (x / SIZE) * n - 0.5
      const x0 = Math.floor(t)
      const f = t - x0
      const at = (i: number) => coarse[(n / 2) * n + Math.min(n - 1, Math.max(0, i))]
      return at(x0) * (1 - f) + at(x0 + 1) * f
    }
  }

  it('turn smoothly at every level from 3 up, where a box chain went blocky', () => {
    for (const lod of [3, 3.5, 4, 4.5, 5, 6]) {
      expect(blockiness((x) => rowAt(levels, x + 0.37, lod)), `lod ${lod}`).toBeLessThan(0.45)
    }
    // The measure tells the two apart: generateMipmaps' levels 3 and 4 kink as much as they slope.
    for (const level of [3, 4]) expect(blockiness(boxChainRow(level)), `box level ${level}`).toBeGreaterThan(0.8)
  })
})

describe('a gobo racked through focus', () => {
  const data = buildGoboAtlasData()
  const sample = atlasSampler(data, SIZE)
  const spokes = goboLayerFor('spokes')!

  /** The spokes' contrast round a circle half a field radius out, at [lod]: the profile's RMS about its mean. */
  function contrast(lod: number): number {
    const values = Array.from({ length: 144 }, (_, i) => {
      const a = (i / 144) * Math.PI * 2
      return sample(spokes, 0.5 + 0.25 * Math.cos(a), 0.5 + 0.25 * Math.sin(a), lod)
    })
    const mean = values.reduce((s, v) => s + v, 0) / values.length
    return Math.sqrt(values.reduce((s, v) => s + (v - mean) ** 2, 0) / values.length)
  }

  it('loses contrast steadily, a quarter level at a time — no pulse as a level steps', () => {
    // Level 1's σ is a quarter of a texel, under what bilinear reading blurs by anyway: up to it the
    // pattern holds, and past it every quarter level takes some contrast away.
    let previous = Infinity
    for (let lod = 0; lod <= FOCUS_LOD_MAX + 1e-9; lod += 0.25) {
      const c = contrast(lod)
      if (lod <= 1) expect(c, `lod ${lod}`).toBeLessThanOrEqual(previous)
      else expect(c, `lod ${lod}`).toBeLessThan(previous)
      previous = c
    }
    expect(contrast(0)).toBeGreaterThan(0.4)
    expect(contrast(FOCUS_LOD_MAX)).toBeLessThan(contrast(0) / 20)
  })
})
