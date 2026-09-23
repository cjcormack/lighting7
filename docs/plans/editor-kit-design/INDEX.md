# One editor kit — design record

Source: five boards drawn 2026-09-22 against `lighting-react`'s shipped busk view (`7fc7bf84`) and
programmer, in the busk-further record's dark vocabulary (`completed/busk-further-design/`), plus a
sixth (`RailTabs`) drawn 2026-09-23 against the shipped kit (`679bd1ae`) for the one call left open. **Read
them as the intended visual output, not as structure to copy**: there is no React here.

The brief, from Chris on 2026-09-22: the busk view's recent work — the **Spread** tab, the
**Colour** tab and the sheet's anatomy — is the state of the art, and the programmer should take the
same improvements: Spread where it says Fan (and one name for it), the better colour editor, the
other inconsistencies, every value editor on the better design, shared components across both views,
plus other ideas. The outcome asked for was these boards; the plan document follows once the designs
are agreed.

**Status: approved 2026-09-22 (Chris: "the designs look good"); sessions 1–3 shipped 2026-09-22 (lighting-react `b12887ab`, `a976489e`, `679bd1ae`; lighting7 `8fc8fcb`); session 4 shipped 2026-09-23 (lighting-react `53b0c57b`), calls 8–12 as drawn — plan complete.** The
implementation plan is [`../completed/editor-kit-plan.md`](../completed/editor-kit-plan.md); the seven calls on
`Model.dc.html` stand as drawn and are recorded in its §11, the rail tabs' five with them. The live copy of these boards is at
<https://claude.ai/artifact/UECPcKmhVPZ2FFhvxtTHMd> — private to Chris, a convenience; the files here are
the authority.
Where a board and the plan will disagree, the plan wins on behaviour and these files on layout and
copy, as with every record in this directory's siblings. **Where a board and the shipped busk tab
disagree on a measurement, the busk tab wins**: it is the design authority these boards extend.

## What the boards propose, in one paragraph each

**Fan is renamed and replaced by the busk tab's Spread panel.** One component,
`components/editor/SpreadPanel.tsx`, hosted by the programmer's row C as a popover (in the cell
editor's three forms), by the busk view as the docked tab, and by the patch list and cue sheet with
their own plan kinds. Every spread over a property in the template vocabulary is resolved by the desk
through `POST /programmer/spread` — the programmer's rows are heads the desk knows better than the
browser does — with the marquee's heads as targets (a group row to its visible members, an element
row as a cell, a fixture row as itself), the family segment answered by the marquee and drawn checked,
and the Property row from the column. The ends are intents (percent, colour + policy or a template,
degrees), so the position exclusion goes; Live comes to the programmer; Reverse is *Order: Reverse*.
The focused-Look-layer arm survives as one flag on the route (`write: false` — resolve and answer,
the client lands the literals in the layer's draft), which also needs `written[].value` to become
the literal rather than the intent. `fanValues` / `fanColours` are deleted; the client walks only
addresses, fade times, and raw bytes on a column outside the vocabulary.

**The colour editor is one body, one read-out and one footer.** `ColourPickerBody` becomes
`ColourEditor` and takes the busk tab's read-out line (emitter counts per head · *mixed* · swatch ·
hex), **Pick**, and the footer with **Save as template…** and **Spread…**; the programmer's colour cell
hosts it at 352px with a fluid picker, the busk view docks it, the phone sheet and the short-viewport
sheet take it, and the Spread panel's colour endpoint hosts the picker half alone. The count line at
the top (*Applying to 4 targets*) and the guidance paragraph go; Recent chips are drawn only where no
strip is on screen (the busk tab and the phone sheet). The two property visualisers keep the
picker-only form.

**The value editors take the busk tabs' anatomy.** A label line (heads · scope on the left, the
column on the right), 9px uppercase labels over 28px fields with the unit inside, a segmented row for
any choice of four or fewer, a read-out line that says what the desk holds and what was skipped, and a
static footer only where a panel writes on Apply. `components/editor/` exports each piece once —
`EditorSurface`, `EditorLabel`, `EditorField`, `EditorReadout`, `EditorFooter`, `ValueFieldRow`, the
keyboard and open hooks, `useLivePush` — and every host imports it. The level editor's field is drawn
as a percent where the cell reads one (bytes on the DMX sheet); the position editor gains a 120px XY
pad and degree fields; widths are 288 / 256 / 352 / 336 / 320 by content.

**Ten other ideas, each with a verdict, and seven open calls** — on `Model`. The one idea outside the
three above that the boards recommend asking about is Colour · Spread as tabs on the programmer rail
(the docked form on the programmer, the popover as the quick form).

## The calls, as drawn (approved with the boards; §11 of the plan)

1. **Save as Look… in the programmer's Spread footer.** Drawn without: Record is one row up.
2. **Recent chips in the desk colour popover.** Drawn without: row C's strip is one row up; the phone sheet draws them.
3. **Percent in the programmer's level editor.** Drawn as %, byte on the read-out; the DMX sheet keeps bytes.
4. **The Speed column.** Outside the template vocabulary. Drawn: a `raw` plan kind keeps the client byte lerp for it alone.
5. **Over: Heads as the programmer's default.** Fan expanded a bar into its cells always; Spread follows the busk tab.
6. **The rail tabs.** Colour · Spread as tabs on the programmer rail — now as a session 4, later, or never. Drawn on `RailTabs.dc.html` (2026-09-23), which restates it as call 8 and adds four of its own: 9. a tab claims its own column's open gesture (drawn: yes); 10. the tab rests on Stack on every arrival, not persisted (drawn); 11. the label line in the tab (drawn with); 12. the rail's floor stays 260 with a tab open (drawn).
7. **Degrees in the position cell editor.** Drawn in degrees; the cell keeps bytes.

## Format

Each `*.dc.html` is one artboard, static, dark-only on purpose. **The artboards are generated**:
[`gen.mjs`](gen.mjs) is the source — `node gen.mjs` in this directory rewrites every `.dc.html`,
`canvas.json` and the seeded artifact page `editor-kit.html` (every board on one scroll, with a zoom
control), which is what the artifact URL serves. Change a value there rather than in an artboard, or
they drift. Board heights are the `H` map at the top of the generator, measured in a browser after a
layout change. Ignore the `<script src="./support.js">` line and the `<x-dc>` / `<helmet>` wrappers —
canvas scaffolding; open a file in a browser and it renders standalone.

| File | Draws |
| --- | --- |
| `Survey.dc.html` | Today, side by side: the programmer's Fan on a Colour and on a Dimmer marquee against the busk Spread tab, with the nine differences that matter; the colour cell's popover against the Colour tab, with seven; the three programmer value editors against the busk endpoint fields; and the six rules the other boards apply. |
| `Spread.dc.html` | The programmer's row C reading Spread with the panel open over a 4 × Colour marquee; the same panel as the busk tab, in a focused Look layer, on a Position marquee with Live on, and the kit's address and duration kinds; the request and response including the one new flag. |
| `Colour.dc.html` | The colour editor open from the colour cell at 352; the busk tab, the phone bottom sheet, the short-viewport side sheet, and the Spread endpoint picker; the pieces-by-host table. |
| `Editors.dc.html` | The anatomy with its five pieces named; Level (programmer, %), Level (DMX sheet, bytes), Position with the XY pad, Setting, Text and Address on it; six rules. |
| `Model.dc.html` | Where the code goes (fourteen moves), the rename table, the wire (one flag, one response field), ten ideas with verdicts, the seven open calls, and four sessions. |
| `RailTabs.dc.html` | **Drawn 2026-09-23, after sessions 1–3 shipped: call 6 drawn out.** The programmer rail with a tab strip for its header — Stack (Layers · FX) · Colour · Spread — the Colour tab docked at 300 over a 4 × Colour marquee beside the grid; the Spread tab on a Position marquee with Live on, the Stack tab as today, the empty state, the collapsed strip with a glyph per tab; what each grid gesture does while a tab is open; the scope per tab; where the code goes (the marquee published through a context, two hosts, one plan hook shared with the popover); calls 8–12 and six declined alternatives. Shipped as session 4 (lighting-react `53b0c57b`). |
