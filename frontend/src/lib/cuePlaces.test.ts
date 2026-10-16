import { describe, expect, it } from 'vitest'
import { cueRow, markerRow } from '@/test/cueEntries'
import type { CueStack, CueStackCueEntry } from '@/api/cueStacksApi'
import { cuePlaceLabel, cuePlacesByCue, sharedCueLabel } from './cuePlaces'

function stack(id: number, name: string, sortOrder: number, cues: CueStackCueEntry[]): CueStack {
  return {
    id, name, loop: false, sortOrder, type: 'STACK', label: null, cues,
    activeCueId: null, nextCueId: null, activeEntryId: null, nextEntryId: null, standbyEntryId: null,
    canEdit: true, canDelete: true,
  }
}

describe('cuePlacesByCue', () => {
  it('lists every entry of a cue in show order, markers left out', () => {
    const places = cuePlacesByCue([
      stack(2, 'Act 2', 1, [cueRow(5, { entryId: 205, cueNumber: '9' })]),
      stack(1, 'Act 1', 0, [cueRow(5, { entryId: 105, cueNumber: '1' }), markerRow(150), cueRow(6)]),
    ])
    expect(places.get(5)).toEqual([
      { stackId: 1, stackName: 'Act 1', entryId: 105, cueNumber: '1' },
      { stackId: 2, stackName: 'Act 2', entryId: 205, cueNumber: '9' },
    ])
    expect(places.get(6)).toHaveLength(1)
  })
})

describe('sharedCueLabel', () => {
  const at = (stackId: number, entryId: number) => ({ stackId, stackName: `S${stackId}`, entryId, cueNumber: null })

  it('says nothing for a cue in one place', () => {
    expect(sharedCueLabel([at(1, 101)])).toBeNull()
    expect(sharedCueLabel(undefined)).toBeNull()
  })

  it('counts stacks, and a cue twice in one stack apart', () => {
    expect(sharedCueLabel([at(1, 101), at(2, 201), at(2, 202)])).toBe('in 2 stacks')
    expect(sharedCueLabel([at(1, 101), at(1, 102)])).toBe('×2 in stack')
  })

  it('names a place by its stack and number', () => {
    expect(cuePlaceLabel({ stackId: 1, stackName: 'Act 1', entryId: 101, cueNumber: '14' })).toBe('Act 1 · Q14')
    expect(cuePlaceLabel(at(1, 101))).toBe('S1')
  })
})
