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
>
> **Revised 2026-10-15.** The five R sessions are done and produced six plans. §4 is now the run order
> across all of them (P6), with seven course checks (K1–K7). P1 became a checklist, and D20 and P5
> were added.
>
> A one-page copy for human readers is
> [`venue-visit-design/venue-visit.html`](venue-visit-design/venue-visit.html), with a live copy at
> <https://claude.ai/artifact/GXeetYDiTwUhE4Wxvo3MRF> (the checked-in file is the authority).

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
| D20 | **A cue sets its speed masters on every GO into one of its entries**, GO TO and GO BACK included, even when another stack already holds the cue live. Tempo is state the cue re-asserts. Its events are different: they fire once, on the cue's first hold (cue model plan D15). Agreed 2026-10-15, after R1. |

The plan adds its own:

- **P1 — One PR per session, per CLAUDE.md §"Git workflow".** Every session in §4, whichever plan
  it belongs to, does this before the PR:
  1. **Gates.** `./gradlew test` and `npm run check` are green.
  2. **Docs.** Its engineering-doc paragraphs are written, and CLAUDE.md (root or `frontend/`) is
     updated wherever it lists an endpoint, frame, table or tool the session changed.
  3. **Follow-ups.** An item the session closes becomes a one-line row in `followups.md`'s
     Completed table. An item its plan's "Follow-ups to record" assigns to it is added.
  4. **Operator check.** What only the rig can prove gets an Outstanding row in
     `manual-validation.md`, from its plan's Verification section where it has one.
  5. **Hand-offs.** Where its plan says "whichever lands second…", or the session changes something
     another plan or §4's Needs assumes, it makes that edit in the same PR.
  6. **Done-marker.** A second commit on the branch, *Mark <id> done*, citing the change commit(s),
     never the merge commit. It marks the session's heading in its own plan, and its row in §4:
     the SHA for an S session, *done* for any other.
  7. **One-page copies.** The checked-in HTML and its artifact are left alone; course checks
     refresh them.
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
- **P5 — No plan names a `formatVersion`.** A session that bumps it takes the next number free when
  it lands. Several sessions across these plans bump it: S16, possibly S8 and S15, the cue model
  plan's session 3, the effects plan's sessions 1–3 and possibly venue-attach session 1.
- **P6 — One queue, bodies in their plans.** §4 orders every session across this plan and the six R
  plans. A session's body and its done-marker live in its own plan, and its row in §4 says *done* and
  links there. Where §4's Needs and a plan's own dependencies disagree, the plan wins and §4 is
  corrected.

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
  Give it a cue picker that lists cues by name, whatever stack they're in, so it serves loose cues
  once the cue model plan's session 4 lands.

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
- **When it applies (D15, D20).** On every GO into one of the cue's entries, GO TO it, or GO BACK
  onto it, even when another stack already holds the cue. It never tracks.
- **It lands after the cue model plan's session 2** (holds), and hooks the entry's GO rather than the
  hold registry: D20 fires where events, on the first hold, don't.
- It's a field of the cue, not of its entries, so a shared cue sets the same tempo from every stack.
- It's a portable cue field, so expect a `formatVersion` bump (P5).

### Course checks

**K1–K7 — Check the course.** Each lands documents only, as one PR. There is a check at every wave
boundary in §4, and one within a wave after about eight sessions. If §4 is reordered, it keeps that
spacing. Each one:
- checks every done-marker in §4 and the R plans against `git log`;
- re-reads the plans with sessions still to run against the code that has landed since the last
  check, and fixes what it has made wrong. It reads only the plans that code touches, so a check
  after independent sessions is quick;
- re-checks §4's Needs and waves;
- triages the venue-visit rows in `manual-validation.md`;
- refreshes the one-page copy and republishes its artifact;
- brings Chris any question about priorities.

K7 also closes the plan: it moves this plan and the six R plans to `completed/`. Another evening on
the rig calls for an unscheduled check first, to file its notes into the queue.

### Research and design — done

Each produced a plan, which wins inside its scope. Their sessions are in §4.

- ~~R1 — The cue model (D1)~~ — done, [`cue-model-plan.md`](cue-model-plan.md), seven sessions.
- ~~R2 — Editing a cue in the Show view~~ — done, [`show-cue-editing-plan.md`](show-cue-editing-plan.md),
  eight sessions.
- ~~R3 — Applying to a selection~~ — done, [`apply-model-plan.md`](apply-model-plan.md), eight
  sessions.
- ~~R4 — The effects survey~~ — done, [`effects-plan.md`](effects-plan.md), seven sessions.
- ~~R5 — Project structure and navigation~~ — done, [`navigation-plan.md`](navigation-plan.md) (five
  sessions) and [`venue-attach-plan.md`](venue-attach-plan.md) (three).

## 4. Order

One queue across this plan and the six R plans (P6). Session ids:

- **S*n*** is this plan's.
- **R1.*n*** is [`cue-model-plan.md`](cue-model-plan.md)'s session *n*, **R2.*n***
  [`show-cue-editing-plan.md`](show-cue-editing-plan.md)'s, **R3.*n***
  [`apply-model-plan.md`](apply-model-plan.md)'s and **R4.*n*** [`effects-plan.md`](effects-plan.md)'s.
- **Nav.*n*** is [`navigation-plan.md`](navigation-plan.md)'s and **Att.*n***
  [`venue-attach-plan.md`](venue-attach-plan.md)'s.
- **K*n*** is a course check (§3).

Within a plan, sessions run in that plan's order unless it says otherwise (R2.1 and R2.2, and Nav.1–3,
are independent). **Needs** lists only what a session needs from outside its own plan. The waves are
a suggested order; Needs is the constraint.

**Landed**

| Session | Done |
|---|---|
| S1 Palettes reach cells | `4a1a8fb4` |
| S2 Chrome and defaults | `f6b37b38` |
| S3 Cue list fixes | `e2153f5c` |
| S4 Templates → palettes | `b954eee1` |
| R1–R5 | the six plans above |

**A — nothing blocks these**

| # | Session | Needs | Done |
|---|---|---|---|
| 1 | S5 Grand master | — | |
| 2 | S6 Make rig safe, Undo / Quit desk | — | |
| 3 | S10 Record polish | — | |
| 4 | S11 Position scale | — | |
| 5 | S15 Effect timing | — | |
| 6 | S7 Group member order | — | |
| 7 | S9 Blinder colour | — | |
| 8 | S14 Applied tables | — | |
| 9 | K1 Course check | rows 1–8 | |
| 10 | R2.1 Blind holds the programmer's effects | — | |
| 11 | R2.2 A cue's children keep their uuids | — | |
| 12 | R3.1 The press on the desk | — | |
| 13 | Nav.1 Sections and the registry | — | |
| 14 | Nav.2 Off the rig | — | |
| 15 | Nav.3 The switcher | — | |
| 16 | Att.1 The origin, and the pairing | — | |
| 17 | Att.2 The attach | — | |
| 18 | K2 Course check | wave A | |

**B — the cue model.** The long pole: R2.3 onwards waits on it. R1.1 is the startup migration, run on
a copy of the desk's database first. K3 matters most: the plans still to run were written against
the old cue model.

| # | Session | Needs | Done |
|---|---|---|---|
| 19 | R1.1 Entries and the migration | — | |
| 20 | R1.2 Holds | — | |
| 21 | R1.3 Sync | — | |
| 22 | R1.4 Sharing and explicit delete | — | |
| 23 | K3 Course check | wave B | |

**C — on the scale, the timing and the holds**

| # | Session | Needs | Done |
|---|---|---|---|
| 24 | S8 Per-patch locate | S11 | |
| 25 | S12 Fan | S7, S11 | |
| 26 | S13 Aim from the plan | S11 | |
| 27 | S16 Speed masters on a cue | S15, R1.2 | |
| 28 | R4.1 Size, direction and N-shot | S15 | |
| 29 | R4.2 Shaping the spread | — | |
| 30 | R4.3 One effect over a selection | — | |
| 31 | R1.5 The Cues table | Nav.1 (its Library row; either order) | |
| 32 | R3.2 Local effects follow the selection | S15, R4.3 | |
| 33 | R3.3 One press in the client | — | |
| 34 | R3.4 One mask | S10 | |
| 35 | K4 Course check | wave C | |

**D — cue editing, the rest of effects, and the project's home**

| # | Session | Needs | Done |
|---|---|---|---|
| 36 | R2.3 The action set | R1.4 | |
| 37 | R1.6 Copy from another show | R2.3 (for *Duplicate*; else it builds it) | |
| 38 | R1.7 The MCP tools | — | |
| 39 | R2.4 The editor and its two hosts | — | |
| 40 | R2.5 The Values grid | — | |
| 41 | R2.6 The Looks and FX tables | — | |
| 42 | R2.7 Edited since GO and Update stage | — | |
| 43 | R2.8 Show on stage | — | |
| 44 | K5 Course check | rows 36–43 | |
| 45 | R4.4 Fading by size | R1.2 | |
| 46 | R4.5 A stack's effect size | S5 (its shape; either order) | |
| 47 | R4.6 Beam effects | — | |
| 48 | R4.7 Palette Steps and Slot Steps | — | |
| 49 | Nav.4 One set of project actions, and a show's home | — | |
| 50 | Nav.5 A venue's home | — | |
| 51 | Att.3 The sheet | Nav.4 (else it uses the card's ⋮) | |
| 52 | K6 Course check | wave D | |

**E — applying everywhere**

| # | Session | Needs | Done |
|---|---|---|---|
| 53 | R3.5 The palette popup and the one picker | — | |
| 54 | R3.6 Effects in the popup | — | |
| 55 | R3.7 The busk side sheets | — | |
| 56 | R3.8 Into the cue editor | R2.6 | |
| 57 | K7 Course check and close | wave E | |

## 5. Not in this plan

Each R plan's own out-of-scope section holds what it declined.
