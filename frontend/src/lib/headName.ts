/** The fields of a fixture a head is named and folded by — `Fixture` from `store/fixtures`. */
interface NamedFixture {
  key: string
  name: string
  elements?: readonly { key: string; displayName: string }[]
}

/** A fixture's name, or a head's as *Bar 1 Head 3*, looked up by key — element keys never parsed. */
export function headName(fixtures: readonly NamedFixture[], key: string): string {
  for (const fixture of fixtures) {
    if (fixture.key === key) return fixture.name
    const element = fixture.elements?.find((e) => e.key === key)
    if (element) return `${fixture.name} ${element.displayName}`
  }
  return key
}

/** A fixture's cells' keys — none for a single-head fixture or a key that names no fixture. */
export function cellKeysOf(fixtures: readonly NamedFixture[], key: string): readonly string[] {
  return fixtures.find((fixture) => fixture.key === key)?.elements?.map((e) => e.key) ?? []
}
