# MIDI surface view — design

Source: a Claude Design canvas authored 2026-09-06 against `lighting-react`'s shipped components
(the oklch tokens in `src/index.css`, the settings-tab shell, the busk view's `LibraryPalette`,
`BuskPad` presence ring and *Edit layout* toggle, `Badge` / `Button` / `Tabs` geometry) and
lighting7's `XTouchCompactStandard` profile. **Read them as the intended visual output, not as
structure to copy**: there is no React here, and the X-Touch panel is drawn from the profile's
control list with a hand-placed layout that session 2 of the plan turns into data.

This is a proposal, not a plan: nothing here has landed. The brief was three things — the
Blackout / GM toolbar does not belong on the view; bindings should be made by dragging from a
library onto a picture of the attached surface, as the busk view is configured; and the picture
should show the state of the controls. Working it through with a survey of other desks added a
fourth: the surface should be able to drive the **programmer**, which every console does through a
selection and a channel-strip layout rather than through fixed per-control bindings. The canvas's
second revision (2026-09-06, "Strip and selection model") draws that.

The implementation plan lives at [`../midi-surface-plan.md`](../midi-surface-plan.md). Its §4 is a
grep-able summary of what these files draw; where wording disagrees, the plan wins on behaviour
and these files win on layout and copy.

The live, pannable version is at
<https://claude.ai/code/artifact/41182cc3-bcf1-4315-966c-ba3d7b5fd797> — private to Chris, so
**treat these files as the authority** and the URL as a convenience if you happen to have access.

## Format

Each `*.dc.html` is one artboard. All three are **static mockups**: no `{{ hole }}` bindings and
no logic script, so each renders standalone in a browser. The live canvas carries one tweak the
checked-in copies do not — a light / dark switch — and these files are the **dark** rendering.
`canvas.json` is the layout manifest, one page, plus three sticky notes carrying what the boards
imply for the backend.

The sample show is invented: a small band gig with six groups, one stack of six cues, two speed
masters. Values, names and the second (unmatched) device exist to exercise states, not to record a
rig.

## Artboards

| File | Draws |
| --- | --- |
| `Main.dc.html` | The Surfaces tab in **run mode**. Device chips, dead-binding badge and the bank switcher in the header; no Blackout / GM buttons. The X-Touch Compact drawn as eight strips plus the right block and master: each strip bound to one group (fader = dimmer, select button = the desk's selection with its LED lit, encoder = the lit *Encoder bank*'s property, flash button derived). The right-block encoders are selection encoders, one reading *mixed*; the six transport buttons are the encoder-bank pager. Button rows mix fixed targets (cues, stack transport, tap, blackout) with template presses, a position template and a bound Look. A Selection chip with *Clear* in the panel header. Fader 5 selected, with the inspector describing its **strip binding** and live state (touched, motor feedback paused). |
| `Edit.dc.html` | The same page in **edit bindings** mode: *Done* in the header, the library palette in the inspector's place, bound controls carrying the busk pad's remove cross, and a group **row** ("Side wash") mid-drag over strip 7 with the whole column lit as the drop target. Library rows: groups and fixtures with a *Strip* chip plus per-property chips and *select*; a Selection row; an Encoder bank row; a template's *Press*; a Look's *Apply*; a busk page's pads and page moves; desk actions. |
| `Legend.dc.html` | The control status vocabulary: fader states (unbound, bound, touched, pickup waiting, dead, drop target, selected), button states (unbound, bound, LED lit, cue face, removable), encoder states, strip and selection states (select off / on, strip unbound, encoder bank), the three selection-encoder states (uniform, mixed, no selection), and which library items land on which controls. |

## What the sticky notes say

- **Run mode** — the picture of the attached surface is the page; the toolbar is gone; the strip
  model as above.
- **Edit bindings** — row → strip, chip → one control; fixed and strip bindings coexist.
- **Backend implied** — a desk-side selection shared by the busk view and the surface; strip,
  selection-property, encoder-bank, select, Look, template, pad and busk-page binding targets; and
  one keyed control-state stream for fader position, touch, LED and ring state, mirroring what
  `SurfaceFeedbackPublisher` already sends to the hardware.
