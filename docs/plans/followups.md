# Lighting7 Follow-ups

Consolidated follow-up items from the completed cue-authoring-unification and
control-surface plans (Phases 0–9 landed through 2026-04-23). Each entry is
self-contained so a Claude Code session can pick up one item cold.

> **Status (2026-04-25)**: Active backlog drained. Every open item is
> **Trigger-gated**, **Blocked**, or **Manual** — nothing is in **Ready**. Do
> not poll this doc; consult it only when your current work might fire a
> listed trigger (CLAUDE.md → "Follow-ups" explains when to look).

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
  under effect load (indicates the 25 ms throttle is being bypassed). This is
  now directly observable on the Diagnostics dashboard via
  `GET /api/rest/perf/artnet-rates` (`FU-PERF-INSTRUMENT-ARTNET`, 0d19fad) —
  no profiling pass required to detect.
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

### `FU-FE-HEALTH-BADGE` — Shared `<HealthBadge>` for AssignmentHealth

**Status**: Trigger (4th display surface)
**Origin**: `moveInDark` row-list editor, 2026-04-25

`AssignmentHealth` is now rendered in three places:
[`DeadAssignmentsBanner.tsx`](https://github.com/cjcormack/lighting-react/blob/main/src/components/cues/editor/DeadAssignmentsBanner.tsx),
[`DeadPresetAssignmentsBanner.tsx`](https://github.com/cjcormack/lighting-react/blob/main/src/components/presets/DeadPresetAssignmentsBanner.tsx),
and `PropertyAssignmentsList.tsx`. All three use `describeHealth()` from
`lib/healthDescriptor.ts` for the label, but each wraps it in its own
`<Badge>` / `<Alert>` markup. Worth extracting a small `<HealthBadge
health={...} />` once a fourth surface needs it (likely candidates: cue
detail sheet, surface bindings list).

**Trigger to revisit**: a new UI surface needs to render `AssignmentHealth` —
extract then rather than now. Three call sites with stable display patterns
isn't enough to overcome the cost of the abstraction.

### `FU-FE-USE-TARGET-PROPERTIES` — Shared hook for fixture/group property lookup

**Status**: Trigger (next consumer)
**Origin**: `moveInDark` row-list editor, 2026-04-25

`PropertyAssignmentsList.tsx::useTargetProperties` fetches a fixture's or
group's available properties via `useFixtureListQuery` / `useGroupPropertiesQuery`
and maps to a uniform shape. The same fetch-and-map pattern appears in
`FixtureContent.tsx`, `GroupCard.tsx`, `PresetEditor.tsx`,
`PresetLivePreview.tsx`, and the busking target panel — each one re-doing the
property categorization inline. Extract a `useTargetProperties(selection)` hook
in `src/hooks/` returning a flat `AvailableProperty[]` (and ideally a
categorized variant for surfaces that need colour/dimmer/position grouping).

**Trigger to revisit**: the next time a sixth consumer needs the same lookup,
or a property-shape change forces a multi-file edit. Today's three
implementations are stable; pulling them together is mechanical churn that
risks breaking visual regressions until the next consumer pays for itself.

---

## Backend / composition model

---

## Distribution

### `FU-DIST-ICONS` — Real macOS / Windows installer icons

**Status**: Ready
**Origin**: Windows-distribution Phase 3, 2026-04-28

`packageMac` / `packageWindows` pass `--icon` only when `assets/lighting7.icns`
or `assets/lighting7.ico` exists. Today neither does, so jpackage falls back
to its default Java cup icon for the dock / taskbar / installer.

**Scope**: design (or commission) a 1024×1024 source PNG for lighting7, then
generate `assets/lighting7.icns` (macOS) and `assets/lighting7.ico` (Windows,
multi-resolution embed: 16/32/48/64/128/256). `iconutil` produces `.icns`
from an `iconset` directory; ImageMagick `convert` produces `.ico`. Drop both
files at `assets/`; `packageMac` and `packageWindows` pick them up on the
next run with no Gradle changes.

The existing `launcher/src/main/resources/lighting7.png` is the tray-icon
placeholder and is too small / not OS-icon-shaped — use it as inspiration,
not as the source PNG.

---

## Cloud sync

### `FU-SYNC-TOMBSTONE-GC` — Tombstone garbage collection

**Status**: Trigger (deferred maintenance)
**Origin**: Cloud-sync Phases 7–8, deferred 2026-05-01

`tombstones/{tableName}/{uuid}.json` files (~25 bytes each) plus their
matching `sync_state` rows accumulate forever. GC isn't a correctness
issue, just tree-size optimisation — but the *safety analysis* is the
load-bearing part, not the home of the loop.

**Why pure age-based GC is unsafe.** A naive 90-day cutoff would resurrect
records on installs that were offline longer than the cutoff: an install
catching up after, say, 6 months would see `live record locally, no remote
file, no sync_state row` and treat it as a brand-new local record (push
it). That's the exact resurrection scenario the tombstone was preventing
— see
[`RemoteSyncEngineTombstonePropagationTest`](../../src/test/kotlin/uk/me/cormack/lighting7/sync/RemoteSyncEngineTombstonePropagationTest.kt).

**Safe shape.** Extend `installs.json` so each install records the commit
SHA and timestamp it last synced to. GC only tombstones whose path's last
touch (`git log -1 --format=%ct -- tombstones/{table}/{uuid}.json`) is
older than the *oldest* install's last-synced timestamp — i.e. older than
the slowest peer's view of history. That's a `formatVersion` bump (new
required field on the install registry) plus a migration for repos
already in flight. A simpler escape hatch: drop installs from
`installs.json` once they haven't pushed in N months, GC tombstones
older than every remaining install's last-synced, and document that
re-introducing a long-dormant install requires a clean clone.

**Where it lives.** A separate `SyncMaintenance` coroutine in
[`sync/`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/), started
from `Application.module()` parallel to
[`AutoSyncScheduler`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/AutoSyncScheduler.kt),
ticking once per day. Iterates every `sync_configs.enabled = true`
project (not just auto-sync — manual-sync projects also accumulate
tombstones). A manual `POST /api/rest/project/{id}/sync/maintenance/gc-tombstones`
endpoint surfaces the same operation for operator-driven cleanup and
tests. Tying GC to `AutoSyncScheduler` would skip projects that only
ever sync on demand.

**Per-project GC steps.** `git log` the `tombstones/` paths, identify
files older than the low-water mark, then in order: (1) snapshot a
commit that `git rm`s the tombstone, (2) drop the matching
[`sync_state`](../../src/main/kotlin/uk/me/cormack/lighting7/models/syncState.kt)
row. Reverse order would leave a snapshot with no `sync_state` row,
re-arming carry-forward (`SnapshotEngine.snapshot` only writes
tombstones for records that *do* have a `sync_state` row), so a peer
that hadn't yet caught up would resurrect the deletion.

**Trigger to revisit** (any one):
- `find tombstones -type f | wc -l` exceeds ~1000 on a real project's
  working tree.
- Operator reports working-tree size pain or `git gc` failing to
  reclaim meaningful space.
- A delete-heavy migration is being planned (e.g. tearing out an old
  fixture set en masse) where the post-migration tombstone count is
  predictable and the operator wants a clean tree before pushing.

Below those, the engineering cost (peer low-water in `installs.json`,
migration, ordering invariants) is out of proportion to the disk
savings.

### `FU-SYNC-JGIT-STRESS-BENCH` — JGit memory stress benchmark

**Status**: Trigger (signal-driven)
**Origin**: Cloud-sync plan §"Risks and open items", revisited 2026-05-01

The cloud-sync design doc flagged "JGit memory on large projects with
thousands of cues" as a Phase-5 stress-test item. Phases 5–8 landed
without a concrete stress fixture. The dominant memory cost during
`runSync` is
[`JGitClient.walkTree`](../../src/main/kotlin/uk/me/cormack/lighting7/sync/JGitClient.kt)
materialising every blob into a `Map<String, String>` — bounded by ~2×
the working tree's blob size resident at peak (local + remote snapshots
plus the merged tree).

For lighting7's actual deployment scale (personal rig: tens of cues per
project; small touring rig: low hundreds), this is invisible. The risk
only matters at the speculative "thousands of cues" tier the design doc
called out.

**Suggested harness shape.**
- Programmatic seed in a new test under
  [`src/test/kotlin/uk/me/cormack/lighting7/sync/`](../../src/test/kotlin/uk/me/cormack/lighting7/sync/):
  N cues × M stacks × K propertyAssignments per cue at realistic content
  sizes (~5 KB per cue JSON). Sane large N: 1000 / 5000 / 10000.
- Run a full `RemoteSyncEngine.runSync` cycle against a local bare-repo
  remote (no network — same pattern as
  [`JGitRemoteTest`](../../src/test/kotlin/uk/me/cormack/lighting7/sync/JGitRemoteTest.kt)).
- Measure peak heap via
  `Runtime.getRuntime().totalMemory() - freeMemory()` snapshots at the
  obvious phase boundaries (post-fetch, post-diff, post-apply,
  post-push), or attach a JFR recording for an allocation profile.
- Output: a one-shot `[stress]` log line per project size with peak
  heap, runSync wall-clock, and total allocations. Gate on
  `-Dsync.stress=true` (mirrors the `dmx.benchmark` / `fx.benchmark`
  precedent forwarded by [`build.gradle.kts`](../../build.gradle.kts)).

**Likely fixes if a threshold is hit.** Stream blobs through `walkTree`
instead of materialising every body (yield per-record snapshots to the
diff). Shallow clones (`--depth=1`) for the working tree if
push-history walking isn't needed. Both are real engineering, hence not
pre-empted.

**Trigger to revisit** (any one):
- A real project hits ~1000 synced records (sum across
  `cues + cuePropertyAssignments + cuePresetApplications + ...`).
- Operator reports `runSync` taking >5s on a real project.
- Sync OOMs in the field.

Until one of those fires, the speculative threshold sits in
[`docs/sync-engineering.md`](../sync-engineering.md) §"Operational
notes" so future-you knows where to look.

### `FU-SYNC-STREAMING-PROGRESS` — Streaming sync progress (fetch %, conflict count)

**Status**: Trigger (operator feedback)
**Origin**: Cloud-sync plan §"Risks and open items", revisited 2026-05-01

Today `runSync` emits exactly one terminal WS message per cycle:
`cloudSyncDone` / `cloudSyncFailed` / `cloudSyncConflictsPending`. The
design doc deliberately punted intermediate progress (a hypothetical
`cloudSyncProgress` carrying fetch %, conflicts-discovered count, etc.)
on the basis that data volumes don't justify the ceremony.

For typical projects the entire `cloudSyncStarted → cloudSyncDone`
interval is sub-second. UX feels fine; no signal yet that operators
need more.

**Trigger to revisit**: operator reports the sync UX feels
unresponsive, **or** a real project's `cloudSyncStarted →
cloudSyncDone` interval consistently exceeds 5s. Either indicates the
user is staring at a non-progressing spinner and would benefit from
intermediate state. The wire-protocol shape will follow whatever the
actual unmet case demands — no design sketch needed pre-emptively.

---

## Code quality

---

## Testing

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

### `FU-MANUAL-DIST-INSTALL` — End-to-end installer validation on Mac + Windows

**Status**: Manual
**Origin**: Windows-distribution Phases 1–3, 2026-04-28

All three phases of the Windows-distribution plan are build-side green —
backend boots from `lighting7.jar` on the trimmed JRE, `packageMac`
produces a 337 MB `.pkg` with the right install layout, the launcher's
`ensureDefaultConfig` writes `local.conf` on first launch. What's never
been exercised end-to-end:

- **Mac**: install `lighting7-1.0.0.pkg` on a clean machine (or wipe
  `/Applications/lighting7.app` + `~/Library/Application Support/lighting7`
  first). Double-click → tray icon appears in the menu bar, browser opens
  to `localhost:8413`, iPad on the same Wi-Fi reaches
  `http://lighting7-<hostname>.local:8413/`, Quit from tray leaves no
  `java` processes (`pgrep -f lighting7`).
- **Windows**: `gradlew.bat packageWindows` on a Windows host →
  install `.msi` on a clean Windows VM with no JDK. Same checks plus:
  `%APPDATA%\lighting7\` is writable for the launcher's first-run
  `local.conf`, mDNS resolves from the iPad (Bonjour-on-Windows ships
  with iTunes / Apple Software Update; without it, JmDNS is the
  responder), Windows Defender doesn't quarantine `lighting7.exe`.
- **Smoke checklist** in either install: BPM tap, fixture patch CRUD,
  run a cue, edit a script in the embedded editor (covers the
  compiler-server child process), iPad WebSocket reconnects after a
  brief Wi-Fi drop.

10–20 minutes per OS. Failures here likely surface as concrete engineering
items (jlink module gaps, mDNS edge cases, Windows path quirks); promote
those to dedicated `FU-DIST-*` follow-ups before fixing.

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

- `FU-BE-MOVE-IN-DARK` — commit 0593d81 (2026-04-25) — Layer3 position
  snap during outgoing fade. Hardware blocker cleared by
  `Fusion100SpotMkIIFixture` modes 8CH/15CH (both `WithDimmer` +
  `WithPosition`). Schema: new `move_in_dark` column on
  `cue_property_assignments` (default false), `moveInDark: Boolean` on
  `CuePropertyAssignmentDto` plumbed through every DAO↔DTO conversion site.
  Detection lives inside `Layer3Resolver.resolve()` via a new
  `computeMoveInDarkArmed` pre-pass: a fixture target arms when a Position
  contributor with the flag is accompanied by a *different* cue's
  DIMMER-category contributor with value 0 on the same target (the
  different-cue requirement excludes self-arming, which the LTP fallback
  already handles correctly). Snap branch runs at the top of `composeLtp`
  *before* the winner-by-priority logic — at fade start the outgoing cue
  wins on the `fadeWeight` tie-break and would otherwise short-circuit to
  outgoing.value. 7 new test cases in `Layer3ResolverTest` cover
  snap-at-start/mid/end, no-flag baseline, bright-dimmer veto,
  no-dimmer-row defensive, self-cue guard, group-expansion per-member, and
  parallel bright-cue. Frontend (lighting-react) ships a new "Assignments"
  tab in `CueTargetDetail` with a per-row editor (value + fade override +
  delete) and a "Move in dark" checkbox visible only on `position` rows;
  authored at the cue level and saved through the existing PUT path.
  Browser-verified end-to-end: UI toggle → PUT → DB column → GET → reopen
  → checkbox state restored. Stage-behaviour validation on the moving head
  remains for the operator (3-cue stack with a dark cue between two
  position-different cues, slow fade, `moveInDark = true` on the second
  position row → head should be aimed for the entire fade-up).
- `FU-PERF-COALESCE-WRITES` — cancelled 2026-04-25 — Profiled the cueEdit
  hot path via new opt-in
  [`CueEditProfileTest`](../../src/test/kotlin/uk/me/cormack/lighting7/perf/CueEditProfileTest.kt)
  (gated on `-Dcueedit.profile=true`, forwarded by
  [build.gradle.kts](../../build.gradle.kts) alongside `dmx.benchmark` /
  `fx.benchmark`). Test extends `RouteIntegrationTest`, patches a
  `HexFixture`, opens a Live cueEdit session over WS, and drives a 6000-event
  flood across four sub-profiles inline (ramp / wiggle / colour-ramp /
  burst — mixed `dimmer` slider + `rgbColour` to exercise both the SQL
  upsert hot path and the `ExtendedColour.toSerializedString` →
  `Color.toHexString` allocation path that
  `FU-PERF-HEX-FORMAT-ALLOC` would have targeted). Histogram scraped from
  `GET /api/rest/perf/cueedit-histogram` after `endEdit`. Drain coroutine
  signals `sessionEnded` via a `CompletableDeferred` so the post-flood
  handshake doesn't race the in-flight ack backlog. Made
  `RouteIntegrationTest.testTimeout` open so the profile harness can extend
  the per-test cap to 300 s. Result on a clean Hikari + embedded-Postgres
  rig: **count=6000 p50=524µs p95=2097µs p99=2097µs max=17.3ms mean=617µs**
  — well under the 5 ms p99 threshold from the decision criteria. Hikari +
  Exposed on local Postgres is fast enough for show-scale operation; the
  "operators probably don't wiggle faders at 100 Hz for minutes on end"
  hypothesis is confirmed. No coalescer needed; existing CONFLATED + signal
  pattern in `KtMidiController` / `ArtNetController` stays the precedent
  for any future flood-path optimisation. Distribution shape
  (54% ≤524µs, 40% ≤1ms, 5% ≤2.1ms, <1% above 2.1ms, single 17ms outlier)
  shows the path is well-behaved with no GC pathology under load.
- `FU-PERF-HEX-FORMAT-ALLOC` — cancelled 2026-04-25 — Was gated on
  `FU-PERF-COALESCE-WRITES` ("low priority — dwarfed by COALESCE-WRITES");
  with that cancelled, this becomes a tiny micro-allocation chasing a
  non-bottleneck. The same 6000-event profile (1000 events through the
  colour serialize path) lands inside the same fast distribution as the
  slider path — no measurable colour-vs-slider gap to chase. The
  `String.format` allocation in
  [Effect.kt:247](../../src/main/kotlin/uk/me/cormack/lighting7/fx/Effect.kt:247)
  stays as-is; if a future workload ever surfaces it, the lookup-table fix
  shape is sketched in the original slug body.
- `FU-TEST-DMX-FX-BENCH-HARNESS` — commit 6e1222e (2026-04-25) — Added
  [`AsyncTestDmxController`](src/main/kotlin/uk/me/cormack/lighting7/dmx/AsyncTestDmxController.kt),
  a coroutine-aware `DmxController` test fake that mirrors
  `ArtNetController`'s per-channel conflated consumer + ack-roundtrip loop
  without UDP — `MockDmxController.setValuesSuspend` falls through to the
  sync body and would silently flatter both paths. New track-only
  [`BenchmarkSetValues`](src/test/kotlin/uk/me/cormack/lighting7/dmx/BenchmarkSetValues.kt)
  drives a 4-universe rig (128 writes per universe per iteration =
  512 total) through `ControllerTransaction.apply()` vs `applySuspend()`,
  prints `[blocking]` / `[suspend]` summary lines (p50/p99/mean/allocBytes),
  and gates on a 1 s p99 floor only. Banked infrastructure — no concrete
  perf change in flight; the harness is calibration-ready for the next
  `setValues` refactor. Skipped by default; opt in via `-Ddmx.benchmark=true`
  ([`build.gradle.kts`](build.gradle.kts) forwards the flag alongside
  `fx.benchmark`). ±20% regression gate stays deferred to
  `FU-TEST-FX-BENCH-CI-GATE`.
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
- `FU-FE-PERF-DASHBOARD` — commit 73f11bb in lighting-react (2026-04-25) — Added a
  `Diagnostics` route in `lighting-react` consuming all three
  `/api/rest/perf/*` endpoints (`artnet-rates`, `cueedit-histogram`,
  `midi-latency`). New
  [perf.ts](../../../lighting-react/src/store/perf.ts) RTK Query slice
  injects `useGetArtNetRatesQuery` / `useGetCueEditHistogramQuery` /
  `useGetMidiLatencyQuery` / `useResetMidiLatencyMutation` into the
  shared `restApi`; all three queries polled at 2 s — slow enough not to
  pressure the backend, fast enough to see effect-load spikes. Added a
  new `'PerfMidi'` tag in
  [restApi.ts](../../../lighting-react/src/store/restApi.ts) so the
  `POST /perf/midi-latency/reset` mutation invalidates the MIDI query
  immediately rather than waiting for the next 2 s tick.
  Route component
  [Diagnostics.tsx](../../../lighting-react/src/routes/Diagnostics.tsx)
  renders three cards: ArtNet panel (per-universe table: subnet,
  universe, packets/sec, total), cueEdit panel (live snapshot while
  `sessionActive`, falling back to `lastSessionEnded` when idle), and
  MIDI panel (per-stage latency table — count / p50 / p95 / p99 / max
  per `ingressContinuous` / `ingressButton` / `egressMotor` /
  `egressLed` — plus per-port CC rates and a Reset button since MIDI
  has no per-session boundary). The cueEdit histogram view trims
  leading/trailing zero-count log2 buckets to focus on the active
  range, surfaces count / mean / p50 / p95 / p99 / max inline, and
  renders a horizontal bar chart of bucket counts with bucket-bound
  labels (`formatNanos` switches between ns / µs / ms / s). Empty-state
  copy on each panel covers the harness flow ("patch a non-mock
  universe" / "open a cue editor in Live mode and adjust a bound
  fader" / "connect a control surface"). Wired the route into
  [App.tsx](../../../lighting-react/src/App.tsx) at
  `/projects/:projectId/diagnostics` (with a `/diagnostics` redirect to
  the current project, mirroring the `/surfaces` pattern) and into the
  shared
  [navigation.ts](../../../lighting-react/src/navigation.ts) registry as
  a `live`-group entry with `Activity` icon and `visibility: "always"`
  (the perf endpoints are process-global — they don't need an active
  project to return data). Type-check passes; smoke-tested in-browser
  against a running backend — `/diagnostics` redirect resolves to the
  current project, the live-group sidebar entry highlights when active,
  all three perf queries poll cleanly at 2 s with 200 OK, ArtNet table
  populates from real universe data, cueEdit and MIDI empty states
  render as expected with no console errors.
- `FU-TEST-COREMIDI-INIT-DEADLOCK` — commit 19fa952 (2026-04-25) — Reorder
  fix already landed in `FU-PERF-INSTRUMENT-MIDI` (b01d4b3) — the
  `addNotificationListener` call now precedes `midiRegistry.start(...)` in
  [State.initializeShow](src/main/kotlin/uk/me/cormack/lighting7/state/State.kt),
  closing the AB/BA window directly. This landing adds the durable second
  half: `State.shutdown()` cancels the `projectChangedFlow` collector and
  walks each surface class's existing `stop()` / `close()` in reverse-startup
  order (input → feedback → learn → matcher → registry), then closes the
  show and drains the Hikari pool (the previously-untracked
  `HikariDataSource` is now retained in a private nullable field so
  `dataSource.close()` can run — Exposed's `Database.connect()` doesn't
  surface the underlying datasource itself). Idempotent via a `shutdownDone`
  flag; every step is `runCatching`-wrapped so a partially-initialised State
  (e.g. `initializeShow` never called) tears down without throwing.
  [RouteIntegrationTest.tearDownIntegrationTest](src/test/kotlin/uk/me/cormack/lighting7/testsupport/RouteIntegrationTest.kt)
  now calls `state.shutdown()` instead of the prior pair of
  `runCatching { state.show.fxEngine.stop() }` / `state.show.close()` —
  the new path subsumes both. Added a base-class
  `@get:Rule val testTimeout: Timeout = Timeout.seconds(60)` so any future
  hang fails the offending test loudly instead of dragging `gradlew test`
  out for the worker idle timeout. Verified: `./gradlew test` finishes green
  in ~43s (full suite ran end-to-end without wedging — same suite that
  previously hung ~18 minutes when run after the targeted integration
  tests). CoreMIDI4J listener removal not attempted; `addNotificationListener`
  has no remove counterpart in the codebase, and on tests the leaked
  callbacks fire only on hot-plug events that don't occur in CI.
- `FU-TEST-VITE-BUILD` — validated 2026-04-25 — `npm run build` (which
  runs `tsc && vite build`) is clean on Node 24.12 LTS:
  2344 modules transformed, dist artefacts emitted, no errors. Only
  output is the informational "chunk > 500 kB" code-splitting hint —
  unchanged from prior runs, not a regression. The Node 16.x → 24.x
  bump on the dev machine resolved the original blocker; the wider
  shell-init issue (Bash subshells inheriting an older Node from PATH
  even after `nvm alias default lts/*`) is sidestepped by sourcing
  `~/.nvm/nvm.sh` in the launch.json invocation.
