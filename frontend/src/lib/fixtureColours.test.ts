import { describe, expect, it } from 'vitest'
import kotlin from '../../../src/main/kotlin/uk/me/cormack/lighting7/models/fixtureColours.kt?raw'
import {
  COLOUR_DIGITS,
  FIXTURE_COLOURS,
  RESERVED_HUES,
  colourForDigit,
  readColour,
  splitColourTerms,
  tagClass,
} from './fixtureColours'
import { fixtureMatchesTerms, filterTerms, hasColourTerm } from './fixtureSearch'
import { selectionForColour } from './colourSelection'

/** The desk's `FixtureColours.KEYS` and `RESERVED`, read from its source. */
function deskKeys(): { keys: string[]; reserved: string[] } {
  const keys = /val KEYS: List<String> = listOf\(([^)]*)\)/.exec(kotlin)?.[1] ?? ''
  const reserved = /val RESERVED: Map<String, String> = linkedMapOf\(([\s\S]*?)\n {4}\)/.exec(kotlin)?.[1] ?? ''
  return {
    keys: [...keys.matchAll(/"([a-z]+)"/g)].map((m) => m[1]),
    reserved: [...reserved.matchAll(/"([a-z]+)" to/g)].map((m) => m[1]),
  }
}

describe('the colour keys', () => {
  it('are the desk’s ten, in its order — a key one side lacks reads as none on the other', () => {
    // Not kept in step by hand: a key the desk refuses would be a swatch whose every write is 400
    // COLOUR_UNKNOWN, and a key it stores that this list lacks would draw as uncoloured.
    const desk = deskKeys()
    expect(desk.keys).toHaveLength(10)
    expect([...FIXTURE_COLOURS]).toEqual(desk.keys)
    expect(RESERVED_HUES.map((h) => h.key)).toEqual(desk.reserved)
  })

  it('never offers a reserved hue', () => {
    for (const { key } of RESERVED_HUES) expect(readColour(key)).toBeNull()
  })

  it('reads an unknown or absent key as none', () => {
    expect(readColour('red')).toBe('red')
    expect(readColour('magenta')).toBeNull()
    expect(readColour('Red')).toBeNull()
    expect(readColour(null)).toBeNull()
    expect(readColour(undefined)).toBeNull()
  })

  it('maps 1–9 and 0 onto the ten in order, and nothing else', () => {
    expect(FIXTURE_COLOURS.map((c) => COLOUR_DIGITS[c])).toEqual(['1', '2', '3', '4', '5', '6', '7', '8', '9', '0'])
    expect(colourForDigit('1')).toBe('red')
    expect(colourForDigit('9')).toBe('white')
    expect(colourForDigit('0')).toBe('grey')
    expect(colourForDigit('r')).toBeUndefined()
    expect(colourForDigit('.')).toBeUndefined()
  })

  it('marks a row with whole class strings, and nothing for none', () => {
    expect(tagClass('lime')).toBe('fixture-tag fixture-tag-lime')
    expect(tagClass(null)).toBeUndefined()
  })
})

describe('colour: in the fixture filter (D11)', () => {
  const hex = { name: 'Hex 1', manufacturer: 'Chauvet', model: 'Freedom Par Hex', typeKey: 'par-hex' }

  it('splits the colour terms from the rest, matching a key or label by prefix', () => {
    expect(splitColourTerms(['colour:red', 'hex'])).toEqual({ colours: ['red'], rest: ['hex'] })
    expect(splitColourTerms(['colour:gr'])).toEqual({ colours: ['green', 'grey'], rest: [] })
    expect(splitColourTerms(['colour:'])).toEqual({ colours: null, rest: [] })
    expect(splitColourTerms(['colour:blue']).colours).toEqual([])
    expect(splitColourTerms(['hex']).colours).toBeNull()
  })

  it('matches the colour, any of several, beside the text terms', () => {
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red'), 'red')).toBe(true)
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red'), 'lime')).toBe(false)
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red'), null)).toBe(false)
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red colour:lime'), 'lime')).toBe(true)
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red hex'), 'red')).toBe(true)
    expect(fixtureMatchesTerms(hex, filterTerms('colour:red spot'), 'red')).toBe(false)
    // Without a colour term, the colour is not read.
    expect(fixtureMatchesTerms(hex, filterTerms('hex'))).toBe(true)
    expect(hasColourTerm(filterTerms('hex colour:pi'))).toBe(true)
    expect(hasColourTerm(filterTerms('hex'))).toBe(false)
  })
})

describe('⌘K select by colour', () => {
  const desk = { targets: [{ type: 'group' as const, key: 'Front' }, { type: 'fixture' as const, key: 'hex-2' }], families: ['COLOUR' as const] }

  it('replaces the desk selection, mask and all', () => {
    expect(selectionForColour(['hex-1', 'hex-2'], desk, false)).toEqual({
      targets: [
        { type: 'fixture', key: 'hex-1' },
        { type: 'fixture', key: 'hex-2' },
      ],
      families: null,
    })
  })

  it('adds to it with ⌥, keeping its heads, its order and its mask', () => {
    expect(selectionForColour(['hex-1', 'hex-2'], desk, true)).toEqual({
      targets: [...desk.targets, { type: 'fixture', key: 'hex-1' }],
      families: ['COLOUR'],
    })
  })
})
