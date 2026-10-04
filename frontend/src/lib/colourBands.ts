import { createContext, useCallback, useContext, useSyncExternalStore } from 'react'
import type { SettingOption } from '../store/fixtures'
import { OPEN_WHITE } from './fittedMedia'

/**
 * What a colour setting's current band puts in the beam (fixture-optics plan D8), shared by both
 * colour dispatches — `FixtureAppearanceSource` (2D) and `FixtureModel`'s `ColourSync` (3D) — so
 * they cannot disagree.
 *
 * A band is one of three things, as the desk declares it:
 *
 * - a **colour** — its `colourPreview`;
 * - a band with **no single colour** (`noColour`: a scroll, a random or rainbow program, an auto
 *   change) — which **animates through the wheel's own previews**, so a scrolling wheel visibly
 *   changes colour rather than drawing black. The desk cannot know which colour the real wheel
 *   shows at any instant; cycling its own colours is honest about what the fixture is doing;
 * - neither — an older desk, or a fitted slot nobody annotated — which reads as nothing at all:
 *   `undefined`, which a colour **source** draws open white (never black for want of data) and a
 *   **filter** passes the beam through unchanged.
 *
 * Time is passed in as seconds, never read from a clock here, so the parity test and the profile
 * harness stay reproducible: the 3D path hands in its scene clock, the 2D path a shared ticking
 * clock ([useColourBandTime]).
 */

/** Seconds each colour of an animated band lasts, its move to the next included. */
export const BAND_STEP_S = 1.2

/** The fraction of each step the wheel spends moving to the next colour; it holds for the rest. */
const BAND_MOVE = 0.35

/** True when [option] is a band with no single colour: it animates, and its picture moves with time. */
export function isAnimatedBand(option: SettingOption | undefined): boolean {
  return option != null && option.colourPreview == null && option.noColour === true
}

/**
 * The option whose band holds [level]: the last whose start is at or below it, else the first —
 * `resolveSettingOption`'s rule (`hooks/usePropertyValues.ts`), restated here so this pure module
 * reaches no hook module. Options are level-ascending, as the desk sorts them.
 */
function bandOption(options: readonly SettingOption[], level: number): SettingOption | undefined {
  for (let i = options.length - 1; i >= 0; i--) if (level >= options[i].level) return options[i]
  return options[0]
}

/** Whether the band [level] sits in on [options] animates — [isAnimatedBand] by level. */
export function isAnimatedAt(options: readonly SettingOption[], level: number): boolean {
  return isAnimatedBand(bandOption(options, level))
}

const HEX = /^#([0-9a-f]{6})$/i

function parse(hex: string): [number, number, number] | null {
  const m = HEX.exec(hex.trim())
  if (!m) return null
  return [parseInt(m[1].slice(0, 2), 16), parseInt(m[1].slice(2, 4), 16), parseInt(m[1].slice(4, 6), 16)]
}

function toHex(r: number, g: number, b: number): string {
  return '#' + [r, g, b].map((v) => Math.round(Math.max(0, Math.min(255, v))).toString(16).padStart(2, '0')).join('')
}

/** True for a pure-black preview: a blackout band (the Slender bar's), not a colour. */
export function isBlackout(hex: string | undefined): boolean {
  const c = hex ? parse(hex) : null
  return c != null && c[0] === 0 && c[1] === 0 && c[2] === 0
}

// Per options array: the frame loop asks every frame while a band animates, and a descriptor's
// options are stable for the life of the fixture list, so the palette is built once.
const PALETTES = new WeakMap<readonly SettingOption[], readonly string[]>()

/**
 * A wheel's own colours, in level order, each once (lowercase `#rrggbb`) — what an animated band
 * cycles through. A blackout is left out: a wheel scrolling does not go dark between colours.
 */
export function wheelPalette(options: readonly SettingOption[]): readonly string[] {
  const cached = PALETTES.get(options)
  if (cached) return cached
  const seen = new Set<string>()
  const out: string[] = []
  for (const o of options) {
    const c = o.colourPreview ? parse(o.colourPreview) : null
    if (!c || (c[0] === 0 && c[1] === 0 && c[2] === 0)) continue
    const hex = toHex(c[0], c[1], c[2])
    if (seen.has(hex)) continue
    seen.add(hex)
    out.push(hex)
  }
  PALETTES.set(options, out)
  return out
}

/**
 * [palette] at [timeS]: each colour holds, then moves to the next over the last [BAND_MOVE] of its
 * step, eased, wrapping round — a deterministic function of the time handed in.
 */
export function cycleColour(palette: readonly string[], timeS: number): string | undefined {
  const n = palette.length
  if (n === 0) return undefined
  if (n === 1) return palette[0]
  const s = Math.max(0, Number.isFinite(timeS) ? timeS : 0) / BAND_STEP_S
  const whole = Math.floor(s)
  const i = whole % n
  const f = s - whole
  const hold = 1 - BAND_MOVE
  if (f <= hold) return palette[i]
  const t = (f - hold) / BAND_MOVE
  const e = t * t * (3 - 2 * t)
  const a = parse(palette[i])!
  const b = parse(palette[(i + 1) % n])!
  return toHex(a[0] + (b[0] - a[0]) * e, a[1] + (b[1] - a[1]) * e, a[2] + (b[2] - a[2]) * e)
}

/**
 * The colour a colour setting at [level] puts in the beam at [timeS] seconds: the band's preview, or
 * for an animated band ([isAnimatedBand]) its wheel's palette at that time, or `undefined` where the
 * band says nothing (see the module comment for what each dispatch does with that).
 */
export function settingColourAt(
  options: readonly SettingOption[],
  level: number,
  timeS: number,
): string | undefined {
  const option = bandOption(options, level)
  if (!option) return undefined
  if (option.colourPreview) return option.colourPreview
  if (option.noColour === true) return cycleColour(wheelPalette(options), timeS)
  return undefined
}

/**
 * A colour **source**'s beam from [settingColourAt]'s answer: its colour, open white where it has
 * none (nothing draws black for want of data — fixture-optics plan D8), lit at full unless the band
 * is a blackout. The level multiplies the fixture's dimmer.
 */
export function sourceBandColour(colour: string | undefined): string {
  return colour ?? OPEN_WHITE
}

export function sourceBandLevel(colour: string | undefined): number {
  return isBlackout(colour) ? 0 : 1
}

// ─── the 2D clock ───────────────────────────────────────────────────────────

/**
 * The time an animated band is drawn at on the 2D surfaces (the plot, the DOM markers, the busk
 * rig's tiles). `now` only changes when it notifies, so it is a valid `useSyncExternalStore`
 * snapshot. The test supplies a fixed one; the 3D scene has its own (`colourTicker.ts`).
 */
export interface ColourBandClock {
  now(): number
  subscribe(onTick: () => void): () => void
}

/** How often the 2D surfaces redraw an animated band: a colour change, not a frame-rate animation. */
const TICK_MS = 1000 / 15

function makeBrowserClock(): ColourBandClock {
  const listeners = new Set<() => void>()
  let current = 0
  let timer: ReturnType<typeof setInterval> | null = null
  const read = () => (typeof performance !== 'undefined' ? performance.now() : Date.now()) / 1000
  const tick = () => {
    current = read()
    for (const l of [...listeners]) l()
  }
  return {
    now: () => current,
    subscribe(onTick) {
      listeners.add(onTick)
      if (timer == null) {
        current = read()
        timer = setInterval(tick, TICK_MS)
      }
      return () => {
        listeners.delete(onTick)
        if (listeners.size === 0 && timer != null) {
          clearInterval(timer)
          timer = null
        }
      }
    },
  }
}

export const ColourBandClockContext = createContext<ColourBandClock>(makeBrowserClock())

const NO_SUBSCRIPTION = () => () => {}

/**
 * The 2D surfaces' time for an animated band, in seconds: it ticks — and re-renders the caller —
 * only while [animated], so a wheel on a fixed colour costs nothing.
 */
export function useColourBandTime(animated: boolean): number {
  const clock = useContext(ColourBandClockContext)
  const subscribe = useCallback((onTick: () => void) => clock.subscribe(onTick), [clock])
  return useSyncExternalStore(animated ? subscribe : NO_SUBSCRIPTION, animated ? clock.now : zero, animated ? clock.now : zero)
}

function zero(): number {
  return 0
}
