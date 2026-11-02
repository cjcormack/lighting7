import { describe, expect, it } from 'vitest'
import {
  NEW_STACK_MOVE_IN_DARK,
  darkCellOf,
  darkCellSentence,
  darkCellShows,
  familiesLabel,
  movingNow,
  parseSeconds,
  remainingLabel,
  stackDraftOf,
  stackDraftResult,
  stackModeLabel,
} from './moveInDark'
import type { MoveInDarkHead } from '@/api/moveInDarkApi'

const head = (over: Partial<MoveInDarkHead>): MoveInDarkHead => ({
  target: 'spot-1',
  entryId: 12,
  cueId: 12,
  label: 'Q12',
  state: 'SET',
  endsAtMs: null,
  families: ['POSITION'],
  position: null,
  ...over,
})

describe('moveInDark', () => {
  it('reads a delay as seconds, a bare number included, in 0–30 s', () => {
    expect(parseSeconds('1')).toBe(1000)
    expect(parseSeconds('2.5s')).toBe(2500)
    expect(parseSeconds('500ms')).toBe(500)
    expect(parseSeconds('0')).toBe(0)
    expect(parseSeconds('30')).toBe(30_000)
    expect(parseSeconds('31')).toBeUndefined()
    expect(parseSeconds('soon')).toBeUndefined()
    expect(parseSeconds('')).toBeUndefined()
  })

  it('round-trips a stack setting through its draft, and names every problem with one', () => {
    const draft = stackDraftOf({ ...NEW_STACK_MOVE_IN_DARK, families: ['BEAM', 'POSITION'] })
    expect(draft).toEqual({ on: true, when: 'EARLY', delay: '1s', time: '2s', families: ['POSITION', 'BEAM'] })
    expect(stackDraftResult(draft).value).toEqual({ ...NEW_STACK_MOVE_IN_DARK, families: ['POSITION', 'BEAM'] })
    const bad = stackDraftResult({ ...draft, delay: '40', time: 'x', families: [] })
    expect(bad.value).toBeNull()
    expect(Object.keys(bad.problems ?? {}).sort()).toEqual(['delay', 'families', 'time'])
  })

  it('says the stack setting a cue on Stack follows', () => {
    expect(stackModeLabel(NEW_STACK_MOVE_IN_DARK)).toBe('Stack · Early')
    expect(stackModeLabel({ ...NEW_STACK_MOVE_IN_DARK, when: 'LATE' })).toBe('Stack · Late')
    expect(stackModeLabel({ ...NEW_STACK_MOVE_IN_DARK, on: false })).toBe('Stack · Off')
    expect(familiesLabel(['BEAM', 'POSITION'])).toBe('Position · Beam')
  })

  it('counts a cue’s cell from its plan entry: set, lit and why, and Off off its reasons', () => {
    expect(darkCellShows(darkCellOf(undefined))).toBe(false)
    const cell = darkCellOf({
      entryId: 1,
      cueId: 1,
      heads: [{ target: 'a', fromEntryId: 0, families: ['POSITION'] }],
      live: [
        { target: 'b', reason: 'TOO_QUICK' },
        { target: 'c', reason: 'TOO_QUICK' },
        { target: 'd', reason: 'KEPT_LIVE' },
      ],
    })
    expect(cell).toEqual({ set: 1, lit: 3, reasons: ['TOO_QUICK', 'KEPT_LIVE'], off: false })
    expect(darkCellSentence(cell)).toBe('1 head set in the dark for this cue; 3 heads move while lit (too quick, kept live)')
    const off = darkCellOf({ entryId: 2, cueId: 2, heads: [], live: [{ target: 'a', reason: 'CUE_OFF' }] })
    expect(off.off).toBe(true)
    expect(darkCellSentence(off)).toBe('Off — 1 head moves lit in its fade')
    // The cue's own mode says Off whatever it lights.
    expect(darkCellShows(darkCellOf({ entryId: 3, cueId: 3, mode: 'OFF', heads: [], live: [] }))).toBe(true)
  })

  it('sums what a stack moves now: lit early apart, the soonest end, the first cue named', () => {
    const now = movingNow([
      head({ state: 'MOVING', endsAtMs: 2000 }),
      head({ target: 'spot-2', state: 'WAITING', endsAtMs: 1500, label: 'Q13' }),
      head({ target: 'spot-3', state: 'LIT_EARLY' }),
    ])
    expect(now).toEqual({ moving: 2, litEarly: 1, label: 'Q12', endsAtMs: 1500 })
    expect(remainingLabel(1500, 100)).toBe('1.4s')
    expect(remainingLabel(1500, 1600)).toBeNull()
  })
})
