# Implementation prompt

Paste into a Claude Code session in `lighting-react`:

```
/verified-ship Put every list view on one shared shell, per the design record at
../lighting7/docs/plans/list-shell-design/ (read INDEX.md first, then Kit.dc.html and Spec.dc.html —
the Kit's "Where the code goes" table is the file-by-file plan; the six surface artboards are the
intended output at 1180×820). The five open calls are made and recorded on Spec under
"Called — 2026-09-15"; do not reopen them.

The shell, stated once and imported everywhere:

1. `src/components/sheet/sheetFrame.ts` — the class strings: PAGE_HEADER_CLASS (48px, px-3,
   border-b), CHROME_ROW_CLASS (h-10 px-3 gap-2 border-b, 32px controls), SHEET_SCROLLER_CLASS
   (overflow-auto bg-background, no border-t), SHEET_HEADER_ROW_CLASS, SHEET_HEADER_CELL_CLASS (the
   11px uppercase tracked cell — replace the inline copies in FixturesTable), SHEET_STICKY_CELL_CLASS,
   SHEET_ROW_CLASS, SHEET_DIVIDER_CLASS (bg-muted/30), SHEET_FOOTER_CLASS (h-[22px] border-t px-3
   text-[10.5px] text-muted-foreground).
2. `src/components/sheet/SheetPage.tsx` — `SheetPage` (full-height flex column filling <main>),
   `SheetPage.Header`, `SheetPage.Row`, `SheetPage.Footer`, `SheetPage.Empty` (the centred spinner or
   "Project not found" sentence, under the same header row), and one `LegendSwatch` (size-3 rounded-sm,
   ringed by the real `ownershipCellClass`) replacing the three swatch shapes on the cue sheet, DMX sheet
   and programmer footers.

The rules the boards apply: a list is a full-height column (no Card, no page scroll — the sheet is the
only scroller); 12px gutter on every row; header 48, every other chrome row 40, the SelectionBar 40,
footer 22, sheet header 30 and rows 36 (44 on the DMX sheet); three grounds — the page (bg-muted/40),
the sheet (bg-background on header row, sticky column and body alike), the divider row; a chrome row
owns its border-b, the sheet owns no top line, the footer owns its border-t, so there is one 1px line
between neighbours (today the bar's border-b and the sheet's border-t stack to 2px). Two named
exceptions, both by decision: the programmer's row A keeps bg-card/50, and ShowHeader's border stays
transparent until the unlocked wash colours it.

Per surface:
- Fixtures › List and Groups › List (`routes/FixturesList.tsx`, `GroupsList.tsx`,
  `fixtures-list/FixturesListContainer.tsx`): drop the Card and LIST_PAGE_CARD_CLASS; mount SheetPage
  with the breadcrumb header (view switcher on the right), one toolbar row (filter max-w-[340px]
  flex-[999_1_0%] · spacer · Lit · Columns), the SelectionBar (reserved "Nothing selected" when
  empty — the inline count in SelectionToolbar goes), the container with `fill`, and a footer with the
  count. `fill` becomes the container's only arm: delete the space-y-3 wrapper, the wrapping default
  toolbar, and FixturesTable's non-fill `rounded-md border` + `calc(100vh - 14rem)` arm. Keep the
  ?select= deep-link and sticky-view wiring untouched.
- FixturesTable: bg-background on the scroller (the fix SheetTable already carries — today the sticky
  name column is bg-background over a transparent body and reads darker than the cells on the
  programmer); header and row classes from sheetFrame.ts.
- SheetTable: same imports; the fill arm loses its border-t; the non-fill arm goes.
- Programmer (`routes/ProgrammerPage.tsx`, `programmer/ProgrammerGrid.tsx`): rows A and B become
  SheetPage.Row; the legend footer becomes SheetPage.Footer with LegendSwatch. Nothing else moves —
  ProgrammerPage.test.tsx's gridMounts assertion must still hold.
- Show › Table (`runner/StackDetail.tsx`, `runner/CueSheet.tsx`): the stack header goes from px-4 to the
  12px gutter via SheetPage.Header; the cue sheet's footer becomes SheetPage.Footer. ShowBar keeps its
  own @[440px]:px-4 (CLAUDE.md says why).
- Channels › Table (`routes/ChannelsTable.tsx`, `channels/DmxSheet.tsx`): header row and footer onto the
  shell; only the doubled line and the swatch change.
- Patch list (`routes/Patches.tsx`, `routes/ProjectSettings.tsx`, `navigation.ts`, `App.tsx`): a routed
  page again at /projects/:id/patches with breadcrumbs in a 48px header row; the Patch List tab and
  PatchListContent's mount in ProjectSettings go; PatchesRedirect already answers the bare path; add
  the nav entry back. The universe/group chips row becomes a 40px chrome row (28px chips) with its
  border-b, above row B (filter · spacer · Groups · Columns · + Patch).
- Loading and not-found on every list route: render inside SheetPage.Empty under the header row
  instead of `Card m-4 p-4`, so a list keeps its shape from loading to loaded.

Update CLAUDE.md: a short §List shell section under "Sheet kit" naming sheetFrame.ts and SheetPage as
the one place the shell is stated, the two exceptions, and that the patch list is a route again (the
"routes/ — what may live here" rule 2 example changes: Patches.tsx is routed once more). Update
docs/stage-vis-engineering.md only if it references the Patch List tab.

Verify: `npm run check` green with 0 warnings; then the browser pass at 1180×820 against the six
artboards — header/row/bar/footer heights and the 12px gutter measured with getBoundingClientRect on
each of the six views, the sheet header, sticky column and body computed to the same background, and
exactly one 1px line between the bar and the sheet. Commit straight to main, no PR, per CLAUDE.md.
```

The reviewers should be pointed at the record too: the Kit's rules are the acceptance criteria, and
the two named exceptions are not findings.
