# Undo and history

The desk keeps two histories behind one key (undo plan, `docs/plans/undo-plan.md`): the
**programmer's steps**, snapshots of the shared programmer (`history/ProgrammerHistory.kt`,
`docs/lighting-composition-model.md` §"Programmer history"), and the **show history**, a journal of
every stored record a request, frame, tool call or surface input changed. This document is the show
history: who a change belongs to, the record cache it is measured against, how the journal captures a
commit, and what happens to a write that belongs to no one. Putting records back is the plan's
sessions 4 and 5; until then the show history is read-only, an activity log in the History sheet.

Both are runtime state (D15): no table, no column, nothing synced. A restart starts empty.

## Write origin

`history/WriteOrigin.kt` is a coroutine context element that is also a `ThreadContextElement`, so the
blocking `transaction {}` a handler runs reads it through a `ThreadLocal` on whatever thread it lands.
Four doors set one, and nothing between a door and the transaction changes:

| Door | Where | Source | Window |
|---|---|---|---|
| REST | `installHistoryOrigin` (`plugins/HistoryOrigin.kt`), every non-read call under `/api/rest` | the session's account; `desk` with no accounts | the `X-Desk-Window` header: the `windows.state` row id, which `restApi`'s base query sends |
| WS | the dispatch loop in `Sockets.kt`, per inbound frame (`SocketScope.writeOrigin`) | `SocketScope.user` | `SocketScope.window` |
| MCP | `McpProtocol.callTool`, per tool call | `mcp:{grantId}`, named *Claude · {clientName}* from the grant, or plain *Claude* for a client called Claude or unnamed | none |
| Surface | `SurfaceInputRouter.route`, per input | `surface:{deviceKey}` | none |

A source is `HistorySource`: an account, a Claude grant, a surface, or the desk. Two windows signed in
as one account are one source; the window is kept for display.

The REST door answers the request's id as `X-History-Entry` before the handler runs. The entry that
holds the request's writes lists that id in its `requestIds` (an entry holds every request folded into
it); a request that changed nothing is in no entry. A
route names its entry with `call.historyLabel(…)` (a tool with `historyLabel(…)`), which sets
`WriteOrigin.entryLabel` — before the write commits, since the entry takes its name then; otherwise `history/RecordLabeler.kt` names it from the records (below).
`WriteOrigin.label` is the programmer step's name and is not the entry's: the REST door fills it from
the path for every call, which is too coarse for a show entry.

Each door also makes sure the project its writes go to has a **record cache** before the handler runs
(`State.ensureHistory`): the current project always, the project a REST path names
(`/projects/{id}/…`), and the project an MCP tool's `projectId` names (`PROJECT_SCOPED_TOOLS`). A
project without one is not journaled. A venue version never has one (D12). A project whose export
fails is not tried again for a minute, since every frame and surface input asks.

A transaction with no origin is **untracked** (D14): the debounced tempo flush
(`Show.flushSpeedMasterWrites`), scheduled work, startup steps. So is one under
`WriteOrigin.Kind.UNTRACKED`, which `ProjectManager.switchProject` runs under so that a switch a
request sets off is no one's gesture.

## The record cache

`HistoryService` (`history/HistoryService.kt`, `State.history`) holds, per project, every record the
exporter writes for it, in memory: `(folder, uuid) → RecordImage(json, rowId, hash)`, the
`ProjectExporter.exportRecords` map (sync-engineering.md §"Repo layout (Phase 1)", its `MapSink`). `ensure` builds it inside its
own transaction and installs it before that transaction ends: the pool has one connection, so no commit
can land between the export and the cache going in. The cache is what a commit is diffed against, and
it is kept up to date by every commit the journal sees, tracked or not.

## The journal

`history/JournalInterceptor.kt` is a `GlobalStatementInterceptor`, registered through
`META-INF/services` beside `DbTracing.StatementSpans`. Exposed's interceptors are global, so each
`Database` registers the `HistoryService` it feeds (`State.history`'s initialiser).

- **`beforeExecution`** adds an `INSERT`, `UPDATE`, `DELETE` or `MERGE` statement's target tables to the
  transaction's user data.
- **`beforeCommit`** first flushes the DAO's entity cache — pending entity writes reach the database
  here, not in the DAO's own interceptor, which may run after this one in `ServiceLoader` order. A
  failed flush fails the commit, as it did before: the DAO drops a table's pending inserts before it
  runs them, so swallowing the failure would commit without those rows. It maps
  the touched tables to record folders through `SyncDispositions` (no delete cascades: the desk runs
  with SQLite's foreign keys off, so every row a write changes is its own statement's), re-exports those folders for every project with a cache, **inside the committing
  transaction**, which reads its own writes, and diffs them by hash against the cache. The diff is
  staged against the transaction. Reads it makes are skipped by the interceptor.
- **`afterCommit`** hands the staged diff to `HistoryService.commit`; **`afterRollback`** drops it.

Exposed clears a transaction's user data on commit, which is why the staged diff is held in a map keyed
by the transaction rather than in its user data. A failure in the hook never fails the write: the
history of every journaled project is cleared instead, since its cache can no longer be trusted.

A commit re-exports only the folders it touched: a cue edit re-exports the cue folders, not the patch.
A record whose JSON reads a table outside its own folder is refreshed only when its folder is next
written; the one known case is `project.json`'s `venueRepo` hint, which reads the machine-local sync
tables.

### Entries

`HistoryEntry` (`history/HistoryEntry.kt`) holds one `Change` per record — its image before the entry's
first write and after its last — the source, the window, the request id and the times.

- **One per request id per project.** A second commit under the same id folds into the entry that holds
  it, which may be another request's after a merge: an entry keeps every request id it took.
- **D8's merge.** A new commit from the source of the project's newest entry, touching exactly that
  entry's records, within 1.5 s of its last write, folds into it: a dragged pad, a nudged park, the cue
  editor's flushes, a create and the rename that follows it.
- A record that ends as it began drops out of its entry; an entry left with none is removed.
- **Redo branches (D10).** A new entry from a source drops that source's undone entries.
- **Caps (D5).** 500 entries or 32 MB of images per project; the oldest go first.

### Labels

`RecordLabeler` names an entry its door did not: one subject — *Look “Warm wash” added*, *Cue “Rose
garden” deleted*, *Park 0/5 added* — or several, counted by kind: *3 cues, 1 Look changed*. A cue's
values, layers, FX and triggers are their cue's (*Cue “Rose garden”: 4 values*), and so is the stack
entry of a cue added or deleted with it; other stack entries are their stack's. A record whose own
fields are unchanged but whose embedded rows moved counts those (*Look “Warm wash”: 4 values*).
`RecordLabelerTest` holds every record folder to a name.

### Untracked writes (D14)

An untracked commit's diff updates the cache and is **rebased into the journal**: each field it
changed is patched into the `before` and `after` images of every entry holding that record, and the
images re-hashed. A later conflict check then sees the record as the entry left it, and an undo would
not put the field back. A scalar is set in both images; an array (a Look's rows, a group's members) is
set only in an image that held the value the write replaced, since one the entry itself changed cannot
be merged. That, and an untracked add or delete of a record (none is known today), clears the project's
history and logs why. A change the rebase leaves as it began drops out of its entry — a tracked tempo
edit the flush then undoes leaves nothing to undo.

### Clear points (D5)

`HistoryService.clear(projectId, reason)` drops the project's entries and its cache, and records the
moment as a rule in the History sheet. The next door to name the project builds the cache again. The
callers:

| Where | Reason |
|---|---|
| `ProjectImporter.replaceFromWorkingTree` (a sync pull, a conflict resolution) | *Pulled from the sync repo* |
| … from `VenueVersions` discard | *Venue draft discarded* |
| `VenueUpdate.update`, `VenueAttach.attach`, `VenueDetach.detach`, `VenueExtract.extract` (not on a dry run) | *Venue updated*, *Attached to a venue*, *Detached from its venue*, *Venue extracted* |
| `deleteProjectRows` | the history is forgotten, not cleared |

Two clear points with nothing between them within five seconds are one rule — a pull's untracked
rewrite clears, and the pull itself then clears again. Clear points drop show entries only; the
programmer's steps follow their own rules (a reload keeps them, a switch drops them).

## Reading it

- `GET /projects/{id}/history?limit=&before=` (`routes/projectHistory.kt`): the project's show entries
  and, on the live project, its programmer steps, newest first, with its clear points among them as
  `CLEAR` rows. Each row is `{id, kind, at, label, source?, mine, counts?, records, moreRecords,
  requestIds, undone, redoable, moves}`, built only for the page asked for; `mine` is the caller's own source, `moves` is `changes output` for an entry
  holding a patch, group, universe, hide, override, colour, order or park. Any project, both roles.
- `history.state` (`plugins/HistorySocket.kt`, websocket-engineering.md §"History"): per socket, what
  the key would take for its source; `revision` is the programmer's, `showRevision` the show history's.
- The History sheet (`frontend/src/components/history/`): the header's History icon (the user menu on
  a phone), filters *All · Mine · Show · Programmer · Claude*, a row's records on a click.
