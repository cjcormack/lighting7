// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CloudState, RemoteAccessSettings, RemoteSessions } from '@/store/cloud'

const mocks = vi.hoisted(() => ({
  settings: undefined as unknown,
  sessions: undefined as unknown,
  set: vi.fn((_body: unknown): Promise<unknown> => Promise.resolve({})),
  end: vi.fn((_sid: string) => Promise.resolve({})),
  polling: undefined as number | undefined,
}))

vi.mock('@/store/cloud', () => ({
  useRemoteAccessSettingsQuery: () => ({ data: mocks.settings, error: undefined }),
  useSetRemoteAccessMutation: () => [
    (body: unknown) => ({ unwrap: () => mocks.set(body) }),
    { isLoading: false, error: undefined, reset: () => {} },
  ],
  useRemoteSessionsQuery: (_arg: unknown, options?: { pollingInterval?: number }) => {
    mocks.polling = options?.pollingInterval
    return { data: mocks.sessions, error: undefined }
  },
  useEndRemoteSessionMutation: () => [(sid: string) => mocks.end(sid), { isLoading: false }],
}))

import { CloudRemoteAccess, LOCAL_ONLY_REASON, SESSIONS_POLL_MS } from './CloudRemoteAccess'

const adopted = {
  installId: 'inst_123',
  orgName: 'Cormack Lighting',
  adoptedAt: '2026-11-04T09:00:00.000Z',
  fingerprint: 'SHA256:abcd',
}

function cloud(over: Partial<CloudState> = {}): CloudState {
  return { configured: true, enabled: true, connection: 'ONLINE', adopted, ...over }
}

function settings(over: Partial<RemoteAccessSettings> = {}): RemoteAccessSettings {
  return {
    remoteAccess: true,
    mcpAccess: false,
    approveSessions: false,
    allowScripts: false,
    allowEffects: false,
    allowCommands: true,
    ...over,
  }
}

const sessions: RemoteSessions = {
  open: [{ sid: 'sid-1', email: 'op@example.com', displayName: 'Op', user: 'Operator', startedAt: '2026-11-06T10:00:00.000Z', path: 'RELAY' }],
  recent: [
    {
      sid: 'sid-0',
      email: 'someone@example.com',
      startedAt: '2026-11-06T09:00:00.000Z',
      endedAt: '2026-11-06T09:30:00.000Z',
      endReason: 'ENDED_BY_ADMIN',
    },
  ],
}

const switchNamed = (name: string) => screen.getByRole('switch', { name })

beforeEach(() => {
  mocks.settings = settings()
  mocks.sessions = sessions
})

afterEach(() => {
  cleanup()
  mocks.set.mockClear()
  mocks.end.mockClear()
})

describe('CloudRemoteAccess', () => {
  it('on the desk, an adopted desk shows the three cloud switches and each writes its own field', () => {
    render(<CloudRemoteAccess state={cloud()} />)
    expect(switchNamed('Remote sessions').getAttribute('aria-checked')).toBe('true')
    expect(switchNamed('Claude (MCP)').getAttribute('aria-checked')).toBe('false')
    expect(screen.queryByText(LOCAL_ONLY_REASON)).toBeNull()

    fireEvent.click(switchNamed('Approve each new session on this desk'))
    expect(mocks.set).toHaveBeenLastCalledWith({ approveSessions: true })
    fireEvent.click(switchNamed('Claude (MCP)'))
    expect(mocks.set).toHaveBeenLastCalledWith({ mcpAccess: true })
    fireEvent.click(switchNamed('Remote sessions'))
    expect(mocks.set).toHaveBeenLastCalledWith({ remoteAccess: false })
  })

  it('moves the scripts, effects and commands switches here, writing through the same route', () => {
    render(<CloudRemoteAccess state={cloud()} />)
    const commands = screen.getByLabelText(/Allow fixture commands over remote access/) as HTMLInputElement
    expect(commands.checked).toBe(true)
    fireEvent.click(screen.getByLabelText(/Allow scripts over remote access/))
    expect(mocks.set).toHaveBeenLastCalledWith({ allowScripts: true })
    fireEvent.click(screen.getByLabelText(/Allow arming and firing over remote access/))
    expect(mocks.set).toHaveBeenLastCalledWith({ allowEffects: true })
  })

  it('says how the sidecar is, while Remote sessions is on', () => {
    const { rerender } = render(<CloudRemoteAccess state={cloud({ link: 'RUNNING' })} />)
    expect(screen.getByTestId('cloud-sidecar').textContent).toBe('Sidecar running')
    rerender(<CloudRemoteAccess state={cloud({ link: 'RESTARTING' })} />)
    expect(screen.getByTestId('cloud-sidecar').textContent).toBe('Sidecar restarting…')
    mocks.settings = settings({ remoteAccess: false })
    rerender(<CloudRemoteAccess state={cloud({ link: 'OFF' })} />)
    expect(screen.queryByTestId('cloud-sidecar')).toBeNull()
  })

  it('lists the open sessions with End, asked once, and the recent ones with why they ended', () => {
    render(<CloudRemoteAccess state={cloud()} />)
    expect(mocks.polling).toBe(SESSIONS_POLL_MS)
    const list = screen.getByTestId('cloud-sessions')
    expect(within(list).getByText('op@example.com')).toBeTruthy()
    expect(within(list).getByText(/as Operator/)).toBeTruthy()
    expect(within(list).getByText(/relayed/)).toBeTruthy()
    expect(within(list).getByText(/ended here/)).toBeTruthy()
    expect(within(list).getByText(/not linked/)).toBeTruthy()

    fireEvent.click(within(list).getByRole('button', { name: 'End' }))
    expect(mocks.end).not.toHaveBeenCalled()
    fireEvent.click(within(list).getByRole('button', { name: 'End now' }))
    expect(mocks.end).toHaveBeenCalledWith('sid-1')
  })

  it('over remote access, reads every switch and says why it cannot change one, but End still works', () => {
    render(<CloudRemoteAccess state={cloud({ remote: true })} />)
    expect(screen.getByText(LOCAL_ONLY_REASON)).toBeTruthy()
    for (const name of ['Remote sessions', 'Approve each new session on this desk', 'Claude (MCP)']) {
      expect((switchNamed(name) as HTMLButtonElement).disabled).toBe(true)
    }
    for (const label of [/Allow scripts/, /Allow arming/, /Allow fixture commands/]) {
      expect((screen.getByLabelText(label) as HTMLInputElement).disabled).toBe(true)
    }
    fireEvent.click(switchNamed('Claude (MCP)'))
    expect(mocks.set).not.toHaveBeenCalled()
    const list = screen.getByTestId('cloud-sessions')
    fireEvent.click(within(list).getByRole('button', { name: 'End' }))
    fireEvent.click(within(list).getByRole('button', { name: 'End now' }))
    expect(mocks.end).toHaveBeenCalledWith('sid-1')
  })

  it('before adoption, shows only the switches that hold for the tunnel too', () => {
    render(<CloudRemoteAccess state={cloud({ adopted: null })} />)
    expect(screen.queryByRole('switch')).toBeNull()
    expect(screen.queryByTestId('cloud-sessions')).toBeNull()
    expect(screen.getByLabelText(/Allow scripts over remote access/)).toBeTruthy()
  })

  it('before adoption over remote access, the switches still read and say why', () => {
    render(<CloudRemoteAccess state={cloud({ adopted: null, remote: true })} />)
    expect(screen.getByText(LOCAL_ONLY_REASON)).toBeTruthy()
    expect((screen.getByLabelText(/Allow scripts/) as HTMLInputElement).disabled).toBe(true)
  })
})
