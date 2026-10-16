// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import { cueHoldsWs, installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it (also stubs the cues /
// cueStacks WS subscriptions the store registers at module load).
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from './index'
import { restApi } from './restApi'
import { useCueRings } from './cueHolds'

/**
 * FS-BUG-CUESLOT-LIVENESS, and the cue model plan's holds: "is this cue on stage?" comes from
 * `cueHolds.state` — every cue a stack or a press holds — never from the FX effect stream. A rows-only
 * cue spawns no FxInstance, so an effect-derived answer reads it as never running; and a cue a pad
 * holds loose is live with no stack behind it, so the stacks' playheads are not the answer either.
 */
describe('active cue liveness', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({})
    cueHoldsWs.frame = { projectId: null, cues: [] }
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const wrapper = ({ children }: { children: ReactNode }) => <Provider store={store}>{children}</Provider>

  it('seeds from the last frame, with no effects anywhere', async () => {
    cueHoldsWs.frame = {
      projectId: 1,
      cues: [{ cueId: 7, priority: 1000, holds: [{ kind: 'STACK', stackId: 2, entryId: 70 }] }],
    }
    const cues = renderHook(() => useCueRings(1), { wrapper })
    await vi.waitFor(() => expect(cues.result.current.has(7)).toBe(true))
    expect(cues.result.current.has(9)).toBe(false)
  })

  it('follows cueHolds.state frames — a loose hold on, then off', async () => {
    const cues = renderHook(() => useCueRings(1), { wrapper })
    await vi.waitFor(() => expect(cueHoldsWs.callback).not.toBeNull())

    act(() => {
      cueHoldsWs.callback?.({ projectId: 1, cues: [{ cueId: 9, priority: 2_000_000_001, holds: [{ kind: 'LOOSE', seq: 1 }] }] })
    })
    await vi.waitFor(() => expect(cues.result.current.has(9)).toBe(true))

    act(() => {
      cueHoldsWs.callback?.({ projectId: 1, cues: [] })
    })
    await vi.waitFor(() => expect(cues.result.current.has(9)).toBe(false))
  })

  it('reads another project’s frame as nothing live', async () => {
    cueHoldsWs.frame = { projectId: 2, cues: [{ cueId: 7, priority: 1000, holds: [{ kind: 'LOOSE', seq: 1 }] }] }
    const cues = renderHook(() => useCueRings(1), { wrapper })
    await vi.waitFor(() => expect(cueHoldsWs.callback).not.toBeNull())
    expect(cues.result.current.size).toBe(0)
  })

  it('subscribes nothing without a project id', () => {
    const { result } = renderHook(() => useCueRings(undefined), { wrapper })
    expect(result.current.size).toBe(0)
  })
})

/** D11: a cue a press holds draws solid, one only a stack holds draws an outline. */
describe('cue rings', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch({})
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  const wrapper = ({ children }: { children: ReactNode }) => <Provider store={store}>{children}</Provider>

  it('is loose wherever a press holds the cue, stack where only stacks do', async () => {
    cueHoldsWs.frame = {
      projectId: 1,
      cues: [
        { cueId: 7, priority: 1000, holds: [{ kind: 'STACK', stackId: 2, entryId: 70 }] },
        {
          cueId: 8,
          priority: 2_000_000_002,
          holds: [
            { kind: 'STACK', stackId: 2, entryId: 80 },
            { kind: 'LOOSE', seq: 2 },
          ],
        },
      ],
    }
    const rings = renderHook(() => useCueRings(1), { wrapper })
    await vi.waitFor(() => expect(rings.result.current.size).toBe(2))
    expect(rings.result.current.get(7)).toBe('stack')
    expect(rings.result.current.get(8)).toBe('loose')
    expect(rings.result.current.get(9)).toBeUndefined()
  })
})
