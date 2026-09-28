// @vitest-environment jsdom
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

// The sheet is store-connected. Mocking the store module keeps this a component test and
// keeps the import graph away from lightingApi's real WebSocket, as ResetPasswordPage's test
// does. Every hook it reaches for gets a quiet stub — the point here is what renders for
// `isSelf`, not what any request returns.
// The factory is hoisted above everything else in this file, so it can't close over a helper
// declared here — hence the repetition.
// Hoisted alongside the factory below so the factory can close over it — a plain `let` here
// would be initialised after the hoisted mock runs. Lets one test hand `useUserQuery` an error.
const mocks = vi.hoisted(() => ({
  userQuery: { data: undefined } as { data: undefined; error?: unknown },
}))

vi.mock('@/store/users', () => {
  const mutation = () => [
    (args: unknown) => ({ unwrap: () => Promise.resolve(args) }),
    { isLoading: false },
  ]
  return {
    useUserQuery: () => mocks.userQuery,
    useUpdateUserMutation: mutation,
    useDeleteUserMutation: mutation,
    useSetUserPasswordMutation: mutation,
    useCreateResetTokenMutation: mutation,
    useCancelResetTokenMutation: mutation,
    useResetTokenStatusQuery: () => ({ data: undefined }),
    useResetTokenHistoryQuery: () => ({ data: [], isLoading: false }),
  }
})

import { UserDetailSheet } from './UserDetailSheet'

const user = {
  id: 7,
  uuid: 'u-7',
  username: 'boss',
  displayName: 'The Boss',
  role: 'ADMIN' as const,
  disabled: false,
  createdAt: new Date(0).toISOString(),
  lastLoginAt: null,
}

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
  mocks.userQuery = { data: undefined }
})

function renderSheet(isSelf: boolean) {
  render(<UserDetailSheet user={user} open onOpenChange={() => {}} isSelf={isSelf} />)
}

describe('UserDetailSheet', () => {
  // FU-AUTH-SELF-RESET-GUARD and FU-AUTH-SELF-ROLE-GUARD. Both are enforced by the backend —
  // these assertions are about not offering an action that can only ever answer 409.
  describe('for your own account', () => {
    it('offers no way to reset or set your own password', () => {
      renderSheet(true)
      expect(screen.queryByRole('button', { name: /Reset with a QR code/ })).not.toBeInTheDocument()
      // Absent, not merely disabled: a QR here would put a link that re-passwords an admin
      // account on the desk's own screen, for anyone passing to photograph.
      expect(screen.queryByLabelText('New password')).not.toBeInTheDocument()
      expect(screen.getByText(/Profile…/)).toBeInTheDocument()
    })

    it('will not let you change your own role', () => {
      renderSheet(true)
      expect(screen.getByRole('combobox')).toBeDisabled()
      expect(screen.getByText(/another administrator has to do it/)).toBeInTheDocument()
    })
  })

  describe('for somebody else', () => {
    it('offers both password routes and an editable role', () => {
      renderSheet(false)
      expect(screen.getByRole('button', { name: /Reset with a QR code/ })).toBeInTheDocument()
      expect(screen.getByLabelText('New password')).toBeInTheDocument()
      expect(screen.getByRole('combobox')).not.toBeDisabled()
    })
  })

  // Now that the user list self-heals over the WebSocket (store/users.ts), another admin's
  // delete lands here while the sheet is open. Falling back to the stale list row would leave
  // Save / Delete / Reset buttons on an account that no longer exists, and the list behind the
  // sheet would visibly disagree with it.
  describe('when the account is deleted from another client', () => {
    it('replaces the form with an explanation instead of stale controls', () => {
      mocks.userQuery = { data: undefined, error: { status: 404 } }
      renderSheet(false)

      expect(screen.getByText(/no longer exists/)).toBeInTheDocument()
      expect(screen.queryByLabelText('Display name')).not.toBeInTheDocument()
      expect(screen.queryByLabelText('New password')).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^Save$/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /^Delete$/ })).not.toBeInTheDocument()
      expect(screen.queryByRole('button', { name: /Reset with a QR code/ })).not.toBeInTheDocument()
    })

    it('keeps rendering normally for a non-404 failure', () => {
      // A dropped connection is not a deletion. `FETCH_ERROR` carries a *string* status, so a
      // check loose enough to treat any error as "gone" would blank the sheet on a blip.
      mocks.userQuery = { data: undefined, error: { status: 'FETCH_ERROR' } }
      renderSheet(false)

      expect(screen.queryByText(/no longer exists/)).not.toBeInTheDocument()
      expect(screen.getByLabelText('Display name')).toBeInTheDocument()
    })
  })
})
