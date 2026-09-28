import { restApi } from './restApi'

// The two auth-exempt endpoints behind /device/:token — a phone with no session hits these
// directly, so they live outside ./users (admin, cookie-authenticated) and ./auth (this
// browser's own session), for the same audience reason ./passwordReset does. No tags: the
// phone page mounts once per token and reloads the document on success, so there is no cache
// left for the redemption to invalidate.

export interface DeviceLoginInfo {
  username: string
  displayName: string
  expiresAt: string
}

export const deviceLoginApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // Whose account this QR signs you in as. 410 Gone with `code: USED | EXPIRED |
    // CANCELLED` for a dead code, 404 for an unknown one — and 404 also for a request from
    // off the LAN, deliberately, so a probe from outside learns nothing.
    //
    // A GET, and it does *not* consume the token: the page shows the name and waits for a
    // tap, so a scanner prefetch or a StrictMode double-render can't burn a two-minute code.
    deviceLoginInfo: build.query<DeviceLoginInfo, { token: string }>({
      query: ({ token }) => `auth/device/${encodeURIComponent(token)}`,
    }),

    // The exchange. Sends an empty JSON object rather than no body at all: a bodiless public
    // POST would be reachable by a cross-origin auto-submitting form, and the desk's CSRF
    // answer is "SameSite=Lax plus JSON-only endpoints" — the backend's `call.receive` needs
    // something to refuse a form encoding with. On success the session cookie is set and the
    // caller should do a full document load, because `App.tsx` reads its public-path flag
    // once at module scope.
    redeemDeviceLogin: build.mutation<void, { token: string }>({
      query: ({ token }) => ({
        url: `auth/device/${encodeURIComponent(token)}`,
        method: 'POST',
        body: {},
      }),
    }),
  }),
  overrideExisting: false,
})

export const { useDeviceLoginInfoQuery, useRedeemDeviceLoginMutation } = deviceLoginApi
