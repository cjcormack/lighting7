import { lightingApi } from "../api/lightingApi"
import { useIsNavAdmin } from "../navigation"
import { restApi } from "./restApi"
import { store } from "./index"

// ─── Types ─────────────────────────────────────────────────────────────

/**
 * Install-wide GitHub OAuth identity. Returned by `GET /oauth/github/identity`.
 *
 * `oauthConfigured` is independent of `connected` — it's `true` whenever
 * `sync.oauth.github.clientId` is set in the install's `local.conf`. The UI uses
 * it to decide whether to show the "Connect GitHub" path at all (otherwise only
 * the Advanced/PAT entry is offered).
 */
export interface OAuthIdentity {
  connected: boolean
  oauthConfigured: boolean
  login?: string | null
  githubUserId?: number | null
  accessExpiresAt?: string | null
  refreshExpiresAt?: string | null
  connectedAt?: string | null
  /**
   * Connected, but GitHub has rejected the stored refresh token — nothing will sync over
   * OAuth until the user reconnects. `connected` stays `true` (the identity still names
   * who it belongs to), so **every surface that reads `connected` as "OAuth works" must
   * also check this**: that conflation is what let the desk display "Connected as @user,
   * refreshing soon" for 25 days while every sync failed.
   */
  reauthRequired?: boolean
  /** GitHub's stated reason, when `reauthRequired`. */
  reauthReason?: string | null
  /** When the rejection was first seen, when `reauthRequired`. */
  reauthRequiredAt?: string | null
}

export interface GithubRepo {
  fullName: string
  name: string
  owner: string
  private: boolean
  defaultBranch: string
  htmlUrl: string
  cloneUrl: string
  description: string | null
  pushPermission: boolean
  /**
   * Lighting-project metadata, populated only when the list is fetched with
   * `lightingOnly: true` (the backend probes each repo's `project.json`). `projectName`
   * / `projectDescription` come from that `project.json`, not the GitHub repo fields.
   */
  lightingProject: boolean
  projectName: string | null
  projectDescription: string | null
  projectUuid: string | null
}

export interface CreateRepoBody {
  name: string
  private: boolean
  description?: string | null
}

export interface DeviceFlowStartResponse {
  deviceCode: string
  userCode: string
  verificationUri: string
  /** Lifetime in seconds. */
  expiresIn: number
  /** Recommended polling interval in seconds. */
  interval: number
}

export type DeviceFlowStatus = "PENDING" | "SLOW_DOWN" | "DONE" | "EXPIRED" | "DENIED"

export interface DeviceFlowPollResponse {
  status: DeviceFlowStatus
  login?: string | null
}

// ─── Endpoints ─────────────────────────────────────────────────────────

/**
 * GitHub OAuth identity + repo discovery. Identity is install-wide (one signed-in
 * GitHub user shared across every project); repo lookup honours the App's
 * per-installation permission grant.
 */
export const oauthGithubApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    oauthGithubIdentity: build.query<OAuthIdentity, void>({
      query: () => 'oauth/github/identity',
      providesTags: ['OAuthIdentity'],
    }),
    disconnectOAuthGithub: build.mutation<void, void>({
      query: () => ({
        url: 'oauth/github/identity',
        method: 'DELETE',
      }),
      // Disconnect removes the identity AND wipes the install's repo permissions,
      // so the cached repo list is no longer accurate.
      invalidatesTags: ['OAuthIdentity', 'OAuthRepos'],
    }),
    listGithubRepos: build.query<
      GithubRepo[],
      { query?: string | null; page?: number; perPage?: number; lightingOnly?: boolean }
    >({
      query: (args) => ({
        url: 'oauth/github/repositories',
        params: {
          query: args.query?.trim() ? args.query.trim() : undefined,
          page: args.page,
          perPage: args.perPage,
          lightingOnly: args.lightingOnly ? true : undefined,
        },
      }),
      providesTags: ['OAuthRepos'],
    }),
    createGithubRepo: build.mutation<GithubRepo, CreateRepoBody>({
      query: (body) => ({
        url: 'oauth/github/repositories',
        method: 'POST',
        body,
      }),
      // A new repo widens the installation's repo set; refresh the list cache.
      invalidatesTags: ['OAuthRepos'],
    }),
    startGithubDeviceFlow: build.mutation<DeviceFlowStartResponse, void>({
      query: () => ({
        url: 'oauth/github/device/start',
        method: 'POST',
      }),
    }),
    pollGithubDeviceFlow: build.mutation<DeviceFlowPollResponse, { deviceCode: string }>({
      query: (body) => ({
        url: 'oauth/github/device/poll',
        method: 'POST',
        body,
      }),
      // Successful poll establishes the identity; let the next render see it.
      invalidatesTags: (_result, _err) => ['OAuthIdentity'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useOauthGithubIdentityQuery,
  useDisconnectOAuthGithubMutation,
  useListGithubReposQuery,
  useCreateGithubRepoMutation,
  useStartGithubDeviceFlowMutation,
  usePollGithubDeviceFlowMutation,
} = oauthGithubApi

/**
 * The install-wide GitHub identity changed on the desk — connected, disconnected, refreshed,
 * or found dead by a background refresh. A module-scope bridge rather than a per-component
 * subscription (the shape `store/users.ts` uses) because the identity is now watched from the
 * sidebar badge and the global banner as well as the two sync pages: four subscriptions to
 * keep in step, when what they all want is simply for the cache never to be stale.
 *
 * The repo list is busted only when the identity's *usability* flips, because a token refresh
 * doesn't change which repos the App can see. Keying that on `connected` alone would be wrong:
 * a rejected identity stays `connected: true` for the whole reject → reconnect cycle, so the one
 * transition that really can change the App's repo grant — re-authorising, especially via the
 * in-page device-code flow, which never navigates away — would be the one it missed.
 *
 * **Every caller of `useOauthGithubIdentityQuery` must skip for non-admins**, because
 * `/api/rest/oauth/` is admin-gated (`auth/AuthGate.kt`): an operator's refetch can only 403.
 * [useOAuthReauthState] bakes that guard in; use it rather than the raw query for anything
 * outside the admin-only sync pages.
 *
 * **Started explicitly from `main.tsx`, not on import** — unlike the equivalent in
 * `store/users.ts`, which subscribes at module scope. That difference is load-bearing: this
 * module is now imported from the earliest render path (the sidebar badge), so its body can run
 * while `api/lightingApi` is still mid-initialisation, and touching `lightingApi` there throws a
 * TDZ `ReferenceError` that takes every export of this slice with it — the whole sidebar and
 * banner render as "not defined". `tsc`, `vite build` and the unit tests all pass regardless,
 * because the cycle only exists at runtime and the tests mock this module; it shows up solely as
 * a blank-looking app in the browser. Keep the subscription lazy.
 */
let lastIdentityUsable: boolean | null = null
let bridgeStarted = false

export function startOAuthIdentityBridge(): void {
  if (bridgeStarted) return // HMR re-runs the entry; a second subscriber would double-invalidate.
  bridgeStarted = true
  lightingApi.cloudSync.subscribeOAuthIdentityChanged((event) => {
    store.dispatch(restApi.util.invalidateTags(['OAuthIdentity']))
    const usable = event.connected && event.reauthRequired !== true
    if (lastIdentityUsable !== null && lastIdentityUsable !== usable) {
      store.dispatch(restApi.util.invalidateTags(['OAuthRepos']))
    }
    lastIdentityUsable = usable
  })
}

/**
 * Whether the desk's GitHub connection needs re-authorising, for the surfaces that warn about
 * it outside the sync pages (the sidebar badge and the global banner).
 *
 * Skips entirely for operators: they cannot read the endpoint (403) and cannot fix the problem,
 * and the desk deliberately doesn't nag whoever is running a show. So this reports "nothing
 * wrong" for them — it is a prompt for the person who can act, not a status display.
 */
export function useOAuthReauthState(): {
  reauthRequired: boolean
  reauthReason?: string | null
  reauthRequiredAt?: string | null
} {
  const isAdmin = useIsNavAdmin()
  const { data } = useOauthGithubIdentityQuery(undefined, { skip: !isAdmin })
  return {
    reauthRequired: data?.connected === true && data.reauthRequired === true,
    reauthReason: data?.reauthReason,
    reauthRequiredAt: data?.reauthRequiredAt,
  }
}
