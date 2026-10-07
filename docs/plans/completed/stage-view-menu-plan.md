# The Stage view's View menu — a popover, work lights, and a frame-rate readout

> **Document status: DONE — sessions 1–2 shipped 2026-10-06, the last as `f80902ec`
> ([PR #75](https://github.com/cjcormack/lighting7/pull/75)).** Approved 2026-10-06: Chris approved
> the boards and answered §10's questions the same day: work lights are announced, `render_view`
> draws them off unless asked, level *a*, and the group is called *Work lights* (§10). The boards,
> the measurements and the record are in
> [`stage-view-menu-design/INDEX.md`](../stage-view-menu-design/INDEX.md).
>
> This document is the engineering half. Where it and the record disagree, this plan wins on
> behaviour and the boards on layout and copy.

## 1. Context

Chris's brief, 2026-10-06: redesign the Stage view's View menu, starting from boards. It is one
dropdown (`frontend/src/components/stage3d/StageViewMenu.tsx`) of 27 items, measured at 1,535 px on
the desk, of which a 1440 × 900 window shows 801; everything from Labels down scrolls. Two additions:

- **Realistic / Readable**, the stage-light plan's §10 question 1. Decided in the brief: Readable
  lifts the dark — the room's ambient and the materials' fill — while pools keep their exposure.
  Open: per window or per machine, and `render_view`.
- **A frame-rate readout**, fps and ms a frame while the demand canvas draws, *idle* otherwise.

The findings that shape the plan, all in the record:

- Readable tried on the live renderer at *Balcony · desk*: ambient 0.02 and a fill lift of 0.04 move
  the pools by 0–2 of 255 while unlit seats go 1 → 17. Brighter levels make the pale ceiling
  outshine the floor pool. On the Plan the housings disappear against the lit floor unless their own
  fill rises to 0.8. Black serge stays at 2–5 of 255.
- *Beam cones* off unmounts `StageEmitters`, which packs the light table the surfaces read, so it
  switches off every pool too. The label is wrong.
- The Show flags and Labels are per browser today (`localStorage stageViewFlags`); Venue, Set,
  Seating and Haze are per window but not announced; only the viewpoint and the source ride
  `windows.viewOptions`.

## 2. Decisions

| # | Decision |
|---|---|
| D1 | The View button opens a **popover** (`components/ui/popover.tsx`) with two tabs, **View** and **Performance**, replacing the dropdown. Groups are segmented controls (`ToggleGroup`) and toggles, each with a visible label; only the chosen value's hint is shown. Scope is written on every group (*this window* / *this machine*). |
| D2 | **View** is this window's: Source (2 × 2, the chosen hint and Next GO's status), Show (Fixtures, Light, Rigging, Regions; Venue, Set, Seating), Haze, Labels, **Work lights** (Off \| On). The stage-light plan called it *Realistic / Readable*; the group is named for what it imitates, and not *Look*, which on this desk is a stored record. |
| D3 | The Show flags and Labels move from `localStorage stageViewFlags` to **`sessionStorage stage.viewFlags`**, a window with nothing stored reading the old key once (the vis source's precedent). They are not announced. |
| D4 | *Beam cones* is renamed **Light**, hint *Beams and every pool*. The stored key stays `beamCones`. |
| D5 | **Performance** leads with a live block — fps and ms a frame, lights packed of lit, the haze tier — then Light budget, Gobos on surfaces and Box shadows (per machine, unchanged storage), then Test recovery. |
| D6 | **Work lights** are a two-row table — `off {ambient 0.003, lift 0, housing 0.225}`, `on {ambient 0.02, lift 0.04, housing 0.8}` (level *a*) — applied as uniforms: the lift adds to every surface material's directional fill, the housings' fill is swapped, and the billboard housings follow through `litByFill`. Exposure (`SURFACE_LIGHT_GAIN`), the roll-off, the haze, the lenses and the canvas background do not change. The levels are tuned so no unlit surface is brighter than a lit pool on *Balcony · desk*. |
| D7 | **The lift reflects off a floor albedo** (about 4 %, tuned in `?profileHarness=cyc`) so black serge shows its folds with work lights on. Lights keep the finish's own albedo: stage-light D6 stays true of the light. |
| D8 | **Work lights are per window**: `sessionStorage stage.workLights` (`off \| on`), default off, riding `viewOptions` as `workLights` beside `source`, applied by `applyStageViewOptions`, carried by *Copy link* (`?workLights=`), drawn as a *Work lights · Off \| On* segment on the Screens row. Every canvas in the window follows it — the Stage view and the Positions plan. |
| D9 | **`render_view` takes an optional `workLights`** (boolean, default false), so a capture means the room as lit; the tool's description says to turn them on to check geometry. It rides `stageRender.request` beside `source` and is echoed in the result. A capture never reads or writes the window's own. |
| D10 | **The readout** is per window (`sessionStorage stage.frameRate`, default off), not announced. A chip in the canvas's bottom-left; on a section in Edit it sits one row above the section HUD's cursor strip. fps counts frames drawn in the trailing second; ms is the median gap between frames less than a second apart; amber past 28 ms (the haze governor's step-down line); *idle* after a second with no frame. Clicking it opens the popover on Performance. It is hidden while the context is lost and never mounted by a capture. |
| D11 | The trigger names a source that is not Output: *View · Next GO*. |

The plan adds two of its own:

- **P1 — Two sessions, one PR each**, in §5's order, per CLAUDE.md §"Git workflow". Session 2 needs
  session 1's popover.
- **P2 — No schema, table or sync change.** The one wire addition is `workLights` on `render_view`
  and its outbound frame, landing with its client in session 2's PR.

## 3. The model

- **`useStageView`** moves to `createSyncStore` over `sessionStorageArea` (key `stage.viewFlags`),
  seeded from `localStorage stageViewFlags` when the window has nothing stored, parsed field by field
  over `DEFAULT_VIEW_FLAGS` as today (`toStageLabelMode` included). The old key is read, never
  written or deleted, so a build rolled back finds it as it was.
- **`scene/workLights.ts`** (new): `WORK_LIGHTS = ['off', 'on']`, `isWorkLights`, the table, the
  store (`stage.workLights`), `VIEW_OPTION_WORK_LIGHTS = 'workLights'`.
- **The surface uniforms** (`makeSurfaceUniforms`) gain `uLift` and `uLiftAlbedoFloor` beside
  `uAmbient`; the fragment shader's fill becomes
  `(uFill + uLift) × the fill's direction term`, reflected by `max(albedo, uLiftAlbedoFloor)` for the
  lift's share only. Switching writes three uniforms and the housing material's `uFill`, then
  `invalidate`. No define changes, so nothing recompiles.
- **`scene/frameRate.ts`** (new, pure): a ring of frame timestamps; `fps(now)`, `msPerFrame()`,
  `idle(now)`. A probe beside `HazeGovernorProbe` (`useFrame`, priority 2) feeds it; the chip
  writes its text to its own DOM node at most four times a second; a `setTimeout` flips it to
  *idle*. A **stats store** carries the frame rate, the light counts the emitters already stamp as
  `data-lights` and the haze tier the governor stamps as `data-haze-tier`; the attributes stay.

## 4. UX

The boards are the layout authority: `Menu.dc.html` (the popover), `WorkLights.dc.html` (work lights),
`Readout.dc.html` (the chip), `Model.dc.html` (scope and wire). In short:

- one View button, a 320 px popover, 502 px on View and 457 px on Performance — under a 768 px
  screen's fold with room to spare;
- every value visible without hovering, and the scope of each group written on it;
- work lights lift the house and the set out of the black and keep the rig's silhouette, with pools
  where they were;
- an fps chip in the corner while tuning a machine, *idle* when the stage is still.

No keys are bound. The popover takes the dropdown's place in the header; at narrow widths it
follows the header's existing wrap.

## 5. Implementation — two sessions

Each session is one PR. It ends with `npm run check` green (and `./gradlew test` where anything
outside `frontend/` changed), the engineering-doc paragraphs written, and its done-marker here: a
one-line row with the session's commit SHA, added on the branch before its PR merges.

### ~~Session 1 — the popover, Performance and the readout (frontend)~~ — done, `9e8cb5f7`

- **Menu (D1, D2, D4, D5, D11):** `StageViewMenu.tsx` rewritten on `Popover`, a two-value tab, a
  `ToggleGroup` per enum, toggles for Show. Same props, plus the readout's flag; the light budget,
  gobos and box shadows keep their stores. *Light* replaces *Beam cones* in copy only.
- **Show flags per window (D3):** `useStageView` on `createSyncStore` + `sessionStorageArea`,
  seeded once from the old key.
- **Readout (D10):** `scene/frameRate.ts`, the probe and the chip in `Stage3D`, the stats store; the
  chip moves up a row when `SectionHud` is shown. Not mounted under `capture`.
- **Tests:** the menu's groups show their values and call their setters (the dropdown has no test
  today); the flags store reads the legacy key once and never writes it;
  `frameRate.ts` — trailing count, median gap, the 1 s idle, a run after idle; the chip mounted in a
  demand canvas requests no frame; hidden under context loss; absent from a `StageRenderJob`.
- **Docs:** `frontend/docs/stage-vis-engineering.md` §"The 3D renderer" (a *Frame-rate readout*
  subsection; the light-budget and box-shadow bullets name the Performance tab), §"The label layer"
  (the flag is per window); `frontend/CLAUDE.md` §Stage views; `manual-validation.md`'s
  `FU-MANUAL-STAGE-LIGHT-BUDGET` reads the readout instead of the Web Inspector.

### ~~Session 2 — work lights, and `render_view`'s (frontend + backend)~~ — done, `f80902ec`

- **Work lights (D6, D8):** `scene/workLights.ts`; the uniforms; the housing fill and billboard
  colours in `bodies/StageBodies.tsx`; `litByFill` takes the work lights. `lib/stageViewpoint.ts` —
  `applyStageViewOptions`, `consumeLaunchStageOptions` and `stageViewOptions` carry `workLights`;
  `STAGE_WORK_LIGHTS_OPTION` on the Stage entry of `lib/windowViews.ts`; *Copy link*. The Positions
  plan reads the same store.
- **Black finishes (D7):** `uLiftAlbedoFloor` tuned in `?profileHarness=cyc` (black serge beside the
  white cyc) until the serge's folds read with work lights on; off unchanged.
- **Re-measure** *Balcony · desk* as the record did: with work lights on, pools within ±3 of off,
  the ceiling below the floor pool, the FOH housings visible on the Plan.
- **`render_view` (D9):** `ai/RenderViewTool.kt` takes `workLights` (boolean, optional;
  anything else a named problem), `state/StageRenderService.render` passes it, the
  `stageRender.request` frame carries it; `api/stageRenderApi.ts` parses it, reading a missing one as
  off; `StageRenderJob` hands it to `Stage3D`.
- **Tests:** the work-lights store and its `viewOptions` round trip, an out-of-vocabulary value ignored;
  `windowViews`' Stage options; `litByFill` with work lights off and on (beside its pins in
  `stageLook.test.ts`); the uniforms each writes; a capture with each; `RenderViewTool` accepts, defaults and refuses
  `workLights`; the request parse.
- **Docs:** stage-vis §"Light lands through one surface shader" (the colour bullet: work lights),
  §"Rendering for `render_view`"; `docs/mcp-engineering.md` §"`render_view`"; root `CLAUDE.md`'s
  `stageRender.request` and `windows.viewOptions` lines; `frontend/CLAUDE.md` §Stage views; the
  stage-light plan's §10 question 1 marked answered with a pointer here.

## 6. Migration

`stageViewFlags` in `localStorage` is read once per window as a seed and left in place. Nothing on
the desk changes.

## 7. Explicitly out of scope

- A continuous exposure control, or a work-light level per window.
- A per-window light budget, gobo or box-shadow setting.
- GPU timing (`EXT_disjoint_timer_query_webgl2`): Safari has none, and per-pixel cost stays the
  occlusion bench's job.
- ⌘K commands and MIDI bindings for work lights or the readout.

## 8. Follow-ups

None to record. `FU-AUTH-WS-PER-MESSAGE` is not fired: the socket gains no inbound message, only a
field on the outbound `stageRender.request`.

## 9. Verification

- **Session 1:** the popover opens under the fold on a 1440 × 900 window and a 1024 × 768 iPad;
  every value and scope is visible without scrolling; a second window keeps its own Show flags;
  the chip reads *idle* on a still stage and a rate while orbiting, and a still stage stays still
  with it on.
- **Session 2:** work lights lift the house, the set and the rig on *Balcony · desk* and the Plan with
  the pools where they were; black serge shows its folds; a Screens row sets another window's work lights;
  `render_view` with `workLights: true` returns a legible frame of an unlit scene.

## 10. Questions answered

Chris, 2026-10-06:

1. **Work lights on the wire (D8):** announced, with the Screens row segment.
2. **`render_view`'s default (D9):** off — a capture means the room as lit.
3. **The level (D6):** level *a*.
4. **The name (D2):** *Work lights · Off | On*, over *Rendering* and *Room*.
