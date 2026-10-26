import { beforeEach, describe, expect, it, vi } from 'vitest'

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

/**
 * Every endpoint a store module declares, read from the modules' source. Every endpoint is injected
 * into `restApi` by a module here as `name: build.query(…)` or `name: build.mutation(…)`, so this is
 * the set `restApi.endpoints` holds once they are all loaded. Loading them instead is slow enough to
 * time out under load.
 */
const sources = import.meta.glob(['./*.ts', '!./*.test.ts'], {
  query: '?raw',
  import: 'default',
  eager: true,
}) as Record<string, string>
const declaredEndpoints = new Set(
  Object.values(sources).flatMap((source) =>
    [...source.matchAll(/^\s*(\w+): *build\.(?:query|mutation|infiniteQuery)\b/gm)].map((m) => m[1]),
  ),
)

describe('SILENT_ENDPOINTS', () => {
  it('only names endpoints that actually exist', () => {
    // A renamed or deleted endpoint would otherwise leave a stale entry here — harmless-looking,
    // but it means the *real* endpoint of that name silently stops being suppressed (or, worse,
    // a typo means a call site that reports its own errors double-toasts forever).
    const unknown = [...SILENT_ENDPOINTS].filter((name) => !declaredEndpoints.has(name))
    expect(unknown).toEqual([])
  })
})
