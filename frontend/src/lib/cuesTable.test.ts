import { describe, expect, it } from 'vitest'
import type { Cue, CueEntryPlace } from '@/api/cuesApi'
import {
  DEFAULT_CUES_VIEW,
  cuesSearch,
  cuesShown,
  cuesTablePath,
  cueOpenPath,
  cuesViewFromSearch,
  describeUsedBy,
  filterBeforeShow,
  showCounts,
} from './cuesTable'

/**
 * The Cues table's view (cue model plan §3.7): filters, chips and sort, all of them the address.
 */
const place = (stackId: number, stackName: string, entryId: number, cueNumber: string | null = null): CueEntryPlace => ({
  entryId,
  stackId,
  stackName,
  sortOrder: 0,
  cueNumber,
  cueNumberAuto: false,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
})

function cue(id: number, name: string, over: Partial<Cue> = {}): Cue {
  return {
    id,
    name,
    layers: [],
    adHocFx: [],
    attributeAssignments: [],
    triggers: [],
    cueStackId: null,
    cueStackName: null,
    sortOrder: 0,
    autoAdvance: false,
    autoAdvanceDelayMs: null,
    fadeDurationMs: null,
    fadeCurve: 'LINEAR',
    cueNumber: null,
    cueNumberAuto: false,
    entries: [],
    notes: null,
    cueType: 'STANDARD',
    canEdit: true,
    canDelete: true,
    ...over,
  }
}

const CUES = [
  cue(1, 'Opener', { entries: [place(7, 'Act 1', 101, '1')], fadeDurationMs: 3000 }),
  cue(2, 'Storm', { entries: [place(7, 'Act 1', 102, '7'), place(8, 'Act 2', 201, '14')], notes: 'thunder' }),
  cue(3, 'Wash', { usedBy: { pads: [{ padId: 1, pageId: 1, pageName: 'Colours' }], slots: [{ slotId: 2, label: 'Page 1, slot 3' }] } }),
  cue(4, 'Bows', { entries: [place(8, 'Act 2', 202, '30')], usedBy: { pads: [{ padId: 5, pageId: 1, pageName: 'Colours' }] } }),
]

const ids = (cues: readonly Cue[]) => cues.map((c) => c.id)

describe('the view in the address', () => {
  it('round-trips, writing only what differs from the default', () => {
    const view = { q: 'storm', stack: 7, show: 'shared' as const, cue: 2, sort: 'fade', dir: 'desc' as const }
    expect(cuesSearch(view)).toBe('?q=storm&stack=7&show=shared&cue=2&sort=fade&dir=desc')
    expect(cuesViewFromSearch(new URLSearchParams(cuesSearch(view)))).toEqual(view)
    expect(cuesSearch(DEFAULT_CUES_VIEW)).toBe('')
  })

  it('ignores what it cannot read', () => {
    expect(cuesViewFromSearch(new URLSearchParams('?stack=x&show=all&cue=-3&dir=up'))).toEqual(DEFAULT_CUES_VIEW)
  })

  it('opens a loose cue as the table filtered to it', () => {
    expect(cuesTablePath(9, 3)).toBe('/projects/9/cues?cue=3')
    expect(cuesTablePath(9)).toBe('/projects/9/cues')
  })

  it('opens a cue from a busk pad at its first entry, or loose in the table filtered to it', () => {
    expect(cueOpenPath(9, CUES[1])).toBe('/projects/9/show/stacks/7?cue=2&entry=102')
    expect(cueOpenPath(9, CUES[2])).toBe('/projects/9/cues?cue=3')
    expect(cueOpenPath(9, { id: 5 })).toBe('/projects/9/cues?cue=5')
  })
})

describe('the filters', () => {
  it('narrows by name, notes, a stack or a number', () => {
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, q: 'thunder' }))).toEqual([2])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, q: 'act 2' }))).toEqual([2, 4])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, q: 'q14' }))).toEqual([2])
  })

  it('keeps one stack’s cues, a shared one included', () => {
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, stack: 7 }))).toEqual([1, 2])
  })

  it('Loose, Shared and On a pad each keep their own', () => {
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, show: 'loose' }))).toEqual([3])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, show: 'shared' }))).toEqual([2])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, show: 'pad' }))).toEqual([3, 4])
  })

  it('counts the chips over the rest of the filters', () => {
    expect(showCounts(CUES)).toEqual({ loose: 1, shared: 1, pad: 2 })
    expect(showCounts(filterBeforeShow(CUES, { ...DEFAULT_CUES_VIEW, stack: 8 }))).toEqual({ loose: 0, shared: 1, pad: 1 })
  })

  it('keeps one cue for `?cue=`', () => {
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, cue: 3 }))).toEqual([3])
  })

  it('sorts by a column, nulls last either way', () => {
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, sort: 'fade', dir: 'desc' }))).toEqual([1, 2, 3, 4])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, sort: 'in' }))).toEqual([1, 2, 4, 3])
    expect(ids(cuesShown(CUES, { ...DEFAULT_CUES_VIEW, sort: 'in', dir: 'desc' }))).toEqual([4, 2, 1, 3])
  })
})

describe('Used by', () => {
  it('counts pads, slots and bindings, and is null for a cue nothing else names', () => {
    expect(describeUsedBy(CUES[2])).toBe('1 pad · 1 slot')
    expect(describeUsedBy(CUES[0])).toBeNull()
  })
})
