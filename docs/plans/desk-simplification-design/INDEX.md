# Desk simplification — design reference

Source: a Claude Design canvas authored 2026-08-22/23, drawn against `lighting-react`'s real
tokens — the same oklch values from `index.css`, the same shadcn control heights, and the ownership
ring colours copied from `components/fixtures-list/ownership.ts`. **Read them as the intended
visual output, not as structure to copy**: they are static HTML mockups with no React, no state and
no components worth lifting.

The implementation plan lives at
[`../desk-simplification-plan.md`](../desk-simplification-plan.md). It cites these files by name.

The live, pannable version is at
<https://claude.ai/code/artifact/d4f18b3f-87db-45c8-a0d1-22cdf9d51595> — private to Chris, so
**treat these files as the authority** and the URL as a convenience if you happen to have access.

## Format

Each `*.dc.html` is one artboard: a single fixed-size `<div class="app">` at the top of `<body>`,
with its width and height in an inline style. Ignore the `<script src="./support.js">` line and the
`<x-dc>` / `<helmet>` wrappers — canvas scaffolding, not part of the design. Everything meaningful
is the markup inside, and repeated chrome is classed in the `<helmet><style>` block.

[`canvas.json`](canvas.json) is the layout manifest: which artboard sits where, on which of the
three pages, plus the sticky notes. Useful only for reading the intended grouping.

Every artboard is **dark-only on purpose** — these surfaces are read at a desk in a blacked-out
room.

## Page 1 — directions (decided; kept as a record)

| File | What it is |
|---|---|
| [`DirectionA.dc.html`](DirectionA.dc.html) | Programmer and Show as siblings, cue editor kept. Not chosen. |
| [`DirectionB.dc.html`](DirectionB.dc.html) | One "Desk" view split down the middle. Not chosen. |
| [`DirectionC.dc.html`](DirectionC.dc.html) | **Chosen.** Opening a cue Includes it, so the programmer *is* the cue editor. Its "Against" panel states the standing tradeoff. |

## Page 2 — the structural answer

> Page 3 **supersedes** this page's layer and template thinking. Where they disagree, page 3 wins.

| File | Read it for |
|---|---|
| [`Main.dc.html`](Main.dc.html) | The Programmer view whole: source strip, zoned action bar, selection bar, value grid with ownership rings and a drag marquee, layer + FX rail. The density reference for everything else. |
| [`ProgrammerActions.dc.html`](ProgrammerActions.dc.html) | Today's seven-peer toolbar vs the three zones, Record's destination menu, and the source strip's **five states** including empty. Also the phone-width arrangement. |
| [`Show.dc.html`](Show.dc.html) | The renamed Show view: stack header with no "Add Cue", collapsed cue rows previewing cooked output, and an expanded cue drawn as the read-only grid + `LayerRow`. |
| [`ShowBar.dc.html`](ShowBar.dc.html) | The 560px threshold collision spelled out, then the proposed ladder at **four widths** (1240 / 880 / 640 / 380) with the masters popover. |
| [`LookLibrary.dc.html`](LookLibrary.dc.html) | `/looks` with no New button, rows previewing in the grid's language, and the pointer across to `/templates`. |
| [`TemplateEditor.dc.html`](TemplateEditor.dc.html) | Family-first template authoring with no fixture type, and the live "resolves to" panel across five fixture kinds. |
| [`BeamColour.dc.html`](BeamColour.dc.html) | The intent → resolve-at-cook model, and the table of what each family can and cannot promise across types. The beam exclusions are here. |

## Page 3 — layers and templates (current thinking)

| File | Read it for |
|---|---|
| [`Stack.dc.html`](Stack.dc.html) | **The centrepiece.** The stack in two bands with the values-beat-effects boundary drawn, the grid scoped to one focused layer, inert masked columns, dimmed non-targets, and the contextual template strip. |
| [`StackScopes.dc.html`](StackScopes.dc.html) | The three scopes side by side (Output / Local / one layer), the promote-a-selection-to-a-layer flow, the shared-Look edit guard, and why reorder must not restart phase. |
| [`Templates.dc.html`](Templates.dc.html) | The two apply gestures with before/after grids, the selection-filtered strip, `/templates` with **New template**, and the generic-vs-per-fixture case. |
| [`TwoThings.dc.html`](TwoThings.dc.html) | Look vs Template as two entities: the verb-by-verb table, and what stays one backend table. |
| [`AddEffect.dc.html`](AddEffect.dc.html) | Where a new effect lands per focused scope, and the full list of what a layer *can* override about an effect it does not own. |
| [`RecordLook.dc.html`](RecordLook.dc.html) | Building a shared Look from values **and** effects: both paths (busk-first, declare-first), the record sheet's Effects section, and the two things that do not travel. |

## What to read for each session

- **Session 1** — `Main`, `ProgrammerActions`, `ShowBar`. Plus `DirectionC` for why the view splits.
- **Session 2** — `Stack`, `StackScopes`, `RecordLook`, `AddEffect`, `Show`. **These predate the
  Run/Show merge** that Session 2 absorbed on 2026-08-23: they draw Show as one of four views, with
  Run still beside it, and no lock anywhere. Read them for the cue surface and the stack, not for the
  view count or the chrome around it — the plan's Session 2 is the authority there.
- **Session 3** — `Templates`, `TemplateEditor`, `TwoThings`, `BeamColour`, `LookLibrary`.

## Reading them

Open a file in a browser and it renders standalone — the styles are inline or in its own `<helmet>`
block, and nothing loads over the network. Sizes are fixed, so use the browser zoom rather than
resizing the window; `ShowBar.dc.html` draws its own four widths internally rather than responding
to yours.
