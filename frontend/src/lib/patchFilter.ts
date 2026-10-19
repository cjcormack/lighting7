import type { FixturePatch } from '@/api/patchApi'
import { readColour, splitColourTerms } from './fixtureColours'
import { filterTerms } from './fixtureSearch'

/**
 * The universe toggle and the text filter, over patches — the sheet's rows and the removed heads
 * alike. A `colour:red` term (tables-colours plan D11) matches the colour; the rest of the text is
 * one needle, as it always was.
 */
export function filterPatches(
  patches: readonly FixturePatch[],
  universeFilter: 'all' | number,
  filter: string,
): FixturePatch[] {
  const terms = filterTerms(filter)
  const { colours, rest } = splitColourTerms(terms)
  // A `colour:` term — a bare one still being typed included — is never part of the needle.
  const needle = rest.length === terms.length ? filter.trim().toLowerCase() : rest.join(' ')
  return patches
    .filter((p) => universeFilter === 'all' || p.universe === universeFilter)
    .filter((p) => {
      if (colours != null) {
        const colour = readColour(p.colour)
        if (colour == null || !colours.includes(colour)) return false
      }
      if (needle === '') return true
      const address = `${p.universe}-${String(p.startChannel).padStart(3, '0')}`
      const head = p.headNumber == null ? null : String(p.headNumber)
      return [p.displayName, p.key, head, p.manufacturer, p.model, p.modeName, address]
        .some((field) => field?.toLowerCase().includes(needle))
    })
}
