# Busk view, further — design record

Source: a Claude Design canvas authored 2026-09-17 against `lighting-react`'s shipped busk view
(the dark oklch tokens, the 52px `TargetPad`, the 56px pad shell, the 288px `BuskSpeedRail`, the
360px `LibraryPalette`) and the look-groups record beside it. **Read them as the intended visual
output, not as structure to copy**: there is no React here.

All eight sessions of the plan beside these files landed, and that plan is the record of what was
built. The brief was to take the busk view and the multi-screen work further — a configurable,
positioned target band; focus on pads or on the selection; re-proportioning the two regions; a
collapsible speed rail; a colour picker as an alternative sheet; spread over a selection;
multi-head fixtures in selections — plus ideas, and a survey of other desks. Every open call was
answered by Chris on 2026-09-17; the answers are in the plan's §2 and §11.

The implementation plan lives at [`../busk-further-plan.md`](../busk-further-plan.md). Its §4 is
a grep-able summary of what these files draw; where wording disagrees, the plan wins on behaviour
and these files win on layout and copy.

The live, pannable version is at <https://claude.ai/artifact/FViMcdxsaKHautb7sWcmQ2> — private to
Chris, so **treat these files as the authority** and the URL as a convenience if you happen to have
access.

## Superseded by the plan

These boards are the record of what was *drawn*, so five of their answers were overtaken while the
work was built and are not what shipped. Read the plan for each, and don't rebuild the board's
older answer:

- **`windows.focus`** (`Focus`) — focus is carried by the generic `windows.viewOptions` command,
  not a command of its own.
- **`TARGET_CELLS_UNSUPPORTED`** (`Model`, `Cells`) — never existed. The element arm landed first,
  as session 1, so no door ever had to refuse a cell.
- **`busk.sheetOpen`** (`Focus`, `Sheets`, `Model`) — one fact, `busk.sheet`, whose enum carries
  `none`; there is no separate open flag.
- **The Cells chip as `selection.set`** (`Cells`) — the chip is `selection.subselect`, which
  rewrites the selection's targets over the rig order on the desk.
- **The client-side parent→cell fold in `lookPresence.ts`** (`Cells`) — the coverage rule lives in
  `TargetCoverage` on the desk, stated once, so the ring and `pressWouldRelease` cannot disagree.

## Format

Each `*.dc.html` is one artboard. All twelve are **static mockups**: no `{{ hole }}` bindings and a
no-op logic script, so each renders standalone in a browser. `canvas.json` is the layout manifest,
one page in four rows, plus sticky notes recording the five open calls as they were asked (all now
answered) and one on how to read the rows.

Every artboard is **dark-only on purpose**, in the busk view's own vocabulary.

## Row 1 · The proposal as screens

| File | Draws |
| --- | --- |
| `Main.dc.html` | The busk view in play at 1440×900: the rig band as three named rows of tiles with live colour bars and pips, the label row with the Cells chip and *Spread…*, the rows handle; the page strip with the Focus control; the page as today; the side sheet with Speed · Colour · Spread tabs and the fold, Colour open. Six callouts. |
| `Rig.dc.html` | Edit mode: the band under the page's amber wash, row name fields, crosses, a drop slot, *+ Row*, *Arrange: Rows / Plot*, the tile's cell-mode menu, and the palette's **Rig** tab. |
| `Focus.dc.html` | The three shapes (Split · Pads · Rig) as miniatures; the two-screen flow; the per-window facts and how they are set. |
| `Screens.dc.html` | The Screens sheet with a busk row's Focus / Sheet / Page pickers, a row contributing no options, the widened *Copy link*, and the generic descriptor and command. |

## Row 2 · The three new controls at full size

| File | Draws |
| --- | --- |
| `Sheets.dc.html` | The 44px fold, the Speed tab unchanged, the Colour tab, and the six rules about what it writes. |
| `Spread.dc.html` | The Spread tab (From / To, the four curves, Order, Parts, Over, Live, the preview strip), the request and response, and what each desk calls it. |
| `Cells.dc.html` | A multi-head tile in four states, the Cells chip, the wire, the backend gaps (now session 1), the rules the tile states, and the desk comparison. |

## Row 3 · Evidence, model, extras

| File | Draws |
| --- | --- |
| `Survey.dc.html` | MagicQ, grandMA3, Titan, Hog 4, Eos and Lightkey / QLC+ across selection surface, layout and screens, fan, sub-fixtures and colour. |
| `Model.dc.html` | What is stored on the desk, in the window and on the wire; where the code goes; the session split as first drawn (the plan's seven sessions supersede its five). |
| `Ideas.dc.html` | Ten extra ideas as separate cards, each tagged by cost, and three things declined. All out of scope by decision. |

## Row 4 · How it folds

| File | Draws |
| --- | --- |
| `Phones.dc.html` | iPhone portrait at 1:1 in three states (Split, Pads with the Colour bottom sheet, Rig focus) and landscape (Pads with the side-sheet overlay), with the short-beats-narrow rule. |
| `Tablets.dc.html` | iPad portrait (rail folded by default) and landscape (the desktop board) at 0.6, and the fold ladder for every surface on the app's existing breakpoints. |
