import { describe, expect, it } from 'vitest'
import { cuePickerRows, filterCuePickerRows, type CuePickerSource } from './cuePickerRows'

/**
 * The "An existing cue…" picker's rows (venue-visit plan S10): every cue, flat and by name, labelled
 * with whatever stack its list row names — and a row that names none (a loose cue, once the cue
 * model plan's session 4 lands) is still a cue.
 */
const cue = (over: Partial<CuePickerSource> & { id: number; name: string }): CuePickerSource => ({
  cueNumber: null,
  cueStackName: 'Act 1',
  cueType: 'STANDARD',
  ...over,
})

describe('cuePickerRows', () => {
  it('lists every cue by name, whatever stack it is in', () => {
    const rows = cuePickerRows([
      cue({ id: 1, name: 'Storm', cueNumber: '7', cueStackName: 'Act 1' }),
      cue({ id: 2, name: 'blackout', cueNumber: '12', cueStackName: 'Act 2' }),
      cue({ id: 3, name: 'Bows', cueNumber: '30', cueStackName: 'Act 2' }),
    ])
    expect(rows.map((row) => row.name)).toEqual(['blackout', 'Bows', 'Storm'])
    expect(rows[0]).toMatchObject({ number: 'Q12', stackName: 'Act 2' })
  })

  it('orders numbers inside names numerically, and ties by id', () => {
    const rows = cuePickerRows([
      cue({ id: 9, name: 'Scene 10' }),
      cue({ id: 4, name: 'Scene 2' }),
      cue({ id: 2, name: 'Scene 2' }),
    ])
    expect(rows.map((row) => row.id)).toEqual([2, 4, 9])
  })

  it('keeps a cue that names no stack, and one with no number', () => {
    const rows = cuePickerRows([cue({ id: 5, name: 'Loose', cueStackName: null }), cue({ id: 6, name: 'Bare', cueStackName: undefined })])
    expect(rows).toEqual([
      { id: 6, name: 'Bare', number: null, numberAuto: false, stackName: null },
      { id: 5, name: 'Loose', number: null, numberAuto: false, stackName: null },
    ])
  })

  it('leaves out a marker', () => {
    expect(cuePickerRows([cue({ id: 1, name: 'Interval', cueType: 'MARKER' })])).toEqual([])
  })

  it('carries whether the number was derived', () => {
    expect(cuePickerRows([cue({ id: 1, name: 'A', cueNumber: '3', cueNumberAuto: true })])[0].numberAuto).toBe(true)
  })
})

describe('filterCuePickerRows', () => {
  const rows = cuePickerRows([
    cue({ id: 1, name: 'Storm', cueNumber: '7', cueStackName: 'Act 1' }),
    cue({ id: 2, name: 'Bows', cueNumber: '30', cueStackName: 'Finale' }),
    cue({ id: 3, name: 'Loose wash', cueStackName: null }),
  ])

  it('matches the name, the number and the stack, ignoring case', () => {
    expect(filterCuePickerRows(rows, 'storm').map((r) => r.id)).toEqual([1])
    expect(filterCuePickerRows(rows, 'q30').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'FINALE').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'wash').map((r) => r.id)).toEqual([3])
  })

  it('keeps every row for a blank filter', () => {
    expect(filterCuePickerRows(rows, '  ')).toHaveLength(3)
  })
})
