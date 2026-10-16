# Cue Stacks Engineering Documentation

This document describes the Cue Stack system — ordered containers for sequential cue playback with looping and per-cue auto-advance and intensity envelope crossfades.

## Overview

A **Cue Stack** is a named, project-scoped entity that groups cues into an ordered sequence for theatre-style playback:
- **An ordered list of entries** (`cue_stack_entries`) — each a cue's place in the stack, or a marker
- **Looping** — wraps from last cue back to first (and vice versa)
- **Per-cue auto-advance** — each cue can individually enable timed progression to the next cue
- **Per-cue crossfade** — each cue can configure its own fade-in duration and easing curve
- **Multiple active stacks** — several stacks can be active simultaneously

Key behaviours:
- Every cue has exactly one entry, so it is in exactly one stack (cue model plan P4, until its
  session 2; there are no standalone cues, and an archive from before stacks were mandatory imports
  its stack-less cues into an "Unsorted" stack)
- Activating a stack applies the first (or specified) cue's effects
- Advancing steps through the list's cue entries in order, markers skipped
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

cue_stack_entries (cue model plan session 1 — `models/cueStackEntries.kt`)
├── id (auto-increment PK)
├── stack_id (FK → cue_stacks)
├── cue_id (FK → cues, nullable — null exactly when kind is MARKER)
├── kind (varchar 20 — CUE or MARKER)
├── label (varchar 255, nullable — a marker's text)
├── ordinal (double, default 0.0 — the list's order; ties by id)
├── cue_number (varchar 20, nullable — free-form display label)
├── cue_number_auto (boolean, default false — cue_number was derived from position)
├── auto_advance (boolean, default false — the follow)
├── auto_advance_delay (Duration, nullable — nanoseconds on disk)
├── uuid
└── index(stack_id, ordinal), index(cue_id)

Partial unique index: uq_entry_number_per_stack (stack_id, cue_number)
                      WHERE cue_number IS NOT NULL AND kind = 'CUE'
```

The cue itself (`cues`, `cues-engineering.md` §"Data Model") keeps only what it is — name, fade,
notes, stomp, children; everything that is a matter of the list is its entry's. Every handler writes
the order through `writeEntryOrder`, which renormalises the ordinals to `1.0, 2.0, …` (session 3
adopts midpoint inserts). The wire still speaks of a **position** — `sortOrder` on `NewCue`,
`add-cue` and the DTOs is the entry's index in the list, markers counted.

#### The startup migration

The entries arrived by a one-off migration, `state/CueModelMigration.kt` (cue model plan D5), which
`State.initDatabase` runs **before** the schema is reconciled, while `cues` still has
`cue_stack_id`. It backs the database up with `VACUUM INTO '<db>.pre-cue-model-<utc>.db'`, then in
one transaction makes each cue an entry (its stack, its sort order renormalised per stack by
`(sort_order, id)`, its number, auto flag and follow), each MARKER cue a marker entry labelled with
its name and keeping its uuid (the cue and its children, pads and slots deleted), re-points every
prompt-book anchor to its cue's entry (dropping one whose cue is gone), rebuilds `cues` and
`prompt_book_anchors` from the model and drops `uq_cue_number_per_stack`. A converted database has
no `cue_stack_id`, so a second start does nothing. Its KDoc explains why it is the exception to
`InstallBootstrap.kt`'s "after the schema is created"; `CueModelMigrationTest` runs it over
`src/test/resources/db/pre-cue-model.sql`, the old shape frozen.

### Entry kinds

- **CUE** — places one cue. Participates in advance, go-to and standby.
- **MARKER** — an inert, labelled section divider with no cue behind it (cue model plan D12): made,
  renamed and removed through `/{stackId}/markers`, never through `/cues`. Invisible to `advance`;
  `go-to` and `standby` naming one are HTTP 400. Not moved by `sort-by-cue-number`. A row's kind
  reaches the wire as `cueType` (`STANDARD` | `MARKER`), the archive's vocabulary.

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

A cue entry with no explicit number is given one derived from its position, flagged with
`cue_number_auto`. `renumberAutoEntries(stack, before)` recomputes them and is called from every handler
that changes a stack's membership, order or numbers: cue create / delete / copy, `reorder`,
`add-cue` (which renumbers the stack the cue left as well as the one it joined), `sort-by-cue-number`,
the marker routes, Record's new cue, the AI's `build_cue_stack` (once, after it has placed every
cue), and any PATCH/PUT that changes a cue number. Marker entries take no number and are not an auto
cue's neighbours.

**An auto number stays put while its cue does** (venue-visit plan D11). Each handler takes
`cueEntryIds(stack)` *before* it changes the list and hands it to `renumberAutoEntries`; an auto cue
still at the same index keeps the number it holds (unless an explicit number has claimed it), and
only the cues that are new, moved, or shifted by an insert or a delete are labelled again. A number
edit moves nothing, so it passes the order as it stands and no other auto cue changes: in five auto
cues, cue 2 set to `4.5` reads 1, 4.5, 3, 4, 5, the frontend's out-of-order check sees the descent
and offers *Fix Order*, and *Fix Order* moves that cue rather than renumbering anything — the cues it
passed take numbers from where they land (1, 2, 3, 4.5, 5). Cue 3 set to `10` reads 1, 2, 10, 4, 5
and sorts to 1, 2, 3, 4, 10; cue 3 set to `1` takes cue 1's number, so cue 1 — released by
`releaseAutoNumber` — takes the highest free integer below its neighbour and the stack reads
0, 2, 1, 4, 5, sorting to 0, 1, 2, 4, 5. `CueNumberingTest` pins the three on the rule and
`CueNumberingRouteTest` through the routes; `EntryNumberingTest` runs them again with a marker in
the list (cue-model plan D14).

The cues that do need a label are labelled in maximal runs, each from the nearest preceding *fixed*
number — an explicit one, or an auto one being kept:

- **Increment** its trailing decimal — `S1-3` → `S1-4`, `S1-5`.
- **Decimal-insert** beneath it instead — `S1-3.1`, `S1-3.2` — when incrementing would land on a
  number used elsewhere in the stack or run past the next fixed cue in the same group.

**An explicit number that reads out of order against the auto numbers beside it in its group** —
the nearest one before it is not below it, or the nearest one after it is not above it — **neither
anchors nor bounds a run**: the run passes through it, counting its place. It is a renumber waiting
for *Fix Order*, and anchoring on it would relabel the cues around it the next time anything in the
stack changed, which is D11's relabelling one gesture later. Two explicit numbers out of order
against each other still anchor, as they always did.

A run with nothing fixed before it borrows the following cue's prefix and counts *up to* it: two
cues before `S1-3` are `S1-1` and `S1-2`, and a lone cue before `S1-1` is `S1-0` — zero itself is
free, so there is no need to decimal-insert. A block a number in it is taken from is tried lower
down before giving up (a lone cue before `2` whose `1` is taken is `0`). Only when there isn't room
for the whole run below the boundary (two cues before `S1-1`, say) does it fall back to `S1-0.1`,
`S1-0.2`. With nothing either side the run is starting the series, so a fresh stack numbers `1`,
`2`, `3`.

**Nothing renumbers a stored stack on upgrade or import.** Every number the derive-from-scratch rule
wrote is one the keep rule leaves alone — a stack's kept labels are its own — and the sync importer,
`copy_to_project` and a clone store `cue_number` and `cue_number_auto` as they arrive, calling no
renumber at all. A stack's auto numbers move only when an operator next changes that stack's list,
and then only for the cues that change moves.

Typing an explicit number releases that value from any auto sibling holding it (`releaseAutoNumber`)
so the edit can't be rejected by `uq_entry_number_per_stack`; clearing the field hands the cue back to
the auto scheme. `renumberAutoEntries` writes in two passes with a flush between, since two auto
numbers swapping places would otherwise be a transient duplicate.

Labels that won't fit `varchar(20)` are skipped — the cue stays blank rather than carrying a
truncated number.

**Backfill (removed).** The renumber only runs off a mutation, so cues that predated the
feature would have stayed blank until something touched their stack. A `backfillAutoCueNumbers()`
walked every stack once at startup to fix that. It went with the rest of the migrations on
2026-08-24, once the only database in existence had no blank auto numbers left — see
[InstallBootstrap.kt](../src/main/kotlin/uk/me/cormack/lighting7/state/InstallBootstrap.kt).
Nothing backfills now, so a cue that somehow acquires a blank auto number keeps it until its stack
is next mutated.

### Key Design Decisions

- **Every cue has one entry** (P4, for now) — moving a cue is `add-cue` to another stack, which moves
  its entry; deleting a stack deletes its entries and their cues (with their children, scenery and
  events). The caller always names the stack (P7) — nothing picks a cue's "home" stack
- **Ordinal** as a double, ties broken by id, so session 3's inserts can land between two entries
  without rewriting the list
- **Per-cue fade settings** allow different transition timing for each cue in a stack; `fade_duration = null` means snap-cut
- **`cue_number` is a display label only** — the entries' order remains the authoritative playback order
- **Markers are entries, not cues** — `advance`, `go-to` and standby only target cue entries
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
> cue entry, honouring `loop`. Null at the end of a non-looping stack.

Everything reads it: `advanceStack` FORWARD **fires** it (so a client calls `advance`
unconditionally instead of choosing between `advance` and `go-to`), `activateAtFirstCue` starts a
stopped stack on it, `CueStackDetails.nextCueId` reports it, and the run-state broadcast carries
it. A GO consumes the standby; deactivating a stack clears it.

`orderedStandardCueIds` (the stack's cue entries' cues, in order) + `positionalCueId` are the shared primitives — `advanceStack` and
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
| `setStandby(state, stackId, cueId)` / `clearStandby(state, stackId)` | Arm / disarm the next GO. Rejects a cue with no entry in that stack — arming is a deferred GO. The REST route takes the **entry** and refuses a marker before it gets here |
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
7. Publish the run state, then the scenery (`SceneryService.onCueLive`) — §"Scenery" below.
   `deactivateStack` likewise ends with `onStackStopped`
8. Then the cue's **events** (`EffectsService.onCueGo`) — §"Events" below — unless this is the
   BACKWARD arm of `advanceStack`. `deactivateStack` drops the desk's arm (`onStackStopped`)

A step "merge cue palette into stack palette" used to sit at 4, and the stack carried a positional
colour list its cues inherited. That whole grammar is gone — an effect parameter names a colour
palette (`tmpl:{uuid}`) whose answer does not depend on which stack is running — so a stack now
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
| DELETE | `/{stackId}` | Delete stack and its cues |
| POST | `/{stackId}/reorder` | Reorder the list: body `{ entryIds: [3, 1, 5] }`, markers included; an entry left out keeps its relative order after the named ones |
| POST | `/{stackId}/add-cue` | Add or move a cue to this stack: body `{ cueId, sortOrder?, insertByNumber? }` |
| POST | `/{stackId}/activate` | Activate stack (first cue entry), optional `{ cueId }` — still a cue id until session 2 |
| POST | `/{stackId}/deactivate` | Deactivate stack |
| POST | `/{stackId}/advance` | Advance through cue entries only (markers skipped): body `{ direction: "FORWARD"\|"BACKWARD" }` |
| POST | `/{stackId}/go-to` | Go to specific cue: body `{ entryId }` — HTTP 400 for a marker or another stack's entry |
| POST | `/{stackId}/standby` | Arm the next GO: body `{ entryId }`; `{ entryId: null }` disarms. HTTP 400 for a marker or another stack's entry |
| POST | `/{stackId}/markers` | Add a marker entry: body `{ label, sortOrder? }` (a position; appended if omitted). 201 with its row |
| PUT / DELETE | `/{stackId}/markers/{entryId}` | Rename (`{ label }`) or remove a marker; 404 for a cue's entry |
| POST | `/{stackId}/preview` | Compose a cue without firing it: body `{ cueId? }` (null → the effective next). See below |
| POST | `/{stackId}/sort-by-cue-number` | Group-aware sort of cue_number |

### `add-cue` with `insertByNumber`

When `insertByNumber: true`, the cue is inserted at its sorted position **within its own prefix
group** — other groups say nothing about where it belongs. Returns 400 if the cue's number has no
decimal run to position it by. Entries at or after the insertion point shift down, markers included.
The move takes the cue's one entry with it (number, auto flag, follow); an **explicit** number the
target stack already holds refuses the move (400) rather than tripping the unique index, an auto one
is dropped and the cue renumbered where it lands.

### `sort-by-cue-number`

Sorts each prefix group's cue entries among themselves and writes them back into the slots of the
list that group already occupied, so groups keep their relative placement. Markers and unparseable
numbers never move. Afterwards the cues the sort moved take auto numbers from where they landed and
the rest keep theirs (§"Auto Numbering", D11), so *Fix Order* moves a renumbered cue rather than
relabelling its neighbours.

Response: `{ updatedCues: [...], pinnedCount, nullNumberCount }` — `pinnedCount` is the number of
unparseable numbers left in place. No longer returns 400 when nothing was eligible; a stack that
needs no sorting simply comes back unchanged.

Both this and `reorder` also call `FxEngine.repriorityCues`, since cue priority is derived from the
entry's position — see [cues-engineering.md](cues-engineering.md) §Priority.

### DTOs

- `NewCueStack` — name, loop
- `CueStackDetails` — full stack with ordered cues, activeCueId, canEdit, canDelete
- `CueStackCueEntry` — one **row** of the list: `entryId` (what reorder, standby and go-to name), `id` (the cue's; null on a marker), name (a marker's label), sortOrder (its position), layerCount, adHocEffectCount, autoAdvance, autoAdvanceDelayMs, fadeDurationMs, fadeCurve, cueNumber, cueNumberAuto, notes, cueType, scenery (the cue's own scenery changes, always encoded — §"Scenery")
- `CueStackActivateResponse` — stackId, cueId, cueName, effectCount
- `CueStackDeactivateResponse` — stackId, removedCount
- `SortByNumberResponse` — updatedCues, pinnedCount, nullNumberCount

## Preview compose

`POST /{stackId}/preview` answers "what would this cue look like?" — the channel values a cue
*would* produce, with nothing published. It backs the Next GO stage view (lighting-react
`docs/stage-vis-engineering.md` §"The fourth source: Next GO"): composing a cue in the browser
would otherwise mean reimplementing specificity, HTP/LTP, palette resolution and move-in-dark
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

**Scenery is the exception to all three** (stage-view plan session 8): the response's `scenery` is
the whole stage's scenery as it would resolve with this cue live — its stack's set and its cues
tracked down to it, beside every other live stack and every live Look, programmer included (its
Looks and its own scenery, unless it is blind) — each
element with `from` (where it is drawn now) and the `durationMs` the GO would move it over. Unlike
`channels` it is whole: an element absent shows its base. It comes from the same resolver the GO
runs (`SceneryService.preview`), so a preview and the GO that follows cannot disagree.

## Scenery

A cue, a stack and a Look can each carry **scenery changes** — an element of the scene document and
the states it takes (`visible`, a drawn drape's `open`, a flown piece's `trimM`) — in
`cue_scenery`, `cue_stack_scenery` and `look_scenery` (`models/scenery.kt`; stage-view plan session 8,
D11–D13). Scenery sits **beside** the composition model, not in it: nothing here is a DMX channel,
Record captures only what the programmer **holds** (the scenery-programmer plan's D7, its session 3;
below), and a palette never carries it. Above the three owners sits the **programmer's own scenery** — what the operator's
hands hold now (scenery-programmer plan D1). See `docs/lighting-composition-model.md`
§"Scenery — beside the layers".

**Scenery tracks; lighting does not.** A cue is a complete lighting state, but a tab closed at Q14
stays closed at Q15. `show/SceneryResolver.kt` resolves each element, per state, highest last:

1. the element's base (its own `params.states`);
2. each live stack's **set**, then its **cues from the top of the list down to the live cue**
   (cue entries only), the last change winning — computed from the list, not from history, so
   GO TO Q20 lands the set as if the list had been run. With two stacks live they fold in GO order,
   so the most recently GO'd wins — not the lighting resolver's database-id order;
3. the Looks the live cues **layer** (enabled, `amount > 0`, not timed), in layer order;
4. the Looks **live in the programmer** — pressed, or on a busk pad — in layer order;
5. the **programmer's own scenery** (`state/ProgrammerScenery.kt`, scenery-programmer plan D1, D2):
   a sparse overlay, element → the states held and the fade they were moved on, one per desk,
   runtime only. Written by `programmer.setScenery` / `programmer.clearScenery` on the programmer's
   socket and by the AI's `move_scenery`; each write is checked against the element's kind with
   `parseSceneryState` (`open` only on a DRAW drape, `trimM` only on a flown piece) and merged over
   what the element already holds. Clear (`clearProgrammerCompletely`) lets it all go on the Clear's
   fade, and State's project collector drops it on a switch.

A state the element's kind no longer takes (a drape switched from DRAW) is ignored.

**Blind** takes tiers 4 and 5 off stage: the live resolve leaves them out, and a second resolve that
includes them is published as `staged` — only the elements whose state differs from live, and only
while there is one (D12). Each staged entry moves from where live (or an earlier staged move) has the
piece, on the clock leaving Blind will give it, so the Stage view's Output + Programmer and
Programmer sources can draw the move the operator is rehearsing. Leaving Blind lands it.

**Each entry names what holds it** (`source`, D4): the source of its highest-tier state —
`base`, `set` (with the stack and its name), `cue` (stack, cue, label), `cueLook` (stack, Look and its
name), `programmerLook` (Look and name) or `programmer` (`SceneryResolver.holderOf`).

**The hook.** `state/SceneryService.kt` holds the result as a `StateFlow`, `scenery.state` on the
socket. It recomputes on `activateCueInStack` (`onCueLive`) and `deactivateStack`
(`onStackStopped`), so every GO path reports — REST, MIDI, busk, auto-advance — and on the AI's
`apply_cue`, which bypasses the manager (`applyCue` calls `onCueApplied`; the cue stands in for its
stack's live cue until the stack goes or stops, and `stop_cue` lets it go). It also follows the
programmer's layer stack (`layersFlow`), the programmer's scenery (`ProgrammerScenery.flow`), blind,
and every cue, stack, Look and element list change, so an edit anywhere moves the stage. The elements
resolved are those a stored change names, those the programmer holds, and those the last frame
carried — so a piece only the programmer held flies home before it leaves the frame, which it does
once landed on its base.

A hook only queues: the recompute runs on the service's one worker thread, never inside the
caller's transaction (a stack delete calls `deactivateStack` inside its own, and the pool is one
SQLite connection), and reads holding no lock. Two rules ride on that queue. **A recompute not yet
started answers every later one**, so a fader riding a layer's amount queues one read, not one per
move. And **a GO's moves start on the first recompute that reads it**, whatever queued that run: the
hook records the GO beside the live table, and the run takes both at once. So an edit's recompute
still queued when a GO lands moves the GO's changes on their clocks, two stacks going in one burst
each move on their own cue's, and a second GO on a stack before the first's was read leaves the
first cue's changes to land. The live table is the service's own, written by the hooks — not the
manager's, which changes a stack's cue before `activateCueInStack` reaches `onCueLive`.

**Clocks** (`SceneryService.durationFor`, scenery-programmer plan D6). Per state that moved, the
longest wins, each on the first clock that applies:

1. a change of **the cue just GO'd** keeps its own `transition` (a cue's only; null follows the cue's
   fade);
2. a **programmer move** takes the operator's fade when it is above 0;
3. a piece the programmer **let go on a fade** (Clear's, or a release that carried one) flies home on
   it;
4. **everything else** — a programmer move with no fade, a Look pressed, a set edit, a stack stopping,
   GO TO landing a change an earlier cue made — runs at the element's own `travelS`, the seconds for
   its whole travel (`docs/fixtures-engineering.md` §"The scene document"), scaled by the share of
   it moved: `|Δopen|` for a drawn drape, `|ΔtrimM| / |out − in|` for a flown piece (in its Z, out
   its base trim; a full travel when they are equal). `visible` never travels, and a piece with no
   `travelS` snaps, as every such move did before.

A move's `from` is where the piece is drawn at that moment, so a retarget mid-move starts there.

**REST.** Whole-list `PUT` on `cues/{id}/scenery`, `cue-stacks/{id}/scenery` and
`looks/{id}/scenery` (`routes/projectScenery.kt`), body `{scenery: [{elementUuid, state,
transitionMs?}]}`, every problem at once (a state its element's kind cannot take, an element named
twice, a `transitionMs` off a cue, or a separator). The owners' read DTOs carry the lists:
`CueDetails.scenery` (plus `trackedScenery`, what the cue shows without moving it and where from),
`CueStackDetails.scenery`, `LookDetails.scenery` — the stack list carries every cue's **own**
changes too, `CueStackDetails.cues[].scenery` (scenery-programmer plan D13, D14: the cue table's
Scenery column and the Prompt Book read every cue at once; `@EncodeDefault(ALWAYS)`, one batched query
per stack, `cueSceneryByCue`), so every cue scenery write — the `PUT`, the AI's `set_scenery` on a
cue, a Record or an Update that wrote scenery — fires `cueStackListChanged` beside `cueListChanged`, and an
element delete that swept scenery (REST or `set_scene`'s `removeElements`) announces the cue, stack
and Look lists (`sceneryOwnersChanged`) —
and the Look list's and busk pads' `LookDto`
carries a summary, `scenery: [{elementUuid, elementName, state}]` (`@EncodeDefault(ALWAYS)`, one
batched query per list, `lookScenerySummariesFor`), for the Looks sheet's Scenery column. Deleting an element sweeps its changes; deleting a
cue, stack or Look sweeps its own; a same-project cue or Look copy carries them.

**Record, Include and Update** (scenery-programmer plan session 3, D7, D8;
`routes/programmerSceneryRecord.kt`). Record (`POST /programmer/record`, `record-look`, the AI's
`record_cue` and `record_look`) writes the programmer's held scenery into the owner's rows unless its request says
`scenery: false`; no mask, source or fixture scope narrows it.

- **Into a cue**, a held piece the cue has no row for is written only where it differs from what the
  cue shows without it — `sceneryBeneathCue`: the base, the stack's set, then every earlier cue entry
  of the list, the resolver's own fold with the cue's own rows left out. Equal, it is counted as
  `sceneryAlreadyTracked` and not written. A row the cue already has is replaced, whatever it says —
  its clock kept unless the held piece carries one from Include — and a row already saying exactly
  that is left alone and not counted. MERGE and UPDATE_EXISTING are that; REMOVE deletes the cue's
  rows for the held pieces. A marker is no cue and is refused by Record outright. A new cue is read at its own place in
  the list, after it is created.
- **Into a Look**, every held state is written, merged per element over the Look's rows; REMOVE
  deletes them. A Look recorded with only scenery held is a scenery Look.
- **Include** (`programmer/include`, `include_into_programmer`) holds the source's own rows in the
  overlay (`ProgrammerScenery.include`), each replacing what it held on that element — never merged,
  never the tracked state, never masked — on the Include's fade, a cue row's `transitionMs` kept on
  the held entry (a later move keeps it). A cue with only scenery sets the include target.
- **Update** writes the held scenery back in Mode A only — into the cue by the MERGE rule above, into
  a Look as its scenery. Mode B writes none.
- **The baseline.** Include sets one (the source's rows) when it moves the include target — an Include that stages nothing leaves both where they were — a Record that retargets the include target
  resets it to what the cue now says (all held, or none when the Record left scenery out or removed
  it), and a Mode A Update to everything held. `programmer.sceneryState` carries
  `changedSinceInclude` — the held pieces that differ from it — which the source strip adds to its
  value dirty count, because the include target and the overlay are two flows and a client cannot
  order them. A project switch drops it.

**Read by element, the other way round** (scenery-programmer plan D11, session 4): `GET
…/stage-elements/{id}/scenery` answers every owner that moves one element — its cues in show order
with their clocks, the stacks whose set holds it, the Looks that show it — each owner's own row and
never what a cue only tracks (`elementSceneryOf`, `routes/projectScenery.kt`). It is the Stage
view's *Moves with*: the popover a click on a piece opens, and the element form, whose entries open
each owner's editor. The popover itself writes the programmer's overlay (tier 5), as the rail's band
does; in Blind the Stage view's programmer sources draw `staged` (D12).

## Events

A cue's **events** fire one-shot triggers — a confetti cannon's tubes — at their offsets after GO
into the cue, only while the desk is armed (stage-view plan session 9; `cues-engineering.md`
§"Cue events" is the contract). Two facts are the stack manager's:

- **The hook, beside scenery's.** `activateCueInStack` builds the cue with `buildCueGoData` (the one
  builder that carries events, so no republish or preview can fire one) inside its own read, and
  ends with `state.effectsService.onCueGo(cueId, label, events)` — after the run state and the
  scenery, so every GO path fires them: REST, MIDI, a busk pad, auto-advance. The AI's `apply_cue`
  reaches the same hook from `applyCue`. `advanceStack` passes `fireEvents = false` for BACKWARD:
  stepping back into a cue fires nothing. **The hook only schedules** — it judges the arm and blind
  at that instant and launches one coroutine per event on the effects service's scope; nothing
  blocks the GO and nothing runs inside a caller's transaction (the pool is one SQLite connection).
  A fire touches memory and the trigger output, and the spent tube is written by the service's own
  worker thread, scenery's arrangement.
- **A stack stop drops the arm.** `deactivateStack` calls `effectsService.onStackStopped`, which
  disarms the desk — any stack stopping is a stop, and the pending events of an armed GO find the
  arm gone when their offset comes and are announced as skipped.

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
remainder instead of nothing. The snapshot is read *after* the listener is registered, so a GO
landing in between is never missed — but that GO can then reach the socket twice, and every frame
is its own launch, so the two can arrive in either order. Each frame therefore carries a
server-side `CueRunState.seq` (one process-wide counter, taken *before* the state is read, so a
frame stamped `n` is no older than any publish stamped `n` or below), and a per-socket
`CueRunStateGate` sends only frames newer than the last it sent for that stack. A snapshot that
ties a change already sent is dropped; a change that ties a snapshot still goes, because only it
carries `transition`. `seq` is not on the wire.

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
- `src/api/cueStacksApi.ts` — Type definitions for CueStack, CueStackInput, CueStackCueEntry, etc. A `CueStackCueEntry` is a union keyed by `cueType`: a `CueStackCueRow` (`id` the cue's) or a `CueStackMarkerRow` (`id: null`), both carrying `entryId`; rows are keyed, sorted, reordered, armed and gone to by `entryId`, and `isCueRow` / `cueRowById` narrow to cue rows
- `src/api/cueStacksWsApi.ts` — WebSocket subscription for `cueStackListChanged`
- `src/api/lightingApi.ts` — Registers `cueStacks` WS API
- `src/api/fxApi.ts` — `FxEffectState` includes `cueStackId`
- `src/api/cuesApi.ts` — `Cue` interface includes `cueStackId`, `cueStackName`, `entryId`, `sortOrder`

### State Management
- `src/store/cueStacks.ts` — RTK Query endpoints with `CueStackList` tag, all CRUD + control mutations, and the marker mutations (`createMarkerEntry`, `renameMarkerEntry`, `deleteMarkerEntry`)
- `src/store/cues.ts` — `useActiveCueStackIds()` hook derives active stack IDs from FxState
- `src/store/restApi.ts` — `CueStackList` added to tag types

## MCP tools

Seven tools (`ai/AiTools.kt`, offered over MCP — `docs/mcp-engineering.md`):
- `create_cue_stack` — Create with name, loop
- `activate_cue_stack` — Activate (optionally at specific cue). With no cue named it routes to
  `activateAtFirstCue`, so it honours an armed standby exactly as the REST route and the surface do
- `deactivate_cue_stack` — Deactivate
- `go_cue_stack` — GO: fire whatever is on deck, starting the stack if it is stopped. It calls
  `CueStackManager.go`, which is also what `SurfaceActions.cueStackGo` calls — the active-or-not
  branch is GO's whole meaning, so the model, the tablet and the physical GO button press one
  button rather than three implementations of it
- `advance_cue_stack` — Advance forward/backward; the model is pointed at `go_cue_stack` for FORWARD
  and this for BACKWARD
- `set_standby` — Arm (or, with no `cueId`, disarm) the cue the next GO fires, without moving a light
- `add_cue_to_stack` — Move/add a cue into a stack

The `get_current_state` tool includes `cue_stacks` in its default include set, returning stack names, cue counts, and active cue info, and `cue_run` alongside it for the run state of every stack that has one — the armed-or-positional `nextCueId`, `nextIsArmed`, and the live fade. `cue_stacks` cannot answer what the next GO fires: "next" is server-owned (see §"Standby"), so a model that armed a standby has no other way to read its own arming back. Active effects include `cueStackId`. Auto-advance and crossfade are configured per-cue (not per-stack) via the cue editor UI or `create_cue` tool.

The MCP `instructions` (`RigBriefing.keyConcepts`) describe cue stack concepts and workflow.
