// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { TunnelSettings } from '@/store/remoteAccess'

// Store-connected. Mocking the store modules keeps this a component test and keeps the import
// graph away from lightingApi's real WebSocket, as ApplyUpdateDialog's test does.
const mocks = vi.hoisted(() => ({
  save: vi.fn((args: unknown) => ({ unwrap: () => Promise.resolve(args) })),
  settings: undefined as unknown,
  role: 'ADMIN' as string,
}))

vi.mock('@/store/remoteAccess', () => ({
  useTunnelSettingsQuery: () => ({ data: mocks.settings, isLoading: false, error: undefined }),
  useSaveTunnelSettingsMutation: () => [mocks.save, { isLoading: false, error: undefined, reset: () => {} }],
}))

vi.mock('@/store/auth', () => ({
  useAuthStatusQuery: () => ({ data: { user: { role: mocks.role } } }),
}))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { RemoteAccessPanel, describeTunnelState } from './RemoteAccessPanel'

function settings(over: Partial<TunnelSettings> = {}): TunnelSettings {
  return {
    enabled: false,
    domain: 'desk.ngrok-free.app',
    allowScripts: false,
    hasAuthtoken: true,
    publicUrl: 'https://desk.ngrok-free.app',
    connectorUrl: 'https://desk.ngrok-free.app/mcp',
    publicUrlOverridden: false,
    port: 8414,
    pinnedVersion: '3.39.11',
    state: { status: 'off' },
    ...over,
  }
}

const toggle = () => screen.getByRole('switch', { name: 'Remote access' })

beforeEach(() => {
  mocks.settings = settings()
  mocks.role = 'ADMIN'
})

afterEach(() => {
  cleanup()
  mocks.save.mockClear()
})

describe('RemoteAccessPanel', () => {
  it('turning it on asks first, and only the confirm sends the write', async () => {
    render(<RemoteAccessPanel />)
    fireEvent.click(toggle())
    expect(mocks.save).not.toHaveBeenCalled()
    expect(screen.getByText(/put this desk on the internet\?/i)).toBeTruthy()
    expect(screen.getByText(/to anyone with a desk password/i)).toBeTruthy()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Turn on' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({ enabled: true })
  })

  it('turning it off does not ask', async () => {
    mocks.settings = settings({ enabled: true, state: { status: 'online', url: 'https://desk.ngrok-free.app' } })
    render(<RemoteAccessPanel />)
    await act(async () => {
      fireEvent.click(toggle())
    })
    expect(mocks.save).toHaveBeenCalledWith({ enabled: false })
    expect(screen.queryByText(/put this desk on the internet\?/i)).toBeNull()
  })

  it('cannot be turned on without a token, and says why', () => {
    mocks.settings = settings({ hasAuthtoken: false })
    render(<RemoteAccessPanel />)
    expect(toggle().hasAttribute('disabled')).toBe(true)
    expect(screen.getByText(/paste your ngrok authtoken and save first/i)).toBeTruthy()
  })

  it('changing a saved domain asks first, because the issuer moves', async () => {
    render(<RemoteAccessPanel />)
    fireEvent.change(screen.getByLabelText('Domain'), { target: { value: 'other.ngrok-free.app' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(mocks.save).not.toHaveBeenCalled()
    expect(screen.getByText(/change the desk’s address\?/i)).toBeTruthy()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Change domain' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({ domain: 'other.ngrok-free.app' })
  })

  it('clearing a saved domain asks too, and says remote access goes off rather than naming an empty address', async () => {
    render(<RemoteAccessPanel />)
    fireEvent.change(screen.getByLabelText('Domain'), { target: { value: '' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(mocks.save).not.toHaveBeenCalled()
    expect(screen.getByText(/remove the desk’s address\?/i)).toBeTruthy()
    expect(screen.getByText(/remote access turns off/i)).toBeTruthy()
    expect(screen.queryByText(/https:\/\/\/mcp/)).toBeNull()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Remove domain' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({ domain: '' })
  })

  it('a first domain and a pasted token save without asking; the token is never shown', async () => {
    mocks.settings = settings({ domain: null, hasAuthtoken: false })
    render(<RemoteAccessPanel />)
    const tokenField = screen.getByLabelText('Authtoken') as HTMLInputElement
    expect(tokenField.value).toBe('')
    expect(tokenField.type).toBe('password')
    fireEvent.change(tokenField, { target: { value: ' abc123 ' } })
    fireEvent.change(screen.getByLabelText('Domain'), { target: { value: 'desk.ngrok-free.app' } })
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Save' }))
    })
    expect(mocks.save).toHaveBeenCalledWith({ domain: 'desk.ngrok-free.app', authtoken: 'abc123' })
  })

  it('shows the connector address to paste into Claude', () => {
    render(<RemoteAccessPanel />)
    expect(screen.getByText('https://desk.ngrok-free.app/mcp')).toBeTruthy()
  })

  it('is refused for an operator without asking the desk', () => {
    mocks.role = 'OPERATOR'
    render(<RemoteAccessPanel />)
    expect(screen.getByText(/requires an administrator account/i)).toBeTruthy()
    expect(screen.queryByRole('switch')).toBeNull()
  })
})

describe('describeTunnelState', () => {
  it('reads each state', () => {
    expect(describeTunnelState({ status: 'off' })).toBe('Off')
    expect(describeTunnelState({ status: 'installing', downloadedBytes: 50, totalBytes: 200 })).toBe(
      'Downloading ngrok… 25%',
    )
    expect(describeTunnelState({ status: 'online', url: 'https://a.b' })).toBe('Online · https://a.b')
    expect(
      describeTunnelState({ status: 'error', message: 'Bad token.', code: 'ERR_NGROK_105', retrying: false }),
    ).toBe('Bad token. (ERR_NGROK_105)')
    expect(describeTunnelState({ status: 'error', message: 'Dropped.', retrying: true })).toBe('Dropped. Retrying…')
  })
})
