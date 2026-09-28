# 3D Stage View Upgrade — Multi-Session Plan

## Context

The existing stage view in `lighting-react` is a 2D top-down HTML/CSS layout
(`src/components/stage/StageMarker.tsx`, `src/components/StageOverviewPanel.tsx`)
with fixture positions stored as 0–100% of viewport on `FixturePatch.stageX/Y`.
The backend (`lighting7`) has already shipped the full 3D schema —
`FixturePatchDto` carries `stageX/Y/Z` (metres, Z-up, FOH-relative),
`baseYawDeg/basePitchDeg`, `riggingUuid`, pre-composed `worldPositionX/Y/Z`,
and there are CRUD endpoints for `Rigging` and `StageRegion`. None of those new
fields are surfaced on the frontend.

Goal: upgrade the React app to a 3D stage visualiser per
`/Users/chris/Development/Personal/lighting7/docs/research/stage-vis-discovery.md`,
delivered in independent sessions so each lands cleanly on its own. The
visualiser must:

- render stage geometry, rigging, and fixtures from existing REST data
- track live beam direction and colour from DMX (read via existing channel hooks)
- support visual editing of all three on tablet+ viewports, view-only on phones
- coexist with (toggle to) the current 2D view

## Decisions (confirmed)

- **Coordinates**: reset & start clean. Existing `stageX/stageY` percentages
  are dropped; fixtures are re-placed in the new 3D editor in metres.
- **Live DMX path**: reuse existing per-channel hooks
  (`useSliderValue`, `useColourValue` from `src/hooks/usePropertyValues.ts`).
  Each `<FixtureModel>` reads channels inside `useFrame` and applies pan/tilt
  + colour imperatively. No backend WebSocket changes needed.
- **2D view**: keep as fallback with a user-facing 2D ↔ 3D toggle on the
  stage panel.
- **Edit gate**: view on all viewports; editing enabled at `SM_BREAKPOINT`
  (≥640px). Use existing `useMediaQuery` from `src/hooks/useMediaQuery.ts`.

## Status overview

| Session | Title                              | Status      | Completed | Notes |
|---------|------------------------------------|-------------|-----------|-------|
| 1       | Foundation                         | Done        | 2026-05-03 | Cross-repo: backend now broadcasts riggingListChanged/stageRegionListChanged. Data reset dropped (no meaningful legacy data). |
| 2       | Stage Configuration                | Done        | 2026-05-03 | New "Stage" tab in Project Settings hosts stage dimensions + regions CRUD. |
| 3       | Rigging Configuration              | Done        | 2026-05-03 | Riggings CRUD on new "Rigging" tab; flown-truss defaults on create. |
| 4       | Patching: Rigging-Mounted vs Free  | Done        | 2026-05-03 | Structured rigging assignment + metric stage coords on the patch sheet; legacy 2D map and free-text rigging label retired. |
| 5       | Read-Only 3D Stage View            | Done        | 2026-05-03 | New /stage route with 2D ↔ 3D toggle. Backend pan/tilt metadata (axis + degree mapping) populated on every mover; 2D legacy panel re-fitted to metric coords. |
| 6       | 3D Editor Mode                     | Done        | 2026-05-04 | drei `<TransformControls>` + click-to-edit + create affordances. Rig-axis constraint via post-projection. Drag→form sync via imperative refs; save-on-release replaced the planned 300ms debounce. |
| 7       | Polish (optional)                  | Done        | 2026-05-04 | Rigging `lengthM` end-to-end; 16-bit pan/tilt via fine channels; optimistic drag-release; cone/pool meshes off the raycast tree. |

Status values: `Not started` · `In progress` · `Done` · `Blocked` · `Skipped`.
Update the row on session completion and fill in `Completed` with the
ISO date. Detailed handover notes belong in each session's **Status &
handover** block at the bottom of its section.

**Handover block format** — fill these on completion of every session so the
next one can be picked up cold:

- _Status_: one of the values above
- _Completed_: ISO date
- _What landed_: actual paths added/changed; any deviations from the plan;
  anything reduced in scope
- _Open follow-ups_: anything left for a later session (e.g. "data reset
  button still TODO", "decided to skip stageZ until Session 4")
- _Surprises / decisions_: unexpected behaviour, design tradeoffs taken
  in-flight that the next session needs to know about

## Cross-repo work

- **Pan/tilt range metadata** is a backend gap: `FixtureTypeInfo.properties`
  exposes slider min/max in raw DMX units but not the degree mapping or
  tilt inversion (today that info lives only in `@FixtureProperty`
  annotation strings). This will be fixed in `lighting7` as part of
  Session 5 — adding `degMin`, `degMax`, `inverted`, and an `axis`
  enum (`PAN | TILT | NONE`) to slider property descriptors, populated
  from the Kotlin annotations during registry build. The frontend wires
  these into `<FixtureModel>`'s `useFrame` so live moving-head beam
  direction works on first ship of the 3D view.

---

## Session 1 — Foundation

**Goal**: dependencies, types, queries, coordinate helper, data reset.

- Add deps: `three`, `@react-three/fiber`, `@react-three/drei`,
  `@react-three/postprocessing`, `@types/three`. Update `package.json`,
  `npm install`, verify `npm run build` clean.
- Update `FixturePatch` in `src/api/patchApi.ts:4-23` — add `stageZ`,
  `baseYawDeg`, `basePitchDeg`, `riggingUuid`,
  `worldPositionX/Y/Z` (all `number | null`).
- New RTK Query slices, mirroring `src/store/patches.ts`:
  - `src/store/riggings.ts` — `useRiggingListQuery`, `useCreateRiggingMutation`,
    `useUpdateRiggingMutation`, `useDeleteRiggingMutation`. Tag: `'Rigging'`.
  - `src/store/stageRegions.ts` — same shape. Tag: `'StageRegion'`.
  - Mirror the WebSocket invalidation pattern used at
    `src/store/patches.ts:15-17` so live config sync still works.
- Coordinate helper `src/lib/stageCoords.ts`:
  - `toThree(stageX, stageY, stageZ): Vector3` swizzle for R3F's Y-up default
    (lighting coords are Z-up — easier to swizzle than to flip
    `Object3D.DEFAULT_UP`, since the app already uses default-up Three.js
    nowhere else and we avoid camera-framing surprises).
  - `panTiltToDir(panDeg, tiltDeg): Vector3` — see the convention block below.
    (Superseded the discovery-doc prototype at lines 109–116, which hardcoded a
    270° pan centre and never centred tilt at all.)
  - `worldPositionFor(patch, riggings): Vector3` — prefers
    `worldPositionX/Y/Z` if set, else composes from `stage*` + rigging frame.
### Pan/tilt convention (revised)

Every rotation below is in R3F space (X = stage right, Y = up, Z = downstage).

- **`baseYawDeg` / `basePitchDeg` are the fixture body's mount orientation**,
  applied as `Euler(pitch, yaw, 0, 'YXZ')` to a group wrapping the body mesh.
  Nothing else consumes them — folding them into the beam maths as well would
  double-apply.
- **Pan and tilt are signed angles about each axis's mechanical centre**, where
  centre is `(degMin + degMax) / 2`. The backend declares `degMin`/`degMax` as
  the axis's *travel range* (pan 0–540, tilt 0–210), not a signed angle, so the
  DMX-centre position is the midpoint — never 0, and never a fixed 270.
- **A head that tilts rests along +Y in its own frame**: mid-DMX tilt points up
  the body axis, away from the base. A floor-standing mover is `basePitch = 0`;
  a hung one is `basePitch = 180`.
- **A fixture with no tilt axis is a rigid body emitting along −Y** (its lens
  face). That keeps a PAR at pitch 0 pointing at the floor rather than the
  ceiling, which is why the two rest poses differ.
- The rest axis is chosen from **the presence of a tilt descriptor, never from
  `kind`** — a Source 4 Revolution is a `PROFILE` that pans and tilts, and a
  Scantastic 4 is a `SCANNER` that does.
- A body with a modelled yoke publishes `yokeRef`: pan drives the yoke (arms and
  all) and tilt drives the head between them. Bodies without one take the
  combined `headQuaternionFor(pan, tilt)`. `stageCoords.test.ts` asserts the two
  paths agree.
- The beam's origin and direction are read from the **lens and head world
  matrices**, not recomputed in JS, so the drawn model and the beam cannot
  disagree.

**Migration (applied 2026-08-09).** Removing the hardcoded −270 changed the
meaning of `baseYawDeg` for fixtures with no pan axis, where it had effectively
meant "yaw − 270". Static patches carrying a base orientation had
`baseYawDeg += 90` applied (5 rows across Experiment, TCH: Sleeping Beauty and
The Commemoration Hall) — appearance-preserving, and pinned by a regression test
in `stageCoords.test.ts`. Movers whose pan range isn't 0–540 would need
`+= centre − 270` (−180 for Scantastic 4, +45 for Varytec Easymove XL 60); none
had a base orientation set, so nothing was needed. Mover `basePitchDeg` cannot be
migrated — the old value was an aim offset inside a broken convention, not a
mount orientation, so re-author it as 0 (floor) or 180 (hung).

- **Data reset**: one-time mutation flow that nulls `stageX/Y/Z` on all patches
  with non-null values (existing percentages are meaningless under the new
  metres convention). Either a small admin button on `Patches.tsx` ("Clear
  legacy stage positions"), or done by hand once and relied on. Recommend the
  button — leaves an obvious migration path documented in the UI.

**Critical files**:
- `package.json`
- `src/api/patchApi.ts`
- `src/store/patches.ts` (reference for slice pattern)
- `src/store/riggings.ts`, `src/store/stageRegions.ts` (new)
- `src/lib/stageCoords.ts` (new)

**Verify**: `npm run type-check`, `npm run build`. Confirm new query hooks
return data in DevTools against a project with hand-seeded riggings + regions.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-03
- _What landed_:
  - Frontend deps: `three@^0.171`, `@react-three/fiber@^9.6`,
    `@react-three/drei@^10.7`, `@react-three/postprocessing@^3.0`,
    `@types/three@^0.171`.
  - `src/api/patchApi.ts` — extended `FixturePatch`, `CreatePatchRequest`,
    `UpdatePatchRequest` with `stageZ`, `baseYawDeg`, `basePitchDeg`,
    `riggingUuid`, plus read-only `worldPositionX/Y/Z` on `FixturePatch`.
  - `src/api/riggingApi.ts`, `src/api/stageRegionApi.ts` (new) — DTOs +
    create/update request types + WebSocket subscribe modules listening
    for `riggingListChanged` / `stageRegionListChanged`.
  - `src/api/lightingApi.ts` — `riggings` and `stageRegions` wired in.
  - `src/store/restApi.ts` — added `'Rigging'` and `'StageRegion'` tag types.
  - `src/store/riggings.ts`, `src/store/stageRegions.ts` (new) — RTK Query
    slices mirroring the patches.ts shape, with WS-driven tag invalidation.
  - `src/lib/stageCoords.ts` (new) — `toThree` (Z-up→Y-up swizzle),
    `panTiltToDir`, `worldPositionFor`, plus a `headQuaternionFor` helper
    used by Session 5's `<FixtureModel>` head-rotation update.
- _Open follow-ups_:
  - `worldPositionFor` rigging-frame composition is a translate-only stub.
    Real rotated-frame composition lands in Session 4 (or callers can rely
    on the backend's pre-composed `worldPositionX/Y/Z` until then — that's
    the preferred path anyway).
  - Frontend still has `riggingPosition: string | null` on `FixturePatch`
    and the form. Backend dropped it from `FixturePatchDto`; runtime value
    is `undefined` and existing checks tolerate that. Cleanup happens in
    Session 4 (per the existing plan).
- _Surprises / decisions_:
  - **Cross-repo addition**: lighting7 didn't yet broadcast
    `riggingListChanged` / `stageRegionListChanged`. Added
    `RiggingListChangedOutMessage` / `StageRegionListChangedOutMessage` in
    `Sockets.kt`, extended `FixturesChangeListener` with
    `riggingListChanged()` / `stageRegionListChanged()`, and called them
    from the rigging and stage-region routes. Rigging PUT/DELETE also
    broadcast `patchListChanged` so any rig-mounted patches' world
    positions are re-fetched downstream.
  - **Data reset dropped**: no meaningful legacy `stageX/Y` data exists in
    practice, so the planned admin button on Patches.tsx wasn't built.
  - **Three.js dep majors pinned** to versions known to work with React 19
    (R3F 9.x, drei 10.x, postprocessing 3.x).

---

## Session 2 — Stage Configuration

**Goal**: form-based CRUD for stage regions; project stage dimensions.

- Project-level: confirm `stageWidthM/DepthM/HeightM` are surfaced on
  Project Settings; if not, add the inputs there. Backend already persists.
- New page or section under existing Project Settings: **Stage Regions**.
  - Reuse the table + edit-sheet pattern from
    `src/routes/Patches.tsx` + `src/components/patches/EditPatchSheet.tsx`.
  - Sheet fields: name, centerX/Y/Z, widthM/depthM/heightM, yawDeg, sortOrder.
  - All numeric fields: `<Input type="number">` with `onFocus(e.target.select())`
    pattern from `EditPatchSheet.tsx:214`.
  - Footer: `flex-row justify-between` with destructive Delete + Cancel/Save
    per CLAUDE.md sheet conventions.
- Add to `src/navigation.ts` under Settings or Setup. Likely as a child of a
  new **Stage** group (which will also host Rigging in Session 3).

**Critical files**:
- `src/routes/StageRegions.tsx` (new)
- `src/components/stage/EditStageRegionSheet.tsx` (new)
- `src/navigation.ts`

**Verify**: create a region, edit it, delete it. Confirm WS invalidation makes
a second tab refresh.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-03
- _What landed_:
  - `src/api/projectApi.ts` — `ProjectDetail` and `UpdateProjectRequest`
    extended with `stageWidthM/DepthM/HeightM: number | null`.
  - `src/routes/StageRegions.tsx` (new) — exports `StageRegionsContent`
    (table + "Add region" button + edit sheet) and a
    `StageRegionsRedirect` for `/stage-regions` / current-project deep
    links. Regions sort by `sortOrder` then `id`.
  - `src/components/stage/EditStageRegionSheet.tsx` (new) — single sheet
    for create + edit, with destructive Delete in edit mode. Numeric
    fields treat empty string as `null`.
  - `src/routes/ProjectSettings.tsx` — added a fourth tab "Stage" that
    renders `StageDimensionsCard` (project-level w/d/h inputs hitting
    `useUpdateProjectMutation`) above `StageRegionsContent`.
  - `src/navigation.ts` — added "Stage" child entry under
    `project-settings` (icon: `Box`) so the sidebar deep-links to the
    new tab the same way `patches`/`surfaces` already do.
- _Open follow-ups_:
  - Riggings CRUD lands in Session 3 with the same shape (list page +
    edit sheet, child of `project-settings`).
  - Patch-placement field rework (Session 4) will start consuming
    `useStageRegionListQuery` + the project's stage dims for snap/clamp
    behaviour.
- _Surprises / decisions_:
  - Stage dimensions placed on the new Stage tab (above regions) rather
    than the General tab — keeps spatial config grouped. Confirmed with
    user in plan review.
  - No backend changes needed — Session 1 already exposed the
    stage-dimension fields on `Project`, and `StageRegion` REST + WS
    hooks were already wired.
  - Removed the unused `TabsContent` import from `ProjectSettings.tsx`
    while editing — drive-by cleanup.

---

## Session 3 — Rigging Configuration

**Goal**: form-based CRUD for riggings.

- Same shape as Session 2: list page + edit sheet.
- Fields: `name`, `kind` (free-text or pick from advisory list:
  TRUSS/BAR/BOOM/PIPE/FLOOR_STAND/OTHER), `positionX/Y/Z`,
  `yawDeg/pitchDeg/rollDeg`, `sortOrder`.
- Sensible defaults on create: a flown truss `kind=TRUSS`, `positionZ=4.5`,
  yaw/pitch/roll = 0.

**Critical files**:
- `src/routes/Riggings.tsx` (new)
- `src/components/rigging/EditRiggingSheet.tsx` (new)
- `src/store/riggings.ts` (from Session 1)

**Verify**: round-trip create/edit/delete; confirm
`GET /api/rest/project/{id}/riggings` returns the new entries.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-03
- _What landed_:
  - `src/routes/Riggings.tsx` (new) — exports `RiggingsContent({projectId})`
    with table (Name / Kind / Position / Rotation / Sort columns, hidden at
    sm/md/lg breakpoints), Add button, click-row-to-edit, edit sheet trigger.
    Sorted by `sortOrder` then `id`.
  - `src/components/rigging/EditRiggingSheet.tsx` (new) — single sheet for
    create + edit with destructive Delete in edit mode. `EMPTY_FORM` ships
    flown-truss defaults (`kind=TRUSS, positionZ=4.5, yaw/pitch/roll=0`)
    so an untouched create posts those values verbatim. Dirty-diff PUT on
    edit. Kind is a Select dropdown over the advisory list
    (`TRUSS / BAR / BOOM / PIPE / FLOOR_STAND / OTHER`) — required, no
    null/None option since there are no legacy entries to preserve.
  - `src/lib/utils.ts` — added `formatTriple` and `formatRotation`
    (extracted/shared between the regions and riggings list pages).
  - `src/components/ui/form-fields.tsx` (new) — exports shared
    `FieldGroup` and `NumberField`, replacing the duplicate copies that
    had grown in both edit sheets.
  - `src/routes/ProjectSettings.tsx` — added a fifth tab "Rigging" that
    renders `RiggingsContent` inside the standard `p-4 space-y-4 max-w-3xl`
    wrapper.
  - `src/navigation.ts` — added `rigging` child entry under
    `project-settings` (icon: `Anchor`).
- _Open follow-ups_:
  - Patch-side rig assignment (Mounting select tying `riggingUuid` to a
    rigging) lands in Session 4, plus the `riggingPosition` legacy field
    cleanup carried over from Session 1.
  - `worldPositionFor` rigging-frame composition is still translate-only
    (Session 1 carry-over); real rotated-frame composition or reliance on
    the backend's pre-composed `worldPositionX/Y/Z` is the Session 4 path.
- _Surprises / decisions_:
  - **Kind is a required Select**, not free text. Form state narrows to a
    `Kind` union (`TRUSS / BAR / BOOM / PIPE / FLOOR_STAND / OTHER`); no
    `None` option because there are no legacy entries that could carry an
    out-of-list value.
  - **`EMPTY_FORM` defaults are non-null** (opposite of the StageRegions
    sheet). The create call therefore sends `kind=TRUSS, z=4.5, yaw=pitch=roll=0`
    even when the user touches nothing — exactly the plan's flown-truss
    convention.
  - **Position fields have no `min={0}`** — riggings can sit at negative
    X/Y (downstage of zero). Only StageRegion size fields had a floor.
  - **Skipped a `RiggingsRedirect` export** — Session 2's parallel
    `StageRegionsRedirect` is currently unused, so building one for parity
    would just be dead code.
  - **`Anchor` icon** picked for the truss-hangs-from-structure metaphor.
  - **simplify pass extracted shared helpers**: `formatTriple` to
    `lib/utils.ts` (now used by both regions and riggings tables) and
    `FieldGroup`/`NumberField` to `components/ui/form-fields.tsx` (now
    used by both edit sheets). Dropped the duplicate copies that had been
    pasted into Session 2's files.

---

## Session 4 — Patching: Rigging-Mounted vs Free

**Goal**: edit rig assignment + 3D position on a patch (form-based).

- Rework the Stage section of `EditPatchSheet.tsx:226-235`:
  - Replace `<StageMapField>` (legacy 2D %) with a new `<PatchPlacementFields>`
    component containing:
    - **Mounting** select: "Free" or one of the project's riggings (label =
      `RiggingDto.name`). Bound to `riggingUuid`.
    - **Position** group: `stageX/stageY/stageZ` numeric inputs (metres). Help
      text changes based on mount: "world coordinates" vs
      "offset from rigging origin".
    - **Base orientation** group (collapsible, mostly for moving heads):
      `baseYawDeg`, `basePitchDeg`. Defaults left null.
- Drop `riggingPosition: string` from the form (free-text label, superseded by
  structured `riggingUuid`). Keep the field on the type for now; a follow-up
  can remove it from the DTO once nothing reads it.
- Save flow uses the existing `useUpdatePatchMutation`, partial-PUT pattern at
  `EditPatchSheet.tsx:140-150`. The backend will recompute `worldPositionX/Y/Z`
  on update.
- `Patches.tsx` table: add a "Mount" column showing rigging name or "Free".

**Critical files**:
- `src/components/patches/EditPatchSheet.tsx`
- `src/components/patches/PatchPlacementFields.tsx` (new — replaces
  `StageMapField` for editing)
- `src/routes/Patches.tsx`

**Verify**: assign a fixture to a rigging with offsets; confirm
`worldPositionX/Y/Z` returned on next list query reflects the composition.
Reassign to "Free"; confirm `riggingUuid` clears and world position becomes
the raw `stageX/Y/Z`.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-03
- _What landed_:
  - `src/components/patches/PatchPlacementFields.tsx` (new) — exports
    `PatchPlacementValue` + the component. Mounting `<Select>` (Free + each
    rigging from `useRiggingListQuery`), Position group (X/Y/Z metres) with
    flipping help text ("offset from rigging origin" vs "world coordinates"),
    and a native `<details>` "Base orientation (advanced)" wrapping
    yaw/pitch number fields. Reuses `FieldGroup` + `NumberField` from
    `components/ui/form-fields.tsx`.
  - `src/components/patches/EditPatchSheet.tsx` — replaced `stage` +
    `riggingPosition` state with one `placement: PatchPlacementValue`
    atom; removed `useMemo`/`useRef` imports. Dropped the 300 ms drag-debounce
    `useEffect` and `dragTimerRef` (Session 6 will reintroduce drag-debounce
    inside the 3D editor's own handlers). Dropped the `riggingPresets`
    memo and the `otherFixtures` memo with `StageMapField`. Dirty-diff PUT
    + `hasChanges` now cover `riggingUuid`, `stageX/Y/Z`, `baseYawDeg`,
    `basePitchDeg`. Stage section JSX collapsed from two blocks to a single
    `<PatchPlacementFields>`.
  - `src/routes/Patches.tsx` — added `useRiggingListQuery` and threaded a
    `Map<uuid,name>` into `buildPatchRows`. `PatchRow.riggingPosition`
    swapped for `riggingName`. The "Position" column became "Mount" at the
    same `hidden md:table-cell` slot — rigging name plain, italic
    muted "Free" otherwise.
  - Deleted `src/components/patches/StageMapField.tsx` and
    `src/components/patches/RiggingPositionInput.tsx` — both were only
    referenced by `EditPatchSheet`.
- _Open follow-ups_:
  - ~~`riggingPosition` field still on `FixturePatch` /
    `CreatePatchRequest` / `UpdatePatchRequest` in `src/api/patchApi.ts`
    and read by `src/components/stage/StageMarker.tsx` (legacy 2D marker
    label). Per the Session 4 plan body it stays for now; full DTO removal
    is a follow-up once nothing reads it (likely after the 2D view is
    retired in/after Session 5).~~ — **closed** by the frontend sweep's
    `FS-TYPES-RIGGING-POSITION`, without waiting for the 2D view to retire:
    all three declarations are gone, and the marker badge now names the
    rigging resolved from `riggingUuid` instead of a field the server had
    already stopped sending.
  - `worldPositionFor` rigging-frame composition (Session 1 carry-over) is
    still translate-only. Session 5 callers should prefer the backend's
    pre-composed `worldPositionX/Y/Z`.
- _Surprises / decisions_:
  - **Auto-save dropped, not preserved.** The 300 ms debounced PUT was a
    StageMap-drag affordance; it doesn't belong on a structured form where
    every other field waits for Save. Session 6 reintroduces drag-debounce
    locally inside the 3D editor where it actually applies.
  - **Mount column reuses the Position slot, not appended.** `riggingPosition`
    now arrives as `undefined` from the backend, so the legacy column was
    rendering empty for every row — replacing rather than adding keeps the
    table from gaining a dead column.
  - **Free is italic muted text, not a badge.** Visually quieter than the
    rigging-name plain text, so the "no mount" state recedes while the
    structured assignment reads clearly.
  - **Native `<details>` for Base orientation.** Avoids pulling another
    Radix primitive; `<summary>` styling is the only custom touch.
  - **`useRiggingListQuery` takes a positional `number`**, not the
    `{ projectId }` object I initially wrote — caught at type-check.

---

## Session 5 — Read-Only 3D Stage View

**Goal**: render the scene from REST + live DMX. No editing yet.

- **Backend prep (lighting7)** — extend `PropertyDescriptor` for slider
  properties with:
  - `axis: "PAN" | "TILT" | "NONE"` (defaults to NONE)
  - `degMin: Double?`, `degMax: Double?` — degree mapping at slider min/max
  - `inverted: Boolean` — flips direction (some fixtures invert tilt)

  Populate from existing `@FixtureProperty` annotations in
  `FixtureTypeRegistry.kt` (parse the annotation strings, or, cleaner, add
  optional Kotlin annotation parameters and migrate the registered fixtures
  in the same change). Surface these on `FixtureTypeInfo.properties` over
  REST. Frontend `FixtureTypeInfo` slider type updated to match.
- New route `/stage` (or rename existing). New `src/routes/Stage.tsx` containing:
  - 2D ↔ 3D toggle button (segmented control, persisted in localStorage).
    2D branch renders existing `<StageOverviewPanel>` unchanged.
  - 3D branch renders `<Stage3D>`.
- `src/components/stage3d/Stage3D.tsx` — Canvas root:
  - `<Canvas>` from `@react-three/fiber`, `flat` shadow, `linear` colorspace.
  - `<OrbitControls>` from drei (touch enabled).
  - `<Bloom luminanceThreshold={0.15} intensity={1.7} radius={0.5} />` from
    `@react-three/postprocessing` per discovery doc lines 75–77.
  - Coordinate convention: see `src/lib/stageCoords.ts` swizzle. Camera framed
    to project bounding box (`stageWidthM/DepthM`).
- `<StageRegionMeshes>` — boxes from `useStageRegionListQuery`.
- `<RiggingMeshes>` — long thin boxes from `useRiggingListQuery`, oriented by
  yaw/pitch/roll.
- `<FixtureModel>` — one per patch:
  - Clamp + head group + lens disc + 2 cones + 2 floor pool circles per
    discovery doc lines 42–80.
  - Static position from `worldPositionFor(patch, riggings)`.
  - **Live colour** via `useColourValue` (or `useSettingColourPreview` /
    gel fallback) reusing the same property-resolution logic from
    `StageMarker.tsx:36-46`.
  - **Live intensity** via `useNormalizedIntensity` pattern at
    `StageMarker.tsx:190-204`.
  - **Live beam direction**: read pan/tilt slider values via
    `useSliderValue`, map to degrees using the new `degMin/degMax/inverted`
    metadata, compose with `baseYawDeg/basePitchDeg`, then convert to a
    direction vector via `panTiltToDir`. Apply imperatively to head group
    quaternion + cone + floor pool inside `useFrame`, no React re-renders.
- Reuse colour-source resolution (`findColourSource`,
  `findDimmerProperty` from `src/store/fixtures.ts`) — do NOT duplicate.

**Critical files**:
- `src/routes/Stage.tsx` (new — replaces or wraps current stage route)
- `src/components/stage3d/Stage3D.tsx` (new)
- `src/components/stage3d/StageRegionMeshes.tsx` (new)
- `src/components/stage3d/RiggingMeshes.tsx` (new)
- `src/components/stage3d/FixtureModel.tsx` (new)
- `src/components/stage3d/Bloom.tsx` (new — wraps the postprocess effect)
- `src/lib/stageCoords.ts` (from Session 1)

**Verify**: load page with a stage region, a truss, a few patched fixtures;
confirm meshes render at expected positions. Run a scene that pulses dimmer
and a colour scene — confirm fixture lens + cone + pool change in real time
without React re-renders (observe via React DevTools Profiler).

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-03
- _What landed_:
  - **Backend (lighting7)**: extended `@FixtureProperty` with `axis`
    (new `PanTiltAxis` enum: `PAN | TILT | NONE`), `degMin`, `degMax`,
    `inverted` optional params (NaN as the unset sentinel for the doubles,
    converted to `null` on reflect). Threaded through
    `Fixture.Property` and both slider-creation paths in
    `DmxFixture.kt` (top-level + element). `SliderPropertyDescriptor` in
    `routes/lightFixtures.kt` gained 4 nullable wire fields. Every
    moving-head fixture in the registry got `axis = PanTiltAxis.PAN/TILT`
    and degree ranges from datasheet:
    Martin Mac 250 (540°/257°), Varytec Easymove XL 60 Spot (630°/270°),
    Robe ColorSpot 575 (540°/257°), Fusion 100 Spot MkII (540°/210° × 3
    modes), Source 4 Revolution (540°/270°), Gear4Music Orbit-70
    (540°/270°), IMG Stageline Wash-42LED (540°/180°), Shehds LED 19 RGBW
    (540°/270° × 2 modes), Slender Beam Bar Quad (540°/270°), Scantastic 4
    (180°/90°). Laserworld CS-1000 RGB Mk3 was deliberately skipped — its
    "Pan/Tilt" channels are galvo offsets, not real head rotation; tagging
    it would mislead the 3D view.
  - **Frontend types**: `SliderPropertyDescriptor` in
    `src/store/fixtures.ts` mirrors the new wire fields (`axis`,
    `degMin`, `degMax`, `inverted`). Added `findPanProperty` and
    `findTiltProperty` helpers alongside `findDimmerProperty`.
  - **`src/lib/stageCoords.ts`**: added `dmxToDegrees(dmx, slider, base?)`
    (returns `null` when the slider lacks both deg bounds — head stays
    static; never invent a range), `headQuaternionFor(panDeg, tiltDeg)`
    (allocation-free Euler→Quaternion variant of `panTiltToDir`), and
    `worldPositionLighting(patch, riggings)` (lighting-coord twin of
    `worldPositionFor`; needed by the 2D top-down panel which reasons in
    metres rather than R3F space).
  - **`src/hooks/useNormalizedIntensity.ts` (new)**: extracted from
    `StageMarker.tsx:190–204` so 2D + 3D leaves share one
    fallback-slider definition.
  - **`src/components/StageOverviewPanel.tsx`**: re-fitted to metric
    coords. Now reads `useProjectQuery` (for `stageWidthM`/`stageDepthM`,
    defaults 10×8 m) and `useRiggingListQuery`; computes each placed
    patch's stage position via `worldPositionLighting`, then maps to
    canvas % with FOH at the bottom, upstage at the top, stage centre at
    `x=0`. Patches with no resolvable position are filtered out. The
    legacy "drag the dot…" empty-state copy now reads "Open a patch and
    set its stage position."
  - **`src/components/stage3d/` (new)**:
    - `Bloom.tsx` — `<EffectComposer>` + `<Bloom luminanceThreshold=0.15
      intensity=1.7 radius=0.5 />` per stage-vis-discovery.md L75–77.
    - `StageRegionMeshes.tsx` — translucent boxes with edge outlines.
    - `RiggingMeshes.tsx` — 3 m bar per rigging (no length field on the
      DTO yet — Session 7 polish), Euler `'YXZ'` rotation matching the
      stageCoords convention.
    - `FixtureModel.tsx` — clamp + head group + lens + outer/inner cone
      + outer/inner floor pool. Colour leaves (`ColourBeamSync`,
      `SettingColourBeamSync`, `FixedColourBeamSync` for gel/dimmer-only)
      mirror the dispatch in `StageMarker.tsx:36–46, 91–126`, but write
      colour/opacity imperatively into the cone/pool/lens material refs
      via `useEffect` — not via React-driven props (avoids one
      re-render per channel push). `<BeamDirector>` reads pan/tilt via
      `useSliderValue`, runs `useFrame` to compute beam direction with
      `dmxToDegrees` + `panTiltToDir`, and updates head quaternion +
      cone orientation + pool floor-intersection imperatively. Module-
      scoped `SCRATCH_DIR / SCRATCH_NEG_DIR / SCRATCH_QUAT / SCRATCH_POOL`
      keep the hot path allocation-free.
    - `Stage3D.tsx` — `<Canvas flat>` with `OrbitControls`, ambient +
      `gridHelper`, `<StageBoxOutline>` wireframe of the stage volume,
      and Bloom. Camera framed against `stageWidthM × stageDepthM`.
  - **Route + nav**:
    - `src/routes/Stage.tsx` (new) — top-level route with 2D ↔ 3D
      `<ToggleGroup size="sm">` persisted in `localStorage["stageViewMode"]`
      (default 3D). `<StageRedirect>` mirrors `FixturesRedirect`.
    - `src/App.tsx` — added `/projects/:projectId/stage` and bare
      `/stage` redirect.
    - `src/navigation.ts` — renamed the existing settings child label
      from "Stage" to "Regions" (id/path unchanged); added a new
      top-level `id: "stage-view"` entry in the `live` group with
      `Boxes` icon, `visibility: "always"`.
- _Open follow-ups_:
  - **No R3F edit interactions yet** — `onClick` selects a fixture
    (visible white ring on the lens) but no drag/sheet handling.
    Session 6 covers click-to-edit + drag.
  - **Rigging length is fixed at 3 m**. A future RiggingDto field
    (`lengthM`?) would let `<RiggingMeshes>` render real proportions;
    deferred to Session 7 polish.
  - **Pan/tilt fine channels (PAN_FINE / TILT_FINE)** are not yet folded
    into the degree calculation — the 3D view reads coarse-only, which
    gives 256-step resolution over the head's full sweep (more than
    enough for visual rendering).
  - **`PositionPropertyDescriptor` was deliberately not extended.** Per
    plan-strict scope the slider variant is the one the frontend reads
    (via `findPanProperty/findTiltProperty` against `axis ===
    'PAN'/'TILT'`). For fixtures that surface only a position descriptor
    and no axis-tagged sliders, the head will render static — acceptable
    for now because the existing reflection emits both descriptors for
    `WithPosition` fixtures.
  - **`worldPositionFor` rigging-frame composition** is still
    translate-only (Session 1 carry-over). Session 5 prefers the
    backend's pre-composed `worldPositionX/Y/Z`, so rotated rigging
    frames work end-to-end via that path; the fallback only hits when
    the backend hasn't re-composed (e.g. a freshly assigned rig before
    the next list query).
  - Legacy `riggingPosition: string | null` field still on
    `FixturePatch` and read by `StageMarker.tsx:63–74` for the badge
    label. Cleanup deferred until the 2D legacy view is fully retired.
  - **Flagged by `/simplify` but skipped this session** (each spans
    multiple unrelated files; pick up when the surface is touched
    again):
    1. **Unify the colour-source dispatch between
       `StageMarker.tsx` and `FixtureModel.tsx`.** Both files run the
       identical `findColourSource` / `findDimmerProperty` /
       `acceptsGel + gelCode → findGel` discriminator and then split
       into a leaf-per-source (Colour / Setting / Gel / Dimmer-only /
       Placeholder). The leaves diverge in *what* they render (CSS vs
       Three materials) but the *input* they need is identical: one
       CSS colour string + a 0..1 intensity. Lift either a
       `resolveColourSource(patch, fixture, fixtureType)` helper into
       `src/store/fixtures.ts` (smaller, returns a tagged union both
       sites switch on), or a full `useFixtureColour(...) → { css,
       intensity }` hook (larger but lets each consumer collapse to a
       single visualiser). Touching `StageMarker` is the risk — it's
       the load-bearing 2D renderer used inside `StageOverviewPanel`
       and `ProjectOverview`.
    2. **Three small generic hooks each duplicated 3+ times
       across the codebase.** All worth extracting on the next sweep
       through their respective files:
       - `useFixtureLookup()` — the `fixtureByKey` /
         `typeByKey` `useMemo` Map-builder duplicated in
         `Stage3D.tsx`, `StageOverviewPanel.tsx`, and
         `runner/program/CueCardEditor/MiniStage.tsx`.
       - `useLocalStorage<T>(key, default)` — `useStageViewMode`
         is the 6th hand-rolled instance (alongside
         `useStageOverview`, `useEffectsOverview`,
         `useFixtureOverview`, `useCueSlotOverview`, plus inline
         `localStorage` reads in `ThemeToggle` and
         `CueSlotOverviewPanel`). Worth one shared hook so the
         JSON-vs-string serialisation policy lives in exactly one
         place.
       - `normaliseSliderDmx(dmx, min, max)` — the `(value - min)
         / span` clamp logic in `dmxToDegrees` is also in
         `usePropertyValues.ts:260–263`,
         `useGroupPropertyValues.ts:407–410`,
         `PropertyVisualizers.tsx:247–251`, and
         `GroupPropertyVisualizers.tsx:299–300`. Lift to a shared
         helper (likely in `src/lib/stageCoords.ts` or
         `src/lib/dmx.ts`).
    3. **Backend pan/tilt description strings now duplicate
       structured `degMin`/`degMax`.** Annotations like
       `@FixtureProperty("Pan adjustment 0-540°", category = PAN,
       degMin = 0.0, degMax = 540.0, ...)` carry the range in two
       places. A future cleanup could either auto-suffix the
       degree range when the structured fields are set
       (centralising the format) or strip the redundant text from
       the description so the structured fields are the only source
       of truth. Do this when the next mover gets added — that's
       the moment the inconsistency would otherwise silently
       grow.
- _Surprises / decisions_:
  - **2D fallback was repaired, not dropped.** The plan body said "2D
    branch renders existing `<StageOverviewPanel>` unchanged" but Session
    4 had silently broken it (CSS `left: %` against metric metres). Per
    user directive 2D is the phone view and stays — so this session
    re-fitted `StageOverviewPanel` to metric coords using
    `useProjectQuery` + `worldPositionLighting`. ProjectOverview's
    embedded panel benefits for free.
  - **Annotation populated for every mover, not just the common ones.**
    Per user directive. Where the description string already encoded
    the range (Varytec, Fusion, IMG, Shehds, Slender, Scantastic) the
    numbers came verbatim; for fixtures with only "Pan (coarse)" the
    datasheet was consulted (Martin, Robe, Source 4, Gear4Music). No
    range was invented.
  - **Two cone-orientation false starts in `<BeamDirector>`.** R3F
    `coneGeometry` defaults to apex at +Y and base at -Y. To put the
    apex at the fixture origin and base along `+dir` requires mapping
    +Y → **-dir** then translating by +(length/2)*dir. Doing it the
    "obvious" way (+Y → +dir) puts the base in the wrong direction.
    Documented inline.
  - **Colour applied via `useEffect`, not React props on materials.**
    Cones/pool/lens use `MeshBasicMaterial` whose colour we mutate
    imperatively from a leaf component that subscribes to the colour
    hook. Setting colour via JSX would re-render `<FixtureModel>` on
    every channel push; the imperative update keeps the geometry tree
    stable. Lens opacity tracks `0.5 + 0.5*intensity` so the lamp body
    is always visible when on, not just during full intensity.
  - **`Boxes` icon for the new top-level entry.** `Box` was already
    used by the (now renamed) "Regions" settings child; `Boxes` (lucide)
    visually echoes the multi-mesh nature of the 3D view.
  - **Cross-repo build passed clean** with two pre-existing
    `Unchecked cast of KProperty1` warnings unchanged. No frontend
    type-check or build regressions; the type-check was clean on the
    second pass after fixing a `project` variable shadow in
    `StageOverviewPanel`.

---

## Session 6 — 3D Editor Mode

**Goal**: visual editing on tablet+ viewports.

- Add an "Edit" toggle in `<Stage3D>` toolbar — only enabled when
  `useMediaQuery(SM_BREAKPOINT)` returns true. Phones see view-only.
- **Click-to-edit**: pointer events on each mesh open the relevant existing
  edit sheet — `EditStageRegionSheet`, `EditRiggingSheet`, `EditPatchSheet`.
  Sheets are slide-over so the 3D canvas remains visible behind them and
  changes are seen live.
- **Visual drag**:
  - Stage regions / riggings: drei `<TransformControls>` for
    translate-on-ground-plane. Rotation via gizmo for yaw.
  - Fixtures: depends on mount.
    - Free fixtures: `TransformControls` translate.
    - Rigging-mounted: drag along the rigging's local X axis only (project
      pointer onto the rigging's line; update `stageX` only).
- **Live binding**: drag updates local mesh transform AND the in-flight
  edit-sheet form state. Mutation is debounced (300ms) per the pattern at
  `EditPatchSheet.tsx:76-85` so the backend isn't hammered mid-drag.
- **Add new** affordances: "+ Add Rigging" / "+ Add Region" buttons in the
  toolbar that drop a default-sized object at stage centre, then immediately
  open its edit sheet.
- Form fields on the sheets continue to work — visual is "another input",
  numeric fields stay authoritative for precise placement.

**Critical files**:
- `src/components/stage3d/Stage3D.tsx` (extended)
- `src/components/stage3d/EditModeProvider.tsx` (new — context for selection +
  edit mode toggle)
- `src/components/stage3d/dragHandlers/` (new — translate-on-plane, drag-along-rig)
- existing edit sheets from Sessions 2/3/4

**Verify**: drag a fixture across the floor, see beam pool follow and the
sheet's `stageX/Y` numbers update live; release, then 300ms later see the
PUT fire (Network tab). On mobile width, confirm the Edit toggle is
disabled with an explanatory tooltip.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-04
- _What landed_:
  - `src/routes/Stage.tsx` — promoted to the editor host. Adds `editMode`,
    `selection: { kind, id } | null`, and `sheetOpenFor: SheetState | null`
    state. Header gained an Edit toggle (gated on `useMediaQuery(SM_BREAKPOINT)`,
    visible in 3D mode only) and `+ Region` / `+ Rigging` buttons (visible
    only when editing is active). Mounts all three edit sheets — `EditPatchSheet`,
    `EditStageRegionSheet`, `EditRiggingSheet` — controlled by `sheetOpenFor`,
    each with an imperative ref. Drag callbacks call `sheetRef.setPlacement(…)`
    on every tick (live form sync) and the matching `useUpdate{Patch,
    StageRegion,Rigging}Mutation` only on `settled=true` (single PUT per drag).
    Auto-drops edit mode if the viewport shrinks below SM. New
    `findByUuid<T extends { uuid: string }>` helper to flatten the sheet-entity
    lookups.
  - `src/components/stage3d/Stage3D.tsx` — major rewrite around an inner
    `Controls` component. New props: `editMode`, `selection`,
    `onSelectionChange`, plus three optional `on{Patch,Region,Rigging}…Change`
    callbacks each carrying `(entity, next, settled)`. Manages a local
    `target: Object3D | null` (set imperatively from click events via
    `e.eventObject`) and a `gizmoMode: 'translate' | 'rotate'` state with a
    derived `effectiveGizmoMode` that pins patches to translate. Renders
    `<TransformControls>` from drei against the target whenever edit mode +
    target are present; subscribes to the underlying THREE
    `dragging-changed` event to disable `OrbitControls` during drag and to
    fire the settled flush on release. The flush helper:
    `target.getWorldPosition()` → `patchPlacementFromWorld` (with rig-local
    projection for rig-mounted patches) / region floor-anchor math (backs
    out the +h/2 lift) / direct rigging coords. Memoised
    `selectedPatch/Region/Rigging` and `riggingByUuid: Map<string, RiggingDto>`
    keep the per-tick flush off `Array.find`. A small Move/Rotate HTML
    overlay sits absolute-positioned over the canvas, visible only when a
    region or rigging is selected.
  - `src/components/stage3d/StageRegionMeshes.tsx` &
    `src/components/stage3d/RiggingMeshes.tsx` — added `selectedUuid` +
    `onClick(entity, mesh: Object3D)` props. Click handlers
    `e.stopPropagation()` and pass `e.eventObject` so the parent can attach
    TransformControls to the actual Three.js node. Selected materials get a
    brighter colour + edge tint (regions) / brighter base + emissive (riggings).
  - `src/components/stage3d/FixtureModel.tsx` — `onClick` signature changed
    from `() => void` to `(group: Group) => void`; the click handler
    `stopPropagation`s and emits `e.eventObject`. White selection ring on
    the lens unchanged.
  - `src/components/patches/EditPatchSheet.tsx` — wrapped in `forwardRef`
    exposing `EditPatchSheetHandle.setPlacement(p)`. Seeding `useEffect`
    dep narrowed from `[patch]` to `[patch?.id]` so refetched patch data
    after a drag-settled PUT doesn't clobber values the user just typed.
  - `src/components/stage/EditStageRegionSheet.tsx` — same treatment.
    Exposes `setPosition({ centerX, centerY, centerZ, yawDeg })`. Seeding
    dep already keyed on `region?.uuid`.
  - `src/components/rigging/EditRiggingSheet.tsx` — same. Exposes
    `setPosition({ positionX, positionY, positionZ, yawDeg, pitchDeg, rollDeg })`.
  - `src/lib/stageCoords.ts` — added `fromThree(v)` (R3F → lighting coords;
    inverse of `toThree`).
  - **Simplify-pass cleanups (folded in)**:
    - `src/hooks/useFixtureLookup.ts` (new) — exports
      `useFixtureLookup(): { fixtures, fixtureTypes, fixtureByKey, typeByKey }`.
      Replaces the 3× duplicated `fixtureByKey/typeByKey` `useMemo` blocks
      in `Stage3D`, `StageOverviewPanel`, and `runner/program/CueCardEditor/MiniStage`.
    - All three sites migrated to the hook; `useFixtureListQuery` /
      `useFixtureTypeListQuery` imports dropped where the hook now covers them.
    - Inline `(r * 180) / Math.PI` and `(d * Math.PI) / 180` swapped for
      `MathUtils.radToDeg` / `MathUtils.degToRad` (Three.js exports both).
    - Free-fixture branch of `patchPlacementFromWorld` uses `fromThree(worldR3F)`
      directly instead of inline `(x, -z, y)` swizzle.
    - Removed an unused `useDebouncedCallback` helper that was created
      during planning but obsoleted by the save-on-release pattern.
    - Removed the `gizmoMode`-reset `useEffect` in favour of the derived
      `effectiveGizmoMode`. Removed three dead `selected*` locals in
      `Stage.tsx` (`sheetPatch` etc. now look up directly).
- _Open follow-ups_:
  - **No optimistic update on drag-release.** Between mutation fire and
    refetch landing the moved object visibly snaps back to its prior
    position for the round-trip duration (typically a few hundred ms on
    localhost). To fix: dispatch `restApi.util.updateQueryData('patchList', …)`
    inside the change handler before firing the PUT. Deferred — UX wart,
    not a correctness bug.
  - **Stale `target` reference if the selected entity is deleted while
    selected.** Three.js operations on a detached `Object3D` are safe but
    drag would no-op silently. Add a parent-presence check or clear
    target on RTK Query refetch. Low risk.
  - **`useLocalStorage<T>(key, default)` consolidation flagged but not
    done** — still 6 hand-rolled siblings (`useStageOverview`,
    `useEffectsOverview`, `useFixtureOverview`, `useCueSlotOverview`,
    `useStageViewMode`, plus inline reads in `ThemeToggle` and
    `CueSlotOverviewPanel`). Touches 7 files; pick up when one of them
    needs a feature change.
  - **Rig-axis constraint is yaw-only-correct in practice.** The rig
    `worldToLocal` projection uses the full `YXZ` Euler so pitched/rolled
    rigs would also constrain correctly — but the *visual* gizmo doesn't
    snap to the rig axis during drag (only on release). A future polish
    could constrain visually by attaching a hidden helper Object3D with
    the rig's rotation and using `space="local" showY={false} showZ={false}`
    on TC.
  - **Region drag updates centerZ**: I back out the +h/2 lift so vertical
    drag persists the floor anchor cleanly, BUT this means a region
    can be dragged off the floor by accident. If floor-locking is wanted,
    restrict TC translate to X/Z (drei `showY={false}`) for region targets.
- _Surprises / decisions_:
  - **Scene-graph parenting under rig groups was scoped out** (deviation
    from the `--Recommended--` answer in the planning AskUserQuestion).
    The parenting would put rigged fixtures inside a rotated parent so
    `space="local"` X-only drag works on the gizmo — but the floor pool
    is currently a child of the fixture group, and re-rendering the pool
    on the world floor under a rotated parent requires either lifting
    the pool out of the fixture tree (intrusive) or `worldToLocal` per
    frame (subtle). The post-projection approach (free XYZ drag → rig
    `worldToLocal` → take only X) achieves the same persisted state with
    none of the rendering churn. Visual gizmo doesn't snap to rig axis
    during drag, only on release; flagged above.
  - **Save-on-release replaced the planned 300ms debounce.** Plan body
    referenced an "EditPatchSheet.tsx:76-85 debounce pattern" that turned
    out not to exist (Session 4 handover noted the auto-save was dropped
    when the StageMap was retired). Save-on-release is functionally
    equivalent for the "no mid-drag hammering" goal and avoids the
    cross-entity race where switching selection inside a 300ms window
    would discard the prior entity's pending PUT. The
    `useDebouncedCallback` helper that I built for the originally-planned
    flow ended up unused and was deleted in the simplify pass.
  - **Imperative refs over lifted form state.** Considered making
    `placement` (and the analogous region/rigging position triples) a
    fully-controlled prop on each sheet, which would have been more
    React-idiomatic. Imperative `setPlacement` / `setPosition` via
    `forwardRef` won because it avoided rewriting all three sheets'
    `hasChanges` / dirty-diff logic. The **id-only seeding effect** is
    the load-bearing piece: without it, the post-PUT refetch would
    clobber any value the user typed during the round trip.
  - **Click via `e.eventObject` rather than callback refs.** The
    cleanest way to get the Three.js `Object3D` of the clicked target
    into TC. R3F's pointer events expose both `e.object` (closest hit
    mesh) and `e.eventObject` (the listener-attached node); the listener
    is on the outer fixture/region/rigging group/mesh, so
    `e.eventObject` is exactly what TC needs.
  - **OrbitControls disabled via THREE 'dragging-changed' event** rather
    than relying on drei `makeDefault` to coordinate. drei does pass the
    event through but doesn't auto-disable Orbit on Transform drag in
    the version pinned here; manual wiring was simpler than upgrading.
  - **`MathUtils` import** ended up the cleanest source for both
    `radToDeg` and `degToRad` — Three's standard utility, no new dep,
    consistent across Stage3D and the existing RiggingMeshes/StageRegionMeshes
    callsites.

---

## Session 7 — Polish (optional)

- **Zoom**: read beam-angle DMX channel into cone radius; currently fixed at
  `patch.beamAngleDeg` static value.
- **Performance**: cap `castShadow` to a configurable subset; raycast only the
  selected mesh in edit mode; geometry instancing for repeated truss segments.
- **Gobo / strobe / fixture library**: out of scope per discovery doc lines
  381–387. Track separately.

**Status & handover**:

- _Status_: Done
- _Completed_: 2026-05-04
- _What landed_:
  - **Rigging `lengthM` end-to-end** (cross-repo). Backend: added nullable
    `length_m` column on `riggings` (auto-applied via
    `SchemaUtils.createMissingTablesAndColumns`; no historical migration
    function needed because the column is nullable). DTO/wire field added to
    `RiggingDto`, `CreateRiggingRequest`, `RiggingJson` (sync export/import),
    plus PUT body handling and a `validateRiggingPose` range check (0 < x ≤
    100 m + finite-number guard, mirroring `checkAngle`'s NaN handling).
    Frontend: matching `lengthM: number | null` on `RiggingDto` /
    `CreateRiggingRequest` / `UpdateRiggingRequest`; new "Length (m)" input
    on `EditRiggingSheet` (default 3 m on create, helper text "Length along
    the rig's local X axis"); `<RiggingMeshes>` uses
    `rig.lengthM ?? DEFAULT_RIGGING_LENGTH_M (= 3)` so the box geometry
    width matches the real bar. Thickness extracted into a named constant
    (`RIGGING_THICKNESS_M = 0.18`).
  - **16-bit pan/tilt via PAN_FINE / TILT_FINE channels.** Added
    `findPanFineProperty` / `findTiltFineProperty` (matches sliders by
    `category === 'pan_fine' / 'tilt_fine'`) in `src/store/fixtures.ts`.
    `BeamDirector` in `FixtureModel.tsx` subscribes to coarse + fine via
    parallel `useSliderValue` calls (using `makeFallbackSlider` for the
    stable hook-call order when fine is absent), then combines as
    `coarse + fine/256` before passing to `dmxToDegrees`. Fine slider
    range stays implicit — slider min/max in `dmxToDegrees` are still the
    coarse-channel 0–255, fine just contributes a sub-step inside one
    coarse step. Effect: 65 536-step head positioning on the ten registered
    movers that ship `PAN_FINE / TILT_FINE` (Martin Mac 250, Varytec, Robe
    ColorSpot 575, Source 4 Revolution, Gear4Music Orbit-70, Fusion 100
    Spot MkII, IMG Wash-42LED, Shehds LED 19 RGBW, Slender Beam Bar Quad).
  - **Optimistic update on drag-release.** Each `onPatch/Region/Rigging
    PositionChange` callback in `Stage.tsx` now dispatches
    `restApi.util.updateQueryData` to pre-apply the dragged values to the
    cached list before firing the PUT. For patches the optimistic write
    also nulls `worldPositionX/Y/Z` so `worldPositionFor` falls through to
    the just-written `stageX/Y/Z` instead of the stale composed value.
    The mutation's `invalidatesTags` still triggers a refetch that
    overwrites with authoritative server state — but the snap-back the
    user used to see during the round-trip is gone. Uses the singleton
    `store.dispatch` directly rather than `useDispatch` to side-step the
    typed-AppDispatch dance (the project doesn't export an `AppDispatch`
    type yet).
  - **Performance: raycast off for visual-only meshes.** Cones, floor pool
    discs, and the `StageBoxOutline` invisible box now have
    `raycast={NO_RAYCAST}` (a const `() => {}` no-op) so pointer events
    skip them entirely. Cones in particular are large transparent meshes
    that R3F was triangle-testing on every pointer move; the click target
    bubbles up via the small lens / yoke meshes to the outer fixture group
    instead. Fixture click handler is also gated on `editMode` —
    non-editing viewers get no fixture pointer listener at all.
- _Open follow-ups_:
  - **Zoom (beam-angle DMX channel → cone radius) deferred.** Implementing
    cleanly needs a backend descriptor extension: a new
    `PropertyCategory.ZOOM` (or an extension of the `axis` enum to cover
    beam-angle sliders) plus `degMin`/`degMax` populated from the fixture
    annotations, so `dmxToDegrees` can be reused for beam angle the way
    Session 5 set it up for pan/tilt. None of the registered fixtures
    currently expose a structured beam-angle slider — only `acceptsBeamAngle`
    + the static `patch.beamAngleDeg` field. When a moving-head with real
    zoom (e.g. Martin Mac Aura, Robe Pointe) gets registered, that's the
    natural moment to land the descriptor + frontend wiring together.
  - **`castShadow` capping not landed.** No mesh in `Stage3D` currently
    casts a shadow (no light is set up to receive them either) so this is
    a no-op until shadow casting is added. If/when that lands, restrict
    `castShadow` to the head + cone meshes only and turn shadows off on
    the floor pool / lens / yoke base.
  - **Geometry instancing for truss segments not landed.** Worth doing
    when a single rigging carries multiple truss segments (currently each
    `RiggingDto` renders as one box). The right shape is probably a
    `<TrussInstanced>` wrapper that takes `(rig, segments)` and renders a
    drei `<Instances>` block. Skip until a multi-segment rigging design
    is added.
  - **Optimistic update is "fire and forget" — no rollback.** If the PUT
    fails (network down, validation rejection) the cached list keeps the
    dragged values until the next refetch. Acceptable for the home-show
    use case but a `.catch()` could `dispatch(invalidateTags(...))` to
    force a refetch and snap back. Left as-is.
  - **Rig-mounted patch optimistic update doesn't compose rotated rig
    frames.** Nulling `worldPosition*` makes `worldPositionFor` fall back
    to its translate-only stub (Session 1 carry-over). For a rigging with
    yaw/pitch the dragged patch will sit in the wrong place visually
    until the backend's recomposed `worldPosition*` arrives ~50 ms later.
    The clean fix is to compose using the rig pose locally in
    `worldPositionFor`; that fix has been deferred since Session 1 and
    still belongs there.
  - **Length-aware rig-axis clamp not added.** Dragging a rig-mounted
    patch projects to the rig's local X axis (Session 6) but doesn't
    clamp `stageX` to ±lengthM/2. Cosmetic — patches can sit "off the end
    of the bar" if the user drags far enough — but the visual stays at
    the projected point. Wire up `Math.max(-len/2, Math.min(len/2, x))`
    inside `patchPlacementFromWorld` if it becomes annoying.
  - **`useLocalStorage<T>(key, default)` consolidation still pending**
    (Session 5/6 carry-over). Same six hand-rolled siblings; no new
    callers added this session.
  - **Colour-source dispatch unification** (Session 5 carry-over). Same
    duplication between `StageMarker.tsx` (2D) and `FixtureModel.tsx`
    (3D); not touched here.
- _Surprises / decisions_:
  - **Backend `lengthM` skipped a migration function.** New nullable
    columns are added by `SchemaUtils.createMissingTablesAndColumns` on
    both PG and SQLite (State.kt:614), so the historical
    `migrate*V*()` pattern is only for column drops / type changes /
    data backfills. `lengthM` is purely additive.
  - **Length validation chose 0 < x ≤ 100 m**, asymmetric vs the existing
    `checkStageCoord` (±500 m). Length must be positive (a 0 m bar is
    nonsense) and 100 m is generous for any real venue. Implementation
    inlined into `validateRiggingPose` rather than added to
    `RouteHelpers.kt` since it's the only call site.
  - **`store.dispatch` over `useDispatch`.** The plain `useDispatch`
    returns `Dispatch<UnknownAction>` which can't dispatch RTK Query
    thunks like `updateQueryData`. The repo doesn't export an
    `AppDispatch` type, so introducing a typed hook would add three
    cross-cutting changes to fix one local need. Using the singleton
    `store` directly is the smaller change.
  - **Optimistic write nulls `worldPosition*`** rather than re-composing
    the new world position locally. Re-composing requires knowing the
    backend's exact composition rules (rig yaw/pitch/roll order, sign
    conventions); nulling lets `worldPositionFor` fall through to the
    `stage*` triple which the user just dragged. Hides the fact that
    the helper's rig-frame composition stub is still translate-only —
    correct enough for the dominant axis-aligned-rig case.
  - **Pan-fine combined as `coarse + fine/256`** (not the more obvious
    `(coarse << 8) | fine` 16-bit integer). The slider's min/max stay in
    coarse units (typically 0–255) so `dmxToDegrees`' `(dmx - min) / span`
    formula already works on the fractional value. Flipping to a 16-bit
    integer would require re-scaling slider min/max to 0–65535 across
    the codebase.
  - **`raycast={NO_RAYCAST}` not `raycast={undefined}`.** R3F treats
    undefined as "use Three.js default raycast", which IS active.
    Assigning a no-op function is the documented opt-out.
  - **Fixture click gated on edit mode.** Without edit mode, selecting
    a fixture had no visible effect (the gizmo only attaches when
    editing). Removing the listener entirely tightens the raycast hot
    path further and matches the regions/riggings handling that was
    already conditional on edit mode.
  - **Backend `./gradlew build` fails with a Gradle 9 strict-mode
    `:launcher:distZip → :launcher:shadowJar` implicit-dependency
    warning** — pre-existing, unrelated to this session. Verified the
    Kotlin changes via `./gradlew compileKotlin --rerun-tasks` and the
    affected route tests via
    `./gradlew test --tests "uk.me.cormack.lighting7.routes.RiggingsRoutesTest"`
    (passes). The launcher packaging issue is for a separate cleanup.

---

## Cross-cutting verification

- `npm run type-check` and `npm run build` clean at end of every session
  (CLAUDE.md gates these as the pre-commit equivalent).
- Backend: ensure a project has at least one rigging, one stage region, and
  one rig-mounted patch + one free patch for end-to-end testing.
- Manual smoke: start `lighting-react` dev server, point at running
  `lighting7` backend on `:8413`, run a Kotlin scene script that varies
  pan/tilt/RGB/dimmer — confirm 3D scene reflects in real time, no
  per-frame React re-renders, and 2D toggle still works.
