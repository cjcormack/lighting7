# Venues and shows — design record

Source: Chris's brief, 2026-10-10. A project today is one thing: the hall, its rig and the show all
live in it, so a second show in the same hall copies the room. This is `FU-STAGE-VENUE-SHARED`
(`docs/plans/followups.md`), whose trigger is a second show in the Commemoration Hall. The brief:
the material structure of a venue doesn't change between shows, some things always do (the set),
and some sometimes do (fixture positions). Two refinements followed the same day:

- **Seating is per show.** The seats are not fixed. Revised the same evening: some venues have
  static seating, so it can be part of the venue. That became a per-block choice (D6).
- **Venue details are versioned.** Changing the house patch or universes must not change an old
  show. Opening a show on an older version warns, and offers an update.

**Status: approved 2026-10-10.** The implementation plan is [`../venue-plan.md`](../venue-plan.md). It departs from the Model board once: links into the venue are re-pointed through a registry rather than converted to uuid columns (the plan's P3), so the boards' sessions are re-cut there. A live copy of
all five boards on one page is at <https://claude.ai/artifact/LxMaLSktSQa73AkoXQzUvh>, private to
Chris, a convenience; the files here
are the authority. Where a board and the plan disagree, the plan wins on behaviour and the boards on
layout and copy, as with every record in this directory.

## Decided before the boards were drawn

Chris answered these on 2026-10-10, in two rounds. The boards draw them; the Model board numbers them D1–D12.

| Question | Answer |
| --- | --- |
| How does a version come into being? | **Explicit publish** from a draft, with a note. Versions are immutable. |
| A show whose pinned version has output-affecting changes behind it? | **Opens in Blind** until updated or *Run against vN* is confirmed. Drawing-only drift is a banner. |
| Seating layouts? | **Venue presets, copied** into a show at creation. |
| Will a show move venue? | **Not for now.** Detach covers the archive and the rare move. |
| Where does the venue sync? | **Its own repo.** A version is a tagged commit; a show names `venueUuid` + `venueVersion`. |
| Who edits and publishes the venue? | **Admins only.** Operators see it read-only. |
| Fixed or movable seating? | **Per seating block.** A Fixed block (the balcony) is the venue's, with its seat views; a Preset (the stalls layouts) is copied into a show. The hall is both, so one switch per venue was declined. |
| What does the hall own? | **Everything patched in project 9 today**: dimmers, house lights, hazer, Revolutions, movers, LED kit and the cannon. A show adds what it brings in; the panto will add fixtures. |
| The Programmer in a venue? | **Yes, with Record off**, for testing and focusing. |
| Venue groups? | **Yes.** A show adds its own, which may hold venue fixtures; a venue group never holds a show's. |
| Do the four facets fit? | **Yes**: Address · Position · Focus · Media. |

## What was found in the code

- **Most show content names fixtures by key.** Look, template and cue rows, ad-hoc effects and
  surface bindings carry `targetType` + `targetKey`, so they resolve against whatever patch holds
  the key. A venue-owned patch needs no change to them.
- **Four links use row ids**: group members → patch, busk rig tiles → patch or group, cue events →
  patch, and scenery (cue, stack and Look) → element. These are the ones that would cross from show
  to venue, and they must become uuid links before a show can change version (session 1).
- **uuid columns are not unique-indexed** (0 of 43), so a frozen copy of a venue version can share
  its records' uuids with the draft.
- **`AssignmentHealth` already marks dead rows** (`MissingFixture`, `MissingGroup`), so a key an
  update removes leaves cue rows marked, not broken.
- **Sync is one repo per project**, guarded by the project uuid; a venue needs its own identity.
- **The scene document already has `layer = VENUE | SET`**, and `machine_overrides` is the
  precedent for "same record, different value here".

## What the boards propose, in one paragraph each

**A venue is a project of kind VENUE** (D1). Every existing table keeps its project FK; *Extract a
venue* moves a project's room, house patch, universes and bars into a new venue at v1 with their
uuids. A show references a venue and a version, or none. Opening the venue uses the views that
already exist, with the show-only ones hidden and Record off.

**A show's rig is the venue at its pinned version plus the show's layer** (D4, D5): additions (hires,
booms, the set, seating), overrides of a whole facet of a venue record (Address, Position, Focus,
Media), and hides. Identity (name, key, type, head) is the venue's. Keys are one namespace. The
Patch list gains a source column and a Venue | This show filter; the fixture sheet gains a switch per
facet. *Drift* is a venue change under an override, kept and marked amber.

**Publishing** (D3) shows the draft's changes split into *changes output* and *drawing only*, and
every show on the venue with what it will see. **Opening a show that's behind** (D7) classifies the
gap the same way: drawing-only is a banner; output-affecting holds output in Blind. The update review
shows the hall's changes beside what they do to the show (carry over, kept under drift, dropped,
clashes needing a decision, rows that will point at nothing). *Run against vN* is per session, and
the installed version's trigger and command channels stay guarded whatever it sends.

## Format

Each `*.dc.html` is one artboard, generated by `gen.mjs` (`node gen.mjs`) from the Stage boards'
stylesheet (`../../stage-view-design/Stage.dc.html`) plus what these boards add, so each renders
standalone; `canvas.json` is the layout manifest. Every board is dark-only. `venue.html` is the
one-page copy published as the live artifact.

| File | Draws |
| --- | --- |
| `Projects.dc.html` | The Projects page with a Venues row; show cards naming venue and version (current, behind, behind with output changes, none); a show's menu with *Update venue* and *Detach*; *Create New Project* with the venue picker open and the stalls layout. |
| `Venue.dc.html` | The Commemoration Hall open as a venue: the editing band, the Patch list filtered to draft changes, the History table, the *Publish v6* sheet with output and drawing changes and the shows affected. |
| `Show.dc.html` | Panto 2027's Patch list: venue rows (a mover re-hung on a boom), facet overrides, drift, a hidden house unit, the panto's hired spots; the Edit Fixture sheet's per-facet switches; a plan of the hall coloured by owner, the balcony the venue's and the stalls the show's. |
| `Update.dc.html` | Spring Concert 2026 opened against v5: the Blind hold, the drawing-only banner, the *Run against v3* banner, and the update review. |
| `Model.dc.html` | Versions and pins; the show's layer; what's whose; D1–D9; storage and the uuid-link prerequisite; seven sessions; what was declined; the four questions still open. |

## Names on the boards

The venue is extracted from project 9, *The Commemoration Hall*. Panto 2027 is the show the brief
names; Summer Show 2026, Christmas Concert 2026, Spring Concert 2026 and Studio test are example
names, and the version history and its dates are illustrative.
