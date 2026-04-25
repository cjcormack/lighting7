# Lighting7 Follow-ups

Consolidated follow-up items from the completed cue-authoring-unification and
control-surface plans (Phases 0–9 landed through 2026-04-23). Each entry is
self-contained so a Claude Code session can pick up one item cold.

## How to use

- **Pick items by slug ID** (e.g. `FU-PERF-COALESCE-WRITES`). Slugs are stable —
  unlike numbers, they don't shift when items land.
- **Check the status tag first**:
  - **Ready** — scope is clear, no blocker; pick up and go.
  - **Trigger** — listed signal must fire first; the condition is named in the
    item body.
  - **Blocked** — named prerequisite must land first.
  - **Manual** — operational validation on hardware; no engineering scope.
- **Each item is self-contained**: origin, scope, suggested shape, and (where
  relevant) the trigger to revisit. A cold session should need nothing else.
- **When an item lands**, move it to the Completed section at the bottom with
  the commit SHA or PR link — keeps an audit trail.
- **New follow-ups** surfacing during work: add them here rather than a new doc.

---

## Performance

### `FU-PERF-COALESCE-WRITES` — Coalesce per-target cue-edit writes on the fader hot path

**Status**: Trigger (profile first)
**Origin**: Control-surface Phase 6, 2026-04-23

A bound fader during an open `cueEdit` session runs
`CueEditSessionHandler.setPropertyForSession` per inbound MIDI CC event —
potentially 100 transactions/second on the MIDI input coroutine (Hikari borrow,
Exposed transaction, lazy-load of `propertyAssignments`, `upsertAssignment`,
`buildCueApplyData`, `republishLayer3`, registry resync). Layer 4 fallback
(`writeFixtureProperty`) is pure in-memory + UDP, orders of magnitude cheaper.

**Mitigation options**:
- **Coalesce**: debounce per `(cueId, targetType, targetKey, propertyName)` so
  only the last value in a ~10 ms window is persisted. Per-target coroutine
  with a conflated channel.
- **Off-thread dispatch**: dedicated single-consumer coroutine so the MIDI
  input loop never blocks on a DB transaction. Cheaper than debouncing (no
  drop-intermediate) but doesn't reduce total work.
- **Batch at transaction boundary**: per-session dispatcher groups N writes
  into one transaction. Harder — the handler also republishes Layer 3 per
  write.

Recommended first step: **coalesce + off-thread**. 10–20 ms debounce in a
per-target actor on `Dispatchers.IO`. Acceptable sacrifice: mid-move values
don't all hit the DB (only endpoints + ~50 Hz samples).

**Trigger to revisit**: profile first. Operators probably don't wiggle faders
at 100 Hz for minutes on end; Hikari + Exposed on local Postgres may be fast
enough for show-scale operation.

### `FU-PERF-HEX-FORMAT-ALLOC` — `String.format` allocation per colour cueEdit write

**Status**: Trigger (after `FU-PERF-COALESCE-WRITES`)
**Origin**: Control-surface Phase 6, 2026-04-23

Routed through `Layer3Resolver.PropertyValue.Colour.serialize()` →
`ExtendedColour.toSerializedString()` → `Color.toHexString()`. Each fader move
on a colour binding allocates a Formatter + intermediate strings. Low priority
— dwarfed by `FU-PERF-COALESCE-WRITES`.

**Fix shape**: micro-benchmark first, then replace with a lookup table (e.g.
`val HEX = Array(256) { "%02x".format(it) }`).

### `FU-PERF-FRAME-TXN-UNIFY` — FX beat + wall-clock frame-transaction unification

**Status**: Trigger (event-driven)
**Origin**: Control-surface Phase 8 step 2, deferred 2026-04-23

The two FX tick loops (`processBeatTickSuspend` ~120 Hz,
`processWallClockTickSuspend` 50 Hz) each construct their own
`ControlTransaction` and call `applySuspend()` independently. Within the same
~20 ms window on the same universe, two ArtNet packets go out. The 25 ms
transmission throttle in `ArtNetController.runTransmissionChannel` coalesces
most of that; the per-channel delta filter in `sendCurrentValues()`
(`previousSentDmxData != byteValue`) suppresses redundant values at packet-
build time — visible cost today is negligible.

Phase 8 design sketched a `FrameTransaction` abstraction: beat + wall-clock
share one `ControlTransaction` when their tick times fall inside a
configurable fuzz window (default 10 ms); otherwise commit independently.
Implementation needs an `AtomicReference<FrameTransaction?>` + a short mutex
around the open/close edge (both loops run on `Dispatchers.Default`).

**Trigger to revisit** (any one):
- Operator reports visible flicker / double-stepping on a fixture where beat
  and wall-clock effects share a universe.
- Profiling shows sustained ArtNet packet rate above ~40 pkts/sec per universe
  under effect load (indicates the 25 ms throttle is being bypassed).
- A future effect category pushes wall-clock density high enough that the tick
  windows overlap frequently (current 50 Hz wall-clock + ~120 Hz beat worst-
  case doesn't).

Without one of those, coordination cost > savings. Leave dormant.

---

## Frontend polish

### `FU-FE-REBIND-INPLACE` — In-place "Rebind" UX for dead assignments

**Status**: Trigger (operator feedback)
**Origin**: Cue-authoring Phase 6, deferred 2026-04-22

`DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` ship a single Remove
button per dead row. The plan's original framing called for a "Rebind" quick-
action that opens a picker pre-populated with the dead assignment's property +
value and creates a new row on commit. We shipped the simpler Remove-and-re-
author flow.

**Trigger to revisit**: operator feedback asks for it.
`PropertyChannelWriter` (Phase 7) can drive live-preview of the proposed
rebind — implementation is unblocked.

### `FU-FE-PERF-DASHBOARD` — Surface `/api/rest/perf/*` data in the frontend

**Status**: Trigger (after second perf endpoint settles)
**Origin**: Surfaced 2026-04-25 alongside `FU-PERF-INSTRUMENT-CUEEDIT`. The
backend now exposes `GET /api/rest/perf/artnet-rates` and
`GET /api/rest/perf/cueedit-histogram`, but no frontend view consumes them —
operators must `curl` the endpoints directly to read p50/p95/p99 or per-universe
ArtNet packet rates.

That's acceptable today (perf data is harness-driven, scraped post-run by the
operator running the flood) but won't scale: any future "is the show pipeline
healthy right now?" check requires a JSON-aware terminal. A live-show operator
shouldn't have to leave the UI to answer it.

**Fix shape**: a `Diagnostics` / `Performance` route in `lighting-react` —
sibling to `/surfaces`. Initial scope:
- ArtNet panel: per-universe table (subnet, universe, packets/sec, total).
  Polled at 2 s — slow enough to not pressure the backend, fast enough to see
  effect-load spikes.
- cueEdit panel: live + last-session histogram. Bar chart of bucket counts
  with p50/p95/p99/max as overlay markers; "session active" indicator.
  Polled at 2 s while session is active; final snapshot held when inactive.
- Empty-state copy on each panel pointing to the relevant
  [followups.md](docs/plans/followups.md) item ("no ArtNet universes — mock
  show", "no cueEdit session — open one to populate").

Add RTK Query endpoints `useGetArtNetRatesQuery` / `useGetCueEditHistogramQuery`
in the existing `lighting-react/src/store/api/` slice. No new state shape — both
endpoints are read-only polled GETs.

**Trigger to revisit**: a third perf endpoint lands (likely
`FU-PERF-INSTRUMENT-MIDI`), at which point per-endpoint curl friction crosses
the threshold to justify a unified view. Building the dashboard for two
endpoints today is borderline; three+ is clearly worth it. Until then, the
existing curl-and-jq workflow is fine for the harness operator.

---

## Backend / composition model

### `FU-BE-MOVE-IN-DARK` — `moveInDark` during outgoing fade

**Status**: Blocked (needs moving-head fixture on test rig)
**Origin**: Cue-authoring spec'd Phase 1, not yet implemented

Current linear-interp crossfade path handles basic position fades. The
"pre-apply incoming position during outgoing fade when outgoing intensity is
0 at end" affordance (spec'd in
[lighting-composition-model.md](../lighting-composition-model.md)) is still
deferred. Scope small — `Layer3Resolver` already knows the `moveInDark` flag
on each `Assignment`.

**Blocker**: needs a real moving-head fixture on the test rig to validate the
behaviour visually and tune the "pre-apply window" threshold empirically.

---

## Code quality

---

## Testing

### `FU-TEST-DMX-FX-BENCH-HARNESS` — DMX/FX micro-benchmark harness

**Status**: Trigger (next perf-sensitive DMX/FX change)
**Origin**: Control-surface Phase 8 exit criterion, 2026-04-23

Phase 8 proposed `BenchmarkSetValues.kt` in `src/test/kotlin/…/dmx/`
synthesising a "burst of 512 channel writes across 4 universes" workload and
recording the blocking-vs-suspend delta. `MockDmxController.setValuesSuspend`
is synchronous (delegates to the sync body), so benchmarking against the mock
shows no difference between the two paths — meaningful numbers need a
coroutine-aware test controller that mimics `ArtNetController`'s per-channel
conflated consumer loop without opening a UDP socket. Non-trivial to build
well.

**Trigger to revisit**: build alongside the next perf-sensitive DMX/FX change
that would benefit from regression coverage — another `setValues` refactor, a
new effect-engine hot path, or an operator-reported lag investigation.
Premature to build speculatively without a concrete workload to calibrate
against.

### `FU-TEST-FX-BENCH-CI-GATE` — `FxEngineBenchmark` CI regression gate

**Status**: Trigger (variance study)
**Origin**: Cue-authoring Phase 5, deferred 2026-04-22

Benchmark ships track-only. The plan called for a fail-on-regression gate at
±20% tolerance against a committed baseline.

**Trigger to revisit**: collect a week of baseline numbers across dev / CI
hardware first to judge variance. 20% is a guess; real tolerance depends on
how jittery the allocation counter + `measureNanoTime` numbers are on the
actual CI runner. Without that variance study, a fixed threshold will either
flake constantly or fail to catch real regressions.

### `FU-TEST-MULTI-CONN-CUEEDIT` — Multi-connection cueEdit conflict

**Status**: Blocked (awaiting cue-authoring semantics)
**Origin**: Control-surface Phase 6

The plan defers to cue-authoring's "reject-second-beginEdit" conflict
resolution, but there's no Phase 6 test that two WS connections racing on
`beginEdit` behave correctly for surface routing.

**Unblock by**: confirming exact semantics with cue-authoring owners, then
add the test.

### `FU-TEST-VITE-BUILD` — Frontend `vite build` validation

**Status**: Blocked (Node upgrade)
**Origin**: Cue-authoring Phase 6, 2026-04-22

Node 16.x on the development machine at landing time blocked `vite build`;
`tsc --noEmit` passed. Subsequent landings have worked around this. Confirm
a clean prod build once Node is upgraded — nothing structural is known to
fail, but the check hasn't run cleanly in a while.

### `FU-TEST-COREMIDI-INIT-DEADLOCK` — Full-suite hang in `initializeShow` ↔ `MidiDeviceRegistry` poll

**Status**: Ready
**Origin**: Surfaced 2026-04-25 while running `./gradlew test` for
`FU-PERF-INSTRUMENT-MIDI` — full suite wedged ~18 minutes; targeted runs of
the same integration tests pass in 30 s. Reproduces only when several
integration test classes run sequentially in the same Test Worker JVM.

**Symptom**: a Test Worker thread blocks indefinitely at
[State.kt:351](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt:351)
inside `registerCoreMidiChangeListener` →
`CoreMidiDeviceProvider.addNotificationListener`, waiting on the
`CoreMidiDeviceProvider` class lock. A `jstack` shows the lock is held by a
`MidiDeviceRegistry-poll` coroutine running
[MidiDeviceRegistry.tickLocked](src/main/kotlin/uk/me/cormack/lighting7/midi/MidiDeviceRegistry.kt:152)
→ `MidiSystem.getMidiDeviceInfo` → `JSSecurityManager.getProviders` →
`ServiceLoader` → `CoreMidiDeviceProvider.<init>` (which itself wants the
class lock again under `getSystemMidiDeviceInfo`). Two threads, opposite
acquisition orders for the `CoreMidiDeviceProvider` class lock and the
`JSSecurityManager` class lock — classic AB/BA deadlock.

The race window opens because [State.initializeShow](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt:282)
starts the registry's poll loop *before* calling `registerCoreMidiChangeListener`:

```
midiRegistry.start(GlobalScope)        // spawns the poll loop
deviceMatcher.start(GlobalScope)
midiLearnSessionManager.start(GlobalScope)
surfaceFeedbackPublisher.start(GlobalScope)
surfaceInputRouter.start(GlobalScope)
registerCoreMidiChangeListener()       // races first poll tick
```

Production survives because (a) tests rarely run more than one integration
class in a session, and (b) on a real boot the listener add usually wins
before the first poll tick fires. Test runs accumulate ~10× registries (one
per `RouteIntegrationTest` subclass) in the same JVM, and `initializeShow`
leaks the `GlobalScope` poll loops between tests (no `State.shutdown` exists
— see scope-limits note on `FU-TEST-HTTP-ROUNDTRIP`). Eventually two of those
loops + one `addNotificationListener` interleave wrong.

**Fix shape options**:
- Serialise initialisation: call `registerCoreMidiChangeListener` *before*
  `midiRegistry.start`. The notification listener add doesn't depend on the
  poll loop; reordering removes the race entirely.
- Stop leaking poll loops: give `State` a `shutdown()` that cancels the
  GlobalScope-rooted jobs and wire it to JUnit `@AfterClass` in
  `RouteIntegrationTest`. Independently good — also kills the Hikari pool
  leak called out in `FU-TEST-HTTP-ROUNDTRIP`.
- Defensive: guard `tickLocked` against entering `MidiSystem.getMidiDeviceInfo`
  while a class-init is in progress (hard to check without internal hooks).

Reordering (option 1) is the cheap immediate fix; the shutdown hook (option 2)
is the proper one. Both should land. Detect future regressions by adding
`@Test(timeout = 60_000)` to the `RouteIntegrationTest` base — currently a
hung integration class drags out a `gradlew test` run for the configured
worker idle timeout (~30 min on this host).

**Why now**: every integration-test author hits this once they add a third
integration class. Subsequent landings have hand-waved past it by running
targeted `--tests`; that workaround silently disables `make commit-check`-style
gating. Cheap fix, high leverage.

---

## Manual hardware validation

These are operational validations pending an operator session on the X-Touch
Compact. No engineering scope; each is 10–15 minutes end-to-end.

### `FU-MANUAL-SCALER-PROJECT-SWITCH` — Scaler state across project switches (Phase 9)

**Status**: Manual
**Origin**: Control-surface Phase 9

Connect device → toggle **Blackout** on project A (confirm LED + stage) →
switch to project B via `/projects` → Blackout off on B (fresh holder) →
switch back to A → Blackout still on, stage still dark. Same flow for
**Grand Master**. Verify a WS client open across the switch sees the correct
`surfaceScaler.state` payload at each switch and after toggling within the
new project. Backend restart resets both projects (expected — option B not
landed; see `FU-BE-SCALER-PERSISTENCE`).

### `FU-MANUAL-SUSPEND-PATH` — Suspend-path sanity check (Phase 8)

**Status**: Manual
**Origin**: Control-surface Phase 8

Run a script that adds and removes 100 effects/sec while a MIDI fader is at
full 60 Hz on the same property. Confirm no stage stutter, no WebSocket
`channelState` lag, no coroutine leak on a thread dump. No functional changes
expected — the suspend path delivers the same per-channel acks as the old
blocking path — regression sanity check, not new validation work.

### `FU-MANUAL-CUEEDIT-HARDWARE` — cueEdit integration on hardware (Phase 6)

**Status**: Manual
**Origin**: Control-surface Phase 6

Open a cue for edit in Live mode via the frontend → wiggle a bound fader →
confirm the cue's Layer 3 `dimmer` row updates (via `GET /cues/{id}`) →
stage reflects the new value → close the editor → retrigger the cue →
reproduces the edit. Repeat in Blind mode: stage unaffected during the edit,
value still persists.

### `FU-MANUAL-SURFACES-FLOW` — End-to-end `/surfaces` flow (Phase 5)

**Status**: Manual
**Origin**: Control-surface Phase 5

Connect the device → `/surfaces` shows it as attached. Click **+** on a fader
row, open MIDI Learn, wiggle the physical fader → binding appears. Switch
banks via the `BankSwitcher` → matrix rows update. Validates the Phase 5 UI
+ Phase 3/4 wiring against real hardware edges (debounce, device-side bank
events, motor drive under load).

### `FU-MANUAL-DEAD-ASSIGNMENTS` — Dead-assignment banner live rig (Phase 6)

**Status**: Manual
**Origin**: Cue-authoring Phase 6, 2026-04-22

Backend logic for `DeadAssignmentsBanner` / `DeadPresetAssignmentsBanner` is
stateless and covered by unit tests. WS fan-out + React rendering of dead
markers after a fixture rename wasn't validated end-to-end on real hardware.

**Manual test**: rename a fixture in a patch, reload the cue editor, confirm
dead markers appear on affected rows, confirm Remove clears them. 10 minutes.

---

## Completed

_Move items here as they land. Format:_
`- FU-SLUG-ID — commit abcdef0 (YYYY-MM-DD) / [PR link] — short note if useful_

- `FU-BE-PALETTE-CASCADE` — commit 3181784 (2026-04-23) — Introduced
  `PaletteCascade(preset, cue, global)` in [Layer3Resolver.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/Layer3Resolver.kt)
  with an `effective` property that picks the most-specific non-empty scope.
  Extended `Layer3Resolver.parseAssignmentValue` with an optional
  `palette: List<ExtendedColour>` that routes colour values through
  `resolveColour` (palette-ref aware). Both `buildLayer3AssignmentsForCue`
  and `buildLayer3AssignmentsForPreset` take a single `cascade: PaletteCascade`
  parameter; callers construct the cascade once per cue-apply and reuse via
  `cascade.copy(preset = ip.palette)` for each preset contribution. Added
  `List<String>.toPaletteColours()` helper in
  [EffectParamUtils.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/EffectParamUtils.kt)
  collapsing 10+ `map { parseExtendedColour(it) }` sites. Threaded the
  cascade through all call sites: `applyCue`, `republishLayer3`,
  `activateTimedEffectsForCue` (global palette hoisted out of per-fire
  lambda; recurring fires load only the preset's palette each transaction),
  `CueStackManager.activateCueInStack`, and the preset toggle / Layer 4
  write paths. Tests in
  [Layer3ResolverTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/Layer3ResolverTest.kt)
  cover palette-ref parsing with empty / non-empty palettes and wrap-modulo
  indexing;
  [BuildLayer3AssignmentsForPresetTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/BuildLayer3AssignmentsForPresetTest.kt)
  asserts the preset → cue → global cascade ordering and static-colour
  bypass;
  [BuildLayer3AssignmentsForCueTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/BuildLayer3AssignmentsForCueTest.kt)
  asserts cue-palette application and no-palette → white fallback.
- `FU-BE-PRESET-FIXTURE-TYPE-NOTNULL` — commit 83ae4d3 (2026-04-23) — Dropped `.nullable()` on
  `DaoFxPresets.fixtureType` and added `migrateFxPresetsFixtureTypeNotNull` to
  [State.kt](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt): inspects
  `information_schema`, deletes any legacy NULL-type presets (plus their
  `fx_preset_property_assignments` and `cue_preset_applications` children) so
  the subsequent `ALTER TABLE … SET NOT NULL` succeeds. Tightened
  `FxPresetDetails.fixtureType`, `PersistedFixtureReferenceValidator.validatePresetPropertyReference`,
  and the `lightFixtures` / `lightGroups` compatibility filters to non-nullable
  now that the column can't be null; required `fixtureType` in the `create_fx_preset`
  AI tool schema. Dropped the now-obsolete "preset with null fixtureType" test.
- `FU-BE-TIMED-PRESETS-LAYER3` — commit ce5304c (2026-04-23) — Added
  `FxEngine.appendCueAssignments(cueId, additions)` and
  `removeCueAssignmentSubset(cueId, toRemove)` (structural-equality
  one-per-element remove). Wired `CueTriggerManager`'s timed-preset fire path
  to append the preset's property assignments to Layer 3 at fire time and
  retract the prior fire's rows on each recurring tick so the cue's assignment
  list does not accumulate duplicates. Cue teardown still calls
  `removeCueAssignments(cueId)` which clears everything in one shot —
  `removeCueAssignmentSubset` is only needed for the recurring re-fire path.
  `activateTimedEffectsForCue` now takes a `priority: Int` parameter (passed
  from `cueDerivedPriority(cueData)` at both call sites) so timed and
  apply-time rows share composition priority.
- `FU-FE-EXT-COLOUR-CHANNELS` — commit 53a96a0 in lighting-react + b39f1f2
  (docs) in lighting7 (2026-04-23) — Added W/A/UV sliders to
  `ColourPickerPopover` (lighting-react), gated on
  `ColourPropertyDescriptor.whiteChannel` / `amberChannel` / `uvChannel`
  presence. Writes route through existing `useUpdateFixtureColour` /
  `useUpdateGroupColour` hooks unchanged. Both `PropertyVisualizers` (fixture)
  and `GroupPropertyVisualizers` (group) pass current `w` / `a` / `uv` values
  so the popover reflects live state.
- `FU-TEST-PROJECT-SWITCH-CUEEDIT` — commit ef3cf29 (2026-04-24) — Added
  integration test in
  [SurfaceFeedbackPublisherTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisherTest.kt)
  (`project switch clears cue-edit session cache and falls back to live DMX`)
  that drives the scenario: begin a cueEdit session on project A (cue
  `dimmer=64` while live DMX=255, so feedback tracks the cue at 7-bit 32) →
  call `SurfaceFeedbackPublisher.onProjectChanged()` → assert the post-switch
  full-resync feedback flips to 7-bit 127 (live DMX 255), proving
  `sessionAssignments` was cleared.
- `FU-BE-GROUP-LAYER3-ROUNDTRIP` — commit ff578a9 (2026-04-24) — Reworked
  `captureCurrentState` in
  [projectCues.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt)
  to preserve group-scoped Layer 3 shape. Added
  `FxEngine.activeCueAssignmentIds()` (snapshot of the cue-assignment map
  keys). `captureCurrentState` now fetches each active cue's DB
  `propertyAssignments` in a single transaction, collects every
  `(groupKey, propertyName)` mentioned with `targetType="group"`, and
  delegates to the pure `captureLayer3AssignmentsFromSnapshot` helper. That
  helper emits one group row per hint iff all members share a single composed
  value in `currentLayer3State`; any break in uniformity (cross-cue fixture
  override, partial timed-preset) falls back to per-fixture rows. Composed
  values remain authoritative — the DB rows only hint at which groups to try
  to preserve, so HTP / LTP / crossfade still reflect the stage look.
  Uncovered `currentLayer3State` entries (e.g. timed-preset fires not in DB)
  still emit as `targetType="fixture"`. Unit coverage in
  [CaptureLayer3AssignmentsTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/CaptureLayer3AssignmentsTest.kt)
  exercises collapse on uniform values, fallback on override break / missing
  member / unknown group, mixed group + uncovered uv row, and empty-snapshot
  short-circuit.
- `FU-PERF-REGISTRY-INDICES` — commit 672c139 (2026-04-24) — Added secondary
  index `sessionsByProject: ConcurrentHashMap<Int, Entry>` to
  [CueEditSessionRegistry.kt](src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSessionRegistry.kt),
  kept in lockstep with the handle-keyed `sessions` map under a single
  `mutationLock` critical section on register / unregister. `activeSession` now
  does a direct `sessionsByProject[projectId]` lookup instead of the prior
  `sessions.values.firstOrNull { ... }` scan. Added
  `continuousByAssignmentKey: Map<AssignmentKey, List<ContinuousEntry>>` to the
  `Index` snapshot in
  [SurfaceFeedbackPublisher.kt](src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt),
  populated in `rebuildIndex()` from the pre-existing `assignmentKeyFor(entry)`
  helper; `resyncEntriesMatching(key)` now iterates that index's pre-filtered
  list instead of walking every `continuousByDisplay` entry and filtering with
  `entryMatchesAssignmentKey`. `entryMatchesAssignmentKey` deleted — the
  secondary index subsumes its role. Existing
  [CueEditSessionRegistryTest.kt](src/test/kotlin/uk/me/cormack/lighting7/plugins/CueEditSessionRegistryTest.kt)
  and
  [SurfaceFeedbackPublisherTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisherTest.kt)
  cover the new paths (including the `AssignmentChanged event drives feedback
  to the new cue value` case which exercises the assignment-key index).
- `FU-FE-PRESET-LIVE-PREVIEW` — commit bb24302 in lighting7 + 9929274 in
  lighting-react (2026-04-24) —
  Added `POST /{projectId}/fx-presets/preview` and `DELETE /{projectId}/fx-presets/preview`
  in [projectFxPresets.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectFxPresets.kt).
  Each push atomically clears the project's prior preview Layer-4 writes (via the
  existing `clearPresetToggleWrite`) and reapplies the in-progress draft using
  `applyPresetLayer4Writes` with a synthetic preset id derived from the project key
  hash. `swapPresetPreviewSlot` extracts the `ConcurrentHashMap.compute` lifecycle
  so two concurrent pushes for the same project can't interleave; coverage in
  [PresetPreviewSlotTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/PresetPreviewSlotTest.kt).
  Frontend ships a `PresetLivePreview` panel inside `PresetEditor` (lighting-react
  `src/components/presets/PresetLivePreview.tsx`) — operator toggles "Live Preview" on,
  picks compatible fixtures (filtered by `preset.fixtureType`) and/or groups (unfiltered),
  the panel debounces draft pushes at 80 ms and fires a clear on toggle-off / editor
  unmount via the new `usePreviewPresetMutation` / `useClearPresetPreviewMutation`
  RTK hooks. Effects-channel preview is intentionally out of scope — only Layer-4
  property assignments land. Single preview slot per project (last-writer-wins);
  multi-tab race is acceptable, mid-show preview by an operator is the dominant case.
- `FU-QUAL-PUSHDOWN-SESSION-ROUTING` — commit 379a845 (2026-04-24) — Dropped
  `writeFixturePropertyToCueEdit` / `writeGroupPropertyToCueEdit` from the
  [SurfaceActions.kt](src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceActions.kt)
  interface; `DefaultSurfaceActions.writeFixtureProperty` / `writeGroupProperty`
  now call a private `activeCueEditSession()` that reads
  `state.cueEditSessionRegistry.activeSession(projectId)?.session` and fans out
  to the existing `upsertCueAssignment` path when a session is open. Router
  [SurfaceInputRouter.kt](src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt)
  drops its `cueEditSessionProvider` constructor param; `dispatchContinuous`
  becomes a plain 2-arm `when` over `BindingTarget.FixtureProperty` /
  `GroupProperty`. [State.kt](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
  no longer wires a provider into the router. Flash press keeps hitting Layer 4
  regardless of session (frontend parity) — that path was always outside the
  removed router branch. Router tests that exercised session routing at the
  router layer (3 tests) are deleted — the behaviour moves inside
  `DefaultSurfaceActions`, which is state-coupled and exercised by hardware
  validation (FU-MANUAL-CUEEDIT-HARDWARE). Remaining
  [SurfaceInputRouterTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouterTest.kt)
  is correspondingly simpler (no session parameter, no `CueEditSessionState`
  import).
- `FU-QUAL-TARGET-REF-SEALED` — commit 8161820 (2026-04-24) — Introduced
  [TargetRef.kt](src/main/kotlin/uk/me/cormack/lighting7/models/TargetRef.kt):
  `sealed class TargetRef { Fixture(key); Group(key) }` with `discriminator` /
  `key` accessors and `TargetRef.of(type, key)` for parsing DB / DTO strings.
  Wire format stays unchanged — DTOs (`CueTargetDto`, `CuePropertyAssignmentDto`,
  `CueAdHocEffectDto`, `TogglePresetTarget`) keep their `targetType: String` +
  `targetKey: String` fields and gain a `target: TargetRef` computed property
  plus a `constructor(target: TargetRef)` convenience. DAO entities
  (`DaoCuePropertyAssignment`, `DaoCueAdHocEffect`) gain a `var target: TargetRef`
  with a setter that mirrors into the underlying varchar columns. Internal
  branch logic converts from `when/if (... == "group")` over the string to
  `when (ref) { is TargetRef.Fixture -> ...; is TargetRef.Group -> ... }` in
  [projectCues.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt)
  (`buildLayer3AssignmentsForCue`, `buildLayer3AssignmentsForPreset`,
  `buildStompOverlapFromAssignments`, `captureLayer3Assignments`,
  `resolveTargetForCue`),
  [projectFxPresets.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectFxPresets.kt)
  (`isPresetActiveOnTarget`, `resolveTarget`, toggle preset flow), and
  [AiTools.kt](src/main/kotlin/uk/me/cormack/lighting7/ai/AiTools.kt)
  (`executeClearEffects`). Event payloads in
  [CueEditSessionRegistry.kt](src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSessionRegistry.kt)
  (`Event.AssignmentChanged` / `AssignmentCleared`) now carry a `target: TargetRef`
  in place of the prior `targetType` / `targetKey` pair; ditto
  `SurfaceFeedbackPublisher.AssignmentKey`. `CueEditSessionHandler.setProperty` /
  `setPropertyForSession` / `clearAssignment` now take `target: TargetRef`;
  [Sockets.kt](src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt)
  converts from the WS message's still-stringly-typed `targetType` / `targetKey`
  at the entry point. `DefaultSurfaceActions` likewise parses to `TargetRef` at
  the call site. `PersistedFixtureReferenceValidator.validateTargetedReference`
  now takes `target: TargetRef`; callers in
  [BindingHealthEvaluator.kt](src/main/kotlin/uk/me/cormack/lighting7/midi/BindingHealthEvaluator.kt)
  and `toDtoWithHealth` in
  [projectCues.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt)
  adapted accordingly. Tests updated:
  [PersistedFixtureReferenceValidatorTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/PersistedFixtureReferenceValidatorTest.kt),
  [CueEditSessionRegistryTest.kt](src/test/kotlin/uk/me/cormack/lighting7/plugins/CueEditSessionRegistryTest.kt),
  [SurfaceFeedbackPublisherTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisherTest.kt).
  DB columns stay as `varchar(50)`; JSON wire format unchanged; frontend needs
  no changes. `FxTargetRef` (in `fx/FxTarget.kt`) left as-is — it's an
  fx-target-specific type with richer semantics (property mapping, blend
  modes).
- `FU-FE-PICKER-UX-POLISH` — commit e97a664 in lighting-react (2026-04-24) —
  Added a `preselectedTarget?: CueTarget | null` prop to both
  [EffectFlow.tsx](src/components/cues/editor/EffectFlow.tsx) and
  [PresetPicker.tsx](src/components/cues/editor/PresetPicker.tsx). When set
  in add mode, the flow starts at the category / preset step with
  `selectedTarget` pre-filled instead of re-asking for a target; back from
  that step calls `onCancel` (no target-picker to return to).
  [CueTargetDetail.tsx](src/components/cues/editor/CueTargetDetail.tsx)
  passes its current `selection` directly (`TargetSelection` is
  structurally `CueTarget`). The full target-picker flow is preserved for
  any non-preselected entry path.
- `FU-BE-PRESET-PER-ELEMENT` — commit 0106ab4 (2026-04-24) — Added nullable
  `element_key` column to `fx_preset_property_assignments` via
  [fxPresets.kt](src/main/kotlin/uk/me/cormack/lighting7/models/fxPresets.kt)
  (picked up automatically by `SchemaUtils.createMissingTablesAndColumns`;
  no explicit migration). DTO `FxPresetPropertyAssignmentDto.elementKey` is
  optional (`null` preserves existing per-fixture behaviour); when non-null
  it's interpreted as either a suffix (`"head-0"`) or a fully-qualified
  element key (`"bar-1.head-0"`). `buildLayer3AssignmentsForPreset` in
  [projectCues.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt)
  now resolves element-scoped assignments via a new `findElement` helper
  (requires `MultiElementFixture` on the target; skips members without a
  matching element) and looks up category / composition via
  `elementCategoryFor`, which reflects on the element class's
  `@FixtureProperty` annotations since `FixtureElement` isn't a `Fixture`
  and doesn't participate in the parent's `fixtureProperties` catalogue.
  Group-scoped element assignments fan out per-member: one row per
  `${memberKey}.${elementSuffix}` target that exists. Widened
  [PropertyChannelWriter.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/PropertyChannelWriter.kt)
  (`resolve` / `channelsFor`) to accept `GroupableFixture` — internally
  branches on `Fixture` vs `FixtureElement<*>` for the reflection lookup;
  widened `FxEngine.writeLayer4Property` / `clearLayer4Property` to match
  so preset toggle / preview Layer 4 writes work on elements too.
  `applyPresetLayer4Writes` / `clearPresetToggleWrite` switched from
  `untypedFixture` to `untypedGroupableFixture` to resolve element rows.
  `PersistedFixtureReferenceValidator.validatePresetPropertyReference` now
  takes an optional `elementKey: String?`; when set, it validates against
  `FixtureTypeInfo.elementGroupProperties` (properties common to all
  elements) rather than the fixture-level descriptor list, so multi-head
  presets with mixed per-head properties don't false-positive. Coverage:
  6 new cases in
  [BuildLayer3AssignmentsForPresetTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/BuildLayer3AssignmentsForPresetTest.kt)
  (fixture + group element targeting, full-key vs suffix, non-multi-element
  fixture skip, mixed-group skip-one-emit-other, `elementKey=null`
  backwards-compat); 4 new cases in
  [PersistedFixtureReferenceValidatorTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/PersistedFixtureReferenceValidatorTest.kt)
  (valid element property, synthetic `position` via pan+tilt, single-head
  fixture rejection, unknown property on multi-head); 3 new cases in
  [PropertyChannelWriterTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/PropertyChannelWriterTest.kt)
  (element slider / position / channelsFor) exercising the
  `GroupableFixture` path. Frontend preset-editor element switcher is out
  of scope for this backend landing — when added, it surfaces through the
  existing `elementGroupProperties` on `FixtureTypeInfo`; DTO already
  round-trips `elementKey`.
- `FU-QUAL-KEY-CONVERGENCE` — commit 30dd0fc (2026-04-24) — Replaced
  `Layer3Resolver.Key(targetKey: String, propertyName)` with
  `Layer3Resolver.Key(target: TargetRef, propertyName)` in
  [Layer3Resolver.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/Layer3Resolver.kt),
  keeping `targetKey: String` as a computed accessor (`target.key`) so
  existing read sites stayed untouched. Added
  `Layer3Resolver.Key.fixture(fixtureKey, propertyName)` and
  `.group(groupKey, propertyName)` companion factories — the former is the
  dominant-case constructor for resolver output (post-expansion rows are
  always `TargetRef.Fixture`). Updated internal `resolve()` plus every
  construction site in `FxEngine` (`clearLayer4Property`,
  `applyLayer4Write`, `applyLayer4GroupOperation`), `LayerResolver`
  (`fallbackFor`), and `captureLayer3AssignmentsFromSnapshot` in
  [projectCues.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/projectCues.kt).
  Deleted `SurfaceFeedbackPublisher.AssignmentKey` — it was structurally
  identical `(target: TargetRef, propertyName: String)`; converged on
  `Layer3Resolver.Key`, which correctly expresses `TargetRef.Group` for
  pre-expansion surface routing alongside the resolver's own
  `TargetRef.Fixture` output. Tests in
  [Layer3ResolverTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/Layer3ResolverTest.kt),
  [FxEngineCueAssignmentsTest.kt](src/test/kotlin/uk/me/cormack/lighting7/fx/FxEngineCueAssignmentsTest.kt),
  [CaptureLayer3AssignmentsTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/CaptureLayer3AssignmentsTest.kt),
  and
  [BuildLayer3AssignmentsForCueTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/BuildLayer3AssignmentsForCueTest.kt)
  adopt `Layer3Resolver.Key.fixture(...)` at every site. No
  semantic change — the resolver still emits only fixture-level keys
  post-expansion; the type now just carries the discriminator the surface
  layer already needed.
- `FU-BE-SCALER-PERSISTENCE` — commit 7bcd109 (2026-04-24) — Added
  [DaoProjectScalerStates](../../src/main/kotlin/uk/me/cormack/lighting7/models/projectScalerStates.kt)
  (`project_scaler_states(project_id UNIQUE, blackout BOOLEAN default false,
  grand_master BOOLEAN default true)`) and registered it in
  `SchemaUtils.createMissingTablesAndColumns` so it materialises on next boot
  with no explicit migration. Widened
  [GlobalScalerStateHolder](../../src/main/kotlin/uk/me/cormack/lighting7/midi/GlobalScalerStateHolder.kt)
  with optional `initialBlackout` / `initialGrandMaster` constructor args and
  a `persist: (Boolean, Boolean) -> Unit` write-through callback fired on every
  actual state change (skipped on no-op writes via the existing equality
  guard). [State.scalerHolderFor](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
  now seeds the holder from the persisted row on first access (defaults
  `false` / `true` when absent) and wires `persist` to an upsert against
  `DaoProjectScalerStates` inside `transaction(state.database)`. Tests added
  to [GlobalScalerStateTest.kt](../../src/test/kotlin/uk/me/cormack/lighting7/midi/GlobalScalerStateTest.kt)
  cover (a) seeded initial state (restart rehydration), (b) persist fires on
  every mutation path including toggles and carries the current counterpart
  value, (c) no-op writes skip persist, (d) mutations routed through
  `GlobalScalerState.setBlackout` / `setGrandMaster` still reach the persist
  callback.
- `FU-PERF-FX-TICK-ALLOCS` — commit a0d5a8c (2026-04-24) — On the
  [FxEngineBenchmark](src/test/kotlin/uk/me/cormack/lighting7/fx/FxEngineBenchmark.kt)
  rig (4 universes × 168 HexFixtures × 336 effects) cut p50 beat-tick latency
  ~520µs → ~287µs and per-tick allocation ~1.97 MB → ~1.03 MB (both ~45–48%
  down). Three changes: (1) in
  [ControllerTransaction.kt](src/main/kotlin/uk/me/cormack/lighting7/dmx/ControllerTransaction.kt)
  dropped the eager `currentValues.toMutableMap()` copy per transaction;
  `getValue` now checks the staged `valuesToSet` first and falls through to
  the live controller, killing an O(channels-per-universe × universes) copy
  per tick. Also added a fast-path in `applySuspend` that skips the
  coroutineScope when nothing is pending. (2) Added a per-transaction
  `wrappedFixtureCache` in
  [Fixtures.FixturesWithTransaction](src/main/kotlin/uk/me/cormack/lighting7/show/Fixtures.kt)
  so repeated `untypedFixture` / `untypedGroupableFixture` lookups within a
  tick reuse the cloned wrapper instead of re-cloning the fixture's 10+ DMX
  property objects on every call — halves per-tick fixture-wrap allocation on
  the reset + apply double-lookup pattern. (3) In
  [LayerResolver.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/LayerResolver.kt)
  built a `layer3Index: Map<String, Map<String, PropertyValue>>` alongside
  `layer3State` so `fallbackFor` skips the compound `Layer3Resolver.Key`
  allocation on every reset. Also restructured
  `FxEngine.resetActiveProperties` in
  [FxEngine.kt](src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt) to
  drop the per-(fixture, property) `PropertyKey` data class + `buildList`
  with composite-secondary targets, using a two-level `HashMap<String,
  HashSet<String>>` dedupe that only fans out when `compositeTargets` is
  non-null. New coverage in
  [ControllerTransactionSuspendTest.kt](src/test/kotlin/uk/me/cormack/lighting7/dmx/ControllerTransactionSuspendTest.kt)
  pins the lazy-read semantics (`getValue` returns staged writes before
  commit, falls through to live controller otherwise); new coverage in
  [FixturesWithTransactionTest.kt](src/test/kotlin/uk/me/cormack/lighting7/show/FixturesWithTransactionTest.kt)
  asserts repeated lookups return the same wrapped instance.
- `FU-PERF-INSTRUMENT-ARTNET` — commit 0d19fad (2026-04-25) — Added
  [PacketRateCounter.kt](src/main/kotlin/uk/me/cormack/lighting7/dmx/PacketRateCounter.kt):
  lock-free 30-bucket sliding-window counter (one bucket per second, keyed by
  `epochSecond % windowSeconds`); stale buckets CAS-reset before increment so
  a wrap-around doesn't carry yesterday's count into the new second. The
  in-progress second is excluded from the rate average — partial counts would
  otherwise depress p99 readings. Wired into
  [ArtNetController.sendCurrentValues](src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt)
  with one `record()` call after each successful `broadcastDmx` /
  `unicastDmx`; exposed as `packetsPerSecond: Double` and
  `totalPacketsSent: Long` properties on the controller. New
  [perf.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/perf.kt) route
  registers `GET /api/rest/perf/artnet-rates`, filtering
  `state.show.fixtures.controllers` to `ArtNetController` instances and
  returning `{ windowSeconds, universes: [{subnet, universe, packetsPerSec,
  totalPackets}, …] }`. Mock-only test setups return an empty `universes`
  list so the endpoint stays well-formed in tests. Unit coverage in
  [PacketRateCounterTest.kt](src/test/kotlin/uk/me/cormack/lighting7/dmx/PacketRateCounterTest.kt)
  exercises stale-bucket reset on wrap-around (the load-bearing case for
  correctness — `t=100` and `t=130` collide on `% 30`), bucket-out-of-window
  exclusion, partial-window readings, and concurrent-record total
  preservation. Route coverage in
  [PerfRouteTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/PerfRouteTest.kt)
  asserts the empty-mock-show contract.
- `FU-TEST-HTTP-ROUNDTRIP` — commit 4245a7d (2026-04-24) — Added
  `src/test/kotlin/uk/me/cormack/lighting7/testsupport/` harness
  ([EmbeddedTestPostgres.kt](src/test/kotlin/uk/me/cormack/lighting7/testsupport/EmbeddedTestPostgres.kt),
  [RouteIntegrationTest.kt](src/test/kotlin/uk/me/cormack/lighting7/testsupport/RouteIntegrationTest.kt),
  [TestAppConfig.kt](src/test/kotlin/uk/me/cormack/lighting7/testsupport/TestAppConfig.kt),
  [TestShow.kt](src/test/kotlin/uk/me/cormack/lighting7/testsupport/TestShow.kt))
  built around `io.zonky.test:embedded-postgres` — Testcontainers 1.21
  hardcodes Docker API 1.32 via `DockerClientProviderStrategy`, which
  Docker Engine 25+ and OrbStack reject (min 1.40); embedded Postgres runs
  a real Postgres binary in-JVM with no Docker daemon needed. Split
  `Application.module()` → `moduleWithState(state)` in
  [Application.kt](src/main/kotlin/uk/me/cormack/lighting7/Application.kt)
  so tests mount routes over an externally-provided `State` without
  re-entering `initializeShow`. `RouteIntegrationTest` abstract base owns
  per-test schema reset + project + MOCK universe seed + show init.
  Primary test
  [HttpRoundTripTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/HttpRoundTripTest.kt)
  drives POST `/patches` (hex at ch1) → WS `cueEdit.beginEdit(LIVE)` →
  `setProperty(dimmer=200)` → POST `/snapshot-from-live` → `endEdit` →
  GET, asserting the snapshot captured the Live edit's Layer 3 state
  (snapshot intentionally lands inside the open Live session because
  `endEdit` for a standalone cue triggers `removeEffectsForCue` →
  `removeCueAssignments`, tearing Layer 3 down). Inbound WS filter
  `awaitOfType<T>` skips the initial-state burst
  (`channelMapping`/`fxState`/`palette`/`beatSync`) the socket fans out
  on connect. Broader coverage:
  [CueCrudRoundTripTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/CueCrudRoundTripTest.kt)
  (pure-HTTP cue CRUD + PATCH partial update + stack membership),
  [FxPresetRoundTripTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/FxPresetRoundTripTest.kt)
  (preset POST/GET/PUT/DELETE with property-assignment children). All 3
  pass; full suite green. Bumped `kotlin.daemon.jvmargs=-Xmx2g` — default
  1 GB OOMs on full recompiles with Kotlin 2.2 + FX DSL + new test
  sources. Scope limits retained from the plan: no migration of existing
  DTO-level route tests, no `State.shutdown` for clean coroutine teardown
  (known-leaky GlobalScope pollers from `initializeShow` tolerated), no
  Hikari pool close in tearDown (3 tests × 8 connections acceptable).
- `FU-PERF-INSTRUMENT-CUEEDIT` — commit 1607d91 (2026-04-25) — Added
  [LatencyHistogram.kt](src/main/kotlin/uk/me/cormack/lighting7/perf/LatencyHistogram.kt):
  lock-free log2-bucketed nanosecond histogram (`AtomicLongArray` per bucket +
  `AtomicLong` count / sum / max with CAS-update on max). Default 32 buckets
  cover [1 ns, ~4.29 s); observations beyond the top bucket pile into bucket
  31 but `maxNanos` still tracks the actual peak. `percentileNanos(p)` walks
  buckets cumulatively and returns the right bound of the bucket holding the
  target observation, capped by `maxNanos` so the top bucket never reports an
  inflated value. Wrapped in
  [CueEditLatencyTracker.kt](src/main/kotlin/uk/me/cormack/lighting7/perf/CueEditLatencyTracker.kt):
  one tracker per [State](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt);
  `onBeginEdit` resets the live histogram and flips `sessionActive`, `measure {}`
  records each call's wall-clock duration (records on exception too — a failed
  transaction is part of what the operator wants to see), `onEndEdit` freezes
  a snapshot to `lastSessionEnded`. Wired into
  [CueEditSessionHandler](src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSession.kt):
  `beginEdit` calls `onBeginEdit` after the apply succeeds (so a failed
  Live-apply doesn't reset the histogram); `setPropertyForSession`'s body
  runs inside `measure { }` so both call sites (WS `cueEdit.setProperty` and
  the MIDI fader path through `DefaultSurfaceActions`) record uniformly;
  `endEdit` and `endSessionOnDisconnect` both call `onEndEdit` so a closed
  WebSocket still freezes the snapshot. New `GET /api/rest/perf/cueedit-histogram`
  in [perf.kt](src/main/kotlin/uk/me/cormack/lighting7/routes/perf.kt) returns
  `{ sessionActive, live, lastSessionEnded }` — the harness operator scrapes
  this after a `MidiFloodHarness` flood + endEdit to read p50/p95/p99/max
  + per-bucket counts. Unit coverage in
  [LatencyHistogramTest.kt](src/test/kotlin/uk/me/cormack/lighting7/perf/LatencyHistogramTest.kt)
  pins log2 bucket placement, percentile cumulative walk + max-capped right
  bound, top-bucket overflow, reset semantics, and concurrent-record count
  preservation; in
  [CueEditLatencyTrackerTest.kt](src/test/kotlin/uk/me/cormack/lighting7/perf/CueEditLatencyTrackerTest.kt)
  asserts the begin → measure → end lifecycle, the next-begin reset
  preserving lastSessionEnded, and that a throwing block still records;
  in [PerfRouteTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/PerfRouteTest.kt)
  asserts the empty-snapshot pre-session case and the surfaced
  `lastSessionEnded` after a synthetic begin → measure ×3 → end cycle.
  Unblocks `FU-PERF-COALESCE-WRITES` and `FU-PERF-HEX-FORMAT-ALLOC` —
  the trigger condition for both ("profile first") is now answerable.
- `FU-PERF-INSTRUMENT-MIDI` — commit b01d4b3 (2026-04-25) — Added
  [MidiLatencyTracker.kt](src/main/kotlin/uk/me/cormack/lighting7/perf/MidiLatencyTracker.kt)
  with `enum MidiLatencyStage(val wireName)` covering the four named buckets
  (`INGRESS_CONTINUOUS`/`INGRESS_BUTTON`/`EGRESS_MOTOR`/`EGRESS_LED`); the
  tracker pre-allocates one [LatencyHistogram] per stage in an
  ordinal-indexed `Array`. `inline fun <T> measure(stage, block)` — array
  index + `System.nanoTime()` pair, no map lookup, no lambda allocation on the
  ~100 Hz hot path. Threaded through
  [State.midiLatencyTracker](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
  into both [SurfaceInputRouter](src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt)
  (wraps `dispatchContinuous` in `INGRESS_CONTINUOUS`; `dispatchButtonPress` /
  `dispatchButtonRelease` in `INGRESS_BUTTON`) and
  [SurfaceFeedbackPublisher](src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt)
  (wraps `controller.sendFeedback` in `EGRESS_MOTOR` / `EGRESS_LED`). Tracker
  parameter is non-null with a default `MidiLatencyTracker()` — no opt-out
  needed; tests construct their own. Added `inboundCcRate` / `outboundCcRate:
  PacketRateCounter` to the `MidiController` interface;
  [KtMidiController](src/main/kotlin/uk/me/cormack/lighting7/midi/KtMidiController.kt)
  records inbound on each parsed `MidiInputEvent.ControlChange` and outbound
  on each `MidiFeedbackMessage.ControlChangeFeedback` that survives delta
  suppression — sysex / NoteOn / NoteOff are not counted.
  [MidiDeviceRegistry.portCcRates](src/main/kotlin/uk/me/cormack/lighting7/midi/MidiDeviceRegistry.kt)
  exposes a per-port `@Serializable PortCcRates` snapshot returned directly by
  the route (mirroring the `UniversePacketStats` pattern). New
  [GET /api/rest/perf/midi-latency](src/main/kotlin/uk/me/cormack/lighting7/routes/perf.kt)
  returns `{ windowSeconds, histograms: {wireName → snapshot}, ports }`;
  `POST .../reset` zeroes every bucket (operator-driven; no per-session
  boundary exists for MIDI). Bucket keys sorted alphabetically by `wireName`.
  Adjacent: reordered `registerCoreMidiChangeListener()` before
  `midiRegistry.start(...)` in
  [State.initializeShow](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)
  to close a poll-loop ↔ listener-add deadlock that hung the full test suite —
  see `FU-TEST-COREMIDI-INIT-DEADLOCK`. Coverage:
  [MidiLatencyTrackerTest.kt](src/test/kotlin/uk/me/cormack/lighting7/perf/MidiLatencyTrackerTest.kt)
  pins pre-allocated buckets, alphabetic snapshot ordering, reset semantics,
  exception-records-still-counted, and `record(stage, nanos)` accepts
  pre-measured durations;
  [MidiFeedbackConflationTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/MidiFeedbackConflationTest.kt)
  adds three cases (outbound CC counted, delta-suppressed sends not counted,
  inbound CC parsed-and-counted while NoteOn skipped);
  [SurfaceInputRouterTest.kt](src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouterTest.kt)
  adds three cases (`INGRESS_CONTINUOUS` on fader CC, `INGRESS_BUTTON` on
  press + release, no recording when binding misses);
  [PerfRouteTest.kt](src/test/kotlin/uk/me/cormack/lighting7/routes/PerfRouteTest.kt)
  adds three cases (empty snapshot, surfaced buckets, POST reset zeroing).
  Unblocks end-to-end interpretation of `MidiFloodHarness` output and
  quantitative `FU-MANUAL-SUSPEND-PATH` validation.
