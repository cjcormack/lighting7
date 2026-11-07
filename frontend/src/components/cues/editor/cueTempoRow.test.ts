import { describe, expect, it } from 'vitest'
import { withTempoRow } from './cueListEdits'

/** The cue FX tab's Tap on a master writes that master's row of the cue's Tempo on GO (fx-speed plan D8). */
describe('withTempoRow', () => {
  const rows = [
    { speedMasterUuid: 'm1', bpm: 120 },
    { speedMasterUuid: 'm2', bpm: 90 },
  ]

  it('replaces the master’s row in place', () => {
    expect(withTempoRow(rows, 'm2', 132)).toEqual([
      { speedMasterUuid: 'm1', bpm: 120 },
      { speedMasterUuid: 'm2', bpm: 132 },
    ])
  })

  it('adds a row for a master the cue does not set yet', () => {
    expect(withTempoRow(rows, 'm3', 64)).toEqual([...rows, { speedMasterUuid: 'm3', bpm: 64 }])
  })
})
