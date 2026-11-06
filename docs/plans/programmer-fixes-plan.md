# Programmer fixes

> **Document status: PLANNED. Design agreed 2026-11-06 (§3), corrected in review the same day
> (§4).** No session started.

The design record is [`programmer-fixes-design/INDEX.md`](programmer-fixes-design/INDEX.md), and its
one-page copy [`programmer-fixes.html`](programmer-fixes-design/programmer-fixes.html) holds the
mock-ups. Where the two differ, this plan wins.

## 1. Context

Four things get in the way on the programmer, all reported on 2026-11-06:

1. **Cells that are not next to each other cannot be selected together.** A click on a cell always
   replaces the selection. Every cell's trigger passes its click event to `onBeginEdit`, but the
   hosts drop it: `components/fixtures-list/FixturesTable.tsx` line 1153 and
   `components/sheet/SheetTable.tsx` line 930 call `onBeginCellEdit(row, col)` without the event,
   and the three handlers (`FixturesListContainer.tsx`'s `handleBeginCellEdit` (line 660),
   `components/sheet/useSheet.ts`'s (line 484) and `components/cues/editor/CueValuesGrid.tsx`'s
   (line 333)) select `[cell]` with `'replace'`. A ⌘-drag already accumulates (`useCellMarquee`
   reads the modifiers, `applyCellSelection` unions), and a row's name already takes ⌘ / ⇧ on every
   host. The cue Timeline is a fourth door with rules of its own: `runner/StackTimeline.tsx`'s
   `clickCellRef` (declared at line 378, called from each cell at line 449, its handler at line 579) already draws a ⇧-click rectangle, from the stored
   cursor's anchor or else the first selected cell, and a click on the one selected cell toggles its
   layer (`toggleOps`) rather than selecting. The request asks for this on every table with a
   multi-selection.
2. **Element rows expand again on the way back to the programmer.** Expansion is `useState` in
   `FixturesListContainer.tsx` (lines 326–327), so it is lost on unmount, and on remount the grid
   **replays the last Include or select request**: `store/includeSelection.ts` keeps it in a
   module-level store that never marks a request spent, and the container's guard, `appliedIncludeNonceRef`, is
   reset to 0 on every unmount (lines 1240–1248). The effect at line 1249 then opens every parent
   fixture and group again, and also re-selects those heads, clears the filter, turns Lit off and
   scrolls. `components/fixtures-list/programmerSelectRequest.ts` (`selectWindowHeads`) publishes
   through the same store. There is no expand / collapse all anywhere.
3. **The value pop-ups show no recent values.** Nothing keeps any: the only recency is a palette's
   `lastPressedAt` (`lib/paletteRecents.ts`, the Palettes band's order). Three colour pickers exist:
   `components/editor/ColourEditor.tsx` (the programmer's colour cell, the cue Values grid, the
   fixture sheet, busk Colour, the Fan), `components/fx/ColourEditorBody.tsx` (FX colour parameters,
   under `FxColourPicker` and `FxColourListPicker`) and
   `components/templates/familyControls/ColourControl.tsx` (the palette editor). The level and
   position cells (`fixtures-list/cells/SliderCell.tsx`, `PositionCell.tsx`) and warmth
   (`WarmthCell.tsx` over `editor/WarmthEditor.tsx`) are the other value pop-ups.
4. **A colour FX's picker sometimes closes mid-drag**, seen from the programmer's row C **FX**
   button (Chris, 2026-11-06). Not reproduced: a real-Chrome harness and jsdom both kept a nested
   Radix popover open through a `react-colorful` drag, released anywhere. **The cause is not
   known.** What the code shows is that the picker's lifetime follows the selection's **visible**
   rows: row C's actions render only while `locateTargets` (`rows ∩ selectedRowIds`, line 548) is
   non-empty (line 1606, on purpose: a filter that hides every selected row must not leave a live
   toolbar), `ProgrammerAddFxButton.tsx` closes the picker when its offer turns disabled (line 20),
   and `ProgrammerAddFxSheet` returns null then (`ProgrammerAddFx.tsx` line 99). While the window
   follows the desk, the offer's pair is the desk's selection (`useWindowSelectionTargets`), so
   another window, a surface or the bridge moving it closes the picker too. One way the visible
   rows empty without the operator is *Lit* (`usePersistentState('fixturesList.onlyLit')`, per
   browser): `useLitFixtureKeys` judges a fixture by its **dimmer channels only**, so a dimmer FX or
   fade through 0 on a selected fixture takes its row away. A colour drag never moves a dimmer,
   though, so this is not the reported gesture. Two defects do sit on the reported path. Colour
   Cycle's list picker sends every change as a release (`FxFields.tsx` line 1812,
   `onChange(v, true)`), skipping `useLivePush`'s 50 ms floor, so each mousemove is an `updateFx`,
   an `fxChanged` and a refetch of the active FX; one colour (`FxColourPicker`, line 1772) sends no
   release at all. And `FxColourListPicker.tsx`'s `[value]` effect (line 72) re-mints the swatch ids
   and runs `setEditingIndex(null)` whenever the value is not the one it last emitted, which closes
   the open swatch's popover. In the add-FX picker that value is `FxEditor`'s local draft
   (`FxFields.tsx` line 240), which a desk echo never rewrites, so the reset fires only on a revert,
   a type swap, a reset to palette or an emptied list falling back to its default; D24 guards it
   anyway, since a host that does pass the desk's value through would close the swatch on a drag.

## 2. Decisions

### Non-contiguous cells

- **D1 — A cell click takes the row click's modifiers** (`listSelectionIntentFor`): plain replaces
  with that cell, as now; **⌘ / Ctrl toggles that one cell** in or out; **⇧ selects the rectangle**
  from the cursor's anchor to the clicked cell, replacing the selection; **⌘⇧ adds that rectangle**.
  With no stored cursor (after a marquee) the anchor is derived as the arrows derive it,
  `cursorOfSelection`'s first cell in reading order; with no selection at all, or an anchor no
  longer on the grid (its row collapsed or filtered away), ⇧ and ⌘⇧ act as a plain and a ⌘ click.
  A second click on the **same cell** within a double-click (`event.detail > 1` and the previous
  click's cell) never toggles: it leaves the cell selected, adding it back if the first click took
  it out, so a ⌘ double-click opens the editor on that cell, selected. A modified click (or an Add
  tap, D7) on a blank cell does nothing, rather than clearing the selection being built: both
  tables draw a blank cell with `onEmptyCellClick` (`FixturesTable.tsx` line 988, `SheetTable.tsx`
  line 900), which today calls the host's background clear with no event, so the handler takes the
  event and skips the clear when a modifier is held or the latch is on. A plain click on a blank
  cell still clears.
- **D2 — Two pure operations in `components/sheet/cellSelectionModel.ts`**: `toggleCell(state,
  cell)` (removes a selected cell, adds an unselected one; the marquee's `'toggle'` still only
  accumulates) and `cellRectangle(grid, anchor, head)`, the rectangle `cellArrowStep` builds for a
  ⇧-arrow, extracted so the click and the arrow cannot disagree. A rectangle covers cells a row does
  not take, as the marquee's and the ⇧-arrow's do.
- **D3 — A click sets the cursor.** `CellSelection` gains `click(cell, intent, grid)`: plain and ⌘
  set anchor and head to the cell; ⇧ and ⌘⇧ keep the anchor and move the head. So the arrows step
  from the last cell clicked, and ⇧-arrows grow from the click's anchor, replacing the selection
  with that rectangle, as a ⇧-arrow does today, so a ⇧-arrow after ⌘-clicks keeps no gaps. A
  ⌘-click that removes a cell still moves the cursor to it, unless it empties the selection, which
  drops the cursor too; `clear()` drops a cursor left with no keys rather than bailing. `select`
  (the marquee's door) still drops the cursor.
- **D4 — Four doors, one rule.** The event is threaded from the triggers through
  `onBeginCellEdit(row, col, event)` in `FixturesTable` and `SheetTable`. The prop is typed
  `() => void` today on both, on `sheet/sheetModel.ts`'s cell props and on each cell component, so
  the type changes with it. Each host's handler calls `click`: `useSheet` (every kit sheet:
  the patch list, the cue sheet, the libraries, the DMX sheet), `FixturesListContainer`
  (Programmer, Fixtures, Groups), `CueValuesGrid`, and the Timeline's `clickCellRef`. A
  keyboard-activated trigger (Enter / Space on a focused cell, no pointer modifiers) stays a plain
  click.
- **D4a — The Timeline owns its modified click.** Its `clickCellRef` handler takes the event in
  place of its `extend` flag and calls `click` itself for ⌘, ⇧ and ⌘⇧, never forwarding a modified
  click to its `select` argument (`useSheet`'s handler, which would toggle a second time). ⇧ and
  ⌘⇧ take D1's rectangle and derived anchor, today's rule but after a ⌘-union, where today's
  fallback anchor is the first cell added (`cellSelection.cells[0]`, insertion order) and D1's is
  the first in reading order. ⌘ never reaches the sole-cell layer toggle. A plain click keeps
  today's rule: `select`, or on the one selected cell toggle its layer.
- **D5 — Rows and cells stay exclusive.** A ⌘- or ⇧-click on a cell while rows are selected clears
  the rows first, as a plain cell click does today, then applies to an empty cell selection.
- **D6 — ⌘-drag keeps adding** even when it starts on a selected cell; there is no subtracting drag.
- **D7 — The Add latch for touch.** Both selection bars, the kit's (`components/sheet/SelectionBar.tsx`)
  and the programmer's row C (`components/programmer/SelectionBar.tsx`), draw an **Add** toggle
  (`aria-pressed`, a ＋ glyph) where `(pointer: coarse)` matches, and never for a mouse. While it is
  on, a tap on a cell is a ⌘-click, a tap on a row's name is a ⌘-click on the row, and a held drag
  accumulates. It is the host's state, not stored, and it turns off when the selection clears and on
  Escape.
- **D8 — Nothing downstream assumes a rectangle.** The write path is per column already
  (`cellsByColumn`, `nextColumnTargets`), so Set, a typed value, ⌫ and Spread reach exactly the
  selected cells; Spread runs over a column's selected heads in row order and skips the gaps. The
  editor anchors at the selection's first cell in reading order (`cursorOfSelection`). The desk
  selection stays heads × the union of the cells' families (`useDeskSelectionBridge`'s
  `cellFamilies`, the ⌘-union rule), so a palette press after a selection with gaps can reach a head
  in a family whose cell was not selected. That is today's behaviour after a ⌘-drag of two blocks.

### Element rows

- **D9 — Expansion is per window, per list, per project.** `lib/listExpansion.ts`: one
  `sessionStorage` entry per project and `selectionScope` (`programmer`, `fixtures`, `groups`),
  `fixturesList.expansion.<projectId>.<scope>`, holding `{groups: string[], fixtures: string[]}`
  (group names and fixture keys, the sets `buildRows` already takes), so a show's same-named groups
  and keys never open another show's rows. `FixturesListContainer` reads and writes it in place of
  its two `useState`s. A key whose group or fixture has gone is ignored on read and dropped on the
  next write, once the rig has loaded, so the entry never outgrows the rig. It survives navigation
  and a reload, as the cue pane's toggle does.
- **D10 — A select request lands once.** `store/includeSelection.ts` records which nonce has been
  **spent**, at module scope: a grid that lands a request spends it, and a grid mounting later finds
  nothing to land. A request carries its project id and when it was published; one published while
  no grid is mounted is landed by the next grid of **that project** to mount within 5 seconds
  (Include from ⌘K, then the route change; the FX tray's *All*, then the editor), and is otherwise
  spent unlanded, so it never replays later or in another show. StrictMode's immediate remount must still land it, since
  the unmount clears the selection: the spend runs a task after the land and the effect's cleanup
  cancels it. The store stamps the current project and the time when a request is published, so
  neither caller (`programmerSelectRequest.ts`, `programmer/useInclude.ts`) changes.
- **D11 — Gestures that select elements still open their rows**: an Include onto an element,
  `landTargets` (the heads chip's subselect, Select by colour) and the deep-link land. They add to
  the stored sets, so what they opened stays open.
- **D12 — Expand all / Collapse all.** One button, a new `expand` part of the container's
  `renderToolbar` parts beside `lit`: the plain lists' own toolbar (filter, spacer, Lit, Columns)
  draws it after Lit, and the programmer places it on row B beside Groups. It reads
  *Expand all* (and its glyph) while any group or multi-head fixture among the rows the filter shows
  is closed, else *Collapse all*. Expand opens every group and multi-head fixture shown; collapse
  closes every one but those that hold the selection (a row or a cell): a fixture whose element
  rows do, and in grouped mode a group whose member rows, or their element rows, do. So a
  collapse never empties the selection. **⌥-click on a chevron** does the same over that row's level: a
  group's chevron every group, a fixture's every multi-head fixture. A plain chevron click, → and ←
  are unchanged, and a plain collapse of one fixture still drops its element cells from view.
- **D13 — The cue editor's Values view** (`components/cues/editor/CueValuesView.tsx`) gets the same
  button and ⌥-click over its own `expandedGroups` / `expandToggled`. Its rule that a fixture opens
  when the cue holds one of its elements stays, and nothing there is stored.

### Recent values

- **D14 — The desk keeps the lists.** `state/RecentValues.kt`, `RecentValuesService`: in memory, a
  map per project id of list key → values, newest first, at most **12**, no repeats (a value
  already listed moves to the front). Runtime only (no table, no sync, no `SyncDispositions` row),
  kept for every project while the desk runs, so switching shows and back keeps each show's lists,
  and cleared by a restart.
- **D15 — The keys and their values.** Each is recorded from the editor's commit and replayed
  through the editor's own typed path, so a tap lands as typing the value would.
  - `colour`: the programmer's colour literal, lowercase `#rrggbb` with a `;wNNN` / `;aNNN` /
    `;uvNNN` tag for **every emitter the editor holds, zero included** (`;w0`), so a white set to 0
    replays as 0 rather than as "leave white". `serializeExtendedColour` drops zeros and
    `rgbToHex` writes uppercase, so the band has its own small serializer; the desk lowercases
    before it compares, so the palette editor's `#HEX` and the grid's `#hex` are one recent.
  - `level:<attribute>`: a percent, `pct:<0–100>`, one list per attribute so dimmer and zoom
    never mix. The slider cell commits a byte (`{kind: 'slider', value}`), so it records the
    percent the grid reads for that byte (`toPct`, `editor/cellPercent.ts`: a share of 255), and a
    tap replays it as a typed percent (`commitPct`, `fromPct` back to a byte), which each head
    clamps to its range as a typed percent is (`clampCommitToResolution`).
  - `palette-level:<attribute>`: the palette editor's own level list, `pct:<0–100>` as its
    `PercentControl` commits it, a share of **each head's own range** (the palette intent's
    meaning). It is kept apart from `level:<attribute>` because the same number lands on a
    different byte on a head with a capped range (Hex, Whex and UV cap their dimmer), so each list
    holds only numbers that mean the same thing where they are tapped (Chris, 2026-11-06).
  - `position`: `pos:<pan>,<tilt>`, the position editor's centre-relative pair (−128..127). The
    cell's fields commit one axis at a time (`{panCentre}` / `{tiltCentre}`), so a pair is recorded
    only when, after the commit, **neither axis is mixed** across the editor's heads; otherwise
    nothing is. A tap replays both axes.
  - `warmth`: `<kelvin>K;<0–100>`. The warmth editor commits an RGB colour (`whiteMixColour`), so
    it records the kelvin and the level as a percent it computed that colour from, and a tap
    replays them through `commit(kelvin, level)` with the percent made a byte, as `commitLevel`
    does.

  The level and position spellings are the palette intent's (`lib/paletteIntent.ts`); the colour
  and warmth ones are not, since a palette intent's colour is `#hex;policy=…` with no emitter
  parts and it has no warmth. `RecentValues.kt` checks each key against this vocabulary, with the
  `<attribute>` of a `level:` key an attribute some patched fixture in the live rig has and of a
  `palette-level:` key a palette attribute (`PaletteAttribute`), and the value against its own
  pattern per key, and drops (logs) anything else, so a client cannot grow the map; it resolves
  nothing.
- **D16 — The wire.** Outbound `recentValues.state {projectId, lists: {<key>: [value, …]}}`,
  `StateFlow`-backed and following the live show as `grandMaster.state` does
  (`plugins/GrandMasterSocket.kt`), so the subscription is the snapshot. Inbound
  `recentValues.record {key, value}`, for the live project only: a gesture of both roles on either
  listener, registered in `plugins/SocketMessages.kt` beside `GrandMasterInMessage` /
  `GrandMasterOutMessage`, with a `TraceSampling.WS_MESSAGES` row as `GESTURE`: one send per
  commit (D17), not a stream. It is not an admin operation, so `FU-AUTH-WS-PER-MESSAGE` does not
  fire. No REST, no MCP tool.
- **D17 — A gesture's end records, a frame does not.** Most of these editors commit on every drag
  frame (`SliderCell`'s and `WarmthEditor`'s sliders, `PercentControl`, `PositionControl`, the
  position pad's `onChange`, `ColourEditor`'s emitter sliders), so an editor hands each commit to the
  band's recorder (`useRecordRecent(key)`) and the recorder sends only the last one when the
  gesture ends: a slider's Radix `onValueCommit`, a pad's or picker's window release (the
  `holdPicker` pattern, `ColourEditor.tsx` line 418), and at once for a typed value, Enter, a typed
  hex or a tap on a recent (which moves it to the front). Not recorded: a palette press or *Apply as
  values* (palettes have their own recency), Pick (it reads the output), an FX swap or Revert, and a
  write from MIDI.
- **D18 — One kit band.** `components/editor/RecentValues.tsx`: a *Recent* label and one row,
  never wrapping, at most eight: swatches for a colour (the hex on the hover), chips for the rest
  (`62%`, `−20, 12`, `3200 K · 80%`). A tap sets the value as if it had been typed, through the
  host's own commit, so in a batch it lands on every selected cell and in the programmer it writes
  Local. Its store hook runs in the band, so a list's change re-renders the band alone. It draws
  nothing while its list is empty or the desk sends none (an older desk), and nothing, recording
  nothing, in an editor of a project that is not the live one (the palette editor can open any
  project's palette, and the lists are the live project's).
- **D19 — Where it sits.** Under the editor's controls and above its footer, in every form
  (popover, docked, sheet). Every host of `ColourEditor` gets it at once. Settings get none: an
  option's name belongs to its model, and the setting editor is a type-ahead list already.
- **D20 — The other pickers mount the same band.** The FX editor's `ColourEditorBody` (each colour
  of a list too), the palette editor's `ColourControl`, `PercentControl` (as
  `palette-level:<attribute>` for the palette's attribute, D15) and `PositionControl`, the level and position cells, the fixture sheet's
  level, position and warmth rows, and `WarmthEditor`. The FX editor's level parameters (Wave's
  `low` / `high`) get none.
- **D20a — The palette editor's colour.** `ColourControl` commits a palette intent
  (`#HEX;policy=…`) and holds white, amber and UV as rows of their own, so there a colour recent is
  its hex alone: a tap sets the colour row's hex and keeps its policy, and leaves the emitter rows
  as they are (setting them would force *RGB only*). It records the hex alone, `#rrggbb`, on a
  release, which its `HexColorPicker` (line 69) does not report today: it takes D23's window
  release.

### The FX picker

The reported close is not explained (§1, item 4). These remove the defects on its path and one
real way for the visible rows to vanish under an open picker, and leave a trace that names the door
if it happens again.

- **D21 — Lit holds still while something is open over the grid.** While a cell editor, the
  add-FX picker or a marquee is live, `useLitFixtureKeys` answers the set it had when the first of
  them began, and catches up when the last ends. So a dimmer wave or fade through 0 on a selected
  fixture no longer takes its row, row C and an open FX picker with it, and no row moves under a
  pointer mid-gesture. It reads no selection, so `rows` gains no dependency on it. The holds are a
  module-level count (`lib/litHold.ts`, take and release). A cell editor takes it while open, in
  `components/editor/useEditorOpen.ts`, where every cell's open state lives (the container only
  sends one-shot open and close requests); the add-FX picker takes it while open; and the
  container takes it while `marqueeDragging`. Outside a hold *Lit* behaves as now: a
  selected row that goes dark leaves the list.
- **D22 — The close rule stays, and it has two inputs.** The picker closes when its offer turns
  disabled, and row C gates on the visible selection (line 1606), so a typed filter that hides every
  selected row still disarms the toolbar. The offer's pair is the window's selection
  (`useWindowFxPair` → `useWindowSelectionTargets`, `components/fx/selectionFxTarget.ts`): while the
  window follows the desk that is **the desk's selection**, so another window, a surface or the
  bridge emptying it also closes the picker. That stays, since the picker is for the selection, but
  it is a door the trace names. `ProgrammerAddFxSheet`'s four users (row C, `AppliedFxTable`,
  `FxEditorSelection`, ⌘K's `ProgrammerAddFxDoor`) are unchanged.
- **D23 — Both FX colour pickers release once.** `ColourEditorBody` gains `onRelease`, fired by the
  pattern `ColourEditor`'s `holdPicker` uses (line 418): a capture-phase press on the picker arms
  window listeners for `pointerup`, `pointercancel`, `mouseup`, `touchend`, `touchcancel` and
  `blur`, so a drag let go outside the popover still releases. Its `onColourChange` takes the flag
  too, since a typed hex, a preset and a picker drag all reach it: the drag's frames `false`, a typed
  hex and a preset `true`. `FxColourListPicker`'s `onChange`
  gains a `release` flag: a drag's frames are `false` and its end (`onRelease`) `true`; the
  body's White, Amber and UV sliders (`ExtendedChannelSlider`, which commits on `onValueChange`
  only) send `false` while they move and `true` on Radix's `onValueCommit`; and an add, a removal,
  a reorder, a typed hex and a preset are each `true`. `FxFields`' `colourlist` arm passes
  the flag through in place of its constant `true`; the `colour` arm (`FxColourPicker`, whose
  `onChange` sends no release today) takes the same flag, a typed hex, a preset and a palette
  picked from `ColourEditorBody`'s palette row (`onPickPalette`) `true`. So
  `useLivePush` lands the last move on release for both, and session 5 can record on it.
- **D24 — The list picker keeps an open swatch while it is being edited.** While a swatch's
  popover is open or a drag is live, `FxColourListPicker` keeps its ids and its open swatch
  whatever `value` arrives, a late copy of an earlier frame included, and resyncs from `value` when
  the swatch closes. A different list arriving while nothing is being edited (a revert, a type swap)
  still resets it. Defensive in the add-FX picker, whose value is the editor's own draft (§1,
  item 4).
- **D25 — An opt-in close trace.** With `localStorage['lighting7.debug.fxPickerClose'] = '1'`,
  read at each close so no reload is needed, the desk's own bundle logs one `console.warn` per close
  of the add-FX picker, naming the door: `ApplyPicker`'s `onOpenChange(false)` (and what Radix
  reported: escape, outside press, focus outside), `ProgrammerAddFxButton`'s disabled close with
  why (the visible rows emptied, or the desk's selection changed and who moved it, from
  `selection.state`'s `source`), the row C actions unmounting, and Done. Each is logged on the open-to-closed transition in an effect, never in render.
  Off by default, so nothing is logged for an operator who did not ask. It is removed once §6's
  check settles the cause.

## 3. Calls

Decided by Chris on 2026-11-06 (`programmer-fixes-design/INDEX.md`):

1. **Touch builds a selection with gaps through an *Add* latch** on the selection bar, on coarse
   pointers only. Long-press toggling and mouse only were declined.
2. **Recent values live on the desk**, shared by every window and device, a runtime list per
   project. This browser's localStorage and per window were declined.
3. **Expansion persists per window, per list.** Per browser and one state for every list were
   declined.
4. **The FX picker closed from the programmer's row C → FX.**
5. **The palette editor keeps its own level recents** (`palette-level:<attribute>`, D15), apart
   from the grid's, since its percent is of each head's range and the grid's of 255. One shared
   list was declined. Asked after review, the same day.

The design page made four more calls, agreed with it: ⌘-drag never subtracts (D6); recents are
runtime only (D14); Collapse all leaves open what holds the selection (D12); the Add latch is not
drawn for a mouse (D7).

## 4. Risks

- **The FX bug is not explained.** The design page agreed on 2026-11-06 named *Lit* hiding a head
  whose colour went to 0 as the likely cause; review found *Lit* reads dimmers only, so that cannot
  be the reported gesture, and this plan was corrected the same day. D21–D24 remove the defects on
  the reported path and one real way the selection vanishes; D25's trace names the door if it still
  closes. §6's check is the proof, and a failure there becomes a follow-up rather than reopening a
  merged session.
- **The Timeline's sole-cell toggle writes a cue.** A ⌘-click that reached it would toggle a stored
  layer instead of deselecting (D4a). Session 3 pins that a ⌘-click on the one selected Timeline
  cell sends no timeline edit.
- **`toggleCell` must not become the marquee's toggle.** A ⌘-drag that removed the cells it crossed
  would un-select under the pointer as the rectangle grew. D2 keeps them two operations, and
  session 3 pins both.
- **A consumed request must still land under StrictMode** (D10), or Include in development selects
  nothing. Session 2 tests it with `<StrictMode>` around the grid.
- **The recents band is in the cell editors' popovers**, which mount per open, never per cell; its
  subscription must not be added to a row component, or every visible row subscribes.

## 5. Implementation: five sessions

Each session is one PR, run by `/next-session`, titled *Programmer fixes: session N — title*. They
run in order and stack on each other's branches while open: sessions 1, 2 and 3 all edit
`FixturesListContainer.tsx`, and sessions 2 and 3
`FixturesTable.tsx`. Session 4 crosses the wire and runs
`./gradlew compileTestKotlin` and the touched packages' tests; every session runs
`npm run check:quick` in `frontend/` (root `CLAUDE.md` §"Pre-commit checks"). None runs the full
suites locally: no Gradle change, schema, sync or cross-package refactor.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The FX picker: one release and a close trace | planned | | | |
| 2 | Element rows stay put | planned | | | |
| 3 | Cells with gaps | planned | | | |
| 4 | Recent values on the desk | planned | | | |
| 5 | Recents in every value editor | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — The FX picker: one release and a close trace

D21–D25.

- `lib/litHold.ts` and `components/fixtures-list/useLitFixtureKeys.ts` (D21); the holds taken in
  `components/editor/useEditorOpen.ts` (an open cell editor), `FixturesListContainer.tsx`
  (`marqueeDragging`) and `programmer/ProgrammerAddFxButton.tsx` (the picker's open state).
- `components/fx/ColourEditorBody.tsx` (`onRelease` and its emitter sliders' `onValueCommit`,
  D23), `components/fixtures/ExtendedChannelSlider.tsx` (an `onValueCommit` prop), `FxColourPicker.tsx`,
  `FxColourListPicker.tsx` (the `release` flag and D24), `FxFields.tsx` (the `colour` and
  `colourlist` arms).
- D25's trace: one small module (`lib/debugTrace.ts` or beside the picker) reading the flag at each
  close, called from `ApplyPicker.tsx`, `ProgrammerAddFxButton.tsx` and `FixturesListContainer.tsx`'s
  row C actions.
- Tests: `useLitFixtureKeys` (a hold keeps the set through a dimmer going to 0 and catches up on
  release; two holds release in either order); `FixturesListContainer.test.tsx` (Lit on, row C's FX
  picker open, a selected fixture's dimmer to 0: the row, row C and the picker stay, and the row
  goes once the picker closes; a typed filter hiding every selected row still removes row C's
  actions); `FxColourListPicker` / `FxFields` tests (a drag sends no release until the window's
  pointer-up, released outside the popover too; an emitter slider's drag releases once on commit;
  an add, a reorder, a removal and a typed hex each release; one colour now releases; at most one push per 50 ms; a late copy of an earlier value
  while a swatch is open keeps it open; a different list with no swatch open resets it); the trace
  (silent without the flag; with it set after load, one warn per close naming the door, including
  a desk-selection change with its source).
- Docs: frontend `CLAUDE.md` gains a short paragraph on *Lit* where the programmer's toolbar is
  described (it has none: what counts as lit, and that it holds still while something is open),
  and the trace flag beside the picker's description.
- Local: `check:quick`.

### Session 2 — Element rows stay put

D9–D13.

- `lib/listExpansion.ts` (the per-project, per-scope entries over `lib/syncStore.ts`'s
  `sessionStorageArea`, and a `useListExpansion(projectId, scope)` hook);
  `FixturesListContainer.tsx` onto it, every `setExpandedGroups` / `setExpandedFixtures` caller
  (`handleToggleExpand`, which → and ← reach through `treeKeyAction`; `landTargets`; the Include
  effect; the deep-link land) unchanged in shape.
- `store/includeSelection.ts`: the spent nonce and the deferred spend (D10); the container's
  `appliedIncludeNonceRef` and its unmount reset go.
- The `expand` part of `renderToolbar` (D12), drawn by the default chrome and placed by
  `ProgrammerGrid.tsx` on row B; ⌥-click on the chevron (`FixturesTable.tsx`'s chevron handler), in the
  container and in `CueValuesView.tsx`.
- Tests: `listExpansion` (a parse that drops junk, per-scope and per-project isolation, stale keys
  dropped on write once the rig has loaded); `FixturesListContainer.test.tsx`:
  expand a fixture, unmount, remount: still expanded; another project's same-named group stays
  closed; an Include, unmount, remount: no re-select, the filter kept; the same Include under
  `<StrictMode>` still selects; a request published while no grid is mounted lands on the next
  mount within 5 seconds, not after, and never on another project's grid; Expand all / Collapse all (the label follows the state, the filter
  bounds it, a selected element's fixture stays open, and in grouped mode a group holding a
  selected member row stays open);
  ⌥-click; `CueValuesView` the button.
- Docs: frontend `CLAUDE.md` where the Include request is described (*opens the group or fixture a
  head sits in…*, *The Values grid never unmounts*), the → / ← paragraph, and the container's
  toolbar.
- Local: `check:quick`.

### Session 3 — Cells with gaps

D1–D8, D4a.

- `components/sheet/cellSelectionModel.ts`: `toggleCell`, `cellRectangle` (and `cellArrowStep`
  over it). `useCellSelection.ts`: `click`, with D1's derived anchor and the `detail > 1` rule.
- The event through `onBeginCellEdit`: `FixturesTable.tsx` and `SheetTable.tsx`, and the
  `onBeginEdit: () => void` types on `FixturesTable.tsx`'s cell props, `sheet/sheetModel.ts` and the
  cells: `fixtures-list/cells/{Slider,Colour,Position,Setting,Warmth}Cell.tsx`, `sheet/cells/*`,
  `runner/CueNumberCell.tsx`, `templates/PaletteValueCell.tsx` and `patches/GelCell.tsx`, each of
  which already calls `onClick={onBeginEdit}`. `cues/CueValueGrid.tsx`, `fixtureSheet/AttributeRow.tsx` and `PickAttributeRow.tsx` pass no-ops,
  which already fit the wider type, so they need no edit.
- The four handlers (D4), D4a in `runner/StackTimeline.tsx`'s `clickCellRef`, and D5's row clear;
  D1's blank-cell rule in `onEmptyCellClick` in `FixturesTable.tsx` and `SheetTable.tsx`, the event
  passed to the hosts' background clear.
- The Add latch (D7) in both selection bars and their hosts' intent.
- Tests: `cellSelectionModel.test.ts` (`toggleCell` adds and removes; the marquee's `'toggle'` still
  only adds; `cellRectangle` equals the ⇧-arrow's); `useCellSelection` (`click` sets the cursor; ⇧
  from it; ⇧ after a marquee derives the anchor; ⇧ with an anchor off the grid is a plain click; a
  ⌘ double-click leaves the cell selected whether or not it started selected, and a quick ⌘-click
  on a neighbouring cell still toggles it; removing the last cell drops the cursor);
  `FixturesListContainer.test.tsx`: ⌘-click two cells in one column and one in another: a typed
  value lands on those three only, Spread skips the gap; ⇧-click a rectangle; ⌘-click with rows
  selected clears them; a sheet-kit host (`PatchSheet` or the cue sheet) the same; the cue editor's
  Values grid ⌘-click; a ⌘-click on a blank cell keeps the selection and a plain one clears it,
  in the programmer and a kit sheet; `StackTimeline.test.tsx`: ⌘-click adds a cell, a ⌘-click on the one
  selected cell deselects it and sends no timeline edit, a ⌘-click toggles once (not again through
  `useSheet`'s handler), a plain click on the one selected cell still toggles its layer, ⇧-click
  unchanged; the Add latch on a coarse pointer (a tap toggles; absent on a fine
  pointer; off on Escape).
- Docs: frontend `CLAUDE.md` §Sheet kit's selection paragraphs (the click, ⌘ / ⇧, the cursor), the
  Timeline's cell paragraph and the programmer's row C.
- Local: `check:quick`.

### Session 4 — Recent values on the desk

D14–D19.

- Backend: `state/RecentValues.kt` (the service, the vocabulary and per-key patterns);
  `plugins/RecentValuesSocket.kt` beside `GrandMasterSocket.kt`, its In and Out messages registered
  in `plugins/SocketMessages.kt` and dispatched in `plugins/Sockets.kt` where the grand master's
  are; `telemetry/TraceSampling.kt`'s `GESTURE` row for `recentValues.record`.
- Client: `api/recentValuesApi.ts` (parse and send, after `api/grandMasterApi.ts`),
  `store/recentValues.ts` (`useRecentValues(key)`), `components/editor/RecentValues.tsx`, its
  colour serializer (D15) and `useRecordRecent` (D17); `ColourEditor.tsx` mounts the band and
  records through it, its picker on `holdPicker`'s release and its emitter sliders on
  `onValueCommit`.
- Tests: `RecentValuesTest.kt` (newest first, cap 12, a repeat moves to the front, per project, an
  unknown key or bad value dropped) and a socket test (`record` then `state` on the live project; a
  second socket sees it); `TraceSamplingTest` and `SocketMessageWireFormatTest` with the new
  frames; `recentValuesApi.test.ts`; `RecentValues.test.tsx` (draws the list, a tap
  commits the value, empty draws nothing); `ColourEditor` tests (a drag records once on release,
  never per frame; Pick and a palette press record nothing).
- Docs: root `CLAUDE.md` §"WebSocket Messages" (`recentValues.state` / `.record`);
  `docs/websocket-engineering.md` (the frames); `docs/observability-engineering.md` where the
  sampling table's rows are listed; frontend `CLAUDE.md` §"The editor kit" (the band).
- Local: `./gradlew compileTestKotlin`, `--tests 'uk.me.cormack.lighting7.state.*'`,
  `--tests 'uk.me.cormack.lighting7.plugins.*'` and `--tests 'uk.me.cormack.lighting7.telemetry.*'`,
  `check:quick`.

### Session 5 — Recents in every value editor

D20, D20a.

- `fixtures-list/cells/SliderCell.tsx` (`level:<attribute>`), `PositionCell.tsx` (`position`),
  `WarmthCell.tsx` / `editor/WarmthEditor.tsx` (`warmth`); `fixtureSheet/AttributeRow.tsx` and
  `PickAttributeRow.tsx` rows; `fx/ColourEditorBody.tsx`;
  `templates/familyControls/ColourControl.tsx`, `PercentControl`, `PositionControl`. Each records
  through `useRecordRecent` (D17) with its own gesture end: the sliders' Radix `onValueCommit`
  (`SliderCell`, `WarmthEditor`, `PercentControl`, `PositionControl`), the position pad's and
  `ColourControl`'s window release (D20a), `ColourEditorBody`'s `onRelease` and emitter sliders' commit (D23), and the typed
  fields at once.
- Tests: per editor, a drag records once at its end with the right key and spelling, never per
  frame, and a tap on a recent writes it as a typed value (the slider cell over a batch of three:
  three writes); a position commit over heads with a mixed other axis records nothing; the FX list
  picker's colour records on release; the palette editor of a project that is not live draws no
  band and records nothing.
- Docs: frontend `CLAUDE.md` §"The editor kit" (where the band is), §"The fixture sheet" rows, the
  palette editor's family controls.
- Local: `check:quick`.

## 6. Operator checks

One row in `manual-validation.md` per merged session, at the desk:

- Session 1: with `localStorage['lighting7.debug.fxPickerClose'] = '1'` set in the desk's window,
  row C → FX → Colour Cycle and a single-colour FX on a selected head, each colour's dot dragged
  around and let go inside and outside the popover, **Lit on and Lit off**: the picker stays open.
  If it closes, the console's warning names the door. Then, Lit on, a dimmer Wave on the selected
  head with the picker open: the row and the picker stay.
- Session 2: expand two bars, go to Show and back: still expanded, nothing re-selected, the filter
  kept; Collapse all with a head selected.
- Session 3: ⌘-click three scattered colour cells and set one colour; on the tablet, the same with
  *Add*.
- Sessions 4–5: a colour set on the desk's window shows in the tablet's colour pop-up; a recent
  tapped over three cells lands on all three.

## 7. Explicitly out of scope

- A per-cell desk selection (each head with its own families): the desk keeps heads × families
  (D8).
- Recent settings, recents that survive a restart (a machine-local table), and clearing a recent by
  hand.
- Making the three colour pickers into one; session 5 mounts the same band in each.
- A subtracting ⌘-drag (D6).
- Expansion on the Cards and Stage views, which have no element rows.
