import { restApi } from "./restApi"
import { lightingApi } from "../api/lightingApi"
import { store } from "./index"

/** Mirrors `UpdateChannelKind` in `update/UpdateDtos.kt`. */
export type UpdateChannelKind = 'PACKAGED_WINDOWS' | 'DEV' | 'UNSUPPORTED_PLATFORM'

export type UpdatePhase =
  | 'IDLE'
  | 'CHECKING'
  | 'UPDATE_AVAILABLE'
  | 'DOWNLOADING'
  | 'READY_TO_APPLY'
  | 'APPLY_REQUESTED'
  | 'FAILED'

export type UpdateAvailability = 'UP_TO_DATE' | 'UPDATE_AVAILABLE' | 'AHEAD' | 'UNKNOWN'

export type UpdateErrorCode =
  | 'NETWORK'
  | 'RATE_LIMITED'
  | 'NO_ASSET'
  | 'NO_CHECKSUM'
  | 'CHECKSUM_MISMATCH'
  | 'INSUFFICIENT_DISK'
  | 'DOWNLOAD_FAILED'
  | 'WRITE_FAILED'
  | 'APPLY_REJECTED'
  | 'NO_VERSION_CHANGE'
  | 'UNSUPPORTED_PLATFORM'
  | 'DEV_BUILD'

export interface UpdateError {
  code: UpdateErrorCode
  message: string
}

export interface UpdateRelease {
  tag: string
  version: string
  name?: string | null
  /** Untrusted text from the internet — render as plain text, never as markdown or HTML. */
  notes?: string | null
  publishedAtMs?: number | null
  htmlUrl: string
  assetName?: string | null
  assetSizeBytes?: number | null
}

/** What the rig is doing, so the confirm dialog can say what stopping it would cost. */
export interface LiveHint {
  showReady: boolean
  activeStackName?: string | null
  activeEffectCount: number
}

export interface ApplyOutcome {
  targetVersion: string
  succeeded: boolean
  msiExitCode?: number | null
  finishedAtMs?: number | null
  message: string
}

export interface UpdateStatus {
  channel: UpdateChannelKind
  currentVersion: string
  currentCommit?: string | null
  phase: UpdatePhase
  availability: UpdateAvailability
  latest?: UpdateRelease | null
  lastCheckedAtMs?: number | null
  lastCheckError?: string | null
  autoCheckEnabled: boolean
  downloadedBytes: number
  totalBytes?: number | null
  stagedVersion?: string | null
  error?: UpdateError | null
  lastApplyOutcome?: ApplyOutcome | null
  live: LiveHint
  throttled: boolean
}

export interface ApplyUpdateRequest {
  /** The version the user actually saw. The backend 409s if the staged one has moved on. */
  confirmVersion: string
}

export const updatesApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    updateStatus: build.query<UpdateStatus, void>({
      query: () => 'update/status',
      providesTags: ['Update'],
    }),
    checkForUpdate: build.mutation<UpdateStatus, void>({
      query: () => ({ url: 'update/check', method: 'POST' }),
      invalidatesTags: ['Update'],
    }),
    startUpdateDownload: build.mutation<UpdateStatus, void>({
      query: () => ({ url: 'update/download', method: 'POST' }),
      invalidatesTags: ['Update'],
    }),
    cancelUpdateDownload: build.mutation<UpdateStatus, void>({
      query: () => ({ url: 'update/download/cancel', method: 'POST' }),
      invalidatesTags: ['Update'],
    }),
    applyUpdate: build.mutation<UpdateStatus, ApplyUpdateRequest>({
      query: (body) => ({ url: 'update/apply', method: 'POST', body }),
      invalidatesTags: ['Update'],
    }),
    setUpdateSettings: build.mutation<UpdateStatus, { autoCheckEnabled: boolean }>({
      query: (body) => ({ url: 'update/settings', method: 'PUT', body }),
      invalidatesTags: ['Update'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useUpdateStatusQuery,
  useCheckForUpdateMutation,
  useStartUpdateDownloadMutation,
  useCancelUpdateDownloadMutation,
  useApplyUpdateMutation,
  useSetUpdateSettingsMutation,
} = updatesApi

/**
 * Phases after which the *rest* of the status is worth refetching — a new `latest`, an `error`,
 * release notes, an apply outcome. Progress ticks are not in here on purpose.
 */
const TERMINAL_PHASES: ReadonlySet<UpdatePhase> = new Set<UpdatePhase>([
  'IDLE',
  'UPDATE_AVAILABLE',
  'READY_TO_APPLY',
  'APPLY_REQUESTED',
  'FAILED',
])

// The load-bearing split. Progress frames patch the cached status directly, so a 300 MB download
// costs *zero* extra HTTP round-trips; only a terminal transition invalidates the tag and
// refetches the fields the frame doesn't carry. Invalidating on every tick would defeat the whole
// reason this frame carries a payload.
lightingApi.updates.subscribe((ev) => {
  store.dispatch(
    updatesApi.util.updateQueryData('updateStatus', undefined, (draft) => {
      draft.phase = ev.phase
      draft.availability = ev.availability
      draft.downloadedBytes = ev.downloadedBytes
      if (ev.totalBytes != null) draft.totalBytes = ev.totalBytes
    }),
  )
  if (TERMINAL_PHASES.has(ev.phase)) {
    store.dispatch(restApi.util.invalidateTags(['Update']))
  }
})
