// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import type { ReactNode } from 'react'
import {
  cueRunStateWs,
  failWith,
  installRecordingFetch,
  installRelativeUrlRequest,
} from '@/test/backendMock'

// lightingApi opens a real WebSocket at import; mock it before the store pulls it in.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

import { store } from '../store'
import { restApi } from '../store/restApi'
import { useNextGoSource, useNextGoStatus } from './useNextGoPreview'

/**
 * The Next GO source follows the *server's* next cue. These cover the two things that go wrong
 * quietly: not re-requesting when the desk moves on, and emptying the source when there is
 * nothing on deck (which must show plain output rather than blacking the stage out).
 */

const CUES = [
  { id: 11, name: 'Preset', cueNumber: '1', cueNumberAuto: false },
  { id: 12, name: 'Blackout', cueNumber: '2', cueNumberAuto: false },
]

const stack = (nextCueId: number | null) => ({
  id: 7,
  name: 'Act 1',
  loop: false,
  sortOrder: 0,
  type: 'STACK',
  label: null,
  activeCueId: 10,
  standbyCueId: null,
  nextCueId,
  canEdit: true,
  canDelete: true,
  cues: CUES,
})

function wrapper({ children }: { children: ReactNode }) {
  return <Provider store={store}>{children}</Provider>
}

function routes(over: Record<string, unknown> = {}) {
  return {
    'projects/1/cue-stacks/7/preview': {
      cueId: 11,
      channels: [{ universe: 0, channel: 1, value: 255 }],
      skipped: [],
    },
    'projects/1/cue-stacks': [stack(11)],
    'projects/1/show': { projectId: 1, activeStackId: 7, canEdit: true },
    'projects/current': { id: 1, name: 'Show', isCurrent: true },
    ...over,
  }
}

const frame = (over: Record<string, unknown> = {}) => ({
  projectId: 1,
  stackId: 7,
  activeCueId: 11,
  nextCueId: 12,
  nextIsArmed: false,
  transition: true,
  fadeDurationMs: null,
  fadeElapsedMs: null,
  autoAdvance: false,
  autoAdvanceDelayMs: null,
  ...over,
})

describe('useNextGoSource', () => {
  let fetchMock: ReturnType<typeof installRecordingFetch>

  beforeEach(() => {
    installRelativeUrlRequest()
    fetchMock = installRecordingFetch(routes())
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  function previewRequests(): Request[] {
    return fetchMock.mock.calls
      .map((c) => c[0] as Request)
      .filter((r) => r.url.includes('/preview'))
  }

  it('holds the channels the previewed cue asserts, and nothing else', async () => {
    const { result } = renderHook(() => useNextGoSource(true), { wrapper })

    await waitFor(() => expect(result.current?.holds('0:1')).toBe(true))
    expect(result.current!.getByKey('0:1')).toBe(255)
    // Absent, not zero — the overlay above must fall back to the wire here.
    expect(result.current!.holds('0:2')).toBe(false)
  })

  it('re-requests when the desk moves the next cue on', async () => {
    const { result } = renderHook(() => useNextGoSource(true), { wrapper })
    await waitFor(() => expect(previewRequests()).toHaveLength(1))
    expect(result.current).not.toBeNull()

    cueRunStateWs.callback!(frame())

    await waitFor(() => expect(previewRequests()).toHaveLength(2))
    const url = new URL(previewRequests()[1].url)
    expect(url.searchParams.get('cueId')).toBe('12')
  })

  it('requests nothing while disabled', async () => {
    renderHook(() => useNextGoSource(false), { wrapper })
    await waitFor(() => expect(fetchMock).not.toHaveBeenCalled())
  })

  it('empties rather than blacks out when there is no next cue', async () => {
    vi.unstubAllGlobals()
    installRelativeUrlRequest()
    installRecordingFetch(routes({ 'projects/1/cue-stacks': [stack(null)] }))

    const { result } = renderHook(() => useNextGoSource(true), { wrapper })

    await waitFor(() => expect(result.current).not.toBeNull())
    expect(result.current!.holds('0:1')).toBe(false)
    expect(previewRequests()).toHaveLength(0)
  })

  it('makes one request however many stage surfaces are mounted', async () => {
    // The overview panel is mounted globally and builds its own source, so on the Stage route two
    // providers ask at once. A query collapses identical args into a single request; a mutation
    // would have fired one POST per surface, including from a collapsed panel.
    renderHook(
      () => {
        useNextGoSource(true)
        useNextGoSource(true)
      },
      { wrapper },
    )

    await waitFor(() => expect(previewRequests()).toHaveLength(1))
  })

  it('empties when the backend rejects the preview', async () => {
    // 400 "no next cue" is an ordinary state at the end of a non-looping stack, and a failed
    // request must not leave the last look frozen on stage.
    vi.unstubAllGlobals()
    installRelativeUrlRequest()
    installRecordingFetch(
      routes({ 'projects/1/cue-stacks/7/preview': failWith(400, { error: 'Nothing to preview' }) }),
    )

    const { result } = renderHook(() => useNextGoSource(true), { wrapper })

    await waitFor(() => expect(result.current).not.toBeNull())
    await waitFor(() => expect(result.current!.holds('0:1')).toBe(false))
  })
})

describe('useNextGoStatus', () => {
  beforeEach(() => {
    installRelativeUrlRequest()
    installRecordingFetch(routes())
  })

  afterEach(() => {
    store.dispatch(restApi.util.resetApiState())
    vi.unstubAllGlobals()
  })

  it('names the cue on deck', async () => {
    const { result } = renderHook(() => useNextGoStatus(true), { wrapper })
    await waitFor(() => expect(result.current).toBe('Previewing 1 · Preset.'))
  })

  it('says so when nothing is on deck', async () => {
    vi.unstubAllGlobals()
    installRelativeUrlRequest()
    installRecordingFetch(
      routes({ 'projects/1/show': { projectId: 1, activeStackId: null, canEdit: true } }),
    )

    const { result } = renderHook(() => useNextGoStatus(true), { wrapper })
    await waitFor(() => expect(result.current).toBe('No cue on deck — showing output.'))
  })

  it('admits a failed preview rather than naming a cue nobody is seeing', async () => {
    // The stage falls back to plain output when the request fails, and there is no toast — so if
    // the menu still read "Previewing 1 · Preset." the operator would be told they are looking at
    // a preview of live output.
    vi.unstubAllGlobals()
    installRelativeUrlRequest()
    installRecordingFetch(
      routes({ 'projects/1/cue-stacks/7/preview': failWith(400, { error: 'Nothing to preview' }) }),
    )

    const { result } = renderHook(() => useNextGoStatus(true), { wrapper })
    await waitFor(() => expect(result.current).toBe('Preview unavailable — showing output.'))
  })

  it('is silent when the source isn’t selected', () => {
    const { result } = renderHook(() => useNextGoStatus(false), { wrapper })
    expect(result.current).toBeNull()
  })
})
