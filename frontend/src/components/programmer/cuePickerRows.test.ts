import { describe, expect, it } from 'vitest'
import { cuePickerRows, filterCuePickerRows, type CuePickerSource } from './cuePickerRows'

/**
 * The "An existing cue…" picker's rows (venue-visit plan S10): every cue, flat and by name, labelled
 * with every entry naming it (cue model plan session 5) — and a cue with none, loose, is still a cue.
 */
type Place = { stackName: string; cueNumber: string | null; cueNumberAuto?: boolean }
const at = (stackName: string, cueNumber: string | null = null, cueNumberAuto = false): Place => ({ stackName, cueNumber, cueNumberAuto })
const cue = (over: Partial<CuePickerSource> & { id: number; name: string }): CuePickerSource => ({
  entries: [at('Act 1')],
  cueType: 'STANDARD',
  ...over,
})

describe('cuePickerRows', () => {
  it('lists every cue by name, whatever stack it is in', () => {
    const rows = cuePickerRows([
      cue({ id: 1, name: 'Storm', entries: [at('Act 1', '7')] }),
      cue({ id: 2, name: 'blackout', entries: [at('Act 2', '12')] }),
      cue({ id: 3, name: 'Bows', entries: [at('Act 2', '30')] }),
    ])
    expect(rows.map((row) => row.name)).toEqual(['blackout', 'Bows', 'Storm'])
    expect(rows[0].places).toEqual([{ number: 'Q12', numberAuto: false, stackName: 'Act 2' }])
  })

  it('orders numbers inside names numerically, and ties by id', () => {
    const rows = cuePickerRows([
      cue({ id: 9, name: 'Scene 10' }),
      cue({ id: 4, name: 'Scene 2' }),
      cue({ id: 2, name: 'Scene 2' }),
    ])
    expect(rows.map((row) => row.id)).toEqual([2, 4, 9])
  })

  it('keeps a loose cue, and one whose list row predates entries, with no places', () => {
    const rows = cuePickerRows([cue({ id: 5, name: 'Loose', entries: [] }), cue({ id: 6, name: 'Bare', entries: undefined })])
    expect(rows).toEqual([
      { id: 6, name: 'Bare', places: [] },
      { id: 5, name: 'Loose', places: [] },
    ])
  })

  it('names every place a shared cue sits, in show order', () => {
    const [row] = cuePickerRows([cue({ id: 1, name: 'Storm', entries: [at('Act 1', '7'), at('Act 2'), at('Act 2', '14', true)] })])
    expect(row.places).toEqual([
      { number: 'Q7', numberAuto: false, stackName: 'Act 1' },
      { number: null, numberAuto: false, stackName: 'Act 2' },
      { number: 'Q14', numberAuto: true, stackName: 'Act 2' },
    ])
  })

  it('leaves out a marker', () => {
    expect(cuePickerRows([cue({ id: 1, name: 'Interval', cueType: 'MARKER' })])).toEqual([])
  })
})

describe('filterCuePickerRows', () => {
  const rows = cuePickerRows([
    cue({ id: 1, name: 'Storm', entries: [at('Act 1', '7')] }),
    cue({ id: 2, name: 'Bows', entries: [at('Act 1', '29'), at('Finale', '30')] }),
    cue({ id: 3, name: 'Loose wash', entries: [] }),
  ])

  it('matches the name, and any place’s number or stack, ignoring case', () => {
    expect(filterCuePickerRows(rows, 'storm').map((r) => r.id)).toEqual([1])
    expect(filterCuePickerRows(rows, 'q30').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'FINALE').map((r) => r.id)).toEqual([2])
    expect(filterCuePickerRows(rows, 'act 1').map((r) => r.id)).toEqual([2, 1])
    expect(filterCuePickerRows(rows, 'wash').map((r) => r.id)).toEqual([3])
  })

  it('keeps every row for a blank filter', () => {
    expect(filterCuePickerRows(rows, '  ')).toHaveLength(3)
  })
})
