# Programmer tables and fixture colours — design record

Source: three boards drawn 2026-10-18 against the shipped programmer (the rail's Stack · Colour ·
Spread, the Applied tables at `/programmer/applied`, `FxSheet`'s *Per-fixture FX*) and the shipped
patch list, in the earlier records' dark vocabulary (the oklch tokens from `index.css`, the list
shell's row system). **Read them as the intended visual output, not as structure to copy**: static
mockups with no React and no state.

The brief, from Chris on 2026-10-18: avoid having the FX / Looks / Palettes tables as a separate
view, and combine the confusing sheets on the programmer's right with the other tables; and add
ChamSys-style colours for fixtures, which also reach the patch list and other views. Keep today's
responsiveness.

**Status: calls decided 2026-10-18** (Chris answered all nine as recommended). The implementation
plan is [`../completed/tables-colours-plan.md`](../completed/tables-colours-plan.md). The live copy is at
<https://claude.ai/artifact/GmP5uvxEaF2zBKCfYuHmPi>, private to Chris; the files here are the
authority. Where a board and the plan disagree, the plan wins on behaviour and the boards on layout
and copy.

## Format

Each `*.dc.html` is one artboard. Ignore the `<script src="./support.js">` line and the `<x-dc>` /
`<helmet>` wrappers — canvas scaffolding. The repeated rows are data in each board's
`renderVals()`. Unlike the earlier records there is no `gen.mjs`: the boards are hand-written, so
change a value in the board itself. `canvas.json` lays the three out on one page.

## The artboards

| File | Frame | Read it for |
|---|---|---|
| [`Main.dc.html`](Main.dc.html) | 1240×1720 | Today against the proposal (one fact in three places → one sheet with tabs and an editor dock), each proposal in a paragraph, the six responsiveness rules, and the nine calls with their decisions. |
| [`Programmer.dc.html`](Programmer.dc.html) | 1440×2560 | **Values** with effects drawn in the cells (the FX lens, struck through when held back) beside the Colour · Spread dock; **Stack** (your values pinned, then every layer top-first, drag to reorder, kind chips); **FX** (Applied FX moved, `+ Effect`); folds and navigation. |
| [`Colours.dc.html`](Colours.dc.html) | 1440×2620 | The ten named colours and the reserved hues, the three name marks (Tweaks switches them; *both* was chosen); the patch list's **Colour** column and its popover; the colour in the programmer grid, busk rig tiles, the Stage view's labels and housings, the DMX sheet's footprints and a surface's labels; selecting by colour; the data model and its cost. |

## The calls, as decided

1. Tabs, with the in-cell FX lens for the side-by-side case; no split pane.
2. The right side keeps Colour · Spread only; layer reorder moves to the Stack table.
3. Scenery is a tab.
4. Looks and palettes are one Stack table in stack order, with kind chips.
5. Ten named colours, no custom hex.
6. A group's colour does not reach its members; *Colour from group* copies it.
7. A venue fixture's colour is the venue's (drawing-only); a show recolours through a show-owned
   table that covers venue groups too.
8. The name is **Colour** (*Set colour*); *Highlight* stays the dimmer-to-full verb.
9. Stage view housings tint by colour as a view option, off by default.

Also decided: the name mark is a swatch plus a wash on the name cell.
