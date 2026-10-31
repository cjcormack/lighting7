import { describe, expect, it, vi } from 'vitest'
import { fakeWsConnection } from '../test/fakeWsConnection'
import {
  createMoveInDarkWsApi,
  moveInDarkHeadsOf,
  moveInDarkRemainingMs,
  NOTHING_MOVING,
  parseMoveInDarkFrame,
} from './moveInDarkApi'

const frame = {
  type: 'moveInDark.state',
  projectId: 3,
  stacks: [
    {
      stackId: 4,
      heads: [
        { target: 'spot-3', entryId: 12, cueId: 40, label: 'Q12', state: 'MOVING', remainingMs: 800, families: ['POSITION', 'BEAM'], position: { pan: 142, tilt: 88 } },
        { target: 'spot-4', entryId: 12, cueId: 40, label: 'Q12', state: 'SET', families: ['COLOUR'] },
      ],
    },
  ],
}

describe('moveInDark.state', () => {
  it('reads each head, anchoring the time left to when the frame arrived', () => {
    const state = parseMoveInDarkFrame(frame, 10_000)
    expect(state.projectId).toBe(3)
    const [moving, set] = moveInDarkHeadsOf(state, 4)
    expect(moving).toEqual({
      target: 'spot-3',
      entryId: 12,
      cueId: 40,
      label: 'Q12',
      state: 'MOVING',
      endsAtMs: 10_800,
      families: ['POSITION', 'BEAM'],
      position: { pan: 142, tilt: 88 },
    })
    expect(moveInDarkRemainingMs(moving, 10_300)).toBe(500)
    expect(moveInDarkRemainingMs(moving, 12_000)).toBe(0)
    expect(set.endsAtMs).toBeNull()
    expect(set.position).toBeNull()
    expect(moveInDarkRemainingMs(set, 10_300)).toBeNull()
  })

  it('reads a stack absent, a frame with no list, or nothing at all as nothing moving', () => {
    expect(moveInDarkHeadsOf(parseMoveInDarkFrame(frame), 5)).toEqual([])
    expect(parseMoveInDarkFrame({ projectId: 3 })).toEqual({ projectId: 3, stacks: {} })
    expect(parseMoveInDarkFrame(null)).toBe(NOTHING_MOVING)
  })

  it('drops a head it cannot read rather than the frame', () => {
    const state = parseMoveInDarkFrame({
      projectId: 3,
      stacks: [{ stackId: 4, heads: [{ target: 'spot-3', entryId: 12, cueId: 40, state: 'SOMETHING' }, frame.stacks[0].heads[1]] }],
    })
    expect(moveInDarkHeadsOf(state, 4).map((h) => h.target)).toEqual(['spot-4'])
  })

  it('streams the frame to a late subscriber, and ignores every other frame', () => {
    const { conn, frame: deliver } = fakeWsConnection()
    const api = createMoveInDarkWsApi(conn)
    expect(api.get()).toBe(NOTHING_MOVING)

    deliver({ type: 'stackFxAmount.state', projectId: 3, stacks: [] })
    expect(api.get()).toBe(NOTHING_MOVING)

    deliver(frame)
    const seen = vi.fn()
    api.subscribe(seen)
    expect(seen).toHaveBeenCalledTimes(1)
    expect(moveInDarkHeadsOf(seen.mock.calls[0][0], 4)).toHaveLength(2)

    deliver({ type: 'moveInDark.state', projectId: 3, stacks: [] })
    expect(seen).toHaveBeenLastCalledWith({ projectId: 3, stacks: {} })
  })
})
