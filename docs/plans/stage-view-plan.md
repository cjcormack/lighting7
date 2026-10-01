# The stage view — a modelled venue, viewpoints, lanterns, scenery and one-shot effects

> **Document status: IN PROGRESS — session 7 ([PR #35](https://github.com/cjcormack/lighting7/pull/35)).** Approved 2026-09-29. The design is checked in beside this plan at
> [`stage-view-design/`](stage-view-design/INDEX.md). It contains:
> - the design record;
> - a working three.js prototype of project 15;
> - an art book of 30 plates;
> - eight artboards drawing the proposal into the desk as it is today.
>
> Live copies (private to Chris; the checked-in files are the authority):
> - boards: <https://claude.ai/artifact/UfcvSEzjFDEe8vs1JgpAys>
> - art book: <https://claude.ai/artifact/1QJzfob6sy9fGigbdrm2cw>
> - prototype: <https://claude.ai/artifact/MYfoTJJheASwdNemFeedF7>
>
> This document is the engineering half. Where it and the boards disagree, this plan wins on
> behaviour and the boards on layout and copy. **Chris confirmed the sixteen design decisions as
> recommended on 2026-09-29**; three questions remain open (§11).

## 1. Context

Chris's brief, 2026-09-29, arrived in rounds:
- the 3D view is clunky and should model the real hall, with Claude doing the modelling over MCP;
- the stage should be visible from anywhere, including a seat;
- the 2D plan does not work at 45 fixtures;
- fixture bodies should be per type, with more options for generic dimmers and no glowing balls;
- beams should start where the lamp is and read as washes where they are;
- shutters, focus and zoom;
- props, scenery and tabs that move with Looks, cues and stacks;
- the balcony units stand rather than hang;
- the Twin Shot is a confetti cannon worth firing.

The record (`INDEX.md`) holds the findings, the research and the reasoning; this plan does not
repeat them. The findings that shape the order of work:

- **The Stage view is memory-heavy and cannot recover.** The composer's 8× MSAA half-float targets,
  instance buffers sized for the worst case, a React root per label, and no `webglcontextlost`
  handling. Safari's "reloaded because it was using significant memory" is the symptom.
- **Two bugs need no design.** Regions are drawn upward from `centerZ`, which every other reader
  treats as the top surface. Every lens glows at 50% at dimmer zero.
- **The backend has no venue vocabulary.** It knows a stage box, regions, riggings and placements.
- **Cues do not track, and every composed value is a DMX channel write.** So scenery cannot ride
  the composition pipeline and gets its own record (D12).
- **The Twin Shot's fire channels are plain `OTHER` sliders.** A Look, a cue or a Beam-masked
  Record can hold a raised one today.

## 2. Decisions taken

The design decisions, D1–D16, are in the record's §"Decisions" with their reasons. Chris confirmed
all sixteen as recommended on 2026-09-29. Each is one line:

| # | Decision |
|---|---|
| D1 | The SVG 2D renderer goes once ortho editing in 3D has parity; looking moves to ortho cameras at once. |
| D2 | One `stage_elements` table with per-kind `params`, validated at the write boundary. |
| D3 | Venue and set are layers within a project; no shared Venue entity yet. |
| D4 | Claude gets `render_view`, rendered by a signed-in desk window. |
| D5 | Regions stay separate from elements; a platform may link to one. |
| D6 | Viewpoints are portable. |
| D7 | No GLB or MVR import yet. |
| D8 | The lantern library ships with the desk as a resource; per-project custom lanterns come later. |
| D9 | Each extra placement chooses its own lantern. |
| D10 | Parametric archetypes now; a GDTF-shaped `body` slot for a GLB later; no GDTF-Share meshes. |
| D11 | Templates carry no scenery. |
| D12 | Scenery is its own tracked record, not virtual DMX fixtures. A real DMX tab track is a fixture an element follows. |
| D13 | Record does not capture scenery. |
| D14 | Focus data (shutters, gate, iris, focus, zoom) lives on the placement, portable, never in a look. |
| D15 | One-shot triggers are their own property kind, outside composition, Record and FX. |
| D16 | Firing needs a desk-wide arm that lapses and is shown everywhere. An unarmed cue event is skipped and announced, never queued. MCP is refused by default. |

The plan adds five decisions of its own:

- **P1 — Ten sessions, one PR each**, in the order of §5, per CLAUDE.md §"Git workflow". Sessions 0
  and 6 are frontend-only and can start at once. Everything else follows the dependency notes.
- **P2 — Arm, fire and reload are REST, not WS commands.** The socket only *reports* the armed
  state and fires. This keeps `FU-AUTH-WS-PER-MESSAGE` unfired: no new operation reaches the
  socket. The remote listener refuses arm and fire through a `requireEffectsAccess` twin of
  `requireScriptAccess` (`mcp/RemoteRequests.kt:49`), off unless an admin allows it.
- **P3 — One `formatVersion` per session that adds portable data** (18, 19, 20, 21), each by
  `docs/sync-engineering.md` §"Format versioning"'s sharp-edge rule: an older reader would drop
  the new records and push the loss back. There is one install today, so a bump is cheap. Batching
  would couple unrelated sessions.
- **P4 — The prototype is a reference, not code to port.** It proves the maths: apertures, the
  shared beam mask, standing mounts, tracked scenery, the confetti model. The implementation lives
  in the existing R3F structure (`stage3d/`), and keeps the desk's beam optics, gobos, prisms and
  vis sources.
- **P5 — Project 15's data fixes are Chris's to make on the desk,** not a migration:
  - FOH Balcony becomes a `LEDGE` at about 1.9 m, y ≈ −16.2 m;
  - the two S4 Revs get `basePitchDeg 0`;
  - lantern types are set once the library exists;
  - check that no Look, cue or template stores `output1` or `output2` on either Twin Shot before
    session 9 strips them.

## 3. The model

### 3.1 Portable tables and fields

| Session | Table / field | Columns | Notes |
|---|---|---|---|
| 2 | **`stage_elements`** | `uuid`, `project_id`, `name` (unique per project), `kind` (`ROOM`, `PROSCENIUM`, `FLAT`, `DRAPE`, `PLATFORM`, `SEATING`, `OBJECT`), `layer` (`VENUE`, `SET`), `position_x/y/z`, `yaw_deg`, `width_m`, `depth_m`, `height_m`, `finish_colour`, `finish_pattern`, `emissive`, `params` (text, JSON), `hidden`, `sort_order` | Z is the element's base; for `PLATFORM` it is the top surface, as for a region. `params` is a sealed `ElementParams` per kind, serialised canonically. It holds the kind's `states` and their base values: `open` for tabs, `trimM` for flown pieces, `visible` for all. |
| 2 | **`stage_viewpoints`** | `uuid`, `project_id`, `name`, `kind` (`ORBIT`, `EYE`, `SEAT`), `eye_x/y/z`, `target_x/y/z`, `fov_deg`, `seat_element_uuid`, `seat_id` (e.g. `F6`), `sort_order` | Plan, Front and Side are built in, not rows. |
| 7 | `fixture_patches` + `fixture_patch_placements` | `lantern_type`, `zoom_deg`, `lamp_rotation_deg`, `shutters` (text, JSON: four `{depth, angleDeg}`), `gate_rotation_deg`, `iris`, `focus_softness` | On both, so each lantern is focused separately (D9, D14). `kind_override` becomes derived from the lantern; `beam_angle_deg` stays as an override. |
| 8 | **`cue_scenery`**, **`cue_stack_scenery`**, **`look_scenery`** | `uuid`, the owner FK, `element_id`, `state` (text, JSON), `transition` (`duration(…)`, cue only), `sort_order` | By element uuid on the wire. An element delete sweeps them. |
| 9 | **`cue_events`** | `uuid`, `cue_id`, `patch_id`, `trigger` (the trigger's property name), `offset` (`duration(…)`), `sort_order` | Fires on GO into the cue only. |

Every duration is a `duration("…")` column, never an `_ms` name (`TimeColumnsTest`). Every new
table gets:
- an `ALL_TABLES` entry;
- a `SyncCoverageTest` disposition;
- a sync DTO and exporter/importer wiring;
- a `RichProjectFixture` row with non-default values;
- a round-trip test case.

Cloning is derived and needs nothing extra.

### 3.2 Machine-local and runtime

- **`effect_tube_state`** (session 9) is machine-local: `patch_uuid`, `trigger`, `spent_at`
  (`utcInstant`). Its disposition is `MachineLocal`. Loaded and spent are the physical rig, not
  show content.
- **Armed until** is runtime (`StateFlow`, not persisted). A restart disarms.
- **Per-window:** the current viewpoint and source ride `windows.viewOptions` under `stage`, and the
  orbit pose sits in `sessionStorage`. Neither is new on the backend: `viewOptions` is already
  opaque to the registry. The viewpoint landed in sessions 1–2; the source goes on it in session 3,
  with the View menu's other per-window facts.

### 3.3 The wire

- **REST**, under `/api/rest/projects/{id}` and following `docs/api-conventions.md` (kebab-case,
  plural collections, `?force=true`):
  - `stage-elements` and `stage-viewpoints`: CRUD (session 2);
  - `cues/{id}/scenery`, `cue-stacks/{id}/scenery`, `looks/{id}/scenery`: whole-list `PUT`
    (session 8);
  - `cues/{id}/events`: whole-list `PUT` (session 9);
  - `effects/arm` `{on, seconds?}`, `patches/{id}/fire` `{trigger}`, `patches/{id}/reload`
    (session 9, P2).
- **WS:**
  - `stageElementListChanged` and `stageViewpointListChanged` (session 2);
  - `scenery.state`, per element `{state, from, startedAt, durationMs}`, `StateFlow`-backed so the
    subscription is the snapshot (session 8);
  - `effects.armed` and `effects.fired` (session 9).

  Each goes into `docs/websocket-engineering.md`.
- **`POST cue-stacks/{id}/preview`** answers the incoming scenery beside `channels` (session 8).
- **MCP:**
  - `set_scene` (upsert by name, all-or-nothing, plus `template: "proscenium-hall"`) and
    `get_scene` (session 2);
  - `render_view` (session 4);
  - `place_fixtures` and `patch_fixtures` gain the lantern and focus fields (session 7);
  - the cue and Look authoring tools gain `scenery` (session 8) and `events` (session 9);
  - `describe_rig` gains a stage summary (session 2).

  Each goes into `docs/mcp-engineering.md` §"Show-setup tools".

## 4. UX — what the design draws

`stage-view-design/` holds:
- **Stage:** the viewpoint picker, cameras, and the Focus tab;
- **Positions:** replacing `StageOverviewPanel`;
- **Edit:** `+ Scenery` and the element form;
- **Patch:** the Lantern box, per-placement lanterns, and mounts;
- **Cue:** Scenery and Events, and ARMED on the header;
- **StacksLooks:** a stack's set, a Look's scenery, and the cannon's panel;
- **Screens:** a hall screen on a viewpoint;
- **Model.**

The prototype's Notes tab lists what it does and leaves out.

## 5. Implementation — ten sessions

Each session is one PR. It ends with `./gradlew test` and `npm run check` green, the CLAUDE.md or
engineering-doc paragraphs written, and its done-marker here: a one-line row with the merge SHA.

### ~~Session 0 — the renderer, fixed first (frontend)~~ — done, `af5beca` ([PR #27](https://github.com/cjcormack/lighting7/pull/27))

- **Composer and pixel ratio.** `Bloom.tsx`: `multisampling={0}`, or drop the composer and keep
  bloom only for lit lenses. `Stage3D.tsx`: DPR capped at 1.5, `antialias` kept.
- **Context loss.** Handle `webglcontextlost` / `webglcontextrestored` with a *3D paused · Restore*
  state; no blank canvas.
- **Instance buffers.**
  - `StageEmitters.tsx` / `emitterLayout.ts`: size the region-cookie and wash instances by what the
    rig has (prism fixtures × lobes, strip pixels × strips), not fixtures × 16 × regions.
  - Push region geometry through uniforms so a region drag stops rebuilding every buffer.
- **Labels.** One DOM label layer, decluttered in screen space, replaces the per-object drei
  `<Html>` in `StageLabel.tsx`. It offers Positions / All / None.
- **Regions.** `StageRegionMeshes.tsx` draws the box *down* from `centerZ`, the top surface.
  `StageAimControls.regionAimPoint` is already right.
- **Lenses.** Dark glass, emissive only with level, in every body and `PixelStrip.tsx`. Housings go
  matt black.
- **Fonts.** Bundle drei `Text`'s font rather than loading it from jsdelivr.
- **`frameloop`** stays `always` while DMX moves, and demands a render when idle.
- **Docs.** `frontend/docs/stage-vis-engineering.md`.

### ~~Session 1 — viewpoints, cameras, Positions (frontend)~~ — done, `687f67e` ([PR #28](https://github.com/cjcormack/lighting7/pull/28))

- **Cameras.** Orbit, Eye (look-around from a point) and orthographic Plan / Front / Side on the
  one scene, each section cutting what lies between it and the camera. The 3D / Plan / Front /
  Side toggle in `routes/Stage.tsx` switches cameras. `Stage2DView` stays behind Edit (D1).
- **The viewpoint picker.**
  - Built-ins: Orbit, Plan, Front, Side, and *Frame the selection* (F).
  - The current viewpoint rides `windows.viewOptions` (`lib/windowViews.ts` gains the `stage`
    descriptor), and the Screens row draws it.
  - Saved views and seats come in session 2.
- **Positions.**
  - `PositionsPanel` replaces `StageOverviewPanel` and `StageMarker` behind the header toggle
    (`overviewPanels.tsx`, `Layout.tsx:272`).
  - Rows by rigging, upstage first, with the stage edge marked. Chips set the desk selection.
    Group chips dim non-members.
  - A Plan tab embeds the ortho camera.
  - Derived, never stored (`FU-BUSK-RIG-PLOT`).
- **Docs.** CLAUDE.md §Windows (the `stage` view option) and the frontend CLAUDE.md.

### ~~Session 2 — the scene document (backend + frontend client)~~ — done, `a05472a` ([PR #29](https://github.com/cjcormack/lighting7/pull/29))

- **Backend.**
  - `stage_elements` and `stage_viewpoints` (§3.1): models, routes and `formatVersion` 18.
  - `SetupTools.kt` / `SetupToolSchemas.kt`: `set_scene`, the `proscenium-hall` template (hall
    size, stage size, pros opening and sill, rows, seat pitch, balcony), and `get_scene`.
  - `RigBriefing.kt`: the stage summary. The `RIGGING_KINDS` list gains `LEDGE`.
- **Frontend.**
  - `api/` clients and `store/`.
  - The viewpoint picker's saved views, seats and *Save this view…*.
  - Scene elements are read but not yet drawn beyond boxes (session 3 draws them).
- **Docs.** `docs/sync-engineering.md` (v18), `docs/fixtures-engineering.md` §stage,
  `docs/mcp-engineering.md`, `docs/api-conventions.md`.
- **Gate.** `FU-AUTH-ATTRIBUTION` fires on this bump; §11 Q2 passed it (v18 has no attribution
  columns).

### ~~Session 3 — the room, lit (frontend)~~ — done, `fc47f67` ([PR #30](https://github.com/cjcormack/lighting7/pull/30))

- **Element builders** in `stage3d/scene/`: room (inward-facing faces), proscenium, flat with
  openings, drape and tabs, platform with rail, seating (instanced, emitting the seat list),
  object, flown piece.
- **The surface shader.** A light-array receiver shader lights every venue and set surface. It uses
  a data texture of lights rather than uniform arrays (the prototype's 48-light ceiling), and axial
  beam reach stands in for occlusion.
- **Retirement.** The region, wall and floor cookie instances go.
- **Seats.** *Sit in a seat…* picks from the seating mesh.
- **View menu.** Venue, Set, Seating and Haze.
- **Source per window.** The View menu's Source rides `windows.viewOptions` beside `viewpoint`, and
  a Stage row on the Screens sheet gains its Source segment (`Screens.dc.html` §1).
- **Haze** follows the hazer's DMX instead of `washConfig.ts`'s constant.
- **Docs.** `frontend/docs/stage-vis-engineering.md`.

### ~~Session 4 — `render_view` (backend + frontend)~~ — done, `f9ee279` ([PR #31](https://github.com/cjcormack/lighting7/pull/31))

- **The request.** The tool asks a signed-in window, through the window registry, to render a
  viewpoint offscreen at a fixed size and upload the frame. The answer is a PNG, or a named error
  when no window is open.
- **The renderer.** It reuses session 1's cameras and session 3's scene, so the frame is exactly
  what the Stage view shows.
- **Docs.** `docs/mcp-engineering.md`; `docs/desk-screens.md` (a window can be asked to render).

### ~~Session 5 — edit in 3D, then retire the SVG plan (frontend)~~ — done, `898c40e` ([PR #32](https://github.com/cjcormack/lighting7/pull/32))

- **Parity in the ortho cameras:** marquee, snap-to-rigging, alignment guides, the unplaced tray,
  nudging, duplicate, and the region and rigging handles.
- **The Edit header** gains `+ Scenery` and the element form in `StageEditorPanel`
  (`EditSceneElementForm`, with the read-only *Moves with* list).
- **Deletions.** `components/stage2d/` goes (≈3,000 lines), and so does `StageBackdrop` if nothing
  else uses it.
- **Docs.** `frontend/CLAUDE.md`, `stage-vis-engineering.md`.

### ~~Session 6 — fixture bodies (frontend, one backend constant)~~ — done, `13f14c5` ([PR #34](https://github.com/cjcormack/lighting7/pull/34))

- **Archetypes.** Parametric bodies (profile, box profile, fresnel/PC, PAR, flood, downlight, mover
  heads, batten, blinder, effect, cannon) replace `fixtureBodies/*`. They are instanced per
  archetype part, with a simplified mesh and a billboard at distance.
- **Beams leave the aperture** (record item 8): frusta from the lens or cell face, apex at
  aperture ÷ tan(half-field). This replaces the lens-point origin at `FixtureModel.tsx:759`. Edge
  softness comes from the family.
- **Cells replace the `STRIP` gate** (`FixtureModel.tsx:220`). Every element with a colour gets its
  own lens, beam and light; heads without elements share the fixture's colour.
- **The shared beam mask**, for pool and haze. The haze is raymarched for round beams, as the gobo
  volume already is.
- **Mounts.** A rigging of kind `LEDGE` or `FLOOR_STAND` stands its fixtures: base down, no hanger.
- **DMX iris** is drawn, and frost maps onto edge softness.
- **Docs.** `docs/fixtures-engineering.md` §"Aiming a head at a point" (standing mounts) and
  `stage-vis-engineering.md`.

### Session 7 — the lantern library and focus data (backend + frontend)

- **The library.** A resource of about 25 lanterns (§"The lantern library" in the record, seeded
  from datasheets), exposed as `GET /lanterns`. `@FixtureType` gains a `body` descriptor, which
  defaults from the kind.
- **Fields.** The patch and placement fields (§3.1), on `formatVersion` 19. `METADATA_ONLY_PUT_KEYS`
  and the bulk placement route learn them.
- **The Lantern box** in `EditPatchForm.tsx` replaces *Beam & Gel* and *3D shape*.
  `ExtraPlacementsFields.tsx` gains a lantern and Focus per entry. The patch list gains Mount and
  Lantern columns.
- **The Focus tab** on `StageFixtureControlPanel`, and the focus card. It shows the lantern, the
  gate, depth and angle per blade, rotation, iris, sharp ↔ soft, zoom in range, and a switch
  between the lanterns of a pair.
- **Oval PAR beams** use two angles and `lamp_rotation_deg`, in the beam and in the shader.
- **MCP.** `place_fixtures` and `patch_fixtures` gain the fields.
- **Docs.** `docs/fixtures-engineering.md`, `docs/sync-engineering.md` (v19).

### Session 8 — scenery on cues, stacks and Looks (backend + frontend)

- **Tables and routes** (§3.1, §3.3), on `formatVersion` 20.
- **The resolver.** `SceneryResolver` tracks each element through, in precedence order:
  1. live Looks (programmer and busk layers);
  2. the live stack's cues, from the top of the list down to the active cue;
  3. the stack's set;
  4. the element's base.

  With two stacks live, the most recently GO'd wins per element.
- **The hook.** `CueStackManager.activateCueInStack` and `deactivateStack` recompute and publish
  `scenery.state`. The AI's `apply_cue` path (`projectCuesHelpers.kt:651`) is routed through the
  same hook.
- **Preview.** It answers scenery (§3.3), and the Next GO vis source animates it.
- **Frontend.**
  - *Scenery* on the cue card (`CueDetailContent.tsx`) and in Cue properties (`CuePropsPane.tsx`).
  - *Set for this stack* in `CueStackForm.tsx`.
  - *Scenery while live* in `LookDetailSheet.tsx`.
  - Scenery animation in the Stage view.
  - Beam reach reads closed tabs.
- **MCP.** The cue and Look tools gain `scenery`.
- **Docs.** `docs/lighting-composition-model.md` (scenery sits beside the layers, not in them),
  `docs/cue-stacks-engineering.md`, `docs/sync-engineering.md` (v20).

### Session 9 — the party trick (backend + frontend)

- **`TriggerProperty`.** A new property kind with an `arm` channel. `EquinoxTwinShotMkIIFixture`'s
  outputs become triggers and its master becomes their arm.
  - Triggers are refused by Look rows, template rows, cue rows, the programmer, Record/Update,
    effects and busk pads.
  - A reload seeds them idle.
  - A one-off pass strips stored rows that name them and logs the count.
- **Firing.**
  - A backend-timed pulse (≈300 ms high, then idle), written above composition like a park.
  - `cue_events` on `formatVersion` 21. They fire on GO only, are skipped with a message when
    unarmed, and are never previewed or tracked.
  - Arm, fire and reload over REST (P2).
  - `effect_tube_state`.
  - `effects.armed` and `effects.fired`.
  - A MIDI `FireTrigger` binding that requires the arm.
- **Frontend.**
  - ARMED on the app header of every window.
  - The cannon's panel: loaded and spent, disarm, hold to fire, reload.
  - Events on the cue card and in Cue properties.
  - Confetti in the Stage view on `effects.fired`.
  - In Blind or a Programmer vis source, a fire is rehearsed: confetti, no DMX.
- **Docs.** `docs/fixtures-engineering.md` (triggers), `docs/cues-engineering.md` (events),
  `docs/desk-accounts.md` (arming is for both roles on the local listener, and off remotely by
  default), `docs/midi-control-surface-engineering.md`.

**Dependencies:**
- 0 → everything.
- 1 before 2.
- 2 → 3 → 4, and 3 → 5.
- 6 → 7.
- 2 and 3 → 8.
- 6 → 9.

Sessions 6 and 7 can run beside 2–5. Session 9 needs only 6 and the cue editor, so it can go
before 8.

## 6. Migration

- **No DB migration.** Every change is additive, and `SchemaUtils.createMissingTablesAndColumns`
  covers it.
- **Session 9's strip of stored trigger rows** is a one-off pass at startup, logged. It follows the
  `InstallBootstrap.kt` precedent for where such a pass plugs in, and is deleted once it has run on
  the one install.
- **The `formatVersion` bumps** (P3): 18, 19, 20 and 21, each with its `docs/sync-engineering.md`
  entry.
- **Project 15's data** is Chris's to fix (P5).

## 7. Explicitly out of scope

- GLB, GDTF and MVR import (D7, D10).
- A shared Venue entity across projects (D3).
- Per-project custom lanterns (D8).
- Photogrammetry or splat backdrops.
- Shadowed spotlights and gobo projection on surfaces, beyond the axial reach (the record's quality
  tier).
- Dragging shutter blades on the pool (Capture's Focus mode).
- Heads that tilt independently (the Slender Beam Bar Quad).
- Any DMX-driven tab track; it stays a fixture an element can follow (D12).

## 8. Follow-ups to record

- `FU-STAGE-QUALITY-TIER` — shadow maps for the selected heads, and gobos projected by the surface
  shader.
- `FU-STAGE-GLB-IMPORT` — a `mesh` element kind and the `body` slot's GLB, with blob storage and a
  sync story (D7, D10).
- `FU-STAGE-VENUE-SHARED` — a Venue shared by projects, once a second show plays the Commemoration
  Hall (D3).
- `FU-STAGE-FOCUS-ON-POOL` — drag blades on the pool itself.
- `FU-STAGE-INDEPENDENT-HEADS` — a tilt node per head for multi-head movers.
- `FU-LANTERNS-CUSTOM` — per-project lanterns (D8).
- `FU-STAGE-HAZE-FOLLOWS-HAZER` — the haze level follows the hazer's DMX, once a rig can say which
  fixture is its hazer (session 3 found no typed identity: the Commemoration Hall's is a generic
  dimmer).
- The stale docs noted in the record's §"Noticed on the way" are fixed in the sessions that touch
  them; they are not follow-ups.

## 9. Verification

Beyond the unit suites, at the desk:

- **Session 0.** Safari at full-window Retina for ten minutes with a 45-fixture look and a region
  drag, and no memory reload. Force a context loss (the page's *Test recovery*, then background the
  tab on an iPad): the view pauses and restores. "Main stage" sits flush with the deck. No lens
  glows at zero.
- **Session 1.** Positions at 45 fixtures on a phone and at 1440 px. A chip selects on every
  window. A second window on *Row F centre* keeps it across a desk reload.
- **Sessions 2–4.** Claude, given the video frame, builds the hall with `set_scene` and a template,
  calls `render_view` from the desk angle, and corrects it. The result round-trips through sync
  and a clone.
- **Session 5.** Every 2D edit gesture works in Plan. The SVG files are gone. `npm run check` is
  green.
- **Sessions 6–7.**
  - A Source Four 19°'s cone passes the lens at the lens's width.
  - The blinder throws two soft columns, and the Liteobar three rectangles, in the colours of its
    elements.
  - A shuttered profile cuts a straight edge on the set.
  - The balcony Revs stand and aim right after P5's data fix.
- **Session 8.**
  - GO through Act 1: the tabs close at Q1 and draw over 4 s at Q2.
  - GO TO Q4 lands the tracked set.
  - Next GO previews Q2's tabs.
  - A busk pad for the Night Look flies the moon in.
- **Session 9.**
  - An unarmed Q5 announces the skip and fires nothing: check the DMX monitor.
  - Armed, both cannons fire once each at their offsets, and a reload clears spent.
  - A Look or Record cannot hold an output.
  - A remote socket cannot arm.
  - Rehearse in Blind: confetti on every window, and zero DMX on the cannon's channels.

## 10. Scope honesty

- **The prototype hides costs.** It runs 52 placements at 60 fps on a desktop GPU with a 48-light
  uniform array and 20-step raymarched haze. Session 3's data texture and session 6's raymarch need
  measuring in Safari on the operator's Mac and an iPad before their sizes are fixed. The rule —
  the surface shader budgets lights, haze degrades before frame rate — is the decision; the numbers
  are not.
- **`render_view` depends on a window being open.** A headless desk cannot answer it. That is by
  design (D4) but limits unattended use.
- **The lantern data is ours to collect.** About 25 datasheets, some for discontinued Strand and CCT
  units (Theatrecrafts' archive). Session 7's library is only as good as that pass.
- **The venue in the prototype is estimated** from a five-second video. The house tabs, the moon
  and the Q1–Q5 list are illustrations, not project 15's show.
- **Session 5's parity list is the SVG view's features as they are today.** Anything added there
  before session 5 joins the list.

## 11. Open questions

1. ~~**Confirm D1–D16.**~~ Confirmed as recommended, Chris, 2026-09-29.
2. ~~**`FU-AUTH-ATTRIBUTION`** fires on session 2's `formatVersion` bump.~~ Passed, Chris,
   2026-09-29: v18 went without attribution columns. It needs its own design for machine-local
   users, and bumps 19–21 remain to fold it into.
3. **Where do the balcony units stand?** The prototype assumes the front ledge at about 1.9 m. If
   they are on stands behind the rail, P5's heights change.
4. **Which lanterns does the Commemoration Hall own?** The prototype's defaults (Source Four 19°
   fronts, Cantata F fresnels, Par 64 CP62 cans) are guesses. The real list decides session 7's
   first seed.
5. **Does the hall have house tabs?** The video shows none. The prototype's are an illustration of
   the element kind.
