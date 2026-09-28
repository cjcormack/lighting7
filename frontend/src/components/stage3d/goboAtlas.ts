import { DataArrayTexture, LinearFilter, LinearMipmapLinearFilter, RedFormat } from 'three'
import { GOBO_PATTERNS, GOBO_SLOT_COUNT, PATTERN_GENERATORS, smoothstep } from './goboPatterns'

/**
 * The gobo patterns from `goboPatterns.ts`, baked as a layered R8 texture.
 *
 * A `DataArrayTexture` rather than an atlas: a gobo is sampled across a whole
 * floor pool at glancing angles, and neighbouring tiles in an atlas bleed into
 * each other under minification unless you add gutters and clamp by hand. Array
 * layers can't bleed, and the lookup is one `texture(uGobo, vec3(uv, slot))`.
 *
 * At 17 x 128 x 128 x R8 this is ~278 KB. The generators are plain maths with
 * no `<canvas>`, so the build runs (and is tested) under jsdom.
 *
 * Layer 0 is "open" — solid 255. The renderer skips sampling entirely for slot
 * 0, so it exists only to keep the layer index and the gobo slot the same
 * number.
 */
export const GOBO_TILE_PX = 128

/**
 * Layer-major R8 bytes for the whole array texture. Deterministic — no
 * `Math.random`, so the profile harness and the tests stay reproducible.
 *
 * The disc mask (soft fade at the beam rim, hard zero outside it) is applied
 * here rather than in each generator, so every pattern gets the identical
 * edge treatment.
 */
export function buildGoboAtlasData(
  slots = GOBO_SLOT_COUNT,
  size = GOBO_TILE_PX,
): Uint8Array {
  const data = new Uint8Array(slots * size * size)
  for (let layer = 0; layer < slots; layer++) {
    const generate = PATTERN_GENERATORS[GOBO_PATTERNS[layer]]
    const base = layer * size * size
    for (let py = 0; py < size; py++) {
      // +0.5 samples texel centres, so the pattern is symmetric about the disc.
      const y = ((py + 0.5) / size) * 2 - 1
      for (let px = 0; px < size; px++) {
        const x = ((px + 0.5) / size) * 2 - 1
        const r = Math.hypot(x, y)
        const a = Math.atan2(y, x)
        // Everything outside the disc is dark for every pattern — the beam's
        // own cone test already clips there, this just keeps the edge soft.
        const disc = 1 - smoothstep(0.94, 1.0, r)
        const v = layer === 0 ? 1 : r > 1 ? 0 : disc * generate(r, a)
        data[base + py * size + px] = Math.max(0, Math.min(255, Math.round(v * 255)))
      }
    }
  }
  return data
}

let sharedGoboTexture: DataArrayTexture | null = null

/**
 * The shared atlas texture, built lazily once per page load. The data is
 * deterministic and ~278k texels to generate, so remounting the Stage view
 * must not re-pay the build (or re-upload); three re-uploads it automatically
 * if the GL context is ever lost. Deliberately never disposed — it's ~278 KB
 * of GPU memory for the lifetime of the app.
 */
export function getGoboTexture(): DataArrayTexture {
  return (sharedGoboTexture ??= createGoboTexture())
}

/** Build a fresh GPU texture. Caller owns disposal; prefer [getGoboTexture]. */
export function createGoboTexture(): DataArrayTexture {
  const tex = new DataArrayTexture(
    buildGoboAtlasData(),
    GOBO_TILE_PX,
    GOBO_TILE_PX,
    GOBO_SLOT_COUNT,
  )
  tex.format = RedFormat
  tex.magFilter = LinearFilter
  tex.minFilter = LinearMipmapLinearFilter
  tex.generateMipmaps = true
  tex.needsUpdate = true
  return tex
}
