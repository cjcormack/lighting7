# Scenes & Chases Engineering Documentation

This document describes the scene and chase systems for recording and recalling lighting states.

## Overview

- **Scene**: A snapshot of DMX channel values that can be recalled
- **Chase**: A continuously running script that produces dynamic effects

Both are stored in the database and associated with a script. The difference is in how they're executed and tracked.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                            REST API / UI                                │
│                                                                         │
│   POST /scene/{id}/run  ─────────────────────────────────────────────┐ │
│                                                                       │ │
└───────────────────────────────────────────────────────────────────────┼─┘
                                                                        │
                                                                        ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                                 Show                                      │
│                                                                           │
│   runScene(id)                                                            │
│       │                                                                   │
│       ▼                                                                   │
│   ┌─────────────────────────────────────────────────────────────────┐    │
│   │                       ScriptRunner                               │    │
│   │                                                                  │    │
│   │   1. Load scene from database                                    │    │
│   │   2. Get script + settings                                       │    │
│   │   3. Execute script with transaction                             │    │
│   │   4. Apply transaction (DMX output)                              │    │
│   │   5. Record based on mode:                                       │    │
│   │                                                                  │    │
│   │      ┌─────────────────┐     ┌─────────────────────────┐        │    │
│   │      │   Mode.SCENE    │     │      Mode.CHASE         │        │    │
│   │      │                 │     │                         │        │    │
│   │      │ recordScene()   │     │ recordChaseStart()      │        │    │
│   │      │ (channel values)│     │ (mark as running)       │        │    │
│   │      │                 │     │ ...script runs...       │        │    │
│   │      │                 │     │ recordChaseStop()       │        │    │
│   │      └─────────────────┘     └─────────────────────────┘        │    │
│   └─────────────────────────────────────────────────────────────────┘    │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
                                        │
                                        ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                              Fixtures                                     │
│                                                                           │
│   ┌─────────────────────────────────────────────────────────────────┐    │
│   │                    Active Scene Tracking                         │    │
│   │                                                                  │    │
│   │   activeScenes: Map<sceneId, Map<Universe, Map<channelNo, value>>>   │
│   │   activeChases: Map<sceneId, Boolean>                            │    │
│   │                                                                  │    │
│   │   When channel values change:                                    │    │
│   │     → Check if any activeScene values no longer match            │    │
│   │     → Remove from activeScenes if mismatch                       │    │
│   │     → Notify listeners (sceneChanged)                            │    │
│   └─────────────────────────────────────────────────────────────────┘    │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

## Data Model

### Mode Enum

```kotlin
enum class Mode {
    SCENE,  // One-shot, records final channel values
    CHASE,  // Continuous, tracks running state only
}
```

### Scene Entity

```kotlin
object DaoScenes : IntIdTable("scenes") {
    val name = varchar("name", 255)
    val script = reference("script_id", DaoScripts)
    val project = reference("project_id", DaoProjects)
    val settingsValues = json<Map<String, IntValue>>("settings_values", Json).nullable()
    val mode = enumerationByName("mode", 50, Mode::class).default(Mode.SCENE)
}
```

Key relationships:
- Scene → Script (many scenes can use same script with different settings)
- Scene → Project (scoped to project)

## Scene Execution Flow

### 1. Start Scene

```kotlin
fun startScene(id: Int): ScriptRunner {
    // Load from database
    val (scene, scriptData) = transaction(state.database) {
        val scene = DaoScene.findById(id)
        val scriptData = ScriptData(
            scene.name,
            scene.script.name,
            scene.script.script,
            scene.script.settings?.list.orEmpty(),
            scene.settingsValues.orEmpty()
        )
        Pair(scene, scriptData)
    }

    // Get or compile script
    val script = script(scriptName, scriptBody, scriptSettings)

    // Create runner with scene context
    return ScriptRunner(
        this,
        script,
        scene,
        sceneIsActive = fixtures.isSceneActive(id),
        settingsValues = sceneSettingsValues,
    )
}
```

### 2. Script Execution

Inside `ScriptRunner`:

```kotlin
// For chases, mark as started
if (scene != null && scene.mode == Mode.CHASE) {
    show.fixtures.recordChaseStart(scene.id.value)
}

// Execute script
job = CoroutineScope(show.runnerPool).launch {
    val runResult = BasicJvmScriptingHost().evaluator(compiledScript, ...)

    // Apply DMX changes
    val actualChannelChanges = transaction.apply()

    // Record based on mode
    if (scene != null) {
        when (scene.mode) {
            Mode.SCENE -> {
                show.fixtures.recordScene(scene.id.value, channelChanges)
            }
            Mode.CHASE -> {
                show.fixtures.recordChaseStop(scene.id.value)
            }
        }
    }
}
```

### 3. Scene Recording

```kotlin
fun recordScene(sceneId: Int, changeDetails: Map<Universe, Map<Int, UByte>>) {
    activeScenesLock.write {
        if (changeDetails.isEmpty()) {
            activeScenes.remove(sceneId)
        } else {
            activeScenes[sceneId] = changeDetails
        }
    }
    sceneChanged(sceneId)  // Notify listeners
}
```

## Active Scene Tracking

The system tracks which scenes are currently "active" - meaning their recorded values match the current DMX output.

### Recording Active State

When a scene runs:
```kotlin
activeScenes[sceneId] = mapOf(
    Universe(0, 0) to mapOf(
        1 to 255u,   // Channel 1 = 255
        2 to 128u,   // Channel 2 = 128
        // ...
    ),
    // Other universes...
)
```

### Auto-Deactivation

When DMX channels change (from any source), the system checks if active scenes are still valid:

```kotlin
// In channelChangeHandlerForController
override fun channelsChanged(changes: Map<Int, UByte>) {
    // Find scenes whose values no longer match
    val scenesToUnset = activeScenesLock.read {
        activeScenes.filterValues { sceneChannels ->
            sceneChannels[controller.universe]?.any { (channelNo, sceneValue) ->
                val changeValue = changes[channelNo]
                changeValue != null && changeValue != sceneValue
            } ?: false
        }.keys
    }

    // Remove mismatched scenes
    if (scenesToUnset.isNotEmpty()) {
        activeScenesLock.write {
            scenesToUnset.forEach {
                activeScenes.remove(it)
                sceneChanged(it)
            }
        }
    }
}
```

### Checking Active Status

```kotlin
fun isSceneActive(sceneId: Int): Boolean = activeScenesLock.read {
    activeScenes.containsKey(sceneId) || activeChases.getOrDefault(sceneId, false)
}
```

A scene is active if:
- It's in `activeScenes` (values match), OR
- It's a chase in `activeChases` marked as running

## Chase Handling

Chases differ from scenes in tracking:

```kotlin
fun recordChaseStart(sceneId: Int) {
    activeScenesLock.write {
        activeChases[sceneId] = true
    }
    sceneChanged(sceneId)
}

fun recordChaseStop(sceneId: Int) {
    activeScenesLock.write {
        activeChases[sceneId] = false
    }
    sceneChanged(sceneId)
}
```

Chases don't track channel values because they continuously change. They're simply marked as "running" or "not running".

## Scene Settings

Scenes can override script settings:

```kotlin
// Script defines available settings
val settings = listOf(
    IntSetting("intensity", minValue = IntValue(0), maxValue = IntValue(100), defaultValue = IntValue(50)),
    IntSetting("speed", defaultValue = IntValue(10)),
)

// Scene provides specific values
val sceneSettings = mapOf(
    "intensity" to IntValue(75),
    "speed" to IntValue(20),
)
```

This allows one script to power multiple scenes with different configurations.

## REST API

### List Scenes

```
GET /api/rest/scene/list?mode=SCENE
GET /api/rest/scene/list?mode=CHASE
```

Returns array of scene IDs, ordered by ID descending.

### Get Scene Details

```
GET /api/rest/scene/{id}
```

Response:
```json
{
    "id": 1,
    "mode": "SCENE",
    "name": "Blue Wash",
    "scriptId": 5,
    "isActive": true,
    "settingsValues": {
        "intensity": { "int": 75 }
    }
}
```

### Create Scene

```
POST /api/rest/scene/
```

Body:
```json
{
    "mode": "SCENE",
    "name": "Blue Wash",
    "scriptId": 5,
    "settingsValues": {
        "intensity": { "int": 75 }
    }
}
```

### Update Scene

```
PUT /api/rest/scene/{id}
```

Same body as create.

### Delete Scene

```
DELETE /api/rest/scene/{id}
```

### Run Scene

```
POST /api/rest/scene/{id}/run
```

Executes the scene and waits for completion. Returns run result.

## Notifications

The `Fixtures` class notifies listeners of scene changes:

```kotlin
interface FixturesChangeListener {
    fun sceneListChanged()        // Scene added/removed
    fun sceneChanged(id: Int)     // Scene active status changed
    // ...
}
```

These propagate to WebSocket clients for real-time UI updates.

## Scene vs Chase: When to Use

| Use Case | Mode | Why |
|----------|------|-----|
| Static look (all blue) | SCENE | Values recorded, can detect if still active |
| Color chase effect | CHASE | Continuous changes, just track running state |
| Fade to black | SCENE | Final state is what matters |
| Sound-reactive | CHASE | Values constantly changing |
| Strobe effect | CHASE | Rapid changes, no stable state |

## Custom Channel Changes

Scripts can override what gets recorded:

```kotlin
// In script execution
val fixturesWithTransaction = show.fixtures.withTransaction(transaction)

// Script can set custom changes to record
fixturesWithTransaction.customChangedChannels = mapOf(
    Universe(0, 0) to mapOf(1 to 255u)
)
```

This is useful when a script makes many intermediate changes but only certain values should define the scene's "active" state.

## Thread Safety

| Resource | Protection |
|----------|------------|
| `activeScenes` | `ReentrantReadWriteLock` (activeScenesLock) |
| `activeChases` | `ReentrantReadWriteLock` (activeScenesLock) |
| Scene execution | Serialized on `runnerPool` |
| Database access | Exposed transactions |

## File Reference

| File | Purpose |
|------|---------|
| `models/scenes.kt` | Scene entity and Mode enum |
| `show/Show.kt` | Scene execution (startScene, runScene, stopScene) |
| `show/Fixtures.kt` | Active scene tracking and notifications |
| `routes/lightScenes.kt` | REST API endpoints |
