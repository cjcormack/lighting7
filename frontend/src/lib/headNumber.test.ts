import { describe, expect, it } from 'vitest'
import {
  checkHeadNumberLanding,
  consecutiveHeadNumbers,
  findHeadNumberClashes,
  headNumberFieldError,
  parseHeadNumberDraft,
} from './headNumber'

/**
 * The patch list's head numbers (CLAUDE.md §Sheet kit, lighting7 `docs/fixtures-engineering.md`
 * §"Head numbers"): what a typed number does to a selection, and what the desk would refuse.
 */
const head = (id: number, headNumber: number | null, name = `Head ${id}`) => ({ id, name, headNumber })

describe('parseHeadNumberDraft', () => {
  it('reads a whole number in range, and an empty draft as unnumbered', () => {
    expect(parseHeadNumberDraft(' 12 ')).toBe(12)
    expect(parseHeadNumberDraft('1')).toBe(1)
    expect(parseHeadNumberDraft('99999')).toBe(99999)
    expect(parseHeadNumberDraft('')).toBeNull()
    expect(parseHeadNumberDraft('   ')).toBeNull()
  })

  it('refuses anything else, including what `Number()` would take', () => {
    for (const bad of ['0', '100000', '-3', '1.5', '12.0', '1e3', '+4', 'twelve', '0x10']) {
      expect(parseHeadNumberDraft(bad), bad).toBe('invalid')
    }
  })
})

describe('checkHeadNumberLanding', () => {
  const all = [head(1, 1), head(2, 2), head(3, 3, 'Spot 1'), head(4, null)]

  it('counts up from the typed number in the order given', () => {
    expect([...consecutiveHeadNumbers([head(4, null), head(1, 1)], 10)]).toEqual([
      [4, 10],
      [1, 11],
    ])
    const landing = checkHeadNumberLanding([head(1, 1), head(4, null)], 10, all)
    expect(landing.error).toBeNull()
    expect(landing.lines).toEqual(['Head 1 → 10', 'Head 4 → 11'])
  })

  it('names a number a head outside the selection holds', () => {
    const landing = checkHeadNumberLanding([head(1, 1), head(2, 2)], 2, all)
    expect(landing.error).toBe('Head 3 is already Spot 1')
    expect(landing.numbers).toBeNull()
  })

  it('allows a number a member of the selection holds — the batch moves together', () => {
    const landing = checkHeadNumberLanding([head(2, 2), head(1, 1)], 1, all)
    expect(landing.error).toBeNull()
    expect(landing.numbers).toEqual(new Map([[2, 1], [1, 2]]))
  })

  it('refuses a run that would count past the ceiling', () => {
    const landing = checkHeadNumberLanding([head(1, 1), head(4, null)], 99999, all)
    expect(landing.error).toMatch(/ends at 100000/)
  })

  it('draws no lines for one head — renumbering one is not a run', () => {
    expect(checkHeadNumberLanding([head(4, null)], 7, all).lines).toEqual([])
  })
})

describe('findHeadNumberClashes', () => {
  it('maps every head sharing a number to another that holds it, and ignores the unnumbered', () => {
    const clashes = findHeadNumberClashes([head(1, 5), head(2, 5), head(3, 6), head(4, null), head(5, null)])
    expect([...clashes.keys()].sort()).toEqual([1, 2])
    expect(clashes.get(1)?.id).toBe(2)
    expect(clashes.get(2)?.id).toBe(1)
  })
})

describe('headNumberFieldError', () => {
  const all = [
    { id: 1, displayName: 'PAR 1', headNumber: 1 },
    { id: 2, displayName: 'PAR 2', headNumber: null },
  ]

  it('names the head holding a number, but not the head being edited', () => {
    expect(headNumberFieldError('1', all, null)).toBe('Head 1 is already PAR 1')
    expect(headNumberFieldError('1', all, 1)).toBeNull()
    expect(headNumberFieldError('2', all, null)).toBeNull()
    expect(headNumberFieldError('', all, null)).toBeNull()
    expect(headNumberFieldError('abc', all, null)).toMatch(/whole number/)
  })
})
