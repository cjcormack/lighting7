// @vitest-environment jsdom
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render } from '@testing-library/react'

type Listener = (event: Record<string, unknown>) => void
const { listeners, subscribe } = vi.hoisted(() => {
  const listeners: Record<'done' | 'failed' | 'conflicts', Listener[]> = { done: [], failed: [], conflicts: [] }
  const subscribe = (kind: keyof typeof listeners) => (fn: Listener) => {
    listeners[kind].push(fn)
    return { unsubscribe: () => {} }
  }
  return { listeners, subscribe }
})
vi.mock('@/api/lightingApi', () => ({
  lightingApi: {
    cloudSync: {
      subscribeDone: subscribe('done'),
      subscribeFailed: subscribe('failed'),
      subscribeConflictsPending: subscribe('conflicts'),
    },
  },
}))
vi.mock('@/store/projects', () => ({ useProjectListQuery: () => ({ data: [{ id: 4, name: 'Sleeping Beauty' }] }) }))

let pathname = '/'
vi.mock('react-router', () => ({ useLocation: () => ({ pathname }) }))

const toastError = vi.fn()
const toastSuccess = vi.fn()
vi.mock('sonner', () => ({
  toast: { error: (...a: unknown[]) => toastError(...a), success: (...a: unknown[]) => toastSuccess(...a), warning: vi.fn() },
}))

import { SyncNotifications } from './SyncNotifications'

afterEach(() => {
  vi.clearAllMocks()
  listeners.done = []
  listeners.failed = []
  listeners.conflicts = []
})

function fail(errorCode: string) {
  for (const fn of listeners.failed) fn({ projectId: 4, errorCode, message: 'refused' })
}

describe('SyncNotifications', () => {
  it("leaves a project's events to its Sync tab while that tab is open", () => {
    pathname = '/projects/4/settings/sync'
    render(<SyncNotifications />)
    fail('SESSION_PENDING')
    for (const fn of listeners.done) fn({ projectId: 4, outcome: 'PUSHED', headSha: 'abc1234', message: 'Pushed' })
    expect(toastError).not.toHaveBeenCalled()
    expect(toastSuccess).not.toHaveBeenCalled()
  })

  it("toasts a project's failure elsewhere, but not a restore whose review moved on", () => {
    pathname = '/projects/4/show'
    render(<SyncNotifications />)
    fail('RESTORE_REVIEW_STALE')
    expect(toastError).not.toHaveBeenCalled()
    fail('SESSION_PENDING')
    expect(toastError).toHaveBeenCalledWith('Sleeping Beauty: refused', expect.objectContaining({ id: 'cloud-sync-4' }))
  })
})
