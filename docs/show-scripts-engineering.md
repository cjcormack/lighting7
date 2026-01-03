# Show & Script Execution Engineering Documentation

This document describes the show orchestration system and embedded Kotlin script execution engine.

## Overview

The Show system orchestrates:
- Script compilation with caching
- Scene execution (one-shot or continuous chase)
- Run loop for continuous effects
- Music track change handling

Scripts are written in Kotlin and compiled at runtime using the Kotlin Scripting API, then cached by content hash.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              Show                                       │
│                                                                         │
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐  │
│  │   Run Loop      │  │  Track Changed  │  │     Scene Execution     │  │
│  │   (ticker)      │  │    Handler      │  │                         │  │
│  └────────┬────────┘  └────────┬────────┘  └────────────┬────────────┘  │
│           │                    │                        │               │
│           └────────────────────┼────────────────────────┘               │
│                                ▼                                        │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │                        Script Cache                               │  │
│  │                  (keyed by SHA-256 hash)                          │  │
│  │                                                                   │  │
│  │   "scriptName-a1b2c3..." → Script (compiled)                      │  │
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
│  │   │    - fixtures (with transaction)                         │    │  │
│  │   │    - settings                                            │    │  │
│  │   │    - step counter                                        │    │  │
│  │   │    - scene context                                       │    │  │
│  │   └──────────────────────────────────────────────────────────┘    │  │
│  │                                                                   │  │
│  │   After execution: transaction.apply() → record scene             │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

## Core Types

### Show

Main orchestrator class:

```kotlin
class Show(
    val state: State,                    // Database connection
    val projectName: String,             // Active project
    val loadFixturesScriptName: String,  // Script to define fixtures
    val initialSceneName: String,        // Scene to run on startup
    val runLoopScriptName: String?,      // Optional continuous loop script
    val trackChangedScriptName: String?, // Optional music trigger script
    val runLoopDelay: Long,              // Loop interval in ms
)
```

**Thread pools:**
- `runnerPool`: Single-thread executor for script evaluation
- `compilerPool`: Single-thread executor for compilation

**Key methods:**
- `start()`: Initialize fixtures and start run loop
- `runScene(id/name)`: Execute scene and wait for completion
- `startScene(id/name)`: Execute scene asynchronously
- `stopScene(id)`: Cancel running scene
- `evalScriptByName(name)`: Execute a standalone script
- `trackChanged(details)`: Handle music track change

### Script

Compiled script with caching:

```kotlin
class Script(
    val show: Show,
    val scriptName: String,
    val literalScript: String,
    val settings: List<ScriptSetting<*>>,
) {
    val compiledResult: ResultWithDiagnostics<CompiledScript>
    val compileStatus: ScriptResult
}
```

Scripts are cached by a key combining name and SHA-256 hash of content:
```kotlin
val scriptKey = "$scriptName-${literalScript.cacheKey()}"
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
    val scene: DaoScene? = null,        // Associated scene (if any)
    step: Int = 0,                       // Loop iteration counter
    sceneIsActive: Boolean = false,      // Is scene currently applied?
    settingsValues: Map<String, ScriptSettingValue> = emptyMap()
)
```

**Execution flow:**
1. Cancel any previous runner for this scene
2. Create `ControllerTransaction` for batched DMX updates
3. Wrap fixtures with transaction
4. Launch coroutine on `runnerPool`
5. Evaluate compiled script with provided properties
6. Apply transaction (send DMX values)
7. Record scene state (for SCENE mode) or stop chase (for CHASE mode)

## Script DSL

### LightingScript Base Class

All scripts extend this base class:

```kotlin
abstract class LightingScript(
    private val show: Show,
    val fixtures: Fixtures.FixturesWithTransaction,
    val scriptName: String,
    val step: Int,              // Loop iteration (0 for one-shot)
    val sceneName: String,      // Empty if not a scene
    val sceneIsActive: Boolean, // True if scene values match current state
    val settings: Map<String, String>,
    val coroutineScope: CoroutineScope,
    val currentTrack: TrackDetails?,
)
```

**Helper methods:**
```kotlin
fun controller(subnet: Int, universe: Int): DmxController
inline fun <reified T: Fixture> fixture(key: String): T
inline fun <reified T: Fixture> group(key: String): FixtureGroup<T>
fun runScene(sceneName: String)   // Blocking
fun startScene(sceneName: String) // Non-blocking
```

### Automatic Imports

Scripts automatically import:
```kotlin
uk.me.cormack.lighting7.fixture.*
uk.me.cormack.lighting7.fixture.dmx.*
uk.me.cormack.lighting7.fixture.hue.*
uk.me.cormack.lighting7.scriptSettings.*
java.awt.Color
uk.me.cormack.lighting7.dmx.*
kotlinx.coroutines.*
```

### Script Wrapping

User scripts are wrapped in `runBlocking`:
```kotlin
runBlocking {
    // User script content here
}
```

This allows use of `delay()` and other suspend functions.

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

// Access settings
val intensity = (settings["intensity"] as? IntValue)?.int ?: 100

// Check scene state
if (sceneIsActive) {
    // Scene is already applied, maybe skip
}

// Trigger other scenes
startScene("chase-1")
```

## Script Settings

Scripts can define configurable parameters:

### ScriptSetting Types

```kotlin
sealed class ScriptSetting<T: ScriptSettingValue> {
    abstract val name: String
    abstract val defaultValue: T?
}

// Integer setting with optional min/max
class IntSetting(
    override val name: String,
    val minValue: IntValue? = null,
    val maxValue: IntValue? = null,
    override val defaultValue: IntValue? = null,
)
```

### Settings Storage

- **Scripts** store setting definitions in `DaoScripts.settings` (JSON)
- **Scenes** store setting values in `DaoScenes.settingsValues` (JSON)

This allows one script to be reused with different settings per scene.

## Execution Modes

### One-Shot Execution

```kotlin
show.evalScriptByName("my-script")
// or
show.runScene("my-scene")
```

Script runs once, applies changes, records state if scene.

### Run Loop

Configured via `runLoopScriptName` and `runLoopDelay`:

```kotlin
// In Show.start()
GlobalScope.launch {
    runShow(runLoopScriptName, runLoopDelay)
}
```

The run loop:
1. Waits for `delay` milliseconds
2. Calls `evalScriptByName(runLoopScriptName, step)`
3. Increments `step` counter
4. Repeats

Scripts can use `step` for animation:
```kotlin
val phase = step % 100
val brightness = (phase * 2.55).toInt().toUByte()
fixture<HexFixture>("led").dimmer.value = brightness
```

### Track Change Trigger

When music track changes:
```kotlin
fun trackChanged(newTrackDetails: TrackDetails) {
    if (trackHasChanged && trackChangedScriptName != null) {
        evalScriptByName(trackChangedScriptName)
    }
    fixtures.trackChanged(isPlaying, artist, title)
}
```

Scripts can access current track:
```kotlin
val track = currentTrack
if (track != null) {
    println("Now playing: ${track.artist} - ${track.title}")
}
```

## Scene vs Chase

### Scene Mode (Mode.SCENE)

After script execution:
```kotlin
show.fixtures.recordScene(scene.id.value, channelChanges)
```

Records the final channel values. Scene is marked "active" if current DMX values match.

### Chase Mode (Mode.CHASE)

Before execution:
```kotlin
show.fixtures.recordChaseStart(scene.id.value)
```

After execution:
```kotlin
show.fixtures.recordChaseStop(scene.id.value)
```

Chase is a continuous sequence - typically uses delays and loops within the script.

## Compilation Details

### Kotlin Scripting Host

```kotlin
val compilationConfiguration = createJvmCompilationConfigurationFromTemplate<LightingScript>()
val compiledResult = BasicJvmScriptingHost().compiler(
    expandedScript.toScriptSource(),
    compilationConfiguration
)
```

Configuration includes:
- JVM target: 17
- Dependencies: entire classpath
- Base class: `LightingScript`
- Default imports

### Evaluation

```kotlin
BasicJvmScriptingHost().evaluator(compiledScript, ScriptEvaluationConfiguration {
    providedProperties(Pair("show", show))
    providedProperties(Pair("fixtures", fixturesWithTransaction))
    providedProperties(Pair("scriptName", script.scriptName))
    providedProperties(Pair("step", step))
    providedProperties(Pair("sceneName", scene?.name ?: ""))
    providedProperties(Pair("sceneIsActive", sceneIsActive))
    providedProperties(Pair("settings", settings))
    providedProperties(Pair("coroutineScope", this@launch))
    providedProperties(Pair("currentTrack", currentTrack))
})
```

## Thread Safety

| Resource | Protection | Notes |
|----------|------------|-------|
| `scripts` cache | `ReentrantLock` | Serialize cache access |
| `runningScenes` | `ReentrantReadWriteLock` | Track active scene runners |
| `currentTrack` | `ReentrantReadWriteLock` | Music state |
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

### Run Loop Errors

Consecutive errors are tracked:
- First error: stack trace printed
- Subsequent errors: suppressed, 10-second backoff
- Loop continues regardless (commented out: bail after 20 errors)

## Data Model

### Scripts Table

```kotlin
object DaoScripts : IntIdTable("scripts") {
    val name = varchar("name", 255)
    val script = text("script")           // Kotlin source code
    val project = reference("project_id", DaoProjects)
    val settings = json<ScriptSettingList>("settings", Json).nullable()
}
```

### Scenes Table

```kotlin
object DaoScenes : IntIdTable("scenes") {
    val name = varchar("name", 255)
    val script = reference("script_id", DaoScripts)
    val project = reference("project_id", DaoProjects)
    val settingsValues = json<Map<String, IntValue>>("settings_values", Json).nullable()
    val mode = enumerationByName("mode", 50, Mode::class).default(Mode.SCENE)
}
```

## File Reference

| File | Purpose |
|------|---------|
| `show/Show.kt` | Main orchestrator, Script, ScriptRunner classes |
| `scripts/scriptDef.kt` | LightingScript base class and configuration |
| `scriptSettings/settings.kt` | ScriptSetting base types |
| `scriptSettings/int.kt` | IntSetting implementation |
| `models/scripts.kt` | Script database entity |
| `models/scenes.kt` | Scene database entity with Mode enum |

## Startup Sequence

1. `Show.start()` called
2. Execute `loadFixturesScriptName` to register controllers and fixtures
3. Execute `initialSceneName` to set initial lighting state
4. If `runLoopScriptName` configured, start run loop coroutine
5. Start ping ticker for track server (every 5 seconds)
