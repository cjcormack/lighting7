# Cloud Sync — Engineering

This is the engineering reference for the cloud-sync subsystem. The big-picture
design and rationale live in [`docs/plans/cloud-sync.md`](plans/cloud-sync.md);
this doc captures the contracts and operational details that current code
relies on so that future contributors don't reinvent them.

The subsystem ships in eight phases (see the design doc). **Today, Phases 1,
2, and 3 are in-tree** — UUID columns, canonical JSON, manual export/import,
the install-identity / `machine_override` tables that move per-rig fields
out of the synced graph, and a per-project local git working tree with a
"Take snapshot" REST flow. Sections in this doc that pertain to Phase 4+
are marked *Forward-looking* and describe the contract those phases are
expected to land against, not anything that runs yet.

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
  AI conversation history, parked DMX channels.

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

* `GET /config` / `PUT /config` — read or update the `sync_configs` row.
* `GET /status` — `{ workingTreePath, hasRepo, head: CommitInfo?, dirty }`.
* `POST /snapshot` — body `{ message?: string }`. Returns
  `{ noChanges, workingTreePath, commit: CommitInfo? }`.
* `GET /log?limit=50` — recent `CommitInfo[]` from `git log`.

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
2. **Resolve PAT** via the [credential store](#auth--credential-storage). Else
   401 with `MISSING_PAT`.
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

### Force-push policy on divergence

Phase 4 deliberately collapses true conflict resolution: when both sides have
new commits since the last `lastSyncedSha`, the local side **wins** via a force
push, dropping `b` remote commits. The UI surfaces this with a warning toast
and the `replaced` count so the user notices that work was overwritten.

This is acceptable for the **solo-multi-machine** case the phase targets — the
user driving sync is the only writer, so divergence only happens via operator
error (forgetting to sync before working on machine B). It is **not** an
acceptable answer for true multi-master use; phase 5 introduces the three-way
diff and conflict-resolution UX that replaces this branch.

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

GitHub Personal Access Tokens are credentials and don't belong in
`local.conf` (which can be checked into the user's dotfiles repo) or in the
synced DB. The
[`CredentialStore`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStore.kt)
interface abstracts storage; phase 4 ships two implementations:

| Backend | Used when | Notes |
|---|---|---|
| `KeyringCredentialStore` | `sync.credentialStore = "keychain"` (default) | macOS Security framework, libsecret on Linux, Windows Credential Manager — wrapped by [java-keyring][jk] (which uses JNA under the hood). Service name `lighting7`, account = the repo URL. |
| `FileCredentialStore` | `sync.credentialStore = "file"` *or* keychain init failed | AES-GCM-encrypted JSON at `<appDataDir>/credentials.enc`. Key derived from the install UUID + the machine hostname (single SHA-256 round). Defeats casual file-copy leaks; not a real secret store. |

The factory ([`CredentialStoreFactory`](../src/main/kotlin/uk/me/cormack/lighting7/sync/auth/CredentialStoreFactory.kt))
falls back from keychain to file silently if the native backend isn't
available (typical on a headless Linux box). The fallback is logged at WARN
so the choice isn't completely invisible.

Tokens are keyed per-`repoUrl` so two projects pointing at different
remotes hold different PATs. Changing a project's `repoUrl` invalidates the
old PAT lookup; the user has to re-enter the token under the new URL.

REST surface (additions in phase 4):

* `PUT /api/rest/project/{id}/sync/credentials` — body `{ pat }`. The repo
  URL must be set first; the PAT is keyed against it.
* `DELETE /api/rest/project/{id}/sync/credentials` — clears the entry. No-op
  if none exists.
* `GET /api/rest/project/{id}/sync/config` returns `tokenPresent: Boolean` so
  the UI can render "✓ stored" without round-tripping the secret.

The PAT itself is **never** sent to the client. Rotation = clear + re-enter.

GitHub PAT scope: `repo` is required for private repos. The phase 4 backend
relies on JGit surfacing a 401 on push/fetch if the scope is missing; a
proactive scope-validation request is in the phase 4 plan but out of scope
for v1 (low value vs. the round trip it adds on every set-PAT).

[jk]: https://github.com/javakeyring/java-keyring

## Sync session lifecycle (forward-looking)

Phase 5+ introduces a `sync_session` row that survives a crash mid-sync.
The state machine is `FETCHING → CONFLICTS_PENDING → APPLYING → DONE/FAILED`.
Phase 4 has no equivalent because the only persisted state across a
crash is `sync_configs.lastSyncedSha`/`lastSyncedAtMs`, written
post-success. A crash mid-pipeline leaves the working tree in whatever state
it was in (snapshot may or may not have committed) and the DB unmodified
beyond what the importer's transaction has done — re-running `Sync now`
recovers cleanly.

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
