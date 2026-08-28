# Cue Stacks Engineering Documentation

This document describes the Cue Stack system — ordered containers for sequential cue playback with looping and per-cue auto-advance and intensity envelope crossfades.

## Overview

A **Cue Stack** is a named, project-scoped entity that groups cues into an ordered sequence for theatre-style playback:
- **Ordered cues** with `sortOrder` for sequential traversal
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

## Data Model

### Database Tables

```
cue_stacks
├── id (auto-increment PK)
├── name (varchar 255)
├── project_id (FK → projects)
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
├── cue_number_auto (boolean, default false — cue_number was derived from position)
├── notes (text, nullable — script reference annotation)
└── cue_type (varchar 20, default "STANDARD" — STANDARD or MARKER)

Partial unique index: (cue_stack_id, cue_number) WHERE cue_number IS NOT NULL AND cue_type = 'STANDARD'
```

### Cue Types

- **STANDARD** — Normal cue that can be activated. Participates in advance/go-to.
- **MARKER** — Inert section divider. Invisible to `advance` and `go-to` (returns HTTP 400). Not moved by `sort-by-cue-number`.

### Cue Number Model

See [`cueNumbering.kt`](../src/main/kotlin/uk/me/cormack/lighting7/routes/cueNumbering.kt); the
frontend mirrors it in `src/lib/cueNumber.ts` (lighting-react).

A number is parsed as **prefix + decimal run + letter suffix** — `S1-3.1` → `("S1-", [3,1], "")`,
`Pre-show 2` → `("Pre-show ", [2], "")`, `14A` → `("", [14], "A")`. The prefix is the **group key**,
and numbers are only ever compared against others in the same group. So
`["Pre-show 1", "Pre-show 2", "T2-1", "S-1", "S-2"]` is in order despite the groups not being
alphabetical, while `[…, "S-2", "S-1"]` is not.

| Class | Rule | Examples | Behaviour |
|-------|------|----------|-----------|
| **Numbered** | parses to a decimal run | "1", "1.5", "14A", "S1-3.1" | Sorted within its prefix group |
| **Unparseable** | no decimal run at all | "intro", "A" | Singleton group — never moved |
| **Unnumbered** | `cue_number` is null | — | Given an auto number (below) |

This replaced an earlier "first character must be a digit" participation rule, which pinned every
prefixed number — a stack numbered `S1-1`, `S1-2`, … had *nothing* eligible to sort.

### Auto Numbering

A cue with no explicit number is given one derived from its position, flagged with
`cue_number_auto`. `renumberAutoCues(stack)` recomputes them and is called from every handler that
changes a stack's membership or order: cue create / delete / copy, `reorder`, `add-cue`,
`sort-by-cue-number`, and any PATCH/PUT that changes a cue number.

Each maximal run of auto cues is labelled from the nearest preceding explicit number:

- **Increment** its trailing decimal — `S1-3` → `S1-4`, `S1-5`.
- **Decimal-insert** beneath it instead — `S1-3.1`, `S1-3.2` — when incrementing would land on a
  number used elsewhere in the stack or run past the next explicit cue in the same group.

A run with nothing explicit before it borrows the following cue's prefix and counts *up to* it: two
cues before `S1-3` are `S1-1` and `S1-2`, and a lone cue before `S1-1` is `S1-0` — zero itself is
free, so there is no need to decimal-insert. Only when there isn't room for the whole run below the
boundary (two cues before `S1-1`, say) does it fall back to `S1-0.1`, `S1-0.2`. With nothing either
side the run is starting the series, so a fresh stack numbers `1`, `2`, `3`.

Typing an explicit number releases that value from any auto sibling holding it (`releaseAutoNumber`)
so the edit can't be rejected by `uq_cue_number_per_stack`; clearing the field hands the cue back to
the auto scheme. `renumberAutoCues` writes in two passes with a flush between, since two auto
numbers swapping places would otherwise be a transient duplicate.

Labels that won't fit `varchar(20)` are skipped — the cue stays blank rather than carrying a
truncated number.

**Backfill (removed).** `renumberAutoCues` only runs off a mutation, so cues that predated the
feature would have stayed blank until something touched their stack. A `backfillAutoCueNumbers()`
walked every stack once at startup to fix that. It went with the rest of the migrations on
2026-08-24, once the only database in existence had no blank auto numbers left — see
[InstallBootstrap.kt](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt).
Nothing backfills now, so a cue that somehow acquires a blank auto number keeps it until its stack
is next mutated.

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

Two halves of running a stack live beside it rather than in it, because neither needs the firing
machinery:

| Class | Owns |
|-------|------|
| `fx/CueCrossfadeDriver.kt` | The Layer 4 fade envelope: one in-flight fade per stack, its tick loop, and the outgoing cue it must drop if cancelled |
| `fx/CueRunStateTracker.kt` | Standby arming, the definition of "next", `CueRunState` and its broadcast. Reached as `cueStackManager.runState` |

The manager keeps the firing path and the per-stack live-cue / auto-advance bookkeeping; the
tracker reads that bookkeeping through a `LiveStacks` snapshot rather than the mutable entry, so
one broadcast frame can't describe two moments.

### Per-Stack State

```kotlin
data class ActiveStackState(
    val stackId: Int,
    var activeCueId: Int,
    var autoAdvanceJob: Job?,
    // Copied from the live cue at activation, so the run-state broadcast can describe
    // the stack without going back to the DB.
    var fadeDurationMs: Long?,
    // The cue's own flag. What goes on the wire is derived from `autoAdvanceJob` instead —
    // "will it advance", not "is it configured to".
    var autoAdvance: Boolean,
    var autoAdvanceDelayMs: Long?,
    // Null unless a real crossfade is under way — a cue that snapped (no outgoing cue to fade
    // out of) has no elapsed time to report, and reporting one would have every client animate
    // a fade that never happened.
    var fadeStartedAtMs: Long?,
)
```

Stored in a `ConcurrentHashMap<Int, ActiveStackState>`.

### Standby — the armed "next"

Lives in `CueRunStateTracker`, reached as `cueStackManager.runState`.

`standbyCueIds: ConcurrentHashMap<Int, Int>` (`stackId → cue`) holds the cue an operator has
armed as the next GO. It sits **beside** `activeStacks`, not inside `ActiveStackState`, for two
reasons: a cue can be armed before the stack is running (pre-show), and `activateCueInStack`
replaces the whole `ActiveStackState` entry.

Transient runtime state — never persisted, never synced (see `docs/sync-engineering.md`'s
decision tree).

It used to live in the browser. Each session computed "next" for itself, so a cue armed on a
tablet was invisible to the desk and the two disagreed about what GO would fire. There is now
one definition, `effectiveNextCueId`:

> the armed standby, when one is set and isn't already live — else the positional next
> STANDARD cue, honouring `loop`. Null at the end of a non-looping stack.

Everything reads it: `advanceStack` FORWARD **fires** it (so a client calls `advance`
unconditionally instead of choosing between `advance` and `go-to`), `activateAtFirstCue` starts a
stopped stack on it, `CueStackDetails.nextCueId` reports it, and the run-state broadcast carries
it. A GO consumes the standby; deactivating a stack clears it.

`orderedStandardCueIds` + `positionalCueId` are the shared primitives — `advanceStack` and
`effectiveNextCueId` cannot drift because they walk the same list by the same rules. The one
difference is at a non-looping boundary: `positionalCueId` returns null there, which GO reads as
"stay on the current cue" and "next" reads as "nothing on deck".

### Key Methods

| Method | Description |
|--------|-------------|
| `activateCueInStack(state, stackId, cueId, scope)` | Activate a cue within a stack (handles crossfade and auto-advance) |
| `advanceStack(state, stackId, direction, scope)` | Advance forward/backward respecting loop setting |
| `goToCue(state, stackId, cueId, scope)` | Jump to a specific cue |
| `deactivateStack(stackId)` | Remove all effects, cancel timers |
| `getActiveCueId(stackId)` | Query active cue (or null) |
| `getActiveStackIds()` | All active stack IDs |
| `isStackActive(stackId)` | Check if active |

On `cueStackManager.runState` (`CueRunStateTracker`):

| Method | Description |
|--------|-------------|
| `setStandby(state, stackId, cueId)` / `clearStandby(state, stackId)` | Arm / disarm the next GO. Rejects a MARKER and a cue from another stack — arming is a deferred GO |
| `getStandbyCueId(stackId)` | The explicitly armed cue, if any |
| `effectiveNextCueId(...)` | What the next GO fires. Two overloads: one taking the stack's already-loaded cue list (for the details DTO), one that queries |
| `runStateFor(state, stackId)` | The stack's run state, for the broadcast and the connect-time snapshot |
| `stacksWithRunState()` | Stacks that are live or hold an armed cue — what the connect snapshot walks |

### Activate Flow

1. Cancel any in-progress crossfade and auto-advance for this stack
2. Snapshot outgoing effects (for crossfade) — effects where `cueStackId == stackId`
3. If crossfading: leave outgoing effects in place; if snap-cut: remove them
4. Apply cue's effects (presets + ad-hoc) tagged with both `cueId` and `cueStackId`
5. If crossfading: start new effects at `intensityMultiplier = 0.0`, launch crossfade coroutine
6. If this cue has auto-advance configured: start delay timer

A step "merge cue palette into stack palette" used to sit at 4, and the stack carried a positional
colour list its cues inherited. That whole grammar is gone — an effect parameter names a colour
template (`tmpl:{uuid}`) whose answer does not depend on which stack is running — so a stack now
carries no colour state at all, and `removeEffectsForCueStackKeepPalette` folded back into
`removeEffectsForCueStack`.

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

Applied in `FxEngine` once, inside `calculateEffectOutput`, which the three effect output
sites all call into. Both the beat and wall-clock passes share those three, differing only in
the `PhaseSource` they hand them:
1. `processFixtureEffect` — direct fixture
2. `processGroupEffect` — group member direct
3. `processElementKeys` — per-element distribution, serving both the multi-element fixture
   expansion and a group's `FLAT`/`PER_FIXTURE` element lists (they became the same walk once
   the expansion started arriving pre-resolved — see `FxTargetExpansion`)

### Crossfade Coroutine

`CueCrossfadeDriver.runFade`, launched by `CueCrossfadeDriver.start`.

```kotlin
private suspend fun runFade(outgoingCueId, incomingCueId, durationMs, easingCurve) {
    val startTime = System.currentTimeMillis()
    while (true) {
        val progress = (elapsed / durationMs).coerceIn(0.0, 1.0)
        val eased = easingCurve.apply(progress)
        // Outgoing: 1→0, Incoming: 0→1 — one map, so the engine republishes once per tick
        fxEngine.cueLayer.updateFadeWeights(buildMap {
            outgoingCueId?.let { put(it, 1.0 - eased) }
            incomingCueId?.let { put(it, eased) }
        })
        if (progress >= 1.0) break
        delay(CROSSFADE_TICK_MS) // 16 ms, ~60fps
    }
    // Remove outgoing's Layer 4, pin incoming at 1.0
}
```

Cancelling mid-flight (a new cue activating, or the stack stopping) drops the outgoing cue's
assignments too — end-of-fade would have removed them, so without that they'd linger frozen at
whatever weight the fade had reached.

### Easing Curves

`EasingCurve` enum: LINEAR, SINE_IN_OUT, CUBIC_IN_OUT, EASE_IN, EASE_OUT, EASE_IN_OUT. Stored as string on each cue, parsed at runtime.

## REST API

All endpoints under `/api/rest/projects/{projectId}/cue-stacks`.

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
| POST | `/{stackId}/standby` | Arm the next GO: body `{ cueId }`; `{ cueId: null }` disarms. HTTP 400 for a MARKER or a cue from another stack |
| POST | `/{stackId}/preview` | Compose a cue without firing it: body `{ cueId? }` (null → the effective next). See below |
| POST | `/{stackId}/sort-by-cue-number` | Group-aware sort of cue_number |

### `add-cue` with `insertByNumber`

When `insertByNumber: true`, the cue is inserted at its sorted position **within its own prefix
group** — other groups say nothing about where it belongs. Returns 400 if the cue's number has no
decimal run to position it by. Cues at or after the insertion point shift down, MARKERs included.

### `sort-by-cue-number`

Sorts each prefix group's STANDARD cues among themselves and writes them back into the `sort_order`
slots that group already occupied, so groups keep their relative placement. MARKERs and unparseable
numbers never move. Auto numbers are recomputed afterwards from the new positions.

Response: `{ updatedCues: [...], pinnedCount, nullNumberCount }` — `pinnedCount` is the number of
unparseable numbers left in place. No longer returns 400 when nothing was eligible; a stack that
needs no sorting simply comes back unchanged.

Both this and `reorder` also call `FxEngine.repriorityCues`, since cue priority is derived from
`sort_order` — see [cues-engineering.md](cues-engineering.md) §Priority.

### DTOs

- `NewCueStack` — name, loop
- `CueStackDetails` — full stack with ordered cues, activeCueId, canEdit, canDelete
- `CueStackCueEntry` — id, name, sortOrder, presetCount, adHocEffectCount, autoAdvance, autoAdvanceDelayMs, fadeDurationMs, fadeCurve, cueNumber, cueNumberAuto, notes, cueType
- `CueStackActivateResponse` — stackId, cueId, cueName, effectCount
- `CueStackDeactivateResponse` — stackId, removedCount
- `SortByNumberResponse` — updatedCues, pinnedCount, nullNumberCount

## Preview compose

`POST /{stackId}/preview` answers "what would this cue look like?" — the channel values a cue
*would* produce, with nothing published. It backs the Next GO stage view (lighting-react
`docs/stage-vis-engineering.md` §"The fourth source: Next GO"): composing a cue in the browser
would otherwise mean reimplementing specificity, HTP/LTP, template resolution and move-in-dark
arming client-side.

`routes/cuePreview.kt`, and it is reuse end to end:

1. **Retained rows** — `CueAssignmentLayer.assignmentsExcludingStack(stackId)`: every published cue
   that isn't this stack's, because firing a cue replaces its own stack's contribution and
   nothing else. Cues published without a stack (a cue-edit live apply) survive a stack GO, so
   they are retained too. The filter lives in the engine so the rows and the `cueId → stackId`
   map are read under one lock. The rows carry their *stored* weight (always 1.0 — live
   crossfade progress lives in `cueFadeWeights`), so a cue caught mid-crossfade is previewed
   settled, which is what a preview wants.
2. **Incoming rows** — `buildCombinedCueLayerRows`, the same builder `republishCueLayer` uses,
   which is what makes the preview and the GO agree by construction rather than by inspection.
3. **Compose** — a *fresh* `CueAssignmentResolver`. `resolve` is a pure function of its rows, so
   this cannot disturb `layerResolver`'s live state (`CuePreviewRouteTest` asserts that).
4. **To channels** — `PropertyChannelWriter.resolve` per composed property, subnet 0 only,
   keyed so two properties backing one channel resolve last-write-wins as a transaction would.

Three deliberate limits, all worth knowing before building UI on it:

- **Layer 4 only.** Effects in the cue band have no static value to report, and timed preset
  applications don't contribute (matching `applyCue`). A cue whose look is carried by an effect
  previews as whatever its assignments say — possibly nothing.
- **Assertions only.** Channels no cue asserts are *absent* rather than reported as 0; the caller
  falls back to the live output the way the "Output + Programmer" vis source already overlays.
  Reporting 0 would black out every unaddressed fixture in the preview.
- **No programmer, no park.** A preview of playback, not of the stage.

## WebSocket

### Messages

```json
{"type": "cueStackListChanged"}
```

Broadcast on stack CRUD operations. Frontend subscribes and invalidates `CueStackList` RTK Query tag.

```json
{"type": "cueRunStateChanged", "projectId": 1, "stackId": 4, "activeCueId": 12,
 "nextCueId": 13, "nextIsArmed": false, "transition": true, "fadeDurationMs": 2000,
 "fadeElapsedMs": 0, "autoAdvance": false, "autoAdvanceDelayMs": null}
```

A stack's run state: what is live, what the next GO fires, and how far through a fade the desk
is. **One frame per transition, not a per-tick stream** — the client animates the fade locally
from `fadeElapsedMs` + `fadeDurationMs`, the way the session that pressed GO always did. The
crossfade itself still ticks at 60 fps inside `FxEngine`; that never goes on the wire.

Fired from `CueStackManager`, not from the routes, so *every* path reports: REST, the MIDI
surface's GO binding, a cue-edit live apply, and the auto-advance timer (which previously moved
the rig with no client ever being told). `setupBroadcastSubscriptions` also sends one frame per
stack in `stacksWithRunState()` on connect, so a session that opens mid-fade animates the
remainder instead of nothing. That snapshot is *read* synchronously while the listener is being
registered and only *sent* from a coroutine: read inside the coroutine it would describe whenever
the coroutine got scheduled, which can be after a GO the listener has already queued a frame for
— i.e. a `transition = false` frame carrying a newer cue than the transition frame beside it.

`fadeElapsedMs` is an elapsed duration, deliberately not a start timestamp: a tablet with a
skewed clock would otherwise animate a fade that is already over. Null means no fade is running
— which is also how a client tells a standby-only change (leave the animation alone) from a cue
transition.

`autoAdvance` reports whether the stack **will** roll forward — a live `autoAdvanceJob`, not
merely a cue configured for one. `pauseAutoAdvance` / `resumeAutoAdvance` publish a frame for
exactly this reason: a cue-edit Live session cancels the timer on a cue still flagged
`autoAdvance`, and since the client draws the countdown but no longer *drives* it (see below), a
config-shaped answer leaves every session with a bar completing into nothing.

### The client no longer drives auto-advance

lighting-react used to run its own auto-advance timer and call the server when it finished. With
the transition broadcast in place that would step the stack once per open session, so
`scheduleAutoAdvance` here is the only timer and the client's countdown is a display of it. Any
change to who owns that timer has to move both halves together.

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
- `create_cue_stack` — Create with name, loop
- `activate_cue_stack` — Activate (optionally at specific cue)
- `deactivate_cue_stack` — Deactivate
- `advance_cue_stack` — Advance forward/backward
- `add_cue_to_stack` — Move/add a cue into a stack

The `get_current_state` tool includes `cue_stacks` in its default include set, returning stack names, cue counts, and active cue info. Active effects include `cueStackId`. Auto-advance and crossfade are configured per-cue (not per-stack) via the cue editor UI or `create_cue` tool.

The system prompt describes cue stack concepts and workflow.
