# Undo and history

The desk keeps two histories behind one key (undo plan, `docs/plans/completed/undo-plan.md`): the
**programmer's steps**, snapshots of the shared programmer (`history/ProgrammerHistory.kt`,
`docs/lighting-composition-model.md` §"Programmer history"), and the **show history**, a journal of
every stored record a request, frame, tool call or surface input changed. This document is the show
history: who a change belongs to, the record cache it is measured against, how the journal captures a
commit, what happens to a write that belongs to no one, and how an undo puts records back. Every
portable folder is restorable (plan sessions 4 and 5).

Both are runtime state (D15): no table, no column, nothing synced. A restart starts empty.

## Write origin

`history/WriteOrigin.kt` is a coroutine context element that is also a `ThreadContextElement`, so the
blocking `transaction {}` a handler runs reads it through a `ThreadLocal` on whatever thread it lands.
Five doors set one, and nothing between a door and the transaction changes:

| Door | Where | Source | Window |
|---|---|---|---|
| REST | `installHistoryOrigin` (`plugins/HistoryOrigin.kt`), every non-read call under `/api/rest` | the session's account; `desk` with no accounts | the `X-Desk-Window` header: the `windows.state` row id, which `restApi`'s base query sends |
| WS | the dispatch loop in `Sockets.kt`, per inbound frame (`SocketScope.writeOrigin`) | `SocketScope.user` | `SocketScope.window` |
| MCP | `McpProtocol.callTool`, per tool call | `mcp:{grantId}`, named *Claude · {clientName}* from the grant, or plain *Claude* for a client called Claude or unnamed | none |
| Surface | `SurfaceInputRouter.route`, per input | `surface:{deviceKey}` | none |
| OSC | `OscHandler.handle`, per message (`osc/OscHandler.kt`) | `osc:{senderId}`, named as the allowlist names the sender (*QLab*) | none |

A source is `HistorySource`: an account, a Claude grant, a surface, an OSC sender, or the desk. Two windows signed in
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

## Restore

`POST /projects/{id}/history/undo` and `…/redo` (`routes/projectHistory.kt`) take `{entryId?,
backToHere?, programmerOnly?, confirm?, fadeMs?}`. With no `entryId` the key takes, for the caller's
source, the newer of the live programmer's top step and its own newest show entry (D1, D2); an id is a
step's (`p-…`) or an entry's (`e-…`). A step goes back through the programmer's history, on the current
project only. An entry that puts back a script or an FX definition authors code, so on the public
listener it is 403 `REMOTE_SCRIPTS_DISABLED` unless an admin allows remote scripts, as those routes are
(`requireScriptAccess`). An entry goes back through `history/RecordRestorer.kt` on any project; on a venue an
operator is refused 403 `VENUE_ADMIN_ONLY` here, since the write guard passes the history path as the
programmer's. Both answers carry the caller's `history.state` frame for the project.

**Planning** (`HistoryService.planUndo` / `planRedo`) reads the record cache inside the transaction that
will write, after a first statement has taken the pool's one connection, so no commit lands between.

- **Undo** needs every record the entry holds to stand as it left it, nothing to have come to link to a
  record it takes away, and nothing it puts back to link to a record gone since. A later entry, not
  undone, collides when it touches one of the records, when a record it wrote carries the uuid of one the
  undo takes away, or when it took away a record one the undo puts back carries the uuid of — and so does
  any later entry that collides with a collider, until none does. Every restorable folder's records
  count as links but a binding's, whose payload tolerates a record gone (sync v11); a prompt book's
  anchors and notes name no book, since a project has one, so each links to it. With collisions the answer is
  409 `HISTORY_CONFLICT` naming them newest first (`later`); `backToHere` takes them too, and on the
  caller's own entry also every later entry of its source. Each record goes back to the oldest taken
  entry's `before`, and must stand as the newest left it, checked by hash; a record moved by something
  the history does not hold is a conflict with no `later`. A record the undo would take away that a
  standing record still links to (one written before the history began) is 409 `HISTORY_UNDO_BLOCKED`
  naming both.
- **Redo** takes the source's most recently undone entry (`undoneSeq`), or the one named, and needs
  every record as the entry found it; otherwise 409 `HISTORY_CONFLICT` names the later entries still
  standing and the earlier ones still undone that touch them. It is held to the same links.

With no `entryId`, undo takes whichever of the programmer's top step and the caller's show entry was
written last, and redo whichever was undone last (`HistoryEntry.undoneAt`, `StepInfo.undoneAt`); the
route and `history.state` decide it with one function, `showTakes`.

**Every door's undo is one function**, `history/HistoryMove.kt`'s `moveHistory(state, projectId,
caller, HistoryMove, window?, operator, scriptsAllowed)`, answering a `HistoryMoveResult` — `Steps`,
`Show(RecordRestorer.Outcome)`, `Empty`, `UnknownStep`, `NotLive` or `VenueAdminOnly`. Its
`refusal(undo, entryId)` is the one mapping of a refusal to its message, code and extras (`later`,
`moves`, `record`, `reason`), and each door carries it its own way. The routes answer it as HTTP; a
surface's `undo`, `redo` and `programmerUndo` buttons (midi-control-surface-engineering.md) run it as
`surface:{deviceKey}` at no fade, one press at a time in press order (one single-threaded dispatcher
and a `Mutex` round the whole move, so each decides what it takes only after the last finished), and
log a refusal, never confirming, so an undo that would move a running show is not done from one; Claude's
`undo_changes` (mcp-engineering.md §"Claude's own history") runs it entry by entry for its own grant
only, refusing another source's entry as `HISTORY_NOT_YOURS`.

**Confirm (D6).** On the live project, while any cue is held, an undo that moves the stage answers 409
`HISTORY_CONFIRM` with `moves` until it is resent with `confirm`: a change to the rig that rebuilds the
fixtures, as its own route's write would (`KindEffects.rigReach`: a patch or group added or taken away,
any but a patch's drawn fields, a universe, a group order, an Address or Media override, a patch or group
hide — *The fixtures reload: Fixture “spot-3”*), a park or park release (*Parked channels change: …*), and a Look or palette a held
cue's stored layer or a programmer layer uses, which republishes (*Live cue “Q11” uses Look “Warm
wash”*). A layer the undo itself puts back is not on stage, so it asks nothing; its cue is marked edited
since GO.

**Writing.** The transaction runs under an `UNDO` or `REDO` origin carrying the entries' ids, which its
commit marks undone or standing in the same step that moves the cache (`HistoryService.commit`), so a
reader never sees the records moved and the entry not; one whose records already stood where it put
them writes nothing, and is marked after (`HistoryService.mark`). Entries it takes away leave their
stacks through `removeEntries`, as *Remove from stack* does, so a live one is detached and its stack plays
on from where it stood. `ProjectImporter.restore` then deletes, in reverse foreign-key order, each other
record whose target is absent, with its embedded children; then it upserts the rest through the
importer's own per-kind code in `UPSERT` mode (sync-engineering.md §"Importer modes"), freeing the
explicit numbers it writes first — and every unique key or name it rewrites (a patch's key; a group's,
rigging's, element's, region's, viewpoint's, script's, Look's, palette's, busk page's or speed master's
name; a master's index; a definition's FX id), so two records that traded can take each other's back.
Each stack it touched is then renumbered as its records say (`RecordRestorer.renumberAsWritten`,
through `renumberAsArchived`): an entry whose image carries a number takes it, and every other auto
entry is derived as an import derives it, which a renumber by position, run backwards, would not give.
The export leaves a derivable auto number out, so an entry a delete renumbered may be in no entry at
all, and must still come back to the number its export implies. A stack the undo takes away is stopped
on the live project, and stops being any project's playhead, once nothing can refuse the undo.

Deleting a record takes its embedded children with it (a patch's placements, a group's members) and
nothing else: what its own delete route sweeps beside it — busk-rig tiles, group memberships, cue
events, anchors — are records of their own, which the undo takes away beside it or refuses as in use.
Machine-local rows naming it (a universe's address, an element's *Full detail*) are left, so a redo
finds them again. A universe's machine-local `address` column is never written by an upsert.

Before the commit the restorer exports the folders it wrote and holds them to the cache with the
targets laid over it: every record as its target says, with its row id, and no other record in those
folders moved. A difference rolls the undo back as 409 `HISTORY_UNDO_BLOCKED` (*It could not be put back
exactly*), and so does a constraint the database holds, a name someone has taken since among them
(*Something else has taken its name or its place since*), and a record that names one gone since outside
the history (*Something it names has gone since*), with `record` naming it. On a show on a venue, an undo
that touches a patch, group, rigging, element, region, viewpoint or hide is also held to the venue's
namespace: a key, head number or name the show's own records share with the pinned version's after the
restore that they did not before (`VenueRigChecks.clashesOf`) — one a venue record took back while the
show's was gone, unhidden since — is the same refusal (*It would clash with the venue: …*).

**What it sets off** (`history/KindEffects.kt`, §5 of the plan) runs after the commit, under an origin of
the undo's kind, so the programmer takes no step for it and no entry is made. It goes through the routes'
own after-commit helpers. Every folder sends the list frames its writes send. On the live project also:
a Look or palette is republished (`republishForLookEdit` / `republishForPaletteEdit`); one taken away is
released from the programmer's layers, and a Look's layering cues republished as a Look delete does
(`republishForLookDelete`); a cue taken away is let go (`afterCuesDeleted`); entries taken away go through
`afterEntriesRemoved`; a held cue whose *Update stage* content (`stageContent`, read before and after in
the transaction) moved is marked edited since GO, as a cue PATCH marks it; every stack an entry joined or
left, and every stack put back, has its holds restamped and its run state published
(`announceEntriesPlaced`). A change to the rig reaches the live fixtures as its own route's write does
(`KindEffects.rigReach`): one that rebuilds them reloads them (`DbFixtureLoader.loadFixtures`); a patch's
drawn fields alone (`PUT_KEYS_WITHOUT_REBUILD`, its locate) and a Focus override refresh its metadata in
place; a recolour, or a group's colour alone, announces the fixture list; a Position override and a hide
of a rigging, element, region or viewpoint are drawn only. Any of them re-asserts the Locate of every unit a patch or override it moved stands for (`reassertLocates`), as a
stored locate's write does, and sends `patchListChanged`, a hide also its record kind's list frame, and a
patch, group, recolour or group order `busk.rigChanged`; it runs first, with the parks, so a Look,
palette or cue republished after resolves its keys against the rig as it now stands, and every held cue
the undo touched is republished with a rebuild (`republishCueIfLive`) — the order `rename_fixtures` announces
its rewrite in. A rigging, region, element or viewpoint sends its list frame, the element's moving the
scenery (`SceneryService` recomputes on it); a park or release reloads the parks (`ParkManager.reload`:
each channel moved straight to its new park, one left with none handed down first as an unpark hands it
down, the venue parks refused at load kept out, and a park made while it read kept; then
`Show.dropRefusedParks`); a speed master reloads the bank, keeping the live tempo (D4); an FX definition
is unregistered under the id it had and compiled and registered again (`registerUserFx`), and one that no
longer compiles is left unregistered; a script, a prompt book or a note sends its frame; and a binding
drops the binding cache. `RecordRestoreTest` holds the table, `ProjectExporter.FOLDERS`
and the labeler to `RESTORABLE_FOLDERS`, and that to every portable folder.

The scene-image store's prune at project load (`ProjectManager.pruneSceneImages`) keeps every image a
stage element's image in the project's journal paints with (`HistoryService.sceneImageHashes`), so an
undo that puts a cloth back finds its paint.

A Look's and a palette's rows and FX, a patch's placements and a group's members that still exist are
matched by uuid in an upsert and keep their row ids — an included Look's FX is named by its row id; one
the undo inserts again takes a new one. Every other embedded child — scenery, a cue's events, a busk
page's columns, banks and pads, a rig row's tiles — comes back with its uuid and a new row id: an image
does not carry them, and nothing that outlives a re-read names them (`FU-HISTORY-EMBEDDED-CHILD-IDS`). A stack entry whose number the export carried as a held auto number
comes back explicit, as an import makes it.
