import type { SettingOption } from '../store/fixtures'

/**
 * Where a colour wheel splits (wheel-colour-chase plan D5, D9): the client's reading of the desk's
 * rule in `fixture/dmx/ColourSplits.kt`, pinned to it by `src/test/resources/fixture/colourSplit.fixture.json`.
 *
 * Only an option declaring `continuousLevel` takes part. The declared options, in level order, are
 * the wheel's continuous band: two of them split where they are neighbours among them, at the
 * midpoint of their two levels, rounded down. A level inside the band reads as the declared slots
 * either side of it and how far it is from the first to the second.
 */

/** A level on a wheel's continuous band: [share] of the way from [a] to [b], 0 to 1. */
export interface SplitReading {
  a: SettingOption
  b: SettingOption
  share: number
}

const DECLARED = new WeakMap<readonly SettingOption[], readonly SettingOption[]>()

function declared(options: readonly SettingOption[]): readonly SettingOption[] {
  const cached = DECLARED.get(options)
  if (cached) return cached
  const out = [...options].sort((x, y) => x.level - y.level).filter((o) => o.continuousLevel != null)
  DECLARED.set(options, out)
  return out
}

/** Whether a wheel with [options] splits at all: two or more declare a level. */
export function wheelSplits(options: readonly SettingOption[]): boolean {
  return declared(options).length >= 2
}

/**
 * [level] read on the wheel's continuous band, or null outside it or on a wheel that does not split.
 * Exactly on a declared level is that slot at share 0 with the next (the last slot's at share 1 with
 * the one before), as the desk reads it.
 */
export function splitReading(options: readonly SettingOption[], level: number): SplitReading | null {
  const slots = declared(options)
  if (slots.length < 2) return null
  const first = slots[0].continuousLevel!
  const last = slots[slots.length - 1].continuousLevel!
  if (level < first || level > last) return null
  let i = 0
  for (let j = 0; j < slots.length; j++) if (slots[j].continuousLevel! <= level) i = j
  i = Math.min(i, slots.length - 2)
  const a = slots[i]
  const b = slots[i + 1]
  const share = (level - a.continuousLevel!) / (b.continuousLevel! - a.continuousLevel!)
  return { a, b, share }
}

/** The level holding the split of [a] and [b] (option names, either way round), or null where they do not split. */
export function splitLevel(options: readonly SettingOption[], a: string, b: string): number | null {
  if (a === b) return null
  const slots = declared(options)
  const i = slots.findIndex((o) => o.name === a)
  const j = slots.findIndex((o) => o.name === b)
  if (i < 0 || j < 0 || Math.abs(i - j) !== 1) return null
  return Math.floor((slots[i].continuousLevel! + slots[j].continuousLevel!) / 2)
}

/**
 * A reading in words for a table cell: *Red / Yellow* between two slots, or the one slot where the
 * level holds it whole. Null outside the band.
 */
export function splitLabel(options: readonly SettingOption[], level: number): string | null {
  const reading = splitReading(options, level)
  if (reading == null) return null
  if (reading.share <= 0) return reading.a.displayName
  if (reading.share >= 1) return reading.b.displayName
  return `${reading.a.displayName} / ${reading.b.displayName}`
}
