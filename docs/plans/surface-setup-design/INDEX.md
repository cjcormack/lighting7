# Surface setup: design record

Source: a design session on 2026-11-08. Chris asked for research and new designs for configuring a
control surface. Three things prompted it: the drag interface is hard to use and doesn't reach
everything (a speed master other than master 1 only through editing a binding); some functions work on
the selection but not on a named fixture or group (Locate); and the layer a control applies to is not
consistent (a selection dimmer writes the programmer, while pan and tilt seem to apply to the output).
He asked for the UI concept first, then a review of the surface code against it.

**Status: agreed 2026-11-08.** The implementation plan is [`../surface-setup-plan.md`](../surface-setup-plan.md).
Where the plan and the pages disagree, the plan wins on behaviour and the pages on layout and copy.

## Asked and answered

Chris answered these on 2026-11-08, in four rounds, taking the recommended answer each time except
where the table says otherwise.

| Question | Answer |
| --- | --- |
| What does a fixture control read back? | **Local first**: what the programmer holds for its key (the staged value in Blind), else the stage. |
| A region's job: live or written once? | **Live**, stored as a strip is today; a control bound by hand leaves it. |
| Which editors? | **All four, now** (the recommendation was A, C and D now and B later): the inspector, region jobs, the mapping table and *Assign to surface…*. |
| Programmer verbs on a surface? | **Clear, Blind, Release and Record** (Record was not recommended). |
| How does Record work from a button? | **Two functions**: Record appends a new cue to a named stack with every touched value; Update writes back to what Include loaded. |
| An encoder's push? | **Its own binding**, empty until bound. |
| Flash? | **A percentage of each head's range, and on the selection.** |
| Words? | **Layer A / B**, **Encoder page**, **Writes to Local**. |
| Is "the stage" in the readback before FX? | **The cue layer under Local, without FX.** |
| Flash and park in the readback? | **Beneath a held flash, and park ignored.** |
| Touching a control to identify it? | **Captured without firing**: one press, a banner, 30 s. |
| Does the desk serve the resolved map and the function catalogue? | **Yes, both.** |
| Per device or per type? | **Bindings per device type, live state per device.** |
| What does Record capture beyond values, and Update with nothing included? | **The Record sheet's defaults**; Update refuses. |
| Fire on a group? | **No, fixture only.** |
| `minReader` for the new shape? | **Raised with the format version.** |

## What was found in the code

Read from `main` at `974d718`. Every item was checked in the code; the review page cites each one.

- **Every surface write already lands in the programmer.** What differs is the readback: a level,
  colour or pan control reads the controller buffer (park, else composed, before the grand master;
  `midi/SurfaceFeedbackPublisher.kt:1319`, `dmx/ArtNetController.kt:229`), while the fan moves from
  the programmer's value (`routes/programmerFan.kt:495`).
- **Pan and tilt write their own keys**, which don't hold back a position FX
  (`fx/FxSuppression.kt:73`, `fx/FxTarget.kt:719`). The fixture sheet had the same bug and fixed it
  with `programmer.setPosition` (fixture-fx-sheets plan D8).
- **A selected cell, or a fixture's heads in a group, get no surface write**
  (`midi/SelectionWrites.kt:36, 42`); a **white-mix colour head drops every colour move**
  (`midi/AttributeChannelResolver.kt:364`).
- **Feedback runs on the transmit path under the fixtures read lock** (`show/Fixtures.kt:747`).
- **Learn captures beside dispatch**, so identifying a bound control fires it
  (`midi/MidiLearnSessionManager.kt:220`); it only opens on a bound control
  (`components/surfaces/SurfaceInspector.tsx:227`).
- **The drag library offers master 1 only** for tempo (`components/surfaces/SurfaceLibrary.tsx:683`);
  a fixture flash can't be made from any door (`BindingTargetPicker.tsx:1270`); the phone table can't
  see strips (`BindingMatrix.tsx:77`).
- **The selection and named targets don't match**: Locate, Fan, FX speed and Press palette are
  selection-only; Flash and Fire named-only; no Clear, Blind, Release or Record target exists.
- **Feedback has seven entry kinds with no shared base** and hand-kept resync lists that have drifted:
  a motorised tempo fader gets no catch-up on touch-off (`SurfaceFeedbackPublisher.kt:1157`).

## The pages

- [`surface-setup.html`](surface-setup.html) is the concept page: what's wrong today, prior art from
  grandMA3, Eos, MagicQ, Titan, Hog, ReaLearn, Resolume, Companion and Ableton, the grammar
  (Does · On · How), the layer rule, four interactive editor mock-ups (A–D), the six sessions and the
  decisions. Published at <https://claude.ai/artifact/LQQTixU717GnAoDb6fczNp>.
- [`surface-review.html`](surface-review.html) is the code review against the agreed model: the bugs
  found, the target design for the binding, feedback, jobs and the frontend, the inventory, migration
  and sync, and the six sessions. Published at <https://claude.ai/artifact/JfaaYXomTpAfPcJSVpM9n3>.

Both are private to Chris and a convenience; the files here are the authority. Republish from these
files after a change. Names in the mock-ups (Front wash, Act 1, M2 · Chase) are illustrative.
