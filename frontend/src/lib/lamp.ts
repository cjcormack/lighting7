import type { FixturePatch } from '../api/patchApi'
import type { FixtureKind } from '../store/fixtures'
import { effectiveLantern, type LampKind, type Lantern, type LanternIndex } from './lanterns'

/**
 * A lantern's **lamp**: its power, its colour temperature at full and its kind, as the Stage view
 * draws them. The lantern library gives each lantern its own (`watts`, `kelvin`, `lamp`); a patch
 * may name its own power and colour temperature (`lampWatts`, `lampKelvin`), one lamp for every
 * lantern it drives. Only a type hung with a lantern (a generic dimmer) has one — every other
 * fixture's light is its own colour channels. Drawn, never output: nothing here reaches the rig.
 *
 * Three things follow from the lamp, each in one function so the 2D and 3D colour dispatches agree:
 * - its **colour** — the black body at its colour temperature ([kelvinToLinearRgb]), shifted to the
 *   view's adapted white ([ADAPTED_WHITE_K]);
 * - **amber drift** — a tungsten filament cools as it is dimmed, so its colour temperature falls
 *   with its level ([driftedKelvin]); an LED's holds;
 * - its **output** — how much light it puts on the stage against a 575 W tungsten profile's
 *   ([lampOutput]), which the 3D view multiplies into the beam and the pool.
 *
 * A gel **filters** the lamp ([lampHex]): the lamp's colour times the gel's transmission, so a CTB
 * on a 3200 K lamp reads near daylight and a CTO goes deep amber.
 *
 * Pure, so the numbers are pinned by a node test.
 */

export interface Lamp {
  watts: number
  kelvin: number
  kind: LampKind
}

/** The lamp a lantern-hung patch with no lantern to read is drawn with: a theatre tungsten lamp. */
export const DEFAULT_LAMP: Lamp = Object.freeze({ watts: 575, kelvin: 3200, kind: 'TUNGSTEN' })

/**
 * Lumens out of the lantern a watt buys, by kind — what reaches the stage, not what the lamp makes.
 * Tungsten: an HPL 575 makes about 16,500 lm and a Source Four passes about 40% of it, some 6,500 lm.
 * LED: a downlight's rated lumens are already the fitting's, about 80 lm/W. Estimates.
 */
export const LUMENS_PER_WATT: Readonly<Record<LampKind, number>> = { TUNGSTEN: 11, LED: 80 }

/** The output every lamp is measured against: the 575 W tungsten profile the view was tuned with. */
const REFERENCE_LUMENS = DEFAULT_LAMP.watts * LUMENS_PER_WATT.TUNGSTEN

/**
 * The white the view is adapted to. The display's own white is D65 (6500 K), against which a 3200 K
 * lamp is a deep peach — what a camera balanced for daylight sees, not what an eye in the room does.
 * 5000 K is a partial adaptation: tungsten reads a warm cream, a daylight lamp a pale blue, and an
 * LED's RGB white (drawn unadapted) sits between them. Estimate, tuned by eye.
 */
export const ADAPTED_WHITE_K = 5000

/**
 * How far a tungsten lamp's colour temperature falls as it is dimmed: T = T_full × level^k. A
 * filament's colour temperature goes as about V^0.42 and its light as about V^3.4 (V the lamp
 * voltage), so for a level that is the light's fraction k = 0.42 / 3.4. 3200 K is about 2940 K at
 * half and 2400 K at a tenth.
 */
export const AMBER_DRIFT_EXPONENT = 0.42 / 3.4

/** Below this a lamp is dark; drift is not followed down into the glow of a cold filament. */
const DRIFT_FLOOR_LEVEL = 0.002

/**
 * The lamp a patch is drawn with — its own power and colour temperature over its lantern's — or
 * null for a fixture that is not hung with a lantern. [lantern] is the unit's effective lantern
 * (`effectiveLantern`); a lantern-hung unit with none to read takes [DEFAULT_LAMP]'s.
 */
export function lampFor(
  patch: Pick<FixturePatch, 'lampWatts' | 'lampKelvin'>,
  lantern: Lantern | null,
  acceptsLantern: boolean,
): Lamp | null {
  if (!acceptsLantern) return null
  return {
    watts: positive(patch.lampWatts) ?? positive(lantern?.watts) ?? DEFAULT_LAMP.watts,
    kelvin: positive(patch.lampKelvin) ?? positive(lantern?.kelvin) ?? DEFAULT_LAMP.kelvin,
    kind: lantern?.lamp ?? DEFAULT_LAMP.kind,
  }
}

/**
 * The lamp a unit is drawn with, from its patch (or a placement laid over it — `patchAtPlacement`
 * keeps the patch's lamp and gives the placement's lantern), its type and the library. [ownKind] is
 * the patch's kind before a lantern derives one (`resolveFixtureKind`), which picks the default
 * lantern for a unit that names none — as `bodyInputFor` picks it.
 */
export function unitLamp(
  patch: Pick<FixturePatch, 'lampWatts' | 'lampKelvin' | 'lanternType'>,
  acceptsLantern: boolean,
  ownKind: FixtureKind,
  lanterns: LanternIndex,
): Lamp | null {
  if (!acceptsLantern) return null
  return lampFor(patch, effectiveLantern(lanterns, patch.lanternType, ownKind), true)
}

/** The lamp's light against the reference profile's: 1 for a 575 W tungsten lamp. */
export function lampOutput(lamp: Lamp): number {
  return (lamp.watts * LUMENS_PER_WATT[lamp.kind]) / REFERENCE_LUMENS
}

/** The colour temperature the lamp burns at [level] (0..1): a tungsten lamp's falls, an LED's holds. */
export function driftedKelvin(lamp: Lamp, level: number): number {
  if (lamp.kind !== 'TUNGSTEN') return lamp.kelvin
  const l = Math.min(1, Math.max(DRIFT_FLOOR_LEVEL, level))
  return lamp.kelvin * Math.pow(l, AMBER_DRIFT_EXPONENT)
}

/**
 * The black body at [kelvin] (1000–25000 K) in **linear** sRGB, its brightest channel 1 — the
 * Planckian locus by Kim et al.'s cubic fit to CIE 1931 chromaticity, then XYZ at Y = 1 to linear
 * sRGB, a channel below zero (out of gamut) clamped. Unadapted: 6500 K is about white.
 */
export function kelvinToLinearRgb(kelvin: number): [number, number, number] {
  const t = Math.min(25000, Math.max(1000, kelvin))
  const x =
    t <= 4000
      ? -0.2661239e9 / t ** 3 - 0.2343589e6 / t ** 2 + 0.8776956e3 / t + 0.17991
      : -3.0258469e9 / t ** 3 + 2.1070379e6 / t ** 2 + 0.2226347e3 / t + 0.24039
  const y =
    t <= 2222
      ? -1.1063814 * x ** 3 - 1.3481102 * x ** 2 + 2.18555832 * x - 0.20219683
      : t <= 4000
        ? -0.9549476 * x ** 3 - 1.37418593 * x ** 2 + 2.09137015 * x - 0.16748867
        : 3.081758 * x ** 3 - 5.8733867 * x ** 2 + 3.75112997 * x - 0.37001483
  const X = x / y
  const Z = (1 - x - y) / y
  const r = 3.2404542 * X - 1.5371385 - 0.4985314 * Z
  const g = -0.969266 * X + 1.8760108 + 0.041556 * Z
  const b = 0.0556434 * X - 0.2040259 + 1.0572252 * Z
  return normalised([Math.max(0, r), Math.max(0, g), Math.max(0, b)])
}

const ADAPTED_WHITE = kelvinToLinearRgb(ADAPTED_WHITE_K)

/** [kelvin] as the view draws it — the black body against [ADAPTED_WHITE_K] — linear, brightest 1. */
export function lampLinearRgb(kelvin: number): [number, number, number] {
  const c = kelvinToLinearRgb(kelvin)
  return normalised([c[0] / ADAPTED_WHITE[0], c[1] / ADAPTED_WHITE[1], c[2] / ADAPTED_WHITE[2]])
}

/**
 * The beam's colour as a full-brightness `#rrggbb`: the lamp at [level] (its amber drift), through
 * [gelHex] when one is fitted — the lamp's colour times the gel's transmission, both linear. A gel
 * darkens as it does in the frame; the lamp alone has its brightest channel at full.
 */
export function lampHex(lamp: Lamp, level: number, gelHex?: string | null): string {
  const c = lampLinearRgb(driftedKelvin(lamp, level))
  const gel = gelHex ? hexToLinear(gelHex) : null
  if (gel) {
    c[0] *= gel[0]
    c[1] *= gel[1]
    c[2] *= gel[2]
  }
  return linearToHex(c)
}

function normalised(c: [number, number, number]): [number, number, number] {
  const m = Math.max(c[0], c[1], c[2])
  return m > 0 ? [c[0] / m, c[1] / m, c[2] / m] : [1, 1, 1]
}

function positive(v: number | null | undefined): number | null {
  return v != null && Number.isFinite(v) && v > 0 ? v : null
}

function hexToLinear(hex: string): [number, number, number] | null {
  const m = /^#?([0-9a-f]{6})$/i.exec(hex.trim())
  if (!m) return null
  const n = parseInt(m[1], 16)
  return [decode((n >> 16) & 255), decode((n >> 8) & 255), decode(n & 255)]
}

function decode(byte: number): number {
  const c = byte / 255
  return c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

function encode(c: number): number {
  const v = Math.min(1, Math.max(0, c))
  const s = v <= 0.0031308 ? 12.92 * v : 1.055 * v ** (1 / 2.4) - 0.055
  return Math.round(s * 255)
}

function linearToHex(c: readonly number[]): string {
  return '#' + c.map((v) => encode(v).toString(16).padStart(2, '0')).join('')
}
