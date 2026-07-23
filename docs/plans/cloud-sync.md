# Cloud Sync for Lighting7 — Design

## Context

The user runs the lighting7 app on multiple installs (touring laptop,
studio rig, dev machine, etc.) and wants to share shows between them via a
cloud-hosted sync mechanism. Today nothing leaves the local Postgres/SQLite
DB — the only way to move a show between installs is hand-editing config
or copying a database. The goal of this plan is to add a first-class sync
feature with these constraints:

* **Offline-first.** Running and configuring a show must never depend on
  network availability. Sync is opportunistic.
* **Free at the user's scale.** No paid SaaS in the critical path.
* **Versioning.** Past states should be recoverable; conflicts shouldn't
  silently destroy work.
* **Configurable from the UI.** Auth, status, and a log feed all live in
  the React frontend, not in a config file the user has to hand-edit.

## Decisions locked with the user

1. **Multi-master with conflict prompts** — any install can edit; on pull,
   conflicts surface in the UI for per-record resolution.
2. **GitHub repo as the backend** — serialise project to JSON files,
   push/pull via JGit. Auth via Personal Access Token. Free for private
   repos. Versioning is git history.
3. **Split portable vs. machine-local data** — a new `machine_override`
   table holds per-install ArtNet IPs (and similar). Subnet/universe
   numbers sync; controller addresses do not.
4. **Whole-project sync unit** — one project per "Sync now" click. One git
   repo per project (rejected: one shared repo across all projects, since
   different projects may have different collaborators).

## Data that does and does not sync

**Synced (per project):** `DaoProjects`, `DaoShowEntries`, `DaoCueStacks`,
`DaoCues`, `DaoCuePropertyAssignments`, `DaoCuePresetApplications`,
`DaoCueAdHocEffects`, `DaoCueTriggers`, `DaoFixturePatches`,
`DaoUniverseConfigs` (portable subset only — no IPs), `DaoFixtureGroups`,
`DaoFixtureGroupMembers`, `DaoFxPresets`,
`DaoFxPresetPropertyAssignments`, `DaoFxDefinitions`, `DaoCueSlots`,
`DaoControlSurfaceBindings`, `DaoScripts`.

**Not synced:** `DaoProjectScalerStates` (transient operator toggles),
`DaoAiConversations` (private/local), `DaoParkedChannels` (live state),
the `address` column on `DaoUniverseConfigs` (machine-local).

## Repository layout

One repo per project. File-per-record at the top tables; embedded children
where the child has no independent identity.

```
/formatVersion.json                  { "formatVersion": 1, "minReader": 1 }
/project.json                        name, description, createdAt
/installs.json                       { uuid -> friendlyName } registry
/showEntries/{uuid}.json
/cueStacks/{uuid}.json
/cueStacks/{uuid}/cues/{uuid}.json
/cueStacks/{uuid}/cues/{uuid}/propertyAssignments/{uuid}.json
/cueStacks/{uuid}/cues/{uuid}/presetApplications/{uuid}.json
/cueStacks/{uuid}/cues/{uuid}/adHocEffects/{uuid}.json
/cueStacks/{uuid}/cues/{uuid}/triggers/{uuid}.json
/fixturePatches/{uuid}.json
/universeConfigs/{uuid}.json         portable subset only
/fixtureGroups/{uuid}.json           members embedded inline
/fxPresets/{uuid}.json               propertyAssignments embedded inline
/fxDefinitions/{uuid}.json
/cueSlots/{uuid}.json
/controlSurfaceBindings/{uuid}.json
/scripts/{uuid}.kts                  raw Kotlin script body, git-friendly
/scripts/{uuid}.meta.json            metadata sidecar (name, scriptType)
/promptScripts/{sha256}.pdf          prompt-book script PDF, binary blob (v4+)
/tombstones/{tableName}/{uuid}.json  deletion markers
```

> Note: prompt books (record JSON + anchors/annotations) and their PDF binaries
> were added after this design doc was first written. The living layout and the
> binary-blob contract are in
> [`docs/sync-engineering.md`](../sync-engineering.md) (see "Version 4").

**FK rewrite.** Every synced DAO gains a `uuid: UUID` column (indexed,
unique, non-null). Auto-increment `id` becomes a local-only handle; JSON
only ever references UUIDs. Existing rows get UUIDs assigned in-place by a
one-shot migration on first run after upgrade.

**Ordering.** Replace `sortOrder: Int` with `ordinal: Double` so concurrent
inserts pick midpoints without cascading renumbers; tiebreak by UUID for
determinism.

**Canonical JSON.** kotlinx.serialization with sorted keys, omitted nulls,
2-space indent, trailing newline. Anything less and every commit is noise.

## New tables

All new tables are **machine-local** (never synced).

```
install
  uuid UUID PK             -- generated once on first launch
  friendlyName VARCHAR     -- "Studio Mac", user-editable
  createdAt INSTANT

sync_config
  id PK
  projectId FK             -- one row per synced project
  repoUrl VARCHAR          -- https://github.com/user/repo.git
  branch VARCHAR DEFAULT 'main'
  enabled BOOLEAN
  autoSyncEnabled BOOLEAN
  autoSyncIntervalMs BIGINT
  lastSyncedSha VARCHAR    -- repo HEAD at last successful sync
  lastSyncedAt INSTANT

sync_state
  id PK
  projectId FK
  tableName VARCHAR        -- e.g. "cues"
  recordUuid UUID
  lastSyncedSha VARCHAR    -- commit SHA the lastSyncedHash came from
  lastSyncedHash VARCHAR   -- SHA-256 of canonical JSON at last sync
  localVersion BIGINT      -- monotonic counter, bumped on local write
  localHash VARCHAR        -- hash of current local canonical JSON
  deletedLocally BOOLEAN
  deletedAt INSTANT?
  lastEditedByInstall UUID
  UNIQUE(projectId, tableName, recordUuid)

sync_session                -- crash-safe in-progress sync state
  id PK
  projectId FK
  startedAt INSTANT
  state VARCHAR             -- FETCHING | CONFLICTS_PENDING | APPLYING | DONE | FAILED
  fetchedSha VARCHAR
  errorMessage TEXT?

sync_session_conflict
  id PK
  sessionId FK
  tableName VARCHAR
  recordUuid UUID
  conflictKind VARCHAR      -- EDIT_EDIT | EDIT_DELETE | DELETE_EDIT
  resolution VARCHAR?       -- LOCAL | REMOTE | MANUAL | null (unresolved)
  manualValueJson TEXT?

sync_log_entry              -- circular ring, last ~500 entries
  id PK
  projectId FK
  ts INSTANT
  level VARCHAR             -- INFO | WARN | ERROR
  event VARCHAR             -- e.g. PUSH_OK, PULL_CONFLICT, AUTH_FAIL
  message TEXT

machine_override
  id PK
  projectId FK
  tableName VARCHAR         -- "universeConfigs"
  recordUuid UUID
  fieldName VARCHAR         -- "controllerIp"
  valueJson TEXT
  UNIQUE(projectId, tableName, recordUuid, fieldName)
```

`machine_override` is intentionally generic key/value so future
machine-local fields don't need new columns. At runtime, every read of a
record with overridable fields goes through a small `Overrides.resolve()`
helper that overlays values; the JSON serialiser explicitly excludes
override fields so they never reach the repo.

## Conflict detection — three-way diff

For each `(table, uuid)` in `union(local rows, remote files)`:

| Local vs lastSynced | Remote vs lastSynced | Outcome |
|---|---|---|
| unchanged | unchanged | no-op |
| changed | unchanged | local-only — push |
| unchanged | changed | fast-forward — apply remote |
| changed | changed, hash equal | concurrent-but-equal — fast-forward metadata |
| changed | changed, hash differs | **EDIT_EDIT conflict** |
| absent locally, no syncState | exists remotely | new remote — insert |
| exists locally, no syncState | absent remotely | new local — push |
| deletedLocally | unchanged remote | delete remote on push |
| deletedLocally | changed remote | **DELETE_EDIT conflict** |
| present | tombstone remote | **EDIT_DELETE conflict** |

`lastSyncedHash` is what makes this work — without it we can't distinguish
"I've changed it locally" from "I happen to look like remote even though my
localVersion bumped."

Tombstones never get implicitly resurrected. They're written on push when
`deletedLocally=true`. A maintenance task can GC tombstones older than 90
days.

## Sync flow

1. User clicks **Sync now** on `/projects/{id}/sync`.
2. Server creates a `sync_session` row (survives a crash).
3. `git fetch origin {branch}` into the per-project working tree at
   `~/.lighting7/sync/{projectUuid}/repo/`.
4. Compute three-way diff per record. Persist conflicts into
   `sync_session_conflict`.
5. **Zero conflicts, zero local changes** → done. **Zero conflicts, only
   fast-forwards** → apply, advance `lastSyncedSha`, done. **Zero
   conflicts, only local changes** → jump to step 9.
6. Broadcast `cloudSyncConflictsPending` WS message. UI shows a per-record
   three-pane diff (mine / theirs / common ancestor).
7. User resolves each conflict (LOCAL / REMOTE / MANUAL edit). Each
   resolution persists immediately, so closing the app is safe.
8. **Apply resolutions** → write resolved values to DB and to the working
   tree.
9. Local commit, attribution message:
   `{friendlyName}: {summary} [install:{shortUuid}]`.
10. `git push origin {branch}`. If rejected (someone pushed in between):
    re-fetch, re-diff, surface only newly conflicting records, retry. Cap
    at 3 retries before erroring out.
11. On success: update every touched `sync_state.lastSyncedSha` and
    `lastSyncedHash`. Mark session `DONE`.

**Crash safety:** the session row is the source of truth. On startup, if a
session is in `CONFLICTS_PENDING` or `APPLYING`, prompt **Resume** vs.
**Discard**. Discard does `git reset --hard origin/{branch}` — but only on
the working tree; the DB is left alone (since nothing has been applied to
it yet — applies happen in step 8 atomically inside a DB transaction).

## Auth / credential storage

GitHub PATs are credentials and must not live in `local.conf` (next to
checked-in code) or in the synced DB.

* **Primary:** OS keychain. macOS Security framework (via JNA), Linux
  libsecret, Windows Credential Manager. Behind a `CredentialStore`
  interface in `src/main/kotlin/uk/me/cormack/lighting7/sync/auth/`.
* **Fallback:** `~/.config/lighting7/credentials.enc` encrypted with a key
  derived from the install UUID + machine entropy. Defeats casual leaks,
  not a real secret store. Acceptable for this use case.
* **Config knob:** `sync.credentialStore = "keychain" | "file"` in
  `local.conf` selects backend; the token itself is never in that file.

PAT storage is per-repo so two projects can use different credentials. UI
shows token presence (yes/no) but never the value, with a "rotate" action
that simply prompts for a new one.

## JGit specifics

**Library:** `org.eclipse.jgit:org.eclipse.jgit:6.x` (verify latest 6.x
patch on Maven Central before pinning). Rejected alternatives:

* GitHub REST Contents API — not atomic across multiple files,
  rate-limited, needs network for everything, no local history walk.
* Shell-out to `git` — must ship the binary or assume one, brittle on
  Windows.

**Auth:** GitHub HTTPS with PAT uses
`UsernamePasswordCredentialsProvider("x-access-token", pat)` — the
username is a literal placeholder. Validate `repo` scope on first use and
surface a clear error if missing. No SSH keys (key management UX would be
its own feature).

## Format versioning

`formatVersion.json` at repo root carries `{ formatVersion, minReader }`.

* New optional fields → no version bump (serialiser is configured
  `ignoreUnknownKeys = true`).
* New required field, removed field, semantic change → bump
  `formatVersion`. Older installs that can degrade gracefully still read.
* Breaking change → bump both `formatVersion` and `minReader`.

On pull: if remote `formatVersion > localMaxFormatVersion`, refuse and
prompt the user to upgrade lighting7. On push: if local would write a
higher `formatVersion`, warn that older installs will need to upgrade.

Migrations live at `src/main/kotlin/uk/me/cormack/lighting7/sync/migrations/V{n}_to_V{n+1}.kt`
and run on pull, before three-way diff.

## REST API additions

In a new file `src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt`,
registered from `routes/router.kt` as `routeApiRestCloudSync(state)`.

```
GET    /api/rest/projects/{id}/sync/config        # repo URL, branch, auto-sync flags, token-present
PUT    /api/rest/projects/{id}/sync/config        # update config
PUT    /api/rest/projects/{id}/sync/credentials   # set PAT (write-only)
DELETE /api/rest/projects/{id}/sync/credentials   # clear PAT
GET    /api/rest/projects/{id}/sync/status        # last-synced, dirty?, conflicts pending?
POST   /api/rest/projects/{id}/sync/run           # start a sync session
GET    /api/rest/projects/{id}/sync/conflicts     # current conflicts (if session pending)
POST   /api/rest/projects/{id}/sync/resolve       # body: per-uuid resolution
POST   /api/rest/projects/{id}/sync/apply         # apply resolved conflicts and commit/push
POST   /api/rest/projects/{id}/sync/abort         # abort current session, reset working tree
GET    /api/rest/projects/{id}/sync/log           # recent sync_log_entry rows
GET    /api/rest/projects/{id}/sync/machineOverrides
PUT    /api/rest/projects/{id}/sync/machineOverrides
GET    /api/rest/install                          # this install's identity
PUT    /api/rest/install/friendlyName             # rename this install
```

## WebSocket additions

In `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt`, new sealed
`InMessage` / `OutMessage` subclasses (one outbound message per state
transition — start with single done/conflicts/error, defer streaming
progress until we feel the pain on big projects):

* In: `cloudSyncRun(projectId)`, `cloudSyncAbort(projectId)`,
  `cloudSyncResolve(...)`, `cloudSyncApply(projectId)`.
* Out: `cloudSyncStarted`, `cloudSyncDone`, `cloudSyncConflictsPending`,
  `cloudSyncFailed`, `cloudSyncLogAppended`, `cloudSyncStateChanged`.

## Frontend additions

In `/Users/chris/Development/Personal/lighting-react`:

* New route `/projects/{id}/sync` registered in `src/App.tsx`.
* New page component with three sections:
  * **Configuration**: repo URL, branch, PAT (write-only input), auto-sync
    toggle. Uses existing `Sheet` + RTK Query mutation pattern from
    `src/EditProjectDialog.tsx`.
  * **Status**: last-synced timestamp, dirty/clean indicator, "Sync now"
    button, conflicts-pending banner that opens the conflict view. Mirrors
    the pattern from `src/connection.tsx` for live status.
  * **Activity log**: scrolling list of `sync_log_entry` rows,
    live-updated via the `cloudSyncLogAppended` WS message. New WS
    subscribable in `src/api/cloudSyncWsApi.ts`.
* New conflict-resolution view: per-record three-pane diff (mine, theirs,
  common ancestor) with LOCAL / REMOTE / MANUAL pickers. For Phase 5 the
  "MANUAL" path can be a raw JSON textarea; richer per-table editors can
  come later.
* `sonner` toasts for sync start/done/error.
* Settings panel for install identity (friendly name) and per-project
  machine overrides (ArtNet IPs).

## Phasing

Each phase ships standalone value and is independently testable.

| # | Phase | Demoable outcome |
|---|---|---|
| 1 | UUID columns + canonical JSON serialiser + local export/import | "Export project to folder" / "Import from folder" buttons. Manual backups work. |
| 2 | Install identity (`install` table) + `machine_override` table + UI | Friendly install name editable; ArtNet IP override per universe. |
| 3 | Local git wiring (JGit, working tree, `sync_config`) | "Commit snapshot" button; user can browse commit history locally. |
| 4 | Remote push/pull (no conflicts) + PAT storage + `formatVersion` | Solo-multi-machine use case works end-to-end. |
| 5 ✅ | `sync_state` + three-way diff + flat conflict list (LOCAL/REMOTE only) | True multi-master support, minimal UX. |
| 6 | Conflict-resolution UX (three-pane diff, MANUAL edit, resume-after-crash) | Polished conflict resolution. |
| 7 | Tombstones, EDIT_DELETE / DELETE_EDIT conflicts, push-rejected retry | Correctness corners. |
| 8 ✅ | Auto-sync, log feed polish, attribution rendering, history browser | Quality of life. |

Phase 1 alone is genuinely useful (manual backups). Phases 1–4 cover the
common solo-but-multi-machine case; phases 5–7 are needed only when two
people may edit at once.

Each phase that lands a user-visible change or a new architectural concept
must update documentation in the same PR — see "Documentation updates"
below.

## Documentation updates

Two artefacts maintained alongside the code:

### `docs/sync-engineering.md` (new)

A new engineering doc, sibling to the existing
`docs/dmx-engineering.md`, `docs/fixtures-engineering.md`, etc., linked
from the "Engineering Documentation" list in `CLAUDE.md`. It should cover:

* **Architecture overview** — git-as-backend rationale, repo-per-project
  layout, machine-local vs. portable split.
* **Repo layout reference** — the canonical directory tree, with what
  each file represents and which DAO it maps to.
* **Canonical JSON contract** — the kotlinx.serialization settings
  (sorted keys, omit nulls, indent, trailing newline) and why diff noise
  is a correctness concern, not a stylistic one.
* **UUID + ordinal contract** — every synced DAO carries a UUID; FKs in
  JSON are by UUID; ordering uses double-precision ordinals with UUID
  tiebreak.
* **`sync_state` and three-way diff** — the state-machine table from this
  plan plus a short walkthrough of how `lastSyncedHash` makes detection
  correct.
* **Sync session lifecycle** — the FETCHING → CONFLICTS_PENDING → APPLYING
  → DONE/FAILED transitions, including crash-resume rules.
* **Tombstones** — why a missing file isn't a deletion, the
  `tombstones/` directory, and the GC policy.
* **Format versioning** — `formatVersion`/`minReader` semantics and the
  upgrade contract for schema changes.
* **Machine overrides** — the `machine_override` table, the
  `Overrides.resolve()` runtime overlay, and the rule that override
  fields are excluded from serialisation.
* **Auth / credential storage** — keychain primary, encrypted-file
  fallback, `sync.credentialStore` config knob, PAT scope requirements.
* **Operational notes** — JGit version pin, working-tree location, log
  retention, garbage collection cadence.
* **"How to add a new synced field"** — a short checklist for future
  contributors: add the field to the DAO, add to the JSON DTO, decide
  portable vs. machine-local, decide whether a `formatVersion` bump is
  needed, write a migration if so.

### `CLAUDE.md` updates

Add a new top-level section, **Database changes and cloud sync**, between
the "Database" and "Common Development Tasks" sections. Content:

* Cloud sync persists (most of) the project graph as JSON in a per-project
  GitHub repo. Adding or modifying tables/columns has implications for
  sync correctness — read `docs/sync-engineering.md` before changing
  schema.
* **Decision tree for any DB change:**
  1. Is the new table/column part of a project's portable show content,
     machine-local state, or transient runtime state?
  2. If portable → must have a `uuid` column, must round-trip through
     canonical JSON, must be added to the sync allow-list. Consider
     whether the change needs a `formatVersion` bump and a migration.
  3. If machine-local → add to `machine_override` (per-field) or, if
     it's a wholly machine-local concept, a new local-only table. Never
     synced.
  4. If transient → add to the explicit non-synced list and document
     why.
* New tables default to **not synced** until explicitly added to the
  serialiser's allow-list — don't rely on auto-discovery.
* Reordering existing fields, renaming columns, or changing FK targets
  on synced tables is a `formatVersion` change. Removing fields is a
  `minReader` bump.
* Updates to `docs/sync-engineering.md` are required when adding a
  synced table, changing the JSON layout, or changing the conflict
  semantics.

Also extend the "Engineering Documentation" list at the bottom of
`CLAUDE.md` to include the new doc:

```
- [Cloud Sync](docs/sync-engineering.md) - Git-backed cloud sync, conflict
  detection, repo layout, machine-local overrides
```

### When docs land per phase

* **Phase 1** lands a stub `docs/sync-engineering.md` with the canonical
  JSON contract + UUID/ordinal contract sections, and the new CLAUDE.md
  section (the decision tree applies from day one because UUID columns
  are arriving).
* **Phases 2–4** flesh out machine overrides, repo layout, format
  versioning, auth, sync session lifecycle.
* **Phases 5–7** flesh out `sync_state`, three-way diff, tombstones,
  push-rejected retry.
* **Phase 8** finalises operational notes and the "how to add a synced
  field" checklist.

## Critical files to touch

| File | Why |
|---|---|
| `build.gradle.kts` | Add JGit, JNA (for keychain). |
| `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt` | Register new tables in `SchemaUtils.createMissingTablesAndColumns`. |
| `src/main/kotlin/uk/me/cormack/lighting7/models/*.kt` | Add `uuid` column to every synced DAO; create new DAOs (`DaoInstall`, `DaoSyncConfig`, `DaoSyncState`, `DaoSyncSession`, `DaoSyncSessionConflict`, `DaoSyncLogEntry`, `DaoMachineOverride`). |
| `src/main/kotlin/uk/me/cormack/lighting7/sync/` (new package) | `SyncEngine`, `JsonSerializers`, `ThreeWayDiff`, `JGitClient`, `auth/CredentialStore`, `migrations/`, `Overrides`. |
| `src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt` (new) | REST endpoints. |
| `src/main/kotlin/uk/me/cormack/lighting7/routes/router.kt` | Register new route. |
| `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` | New sealed message subclasses + handlers. |
| `local.conf` / `example.local.conf` | `sync.credentialStore` knob. |
| Frontend: `src/App.tsx` | Register `/projects/{id}/sync` route. |
| Frontend: `src/api/cloudSyncWsApi.ts` (new), `src/store/restApi.ts` | API plumbing. |
| Frontend: `src/routes/CloudSync.tsx` (new), `src/components/cloudSync/*` (new) | Configuration page, status, activity log, conflict view. |
| `docs/sync-engineering.md` (new) | New engineering doc — see "Documentation updates" above. |
| `CLAUDE.md` | New "Database changes and cloud sync" section + add new doc to the engineering-docs list. |

Existing patterns reused (do not reinvent):

* WS subscribable factory in `src/api/wsSubscriptionFactory.ts`.
* Connection-status component in `src/connection.tsx` (mirror for sync
  status).
* Sheet + RTK Query mutation flow in `src/EditProjectDialog.tsx`.
* Sealed-class `InMessage` / `OutMessage` discriminator pattern in
  `plugins/Sockets.kt`.
* `routeApiRest*` extension function pattern in `routes/`.

## Verification

* **Phase 1**: round-trip a non-trivial project through export → wipe DB →
  import; assert byte-identical canonical JSON between two exports of
  semantically identical state.
* **Phase 3**: open the local repo with `git log` / `git show` and verify
  human-readable history.
* **Phase 4**: two installs against a private GitHub repo, take turns
  editing; verify clean linear history.
* **Phase 5**: scripted concurrent edits on disjoint records → no
  conflicts; concurrent edits on the same cue → conflict surfaces in UI.
* **Phase 7**: kill the app mid-sync at each persisted state; verify
  resume on next launch leaves the DB consistent.
* End-to-end: `./gradlew test` (project's pre-commit equivalent — see
  CLAUDE.md). New unit tests for `ThreeWayDiff`, canonical-JSON
  determinism, FK-by-UUID rewriting, and the override resolver.

## Risks and open items

* **Cue ordering under concurrent inserts.** Two installs each insert a
  cue between cues 5 and 6 → both pick the midpoint ordinal, push, second
  rebases. Not a *conflict* (different UUIDs) but the visual ordering is
  non-deterministic. Mitigation: tiebreak by UUID. Acceptable but
  documented.
* **Scripts referencing IDs.** Today scripts reference fixtures by string
  key (e.g. `fixture("hex-1")`) — confirm before Phase 1 that no script
  accesses are by auto-increment ID. If any do, the FK-rewrite has to
  cover script bodies too, which is a larger surface.
* **JGit memory** on large projects with thousands of cues. Stress-test in
  Phase 5; may need shallow clones if it becomes an issue.
* **Single-repo permission model.** Anyone with PAT access can rewrite
  history. Acceptable for a small-team or solo-multi-machine context;
  flag in user-facing docs.
* **Streaming sync progress** (fetch %, conflict-count discovered).
  Deliberately not in v1 — single done/conflicts/error message is enough
  for the data volumes here. Revisit if user reports slow projects feel
  unresponsive.

## Out of scope

* Sharing the contents of `local.conf` (DB connection, API keys).
* Real-time collaboration (live cursor, presence).
* Per-resource sync (one cue at a time).
* Branches per install with manual merge UX (rejected by user).
* Auth on the lighting7 server itself (the sync feature has its own creds;
  the server remains unauthenticated for now).
