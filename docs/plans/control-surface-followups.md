# Control Surface — Follow-ups & Deferred Improvements

Ideas surfaced during Phase 6 review and subsequent phases that aren't blocking but are
worth revisiting once we have real operator hours on the hardware. Each entry includes the
origin (which phase / review noticed it), the scope, and a suggested fix shape.

Companion: [cue-authoring-followups.md](cue-authoring-followups.md) — deferred items from
the cue-authoring plan. Cross-plan items are cross-referenced rather than duplicated.

## Performance

### 1. Coalesce per-target cue-edit writes on the fader hot path

**Origin**: Phase 6 efficiency review, 2026-04-23.

A bound fader during an open `cueEdit` session runs `CueEditSessionHandler.setPropertyForSession`
per inbound MIDI CC event. That is:

1. `transaction(state.database) { … }` — Hikari borrow, Exposed transaction, `DaoCue.findById`,
   lazy-load of `propertyAssignments`, `upsertAssignment`, `buildCueApplyData`.
2. `republishLayer3` if Live — walk presets + rebuild flat `Layer3Resolver.Assignment` list +
   `layerResolver.applyAssignments` + `publishLayer3ToControllers`.
3. `notifyAssignmentChanged` on the registry → publisher resync for matching bound entries.

An X-Touch fader emits roughly 30–100 CC events per second when moving. That's potentially
100 transactions/second on the MIDI input coroutine. By contrast the Layer 4 fallback
(`writeFixtureProperty`) is pure in-memory + UDP — orders of magnitude cheaper.

**Mitigation options**:

- **Coalesce**: debounce per `(cueId, targetType, targetKey, propertyName)` so only the last
  value in a ~10 ms window is persisted. A coroutine per target with a conflated channel is
  the usual shape. Keeps semantics identical (final fader position lands in the DB).
- **Off-thread dispatch**: hand each write off to a dedicated single-consumer coroutine so
  the MIDI input loop never blocks on a DB transaction. Cheaper than debouncing (no drop-
  intermediate) but doesn't reduce total work.
- **Batch at transaction boundary**: rather than a transaction per write, a per-session
  dispatcher that groups N writes into one transaction. Harder to implement because the
  handler also republishes Layer 3 per write.

Recommended first step: **coalesce + off-thread**. Debounce 10–20 ms in a per-target actor;
the actor runs on a dedicated Dispatcher (e.g. `Dispatchers.IO`) so the MIDI input coroutine
stays snappy. Acceptable sacrifice: mid-move values don't all hit the DB (only endpoints and
~50 Hz intermediate samples).

Measure first — in practice operators probably don't wiggle faders at 100 Hz for minutes on
end, and Hikari + Exposed on a local Postgres is fast enough that the naïve path may be fine
for show-scale operation. Defer until we have a real profile.

### 2. `String.format("#%02x%02x%02x", …)` allocation per colour cueEdit write

**Origin**: Phase 6 efficiency review, 2026-04-23.

Now routed through `Layer3Resolver.PropertyValue.Colour.serialize()` which calls
`ExtendedColour.toSerializedString()` which calls `Color.toHexString()`. Each fader move on a
colour binding allocates a Formatter + a few intermediate strings. Low priority — dwarfed by
follow-up 1 — but worth a micro-benchmark before picking a replacement (e.g. a
`val HEX = Array(256) { "%02x".format(it) }` lookup table).

### 2b. Frame-transaction unification (FX beat + wall-clock loops)

**Origin**: Phase 8 step 2, deferred 2026-04-23.

The two FX tick loops (`processBeatTickSuspend` at up to ~120 Hz, `processWallClockTickSuspend`
at 50 Hz) each construct their own `ControlTransaction` and call `applySuspend()`
independently. When both loops tick within the same ~20 ms window and touch the same
universe, two ArtNet packets go out. The 25 ms transmission throttle in
`ArtNetController.runTransmissionChannel` coalesces most of that, and the per-channel delta
filter in `sendCurrentValues()` (`previousSentDmxData != byteValue`) suppresses redundant
values at packet-build time — so the visible cost today is negligible.

The Phase 8 design sketched a `FrameTransaction` abstraction: beat + wall-clock processing
share one `ControlTransaction` when their tick times fall inside a configurable fuzz window
(default 10 ms); otherwise they commit independently. Implementation needs an
`AtomicReference<FrameTransaction?>` plus a short mutex around the open/close edge to
coordinate ownership between the two tick loops (both running on `Dispatchers.Default`).

**Trigger to revisit — event-driven, not scheduled:**

- Operator reports visible flicker or double-stepping on a fixture where beat and wall-clock
  effects share a universe.
- Profiling shows sustained ArtNet packet rate above ~40 pkts/sec per universe under effect
  load (would indicate the 25 ms throttle is being bypassed rather than coalescing).
- A future effect category pushes wall-clock effect densities high enough that the tick
  windows overlap frequently (current 50 Hz wall-clock + ~120 Hz beat worst-case doesn't).

Without one of those signals, the coordination cost outweighs the savings. Leave dormant.

### 2c. DMX/FX micro-benchmark harness

**Origin**: Phase 8 exit criterion, deferred 2026-04-23.

Phase 8 proposed a `BenchmarkSetValues.kt` harness in `src/test/kotlin/…/dmx/` that would
synthesise a "burst of 512 channel writes across 4 universes" workload and record the
blocking-vs-suspend delta. `MockDmxController.setValuesSuspend` is synchronous (delegates to
the sync body), so benchmarking against the mock shows no difference between the two paths —
meaningful numbers need a coroutine-aware test controller that mimics `ArtNetController`'s
per-channel conflated consumer loop without opening a UDP socket. Building that stub well is
non-trivial.

**Trigger to revisit:** build alongside the next perf-sensitive DMX/FX change that would
benefit from regression coverage — e.g. another `setValues` refactor, a new effect-engine
hot path, or investigating a specific operator-reported lag. Premature to build
speculatively without a concrete workload to calibrate against.

### 3. Secondary indices for registry and feedback publisher

**Origin**: Phase 6 efficiency review.

- `CueEditSessionRegistry.activeSession(projectId)` is an O(N) scan over `sessions.values`.
  N is ≤ 1 in practice, but a `ConcurrentHashMap<Int, Entry>` keyed by projectId (kept in
  lockstep with the handle-keyed map) makes it O(1) if sessions ever grow.
- `SurfaceFeedbackPublisher.resyncEntriesMatching(key)` walks every `continuousByDisplay`
  entry on every assignment change. With one device and ≤50 controls this is trivial; a
  secondary `Map<AssignmentKey, List<ContinuousEntry>>` built in `rebuildIndex()` would make
  it O(1).

Neither is urgent; flag for when binding counts grow.

## Code quality

### 4. Stringly-typed `"fixture"` / `"group"` target types

**Origin**: Phase 6 quality review.

Pervasive in the codebase — `CueTargetDto.type`, `CuePropertyAssignmentDto.targetType`, DB
columns, `resolveTargetForCue` switch-on-string logic in `routes/projectCues.kt`. A sealed
`TargetRef.Fixture(key) | Group(key)` would be type-safe but touches many files. Phase 6's
`AssignmentKey` inherits the convention. Worth a dedicated cleanup pass, not in-flight with
feature work.

### 5. Push-down session routing into `DefaultSurfaceActions`

**Origin**: Phase 6 quality review.

Currently the router branches on `activeCueEditSession()` and calls one of
`writeFixturePropertyToCueEdit` / `writeFixtureProperty`. An alternative: the
`DefaultSurfaceActions.writeFixtureProperty` / `writeGroupProperty` implementations could
themselves check for a session and fan out to the cueEdit path, removing the
`cueEditSessionProvider` parameter from the router entirely. Tradeoff: current router shape
is easier to test (the router test doesn't need a session provider); the push-down would
move that branching into `DefaultSurfaceActions`, which is already State-coupled. Defer until
the router grows more context-dependent routing rules.

### 6. `AssignmentKey` vs `Layer3Resolver.Key` convergence

**Origin**: Phase 6 reuse review.

`Layer3Resolver.Key(targetKey, propertyName)` is targetType-agnostic because the resolver
expands group rows to member rows upstream via `targetIsGroup`. Surfaces sit before that
expansion, so they need the `targetType` discriminator. A future refactor could add
targetType to `Layer3Resolver.Key` (and thread it through the resolver), but that's a bigger
change than the Phase 6 scope.

## Plan alignment

### 7. Group-level Layer 3 assignments

**Origin**: Phase 6 reuse review.

`routes/projectCues.kt::captureCurrentState` always emits `targetType="fixture"` rows (it
reads `layerResolver.currentLayer3State`, which is post-expansion). Phase 6's surface
`writeGroupPropertyToCueEdit` writes a `targetType="group"` row directly. Both paths work,
but `captureCurrentState` called after a surface edit to a group will round-trip through as
per-fixture rows rather than preserving the group shape. Need to:

- Decide whether group-scoped assignments are first-class Layer 3 rows (they are per DB
  schema and `CueEditSessionHandler` accepts them).
- Align `captureCurrentState` behaviour — perhaps by snapshotting from the pre-expansion
  assignment list on the cue, not from the resolver's expanded state.

Loop in the cue-authoring plan owner before acting.

## Testing gaps

### 8. Project switch during an open cueEdit session

**Origin**: Phase 6 efficiency review.

`SurfaceFeedbackPublisher.onProjectChanged()` now clears `sessionAssignments` (fixed during
simplification pass), but no test covers the scenario: begin edit on project A → switch to
project B → verify cache is empty, feedback falls back to live DMX. Add an integration test.

### 9. Multi-connection cueEdit conflict

**Origin**: Phase 6 quality review.

The plan defers to cue-authoring's "reject-second-beginEdit" conflict resolution, but
there's no Phase 6 test that two WS connections racing on `beginEdit` behave correctly for
surface routing. Add one once the exact semantics are confirmed with cue-authoring owners.

## Deferred phases

### 10. Phase 9.1 — cross-restart scaler persistence (option B)

**Origin**: Phase 9 design, deferred 2026-04-23.

Phase 9 shipped option A: `GlobalScalerState` is project-scoped and survives project
switches within a session. Backend restart resets Blackout / Grand Master on every project
(intended).

Option B, deferred, persists scaler state across restarts via a single-row DB table per
project:

```sql
project_scaler_state(project_id, blackout BOOLEAN, grand_master BOOLEAN)
```

**Shape when picked up**: `State.scalerHolderFor(projectId)` loads from DB on first
access; `GlobalScalerStateHolder.setBlackout` / `setGrandMaster` write through via
`transaction(state.database)`. Exposed `SchemaUtils.createMissingTablesAndColumns` picks
up the new table on next boot — no migration file.

**Trigger to revisit**: an operator asks to preserve Blackout across a backend restart
(e.g. remote-restart during a show). Without that signal, the deferred framing in the
phase's open question stands — preserving across session-restart is a behaviour change
that warrants explicit user confirmation.

## Manual hardware validation

These are operational validations pending an operator session on the X-Touch Compact —
captured here so the plan file can be marked complete. No engineering scope; each is
10–15 minutes end-to-end.

### 11. Phase 9 — scaler state across project switches

Connect device → toggle **Blackout** on project A (confirm LED + stage) → switch to
project B via `/projects` → Blackout off on B (fresh holder) → switch back to A →
Blackout still on, stage still dark. Same flow for **Grand Master**. Verify a WS client
open across the switch sees the correct `surfaceScaler.state` payload at each switch and
after toggling within the new project. Backend restart resets both projects (expected —
option B not landed; see §10).

### 12. Phase 8 — suspend-path sanity check

Run a script that adds and removes 100 effects/sec while a MIDI fader is at full 60 Hz
on the same property. Confirm no stage stutter, no WebSocket `channelState` lag, no
coroutine leak on a thread dump. No functional changes are expected — the suspend path
delivers the same per-channel acks as the old blocking path — this is a regression
sanity check, not new validation work.

### 13. Phase 6 — cueEdit integration on hardware

Open a cue for edit in Live mode via the frontend → wiggle a bound fader → confirm the
cue's Layer 3 `dimmer` row updates (via `GET /cues/{id}`) → stage reflects the new value
→ close the editor → retrigger the cue → reproduces the edit. Repeat in Blind mode: the
stage is unaffected during the edit, the value still persists.

### 14. Phase 5 — end-to-end `/surfaces` flow

Connect the device → `/surfaces` shows it as attached. Click **+** on a fader row, open
MIDI Learn, wiggle the physical fader → binding appears. Switch banks via the
`BankSwitcher` → matrix rows update. Validates the Phase 5 UI + Phase 3/4 wiring against
real hardware edges (debounce, device-side bank events, motor drive under load).
