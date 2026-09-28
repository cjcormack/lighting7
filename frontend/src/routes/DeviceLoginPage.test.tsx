// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Store-connected and reads its token from the router, so both are mocked — same shape as
// ResetPasswordPage.test.tsx, which also keeps the import graph away from lightingApi's real
// WebSocket.
const redeem = vi.fn()
let infoResult: {
  data?: { username: string; displayName: string; expiresAt: string }
  error?: unknown
  isLoading?: boolean
} = {}

vi.mock('@/store/deviceLogin', () => ({
  useDeviceLoginInfoQuery: () => infoResult,
  useRedeemDeviceLoginMutation: () => [
    (args: unknown) => ({ unwrap: () => redeem(args) }),
    { isLoading: false },
  ],
}))
vi.mock('react-router', () => ({
  useParams: () => ({ token: 'a-code' }),
}))

import { DeviceLoginPage } from './DeviceLoginPage'

const assign = vi.fn()
const replaceState = vi.fn()

beforeEach(() => {
  // jsdom's `location` is non-configurable, so its `assign` can't be spied on — stub the
  // whole global instead. `href` is carried over so nothing that reads it sees undefined.
  vi.stubGlobal('location', { ...window.location, href: window.location.href, assign })
  vi.spyOn(window.history, 'replaceState').mockImplementation(replaceState)
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  vi.clearAllMocks()
  infoResult = {}
})

function live() {
  infoResult = {
    data: { username: 'op', displayName: 'Ops Person', expiresAt: new Date(Date.now() + 120_000).toISOString() },
  }
}

describe('DeviceLoginPage', () => {
  it('names the account before anyone commits to signing in', () => {
    live()
    render(<DeviceLoginPage />)
    // Both the username and the display name, so the person can tell two similar accounts
    // apart before handing their phone a 30-day session.
    expect(screen.getByText(/\(op\)/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Sign in as Ops Person/ })).toBeInTheDocument()
  })

  // The reason the flow has a tap at all. A QR scanner that prefetches, a link preview, or
  // React's StrictMode double-render would each burn a single-use two-minute code before
  // anyone saw the screen.
  it('does not redeem on load — only the tap does', () => {
    live()
    render(<DeviceLoginPage />)
    expect(redeem).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /Sign in as Ops Person/ }))
    expect(redeem).toHaveBeenCalledWith({ token: 'a-code' })
  })

  // Pins the *document-level* navigation. `App.tsx` reads its `publicPath` bypass once at
  // module scope, so a react-router navigate() here would render the whole app with both
  // AuthGate and BootGate still bypassed.
  it('finishes with a full document load, not a router navigation', async () => {
    live()
    redeem.mockResolvedValue(undefined)
    render(<DeviceLoginPage />)

    fireEvent.click(screen.getByRole('button', { name: /Sign in as Ops Person/ }))
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/'))
  })

  // Deliberately NOT on mount. This page has no retry button, so reloading is the recovery
  // from the flaky-Wi-Fi case, and `/device` matches no route — stripping the code early
  // would strand the phone on a router error page with nothing left to retry.
  it('keeps the code in the address bar until sign-in succeeds', async () => {
    live()
    redeem.mockResolvedValue(undefined)
    render(<DeviceLoginPage />)
    expect(replaceState).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: /Sign in as Ops Person/ }))
    await vi.waitFor(() => expect(replaceState).toHaveBeenCalledWith(null, '', '/'))
  })

  it('leaves the code in the address bar when sign-in fails, so a reload can retry', async () => {
    live()
    // Shaped like the real 410 from `POST /auth/device/{token}`; the redeem path renders it
    // through `formatError`, which reads `data.error`.
    redeem.mockRejectedValue({
      status: 410,
      data: { error: 'This sign-in code is no longer valid', code: 'USED' },
    })
    render(<DeviceLoginPage />)

    fireEvent.click(screen.getByRole('button', { name: /Sign in as Ops Person/ }))
    await vi.waitFor(() =>
      expect(screen.getByText('This sign-in code is no longer valid')).toBeInTheDocument(),
    )
    expect(replaceState).not.toHaveBeenCalled()
    expect(assign).not.toHaveBeenCalled()
  })

  it('explains a dead code in its own terms rather than generically', () => {
    infoResult = { error: { status: 410, data: { code: 'EXPIRED' } } }
    render(<DeviceLoginPage />)
    expect(screen.getByText(/expired/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Sign in as/ })).not.toBeInTheDocument()
  })

  // A string status means the request never reached the server; reading the code regardless
  // would tell someone on bad venue Wi-Fi that a perfectly good code is invalid.
  it('does not blame the code for a network failure', () => {
    infoResult = { error: { status: 'FETCH_ERROR', error: 'offline' } }
    render(<DeviceLoginPage />)
    expect(screen.queryByText(/isn't valid/i)).not.toBeInTheDocument()
  })
})
