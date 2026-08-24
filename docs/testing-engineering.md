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
./gradlew :test --tests "…CueEditProfileTest" -Dcueedit.profile=true
```

Note `:test` rather than `test`: the latter also runs `:launcher:test`, which fails the
`--tests` filter with "No tests found for given includes".
