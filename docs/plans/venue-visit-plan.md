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
> **Revised 2026-10-15.** The five R sessions are done and produced six plans. §4 sorts every
> session across all of them into lanes that run in parallel (P6), and course checks fall due by
> count and by event. P1 became a checklist, and D20 and P5 were added.
>
> **Checked 2026-10-16 (K1).** Twelve sessions had merged since the lanes, S7 while K1 ran. K1 corrected
> the plans they touched, folded the Rig and output lane, opened Apply next, and gave P5 its claiming rule.
>
> **Checked 2026-10-16 (K2).** Due when R1.4 merged. Four sessions had merged since K1 (R1.3, R4.2, Nav.1
> and R1.4). K2 corrected the plans for the shared cue, recorded R1.4's known gaps with the sessions
> that own them (and one as `FU-BUSK-PAD-STALE-NUMBER`), and folded the Cues lane.
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
| D3 | **Position is centre-relative DMX, −127…+127**, with 0 at the centre of travel (DMX 128) and the fine channel still underneath (S11 landed it as exactly DMX − 128, so −128…+127). It applies everywhere an operator types or reads position: programmer, fixture sheet, cue values, spread, FX editor and palettes. A generic position palette means the same fraction of travel on every head, not the same angle. |
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
| D20 | **A cue sets its speed masters on every GO into one of its entries**, GO TO and GO BACK included, even when another stack already holds the cue live. Tempo is state the cue re-asserts. Its events are different: they fire once, on the cue's first hold (cue model plan D15). Agreed 2026-10-15, after R1. A loose press (a pad, a slot, `apply_cue`, `FireCue`) sets them too, as it brings the cue on as a GO does; a reload's re-press does not (Chris, 2026-10-17, K3; S16). |

The plan adds its own:

- **P1 — One PR per session, per CLAUDE.md §"Git workflow".** Every session in §4, whichever plan
  it belongs to, works this way.
  - **Starting.** It works on a branch named `claude/vv-<id>` (for example `claude/vv-r1.1`), in its
    own worktree, cut from an up-to-date `main` with every session it needs merged. The branch is
    its claim: `gh pr list` and `git branch -r` show what is taken. It may instead start on a
    predecessor whose PR is open and green, and its own PR then says *stacked on #n*, so the two
    merge in order. It never runs a server on :8413, where the desk runs.
  - **Before the PR:**
    1. **Main merged in.** It merges `main` into its branch (never rebases) and resolves the shared
       docs. If another session bumped `formatVersion` meanwhile, it takes the next number (P5).
    2. **Gates.** `./gradlew test` and `npm run check` are green.
    3. **Docs.** Its engineering-doc paragraphs are written, and CLAUDE.md (root or `frontend/`) is
       updated wherever it lists an endpoint, frame, table or tool the session changed.
    4. **Follow-ups.** An item the session closes becomes a one-line row in `followups.md`'s
       Completed table. An item its plan's "Follow-ups to record" assigns to it is added.
    5. **Operator check.** What only the rig can prove gets an Outstanding row in
       `manual-validation.md`, from its plan's Verification section where it has one.
    6. **Hand-offs.** Where its plan says "whichever lands second…", or the session changes
       something another plan or §4 assumes, it makes that edit in the same PR.
    7. **Done-marker.** A second commit on the branch, *Mark <id> done*, citing the change
       commit(s), never the merge commit. It marks the session in its own plan only. An S
       session's entry in §3 becomes a one-line row, struck through, with the SHA. An R-plan
       session's heading gains its marker, as that plan's P1 says. A session never edits §4.
    8. **One-page copies.** The checked-in HTML and its artifact are left alone; course checks
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
- **P5 — No plan names a `formatVersion`; merge order assigns it.** A session that bumps it:
  - writes the next number free on `main` when it branches, and puts `sync vN` in its PR title, so
    `gh pr list` shows two sessions on one number before either merges;
  - if a PR with the same number merges first, renumbers in its merge of `main` (P1 step 1), in that
    merge commit, whose message names both numbers (as S8's `37742a48` did): `SUPPORTED_FORMAT_VERSION`
    and the writer's `formatVersion`, the importer's version comment, `sync-engineering.md`'s
    §"Version N" (above the other's), every `formatVersion N` it wrote in CLAUDE.md, the engineering
    docs, `followups.md` and its plan row, and `ProjectRoundTripTest` and `RichProjectFixture`;
  - never takes or changes a number `main` already has.

  Taken: S11 28, S15 29, R4.1 30, S8 31, R1.3 32, R4.2 33, R4.3 34. Still to bump: S16, and possibly
  venue-attach session 1.
- **P6 — Lanes, bodies in their plans.** §4 sorts every session across this plan and the six R
  plans into lanes that run in parallel. A session's body and its done-marker live in its own plan.
  §4 holds no status, so parallel PRs never edit it. Where §4 and a plan's own dependencies
  disagree, the plan wins and the next course check corrects §4.

## 3. Sessions

Grouped by area. §4 gives the order.

### Correctness and quick wins

- ~~S1 — Palettes reach a multi-head fixture's cells~~ — done, `4a1a8fb4`.
- ~~S2 — Chrome and defaults (D4)~~ — done, `f6b37b38`.
- ~~S3 — Cue list fixes that need no new model (D11)~~ — done, `e2153f5c`.
- ~~S4 — Templates become palettes (D2)~~ — done, `b954eee1`.

### Rig and output

- ~~S5 — Grand master (D5)~~ — done, `6729e6f9`.
- ~~S6 — Make rig safe, its Undo, and Quit desk (D9, D18, D19)~~ — done, `fa5291c1`.
- ~~S9 — Blinder colour (D8)~~ — done, `aeada21c`.
- ~~S7 — Group member order in the Groups views~~ — done, `d48ff355` (drag in the group sheet's
  Members view, the list numbered and undragged by Chris's call; each strategy's step from the desk;
  a spread over one group runs in its member order).
- ~~S8 — Per-patch locate (D6, D17)~~ — done, `2e6963ee` (DMX bytes per unit, read from the output;
  in a show on a venue it is the show's Focus override; MCP reads and writes it; `formatVersion` 31).

### Programmer

- ~~S10 — Record polish~~ — done, `499c0d88` (*All attributes* is a switch that hides the four
  family checkboxes, `MaskToggles` for R3.4).
- ~~S11 — Position in −127…+127 (D3, P3)~~ — done, `cb71b335` (the scale is exact, DMX − 128, so
  DMX 0 reads −128; the intent is `pos:`; `formatVersion` 28).
- ~~S14 — Full-width applied tables~~ — done, `dae7dd5` (one route with a tab per table,
  `/programmer/applied?table=`, under the Programmer row; a row opens what the rail's row opens, by
  Chris's calls of 2026-10-17).

**S12 — Fan in and fan out.**
- Spread gains a relative mode: a fan amount around each head's current position.
- It works pan-only, tilt-only or both, symmetric about the selection's centre in rig order. A
  selection that is one group is in that group's own member order, as a spread over one already is
  since S7.
- Encoders and a drag control set the amount. It sits on S11's scale and S7's order.
- Start with a one-board sketch of the panel. No separate design session.

**S13 — Aim from the floor plan.**
- Click to aim the selection, single or multiple, on the Stage view's Plan camera.
- Height comes from a Z control or a region's surface.
- Aim also opens from the programmer.
- Points outside the stage (auditorium, walls, the room) are reachable because the plan extends to
  the room, with numeric entry for anything off the drawing.
- Show skipped heads by reason, as the route already answers them.

### Effects

- ~~S15 — Effect timing (D7) and slower defaults~~ — done, `6d7ca0cf`.

### Cues

- ~~S16 — Speed masters on a cue (D15, D20)~~ — done, `8236eb9` (master and BPM pairs on the cue, set
  by `activateEntry` and `takeLooseCue`, never by a reload; a follower or a deleted master is skipped
  and logged; edited in the Cue tab; copies map a master by index; `formatVersion` 35).

### Course checks

**K — Check the course.** Each check lands documents only, as one PR on `claude/vv-k<n>`, and the
lanes keep running while it does. Checks are numbered as they run. One is due:
- after about eight sessions have merged since the last check;
- when R1.4 merges, because the plans still to run were written against the old cue model (K2 ran it);
- when every lane is empty, to close the plan.

Each one:
- checks every done-marker against `git log`, and names any `claude/vv-` branch that has gone
  quiet without a PR;
- re-reads the plans with sessions still to run against the code merged since the last check, and
  fixes what that code has made wrong. It reads only the plans that code touches, so a check after
  independent sessions is quick;
- re-checks §4's lanes and Needs;
- triages the venue-visit rows in `manual-validation.md`;
- refreshes the one-page copy and republishes its artifact;
- brings Chris any question about priorities.

The closing check moves this plan and the six R plans to `completed/`. Another evening on the rig
calls for an unscheduled check first, to file its notes into the lanes. Each check, once merged, is
a one-line row here.

- ~~K1 — after twelve sessions~~ — done, `64ab8eaf` (plans corrected for R1.1, R1.2, R4.1, S6, S8,
  S11 and S15; Rig and output folded; Apply opened next; P5's claiming rule; no manual check run).
- ~~K2 — after R1.4~~ — done, `fff3addb` (plans corrected for the shared cue, R1.3, R4.2 and Nav.1;
  R1.4's gaps to R2.3, R2.4 and `FU-BUSK-PAD-STALE-NUMBER`; the Cues lane folded; no manual check run).
- ~~K3 — after twelve more~~ — done, `d9cb8382` (plans corrected for R3.1–R3.4, R4.3, R4.6, R4.7,
  R2.3, R2.4 and Nav.2–Nav.4; R1.6's and Att.3's needs met; nine questions answered by Chris, in
  the plans' question lists; no manual check run).

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

Every session still to run, in **lanes** that run in parallel (P6). Session ids:

- **S*n*** is this plan's.
- **R1.*n*** is [`cue-model-plan.md`](cue-model-plan.md)'s session *n*, **R2.*n***
  [`show-cue-editing-plan.md`](show-cue-editing-plan.md)'s, **R3.*n***
  [`apply-model-plan.md`](apply-model-plan.md)'s and **R4.*n*** [`effects-plan.md`](effects-plan.md)'s.
- **Nav.*n*** is [`navigation-plan.md`](navigation-plan.md)'s and **Att.*n***
  [`venue-attach-plan.md`](venue-attach-plan.md)'s.

**Picking a session.** A lane runs its sessions in order. In a lane, the next session is the first
that is neither done (its own plan marks it) nor claimed (a `claude/vv-<id>` branch or PR exists),
once everything in its *From other lanes* column has merged. Run three or four lanes at once. Two
chains reach R3.8: Cue editor (R2.6, one deep since R2.5 merged) and Apply (R3.5–R3.7, three).
Apply is the longer again, so keep it moving.
Course checks (§3) fall due by count and by event, not by place in a lane.

K1 (2026-10-16) took out the sessions done since the lanes were drawn and the needs that have merged,
folded the Rig and output lane (S6, S9 and S8 are done), and opened Apply next. R1.3 and R4.2 were in
flight as it ran.

K2 (2026-10-16) took out R1.3, R4.2, Nav.1 and R1.4 and folded the Cues lane. R1.4 unblocked the Cue
editor lane and R1.5. R4.3 (PR #176), R3.1 and Nav.2 were in flight as it ran.

K3 (2026-10-17) took out R3.1, R4.3, Nav.2, R2.3, R4.6, R3.3, Nav.3, R2.4, R3.2, R4.7, Nav.4 and R3.4,
and every need they met. R2.5 (#190), Nav.5 (#191) and R4.4 (#192) were in flight as it ran. All three
merged before K3 did, and are out of the lanes. Three lanes have not started: Cue library,
Programmer and Venue attach. Each can start now.

**Cue editor.**

| Session | From other lanes |
|---|---|
| R2.6 The Looks and FX tables | — |
| R2.7 Edited since GO and Update stage | — |
| R2.8 Show on stage | — |

**Cue library.** Not started; S16 and R1.5 can both start now.

| Session | From other lanes |
|---|---|
| S16 Speed masters on a cue | — |
| R1.5 The Cues table | — |
| R1.6 Copy from another show | — |
| R1.7 The MCP tools | — |

**Programmer.** Not started.

| Session | From other lanes |
|---|---|
| S13 Aim from the plan | — |
| S12 Fan | — |

**Effects.**

| Session | From other lanes |
|---|---|
| R4.5 A stack's effect size | — |

**Apply.** R3.5 and R2.6 both change `AddLayerSheet`: R3.5 moves the programmer's door and R3.8 the
cue editor's, so either order works (the apply plan's §5).

| Session | From other lanes |
|---|---|
| R3.5 The palette popup and the one picker | — |
| R3.6 Effects in the popup | — |
| R3.7 The busk side sheets | — |
| R3.8 Into the cue editor | R2.6 |

**Navigation.** Nav.4 and Nav.5 ran ahead of S14, the lane's last session, which stays unclaimed.

| Session | From other lanes |
|---|---|
| S14 Applied tables | — |

**Venue attach.** Not started. Nav.4 has merged, so Att.3 hangs off its project actions.

| Session | From other lanes |
|---|---|
| Att.1 The origin, and the pairing | — |
| Att.2 The attach | — |
| Att.3 The sheet | — |

## 5. Not in this plan

Each R plan's own out-of-scope section holds what it declined.
