import { createApi, fetchBaseQuery } from "@reduxjs/toolkit/query/react"
import type { BaseQueryFn, FetchArgs, FetchBaseQueryError } from "@reduxjs/toolkit/query/react"

// Same-origin is fetch's default, but the session cookie is the whole auth mechanism
// here, so state it rather than leaving it to a default someone could change.
const rawBaseQuery = fetchBaseQuery({
  baseUrl: '/api/rest',
  credentials: 'same-origin',
  prepareHeaders: (headers) => {
    const window = deskWindowOf()
    if (window) headers.set(DESK_WINDOW_HEADER, window)
    return headers
  },
})

/**
 * The desk window a REST call comes from (undo plan §3.2): this window's `windows.state` row id, which
 * the desk's history shows beside the source (*You · Desk left*). `store/windows.ts` supplies it, since
 * this module is below the WS layer and must not import it.
 */
export const DESK_WINDOW_HEADER = 'X-Desk-Window'

let deskWindowOf: () => string | null = () => null

export function setDeskWindowSource(source: () => string | null): void {
  deskWindowOf = source
}

// Endpoints whose 401 means "wrong password", not "your session died". All three
// take a password the user typed: `login`/`setup` answer 401 for bad credentials,
// and `changePassword` answers 401 for a wrong *current* password (lighting7 throws
// AuthenticationException, which ErrorHandling.kt maps to 401). Each renders the
// message inline, so invalidating Auth would only churn a refetch of a status we
// already know.
//
// `authStatus` is here for a different reason: it is the query the invalidation
// refetches. The backend keeps it auth-exempt so it should never 401 — but that is
// a hand-maintained path list in another repo, and if it ever stops matching, a
// 401 here would refetch itself forever. One string buys immunity from that.
const NOT_A_SESSION_LOSS: ReadonlySet<string> = new Set([
  'login',
  'setup',
  'changePassword',
  'authStatus',
])

/**
 * The history's request id every REST write answers (undo plan §3.2): the entry holding the write
 * lists it in `requestIds`, which is how a toast's Undo names the change it made.
 */
export const HISTORY_ENTRY_HEADER = 'X-History-Entry'

const historyTrackers = new Set<{ endpoints: ReadonlySet<string>; seen: string[] }>()

/**
 * Collect the `X-History-Entry` of every answer to [endpoints] — the mutations a gesture sends — until
 * `stop()`, in order: the requests it made, for its Undo. Named by endpoint so another write this
 * window makes meanwhile, a cue editor's flush or a sheet's edit, is not taken back with it.
 */
export function trackHistoryWrites(endpoints: readonly string[]): { stop(): string[] } {
  const tracker = { endpoints: new Set(endpoints), seen: [] as string[] }
  historyTrackers.add(tracker)
  return {
    stop: () => {
      historyTrackers.delete(tracker)
      return tracker.seen
    },
  }
}

/**
 * Wraps the base query so that a 401 anywhere flips the app back to the login screen, and hands each
 * answer's `X-History-Entry` to the trackers listening for its endpoint.
 *
 * This is the entire logout mechanism: invalidating `Auth` refetches
 * `GET /auth/status`, which now answers `authenticated: false`, and `AuthGate` swaps
 * in the login screen.
 *
 * `restApi` is read inside the async body, not at module-eval time, so the binding is
 * populated by the time any request runs. `api.dispatch` rather than importing the
 * store, which would be a genuine import cycle (store/index.ts imports this module).
 */
const baseQueryWithAuthCheck: BaseQueryFn<string | FetchArgs, unknown, FetchBaseQueryError> =
  async (args, api, extraOptions) => {
    const result = await rawBaseQuery(args, api, extraOptions)
    const requestId = result.meta?.response?.headers.get(HISTORY_ENTRY_HEADER)
    if (requestId) for (const tracker of historyTrackers) if (tracker.endpoints.has(api.endpoint)) tracker.seen.push(requestId)
    if (result.error?.status === 401 && !NOT_A_SESSION_LOSS.has(api.endpoint)) {
      api.dispatch(restApi.util.invalidateTags(['Auth']))
    }
    return result
  }

/**
 * Every REST cache tag in the app.
 *
 * Exported because the reconnect resync in `./status.ts` derives its invalidation set from
 * this array rather than restating it: the previous hand-maintained list had drifted to 15 of
 * these, leaving twenty tags — `ProgramState` and `Patch` among them — with no path back to the
 * server after a laptop sleep or a lighting7 restart.
 */
export const REST_TAG_TYPES = ['Channel', 'Fixture', 'Script', 'Project', 'ProjectList', 'GroupList', 'GroupActiveFxList', 'FixtureFx', 'FxLibrary', 'Look', 'LookList', 'PaletteList', 'SpeedMaster', 'SpeedMasterList', 'Cue', 'CueList', 'CueStackList', 'CueStackTimeline', 'CueSlotList', 'BuskPage', 'BuskRig', 'Patch', 'UniverseConfig', 'ProgramState', 'ControlSurfaceType', 'SurfaceBinding', 'PerfMidi', 'Install', 'Telemetry', 'CloudSyncConfig', 'CloudSyncStatus', 'CloudSyncLog', 'CloudSyncActivity', 'CloudSyncConflicts', 'OAuthIdentity', 'OAuthRepos', 'Rigging', 'StageRegion', 'StageElement', 'SceneImage', 'StageViewpoint', 'PromptBook', 'BootStatus', 'Locate', 'Auth', 'AuthSessions', 'AuthConnectedApps', 'UserList', 'User', 'ResetToken', 'ResetTokenList', 'DeviceLogin', 'Update', 'RemoteAccess', 'Cloud', 'Venue', 'VenueUpdate', 'History'] as const

export type RestTagType = (typeof REST_TAG_TYPES)[number]

export const restApi = createApi({
  baseQuery: baseQueryWithAuthCheck,
  tagTypes: REST_TAG_TYPES,
  endpoints: () => ({}),
})
