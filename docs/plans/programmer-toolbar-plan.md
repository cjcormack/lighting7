# The programmer's toolbar: three doors, a tidier row C, and ⌘K

> **Document status: READY — written 2026-10-25 from the boards Chris and Claude worked through that
> day.** Three sessions, each one PR, each stacked on the one before, each run by `/next-session`
> (§5). The boards and the record are in
> [`programmer-toolbar-design/INDEX.md`](programmer-toolbar-design/INDEX.md).
>
> This document is the engineering half. Where it and the record disagree, this plan wins on
> behaviour and the boards on layout and copy.

## 1. Context

Chris's brief, 2026-10-25, named four problems with the programmer's toolbar:

1. Adding a Look, a palette or an FX means switching to the Stack or FX tab first.
2. The palette strip on row C takes a lot of room for a gesture that two clicks elsewhere already
   do.
3. ⌘K reaches few of the programmer's actions.
4. The selection's actions on row C read as a jumble.

Today, row A is the source box, then Clear · fade, Blind, Include… and Record ▾. Row B is the table
tabs, the scope, the filter, Lit, Groups and Columns. Row C is the count, the mask pill, the ⏎/⌫ hints
and the desk chip, then the palette strip (`programmer/PaletteStrip.tsx`), then seven peers: Set ·
Clear · Fan · Release · Locate ▾ · Highlight · Deselect.

What the code says, read at `bbeff46`. `F/` is `frontend/src`.

- **Set's popup already carries palettes and FX** (`F/components/editor/PaletteBands.tsx`): the
  family's value palettes above the editor, its running FX, FX palettes and *+ FX…* below, and *Save
  as palette…* in the footer. It opens only on cells, through `CellSelectionActions`' Set.
- **Replace · Stack is asked only on an FX editor lane** (`F/components/fxEditor/FxEditorSelection.tsx`,
  `AddFx`, from `fxLanes`' `holdsLocal`). ⌘K's *Apply FX*, the FX table's *+ FX* and a band's *+ FX…*
  all use `FxPicker`'s bare press, which always replaces.
- **The busk rig band has the selection subsets** (`F/components/busking/RigBand.tsx`, `CellsMenu` and
  `StepButtons`): All · Odd · Even · 1st half · 2nd half · Invert · Masters only, and Prev / Next.
  It sends `selection.subselect` while the window follows the desk, and runs `lib/cellsSubSelection.ts`
  over its own copy when unlinked. The programmer has no subsets.
- **Row C is the sheet kit's** (`F/components/sheet/SelectionBar.tsx`), shared by about a dozen
  sheets, and its verbs come from `F/components/fixtures-list/SelectionToolbar.tsx`, which the patch
  list shares.
- **⌘K is mounted in `Layout`, outside the programmer's stores.** Its one programmer command reaches
  in through a door the programmer's tables register (`F/components/programmer/addFxDoor.ts`).
- **No wire change is needed.** Every operation exists: `POST /programmer/fx` with `stack`,
  `selection.subselect`, `programmer.clearTarget`, the palette and Look toggles.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | **Three doors, by what each needs.** **+ Look** in row A, on every tab: a Look lands whole. **FX** in row C, beside the selection's verbs: it starts one Local FX on the selection. **Palettes through Set**: its popup already draws them. The Stack table's *+ Look or palette*, the FX table's *+ FX* and an FX editor lane's *+ FX* stay. |
| D2 | **The palette strip goes.** `PaletteStrip` and what only it used are deleted. Its *New palette* moves to Record ▾ as **A new palette**, from the selection (`NewPaletteFromSelectionSheet`), disabled with the reason when nothing is selected. Set's footer keeps *Save as palette…* for the cells' own values. |
| D3 | **Row C is three clusters, left to right.** *Which heads*: the heads chip, then the mask pill. *What to do to them*: **Set ⏎ · Fan · FX · Release ⌫ ▾**. *Where they are on the rig*: **Locate ▾ · Highlight**, pushed to the right edge. This is the programmer's row only: every other sheet's selection bar draws exactly as it does today. |
| D4 | **The heads chip** is the count (*6 heads · 6 cells*, *4 heads*, *Nothing selected*), the desk link glyph, a chevron that opens its menu, and a **×** that deselects. The menu has: the busk band's subsets, *All · Odd · Even · 1st half · 2nd half · Invert · Masters only* and *Previous · Next*, sent the busk band's way (lifted into one shared component); *Select by colour ▸* (`fixtureColours/SelectByColour.tsx`); *Follow the desk selection* ✓; on a phone, *Locate*, *Set locate from current* and *Highlight*; and *Deselect*. |
| D5 | **The desk link moves into the heads chip.** On the programmer's row C the chip's glyph shows whether the window follows (the link badge) or not (the dashed mark). The toggle is the menu's *Follow the desk selection* item, not a press on the glyph. This revises desk-follow plan D11 for this row only; the busk band keeps its press-to-toggle chip. |
| D6 | **Set works on whole rows too.** With cells selected it opens today's popup. With only rows selected it first asks for an attribute family; the choice selects those rows' cells in that family's column (a marquee, so the mask becomes that family) and opens the same popup. Set is never drawn disabled while anything is selected. |
| D7 | **Release is one split button.** The face releases the cells when cells are selected, else the whole heads. The menu has two arms: *These n cells* (⌫; their values leave Local, worded per scope as `cellActionCopy` words today's cell Clear) and *The whole n heads* (every value and Local FX on them, today's Release). The cell **Clear** button goes, and ⌫ keeps doing what it does. Afterwards *Clear* means only row A's whole-programmer Clear. |
| D8 | **FX opens the picker on the grid**, over the window's FX pair (`useWindowFxPair`), on the mask's family when the mask names exactly one. If `fxLanes` finds a Local FX of the chosen family on a selected head, it asks **Replace · Stack**, starting on Replace. The segment and its wording are one shared component, lifted out of `AddFx`, which the FX editor lane uses too. A tap starts the FX, as `FxPicker`'s press does today. |
| D9 | **Once started, the popover is the live editor**, with **Open in FX editor ⤢** and Done. ⤢ picks the FX (`setPickedFx`), puts the FX tab on *Selection* (`setFxTabView`) and shows the FX table, the move the fixture sheet's tray makes (`FxTray`). That move is lifted into one function both call. The selection is already the editor's subject, so nothing is reselected. |
| D10 | **FX in the other scopes**: a focused Look layer absorbs the FX into the Look and asks no Replace · Stack, as *+ FX* does today; in Output the button is disabled with `useProgrammerAddFx`'s reason. Set's *+ FX…* band and ⌘K's *Add FX on the selection* open the same picker, Replace · Stack included. |
| D11 | **+ Look** sits in row A between Blind and Include. It is the one picker on its Looks (`ApplyPicker`, `library="looks"`), pressed with no targets and no mask, with the lit ring, ⌥-click as values, and it stays open while pressed. It is on every tab. |
| D12 | **The width ladder**, by the row's own width (container queries, as today): **≥ 1100** every word, ⏎ and ⌫ on Set and Release, Locate and Highlight worded; **800–1099** Clear, Blind, Include, Locate and Highlight iconic, while Look, Record, Set, Fan, FX and Release keep their words; **600–799** every control iconic, the mask abbreviated, the filter still a field; **< 600** Look and Include merge into one **+** whose bottom sheet has *Include a cue or Look…* at its top. On a phone the fade drops its chevron (as today), the heads chip counts cells only and loses its × (Deselect is in its menu), the mask is an icon, Locate and Highlight move into the heads chip's menu, and Set, FX and Release ▾ open bottom sheets. **Short** (≤ 500 px tall) folds rows A and B onto one line, as today, and row C stays out of the flow until something is selected. Nothing in row A goes behind a kebab at any width. |
| D13 | **⌘K gains two groups, on the programmer route only**, listed first there. *Programmer*: Add a Look… (a sub-page of Looks), Include a cue or Look…, Record…, Record into *the included cue*, Record a new Look…, A new palette from the selection…, Update *source*, Revert to *source*, Clear the programmer, Blind on / off, Programmer fade (Snap · 0.5 s · 1 s · 2 s · 3 s), Show the *Values · Stack · FX · Scenery* table, Show Output / Local. *Selection*: Set the selected cells, Apply a palette… (a sub-page of the palettes that fit), Add FX on the selection…, Fan across the selection…, Release the selected cells, Release the whole heads, the subsets of D4, Mask (All · Intensity · Colour · Position · Beam), Locate, Set locate from current, Highlight, Follow the desk selection / This window's own, Deselect. Every item names what it will act on (*6 heads · Colour*, *over 1 s*) and is missing, not disabled, when it cannot act. |
| D14 | **Words.** Look, FX, Set, Fan, Release, Include, Record: the glossary's. The subsets use the busk band's labels. The menu's heading is *In rig order*, not the boards' placeholder *Narrow*. |

The plan adds three of its own:

- **P1 — Three sessions, three stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. Session 2 needs session 1's row
  C, and session 3 needs both sessions' names. Each PR targets the previous session's branch until
  that one merges, then `main`.
- **P2 — Frontend only.** No route, frame, table or sync change. If a session finds it needs one,
  that is a finding to raise, not a change to slip in.
- **P3 — The sheet kit stays as it is for every other sheet.** Where the programmer's row needs
  something the kit does not offer, add an optional slot that no other sheet passes; don't fork the
  kit.

## 3. The model

Row C becomes three clusters over the same selection model. Nothing about what a selection *is*
changes: rows and cells are still one selection (§"One selection, two shapes"), the mask is still the
pill's (§"The desk selection has a mask"), and every press still goes through `usePress` or
`FxPicker`'s press arm. What changes is where the doors sit:

| Door | Where | Needs a selection | Lands on |
|---|---|---|---|
| + Look | Row A, every tab | no | the programmer stack (a Look layer), or refused in a focused Look |
| Set → palettes | Row C, Values | yes | the selection under its mask, or into a focused Look |
| FX | Row C, Values | yes | one Local FX, or absorbed into a focused Look |
| Include | Row A, every tab | no | the source |
| Record ▾ → A new palette | Row A, every tab | yes | a new palette in the library |

## 4. UX

The boards are the authority on layout and copy: `Main` (interactive), the width boards, and the
open states — `SetPopup`, `SetRows`, `FxPicker`, `FxStarted`, `SelMenu`, `ReleaseMenu`, `LookPicker`,
`RecordMenu` — and `Command` for ⌘K. Two things the boards do not show:

- **Touch targets**: the phone's bottom sheets use the finger sizes the fixture sheet's phone host
  uses (44 px rows, 40 px chips).
- **Focus**: each popover takes focus in `onOpenAutoFocus`, as the cell editors do
  (`useEditorKeyboard`); Escape closes it and returns focus to its button.

## 5. Implementation — three sessions

Each session is one PR, stacked on the one before. Each is run by `/next-session`, which cuts the
branch, records the previous session's PR and head SHA in the table below, and hands the session to
`/verified-ship`. A session ends with `npm run check:quick` green, the docs it names written, and its
row here marked *in review* with its branch. It also runs the full `npm run check` locally, because
each session deletes or rewrites a test file another suite imports from. A session's PR is titled
*Programmer toolbar: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | Row C: three clusters, the heads chip, Set on rows, Release, no strip | merged | claude/amazing-wozniak-7r87f8 | [#283](https://github.com/cjcormack/lighting7/pull/283) | f11be5db |
| 2 | + Look in row A, and FX on row C with Replace · Stack | merged | claude/programmer-toolbar-s2 | [#285](https://github.com/cjcormack/lighting7/pull/285) | 637d6ae0 |
| 3 | ⌘K's Programmer and Selection groups | in review | claude/jolly-albattani-gi8f0u | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — Row C: three clusters, the heads chip, Set on rows, Release, no strip

- **The row (D3, D12):** the programmer's row C composed in `programmer/SelectionBar.tsx` from the
  kit's pieces, with a slot added to `sheet/SelectionBar.tsx` only if one is needed (P3).
  `FixturesListContainer`'s `selectionControl` / `selectionActions` / `releaseControl` get a programmer
  arm, so the patch list's `SelectionToolbar` is unchanged. Locate ▾ and Highlight move to the end,
  and into the heads chip's menu below 600 px.
- **The heads chip (D4, D5):** a new `programmer/HeadsChip.tsx`. The busk band's `CellsMenu` and
  `StepButtons` are lifted into one shared component, so the band and the chip send the same
  `SubselectMode`s, the band's tests unchanged. Unlinked, the programmer runs the subset over the
  list's own selection; find how the list's selection takes `lib/cellsSubSelection.ts`'s result
  rather than assuming the busk copy's path fits. *Select by colour* reuses `SelectByColour`. The
  follow toggle uses `unlinkFromDeskNow` / the relink `DeskChip` presses today.
- **Set on rows (D6):** the attribute choice selects the rows' cells in that family's column through
  the marquee store, then opens the popup through `toggleCellEditor`'s path.
- **Release (D7):** `ReleaseTargetsButton` gains the split and the two arms; the cell Clear button
  leaves `CellSelectionActions` on the programmer only (the cue sheet, the look sheet and the other
  hosts keep theirs).
- **The strip (D2):** `PaletteStrip.tsx` and `PaletteStrip.test.tsx` deleted, with whatever only they
  used (check `lib/paletteRecents.ts`' other readers: the bands and `ApplyPicker` use it). Record ▾
  gains *A new palette* over `NewPaletteFromSelectionSheet`, its pair read through the marquee store
  the way `useWindowPressPair` reads it, since row A sits outside the grid.
- **Measure (D12):** in Chromium (Playwright is installed) at 1440, 1100, 900, 700 and 390 wide and at
  852 × 393, against a desk with a patch. Record each row's measured width and the narrowest it fits
  in this plan's §9, beside the boards' estimates, and fix the ladder where the estimates were wrong.
- **Tests:** `programmer/SelectionBar.test.tsx` rewritten for the three clusters and each width's
  arm; `HeadsChip` (each subset sends its mode following, runs the mirror unlinked, the follow item
  toggles, × and Deselect deselect); Set on rows opens on the chosen family with the cells selected;
  Release's face and both arms per scope; Record ▾'s *A new palette* opens the sheet with the
  selection and is disabled without one; the busk band's subset tests still pass through the lifted
  component; the other sheets' selection-bar tests unchanged; `shortViewport.test.ts`.
- **Docs:** `frontend/CLAUDE.md` §"Applying to a selection" (the strip's door gone, Set's popup and
  Record ▾ named), §"The programmer's scoped grid" and §"Sheet kit" where they describe row C, the
  desk-follow paragraph's line about the programmer's chip (D5), and every other mention of
  `PaletteStrip` (`grep -rn PaletteStrip frontend docs`); `docs/plans/followups.md` line 2526's
  reference to the strip.
- **What the code said, recorded at the session** (where this section was wrong or silent):
  - *Set is still refused by the scope.* D6's "never drawn disabled" is about the selection's
    shape; in Output and on a focused palette layer Set stays disabled with `cellActionCopy`'s
    reason, since the keyboard refuses ⏎ there and a button must not promise what the key refuses.
  - *The unlinked subset* runs `cellsSubSelection.ts` over the list's own rows, read as targets with
    the bridge's `selectedRowTargets` and back with its `rowIdsForTargets` (`useListSelectionWrites`),
    not over the busk view's `sessionStorage` copy, which the programmer does not use.
  - *The phone arm is measured, not a class.* Below 600 the row changes components (bottom sheets,
    the chip's count and menu), which a container query cannot do, so the bar measures itself
    (`useContainerBand`) and hands the answer down (`ToolbarPhoneContext`); every other rung is a
    container class (`rowCFolds.ts`).
  - *Fan's word* is `SpreadPanel`'s `WORD_CLASS`, which folds with the strip's 1100 rung; it gained
    an optional `wordClass` so row C can keep it to 800 (P3: no other host passes it).
  - *The heads chip counts cells alone under 640 of the row*, not only on a phone (§9's band).
  - *Record ▾ opens on an empty programmer* (off only in a venue), so *A new palette* is reachable
    with a selection and nothing recorded; its Record destinations are disabled there instead.

### Session 2 — + Look in row A, and FX on row C with Replace · Stack

- **+ Look (D11, D12):** in `ProgrammerActionBar.tsx` between Blind and Include, opening `ApplyPicker`
  on its Looks with no targets and no mask. Below 600 px one **+** opens a bottom sheet with Include on
  top and the Looks under it; row A's verbs stay at today's 247 px.
- **Replace · Stack (D8):** lifted out of `FxEditorSelection.tsx`'s `AddFx` into
  `components/fx/ReplaceStack.tsx`, with a hook that answers "does the window's pair hold a Local FX
  of this family" from `fxEditor/fxEditorList.ts`'s `fxLanes`, so the lane and the new doors read one
  rule. The lane uses the lifted component.
- **FX on row C (D8–D10):** a new `programmer/ProgrammerAddFxButton.tsx` over `useProgrammerAddFx`'s
  offer, opening the picker as a popover (a bottom sheet below 600 px), on the mask's family, with the
  segment when the hook says so and `press.stack` from it. Once started it shows the live editor
  (`FxEditorPopover`'s body), with *Open in FX editor ⤢* and Done. `openInFxEditor(fxId)` is lifted
  out of `FxTray` and used by both. `FxBand`'s *+ FX…* and `ProgrammerAddFxDoor` get the segment too.
- **Tests:** + Look presses a Look with no targets and no mask, stays open, rings what is on, and
  merges with Include below 600 px; the FX button opens on the mask's family, asks Replace · Stack
  only when the hook says so, sends `stack: true` on Stack and not on Replace, is disabled in
  Output with the reason, and absorbs in a focused Look without the segment; ⤢ sets the picked FX,
  the *Selection* view and the FX table; the lane's existing tests pass through the lifted component;
  `FxBand`'s *+ FX…* and ⌘K's door ask the same question.
- **Docs:** `frontend/CLAUDE.md` §"Applying to a selection" (the bare FX's doors and Replace · Stack),
  the fixture sheet's §"The live editor" paragraph naming the doors, and §"The programmer's tables"
  where it names *+ Look or palette* and *+ FX*.
- **What the code said, recorded at the session** (where this section was wrong or silent):
  - *The live editor is the picker's own.* The FX arm (`ApplyPicker`'s `EffectsArm`) already drew
    `FxEditor` after *Edit <name>*, and absorbs into a focused Look when its session ends; the doors
    turn it on at the start (`editOnStart`) rather than mounting `FxEditorPopover`'s `SplitFxEditor`.
    An FX just started on the selection paints exactly the selection, so there is nothing to split.
  - *Replace · Stack asks by where the FX lands.* The desk replaces in the family of the attribute
    the FX drives (a Pulse on `uv` is Colour), not the library family `FxPicker`'s segment files it
    under, so the picker reports the attributes its listed FX would land on (`onLanding`), draws the
    segment in a slot under its families (`underFamilies`) and takes the choice per landing attribute
    (`press.stackOn`). The rule is `localHolderIn` over `fxLanes` from the running list alone
    (`useLocalFxHolder`), as the lane reads it before the preview answers. It is asked only before the
    session's first start: every later tap swaps that FX, which keeps how it started.
  - *Every FX door on the programmer page asks, and none elsewhere.* The picker asks wherever the
    programmer's scope is provided — row C's FX, ⌘K's door, the FX table's *+ FX*, a band's *+ FX …*,
    the palettes' *FX…* — so the shared `FxBand` asks on the programmer and not in the busk view's
    family tabs, which §7 leaves as they were. Only row C and ⌘K turn into the editor on a start (the
    sheet's `editor`), and *‹ Swap* comes back to the list kept mounted under the editor.
  - *The sheets follow the viewport.* `ApplyPicker` takes `useEditorForm`'s form, so + Look's and
    FX's bottom sheets are a phone's, not a 600px row's; a narrow row on a desk keeps the popover.
  - *Row A's word ladder* (D12) was row A's to apply here: Clear, Blind and Include lose their
    words below 1100 of the row, Look and Record below 800. §9 has the widths.
  - *FX did not fit D12's row C ladder.* Measured with FX among the verbs, a marquee's row C needed
    852px worded (so 800–851 overflowed), 656px in the 600–799 arm with the heads (so 640–655
    overflowed) and 389px on a phone (so 360 and 375 clipped). Set, Fan, FX and Release now keep their
    words from 860, the heads chip counts cells alone under 660, and the phone arm closes the verbs
    up — no divider, a 4px gap, 8px padding. §9 has the widths.

### Session 3 — ⌘K's Programmer and Selection groups

- **Doors (D13):** a registry beside `programmer/addFxDoor.ts`, generalised to every command: the
  programmer registers one handler per command while mounted, and an offer (`label`, `hint`, whether
  it can act) that ⌘K reads when it opens, so `Layout` subscribes to nothing at selection rate.
  `addFxDoor.ts` becomes one entry in it.
- **The groups:** `CommandPalette.tsx` draws *Programmer* and *Selection* first on the programmer
  route, each item missing when its offer says it cannot act. *Add a Look…* and *Apply a palette…*
  are sub-pages, as *Apply FX* is today. Each item's hint is its offer's.
- **Tests:** there is no `CommandPalette` test today; add one. The groups appear on the programmer
  route and not elsewhere; each item calls its door; an item that cannot act is absent; the two
  sub-pages list Looks and the fitting palettes, and pressing one presses it; hints name the counts.
- **Docs:** `frontend/CLAUDE.md` has no ⌘K section: add a short one beside §"The programmer's
  keyboard" (the door registry, the two groups, the offers), and update the live editor paragraph's
  sentence on ⌘K's *On the selection* (`addFxDoor.ts`).
- **What the code said, recorded at the session** (where this section was wrong or silent):
  - *Each owner registers its own door.* "The programmer registers one handler per command" reads as
    one place; the commands' state lives in six (row A, `AddLookAndInclude`, the source box,
    `ProgrammerTables`, the scope band, the list's container), so each registers what it owns
    (`commandDoors.ts`, `useCommandDoor`), read in `COMMAND_DOORS`' order. `addFxDoor.ts` is
    deleted rather than kept beside it: `ProgrammerAddFxDoor` is the `fx` door, reading its offer
    from the marquee store and the desk's selection without subscribing (`readProgrammerFxPair`).
  - *The sub-pages' doors are renderless components.* *Add a Look…* and *Apply a palette…* press
    through `usePress`, whose mutation hooks row A's + Look button cannot carry without every test of
    row A gaining a store, so they are `ProgrammerLookDoor` and `ProgrammerPaletteDoor`, which the
    tables' frame mounts beside `ProgrammerAddFxDoor`. A press from a sub-page closes ⌘K, as every
    other ⌘K action does; the picker is still the way to audition several.
  - *A multi-valued row is one command per value.* *Programmer fade (Snap · 0.5 s · …)*, *Show the …
    table*, *Show Output / Local*, the subsets and *Mask (All · Intensity · …)* are an item each, the
    one already chosen missing, since it cannot act. The steps read *Previous* and *Next*; Highlight
    latches (*Stop highlighting*), as the phone's menu does, since a command cannot be held.
  - *Set and Fan bring Values back.* The grid is hidden and inert under the other tables, so the two
    commands that open on it switch the table first; Set goes through Set-on-rows' retry, and Fan
    through a new `{open: true}` seed (`SpreadSeed`), so a Fan its trigger would refuse says the
    trigger's reason instead of opening.
  - *⌘K kept a highlight whose row had gone.* After a sub-page opened, cmdk highlighted nothing and
    ⏎ ran nothing (found on the desk; jsdom's timing hid it), so the palette now holds the highlight
    and moves it to a new page's first row once drawn — which fixes *Apply FX*'s sub-page too.
  - *The picker's and Set's fit contexts named `templates`.* `FxFitContext` reads `palettes`, so
    neither the picker nor Set's Effects band narrowed a Palette Steps palette by the cell's
    attribute. Both pass `palettes` now, and the picker and *Apply a palette…* share the mask split
    (`splitByMask`), so the three offer one set.
  - *Add FX's hint names the heads and the mask, not Replace · Stack.* Whether a Local FX of the
    family is on a selected head depends on the family, which is chosen in the picker; the picker
    asks there.

## 6. Migration

None. Nothing stored changes. A window's per-tab state (the follow flag, the table, the FX tab's
view) is read as it is today.

## 7. Explicitly out of scope

- Any change to the busk view's band, the other sheets' selection bars, or the FX editor beyond the
  lifted Replace · Stack.
- One-tap palette presses on the programmer page: Set (two clicks) and the busk view (one) cover them.
- MIDI bindings for the new commands.
- A ⌘K group for the programmer on other routes.

## 8. Follow-ups

None fired. The changes add no WS command (`FU-AUTH-WS-PER-MESSAGE`), and no new consumer of
fixture or group attribute lookup. Each session still reads `followups.md`'s index for a gate its
change might fire, per CLAUDE.md.

## 9. Verification

- **Session 1:** on a desk with a patch, select cells and rows on a 1440-wide window and on an iPad
  and a phone; row C reads in its three clusters at each width with nothing clipped; each subset
  narrows the selection on the rig as the busk band does; Set on rows lands the chosen family's
  popup; Release's face and arms release what they say; a second window following the desk sees
  each change. Measured widths (2026-10-25, Chromium, a desk with 4 hexes, 2 MAC 250s, a Shehds bar
  and a par; a marquee over 4 heads' Dimmer and Colour, so the mask reads *Intensity · Colour*):

  | Window | Row C | Arm | Content needs | Overflow |
  |---|---|---|---|---|
  | 1440 × 900 | 1200 | every word, ⏎ ⌫ | 942 (chip 183, what 316, where 233) | none |
  | 1100 × 800 | 860 | Locate, Highlight iconic | 782 | none |
  | 900 × 800 | 660 | iconic, mask *Int · Col* | 610 → 554 with the 640 fold | none |
  | 700 × 800 | 700 | iconic | 610 | none |
  | 605 × 800 | 605 | iconic, cells only | 554 | none |
  | 852 × 393 | 612 | iconic, cells only (short) | 561 | none |
  | 390 × 844 | 390 | phone: chip, mask glyph, Set · Fan · Release ▾ | 342 | none |

  The boards' ladder held but for one band: the 600–799 arm needed 610px, so a row of 600–609
  overflowed and the 852 × 393 phone's 612 had 2px to spare. Under 640 of the row the heads chip
  now counts cells alone, as on a phone, which brings that arm to 554.
- **Session 2:** + Look lights the Look and lands it whole; FX on heads already running a Colour FX
  asks Replace · Stack, and Stack leaves both running at the family's starting blend; ⤢ opens the
  FX editor with the FX picked and its graph drawing. Checked 2026-10-25 in Chromium on a desk with
  4 hexes, two recorded Looks and a Local Rainbow Cycle on Hex 1–2: + Look's pad lit and the picker
  stayed open; FX on Hex 1–2's rows, on Colour, said *RainbowCycle on all 2 heads already* (since worded *on both heads*), Stack
  started Colour Wave beside it and Replace a Colour Fade in place of both; ⤢ landed on
  `?table=fx`, *Selection*; Done closed the picker; Output disabled FX with its reason. Row A,
  `main` against this session, no overflow at any width:

  | Window | Row A before | Row A after | Words after |
  |---|---|---|---|
  | 1440 × 900 | 483 | 565 | every word |
  | 1100 × 800 | 483 | 422 | Look, Record |
  | 900 × 800 | 285 | 329 | none |
  | 700 × 800 | 285 | 329 | none |
  | 390 × 844 | 247 | 247 | none (**+**) |
  | 852 × 393 | 227 | 227 | none (**+**, short) |

  Row C with FX, the same desk, Hex 1–4's rows (*4 heads*) and a marquee over their Dimmer and
  Colour (*4 heads · 8 cells*, mask *Intensity · Colour*), content needed against the row, after the
  ladder fix — nothing clipped at any width:

  | Window | Row C | Rows need | Marquee needs | Verbs |
  |---|---|---|---|---|
  | 1440 × 900 | 1200 | 838 | 1012 | worded, ⏎ ⌫ |
  | 1100 × 800 | 860 | 720 | 852 | worded |
  | 1060 × 800 | 820 | 579 | 710 | iconic, mask whole |
  | 900 × 800 | 660 | 579 | 656 | iconic |
  | 880 × 800 | 640 | 579 | 600 | iconic, cells only |
  | 605 × 800 | 605 | 579 | 600 | iconic, cells only |
  | 852 × 393 | 612 | 579 | 600 | iconic, cells only (short) |
  | 390 × 844 | 390 | 358 | 349 | phone |
  | 360 × 780 | 360 | 358 | 349 | phone |
- **Session 3:** every command in both groups does what its row says, from ⌘K, on the desk. Checked
  2026-10-25 in Chromium (Playwright, 1440 × 900) on a desk with 4 hexes and two palettes, through
  the working tree's dev server: with nothing selected the groups offered Programmer's verbs and the
  follow toggle alone; over four rows every Selection command was listed with *4 heads*; *Apply a
  palette…* listed both palettes, ⏎ pressed Amber wash as a layer and the next open said *on*;
  *Record a new Look…*, *Record…* (into a new stack), *A new palette from the selection…* and
  *Include…* opened their sheets; *Clear* emptied the programmer and left ⌘K without it; *Add a
  Look…* pressed the recorded Look and lit it; Odd narrowed four heads to two; *Mask: Colour* set the
  pill and the mask rows then said *now Colour*; Fan opened its panel; *Add FX…* opened the picker on
  4 heads; over a two-cell marquee *Set* opened the Dimmer editor, from the FX table too, landing back
  on `?table=values`; *Show Output* pressed the pill and Set left ⌘K there; Blind went on and off at
  the fade ⌘K had set; Locate, Highlight, *Set locate from current*, Release, the follow toggle both
  ways and Deselect did as row C's controls do; with a recorded cue included and one change made,
  *Update 1 Cue 1* opened Update and *Revert* cleared and included it again.
