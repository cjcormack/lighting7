# Programmer toolbar: design record

Source: a design session on 2026-10-25. Chris's brief named four problems with the programmer's
toolbar:

1. Adding a Look, a palette or an FX means switching to the Stack or FX tab first.
2. The palette strip on row C takes a lot of room for a gesture that two clicks elsewhere already
   do.
3. ⌘K reaches few of the programmer's actions.
4. The selection's actions on row C read as a jumble.

The engineering half is [`../programmer-toolbar-plan.md`](../programmer-toolbar-plan.md). Where the
plan and these boards disagree, the plan wins on behaviour and the boards on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| One *+ Add* for Looks, palettes and FX? | **No: three doors, by what each needs.** A Look lands whole, so **+ Look** is in row A on every tab. An FX starts on the selection, so **FX** is in row C. Palettes go through **Set**, whose popup already draws them. |
| Keep the palette strip? | **No.** Set's Palettes band does its job; *New palette* moves to Record ▾. |
| Set on whole rows, with no cells picked? | **Yes**: it asks for the attribute first, then opens that family's popup. |
| How does FX from the Values tab meet the FX editor? | **It starts on the grid, then hands over.** The picker asks *Replace · Stack* the way an FX editor lane does; once started, the popover is the live editor with *Open in FX editor ⤢*. |
| Spread or Fan? | **Fan**, the glossary's word, which the button already says. The first boards had it wrong. |

## What was found in the code

- **Set's popup already carries palettes and FX** (`frontend/src/components/editor/PaletteBands.tsx`):
  the family's palettes above the editor, its FX and FX palettes below, *Save as palette…* in the
  footer. It opens only on cells.
- **Replace · Stack exists only on an FX editor lane** (`fxEditor/FxEditorSelection.tsx`, `AddFx`).
  Every other door that starts an FX (⌘K *Apply FX*, the FX table's *+ FX*, a band's *+ FX…*) is
  the bare press, which always replaces.
- **The busk rig band already has the selection subsets** (`busking/RigBand.tsx`, `CellsMenu` and
  `StepButtons`): `selection.subselect` while following, `lib/cellsSubSelection.ts` over the copy
  when unlinked. The programmer has none.
- **Row C is the sheet kit's** (`sheet/SelectionBar.tsx`), which about a dozen sheets share, and its
  verbs are `fixtures-list/SelectionToolbar.tsx`'s, which the patch list shares too.
- **⌘K lives in `Layout`, outside the programmer's stores**; its one programmer command reaches in
  through a registered door (`programmer/addFxDoor.ts`).

## Format

Claude Design canvas: `canvas.json` is the index, and each `*.dc.html` is one artboard.
`Toolbar.dc.html` is the component; every other board imports it at a width, a selection and an open
menu (`Main` is interactive, with a width and selection switcher). `Command.dc.html` is a clickable
⌘K. `Today.dc.html` is the bar as it was on 2026-10-25, for comparison. The row widths in the boards
are estimates; session 1 measures them in the app.

The live canvas is at <https://claude.ai/artifact/PnF3qyzRjc27tLpmCMcNAh>. It is private to Chris and
only a convenience: these files are the authority.

## Names on the boards

*Spring Revue*, *Q4 Warm wash*, the fixtures, palettes, Looks and every value are illustrative.
