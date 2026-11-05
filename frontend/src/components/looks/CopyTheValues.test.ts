import { describe, expect, it } from 'vitest'
import { followsSentence, rowsToCopy } from './CopyTheValues'

const DSR = { paletteId: 2, paletteUuid: 'dsr', name: 'DSR Focus' }
const AMBER = { paletteId: 3, paletteUuid: 'amber', name: 'Amber' }

describe('followsSentence', () => {
  it('names one palette, and counts several', () => {
    expect(followsSentence([{ uuid: 'a', reference: DSR }])).toBe('1 value follows DSR Focus.')
    expect(followsSentence([{ uuid: 'a', reference: DSR }, { uuid: 'b', reference: DSR }])).toBe('2 values follow DSR Focus.')
    expect(followsSentence([{ uuid: 'a', reference: DSR }, { uuid: 'b', reference: AMBER }])).toBe('2 values follow 2 palettes.')
    expect(followsSentence([])).toBeNull()
  })
})

describe('rowsToCopy', () => {
  const rows = [
    { uuid: 'a', reference: DSR },
    { uuid: 'b', reference: AMBER },
  ]
  it('takes the picked references, else every one', () => {
    expect(rowsToCopy(rows, new Set(['b']))).toEqual([rows[1]])
    expect(rowsToCopy(rows, new Set())).toEqual(rows)
    // A pick that names no reference (a literal row's uuid) is no pick.
    expect(rowsToCopy(rows, new Set(['z']))).toEqual(rows)
  })
})
