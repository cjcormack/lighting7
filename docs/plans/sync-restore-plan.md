# Snapshot restore: go back to an earlier cloud-sync snapshot

> **Document status: READY — written 2026-11-07 from the page Chris and Claude agreed that day.**
> Three sessions, each one PR, each stacked on the one before while that one is unmerged, each run
> by `/next-session` (§5). The page and the record are in
> [`sync-restore-design/INDEX.md`](sync-restore-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's question, 2026-11-07: with cloud sync, is there any way to restore an earlier version? There
isn't from the desk. The sync page's History lists every snapshot, and going back means rebuilding an
old commit's tree as a new commit in a clone, pushing it and pressing *Sync now*.

What the code says, read at `9bc4001`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **Snapshots.** `K/sync/SnapshotEngine.kt`'s `snapshot(projectId, projectUuid, installUuid,
  installFriendlyName, message, logNoop)` wipes the working tree (`SyncWorkingTree.cleanTrackedFiles`,
  which spares `.git/`, the two metadata files and the binary dirs), re-exports through
  `ProjectExporter.export`, writes a deletion marker for every `sync_state` key the export did not
  write (`exporter.writeTombstones(path, syncStateKeys - exportResult.liveKeys)`), stages and commits
  as `"$installFriendlyName: $summary [install:$shortInstall]"`, the summary defaulting to `Snapshot
  {ISO instant}`. A change confined to `installs.json` is not a commit. Its answer is
  `SnapshotResponse(noChanges, workingTreePath, commit)`.
- **The working tree** is `<syncWorkingTreeRoot>/{projectUuid}/repo/` (`K/sync/SyncWorkingTree.kt`,
  `pathFor`). It exists for any project that has had a snapshot, with or without a remote.
- **The history read.** `GET …/sync/log?limit=&before=` (`K/routes/cloudSync.kt`,
  `ProjectSyncLogResource`) answers `JGitClient.log`'s `CommitInfo(sha, shortSha, authorName,
  authorEmail, whenMs, message, installShortUuid?, installFriendlyName?)` (`K/sync/JGitClient.kt`),
  attributed by `CommitInfo.withAttribution`, which parses the `[install:…]` marker anywhere in the
  message.
- **Reading an old commit.** `JGitClient.extractTree(repo, ref, target)` writes every file of a
  commit's tree, binaries included, without touching the working tree; the venue repo uses it to
  read a tag back. `walkTree` skips the binary dirs and is no use here.
- **Rebuilding a project from a tree.** `ProjectImporter.replaceFromWorkingTree(projectId, sourceDir,
  historyReason)` (`K/sync/ProjectImporter.kt`) checks the tree's project uuid and kind against the
  row, refuses a pin whose snapshot the desk lacks (`requireSnapshot`), deletes every child row in the
  project-delete order and re-imports, keeping the project row's id so `machine_overrides` and
  `sync_configs` rows keyed to it survive, and reads every archive back to
  `MIN_SUPPORTED_FORMAT_VERSION` 5 (`SUPPORTED_FORMAT_VERSION` 49). It writes every field of
  `project.json` onto the row: name, description, stage size, *In production*, the venue pin and the
  detached-from origin. Its refusals are `ImportError`s, not `SyncException`s: the pin's
  `ImportError.of(VenuePins.missing(…))`, `unsupportedFormat` for an archive outside 5–49, a conflict for
  a uuid or kind that doesn't match. No StatusPages handler maps `ImportError`
  (`K/plugins/ErrorHandling.kt`), so one that escapes a route is a 500. It runs in its own
  `transaction(state.database)` and then calls `state.history.clear(projectId, historyReason)`, after
  the transaction. Two callers: `RemoteSyncEngine.importTree` and `VenueVersions` (a venue's Discard).
- **Project names are unique** (`DaoProjects.name`, `uniqueIndex`, `K/models/projects.kt`): an import
  writing a name another project holds fails on the constraint, which StatusPages answers as a bare 409.
- **Universe addresses** are machine-local only: the exporter leaves them out and the loader reads
  them from `machine_overrides`, keyed by project id and record uuid (`K/sync/Overrides.kt`,
  `K/show/DbFixtureLoader.kt`). Extract moves the moved records' rows to the venue's id
  (`K/venue/VenueExtract.kt`, `overrides.forEach { it.project = venue }`); Attach re-keys or deletes the
  rows of the records it pairs (`K/venue/VenueAttach.kt`); Detach copies the venue's rows to the show's
  id (`K/venue/VenueDetach.kt`).
- **Exporting to memory.** `ProjectExporter.exportRecords(projectId)` answers a `MapSink` of every
  record (`RecordKey(folder, uuid)` → `RecordImage(json, rowId, hash)`, `K/sync/RecordSink.kt`),
  inside the caller's transaction when there is one; the undo history's record cache is built the same
  way. `RecordLabeler.name(key, image)` (`K/history/RecordLabeler.kt`) names one record as the History
  sheet does (*Cue “Rose garden”*).
- **Binary stores.** `PromptScriptRepoSync.hydrateStore(state, projectUuid, treeDir)` and
  `SceneImageRepoSync.hydrateStore(state, projectUuid, treeDir)` copy a tree's PDFs and scene images
  into the local stores; the exporter's `reconcileTree` copies the referenced ones back into the
  working tree on every snapshot. A scene image no element has referenced for 7 days is pruned at
  project load, so an old snapshot can name an image the store has lost.
- **The sync engine.** `K/sync/RemoteSyncEngine.kt`: `runSync` takes the per-project `Mutex`
  (`withProjectLock`, private) and runs `doRun` (private): config check, `ConflictSession.findActive`
  (→ `SESSION_PENDING`), credentials, a snapshot with no message (so pending edits become a plain
  `Snapshot {ISO instant}` commit), fetch, the pre-pull checks (`FORMAT_TOO_NEW`,
  `PROJECT_MISMATCH`, `checkRemotePin` → `VENUE_MISSING`), classify and reconcile, `lastSyncedSha` /
  `lastSyncedAt`, and `reloadShowIfActive` (private) after a fast-forward or merge. A fast-forward
  imports from a scratch tree **before** moving HEAD (`fastForwardTo`), so a failure leaves the
  database ahead of git, which the next sync settles as a no-op merge. A pull-only desk
  (`State.syncPushEnabled`, `sync.push = false`) skips every push. `JGitClient.fetch` turns a 401/403
  into `GitAuthException` (→ `AUTH_FAILED`); any other transport failure leaves `toAuthOrRethrow` as a
  plain `RuntimeException` whose cause is JGit's `TransportException`, as `AutoSyncScheduler`'s catch-all
  notes. Push does the same. Neither sets a timeout. `doRun` can also throw an `ImportError` or an
  `ExposedSQLException` from importing a pulled or merged tree, after a merge's `resetHard` has already
  moved HEAD. A diverged merge starts from the remote's `project.json`, and `mergeProjectPin` keeps this
  desk's value only for the pin, the origin and *In production*; the name, description and stage size are
  the remote's. The auto-sync scheduler calls `runSync`, so it shares the lock, which REST and WS writes
  do not take.
- **Errors.** `SyncErrorCode` and `toHttpStatus` sit at the foot of `RemoteSyncEngine.kt` (most codes
  409, `FORMAT_TOO_NEW` 422); routes answer `SyncErrorResponse(error, code)` through `respondSyncError`,
  which takes only a `SyncException` (`K/routes/cloudSync.kt`). The client mirrors the codes in
  `F/store/cloudSync.ts`'s `SyncErrorCode` union.
- **The database** is one SQLite connection (`maximumPoolSize = 1`), so one transaction holds off every
  other write while it runs.
- **Who.** `routeApiRestProjectCloudSync` is mounted inside `adminOnly { refuseVersionSync(state) {…} }`
  in `K/routes/projects.kt`. A venue's snapshot route answers `VENUE_DRAFT_NOT_SYNCED`.
- **The live show.** `ProjectManager.switchProject(id, reload = true)` carries the running show across
  (`K/show/ShowCarry.kt`). `K/state/VenueHoldService.kt` judges the hold on every load: a reload whose
  pin is behind its venue on output is held, every channel at zero but parks. `refreshCurrent` keeps a
  running show live, run against its pin, when a publish puts it behind; `runAgainstOnLoad(projectId,
  pinned, installed)` records the same before a reload, which venue Attach uses
  (`K/routes/venueAttach.kt`). `ShowBehind.judge(state, projectId)` answers `behind`
  (`VenueBehind.NONE | DRAWING | OUTPUT`), `pinned` and `installed`.
- **Undo history.** `HistoryService.clear(projectId, reason)` leaves a clear point the History sheet
  shows. `ProjectImporter.restore(project, targets)` is undo's upsert: records found by uuid are updated
  in place, others inserted **with the row id their image keeps**; only `RESTORABLE_FOLDERS`. A REST
  write is journaled by `K/history/JournalInterceptor.kt` under the caller's `WriteOrigin`, named with
  `historyLabel(…)` (`K/history/WriteOrigin.kt`).
- **The activity log.** `SyncLogEvent` (`K/sync/SyncLogger.kt`) is a set of string constants;
  `syncLogger.info(projectId, event, message)` writes a row the Activity panel shows.
- **The client.** `F/routes/CloudSync.tsx` shows `HistoryPanel` (`F/components/cloudSync/HistoryPanel.tsx`)
  only when `config.synced`. The panel pages `useCloudSyncLogQuery` (`F/store/cloudSync.ts`, tag
  `CloudSyncLog`) and has no actions. Toasts are `sonner`'s. `F/components/cloudSync/` has tests only
  for `IdentityRow` and `SyncReauthBanner`.
- **Sync tests** live in `src/test/kotlin/uk/me/cormack/lighting7/sync/`; two or three installs over
  one bare repo is `RemoteSyncEngineTombstonePropagationTest`'s set-up, pull-only is
  `PullOnlySyncTest`'s, and `testsupport/RichProjectFixture.kt` is the project every round trip uses.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **A restore is a new commit on top of the branch, never a reset or force-push.** Its content is the target snapshot's, its parent the show as it stood just before. Peers reach it by fast-forward or three-way merge. |
| D2 | **A restore goes through the database.** The target's tree is extracted, imported by `replaceFromWorkingTree`, and the next snapshot writes it. Deletion markers then come from `sync_state` as for any deletion, the commit is in today's format, and an older snapshot is read as an older archive is. Git's tree is never copied into the working tree directly. |
| D3 | **The show as it stood is always a commit before the restore, and undoing a restore is restoring that commit** (`before`). With a repo attached it is usually the first sync's own snapshot; otherwise a `Before restoring …` snapshot, or HEAD when nothing changed. A whole-project rebuild clears the undo history; the sync history is the way back. |
| D4 | **The review is the restore, rolled back, compared record by record in today's format.** Export the project, import the target in the same transaction, export again, roll back. Never a hash of the two git trees, which would count a format change as an edit. |
| D5 | **Sync first when the repo can be reached, push after; offline, restore locally** (Q1). The first sync makes the review cover every desk's changes. A remote that can't be reached (a transport failure, a timeout, missing or refused credentials) never stops a restore; any other failure of the first sync does. |
| D6 | **The confirmed review's token guards the write.** The restore recomputes the review after its first sync and snapshot; a different token refuses with the new review. The token hashes the review's content, never the head. |
| D7 | **The venue pin comes back with the rows** (Q2): the project's own fields are part of the restore (name, description, stage size, pin, origin) and part of the review, apart from P5's and P6's. |
| D8 | **A live show reloads with carry, and a restore never holds a running show.** Left behind on output, it runs against its restored pin and opens held next time. |
| D9 | **Shows only, admins only, REST only.** A venue answers `VENUE_DRAFT_NOT_SYNCED`. No WS operation, no MCP tool. |
| D10 | **Bringing back individual records is session 3, through undo's upsert** (Q3), journaled so ⌘Z takes it back. |
| D11 | **A restore may carry a note** (Q4), up to 200 characters on one line, appended to its commit message. |

The plan adds seven of its own:

- **P1 — Three sessions, three stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged.
- **P2 — One review.** The review the GET answers, the token the POST checks and session 3's
  selection are all built by one function in `K/sync/SnapshotRestore.kt`. Nothing computes a second
  diff.
- **P3 — A refusal moves nothing.** Every refusal leaves the database, the branch, the working tree and
  the binary stores as they were, apart from the first sync's own effect, which is an ordinary sync, and
  the one case §3's step 4 names: a write that lands during the restore keeps the `Before restoring`
  snapshot it was compared against. Each refusal's test asserts it.
- **P4 — Show safety is tested.** A restore of the live project that leaves it behind on output keeps
  it unheld, asserted on `VenueHoldService`'s status after the reload.
- **P5 — *In production* is not restored.** The show keeps the value it has now, as a merge keeps this
  desk's (`docs/sync-engineering.md`): a desk that turned the lock on for a performance never loses it to
  an older commit.
- **P6 — A name another project now holds is not restored.** The show keeps its current name and the
  review says so, rather than failing on the unique index.
- **P7 — Addresses follow the records.** Where the target makes a universe or patch the show's own
  again and the venue holds a machine override for that uuid (a snapshot from before an Extract), the
  override is copied to the show's id, as Detach copies it. Where a restored universe has no address on
  this desk (a snapshot from before an Attach, whose rows are gone), the review names it.

## 3. The model

### The review (D4, D6, D7, P2, P5–P7)

`SnapshotRestore.review(projectId, sha)`, under the project lock:

1. Resolve `sha` in the working tree's repo to a commit that is HEAD or an ancestor of it
   (`RevWalk.isMergedInto`); else `RESTORE_COMMIT_UNKNOWN`. A project with no repo or no HEAD has no
   history to restore from: the same code.
2. Extract its tree to a scratch directory (`JGitClient.extractTree`), deleted in a `finally`. Before
   importing, the scratch `project.json` takes the show's current *In production* (P5), and its current
   name when another project holds the snapshot's (P6).
3. In one `transaction(state.database)`: `exportRecords(projectId)` (*now*), the import of the scratch
   tree without the history clear (below), `exportRecords(projectId)` (*then*), the venue judgement
   (`ShowBehind.judge`), the addresses P7 would copy or lack, then `rollback()`. No store is hydrated and
   nothing is journaled, since nothing commits. An `ImportError` maps to a `SyncException` (§"Wire").
4. Compare *now* and *then* by `RecordKey`: present only in *then* is `BACK`, only in *now* is `GONE`,
   in both with different hashes is `CHANGED`. The project record is not listed among the records; its
   fields that differ are the review's `project` (name, description, stage size, origin) and `venue`
   (the pin). Each record is labelled by `RecordLabeler.name` with the image of the side it exists on
   (*then* for `BACK` and `CHANGED`).
5. `noChanges` is true only when no record and no project field differs. The token is the SHA-256 of the
   canonical JSON of the target's full sha, every listed record's `(folder, uuid, change)` sorted, and the
   `project`, `venue` and `addresses` fields.

```kotlin
@Serializable data class RestoreReviewDto(
    val target: CommitInfo,
    val head: CommitInfo?,
    val token: String,
    val noChanges: Boolean,
    val counts: RestoreCounts,                 // back, gone, changed
    val kinds: List<RestoreKindDto>,           // folder, noun, plural, records: [{uuid, label, change}]
    val project: RestoreProjectDto?,           // each differing field: name, description, stage size, origin
                                               // as {now, then}; nameKept (P6)
    val venue: RestoreVenueDto?,               // now {uuid, name?, version}?, then {…}?, behind, runAgainst
    val addresses: RestoreAddressesDto?,       // copied: n, missing: [universe names] (P7)
    val live: Boolean,                         // the project is current: it will reload
    val sync: RestoreSyncDto,                  // repo, lastSyncedAt?, push (false on a pull-only desk)
)
```

`venue` is present when the pin differs or the show is on a venue. `runAgainst` is true when the
project is live, `then`'s pin is behind on output and the show is not held now (D8). `kinds` are in
the importer's folder order, records by label.

### Restore (D1–D3, D5–D8, D11, P3, P5–P7)

`SnapshotRestore.restore(projectId, sha, token, note?)`, under the project lock for its whole run:

1. **Sync first (D5).** With a repo attached, the engine's `doRun` (the lock is held, so `runSync`
   cannot be called). Outcomes:
   - any outcome but `CONFLICTS_PENDING`: carry on;
   - `CONFLICTS_PENDING`, or `SESSION_PENDING` before it: refuse `SESSION_PENDING`;
   - `REPO_URL_MISSING`, `SYNC_DISABLED`: no sync; carry on, noted;
   - unreachable, and only these: a `GitTransportException` from fetch or push (a timeout among
     them), `AUTH_FAILED`, `MISSING_CREDENTIALS`, `OAUTH_REAUTH_REQUIRED`, `PUSH_REJECTED`: carry on
     locally, the note naming why;
   - anything else refuses, with its code: `FORMAT_TOO_NEW`, `PROJECT_MISMATCH` and `VENUE_MISSING`
     (the repo and this project disagree) and every exception from importing the pulled tree, which can
     leave HEAD at the remote tip with the database still local. Restoring on top of that would commit
     the local state over the remote's.

   Session 1 makes `toAuthOrRethrow` throw a named `GitTransportException` (a `RuntimeException`, so
   every existing catch still holds) where it now throws a bare `RuntimeException`, so the restore matches
   a type, never a message or a cause chain. It also gives JGit's fetch and push a 30-second idle timeout
   (`TransportCommand.setTimeout`), which bounds every sync, not only a restore's.
2. **Check (D6, P3).** The note (`RESTORE_INVALID`) and the review recomputed: `noChanges` →
   `RESTORE_NO_CHANGES`, a different token → `RESTORE_REVIEW_STALE` with the new review, an
   `ImportError` → its code. Nothing has been written yet, apart from the first sync's own effect.
3. **Snapshot** with the message `Before restoring {shortSha}`. With a repo attached the first sync
   usually committed the edits already, so this finds nothing; `before` is the newest commit after this
   step either way.
4. **Import**, in one transaction, which on the pool's one connection holds off every other write:
   re-export the project and compare it with `before`'s records, both in today's format; a write that
   landed since step 3 refuses `RESTORE_REVIEW_STALE` with a fresh review, the step-3 snapshot kept
   (it is the show as it stood, as any snapshot is). Otherwise the import without the history clear and
   P7's override copies. After it commits, `state.history.clear(projectId, "Restored a snapshot")` and
   both `hydrateStore`s from the scratch tree.
5. **Snapshot** with the message `Restored {shortSha} from {yyyy-MM-dd HH:mm} UTC`, `: {note}` appended
   when there is one, and a `Restored-From: {full sha}` trailer after a blank line. The trailer is parsed
   only as a line of its own.
6. **Reload.** If the project is current: when the review said `runAgainst`,
   `state.venueHold.runAgainstOnLoad(projectId, pinned, installed)` first, as Attach does; then
   `reloadShowIfActive`.
7. **Push.** With a repo reachable at step 1 and push enabled, `doRun` again, which pushes a local-ahead
   branch. A remote that moved since step 1 merges as any sync does, conflicts included, and its result is
   the answer's `sync`. A merge takes the remote's name, description and stage size (`mergeProjectPin`
   keeps only the pin, the origin and *In production*), so after a merge the restore compares those
   fields with what it restored and lists any it lost in `notes`. Otherwise no push, and the reason is in
   `notes`; the restore goes out on the next sync, under the same merge rule.

Steps 4 and 5 keep the fast-forward's failure order: the database moves before git, so a death between
them leaves the next snapshot to write the restored show. The activity log gets one
`SyncLogEvent.RESTORED` row: *Restored 3f2a91c from 2026-10-29 21:14 UTC: 3 back, 2 gone, 9 changed*.

`replaceFromWorkingTree` is split so the review's step 3 and the restore's step 4 can run its import
inside a caller's transaction and leave the history clear to the caller (`replaceInTransaction`, say).
Its two callers keep today's behaviour.

`CommitInfo` gains `restoredFrom: String?`, parsed from the trailer beside `withAttribution`, for the
History panel's marker (session 2).

### Undo (D3)

The answer's `before` is the newest commit after step 3: the show as it stood. Undoing a restore is a
review and a restore of `before`.

### Wire

Both admin only, inside `routeApiRestProjectCloudSync`.

| Route | Body / answer |
|---|---|
| `GET /api/rest/projects/{id}/sync/restore/{sha}` | `RestoreReviewDto` |
| `POST /api/rest/projects/{id}/sync/restore` | `{sha, token, note?}` → `{commit, before, counts, sync?: SyncRunResult, notes: [String]}` |

New `SyncErrorCode`s, each with its status in `toHttpStatus`:

| Code | Status | When |
|---|---|---|
| `RESTORE_COMMIT_UNKNOWN` | 404 | The sha isn't HEAD or an ancestor of it, or there is no history. |
| `RESTORE_REVIEW_STALE` | 409 | The restore would now do something else, or a write landed during it. Body `{error, code, review}`. |
| `RESTORE_NO_CHANGES` | 409 | The snapshot is the show as it is. |
| `RESTORE_INVALID` | 400 | A note over 200 characters or holding a line break; a blank sha or token. |
| `RESTORE_TOO_OLD` | 422 | The target's archive is older than `MIN_SUPPORTED_FORMAT_VERSION` (the importer's `unsupportedFormat`). |
| `RESTORE_UNREADABLE` | 422 | Any other `ImportError` from the target's tree. |

Reused, each at its existing status: `SESSION_PENDING`, `VENUE_DRAFT_NOT_SYNCED`, `VENUE_MISSING` (also
the target pin's `ImportError`), `PROJECT_MISMATCH` (also a uuid or kind conflict in the target) at 409;
`FORMAT_TOO_NEW` at 422. Review and restore map every `ImportError` this way, so none reaches a route as
a 500. The POST emits `cloudSyncStarted` and `cloudSyncDone` / `cloudSyncFailed` as *Sync now* does, so
the sync notifications and the status panel follow it. No new WS frame.

### Storage

None. No table, no column, no `formatVersion` change: a restore commit is an ordinary snapshot, and the
trailer is in the commit message, not the tree.

## 4. UX

- **History row menu** (session 2): *Review and restore…* and *Copy commit id* on every row; restore is
  disabled on the newest row, which is the show apart from unsnapshotted edits. A row with
  `restoredFrom` is marked as a restore and names its target.
- **The review dialog**: *Restore to {local date and time}?*, the commit's id, desk and message; the three
  counts; one collapsible group per kind; a *Show settings* group for the project fields that change;
  the notes (*This show is live…*, the venue line, the addresses P7 copies or lacks, the name kept by
  P6, *Last synced …*, a pull-only desk's *This desk doesn't push…*); an optional note field; *Cancel* and
  *Restore*. `noChanges` disables *Restore* and says why. A `RESTORE_REVIEW_STALE` answer replaces the
  dialog's review in place with a line saying it changed.
- **Progress and done**: *Restore* shows progress until the POST answers; then a toast *Restored to
  {date}* with the counts, the push outcome and any `notes`, and *Undo*, which opens the review for
  `before`.
- **History sheet**: the restore's clear point reads *Restored a snapshot*.

## 5. Implementation — three sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names
written, and its row marked *in review* with its branch. A session's PR is titled
*Snapshot restore: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Review and restore on the desk | in review | `claude/relaxed-planck-rbdr44` | [#393](https://github.com/cjcormack/lighting7/pull/393) | `2a4fa05` |
| 2 | The History panel and the review | in review | `claude/sync-restore-fy9kx9` | | |
| 3 | Bring back selected records | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Review and restore on the desk

- **The importer:** `replaceFromWorkingTree` split so its import runs in a caller's transaction with
  the history clear left to the caller; `RemoteSyncEngine.importTree` and `VenueVersions` unchanged in
  behaviour.
- **The restore:** `K/sync/SnapshotRestore.kt` with `review` and `restore` (§3), the extraction, the
  token and the DTOs. `RemoteSyncEngine` gains `reviewRestore` and `restore`, which take
  `withProjectLock` and hand `SnapshotRestore` a way to run `doRun` and `reloadShowIfActive` under it.
  `SnapshotEngine.snapshot` takes an optional trailer for the message body.
- **Errors and log:** the six `SyncErrorCode`s and their statuses (§3 "Wire"), every `ImportError` from
  review or restore mapped to one; `SyncLogEvent.RESTORED`.
- **Sync rules:** step 1's classification of the first sync's failures; `GitTransportException` and the
  30-second idle timeout on JGit's fetch and push in `K/sync/JGitClient.kt`; step 7's check of the
  project fields after a merge.
- **Project fields (D7, P5–P7):** the review's `project` and `addresses`, *In production* and a taken
  name kept, and P7's override copies (`DaoMachineOverride` rows of the venue for the restored uuids,
  copied as `VenueDetach` copies them).
- **The hold (D8):** step 6's `runAgainstOnLoad`, from the review's `runAgainst`.
- **`CommitInfo.restoredFrom`**, parsed beside `withAttribution`.
- **Routes:** the two in `K/routes/cloudSync.kt`, with the venue refusal and the `cloudSync*` events.
- **Tests:** `src/test/kotlin/uk/me/cormack/lighting7/sync/SnapshotRestoreTest.kt` on
  `RichProjectFixture`: snapshot, then edit, add and delete, snapshot, restore the first; the project
  then exports exactly as the first snapshot imports, the restore commit carries a deletion marker for
  each synced record created since, and a record deleted since comes back live. The review's groups,
  labels and token; `noChanges`; a token that survives a snapshot changing nothing listed and one that
  doesn't. A frozen tree at an older `formatVersion` restores with no record `CHANGED` for its format
  alone. A scene image and the prompt-book PDF missing from the stores come back. Two installs over a
  bare repo (`RemoteSyncEngineTombstonePropagationTest`'s set-up): the peer fast-forwards; a peer with
  an unrelated edit merges cleanly; a peer that edited a restored record gets one conflict. The sync
  rules: an unreachable remote (a refused connection) restores locally and says so; `sync.push = false`
  never pushes; a first sync that opens a conflict session refuses; a first sync whose import fails
  refuses. A snapshot that differs only in stage size is not `noChanges`, and the review lists it. A
  restore keeps *In production* on, and keeps the current name when another project holds the old one.
  A show restored to before an Extract gets its universes' addresses back from the venue's overrides,
  and one restored to before an Attach lists the universes left without one. A diverged step-7 merge that
  takes the remote's name says so in `notes`. Each refusal (unknown sha, stale token, no changes,
  conflict session, venue, the target's `VENUE_MISSING`, an archive older than v5, a bad note, a write
  during step 4) leaves the database, branch, working tree and stores unmoved, but for P3's exception. The live project reloads, and one left behind on output is not held (P4). The
  routes' admin gate and their answers.
- **Docs:** `docs/sync-engineering.md` §"Restoring a snapshot" and its REST table;
  `docs/undo-engineering.md` §"Clear points" (the restore's reason); `docs/fixtures-engineering.md`
  §"Behind, the hold and Update" (a restore runs against, as Attach does); the root `CLAUDE.md` (a
  paragraph in §"Database changes and cloud sync"); `FU-MANUAL-RESTORE-S1` in
  `docs/plans/manual-validation.md` (§6).
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `sync`, `routes`, `venue`
  and `history` packages.
- **As built**, where the code asked for something §3 does not say:
  - The note, the sha and the token are checked before step 1, so a bad request never syncs.
  - A first sync whose pulled tree fails to import without an `ImportError` (a record that is not JSON,
    a constraint) refuses `RESTORE_UNREADABLE`, its message saying it was the pulled tree.
  - A refusal is logged as `SyncLogEvent.RESTORE_FAILED`, beside `RESTORED`.
  - `addresses.missing` names a universe as *Universe 1* (*Universe 2:1* off subnet 0).
  - The before-an-Attach case is tested by deleting a universe and its override, which is what Attach
    does to a paired universe's rows; the address check reads only the rows.

### Session 2 — The History panel and the review

- **Store:** `cloudSyncRestoreReview` (query) and `cloudSyncRestore` (mutation, invalidating
  `CloudSyncConfig`, `CloudSyncStatus`, `CloudSyncLog`, `CloudSyncActivity`, `CloudSyncConflicts` (step 7
  can open a session) and `ProjectList`) in `F/store/cloudSync.ts`, with the DTOs,
  `CommitInfo.restoredFrom` and the six new codes in its `SyncErrorCode` union.
- **History:** the row menu and the restore marker in `F/components/cloudSync/HistoryPanel.tsx`.
- **Review:** `F/components/cloudSync/RestoreReviewDialog.tsx`: §4's dialog, the note field, the stale
  answer replaced in place, progress, and the toast with *Undo*.
- **Tests:** `HistoryPanel.test.tsx` (the menu, the disabled newest row, the marker) and
  `RestoreReviewDialog.test.tsx` (groups, each note, `noChanges`, the stale answer, the error codes'
  messages, *Undo* opening the review for `before`).
- **Docs:** `docs/sync-engineering.md` (the UI in a line); `FU-MANUAL-RESTORE-S2`.
- **Local:** `npm run check:quick` in `frontend/`.
- **As built**, where the code asked for something §4 does not say:
  - The venue line has no installed version (*installed v5* on the page): the review carries the
    restored pin's `behind`, not the installed number.
  - A venue's History rows have no *Review and restore…*, only *Copy commit id*, since the route
    answers `VENUE_DRAFT_NOT_SYNCED` for a venue.
  - The restore also invalidates `History`, `SceneImage`, `Venue`, `VenueUpdate` and every show-record
    tag undo does (`SHOW_RECORD_TAGS`, now in `F/store/restApi.ts`): it rewrites every record, clears the
    undo history and can move the pin, and a project that is not current gets no reload to refresh them.
  - The toast's *Undo* links to the Sync tab with `?review={before}`, which `HistoryPanel` opens, so it
    works after the panel has gone.
  - The global sync toaster's own-page check named `/sync/projects/{id}`, which only redirects now; it
    names the Sync tab too, so a restore's refusal and its done frame aren't toasted over the dialog.
    It also skips a `RESTORE_REVIEW_STALE` failure in other windows: that is a step in a review.
  - A review that is not of the live show still says the undo history starts again, since the
    restore clears it either way.

### Session 3 — Bring back selected records

**Needs:** operator: sessions 1 and 2 have been used to restore a real show.

- **Selection:** a check box beside each `BACK` and `CHANGED` record in the review. A chosen record's
  image names other records by uuid; each such uuid that is a record in *then* and not in *now* joins
  as *also needed*, transitively, listed before anything is applied. `GONE` records can't be chosen:
  taking a record away is an ordinary delete.
- **Apply:** `POST …/sync/restore/records` `{sha, records: [{folder, uuid}], token}` through
  `ProjectImporter.restore`, under a `WriteOrigin` and named *Brought back from {shortSha}*, so the
  journal makes it one show-history entry ⌘Z takes back, and `KindEffects` runs what the records'
  writes would. No reload of the whole project, no commit of its own: the next snapshot or sync
  carries it. Its own token covers the chosen records and their *also needed*.
- **Row ids:** `RecordImage.rowId` is a row id from the moment it was exported, which a record from
  git doesn't have. The session settles how `restore` inserts one with a fresh id, without changing
  undo's behaviour.
- **Refusals:** a name taken since, as an undo refuses (`HISTORY_UNDO_BLOCKED`); a folder outside
  `RESTORABLE_FOLDERS`; on a show on a venue, a key the venue holds.
- **Tests:** a deleted cue comes back with its stack entry and its Looks; ⌘Z takes it back; a name
  clash refuses with nothing written; the journal entry's label.
- **Docs:** `docs/sync-engineering.md`, `docs/undo-engineering.md` (a door that restores from git), the
  root `CLAUDE.md`; `FU-MANUAL-RESTORE-S3`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `sync`, `history` and
  `routes` packages, and `npm run check:quick`.

## 6. Operator checks

One row in `manual-validation.md` per merged session, on a show synced between two desks:

- `FU-MANUAL-RESTORE-S1`: restore an older snapshot through the API while the show runs; the rig
  crossfades and never dips; the other desk takes it on *Sync now*; restoring `before` puts it back.
- `FU-MANUAL-RESTORE-S2`: the same from the History panel; the review's counts match what changed; the
  toast's *Undo* works; a restore with the network off goes out on the next sync.
- `FU-MANUAL-RESTORE-S3`: bring back a cue deleted last week, with its Look; ⌘Z takes it back.

## 7. Explicitly out of scope

- Restoring a venue, or a venue version.
- Restoring machine-local settings (universe addresses, the grand master, remote access).
- Showing History for a project with snapshots but no repo attached (`CloudSync.tsx` shows it only when
  `config.synced`).
- An MCP tool for restoring.
- Resetting or force-pushing a branch (D1).
- Better messages for automatic snapshots in the History list.
