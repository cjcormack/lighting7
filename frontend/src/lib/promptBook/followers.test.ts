import { describe, expect, it } from 'vitest'
import { cueRow, markerRow } from '@/test/cueEntries'
import type { CueStack, CueStackCueEntry } from '../../api/cueStacksApi'
import vectors from '../../../../src/test/resources/promptBook/followHeads.fixture.json'
import { followLinks, followTails, sequenceScenery, sequenceStatus } from './followers'

interface VectorRow {
  id: number
  marker?: boolean
  autoAdvance?: boolean
  delayMs?: number
}

const stack = (cues: CueStackCueEntry[], over: Partial<CueStack> = {}) =>
  ({ id: 1, name: 'S', type: 'STACK', loop: false, cues, ...over }) as CueStack
const follows = (id: number, delayMs: number | null = 2000) =>
  cueRow(id, { autoAdvance: true, autoAdvanceDelayMs: delayMs })

describe('the shared vector', () => {
  const cases = vectors.cases as unknown as { name: string; rows: VectorRow[]; heads: Record<string, number> }[]

  it.each(cases)('$name', ({ rows, heads }) => {
    const cues = rows.map((row) =>
      row.marker
        ? markerRow(row.id)
        : cueRow(row.id, { entryId: row.id, autoAdvance: row.autoAdvance ?? false, autoAdvanceDelayMs: row.delayMs ?? null }),
    )
    const answered = [...followLinks([stack(cues)])].map(([entryId, link]) => [String(entryId), link.headEntryId])
    expect(Object.fromEntries(answered)).toEqual(heads)
  })
})

describe('followLinks', () => {
  it('links a chain to the cue that starts it, each delay from the cue before', () => {
    const links = followLinks([stack([cueRow(1), follows(2, 3000), follows(3, 8000), cueRow(4), cueRow(5)])])
    expect([...links]).toEqual([
      [103, { headEntryId: 102, afterLabel: 'Q2', delayMs: 3000 }],
      [104, { headEntryId: 102, afterLabel: 'Q3', delayMs: 8000 }],
    ])
  })

  it('takes a follow with no delay as none, which is how the desk runs it', () => {
    expect(followLinks([stack([follows(1, null), cueRow(2)])]).size).toBe(0)
  })

  it('counts a delay of nothing', () => {
    expect(followLinks([stack([follows(1, 0), cueRow(2)])]).get(102)?.delayMs).toBe(0)
  })

  it('reads across a marker', () => {
    expect(followLinks([stack([follows(1), markerRow(7), cueRow(2)])]).has(102)).toBe(true)
  })

  it('never makes a stack\'s first cue a follower, looping or not', () => {
    expect([...followLinks([stack([follows(1), follows(2)], { loop: true })]).keys()]).toEqual([102])
  })

  it('stops at the end of a stack rather than running into the next', () => {
    const links = followLinks([stack([follows(1)]), stack([cueRow(2)], { id: 2 })])
    expect(links.size).toBe(0)
  })
})

describe('followTails', () => {
  it('reads the links from the head, in play order', () => {
    const links = followLinks([stack([follows(1, 3000), follows(2, 8000), cueRow(3)])])
    const tails = followTails(links, new Map([[102, 'Q2'], [103, 'Q3']]))
    expect([...tails]).toEqual([[101, { entryIds: [102, 103], summary: 'Q2 · +3.0s\nQ3 · +8.0s' }]])
  })
})

describe('sequenceScenery', () => {
  const lines = new Map([[101, ['Tabs → open']], [102, ['Moon → in']]])
  const linesOf = (entryId: number) => lines.get(entryId) ?? []
  const labels = new Map([[102, 'Q2'], [103, 'Q3']])

  it('lists the head\'s changes, then each follower\'s by its cue', () => {
    const tail = { entryIds: [102, 103], summary: '' }
    expect(sequenceScenery(101, tail, linesOf, labels)).toBe('Tabs → open\nQ2: Moon → in')
  })

  it('draws for a head that moves nothing when a follower does', () => {
    expect(sequenceScenery(100, { entryIds: [102], summary: '' }, linesOf, labels)).toBe('Q2: Moon → in')
  })

  it('is nothing when nothing in the sequence moves', () => {
    expect(sequenceScenery(100, { entryIds: [103], summary: '' }, linesOf, labels)).toBeNull()
    expect(sequenceScenery(100, undefined, linesOf, labels)).toBeNull()
  })
})

describe('sequenceStatus', () => {
  it('is live while any cue in the sequence is', () => {
    expect(sequenceStatus(['done', 'live', 'standby'])).toBe('live')
  })

  it('is next when a cue in it is on deck and none is live', () => {
    expect(sequenceStatus(['done', 'next'])).toBe('next')
  })

  it('is done only when every cue is', () => {
    expect(sequenceStatus(['done', 'done'])).toBe('done')
    expect(sequenceStatus(['done', 'standby'])).toBe('standby')
  })
})
