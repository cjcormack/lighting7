# Programmer space — the grid as the page

> **Document status: PROPOSED 2026-09-09.** Nothing here is built. Four sessions plus one
> optional, all in `lighting-react`; there is no backend work, and no route, protocol or table
> changes. The plan lives here because every plan does, and because the design it cites is
> committed alongside it.
>
> **The design is committed alongside this plan** at
> [`programmer-space-design/`](programmer-space-design/INDEX.md) — nine artboards drawn against
> `lighting-react`'s real tokens, control heights, rungs and ownership colours. Cited below as
> *(design: `Main`)*, meaning `programmer-space-design/Main.dc.html`; open it in a browser and it
> renders standalone. Read the INDEX first: it maps artboards to sessions. The live canvas is at
> <https://claude.ai/code/artifact/a8f3830e-092f-4a0e-93ad-06c309853453>, private to Chris; the
> committed files are the authority.
>
> **Scope: `lighting-react` only.** `ProgrammerPage`, the six components it stacks, `FixturesTable`,
> `ShowBar`'s narrowest rung and `Layout`'s sidebar default. The desk-simplification plan's model —
> scopes, layers, templates on the selection, the two apply gestures — is not touched. This is a
> layout plan: the same things, in less room.

---

## 1. Context

The programmer view (`/projects/:id/programmer`) is the one place values are edited, and its value
grid gets the least room of anything on the page. Measured from the app at 1440×900 with the
sidebar open, and from the classes in the code (design: `Budget`):

| | Today | Proposed |
|---|---|---|
| Grid height, 1440×900 | 371px · **41%** | 559px · **62%** (593px with nothing selected) |
| Grid width, 1440 | 752px · **52%** | 1076px · **75%** (1336px with the rail collapsed) |
| Grid height, iPhone 393×852 | 144px · **17%** | 508px · **60%** |

Where the height goes today, top to bottom: app header 56, ShowHeader 59, ShowBar 64, source strip
33, action bar 68, scope band 47, workspace padding 16, filter row 44, template strip 72, column
header 30. That is 489px before the first fixture, on a rig of seven fixtures with eight
templates. A real rig makes every one of those bands *taller*, not shorter: the template strip
wraps to four rows at forty templates, the action bar wraps below 560px, and the scope band wraps
once *Make layer* runs out of room.

Four causes, from the seat:

- **Six bands of chrome say six things one line each.** The source strip, the action bar and the
  scope band are three rows because each was designed on its own (`ProgrammerPage`'s doc comment
  calls them "six bands, deliberately separate siblings"). Two of them spend a line on a label
  (`STAGE · LOAD · SAVE`, `Showing`) and a sentence of explanation.
- **The template strip is always on, and it wraps.** With nothing selected it shows the whole
  library — for a press that can only toast "select the fixtures this should land on first".
- **The rail is 404px and fixed.** Below 900px of content width it drops *beneath* the grid, which
  on an iPad portrait leaves the grid a 26rem floor and the layers off-screen.
- **The phone gets the desktop, stacked.** Every band wraps to two or three rows, the ShowBar alone
  is 118px, and the first fixture row appears 700px down an 852px screen.

And one thing the mockups surfaced that the brief did not ask for: **a selected cell and a cell you
own look alike.** Both are primary blue — the row wash, the checkbox, the marquee and the
`programmer` ownership ring all use `--primary` — so the two facts the grid most needs to keep apart
share one colour.

---

## 2. Decisions taken

Settled. A session should implement these, not re-open them.

**D1 — The grid is the page.** Everything above and beside it earns its place by the line. The
numbers in §1 are the acceptance test: at 1440×900 the grid gets at least 60% of the height and
75% of the width with the rail open. *(design: `Main`, `Budget`)*

**D2 — Two rows, not six bands.** Row A is the *noun and the verbs*: the source strip's content
(Editing Q4 · Warm Wash · 3 changes · Update · Revert, or "Programmer is empty…") on the left,
Clear + fade / Include / Record on the right, one 40px row. Row B is *what the grid shows*: the
Output / Local / focused-layer toggle, the filter, Lit, Groups, Columns, one 36px row, spanning the
grid column only. The zone labels and the scope band's explanatory sentences become tooltips and
`aria-label`s; nothing they said is lost, it is said on hover. `Make layer` moves onto the rail's
Local-values row (D6). *(design: `Main` rows A and B)*

**D3 — The templates ride the selection bar.** The strip is one line, scrolling sideways under a
fade, inside the bar that already appears when something is selected. **With nothing selected the
strip does not render.** This reverses the current rule ("nothing selected: the whole library
shows, and a press toasts that it has nowhere to land") — a row of chips that can only toast is
the single most expensive line on the page. The library is browsed on `/templates`; the strip is
where a template is *pressed*, and a press needs a target. The `targets.length === 0` guard in
`TemplateStrip.press` stays, as defence in depth. *(design: `Main` row C)*

**D4 — Selection is neutral; colour is ownership.** A selected row gets a 3px `--foreground` left
edge, a `foreground/6%` wash and a bold name; the checkbox is foreground-filled with a dark tick;
the marquee is a 2px solid `--foreground` frame with foreground corner handles over a `foreground/5%`
fill; the selection bar is `foreground/5%`. `--primary` is then reserved for *you own this value*,
which is what the ownership legend already says it means. The six ownership rings do not change.
*(design: `Main` rows SL Wash 1–4)*

**D5 — The cell marks share one gutter.** The effect badge (top-right) and the layer glyph
(bottom-right) sit at the same `right` inset, and the cell body reserves 16px on the right so a
value never runs under either. Today the value text is centred across the whole cell and the marks
float over its last characters. *(design: `Main`, the Colour column)*

**D6 — The rail is 300px, drag-to-size, and collapses to a 40px strip.** Default 300, bounds
260–480, remembered per desk in `localStorage`. Collapsed, it is a 40px strip carrying the two
counts (layers, FX) and the `+` door. **Under 1200px of content width it starts collapsed and opens
as an overlay over the grid** rather than squeezing it; the stacked-below-the-grid layout at
`@max-[900px]` goes entirely. One list, two bands — Local values and the layers, then the boundary,
then the effects — under one footer of `+ Look · + Template · + Effect`, which is what
`ProgrammerWorkspace`'s doc comment has said Session 2 would build since the day it shipped.
*(design: `Main`, `TabletLandscape`, `TabletPortrait`)*

**D7 — The sidebar defaults to its collapsed rail on the live views.** `Layout` already has the
64px state; the four live views (Programmer · Show · Prompt Book · Busk) simply start there, and
remember their own toggle separately from the rest of the app. That is 176px of width for free, on
every live view, and the pill switcher in `ShowHeader` is how those views move between each other
anyway.

**D8 — The phone gets its own arms, not the desktop stacked.** The ShowBar's `<440px` rung becomes
**one row**: DBO · BLIND · tempo chip · `Q4 → Q5` · BACK · GO, 44px of controls in a 56px band,
replacing the chips-then-52px-GO pair. Rows A and B go to icon arms. The value columns scroll
sideways under a fade with the name column at its existing `min(45vw, 260px)`. The rail is a
**bottom sheet** with a 44px handle naming the layers. The ownership legend goes behind a key
button. Below 500px of *height* the app header scrolls with the page and rows A and B fold into
one. *(design: `Phone`, `PhoneLandscape`)*

**D9 — The one-band chrome is optional and separate.** Folding the ShowHeader into the ShowBar
saves 59px on all four live views and is drawn (design: `ChromeMerge`), but it touches surfaces
this plan otherwise leaves alone. It is Session 5, decided after Session 1 lands and the height is
felt at a desk. Every number in §1 is computed *without* it; with it the desktop grid reaches 72%.

**Not decided, and deliberately: the rail's side.** `DirectionB` (a bottom band) and `DirectionC`
(a left rail) are on page 2 of the canvas as records. The right-hand rail keeps `FixturesTable`'s
sticky name column against the page edge and is the only arrangement that can collapse without
moving the grid.

---

## 3. Constraints that carry over

Written here so no session rediscovers them.

- **The grid must never remount on a scope change, a rail toggle, a sheet open or a width
  change.** `useListSelection` clears its Redux scope on unmount, so a conditional mount or a
  `key` per state silently discards the fixture selection Record scopes on.
  `ProgrammerPage.test.tsx`'s `gridMounts` assertion is the gate; Sessions 3 and 4 both add
  cases to it. The *rail's* contents may unmount freely — nothing in it owns a selection — and
  `FxSheet` must stay mount-on-demand for the reason `ProgrammerRail` documents.
- **`@container` is a wrapper, and the queried classes go on its child.** `ProgrammerWorkspace`'s
  doc comment records the bug; D6's 1200px rule and D8's arms are all container queries and all
  need the same shape. Rows A and B each declare their own `@container`, as the action bar does
  today.
- **The bar wraps rather than deleting.** `ShowBar`'s rung ladder is a ladder because a
  `shrink-0` tile that hid information at 560px crushed the live block; D8's one-row arm adds a
  rung, it does not remove the `basis-full` transport fallback in the 440–700 band.
- **`ProgrammerBody` is the memo barrier** for the whole grid/rail subtree. Rail width, collapsed
  state and sheet-open state are new pieces of state that must not be held *above* it in
  `ProgrammerPage`, or every ShowBar re-render reaches the grid again.
- **Sheets for editing, dialogs for confirmation** (`CLAUDE.md` §Sheets vs Dialogs). The phone's
  rail is a `Sheet` with `side="bottom"`, and the guarded-close rules apply to nothing in it — it
  holds no draft.
- **The two apply gestures stay on the chip's `title`.** Moving the strip does not move the
  click / ⌥click contract or its tests.

---

## 4. Implementation — four sessions, one optional

Each session is one shippable commit on `main` that leaves `npm run check` green and the page
usable at every width. They are ordered so each one's screenshot is the acceptance test for the
next one's starting point.

### Session 1 — Two rows

**Outcome.** The six bands above the grid are two. At 1440×900 the grid gains ~190px of height
before anything else changes.

*(design: `Main` rows A and B; `Budget`)*

- **`ProgrammerSourceStrip` becomes the left half of row A.** Same five states
  (`resolveProgrammerSource`), same tone classes, drawn as a bordered 32px box that `flex:1`s and
  truncates its name before its badges. The location (`Act 1 · cue 4 of 14`) and the long badge
  text appear at `@[1100px]`, `Update Q4` shortens to `Update` below `@[800px]`, Revert becomes an
  icon below `@[1100px]`. `Update` and `Revert` stay *inside* the source box: they act on the thing
  it names.
- **`ProgrammerActionBar` becomes the right half.** `ActionZone` and `Divider` go; the three
  labels become `title`s on the split buttons, and `aria-label`s where the visible text shrinks.
  Below `@[800px]` Clear keeps its fade segment and loses its word, Include and Record go to icons.
  The Record destination menu is unchanged.
- **`ProgrammerScopeBand` becomes row B**, spanning the grid column only (it moves *inside*
  `ProgrammerGrid`'s toolbar slot, above the filter). The `ToggleGroup` stays; the explanatory
  sentences become the items' `title`s; the layer arm keeps its `LookNameBadge` in the third pill
  and its `asserts …` / `n targets` / save-state text collapses into one muted line that appears
  only in layer scope. The filter, Lit, Groups and Columns join it on the same line —
  `sheetControls` stops being a prop of the action bar. `Make layer` stays at row B's right end
  *until Session 3 moves it*.
- **`ProgrammerWorkspace` drops its `p-4` and `gap-3`.** The grid runs edge to edge; the rail gets
  a `border-l`. The `@max-[900px]` stacking stays for now (Session 3 replaces it).
- **`OwnershipLegend` becomes a 22px footer** with the row count and selection count on its left
  (`24 fixtures · 4 selected`), the same swatches, and the long glosses only at `@[1100px]`.
- **`Layout`: the sidebar defaults to collapsed on the live views** (D7). `open` becomes two
  persisted booleans keyed on whether the route is one of the four live views, default `false`
  there and `true` elsewhere; the toggle writes whichever applies.

**Tests.** `ProgrammerActionBar.test.tsx` and `ProgrammerSourceStrip.test.tsx` are rewritten for
the new arms (assert the `title`s carry the old labels — that is the promise). `ProgrammerPage.test.tsx`'s
`gridMounts` stays at 1 across a scope switch. A new `Layout` test pins the sidebar default per
route group.

**Cuts to record.** The scope band's *"Click a tinted cell to jump to whatever won it"* was the
only place that gesture was taught; a `title` on the Output pill is thinner. If it proves too
thin, the answer is a first-visit hint, not the sentence back.

### Session 2 — The selection bar, and selection that looks like selection

**Outcome.** The templates cost no height until there is something to press them onto, and a
selected cell cannot be mistaken for an owned one.

*(design: `Main` row C, the marquee, the Colour column; `Phone`'s selection bar)*

- **`TemplateStrip` moves into the selection bar** `ProgrammerGrid.renderToolbar` already draws
  (the `bg-primary/[0.09]` band). Order: marquee glyph · `4 fixtures · 8 cells` · family badge ·
  keyboard hints (`@[1100px]`) · a hairline · **the chips in a `flex-1 min-w-0 overflow-x-auto`
  scroller with a masked right edge** · `New` · Locate / Fan / Deselect. Chips keep their
  `values · hairline · effects` order. The strip returns `null` with no targets (D3); the empty
  state *"No template fits what is selected"* stays. `SelectionToolbar`'s Locate and Highlight lose
  their words below `@[1100px]`, as they already do below `sm`.
- **Neutral selection (D4)** in `FixturesTable`: `RowView`'s `bg-primary/10` → `bg-foreground/[0.06]`
  plus `shadow-[inset_3px_0_0_var(--foreground)]` and `font-semibold` on the name; the sticky name
  cell's tint overlay follows; the checkbox loses `accent-primary` for a foreground fill; the marquee
  band's `border-dashed border-primary bg-primary/[0.13]` → `border-2 border-foreground
  bg-foreground/5` with foreground handles; the selection bar's wash → `bg-foreground/5`. The
  floating scope chip stays primary — it follows the pointer and never sits still beside an owned
  cell.
- **The marks gutter (D5).** The cell wrapper gets `pr-4`; the effect badge and the layer glyph
  both sit at `right-1`; the template-division mark (`effectDriven`) already shares the layer
  glyph's corner and never coexists with it, so it moves with it. `PropertyCell`'s inner layout is
  untouched — the gutter is around it, for the reason `ownershipCellClass` documents.

**Tests.** `TemplateStrip.test.tsx` gains "renders nothing with no targets" and keeps the
click / ⌥click split. `FixturesTable.test.tsx` pins the selected row's class and that the marquee
band carries no `border-primary`. `OwnershipLegend.test.ts` is unchanged — the rings did not move.

### Session 3 — The rail

**Outcome.** The rail is one list that the operator can size, hide and — on a narrow page — pull
over the grid. Below 900px the layers are never off-screen again.

*(design: `Main`'s rail; `TabletLandscape`; `TabletPortrait`)*

- **`ProgrammerWorkspace` owns the rail state**, held *inside* `ProgrammerBody`'s memo barrier:
  `programmer.rail.width` (260–480, default 300) and `programmer.rail.collapsed` via
  `usePersistentState`, plus a transient `overlayOpen`. A 5px drag handle on the rail's left edge
  sets the width; `pointercancel` ends a drag like `pointerup` (the busk rail's lesson).
- **Three arms, by the workspace's own width** (`@container` wrapper, classes on the child):
  ≥1200 — docked, at the stored width, or the 40px strip when collapsed; <1200 — the strip always,
  and opening it mounts the rail as a 300px `absolute` overlay with a shadow over the grid's right
  edge, closed by the strip's chevron, Escape, or a click on the grid. The `@max-[900px]` stacking
  arm and its `min-h-[26rem]` floor are deleted; the grid is the scroller at every width, and the
  rail scrolls itself.
- **`ProgrammerRail` becomes one scroller with two bands.** Header: `LAYERS n · FX n` and the
  collapse chevron, 36px, level with row B. Body: a `VALUES · top wins` label, a new **Local values
  row** (count from `useLocalValueCount`, and the `Make layer` button moved here from row B), the
  `LookStack` rows at the density `Stack.dc.html` drew — two lines each, order badge, name, family
  badge, source line — then the amber *"Values above beat effects below"* boundary, then
  `ProgrammerFxList`'s rows. Footer: `+ Look · + Template · + Effect`, which is the existing
  `AddLayerSheet` opened at a kind plus `ProgrammerAddEffect`. `Per-fixture FX` stays a
  mount-on-demand disclosure at the foot of the body.
- **The 40px strip** carries the two counts as badges under their glyphs and one `+` that opens
  the same footer menu, so nothing is reachable only with the rail open.
- **`LookStack`'s row** gets a `dense` variant rather than a fork: the same `LayerHandlers`, the
  same drag, the same focus-by-name-badge, with the amount and blend behind the row's popover
  rather than inline. The cue editor's `LookStack` keeps its current density.

**Tests.** `ProgrammerPage.test.tsx`: `gridMounts` stays at 1 across collapse, resize and an
overlay open/close. `ProgrammerLookStack.test.tsx` covers the dense rows' handlers.
`ProgrammerFxList.test.tsx` is unchanged. A `ProgrammerWorkspace.test.tsx` pins the three arms by
width and that the stored width survives a remount.

### Session 4 — Small viewports

**Outcome.** A phone shows fixtures above the fold, and a landscape phone shows more than three.

*(design: `Phone`, `PhoneLandscape`, `TabletPortrait`'s ShowBar)*

- **`ShowBar`'s `<440` rung becomes one row** (D8): the DBO and BLIND tiles at their chip size,
  `SpeedMastersChip` without its `+n`, a `Q4 → Q5` live block that shows only the numbers, an
  icon BACK, and GO at 84px × 44px. The `basis-full` transport line stays for the 440–700 band.
  `ShowBar.test.tsx` gains the rung; the docblock's table gains a row.
- **Rows A and B get their icon arms** below `@[600px]`: the source box drops `Editing` and its
  glyph and keeps `Q4 · name · badge`; every verb is its icon; the filter becomes a search icon
  that opens the field; Groups and Columns are icons; a **key** icon opens the ownership legend as
  a popover (the footer is not rendered at this width).
- **The rail is a bottom sheet** below `md`: `ProgrammerWorkspace`'s third arm renders the strip
  as a 44px handle across the bottom (`LAYERS n · FX n · the layer names, truncated · ^`) and the
  body inside a `Sheet side="bottom"` at 80% height. Same `ProgrammerRail`, mounted only while
  open.
- **The value columns scroll sideways** with a 24px fade at the grid's right edge, drawn on the
  scroller's wrapper so it does not scroll with the content.
- **Short-height mode.** Under `@media (max-height: 500px)` `Layout`'s header stops being sticky
  and rows A and B render as one row (a `ProgrammerToolbarFolded` arm of the same two components,
  or a single container query on a shared wrapper — whichever keeps the memo barrier intact). The
  ShowHeader gets `py-2` there.

**Tests.** `ShowBar.test.tsx` for the new rung. A `ProgrammerWorkspace` case for the sheet arm,
asserting the grid's mount count across open/close. The rest is a browser pass at 393×852 and
852×393 (§6).

### Session 5 (optional) — One chrome band

Decide after Session 1 has been used at a desk. Folds `ShowHeader` into `ShowBar` on all four live
views: the pill switcher leads the bar, the breadcrumb goes (the sidebar's project switcher and the
active pill already say where you are), `SaveStatusIndicator` moves into the app header, Stop and
the live dot keep their right-anchored slot, and the masters take the railed tile. It holds at
1300px of content and wraps below that the way the bar already does. *(design: `ChromeMerge`)*
This is the one session that touches Show, the Prompt Book and Busk, and `useShowBarProps` /
`ShowHeader.test.tsx` / `ShowBar.test.tsx` would all move with it.

---

## 5. Explicitly out of scope

- **The programmer's model.** Scopes, the `LookRowStore`, `FocusedTemplateLayer`, the two apply
  gestures, Record / Include / Update, `cellKeyboardPermission`. Every one is reached from a
  different place afterwards and behaves the same.
- **The other three live views' bodies.** Show, the Prompt Book and Busk keep their layouts. They
  inherit D7 (sidebar default) and D8's ShowBar rung because those are shared chrome, and D9 only
  if Session 5 is taken.
- **`/fixtures/list` and `/groups/list`.** They mount the same `FixturesTable`, so D4 and D5 reach
  them — that is intended, a selected row should look the same everywhere — but their toolbars,
  filters and cards/list switcher are untouched.
- **DBO.** Still inert ([`FU-FE-DBO-INERT`](followups.md#fu-fe-dbo-inert)); this plan draws it
  where it is.

---

## 6. Verification

`npm run check` per session, and a browser pass at five sizes — the ones the artboards are drawn
at: 1440×900, 1180×820, 820×1180, 393×852, 852×393. At each, with Q4 included and four heads
selected: the first fixture row's `y`, the grid's width, and that nothing overflows its band. The
§1 table is the pass mark for the first and the last.

Then a desk pass, once, after Session 4 — the same rule every plan in this directory has learned:
a green suite called 2a working with two defects in it.

1. Busk a wash with the rail collapsed; open it from the strip at 1180 wide and confirm it covers
   the grid rather than moving it; drag it to 480 and back; reload and confirm the width held.
2. Select four colour cells, press a template from the selection bar, ⌥-press another, and read
   the rail: the layer arrived, Local shows the literal, and the selected cells read *white* while
   the owned cells read *blue*.
3. On a phone, do the same from the bottom sheet, then GO from the one-row bar with the sheet open.
4. Landscape phone: confirm five fixture rows are on screen with the header scrolled away.

Record the outcome in [`manual-validation.md`](manual-validation.md) as `FU-MANUAL-DESK-SPACE`.

---

## 7. Follow-ups to record

- **The Output-scope teaching line** (Session 1's cut). If a desk pass shows nobody clicks a
  tinted cell, record `FU-PROG-OUTPUT-JUMP-HINT` rather than putting the sentence back.
- **The templates with nothing selected.** D3 makes the library reachable from the programmer only
  via `/templates` or by selecting first. If an operator wants to *browse* from here, the answer
  is a chip that opens the library filtered to the selection's families — not the always-on strip.
- **`LookStack`'s two densities.** If the cue editor's stack wants the dense rows too, promote the
  variant to the default and delete the other; do not carry both indefinitely.
- **The rail's stored width is per desk, not per project.** Fine today; note it if a desk ever
  drives two very different rigs.

---

## 8. Sizing

Sessions 1–3 are each a day; Session 4 is the largest, because the short-height mode touches
`Layout`, and the bottom sheet is a third rail arm. Session 5 is half a day and a decision. None of
them needs the backend running for anything but the browser pass, and none needs a rig until §6.

## 9. Model and effort per session

Judged by where each session's risk is, not by its size.

| Session | Model | Effort | Why |
|---|---|---|---|
| 1 · Two rows | Opus 5 | medium | Mostly JSX restructuring across six components, but it moves state around `ProgrammerBody`'s memo barrier and touches `Layout`. The code is easy; the doc comments that record *why* each band went are where a smaller model drifts, and this repo treats those as load-bearing. |
| 2 · Selection bar | Sonnet 5 | high | The best-specified session: class swaps in `FixturesTable`, moving `TemplateStrip` into an existing slot, two test files. Low ambiguity, contained blast radius. High effort so it re-reads `ownershipCellClass` and the marquee before touching either. |
| 3 · The rail | Opus 5 or Fable | high | The riskiest one. New persisted state that must sit inside the memo barrier, three container-query arms with the wrapper trap, a drag handle that has to honour `pointercancel`, a dense variant carved out of the 700-line `LookStack`, and the grid-never-remounts rule across every transition. The `@container` bug that shaped `ProgrammerWorkspace` was found three sessions after it shipped, so this is where silent breakage lives. |
| 4 · Small viewports | Opus 5 | high | Cross-cutting: a new `ShowBar` rung on a ladder with a documented history of collisions, a short-height mode in `Layout`, and a bottom sheet that mounts the rail body while the grid stays put. Each piece is small; the interactions are not. |
| 5 · One chrome band | Sonnet 5 | medium | Half a day of moving chrome once the decision is taken. The decision itself is the operator's, at a desk, after Session 1. |

Two rules beyond the model choice. **Run a one-tier-down review after Sessions 3 and 4** at least
(`/code-review-lite` or `/verified-ship`): the failure class here is a rule stated in one place
and not another, which a fresh reader catches better than the author. And **keep Sessions 1 and 2
as separate commits** even though both are cheap — Session 2 changes what a selected row looks
like on `/fixtures/list` and `/groups/list` too, and that diff should be readable on its own.
