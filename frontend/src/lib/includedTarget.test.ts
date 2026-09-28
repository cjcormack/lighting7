import { describe, expect, it } from 'vitest'
import { describeIncludedTarget, includedCueId, includedTargetKey, includedTargetParts } from './includedTarget'
import type { IncludedTarget } from '@/api/programmerWsApi'

const CUE: IncludedTarget = { kind: 'CUE', cueId: 4, cueNumber: '2.1', cueName: 'Warm wash' }
const LOOK: IncludedTarget = { kind: 'LOOK', lookId: 7, lookName: 'Warm Amber' }

describe('describeIncludedTarget', () => {
  it('names a cue by number and name', () => {
    expect(describeIncludedTarget(CUE)).toBe('2.1 · Warm wash')
  })

  it('names a look', () => {
    expect(describeIncludedTarget(LOOK)).toBe('Warm Amber')
  })

  it('falls back to the id when the server sent no name', () => {
    expect(describeIncludedTarget({ kind: 'CUE', cueId: 9 })).toBe('Cue 9')
    expect(describeIncludedTarget({ kind: 'LOOK', lookId: 9 })).toBe('Look 9')
  })

  // A fourth case stood here pinning that a legacy `PALETTE` target still got a name. It certified
  // the drift rather than catching it: `IncludedTargetDto` carries no `palette*` fields any more,
  // so the payload it fed is one the server cannot emit, and the arm it exercised could only ever
  // have rendered "Palette undefined" against a real frame.
})

describe('includedTargetKey', () => {
  it('gives the three states three distinct keys', () => {
    // This feeds UpdateDialog's once-per-run guard. Two states sharing a key leaves the dialog
    // showing a stale frame — the Mode B case previously rendered no checklist behind a
    // permanently disabled button.
    const keys = [includedTargetKey(null), includedTargetKey(CUE), includedTargetKey(LOOK)]
    expect(new Set(keys).size).toBe(3)
  })

  it("doesn't collide a cue and a look that share an id", () => {
    // The ids come from different tables, so sharing a number is normal rather than a corner case.
    expect(includedTargetKey({ kind: 'CUE', cueId: 3 })).not.toBe(
      includedTargetKey({ kind: 'LOOK', lookId: 3 }),
    )
  })
})

describe('includedCueId', () => {
  it('narrows to the cue arm only', () => {
    expect(includedCueId(CUE)).toBe(4)
    expect(includedCueId(LOOK)).toBeNull()
    expect(includedCueId(null)).toBeNull()
  })
})

describe('includedTargetParts', () => {
  it('splits a cue into its number and its name', () => {
    expect(includedTargetParts({ kind: 'CUE', cueId: 5, cueNumber: 'Q3', cueName: 'Warm Wash' }))
      .toEqual({ number: 'Q3', name: 'Warm Wash' })
  })

  it('omits the name for a numbered cue that has none', () => {
    // Not `Cue 5`: "Q3 · Cue 5" would name one cue twice, in two vocabularies. This is the case
    // that makes `describeIncludedTarget` a join of the parts rather than a template.
    expect(includedTargetParts({ kind: 'CUE', cueId: 5, cueNumber: 'Q3' }))
      .toEqual({ number: 'Q3', name: undefined })
    expect(describeIncludedTarget({ kind: 'CUE', cueId: 5, cueNumber: 'Q3' })).toBe('Q3')
  })

  it('falls back to the id only when there is nothing else', () => {
    expect(includedTargetParts({ kind: 'CUE', cueId: 5 })).toEqual({
      number: undefined,
      name: 'Cue 5',
    })
  })

  it('gives a Look its name and no number', () => {
    expect(includedTargetParts({ kind: 'LOOK', lookId: 2, lookName: 'Amber Key' })).toEqual({
      name: 'Amber Key',
    })
  })
})
