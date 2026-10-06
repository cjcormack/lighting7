// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, waitFor } from '@testing-library/react'
import { Provider } from 'react-redux'
import { useEffect } from 'react'
import { failWith, installRecordingFetch, installRelativeUrlRequest, stageRenderWs } from '@/test/backendMock'

vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

const toasts: unknown[] = []
vi.mock('sonner', () => ({
  toast: Object.assign((...a: unknown[]) => toasts.push(a), {
    success: (...a: unknown[]) => toasts.push(a),
    error: (...a: unknown[]) => toasts.push(a),
    warning: (...a: unknown[]) => toasts.push(a),
  }),
}))

// The real job draws WebGL; this one records what it was handed and whether it is still mounted.
interface MountedJob {
  request: StageRenderRequest
  onDone: (outcome: StageRenderOutcome) => void
  mounted: boolean
}
const jobs: MountedJob[] = []
let throwOnMount = false
vi.mock('../stage3d/render/StageRenderJob', () => ({
  default: function FakeJob(props: Omit<MountedJob, 'mounted'>) {
    if (throwOnMount) throw new Error('no WebGL here')
    useEffect(() => {
      const job = { ...props, mounted: true }
      jobs.push(job)
      return () => {
        job.mounted = false
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    }, [])
    return null
  },
}))

import { store } from '../../store'
import { restApi } from '../../store/restApi'
import type { StageRenderRequest } from '../../api/stageRenderApi'
import type { StageRenderOutcome } from '../stage3d/render/StageRenderJob'
import { StageRenderHost } from './StageRenderHost'

/**
 * The window's side of `render_view` (stage-view plan session 4): a request mounts one render, its
 * outcome goes back to the desk bound to the request's id and token, the render is taken down, and
 * nothing of it reaches the operator's screen — not even a refused answer.
 */

const request = (over: Partial<StageRenderRequest> = {}): StageRenderRequest => ({
  requestId: 'r-1',
  token: 'secret-1',
  projectId: 15,
  viewpoint: 'plan',
  width: 640,
  height: 360,
  source: 'output',
  workLights: 'off',
  timeoutMs: 30_000,
  ...over,
})

let fetchMock: ReturnType<typeof installRecordingFetch>

beforeEach(() => {
  jobs.length = 0
  toasts.length = 0
  throwOnMount = false
  stageRenderWs.reset()
  installRelativeUrlRequest()
  fetchMock = installRecordingFetch({ 'stage-renders/gone': failWith(404, { error: 'answered', code: 'RENDER_REQUEST_UNKNOWN' }) })
})

afterEach(() => {
  store.dispatch(restApi.util.resetApiState())
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

function mount() {
  return render(
    <Provider store={store}>
      <StageRenderHost />
    </Provider>,
  )
}

function answers(): Request[] {
  return fetchMock.mock.calls.map((c) => c[0] as Request).filter((r) => r.url.includes('/stage-renders/'))
}

describe('StageRenderHost', () => {
  it('draws nothing and loads no render until the desk asks', () => {
    const { container } = mount()
    expect(container.innerHTML).toBe('')
    expect(jobs).toEqual([])
    expect(stageRenderWs.callback).not.toBeNull()
  })

  it('mounts one render for a request and uploads its PNG with the request id and token, then takes it down', async () => {
    mount()
    act(() => stageRenderWs.fire(request()))
    await waitFor(() => expect(jobs).toHaveLength(1))
    expect(jobs[0].request).toEqual(request())

    const png = new Blob([new Uint8Array([0x89, 0x50, 0x4e, 0x47])], { type: 'image/png' })
    act(() => jobs[0].onDone({ png }))
    expect(jobs[0].mounted).toBe(false)

    await waitFor(() => expect(answers()).toHaveLength(1))
    const [upload] = answers()
    expect(new URL(upload.url).pathname).toBe('/api/rest/stage-renders/r-1')
    expect(upload.method).toBe('POST')
    expect(upload.headers.get('X-Render-Token')).toBe('secret-1')
    expect(new Uint8Array(await upload.clone().arrayBuffer())).toEqual(new Uint8Array([0x89, 0x50, 0x4e, 0x47]))
  })

  it('sends the reason when the render could not be drawn', async () => {
    mount()
    act(() => stageRenderWs.fire(request({ requestId: 'r-2', token: 'secret-2' })))
    await waitFor(() => expect(jobs).toHaveLength(1))
    act(() => jobs[0].onDone({ reason: 'it gave up after 27 s, waiting for the scene' }))

    await waitFor(() => expect(answers()).toHaveLength(1))
    const [failure] = answers()
    expect(new URL(failure.url).pathname).toBe('/api/rest/stage-renders/r-2/failure')
    expect(failure.headers.get('X-Render-Token')).toBe('secret-2')
    expect(await failure.clone().json()).toEqual({ reason: 'it gave up after 27 s, waiting for the scene' })
  })

  it('renders one at a time: a request that arrives mid-render is answered busy, and the first carries on', async () => {
    mount()
    act(() => stageRenderWs.fire(request()))
    await waitFor(() => expect(jobs).toHaveLength(1))
    act(() => stageRenderWs.fire(request({ requestId: 'r-3', token: 'secret-3' })))

    await waitFor(() => expect(answers()).toHaveLength(1))
    expect(new URL(answers()[0].url).pathname).toBe('/api/rest/stage-renders/r-3/failure')
    expect((await answers()[0].clone().json()).reason).toMatch(/still finishing another render/)
    expect(jobs).toHaveLength(1)
    expect(jobs[0].mounted).toBe(true)
  })

  it('ends a render that throws with the reason, and the page carries on', async () => {
    throwOnMount = true
    vi.spyOn(console, 'error').mockImplementation(() => {})
    const { container } = mount()
    act(() => stageRenderWs.fire(request({ requestId: 'r-4', token: 'secret-4' })))

    await waitFor(() => expect(answers()).toHaveLength(1))
    expect(new URL(answers()[0].url).pathname).toBe('/api/rest/stage-renders/r-4/failure')
    expect((await answers()[0].clone().json()).reason).toMatch(/the render threw: no WebGL here/)
    expect(container.innerHTML).toBe('')
  })

  it('says nothing on this screen when the desk refuses the answer', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    mount()
    act(() => stageRenderWs.fire(request({ requestId: 'gone' })))
    await waitFor(() => expect(jobs).toHaveLength(1))
    act(() => jobs[0].onDone({ reason: 'late' }))

    await waitFor(() => expect(warn).toHaveBeenCalled())
    expect(toasts).toEqual([])
  })
})
