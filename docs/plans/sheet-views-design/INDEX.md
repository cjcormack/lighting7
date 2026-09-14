# Sheet views — design reference

Source: a Claude Design canvas authored 2026-09-14, generated from `lighting-react`'s real values —
the oklch tokens from `index.css`, the `button.tsx` sizes, the row system from
`programmer-chrome-design/`, and the `FixturesTable` anatomy (30px header, 36px rows, the sticky
name column, the cell trigger with its 18px marks gutter, the ownership rings, the marquee band and
the `after:` selection overlay). **Read them as the intended visual output, not as structure to
copy**: static HTML mockups with no React and no state.

The brief: the programmer gained drag selection, double-click Set, keyboard entry and a verbs bar;
roll them out to the **patch list**, **channels** and **show**, look at what other desks do in those
views, and say which components can be shared. This is a **proposal**, not a plan — nothing here is
implemented, and the open calls are on the `Spec` artboard.

The live, pannable version is at
<https://claude.ai/code/artifact/abbed62a-4be2-4a9a-9d3a-b751874fd853> — private to Chris, so
**treat these files as the authority** and the URL as a convenience.

## Format

Each `*.dc.html` is one artboard. Ignore the `<script src="./support.js">` line and the `<x-dc>` /
`<helmet>` wrappers — canvas scaffolding. Open a file in a browser and it renders standalone.

**The artboards are generated.** [`gen.mjs`](gen.mjs) is the source: `node gen.mjs` in this
directory rewrites every `.dc.html` and `canvas.json`. Change a value there rather than in an
artboard, or they drift. The seeded canvas (`sheet-views.html`, the editor plus the artboards, ~3.5 MB)
is **not committed** — regenerate it with the design skill's `seed-canvas.mjs` from these files, never
by hand, and republish to the artifact URL above to update the live copy.

Every artboard is **dark-only on purpose** — read at a desk in a blacked-out room — and 1440 wide;
no phone or tablet arms are drawn. Those are a second pass once the desktop shape is agreed.

## The artboards

| File | Frame | Read it for |
|---|---|---|
| [`Spec.dc.html`](Spec.dc.html) | 1240×1080 | The desk survey (grandMA3, Eos, Titan, MagicQ, Hog 4, ONYX — patch, DMX sheet, cue sheet), the six rules the mockups apply, and the open calls: one word for the second view, where the patch sheet lives, a bulk patch route, the cue sheet's columns, the DMX cell's readout. |
| [`Kit.dc.html`](Kit.dc.html) | 1240×1480 | The shared kit: the sheet anatomy every surface shares, row C per surface (the left half constant, the verbs the surface's), the gesture × surface matrix, and where the code goes — what lifts out of `components/fixtures-list/` into `components/sheet/`, the `SheetColumn<Row>` shape, the two new cells (`TextCell`, `AddressCell`), the writers per surface, and the `ViewSwitcher` wiring. |
| [`Main.dc.html`](Main.dc.html) | 1440 wide, three strips | The patch list as a sheet: the Patch List tab with universe chips carrying a fill bar, a universe toggle and filter on row B, `+ Patch` on the row; then **Set four addresses** (the address editor, consecutive by footprint, the collision named before Apply) and **Fan eight heads** (From · Step · Order). The overlap is a destructive ring on the cell. |
| [`Channels.dc.html`](Channels.dc.html) | 1440 wide, three strips | Cards (today) with the Cards · Table switcher added to the toolbar; the Table — a 16-wide DMX sheet with 44px cells (address · attribute / value, a fixture's footprint as a tinted run named on its first cell, ownership rings); then **drag eight addresses and set them** with the slider editor and the Set · Clear · Fan · Park · Unpark bar. |
| [`Show.dc.html`](Show.dc.html) | 1440 wide, three strips | The cue sheet: Cards · Table on the stack header, one row per cue, markers as divider rows, Name · Fade · Curve · Follow · Notes as cells (the cue number keeps its inline edit) and Book · Layers · FX · Hooks as read-outs; **Fan four fade times** under the unlocked amber wash; and **Locked**, where every value cell is inert, the verbs say why, and the Cue column arms a cue as next. |
| [`ChannelsRows.dc.html`](ChannelsRows.dc.html) | 900×1040 | The alternate for channels — one row per address on the programmer's exact table — with the for / against / call. The grid is the recommendation. |

`canvas.json` lays them out on one page with two sticky notes: the brief, and the channels call.
