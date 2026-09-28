import { describe, expect, it } from 'vitest'
import { ATTRIBUTE_FAMILIES } from './attributeFamily'
import {
  familiesKey,
  normaliseFamilies,
  parseFamilies,
  parseFamilyList,
  sameFamilies,
  skippedRowsMessage,
} from './selectionMask'

describe('normaliseFamilies', () => {
  it('folds none and all four to null, and orders the rest by declaration', () => {
    expect(normaliseFamilies(null)).toBeNull()
    expect(normaliseFamilies([])).toBeNull()
    expect(normaliseFamilies([...ATTRIBUTE_FAMILIES])).toBeNull()
    expect(normaliseFamilies(['COLOUR', 'INTENSITY'])).toEqual(['INTENSITY', 'COLOUR'])
  })

  it('gives every mask one key, so a frame compares to what was sent', () => {
    expect(familiesKey(['COLOUR', 'INTENSITY'])).toBe('INTENSITY,COLOUR')
    expect(familiesKey(null)).toBe('')
    expect(familiesKey([...ATTRIBUTE_FAMILIES])).toBe('')
    expect(sameFamilies(['COLOUR', 'INTENSITY'], ['INTENSITY', 'COLOUR'])).toBe(true)
    expect(sameFamilies(null, [])).toBe(true)
    expect(sameFamilies(['COLOUR'], null)).toBe(false)
  })
})

describe('parseFamilies', () => {
  it('reads the wire spelling case-insensitively and drops what it does not know', () => {
    expect(parseFamilies(['colour', ' Position '])).toEqual(['POSITION', 'COLOUR'])
    expect(parseFamilies(['COLOUR', 42, 'GOBO'])).toEqual(['COLOUR'])
    expect(parseFamilies('COLOUR')).toBeNull()
  })
})

describe('skippedRowsMessage', () => {
  it('names the rows skipped and the mask, in the operator’s words', () => {
    expect(skippedRowsMessage(['POSITION'], ['COLOUR'])).toBe(
      'Position rows skipped — the selection is Colour',
    )
    expect(skippedRowsMessage(['POSITION', 'BEAM'], ['INTENSITY', 'COLOUR'])).toBe(
      'Position + Beam rows skipped — the selection is Intensity + Colour',
    )
  })

  it('says nothing for an unmasked press or an off press', () => {
    expect(skippedRowsMessage([], ['COLOUR'])).toBeNull()
    expect(skippedRowsMessage([], null)).toBeNull()
  })

  it('reads the skip as a list, never as a mask — all four is a report, not none', () => {
    // `parseFamilies` folds a complete list to null because for a *mask* all four is no mask; a
    // skip naming every family would then toast nothing. Unreachable against af3575a (an empty
    // intersection is a 400), pinned so a server change cannot silently swallow the toast.
    expect(parseFamilyList(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'])).toEqual([
      'INTENSITY',
      'POSITION',
      'COLOUR',
      'BEAM',
    ])
    expect(skippedRowsMessage(['INTENSITY', 'POSITION', 'COLOUR', 'BEAM'], null)).toBe(
      'Intensity + Position + Colour + Beam rows skipped',
    )
    expect(parseFamilyList(['GOBO', 'colour'])).toEqual(['COLOUR'])
  })

  it('still says what was skipped when the mask it holds is stale', () => {
    // The desk answered from the mask it was sent; a tab that has since lost its own reading
    // still owes the operator the skip.
    expect(skippedRowsMessage(['POSITION'], null)).toBe('Position rows skipped')
  })
})
