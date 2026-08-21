# Cues Engineering Documentation

This document describes the Cue system — named states built as an ordered stack of **Look layers** plus the cue's own local values and ad-hoc effects. See `lighting-composition-model.md` §"Looks and layers" for how the stack is composed.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md). Cues contribute
> at **Layer 4** (property assignments) and own the ad-hoc effects they
> spawn at **Layer 3**. Cue-owned effects receive a derived priority so composition across
> restarts and reapplies is deterministic. The `stomp` flag (see below) removes other cues'
> ad-hoc effects when a stomping cue applies and overlaps their targets.

## Overview

A **Cue** is a named, project-scoped entity that captures a complete lighting look:
- **Colour palette** (list of extended colour strings) — isolated per-cue, not shared with the global palette
- **Layers** — an ordered list of Looks to apply, each with its own targets, property mask, blend
  mode and amount. Looks are read fresh at apply time, so editing a Look moves every cue layering
  it. Later layers override earlier ones for the same fixture and property, whatever the attribute;
  the cue's own local values win over every layer.
- **Ad-hoc effects** — manually applied effects belonging to no Look, stored as full inline effect definitions.
- **`updateGlobalPalette`** — boolean flag; when true, applying the cue also sets the global palette (affecting ad-hoc effects and the PalettePanel).

Key behaviours:
- **Multiple concurrent cues**: Applying a cue adds it alongside other running cues (not replace). Each cue's effects resolve palette refs (P1, P2, etc.) against the cue's own palette.
- **Re-apply**: Applying a cue that is already running refreshes it (stops and re-starts its effects only).
- **Replace all**: Apply with `?replaceAll=true` to stop all other running cues first.
- **Stop**: Use the stop endpoint to remove a specific cue's effects without affecting others.
- Create from current live FX/palette state
- Duplicate within active project, copy to another project
- Deleting a Look is blocked if any cue layer references it (`LOOK_IN_USE`), unless forced

## Data Model

### Database Tables

```
cues
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
├── palette (JSON: List<String>)
└── update_global_palette (boolean, default false)

cue_layers
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── look_id (FK → looks)
├── targets (JSON: List<CueTargetDto>)
├── delay_ms (long, nullable — delayed application)
├── interval_ms (long, nullable — recurring application)
├── random_window_ms (long, nullable — randomisation for recurring)
└── sort_order (int, default 0)

cue_ad_hoc_effects
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── target_type (varchar 50)
├── target_key (varchar 255)
├── effect_type (varchar 255)
├── category (varchar 50)
├── property_name (varchar 255, nullable)
├── beat_division (double)
├── blend_mode (varchar 50)
├── distribution (varchar 50)
├── phase_offset (double, default 0.0)
├── element_mode (varchar 50, nullable)
├── element_filter (varchar 50, nullable)
├── step_timing (boolean, nullable)
├── parameters (JSON: Map<String, String>)
├── delay_ms (long, nullable — delayed application)
├── interval_ms (long, nullable — recurring application)
├── random_window_ms (long, nullable — randomisation for recurring)
└── sort_order (int, default 0)

cue_triggers (script hooks only)
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── trigger_type (enum: ACTIVATION, DEACTIVATION, DELAYED, RECURRING)
├── delay_ms (long, nullable — for DELAYED)
├── interval_ms (long, nullable — for RECURRING)
├── random_window_ms (long, nullable — randomisation window)
├── script_id (FK → scripts — required)
└── sort_order (int, default 0)
```

Cue names are free-form and **not** unique. The old `unique(project_id, name)` index predated
cue stacks; with a project owning many stacks, two stacks may legitimately both hold a
"Blackout". Cues are identified by `id` locally and by `uuid` across cloud sync. Cue *numbers*
remain unique per stack via the partial index `uq_cue_number_per_stack`.

### Key Design Decisions

- **Separate child tables with FKs** rather than JSON columns for layers and ad-hoc effects. This provides referential integrity (FK from a layer to its Look), simpler queries for usage counts — the delete guard is a plain indexed FK query where the palette era could only scan opaque value text — and proper normalization.
- **Targets stored as JSON** within `cue_layers` because each application has a small, variable-length list of targets that doesn't benefit from its own table.
- **Parameters stored as JSON**, matching `cue_ad_hoc_effects` and `look_effects`.

## REST API

All endpoints are scoped under `/api/rest/project/{projectId}/cues`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/{projectId}/cues` | List all cues for a project |
| POST | `/{projectId}/cues` | Create a new cue (current project only) |
| GET | `/{projectId}/cues/{cueId}` | Get cue details |
| PUT | `/{projectId}/cues/{cueId}` | Update a cue (current project only) |
| DELETE | `/{projectId}/cues/{cueId}` | Delete a cue (current project only) |
| POST | `/{projectId}/cues/{cueId}/copy` | Copy cue to another project |
| POST | `/{projectId}/cues/{cueId}/apply` | Apply cue (current project only). Query param: `replaceAll=true` to stop all other cues first. |
| POST | `/{projectId}/cues/{cueId}/stop` | Stop a running cue, removing its effects |
| POST | `/{projectId}/cues/from-state` | Create cue from current FX/palette state |

Writing the *programmer* into a cue lives outside this namespace, under
`/api/rest/programmer` — see [the composition model](lighting-composition-model.md#record--include--update):

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/programmer/record` | Write the programmer into a cue (`CREATE`/`MERGE`/`REMOVE`/`UPDATE_EXISTING`, optional I/P/C/B mask) |
| POST | `/programmer/include` | Load a cue's assignments + FX (or a palette's entries) into the programmer for editing |
| POST | `/programmer/record-palette` | Record the programmer into a named palette, masked by its type |
| POST | `/programmer/make-hard` | Replace the programmer's palette references with literals |
| POST | `/project/{id}/cues/{cueId}/make-hard` | Replace a stored cue's palette references with literals |
| POST | `/project/{id}/looks` … `/looks/{lookId}` | Look CRUD, banked by derived attribute family; delete guarded by `LOOK_IN_USE` |
| POST | `/programmer/update` | Write programmer edits back — to the include target, or to cues named from the Mode B checklist |

`POST /{projectId}/cues/{cueId}/snapshot-from-live` was removed in the programmer redesign's
Session 3. Capturing the stage is now `POST /programmer/record { source: "STAGE_SNAPSHOT",
mode: "UPDATE_EXISTING", cueId }` — the same capture, plus the programmer overlay the old route
could not see (it read Layer 4 only, so anything busked was silently dropped), and preserving
triggers and timed effects the old route left to chance.

### Apply Semantics

When a cue is applied:
1. **Remove previous effects for this cue** — only effects tagged with this cue's ID are removed (allows other cues to keep running). If `replaceAll=true`, all effects tagged with any `cueId` are removed instead, and their per-cue palettes are cleaned up.
2. **Set per-cue palette** — the cue's palette is stored in FxEngine's per-cue palette storage, isolated from the global palette. If `updateGlobalPalette` is true on the cue, the global palette is also updated.
3. The layer stack is cooked to one contributor per (fixture, property), then each layer's effects are spawned **in layer order**
4. Each ad-hoc effect is applied directly from its stored definition
5. All new FxInstances are tagged with the cue's ID
6. **Palette resolution**: Effects created from a cue use palette suppliers that resolve against the cue's palette first, falling back to the global palette if the cue has no palette set.

### Stop Semantics

When a cue is stopped via `POST /{cueId}/stop`:
1. All effects tagged with the cue's ID are removed from FxEngine
2. The cue's per-cue palette is cleaned up
3. Other running cues are unaffected

### Per-Cue Palette

FxEngine maintains per-cue palette storage (`ConcurrentHashMap<Int, CuePaletteEntry>`):
- `setCuePalette(cueId, colours)` — stores palette for a cue with a version counter
- `getCuePalette(cueId)` — returns the cue's palette (or null if not set)
- `getCuePaletteVersion(cueId)` — returns the cue's palette version (for cache invalidation)
- `removeCuePalette(cueId)` — removes the cue's palette (called on stop/re-apply)

Effects created from a cue use palette suppliers:
```kotlin
paletteSupplier = { engine.getCuePalette(cueId) ?: engine.getPalette() }
paletteVersionSupplier = { engine.getCuePaletteVersion(cueId) + engine.paletteVersion }
```

The version supplier sums both versions to ensure cache invalidation when either the cue palette or global palette (fallback case) changes.

### From-State Capture

The `from-state` endpoint and `programmer/record { source: "STAGE_SNAPSHOT" }` share
`captureCurrentState`, which captures the current FxEngine state:
- Effects with a non-null `presetId` (now a look id) are grouped by Look into layer entries, deduplicating targets
- Effects belonging to no Look (ad-hoc) are captured as full `CueAdHocEffectDto` entries with all fields

### Look Delete Blocking

When a Look is referenced by any cue layer (via FK), it cannot be deleted. The delete endpoint returns 409 Conflict with code `LOOK_IN_USE` and the names of referencing cues. `?force=true` deletes the layers with it.

## Cue Stack Membership

Cues can optionally belong to a **cue stack** for sequential playback. See `cue-stacks-engineering.md` for full details.

### Database Fields

The `cues` table has two additional columns:
- `cue_stack_id` (nullable FK → `cue_stacks`) — which stack the cue belongs to, or null for standalone
- `sort_order` (int, default 0) — position within the stack's ordered sequence

### API Changes

`CueDetails` includes:
- `cueStackId: Int?` — null for standalone cues
- `cueStackName: String?` — resolved from the stack entity
- `sortOrder: Int` — position within stack (0 for standalone)

`NewCue` accepts optional:
- `cueStackId: Int?` — assign cue to a stack on creation
- `sortOrder: Int?` — position within stack (appended to end if omitted)

### Standalone vs Stacked Cues

| Behaviour | Standalone Cue | Stacked Cue |
|-----------|---------------|-------------|
| Apply/Stop | Via `apply_cue`/`stop_cue` | Via stack activate/advance/deactivate |
| Palette | Per-cue palette in FxEngine | Stack palette (cue palette overrides) |
| Multiple concurrent | Yes (independent) | Yes (via multiple active stacks) |
| FxInstance tagging | `cueId` set, `cueStackId` null | Both `cueId` and `cueStackId` set |
| Crossfade | None (snap-cut on re-apply) | Intensity envelope (if cue has fadeDurationMs) |
| Auto-advance | N/A | Per-cue (autoAdvance + autoAdvanceDelayMs) |

## FxInstance Integration

The `FxInstance` class has a `cueId: Int?` field (alongside `presetId`, which now carries the look id). When a cue is applied, all created FxInstances are tagged with the cue's ID. This allows:
- The apply logic to identify and remove effects from a specific cue (not all cues)
- The stop endpoint to remove only the target cue's effects
- Active cue tracking in the frontend (derived from effect cueIds in WebSocket state)

### Priority

Cue-owned `FxInstance`s receive a derived `priority` at apply time via `cueDerivedPriority`
in [`projectCues.kt`](../src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt):
`stackId * 1_000_000 + sortOrder * 1_000 + 1`. Manual (uncued) effects keep priority `0`.

The engine iterates effects in priority-ascending order, id-ascending tie-break. Higher
priority composes later, so under non-OVERRIDE blend modes a later-positioned cue dominates
earlier cues on the same property. See
[lighting-composition-model.md](lighting-composition-model.md) §"Layer 3 — Effects". Note that a property held by the programmer (Layer 2) suppresses cue-owned effects on it entirely.

Because priority is derived from `sortOrder`, **reordering a stack changes precedence**. Priority
is stamped at apply time, so the reorder / `add-cue` / `sort-by-cue-number` handlers call
`FxEngine.repriorityCues(cueId → priority)` after writing sort orders. That restamps both layers a
cue can own — `FxInstance.priority` and the Layer 4 `Assignment.priority` rows — so cues already on
stage compose in the new order without needing to be re-applied. Crossfade weights are left
untouched, and cues whose derived priority didn't change are skipped, which makes the ordinary
single-live-cue reorder a no-op.

### Stomp

`DaoCues.stomp: Boolean` (default false) — when a stomping cue applies, the engine calls
`FxEngine.stompForCue(stompingCueId, overlap)` to remove ad-hoc effects owned by *other*
cues that target the overlap set. Phase 0 derives the overlap from the stomping cue's own
ad-hoc effect targets; Phase 1 will switch to Layer 4 property assignments once they land.
Manual effects and effects owned by the stomping cue itself are never stomped. See
[lighting-composition-model.md](lighting-composition-model.md) §"Stomp".

## Active Cue Tracking

Active cues are derived from `FxInstance.cueId` — there is no separate "active cue" registry. The `cueId` field is included in:
- `FxInstanceState` (FxEngine state flow)
- `FxEffectState` (WebSocket broadcast to frontend)
- `EffectDto` and `GroupEffectDto` (REST API responses)
- `get_current_state` AI tool output

The frontend derives active cue IDs from the real-time FxState WebSocket stream using the `useActiveCueIds()` hook (no additional WebSocket message needed).

## WebSocket Notifications

When cues are created, updated, or deleted, a `cueListChanged` message is broadcast to all connected WebSocket clients:

```json
{"type": "cueListChanged"}
```

The frontend subscribes to this message and invalidates the `CueList` RTK Query tag, triggering a re-fetch of the cue list.

The `fxState` WebSocket message now includes `cueId` on each effect in `activeEffects`, enabling the frontend to track which cues are running.

## Frontend Integration

### API Layer
- `src/api/cuesApi.ts` — Type definitions for Cue, CueInput, StopCueResponse, etc.
- `src/api/cuesWsApi.ts` — WebSocket subscription for `cueListChanged`
- `src/api/lightingApi.ts` — Registers `cues` WS API
- `src/api/fxApi.ts` — `FxEffectState` includes `cueId`

### State Management
- `src/store/cues.ts` — RTK Query endpoints with `CueList` tag, `stopCue` mutation, `useActiveCueIds()` hook
- `src/store/restApi.ts` — `CueList` added to tag types

### UI Components
- `src/routes/Cues.tsx` — Main route with list, create, edit, delete, apply, stop, replace-all, copy-palette-to-global, duplicate, copy. Active cues show coloured left border and a stop button.
- `src/components/cues/CopyCueDialog.tsx` — Copy to another project
- `src/components/cues/CueForm.tsx` — Create/edit with palette, presets, ad-hoc effects, and `updateGlobalPalette` toggle

### Navigation
- Cues nav item in `ProjectSwitcher.tsx` (visible for all projects)
- `QuickNavCard` in `ProjectOverview.tsx` (current project only)
- Routes in `App.tsx`: `/projects/:projectId/cues` and `/cues` redirect

## Lux AI Integration

Three tools for the AI assistant:
- `create_cue` — Create a named cue with a positional colour list, Look layers, ad-hoc effects, and optional `updateGlobalPalette` flag
- `apply_cue` — Apply a saved cue by ID. Optional `replaceAll` parameter to stop all other running cues first.
- `stop_cue` — Stop a running cue by ID, removing all its effects. Other running cues are unaffected.

The `get_current_state` tool includes `cueId` on active effects and `cues` in its default include set, returning cue names and counts for the current project.

The system prompt describes:
- Multiple concurrent cues with isolated palettes
- The `updateGlobalPalette` flag behaviour
- Active effects display includes `cueId` for each effect
- The distinction between global palette (ad-hoc effects) and per-cue palettes

## Assignment values: the third form

A `cue_property_assignments.value` (and its `look_rows` twin, which accepts literals only) holds one of:

1. a **literal** in the canonical `CueAssignmentResolver.PropertyValue.serialize()` grammar — `"200"`,
   `"#rrggbb[;wN;aN;uvN]"`, `"pan,tilt"`;
2. a **positional palette ref** — `"P1"`, `"P2"`, `"P*"` — indexing the ordered colour list scoped
   global → stack → cue (`PaletteCascade`), colour-only;
3. a **named-palette reference** — `"ref:{paletteUuid}"` — resolved per fixture against the
   `Palette` entity.

Form 3 is the one that survives an edit: changing the palette moves every live look that references
it. See `docs/lighting-composition-model.md` §"Named palettes (references)" for resolution,
republish and hardening semantics, and why the `ref:` check must precede the literal parser.

The reference stores the palette's **uuid** rather than its int id because int primary keys never
appear in the sync export and are re-minted on import, while `ExportUuidRemapper` rewrites uuids
across the whole export — including ones embedded in an opaque `value` string.
