/**
 * The one definition of "does this fixture match the filter text": every term
 * must appear somewhere in name / manufacturer / model / typeKey. Shared by
 * the Fixtures card view and the Fixtures List so the same query never returns
 * different fixtures on the two pages.
 */

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

export function fixtureMatchesTerms(fixture: FixtureSearchFields, terms: string[]): boolean {
  if (terms.length === 0) return true
  const searchable = [fixture.name, fixture.manufacturer, fixture.model, fixture.typeKey]
    .filter(Boolean)
    .join(' ')
    .toLowerCase()
  return terms.every((term) => searchable.includes(term))
}
