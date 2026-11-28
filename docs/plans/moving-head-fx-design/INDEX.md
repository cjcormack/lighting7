# Moving-head FX: design record

Source: Claude's report of 2026-11-28, written after building an XMAS letters effect for the Sleeping
Beauty show: four Show-Sound LEDBEAMRING moving heads on LX3, colour-wheel heads, an 8-bit Path. Chris
asked for it to be planned in the usual form. There was no design page and no question round; the
engineering half is [`../moving-head-fx-plan.md`](../moving-head-fx-plan.md).

## The brief, as the report gave it

Bugs, first:

1. **A colour-family FX is given the attribute `colour`, which no fixture has.** `create_look` (and
   `update_look`, `create_palette` with `fx`, `create_cue`'s ad-hoc FX and layers) with
   `{fxType: "ColourCycle", family: "colour"}` and no `attribute` on the Beam Rings reported
   `fxCount: 2`, and the FX never ran when the Look was pressed. Expected: the colour family resolves
   to `rgbColour`, which works on colour-wheel heads.
2. **An FX with an attribute no target can take is saved silently.** Expected: refuse when no head can
   take it, report skipped heads when only some can. Also: `record_cue` MERGE with `includeFx: false`
   reported `fxWritten: 1` for a Look layer; report layers apart from FX and say whether `includeFx`
   excludes Look layers.

Features, in the report's priority order: fine pan/tilt end to end; control over which of a head's
two aim solutions the solver picks (`near`, `tiltSide`, both in dryRun); aiming from inside an FX
script, with a built-in stage-space path; a head's identity in an FX (`fixtureKey`, element key);
where an FX starts on a press (`RESTART`, `NEXT_BAR`, `NEXT_BEAT`, `FREE`, runs counted from it);
per-fixture palettes in Palette Steps; `get_cue`; and four smaller items (busk pad tools, a speed
channel's direction, travel time in `preview_fx`, and `render_view` from the programmer in Blind
drawing lenses without beams).

Acceptance, as the report gave it: a ColourCycle Look with no attribute runs on colour-wheel heads and
an impossible attribute is refused clearly; a path FX on fine output with `context.aimAt` traces a 2 m
S on a cloth 3 m away with no visible stepping and no mid-path pan flip; a sequence Look pressed on a
pad with `RESTART` always starts on its first step; `aim_fixtures` with `nearPosition` returns the
nearest solution and dryRun lists both.

## Chosen without asking

The plan settled these itself. Each is the plan's D-item; any of them can be overturned before its
session runs.

| Choice | Plan's answer | Why |
| --- | --- | --- |
| How fine an FX's position is (D5) | Doubles in coarse-DMX units, the 8-bit constructor kept | Every script, blend and lerp already works in coarse DMX; 0–1 or `UShort` would need a rescale at each. |
| Fine in a Look or cue (D8) | The `panFine`/`tiltFine` rows aim already records | A decimal `"pan,tilt"` fails an older desk's parse; a decimal `pos:` does not, so palettes take decimals. |
| The aim's default (D9) | Today's (nearest each axis's zero); `near` and `tiltSide` on request | An answer that depends on where the heads are now is harder to reproduce; callers who want it ask. |
| Where the start lives (D13, D14) | Per stored FX, not per Look or pad | The brief's "per-look/pad" can be built on it; a pad override is a follow-up. |
| What NEXT_BAR does before the bar (D13) | Holds phase 0 | "Always starts on its first step" reads better than painting nothing for up to a bar. |
| A head with no row in a per-head palette step (D15) | Holds what is underneath | Matches a press of the same palette on that head. |
| Who the FX check covers (D3) | MCP only | The picker already offers only FX a head can take. |
| `render_view` (D20) | Reproduce, then fix the cause found | The code points at a programmer with no pan/tilt for the heads, read not reproduced. |
