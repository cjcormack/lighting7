# Claude Code Configuration for Lighting7

A professional stage/event lighting control system built in Kotlin using Ktor. Controls physical lighting fixtures through DMX (ArtNet) and Philips Hue.

## Tech Stack

- **Kotlin 1.9.23** on JVM 17
- **Ktor 2.3.9** (web server, WebSockets, REST API)
- **PostgreSQL** with Exposed ORM and HikariCP
- **ArtNet4j** for DMX protocol
- **Kotlin Scripting** for embedded lighting DSL

## Building and Running

```bash
# Build
./gradlew build

# Run (starts REST on :8413)
./gradlew run
```

### Configuration

1. Copy `example.local.conf` to `local.conf`
2. Configure PostgreSQL connection settings
3. Set project name

### Pre-commit checks

This project has no Makefile — the global `make commit-check` rule does not
apply. `./gradlew test` is the equivalent pre-commit check. A recent green
run earlier in the same session is sufficient; you do not need to re-run it
just before `git commit` if nothing has changed since.

## Project Structure

```
src/main/kotlin/uk/me/cormack/lighting7/
├── Application.kt          # Entry point
├── dmx/                    # DMX/ArtNet controllers, easing curves
├── fixture/                # Fixture abstractions
│   ├── dmx/               # Specific DMX fixture types (DmxSlider, DmxColour, fixtures)
│   ├── group/             # Fixture group system
│   │   └── property/      # Group property aggregators (GroupSlider, GroupColour)
│   ├── property/          # Property interfaces (Slider, Colour, Position, Strobe)
│   ├── trait/             # Trait interfaces (WithDimmer, WithColour, etc.)
│   └── hue/               # Philips Hue integration
├── fx/                     # FX (effects) system
│   ├── effects/           # Effect implementations
│   └── group/             # Group FX distribution
├── show/                   # Show orchestration & script runner
├── state/                  # Application state management
├── models/                 # Database entities (projects, scripts, cues, presets)
├── routes/                 # REST API endpoints
├── plugins/                # Ktor plugins (HTTP, WebSockets, Routing)
└── scripts/                # LightingScript DSL definition
```

## Key Concepts

### Fixtures
Fixtures represent physical lighting devices. They use trait-based composition:
- `WithDimmer` - brightness control via `dimmer: Slider`
- `WithColour` - RGB color control via `rgbColour: Colour`
- `WithStrobe` - strobe effects via `strobe: Strobe`
- `WithUv` - UV lighting via `uv: Slider`
- `WithPosition` - pan/tilt control via `pan: Slider`, `tilt: Slider`

Add new fixtures in `fixture/dmx/` by extending the appropriate base classes and traits.

### Property System
Properties provide a unified interface for fixture and group control:

**Property Interfaces** (`fixture/property/`):
- `Slider` - Single value control (dimmer, UV, pan, tilt)
- `Colour` - RGB colour with `redSlider`, `greenSlider`, `blueSlider`
- `Position` - Pan/tilt via `panSlider`, `tiltSlider`
- `Strobe` - Strobe control with `fullOn()`, `strobe(intensity)`

**Aggregate Interfaces** (for groups):
- `AggregateSlider` extends `Slider` - adds `memberValues`, `isUniform`, `minValue`, `maxValue`
- `AggregateColour` extends `Colour` - adds `memberValues`, `isUniform`

**Value Semantics**:
- Single fixtures: `value` always returns the actual value (non-null)
- Groups: `value` returns null if members have different values
```kotlin
group.dimmer.value = 200u        // Sets all members
val level = group.dimmer.value   // null if non-uniform
val uniform = group.dimmer.isUniform
val all = group.dimmer.memberValues  // [200, 200, 200]
```

### Scripts
Lighting scripts use embedded Kotlin via `LightingScript` base class:
- Access fixtures through the `fixtures` property
- Use coroutines for timing and animation
- Scripts are cached by SHA-256 hash

### DMX Control
- `DmxController` interface abstracts DMX output
- `ArtNetController` implements ArtNet protocol
- `Universe` represents subnet + universe addressing
- Use `ControllerTransaction` to batch channel updates with fades
- `EasingCurve` enum provides curve types for smooth fades (sine, quad, cubic, step)

### FX System
Tempo-synchronized effects for continuous animations without complex scripts:
- **MasterClock** - Global BPM reference (20-300 BPM), emits 24 ticks/beat
- **FxEngine** - Processes active effects, applies to fixtures via transactions
- **FxRegistry** - Unified registry for all effect types (built-in and script-defined)
- **FxTargetable** - Common interface for Fixture and FixtureGroup (enables unified FX targeting)
- **FxTargetRef** - Reference type distinguishing fixture vs group targets
- **BeatDivision** - Timing constants (QUARTER, HALF, WHOLE, ONE_BAR, etc.)
- **BlendMode** - How effects combine: OVERRIDE, ADDITIVE, MULTIPLY, MAX, MIN

Effect interfaces:
- **Effect** - Pure phase-based: `(phase, context) → FxOutput`
- **StatefulEffect** - Tick-based with internal state: `(tick, deltaMs, context) → FxOutput` (e.g., CandleFlicker)
- **CompositeEffect** - Multi-output: `(phase, context) → Map<FxOutputType, FxOutput>` (e.g., LightningStrike)

Built-in effect types:
- **Dimmer**: SineWave, Pulse, RampUp/Down, Triangle, Strobe, Flicker, Breathe, CandleFlicker
- **Colour**: ColourCycle, RainbowCycle, ColourStrobe, ColourPulse, ColourFade
- **Position**: Circle, Figure8, Sweep, PanSweep, TiltSweep, RandomPosition
- **Composite**: LightningStrike (dimmer + colour)

Scripts can apply effects using extension functions:
```kotlin
fixture.applyDimmerFx(fxEngine, SineWave(), FxTiming(BeatDivision.HALF))
fixture.applyColourFx(fxEngine, RainbowCycle(), FxTiming(BeatDivision.ONE_BAR))
```

Scripts can also register custom effects that appear in the library API:
```kotlin
registerEffect(EffectRegistration(
    id = "my-effect", name = "My Effect",
    category = "dimmer", outputType = FxOutputType.SLIDER,
    compatibleProperties = listOf("dimmer"),
    factory = { params, _, _ -> MyCustomEffect(params) },
))
```

### Fixture Groups
Type-safe fixture groups for treating multiple fixtures as a single unit:
- **FixtureGroup<T>** - Generic group with compile-time type safety, implements `FixtureTarget`
- **GroupMember** - Fixture wrapper with position and metadata (pan/tilt offsets, tags)
- **DistributionStrategy** - Phase distribution patterns (LINEAR, UNIFIED, CENTER_OUT, etc.)
- **MultiElementFixture** - Support for fixtures with multiple controllable elements

**Group Property Access**: Groups expose trait properties through extension properties:
```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Direct property access (returns AggregateSlider/AggregateColour)
group.dimmer.value = 255u                    // Set all dimmers
group.rgbColour.value = Color.RED            // Set all colours
group.uv.value = 128u                        // Set all UV

// Uniformity detection
if (group.dimmer.isUniform) {
    println("All at ${group.dimmer.value}")
} else {
    println("Mixed: ${group.dimmer.memberValues}")
}

// Access individual channels
group.rgbColour.redSlider.value = 200u       // Set all reds
```

**Hierarchical Groups (SubGroups)**: Groups can contain other groups of the same type:
```kotlin
val frontHexes = createGroup<HexFixture>("front-hexes") {
    addSpread(listOf(hex1, hex2))
}
val atmosphericHexes = createGroup<HexFixture>("atmospheric-hexes") {
    addSpread(listOf(hex3, hex4))
}
val allHexes = createGroup<HexFixture>("all-hexes") {
    addGroup(frontHexes)
    addGroup(atmosphericHexes)
    // Or: addGroups(listOf(frontHexes, atmosphericHexes))
}
// allHexes.fixtures returns all 4 HexFixtures
// allHexes.subGroups returns [frontHexes, atmosphericHexes]
allHexes.dimmer.value = 255u  // Sets all 4 fixtures
```

**Flatten Method**: Use `flatten()` to get all fixtures including from sub-groups:
```kotlin
val allHexes: FixtureGroup<HexFixture> = ...
val allFixtures = allHexes.flatten()          // List<FixtureTarget>
val hexOnly = allHexes.flattenAs<HexFixture>() // List<HexFixture>
```

**Group FX Targeting**: A single `FxInstance` targets the entire group. The `FxEngine` expands
the effect to group members at processing time, applying distribution strategy offsets.

Groups are created via `DbFixtureLoader` from DB patch records. Internally, the loader calls `Fixtures.register {}`:
```kotlin
// Internal to DbFixtureLoader — not available in user scripts
fixtures.register {
    val hex1 = addFixture(HexFixture(universe, "hex-1", "Hex 1", 1))
    val hex2 = addFixture(HexFixture(universe, "hex-2", "Hex 2", 13))

    createGroup<HexFixture>("front-wash") {
        addSpread(listOf(hex1, hex2), panSpread = 60.0)
        configure(symmetricMode = SymmetricMode.MIRROR)
    }
}
```

Applying effects to groups:
```kotlin
val group = fixtures.group<HexFixture>("front-wash")

// Pulse effect with linear distribution
val effectId = group.applyDimmerFx(fxEngine, Pulse(), distribution = DistributionStrategy.LINEAR)

// Unified colour across all fixtures
group.applyColourFx(fxEngine, RainbowCycle(), distribution = DistributionStrategy.UNIFIED)
```

## API Endpoints

- **REST API**: `http://localhost:8413/api/rest`
- **WebSocket**: `ws://localhost:8413/api`
- **Swagger UI**: `http://localhost:8413/openapi`

### FX REST Endpoints
- `GET /api/rest/fx/clock/status` - Get BPM and clock state
- `POST /api/rest/fx/clock/bpm` - Set BPM
- `POST /api/rest/fx/clock/tap` - Tap tempo
- `GET /api/rest/fx/active` - List active effects
- `POST /api/rest/fx/add` - Add effect to fixture
- `DELETE /api/rest/fx/{id}` - Remove effect
- `POST /api/rest/fx/{id}/pause` / `resume` - Control effect
- `GET /api/rest/fx/library` - Available effect types

### Group REST Endpoints
- `GET /api/rest/groups` - List all fixture groups
- `GET /api/rest/groups/{name}` - Get group details with members
- `GET /api/rest/groups/{name}/properties` - Get aggregated property descriptors for group members
- `GET /api/rest/groups/{name}/fx` - Get active effects for group
- `POST /api/rest/groups/{name}/fx` - Apply effect to group (returns single `effectId`)
- `DELETE /api/rest/groups/{name}/fx` - Clear all effects for group
- `GET /api/rest/groups/distribution-strategies` - List distribution strategies

### WebSocket Messages
- `channelState` - DMX channel value updates
- `channelMappingState` - Channel-to-fixture mapping (sent on connect and fixtures change)
- `universesState` - Available DMX universes
- `updateChannel` - Direct channel control
- `fxState` - Request/receive FX state (BPM, active effects)
- `setFxBpm` - Set tempo
- `tapTempo` - Tap for tempo
- `removeFx` / `pauseFx` / `resumeFx` / `clearFx` - Effect control
- `fxChanged` - Broadcast on effect add/remove/update
- `groupsState` - Request/receive fixture groups state
- `clearGroupFx` - Clear all effects for a group
- `groupFxCleared` - Confirmation of group effect removal

## Database

Uses Exposed ORM with PostgreSQL. Tables auto-create on startup via `SchemaUtils.createMissingTablesAndColumns`.

Key tables:
- `DaoProjects` - Project definitions
- `DaoScripts` - Lighting script source code

## Database changes and cloud sync

Cloud sync (see [`docs/sync-engineering.md`](docs/sync-engineering.md))
serialises most of the project graph as canonical JSON. Adding or modifying
tables/columns has implications for sync correctness — read
`docs/sync-engineering.md` before changing the schema.

**Decision tree for any DB change:**

1. **Is the new table/column part of a project's portable show content,
   machine-local state, or transient runtime state?**
2. **Portable** → must have a `uuid` column, must round-trip through
   canonical JSON, must be wired through both `ProjectExporter` and
   `ProjectImporter`, and the appropriate sync DTO in
   `sync/dto/SyncDtos.kt` must carry the field. Consider whether the
   change needs a `formatVersion` bump and a migration. Extend the
   round-trip test in `src/test/kotlin/.../sync/ProjectRoundTripTest.kt`.
3. **Machine-local** (per-rig values like controller IPs, sync config) →
   don't add to the sync DTO. Add to the `machine_overrides` table via the
   `sync/Overrides.kt` helper (see `Overrides.resolveUniverseAddress` /
   `setUniverseAddress` for the precedent), or — if the field is logically
   wholly machine-local rather than a per-record override (e.g. the cloud
   sync config table `sync_configs`) — give it its own local-only table.
   Either way, never wire it through `ProjectExporter` / `ProjectImporter`.
4. **Transient runtime state** → leave out of `ProjectExporter` /
   `ProjectImporter` entirely and document why.

**Specific rules:**

* New tables default to **not synced** until explicitly wired into
  `ProjectExporter` and `ProjectImporter` — don't rely on auto-discovery.
* Reordering existing fields in a synced DTO is a non-issue — the
  canonical JSON serialiser sorts keys alphabetically.
* Renaming a JSON field, removing a field, or changing FK targets on a
  synced table is a `formatVersion` change. Removing a required field is
  a `minReader` bump.
* Updates to `docs/sync-engineering.md` are required when adding a
  synced table, changing the JSON layout, or changing the conflict
  semantics.

## Common Development Tasks

### Adding a New Fixture Type
1. Create class in `fixture/dmx/` extending appropriate base
2. Add `@FixtureType` annotation with name
3. Implement required traits (Dimmer, Colour, etc.)
4. Use `@FixtureProperty` to annotate controllable properties

### Multi-Mode Fixtures
Some fixtures support multiple DMX channel modes (set via DIP switches). Use the sealed class pattern:
- `DmxChannelMode` - Interface for mode definitions with `channelCount` and `modeName`
- `MultiModeFixtureFamily<M>` - Marker interface associating fixture with its mode

**Pattern:**
```kotlin
sealed class MyFixture(...) : DmxFixture(...), MultiModeFixtureFamily<MyFixture.Mode> {
    enum class Mode(override val channelCount: Int, override val modeName: String) : DmxChannelMode {
        MODE_6CH(6, "6-Channel"),
        MODE_12CH(12, "12-Channel")
    }

    @FixtureType("my-fixture-6ch")
    class Mode6Ch(...) : MyFixture(...), FixtureWithDimmer { ... }

    @FixtureType("my-fixture-12ch")
    class Mode12Ch(...) : MyFixture(...), FixtureWithDimmer, MultiElementFixture<Head> { ... }
}
```

**Example:** `SlenderBeamBarQuadFixture` - 4-head LED bar with 5 modes (1/6/12/14/27 channel)

### Writing a Lighting Script

Three script types with focused API surfaces (controlled by `ScriptType` enum, stored per-script in DB):

**`FX_APPLICATION`** — apply effects to fixtures (most common):
```kotlin
val wash = fixture<HexFixture>("front-wash-1")
wash.fx {
    dimmer(SineWave(), BeatDivision.HALF)
    colour(ColourCycle(), BeatDivision.ONE_BAR)
}
setBpm(128.0)
```

**`FX_DEFINITION`** — define custom effect types:
```kotlin
registerEffect(EffectRegistration(
    id = "my-effect", name = "My Effect",
    category = "dimmer", outputType = FxOutputType.SLIDER,
    compatibleProperties = listOf("dimmer"),
    factory = { params, _, _ -> CandleFlicker(baseLevel = 180u) },
))
```

**`GENERAL`** (`LightingScript`) — full-power scripts with DMX, fixtures, FX, coroutines. Can read fixture state but cannot register fixtures (registration is handled by DbFixtureLoader from DB patches).

### Modifying REST API
Add routes in `routes/` package using Ktor Resources for type-safe routing.

## Related Projects

- **Frontend**: `/Users/chris/Development/Personal/lighting-react/`

## External Integrations

- **DMX Hardware**: ArtNet protocol over network
- **Philips Hue**: HTTP API via Ktor client

## Engineering Documentation

For deeper technical details, see the docs in `docs/`:

- [DMX Subsystem](docs/dmx-engineering.md) - Low-level DMX control architecture, ArtNet implementation, fading, transactions
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, property types, adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script compilation, caching, execution
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication, message types, update flow
- [FX System](docs/fx-engineering.md) - Tempo-synchronized effects, Master Clock, effect types, blend modes
- [Fixture Groups](docs/groups-engineering.md) - Type-safe groups, distribution strategies, multi-element fixtures
- [Cloud Sync](docs/sync-engineering.md) - Canonical JSON, UUID identity, machine-local overrides, per-project JGit working tree + snapshot flow (Phases 1–3 of the cloud-sync plan)

## Follow-ups

[`docs/plans/followups.md`](docs/plans/followups.md) tracks dormant work — all
open items are Trigger-gated, Blocked, or Manual. Don't poll it routinely.
**Open it only when your current change might fire a listed trigger** (e.g.,
touching FX tick loops, ArtNet output paths, shared `AssignmentHealth` UI,
cueEdit session routing, or anything that adds a 6th consumer of fixture/group
property lookup). Grep the file's `### `FU-…`` headers + `**Trigger to
revisit**` lines first; only read full bodies for matches. If a trigger fires,
flag it inline (or promote the item to Ready) rather than silently working
around it.
