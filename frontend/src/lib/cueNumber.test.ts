import { describe, it, expect } from 'vitest'
import {
  compareWithinGroup,
  cueNumberGroupKey,
  detectCueNumbersOutOfOrder,
  detectOutOfOrder,
  parseCueNumber,
} from './cueNumber'

/**
 * Mirrors `CueNumberingTest.kt` in the lighting7 backend for the parts both sides implement.
 * If a case here changes, change it there too — the banner this drives and the fix the server
 * performs have to agree on what "out of order" means.
 */
describe('parseCueNumber', () => {
  it('splits prefix, decimal run and suffix', () => {
    expect(parseCueNumber('S1-3.1')).toEqual({ prefix: 'S1-', segments: [3, 1], suffix: '' })
    expect(parseCueNumber('S1-4')).toEqual({ prefix: 'S1-', segments: [4], suffix: '' })
    expect(parseCueNumber('T2-1')).toEqual({ prefix: 'T2-', segments: [1], suffix: '' })
    expect(parseCueNumber('Pre-show 2')).toEqual({
      prefix: 'Pre-show ',
      segments: [2],
      suffix: '',
    })
    expect(parseCueNumber('14A')).toEqual({ prefix: '', segments: [14], suffix: 'A' })
  })

  it('takes the last decimal run, not the first', () => {
    // "S1-3" must group as ("S1-", 3), not ("S", 1) with a dangling "-3".
    expect(parseCueNumber('S1-3')?.prefix).toBe('S1-')
    expect(parseCueNumber('S1-3')?.segments).toEqual([3])
  })

  it('rejects numbers with nothing to order by', () => {
    expect(parseCueNumber('A')).toBeNull()
    expect(parseCueNumber('intro')).toBeNull()
    expect(parseCueNumber('')).toBeNull()
    expect(parseCueNumber('S1-')).toBeNull()
  })
})

describe('cueNumberGroupKey', () => {
  it('keys on the prefix, case-insensitively', () => {
    expect(cueNumberGroupKey('S1-3')).toBe('s1-')
    expect(cueNumberGroupKey('s1-9')).toBe('s1-')
    expect(cueNumberGroupKey('Pre-show 2')).toBe('pre-show ')
    expect(cueNumberGroupKey('14A')).toBe('')
  })

  it('gives unparseable numbers private keys', () => {
    expect(cueNumberGroupKey('intro')).not.toBe(cueNumberGroupKey('verse'))
  })
})

describe('compareWithinGroup', () => {
  const sortNumbers = (numbers: string[]) =>
    [...numbers].sort((a, b) => compareWithinGroup(parseCueNumber(a)!, parseCueNumber(b)!))

  it('nests decimals and tie-breaks on suffix', () => {
    expect(sortNumbers(['100', '14B', '1.5', '15', '2', '14A', '1', '14'])).toEqual([
      '1',
      '1.5',
      '2',
      '14',
      '14A',
      '14B',
      '15',
      '100',
    ])
  })

  it('compares decimal segments numerically, not as text', () => {
    // "1.10" is the tenth insert under 1, so it follows "1.5".
    expect(sortNumbers(['1.10', '1.5'])).toEqual(['1.5', '1.10'])
  })

  it('ignores leading zeros', () => {
    expect(compareWithinGroup(parseCueNumber('01')!, parseCueNumber('1')!)).toBe(0)
  })
})

describe('detectCueNumbersOutOfOrder', () => {
  it('accepts interleaved groups that each ascend', () => {
    expect(
      detectCueNumbersOutOfOrder(['Pre-show 1', 'Pre-show 2', 'T2-1', 'S-1', 'S-2']),
    ).toBe(false)
  })

  it('flags a group descending against itself', () => {
    expect(
      detectCueNumbersOutOfOrder(['Pre-show 1', 'Pre-show 2', 'T2-1', 'S-2', 'S-1']),
    ).toBe(true)
  })

  it('flags prefixed numbers — the old digit-first rule missed these', () => {
    expect(detectCueNumbersOutOfOrder(['S1-4', 'S1-3.1'])).toBe(true)
    expect(detectCueNumbersOutOfOrder(['S1-3.1', 'S1-4'])).toBe(false)
  })

  it('ignores blank and unparseable numbers', () => {
    expect(detectCueNumbersOutOfOrder(['S1-1', null, 'A', '', 'S1-2'])).toBe(false)
  })
})

describe('detectOutOfOrder', () => {
  const cue = (cueNumber: string | null, cueType = 'STANDARD') => ({ cueNumber, cueType })

  it('skips MARKER rows', () => {
    // The separator's "9" would look like a descent if markers participated.
    expect(detectOutOfOrder([cue('1'), cue('9', 'MARKER'), cue('2')])).toBe(false)
  })

  it('flags a genuine descent among standard cues', () => {
    expect(detectOutOfOrder([cue('S1-4'), cue('S1-3.1')])).toBe(true)
  })

  it('accepts an empty or unnumbered stack', () => {
    expect(detectOutOfOrder([])).toBe(false)
    expect(detectOutOfOrder([cue(null), cue(null)])).toBe(false)
  })
})
