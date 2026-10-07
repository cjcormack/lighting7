# Scenery across the desk — design record

Source: Chris's brief, 2026-10-07. He asked how theatre and scenery state (a tab's position, a
piece flown in) can be tied more closely into the programmer, Looks, templates, the Stage view and
the Prompt Book. His starting belief was that scenery can only be changed in the Show view, in card
mode.

**Status: approved 2026-10-07**, on two conditions: consistency with the desk's current design
language, and iPhone, iPad and desktop taken into account. The implementation plan is
[`../scenery-programmer-plan.md`](../scenery-programmer-plan.md); its D17 and §4 answer the
conditions.

The design document is a Claude Docs page at
<https://claude.ai/code/artifact/0def61d0-9e4c-4ba4-ba7d-a1bd0d339f71>. It is private to Chris and is
a convenience. It holds the five mock-ups: the programmer's Scenery tab, the Record sheet and Looks
library, the Stage popover, the cue table's column, and the Prompt Book. There are no `.dc.html`
boards for this record; the files here are the authority on what was found and decided. Where the
mock-ups and the plan disagree, the plan wins on behaviour and the mock-ups on layout and copy, as
with every record in this directory.

## What was found

Read from the code on `main` at `d2c35aa`, 2026-10-07.

| Surface | Reads scenery | Writes scenery |
|---|---|---|
| Show · Cards (unlocked) → Cue properties | the card's readout: own changes and tracked state | the cue's changes, each with a time |
| Show · Table (`CueSheet`) | nothing | nothing; no column and no door to Cue properties |
| Show · stack list → Stack settings | the set | the set (no time) |
| Looks library → Look sheet | *Scenery while live* | the same (no time) |
| Programmer | nothing | nothing directly; a pressed Look brings its own |
| Templates | — | refused by design (stage-view D11) |
| Busk pads | nothing | indirectly, through a Look pad |
| Stage view | draws live scenery; Next GO draws the preview | Edit's form writes the element's **base** only; *Moves with* is a static placeholder |
| Prompt Book | read-only readout in the rail card's Details | nothing; *Edit cue* deep-links to Show |
| MCP | `get_scene` | `set_scenery`, `create_cue`, `create_look`, `build_cue_stack` (authoring only) |

Also found:

- **The resolver has five tiers today**, in this order: base, then each live stack's set and its
  cues tracked to the live cue, then the Looks layered in live cues, then the programmer's Looks.
  The stage-view plan's session-8 text names four; the code separates cue-layered Looks from
  programmer Looks.
- **Only a cue row has a clock.** A pressed Look, a set edit, a stack stop and GO TO all snap.
- **`SceneryEditor` offers steps, not a range.** A tab is closed, half or drawn; a flown piece is in
  (Z) or out (its stored trim).
- **The Stage view anchors no DOM to a 3D object.** The label layer's per-frame projection is the
  precedent.
- **Edit's *Moves with* was promised for session 8 and never filled.**
- **The programmer rail's tabs exist only when docked** (≥ 1200 px of workspace). Below that the
  rail is an overlay or, on a phone, a bottom sheet that keeps the stack.
- **"Standby" means the armed next cue in the cue table (blue) and the later unfired cues in the
  Prompt Book (amber).**
- **Element params are stored verbatim and decoded with `ignoreUnknownKeys`**, so a new optional
  param needs no `formatVersion` bump.

## What the design proposes, in one paragraph each

**The programmer gets hands on scenery.** A sparse, runtime-only overlay of element states sits on
top of the resolver, above the programmer's Looks. Blind stages it, Clear drops it, and Record,
Include and Update carry it into cues and Looks. Record still captures only what the programmer
holds, so stage-view D13 is narrowed rather than reversed. A state the cue already tracks is not
written.

**One control everywhere.** `SceneryControl` keeps the editor's steps as presets and adds a slider
between a tab's closed and drawn positions, or a flown piece's in and out. It is built from the
editor kit and opened through `EditorSurface`, so it is a popover on a desk, a bottom sheet on a
phone and a side sheet on a short viewport.

**Every surface goes through that door.**

- The programmer gains a Scenery rail tab, and an action-bar chip that reaches it at every width.
  (Superseded in session 2: scenery is a band of the rail's Stack body instead, with no tab and no
  chip — the plan's §4, *Session 2 amendment*.)
- The Stage view opens the control on a clicked piece.
- The cue table gains a Scenery column.
- The Prompt Book marks cues that move scenery, lists their changes on the rail cards and names the
  next GO's moves on an *On GO* line.
- The Looks library gains a Scenery column.
- Templates stay without scenery; a Look that holds only scenery is the busk vehicle.

**Moves glide.** Each drawn or flown element may carry a `travelS`. Every move that is not on a
cue's own clock runs at it, scaled by the share of travel moved, and a programmer fade overrides
it. Unset still snaps.

## Considered and declined

- **Scenery templates** (a fifth family over a scenery selection). They need a selection beside the
  head selection, and a press route and template editor with no heads, for six intents that the
  control's presets already give.
- **Scenery in the desk selection.** It would teach every target consumer a new kind.
- **A switch on the Record sheet.** The sheet's rows are checkboxes with hints; the switch in an
  early mock-up was corrected.
- **Drag handles in the Stage view, and a fly department in the book**, for now. Both are follow-ups.

## Questions answered

Chris, 2026-10-07. The answers are recorded in the plan's §10.
