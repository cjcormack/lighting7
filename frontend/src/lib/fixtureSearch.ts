/**
 * The one definition of "does this fixture match the filter text": every term
 * must appear somewhere in name / manufacturer / model / typeKey. Shared by
 * the Fixtures card view and the Fixtures List so the same query never returns
 * different fixtures on the two pages.
 *
 * A `colour:<key>` term (tables-colours plan D11) matches the fixture's colour instead; several are
 * any of them (`splitColourTerms`).
 */

import { splitColourTerms, type FixtureColour } from './fixtureColours'

export interface FixtureSearchFields {
  name: string
  manufacturer?: string
  model?: string
  typeKey: string
}

/** Split filter text into lowercase terms (empty array = no filtering). */
export function filterTerms(filterText: string): string[] {
  return filterText.trim().toLowerCase().split(/\s+/).filter(Boolean)
}

/** Whether these terms carry a `colour:` term — the one case where the fixtures' colours filter. */
export function hasColourTerm(terms: readonly string[]): boolean {
  return splitColourTerms(terms).colours != null
}

export function fixtureMatchesTerms(
  fixture: FixtureSearchFields,
  terms: string[],
  colour?: FixtureColour | null,
): boolean {
  if (terms.length === 0) return true
  const { colours, rest } = splitColourTerms(terms)
  if (colours != null && (colour == null || !colours.includes(colour))) return false
  if (rest.length === 0) return true
  const searchable = [fixture.name, fixture.manufacturer, fixture.model, fixture.typeKey]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return rest.every((term) => searchable.includes(term))
}
