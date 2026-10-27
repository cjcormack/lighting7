import type { FxLibraryEntry } from '@/store/fixtureFx'
import { normalizeFxName } from '../busking/buskingTypes'

/**
 * The common values a numeric parameter offers as a segment, ending in a typed cell (fx-settings
 * plan D9: a segment of presets always ends in a typed value, lit when the value matches none) —
 * Wave's Width, Colour Strobe's. A choice that stands for a value of its own (Wave's *One head*,
 * its `widthUnit`) joins the segment as one more preset, and the typed cell takes it back off.
 *
 * A client table, keyed by FX and parameter: which values are common is the editor's word, not a
 * rule the desk runs.
 */
export interface ParamPresets {
  /** Stored values, in the segment's order. */
  values: readonly number[]
  /** A choice drawn as one more preset: [value] on [param], and [off] while a number is chosen. */
  alongside?: { param: string; value: string; off: string; label: string }
}

const TABLE: Readonly<Record<string, Readonly<Record<string, ParamPresets>>>> = {
  wave: {
    width: { values: [0.1, 0.5], alongside: { param: 'widthUnit', value: 'HEAD', off: 'RATIO', label: 'One head' } },
  },
  colourstrobe: {
    width: { values: [0.1, 0.25, 0.5] },
  },
}

/** [param]'s presets on [entry], or null where it has none. */
export function paramPresetsOf(entry: Pick<FxLibraryEntry, 'name'>, param: string): ParamPresets | null {
  const name = normalizeFxName(entry.name)
  // Own keys only: a user FX may be named `constructor`, and a parameter `length`.
  const byParam = Object.hasOwn(TABLE, name) ? TABLE[name] : undefined
  return byParam != null && Object.hasOwn(byParam, param) ? byParam[param] : null
}
