# Testing

How the suite is built, what makes it fast, and the traps that have actually bitten.

`./gradlew test` is the pre-commit check (this project has no Makefile, so the global
`make commit-check` rule does not apply). A recent green run earlier in the same session is
sufficient; you do not need to re-run it just before `git commit` if nothing has changed.

## Shape of the suite

~1790 tests in ~180 classes, on **JUnit 4** (`kotlin-test-junit`; `org.junit.Test`,
`@Before`/`@After`). There is no `useJUnitPlatform()` and no `junit-platform.properties`, so
JUnit 5 features — `@Execution(CONCURRENT)`, `@ResourceLock`, `@Isolated` — are not available.
Tests run in **one forked JVM, sequentially**.

Two broad kinds:

- **Pure unit tests** (~1650) — DTOs, resolvers, registries, wire formats. Collectively a few
  seconds. Add tests here by preference.
- **Integration tests** (~140) that build a real `State`: a fresh SQLite file, the full schema,
  a `Show`, and for route tests a Ktor `testApplication`. `testsupport/RouteIntegrationTest`
  is the base class; its `@Before` runs **per test method**.

## Why the suite is ~1 minute and not ~14

It was 14 minutes until 2026-08-24. Almost all of it was fixed per-test cost, not test work —
every integration test paid the same ~1.3 s floor regardless of what it asserted. Four changes
removed it. Each is load-bearing; none is a micro-optimisation.

### 1. `SchemaUtils.create` on an empty database (~1.21 s per test)

`State.initDatabase` branches on `isEmptyDatabase()`. `createMissingTablesAndColumns`
reconciles the model against the live schema through JDBC metadata, and sqlite-jdbc answers
that by re-parsing every table's DDL: **~1220 ms for these 41 tables, paid whether or not
anything is missing** (an immediate second call costs the same again). A database with no
tables has nothing to reconcile, so plain `SchemaUtils.create` is equivalent there and runs in
~6 ms. Detecting the empty case costs 0.2 ms.

This is also ~1.2 s off a new desk's first boot.

`FreshSchemaEquivalenceTest` guards it by building a database both ways and diffing
`sqlite_master`. Index *names* are excluded: Exposed's two paths name the same unique indices
differently (`projects_name` vs `projects_name_unique`) and log about it on every
reconciliation. Everything else must match.

**A test that needs the reconcile path still gets it** — `InstallBootstrapTest` builds a `State`
against a database an earlier `State` already populated, so it sees a non-empty database and pays
the full ~1.4 s. That is correct, and it is why it is among the slowest classes.

### 2. One process-wide FX compilation cache (~70 ms per test)

`FxScriptCompiler`'s cache is on the **companion object**, not the instance. A compiler is
built per `Show`, and a `Show` per project switch — per *test* in the suite. With a per-instance
cache every new `Show` re-evaluated all 28 built-in `.fx.kts` effects: even with the on-disk jar
cache warm, that is 28 jar loads, 28 classloaders and 28 evaluations to arrive at lambdas
identical to the previous `Show`'s.

Safe because `CompiledFxScript` is immutable and its lambdas are stateless — a STATEFUL
effect's mutable state is passed in per call, never captured. `FxScriptCompilerCacheTest` pins
this with an `assertSame` across two compilers.

### 3. The build fingerprint is memoised (~55 ms per test)

`ScriptCache.buildFingerprint` is a file-level `by lazy`. `java.class.path` cannot change
within a JVM, but `buildScriptingHostConfiguration` runs once per `State`, so without the
memoisation every test walked the whole classpath again (~5170 files under `build/classes`).

A rebuild *while the app is live* under `gradle run` keeps the fingerprint the process started
with. That is correct: the JVM is still executing the old classes, and picking up new ones
already requires a restart.

### 4. The CoreMIDI listener is unregistered (memory, not time)

`State.shutdown` calls `unregisterCoreMidiChangeListener()`. CoreMIDI4J's listener list is
**static** and the callback captures `this`, so without the removal every `State` stayed
reachable for the life of the JVM along with its `Show`, both scripting hosts and every
compiled-script classloader. That is invisible in production (one `State` per process) and was
why the Test task needed `maxHeapSize = 2g`. It now passes at the 512m default; the setting is
1g for headroom.

`Show.close()` likewise closes `runnerPool` — `newFixedThreadPoolContext` starts its thread
eagerly and does not stop it when collected, so each discarded `Show` leaked one.

## Two speed regimes

- **Warm** (no code changed since the last run): ~55–60 s.
- **Cold** (any rebuild): **~+55 s**. The compiled-script jar cache is keyed partly on a
  fingerprint of the classpath, so editing any source file invalidates all 28 built-in FX jars
  and they recompile from source. In a normal edit-test loop this is paid every time.

The cost lands on whichever test class first builds a `Show` — today
`ParkSurvivesFixtureReloadTest`, which is why that one-test class can look like the slowest
thing in the suite. It is not slow; it is first. Don't optimise it.

## Test data lives under `build/test-data`

`tasks.test` pins `-Dlighting7.dataDir=build/test-data`. `State` resolves the compiled-script
cache, the prompt-book PDF store, the cloud-sync working tree and the project export root under
`appDataDir()`, which otherwise defaults to the **live desk installation**
(`~/Library/Application Support/lighting7`). Without the pin the suite reads and writes a real
installation next to a desk that may be running, and on a sandboxed machine fails with
`FileSystemException … Operation not permitted`.

`lighting7.dataDir` is read before any config is parsed (see `AppDataDir.kt`), so a system
property is the only hook — `testAppConfig` can pin `database.path` but not this.

**If you ever see `FileSystemException … Operation not permitted`, or a `MissingFieldException`
on a route DTO, suspect the pin has been lost** — the route 500s and returns `ErrorResponse`,
so a denied write disguises itself as a serialization regression.

The directory is not pruned between runs. `script-cache` accumulates a generation of jars per
build fingerprint; `clean` discards the lot.

`testAppConfig` also pins:

- `auth.bcryptCost = 4` (~2 ms) instead of the production 12 (~250 ms).
- `sync.credentialStore = "file"`, to keep the suite out of the developer's real OS keychain.
  The production default is `keychain`, whose service name (`lighting7`) is shared with the
  running desk, so a test that reached `State.credentialStore` would read and write the
  operator's actual GitHub tokens.

Each test gets its own SQLite file: `IntegrationTestDb.reset()` rotates `path` to a fresh name
in a per-JVM temp directory. That singleton is safe across processes but **not** across threads
— `reset()` then read is a two-step protocol.

## Tests that assert real elapsed time

These measure wall clock and are the first to fail on a loaded machine. Treat a failure here as
"the machine was busy" only after checking it is not a real regression, and never tighten their
bounds:

| Test | What it asserts |
|---|---|
| `SpeedMasterBankTest` "deadline timer holds long-run rates exact" | a real 2500 ms window, rates within ±1.5% |
| `ArtNetStreamingTest` "the configured interval governs packet cadence" | 20 frames take ≥1600 ms **and ≤8000 ms** |
| `ArtNetParkSafetyTest`, `ActiveBankStateTest`, `CueEditSessionRegistryTest` | `withTimeout` waits of 1–5 s |

Precedent: commit `51965fc` removed a tick-delivery assertion from `SpeedMasterBankTest` that
held only on an idle machine — it failed 3 of 4 full runs and 0 of 5 runs of the class alone.
`Dispatchers.Default` is shared with the integration tests, and it saturates.

`RouteIntegrationTest` puts a 60 s `Timeout` rule on every test so a hung one fails loudly
instead of stalling the run until the worker idle timeout (~30 min). Note that JUnit 4's
`Timeout` runs the test body on a *separate thread*, which changes thread confinement.

## Order dependence

`scripts/find-order-dependent-tests.sh` runs every test class on its own and diffs the result
against a full-suite run. A class that passes alone and fails in the suite (or vice versa) is an
ordering bug. It takes a while — it is a debugging tool, not part of the normal loop.

Known latent coupling, currently harmless but worth knowing when a test starts behaving oddly:

- `State.shutdown()` does not call `TransactionManager.closeAndUnregister(database)`, so
  `Database` objects accumulate and Exposed's `defaultDatabase` is whichever registered *first*.
  `midi/FakeDatabase.kt` connects an in-memory SQLite from a lazy `object`, so test order
  decides that default. Only `routes/cueNumbering.kt` uses ambient transaction context, which is
  why it has not bitten yet.
- No test currently seeds `controllerType = "ARTNET"`, and it should stay that way: that path
  builds a real `ArtNetController`, which binds UDP 6454 and spawns `GlobalScope` coroutines in
  its constructor, and only the first client in a process wins that port. `seedMinimalProject`
  seeds `MOCK` deliberately; use `RecordingTransport` (`testsupport/ArtNetTestSupport.kt`) to
  assert on what would have been transmitted.

## Why the suite is not run in parallel

`maxParallelForks` is unset. It was considered and is not currently worth it: after the changes
above the suite is ~1 minute, of which a large part is a single cold FX compile that forking
would duplicate rather than divide, and the remainder is dominated by the wall-clock tests
above — exactly what CPU oversubscription breaks.

Forking would also need per-worker data-dir isolation. `build/test-data` is shared, and Kotlin's
`CompiledScriptJarsCache.store()` is a truncate-then-write with no lock or tmp+rename, while all
forks compute *identical* jar filenames — so two workers can tear each other's cached jars.
(`FxFileLoader` already defends against a corrupt cached jar, which is a hint about how this
fails.) `exports/` and `credentials.enc` are not config-overridable, so pinning individual keys
in `testAppConfig` would not cover them; the data dir itself would have to become per-worker.

If the suite grows back past a few minutes, that is the change to make — and this paragraph is
the reason it was not made now.

## Opt-in harnesses

Skipped by default via `org.junit.Assume`; each needs its flag forwarded by `tasks.test`.

```
./gradlew :test --tests "…FxEngineBenchmark"  -Dfx.benchmark=true
./gradlew :test --tests "…BenchmarkSetValues" -Ddmx.benchmark=true
```

There was a third, `CueEditProfileTest` (`-Dcueedit.profile=true`); it went with the
`cueEdit.*` family in backend-sweep item D1. Its flag-forwarding arm in `build.gradle.kts`
went too, but the `if` block around it is shared with the two above — it sets
`outputs.upToDateWhen { false }` and `showStandardStreams` for all of them, so removing the
block rather than just the arm silently costs the surviving benchmarks their numbers.

Note `:test` rather than `test`: the latter also runs `:launcher:test`, which fails the
`--tests` filter with "No tests found for given includes".

### `FxEngineBenchmark` scenarios

Five independent rigs, one per `@Test`, because engine state, cue assignments and the effect
registry all persist per rig — sharing one would make the numbers order-dependent.

| Scenario | Rig | Exists for |
|---|---|---|
| `[beat]` / `[wall]` | 4 universes × `HexFixture`, one beat + one wall-clock `SliderTarget` each (168 fixtures, 336 effects) | the original Phase 5 harness — **frozen**, it is what the 2026-04-22 baseline was measured on |
| `[chase-beat]` / `[chase-wall]` | 40 × `LedLightbar12PixelFixture.Mode48Ch` in two groups (480 `RgbwPixel` elements), 4 group colour effects across two speed masters — FLAT and PER_FIXTURE on both tick loops | sweep C1 (per-tick target re-expansion), C2 (reflective property access on the colour write path), C6 (allocation bundle) |
| `[crossfade]` | 168 `HexFixture`s, two cues × dimmer+colour rows (672 rows), 169 effects; drives `cueLayer.updateFadeWeights` at 62 fps | sweep C3 |
| `[colour-beat]` | 168 `HexFixture`s with colour effects and a half-covered programmer band | sweep C2 — scenario 2 was claimed to cover it and does not |
| `[spawn-each]` / `[spawn-batch]` | scenario 1's fixtures, a **fresh** engine per sample, 168 dimmer effects put up as one cue GO would | sweep C7 — the only scenario that measures *adding* effects rather than ticking them |

`[spawn-each]`/`[spawn-batch]` is shaped differently from the other four on purpose: it reports
both the per-effect and the batched add in the same run, so it carries its own before/after and
does not depend on a historical block. Every other scenario spawns its effects in rig setup,
outside the measured window, which is why none of them could see C7 at all.

The **effects** the harness applies are load-bearing for the same reason the rigs are, and they
live in `testsupport/TestEffects.kt` rather than coming from the `FxRegistry`. Effect maths runs
inside the measured loop, so a baseline is only comparable while that maths stays put — and the
registry's 28 built-ins are `.fx.kts` resources that are expected to evolve. `SineSlider`,
`WindowedSlider`, `HueSweepColour` and `SteppedColour` are frozen copies of the retired
`fx.effects` classes the 2026-04-22 baseline was taken against (backend-sweep item D7). Don't
"improve" their maths, and don't repoint the harness at the registry: either silently moves every
number. Behaviour of the real built-ins is covered separately, against the real registry, in
`fx/BuiltInEffectBehaviourTest`.

The chase rig's fixture choice is load-bearing, not incidental. `Mode48Ch` implements
`MultiElementFixture` and deliberately does *not* implement `WithColour` itself, which is what
pushes `processGroupEffect` down the element-expansion branch; its `RgbwPixel` elements are
`WithColour, WithWhite` with `bundleWithColour = true`, which is what makes each element's write
pay `ColourTarget.extendedComponent`'s `fixtureProperties.find {}` + `KProperty1.call`. Swap in a
cheaper fixture and the harness still runs, still passes, and measures none of that — so each
scenario asserts its own shape before the warmup (`fixtureKeysCoveredBy(...).size`, effect counts,
`cueLayer.activeCueIds()`). Those guards are the only thing standing between a wrong rig and
plausible-looking microsecond numbers.

Since C1 the engine caches each effect's expansion, so those guards no longer prove it is *live* —
a cache that never invalidated would satisfy every one of them, because a benchmark rig registers
once and never repatches. The chase rig re-checks past a `patchListChanged()` to catch a cache
serving garbage, but the invalidation itself is covered by `FxExpansionCacheTest`, not here.

The crossfade scenario measures the **single-threaded** per-frame republish cost. C3 also flags
lock contention on the Layer 4 publish lock (now `CascadePublisher`'s) with concurrent programmer writes; that is a different
shape of measurement and is deliberately not in this harness.

### Recorded baselines

Track-only — nothing asserts against these. `FU-TEST-FX-BENCH-CI-GATE` holds the ±20 %
fail-on-regression gate, deferred pending a variance study on real CI hardware.

**2026-08-24, selwyn.local, JDK 25** — the C-wave "before", captured under sweep item C0:

```
[setup] universes=4 fixtures=168 effects=336
[beat]  ticks=2400 p50=322µs p99=714µs mean=386µs allocBytes/tick=1111848
[wall]  ticks=500  p50=316µs p99=438µs mean=347µs allocBytes/tick=1104593

[setup] universes=4 bars=40 elements=480 elementsPerGroup=240 effects=4 masters=2
[chase-beat] ticks=1200 p50=1007µs p99=6703µs mean=1177µs allocBytes/tick=3441219
[chase-wall] ticks=500  p50=322µs  p99=914µs  mean=402µs  allocBytes/tick=1139358

[setup] universes=4 fixtures=168 cueRows=672 effects=169 frames=312
[crossfade] frames=312 p50=816µs p99=2108µs mean=867µs allocBytes/frame=1338020
```

The chase rig's four effects are deliberately one per branch C1 rewrites: beat FLAT, beat FLAT
on the second master, wall-clock PER_FIXTURE, wall-clock FLAT. Dropping the last one (an earlier
draft did) halves `[chase-wall]` to ~165 µs and hides the wall-clock loop's FLAT arm entirely —
the reason each mode/loop pairing gets its own `fixtureKeysCoveredBy` guard.

Two things to read off it. `[chase-beat]` costs ~3× a `[beat]` tick while running **3** effects
against 336 — that ratio is the C1/C2 signal, and it is what the C-wave should move.
`[crossfade]` at ~773 µs/frame × 62 fps is ~5 % of a core spent continuously republishing, which
is C3's case in one number.

`[beat]` reads faster here than the 600 µs recorded on 2026-04-22 in
`docs/plans/completed/cue-authoring-unification-plan.md`. That scenario's code is unchanged, so
the difference is environment (JDK, JIT, machine state), not a real improvement — which is also
the reason the CI gate wants a variance study before it picks a threshold.

Both wall-clock windows are inherently noisier than the beat ones:
`FxEngine.processWallClockTickSuspend` derives its `deltaMs` from the real
`System.currentTimeMillis()`, while the beat windows are driven by synthetic ticks.

**2026-08-24, selwyn.local, JDK 25** — after sweep item C1 (per-tick target re-expansion). Median
of four runs each side, captured back to back in one sitting; the block above is the matching
"before" median, not the single run recorded under C0.

```
[beat]       p50=319µs  (was 319µs)   allocBytes/tick=1108245  (was 1112064)
[wall]       p50=321µs  (was 316µs)   allocBytes/tick=1100577  (was 1104600)
[chase-beat] p50=964µs  (was 1023µs)  allocBytes/tick=3354364  (was 3440994)
[chase-wall] p50=263µs  (was 329µs)   allocBytes/tick=1039159  (was 1139773)
[crossfade]  p50=740µs  (was 781µs)   allocBytes/frame=no measurable change
```

Read the per-tick allocation column, not the percentiles: across the four runs each side it
varied by ~240 bytes on `[chase-beat]` and ~2.7 kB on `[chase-wall]`, while p50 on unchanged code
moves by tens of µs. `[beat]`/`[wall]` are flat, as they should be — single-fixture `SliderTarget`
rigs have no group expansion to cache, so they only confirm the validity check costs nothing.

`[crossfade]`'s **per-frame** allocation is the one figure to distrust: it bounced between 1.31 MB
and 1.41 MB across six runs on *both* sides of the change, so the medians say nothing. Its p50 did
move. If C3 wants an allocation signal out of this scenario it needs a quieter measurement first.

`[chase-beat]` moving ~6 % is the honest size of C1 on its own, not a disappointment. That
scenario's cost is dominated by two things C1 deliberately did not touch:
`DistributionStrategy.RANDOM` allocating a `java.util.Random` plus a boxed permutation **per
member per call, twice per member** (~2.4 MB of the 3.35 MB/tick, C6), and the reflective
property resolution on the colour write path (C2). C1 removed the register lookups and the
per-tick list rebuilds around those, which is what `[chase-wall]` — no RANDOM effect — shows at
−20 %.

Two knock-ons for later items. C1 also cut `fixtureHasProperty` from twice per effect per tick to
twice per rebuild, so **C2's measured win will read smaller than its sweep entry predicts** — part
of it has already been taken here. And C3's entry cites the per-crossfade-frame
`resolveEffectFixtureKeys` walk as part of the cost; that walk is now cached, so C3 should be
re-measured rather than implemented against the old premise.

*(The first of those two predictions was wrong, and instructively so — see the C2 block below.)*

**2026-08-24, selwyn.local, JDK 25** — sweep item C2 (reflective property access). Median of four
runs per stage, all captured back to back in one sitting. `[colour-beat]` is new in this round, so
its "before" is the same session, not a historical figure.

```
                  before        C2a (catalogue)   C2b (indexed lookups)
[beat]        p50 311µs         96.5µs   −69%     97.5µs      —
              alloc 1 107 767   355 020  −68%     352 449     —
[wall]        p50 311.5µs       107µs    −66%     93µs        —
              alloc 1 100 544   346 745  −68%     343 922     —
[colour-beat] p50 356µs         203µs    −43%     241.5µs     — (noise, see below)
              alloc 1 099 724   719 647  −35%     712 227     −1 %
[chase-beat]  p50 965µs         948.5µs  —        961µs       —
              alloc 3 354 327   3 377 335 —       3 354 404   —
[chase-wall]  alloc 1 038 581   1 069 362 —       1 040 671   —
[crossfade]   p50 734.5µs       643.5µs  −12%     594µs       −8 %
              alloc 1 353 139   1 052 023 −22%    1 020 553   −3 %
```

**The win is the wrap, not the scan the item was named after.** `Fixture` computed
`fixtureProperties` and its `@FixtureType` in *instance* initializers, and `FixturesWithTransaction`
binds a fixture to a tick's transaction by constructing a new instance — so every touched fixture
re-ran a full `memberProperties` scan 50×/s. `wrappedFixtureCache` deduped within a tick and
therefore hid this completely from anyone reading that code. Hoisting the scan to a per-class
`FixturePropertyCatalogue` took two thirds of `[beat]`'s entire per-tick allocation.

**C2b — replacing the five `fixtureProperties.find {}` scans with indexed lookups — did not
move anything measurable**, and is recorded here as a null result rather than dropped. A linear
scan over nine properties is simply not where the time was; `[colour-beat]`'s p50 varied 171–270 µs
across the four runs, which swamps it entirely. The change was kept for the O(1) lookup and because
it removes the last linear scans from the tick, not on the strength of a number. The gated
follow-on (`KProperty1.call` → `.get`) was **not** done: C2b's null result is good evidence the
remaining cost is not in that resolution either.

`[chase-beat]`/`[chase-wall]` staying flat is a *passing check*, not a disappointment — it is what
falsifies the model if it fails. That rig drives `RgbwPixel`, a `FixtureElement`, which does not
extend `Fixture`; it constructs no `Fixture` per tick and its elements were already served by a
per-class catalogue. Its 3.35 MB/tick remains C6's Fisher-Yates. Scenario 2's KDoc claimed to
measure C2 and was corrected in the same change.

`[crossfade]`'s allocation **is** readable this time, unlike under C1. The before and after ranges
are disjoint (1.309–1.400 MB vs 1.016–1.055 MB) rather than overlapping inside one noise band, so
the ~22 % drop is real — it is the same 168 `HexFixture` wraps, paid per frame. C3 should be
re-measured against this block, not the C1 one.

**2026-08-26, selwyn.local, JDK 25** — sweep item C3 (crossfade republish). Median of four runs
each side, captured back to back in one sitting. The re-measured "before" (left column) confirmed
the item's premise was only partly spent by C1/C2: `[crossfade]` still ran the winner-set resolve,
the effect-coverage walk and a duplicate flat `state` map per frame.

```
                  before          after C3
[beat]        alloc 350 837       351 141    —
[wall]        alloc 341 370       341 389    —
[chase-beat]  alloc 2 191 780     2 196 535  —
[chase-wall]  alloc 1 040 916     1 040 382  —
[crossfade]   alloc 1 033 168     900 663    −12.8%
[colour-beat] alloc 704 795       700 786    —
```

The −12.8 % on `[crossfade]`'s per-frame allocation is the honest size of C3's cuts (winner maps
carried forward on weight ticks, effect coverage cached on a mutation-bumped epoch, the flat
`Key`-keyed composition map made lazy, plus the review round's `applySpecificity` fast path and
compound-`Key` removals) — and the figure is *readable*: the before runs varied by 93 bytes, the
after runs by 14 kB, nothing like the 100 kB band C1 saw. Every other scenario is flat, as it
should be — none of them tick fade weights.

**The timing columns from this capture are deliberately not recorded.** The after-runs' p50
varied 485–975 µs on identical `[crossfade]` code (and the *unchanged* scenarios drifted the same
way — `[chase-wall]` p99 spiked to 11.5 ms), so that column measured the machine, not the change.
An earlier same-sitting capture with quieter timings read `[crossfade]` p50 as flat (612 → 608 µs):
the frame's remaining time is what a weight tick genuinely must redo — recomposing 336 keys and
writing ~168 moved colour fallbacks — which is C6's target, not C3's. The half of C3 no
single-threaded benchmark can see is the lock: everything the item removed ran inside
the Layer 4 publish lock (now `CascadePublisher`'s), which programmer writes contend for.

Two capture notes. Absolute numbers this session are ~40 % below the 2026-08-24 session across
every scenario, unchanged code included — the same environment effect the C0 block warns about.
And the crossfade rig runs its `FxEngine` without `start()`, so the provenance emit computes
provenance *synchronously inside every measured frame* rather than coalescing as it does live —
`[crossfade]` numbers include a per-frame provenance recompute a real desk pays ~20×/s at most.

**2026-08-26 — sweep item C4 (template colour-cache versioning): no comparison taken, and why.**
C4's cost lives on the *invalidation* path — a template edit re-resolving `tmpl:` colour parameters,
and the DB read that resolve opens — and none of the five scenarios has a `tmpl:` parameter in it.
The harness's effects are the frozen `testsupport/TestEffects.kt` copies, which do not go through
`TypedParams` at all, so nothing here calls `colourSourceVersion` once, let alone 50×/s. A
before/after pair would have measured run-to-run noise and then sat in this section looking like
evidence. What C4 would need is a sixth scenario — effects with template-referencing colour
parameters, ticking across a template edit — which nobody has needed enough to build; its unit
coverage is the load-count assertions in `TemplateColourSourceTest`, and its rig behaviour is
`FU-MANUAL-FX-TEMPLATE-COLOUR` step 4.

**2026-08-26 — sweep item C5 (timed-layer fires, `CueApplyData` builders, Look-edit republish): no
comparison taken, and why.** Every cost C5 removes sits off the FX tick — a timed layer's fire
coroutine, the DB reads a route thread pays building `CueApplyData`, and the per-cue transaction a
Look edit used to open. `FxEngineBenchmark` drives `FxEngine` tick and crossfade-frame paths
directly and never activates a cue through `CueStackManager`, so all five scenarios are structurally
blind to it: a before/after pair would have measured run-to-run noise. The unit coverage is
`CueApplyDataBuilderTest` (the one builder carries every field, and GO and standalone apply agree)
and `TimedLayerFireCookTest` (a Look edit between two fires survives the next one — the memo's
staleness hazard). Measuring C5 properly would need a scenario that GOes a cue holding a recurring
timed layer and counts queries, not µs.

**2026-08-26, selwyn.local, JDK 25** — sweep item C6 (per-tick allocation bundle). Median of
**eight** runs each side, all in one sitting; the "before" side ran from a `git worktree` at the
same commit rather than a stash, so the working tree was never disturbed.

```
             p50                      allocBytes/tick
[beat]        98 →  101 µs      349,245 →  319,079   (-8.6%)
[wall]        98 →   92 µs      341,233 →  309,562   (-9.3%)
[chase-beat] 640 →  360 µs    2,191,854 →  921,775  (-57.9%)
[chase-wall] 274 →  329 µs    1,038,583 →  925,855  (-10.9%)
[crossfade]  556 →  494 µs      908,334 →  748,256  (-17.6%)   per *frame*
[colour-beat] 224 → 220 µs      720,396 →  671,705   (-6.8%)
```

`[chase-beat]` is the headline: its per-tick allocation drops by well over half, because that
scenario is the one with 240 elements per group and it was building a synthetic
`DistributionMemberInfo` *and* an `EffectContext` for every one of them on every tick. Caching
both per (expansion, strategy) removes essentially all of it. `[crossfade]`'s 160 kB is the row
copies and the re-cook a weight tick no longer pays.

**`[chase-wall]`'s p50 went the wrong way and is not explained.** Eight runs a side puts the
medians at 274 → 329 µs while its allocation falls 11%, and the two sides' ranges (264–327 before,
255–459 after) overlap heavily. It is the least trustworthy window in the suite — 500 ticks rather
than 1200, so the least JIT-warmed, and its p99 swings 20× run to run. The obvious hypothesis, that
the now-long-lived `EffectContext` array is read from old-gen memory instead of a hot TLAB, does
not survive `[chase-beat]` improving 44% on the same structure over twice as many elements. Left
recorded rather than explained; if it matters it needs the quieter measurement `FU-TEST-FX-BENCH-CI-GATE`
is waiting on anyway.

Both `[beat]` and `[wall]` move about 9% on allocation despite being single-fixture `SliderTarget`
rigs with no group expansion at all — that is the `resetActiveProperties` scratch reuse, which is
the only part of C6 those two scenarios can see.

**2026-08-26, selwyn.local, JDK 25** — sweep item C7 (`emitStateUpdate` makes cue apply O(N²)).
Both sides in one run of the new `cue spawn cost` scenario: 168 effects put up on a fresh engine,
8 samples a side after 3 warmups, engine and instances built outside the timed window.

```
              p50        p99        mean       allocBytes/spawn
[spawn-each]  8,519 µs   14,724 µs  8,704 µs   7,353,990
[spawn-batch]   106 µs      163 µs    112 µs     162,843
```

~80× on p50 and ~45× on allocation, for the 168-effect case. The shape is the point rather than
the multiplier: `[spawn-each]` is quadratic, so the factor grows with the cue — every `addEffect`
re-sorted the snapshots *and* rebuilt an `FxInstanceState` for every effect already up, each with
a group / multi-element lookup. A batched add pays that once.

The five tick scenarios are structurally blind to this and were confirmed flat; their numbers from
this session are not recorded, because `--rerun-tasks` compile load was running alongside them and
their p99s (`[chase-wall]` 19.8 ms) measured the machine. C7 does not touch a tick path — `insert`
does exactly what `addEffect`'s body did, and the one rebuild still lands before anything the
calling flow publishes.
