# Palette references: design record

Source: a design session on 2026-11-04. Chris's brief: a Look should follow a palette rather than
copy its values, and three flows have to work:

1. Select four heads' positions in the programmer, all different, and make a palette from them. The
   palette holds each head's own position, and the four heads' values become references to it.
2. Select two heads and press a palette that holds four. Only the two selected heads take it; the
   palette's rows for the other two are ignored.
3. It is visually obvious when a value comes from a palette.

Later the same day, after testing the programmer: *a palette being a layer doesn't feel natural — it
should act the same as a value, a reference instead of a literal.* The boards were redrawn on that
model, so a palette press writes values and the Stack holds no palette layer.

The engineering half is [`../palette-references-plan.md`](../palette-references-plan.md). Where the
plan and these boards disagree, the plan wins on behaviour and the boards on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| Is a pressed palette a layer or a value? | **A value**: a reference, `pal:{uuid}`, per head and attribute. |
| *Use it on these heads* on a new palette: on or off by default? | **On.** |
| A value set over a reference? | **It replaces it**, as any later value does. Release takes it away and Undo brings it back. |
| Does Local show references? | **Yes**: they are your values. |
| FX palettes? | **Still layers** for now. |
| Palette layers already stored in cues? | **The plain ones become reference rows**; the rest stay layers, and nothing makes a new one. |

## Format

Claude Design canvas: `canvas.json` is the index, and each `*.dc.html` is one artboard.
`Programmer.dc.html` is the component; its `step` prop picks one of the seven states. `Main` is
interactive and steps through them. `A1`–`C2` show one step each, and `Key` shows the cell marks.
Ignore the `<script src="./support.js">` line and the `<x-dc>` / `<helmet>` wrappers: they are
canvas scaffolding.

The live canvas is at <https://claude.ai/artifact/94AA8mNyMUEkNUppZG5bpg>. It is private to Chris
and only a convenience: these files are the authority.

## The artboards

| File | Read it for |
| --- | --- |
| `Main.dc.html` | The seven steps in order, with Back and Next. |
| `Key.dc.html` | The cell marks: a literal of yours, a reference of yours, a reference in a cue or a Look, a partly-referenced group row, a Look layer's value, not set. |
| `A1`–`A3` | Flow 1: Record ▾ → *A new palette*, the sheet's *Use it on these heads*, and the cells afterwards. |
| `B1`–`B2` | Flow 2: Set's palette chip saying *2 of 4*, and the result, with the toast naming what was left out. |
| `C1`–`C2` | Flow 3: the Record Look sheet's line about references, and the Look focused in the grid with its sheet docked. |

## Names on the boards

*Spring Revue*, *DSR Focus*, *Act 1 Spots*, the fixtures and every value are illustrative.
