import type { Fixture, SettingAttributeDescriptor, SettingOption } from '../store/fixtures'
import { findColourSource } from '../store/fixtures'
import { isBlackout } from './colourBands'
import { wheelSplits } from './colourSplit'

/**
 * A colour chase on colour-wheel heads, as the editor and the picker start one (wheel-colour-chase
 * plan D1, D7). The desk snaps each colour to a head's nearest slot; this side only says which heads
 * are wheels and what a chase "from the wheel" holds.
 */

/** A head's colour wheel, where its colour is one: a colour setting and no mix. */
export function colourWheelOf(fixture: Fixture): SettingAttributeDescriptor | undefined {
  const source = findColourSource(fixture.attributes ?? [])
  return source?.type === 'setting' ? source.attribute : undefined
}

/** The colour source of [fixture]'s elements, where the fixture itself has none (the Shehds' zones). */
function elementColourSource(fixture: Fixture) {
  for (const element of fixture.elements ?? []) {
    const source = findColourSource(element.attributes ?? [])
    if (source != null) return source
  }
  return undefined
}

/** Whether any of [fixtures] makes its colour with a wheel, its own or its elements', so a stepping colour FX reaches it (D1). */
export function hasColourWheel(fixtures: readonly Fixture[]): boolean {
  return fixtures.some((f) => colourWheelOf(f) != null || (findColourSource(f.attributes ?? []) == null && elementColourSource(f)?.type === 'setting'))
}

/** The wheel a chase can start from: one model's, with its colours in wheel order and whether it splits. */
export interface WheelStart {
  /** What the editor calls it: *the MAC 250's wheel*. */
  model: string
  typeKey: string
  /** Each slot's preview in wheel order, open and no-colour bands left out, each once. */
  colours: string[]
  splits: boolean
}

const OPEN = /^open/i

/** A wheel slot a chase may land on: it carries a colour, and is neither the open hole nor a blackout. */
function chaseSlot(option: SettingOption): boolean {
  return option.colourPreview != null && option.noColour !== true && !OPEN.test(option.name) && !isBlackout(option.colourPreview)
}

/**
 * The wheel a Colour Cycle over [fixtures] starts from (D7): only where every head with a colour
 * makes it with a wheel of its own, and every such wheel is one model's. A head with a mix, colour on
 * its elements, two models, or no wheel at all answer null.
 */
export function wheelStartOf(fixtures: readonly Fixture[]): WheelStart | null {
  let start: { fixture: Fixture; wheel: SettingAttributeDescriptor } | null = null
  for (const fixture of fixtures) {
    const source = findColourSource(fixture.attributes ?? [])
    if (source == null) {
      if (elementColourSource(fixture) != null || fixture.elementGroupAttributes?.some((p) => p.type === 'colour')) return null
      continue
    }
    if (source.type !== 'setting') return null
    if (start == null) start = { fixture, wheel: source.attribute }
    else if (start.fixture.typeKey !== fixture.typeKey) return null
  }
  if (start == null) return null
  const seen = new Set<string>()
  const colours: string[] = []
  for (const option of [...start.wheel.options].sort((a, b) => a.level - b.level)) {
    if (!chaseSlot(option)) continue
    const hex = option.colourPreview!.toLowerCase()
    if (seen.has(hex)) continue
    seen.add(hex)
    colours.push(hex)
  }
  if (colours.length === 0) return null
  return {
    model: start.fixture.model ?? start.fixture.typeKey,
    typeKey: start.fixture.typeKey,
    colours,
    splits: wheelSplits(start.wheel.options),
  }
}

/** Colour Cycle's parameters starting from [start]: its colours, on Split where the wheel splits and Cut where it does not. */
export function wheelChaseParameters(start: WheelStart): Record<string, string> {
  return { colours: start.colours.join(','), between: start.splits ? 'SPLIT' : 'CUT' }
}

/** Whether [setting] is a colour wheel that splits, where Slot Steps' *Between* means something (D6). */
export function isSplittingColourWheel(setting: SettingAttributeDescriptor | undefined): boolean {
  return setting != null && setting.category === 'colour' && wheelSplits(setting.options)
}

/** Whether any of [fixtures], or their elements, has [attribute] as a colour wheel that splits. */
export function anySplittingWheel(fixtures: readonly Fixture[], attribute: string | null | undefined): boolean {
  if (attribute == null) return false
  const splits = (attributes: readonly { name: string; type: string }[] | undefined) =>
    (attributes ?? []).some((p) => p.type === 'setting' && p.name === attribute && isSplittingColourWheel(p as SettingAttributeDescriptor))
  return fixtures.some((f) => splits(f.attributes) || (f.elements ?? []).some((e) => splits(e.attributes)))
}

/** Whether [fxType] names Colour Cycle, however it is spelled. */
export function isColourCycle(fxType: string | null | undefined): boolean {
  return fxType != null && fxType.replace(/[\s_]/g, '').toLowerCase() === 'colourcycle'
}

/**
 * Whether a stepping colour FX reaches a wheel head (D1): a colour FX naming `stepsFrom` and
 * `rgbColour`. The desk lands each step on a wheel head's nearest slot; any other colour FX leaves
 * a wheel alone, so a picker offers it there only where a head mixes its colour.
 */
export function reachesColourWheels(entry: { family: string; stepsFrom?: string | null; compatibleAttributes: readonly string[] }): boolean {
  return entry.family.toLowerCase() === 'colour' && entry.stepsFrom != null && entry.compatibleAttributes.includes('rgbColour')
}

/** Colour Cycle's starting parameters on [start], under what a preset already names ([named]). */
export function wheelStartParameters(start: WheelStart, named: Readonly<Record<string, string>> = {}): Record<string, string> {
  return Object.fromEntries(Object.entries(wheelChaseParameters(start)).filter(([k]) => !(k in named)))
}
