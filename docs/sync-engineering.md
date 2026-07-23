# Cloud Sync — Engineering

This is the engineering reference for the cloud-sync subsystem. The big-picture
design and rationale live in [`docs/plans/completed/cloud-sync.md`](plans/completed/cloud-sync.md);
this doc captures the contracts and operational details that current code
relies on so that future contributors don't reinvent them.

The subsystem ships in eight phases (see the design doc). **Today, Phases 1
through 8 are in-tree** — UUID columns, canonical JSON, manual export/import,
the install-identity / `machine_override` tables, a per-project local git
working tree, remote push/pull with PAT credentials, the multi-master
three-way diff with a flat conflict-resolution flow, Phase 6's polished
conflict UX (MANUAL resolution + crash recovery for sessions caught mid-apply),
Phase 7's correctness-corner work (tombstones for deletion propagation,
`EDIT_DELETE` / `DELETE_EDIT` conflict kinds, and an in-engine push-rejected
retry), and Phase 8's quality-of-life additions: a periodic auto-sync
scheduler, a persisted activity log, parsed commit attribution, and history
pagination.

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
fixturePatches/{uuid}.json     # carries universeConfigUuid + optional riggingUuid
universeConfigs/{uuid}.json    # `address` deliberately omitted (machine-local)
riggings/{uuid}.json           # truss/bar/boom pose; fixtures hang off these (v3+)
stageRegions/{uuid}.json       # rectangular platforms describing the deck (v3+)
fixtureGroups/{uuid}.json      # members embedded inline
fxPresets/{uuid}.json          # propertyAssignments embedded inline
fxDefinitions/{uuid}.json
cueSlots/{uuid}.json
parkedChannels/{uuid}.json     # (universe, channel, value) — the channel's parked output
controlSurfaceBindings/{uuid}.json
scripts/{uuid}.kts             # raw Kotlin script body for git-friendly diffs
scripts/{uuid}.meta.json       # metadata sidecar (name, scriptType)
promptBooks/{uuid}.json        # script reference by content hash (scriptHash)
promptBookAnchors/{uuid}.json  # carries promptBookUuid + cueUuid; normalized region rects
promptBookAnnotations/{uuid}.json  # carries promptBookUuid; note/strikethrough/freetext
promptScripts/{sha256}.pdf     # the script PDF itself — binary blob, content-addressed (v4+)
```

### Prompt books

Prompt-book records sync as JSON like everything else. **As of format v4 the
script PDF travels too**, as a binary blob at `promptScripts/{sha256}.pdf`
(see [Version 4](#version-4--prompt-book-pdf-binaries)). `promptBooks/{uuid}.json`
carries the PDF's SHA-256 (`scriptHash`), which is the script's identity (never
the filename); the blob's filename *is* that hash. Anchor and annotation regions
are validated on import against the book's `pageCount` (same invariant as the
REST routes — see `checkPromptBookRegion`).

The bytes live in a per-install content-addressed store
(`<appDataDir>/prompt-scripts/{projectUuid}/{hash}.pdf`, `State.promptScriptPath`)
and are copied to/from the repo as **raw bytes only** — never through the
text/`walkTree` machinery. See [Version 4](#version-4--prompt-book-pdf-binaries)
for the full contract. The legacy "PDF missing on this install" re-import card
(re-attach by hash) survives as a fallback for books whose bytes reached no peer
(e.g. created before v4).

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
Current writer emits `formatVersion = 3`, `minReader = 1`. Rules for future
phases:

* New optional field → no version bump (`ignoreUnknownKeys = true`).
* New required field, removed field, or semantic change → bump
  `formatVersion`.
* Truly breaking change → bump both `formatVersion` and `minReader`.

### Version 2 — FOH stage geometry

Bumped when the FOH 3D stage view landed. Additive changes only; `minReader`
stayed at `1` because v1 readers tolerate the new optional fields via
`ignoreUnknownKeys`. Specifically:

* `FixturePatchJson` gained `stageZ` (depth, metres), `baseYawDeg`,
  `basePitchDeg` — see `docs/fixtures-engineering.md` for the FOH-relative
  coordinate system. The pre-existing `stageX` / `stageY` fields are
  reinterpreted as **metres** in the same coordinate system; before v2 they
  were a UI-internal 0–100 percentage.
* `ProjectJson` gained `stageWidthM`, `stageDepthM`, `stageHeightM` —
  per-venue stage bounds for the renderer.

Reinterpreting `stageX` / `stageY` is the closest thing to a semantic break
in v2; bumping `formatVersion` makes that explicit even though the wire
shape is technically backward-compatible. There is no live data migration —
the project repo this lighting rig was bootstrapped against had only test
coordinates, and they are expected to be re-placed in metres.

### Version 3 — Riggings, stage regions, Z-up

Bumped when riggings became a first-class entity, stage regions arrived, and
the coordinate system flipped from Y-up to Z-up. Both `SUPPORTED_FORMAT_VERSION`
and `MIN_SUPPORTED_FORMAT_VERSION` are 3 — older repos are **rejected on
import** (HTTP 422 with a "re-export from a newer install" message). No
graceful v2-to-v3 migration code: this project hasn't shipped beyond the dev
box, so backwards-reader plumbing would be dead weight. (Once the project
runs on a real lighting console, the next breaking bump should add a
v3-to-v4 reader instead of repeating this shortcut.)

Wire-format changes:

* `FixturePatchJson` lost `riggingPosition` (the legacy free-text string)
  and gained `riggingUuid` (FK to a Rigging by UUID).
* `FixturePatchJson.stageY` / `stageZ` swap meaning — `stageY` is now depth
  (was height), `stageZ` is now height (was depth). Same field names,
  different axes.
* New `RiggingJson` table — pose (position + yaw/pitch/roll), name, optional
  `kind` advisory label.
* New `StageRegionJson` table — rectangular platforms describing the
  playable surface beyond the project bounding box.

Local DB migration: `State.migrateRiggingsV3` runs on PG installs that still
have a `rigging_position` column. It reads each distinct legacy string, mints
a Rigging row per `(projectId, riggingPosition)` pair (name = the legacy
string, geometry null), updates `fixture_patches.rigging_id` to point at the
new row, swaps `stage_y` ↔ `stage_z` on every patch (Y-up → Z-up), and drops
the `rigging_position` column. Idempotent — gated on the column's existence.

On import (manual fresh-import or post-pull replace), an export whose
`formatVersion` is greater than `SUPPORTED_FORMAT_VERSION` is rejected (HTTP 422);
same if it is less than `MIN_SUPPORTED_FORMAT_VERSION`. On pull, the remote
`formatVersion.json` is read straight from the fetched git ref **before** the
working tree is touched (see "Pre-pull formatVersion check" under
[Remote sync (Phase 4)](#remote-sync-phase-4)) so a too-new repo can't corrupt
the local working tree. When Phase 5+ migrations land, they live at
`sync/migrations/V{n}_to_V{n+1}.kt` and run before the importer's three-way diff.

### Version 4 — Prompt-book PDF binaries

Bumped when the script PDF a prompt book is bound to began travelling in the
repo. `SUPPORTED_FORMAT_VERSION = 4`; `MIN_SUPPORTED_FORMAT_VERSION` **stays 3**
so a v4 install still reads pre-v4 (JSON-only) repos. The writer always emits
`formatVersion = 4`.

The bump is deliberately a hard gate rather than a graceful additive change: a
pre-v4 install lacks the wipe-preserve/reconcile logic below, so on its next
snapshot it would delete `promptScripts/` and **revert the PDF onto every peer**.
Emitting v4 makes such an install refuse the repo as too-new (the same
`formatVersion > SUPPORTED` check used everywhere else), which is the protection.
For that to work the value must actually be on disk: `FormatVersionJson`
force-encodes both fields with `@EncodeDefault(ALWAYS)`, because the canonical
encoder's `encodeDefaults = false` would otherwise omit them (serialising the
whole object to `{}`) and every reader would fall back to its own compiled-in
default — silently defeating the gate. This also fixes that latent gap for good.

Wire-format / repo-layout change: a new top-level `promptScripts/{sha256}.pdf`
directory holding the raw PDF bytes. There is **no JSON DTO** — the file is the
content, keyed by its own SHA-256 (which is also the book record's `scriptHash`).

**Binaries never touch the text machinery.** PDFs are content-addressed and
immutable, so they are *not records*: they're excluded from `RecordHasher`, the
three-way diff, and `JGitClient.walkTree` (which UTF-8-decodes blobs and would be
lossy — and, at up to 100 MB each, expensive). They move only as raw bytes via
[`PromptScriptRepoSync`](../src/main/kotlin/uk/me/cormack/lighting7/sync/PromptScriptRepoSync.kt):

* **store → tree** (`reconcileTree`) — on every export/snapshot, and on the
  auto-merge path after the DB is merged. Copies the referenced hash's PDF from
  the local store into `promptScripts/`, deletes orphaned hashes (a `scriptHash`
  change or a deleted book), and — critically — **never deletes a referenced
  hash's file even if the store lacks the bytes**, so a store-less install can't
  drop the repo copy. The merge call is what puts the *winning* side's PDF into
  the merge commit, since the record overlay only writes JSON.
* **tree → store** (`hydrateStore`) — on every pull (from the real git checkout,
  post-`resetHard`) and on manual import (from the export folder). Copies
  `promptScripts/*.pdf` into the local store so the book renders without a manual
  re-import.

Supporting pieces:

* `promptScripts/` is exempt from the snapshot **wipe**
  (`SyncWorkingTree.isPreserved`) — see the no-data-loss rule above. Unchanged
  PDFs keep their mtime, so `stageAll` treats them as no-change and they aren't
  re-committed.
* `.gitattributes` gains `promptScripts/** binary` so git skips EOL
  normalisation and textual diffing. The rule is **back-filled** onto
  already-initialised repos (`writeMetadataFiles` appends it if missing).
* `walkTree` prunes the `promptScripts/` subtree, so the diff input and the
  fast-forward scratch reconstruction never see (and never corrupt) the bytes.

There is no live migration: a pre-v4 repo simply has no `promptScripts/` dir;
the first v4 push adds it and stamps `formatVersion = 4`.

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

1. Validates `formatVersion == 3` (422 otherwise — too old or too new).
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
    clientId      = ""    # blank → fall back to bundled credentials (see below)
    clientSecret  = ""    # sensitive; treat local.conf as a secret file
    publicBaseUrl = ""    # default "http://localhost:8413"; appended with
                          # /api/rest/oauth/github/callback
}
```

`clientId` and `clientSecret` resolve as an atomic pair through
[`State.resolveOAuthCredentialPair`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt):

1. If `local.conf` provides both, use that pair.
2. Otherwise, if `BundledOAuthCredentials` (generated at build time) provides
   both, use that pair.
3. Otherwise OAuth is disabled and the UI offers PAT-only auth.

We never mix `clientId` from one source with `clientSecret` from another —
they belong to different GitHub Apps and pairing them across sources would
produce an invalid client.

##### Build-time bundled credentials

Installer distributions (the Windows .msi built by `.github/workflows/windows-build.yml`)
can't ship a `local.conf` with secrets, so the workflow injects credentials
from repository secrets via Gradle properties:

```bash
./gradlew packageWindows \
  -PghOauthClientId=$GH_OAUTH_CLIENT_ID \
  -PghOauthClientSecret=$GH_OAUTH_CLIENT_SECRET
```

The `generateBundledOAuthCredentials` task writes
`build/generated/source/oauth/main/kotlin/.../BundledOAuthCredentials.kt`
with the values as `const val` strings; that file is on the main source set's
classpath, so the values end up in `lighting7.jar` and the resulting .msi.
Local builds leave the props empty (the file generates with empty strings)
and rely on `local.conf` as before.

The repository secrets are `LIGHTING7_GH_OAUTH_CLIENT_ID` and
`LIGHTING7_GH_OAUTH_CLIENT_SECRET`. The credentials end up in the binary;
that's the standard "public client" trade-off for OAuth desktop apps where
PKCE isn't available (GitHub doesn't support PKCE on OAuth/GitHub Apps).
Mitigations: a GitHub App is scoped per-install (a leaked secret can't
pivot beyond what each user explicitly authorised), permissions are kept
minimal, and rotation is fast (regenerate the App's client_secret →
re-run the workflow → ship a new installer).

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

Phase 7 added a per-snapshot `isDeleted` bit (the snapshot is either a live
record or a tombstone). The matrix now branches on that bit so a deletion can
be distinguished from "we never had this." Each side's input is `(hash,
isDeleted)`; same-hash + same-isDeleted is `local == remote` for the matrix's
`NoOp` row.

| Local | Remote | sync_state | Hash relations | Outcome |
|---|---|---|---|---|
| any | any | any | `local == remote` (kind+hash both equal) | `NoOp` (covers concurrent identical edits AND concurrent identical deletes) |
| live | live | yes | `local == base, remote ≠ base` | `TakeRemote` |
| live | live | yes | `remote == base, local ≠ base` | `TakeLocal` |
| live | live | none | `local ≠ remote` | `Conflict (EDIT_EDIT)` (Phase 4 → 5 upgrade) |
| live | live | yes | both moved, different values | `Conflict (EDIT_EDIT)` |
| **tombstone** | **live** | yes, both moved | — | `Conflict (DELETE_EDIT)` |
| **live** | **tombstone** | yes, both moved | — | `Conflict (EDIT_DELETE)` |
| **tombstone** | **live** | yes, remote unchanged from base | — | `TakeLocal` (push the tombstone, deletion wins) |
| **live** | **tombstone** | yes, local unchanged from base | — | `TakeRemote` (accept the deletion) |
| live | absent | yes (live base) | — | `TakeLocal` with `WARN` (history rewrite or pre-Phase-7 peer dropped a record without a tombstone) |
| live | absent | none | — | `TakeLocal` (new local record, no tombstone awareness needed) |
| absent | live | any | — | `TakeRemote` (new remote record) |
| tombstone | absent | any | — | `NoOp` (deletion already not on the remote side) |
| absent | tombstone | any | — | `NoOp` (we never had it, nothing to delete) |
| absent | absent | yes | — | `NoOp` (orphan; bootstrap GCs it) |

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

### Tombstones and deletion propagation (Phase 7)

Phase 7 closed the resurrection gap with three pieces: snapshot-time
tombstone derivation, `(hash, isDeleted)` plumbing through the diff, and a
push-rejected retry that absorbs peer races. See [Tombstones](#tombstones-phase-7)
below for the full contract.

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

## Tombstones (Phase 7)

Phase 7 introduces deletion markers in the repo so the diff can distinguish
"local has X, remote deleted X" from "local has X, remote never had X." The
former propagates the deletion; the latter pushes the new record. Without
this, a record deleted on install A could be resurrected on the next sync
from install B that still had it.

### File format

Path: `tombstones/{tableName}/{uuid}.json`. Body:

```json
{
  "tombstone": true
}
```

Hash-stable: the body never carries a timestamp or attribution, so re-running
a snapshot doesn't churn the file. Forensics (when, by whom) come from `git
log` on the tombstone path. The DTO is
[`TombstoneJson`](../src/main/kotlin/uk/me/cormack/lighting7/sync/dto/SyncDtos.kt).

### Snapshot-time derivation (and carry-forward)

`SnapshotEngine.snapshot` writes tombstones for every `sync_state` row not
matched by a live record in the freshly-exported tree. The set is the union of:

1. **Locally deleted between snapshots** — `sync_state` row had a live record
   last time but the live DB no longer has that UUID.
2. **Carry-forward** — `sync_state` row already has `lastSyncedIsDeleted = true`
   (the install previously pulled a tombstone). The wipe-then-export step
   nuked the on-disk tombstone before re-export; the carry-forward step writes
   it back. **Without this, the install would silently drop tombstones on its
   next push and a peer who never saw the deletion would resurrect the record.**
   The three-install propagation regression test in
   `RemoteSyncEngineTombstonePropagationTest` enforces this rule.

The deletion set is derived purely by walking
[`RecordHasher.scanRecordKeys(workingTreePath)`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RecordHasher.kt)
and computing `sync_state \ on-disk` — no per-DAO delete hooks. The cost is
one DB read per snapshot.

### `sync_state.lastSyncedIsDeleted`

Phase 7 adds one column on `sync_state`:

```kotlin
val lastSyncedIsDeleted = bool("last_synced_is_deleted").default(false)
```

`bootstrapSyncStateAtHead` writes the bit from each snapshot's `isDeleted`
field, so a tombstone-at-HEAD seeds a `sync_state` row with `isDeleted = true`.
The diff reads this back so a record that's tombstoned on both sides reads as
`NoOp` rather than ambiguous-deletion.

### Defensive invariants

* If a snapshot ever sees both a live record AND a tombstone for the same
  `(tableName, uuid)`, the live record wins and `RecordHasher` logs a `WARN`.
  The wipe-and-export pipeline can't normally produce this state.
* If remote is absent and `sync_state` says the base was a live record (i.e.
  remote dropped a record without a tombstone — history rewrite, manual `rm`,
  pre-Phase-7 peer), the diff falls back to `TakeLocal` with a `WARN`. Same
  semantics as Phase 5/6, so the user-visible behaviour doesn't regress.

### Pre-Phase-7 deletion forensics

Deletions that happened before this upgrade can't retroactively get
tombstones — they were silently dropped from `sync_state`. The first
post-upgrade sync that finds a record on local but absent remotely with no
matching `sync_state` row treats it as a brand-new local record (push it).
Operators who care about pre-upgrade deletions need to apply them on every
install manually before re-syncing.

### Conflict kinds

Phase 7 produces two new conflict kinds in addition to `EDIT_EDIT`:

* **`DELETE_EDIT`** — local deleted, remote edited. The user picks LOCAL
  (keep the deletion) or REMOTE (restore the remote edit). MANUAL is
  disabled — there's no live local-side content to hand-edit; the route
  layer rejects MANUAL with HTTP 400.
* **`EDIT_DELETE`** — local edited, remote deleted. The user picks LOCAL
  (keep the edit), REMOTE (accept the deletion), or MANUAL (rescue the
  record with hand-edited content). MANUAL on `EDIT_DELETE` is fully
  supported.

The route layer's MANUAL gate is in
[`isManualEditAllowed(tableName, conflictKind)`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cloudSync.kt) —
multi-file records (scripts) and `DELETE_EDIT` conflicts both return false.

### Auto-merge with path removal

When the merge winner's set of files differs from the loser's (a
record-↔-tombstone flip), the engine deletes paths that exist only on the
remote side before writing the local snapshot's files. Without that step the
remote-side stale file would linger in the merged tree.

```kotlin
val remotePaths = remoteSnapshots[key]?.files?.keys ?: emptySet()
val localPaths = localSnap.files.keys
for (path in remotePaths - localPaths) Files.deleteIfExists(workingTree.resolve(path))
for ((p, c) in localSnap.files) writeWorkingTreeFile(workingTree, p, c)
```

`replaceFromWorkingTree` only walks the named record subdirs (`cues/`,
`cueStacks/`, …) and ignores `tombstones/`, so a tombstone on disk naturally
suppresses DB import for that UUID — no importer changes needed.

### Tombstone GC (deferred)

Tombstone files are tiny (~25 bytes) but accumulate forever in `sync_state`
and on disk. GC is deliberately not in Phase 7 — it's a tree-size
optimisation, not a correctness issue.

A naïve age-based sweep (e.g. "prune `tombstones/{path}` whose `git log -1
--format=%ct` is older than 90 days") **is not safe in this multi-master
model**. An install that's been offline longer than the cutoff would, on
catch-up, see `live record locally / no remote file / no sync_state row`
and treat the record as a brand-new local insert, resurrecting the
deletion. That's the exact scenario `RemoteSyncEngineTombstonePropagationTest`
prevents. Any GC needs a peer-aware low-water mark — for example, an
extension to `installs.json` recording each install's last-synced
timestamp, with GC bounded by the *oldest* peer's view of history.

The design sketch (safe shape, where the maintenance loop lives, ordering
of `git rm` vs. `sync_state` row drop) lives in
[`docs/plans/followups.md` → `FU-SYNC-TOMBSTONE-GC`](plans/followups.md).

## Push-rejected retry (Phase 7)

When push is rejected by the remote (a peer pushed in the window between our
fetch and our push), the engine retries up to
[`RemoteSyncEngine.MAX_PUSH_RETRIES`](../src/main/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngine.kt)
= 3 times before surfacing `PUSH_REJECTED` to the caller.

The retry wrapper is around the IO sub-step (the contents of `withContext(Dispatchers.IO)`),
not the whole orchestrator — so DB-side post-success bookkeeping
(`lastSyncedSha` update, `cloudSyncDone` event, session→DONE) runs exactly
once after the IO step succeeds. Two distinct retry paths:

* **`LocalAhead` / `RemoteAbsent`** — `pushAheadOrRedispatch`. On rejection,
  re-fetch and recurse into `configureAndExchange` with
  `attemptsRemaining - 1`. The new history relation may turn out to be
  `Diverged` (peer's commit means we now need to merge), in which case the
  recursive call dispatches to `handleDiverged` for full merge handling.
* **`Diverged` auto-merge** — `retryAfterPushReject`. Re-fetch, re-classify,
  rebuild the diff against the new remote tip. If zero new conflicts, redo
  `autoMerge` (resetHard new-remote, overlay local-wins, replaceFromWorkingTree,
  commitWithParents with the original `localSha` as parent #2, push). If new
  conflicts emerge: open a fresh conflict session (or, on the
  apply-from-session retry path, throw `SESSION_STALE` — the user must abort
  and re-run because their stored resolutions may now be wrong against the
  newer remote).

The DB churn is real: `replaceFromWorkingTree` rewrites the project's row set
on every retry. For the small project sizes this engine targets that's
acceptable, but a future optimiser could budget here if it ever bites.

### Coverage limitation

Fully-deterministic push-retry coverage requires inserting a peer-push
between our fetch and our push, which has no test seam in the current
synchronous IO block. `RemoteSyncEnginePushRetryTest` exercises the retry
path opportunistically via concurrent `runSync` calls from two engines
against a shared bare repo, asserting eventual consistency rather than
verifying the retry counter directly. A `JGitClient` interface refactor
would unlock fully-deterministic coverage and is tracked as a follow-up.

## Auto-sync (Phase 8)

Periodic syncs are driven by [`AutoSyncScheduler`](../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt).
Lifecycle:

* Started from `Application.module()` once `State` is fully constructed; stopped from
  [`State.shutdown`](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt) so the
  pollers don't outlive the DB.
* Owns one coroutine per project where `sync_configs.autoSyncEnabled = true`. The loop
  delays `autoSyncIntervalMs` (clamped to [`MIN_INTERVAL_MS`](../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt)
  = 60s) before its first tick — a freshly-enabled auto-sync doesn't fire mid-form-submit.
* `PUT /sync/config` calls `scheduler.reschedule(projectId)` after committing. That cancels
  any existing loop and (re-)launches one if the new config still has auto-sync enabled,
  so toggling the flag or changing the interval takes effect immediately without a
  restart.
* Each tick re-reads the config, refuses to run when `enabled = false` or
  `autoSyncEnabled = false`, and **skips when a conflict session is pending** —
  auto-syncing under an active conflict would either bounce a 409 off the engine or
  silently re-enter the diff against stale resolutions. The skip is logged as
  `AUTO_SYNC_SKIPPED` so the operator sees why nothing happened.
* `SyncException` from `engine.runSync` is caught and logged (the engine has already
  written `RUN_FAILED`); unexpected `Throwable`s log a fresh `RUN_FAILED` and keep the
  loop alive. A single tick failure must never kill the project's auto-sync.

The scheduler shares the engine's per-project mutex with manual `Sync now` clicks, so a
human-driven sync and an auto-sync tick can never run concurrently against the same
project.

## Activity log (Phase 8)

`sync_log_entry` (machine-local) carries the operator-visible activity feed: one row per
noteworthy event, written by [`SyncLogger`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt).
Capped per project at [`MAX_ENTRIES_PER_PROJECT`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt)
= 500; older rows are pruned on every write so the table never accumulates without
bound.

```
sync_log_entry
  id PK
  project_id FK -> projects (indexed)
  ts_ms LONG (indexed)
  level VARCHAR             -- INFO | WARN | ERROR
  event VARCHAR             -- stable code, see SyncLogEvent
  message TEXT
```

Stable event codes (see [`SyncLogEvent`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt)):

| Event | Emitted by | Notes |
|---|---|---|
| `RUN_STARTED` | `RemoteSyncEngine.runSync` (start) | One per manual or auto-sync run. |
| `RUN_DONE` | `RemoteSyncEngine.runSync` (success) | Outcome + summary in the message. |
| `RUN_FAILED` | `RemoteSyncEngine.runSync` (catch) | `{code}: {message}` shape. |
| `CONFLICTS_PENDING` | `RemoteSyncEngine.runSync` | Conflict count + session id. |
| `APPLY_DONE` | `RemoteSyncEngine.applySession` (success) | Post-resolve commit pushed. |
| `APPLY_FAILED` | `RemoteSyncEngine.applySession` (catch) | Same shape as `RUN_FAILED`. |
| `SESSION_ABORTED` | `RemoteSyncEngine.abortSession` | Working tree reset. |
| `SNAPSHOT_TAKEN` | `SnapshotEngine.snapshot` | Carries short SHA + summary. |
| `SNAPSHOT_NOOP` | `SnapshotEngine.snapshot` | Nothing to commit. |
| `AUTO_SYNC_TICK` | `AutoSyncScheduler` | Pre-runSync trace marker. |
| `AUTO_SYNC_SKIPPED` | `AutoSyncScheduler` | Conflict session pending. |

Each write fans out a `cloudSyncLogAppended` WebSocket message
(see [`Sockets.kt`](../src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt))
so the frontend's activity feed updates live. Consumers don't need to re-fetch
`/sync/activity` while the WS is connected — but a fresh page-load reads from the
endpoint, so the persisted ring is the source of truth.

`GET /api/rest/project/{id}/sync/activity?limit=…&beforeId=…` paginates newest-first.
`beforeId` is the smallest id from the previous page; `limit` is clamped to
[`MAX_LIST_LIMIT`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SyncLogger.kt) = 500.

## Attribution rendering (Phase 8)

Every snapshot / merge / resolve commit has a stable `[install:{shortUuid}]` marker in
its message (see "Snapshot commit format" above). The `installs.json` registry tracks
the friendly names that have ever pushed to the repo:

* On every snapshot, [`SnapshotEngine`](../src/main/kotlin/uk/me/cormack/lighting7/sync/SnapshotEngine.kt)
  reads the current `installs.json` from the working tree **before** the wipe step,
  passes the entries to [`ProjectExporter.export`](../src/main/kotlin/uk/me/cormack/lighting7/sync/ProjectExporter.kt)
  as `knownInstalls`, and the exporter writes back the union of `knownInstalls` and
  the local install row. Local install always wins on key clash so a renamed install
  propagates its new `friendlyName`.
* `git log` walks back through prior commits and the history is the long-term record;
  `installs.json` at HEAD is the union the diff-and-merge layer cares about.

`GET /sync/log` enriches each [`CommitInfo`](../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt)
with `installShortUuid` and `installFriendlyName` parsed via
[`CommitInfo.withAttribution`](../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt).
Commits without the marker (e.g. ones authored outside the engine) keep both fields
null. Unknown `shortUuid`s — peers who never showed up in `installs.json` at HEAD —
keep `installFriendlyName = null` so the UI can fall back to "(unknown @ short)".

## History pagination (Phase 8)

`GET /sync/log?limit=…&before={fullSha}` walks history backwards from `HEAD` (default)
or from `{fullSha}`'s **first parent** when the cursor is supplied. The first-parent
hop is what makes `before = lastSha-of-prev-page` advance the page without overlapping
the boundary commit. Limit is clamped to `MAX_LOG_LIMIT` = 500. The response is the
same `CommitInfo[]` shape as before, with the added attribution fields described above.

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
* **Sync-cycle memory**: peak heap during `runSync` is roughly bounded by
  2× the working tree's blob size — `JGitClient.walkTree` materialises
  every blob into a `Map<String, String>` on both the local and remote
  sides for the three-way diff. At ~5 KB per record this comfortably
  stays under 50 MB up to ~5000 records, which is well above any expected
  project size. Stress-test before acting on this number — the
  benchmark harness shape is sketched in
  [`docs/plans/followups.md` → `FU-SYNC-JGIT-STRESS-BENCH`](plans/followups.md);
  the trigger to run it is a real project crossing ~1000 records or a
  perceptible (>5s) `runSync` wall-clock.
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
