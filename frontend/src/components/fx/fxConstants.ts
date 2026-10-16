import { Sun, Palette, Move, Aperture, SlidersHorizontal, ListOrdered, type LucideIcon } from 'lucide-react'
import { ORDER_LABELS, ORDER_OPTIONS, orderLabel } from '@/lib/orderLabels'

/**
 * An effect's speed, in **beats a cycle** — the one spelling every surface uses
 * (`FU-FE-FX-SPEED-SPELLING`, settled by venue-visit plan S15). The editor's speed segment draws
 * these labels and every chip reads them through [getBeatDivisionLabel], so a speed is never *1*
 * in one place and *1/4* in another. A bar is four beats (venue-visit plan D16's words), so the
 * long cycles say bars.
 *
 * The note values (`1/4` for one beat, `1/32` for an eighth of one) are gone: they read the same
 * glyph-shape as the segment's fractions for a different number.
 */
export const SPEED_STEPS: readonly { value: number; label: string }[] = [
  { value: 0.125, label: '⅛ beat' },
  { value: 0.25, label: '¼ beat' },
  { value: 0.5, label: '½ beat' },
  { value: 1, label: '1 beat' },
  { value: 2, label: '2 beats' },
  { value: 4, label: '1 bar' },
  { value: 8, label: '2 bars' },
  { value: 16, label: '4 bars' },
]

/** The fractions a division off the eight is still said with — a triplet's ⅓. */
const FRACTION_GLYPHS: readonly { value: number; glyph: string }[] = [
  { value: 1 / 3, glyph: '⅓' },
  { value: 2 / 3, glyph: '⅔' },
  { value: 0.75, glyph: '¾' },
]

/** A number as a label writes it: at most three decimals, no trailing zeros. */
export function trimNumber(n: number): string {
  return String(Math.round(n * 1000) / 1000)
}

export const BLEND_MODE_OPTIONS = [
  { value: 'OVERRIDE', label: 'Override', description: 'Replace current value' },
  { value: 'ADDITIVE', label: 'Add', description: 'Add to current value' },
  { value: 'MULTIPLY', label: 'Multiply', description: 'Multiply with current value' },
  { value: 'MAX', label: 'Max', description: 'Use whichever is higher' },
  { value: 'MIN', label: 'Min', description: 'Use whichever is lower' },
] as const

export const EFFECT_CATEGORY_INFO: Record<
  string,
  { label: string; icon: LucideIcon; description: string }
> = {
  dimmer: { label: 'Dimmer', icon: Sun, description: 'Intensity and brightness effects' },
  colour: { label: 'Colour', icon: Palette, description: 'RGB colour cycling and effects' },
  position: { label: 'Position', icon: Move, description: 'Pan/tilt movement patterns' },
  beam: { label: 'Beam', icon: Aperture, description: 'Zoom, iris, frost and focus effects, and slot steps' },
  palette: { label: 'Palette steps', icon: ListOrdered, description: 'Steps through palettes of one family' },
  controls: { label: 'Controls', icon: SlidersHorizontal, description: 'Settings and slider controls' },
}

const DISTRIBUTION_DESCRIPTIONS: Record<string, string> = {
  LINEAR: 'Chase across heads in order',
  REVERSE: 'Chase in reverse order',
  CENTER_OUT: 'Expand from center to edges',
  EDGES_IN: 'Converge from edges to center',
  SPLIT: 'Split into two halves',
  RANDOM: 'Random offset per head',
  PING_PONG: 'Bounce back and forth',
  POSITIONAL: 'Based on fixture position',
  UNIFIED: 'All heads move together',
}

/**
 * Every distribution strategy, worded and ordered as the effect editor's Order (`lib/orderLabels.ts`,
 * effects plan §3.1) — one vocabulary, so a Look's summary and a group's *Runs as* picker say *Rig*
 * where the editor does. The stored value is the strategy's name.
 */
export const DISTRIBUTION_STRATEGY_OPTIONS: readonly { value: string; label: string; description: string }[] =
  ORDER_OPTIONS.map((value) => ({ value, label: orderLabel(value), description: DISTRIBUTION_DESCRIPTIONS[value] ?? '' }))

export const ELEMENT_MODE_OPTIONS = [
  { value: 'PER_FIXTURE', label: 'Per Fixture', description: 'Effect runs independently on each fixture' },
  { value: 'FLAT', label: 'Flat', description: 'All heads treated as one continuous strip' },
] as const

export const ELEMENT_FILTER_OPTIONS = [
  { value: 'ALL', label: 'All', description: 'All elements' },
  { value: 'ODD', label: 'Odd', description: 'Elements 1, 3, 5, ...' },
  { value: 'EVEN', label: 'Even', description: 'Elements 2, 4, 6, ...' },
  { value: 'FIRST_HALF', label: 'First Half', description: 'First half of elements' },
  { value: 'SECOND_HALF', label: 'Second Half', description: 'Second half of elements' },
] as const

export function getElementModeLabel(mode: string): string {
  const option = ELEMENT_MODE_OPTIONS.find((o) => o.value === mode)
  return option?.label ?? mode.toLowerCase()
}

export function getElementFilterLabel(filter: string): string {
  const option = ELEMENT_FILTER_OPTIONS.find((o) => o.value === filter)
  return option?.label ?? filter.toLowerCase()
}

/** Fallback descriptions if the library API doesn't provide them */
export const EFFECT_DESCRIPTIONS: Record<string, string> = {
  sinewave: 'Smooth wave oscillation',
  rampup: 'Gradual fade up, then reset',
  rampdown: 'Gradual fade down, then reset',
  triangle: 'Linear fade up and down',
  pulse: 'Sharp spike with quick decay',
  squarewave: 'Alternates between on and off',
  strobe: 'Fast on/off flashing',
  flicker: 'Random candle-like flicker',
  breathe: 'Gentle slow breathing rhythm',
  colourcycle: 'Step through a list of colours',
  rainbowcycle: 'Smooth rainbow hue rotation',
  colourstrobe: 'Flash between two colours',
  colourpulse: 'Pulse between two colours',
  colourfade: 'Crossfade between two colours',
  colourflicker: 'Random colour variation',
  circle: 'Circular movement pattern',
  figure8: 'Figure-8 movement pattern',
  sweep: 'Sweep between two positions',
  pansweep: 'Horizontal pan back and forth',
  tiltsweep: 'Vertical tilt up and down',
  randomposition: 'Random position jumps',
  zoombreathe: 'Zoom breathing between two widths',
  irispulse: 'Iris pulsing closed and open',
  frostpulse: 'Frost pulsing in and out',
  focuspull: 'Focus pulled between two distances',
  palettesteps: 'Steps through palettes of one family — each head goes to its own value for each',
  slotsteps: 'Steps a gobo or colour wheel through named slots — each head to its own level for each',
}

export function getEffectDescription(effectName: string, libraryDescription?: string): string {
  if (libraryDescription) return libraryDescription
  return EFFECT_DESCRIPTIONS[effectName.toLowerCase()] ?? effectName
}

export function getDistributionLabel(strategy: string): string {
  return ORDER_LABELS[strategy] ?? strategy.toLowerCase()
}

/**
 * An effect's speed as a label, with its **unit resolved** — beats a cycle for an effect that
 * follows a master, seconds for one on *Seconds* (venue-visit plan D7): a beat effect with
 * [seconds] set, or any `WALL_CLOCK` effect, whose division is seconds already.
 *
 * `beatDivision` carries both readings on one field, and they are a tempo apart: `2` is two beats
 * or two seconds, and only the timing says which. Everything that renders a speed away from the
 * live editor's fields (`FxEffectFields`, which have the library entry in hand and label the input
 * themselves) goes through this, so no surface has to re-derive the rule and none can get it half
 * right.
 *
 * **Null when the timing source is unknown**, and callers should then say nothing about the speed.
 * That happens for a template whose stored `effectType` no longer resolves in the registry — an
 * import from a desk carrying script-registered effects this one does not have. A confident
 * "2 bars" for a two-second cycle is worse than an absent clause.
 */
export function effectSpeedLabel(
  beatDivision: number,
  timingSource: string | null | undefined,
  seconds = false,
): string | null {
  if (timingSource == null) return null
  return seconds || timingSource === 'WALL_CLOCK' ? secondsLabel(beatDivision) : getBeatDivisionLabel(beatDivision)
}

/** A cycle in seconds — `4 s`, `0.5 s`. */
export function secondsLabel(seconds: number): string {
  return `${trimNumber(seconds)} s`
}

/** A division in beats a cycle, from [SPEED_STEPS] — `1 bar`, `½ beat`, `3 beats` off the table. */
export function getBeatDivisionLabel(beatDivision: number): string {
  const step = SPEED_STEPS.find((o) => Math.abs(o.value - beatDivision) < 0.001)
  if (step) return step.label
  const fraction = FRACTION_GLYPHS.find((f) => Math.abs(f.value - beatDivision) < 0.001)
  if (fraction) return `${fraction.glyph} beat`
  if (beatDivision >= 4 && Math.abs(beatDivision / 4 - Math.round(beatDivision / 4)) < 0.001) {
    return `${Math.round(beatDivision / 4)} bars`
  }
  return `${trimNumber(beatDivision)} ${beatDivision === 1 ? 'beat' : 'beats'}`
}
