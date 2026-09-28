import { beforeEach, describe, expect, it, vi } from 'vitest'

// lightingApi opens a real WebSocket at import; the store modules below register WS
// subscriptions at module load, so it has to be stubbed before any of them are pulled in.
vi.mock('@/api/lightingApi', async () => (await import('@/test/backendMock')).lightingApiMock())

vi.mock('sonner', () => ({ toast: { error: vi.fn() } }))

import { toast } from 'sonner'
import { errorToastMiddleware, ignoreReportedError, SILENT_ENDPOINTS } from './errorToastMiddleware'

const toastError = vi.mocked(toast.error)

/** A rejected-with-value action shaped the way RTK Query dispatches one. */
function rejection(opts: {
  endpointName: string
  type?: 'query' | 'mutation'
  payload?: unknown
  condition?: boolean
}) {
  return {
    type: `${'restApi'}/executeQuery/rejected`,
    payload: opts.payload ?? { status: 409, data: { error: 'Already in use' } },
    error: { message: 'Rejected' },
    meta: {
      rejectedWithValue: true,
      arg: { type: opts.type ?? 'mutation', endpointName: opts.endpointName },
      condition: opts.condition,
      requestId: 'abc',
      requestStatus: 'rejected',
    },
  }
}

function run(action: unknown) {
  const next = vi.fn((a: unknown) => a)
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const result = (errorToastMiddleware as any)({})(next)(action)
  return { next, result }
}

describe('errorToastMiddleware', () => {
  beforeEach(() => {
    toastError.mockClear()
  })

  it('toasts the server message when a mutation fails', () => {
    run(rejection({ endpointName: 'createProjectCue' }))
    expect(toastError).toHaveBeenCalledOnce()
    expect(toastError.mock.calls[0][0]).toBe('Already in use')
  })

  it('stays silent for a failed query', () => {
    // Queries retry and refetch on focus; toasting each attempt would spam a flaky connection.
    run(rejection({ endpointName: 'projectCueList', type: 'query' }))
    expect(toastError).not.toHaveBeenCalled()
  })

  it('stays silent for an endpoint whose call site reports its own errors', () => {
    run(rejection({ endpointName: 'copyLook' }))
    expect(toastError).not.toHaveBeenCalled()
  })

  it('stays silent for a skipped/aborted request', () => {
    run(rejection({ endpointName: 'createProjectCue', condition: true }))
    expect(toastError).not.toHaveBeenCalled()
  })

  it('reuses one toast id per endpoint so a burst collapses into a single toast', () => {
    // patchCue fires per keystroke; ten failures must not stack ten toasts.
    for (let i = 0; i < 10; i++) run(rejection({ endpointName: 'patchProjectCue' }))
    expect(toastError).toHaveBeenCalledTimes(10)
    const ids = new Set(toastError.mock.calls.map((c) => c[1]?.id))
    expect(ids).toEqual(new Set(['mutation-error:patchProjectCue']))
  })

  it('describes an unreachable server rather than dumping the raw object', () => {
    run(rejection({ endpointName: 'createProjectCue', payload: { status: 'FETCH_ERROR' } }))
    expect(toastError.mock.calls[0][0]).toBe('Could not reach the server')
  })

  it('passes every action through untouched', () => {
    const action = rejection({ endpointName: 'createProjectCue' })
    const { next, result } = run(action)
    expect(next).toHaveBeenCalledWith(action)
    expect(result).toBe(action)
  })

  it('ignores non-RTK-Query actions', () => {
    run({ type: 'runner/setThing', payload: 1 })
    expect(toastError).not.toHaveBeenCalled()
  })
})

describe('ignoreReportedError', () => {
  it('swallows a rejection so it does not become an unhandled promise rejection', async () => {
    await expect(
      Promise.reject(new Error('boom')).catch(ignoreReportedError),
    ).resolves.toBeUndefined()
  })
})

describe('SILENT_ENDPOINTS', () => {
  it('only names endpoints that actually exist', async () => {
    // A renamed or deleted endpoint would otherwise leave a stale entry here — harmless-looking,
    // but it means the *real* endpoint of that name silently stops being suppressed (or, worse,
    // a typo means a call site that reports its own errors double-toasts forever).
    const { restApi } = await import('./restApi')
    await Promise.all([
      import('./cues'),
      import('./cueStacks'),
      import('./looks'),
      import('./templates'),
      import('./busk'),
      import('./scripts'),
      import('./projects'),
      import('./cloudSync'),
      import('./oauthGithub'),
      import('./installs'),
      import('./stageRegions'),
      import('./riggings'),
      import('./patches'),
      import('./programmerOps'),
      import('./speedMasters'),
      import('./fxDefinitions'),
      import('./auth'),
      import('./users'),
      import('./passwordReset'),
      import('./deviceLogin'),
      import('./updates'),
      import('./remoteAccess'),
      // The three WebSocket-backed mutations, injected from these two modules.
      import('./park'),
      import('./channels'),
    ])

    const known = new Set(Object.keys(restApi.endpoints))
    const unknown = [...SILENT_ENDPOINTS].filter((name) => !known.has(name))
    expect(unknown).toEqual([])
  })
})
