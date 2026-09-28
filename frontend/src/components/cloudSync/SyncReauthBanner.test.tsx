// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Store- and router-connected, so both are mocked — same shape as
// components/auth/DeviceLoginSection.test.tsx, which also keeps the import graph away from
// lightingApi's real WebSocket (this module's store slice now opens one at import time).
//
// The dismissal semantics are the whole point of these tests: a banner that stays dismissed
// forever would recreate the bug it exists to prevent (a broken connection that looks healthy),
// and one that returns on every reload would train people to dismiss without reading.
let reauthState: {
  reauthRequired: boolean
  reauthReason?: string | null
  reauthRequiredAt?: string | null
} = { reauthRequired: false }

vi.mock('@/store/oauthGithub', () => ({
  useOAuthReauthState: () => reauthState,
}))

const navigate = vi.fn()
vi.mock('react-router', () => ({
  useNavigate: () => navigate,
}))

import { SyncReauthBanner } from './SyncReauthBanner'

const REJECTED_AT = new Date(1787063158854).toISOString()
const REJECTED_AT_LATER = new Date(1787063158854 + 60_000).toISOString()

describe('SyncReauthBanner', () => {
  beforeEach(() => {
    window.localStorage.clear()
    reauthState = { reauthRequired: false }
    navigate.mockClear()
  })
  afterEach(cleanup)

  it('renders nothing when the connection is healthy', () => {
    render(<SyncReauthBanner />)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('warns, with the reason, when GitHub has rejected the desk', () => {
    reauthState = {
      reauthRequired: true,
      reauthReason: 'GitHub rejected the refresh token (bad_refresh_token); user must re-connect.',
      reauthRequiredAt: REJECTED_AT,
    }
    render(<SyncReauthBanner />)

    const alert = screen.getByRole('alert')
    expect(alert.textContent).toContain('Cloud sync is not running')
    expect(alert.textContent).toContain('bad_refresh_token')
    expect(screen.getByRole('button', { name: /reconnect/i })).toBeTruthy()
  })

  it('stays dismissed for this rejection across a remount', () => {
    reauthState = { reauthRequired: true, reauthRequiredAt: REJECTED_AT }
    const first = render(<SyncReauthBanner />)
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByRole('alert')).toBeNull()

    // Remount stands in for a reload or a route change: the dismissal is persisted, not state.
    first.unmount()
    render(<SyncReauthBanner />)
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('comes back for a *later* rejection that was never dismissed', () => {
    // The property that stops this from becoming the original bug: a desk broken again in three
    // months must not be silently pre-dismissed by a click from today.
    window.localStorage.setItem('lighting7:syncReauthDismissedAt', REJECTED_AT)
    reauthState = { reauthRequired: true, reauthRequiredAt: REJECTED_AT_LATER }
    render(<SyncReauthBanner />)

    expect(screen.getByRole('alert')).toBeTruthy()
  })

  it('re-arms in a tab that was already open when the new rejection arrives', () => {
    // The store's WS bridge keeps the identity live, so the stamp can change under a mounted
    // banner with no reload — the case a plain `useState` initialiser would miss.
    reauthState = { reauthRequired: true, reauthRequiredAt: REJECTED_AT }
    const view = render(<SyncReauthBanner />)
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    expect(screen.queryByRole('alert')).toBeNull()

    reauthState = { reauthRequired: true, reauthRequiredAt: REJECTED_AT_LATER }
    view.rerender(<SyncReauthBanner />)
    expect(screen.getByRole('alert')).toBeTruthy()
  })

  it('sends the user to the install-level sync page, where the identity lives', () => {
    reauthState = { reauthRequired: true, reauthRequiredAt: REJECTED_AT }
    render(<SyncReauthBanner />)
    fireEvent.click(screen.getByRole('button', { name: /reconnect/i }))
    expect(navigate).toHaveBeenCalledWith('/install/sync')
  })
})
