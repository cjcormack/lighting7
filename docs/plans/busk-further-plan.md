# Busk view, further — a built rig, a window's focus, a tabbed sheet, spread, cells

> **Document status: IN PROGRESS, 2026-09-18 — sessions 1 and 2 (lighting7) landed, `b263ca5`
> and `6e2cc72`; sessions 3, 4 and 5 (lighting-react) landed, `e7b540a8`, `51e9f8c1` and
> `db9542cd`; sessions 6 and 7 not started.** Each landed session's heading in §5
> carries its hash, and a *session N amendment* beside any §3 sentence it proved wrong is the
> current truth over the sentence it follows. The visual
> design is settled and checked in beside this plan at
> [`busk-further-design/`](busk-further-design/INDEX.md) — twelve static artboards: the busk view
> in play with the new rig band and side sheet, the rig in edit mode, focus and the two-screen
> flow, the Screens sheet's per-window options, the side sheet's three tabs, Spread, multi-head
> fixtures in a selection, a survey of five desks, the model and the wire, the extra ideas, and how
> it folds on phones and tablets. The live canvas at
> <https://claude.ai/artifact/FViMcdxsaKHautb7sWcmQ2> is a convenience copy, private to Chris;
> the checked-in files are the authority. This document is the engineering half: the model, the
> decisions and their reasons, and the session split. Where wording here and the artboards
> disagree, this plan wins on behaviour and the artboards win on layout and copy. Every open call
> the canvas carried was answered by Chris on 2026-09-17 and is recorded in §2 and §11. A plan
> review on the same day (fifteen findings, all folded in) corrected session 1's account of the
> element gap, moved the parent↔cell coverage rule onto the desk, fixed the session ordering and a
> dozen names; §12 lists what it changed.

## 1. Context

The busk layout plan gave the operator a page of banks they build; the multi-screen plan gave the
desk a registry of windows, a shared selection with a family mask, a per-tab follow flag and the
hand. The brief for this plan takes both further, on the busk view specifically:

- **Configure which fixtures and groups the busk view offers, and position them.** Today the
  target band is every group then every fixture, in one two-row column-flow grid that scrolls
  sideways. A rig with thirty patches is a long strip with nothing the operator chose on it.
- **Focus on the pads or on the selection.** Today both regions are always drawn, at fixed
  proportions, and the second screen at a desk shows exactly what the first does.
- **Re-proportion the two regions.**
- **Collapse the speed rail.** It is 288px and always there above `md`.
- **A colour picker as an alternative to the speed rail.**
- **Spread a value across a selection** — what every other desk calls fan.
- **Multi-head fixtures in selections.** The desk selection can already carry an element key (an
  element row publishes as `{type:'fixture', key: element.key}`, `rowModel.ts:242-255`), and more
  of the press path resolves one than the follow-ups suggest: `Fixtures.untypedGroupableFixture`
  answers an element for an element key (`show/Fixtures.kt:190`), `templateApply.membersOfTarget`
  and the value-apply loop go through it, `TemplateResolver` takes a `GroupableFixture` and so
  reads the element's own emitters, and `TargetCoverage.expand` passes an element DTO through
  unchanged. What does **not** resolve one: `CueComposer.applyLayer` (`:696`, skips a row with an
  `elementKey`), `LookRegistry.expand` (two `continue`s), `lookRecord.kt` (writes no `elementKey`;
  expands through `expandTargetsToFixtureKeys`), `EffectSpawner.createFixtureTargetForCue`
  (`untypedFixture(key) as? Fixture`, register-only — an effect template on a cell resolves
  nothing), and the bundled W/A/UV path (`FU-FX-ELEMENT-BUNDLED-COLOUR`). *Session 1 amendment:*
  `expandTargetsToFixtureKeys` already kept an element key as its own row; the record drop was
  `collectProgrammerEntries`' `ELEMENT_TARGET` skip, and `FxEngine.buildExpansion`'s fixture arm
  (`untypedFixture`) was a further drop site for an effect on a cell. And nothing defines
  whether a whole-bar layer *covers* a cell press: `pressWouldRelease` compares expanded DTOs by
  equality, so a cell press under a whole-bar layer never reads as lit.

What the design settled, and what this plan builds: the target band becomes a **rig** the operator
builds (rows of tiles, a document like the page's); **focus**, the **split**, and the **side
sheet's tab** become per-window facts on the page-follow model, settable from the Screens sheet
through one generic command; the speed rail becomes a **three-tab sheet** (Speed · Colour ·
Spread) with a 44px fold; **Spread** is resolved on the desk; and a **cell** is a target the desk
can press, drawn as pips on the tile, narrowed by a Cells chip. Chris chose to land the element
arm **first**, so nothing in the client ever draws a cell it cannot press.

A survey of MagicQ, grandMA3, Titan, Hog 4 and Eos is on the `Survey` artboard. The short form:
every serious desk has moved from a list to a *built* selection surface (MA layout views, Eos
magic sheets, Hog plots, MagicQ execute regions), fan curves are best named the way Titan names
them (Line · Mirror · Arrow · Wings), and sub-fixtures are either a *mode* (MagicQ's element mask)
or a *property of the selection* (Eos's `1.3`, MA's MAtricks, Hog's buddying). The busk view is a
touch surface with no command line, so the choice is made for it: on the tile, in the selection,
never a mode.

## 2. Decisions taken

The five open calls on the canvas and the seven scope questions were answered interactively on
2026-09-17. Each is stated here with its reason; the artboards are drawn to these.

- **D1 — The rig is a document, per project, and an empty rig is today's band.** Rows of tiles,
  each tile a group, a fixture, or a fixture's cell. One rig per project, not per page: the
  two-screen flow this exists for is a colour page on one screen and a position page on the
  other, pressed onto **one** selection, and a rig per page would give those screens two
  selection surfaces. A page that wants fewer targets is a row the operator scrolls to. An empty
  rig renders exactly what `TargetBand` renders today — every group, then every fixture — so there
  is no migration and no first-open generator. *(Chris: one rig per project.)*
- **D2 — Rows, not coordinates.** The same reasoning as the busk layout's D2: a coordinate system
  nothing else reads is a second model. *Plot* — tiles placed by the patch's `stageX` / `stageY`
  — is recorded on the Ideas board and is **out of scope** for this plan. *(Chris: keep scope as
  drawn; no extras.)*
- **D3 — A multi-head tile decides how it shows its cells.** `cell_mode` on the tile: `PIPS`
  (default: the whole fixture, cells drawn as pips that select individually), `WHOLE` (no pips),
  `PER_CELL` (one tile per cell, the fixture's own tile absent), `HALVES(n)` (n tiles, each a
  contiguous run). Eos's `.0` vs `.n` and MA's sub-fixture layout, stated once on the tile rather
  than in a mode.
- **D4 — One edit mode.** *Edit layout* already exists; the palette gains a **Rig** tab beside
  **Library**, and the rig band takes drops while the page does. Edit mode forces Split for its
  duration and restores the window's focus on Done. *(Chris: one edit mode, Rig tab.)*
- **D5 — Focus, the split and the sheet are per-window facts** on `deskFollow.ts`'s model:
  per-tab `sessionStorage` through `createSyncStore` (`lib/syncStore.ts`, its `storage` argument),
  never the desk's, never `localStorage` (two desk screens are two windows of one profile). Launch
  params `?focus=` and `?sheet=` are latched once per tab on `buskPageFollow.ts`'s model — the raw
  string read at mount, a tri-state *decided* flag — and mirrored back with `replace`. (Not
  `windowIdentity.ts`'s `consumeLaunchParam`, which is private and reads only `?window=`.)
- **D6 — The split is in whole rows, snapped.** The handle shows 1…N complete rig rows; dragging
  past the last is Rig focus, dragging to none is Pads focus, so the segmented control and the
  handle are one setting. A half row of tiles is useless. *(Chris: rows, snapped.)*
- **D7 — One side sheet, three tabs, one fold, one fact.** Speed is `BuskSpeedRail` unchanged;
  Colour and Spread are new; the fold is 44px and keeps the beat, master 1's tempo, the three tab
  glyphs and the selection's colour as a dot. The sheet is **one** fact, `busk.sheet ∈ none |
  speed | colour | spread` — `none` is the fold — which is what the artboards' descriptor and the
  copied link (`sheet=none`) already say. Below `md` the rail is not drawn, as today; the sheet
  is a bottom sheet (upright phone) or a right-hand overlay (short viewport) through the cell
  editor's own three forms, and on a phone it carries **Colour and Spread only** — Speed is the
  ShowBar's chip there, as it is now.
- **D8 — The Colour tab writes literals to Local, and only that.** Every drag is
  `programmer.setColour` per selected target (`ProgrammerSocket.kt` takes `targetType` /
  `targetKey` through `TargetRef.ofOrNull`, group arm included) — what a template *click* and a
  colour cell do. *Session 5 amendment:* a group is one group write only where its members agree
  on emitters; otherwise one write per member carrying `sourceGroup`, because `resolveColour` writes
  a group colour verbatim per member and adds white only on a `WithWhite` head, so pure white as one
  group write over a mixed group would black out its RGB members. No layer arm (a picked colour has no library referent for a layer to follow);
  *Save as template…* through `POST /templates/from-programmer` is the route to something
  trackable. The family mask is not consulted, as for any value write; the tab's header says what
  it is about to do and reads the family pill. *(Chris: literals only.)*
- **D9 — Spread resolves on the desk: `POST /programmer/spread`.** The client sends two intents,
  a curve, an order, parts and an over-switch; the desk resolves per head through
  `TemplateResolver`, writes one literal per head into Local, and answers what it wrote. The
  client never lerps: only the desk knows a group's member order, each head's range, which cells a
  fixture has, and what a colour means on a head with amber — the same rule that keeps
  `templateIntent.ts` a serialiser. Curves are Titan's four; order is a `DistributionStrategy`
  name resolved through its `byName`. *(Chris: on the desk.)*
- **D10 — A spread is a result, not a template.** *Save as Look…* is `record-look` over the
  selection. A "spread template" would need a second grammar and a resolver that knows the
  selection's order at cook time, which no template does.
- **D11 — A cell is not a new target kind, and the desk owns the coverage rule.** `CueTargetDto`
  stays `{type, key}`; a cell is `{type:'fixture', key:<elementKey>}`, the shape `rowLocateTarget`
  already publishes, and element keys stay opaque (`rowModel.ts`'s rule: never parsed). What
  changes is the other side of the press — the four drop sites and the bundled-colour path named
  in §1 — **and one rule stated once, in `TargetCoverage`: a parent covers its cells.** A layer on
  the whole bar covers a press on four of its cells (that press releases nothing and reads *all*);
  a layer on four cells does not cover the whole bar (that press adds). `pressWouldRelease`,
  `TargetCoverage.narrow` and `appliedState`'s extents read it, and the client's `lookPresence.ts`
  keeps reading the desk's resolved `applied` state as it does today — no browser copy of the
  rule, which is the pads' own rule (§"The pads still go through toggle"). **The element arm lands
  first**, as session 1, so pips are pressable from the day they draw. *(Chris: land the element
  arm first.)*
- **D12 — The sub-selection is the desk's rule.** The Cells chip — five on its face (All · Odd ·
  Even · Next · Prev, as `Main` draws it), the rest in its menu (1st half · 2nd half · Invert ·
  Masters only, as `Cells` lists them) — rewrites the selection's *targets* and nothing else,
  through one desk op, `selection.subselect {mode}`, so a MIDI button and the chip share one rule
  and *Next* walks **rig order**, which lives on the desk now. An unlinked window mirrors the rule
  client-side (`lib/cellsSubSelection.ts`, pinned against the server's test fixtures the way
  `templateIntent.test.ts` pins its grammar) over the rig document and the groups' member lists it
  already fetches; it is the same split `deskFollow.ts` already makes.
- **D13 — The Screens sheet gets per-view options, generically.** A busk row shows Focus, Sheet and
  Page beside the view picker; each entry in `lib/windowViews.ts` carries an optional `options`
  descriptor and the row renders whatever its current view contributes, so the sheet never learns
  the word busk. The write is one keyed broadcast command, `windows.viewOptions {targetId, view,
  options}`, applied by the named window to its own tab facts. **Page is settable** on the row: a
  remote set unlinks that window onto the page exactly as arriving with `?page=` does. Selection
  follow stays read-only there, as it is. *(Chris: settable.)* The test for what goes on the row:
  can setting it remotely lose something the operator at that window built? Focus, sheet and page
  cannot; unlinking the selection can.
- **D14 — The MIDI binding targets are in scope.** `BuskFocusSet`, `BuskSheetToggle`,
  `SelectionNext`, `SelectionPrev` and `SelectionCells` are BUTTON targets in `BindingTarget`,
  mirrored in `lib/surfaceDrop.ts` and the surface library, with health arms. A window-addressed
  target names the window by its **registry name**, since a binding cannot hold a socket-minted
  row id; a name matching no connected window is a no-op with a log line and a `missingWindow`
  health, **re-evaluated when the registry changes** (today's evaluator re-runs only on the
  fixtures-change hooks). *(Chris: include the binding targets.)*
- **D15 — Rig focus replaces the narrow-width target sheet.** `TargetList` / `TargetListItem`,
  `TargetBand.test.tsx` (which imports them) and *Pick targets…* are deleted; below `md`, Rig focus
  is the same list with rows and cells, and a tap toggles. `BuskingView`'s `isDesktop` query stays.
  *(Chris: replace it.)*
- **D16 — Backend first.** Sessions 1 and 2 are lighting7 only; the client sessions follow and
  each finds its routes already there. *(Chris: backend first, then client.)*
- **D17 — The extras stay on the Ideas board.** Target pads and special pads as new pad kinds,
  flash on hold (`FU-BUSK-MOMENTARY`), hold a rig tile to drag its level, save selection as group,
  plot arrangement, and focus from the hardware beyond D14's targets are **not** in this plan.
  Three things are declined outright and recorded as such: a rig per page, free positioning, and
  a programmer per window (`FU-PROG-PER-USER`).

## 3. The model

### 3.1 Schema — the rig

Two tables in `models/buskRig.kt`, in the `buskLayout.kt` style: no `ReferenceOption` cascades,
hand-rolled deletes, a `CHECK` for the exactly-one reference.

```
busk_rig_rows   id, project FK, name varchar(64), sort_order int, uuid
                uniqueIndex(project, uuid)
busk_rig_tiles  id, row FK, sort_order int, uuid,
                group FK (nullable) | patch FK (nullable),
                element_key varchar(128) nullable   -- only with patch: a tile dragged in as one cell
                cell_mode varchar(16) = PIPS          -- PIPS | WHOLE | PER_CELL | HALVES
                cell_split int nullable               -- HALVES only: n
                label varchar(64) nullable
                CHECK busk_rig_tile_exactly_one_ref (group xor patch)
```

`BuskRigCellMode` is an enum. A tile whose `cell_mode` is `PER_CELL` or `HALVES` is *one* stored
tile that the read side **expands** into its cell tiles; `element_key` is set only on a tile the
operator dragged in as a single cell from the palette. A group tile has no cell mode. A tile
referencing a patch that is `stageHidden` still renders (the rig is the operator's, the stage flag
is the stage's); the palette dims it.

### 3.2 The write boundary — `PUT /projects/{id}/busk/rig`

Whole document, the busk layout's D10 exactly: one PUT per gesture, ids read once at commit, rows
and tiles addressed by position, the server refuses an empty row (`BUSK_RIG_INVALID`), a dangling
ref (`BUSK_RIG_REF`), an id that is not the project's (`BUSK_RIG_IDENTITY`), a blank row name, a
`HALVES` with `cell_split < 2` or greater than the fixture's element count, and an `element_key`
that is not one of the patch's elements (validated against `elementGroupProperties` the way
`PersistedFixtureReferenceValidator` does). Rows and tiles are renumbered dense on write.

**Three delete paths sweep tiles** (*session 2 amendment:* five, plus a rename — the universe-config delete cascades patches and the project delete both sweep too, and a patch **rename** sweeps that patch's cell tiles, because a stored element key embeds the parent's key and the desk never parses one, so it can only be checked against the live fixture, which after the rename has different keys), each inside its own transaction, in the shape of
`deleteBuskPadsReferencing`: the group delete (`routes/projectPatchGroups.kt`), the patch delete
(`routes/projectPatches.kt`), and **the project importer**, which deletes `fixtureGroups` and
`fixturePatches` when replacing a project (`sync/ProjectImporter.kt:266-270`) and sweeps busk
pages already (`:243`) — the rig must be wiped before those two deletes or the new FKs block them.

`GET /projects/{id}/busk/rig` answers `{rows: [{uuid, name, tiles: [{uuid, kind, group?, patch?,
elementKey?, cellMode, cellSplit?, label?}]}]}` with the group and patch **summaries embedded**
(`GroupSummaryDto`, `FixturePatchDto`'s name/key/elements), so the band draws from one read.
An empty `rows` is the show-all fallback, answered by the **client** (`effectiveRig`), not the
server — the server stores what the operator built and nothing else. *Session 3 amendment:*
`GroupSummaryDto` carries **no id**, while the PUT names every group tile by `groupId` — a kept
tile as much as a new one — so the client resolves a group through the patch list's
`groups[].id` (`rigIdsFromPatches`) and refuses, by name, a group with no patched member before
the PUT. Embedding the group's id on `BuskRigTileDto` (or on `GroupSummaryDto`) is the fix, and
lighting7's to make; until then a memberless group cannot be placed on the rig. Also: an empty rig
arrives as `{}` (the converters omit a defaulted empty list), so `rows`, `tiles` and a patch's
`elements` are optional on the client.

`busk.rigChanged` is one keyed broadcast frame with no payload (there is one rig, so no id to key
on); `store/busk.ts` invalidates `BuskRig`, which joins `REST_TAG_TYPES` (`store/restApi.ts`) so the
reconnect resync covers it. It fires for every write and for a group or patch delete that took
tiles off.

### 3.3 The read side — rig order

`state/BuskRigOrder.kt` answers the **effective rig order** the desk needs for D12's *Next* /
*Prev* and D9's `LINEAR` order: rows in `sort_order`, tiles in `sort_order`, a group expanded to
its members in member order, a `PIPS` fixture as one step (or its cells when `over = CELLS`), a
`PER_CELL` fixture as its cells. With an empty rig it is every group then every fixture, which is
what the client's fallback draws — the two must agree, and `BuskRigOrderTest`'s fixture is what
`buskRig.test.ts` pins `effectiveRig` against. `DeskSelection` is constructed today with only
`fixtures: () -> Fixtures?` (`state/DeskSelection.kt:13`, wired at `State.kt:630`); `subselect`
needs a rig-order provider added to that constructor, so **the rig tables and the op ship in the
same session**.

### 3.4 Per-window facts (client)

`lib/buskWindow.ts`, on `createSyncStore({…, storage: sessionStorage})`:

```
busk.focus      'split' | 'pads' | 'rig'                     // default split
busk.rigRows    number                                       // rows shown in split; clamped to the rig
busk.sheet      'none' | 'speed' | 'colour' | 'spread'       // default: see the fold ladder
```

Defaults follow the surface (`Tablets` artboard's ladder): `sheet` defaults to `speed` where
docking the rail leaves the page body ≥ 600px (desktop, iPad landscape) and to `none` where it
would stack it (iPad portrait) or where the viewport is short; `focus` defaults to `pads` on a
short viewport. *Session 4 amendment:* the "leaves the page body ≥ 600px" test is answered by
Tailwind's `lg` width query (1024), which partitions the ladder exactly, rather than by measuring
the body — the default has to be answerable off the busk view, since the announce carries these
values from `Layout`, where there is no body to measure; and `rigRows` takes a default of its own
from the same ladder, three rows on a desk screen and two where cramped (750) or below `lg`. The short and cramped queries are **duplicated per site by convention**
(`CellEditorSurface.tsx` keeps them private; `shortViewport.test.ts` pins every copy's source
text), so `buskWindow.ts` carries its own copies and the test gains them. A default is only a
default: once the window has chosen, the tab fact wins. `?focus=` and `?sheet=` are latched once
per tab on `buskPageFollow.ts`'s model (a reload is never an arrival). Edit mode stays in the
Redux slice and forces Split while on.

### 3.5 The wire — three additions

- **`windows.viewOptions {targetId, view, options}`** (in) → `WindowRegistry.Command.ViewOptions`
  → the same keyed broadcast every socket receives (D11 of the multi-screen plan); the named window
  applies `options` to its tab facts **for that view only** (a busk options frame arriving at a
  window on the Prompt Book is ignored) and re-announces. `WindowsAnnounceInMessage` gains an
  optional `viewOptions: Map<String, String>? = null` — the socket `Json` is the bare default
  (`plugins/Sockets.kt:55`), so an optional field **with a default** is safe for a client that does
  not send it, and the backend lands first (session 2); `windowsApi.test.ts`'s pinned key set
  (`['type', windowId, name, view, fullscreen, follows]`) becomes six keys plus `type` when the
  client sends it (session 4). `WindowRegistry.Window` gains `viewOptions` and `windows.state`
  carries it back.
- **`POST /projects/{id}/programmer/spread`** — request `{targets, families?, property, from, to,
  curve, order, parts, over, fadeMs?}`: `from`/`to` are serialised `TemplateIntent`s (a colour +
  policy, a percent, a position in degrees, a level), `curve ∈ LINE | MIRROR | ARROW | WINGS`,
  `order` a `DistributionStrategy` name via `byName` (`LINEAR` is rig order; `RANDOM` takes its
  seed from the request or 0), `parts ≥ 1`, `over ∈ HEADS | CELLS`. Response `{written: [{target,
  propertyName, value}], skipped: [{target, reason}], skippedFamilies}`. Each write is an ordinary
  Local entry (`ProgrammerStore.put` per key, with the programmer fade), so it lands in
  `programmer.entryChanged` frames, Record captures it, Blind previews it. A `tmpl:{uuid}` in
  `from`/`to` resolves the template's colour generically, as an FX colour reference does. Colour
  interpolation needs `TemplateResolver.mixColour`, which is private today; session 2 exposes the
  Lab-space mix as `internal`. *Session 2 amendment:* `mixColour` is the per-head emitter split
  and stays private; the session added a new `internal mixLab` (with its `fromLab` inverse) for
  the interpolation, so the two stay separate. `parts` cuts the ordered heads into that many
  contiguous fans, each its own *from → to* (Titan's reading), rather than repeating a continuous
  curve — four heads in two parts is `0 · 1 · 0 · 1`; a property outside the mask answers 200
  with `skippedFamilies` rather than a 400, the Look press's shape; and the four curves' maths,
  which the plan never wrote down, is: `LINE` `t = x`, `MIRROR` `|2x − 1|` (centre at *from*),
  `ARROW` `1 − |2x − 1|` (ends at *from*), and `WINGS` two mirrored complete fans meeting at the
  centre (*to* at each outer end, *from* at the centre, an odd count's centre head in both wings) —
  Titan's outward-from-centre reading, chosen by Chris on 2026-09-17 over the plan's first
  "sampled twice and folded" draft.
- **`selection.subselect {mode}`** (in, `plugins/SelectionSocket.kt`; client `api/selectionApi.ts`
  gains `subselect` beside `set` / `toggle` / `clear`, and `store/selection.ts` a
  `subselectDeskSelection`) — `mode ∈ ALL | ODD | EVEN | FIRST_HALF | SECOND_HALF | INVERT |
  NEXT | PREV | MASTERS`. The desk rewrites its selection's targets over the effective rig order
  (§3.3) and answers with the ordinary `selection.state` frame, `source` stamped as for any write.
  `ODD`/`EVEN`/halves act over the selection's cells where a selected fixture has elements and
  over heads where none does; `MASTERS` drops every element key and keeps the parents;
  `NEXT`/`PREV` step the whole selection one place along rig order (a group steps as a group).
  *Session 2 amendment:* `ALL` widens every selected cell to its whole fixture; `INVERT` takes the
  rig as its universe at the selection's granularity; `NEXT`/`PREV` step at cell granularity when
  every selected target is a cell, and from an empty selection land on the first / last step.

### 3.6 MIDI

`BindingTarget` gains five BUTTON variants: `BuskFocusSet(windowName, focus)`,
`BuskSheetToggle(windowName)`, `SelectionNext`, `SelectionPrev`, `SelectionCells(mode)`. The two
window-addressed ones dispatch a `WindowRegistry.Command.ViewOptions` to **every** row of that
name (D9 of the multi-screen plan accepts duplicate names); no row → log and no-op, health
`missingWindow`. *Session 2 amendment:* the command carries the **view that row announced** and
`{focus: …}` or `{sheet: toggle}`, so a window not on the busk view receives a frame for its own
view with a key it does not contribute and ignores it — the desk never learns the busk view's id.
`BindingHealthEvaluator.Context` (built at `State.kt:567` from DB sets) gains
`connectedWindowNames`, and `SurfaceFeedbackPublisher` gains a `WindowRegistry.windows` collector
beside its `FixturesChangeListener` hooks so the health re-evaluates on connect and disconnect —
without it a binding stays `missingWindow` after its window arrives. The three selection ones
call `deskSelection.subselect`. The top-level `targetControlKind` in `midi/BindingControlKind.kt`
answers BUTTON for all five; `refuseWrongKind` therefore refuses them on a fader by the existing
path. A sub-selection is not a state the desk keeps, so `SelectionCells` has no LED
(`FU-SURFACE-SUBSELECT-LED`). Client mirror in `lib/surfaceDrop.ts` (`targetControlKind`),
`components/surfaces/targetUtils.ts` (`describeTarget`, which already has its `default` arm),
`lib/healthDescriptor.ts` (`describeHealth`), `api/surfacesApi.ts` (`BindingHealth` gains
`missingWindow`), and `SurfaceLibrary`'s `actionChip` rows: *Focus · Split / Pads / Rig* and
*Sheet* per **window** under the Desk row (a chip per window reads as window-specific, which it
is), *Next · Prev · Odd · Even · Masters* once on the Desk row.

### 3.7 Sync

`formatVersion` 12 (was `SUPPORTED_FORMAT_VERSION = 11` in `sync/ProjectImporter.kt` before this
session; bumped by session 2): `/buskRig.json` beside `/buskPages/`, rows with tiles inline, tiles referencing groups
and patches by uuid and elements by key. *Session 2 amendment:* `buskRig/{rowUuid}.json`, one
document per row, not one top-level file — `RecordHasher` filters every top-level file out of the
record scan, so a single `buskRig.json` would never propagate through the three-way diff. `ProjectRoundTripTest` gains the rig — including the
importer's wipe order (§3.2); `SyncCoverageTest` gains disposition rows for both tables;
`MIN_SUPPORTED_FORMAT_VERSION` stays 5 and a v11 export imports as an empty rig.

## 4. UX — what the design draws

A grep-able summary of the twelve artboards; the files are the authority for layout and copy.

- **`Main`** — the busk view in play at 1440×900: the rig band as three named rows of 52px tiles
  (Groups, Movers, Bars), each tile with a 3px live colour bar and a multi-head tile with pips, a
  label row *Rig · summary · family pill · Targets: Desk · Cells: All · Spread… · Locate ·
  Highlight · Clear*, a `3 of 4 rows` handle; the page strip with a *Focus* segmented control
  (Split · Pads · Rig) beside *Edit layout*; the page as today; the side sheet with tabs Speed ·
  Colour · Spread and a fold chevron, Colour open: picker, hue bar, hex + R/G/B, White/Amber/UV
  rows with the emitter count, *Recent* swatches from the template recents row, a *Second colour,
  spread across the selection* switch, *Save as template…* and *Pick*.
- **`Rig`** — edit mode: the band under the page's amber wash, row name fields, grips, crosses on
  tiles, a dashed drop slot, a *Drop a target* ghost tile, *+ Row*, *Arrange: Rows | Plot* (Plot
  drawn as a segment, out of scope), *Show every target*; a tile menu with the four cell modes; the
  palette's **Rig** tab: search, All / Groups / Fixtures, every group and fixture once with *on rig*
  / *not on rig* / *hidden*, cells expanded under their fixture.
- **`Focus`** — the three shapes as miniatures (Split, Pads with the rig folded to a 32px strip,
  Rig with the page folded to its tab strip); the two-screen flow (Screen 1 Rig + Colour, Screen 2
  Pads on Position, unlinked page); the per-window facts and the ways to change them.
- **`Screens`** — the Screens sheet with a busk row's Focus / Sheet / Page pickers (Sheet as one
  enum with `none`), an iPad row on the Prompt Book contributing nothing, *Copy link for Screen 2*
  minting `?window=Screen%202&page=3&focus=pads&sheet=none`, a *Layouts…* placeholder
  (`FU-SCREENS-LAYOUTS`).
- **`Sheets`** — the 44px fold, the Speed tab unchanged, the Colour tab at full size, and the six
  rules (literals, reuse of `ColourPickerPopover`'s content, Recent, Pick, the mask, the window).
- **`Spread`** — the Spread tab: family segment, From / To, the four curves as pictures, Order (Rig
  · Stage L→R · Centre · Random), Parts (1 2 3 4 N…), Over (Heads · Cells), a *Live* switch, the
  preview strip drawn from the desk's answer, *Save as Look…* / *Apply*; the request/response; the
  desk comparison.
- **`Cells`** — the tile in four states; the Cells chip's nine modes; the wire; the backend gaps
  (now session 1, with §1's corrected list); the rules the tile states; the desk comparison.
- **`Survey`**, **`Model`**, **`Ideas`** — as named; `Model`'s five-session sketch is superseded by
  §5's seven. **`Phones`** and **`Tablets`** — the folds: phone portrait (one rig row with a row
  chip, Focus as glyphs, the sheet as a bottom sheet carrying Colour and Spread, Rig focus stacked
  two-across), phone landscape (short beats narrow: strips merge, side-sheet overlay), iPad portrait
  (rail folded by default), iPad landscape (the desktop board), and the ladder.

## 5. Implementation — seven sessions

Backend first (D16). Sessions 1 and 2 are lighting7 only and leave nothing visible; 3–7 are
lighting-react, each finding its routes in place. **The order is 3 → 4 → {5, 6} → 7**: 4 builds
the `SideSheet` that 5 and 6 are tabs of, 6's *Spread…* button is on 3's band, 5's *Second colour*
switch opens 6's tab, and 7 needs 3 (the pips) and 6 (*Over*). 5 and 6 are independent of each
other. Every session ends with `npm run check` / `./gradlew test` green, its docs paragraph
written, and its done-marker here (strikethrough plus commit hash, nothing more — the detail
belongs in the commit message).

### Session 1 — the element arm (lighting7) — Fable 5.1, high — **landed**, `b263ca5`

The prerequisite Chris chose to land first. Start from **one failing test per door** — a colour
template on two cells, a Look recorded from two cells and toggled, an effect template on a cell,
an amber template on a cell's white emitter, and a cell press under a whole-bar layer — and make
each pass; the list below is where the review found the gaps, not a substitute for the tests.

- **`TargetCoverage`** (`fx/TargetCoverage.kt`): the parent↔cell coverage rule of D11, stated once
  — `covers(held: Set<CueTargetDto>, pressed: CueTargetDto)` answering true for a parent over its
  cell; `pressWouldRelease` (`routes/pressArm.kt:31-49`) and `narrow` (`:53`) read it, and
  `ProgrammerLayerStack.appliedState` reports a cell selection under a whole-bar layer as `ALL`.
  `expand` itself needs no arm (it passes an element DTO through).
- **`CueComposer.applyLayer`** (`:696`): a row with an `elementKey` composes onto that element,
  resolved through `Fixtures.untypedGroupableFixture`.
- **`LookRegistry.expand`**: both `continue`s carry the element key instead. (The include route,
  `routes/programmerLookInclude.kt:302`, already carries `elementKey`.)
- **`routes/lookRecord.kt`**: `record-look` over a selection with element keys writes element rows
  — `expandTargetsToFixtureKeys` (`:297`) keeps an element key as its own row rather than folding
  it into the parent. *Session 1 amendment:* it already did; the drop was
  `collectProgrammerEntries` skipping element entries as `ELEMENT_TARGET`, now lifted for
  `record-look` alone (`allowElements`), with `writeRecordingIntoLook` writing `targetKey` the
  parent and `elementKey` the cell.
- **`fx/EffectSpawner.createFixtureTargetForCue`** (`:~92`): `untypedFixture(key) as? Fixture` is
  register-only; resolve through `untypedGroupableFixture` so an effect template on a cell targets
  the element. Check `FxTargetFactory` for the same cast. *Session 1 amendment:* the engine's own
  `buildExpansion` fixture arm had the same register-only lookup and is the site that made the
  effect paint nothing; both now resolve through `untypedGroupableFixture`.
- **`FU-FX-ELEMENT-BUNDLED-COLOUR`**: `FxTarget.applyValueToFixture` / `resetToFallback` /
  `composeProgrammerOver` / `isPropertyFullyParked` hand an element its bundled W/A/UV through
  `FixturePropertyCatalogue.of(element::class).bundledByCategory`.
- Already correct, verified rather than changed: `templateApply.membersOfTarget` and the
  value-apply loop resolve element keys; `TemplateResolver.resolve` / `unmetColourRequirement`
  read the element's own emitters; `BuskPressService` and the Look toggle pass element targets
  through.
- Tests: `TargetCoverageCellsTest` (the rule, both directions), `PressArmCellsTest`,
  `CueComposerElementRowsTest`, `LookRegistryElementTest`, `LookRecordElementTest`,
  `EffectSpawnerElementTest`, `FxTargetBundledColourTest`, `BuskPressRouteTest` gains a cell press
  under a whole-bar layer (reads lit, releases nothing) and a whole-bar press under a cell layer
  (adds).
- Docs: `docs/lighting-composition-model.md` §"A press is per target" gains the element and
  coverage sentences; close `FU-LOOK-ELEMENT-ROWS` and `FU-FX-ELEMENT-BUNDLED-COLOUR` in
  `followups.md` with the hash.

### Session 2 — the rig, spread, the command and the targets (lighting7) — Fable 5.1, high — **landed**, `6e2cc72`

- `models/buskRig.kt` (§3.1) with a docblock recording D1–D3 and the no-cascade rule; register in
  `Schema.ALL_TABLES`.
- `routes/projectBuskRig.kt`: `GET` / `PUT …/busk/rig` (§3.2), the validation codes, dense
  renumbering; the three delete sweeps (group route, patch route, **the importer before its group
  and patch deletes**); `Fixtures.buskRigChanged()` and `BuskRigChangedOutMessage`
  (`busk.rigChanged`) in `plugins/BroadcastSocket.kt`.
- `state/BuskRigOrder.kt` (§3.3); `DeskSelection` takes a rig-order provider (`State.kt:630`
  wiring) and gains `subselect(mode)`; `selection.subselect` in `plugins/SelectionSocket.kt`.
- `routes/programmerSpread.kt`: `POST …/programmer/spread` (§3.5). The curve and order maths in
  `fx/SpreadPlan.kt` — pure over an ordered list of heads, `parts` repeating the curve, `over`
  choosing heads or cells; `DistributionPlan.of(strategy, members)` for order; `TemplateResolver`
  per head for the endpoints and the (now `internal`) Lab mix / linear interpolation between them,
  in the intent's own space (Lab for colour, degrees for position, percent for level).
- `WindowRegistry.Command.ViewOptions`, `WindowsViewOptionsInMessage` / `OutMessage`
  (`windows.viewOptions`), `Window.viewOptions`, `WindowsAnnounceInMessage.viewOptions` optional
  with a null default.
- `midi/BindingTarget.kt`: the five variants (§3.6); `targetControlKind`;
  `SurfaceInputRouter.dispatchButtonPress` arms; `BindingHealthEvaluator.Context.connectedWindowNames`
  and the `missingWindow` health; `SurfaceFeedbackPublisher` re-evaluates on `WindowRegistry.windows`.
- Sync v12 (§3.7).
- Tests: `BuskRigRoutesTest` (shape, identity, dangling ref, empty row refused, halves bounds,
  element key validated, dense renumbering, the three sweeps), `BuskRigOrderTest`, `SpreadPlanTest`
  (each curve at n = 1, 2, 3, 8; parts; over cells; reverse), `ProgrammerSpreadRouteTest` (a group
  spreads in member order; a head lacking the property is skipped by name; `tmpl:` endpoints;
  `skippedFamilies`), `DeskSelectionSubselectTest` (every mode, with and without elements, Next
  wraps; its fixture is exported as JSON for the client mirror), `WindowsSocketTest` gains
  viewOptions and an old-client announce without it, `BindingTargetSerializationTest` gains the
  five, `ControlSurfaceBindingHealthTest` gains `missingWindow` and its re-evaluation on connect,
  `ProjectRoundTripTest` gains the rig and the importer wipe order.
- Docs: `docs/lighting-composition-model.md` §"The busk layout" gains §"The rig";
  `docs/websocket-engineering.md` rows for `busk.rigChanged`, `selection.subselect`,
  `windows.viewOptions`; `docs/sync-engineering.md` v12; `docs/midi-surface-engineering.md`
  (lighting-react) waits for session 7. Record `FU-SURFACE-SUBSELECT-LED`, and — since CLAUDE.md
  and the multi-screen plan cite it but `followups.md` has no entry — `FU-SCREENS-LAYOUTS`.

### Session 3 — the rig band (lighting-react) — Opus 5, xhigh — **landed**, `e7b540a8`

- `api/buskRigApi.ts`, `store/busk.ts`: `useBuskRigQuery`, `useSaveBuskRigMutation`, the
  `BuskRig` tag **added to `REST_TAG_TYPES`** (`store/status.test.ts` pins the resync set), the
  `busk.rigChanged` bridge at module scope (`store/busk.ts` already subscribes at module scope and
  is imported only by busking and surface components, not by `Layout` or `navigation.ts`), and a
  `useBuskRigCommit` on `useBuskLayoutCommit`'s operation-queue model, whole document, response
  written when the queue drains.
- `lib/buskRig.ts`: the document model, sibling of `buskLayout.ts` — addresses `rrow:{r}` /
  `rtile:{r}.{t}` / `rpal:{kind}:{key}`, `applyDrop`, `normaliseRig` (drops an empty row; keeps an
  empty rig), `toRigRequest`, `nextRowName`, and `effectiveRig(rig, groups, fixtures)` answering
  the show-all fallback so `RigBand` has one render path. `buskRig.test.ts` mirrors
  `buskLayout.test.ts` and pins the fallback's order against `BuskRigOrderTest`'s fixture.
  *Session 3 amendment:* the fixture is **copied** to `src/lib/__fixtures__/rigOrder.fixture.json`
  (a cross-repo import would tie the suite to a sibling checkout, and the app's tsconfig has no
  Node types to read one with); re-copy it when `BuskRigOrderTest`'s fixture changes.
- `components/busking/RigBand.tsx` replaces `TargetBand.tsx`: rows of `RigTile`, the label row
  as drawn (Cells chip and *Spread…* present but inert until sessions 7 and 6), the `n of N rows`
  handle (writes `busk.rigRows`; session 4 reads it — here it is drawn and clamps), the folded
  32px strip (`RigStrip.tsx`, used by session 4). `RigTile` draws the 3px live bar and, for a
  multi-head, the pips, through `FixtureAppearanceSource` (a render prop; the tile mounts one leaf
  per head) — the pips are **read-only** until session 7; a tap on the tile is the whole fixture,
  as today. Extract `BankNameField` from `BuskBank.tsx` (private today) for the row name.
- Edit mode: the band joins the app's one `DndContext` through `useDndMonitor` with `rig-`
  prefixed ids; `parseBuskDragId` (`lib/buskLayout.ts`) and the slot handler ignore them;
  `buskDnd.ts`'s private `canLand` refuses a rig source everywhere but a rig row and a page source
  on the rig. `LibraryPalette` gains the **Rig** tab (`RigPalette.tsx`: search, kind filter, *on
  rig* / *not on rig* / *hidden*, cells expandable under a fixture, rows draggable by the grip).
  The tile menu with the four cell modes.
- The hand: `lib/handTargets.ts` gains a `rig-row` kind beside `bank | slot | layer-stack |
  cue-stack` for a group or fixture held in the hand; `HandPlaceStrip` (`components/hand/HandTarget.tsx`)
  on each row in edit mode (solid, per the hand's rule). *Session 3 amendment:* **the hand cannot
  hold a group or fixture** — `hand.pickUp {kind, id}` takes a `BuskPadKind` and `HandState.Held`
  requires a template, Look or cue summary — so the `rig-row` kind answers false for every held
  kind, the strip is mounted and wired (the rig PUT through the commit queue, then `hand.drop`)
  and never lights, and `RigBand.rigRecordOf` is the one function to teach when the wire gains a
  group kind. That wire change is lighting7's, and was not worked around client-side.
- Deletes `TargetList.tsx`, `TargetListItem.tsx`, `TargetBand.tsx`, `TargetBand.test.tsx`, the
  left `Sheet` in `BuskingView.tsx` and `onOpenPicker` (D15); below `md` the band renders one row
  with a row chip (the phone board), and Rig focus arrives in session 4.
- Tests: `RigBand.test.tsx` (fallback = today's band; a built rig draws its rows; a tile press
  toggles; the handle clamps), `RigPalette.test.tsx`, `buskDnd.test.ts` gains rig sources and the
  composition of slot and landing place for a rig row, `BuskingView.test.tsx` updated,
  `status.test.ts` for the tag.
- Docs: CLAUDE.md §"The busk layout" gains §"The rig"; `docs/stage-vis-engineering.md` notes the
  tile as a fourth reader of the colour dispatch.

### Session 4 — focus, the split, the sheet, the Screens sheet (lighting-react) — Opus 5, high — **landed**, `51e9f8c1`

- `lib/buskWindow.ts` (§3.4) on `createSyncStore({…, storage: sessionStorage})`; `?focus=` /
  `?sheet=` latched on `buskPageFollow.ts`'s model; `useBuskFocus`, `useBuskSheet`, the defaults
  ladder from its own copies of the two media queries (`shortViewport.test.ts` gains the file).
- `BuskingView.tsx`: the *Focus* segmented control on the page strip; the three shapes — Split
  (the band shows `busk.rigRows` rows), Pads (`RigStrip` folded), Rig (the band fills, the page
  folds to `BuskPageStrip` with the Focus control on it); the handle from session 3 now writes the
  fact; edit mode forces Split and restores on Done.
- `components/busking/SideSheet.tsx`: the tab strip and the 44px `SideSheetFold.tsx`; Speed live;
  the Colour and Spread tabs **hidden until their sessions land** (a tab with an empty state is a
  promise the desk cannot keep). Below `md` the sheet is a bottom sheet / side sheet through
  `useCellEditorForm`, opened from a button on the page strip, and carries no Speed tab (D7).
  *Session 4 amendment:* with Speed excluded and Colour and Spread not landed, that sheet has no
  tab to open onto, so `SideSheetOverlay` never opens and the strip's button is drawn inert with
  the reason on its title; the `Phones` board's merged 32px strip on a short viewport is not built
  (moved to session 5).
- `lib/windowViews.ts`: `options` on the busk entry (D13, Sheet as one enum with `none`);
  `api/windowsApi.ts`: `viewOptions` on the announce (the pinned key set in `windowsApi.test.ts`
  becomes six keys plus `type`), `windows.viewOptions` in and out; `useWindowsBridge.ts` handles
  it for this window and the announce carries the current values from `buskWindow.ts` and
  `buskPageFollow.ts`; `ScreensSheet.tsx`'s `WindowRow` renders a row's view options from the
  descriptor (Focus, Sheet, Page pickers for a busk row), and *Copy link* mints the whole setup
  (*session 4 amendment:* a **per-row** *Copy link for <name>*, `windowSetupUrl`, carrying that
  row's announced page, focus and sheet; the new-window section's link is name-only as before);
  `navigation.ts` `buildWindowCommands` gains *Focus pads / rig / Split* for this window and a
  focus arm on *Show <view> on <window>*.
- Tests: `buskWindow.test.ts` (defaults per surface, latch once, reload is not an arrival),
  `BuskingView.test.tsx` (three shapes; edit mode forces Split), `SideSheet.test.tsx`,
  `ScreensSheet.test.tsx` (a busk row draws the pickers, a Prompt Book row draws none; page set
  unlinks), `windowsApi.test.ts`, `navigation.test.ts`, `shortViewport.test.ts`.
- Docs: CLAUDE.md §"The busk layout" gains §"Focus and the side sheet"; §"Windows, full screen
  and the hand" gains the viewOptions paragraph and the announce key rule.

### Session 5 — the Colour tab (lighting-react) — Sonnet 5, high — **landed**, `db9542cd`

- Extract `hooks/useLivePush.ts` from `BuskSpeedRail.tsx`'s private `useLiveTempoPush` — generic
  over the value: dedupe, the 50ms floor, a deferred value held and sent when the floor lifts, the
  release bypassing both — and have `BuskSpeedRail` adopt it (`BuskSpeedRail.test.tsx` unchanged).
- `components/busking/ColourSheet.tsx` over `ColourPickerPopover`'s content (the picker, typed
  R/G/B, the emitter rows) hosted in the sheet rather than a popover — extract the body as
  `ColourPickerBody` if the popover cannot be hosted as is; `compact` under the cramped query.
  Emitter rows for the selection's union (`targetEmitters` from `rowModel.ts`) with the count of
  heads that take them. *Session 5 amendment:* the union is read off the colour descriptors alone
  (`emitterHeadCounts` in `ColourSheet.tsx`), not `targetEmitters`, whose slider-in-an-emitter-
  category arm serves the template offer — a `setColour` cannot drive that emitter, and a row for
  it would be a slider that moves nothing.
- Writes: `programmer.setColour` per selected target through `useLivePush`; *Recent* from
  `recentTemplates` (colour family), a tap is a template **apply** through `useTemplatePress`;
  *Pick* reads the selection's current colour — `FixtureAppearanceSource` is a render prop and
  cannot be read from a click handler, so the rig tiles' per-head leaves report their appearance
  into a small store (`lib/liveAppearance.ts`) that Pick reads (first head in rig order wins,
  *mixed* shown when they disagree; *session 5 amendment:* the sheet mounts a hidden leaf per
  selected head as well, since in Pads focus the tiles are folded away and a Pick that answered
  nothing there would be the tab's own default state); *Save as template…* opens `NewTemplateFromSelectionSheet`
  with the sheet's targets; the *Second colour* switch opens the Spread tab with From set
  (session 6 wires the far end; *session 5 amendment:* it ships inert behind an `onSpread` seam
  on `ColourSheet`, disabled with the reason on its title until that tab lands).
- **The short-viewport fold** (`Phones` board, landscape phone, carried over from session 4
  which did not build it): where the viewport is short (`max-height: 500px`) but at or above
  `md`, *short beats narrow* — the rig strip and the page strip merge into **one 32px row**
  (`Rig · summary · pill · Targets: Desk` on the left, the page tabs, page chip and Focus
  glyphs on the right), Split shows one row of 48px tiles with the row chip as below `md`, the
  side sheet **overlays** rather than docks (the fold and the docked rail are not drawn; the
  sheet is `SideSheetOverlay`'s right-hand form, 288 wide, Colour in its compact layout), and
  *Edit layout* is withheld as it is below `md`, since a palette drag needs both regions. The
  defaults are already Pads and `none` from `buskWindow.ts`'s ladder; what changes is which
  board `BuskingView` draws, so `isDesktop` gains a short arm read from the same duplicated
  query (`shortViewport.test.ts` gains the site) rather than a new number. *Session 5
  amendment:* the merged row is the rig strip's pieces in the page strip's `leading` slot while
  the rig is folded (Pads focus); in Split the compact band carries them itself and the page's
  row is the same 32px row holding only the page, and *Edit layout* is withheld through an
  `editable` prop rather than a width class, since the short board is wider than `md`.
- Tests: `useLivePush.test.ts`, `ColourSheet.test.tsx` (per-target writes; emitters by union;
  Pick; Recent is an apply; no write under an empty selection, toasts as the strip does),
  `BuskingView.test.tsx` (the short-viewport board: merged strip, overlay not docked, no *Edit
  layout*), `shortViewport.test.ts`.
- Docs: CLAUDE.md §"Focus and the side sheet" gains the Colour paragraph (D8, verbatim rules)
  and the short-viewport board.

### Session 6 — the Spread tab (lighting-react) — Opus 5, high

- `store/programmerOps.ts`: `useSpreadMutation` (REST, structured reply, the file's own rule; the
  WS ops live in `api/programmerWsApi.ts` and are not touched).
- `components/busking/SpreadSheet.tsx`: family segment (from `targetFamilies` of the selection),
  From / To (a `TemplateIntent` editor per family: the colour picker inline, a percent field, a
  position pair, a level), the four curves as pictures, Order, Parts, Over (Heads / Cells —
  enabled only when a selected fixture has elements), *Live* through `useLivePush`, the preview
  strip drawn from `written[]`, *Apply*, *Save as Look…* (opens `RecordLookSheet` over the
  selection). *Spread…* on the band opens the tab. `lib/spreadIntent.ts` serialises only.
- Tests: `SpreadSheet.test.tsx` (request shape per family; Live throttles and the release lands;
  preview is the response, not a client lerp — assert no `fanMath` import; Over disabled without
  elements), `spreadIntent.test.ts`.
- Docs: CLAUDE.md §"Focus and the side sheet" gains the Spread paragraph (D9, D10).

### Session 7 — cells, the chip, the MIDI mirror (lighting-react) — Opus 5, xhigh

- `RigTile`: pips become tappable (a pip is `{type:'fixture', key: element.key}`; a drag across
  pips is a run; on `touch`/`pen` a pip grows to 44px under the finger, the marquee's arm rule);
  the tile reads `some` when part of its cells is selected; `cell_mode` `PER_CELL` / `HALVES`
  expansion draws its tiles.
- `lookPresence.ts` keeps reading the desk's resolved `applied` state — the parent↔cell rule is
  session 1's, on the desk; the client folds only the selection into one ring, as it does today.
- The Cells chip: `selection.subselect` while following (`api/selectionApi.ts` gains it,
  `store/selection.ts` a `subselectDeskSelection`); `lib/cellsSubSelection.ts` for an unlinked
  window over the rig document (`useBuskRigQuery`) and the groups' member lists, pinned against
  `DeskSelectionSubselectTest`'s exported fixture (`cellsSubSelection.test.ts` reads the same
  JSON). *Over: Cells* on the Spread tab reads the same expansion.
- MIDI mirror: `lib/surfaceDrop.ts` `targetControlKind` for the five; `targetUtils.describeTarget`
  arms; `healthDescriptor.ts` `missingWindow`; `api/surfacesApi.ts` `BindingHealth` variant;
  `SurfaceLibrary.tsx` chips (§3.6); `SurfaceInspector` renders the window name and the mode.
- Tests: `RigBand.test.tsx` gains pip gestures, `lookPresence.test.ts` gains cells,
  `cellsSubSelection.test.ts`, `surfaceDrop.test.ts`, `targetUtils.test.ts`, `SurfaceLibrary.test.tsx`.
- Docs: CLAUDE.md §"The rig" gains the cells paragraph (D11, D12); `docs/midi-surface-engineering.md`
  gains the five targets and the window-by-name rule.

## 6. Migration

None. An empty rig is today's band (D1); the per-window facts default to today's arrangement
(Split, the rail open where it is drawn today); every new route is additive; sync moves to v12
with the rig file optional on import (a v11 export has no rig and imports as empty).

## 7. Explicitly out of scope

- The extras on the Ideas board (D17): target pads and special pads as pad kinds, flash on hold,
  hold-to-drag level, save selection as group, plot arrangement.
- A rig per page; free positioning; a programmer per window.
- Saved screen layouts (`FU-SCREENS-LAYOUTS`) — the Screens sheet is laid out for the button and
  the announce is the record's shape, and that is all.
- A "spread template" (D10).
- LED feedback for `SelectionCells` (`FU-SURFACE-SUBSELECT-LED`).
- Any change to the page document, the press route, `selection.state`'s shape, page follow, the
  hand's frames or the speed-master frames.

## 8. Follow-ups to record

- `FU-SCREENS-LAYOUTS` — cited by CLAUDE.md and the multi-screen plan, never entered in
  `followups.md`; session 2 enters it.
- `FU-SURFACE-SUBSELECT-LED` — a sub-selection is not a desk state, so its buttons have no LED.
- `FU-BUSK-RIG-PLOT` — *Arrange: Plot* from the patch's stage coordinates; the segment is drawn.
- `FU-BUSK-TARGET-PAD` / `FU-BUSK-SPECIAL-PAD` — the two pad kinds from the Ideas board.
- `FU-BUSK-TILE-LEVEL-DRAG` — hold a rig tile to drag its level.
- `FU-BUSK-SAVE-AS-GROUP` — *Save as group…* on the band.
- `FU-WINDOWS-NAME-ADDRESSED-BINDING` — a MIDI target names a window by name; if D9's duplicate
  names ever matter, this is where to revisit.
- `FU-BUSK-RIG-GROUP-ID` — *session 3*: `GroupSummaryDto` embeds no id, so the client names a
  group tile through the patch list and a memberless group cannot be placed; one field to add.
- `FU-HAND-GROUP-KIND` — *session 3*: the hand holds only a template, Look or cue, so the rig-row
  target is wired but inert until the desk can hold a group or fixture.

## 9. Verification

Beyond the unit suites, the desk checks, in the order the sessions land:

1. **Element arm** (after session 1): select two cells of a bar on the programmer's grid, press a
   colour template — only those cells change; record a Look, toggle it off and on — the cells
   follow; an effect template on one cell runs on that cell; an amber template on odd cells lands
   amber on the odd cells' white emitter; with the whole bar under a Look, pressing the Look on
   four cells reads lit and releases nothing.
2. **Rig** (after session 3): an empty rig draws exactly today's band; build three rows on the
   desk, reload on the iPad — same rig; delete a group in the fixtures list — its tile is gone
   everywhere without a refresh; import a v11 export — an empty rig, no FK error.
   *Session 3, 2026-09-17, browser pass at 800px on the dev desk:* the empty-rig fallback,
   a tile press, the rows handle, the inert Cells / Spread…, edit mode with the Rig tab, one
   palette drop minting a row through the PUT, PER_CELL and HALVES on a saved tile, and *Show
   every target* were all seen; the iPad reload, the group delete's `busk.rigChanged` sweep and
   the v11 import are **still to check at the desk**.
3. **Focus and screens** (after session 4): Screen 1 Rig + Colour, Screen 2 Pads on Position; set
   Screen 2's focus and page from Screen 1's Screens sheet; copy the link, open it on the iPad —
   it arrives as drawn; rotate a phone with the Colour tab open — bottom sheet becomes side sheet.
   *Session 4, 2026-09-18, browser pass on the dev desk (project 6):* the three shapes, the
   handle's snaps, the fold and the docked sheet at 1440×900 and 820×1180, edit mode forcing
   Split, the Screens sheet's Focus / Sheet / Page controls, a focus set from one tab landing in
   a second tab launched at `?window=Screen%202&focus=pads&sheet=none`, the per-row Copy link,
   the ⌘K focus arm as two frames, and a reload keeping the shape were all seen. **Deferred:**
   the two real desk screens, the iPad arrival from a copied link and the phone rotation are
   still to check at the desk — Chris is away from it for a while, and session 5 need not wait.
4. **Colour** (after session 5): drag the picker with a group and two cells selected — the rig
   follows at stage cadence; Pick reads the current colour back; Save as template lands in the
   library and the recents row.
   *Session 5, 2026-09-18, browser pass on the dev desk (project 6):* typed bytes reaching two
   heads at the live push's cadence and echoing back on the rig tiles, Pick reading the rig
   without writing and saying *mixed*, a Recent chip landing as an apply, the overlay on the short
   board (852×393) and the merged 32px row were all seen; a real drag of the picker with a group
   and two cells, Save as template, and the rig itself are **still to check at the desk**.
5. **Spread** (after session 6): amber → blue across two bars, Line, Over Cells — sixteen steps;
   Over Heads — two; Mirror and Wings read as their pictures; Save as Look pads it.
6. **Cells and MIDI** (after session 7): Odd on a group of bars; Next on the X-Touch steps one
   cell; a BuskFocusSet button flips Screen 2, and its health goes green when Screen 2 connects;
   a fader bound to it is refused at write.

## 10. Scope honesty

Seven sessions is more than the busk layout's five, and two of them are backend-only with nothing
to see. That is the cost of D11 and D16: landing the element arm first is right (nothing draws a
cell it cannot press) and it is real cook-path work with its own tests — smaller than first
written, since the template value path already resolves elements, but with a coverage rule the
first draft missed entirely. If the sessions must be cut, cut from the far end — session 7's MIDI
mirror is the most separable piece, then the Spread tab as a whole (the route stays) — never from
the front. If only one client session ships, ship sessions 3 and 4 together: 4 is what the
two-screen desk is asking for, and it needs 3's band to fold.

## 11. Open questions — all answered 2026-09-17

| Call | Answer |
| --- | --- |
| The rig's home | One rig per project (D1). |
| Where Spread resolves | On the desk (D9). |
| Cells before the element arm | Land the element arm first (D11). |
| The split's unit | Whole rows, snapped (D6). |
| Page on the Screens row | Settable (D13). |
| Which extras are in scope | None; keep the scope as drawn (D17). |
| How the rig is edited | One edit mode, Rig tab in the palette (D4). |
| Session ordering | Backend first, then client (D16). |
| Plan and design record | lighting7 docs, canvas exported (this file and `busk-further-design/`). |
| What the Colour tab writes | Literals to Local only (D8). |
| The narrow-width target sheet | Replaced by Rig focus (D15). |
| MIDI doors | The binding targets are in scope (D14). |

One call made by the plan's author rather than asked, because it is tile-level detail: a
multi-head tile keeps all four cell modes (D3). Say so if fewer are wanted before session 2 writes
the enum.

## 12. What the review changed

Recorded so the next reader knows which parts of §1–§5 were rewritten after the first draft and why:

- **Session 1's account of the element gap.** The first draft said `TargetCoverage.expand` and
  `templateApply.membersOfTarget` needed element arms; both already resolve an element key. The
  real drop sites are the composer, `LookRegistry.expand`, `lookRecord.kt`, `EffectSpawner`'s
  register-only cast, and the bundled-colour path — and the coverage rule nobody had stated.
- **The parent↔cell coverage rule** moved from a client-side fold in `lookPresence.ts` to
  `TargetCoverage` on the desk (D11), because a browser copy of a match rule is the thing the
  pads' own rule forbids, and `pressWouldRelease` would have disagreed with the ring.
- **The importer's wipe** (§3.2): the new FKs would block the importer's group and patch deletes.
- **`?focus=` / `?sheet=` latch** on `buskPageFollow.ts`'s model, not `windowIdentity.ts`'s
  private memo.
- **`missingWindow` re-evaluation** on registry change (§3.6); without it the health never
  recovers.
- **Session order** 3 → 4 → {5, 6} → 7; `SideSheet` is session 4's and the tabs are its children.
- **`useLivePush`** extracted from the speed rail's private tempo hook (session 5).
- **`Pick`** reads a per-head appearance store, since `FixtureAppearanceSource` is a render prop.
- **`BuskRig` in `REST_TAG_TYPES`**; **`busk.sheet`** as one enum with `none`, matching the
  artboards; the phone sheet carries Colour and Spread only; the Cells chip's face is five modes
  with the rest in its menu; `selection.subselect` needs a rig-order provider on `DeskSelection`
  and a client `selectionApi` op; a dozen file and visibility names corrected in place.
