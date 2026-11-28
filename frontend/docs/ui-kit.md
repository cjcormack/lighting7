# UI kit

The shared building blocks: the sheet kit, the list shell, the library sheets, the editor kit and the cell editor's three forms. Moved out of `frontend/CLAUDE.md`, headings unchanged, so a citation of `CLAUDE.md §<heading>` lands here.

### Sheet kit

**One sheet, four surfaces.** The programmer's grid gestures — drag selection, single click
selects a cell, double click / ⏎ / typing opens one editor for every selected cell, ⌫ clears, one
editor per column spread over the selected columns, the Set · Clear · Fan bar — are a kit in
`components/sheet/`, mounted by the **patch list** (`components/patches/PatchSheet.tsx`), the
**DMX sheet** (`components/channels/DmxSheet.tsx`) and the **cue sheet**
(`components/runner/CueSheet.tsx`) as well as the programmer. The design record is
`../docs/plans/sheet-views-design/` (the `Kit` artboard is the module map, `Spec` the desk
survey and the rules). The surfaces differ in their columns and their verbs, never in the gesture.

**What lifted out of `components/fixtures-list/`**, generic over the column key (`CellRef<C>`,
`CellSelection<C>`): `cellSelectionModel`, `cellSelection`, `cellMarquee`, `listSelectionModel`,
`useCellSelection`, `cellEntry` (the generic half — `orderedSelectedCells`, the two DOM guards,
and the *shapes* `CellKeyboardPermission` / `CellActionCopy`; the programmer's own answers
`cellKeyboardPermission` / `cellActionCopy` stay in `fixtures-list/cellEntry.ts`),
the cell editor's surface and its two hooks, `ValueFieldRow` and `UnsetCellMark` (all four moved on
again since, to `components/editor/` — §The editor kit), the three fold
constants (`toolbarFolds.ts`, re-exported by `SelectionToolbar`), `CellSelectionActions`,
the fan popover and its `fanMath` (both since replaced by `editor/SpreadPanel` + `spreadPlans`,
below), `selectionBand`, `useEscapeEditorSnapshot`. Three were extractions rather
than moves: `useCellMarquee` (a local of `FixturesTable`, now generic over rows with a
`rowHeight` and an `isSelectableRow`), `useCellEditorRequests` (the open/close one-shots and the
Set toggle from the container), and `commitToSelectedCells` / `selectedRowsByColumn`
(`sheetModel.ts` — the container's `commitToCells` / `columnTargets` over rows instead of write
targets). The fixtures list keeps its columns, row model, ownership and scope, and
`FixturesListContainer` keeps its own keyboard listener: its tests pin it, and its selection is
Redux-scoped for readers outside the list.

**A `SheetColumn<Row, C>` per surface** says how to read a row (`value`), which cell it draws
(`cell`, or `display` for a read-out), whether it spreads (`spread` → a `SpreadPlan`), and what a
commit does (`write(rows, value)`, over the **batch**, answering false for a value it refuses) and
what Clear does (`clear`, or `clearRefusal` as the button's reason). **A commit spreads only to the
selected columns that share its origin's `kind`**: the programmer tells commits apart by shape,
but a cue's name, notes and fade are all one string, so each surface column names its vocabulary
(`level` on all sixteen DMX columns, one kind per column on the cue and patch sheets) and a `3s`
typed into Fade over a Fade→Follow marquee cannot switch auto-advance on. A read-out column hangs
no `data-column-header`, so a marquee never selects it. `useSheet` is the container half — one selection in two shapes, the keyboard, the editor
requests, the throttled commit, the batch count — and `SheetTable` the anatomy: 30px uppercase
header, 36px rows (56 on the DMX sheet), a sticky first column with the 3px selection edge, the
same DOM contract as `FixturesTable` (`data-grid-header`, `data-column-header`,
`data-grid-name-header`, `data-row-id`, `data-cell`). Five kit cells go through
`EditorSurface`, so they get the three forms and the double click for free: `TextCell`
(commit on ⏎, Escape reverts), `LevelCell` (the DMX value, live like `SliderCell`), `OptionCell`
(`SettingCell`'s type-ahead over plain options), `AddressCell` and `FixtureColourCell` (the patch
list's Colour, §Fixture colours — not `fixtures-list/cells/ColourCell`, which edits a light's
output). **A column may commit a typed character without opening its editor**:
`SheetColumn.typed(key)` answers a value for the first selected cell's column, and `useSheet`
commits it over the selection as the editor would (the Colour column's 1–9 and 0); undefined opens
the editor as before. A surface marks a row with `SheetTable`'s `rowClass`, and the sticky name cell
carries `data-name-cell` for the colour's wash.

**`SelectionBar` is a shell** — counts · family pill · ⏎/⌫ hints · a `strip` slot · a `verbs`
slot — and the programmer's `SelectionBar` fills it with its three clusters: the heads chip and the
mask in `leading` (a slot no other sheet passes, drawn in place of the counts), Set · Fan ·
Release ▾ in `strip`, Locate ▾ · Highlight as the verbs (§The programmer's scoped grid).
`CellSelectionActions` takes a `permission` of `CellKeyboardPermission`'s shape and a `spread`
slot, so a disabled button and a refused key always read one object, and each surface hands in its
own `SpreadPanel` instance. Surface verbs come after Fan; Deselect is always last and ghost.

**Fan is one panel, four plan kinds, every host — and the desk resolves it on both sides**
(editor-kit plan D1–D7, D15; `Spread.dc.html` is the layout authority, the busk tab wins on a
measurement). The verb was *Fan* until session 3 of that plan, then *Spread*, and *Fan* again since terminology
plan session 2; its mode about each head's own position (venue-visit S12) is *About each position*
(below).
`components/editor/SpreadPanel.tsx` is the busk tab's body plus the old fan popover's surface,
chooser and keyboard, hosted by the programmer's row C as a popover in the cell editor's three forms
(`fixtures-list/SpreadPopover.tsx` builds its plans from the marquee), by the busk view as the
docked tab (`busking/SpreadSheet.tsx`, §Focus and the side sheet), and by the patch list, the cue
sheet and the DMX sheet with plans of their own. The kinds: **`intent`** — desk-resolved, the
palette vocabulary: two intents of one attribute's shape, a curve, an order, runs and an
over-switch go to `POST /programmer/fan`, and one literal per head lands in Local (D3: the
programmer's rows are heads the desk knows better than the browser does — a group's member order,
each head's range, which elements a fixture has, what a colour means on a head with amber). The ends
are intents — a percent, a colour + policy or a `pal:` reference, a centre-relative position — so a fan from 0 to
full is *0% → 100%* on every head whatever its range, which the byte form never was, and the old
position exclusion went with the byte lerp it objected to. **`raw`** — bytes on a column outside
the vocabulary, Speed alone (D15; `rawValues` in `editor/spreadPlans.ts` is `fanValues` renamed and
kept for that one column, under the same Curve · Order · Runs · Over, with `spreadFractions`
mirroring `fx/FanPlan.kt`; if Speed ever stops offering Fan the kind goes with it).
**`address`** — From · Step in visible order, the patch list's walk (`walkAddresses`, which
`lib/patchAddress.ts` shares with the consecutive Set). **`duration`** — From · To with the curve
row where the one-option select was, the cue sheet's fade times (`spreadDurations`;
`FU-SPREAD-DURATION-CURVES`). `fanColours` is deleted: the client never interpolates an intent, and
`spreadIntent.test.ts` pins the import lists of `spreadIntent.ts`, `SpreadPanel` and
`SpreadPopover` — the panel reaches `spreadPlans.ts` only for the raw arm, the popover walks
nothing. The panel is **store-free**, so the three kit sheets mount it without a store; the two
hosts that draw a colour endpoint hand `ColourEditor` in (`colourEditor`), because its leaves and
Palettes band reach the store.

**The marquee answers the panel's first two questions** (D4). Targets are the marquee's heads —
`spreadTargetsFor` in `rowModel.ts`, which `paletteTargetsFor` now delegates to: a
group row expanded to its **visible** members, an element row as `{type: 'fixture', key:
element.key}` (the element contract the desk already takes), a fixture row as itself — the palette
rule's answer too since venue-visit plan S1, so `paletteTargetsFor` is this walk. It is `expandSelectionToTargets`'s walk read as cue targets (`spreadTargetsOf`), so the
container's per-column targets and the request cannot name different heads. `families` is the pair
the press sends (`usePressFamilies`) — on the programmer, the one surface that bridges; the two
plain lists send the marquee's own families, as their pill shows (`desk` on `SpreadPopover`). The
family segment is drawn **answered and checked, never
hidden** — a row that appears only in some hosts is how two hosts drift — with the marquee's
families live and the rest the heads can take disabled with the reason, and both live when the
marquee spans two (Intensity + Colour), which is the chooser Fan drew for the same case. The
**Attribute** row appears wherever the family holds more than one attribute the heads can take
(`spreadAttributesOffered`, from the column → `PaletteAttribute` map `spreadAttributeForColumn`):
a Colour marquee over RGBW heads offers Colour · White, a Dimmer marquee over heads with a strobe
Level · Strobe. **Over: Heads is the default on both sides** (D5) — the old fan popover expanded a bar into its
elements always; Fan treats a fixture row as one head unless the switch says Elements, offered with
the count where a selected fixture has elements (`spreadCellCount`, the busk `selectedCells` reading)
and **pressable without it**: the operator's Over survives a marquee moving onto heads with no
elements, which spread as Heads (`effectiveSpreadOver`, one rule for both kinds: the desk request and
the raw arm both say `HEADS` where the plan counts no elements), so moving from a pixel bar to a par
and back keeps Elements. It survives an **empty**
selection too, which unmounts the popover: Over is not the form's but a
per-tab store, `lib/spreadOver.ts`, one fact for every host of the panel that `commit` writes through and a mounting panel reads. A panel mounted *beside* the
one that moved it follows the store into its form and **never pushes**, since its own Live may be
on over a selection nobody is touching. The popover host applies the **chosen** plan only where it
has two points (one point is a set), where the docked tab keeps the busk rule and applies one head
at *from*; a palette endpoint is drawn and nudged from the palette's swatch colour, on both
hosts. **Live** comes to the programmer too (D7), off by default,
through the same `useLivePush` with an equality over the whole request; Apply always sends and
reads *Send again* while Live is on; the release is read from the window. The popover is
**336px** (`contentClassName`; measured in the app on 2026-09-22 at 1100×700 as 336, the board's
number — read after the open animation settles, since a hidden pane freezes it at 0.95 and reads
319), its colour arm 611px tall with the endpoint picker at 218×176 (`ColourEditor` compact, the
busk tab's shipped measurement), and **its body scrolls under the viewport's room with the footer
outside the scroller**, as the docked host keeps it: a desk window between the short-viewport fold
and ~700px would otherwise put Live · Apply below the fold. The bound takes off the surface's
padding and its border, or the box hangs 2px below the viewport (measured).

**The focused-Look-layer arm is one flag on the route** (D6): `SpreadRequest.write`, default true.
Local sends **no `write` key at all** — the REST Json refuses an unknown key, so a desk mid-upgrade
would 400 every Local fan that carried `write: true`; `spreadRequestOf` in `SpreadPanel` is the
one builder and adds the key only when false. A focused Look layer sends `write: false`: the desk
resolves exactly as it does for Local and answers without writing, and `SpreadPopover` lands each
`written[].value` — the head's **literal** in the Look row grammar (`"0".."255"`, `"#rrggbb;w128"`,
`"pan,tilt"`), which the route answers since session 3 in place of the interpolated intent — in the
layer's draft through `LookRowStore.setValue`, which coalesces and PUTs as every layer-scope edit
does (400 ms, 2 s ceiling; flush cadence is stage cadence). A desk that still answers the intent is
told apart by **shape** (`isIntentString` in `SpreadPopover.tsx`: a `pct:` / `pos:` / `deg:` / `dmx:` /
`pal:` prefix or a `;policy=` tag — `parseProgrammerValue` alone would take a colour intent as a
colour, since it splits on `;` and tests only the head) and refused with a toast naming the desk
rather than landing an intent in a Look row; in practice such a desk 400s the request first, its
Json refusing the unknown key. Two things the desk does for this arm: a **colour-wheel** head's
resolution is a wheel *slot*, answered as the slot's level under the head's own `colourWheel`,
which a Look row holds and the cook reads back as that slot; and the curve is spread over the heads
that can take the attribute, found in a first pass, so a par swept up by a geometric Colour marquee
is skipped and consumes no position on it — two RGB heads among eight rows land at *from* and
*to*, not at 0 and ⅐. Output and a focused palette layer
refuse as they always did, with the same words, disabled rather than hidden. The response is read
for `skippedFamilies` (toasted in `skippedRowsMessage`'s vocabulary, keyed) and, in layer scope,
for `written[]`; nothing draws a preview from it — the grid is the preview, as the rig is on the
busk view. `useSheetKeyboard` is the kit's window listener and it is **capture-phase**:
`useTransportKeys` toggles the lock on `L` from a bubble listener whether or not the transport is
enabled, and a name typed into a cue cell begins with a character — so the sheet claims the key
first and the transport now stands aside from a key whose default is already prevented.

***About each position* is Fan's second mode on Position** (venue-visit plan S12, `../docs/plans/completed/venue-visit-design/Fan.dc.html`,
decided with Chris 2026-10-17). A position plan draws *Between two values · About each position* above
the endpoints, and in *About each position* *Pan · Tilt · Both* on the row below; *Between two values* is
From → To as before. *About each position* is an **amount** about each head's own position. One amount row
per chosen axis replaces From · To: a centred drag strip (`role="slider"`, −128…+127 centre steps,
0 in the middle; a press anywhere drags from the value as it stands, ⇧ a quarter as far, a double
click is 0, the arrows step it, ⇧ by 8) and a typed field. The amount is the outer heads' offset;
Curve · Order · Runs · Over keep their meanings against the midline the curve pictures draw. The
mode and axes are a per-tab store, `lib/spreadFan.ts`, for `spreadOver.ts`'s reason; the amounts are
the form's. **The desk does the arithmetic**: the request is `mode: 'FAN'` and `fan: {pan?, tilt?}`
with no endpoints (an axis not fanned is absent), and the desk reads where each head starts. **The
panel holds the gesture's anchor**: the first send's answer carries `base`, each head's starting
`pan,tilt`, and every later send carries it back, so a Live drag never fans a fan; until that answer
is in, later sends wait for it rather than read heads it is moving. Each gesture is its own object
(`FanGesture`): dropping the anchor starts a new one, and the old one still finishes — its first
answer lands its anchor and sends the send that waited, so the amount a drag was let go on lands
after the popover closes, and one that waited on a failed first send is tried in its place. An answer
with no `base` (the mask kept Position out) is no anchor. A fan of 0 is not sent before one is set,
so turning Fan on under Live writes nothing. The anchor and the amounts go when the selection or the
plan's `fanScope` (a focused layer's or a cue's id) changes, the popover closes, the mode or attribute
changes, or *Re-read* (on the read-out) is pressed. A `write: false` host starts from the row being
edited: `useMarqueeSpreadPlans` gives the plan `fanBases` — the layer's draft-else-committed position
(`lookPositionOf`) or the cue's `composedValue` per head — sent with the first request, and the
read-out says *From the layer’s rows · 2 heads from the stage* for the heads the row lacked, and
names any the desk held at the end of travel (`clamped`); the desk reads those heads from the stage,
never from Local, which is another context's. The position cell's ***Fan…*** opens row C's
popover in *About each position* at 0 (`FanSpreadSeed`); see `PaletteBands` below.

**A fan can be across the stage** (*Fan in: Pan/Tilt · Stage* under the mode row). The switch is the
position pad's own mode (`lib/positionPadMode.ts`), one fact for the pads and the fan, and a change is
a new gesture. In Stage the amounts are metres — *Across* (SR ↔ SL, `fan.x`) and *Depth* (`fan.y`) in
place of Pan and Tilt, the strip ±10 m, the arrows 0.1 m and ⇧ 1 m — and the desk moves each head's
landing point by its share and aims it (lighting7 `docs/lighting-composition-model.md` §"A fan about
each head's own position"). The answer's `base` then carries fine bytes and its `near`
each head's degrees, and the gesture sends both back, `near` each head's latest (an older answer
landing late is not newer), as the jog does. The read-out counts the heads the fan took *out of
reach*. Over is Heads: *Elements* is disabled and shown unpressed, an element having no placement of its own.

**The row keys are every sheet's, and they are the one half that listens in the bubble phase.**
Where a sheet selects rows — every kit sheet but the DMX sheet, whose surface passes `useSheet`
`selectsRows: false`, the one place that is said (`tableProps` carries it to `SheetTable`, so the
pointer and the keyboard cannot disagree) — ⌘A selects every row and ↑ / ↓ step the row selection,
Shift extending, as the programmer's list does: `arrowStepTarget` in `listSelectionModel.ts` is the
one rule both step by (a plain step from the anchor, Shift from the range's moving edge, ↓ onto the
first row and ↑ onto the last with nothing selected), pinned in `listSelectionModel.test.ts`. ⌘A
drops a cell marquee through the row door as a row click does; **↑ / ↓ do not any more** — over a
cell selection the arrows are the cells' (below). They are a second listener
in `useSheetKeyboard` (`rowKeys`), **bubble-phase and standing aside from a `defaultPrevented`
key**, because some controls answer an arrow themselves — a Select's list, a menu, a slider — and
claim it on their own handler, which only a bubble listener runs after; from capture, ↓ in an open
menu would move the menu's highlight and the sheet's row. The partition chips and the bar's verbs
claim nothing — they are plain buttons — so the row keys also stand aside from **any focused
control outside the rows** (`isForeignControl` in `sheet/cellEntry.ts`), exempting the rename
button and cell trigger a click inside a row leaves focused, which is where focus sits while ↓
moves the selection on — and **one outside the rows that a click left focused**, which a capture
companion of the same listener hears instead (`pointerLeftOnForeignControl`, §The programmer's
keyboard). Only plain and Shift arrows (⌘, Ctrl and ⌥ arrows are the browser's and
the OS's), and — on the kit — not under an open editor. The programmer's list takes the
foreign-control and modifier rules too, and the dialog guard every
sheet listener and the programmer's ask is `keyTargetIsGuarded` beside it — which matches
`alertdialog` as well as `dialog`, since a Radix `AlertDialog` (the batch delete's confirm, the cue
sheet's unlock question, *Discard changes?*) is the former, and reading `dialog` alone let a key
reach the rows behind one. →/← over a *row* selection stay the programmer's alone: they open and
close a group or a multi-head fixture, and no kit sheet has a tree.

**The arrows move a cell selection, on every sheet and the programmer — a spreadsheet's arrows.**
`cellArrowStep` in `sheet/cellSelectionModel.ts` is the one rule, pinned in
`cellSelectionModel.test.ts`; the kit's sheets reach it through `useSheetKeyboard`'s `cellKeys` —
the same bubble listener as `rowKeys`, under every one of its guards — and the programmer's
container calls it from its own listener, as it calls `arrowStepTarget`. **Which of the two hears
an arrow**: the cells' whenever cells are selected, or the sheet has no row axis; otherwise ↑ / ↓
are the rows' and ← / → nobody's (on the programmer, the tree's). So a row sheet's first arrow still
selects a row, and the arrows walk cells once a click or a marquee has put the sheet in its cell
shape. The rule's terms are `arrowStepTarget`'s: **a plain arrow moves from the anchor** one
cell and collapses the selection onto it; **Shift moves the head** and keeps the anchor, so Shift
back shrinks towards the anchor and then grows past it; with no cursor on the grid ↓ / → land on
the first cell and ↑ / ← on the last; at an edge the key is still claimed, so the page does not
scroll instead. The grid is the selectable rows × the columns that hold cells — the kit derives it
from `rows` and `columns`, skipping dividers and read-out columns (no `data-column-header`, so no
marquee reaches them either), and the programmer from its selectable rows and visible columns.
**A blank cell is stepped past** (`CellGrid.takes`): a column the row has nothing to set in — a
kit cell whose `value` is undefined (`takesValue`), a programmer column the row resolves nothing
for (`buildRowCells`) — is drawn with no `data-cell` and no ring, so landing there would select
something invisible that nothing could scroll to. ↓ in Colour skips the dimmer-only pars and lands
on the next head that has colour; with none before the edge the arrow stays. A Shift rectangle
still covers the blanks between its corners, as the marquee's does. **Shift grows a rectangle on
every sheet, and never wraps** — Shift+↓ ×4 then Shift+→ ×3 from one cell is five rows by four
columns. **A surface says only its `cellFlow`** (a `useSheet` option), and it governs a *plain*
← / → alone: **`grid`**, the default, stops at a row's ends; **`linear`** wraps from a row's end
onto the next — the DMX sheet, whose reading order is address order in every arm, so the kit needs
no notion of an address. ↑ / ↓ are one row in both, stopping at the first and last. Shift on a
`linear` sheet extended a run of addresses until the desk tried it (2026-09-24): a block starting
mid-row took the whole of every row in between, when the gesture on screen was a rectangle.

**A cell click takes the row click's modifiers** (programmer-fixes plan D1–D5; `CellSelection.click`,
by `listSelectionIntentFor`): plain selects the cell alone, **⌘ / Ctrl toggles that one cell**, **⇧
selects the rectangle** from the cursor's anchor to it, replacing the selection, and **⌘⇧ adds that
rectangle** — so a selection can have gaps. The rectangle is `cellRectangle`, the one a ⇧-arrow
walks; the toggle is `toggleCell`, never the marquee's `'toggle'`, which only adds (a ⌘-drag never
subtracts). With no anchor on the grid (nothing selected, or its row collapsed or filtered away) ⇧
and ⌘⇧ act as a plain and a ⌘ click. A second click of a double click on the same cell
(`event.detail > 1`) only makes sure it is selected, so a ⌘ double click opens the editor on a
selected cell; a click with no pointer behind it (`detail` 0: Enter or Space on a focused cell) is
always plain (`cellClickIntent`). The event comes from the cell's trigger through `onBeginEdit(event)`
and the tables' `onBeginCellEdit(row, col, event)`; each host — `useSheet`, `FixturesListContainer`,
`CueValuesGrid` — clears a row selection first and calls `click`. A blank cell passes its click to
the host's background clear, which leaves the selection alone when ⌘, Ctrl or ⇧ is held or the
latch is on (`blankClickKeepsSelection`); a plain click there still clears. Nothing downstream
assumes a rectangle: the writes, ⌫ and Spread are per column (`cellsByColumn`), so they reach exactly
the selected cells, and the desk selection stays heads × the cells' families.

**On a touch screen the selection bar draws *Add*** (D7; `AddLatchToggle`, `useAddLatch`), on a coarse
pointer only: a toggle (`aria-pressed`, ＋) under which a tap on a cell is a ⌘-click, a tap on a row's
name a ⌘-click on the row, and a held drag accumulates (`latchedIntent`, the marquee's `addLatch`).
It is the host's state, never stored, and turns off on a clear — Escape, Deselect, a tap on the empty
space under the rows — and when the pointer stops being coarse, but not when a tap takes out the last
cell, so a double-tap on the one selected cell opens its editor with Add still on. Every kit surface
passes `sheet.addLatch` to its bar, whose word folds to the glyph below `@[600px]`; the programmer's
container hands its own to row C through `renderToolbar`'s `addLatch`, and on the phone arm it is the
heads chip's menu item (*Add*) rather than a button, since that row has no room. A modified click
takes the focus off the cell trigger it landed on (`releaseCellTriggerFocus`), since a ⌘-click can
leave that cell unselected and a focused trigger would take Enter, a character and ⌫ for itself.

**The anchor rides in the cell selection** (`useCellSelection`'s `cursor`, beside the keys in one
state object so they cannot describe two different selections): the keys' door, `place`, sets it,
and so does a click — a plain or ⌘ click puts anchor and head on its cell, a ⇧ click keeps the anchor
and moves the head, so the arrows step from the last cell clicked and a ⇧-arrow after ⌘-clicks
replaces them with its rectangle. A ⌘-click that empties the selection drops the cursor, and a
marquee and a clear drop it too. With none stored, a selection's cursor is derived —
its first cell in reading order as the anchor and its last as the head (`cursorOfSelection`) — so a
marquee dragged either way is anchored at its top-left. A step asks the table to **reveal** the
head (`revealCell` on `SheetTable` and `FixturesTable`, through `sheet/revealCell.ts`): the least
scroll that shows the cell whole clear of the sticky header row and first column, and nothing at
all while it is already on screen, where a row key's `scrollToRowId` centres its row. A cell whose
row is virtualised away is scrolled to by the virtualiser (`align: 'auto'`) and revealed once the
row renders, asked again each frame for up to six (`REVEAL_FRAMES`) — the virtualiser renders from
its own scroll listener, and one frame is not a promise. The sticky column is read as the header's
first child, never `[data-grid-name-header]`, which only a sheet that selects rows hangs. **⌘A on a sheet with no row axis selects every cell** — the DMX sheet's whole universe; on
every other sheet ⌘A is still the rows'. Enter, a typed character and ⌫ then act on whatever the
arrows left selected, so *select, arrow, type, Enter* is the keyboard flow.

Three surface rules, each pinned by its test:

- **Patch list** (`PatchSheet.test.tsx`, `lib/patchAddress.test.ts`): **Set over N addresses lands
  them consecutively by footprint from the typed one**, in visible-row order, each head on its own
  universe (the PUT cannot move a head across universes); Fan on Address is From + Step. An
  overlap is a destructive ring on the Address cell with the other head on its title and a legend
  line under the sheet; the address editor **names the collision before Apply and refuses it**.
  That refusal is the only overlap check there is — **the patch PUT has none today** (only the
  POST checks), and there is no bulk atomic route, so a batch is N PUTs that can half-apply on a
  network failure; both are lighting7 work and Chris's call. Clear is refused on Address, Fixture,
  Key and Stage; offered on Mount, Lantern, Angle and Gel. It is a routed page again (§List shell); row B is
  universe toggle · filter · spacer · Groups · Columns · + Patch, and the universe chips carry a
  fill bar on a 40px chrome row of their own. **Colour is the first value column** (§Fixture
  colours): the one column a venue's head is not locked out of in a show (`lockVenueRows` skips it),
  written in one atomic bulk request, with *Colour from group* on row C; `colour:red` filters the
  list (`lib/patchFilter.ts`).
  **A double click on a fixture's name opens a rename popover — the kit's `TextCell`, mounted with
  `firstColumnCellProps`** (`sheetModel.ts`), so the first column edits in the same popover, in the
  same three forms, as every value cell; the single click underneath still selects the row and a
  press still starts the row marquee. It was an `InlineEditField`, the one editor on a sheet that was
  not a popover. The pencil beside it — the kit's `firstColumn.onOpen` since the library sheets —
  still opens the full editor, and so does ⏎ with that one row selected (§Library sheets).
  **Set over N *keys* fans one typed key over them**, `lib/fixtureKey.ts`: one head takes it as it
  is, several count up from it, continuing the number, the separator and the zero padding the typed
  key already carries — the vocabulary `AddFixtureSheet` mints keys in, read back off what was typed
  rather than imposed. Two refusals are named before Apply: a key another head holds, and a set that
  cannot be *ordered*. The ordering exists because this PUT, unlike the address one, checks
  uniqueness on **every call** — so `par-1 … par-4` re-keyed from `par-2` walks *down*, and the
  writes go out one at a time and stop on the first failure, saying how far they got. A true swap
  (the visible order is not the key order) is refused rather than broken open by parking a head on a
  synthetic key: a PUT failing after a park leaves a fixture called `par-2-tmp1` on a live rig with
  nothing to put it back. **One batch at a time**, too: `write` has to answer synchronously, so the
  loop runs detached and the editor closes over it — a second batch planned in that window would be
  planned against an `allPatches` the first has not landed in, and two plans each assuming they are
  the only writer is how one walks onto a key the other is mid-way through vacating. **`key` is also outside `METADATA_ONLY_PUT_KEYS`**, so each of those PUTs
  rebuilds the fixture registry and broadcasts — the same cost the address batch has always had, and
  the same answer: a bulk route is lighting7 work and Chris's call.
  **The Head column is the operator's head number** — a ChamSys head number carried across a
  migration (lighting7 `docs/fixtures-engineering.md` §"Head numbers"), unique in the project,
  optional. **Set over N counts up from the typed number** in visible-row order, and — unlike Key —
  goes out as **one atomic request** on the bulk placement route (`useSetHeadNumbersMutation`), which
  the desk judges against the batch's *final* state, so a renumber that swaps two heads needs no write
  ordering and cannot half-apply. A number held by a head outside the batch is named before Apply
  (`lib/headNumber.ts`); a number two heads share anyway (a sync merge imports as it stands) is ringed
  like an overlapping address, with its own legend line. Clear unnumbers; there is no Fan. The add
  and edit forms carry a *Head number* field, and the add sheet advances it by one per fixture patched.
  It is **not** on `GET /fixtures` yet, so nothing outside the patch views can show it.
  **An unnumbered Head opens on the next free number** (`nextHeadNumber`: one past the highest, so
  a run from it always lands clear), selected and noted as a suggestion — `TextCell`'s `emptySeed`
  — where it used to open empty under a `1` placeholder that read as the value about to be applied.
  **The Gel cell is the patch editor's gel picker** (`GelCell` over `GelPicker`, which
  `GelPickerField` also mounts): search, brand chips, swatches, ↑/↓ and Enter — not a typed code.
  **Every `OptionCell` with two or more options draws its filter**, because the filter is the
  cell's keyboard: Stage and Role could not be set by typing while the threshold was three.
  **A batch landing is drawn one head to a line** — `EditorReadout`'s `lines` arm, which was
  `cells/LandingLines.tsx` until the editor kit — shared by the address editor and the Key column,
  rather than joined with `·` into a paragraph read at the worst moment.
- **DMX sheet** (`DmxSheet.test.tsx`): `/projects/:id/channels/:universe/table`, sticky key
  `channels.view`, a grid of 56px cells — the address with the raw 0–255 value in the top-right
  corner, then the fixture's name, then the channel's use, on **every** patched cell alike (no
  first-cell emphasis), a 2px run edge where each fixture's footprint begins, and ownership rings
  read through the desk's own keys for the address (below). No row axis: the row head
  hangs no `data-grid-name-header`, so every press is a cell press, the arrows always walk the
  cells — `cellFlow: 'linear'`, so a plain ← / → wraps `016 → 017`, ↑ / ↓ are ± the row width in
  whichever arm is showing, the ends are `001` and `512`, and Shift grows a rectangle (↓ then →
  from `005` at sixteen wide is `005`, `006`, `021`, `022`) — and ⌘A selects all 512, which ⌫ would then zero. Fan is one `raw` plan
  over every selected cell in address order (an address has no intent for the desk to resolve). Writes are `channels.update` per address; Clear is 0; Park /
  Unpark act on the selection; the desk being offline is the read-only scope; Unpark All keeps its
  confirm and there is no Edit/Done toggle. Raw 0–255 only, no level bar — left for later.

  **A cell's face paints no background, and that is what keeps its ring honest.** The ownership
  ring is an inset `box-shadow` on a wrapper around the face, and an element's own shadow paints
  *beneath* its children — so a background on the face covers the ring and its fill. The alternating
  footprint tint that sat there did exactly that: an owned address on a tinted run read as a fainter
  owner than the same state beside it. The footprint is the run edge now, drawn in the 2px padding
  outside both the ring and the selection overlay, and as a sibling of the ownership wrapper so the
  baseline dim does not fade it. `DmxSheet.test.tsx` pins that nothing under the ring carries a
  `bg-` class. **The run edge is where a fixture's colour goes** (§Fixture colours): `colours`, the
  route's `useFixtureColourIndex`, paints it inline; an uncoloured fixture keeps the neutral edge.

  **A long name or use is cut at its beginning, and fades rather than taking an ellipsis** — the
  end is what tells two apart (a fixture's number, a head's colour). The line is laid out right-to-left so it
  overflows off its left edge, aligned left, around an LTR `<bdi>`; `text-overflow` cuts at a
  character, and one landing on a space drew `… Lightbar`, so the edge is a mask instead, drawn only
  while `data-overflow` says the line overflows. That flag is written to the DOM by one shared
  `ResizeObserver` watching each line's box *and* its text (`inline-block`, so it has a box), never
  through state — a state per line would re-render ~400 cells on a resize. The cell's hover carries
  all of it (`025 · LED Lightbar 12 Pixel · Strobe — Programmer`). Fixture names, not keys: on a
  desk that minted its keys from names they are no shorter.

  **A row is 16 addresses wide on a desk, 8 on a tablet and 4 on a phone** — `DMX_ROW_WIDTHS`, the
  widest arm whose `48 + 64 × n` the container can draw without scrolling sideways, measured by
  `hooks/useContainerBand.ts`. It halves rather than taking any n so that a fixture's footprint
  still reads across a row and a row's base stays a round address (`001`, `009`, `017`). A
  container query cannot answer it: the count is JavaScript, not a class. **Changing arms drops the
  cell selection, during the render that changes it** — 16, 8 and 4 are multiples, so every wide
  row id exists narrow too, and the same `rowId · col` pair would silently name a different
  address; done in an effect it left one painted frame where `selectedChannels` named an address
  nothing showed as selected.

  **A cell's ring asks the desk which attributes drive its address, and never works it out.**
  `channelMappingState` carries `attributes` on every entry — every `(target, attribute)` key whose
  channels include the address, from `AttributeChannelWriter.attributeKeysByChannel`, which is
  `channelsFor` inverted — and the cell aggregates the programmer's state over all of them, plus the
  programmer's channel **sideband** at that address (`ProgrammerState.channels`, one subscription
  for the sheet) — a sideband slot promotes the address over a cue or baseline, never over park or
  an `fx` verdict, because a programmer-band FX outranks the programmer and the desk's
  per-key answer has already weighed any slot it can attribute. The desk builds the frame once per
  `Fixtures.structureVersion`, from one read of the register, and shares it across sockets. The sheet used to build that map itself from the fixture descriptors and drifted
  from the desk three ways at once: a bundled white/amber/UV was filed under `rgbColour` while
  `updateChannel` lifts a write on one to its **own slider**, so a value set here never rang; an
  element's white was missing entirely (its colour descriptor names none); and a pan axis read only
  as `position` where the desk lifts it to `pan`. Two keys per address is normal, so **the address
  takes the strongest source and is never drawn dashed** — the wire carries one byte, and the
  programmer grid's "these heads disagree" means nothing for one channel. An address the desk names
  nothing for reads **baseline** (dimmed) like any idle one; it used to read *nothing* and drew
  brighter than its patched neighbours. A multi-head fixture's heads ring through their own
  element keys: the desk's covering lookup (`resolveChannelCoveringKey`) reaches elements, so a
  write on a head lifts to that head's attribute (a red to its `rgbColour`, its white to its own
  `white`) and records into a cue as an element-target row. The sheet still reads the sideband for the
  addresses the desk *cannot* lift — nothing covers them — which ring through no key. A desk that predates
  the field sends no `attributes` and the sheet reads every patched address as baseline until it
  restarts.

  Its cells set **`SheetColumn.gutter: false`**: they draw no corner glyph, and the kit's 18px marks
  gutter made a cell's own ownership ring a box 18px narrower than the selection overlay drawn over
  it — the two lines an operator reads a channel by, disagreeing on three of four edges. Without it
  the wrapper is padded 2px all round and `cellSelectionClass` insets the overlay to match.

  **The route is full height, not a `Card` in a scrolling page** (`routes/ChannelsTable.tsx`): as a
  card the page scrolled *and* the table scrolled inside a `calc(100vh - 14rem)` cap, two bars for
  one list, and a 393px-tall landscape phone got 169px of grid. Its breadcrumb header is 48px like
  `StackDetail`'s — a header, not one of the 40px chrome rows (the `ShowHeader` was 48 too until
  the busk-chrome plan's session B made it a 40px chrome row; this one and `StackDetail`'s stay).
- **Cue sheet** (`CueSheet.test.tsx`): `/projects/:id/show/stacks/:stackId/table`, sticky key
  `show.view`, the switcher on the `StackDetail` header. **The first column is the row head** — the
  kit's grip, the pip and the played mark, 64px — and a click there selects the row (locked, arms
  it). **Cue # · Name · Fade · Curve · Follow · Notes are cells** (show-view-fixes plan D7–D12). Cue
  # is a column of its own kind, `cueNumber`, right after the row head and not sticky, its value the
  entry's typed number (`''` when auto), so it selects with a click, a marquee or the arrows and no
  other column's commit reaches it. `runner/CueNumberCell.tsx` draws it: over **one** cell the number
  field, the entry `PATCH` with `placement: 'ASK'` through `useCueNumberPlacement`; over **several**
  the renumber form (`RenumberForm`, which the bar's *Renumber…* mounts too), *First* seeded with what
  was typed at the grid, else the first cue's whole number, then one `POST …/renumber` of those
  entries in stack order, whole numbers only. Clear returns each typed number to auto, one `PATCH` per
  entry; there is no Spread on it. The cell picks its arm by `batchCount`, so it is a new node when a
  marquee grows past one: jsdom, with no pointer capture, needs the cell asked for again after the
  move (`dragNumbers`). **The pip arms** a cue that is neither live nor next, locked or not
  (`CueStatePip`'s `onArm`, below). **Scenery is a read-out that opens an
  editor** (scenery-programmer plan D13): the cue's own changes (`Tabs → closed · 4 s`), the tracked
  hatch where it changes nothing but something is tracked into it, the em-dash where nothing is
  (the editor can set it), blank on a MARKER; a press opens `CueSceneryEditor` at the cell, and
  locked it asks to unlock like every refused edit (disabled with the reason where the lock is not
  the operator's), closing on a re-lock. Not a `cell` — one cue's list is not a value a marquee
  spreads. **Layers · FX are read-outs that select their row and open the pane on that tab**,
  turning it on if it is off (below); **Book opens the Prompt Book** at that cue instead, through
  `?cue=`, which is that page's arrival contract and the mirror of the one it mints for Show — it is
  the one read-out that names a place in another document. **No Hooks column**: `CueStackCueEntry`
  carries no trigger count, and adding one is a backend field.

  **The bottom pane is a per-window toggle, and the selection only chooses what it shows**
  (show-cue-editing plan D5; show-view-fixes plan D3–D6; `runner/CueEditorPane.tsx`): the
  *Editor* button in `StackDetail`'s header (`aria-pressed`, drawn on the Table and the Timeline,
  not on Cards, locked or not) keeps `sessionStorage cueSheet.paneOpen`, `cuePaneOpenStore`, on in a
  window that never chose. On, the pane is always drawn at its height, so a selection never moves
  the rows under a marquee. It shows `CueEditor` for the one selected cue row
  (markers beside it aside), else a blank state in the same box — *No cue selected*, *3 cues
  selected · … The bar above acts on all three*, or a separator's (unreachable while the kit never
  selects a divider) — chosen by `runner/cuePaneContent.ts`'s `paneContentOf`. **During a marquee
  it keeps what it had** (`useHeldPaneContent`, read from `sheet.marqueeDragging`) and follows the
  selection on release, so `CueEditor` is never mounted per row crossed. A read-out that opens it —
  Layers, FX, the Move in dark chip, the Timeline's Dark tap — turns it on first, and so does the
  `?cue=` deep link (the Prompt Book's *Edit cue*); a plain click never does. Off, it is never drawn. The pane measures its column as its own parent element,
  since it mounts with the sheet and an ancestor's ref is not attached when a child's layout effect
  runs. It sits under the
  sheet with the busk `RigHandle`'s grip — one drag, `hooks/useHeightGrip.ts`, and one snap,
  `snapRigHeight` — but its snap points are its own: its **160px floor and half the column**,
  nothing else. The height
  is this window's, `sessionStorage cueSheet.paneHeight`, null until dragged and drawn then at half
  the column; the ceiling leaves the sheet its header and three rows (`SHEET_KEEP_PX`), clamped on
  read. ↑ / ↓ on the grip step between the floor, half and the ceiling, and a double press restores
  the default. The ceiling is measured from the column (less its other children), never from the
  pane, whose content can be taller than the room. The height is written onto the pane
  imperatively per move and committed on release, so a drag re-renders the grip and not the sheet,
  and the edited row is scrolled into view by the least move as the pane opens, resizes or takes another cue — never for a blank state. **The
  grip and the editor are key regions** (`data-key-region`, which `keyTargetIsGuarded` stands aside
  for, as it does for a Select's list; a blank state is not one): Escape, Enter or ⌫ pressed in the editor is never the
  sheet's. The editor is `CueEditor` with `host="pane"` —
  the card's own (§"Cues, Stacks & Triggers") — keyed by the entry, so its header names the stack
  beside the number (*Act 1 · Q1*); locked it reads. A read-out's tab request is spent once the pane
  leaves its row, so a later click opens on the window's own tab. **A follow of 0 fires at once**:
  the Follow column and the editor's header read it through `parseFollowDelay`, blank or *off* being
  none, where `parseFadeDuration` reads 0 as a snap.

  **The sheet follows the show** (venue-rig-fixes plan D10, D11; `runner/useFollowShow.ts`, which
  the Timeline and the Cards list mount too): entering a running stack centres its live row, and each
  later change of the live or armed entry — a GO, GO back, GO to, a follow, arming — centres that row
  only when it is outside the scroller, through `sheet.scrollTo` (`SheetTable` marks its scroller
  `data-sheet-scroller`). A `?cue=` link wins: it scrolls instead, and following starts from it. A
  hand scroll holds following off for 10 s, told by its input and never by a scroll event — a wheel or
  a touch move over the scroller, a press on the scroller itself, or a scrolling key in the list that
  nothing else took — so the sheet's own scrolls (the pane row kept in view, the link, the keyboard
  cursor) never pause it, and a click, which selects a row already in view, never scrolls. A GO
  during the pause is not caught up when it ends; the next GO or arm is followed.
  **Duplicate stays put** (D12): `CueActions` hands the copies' entries to the host through
  `onDuplicated`, and once the stack's refetch lists them the sheet selects those it draws and
  scrolls to the first (`runner/useSheetFollow.ts`), once — `runner/useLandCopies.ts` forgets them on
  leaving the stack or after 5 s; the pane keeps its toggle and shows what the selection says. The
  toast says *Duplicated n cues* (one loose copy *Duplicated as “X”, in no stack*), with no Undo: each
  copy is its own history entry. The Timeline does the same, and Cards, which has no selection, scrolls to the first.

  **A cell that cannot be selected is blank**, not an em-dash: the em-dash is the mark an *empty but
  settable* cell wears (Follow, Notes), and wearing it on a read-out and on a snap cue's Curve made
  half the sheet's dashes look editable.

  **The lock is the sheet's read-only scope** the way
  Output is the programmer's — locked, every value cell is inert in all four places, the marquee
  still works, and a click on the row head arms
  the cue as next; unlocked, cells edit under the amber wash. **A refused edit asks to unlock**
  rather than doing nothing: Set · Clear · Fan stay live and open a confirm, and so does ⏎ — through
  `useSheetKeyboard`'s `onRefused`, which reports the refused gesture **and the key** and lets the
  *surface* say whether to claim it. The kit takes no view on which keys are safe: that depends on
  what else the surface has bound, and here it is Enter alone, because while a show is locked
  `useTransportKeys` owns Backspace (BACK) and a typed `l` (the lock toggle, the keyboard's own way
  back) and both stand aside on `defaultPrevented`. That reasoning lives in `CueSheet`, beside the
  hook it is about, and `CueSheet.test.tsx` pins both keys as untouched. The offer is withheld where
  the lock is not the operator's to lift (`canEdit` false), where the disabled verbs and their
  reason are the honest answer. Writes are one PATCH per cue carrying
  the field, the cards' own auto-saving contract. The transport is otherwise untouched:
  `useTransportKeys` is enabled exactly while locked, as it always was, and `canOperate` is never
  handed `locked`.
  The sheet consumes `?cue=` by selecting the addressed cue's row and scrolling to it — the
  external contract holds on both views.

  **The bar carries the cue action set** after Set · Clear · Fan and before Deselect
  (show-cue-editing plan §4.3, §"The cue action set" below), over the selected cues in row order, a
  marker left out. So it folds as a loaded bar does — `foldForStrip`, the cell verbs' words and the
  hints going below 1100 — Deselect's word folds below 760, and the set's own rungs were measured
  with a cell selection, the widest the bar gets.

  **Unlocked, a grip in the row head reorders cues and separators** — `SheetTable`'s `rowDrag`,
  on the cards view's own `reorderCues`, so one gesture and one mutation serve both. The
  `DndContext` is mounted only where a surface asks for it and the rows are turned off through
  dnd-kit's `disabled` rather than by unmounting it (`StackDetail` learned that one); the grip
  stops its own `pointerdown` propagating, or starting a drag would also start a marquee.

  **A drag from a selected row moves the selection** (show-view-fixes plan D22–D24): `rowDrag.group`
  is asked once as the drag starts for the rows that travel with it, and the cue sheet and the
  Timeline answer with `dragGroupOf(selectedRows, id)` — the row selection on the Table, the
  selected cells' rows on the Timeline — when the dragged row is one of them, else the row alone.
  The new order is `sheetModel.ts`'s `moveRows(rowIds, group, activeId, overId)`: the group together
  in row order, before the row dropped on moving up and after it moving down, gaps closed, a drop on
  the group nothing; still one `reorder`, and the selection stays on the moved rows, whose entry ids a
  reorder keeps. While a group moves its other rows dim in place, the dragged row's grip carries
  **+n** and a line marks the landing; the gap stays one row high, dnd-kit's list strategy moving rows
  for the active one only. A single-row drag draws none of it and sets no state; a host that passes no
  `group` (the Stack table, the applied tables) only ever drags one row.

  **A sortable row is positioned with `top`, never the virtualiser's `translateY`.** dnd-kit
  measures droppables with transforms discounted, so rows positioned only by a transform all measure
  at the container's origin: every centre-distance ties, `closestCenter`'s stable sort returns the
  rows in DOM order on every frame, and the drop lands on the row the drag began on. It does not
  fail cleanly — dragging **up** still works, because for an upward drag DOM order and distance order
  agree — which is how it survived a first browser check. `CueSheet.test.tsx` pins the `top`; the
  sheets with no row drag keep `translateY`, having nothing else competing for `transform`.

**Cards · List and Cards · Table are one switcher and one storage vocabulary.** Fixtures and
Groups keep the word *List*; Channels and Show say *Table* (their second view is a table and their
first genuinely is cards). The stored value is `'list'` for all four keys, so `getStoredCardsListView`
answers every pair and a rename can never reset a desk's remembered view — Show alone may also store
`'timeline'`, its stack's third view (§The Timeline). `stickyRedirectsToList` is the redirect
decision of the Fixtures, Groups and Channels cards routes, and Show's is `stickyStackSheetView`,
which names the Table or the Timeline (`ViewSwitcher.test.ts`). **A first
visit lands Fixtures and Groups on their lists**, Channels and Show on their cards (venue-visit plan
S2, `CARDS_LIST_DEFAULTS`): only an absent or unreadable value reads the default, so a stored
`'cards'` or `'list'` means what it always did, and a read never writes the default back. The Cards
segment still writes `'cards'` before it navigates and carries `CARDS_LINK_STATE`, so it reaches the
cards even where storage refuses the write (`routes/cardsListDefault.test.tsx` drives the round
trip from a first visit).

### List shell

**Every list view is one column, stated once.** Fixtures › List, Groups › List, the programmer (its
Values grid and its Stack, FX and Scenery tables alike), Show › Table, Channels › Table and the patch
list share one anatomy — a 48px header row (the
programmer's is the `ShowHeader`, a 40px chrome row since the busk-chrome plan's session B), any
number of 40px chrome rows, the 40px `SelectionBar`, the sheet, a 22px footer — and the whole of it
is two files: `components/sheet/sheetFrame.ts` holds the class strings (`PAGE_HEADER_CLASS`,
`CHROME_ROW_CLASS`, `SHEET_SCROLLER_CLASS`, the sheet header row and cell, the sticky cell, the row,
the divider, `SHEET_FOOTER_CLASS`), and `components/sheet/SheetPage.tsx` the thin components over
them (`SheetPage`, `.Header`, `.Row`, `.Footer`, `.Empty`) plus the one `LegendSwatch`. `SheetTable`
and `FixturesTable` draw their frame from the constants; every surface mounts the components and
never writes the classes. A surface that wants to differ says so at the import, in one file. The
design record is `../docs/plans/list-shell-design/` (`Kit.dc.html`'s "Where the code goes"
is the file-by-file map; the five open calls are made on `Spec` under "Called — 2026-09-15").

The rules the two files encode, each of which was a measured inconsistency before them: a list is a
full-height column filling `<main>` — **no `Card`, no page scroll, the sheet is the only scroller**;
a 12px gutter on every row; header 48, every other chrome row 40 with 32px controls, footer 22, sheet
header 30 and rows 36 (56 on the DMX sheet); **three grounds** — the page (`<main>`'s `bg-muted/40`,
which every chrome row sits on with no ground of its own), the sheet (`bg-background` on the header
row, the sticky column and the body alike, so a name column can never read darker than its cells)
and a divider row (`bg-muted/30`); and **one line between neighbours** — a chrome row owns its
`border-b`, the sheet owns no top line, the footer owns its `border-t`. **Two named exceptions, both
by decision:** the programmer's row A keeps its `bg-card/50` wash, and `ShowHeader`'s border stays
transparent until the unlocked wash colours it.

Three things the shell changed that read as bugs if you do not know they are decisions. **The two
plain lists have row C and a footer now** — reserved (`Nothing selected`) when nothing is, the
count in the footer — so `SelectionToolbar` draws no count of its own, and `FixturesListContainer`
has one arm: no `fill`, no `space-y-3` wrapper, and its default toolbar is the shell's row (filter ·
spacer · Lit · Expand all · Columns) plus
the programmer's own `SelectionBar` with no `projectId`, which is what draws it as the kit's bar
rather than the programmer's three clusters — one component, so the two bars cannot count a marquee
two ways. The bar's own
`@container` sits above it on those two routes as everywhere. **The strip folds — the verbs' words
at 1100 and Locate · Highlight at 800 — apply only on a bar that carries a strip**: the kit's
`SelectionBar` marks itself `group/bar` + `data-strip` (from `foldForStrip`, defaulting to "there
is a strip"), and `WORD_CLASS` / `STRIP_MID_FOLDED_CLASS` in `toolbarFolds.ts` are gated on it.
Those folds gave the programmer's retired palette strip room (its row C has its own ladder now,
`rowCFolds.ts`, and sets no `data-strip`); on the plain lists, the DMX sheet and the patch
list nothing needs it, and a 1100 fold there emptied every word at once on an ordinary 1160px
window. The cue sheet folds only while a cue row is selected, when the cue action set fills the
bar (§"The cue action set").
`SheetPage.Header` and `.Footer` put their `@container` on an unpadded wrapper, never on the `px-3`
row: a size query measures the content box, and a container on the row would fire every threshold
24px early. **Loading and not-found render inside the same `SheetPage`** — an empty header row over
`SheetPage.Empty` — so a list keeps its shape from loading to loaded. And **the patch list is a
route again**, `/projects/:id/patches`, with the chips as a 40px chrome row of 28px chips above row
B: it left Project Settings because a tab body under a settings heading was the one list that could
not have the header row and the gutter. `/settings/patches` redirects there, `?action=new` intact.

### Library sheets

**The five libraries — Scripts, FX Library, Looks, Palettes, Speed Masters — are sheets on the
sheet kit, each on the route it always had** (`../docs/plans/completed/library-sheets-plan.md`;
the boards are `../docs/plans/completed/library-sheets-design/`, where the plan won on behaviour and the
boards on layout and copy). Four sessions built it: the kit's library half with **Speed Masters** on
it (1), **Looks and Palettes** (2), a generic value palette's **Value** edited in the cell (3), and
**Scripts and the FX Library** (4). The rules, which every one of the five keeps (the Cues table,
a sixth library since the cue model plan's session 5, keeps them too but for its bar, which is the
cue action set — §"The Cues table"):

- **The sheet replaces the list, on the same route** (D1). No Cards · Table switcher and no sticky
  view key: a second view has to earn one. The record editors stay — they are what a row *opens*.
- **The whole list shell** (D2): the 48px header with the breadcrumbs, the **library row**, the
  `SelectionBar`, the sheet, the footer. The library row is row B for a library — filter ·
  partition chips · spacer · the create verb, which leaves the page header for it.
- **Chips filter, dividers group** (D3). Where a library partitions exactly (script type, FX
  family, palette family) the partition is a chip set with counts, and under *All* the sheet is
  grouped by divider rows in the partition's declared order. Looks and Speed Masters partition
  nothing and draw no chips. The partition is a **view, never a route**: a `?param=` plus a
  remembered value, both the route's.
- **The name column is the sheet's `firstColumn`, never a `SheetColumn`** (D4), so it is never in
  the marquee. A double click renames it in `TextCell`'s popover — one row at a time by
  construction — and the pencil opens the record, as does **⏎ with exactly one row selected and no
  cells**. That ⏎ is the kit's, so the patch list has it too.
- **What a cell edits** (D5): name and notes everywhere, plus the few values a library holds — a
  palette's Value, Fade and FX Master; a master's live BPM, Start, Follows, Ratio and Family.
  What a Look holds stays recorded and changed by Include. Every other column is a read-out.
- **Row verbs on the bar** (D11): after Set · Clear · Fan come Include, Pick up, Duplicate,
  Copy to…, Fork, Compile, Run, Delete, then Deselect. A verb over one record is disabled over
  several with the reason. The per-row `…` menus go.
- **Read-only by row and by scope** (D12). A row with nothing to set in a column has
  `value: undefined` there; the marquee is geometric and still covers the cell, so **the kit drops
  it before `write`** and names it on the editor's read-out. Such a cell is **blank**, as the
  programmer draws a column a row resolves nothing for — no `display`, so a click there clears the
  selection — unless what it reads out is worth reading (a follower's derived BPM). The boards drew a
  `·` there and were corrected to match. **Another project's library** is the
  sheet's read-only scope, the cue lock's shape: selection works, every value verb is disabled with
  the reason, *Copy to…* is the one live verb (`libraryPermission`). **Speed Masters is half
  exempt**: its REST routes are `withProject`, so its stored fields stay editable from any project,
  but **BPM and TAP are read-only off the current project** — they write the running show's
  clocks, and master 1 is written as a null uuid, so before the sheet a TAP on another project's
  master 1 tapped the live one.
- **One delete for a batch** (D13). Each record is sent plain, the in-use refusals are gathered and
  asked about **once**; *Delete anyway* forces only those, *Keep them* leaves them selected.
  Records that may never be deleted (master 1, built-in FX) are skipped by name before
  anything is sent.
- **The sheets report their own failures** (D14). Their write endpoints stay in
  `SILENT_ENDPOINTS` (the dialogs they replaced rendered refusals inline), so a refused cell write is
  toasted by the sheet **by code, keyed per column** — a batch replaces rather than stacks — and
  the middleware does not say it a second time, generically.

**What the kit gained**, each generic:

- **`SheetTable`'s `firstColumn.onOpen(row)`** draws the pencil — on hover, focus and **while the
  row is selected**, so a touch screen reaches it by tapping the row — and `useSheet`'s
  `onOpenRow` is the same callback on ⏎. Pass both. The patch list's hand-rolled pencil went.
- **`useSheetKeyboard`'s ⏎-opens-row arm**, fed a row count and the callback by `useSheet`. It
  exempts **the selected row's own first-column trigger** from the focused-control guard
  (`firstColumnOwnsKeyTarget`, reading `data-first-column` and the row's `data-state`): clicking a
  name focuses its `TextCell` `<button>`, and without the exemption ⏎ would press that instead. The
  pencil (`data-row-open`) is not exempt — ⏎ on it is its own press. The count is the *visible*
  selected rows, so a selected id whose row has gone does not make one row read as two.
- **Skipped rows in `useSheet`**: `takesValue` / `splitBatch` in `sheetModel.ts`, applied in the
  commit, the row-selection path, Clear and Fan; `batchCount` counts only the rows that take the
  value, and `SheetCellProps.skipped` carries the sentence every kit cell draws on its
  `EditorReadout` — the column's `skipNote` where it knows *why* (*M2 and M4 follow M1*), else the
  rows by the sheet's `rowName`. It changed what `write` receives on the existing sheets too: a snap
  cue's Curve and a head without a beam angle or gel no longer reach their writers.
- **`LibraryRow.tsx`** (the library row) — whose `leading` slot, before the filter, is where the
  programmer's tables put their tabs (§The programmer's tables) — and **`PartitionChips`**,
  `LookFamilyFilterBar` generalised (it is gone) and still controlled, folding to a select below its `fold` of its
  **own** `@container` — so it works inside `ApplyPicker`'s portalled popover, which has no row.
  **Measured folds, chosen per chip set**: **400**, the default — the palette family's five
  chips with counts are 361–367px, and the first cut's 600 folded them on the 1180×820 frame where
  the row leaves them ~430 — **560** for Scripts (all six types in their short labels, 542), and
  **640** for the FX Library (seven families since Palette Steps added *Steps*, effects plan
  session 7: 619px with *All · 30*; at 560, which its six took, they ran under *New FX* in
  1300–1349px windows). Session 2 wrote "one number for every library, re-measured"; measured in
  session 4, one number moved to 560 would fold the palettes on an iPad where they fit, so the fold
  became a choice of literal class pairs (`FOLD_CLASSES` — Tailwind never emits a class built from
  `${}`).
- **`usePartitionView.ts`** (session 4) — the partition as a view, never a route: a `?param=` plus
  a remembered value, the first render reading the stored one and never writing it back on arrival,
  a `?param=` arriving winning and being remembered, a change mirrored into the URL with `replace`.
  The Scripts (`?type=`, `scripts.type`) and FX Library (`?category=`, `fxLibrary.category`) routes
  take it; `routes/Palettes.tsx` still owns `?family=` by hand, as it did first.
- **`groupRows.ts`** interleaves the dividers; the caller mints the divider row, and a row in an
  undeclared partition is never dropped.
- **`cells/NumberCell.tsx`** — `TextCell`'s shape with `EditorField` inside: a unit, a range that is
  **refused rather than clamped**, commit on Enter and Apply rather than per keystroke (a BPM typed
  live would retune a clock through `1`, `12`, `128`).
- **`ReadOutButton.tsx`** — `CueSheet`'s private `ReadOut`, lifted: a read-out whose display is a
  press. TAP is one.
- **`LibraryNameColumn.tsx`** — `libraryNameColumn()`, the `firstColumn` every library spreads in:
  the rename `TextCell`, a prefix (`M2`), badges, the pencil. A render helper, not a column.
- **`useBatchDelete.ts` + `BatchDeleteDialog.tsx`** — D13, answering per entity
  `ok | inUse(summary) | refused(reason)`. The plain pass is sequential, since every delete
  invalidates the list.
- **`libraryScope.ts`** — `libraryPermission(isCurrentProject, projectName)`: the permission, the
  bar's words and the reason.
- **`reportSheetWriteFailure.ts`** — D14's toast, `rigWriteFailureMessage`'s pattern.
- **`LibraryVerb.tsx`** (session 2) — a row verb on the bar, its disabled title the *reason*, and
  `oneRecordReason` for Include and Pick up over several rows. Its word folds below **1100px of
  bar** (`ROW_VERB_WORD_CLASS` in `toolbarFolds.ts`), ungated by a strip and earlier than Set and
  Clear: measured on the palette sheet, Set · Clear · Fan, four row verbs and Deselect are
  769px worded — ~1090 with the counts and hints — and the 1180×820 frame gives the bar 940, so
  Delete and Deselect were clipped.
- **`CopyToProjectSheet.tsx`** (session 2) — *Copy to…* over a selection: one request per record,
  the target defaulting to the running project when the library shown is another's, a new name
  offered only for one record, and a record that fails named in the sheet's alert and **kept** for
  a retry while the ones that landed drop out. It replaced `CopyLookDialog`.
- **`useDuplicateBatch.ts`** and **`LibraryReadOuts.tsx`** (session 2) — Duplicate over a selection,
  one copy-route call per record, a refusal toasted once under the sheet's key; and `CountReadOut`
  (a faint em-dash at zero) and `ReadOnlyNote` (the scope's lock and reason on the bar).
- **`lib/duplicateName.ts`** (session 2) — Duplicate's `(Copy)`, `(Copy 2)` …, against the
  library's names **and the ones the batch has already minted** (it adds to the set it is given),
  since the list has not refetched between two copies of one batch.

**The Speed Masters sheet** (`components/speedMasters/SpeedMasterSheet.tsx`, on
`routes/SpeedMasters.tsx`) is beat · BPM · Tap · Start · Follows · Ratio · Family · Used by · Notes.
Four things about it are easy to get wrong:

- **BPM is the live tempo** (`speedMasters.setBpm`, the null uuid for master 1), **Start the stored
  row** (`PUT {bpm}`). They are two columns because they are two routes, but **on the running
  project the desk couples them both ways**: a live change is written back to the row after 750 ms
  (`Show.kt`'s debounce), and a PUT of a stored tempo retunes the running clock. So a Set on BPM
  moves Start a moment later, and a Set on Start on the current project is a live tempo write.
- **Follows carries the origin's leader to every selected row**, so its `write` also drops a row
  that may not follow it — itself or one of its own followers, which the desk would refuse as
  `SPEED_MASTER_FOLLOW_CYCLE` — and says so in a toast. A new link starts at
  `DEFAULT_FOLLOW_RATIO`, a re-pointed one keeps its ratio, and master 1's two spellings (its uuid,
  and the null that means it) are normalised before comparing, as the detail sheet's
  `canonicalTarget` does.
- **Family over more than one row is refused before anything is sent** (unless None): a family is
  one master's per project, and the desk would accept the first and 409 the rest.
- **Off the current project the live bank is not read at all** — no beat, the stored tempo shown —
  because a cloned project's masters can share uuids with the running ones.

**The Looks sheet** (`components/looks/LookSheet.tsx`) is Families · Preview · Contents · Scenery ·
Notes · Cue layers · Busk pages — **Scenery** a read-out since the scenery-programmer plan's session 3
(D10): a `Blinds` glyph and a summary such as *Moon in · Sofa shown* off `LookSummary.scenery`
(`describeLookScenery`, `summariseSceneryState` in `lib/scenery.ts`; a flown piece's in and out named
from the scene's element list, metres until it loads), blank for a Look with none, and *Scenery only*
in Contents for a Look that lights nothing. **Preview is one line that never wraps**
(`LookPreviewStrip` in `lookValueChips.tsx`): the chips that fit, `+N` for the rest (every value on
the cell's hover), then the FX glyph; a Look of FX alone names them off
`LookSummary.fxTypes` rather than showing a bare glyph. What fits is measured off an invisible copy
of every chip (`previewChipsThatFit`, pure in `previewFit.ts`) and re-measured on resize — the
`flex-wrap` it replaced put a Look's second and third lines of chips over the rows around it. The Scenery column's 120px floor puts the sheet past the 1180×820 iPad
frame's ~940 with the sidebar open, so there it scrolls sideways under the sticky name, as the plan's
§4 has it; **the Palettes sheet** (`components/templates/PaletteSheet.tsx`) is Holds ·
Value · Fade · Master · Notes · Layers · Pages · Pressed, grouped by the route under family dividers
(§Looks, palettes and layers). Session 2's facts:

- **A record with busk pads is held for the batch delete's question, unsent.** Pads are a hint the
  desk does not refuse on — `layerCount` alone gates the delete — but they go silently with the
  record, and the confirm the sheets replaced (*"It has pads on 2 busk pages; those go with it"*)
  was the only warning an operator ever got. So `useLookDelete` and `usePaletteDelete` answer
  `inUse` for one on the plain pass **without sending**, and the dialog lists it beside the desk's
  refusals, as the Kit board draws *Ballyhoo · on 3 busk pages*. **Its *Delete anyway* is sent
  plain, never forced** — pads were the only use the dialog could name, and the desk skips its
  usage check on `force`, so forcing would take a cue layer, an FX reference or a running layer
  with it unsaid. If the desk then answers in use, the dialog reopens with its answer
  (`useBatchDelete`'s force pass reopens on an in-use outcome, handing `remove` the record's earlier
  answer as `previous`), and only that answer's *Delete anyway* forces.
  Both hooks are also the detail sheets' deletes — `LookDetailSheet` and `PaletteEditor`'s
  Delete — so a single delete asks the same question in the same dialog.
- **The palette in-use line names all three uses the desk counts**, because each fails
  differently under force: cue layers go with it, FX parameters naming it (`fxReferenceCount`)
  are not rewritten and an unresolved colour runs **white**, and programmer layers applying it now
  (`runningCount`) stop at once.
- **Another project's library is read-only on the server too.** Both libraries' PUT and DELETE are
  `withCurrentProject` (a DELETE there answers *Cannot modify - not current project*), only the copy
  route is `withProject` on its source — so off the current project the rename, Notes, Fade and
  Master are inert, **no row opens** (the detail sheet and the editor edit exactly what the scope
  refuses), Pick up is not drawn, and Include, Duplicate and Delete are disabled with the reason.
  It also means a test record copied *into* another project cannot be deleted from here.
- **Fade is seconds, on value palettes only.** A null fade — *default (none)*, which a press
  applies at 0 — draws the em-dash and opens its editor at 0; Clear writes null with
  `fadeDurationMsPresent: true`; Fan is the kit's `duration` plan. Set and Fan compare a null
  fade **as 0**, so Enter on an untouched editor does not turn *default* into an explicit 0s.
  An FX palette's Fade is `undefined`: blank, and named when a marquee sweeps it up.
- **Master is the FX's timing in `PUT {fx}`: Own tempo, Natural or a master** (fx-speed plan D3,
  D4), the free option (*Own tempo / Natural*) offered first on every FX. A master is
  `speedMasterUuid` on a `BEAT` FX, `rateSpeedMasterUuid` on a `WALL_CLOCK` one; Own tempo is
  `ownBpm` with no master on a beat FX (starting at the master's **stored** tempo, since the sheet
  may edit a project that is not current, its cycle kept) and Natural a null rate master on a
  wall-clock one; the cell reads `Own 96` or `Natural`. The body is the whole FX with the timing changed — the flag only
  where it moves — since the PUT replaces the FX half, and it **recreates the FX**, so a
  running instance restarts (the cell's title says so). A beat FX's null is master 1, compared
  canonically and written back as null — and it reads as master 1 even before the bank has loaded (a
  `master-1` stand-in, never on the wire), or a marquee over it would skip a row that takes a master.
  Clear is each kind's default: master 1 on a beat FX, Natural on a wall-clock one. An FX
  whose type no longer resolves has no `timingSource`, so which field to write is unknown: its Master
  is blank and skipped.
- **Value reads `rows ?? []`** and never resolves (`paletteIntent.ts`'s rule): the swatch and
  `describePaletteRows` for a generic value, *n heads · per fixture*, or the FX and its cycle.

**Session 3 put a generic value palette's Value in the cell** (D6) — `PaletteValueCell`, on the
Palettes sheet's Value column. What it is and what it learned:

- **It mounts the editor's own family controls, lifted, not the editor kit's editors.** `FamilyControls`
  and its four (`ColourControl`, `PercentControl`, `PositionControl`, `BeamControls`) moved into
  `components/templates/familyControls/` **with the rows half** — `seedValues`, `withIntent`,
  `colourPolicyLocked`, `effectiveColourPolicy`, `paletteRowsFromValues` (the editor's `rows` memo)
  and `paletteRowsKey` (its dirty check), all in `paletteRows.ts` — and `PaletteEditor` imports
  them back, so a palette's value has one control and one grammar in two hosts. (`valueChanges`,
  `applyValueChanges` and `paletteValuesEmpty` joined them for the cell.) The rgbonly rule is
  the builder's, applied to the saved rows (§Looks, palettes and layers); a host that built rows any
  other way could send the pair the write boundary refuses by name. The hex field gained
  `type="text"` and an `aria-label` (*Hex colour*) in the move — `useEditorKeyboard`'s selector
  names the type, so without it Enter in the field did nothing in the cell.
- **Their Recent bands are the live project's** (programmer-fixes plan D20, D20a): both hosts pass
  the palette's `projectId`, and a palette of another project draws none and records nothing. A
  percent is the palette's own `palette-level:` list, never the grid's `level:`; a colour is its hex
  alone, a tap keeping the policy and the emitter rows. They record a draft's gesture, before Save
  or Apply.
- **Generic value palettes only.** A per-fixture palette's values were recorded per head and an
  FX palette holds no value, so both keep the read-out — `value: undefined`, blank of any
  editor, named when a marquee sweeps them up (*Pulse runs an FX — open to change · skipped*) —
  and change through the pencil. So does a palette whose `family` is null (rows naming no known
  attribute): there is no control to mount.
- **The family rule lives in `write`, not in `kind`.** The column has no `kind`, so it takes commits
  from its own editor alone; the commit is `{family, values}` — the origin's family travels with it
  — and `write` drops every row of another family. The **editor names them**, since the kit's skip
  read-out knows only `value: undefined`: the column hands the cell a `landing()` over the batch, and
  the label line counts the origin's family (*2 palettes*, not the marquee's 4) beside *Amber is
  Colour · skipped*.
- **It commits on Enter and Apply, never as the controls move.** A rows PUT republishes every cue
  layering the palette, so a colour drag written per frame would be a republish per frame — the
  `NumberCell` / `TextCell` rule, not the level editor's. Three families have no text field, so on a
  desk the **first slider takes focus** (Radix's default is the first button, a *Clear*, which made
  Enter a deletion) and Enter on a focused slider applies.
- **A commit carries what changed, never the origin's draft.** The PUT replaces a palette's rows
  whole, so the first cut — the origin's rows written to every same-family palette in the marquee —
  deleted a sibling's strobe, white or other beam role on an Enter that changed nothing (found in
  review, the colour editor's unstated-emitter bug again). The cell commits `valueChanges(seed,
  draft)` — each attribute set or removed — and `write` applies it over **each palette's own**
  `seedValues` (`applyValueChanges`) before the builder, so an attribute the operator did not touch
  stays as each palette has it. A removal that would leave a sibling with no rows is skipped and
  toasted by name. `PaletteSheet.test.tsx` pins it with a dimmer + strobe sibling.
- **A no-op writes nothing, compared through the builder on both sides.** No change is no change
  anywhere, and `write` builds each target's current rows from `seedValues` rather than reading them
  raw, so a stored lower-case hex or an older row order is the same intent. An empty draft is refused
  in the editor (the write boundary 400s a value palette with no rows); Clear is refused on the
  column and there is no Fan (plan §7). The draft is cached per palette object
  (`paletteValueDraft`), so a row's cell sees one identity until the list refetches, and the
  editor reads the batch's landing once, on the open.
- **Measured in the app on 2026-09-24**: the popover is **288** for Intensity, Position and Beam and
  **320** for Colour (the 160px picker, a gap and the hex column need 296 inside the padding). Its
  body scrolls under the viewport with Apply outside the scroller, `SpreadPanel`'s bound — Colour is
  ~565px of controls and Beam ~625 (the exclusions list), both taller than half an 820 frame. The
  footer's copy is the board's: *Value edits the intent — the desk resolves it per head*.

**The FX Library sheet** (`components/fxLibrary/FxLibrarySheet.tsx`, on `routes/FxLibrary.tsx`) is
Output · Mode · Timing · Params · Drives · Source under family chips and dividers; **every column
is a read-out**, so nothing is in the marquee and there are no cell verbs. The route was 930 lines
of one file; the sheet and the three sheets it opens — `FxDetailSheet`, `EditFxDefinitionSheet`,
`NewFxDefinitionSheet` — are `components/fxLibrary/` now, with the rules in `fxLibraryModel.ts`.

- **Source is the definition list read against the library** (`fxSourceOf`): a row is **custom**
  when some definition's `fxId` is the entry's id (`useFxDefinitionListQuery`, the first client
  of `GET fx/definitions`, tagged `FxLibrary` so the `fxDefinitionListChanged` bridge in
  `store/fixtureFx.ts` invalidates it on the library's frame); any other `USER` entry is
  **script**-registered, and the rest are built-ins. The library entry alone cannot tell the two
  `USER` kinds apart — both carry a `sourceDefinitionId`, which is a definition's row id for one and
  the **script's** id for the other — and that was the plan's §1 bug: the old page fetched
  `fx/definitions/<scriptId>` for a script's FX. **What a row opens follows the source**: a
  custom row its definition's editor, a script's FX **its script** (`/scripts/:scriptId`, the
  Scripts route's deep link), a built-in its read-only detail. A custom row is named by its
  definition (`entryName`); everything else by its registry id as words.
- **The library is the running show's, whatever the URL's project.** `GET fx/library` and
  `GET fx/definitions` carry no project and `/fx/definitions` always writes to the running show,
  and there is no copy route — so off the running project the sheet is the read-only scope with
  **nothing** live, and every row opens the read-only detail rather than an editor for the running
  show's record. Its reason is its own (`libraryPermission`'s third argument): the default
  *…library — copy it here to edit* would promise a verb this sheet does not have.
- **Rename is `PUT fx/definitions/{id} {name}`, on custom rows alone** — a built-in's name is the
  desk's and a script's FX's is its script's, so `renameDisabled` refuses both, and the name
  column's *Double-click to rename* hint is drawn only where the rename is (`LibraryNameColumn` asks
  once, for both; the hint stood on refused rows until this session).
- **Fork (D9) takes one built-in** and is refused over several, over a custom or script row, or on a
  built-in that publishes no script, each with the reason; the detail sheet's footer carries the
  same press. `forkRequest` copies the script, family, output type, mode, parameters,
  `compatibleAttributes` and `timingSource` into `POST /fx/definitions`, named *<name> (Custom)*,
  and the route opens the new definition's editor. **The `fxId` is what must be unique**:
  `FxRegistry.register` overwrites by id and deleting a definition unregisters its id, so a fork
  sharing its source's id would replace the built-in and its delete would remove the built-in until
  a restart. `uniqueFxId` mints `<id>Custom`, `<id>Custom2`… against every library id **and**
  every definition's (one whose script failed to compile is not registered but holds its id), under
  the registry's own normalisation (case, spaces, underscores), and the name takes the same ordinal
  — *Pulse (Custom 2)*. **New FX** mints through the same rule: it took `name` without spaces
  as its id, so a new FX called *Pulse* replaced the built-in.
- **A fork starts with `defaultTiming` `CYCLE`, and the editor says so.** The library entry does
  not publish a built-in's default, so the fork cannot copy it; `EditFxDefinitionSheet` draws the
  toggle (a PUT field it never exposed) with a note naming the built-in when Fork opened it.
- **Delete takes custom rows only**, through `useFxDefinitionDelete` — the sheet's batch and the
  editor's Delete — which skips built-ins and script-registered FX **by name** before anything
  is sent, and is refused with the reason when the selection holds no custom row. The desk keeps no
  record of what uses a definition, so there is no in-use dialog; `deleteFxDefinition` joined
  `SILENT_ENDPOINTS`, because `useBatchDelete` toasts every refusal itself.

**The Scripts sheet** (`components/scripts/ScriptSheet.tsx`, on `routes/ProjectScripts.tsx`) is
Type · Lines · Check · Used by under type chips — *All* and one per type present, in short labels —
and type dividers. `ScriptTypePanel` (the sidebar and its phone sheet) and `ScriptListContent` are
deleted, and with them `scriptUtils`' usage-glyph helpers, which only ever read the type.

- **Check is this tab's last Compile** (D8), a `Map<scriptId, ScriptCheck>` in the route, never
  stored (`scriptCheck.ts`). The bar's Compile sends each script's **text** and type
  (`POST …/scripts/compile` compiles a literal), **one at a time** in visible order — the rows read
  *Queued*, then *Compiling…*, then ✓ or *2 errors · line 14* (the first error's message on the
  title) as each answer lands. A result carries the text and type it compiled, and `checkFor`
  answers only while the script still has them, so an edit reads *not checked* again with no
  invalidation anywhere, and a rename keeps its check.
- **A rename is `PUT {name, script, scriptType}` from the list's own copy of the row**: the route
  replaces the whole row and `NewScript.scriptType` defaults to `GENERAL`, so a rename that left the
  type out would silently turn a definition script into a general one.
- **Used by** is the FX an `FX_DEFINITION` script registers — `fxRegisteredBy`, the FX
  Library's discriminator from the other side, so a custom definition whose row id happens to equal
  a script's id is never credited to it. Anything else reads *—* until `FU-SCRIPT-USED-BY`: cue
  hooks are only on full cue details. Off the running project it is *—* throughout, since another
  project's script ids name nothing in the running show's library.
- **Run takes one script** (by id, against the live show; the route shows `ScriptRunDialog`), and
  Compile, Run, the rename and Delete are the running project's alone. Off it, *Copy to…* —
  `CopyToProjectSheet` over `copyScript`, which keeps the type since session 0 — is the one live
  verb, and a row still opens: `ScriptForm` has a read-only arm.
- **Delete** is `useScriptDelete` — the sheet's batch and `ScriptForm`'s Delete, which lost its
  `confirm()` for it. The desk has no in-use refusal for a script, so the one use this side can name
  is held back unsent, a Look's busk pads' way: an `FX_DEFINITION` script whose FX are in the
  library, listed with what it registers. Deleting it does not unregister them — they stay until the
  desk restarts — so *Delete anyway* is the question; it sends plain (there is no `force`). Every
  other script deletes on the plain pass. `deleteProjectScript` joined `SILENT_ENDPOINTS`.
- **The form re-reads its script from the list by id**, as the Look and palette routes do, so a
  rename on the sheet reaches an open form, and a script deleted from another client closes it
  rather than turning it into a create form.

### The editor kit

**Every value editor is built from the same five pieces, exported once from `components/editor/`**
(editor-kit plan D8, D9; the design record is `../docs/plans/completed/editor-kit-design/`, whose
`Editors.dc.html` draws the anatomy and each editor on it). They were the busk sheet's anatomy —
9px uppercase labels, 28px fields with the unit inside, a read-out line, a static footer with the
save first — and the programmer's four cell editors and the kit's four each answered the count
line, the label style, the field height and the unit their own way. What lives there now, and what
each was:

- **`EditorSurface`** — `CellEditorSurface` moved (§The cell editor's three forms): the three
  forms, the double click, `wide`, the anchor. Its hooks are `useEditorForm`, `useEditorCramped`
  and `editorIsOpen`; the `data-cell-editor-surface` attribute and the two media constants kept
  their spellings, which `shortViewport.test.ts` pins. `useEditorKeyboard`, `useEditorOpen`,
  `ValueFieldRow` and `UnsetCellMark` moved beside it, and `HandChip` and `useEscapeEditorSnapshot`
  follow the `editorIsOpen` rename.
- **`EditorLabel`** — the busk view's `BuskLabel`, moved and renamed; the busk view imports it
  back. The label over every control.
- **`EditorField`** — one 28px number field with the unit as a trailing muted glyph and
  `useNumberFieldDraft` inside. It replaced `ValueFieldRow`'s bare `Input`, the busk Fan tab's
  private `NumberField` and the colour editor's `ChannelNumberInput` — which survived session 1 as
  a thin wrapper and went in session 2, when `ColourEditor` took the field directly and its byte
  clamp with it. **The caller still clamps**: the field parses, and a channel byte is 0–255 where a
  slider cell's bounds come from its resolution and a percent is 0–100. The native spinner is hidden
  so the unit glyph has the right edge; the arrows still step the value.
- **`EditorReadout`** — the 10px muted line under a panel's controls, saying what the desk holds
  and what was skipped, with a `lines` arm that was `LandingLines` (the address editor's and the
  Key column's one-head-to-a-line landing; `LandingLines.tsx` is deleted).
- **`EditorFooter`** — save slot · note · spacer · verbs, the shape the busk Colour and Fan tabs'
  footers have. Drawn where a panel has **verbs beside the value**: Apply on `TextCell` and
  `AddressCell`, and — since session 2 — the colour editor's Save · Pick · Fan…, which are
  gestures about the batch and not writes of it (that editor writes as it goes; its footer writes
  nothing). The level and position editors draw it too since apply-model session 5, for
  `PaletteBands`' *Save as palette…*; never the setting editor, which writes as it goes and carries
  no verb. A panel that docks it full-bleed says so at its own import — the busk Fan tab with its
  `px-3 py-2` (session 3 rewires it), `ColourEditor` for its docked host.
- **`ColourEditor`** — `fixtures/ColourPickerBody` moved and finished (D10, D12; session 2): the
  picker, the typed R/G/B, the emitter rows and the six-channel buffer, **plus everything the busk
  Colour tab had grown around them** — the read-out line (the emitter counts per head from
  `emitterHeadCounts`, *mixed*, the swatch, the hex), **Pick**, the **Palettes band** and the
  `EditorFooter` with *Save as palette… · Pick · Fan…*. The host says which pieces it draws: `targets` (the
  heads, in the host's order — the busk selection expanded in rig order through `headOrder`, the
  programmer's marquee column in row order), `projectId` (what the hidden leaves read the patch
  list of), `palettes` (the band's press: its targets and its mask — *Colour* in a cell, the busk
  pair's in the tab),
  `footer`, `counts`, `labelLine`, `docked` (the busk tab's frame: one scroller, the footer static
  under it), `pickOnTargets` (the busk tab's seed from the rig), `onPick`, `onSave`, `onSpread` (absent
  draws the button inert — session 3 wires the programmer's). The cell hands it the batch's targets
  **as a colour commit lands on them** (`colourTargetsOf`: one per RGB colour cell or warm/cold
  white pair, a bar whose colour lives on its elements expanded, a head with neither dropped — a
  colour-wheel head resolves a `colour-setting` cell the batch counts and a colour commit refuses,
  so the label line counts this list rather than `batch.count`, and the two lines say one number),
  and the container passes `projectId` for the **programmer alone**, since Save records from the
  programmer. A batch of warm/cold white pairs alone (the
  blinder's elements, venue-visit plan D8) opens `WarmthCell` instead — a warmth between the pair's
  declared temperatures and a level, committing the colour that mix makes (`lib/whiteMix.ts`;
  `docs/fixtures-engineering.md` §"DmxWhiteMixColour"), with Save and Fan in its footer — and
  `ColourCell` dispatches between the two. Its controls are `editor/WarmthEditor`, which the fixture
  sheet's `white-mix` row draws too. Its hosts: the programmer's colour
  cell through a thinned `ColourPickerPopover` (the popover at 352 — `contentClassName`, measured
  in the app on 2026-09-22 with the picker at 234×200 — and **528 compact**, the side sheet's
  compact width, because the compact body sits its 208px emitter column beside a 256px picker row
  and at 352 it wrapped under, costing the height compact exists to save; and both sheets, `wide`
  on the side sheet),
  the busk tab docked, the Fan tab's colour endpoint (`footer` and `counts` off — a colour intent
  has no emitter component), the fixture sheet's colour rows (docked in the row, `footer` and
  `counts` off, the typed channels on) and the FX colour parameters (venue-rig-fixes plan D18):
  `FxColourPicker` and each swatch of `FxColourListPicker` in a 352px popover, through one
  adapter, `fx/FxColourEditor`, over **`ColourEditorCore`** — `ColourEditor` is the core plus its two press bands, which reach
  `ApplyPicker` and through it `FxEditor`, where these pickers are drawn, so mounting `ColourEditor`
  there would be an import cycle — `footer` off, the
  typed channels on, the band as **`references`** — `FxColourPaletteRow`'s chips picking a
  `pal:{uuid}` and its *Save `<hex>` as palette…*, the read-out naming the palette a value
  references — and **`onRelease`**, the gesture's end (the picker let go a frame after its last
  move, a picker key's key-up, a field left, a slider's commit a microtask on, a recent tapped, the
  editor closed), each edit before it a frame, which is how the FX editor's live push and the cue
  FX tab's saves keep their floors. Pure white stays `#ffffff` there (`pickerWhiteLed` off, its
  hints not drawn): one FX colour reaches every head, and the white LED alone would black an RGB
  one. Their emitter rows are the targets' heads' through `emitterFlagsOf`, the walk
  `emitterHeadCounts` runs (`lib/emitterHeads.ts`), a Shehds' zones included (D19); a palette's
  FX offers what any patched head takes; an emitter the targets lack is never written, so the
  value keeps a stored one. The picker-only form in a 224px popover was the property
  visualisers', which fixture-fx-sheets session 4 deleted. **Pick reads the heads the editor is
  editing** through one hidden `FixtureAppearanceSource` leaf per parent fixture, mounted by the
  editor itself in a store-bound child (`AppearanceLeaves`) so a cell mounted with no project pays
  nothing — the grid's rows do not report into `lib/liveAppearance.ts` and are not made to; it moves
  the knob, the fields and the *mixed* marker and writes nothing, the picked colour becoming the
  buffer the next single-channel edit builds on — and **an emitter the host does not hold stays
  unstated**: the rows are the batch's union but the bytes are one row's, and `writeColour`
  samples the wire only for an undefined component, so folding a missing white to 0 drove every
  RGBW head in the marquee to white 0 on a byte typed from an RGB row's cell (found in review;
  the picker gesture and Pick zero only what the editor holds) — (`targetHeads` in `liveAppearance.ts` is the fold,
  over `WriteTarget`'s new `fixtureKey` / `cellIndex`, which `rowWriteTargets` stamps on an element
  row and `writeTargetsOf` on a busk element — element keys are never parsed). **The Palettes band is
  drawn in every form** (apply-model plan §4.3), above the picker, and in the busk tab's scroller
  where Recent was: D11's *drawn once on a screen* held for Recent, a recents row like row C's
  strip, and the band is the family's offer instead. Its store hooks run in the band, mounted only
  where a host passes `palettes`, never per cell. **The guidance is the picker's
  title everywhere**, the paragraph gone. **The picker is fluid in every host**: `index.css` keeps
  the picker's look (saturation radius, the 12px hue bar and its 16px gap, the pointer) and the
  `.colour-picker-fluid` and `.colour-picker-compact` rules, and the 200px `!important` pin is gone
  — with two things recorded there. **The picker cannot be sized by a Tailwind utility**: v4 emits
  utilities inside `@layer utilities` and react-colorful's stylesheet is unlayered, which beats any
  layered declaration whatever its specificity (measured on 2026-09-22: `[&_.react-colorful]:h-44`
  was generated, applied and lost to the library's 200px; and an arbitrary variant reads `_` as a
  space, so `__hue` became ` hue`) — so the compact heights stayed in `index.css` after all, where
  the plan had them reduce to the fluid rule. The old fan popover's inline 150×120 pickers went
  with it in session 3: the Fan panel's colour endpoint is `ColourEditor` compact in every host
  — the busk tab's shipped measurement, `.colour-picker-compact`'s 11rem — and never a utility. `ChannelNumberInput`
  is deleted; the editor mounts `EditorField` and clamps the byte itself.
- **`RecentValues`** — the **Recent** band (programmer-fixes plan D14–D19, session 4): a label and
  one row, never wrapping, of at most eight of the live project's last values for one list —
  swatches for a colour, the hex on the hover; chips for a level, a position or a warmth
  (`62%`, `−20, 12`, `3200 K · 80%`). The desk keeps the lists, for every window and device
  (`recentValues.state` / `.record`, `api/recentValuesApi.ts`); the keys and spellings are
  `recentValueSpelling.ts`, mirroring lighting7 `state/RecentValues.kt` — a colour lowercase
  `#rrggbb` with a `;w` / `;a` / `;uv` tag per emitter the editor wrote, zero included, so a white
  set to 0 replays as 0. `store/recentValues.ts` reads it through `useSyncExternalStore`, not an RTK
  entry, since the kit's hosts provide no store, and its hook runs in the band, so a list's change
  re-renders the band alone: mount it in an editor, never in a row. A tap hands the host the value,
  which writes it **through its own typed path** — in a batch every selected cell, in the
  programmer Local, and it records the value **as tapped**, so the desk moves that recent to the
  front. **`useRecordRecent(key, projectId)`** is the editor's recorder, subscribing to nothing: a
  commit inside a gesture is `stage`d, under the key and live project it was staged in, and only the
  gesture's last is sent. `flush` sends it at once — a field left (`EditorField`'s
  `onDraft(null)`), and a slider's `onValueCommit` a microtask later, since Radix commits a key's
  step before it reports the step's value. `release` is the picker's alone (its window release
  through `holdPicker`, an arrow key's key-up): it sends 100 ms later, because react-colorful reports
  a drag's last move from an effect that can land after the release, and the next press flushes
  first. A tap is `now`, after anything staged; closing the editor sends what is staged. A colour's
  label is its hex and each emitter it lights, `#000000 · W 255`. Never recorded: a palette press,
  Pick, an FX swap or Revert, a MIDI write. The band draws
  nothing, and the recorder sends nothing, while the list is empty, before the desk's first frame
  (an older desk sends none), or in an editor whose `projectId` is not the live project. Session 4
  mounts it in `ColourEditor` under the controls and read-out, above the footer, in every host
  (read-only takes it out of reach with the controls). Session 5 mounts it in the other value
  editors, each recording its own list and replaying a tap through its own commit:
  - the programmer's cells — `SliderCell` (`level:<attribute>`, never for a pan or tilt:
    `recentLevelKeyFor`), `PositionCell` (`position`) and `WarmthEditor` under `WarmthCell`
    (`warmth`). The two cells draw their editor body as an inner component (`SliderEditorBody`,
    `PositionEditorBody`) so the recorder mounts and unmounts with the open editor: a hook at the
    cell's level would outlive the popover and never send what a closed editor staged.
  - the FX colour pickers (each colour of a list too) through `ColourEditorCore`, which records as in
    every host; the project is the route's, as the pickers' palettes are.
  - the palette editor's `FamilyControls`, given the palette's `projectId` by `PaletteEditor` and
    the palette sheet's Value cell: `PercentControl` records `palette-level:<attribute>`,
    `PositionControl` the pair, and `ColourControl` its hex alone (D20a) on the window's release
    (`useWindowRelease`), a tap setting the hex and keeping the policy and the emitter rows.
  - the fixture sheet's rows (§The fixture sheet).

  A **position** records only a pair, and nothing while the other axis is not one value over the
  heads: the cell takes it from what the open editor has set, else from its own scoped value where
  that is uniform over exactly the batch's heads — never the wire, which is not the scope being
  edited; the fixture sheet takes the pair its write resolves (`sheetPositionRecent` over
  `sheetWrites.ts`' `resolvePosition`: the held entry, else the wire). A slider's gesture ends on
  `useSlideEnd` — Radix's `onValueCommit` and the press's window release, since Radix sends no
  commit for a drag back to where it began — and the recorder's `slideEnd` sends a microtask on.
  A key's step on a picker records on its key-up.
- **`useLivePush`** — `hooks/useLivePush.ts` moved beside its callers, and **`useSheet`'s ~30 Hz
  commit throttle is that hook with `floorMs: 33`** (D16): the busk tabs' hook and the sheet's
  throttle were two copies of one rule — a floor, a dedupe, a release that always lands. Two things
  the hook has no notion of are kept in `useSheet`: a commit for a *different* cell lands the
  pending one first, and an unmount lands whatever is pending. **The sheet switches the hook's
  dedupe off** (`neverEqual`): the hook dedupes a gesture's moves against what it last sent and
  relies on `reset()` for a fresh gesture, and a sheet's value moves by routes the hook never sees
  — ⌫ through `column.clear`, Fan, Park, the wire — so a value set, cleared and retyped would
  never reach the rig while the field showed it. `useSheet.test.ts` pins the cadence as the
  literal 33, the trailing call, and that a repeat is sent. So a component test's **second**
  editor commit on one sheet (an Enter, a pick; a key typed over the marquee is not throttled) lands
  at once only if 33 ms have passed since the first, which a fast machine does not take: assert it
  through `waitFor` (flaky-tests plan session 3).

- **`PaletteBands`** — `components/editor/PaletteBands.tsx`, the per-attribute popup (apply-model
  plan §4.3, D4; session 5): the family's palettes **round** a value cell's editor, in every form it
  has. `SliderCell` and `PositionCell` wrap their controls in it; `ColourEditor` draws its
  `PaletteBand` itself; and the busk side sheet's family tabs dock the bands round their editors
  (`busking/FamilySheets.tsx`, §Focus and the side sheet), passing the busk pair and
  `scope: 'programmer'`, which both bands and `PaletteFooter`'s `className` take for a docked host. **Above**, the Palettes band: a header — *Palettes*, the cell's family as a
  badge (the mask a press carries, §3.2 rule 5), the heads it lands on (a fixture by name, an element
  by its element key, which is what the press sends), *All · n* — then the family's **value**
  palettes that fit the heads, recent first and then by name (`recentFirst` in
  `lib/paletteRecents.ts`), two rows of chips clipped by height, never a scroller. The offer is
  `paletteFits`'s (`lib/paletteOffer.ts`), the picker's too: the family, every emitter a palette names
  somewhere in the heads (so a Shehds zone is offered what its zone takes), and **for a Beam cell
  the palettes with a row for its attribute** — a zoom cell is offered *Wide*, not *Soft edge*.
  A chip is `PalettePressButton` through `usePress` over the cell batch
  (`paletteTargetsOf(batch.targets)`) with the mask `[family]`, so the gestures and the ring are
  the picker's; under a focused Look a teal *Into <Look> · as references* line shows and the rings go. *All* mounts
  `ApplyPicker` on the family on its first press and keeps it, with no *New from selection*
  (`offerNew={false}`): the picker lives inside the cell editor, and a sheet there would close with
  it, draft and all — the footer's *Save as palette…* is mounted outside. **The editor**, unchanged. **Below**,
  `PaletteFooter`: *Save as palette…* (not in a venue), whose sheet the cell mounts **outside** its
  surface through `usePaletteSave` — a press in a sheet is an outside press to a popover, which
  would close the sheet with it — then the family's own tools in its `tools` slot: the position
  cell's ***Aim…*** (venue-visit plan S13, `stage3d/PositionAimTool.tsx`), `StageAimControls` in a
  popover over the cell batch's heads, refused with the reason off the rig, in Output, in a focused
  layer (aim writes Local) and where no head annotates its pan and tilt in degrees; then its
  ***Fan…*** (venue-visit plan S12, `cells/PositionSpreadTool.tsx`), which opens Fan on Position
  in *About each position* at 0 and closes the cell's editor — no seed, since a fan starts where the heads are. The
  opener is the list container's (`PositionFanContext`): row C's popover, as the colour hand-over's
  is. It is drawn only in the programmer,
  the one list whose popup draws its footer, refused under two heads (a fan of one moves nothing), in Output and on a
  focused palette layer, and live in a focused Look layer, where the fan lands in the layer's draft. At the popup's 288px both fold to their icons, their words the buttons'
  names, and *Save as palette…* keeps its word: the colour footer's fold. **Drawn only where a press has a door**: the container passes
  `projectId` on the programmer alone, so the plain lists draw the editor bare; the cue editor's
  Values grid passes none either but its presses land in the cue (`PressCue`, below), so its cells
  draw both bands and no footer — *Save as palette…* records from the programmer, and *Aim…* writes
  Local. A slider cell whose attribute no value palette can carry (`paletteCarries`,
  the closed `PALETTE_ATTRIBUTES` vocabulary) has no Palettes band and no footer. A setting cell
  (gobo, prism) passes `palettes={false}` — prism is in that vocabulary, and a value palette cannot
  carry a slotted role — so its popup is the FX band alone (§11, 11). **Below the editor, the
  FX band** (`FxBand`, session 6; `ColourEditor` draws it itself, under the cell's editor
  and docked under the Palettes band in the busk Colour tab): the FX on this attribute that
  paint one of the heads, **lit** — a tap
  opens `FxEditorPopover` beside the chip over the cell batch, so a Local FX on more heads than
  these offers the split; a cue's is drawn but not opened — then the family's **FX palettes**
  that fit, pressed through `usePress` as the Palettes band's are, and *+ FX …*, the picker's
  FX arm on this attribute under the cell's family. Its family is the attribute's group as the
  press reads it (a Pulse on a uv cell is Colour). `paletteFits` narrows a Beam cell's FX
  palettes by the attribute the FX drives — the attribute an FX palette names (a Wave's), else its
  library attributes, Slot Steps' named setting, Palette Steps' palettes' rows (`fxPaletteDrives`,
  given the library and palette list through its `FxFitContext`) — so a zoom cell is offered the
  palettes of a Wave on zoom and a gobo cell the Slot Steps of its gobo. **In the cue editor** (apply-model plan session 8) the bands carry the
  cue arm without a prop: `usePress` reads `PressCue` (`components/press/pressCue.ts`), which
  `CueRowStoreProvider` provides, so a band press there is the cue's layer press and the line reads
  *Into Q1 · as a layer*; a chip is ringed partly where the cue layers its record (`cuePresence` — by
  record, never by head, since the desk owns coverage); the FX band lists the cue's **stored**
  FX on the attribute that reach the heads (`storedFxReaches`), drawn and not opened — the FX
  tab edits them, and no live editor or `SplitFxEditor` is offered — and *+ FX …* stores a draft.
  `PaletteBands.test.tsx` pins the offer per cell, the press, the bare arm, the FX band's offer,
  lit chips and setting-cell popup, and the cue arm.

- **`SceneryControl`** — `components/scenery/SceneryControl.tsx` (scenery-programmer plan D5, D17;
  session 2): one scene element's state wherever it is edited, built from the kit — an
  `EditorLabel` with the element's name (a host that names it itself, `SceneryEditor`'s row with its
  element select, passes `showLabel={false}`; `labelEnd` takes the kind's word and a release ×), a
  `ToggleGroup size="sm"` of presets (*Closed · Half · Drawn* for a drawn drape, *In · Out* for a
  flown piece — the old step select's choices, in the mock-ups' words), a `Slider` with an
  `EditorField` (`open` 0–1 shown as `%`, `trimM` between the piece's in — its Z — and its out — its
  stored trim — in `m`; a flown piece whose stored trim is its Z has *In* and no slider), *Shown ·
  Hidden* on every element (`visible` is every kind's), and an `EditorReadout` saying what holds the
  piece. The presets, the range and the shown state are `lib/scenery.ts`'s (`sceneryPresetsOf`,
  `sceneryRangeOf`, `shownSceneryState`), so the control only draws. **No verbs and no
  `EditorFooter`**: it writes as it goes through `useLivePush` at `floorMs: 33` (the sheet's floor)
  and a release that always lands — `reset()` after every release, since the piece moves by other
  routes between gestures — and each write carries only the keys the gesture moved, merged across
  it, so a preset pressed while a drag's last move is held by the floor carries that move too
  rather than `flush` dropping it. `commit="release"` writes once per gesture instead, for a host
  whose write is a **whole-list `PUT`** (a cue's, a stack's or a Look's scenery), where thirty saves a
  second would be thirty refetches of every cue. What the operator moved is drawn over the host's
  state until the host says the same or 1.5 s pass, so the thumb does not jump back for a round trip.
  Finger-sized presets in the two sheet forms only (`useEditorForm`), never a `sm:` variant. A press
  on the **lit** preset or visibility writes too (Radix deselects it with `''`, which writes
  nothing), since holding a piece where it already is — its base, or a cue's — is a gesture. A host
  whose record states only part of the state (`SceneryEditor`'s row) passes `stated` and
  `onUnstate`: an unstated key is drawn muted, and each stated one gets an *Unset* × while the
  record states another.

**The label line replaced the count line** (D8). No editor says *Applying to N targets* any more,
anywhere — `ColourCell`'s trigger `title` was the last place the sentence survived, until session 2.
The popover form draws `EditorLabelLine` first — *4 heads · Local* on the left (the batch and the
scope, or the focused Look's name in layer scope, which no editor said before; *4 cues* / *8
channels* / *4 fixtures* on the kit sheets, from `SheetCellProps.batchLabel` and the sheet's `noun`)
and the column on the right — and **only the popover**: both sheet forms keep their title row, which
already names the column, and the band above the grid already says the count. The programmer's
cells take a **`CellBatch`** (`rowModel.ts`) in place of the old `batchCount` — the count, the
**skipped** heads (targets that resolve nothing for the column, which a geometric marquee sweeps up
along with the rest) and the resolutions — from `FixturesListContainer`'s `batchFor`, **one per marquee column**
(`marqueeBatches`): a commit from a cell in column X lands on column X's targets alone, so what the
editor says about the batch must be that column's, not the marquee's total — summed, the Dimmer
editor said "1 head has no dimmer · skipped" for a par the *Gobo* column skipped. `scopeLabel` rides
beside it from the scope and the Look store's `lookName`. The container's own 33 ms position merge
goes through `mergePositionCommits`, which keeps `panCentre` / `tiltCentre` and one unit per axis. The read-out says the skip (*2 heads have no gobo · skipped*) where the write would
silently drop it. `ColourCell` takes the same `CellBatch` since session 2, and `CellBatch`
gained the **`targets`** themselves beside the count — a count and a resolution list cannot name a
head, and the colour editor needs the heads for its emitter union, its leaves and the palette
targets its Save and its Palettes band land on.

**The unit is the cell's** (D13). The programmer's dimmer cell reads *80%*, so `SliderCell`'s field
is a percent — `toPct` / `fromPct`, 0 ↔ 0, 50 ↔ 128, 100 ↔ 255, pinned as literals in
`SliderCell.test.tsx` — and the byte is the read-out (*204 of 255 · 0–255 on every head*, or *on the
first head · ranges differ*). The slider stays in bytes. The DMX sheet reads *204*, so `LevelCell`
keeps bytes; the percent is its read-out. Strobe, zoom, focus and iris are slider cells and take the
percent for the same reason — a palette of them is a percent already. The one exception is a
**stepped zoom** (the Robe ColorSpot 575's, fixture-optics session 5): a setting, so its cell is the
setting cell listing the steps, and a palette's percent on it snaps to the step nearest that
proportion of its angles.

**Position is centre-relative DMX on every head** (venue-visit plan D3, which replaced editor-kit
D14's degrees). `lib/positionScale.ts` is the one conversion every surface takes for display and
entry — the programmer's cells, the fixture sheet, cue values, Fan, the FX editor and palettes:
`-128..127` on each axis, 0 at the centre of travel (DMX 128), one unit a coarse step, so DMX 1 reads
-127, 255 reads +127 and 0 reads -128 (Chris's call: exact, with -128 typeable). The fine channel is
not part of the scale — only aim and the pads' Stage jog (through aim's route) write it. `PositionCell`
draws a 120px XY pad (a 100px jog pad in Stage mode, below) beside Pan · Tilt — a drag writes both axes in one commit — the cell itself reads `-20,+40`, the
read-out keeps the DMX bytes underneath, and the commit carries **`panCentre` / `tiltCentre`**, which
`clampCommitToResolution` resolves to **each head's own byte** as the same fraction of its own axis
range: `128 + value` on every 0–255 axis this rig has, so a batch never splits into two units and a
head with no degree annotation takes the same scale. The literal on the wire is unchanged — a
programmer entry, cue row or Look row is still `"pan,tilt"` bytes. `lib/axisDegrees.ts` is no longer
for display or entry: it stays for the Stage view, aim (*Aim…* is offered where both axes annotate,
`sheetRows`' `aimable`) and `positionOnScale`'s one read of a palette stored in degrees.

**Every position pad has a *Pan/Tilt | Stage* switch** (`components/editor/StageJog.tsx`): the
grid's `PositionCell`, the fixture sheet's `PositionRow` and its pick row (`PickPositionControls`, so
the busk Position tab too), drawn while the row is open; Stage replaces the XY pad with a 100px jog
pad (64px folded). The mode is one fact for all three, per viewer in `localStorage`
(`lib/positionPadMode.ts`). In *Stage* the pad is a **jog**: edges *Upstage* at the top, *Downstage*
at the bottom, *SR* left and *SL* right, as the Plan draws them (*US · DS* on the fixture sheet's
folded 64px pad); a drag sends its whole offset from where it began, 0.035 m a pixel, and the dot
springs back on release. **Held at an edge the drag keeps going** (`lib/stageJog.ts`'s `JogDrag`): the
dot stops at the pad's edge, and while the pointer stays at or past it the offset travels on that way
at 1.5 m/s, 0.05 m/s faster for each pixel further past, 5 m/s at most, an animation frame at a time,
so one gesture crosses the stage, a stalled frame carrying no more than a tenth of a second; back
inside, the pointer is a displacement again from what the edge carried, no gesture goes past ±40 m,
and a lost pointer capture, a window blur or a hidden tab ends the drag. The arrow keys nudge 0.25 m,
a run of presses one gesture. The desk solves every head (`POST /programmer/aim`, `mode: JOG`; lighting7 `docs/fixtures-engineering.md` §"Jogging
heads across the stage"); `lib/stageJog.ts`'s `JogQueue` keeps one request in flight with the latest
offset winning — across every pad in the window, no faster than every 50 ms, and a new press leaving
the last gesture's let-go offset to land first — and sends back each head's first answered `base`
and its last answered degrees as `near`, so a drag is measured from its start. The sheet hosts send
the groups their pick is from (`pickGroups`), so the writes carry their source group as the XY pad's
do. The fields and sliders read the result. Live hosts refuse as
*Aim…* does — the project not on the rig, Output, a focused palette layer, no head annotating its
degrees; the cell's draft hosts (a focused Look layer, the cue editor's Values) send `write: false`
with the rows as `base` and land each head's literal in the draft (`usePositionJog`). The desk's
skips read *1 head has no stage jog · skipped* under the pad — *1 head out of reach* for one only
past its travel this step — by name on its hover.

**Widths by content** (D17): `w-72` for level, position, text and address (the busk endpoint editors'
two fields plus a gutter, up from `w-64`), `w-64` for the setting and option lists and `w-[352px]`
for colour (`w-[528px]` compact, the side sheet's compact width) — measured in the app on
2026-09-22 as 288, 256, 352 and 528 (`getBoundingClientRect` on the open popover, with the field at
28, the pad at 120 and the colour picker at 234×200), which is what each component's docblock
records.

### The cell editor's three forms

**A cell editor is a popover on a desk, a bottom sheet on an upright phone, and a right-hand sheet
where the viewport is short.** `components/editor/EditorSurface.tsx` is the one
place that decides, and every cell goes through it — the programmer's four (`SliderCell`,
`ColourCell` via `ColourPickerPopover`, `PositionCell`, `SettingCell`) and the kit's four
(§Sheet kit). There were **five**: the marquee's own typed
field, `CellEntryPopover`, drawn through this same surface precisely so that Enter and a click
would read as one kind of thing. It is gone, and the reason is the stronger form of that argument —
§The programmer's keyboard.

Three rules, each of which was learned rather than designed:

- **Short beats narrow.** A landscape phone matches both queries — 852×393 — and the bottom sheet is
  the one shape that needs the vertical room a short viewport has not got. The width query is the
  `sm` breakpoint the sheet primitive itself uses (`max-width: 639px`); the height one is the space
  plan's own `max-height: 500px` fold, duplicated and pinned by `shortViewport.test.ts` like every
  other site of that number.
- **Width is the content's to ask for, and so is the compact layout.** `wide` on the surface is the
  colour editor's alone — `ColourPickerPopover` asks for it on `ColourEditor`'s behalf, 35rem
  against 22rem for the other three, which looked absurd in that much room. `useEditorCramped()` is a *separate* `max-height: 750px` question, because all three
  forms can be short of height: below it the colour editor draws a two-column layout with its
  emitter rows beside the picker. 750 and not 500 because **a popover must fit beside its cell, and
  a cell can be any row**, so the room it really gets is about half the viewport — below ~725 there
  is a band where neither side holds the full 357px editor, Radix flips it above the cell, and
  `limitShift` then refuses to slide it back down because that would cover the anchor. It renders at
  a negative `top` and the window clips it, silently. Found on a 852×524 screen, not by a test.
  The popover is also bounded by that room (`POPOVER_BOUND_CLASS`, Radix's available height) and
  scrolls inside it, so an editor taller than it — a colour cell with both bands, low in the cue
  editor — is never cut off past the window.
- **"Is this a touch surface" is the form, never a `sm:` variant.** `SettingCell`'s option rows are
  the one control that *is* the list, so they need a finger-sized height on both sheets and not in
  the popover. A `sm:` variant says `min-width: 640px`, which is true of the landscape phone the
  side sheet exists for — so the first version shrank the rows on the very surface they were added
  for. Width cannot answer that question on this desk.

Two things the sheets carry that a popover never needed. **The keyboard's bite** is measured from
`visualViewport` and given back — the bottom sheet rises by it, the side sheet shortens — because a
`position: fixed` sheet is laid out against the *layout* viewport, which does not shrink when iOS
opens the keyboard, so a sheet holding a number field would slide neatly behind it. And the
**safe-area inset is added to the side sheet's width rather than padded into it**: as padding alone
it came off the content box and the colour picker no longer fitted, so the sheet scrolled sideways
on whichever rotation puts the notch on that edge. `SheetContent`'s own `sm:max-w-sm` silently caps
an inline width, so `maxWidth: 'none'` goes with it — the symptom of forgetting is a sheet that
stays narrow and scrolls, which looks exactly like the width never being applied.

**On the programmer's grid a single click on a cell selects it, and a double click opens its
editor.** The whole of a single click is the selection: a plain click is one cell, replacing whatever
was there — including when the cell is already in the marquee, which is how a rectangle is narrowed
to one of its own cells — and ⌘ and ⇧ shape it as on every sheet (§Sheet kit). The editor is opened by the selection bar's
**Set**, by Enter, by typing (§The programmer's keyboard), or by a **double** click, so the drag,
the single click and the keys all say *what* to edit and one gesture says *edit it*.

**The double click is that second gesture made with the pointer alone**, and it is the surface's
rather than the four cells': `EditorSurface` decides it once, above the form branch, so all
three forms and all four value kinds answer it identically — the reason `CellClickBehaviour` is one
type rather than four copies of two props. It opens through the same `onOpenChange` every other
opener uses, so it lands in `useEditorOpen`'s click path: beside the cell (Set's toolbar anchor
is Set's alone), with no typed seed, and with whatever a click's open resets reset. It is wired
**only where a single click selects** — in `CueValueGrid` a single click already opens, and two of
them would toggle the editor shut and back open. And it needs no
*permission* gate of its own: a `disabled` trigger fires no click and therefore no double click,
which is how Output scope, a focused palette layer and an unreachable desk stay read-only through
this door as much as through the other three.

**The handler is withheld while that cell's editor is already open**, because `setOpen(true)` is
not idempotent: it re-runs the cell's own open reset (`SliderCell`'s `draft.reset`, `SettingCell`'s
filter) over what the operator had half-typed, and re-latches `atButton` to false — which swings a
Set-anchored panel across the screen *and* swaps the branch from `TriggerState` to `PopoverAnchor`,
two component types at one JSX slot, so React remounts the operator's own button under their finger.

**Do not delete the guard on the grounds that Radix already closes the editor first.** At the desk
it does: the gesture's first `pointerdown` is an outside press on the open content, and
`DismissableLayer` listens for `pointerdown` on the document, so the `dblclick` lands on an editor
that has already shut and simply opens it again — verified in the browser on this change and on the
commit before it, where a single click on an open cell behaves identically. But that dismissal is
the *environment's* and not a rule this code states, and a test never reaches it: `fireEvent`
dispatches exactly the one event it names, so no `pointerdown` is seen, the listener never runs, and
the second open goes straight through. Both tests in `EditorSurface.test.tsx` fail if the guard
is dropped. **The difference is not Radix rescuing the browser case** — `PopoverContent` suppresses
an outside press only where it lands on a real `PopoverTrigger`, and this grid renders none in
either environment.

**The handler goes on the wrappers that already clone the trigger, never in a wrapper of its own.**
Each branch hands the trigger through a `Slot` as it is (`PopoverAnchor asChild`, `TriggerState`),
so a `<TriggerDoubleClick>` around it — which is how this was first built — meant a *second* clone
per cell per render, on a grid that mounts one of these per visible cell and re-renders the whole
viewport on every frame of a marquee drag. The nested slot was a trap besides: an outer slot hands
its child the anchor `ref` and `data-state` as props, so the wrapper had to forward them or the
popover would never be positioned and no cell would be marked open.

Three consequences worth knowing before touching any of it:

- **The trigger is a `PopoverAnchor`, not a `PopoverTrigger`** (`triggerOpens` on
  `EditorSurface`, set from `CellClickBehaviour`, which is one type rather than four copies of
  two props precisely so the four cells cannot answer this differently). Radix then has nothing to
  toggle and the button's own `onClick` is free to be the selection. `data-state` is restored by
  hand on the anchor: it is the only thing that says *which cell* the open editor belongs to, and
  the grid's addressing contract is read through it.
- **All three lists answer a click the same way, and `FixturesTable`'s `cellSelection` is
  required.** Fixtures → List and Groups → List were the exception until this session: no
  `cellSelection`, so a click there opened the editor *and* selected the clicked **row** — which
  was the honest answer while they had no cell selection for the toolbar, `commitNow` or
  `batchCountFor` to read, and which made one component answer one gesture two ways depending on
  the route it was mounted under. They have the marquee, the keyboard, Set · Clear · Fan and the
  double click now, and `showOwnership` is back to meaning only what it says: draw provenance.
  `clickSelectsCell` went with the branch — `FixturesListContainer` is this table's one caller, so
  the flag was a constant — and the cells keep their own `clickSelects` for `CueValueGrid`, which
  has no selection and would otherwise lose every way into an editor.
- **What the two plain lists keep of their own is the *row* Fan** (`!showOwnership`), over the whole
  row selection with the column chosen in the panel. A row selection there is made by dragging the
  name column or by ⌘A, and spreading across eight whole heads without first drawing a rectangle over
  one of their columns is a gesture those views already offered; the programmer trades it away
  because there the marquee is what a selection is *for*. They also now mount `useClearCellFx`
  — a subscription of their own, which the programmer shares with its tables' counts — because
  ⌫ clearing the values while the FX driving them kept running is, on the rig,
  indistinguishable from the key having done nothing.
- **A click on a column the row resolves nothing for clears the selection** — a dimmer-only par's
  Colour cell. It is blank rather than an em-dash, and it is *not* background in the DOM (the row
  carries `data-row-id`, which the scroller's background handler stops at), so it carries
  `onEmptyCellClick` itself. That callback reaches `RowView` through a ref, because the container's
  answer is the cells-then-rows ladder and so changes identity on every marquee frame — passed
  straight down it would re-render every visible row at pointer rate.
- **Escape is a rung longer than it looks.** An open editor takes it first and the selection
  survives; a second Escape, with nothing open, clears as it always did. The question is *is an
  editor open* (`editorIsOpen()`, a DOM read of the surface's own attribute) and not *where was
  the key pressed*, which is what `isEditableTarget` and `closest('[role=dialog]')` answer — a
  different question, and one that answers wrongly the moment focus is not inside the panel.
  **The answer is snapshotted in the window's capture phase**, because Radix listens on the
  *document* and the grid's handler on the *window*: on the way up Radix closes the panel first, and
  a keydown is discrete so React has already flushed the unmount — asking in the bubble handler
  always answers "nothing open" and clears anyway. Capture on the window is the first thing any
  keydown in the document reaches.
- **Set closes the editor it opened**, the way Fan's own button always has — through a `close`
  one-shot mirroring `keyboardOpen` (`closeEditorCell` → `autoClose`). It needs one: a press on Set
  is **not** the outside click that dismisses a popover, because Set is that popover's own anchor.
  Verified at the desk rather than reasoned — pressing Set twice left the panel open with focus
  stranded on the button, which is what made Escape clear the selection. Which cell to shut is read
  from the `data-state` the cell's anchor carries, scoped `[data-cell]` so `SpreadPanel`'s own panel
  — a cell editor in every way but this one — does not read as one.

**The editor opens where the gesture was made.** Set is pressed at the toolbar, so its editor opens
at the Set button (`anchorRef` on `EditorSurface`, `editorAnchorRef` threaded container → table
→ row → cell); **Enter and a typed character are made at the selection, with the operator's eye on
the grid, so theirs opens beside the cell** — the cells express that by withholding the anchor
(`atButton ? editorAnchorRef : undefined`) rather than by a second prop on the surface. Anchoring
*everything* at the cell was the original defect: for a marquee near the bottom of a long list the
panel landed nowhere near the hand that pressed Set. Three rules in it:

- **The choice is latched at open**, in `useEditorOpen`, exactly as `keyboardSeed` is. The
  request is a one-shot the table drops on the very next commit, so a per-render read would
  re-anchor the panel from the button to the cell a frame after opening — visibly jumping across
  the screen.
- **The ref is read at render time and only while open**, because a `virtualRef` whose `current` is
  null sets Radix's anchor to null and the content is then never positioned at all. So no button
  means the cell, which is what a double click gets on any of the three lists.
- **The virtual anchor is rendered after the cell's**, which is what makes it win: it claims the
  anchor from an effect, and effects run after the refs of the same commit.

**An editor closes when the selection it was opened for goes away** (`selectionEmpty`, threaded to
`useEditorOpen`). An editor is open *for* a selection, so a Deselect used to leave one on screen still
writing — to something narrower than the batch its label line had just named. It is
**edge-triggered**, on the false→true crossing and not on the state, or a grid with no selection at
all could never open one: the close would land in the effect immediately after the click that
opened it, and the editor would flicker rather than fail in a way anyone could report. `undefined`
is a third state meaning "the question does not arise", which is what `CueValueGrid` passes.

**React bubbles synthetic events through portals**, and every cell editor is rendered from inside a
row — so the grid's marquee saw a drag on the dimmer slider as a press on the grid, and selected
cells under the operator while they were setting a value. `useCellMarquee`'s `onPointerDown` asks
the DOM (`e.currentTarget.contains(e.target)`), which is the same guard the scroller's background
`onClick` beside it already documented and this one was simply missing. Any new handler on the rows
wrapper needs it too.
