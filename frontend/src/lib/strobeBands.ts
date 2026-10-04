import type {
  SettingOption,
  SettingPropertyDescriptor,
  SliderPropertyDescriptor,
  StrobeBand,
  StrobeKind,
} from '../store/fixtures'

/**
 * What a strobe channel does to the light (fixture-optics plan D12), as a **level factor** both
 * colour dispatches multiply into the dimmer's — `FixtureAppearanceSource` (2D) and `FixtureModel`'s
 * `ColourSync` (3D) — so they cannot disagree, and the lens, the beam and the pool all follow it.
 *
 * The desk declares each band of a strobe channel (`strobeBands` on a slider, `strobeKind` on a
 * setting's options): **closed** is dark whatever the dimmer says (a MAC 250 at strobe 0), **open**
 * leaves the dimmer alone, and **strobe**, **random** and **pulse** move with time at the band's rate.
 * A value no band covers — or a channel from a desk that declares none — draws open, as it always
 * did.
 *
 * **The three-flash rule** (WCAG 2.3.1): the Stage view is on screens other people watch, so it never
 * draws more than three flashes in any second. A strobe or pulse at up to [FLASH_HZ_MAX] flashes at
 * its rate; a random band — whose flashes can bunch — at up to [RANDOM_HZ_MAX]; anything faster draws
 * a **shimmer**, lit at [SHIMMER_LEVEL] and rippling by less than WCAG's 10% flash threshold.
 *
 * Time is passed in as seconds, never read from a clock here (the `colourBands.ts` rule), so the
 * parity test and the profile harness stay reproducible: the 3D path hands in the scene clock, the
 * 2D path the shared band clock.
 */

/** The fastest a strobe or pulse is drawn flashing, in Hz: WCAG 2.3.1's three flashes a second. */
export const FLASH_HZ_MAX = 3

/**
 * The fastest a random band is drawn flashing, in Hz. A flash lands anywhere in its cycle, so two can
 * fall back to back; at 2 Hz any one second still overlaps at most three cycles, so at most three
 * flashes — the rule holds whatever the dice say.
 */
export const RANDOM_HZ_MAX = 2

/** How long one drawn flash lasts, in seconds — long enough to land in a few frames at 60 Hz. */
export const FLASH_S = 0.1

/** A shimmer's mean level: a strobe too fast to draw reads lit, not dark. Estimate (D15). */
export const SHIMMER_LEVEL = 0.6

/**
 * A shimmer's ripple, as a fraction of its level: 8%, under WCAG's 10% change in luminance that makes
 * a flash, so it may move at the strobe's own rate.
 */
export const SHIMMER_DEPTH = 0.08

/** The fastest a shimmer ripples, in Hz — faster only aliases against the frame rate. */
const SHIMMER_HZ_MAX = 10

type StrobeProperty = SliderPropertyDescriptor | SettingPropertyDescriptor

const TAU = Math.PI * 2

/**
 * The band [level] sits in on [prop]: a slider's declared band, or a setting's option as a band
 * running from its level to the next option's. `undefined` where nothing is declared.
 */
export function strobeBandAt(prop: StrobeProperty, level: number): StrobeBand | undefined {
  if (prop.type === 'slider') {
    const bands = prop.strobeBands
    if (!bands) return undefined
    for (const b of bands) if (level >= b.from && level <= b.to) return b
    return undefined
  }
  return settingBand(prop.options, level)
}

// Per options array: a setting's options are stable for the life of the fixture list, and the frame
// loop asks every frame while a band flashes, so each option's band is built once.
const SETTING_BANDS = new WeakMap<readonly SettingOption[], readonly (StrobeBand | undefined)[]>()

function settingBand(options: readonly SettingOption[], level: number): StrobeBand | undefined {
  let bands = SETTING_BANDS.get(options)
  if (!bands) {
    bands = options.map((o, i) =>
      o.strobeKind == null
        ? undefined
        : {
            from: o.level,
            to: i + 1 < options.length ? options[i + 1].level - 1 : 255,
            kind: o.strobeKind,
            hzMin: o.hzMin,
            hzMax: o.hzMax,
            inverted: o.strobeInverted,
          },
    )
    SETTING_BANDS.set(options, bands)
  }
  // The last option whose level is at or below [level], else the first — `resolveSettingOption`'s
  // rule, as `colourBands.ts` restates it. Options are level-ascending, as the desk sorts them.
  for (let i = options.length - 1; i >= 0; i--) if (level >= options[i].level) return bands[i]
  return bands[0]
}

/** True for the kinds that change the light with time. */
export function isFlashingKind(kind: StrobeKind | undefined): boolean {
  return kind === 'STROBE' || kind === 'RANDOM' || kind === 'PULSE'
}

/**
 * A flashing band's rate at [level], in Hz: `hzMin` at its first value to `hzMax` at its last,
 * linear, the other way round where `inverted`. 0 where it declares none.
 */
export function strobeRateAt(band: StrobeBand, level: number): number {
  const lo = band.hzMin
  const hi = band.hzMax
  if (lo == null || hi == null) return 0
  const span = band.to - band.from
  let f = span > 0 ? (Math.max(band.from, Math.min(band.to, level)) - band.from) / span : 0
  if (band.inverted) f = 1 - f
  return lo + (hi - lo) * f
}

/** 0..1, deterministic in its two integer arguments: where a random band's flash falls in a cycle. */
function hash01(k: number, seed: number): number {
  let h = (Math.imul(k | 0, 0x9e3779b1) ^ Math.imul((seed | 0) + 0x632be5ab, 0x85ebca6b)) >>> 0
  h = Math.imul(h ^ (h >>> 16), 0x7feb352d) >>> 0
  h = Math.imul(h ^ (h >>> 15), 0x846ca68b) >>> 0
  return ((h ^ (h >>> 16)) >>> 0) / 4294967296
}

function shimmer(rateHz: number, timeS: number): number {
  const ripple = 0.5 + 0.5 * Math.sin(TAU * Math.min(rateHz, SHIMMER_HZ_MAX) * timeS)
  return SHIMMER_LEVEL * (1 - SHIMMER_DEPTH * ripple)
}

/**
 * The level factor, 0..1, that [band] at [level] gives the light at [timeS] seconds. [seed] keeps two
 * random strobes from flashing in step (the channel's address does); the rest ignore it.
 */
export function strobeLevelAt(band: StrobeBand | undefined, level: number, timeS: number, seed = 0): number {
  if (!band) return 1
  switch (band.kind) {
    case 'CLOSED':
      return 0
    case 'OPEN':
      return 1
  }
  const rate = strobeRateAt(band, level)
  if (!(rate > 0)) return 1
  const t = Math.max(0, Number.isFinite(timeS) ? timeS : 0)
  switch (band.kind) {
    case 'STROBE': {
      if (rate > FLASH_HZ_MAX) return shimmer(rate, t)
      // One flash at the start of each cycle.
      const phase = (t * rate) % 1
      return phase * (1 / rate) < FLASH_S ? 1 : 0
    }
    case 'RANDOM': {
      if (rate > RANDOM_HZ_MAX) return shimmer(rate, t)
      // One flash per cycle, somewhere in it.
      const period = 1 / rate
      const cycle = Math.floor(t / period)
      const at = hash01(cycle, seed) * Math.max(0, period - FLASH_S)
      const into = t - cycle * period
      return into >= at && into < at + FLASH_S ? 1 : 0
    }
    case 'PULSE': {
      if (rate > FLASH_HZ_MAX) return shimmer(rate, t)
      // A smooth swell and fade, one a cycle: a ramp, not a snap.
      return 0.5 - 0.5 * Math.cos(TAU * ((t * rate) % 1))
    }
  }
  return 1
}

/** A strobe channel's seed: its address, so each fixture's random strobe keeps its own dice. */
export function strobeSeed(prop: StrobeProperty): number {
  return prop.channel.universe * 512 + prop.channel.channelNo
}

/**
 * The light's strobe factor across every strobe channel the fixture has (`findStrobeProperties`):
 * the product of each one's [strobeLevelAt], [read] giving each channel's DMX value. 1 with none.
 */
export function strobeFactor(
  props: readonly StrobeProperty[],
  read: (prop: StrobeProperty) => number,
  timeS: number,
): number {
  let factor = 1
  for (const p of props) {
    const level = read(p)
    factor *= strobeLevelAt(strobeBandAt(p, level), level, timeS, strobeSeed(p))
  }
  return factor
}

/**
 * Whether [strobeFactor] changes with time, so the picture changes every frame: some channel sits on
 * a band that moves with time, and none is closed — a closed one holds the product at 0.
 */
export function strobeAnimates(
  props: readonly StrobeProperty[],
  read: (prop: StrobeProperty) => number,
): boolean {
  let moving = false
  for (const p of props) {
    const level = read(p)
    const band = strobeBandAt(p, level)
    if (!band) continue
    if (band.kind === 'CLOSED') return false
    if (isFlashingKind(band.kind) && strobeRateAt(band, level) > 0) moving = true
  }
  return moving
}
