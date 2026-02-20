# Cues Engineering Documentation

This document describes the Cue system — named snapshots that bundle a colour palette with FX preset applications and ad-hoc effects.

## Overview

A **Cue** is a named, project-scoped entity that captures a complete lighting look:
- **Colour palette** (list of extended colour strings)
- **Preset applications** — which FX presets to apply to which targets (fixtures/groups). Presets are read fresh at apply time, so edits to presets are always reflected.
- **Ad-hoc effects** — manually applied effects not from a preset, stored as full inline effect definitions.

Key behaviours:
- Apply a cue to replace the previously applied cue's effects and set the palette
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
| POST | `/{projectId}/cues/{cueId}/apply` | Apply cue (current project only) |
| POST | `/{projectId}/cues/from-state` | Create cue from current FX/palette state |

### Apply Semantics

When a cue is applied:
1. All effects tagged with any `cueId` are removed (clears previous cue)
2. The palette is set from the cue's palette
3. Each preset application's preset is read fresh from DB and its effects are applied to the specified targets
4. Each ad-hoc effect is applied directly from its stored definition
5. All new FxInstances are tagged with the cue's ID

### From-State Capture

The `from-state` endpoint captures the current FxEngine state:
- Effects with a non-null `presetId` are grouped by preset into `CuePresetApplicationDto` entries, deduplicating targets
- Effects without a `presetId` (ad-hoc) are captured as full `CueAdHocEffectDto` entries with all fields

### Preset Delete Blocking

When a preset is referenced by any cue's preset applications (via FK), the preset cannot be deleted. The delete endpoint returns 409 Conflict with the names of referencing cues.

## FxInstance Integration

The `FxInstance` class has a `cueId: Int?` field (alongside `presetId`). When a cue is applied, all created FxInstances are tagged with the cue's ID. This allows the apply logic to identify and remove all effects from a previously applied cue.

## WebSocket Notifications

When cues are created, updated, or deleted, a `cueListChanged` message is broadcast to all connected WebSocket clients:

```json
{"type": "cueListChanged"}
```

The frontend subscribes to this message and invalidates the `CueList` RTK Query tag, triggering a re-fetch of the cue list.

## Frontend Integration

### API Layer
- `src/api/cuesApi.ts` — Type definitions for Cue, CueInput, etc.
- `src/api/cuesWsApi.ts` — WebSocket subscription for `cueListChanged`
- `src/api/lightingApi.ts` — Registers `cues` WS API

### State Management
- `src/store/cues.ts` — RTK Query endpoints with `CueList` tag
- `src/store/restApi.ts` — `CueList` added to tag types

### UI Components
- `src/routes/Cues.tsx` — Main route with list, create, edit, delete, apply, from-state, duplicate, copy
- `src/components/cues/CopyCueDialog.tsx` — Copy to another project
- `src/components/cues/CueForm.tsx` — Create/edit name dialog

### Navigation
- Cues nav item in `ProjectSwitcher.tsx` (visible for all projects)
- `QuickNavCard` in `ProjectOverview.tsx` (current project only)
- Routes in `App.tsx`: `/projects/:projectId/cues` and `/cues` redirect

## Lux AI Integration

Two new tools for the AI assistant:
- `create_cue` — Create a named cue with palette, preset applications, and ad-hoc effects
- `apply_cue` — Apply a saved cue by ID

The `get_current_state` tool includes `cues` in its default include set, returning cue names and counts for the current project.

The system prompt lists existing cues with their ID, palette size, preset application count, and ad-hoc effect count.
