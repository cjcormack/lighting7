# Programmer space — design reference

Source: a Claude Design canvas authored 2026-09-09, drawn against `lighting-react`'s real tokens —
the oklch values from `index.css`, the shadcn control heights (`h-8` buttons, 36px grid rows, the
260px sticky name column, `minmax(96px, 1fr)` value columns), the ShowBar's four rungs, and the
ownership ring colours from `components/fixtures-list/ownership.ts`. **Read them as the intended
visual output, not as structure to copy**: they are static HTML mockups with no React and no state.

The implementation plan is [`../programmer-space-plan.md`](../programmer-space-plan.md). It cites
these files by name.

The live, pannable version is at
<https://claude.ai/code/artifact/a8f3830e-092f-4a0e-93ad-06c309853453> — private to Chris, so
**treat these files as the authority** and the URL as a convenience.

## Format

Each `*.dc.html` is one artboard: a single fixed-size `<div class="app">` with its width and height
in an inline style. Ignore the `<script src="./support.js">` line and the `<x-dc>` / `<helmet>`
wrappers — canvas scaffolding. The shared chrome is classed in the `<helmet><style>` block; the
rest is inline. Open a file in a browser and it renders standalone.

[`canvas.json`](canvas.json) is the layout manifest and carries the three sticky notes, which are
the shortest statement of the direction.

Every artboard is **dark-only on purpose** — these surfaces are read at a desk in a blacked-out
room. They draw the show **running** with Q4 included and four heads selected, because an empty
programmer is the state that makes any layout look spacious.

## Page 1 — the Programmer

| File | Frame | Read it for |
|---|---|---|
| [`Main.dc.html`](Main.dc.html) | 1440×900 | The whole answer: row A (source + verbs), row B (scope + grid tools), the selection bar carrying the templates, the neutral selection, the marks gutter, and the 300px rail with its two bands. Sidebar collapsed. |
| [`Budget.dc.html`](Budget.dc.html) | 760×570 | Where the page goes, today and proposed, at 1440×900 and on an iPhone. The numbers the plan's §1 quotes. |
| [`ChromeMerge.dc.html`](ChromeMerge.dc.html) | 1376×190 | The **optional** one-band chrome: ShowHeader folded into the ShowBar. Separable, and shared by all four live views. |
| [`TabletLandscape.dc.html`](TabletLandscape.dc.html) | 1180×820 | The rail collapsed to its 40px strip, which is the default under 1200px of content width. |
| [`TabletPortrait.dc.html`](TabletPortrait.dc.html) | 820×1180 | The rail **opened from the strip as an overlay** over the grid, and the 700–1000 ShowBar rung under the two rows. |
| [`Phone.dc.html`](Phone.dc.html) | 393×852 | The one-row ShowBar, icon arms of rows A and B, sideways-scrolling value columns under a fade, and the rail as a bottom sheet with a 44px handle. |
| [`PhoneLandscape.dc.html`](PhoneLandscape.dc.html) | 852×393 | The short-height mode: app header scrolled away, rows A and B folded into one. |

## Page 2 — alternates, kept for the record

| File | What it is |
|---|---|
| [`DirectionB.dc.html`](DirectionB.dc.html) | Layers as a bottom band under a full-width grid. Not chosen; worth a second look only if width still feels short on a 24-column rig. |
| [`DirectionC.dc.html`](DirectionC.dc.html) | The desk-simplification plan's own session-2 sketch: a 322px left rail with the scope switch inside it. Not chosen. |

## What to read for each session

- **Session 1** — `Main` rows A and B, `Budget`.
- **Session 2** — `Main`'s selection bar, marquee and cell marks; `Phone`'s selection bar.
- **Session 3** — `Main`'s rail, `TabletLandscape` (strip), `TabletPortrait` (overlay).
- **Session 4** — `Phone`, `PhoneLandscape`, and `TabletPortrait`'s ShowBar rung.
- **Session 5 (optional)** — `ChromeMerge`.
