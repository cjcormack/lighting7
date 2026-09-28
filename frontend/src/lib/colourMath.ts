/**
 * Pure colour maths for fixture preview swatches.
 *
 * The preview colour is split into two independent parts:
 *  - **hue**   — the colour direction, normalised so its brightest emitter = 255
 *  - **level** — how bright it is (0..1): `dimmerFactor × colourMagnitude`
 *
 * Displays (sRGB) and the eye (~cube-root) are both non-linear, so a *linear*
 * level maps a dim fixture to near-black on screen even though the real fixture
 * is clearly coloured. `perceptualBrightness` reshapes the level with a gamma
 * curve so low levels stay legible, matching what the eye sees on stage.
 */

/** Single tunable knob for the low-end boost. Higher = brighter dim colours. */
export const PERCEPTUAL_GAMMA = 2.2

/**
 * Brightness floor for indicator swatches (fixture card, head dots, compact,
 * gel). Keeps a set colour faintly visible when the level is driven to zero so
 * the swatch — which is also the colour-picker trigger — never fully vanishes.
 */
export const SWATCH_FLOOR = 0.15

/**
 * Map a linear 0..1 level to a perceptual 0..1 display brightness via a gamma
 * curve. Anchored at both ends (0 → floor, 1 → 1). `floor` keeps a set colour
 * faintly visible at zero level (e.g. so a picker-trigger swatch never vanishes).
 */
export function perceptualBrightness(level: number, floor = 0): number {
  const n = clamp01(level)
  if (n <= 0) return floor
  return floor + (1 - floor) * Math.pow(n, 1 / PERCEPTUAL_GAMMA)
}

/**
 * The "virtual dimmer" of a colour: the brightest emitter as a 0..1 fraction.
 * A dimmerless fixture set to `r:20` reads as level 20/255, exactly like a
 * real dimmer at 20. White/amber/UV all count as emitters.
 */
export function colourMagnitude(
  r: number,
  g: number,
  b: number,
  w = 0,
  a = 0,
  uv = 0,
): number {
  return clamp01(Math.max(r, g, b, w, a, uv) / 255)
}

/**
 * Fold white/amber/UV into RGB. Returns rounded 0..255 channels. Exported and
 * reused wherever a folded hue is needed (CSS strings, baked brightness, and
 * the group-beam aggregate) so the emitter maths lives in exactly one place.
 */
export function foldChannels(
  r: number,
  g: number,
  b: number,
  w: number | undefined,
  a: number | undefined,
  uv: number | undefined,
): { r: number; g: number; b: number } {
  let combinedR = r
  let combinedG = g
  let combinedB = b
  if (w !== undefined && w > 0) {
    const whiteFactor = w / 255
    combinedR = Math.min(255, combinedR + whiteFactor * (255 - combinedR))
    combinedG = Math.min(255, combinedG + whiteFactor * (255 - combinedG))
    combinedB = Math.min(255, combinedB + whiteFactor * (255 - combinedB))
  }
  if (a !== undefined && a > 0) {
    const amberFactor = a / 255
    combinedR = Math.min(255, combinedR + amberFactor * (255 - combinedR * 0.3))
    combinedG = Math.min(255, combinedG + amberFactor * (191 - combinedG * 0.5))
  }
  if (uv !== undefined && uv > 0) {
    const uvFactor = uv / 255
    combinedR = Math.min(255, combinedR + uvFactor * (139 - combinedR * 0.5))
    combinedG = Math.min(255, combinedG * (1 - uvFactor * 0.3))
    combinedB = Math.min(255, combinedB + uvFactor * (255 - combinedB * 0.3))
  }
  return {
    r: Math.round(combinedR),
    g: Math.round(combinedG),
    b: Math.round(combinedB),
  }
}

/**
 * Fold white/amber/UV into RGB and return an `rgb(...)` string. This is the
 * raw hue-at-its-own-level colour (no perceptual adjustment) — used to seed the
 * colour picker, so its output must stay stable.
 */
export function computeCombinedCss(
  r: number,
  g: number,
  b: number,
  w: number | undefined,
  a: number | undefined,
  uv: number | undefined,
): string {
  const c = foldChannels(r, g, b, w, a, uv)
  return `rgb(${c.r}, ${c.g}, ${c.b})`
}

/**
 * The pure hue at full brightness as rounded 0..255 channels: the colour is
 * folded *first* (at its own level), then the folded RGB is scaled so its
 * brightest channel hits 255. Folding first matters — folding isn't
 * scale-invariant, so normalising the raw channels first would distort mixes
 * (e.g. red=100 + white=100 would wash to neutral white instead of warm red).
 * Removes the magnitude so brightness can be re-applied perceptually. Returns
 * `{0,0,0}` for no colour.
 */
export function computeNormalizedHue(
  r: number,
  g: number,
  b: number,
  w?: number,
  a?: number,
  uv?: number,
): { r: number; g: number; b: number } {
  const folded = foldChannels(r, g, b, w, a, uv)
  const max = Math.max(folded.r, folded.g, folded.b)
  if (max <= 0) return { r: 0, g: 0, b: 0 }
  const s = 255 / max
  return {
    r: Math.round(folded.r * s),
    g: Math.round(folded.g * s),
    b: Math.round(folded.b * s),
  }
}

/** `computeNormalizedHue` as an `rgb(...)` string. */
export function computeNormalizedHueCss(
  r: number,
  g: number,
  b: number,
  w?: number,
  a?: number,
  uv?: number,
): string {
  const c = computeNormalizedHue(r, g, b, w, a, uv)
  return `rgb(${c.r}, ${c.g}, ${c.b})`
}

/**
 * The pure hue rendered at a given display brightness (0..1), baked into a
 * single `rgb(...)` string. Equivalent to `computeNormalizedHueCss` multiplied
 * by `brightness`; use where a CSS `filter: brightness()` isn't convenient.
 */
export function computeAppearanceCss(
  r: number,
  g: number,
  b: number,
  w: number | undefined,
  a: number | undefined,
  uv: number | undefined,
  brightness: number,
): string {
  const hue = computeNormalizedHue(r, g, b, w, a, uv)
  const bf = clamp01(brightness)
  return `rgb(${Math.round(hue.r * bf)}, ${Math.round(hue.g * bf)}, ${Math.round(hue.b * bf)})`
}

/**
 * Read the channels back out of either colour-string form this module and the fixture
 * descriptors produce: `#rgb` / `#rrggbb` (gel and setting-preview colours) or `rgb(r, g, b)`
 * (everything from [computeNormalizedHueCss]). Null for anything else.
 *
 * Needed because a `FixtureAppearance.color` can be either form, so any surface that has to
 * *modify* the colour — dim it, give it an alpha — has to parse it first.
 */
export function parseCssRgb(color: string): { r: number; g: number; b: number } | null {
  if (color.startsWith('#')) {
    let hex = color.slice(1)
    if (hex.length === 3) hex = hex[0] + hex[0] + hex[1] + hex[1] + hex[2] + hex[2]
    if (!/^[0-9a-f]{6}$/i.test(hex)) return null
    const n = parseInt(hex, 16)
    return { r: (n >> 16) & 255, g: (n >> 8) & 255, b: n & 255 }
  }
  const m = /rgb\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*\)/.exec(color)
  return m ? { r: Number(m[1]), g: Number(m[2]), b: Number(m[3]) } : null
}

/**
 * Scale a colour string to `brightness` (0..1), returning `rgb(...)`.
 *
 * The string-input equivalent of [computeAppearanceCss], for callers holding a colour they did
 * not compute from channels — a gel hex, a wheel's preview colour, or an already-normalised hue.
 * Baking the brightness into the colour rather than applying it as opacity keeps it independent
 * of whatever opacity the surface already uses for selection or filter dimming.
 *
 * Unparseable input comes back unchanged, so a colour form this doesn't know still renders.
 */
export function dimCssColour(color: string, brightness: number): string {
  const rgb = parseCssRgb(color)
  if (!rgb) return color
  const bf = clamp01(brightness)
  return `rgb(${Math.round(rgb.r * bf)}, ${Math.round(rgb.g * bf)}, ${Math.round(rgb.b * bf)})`
}

function clamp01(n: number): number {
  return Math.max(0, Math.min(1, n))
}
