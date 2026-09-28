import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"

// Desk-local user accounts. Users belong to the *machine*, not to a project — they
// are never exported, cloned or cloud-synced. The session lives in an httpOnly
// cookie, so nothing here can read it: `GET /auth/status` is the only source of
// truth about who (if anyone) is signed in.

export type UserRole = 'ADMIN' | 'OPERATOR'

export interface AuthUser {
  uuid: string
  username: string
  displayName: string
  role: UserRole
}

export interface AuthStatus {
  /** No users exist yet. The backend is bootstrap-open: the API is unauthenticated. */
  setupRequired: boolean
  authenticated: boolean
  // Explicitly `null` (not absent) when signed out — the backend serialises the
  // field either way, so consumers must test the value, not `'user' in status`.
  user?: AuthUser | null
}

/** How a session was created. `PASSWORD` for rows predating the column. */
export type SessionOrigin = 'PASSWORD' | 'QR'

export interface SessionInfo {
  id: number
  createdAt: string
  lastSeenAt: string
  userAgent: string | null
  /** The session this browser is using. It survives "sign out everywhere else". */
  current: boolean
  createdVia: SessionOrigin
}

/**
 * An app signed in to this account through the desk's MCP listener (OAuth) — Claude on a phone,
 * say. One row per grant: connecting the same app twice is two rows, each revocable.
 */
export interface ConnectedApp {
  id: number
  clientName: string
  createdAt: string
  /** When it last called a tool; updated at most every ten minutes, so it is a hint. */
  lastUsedAt: string
}

/** A freshly minted device-login QR, as the desk's sheet needs it. */
export interface DeviceLoginResponse {
  /** Opaque uuid, for the poll and cancel calls. */
  id: string
  /** The URL to render as a QR code. */
  url: string
  /** Other addresses the same page answers on, for a phone that can't reach `url`. */
  alternateUrls: string[]
  expiresAt: string
  displayName: string
}

export type DeviceLoginStatus = 'PENDING' | 'USED' | 'EXPIRED' | 'CANCELLED'

export interface DeviceLoginStatusInfo {
  status: DeviceLoginStatus
  expiresAt: string
  /**
   * The phone that took the QR, once one has. There is no confirmation step in this flow, so
   * this is the desk's only way to notice a *wrong* device took it — paired with `sessionId`
   * so the sheet can offer to sign that device straight back out.
   */
  redeemedByUserAgent?: string | null
  sessionId?: number | null
}

export interface LoginRequest {
  username: string
  password: string
}

export interface SetupRequest {
  username: string
  displayName: string
  password: string
}

export interface ChangePasswordRequest {
  currentPassword: string
  newPassword: string
}

export interface UpdateProfileRequest {
  displayName: string
}

// Shared by `login` and `setup`: both mint a session cookie, so both have to
// re-handshake the socket. It may have been opened before we had an identity
// (bootstrap-open), or closed with 4401 when the previous session died. `force`
// because in the bootstrap-open case the socket is still OPEN and a plain
// reconnect() no-ops, which would leave an anonymous socket streaming that Session
// 3's revocation flow has no session hash to revoke. Also used by `logout`, where
// the point is the reverse: drop the socket that still holds the departing session.
//
// The catch is load-bearing, not defensive: `queryFulfilled` rejects on a failed
// login and an uncaught rejection here surfaces as an unhandled promise rejection.
async function rehandshakeSocketOnSuccess(
  _arg: unknown,
  { queryFulfilled }: { queryFulfilled: Promise<unknown> },
) {
  try {
    await queryFulfilled
  } catch {
    return
  }
  lightingApi.status.reconnect(true)
}

export const authApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    authStatus: build.query<AuthStatus, void>({
      query: () => 'auth/status',
      providesTags: ['Auth'],
    }),

    // Both entry points mint a session cookie, so both have to re-handshake the
    // socket: it may have been opened before we had an identity (bootstrap-open),
    // or closed with 4401 when the previous session died. `force` because in the
    // bootstrap-open case the socket is still OPEN and a plain reconnect() no-ops,
    // which would leave an anonymous socket streaming that nothing can revoke.
    //
    // Both invalidate only on success and reconnect only on success: a rejected
    // login is a typo, and nothing about the session — or the socket — changed.
    // RTK Query applies a static `invalidatesTags` to failed mutations as well, so
    // the error has to be checked explicitly.
    login: build.mutation<AuthStatus, LoginRequest>({
      query: (body) => ({ url: 'auth/login', method: 'POST', body }),
      invalidatesTags: (_result, error) => (error ? [] : ['Auth', 'AuthSessions']),
      onQueryStarted: rehandshakeSocketOnSuccess,
    }),
    setup: build.mutation<AuthStatus, SetupRequest>({
      query: (body) => ({ url: 'auth/setup', method: 'POST', body }),
      invalidatesTags: (_result, error) => (error ? [] : ['Auth', 'AuthSessions']),
      onQueryStarted: rehandshakeSocketOnSuccess,
    }),

    // Logging out has to re-handshake for the same reason logging in does, and it
    // matters more: the backend only checks identity at the WS handshake (live
    // revocation is Session 3), and `lightingApi` is a module-level singleton that
    // unmounting the router does not touch. Without this the socket authenticated
    // as the operator who just left stays open and streaming behind the login
    // screen. The replacement carries no cookie, so the server closes it 4401 and
    // `internalApi` leaves it closed.
    logout: build.mutation<void, void>({
      query: () => ({ url: 'auth/logout', method: 'POST' }),
      invalidatesTags: ['Auth', 'AuthSessions'],
      onQueryStarted: rehandshakeSocketOnSuccess,
    }),

    // Revokes the caller's *other* sessions server-side but keeps this one, so the
    // session list changes without this browser being logged out. Every connected app goes
    // with them: an app has no "this one" to keep.
    changePassword: build.mutation<void, ChangePasswordRequest>({
      query: (body) => ({ url: 'auth/password', method: 'PUT', body }),
      invalidatesTags: ['AuthSessions', 'AuthConnectedApps'],
    }),

    // The one thing a user may change about themselves. Authenticated but *any* role
    // server-side, deliberately outside the admin-only `/users` subtree — the desk's own
    // account maintenance is not an administrative act.
    //
    // Success-only in the `(_result, error)` form `login`/`setup` use above, because RTK
    // Query applies a static `invalidatesTags` to failed mutations too. `Auth` is what
    // re-renders the header avatar's initials and the menu's name; `UserList`/`User` cover
    // an *admin* renaming themselves, whose own row in the users tab would otherwise sit
    // stale — a bare `'User'` invalidates every id'd entry of that type. Neither users
    // cache exists unless that tab was visited, so the extra tags cost nothing.
    updateProfile: build.mutation<AuthUser, UpdateProfileRequest>({
      query: (body) => ({ url: 'auth/profile', method: 'PUT', body }),
      invalidatesTags: (_result, error) => (error ? [] : ['Auth', 'UserList', 'User']),
    }),

    sessions: build.query<SessionInfo[], void>({
      query: () => 'auth/sessions',
      providesTags: ['AuthSessions'],
    }),
    // Also retires any live device-login QR for this account, server-side. "Sign out
    // everywhere else" is what someone presses when they think they've been compromised, and
    // leaving an exchangeable QR alive through it would defeat the point.
    revokeOtherSessions: build.mutation<void, void>({
      query: () => ({ url: 'auth/sessions', method: 'DELETE' }),
      invalidatesTags: ['AuthSessions', 'AuthConnectedApps', 'DeviceLogin'],
    }),

    // Grants made on the MCP listener's sign-in page. Revoked server-side by the same events
    // that end sessions (a password change, "sign out everywhere else", being disabled), which
    // is why those mutations invalidate this tag too.
    connectedApps: build.query<ConnectedApp[], void>({
      query: () => 'auth/connected-apps',
      providesTags: ['AuthConnectedApps'],
    }),
    revokeConnectedApp: build.mutation<void, { id: number }>({
      query: ({ id }) => ({ url: `auth/connected-apps/${id}`, method: 'DELETE' }),
      invalidatesTags: ['AuthConnectedApps'],
    }),

    // ─── Device-login QR (the desk's own side) ───────────────────────────
    //
    // Authenticated but open to *any* role — this is "sign my own phone in", not an
    // administrative act, so it can't live under the admin-only /users subtree. The two
    // public halves the phone calls are in ./deviceLogin.

    // Always mints for the caller: there is no target parameter, by design, so this can
    // never become a way to hand somebody else a session.
    createDeviceLogin: build.mutation<DeviceLoginResponse, void>({
      query: () => ({ url: 'auth/device-logins', method: 'POST' }),
    }),

    deviceLoginStatus: build.query<DeviceLoginStatusInfo, { id: string }>({
      query: ({ id }) => `auth/device-logins/${encodeURIComponent(id)}`,
      providesTags: (_result, _error, { id }) => [{ type: 'DeviceLogin', id }],
    }),

    // Fired as the sheet closes — the opposite call from the reset flow's history-and-revoke,
    // and deliberately so: a reset link can only ever set a password, whereas this one is a
    // way into the account, so it should not outlive the screen showing it by a second.
    cancelDeviceLogin: build.mutation<void, { id: string }>({
      query: ({ id }) => ({ url: `auth/device-logins/${encodeURIComponent(id)}`, method: 'DELETE' }),
      invalidatesTags: (_result, error, { id }) =>
        error == null ? [{ type: 'DeviceLogin', id }] : [],
    }),
  }),
  overrideExisting: false,
})

export const {
  useAuthStatusQuery,
  useLoginMutation,
  useSetupMutation,
  useLogoutMutation,
  useChangePasswordMutation,
  useUpdateProfileMutation,
  useSessionsQuery,
  useRevokeOtherSessionsMutation,
  useConnectedAppsQuery,
  useRevokeConnectedAppMutation,
  useCreateDeviceLoginMutation,
  useDeviceLoginStatusQuery,
  useCancelDeviceLoginMutation,
} = authApi

// The socket was rejected (close code 4401), which means this browser's session is
// gone — most likely revoked from another device or by an admin. Invalidating `Auth`
// makes AuthGate re-check and drop to the login screen, instead of the app sitting
// there with a dead socket. Mirrors the WS→cache bridges in ./bootStatus.ts.
lightingApi.auth.subscribeUnauthenticated(() => {
  store.dispatch(restApi.util.invalidateTags(['Auth']))
})

// Our own account changed — an admin renamed us, or re-roled us. Unlike its neighbour above this
// is a *change*, not a loss: the session is still good, but the display name, role or
// `setupRequired` in `auth/status` is stale, and the role drives which nav entries the sidebar
// offers. Re-reading it is what makes an admin's edit land on the phone or tablet it was about.
//
// The backend sends this **only** to sockets belonging to the affected account, so this is not a
// broadcast — see `plugins/MachineSocket.kt`. Don't be tempted to fold it into the user-list
// bridge in ./users.ts: every connected client refetching `auth/status` on any admin edit is a
// thundering herd for no gain.
lightingApi.auth.subscribeOwnAccountChanged(() => {
  store.dispatch(restApi.util.invalidateTags(['Auth']))
})
