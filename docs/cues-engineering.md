# Cues Engineering Documentation

This document describes the Cue system — named snapshots that bundle a colour palette with FX preset applications and ad-hoc effects.

## Overview

A **Cue** is a named, project-scoped entity that captures a complete lighting look:
- **Colour palette** (list of extended colour strings) — isolated per-cue, not shared with the global palette
- **Preset applications** — which FX presets to apply to which targets (fixtures/groups). Presets are read fresh at apply time, so edits to presets are always reflected.
- **Ad-hoc effects** — manually applied effects not from a preset, stored as full inline effect definitions.
- **`updateGlobalPalette`** — boolean flag; when true, applying the cue also sets the global palette (affecting ad-hoc effects and the PalettePanel).

Key behaviours:
- **Multiple concurrent cues**: Applying a cue adds it alongside other running cues (not replace). Each cue's effects resolve palette refs (P1, P2, etc.) against the cue's own palette.
- **Re-apply**: Applying a cue that is already running refreshes it (stops and re-starts its effects only).
- **Replace all**: Apply with `?replaceAll=true` to stop all other running cues first.
- **Stop**: Use the stop endpoint to remove a specific cue's effects without affecting others.
- Create from current live FX/palette state
- Duplicate within active project, copy to another project
- Deleting a preset is blocked if any cue references it

## Data Model

### Database Tables

```
cues
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
├── palette (JSON: List<String>)
├── update_global_palette (boolean, default false)
└── unique(project_id, name)

cue_preset_applications
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── preset_id (FK → fx_presets)
└── targets (JSON: List<CueTargetDto>)

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
└── parameters (JSON: Map<String, String>)
```

### Key Design Decisions

- **Separate child tables with FKs** rather than JSON columns for preset applications and ad-hoc effects. This provides referential integrity (FK from preset applications to fx_presets), simpler queries for cue usage counts, and proper normalization.
- **Targets stored as JSON** within `cue_preset_applications` because each application has a small, variable-length list of targets that doesn't benefit from its own table.
- **Parameters stored as JSON** matching the pattern used by `DaoFxPresets.effects`.

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

### Apply Semantics

When a cue is applied:
1. **Remove previous effects for this cue** — only effects tagged with this cue's ID are removed (allows other cues to keep running). If `replaceAll=true`, all effects tagged with any `cueId` are removed instead, and their per-cue palettes are cleaned up.
2. **Set per-cue palette** — the cue's palette is stored in FxEngine's per-cue palette storage, isolated from the global palette. If `updateGlobalPalette` is true on the cue, the global palette is also updated.
3. Each preset application's preset is read fresh from DB and its effects are applied to the specified targets
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

The `from-state` endpoint captures the current FxEngine state:
- Effects with a non-null `presetId` are grouped by preset into `CuePresetApplicationDto` entries, deduplicating targets
- Effects without a `presetId` (ad-hoc) are captured as full `CueAdHocEffectDto` entries with all fields

### Preset Delete Blocking

When a preset is referenced by any cue's preset applications (via FK), the preset cannot be deleted. The delete endpoint returns 409 Conflict with the names of referencing cues.

## FxInstance Integration

The `FxInstance` class has a `cueId: Int?` field (alongside `presetId`). When a cue is applied, all created FxInstances are tagged with the cue's ID. This allows:
- The apply logic to identify and remove effects from a specific cue (not all cues)
- The stop endpoint to remove only the target cue's effects
- Active cue tracking in the frontend (derived from effect cueIds in WebSocket state)

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
- `create_cue` — Create a named cue with palette, preset applications, ad-hoc effects, and optional `updateGlobalPalette` flag
- `apply_cue` — Apply a saved cue by ID. Optional `replaceAll` parameter to stop all other running cues first.
- `stop_cue` — Stop a running cue by ID, removing all its effects. Other running cues are unaffected.

The `get_current_state` tool includes `cueId` on active effects and `cues` in its default include set, returning cue names and counts for the current project.

The system prompt describes:
- Multiple concurrent cues with isolated palettes
- The `updateGlobalPalette` flag behaviour
- Active effects display includes `cueId` for each effect
- The distinction between global palette (ad-hoc effects) and per-cue palettes
