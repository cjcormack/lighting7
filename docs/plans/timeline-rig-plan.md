# Timeline rig columns: fixtures and groups in the Timeline, copy and paste, and staying put

> **Document status: PLANNED, 2026-11-28.** Written from the design page Chris and Claude went
> through that day, with his answers to its questions.
> Five sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). The page and the record are in
> [`timeline-rig-design/INDEX.md`](timeline-rig-design/INDEX.md).
>
> This document is the engineering half. Where it and the page disagree, this plan wins on
> behaviour and the page on layout and copy.

## 1. Context

Chris's brief, 2026-11-28: *Edit in programmer* should not navigate when another window already
shows the programmer; the Timeline should take columns for fixtures and groups, one summary cell
each and optionally a cell per head, so a fixture's state in a cue can be seen, dragged to the next
or previous cue, copied and pasted (onto the same fixture type) and edited; the existing cells
should copy and paste too; and the tab should get a UI/UX review.

What the code says, read at `e03b8e9`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **One place navigates.** `CueActions` (`F/components/runner/CueActions.tsx`) holds a local
  `include(row, then)` that Includes and then `navigate`s to the programmer; every cue host draws it
  (the Show's cards, Table and Timeline, the phone runner, the busk Show tab, the Prompt Book, the
  Cues table). `useInclude` (`F/components/programmer/useInclude.ts`) is the API half and never
  navigates. `useOpenInFxEditor` (`F/components/fxEditor/openInFxEditor.ts`) is the only other door
  that navigates to the programmer from elsewhere.
- **The programmer is the desk's.** Its values, layers, include target and Blind are broadcast, so a
  second window on the programmer shows an Include at once. Two things are per tab:
  `publishIncludeSelection` (`F/store/includeSelection.ts`), which selects the Included heads in the
  tab that pressed, and the *← Show* link (`F/lib/showInclude.ts`, sessionStorage).
- **The window registry knows each window's view.** `DeskWindow` (`F/api/windowsApi.ts`) carries
  `id`, `name`, `view` (the route pathname), `user` and `follows`; `windowViewOf(view)?.id ===
  'programmer'` and `projectIdOfPath(view)` (`F/lib/windowViews.ts`) say what a window shows.
  `useCoPagedWindowNames` (`F/store/windows.ts`) is the pattern for "other windows on view X".
- **The Timeline has no fixture in it.** `GET …/cue-stacks/{sid}/timeline`
  (`K/routes/cueStackTimeline.kt`) answers layers, ad-hoc FX and scenery; a cue's own rows are left
  out but for palette references, which come back as pseudo-layers. The only fixture-keyed data is
  each layer's and FX's `heads`. `FU-TIMELINE-VALUES` records the gap.
- **A cue's state per head is already cooked on the desk.** `CueComposer` flattens a cue's layers and
  own rows to one contributor per (fixture, attribute); `buildCueAssignmentsForCue`
  (`K/fx/CueApply.kt`) expands group rows and resolves `pal:` rows per head; a fixture's own row
  beats its group's row and every layer in the same cue. `stackMoveInDarkPlan`
  (`K/routes/cueStackMoveInDark.kt`) already cooks every cue of a stack against the loaded rig and
  answers `rigLoaded: false` off the current project.
- **Edits are one route.** `POST …/timeline/edit` (`K/routes/cueStackTimelineEdit.kt`) runs a batch
  of ops in one transaction and answers `before`; its `restore` op already carries
  `attributeAssignments`.
- **Two Undos.** The Timeline keeps a module-level stack of `before` snapshots and claims ⌘Z
  (`F/components/runner/TimelineEditing.tsx`); the desk's History journals the same writes and has
  its own ⌘Z (`F/components/history/undoKeys.ts`, `docs/undo-engineering.md`).
- **The review** (the page's Part 4): no context menu and no clipboard; a run drags from its end
  only; *On* in an empty FX or scenery column does nothing (`onOp` returns null); *+ Column* offers
  no FX though the first plan said it would; columns move by hover chevrons only; names truncate at
  84 px with the full name in a `title`.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Edit in programmer stays put when a programmer is open.** When another window signed in as the same user shows the programmer for this project, *Edit in programmer* and *Blind edit in programmer* Include and leave this window where it is. Otherwise they navigate as today. The rule is `CueActions`', so every host gets it, and `useOpenInFxEditor` follows it. |
| D2 | **A toast says where it went**: the cue and the window's name, with *Open here*, which navigates this window as today. With several such windows it names the first in the Screens list. |
| D3 | **The heads are still selected.** A press that stays put writes the Included fixtures and groups to the desk selection, replacing its targets and keeping its family mask. A programmer window that follows the selection takes it; one that does not keeps its own selection and still shows the cue's values. The *← Show* link stays the pressing window's. |
| D4 | **Rig columns.** A fifth kind of Timeline column, after Scenery: one per fixture or group, one cell per cue, showing the state that cue lands the target in. |
| D5 | **Rig columns are stored on the stack and synced**, in the operator's order. *+ Column* adds one by search; *+ Selection* adds the desk selection's targets. The other kinds stay derived, their order per window. |
| D6 | **The desk resolves every cell** with the cook a GO runs, per cue alone: no other cue, no programmer, no park, timed layers left out, as the cue preview does. The browser draws it. |
| D7 | **A cell is a summary**: the colour with the level as its fill, the level, and marks for position, beam and FX. Solid where the cue holds rows for exactly this target (its **own**), outlined where the state arrives from a Look, a palette layer or its group. Dark is a dash. |
| D8 | **Equal neighbours join and changes are marked.** Cells equal to the cue above draw as one bar and ◆ marks a change. Equality is the desk's, per family. Nothing starts tracking. |
| D9 | **A group is one column and a multi-head fixture opens.** Uniform members read as one fixture; mixed members draw a striped swatch and a level range. A multi-head fixture's column opens into a cell per head; whether it is open is stored with the column. |
| D10 | **The family segment narrows rig cells** to one family, and is the mask a copy carries. |
| D11 | **A paste lands the same state with the fewest own rows** (§3.3). Palette references stay references; a value from a Look or a group travels as the literal each head resolves to; dark is written per head where the destination would light it: dimmer 0 where the head or its parent has a dimmer, else its colour mix at black. |
| D12 | **A state carries FX on exactly its target**, through `fxOn`. Copied onto the same target it joins the source's run, as the FX column's copy does; copied onto a different target it takes a fresh run key. An FX that reaches the target through a group or a selection is not copied and the toast names it. |
| D13 | **A state pastes onto the same fixture type.** The same column in any cue of the project, another fixture of the same type, a head onto a head of the same type, or a group whose members are the same types in the same order. Anything else is refused by name. |
| D14 | **A rig cell drags both ways.** A handle on its top and bottom edge: down pastes into each cue crossed, up into each earlier cue. *Fill ↓* and ⌘D do the same over a range. |
| D15 | **A rig cell is edited in a popover** with the programmer's level, colour, position and beam controls. A change writes an own row for that target in that cue. Each row names where its value comes from. *Clear own rows* hands the target back to its group and the cue's Looks. *Edit in programmer* Includes the cue with the target selected, under D1. One selected rig cell turns the pane to the cue's Values tab, filtered to the target. |
| D16 | **Every cell copies and pastes** (§3.5). ⌘C, ⌘X, ⌘V; a cut is a move. The clipboard names cells, not values, and is shared by the desk's windows on one machine, across stacks. |
| D17 | **A context menu** on a cell (right-click or a long press) carries every verb the bar has for it. |
| D18 | **A run drags from its top too**, filling earlier cues. Dragging inward still empties. |
| D19 | ***On* in an empty FX or scenery column opens a picker**, and *+ Column* offers FX. |
| D20 | **A header menu** replaces the hover chevrons: Move left, Move right, Hide, Select column, Open its Look or palette, Remove (a rig column or an empty added one). Headers drag to reorder within their kind. |
| D21 | **Two densities per window**, *Compact* (today's widths) and *Wide*, and a header that wraps to two lines. |
| D22 | ***Changes only*** dims every cell equal to the cue above, in every kind. |
| D23 | **The Timeline's Undo is the desk's.** ⌘Z and a toast's *Undo* undo the gesture's History entry; redo works and a reload keeps it. Each `timeline/edit` request is its own entry, never merged with another's. The Timeline's own stack goes. |

The plan adds its own:

- **P1. One PR per session**, each crossing the wire with its client (CLAUDE.md §"Git workflow").
- **P2. Nothing reaches the stage.** Every new op is a stored write through `timeline/edit`: one
  transaction, all or nothing, a held cue marked *Edited since GO* where its stage content changed.
- **P3. The browser holds no cook.** A cell's state, its sources, its equality with the cell above
  and whether a paste is allowed are the desk's answers. A guard test holds the rig read to the cue
  preview's channels for a cue composed alone.
- **P4. A copy is by reference.** The clipboard holds `{projectId, stackId, cueId, column}` per
  cell, and a paste is an op naming the source cue, resolved on the desk when it lands. A source
  that has gone is refused by name. A cut is a move: ⌘X marks the cells and leaves them in place,
  and the paste turns the source off in its own batch (§3.5).
- **P5. A source cue may be outside the stack.** `timeline/edit` keeps "every cue written must have
  an entry in this stack"; an op's *source* cue need only be in the project.
- **P6. Until session 5, new ops answer `before`** as the existing ones do, so the Timeline's own
  Undo keeps working over them. Session 5 moves all of it at once. Adding, removing and reordering
  a rig column is not on that Undo: until session 5 the History sheet undoes it, and from session 5
  ⌘Z does.
- **P7. A rig column is an enrichment, never a guard.** Deleting its fixture, group or stack sweeps
  it in the same transaction, as a busk rig tile is swept (`K/models/buskRig.kt`). It never stops a
  show hiding the venue record it names (§3.1).

## 3. The model

### 3.1 Stored columns (D5, D9, P7)

`cue_stack_rig_columns`: `uuid`, the stack, exactly one of a patch or a group, `show_heads`,
`sort_order`. No `ReferenceOption`, swept by hand like `busk_rig_tiles`. A column naming a venue's
patch or group in a show is a `VenueLinks` link, so Update, Detach, Attach and Extract carry it.
It is exempt from `ShowHides.holdersOf`, as a patch's group membership is, so the show can still
hide that record. The stored column stays. It draws no cells and says the fixture or group is
hidden in this show, until the record is brought back or the column removed.

`GET/PUT …/cue-stacks/{sid}/timeline/rig-columns` is the whole list on every write, in order:
ids minted and answered, a target twice or a target the project's rig lacks refused as a whole
(`TIMELINE_RIG_INVALID`, every problem at once). A column already stored on a record the show has
since hidden is kept. A stored show write either role may make. It fires
`cueStackListChanged`.

Portable, so it follows CLAUDE.md §"Database changes and cloud sync": carried on the stack's record
(`cueStacks/`) as an ordered list by target uuid, each column with its own uuid as a busk rig tile
carries its own, so History and an import keep them. It takes the next `formatVersion` after the
one on `main` when session 2 runs (56 at the time of writing), and with it `SyncDispositions`,
`RichProjectFixture` with a non-default `show_heads`, the round-trip and clone tests and
`docs/sync-engineering.md`. An archive whose column
names a record it lacks reads the column as absent, with a warning. The reconciliation adds the
table; no migration step.

### 3.2 The read (D6–D9, P3)

`GET …/cue-stacks/{sid}/timeline/rig`, beside the timeline read. Per stored column: its target,
name, fixture type (key and name), heads, and for a group its members in the show's order
(`RigScope.members`). Per cue entry and column, a cell:

- per family (Intensity, Colour, Position, Beam): the value to draw (a level; a colour or a wheel
  slot's name and preview; a position's palette name where it is a reference, else that it is set;
  the beam attributes set), its **source** (`OWN`, the group's row, a Look or palette layer by name,
  a `pal:` reference by name) and a signature the client compares for D8;
- the FX reaching the target, each marked exact-target or not;
- for a group, uniform or the members' spread; with `show_heads`, a cell per head.

Cooked by `CueComposer` and `buildCueAssignmentsForCue` for that cue alone, batched over the stack
as `stackMoveInDarkPlan` batches. Off the current project it answers `rigLoaded: false` and no
cells, and the columns draw as headers with a line saying why. The client refetches on the
timeline's tags and on the patch and group lists.

### 3.3 Copying a state (D11–D13)

`stateCopy {from: {cueId, target}, to: {cueId, target}, families?}` in `timeline/edit`. For the
families named (all, by default), on the destination target and what it contains (a group's
members, a fixture's heads):

1. The destination's own rows for those families are dropped, and what the destination then lands
   is cooked.
2. For each head and attribute where that differs from the source's cooked state, an own row is
   written: the source's `pal:` reference where its value is one, else the literal. One row on the
   target where every head it contains takes the same value, else a row per head that differs.
3. A head dark in the source and lit in the destination is written dark: dimmer 0 where it or its
   parent has a dimmer, else its colour mix at black, the levels `K/fx/HeadIntensity.kt` reads as
   intensity. A family the source sets
   nothing for and the destination still lands from a Look or a group is left, and answered in
   `kept`.
4. Ad-hoc FX on exactly the target: the destination's are removed and the source's copied as
   `fxOn` copies them. Onto the same target a copy joins the source's run (run keys under the first
   plan's P3); onto a different target it takes a fresh run key, since two FX of one cue may not
   share one. FX reaching the target any other way are answered in `notCopied`.

The answer gains `states: [{op, written, removed, kept, notCopied}]` for the toast. `dryRun` is the
drag's ghost and the paste's check. Refusals are `TIMELINE_EDIT_INVALID` problems: a type mismatch
(D13), a source cue or target that is gone, a destination cue outside the stack.

### 3.4 Editing a state (D15)

`stateSet {cueId, target, attribute, value}` writes or replaces the target's own row, checked as a
cue row is checked (a `pal:` reference included, `PALETTE_REFERENCE_INVALID`).
`stateClear {cueId, target, families?}` removes the target's own rows. The popover's controls are
the programmer's value pop-ups, committing once per gesture.

### 3.5 Copy and paste for the other kinds (D16)

| Copied | Pasted into | Sends |
|---|---|---|
| Look or palette cell | the same column, another cue | `layerOn` with `settingsFrom` the copied layer |
| | another column of the same kind | `layerSettings` with the copied layer's fields, after a `layerOn` where the cell is empty |
| FX cell | the same column | `fxOn` from the copied row |
| | the same FX and attribute on another target | `fxOn` with a new `target` and a fresh run key, refused where the target lacks the attribute |
| Scenery cell | the same element, or one of the same kind | `scenerySet` with the copied state and transition |
| A block | a cell in another cue | each column's cells the same number of cue rows down, markers skipped, as one batch |

`settingsFrom` and `fromFxUuid` take a layer or FX row of any cue in the project (P5). A paste
whose op list is empty is disabled.

A cut is a move. ⌘X marks the cells as cut and leaves them in place, drawn dashed as copied cells
are. The paste sends its paste ops and then each source cell's off (`layerOff`, `fxOff`,
`sceneryClear`, or `stateClear` for a rig cell) in the same batch: one request and one Undo. Esc
or a new copy cancels the cut.

### 3.6 Undo (D23)

`timeline/edit` answers its request id as `X-History-Entry`. The client finds the History entry
whose `requestIds` holds that id, and the toast's *Undo* sends `POST …/history/undo` with that
entry's id (`e-…`). History merges one source's writes to the same records within 1.5 s, which
would put two gestures in one entry, so session 5 makes each `timeline/edit` request its own
entry on the desk. ⌘Z is no longer claimed by the Timeline and falls to the desk's.
`restore` stays an op of the route, with no client caller. A held cue's *Edited since GO* after an
entry undo is checked in session 5 and recorded here.

## 4. UX

The page is the reference for layout and copy. In short:

- **Row B** gains a *Rig* kind chip, *+ Selection* beside *+ Column*, *Changes only*, and the
  density choice. *Selection only* keeps a rig column whose target meets the selection.
- **A rig cell** selects as any cell does. A second click on the one selected cell opens the
  popover (D15). It carries the two handles (D14) on hover, never while locked.
- **The bar** gains *Copy*, *Paste* and, over rig cells, *Clear own rows*; *On*, *Off* and
  *Replace with…* do not apply to rig cells and are disabled over them.
- **Toasts** name what a paste wrote and what it left: *Spot 2 in Q5: 3 rows written. Not copied:
  Wave over Spots.*
- **Locked**, every new write asks to unlock as the existing ones do; copy still works.
- **Still from 1100 px.**

## 5. Implementation — five sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names written,
and its row marked *in review* with its branch. A session's PR is titled *Timeline rig columns:
session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Edit in programmer stays put | planned | | | |
| 2 | Rig columns, read-only | planned | | | |
| 3 | Copying a state | planned | | | |
| 4 | Editing a state, and copy for every cell | planned | | | |
| 5 | The review items and one Undo | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Edit in programmer stays put

- **D1–D3**, client only: a `useProgrammerElsewhere(projectId)` beside `useCoPagedWindowNames`
  (another row, same `user`, on the programmer for this project); `CueActions`' `include` staying
  put on it, for both *Edit in programmer* and *Blind edit*; the toast and *Open here*; the Included
  targets written to the desk selection when staying; `useOpenInFxEditor` under the same rule.
- **Tests:** per host kind that the press navigates with no such window and stays with one; a
  window of another user, or on another project, does not count; Blind edit still waits for Blind;
  the toast's *Open here* navigates; the desk selection is written only when staying.
- **Docs:** `frontend/CLAUDE.md` §"The cue action set", `docs/desk-screens.md`.
- **Checks:** `npm run check:quick`.

### Session 2 — Rig columns, read-only

- **§3.1:** the table, its route, the sweeps on a patch, group and stack delete, `VenueLinks`, a
  hide of a venue record a column names, the sync field and bump.
- **§3.2:** the read and its DTOs, the guard test of P3.
- **Client (D4–D10):** the `rig` kind in `lib/stackTimeline/`, the cells and their joins and ◆, the
  head cells, group cells, *+ Column* over fixtures and groups, *+ Selection*, the *Rig* chip, the
  family segment on rig cells, *Selection only*, the header's remove and reorder writing the stored
  list, the off-project state.
- **Tests:** the route's refusals and sweeps; a venue patch with a column hidden in a show, the
  column kept and drawing no cells; a column write undone from History with its uuid kept; the round trip, the clone and a show on a venue
  through Update and Detach; the read over a stack with a group row under a fixture row, a Look, a
  `pal:` row on a mixed group, a multi-head fixture and an FX over a group; the derivation; a column
  added, reordered and removed in the grid.
- **Docs:** `docs/cue-stacks-engineering.md` §"Timeline", `docs/sync-engineering.md`, CLAUDE.md's
  Cue Stack Run Endpoints, `frontend/CLAUDE.md` §"The Timeline". Close `FU-TIMELINE-VALUES` in `docs/plans/followups.md`:
  its index row and section go, for a one-line row in Completed (CLAUDE.md §"Follow-ups").
- **Checks:** the full suites locally: a table and a `formatVersion` bump. Read `followups.md`'s
  index first for the gate on a new consumer of fixture and group attribute lookup.

### Session 3 — Copying a state

- **§3.3:** `stateCopy`, its answer and refusals, P4 and P5.
- **Client (D11–D14, part of D16):** the clipboard (localStorage, read defensively), ⌘C and ⌘V and
  the bar's *Copy* and *Paste* over rig cells, the two handles with `dryRun` ghosts, *Fill ↓* and ⌘D
  over rig cells, the family mask, the toast.
- **Tests:** each row of the page's paste table as a case; two cues layering one Look paste to no
  rows; a reference stays a reference; a group paste writing one group row or member rows; the
  type refusals; a state pasted onto another fixture giving its FX a fresh run key; a head with no
  dimmer pasted dark; a paste from another stack; a batch that fails on its last op rolling back; a live
  cue marked edited and nothing sent; one request per gesture.
- **Docs:** `docs/cue-stacks-engineering.md` §"Timeline", CLAUDE.md's `timeline/edit` entry,
  `frontend/CLAUDE.md`.
- **Checks:** `./gradlew test --tests` for `routes` and `fx`; `npm run check:quick`.

### Session 4 — Editing a state, and copy for every cell

- **§3.4:** `stateSet`, `stateClear`; the popover, its sources and ways out; the pane's Values tab
  on one selected rig cell (D15).
- **§3.5:** `fxOn`'s `target`, the project-wide `settingsFrom` and `fromFxUuid`, and copy, cut and
  paste for Look, palette, FX and scenery cells and for a block (D16).
- **Tests:** a set over a group row landing on the one fixture; a cleared cell landing its group's
  value again; each row of §3.5's table; an FX pasted onto another target taking a fresh run key; a
  cut pasted as one request that turns its source off, and cancelled by Esc and by a new copy; a block pasted across a marker; a refused paste naming
  every cell; *Edit in programmer* from the popover selecting the target, staying put under D1.
- **Docs:** as session 3.
- **Checks:** as session 3.

### Session 5 — The review items and one Undo

- **D17–D22**, client: the context menu, the top handle, the FX and scenery pickers and FX in
  *+ Column*, the header menu and drag in place of the chevrons, densities, *Changes only*.
- **D23, §3.6:** on the desk, each `timeline/edit` request its own History entry (`K/history`);
  the toast's *Undo* finding its entry by request id, ⌘Z through History, the Timeline's stack and
  its `useTimelineCommandKeys` claim on ⌘Z removed, redo.
- **Tests:** the menu's verbs match the bar's for each kind; a top-handle drag; a picker opening
  where *On* did nothing; a column moved by menu and by drag; *Changes only* over each kind; two edits of
  one cell inside 1.5 s making two entries, *Undo* taking back only the second; an edit
  undone and redone through History, after a reload, and from another window.
- **Docs:** `frontend/CLAUDE.md` §"The Timeline", `docs/undo-engineering.md`,
  `docs/cue-stacks-engineering.md`. Add §8's follow-ups.
- **Checks:** `npm run check:quick`; `./gradlew test --tests` for `history` and `routes`.

## 6. Migration

One new table, created by the reconciliation, and one `formatVersion` bump (§3.1). No one-shot
step, so `SCHEMA_USER_VERSION` does not move. An older desk refuses a newer archive, as every bump
does.

## 7. Explicitly out of scope

- Tracking for lighting. A rig column reads like a track sheet and stores nothing new per cue.
- A paste between different fixture types, or from a fixture onto a group.
- Splitting a group's FX to carry one head's share (the page's Q2: declined).
- A cue's events and speed masters as columns.
- A tablet or phone Timeline.
- MCP tools for the Timeline (`FU-TIMELINE-MCP` stands; its trigger is unchanged).
- A stored order or stored columns for the derived kinds.

## 8. Follow-ups

Session 2 closes `FU-TIMELINE-VALUES`. Session 5 adds, in
[`followups.md`](followups.md):

- `FU-TIMELINE-BAND-COLLAPSE`: collapse a kind's band to one count column. Trigger: a stack whose
  Timeline is wider than two screens.
- `FU-TIMELINE-INLINE-TIMING`: edit Fade and Follow in place. Trigger: an operator leaves the
  Timeline for the Table to change a fade.
- `FU-TIMELINE-STATE-CROSS-TYPE`: paste a state between fixture types by family. Trigger: an
  operator asks to copy a colour from a par to a spot in the Timeline.

No session adds a WS frame or command.

## 9. Verification

At the desk, on a show with a stack of a dozen cues:

- **Session 1:** with the programmer on a second screen, *Edit in programmer* on the Timeline
  leaves the Timeline up and the second screen shows the cue with its heads selected. With that
  screen on the Stage view, it navigates as before.
- **Session 2:** add two spots and a group as columns and check three cues' cells against the cue
  editor. Open the columns on a second desk after a sync.
- **Session 3:** drag a spot's state from Q5 to Q6 and Q7, paste it onto the other spot in Q9, and
  undo each. GO through them and see the stored state on stage.
- **Session 4:** change a spot's colour from its cell and see the cue editor's row; copy a Look cell
  to a cue six rows away.
- **Session 5:** undo a Timeline edit from the History sheet, and redo it with ⇧⌘Z.
