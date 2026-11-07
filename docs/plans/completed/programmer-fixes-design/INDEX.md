# Programmer fixes — design record

Source: a request on 2026-11-06 listing four problems on the programmer. Cells that are not next to
each other cannot be selected together, and that should hold on every table with a multi-selection.
Multi-head fixtures' element rows expand again whenever the operator navigates to the programmer,
and there should be an expand / collapse all control. The colour editor and the other value pop-ups
should show recent values, wherever those pop-ups appear. Dragging the colour picker's dot in a
colour FX's picker sometimes closes it.

**Status: agreed 2026-11-06, corrected in review the same day** (the FX picker's suspected cause,
the Timeline's click, the recents' value spelling). The implementation plan is
[`../programmer-fixes-plan.md`](../programmer-fixes-plan.md). The one-page copy is
[`programmer-fixes.html`](programmer-fixes.html), hand-written and theme-aware, its mock-ups drawn
dark in both themes as the desk is. Its §Decisions holds the five answers and the calls it made.

## Asked and answered

Chris answered these on 2026-11-06: the first four before the design, the last after review.

| Question | Answer |
| --- | --- |
| How is a non-contiguous selection made by touch? | **An *Add* latch on the selection bar**, on coarse pointers. Long-press (it collides with the touch marquee's hold) and mouse-only were declined. |
| Where do recent values live? | **On the desk, shared by every window and device**, a runtime list per project. This browser's localStorage and per window were declined. |
| How widely does expansion persist? | **Per window, per list** (sessionStorage). Per browser and one state shared by every list were declined. |
| Where did the FX picker close? | **The programmer's row C → FX**, a colour FX, dragging the colour picker's dot. |
| One level-recents list for the grid and the palette editor? | **Separate lists.** The grid's percent is of 255 and the palette's of each head's range, so they land differently on a capped head. Asked after review. |

## What was found in the code

Read from `main` at `c1610df`, 2026-11-06:

- **A cell click always replaces.** Every cell's trigger passes its click event to `onBeginEdit`,
  but the hosts drop it: `FixturesTable.tsx` line 1153 and `SheetTable.tsx` line 930 call
  `onBeginCellEdit(row, col)` with no event, and `FixturesListContainer.tsx`'s, `useSheet.ts`'s and
  `CueValuesGrid.tsx`'s handlers select `[cell]` with `'replace'`. A ⌘-drag already accumulates
  (`useCellMarquee` reads the modifiers and `applyCellSelection` unions), and row clicks already
  take ⌘ / ⇧ on every host. The cue Timeline handles its own cell clicks (`StackTimeline.tsx`'s
  `clickCellRef`): it already draws a ⇧-click rectangle, and a click on its one selected cell
  toggles that cell's layer.
- **Expansion is `useState`** in `FixturesListContainer.tsx` (lines 326–327), lost on unmount. On
  the way back the grid **replays the last Include or select request**: `store/includeSelection.ts`
  keeps it in a module-level store that never marks a request spent, and the container's guard is a ref reset
  to 0 on every unmount (lines 1240–1248), so the effect at line 1249 re-opens parent fixtures and
  groups, re-selects, clears the filter, turns Lit off and scrolls. `programmerSelectRequest.ts`
  publishes through the same store.
- **No recent values anywhere.** The only recency is `PaletteSummary.lastPressedAt`
  (`lib/paletteRecents.ts`). There are three colour pickers: `editor/ColourEditor.tsx` (programmer,
  cue Values, fixture sheet, busk Colour, Fan), `fx/ColourEditorBody.tsx` (FX colour parameters) and
  `templates/familyControls/ColourControl.tsx` (palette editor).
- **The FX picker's close is not explained.** A real-Chrome harness and jsdom both kept a nested
  Radix popover with a `react-colorful` drag open. The picker's lifetime follows the visible
  selection: row C's actions render only while `locateTargets` (`rows ∩ selectedRowIds`) is
  non-empty (`FixturesListContainer.tsx` line 1606), and `ProgrammerAddFxButton` closes its picker
  when the offer turns disabled; while the window follows the desk, that offer reads the desk's
  selection, so another window or a surface emptying it closes the picker too. The design page first named *Lit* hiding a head whose colour
  reached 0; review found `useLitFixtureKeys` reads dimmer channels only, so *Lit* empties the rows
  on a dimmer fade through 0, never on a colour drag, and the record was corrected the same day.
  On the reported path, Colour Cycle's list picker sends every change as a release (`FxFields.tsx`
  line 1812), skipping `useLivePush`'s floor, one colour sends no release, and the list picker
  re-mints its swatches and closes the open one on any value it did not last emit
  (`FxColourListPicker.tsx` line 72).

## Format

`programmer-fixes.html` is the record. Names in its mock-ups (Wash 1, Bar 1, Spot 1) are
illustrative.
