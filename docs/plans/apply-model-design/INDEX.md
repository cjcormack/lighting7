# Applying to a selection — design record

Source: design session R3 of [`../venue-visit-plan.md`](../venue-visit-plan.md), 2026-10-15. On the
first evening in the hall, putting a palette, a Look or an effect onto some fixtures went through
seven mechanisms: the palette strip, the picker, busk pads, the add-layer sheet, the FX sheet, the busk
side sheets and Include. Each felt like its own, and it was confusing. R3 asked for one model, a
per-property popup that offers palettes for every family, how the mask, the selection and the layer
relate, and which surfaces fold in and which stay as shortcuts. It started from
[`../../lighting-composition-model.md`](../../lighting-composition-model.md), after venue-visit S1
(palettes reaching cells, `4a1a8fb`). S14 (the applied tables) had not landed, so the rail's rows stand
in for them.

**Status: approved 2026-10-15.** The implementation plan is
[`../apply-model-plan.md`](../apply-model-plan.md). A one-page copy for human readers is
[`apply-model.html`](apply-model.html), with a live copy at
<https://claude.ai/artifact/9cDRZDeBKFiN5un88v7cNJ>, private to Chris and a convenience; the files
here are the authority. Where a board and the plan disagree, the plan wins on behaviour and the boards
on layout and copy, as with every record in this directory.

## Asked and answered

Chris answered these on 2026-10-15, in three rounds. The plan numbers them D1–D10.

| Question | Answer |
| --- | --- |
| What does a palette press put on? | **A tracking layer, on every surface.** Values only from *Apply as values* or *Detach*. Values everywhere (a layer on ⌥), and today's split, were declined. |
| A palette on heads holding another palette of its family? | **It replaces it on those heads.** Stacking on top, and replacing only in solo banks, were declined. |
| How many masks? | **One, the selection's.** The family pill sets it; Record, Include, Make layer and Update open on it. Two masks, and the marquee alone, were declined. |
| The popup's layout? | **Bands round the editor**: palettes above, effects below, *All …* for the family. Tabs, and a separate palette button, were declined. |
| A bare library effect, and how it is edited? | **Local on the whole selection; edits follow the selection** (MagicQ's rule, after a look at MagicQ, grandMA3 and Eos). An edit with a subset selected splits it off at its phase. "An edit changes all heads" and "always through a palette" were declined. |
| What does the family replacement cover? | **Value and effect palettes apart, Looks stacking.** "Any palette of the family" and "Looks too" were declined. |
| A palette pressed with a Look layer focused? | **Its values are written into the Look.** Refusing it, and the programmer as today, were declined. |
| ⌥click and hold, now a click puts a layer on? | **⌥ is *Apply as values*; a hold opens the menu, everywhere.** No modifier, and ⌥ to stack, were declined. |
| Should the MCP tools follow the UI? | **Yes**: `apply_palette` layers by default, `asValues: true` for literals. |
| The busk view's popup? | **A side-sheet tab per family**: Level, Colour, Position, Beam. Colour only, and pads only, were declined. |

## What was found in the code

Read from `main` at `2bf9aba`, 2026-10-15, by a survey of the frontend's apply surfaces and a read of
the press routes. The plan's §1 holds the findings with file:line; in short:

- **Four meanings of a palette press**: a click's literals, ⌥click's layer, a pad's layer, the add-layer
  sheet's new layer.
- **Six sources of fixtures**, among them one target from the add-layer sheet's own picker, the first
  group or fixture for *+ Effect*, and the desk selection for the hand even when the window is unlinked.
- **The mask is honoured by five doors and ignored by seven**, and only the marquee sets it. Record and
  Include have a separate mask picker.
- **A focused Look is honoured by cell edits, Spread and *+ Effect***, and ignored by the strip, the
  picker and the Recent chips.
- **Effects arrive in four forms.** An instance targets one fixture or one group, so a hand selection
  runs in unison.
- **Most of the machinery exists.** Per-target presses, `releaseSiblings`, the mask's on-arm test,
  `TemplateFanOut`, `appliedState` and Spread's `write: false` are already on the desk; the model is
  mostly routing.

## What the boards propose, in one paragraph each

**The popup.** Every value cell's editor gains two bands: the family's palettes that fit the cell's
heads above it, with the pad's rings, and the effects below, with what already runs on those heads
lit. A tap is the press, ⌥click applies values, and a hold opens the menu. Under a focused Look a teal
band says where the press goes, and the rings go. A setting cell has no palette band.

**The programmer.** The selection bar's family pill opens the mask's picker. The strip is the recents
row with rings, filtered by the mask. Pressing Amber over Red narrows Red's layer, and the rail says so.
A layer's menu gains *Detach*. *+ Look*, *+ Palette* and *All · n* open one picker on the selection,
with *Outside the mask* dimmed.

**Busk.** The side sheet gains Level, Position and Beam tabs beside Colour, each the popup's body. The
Effects tab adds an effect over the selection and edits by selection: with part of an effect selected,
*Apply to Hex 1–2* splits those heads off at their phase.

## Format

Each `*.dc.html` is one dark artboard, generated by `gen.mjs` (`node gen.mjs`) from the
show-cue-editing boards' stylesheet, restated in the generator with the popup's classes appended, so
each board renders standalone; `canvas.json` is the layout manifest. Numbered amber markers are keyed in
each board's notes strip. `apply-model.html` is the one-page copy published as the live artifact,
hand-written, theme-aware, with its mock-ups drawn dark in both themes as the desk is.

| File | Draws |
| --- | --- |
| `Popup.dc.html` | The colour cell's popup with both bands and the rings; the hold menu; the same popup under a focused Look; the Level, Position and Zoom popups; a gobo cell with no band. |
| `Programmer.dc.html` | The programmer after Amber is pressed over Red: the mask pill and its picker, the strip with rings, the grid's palette cells, the rail's narrowed layer and *Detach*, and the one picker from *+ Palette*. |
| `Busk.dc.html` | The busk side sheet's family tabs on Position, and the Effects tab editing part of a Local effect. |

## Names on the boards

Sleeping Beauty's fixtures and project name are the desk's; the Hex, Orbit and Robe numbering, the
palette names, Warm wash and every state shown are illustrative.
