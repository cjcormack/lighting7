// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { Provider } from 'react-redux'
import { moveInDarkWs } from '@/test/backendMock'

// lightingApi opens a real WebSocket at import time (jsdom has none). The mock's `moveInDark`
// namespace remembers the subscriber, so `moveInDarkWs.fire` is a `moveInDark.state` frame.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { parseMoveInDarkFrame } from '@/api/moveInDarkApi'
import { store } from './index'
import { restApi } from './restApi'
import { useMoveInDarkHeads } from './moveInDark'

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

beforeEach(() => {
  moveInDarkWs.callback = null
  moveInDarkWs.last = { projectId: null, stacks: {} }
  store.dispatch(restApi.util.resetApiState())
})

describe('useMoveInDarkHeads', () => {
  it('seeds from the last frame and follows each one after, for its stack alone', async () => {
    moveInDarkWs.last = parseMoveInDarkFrame({
      projectId: 3,
      stacks: [{ stackId: 4, heads: [{ target: 'spot-3', entryId: 12, cueId: 40, label: 'Q12', state: 'WAITING', remainingMs: 600, families: ['POSITION'] }] }],
    })
    const { result } = renderHook(() => ({ four: useMoveInDarkHeads(4), five: useMoveInDarkHeads(5) }), { wrapper })
    await waitFor(() => expect(result.current.four.map((h) => h.state)).toEqual(['WAITING']))
    expect(result.current.five).toEqual([])

    await waitFor(() => expect(moveInDarkWs.callback).not.toBeNull())
    act(() =>
      moveInDarkWs.fire(
        parseMoveInDarkFrame({
          projectId: 3,
          stacks: [{ stackId: 4, heads: [{ target: 'spot-3', entryId: 12, cueId: 40, label: 'Q12', state: 'SET', families: ['POSITION'] }] }],
        }),
      ),
    )
    await waitFor(() => expect(result.current.four.map((h) => h.state)).toEqual(['SET']))

    act(() => moveInDarkWs.fire(parseMoveInDarkFrame({ projectId: 3, stacks: [] })))
    await waitFor(() => expect(result.current.four).toEqual([]))
  })
})
