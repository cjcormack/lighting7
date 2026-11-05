// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CloudState } from '@/store/cloud'

// Store-connected. Mocking the store modules keeps this a component test and keeps the import
// graph away from lightingApi's real WebSocket, as RemoteAccessPanel's test does.
const mocks = vi.hoisted(() => ({
  state: undefined as unknown,
  role: 'ADMIN' as string,
  start: vi.fn(() => Promise.resolve({})),
  cancel: vi.fn(() => Promise.resolve({})),
  unadopt: vi.fn((_args: { force?: boolean }): Promise<unknown> => Promise.resolve({})),
}))

vi.mock('@/store/cloud', async () => {
  const { useState } = await import('react')
  // A mutation hook whose `error` follows the last call, as RTK Query's does.
  const mutationHook =
    <A,>(fn: (arg: A) => Promise<unknown>) =>
    () => {
      const [error, setError] = useState<unknown>(undefined)
      const trigger = (arg: A) => ({
        unwrap: async () => {
          try {
            return await fn(arg)
          } catch (e) {
            setError(e)
            throw e
          }
        },
      })
      return [trigger, { isLoading: false, error, reset: () => setError(undefined) }] as const
    }
  return {
    useCloudStateQuery: () => ({ data: mocks.state, isLoading: false, error: undefined }),
    useStartCloudAdoptionMutation: mutationHook(() => mocks.start()),
    useCancelCloudAdoptionMutation: mutationHook(() => mocks.cancel()),
    useUnadoptCloudMutation: mutationHook((arg: { force?: boolean }) => mocks.unadopt(arg)),
  }
})

vi.mock('@/store/auth', () => ({
  useAuthStatusQuery: () => ({ data: { user: { role: mocks.role } } }),
}))

vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { CloudPanel } from './CloudPanel'

const NOW = Date.parse('2026-11-05T12:00:00.000Z')

function cloud(over: Partial<CloudState> = {}): CloudState {
  return { configured: true, enabled: true, connection: 'OFF', ...over }
}

const adoption = {
  userCode: 'BCDF-GHJK',
  verificationUri: 'https://cloud.example/adopt',
  verificationUriComplete: 'https://cloud.example/adopt?code=BCDF-GHJK',
  expiresAt: '2026-11-05T12:10:00.000Z',
  fingerprint: 'SHA256:abcd',
}

const adopted = {
  installId: 'inst_123',
  orgName: 'Cormack Lighting',
  adoptedAt: '2026-11-04T09:00:00.000Z',
  adoptedBy: 'Chris',
  linkedEmail: 'chris@example.com',
  fingerprint: 'SHA256:abcd',
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] })
  vi.setSystemTime(NOW)
  mocks.state = cloud()
  mocks.role = 'ADMIN'
  window.localStorage.clear()
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  mocks.start.mockClear()
  mocks.cancel.mockClear()
  mocks.unadopt.mockReset()
  mocks.unadopt.mockImplementation(() => Promise.resolve({}))
})

describe('CloudPanel', () => {
  it('says so when no cloud is configured, and offers no Adopt', () => {
    mocks.state = cloud({ configured: false })
    render(<CloudPanel />)
    expect(screen.getByText('No cloud configured')).toBeTruthy()
    expect(screen.getByText('cloud.baseUrl')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Adopt this desk' })).toBeNull()
  })

  it('says why the desk refused cloud.baseUrl', () => {
    mocks.state = cloud({
      configured: false,
      lastError: { code: 'CLOUD_URL_INVALID', message: 'cloud.baseUrl must use https unless the cloud runs on this machine.' },
    })
    render(<CloudPanel />)
    expect(screen.getByText('No cloud configured')).toBeTruthy()
    expect(screen.getByText(/must use https/)).toBeTruthy()
    expect(screen.queryByText(/names no cloud/)).toBeNull()
  })

  it('says so when the cloud is turned off on this desk, naming the key', () => {
    mocks.state = cloud({ enabled: false })
    render(<CloudPanel />)
    expect(screen.getByText('Turned off on this desk')).toBeTruthy()
    expect(screen.getByText('cloud.enabled = false')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Adopt this desk' })).toBeNull()
  })

  it('adopts from the desk itself', async () => {
    render(<CloudPanel />)
    expect(screen.getByText(/keeps working exactly as it does now/)).toBeTruthy()
    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Adopt this desk' }))
    })
    expect(mocks.start).toHaveBeenCalledTimes(1)
  })

  it('cannot adopt over remote access, and says why', () => {
    mocks.state = cloud({ remote: true })
    render(<CloudPanel />)
    expect(screen.getByRole('button', { name: 'Adopt this desk' }).matches(':disabled')).toBe(true)
    expect(screen.getByText(/only be adopted from the desk itself/)).toBeTruthy()
  })

  it('shows a pending adoption: the code, a QR, the link and the time left, and cancels it', async () => {
    mocks.state = cloud({ adoption })
    const { container } = render(<CloudPanel />)
    expect(screen.getByTestId('cloud-user-code').textContent).toBe('BCDF-GHJK')
    expect(container.querySelector('svg')).not.toBeNull()
    const link = screen.getByRole('link', { name: 'https://cloud.example/adopt' })
    expect(link.getAttribute('href')).toBe(adoption.verificationUriComplete)
    expect(screen.getByText(/10:00 left/)).toBeTruthy()

    act(() => {
      vi.advanceTimersByTime(65_000)
    })
    expect(screen.getByText(/8:55 left/)).toBeTruthy()

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    })
    expect(mocks.cancel).toHaveBeenCalledTimes(1)
  })

  it('shows no code or link for a pending adoption over remote access', () => {
    mocks.state = cloud({
      remote: true,
      adoption: { ...adoption, userCode: null, verificationUri: null, verificationUriComplete: null },
    })
    const { container } = render(<CloudPanel />)
    expect(screen.queryByTestId('cloud-user-code')).toBeNull()
    expect(container.querySelector('svg')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(screen.getByText(/can only be approved from the desk itself/)).toBeTruthy()
  })

  it('shows an adopted desk and its connection', () => {
    mocks.state = cloud({ adopted, connection: 'ONLINE' })
    render(<CloudPanel />)
    expect(screen.getByText('Adopted by Cormack Lighting')).toBeTruthy()
    expect(screen.getByText('inst_123')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Copy install id' })).toBeTruthy()
    expect(screen.getByText(/by Chris$/)).toBeTruthy()
    expect(screen.getByText('chris@example.com')).toBeTruthy()
    expect(screen.getByTestId('cloud-connection').textContent).toBe('Online')
  })

  it('counts down to the next attempt and says why the last one ended', () => {
    mocks.state = cloud({
      adopted,
      connection: 'RETRYING',
      retryAt: '2026-11-05T12:00:12.000Z',
      lastError: { code: 'CLOUD_CLOCK_SKEW', message: 'Check this machine’s clock: it is 7 minutes off.' },
    })
    render(<CloudPanel />)
    const line = () => screen.getByTestId('cloud-connection').textContent
    expect(line()).toContain('Retrying in 12 s')
    expect(line()).toContain('Check this machine’s clock')
    act(() => {
      vi.advanceTimersByTime(5_000)
    })
    expect(line()).toContain('Retrying in 7 s')
  })

  it('removes the desk from the cloud after the confirm', async () => {
    mocks.state = cloud({ adopted, connection: 'ONLINE' })
    render(<CloudPanel />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove from cloud' }))
    expect(mocks.unadopt).not.toHaveBeenCalled()
    expect(screen.getByText('Remove this desk from Cormack Lighting?')).toBeTruthy()

    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove from cloud' }))
    })
    expect(mocks.unadopt).toHaveBeenCalledWith({ force: false })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('offers to forget on this desk only when the cloud cannot be reached', async () => {
    mocks.state = cloud({ adopted, connection: 'RETRYING' })
    mocks.unadopt.mockImplementationOnce(() =>
      Promise.reject({
        status: 409,
        data: { error: 'The cloud is turned off on this desk, so it can’t be told.', code: 'CLOUD_UNREACHABLE' },
      }),
    )
    render(<CloudPanel />)
    fireEvent.click(screen.getByRole('button', { name: 'Remove from cloud' }))
    await act(async () => {
      fireEvent.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Remove from cloud' }))
    })
    expect(mocks.unadopt).toHaveBeenLastCalledWith({ force: false })
    expect(screen.getByText('The cloud couldn’t be told')).toBeTruthy()
    expect(screen.getByRole('dialog').textContent).toContain('The cloud is turned off on this desk')

    await act(async () => {
      fireEvent.click(screen.getByRole('button', { name: 'Forget on this desk only' }))
    })
    expect(mocks.unadopt).toHaveBeenLastCalledWith({ force: true })
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('shows a notice after a revoke until it is dismissed, and the next one with the same words', () => {
    const notice = 'This desk was removed from Cormack Lighting in the cloud.'
    mocks.state = cloud({ notice, noticeAt: '2026-11-05T11:00:00.000Z' })
    render(<CloudPanel />)
    expect(screen.getByText(notice)).toBeTruthy()
    fireEvent.click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(screen.queryByText(notice)).toBeNull()

    cleanup()
    render(<CloudPanel />)
    expect(screen.queryByText(notice)).toBeNull()

    cleanup()
    mocks.state = cloud({ notice, noticeAt: '2026-11-05T11:30:00.000Z' })
    render(<CloudPanel />)
    expect(screen.getByText(notice)).toBeTruthy()
  })

  it('asks an operator for an administrator', () => {
    mocks.role = 'OPERATOR'
    render(<CloudPanel />)
    expect(screen.getByText(/requires an administrator account/)).toBeTruthy()
  })
})
