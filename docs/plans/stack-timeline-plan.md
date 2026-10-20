# A stack's timeline: cues against the Looks, palettes, effects and scenery they apply

> **Document status: agreed 2026-10-20, not started.** The design is the record in
> [`stack-timeline-design/`](stack-timeline-design/INDEX.md): four boards, the desk survey, and the
> eighteen calls Chris answered (D1–D11 in four rounds, C1–C7 in a fifth, every C as recommended).
> A one-page copy for human readers is
> [`stack-timeline-design/stack-timeline.html`](stack-timeline-design/stack-timeline.html), live at
> <https://claude.ai/artifact/NwW5yWvAqzzincmMnwm8EY> (the checked-in files are the authority).
> Where this plan and the boards disagree, the plan wins on behaviour and the boards on layout and
> copy.

## 1. Context

A stack's Table shows a count of layers per cue, and the cue editor shows one cue at a time. A
question such as *where does Lavender come off?* means opening cue after cue. The brief asked for a
table of a stack's cues against what each applies, where whether a Look is in a cue is one cell.

What the code says, read on 2026-10-20 at `487557e2`. `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`, `F/` is `frontend/src`.

- **Lighting does not track.** A cue is a complete state and a GO is built from the incoming cue
  alone (`docs/lighting-composition-model.md:1520`). Only scenery tracks
  (`K/show/SceneryResolver.kt`, `trackedSceneryAt` at `K/routes/projectScenery.kt:170`). So
  *Lavender in Q2 to Q4* is three layers, one per cue.
- **The stack list carries counts only.** `CueStackCueEntry` (`K/routes/projectCueStacks.kt:735`)
  has `layerCount` and `adHocEffectCount`. The full layers are only on `CueDetails`, one cue at a
  time.
- **A GO adopts layer effects only.** `CueHolds.bringOn` (`K/fx/CueHolds.kt:529-649`) adopts a
  live instance when `continues` holds (`:717`): same `layerEffectUuid`, target, property and
  `comparableEffectSettings`. Adoption is tried only for a layer's effect (`:573`,
  `layer?.let { … }`). An ad-hoc effect is stamped with its own row uuid (`:696`), so an effect
  copied into the next cue is a different row and restarts at every GO.
- **The press already writes a stored cue.** `pressCueLayer` (`K/routes/cueLayerPress.kt:99`) runs
  the pure `LayerPress.press` (`K/fx/LayerPress.kt:61`) over a cue's layers and writes them through
  `writeCueChildren`, retrying while other writes move the layers. It marks a held cue edited
  when `stageContent` changed and fires `cueListChanged` and `cueStackListChanged`.
- **A cue write keeps its children's uuids.** `writeCueChildren` (`K/routes/projectCuesHelpers.kt:645`)
  pairs ad-hoc effects by `CueEffectKey(targetType, targetKey, propertyName, effectType)` and a
  survivor takes the new fields through `take()` (`:763-791`). The client strips `uuid` on write
  (`F/lib/cueUtils.ts:123`).
- **Copies are made field by field.** `CueCopier.copyCue` (`K/routes/cueCopier.kt:77`, ad-hoc rows at
  `:121-152`) serves *Duplicate*, *Copy from another show* and MCP's `copy_to_project`.
- **The sheet kit virtualises fixed 36 px rows, each its own grid** (`F/components/sheet/SheetTable.tsx:217, 755`).
  Nothing spans rows, so a run is drawn per cell from its neighbours. `CueSheet`
  (`F/components/runner/CueSheet.tsx`) already mounts `useSheet`, the bottom `CueEditorPane` and row
  drag; `CueRowStore` (`F/components/cues/editor/CueRowStore.tsx:38, 298`) is the Undo pattern to
  copy.

## 2. Decisions

From the design record, agreed with Chris on 2026-10-20:

| # | Decision |
|---|---|
| D1 | **Cues as rows.** Cue, name, fade and follow at the left; the grid to the right. |
| D2 | **A third mode of the stack view**, *Cards · Table · Timeline*, keeping the Table's rows, row drag, edit lock and bottom pane. |
| D3 | **A column per record and target**: a Look (its own fixtures, or one target it is filtered to), a palette on one target, an effect by type, property and targets, a scenery element. One layer over two targets is two cells. |
| D4 | **Cells stay per cue.** A filled cell is that cue's own layer; neighbouring cells draw as one bar; drag, fill and range on/off edit them. Layers do not start tracking. |
| D5 | **A palette cell follows the press rule**: other palettes of its family come off the same heads in that cue. |
| D6 | **A new cell copies its settings** from the nearest filled cell above in its column, else below, else the press defaults. A new layer goes on top; every cell shows its layer's rank. |
| D7 | **Effects are columns, with a run key.** Ad-hoc effect rows that share a key draw as one run, and a GO between them hands the running effect on, phase kept. |
| D8 | **A parameter change inside a run is an edit in place** at that GO, phase kept, marked ◆. |
| D9 | **Scenery is a column per element**: a change is a cell, the tracked state a line, both editable. |
| D10 | **Timing and live state show**: fade, follow, the live cue framed with its fade running, the next cue, *Edited since GO*. |
| D11 | **The first version has** selection and family filters, *Replace* across a range, a settings popover per cell and a plain *+ Column*. |
| C1 | **It is called Timeline.** |
| C2 | **One selected cell opens the bottom pane** on its cue at the column's tab; a range over several cues folds it. |
| C3 | **Every gesture is one request on one stack route**, one transaction, all or nothing, one Undo. |
| C4 | **A copied cue's effects get fresh run keys.** |
| C5 | **No migration.** Effects stored before the key draw as broken runs; *Join run* fixes them. |
| C6 | **Timeline is offered from 1100 px wide.** Below that the switcher hides it. |
| C7 | **Column order is first use, grouped by kind**, reordered per window and never stored. |

The plan adds its own:

- **P1. One PR per session**, in §6's order, each crossing the wire with its client (CLAUDE.md
  §"Git workflow"). Locally, each session runs the packages it touches (CLAUDE.md §"Pre-commit
  checks"); session 1 runs the full suites, because it bumps `formatVersion`. Each session writes
  its engineering-doc paragraphs and puts its change commit's SHA in its heading when it lands.
- **P2. The run key is the timeline's to write.** `runUuid` is read-only on `CueAdHocEffectDto`, as
  `uuid` is: a cue PATCH, Record, Update and MCP's `create_cue` keep a matched row's key and give a
  new row none. Only the timeline route, the copier and the importer set it. The cue editor needs no
  change, and no other door can break a run by accident.
- **P3. A run key is unique within a cue.** Two effect rows of one cue never share a key, so the key
  can name one instance after adoption. The route refuses a write that would break this.
- **P4. A run key is not stage content.** `stageContent` leaves `runUuid` out, so *Break run* and
  *Join run* on a live cue do not mark it edited since GO: neither changes what is on stage now.
- **P5. Undo restores, it does not invert.** Every edit answers each touched cue's layers, effects
  and scenery as they were, and Undo writes them back with a `restore` op. Inverting a press would
  have to undo the siblings it released, which a snapshot restores for free.
- **P6. The grid is derived on the client.** Nothing stores a column. The read answers stored rows
  plus what the client cannot work out alone (families, the heads each layer reaches), and one pure
  module turns them into columns, cells, runs and ranks.

## 3. The model

### 3.1 Vocabulary

- **Column**: a key the client derives from stored rows (§3.2). Nothing stores it.
- **Cell**: one column in one cue. *Filled* when that cue holds what the column names.
- **Run**: vertically adjacent filled cells that draw as one bar. Layer and palette cells join
  whenever both are filled; effect cells join only when they share a run key; scenery cells never
  join (each change is its own chip, and the tracked state is a line between them).
- **Run key**: `cue_ad_hoc_effects.run_uuid`, nullable. Cells with the same key are one run.
- **Rank**: a layer's 1-based position in its cue's layer order, 1 at the bottom. Two cells of one
  layer (two targets) show the same rank.

### 3.2 What a column is (D3)

| Kind | Column key | Filled when the cue has | Turning a cell on | Turning it off |
|---|---|---|---|---|
| Look | `lookId` + a target, or none for the Look's own fixtures | a layer of that Look whose targets include the target (or, for none, are empty) | the press with `targets: [target]`, or none | the off press on that target |
| Palette | `templateId` + a target, or none for a per-fixture palette's own heads | a layer of that palette covering the target | the press; same-family palettes come off those heads (D5) | the off press |
| Effect | `effectType` + `propertyName` + the canonical target (`CueAdHocEffectDto.canonicalTarget()`) | an ad-hoc row with that signature | a copy of the nearest filled cell's row (D6), joining its run | the row is deleted |
| Scenery | `elementUuid` | a `cue_scenery` row | the state and transition are written | the row is deleted; tracking resumes |

A Look's own effects stay inside its column: they already continue across cues that layer it. A
palette holding an effect is a palette column. Groups and fixtures are both targets; a cell is one
target exactly as stored, never expanded.

### 3.3 The run key at a GO (D7, D8)

`CueHolds.bringOn` gains an arm for ad-hoc effects. With the outgoing cue named by `adoptFrom` (a
stack GO, GO TO or GO BACK whose outgoing cue's only hold is that stack's live entry), an incoming
ad-hoc instance with a run key looks for a live instance of the outgoing cue with the same key,
target and property, running and not an N-shot (`mayContinue`):

- **Same settings** (`comparableEffectSettings` equal): adopted as a Look's effect is today, id and
  phase kept, retagged to the incoming cue and restamped with the incoming row's uuid.
- **Different settings**: adopted the same way, then edited in place with `applyEffectUpdate`
  (`K/routes/lightFx.kt:365`) on the request `alignmentRequest` builds
  (`K/routes/cueUpdateStage.kt:205`, made shared). The edit lands at the GO, not over the fade. A
  move between *Seconds* and a master restarts the phase, as it does for Update stage. A refused
  update gives the instance back and the incoming effect starts fresh.
- **No match**: today's behaviour. The outgoing effect shrinks out over the fade and the incoming
  one grows from phase 0.

`FxInstance` gains `runUuid`, stamped in `cueEffectInstances` beside `layerEffectUuid` (which stays
the row uuid, so Update stage's `CueEffectKey` reconcile is untouched). `Adoption` records the
instance's old `layerEffectUuid`, settings and cue so `giveBack` restores all three. A loose press
(`adoptFrom` null) adopts nothing, as today.

### 3.4 Storage and sync (P2, C4, C5)

- `cue_ad_hoc_effects.run_uuid` (`javaUUID`, nullable), added by `createMissingTablesAndColumns`.
  No backfill (C5).
- `CueAdHocEffectDto.runUuid`, read-only. `DaoCueAdHocEffect.take()` leaves it alone, so a matched
  row keeps its key through any cue write; a new row has none.
- `CueAdHocEffectJson.runUuid`, omitted at null. Sync `formatVersion` 36 → 37, `minReader` stays 5,
  following the v29 pattern: the `FormatVersionJson` paragraph (`K/sync/dto/SyncDtos.kt:36-93`), the
  importer's (`K/sync/ProjectImporter.kt:~182-213`), both constants, and a §"v37" section in
  `docs/sync-engineering.md`. `RichProjectFixture` sets a key on two rows of adjacent cues.
- **Copies** (C4): `CueCopier` maps each source key to a fresh one, once per copy call. A single
  cue's *Duplicate* gets keys used nowhere else; a whole stack copied by `copy_to_project` keeps its
  runs, joined to each other and to nothing in the source. A project clone keeps keys as they are:
  it copies the whole project, runs and all.

### 3.5 The read

`GET /projects/{id}/cue-stacks/{sid}/timeline`, under `withProject` like the stack GET. One entry
per row of the stack in `ENTRY_ORDER`, markers included:

```
{ stackId, entries: [{ entryId, kind: CUE | MARKER, label?, cueId?,
    layers: [{ id, source: {kind, id, uuid, name, families}, targets, heads,
      sortOrder, enabled, amount, blendMode, propertyMask, stomp, timed,
      speedMasterUuid, rateSpeedMasterUuid }],
    effects: [{ id, uuid, runUuid, effectType, propertyName, targetType, targetKey,
      family, heads, settings }],
    scenery: [{ elementUuid, state, transitionMs }],
    trackedScenery: [{ elementUuid, state, fromCueId }] }] }
```

- `families` is the source's families (a Look's from its rows' properties, a palette's from its
  family) and `family` an effect property's group, so the family filter needs no client lookup.
- `heads` are the fixture keys a layer or effect reaches, so *Selection only* intersects them with
  the desk selection's heads (expanded through the group list the client already caches).
- `settings` is what `comparableEffectSettings` compares, so the client marks ◆ where a run's
  settings change. `trackedScenery` reuses `trackedSceneryAt`.
- Everything else (names, fade, follow, numbers, live state) the client already has from the stack
  list, `cueRunStateChanged` and `cueHolds.state`.

The client tags it `CueStackTimeline` and refetches on `cueStackListChanged`, `cueListChanged`,
`lookListChanged`, `paletteListChanged` and `stageElementListChanged`. No new frame.

### 3.6 The edit route (C3, P5)

`POST /projects/{id}/cue-stacks/{sid}/timeline/edit` `{ops, dryRun?}`, under `withCurrentProject`
like the press. One transaction for the batch; inside it the ops run in order, each over the state
the previous left. Every named cue must have an entry in the stack.

| Op | Does |
|---|---|
| `layerOn {cueId, source, target?, settingsFrom?}` | Builds the arriving layer with the settings of layer `settingsFrom` (D6) or the press defaults, then runs `LayerPress.press` as `pressCueLayer` does (its decide step extracted so it runs inside this transaction). Records what it released. |
| `layerOff {cueId, source, target?}` | The off press. |
| `layerSettings {cueId, layerId, fields}` | The cue editor's layer fields: enabled, amount, blend, mask, stomp, delay, interval, random, masters. |
| `replace {cueIds, from, to, keepSettings}` | In each cue: where the cue lacks `to`, `from`'s layer takes `to`'s source in place (rank and settings kept when `keepSettings`); where it has `to`, `from` comes off. |
| `effectOn {cueId, fromEffectUuid}` | Copies that row into the cue. Its key is the source's, or a new key given to both when the source has none. |
| `effectOff {cueId, effectUuid}` | Deletes the row. |
| `breakRun {cueId, effectUuid}` | A fresh key for this cell and the cells of its run below it in this stack. |
| `joinRun {cueId, effectUuid}` | Takes the key of the nearest filled cell above in the same column, for this cell and the rest of its run below. Refused when there is none. |
| `scenerySet {cueId, elementUuid, state, transitionMs?}` / `sceneryClear {cueId, elementUuid}` | Checked by `parseSceneryList`, written by `replaceCueScenery`. |
| `restore {cueId, layers, adHocEffects, scenery}` | Undo (P5): writes the lists back through `writeCueChildren` and `replaceCueScenery`, run keys included. |

It answers `{changed: [cueId], released: [{cueId, source, targets}], before: [{cueId, layers,
adHocEffects, scenery}], edited: [cueId]}`. A cue held live whose `stageContent` changed is marked
edited since GO (`CueHolds.markEdited`) and is listed in `edited`; nothing is sent to the stage.
`dryRun` runs the batch and rolls it back. Problems are named all at once: 400
`TIMELINE_EDIT_INVALID {problems: [{op, message}]}` (an unknown source, a cue not in the stack, a
key collision under P3, a scenery state its element refuses), 409 when the project is not current.
It fires `cueListChanged` and `cueStackListChanged` once per batch.

### 3.7 The client's model

`F/lib/stackTimeline/` holds the pure derivation, tested on its own:

- `columns(timeline, names)`: the columns, grouped Looks, Palettes, Effects, Scenery, each group in
  order of first use down the stack (C7), with a per-window order applied over it from
  sessionStorage (`timeline.columns.<stackId>`).
- `cells(timeline, columns)`: per entry and column, the cell: filled or not, rank, glyphs
  (non-default amount, blend, mask, stomp, timed, a master, disabled), and for effects the key and
  whether settings changed from the cell above.
- `runs(cells)`: per cell, whether it starts or ends a run, joins across a marker, restarts (↺,
  two keys meet) or changes in place (◆); for scenery, whether the cell carries a tracked line.
- `filter(columns, {kinds, family, selectionHeads})`: the kind chips; the family filter keeps a Look,
  palette or effect column whose families include the family and always keeps scenery; *Selection
  only* keeps columns whose heads meet the desk selection.

## 4. UX

### 4.1 Where it lives (D2, C6)

- `/show/stacks/:stackId/timeline` beside `/table`, `ShowPage stackView="timeline"`. `CardsListView`
  gains `timeline`; `ShowStackViewSwitcher` draws three segments, the third hidden below 1100 px of
  content width, where a stored or linked Timeline falls back to Table.
- `StackDetail` mounts `StackTimeline` for the mode. It takes the same `locked`, live and standby
  props as `CueSheet` and the same `CueEditorPane`.

### 4.2 The grid (D1, D4, D9, D10)

- Built on `useSheet` and `SheetTable`. The sticky first column holds the grip, the pip, the number,
  the name and its chips (shared, *Edited*); Fade and Follow follow it; then one column per derived
  column, 84 px (92 for effects and scenery), under a group band naming the kind and its count.
- A cell draws its run segment from `runs()`: rounded where it starts or ends, square and bleeding
  over the row border where it continues, its kind's colour as a straight left strip. Layer cells
  show the rank and glyphs, palette cells a swatch, effect cells ∿, ◆ with the new setting or ↺,
  scenery cells the change's text, and a 2 px line where the state is only tracked.
- Markers are divider rows that let runs and tracking lines pass through.
- The live row has a green edge and a fade bar under the first columns, driven by
  `cueRunStateChanged`'s `fadeElapsedMs`; the next row a blue edge.
- A legend footer names the marks.

Kit changes, in `F/components/sheet/`, kept generic:

- **A divider row that can draw cells.** `SheetRow.divider` rows take an optional per-column render
  so the timeline can draw a run passing through a marker. Other sheets keep their full-width rule.
- **A selection ring that joins.** A selected cell whose neighbour above or below in the same column
  is selected drops that edge of its overlay, so a range in one column is one ring. All sheets get
  it.
- **A header band.** `SheetColumn.group` names a band drawn above the header row over consecutive
  columns sharing it.

### 4.3 Filters (D11)

Row B under the stack header: kind chips (Looks, Palettes, Effects, Scenery, each with its count),
a family segment (All, Intensity, Colour, Position, Beam), *Selection only* naming the desk
selection, and *+ Column*. Filters are per window (sessionStorage).

### 4.4 Editing (D4–D8, C2, C3)

All writes go through §3.6's route, one request per gesture; nothing is sent to the stage.

- **One cell.** A click selects; ⏎ or a second click toggles it; ⌫ turns it off. A layer cell on is
  `layerOn` with `settingsFrom` per D6; the toast names what the press released ("Amber on LX1
  Orbits in Q5. Lavender released there.") with *Undo*.
- **A run's end.** The last cell of a run carries a handle. Dragging it down fills each cell it
  crosses from the dragged cell; dragging it up empties them. Ghost cells and a scope chip show the
  result before release.
- **A range.** Marquee or shift-click, across columns. The selection bar: *On* (empty cells filled,
  each column from its nearest filled cell), *Off*, *Fill ↓* (each column's top row copied down),
  *Replace with…*, *Settings…*, *Deselect*. ⌘D fills down. A verb that would change nothing is
  disabled.
- **Replace with…** A popover: the selected column's record, a picker for the replacement and its
  target (the desk selection by default), *Keep each layer's rank and settings*, and a readout of
  which cues swap and which already hold it.
- **Settings…** The layer's fields in a popover, as the cue editor's Looks tab draws them; over a
  range a field the cells disagree on reads *mixed* until set. A palette layer covering several
  targets says so. *Open Look* opens the Looks library.
- **An effect cell.** Its popover lists the run cue by cue with each cue's settings, ◆ where they
  change, and *Break run at Qn*, *Join run above* and *Edit Qn's effect…* (the pane's FX tab).
- **A scenery cell.** The element's states for its kind and the transition; *Clear change* hands the
  cue back to tracking.
- **+ Column.** Searches Looks, palettes and effects; a palette or effect takes the desk selection's
  target unless one is picked. The new column is empty and lives in this window until a cell is
  filled, after which it is derived like any other.
- **Undo.** The toast's *Undo* and ⌘Z send a `restore` op with the answer's `before` (P5), one step
  per gesture, last first, kept while the window lives.
- **The pane (C2).** One selected cell opens `CueEditorPane` on its cue at the column's tab (Looks
  for Look and palette columns, FX, Scenery); a selection over several cues folds it.
- **Live and locked.** A write to a live cue marks it *Edited since GO*; with the live row selected
  the bar offers *Update stage…*. Locked, cells are read-only, filters and selection still work, and
  every verb asks to unlock, as in the Table.

## 5. What is not changing

- **No tracking for lighting** (D4). A layer in Q2 says nothing about Q3.
- **The cue editor** writes as it does. P2 keeps it from touching run keys.
- **Look and palette effect adoption** keeps its rule (same uuid and settings).
- **Update stage** keeps its reconcile by row uuid; run keys are a GO's business.
- **MCP** gets no tool. `create_cue` and Record make effects with no key; the run key reaches MCP
  when a use for it does (§9).

## 6. Implementation: five sessions

Sessions 1 and 2 are backend only and can land before any UI. Session 3 needs neither. Sessions 4
and 5 need 3, and 5 needs 1 and 2 for effect runs.

### Session 1: the run key, stored and synced (backend)

- §3.4: the column, the read-only DTO field, `take()` leaving it, `stageContent` leaving it out
  (P4), the sync field and the v37 bump, `CueCopier`'s per-call key map (C4).
- **Tests:** a cue PATCH, a Record UPDATE and `create_cue` keep a matched row's key and give a new
  row none; the round trip carries a key and omits a null one; a v36 archive imports with none; a
  duplicate gets fresh keys; a copied stack keeps its runs joined among the copies; a clone keeps
  them as they are.
- **Docs:** `cues-engineering.md` (the ad-hoc row), `sync-engineering.md` §"v37".
- Full suites locally (P1).

### Session 2: a GO hands a run on (backend)

- §3.3: `FxInstance.runUuid`, the ad-hoc arm of `bringOn`, settings-differ adoption through a shared
  `alignmentRequest`, `Adoption` and `giveBack` restoring the uuid and settings.
- **Tests**, beside `EffectFadeTest`'s P4 cases: same key and settings keeps id and phase; a new
  speed keeps id and phase and runs at the new speed after the GO; a Seconds/master move restarts;
  different keys fade one out and one in; no key behaves as today; a paused or N-shot effect is not
  handed on; GO BACK hands it back; a press between the two holds gives the adoption back; Update
  stage on the incoming cue still reconciles by row uuid.
- **Docs:** `fx-engineering.md` §"FxInstance.cueId" and the P4 paragraph,
  `cue-stacks-engineering.md` §"Holds".

### Session 3: the read and a read-only Timeline (backend + frontend)

- §3.5: the route, its DTOs, `heads` and `families`.
- §3.7 and §4.1–4.3: the mode, the switcher and its 1100 px rule, `lib/stackTimeline/`, the grid
  with runs, ranks, glyphs, markers, live and next rows, the legend, filters, per-window column
  order, the kit's three changes, the pane on one selected cell (C2).
- **Tests:** the route over a stack with markers, a shared cue, a two-target layer, keyed and
  unkeyed effects and tracked scenery; the derivation's columns, cells, runs (across a marker, a
  restart, a change) and filters; the kit's joined ring and passing divider; the switcher hiding
  Timeline below 1100 px.
- **Docs:** `cue-stacks-engineering.md` §"REST API", CLAUDE.md's Cue Stack Run Endpoints,
  `frontend/CLAUDE.md` for the mode.

### Session 4: editing layers (backend + frontend)

- §3.6's route with `layerOn`, `layerOff`, `layerSettings`, `replace`, `scenerySet`, `sceneryClear`
  and `restore`; `pressCueLayer`'s decide step extracted and shared.
- §4.4 for Look, palette and scenery cells: toggles, keys, drag, range verbs, *Replace with…*,
  *Settings…*, the scenery popover, *+ Column*, the released toast, Undo, live and locked.
- **Tests:** each op; a batch that fails on its last op rolls back its first; `dryRun` writes
  nothing; settings copied on `layerOn` and a widened layer keeping its own; a palette knock-on
  answered in `released`; `restore` returns a cue to its `before` exactly, uuids included; a live
  cue marked edited and nothing sent (no `programmer.*`, `channels.*` or `fx.*` frame); a client
  test per gesture asserting one request.
- **Docs:** `cue-stacks-engineering.md` §"REST API" and a new §"Timeline", CLAUDE.md's endpoint
  list.

### Session 5: editing effects and runs (backend + frontend)

- §3.6's `effectOn`, `effectOff`, `breakRun`, `joinRun`, and P3's refusal.
- §4.4 for effect cells: toggles, drag and fill joining runs, the run popover, *Break run* and *Join
  run*, ↺ and ◆ live in the grid.
- **Tests:** `effectOn` from an unkeyed source keys both; break and join over a run with a marker in
  it; a key collision refused; a live cue's break not marking it edited (P4); end to end, a run made
  in the timeline continues across a GO with its phase kept.
- **Docs:** `cue-stacks-engineering.md` §"Timeline", `fx-engineering.md`'s run-key paragraph.

## 7. Migration

One nullable column, no backfill (C5). `formatVersion` 37: a desk on 36 refuses any archive a 37
desk writes ("newer than this install supports", `ProjectImporter.kt:630`), as every bump does, and a
37 desk reads a 36 archive as having no keys.

## 8. Explicitly out of scope

- A tablet or phone Timeline (C6).
- A stored column order (C7).
- Tracking for lighting (D4).
- The cue's own values, events and speed masters as columns (D10).
- A picker of unused records with use counts (D11).
- An MCP tool for the timeline or for run keys.
- Run keys on Look and palette effects, which already continue.

## 9. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-TIMELINE-MCP` (session 5): an MCP tool to read a stack's timeline and write runs. Trigger: an
  operator asks Claude to make an effect continue across cues.
- `FU-TIMELINE-VALUES` (session 3): the cue's own values as columns, per family. Trigger: the
  timeline is used to find where a hand-set value changes.

## 10. Verification

Beyond the suites, at the desk on Sleeping Beauty:

- **Session 1.** Duplicate a cue with a keyed effect and read both cues' keys through the API.
- **Session 2.** Key the Pulse in Q2 and Q3 through the API, GO from Q2 to Q3 and watch it not
  restart; change Q3's speed and watch it slow at the GO without a jump.
- **Session 3.** Open Act 1 in Timeline and check its Looks against the cue editor for three cues;
  narrow the window below 1100 px and land in Table.
- **Session 4.** Drag Shehds colour from Q3 to Q6 and undo it; turn Amber on in Q5 and see Lavender
  come off; edit the live cue and see *Edited since GO* while the rig does not move.
- **Session 5.** Fill a Pulse from Q2 to Q6, GO through the run and watch it continue; break it at
  Q4 and watch Q4's GO restart it.

## 11. Questions

Answered by Chris on 2026-10-20 while designing; the record's INDEX lists the declined options:

1. Which way round? Cues as rows (D1).
2. Where does it live? A mode of the stack view (D2).
3. What is a column? One per record, a palette per target (D3).
4. A Look across cues? Per-cue cells with range gestures (D4).
5. A palette cell? The press rule (D5).
6. Effects? Columns (D7), with a run key (D7).
7. A change mid-run? In place (D8).
8. Scenery? A column per element (D9).
9. What else shows? Timing, live state, effects, scenery (D10).
10. The first version? Filters, Replace, the settings popover (D11).
11. The name? Timeline (C1). 12. The pane? On one cell (C2). 13. Writes? One route, one Undo (C3).
14. Copies? A fresh key (C4). 15. Old effects? No migration (C5). 16. Narrow? From 1100 px (C6).
17. Column order? Per window (C7).
