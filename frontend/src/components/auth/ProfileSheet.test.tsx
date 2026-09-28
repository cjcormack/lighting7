// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// Store-connected, so the store module is mocked — same shape as UserDetailSheet.test.tsx,
// which also keeps the import graph away from lightingApi's real WebSocket. The factory is
// hoisted above everything else in this file, so it can't close over helpers declared here.
const changePassword = vi.fn()
const updateProfile = vi.fn()
const createDeviceLogin = vi.fn()
const cancelDeviceLogin = vi.fn()
let sessionsResult: { data?: unknown[]; isLoading?: boolean; isError?: boolean } = { data: [] }
const revokeConnectedApp = vi.fn()
let appsResult: { data?: unknown[]; isError?: boolean } = { data: [] }

vi.mock('@/store/auth', () => ({
  useChangePasswordMutation: () => [
    (args: unknown) => ({ unwrap: () => changePassword(args) }),
    { isLoading: false },
  ],
  useUpdateProfileMutation: () => [
    (args: unknown) => ({ unwrap: () => updateProfile(args) }),
    { isLoading: false },
  ],
  useRevokeOtherSessionsMutation: () => [
    () => ({ unwrap: () => Promise.resolve() }),
    { isLoading: false },
  ],
  useSessionsQuery: () => sessionsResult,
  useConnectedAppsQuery: () => appsResult,
  useRevokeConnectedAppMutation: () => [
    (args: unknown) => ({ unwrap: () => revokeConnectedApp(args) }),
    { isLoading: false },
  ],
  useCreateDeviceLoginMutation: () => [
    () => ({ unwrap: () => createDeviceLogin() }),
    { isLoading: false },
  ],
  useCancelDeviceLoginMutation: () => [
    (args: unknown) => {
      cancelDeviceLogin(args)
      return { unwrap: () => Promise.resolve() }
    },
    { isLoading: false },
  ],
  useDeviceLoginStatusQuery: () => ({}),
}))
vi.mock('sonner', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))

import { ProfileSheet } from './ProfileSheet'

const user = {
  uuid: 'u-7',
  username: 'boss',
  displayName: 'The Boss',
  role: 'ADMIN' as const,
}

beforeEach(() => {
  changePassword.mockResolvedValue(undefined)
  updateProfile.mockResolvedValue(user)
  createDeviceLogin.mockResolvedValue({
    id: 'code-1',
    url: 'http://desk.local:8413/device/a-code',
    alternateUrls: [],
    expiresAt: new Date(Date.now() + 120_000).toISOString(),
    displayName: 'The Boss',
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  sessionsResult = { data: [] }
  appsResult = { data: [] }
})

function renderSheet(open = true) {
  return render(<ProfileSheet user={user} open={open} onOpenChange={() => {}} />)
}

// The four concerns are tabs, so a test has to be on the right one before its controls exist in
// the DOM at all — Radix renders only the active tab's content.
//
// `mouseDown`, not `click`: Radix Tabs selects on pointer-down (and on focus, its default
// "automatic" activation), so a bare click leaves the tab unchanged and every query below it
// fails with a confusing "unable to find" instead.
const goTo = (name: 'Profile' | 'Password' | 'Devices' | 'Sign-in') => {
  const trigger = screen.getByRole('tab', { name })
  fireEvent.mouseDown(trigger)
  fireEvent.focus(trigger)
}

const saveButton = () => screen.getByRole('button', { name: /Save name/ })
const passwordButton = () => screen.getByRole('button', { name: /Change password/ })
const typeName = (value: string) =>
  fireEvent.change(screen.getByLabelText('Display name'), { target: { value } })
const fillPassword = (current = 'old-one', next = 'a-new-password', confirm = next) => {
  fireEvent.change(screen.getByLabelText('Current password'), { target: { value: current } })
  fireEvent.change(screen.getByLabelText('New password'), { target: { value: next } })
  fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: confirm } })
}

describe('ProfileSheet', () => {
  describe('the display name', () => {
    it('saves on its own, trimmed, without touching the password', async () => {
      renderSheet()
      expect(saveButton()).toBeDisabled()

      typeName('  The Boss Jr  ')
      expect(saveButton()).toBeEnabled()
      fireEvent.click(saveButton())

      await vi.waitFor(() =>
        expect(updateProfile).toHaveBeenCalledWith({ displayName: 'The Boss Jr' }),
      )
      expect(changePassword).not.toHaveBeenCalled()
    })

    it('needs no current password', () => {
      renderSheet()
      typeName('The Boss Jr')

      // The reason the two saves are separate at all: the password submit needs the current
      // one, and a rename must never inherit that requirement. With the two on separate tabs
      // the password fields aren't even rendered here, which is the structural half of the
      // same guarantee.
      expect(screen.queryByLabelText('Current password')).not.toBeInTheDocument()
      expect(saveButton()).toBeEnabled()
    })

    it('refuses a name the server would reject', () => {
      renderSheet()
      typeName('x'.repeat(101))

      expect(saveButton()).toBeDisabled()
      expect(screen.getByText(/100 characters or fewer/)).toBeInTheDocument()
    })
  })

  describe('the password', () => {
    it('stays disabled until all three fields agree', () => {
      renderSheet()
      goTo('Password')
      expect(passwordButton()).toBeDisabled()

      fireEvent.change(screen.getByLabelText('Current password'), { target: { value: 'old-one' } })
      fireEvent.change(screen.getByLabelText('New password'), { target: { value: 'a-new-password' } })
      expect(passwordButton()).toBeDisabled()

      fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'mismatch' } })
      expect(passwordButton()).toBeDisabled()
      expect(screen.getByText(/don't match/)).toBeInTheDocument()

      fireEvent.change(screen.getByLabelText('Confirm new password'), { target: { value: 'a-new-password' } })
      expect(passwordButton()).toBeEnabled()
    })

    it('does not carry the display name with it', async () => {
      renderSheet()
      // Typed but not saved, then left behind on the other tab: the password submit must not
      // pick it up on the way past.
      typeName('Renamed But Not Saved')
      goTo('Password')
      fillPassword()
      fireEvent.click(passwordButton())

      await vi.waitFor(() => expect(changePassword).toHaveBeenCalledTimes(1))
      expect(updateProfile).not.toHaveBeenCalled()
    })
  })

  describe('the signed-in devices', () => {
    it('says so rather than offering an action when there are no others', () => {
      sessionsResult = { data: [{ id: 1, current: true, lastSeenAt: new Date(0).toISOString(), userAgent: null, createdVia: 'PASSWORD' }] }
      renderSheet()
      goTo('Devices')

      expect(screen.getByRole('button', { name: /No other devices signed in/ })).toBeDisabled()
    })

    it('counts the others when it knows the count', () => {
      sessionsResult = {
        data: [
          { id: 1, current: true, lastSeenAt: new Date(0).toISOString(), userAgent: null, createdVia: 'PASSWORD' },
          { id: 2, current: false, lastSeenAt: new Date(0).toISOString(), userAgent: null, createdVia: 'QR' },
        ],
      }
      renderSheet()
      goTo('Devices')

      expect(screen.getByRole('button', { name: /Sign out everywhere else \(1\)/ })).toBeEnabled()
    })

    it('stays actionable when the list failed to load', () => {
      // Refusing to act on a list we couldn't fetch would strand whoever came here to kick a
      // device off; the backend is the authority either way.
      sessionsResult = { isError: true }
      renderSheet()
      goTo('Devices')

      expect(screen.getByRole('button', { name: /^Sign out everywhere else$/ })).toBeEnabled()
      expect(screen.getByText(/Couldn't load your signed-in devices/)).toBeInTheDocument()
    })
  })

  describe('connected apps', () => {
    it('draws nothing when no app has ever been connected', () => {
      renderSheet()
      goTo('Devices')
      expect(screen.queryByText('Connected apps')).not.toBeInTheDocument()
    })

    it('lists each grant and revokes the one pressed', async () => {
      revokeConnectedApp.mockResolvedValue(undefined)
      const at = new Date(0).toISOString()
      appsResult = {
        data: [
          { id: 4, clientName: 'Claude', createdAt: at, lastUsedAt: at },
          { id: 9, clientName: 'Claude Code', createdAt: at, lastUsedAt: at },
        ],
      }
      renderSheet()
      goTo('Devices')

      expect(screen.getByText('Connected apps')).toBeInTheDocument()
      fireEvent.click(screen.getByRole('button', { name: 'Revoke Claude Code' }))
      await vi.waitFor(() => expect(revokeConnectedApp).toHaveBeenCalledWith({ id: 9 }))
    })
  })

  describe('the sign-in QR', () => {
    const showCode = () => goTo('Sign-in')

    it('mints nothing while the sheet is merely open', () => {
      renderSheet()
      // Opening Profile to change a password must not hand out a way into the account. The
      // Sign-in tab has to be navigated to, and *that* is the request for a code.
      expect(createDeviceLogin).not.toHaveBeenCalled()
      goTo('Devices')
      expect(createDeviceLogin).not.toHaveBeenCalled()
    })

    it('mints exactly one code on arriving at the tab', async () => {
      renderSheet()
      showCode()

      expect(await screen.findByText('http://desk.local:8413/device/a-code')).toBeInTheDocument()
      expect(createDeviceLogin).toHaveBeenCalledTimes(1)
    })

    it('cancels the code when the sheet closes, and does not mint again on reopen', async () => {
      const { rerender } = renderSheet()
      showCode()
      await screen.findByText('http://desk.local:8413/device/a-code')

      rerender(<ProfileSheet user={user} open={false} onOpenChange={() => {}} />)
      expect(cancelDeviceLogin).toHaveBeenCalledWith({ id: 'code-1' })

      // The sheet stays mounted for the whole session, so a `showQr` left set would mint a
      // live code the next time anyone opened Profile — with nobody having pressed anything.
      rerender(<ProfileSheet user={user} open onOpenChange={() => {}} />)
      expect(createDeviceLogin).toHaveBeenCalledTimes(1)

      // Reopening lands back on the first tab, which is what makes "nothing mints on open"
      // true: the Sign-in tab is not mounted, so no second code exists.
      expect(screen.getByRole('tab', { name: 'Profile' })).toHaveAttribute('data-state', 'active')
      expect(createDeviceLogin).toHaveBeenCalledTimes(1)
    })

    it('cancels the code when you leave the tab, and mints a fresh one on return', async () => {
      renderSheet()
      showCode()
      await screen.findByText('http://desk.local:8413/device/a-code')

      // Radix unmounts the inactive tab's content, so switching away cancels the code by the
      // same teardown a sheet close uses.
      goTo('Password')
      expect(cancelDeviceLogin).toHaveBeenCalledWith({ id: 'code-1' })

      // Coming back is a fresh request, so it mints again — the tab *is* the button.
      showCode()
      await screen.findByText('http://desk.local:8413/device/a-code')
      expect(createDeviceLogin).toHaveBeenCalledTimes(2)
    })
  })
})
