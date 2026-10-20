# A stack's timeline: design record

Source: a design session on 2026-10-20. The brief, from Chris: a timeline view for a stack, to see
and edit when Looks, palettes and the rest are applied to its cues and when they are not, in a
table, so that whether a Look is in a cue can be changed in one place. The session surveyed what
other desks do, read the cue model, and settled the design in four rounds of questions.

**Status: approved 2026-10-20.** Every call is decided: D1–D11 in the first rounds, C1–C7 in a
second, each as recommended, all on the `Spec` board. The implementation plan is
[`../stack-timeline-plan.md`](../stack-timeline-plan.md). Where a board and a later plan disagree, the
plan wins on behaviour and the boards on layout and copy, as with every record in this directory.

## Asked and answered

| Question | Answer |
| --- | --- |
| Which way round should the grid go? | **Cues as rows** (D1), with the cue's settings at the left. Cues as columns, a left-to-right timeline, was declined. |
| Where should it live? | **A mode of the stack view** (D2): *Cards · Table · Timeline*, sharing the Table's rows, bottom pane, edit lock and row drag. Columns added to the Table, and a page of its own, were declined. |
| What is a column? | **One per record** (D3), a palette's column being the palette on one target. Looks on/off with palettes grouped by family, and Looks only, were declined. |
| A Look across several cues, when lighting does not track? | **Per-cue cells with range gestures** (D4): adjacent cells draw as one bar. Adding tracking to layers was declined. |
| Turning a palette cell on? | **The press rule** (D5): other palettes of its family come off the same heads in that cue. A plain add was declined. |
| How should effects appear? | **Columns, like layers** (D7). A summary cell was declined. |
| Should an effect bar continue across its GOs? | **Yes, through a run key** (D7) on a cue's ad-hoc effects that GO adopts on. Restarting each cue, and steering runs into Looks, were declined. |
| A parameter change partway through a run? | **Edited in place, phase kept** (D8). A crossfade was declined. |
| How should scenery appear? | **A column per element** (D9): changes as cells, tracking as a line. A summary cell was declined. |
| What else should show? | **Timing and live state, effects, scenery** (D10). The cue's own values, events and speed masters were left out. |
| What belongs in the first version? | **Selection and family filters, Replace across a range, a settings popover per cell** (D11). An unused-record picker with use counts was left out. |
| Columns per target, or per target set? | **Per target** (D3): a cell is exactly a press of that record on that target. |

The second round, C1–C7, took every recommendation:

| Question | Answer |
| --- | --- |
| Its name? | **Timeline** (C1). *Track sheet* and *Layers* were declined. |
| When does the bottom pane open? | **On one selected cell**, on its cue at the column's tab (C2); a range over several cues folds it. Opening only on a selected row was declined. |
| How does a gesture over several cues write? | **One stack route, one transaction, one Undo** (C3). One write per cue was declined. |
| Run keys on a duplicated or copied cue? | **A fresh key** (C4). Keeping it was declined. |
| Effects stored before the run key? | **No migration** (C5): they draw as broken runs and *Join run* fixes them. Joining identical neighbours at load was declined. |
| Narrow screens? | **Timeline from 1100 px** (C6); below that the Table stays. A scrolled tablet grid was declined. |
| Column order? | **First use, grouped by kind, reordered per window** (C7), never stored. A stored order per stack was declined. |

Two defaults were stated and not objected to (D6): a new cell copies its settings from the nearest
cell above in its column, else below, else the press defaults; and every cell shows its layer's rank,
a new layer going on top.

## What was found in the code and on other desks

- **Lighting does not track.** A cue is a complete state; only scenery tracks
  (`show/SceneryResolver.kt`). "Lavender in Q2 to Q4" is three separate layers.
- **A GO carries an effect over only on its stored row's uuid and settings**
  (`fx/CueHolds.kt`, `continues`). A Look's effect continues across cues that layer it; an ad-hoc
  effect copied into the next cue is a new row and restarts. That is why D7 needs a run key.
- **The press already works on a stored cue** (`POST /cues/{cid}/layers/press`, `fx/LayerPress.kt`),
  per target, sending nothing.
- **The stack list carries counts only** (`layerCount`, `adHocEffectCount` on
  `CueStackCueEntry`), so the grid needs a read of its own.
- **Every desk with a grid puts cues as rows** (Eos, grandMA2/3, MagicQ, Titan). grandMA3's
  recipes, cooked into cues, are the nearest thing to this desk's layers; Lightkey's cues built from
  presets are the nearest model. No desk draws a cue × preset grid. The survey, with what each desk
  lends this design, is on the `Spec` board.

## What the boards propose, in one paragraph each

**A third mode of the stack view.** *Timeline* sits beside Cards and Table and keeps the Table's
rows, so markers stay divider rows and the cue editor stays in the bottom pane. Cue, name, fade and
follow come first, then four column groups: Looks, Palettes, Effects, Scenery.

**A cell is a press.** A filled cell is that cue's own layer, showing its rank and any non-default
setting. Adjacent cells draw as one bar. Click toggles a cell; dragging a bar's end, Fill ↓ and a
range's On and Off write many cells as one gesture, one Undo. *Replace* swaps one record for another
over a range, and a popover edits one layer's settings.

**Effect runs mean what they show.** A run key on `cue_ad_hoc_effects` lets a GO hand a running
effect to the next cue, phase kept, and apply a changed parameter in place. Effects stored before
the key draw as broken runs, joined by hand.

**One route for every write.** `POST /cue-stacks/{sid}/timeline/edit` takes a batch of ops, all or
nothing, and sends nothing to the stage; a live cue it touches is marked edited since GO.

## Format

Each `*.dc.html` is one dark artboard, generated by `gen.mjs` (`node gen.mjs` in this directory
rewrites all four and `canvas.json`); change a value there rather than in a board. The stylesheet
restates show-cue-editing-design's, so each board renders standalone. Numbered amber markers on the
two drawn boards are keyed in each board's notes strip.

| File | Frame | Draws |
| --- | --- | --- |
| [`Spec.dc.html`](Spec.dc.html) | 1240 wide | The brief, the desk survey and what each desk lends, what the code says, decisions D1–D11 and the second round's C1–C7. |
| [`Timeline.dc.html`](Timeline.dc.html) | 1440 wide | Act 1 in Timeline mode, running and unlocked: the view switcher, the filter row, a three-cell selection, Look and palette runs with ranks and setting glyphs, an effect run with an in-place change (◆) and a break (↺), scenery changes and tracking lines, Q9 live with its fade and *Edited*, Q10 next. |
| [`Editing.dc.html`](Editing.dc.html) | 1440 wide | Nine panels: one cell as a press with its knock-on, dragging a run's end, a range and its verbs, *Replace* across a range, one layer's settings, an effect run, a scenery cell, filters and *+ Column*, a live cue and the locked state. |
| [`Model.dc.html`](Model.dc.html) | 1240 wide | What a column is per kind, the run key and the three GO cases, the read endpoint's shape, the frames that keep it live, the write route's ops, and size, tests and MCP. |

`canvas.json` lays them out: Spec and Model on the first row, Timeline and Editing below.

[`stack-timeline.html`](stack-timeline.html) is a one-page copy for human readers, hand-written and
theme-aware, embedding the Timeline and Editing boards scaled to fit. It is published at
<https://claude.ai/artifact/NwW5yWvAqzzincmMnwm8EY>, private to Chris and a convenience; the files
here are the authority. Republish it from this file after regenerating the boards.

## Names on the boards

The show, Act 1's first cues, the four Looks, the fixtures and the scenery elements are the desk's
(`lighting7-tch-2026-sleeping-beauty`), as the show-cue-editing record drew them. The other cue
names, which cue holds which layer, the palettes Lavender, Amber and Throne DS, the effects and the
run keys are illustrative.
