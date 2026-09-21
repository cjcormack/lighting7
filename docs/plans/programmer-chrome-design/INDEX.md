# Programmer chrome — design reference

Source: a Claude Design canvas authored 2026-09-14, generated from `lighting-react`'s real values —
the oklch tokens from `index.css`, the `button.tsx` sizes, and the row components under
`src/components/programmer/`. **Read them as the intended visual output, not as structure to
copy**: they are static HTML mockups with no React and no state.

This is a **tidy-up, not a redesign**, and it has no plan document of its own. Every row keeps its
job, its controls and its arms; what changes is the spacing system, plus two phone-arm folds that
fix genuine overflows. The system in one paragraph: a 12px gutter on every row (the `ShowHeader`
included); every chrome row 40px holding 32px controls, so the inset is 4px everywhere; three
control tiers by nesting — 32 for a control on a row, 28 for a control inside a control (Update and
Revert in the source box, a template chip, `New`), 24 for a toggle item, 20 for a pill; the header
48px at every height (**revised 2026-09-21** by the busk-chrome plan's D12, session B: the
`ShowHeader` is a 40px chrome row like the rest, `CHROME_ROW_CLASS`'s `h-10 px-3` — the boards below still draw 48); 8px
between controls on a row and 6px inside a control; row B's filter a
field from 360px of row up, taking the row's slack before the spacer does; the key not drawn in
layer scope; and below 600px of row the source box folding to `Q4 · Update · Revert` with the
change count as an amber dot on Update, and the fade trigger keeping its value and losing its
chevron. The measured today → proposed table is the `Spec` artboard. The implementation is the
commit that cites this directory; `lighting-react`'s `CLAUDE.md` §"The programmer's scoped grid"
states the system as facts.

The live, pannable version is at
<https://claude.ai/code/artifact/ea669ee2-7dd1-4931-9c61-690ff18831c5> — private to Chris, so
**treat these files as the authority** and the URL as a convenience.

## Format

Each `*.dc.html` is one artboard: a single fixed-size `<div class="app">` with its width and height
in an inline style. Ignore the `<script src="./support.js">` line and the `<x-dc>` / `<helmet>`
wrappers — canvas scaffolding. The shared chrome is classed in the `<helmet><style>` block; the
rest is inline. Open a file in a browser and it renders standalone.

**The artboards are generated.** [`gen.mjs`](gen.mjs) is the source: the `SYS` block at its top is
the proposed system as numbers, the builders below it draw every row from those numbers, and
`node gen.mjs` in this directory rewrites all twelve `.dc.html` files and `canvas.json`. Change a
number there rather than in an artboard, or they drift. `TODAY`'s figures are quoted only in the
`Spec` table.

[`canvas.json`](canvas.json) is the layout manifest, and carries a sticky note per page — each the
shortest statement of that page's brief. It has **two pages**: *Chrome* (the spacing tidy-up) and
*Templates* (the recents row and the picker). They share a directory because they are the same
rows drawn to the same system: the templates work is what row C does with the width the chrome
work gave it.

Every artboard is **dark-only on purpose** — these surfaces are read at a desk in a blacked-out
room. Each device artboard stacks the **four operating modes** top to bottom — nothing loaded,
busking, editing a cue with unwritten changes, a Look layer focused — because the chrome's rows
change shape with the mode, and an empty programmer is the state that makes any layout look
spacious.

## The artboards

| File | Frame | Read it for |
|---|---|---|
| [`Spec.dc.html`](Spec.dc.html) | 1180×1400 | The system stated, and the measured **today → proposed** table: gutter, header height, each row's height and control tiers, the gap, the filter threshold, the right edges, the two phone-arm folds, and the chrome above the first fixture row (256 → 250 on a desktop). Below it, the rhythm at a 1000px viewport with the gutter, gap and inset marked. |
| [`Main.dc.html`](Main.dc.html) | 1440×900 | The whole answer at desktop width, all four modes: the 48px header on the 12px gutter, rows A, B and C at 40 with 32px controls, the filter as a field taking the slack, the 28px chips on row C, and the rail header level with row B. |
| [`TabletLandscape.dc.html`](TabletLandscape.dc.html) | 1180×820 | The rail collapsed to its strip, whose chevron is a 40px cell level with row B; the field still on row B. |
| [`TabletPortrait.dc.html`](TabletPortrait.dc.html) | 820×1180 | The width where the old `@[800px]` threshold left row B's middle a hole — here the field is on the row, since the row is over 360. |
| [`Phone.dc.html`](Phone.dc.html) | 393×852 | The two folds: with a cue included, row A is `Q4 · Update · Revert` with the amber dot, beside a 48px fade trigger with no chevron; in layer scope, row B is the pill capped at 120 with the save dot, and no key. |
| [`PhoneLandscape.dc.html`](PhoneLandscape.dc.html) | 852×393 | The short-height fold: rows A and B on one 40px line, the 48px header, and the same two phone-arm folds inside the folded row. |

## The Templates page

A second piece of work on the same rows, authored 2026-09-14: **recents on row C, and a picker for
the rest of the library**. The problem is that `TemplateStrip` was a sideways scroller of *every*
offerable template, sized for six, and a real show has sixty — on an iPad portrait the row had room
for one chip and no way to reach past it.

The answer in one paragraph: row C keeps the templates most recently **pressed** for the selected
family, most recent first, up to eight, then an `All · n` button and `New`. Recency is a **desk**
fact, stamped server-side on every press from any surface — the chip's click, ⌥click / hold, a busk
pad, a MIDI `pressTemplate` — so every client and the hardware agree; a toggle *off* is not a press.
`All · n` opens a searchable pad grid, sections Recent · All A–Z · Per fixture · Effects, built from
the busk page's own pad face and pressed with the same two gestures; a press does **not** close it,
because auditioning three colours in a row is the normal case. It takes the cell editor's three
forms from the cell editor's own two queries (`max-width: 639px`, `max-height: 500px`). To buy the
row the width, the fixture count and Locate / Highlight fold below 800px of row C, and below 600px
the chip scroller is not drawn at all — the phone reaches the library through `All · n` alone.

Each Templates artboard draws the same colour selection **twice**: the row closed, then the picker
open over it. The library drawn is 84 templates of which 71 fit the selection.

| File | Frame | Read it for |
|---|---|---|
| [`TemplatesSpec.dc.html`](TemplatesSpec.dc.html) | 760×900 | The rules stated: what the row keeps, what it sheds and at which width, the pad grid's sections and `repeat(auto-fill, minmax(110px, 1fr))` track, the two gestures, the three forms, and what is reused rather than built. |
| [`TemplatesDesktop.dc.html`](TemplatesDesktop.dc.html) | 1440×900 | The whole answer at desktop width: the recents on row C with `All · n` and `New`, then the 640×540 popover under `All` — search, the `Colour · for 4 cells` scope line, five pad columns, and the footer's gesture hint, count and *New from selection*. |
| [`TemplatesTabletLandscape.dc.html`](TemplatesTabletLandscape.dc.html) | 1180×820 | The same 640-wide popover with the rail collapsed — the width is the *panel's*, not the column's, down to 900px of viewport. |
| [`TemplatesTabletPortrait.dc.html`](TemplatesTabletPortrait.dc.html) | 820×1180 | The 800px fold doing its job: the fixture count and Locate / Highlight gone, two recent chips where there would otherwise be none, and the narrower 560×520 popover. |
| [`TemplatesPhone.dc.html`](TemplatesPhone.dc.html) | 393×852 | No chip scroller at all — `All · n` and `New` are the whole strip — and the bottom sheet at 80% height with Recent as its first section. |
| [`TemplatesPhoneLandscape.dc.html`](TemplatesPhoneLandscape.dc.html) | 852×393 | The short-height fold: the same library in a 360-wide right-hand sheet, because a bottom sheet needs the vertical room this viewport has not got. |

## Where each number lives in the code

- **Header** — `components/ShowHeader.tsx` (`px-3 py-2`, shared by all four live views).
- **Row A** — `routes/ProgrammerPage.tsx` (unchanged: already `px-3 h-10`); the fade trigger's
  phone arm in `components/programmer/ProgrammerActionBar.tsx`; the source box's phone arm in
  `ProgrammerSourceStrip.tsx`.
- **Row B** — `components/programmer/ProgrammerGrid.tsx`: the row height, the field's `@[360px]`
  and `flex: 999 1 0%`, the key's absence in layer scope; the layer pill and save dot in
  `ProgrammerScopeBand.tsx`; the short placeholder in `lib/fixtureFilterCopy.ts`.
- **Row C** — `components/programmer/SelectionBar.tsx` (`h-10`) and the 28px chips in
  `TemplateStrip.tsx`.
- **Row C's recents and the picker** — `lib/templateRecents.ts` (the eight, and the by-name
  fallback), `components/programmer/TemplateStrip.tsx` (the `@[600px]` scroller fold and the
  `All · n` trigger), `TemplatePicker.tsx` (the three forms and the four sections),
  `useTemplatePress.ts` (the two gestures, shared by the chip and the pad), and
  `fixtures-list/SelectionToolbar.tsx`'s `MID_FOLDED_CLASS` for the 800px fold. The stamp itself is
  lighting7's `DaoTemplates.lastPressedAtMs` and `routes/templatePress.kt`.
- **Rail** — `ProgrammerRail.tsx` (header `h-10 px-3 gap-2`, strip chevrons `h-10`); the phone
  handle's gap in `ProgrammerWorkspace.tsx`.
