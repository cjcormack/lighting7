import { lampLinearRgb } from './lamp'

/**
 * **A warm/cold white pair as a colour** (venue-visit plan D8) — the client half of the desk's
 * `fixture/property/WhiteMix.kt`, so the warmth control and the colour it shows agree with what
 * the desk writes. `src/test/resources/fixture/whiteMix.fixture.json` pins the two to one answer.
 *
 * Each white is the black body at its declared temperature against the view's adapted white
 * ([lampLinearRgb], as a lamp is drawn), so a white colour lands between the two. A colour lands
 * as the mix nearest it in chromaticity, its brighter white at the colour's brightest channel; two
 * levels read back as the colour they make. Colours are drive levels (0–255), as on an RGB head,
 * so there is no gamma.
 */

export interface WhiteMix {
  warmKelvin: number
  coldKelvin: number
}

export interface WhiteLevels {
  warm: number
  cold: number
}

export type Rgb = [number, number, number]

const MIRED = 1_000_000

/** Each white as the view draws it, by temperature — the fit is pure, and every cell asks per render. */
const whiteRgb = new Map<number, readonly number[]>()
function white(kelvin: number): readonly number[] {
  let rgb = whiteRgb.get(kelvin)
  if (rgb == null) {
    rgb = lampLinearRgb(kelvin)
    whiteRgb.set(kelvin, rgb)
  }
  return rgb
}

/** The nearest mix to [rgb], its brighter white at [rgb]'s brightest channel. */
export function whiteLevelsFor(mix: WhiteMix, rgb: Rgb): WhiteLevels {
  const peak = Math.max(rgb[0], rgb[1], rgb[2]) / 255
  if (peak === 0) return { warm: 0, cold: 0 }
  return levelsForShare(mix, coldShareOf(mix, [rgb[0] / 255, rgb[1] / 255, rgb[2] / 255]), peak)
}

/** The colour [warm] and [cold] (0–255) make. */
export function whiteMixColour(mix: WhiteMix, warm: number, cold: number): Rgb {
  const a = warm / 255
  const c = cold / 255
  const peak = Math.max(a, c)
  if (peak === 0) return [0, 0, 0]
  const w = white(mix.warmKelvin)
  const k = white(mix.coldKelvin)
  const mixed = [0, 1, 2].map((i) => a * w[i] + c * k[i])
  const brightest = Math.max(...mixed)
  return mixed.map((v) => byte((v / brightest) * peak)) as Rgb
}

/** The temperature a mix is labelled with — mired-weighted between the whites — or null when both are off. */
export function whiteMixKelvin(mix: WhiteMix, warm: number, cold: number): number | null {
  if (warm === 0 && cold === 0) return null
  const share = coldShareOfLevels(mix, warm, cold)
  const mired = (1 - share) * (MIRED / mix.warmKelvin) + share * (MIRED / mix.coldKelvin)
  return Math.round(MIRED / mired)
}

/** The levels of the mix labelled [kelvin] (clamped to the pair), its brighter white at [level] (0–255). */
export function whiteLevelsForKelvin(mix: WhiteMix, kelvin: number, level: number): WhiteLevels {
  if (level <= 0) return { warm: 0, cold: 0 }
  const warmMired = MIRED / mix.warmKelvin
  const coldMired = MIRED / mix.coldKelvin
  const share = Math.min(1, Math.max(0, (warmMired - MIRED / kelvin) / (warmMired - coldMired)))
  return levelsForShare(mix, share, Math.min(255, level) / 255)
}

function coldShareOf(mix: WhiteMix, target: Rgb): number {
  const t = chromaticity(target)
  const w = chromaticity(white(mix.warmKelvin))
  const c = chromaticity(white(mix.coldKelvin))
  const dx = c[0] - w[0]
  const dy = c[1] - w[1]
  const along = ((t[0] - w[0]) * dx + (t[1] - w[1]) * dy) / (dx * dx + dy * dy)
  return Math.min(1, Math.max(0, along))
}

function coldShareOfLevels(mix: WhiteMix, warm: number, cold: number): number {
  const warmSum = sum(white(mix.warmKelvin))
  const coldSum = sum(white(mix.coldKelvin))
  return (cold * coldSum) / (warm * warmSum + cold * coldSum)
}

function levelsForShare(mix: WhiteMix, share: number, peak: number): WhiteLevels {
  const a = (1 - share) / sum(white(mix.warmKelvin))
  const c = share / sum(white(mix.coldKelvin))
  const scale = peak / Math.max(a, c)
  return { warm: byte(a * scale), cold: byte(c * scale) }
}

function chromaticity(v: readonly number[]): [number, number] {
  const s = sum(v)
  return [v[0] / s, v[1] / s]
}

function sum(v: readonly number[]): number {
  return v[0] + v[1] + v[2]
}

function byte(x: number): number {
  return Math.min(255, Math.max(0, Math.round(x * 255)))
}
