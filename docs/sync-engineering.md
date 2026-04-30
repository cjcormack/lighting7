# Cloud Sync — Engineering

This is the engineering reference for the cloud-sync subsystem. The big-picture
design and rationale live in [`docs/plans/cloud-sync.md`](plans/cloud-sync.md);
this doc captures the contracts and operational details that current code
relies on so that future contributors don't reinvent them.

The subsystem ships in eight phases (see the design doc). **Today, Phases 1
through 6 are in-tree** — UUID columns, canonical JSON, manual export/import,
the install-identity / `machine_override` tables, a per-project local git
working tree, remote push/pull with PAT credentials, the multi-master
three-way diff with a flat conflict-resolution flow, plus Phase 6's polished
conflict UX: **MANUAL** resolution (per-record JSON edit) and crash recovery
for sessions caught mid-apply. Sections marked *Forward-looking* describe the
contract Phase 7+ work is expected to land against, not anything that runs yet.

## Architecture overview

The eventual delivery mechanism is a per-project GitHub repo — each install
keeps a working tree under `appDataDir()/sync/{projectUuid}/repo/`, pushes and
pulls via JGit, and resolves conflicts in the UI. Phase 1 defined the file
format. Phase 3 puts a real on-disk JGit-backed working tree behind it so
every change can be captured as a local commit; phase 4 adds the remote.

The repo split is:

* **Portable** (synced, lives in JSON): show content — cues, presets, fixture
  patches, scripts, etc.
* **Machine-local** (never synced): controller IPs and similar per-rig
  configuration. From Phase 2, the canonical storage is the
  `machine_overrides` table accessed via the `Overrides` helper. The
  legacy `DaoUniverseConfigs.address` column is retained in the schema
  but is unused — a one-shot startup migration moves any prior values
  into `machine_overrides` and nulls the column.
* **Transient runtime state** (never synced): grand master / blackout state,
  AI conversation history.

Parked DMX channels are portable: operators use park to pin "house lights at
50%" or to protect a hot-powered fixture plugged into a dimmer, both of which
need to follow the project. They sit alongside the rest of the show graph in
`parkedChannels/{uuid}.json` and three-way diff with the same record-level
semantics as cues or fixture patches.

## Repo layout (Phase 1)

Phase 1 ships a flatter layout than the eventual git-repo layout. Everything
lives directly under the export folder:

```
formatVersion.json
project.json
installs.json                  # populated with the local install's identity (Phase 2)
showEntries/{uuid}.json
cueStacks/{uuid}.json
cues/{uuid}.json               # carries cueStackUuid (nullable)
cuePropertyAssignments/{uuid}.json   # carries cueUuid
cuePresetApplications/{uuid}.json    # carries cueUuid + presetUuid
cueAdHocEffects/{uuid}.json    # carries cueUuid
cueTriggers/{uuid}.json        # carries cueUuid + scriptUuid
fixturePatches/{uuid}.json     # carries universeConfigUuid
universeConfigs/{uuid}.json    # `address` deliberately omitted (machine-local)
fixtureGroups/{uuid}.json      # members embedded inline
fxPresets/{uuid}.json          # propertyAssignments embedded inline
fxDefinitions/{uuid}.json
cueSlots/{uuid}.json
parkedChannels/{uuid}.json     # (universe, channel, value) — the channel's parked output
controlSurfaceBindings/{uuid}.json
scripts/{uuid}.kts             # raw Kotlin script body for git-friendly diffs
scripts/{uuid}.meta.json       # metadata sidecar (name, scriptType)
```

A future phase may re-nest cue children under their parent stack folder
(`cueStacks/{stackUuid}/cues/{cueUuid}/...`) so `git diff` scopes to the
edited stack — that's a `formatVersion` bump when it lands.

## Canonical JSON contract

Round-trip stability is a correctness concern, not a stylistic one. Two
exports of semantically identical state must produce byte-identical files;
otherwise every commit is dominated by incidental key reorderings, breaking
diff readability and making three-way merge useless.

The contract is implemented in
[`sync/CanonicalJson.kt`](../src/main/kotlin/uk/me/cormack/lighting7/sync/CanonicalJson.kt):

* **`prettyPrint = true`, 2-space indent** — diffs are human-readable.
* **`explicitNulls = false`** — null fields are omitted. Adding a new optional
  field is therefore forward-compatible: existing repos serialise without it,
  older readers skip it (`ignoreUnknownKeys = true`).
* **`encodeDefaults = false`** — fields equal to their default are omitted.
  Combined with the previous setting, a fresh DTO with default state encodes
  to `{}`.
* **Recursive key sort** — kotlinx.serialization's `JsonObject` is a
  `LinkedHashMap` that preserves insertion order, which depends on
  `@Serializable` property declaration order. We re-walk the encoded
  `JsonElement` and sort keys alphabetically before stringifying. Without this
  step, a property reorder in source code produces noisy diffs.
* **Trailing newline** — POSIX-friendly; also avoids the "no newline at end
  of file" diff marker.

Anything that round-trips through the sync layer must use `canonicalEncode()`,
not a raw `Json.encodeToString`.

## UUID contract

Every synced DAO carries a `uuid: UUID` column. JSON foreign keys are by UUID
(e.g. `cueStackUuid`, `presetUuid`); int ids are local-only handles that
never appear in exported files.

The DAO declaration is:

```kotlin
val uuid = uuid("uuid").autoGenerate()
```

`autoGenerate()` ensures every new row gets a UUID. UUID-collision protection
on import is enforced at the application layer in `ProjectImporter` (a
fast `find { uuid eq targetUuid }` lookup before insert), not via a unique
index — random UUIDs make accidental collisions astronomically unlikely.

New tables default to **not synced** until explicitly added to
`ProjectExporter` / `ProjectImporter`. Don't rely on auto-discovery.

## Ordinal contract (forward-looking)

Phase 1 keeps `sortOrder: Int` on the ten ordered tables. Phase 5 replaces
this with `ordinal: Double` so concurrent multi-master inserts can pick
midpoints without cascading renumbers; tiebreak by UUID for determinism.
That's a `formatVersion` bump and a migration. Today, exports already sort
embedded child arrays by `(sortOrder, uuid)` so the canonical output is
deterministic ahead of the type change.

## Format versioning

`formatVersion.json` at repo root carries `{ formatVersion, minReader }`.
Phase 1 writes `1` for both. Rules for future phases:

* New optional field → no version bump (`ignoreUnknownKeys = true`).
* New required field, removed field, or semantic change → bump
  `formatVersion`.
* Truly breaking change → bump both `formatVersion` and `minReader`.

On import (manual fresh-import or post-pull replace), an export with
`formatVersion > 1` is rejected (HTTP 422). On pull (cloud-sync phase 4), the
remote `formatVersion.json` is read straight from the fetched git ref
**before** the working tree is touched (see "Pre-pull formatVersion check"
under [Remote sync (Phase 4)](#remote-sync-phase-4)) so a too-new repo can't
corrupt the local working tree. When Phase 5+ migrations land, they live at
`sync/migrations/V{n}_to_V{n+1}.kt` and run before the importer's
three-way diff.

## Machine-local data

Per-rig fields (controller IPs and similar) live in the `machine_overrides`
table — keyed by `(projectId, tableName, recordUuid, fieldName)` with a
canonically-encoded `valueJson`. Reads and writes go through the
`Overrides` helper in
[`sync/Overrides.kt`](../src/main/kotlin/uk/me/cormack/lighting7/sync/Overrides.kt),
which serialises strings via the same `canonicalEncode` /
`canonicalDecode` pair used for synced documents.

The universe-configs route is the only Phase 2 consumer:
`PUT /api/rest/project/{id}/universe-configs/{configId}` with `address` in
the body upserts the override; `GET` reads it back via
`Overrides.resolveUniverseAddress`. Runtime DMX output picks up the value
in [`DbFixtureLoader`](../src/main/kotlin/uk/me/cormack/lighting7/show/DbFixtureLoader.kt).

The legacy `DaoUniverseConfigs.address` column is retained for schema
compatibility but is no longer the source of truth — a one-shot startup
migration in `State.initDatabase` moves any pre-Phase-2 values into
`machine_overrides` and nulls the column. A future phase can drop it.

The exporter never serialises override values: machine-local fields stay
in the local SQLite DB by construction. On import, no override rows are
created — the operator re-enters per-rig values via the universe-configs
UI on the importing machine.

This is the precedent for any future per-install field. The decision tree
in `CLAUDE.md` §"Database changes and cloud sync" guides which side of the
portable/machine-local line a new field falls on.

## Install identity

Each install carries a stable identity — one row in the `installs` table,
bootstrapped on first DB init by
[`State.ensureInstallRow`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
with `friendlyName` defaulting to the system hostname. The user can rename
it via `PUT /api/rest/install`; the UUID is immutable.

The exporter writes the local install's identity into `installs.json` so
exported folders carry a "produced by" stamp for human readers and for
future cloud-sync attribution. Phase 1 wrote an empty stub; Phase 2 fills
it. The importer treats `installs.json` as informational — it doesn't
copy the foreign install's identity into the local install row.

The install row is machine-local; it never leaves the local SQLite DB.

## Project import semantics

The Phase 1 importer is intentionally restrictive — it's the manual-backup
case, not multi-master sync. Import:

1. Validates `formatVersion ≤ 1` (422 otherwise).
2. Refuses (409) if a project with the imported UUID already exists. The
   operator can delete that project or skip the import; merge is Phase 5+.
3. Refuses (409) if the imported name collides with an existing project's
   name (unique index). The operator supplies a `nameOverride` to
   disambiguate.
4. Forces `isCurrent = false` and `activeEntryId = null` on insert. The
   operator switches to the imported project explicitly via the existing
   "Activate" UI.
5. Resolves UUID-keyed FKs in topological order: `projects → scripts →
   fxDefinitions → fxPresets → … → cues → cue-children → showEntries →
   cueSlots → controlSurfaceBindings`.
6. Wraps the whole sequence in a single
   `transaction(state.database) { … }` — any FK or validation failure
   rolls back atomically.

## Working tree (Phase 3)

Each project gets its own per-UUID JGit working tree at
`<state.syncWorkingTreeRoot>/{projectUuid}/repo/`. The default root is
`appDataDir().resolve("sync")` (so `~/Library/Application Support/lighting7/sync/…`
on macOS); override via the `sync.workingTreeRoot` knob in `local.conf` to
point at a different volume or, in tests, at a temp directory.

Two metadata files live at the working-tree root and are written-once by
[`SyncWorkingTree.ensureInitialised`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncWorkingTree.kt):

* **`.gitignore`** — excludes OS junk (`.DS_Store`, `Thumbs.db`, `desktop.ini`).
* **`.gitattributes`** — `* text=auto eol=lf`. This is load-bearing: without
  it, a Windows install committing into the same repo as a macOS install
  would produce a diff for every file on first push.

The repo is initialised with `--initial-branch=main` so HEAD points at
`refs/heads/main` from the unborn state — JGit's default is `master`
regardless of the host's git config, which would surprise anyone running
`git log` on the result.

### Wipe-then-export idempotent strategy

Snapshots use a wipe-then-re-export approach in
[`SnapshotEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SnapshotEngine.kt):

1. Open or init the repo via `SyncWorkingTree.ensureInitialised`.
2. **Delete** every file under the working tree except `.git/`,
   `.gitignore`, `.gitattributes` (`SyncWorkingTree.cleanTrackedFiles`).
3. Re-run `ProjectExporter.export(projectId, path)` to repopulate the
   tree from the current DB state.
4. `git add -A` (modelled by `JGitClient.addAll`, which combines
   `AddCommand` for new/modified files with `RmCommand` for missing
   tracked files).
5. If `git status` is clean (nothing staged), short-circuit and return
   `SnapshotResult.NoChanges`. Otherwise, commit.

The wipe is what makes deletions surface: if a cue stack is deleted from
the DB and we only re-ran the exporter without the wipe, the stale
`cueStacks/{uuid}.json` would linger and `git status` would report no
change. The wipe + re-export forces the tree to mirror the DB exactly,
so deletions show up as removed files in the next commit.

### Snapshot commit format

Author identity comes from the `installs` row:

* **author/committer name**: `friendlyName`
* **author/committer email**: `{shortInstallUuid}@lighting7.local` where
  `shortInstallUuid` is the first 8 hex chars of the install UUID.

Commit message format: `{friendlyName}: {summary} [install:{shortUuid}]`,
where `summary` is the user-supplied message or, if absent,
`Snapshot {ISO-8601 timestamp}`. The `[install:{shortUuid}]` suffix is the
attribution marker that future cloud-sync phases will use to thread
multi-install history together — keep the format stable.

### `sync_configs` table

```
sync_configs
  id PK
  project_id FK -> projects (UNIQUE)
  repo_url VARCHAR? NULL          -- populated in phase 4
  branch VARCHAR DEFAULT 'main'
  enabled BOOLEAN DEFAULT false   -- toggled in phase 4
  auto_sync_enabled BOOLEAN DEFAULT false
  auto_sync_interval_ms LONG? NULL
  last_synced_sha VARCHAR? NULL   -- last successful push sha (phase 4+)
  last_synced_at_ms LONG? NULL
```

Phase 3 reads/writes only `branch`. The other fields exist so the form on
`/projects/{id}/sync` can collect remote details ahead of phase 4, but the
backend ignores them today. The table is **machine-local** — never
serialised to the cloud repo (storing PATs or remote URLs in synced JSON
would be a credential leak waiting to happen).

The `sync_configs` row for a project is created lazily on first read by
`ensureSyncConfig` in
[`routes/cloudSync.kt`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt),
so the UI form always has data to render even on the very first visit.

### REST surface

All endpoints live under `/api/rest/project/{projectId}/sync/...`:

| Method | Path | What it does |
|---|---|---|
| GET | `/config` | `sync_configs` row + `tokenPresent` |
| PUT | `/config` | Update `repo_url` / `branch` / `enabled` |
| PUT | `/credentials` | Store a PAT against the configured `repo_url` (Advanced fallback). |
| DELETE | `/credentials` | Clear the stored PAT |
| GET | `/api/rest/oauth/github/identity` | Connected GitHub user (or 404-shape "not connected") |
| DELETE | `/api/rest/oauth/github/identity` | Clear the install-wide OAuth identity |
| GET | `/api/rest/oauth/github/start?projectId=…` | Kick off the OAuth web flow |
| GET | `/api/rest/oauth/github/callback` | OAuth web-flow return path |
| POST | `/api/rest/oauth/github/device/start` | Begin device flow |
| POST | `/api/rest/oauth/github/device/poll` | Poll until user authorises |
| GET | `/api/rest/oauth/github/repositories?query=` | List repos accessible to the App |
| POST | `/api/rest/oauth/github/repositories` | Create a new repo under the user |
| GET | `/status` | `{ workingTreePath, hasRepo, head, dirty }` |
| GET | `/log?limit=50` | Recent `CommitInfo[]` |
| POST | `/snapshot` | Take a snapshot commit (refused while session active) |
| POST | `/run` | Run the full pipeline (returns `CONFLICTS_PENDING` if same-record conflicts) |
| GET | `/conflicts` | Active session + flat conflict list |
| POST | `/resolve` | Set `LOCAL` / `REMOTE` per record |
| POST | `/apply` | Apply resolutions, commit, push |
| POST | `/abort` | Drop the active session |

JGit calls are blocking and run inside `withContext(Dispatchers.IO) {…}`;
the snapshot engine wraps that boundary itself, route handlers wrap their
own log/status calls.

## Remote sync (Phase 4)

Phase 4 turns the local-only working tree into a sharing surface: one project,
one GitHub repo, push and pull via [JGit][jgit]. The user clicks **Sync now**
on `/projects/{id}/sync` and a single REST endpoint —
`POST /api/rest/project/{id}/sync/run` — owns the entire pipeline.

### `sync/run` pipeline

Implemented by
[`RemoteSyncEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngine.kt).
Inside `Dispatchers.IO`, holding a per-project mutex so concurrent clicks
serialise:

1. **Validate config**: `sync_configs.repoUrl` non-blank, `enabled = true`. Else
   400 with `REPO_URL_MISSING` / `SYNC_DISABLED`.
2. **Resolve credentials** via [`AuthResolver`](#github-oauth) — OAuth first,
   PAT fallback. Else 401 with `MISSING_CREDENTIALS` (or
   `OAUTH_REAUTH_REQUIRED` if an OAuth identity exists but its refresh token
   has expired).
3. **Snapshot** local DB → working tree → commit (reuses the phase 3
   `SnapshotEngine` verbatim — every sync starts by capturing whatever changes
   the user has made since the last snapshot).
4. **Configure remote** (`origin` → `repoUrl`). Idempotent and rewrites the URL
   if a typo was corrected.
5. **Fetch** `origin/{branch}` with auth. JGit treats "remote exists but branch
   doesn't" as a transport error; we sniff the message and treat it as
   `RemoteAbsent` so the first sync into a fresh repo works.
6. **Pre-pull format check**: read `formatVersion.json` *from the fetched ref*
   (without checking out) via `JGitClient.readBlob`. If the remote's
   `formatVersion > SUPPORTED_FORMAT_VERSION`, abort with `FORMAT_TOO_NEW`. The
   working tree and DB are untouched.
7. **Classify** the relationship between local HEAD and `origin/{branch}` via
   `JGitClient.classify`, which returns one of `Equal`, `RemoteAbsent`,
   `LocalAhead(n)`, `RemoteAhead(n)`, `Diverged(ahead, behind)`.
8. **Reconcile** based on classification (table below).
9. **Update `sync_configs`**: `lastSyncedSha = HEAD-after-sync`,
   `lastSyncedAtMs = now`.
10. **Hot-reload show** if the just-pulled project happens to be the active
    one — `ProjectManager.switchProject(currentId)` tears down + rebuilds the
    show so fixtures, cues, etc. reflect the new DB state immediately.

### History classification → action

| Relation | Action | Outcome |
|---|---|---|
| `Equal` | nothing | `NO_OP` |
| `RemoteAbsent` | regular push (creates the remote branch) | `PUSHED` |
| `LocalAhead(n)` | regular push | `PUSHED`, `pushed = n` |
| `RemoteAhead(n)` | `resetHard origin/{branch}` + `replaceFromWorkingTree` | `FAST_FORWARDED`, `pulled = n` |
| `Diverged(a, b)` | **force push** | `FORCE_PUSHED`, `pushed = a`, `replaced = b` |

### Diverged history → three-way diff (Phase 5)

Phase 4 force-pushed on divergence and silently dropped peer commits. **Phase 5
replaces that branch** with a record-level three-way diff and (where
necessary) a user-driven conflict-resolution flow. Force-push is gone from
the engine; the only ways to advance HEAD are now no-op, regular push,
fast-forward, auto-merge, or post-resolution merge — all linear or two-parent
merges, never history-rewriting.

See [Three-way diff](#three-way-diff-phase-5) and [Sync session lifecycle
(Phase 5)](#sync-session-lifecycle-phase-5) below for the contract.

### Pull → DB

A fast-forward step changes the working tree on disk. The DB has to follow.
We use [`ProjectImporter.replaceFromWorkingTree`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ProjectImporter.kt)
inside one transaction:

* Validate `project.json`'s UUID matches the existing `DaoProject.uuid` —
  refuse to clobber a different project by accident.
* Cascade-delete every child row (cues + their preset/property/effect/trigger
  children, cue stacks, fxPresets, fxDefinitions, scripts, fixture patches,
  groups, universe configs, etc.) using the same FK-safe order as the
  project-delete flow in `routes/projects.kt`.
* Re-run the per-table import loops binding everything to the existing
  project row, preserving its int `id` so non-synced FKs (`machine_overrides`,
  `sync_configs`) survive.
* Update the project's `name` + `description` from JSON; `isCurrent`, `uuid`,
  `activeEntryId` are left alone.

`machine_overrides` survive because they're keyed by `(projectId,
recordUuid, fieldName)` and `recordUuid` is preserved across re-import. The
user's per-rig ArtNet IPs stay put when remote changes land.

[jgit]: https://www.eclipse.org/jgit/

### Pre-pull `formatVersion.json` check

The check has to happen **before** the working tree is moved. If we
fast-forwarded first, a too-new repo would corrupt the working tree before we
could refuse — and the working tree is what the next snapshot+import cycle
reads. So `JGitClient.readBlob(repo, "refs/remotes/origin/{branch}",
"formatVersion.json")` reads the version straight from the fetched git
object, no checkout required. If too new, abort and the working tree is left
as-is.

## Auth / credential storage

Two paths:

1. **GitHub OAuth (primary)** — install-wide identity established via the web
   flow or device flow. Auto-refreshing user-to-server tokens; per-repo
   permission grants chosen by the user when they install the GitHub App.
2. **Personal Access Tokens (Advanced fallback)** — per-repo PATs for headless
   rigs, GitHub Enterprise, or as an explicit override. Existing PAT-only
   configurations from before the OAuth migration keep working unchanged.

### GitHub OAuth

Architecture lives in
[`sync/auth/oauth/`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/oauth/):

| Class | Role |
|---|---|
| `OAuthGitHubClient` | Ktor HTTP client for `github.com/login/...` and `api.github.com/user/...`. Methods: `exchangeCode`, `refresh`, `startDeviceFlow`, `pollDeviceFlow`, `getAuthenticatedUser`, `listInstallationRepositories`, `createUserRepo`. |
| `OAuthTokenStore` | Persists the install-wide identity as a JSON blob in [`CredentialStore`](#credential-store) under `oauth:github:default`. Refresh always overwrites the whole blob so a partial write can't leave a fresh access token paired with a stale refresh token. |
| `OAuthTokenProvider` | Refresh-on-demand wrapper. A `Mutex` guards the read-refresh-write so concurrent git ops don't race-burn the (single-use) refresh token. Throws `OAuthReauthRequiredException` when the refresh token is itself expired. |
| `AuthResolver` | The single throat callers use to get JGit credentials. Prefers OAuth, falls back to the per-repo PAT if OAuth is missing or its refresh token has expired. |

Identity metadata mirror table:

```
oauth_identities      (machine-local; never synced)
  id PK
  provider VARCHAR    -- "github"
  scope VARCHAR       -- "default"
  github_login VARCHAR
  github_user_id BIGINT
  access_expires_at_ms BIGINT?
  refresh_expires_at_ms BIGINT?
  connected_at_ms BIGINT
  UNIQUE(provider, scope)
```

The row carries only non-secret display metadata so the UI can render
"Connected as @login" without round-tripping the credential store. Tokens
themselves never go in this table — only `CredentialStore`.

#### Web flow vs. device flow

* **Web flow** (primary): the user clicks "Connect GitHub" in the sync UI,
  which hits `GET /api/rest/oauth/github/start`. The backend mints a CSRF
  cookie carrying the originating `projectId`/`returnTo`, redirects the
  browser to `github.com/login/oauth/authorize`, and waits for the callback at
  `/api/rest/oauth/github/callback`. The exchange happens server-side and
  the user is bounced back to `/projects/{id}/sync` with the identity stored.
* **Device flow** (fallback): for rigs accessed from a host that doesn't match
  the GitHub App's registered Callback URL (e.g. iPad on the LAN browsing
  the studio rig). The UI shows a short user code and a verification URI;
  the backend polls until GitHub observes the user's authorisation.

Both paths yield the same `StoredOAuthIdentity` blob and the same DB mirror
row.

#### Refresh

Access tokens last 8 hours; refresh tokens last 6 months and are *single-use*
(each refresh issues a new pair). The provider refreshes proactively when an
access token has less than 60 s of remaining lifetime, and is single-flight
under a Mutex so two concurrent git ops don't race the same refresh.

The `onRefreshed` hook (wired up in `State`) writes the new expiries into the
`oauth_identities` row so the UI's "expires in" badge stays accurate without
polling.

#### Configuration

```hocon
sync.oauth.github {
    clientId      = ""    # blank disables OAuth — UI offers PAT only
    clientSecret  = ""    # sensitive; treat local.conf as a secret file
    publicBaseUrl = ""    # default "http://localhost:8413"; appended with
                          # /api/rest/oauth/github/callback
}
```

GitHub App registration steps (one-time, by the install operator):

* Repository permissions: `Contents: Read & write`, `Metadata: Read-only`,
  `Administration: Read & write` (the last is needed for the inline
  "Create new private repo" affordance — drop it if you only ever pick
  existing repos).
* Enable "Request user authorization (OAuth) during installation".
* Enable "Device Flow" so the device-flow fallback works.
* Set Callback URL to `${publicBaseUrl}/api/rest/oauth/github/callback`.
* Webhooks: off.

No private key (`.pem`) is needed — that's only required for server-to-server
operations (acting as the App without a logged-in user), which lighting7
doesn't do.

### Credential store

The
[`CredentialStore`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStore.kt)
interface holds both PATs (under `pat:<repoUrl>`) and the OAuth blob (under
`oauth:github:default`). The PAT-keyed methods are convenience wrappers over
the more general `getBlob`/`setBlob`/`deleteBlob`/`containsBlob` API.

| Backend | Used when | Notes |
|---|---|---|
| `KeyringCredentialStore` | `sync.credentialStore = "keychain"` (default) | macOS Security framework, libsecret on Linux, Windows Credential Manager — wrapped by [java-keyring][jk] (which uses JNA under the hood). Service name `lighting7`, account = the blob key. |
| `FileCredentialStore` | `sync.credentialStore = "file"` *or* keychain init failed | AES-GCM-encrypted JSON at `<appDataDir>/credentials.enc`. Key derived from the install UUID + the machine hostname (single SHA-256 round). Defeats casual file-copy leaks; not a real secret store. |

The factory ([`CredentialStoreFactory`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStoreFactory.kt))
falls back from keychain to file silently if the native backend isn't
available (typical on a headless Linux box). The fallback is logged at WARN.

### PAT (Advanced fallback)

PATs are keyed per-`repoUrl` so two projects pointing at different remotes
can hold different credentials. Changing a project's `repoUrl` invalidates
the old PAT lookup; the user has to re-enter the token under the new URL.

REST surface:

* `PUT /api/rest/project/{id}/sync/credentials` — body `{ pat }`. The repo
  URL must be set first; the PAT is keyed against it.
* `DELETE /api/rest/project/{id}/sync/credentials` — clears the entry.
  No-op if none exists.
* `GET /api/rest/project/{id}/sync/config` returns `tokenPresent: Boolean`
  so the UI can render "✓ stored" without round-tripping the secret.

The PAT itself is **never** sent to the client. Rotation = clear + re-enter.

GitHub PAT scope: `repo` is required for private repos. JGit surfaces a 401
on push/fetch if the scope is missing.

### JGit transport

Both PAT and OAuth user-to-server tokens authenticate identically over
HTTPS — the username is the literal placeholder `x-access-token`, the
password is the token. `GitCredentials.forGitHubToken(...)` (formerly
`forGitHubPat`) builds the JGit credentials provider for either source.

### Error codes

| Code | Mapped HTTP | Cause |
|---|---|---|
| `MISSING_CREDENTIALS` | 401 | No OAuth identity AND no PAT for this repo. |
| `OAUTH_REAUTH_REQUIRED` | 401 | OAuth identity present but refresh token rejected — user must re-connect. |
| `AUTH_FAILED` | 401 | Credentials present but GitHub rejected the push/fetch. |
| `MISSING_PAT` | 401 | Legacy alias for `MISSING_CREDENTIALS`; retained for one release. |

[jk]: https://github.com/javakeyring/java-keyring

## Three-way diff (Phase 5)

Implemented by [`ThreeWayDiff`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ThreeWayDiff.kt)
and the surrounding [`RecordHasher`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RecordHasher.kt).

For every record on either side, the engine computes a SHA-256 hash of its
canonical JSON (or, for scripts, the canonical concatenation of meta + body)
and consults the per-record `sync_state` row to determine the relationship
between local, remote, and the last-known shared base.

### `RecordKey` & hashing

* `RecordKey(tableName, uuid)` — `tableName` is the export folder name
  (`"cues"`, `"cueStacks"`, `"scripts"`, …) and matches the string used by
  the `machine_overrides` row.
* For most tables, the JSON file at `{tableName}/{uuid}.json` is one record.
  For **scripts**, `RecordKey("scripts", uuid)` covers both `scripts/{uuid}.kts`
  and `scripts/{uuid}.meta.json`; the hash is over their deterministic
  concatenation so a body-only edit bumps the hash.
* Top-level metadata (`formatVersion.json`, `project.json`, `installs.json`,
  `.gitignore`, `.gitattributes`) isn't a record and isn't part of the diff.

### Outcome matrix

| Local present? | Remote present? | sync_state | Hash relations | Outcome |
|---|---|---|---|---|
| yes | yes | any | `local == remote` | `NoOp` |
| yes | yes | yes | `local == base, remote ≠ base` | `TakeRemote` |
| yes | yes | yes | `remote == base, local ≠ base` | `TakeLocal` |
| yes | yes | none | `local == remote` | `NoOp` |
| yes | yes | none | `local ≠ remote` | `Conflict (EDIT_EDIT)` |
| yes | yes | yes | `local ≠ base, remote ≠ base, local ≠ remote` | `Conflict (EDIT_EDIT)` |
| yes | no | any | — | `TakeLocal` (Phase 5: tombstone-blind) |
| no | yes | any | — | `TakeRemote` (Phase 5: tombstone-blind) |
| no | no | yes | — | `NoOp` (orphan; bootstrap GCs it) |

The "no shared base" rows cover the **first sync after upgrading from
Phase 4**: until `bootstrapSyncStateAtHead` populates `sync_state` for the
first time, every disagreement is conservatively a conflict, surfacing in
the UI rather than silently picking a side.

### Auto-merge (no conflicts)

When `Diverged` produces zero conflicts, the engine merges automatically:

1. `resetHard origin/{branch}` — working tree now matches remote tip.
2. For each `TakeLocal` outcome, write the local snapshot's bytes (one or
   two files) over the remote tree.
3. `ProjectImporter.replaceFromWorkingTree` updates the local DB to match.
4. `git add -A`, then commit using `Repository.writeMergeHeads(localSha)` so
   JGit's `CommitCommand` produces a real two-parent merge commit
   (`origin tip` × `local snapshot tip`).
5. Regular `push` — we're now strictly ahead of remote.
6. `bootstrapSyncStateAtHead` walks the new HEAD and rewrites every
   `sync_state` row.

If step 2 produces no tree-level diff (everything was `TakeRemote`), step 4
is skipped and HEAD remains at remote tip — equivalent to a fast-forward.

### Sync-state bootstrap

`bootstrapSyncStateAtHead(projectId, sha)` runs at the end of every terminal
outcome that advances HEAD: `NO_OP`, `PUSHED`, `FAST_FORWARDED`, and the
post-merge / post-apply paths. It deletes every `sync_state` row for the
project and re-creates one row per record on disk at `sha`. This is the
**bootstrap** path: the very first Phase-5 sync on a Phase-4 working tree
finds zero rows and seeds them all.

The deliberate corollary: **the first sync after upgrade can't tell prior
local edits from remote ones**, so any disagreement at that moment surfaces
as a conflict. The engineering trade-off is one extra round of operator
attention immediately after upgrade in exchange for never silently dropping
peer work.

## Sync session lifecycle (Phase 5)

Phase 5 ships the persistent half of the design's session state machine —
`CONFLICTS_PENDING → APPLYING → DONE/FAILED/ABORTED` — backed by
`sync_session` and `sync_session_conflict` rows. The `FETCHING` state and
crash-resume on startup are deliberately deferred to Phase 6: they require
recovery UX that's not yet built.

```
runSync                ┌──── no conflicts ───────► auto-merge → push
   │                   │                              │
   │  Diverged ────► three-way diff                  ▼
   │                   │                          DONE (no session)
   │                   │
   │                   └─── conflicts ──► sync_session(CONFLICTS_PENDING)
   │                                            │       │
   │                                       resolve   abort
   │                                            ▼       ▼
   │                                          apply   sync_session(ABORTED)
   │                                            ▼
   │                                  sync_session(APPLYING)
   │                                            ▼
   │                                       autoMerge
   │                                            ▼
   │                                  sync_session(DONE)
```

Tables (machine-local, never serialised):

| Table | What it carries |
|---|---|
| `sync_state` | One row per `(project, table, uuid)`: `last_synced_sha` / `last_synced_hash`. Rebuilt by `bootstrapSyncStateAtHead` after every advance. |
| `sync_session` | One row per session: `state`, `local_sha`, `remote_sha`, `base_sha`, `error_message`. Historical `DONE`/`FAILED`/`ABORTED` rows accumulate as audit trail. |
| `sync_session_conflict` | One row per conflicting record in the session: `conflict_kind` (`EDIT_EDIT` only in Phase 5), `resolution` (`LOCAL`/`REMOTE`/`MANUAL`/null), `manual_value_json` (Phase 6, populated when `resolution = MANUAL`), and the canonical-JSON snapshots (`local_json` / `remote_json` / `base_json`) captured at session-open time so resolution remains stable across stale fetches. |

Only one **active** session per project is allowed — defined as
`state IN (CONFLICTS_PENDING, APPLYING)`. While a session is active:

* `runSync` returns 409 `SESSION_PENDING` — apply or abort first.
* `snapshot` returns 409 `SESSION_PENDING` — a snapshot would race the
  `local_json` data the resolution UI is showing.
* `apply` is allowed once every conflict has a `LOCAL` or `REMOTE`
  resolution; otherwise 422 `UNRESOLVED_CONFLICTS`. If local HEAD has moved
  since the session opened (e.g. another process snuck in a snapshot), 409
  `SESSION_STALE` and the user must abort and re-run.
* `abort` deletes the conflict rows, marks the session `ABORTED`, and
  hard-resets the working tree to `local_sha`. Local DB is untouched (we
  never wrote to it for a conflict path).

The **apply** path runs the same auto-merge code as the no-conflicts branch;
the user's resolutions override each conflicting record's outcome. The
resulting commit's message is `Resolve …` instead of `Merge …` so it's
distinguishable in `git log`.

### Known gap until Phase 7

Without tombstones, "local has X but remote doesn't" is treated as
`TakeLocal` even when the truth is "remote deleted X since last sync".
Concretely: a record deleted on machine A may be **resurrected** on the next
sync from machine B that still has it. The conflict UI surfaces a banner
explaining this, and Phase 7 fixes it by introducing tombstone files and
the `EDIT_DELETE` / `DELETE_EDIT` conflict kinds.

## MANUAL resolution (Phase 6)

Phase 5 only let the user pick whole-record `LOCAL` or `REMOTE`. Phase 6 adds a
third option: **MANUAL** — supply the replacement payload directly. Useful when
neither side is right but the user can hand-merge the JSON.

* **Storage.** A new `manual_value_json` column on `sync_session_conflict`
  carries the user's edit. It's populated only when `resolution = MANUAL`;
  switching the resolution back to `LOCAL` / `REMOTE` clears the column so a
  stale draft can never sneak through `apply`.
* **Apply.** During `applyMergeFromSession`, MANUAL outcomes are surfaced as a
  new [`DiffOutcome.TakeManual(content)`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ThreeWayDiff.kt)
  variant. `autoMerge` overlays `content` verbatim onto the working-tree path
  the record would normally occupy, then runs the same
  `replaceFromWorkingTree` + commit + push pipeline as `LOCAL` / `REMOTE`.
* **Single-file restriction.** Phase 6 only allows MANUAL on records that
  serialise to a single file — i.e. everything except `scripts`, which is split
  into `scripts/{uuid}.kts` + `scripts/{uuid}.meta.json`. The route layer
  rejects MANUAL for `scripts` with HTTP 400 and the conflict DTO carries a
  `manualEditAllowed: Boolean` flag so the UI can hide the option for those
  rows. A richer per-table editor for scripts is on the Phase 7+ list.
* **Validation.** `POST /sync/resolve` rejects MANUAL with a missing
  `manualValueJson` (400). `POST /sync/apply` re-checks at apply time and
  raises `UNRESOLVED_CONFLICTS` if a row has somehow ended up MANUAL with
  null content.

The resolution choice + saved manual content round-trip through
`GET /sync/conflicts` so the UI can re-render a half-typed draft if the user
navigates away and back.

## Crash recovery (Phase 6)

The `applyMergeFromSession` pipeline isn't a single atomic transaction — the
DB importer commits before the git commit + push. A crash mid-apply can
therefore leave the local DB partly merged without a corresponding remote
push, so a naive resume would either re-do work that already landed in the
DB or push something the remote already has.

Phase 6's policy: on every [`State`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
construction (i.e. process startup), [`ConflictSession.recoverFromCrash`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ConflictSession.kt)
demotes any `APPLYING` session to `FAILED` with an explanatory error message.
The user's recovery path is the existing `POST /sync/abort` (which resets the
working tree to `local_sha` and clears the conflict rows) followed by another
`POST /sync/run` — the three-way diff naturally reconciles whatever shape the
partial apply produced.

`CONFLICTS_PENDING` sessions are deliberately **not** demoted: at that point
the DB and working tree are still consistent and the user can pick up where
they left off via the same `/conflicts` and `/apply` endpoints they'd have
used before the crash.

The `FETCHING` enum value is reserved on `SessionState` against a future
phase that opens a session row at the start of `runSync` (so a crash mid-fetch
is observable), but Phase 6 doesn't write `FETCHING` rows — `runSync` is
mutex-locked and a crash mid-fetch leaves no row at all. Reserving the value
now means Phase 7+ can adopt it without a schema migration.

## Operational notes

* **Default export path**: `appDataDir().resolve("exports").resolve(projectUuid)`
  (`~/Library/Application Support/lighting7/exports/{projectUuid}/` on macOS).
  Caller-supplied paths are honoured verbatim.
* **Default sync working-tree path**: `appDataDir().resolve("sync")`,
  per-project at `<root>/{projectUuid}/repo/`. Override via
  `sync.workingTreeRoot = "/some/path"` in `local.conf`. Tests pass an
  explicit value to keep snapshots out of the user's real `appDataDir`.
* **JGit version**: pinned to `org.eclipse.jgit:org.eclipse.jgit:6.10.0.202406032230-r`
  in `build.gradle.kts`. The 7.x line bumps to JDK 17+ which we already meet,
  but 6.10 has a smaller transitive footprint on the jlink runtime used by
  the Windows distribution.
* **java-keyring version**: pinned to
  `com.github.javakeyring:java-keyring:1.0.4`. Brings JNA in transitively;
  no separate JNA dep is declared. Used by the cloud-sync phase 4
  credential store.
* **Credential file path**: when `sync.credentialStore = "file"` (or the
  keychain backend isn't available), PATs are stored at
  `<appDataDir>/credentials.enc`. The file is created with permissions
  `0600` on POSIX filesystems.
* **DB**: SQLite on the lighting7 install path.
* **Tests**: `src/test/kotlin/uk/me/cormack/lighting7/sync/` covers
  canonical-JSON determinism, round-trip byte-identity, UUID-collision /
  name-collision refusal, FK-resolution failure rollback, machine-local
  field stripping, `isCurrent` reset, JGit init/commit/log smoke tests,
  working-tree clean preserving metadata files, and snapshot end-to-end
  (commit on first run, no-op on second, deletion surfaces in the next
  commit).

## How to add a new synced field

1. Add the column to the relevant DAO file.
2. Decide portable vs. machine-local using the CLAUDE.md decision tree.
3. **Portable**: add the field to the matching DTO in `sync/dto/SyncDtos.kt`,
   pass it through `ProjectExporter` and `ProjectImporter`, and extend the
   round-trip test to assert it survives.
4. **Machine-local**: don't put it in the sync DTO at all. Read and write it
   through the `Overrides` helper (`sync/Overrides.kt`); add a typed
   convenience accessor there if more than one call site needs it. The
   field never appears in exported JSON.
5. If the change isn't covered by `explicitNulls = false` /
   `encodeDefaults = false` (i.e. it's a required field with no default),
   that's a `formatVersion` bump — see "Format versioning".
