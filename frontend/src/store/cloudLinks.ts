import { restApi } from './restApi'
import { lightingApi } from '../api/lightingApi'
import type { AuthStatus } from './auth'

// Links between Lighting7 Cloud accounts and desk users (lighting7 cloud-p1 plan D12): how a remote
// session through the cloud knows which user it is. Machine-local, like the users they belong to.

/** Mirrors `CloudLinkVia` in lighting7's `models/cloudIdentityLinks.kt`. */
export type CloudLinkVia = 'ADOPTION' | 'INVITE' | 'PROVED'

export interface CloudLink {
  email: string
  linkedAt: string
  linkedVia: CloudLinkVia
}

export interface CloudInvite {
  inviteId: string
  email: string
  createdAt: string
  expiresAt: string
  /** The admin who sent it; null once they are deleted. */
  invitedBy?: string | null
}

/** The caller's own link and live invite. */
export interface OwnCloudLink {
  link?: CloudLink | null
  invite?: CloudInvite | null
}

export const CLOUD_LINK_VIA_LABELS: Record<CloudLinkVia, string> = {
  ADOPTION: 'linked when this desk was adopted',
  INVITE: 'linked by invite',
  PROVED: 'linked by signing in once',
}

export const cloudLinksApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    ownCloudLink: build.query<OwnCloudLink, void>({
      query: () => 'cloud/link',
      providesTags: ['CloudLink'],
    }),

    // Ends this account's remote sessions, this one too when it is one.
    unlinkOwnCloudAccount: build.mutation<void, void>({
      query: () => ({ url: 'cloud/link', method: 'DELETE' }),
      invalidatesTags: (_result, error) => (error ? [] : ['CloudLink', 'UserList', 'User']),
    }),

    // An unlinked remote session's prove-once. A wrong password is 401, which `restApi` is told is
    // no session loss; success makes the session its user's, which `auth/status` then says, and
    // re-handshakes the socket the desk closed while the session was unlinked, as a login does.
    proveCloudLink: build.mutation<AuthStatus, { username: string; password: string }>({
      query: (body) => ({ url: 'cloud/link/prove', method: 'POST', body }),
      invalidatesTags: (_result, error) => (error ? [] : ['Auth', 'CloudLink']),
      async onQueryStarted(_arg, { queryFulfilled }) {
        try {
          await queryFulfilled
        } catch {
          return
        }
        lightingApi.status.reconnect(true)
      },
    }),

    // Admin only. 409 `CLOUD_OFFLINE`, `CLOUD_LINK_EXISTS`, `CLOUD_INVITE_PENDING`, `CLOUD_NOT_ADOPTED`
    // and 400 `CLOUD_EMAIL_INVALID` are drawn inline by the sheet.
    inviteCloudAccount: build.mutation<CloudInvite, { userId: number; email: string }>({
      query: ({ userId, email }) => ({ url: `users/${userId}/cloud-invite`, method: 'POST', body: { email } }),
      invalidatesTags: (_result, error, { userId }) => (error ? [] : ['UserList', { type: 'User', id: userId }]),
    }),
    cancelCloudInvite: build.mutation<void, { userId: number }>({
      query: ({ userId }) => ({ url: `users/${userId}/cloud-invite`, method: 'DELETE' }),
      invalidatesTags: (_result, error, { userId }) => (error ? [] : ['UserList', { type: 'User', id: userId }]),
    }),
    unlinkCloudAccount: build.mutation<void, { userId: number }>({
      query: ({ userId }) => ({ url: `users/${userId}/cloud-link`, method: 'DELETE' }),
      invalidatesTags: (_result, error, { userId }) =>
        error ? [] : ['UserList', { type: 'User', id: userId }, 'CloudLink'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useOwnCloudLinkQuery,
  useUnlinkOwnCloudAccountMutation,
  useProveCloudLinkMutation,
  useInviteCloudAccountMutation,
  useCancelCloudInviteMutation,
  useUnlinkCloudAccountMutation,
} = cloudLinksApi
