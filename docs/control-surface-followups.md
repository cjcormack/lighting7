# Control Surface — Follow-ups & Deferred Improvements

Ideas surfaced during Phase 6 review that aren't blocking but are worth revisiting once we
have real operator hours on the hardware. Each entry includes the origin (which phase / review
noticed it), the scope, and a suggested fix shape.

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
