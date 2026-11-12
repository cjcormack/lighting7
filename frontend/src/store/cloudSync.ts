import { restApi, SHOW_RECORD_TAGS } from "./restApi"

// ─── Types ─────────────────────────────────────────────────────────────

/** A repository this project has previously been linked to; offered for reconnect. */
export interface LinkedRepo {
  repoUrl: string
  lastLinkedAt: string
  /** Whether the repo is one of this desk's Lighting7 Cloud's. */
  cloud?: boolean
}

export interface SyncConfig {
  branch: string
  repoUrl: string | null
  /**
   * A project is synced iff a repository is attached (`repoUrl != null`). There is no
   * separate enable/disable flag — attaching a repo enables sync; disconnecting clears it.
   */
  synced: boolean
  autoSyncEnabled: boolean
  autoSyncIntervalMs: number | null
  lastSyncedSha: string | null
  lastSyncedAt: string | null
  /**
   * True if a PAT for the configured `repoUrl` is stored in the OS keychain (or the
   * encrypted-file fallback). The actual token is never sent to the client; this flag
   * is just so the UI can render a "✓ token stored" indicator next to the Set-token
   * input without needing a separate request.
   */
  tokenPresent: boolean
  /**
   * Repositories this project has previously been linked to, most-recently-linked first.
   * The UI offers these for one-click reconnect — the only sanctioned way to re-attach an
   * existing, non-empty repo.
   */
  linkedRepos: LinkedRepo[]
  /** A cloud repo's name, as the cloud last gave it: what the desk shows, never the URL. */
  repoName?: string | null
  /** Whether `repoUrl` is a repo of this desk's Lighting7 Cloud. */
  cloud?: boolean
}

/** One of the org's cloud repos (`GET /cloud-sync/repos`), with whether a project here syncs it. */
export interface CloudRepo {
  repoId: string
  url: string
  name: string
  kind: "SHOW" | "VENUE"
  projectUuid: string
  headSha?: string | null
  sizeBytes: number
  formatVersion?: number | null
  lastPushAt?: string | null
  syncedHere: boolean
  projectId?: number | null
}

/** `POST /projects/{id}/sync/cloud`'s answer: the repo attached, and how its first sync went. */
export interface SyncToCloudResult {
  config: SyncConfig
  /** The cloud already had a repo for this project (another desk made it). */
  existed: boolean
  sync?: SyncRunResult | null
  syncError?: { error: string; code: string } | null
}

export interface UpdateSyncConfigRequest {
  /**
   * Attach a repository to a project with no repo yet. Ignored once a repo is attached —
   * the repo is immutable except via disconnect → reconnect.
   */
  repoUrl?: string | null
  branch?: string
  autoSyncEnabled?: boolean
  /** Backend rejects values < 60000ms (`AutoSyncScheduler.MIN_INTERVAL_MS`). */
  autoSyncIntervalMs?: number | null
}

export interface ReconnectRequest {
  repoUrl: string
}

/** Lower bound on `autoSyncIntervalMs` — kept in sync with `AutoSyncScheduler.MIN_INTERVAL_MS`. */
export const AUTO_SYNC_MIN_INTERVAL_MS = 60_000

/**
 * Outcomes of a successful sync run; matches the `SyncOutcome` Kotlin enum. Phase 5
 * replaces Phase 4's `FORCE_PUSHED` with `MERGED` (clean auto-merge of disjoint records)
 * and adds `CONFLICTS_PENDING` for the same-record-on-both-sides case.
 */
export type SyncOutcome =
  | "NO_OP"
  | "PUSHED"
  | "FAST_FORWARDED"
  | "MERGED"
  | "CONFLICTS_PENDING"

export interface SyncRunResult {
  outcome: SyncOutcome
  pushed: number
  pulled: number
  /**
   * Always 0 in Phase 5 — retained for API stability with the Phase 4 force-push field.
   * Phase 4 set this to the number of remote commits dropped on a force-push; Phase 5
   * never drops remote commits.
   */
  replaced: number
  headSha: string
  message: string
  /** Set when `outcome === "CONFLICTS_PENDING"`. */
  sessionId?: number | null
  /** Number of conflicts persisted; 0 unless `outcome === "CONFLICTS_PENDING"`. */
  conflictCount?: number
  /**
   * Explicit cue numbers the pulled or merged tree held twice in one stack (sync v32): the entry
   * with the lower uuid kept the number, the other was made auto. Also said in `message`.
   */
  numberClashes?: CueNumberClash[]
}

/** One explicit cue number held twice in a stack after a merge, and how the pull resolved it. */
export interface CueNumberClash {
  stack: string
  number: string
  keptEntryUuid: string
  /** The cue that kept the number. */
  kept: string
  entryUuid: string
  /** The cue now numbered automatically. */
  cue: string
}

/** Stable error codes returned by the run / apply / abort endpoints. */
export type SyncErrorCode =
  | "REPO_URL_MISSING"
  | "SYNC_DISABLED"
  /** No GitHub credentials of any kind (OAuth or PAT) configured for this repo. */
  | "MISSING_CREDENTIALS"
  /** Legacy alias retained for one release. */
  | "MISSING_PAT"
  /** OAuth identity present but refresh token rejected — user must re-connect. */
  | "OAUTH_REAUTH_REQUIRED"
  | "AUTH_FAILED"
  | "FORMAT_TOO_NEW"
  | "PUSH_REJECTED"
  | "NO_REPO"
  | "SESSION_PENDING"
  | "SESSION_NOT_FOUND"
  | "SESSION_STALE"
  | "UNRESOLVED_CONFLICTS"
  | "REPO_URL_IMMUTABLE"
  | "PROJECT_MISMATCH"
  /** A show's repo pins a venue version this desk lacks — sync or import the venue first. */
  | "VENUE_MISSING"
  /** A venue version's number is the remote's already, published on another desk. */
  | "VENUE_VERSION_TAKEN"
  /** A pull-only desk cannot publish a venue that has a repo. */
  | "VENUE_PUBLISH_PULL_ONLY"
  /** A venue's draft never syncs — no snapshot of it is taken. */
  | "VENUE_DRAFT_NOT_SYNCED"
  /** A venue repo's tag is not a version this desk can read. */
  | "VENUE_REPO_INVALID"
  /** The snapshot isn't HEAD or an ancestor of it, or the project has no history. */
  | "RESTORE_COMMIT_UNKNOWN"
  /** The restore would now do something other than the review confirmed; the body carries the new one. */
  | "RESTORE_REVIEW_STALE"
  /** The snapshot is the show as it is. */
  | "RESTORE_NO_CHANGES"
  /** A note over 200 characters or holding a line break; a blank sha or token. */
  | "RESTORE_INVALID"
  /** The snapshot's archive is older than this desk reads. */
  | "RESTORE_TOO_OLD"
  /** The snapshot's tree can't be imported for another reason. */
  | "RESTORE_UNREADABLE"
  /** A cloud repo, and this desk is not adopted by the cloud (nor started with a developer token). */
  | "CLOUD_NOT_ADOPTED"
  /** The org has cloud sync turned off. */
  | "SYNC_OFF"
  /** The cloud repo was deleted, or belongs to another org. */
  | "REPO_NOT_FOUND"
  /** The org is out of storage for its repos. */
  | "SYNC_QUOTA_EXCEEDED"
  /** The cloud cannot store the push just now. */
  | "SYNC_STORAGE_UNAVAILABLE"
  /** The cloud's repo API did not answer. */
  | "CLOUD_UNREACHABLE"
  /** The cloud's repo API refused, in its own words. */
  | "CLOUD_REFUSED"
  /** A pull-only desk (`sync.push = false`) cannot make a cloud repo. */
  | "CLOUD_SYNC_PULL_ONLY"

export interface CommitInfo {
  sha: string
  shortSha: string
  authorName: string
  authorEmail: string
  whenMs: number
  message: string
  /** Short UUID parsed from the `[install:{shortUuid}]` marker; null on commits authored outside the engine. */
  installShortUuid?: string | null
  /** Friendly name from `installs.json` at HEAD; null when the short UUID isn't in the registry. */
  installFriendlyName?: string | null
  /** The full sha a restore commit's `Restored-From:` trailer names; null on every other commit. */
  restoredFrom?: string | null
}

/** A single row from the per-project activity log; mirrors `SyncLogEntryDto`. */
export interface SyncLogEntry {
  id: number
  ts: string
  level: "INFO" | "WARN" | "ERROR"
  /**
   * Stable code from `SyncLogEvent` — e.g. `RUN_DONE`, `AUTO_SYNC_RECOVERED`. Rendered as
   * an opaque string, so codes retired backend-side (`AUTO_SYNC_TICK`) still display on
   * rows written before they were.
   */
  event: string
  message: string
}

export interface SyncStatus {
  workingTreePath: string
  hasRepo: boolean
  head: CommitInfo | null
  dirty: boolean
}

export interface SnapshotResponse {
  noChanges: boolean
  workingTreePath: string
  commit: CommitInfo | null
}

// ─── Snapshot restore (sync-restore plan §3) ──────────────────────────

export type RestoreChange = "BACK" | "GONE" | "CHANGED"

export interface RestoreCounts {
  back: number
  gone: number
  changed: number
}

export interface RestoreRecord {
  uuid: string
  /** The History sheet's name for it, e.g. `Cue “Rose garden”`. */
  label: string
  change: RestoreChange
}

/** One kind of record the restore changes, in the importer's folder order, its records by label. */
export interface RestoreKind {
  folder: string
  noun: string
  plural: string
  records: RestoreRecord[]
}

export interface RestoreText {
  now?: string | null
  then?: string | null
}

export interface RestoreStageSize {
  widthM?: number | null
  depthM?: number | null
  heightM?: number | null
}

/** A venue pin, or a detached show's origin. `name` where this desk holds the venue. */
export interface RestoreVenueRef {
  uuid: string
  name?: string | null
  version: number
}

/** The show's own fields the restore changes, each `{now, then}`; `nameKept` the snapshot's name it leaves off (P6). */
export interface RestoreProject {
  name?: RestoreText | null
  description?: RestoreText | null
  stageSize?: { now?: RestoreStageSize | null; then?: RestoreStageSize | null } | null
  origin?: { now?: RestoreVenueRef | null; then?: RestoreVenueRef | null } | null
  nameKept?: string | null
}

/** The venue pin before and after. `behind` judges the restored pin; `runAgainst` that the live show runs against it (D8). */
export interface RestoreVenue {
  now?: RestoreVenueRef | null
  then?: RestoreVenueRef | null
  behind: "NONE" | "DRAWING" | "OUTPUT"
  runAgainst: boolean
}

/** P7: overrides copied back from the venue, and the universes left with no address on this desk. */
export interface RestoreAddresses {
  copied: number
  missing: string[]
}

/** `GET …/sync/restore/{sha}`: what restoring the snapshot would do, changing nothing. */
export interface RestoreReview {
  target: CommitInfo
  head?: CommitInfo | null
  /** Sent back with the restore; a restore whose review has moved on refuses `RESTORE_REVIEW_STALE`. */
  token: string
  noChanges: boolean
  counts: RestoreCounts
  kinds: RestoreKind[]
  project?: RestoreProject | null
  venue?: RestoreVenue | null
  addresses?: RestoreAddresses | null
  /** The project is current: it reloads. */
  live: boolean
  sync: { repo?: string | null; lastSyncedAt?: string | null; push: boolean }
}

export interface RestoreRequest {
  sha: string
  token: string
  note?: string | null
}

/** `POST …/sync/restore`'s answer. Restoring `before` undoes it (D3). */
export interface RestoreResult {
  commit: CommitInfo
  before: CommitInfo
  counts: RestoreCounts
  /** The push's run; absent when it was skipped, `notes` saying why. */
  sync?: SyncRunResult | null
  notes: string[]
}

/** One record of a snapshot, as the review lists it. */
export interface BringBackRecordRef {
  folder: string
  uuid: string
}

/** `POST …/sync/restore/records`: the records chosen; `token` the selection's, unless `dryRun`. */
export interface BringBackRequest {
  sha: string
  records: BringBackRecordRef[]
  token?: string | null
  dryRun?: boolean
}

/** A record bringing back puts back: one chosen, or `needed` by one chosen. */
export interface BringBackRecord {
  folder: string
  uuid: string
  noun: string
  label: string
  change: RestoreChange
  needed: boolean
}

/**
 * What bringing the chosen records back puts back, chosen first, and its token; `applied` once
 * written, as the show-history entry `entryId`.
 */
export interface BringBackSelection {
  target: CommitInfo
  token: string
  /** The show-history entry's name, *Brought back from 3f2a91c*. */
  label: string
  records: BringBackRecord[]
  /** The venue's address overrides copied to the show, and the universes that come back with none here. */
  addresses?: RestoreAddresses | null
  /** What it moves on the live stage while a cue is held, in undo's words; a dry run's only. */
  moves?: string[]
  applied: boolean
  entryId?: string | null
}

/** A restore note's limit, the desk's `MAX_NOTE_LENGTH`. */
export const RESTORE_NOTE_MAX_LENGTH = 200

// ─── Phase 5/6 conflict-session types ──────────────────────────────────

/**
 * `MANUAL` (Phase 6) saves a user-edited replacement payload alongside the choice;
 * apply substitutes the manual JSON for both sides at merge time.
 */
export type ConflictResolution = "LOCAL" | "REMOTE" | "MANUAL"

export interface ConflictDto {
  tableName: string
  recordUuid: string
  conflictKind: "EDIT_EDIT" | "EDIT_DELETE" | "DELETE_EDIT"
  /** `null` until the user picks LOCAL / REMOTE / MANUAL. */
  resolution: ConflictResolution | null
  /** Three-pane diff sources — mine / theirs / common ancestor. May be null on EDIT_DELETE / DELETE_EDIT sides. */
  localJson: string | null
  remoteJson: string | null
  baseJson: string | null
  /** User-edited replacement payload when `resolution === "MANUAL"`. Null otherwise. */
  manualValueJson?: string | null
  /** False for multi-file records (e.g. scripts) — UI must hide the MANUAL option for those rows. */
  manualEditAllowed?: boolean
}

export type SyncSessionState =
  | "FETCHING"
  | "CONFLICTS_PENDING"
  | "APPLYING"
  | "DONE"
  | "FAILED"
  | "ABORTED"

export interface ConflictsResponse {
  activeSession: boolean
  sessionId?: number
  state?: SyncSessionState
  localSha?: string | null
  remoteSha?: string | null
  baseSha?: string | null
  errorMessage?: string | null
  conflicts: ConflictDto[]
}

export interface ResolveEntry {
  tableName: string
  recordUuid: string
  /** Pass `null` to clear an earlier choice. */
  resolution: ConflictResolution | null
  /** Required when `resolution === "MANUAL"`; ignored otherwise. */
  manualValueJson?: string | null
}

export interface AbortResult {
  sessionId: number
}

export interface ImportFromRemoteRequest {
  repoUrl: string
  /** Defaults to `main` server-side when null/blank. */
  branch?: string | null
  /** Defaults server-side to whatever's in the imported `project.json`. */
  projectName?: string | null
}

export interface ImportFromRemoteResult {
  projectId: number
  projectUuid: string
  name: string
  /** Explicit cue numbers the repo held twice in one stack, and how each was resolved (sync v32). */
  numberClashes?: CueNumberClash[]
}

// ─── Endpoints ─────────────────────────────────────────────────────────

/**
 * Per-project cloud-sync REST endpoints. Snapshot writes a git commit in
 * `<appDataDir>/sync/{projectUuid}/repo`; status surfaces HEAD + dirty flag;
 * log lists recent commits. Phase 5 layers conflict-session endpoints on top.
 */
export const cloudSyncApi = restApi.injectEndpoints({
  endpoints: (build) => ({
    cloudSyncConfig: build.query<SyncConfig, number>({
      query: (projectId) => `projects/${projectId}/sync/config`,
      providesTags: ['CloudSyncConfig'],
    }),
    /**
     * Batch fetch of every project's sync config keyed by stringified project id.
     * Replaces the N+1 `useCloudSyncConfigQuery(id)` pattern in the hub list. The
     * map is sparse — projects that have never had a sync_config row are absent
     * (treated as "unconfigured" by the hub row).
     *
     * Shares the `CloudSyncConfig` tag with the per-project query so the existing
     * mutation invalidations + WS-driven `restApi.util.invalidateTags(['CloudSyncConfig'])`
     * busts both caches in one go.
     */
    cloudSyncConfigs: build.query<Record<string, SyncConfig>, void>({
      query: () => `cloud-sync/configs`,
      providesTags: ['CloudSyncConfig'],
    }),
    updateCloudSyncConfig: build.mutation<
      SyncConfig,
      { projectId: number; body: UpdateSyncConfigRequest }
    >({
      query: ({ projectId, body }) => ({
        url: `projects/${projectId}/sync/config`,
        method: 'PUT',
        body,
      }),
      invalidatesTags: ['CloudSyncConfig'],
    }),
    /**
     * Detach the current repo. The backend remembers the URL (for reconnect), stops
     * auto-sync, and clears `repoUrl` while preserving the working tree + sync-state, so
     * a later reconnect is cheap. `ProjectList` is busted so the projects-list sync badge
     * updates; `CloudSyncStatus` because the synced/last-synced summary changes.
     */
    cloudSyncDisconnect: build.mutation<SyncConfig, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/disconnect`,
        method: 'POST',
      }),
      invalidatesTags: ['CloudSyncConfig', 'CloudSyncStatus', 'ProjectList'],
    }),
    /**
     * Re-attach a previously-linked repo. `repoUrl` must be one of the project's
     * remembered `linkedRepos` — the backend rejects anything else. Auto-sync defaults
     * back on.
     */
    cloudSyncReconnect: build.mutation<
      SyncConfig,
      { projectId: number; body: ReconnectRequest }
    >({
      query: ({ projectId, body }) => ({
        url: `projects/${projectId}/sync/reconnect`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['CloudSyncConfig', 'CloudSyncStatus', 'ProjectList'],
    }),
    cloudSyncStatus: build.query<SyncStatus, number>({
      query: (projectId) => `projects/${projectId}/sync/status`,
      providesTags: ['CloudSyncStatus'],
    }),
    cloudSyncLog: build.query<
      CommitInfo[],
      { projectId: number; limit?: number; before?: string }
    >({
      query: ({ projectId, limit, before }) => {
        const params: Record<string, string | number> = {}
        if (limit != null) params.limit = limit
        if (before) params.before = before
        return {
          url: `projects/${projectId}/sync/log`,
          params: Object.keys(params).length ? params : undefined,
        }
      },
      providesTags: ['CloudSyncLog'],
    }),
    cloudSyncActivity: build.query<
      SyncLogEntry[],
      { projectId: number; limit?: number; beforeId?: number }
    >({
      query: ({ projectId, limit, beforeId }) => {
        const params: Record<string, string | number> = {}
        if (limit != null) params.limit = limit
        if (beforeId != null) params.beforeId = beforeId
        return {
          url: `projects/${projectId}/sync/activity`,
          params: Object.keys(params).length ? params : undefined,
        }
      },
      providesTags: ['CloudSyncActivity'],
    }),
    cloudSyncSnapshot: build.mutation<
      SnapshotResponse,
      { projectId: number; message?: string | null }
    >({
      query: ({ projectId, message }) => ({
        url: `projects/${projectId}/sync/snapshot`,
        method: 'POST',
        body: { message: message ?? null },
      }),
      // A snapshot changes HEAD + the log; refresh both. Config is unaffected.
      invalidatesTags: ['CloudSyncStatus', 'CloudSyncLog'],
    }),
    setCloudSyncCredentials: build.mutation<void, { projectId: number; pat: string }>({
      query: ({ projectId, pat }) => ({
        url: `projects/${projectId}/sync/credentials`,
        method: 'PUT',
        body: { pat },
      }),
      // tokenPresent flips → refresh config so the UI badge updates.
      invalidatesTags: ['CloudSyncConfig'],
    }),
    clearCloudSyncCredentials: build.mutation<void, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/credentials`,
        method: 'DELETE',
      }),
      invalidatesTags: ['CloudSyncConfig'],
    }),
    cloudSyncRun: build.mutation<SyncRunResult, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/run`,
        method: 'POST',
      }),
      // A run can advance HEAD (push), rewrite the working tree (fast-forward), and
      // update lastSyncedSha on the config; or open a conflict session. Refresh
      // everything including the conflicts panel.
      invalidatesTags: ['CloudSyncConfig', 'CloudSyncStatus', 'CloudSyncLog', 'CloudSyncConflicts'],
    }),

    /**
     * The restore of one snapshot, rolled back on the desk. Never cached: each open reads it afresh,
     * since its token is only good for the show as it is.
     */
    cloudSyncRestoreReview: build.query<RestoreReview, { projectId: number; sha: string }>({
      query: ({ projectId, sha }) => `projects/${projectId}/sync/restore/${encodeURIComponent(sha)}`,
      keepUnusedDataFor: 0,
    }),
    cloudSyncRestore: build.mutation<RestoreResult, { projectId: number; body: RestoreRequest }>({
      query: ({ projectId, body }) => ({
        url: `projects/${projectId}/sync/restore`,
        method: 'POST',
        body,
      }),
      // A restore rebuilds every record of the project, clears its undo history, can move its venue
      // pin, commits and syncs, and the push can open a conflict session. A project that is not
      // current reloads nothing, so no socket frame refreshes its caches.
      invalidatesTags: [
        'CloudSyncConfig',
        'CloudSyncStatus',
        'CloudSyncLog',
        'CloudSyncActivity',
        'CloudSyncConflicts',
        'ProjectList',
        'History',
        'SceneImage',
        'Venue',
        'VenueUpdate',
        ...SHOW_RECORD_TAGS,
      ],
    }),

    /** What bringing the chosen records back would put back: the same route as a dry run, writing nothing. */
    cloudSyncBringBackSelection: build.mutation<BringBackSelection, { projectId: number; body: BringBackRequest }>({
      query: ({ projectId, body }) => ({
        url: `projects/${projectId}/sync/restore/records`,
        method: 'POST',
        body: { ...body, dryRun: true },
      }),
    }),
    cloudSyncBringBack: build.mutation<BringBackSelection, { projectId: number; body: BringBackRequest }>({
      query: ({ projectId, body }) => ({
        url: `projects/${projectId}/sync/restore/records`,
        method: 'POST',
        body: { ...body, dryRun: false },
      }),
      // Records written as one show-history entry, which the next snapshot or sync carries.
      invalidatesTags: ['CloudSyncStatus', 'History', 'SceneImage', ...SHOW_RECORD_TAGS],
    }),

    // ─── Phase 5 conflict-session endpoints ─────────────────────────

    cloudSyncConflicts: build.query<ConflictsResponse, number>({
      query: (projectId) => `projects/${projectId}/sync/conflicts`,
      providesTags: ['CloudSyncConflicts'],
    }),
    cloudSyncResolve: build.mutation<
      void,
      { projectId: number; resolutions: ResolveEntry[] }
    >({
      query: ({ projectId, resolutions }) => ({
        url: `projects/${projectId}/sync/resolve`,
        method: 'POST',
        body: { resolutions },
      }),
      // Refresh the conflicts query so badges flip from "unresolved" → "resolved".
      invalidatesTags: ['CloudSyncConflicts'],
    }),
    cloudSyncApply: build.mutation<SyncRunResult, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/apply`,
        method: 'POST',
      }),
      // Apply closes the session and may advance HEAD + push.
      invalidatesTags: ['CloudSyncConfig', 'CloudSyncStatus', 'CloudSyncLog', 'CloudSyncConflicts'],
    }),
    cloudSyncAbort: build.mutation<AbortResult, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/abort`,
        method: 'POST',
      }),
      invalidatesTags: ['CloudSyncConflicts', 'CloudSyncStatus'],
    }),
    /**
     * Clone a remote repo into a brand-new local project. Returns the new project's
     * id so the caller can navigate straight to its sync drill-in. Invalidates
     * `ProjectList` (a new project appeared) and `CloudSyncConfig` (its sync row
     * is now in the batch query's response).
     */
    cloudSyncImport: build.mutation<
      ImportFromRemoteResult,
      ImportFromRemoteRequest
    >({
      query: (body) => ({
        url: `cloud-sync/import`,
        method: 'POST',
        body,
      }),
      invalidatesTags: ['ProjectList', 'CloudSyncConfig', 'CloudRepos'],
    }),
    /** The org's cloud repos, each marked when a project here already syncs it. */
    cloudRepos: build.query<CloudRepo[], void>({
      query: () => `cloud-sync/repos`,
      transformResponse: (response: { repos: CloudRepo[] }) => response.repos,
      providesTags: ['CloudRepos'],
    }),
    /** Make the project's cloud repo (or attach the one it has), then run its first sync. */
    cloudSyncToCloud: build.mutation<SyncToCloudResult, number>({
      query: (projectId) => ({
        url: `projects/${projectId}/sync/cloud`,
        method: 'POST',
      }),
      invalidatesTags: ['CloudSyncConfig', 'CloudSyncStatus', 'CloudSyncLog', 'CloudSyncConflicts', 'ProjectList', 'CloudRepos'],
    }),
  }),
  overrideExisting: false,
})

export const {
  useCloudSyncConfigQuery,
  useCloudSyncConfigsQuery,
  useUpdateCloudSyncConfigMutation,
  useCloudSyncDisconnectMutation,
  useCloudSyncReconnectMutation,
  useCloudSyncStatusQuery,
  useCloudSyncLogQuery,
  useLazyCloudSyncActivityQuery,
  useLazyCloudSyncLogQuery,
  useCloudSyncSnapshotMutation,
  useSetCloudSyncCredentialsMutation,
  useClearCloudSyncCredentialsMutation,
  useCloudSyncRunMutation,
  useCloudSyncRestoreReviewQuery,
  useCloudSyncRestoreMutation,
  useCloudSyncBringBackSelectionMutation,
  useCloudSyncBringBackMutation,
  useCloudSyncConflictsQuery,
  useCloudSyncResolveMutation,
  useCloudSyncApplyMutation,
  useCloudSyncAbortMutation,
  useCloudSyncImportMutation,
  useCloudReposQuery,
  useCloudSyncToCloudMutation,
} = cloudSyncApi
