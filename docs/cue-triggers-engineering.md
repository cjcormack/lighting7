# Cue Timing & Script Hooks Engineering Documentation

This document describes how cue effects can be timed (delayed, recurring) and how script hooks automate actions at cue lifecycle events.

## Overview

### Timed Effects

Preset applications and ad-hoc effects can optionally have timing fields that control *when* they are applied relative to cue activation:

| Timing | Behaviour | Configuration |
|--------|-----------|---------------|
| **Immediate** (default) | Applied when the cue activates | `delayMs = null, intervalMs = null` |
| **Delayed** | Applied after a delay from activation | `delayMs` |
| **Recurring** | Applied repeatedly at an interval | `intervalMs`, `randomWindowMs` (optional) |

These timing fields live directly on the `cue_preset_applications` and `cue_ad_hoc_effects` tables — no separate trigger entity needed for effect timing.

### Script Hooks (Triggers)

Script hooks are lifecycle callbacks that run FX_APPLICATION scripts at cue events:

| Type | When it fires | Configuration |
|------|--------------|---------------|
| `ACTIVATION` | When the cue is activated — immediately, or after `delayMs`, or repeatedly every `intervalMs` | `delayMs`, `intervalMs`, `randomWindowMs` (all optional) |
| `DEACTIVATION` | When the cue is deactivated (cleanup) | — |

There are **two** types (`TriggerType` in `models/cueTriggers.kt`). A delayed or recurring hook is
an `ACTIVATION` hook with timing: `CueTriggerManager` launches a timed coroutine when either timing
field is positive and runs the script at once otherwise. `DELAYED` and `RECURRING` were types of
their own once; a write still naming either is normalised to `ACTIVATION` with its timing
(`createCueChildren`), so an older client keeps working, but neither is ever stored or answered.

Script hooks are stored in the `cue_triggers` table and always reference a script. The DEACTIVATION type only applies to script hooks (effects are inherently removed on deactivation).

**A script hook is not a cue event.** A cue's **events** (stage-view plan session 9,
`cues-engineering.md` §"Cue events") fire a one-shot trigger — a confetti cannon's tube — at an
offset after GO, only while the desk is armed and only on GO into the cue; a hook runs a script on
every activation whatever the desk's arm. Reach for an event to fire a cannon: a script cannot set
a trigger (it is not a property), and the arm exists so that nothing but a deliberate event can.

## Data Model

### Timing on `cue_preset_applications`

> **Historical.** This table is no longer in `ALL_TABLES` — it went with the presets/palettes
> model. Its columns are shown as they were, which is why they still read `delay_ms` while every
> live table below now uses a `Duration` column named `delay`.

```
cue_preset_applications
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── preset_id (FK → fx_presets)
├── targets (JSON: List<CueTargetDto>)
├── delay_ms (nullable Long — fires after this delay)
├── interval_ms (nullable Long — recurring interval)
├── random_window_ms (nullable Long — randomisation range for recurring)
└── sort_order (Int)
```

### Timing on `cue_ad_hoc_effects`

Same timing columns added to `cue_ad_hoc_effects`:
- `delay`, `interval`, `random_window` (each a `Duration`, nanoseconds on disk), `sort_order`

### Script Hooks: `cue_triggers`

```
cue_triggers
├── id (auto-increment PK)
├── cue_id (FK → cues)
├── trigger_type (enum: ACTIVATION, DEACTIVATION)
├── delay (nullable Duration — an ACTIVATION hook fires this long after activation; nanoseconds on disk)
├── interval (nullable Duration — an ACTIVATION hook re-fires at this interval)
├── random_window (nullable Duration — randomisation range)
├── script_id (FK → scripts — required, always a script)
└── sort_order (Int)
```

Note: The trigger table no longer has `action_type`, `preset_id`, or `targets` columns. Preset application timing moved to the preset application table itself.

## CueTriggerManager

**File:** `fx/CueTriggerManager.kt`

Manages the runtime lifecycle of both timed effects and script hooks using coroutines.

### Runtime State

- `activeTriggerJobs: ConcurrentHashMap<Int, MutableList<Job>>` — running coroutines per cueId (both timed effects and script hooks)
- `triggerEffectIds: ConcurrentHashMap<Int, MutableList<Long>>` — effect IDs created by timed/script actions per cueId
- `cueToStack: ConcurrentHashMap<Int, Int>` — cueId → stackId mapping for stack-level cleanup
- `deactivationTriggers: ConcurrentHashMap<Int, List<CueTriggerDto>>` — stored DEACTIVATION script hooks

### Activation Flow

After the cue's immediate effects have been applied:

1. **Timed effects** (`activateTimedEffectsForCue`): For each preset/ad-hoc effect with `delayMs` or `intervalMs`, launch a coroutine that applies the effect at the configured timing. Timed preset fires also contribute their property assignments to the cue layer (Layer 4) at fire time (`FxEngine.appendCueAssignments`), retracting the prior fire's rows (`FxEngine.removeCueAssignmentSubset`) on each recurring tick so the cue's assignment list does not accumulate duplicates.
2. **Script hooks** (`activateTriggersForCue`): For each script trigger, execute by type:
   - **ACTIVATION** with no timing: run the script immediately
   - **ACTIVATION** with `delayMs`: launch `delay(delayMs) → runScript()`
   - **ACTIVATION** with `intervalMs`: launch `while(isActive) { delay(randomised) → runScript() }`
   - **DEACTIVATION**: stored for later

### Deactivation Flow

1. Fire DEACTIVATION script hooks (synchronous)
2. Cancel all running coroutine jobs (both timed effects and script hooks)
3. Remove all effect IDs created by timed/script actions from FxEngine
4. Clean up all maps

### Crossfade Interaction

Timed effects fire at full intensity (they don't participate in the crossfade envelope). When a stack crossfade begins:
1. Old timed effect jobs and script hooks are cancelled
2. Old trigger-created effects are removed during crossfade
3. New cue's timed effects and hooks start after the new cue's immediate effects are applied

### Recurring Randomisation

```kotlin
val offset = Random.nextLong(-window, window + 1)
return (base + offset).coerceAtLeast(100L)  // 100ms safety floor
```

Each iteration computes a fresh random interval for organic variation.

## CueContext Auto-Tagging

**File:** `fx/FxEngine.kt`

When running FX_APPLICATION scripts (from script hooks), `CueTriggerManager` sets `fxEngine.currentCueContext` before execution. The `FxEngine.addEffect()` method auto-tags effects:

```kotlin
currentCueContext?.let { ctx ->
    if (effect.cueId == null) effect.cueId = ctx.cueId
    if (effect.cueStackId == null) effect.cueStackId = ctx.cueStackId
}
```

## REST API

Timing and script hooks are part of the cue entity — no separate endpoints.

### Create/Update Cue

```json
POST /api/rest/{projectId}/cues
PUT  /api/rest/{projectId}/cues/{cueId}

{
  "name": "Fluorescent Flicker Scene",
  "presetApplications": [
    {
      "presetId": 1,
      "targets": [{"type": "group", "key": "front-wash"}]
    },
    {
      "presetId": 3,
      "targets": [{"type": "fixture", "key": "uv-strip-1"}],
      "intervalMs": 40000,
      "randomWindowMs": 5000
    }
  ],
  "triggers": [
    {
      "triggerType": "DEACTIVATION",
      "scriptId": 7
    }
  ]
}
```

### Get Cue (response)

The second preset application has timing; triggers only contain scripts:

```json
{
  "id": 1,
  "name": "Fluorescent Flicker Scene",
  "presetApplications": [
    {
      "presetId": 1,
      "presetName": "Warm Wash",
      "targets": [{"type": "group", "key": "front-wash"}]
    },
    {
      "presetId": 3,
      "presetName": "Fluorescent Flicker",
      "targets": [{"type": "fixture", "key": "uv-strip-1"}],
      "intervalMs": 40000,
      "randomWindowMs": 5000,
      "sortOrder": 1
    }
  ],
  "triggers": [
    {
      "triggerType": "DEACTIVATION",
      "scriptId": 7,
      "scriptName": "Cleanup UV"
    }
  ]
}
```

## Example: Fluorescent Flicker

**Before (imperative):**
- A GENERAL script that sets UV values with `delay()` calls between state changes
- Manual timing logic to trigger the flicker at the right interval

**After (declarative):**
- A STATEFUL + WALL_CLOCK FX definition (`FluorescentFlicker.fx.kts`) defining the flicker pattern
- A cue with a recurring preset application (~40s, +-5s randomisation) that applies the flicker FX preset to the UV fixture
- Optionally, a DEACTIVATION script hook to clean up external state

## File Reference

| File | Purpose |
|------|---------|
| `models/cues.kt` | Timing columns on preset apps and ad-hoc effects |
| `models/cueTriggers.kt` | Script hook table, DAO, DTOs |
| `fx/CueTriggerManager.kt` | Runtime lifecycle for timed effects + script hooks |
| `fx/FxEngine.kt` | `currentCueContext` for auto-tagging |
| `fx/CueStackManager.kt` | Immediate/timed split during cue transitions |
| `routes/projectCues.kt` | CRUD, apply logic, immediate/timed split |
| `state/State.kt` | `cueTriggerManager` lazy property, migration |
