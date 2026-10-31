// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, renderHook, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'
import { cuesWs, failWith, installRecordingFetch, installRelativeUrlRequest } from '@/test/backendMock'

/**
 * Every call to a writer of the rig, by namespace and member. The three namespaces a stage source
 * could reach the rig through are recording proxies: their subscriptions answer as no-ops, and
 * anything else called on them is a write this source must never make.
 */
const writes = vi.hoisted(() => [] as string[])

vi.mock('@/api/lightingApi', async () => {
  const { lightingApiMock } = await import('@/test/backendMock')
  const mock = lightingApiMock()
  const recording = (ns: string) =>
    new Proxy({} as Record<string, unknown>, {
      get: (_target, prop: string) =>
        (...args: unknown[]) => {
          if (/^(subscribe|get|request|is)/.test(prop)) return { unsubscribe: () => {} }
          writes.push(`${ns}.${prop}`)
          void args
          return undefined
        },
    })
  const channels = recording('channels')
  const fx = recording('fx')
  const programmer = recording('programmer')
  return {
    lightingApi: new Proxy(mock.lightingApi, {
      get: (target, prop: string) =>
        prop === 'channels' ? channels : prop === 'fx' ? fx : prop === 'programmer' ? programmer : Reflect.get(target, prop),
    }),
  }
})

import { store } from '../store'
import { restApi } from '../store/restApi'
import { resetVisSourceStore, showCueOnStage } from './useVisSource'
import { EDIT_REFETCH_DEBOUNCE_MS, useEditCueSourceState, useEditCueStatus } from './useEditCuePreview'
import { StageChannelSourceProvider } from './useChannelSource'

const CUES = [
  { id: 11, entryId: 110, name: 'Preset', cueNumber: '1', cueNumberAuto: false },
  { id: 17, entryId: 170, name: 'Storm', cueNumber: '7', cueNumberAuto: false },
]

const stack = {
  id: 7,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  activeCueId: 11,
  activeEntryId: 110,
  standbyCueId: null,
  standbyEntryId: null,
  nextCueId: 17,
  nextEntryId: 170,
  canEdit: true,
  canDelete: true,
  cues: CUES,
}

function routes() {
  return {
    'projects/1/cue-stacks/7/preview': { cueId: 17, channels: [{ universe: 0, channel: 1, value: 200 }], skipped: [] },
    'projects/1/cues/40/preview': { cueId: 40, channels: [{ universe: 0, channel: 2, value: 90 }], skipped: [] },
    'projects/1/cue-stacks': [stack],
    'projects/1/cues': [{ id: 40, name: 'Thunder', entries: [] }],
    'projects/current': { id: 1, name: 'Show', isCurrent: true },
    fixtures: [],
    'fixture-types': [],
  }
}

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

describe('the cue in edit vis source', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    resetVisSourceStore()
    writes.length = 0
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch(routes())
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
    resetVisSourceStore()
  })

  const requests = (fragment: string) =>
    fetchMock.mock.calls.map((c) => c[0] as Request).filter((r) => r.url.includes(fragment))

  it('previews an entry in its stack, found from the stack list', async () => {
    showCueOnStage({ kind: 'entry', entryId: 170 })
    const { result } = renderHook(() => useEditCueSourceState(true), { wrapper })
    await waitFor(() => expect(result.current.source?.holds('0:1')).toBe(true))
    expect(result.current.source!.getByKey('0:1')).toBe(200)
    expect(new URL(requests('/cue-stacks/7/preview')[0].url).searchParams.get('entryId')).toBe('170')
  })

  it('previews a cue with no entry on its own route, as a loose press', async () => {
    showCueOnStage({ kind: 'cue', cueId: 40 })
    const { result } = renderHook(() => useEditCueSourceState(true), { wrapper })
    await waitFor(() => expect(result.current.source?.holds('0:2')).toBe(true))
    expect(requests('/cues/40/preview')).toHaveLength(1)
    expect(requests('/cue-stacks/7/preview')).toHaveLength(0)
  })

  it('names the cue it is showing, and says when the show on the rig lacks it', async () => {
    showCueOnStage({ kind: 'entry', entryId: 170 })
    const { result } = renderHook(() => useEditCueStatus(true), { wrapper })
    await waitFor(() => expect(result.current).toBe('Showing Q7 · Storm as stored — nothing is sent.'))
    act(() => showCueOnStage({ kind: 'entry', entryId: 999 }))
    await waitFor(() => expect(result.current).toMatch(/isn’t in the show on the rig/))
  })

  it('refetches once per flush, debounced, and on a recompose naming the cue', async () => {
    const pause = (ms: number) => act(() => new Promise((r) => setTimeout(r, ms)))
    showCueOnStage({ kind: 'entry', entryId: 170 })
    renderHook(() => useEditCueSourceState(true), { wrapper })
    await waitFor(() => expect(requests('/cue-stacks/7/preview')).toHaveLength(1))

    // A flush: the desk's frames arrive close together, then nothing for a while.
    act(() => {
      cuesWs.listChanged()
      cuesWs.listChanged()
    })
    await pause(EDIT_REFETCH_DEBOUNCE_MS / 2)
    act(() => cuesWs.listChanged())
    await pause(EDIT_REFETCH_DEBOUNCE_MS / 2)
    expect(requests('/cue-stacks/7/preview')).toHaveLength(1)
    await waitFor(() => expect(requests('/cue-stacks/7/preview')).toHaveLength(2))
    await pause(EDIT_REFETCH_DEBOUNCE_MS + 50)
    expect(requests('/cue-stacks/7/preview')).toHaveLength(2)

    // A Look under another cue changed: not this one's to refetch.
    act(() => cuesWs.recomposedFor([11]))
    await pause(EDIT_REFETCH_DEBOUNCE_MS + 50)
    expect(requests('/cue-stacks/7/preview')).toHaveLength(2)
    act(() => cuesWs.recomposedFor([17]))
    await waitFor(() => expect(requests('/cue-stacks/7/preview')).toHaveLength(3))
  })

  it('asks again once a slow preview settles, when an edit lands while it is in flight', async () => {
    // On the fake clock, and with each preview answered only when the test lets it go, so the
    // second edit's timer is certain to fire while the request it should wait for is pending.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    try {
      const advance = (ms: number) => act(() => vi.advanceTimersByTimeAsync(ms))
      const held: (() => void)[] = []
      const recording = fetchMock
      vi.stubGlobal(
        'fetch',
        vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
          const answer = recording(input, init)
          const url = input instanceof Request ? input.url : String(input)
          if (!url.includes('/preview')) return answer
          return answer.then((r) => new Promise<Response>((done) => held.push(() => done(r))))
        }),
      )
      const release = () => act(async () => held.splice(0).forEach((done) => done()))
      const previews = () => requests('/cue-stacks/7/preview')

      showCueOnStage({ kind: 'entry', entryId: 170 })
      renderHook(() => useEditCueSourceState(true), { wrapper })
      await advance(50)
      expect(previews()).toHaveLength(1)
      await release()
      await advance(10)

      act(() => cuesWs.listChanged())
      await advance(EDIT_REFETCH_DEBOUNCE_MS + 10)
      expect(previews()).toHaveLength(2)

      act(() => cuesWs.listChanged())
      await advance(EDIT_REFETCH_DEBOUNCE_MS + 10)
      expect(held).toHaveLength(1)
      expect(previews()).toHaveLength(2)

      await release()
      await advance(10)
      expect(previews()).toHaveLength(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('says the preview is unavailable when the stack list cannot be read, not that the cue is gone', async () => {
    vi.unstubAllGlobals()
    installRelativeUrlRequest()
    installRecordingFetch({ ...routes(), 'projects/1/cue-stacks': failWith(500) })
    showCueOnStage({ kind: 'entry', entryId: 170 })
    const { result } = renderHook(() => useEditCueStatus(true), { wrapper })
    await waitFor(() => expect(result.current).toBe('Preview unavailable — showing output.'))
  })

  it('draws only: nothing it does reaches output', async () => {
    showCueOnStage({ kind: 'entry', entryId: 170 })
    let settled = false
    render(
      <Provider store={store}>
        <StageChannelSourceProvider onSettled={(s) => (settled = s)}>
          <span />
        </StageChannelSourceProvider>
      </Provider>,
    )
    await waitFor(() => expect(settled).toBe(true))
    act(() => cuesWs.listChanged())
    await waitFor(() => expect(requests('/cue-stacks/7/preview')).toHaveLength(2), { timeout: 2000 })

    expect(writes).toEqual([])
    const methods = fetchMock.mock.calls.map((c) => (c[0] as Request).method)
    expect(methods.every((m) => m === 'GET')).toBe(true)
  })

  it('asks nothing while another source is drawn', async () => {
    showCueOnStage({ kind: 'entry', entryId: 170 })
    renderHook(() => useEditCueSourceState(false), { wrapper })
    act(() => cuesWs.listChanged())
    await new Promise((r) => setTimeout(r, EDIT_REFETCH_DEBOUNCE_MS + 50))
    expect(requests('/preview')).toHaveLength(0)
  })
})
