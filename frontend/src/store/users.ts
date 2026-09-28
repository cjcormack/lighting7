import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import { store } from './index'
import type { UserRole } from './auth'

// Admin-only desk-account management. Distinct from ./auth: that module is "who am I and
// how do I sign in", this one is "who else is allowed to" — a different tag family so
// editing the user list never churns the caller's own session/auth cache.

export interface DeskUser {
  id: number
  uuid: string
  username: string
  displayName: string
  role: UserRole
  disabled: boolean
  createdAt: string
  lastLoginAt?: string | null
}

export interface NewUserRequest {
  username: string
  displayName: string
  role: UserRole
  password: string
}

// Every field optional: an absent field means "leave it alone", not "clear it" — the
// backend applies this as a partial patch, so sending `disabled: false` on every edit
// would silently re-enable a user the admin never meant to touch.
export interface UpdateUserRequest {
  displayName?: string
  role?: UserRole
  disabled?: boolean
}

export interface ResetTokenResponse {
  id: number
  url: string
  alternateUrls: string[]
  expiresAt: string
  username: string
  displayName: string
}

export type ResetTokenStatus = 'PENDING' | 'USED' | 'EXPIRED' | 'CANCELLED'

export interface ResetTokenStatusInfo {
  status: ResetTokenStatus
  expiresAt: string
}

/** One row of a user's reset-link history. Deliberately carries no token and no hash. */
export interface ResetTokenHistoryEntry {
  id: number
  status: ResetTokenStatus
  createdAt: string
  expiresAt: string
  usedAt?: string | null
  cancelledAt?: string | null
  /** The admin who minted it; null once that admin is deleted. */
  createdByDisplayName?: string | null
}

export const usersApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    users: build.query<DeskUser[], void>({
      query: () => 'users',
      providesTags: ['UserList'],
    }),

    user: build.query<DeskUser, { userId: number }>({
      query: ({ userId }) => `users/${userId}`,
      providesTags: (_result, _error, { userId }) => [{ type: 'User', id: userId }],
    }),

    // A duplicate username answers 409 (the backend leans on the unique index rather
    // than a pre-check, so it's race-proof) and a short password 400. Both are ordinary
    // flow steps rendered inline in CreateUserSheet, not toast material.
    createUser: build.mutation<DeskUser, NewUserRequest>({
      query: (body) => ({ url: 'users', method: 'POST', body }),
      invalidatesTags: (result) => (result == null ? [] : ['UserList']),
    }),

    // 409 `LAST_ADMIN` (demoting/disabling the only enabled admin) and `SELF_TARGET`
    // (disabling, deleting or re-roling yourself) are ordinary flow steps rendered inline in
    // UserDetailSheet. A disabling edit also revokes that user's sessions and closes their
    // socket server-side (Session 3.5) — nothing for this cache to reflect beyond the row
    // itself flipping to disabled.
    updateUser: build.mutation<DeskUser, { userId: number } & UpdateUserRequest>({
      query: ({ userId, ...body }) => ({ url: `users/${userId}`, method: 'PUT', body }),
      // `Auth` too, because an edit to *another* account can still change what its owner may
      // do, and `auth/status` is where the app reads roles from.
      //
      // This used to be justified by self-demotion — an admin who demoted themselves became
      // an OPERATOR from the next request, and 403s aren't intercepted the way 401s are
      // (`restApi`'s interceptor treats only 401 as "session gone"). The backend now refuses a
      // self-role change outright (409 SELF_TARGET, `FU-AUTH-SELF-ROLE-GUARD`), so that case
      // can no longer arise. The invalidation stays for the other-account case; one extra
      // status fetch per user edit, and edits are rare.
      invalidatesTags: (result, _error, { userId }) =>
        result == null ? [] : ['UserList', { type: 'User', id: userId }, 'Auth'],
    }),

    // Same LAST_ADMIN/SELF_TARGET guard as updateUser, and the same session/socket
    // teardown on success.
    deleteUser: build.mutation<void, { userId: number }>({
      query: ({ userId }) => ({ url: `users/${userId}`, method: 'DELETE' }),
      // The per-user entry goes too, not just the list: SQLite hands out the deleted row's
      // id again for the next account created after it, and a surviving cache entry under
      // that id would seed the detail sheet with the *deleted* user's name and role — an
      // admin could then save those onto the new account.
      invalidatesTags: (_result, error, { userId }) =>
        error ? [] : ['UserList', { type: 'User', id: userId }],
    }),

    // Direct set for "the user is standing next to you". No list field changes, so
    // nothing to invalidate — it only revokes that user's sessions server-side, which
    // this cache has no representation of.
    setUserPassword: build.mutation<void, { userId: number; newPassword: string }>({
      query: ({ userId, newPassword }) => ({
        url: `users/${userId}/password`,
        method: 'PUT',
        body: { newPassword },
      }),
    }),

    // Every reset link this account has had — how a live one stays visible now that closing
    // the QR sheet no longer cancels it. Newest first; never carries a raw token.
    resetTokenHistory: build.query<ResetTokenHistoryEntry[], { userId: number }>({
      query: ({ userId }) => `users/${userId}/reset-tokens`,
      providesTags: (_result, _error, { userId }) => [{ type: 'ResetTokenList', id: userId }],
    }),

    // Minting cancels that user's outstanding tokens server-side, so the history list has
    // *two* rows that change: the new PENDING one and the one it just superseded. ResetQrSheet
    // still holds the minted token in local state and polls `resetTokenStatus` for its own
    // display — the invalidation is for the list in UserDetailSheet behind it.
    createResetToken: build.mutation<ResetTokenResponse, { userId: number }>({
      query: ({ userId }) => ({ url: `users/${userId}/reset-tokens`, method: 'POST' }),
      invalidatesTags: (result, _error, { userId }) =>
        result == null ? [] : [{ type: 'ResetTokenList', id: userId }],
    }),

    resetTokenStatus: build.query<ResetTokenStatusInfo, { userId: number; tokenId: number }>({
      query: ({ userId, tokenId }) => `users/${userId}/reset-tokens/${tokenId}`,
      providesTags: (_result, _error, { tokenId }) => [{ type: 'ResetToken', id: tokenId }],
    }),

    // Now a deliberate revoke from the history list rather than a background fire-and-forget
    // as the sheet closed, so both the row's own status and the list it sits in have to
    // re-read — the whole point is that the admin watches it flip to CANCELLED.
    cancelResetToken: build.mutation<void, { userId: number; tokenId: number }>({
      query: ({ userId, tokenId }) => ({
        url: `users/${userId}/reset-tokens/${tokenId}`,
        method: 'DELETE',
      }),
      invalidatesTags: (_result, error, { userId, tokenId }) =>
        error == null
          ? [{ type: 'ResetTokenList', id: userId }, { type: 'ResetToken', id: tokenId }]
          : [],
    }),
  }),
  overrideExisting: false,
})

export const {
  useUsersQuery,
  useUserQuery,
  useCreateUserMutation,
  useUpdateUserMutation,
  useDeleteUserMutation,
  useSetUserPasswordMutation,
  useResetTokenHistoryQuery,
  useCreateResetTokenMutation,
  useResetTokenStatusQuery,
  useCancelResetTokenMutation,
} = usersApi

// A desk account changed on some other client — another admin's browser, a phone signed in by
// QR, a second desk. Without this the mutation's own `invalidatesTags` reaches only the tab that
// made the edit, and every other one keeps the old name until it happens to refetch.
//
// Bare `'User'` invalidates every id'd entry rather than the one that changed, because the frame
// deliberately carries no id — see `plugins/MachineSocket.kt` for why nothing about a user may
// travel over a socket an operator can hold open.
//
// **This relies on `UsersTab` being the only caller of `useUsersQuery`.** The backend does not
// role-filter the frame; it is safe for operators only because that call site passes
// `skip: !isAdmin`, so a non-admin has no subscriber and this dispatch is a no-op. A second call
// site without that guard would turn every operator socket into a 403 generator on every user
// edit. `ResetTokenList` is deliberately absent: the frame fires on account writes, not on token
// mints, so including it would cover half the staleness and look like it covered all of it.
lightingApi.users.subscribe(() => {
  store.dispatch(restApi.util.invalidateTags(['UserList', 'User']))
})
