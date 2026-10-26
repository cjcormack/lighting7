# FX editor follow-ups: design record

Source: a design session on 2026-10-26. Chris's brief, after using the FX editor on the rig, named
six problems: the empty state, the subject over several fixtures, misaligned colour cells, graph
controls that are hard to make out, multi-element fixtures drawn as their first element with no room
for names, and no way to run an FX with each fixture's elements alike and the fixtures different.

The engineering half is [`../fx-editor-followups-plan.md`](../fx-editor-followups-plan.md). Where the
plan and the page disagree, the plan wins on behaviour and the page on layout and copy.

## Asked and answered

| Question | Answer |
| --- | --- |
| How should "elements alike, fixtures different" be offered? | **A third element mode on the desk**, MagicQ's *Dup Elements*. A preset over Flat and Segments, and a hint alone, were declined. |
| Its words? | **Over: Fixtures · Elements · Each fixture**, the Fan's word for the same question. MagicQ's *Dup · Include · Use* and keeping *Element mode* were declined. |
| The default for a new FX? | **Fixtures**, as MagicQ's is. Keeping Each fixture was declined. |
| A fixture's own row when it has no attribute of its own? | **The mix of its elements.** Stacked element bands and a label line only were declined. |
| The graph's controls? | **A beat ruler and knobs.** An *Edit on graph* toggle was declined. |

## What other desks do

MagicQ asks how to treat elements whenever an FX lands on multi-element heads: *Dup Elements* (a
head's elements act as one, the same offset; its default), *Include Elements* (the FX spreads over
every element of every head) and *Use Elements* (the FX runs within each head, every head in step).
ETC Eos applies a command on a multicell fixture's channel to the master and every cell alike, and
to cells alone when they are selected.

## The page

[`fx-editor-followups.html`](fx-editor-followups.html) is one theme-aware page with the desk drawn
dark inside it. Its graph board is live: a small model of Colour Cycle over four three-element
fixtures, switchable between the three Over modes and between a cut and a fade, with the fixture rows
drawn as the mix of their elements. That model exists for the page alone; the editor draws the desk's
samples.

It is published at <https://claude.ai/artifact/3DQLpbp3nC3eqsvqRXfd1F>, private to Chris and a
convenience; the files here are the authority. Republish it from this file after a change.

## Names on the page

The four Shehds, their names, the FX on them and every value are illustrative.
