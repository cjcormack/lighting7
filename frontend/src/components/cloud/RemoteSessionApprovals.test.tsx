// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { PendingRemoteSession } from '@/api/cloudWsApi'

const mocks = vi.hoisted(() => {
  let pending: unknown[] = []
  const listeners = new Set<(p: unknown[]) => void>()
  return {
    toasts: new Map<string, () => ReactNode>(),
    dismissed: [] as string[],
    allow: vi.fn((_sid: string) => Promise.resolve()),
    deny: vi.fn((_sid: string) => Promise.resolve()),
    push(next: unknown[]) {
      pending = next
      listeners.forEach((fn) => fn(next))
    },
    reset() {
      pending = []
      listeners.clear()
    },
    cloud: {
      subscribePending: (fn: (p: unknown[]) => void) => {
        listeners.add(fn)
        return { unsubscribe: () => listeners.delete(fn) }
      },
      pending: () => pending,
    },
  }
})

vi.mock('@/api/lightingApi', () => ({ lightingApi: { cloud: mocks.cloud } }))

vi.mock('sonner', () => ({
  toast: {
    custom: (render: () => ReactNode, options: { id: string }) => {
      mocks.toasts.set(options.id, render)
    },
    dismiss: (id: string) => {
      mocks.dismissed.push(id)
      mocks.toasts.delete(id)
    },
  },
}))

vi.mock('@/store/cloud', () => ({
  useAllowRemoteSessionMutation: () => [mocks.allow, { isLoading: false }],
  useDenyRemoteSessionMutation: () => [mocks.deny, { isLoading: false }],
}))

import { RemoteSessionApprovals } from './RemoteSessionApprovals'

const NOW = Date.parse('2026-11-06T12:00:00.000Z')

const waiting = (sid: string, over: Partial<PendingRemoteSession> = {}): PendingRemoteSession => ({
  sid,
  email: `${sid}@example.com`,
  displayName: 'Someone',
  linkedUser: 'Operator',
  expiresAt: new Date(NOW + 45_000).toISOString(),
  ...over,
})

function showToast(sid: string) {
  const render_ = mocks.toasts.get(`remote-session-pending:${sid}`)
  if (render_ == null) throw new Error(`no toast for ${sid}`)
  return render(<>{render_()}</>)
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(NOW)
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  mocks.reset()
  mocks.toasts.clear()
  mocks.dismissed.length = 0
  mocks.allow.mockClear()
  mocks.deny.mockClear()
})

describe('RemoteSessionApprovals', () => {
  it('toasts each waiting session with who it is, as whom, and the time left', () => {
    render(<RemoteSessionApprovals />)
    act(() => mocks.push([waiting('sid-1'), waiting('sid-2', { linkedUser: null, displayName: 'sid-2@example.com' })]))
    expect([...mocks.toasts.keys()]).toEqual(['remote-session-pending:sid-1', 'remote-session-pending:sid-2'])

    const first = showToast('sid-1')
    expect(screen.getByText(/Someone \(sid-1@example.com\) wants to open this desk/)).toBeTruthy()
    expect(screen.getByText(/as Operator\./)).toBeTruthy()
    expect(screen.getByText('Denied in 45 s unless someone answers.')).toBeTruthy()
    first.unmount()
    showToast('sid-2')
    expect(screen.getByText(/^sid-2@example.com wants to open this desk.*with no desk account linked\./)).toBeTruthy()
  })

  it('Allow and Deny answer the desk for that session', () => {
    render(<RemoteSessionApprovals />)
    act(() => mocks.push([waiting('sid-1')]))
    showToast('sid-1')
    fireEvent.click(screen.getByRole('button', { name: 'Allow' }))
    expect(mocks.allow).toHaveBeenCalledWith('sid-1')
    fireEvent.click(screen.getByRole('button', { name: 'Deny' }))
    expect(mocks.deny).toHaveBeenCalledWith('sid-1')
  })

  it('takes a toast down once the desk no longer lists its session, on every window', () => {
    render(<RemoteSessionApprovals />)
    act(() => mocks.push([waiting('sid-1'), waiting('sid-2')]))
    act(() => mocks.push([waiting('sid-2')]))
    expect(mocks.dismissed).toEqual(['remote-session-pending:sid-1'])
    expect([...mocks.toasts.keys()]).toEqual(['remote-session-pending:sid-2'])
    act(() => mocks.push([]))
    expect(mocks.toasts.size).toBe(0)
  })

  it('takes its toasts down when it unmounts', () => {
    const { unmount } = render(<RemoteSessionApprovals />)
    act(() => mocks.push([waiting('sid-1')]))
    act(() => mocks.push([waiting('sid-1'), waiting('sid-2')]))
    unmount()
    expect(mocks.toasts.size).toBe(0)
  })

  it('shows what was already waiting when it mounts', () => {
    mocks.push([waiting('sid-1')])
    render(<RemoteSessionApprovals />)
    expect(mocks.toasts.has('remote-session-pending:sid-1')).toBe(true)
  })
})
