# Show view editing fixes — design record

Source: a request on 2026-11-06 listing six problems with editing a show on the Show view: Duplicate
dropping a cue's follow, the cue sheet's bottom editor opening and closing under a marquee, the
editor appearing with the selection, cue numbers editable one row at a time, arming a cue needing
the lock, and every running show locking whether or not it is in production. A seventh followed:
dragging one of several selected cues should move them all.

**Status: agreed 2026-11-06.** The implementation plan is
[`../show-view-fixes-plan.md`](../show-view-fixes-plan.md). The one-page copy is
[`show-view-fixes.html`](show-view-fixes.html), hand-written and theme-aware, its mock-ups drawn
dark in both themes as the desk is. Its §Decisions holds Q1–Q6.

## Asked and answered

Chris answered these on 2026-11-06, in two rounds.

| Question | Answer |
| --- | --- |
| Where does a duplicated cue land? | **At the end of the stack**, as now; only the follow is new. After its source was declined. |
| Do the copy's FX join the source's runs? | **No, fresh run keys.** A copy at the end has no neighbour in common with its source. |
| Editor pane on or off in a window that never chose? | **On.** |
| Cue number its own column, or sticky? | **Its own column**, after a narrow row head. |
| Decimal renumbers over several cells? | **Whole numbers only** in this change. |
| *In production* default and home? | **Synced with the show; existing shows on, a new show off.** On for all and machine-local were declined. |

## What was found in the code

Read from `main` at `d2f6b9b`, 2026-11-06:

- **Follow belongs to the stack entry** (`cue_stack_entries.auto_advance`, `.auto_advance_delay`),
  and `POST /cues/{id}/duplicate` takes only `{stackId}`, so it cannot know which place was
  duplicated. `CueCopier.copyCue` already copies every column of every table hanging off a cue.
- **The cue sheet's pane is derived from the selection** (`CueSheet.tsx`'s `paneRow`, the Timeline's
  twin), so a marquee crossing from one row to two changes the sheet's height under the pointer.
- **The cue number sits in the kit's first column**, the row selector, so it can never join a cell
  selection; *Renumber…* (`RenumberCuesButton`, `POST …/renumber`) is reachable only from the bar.
- **Arming is a locked gesture only**: a locked click on a card or the Cue column, and *Arm as
  next* drawn only while locked. On a card the grip hides the pip while unlocked.
- **The lock arms whenever a stack runs** (`useEditLock`), and five hosts plus `useHistoryMove`
  each compute the amber wash or the lock state themselves.
- **The sheet kit's row drag moves one row** (`SheetTable`'s `handleDragEnd`), whatever is
  selected; the cue sheet and the Timeline send the whole new order as one `reorder`.

## Format

`show-view-fixes.html` is the record. Names in its mock-ups (Cinderella, Act 1, Q9–Q18) are
illustrative.
