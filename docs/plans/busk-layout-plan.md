# Busk layout — pages of rows, columns and banks the operator builds

> **Document status: COMPLETE — session 1 (the model and the routes) landed 2026-09-04 as
> `63e519f`; session 2 (the busk view) landed 2026-09-04 as lighting-react `89649d6`; session 3
> (the removals) landed 2026-09-04 as lighting-react `f024713` and lighting7 `fed63a8`;
> session 4 (the edges) landed 2026-09-05 as lighting7 `444956f` and lighting-react `3fa49e5`.** The seven desk checks of §9 are **staged, not run** —
> they are `FU-MANUAL-BUSK-LAYOUT` in [`manual-validation.md`](manual-validation.md). Where a
> session refined a
> decision, the text below says "session 1 amendment" … "session 4 amendment"
> beside it. The visual design is settled
> and checked in beside this plan at [`look-groups-design/`](look-groups-design/INDEX.md) —
> eleven static artboards on two canvas pages: the busk view in play and edit mode, the build
> flow, the rows/columns/banks structure, the model and delta, the FX cue slots overlay fed from
> the busk library, a survey of other desks, and the three directions considered (A: Look groups
> mirroring template groups; B: one library group for both kinds; C: this plan). The live canvas
> at <https://claude.ai/code/artifact/4eb72004-4860-4297-b6e3-27b3f1f39dd9> is a convenience
> copy, private to Chris; the checked-in files are the authority. This document is the engineering
> half: the model, the decisions and their reasons, and the session split. Where wording here and
> the artboards disagree, this plan wins on behaviour and the artboards win on layout and copy.

## 1. Context

The brief began as "template groups and ordering, but for Looks", so that a set of complex move
patterns could be mutually exclusive on the busk view. Working it through showed the group pattern
was not wrong but typed too narrowly: a template group is two facts the cook never reads — a
*place* in the library's order and a set of *siblings* a press releases — plus a one-family rule
that is template-shaped. A Look spans families by construction, and the operator's example (a
position palette and a movement pattern that must not run together) needs a group that holds both
kinds. Direction B on the canvas generalised the library group to do that. Chris chose Direction C
instead: **the busk page becomes a layout the operator builds**, referencing library records,
owning order and exclusivity itself, and the library goes back to being a flat list.

What that replaces, all of it on the dev desk only and none of it shipped anywhere else:

- **Template groups and ordering** — `template_groups`, `templates.group_id`, the shared
  `sort_order` across both tables, `POST /templates/reorder`, the `/template-groups` CRUD, the
  one-family rule enforced at three write boundaries (`templateLayout.kt`, `projectTemplates.kt`),
  `siblingUuids()` on the template toggle, and the `TemplateLayoutList` / `TemplateGroupRow`
  drag-and-drop on `/templates` with `lib/templateLayout.ts` mirroring the server's tie-break.
- **Looks' raw `sort_order`** — written by the client on `PUT`, never renumbered, no reorder route.
- **Pinned cues** — `cues.pinned_to_busk`, the Pin toggle in `CuePropsPane`, and the busk view's
  cue column (`BuskCueStacks.tsx`: one card per runnable stack plus the pinned pads, firing
  `POST /show/go-to` with `cueId` — the additive field on `GoToStackRequest` whose docblock says
  it exists for exactly this).
- **The busk view's automatic layout** — `BuskPools.tsx`'s four family columns, `columnRuns`
  coalescing a group's members into a cluster, and the Looks pool of every Look with a deferred
  effect.
- **The FX cue slots overlay's own editing** — `CueSlotEditAssignPanel`, the long-press wiggle
  mode, and the slot's ability to hold a cue *stack*. The overlay itself stays (D7).

The busking-view plan's decision that "a busk pad presses a named thing from the library onto the
selection" stands. Every pad is still a template, a Look or a cue. What changes hands is who says
where a pad sits and what else goes off when it is pressed: the operator, in the layout, instead
of the library's family and group.

## 2. Decisions taken

- **D1 — the page is the operator's, the library is flat.** Order and grouping mean something
  only on a busk page. `/templates` and `/looks` list by name, keep their family filters, and lose
  drag-and-drop and *New group*. A row may later carry an "on *n* pages" hint (§11).
- **D2 — rows, columns, banks, pads; no coordinates.** A page is rows. A row is columns, each with
  a width share (¼, ⅓, ½, ⅔, ¾, full). A column stacks banks top to bottom. A bank has a name,
  `solo`, and a `flow` — pads wrap to the bank's width, or run one per line. Solo never decides a
  bank's shape. Nothing is positioned by hand, nothing overlaps, and columns stack on a narrow
  screen. This is MagicQ's fixed execute grid, not QLC+'s free canvas: free positioning is fiddly
  on a touch surface and never survives a narrower screen.
- **D3 — a pad is a reference, never a copy.** `{kind: TEMPLATE | LOOK | CUE, uuid}`, ordered
  within its bank. One record may sit on several pads. Deleting the record deletes its pads — a pad
  is an enrichment, not a guard, so the template and Look delete guards do not learn about pads.
- **D4 — a press goes through the pad, and the bank decides the siblings.**
  `POST /busk/pads/{padId}/press` reads the pad, its record and — when the bank is solo — the
  records on its sibling pads, in one transaction, exactly as the template toggle reads its group
  today (`projectTemplates.kt` ~400: "the siblings are read beside the source so a concurrent
  regroup cannot release the wrong set"). It then calls the existing `ProgrammerLayerStack.toggle`
  with `releaseSiblings`, which is already uuid-keyed and kind-blind. **`toggle` does not change;
  the engine gains one additive method**, `release(sourceUuids)` — one mutation, one recook — for
  the cue press's wholesale sweep (D6), which no existing operation could do in one frame (session
  1 amendment; `ProgrammerLayerStackTest` is untouched). `/templates/{id}/toggle` and
  `/looks/{id}/toggle` remain for the programmer's ⌥click strip and the AI, always siblingless.
- **D5 — every pad is a toggle, and a cue pad presses the way a cue slot does.** A cue pad is
  apply/stop through `CueStackManager` (`POST /cues/{id}/apply` and `/stop`), lit from its stack's
  `activeCueId`, live without being the playhead — the FS-BUG-CUESLOT-LIVENESS decision. The
  pinned pad's move-the-playhead press (`GoToStackRequest.cueId`) loses its only caller and goes.
  GO and BACK for the playhead stay on the ShowBar.
- **D6 — solo has one meaning for every kind: pressing one turns its siblings off.** A layer
  sibling (template or Look) is narrowed on the pressed heads through the existing `withoutTargets`
  rule. A cue sibling that is live is stopped. A cue press turns its layer siblings off wholesale,
  because a cue has no targets. Solo off means the bank stacks — the behaviour ungrouped pads have
  today, now a choice per bank.
- **D7 — the FX cue slots overlay stays as coded, and is fed from the busk library.** It has no
  selection, so it can hold only what needs none: cues, and Looks with no deferred effect (rows are
  always bound, so that is the whole test). `cue_slots.cue_stack_id` becomes `look_id`. A Look slot
  presses `POST /looks/{id}/toggle` with no targets, and the route derives them from the Look's own
  fixtures when it has no deferred effect (today it 400s on an empty target list; that stays for a
  busk-only Look). The paged grid of eight, tap-to-toggle, paging and swap-by-drag stay. The wiggle
  mode and `CueSlotEditAssignPanel` go: the tiles grow their crosses and become drop targets exactly
  while the busk view is in edit mode, taking rows from the same library palette the banks take.
  A "busk strip" that mirrored a page on every route was drawn and rejected as too complex for the
  overlay's use.
- **D8 — editing happens in place, and every gesture saves.** *Edit layout* on the busk view turns
  the page editable: bank names become fields, Solo a switch, pads grow a cross, a lifted bank sees
  three drop zones (beside a column → new column; under a bank → stack in that column; below the
  page → new row), and the page ends in *+ Row*. Each gesture writes the whole page (D10). *Done*
  only leaves the mode. Pads do not press while editing and the target band dims to say so.
- **D9 — the library is a palette, not a picker.** While editing, the speed rail's place is taken
  by a drawer listing templates, Looks and cues under one search, a kind filter and a family filter,
  every row draggable onto a bank or a slot. A record already on the page says so and may be placed
  again. Long-press lifts on touch — the same `useLongPress` the pads use to open their editor
  outside edit mode. No ticking, no confirm.
- **D10 — one write for a page, the whole page.** `PUT /busk/pages/{pid}/layout` takes rows of
  columns of banks of pads, every bank named once, pads without an id created, and renumbers
  densely — the `applyTemplateLayout` shape ("a partial list cannot express a move out"), which this
  plan retires along with the route that carried it. Reasons of the same kind apply: a partial
  document cannot say "this column is now empty".
- **D11 — no migration.** Template groups, templates' and Looks' order, pinned cues and cue-stack
  slots exist only on the dev desk. Their tables and columns go outright and nothing is backfilled.
  The one starting state to build is the **empty-project default**: on first open with no pages, the
  desk offers *Start from your library* (a stacking bank per family, the buskable Looks, and a Cues
  bank of nothing — there are no pinned cues to carry) or *Start empty*.
- **D12 — sync `formatVersion` 10, writer bump, `minReader` stays 5.** The v9 argument verbatim:
  a layout changes what a press does on stage, so a v9 install must refuse the repo rather than
  silently drop it.

## 3. The model

### 3.1 Schema

Four new tables, all portable show content (CLAUDE.md's decision tree, branch 2):

| Table | Columns | Notes |
| --- | --- | --- |
| `busk_pages` | `project_id`, `name`, `sort_order`, `uuid` | `uniqueIndex(project, name)`, the template/Look identity rule |
| `busk_columns` | `page_id`, `row`, `sort_order`, `width`, `uuid` | `width` is a share in twelfths (3, 4, 6, 8, 9, 12); `row` is a dense integer the layout route renumbers |
| `busk_banks` | `column_id`, `sort_order`, `name`, `solo`, `flow`, `uuid` | `flow` ∈ `WRAP`, `COLUMN`; `name` may repeat across banks |
| `busk_pads` | `bank_id`, `sort_order`, `template_id?`, `look_id?`, `cue_id?`, `uuid` | exactly one FK set, validated in the route — the `DaoCueLayers` pattern, and for the same reason: SQLite enforces no cascade without a pragma, so the routes validate by hand |

Deleting a template, Look or cue deletes its pads inside the same transaction the delete already
runs (the counterpart of `templates.group_id` having no `ReferenceOption`). A page delete removes
its columns, banks and pads.

`cue_slots` loses `cue_stack_id` and gains `look_id` (nullable, exactly one of the two set).

**Removed:** `template_groups`; `templates.group_id`; `templates.sort_order`; `looks.sort_order`;
`cues.pinned_to_busk`. Every disposition row in `SyncCoverageTest.dispositions` follows.

### 3.2 The write boundary

- `POST /projects/{id}/busk/pages` (name → appends), `PUT .../{pid}` (rename),
  `DELETE .../{pid}`, `POST .../pages/reorder` (every page id once).
- `PUT /projects/{id}/busk/pages/{pid}/layout` — the whole page:
  `rows: [{columns: [{columnId?, width, banks: [{bankId?, name, solo, flow, pads: [{padId?, templateId|lookId|cueId}]}]}]}]`
  (int ids, like every REST body; uuids are sync's — session 1 amendment). Validates shape (400
  `BUSK_LAYOUT_INVALID`), then identity (every named column/bank/pad id belongs to this page, none
  twice — 400 `BUSK_LAYOUT_IDENTITY`), then every record resolves in this project (400
  `BUSK_LAYOUT_REF`), then writes densely from zero. Returns before touching a row on any refusal.
  An empty column or row is refused (the client removes them as it edits); an empty bank and an
  empty page are legal. Widths in a row need not sum to twelve; the client renders them as `fr`
  shares. **Answers the page as written**, because the client's next whole-page write must carry
  the ids this one minted.
- `POST /projects/{id}/busk/banks/{bankId}/pads` `{templateId|lookId|cueId}` — appends **one** pad
  to one bank and answers the whole `BuskPageDto` (session 4 amendment; the additive exception to
  D10, for the surfaces that place a pad without opening the busk view). Same validation order and
  the same `BUSK_LAYOUT_INVALID` / `_REF` codes; an unknown bank, or one on another project's page,
  is 404. The record is resolved **before** the pad is minted — a pad row with no arm trips
  `busk_pad_exactly_one_ref` at flush and would turn a refusal into a 500.
- `POST /projects/{id}/busk/pads/{padId}/press` `{targets}` → by kind: template →
  `toggle(source, targets, familyMask, siblings)`; Look → `toggle(source, targets, null, siblings)`,
  with the Look toggle's empty-targets rule (`LOOK_NEEDS_SELECTION` / `LOOK_NO_TARGETS`); cue →
  apply when its stack's `activeCueId` is not this cue, else stop; siblings resolved as D4/D6.
  Response `{kind, action, effectCount, released}`, `released` counting layer siblings narrowed or
  dropped plus cue siblings stopped.
- `POST /projects/{id}/looks/{id}/toggle` with empty `targets` succeeds for a Look with no deferred
  effect by taking the Look's own *patched* fixtures as the targets (D7); a Look with a deferred
  effect answers 400 `LOOK_NEEDS_SELECTION`, one with no patched fixture 400 `LOOK_NO_TARGETS`.
  Unchanged otherwise.
- `POST /projects/{id}/cue-slots` accepts `lookId` in place of `cueStackId`, and refuses a Look with
  a deferred effect (409, `CUE_SLOT_LOOK_NEEDS_SELECTION`).
- All of the above are `withCurrentProject`-gated like the cue-slot writes.

### 3.3 The read side

- `GET /projects/{id}/busk/pages` (+ `GET .../{pid}`) — every page, nested (session 1 amendment:
  the collection GET on the plural collection, per `docs/api-conventions.md`). A `BuskPadDto`
  carries `kind`, `uuid` and **the record's own summary DTO** — `template: TemplateDto?`,
  `look: LookDto?`, `cue: BuskCueDto?` (`id, uuid, name, cueNumber?, cueStackId, cueStackName`) —
  rather than a flattened name / swatch / detail line: the client already derives a pad's face from
  the summary it holds for the library row, and an effect template's detail line reads a *live*
  speed-master label a server string would freeze. One record's DTO is built once per response.
- Lit state is unchanged: templates and Looks from `programmer.layerState.applied` (per record
  uuid, so a record on two pads lights on both and either pad turns it off); cues from the cue
  stack list's `activeCueId`. The FX cue slots overlay gains the `applied` feed for its Look tiles.
- WebSocket: one keyed frame, `busk.layoutChanged {pageIds}` (dotted, per the naming rule in
  `docs/websocket-engineering.md`), for every layout write, page CRUD and reorder, and for a record
  delete that took pads off a page. `templateListChanged` and `lookListChanged` shed their "reordered or moved between
  groups" meaning. `cueSlotListChanged` is unchanged.

### 3.4 Sync

`buskPages/{uuid}.json` — one canonical document per page with rows, columns, banks and pads
nested; pads reference records by uuid; a dangling reference — or a pad naming none or two — drops
the pad with a warning (a pad is an enrichment, D3). The structural fields have no defaults in
their DTOs, so a zero position is written rather than omitted. `cueSlots/{uuid}.json`: `cueStackUuid` → `lookUuid`. `templateGroups/`
goes; `TemplateJson.groupUuid`, `TemplateJson.sortOrder`, `LookJson.sortOrder` and
`CueJson.pinnedToBusk` go. Import order: pages after templates, Looks and cues. `formatVersion`
10 per D12; `docs/sync-engineering.md` gains a "Version 10" section beside "Version 9".
`RichProjectFixture` gets a page with two rows, a stacked column, a solo bank holding all three
kinds, a `COLUMN`-flow bank, and a slot holding a Look — every field non-default, because
canonical JSON omits defaults.

## 4. UX — what the design draws

Grep-able summary; the artboards are the authority on layout and copy.

- **Busk view, play** (`Main.dc.html`): ShowBar; target band; a page strip (`seg big`) with *Edit
  layout* at its right; rows of columns of banks; a bank is a `rounded-md` cluster with a 11px/600
  name and a small *solo* tag when solo; pads are the shipped `LookPadButton` (56px, presence
  ladder) and a cue pad is the shipped pinned-cue pad (mono number, name, stack, green when live);
  the speed rail is unchanged. No family columns, no Looks pool, no stack cards, no pinned grid.
- **Busk view, edit** (`Edit.dc.html`): the strip gains *+ Page*, *Saved* and a primary *Done*;
  bank headers become grip + name field + *Solo* switch + ⋯ (Rename, Width ¼…Full, Flow Wrap/Column,
  Duplicate, Delete); pads grow a top-left cross; a *+ Bank* column slot ends each row and a
  *+ Row* strip ends the page; the target band dims; the library palette replaces the speed rail
  (360px): search, kind filter, family filter, draggable rows with an *on page* badge; one row is
  mid-drag with its drop slot open in a bank.
- **Rows, columns, banks** (`Layout.dc.html`): the structure as nested boxes; the three drop
  zones; width shares and flow.
- **Building a page** (`Flows.dc.html`): first open (*Start from your library* / *Start empty*);
  edit layout; drag from the library; arrange (pads and banks); solo on/off with the cue case;
  *Add to busk page* from a cue's properties and the template/Look editors, and *Also add to
  <bank>* on *Save as template*.
- **FX cue slots** (`Slots.dc.html`): the shipped overlay above the busk view in edit mode; tiles
  with crosses; an empty tile showing *+* under a dragged bound Look; busk-only Looks and templates
  dimmed in the palette with *needs a selection*.

## 5. Implementation — four sessions

Backend first with the new API beside the old, then the busk view, then the removals, then the
edges. Each session ends green (`./gradlew test`; `npm test` in `lighting-react`).

### Models

Which Claude Code model runs each session, and at what effort. The judgement is where silent
failures live, not where the most code is.

| Session | Model | Effort | Why |
| --- | --- | --- | --- |
| 1 — the model and the routes | Fable 5.1 | high | The invariant-dense one: four tables through the sync decision tree, a format bump, whole-page validation, the one-transaction sibling read, target derivation on the Look toggle. Its failures are silent (a fixture field left at its default, a disposition row missed, a dangling reference that 500s). |
| 2 — the busk view | Opus 5 | xhigh | A lot of UI code, moderate reasoning, the canvas to match. The one subtle piece is dnd-kit's context spanning the page, the palette and the header overlay. Fast mode is available on Opus 5 for the visual iteration. |
| 3 — the removals | Opus 5 | high | Well specified but wide across two repos, docs and tests; the risk is stragglers, not reasoning. Sonnet 5 at high is an acceptable saving. |
| 4 — the edges | Sonnet 5 | high | Small, well-scoped additions. The desk checks are a human job. |

**Reviews run on `/code-review-lite`** (the built-in review with its subagents one tier down) or a
plain Opus 5 review after every session, session 1 included. Not on Fable: a review there burns
quota far faster than the implementation does, and the session-1 risks are covered instead by its
tests (§9) and the round-trip and coverage suites that already fail on a missed rule.

`max` effort nowhere: on these models a lower effort matches the previous generation's best, and
`xhigh` is the coding sweet spot on Opus 5.

### Session 1 — the model and the routes (lighting7) — Fable 5.1, high

- `models/buskLayout.kt`: the four tables (§3.1) with a docblock in the `templates.kt` style
  recording D2–D4 and the no-cascade rule. Register in `Schema.ALL_TABLES`; disposition rows in
  `SyncCoverageTest`.
- `routes/projectBusk.kt`: pages CRUD + reorder, `PUT .../layout`, `GET /busk`. `routes/buskPress.kt`:
  the press route. `Fixtures.buskLayoutChanged(pageIds)` and `BuskLayoutChangedOutMessage` (`busk.layoutChanged`).
- `projectLooks.kt` toggle: derive targets for a Look with no deferred effect (D7).
- `projectCueSlots.kt` / `models/cueSlots.kt`: `look_id`, the eligibility refusal, `CueSlotDetails.itemType`
  gains `"look"`. The stack arm stays until session 3, so the assign is three-way for now.
- Deletes: a template, Look or cue delete sweeps its pads inside its own transaction and fires
  `busk.layoutChanged`; so does the **cue-stack** delete through its cues (a path §5 first missed).
  Found in passing and fixed: `cue_slots.cue_id`'s declared `CASCADE` is not enforced by SQLite, so
  a cue delete left a slot whose read `error()`ed; the cue, stack and Look deletes now sweep slots too.
- Sync: exporter, importer, DTOs, `formatVersion` 10, `RichProjectFixture`, `ProjectRoundTripTest`.
- Tests: `BuskLayoutRoutesTest` (whole-page validation: shape, identity, dangling ref, empty column
  refused, dense renumbering, deletes cascade by hand), `BuskPressRouteTest` (each kind; solo
  narrows a layer sibling and stops a cue sibling; a cue press turns layer siblings off; a stacking
  bank releases nothing; a record on two pads lights on both and either turns it off),
  `CueSlotLivenessRouteTest` extended for a Look slot, `LookRoutesTest` for target derivation.
- Docs: `docs/lighting-composition-model.md` gains §"The busk layout" beside §"Applied state is
  resolved by the desk"; `docs/websocket-engineering.md` row; `docs/sync-engineering.md` v10.

### Session 2 — the busk view (lighting-react) — Opus 5, xhigh — **landed**, `89649d6`

- `store/busk.ts` (RTK Query: page CRUD, reorder, the layout PUT and the press), `api/buskWsApi.ts`
  for `busk.layoutChanged`, `lib/buskLayout.ts` for the document and every gesture over it.
- `BuskingView.tsx` renders pages → rows → columns → banks → pads from the document; `BuskPools` and
  `BuskCueStacks` retire (their tests with them); `LookPadButton` and the cue pad survive as the two
  faces of one `BuskPadButton`; `BuskLabel`, `TargetBand`, `BuskSpeedRail`, `TargetList` untouched.
- Edit mode: `BuskEditProvider`, bank header controls, the three bank drop zones (D8), pad drag
  within and between banks, `LibraryPalette.tsx` (D9). Every gesture PUTs the whole page.
- First-open state (D11) and *Start from your library*, client-side from the two list queries.

**Session 2 amendments**, where the plan and the code diverged:

- **§10's dnd-kit worry was wrong on the facts.** `Layout.tsx` already wrapped the cue-slot overlay
  *and* the routed page in one `DndContext`; they were never under different providers. The real
  constraint is **nesting** — a context inside the routed page wins for its subtree and would hide
  the busk page from the overlay's droppables, which is the drop session 3 needs. So the busk page
  joins the existing context through `useDndMonitor`, and `CueSlotDndProvider` became
  `components/dnd/DeskDndProvider.tsx`: it is the app's one drag context, not the cue slots'.
- **No `SortableContext`, and the preview is a ghosted source plus one dashed slot.** The primary
  gesture is a *palette* drop, and a palette row is in no sortable list, so the placeholder is
  hand-drawn either way; the three bank zones are not list indices. `applyDrop` runs once, on drop.
- **`lookPresence.ts` survives.** §5's wording above reads as retiring it; there is no column logic
  in that file (it was in `BuskPools`), and both functions are still exactly what a pad's ring asks.
- **Edit mode is a Redux slice, not a React context** — session 3's overlay is a *sibling* of the
  routed page in `Layout.tsx` and could never read a context provided inside the busk view.
- **The empty-selection dim is gone, and must not come back.** A per-fixture template names its own
  heads, a Look with no deferred effect names its own fixtures, and a cue has no targets — all three
  press with nothing selected. The two that cannot are refused by name (`TEMPLATE_NEEDS_SELECTION`,
  `LOOK_NEEDS_SELECTION`), which is a better answer than a grey page; and a bank mixes kinds, so the
  old per-section dim has nothing left to be per.
- **A cue pad reads `useActiveCueIds`, and the view holds no transport.** GO and BACK stay on the
  ShowBar. `GoToStackRequest.cueId` therefore already has no caller — session 3 deletes it.
- **The page showing lives in `?page=`**, deep-linkable beside `?cue=`. The wrong-project redirect
  in `routes/Busk.tsx` deliberately **drops** it: a page id is project-scoped, so carrying it would
  name nothing.

**§11 settled by this session**: save **per gesture**, with *Done* only leaving the mode; and the
first-open generator builds §11's drafted content — a stacking bank per family, the Looks with
deferred effects, an empty Cues bank, **nothing solo**, reading neither template groups nor pins, so
session 3's removals leave it untouched.

**Review** (`/code-review-lite` on Sonnet, `high`): seven findings fixed, three declined. The one
worth carrying forward: **a pad drop target is an insertion point, not an `arrayMove` destination**
— it names the gap the dashed slot is drawn in, counted with the source still in place. Reading it
as a destination made every *downward* drag within a bank overshoot by exactly one place, and it
survived because each half of the pipeline was self-consistent and only their composition was
wrong; two tests agreed with the bug. `buskDnd.test.ts` now composes `resolveDropTarget` with
`applyDrop` and asserts the slot and the landing place agree.

**Not done**: the desk checks in §9. They are a human job and none has been run.

### Session 3 — the removals (both repos) — Opus 5, high — **landed**, lighting7 `fed63a8`, lighting-react `f024713`

- lighting7: drop `template_groups`, `templates.group_id`, both `sort_order`s, `pinned_to_busk`,
  `cue_stack_id`; delete `projectTemplateGroups.kt`, `templateLayout.kt`, the reorder route,
  `groupFamilyClash` and `TEMPLATE_GROUP_FAMILY`, `siblingUuids()`, `TemplateInput.groupId/groupIdPresent`,
  `TemplateDto.groupId/sortOrder`, `LookDto.sortOrder`, `GoToStackRequest.cueId`,
  `TemplateGroupRoutesTest`, the layout tests; `GET /looks` and `/templates` order by name.
- lighting-react: delete `TemplateLayoutList`, `TemplateGroupRow`, `lib/templateLayout.ts`, the
  *New group* / *Ungroup* dialogs in `Templates.tsx`, Looks' raw sort order, the Pin toggle in
  `CuePropsPane`, `CueSlotEditAssignPanel`, the wiggle mode in `CueSlotOverviewPanel`; the overlay's
  tiles become droppable and crossable only while `useBuskEditMode()` is true, and read `applied`
  for Look tiles.
- CLAUDE.md: the template-group paragraph, the Template Endpoints list, the WebSocket list, and the
  FX section's "template group" sentence. `followups.md`: close `FU-TMPL-LAYOUT-FAMILY-SCOPE`,
  `FU-TMPL-LAYOUT-SIGNAL`, `FU-TMPL-GROUP-MISSING-404`, `FU-TMPL-GROUP-AI` as one-line Completed
  rows; reword `FU-BUSK-MOMENTARY`'s "every pad left is a layer toggle" (cue pads are toggles too).
  `manual-validation.md`: the cue-slot drag-vs-collapse check (its edit mode is now the busk
  view's) and `FU-MANUAL-BUSK-VIEW` / `FU-MANUAL-FX-TEMPLATE-PADS`, which describe family columns.

**Session 3 amendments**, where the plan and the code diverged:

- **`formatVersion` stays 10, and there is no bump.** `SyncDtos.kt:38-42` had already pre-declared
  these removals under that number; nothing but the dev desk wrote v10 between the two sessions. The
  one new rule that needed writing is its consequence: a v10 archive written *before* this session
  carries `cueStackUuid` on a slot, which `ignoreUnknownKeys` drops, so the slot arrives naming
  nothing. The importer's arity check therefore **splits** — two arms still aborts, none warns and
  drops the slot, the busk pad's posture applied to a slot. `ProjectRoundTripTest` guards it.
- **`useBuskEditMode()` did not exist.** Session 2 landed it as the `buskEdit` Redux slice
  (`selectBuskEdit`); this session added the hook over it, selecting the **boolean** rather than the
  state object so a page change does not re-render eight tiles.
- **"Looks' raw sort order" was DTO-only on the frontend** — no ordering UI, no writer, three type
  fields and four fixtures.
- **`TemplateStrip.tsx` was a fifth consumer of `buildTemplateLayout`** the plan's §5 list missed;
  it now renders the name-ordered list directly.
- **The palette→slot drop resolves in `DeskDndProvider`, not in a cue-slot monitor.** The panel body
  is behind `CollapsiblePanel` and unmounts when the overlay hides — the one place a slot mutation
  must not live. The provider already owns the slot droppables, `projectId` and both mutations, and
  already accepted a foreign source onto its own target. Its docblock's "mutual ignorance" paragraph
  was rewritten: the ignorance is of **ids and foreign targets**, never of sources. The mapping
  itself is pure in `components/dnd/slotDrop.ts`, imported type-only, so the shell's runtime import
  graph still never reaches the busk feature.
- **A lit Look tile does not look like a lit cue tile.** A live cue keeps its solid fill (this cue is
  its stack's live cue); a Look on the rig gets `BuskPad`'s presence vocabulary — border, faint tint,
  ring, corner pip. Two different claims, and the busk view already draws them differently.
- **`lookPresence.ts` needed a third function**, not a relaxed guard. `appliedPresence` answers
  `'none'` for an empty target list *by design*; a slot has no selection, so `lookIsApplied` asks the
  selection-independent question instead.
- **Two things went that the plan did not list**: `useActiveCueStackIds` (the panel was its only
  production caller) and the panel's *Edit slots* context-menu item, whose only job was entering the
  deleted wiggle mode. *View* and *Clear slot* stay, and the cross grew an `aria-label`.
- **One rescue and one addition to the tests.** `TemplateGroupRoutesTest`'s
  `widening the selection and pressing twice leaves the pad off` never had a group in it — it is the
  plain pad's sequence — and moved into `TemplateRoutesTest`, beside a new
  `the toggle route releases nothing` that pins D4's siblingless rule now that nothing else does.
- **New follow-up raised**: `FU-SLOT-DROP-OVERLAY-HIDDEN` — the slot droppables live inside
  `CollapsiblePanel`, so with the overlay shut a palette row dropped at the header lands on nothing,
  silently.

**Not done**: `slotDrop.ts` ships without its own unit test (Chris's call: one focused panel test
rather than two suites), so the eligibility mapping is covered only through
`CueSlotOverviewPanel.test.tsx` and the palette's own refusal test.

### Session 4 — the edges (both repos) — Sonnet 5, high — **landed**, lighting7 `444956f`, lighting-react `3fa49e5`

- *Add to busk page* (page → bank picker that appends) in `CuePropsPane`, `TemplateEditor` and
  `LookDetailSheet`; *Also add to <bank>* on the programmer's *Save as template* sheet.
- The `?family=` filter on `/looks` and `/templates` kept; an "on *n* pages" hint per row if §11
  settles for it.
- AI: `AiToolSchemas` gains nothing this round (§7); the "groups" vocabulary at line 208 refers to
  fixture groups and is untouched.
- Desk checks (§9) run and recorded.

**Session 4 amendments**, where the plan and the code diverged:

- **It ran on Opus 5, not the Sonnet 5 the table above planned for.** Fairly, in hindsight: "small,
  well-scoped additions" turned out to include a new route with a database-enforced ordering rule, a
  count threaded through two DTOs and an embedded one, and a client cache-invalidation policy. The
  review still ran on `/code-review-lite`, and earned its keep.

- **The append is a route, not a client-side splice.** `POST /busk/banks/{bankId}/pads` appends one
  pad and answers the whole page. The client could have done it with what it had — `applyDrop`'s
  `{kind:'pad', at:{…, pad: pads.length}}` target *is* the append case, and `useBuskLayoutCommit`
  already replays ops against the last confirmed document — but that makes four surfaces which never
  *show* the page each hold a whole page document and re-`PUT` it, turning
  `FU-BUSK-EDIT-CONCURRENCY`'s stale-document race from the exception into the normal path. D10 is
  untouched: its argument is about editing ("a partial document cannot say *this column is now
  empty*"), and an append can never empty anything. The bank is addressed by **id**, which is also
  the address that survives a page being reshuffled underneath the menu.
- **Two bugs the route's own tests found, both about ordering.** The append created the pad before
  resolving its record, so a refused reference committed a pad with no arm and the
  `busk_pad_exactly_one_ref` check turned a 400 into a 500 — the file's "returns before touching a
  row" rule, here enforced by the database rather than by discipline. And `buskPageCount` on an
  *embedded* pad summary made "this page is exactly as it was" false the moment a **different** page
  gained a pad; the existing identity test had snapshotted its page before writing the second one.
  The coupling is real but not new — `layerCount` on the same embedded summary has always moved when
  an unrelated cue changed — so the test re-reads its baseline instead.
- **`buskPageCount` carries no default.** The REST `Json` is `routes/router.kt`'s bare `json()`, so
  `encodeDefaults = false`: a defaulted zero would simply not be on the wire and the client would
  read `undefined` where its type says `number`. `layerCount` beside it has no default either, for
  exactly this reason.
- **The count is refreshed on the client, and by who caused the change.** A layout write moves it
  but fires no `templateListChanged`; firing one would refetch the whole library on every edit-mode
  gesture, and review found that cost is real — `LibraryPalette` is mounted and subscribed to both
  lists for the whole of busk edit mode, which a first version of this claimed was not the case. So
  an *own* write invalidates the two lists only when `recordsOnPage` actually moved (an append
  always; a drag, reorder or resize never), and only a *foreign* frame invalidates them wholesale.
- **`?family=` on `/looks` was already gone, and stays gone.** §5 S4's "kept" reads as "don't remove
  these while you are in here". `routes/Looks.tsx` carries the argument against re-adding one — a
  Look's families are derived and one may span several, so a filter would hide most of the library
  from most filters — which is a Looks-page decision this plan never revisited. The REST `?family=`
  param stays on both list GETs; only the `/looks` page UI stays absent.
- **One picker everywhere, not a checkbox.** The artboard draws *Also add to \<bank\>* as a
  pre-checked checkbox on *Save as template*; it shipped as the same page → bank menu the other
  surfaces use, pre-filled from the last bank used, with *Don't add a pad* in it. One control means a
  bank chosen in the template editor and one chosen mid-show teach each other through one remembered
  target. It went on **both** programmer create sheets (*Save as template* and *New from selection*),
  which both mint a template mid-show and both want the pad.
- **Placement from a create sheet is deferred and fails soft.** The pad names an id the template does
  not have until its POST answers, so the choice is held and the append runs after. If the append
  fails the template still stands, the failure is reported by the error-toast middleware, and the
  success line does not claim a pad it did not get.
- **`savingPages` became a refcount.** Two independent writers for one page — the commit queue and an
  append — would have raced a `Set`: whichever settled first would clear the flag while the other was
  still writing. Unreachable in the shipped UI (the picker's surfaces and the busk view are different
  routes), but the queue's own docblock already warns about exactly this race, and a count removes
  the class of it rather than arguing the two cannot overlap.
- **Review found the create-sheet control was dead, and it shipped green.** `useAddToBuskPage`
  returned no targets whenever it had no `record` — which is exactly the create case, where the
  template does not exist until the POST answers. So *Also add to \<bank\>* rendered nothing on both
  sheets and the placement branch added to their submit handlers was unreachable, with `tsc`, lint
  and every suite passing. The record is only needed for the "already here" tick; the pages and banks
  never depended on it. `AlsoAddToBuskRow.test.tsx` now pins it, and it is the reason that file
  exists.
- **A failed write must not open the echo window.** `endBuskPageWrite` stamped `settledAt` from a
  `finally`, so a *rejected* append suppressed genuine `busk.layoutChanged` frames for 500 ms — for
  an echo that was never broadcast, because the write did not land. It takes a `landed` flag now.
- **`onQueryStarted` has to catch.** RTK Query attaches no handler to the promise it returns, so a
  rejected append escaped as an unhandled rejection. `reorderBuskPages` in the same file already
  caught for this reason.
- **The picker takes no router.** An "open the busk view" item would have made every sheet and
  property pane that renders this control a navigation source; the empty state names the view
  instead.

### Session 5 — the first desk use (lighting-react) — Fable 5.1

The §9 desk checks had not been run when session 4 landed. The first one on a real desk found
three things, all client-side:

- **Neither `+ Bank` nor `+ Row` had ever succeeded.** `newBank()` defaulted the name to `''`, and
  `applyBuskLayout` refuses a blank name before touching a row — so the optimistic patch drew the
  bank, the PUT 400'd with "A bank at row 1, column 5 has a blank name", the queue rolled back and
  toasted. ("Column 5" is the column `+ Bank` appends, 1-based; the button making a column is D8
  working.) `newBank` takes a required name now and `nextBankName` mints `Bank N` for the smallest
  free N; the test named "mints documents the server would accept" asserts the name it never did.
- **A bank drag resolved to a pad target.** The resolver picked the deepest collision whatever was
  lifted, and the pad and body droppables were only disabled outside edit mode, so a bank over a
  pad opened a dashed slot inside the bank and then dropped nowhere — `dropBank` refuses a pad
  target, and the monitor returned before committing. Session 2's review carry-forward ("a pad drop
  target is an insertion point") had composed the resolver and the mutator for pads only; the bank
  half was the same class of defect. Legal targets now follow the source on both halves (a filter
  in `resolveDropTarget`, and `disabled` per source on the droppables), and `buskDnd.test.ts`
  composes the bank drag with `applyDrop` for all three zones.
- **Three more reasons the slot did not track the pointer**, each a dnd-kit fact the code had
  assumed the opposite of: `onDragOver` fires only when the over-id *changes* (the hover is fed
  from `onDragMove` as well now); `MeasuringStrategy.Always` is not a timer, so rects went stale
  the moment the slot opened and shifted everything below it (the provider re-measures on each
  target change, and the slot is kept sticky within its bank so fresh rects cannot throw it to the
  end); and the `closestCenter` fallback answered with *every* droppable, so the deepest-wins
  reader could name a pad three banks from the strip `over` had lit (it is cut to one).
- **Columns stack below 600px of the page body**, by container query — D2's promise, never
  implemented. Editing at narrow widths is out of scope by decision: below `md` the palette is not
  shown and *Edit layout* is hidden with it.

`FU-MANUAL-BUSK-LAYOUT` is still to be recorded once the remaining §9 checks are run.

## 6. Migration

None (D11). The only versioning is the sync format bump (D12).

## 7. Explicitly out of scope

- Pages on a MIDI surface (page up/down as `BindingTarget`s) and pad-to-control bindings — hardware
  keeps naming cues and stacks directly.
- AI tools for the layout (`add_busk_pad`, `create_busk_bank`).
- A pad size or a bank height beyond width and flow; free positioning.
- Momentary (flash) pads — `FU-BUSK-MOMENTARY` stands.
- Any busk strip or mirroring of a page outside the busk view (rejected, D7).
- Cue-stack pads. A slot could hold a stack; nothing else could, and nobody asked for it on a page.

## 8. Follow-ups to record

- `FU-BUSK-PAGE-MIDI` — Trigger: an operator wants to change busk page from hardware.
- `FU-BUSK-AI-LAYOUT` — Trigger: the AI is asked to put something on the busk page.
- `FU-BUSK-PAD-SIZE` — Trigger: a page needs more density than width and flow give.
- `FU-BUSK-EDIT-CONCURRENCY` — Trigger: two desks edit one page at once. Per-gesture saves mean a
  half-built page is live for a second operator; the alternative (hold until *Done*, with Discard)
  is recorded in §11.
- `FU-SLOT-LOOK-ELIGIBILITY` — Trigger: a rows-only Look on a slot that asserts nothing on the
  fixtures it names. Sits beside `FU-LOOK-COMPAT-ROW-COVERAGE`, which is the same question asked of
  busk pads.
- `FU-BUSK-ON-PAGES-HINT` — Trigger: an operator deletes a template and is surprised pads went with it.

## 9. Verification

Backend: the tests in §5 S1, plus the existing `ProgrammerLayerStackTest` unchanged (the engine
does not move — if it has to, the plan is wrong). Frontend: §5 S2. Desk checks, to be added to
`manual-validation.md` as `FU-MANUAL-BUSK-LAYOUT` when they are run, not before:

1. Build a page from empty: two rows, a stacked column, a `COLUMN` bank; reload; it is the same page.
2. Solo bank of a position template and a movement Look: press each on the movers; the other goes
   dark on those heads and nothing else moves. Press the Look on two heads only; the template stays
   lit on the rest.
3. A cue pad beside them in the same solo bank: pressing it turns both off and lights the cue;
   pressing it again stops the cue and lights nothing. GO on the ShowBar is unaffected throughout.
4. The same template on two pads on two pages: lit on both; either turns it off.
5. Drag a bound Look from the palette into a slot; tap it on the Show view with no selection; it
   comes on on its own fixtures and lights the tile. A busk-only Look will not drop.
6. Delete a template that is on three pads: the pads go, the pages render, no 500.
7. A narrow window: columns stack, nothing overlaps, nothing is unreachable.

## 10. Scope honesty

This is a large change — four tables, a new routing family, and the removal of two recently landed
features (template groups, FX templates' busk placement) plus pinned cues and the cue-slot assign
flow. What keeps it bounded: the release engine, `appliedState`, the pad faces, the target band,
the speed rail and the cue stack manager are untouched, and the layout route reuses the
whole-document validation shape the reorder route already proved. The riskiest lines are the press
route's one-transaction sibling read (copy the template toggle's), the Look toggle's target
derivation (a Look with rows on a fixture that is no longer patched must resolve to nothing, not
500 — `TemplateResolver`'s unresolvable-target rule applies), and dnd-kit's context having to cover
both the busk page and the header overlay, which today live under different providers in
`Layout.tsx`.

## 11. Open questions

Carried from the canvas's sticky notes; each has a drafted answer the sessions build unless
overturned:

- **The library pages afterwards.** *Settled by session 4*: flat lists by name, no groups, no drag,
  the family filter kept on `/templates` and deliberately still absent from `/looks`, and the
  "on *n* pages" hint **built** — `buskPageCount` on both list DTOs, plus a line in the delete
  confirm. `FU-BUSK-ON-PAGES-HINT` closed.
- **Save per gesture vs on Done.** Drafted: per gesture, like every other editor on the desk, with
  *Done* only leaving the mode. Alternative: hold edits until *Done*, with Discard.
- **Which Looks a slot may hold.** Drafted: no deferred effect. A busk-only Look is refused at assign,
  not at press.
- **The first-open generator's content.** Drafted: a stacking bank per family holding that family's
  templates, a Looks bank of every Look with a deferred effect, an empty Cues bank; nothing solo.
