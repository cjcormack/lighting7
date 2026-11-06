# Show view editing fixes

> **Document status: IN PROGRESS — calls decided 2026-11-06 (§3).** Sessions 1–3 merged; sessions 4 and 5 in review.

The design record is [`show-view-fixes-design/INDEX.md`](show-view-fixes-design/INDEX.md), and its
one-page copy [`show-view-fixes.html`](show-view-fixes-design/show-view-fixes.html) holds the
mock-ups. Where the two differ, this plan wins.

## 1. Context

Seven things make a show slow to edit on the Show view, all reported on 2026-11-06:

1. Duplicate drops the cue's follow. Follow belongs to the **stack entry**
   (`cue_stack_entries.auto_advance`, `.auto_advance_delay`), and `POST /cues/{id}/duplicate` takes
   only `{stackId}` (`routes/projectCues.kt` line 411), so it cannot know which place was
   duplicated; `placeCueInStack` (`routes/projectCuesHelpers.kt` line 1023) makes the copy's entry
   with follow off. `CueCopier.copyCue` (`routes/cueCopier.kt`) already copies every column of every
   table hanging off a cue, so nothing else the cue owns is missing.
2. Marquee-selecting near the bottom of the cue sheet opens and closes the bottom pane under the
   pointer. The pane is derived from the selection (`CueSheet.tsx`'s `paneRow`, line 647; the
   Timeline's twin, `StackTimeline.tsx` line 673), so a marquee crossing from one row to two
   changes the sheet's height and the marquee lands on another row.
3. The pane appears and vanishes with the selection at all.
4. A cue number can be edited only by double-clicking one row: it sits in the sheet kit's first
   column, the row selector (`CueSheet.tsx` lines 868–950), so it can never join a cell selection.
   Several cues are renumbered only through the bar's *Renumber…* (`RenumberCuesButton.tsx`,
   `POST …/cue-stacks/{id}/renumber`).
5. Arming a cue as next is a locked gesture only: a locked click on a card's body
   (`CueCardEditor.tsx` line 154) or the sheet's Cue column (`CueSheet.tsx` line 690), and the bar's
   *Arm as next* drawn only while locked (line 751). On a card the grip replaces the pip on hover
   while unlocked (line 199).
6. A show being built or teched locks and washes amber whenever a stack runs. The lock is
   `!canEdit || (isShowActive && lockRequested)` (`hooks/useEditLock.ts`), and the wash is computed
   by each host as `!locked && isShowActive` (`routes/ShowPage.tsx` line 168,
   `routes/PromptBookPage.tsx` lines 180, 633 and 648); `routes/Cues.tsx` and
   `components/busking/ShowTab.tsx` call the hook, and `components/history/HistoryMove.tsx` reads the
   lock slice and the run state itself (line 186).
7. Dragging one of several selected cues moves only that cue: the kit's `handleDragEnd`
   (`components/sheet/SheetTable.tsx` line 291) moves the active row alone.

## 2. Decisions

- **D1 — Duplicate carries the follow.** `DuplicateCueRequest` gains `entryId: Int?`, the place
  duplicated from. The desk refuses 400 an entry that does not place this cue in this project, and
  passes its `autoAdvance` and `autoAdvanceDelay` to `placeCueInStack`, which already takes both.
  Absent (a loose cue, an older client) the copy has no follow, as now.
- **D2 — What Duplicate does not carry, deliberately.** The cue number (the copy takes an auto
  number), the prompt-book anchor (two cues at one point on the page draw as one mark), busk pads,
  cue slots and bindings (they name the source). The copy still lands **at the end** of the stack,
  unnumbered (call 1), and its FX take **fresh run keys** (call 2).
- **D3 — The editor pane is a per-window toggle.** An *Editor* button in `StackDetail`'s header,
  drawn on the Table and the Timeline and not on Cards, `aria-pressed`, kept in
  `sessionStorage cueSheet.paneOpen` beside `cueSheet.paneHeight` (a `createSyncStore` like
  `cuePaneHeightStore`). A window that never chose has it **on** (call 3). On, the pane is always
  drawn at its height; off, never.
- **D4 — The selection only chooses what the pane shows.** One cue row (Table), or cells in one
  cue's row (Timeline): `CueEditor`, as now. Nothing: *No cue selected · Select a cue to edit it
  here.* Several cues: *3 cues selected · The editor shows one cue at a time. The bar above acts on
  all three.* Only separators: *A separator · Rename it in its row.*
- **D5 — During a marquee the pane keeps what it had** (`sheet.marqueeDragging`) and follows the
  selection on release, so `CueEditor` is not mounted per frame.
- **D6 — A read-out that opens the pane turns it on.** Layers, FX and the Move in dark chip set the
  toggle on if it is off, then select the row and ask for their tab, as now. A plain click never
  turns it on.
- **D7 — The first column becomes the row head**: the drag grip, the pip and the played tick. A
  click selects the row (locked, arms it, as the Cue column does now); a drag from it is the row
  marquee; the grip reorders.
- **D8 — Cue # is a cell column** directly after the row head (call 4), `kind: 'cueNumber'`, its
  `value` the entry's number (`''` when auto) and `undefined` on a marker. It is not sticky.
- **D9 — One Cue # cell edits as today**: the number field, committed as the entry `PATCH` with
  `placement: 'ASK'` through `useCueNumberPlacement`, so an out-of-order number asks *Move to its
  place* or *Keep it here*.
- **D10 — Several Cue # cells edit as a renumber.** The cell editor over a batch is the renumber
  form: *First* (the typed value, else the first selected cue's whole number), *Step* (1), the
  landing previewed (`renumberPreview`), then one `POST …/renumber {entryIds, start, step}` in stack
  order. Whole numbers only (call 5): a decimal is refused with `renumberProblem`'s reason.
- **D11 — `RenumberForm`** is pulled out of `RenumberCuesButton` and mounted by both the bar's
  *Renumber…* (over a row selection) and the batch cell editor; `renumberProblem` and
  `renumberPreview` are unchanged.
- **D12 — Clear on Cue # returns each to auto** (`cueNumber: null`), one `PATCH` per entry like the
  sheet's other cells. No Spread on this column. Locked, it is inert like every value cell and a
  refused gesture asks to unlock.
- **D13 — The pip arms.** `CueStatePip` (`components/cues/CueRowParts.tsx`) takes an optional
  `onArm`; given, it is a focusable button labelled *Arm Q13 as the next GO*, and its click calls it
  and stops propagation, so it never selects a row or expands a card. It is given exactly when the
  locked click arms today: the view has `onSetStandby` and the row is not live. The live pip and the
  already-next pip stay read-outs, titled *On stage* and *Next GO*. This holds in Cards, Table and
  Timeline, locked or not. The bar's *Arm as next* is drawn unlocked too.
- **D14 — On a card the grip leaves the pip's cell** for its own 14 px slot before it, drawn only
  unlocked; the card's grid templates gain the track.
- **D15 — In production** is a show setting: `projects.in_production`, boolean, default `true`,
  added by the reconciliation with no migration, so every existing show is in production. A new show
  starts **off** (call 6). Refused on a venue (a venue has no lock).
- **D16 — It is portable**: `ProjectJson.inProduction: Boolean = true`. Canonical JSON omits the
  default, so an archive from an older desk reads as in production and a show still in production
  writes the same bytes as today. A new optional field: no `formatVersion` bump
  (`docs/sync-engineering.md`, `ignoreUnknownKeys`). A clone keeps it. `RichProjectFixture` sets it
  false. The project's own fields are already restorable by undo.
- **D17 — REST.** `ProjectDto` and `ProjectDetailDto` carry `inProduction`; `UpdateProjectRequest`
  takes it; `POST /projects` creates a show with it off unless told otherwise. Both roles may change
  it: the lock is a stray-click guard, not access control.
- **D18 — The lock guards a running show in production.** `useEditLock` reads the current project's
  `inProduction` and owns the rule: `guarded = canEdit && isShowActive && inProduction`,
  `locked = !canEdit || (guarded && requested)`, `lockRelevant = guarded`, and a new
  `unlockedWarning = guarded && !locked`. Its hosts (`ShowPage`, `PromptBookPage`, `Cues`, the busk
  `ShowTab`) stop computing the wash themselves, and `useHistoryMove` asks the same rule instead of
  reading the slice and the run state.
- **D19 — Switching it on while a stack runs locks at once**, as starting the show does (the re-arm
  effect fires on the `guarded` transition, not only on the run state's).
- **D20 — The header says why there is no lock.** While a show not in production runs, the lock
  control's place shows a quiet, uncoloured *Not in production* label linking to Settings › General.
- **D21 — The switch is in Settings › General** (`routes/ProjectSettings.tsx`'s `general` tab),
  beside the name: *In production* — *On: while a stack runs, Show, the Prompt Book and the Cues
  table lock against stray clicks, and an unlocked running show is washed amber. Off: they stay
  editable while the show runs.*
- **D22 — Dragging a selected row moves the selection.** `SheetTable`'s `rowDrag` gains an optional
  `group(activeId): RowId[]`, the rows that travel with the one dragged, in row order. The cue sheet
  and the Timeline pass the selected rows (the kit's `selectedRows`: the row selection on the Table,
  the selected cells' rows on the Timeline) when the dragged row is one of them, else the row alone.
  Every other `rowDrag` host (`applied/StackTable.tsx`, `applied/AppliedTableShell.tsx`) passes none
  and is unchanged.
- **D23 — Where the block lands** is a pure function, `moveRows(rowIds, group, overId)` in
  `components/sheet/sheetModel.ts`: the group together, in its order, before the row dropped on when
  moving up and after it when moving down, which is where a single row lands now. A group with gaps
  closes up; a drop on a row of the group changes nothing. Separators travel like any row. Still one
  `reorder` request.
- **D24 — While dragging**, the group's other rows dim in place and the dragged row carries a **+n**
  chip; the drop line shows the landing. The gap stays one row high (dnd-kit's list strategy moves
  rows for the active one only); a block-high gap needs a custom strategy and is out of scope (§7).
  After the drop the selection stays on the moved rows (entry ids survive a reorder). Refused
  wherever a single drag is: locked, or under the Move in dark filter.

## 3. Calls

Decided by Chris on 2026-11-06, in two rounds (`show-view-fixes-design/INDEX.md`).

1. **A duplicate lands at the end of the stack**, as now (show-cue-editing plan D12). Directly after
   its source was declined.
2. **Its FX take fresh run keys.** A run joins neighbours only, and a copy at the end has none in
   common with its source.
3. **The editor pane starts on** in a window that has never chosen.
4. **Cue # is its own column**, not sticky. Keeping it in the sticky first column, with a kit change
   for a first column holding a cell, was declined.
5. **A renumber over several cells writes whole numbers only.** Decimal starts and steps were
   declined for this plan.
6. **In production is synced with the show; existing shows on, a new show off.** On for every show,
   and machine-local, were declined.

## 4. Risks

- **A lock host that computes its own state** would keep locking a show not in production, and
  nothing would fail. D18 lists every reader of `selectLockRequested` and every `!locked &&
  isShowActive` today; session 2 greps for both again and pins the hook's three answers in
  `useEditLock.test.tsx`.
- **The pane's blank state must keep its height exactly**, or the marquee bug comes back in a
  smaller form. Session 3's test measures the pane, not just its text.
- **Moving the number out of the first column** moves the `data-cue-row` / `data-entry-row` marks
  that `StackDetail`'s scroll-to-active and several tests query. They stay on the row head.

## 5. Implementation — five sessions

Each session is one PR, run by `/next-session`, titled *Show view fixes: session N — title*.
Sessions 3–5 change the cue sheet's first column, so they are stacked in order; never merge a later
session's PR before an earlier one's. Session 2 runs the full `./gradlew test` and `npm run check`
locally (a new column and a sync field); the others run `./gradlew compileTestKotlin` and the
touched packages' tests where they touch the backend, and `npm run check:quick` in `frontend/`
(root `CLAUDE.md` §"Pre-commit checks").

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Duplicate carries the follow | merged | `claude/festive-curie-bn6jsd` | [#359](https://github.com/cjcormack/lighting7/pull/359) | 4420de9 |
| 2 | In production | merged | `claude/stoic-babbage-zbutzf` | [#360](https://github.com/cjcormack/lighting7/pull/360) | 9e51a1a |
| 3 | The editor pane toggle | merged | `claude/gallant-faraday-nxc79c` | [#362](https://github.com/cjcormack/lighting7/pull/362) | 68abe96 |
| 4 | The row head: Cue # cells and arming from the pip | in review | `claude/lucid-bell-dkik4e` | [#363](https://github.com/cjcormack/lighting7/pull/363) | 99aa159 |
| 5 | Dragging a selection | in review | `claude/busy-fermi-v71afs` | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Duplicate carries the follow

D1, D2.

- `routes/projectCues.kt`: `DuplicateCueRequest(stackId?, entryId?)`; inside the transaction, the
  entry read and checked (it places `resource.cueId`, its stack is this project's; 400 `Cue stack
  entry not found` otherwise), its follow passed to `placeCueInStack(stack, copy, autoAdvance = …,
  autoAdvanceDelay = …)`. A request with `entryId` and no `stackId` makes a loose copy and ignores
  the entry.
- `store/cues.ts`: `entryId` on the duplicate call. (`api/cuesApi.ts` holds only the response
  type; the request body is built in the store.)
  `components/runner/CueActions.tsx`'s `duplicate` sends `row.entryId`; the Cues table's
  `actionRowOf` already hands an entry only when the cue has one place.
- Tests: `CueDuplicateAndMoveRoutesTest.kt` — an entry with follow on and a 2 s delay gives the
  copy's entry both; another cue's entry is 400; no `entryId` gives no follow.
  `CueActions.test.tsx` — the request carries the row's entry.
- Docs: `docs/cues-engineering.md`'s route table row for `duplicate`; frontend `CLAUDE.md`
  §"Cues, Stacks & Triggers" (*Duplicate appends the copy…*) and §"The cue action set".
- Local: `compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.routes.*'`, `check:quick`.

### Session 2 — In production

D15–D21.

- `models/projects.kt`: `inProduction = bool("in_production").default(true)` and the DAO field.
- `routes/projects.kt`: `ProjectDto` / `ProjectDetailDto` / `UpdateProjectRequest` /
  `CreateProjectRequest`; a create of a `SHOW` writes false unless the body says otherwise; an
  update naming it on a venue is 400.
- Sync: `ProjectJson.inProduction`, `ProjectExporter` / `ProjectImporter`, `RichProjectFixture`
  (false), `ProjectRoundTripTest`, `ProjectCloneTest`. Check `SyncDispositions` needs nothing (the
  `projects` table is already covered).
- `frontend/src/api/projectApi.ts`: the field; `routes/ProjectSettings.tsx`: the switch (D21),
  through the project update mutation, shows only.
- `hooks/useEditLock.ts`: D18 and D19, reading the current project through the existing project
  query; `unlockedWarning` returned. Hosts: `routes/ShowPage.tsx`, `routes/PromptBookPage.tsx`
  (including line 633's `warn` and line 648's inset ring), `routes/Cues.tsx`,
  `components/busking/ShowTab.tsx`; `components/history/HistoryMove.tsx` onto the same rule. Grep
  for `selectLockRequested` and `isShowActive` in every lock host afterwards (§4).
- `ShowLockControl` / `ShowHeader`: the *Not in production* label (D20).
- Tests: `useEditLock.test.tsx` (running and not in production: unlocked, `lockRelevant` false, no
  warning; switching on while running locks); a projects route test (create, update, venue
  refused); `ShowPage.test.tsx` (no lock control and no amber class while running not in
  production); `HistoryMove`'s locked undo not asking to unlock then.
- Docs: `docs/sync-engineering.md` (the field, no bump); frontend `CLAUDE.md`
  §"The show-editing lock" (the formula and the setting).
- Local: the full `./gradlew test` and `npm run check`.
- As built: the list DTO is `ProjectListDto` (there is no `ProjectDto`). A new show starts off on
  every door that makes one, not only `POST /projects`: MCP's `create_project` and the `Default`
  project a new desk boots on too. Naming it on anything but a show is 400 `IN_PRODUCTION_NOT_A_SHOW`
  on create and update. The undo restore of `project.json` (`ProjectImporter.restoreProjectRow`)
  puts it back with the name. The rule is `editLockRule` beside the hook, which `useHistoryMove`
  calls, and an unknown `inProduction` (loading, an older desk) reads as on. The lock re-arms on
  *running and in production* turning true (not `canEdit`, which the Prompt Book learns late), and
  `updateProject` re-arms it when it turns the setting on, since the switch is on Settings, where no
  lock host is mounted. On an auto-merge it merges three-way beside the pin (`mergeProjectPin`):
  this desk's change wins where the remote did not change it (Chris, 2026-11-06), so a desk that
  turned it on for a performance cannot lose the lock to another desk's older commit.

### Session 3 — The editor pane toggle

D3–D6.

- `components/runner/CueEditorPane.tsx`: `cuePaneOpenStore` beside `cuePaneHeightStore`; the pane
  takes the edited row or nothing, and draws the D4 blank states inside the same sized box.
- `CueSheet.tsx` and `StackTimeline.tsx`: draw the pane while the toggle is on; `paneRow` held
  during `marqueeDragging` (D5); `openPaneRef` turns the toggle on (D6); `keepPaneRowInView` only
  when the pane has a cue.
- `StackDetail.tsx`: the *Editor* toggle in the header on the Table and the Timeline.
- Tests: `CueSheet.test.tsx` — with the toggle on, a marquee from one row to three leaves the pane's
  height unchanged and shows the several-cues state; off, no pane; a Layers read-out turns it on;
  the stored choice survives a remount. The Timeline's twin in `StackTimeline.test.tsx`.
- Docs: frontend `CLAUDE.md` §Sheet kit's cue sheet entry (*The bottom pane…*) and the
  `CueEditorPane` docblock.
- Local: `check:quick`.
- As built: the content rule and the marquee hold are `runner/cuePaneContent.ts` (`paneContentOf`,
  `useHeldPaneContent`, `paneBlankText`), apart from the pane so they test without a DOM. One cue
  row with markers beside it shows that cue: the blank states count cues. The *only separators*
  state is in the model but unreachable today, because the sheet kit never selects a divider
  (`isSelectableRow`); session 5's selected separators would reach it. The pane no longer takes a
  `columnRef`: mounted with the sheet, its layout effect ran before the column's ref was attached
  and it never measured, so it reads its column as its own parent element. On the Timeline the
  read-out that turns it on is the Dark tap; a cell click only selects. The `?cue=` deep link (the
  Prompt Book's *Edit cue*) turns it on too, on both views (Chris, 2026-11-06). A blank state is not
  a key region, so a press on it leaves the keys with the sheet; the grip and the editor still are.

### Session 4 — The row head: Cue # cells and arming from the pip

D7–D14.

- `CueSheet.tsx`: `firstColumn` becomes the row head (grip from the kit, pip, played tick; the
  `data-cue-row` / `data-entry-row` marks stay on it, §4); the Cue # column (D8–D12), its batch
  editor mounting `RenumberForm` and calling `useRenumberCueStackEntriesMutation`; the bar's *Arm as
  next* unlocked too.
- `RenumberCuesButton.tsx`: `RenumberForm` extracted (D11).
- `components/cues/CueRowParts.tsx`: `CueStatePip({onArm, label})` (D13). Wired in `CueSheet.tsx`,
  `StackTimeline.tsx` (line 1081) and `CueCardEditor.tsx` (line 201), whose grip moves to its own
  slot (D14).
- Tests: `CueSheet.test.tsx` — one Cue # cell sends the `ASK` PATCH; four cells and a typed `20`
  send one renumber in stack order; a decimal over several is refused; Clear sends `null` per entry;
  a Fade commit over Fade→Cue # never reaches the number; locked, a row-head click arms. Pip tests
  in each view: unlocked and locked a grey pip arms and does not select or expand; the live pip
  does nothing. `RenumberCuesButton.test.tsx` still passes on the extracted form.
- Docs: frontend `CLAUDE.md` §Sheet kit's cue sheet entry (the Cue column paragraph) and
  §"The show-editing lock" (arming unlocked).
- Local: `check:quick`.
- As built: the cell is `runner/CueNumberCell.tsx`, a `TextCell` over one cell and the renumber form
  over several, picked by `batchCount`; its write takes a typed string or `{renumber: {start, step}}`.
  Beside `RenumberForm`, `RenumberCuesButton.tsx` exports `useRenumberCues` (the call and its toast,
  shared by both doors) and `renumberStartOf`. Because a typed number's value is `''` for an auto
  number, typing the number an auto cue already shows pins it. The pip is given `onArm` wherever the
  view has `onSetStandby` and ignores it on the live and next pips itself; its press also stops
  `pointerdown`, so it starts no marquee or card drag. The played mark stays the row head's
  screen-reader *Played*: the sheet never drew a visible tick.

### Session 5 — Dragging a selection

D22–D24.

- `components/sheet/sheetModel.ts`: `moveRows`. `SheetTable.tsx`: `rowDrag.group`; `handleDragEnd`
  through `moveRows`; the group's other rows dimmed and the **+n** chip while dragging (the active
  id from `onDragStart`).
- `CueSheet.tsx` and `StackTimeline.tsx`: pass `group` from `selectedRows`.
- Tests: `moveRows` unit tests (up, down, a group with gaps, a drop inside the group, a separator in
  the group, a single row unchanged from today); `CueSheet.test.tsx` — three selected rows dragged
  send one reorder with all three moved and the selection kept; an unselected row's drag moves it
  alone. The existing `StackTable` drag tests unchanged.
- Docs: frontend `CLAUDE.md` §Sheet kit (`rowDrag`) and the cue sheet entry.
- Local: `check:quick`.
- As built: `moveRows` takes the dragged row too, `moveRows(rowIds, group, activeId, overId)`, since
  up and down are its move, which a group with gaps cannot say without it; it answers null where
  nothing moves. The hosts' group is `sheetModel.ts`'s `dragGroupOf(selectedRows, id)`. The kit
  never selects a separator (`isSelectableRow`), so in practice no separator joins a group;
  `moveRows` carries one all the same. There was no drop line to keep: the landing line is new, drawn
  only for a group, and a single-row drag draws and re-renders as before. The **+n** chip is centred
  on the row head's right edge, clear of the pip. A cell selection's rows are the group on the Table
  too, since they are the kit's `selectedRows` and the rows its bar counts. Both views' tests play
  dnd-kit's events into the sheet's handlers, since a jsdom row has no geometry for a sensor to drag
  across.

## 6. Operator checks

One row in `manual-validation.md` per merged session, at the desk: a duplicated follow cue firing
its follower; a marquee down the bottom of a long stack with the pane on; four numbers renumbered
from the cells; a cue armed from its pip while unlocked mid-show; a tech run with *In production*
off showing no lock and no amber, then switched on mid-run locking; a block of three cues dragged.

## 7. Explicitly out of scope

- Placing a duplicate after its source, and sharing its FX runs (calls 1, 2).
- Decimal renumbers (call 5): a change to `routes/cueNumbering.kt`'s renumber of its own.
- A sticky Cue # column (call 4).
- Cue # cells on the Timeline, whose cue column keeps its number read-out.
- A block-high landing gap while dragging (D24).
- Multi-selection on the Cards view.
