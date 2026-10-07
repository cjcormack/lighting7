# Scenery across the desk — the programmer's hands, Record, the Stage popover and the book

> **Document status: APPROVED, 2026-10-07 — session 1 (the programmer's scenery on the desk,
> backend) shipped the same day (`32ce090a`), session 2 (`SceneryControl` and the rail's
> Scenery band, frontend) the same day (`95fe24e4`), session 3 (Record, Include and Update,
> backend + frontend) the same day (`1e83f790`), and session 4 (the Stage view and the Positions
> plan, backend + frontend) the same day (`a68e3a73`); session 5 to come.** Chris approved the design on
> 2026-10-07 and answered its questions the same day (§10). He set two conditions: stay consistent with the desk's
> current design language, and take iPhone, iPad and desktop into account. D17 and §4 answer both.
> The design record is [`scenery-programmer-design/INDEX.md`](scenery-programmer-design/INDEX.md).
> The design document, with its mock-ups, is at
> <https://claude.ai/code/artifact/0def61d0-9e4c-4ba4-ba7d-a1bd0d339f71>. It is private to Chris and is
> a convenience; the checked-in record is the authority.
>
> This document is the engineering half. Where it and the record disagree, this plan wins on
> behaviour and the mock-ups win on layout and copy.

## 1. Context

Chris's brief, 2026-10-07: tie theatre and scenery state (tab positions, a piece flown in) more
closely into the programmer, Looks, templates, the Stage view and the Prompt Book. His starting
belief was that scenery can only be changed in the Show view, in card mode.

The findings that shape the plan:

- **The belief is half right.** Three owners carry scenery: a cue's changes, a stack's set and a
  Look's *scenery while live* (stage-view plan session 8). All three are edited through one
  component, `components/scenery/SceneryEditor.tsx`. Only the cue's changes are stuck behind card
  mode: Cards → unlocked → *Cue properties…*. They cannot be reached from the cue table, the phone
  runner, the Prompt Book, the programmer or the Stage view. The set is edited from *Stack settings*
  in the stack list, and the Look's scenery from its detail sheet.
- **Nothing can move scenery *now*.** The live stage changes only on a GO, a stack stop, a pressed
  Look, or an edit to a stored row. The programmer holds no scenery, so Record has nothing to
  capture (stage-view D13), Blind has nothing to stage, and the Stage view's Output, Output +
  Programmer and Programmer sources all draw the same thing.
- **Every move but a cue's own snaps.** A pressed Look, an edited set, a stack stop and a GO TO all
  jump the piece. Only a cue row has a clock (`SceneryService.durationFor`).
- **The editor offers steps, not a range.** Its choices are closed, half and drawn for a tab, and in
  or out (the piece's Z or its stored trim) for a flown piece. Nothing offers a value in between.
- **Templates are excluded by design** (stage-view D11). A template names no targets of its own, so
  it has nothing to say about a particular sofa.
- **Responsiveness constrains the doors.** The programmer rail's tabs exist only when the rail is
  docked (≥ 1200 px of workspace). The cue table never renders on a phone, because the phone runner
  is always locked. The Prompt Book's rail becomes a drawer below 1040 px. Nothing in the Stage view
  anchors DOM to a 3D object yet; the label layer (`stage3d/stageLabels.ts`) is the precedent for
  projecting a point.
- **"Standby" already means two things.** It is the armed next cue in the cue table (blue) and the
  unfired later cues in the Prompt Book (amber). The book's new line is called *On GO*.

## 2. Decisions

The D-numbers match the design document's. Stage-view plan decisions are named in full
("stage-view D11").

| # | Decision |
|---|---|
| D1 | **The programmer holds scenery.** It is a sparse overlay, element → partial `ElementStates`, one per desk like the programmer, and runtime only. Clear empties it, on the Clear fade, and a project switch drops it. It is written by `programmer.setScenery {elementUuid, state, fadeMs?}` and `programmer.clearScenery {elementUuid?}`, and streamed as `programmer.sceneryState`, backed by a `StateFlow` so the subscription is the snapshot. |
| D2 | **It is the resolver's top tier,** above the programmer's Looks, which stay above cue-layered Looks, cues, sets and base. Blind stages it along with the programmer's Looks, and leaving Blind lands it. |
| D3 | **Scenery is addressed by element, never through the desk selection.** Masks, subselect, spread, MIDI strips and the busk rig learn no new target kind. |
| D4 | **The programmer's scope applies.** Local writes the overlay. A focused Look layer writes that Look's scenery while live, through the Look's own scenery `PUT`. Output is read-only and names each element's source. |
| D5 | **One control, `SceneryControl`, for an element's state everywhere.** It keeps today's steps as presets and adds a range: `open` from 0 to 1, and `trimM` between the piece's in (its Z) and its out (its stored trim). `SceneryEditor`'s rows, the programmer and the Stage popover all use it. |
| D6 | **Moves glide.** Cue rows keep their own `transition`. Anything that snaps today — a pressed Look, a set edit, a stack stop, GO TO landing an earlier change — moves at the element's new optional **`travelS`**, the time for a full travel, scaled by the share of travel moved. A programmer move also runs at `travelS`, but a non-zero programmer fade overrides it. With neither, the move snaps, as today. `visible` never travels. |
| D7 | **Record captures held scenery, narrowing stage-view D13 rather than reversing it.** Into a cue, Record writes a change row for each held element whose state differs from what that cue would track; a held state equal to the tracked one writes nothing. Into a Look, every held state is written, because a Look asserts rather than tracks. A *Record scenery too* row on the Record sheet, beside *Record effects too*, is ticked by default whenever anything is held. The attribute mask does not govern it. |
| D8 | **Include brings a cue's or Look's own scenery into the overlay**, never its tracked state, and Update writes it back. |
| D9 | **Templates still carry no scenery** (stage-view D11 stands; confirmed 2026-10-07). A Look that holds only scenery is the busk vehicle; it can already be pressed with no selection. |
| D10 | **Looks show their scenery where they are listed.** `LookSheet` gains a Scenery read-out column. Record look with only scenery held makes a scenery Look. |
| D11 | **The Stage view moves scenery.** With Edit off, in any camera, clicking a drawn, flown or Set-layer element opens `SceneryControl` for it. The control writes the programmer and says what holds the piece. With Edit on, the form's *Moves with* is filled from a new read. |
| D12 | **The Stage view draws a blind change.** While Blind holds a scenery change, `scenery.state` carries `staged`. Output + Programmer and Programmer draw it; Output draws live. |
| D13 | **Cue scenery reaches the cue table.** `CueSheet` gains a Scenery column. It is a read-out whose button opens `SceneryEditor` with times. `StackDetail` gains *Stack settings…*. Both follow the lock as the cards do. |
| D14 | **The Prompt Book puts scenery on the page.** A Blinds glyph sits beside a marker whose cue moves something. Rail cards list the cue's changes inline. An *On GO* line at the top of the rail names the next GO's moves. When unlocked, a card's *Scenery…* edits in place. |
| D15 | **A live MCP tool, `move_scenery`, moves scenery through the programmer.** It takes `{element, visible?, open?, trimM?, fadeSeconds?, release?}` and writes the same overlay as the Scenery tab, in the current project only. The authoring tools are unchanged. Scenery is drawn and never output, so the tool needs no remote-access gate. *Session 2 amendment:* the tab is the rail's Scenery band (§4), which writes that overlay. |
| D16 | **The Positions plan draws scenery** as the Stage view does, following the window's vis source, so a closed tab or a flown piece shows where the light lands. |
| D17 | **Phone, iPad and desktop use the desk's existing forms** (§4). `SceneryControl` is built from the editor kit. Every pop-up opens through `EditorSurface`. The action bar's Scenery chip is the door to the list wherever the rail's tabs are not docked. *Session 2 amendment:* there is no chip and no tab — scenery is a band of the rail's Stack body, in every arm the rail has (§4). |

The plan adds three decisions of its own:

- **P1 — Five sessions, one PR each**, in §5's order, per CLAUDE.md §"Git workflow". Session 1 comes
  first. Sessions 2 and 3 need it. Session 4 needs 1 and 2. Session 5 needs only session 2's
  control, so it can run beside 3 and 4.
- **P2 — One portable field, no `formatVersion` bump.** `travelS` is an optional param on
  `DrapeParams` and `ObjectParams`. Element params are stored as text, imported verbatim and decoded
  with `ignoreUnknownKeys` (`models/stageScene.kt`), so an older desk keeps the field it does not
  read. Session 1 confirms this against `docs/sync-engineering.md` before relying on it. Everything
  else is runtime state or wire.
- **P3 — Session 1 is additive on the wire.** `scenery.state` gains `staged` and a per-entry
  `source`, both optional for the client, and the programmer frames are new. Today's client draws
  exactly as before until session 2 adopts them. No PR leaves either half broken.

## 3. The model

### 3.1 Portable

- **`travelS`** (D6) is a `Double?` on `DrapeParams` (`DRAW` or `FLY` only) and `ObjectParams`
  (`flies` only), from 0.1 to 600 s. It is refused by name anywhere else, with every problem
  reported at once, as `parseElementParams` already does. It is set in Edit's form and through
  `set_scene`'s element params.

### 3.2 Runtime

- **`state/ProgrammerScenery.kt`** (new) holds the overlay (D1): `elementUuid →
  {state: ElementStates, fadeMs: Long?}`, exposed as a `StateFlow`. It is project-scoped like
  `SceneryService`: attached to the show and cleared by the project collector.
  `ProgrammerStore.clearAll` clears it with the same fade. A write is validated against the
  element's kind with `parseSceneryState` (`models/scenery.kt`), so `open` lands only on a `DRAW`
  drape and `trimM` only on a flown piece.
  *Session 1 amendment:* `ProgrammerStore.clearAll` takes no fade, and `ProgrammerWriter.clearAll`
  returns before reaching it when no value is held — exactly the case of a programmer holding only
  scenery — so the overlay is cleared in `clearProgrammerCompletely` (`routes/programmer.kt`), the
  one path behind `programmer.clearAll`, with the Clear's `fadeMs`. The
  overlay lives on `State` (`state.programmerScenery`) beside `sceneryService`, attached to the
  current project at start and by the project collector, rather than on the show.
- **`show/SceneryResolver.kt`** gains a fifth input and tier, `Source.Programmer`, above
  `ProgrammerLook` (D2). `SceneryService` follows the overlay's flow beside `layersFlow`. While
  `programmerStore.blind` is set, the live resolve leaves out both programmer tiers, as it does
  today for Looks. A second resolve that includes them answers `staged`, but only when it differs
  from live (D12).
  *Session 1 amendment:* the service resolved only elements a stored change names, so a piece only
  the programmer held would have had no entry. It now resolves those a stored change names, those
  the programmer holds, and those the last frame carried — so a released piece flies home before it
  leaves the frame, which it does once landed on its base.
- **Durations** (D6), in `durationFor`:
    - a cue row of the cue just GO'd keeps its own transition;
    - a programmer move uses its `fadeMs` when greater than 0, otherwise `travelS`;
    - everything else uses `travelS`.

  *Session 1 amendment:* D1's "Clear empties it, on the Clear fade" needed a fourth arm the three
  above do not give — a released piece's move home is sourced from a lower tier, so it would have run
  at `travelS`. A release carrying a fade above 0 (Clear's, or `programmer.clearScenery`'s optional
  `fadeMs`, which `move_scenery`'s `release` with `fadeSeconds` uses) is remembered by element and
  read once by the next recompute: that move runs on it, unless a GO'd cue's own clock decides it.
  `travelS` is scaled by the share of travel moved: `|Δopen|` for a drape, and `|ΔtrimM| / |out − in|`
  for a flown piece, which is a full travel when in equals out. With no `travelS` the move snaps.
- **Record** (D7). `ProgrammerRecording` gains `scenery`. A cue's tracked state comes from
  `trackedSceneryAt` (`routes/projectScenery.kt`), so the "differs from tracked" test is the cue
  card's own.
    - CREATE writes rows where the state differs from tracked.
    - MERGE replaces rows for the held elements.
    - REMOVE deletes rows for the held elements.
    - UPDATE_EXISTING is MERGE into Include's source.
  A Look takes every held state.
  *Session 3 amendment:* the held overlay is read beside the recording (`heldSceneryOf`) rather than
  carried on `ProgrammerRecording`, and the rules live in `routes/programmerSceneryRecord.kt`. The
  "tracked" a held piece is compared with is `sceneryBeneathCue` — the base, the stack's set and
  every earlier STANDARD cue, the resolver's own fold without the cue's rows — not
  `trackedSceneryAt`, which hides the keys the cue sets itself and leaves out the base, so a piece
  held at its base would have read as a change. "Equal to tracked writes nothing" holds only where
  the cue has **no row** for the piece: a row the cue already has is replaced whatever it says (it is
  the cue's own assertion, and leaving it would make Record not record what is on stage), its clock
  kept unless the held piece brought one from Include, and a row already saying exactly the held
  state is left untouched and not counted. UPDATE_EXISTING is MERGE into the cue the record names,
  not into Include's source — Record takes its target from the request. A MARKER records none and
  warns.
- **Include** (D8). `routes/programmerInclude.kt` and `programmerLookInclude.kt` replace the overlay
  entries for the elements the source names with its own rows. A cue's per-row `transitionMs` is
  kept beside the entry, so Update writes it back unchanged.
  *Session 3 amendment:* the load is `ProgrammerScenery.include`, called from
  `performProgrammerInclude` (`routes/programmerSurface.kt`), the one sequence both include routes
  and the AI share, rather than from the two route files; a later move keeps the held entry's
  `transitionMs`, and a cue that only moves scenery sets the include target. Update writes the
  overlay back in **Mode A only** — Mode B leaves it, for the reason it leaves the layer stack: held
  scenery says nothing about which of the named cues it belongs to. And the overlay keeps a
  **baseline** (what the last Include, retargeting Record or Mode A Update left the source saying),
  because the source strip's Update counted values only and stayed disabled as *in sync* after the
  tabs moved — the §9 check could not have been run; see §3.3.

### 3.3 The wire

- **`programmer.setScenery {elementUuid, state, fadeMs?}`** and **`programmer.clearScenery
  {elementUuid?}`** are inbound on `plugins/ProgrammerSocket.kt`. (*Session 1 amendment:*
  `clearScenery` also takes an optional `fadeMs`, the clock a released piece flies home on; both reply
  `programmer.sceneryState`, since `handleProgrammer` answers every message.) A refused write answers
  `programmer.error` with the named problem. These are operator gestures of the same tier as
  `programmer.set`, so `FU-AUTH-WS-PER-MESSAGE` is not fired.
- **`programmer.sceneryState {projectId, elements: [{elementUuid, state}]}`** is outbound and backed
  by a `StateFlow`.
- **`scenery.state`** gains a `source` on each entry and an optional `staged` list:
    - `source` is `{kind: base | set | cue | cueLook | programmerLook | programmer, stackId?, cueId?,
      label?, lookId?, name?}`;
    - `staged` is `[{elementUuid, state, from, elapsedMs, durationMs}]`, absent unless Blind holds a
      change.
- **`GET /api/rest/projects/{id}/stage-elements/{eid}/scenery`** (D11) returns `{cues: [{stackId,
  cueId, label, state, transitionMs}], sets: [{stackId, name, state}], looks: [{lookId, name,
  state}]}`, read from the three owner tables.
- **Record** requests gain `scenery: Boolean`, default true. `LookDto` gains `scenery: [{elementName,
  state}]`, with `@EncodeDefault(ALWAYS)` and optional on the client, for D10's column.
  *Session 3 amendment:* each `LookDto.scenery` entry carries `elementUuid` too, so the column names
  a flown piece's *in* and *out* from the scene, batched in one query for a list
  (`lookScenerySummariesFor`). `programmer.sceneryState` gains an optional `changedSinceInclude` —
  the held pieces Update would write that the source does not already say — which the source strip
  adds to its dirty count; the client cannot diff the overlay itself because the include target and
  the overlay arrive on two unordered flows. Record, Include and Update responses carry
  `sceneryWritten` / `sceneryRemoved` / `sceneryAlreadyTracked` / `sceneryIncluded`. All additive;
  no stored or synced shape changed, so no `formatVersion` bump.
- **`move_scenery`** (D15) is in `ai/AiTools.kt`. `get_current_state`'s `programmer` section gains the
  overlay, and `record_cue`, `update_from_programmer` and `include_into_programmer` mention scenery in
  their descriptions.

## 4. UX

The mock-ups in the design document are the layout authority. Every surface uses a form the desk
already has (D17):

- **`SceneryControl`** is built from the editor kit:
    - an `EditorLabel` with the element's name;
    - a `ToggleGroup` (`size="sm"`) of presets;
    - a `Slider` with an `EditorField` (`m` for a trim, `%` for `open`);
    - a `Shown | Hidden` `ToggleGroup` where it applies;
    - an `EditorReadout` saying what holds the piece, for example *held by the programmer · Q14 had
      it out*.

  It writes as it goes through `useLivePush` (floor 33 ms, a release that always lands), and it has
  no verbs and no `EditorFooter`.
  *Session 2 amendment:* `scenery.state`'s `source` names the element's **top** tier only, so the
  read-out says *held by the programmer*, *held by Night, pressed*, *tracked from Q14*, *held by
  Main's set* or *at its base*; the example's second clause (*Q14 had it out*) would need the tier
  below on the wire, which session 1 does not send. And the control has a second commit mode,
  `release`, for a host whose write is a whole-list `PUT` — `SceneryEditor`'s rows and a focused
  Look's scenery — where writing as it goes would be thirty saves, and thirty refetches of every cue,
  a second.
- **Every pop-up opens through `EditorSurface`.** It is a `w-72` popover on a desk or iPad, a bottom
  sheet on an upright phone, and a right-hand sheet where the viewport is short. Rows are
  finger-sized in the two sheet forms only. The form decides; never a `sm:` variant.
- **Held** marks reuse the programmer's ownership language: `ring-primary` on a held row, the
  `BlindDot` on a staged one, and the tracked hatch from `SceneryReadout` where a row only inherits.
  A row the scope cannot write, such as Output, is disabled with its reason, not hidden.
- **Copy** is sentence case. A verb that opens further UI ends in `…` (*Scenery…*, *Stack
  settings…*, *Edit element…*). Read-outs use ` · `.
- **Icons:** `Blinds` stands for scenery everywhere. `SceneryReadout`'s per-element glyphs
  (`ArrowUpDown` for a trim, `Blinds` for a draw, `Box` otherwise) are reused in rows.

| Surface | Desktop | iPad (704–1200 px of workspace) | Phone |
|---|---|---|---|
| Programmer scenery | the **Scenery** rail tab (`Blinds`, `tabWordClass`), docked at ≥ 1200 px | the action bar's Scenery chip opens the same list through `EditorSurface`; rail tabs are docked-only | the chip opens a bottom sheet; the 44 px handle keeps the stack. *Session 2 amendment:* on every width it is the rail's Scenery band instead (below) |
| Stage popover | popover anchored to the piece's projected centre, recomputed per frame through the label layer's projection | popover; Edit still needs ≥ 640 px | bottom sheet; a tap selects, a finger still pans |
| Cue table column | read-out with `ReadOutButton` to the editor (`wide`) | the same; the table scrolls sideways under its sticky first column | the table never renders; the card readout shows scenery as today |
| Prompt Book | *On GO* line at the top of the rail | below 1040 px the rail is a drawer; its toggle carries a `Blinds` dot when the next GO moves something | the same drawer; tapping a glyph opens that cue's card |
| Looks column | read-out | scrolls sideways | scrolls sideways |
| Record row | checkbox row beside *Record effects too* | the same | the same, in the full-screen sheet |

The chip reads *Scenery* with no count when nothing is held, and *Scenery 2* when two pieces are
held. On a docked desk it opens the rail on the Scenery tab. Its word hides below `@[800px]` like
the rest of the action bar, and its glyph stays at every width.
*Session 2 amendment:* **there is no chip, and no Scenery tab: scenery is a band of the rail's
Stack body, like the effects**, at its top (it is the top tier, D2), listing what the scope holds,
with *All scenery…* opening the full list through `EditorSurface`. Chris's call on 2026-10-07, once
the tab and the chip had been built and measured: the tab was docked-only, so the chip was the door
everywhere else, and its 40px pushed `Q15 · Update · Revert` half out of a 393px phone's row A (a
`…` menu folding the chip, Include… and Revert was tried, and a wrapping row A before it). A band is
in every arm the rail has — docked, the overlay, the phone's bottom sheet — so it needs no door of
its own, and row A is as it was. The count rides the rail as FX's does: the collapsed strip and the
phone handle (doors that open the rail at the band) and the tabless faces (the overlay header, the
phone sheet's title); the docked Stack tab's face has no room for a third count (211px of strip
against the 224 its three tabs already need), so the band's label carries it there (with a Look layer focused it names the Look instead). This replaces
D17's *the action bar's Scenery chip is the door to the list wherever the rail's tabs are not
docked* and the surface table's chip entries.

## 5. Implementation — five sessions

Each session is one PR. It ends with `./gradlew test` green where anything outside `frontend/`
changed, `npm run check` green where anything inside it changed, the engineering-doc paragraphs
written, and its done-marker here: a one-line row with the session's commit SHA, added on the
branch before its PR merges.

### ~~Session 1 — the programmer's scenery on the desk (backend)~~ — done, `32ce090a`

- **The overlay (D1):** `state/ProgrammerScenery.kt`; the two inbound messages and the outbound
  `programmer.sceneryState` on `ProgrammerSocket`; `clearAll` and the project collector clear it.
- **The tier, Blind and the source (D2, D12, D4):** `SceneryResolver`'s fifth tier;
  `SceneryService` follows the overlay; the staged resolve; `source` and `staged` on
  `ScenerySocket`'s frame.
- **Clocks (D6):** `travelS` on the two param classes and in `parseElementParams`; `durationFor`'s
  three arms, scaled by travel share; `set_scene`'s schema documents the field.
- **`move_scenery` (D15)** and the overlay in `get_current_state`.
- **Sync (P2):** `travelS` set to a non-default value in `testsupport/RichProjectFixture.kt`; the
  round trip and clone keep it.
- **Tests:**
    - the overlay: set, merge, release, clear, project switch, and a refused key per kind;
    - the resolver: the programmer above a pressed Look, above a cue;
    - Blind: `staged` present while it holds a change and absent otherwise;
    - `source` on each tier;
    - durations: a cue row's own transition, programmer fade over `travelS`, `travelS` scaled for a
      half-travel, and a snap with neither;
    - `travelS` refused on a `DEAD` drape and on a flat;
    - `move_scenery`: names, a refusal, release.
- **Docs:**
    - `docs/cue-stacks-engineering.md` §"Scenery" (the tier, clocks, staged);
    - `docs/lighting-composition-model.md` §"Scenery — beside the layers" (D2's tier, and D13
      narrowed by this plan's D7);
    - `docs/fixtures-engineering.md` §"The scene document" (`travelS`);
    - `docs/websocket-engineering.md`;
    - `docs/mcp-engineering.md`'s tool list;
    - `docs/sync-engineering.md` (the params field, P2);
    - root `CLAUDE.md`'s `scenery.state` line, and a line for the `programmer.*Scenery` frames.

### ~~Session 2 — `SceneryControl` and the Scenery tab (frontend)~~ — done, `95fe24e4`

- **The control (D5, D17):** `components/scenery/SceneryControl.tsx` on the editor kit; presets and
  ranges come from `lib/scenery.ts` (in and out from Z and the stored trim). `SceneryEditor`'s rows
  adopt it, keeping their element select, time field and ×.
- **The wire:** `api/sceneryApi.ts` parses `source` and `staged` and the `programmer.sceneryState`
  bridge (form 3); `setScenery` and `clearScenery` go through `useLivePush`.
- **The tab (D1, D4):** `RAIL_TABS` gains `scenery`, docked only. The list is:
    - grouped Venue then Set and ordered by name;
    - filtered by *Set*, *Venue* and *Moving only*, stored per window in `sessionStorage`;
    - one `SceneryControl` per element, with the held ring, its source read-out and a release ×.
  In Output scope the rows are disabled with *Output is read-only*. A focused Look layer writes the
  Look's scenery through `setLookScenery`.
  *Session 2 amendment:* the three filters are independent — *Set* lists the Set layer, *Venue* the
  Venue layer, *Moving only* drops what only shows and hides — with Set and Venue on by default,
  as the mock-up draws them; a held piece is listed whatever they say, so its × stays in reach. A
  focused **template** layer is read-only too (*A template carries no scenery*, stage-view D11). The
  Look arm writes on release, its whole list per gesture.
- **The chip (D17):** `ProgrammerActionBar` gains the Scenery chip. It opens the rail's tab when the
  rail is docked, and otherwise the same list through `EditorSurface`. Clear's confirmation counts
  held scenery.
  *Session 2 amendment:* the tab and the chip became the rail's Scenery band (§4's chip paragraph
  has why); the tab bullet above is that band's full list, opened by its *All scenery…*. And Clear
  has no confirmation dialog — it releases on a press, and its tooltip is
  what says what it will drop. That tooltip now counts held scenery (*Release … and 2 pieces of held
  scenery over 2s*, from `programmer.sceneryState`, since `programmer.cleared` counts values and
  effects only), and Clear is enabled for a programmer holding only scenery, which it was not.
- **The element form's stale `travelS`** (*Session 1 amendment:* found in session 1's review): the
  backend now refuses `travelS` on a piece that does not travel, and the Stage view's Edit form sends
  `params` whole, so `withKindParam` (`components/stage/elementDraft.ts`) must drop `travelS` when a
  drape stops being DRAW/FLY or an object stops flying — as it already drops a stale `states.open` /
  `states.trimM` — or an element given a `travelS` through `set_scene` cannot be switched to DEAD from
  the form. Session 4 adds the field itself.
- **Tests:**
    - the control's presets and ranges for each kind, and its `useLivePush` release;
    - `withKindParam` dropping `travelS` with the travel;
    - the tab's grouping, filters and scope arms;
    - the chip's two doors by width;
    - `SceneryEditor` keeping its draft over a refetch with the new control.
  *Session 2 amendment:* the tab's tests are the band's and the full list's
  (`RailSceneryBand.test.tsx`, `ProgrammerSceneryList.test.tsx`); the chip's are the rail's doors to
  the band — the collapsed strip's count and the phone handle (`ProgrammerRail.test.tsx`) — and
  Clear's tooltip (`ProgrammerActionBar.test.tsx`). A `SceneryEditor` row also unsets a key it
  states, so a key once set can track again without deleting the row.
- **Docs:** `frontend/CLAUDE.md` §"The rail's tabs", §"The editor kit" (a `SceneryControl` entry)
  and §"Scenery moves with the show".

### ~~Session 3 — Record, Include and Update (backend + frontend)~~ — done, `1e83f790`

- **Capture (D7):** `routes/programmerCapture.kt` takes the overlay; `programmerRecord.kt` and
  `lookRecord.kt` apply the four modes against `trackedSceneryAt`; the requests carry `scenery`.
- **Include and Update (D8):** both include routes load the source's own rows into the overlay with
  their `transitionMs`; UPDATE_EXISTING writes them back.
- **Looks (D10):** `LookDto.scenery`, and `LookSheet`'s Scenery read-out column (`Blinds` summary
  such as *Moon in · Sofa shown*).
- **Record sheet:** a *Record scenery too* checkbox row with its hint, which names the held elements.
  It is ticked by default when the overlay is non-empty and absent when it is empty.
  *Session 3 amendment:* the Record look sheet carries the same row (`RecordSceneryRow`), since its
  request carries `scenery` too and §9's scenery Look is recorded there; and Record is enabled for a
  programmer holding only scenery — the action bar's and the Looks page's *Record from programmer* —
  which neither was. `UpdateDialog`'s result line counts scenery changes, or a scenery-only Update
  read *0 values written*.
- *Session 3 amendment:* **Clear drops the include target itself** (`clearProgrammerCompletely`).
  `ProgrammerStore.clearAll` drops it only when a value is held, so a cue Included for its scenery
  alone kept its target on the desk while every client's `programmer.cleared` dropped its copy, and
  Including that cue again changed nothing the StateFlow would re-send — the source strip read *No
  source* with Update out of reach. Found running §9's check; it predates this session for a cue of
  layers alone, but scenery-only Includes made it the ordinary case.
- **MCP:** `record_cue`, `update_from_programmer` and `include_into_programmer` describe scenery;
  `record_cue` takes `scenery`.
- **Tests:**
    - CREATE skips a state equal to tracked;
    - MERGE replaces per element, and REMOVE deletes;
    - a Look takes every held state;
    - Include then Update round-trips a cue's rows, times included;
    - `scenery: false` writes nothing;
    - the sheet row's default.
- **Docs:** `docs/lighting-composition-model.md` §"Record" and §"Scenery — beside the layers";
  `docs/cue-stacks-engineering.md` §"Scenery".

### ~~Session 4 — the Stage view and the Positions plan (backend + frontend)~~ — done, `a68e3a73`

- **The read (D11):** `GET …/stage-elements/{eid}/scenery`.
- **Clicking a piece (D11):** `Stage3D`'s scene elements take a click with Edit off in every
  camera. Hit priority is fixture, then rigging, then element. A drag or a finger pan never counts as
  a click.
  *Session 4 amendment:* the scene's surfaces stay deaf to R3F, so the priority is the scene's own —
  fixtures and rigging take their R3F clicks first, and only a click R3F hands to `onPointerMissed`
  is cast against the drawn elements (`ScenePicker`, `stage3d/sceneryPick.ts`). The **nearest**
  surface decides, so a fixed wall in front of a piece hides it as it hides it from the eye, and a
  click that meets only the venue clears the selection as before. A click on a piece leaves the
  stage selection alone; a click on a fixture or a bar closes the popover.
- **The popover (D11, D17):** `stage3d/SceneryPopover.tsx` opens through `EditorSurface`. Its anchor
  is a virtual element fed by `stageLabels`' projection of the piece's centre. It holds:
    - `SceneryControl` writing `programmer.setScenery` at the window's programmer fade;
    - *Release*;
    - *Edit element…*, shown at tablet width and up, where Edit exists;
    - the *Moves with* read-out.

  *Session 4 amendment:* it writes through the rail band's own `useSceneryScope`, so the band and the
  popover cannot draw or write a piece two ways; the Stage view has no programmer scope, so it is
  always the Local arm. On a stage that is not the live project's it is read-only, with *Scenery
  moves on the live project only* — the programmer is the live show's. The anchor follows through a
  `StageAnchorTracker` on the label store and two new `EditorSurface` options, `followAnchor`
  (Radix's `updatePositionStrategy="always"`) and `keepOpenWithin` (a drag on the canvas orbits
  rather than dismisses), and `anchorRef` takes any box, not only an element. The anchor is the
  piece's projected **box**, not a point at its centre (`elementAnchorBox`; the tracker projects the
  box's corners too): a zero-size anchor at the centre put the popover over half the piece, found
  driving the §9 check at desk size. *Edit element…* turns
  Edit on with the piece selected, so its form opens in the editor panel.
- **Edit's form:** *Moves with* lists the read, each entry linking to its editor, and the form
  gains `travelS` beside the base states.
  *Session 4 amendment:* "linking" is opening in place: each entry opens its owner's own sheet over
  the Stage view (`stage/OwnerEditor.tsx` — Cue properties, the stack form, the Look sheet), since
  none of the three had a deep link and navigating away would lose the stage, the camera and the
  form. A cue entry names its stack where the read's cues span more than one, from the stack list
  the client already holds — the read's shape is the plan's. The read is invalidated by the cue,
  stack and Look lists and refetched on every mount, because a Look's own scenery write invalidates
  only that Look.
- **Blind on stage (D12):** `useResolvedChannelSource` draws `staged` for Output + Programmer and
  Programmer when present.
- **The Positions plan (D16):** `PositionsPlan` draws the live scenery through the same source. If
  the stage-vis doc records why the plan omits the scene, it draws drapes and Set-layer pieces only,
  and that sentence is amended.
  *Session 4 amendment:* the doc did record it (§"The scene, built by kind": the collapsed panel
  stays cheap), so the plan draws every drape and the Set layer (`Stage3D`'s `sceneSubset`,
  `plansScenery`), and the sentence now says so. It follows the window's Venue · Set · Haze layers
  as the Stage view does (Chris, 2026-10-07, asked during review), and its haze is never clipped at
  the proscenium, which it does not draw. A click on a piece there opens nothing: the popover is the
  Stage route's (`sceneryPopover`).
- **Tests:** the read; element picking order; a pan never opening the popover; the anchor projection
  for a moved camera; `staged` per source; the form's list and `travelS` field.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"Scenery that moves with the show" and §"The
  label layer" (the anchor); `frontend/CLAUDE.md` §Stage views.

### Session 5 — the cue table and the Prompt Book (frontend)

- **The cue table (D13):** a Scenery `SheetColumn` in `CueSheet.tsx`:
    - a read-out showing the cue's changes as *Tabs → closed · 4 s*, or the tracked hatch when it
      has none;
    - a `ReadOutButton` opening `SceneryEditor` (`withTime`) through `EditorSurface` `wide`;
    - blank on a MARKER, and inert under the lock, where a refused edit asks to unlock.

  `StackDetail` gains *Stack settings…*, shown where `ShowOverview` shows it.

  *Session 5 amendment:* where a cue changes nothing, the cell draws the hatch (*tracked*) only when
  something is tracked into it — the stack's set or an earlier cue's change — and the sheet's em-dash
  otherwise, since the sheet's rule is that an em-dash marks a cell that is empty but settable. A
  line on the cue's own fade names no clock (*Moon → in*, the mock-ups' copy), and names its element
  from the scene, leaving out one the scene has lost. The editor is one `CueSceneryEditor`
  (`components/scenery/`), shared with the Prompt Book's *Scenery…*, anchored at the pressed cell —
  one surface for the sheet, as the Stage popover is one for the stage — headed by what the cue
  tracks. *Stack settings…* mounts the stack form in place through session 4's `OwnerEditor`.
- **The Prompt Book (D14):**
    - `ScriptViewer`'s margin marker draws a `Blinds` glyph (`size-3`) when its cue has changes, and
      a tap opens that cue's card;
    - `PromptBookCueCard` lists the changes inline, keeping tracked state under Details;
    - `CueStackPanel`'s header shows the *On GO* line: the Next GO preview's scenery compared with
      live, empty when nothing moves;
    - the drawer toggle carries a `Blinds` dot when the line is non-empty;
    - an unlocked card's *Scenery…* opens the editor in place.

  If the book's cue list lacks `scenery`, the stack's cue DTO gains it. That is the session's only
  backend change.

  *Session 5 amendment:* it lacked it, so `CueStackCueEntry.scenery` is the cue's **own** rows
  (`@EncodeDefault(ALWAYS)`, one batched query per stack, `cueSceneryByCue`; no stored or synced shape
  changed). The DTO change brought a second, smaller one: a cue scenery write announced only the cue
  list, so every one — the `PUT`, the AI's `set_scenery` on a cue, a Record or an Update that wrote
  scenery — now fires `cueStackListChanged` too, and the client's mutations invalidate
  `CueStackList`, or another window's column and book would not have followed; and an element delete
  that sweeps scenery (REST, or `set_scene`'s `removeElements`) announces the cue, stack and Look
  lists, which it never did — a list naming the gone element would have had every whole-list `PUT`
  from its editor refused (found in review). *On GO* names moves
  as a DSM calls them — *close*, *draw*, *in*, *out*, *to 40%*, and *appears* / *hides* for a
  visibility flip — each with its time where the GO takes one, and compares each piece with its base
  filled in on both sides. Its hook (`useOnGoScenery`) also **refetches the shared Next GO preview**
  when the live pieces' targets or the cue on deck's own changes have held still for 250 ms: the
  preview is keyed only on which cue is on deck (the stage-vis doc's known limit), and a line naming
  moves could not lag an edit made in the book's own *Scenery…*. The glyph's tap expands the card,
  slides the drawer in on narrow and scrolls the card into view; the card lists the lines on its
  collapsed face as well as its open one, and says nothing on a cue with no scenery (the mock-up's
  *no scenery* would sit on most of a show's rows). The show-mode doc and `frontend/CLAUDE.md` had no
  Prompt Book section, so each gained one on scenery in the cue table and the book.
- **Tests:** the column's read-out, blank and locked arms; the On GO comparison (moving, unchanged,
  appearing, hiding); the glyph's presence and its tap; the dot in drawer mode.
- **Docs:** `frontend/docs/show-mode-engineering.md` (the column and the book);
  `frontend/CLAUDE.md` §Show and §Prompt Book.

## 6. Migration

None. A piece with no `travelS` moves as today. Nothing stored is rewritten.

## 7. Explicitly out of scope

- Scenery on templates, and a scenery selection (D9, D3).
- Drag handles on flown pieces and drawn tabs — `FU-SCENERY-STAGE-HANDLES`.
- A fly-stack department in the Prompt Book (FQ markers, its own colour) — `FU-SCENERY-FLY-DEPT`.
- A MIDI binding target that rides one element's state — `FU-SCENERY-MIDI-TARGET`.
- An element that follows a DMX tab track (stage-view D12's `drivenBy`) — `FU-SCENERY-DRIVEN-BY`.

## 8. Follow-ups to record

Each is added to `followups.md` by the session that makes it concrete, not before:

- `FU-SCENERY-MIDI-TARGET` and `FU-SCENERY-DRIVEN-BY`, both Trigger-gated, by session 1.
- `FU-SCENERY-STAGE-HANDLES` (Trigger: the popover feels indirect on the rig), by session 4.
- `FU-SCENERY-FLY-DEPT` (Trigger: fly cues are called separately from LX), by session 5.
- `FU-MANUAL-SCENERY-DEVICES` in `manual-validation.md`, by session 5: the whole round trip on an
  iPhone, an iPad and the desk.

`FU-AUTH-WS-PER-MESSAGE` is not fired. The two inbound programmer messages are operator gestures,
and nothing admin-only gains a WS command.

## 9. Verification

- **Session 1:** with a `move_scenery` call, the moon flies in at its `travelS` and `render_view`
  shows it; Blind holds the move; leaving Blind lands it; Clear flies it back out on the Clear fade.
- **Session 2:** on the desk the Scenery tab moves the house tabs; on a 1024 × 768 iPad the chip
  opens the same list as a popover; on an iPhone it opens as a bottom sheet with finger-sized rows.
  *Session 2 amendment:* the band, not a tab: on the desk the docked rail's band moves the house
  tabs; on a 1024 × 768 iPad the overlay rail carries the band and *All scenery…* opens the list as
  a popover; on an iPhone the handle's Scenery count opens the sheet at the band, and *All scenery…*
  opens as a bottom sheet with finger-sized rows.
- **Session 3:** fly the moon in, Record into Q15, and the cue card shows one row; Include Q15, move
  the tabs, Update, and Q15 shows two; Record look with only the moon held makes a pad that flies it.
- **Session 4:** a click on the moon opens the popover in Orbit and in Front; a pan does not; the
  popover follows the piece while orbiting; an iPhone shows a bottom sheet; in Blind, the Programmer
  source draws the staged moon and Output does not.
- **Session 5:** the cue table edits Q15's scenery on an iPad without opening a card; the book shows
  the glyph at Q15 and *On GO* names its moves; in drawer mode the dot shows.

## 10. Questions answered

Chris, 2026-10-07, each as the design document recommended, except the last:

| Question | Answer |
|---|---|
| Should templates hold scenery? | No; scenery Looks are the busk vehicle (D9). |
| Should moves that snap today glide? | Yes, at each element's optional `travelS`; unset still snaps (D6). |
| Which clock does a programmer move run on? | `travelS`, overridden by a non-zero programmer fade (D6). |
| What does Clear do to held scenery? | Drops it with everything else, on the Clear fade (D1). |
| Where does held scenery sit? | At the top, above every Look, cue and set (D2). |
| A fly department in the book? | Not yet; the glyph is enough (D14, `FU-SCENERY-FLY-DEPT`). |
| Stage handles? | The popover first; handles are a follow-up (D11, `FU-SCENERY-STAGE-HANDLES`). |
| Should Record write a state the cue already tracks? | No (D7). |
| Should the Stage view draw a blind change? | Yes (D12). |
| Which out-of-scope items come in? | The live MCP tool (D15) and the Positions plan (D16); MIDI and `drivenBy` stay out. |
| Approval of the design? | Approved, on condition of consistency with the design language and iPhone, iPad and desktop (D17, §4). |
