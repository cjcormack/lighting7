# Claude Code Configuration for Lighting7

A professional stage/event lighting control system built in Kotlin using Ktor. Controls physical lighting fixtures through DMX (ArtNet) and Philips Hue.

## Tech Stack

- **Kotlin 2.4.10** on JVM toolchain 24 (runs on the LTS JDK 25)
- **Ktor 3.5.1** (web server, WebSockets, REST API) — version comes from the
  `io.ktor.plugin` declaration in `build.gradle.kts`; the artifacts are versionless
  and resolved from its BOM
- **SQLite** (embedded, via `sqlite-jdbc`) with Exposed ORM and HikariCP
- **ArtNet4j** for DMX protocol
- **Kotlin Scripting** for embedded lighting DSL

## Building and Running

```bash
# Build
./gradlew build

# Run (starts REST on :8413)
./gradlew run
```

**`lighting7.jar` is target-OS-specific.** `shadowJar` keeps native binaries
(sqlite-jdbc, libremidi, JNA, alsa, coremidi4j) for the host OS only — that's ~18 MB
of the installer — so a Mac-built fat jar copied to Linux dies at its first DB
connection with `No native library found for os.name=Linux`. Two overrides:

- `-PnativePayloadOs=all` — the old portable jar, for when you genuinely need one.
- `-PnativePayloadOs=windows` — reproduces CI's jar byte-for-byte from a Mac, which is
  how the installer's size is measured without a Windows host.

A `doLast` verifier fails the build if the resulting jar has the wrong payloads, because
a stale Ant exclude matches nothing *silently* in both directions. `packageMac` /
`packageWindows` refuse a mismatched override. Tests and `run` are unaffected — they use
`runtimeClasspath`, not the fat jar.

Also note `copyFrontend` is a `Sync`: it owns `src/main/resources/static/` and deletes
anything it didn't put there, so don't hand-place files in it.

### Configuration

1. Copy `example.local.conf` to `local.conf`
2. Optionally set `database.path` — empty uses `<appDataDir>/lighting7.db`
3. Set project name

### Never stop or kill Gradle daemons

**The desk is usually running as `./gradlew run` in the operator's own terminal —
the app *is* a Gradle daemon.** `gradle --stop` is registry-wide: it stops every
daemon for that Gradle version, so "just clearing a wedged daemon" kills the live
show. The app's non-daemon threads (Ktor, ArtNet, the sync engine) then keep the
JVM alive after Gradle's services are torn down, so the registry keeps a stale
**busy** entry — `./gradlew run` afterwards reports "1 busy Daemon could not be
reused" and the operator has to kill the process by hand.

So: never run `./gradlew --stop`, `pkill`/`killall` against java/gradle/kotlin, or
anything else that takes down a JVM you didn't start. `scripts/claude-gradle-guard.sh`
is a `PreToolUse` hook that blocks these; if it fires, don't work around it. When a
build looks wedged, reach for `--no-daemon`, `--rerun-tasks` or `--offline`, and
otherwise ask the operator.

Two related facts, both visible in `~/.gradle/daemon/<version>/daemon-*.out.log`:

- Daemons an agent starts are forked from the sandboxed shell, so they **inherit
  the sandbox for life** (`Could not start the FSEvents stream`, `Operation not
  permitted` on paths outside the write allowlist). Agent sessions therefore set
  `GRADLE_OPTS=-Dorg.gradle.jvmargs=-Xmx2g`, which both raises the 512 MB default
  the daemon OOMs at and gives agent builds their own daemon context, so the
  operator's terminal never lands on a sandboxed daemon.
- `~/.gradle/.tmp` and `~/.gradle/daemon/*/*.log` leak indefinitely (hundreds of
  MB each), and `Problems writing to Binary store … (exist: true)` during
  configuration is the signature of a **full disk**, not a corrupt cache — check
  `df -h /System/Volumes/Data` before believing anything else.

### Pre-commit checks

This project has no Makefile — the global `make commit-check` rule does not
apply. `./gradlew test` is the equivalent pre-commit check. A recent green
run earlier in the same session is sufficient; you do not need to re-run it
just before `git commit` if nothing has changed since.

`tasks.test` pins `-Dlighting7.dataDir` at `build/test-data`, because `State`
resolves the script cache, prompt-book PDF store, sync working tree and export
root under `appDataDir()` — without it the suite reads and writes the real
installation (`~/Library/Application Support/lighting7`), next to a desk that may
be running. `testAppConfig` cannot do this job: `lighting7.dataDir` is read
before any config is parsed. **If you ever see `FileSystemException … Operation
not permitted` there, or a `MissingFieldException` on a route DTO, suspect that
pin has been lost** — the route 500s and returns `ErrorResponse`, so a denied
write disguises itself as a serialization regression.

The suite takes ~1 minute warm, and ~55 s longer on the first run after any code
change (editing a source file changes the classpath fingerprint, which invalidates
the compiled-script jar cache and recompiles the 28 built-in FX effects). That cost
lands on whichever class first builds a `Show` — currently
`ParkSurvivesFixtureReloadTest`, a one-test class that therefore *looks* like the
slowest thing in the suite. It is not slow; it is first. Before optimising anything
in the test suite, read [`docs/testing-engineering.md`](docs/testing-engineering.md):
four specific changes took it from 14 minutes to 1, and each has a guard test that
explains why it is safe.

### Git workflow

Solo personal repo — commit and push directly to `main`. Do **not** open pull
requests, do **not** create feature branches. The standard "still don't commit
or push without me asking" rule from the global CLAUDE.md still applies; this
section only changes *how* a confirmed commit/push happens (straight to `main`,
no PR).

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
├── models/                 # Database entities (projects, scripts, cues, looks)
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
- **SpeedMasterBank** - Per-show bank of named tempo buses (persisted, portable in sync); slot 0 = master 1, the global tempo the script API (`setBpm`/`tapTempo`) and the AI `set_bpm` tool mean. Effects subscribe via `speedMasterUuid` (null → master 1), and wall-clock effects scale their cycle via `rateSpeedMasterUuid` (null → unscaled); both are settable on every authoring surface. One engine pass per (conflated) tick wake-up, however many masters tick. `SpeedMasterBank.beats` fans every master's beat boundaries into one keyed stream (`speedMasters.beat`) — master 1 included, under its real uuid once the bank has loaded. Hardware drives masters through the `speedMasterBpm` / `speedMasterTap` binding targets.
- **MasterClock** - One master's tempo clock (20-300 BPM), emits 24 ticks/beat; phase is a pure function of the tick counter (`MasterClock.phaseForDivision`)
- **FxEngine** - Processes active effects, applies to fixtures via transactions
- **FxRegistry** - Unified registry for all effect types (built-in and script-defined)
- **FxTargetable** - Common interface for Fixture and FixtureGroup (enables unified FX targeting)
- **FxTargetRef** - Reference type distinguishing fixture vs group targets
- **BeatDivision** - Timing constants (QUARTER, HALF, WHOLE, ONE_BAR, etc.)
- **BlendMode** - How effects combine: OVERRIDE, ADDITIVE, MULTIPLY, MAX, MIN

Effect interfaces:
- **Effect** - Pure phase-based: `(phase, context) → FxOutput`
- **StatefulEffect** - Tick-based with internal state: `(tick, deltaMs, context) → FxOutput` (e.g., CandleFlicker)
- **CompositeEffect** - `(phase, context) → Map<FxOutputType, FxOutput>`, of which only the `outputType` entry is applied — one instance still drives one property (e.g., LightningStrike applies its dimmer half, not its colour half)

Built-in effect types:
- **Dimmer**: SineWave, Pulse, RampUp/Down, Triangle, Strobe, Flicker, Breathe, CandleFlicker
- **Colour**: ColourCycle, RainbowCycle, ColourStrobe, ColourPulse, ColourFade
- **Position**: Circle, Figure8, Sweep, PanSweep, TiltSweep, RandomPosition
- **Composite**: LightningStrike (dimmer; its colour half is computed but not applied)

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
- `GET/POST /api/rest/project/{id}/speed-masters` + `GET/PUT/DELETE .../{mid}` - Speed-master CRUD, and the only REST tempo surface: `PUT` with `bpm` sets the stored default *and* retunes the live clock when the project is current (delete guards: `SPEED_MASTER_PROTECTED` for master 1, `SPEED_MASTER_IN_USE` when referenced). Tap is WS-only (`speedMasters.tap`)
- `GET /api/rest/fx/active` - List active effects
- `POST /api/rest/fx/add` - Add effect to fixture
- `DELETE /api/rest/fx/{id}` - Remove effect
- `POST /api/rest/fx/{id}/pause` / `resume` - Control effect
- `GET /api/rest/fx/library` - Available effect types

### Cue Stack Run Endpoints
- `POST /api/rest/project/{id}/cue-stacks/{stackId}/standby` - Arm the next GO (`{cueId}`; null disarms). "Next" is server-owned — see `docs/cue-stacks-engineering.md` §"Standby"
- `POST /api/rest/project/{id}/cue-stacks/{stackId}/preview` - Compose a cue without firing it (`{cueId?}`, null → the effective next). Layer 4 only; see §"Preview compose"

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
- `fxState` - Request/receive the active-effect list (incl. per-effect speed master). Carries no tempo — that is the `speedMasters.*` family's job
- `speedMasters.state` / `speedMasters.setBpm` / `speedMasters.tap` - Keyed per-master tempo control, the only WS tempo surface; `speedMasters.changed` streams live BPM moves, `speedMasters.beat` beat boundaries, `speedMasterListChanged` signals CRUD
- `removeFx` / `pauseFx` / `resumeFx` / `clearFx` - Effect control
- `fxChanged` - Broadcast on effect add/remove/update
- `groupsState` - Request/receive fixture groups state
- `clearGroupFx` - Clear all effects for a group
- `groupFxCleared` - Confirmation of group effect removal
- `cueRunStateChanged` - A cue stack's live cue, armed next, and fade timing. One frame per transition from `CueStackManager` (so REST, the MIDI surface and auto-advance all report), plus a snapshot on connect; clients animate the fade locally from `fadeElapsedMs`

## Database

Uses Exposed ORM over an embedded SQLite file (`org.sqlite.JDBC`, pooled by HikariCP
with `maximumPoolSize = 1` — SQLite has a single writer and a larger pool produces
`SQLITE_BUSY` under load). The DB path comes from `database.path` in `local.conf`,
defaulting to `<appDataDir>/lighting7.db`. Tables auto-create on startup via
`SchemaUtils.createMissingTablesAndColumns`.

SQLite is the only supported backend, and **there are currently no schema
migrations at all.** `StateMigrations.kt` is gone; `state/InstallBootstrap.kt`
holds only the install-identity row, and explains what was removed and why.

That is a deliberate bet on there being exactly one database (the dev desk),
and it expires the moment a second install exists — the Windows MSI ships an
upgrade path. Before making a non-additive schema change once anything is
deployed, recover the migration seam from git history; `InstallBootstrap.kt`
says where it plugs in and what ordering constraint bit last time.

Key tables:
- `DaoProjects` - Project definitions
- `DaoScripts` - Lighting script source code

## Database changes and cloud sync

Cloud sync (see [`docs/sync-engineering.md`](docs/sync-engineering.md))
serialises most of the project graph as canonical JSON. Adding or modifying
tables/columns has implications for sync correctness — read
`docs/sync-engineering.md` before changing the schema.

Auth has two paths: **GitHub OAuth** (primary, install-wide identity via
web flow / device flow, auto-refreshing tokens) and **Personal Access
Tokens** (Advanced fallback, per-repo). Both flow through `AuthResolver`
and use the same `CredentialStore` backend (OS keychain by default, with an
encrypted-file fallback). OAuth requires `sync.oauth.github.clientId` /
`clientSecret` in `local.conf`; absent that, only the PAT path is offered.

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

**Enforcement:** the decision above is not optional bookkeeping — every table
in `models/Schema.kt`'s `ALL_TABLES` must have a recorded disposition in
`SyncCoverageTest.dispositions`, and every table declared portable must
actually produce export output when `testsupport/RichProjectFixture.kt` is
exported. Add a table without answering the question and `./gradlew test`
fails. See `docs/sync-engineering.md` §"How to add a new table".

**Project cloning is derived, not separate.** `POST /project/{id}/clone` runs
export → fresh UUIDs → import (`sync/ProjectCloner.kt`), so wiring a table
through the exporter and importer is all that's needed for it to be cloned.
Never add a table-by-table clone path; that's what rotted last time.

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
* Extend `testsupport/RichProjectFixture.kt` when adding a portable table or
  field, and set a **non-default** value. Canonical JSON omits defaults, so a
  field left at its default is invisible to the round-trip and clone tests.

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

- [Testing](docs/testing-engineering.md) - What makes the suite ~1 min rather than ~14 (the four changes, each load-bearing), the warm/cold script-cache regimes, why `build/test-data` is pinned, the tests that assert real elapsed time, and the order-dependence detector
- [DMX Subsystem](docs/dmx-engineering.md) - Low-level DMX control architecture, ArtNet implementation, fading, transactions
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, property types, adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script compilation, caching, execution
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication, message types, update flow
- [FX System](docs/fx-engineering.md) - Tempo-synchronized effects, Master Clock, effect types, blend modes
- [Fixture Groups](docs/groups-engineering.md) - Type-safe groups, distribution strategies, multi-element fixtures
- [Cloud Sync](docs/sync-engineering.md) - Canonical JSON, UUID identity, machine-local overrides, per-project JGit working tree + snapshot flow, three-way diff + conflict sessions, GitHub OAuth + PAT auth (Phases 1–5 of the cloud-sync plan)
- [Composition Model](docs/lighting-composition-model.md) - The five layers, and §"Looks and layers" for the cook step: a cue's ordered Look layers plus its local rows flatten to one contributor per (fixture, property) before the resolver, which is what makes within-cue precedence one rule for every attribute
- [Desk Accounts](docs/desk-accounts.md) - Desk-local users, the two roles and where they're enforced, cookie sessions + live 4401 revocation, the QR password reset, and the `RESET-ADMIN` break-glass recovery
- [Windows Updates](docs/windows-updates.md) - The pinned MSI UpgradeCode, MSI version rules, `BuildInfo` in both jars, the GitHub release check, and the backend→launcher marker handshake that applies an update

## Windows updates — the load-bearing constants

Three things in this area are silent when they break, so they get a mention here rather than only
in `docs/windows-updates.md`:

- **`windowsUpgradeUuid` (`build.gradle.kts`) must never change.** It is the MSI UpgradeCode.
  Without it jpackage mints a *random* one per build, and Windows Installer then treats each MSI
  as an unrelated product — installing side by side rather than upgrading, with a green build and
  no error. That was the original bug. `verifyWindowsInstaller` reads the UpgradeCode back out of
  the finished `.msi` and asserts it; don't delete that on the grounds the flag is obviously being
  passed. It reads the **installer**, not the WiX jpackage generates — the generated `main.wxs`
  only ever says `UpgradeCode="$(var.JpProductUpgradeCode)"`, so grepping the sources for the UUID
  can never succeed.
- **`:generateBuildInfo` is one task feeding two jars.** `lighting7.jar` and `launcher.jar` must
  report the same version byte-for-byte; a mismatch means an update that reinstalls itself
  forever. Don't "simplify" it into a per-module generator.
- **`UpdateMarkerWriter.SCHEMA` (backend) and `UpdateMarker.SCHEMA` (launcher) are two constants
  in two modules.** Nothing but `UpdateMarkerRoundTripTest` keeps them equal — it round-trips the
  writer through the reader in one JVM, which is the only place the two halves meet before a real
  Windows box.

Update eligibility is **both** gates, always: `channel == release` *and* a packaged install *and*
Windows. Never gate on a version-string heuristic — the packaged default is `1.0.0` while
`project.version` is `0.0.1`, so any heuristic is wrong in both directions.

`/api/rest/update` is deliberately **not** in `ADMIN_ONLY_PREFIXES`: that gate is method-blind, and
`GET /update/status` should be readable by an operator. Actions call `requireAdmin()` per handler,
the same split `PUT /install` uses.

## Follow-ups

[`docs/plans/followups.md`](docs/plans/followups.md) tracks dormant engineering
work — mostly Trigger-gated or Blocked, plus a handful of **Ready** items.
[`docs/plans/manual-validation.md`](docs/plans/manual-validation.md) holds the
operator-on-the-rig checks. Don't poll either routinely.

**Open followups.md when your current change might fire a listed gate** (e.g.
touching FX tick loops, ArtNet output paths, shared `AssignmentHealth` UI,
the auth gate's role prefixes, anything that adds a WS command an operator
shouldn't have, or anything that adds a 6th consumer of fixture/group property
lookup) — or when you want a Ready item to pick up. **Read
the index table at the top first** — one row per item with its status and gate —
and only read the body of a matching item. If a gate fires, flag it inline (or
promote the item to Ready) rather than silently working around it.

When an item lands, replace its section with a one-line row in Completed. The
narrative belongs in the commit message, and anything durable belongs in the
relevant `docs/*-engineering.md` — not in the follow-ups tracker.
