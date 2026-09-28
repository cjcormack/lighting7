// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import {
  cueRunStateWs,
  installRecordingFetch,
  installRelativeUrlRequest,
} from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it (also stubs the cues /
// cueStacks WS subscriptions the store registers at module load).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { useActiveCueIds } from './cues'
import type { CueStack } from '../api/cueStacksApi'

function stack(id: number, activeCueId: number | null): CueStack {
  return {
    id,
    name: `Stack ${id}`,
    loop: false,
    sortOrder: id,
    type: 'STACK',
    label: null,
    cues: [],
    activeCueId,
    nextCueId: null,
    canEdit: true,
    canDelete: true,
  }
}

/**
 * FS-BUG-CUESLOT-LIVENESS: "is this cue/stack on stage?" must come from the playhead
 * (`CueStack.activeCueId`, kept live by `cueRunStateChanged`), never from the FX effect stream —
 * a rows-only cue spawns no FxInstance, so an effect-derived answer reads it as never running and
 * the slot pad re-fires instead of stopping. These tests drive the hooks with a stack list and a
 * run-state frame only; no effect ever exists, which is exactly the case the old derivation missed.
 */
describe('active cue / stack liveness', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({
      'projects/1/cue-stacks': [stack(2, 7), stack(3, null)],
    })
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const wrapper = ({ children }: { children: ReactNode }) => (
    <Provider store={store}>{children}</Provider>
  )

  it('derives liveness from the stack list, with no effects anywhere', async () => {
    const cues = renderHook(() => useActiveCueIds(1), { wrapper })

    await vi.waitFor(() => expect(cues.result.current.has(7)).toBe(true))
    expect(cues.result.current.has(9)).toBe(false)
  })

  it('follows cueRunStateChanged frames — the rows-only fire/stop path', async () => {
    const cues = renderHook(() => useActiveCueIds(1), { wrapper })
    await vi.waitFor(() => expect(cues.result.current.has(7)).toBe(true))

    // A rows-only cue fires on stack 3: the only signal is the run-state frame.
    act(() => {
      cueRunStateWs.callback?.({
        projectId: 1,
        stackId: 3,
        activeCueId: 9,
        nextCueId: null,
        nextIsArmed: false,
        transition: true,
        fadeDurationMs: null,
        fadeElapsedMs: null,
        autoAdvance: false,
        autoAdvanceDelayMs: null,
      })
    })
    await vi.waitFor(() => expect(cues.result.current.has(9)).toBe(true))

    // And its stop is a frame with a null active cue.
    act(() => {
      cueRunStateWs.callback?.({
        projectId: 1,
        stackId: 3,
        activeCueId: null,
        nextCueId: null,
        nextIsArmed: false,
        transition: false,
        fadeDurationMs: null,
        fadeElapsedMs: null,
        autoAdvance: false,
        autoAdvanceDelayMs: null,
      })
    })
    await vi.waitFor(() => expect(cues.result.current.has(9)).toBe(false))
  })

  it('subscribes nothing without a project id', () => {
    const { result } = renderHook(() => useActiveCueIds(undefined), { wrapper })
    expect(result.current.size).toBe(0)
  })
})
