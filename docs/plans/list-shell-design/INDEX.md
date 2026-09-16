# List shell — design reference

Source: a Claude Design canvas authored 2026-09-15, generated from `lighting-react`'s real values —
the oklch tokens from `index.css`, the `button.tsx` sizes, the row system from
`programmer-chrome-design/` and the sheet anatomy from `sheet-views-design/`. **Read them as the
intended visual output, not as structure to copy**: static HTML mockups with no React and no state.

The brief: the list views are visually inconsistent — Fixtures and Groups sit in a `Card` with their
own margins and ground, the tables and their headers differ in background — and the consistency
should come from shared components rather than from styling kept in step by hand. This is a
**proposal**, not a plan. The five open calls were made on 2026-09-15 and are recorded on the `Spec`
artboard: row A keeps its wash; the plain lists gain the bar and the footer; the patch list becomes
its own route again; the chips row is a 40px chrome row; loading and not-found render on the shell too.

The live, pannable version is at <https://claude.ai/artifact/PqrQKeGH131DggxTWsc8um> — private to
Chris, so **treat these files as the authority** and the URL as a convenience.

## Format

Each `*.dc.html` is one artboard. Ignore the `<script src="./support.js">` line and the `<x-dc>` /
`<helmet>` wrappers — canvas scaffolding. Open a file in a browser and it renders standalone.

**The artboards are generated.** [`gen.mjs`](gen.mjs) is the source: `node gen.mjs` in this
directory rewrites every `.dc.html` and `canvas.json`. Change a value there rather than in an
artboard, or they drift. The seeded canvas (`list-views-on-one-shell.html`, the editor plus the
artboards, ~2.5 MB) is **committed since 2026-09-16** (Chris's call) — never edit it by hand; regenerate it with the design skill's `seed-canvas.mjs`
from these files, never by hand, and republish to the artifact URL above to update the live copy.

Every artboard is **dark-only on purpose** — read at a desk in a blacked-out room. The six surface
boards are drawn at **1180×820**, the iPad 11 frame the screenshots in the brief were taken at, so
they compare one to one with what is on screen today.

## The artboards

| File | Frame | Read it for |
|---|---|---|
| [`Spec.dc.html`](Spec.dc.html) | 1240×1400 | **Today, measured from the code**: shell, gutter, header row, toolbar rows, row C, sheet frame, sheet ground and footer for each of the six surfaces, with what differs in amber; the four stray duplications (three legend swatches, two header cells, four footers, two lists with no bar); the eight proposed rules; the five calls as made. |
| [`Kit.dc.html`](Kit.dc.html) | 1240×3220 | **The shell**: a fragment with a ruler — header 48 · rows 40 · the bar 40 · sheet 30 + 36n · footer 22, the 12px gutter, one line between neighbours; the three grounds (page, sheet, divider row) and the two washes; **where the code goes** — `components/sheet/sheetFrame.ts` and `SheetPage.tsx` as the one statement, and what each surface changes or loses; the surface × shell matrix. |
| [`Main.dc.html`](Main.dc.html) | 1180×820 | Fixtures › List on the shell — the lead complaint. Today a Card in a scrolling page with two scrollers, no bar and no footer. |
| [`Groups.dc.html`](Groups.dc.html) | 1180×820 | Groups › List, the same shell with grouped rows and the Ungrouped divider. |
| [`Programmer.dc.html`](Programmer.dc.html) | 1180×820 | Already on the system; drawn so the six agree. The grid's body takes the sheet ground (the name column is no longer darker than the cells) and the doubled line under the bar goes. |
| [`Show.dc.html`](Show.dc.html) | 1180×820 | The cue sheet. The stack header moves from `px-4` to the 12px gutter; the ShowBar keeps its own `px-4` by decision. |
| [`Channels.dc.html`](Channels.dc.html) | 1180×820 | The DMX sheet — the reference shape; only the doubled line and the swatch change. |
| [`Patch.dc.html`](Patch.dc.html) | 1180×820 | The patch list as its own route again (called 2026-09-15), on the whole shell: header row, the chips as a 40px chrome row, row B, the bar, the sheet, the footer. |

`canvas.json` lays them out on one page: Spec and Kit on the first row, the six surfaces in a 3×2
grid beneath, with a sticky note above each surface saying what changed on it and one for the brief.
