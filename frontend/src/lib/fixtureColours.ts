/**
 * A fixture's or group's **colour** (tables-colours plan D7, D8): one of ten named keys that marks
 * which fixture a row, a tile or a label is — identity, never state. The stored value is the key;
 * each key's light and dark values are `--tag-<key>` in `index.css`. Five hues are reserved because
 * the desk already means something by them, and are never offered.
 *
 * The keys mirror lighting7's `models/fixtureColours.kt`, pinned by `fixtureColours.test.ts`.
 */

export const FIXTURE_COLOURS = ['red', 'orange', 'yellow', 'lime', 'green', 'cyan', 'pink', 'brown', 'white', 'grey'] as const

export type FixtureColour = (typeof FIXTURE_COLOURS)[number]

export const FIXTURE_COLOUR_LABELS: Record<FixtureColour, string> = {
  red: 'Red',
  orange: 'Orange',
  yellow: 'Yellow',
  lime: 'Lime',
  green: 'Green',
  cyan: 'Cyan',
  pink: 'Pink',
  brown: 'Brown',
  white: 'White',
  grey: 'Grey',
}

/** The hues the desk already draws something in, and what — never a colour. */
export const RESERVED_HUES: readonly { key: string; means: string }[] = [
  { key: 'blue', means: 'your values' },
  { key: 'amber', means: 'a park' },
  { key: 'violet', means: 'an effect' },
  { key: 'sky', means: 'a cue' },
  { key: 'teal', means: 'a Look and the venue' },
]

const KEYS: ReadonlySet<string> = new Set(FIXTURE_COLOURS)

/** A stored or sent colour as a key; null for none and for a key this desk does not know (D7). */
export function readColour(raw: string | null | undefined): FixtureColour | null {
  return raw != null && KEYS.has(raw) ? (raw as FixtureColour) : null
}

/** The digit that picks each colour: 1–9 for the first nine, 0 for the tenth. */
export const COLOUR_DIGITS: Record<FixtureColour, string> = Object.fromEntries(
  FIXTURE_COLOURS.map((colour, i) => [colour, String((i + 1) % 10)]),
) as Record<FixtureColour, string>

const BY_DIGIT: ReadonlyMap<string, FixtureColour> = new Map(FIXTURE_COLOURS.map((c) => [COLOUR_DIGITS[c], c]))

/** The colour a typed digit picks, or undefined for any other key. */
export function colourForDigit(key: string): FixtureColour | undefined {
  return BY_DIGIT.get(key)
}

/** The CSS custom property holding a colour's value in the current theme. */
export function colourVar(colour: FixtureColour): string {
  return `var(--tag-${colour})`
}

/**
 * The row class that marks a row with a colour: `--tag` for its swatch, and the name cell's wash
 * (`.fixture-tag > [data-name-cell]` in `index.css`). Whole strings, so a reader can grep them.
 */
const TAG_CLASS: Record<FixtureColour, string> = {
  red: 'fixture-tag fixture-tag-red',
  orange: 'fixture-tag fixture-tag-orange',
  yellow: 'fixture-tag fixture-tag-yellow',
  lime: 'fixture-tag fixture-tag-lime',
  green: 'fixture-tag fixture-tag-green',
  cyan: 'fixture-tag fixture-tag-cyan',
  pink: 'fixture-tag fixture-tag-pink',
  brown: 'fixture-tag fixture-tag-brown',
  white: 'fixture-tag fixture-tag-white',
  grey: 'fixture-tag fixture-tag-grey',
}

export function tagClass(colour: FixtureColour | null | undefined): string | undefined {
  return colour == null ? undefined : TAG_CLASS[colour]
}

/**
 * Which colours a filter's `colour:` terms ask for (D11), and the terms left over. A term matches a
 * key or its label by prefix, so `colour:re` is red while it is being typed; several are any of
 * them. A bare `colour:` asks for nothing and is dropped.
 */
export function splitColourTerms(terms: readonly string[]): { colours: FixtureColour[] | null; rest: string[] } {
  const rest: string[] = []
  let colours: Set<FixtureColour> | null = null
  for (const term of terms) {
    const m = /^colou?r:(.*)$/i.exec(term)
    if (m == null) {
      rest.push(term)
      continue
    }
    const wanted = m[1].toLowerCase()
    if (wanted === '') continue
    colours ??= new Set()
    for (const colour of FIXTURE_COLOURS) {
      if (colour.startsWith(wanted) || FIXTURE_COLOUR_LABELS[colour].toLowerCase().startsWith(wanted)) colours.add(colour)
    }
  }
  return { colours: colours == null ? null : [...colours], rest }
}
