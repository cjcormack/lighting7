import { DataArrayTexture, LinearFilter, RedFormat } from 'three'
import { GOBO_PATTERNS, GOBO_SLOT_COUNT, PATTERN_GENERATORS, smoothstep } from './goboPatterns'

/**
 * The gobo patterns from `goboPatterns.ts`, baked as a layered R8 texture — each pattern at
 * [GOBO_BLUR_LEVELS] blurs, so a gobo racked through focus softens smoothly (stage-light plan
 * session 4).
 *
 * A `DataArrayTexture` rather than an atlas: a gobo is sampled across a whole floor pool at glancing
 * angles, and neighbouring tiles in an atlas bleed into each other under filtering unless you add
 * gutters and clamp by hand. Array layers can't bleed, at any blur.
 *
 * **The blurs are layers, not mip levels.** Each level is the full-size pattern under a
 * **Gaussian** whose σ doubles (plus one) a level ([goboLevelSigma]), so nothing is magnified and two
 * neighbouring levels are two nearby blurs; the sampler blends them by the fraction of the level
 * (`goboLayers.ts`), so the blur grows steadily with the focus error. Three cannot upload hand-made
 * mip levels of an array texture (it allocates them and fills level 0 alone), which is why the blend
 * is the shader's.
 *
 * At 17 patterns × 7 blurs × 128 × 128 × R8 this is ~1.9 MB. The generators are plain maths with no
 * `<canvas>`, so the build runs (and is tested) under jsdom.
 *
 * Pattern 0 is "open" — solid 255. The renderer skips sampling entirely for slot 0, so it exists only
 * to keep the layer index and the gobo slot the same number.
 */
export const GOBO_TILE_PX = 128

/** The blurs each pattern is baked at: level 0 sharp, then [goboLevelSigma] of each. */
export const GOBO_BLUR_LEVELS = 7

/**
 * The 10–90 % width of `smoothstep`, as a fraction of its span — the shape of `beamMask`'s focus
 * penumbra, a smoothstep as wide as the blur — and of a Gaussian, in σ.
 */
const SMOOTHSTEP_10_90 = 0.6084
const GAUSSIAN_10_90 = 2.5631

/**
 * A level's Gaussian σ, in texels of the 128 px tile: `k · (2^level − 1)`. `goboLod` reads level
 * `log2(1 + texelsPerRadius · blur)`, so a blur `b` field radii is level `L` where `2^L − 1` is `b`'s
 * width in texels — and with `k` the ratio of a smoothstep's 10–90 % width to a Gaussian's, the
 * gobo's edge at that level is as wide as the pool's edge at that blur (`goboAtlas.test.ts`).
 */
export function goboLevelSigma(level: number): number {
  return (SMOOTHSTEP_10_90 / GAUSSIAN_10_90) * (2 ** level - 1)
}

/** Where a pattern's [level] sits in the array texture: its slot's levels are consecutive. */
export function goboLayerIndex(slot: number, level: number): number {
  return slot * GOBO_BLUR_LEVELS + level
}

/** One pattern's sharp tile, 0..1, texel centres sampled; outside the disc dark. */
function patternTile(slot: number, size: number): Float32Array {
  const tile = new Float32Array(size * size)
  const generate = PATTERN_GENERATORS[GOBO_PATTERNS[slot]]
  for (let py = 0; py < size; py++) {
    // +0.5 samples texel centres, so the pattern is symmetric about the disc.
    const y = ((py + 0.5) / size) * 2 - 1
    for (let px = 0; px < size; px++) {
      const x = ((px + 0.5) / size) * 2 - 1
      const r = Math.hypot(x, y)
      const a = Math.atan2(y, x)
      // Everything outside the disc is dark for every pattern — the beam's own cone test already
      // clips there, this just keeps the edge soft.
      const disc = 1 - smoothstep(0.94, 1.0, r)
      tile[py * size + px] = slot === 0 ? 1 : r > 1 ? 0 : disc * generate(r, a)
    }
  }
  return tile
}

/** A 1-D blur of every row (`stride` 1) or column (`stride` size) by [kernel], the edge clamped as the sampler clamps. */
function convolve(src: Float32Array, dst: Float32Array, size: number, kernel: Float32Array, horizontal: boolean): void {
  const r = (kernel.length - 1) / 2
  for (let line = 0; line < size; line++) {
    for (let i = 0; i < size; i++) {
      let sum = 0
      for (let k = -r; k <= r; k++) {
        const j = Math.min(size - 1, Math.max(0, i + k))
        sum += kernel[k + r] * (horizontal ? src[line * size + j] : src[j * size + line])
      }
      if (horizontal) dst[line * size + i] = sum
      else dst[i * size + line] = sum
    }
  }
}

/** A running-sum box of half-width [r] along rows or columns, the edge clamped. */
function box(src: Float32Array, dst: Float32Array, size: number, r: number, horizontal: boolean): void {
  const at = (line: number, i: number) => src[horizontal ? line * size + i : i * size + line]
  const w = 2 * r + 1
  for (let line = 0; line < size; line++) {
    let sum = 0
    for (let k = -r; k <= r; k++) sum += at(line, Math.min(size - 1, Math.max(0, k)))
    for (let i = 0; i < size; i++) {
      dst[horizontal ? line * size + i : i * size + line] = sum / w
      sum += at(line, Math.min(size - 1, i + r + 1)) - at(line, Math.max(0, i - r))
    }
  }
}

/**
 * Three boxes whose widths make a Gaussian of [sigma] (Kutskir's fit): odd widths `wl` and `wl + 2`,
 * as many of each as brings their summed variance to σ².
 */
function boxesForGauss(sigma: number): number[] {
  const n = 3
  const ideal = Math.sqrt((12 * sigma * sigma) / n + 1)
  let wl = Math.floor(ideal)
  if (wl % 2 === 0) wl--
  const m = Math.round((12 * sigma * sigma - n * wl * wl - 4 * n * wl - 3 * n) / (-4 * wl - 4))
  return Array.from({ length: n }, (_, i) => ((i < m ? wl : wl + 2) - 1) / 2)
}

/** Past this σ the three boxes stand in for the kernel: they cost the same at any width. */
const BOX_SIGMA_MIN = 3

/**
 * [tile] (size × size, 0..1) under a Gaussian of [sigma] texels, the edge clamped. Small blurs are
 * convolved with the sampled Gaussian; wide ones with three running boxes, a Gaussian to within a
 * percent of its σ — so the whole chain builds in milliseconds.
 */
export function gaussianBlur(tile: Float32Array, size: number, sigma: number): Float32Array {
  const a = new Float32Array(tile)
  if (!(sigma > 0)) return a
  const b = new Float32Array(size * size)
  if (sigma >= BOX_SIGMA_MIN) {
    for (const r of boxesForGauss(sigma)) {
      box(a, b, size, r, true)
      box(b, a, size, r, false)
    }
    return a
  }
  const r = Math.max(1, Math.ceil(3 * sigma))
  const kernel = new Float32Array(2 * r + 1)
  let total = 0
  for (let k = -r; k <= r; k++) {
    kernel[k + r] = Math.exp(-(k * k) / (2 * sigma * sigma))
    total += kernel[k + r]
  }
  for (let k = 0; k < kernel.length; k++) kernel[k] /= total
  convolve(a, b, size, kernel, true)
  convolve(b, a, size, kernel, false)
  return a
}

/**
 * Layer-major R8 bytes for the whole array texture: each slot's [GOBO_BLUR_LEVELS] blurs in turn
 * ([goboLayerIndex]). Deterministic — no `Math.random`, so the profile harness and the tests stay
 * reproducible.
 *
 * The disc mask (soft fade at the beam rim, hard zero outside it) is applied before the blur rather
 * than in each generator, so every pattern gets the identical edge treatment.
 */
export function buildGoboAtlasData(slots = GOBO_SLOT_COUNT, size = GOBO_TILE_PX): Uint8Array {
  const area = size * size
  const data = new Uint8Array(slots * GOBO_BLUR_LEVELS * area)
  for (let slot = 0; slot < slots; slot++) {
    const tile = patternTile(slot, size)
    for (let level = 0; level < GOBO_BLUR_LEVELS; level++) {
      const blurred = gaussianBlur(tile, size, goboLevelSigma(level))
      const base = goboLayerIndex(slot, level) * area
      for (let i = 0; i < area; i++) data[base + i] = Math.max(0, Math.min(255, Math.round(blurred[i] * 255)))
    }
  }
  return data
}

let sharedGoboTexture: DataArrayTexture | null = null

/**
 * The shared atlas texture, built lazily once per page load. The data is deterministic and ~2M
 * texels to generate, so remounting the Stage view must not re-pay the build (or re-upload); three
 * re-uploads it automatically if the GL context is ever lost. Deliberately never disposed — it's
 * ~1.9 MB of GPU memory for the lifetime of the app.
 */
export function getGoboTexture(): DataArrayTexture {
  return (sharedGoboTexture ??= createGoboTexture())
}

/** Build a fresh GPU texture. Caller owns disposal; prefer [getGoboTexture]. */
export function createGoboTexture(): DataArrayTexture {
  const tex = new DataArrayTexture(buildGoboAtlasData(), GOBO_TILE_PX, GOBO_TILE_PX, GOBO_SLOT_COUNT * GOBO_BLUR_LEVELS)
  tex.format = RedFormat
  tex.magFilter = LinearFilter
  // Read at level 0 only: the blurs are layers, blended by the sampler.
  tex.minFilter = LinearFilter
  tex.generateMipmaps = false
  tex.needsUpdate = true
  return tex
}
