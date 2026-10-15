# After the venue visit — what the first evening on the rig asked for

> **Document status: AGREED, 2026-10-14.** Chris's notes from an evening programming a show on the
> desk in the hall, with two rounds of questions answered the same day (§2). It is an umbrella plan
> with two kinds of session:
>
> - **S sessions** land a change, one PR each.
> - **R sessions** are research and design. Each one produces a new plan in `docs/plans/`, with a
>   design record beside it if it needs boards, and lands nothing else.
>
> Where a later R plan disagrees with this one, the R plan wins on everything inside its scope.

## 1. Context

The notes cover groups, fixtures, templates, fixture types, lamp control, effects, the show views,
the programmer, the cue list, cues, the grand master and project structure. Three surveys of the
code (2026-10-14) shaped how the notes are split into sessions:

- **Colour palettes don't reach the Shehds' heads, and that's a bug, not a missing feature.** In
  24-channel mode the Shehds' colour lives on its three `Zone` elements, and the parent has none.
  - `applyValueTemplateToProgrammer` expands targets with `expandTargetsToFixtureKeys`
    (`routes/programmerLookInclude.kt:44`), which never fans a parent out to its elements.
  - `TemplateResolver` then skips the parent with "no colour".
  - The frontend collapses a cell selection back to the parent anyway (`ColourCell.tsx:95`
    `templateTargetsOf`; `TemplateTarget` allows only fixture or group).
  - `CueComposer.expandTargets` (`:952`) has the same gap for layers, and so does Spread over Heads.
- **Every cue belongs to one stack.**
  - `cues.cue_stack_id` is non-null, cue numbers are unique per stack, and the sync archive's
    `cues/{uuid}.json` carries `cueStackUuid`.
  - The card's **Remove** hard-deletes the cue, with no confirmation (`CueCardEditor.tsx:417`).
  - Deleting a stack deletes its cues.
  - `add-cue` moves a cue rather than sharing it, and renumbers only the target stack.
  - So "one cue in several stacks" changes the model, and gets a design session (R1).
- **Auto-numbering derives every auto cue from the nearest explicit number.**
  `renumberAutoCues` (`routes/cueNumbering.kt:311`) counts auto cues before the first explicit
  number *up to* it. Renumbering one cue in an all-auto stack therefore relabels the cues on both
  sides, and the frontend's out-of-order check (`StackDetail.tsx:139`) never sees a descent.
- **Show position is held only in the URL** (`/show/stacks/:id?cue=`). The view switcher links to
  bare `/show` (`ViewSwitcher.tsx:79`), so a round trip to the programmer loses both.
  - "Edit in Programmer" is Include, and stays on `/show`.
  - The table view has no delete, duplicate, include or record.
  - Duplicate posts the original's `sortOrder`, so the copy lands beside it.
- **Group member order is already stored and used.** `fixture_group_members.sort_order` drives
  distribution and spread. It can only be reordered from the Patch list's group sheet.
- **Locate is per property category** (`fx/LocateValueResolver.kt`): pan and tilt at mid-range. There
  is no per-unit value.
- **The blinder is one class**, `China2CellLedBlinderFixture`. It has two cells, each with plain
  `warmWhite` and `coldWhite` sliders and no Colour property.
- **Lamp commands exist on two types only** (the Robe ColorSpot and the MAC 250), and only per patch.
  There is no bulk command.
- **The grand master is binary** (`midi/GlobalScalerState.kt`) and has no UI. The Show bar's DBO
  tile does nothing (`FU-FE-DBO-INERT`).
- **Position is shown in degrees** where a head annotates its travel, and in bytes otherwise.
  - Generic position templates and spread endpoints store absolute degrees.
  - FX position parameters are raw DMX, shown in degrees.
- **FX timing comes from a speed master**, and null means master 1. The UI starts every effect at
  `beatDivision: 1`, one cycle per beat (`FxPicker.tsx:193`). Only `WALL_CLOCK` effects read their
  division as seconds.
- **The applied-FX and layer lists live only in the programmer rail**, 260–480 px wide.
- **Navigation:**
  - The Projects page isn't in the nav registry.
  - Remote access and Updates aren't in the install nav.
  - Live views keep their own sidebar state, defaulting to the 64 px rail
    (`hooks/useSidebarOpen.ts`).
  - A detached show can't be re-attached to a venue.
- **Applying to a selection works through seven mechanisms**: the template strip, the picker, busk
  pads, the add-layer sheet, the FX sheet, the busk side sheets and Include. Only the colour cell
  editor offers templates from a per-property popup.

## 2. Decisions

Agreed with Chris, 2026-10-14:

| # | Decision |
|---|---|
| D1 | **One shared cue.** A cue is a project-level record, and a stack is an ordered list of references to cues. Editing a cue changes it in every stack that holds it. A cue may be in no stack. Removing a cue from a stack, or deleting the stack, never deletes the cue: delete is its own explicit operation. R1 designs the model. |
| D2 | **Templates are renamed palettes in everything a person or Claude reads**: UI copy, REST paths, WS frame names, MCP tool names and docs. DB tables, the sync archive's `templates/` folder, `tmpl:` references and internal Kotlin/TS identifiers keep "template", so there's no `formatVersion` bump and no migration. |
| D3 | **Position is centre-relative DMX, −127…+127**, with 0 at the centre of travel (DMX 128) and the fine channel still underneath. It applies everywhere an operator types or reads position: programmer, fixture sheet, cue values, spread, FX editor and palettes. A generic position palette means the same fraction of travel on every head, not the same angle. |
| D4 | **The sidebar has one state across all views.** The live/other split goes. Immersive mode (⌘K) stays as the deliberate way to hide chrome. |
| D5 | **The grand master is a continuous 0–100 % fader in the app bar**, visible on every view. It replaces the inert DBO tile and has REST, WS and a MIDI fader binding. |
| D6 | **Locate is stored on the patch, as part of the Focus facet.** It's set by *Set locate from current*. On a venue it belongs to the venue, and a show can override it like any Focus. |
| D7 | **An effect can run on its own cycle time in seconds**, free of any speed master, as an alternative to following one. |
| D8 | **The blinder takes Colour as a colour-temperature mix.** A requested colour maps onto the nearest warm/cold white mix and level per cell. The two sliders stay for direct control. |
| D9 | **Making the rig safe and quitting are separate.** *Make rig safe* lamps off every unit with a lamp (or the selection's), waits out the commands, blacks out, and then shows that the desk is ready to switch off. The app keeps running. *Quit desk* is its own action in the web UI. |
| D10 | **No hard date.** Sessions are ordered by dependency and by what got in the way on the night. |
| D11 | **A renumbered cue shows as out of order, and *Fix Order* moves it.** An explicit renumber never relabels its auto neighbours: in five auto cues, cue 2 set to `4.5` reads 1, 4.5, 3, 4, 5, with the banner showing. |
| D12 | **The grand master scales colour where a head has no dimmer.** On a dimmerless head it scales the colour-mix channels (RGB, W, A, UV), so pulling it down dims the whole rig. |
| D13 | **Quit desk is admin-only and local-only**, never over remote access. It asks for confirmation, and warns when the rig isn't marked safe. On Windows the launcher exits with the backend instead of restarting it. |
| D14 | **"Ready to switch off" lasts only while it's true.** Anything that sends output again clears it: a GO, a programmer change, a lamp-on, or leaving blackout. |
| D15 | **A cue sets its speed masters whenever it becomes live**: on GO into it, GO TO it, or GO BACK onto it. A tap or manual change afterwards wins until the next cue that sets that master. It does not track. |
| D16 | **A new effect defaults to one bar** (four beats a cycle), on the speed master tagged for its family's `usage` if one is, else master 1. |
| D17 | **A unit's stored locate is pan and tilt only.** Every other property keeps its category default. |
| D18 | **Make rig safe can be undone.** *Undo* strikes the lamps it turned off, then releases the blackout, so the show resumes as it was. |
| D19 | **Bulk lamp-on is staggered.** It strikes one unit at a time, a couple of seconds apart, to spread the inrush current. A unit that went off too recently to restrike reliably is shown with a warning and the time left. The same applies to a bulk lamp-on over a selection. |

The plan adds its own:

- **P1 — One PR per session, per CLAUDE.md §"Git workflow".**
  - Each S session ends with `./gradlew test` and `npm run check` green, its engineering-doc
    paragraphs written, and a done-marker row in §4 citing its change commit(s).
  - Each R session ends with its new plan checked in, and a row here that links it.
- **P2 — Rename before building.** S4 (palettes) lands before any session that adds UI copy, so
  later sessions write the new word once.
- **P3 — Old position data stays readable.** D3 changes what a generic position palette and a spread
  endpoint mean, and there are no migrations. The intent therefore gains a unit:
  - Stored `{panDeg, tiltDeg}` intents keep resolving as angles.
  - New writes are centre-relative.
  - The editor shows an old palette in the new scale, and saves it centre-relative only when it is
    edited.
- **P4 — R sessions gate only what they must.** Anything a design session might reshape (cue
  actions, the apply model, FX parameters) waits for it. Anything it won't touch lands first.

## 3. Sessions

Grouped by area. §4 gives the order.

### Correctness and quick wins

**S1 — Palettes reach a multi-head fixture's cells.**
- Expand a parent fixture target to its elements wherever the property lives only on its elements:
  - the click arm (`templateApply.kt`)
  - the cook (`CueComposer.expandTargets`)
  - Spread over Heads
  - busk presses
- Stop the frontend collapsing cell targets to the parent, and let `TemplateTarget` name an element.
- Tests: a colour palette on the Shehds 24-channel mode, by the parent, by its cells and through a
  group. One case per arm.
- Read `FU-TMPL-CLICK-GROUP-PARTIAL` first, since it's the same family of gap.

**S2 — Chrome and defaults.**
- D4: one sidebar state.
- Fixtures and Groups default to their list views. The sticky choice is kept, but a first visit
  lands on the list.
- Remote access and Updates join the install nav.

**S3 — Cue list fixes that need no new model.**
- **Keep the show position.** The view switcher returns to the last stack and `?cue=` for this
  project, remembered per window.
- **"Edit in programmer" opens the programmer** after the Include.
- **Duplicate appends** the copy to the end of the stack.
- **The cards lose the 2D `MiniStage`**, on desktop and phone.
- **Auto-numbering (D11).**
  - An explicit renumber no longer relabels the auto cues around it. They keep their positional
    numbers.
  - A cue renumbered "to move it" therefore shows as out of order, and *Fix Order* moves it.
  - The cases to pin in `cueNumbering` tests:
    - five auto cues, cue 2 set to `4.5`
    - cue 3 set to `10`
    - cue 3 set to `1`
- Standardising the card and table action sets waits for R2 (P4).

**S4 — Templates become palettes (D2).**
- Rename everywhere D2 lists: copy, nav, REST paths (`/palettes`), WS frame names
  (`paletteListChanged`, and the rest), MCP tool names (`create_palette`, `apply_palette`), and
  `docs/` with CLAUDE.md.
- The frontend is in the same PR, so there are no aliases.
- Leave a short note in `docs/lighting-composition-model.md` on why storage keeps the old name.

### Rig and output

**S5 — Grand master (D5).**
- `GlobalScalerState` becomes a continuous scale. It still persists in
  `project_scaler_states`, which needs a new real column (an additive change).
- REST and WS set and stream it. A MIDI `GrandMasterFader` target is added beside the toggle.
- The app-bar fader replaces the DBO tile. Closes `FU-FE-DBO-INERT`.
- **Scope (D12).** It keeps scaling DIMMER, UV and STROBE. On a head with no dimmer channel it
  scales the colour-mix channels instead.

**S6 — Make rig safe, its Undo, and Quit desk (D9, D18, D19).**
- **Command roles.** `@FixtureCommand` gains a role (`LAMP_ON`, `LAMP_OFF`, `RESET`), so a bulk
  action can ask for "every lamp off" across types.
- **Bulk run.** A bulk run over the selection, or the whole rig, respects `COMMAND_BUSY`, park and
  the venue hold, and reports per unit.
- **Make rig safe.** It lamps off, waits for the holds, blacks out, and then raises a
  "Ready to switch off" state on every window. Anything that sends output again clears it (D14).
- **Undo (D18).** Undo strikes the lamps that Make rig safe turned off, then releases the blackout.
  The desk remembers which units it turned off for as long as the desk runs.
- **Lamp-on (D19).** A bulk lamp-on, whether from Undo or over a selection, is staggered.
  - **Restrike times.** Record when each unit's lamp went off, and give each lamped type a declared
    restrike time on `@FixtureCommand` (the role's metadata), as an estimate from the manuals.
  - **Too soon.** A unit still inside its restrike time is reported with the time left, and struck
    when that time is up.
- **Quit desk (D13).** An admin-only action, on the desk's own listener only, stops the app
  cleanly after a confirmation, and warns when the rig isn't marked safe.
  - **Check first:** what the Windows launcher does when the backend exits on its own. It must exit
    too, not restart the backend. Then check what `./gradlew run` shows the operator.
  - Remote access applies `requireCommandsAccess` to the bulk run. Quit is never remote.

**S7 — Group member order in the Groups views.**
- Drag-reorder members in the group sheet and the Groups list, writing the existing `memberOrder`.
- Show the order the effects and spread will use.

**S8 — Per-patch locate (D6).**
- **Stored values (D17).** A patch's Focus facet gains a locate pan and tilt, with the fine
  channels. They are portable on the patch DTO and the venue facet. Check whether `formatVersion`
  needs a bump, per `docs/sync-engineering.md`.
- **Resolver.** `LocateValueResolver` uses a unit's stored pan and tilt where it has them. Every
  other property keeps its category default.
- **UI.** *Set locate from current* and *Clear locate* go in the programmer and the patch sheet.

**S9 — Blinder colour (D8).**
- Each cell exposes a Colour whose writer maps the requested colour to a warm/cold mix and a level.
  The mapping goes through a small CCT model, with the two whites' temperatures declared on the
  type.
- Palettes, the colour picker and the fixtures table then drive it like any colour head.
- Write the mapping as a reusable helper, in case a second WW/CW type turns up.

### Programmer

**S10 — Record polish.**
- **Record can create a new stack** from the Record sheet's stack select.
- **"All attributes".**
  - The mask picker gets an explicit *All attributes* toggle, which is on when nothing is narrowed.
  - It's used everywhere a mask is taken: Record, Record Look, Include and busk.
  - `UpdateDialog` gains the picker.
- **Fix the "An existing cue…" menu item.** It only creates a new cue (`RecordSheet.tsx:198`).
  Give it a cue picker.

**S11 — Position in −127…+127 (D3, P3).**
- A shared frontend conversion replaces `lib/axisDegrees.ts` for display and entry, on the same
  surfaces the survey listed.
- The intent gains a unit (P3), and `TemplateResolver.resolvePosition` resolves both units.
- Degree annotations stay. Aim and the Stage view still need them.

**S12 — Fan in and fan out.**
- Spread gains a relative mode: a fan amount around each head's current position.
- It works pan-only, tilt-only or both, symmetric about the selection's centre in rig order.
- Encoders and a drag control set the amount. It sits on S11's scale and S7's order.
- Start with a one-board sketch of the panel. No separate design session.

**S13 — Aim from the floor plan.**
- Click to aim the selection, single or multiple, on the Stage view's Plan camera.
- Height comes from a Z control or a region's surface.
- Aim also opens from the programmer.
- Points outside the stage (auditorium, walls, the room) are reachable because the plan extends to
  the room, with numeric entry for anything off the drawing.
- Show skipped heads by reason, as the route already answers them.

**S14 — Full-width applied tables.**
- Three views:
  - Applied FX
  - Applied Looks
  - Applied Palettes
- They share one table shell and the row actions the rail already has.
- Each remembers its sort, filter and scroll, and takes them from a query string.
- Each is registered in `lib/windowViews.ts`, so the Screens ("Windows") sheet can open it on any
  window.
- R3 may later change what a row can do, but not the tables themselves.

### Effects

**S15 — Effect timing (D7) and slower defaults.**
- An effect's timing source is either a speed master (today's behaviour) or *Seconds*: a cycle time,
  with no master.
- It's carried on the effect, the effect palette and cue rows. Check the sync field and whether
  `formatVersion` needs a bump.
- **New default (D16):** one bar on the family's `usage` master where one is set, so ad-hoc effects
  use the per-usage default that only effect templates use today.
- Read `FU-FE-FX-SPEED-SPELLING` and `FU-FX-RATE-MASTER-CLEAR`.

### Cues

**S16 — Speed masters on a cue.**
- A cue can set masters' BPM when it runs: a list of master and BPM pairs.
- **When it applies (D15).** Whenever the cue becomes live: GO into it, GO TO it, or GO BACK onto
  it. It never tracks.
- Hook it beside cue events (`EffectsService.onCueGo`). That hook fires on GO into the cue only,
  so the GO TO and GO BACK paths need their own call.
- It's a portable cue field, so expect a `formatVersion` bump.
- It's independent of R1 because it's content of the cue, not membership.

### Research and design

Each produces a plan, not a feature.

**R1 — The cue model (D1).** Cues as project records, with stacks as ordered membership. The
resulting plan must cover:
- **Membership.** What belongs to a membership and what belongs to the cue: number,
  auto-numbering, follow, wait, and fade overrides.
- **Show order and numbering.** The unique-number index moves to membership. Auto-numbering is
  recomputed in the source stack too.
- **Runtime.** `CueStackManager`, live and standby cues, a cue live in two stacks at once,
  `activeCueId`, and the most-recent-stack rule for scenery.
- **Everything that names a cue.** Busk pads, cue slots, prompt-book anchors (per membership or per
  cue?) and MIDI bindings.
- **Sync.** Membership files, a `formatVersion` bump, and a merge story for `FU-SYNC-ORDINAL-DOUBLE`.
- **Explicit delete.** What a delete takes with it, and the *in use* guard.
- **The all-cues table.** Multi-select, delete, and add to stack.
- **Copying a cue from another show on the same venue,** with a decision on version scope: same
  version only, or mapped by key.
- **The MCP tools' new shape.**

**R2 — Editing a cue in the Show view.** Direct editing of a cue's values, Looks and FX in tables
that behave like the programmer's without sending anything. The resulting plan must cover:
- **How it looks in the card view and in the table view.**
- **One standard action set for both views.** Load into programmer, edit, blind edit, record,
  duplicate, remove from stack and delete, with R1's semantics.
- **Blind edit.** *Blind edit in programmer*, possibly as the second state of the edit button.

R2 follows R1.

**R3 — Applying to a selection.** One model for putting a Look, a palette or an effect onto a
selection of fixtures and properties, replacing the seven mechanisms in §1. The resulting plan must
cover:
- **The per-property popup**, choosing a palette for any family, not only colour.
- **How the mask, the selection and the layer relate.**
- **Which existing surfaces fold in, and which stay as shortcuts.**

It should start from `docs/lighting-composition-model.md`.

**R4 — The effects survey.**
- **What other desks offer.** Survey effect families, targetable attributes and parameters on
  ChamSys MagicQ, grandMA3, ETC Eos and Hog 4: size, rate, offset, direction, wings, groups,
  waveform, fade and spread.
- **Compare with ours.** Set that against the 24 built-ins and the parameters they take. Known gaps:
  no zoom effect, and ColourCycle has a fade ratio but no direction.
- **Output:** an effects plan, which also says whether the busk and editor surfaces need to change
  to carry the new parameters.

**R5 — Project structure and navigation.**
- **Survey these paths:**
  - how the operator reaches the projects list
  - what the top-level project screen shows, and what it leaves out
  - which actions exist only in a project's ⋮ menu
  - which install settings the nav lacks after S2
  - the nav registry's grouping overall
- **Design re-attach.** Attach a standalone or detached show to a venue version: Detach in reverse.
  - Detach keeps uuids, so a detached show's rows can be matched to the venue's by uuid, falling
    back to key.
  - What differs becomes facet overrides or show additions.
- **Output:** a navigation plan, plus a venue-attach plan if re-attach is large enough to need one.

## 4. Order

| # | Session | Needs | Done |
|---|---|---|---|
| 1 | S1 Palettes reach cells | — | `4a1a8fb4` |
| 2 | S2 Chrome and defaults | — | `f6b37b38` |
| 3 | S3 Cue list fixes | — | |
| 4 | S4 Templates → palettes | — | |
| 5 | R1 Cue model | — | [`cue-model-plan.md`](cue-model-plan.md) |
| 6 | S5 Grand master | S4 | |
| 7 | S6 Make rig safe, Undo / Quit desk | S4 | |
| 8 | S10 Record polish | S4 | |
| 9 | S11 Position scale | S4 | |
| 10 | S7 Group member order | — | |
| 11 | S8 Per-patch locate | S11 | |
| 12 | S12 Fan | S7, S11 | |
| 13 | S13 Aim from the plan | S11 | |
| 14 | S9 Blinder colour | S1 | |
| 15 | S15 Effect timing | S4 | |
| 16 | S16 Speed masters on a cue | S15 | |
| 17 | S14 Applied tables | S4 | |
| 18 | R2 Show cue editing | R1 | [`show-cue-editing-plan.md`](show-cue-editing-plan.md) |
| 19 | R3 Applying to a selection | S1, S14 | [`apply-model-plan.md`](apply-model-plan.md) |
| 20 | R4 Effects survey | S15 | |
| 21 | R5 Project structure | S2 | |

R1 is early because more of the notes wait on it than on anything else. The R sessions can run in
parallel with the S sessions, since they land only documents.

## 5. Not in this plan

- **FX survey items** beyond D7 and the default timing: zoom effects, and ColourCycle direction and
  fade. They are R4's.
- **The standardised cue action set and in-place cue editing.** These are R2's. S3 fixes only the
  three actions that already exist.
- **Cross-show cue copy.** This is R1's. `copy_to_project` already copies whole stacks between
  projects, and R1 decides how a single shared cue travels.
