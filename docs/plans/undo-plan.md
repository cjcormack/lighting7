# Undo and history — programmer steps and a journal of record changes, behind one key

> **Document status: AGREED 2026-10-20, not started.** The questions asked and their answers are
> in §11 and in the design record, [`undo-design/INDEX.md`](undo-design/INDEX.md). A one-page copy
> for human readers is [`undo-design/undo.html`](undo-design/undo.html), with a live copy at
> <https://claude.ai/artifact/J9PPZacmX1RdV8YTbqAgCZ> (the checked-in file is the authority). Where
> this plan and the page disagree, the plan wins on behaviour and the page on layout and copy.

## 1. Context

Every other desk has an Undo key. MagicQ undoes up to four programmer steps and then actions
(Record, Set, Move, Copy, Remove, Patch), with a history window. grandMA3's Oops takes back any
number of actions and clears on a patch change. Eos undoes per user through a command history. Hog 4
undoes back to logon, per console. None of them undoes playback. lighting7 has about ten one-step
Undos, each written for one place, and nothing reaches a delete, a Record, a Clear or an edit Claude
made.

The findings that shape the plan, from three surveys of `main` at `59ebdb7`:

- **The undos that exist are one-step inverses.** The hand's place toast runs a call-site inverse
  (`frontend/src/store/hand.ts:132-213`, `HAND_UNDO_MS = 10_000`; the inverses in
  `BuskingView.tsx:443`, `CueSlotOverviewPanel.tsx:371`, `RigBand.tsx:578`,
  `HandLayerTargets.tsx:190`). The cue editor keeps the lists it is about to change and re-PATCHes
  them (`components/cues/editor/CueRowStore.tsx:297-319`, ⌘Z at `CueValuesGrid.tsx:324-338`, the only
  ⌘Z in the app). Prompt-book anchors keep one snapshot (`lib/promptBook/useBookAnchors.ts:149`).
  Make rig safe's Undo is backend runtime and strikes lamps (`state/RigSafetyService.kt:241-266`).
  The cue-authoring plan put "undo/redo inside an edit session" out of scope
  (`completed/cue-authoring-unification-plan.md:826`).
- **Writes have no single path.** About 206 mutating route definitions under `routes/`, each opening
  its own `transaction(state.database)` (about 230 of them), and 79 more in `ai/`, which resolve the
  project themselves (`ai/ToolProject.kt`) and so skip `routes/projectWriteGuard.kt`. Parks
  (`dmx/ParkManager.kt:152`), surface learn (`plugins/SurfaceSocket.kt:277`) and the speed-master
  tempo write-through (`show/Show.kt:538`) write from WS frames or the clock. Every write is a
  blocking `transaction {}`: there is no `newSuspendedTransaction`, and outside the two startup
  migrations no raw-SQL write.
- **A whole-project restore re-mints every integer id.** `ProjectImporter.replaceFromWorkingTree`
  (`sync/ProjectImporter.kt:497-610`) deletes every child table and re-imports, so cues, stacks and
  entries get new ids (`models/LayerSource.kt:29`, `sync/dto/SyncDtos.kt:900`). URLs
  (`/show/stacks/:stackId`), open editors, standby, holds and `ShowCarry` (`show/ShowCarry.kt:28-36`)
  name those ids. Venue *Discard* chains export → replace → `switchProject(reload = true)`
  (`venue/VenueVersions.kt:164-226`), which is fine for a discard and wrong for an undo.
- **Every portable record already has a canonical form.** The exporter writes each record as
  canonical JSON at `{folder}/{uuid}.json` and records its `RecordKey(tableName, uuid)`
  (`sync/RecordHasher.kt:19`). But `export` is one function (`sync/ProjectExporter.kt:159-790`)
  writing every kind inline through `writeAll` and `writeJson` (`:800`, `:964`), always to disk;
  `VenueDiff.recordsOf` (`venue/VenueDiff.kt:60`) exports to a temp folder and reads it back. A
  300-patch rig exports and compares in about 100 ms (`docs/fixtures-engineering.md:2446`).
- **The importer is per kind, but over a folder.** `populateProject`
  (`sync/ProjectImporter.kt:695-750`) runs `importScripts`, `importLooks`, …, `importCues` (`:1368`)
  in foreign-key order, threading uuid→DAO maps, every function private, reading a `dir` and calling
  `DaoX.new {}`.
- **`SyncCoverageTest` already maps every table to its record folder.** `Disposition.Portable(exportDir,
  embeddedField)`, `ProjectRoot`, `MachineLocal`, `Excluded`
  (`src/test/kotlin/.../sync/SyncCoverageTest.kt:106-130`), test-only today.
- **Exposed has the hook.** `DbTracing.StatementSpans` is a `GlobalStatementInterceptor` registered
  through `META-INF/services` (`telemetry/DbTracing.kt:91`) with per-transaction user data.
- **Each kind has one live consequence.** Look and palette edits republish
  (`routes/lookRepublish.kt:63, 87, 106`); cue edits mark *edited since GO* (`fx/CueHolds.kt:446`);
  patch, group, universe, hide and facet edits reload fixtures in place with
  `DbFixtureLoader.loadFixtures` (`projectPatches.kt:269`, `projectPatchGroups.kt:120`,
  `projectUniverseConfigs.kt:114`, `patchFacets.kt:134`, `showHides.kt:105`); speed masters reload
  (`Show.reloadSpeedMasters`, `show/Show.kt:564`). Change frames go through one notifier,
  `FixturesChangeListener` (`show/Fixtures.kt:23-116`, emitters `:472-592`), into
  `plugins/BroadcastSocket.kt`.
- **The programmer is several holders.** `ProgrammerStore` (`fx/ProgrammerStore.kt:194`): private
  slot stacks and channel sideband, `blind`, `lastIncludedTarget`, `layers`,
  `includedLayerSnapshot`, `includedKeys`, epochs, no snapshot API. Local FX are engine
  instances matched by `isLocalFx` (`fx/FxInstance.kt:662`). Locate is `LocateManager`'s private
  map (`show/LocateManager.kt:25`); the programmer's scenery is an immutable `Snapshot`
  (`state/ProgrammerScenery.kt:56`). A reload hands the same objects across by reference
  (`ShowCarry.kt:63-71`).
- **Who and where are known at the doors only.** REST has the account (`auth/AuthGate.kt:20`) and no
  window; WS has `SocketScope.user` and `.window` (`plugins/SocketScope.kt:81-92`); MCP has the grant
  (`sessionTokenHash = "mcp-grant:<id>"`) and its `clientName` (`mcp/McpAuthService.kt:425, 477`),
  but `McpProtocol.callTool` (`:165`) passes only a display name, and only to SetupTools; a surface
  has no user.
- **No ⌘Z, ⇧⌘Z or history UI exists** outside the cue editor. Overview panels are push-down bands
  (`components/overviewPanels.tsx`); Screens… is a Sheet (`components/screens/ScreensSheet.tsx`);
  one `<Toaster position="bottom-right" />` (`App.tsx:512`). Binding targets are
  `midi/BindingTarget.kt`'s sealed class.

## 2. Decisions

Agreed with Chris on 2026-10-20 (§11):

| # | Decision |
|---|---|
| D1 | **⌘Z takes back yours**: your account's show changes from any of your windows, plus programmer steps from anyone, since there is one programmer. Claude (each MCP grant) and each surface are sources of their own. A zero-user desk has one source, *Desk*. |
| D2 | **One key, newest first** across both histories. The programmer's action bar keeps a programmer-only Undo and Redo, and so does the *Programmer undo* binding. |
| D3 | **An older entry that something has changed since is never overwritten.** The answer lists the later entries it collides with; *Undo back to here* takes them too, or Cancel. An entry that collides with nothing is undone on its own. |
| D4 | **Parks are in the show history**; tempo moves, the selection and machine-local settings are not. |
| D5 | **50 programmer steps; 500 show entries or 32 MB per project**, kept while the desk runs and across a switch away and back. A sync pull or import, a venue Update, Attach, Detach, Extract or Discard, a project delete and a restart clear it. |
| D6 | **Locked, Undo asks to unlock. While the show runs, an undo that would move the stage confirms first and names what moves**; one that changes only stored data goes straight through with a toast. |
| D7 | **Claude is a source per grant, named by its client**, with `list_history` and `undo_changes`, which take back its own entries only. One tool call is one entry. |
| D8 | **One entry per request, frame or tool call.** Writes from the same source to the same records within 1.5 s of the last merge into one entry. |
| D9 | **An undo puts records back exactly, ids kept, with no reload**, and runs what the original write ran: its change frames and its live hooks. |
| D10 | **Redo is linear per source.** A new entry from a source drops that source's undone entries. Programmer redo is dropped by any new programmer step. |
| D11 | **The existing undos become pointers into the history.** The hand, cue editor and prompt-book toasts keep their words and undo their entry. Make rig safe keeps its own Undo. |
| D12 | **Undo is a stored write under `/projects/{id}`**, so the write guard applies unchanged: an operator cannot undo onto a venue, a venue version is never journaled. |
| D13 | **Undo is REST only; `history.state` is outbound only.** The socket gains no operation, so `FU-AUTH-WS-PER-MESSAGE` does not fire. |
| D14 | **A write with no origin is untracked**: background writes (the tempo flush, scheduled work) never make an entry. The records they change are rebased into the journal, so the change is invisible to history and never reads as a conflict. |
| D15 | **The history is runtime state.** No table, no column, no `formatVersion` change; nothing about it syncs. |

## 3. The model

### 3.1 Vocabulary

- **Source**: who a change belongs to. `account:{uuid}`, `mcp:{grantId}` (shown as *Claude ·
  {clientName}*), `surface:{deviceKey}`, or `desk`. Two windows signed in as one account are one
  source; the window is kept for display (*You · Desk left*).
- **Origin**: one door's `WriteOrigin(source, window?, requestId, projectId?, label?, kind)`, `kind`
  `TRACKED | UNDO(entryIds) | REDO(entryIds)`.
- **Record image**: one record's canonical JSON and row id, keyed `(folder, uuid)`.
- **Entry**: one change to a project's records, `HistoryEntry(id, projectId, origin, at, label,
  changes: List<Change(folder, uuid, rowId, before?, after?)>, undone)`.
- **Step**: one programmer checkpoint, `ProgrammerStep(id, origin, at, label, snapshot)`.
- **Clear point**: a moment the history of a project is dropped (D5), drawn as a rule in the sheet.

### 3.2 Write origin (D1, D8, D14)

`WriteOrigin` is a coroutine context element that is also a `ThreadContextElement`, so a blocking
`transaction {}` on the handler's thread sees it through a `ThreadLocal`. Doors set it; nothing
between the door and the transaction changes:

| Door | Where | Source | Window |
|---|---|---|---|
| REST | an application plugin (`plugins/HistoryOrigin.kt`) intercepting every `/api/rest` call | the session's account, else `desk` | `X-Desk-Window`, the registry row id, which `restApi`'s `rawBaseQuery` sends |
| WS | the dispatch loop, per inbound frame | `SocketScope.user` | `SocketScope.window` |
| MCP | `McpProtocol.callTool` | `mcp:{grantId}` from `sessionTokenHash`, its `clientName` from the grant | none |
| Surface | `SurfaceInputRouter`, per input | `surface:{deviceKey}` | none |

The REST plugin mints `requestId` (`h-` + a base-36 counter) and appends it as the response header
`X-History-Entry` before the handler runs: a request that writes nothing names an entry that never
appears. Routes and tools may name their entry: `call.historyLabel("Record Q13 into Act 1")`, and
for MCP the tool's own description of what it did. A project-scoped door sets `projectId` and calls
`HistoryService.ensure(projectId)` before the handler, so the project's record cache exists before
the first write (§3.3).

A transaction with no origin — the debounced tempo flush (`Show.flushSpeedMasterWrites`), scheduled
work, startup steps — is untracked (D14). So is a write under `ParkManager`'s clock-driven paths
that are not a gesture. The tempo flush is the one known case that writes a portable record; §3.4
says what happens to it.

### 3.3 Records in memory

Three refactors, none of which changes an exported byte:

- **`sync/SyncDispositions.kt`**: `SyncCoverageTest.dispositions` moves to main code, so the journal
  and the test read one table → folder map. The test keeps its assertions.
- **`RecordKind`s in the exporter.** `ProjectExporter.export` becomes an ordered list of kinds, each
  `RecordKind(folder, tables, export(project, sink))`, the existing code cut into sections. `writeAll`
  and `writeJson` write to a `RecordSink`: `DirSink` (today) or `MapSink`
  (`(folder, uuid) → RecordImage`). `export` iterates every kind into a `DirSink`;
  `exportKinds(projectId, folders, MapSink)` runs some. `VenueDiff.recordsOf` moves to `MapSink`.
- **`ProjectRecords`**: a project's whole `MapSink`, built by `HistoryService.ensure` and held while
  the project has history.

A record image keeps its row id: `RecordImage(json, rowId, hash)`. `project.json` is the record
`("project", projectUuid)`; a script is one record, its `.kts` and `.meta.json` together.

### 3.4 The journal

**Capture.** `JournalInterceptor : GlobalStatementInterceptor`, registered beside `StatementSpans`:

- `beforeExecution`: an `INSERT`, `UPDATE` or `DELETE` adds its statement's target tables to the
  transaction's user data.
- `beforeCommit`: if any table was touched and an origin is present, map the tables to folders
  through the dispositions, re-export those folders for every project with a `ProjectRecords`
  (normally the current one, and any project an MCP tool named) inside the same transaction, which
  reads its own writes, and diff by hash against the cache. Stage the result on the transaction.
  Re-entrant reads from the hook are skipped by the interceptor.
- `afterCommit`: hand the staged diff to `HistoryService`, which updates the cache and appends the
  entry. `afterRollback` drops it.

Because the pool has one connection, commits are serial and so are the entries. A tracked transaction
re-exports only the kinds it touched; a cue edit re-exports cues and their children, tens of
milliseconds at most. Session 3's first step is a spike proving `beforeCommit` can export inside the
transaction. If it cannot, the fallback is a `writeTransaction(origin) {}` helper that diffs at the
end of its block, and the doors' transactions move onto it.

**Entries.** One per `requestId` per project. A second commit under the same `requestId` folds into
the entry (first `before`, last `after` per record). D8's merge: a new entry from the same source
touching exactly the same keys within 1.5 s of that entry's last write folds into it, so a dragged pad
or a nudged park is one entry. A diff that comes out empty makes no entry.

**Labels.** The origin's label when the door gave one, else a `RecordLabeler` per folder reading the
image's name and number: one record — *Cue Q12 “Rose garden” deleted*, *Look “Warm wash”: 4 values*
(its child rows counted); several — *Act 1: renumber 8 cues*, *3 cues, 1 Look changed*. Counts are
added, removed and changed records.

**Untracked writes (D14).** Their diff updates the cache and is rebased into the journal: for each
changed record, the fields that changed are patched into that record's `before` and `after` images
in every entry that holds it, so later conflict checks see the same hashes. An untracked add or delete
of a record (none is known today) clears the project's history and logs why.

**Caps and clear points (D5).** The oldest entries go first past 500 or 32 MB of images. A clear point
drops the project's entries, steps and `ProjectRecords`, and records the rule (*Pulled from GitHub ·
19:02*): `replaceFromWorkingTree`, `ProjectImporter` imports, venue Update, Attach, Detach, Extract
and Discard (each already ends in `switchProject(reload = true)`), project delete, restart.

**Redo branches (D10).** Appending an entry from source S deletes S's undone entries.

### 3.5 Undo and redo

**What the key takes (D1, D2).** `POST /history/undo {}` with no entry id picks, for the caller's
source, the newer of the current show's top programmer step and the source's newest entry that is
not undone. `{programmerOnly: true}` takes the step. `POST /history/redo {}` picks the most recently
undone of the source's entries and the programmer's redo stack.

**Conflicts (D3).** Undoing entry E needs every record in E to be as E left it: the cache's hash
equals E's `after` hash. Otherwise the answer is 409 `HISTORY_CONFLICT` naming the later, not undone
entries that touch E's records. Resent with `backToHere: true`, the undo takes, newest first in one
transaction: on the caller's own row, every later entry of the caller's source; on anyone's row, the
colliding entries; and E. Redo is the mirror: the cache must match E's `before`.

**Others' entries.** From the sheet, anyone may undo anyone's entry, with the conflict rule. From MCP,
`undo_changes` refuses an entry not its own: 409 `HISTORY_NOT_YOURS`.

**Applying (D9).** `RecordRestorer` runs in one transaction under an `UNDO` origin:

- delete, in reverse foreign-key order, records whose `before` is absent;
- upsert, in the importer's order, records with a `before`: found by uuid and **updated in place**,
  else **inserted with their old row id** (`DaoX.new(rowId) {}`; the tables are SQLite
  `AUTOINCREMENT`, so an id is never reused);
- the importer's per-kind functions read a `RecordSource` (`DirSource` today, `ImageSource` here) and
  take `mode: INSERT | UPSERT`. In `UPSERT` the uuid→DAO maps are seeded from existing rows. An
  embedded child (a Look's rows, a group's members, a cue's events and scenery) is matched by uuid
  inside its parent, updated or inserted, and the absent ones deleted. Embedded children's integer ids
  are not kept; nothing names them.

**Invariants.** The database's constraints catch a collision (a key someone has since reused) and roll
back the whole undo: 409 `HISTORY_UNDO_BLOCKED` naming the record (*A fixture called “spot-3” exists
now*). A rule held only in route code runs after the restore and before commit, `VenueRigChecks`'
clash checks for a show on a venue first. Session 4 lists the rest.

**KindEffects.** After commit, one table maps each touched folder to what its write would have done
(§5). The undo is itself an entry's state change, not a new entry: E is marked `undone`.

**Confirms (D6).** An undo that moves the stage while the show runs answers 409 `HISTORY_CONFIRM`
`{moves: [...]}` until resent with `confirm: true`. *Moves* is: a Look or palette that a live cue or a
programmer layer uses, a rig change that rebuilds the fixtures as its own route's write would (a patch or
group added or taken away, a patch's address, type or key, a universe, a member order, an Address or
Media override, a patch or group hide; a drawn-only change asks nothing — Session 5 "As built"), a park. *The
show runs* is any live cue or loose hold. The locked rule is the client's, as the show-editing lock is
(§4.5).

### 3.6 Programmer history

**Snapshot.** `ProgrammerStore.snapshot(): StoreSnapshot` and `restore(snapshot)`: the slot stacks
and sideband (copied; slots are immutable), `layers`, `lastIncludedTarget`, `includedLayerSnapshot`,
`includedKeys`. Not `blind`, not the epochs (`restore` bumps them). Beside it in
`ProgrammerSnapshot`: the Local FX as specs (`LocalFxSpec`: the add request's fields, its
targets and its current phase), `LocateManager`'s active map, and `ProgrammerScenery`'s elements.

**Checkpoints.** `ProgrammerHistory`, owned by the `Show` and adopted by `Show.resume` with the store
on a reload, keeps a ring of 50 steps. The holders call `history.beforeMutation()` at the head of
every mutation: the store's `put*`, `clear*` and `mutateLayers`, the engine's Local-FX add, stop
and edit, `LocateManager`'s writes and `ProgrammerScenery`'s. It takes a snapshot when the origin's
`requestId` differs from the last step's, and none without an origin, so a reload's recook or a
derived write never checkpoints. A continuous gesture (`programmer.set`, `setColour`, `setPosition`,
an encoder or fader move from a surface) merges into the last step while its source, frame type,
targets and attribute match and less than 1 s has passed since the last frame.

**Labels.** From the door: *Clear*, *Release Hex 1*, *Press “Warm wash” on Hex 1–6*, *Fan pan over
Spot 1–4*, *Hex 1–6 colour → Magenta*, *Claude: set_programmer*.

**Restore.** Under an `UNDO` origin, at the request's `fadeMs` (the programmer fade the client
holds): the store restored and republished at the fade, layers recooked (`ProgrammerLayerStack.reinstate`), Local
FX diffed by instance id (those not in the snapshot shrink out at the fade, those missing respawn
at their phase, edited ones take their spec through `applyFxUpdate`), Locate and scenery diffed.
While Blind is on the restore is staged, as any programmer write is. A project switch drops the
history with the programmer.

### 3.7 The wire

| Route or frame | Session | Notes |
|---|---|---|
| `X-Desk-Window` request header | 3 | The registry row id, on every REST call. |
| `X-History-Entry` response header | 3 | The request's entry id, on every REST answer. |
| `GET /projects/{id}/history?limit=&before=` | 3 | Entries and steps newest first with clear points; each `{id, kind: SHOW \| PROGRAMMER, at, label, source: {kind, name, window?}, counts, records?, undone, redoable, moves}`. Both roles. |
| `POST /projects/{id}/history/undo` `{entryId?, backToHere?, programmerOnly?, confirm?, fadeMs?}` | 1, 4 | Answers `{undone: [{id, label}], history}`. 409 `HISTORY_EMPTY`, `HISTORY_CONFLICT {later}`, `HISTORY_CONFIRM {moves}`, `HISTORY_UNDO_BLOCKED {record, reason}`, `HISTORY_NOT_YOURS` (MCP only); a programmer step on a project that is not current is a 409. Session 1 shipped the programmer half, session 4 the show half. |
| `POST /projects/{id}/history/redo` `{entryId?, programmerOnly?, confirm?, fadeMs?}` | 1, 4 | The mirror. |
| `history.state` | 1, 3 | Outbound, per socket, `StateFlow`-backed: `{projectId, undo: {id, label, kind}?, redo: {…}?, revision}` for the socket's source. A client refetches the sheet when `revision` moves. |
| Binding targets `undo`, `redo`, `programmerUndo` | 6 | Buttons, acting as the surface's source. |
| MCP `list_history`, `undo_changes` `{projectId?, entryId? \| count?}` | 6 | Claude's own entries; `count` takes its newest n. |

## 4. UX

The boards are `undo-design/undo.html` §5.

### 4.1 Header

A joined Undo / Redo pair (lucide `Undo2` / `Redo2`) after `ProgrammerIndicator`, before the
overview toggles, white on the header's blue, then a `History` icon that opens the sheet. Tooltips
name the entry and its key: *Undo · Delete cue Q12 “Rose garden”* ⌘Z; a programmer step's tooltip
is violet-tinted; empty after a clear point, *Nothing to undo since the sync pull at 19:02*. Both dim
when there is nothing to take. Below the 640 px header width they fold into the user menu.

### 4.2 The History sheet

A right-hand Sheet at Screens…'s width (`sm:max-w-lg`): title, the project, Undo and Redo; filter
chips *All · Mine · Show · Programmer · Claude*; rows newest first, each a mono time, a kind tile (blue
for show, violet for programmer), the label, a source chip, the counts (+ added, − removed, ~ changed)
and, when the undo would move the stage, *live in Q11* or *changes output*. Hover shows *Undo back to
here* on your own row and *Undo {name}'s change* on anyone else's. Undone rows stay, struck and dimmed,
with Redo while redoable. A clear point is a rule. Clicking a row expands its records, each a link. The
footer says the depths and what clears it. An operator's rows on a venue are read-only.

### 4.3 The programmer

The action bar gains Undo (with the step's label in muted text: *Undo · Clear*) and Redo ahead of
Clear, programmer-only. A long press or right-click on Undo lists the last steps; picking one goes
back to before it. The Clear toast carries Undo.

### 4.4 Toasts

The existing bottom-right toasts gain Undo after a delete, a Record, an Update, a Clear and any bulk
edit; an undo's toast says what came back (*Q12 is back in Act 1 between Q11 and Q13*) and carries
Redo. Each Undo button names its entry by `X-History-Entry`, so it undoes the right thing after other
edits (D11). A refused undo is one error toast in the record's words, never a partial.

### 4.5 Confirms

Three dialogs, each from a 409: *HISTORY_CONFLICT* lists the colliding entries newest first with their
sources, *Undo n changes*; *HISTORY_CONFIRM* names what moves (*Q11 is live and uses “Warm wash”. Its
values will move over its 5.0s fade.*), *Undo on stage* in amber; and the lock: *Unlock and undo*,
reusing the show-editing lock's unlock.

### 4.6 Keys

⌘Z and ⇧⌘Z (Ctrl on Windows and Linux) on `window`, skipped inside a text field, a contenteditable or
an open dialog with its own keys. The cue editor's ⌘Z (`CueValuesGrid.tsx:324`) is removed in favour
of the global one. ⌘K gains *Undo*, *Redo* and *Show history*.

### 4.7 Phone

The phone runner's user menu gains Undo, Redo (each with its label) and History, which opens the sheet
full height.

### 4.8 Surfaces

`undo`, `redo` and `programmerUndo` in `BindingTarget`, `BindingControlKind` (button),
`SurfaceInputRouter`, `BindingHealthEvaluator`, and on the frontend `surfacesApi.ts`'s union,
`surfaceDrop.ts`, `BindingTargetPicker` and the Desk-row chips, labelled *Undo*, *Redo*, *Programmer
undo*.

### 4.9 Claude

The MCP instructions gain one line: changes Claude makes can be listed and taken back with
`list_history` and `undo_changes`, and the operator sees them in History.

## 5. Scope and what an undo sets off

| Folder(s) | On undo | Moves the stage |
|---|---|---|
| `looks`, `palettes` | `republishForLookEdit` / `…PaletteEdit` / `…Delete`; `lookListChanged` / `paletteListChanged`; `cuesRecomposed` | if a live cue or a programmer layer uses it |
| `cues`, `cueAttributeAssignments`, `cueLayers`, `cueAdHocFx`, `cueTriggers` | `holds.markEdited` for a held cue; `cueListChanged`; `cueStackListChanged` | no (edited since GO) |
| `cueStacks`, `cueStackEntries` | `cueStackListChanged`; the run state re-announced (`announceEntriesPlaced`) | no |
| `cueSlots`, `buskPages`, `buskRig` | `cueSlotListChanged`; `busk.layoutChanged(pageIds)`; `busk.rigChanged` | no |
| `fixturePatches`, `fixtureGroups`, `universeConfigs`, `showHides`, `showOverrides`, `showColours`, `showGroupOrders` | as each route's write: `DbFixtureLoader.loadFixtures` where it rebuilds, else the patch metadata refreshed or `fixturesChanged`; `patchListChanged` | where it rebuilds |
| `riggings`, `stageRegions`, `stageElements`, `stageViewpoints` | their list frames; `SceneryService` recompute | no (drawn) |
| `parkedChannels`, `showParkReleases` | `ParkManager` reloads the effective parks | yes |
| `speedMasters` | `Show.reloadSpeedMasters`; `speedMasters.listChanged` | no |
| `scripts`, `fxDefinitions` | recompile the registry; `scriptListChanged` / `fxDefinitionListChanged` | no |
| `promptBooks`, `promptBookAnchors`, `promptBookAnnotations` | `promptBookChanged` | no |
| `controlSurfaceBindings` | the binding service reloads | no |
| `project` | `projectDetailsChanged` | no |

Not undoable: GO, BACK, GO TO, standby, holds, stack FX amount; arm, fire, fixture commands, bulk lamp
runs; grand master, tempo taps and BPM moves, Blind, the selection, window layout; machine-local
settings (`machine_overrides`, `project_scaler_states`, tube state, sync config); sync, venue publish,
Update, Attach, Detach, Extract, Discard; creating, deleting and switching projects; accounts and
install settings.

A test ties the journal to `SyncDispositions`: every `Portable` folder has a `RecordKind`, a codec
and a row in this table's `KindEffects`, or the build fails.

## 6. Implementation — six sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. Sessions 1
and 2 need nothing from each other, but they run in order like the rest. A session ends with the
checks its **Locally** line names green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it
names written, and its row marked *in review* with its branch. A session's PR is titled
*Undo: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | programmer history (backend + programmer UI) | merged | `claude/nice-hypatia-xf5tk7` | [#330](https://github.com/cjcormack/lighting7/pull/330) | `5ac9ce0` |
| 2 | records in memory (backend, no behaviour change) | merged | `claude/admiring-gauss-1m7xs5` | [#331](https://github.com/cjcormack/lighting7/pull/331) | `36a577c` |
| 3 | origin and journal, read-only (backend + sheet) | merged | `claude/gracious-wozniak-i6mim5` | [#333](https://github.com/cjcormack/lighting7/pull/333) | `0b1ac3f` |
| 4 | putting records back: the cue side (backend) | merged | `claude/pensive-curie-096obw` | [#335](https://github.com/cjcormack/lighting7/pull/335) | `4f1542d` |
| 5 | putting records back: the rest (backend) | in review | `claude/keen-ritchie-1y5b98` | | |
| 6 | the controls (frontend + MCP + surfaces) | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — programmer history (backend + programmer UI)

- §3.6: `ProgrammerStore.snapshot/restore`, `LocalFxSpec`, Locate and scenery snapshots,
  `ProgrammerHistory` and `beforeMutation` in every holder; a minimal `WriteOrigin` carrying source
  and `requestId` at the WS dispatch, the programmer REST routes, the press services, MCP's programmer
  tools and `SurfaceActions`, enough for steps and their coalescing (session 3 completes it).
- `POST /history/undo|redo {programmerOnly: true, fadeMs}` and `history.state`'s programmer half.
- Frontend: the action bar's Undo, Redo and steps menu; the Clear toast's Undo; ⌘Z and ⇧⌘Z while the
  programmer view has focus.
- **Tests:** for each door, a gesture then undo restores the store, layers, Local FX (phase
  kept), Locate and scenery to equal the snapshot; a fader drag is one step; a reload keeps the
  history, a switch drops it; under Blind the restore stages; a restore is not itself a step; redo
  dropped by a new step.
- **Docs:** `lighting-composition-model.md` §"Programmer history"; CLAUDE.md's endpoints and WS
  frames; the frontend CLAUDE.md's programmer section.
- **Locally:** the quick checks — `compileTestKotlin`, the tests of every package it touches (`fx`,
  `state`, `routes`, `ai`, `midi`) and `npm run check:quick`. The full suites are CI's.
- **As built**, where it differs from §3 or binds a later session:
  - `LocalFxSpec` is a frozen copy of the instance (settings, ownership, start, accumulator, N-shot
    count) rather than the add request's fields. A spec finds its instance by identity, then by id
    only in the engine that took the step (a reload numbers its FX afresh); a respawn keeps its old id
    where it is free and moves the engine's counter past it.
  - The REST origin plugin already covers every write under `/api/rest` (§3.2's door); session 3 adds
    the window header, `X-History-Entry` and the project. Only the programmer's holders react to it so
    far. `ProjectManager.switchProject` runs under a fourth origin kind, `UNTRACKED`, so a reload set
    off by a request takes no step.
  - Never a step: `putLayerSlots` (a Look edit's recook), a stored locate's re-assert (run under the
    untracked origin), the scenery's `forget` and `attach`, a call that changes nothing, and the
    `UNPARK` and `FLASH` owners' writes — whose slots a step does not keep either, so a restore leaves
    them as they are.
  - The store's snapshot keeps the include target, `includedLayerSnapshot` and `includedKeys`, as §3.6
    says, but not `sourceKeys` or `recordedValues`: they describe the stored source as the last
    Include, Record or Update read it, which an undo does not change. A restored layer keeps the cue
    row an Update since tied it to.
  - A step belongs to its `WriteOrigin` (it records that it has one), not to the last request id, so
    a Claude tool call interleaved with a drag is still one step. A restore drops its layers whose
    Look or palette is gone and its scenery whose element is gone. A step taken while an undo restores
    drops redo, and a restore that throws puts its steps back.
  - `history.state` is one frame for every socket while it holds programmer steps alone; session 3
    makes it per source. It also carries the programmer's step lists, which the steps menu reads.
  - An `entryId` naming no step is 404 `HISTORY_UNKNOWN`.
  - The venue write guard passes `history` as a runtime gesture, since its path cannot tell a
    programmer step from a show entry. **Session 4 must refuse an operator's show-entry undo onto a
    venue in the route** (D12).
  - Redo folds into Undo's menu below `@[800px]` of row A, so the folded phone row keeps one line.

### Session 2 — records in memory (backend, no behaviour change)

- §3.3: `SyncDispositions` to main; `RecordKind`s, `RecordSink`, `DirSink`, `MapSink`,
  `exportKinds`; `VenueDiff.recordsOf` on `MapSink`.
- **Tests:** `RichProjectFixture` exported by `DirSink` before and after is byte-identical (a golden
  hash recorded before the refactor); `MapSink` equals the folder read back; `exportKinds` of each
  folder equals that folder of a whole export; `SyncCoverageTest` reads the moved table unchanged.
- **Docs:** `sync-engineering.md` §"How to add a new table" (where dispositions live now).
- **Locally:** the full `./gradlew test`: a refactor of the exporter that every sync, clone, venue
  and round-trip test goes through. Frontend untouched.
- **As built**, where it differs from §3.3 or binds a later session:
  - The fixtures mint random uuids, so the golden is not a plain hash: `ExportGoldenTest` hashes each
    export with every uuid replaced by a token derived from where it appears, and pins the file
    count. Both were recorded from `main` at `7d0a768` before the refactor.
  - A `RecordKind` is `(folder, write)`; its `tables` are read from `SyncDispositions`, not listed
    per kind. `ProjectExporter.KINDS` and `FOLDERS` are the list; `RecordKindsTest` holds them to
    `SyncDispositions.folders`, `project` included.
  - `formatVersion.json` and `installs.json` are not records, so `export` writes them itself and no
    kind does. A `RecordImage`'s `hash` is the SHA-256 of the file `DirSink` would write, a
    script's over its meta, a newline and its body — `RecordHasher`'s for a record in today's names,
    without its `LegacyNames` parse, which the journal comparing images to images has no use for; its `json` for a script is the meta with the body as `body`, as
    `VenueDiff` read one from the folder before it moved onto `MapSink`.
  - `exportKinds` refuses a folder no kind writes and runs in the caller's transaction when there is
    one, which session 3's `beforeCommit` relies on. `exportRecords(projectId)` is every kind into a
    `MapSink`.
  - §5's table and session 5's list leave out `showGroupOrders`, which is a portable folder; the
    coverage test session 5 adds will name it.

### Session 3 — origin and journal, read-only (backend + sheet)

- §3.2's four doors in full, `X-Desk-Window` from `restApi`, `X-History-Entry`, `historyLabel`.
- §3.4: the `beforeCommit` spike first, then `JournalInterceptor`, `ProjectRecords`,
  `HistoryService` (entries, merge, labels, rebase, caps, clear points), `GET /history`,
  `history.state`'s show half.
- The History sheet, the header's History icon and its filters, listing entries with nothing undoable
  yet: an activity log.
- **Tests:** a sample of mutating routes from each area (cue, stack, Look, palette, busk, patch,
  stage, park, script) each make one entry with the right source, window, label and counts; an MCP
  tool call is one entry under `mcp:`; a cue editor's flushes within 1.5 s are one; a rollback makes
  none; the tempo flush makes none and a later Look edit's entry holds no `bpm`; a clear point drops
  history; a write to another project by MCP journals there.
- **Docs:** new `docs/undo-engineering.md` (origin, cache, journal, untracked writes); CLAUDE.md's
  §"Engineering Documentation" row; `websocket-engineering.md` §"History".
- **Locally:** the full `./gradlew test` (the interceptor sits under every stored write, in every
  package) and `npm run check:quick` for the sheet.
- **As built**, where it differs from §3 or binds a later session:
  - The spike held: `beforeCommit` exports inside the committing transaction, so no
    `writeTransaction` helper exists. Two things it needed: the hook flushes the DAO's entity cache
    itself (the DAO's interceptor may run after it in `ServiceLoader` order), and the staged diff is
    held by transaction, since Exposed clears a transaction's user data on commit. A failure in the
    hook clears the journaled projects' history rather than failing the write.
  - The cache is built lazily: each door calls `State.ensureHistory` for the current project and the
    one it names (a REST path's, an MCP tool's `projectId` through `PROJECT_SCOPED_TOOLS`), and a
    project gets one on its first write after a clear point. Only the touched tables' folders are
    re-exported: the desk runs with SQLite's foreign keys off, so no delete cascades.
  - `X-History-Entry` is on every write's answer, not a read's: a read has no origin. It is the request
    id; an entry keeps every request id folded into it and the page lists them as `requestIds`, which
    is how session 6's toasts find their entry.
  - The entry's name is `WriteOrigin.entryLabel`, set by `historyLabel` before the write commits; the
    door's `label` stays the programmer step's, since the REST door fills it from the path for every
    call. Record names its entry (*Record “Rose garden”*, *Record into “Rose garden”*); MCP tools do
    not yet, so their entries are named from the records, and the source chip says Claude.
  - D8's merge also folds a create and the rename that follows it within 1.5 s: they touch the same
    record.
  - Clear points drop show entries only. The programmer's steps keep session 1's rules (a reload keeps
    them, a switch drops them), so a sync pull leaves them. Two clear points with nothing between
    them within 5 s are one rule. A project delete forgets the project's history outright.
  - An untracked add or delete clears the project's history as *Changed outside the history*.
  - `moves` is read from the folders alone (patch, group, universe, hide, override, colour, order,
    park). Whether a Look or palette edit moves a live cue is session 4's confirm.
  - `history.state` keeps `revision` as the programmer's, which the Clear toast's step wait reads, and
    adds `showRevision`, the show history's; its show half is the programmer frame's project, so the two
    agree mid-switch. The undo and redo routes' answers still carry the programmer-only frame; session 4
    makes them the caller's.
  - A rebase sets an array field (a Look's rows) only in an image that held the value it replaced; one
    an entry changed too clears the project's history. A change a rebase leaves as it began drops out.
  - A project whose cache build fails is not tried again for a minute.
  - A grant whose client calls itself Claude, or gives no name, is plain *Claude*, not *Claude ·
    Claude* (Chris, 2026-11-02).
  - Every commit re-exports its folders for every project with a cache, as §3.4 says; Chris kept it
    (2026-11-02), and `FU-HISTORY-RESTAGE-COST` is the trigger to revisit.
  - The History icon sits after `ProgrammerIndicator` from `sm` up; on a phone it is *History…* in the
    user menu. ⌘K's *Show history* is session 6's, with the rest of ⌘K.
  - A row's expanded records are plain text; §4.2's links to each record are session 6's, with the
    sheet's actions.
  - The full suite took 8 min 39 s in a cloud container, dominated by the migration tests, which no
    door touches; route tests are unchanged in cost (`PaletteRoutesTest`, 76 tests, 9.7 s).

### Session 4 — putting records back: the cue side (backend)

- §3.5: `RecordSource`, `ImageSource`, the importer's `UPSERT` mode and `RecordRestorer` for
  `cueStacks`, `cueStackEntries`, `cues` and their children, `looks`, `palettes`, `cueSlots`,
  `buskPages`, `buskRig`, `project`; the conflict check, back-to-here, confirms, `KindEffects` for
  those rows of §5; undo and redo routes for show entries; the post-restore checks this side needs.
- **Tests:** on `RichProjectFixture`, for each of these folders, a write then undo leaves the export
  byte-identical and every row id unchanged, and redo returns the write's export; deleting a cue held
  on a busk pad, in two stacks and anchored in the prompt book then undoing restores all of it with
  the cue's id; a conflict answers the colliding entries, back-to-here takes them; a Look undo while a
  cue uses it republishes and needs `confirm`; a key clash rolls back whole.
- **Docs:** `undo-engineering.md` §"Restore"; `sync-engineering.md` (the importer's two modes).
- **Locally:** the full `./gradlew test`: the importer gains a mode that sync, clone and the venue
  flows share. Frontend untouched.
- **As built**, where it differs from §3 or binds a later session:
  - `promptBookAnchors` is restorable here, not in session 5: a forced cue delete removes the anchors on
    its entries, and the session's own test puts one back. Session 5 takes `promptBooks` and
    `promptBookAnnotations`.
  - Only the cue side's import functions read a `RecordSource` and take the `Run` (`DirSource` or
    `ImageSource`, insert or upsert); the rest still read their folder directly. Session 5 moves each it
    restores onto the `Run`, places its rows through `Run.row`, adds its deletes to
    `ProjectImporter.deleteRecord`, its folder to `RESTORE_ORDER` and its row to `KindEffects`.
    `RecordRestoreTest`'s identity test then covers it with no change.
  - Against §3.5, a Look's and a palette's rows and FX that still exist are matched by uuid in an upsert
    and keep their row ids: an included Look's FX is named by its row id (`IncludedLookFx.lookFxId`). One
    the undo inserts again takes a new id, and every other embedded child is deleted and inserted again
    from the image, uuid kept and id new (`FU-HISTORY-EMBEDDED-CHILD-IDS`).
  - A cue's entry from before the cue model's entries took its cue's uuid (`CueEntryUuidMigration`), so
    the link check can read the two as linked when they are not: an undo then asks or refuses, never
    writes wrongly.
  - Before it commits, the restore exports the folders it wrote and holds them to the cache with the
    targets over it — every record exact, with its row id, and nothing else in those folders moved —
    else the undo rolls back as `HISTORY_UNDO_BLOCKED`. A constraint failure (a name taken since) and an
    image naming a record gone outside the history are the same refusal, `record` naming the most likely
    record. Session 5's venue key checks go before the commit, beside it.
  - Records link by uuid, and collisions follow the links (D3): a later entry collides when it touches
    a record the undo puts back, when a record it wrote links to one the undo takes away (a stack entry
    another source added for a cue whose creation is undone), or when it took away a record one the undo
    puts back links to. The closure repeats until nothing more collides, so back-to-here's newest-first
    undo never meets a record moved by an entry it left; the 409's `later` is that set. Only the
    restorable folders count as links: a binding's payload tolerates a record gone. A record the undo
    would take away that a standing record outside every entry links to is refused as in use.
  - An entry holding a folder no restore covers yet is refused whole, before any write, as
    `HISTORY_UNDO_BLOCKED` *Undoing this kind of change is not available yet*.
  - Redo takes the source's most recently *undone* entry (`HistoryEntry.undoneSeq`), so redo after
    undoing E3 then E2 brings E2 back first. Its conflict names the later entries still standing and
    the earlier ones still undone that touch the same records; it has no back-to-here. Between the
    programmer and the show the key's undo takes whichever was written last and its redo whichever was
    undone last (`undoneAt` on both; `showTakes`, shared by the route and `history.state`).
  - The undo or redo's commit marks its entries (`WriteOrigin.entryIds`, a property set once the plan is
    made) in the same locked step that moves the cache; one that writes nothing, its records already
    where it puts them, is marked after the transaction.
  - Entries the undo takes away leave through `removeEntries`, so a live one is detached as *Remove from
    stack* leaves it. A stack it takes away is stopped only once nothing can refuse the undo, and stops
    being any project's playhead. What it sets off runs through the routes' own after-commit helpers,
    under an origin of the undo's kind, so the programmer takes no step for it.
  - The confirm counts a held cue's stored layers and the programmer's, which republish; a layer the undo
    itself puts back is not on stage and asks nothing, its cue marked edited since GO. A `pal:` reference
    in an FX parameter is not counted. Its `moves` are sentences (*Live cue “Q11” uses Look “Warm wash”*,
    *The programmer uses …*).
  - A held cue is marked edited since GO when its `stageContent` moved, read before and after the restore,
    as a cue PATCH marks it.
  - A stack entry whose number the export carried as a held auto number comes back explicit, as an
    import makes it; the export is identical. The explicit numbers an upsert writes are freed first, from
    an auto neighbour that took one and between entries that trade them. After the restore every stack
    it touched is renumbered as its records say: an entry whose image carries a number keeps it, every
    other auto entry is derived as an import derives it. The renumber by position the write itself ran
    cannot be replayed backwards, and left a label no reader would derive.
  - The undo and redo routes resolve the project with `withProject`; a step still needs the current
    project, answered as before.

### Session 5 — putting records back: the rest (backend)

- Codecs and effects for `fixturePatches`, `fixtureGroups`, `universeConfigs`, `showHides`,
  `showOverrides`, `showColours`, `riggings`, `stageRegions`, `stageElements`, `stageViewpoints`,
  `promptBooks`, `promptBookAnnotations`, `scripts`, `fxDefinitions`,
  `speedMasters`, `parkedChannels`, `showParkReleases`, `controlSurfaceBindings`; venue key checks;
  `SceneImageStore`'s prune skips a hash any journal image names.
- The coverage test (§5's last paragraph).
- **Tests:** the identity test over every folder; a patch undo reloads fixtures and confirms while
  running; a park undo restores output; undo onto a venue as an operator is 403; a show-on-venue undo
  that would clash with a venue key is blocked.
- **Docs:** `fixtures-engineering.md` (an undo of a patch, hide or facet), `undo-engineering.md`.
- **Locally:** the full `./gradlew test`: the coverage test reads every portable folder, and patch,
  venue and park restores reach the loader. Frontend untouched.
- **As built**, where it differs from §3 or binds a later session:
  - Every portable folder is restorable, `showGroupOrders` among them, so `RESTORABLE_FOLDERS` is every
    portable folder and `project`, and the refusal of a kind *not available yet* is gone. The coverage test
    is `RecordRestoreTest`'s: the portable folders, `RESTORABLE_FOLDERS`, `ProjectExporter.FOLDERS` and
    `KindEffects.folders` are one set, and the labeler names each. Its identity test runs the rich fixture
    for every folder but the five only a show on a venue holds, and `seedShowOnVenue` for those; a hide is
    taken and given back, never edited.
  - A script is one record from either source: `DirSource` reads `{uuid}.meta.json` and `{uuid}.kts`
    together, the body as `body`, as `MapSink` holds one.
  - A binding is the one restorable folder whose records are not links (its payload tolerates a record
    gone). A prompt book's anchors and notes name no book, so each links to the project's book: an undo
    that would take the book away from under a note collides with the note's entry.
  - Deleting a record takes its embedded children (a patch's placements, a group's members) and nothing
    else; what its route sweeps beside it is a record of its own, taken with it or refused as in use.
    Machine-local rows naming it are left, so a redo finds a universe's address again, and an upsert never
    writes a universe's `address` column. A patch's placements and a group's members are matched by uuid
    and keep their ids.
  - A rig undo reaches the fixtures as its own route's write does (`KindEffects.rigReach`, Chris
    2026-11-02, against §3.5's and §5's first wording, which reloaded for any of them): a rebuild for what the loader
    builds from, the patch metadata for a patch's drawn fields and a Focus override, the fixture list for
    a recolour, nothing for a Position override or a hide of a rigging, element, region or viewpoint. The
    confirm counts only what rebuilds (*The fixtures reload: Fixture “spot-3”*) and the parks (*Parked
    channels change: …*) while a cue is held, beside session 4's Looks and palettes.
  - The venue key check is `VenueRigChecks.clashesOf` read before and after the restore, for an undo
    touching a patch, group, rigging, element, region, viewpoint or hide on a show on a venue; a clash the
    restore made is refused as `HISTORY_UNDO_BLOCKED` *It would clash with the venue*. Session 4's route
    already refused an operator's show undo onto a venue (403), and its test stands for this session's.
  - Session 4's renumber after the restore (`renumberAsWritten`, merged in `4f1542d`) now goes through
    `renumberAsArchived`, which writes each held number back as well as keeping it, whatever an earlier
    pass of the restore made of it. This session's identity test found the same bug over `stageRegions`
    before that fix reached it, and now runs every record of every folder, as session 4's does.
  - The upsert first moves every unique key or name it rewrites off its old value (a patch's key, the
    names, a speed master's index, an FX definition's id), so undoing a swap (`rename_fixtures`,
    `edit_groups`) puts each back.
  - An undo that puts back a script or an FX definition is held to the remote script gate (403
    `REMOTE_SCRIPTS_DISABLED` on the public listener unless allowed). Session 6's `undo_changes` needs the
    same check.
  - The effects run the rig and the parks first, so a Look, palette or cue republished after resolves
    against the reloaded rig; with the rig, every held cue the undo touched is republished
    (`republishCueIfLive`), and every unit a patch or override it moved stands for has its Locate
    re-asserted — what `rename_fixtures` and a stored locate's write do.
  - A park undo reloads the parks through a new `ParkManager.reload`: each channel moves straight to its
    new park, one left with none is handed down first as `unpark` hands one down, the venue parks refused
    at load stay out, and a park made while it reads is kept; then `Show.dropRefusedParks`.
    `loadFromDatabase` is unchanged, the start's alone. An FX definition undone on the live project is
    unregistered under the id it had and registered again, and left unregistered if it no longer
    compiles.
  - A speed master undo keeps the live tempo (§5, D4): only the stored row and the bank's other fields
    come back.
  - `FU-HISTORY-MACHINE-LOCAL` is recorded.

### Session 6 — the controls (frontend + MCP + surfaces)

- §4: the header pair, global keys, ⌘K, the sheet's undo actions, toasts with Undo and Redo, the
  three confirms, the hand, cue editor and prompt-book undos moved onto entries (and the cue editor's
  ⌘Z removed), the phone menu, the binding targets, MCP's two tools and instruction line.
- **Tests:** Vitest for the key handler's skips, the toast's entry id from the header, the confirm
  flows from each 409; the binding targets' kinds; MCP refusing another source's entry.
- **Docs:** the frontend CLAUDE.md (§"Undo"), `midi-control-surface-engineering.md`,
  `mcp-engineering.md`, CLAUDE.md's endpoints and MCP paragraph.
- **Locally:** the full `npm run check` (the header, keys and toasts reach most views), and the
  quick backend checks for `ai` and `midi`.

## 7. Explicitly out of scope

- Undoing playback; recovering a last GO is the Show view's BACK.
- A history that survives a restart or travels with sync.
- Branching history, or merging an undone branch back.
- The Stage editor's in-drag undo; its committed placements are journaled like any patch write.
- Selective programmer undo (taking back one step without the ones after it): a step is a snapshot
  of the shared programmer.

## 8. Migration

None (D15). No table, no column, no `formatVersion`. Session 2 changes how the exporter is
structured, not one byte of what it writes.

## 9. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-HISTORY-EMBEDDED-CHILD-IDS`: an embedded child restored by undo takes a new integer id; if
  anything ever names one by id, keep them (session 4).
- `FU-HISTORY-MACHINE-LOCAL`: undo for machine-local settings (universe addresses), declined in D4,
  if a desk night asks for it.

## 10. Verification

Beyond the suites, at the desk on Sleeping Beauty:

- **Session 1.** Busk a Look, fan pan, Clear at 1 s; Undo brings everything back over 1 s with the
  Look's FX mid-phase; Undo again takes back the fan.
- **Session 2.** Export Sleeping Beauty before and after; `diff -r` is empty.
- **Session 3.** Edit a cue, a Look and a park from two windows and ask Claude to rename a group;
  History lists four entries with the right sources and windows.
- **Session 4.** Delete Act 1 Q12 (on a pad and anchored); Undo; Q12 is back at its place with its
  pad and anchor, and `/show/stacks/{id}` still opens. Edit “Warm wash” while Q11 is live; Undo
  confirms and the stage moves over Q11's fade.
- **Session 5.** Re-address a Spot, then Undo while running: the confirm names it and the rig follows.
- **Session 6.** ⌘Z across a Record and two programmer steps takes them in order; ⇧⌘Z brings them
  back; the X-Touch's Undo key does the same.

## 11. Questions

Answered by Chris, 2026-10-20, in two rounds, the recommended answer each time:

1. **Whose changes does ⌘Z take back?** Yours plus the programmer's (D1). One desk-wide stack and one
   stack per window were declined.
2. **Programmer and show: one key or two?** One key, newest first (D2). MagicQ's programmer-first
   order and two keys were declined.
3. **An older entry something changed since?** Back to here, or Cancel (D3). Refusing and
   overwriting were declined.
4. **Parks, tempo, selection, machine-local?** Parks only (D4).
5. **Depth and lifetime?** 50 and 500, until a clear point or a restart (D5). Clearing on a switch
   and persisting across a restart were declined.
6. **Undo while running or locked?** Confirm when the stage moves; locked asks to unlock (D6). No
   confirms, and show undo off while a stack runs, were declined.
7. **Claude?** Its own source per grant, with an undo tool for its own entries (D7). No tool, and
   counting as you, were declined.
