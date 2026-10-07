# Fixture and FX sheets — design record

Source: Chris's brief, 2026-10-07. Survey the fixture and FX sheets and propose better designs for
every place they appear: the fixture list's pop-up, the Stage view's panel, the cards page and the
groups. The brief named six problems:

1. It is hard to see the effect of the layers. For example, a position effect added after a
   position has been set by hand appears to do nothing.
2. A value cannot be cleared.
3. An effect's change cannot be seen until it is applied. FX settings should be live.
4. A slider value cannot be typed.
5. The design should take its cues from the programmer and busk views.
6. In some hosts (the Stage view especially) the FX section is lost after the properties. It should
   stay fixed at the bottom of the sheet.

**Status: drawn 2026-10-07, awaiting approval.** The implementation plan follows once the design is
agreed. A live copy of the boards is at <https://claude.ai/artifact/AnEyZHYphuSdmWcSArXigv>. It is
private to Chris and is a convenience; the files here are the authority. Where a board and the plan
disagree, the plan will win on behaviour and the boards on layout and copy, as with every record in
this directory.

| File | What it is |
| --- | --- |
| `Survey.dc.html` | Today: the pop-up and the Stage panel as they render, the five hosts, and what goes wrong. |
| `Main.dc.html` | The new sheet's anatomy, resting and with the FX tray open. |
| `Layers.dc.html` | Why the Circle did nothing, today against the new design; the property stack; the vocabulary. |
| `Fx.dc.html` | The tray's four states, the add picker, and the live editor for three families. |
| `Hosts.dc.html` | The pop-up, the Stage panel, a phone's bottom sheet and the cards page. |
| `HeadsGroups.dc.html` | The head strip on a multi-head fixture and on a group. |
| `BuskProgrammer.dc.html` | The same answers carried into the Busk and Programmer views: Around current in effect templates, the live editor on the rail, Release over a selection, held-back marks and the stack in the grid, a busk Effects tab and *Fixture sheet…* on a rig tile. |
| `Model.dc.html` | Decisions D1–D21, wire changes W1–W5, the code map, six sessions and the open calls. |
| `canvas.json` | The canvas index. |

## What was found

Read from the code on `main` at `b42aff4`.

- **One body under two headers, in five hosts.**
  - `FixtureContent` is the body, under `FixtureDetailView`'s header.
  - It appears in the 512px pop-up (`FixtureDetailModal`), the Stage view's 380px docked panel
    (shown at 640px and wider only), and a card per fixture on the cards page.
  - The group sheet (`GroupDetailModal`) is a separate set of visualisers. It has no FX and no
    park, and it writes one entry per member (sliders as raw channels) where `GroupCard` writes
    group entries.
- **Every value written on the sheet is a programmer write.** The sheet still shows the output
  only: there is no source mark, no clear and no typed field. Park's lock is the only source it
  shows.
- **Pan and tilt on the sheet write raw channels.** These land in the programmer's raw-channel
  side track, which does not hold effects back. Provenance still reports the programmer as the
  winner for them.
- **Why the Circle did nothing.** The sheet's FX section creates a manual effect at priority 0.
  The engine skips every non-programmer effect on a property the programmer holds
  (`FxEngine.isSuppressed`), and nothing on screen says so. Under the default Override, the Circle
  would have circled 128/128 regardless; only Additive orbits what is underneath.
- **Every FX form is a draft**, committed with Apply or Update. `PUT /fx/{id}` already swaps
  parameters while keeping the effect's phase. The busk tabs' `useLivePush` is the live pattern.
- **The data for a source mark is already on the wire** (`provenanceState`, `getKeyState`). What
  sits underneath the winner is not: held-back effects and the cue value below are missing.

## Agreed before drawing

Chris answered eight questions on 2026-10-07:

- Sheet effects are programmer effects.
- A movement effect orbits the set position by default.
- FX settings are always live.
- The Edit toggle goes.
- Each property shows a source mark, and a tap on it opens the full stack.
- Groups use the same sheet.
- The Stage view on a phone gets a bottom sheet.
- Three extras are in: Release for a whole fixture, the editor kit's controls, and a head strip in
  place of accordions. *Save / Record from the sheet* was not chosen.

On the same day, Chris asked whether the ideas also belong in the Busk and Programmer views. The
survey found the starting bug there too: an effect template stores Override
(`TemplateEditor.tsx:233`), so a Circle pad pressed after a position pad circles 128/128. He chose
to carry over:

- *Around current position* in effect templates.
- The live editor in the programmer.
- Release over a selection.
- Held-back marks and the stack in the programmer grid.
- A busk Effects tab.
- *Fixture sheet…* on a rig tile.

For the Effects tab he decided that a pad's effect is edited as the running instance only, with a
button to update the template from it and one to reset it to the template.
