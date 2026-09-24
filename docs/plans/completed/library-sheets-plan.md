# Library sheets — Scripts, FX Library, Looks, Templates and Speed Masters on the sheet kit

> **Document status: DONE — all five sessions shipped 2026-09-23/24 (lighting7 `1f16441`; lighting-react `042919f8`, `dca9a1f9`, `83243b2e`, `a3699076`).** The design is checked in beside this
> plan at [`library-sheets-design/`](../library-sheets-design/INDEX.md): eight static artboards
> covering the five views today, the rules, the kit's additions, every sheet's columns, and one
> mock-up per view. The live copy at <https://claude.ai/artifact/RFXrmBb8LGYJhsuYTgPh2F> is a
> convenience, private to Chris; the checked-in files are the authority. This document is the
> engineering half. Where it and the artboards disagree, this plan wins on behaviour and the
> artboards win on layout and copy. Every call the boards carried was answered by Chris on
> 2026-09-23 (§2).

## 1. Context

Six list views share one shell and one gesture set: the Programmer, Fixtures › List, Groups › List,
Show › Table, Channels › Table and the patch list (`lighting-react` CLAUDE.md §Sheet kit, §List
shell). Five library views do not. Read from `lighting-react` at `ea56d99b`:

| View | Layout today | Inline edit | Edited in | Multi-select |
|---|---|---|---|---|
| Scripts | a type sidebar (a bottom sheet on a phone) beside a `space-y-1` list of `<button>` rows | none | `ScriptForm` — name, the Kotlin editor, Compile, Run, Delete, Copy to project | none |
| FX Library | a shadcn `Table` with collapsible category rows, all in `routes/FxLibrary.tsx` (930 lines) | none | three sheets — detail (read-only), edit (name + script), new | none |
| Looks | `rounded-lg border divide-y` of `LookListRow`s with a hover `…` menu | none | `LookDetailSheet` — name, notes; contents by Include | none |
| Templates | a `Card divide-y` of `TemplateListRow`s under `LookFamilyFilterBar` | none | `TemplateEditor` (1,095 lines) — every field | none |
| Speed Masters | a `Card` of bordered row cards | live BPM (click-to-type), TAP | `SpeedMasterDetailSheet` — name, usage, follow, start BPM, notes | none |

None imports `SheetPage`, `SheetTable` or `useSheet`; none can select more than one thing; there
are four layouts between them. There are no bulk routes for any of the five. The sheet kit already
has everything a library needs but a handful of generic pieces (§3.1).

Four things the survey found that the plan fixes on the way:

- **The FX Library opens the wrong record for a script-registered effect.** `handleRowClick`
  (`FxLibrary.tsx:189`) treats every `USER` entry's `sourceDefinitionId` as an `fx_definitions` id,
  but an effect a `FX_DEFINITION` script registers carries the **script** id there
  (`fxDefinitionScriptDef.kt:67`), so its row fetches `fx/definitions/<scriptId>` — a 404 or an
  unrelated definition. Nothing on the library entry tells the two apart (`EffectTypeInfo`,
  `FxRegistry.kt:69-80`: both are `USER` with a `sourceDefinitionId`).
- **Copying a script to another project drops its type** (`projectScripts.kt:221-225`, a
  `DaoScript.new` with no `scriptType`): the copy lands as `GENERAL`, the column default, and
  silently stops registering or hooking anything.
- **`SpeedMasterInUseResponse` lacks `followerNames`**, which the backend sends (declared
  `projectSpeedMasters.kt:522`, filled at `:320`) — so the in-use guard cannot name the masters a
  force unlinks.
- **A live tempo write on another project's Speed Masters page retunes this show's master.** The
  REST routes are `withProject`, but BPM and TAP go over the socket to the *current* show's bank,
  and master 1 is written as a null uuid (`routes/SpeedMasters.tsx:125`, `speedMastersWsApi.ts:198`)
  — so pressing TAP on master 1 while browsing another project taps the live master 1.

## 2. Decisions taken

- **D1 — The sheet replaces the list, on the same route.** No Cards · Table switcher and no sticky
  view key: each view has one list today and gets one sheet. A second view has to earn a switcher,
  and none of the five has one to offer. The record editors (`ScriptForm`, the FX sheets,
  `LookDetailSheet`, `TemplateEditor`, `SpeedMasterDetailSheet`) stay — they are what a row
  *opens*. *(Chris, call 1.)*
- **D2 — The whole list shell.** `SheetPage` with a 48px header (breadcrumbs), the **library row**,
  the `SelectionBar`, the sheet and the 22px footer, on the 12px gutter. The library row is row B
  for a library: filter · partition chips · spacer · the create verb — which leaves the page header,
  as the patch list's *+ Patch* did.
- **D3 — Chips filter, dividers group.** Where a library partitions exactly — script type, effect
  category, template family — the partition is a chip set on the library row, with counts, and under
  *All* the sheet is grouped by `divider` rows in the partition's declared order. The FX Library's
  categories **stop collapsing**: the chips are how you narrow. Looks and Speed Masters do not
  partition and draw no chips — a Look spans families by design, which is why the family filter left
  `/looks`. *(Chris, call 2.)* The partition is a view, never a route: a `?param=` plus a remembered
  value, as `/templates?family=` already is.
- **D4 — The name column.** Sticky, and the sheet's `firstColumn` — never a `SheetColumn`, so never
  in the marquee. A double click renames it in the kit's `TextCell` popover (`firstColumnCellProps`,
  as on the patch list and the cue sheet), which is **one row at a time by construction** (it hands
  the commit to that row alone, `sheetModel.ts:80-93`); a counted rename over a batch is not in
  scope. A pencil opens the record's editor, and so does **⏎ with exactly one row selected and no
  cells** — in the kit, so the patch list gains it too. *(Chris, call 3.)*
- **D5 — What a cell edits.** Name and notes everywhere, plus the few values a library holds:
  a template's **Value** (D6), **Fade** and effect **Master**; a master's live **BPM**, **Start**,
  **Follows**, **Ratio** and **Usage**. What a Look *holds* stays recorded and changed by Include —
  the sheet never grows a value grid for it (`LookDetailSheet`'s rule). Every other column is a
  read-out: no `cell`, so never in the marquee.
- **D6 — A template's Value is editable in the cell, in its own session.** Generic value templates
  only; per-fixture and effect templates read out and open the editor. The cell mounts the same
  family control the editor draws — `ColourControl`, `PercentControl`, `PositionControl`,
  `BeamControls`, lifted out of `TemplateEditor.tsx` **with the rules that turn their values into
  rows** (`seedValues`, `effectiveColourPolicy`, `colourPolicyLocked` and the rows builder) — so the
  two cannot disagree about a template's grammar, and writes `rows` through `lib/templateIntent.ts`.
  *(Chris, call 4.)* The board says "the editor kit's editors in intent mode"; lifting the template
  editor's own controls is the same intent with one copy fewer, and this plan wins.
- **D7 — A master's BPM cell is the live tempo.** Set over two masters writes both running clocks
  now (`speedMasters.setBpm`, the WS write click-to-type uses); followers are skipped by name. The
  stored boot tempo is its own **Start** column (`PUT {bpm}`), so the two BPMs never share a cell.
  TAP stays in the row, as a read-out button. *(Chris, call 5.)*
- **D8 — Scripts gain a Check column, filled by a batch Compile.** Compile over a selection runs
  `POST …/scripts/compile` for each and writes the result into its row as it arrives — ✓, or the
  error count and first line. The results are **this tab's**, never stored: a script edited after
  its check reads *not checked* again. The editor keeps its own compile dialog. *(Chris, call 6.)*
- **D9 — Fork a built-in effect.** A custom definition made from the built-in's script, category,
  output type, mode, parameters, `compatibleProperties` and `timingSource` through the existing
  `POST /fx/definitions`, named *<name> (Custom)*, opening in its editor. Its **`effectId`** is the
  thing that must be unique, not its name: `FxRegistry.register` overwrites by id
  (`FxRegistry.kt:123`) and deleting a definition unregisters its id (`fxDefinitions.kt:190`), so a
  fork sharing its source's id would replace the built-in, and deleting the fork would remove the
  built-in until a restart. The client mints `<sourceId>Custom`, `<sourceId>Custom2`… against every
  id in the library. The library entry carries no `defaultStepTiming`, so a fork starts at `false`
  — stated in its editor, not hidden. Current project only: `/fx/definitions` always writes to the
  current show (`fxDefinitions.kt:63`). *(Chris, call 7.)*
- **D10 — Templates get a copy route in lighting7.** `POST /projects/{id}/templates/{templateId}/copy
  {targetProjectId, newName?}`, `copyLook`'s shape. It gives Templates both **Duplicate** (the same
  project, a `(Copy n)` name worked out client-side as Looks does) and **Copy to…**. *(Chris,
  call 8 — the one call answered differently from how it was first drawn.)*
- **D11 — Row verbs on the bar.** After Set · Clear · Spread come the row verbs — Include, Pick up,
  Duplicate, Copy to…, Fork, Compile, Run, Delete — then Deselect. A verb that acts on one record
  (Include, Pick up, Run) is disabled over several with the reason. The per-row `…` menus go: a verb
  over one row is a verb over a selection of one.
- **D12 — Read-only by row and by scope.** A row with nothing to set in a column has
  `value: undefined` there (master 1's Follows, a follower's BPM and Start, an effect template's
  Fade). The marquee is geometric and still covers such cells, so **the kit drops them before
  `write` and names them on the editor's read-out** (§3.1) — today it does neither: `write` receives
  them and `batchCountFor` counts them. **Another project's library** is the sheet's read-only scope
  — the cue lock's shape: marquee and selection work, every value verb is disabled with the reason,
  and *Copy to…* is the one live verb (Looks, Templates, Scripts; the FX Library has no copy route
  and shows the scope with nothing live). **Speed Masters is half exempt**: its REST routes are
  `withProject`, so Start, Follows, Ratio, Usage and Notes stay editable from any project, but
  **BPM and TAP are read-only off the current project**, because they write the live show's clocks
  (§1's last bullet — a bug the page has today, which the sheet must not multiply).
- **D13 — One delete for a batch.** Delete sends each plain, gathers the in-use refusals
  (`LOOK_IN_USE`, `TEMPLATE_IN_USE`, `SPEED_MASTER_IN_USE`) and asks once, listing what each is used
  by; *Delete anyway* forces only those, *Keep them* leaves them selected. Master 1 and built-in
  effects are skipped by name before anything is sent. It replaces the three hand-written delete +
  in-use dialog pairs.
- **D14 — The sheets report their own failures.** Every write these sheets make except the copy
  route is in `SILENT_ENDPOINTS` (`store/errorToastMiddleware.ts:20-63`: `saveSpeedMaster`,
  `deleteSpeedMaster`, `saveTemplate`, `deleteTemplate`, `saveLook`, `deleteLook`, `copyLook`,
  `copyScript`), because today's dialogs render their refusals inline. A sheet has no inline place,
  so a refused cell write (`SPEED_MASTER_USAGE_TAKEN`, a name clash, `SPEED_MASTER_FOLLOW_CYCLE`)
  is toasted by the sheet **by code**, keyed per column so a batch replaces rather than stacks —
  `rigWriteFailureMessage`'s pattern. They stay silent in the middleware, which would otherwise say
  it a second time, generically. `copyTemplate` joins the list with the same treatment as `copyLook`.

## 3. The model

### 3.1 What the kit gains (`components/sheet/`)

| Piece | What |
|---|---|
| `SheetTable` `firstColumn.onOpen(row)` | Draws the pencil the patch list hand-rolls today (`PatchSheet.tsx:706`, beside its `firstColumnCellProps` rename — the two already share that column) and is what ⏎ on one row calls. The patch list moves onto it. |
| `useSheetKeyboard` | A new arm: ⏎ with **one** selected row and no cells → `onOpenRow`, fed a row count and the callback from `useSheet` (the hook knows nothing of rows today). Today it returns after Escape when `cellCount === 0` (`useSheetKeyboard.ts:88`), so no binding changes; the cue sheet's Enter refusal fires only over cells (`CueSheet.tsx:443`), so a sheet passing no `onOpenRow` is untouched. The arm must **exempt the selected row's own first-column trigger** from the focused-control guard (`:89-93`), the way `marqueeOwnsKeyTarget` exempts cell triggers — clicking a name focuses its `TextCell` `<button>`, and without the exemption ⏎ would press that button, not open the row. The pencil is not exempt: ⏎ on it is its own press. |
| Skipped rows | `useSheet` drops the rows whose `column.value(row)` is `undefined` before `write` (in `commitToSelectedCells` and the batch helpers), counts only the rest in `batchCount`, and hands the dropped rows' names to the cell as `SheetCellProps.skipped`, which the kit cells draw through `EditorReadout` (*M2 and M4 follow M1 · skipped*). No kit cell has a skip read-out today (`OptionCell.tsx:37-38` says so). |
| `LibraryRow.tsx` (new) | The library row: filter · `PartitionChips` · spacer · create. `PartitionChips` generalises `LookFamilyFilterBar`, which is purely controlled (`ViewSwitcher.tsx:347-376`) and stays so: counts and a value in, a change out. The `?param=` and the remembered value are the **route's** (as `routes/Templates.tsx` owns `looks.family` today through `get/setStoredLookFamily`), so `TemplatePicker` keeps mounting it with local state inside its portalled popover. Below 600px of row the chips fold into a select — measured on the chips' own container, which works in the popover too. |
| `groupRows.ts` (new) | Interleaves `divider` rows by partition under *All*, in declared order. Pure; the divider arm already exists in `SheetTable`. |
| `cells/NumberCell.tsx` (new) | `TextCell`'s shape with `EditorField` inside: a unit, a range, a step. BPM and Start (20–300), Fade (seconds). |
| `ReadOutButton.tsx` | `CueSheet`'s private `ReadOut` (`:692`), lifted: a read-out whose display is a press. TAP here. |
| `LibraryNameColumn.tsx` (new) | The `firstColumn.render` every library uses: the name `TextCell` through `firstColumnCellProps`, a lock or source glyph, and the `onOpen` pencil. A render helper, not a `SheetColumn`. |
| `useBatchDelete.ts` + `BatchDeleteDialog.tsx` (new) | `{ remove(id, force) → ok \| inUse(summary) \| refused(reason) }` per entity; D13's one dialog. Reports its own failures (D14). |
| `libraryScope.ts` (new) | `libraryPermission(isCurrentProject)` → `permission` + copy, as `LOCKED_REASON` (`CueSheet.tsx:64`) is for the cue sheet. |
| `reportSheetWriteFailure` | D14's toast by code, keyed per column. |

### 3.2 Each sheet's columns

The name is the sheet's `firstColumn` (§3.1), not a row of these tables. A dash is "not offered".
*Skipped* is `value: undefined`, dropped before `write` and named on the read-out. Every write is
one request per row, as the patch list's address batch is.

**Speed Masters** (`routes/SpeedMasters.tsx`, `components/speedMasters/SpeedMasterSheet.tsx`). The
name column shows `M<n>`, the name and *Global* on master 1; a rename is `PUT {name}`.

| Column | Cell | Kind | Writes | Clear |
|---|---|---|---|---|
| (beat) | read-out | — | `BeatIndicator` | — |
| BPM | `NumberCell` 20–300 | `bpm` | `speedMasters.setBpm` per master, live (null uuid for master 1); followers skipped; read-only off the current project (D12) | refused |
| Tap | `ReadOutButton` | — | `speedMasters.tap`, one master; inert on a follower and off the current project | — |
| Start | `NumberCell` 20–300 | `start` | `PUT {bpm}`; followers skipped | refused |
| Follows | `OptionCell` Manual · eligible leaders | `leader` | `PUT {followTargetUuid, followNum, followDen}` — a new link starts at `DEFAULT_FOLLOW_RATIO`; Manual sends all three null; master 1 skipped. Options per row from `eligibleFollowTargets` (memoised — the cell is `memo`), but a commit carries the **origin's** choice to every selected row, so `write` also skips a row for which that leader is not eligible (itself, or its own follower — otherwise the server's `SPEED_MASTER_FOLLOW_CYCLE`). Master 1's two spellings (null target, its uuid) are normalised before comparing, as `SpeedMasterDetailSheet`'s `canonicalTarget` does | Manual |
| Ratio | `OptionCell` `FOLLOW_RATIOS` | `ratio` | `PUT {followNum, followDen}` — both halves, no target, no bpm (the busk rail's rule); manual masters skipped | refused |
| Usage | `OptionCell` None · Dimmer · Colour · Position | `usage` | `PUT {usage}`; refused over more than one row unless None; a usage another master holds is the server's `SPEED_MASTER_USAGE_TAKEN`, toasted by the sheet (D14) | None |
| Used by | read-out | — | `referenceCount` (followers included) | — |
| Notes | `TextCell` | `notes` | `PUT {notes}` | empty |

Verbs: Set · Clear · Delete (master 1 skipped) · Deselect. No Spread. No chips.

**Looks** (`routes/Looks.tsx`). A rename is `PUT {name}` — metadata only, never rows.

| Column | Cell | Writes |
|---|---|---|
| Families · Preview · Contents | read-outs | derived server-side |
| Notes | `TextCell` | `PUT {notes}`; Clear empties |
| Cue layers · Busk pages | read-outs | `layerCount` · `buskPageCount` |

Verbs: Set · Clear · Include (one) · Pick up (one) · Duplicate · Copy to… · Delete · Deselect.
Create: *Record from programmer*. No chips.

**Templates** (`routes/Templates.tsx`). A rename is `PUT {name}`. `TemplateSummary.rows` is optional
on the wire (an effect template's empty list is omitted), so every reader here takes `rows ?? []`.

| Column | Cell | Kind | Writes | Clear | Spread |
|---|---|---|---|---|---|
| Holds | read-out | — | fixed at creation | — | — |
| Value | `TemplateValueCell` (D6) — read-out until session 3 | none (§5 session 3) | `PUT {rows}`; generic value templates only; `write` drops rows of another family | refused | — |
| Fade | `NumberCell` seconds | `fade` | `PUT {fadeDurationMs, fadeDurationMsPresent: true}`; value templates | *default (none)* — null is "the caller's default", and a press sends none, so it applies at 0 (`projectTemplates.kt:394`) | `duration` |
| Master | `OptionCell` masters | `master` | `PUT {effect}` with one field changed: **`speedMasterUuid` for a `BEAT` effect, `rateSpeedMasterUuid` for a `WALL_CLOCK` one** (§Speed Masters' two references). The PUT deletes and recreates the effect row and republishes (`projectTemplates.kt:244-247`), so a running instance restarts. Effect templates only | M1 · *unscaled* | — |
| Notes | `TextCell` | `notes` | `PUT {notes, notesPresent: true}` | empty | — |
| Layers · Pages · Pressed | read-outs | — | `layerCount` · `buskPageCount` · `lastPressedAt` (patched live by `templatePressed`) | — | — |

Verbs: Set · Clear · Spread · Pick up (one) · Duplicate · Copy to… · Delete · Deselect. Chips:
All · Intensity · Colour · Position · Beam.

**FX Library** (`routes/FxLibrary.tsx` → `components/fxLibrary/`). A row is **custom** when a
definition in `GET fx/definitions` (a route the client has never called; a new `useFxDefinitionListQuery`)
has an `effectId` equal to the entry's id; a `USER` entry that matches none is **script-registered**.
That is the discriminator §1's bug needs, with no wire change. A rename is
`PUT fx/definitions/{id} {name}` on custom rows only.

| Column | Cell | Writes |
|---|---|---|
| Output · Mode · Timing · Params · Drives · Source | read-outs | the registry entry; Source is Built-in · Custom · Script |

Verbs: Fork (built-ins, current project) · Delete (custom only) · Deselect — no cell verbs, since no
column is editable. Chips: All · Dimmer · Colour · Position · Controls · Composite. A
script-registered row opens its **script** (`/scripts/:scriptId`), a custom one its definition, a
built-in its detail sheet.

**Scripts** (`routes/ProjectScripts.tsx`). A rename is `PUT {name, script, scriptType}` from the
list's own copy of the row: the route replaces the whole row and `NewScript.scriptType` defaults to
`GENERAL` (`projectScripts.kt:278`), so a rename that left the type out would downgrade the script.

| Column | Cell | Writes |
|---|---|---|
| Type · Lines | read-outs | — |
| Check | read-out | the last Compile in this tab (D8) |
| Used by | read-out | an `FX_DEFINITION` script: the effects it registers — script-registered library entries whose `sourceDefinitionId` is this script (the FX Library's discriminator). Anything else reads *—* until `FU-SCRIPT-USED-BY`: cue hooks exist only on full cue details, and no list carries them. |

Verbs: Compile · Run (one) · Copy to… · Delete · Deselect. Compile sends each script's **text** from
the list (`POST …/scripts/compile {script, scriptType}` compiles a literal, not an id). Compile,
Run, a rename and Delete are current-project only; another project's scripts are the read-only
scope. Chips: All and one per type present (short labels).

### 3.3 The wire — one route

- **`POST /projects/{projectId}/templates/{templateId}/copy`**, body
  `CopyTemplateRequest {targetProjectId, newName?}`, answer `CopyTemplateResponse {templateId,
  templateName, targetProjectId, targetProjectName, message}` — `copyLook`'s shapes. `withProject`
  on the source; a template of another project → 404; a name clash in the target → 409. A fresh
  `uuid`, `lastPressedAt` null; notes, `fadeDuration`, every row (fresh child uuids) and the effect
  copied. A copy into another project keeps a per-fixture template's keys and an effect's speed
  master uuid as they are — the same trade `copyLook` makes; a key that is not patched there reads
  as unhealthy, a master that is not there resolves to master 1. Fires `templateListChanged`.
- **`POST …/scripts/{scriptId}/copy`** copies `scriptType` (the bug in §1). No shape change.

Nothing else moves on the wire. Every other write in §3.2 has a route today.

## 4. UX — what the design draws

`library-sheets-design/`: **Main** (today, the rules, the calls), **Kit** (the additions and the
file map), **Model** (every column and verb, the sessions), and the five surfaces at 1180×820 —
Speed Masters (⏎ over two BPMs), Looks (three rows and the row verbs), Templates (a Fade marquee),
FX Library (dividers and read-only built-ins) and Scripts (a batch Compile filling Check).

## 5. Implementation — five sessions

Each session ends with `npm run check` / the gradle suite green, its CLAUDE.md or engineering-doc
paragraphs written, a browser pass on the dev desk at a desk width and the 1180×820 iPad frame, and
its done-marker here.

### ~~Session 0 — the template copy route (lighting7)~~ — done, lighting7 `1f16441`

- The route, its DTOs and resource in `routes/projectTemplates.kt`; `CopyTemplateOutcome` on
  `CopyLookOutcome`'s model (§3.3).
- The script copy keeping `scriptType`.
- Tests: the copy (same project, another project, a name clash, a foreign template), that rows and
  the effect arrive with fresh uuids, and that a copied script keeps its type.
- `docs/lighting-composition-model.md` §"Looks and layers" names the route beside the Look's.
- **Restart the desk** after it lands: a new route does not hot-swap. Session 2's Duplicate and
  Copy to… need it; sessions 1 and 3 do not.

### ~~Session 1 — the kit's library half, on Speed Masters (lighting-react)~~ — done, lighting-react `042919f8`

- Every piece in §3.1, the skipped-rows change to `useSheet` included — it changes what `write`
  receives on the three existing kit sheets too, so their tests are re-read against it (the cue
  sheet's Curve on a snap cue is the one existing `value: undefined`). The patch list moves onto
  `firstColumn.onOpen` (its hand-rolled pencil goes) and gains ⏎-opens-row.
- The Speed Masters sheet (§3.2) replaces the row cards: `SpeedMasterRow` is deleted, the detail
  sheet stays as what the pencil opens, and its delete moves onto `useBatchDelete`. BPM and TAP
  read-only off the current project (D12) — which fixes the page's live-tempo bug (§1).
- `SpeedMasterInUseResponse` gains `followerNames`, and the dialog names them.
- CLAUDE.md: a **§Library sheets** section (the rules D1–D5, D11–D14 and the kit pieces), and
  §Speed Masters amended in three places. The list of surfaces offering TAP and click-to-type
  **stays at four**: the new BPM cell and TAP read-out replace `SpeedMasterRow` in it, and keep its
  refusals (a follower cannot be typed or tapped). The stored default is no longer "editable only
  in the detail sheet" — the Start column edits it, and is labelled as the boot tempo. Linking and
  unlinking are no longer the sheet's alone — the Follows column does both on this page, under the
  same rules (both halves or neither, never `bpm` beside them); the busk rail still only retunes.

### ~~Session 2 — Looks and Templates (lighting-react)~~ — done, lighting-react `dca9a1f9`

- Both sheets on the kit (§3.2), with Value a read-out. `LookListRow`, `TemplateListRow` and both
  delete + in-use dialog pairs are deleted; `LookFamilyFilterBar` becomes `PartitionChips` (the
  route keeps `looks.family` and `?family=`; `TemplatePicker` mounts it controlled), with family
  dividers under *All*.
- CLAUDE.md: §The hand — the `/looks` and `/templates` *row menus* that carried *Pick up* are gone;
  it is a bar verb over one row now. §Looks, templates and layers — "`/templates` is a flat list
  ordered by name" gains the family dividers under *All* (still name-ordered within each, still no
  stored order).
- Include, Pick up, Duplicate and Copy to… over both copy routes; the batch delete over both in-use
  shapes, the template's `fxReferenceCount` / `runningCount` included.
- Another project's library as the read-only scope (D12): today a row click there opens
  `CopyLookDialog`; now the scope's *Copy to…* does.
- Needs session 0's restarted desk.

### ~~Session 3 — template values in the cell (lighting-react)~~ — done, lighting-react `83243b2e`

- Lift `FamilyControls` and its four family controls out of `TemplateEditor.tsx` into
  `components/templates/familyControls/` **together with the rows half**: `seedValues`
  (`TemplateEditor.tsx:1060`), `effectiveColourPolicy` (`:688`), `colourPolicyLocked` (`:674`) and
  the rows builder in `TemplateEditor`'s `rows` memo (`:185-205`). The rgbonly rule — an explicit
  white or amber row forces the colour row's policy — is applied **there**, to the saved rows;
  `ColourControl` only reads it for display (`:727`). Lifting the controls without it would let the
  cell send the combination the write boundary refuses by name (a 400). The editor imports all of
  it back unchanged.
- Mount them in `TemplateValueCell` through `EditorSurface`, seeded by `seedValues` and committing
  through the lifted rows builder, so a template's value has one control and one grammar in two
  hosts.
- **The family rule lives in `write`, not in `kind`.** `kind` belongs to a column
  (`sheetModel.ts:119`), and the origin column's `write` always receives its whole group
  (`:201-205`), so one Value column cannot carry a per-row `value:<family>` kind. The column has no
  `kind` (it takes commits only from its own editor) and its `write` drops, and names as skipped,
  every row whose family is not the origin's. A commit over several value templates of one family
  writes each.
- `TemplateEditor.test.tsx` keeps passing untouched; the cell's tests pin the `rows` it sends,
  including a white row forcing rgbonly.

### ~~Session 4 — Scripts and the FX Library (lighting-react)~~ — done, lighting-react `a3699076`

- Both sheets on the kit (§3.2): type and category chips with dividers; the sidebar and the mobile
  type sheet go; the collapsible categories go.
- Batch Compile and the Check column (D8), with results held in the route (a `Map<scriptId,
  result>` invalidated when that script's text changes); Run over one.
- `useFxDefinitionListQuery` over the existing `GET fx/definitions`, invalidated by
  `fxDefinitionListChanged` like the library; custom vs script-registered by `effectId` (§3.2) —
  the `sourceDefinitionId` fix (§1) and the Scripts sheet's Used by both read it.
- Fork (D9) with a minted, library-unique `effectId`; built-in and script-registered rows
  read-only.
- `FxLibrary.tsx` splits into `components/fxLibrary/` — the sheet, the three sheets it opens.
- The script *Copy to…* arrives in the target with its type (session 0).

Sessions 2 and 4 are independent of each other and of 3; all three need session 1's kit. Session 3
needs session 2's Templates sheet.

## 6. Migration

None. Every stored preference keeps its key (`looks.family` for the template chips); no schema
change; the copy route is additive, and a desk that predates it 404s the request, which the sheet
reports (D14).

## 7. Explicitly out of scope

- A bulk route for any of the five (every batch is N requests).
- A Look value grid (D5) and any family filter on Looks (D3).
- Stored order on any library: names ascending, masters by index.
- A spread over colour templates — building a palette of eight in one drag — which would need the
  desk to resolve intents without heads (Main board, *Not proposed*).
- Counted renames over a batch (D4).
- Pressing a template or Look from its library sheet: pads stay on the programmer and the busk view.

## 8. Follow-ups to record

- `FU-SCRIPT-USED-BY` — a server-side "used by" for scripts (`usedByProperties` is always empty
  today; cue hooks live only on full cue details), which the Scripts sheet's Used by column reads
  when it lands.

## 9. Verification

Beyond the unit suites, at the desk after each session:

- **Speed Masters.** Drag down BPM over two manual masters, ⏎, type 90: both tiles in the ShowBar
  move now, and the Start column does not. Include a follower in the marquee: the read-out names it
  as skipped and its tempo is untouched. Link M5 to M1 from Follows, then set its Ratio to ⅓; unlink
  with Clear. Choose M3 in Follows over a marquee that includes M3: M3 is skipped, not sent. Set
  Usage *Colour* on two rows: refused before anything is sent; on one row whose usage another master
  holds: the sheet's toast names it. Open another project's Speed Masters: BPM and TAP are inert,
  and the live master 1 is untouched. Delete a master in use
  alongside one that is not: one dialog, naming the in-use one and its followers.
- **Looks and Templates.** Select three Looks from the name column; Duplicate, then Delete the
  copies (one in a cue layer): one dialog. ⏎ on one row opens its sheet; a double click on its name
  renames. Filter Templates by Colour, then All: dividers return. Spread Fade over three templates.
  Open another project's Templates: every value verb disabled, *Copy to…* live.
- **Template values.** Edit a colour template's value in the cell and in the editor: the two draw
  the same control and land the same `rows`. Press the template on the programmer: the rig follows
  the new value.
- **Scripts and FX.** Compile four scripts, one broken: three ✓, one error line; edit the broken
  one and its Check reads *not checked*. Fork *Pulse*, edit the fork, press an effect using it; delete the fork and
  *Pulse* is still in the library. Open
  *Warm Flicker* (script-registered): the script opens. Copy an `FX_APPLICATION` script to another
  project: it arrives as an FX application.
- **The patch list** still opens a fixture from its pencil, and now from ⏎ on one row.

## 10. Scope honesty

The boards drew four client sessions and no backend; call 8 adds session 0. A fact-check of this
plan against the code the same day moved the name column out of the marquee (the Kit board's
`nameColumn` was a `SheetColumn`), added the skipped-rows read-out and D14 to the kit, made BPM and
TAP read-only off the current project, found the discriminator the FX Library needs, and moved the
rgbonly rule and the family rule to where the code actually enforces them. The Model board's
Used by column shows cue hooks for application scripts, which no list query can supply — here it
reads *—* for those until `FU-SCRIPT-USED-BY`. D6's cell is described on the boards as the editor
kit's intent editors; lifting `TemplateEditor`'s own controls is the same behaviour with one copy
fewer. The library row's fold below 600px, and each sheet's column widths, are not known until
measured in the app; the rules are the decision, the numbers are not.

## 11. Open questions

None. Chris answered all eight calls on 2026-09-23.
