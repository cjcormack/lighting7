# One editor kit — Spread, the colour editor and the value editors across the programmer and the busk view

> **Document status: IN PROGRESS — sessions 1–3 shipped 2026-09-22 (lighting-react `b12887ab`, `a976489e`, `679bd1ae`; lighting7 `8fc8fcb`); session 4 open.** The visual design is settled and
> checked in beside this plan at [`editor-kit-design/`](editor-kit-design/INDEX.md) — five
> generated artboards: today's two answers side by side, the Spread panel in every host, the colour
> editor in five hosts, the value editors on one anatomy, and the model with every idea's verdict.
> The live copy at <https://claude.ai/artifact/UECPcKmhVPZ2FFhvxtTHMd> is a convenience, private to
> Chris; the checked-in files are the authority. This document is the engineering half: the model,
> the decisions and their reasons, and the session split. Where wording here and the artboards
> disagree, this plan wins on behaviour and the artboards win on layout and copy; **where an artboard
> and the shipped busk tab disagree on a measurement, the busk tab wins**, since it is the design
> authority these boards extend. Chris approved the boards as drawn on 2026-09-22; the seven calls
> they carried therefore stand as drawn (§11), each one line to flip.

## 1. Context

Between 2026-09-17 and 2026-09-22 the busk view gained a **Spread** tab (busk-further plan D9), a
**Colour** tab (D8) and a side sheet whose anatomy — 9px uppercase labels, 28px fields with the unit
inside, segmented rows, a read-out line, a static footer with the save first — the busk-chrome and
split-height work then settled. The programmer's equivalents predate all of it: `FanPopover` (the
sheet kit's, over `fanMath.ts`'s client lerp), `ColourPickerPopover` (over the `ColourPickerBody`
that session 5 extracted *for* the busk tab, without the tab's read-out, Pick, Recent or footer), and
four cell editors that each answer the count line, the label style, the field height and the unit
their own way.

Chris's brief on 2026-09-22: take the busk view as the state of the art and apply the same
improvements to the programmer — Spread where it says Fan, and one name; the better colour editor;
the other inconsistencies; every value editor on the better design; shared components across both
views; and other ideas. The boards are the answer to the design half; this is the plan.

What this plan is *not*: a change to any desk fact, any document, the selection or the marquee. The
wire gains one optional request field and one response field changes meaning, both on
`POST /programmer/spread`; everything else is the client folding two implementations of each editor
into one module that the programmer's sheets and the busk view's sheet both import.

## 2. Decisions taken

- **D1 — One name: Spread.** The verb on row C, its `aria-label`, the panel (`SpreadPanel`), the
  programmer's adapter (`SpreadPopover`), the plan kinds (`SpreadPlan`, `SpreadColumn`,
  `spreadColumnsForTargets`), the module (`spreadPlans.ts`), the `CellSelectionActions` slot
  (`spread`) and CLAUDE.md all say Spread. "Fan" survives only in the desk survey as what other desks
  call it. The desk already calls the route spread, a Look press "spreads" its rows, and nothing on
  either side called anything else fan but this one verb.
- **D2 — One panel, four plan kinds, every host.** `components/editor/SpreadPanel.tsx` is the busk
  tab's body plus `FanPopover`'s surface, chooser and keyboard, and it is hosted by the programmer's
  row C as a popover in the cell editor's three forms, by the busk view as the docked tab, and by the
  patch list, cue sheet and DMX sheet with their own plans. The kinds: `intent` (desk-resolved — the
  template vocabulary: dimmer, strobe, rgbColour, white, amber, uv, position, zoom, focus, iris,
  frost), `raw` (bytes on a column outside that vocabulary — Speed today), `address` (From · Step in
  visible order, the patch list's walk) and `duration` (From · To with the curve row, the cue
  sheet's). `intent` and `raw` draw Curve · Order · Parts · Over; `address` draws From · Step and its
  landing lines; `duration` draws From · To and the curve row, which is where a second spread than
  Linear was always going to go.
- **D3 — The desk resolves, on both sides.** Every `intent` spread goes through
  `POST /programmer/spread` — the programmer's rows are heads the desk knows better than the browser
  does (a group's member order, each head's range, which cells a fixture has, what a colour means on
  a head with amber: `templateIntent.ts`'s reason). `fanValues` and `fanColours` are deleted. The
  ends are intents — a percent, a colour + policy or a `tmpl:` reference, degrees — so a spread from
  0 to full is *0% → 100%* on every head whatever its range, which the byte form never was. The
  position exclusion goes with the byte lerp it objected to. The client walks only what the desk has
  no grammar for: addresses, fade times, and `raw` bytes.
- **D4 — The marquee answers the panel's first two questions.** Targets are the marquee's heads: a
  group row expanded to its **visible** members (the filter rule every group-row action keeps), an
  element row as `{type: 'fixture', key: element.key}` (the cells contract the desk already takes —
  `rowLocateTarget` publishes it and `BuskRigOrder` sorts it), a fixture row as itself. A new
  `spreadTargetsFor` in `rowModel.ts` says so, beside `templateTargetsFor`, which it must **not**
  reuse: that one folds an element row into its fixture because the template route resolves keys
  against the patch, and a spread over a bar's cells has to keep the cells. `families` is the pair
  the press sends (`usePressFamilies`). The family segment is drawn **answered and checked, never
  hidden** — a row that appears only in some hosts is how two hosts drift — and live with both
  families offered when the marquee spans two (Dimmer + Colour), which is the chooser Fan drew for
  the same case. The Property row appears wherever the family holds more than one property, from the
  column: a Colour marquee offers Colour · White · Amber · UV, a Dimmer marquee Level · Strobe, a
  Zoom marquee Zoom alone.
- **D5 — Over: Heads is the default on both sides.** Fan expanded a multi-head fixture into its
  cells always (`planBatchWrites`); Spread treats a fixture row as one head unless the switch says
  Cells, which is offered with the count when a selected fixture has elements and disabled with the
  reason otherwise — the busk tab's rule, said once. An element row selected on its own is a cell by
  construction. *(Call 5, taken as drawn.)*
- **D6 — The focused-Look-layer arm is one flag on the route.** `SpreadRequest.write: Boolean =
  true`. False resolves exactly as Local does and **answers without writing**; the client lands
  each `written[].value` in the layer's draft through `LookRowStore.setValue`, which coalesces and
  PUTs as every layer-scope edit does (400 ms, 2 s ceiling — flush cadence is stage cadence). For
  that, `written[].value` becomes the head's **literal** in the Look row grammar
  (`PropertyValue.serialize()`: `"0".."255"`, `"#rrggbb;w128"`, `"pan,tilt"`) rather than the
  interpolated intent it carries today — nothing on this side reads the field yet, so the change is
  free and the layer arm is its first reader. The client still never lerps. Output scope and a
  focused template layer refuse as they do today, with the same words. The desk does **not** write
  into the Look itself: `PUT /looks/{id}` republishes every cue layering it, and the draft's
  coalescing is what makes a drag survivable there.
- **D7 — Live on the programmer too.** Off by default, through the same `useLivePush` with an
  equality over the whole request; Apply always sends and reads *Send again* while Live is on; the
  release is read from the window. A cell editor already writes as it goes, so the two surfaces
  agree: a value judged by eye lands as the hand moves.
- **D8 — The count line goes; the label line and the read-out take its place.** No editor says
  *Applying to N targets* any more. Every popover draws a **label line** — heads · scope on the left
  (*4 heads · Local*, or the Look's name in layer scope, which no editor says today), the column on
  the right — and a **read-out** under its controls that says what the desk holds and what was
  skipped (*2 heads have no gobo · skipped*; *Emitters on 6 of 14 heads · the rest take RGB only*).
  The busk tab draws neither line at the top: the band carries the count. The sheets keep their
  title row; the label line is the popover's.
- **D9 — The anatomy is the busk tab's, exported once.** `components/editor/` holds `EditorSurface`
  (`CellEditorSurface` moved: the three forms, the double click, `wide`, the anchor), `EditorLabel`
  (`BuskLabel` moved and renamed — 9px, 700, uppercase, 0.08em, muted, a `<div>`), `EditorField`
  (one 28px field with the unit as a trailing muted glyph and the draft rule built in — the three
  fields it replaces are `ValueFieldRow`'s `Input`, `SpreadSheet`'s `NumberField` and
  `ChannelNumberInput`), `EditorReadout` (the 10px line; `LandingLines` becomes its multi-line arm),
  `EditorFooter` (save slot · note · spacer · verbs), `ValueFieldRow`, `useEditorKeyboard`,
  `useEditorOpen`, `useLivePush`. Segmented rows (`ToggleGroup`) for any choice of four or fewer; any
  row holding a picker or a slider knob padded to the knob's half-width (`px-3.5`). A host that wants
  to differ says so at its import.
- **D10 — The colour editor is one body, one read-out, one footer.** `ColourPickerBody` becomes
  `components/editor/ColourEditor.tsx` and takes the busk tab's read-out line (emitter counts per
  head from `emitterHeadCounts` · *mixed* · swatch · hex), **Pick**, **Recent**, and the footer with
  **Save as template…** and **Spread…**; `recent`, `footer`, `counts` and the label line are props
  the host fills. Hosts: the programmer's colour cell (a 352px popover with a fluid picker, and both
  sheets), the busk tab, the Spread panel's colour endpoint (the picker and R/G/B alone — a colour
  intent has no emitter component), and the two property visualisers (picker only, unchanged). The
  guidance paragraph is the picker's title everywhere, as the compact layout already made it. The
  keyboard is unchanged: R focused on open in the popover, comma R → G → B → W → A → UV, Enter
  closes, a typed character seeds R.
- **D11 — Drawn once on a screen.** Recent chips are on row C's strip on the programmer and in the
  sheet on the busk view; the same screen never shows them twice. So the desk popover draws no
  Recent (*call 2*), the phone bottom sheet does (row C's strip is folded away below 600px), and the
  busk tab does. The programmer's Spread footer carries no *Save as Look…* — Record is one row up
  and is how every programmer state is kept (*call 1*); the busk tab has no Record and keeps its
  save. `EditorFooter`'s `save` slot is what makes that one component.
- **D12 — Pick on the programmer reads the marquee's heads.** `ColourEditor` mounts one hidden
  `FixtureAppearanceSource` leaf per target it is editing, reporting into `lib/liveAppearance.ts`
  exactly as the busk tab does, so Pick answers whatever the grid is showing — the grid's rows do
  **not** report into that store today and are not made to. In the programmer the editor already
  opens at the first cell's value (the placeholder rule); Pick is how a batch is re-read after a drag
  moved only some of it.
- **D13 — The unit is the cell's.** The programmer's dimmer cell reads *80%*, so its editor's field
  is a percent and the byte is the read-out (*204 of 255 · 0–255 on every head*); the DMX sheet
  reads *204*, so its field stays the byte. Strobe, zoom, focus and iris take the percent for the
  same reason — a template of them is a percent already. One `EditorField`; the host names the unit.
  *(Call 3, taken as drawn.)*
- **D14 — Position gets the pad and the degrees.** The position editor draws a 120px XY pad beside
  Pan · Tilt (the cell's 16px thumbnail at a size a finger can use; a drag on it writes both) and
  types **degrees** where the head annotates a range (`degMin` / `degMax` on the pan and tilt
  descriptors — the movers carry them, 10 of the 28 fixture models today), bytes on the read-out — the Spread endpoints' unit, so a
  spread and a set type the same thing. A head with no annotation keeps bytes in the field. The cell
  keeps its byte read-out. *(Call 7, taken as drawn.)*
- **D15 — The Speed column keeps a client byte lerp.** It is outside the template vocabulary, so the
  desk cannot spread it; the `raw` kind keeps `fanValues`'s arithmetic for it alone, under the same
  panel. *(Call 4, taken as drawn; if it is ever dropped, `raw` goes with it and Speed simply stops
  offering Spread.)*
- **D16 — One live-write discipline.** `useSheet`'s ~30 Hz commit throttle and the busk tabs'
  `useLivePush` are two copies of one rule (a floor, a dedupe, a release that always lands); the
  sheet's commit takes the hook with `floorMs: 33` so its cadence does not move.
- **D17 — Widths by content, not one number.** 288 for level, position, text and address (up from
  256 — the endpoint editors' two fields plus a gutter); 256 for the setting list; 352 for colour;
  336 for Spread on the programmer, 320 docked (the sheet's floor). The sheets are as today. The
  boards' numbers are the design's; re-measure in the app and record the app's.

## 3. The model

### 3.1 Facts — nothing new

No desk fact, no per-window fact, no document. Live on the programmer's Spread is component state,
as it is on the busk tab; the panel's form is component state and is dropped on close. The busk
view's `busk.sheet` and the programmer's scope are untouched.

### 3.2 The wire — one route

`POST /projects/{id}/programmer/spread`:

- `SpreadRequest` gains `write: Boolean = true`. `false` runs `spreadIntoProgrammer` to the point of
  the batched `writeProperties` and skips it; everything before — the mask, the target expansion,
  the order, the fractions, the per-head resolve, the skips — is identical. It is a request field
  with a default, so the busk tab's requests are unchanged byte for byte.
- `SpreadWriteDto.value` becomes `resolution.value.serialize()` — the literal, in the grammar Look
  rows and programmer entries already use — instead of `intent.serialize()`. `ProgrammerSpreadRouteTest`
  pins both: a `write: false` request writes nothing and answers every head; a colour spread's
  `written[].value` parses with the client's `parseProgrammerValue`.
- No new frame, no schema. `ProgrammerSpreadRouteTest.kt` is the one backend test that moves.

### 3.3 Where the code goes

| Becomes | From | Note |
| --- | --- | --- |
| `components/editor/EditorSurface.tsx` | `sheet/cells/CellEditorSurface.tsx` | moved; `useEditorForm`, `useEditorCramped`, `editorIsOpen`; the `data-cell-editor-surface` attribute and the two media constants keep their spellings (`shortViewport.test.ts` pins them) |
| `components/editor/EditorLabel.tsx` | `busking/BuskLabel.tsx` | moved and renamed; the busk view imports it back |
| `components/editor/EditorField.tsx` | `ValueFieldRow`'s `Input` · `SpreadSheet`'s `NumberField` · `fixtures/ChannelNumberInput.tsx` | one 28px field, the unit glyph, `useNumberFieldDraft` inside; the caller still clamps |
| `components/editor/EditorReadout.tsx` · `EditorFooter.tsx` | `ColourSheet`'s read-out and footer · `SpreadSheet`'s footer · `sheet/cells/LandingLines.tsx` | new, from the busk tabs |
| `components/editor/ValueFieldRow.tsx` · `UnsetCellMark.tsx` | `sheet/cells/…` | moved; `ValueFieldRow` takes `EditorField` |
| `components/editor/useEditorKeyboard.ts` · `useEditorOpen.ts` | `sheet/cells/useCellEditorKeyboard.ts` · `useCellEditorOpen.ts` | moved; unchanged |
| `components/editor/useLivePush.ts` | `hooks/useLivePush.ts` | moved beside its callers; `useSheet` takes it (D16) |
| `components/editor/ColourEditor.tsx` | `fixtures/ColourPickerBody.tsx` + `ColourSheet`'s read-out, Pick, Recent, footer, `emitterHeadCounts`, the hidden leaves | one body (D10, D12) |
| `components/editor/SpreadPanel.tsx` · `spreadPlans.ts` | `busking/SpreadSheet.tsx` (the body) + `sheet/FanPopover.tsx` (surface, chooser, keyboard) · `sheet/fanMath.ts` | one panel; `walkAddresses`, `spreadDurations`, `rawValues`; `fanColours` deleted |
| `fixtures-list/SpreadPopover.tsx` | `fixtures-list/FanPopover.tsx` | renamed; builds `intent` and `raw` plans from the marquee, the target rule (D4), the scope arm (D6) |
| `fixtures-list/rowModel.ts` | — | `spreadTargetsFor`, beside `templateTargetsFor`; a column → `TemplateProperty` map for D4's Property row |
| `fixtures-list/cells/SliderCell.tsx` · `PositionCell.tsx` · `SettingCell.tsx` · `ColourCell.tsx` | — | the anatomy (D8, D13, D14); `ColourCell` hosts `ColourEditor` through a thinned `ColourPickerPopover` |
| `sheet/cells/LevelCell.tsx` · `OptionCell.tsx` · `TextCell.tsx` · `AddressCell.tsx` | — | the anatomy; bytes on `LevelCell` |
| `patches/PatchSheet.tsx` · `runner/CueSheet.tsx` · `channels/DmxSheet.tsx` | their `FanPopover` instances | `SpreadPanel` with `address` · `duration` · `raw` plans; no behaviour change |
| `busking/SpreadSheet.tsx` · `ColourSheet.tsx` | — | thin hosts: the selection → targets, the seed hand-over, the docked frame; the leaves move into the editor |
| `sheet/CellSelectionActions.tsx` · `SelectionBar.tsx` · `fixtures-list/SelectionToolbar.tsx` | — | the `spread` slot; the verb reads Spread with the wave glyph (`Waves`, the busk tab's), never the fan glyph |
| `index.css` | — | the six-mount-site picker rules reduce to the fluid rule; the 200px `!important` pin goes with the last fixed host |

## 4. UX — what the design draws

`Survey`: today — Fan on a Colour and a Dimmer marquee against the busk Spread tab, nine
differences; the colour popover against the Colour tab, seven; the three programmer value editors
against the busk endpoint fields; the six rules. `Spread`: row C reading Spread with the panel open
over a 4 × Colour marquee at 336; the docked tab; the Look-layer arm with its footer note; a Position
marquee with Live on; the address and duration kinds; the request and response. `Colour`: the editor
open from the colour cell at 352; the docked tab with Recent; the phone bottom sheet with Recent;
the short-viewport side sheet, compact; the Spread endpoint picker; the pieces-by-host table.
`Editors`: the anatomy's five pieces; Level (%), Level (DMX, bytes), Position with the pad, Setting,
Text, Address. `Model`: the moves, the rename, the wire, ten ideas with verdicts, the calls, the
sessions.

## 5. Implementation — three sessions, and a fourth if called

Each session is one commit per repository, gated by `npm run check`, with the done-marker here.
Sessions 1 and 2 are lighting-react only; session 3 is lighting7 first, then lighting-react.

### ~~Session 1 — the kit (lighting-react) — Fable 5.1, high~~ — lighting-react b12887ab

- Create `components/editor/` and move: `CellEditorSurface` → `EditorSurface` (with
  `useEditorForm`, `useEditorCramped`, `editorIsOpen`; `HandChip` and `useEscapeEditorSnapshot`
  follow the rename, the DOM attribute does not), `useCellEditorKeyboard` → `useEditorKeyboard`,
  `useCellEditorOpen` → `useEditorOpen`, `ValueFieldRow`, `UnsetCellMark`, `BuskLabel` →
  `EditorLabel`, `hooks/useLivePush` → `editor/useLivePush`. Every import site follows; no
  behaviour change in this step.
- `EditorField` (D9) from the three fields it replaces; `ChannelNumberInput` becomes a thin
  wrapper or goes, whichever its two callers allow. `EditorReadout` with a `lines` arm that
  `LandingLines`' two callers take. `EditorFooter` with `save`, `note` and `children`.
- `useSheet`'s commit throttle → `useLivePush({ floorMs: 33 })` (D16); `useSheet.test` pins the
  cadence unchanged.
- The seven editors take the anatomy (D8): the label line (heads · scope | column) in the popover
  form only, `EditorLabel` over each control, `EditorField`, the read-out with the skip count,
  `EditorFooter` where Apply exists (`TextCell`, `AddressCell`). *Applying to N targets* is gone from
  all of them. `SliderCell`'s field in the cell's unit with the byte read-out (D13); `LevelCell`
  stays bytes. `PositionCell`'s XY pad and degree fields (D14), reading `degMin` / `degMax` off the
  resolution's descriptors and falling back to bytes where a head has none. Widths per D17,
  re-measured in the app.
- Tests: the moved suites keep their assertions under the new names; `EditorField.test.tsx` (the
  draft rule, the unit, the clamp is the caller's); `EditorReadout.test.tsx` (the lines arm renders
  `LandingLines`' cases); `SliderCell.test.tsx` (percent ↔ byte round trip at each end and at 50%);
  `PositionCell.test.tsx` (degrees from the annotation, bytes without it, the pad writes both);
  `useSheet.test.ts` (the throttle's cadence and trailing call are unchanged through the hook);
  `shortViewport.test.ts` (the moved constants' spellings still match); `HandChip` and
  `useEscapeEditorSnapshot` tests (the Escape ladder still asks `editorIsOpen`).
- Docs: CLAUDE.md §Sheet kit, §The cell editor's three forms, §The programmer's keyboard, §The
  busk layout / §Focus and the side sheet (`BuskLabel` → `EditorLabel`), §Speed Masters
  (`useLivePush`'s home).
- Done-marker here.

### ~~Session 2 — the colour editor (lighting-react) — Fable 5.1, high~~ — lighting-react a976489e

- `ColourEditor` (D10) from `ColourPickerBody` plus the tab's read-out line, Pick, Recent and
  footer; `emitterHeadCounts` and the hidden appearance leaves move in with it (D12), keyed on the
  editor's `targets`. Props: `targets`, `recent?`, `footer?`, `counts?`, `labelLine?`, `onPick`,
  `onSave`, `onSpread?` (absent draws the button inert, as `ColourSheet` already does for a host with
  no Spread tab — session 3 wires it). The picker is fluid in every host; the `.colour-picker-*`
  rules in `index.css` reduce to the fluid rule and the 200px pin goes.
- `ColourPickerPopover` keeps the open state, the keyboard and the two surfaces, and mounts
  `ColourEditor` at 352 (`contentClassName`), `wide` on the side sheet as today.
- `ColourCell`: Pick and Save as template… (`NewTemplateFromSelectionSheet`, `families:
  ['COLOUR']`, targets from the marquee — the strip's `templateTargets`); Recent only in the bottom
  sheet form (D11), from `recentTemplates` over the colour-family, generic, value templates the
  marquee's heads can take, a tap a press through `useTemplatePress`.
- `ColourSheet` becomes the docked host: the selection → write targets planning (`planColourWrites`
  stays here — it is about the busk selection, not the editor), the seed, the docked frame;
  everything else is the editor's. The two property visualisers pass `footer: false`, `counts:
  false`, no label line.
- Tests: `ColourEditor.test.tsx` (the pieces-by-host table on `Colour.dc.html`, one case per row;
  Pick reads the store and says *mixed*; the leaves mount per target; the guidance is the title;
  the keyboard sequence); `ColourCell.test.tsx` (Recent only in the sheet form; Save opens the
  sheet with the family answered; Spread… inert until wired); `ColourSheet.test.tsx` shrinks to
  the host's own (targets, seed, the footer's verbs reach the editor); `ColourPickerPopover.test.tsx`
  (the visualisers' picker-only form unchanged).
- Docs: CLAUDE.md §Focus and the side sheet (the Colour tab paragraph), §The cell editor's three
  forms (`wide`), the "five mounting readers of `fixtureAppearance`" count (the tab's leaves are the
  editor's now, so the count does not change but the sentence does).
- Done-marker here.

### ~~Session 3 — Spread (lighting7, then lighting-react) — Fable 5.1, high~~ — lighting7 8fc8fcb · lighting-react 679bd1ae

- **lighting7 first** (§3.2): `write` on `SpreadRequest`; `written[].value` as the literal;
  `ProgrammerSpreadRouteTest` gains the two cases. The route's docblock records both. Hot-reload
  suffices for the handler body; a new request field is a `@Serializable` class change — check
  whether the running desk needs a restart before the client half is checked against it.
- `SpreadPanel` (D2) from `SpreadSheet`'s body and `FanPopover`'s surface, chooser and keyboard, on
  the kit's pieces; `spreadPlans.ts` from `fanMath.ts` with `walkAddresses`, `spreadDurations` and
  `rawValues` (`fanValues` renamed and kept for D15), `fanColours` deleted. The family segment
  answered and checked (D4); the Property row; Curve · Order · Parts · Over on `intent` and `raw`;
  Live and Apply / Send again (D7); `EditorFooter` with the busk host's `save`; the keyboard on
  both hosts (Enter applies, comma From → To, the first field focused in the popover only).
- `SpreadPopover` (D4, D6): `spreadTargetsFor` and the column → property map in `rowModel.ts`;
  `usePressFamilies`; the scope arm — Local sends `write: true`, a focused Look layer sends
  `write: false` and lands `written[]` through `LookRowStore.setValue`, Output and a focused
  template layer refuse with today's words; `useSpreadMutation` reused; `skippedFamilies` toasted
  in `skippedRowsMessage`'s vocabulary, keyed. Over defaults to Heads (D5). Speed builds a `raw`
  plan (D15); Gobo and Prism build none, as today.
- `CellSelectionActions`'s `spread` slot; the verb reads Spread with the wave glyph on every sheet
  (D1). `PatchSheet`, `CueSheet` and `DmxSheet` take `SpreadPanel` with `address`, `duration` and
  `raw` plans; the cue sheet's duration kind takes the curve row in place of its one-option select.
- `ColourEditor`'s Spread… wired on the programmer: a one-shot request through the container, the
  shape `keyboardOpen` uses, opening the row C popover with From seeded (the busk `SpreadSeed`).
- `SpreadSheet` becomes the docked host: targets from the busk selection, the seed hand-over, the
  frame; the panel is the editor's.
- Tests: `SpreadPanel.test.tsx` from `SpreadSheet.test.tsx` and `FanPopover.test.tsx` (the family
  segment is drawn in every host; the Property row appears only with more than one property; Live
  dedupes and the release lands; Send again while Live; the four kinds draw their rows);
  `spreadIntent.test.ts`'s import-list assertion extended to `SpreadPanel` and `SpreadPopover`
  (neither reaches a lerp but `rawValues`, and only from the `raw` arm); `SpreadPopover.test.tsx`
  (a group row lands on its visible members; an element row lands as a cell; a Look layer sends
  `write: false` and lands the answered literals through `setValue`, in the layer's grammar; Output
  refuses; Speed is `raw`; Over defaults to Heads and offers Cells with the count);
  `CellSelectionActions.test.tsx` (the verb and its label); `PatchSheet.test.tsx`,
  `CueSheet.test.tsx`, `DmxSheet.test.tsx` (behaviour unchanged through the panel);
  `ColourCell.test.tsx` (Spread… opens the popover seeded).
- The rename sweep: every `Fan` in `src/` and in CLAUDE.md (§Sheet kit, §The cell editor's three
  forms, §One selection, two shapes, §List shell), `docs/` where it names the verb; the desk survey
  sentence in `completed/busk-further-design/Spread.dc.html` stays as the other desks' word.
- Docs: CLAUDE.md gains the paragraph this plan's D3–D6 are, in §Sheet kit; §Focus and the side
  sheet's Spread paragraph becomes the host's.
- Done-marker here.

### Session 4 — Colour · Spread as tabs on the programmer rail (lighting-react) — if called

Open (§11). `ProgrammerRail` gains a tab strip beside its `LAYERS n · FX n` header and two docked
hosts of `ColourEditor` and `SpreadPanel` over the marquee, the busk sheet's shape; the popover
stays the quick form. Not drawn on the boards; a board first if it is called.

## 6. Migration

None. No stored state changes shape; a desk mid-upgrade serving the old spread route answers a
`write: false` request by writing (the field is unknown to it — the REST `json()` in `routes/router.kt` is Ktor's `DefaultJson`, which
sets no `ignoreUnknownKeys`, so **an unknown request key is a 400**; the client must not send
`write` at all when it is `true`, and session 3's request builder omits it), and the layer arm is the only reader of the literal `value`, which it can detect by
parsing: an intent string does not parse as a programmer value, and the arm then refuses with a
toast naming the desk version rather than landing an intent in a Look row.

## 7. Explicitly out of scope

- A Beam tab on the busk sheet, or the setting editor on the busk view — the busk view has no gobo
  or prism control by design.
- A "spread again" or recent-spreads row — the desk keeps no spread state; Send again resends the
  form, and a spread worth keeping is a Look.
- Pick in the level and position editors — they open at the live value already.
- Stage L→R as a spread order — the desk has no stage order; the footnote stays a footnote.
- Any change to the marquee, the scope band, the cells' triggers or their placeholder rule, the busk
  selection, the rig band, the Speed and Show tabs, or any route but the spread route.

## 8. Follow-ups to record

- `FU-EDITOR-RAIL-TABS` — session 4, if called.
- `FU-SPREAD-RAW-SPEED` — the `raw` kind exists for one column; if Speed ever gains a template
  property or is dropped from Spread, `rawValues` goes with it.
- `FU-SPREAD-DURATION-CURVES` — the cue sheet's duration kind draws the four curves client-side
  from session 3; if the cue sheet's fade spread ever moves to the desk, it is an `intent` in all
  but name.

## 9. Verification

Beyond the unit suites, at the desk after each session:

- **1.** On `/programmer`: drag four dimmer cells, Set, type `80`, Enter — the field reads a
  percent, the read-out the byte, the rig lands at 204. Open a position editor on a mover and drag
  the pad; the fields read degrees and the head follows. Open a gobo editor over a par and a spot:
  the read-out says one head has no gobo. On the DMX sheet the level editor still reads bytes. No
  editor says *Applying to*. On a landscape phone the side-sheet forms fit as before.
- **2.** Open the colour cell's editor: the square is fluid at 352; the read-out counts the
  emitters; Pick after a drag re-reads the marquee and says *mixed* where heads disagree; Save as
  template… opens the sheet with Colour answered. On the phone, Recent is in the sheet; on the desk
  it is not. On `/busk` the Colour tab is unchanged to the eye, and its Pick still answers in Pads.
- **3.** On `/programmer`: row C reads Spread. Over a 4 × Colour marquee the panel opens at the
  button with the family checked; From amber, To blue, Apply — the heads step through Lab; Live on,
  drag To — the rig follows and the release lands; a Position marquee spreads in degrees; focus a
  Look layer and spread its dimmers — the draft shows the literals and the PUT follows; Output
  refuses with its sentence. On `/busk` the Spread tab is unchanged to the eye and comma steps From
  → To. On the patch list and the cue sheet, Spread does what Fan did. Grep the tree for `Fan` and
  find only the survey.

## 10. Scope honesty

The boards' widths and the two board rules about `react-colorful` (fluid everywhere, the pin gone)
are the design's; session 2 must check every remaining mount site of the picker against
`index.css`'s `!important` rules and record the app's numbers. `Colour.dc.html`'s note that "the
grid's rows report into the store as the rig tiles do" was wrong and D12 corrects it: the editor
mounts the leaves. The degree fields (D14) depend on the pan and tilt descriptors carrying `degMin`
/ `degMax` on this side, which `store/fixtures.ts` declares optional and 10 of the 28 fixture
models set (the movers; `Spread.dc.html`'s "every fixture annotates" overstated it); session 1
keeps bytes where a head is silent, and a marquee mixing annotated and silent heads keeps bytes
for all of them rather than two units in one editor. `useSheet`'s cadence (D16) is kept at 33 ms by
argument rather than because 50 was tried; if a slider drag reads coarser after session 1, the
floor is the one number to move. Session 3's `write` field must be **omitted** when true (§6), which
the boards did not say.

Session 1 left four things for sessions 2 and 3 to build on, each recorded in CLAUDE.md §The
editor kit rather than here: `EditorLabelLine` is the label line as a fourth piece, popover-only;
`EditorField` has an `onDraft` for a host that writes on Apply and must know the box is empty;
`useSheet` runs the hook with the dedupe **off**, because a sheet cannot see the routes that move
a value under it; and the programmer's cells take a per-column `CellBatch` rather than a count,
which is what the Spread panel's popover host will read its targets' resolutions from. The seven
editors' control labels are the column's name (the board drew *Level*) and the position read-out
states both degree bounds — copy the boards' Colour and Spread panels should not inherit unread.

## 11. Open questions

Chris approved the boards as drawn on 2026-09-22, so each of the seven calls stands as drawn and is
recorded here as one line to flip:

- **1 · Save as Look… in the programmer's Spread footer** — drawn without (D11); one `save` prop.
- **2 · Recent chips in the desk colour popover** — drawn without, the phone sheet with (D11).
- **3 · Percent in the programmer's level editor** — drawn as % (D13); the DMX sheet keeps bytes.
- **4 · The Speed column** — drawn as `raw` (D15).
- **5 · Over: Heads as the programmer's default** — drawn as Heads (D5).
- **6 · Colour · Spread as tabs on the programmer rail** — *genuinely open*: not drawn; session 4
  waits on it.
- **7 · Degrees in the position cell editor** — drawn in degrees (D14).
