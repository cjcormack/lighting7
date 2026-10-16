import { describe, expect, it } from 'vitest'
import { describeIncludedTarget, includedCueEntry, includedCueId, includedTargetKey, includedTargetParts } from './includedTarget'
import { cueRow } from '@/test/cueEntries'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'
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

describe('includedCueEntry', () => {
  const stack = (id: number, name: string, cues: CueStackCueEntry[]): CueStack => ({
    id, name, loop: false, sortOrder: id, type: 'STACK', label: null, cues,
    activeCueId: null, nextCueId: null, activeEntryId: null, nextEntryId: null, standbyEntryId: null,
    canEdit: true, canDelete: true,
  })
  // Cue 5 is in Act 1 as Q2 and in Act 2 as Q9.
  const stacks = [
    stack(1, 'Act 1', [cueRow(4), cueRow(5, { cueNumber: '2' })]),
    stack(2, 'Act 2', [cueRow(5, { entryId: 205, cueNumber: '9' })]),
  ]

  it('finds the entry the cue was included from, for its number and stack', () => {
    const found = includedCueEntry({ kind: 'CUE', cueId: 5, entryId: 205 }, stacks)
    expect(found?.stack.name).toBe('Act 2')
    expect(found?.row.cueNumber).toBe('9')
  })

  it('falls back to the cue’s first place when the include named no entry', () => {
    expect(includedCueEntry({ kind: 'CUE', cueId: 5 }, stacks)?.row.cueNumber).toBe('2')
  })

  it('answers null for a cue in no stack, and undefined before the list has arrived', () => {
    expect(includedCueEntry({ kind: 'CUE', cueId: 99 }, stacks)).toBeNull()
    expect(includedCueEntry({ kind: 'CUE', cueId: 5 }, undefined)).toBeUndefined()
  })
})
