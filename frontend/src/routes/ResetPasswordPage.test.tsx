// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// The page is store-connected and reads its token from the router. Mocking both keeps this
// a component test and keeps the import graph away from lightingApi's real WebSocket.
const redeem = vi.fn()
let infoResult: {
  data?: { username: string; displayName: string; expiresAt: string }
  error?: unknown
  isLoading?: boolean
} = {}

vi.mock('@/store/passwordReset', () => ({
  useResetTokenInfoQuery: () => infoResult,
  useRedeemResetTokenMutation: () => [
    (args: unknown) => ({ unwrap: () => redeem(args) }),
    { isLoading: false },
  ],
}))
vi.mock('react-router', () => ({
  useParams: () => ({ token: 'a-token' }),
}))

import { ResetPasswordPage } from './ResetPasswordPage'

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  infoResult = {}
})

function live() {
  infoResult = {
    data: { username: 'op', displayName: 'Ops Person', expiresAt: new Date(Date.now() + 60_000).toISOString() },
  }
}

function fill(password: string, confirm: string) {
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: password } })
  fireEvent.change(screen.getByLabelText('Confirm password'), { target: { value: confirm } })
}

describe('ResetPasswordPage', () => {
  it('names the account the link belongs to', () => {
    live()
    render(<ResetPasswordPage />)
    // Whoever scanned the QR needs to know whose password they are about to set — a
    // wrong-account reset is the mistake this page exists to make impossible.
    expect(screen.getByText('Ops Person')).toBeTruthy()
    expect(screen.getByText(/\(\s*op\s*\)/)).toBeTruthy()
  })

  it('refuses to submit until the password is long enough and confirmed', () => {
    live()
    render(<ResetPasswordPage />)
    const submit = screen.getByRole('button', { name: 'Set password' })

    fill('short', 'short')
    expect((submit as HTMLButtonElement).disabled).toBe(true)

    fill('long-enough-password', 'long-enough-passwOrd')
    expect(screen.getByText(/don't match/i)).toBeTruthy()
    expect((submit as HTMLButtonElement).disabled).toBe(true)

    fill('long-enough-password', 'long-enough-password')
    expect((submit as HTMLButtonElement).disabled).toBe(false)
  })

  it('redeems the token from the URL and confirms success', async () => {
    live()
    redeem.mockResolvedValue(undefined)
    render(<ResetPasswordPage />)

    fill('long-enough-password', 'long-enough-password')
    fireEvent.click(screen.getByRole('button', { name: 'Set password' }))

    expect(redeem).toHaveBeenCalledWith({ token: 'a-token', newPassword: 'long-enough-password' })
    expect(await screen.findByText(/You can now sign in on the desk/)).toBeTruthy()
  })

  it('explains a dead link by its status code rather than generically', () => {
    // The 410 body's `code` is the only thing separating "you're too late" from "that
    // isn't a link", and the two need different instructions.
    infoResult = { error: { status: 410, data: { code: 'EXPIRED' } } }
    render(<ResetPasswordPage />)
    expect(screen.getByText(/expired/i)).toBeTruthy()
    expect(screen.queryByLabelText('New password')).toBeNull()
  })

  it('falls back to generic copy for an unknown link', () => {
    infoResult = { error: { status: 404, data: { error: 'Unknown reset link' } } }
    render(<ResetPasswordPage />)
    expect(screen.getByText(/isn't valid/i)).toBeTruthy()
    expect(screen.queryByLabelText('New password')).toBeNull()
  })

  it('blames the network, not the link, when the request never reached the server', () => {
    // Flaky venue Wi-Fi must not be reported as "your link is invalid": the reader would
    // go ask for a replacement, which cancels the perfectly good link they are holding.
    infoResult = { error: { status: 'FETCH_ERROR', error: 'TypeError: Failed to fetch' } }
    render(<ResetPasswordPage />)
    expect(screen.getByText(/Could not reach the server/i)).toBeTruthy()
    expect(screen.queryByText(/isn't valid/i)).toBeNull()
  })
})
