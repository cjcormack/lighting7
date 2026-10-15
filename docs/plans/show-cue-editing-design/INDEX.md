# Editing a cue in the Show view — design record

Source: design session R2 of [`../venue-visit-plan.md`](../venue-visit-plan.md), 2026-10-14. On the
first evening in the hall, cues were read in the Show view and changed somewhere else: the card's
*Edit in Programmer* Includes the cue, which lights it unless Blind was set first, and the table view
offers no edit at all. R2 asked for direct editing of a cue's values, Looks and effects in tables that
behave like the programmer's and never send output, how that looks in the card and table views, and
one action set for both. It follows R1, [`../cue-model-plan.md`](../cue-model-plan.md), and draws
against that model: shared cues, stacks of entries, delete as its own act.

**Status: approved 2026-10-15.** The implementation plan is
[`../show-cue-editing-plan.md`](../show-cue-editing-plan.md). A one-page copy for human readers is
[`show-cue-editing.html`](show-cue-editing.html), with a live copy at
<https://claude.ai/artifact/N3Acyq477Lj9i9r4p6YWzm>, private to Chris and a convenience; the files
here are the authority. Where a board and the plan disagree, the plan wins on behaviour and the
boards on layout and copy, as with every record in this directory.

## Asked and answered

Chris answered these on 2026-10-14, in three rounds of four. The plan numbers them D1–D17.

| Question | Answer |
| --- | --- |
| What does the cue's Values table show and edit? | **One grid of composed values, edits writing the cue's own rows.** Each cell marked own or by its Look; ⌫ removes the own row. The programmer's scope band, and own rows only, were declined. |
| What can be changed about a Look layer from inside a cue? | **The layer, not the Look.** *Open Look* goes to the library. Editing the Look in place, and read-only layers, were declined. |
| A live cue edited in place? | **Marked *Edited since GO*, with an explicit *Update stage*.** "Mark it, send nothing" and "read-only while live" were declined. |
| Where is the table view's editor? | **A bottom pane** for the one selected row. A right-hand panel, an inline row and "the table opens the card" were declined. |
| Edit and Blind edit on one button? | **A split button whose default follows the show**: Edit while stopped, Blind edit while running. A fixed default and two buttons were declined. |
| Blind does not hold the programmer's effects. What should this plan do? | **Fix Blind**: programmer effects are held while Blind is on. Leaving effects out of Blind edit, and leaving it, were declined. |
| The action set while locked? | **Blind edit works; the rest ask to unlock.** "All ask" and "hidden" were declined. |
| The phone card? | **Actions in a ⋯ menu, no tables.** Tables on the phone, and no change, were declined. |
| How does an edit save? | **As it goes, with Undo.** A draft with Save / Revert was declined. |
| What does *Update stage* send? | **Rows and effects at the cue's fade.** Rows only, snapped or faded, were declined. |
| Where does Duplicate put the copy? | **At the end of the stack** (venue-visit S3), over R1 §3.8's "after the source". |
| Should the Stage view draw the cue in edit? | **Yes, a vis source**, *Cue in edit*. "A follow-up" and "no" were declined. |
| Which fixtures does the grid list? | **The cue's, plus *+ Fixtures…* and *Whole rig*.** The whole rig filtered was declined. |
| Cue properties? | **Folded into the editor.** Keeping the sheet was declined. |
| The way back from the programmer? | ***← Show* in the source box.** The view switcher alone was declined. |
| Adding an effect from the FX table? | **The picker, in draft.** "Through the programmer only" was declined. |

## What was found in the code and on the desk

Read from `main` at `a6e4053`, 2026-10-14, by two surveys (the backend's cue routes and what reaches
the stage; the programmer's editing surfaces) and on the desk running Sleeping Beauty. The plan's §1
holds the findings with file:line; in short:

- **A stored cue edit sends nothing.** Only Record, Update and a Look or palette contents edit
  republish a live cue, so a table that writes the cue is silent by construction and an edited live
  cue goes stale. The comments describing "cue-edit live apply" are stale.
- **Blind holds values and layers, not the programmer's effects**, and Include spawns a cue's
  effects in the programmer band. "Edit in Blind" with effects reaches the rig today.
- **A cue write deletes and recreates every child**, so each gets a fresh uuid; PR #140 fixed the
  same for Looks.
- **The programmer's tables take a fourth writer**: `EditorContext` and `useCellWriters` already have
  a record-writing arm (the focused Look layer), `LookStack`'s handlers are host-agnostic, and
  `FxEffectFields` edits a draft. Mounting `FixturesListContainer` as it is would send output (the
  desk bridge, Release, Locate, the palette strip).
- **`/cues/{id}/cooked` names the winning layer per value**, but expands a group row to its members.
- **On the desk**, Act 1 Q1's card runs to values, five layers and more than a dozen tracked scenery
  rows before its four buttons. On a 390 px phone the venue-behind banner fills the screen (a
  follow-up, outside this plan).

## What the boards propose, in one paragraph each

**One editor, two hosts.** An open card holds the action set directly under its row, then the editor:
a header row and the tabs *Values · Looks · FX · Scenery · Events · Cue*. The table view keeps its
sheet and puts the same editor in a bottom pane for the one selected row, with a grip for its height,
and the action set on the selection bar. Cue properties folds into the header and the Cue tab.

**The tables are the programmer's, writing the cue.** Values is the programmer's grid over a cue row
store: every cell the value GO lands, edged blue for own and teal for the Look that won, an edit
writing an own row, ⌫ clearing one. Looks is the programmer's layer stack over the cue's layers, with
*Open Look* to change a Look's contents in the library. FX lists the cue's stored effects, each
opening the live editor's fields over the stored effect plus the cue's timing, and *+ Effect* adds
one through the picker without running it.

**One action set, three hosts.** *Edit in programmer ▾* (its face Blind edit while the show runs),
*Load into programmer*, *Record…*, then *Duplicate*, *Remove from stack*, *Delete…* and *⋯*. Locked,
Blind edit works and the rest ask to unlock. The phone card carries the same list in a ⋯ menu. The
programmer gains *← Show* back to the cue.

**A live cue edited in place says so**, *Edited since GO*, and offers *Update stage*, the one path
from the Show view to the stage, confirmed with the counts it will change.

## Format

Each `*.dc.html` is one dark artboard, generated by `gen.mjs` (`node gen.mjs`) from a stylesheet in
the busk-chrome boards' vocabulary, restated in the generator so each board renders standalone;
`canvas.json` is the layout manifest. Numbered amber markers are keyed in each board's notes strip.
`show-cue-editing.html` is the one-page copy published as the live artifact, hand-written, theme-aware,
with its mock-ups drawn dark in both themes as the desk is.

| File | Draws |
| --- | --- |
| `Card.dc.html` | Act 1 in the card view, the show running and unlocked: Q1 open with the action set (Blind edit's face), the tabs and the Values grid, a cell editor over two own cells, own and Look edges, a cell override, a group own row; Q4 live and edited since GO with *Update stage…*; Q5 next. |
| `Table.dc.html` | Act 1 in the table view, stopped: the action set on the selection bar, Q1 selected, the grip and the bottom pane on Looks (enable, amount, stomp, mask, targets from the selection, *Open Look*, a Look's effect read-only). |
| `Tabs.dc.html` | The FX tab with an effect open (speed, master, Replace / Within, min and max, the cue's delay · interval · random) and the picker in draft; the Scenery and Cue tabs; the editor header with the entry's fields, the shared chip and the save state. |
| `Actions.dc.html` | The strip stopped, running, locked and over three rows; the split and ⋯ menus; R1's delete confirm; the *Update stage* confirm; the phone card with its ⋯ open; the programmer after Blind edit with *← Show* and the reporters' wording. |

## Names on the boards

Sleeping Beauty, Act 1 and Act 2, the cues, Looks, fixtures and scenery are the desk's
(`lighting7-tch-2026-sleeping-beauty`, on the Commemoration Hall's venue). Q4 being live and edited,
the own values, the Pulse and Colour Cycle effects and the delete confirm's counts are illustrative.
