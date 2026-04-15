# Cue Stacks Engineering Documentation

This document describes the Cue Stack system — ordered containers for sequential cue playback with palette cascading, looping, and per-cue auto-advance and intensity envelope crossfades.

## Overview

A **Cue Stack** is a named, project-scoped entity that groups cues into an ordered sequence for theatre-style playback:
- **Ordered cues** with `sortOrder` for sequential traversal
- **Stack-level palette** that persists across cue transitions (cue palettes override when set)
- **Looping** — wraps from last cue back to first (and vice versa)
- **Per-cue auto-advance** — each cue can individually enable timed progression to the next cue
- **Per-cue crossfade** — each cue can configure its own fade-in duration and easing curve
- **Multiple active stacks** — several stacks can be active simultaneously

Key behaviours:
- A cue can belong to at most one stack (`cue_stack_id` FK, nullable)
- Cues not in a stack are "standalone" and behave as before
- Activating a stack applies the first (or specified) cue's effects
- Advancing steps through cues in sort order
- Deactivating removes all effects tagged with the stack's ID
- Stack palette cascading: cue palette replaces stack palette when set; stack palette persists when a cue has no palette

## Data Model

### Database Tables

```
cue_stacks
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
├── palette (JSON: List<String>)
├── loop (boolean, default false)
└── unique(project_id, name)

cues (modified)
├── ... existing columns ...
├── cue_stack_id (nullable FK → cue_stacks)
├── sort_order (int, default 0)
├── auto_advance (boolean, default false)
├── auto_advance_delay_ms (long, nullable)
├── fade_duration_ms (long, nullable)
├── fade_curve (varchar 50, default "LINEAR")
├── cue_number (varchar 20, nullable — free-form display label)
├── notes (text, nullable — script reference annotation)
└── cue_type (varchar 20, default "STANDARD" — STANDARD or MARKER)

Partial unique index: (cue_stack_id, cue_number) WHERE cue_number IS NOT NULL AND cue_type = 'STANDARD'
```

### Cue Types

- **STANDARD** — Normal cue that can be activated. Participates in advance/go-to.
- **MARKER** — Inert section divider. Invisible to `advance` and `go-to` (returns HTTP 400). Not moved by `sort-by-cue-number`.

### Cue Number Classification

| Class | Rule | Examples | Behaviour |
|-------|------|----------|-----------|
| **Participating** | First char is a digit (0–9) | "1", "1.5", "14A" | Sorted by natural sort |
| **Pinned** | First char is non-digit | "intro", "verse" | Never moved by sort actions |
| **Unnumbered** | `cue_number` is null | — | Appended at end in current order |

### Key Design Decisions

- **Nullable FK** on `cues.cue_stack_id` preserves backward compatibility — standalone cues have `null`
- **Sort order** as integer allows easy reordering without renumbering (gaps are fine)
- **Per-cue fade settings** allow different transition timing for each cue in a stack; `fade_duration_ms = null` means snap-cut
- **`cue_number` is a display label only** — `sort_order` remains the authoritative playback order
- **MARKER cues are invisible to advance** — `advance` and `go-to` only target `STANDARD` cues
- **Per-cue auto-advance** allows some cues to auto-advance while others wait for manual progression
- **EasingCurve enum** (LINEAR, SINE_IN_OUT, CUBIC_IN_OUT, etc.) stored as string for extensibility

## Runtime: CueStackManager

`CueStackManager` (`fx/CueStackManager.kt`) manages in-memory state for active stacks. It holds a reference to `FxEngine` but does not own effects — it delegates to FxEngine for effect lifecycle.

### Per-Stack State

```kotlin
data class ActiveStackState(
    val stackId: Int,
    var activeCueId: Int,
    var autoAdvanceJob: Job?,
    var crossfadeJob: Job?,
)
```

Stored in a `ConcurrentHashMap<Int, ActiveStackState>`.

### Key Methods

| Method | Description |
|--------|-------------|
| `activateCueInStack(state, stackId, cueId, scope)` | Activate a cue within a stack (handles crossfade, palette, auto-advance) |
| `advanceStack(state, stackId, direction, scope)` | Advance forward/backward respecting loop setting |
| `goToCue(state, stackId, cueId, scope)` | Jump to a specific cue |
| `deactivateStack(stackId)` | Remove all effects, cancel timers |
| `getActiveCueId(stackId)` | Query active cue (or null) |
| `getActiveStackIds()` | All active stack IDs |
| `isStackActive(stackId)` | Check if active |

### Activate Flow

1. Cancel any in-progress crossfade and auto-advance for this stack
2. Snapshot outgoing effects (for crossfade) — effects where `cueStackId == stackId`
3. If crossfading: leave outgoing effects in place; if snap-cut: remove them
4. Merge cue palette into stack palette (cue palette replaces, or keep stack palette if cue has none)
5. Apply cue's effects (presets + ad-hoc) tagged with both `cueId` and `cueStackId`
6. If crossfading: start new effects at `intensityMultiplier = 0.0`, launch crossfade coroutine
7. If this cue has auto-advance configured: start delay timer

### Palette Resolution for Stack Cue Effects

```kotlin
paletteSupplier = { engine.getStackPalette(stackId) ?: engine.getPalette() }
paletteVersionSupplier = { engine.getStackPaletteVersion(stackId) + engine.paletteVersion }
```

Falls back to global palette if no stack palette is set.

## Crossfade (Option B — Intensity Envelope)

The crossfade system uses per-effect `intensityMultiplier` (0.0–1.0) to smoothly transition between outgoing and incoming cue effects.

### FxInstance Addition

```kotlin
@Volatile var intensityMultiplier: Double = 1.0
```

### FxOutput.scaled()

The `FxOutput` sealed interface has a `scaled(multiplier: Double)` method:
- **Slider**: scales value toward 0
- **Colour**: scales RGB/W/A/UV toward black
- **Position**: no scaling (snap — no meaningful position fade)

Applied in `FxEngine` at all 4 effect output sites:
1. `processFixtureEffect` — direct fixture
2. `processMultiElementEffect` — per-element distribution
3. `processGroupEffect` — group member direct
4. `processGroupFlatElementEffect` — flat element distribution

### Crossfade Coroutine

```kotlin
private suspend fun runCrossfade(outgoingIds, incomingIds, durationMs, easingCurve) {
    val startTime = System.currentTimeMillis()
    while (true) {
        val progress = (elapsed / durationMs).coerceIn(0.0, 1.0)
        val eased = easingCurve.apply(progress)
        // Outgoing: 1→0, Incoming: 0→1
        for (id in outgoingIds) engine.getEffect(id)?.intensityMultiplier = 1.0 - eased
        for (id in incomingIds) engine.getEffect(id)?.intensityMultiplier = eased
        if (progress >= 1.0) break
        delay(16) // ~60fps
    }
    // Remove outgoing, ensure incoming at 1.0
}
```

### Easing Curves

`EasingCurve` enum: LINEAR, SINE_IN_OUT, CUBIC_IN_OUT, EASE_IN, EASE_OUT, EASE_IN_OUT. Stored as string on each cue, parsed at runtime.

## REST API

All endpoints under `/api/rest/project/{projectId}/cue-stacks`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all stacks with ordered cues + active cue info |
| POST | `/` | Create stack |
| GET | `/{stackId}` | Get stack details |
| PUT | `/{stackId}` | Update stack settings |
| DELETE | `/{stackId}` | Delete stack (query param `?keepCues=true` default) |
| POST | `/{stackId}/reorder` | Reorder cues: body `{ cueIds: [3, 1, 5] }` |
| POST | `/{stackId}/add-cue` | Add cue to stack: body `{ cueId, sortOrder?, insertByNumber? }` |
| POST | `/{stackId}/remove-cue` | Remove cue from stack (becomes standalone): body `{ cueId }` |
| POST | `/{stackId}/activate` | Activate stack (first STANDARD cue), optional `{ cueId }` |
| POST | `/{stackId}/deactivate` | Deactivate stack |
| POST | `/{stackId}/advance` | Advance STANDARD cues only: body `{ direction: "FORWARD"\|"BACKWARD" }` |
| POST | `/{stackId}/go-to` | Go to specific cue: body `{ cueId }` — HTTP 400 if MARKER |
| POST | `/{stackId}/sort-by-cue-number` | Reorder by natural sort of cue_number |

### `add-cue` with `insertByNumber`

When `insertByNumber: true`, the cue is inserted at its natural sort position among participating cues (digit-first `cue_number`). Returns 400 if the cue has no digit-starting `cue_number`.

### `sort-by-cue-number`

Partitions STANDARD cues into three groups (participating, pinned, unnumbered), natural-sorts participating cues, re-slots them into their collective `sort_order` positions, and appends unnumbered cues. MARKERs and pinned cues are not moved.

Response: `{ updatedCues: [...], pinnedCount, nullNumberCount }`. HTTP 400 if no participating cues.

### DTOs

- `NewCueStack` — name, palette, loop
- `CueStackDetails` — full stack with ordered cues, activeCueId, canEdit, canDelete
- `CueStackCueEntry` — id, name, sortOrder, paletteSize, presetCount, adHocEffectCount, autoAdvance, autoAdvanceDelayMs, fadeDurationMs, fadeCurve, cueNumber, notes, cueType
- `CueStackActivateResponse` — stackId, cueId, cueName, effectCount
- `CueStackDeactivateResponse` — stackId, removedCount
- `SortByNumberResponse` — updatedCues, pinnedCount, nullNumberCount

## WebSocket

### Messages

```json
{"type": "cueStackListChanged"}
```

Broadcast on stack CRUD operations. Frontend subscribes and invalidates `CueStackList` RTK Query tag.

### FxState Integration

The `fxState` WebSocket message includes `cueStackId` on each effect in `activeEffects`:

```json
{
  "type": "fxState",
  "activeEffects": [
    { "id": 1, "effectType": "SineWave", "targetKey": "front-wash", "cueId": 10, "cueStackId": 1, ... }
  ]
}
```

## Frontend Integration

### API Layer
- `src/api/cueStacksApi.ts` — Type definitions for CueStack, CueStackInput, CueStackCueEntry, etc.
- `src/api/cueStacksWsApi.ts` — WebSocket subscription for `cueStackListChanged`
- `src/api/lightingApi.ts` — Registers `cueStacks` WS API
- `src/api/fxApi.ts` — `FxEffectState` includes `cueStackId`
- `src/api/cuesApi.ts` — `Cue` interface includes `cueStackId`, `cueStackName`, `sortOrder`

### State Management
- `src/store/cueStacks.ts` — RTK Query endpoints with `CueStackList` tag, all CRUD + control mutations
- `src/store/cues.ts` — `useActiveCueStackIds()` hook derives active stack IDs from FxState
- `src/store/restApi.ts` — `CueStackList` added to tag types

## Lux AI Integration

Five tools:
- `create_cue_stack` — Create with name, palette, loop
- `activate_cue_stack` — Activate (optionally at specific cue)
- `deactivate_cue_stack` — Deactivate
- `advance_cue_stack` — Advance forward/backward
- `add_cue_to_stack` — Move/add a cue into a stack

The `get_current_state` tool includes `cue_stacks` in its default include set, returning stack names, cue counts, and active cue info. Active effects include `cueStackId`. Auto-advance and crossfade are configured per-cue (not per-stack) via the cue editor UI or `create_cue` tool.

The system prompt describes cue stack concepts and workflow.
