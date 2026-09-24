# Library sheets — design record

Source: eight boards drawn 2026-09-23 against `lighting-react` as of `ea56d99b`, in the list-shell
record's vocabulary (its tokens, `button.tsx` sizes and sheet anatomy). **Read them as the intended
visual output, not as structure to copy**: static HTML mockups with no React and no state.

The brief, from Chris on 2026-09-23: move the Scripts, FX Library, Looks, Templates and Speed
Masters views onto the table layout, with the drag select, editing and the rest the Programmer, Show
and Channels sheets support. Then a multi-session plan in the usual style.

**Status: all eight calls answered by Chris on 2026-09-23** (interactive); seven as drawn, the eighth
— a template copy route in lighting7 rather than a client-side duplicate — redrawn the same day. The
implementation plan is [`../completed/library-sheets-plan.md`](../completed/library-sheets-plan.md). The live copy of
these boards is at <https://claude.ai/artifact/RFXrmBb8LGYJhsuYTgPh2F> — private to Chris, a
convenience; the files here are the authority. Where a board and the plan disagree, the plan wins
on behaviour and these files on layout and copy.

## What the boards propose, in one paragraph

Each of the five libraries becomes a sheet on the list shell — header, a library row (filter ·
partition chips · create), the selection bar, the sheet, the footer — on its existing route, with no
Cards · Table switcher. Where a library partitions exactly (script type, effect category, template
family) the chips filter and, under *All*, divider rows group. The name column renames on a double
click and opens the record's editor from a pencil or ⏎. Cells edit metadata everywhere plus the few
values a library holds (a template's Value, Fade and effect Master; a master's live BPM, Start,
Follows, Ratio and Usage). The row verbs move onto the selection bar, and one dialog answers a batch
delete's in-use refusals.

## The boards

| File | Frame | Read it for |
|---|---|---|
| [`Main.dc.html`](Main.dc.html) | 1240×1960 | The five views today, measured from the code; the eight rules (L1–L8); the eight calls as answered; what is not proposed. |
| [`Kit.dc.html`](Kit.dc.html) | 1240×1720 | What the sheet kit gains — the library row, the name column's rename and open, the batch-delete dialog, the read-only library scope — and the file-by-file map. |
| [`Model.dc.html`](Model.dc.html) | 1240×2720 | Every sheet's columns (cell, kind, what a commit sends, Clear, Spread), the verbs on the bar, the sessions, the lighting7 half. |
| [`SpeedMasters.dc.html`](SpeedMasters.dc.html) | 1180×820 | ⏎ over two masters' BPM: one editor, both clocks; followers read out and are skipped. |
| [`Looks.dc.html`](Looks.dc.html) | 1180×820 | Three rows selected from the name column and the row verbs over them. |
| [`Templates.dc.html`](Templates.dc.html) | 1180×820 | Family chips and dividers; a Fade marquee over three templates with its editor. |
| [`FxLibrary.dc.html`](FxLibrary.dc.html) | 1180×820 | Categories as dividers; built-ins as read-only rows; Fork. |
| [`Scripts.dc.html`](Scripts.dc.html) | 1180×820 | Type chips replacing the sidebar; batch Compile filling the Check column. |

The surface boards are drawn at **1180×820**, the list-shell record's iPad frame, dark only.

## Superseded by the plan

- **Call 8** was first drawn as a client-side duplicate; the boards now show the copy route and
  Templates' *Copy to…* verb.
- **The Model board's sessions** are a sketch; the plan's §5 is the authority.
- **The name column is the sheet's `firstColumn`, not a `SheetColumn`.** The Kit board's
  `nameColumn` in `sheetModel.ts`, refusing a batch rename, is `LibraryNameColumn` — a render helper;
  a rename is one row by construction.
- **Skipped rows are dropped before `write`**, and the kit gains the read-out that names them; no kit
  cell has one today.
- **BPM and TAP are read-only off the current project** (they write the live show's clocks); the
  Speed Masters board draws only the current-project case.
- **Scripts' Used by** shows the effects an `FX_DEFINITION` script registers and *—* otherwise; the
  board's *cue hook · Q12* needs `FU-SCRIPT-USED-BY`.
- **Template Value** mounts `TemplateEditor`'s own family controls and rows rules, lifted, not the
  editor kit's editors.

## Corrected after the plan

- **A cell with nothing to set is blank** (master 1's Follows, a follower's Start, a manual master's
  Ratio, an effect template's Fade, a value template's Master). The boards drew a muted `·`; the
  programmer draws such a cell blank, and Chris asked the library sheets to do what the programmer
  does (2026-09-23, during session 1). `gen.mjs`'s `inertDash` is now empty and the Speed Masters and
  Templates boards were regenerated.

## Regenerating

`node gen.mjs` (Node LTS: `source ~/.nvm/nvm.sh && nvm use --lts`) rewrites every `.dc.html` and
`canvas.json` here. Change a value in `gen.mjs`, never in an artboard. It writes `canvas.json` from
scratch in the Design canvas's v3 shape, so merge by hand onto the copy checked in here, which was
re-saved by the canvas editor and carries keys of its own.
