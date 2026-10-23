# FX editor: design record

Source: a design session on 2026-10-23. The brief, from Chris: a new FX editor for the programmer,
to build stacked FX and to see how an FX's attributes interact, for example as a timeline of how
colour and intensity change on each head and element; and anything else that would make FX
programming simpler while keeping its power. It is to be built after the terminology plan
([`../terminology-plan.md`](../terminology-plan.md)), so every word here is that plan's.

**Status: draft for review, 2026-10-23.** Nothing is agreed. The proposals are D1–D15 in
[`../fx-editor-plan.md`](../fx-editor-plan.md), and its §11 asks fifteen questions, each with a
recommendation. Where the plan and the page disagree, the plan wins on behaviour and the page on
layout and copy.

## What was found in the code

- **The live editor edits one FX at a time** (`FxEditor` over `FxEffectFields`), in the tray, a
  popover or a busk row; the programmer's FX tab is a table of every running FX. Nothing draws the
  FX on a selection together.
- **The FX press takes the selected heads off every other Local FX of its family**
  (`routes/programmerEffects.kt`), so there is no door for a second Intensity FX on the same heads.
- **Local FX sit at the bottom of the programmer band** (`routes/lightFx.kt:531`), under every
  programmer layer's FX but the bottom layer's (`fx/ProgrammerLayerStack.kt:1149`), though Local
  values beat every layer. Ties break on creation order; nothing reorders them. A Look's FX store
  no order.
- **Beat and wall-clock FX on one key do not combine**: each tick loop resets and applies in its own
  transaction (`fx/FxEngine.kt:1598`, `:1740`), so the later one wins whatever the blend.
  `docs/fx-engineering.md` says Add and Max compose; they do not.
- **Nothing evaluates an FX off the stage**, and the client never computes an FX's output; the one
  client copy of a desk rule is the spread offsets behind the phase dials (`lib/spreadShape.ts`).
  The phase functions mutate the instance as they read it, and a stateful FX shares one state map
  across its members, so a sampler needs those functions split out pure.

## What the page proposes, in one paragraph each

**The FX tab becomes the FX editor.** *Selection · All*: *All* is today's table; *Selection* is a
list, a graph and an inspector for the FX on the selected heads. The list is in family lanes, top
wins, each ending with what it sits on; Local cards drag to reorder, layer and cue cards sit below
them, read-only where they are today.

**The graph is the desk's.** A preview route samples the stack through the engine's own functions,
for running FX and drafts. Rows are elements in the picked FX's Order; lanes are families; marks
show cycle starts, Segments, Parts, Wings and Runs; a path panel draws movement. The playhead follows
the master.

**Stacking is a choice.** *+ FX* on an occupied lane asks *Replace · Stack*; Stack starts on a blend
that suits the family. Local FX move above layers' FX, apply order is editable and Record keeps it,
and beat and wall-clock FX fold in one pass.

**Simpler, same power.** Shapes (nine named spreads), the offset ladder, hover lights, handles on the
graph, Match, Solo and tempo on the toolbar: each a faster way to set fields that already exist.

## Format

There are no boards. [`fx-editor.html`](fx-editor.html) is one hand-written, theme-aware page with
the desk drawn dark inside it. Its main drawing is live: a small copy of the desk's spread rule
(ported from `lib/spreadShape.ts`, with the terminology plan's Order names) and of the built-in
waveforms (from `src/main/resources/fx/`) runs the graph, so the controls, Shapes, the ladder, Solo,
*+ FX* with *Replace · Stack*, reordering and the cycle handle all work. That copy exists for the page
alone; the editor draws the desk's samples (plan P2).

It is published at <https://claude.ai/artifact/Dj9DW378cSMLMRbSUPp66r>, private to Chris and a convenience; the
files here are the authority. Republish it from this file after a change.

## Names on the page

Hex 1–8, Q12, *Warm*, *Night*, *Slow circle*, the FX on them and every value are illustrative.
