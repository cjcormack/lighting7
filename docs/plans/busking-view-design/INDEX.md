# The Busk view — design reference

Source: a Claude Design canvas authored 2026-08-31, drawn against `lighting-react`'s real tokens —
the dark oklch values from `index.css`, `EffectPadButton`'s presence ladder (none / some / all,
including the ring and the corner dot), and the `SpeedMasters.tsx` tile anatomy (9px tracking
label, mono tabular bpm, bordered TAP cell). **Read them as the intended visual output, not as
structure to copy**: there is no React here and no components worth lifting.

The implementation plan lives at [`../busking-view-plan.md`](../busking-view-plan.md). Its §4 is a
grep-able summary of what these files draw; where wording disagrees, the plan wins on behaviour
and these files win on layout and copy.

The live, pannable version is at
<https://claude.ai/code/artifact/fdf63aa8-0145-4c8b-b98c-170a7489a4b3> — private to Chris, so
**treat these files as the authority** and the URL as a convenience if you happen to have access.

## Format

Each `*.dc.html` is one artboard. Unlike the `desk-simplification-design` set, the two main
artboards are **interactive prototypes**, not static mockups: the markup inside `<x-dc>` uses
`{{ hole }}` bindings, `<sc-for>` loops and `<sc-if>` branches, resolved by the
`class Component extends DCLogic` script at the bottom of each file. Consequences for a reader:

- Opening one raw in a browser shows the fixed chrome but **not** the looped pads or bound values
  — there is no runtime behind `support.js` outside the canvas. The live URL is where the
  behaviour is (target toggles, pad presence, GO, ratio chips).
- The sample rig, templates, cues and masters live as constants at the top of each file's script —
  that is where to read what the pads are meant to say.
- The state classes (`.pad-all`, `.tpad-on`, `.chip-on`, `.cuepad-live`) in each `<helmet><style>`
  block are the exact intended colours for each interaction state.

The two Alt artboards are static and do render standalone.

[`canvas.json`](canvas.json) is the layout manifest plus the sticky notes; the notes carry the
flow summary and the speed-master model rationale in operator words.

Every artboard is **dark-only on purpose** — this surface is read at a desk in a blacked-out room.

## The artboards

| File | What it is |
|---|---|
| [`Main.dc.html`](Main.dc.html) | **The centrepiece.** The whole view at 1440×900: ShowBar on top (unchanged), the two-row target band of group/fixture toggle pads, template pools in four family columns (colour pads carry swatches), Looks, cue-stack cards with Release/GO, pinned-cue pads, and the speed rail — M1 big with TAP, followers with ratio chips in place of TAP, usage badges, the routing caption. |
| [`SpeedMasterSheet.dc.html`](SpeedMasterSheet.dc.html) | The speed-master detail sheet in the app's sheet chrome: Name · Default usage (with the one-master-per-usage helper text) · Tempo as a Manual / Follow Master 1 segmented control, ratio chips, and the 120 → ½× → 60 preview. The follow-mode copy states the TAP refusal. |
| [`AltRail.dc.html`](AltRail.dc.html) | Option A, low-fi: targets as a scrolling left rail, speed masters as a bottom strip. Not chosen — costs pad width, and the strip competes with the ShowBar's own chip. Kept as a record. |
| [`AltPools.dc.html`](AltPools.dc.html) | Option B, low-fi: MA-style paged banks with fader-shaped masters. Not chosen — a mode switch per family. Kept as a record. |

## What to read for each session

- **Sessions 1–2** (speed masters) — `SpeedMasterSheet`, plus `Main`'s speed rail and the
  `note-speed` sticky in `canvas.json` for the model in operator words.
- **Session 3** (the view) — `Main` whole; the alternates only if the arrangement fails at a desk.
- **Session 4** (cues on pads) — `Main`'s stack cards and pinned-cue pads, and the plan's D9/D10
  for the semantics the drawing leaves implicit.
