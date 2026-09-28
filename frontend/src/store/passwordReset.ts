import { restApi } from './restApi'

// The two auth-exempt endpoints behind /reset/:token — a phone with no session and no
// cookie hits these directly, so they live outside ./users (admin, cookie-authenticated)
// and ./auth (this browser's own session). No tags: the phone page mounts once per
// token and there is nothing else in this cache for a redemption to invalidate.

export interface ResetTokenInfo {
  username: string
  displayName: string
  expiresAt: string
}

export const passwordResetApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    // 410 Gone with `code: USED | EXPIRED | CANCELLED` for a dead link, 404 for an
    // unknown one — ResetPasswordPage renders distinct copy per case rather than a
    // generic error, so the raw error payload is read directly from the query state.
    resetTokenInfo: build.query<ResetTokenInfo, { token: string }>({
      query: ({ token }) => `auth/reset/${encodeURIComponent(token)}`,
    }),

    // 400 for a password-policy failure (min 8 chars, max 72 UTF-8 bytes), same 410
    // codes as above if the token died between the GET and this POST. Revokes every
    // session the target user had, same as the admin-side setUserPassword.
    redeemResetToken: build.mutation<void, { token: string; newPassword: string }>({
      query: ({ token, newPassword }) => ({
        url: `auth/reset/${encodeURIComponent(token)}`,
        method: 'POST',
        body: { newPassword },
      }),
    }),
  }),
  overrideExisting: false,
})

export const { useResetTokenInfoQuery, useRedeemResetTokenMutation } = passwordResetApi
