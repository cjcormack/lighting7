// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { CueStack } from '@/api/cueStacksApi'
import { cueRow } from '@/test/cueEntries'

/** The show bar's next-stack label and its Start, read from the running order (busk-stack plan D2, D3). */
const state: { stacks: CueStack[]; activeStackId: number | null } = { stacks: [], activeStackId: null }
const transport = { standbyCueId: null as number | null, nothingOnDeck: true, goReleases: false }

vi.mock('../store/cueStacks', () => ({
  useProjectCueStackListQuery: () => ({ data: state.stacks }),
  useProjectProgramStateQuery: () => ({ data: { activeStackId: state.activeStackId, canEdit: true } }),
  useActivateProgramMutation: () => [vi.fn()],
  useDeactivateProgramMutation: () => [vi.fn()],
}))
vi.mock('./useShowTransport', () => ({
  useShowTransport: () => ({
    ...transport,
    activeStack: state.stacks.find((s) => s.id === state.activeStackId),
    activeCueId: null,
    fade: null,
    go: vi.fn(),
    back: vi.fn(),
    goDisabled: false,
  }),
}))

import { useShowBarProps } from './useShowBarProps'

const mkStack = (over: Partial<CueStack>): CueStack => ({
  id: 10,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  cues: [],
  activeCueId: null,
  nextCueId: null,
  activeEntryId: null,
  nextEntryId: null,
  standbyEntryId: null,
  canEdit: true,
  canDelete: true,
  ...over,
})

afterEach(() => {
  state.stacks = []
  state.activeStackId = null
  Object.assign(transport, { standbyCueId: null, nothingOnDeck: true, goReleases: false })
})

describe('useShowBarProps', () => {
  it('names the next stack in the running order once a Release stack has released at its end', () => {
    state.stacks = [
      mkStack({ id: 10, releaseAtEnd: true, cues: [cueRow(1, { name: 'Opening' })] }),
      mkStack({ id: 11, name: 'Song', sortOrder: 1, inRunningOrder: false }),
      mkStack({ id: 12, name: 'Act 2', sortOrder: 2 }),
    ]
    state.activeStackId = 10
    // Released: the desk names the stack's first cue as next, and nothing is on deck all the same.
    Object.assign(transport, { standbyCueId: 1, nothingOnDeck: true, goReleases: false })
    const { result } = renderHook(() => useShowBarProps(1))
    expect(result.current.nextStack?.name).toBe('Act 2')
    expect(result.current.standbyCue, 'not the first cue the desk names as next').toBeNull()
    expect(result.current.showBarProps.standbyName).toBe('→ Act 2')
  })

  it('names no next stack while GO would release the live one', () => {
    state.stacks = [mkStack({ id: 10, releaseAtEnd: true }), mkStack({ id: 12, name: 'Act 2', sortOrder: 1 })]
    state.activeStackId = 10
    Object.assign(transport, { standbyCueId: null, nothingOnDeck: true, goReleases: true })
    const { result } = renderHook(() => useShowBarProps(1))
    expect(result.current.nextStack).toBeNull()
  })

  it('cannot start a show of busk stacks alone', () => {
    state.stacks = [mkStack({ id: 10, inRunningOrder: false })]
    const { result } = renderHook(() => useShowBarProps(1))
    expect(result.current.showHeaderProps.canStart).toBe(false)

    state.stacks = [mkStack({ id: 10, inRunningOrder: false }), mkStack({ id: 11, sortOrder: 1 })]
    const { result: withShow } = renderHook(() => useShowBarProps(1))
    expect(withShow.current.showHeaderProps.canStart).toBe(true)
  })
})
