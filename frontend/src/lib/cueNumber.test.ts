import { describe, it, expect } from 'vitest'
import {
  canonicalCueNumber,
  cueNumberProblem,
  cueNumberValue,
  CUE_NUMBER_RULE,
  detectCueNumbersOutOfOrder,
  detectOutOfOrder,
} from './cueNumber'

/**
 * Mirrors `CueNumberingTest.kt` in the backend for the parts both sides implement. If a case here
 * changes, change it there too — the field this checks and the desk that refuses have to agree on
 * what a cue number is.
 */
describe('canonicalCueNumber', () => {
  it('reads a number with at most four decimal places', () => {
    expect(canonicalCueNumber('1')).toBe('1')
    expect(canonicalCueNumber('1.5')).toBe('1.5')
    expect(canonicalCueNumber('9999.9999')).toBe('9999.9999')
    expect(canonicalCueNumber('0')).toBe('0')
    expect(canonicalCueNumber('.5')).toBe('0.5')
    expect(canonicalCueNumber('3.')).toBe('3')
    expect(canonicalCueNumber(' 12 ')).toBe('12')
    expect(canonicalCueNumber('Q12')).toBe('12')
  })

  it('gives one number one spelling', () => {
    expect(canonicalCueNumber('2.10')).toBe('2.1')
    expect(canonicalCueNumber('2.10000')).toBe('2.1')
    expect(canonicalCueNumber('007')).toBe('7')
    expect(canonicalCueNumber('10.0')).toBe('10')
    expect(canonicalCueNumber('100')).toBe('100')
  })

  it('refuses anything else', () => {
    for (const raw of ['', '.', 'A', 'intro', '14A', 'S1-3', '1.2.3', '-1', '1e3', '10000', '1.23456', 'Q']) {
      expect(canonicalCueNumber(raw), raw).toBeNull()
    }
  })
})

describe('cueNumberProblem', () => {
  it('passes blank and numbers, and names the rule otherwise', () => {
    expect(cueNumberProblem('')).toBeNull()
    expect(cueNumberProblem('4.5')).toBeNull()
    expect(cueNumberProblem('14A')).toBe(CUE_NUMBER_RULE)
  })
})

describe('cueNumberValue', () => {
  it('sorts by value', () => {
    const sorted = ['100', '2.9', '14', '1.5', '2.15', '10', '2.1', '9', '2', '2.05', '1'].sort(
      (a, b) => cueNumberValue(a)! - cueNumberValue(b)!,
    )
    expect(sorted).toEqual(['1', '1.5', '2', '2.05', '2.1', '2.15', '2.9', '9', '10', '14', '100'])
  })
})

describe('detectCueNumbersOutOfOrder', () => {
  it('is any number lower than one before it', () => {
    expect(detectCueNumbersOutOfOrder(['1', '2', '2.5', '10'])).toBe(false)
    expect(detectCueNumbersOutOfOrder(['1', '10', '9'])).toBe(true)
    expect(detectCueNumbersOutOfOrder(['2.2', '2.15'])).toBe(true)
    expect(detectCueNumbersOutOfOrder(['1', null, '', undefined, '2'])).toBe(false)
  })
})

describe('detectOutOfOrder', () => {
  it('ignores markers', () => {
    expect(
      detectOutOfOrder([
        { cueType: 'STANDARD', cueNumber: '1' },
        { cueType: 'MARKER', cueNumber: '9' },
        { cueType: 'STANDARD', cueNumber: '2' },
      ]),
    ).toBe(false)
    expect(
      detectOutOfOrder([
        { cueType: 'STANDARD', cueNumber: '3' },
        { cueType: 'STANDARD', cueNumber: '2' },
      ]),
    ).toBe(true)
  })
})
