import { describe, expect, it } from 'vitest'
import {
  isGoReleases,
  isNothingOnDeck,
  nextInRunningOrder,
  reorderWithinGroup,
  runningOrder,
  splitBuskStacks,
} from './runningOrder'

type Row = { id: number; type: 'STACK' | 'SEPARATOR'; inRunningOrder?: boolean }
const stack = (id: number, inRunningOrder?: boolean): Row => ({ id, type: 'STACK', inRunningOrder })
const sep = (id: number): Row => ({ id, type: 'SEPARATOR' })

describe('the running order', () => {
  it('is the stacks in it: separators and busk stacks are left out, and a desk without the flag counts every stack', () => {
    const rows = [stack(1), sep(2), stack(3, false), stack(4, true)]
    expect(runningOrder(rows).map((s) => s.id)).toEqual([1, 4])
  })

  it('crosses past busk stacks and ends at the last stack in it', () => {
    const rows = [stack(1), stack(2, false), stack(3)]
    expect(nextInRunningOrder(rows, 1)?.id).toBe(3)
    expect(nextInRunningOrder(rows, 3)).toBeNull()
    expect(nextInRunningOrder(rows, null)).toBeNull()
  })

  it('splits the list as the Show view draws it, keeping separators with the show', () => {
    const { show, busk } = splitBuskStacks([stack(1, false), stack(2), sep(3), stack(4)])
    expect(show.map((s) => s.id)).toEqual([2, 3, 4])
    expect(busk.map((s) => s.id)).toEqual([1])
  })
})

describe('reorderWithinGroup', () => {
  const ordered = [stack(1), sep(2), stack(3), stack(10, false), stack(11, false)]

  it('moves a stack within its group and answers the whole order', () => {
    expect(reorderWithinGroup(ordered, 3, 1)?.map((s) => s.id)).toEqual([3, 1, 2, 10, 11])
    expect(reorderWithinGroup(ordered, 11, 10)?.map((s) => s.id)).toEqual([1, 2, 3, 11, 10])
  })

  it('moves nothing across the divider', () => {
    expect(reorderWithinGroup(ordered, 10, 1)).toBeNull()
    expect(reorderWithinGroup(ordered, 3, 11)).toBeNull()
  })
})

describe('the GO questions', () => {
  it('nothing on deck: no next cue, or a stack released at its end', () => {
    expect(isNothingOnDeck(null, false)).toBe(true)
    expect(isNothingOnDeck(4, true)).toBe(true)
    expect(isNothingOnDeck(4, false)).toBe(false)
  })

  it('GO releases a live Release stack with nothing armed, never a looping or a stopped one', () => {
    expect(isGoReleases({ releaseAtEnd: true, loop: false }, 3, null)).toBe(true)
    expect(isGoReleases({ releaseAtEnd: true, loop: false }, 3, 4)).toBe(false)
    expect(isGoReleases({ releaseAtEnd: true, loop: false }, null, null)).toBe(false)
    expect(isGoReleases({ releaseAtEnd: false, loop: false }, 3, null)).toBe(false)
    expect(isGoReleases(undefined, 3, null)).toBe(false)
  })
})
