import { describe, expect, it } from 'vitest'
import { includeRequestFor } from './useInclude'

describe('includeRequestFor', () => {
  it('includes a cue again from the entry it was included from', () => {
    expect(includeRequestFor({ kind: 'CUE', cueId: 4, entryId: 9, cueName: 'Warm wash' })).toEqual({
      kind: 'CUE',
      cueId: 4,
      entryId: 9,
    })
  })

  it('includes a Look again', () => {
    expect(includeRequestFor({ kind: 'LOOK', lookId: 7, lookName: 'Set — Market' })).toEqual({ kind: 'LOOK', lookId: 7 })
  })

  it('has nothing to include with nothing included', () => {
    expect(includeRequestFor(null)).toBeNull()
  })
})
