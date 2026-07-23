# Show & Script Execution Engineering Documentation

This document describes the show orchestration system and embedded Kotlin script execution engine.

## Overview

The Show system orchestrates:
- Script compilation with caching
- FX engine lifecycle
- Cue trigger script pre-warming

Scripts are written in Kotlin and compiled at runtime using the Kotlin Scripting API, then cached by content hash.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Show                                       │
│                                                                         │
│  ┌─────────────────┐  ┌─────────────────────────┐                       │
│  │   FX Engine     │  │   Cue Trigger Scripts   │                       │
│  │   Lifecycle     │  │     (pre-warmed)        │                       │
│  └────────┬────────┘  └────────────┬────────────┘                       │
│           │                        │                                    │
│           └────────────────────────┘                                    │
│                                ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                        Script Cache                               │  │
│  │                  (keyed by SHA-256 hash)                          │  │
│  │                                                                   │  │
│  │   "scriptName-type-a1b2c3..." → Script (compiled)                 │  │
│  └──────────────────────────────┬────────────────────────────────────┘  │
│                                 │                                       │
│                                 ▼                                       │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                         Script                                    │  │
│  │                                                                   │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │              Kotlin Scripting Host                       │    │  │
│  │   │                                                          │    │  │
│  │   │   Source → Compiler → CompiledScript                     │    │  │
│  │   │              (compilerPool thread)                       │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                                                                         │
│                                 ▼                                       │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                       ScriptRunner                                │  │
│  │                     (runnerPool thread)                           │  │
│  │                                                                   │  │
│  │   ┌──────────────────────────────────────────────────────────┐    │  │
│  │   │  Evaluator executes CompiledScript with:                 │    │  │
│  │   │    - fixtures (with transaction for GENERAL)              │    │  │
│  │   │    - fxEngine (for FX_APPLICATION)                        │    │  │
│  │   │    - step counter                                        │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  │                                                                   │  │
│  │   After execution: transaction.apply() (GENERAL scripts only)     │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Core Types

### Show

Main orchestrator class:

```kotlin
class Show(
    val state: State,                    // Database connection
    val project: DaoProject,             // Active project
)
```

**Thread pools:**
- `runnerPool`: Single-thread executor for script evaluation
- `compilerPool`: Single-thread executor for compilation

**Key methods:**
- `start()`: Initialize fixtures, start FX engine, pre-warm cue scripts
- `evalScriptByName(name)`: Execute a named script from the database
- `compileLiteralScript(script, type)`: Compile without executing
- `runLiteralScript(script, ...)`: Compile and execute a literal script

### Script

Compiled script with caching:

```kotlin
class Script(
    val show: Show,
    val scriptName: String,
    val literalScript: String,
    val scriptType: ScriptType = ScriptType.GENERAL,
) {
    val compiledResult: ResultWithDiagnostics<CompiledScript>
    val compileStatus: ScriptResult
}
```

Scripts are cached by a key combining name, type, and SHA-256 hash of content:
```kotlin
val scriptKey = "$scriptName-$scriptType-${literalScript.cacheKey()}"
```

This means:
- Same script content → reuse compiled result
- Changed script content → recompile (new hash)

### ScriptRunner

Executes a compiled script:

```kotlin
class ScriptRunner(
    val show: Show,
    script: Script,
    step: Int = 0,           // Loop iteration counter
    scriptId: Int? = null,   // DB script ID (for FX_DEFINITION registration)
)
```

**Execution flow (varies by ScriptType):**

**GENERAL:**
1. Create `ControllerTransaction` for batched DMX updates
2. Wrap fixtures with transaction
3. Launch coroutine on `runnerPool`
4. Evaluate compiled script with provided properties
5. Apply transaction (send DMX values)

**FX_APPLICATION:**
1. Launch coroutine on `runnerPool`
2. Evaluate with `show`, `fxEngine`, `scriptName`, `step`

**FX_DEFINITION:**
1. Launch coroutine on `runnerPool`
2. Evaluate with `show`, `scriptName`, `scriptId`

**FX_CALC / FX_CALC_STATEFUL / FX_CALC_COMPOSITE:**
1. Launch coroutine on `runnerPool`
2. Evaluate with type-specific parameters (phase, context, params, etc.)

## Script Types

Scripts have a `ScriptType` enum stored in the database:

| Type | Base Class | Purpose |
|------|-----------|---------|
| `GENERAL` | `LightingScript` | Full-power: DMX, fixtures, FX, coroutines |
| `FX_APPLICATION` | `FxApplicationScript` | Apply effects to fixtures |
| `FX_DEFINITION` | `FxDefinitionScript` | Register custom effect types |
| `FX_CALC` | `FxCalcScript` | Pure phase-based effect calculation |
| `FX_CALC_STATEFUL` | `FxStatefulCalcScript` | Tick-based effect with internal state |
| `FX_CALC_COMPOSITE` | `FxCompositeCalcScript` | Multi-output effect calculation |

### Script Wrapping

Scripts are wrapped differently based on type:
- **GENERAL**: Wrapped in `runBlocking { ... }` for coroutine support
- **FX_APPLICATION / FX_DEFINITION**: Run as-is
- **FX_CALC variants**: Wrapped in a lambda expression

## Script DSL

### LightingScript (GENERAL)

```kotlin
abstract class LightingScript(
    private val show: Show,
    val fixtures: Fixtures.FixturesWithTransaction,
    val scriptName: String,
    val step: Int,
    val coroutineScope: CoroutineScope,
)
```

**Helper methods:**
```kotlin
fun controller(subnet: Int, universe: Int): DmxController
inline fun <reified T: Fixture> fixture(key: String): T
inline fun <reified T: Fixture> group(key: String): FixtureGroup<T>
```

### Automatic Imports

Scripts automatically import:
```kotlin
uk.me.cormack.lighting7.fixture.*
uk.me.cormack.lighting7.fixture.dmx.*
uk.me.cormack.lighting7.fixture.hue.*
java.awt.Color
uk.me.cormack.lighting7.dmx.*
kotlinx.coroutines.*
```

### Example Script

```kotlin
// Access fixtures by key
val frontWash = fixture<HexFixture>("front-wash")
val backLight = fixture<HexFixture>("back-light")

// Set values (queued in transaction)
frontWash.dimmer.value = 255u
frontWash.rgbColour.value = Color.BLUE

// Use fading
backLight.rgbColour.fadeToColour(Color.RED, 2000)

// Use coroutines for timing
delay(1000)
frontWash.rgbColour.value = Color.GREEN
```

## Compilation Details

### Kotlin Scripting Host

```kotlin
val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()
val compiledResult = BasicJvmScriptingHost(state.scriptingHostConfiguration).compiler(
    expandedScript.toScriptSource(),
    compilationConfiguration
)
```

Configuration includes:
- JVM target: 17
- Dependencies: entire classpath
- Base class: varies by `ScriptType`
- Default imports

Every scripting host in the app (`Show.scriptingHost` and the FX-calc
`FxScriptCompiler`) is constructed with the **shared** `state.scriptingHostConfiguration`
(built by `buildScriptingHostConfiguration` in `state/ScriptCache.kt`), so they share the
persistent compilation cache below.

### Persistent compilation cache (on disk)

Runtime script compilation — 28 built-in FX effects, cue-trigger scripts, user effects — is
the dominant start-up cost. `buildScriptingHostConfiguration` installs a
`CompiledScriptJarsCache` under `<appDataDir>/script-cache` (override with `scriptCache.path`;
disable with `scriptCache.enabled=false`). The first compile of each distinct script writes a
`<hash>.jar`; every later boot — and every project switch within a run — loads the compiled
bytecode instead of recompiling from source.

The jar file name is a SHA-256 of the script source, the template base class, and a **build
fingerprint** of the classpath, computed once at startup. Because all templates compile with
`dependenciesFromCurrentContext(wholeClasspath = true)`, a compiled script links against the
app's own classes, so the fingerprint must change on any rebuild. For **file** classpath entries
(jars — the packaged case) it folds in each entry's mtime + size; for **directory** entries (the
`build/classes/kotlin/main` dir under `gradle run`, where rebuilds actually happen) it folds in
the *newest* last-modified time among all contained files — a nested `.class` rebuild bumps that
even though the directory entry's own mtime does not. This prevents stale bytecode from linking
against a class that has since changed shape (which would blow up with `NoSuchMethodError`).

This on-disk cache is distinct from the two in-memory caches (`Show.scripts`,
`FxScriptCompiler.cache`), which avoid re-evaluation within a single process; the disk cache
adds cross-process, cross-`Show` persistence at the compile layer.

### Evaluation (GENERAL scripts)

```kotlin
BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
    providedProperties(Pair("show", show))
    providedProperties(Pair("fixtures", fixturesWithTransaction))
    providedProperties(Pair("fxEngine", show.fxEngine))
    providedProperties(Pair("scriptName", script.scriptName))
    providedProperties(Pair("step", step))
    providedProperties(Pair("coroutineScope", this@launch))
})
```

## Thread Safety

| Resource | Protection | Notes |
|----------|------------|-------|
| `scripts` cache | `ReentrantLock` | Serialize cache access |
| Script execution | `runnerPool` | Single-threaded to avoid conflicts |
| Compilation | `compilerPool` | Single-threaded |

## Error Handling

### Compilation Errors

Captured in `ScriptResult.compileResult`:
```kotlin
val compileStatus = ScriptResult(compiledResult)
// Check compileStatus.compileResult for diagnostics
```

### Runtime Errors

Captured in `ScriptResult.runResult`:
```kotlin
result = ScriptResult(compiledResult, runResult, channelChanges)
```

## Data Model

### Scripts Table

```kotlin
object DaoScripts : IntIdTable("scripts") {
    val name = varchar("name", 255)
    val script = text("script")           // Kotlin source code
    val project = reference("project_id", DaoProjects)
    val scriptType = enumerationByName<ScriptType>("script_type", 50)
}
```

## File Reference

| File | Purpose |
|------|---------|
| `show/Show.kt` | Main orchestrator, Script, ScriptRunner classes |
| `scripts/scriptDef.kt` | LightingScript base class and configuration |
| `scripts/fxApplicationScriptDef.kt` | FxApplicationScript base class |
| `scripts/fxDefinitionScriptDef.kt` | FxDefinitionScript base class |
| `scripts/fxCalcScriptDef.kt` | FxCalcScript base classes |
| `models/scripts.kt` | Script database entity |

## Startup Sequence

### Server-first boot

`Application.module()` mounts the web server, routes, sockets, and static UI **first**, then
runs show init on a background coroutine (`show.backgroundInit=true`, the default). This lets
the loading UI and `GET /api/rest/status` serve immediately while the slow work proceeds. Set
`show.backgroundInit=false` for the legacy order, where the server refuses all connections
until the rig is fully live.

During the warm-up window:
- Show-dependent `/api/rest/*` routes return **503** with the current boot status (the
  readiness gate in `configureRouting`); `/api/rest/status` is exempt.
- The `/api` WebSocket streams `bootProgressState` messages and holds show-dependent
  subscription setup until the show exists.
- `State.bootProgress` (a `BootStatus` `StateFlow`) advances through phases
  `SHOW_INIT → FX_COMPILE → FIXTURES → CUE_PREWARM → READY` (or `FAILED`), with a `percent`
  for the loading bar. FX-compile progress is reported per-effect by `FxFileLoader`.

### Show init steps

1. `Show` constructor compiles the built-in FX effects (parallel unless
   `fx.parallelCompile=false`), served from the disk cache after the first boot
2. `Show.start()` called
3. `DbFixtureLoader.loadFixtures()` registers controllers and fixtures from DB patches
4. Load and apply parked channels
5. Start the FX engine
6. Load user-created FX definitions from database
7. Pre-compile cue trigger scripts to avoid cold-start latency (always runs; cheap after the
   first boot via the on-disk compiled-script cache)
