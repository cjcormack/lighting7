# Editing a cue in the Show view — the programmer's tables, writing the stored cue

> **Document status: AGREED, 2026-10-15.** This is design session R2 of
> [`venue-visit-plan.md`](venue-visit-plan.md). It follows R1, [`cue-model-plan.md`](cue-model-plan.md),
> and designs against that model: a cue is a shared project record, a stack is an ordered list of
> entries, and delete is its own explicit operation. The questions asked and their answers are in
> §11 and in the design record, [`show-cue-editing-design/INDEX.md`](show-cue-editing-design/INDEX.md),
> beside the boards. A one-page copy for human readers is
> [`show-cue-editing-design/show-cue-editing.html`](show-cue-editing-design/show-cue-editing.html),
> with a live copy at <https://claude.ai/artifact/N3Acyq477Lj9i9r4p6YWzm> (the checked-in file is
> the authority).
>
> Where this plan and the venue-visit plan or R1 disagree, this plan wins inside its scope: editing a
> cue in the Show view and the cue action set. It disagrees with R1 once, on purpose: D12 puts a
> duplicate at the end of the stack, where R1 §3.8 put it after the source's entry.

## 1. Context

The venue-visit evening read cues in the Show view and changed them somewhere else. The card shows
what a cue holds and offers *Edit in Programmer*, which Includes it and so lights it on stage unless
Blind was switched on first. The table view has no edit, Record, duplicate or delete at all. The
venue-visit plan's R2 asked for direct editing of a cue's values, Looks and effects, in tables that
behave like the programmer's and never send output, and for one action set in both views.

The findings that shape the plan, from two surveys of the code on `main` at `a6e4053` and the desk
running Sleeping Beauty (project 13, `lighting7-tch-2026-sleeping-beauty`, on venue v7):

- **A stored cue edit sends nothing to the stage.** `PUT` and `PATCH /cues/{id}`
  (`routes/projectCues.kt:139-287`) fire `cueListChanged` (and `cueStackListChanged`) and nothing
  else. Only three paths republish a live cue: Record, Update (`republishCueIfLive`,
  `routes/programmerRecord.kt:581-587`, from `programmerSurface.kt:132, 388`) and the AI's patch-edit
  tool (`ai/PatchEditTools.kt:422`). A Look or template contents edit republishes every live cue that
  layers it (`routes/lookRepublish.kt:118-219`). So a table that writes the cue is silent by
  construction, and a live cue it edits goes stale on stage.
- **The comments describing cue-edit live apply are stale.** `fx/CueApply.kt:439`,
  `fx/CueAssignmentLayer.kt:100, 362` and `fx/CueStackManager.kt:373` still describe a
  "cue-edit live apply" that sweep item D1 retired (`routes/programmerRoutes.kt:79`). R1 session 2
  removed them (`595aced3`); nothing here relies on them.
- **Blind does not hold the programmer's effects.** Blind takes programmer values and layer slots
  out of the merge (`fx/LayerResolver.kt:266`) and empties the suppression map (`fx/FxEngine.kt:207`),
  so "effects resume painting" (`docs/lighting-composition-model.md` §Blind) — including the
  programmer band's own. Include spawns a cue's untimed effects in that band
  (`routes/programmerInclude.kt:59-160`), so including a cue with effects under Blind puts its
  effects on the rig today.
- **A cue write replaces its children.** `deleteCueChildren` then `createCueChildren`
  (`routes/projectCuesHelpers.kt:519, 630`) on every write, so each row, layer, effect and hook gets
  a fresh uuid. PR #140 (`652128a`) fixed the same thing for Looks because sync then read every
  edit as "everything replaced". An editor that saves per gesture would do that to cues dozens of
  times a minute.
- **The card's read surface is long and its verbs are last.** Act 1 Q1 of Sleeping Beauty expands
  to a values preview (4 rows, *+9 more*), five layers, one scenery row and more than a dozen tracked
  ones,
  effects and hooks before *Remove · Duplicate · Edit in Programmer · Record*
  (`components/runner/CueCardEditor.tsx:417`), well over a screen below the cue's name.
- **The programmer already has the tables, behind seams that take a fourth writer.** The value grid
  writes through `EditorContext` (`components/programmer/EditorContext.tsx:35`, arms `live` and
  `lookLayer`) and `useCellWriters` (`components/fixtures-list/useCellWriters.ts:124-269`); the
  focused Look layer's `LookRowStore` (`components/programmer/LookRowStore.tsx:100-261`) is the
  shape of a store that writes a record rather than the programmer. `LookStack`'s `LayerHandlers`
  are index-based and host-agnostic (`components/looks/LookStack.tsx:97-114`), and `FxEffectFields`
  edits a draft (`components/fx/FxEffectFields.tsx:110-139`), which is how `TemplateEditor` uses it.
  `CueValueGrid` already reads `/cues/{id}/cooked` through the same scoped-value helpers.
- **Mounting `FixturesListContainer` as-is would send output.** Its desk-selection bridge, Release,
  `useClearCellEffects`, the palette strip and Locate/Highlight all reach the rig or the desk
  (`components/fixtures-list/FixturesListContainer.tsx:553, 841, 1384`). `useListSelection` has three
  Redux scopes and no fourth (`store/selectionSlice.ts:37`).
- **`/cues/{id}/cooked` loses a group row's key.** Group rows are expanded to members before the
  cook (`fx/CueApply.kt:425-428`), so a cooked row names a member (`routes/cueCooked.kt:91`). Its
  `layerId` is null for the cue's own row and names the winning layer otherwise — exactly the
  provenance a grid needs.
- **Duplicate is client-side.** `ShowPage.tsx:276-291` rebuilds the cue with `buildCueInput` and
  creates it; there is no route. Delete (`routes/projectCues.kt:274`, its teardown `deleteCues` in
  `routes/cueDelete.kt` since R1.4) tears down live output and sweeps pads, slots and anchors; it is
  refused 409 `CUE_IN_USE` until forced, and the Show view has no cue delete, only *Remove from
  stack*.
- **The preview route composes without output.** `GET /cue-stacks/{stackId}/preview?cueId=`
  (`routes/projectCueStacks.kt:381`, `routes/cuePreview.kt:85`; `?entryId=` since R1.2, keeping every
  cue another stack or a press holds) composes a cue against the other live stacks in a fresh resolver and answers DMX channels and scenery. Layer 4 only, no effects.
- **Cue properties is a second editor.** `CuePropertiesSheet` / `CuePropsPane` edit name, number,
  fade, curve, follow, notes, busk pages, scenery, events and hooks, autosaving per field.
- **Seen on the desk, outside this plan:** on a 390px phone the venue-behind banner sets its two
  buttons beside a one-word-wide column of text and fills the screen (`VenueBehindBanner`); §9 records
  it.

## 2. Decisions

Agreed with Chris on 2026-10-14 (§11):

| # | Decision |
|---|---|
| D1 | **One cue editor, two hosts.** The expanded card's body and the table view's bottom pane mount the same `CueEditor`: a header (number, name, fade, curve, follow, the shared chip, the save state, *Show on stage*) and the tabs *Values · Looks · FX · Scenery · Events · Cue*. |
| D2 | **The Values grid shows what GO lands and edits the cue's own rows.** Each cell is marked *own* or with the Look that won it. An edit writes an own row, which beats every layer, so the cell shows what was typed. ⌫ removes the own row and the layer underneath shows again. A filter narrows the grid to the rows the cue holds itself. |
| D3 | **A Look layer is edited as a layer, never as a Look.** Add, remove, reorder, enable, amount, blend, mask, stomp and targets are stored on the cue and send nothing. *Open Look* goes to the Looks library, because a Look's contents edit changes every cue that layers it and republishes the live ones. |
| D4 | **A live cue edited in place is marked, and *Update stage* is the one way to send it.** *Edited since GO* on the card, the row, the pane and the phone card; the next GO into it lands the edit. *Update stage* is explicit and confirmed, the one exception to "never sends output". (Since R1.2 that is the next time the cue comes on, its first hold: while another stack or a press keeps it live, a GO into it lands nothing.) |
| D5 | **The table view's editor is a bottom pane** under the cue sheet, for the one selected row, with a drag handle for its height. With no row, or several, there is no pane. |
| D6 | **Edit in programmer is a split button whose default follows the show.** Stopped, its face is *Edit in programmer* with *Blind edit in programmer* in its menu; running, its face is *Blind edit in programmer* with the live edit in its menu. |
| D7 | **Blind holds the programmer's effects too.** Effects the programmer runs are held while Blind is on and paint when it goes off, as its values do. |
| D8 | **Locked, the tables read and Blind edit still works.** Both views draw the whole action set; every action but Blind edit asks to unlock, as the cue sheet's verbs do today. |
| D9 | **The phone card gets the action set in a ⋯ menu**, and its body stays a read-out. A values grid is not a phone surface. |
| D10 | **Edits save as they go**: coalesced at 400 ms with a 2 s ceiling, the whole list rebuilt from the latest cue at flush, the cue's save state in the header, and an Undo toast for the last edit. |
| D11 | **Update stage sends rows and effects at the cue's fade.** The rows republish over the cue's own fade; effects are brought in line — a changed one edited in place with its phase kept, a removed one stopped, a new one started. Events and scenery are not re-fired. One confirm per press, naming the cue and the counts. |
| D12 | **Duplicate appends the copy to the end of the stack** (venue-visit S3's rule) and opens it. This replaces R1 §3.8's "after the source's entry". |
| D13 | **The Stage view can draw the cue in edit.** A vis source, *Cue in edit*, composed by the preview route and drawn, never output. The editor's *Show on stage* points this window's Stage view and Positions plan at it. |
| D14 | **The grid lists the cue's fixtures**: every target of an own row or of a layer, in rig order, with their cells under a multi-head fixture. *+ Fixtures…* adds rows; *Whole rig* lists every fixture. |
| D15 | **Cue properties folds into the editor.** `CuePropertiesSheet` retires. The FX tray's *on Q12* and the Stage's *Moves with* open the editor instead. |
| D16 | **The programmer's source box gains *← Show*** while its include came from the Show view. It returns to that stack with the cue open. |
| D17 | **An effect is added through the FX picker, in draft.** A tap adds the effect to the cue with its defaults and runs nothing; a second tap swaps it. Its fields are the live editor's (`FxEffectFields`) over the stored effect, plus the cue's timing. |

The plan adds its own:

- **P1 — One PR per session**, in §6's order, per CLAUDE.md §"Git workflow". Each session ends with
  `./gradlew test` and `npm run check` green, its engineering-doc paragraphs written, and a
  done-marker in its heading citing its change commit(s), never the merge commit.
  The venue-visit plan's P1 checklist ([`venue-visit-plan.md`](venue-visit-plan.md)) applies
  before each PR.
- **P2 — Nothing in the Show view sends output but *Update stage*.** The cue grid mounts none of the
  surfaces that reach the rig or the desk: the desk-selection bridge, Release, Locate, Highlight,
  the palette strip, the popup's Palettes bands (a press since R3.3, `a14d8220`; the grid's cells
  take no `projectId`, so they draw none — apply-model plan session 5), `useClearCellEffects` and the live writers. Its selection is
  local state. A test
  per tab asserts that a full editing pass sends no `programmer.*`, `channels.*` or `fx.*` frame.
- **P3 — A cue's children keep their uuids across a write**, reconciled by natural key as a Look's
  are. A per-gesture save then changes one record in sync, and *Update stage* can tell an edited
  effect from a new one.
- **P4 — The editor writes the cue.** A shared cue changes in every stack; the header's chip says
  where (*in 2 stacks: Act 1 Q0 · Act 2 Q0*), and nothing asks first, since R1 D1 is the point.
- **P5 — The entry's fields are this stack's.** Number and follow edit the entry the editor was
  opened from (R1 D2); everything else edits the cue. The header draws the two apart.

## 3. The model

### 3.1 Vocabulary

- **Editor**: `CueEditor`, one cue's header and tabs. **Host**: what mounts it — the card body, the
  table's bottom pane.
- **Own row**: one of the cue's `propertyAssignments`. It is the cue's last layer and wins within the
  cue (`docs/lighting-composition-model.md` §"Looks, templates and layers").
- **Composed cell**: the value the cue's cook lands on one (head, property), and its source: own, or
  the layer that won.
- **Edited since GO**: a cue held live whose rows, layers or effects changed after it came on (its
  first hold).

### 3.2 Where an edit goes (P2)

| Surface | Writes | Reaches the stage |
|---|---|---|
| Values, Looks, FX tabs | the cue (`PATCH /cues/{id}`, whole child lists) | when it next comes on (its first hold), or *Update stage* |
| Header (name, fade, curve, notes) | the cue | no |
| Header (number, follow) | the entry, `PATCH cue-stacks/{sid}/entries/{entryId}` (R1.4; the cue's `PUT` and `PATCH` no longer write them) | no |
| Scenery tab | `PUT /cues/{id}/scenery` | drawn at once, as today (scenery is never output) |
| Events tab | `PUT /cues/{id}/events` | when it next comes on (its first hold), armed only |
| *Open Look* → Looks library | the Look | republishes live cues, as today |
| *Edit in programmer* / *Load into programmer* | the programmer (Include) | live, unless Blind |
| *Blind edit in programmer* | Blind on, then Include | nothing until Blind goes off (D7) |
| *Record…*, programmer *Update* | the cue | republishes if live, as today |
| *Update stage* | nothing stored | the cue's rows and effects, at its fade (D11) |

### 3.3 The cue row store (frontend)

`components/cues/editor/CueRowStore.tsx`, the shape of `LookRowStore` over one cue:

- **Server state** is the cue (its details query) and its cook (`/cues/{id}/cooked`). A **draft**
  holds per-key own values the operator has typed and not yet seen saved, notifying per key as
  `lookRowDraft` does, plus removals, which the Look draft never needed.
- **Writes** go to the draft and schedule a flush (400 ms quiet, 2 s ceiling, D10). The flush builds
  the whole `propertyAssignments` list from the **latest** cue at flush time — keeping each row's
  `fadeDurationMs`, `moveInDark` and `sortOrder` — and PATCHes it. Layers and effects use the same
  store and the same flush, each its own whole list.
- **Undo** keeps the lists as they were before the last flush; *Undo* (the toast, or ⌘Z with focus in
  the editor) PATCHes them back. One step, per editor.
- **Save state** (`Saving… · Saved · Save failed`) is the store's, shown in the header beside the
  existing per-cue `SaveStatusIndicator`'s vocabulary.
- **The writers.** `EditorContext` gains a `cue` arm (`{kind: 'cue', cueId, entryId?}`, in the
  provider's memo keys), and `useCellWriters` a cue branch with **no live fallback**: set, colour,
  position and clear land in the store. A single-axis position edit takes the other axis from the
  draft, else the composed cell, never the wire (the Look arm's rule). Spread posts
  `programmer/spread` with `write: false` and lands `written[].value` in the store, as
  `SpreadPopover` does for a Look layer.
- **The reads.** `useScopedRowValues` gains a cue arm: the draft over the cook, each cell's source
  from the cooked row's `layerId` / `layerSource` (null = own).

### 3.4 Children keep their uuids (backend, P3)

`writeCueChildren` replaces `deleteCueChildren` + `createCueChildren` on every cue write, as
`writeLookChildren` replaced Look's (`652128a`): reconcile by natural key, pair the n-th incoming item
of a key with the n-th existing one in `sortOrder` order, update a survivor in place and keep its
uuid, insert or delete the rest. The keys:

| Child | Key |
|---|---|
| Own row | `(targetType, targetKey, canonical property)` |
| Layer | `(lookId \| templateId)` |
| Effect | `(targetType, targetKey, canonical property, effectType)` |
| Hook | `(triggerType, scriptId)` |

The `CueAdHocEffectDto` gains a read-only `uuid` so *Update stage* and the FX table can name an
effect across writes. No `formatVersion` change: the archive already carries each child's uuid.

### 3.5 Edited since GO and Update stage (D4, D11)

- **The mark.** R1's hold registry (`fx/CueHolds.kt`) gains `editedSinceGo: Set<cueId>`. A cue write
  that changes what *Update stage* sends of a held cue — a row's value, an untimed layer or effect —
  adds it, and so does a Look or palette edit that moves the effects a live cue layers; the cue's
  first hold and *Update stage* clear it, and a Record or Update republish (`republishCueIfLive`)
  leaves it (§11, 21–23, amending this bullet as first written). Scenery and events do not set it:
  scenery is drawn from the stored rows at once, and events fire on GO only.
- **The wire.** `cueHolds.state` (R1 §3.4) carries `edited: true` on a held cue. Outbound only, as
  that frame is.
- **The route.** `POST /api/rest/projects/{id}/cues/{cid}/update-stage {dryRun?}` answers
  `{rows, effects: {edited, stopped, started}}`; `dryRun` is the same run, sending nothing, which the
  confirm reads for its counts. 409 `CUE_NOT_LIVE` for a cue no hold holds. Current project only and
  both roles, as a GO is.
- **Rows** republish through `cueLayer.replaceAssignments` at the cue's fade. Today that path snaps
  (Record and Update); the session gives it a fade and pins that a snap cue still snaps. Since R1.2
  there is no GO to borrow one from: a GO onto the live entry re-fires and snaps, a GO between two
  entries of one cue moves nothing, `CueCrossfadeDriver` keeps one fade per stack and would cut short
  that stack's own GO, and a cue held only loose has no stack. So the fade is the cue's own, keyed by
  cue: the stage's rows to the new cook, over the cue's fade and curve.
- **Effects** reconcile by uuid against the running instances the cue's holds spawned: a changed one
  through `applyEffectUpdate` with the stored Seconds, size, direction, runs and the spread's shape
  (`spread`, `wings`, `blocks`, `groups`, effects plan D4; id kept; phase kept but for a move
  between *Seconds* and a master, S15; a changed count runs from the update, effects plan session
  1), a removed one stopped, a new one spawned at the cue's priority. A changed layer set brings its Look's deferred effects in line the same way.
- **Events** are not fired and **scenery** is not re-run.

### 3.6 Blind holds the programmer's effects (D7)

- **What is held.** Every instance in the programmer priority band (`PROGRAMMER_FX_PRIORITY_BASE`):
  `programmerOwned` adds, the effects Include spawns, and every instance a programmer layer spawned.
  Cue, Look-in-cue and script effects are not the programmer's and keep painting.
- **How.** The tick keeps advancing a held instance's phase and skips its apply while
  `programmerStore.blind`, so leaving Blind lands it mid-phase, in step with the effects it was
  started beside. The suppression rule (`fx/EffectSuppression.kt`) is unchanged.
- **Leaving Blind** grows them from nothing over the Blind fade, like a press (effects plan §3.5,
  session 4); one over a value the programmer holds, and every one on a 0 s Blind fade, paints on
  the next tick at full (`docs/lighting-composition-model.md` §Fades).
- **Reporters.** The busk band's `BLIND` pill, the Show glyph's dot and `ProgrammerIndicator` say
  *values and effects held*; the FX tray and the programmer's FX band mark a held instance *held ·
  Blind*. `programmer.keyStack` lists a held programmer effect as `heldBack` with the reason.
- **Not drawn yet.** The Stage view's programmer sources do not draw a held effect: an effect exists
  only as output. §9 records it.

### 3.7 Cue in edit, on the Stage (D13)

- **The source.** The vis-source vocabulary (`output | outputProgrammer | programmer | nextGo`) gains
  `edit`. It is per window like the others; the window's *edit target* (an entry id, or a cue id for a
  loose cue) rides beside it in the Stage view's per-window store and in `windows.viewOptions`
  (`source: 'edit'`, `editEntry` / `editCue`), so the Screens sheet can point another window at it.
- **The channels** come from R1's entry-keyed preview (`GET …/cue-stacks/{sid}/preview?entryId=`,
  P2 of R1) or, for a loose cue, a route of its own (R1.2's preview takes only an `entryId` under a
  stack's path, composed against that stack's context), composed as a loose press; refetched,
  debounced at 300 ms, on every `cueListChanged` (it names no cue) and on a `cuesRecomposed` whose
  `cueIds` hold the cue (a Look or palette contents edit under it). Because edits save as they go (D10), the
  stored cue is the edit, at most one flush behind.
- **The toggle.** The editor's header *Show on stage* sets this window's source to `edit` with its
  cue, and lights while the window shows it; another editor's toggle moves it. *Output* puts the
  window back.
- **Scope.** Layer 4 and scenery, as Next GO draws them. Effects are not drawn (§8).

### 3.8 The wire

| Route or frame | Session | Notes |
|---|---|---|
| `POST /cues/{id}/duplicate {stackId?}` | 3 | Appends to the end of `stackId`'s list, or loose without one (D12). Replaces the client's `buildCueInput` copy. |
| `POST /cue-stacks/{id}/entries/{entryId}/move {toStackId}` | 3 | *Move to stack…*: the entry row moved whole to the end of the other stack — uuid, explicit number, follow and anchor kept (§11, 17). |
| `GET /cues/{id}/cooked` | 5 | Each row gains `sourceTargetType` / `sourceTargetKey`, the own row's or layer row's own target before group expansion. |
| `CueAdHocEffectDto.uuid` | 2 | Read-only. |
| `POST /cues/{id}/update-stage {dryRun?}` | 7 | §3.5. |
| `cueHolds.state` `edited` | 7 | R1's frame, one field. |
| Stage `source: 'edit'` + `editEntry` / `editCue` | 8 | `windows.viewOptions`, announced only under the Stage view. |

No WS inbound frame, no `formatVersion` change, no table.

## 4. UX

### 4.1 The editor's anatomy

- **Header**, one 40px chrome row: the number and follow (this stack's entry, P5, edited in place as
  the card's inline fields are, but written to the entry by its id), the name, fade and curve, the shared chip (R1 §4: *in 2 stacks*,
  listing the others on hover), the *Edited since GO* chip with *Update stage…* (D4), the save state,
  and *Show on stage* (D13).
- **Tabs**: *Values n · Looks n · FX n · Scenery n · Events n · Cue*. The tab is remembered per
  window (`sessionStorage cueEditor.tab`); a new editor opens on it.
- **Values**: the programmer's `FixturesTable` over `CueRowStore`, with the sheet kit's gestures —
  marquee, click to select, double click / ⏎ / typing to open the cell editor in its three forms,
  ⌫, *Set · Clear · Spread*. Its selection bar is the kit's `SelectionBar` with *+ Fixtures…*,
  *Whole rig* and *Own only*. A cell's edge is blue for *own* and the Look's teal with its name in the
  hover otherwise; a typed value over a Look-won cell becomes own and says *overrides Scrim · opaque
  v2* in its read-out. A group own row draws on the group's row. Cells a row cannot take are blank.
- **Looks**: `LookStack` (wide density, top wins), its handlers mapped by a cue host to a whole-list
  `layers` PATCH that restates every `sortOrder`. *+ Look* and *+ Palette* open the programmer's add-
  layer pickers targeting the Values selection; a layer's *Targets* takes the Values selection
  (`onSetTargets`, a new optional `LayerHandlers` member); *Open Look* leaves for the Looks library.
  The hand's *Place here* strip stays where it is.
- **FX**: one row per stored effect: target, effect, property, speed (a master or *Seconds*, S15),
  size, direction and runs (effects plan session 1), and its fire timing. A row opens
  `FxEffectFields` over the stored effect (`cueEffectDraftOf` / `withCueEffectDraft` in
  `fxEditorModel.ts`, carrying Seconds, size, direction, runs and the spread's shape as
  `withTemplateDraft` does, its `FxScope` from the effect's target — a `selection` target's head
  count through `selectionHeadCount`, effects plan D3), with the cue's *Delay · Interval · Random*
  under it. *+ Effect* opens `FxPicker` in draft over the Values selection (D17). A Look's deferred effects
  are listed under their Look, read-only.
- **Scenery**: `CueSceneryEditor`'s body. **Events**: `CueEventsEditor`. **Cue**: notes, script
  hooks (`CueTriggerEditor`) and the busk-page append, as `CuePropsPane` had them.

### 4.2 Card view

- The collapsed row is unchanged. Opened, the **action set** strip sits directly under it (§5), then
  the editor. The read-only `CueDetailContent` leaves the card; it stays the phone card's and the
  Prompt Book's read-out.
- Two cards can be open (the operator's and the live one, `useCueExpansion`); each has its own
  editor and its own grid selection. Keys go to the region with focus (§4.5).
- *Cue properties…* goes from the card (D15).

### 4.3 Table view

- The **selection bar** carries the action set after *Set · Clear · Spread* and before *Deselect*
  (library-sheets D11's rule); a one-record action is disabled over several rows with the reason.
- **The bottom pane** holds the editor for the one selected row (D5). Its height is this window's
  (`sessionStorage cueSheet.paneHeight`), set by a grip shaped like the busk view's `RigHandle`
  (magnetic only at its minimum, 160 px, and at half the column). It folds when the selection is not
  one row, and the sheet takes the height back.
- The pane's header names the stack beside the number (*Q1 · Act 1*), since the sheet above names
  only the cue.
- A row's Layers and FX read-outs select the row and open the pane on that tab, where today they
  open the card.

### 4.4 Phone

The phone card (`CueCardBody`: Show below 600 px, the busk view's Show tab, the Prompt Book's card)
gets a ⋯ button in its header with the action set in §5's order, *Update stage…* under *Record…*
while the cue is edited since GO, and the *Edited since GO* chip under its identity. Its body stays
a read-out (D9). The locked rule is §4.6's.

### 4.5 Keys and focus

Two grids can be on one page (two open cards, or the sheet and the pane). The sheet kit's and the
programmer's window listeners each claim keys today; the cue grid's listener claims a key only while
its **key region** (`data-key-region` on the editor) holds focus or took the last pointer press, and
the sheet's yields to it then. A pane or card that is not the region draws its selection dimmed.
`useSheetKeyboard` gains the region test; the programmer's own page has one region and is unchanged.

### 4.6 Locked (D8)

- The tables are read-only: the cells inert, as the cue sheet's are, and the Looks and FX tables'
  controls disabled.
- The action set is drawn whole. *Blind edit in programmer* works without unlocking; every other
  action is a press that asks to unlock (`onRequestUnlock`) where the lock is the operator's to lift,
  and is disabled with the reason where it is not (`canEdit` false). *Update stage* asks too.
- The split button's face is *Blind edit* whenever the show is running (D6), so locked it is always
  the one face that works.

### 4.7 The Cues table's editor (R1 §3.7)

R1's Cues table opens a cue in its first entry's stack, and a loose cue in a read-only sheet. Once
session 4 lands, that sheet mounts `CueEditor` (no entry, so no number or follow), so a loose cue is
edited in the same editor. Session 4 landed first (`a4ab679`), so R1 session 5 makes the swap.

## 5. The action set

One component, `CueActions`, takes the cue, the entry it was opened from (or several rows), the lock
and the run state, and draws in three hosts: the card's strip, the cue sheet's selection bar and the
phone card's ⋯ menu. Semantics are R1's.

| Action | Does | Over several rows | Locked |
|---|---|---|---|
| **Edit in programmer ▾** | Include the cue, then open the programmer with *← Show* in its source box (D16). Face per D6. | disabled, *one cue at a time* | asks to unlock |
| **Blind edit in programmer** | Blind on (at the programmer fade), Include, open the programmer. Nothing reaches the stage until Blind goes off (D7). | disabled | **works** |
| **Load into programmer** | Include and stay. Live unless Blind is already on, and the button's title says which. | disabled | asks |
| **Record…** | The Record sheet targeting this cue; it writes the shared cue. | disabled | asks |
| **Duplicate** | `POST /cues/{id}/duplicate {stackId}`: a new cue appended to the end of this stack, opened (D12). | each, in row order | asks |
| **Remove from stack** | Deletes the entry; the cue stays, loose if that was its last. No confirm (R1 §4). Removing the live entry leaves the stage alone (R1 D16). | each | asks |
| **Delete…** | Deletes the cue after R1's confirm listing every entry, pad, slot, anchor and binding (R1 D8). | one confirm for all (`POST /cues/delete`) | asks |
| **⋯** | *Add to stack…*, *Move to stack…* (R1 §4), *Copy link*. | each | asks |

- **Order and emphasis.** The split button is primary (amber while its face is Blind edit). The
  programmer group, a separator, then the stack group; *Delete…* is red. On the card strip and the
  bar, words fold to glyphs below the strip's measured rungs (session 3 measures them), the split
  button last.
- ***← Show*** in the programmer's source box returns to `/show/stacks/:stackId?cue=…&entry=…` for the
  entry the include came from: the include target's `entryId?` (R1 §3.5's row, which R1.4 added). The
  URL names the entry (`?entry=`, which `ShowPage` reads since R1.4), so a cue twice in one stack opens
  the right row; it is drawn only while that include is the current one. *Update* and *Revert* are
  unchanged.
- **What goes.** The card's *Remove* (a hard delete) became *Remove from
  stack* in R1.4, which also put *Add to stack…* and *Move to stack…* on the card; *Cue properties…*
  folds into the editor; the cue sheet's Layers and FX read-outs stop opening the card.

## 6. Implementation — eight sessions

Each session is one PR (P1). Sessions 1 and 2 need nothing from R1 and can land at once. Session 3
onward needs R1 sessions 1–4 (entries, holds, sharing and explicit delete), venue-visit S3 (the show
position and *Edit in programmer* opening the programmer) and S4 (the palette rename, so the new copy
says palette). All of them have merged.

### Session 1 — Blind holds the programmer's effects (backend + reporters) — done, `6f86273f`

- §3.6: the tick skips a programmer-band instance's apply while blind and keeps its phase;
  `keyStack`'s `heldBack` reason; the reporters' wording; the FX tray's and FX band's *held · Blind*.
- **Tests:** a busk effect, an included cue's effect and a programmer layer's effect paint nothing
  under Blind and paint mid-phase after it; a cue's own effect keeps painting under Blind; Record
  under Blind still captures the programmer's effects.
- **Docs:** `lighting-composition-model.md` §Blind, `fx-engineering.md` §"Programmer suppression and
  the priority band", the frontend CLAUDE.md's Blind paragraphs.

### Session 2 — a cue's children keep their uuids (backend) — done, `626ee21a`

- §3.4: `writeCueChildren` on every cue write (REST POST, PUT and PATCH, Record's CREATE and
  UPDATE_EXISTING, `create_cue` and `build_cue_stack`; Update already upserted in place),
  `CueAdHocEffectDto.uuid`.
- **Tests:** a one-value PATCH keeps every other child's uuid; a key held twice pairs in
  `sortOrder` order; the sync export of a one-value edit changes one record; the round trip.
- **Docs:** `cues-engineering.md` §"Data Model", `sync-engineering.md` (what a cue write does to
  uuids).

### Session 3 — the action set (frontend + duplicate route) — done, `ebd029f`

- §5: `CueActions` in the card strip, the cue sheet's bar and the phone ⋯; the split button and its
  run-state face; Load; Record; Duplicate on its new route (this takes the duplicate route from R1
  session 6, which then builds only the cross-project copy, as R1 §3.8 now notes); Remove from stack; Delete… on R1's
  confirm; ⋯; the locked rule; *← Show*.
- **What R1.4 left.** The card already has *Remove from stack*, *Add to stack…* and *Move to stack…*
  (`CueCardEditor.tsx`); this session moves them into `CueActions`. No cue-delete confirm exists yet:
  the stack-delete dialog lists a dry run's uses through `cueDeletePreview` (`DeleteStackDialog.tsx`),
  and this session or R1 session 5, whichever lands first, builds D8's cue confirm on it for the other
  to reuse.
- ***Move to stack…* drops the entry's place.** R1 §4 makes it add then remove, so the entry's
  anchor, explicit number and follow are lost on the way (R1.4 shipped it so). Ask Chris whether the
  action set keeps that or moves the entry whole, before building the ⋯ menu. Answered: §11, 17.
- **Tests:** the face flips with the run state; locked, Blind edit sends Blind then Include and every
  other action asks to unlock; Blind edit with a cue holding an effect sends nothing to the stage
  (with session 1); several rows disable the one-cue actions with the reason; Duplicate appends;
  Remove from stack leaves the cue in the Cues table; *← Show* lands on the entry.
- **Docs:** the frontend CLAUDE.md's §"Cues, Stacks & Triggers" and §"The show-editing lock";
  CLAUDE.md's cue endpoints.

### Session 4 — the editor and its two hosts (frontend) — done, `a4ab679`

- §4.1–4.3: `CueEditor` (header, tabs, the tab memory), the card host, the table's bottom pane with
  its grip and its fold; Scenery, Events and Cue tabs from the existing editors; `CuePropertiesSheet`
  retired and its two outside doors (the FX tray's *on Q12*, *Moves with*) opening the editor in
  place through `OwnerEditor`; Values, Looks and FX as today's read-outs for now.
- **The runner walks by entry.** `store/runnerSlice.ts`'s optimistic GO and BACK still move by cue
  id (R1.4 keyed the rows, standby and expansion by entry, not the runner). In a stack holding one cue
  twice, the optimistic next can point at the wrong place until the desk's `cueRunStateChanged`
  corrects it. Both hosts read it, so this session keys it by entry.
- **Tests:** one row selected opens the pane, two fold it; the pane's height survives a reload in
  the window; the card and the pane mount the same editor; the header's entry fields write the
  entry and the rest write the cue (P5); *on Q12* opens the editor; a GO and a BACK in a stack holding
  one cue twice land on the right entry before the desk answers.
- **Docs:** the frontend CLAUDE.md's §"Sheet kit" (the cue sheet), §"Cues, Stacks & Triggers".

### Session 5 — the Values grid (frontend + cooked route) — done, `ec09327`

- §3.3 and §4.1's Values: `CueRowStore`, the `cue` arm of `EditorContext` and `useCellWriters`, the
  scoped reads with their source edges, D14's rows, *Own only*, Spread with `write: false`, ⌫, Undo,
  the key region (§4.5); `/cooked` gains the source target (§3.8).
- **Tests:** P2's no-frame test over a full pass (set, colour, position, Spread, ⌫, Undo); a typed
  value over a Look-won cell becomes own and wins; ⌫ shows the layer again; a group own row edits on
  the group's row; a row's fade and move-in-dark survive an edit; Undo restores the list; two grids on
  one page take keys only in their region.
- **Docs:** the frontend CLAUDE.md (a section, "The cue editor"), `lighting-composition-model.md`
  (own rows edited in place).

### Session 6 — the Looks and FX tables (frontend) — done, `639fe8c`

- §4.1's Looks and FX: the cue host for `LookStack` with `onSetTargets`, the add-layer pickers,
  *Open Look*; the FX table, `cueEffectDraftOf` / `withCueEffectDraft`, the cue timing fields, the
  picker in draft (D17); a Look's effects read-only.
- **What is there.** The two tabs draw `CueLayersReadout` and `CueEffectsReadout`
  (`CueDetailContent.tsx`) inside `CueEditor`; this session replaces them there, and the phone card
  and the Prompt Book keep them (D9). Session 5 (`ec09327`) built `CueRowStore` over own rows alone,
  so this session gives the store its layers and effects lists, each flushed whole
  from the latest cue with its own Undo step (§3.3).
- **The add doors.** `AddLayerSheet` (`LayerPicker`) is the shared picker, with its timing fields on
  for a cue (`allowTiming`); it takes one `preselectedTarget`, so it learns to take the Values
  selection's targets (R3.8 later moves both doors to the apply plan's picker and retires the sheet).
  `FxPicker` has no draft arm: its tap runs a programmer effect. The draft arm writes the stored
  effect with the tap's start — `startingSpec`, the step effects' seeded lists (`lib/stepEffects.ts`,
  R4.7), D16's master through `useSpeedMasterForCategory` — on `effectTargetOf`'s target
  (`components/fx/selectionEffectTarget.ts`): one row as itself, several as one `selection` effect
  (effects plan D3, R4.3). The `FxScope` is `FxEditor`'s own derivation, lifted to be shared.
- ***Open Look* gets an address.** `/looks` opens no single Look today (a palette has `?edit=`,
  R3.3). This session gives it one, `/looks?edit={id}`, and *Open Look* navigates there (§11, 18);
  opening it in place, as `stage/OwnerEditor` does for *Moves with*, was declined.
- **Tests:** a reorder restates every `sortOrder` and keeps uuids (with session 2); a disabled layer
  is stored; an effect added in draft sends no `fx.add`; a swap replaces it; an effect's Seconds,
  size, direction, runs and spread shape survive an edit and a reorder; an effect added over two
  rows is stored as one `selection` effect; P2's no-frame test over both tabs.
- **Docs:** the frontend CLAUDE.md's "The cue editor"; `fx-engineering.md` (a cue effect edited in
  draft).

### Session 7 — Edited since GO and Update stage (backend + frontend) — done, `f378ee9`

- §3.5: the mark on R1's holds, `cueHolds.state`'s `edited`, the route with its `dryRun`, the cue's
  own fade, the effect reconcile; the chip on the card, the row, the pane and the
  phone, and the confirm.
- **An instance names no stored effect.** `FxInstance` carries its `cueId` and `cueLayerId`, never
  the uuid of the stored effect it came from, so `CueHolds.bringOn` (and `CueTriggerManager`'s
  timed spawn) tags each ad-hoc instance with it, to reconcile against. An edit goes in place with no `targets`: since
  R3.2 a split of a cue's effect is 409 `FX_SPLIT_NOT_LOCAL`, and a moved target is a new uuid by
  §3.4's key, so it stops and starts.
- **Where it draws.** The chip and *Update stage…* join `CueEditorHeader`
  (`components/cues/editor/CueEditor.tsx`), the cue sheet's row and `CueCardBody`'s ⋯
  (`CueActions`). The confirm lives in `CueActionDialogsProvider` (`runner/cueActionDialogs.tsx`)
  beside Record and Delete, since a GO can collapse the card that opened it (R2.4).
- **Tests:** an own-row edit to a held cue marks it and a scenery edit does not; its first hold
  clears it, and a GO into it while another hold keeps it live does not; Update stage fades the rows
  over the cue's fade and snaps a snap cue; an edited effect keeps its id and phase, a removed one stops, a new one starts; events do not fire; `CUE_NOT_LIVE`.
- **Docs:** `cue-stacks-engineering.md` §"Runtime", `websocket-engineering.md` (`cueHolds.state`),
  CLAUDE.md's cue endpoints.

### Session 8 — Show on stage (frontend + preview)

- §3.7: the `edit` source, its announce keys, the Screens sheet's Source segment, the toggle in the
  header; the loose cue's preview route (R1.2's takes only an `entryId`).
- **Where the source lives.** `VisSource` is `hooks/useVisSource.ts`'s, per window; it is announced
  by `stageViewOptions`, applied by `applyStageViewOptions` and read on arrival by
  `consumeLaunchStageOptions` (`lib/stageViewpoint.ts`), and a Screens row's *Copy link* carries it
  as `source=` (`windowSetupUrl`). The edit target rides beside it in all four.
- **The Screens row cannot name a cue.** Its Source is `STAGE_SOURCE_OPTION`, an `enum` segment of
  two of the four sources (*Output · Next GO*, `lib/windowViews.ts`). The row offers *Cue in edit*
  **only while that window already shows one** (§11, 19); a cue `picker` was declined.
- **Off the rig.** The Stage view draws only the project on the rig (`RigGate`, Nav.2) and the
  preview route is `withCurrentProject`, so *Show on stage* is withheld, with the reason, for a cue
  the editor reads as another project's (`canEdit` false).
- **Tests:** the toggle sets this window's source and announces it; another editor's toggle moves
  it; an edit refetches the preview once per flush; the source draws nothing to output.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"Sources", `desk-screens.md`.

## 7. Migration

None. No table, no column, no `formatVersion` change: session 2 keeps the uuids the archive already
carries, and every other change is a route, a field on an answer or a per-window fact.

## 8. Explicitly out of scope

- **Pressing a palette onto cue cells.** How a palette, a Look or an effect reaches a selection is
  R3's (venue-visit R3). R3 has answered it ([`apply-model-plan.md`](apply-model-plan.md) §3.6): its
  session 8 adds `POST /cues/{id}/layers/press` on `fx/LayerPress.kt` (R3.1) and the bands and
  picker to the Values grid and the Looks tab, after sessions 5 and 6 here.
- **Editing a Look's contents from inside a cue** (D3).
- **A row's own fade and move-in-dark.** Kept on every write and shown on the cell's hover; editing
  them is `FU-CUE-ROW-TIMING` (§9).
- **Values tables on a phone** (D9).
- **Drawing effects in *Cue in edit*.** The preview composes Layer 4; drawing effects there needs an
  effect preview the desk does not have.
- **MCP tools.** `update_cue` and friends already write the cue; an MCP *Update stage* waits for a
  use.

## 9. Follow-ups to record

In [`followups.md`](followups.md), when the session that creates them lands:

- `FU-CUE-ROW-TIMING`: editing an own row's fade and move-in-dark in the Values grid (session 5).
- `FU-BLIND-FX-DRAWN`: the Stage view's programmer sources drawing a held programmer effect
  (session 1).
- ~~`FU-VENUE-BANNER-PHONE`~~: the venue-behind banner on a 390 px phone puts its buttons beside a
  one-word column; stack them under the text below `sm` (seen on the desk during R2; any session).
  Not needed: Nav.4 (`8ac4817`) gave the sentence a basis, so on a phone the buttons wrap under it.

## 10. Verification

Beyond the unit suites, at the desk on Sleeping Beauty:

- **Session 1.** Busk a Pulse, turn Blind on: the pulse stops on the rig and the pill says effects
  are held; Blind off, it paints in step with a second pulse that ran unblinded.
- **Session 2.** Change one value in Act 1 Q1 through the API; the sync status lists one changed
  record.
- **Session 3.** With the show running and locked, Blind edit Q4 and confirm nothing moves on stage;
  *← Show* returns to Act 1 with Q4 open. Duplicate Q3 and find the copy at the end of Act 1.
- **Session 4.** Open Q1 in the table, drag the pane, reload, and find it the same height.
- **Session 5.** In Q1, set the Orbits' dimmer to 80 % over the Scrim Look and watch the rig not move;
  GO into Q1 and see 80 %.
- **Session 6.** Reorder Q1's layers and switch one off; add a Pulse in draft; nothing runs until GO.
- **Session 7.** GO into Q4, edit its dimmer, see *Edited since GO*, press *Update stage* and watch
  it fade.
- **Session 8.** Point the Stage view at Q7 while Q1 is live and see Q7's state drawn.

## 11. Questions

Answered by Chris, 2026-10-14, in three rounds:

1. **What does the Values table edit?** One grid of composed values, edits writing own rows (D2).
   The programmer's scope band, and own rows only, were declined.
2. **What can be changed about a Look layer?** The layer, not the Look (D3). Editing the Look in
   place, and read-only layers, were declined.
3. **A live cue edited in place?** Marked, with an explicit *Update stage* (D4). "Mark it, send
   nothing" and "read-only while live" were declined.
4. **Where is the table view's editor?** A bottom pane (D5). A right-hand panel, an inline row and
   "the table opens the card" were declined.
5. **Edit and Blind edit on one button?** A split button whose default follows the show (D6).
6. **Effects under Blind?** Fix Blind (D7). Leaving effects out of Blind edit, and leaving it, were
   declined.
7. **The action set while locked?** Blind edit works, the rest ask to unlock (D8).
8. **The phone card?** Actions, not tables (D9).
9. **Saving?** As it goes, with Undo (D10). A draft with Save / Revert was declined.
10. **What does Update stage send?** Rows and effects at the cue's fade (D11).
11. **Where does Duplicate put the copy?** At the end of the stack (D12), over R1 §3.8's "after the
    source".
12. **Should the Stage draw the cue in edit?** Yes, a vis source (D13).
13. **Which fixtures does the grid list?** The cue's, plus *+ Fixtures…* and *Whole rig* (D14).
14. **Cue properties?** Folded into the editor (D15).
15. **The way back from the programmer?** *← Show* in the source box (D16).
16. **Adding an effect?** The picker, in draft (D17).

Answered by Chris, 2026-10-16, during session 3:

17. ***Move to stack…* drops the entry's place?** No: it moves the entry whole, on a route of its own,
    so its number, follow and prompt-book anchor go with it. Keeping R1.4's add then remove was
    declined, and so was copying the number and follow onto a new entry (which still lost the anchor).

Answered by Chris, 2026-10-17, at venue-visit course check K3:

18. ***Open Look* from the cue editor?** `/looks` gains an address, `?edit={id}` as a palette's, and
    *Open Look* goes there. Opening the Look in place over the cue was declined.
19. **Can a Screens row point a window at the cue in edit?** Only while that window already shows
    one. A picker over every cue was declined.

Answered by Chris, 2026-10-17, during session 7 (each amends §3.5):

20. **Update stage and a cue's timed effects and layers?** Left to the next GO, whose timers re-arm
    them (`FU-UPDATE-STAGE-TIMED`). Re-arming them on the press was declined.
21. **Does a Record or Update republish clear the mark?** No: it moves the rows and not the effects,
    so the mark stays until the first hold or *Update stage*. Clearing it there, and marking rows and
    effects apart, were declined.
22. **A Look or palette edit that moves its effects?** It marks every live cue layering it. Leaving
    such cues unmarked, as §3.5 had it, was declined.
23. **What raises the mark?** Only what *Update stage* sends: a row's value, an untimed layer, an
    untimed effect. A row's own fade or move-in-dark and a timed layer or effect do not.
