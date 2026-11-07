import { Sun, Palette, Move, Aperture, SlidersHorizontal, ListOrdered, type LucideIcon } from 'lucide-react'
import { ORDER_LABELS, ORDER_OPTIONS, orderLabel } from '@/lib/orderLabels'
import type { ElementMode } from '@/api/groupsApi'

/**
 * The heads an FX palette is drawn on with nothing to count (FX editor plan D15): its head ladder
 * and patterns, and its graph strip's notional heads. [NOTIONAL_HEADS_WORD] is the number in the strip's copy.
 */
export const NOTIONAL_HEADS = 8
export const NOTIONAL_HEADS_WORD = 'eight'

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

export const FX_FAMILY_INFO: Record<
  string,
  { label: string; icon: LucideIcon; description: string }
> = {
  intensity: { label: 'Intensity', icon: Sun, description: 'Intensity and brightness FX' },
  colour: { label: 'Colour', icon: Palette, description: 'RGB colour cycling and FX' },
  position: { label: 'Position', icon: Move, description: 'Pan/tilt movement patterns' },
  beam: { label: 'Beam', icon: Aperture, description: 'Zoom, iris, frost and focus FX' },
  steps: { label: 'Steps', icon: ListOrdered, description: 'Steps through palettes, or a setting\'s slots' },
  controls: { label: 'Controls', icon: SlidersHorizontal, description: 'Settings and slider controls' },
}

const ORDER_DESCRIPTIONS: Record<string, string> = {
  NORMAL: 'Chase across heads in order',
  REVERSE: 'Chase in reverse order',
  CENTRE_OUT: 'Expand from centre to edges',
  CENTRE_IN: 'Converge from edges to centre',
  RANDOM: 'Random offset per head',
  PING_PONG: 'Bounce back and forth',
  POSITIONAL: 'Based on fixture position',
  UNIFIED: 'All heads move together',
}

/**
 * Every Order, worded and ordered as the FX editor's Order (`lib/orderLabels.ts`,
 * effects plan §3.1) — one vocabulary, so a Look's summary and a group's *Runs as* picker say *Normal*
 * where the editor does. The stored value is the strategy's name.
 */
export const ORDER_OPTION_ENTRIES: readonly { value: string; label: string; description: string }[] =
  ORDER_OPTIONS.map((value) => ({ value, label: orderLabel(value), description: ORDER_DESCRIPTIONS[value] ?? '' }))

/**
 * *Over* (FX editor follow-ups plan D7): what the spread counts where an FX lands on fixtures'
 * elements. A stored FX naming none is Each fixture; one a door starts is Fixtures (D8).
 */
export const ELEMENT_MODE_OPTIONS = [
  { value: 'WHOLE', label: 'Fixtures', description: 'Each fixture is one unit, its elements alike' },
  { value: 'FLAT', label: 'Elements', description: 'Every element of every fixture is one strip' },
  { value: 'PER_FIXTURE', label: 'Each fixture', description: 'The FX runs within each fixture, every fixture alike' },
] as const

/** The mode a door that starts or creates an FX writes (D8). */
export const NEW_FX_ELEMENT_MODE: ElementMode = 'WHOLE'

/** What a stored FX with no element mode means. */
export const STORED_ELEMENT_MODE: ElementMode = 'PER_FIXTURE'

export const ELEMENT_FILTER_OPTIONS = [
  { value: 'ALL', label: 'All', description: 'All elements' },
  { value: 'ODD', label: 'Odd', description: 'Elements 1, 3, 5, ...' },
  { value: 'EVEN', label: 'Even', description: 'Elements 2, 4, 6, ...' },
  { value: 'FIRST_HALF', label: 'First Half', description: 'First half of elements' },
  { value: 'SECOND_HALF', label: 'Second Half', description: 'Second half of elements' },
] as const

/** An FX's Over in a summary line, worded so it never reads as a target: *over fixtures*, *each fixture*. */
export function elementModeSummary(mode: string): string {
  const option = ELEMENT_MODE_OPTIONS.find((o) => o.value === mode)
  if (option == null) return mode.toLowerCase()
  return option.value === 'PER_FIXTURE' ? option.label.toLowerCase() : `over ${option.label.toLowerCase()}`
}

export function getElementFilterLabel(filter: string): string {
  const option = ELEMENT_FILTER_OPTIONS.find((o) => o.value === filter)
  return option?.label ?? filter.toLowerCase()
}

/** Fallback descriptions if the library API doesn't provide them */
export const FX_DESCRIPTIONS: Record<string, string> = {
  wave: 'A level wave — sine, ramp, triangle, square or pulse — on a dimmer or a beam attribute',
  flicker: 'Random candle-like flicker',
  colourcycle: 'Step through a list of colours',
  rainbowcycle: 'Smooth rainbow hue rotation',
  colourstrobe: 'Flash between two colours',
  colourwave: 'Wave between two colours',
  colourfade: 'Crossfade between two colours',
  colourflicker: 'Random colour variation',
  path: 'A movement — circle, figure 8, random, or a line between two positions',
  palettesteps: 'Steps through palettes of one family — each head goes to its own value for each',
  slotsteps: 'Steps a gobo or colour wheel through named slots — each head to its own level for each',
}

export function getFxDescription(fxName: string, libraryDescription?: string): string {
  if (libraryDescription) return libraryDescription
  return FX_DESCRIPTIONS[fxName.toLowerCase()] ?? fxName
}

export function getOrderLabel(order: string): string {
  return ORDER_LABELS[order] ?? order.toLowerCase()
}
