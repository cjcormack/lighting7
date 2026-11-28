# Timeline rig columns: design record

Source: a design session on 2026-11-28. Chris asked for improvements to the Show view's Timeline:
*Edit in programmer* not navigating when another window already shows the programmer, columns for
fixtures and groups (one summary cell each, optionally a cell per head) that can be read, dragged to
the next or previous cue, copied and pasted, and edited, copy and paste for the existing cells, and a
UI/UX review of the tab.

The page is [`timeline-rig.html`](timeline-rig.html), live at
<https://claude.ai/artifact/8WFQC4fEU19EQmzfvowPFW> (the checked-in file is the authority). The
engineering half is [`../timeline-rig-plan.md`](../timeline-rig-plan.md). Where the plan and the page
disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| What does a pasted state write? | **The fewest own rows that land the same state**, palette references kept. Always-literal rows, and copying the source's own rows only, were declined. |
| Does a state carry FX? | **FX on exactly that target only.** FX over its group or a selection are named in the toast and not copied. A per-head copy of group FX, and never copying FX, were declined. |
| Where do rig columns live? | **Stored on the stack and synced.** The recommendation, per window like column order, was declined, and so was following the desk selection live. |
| How is a cell edited? | **A popover with the programmer's controls**, with *Edit in programmer* and *Open in pane* as ways out. Pane only, and programmer only, were declined. |
| With a programmer open elsewhere, what does the press do here? | **Stay, with a toast** naming the window and offering *Open here*. Staying silently, and a per-window setting, were declined. |
| Should the Timeline's Undo become the desk's? | **Yes.** |
| Which review items come in? | **All seven:** the context menu, the top handle, the pickers, FX in *+ Column*, the header menu with drag, densities, *Changes only*. |

## The review

Read from the code and its tests on 2026-11-28, not at a desk. The findings and what the plan does
with each are the page's Part 4. Two were left for later, collapsing a kind's band and editing Fade and Follow in place. They are
two of the three follow-ups in the plan's §8; the third comes from the plan's §7.
