import { Sun, Palette, Move, SlidersHorizontal, type LucideIcon } from 'lucide-react'

export const BEAT_DIVISION_OPTIONS = [
  { value: 0.125, label: '1/32', description: 'Thirty-second note' },
  { value: 0.25, label: '1/16', description: 'Sixteenth note' },
  { value: 0.333, label: 'Trip', description: 'Triplet' },
  { value: 0.5, label: '1/8', description: 'Eighth note' },
  { value: 1.0, label: '1/4', description: 'Quarter note (1 beat)' },
  { value: 2.0, label: '1/2', description: 'Half note (2 beats)' },
  { value: 4.0, label: '1 Bar', description: 'Full bar (4 beats)' },
  { value: 8.0, label: '2 Bars', description: '2 bars (8 beats)' },
] as const

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
  controls: { label: 'Controls', icon: SlidersHorizontal, description: 'Settings and slider controls' },
}

export const DISTRIBUTION_STRATEGY_OPTIONS = [
  { value: 'LINEAR', label: 'Linear', description: 'Chase across heads in order' },
  { value: 'UNIFIED', label: 'Unified', description: 'All heads move together' },
  { value: 'CENTER_OUT', label: 'Center Out', description: 'Expand from center to edges' },
  { value: 'EDGES_IN', label: 'Edges In', description: 'Converge from edges to center' },
  { value: 'REVERSE', label: 'Reverse', description: 'Chase in reverse order' },
  { value: 'SPLIT', label: 'Split', description: 'Split into two halves' },
  { value: 'PING_PONG', label: 'Ping Pong', description: 'Bounce back and forth' },
  { value: 'RANDOM', label: 'Random', description: 'Random offset per head' },
  { value: 'POSITIONAL', label: 'Positional', description: 'Based on fixture position' },
] as const

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
  colourcycle: 'Step through a palette of colours',
  rainbowcycle: 'Smooth rainbow hue rotation',
  colourstrobe: 'Flash between two colours',
  colourpulse: 'Pulse between two colours',
  colourfade: 'Crossfade between two colours',
  colourflicker: 'Random colour variation',
  staticcolour: 'Fixed colour output',
  circle: 'Circular movement pattern',
  figure8: 'Figure-8 movement pattern',
  sweep: 'Sweep between two positions',
  pansweep: 'Horizontal pan back and forth',
  tiltsweep: 'Vertical tilt up and down',
  randomposition: 'Random position jumps',
  staticposition: 'Fixed position output',
  staticvalue: 'Fixed dimmer/slider level',
  staticsetting: 'Fixed fixture setting',
}

export function getEffectDescription(effectName: string, libraryDescription?: string): string {
  if (libraryDescription) return libraryDescription
  return EFFECT_DESCRIPTIONS[effectName.toLowerCase()] ?? effectName
}

export function getDistributionLabel(strategy: string): string {
  const option = DISTRIBUTION_STRATEGY_OPTIONS.find((o) => o.value === strategy)
  return option?.label ?? strategy.toLowerCase()
}

/**
 * An effect's speed as a label, with its **unit resolved** — the beat division for a `BEAT` effect,
 * seconds for a `WALL_CLOCK` one.
 *
 * `beatDivision` carries both readings on one field, and they are a tempo apart: `2` is two beats
 * or two seconds, and only `timingSource` says which. Everything that renders a speed away from
 * `EffectParameterForm` (which has the library entry in hand and labels the input itself) goes
 * through this, so no surface has to re-derive the rule and none can get it half right.
 *
 * **Null when the timing source is unknown**, and callers should then say nothing about the speed.
 * That happens for a template whose stored `effectType` no longer resolves in the registry — an
 * import from a desk carrying script-registered effects this one does not have. A confident
 * "2 Bars" for a two-second cycle is worse than an absent clause.
 */
export function effectSpeedLabel(
  beatDivision: number,
  timingSource: string | null | undefined,
): string | null {
  if (timingSource == null) return null
  return timingSource === 'WALL_CLOCK' ? `${beatDivision}s` : getBeatDivisionLabel(beatDivision)
}

export function getBeatDivisionLabel(beatDivision: number): string {
  const option = BEAT_DIVISION_OPTIONS.find(
    (o) => Math.abs(o.value - beatDivision) < 0.01
  )
  return option?.label ?? `${beatDivision}x`
}
