# Patch with Stage — Implementation Plan

Source design: Claude Design handoff bundle (`Lighting Project-handoff.zip`),
copied into the repo at
[`patch-with-stage-design/`](patch-with-stage-design/INDEX.md) — the INDEX
points at the right files for each phase. The bundle contains three desktop
variations of patch additions and three variations of a global "Stage" toggle.
We are implementing one of each, per user direction:

- **Patch panel additions → Variation A · "Inline stage map"**
  ([design source: `PvVariationA` in `patch-desktop.jsx`](patch-with-stage-design/patch-desktop.jsx)).
- **Global stage toggle → Variation B · "Slide-down panel"**
  ([design source: `StagePanel` + `mode="panel"` in `stage-toggle.jsx`](patch-with-stage-design/stage-toggle.jsx)).
- **Modification:** clicking a fixture marker in the slide-down panel must open
  our existing fixture sheet (`FixtureDetailModal` from the fixture view), not
  the inline `StageFixtureBar` strip the design uses.

When implementing, treat the design files as the look-and-feel spec
(dimensions, colours, layout rhythm, marker visuals). Translate the shared
CSS variables in [`styles.css`](patch-with-stage-design/styles.css) (e.g.
`--accent`, `--text-faint`, `--border-strong`) to our Tailwind v4 tokens by
*role*, not by literal value — our dark theme is OKLch-based and lives in
`lighting-react/src/index.css`.

## Touched repos

- Backend: `/Users/chris/Development/Personal/lighting7` (Kotlin / Ktor).
- Frontend: `/Users/chris/Development/Personal/lighting-react` (React + Vite +
  Tailwind v4 + RTK Query).

The backend is the source of truth for the new patch fields; the frontend
consumes them via the existing REST + WebSocket flow.

---

## What we're adding (data, end-to-end)

Per-fixture patch fields (all nullable, set in the patch fixture sheet). The
design's reference shape lives in
[`patch-data.jsx`](patch-with-stage-design/patch-data.jsx) — note their field
names (`x`, `y`, `pos`, `beam`, `gel`); ours are spelled out below.

| Field             | Type            | Range / format                  | Notes |
| ----------------- | --------------- | ------------------------------- | ----- |
| `stageX`          | Double?         | 0.0–100.0 (% of stage width)    | Used by the inline mini-stage and the global stage panel. |
| `stageY`          | Double?         | 0.0–100.0 (% of stage depth)    | Y=0 is upstage, Y=100 is downstage (matches design). |
| `riggingPosition` | String? (≤ 50)  | free text, e.g. "LX1", "ADV 2"  | Plain label; presets derived from the distinct values used in the project. |
| `beamAngleDeg`    | Int?            | 2–120                           | Surfaced in UI only for fixtures whose type is "Generic Dimmer"; column exists on every patch so users can hand-edit later. |
| `gelCode`         | String? (≤ 20)  | e.g. "L201", "R26"              | Resolved against a static Lee + Rosco library shipped with the frontend. |

**No new tables.** Rigging position presets are the distinct `riggingPosition`
values across the project's patches, plus a built-in fallback list. The gel
library is a static frontend asset (matches `PATCH_DATA.gels` in the design).
Beam presets (Spot/Narrow/Medium/Wide/Flood) are likewise a static frontend
asset.

**"Live" data on the stage map** (intensity %, current colour) comes from the
existing channel-state / property WebSocket stream — we do **not** add new
backend state for it. The design's `deriveLive` is pseudo-data we'll replace
with the real values at render time.

---

## Phase 1 — Backend (lighting7)

### 1.0 Fixture-type capability flags
Two new boolean capabilities on the fixture type so the frontend can decide
whether to render the beam-angle and gel UI in the patch sheet, without
matching on type name strings:

- `acceptsBeamAngle: Boolean` — true for fixture types with a configurable
  beam (Generic Dimmer, also any open-faced PAR / fresnel we model later).
- `acceptsGel: Boolean` — true for fixture types that take a physical gel
  (Generic Dimmer, conventional fixtures generally; false for LEDs and
  movers, which use built-in colour mixing).

These are independent — a fixture can have one without the other.

Implementation: add the two flags to `FixtureType` metadata
(`fixture/FixtureType.kt` or wherever the `@FixtureType` annotation /
descriptor lives — see `FixtureTypeRegistry`) and surface them in the
fixture-types REST response (`GET /fixture/types`). Default both to `false`;
flip them to `true` on the Generic Dimmer type definition for now. Future
fixture types opt in as appropriate.

The patch sheet (Phase 2) reads these flags via the existing
`useFixtureTypesQuery()` lookup and conditionally renders the Beam / Gel
sections.

### 1.1 Schema additions
File: `src/main/kotlin/uk/me/cormack/lighting7/models/fixturePatches.kt`.

Add five nullable columns to the `fixture_patches` table and corresponding
`var` properties on `DaoFixturePatch`:

- `stageX: double?`
- `stageY: double?`
- `riggingPosition: varchar(50)?`
- `beamAngleDeg: integer?`
- `gelCode: varchar(20)?`

Migration: `SchemaUtils.createMissingTablesAndColumns()` already runs on boot
(`State.kt` ~line 327) and will pick these up — no hand-rolled migration
needed.

### 1.2 Loader pass-through
File: `src/main/kotlin/uk/me/cormack/lighting7/show/DbFixtureLoader.kt`.

Extend the `PatchData` transport class (line ~34) with the five new fields and
read them out in the same transaction. Loader does **not** need to act on them
— the runtime `Fixture` doesn't care about `stageX/Y` etc., they're patch-only
metadata. The fields are surfaced to clients via REST/WebSocket only.

### 1.3 REST DTO + endpoints
File: `src/main/kotlin/uk/me/cormack/lighting7/routes/projectPatches.kt`.

Update:
- `FixturePatchDto` — add the five fields.
- `GET /{projectId}/patches` and `GET /{projectId}/patches/{patchId}` — return
  them.
- `PUT /{projectId}/patches/{patchId}` — accept partial updates for any of the
  five (nullable means "clear"). Ensure validation:
  - `stageX`, `stageY`: `null` or in `[0.0, 100.0]`.
  - `riggingPosition`: trim, uppercase, max 50 chars.
  - `beamAngleDeg`: `null` or in `[2, 120]`.
  - `gelCode`: trim, max 20 chars; no library lookup server-side (the library
    is a frontend concern).
- `POST /{projectId}/patches` — also accept the five fields optionally so
  scripted/imported patches can be created in one shot. Default `null`.

### 1.4 WebSocket
No new message types. The existing `patchListChanged` broadcast already fires
on patch CRUD, and the REST list query is the source of truth — frontend
re-fetches and re-renders.

### 1.5 Tests
`./gradlew test` is the pre-commit gate for this repo. Add a unit test that
round-trips a patch with all five fields set/cleared via the patch routes.

---

## Phase 2 — Frontend, patch list (lighting-react)

### 2.1 RTK Query types
File: `src/store/patches.ts` (and the corresponding API types in
`src/api/patchApi.ts`).

Extend `FixturePatch`, `CreatePatchRequest`, `UpdatePatchRequest` with the
five new fields. Match backend names exactly (`stageX`, `stageY`,
`riggingPosition`, `beamAngleDeg`, `gelCode`).

### 2.2 Patch list — Position column
File: `src/routes/Patches.tsx`.

- `PatchRow` (line ~250) → add `riggingPosition: string | null`.
- `buildPatchRows()` → pull it through.
- `PatchTable()` → insert a `Position` column between `Key` and `Name` (matches
  design header order in `patch-bits.jsx` `PvPatchList`). Render as a `Badge`
  with `variant="outline"` (echoes design's `.pos` chip — small uppercase
  pill). Empty cell when null. Responsive: `hidden md:table-cell` so it drops
  on narrow viewports first.

### 2.3 Patch fixture sheet — new sections
File: `src/components/patches/EditPatchSheet.tsx`. The design's section
breakdown comes from
[`patch-desktop.jsx#PvVariationA`](patch-with-stage-design/patch-desktop.jsx)
+ the form bits in
[`patch-bits.jsx`](patch-with-stage-design/patch-bits.jsx)
(`PvPositionInput`, `PvBeamAngle`, `PvGelPicker`, `PvSection`). Visual rules
are in [`patch.css`](patch-with-stage-design/patch.css) — search for
`.pv-stage`, `.pv-beam`, `.pv-gel`, `.pv-section`. Sections, in order:

1. **Identity** (existing — name, key, group). No change.
2. **Address** (existing — universe, start channel). No change.
3. **Stage** (new) — collapsible section, default open.
   - Toolbar row: "Click or drag to place" hint, `Center` and `Unplace`
     buttons.
   - Inline stage canvas: 100% width × ~220 px tall, dark bg, dashed
     UPSTAGE/DOWNSTAGE labels and crosshair axes (matches `.pv-stage` CSS).
   - Renders **all other fixtures with coords as ghost dots** (10% opacity,
     no labels) for context.
   - Renders **the current fixture** as a solid accent-coloured dot with a
     small label. Dot is draggable (mouse down on canvas → set coords; mouse
     move while down → update). Bounds clamp to 2–98 / 4–94 (matches design).
   - Bottom-right readout: `x N · y N` (one decimal hidden, integer rounded).
4. **Position** (new) — rigging label.
   - Free-text input (uppercased on input), placeholder `e.g. LX1, ADV 2,
     FOH`.
   - Chip row of recent presets: union of (a) distinct `riggingPosition`
     values currently in use across the project's patches, (b) a built-in
     fallback list `["FOH", "LX1", "LX2", "LX3", "ADV 1", "ADV 2", "USR",
     "DSL", "DSR", "USL", "MID", "BOOM L", "BOOM R"]`. Click chip → set
     value.
5. **Beam & Gel** (new, conditional) — Beam Angle is rendered only when the
   fixture type's `acceptsBeamAngle` capability is `true`; Gel only when
   `acceptsGel` is `true`. Both can render independently. When at least one
   is true, group them under a section whose header reflects what's
   present (e.g. "Beam & Gel", "Beam", or "Gel").
   - **Beam Angle:** number input (2–120), `°` suffix, plus preset buttons
     (Spot 14, Narrow 26, Medium 36, Wide 50, Flood 70). Visual cone preview
     using a CSS conic gradient with `--half: <angle/2>deg` (matches the
     design's `.pv-beam-cone`).
   - **Gel:** clickable swatch + code/name display. Click opens a popover
     with: search input, brand tabs (All / Lee / Rosco), and a vertical list
     of swatches. "Open white" (clear) is the first row. Library data lives
     in a new file `src/data/gels.ts` mirroring the design's
     `PATCH_DATA.gels` (Lee + Rosco set with `code`, `name`, `color` hex).

### 2.4 Save flow
- Use the existing `useUpdatePatchMutation()` — payload now includes the new
  fields.
- Stage drag should **debounce** the mutation (e.g., 300 ms after the user
  stops dragging) so we don't spam the backend. Local state is the source of
  truth during a drag; on `mouseup` we flush.

### 2.5 Add sheet
File: `src/components/patches/AddFixtureSheet.tsx`. Out of scope for phase 2 —
new patches are created without coords; user adds them in the Edit sheet.

### 2.6 No new design primitives
All UI uses existing Tailwind tokens + `Button`, `Input`, `Label`, `Badge`,
`Sheet`, `Popover`. Match the existing patch-sheet rhythm: section header
with icon + title + optional meta + chevron, body with `space-y-3` rows.

---

## Phase 3 — Frontend, stage slide-down panel (lighting-react)

The global toggle pattern already exists — `FixtureOverviewToggle` /
`FixtureOverviewPanel` is the template to copy from.

### 3.1 Hook
New file: `src/hooks/useStageOverview.ts`. Mirror `useFixtureOverview` —
boolean visibility, persisted via localStorage (same key prefix the existing
toggles use), keyboard shortcut optional.

### 3.2 Topbar toggle
New file: `src/components/StageOverviewToggle.tsx`. Mirrors
`FixtureOverviewToggle.tsx`. Uses the design's stage icon (small rectangle
with a centre dot — see `StageIcon` in `stage-toggle.jsx`); we'll inline the
SVG, not pull a `lucide-react` icon, since none matches.

Mount it in the topbar in `src/Layout.tsx` (line ~177–181), to the **left of**
`FixtureOverviewToggle` so the order from-left-to-right is: Stage → Fixtures
→ Effects → Cues → AI → Theme. (The design puts Stage between Table and FX —
our topbar layout is different but Stage being the first content-overview
toggle reads cleanly.)

### 3.3 Slide-down panel
New file: `src/components/StageOverviewPanel.tsx`.

Layout (from
[`stage-toggle.jsx#StagePanel`](patch-with-stage-design/stage-toggle.jsx) +
[`stage-toggle.css`](patch-with-stage-design/stage-toggle.css) — search
`.stage-panel`):

- Sits below the topbar, above the page content. Uses the same grid-rows
  animation pattern as `FixtureOverviewPanel` (height 0 → ~420 px on
  desktop, ~50 vh on mobile).
- **Header strip:** "Stage" title with a status dot, fixture count, ghost
  buttons for `Reset view` and `Pop out` (the latter can be a no-op or
  deferred — it's purely visual in the design), close `×` button.
- **Body:** the stage canvas — same grid backdrop and axes as the inline
  patch-sheet stage, but full panel width. Markers rendered for every
  patched fixture that has `stageX`/`stageY` set.
- **Group filter chips** (optional, design has them on Variation C; we'll
  include them here for parity with the patch fixture sheet's sections — All
  + each group with `count > 0`, click toggles a single filter, dimmed
  markers stay visible at low opacity). If this expands scope, drop to a
  follow-up.

### 3.4 Live markers
Marker visuals come from
[`stage-view.jsx#MarkerLive`](patch-with-stage-design/stage-view.jsx) +
[`stage-view.css`](patch-with-stage-design/stage-view.css) (search `.sv-mk-live`,
`.sv-cone`, `.sv-glow`). Each marker is a `<button>` with:

- Position: `style={{ left: stageX + '%', top: stageY + '%' }}`.
- A coloured glow whose colour is the fixture's current rgb, sized by current
  intensity %.
- A faint beam cone (radial gradient) for fixtures with a beam (par,
  moving, dimmer) when intensity > 5%. Beam width derived from
  `beamAngleDeg ?? 30`.
- Label: fixture name + `<sub>` of `riggingPosition` if set.

Live colour + intensity come from the same channel-state / property selectors
the FixtureContent component uses — we'll factor a small selector hook
(`useFixtureLive(fixtureKey) → { color, intensity }`) so both the patch sheet
context dots and the stage panel markers share it. Current value: read from
`usePropertyValues` (already used by `FixtureContent`).

### 3.5 Marker click → existing fixture sheet
**Per user direction**, do **not** render the design's `StageFixtureBar`.
Instead:

- Layout already mounts `FixtureDetailModal` globally, controlled by a
  `selectedFixture` state held in `Layout.tsx`.
- The stage panel's marker `onClick` calls the same setter the fixture
  overview panel uses (`onFixtureSelect(fixture.key)`), so the existing
  fixture sheet slides in from the right exactly as it does in the fixture
  view.
- The stage panel itself stays open behind the sheet; closing the sheet does
  not close the panel.

### 3.6 Empty state
If no patched fixtures have `stageX`/`stageY` set, the panel body shows: "No
fixtures placed yet. Open a patch and drag the dot on the stage map to place
it." with a button that navigates to `/projects/:id/patches`.

### 3.7 Mobile
The design has a separate full-screen mobile overlay
(`MobileStageToggleHost`). Out of scope for phase 3; on mobile (`< md`) the
toggle button is hidden initially. We can add a mobile variant in a
follow-up.

---

## Sequencing / risk

1. Phase 1 ships a backend that is **forward-compatible** with the old
   frontend (all fields nullable, all routes backward-compatible) — safe to
   merge first.
2. Phase 2 only depends on phase 1 being deployed. Editing patches works the
   same as before; the new sections are additive.
3. Phase 3 only depends on phase 1 (it reads the new fields). It does **not**
   depend on phase 2 — but in practice without phase 2 there's no UI to set
   coords, so the panel is empty until users have edited patches. Ship in
   order 1 → 2 → 3.

## Out of scope (potential follow-ups)

- Mobile full-screen stage overlay (design's `MobileStageToggleHost`).
- Beam preview cone in the patch sheet's mini-stage (design only shows the
  cone in the global panel, not the patch sheet — match that).
- Per-project rigging-position library (currently derived from in-use
  values; promote to a dedicated table only if users start asking for empty
  presets).
- Z (height) coordinate (Variation B has it; we picked Variation A so we
  drop it).
- Editing positions by drag inside the global stage panel (design only
  drags inside the patch fixture sheet; the global panel is read-only +
  selection).
- Persisting the panel-open state across reloads (mirror the existing
  fixture-overview toggle behaviour — comes "for free" via the hook).
- "Pop out" stage panel into its own window (design has the icon, function
  not specified).

## Decisions (confirmed 2026-04-26)

1. **Coordinate semantics.** `[0..100]` percent of stage width / depth, origin
   top-left, Y=100 = downstage. Baked into the schema (`stageX`, `stageY` as
   `Double?`).
2. **Beam / gel surfacing.** Two independent boolean capability flags on the
   fixture type — `acceptsBeamAngle` and `acceptsGel`. Default false; Generic
   Dimmer turns both on. Phase 2's patch sheet renders Beam / Gel sections
   conditionally on these flags. See §1.0 above.
3. **Fixture sheet from the stage panel.** Marker click opens
   `FixtureDetailModal` — the same runtime sheet you get from clicking a
   compact fixture card in the fixture overview panel. Not `EditPatchSheet`.
