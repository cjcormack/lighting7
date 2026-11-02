import { describe, expect, it } from 'vitest'
import type { StackMoveInDarkPlan } from '@/api/cueStacksApi'
import { darkColumn, darkSpanStartWords, NO_DARK_COLUMN } from './darkSpans'

/** A list of Q10, Q11, a marker, Q12, Q13 — entry ids 110, 111, 150, 112, 113. */
const ORDER = [110, 111, 150, 112, 113]

function plan(entries: StackMoveInDarkPlan['entries']): StackMoveInDarkPlan {
  return { stackId: 1, rigLoaded: true, entries, summary: { heads: 0, cues: 0, live: 0, off: 0 } }
}

const head = (target: string, fromEntryId: number) => ({ target, fromEntryId, families: ['POSITION' as const] })

describe('the Timeline’s Dark column', () => {
  it('draws an Early move from the cue it happens in, through the cues that keep it dark, to the cue it is for', () => {
    const column = darkColumn(plan([{ entryId: 113, cueId: 13, heads: [head('spot-1', 110), head('spot-2', 110)], live: [] }]), ORDER)
    expect(column.spans).toEqual([{ fromEntryId: 110, toEntryId: 113, toCueId: 13, heads: 2, lane: 0 }])
    expect(column.lanes).toBe(1)
    expect(ORDER.map((id) => column.byEntry.get(id)?.map((m) => [m.start, m.end]))).toEqual([
      [[true, false]],
      [[false, false]],
      [[false, false]],
      [[false, false]],
      [[false, true]],
    ])
  })

  it('draws a Late move over the one row before its cue, across a marker', () => {
    const column = darkColumn(plan([{ entryId: 112, cueId: 12, heads: [head('spot-1', 111)], live: [] }]), ORDER)
    expect(column.byEntry.get(110)).toBeUndefined()
    expect(column.byEntry.get(111)?.[0]).toMatchObject({ start: true, end: false })
    expect(column.byEntry.get(150)?.[0]).toMatchObject({ start: false, end: false })
    expect(column.byEntry.get(112)?.[0]).toMatchObject({ start: false, end: true })
    expect(column.byEntry.get(113)).toBeUndefined()
  })

  it('gives each move from another cue a span of its own, side by side where they share rows', () => {
    const column = darkColumn(
      plan([
        { entryId: 112, cueId: 12, heads: [head('a', 110), head('b', 111)], live: [] },
        { entryId: 113, cueId: 13, heads: [head('c', 112)], live: [] },
      ]),
      ORDER,
    )
    expect(column.spans.map((s) => [s.fromEntryId, s.toEntryId, s.lane])).toEqual([
      [110, 112, 0],
      [111, 112, 1],
      [112, 113, 2],
    ])
    expect(column.lanes).toBe(3)
    // Row 112 ends two moves and starts the third, so the third cannot share either's lane.
    expect(column.byEntry.get(112)?.map((m) => [m.span.lane, m.start, m.end])).toEqual([
      [0, false, true],
      [1, false, true],
      [2, true, false],
    ])
    expect(column.byEntry.get(113)?.map((m) => m.span.lane)).toEqual([2])
  })

  it('draws a move round the end of a looping list as two segments', () => {
    const column = darkColumn(plan([{ entryId: 110, cueId: 10, heads: [head('a', 113)], live: [] }]), ORDER)
    expect(ORDER.map((id) => column.byEntry.get(id)?.map((m) => [m.start, m.end]) ?? null)).toEqual([
      [[false, true]],
      null,
      null,
      null,
      [[true, false]],
    ])
  })

  it('draws nothing off the current project, and leaves out an entry the list lacks', () => {
    expect(darkColumn({ ...plan([{ entryId: 113, cueId: 13, heads: [head('a', 110)], live: [] }]), rigLoaded: false }, ORDER)).toBe(NO_DARK_COLUMN)
    expect(darkColumn(undefined, ORDER)).toBe(NO_DARK_COLUMN)
    expect(darkColumn(plan([{ entryId: 999, cueId: 9, heads: [head('a', 110)], live: [] }]), ORDER).spans).toEqual([])
  })

  it('says where a move starts', () => {
    expect(darkSpanStartWords({ fromEntryId: 110, toEntryId: 112, toCueId: 12, heads: 4, lane: 0 }, 'Q12')).toBe('☾ 4 → Q12')
  })
})
