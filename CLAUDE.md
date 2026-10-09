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

### Cloud sessions

In a Claude Code cloud container, `scripts/cloud-session.sh [owner/repo …]` does the whole
stand-up: installs JDK 24 (the image has 21 and the network policy blocks foojay), writes a
pull-only `local.conf` into the data dir (`~/lighting7-data`), starts `./gradlew run` in the
background, creates an `admin` / `lighting7-dev` account, and imports each show repo through
cloud sync. Attach a show repo to the session before importing it — the session's git proxy
supplies the credentials, so the stored token is a placeholder. The script's header has the
details; `sync.push = false` is in `docs/sync-engineering.md` §"Pull-only installs".

Run Gradle there under `LC_ALL=C.UTF-8`: the image's locale is POSIX, and `compileTestKotlin`
then fails with an internal compiler error writing class files named after test names that
contain "—". The script exports it; a bare `./gradlew test` needs it too.

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
apply. `./gradlew test` is the equivalent pre-commit check for the backend; the
frontend's is `npm run check` in `frontend/`, which `.githooks/pre-commit` runs
when anything buildable under `frontend/` is staged (enable it with
`git config core.hooksPath .githooks`). A recent green
run earlier in the same session is sufficient; you do not need to re-run it
just before `git commit` or `gh pr create` if nothing has changed since.

CI runs the same two gates on every PR (`.github/workflows/ci.yml`, see §"Git workflow"). The
backend job runs `./gradlew build` rather than `test`: that adds the fat jar, whose native-payload
verifier would otherwise first fail on a release build, and the `launcher` module's tests. It runs
on Linux under `LC_ALL=C.UTF-8` for the reason given under §"Cloud sessions".

**Warnings fail the build.** Kotlin compiler warnings in `src/` and `launcher/`
(`compilerOptions.allWarningsAsErrors`), in the `*.gradle.kts` scripts
(`org.gradle.kotlin.dsl.allWarningsAsErrors`), and Gradle deprecations (`org.gradle.warning.mode=fail`)
are all errors — the last two are in `gradle.properties`. Fix the cause; where one genuinely can't
be fixed, `@Suppress`/`@OptIn` it at the site with a comment, never globally. Kotlin compiles
incrementally, so a warning in an untouched file only shows on a full recompile — CI's is always
full.

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

Every change reaches `main` through a pull request; nothing is committed or pushed to `main`
directly. It is still a solo repo — the PR is the CI gate and the review surface, not a
hand-off to anyone.

- **A branch per change**, cut from an up-to-date `main`. An agent's branch is `claude/<slug>`
  (the desktop app's worktrees already name them so); otherwise anything descriptive.
- **One PR per change, including one that crosses the wire.** The frontend is in this repo
  (`frontend/`), so a route and its client, or a Kotlin rule and its TypeScript mirror, land in
  one PR — never a backend PR and a frontend PR that are each broken without the other. Several
  commits on the branch are fine, and each should still be a coherent step with a real message.
- **CI must be green before merging.** `.github/workflows/ci.yml` runs two jobs on every PR:
  `Backend` (`./gradlew build`) and `Frontend` (`npm run check` in `frontend/`). CI is the
  backstop, not the first run — the local checks below are still expected before pushing.
- **Merge with a merge commit**, never squash or rebase-merge, and bring a stale branch up to
  date by merging `main` into it rather than rebasing it once pushed. Plan docs and commit
  messages cite SHAs (`docs/plans/*` rows, "session 3 shipped (lighting-react a3699076)"), and a
  branch commit's SHA survives only a merge. It also keeps the narrative where the plan rows say
  it lives: in the branch's commit messages. The PR description summarises and links; it is not
  the record.
- **The shipping-verb rule in the global CLAUDE.md applies unchanged**, one step at a time:
  "commit" commits on the branch, "push" pushes the branch, "open a PR" opens it
  (`gh pr create --base main`), and only "merge" or "land" merges it. Never enable auto-merge
  unless asked.

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

**One-shot triggers** (stage-view plan session 9) are their own property kind, not a
`@FixtureProperty`: a `DmxTrigger` declared with `@FixtureTrigger(description, label, armName,
armDescription)` — the Twin Shot's `output1` / `output2`, armed by its `master`. Nothing that resolves
a property by name sees one, so no Look, template, cue row, effect, programmer value, Record or busk
pad can hold it, and every write boundary refuses one **by name** (`TRIGGER_NOT_STORABLE`,
`fixture/TriggerGuard.kt`'s `TriggerIndex`), MCP tools and surface bindings included. The desk owns the
channels (`state/TriggerOutput.kt`, above composition and under park through `Show.outputSource`): a
trigger sits idle except for one ~300 ms pulse per fire, its arm follows the desk's arm, a raw channel
write on either is dropped and a park at a firing level refused. A fire is an event
(`state/EffectsService.kt`) — a cue's **events** on GO, the cannon's hold-to-fire panel, a MIDI
`FireTrigger` — and needs the desk's **arm** (desk-wide, 60 s, dropped on a project switch or any stack
stop, runtime only); in Blind it is rehearsed (drawn, never sent). Loaded/spent tubes are machine-local
(`effect_tube_state`). See `docs/fixtures-engineering.md` §"@FixtureTrigger" and
`docs/cues-engineering.md` §"Cue events".

**Fixture commands** (fixture optics plan session 7) follow the trigger's shape: resets and lamp
control are a `DmxCommand` declared with `@FixtureCommand(label, description, holdMs, confirm)` — the
Revolution's five resets, the Robe's lamp on, lamp off and seven resets, the MAC 250's reset and lamp, and the reset
the Varytec, Shehds, Fusion, Orbit and Slender carried as a setting option until then. No Look,
template, cue row, effect, programmer value or binding can hold one: every write boundary refuses one
**by name** (`COMMAND_NOT_STORABLE`, `fixture/CommandGuard.kt`'s `CommandIndex`, beside each
`TriggerIndex` call), and on a channel a command **shares** with a property (the MAC 250's shutter) the
output's band guard sends a value in a command's band as idle, whatever wrote it. `state/CommandOutput.kt`
holds a command's level — and the preconditions it declares (`alongside`: the MAC 250's CTC, prism and
open gobo, the Robe's closed shutter) — for `holdMs`, above composition and under park through
`Show.outputSource`, then gives the channels back; a **dedicated** command channel (no property covers
it) is held idle between commands and a raw write on it dropped. One command per unit (`COMMAND_BUSY`),
refused in Blind (`COMMAND_BLIND`) or on a parked channel (`COMMAND_PARKED`); a blackout does not cut a
hold short, a project switch does. Stored rows holding one were stripped once at startup
and are on every sync import. See
`docs/fixtures-engineering.md` §"@FixtureCommand".

**Travel time** (fixture optics plan session 8, D14) is drawn, never output: `@FixtureType(travel =
Travel(panDegPerS, tiltDegPerS, beamMs, colourMs))` is how fast a type's mechanics move, and a SPEED
slider declared `@FixtureProperty(timing = TimingRole.POSITION | BEAM | COLOUR | ALL,
timingSecondsPerStep, timingFastFrom)` is one of the fixture's own timing channels — the Revolution's
Focus (pan/tilt), Colour and Beam Timing, a move's **duration** at 1 s a step, 0 its own speed. Only the
Stage view reads them (`frontend/src/lib/travel.ts`); the desk sends every value as composed. Most
speeds are estimates (`FU-MANUAL-S8-TRAVEL`); movers' vector speed channels are deliberately not
modelled. See `docs/fixtures-engineering.md` §"Travel and timing channels".

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
- **SpeedMasterBank** - Per-show bank of named tempo buses (persisted, portable in sync); slot 0 = master 1, the global tempo the script API (`setBpm`/`tapTempo`) and a bare AI `set_bpm` mean (that tool takes an optional `speedMasterUuid` to retune any other master, and `create_speed_master` adds one). Effects subscribe via `speedMasterUuid` (null → master 1), and wall-clock effects scale their cycle via `rateSpeedMasterUuid` (null → unscaled); both are settable on every authoring surface. A master may also carry a `usage` (dimmer/colour/position — the apply-time routing default, one per project) and a follow link (`follow_num`/`follow_den`/`follow_target_uuid`: the leader *drives the follower's clock*, so its beats land on the leader's rather than free-running; chains allowed, cycles refused; tempo writes on a follower are refused with `SPEED_MASTER_FOLLOWER`) — see `docs/fx-engineering.md` §"Usage routing and follow". One engine pass per (conflated) tick wake-up, however many masters tick. `SpeedMasterBank.beats` fans every master's beat boundaries into one keyed stream (`speedMasters.beat`) — master 1 included, under its real uuid once the bank has loaded. Hardware drives masters through the `speedMasterBpm` / `speedMasterTap` binding targets.
- **MasterClock** - One master's tempo clock (20-300 BPM), emits 24 ticks/beat; phase is a pure function of the tick counter (`MasterClock.phaseForDivision`). A *follower's* clock is driven by its leader instead of by a timer (`adoptDriven`/`driveTo`), and its derived BPM is reported unclamped — the 20–300 range guards a timer it doesn't have
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

Built-in effect types — **all 28 live as `.fx.kts` resources** under
`src/main/resources/fx/`, compiled into the `FxRegistry` at startup by `FxFileLoader`. There are
no compiled-in effect classes: the parallel `fx/effects/*.kt` set was deleted (sweep item D7)
once it had drifted a file behind the resources and nothing but tests constructed it.
- **Dimmer**: SineWave, Pulse, RampUp/Down, Triangle, SquareWave, Strobe, Flicker, Breathe,
  CandleFlicker, FluorescentFlicker, StaticValue, StaticSetting
- **Colour**: ColourCycle, RainbowCycle, ColourStrobe, ColourPulse, ColourFade, ColourFlicker,
  StaticColour
- **Position**: Circle, Figure8, Sweep, PanSweep, TiltSweep, RandomPosition, StaticPosition
- **Composite**: LightningStrike (dimmer; its colour half is computed but not applied)

A **template** may hold one effect instead of values — an *effect template*, the busking pad for a
named effect. It is always generic (it names no target; the layer's targets are the fan-out), always
exactly one effect, and never both an effect and rows: which one a template holds is its identity,
refused at the write boundary if a write would flip it. Its family is derived from the effect's
library `category` via `familyForEffectCategory`, so `controls`, `composite` and `beam` are refused
by name. `CueComposer` gained no new path — `effectsForLayer` takes `LayerContent.effects` and a
template's one effect goes down the same *deferred* arm a Look's does. See
`docs/lighting-composition-model.md` §"A template holds a value *or* an effect". A press is per
target on **both** arms and is the exact inverse of the pad's lit ring: "already on" is the record
covering every pressed target, and an off press clears them from every layer of that record. A
press *releases* something only through a solo bank on a busk page — per target, not per target set,
so a sibling that only overlaps the press is narrowed rather than left lit underneath
(`ProgrammerLayerStack.toggle`'s `releaseSiblings`, resolved by the press route, never read by the
cook). The library itself is a flat list ordered by name. See §"The busk layout" in the same doc.

Scripts name an effect rather than constructing one — `effect(id, params)` resolves it through the
registry, so a script reaches the same vocabulary as the UI and cues, user effects included:
```kotlin
fixture.applyDimmerFx(fxEngine, effect("SineWave", "min" to "40"), FxTiming(BeatDivision.HALF))
fixture.applyColourFx(fxEngine, effect("RainbowCycle"), FxTiming(BeatDivision.ONE_BAR))

// Every apply site takes a speed master; null means master 1 (unscaled for the rate master)
fixture.applyDimmerFx(fxEngine, effect("Pulse"), speedMasterUuid = speedMasterUuidAt(2))
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
val effectId = group.applyDimmerFx(fxEngine, effect("Pulse"), distribution = DistributionStrategy.LINEAR)

// Unified colour across all fixtures
group.applyColourFx(fxEngine, effect("RainbowCycle"), distribution = DistributionStrategy.UNIFIED)
```

## API Endpoints

- **REST API**: `http://localhost:8413/api/rest`
- **WebSocket**: `ws://localhost:8413/api`
- **Swagger UI**: `http://localhost:8413/openapi`

### FX REST Endpoints
- `GET/POST /api/rest/projects/{id}/speed-masters` + `GET/PUT/DELETE .../{mid}` - Speed-master CRUD, and the only REST tempo surface: `PUT` with `bpm` sets the stored default *and* retunes the live clock when the project is current (delete guards: `SPEED_MASTER_PROTECTED` for master 1, `SPEED_MASTER_IN_USE` when referenced). Tap is WS-only (`speedMasters.tap`)
- `GET /api/rest/fx/active` - List active effects
- `POST /api/rest/fx/add` - Add effect to fixture
- `DELETE /api/rest/fx/{id}` - Remove effect
- `POST /api/rest/fx/{id}/pause` / `resume` - Control effect
- `PUT /api/rest/fx/{id}` - Edit a running effect in place (id and phase kept); one parse with the `updateFx` frame (`applyEffectUpdate`), 400 `FX_UPDATE_REFUSED` / 404 `FX_NOT_FOUND`
- `POST /api/rest/fx/{id}/reset` - Re-apply a programmer template layer's **current** effect to the instance it spawned, id and phase kept, and re-key it so the next recook keeps it (fixture-fx-sheets plan W5); 409 `FX_NOT_FROM_TEMPLATE` / `FX_TEMPLATE_GONE`. See `docs/fx-engineering.md` §"Programmer suppression and the priority band"
- `GET /api/rest/fx/library` - Available effect types

### Library Endpoints
- `GET /api/rest/gels` - The gel library (fixture optics plan D7), the desk's resource `gels.json` served whole: `{code, name, color, brand, estimate?}` per gel. A unit's fitted gel names one by code; see `docs/fixtures-engineering.md` §"Fitted media"
- `GET /api/rest/lanterns` - The lantern library a generic dimmer is hung with (§"Lanterns and focus" there)

### Remote Access Endpoints
- `GET/PUT /api/rest/install/tunnel` - Remote access (the ngrok tunnel), admin only, machine-local. The authtoken is **write-only** (answers say `hasAuthtoken`); every PUT field is optional, an empty `authtoken` or `domain` clears it and turns remote access off; turning it on needs a desk account, a domain and a token (`REMOTE_ACCESS_INVALID`). Live status streams as `tunnel.state`. See `docs/mcp-engineering.md` §"Remote access"

### Cue Stack Run Endpoints
- `POST /api/rest/projects/{id}/cue-stacks/{stackId}/standby` - Arm the next GO (`{cueId}`; null disarms). "Next" is server-owned — see `docs/cue-stacks-engineering.md` §"Standby"
- `POST /api/rest/projects/{id}/cue-stacks/{stackId}/preview` - Compose a cue without firing it (`{cueId?}`, null → the effective next). Layer 4 only — except `scenery`, the whole stage's scenery as the GO would land it, each element with `from` and the GO's duration; see §"Preview compose"

### Scenery Endpoints
- `PUT /api/rest/projects/{id}/cues/{cid}/scenery`, `.../cue-stacks/{sid}/scenery`, `.../looks/{lid}/scenery` - An owner's **whole** scenery list (stage-view plan session 8): `{scenery: [{elementUuid, state: {visible?, open?, trimM?}, transitionMs?}]}`, answered as stored. Each state is checked against its element's kind (`open` a drawn drape's, `trimM` a flown piece's), every problem at once; `transitionMs` is a cue's only (null moves with the cue's fade); a MARKER and a separator are refused. Read through the owners' DTOs (`CueDetails.scenery` + `trackedScenery`, `CueStackDetails.scenery`, `LookDetails.scenery`), and every cue's own changes on the stack list, `CueStackDetails.cues[].scenery` (always encoded, batched by `cueSceneryByCue`; the cue table's Scenery column and the Prompt Book's glyph, cards and *On GO* line read it, scenery-programmer plan D13/D14 — so a cue scenery write fires `cueStackListChanged` as well as `cueListChanged`, and an element delete that swept scenery fires the cue, stack and Look lists); the Look list and busk pads' `LookDto` carries a summary, `scenery: [{elementUuid, elementName, state}]` (always encoded, batched by `lookScenerySummariesFor`), for the Looks sheet's Scenery column. Scenery **tracks** and sits **beside** the composition layers — never a channel, captured by Record only as what the programmer **holds** (the scenery-programmer plan's D7: into a cue a row only where it differs from what the cue tracks, into a Look every held state; Include loads an owner's own rows back and Update writes them, D8 — `routes/programmerSceneryRecord.kt`), never on a template — resolved by `show/SceneryResolver.kt` (the programmer's own scenery over live Looks over the live stacks' cues tracked from the top of the list, over each stack's set, over the element's base; the most recently GO'd stack wins) and streamed as `scenery.state`. A move not on a cue's own clock runs at the element's optional `params.travelS` (a DRAW/FLY drape's or a flown object's full travel, scaled by the share moved), else snaps. Deleting an element sweeps its changes. The reverse read, every owner that moves one element, is `GET .../stage-elements/{eid}/scenery` (§"Stage Scene Endpoints"). See `docs/cue-stacks-engineering.md` §"Scenery"

### Template Endpoints
- `GET/POST /api/rest/projects/{id}/templates` + `GET/PUT/DELETE .../{tid}` - Template CRUD. The list is ordered by name: a template holds no position and no group, because a pad's place on a busk page is the only order there is. `TemplateDto` and `LookDto` both carry `buskPageCount`, the library row's "on *n* pages" hint — batched into `TemplateUsage` / `LookUsage`, kept out of their `describe()`, and never a delete guard (see `docs/lighting-composition-model.md` §"The busk layout")
- `POST .../templates/{tid}/apply` (click → literals) and `.../toggle` (⌥click → a tracking layer, per target on both arms: an off press clears the pressed targets from every layer of that template). Always **siblingless** — exclusivity belongs to a solo bank, so `released` is always zero here

### Busk Endpoints
- `GET /api/rest/projects/{id}/busk/pages` + `GET .../{pid}` - The busk layout, every page nested to the pad; a pad embeds its record's own summary DTO (`template` / `look` / `cue`) so the view needs no second fetch
- `POST /api/rest/projects/{id}/busk/pages` (appends; a duplicate name is 409 `BUSK_PAGE_NAME_TAKEN`), `PUT/DELETE .../{pid}`, `POST .../pages/reorder` (every page once, else 400)
- `PUT .../pages/{pid}/layout` - The **whole** page (`rows: [{columns: [{columnId?, width, banks: [{bankId?, name, solo, flow, pads: [{padId?, templateId|lookId|cueId}]}]}]}]`): pads without an id created, pads absent deleted, everything renumbered densely; refused as a whole with `BUSK_LAYOUT_INVALID` / `_IDENTITY` / `_REF` before touching a row; answers the page with the ids it minted
- `POST .../busk/banks/{bid}/pads` `{templateId|lookId|cueId}` - Appends **one** pad to one bank and answers the whole page. The additive exception to D10's whole-page rule — an append can never empty a column — for the surfaces that place a pad from outside the busk view (a cue's properties, the template editor, the Look sheet, the programmer's create sheets), which hold no page document to re-`PUT`. Same `BUSK_LAYOUT_INVALID` / `_REF` codes; an unknown bank, or one in another project, is 404
- `GET/PUT /api/rest/projects/{id}/busk/rig` - The busk **rig**, the target band as the operator built it (busk-further plan D1–D3): the whole document on every write (`rows: [{rowId?, name, tiles: [{tileId?, groupId|patchId, elementKey?, cellMode, cellSplit?, label?}]}]`), refused as a whole with `BUSK_RIG_INVALID` / `_IDENTITY` / `_REF`, renumbered dense, ids minted and answered; the read embeds each tile's `GroupSummaryDto` or the patch's key, name and cells. Empty `rows` is stored and answered as empty — the show-all fallback is the client's. Current project only. A group or patch delete sweeps its tiles and fires `busk.rigChanged`
- `POST /api/rest/projects/{id}/programmer/spread` `{targets, families?, property, from, to, curve, order, parts, over, fadeMs?, seed?, write?}` - Spread, resolved on the desk (D9; editor-kit plan D3, D6): two intents (or `tmpl:{uuid}` for a colour), `curve ∈ LINE | MIRROR | ARROW | WINGS`, `order` a `DistributionStrategy` name (`LINEAR` = rig order), `parts` contiguous fans, `over ∈ HEADS | CELLS`; interpolates in the intent's own space and writes one literal per head into the programmer as owner `WEB`. Answers `{written, skipped, skippedFamilies}`; a property outside the mask writes nothing and names its family. Since the editor-kit plan's session 3: `write` (default true) — `false` runs the same resolve and **answers without writing**, the programmer's focused-Look-layer arm, whose client lands `written[].value` in the layer's draft; that value is each head's **literal** in the Look row grammar (`PropertyValue.serialize()`: `"0".."255"`, `"#rrggbb;w128"`, `"pan,tilt"`), not the intent — a colour-wheel head's is its slot's level under its own `colourWheel`; and the curve is spread over the heads that can take the property, found first — a head that cannot is skipped and consumes no position on it.
- `POST /api/rest/projects/{id}/programmer/aim` `{targets, x, y, z, fadeMs?}` - Point moving heads at a stage coordinate (metres, FOH-relative Z-up): each head's pan/tilt (and fine pan/tilt) is solved from its world placement, base orientation and annotated travel (`show/FixtureAim.kt`, the inverse of the Stage view's drawing) and written into the programmer as owner `WEB`, like a spread. Answers `{written: [{target, value, panDeg, tiltDeg}], skipped: [{target, reason}]}`; a fixed head, an unplaced fixture, an axis with no degree range, a cell or a point outside the travel is skipped by name. 400 `AIM_NEEDS_SELECTION` / `AIM_INVALID` (a coordinate outside ±500 m). The `aim_fixtures` MCP tool is the same call with a `dryRun`, and a `saveAsTemplate` that also records the aims as a per-fixture position template so they outlive the programmer. See `docs/fixtures-engineering.md` §"Aiming a head at a point"
- `POST /api/rest/projects/{id}/programmer/focus` `{targets, point: {x, y, z}, fadeMs?, write?}` - *Focus here* (fixture-optics plan D11): each head's FOCUS channel solved for its distance to the point, measured from its lens as the Stage view draws it (its placement through aim's maths, then the view's mover proportions to the pivot and lens, `MoverLens`), through the inverse of the Stage view's `resolveDeclaredFocusDistance` (`show/FixtureFocus.kt`; one shared vector, `src/test/resources/stage/focusInverse.fixture.json`, pins both sides), and written into the programmer as owner `WEB`. Answers `{written: [{target, value, distanceM}], skipped: [{target, reason}]}`; `write: false` answers without writing. A head with no focus channel, no declared range, no placement or a point outside its range is skipped by name; 400 `FOCUS_NEEDS_SELECTION` / `FOCUS_INVALID`. `aim_fixtures`' `focus: true` is the same solve on the aim point, for the heads aim aimed. See `docs/fixtures-engineering.md` §"Focusing a head on a point"
- `POST .../busk/pads/{padId}/press` `{targets, families?}` - The busk view's press: a template or Look → `ProgrammerLayerStack.toggle` with the bank's siblings when the bank is solo; a cue → apply/stop through `CueStackManager`, lit from `activeCueId`. A generic template pad pressed with no selection is 400 `TEMPLATE_NEEDS_SELECTION` (a per-fixture one lands on its own heads). Solo means pressing one turns its siblings off — a layer sibling narrowed on the pressed heads, a live cue sibling stopped, and a cue press taking layer siblings off wholesale (`release`). An off press releases nothing. `families` is the selection's attribute mask (absent = every attribute, an unknown name 400), tested on the **on** arm only — an off press comes off under any mask (`routes/pressArm.kt`): a template outside it is 400 `TEMPLATE_OUTSIDE_MASK`, a Look lands with `propertyMask = mask ∩ its families` and answers `skippedFamilies`, or 400 `LOOK_OUTSIDE_MASK` when nothing is inside; a cue ignores it. The same `families` rides `/templates/{id}/apply`, `/templates/{id}/toggle` and `/looks/{id}/toggle`, and the MIDI `PressPad` / `PressTemplate` doors pass the desk's. See `docs/lighting-composition-model.md` §"A press is per target" and §"The busk layout"
- `POST .../looks/{id}/toggle` with empty `targets` presses a Look with no deferred effect onto its own patched fixtures (400 `LOOK_NEEDS_SELECTION` / `LOOK_NO_TARGETS` otherwise); `POST .../cue-slots` takes exactly one of `lookId` / `cueId` and refuses a deferred-effect Look with 409 `CUE_SLOT_LOOK_NEEDS_SELECTION`

### Effects Endpoints
- `POST /api/rest/projects/{id}/effects/arm` `{on, seconds?}` - Arm the desk for `seconds` (default 60, 5–600) or disarm it; answers the arm as `effects.armed` carries it. Current project only. **Both roles** may arm on the desk's own listener; on the public listener it — and fire and reload — is 403 `REMOTE_EFFECTS_DISABLED` unless an admin allows it (`requireEffectsAccess`, the `requireScriptAccess` twin; Install settings → Remote access). REST rather than WS so the socket gains no operation (stage-view plan P2)
- `POST /api/rest/projects/{id}/patches/{pid}/fire` `{trigger, rehearse?}` - Fire one tube now: 409 `TRIGGER_NOT_ARMED` unarmed, 409 `TRIGGER_SPENT` on a spent tube (nothing sent), 400 `TRIGGER_UNKNOWN`. `rehearse` — or a blind programmer — draws it on every window and sends nothing, needing no arm and spending nothing
- `POST /api/rest/projects/{id}/patches/{pid}/reload` `{trigger?}` - Mark one tube, or every tube, loaded
- `PUT /api/rest/projects/{id}/cues/{cid}/events` - A cue's **whole** event list (`{events: [{patchId, trigger, offsetMs}]}`), every problem at once, answered as stored (`CueDetails.events`). Events fire on GO into the cue only — never tracked, never on GO TO a later cue or GO BACK, never previewed — and only while armed; unarmed they are skipped and announced, never queued. The AI's `apply_cue` fires them through the same hook (`EffectsService.onCueGo`). Sync `formatVersion` 21

### Fixture Command Endpoints
- `POST /api/rest/projects/{id}/patches/{pid}/commands/{command}` - Run one fixture command (a reset, a lamp strike, a lamp off): the desk holds its level for its declared time and answers when the hold ends (`{completed}` false when cut short). 400 `COMMAND_UNKNOWN`, 409 `COMMAND_BUSY` / `COMMAND_BLIND` / `COMMAND_PARKED`; on the public listener 403 `REMOTE_COMMANDS_DISABLED` unless an admin allows it (`requireCommandsAccess`, Install settings → Remote access). Current project only; both roles locally. No WS frame — the hold is the request. MCP's `run_fixture_command` is the same call, held to the same setting

### Stage Scene Endpoints
- `GET/POST /api/rest/projects/{id}/stage-elements` + `GET/PUT/DELETE .../{eid}` - The scene document (stage-view plan session 2): named venue and set elements — `ROOM | PROSCENIUM | FLAT | DRAPE | PLATFORM | SEATING | OBJECT`, layer `VENUE | SET`, a pose (Z is the base, except a platform's, which is its top surface, as a region's `centerZ` is), a size, a finish and `params`, a sealed `ElementParams` per kind (`models/stageScene.kt`) checked at the write boundary with every problem at once. A `PUT` is partial and the merged element is checked whole. A drape's `params.fabric` (`CANVAS | MUSLIN | SHARKSTOOTH | BOBBINET`, absent velour) and a drape's or a flat's `params.paint` (`{front?, back?}`, each a scene image's SHA-256, refused as `params.paint.front names no stored image` unless this project's store holds it or the element already did) are the scrim plan's session 1, drawn by the Stage view since session 2 (only velour pleats; the paint on both faces, its alpha cutting holes — `frontend/docs/stage-vis-engineering.md` §"Painted cloths"). `StageElementDto.fullDetail` is this machine's *Full detail* override, never synced. Deleting or reshaping a seating that seat views sit in is 409 `STAGE_ELEMENT_IN_USE`, `?force=true` leaves them dangling. Stored data only, so ungated by the current project, like stage regions. See `docs/fixtures-engineering.md` §"The scene document"
- `POST /api/rest/projects/{id}/scene-images` (raw `image/png` | `image/jpeg` body) + `GET .../scene-images` + `GET .../scene-images/{hash}[?variant=display|detail|mask]` - The scene-image store (scrim plan session 1, §3.3–3.4): the images a cloth is painted with, content-addressed per project at `<appDataDir>/scene-images/{projectUuid}/{sha256}.{png|jpg}` (`state/SceneImageStore.kt`). The upload is idempotent by hash and answers `{hash, width, height, hasAlpha, mediaType}`; at most 25 MB (413 above) and 8192 px a side, the size read from the header **before** any decode; 400 `SCENE_IMAGE_INVALID`. The list is every image this machine holds (what the element sheet's thumbnails and "Image missing on this machine" read). A `GET` serves the original or a derived copy made on first request — 2048 px, 4096 px (both by successive bicubic halving, alpha kept) or the 256 px alpha mask — immutable-cached; 404 `SCENE_IMAGE_UNKNOWN`. An image no element has referenced for 7 days is pruned at project load. Under the stage-element routes' gate (any signed-in account) and stored data only, so ungated by the current project. Synced as raw bytes at `sceneImages/` from `formatVersion` 23 (`sync/SceneImageRepoSync.kt`). See `docs/fixtures-engineering.md` §"The scene document"
- `PUT /api/rest/projects/{id}/stage-elements/{eid}/display-detail` `{full}` - The per-machine *Full detail* switch (scrim plan D12): a `machine_overrides` row (`stage_elements` / `displayDetail` = `"4096"`), answered as the element's `fullDetail`; fires `stageElementListChanged`
- `GET /api/rest/projects/{id}/stage-elements/{eid}/scenery` - What moves one element (scenery-programmer plan D11): `{cues: [{stackId, cueId, label, state, transitionMs}], sets: [{stackId, name, state}], looks: [{lookId, name, state}]}`, each owner's own stored row (never what a cue only tracks), cues in show order, sets in stack order, Looks by name. Stored data, gated like the element routes. The Stage popover's and the element form's *Moves with*
- `GET/POST /api/rest/projects/{id}/stage-viewpoints` + `GET/PUT/DELETE .../{vid}` - Saved viewpoints: `ORBIT` / `EYE` (eye, target, lens) or `SEAT` (a seating element's uuid and a seat id like `F6`, optionally a target and lens). Plan, Front and Side are built in and never rows. The MCP pair is `set_scene` (upsert by name, all-or-nothing, `template: "proscenium-hall"`) and `get_scene`

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
- `speedMasters.state` / `speedMasters.setBpm` / `speedMasters.tap` - Keyed per-master tempo control, the only WS tempo surface; `speedMasters.changed` streams live BPM moves, `speedMasters.beat` beat boundaries, `speedMasters.listChanged` signals CRUD, `speedMasters.error` acks a refused (`SPEED_MASTER_FOLLOWER`) or dropped (`SPEED_MASTER_UNKNOWN`) tempo write before the state reply
- `removeFx` / `pauseFx` / `resumeFx` / `clearFx` - Effect control
- `updateFx` / `fxError` - `PUT /fx/{id}` as a frame, for the live FX editor (fixture-fx-sheets plan W3): `{effectId, …UpdateEffectRequest}`, the same parse and strict coercion, answered `fxChanged(UPDATED)` or a unicast `fxError {effectId, code, message}` (`FX_NOT_FOUND` / `FX_UPDATE_REFUSED`). Gated as `pauseFx` is — an operator gesture, so `FU-AUTH-WS-PER-MESSAGE` does not fire
- `programmer.keyStack` - What sits under one property, top first (W1): park, programmer-band effects, each owner's slot, other effects with `heldBack`, the cue contributor, the base, each with `onStage`; a group per member. Request/reply on the asking socket with a client `requestId`, never broadcast. `heldBack` is `fx/EffectSuppression.kt` — the one rule the tick and provenance ask too, against the engine's own snapshot. See `docs/lighting-composition-model.md` §"Reading one property's stack"
- `programmer.clearTarget` / `programmer.targetCleared` - A fixture's (heads included) or group's *Release* (W2): its local effects (`FxInstance.isLocalEffect`) stopped first, then every non-layer slot and the sideband on its channels released in one pass and one republish at `fadeMs`; a local effect that also drives heads outside it is left running and named in `partial`. Unicast reply
- `fxChanged` - Broadcast on effect add/remove/update
- `groupsState` - Request/receive fixture groups state
- `clearGroupFx` - Clear all effects for a group
- `groupFxCleared` - Confirmation of group effect removal
- `cuesRecomposed` - A Look/template *contents* edit changed what the named `cueIds` compose to. The keyed counterpart to `lookListChanged` / `templateListChanged`, which deliberately don't fire for a contents edit; carries every cue layering the edited record, not just the live ones re-transmitted
- `busk.layoutChanged` - Keyed `{pageIds}`: page CRUD or reorder, a whole-page layout write, a pad appended to a bank, or a template / Look / cue / cue-stack delete that took pads off those pages. The busk view re-reads exactly those pages. The library rows' `buskPageCount` is refreshed by the *client*, not by a `templateListChanged` the backend would have to fire on every gesture: an own write invalidates the two lists only when `recordsOnPage` actually moved, a foreign frame invalidates them wholesale. See `docs/websocket-engineering.md`
- `windows.announce` / `windows.state` / `windows.show` / `windows.rename` / `windows.fullscreen` / `windows.follow` / `windows.viewOptions` - The desk's registry of signed-in browser windows (`state/WindowRegistry.kt`), and the family that lets one screen move another. A row is keyed by the **socket**, not by the client-minted `windowId` a duplicated tab copies: `id` is minted per connection and is what everything addresses, the row lives exactly as long as its socket (announce in, `remove` in the connection's `finally`), and a re-announce replaces it in place. `windows.state` is `StateFlow`-backed, so the connect snapshot is free and arrives before this window has announced. The five commands are **rebroadcast verbatim to every socket** (multi-screen plan D11) — a `targetId` that is not this window's matches nothing, so no handler needs a session lookup, and a command to a disconnected window is lost and visible as its unchanged `view`. **Machine-scoped**: registered in the pre-warm-up band of `Sockets.kt` beside `setupMachineSubscriptions` and *not* cleared on project switch, because a window outlives one and its `view` carries the project id — a registry in the show band would announce nothing until the desk was warm. Nothing persisted, no table, no `SyncCoverageTest` row. See `docs/desk-screens.md`
- `hand.state` / `hand.pickUp` / `hand.drop` - The desk's **hand** (`state/HandState.kt`): one held
  record — a template, a Look or a cue — picked up on any window and placed on any other, which is
  the cross-window move instead of a pointer drag (multi-screen plan D12). **There is no
  `hand.place`**: every place target already has a mutation with its own validation, so a place is
  the placing window's own mutation followed by `hand.drop`, and Undo is its inverse. `StateFlow`-backed,
  so the subscription is the snapshot; the frame carries the record's **own summary DTO**, exactly as
  `BuskPadDto` does, so every window builds the ghost's face without a lookup. `pickUp` resolves in
  the current project only and stamps `pickedUpOn` from the socket's announced window, never from the
  payload; a second pick-up replaces. Project-scoped — cleared by the project collector, unlike
  `windows.*` — plus a five-minute timeout and a reconcile on `lookListChanged` /
  `templateListChanged` / `cueListChanged`. The MIDI doors are `PickUpPad` / `HandPlaceInBank` /
  `HandDrop`. See `docs/lighting-composition-model.md` §"The busk layout"
- `selection.subselect` `{mode}` - Rewrite the selection's targets over the **rig order** (`state/BuskRigOrder.kt`): `ALL | ODD | EVEN | FIRST_HALF | SECOND_HALF | INVERT | NEXT | PREV | MASTERS`, counting cells where a selected fixture has them and heads where none does, `NEXT`/`PREV` stepping the whole selection one step of the rig and wrapping. The Cells chip and the `SelectionCells` / `SelectionNext` / `SelectionPrev` bindings share it; answers the ordinary `selection.state`
- `busk.rigChanged` - Payload-free: the rig document changed (a write, or a group / patch delete that took tiles off). One rig per project, so no id to key on
- `stageElementListChanged` / `stageViewpointListChanged` - Payload-free: scene-element or saved-viewpoint CRUD, through REST or `set_scene`
- `effects.armed` / `effects.fired` / `effects.skipped` - The desk's one-shot effects (stage-view plan session 9), outbound only: `effects.armed` is `StateFlow`-backed (the arm, `remainingMs` at send, `rehearsal`, every spent tube on this machine), so the subscription is the snapshot; `effects.fired {fixture, trigger, label, at, rehearsed, source, cueId?}` makes every Stage view throw confetti; `effects.skipped` announces fires that did not happen (unarmed GO, a spent tube, an arm that dropped). Arm, fire and reload are REST, so no inbound frame exists. See `docs/websocket-engineering.md` §"Effects"
- `scenery.state` - The live scenery (stage-view plan session 8), `StateFlow`-backed so the subscription is the snapshot: `{projectId, elements: [{elementUuid, state, from, startedAt, elapsedMs, durationMs, source}], staged?}`, every element a scenery change names or the programmer holds (absent = its base). `source` names what holds each piece (`{kind: base | set | cue | cueLook | programmerLook | programmer, stackId?, cueId?, label?, lookId?, name?}`, its highest-tier state's); `staged` (`[{elementUuid, state, from, elapsedMs, durationMs}]`) is present only while the programmer is blind and holds a change — what leaving Blind would land, for the Stage view's programmer sources (scenery-programmer plan D4, D12; both additive). Recomputed by `SceneryService` on every GO (`CueStackManager.activateCueInStack`, and the AI's `apply_cue` through the same hook), stack stop, programmer layer or scenery change, blind and owner/element edit. A client animates from `elapsedMs` at receipt, never the wall-clock `startedAt`. Outbound only
- `programmer.setScenery` / `programmer.clearScenery` / `programmer.sceneryState` - The programmer's own scenery (scenery-programmer plan D1, `state/ProgrammerScenery.kt`): a runtime-only overlay, element → partial states, the resolver's top tier. `setScenery {elementUuid, state, fadeMs?}` merges states onto one element, checked against its kind (refusal: `programmer.error`, every problem named); `clearScenery {elementUuid?, fadeMs?}` lets one go, or all with no uuid. `programmer.sceneryState {projectId, elements: [{elementUuid, state}], changedSinceInclude?}` is `StateFlow`-backed (snapshot + broadcast, and the reply to both); `changedSinceInclude` counts the held pieces Update would write that the included source does not already say, against a baseline Include, a retargeting Record and a Mode A Update set. Blind stages it, Clear releases it on the Clear fade, a project switch drops it; a fade above 0 is the move's clock, else the piece's `travelS`. Operator gestures, so `FU-AUTH-WS-PER-MESSAGE` is not fired. The AI's `move_scenery` writes the same overlay (no remote-access gate: scenery is drawn, never output)
- `stageRender.request` `{requestId, token, projectId, viewpoint, width, height, source, workLights, timeoutMs}` - `render_view`'s job for **one** window (stage-view plan session 4): draw a viewpoint (already the Stage view's vocabulary — a camera, a saved view's uuid, `seat:<uuid>:<id>`) offscreen and upload the PNG. `workLights` (stage-view menu plan D9) is the tool's optional boolean, always sent, default false — the room as lit; it is the capture's alone and never touches the drawing window's own work lights. The only unicast frame: it goes down the chosen socket's own queue in `StageRenderService`, and only a signed-in socket on the desk's own listener is ever attached (never one on the public listener, decided by port). Outbound only — the answer is `POST /api/rest/stage-renders/{requestId}` (raw PNG, `X-Render-Token`, the socket's own session, ≤ 4 MB) or `…/failure {reason}`, so the socket gains no inbound message and `FU-AUTH-WS-PER-MESSAGE` stays unfired. See `docs/mcp-engineering.md` §"`render_view`"
- `windows.viewOptions` `{targetId, view, options}` - One generic command for a window's per-view options (busk `focus`, `rigRows`, `sheet`; the Stage view's `viewpoint` — a camera, `orbit | eye | plan | front | side`, a saved `stage_viewpoints` row by its uuid, or an unsaved seat `seat:<seating uuid>:<seat id>` — its `source`, the vis source `output | outputProgrammer | programmer | nextGo`, and its `workLights`, `off | on` — all three per window, stage-view plan sessions 1–3 and the stage-view menu plan's D8), rebroadcast like the other four; the target applies them for that view only and re-announces. Every key rides inside `viewOptions`, never beside it: the announce's Json is bare, so a new top-level key would drop the frame. `windows.announce` carries `viewOptions?` (absent from an older client) and `windows.state` carries it back
- `windows.follow` `{targetId, on}` - Link a window to the desk selection or give it its own, from another window's Screens row (desk-follow plan D4). `windows.fullscreen`'s exact shape, rebroadcast verbatim; nothing is written server-side — the target applies it (or refuses an `on: false` in busk Rig / Pads focus) and re-announces `follows`. A window's fact, not a view's, so not a `viewOptions` entry
- `selection.state` / `selection.set` / `selection.toggle` / `selection.clear` - The desk's one shared selection (`state/DeskSelection.kt`): what a selection-relative surface control and a busk press act on. `StateFlow`-backed, so the subscription is the connect snapshot; writes reply nothing (a no-op write sends no frame). `toggle` is head-by-head: a group all of whose members are selected is "in", and toggling it off narrows the entries that covered it. The frame is the whole fact — `targets`, `families` (the attribute mask, absent = every attribute) and `source` (who moved it last, `{kind: window | surface, id?, name}`): `set` replaces all of it, `toggle` keeps the mask, `clear` drops both (multi-screen plan D2). `source` is stamped by the handler from the socket's own announced window (`SocketScope.window`, set by `windows.announce`) and is never read from a payload; `source.id` is that window's **registry row id** — the one `windows.show` addresses, not the client-minted `windowId` a duplicated tab shares. A socket that has not announced falls back to session 1's `sourceName` stub, remembered on the `SocketScope` and carrying no id (`FU-WINDOWS-RETIRE-SOURCENAME`)
- `surfaceEncoderBank.state` / `surfaceEncoderBank.set` - Which attribute each device's **strip encoders** drive (`deviceTypeKey → propertyName`, default `dimmer`). One frame type — it is a `StateFlow`, so the subscription is snapshot and broadcast both. A *strip* is one binding row whose `controlId` is a profile-declared strip id: `ControlSurfaceBindingService.resolve` answers the control's own binding across both bank levels first, then derives the strip's to the role that control plays (fader → dimmer, select → `SelectTarget(TOGGLE)`, encoder → the encoder bank's property, flash → `Flash`). See `docs/midi-control-surface-engineering.md` §"Strips" and §"Encoder bank"
- `surfaceControls.state` / `surfaceControls.changed` - Per-device control state as the hardware was told it (`value`, `physical`, `touched`, `led`, `ring`), from `ControlStateTracker`: a whole-device `.state` on connect and after every full resync, conflated `.changed` deltas at most every 50 ms per device. The Surfaces view draws exactly this and never recomputes from DMX
- `tunnel.state` - Remote access's live status (`off | installing | starting | online | error | no-binary`, with download progress, the URL, ngrok's `ERR_NGROK_…` code and whether it is retrying). Machine-scoped, **admin sockets only** (a zero-user desk's socket counts), snapshot on connect; the frame carries the state, so the client patches rather than refetches
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

### Time columns

Every point in time is a `utcInstant("…")` column (`models/timeColumns.kt`) — timezone-aware
`TEXT`, always UTC, `'2026-09-14 12:34:56.789Z'`, millisecond resolution. **Not**
`exposed-java-time`'s plain `timestamp()`, which on SQLite writes *local* wall-clock text with no
offset, so the same row means a different moment after a DST change or a move between machines.

Every elapsed interval is `duration("…")` — `java.time.Duration`, stored as **nanoseconds** in a
`BIGINT`. That is the same SQL type the schema used for millisecond `Long`s, and
`SQLiteDialect.supportsColumnTypeChange` is `false`, so nothing would notice a column holding one
unit while the code reads the other: never give a `duration()` column an `_ms` name.
`TimeColumnsTest` asserts both rules.

Three helpers, and each exists to close a hole:

- `nowUtc()` — the clock for anything stored. Truncated to milliseconds, so an in-memory stamp
  and the row it was written to can never disagree.
- `Instant.toIsoUtc()` — the only way an instant reaches the wire. Fixed three-digit fraction, so
  the string is **sortable**; `Instant.toString()` is not, dropping the fraction on an exact second.
- `Long?.asInstant()` — for the one boundary that is still millis, the OAuth credential blob.

On the wire, instants are ISO-8601 `String` and durations stay `…Ms: Long`. Sync JSON carries
neither an instant nor a `Duration`: `ProjectExporter` / `ProjectImporter` convert durations to
`…Ms: Long` at the boundary, which is what keeps `formatVersion` at 11.

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
5. Declare its 3D `body` on `@FixtureType` (archetype, a mover's head, lens) where the kind and
   the words would guess wrong; a type whose body is the lantern it is hung with sets
   `acceptsLantern` instead (the generic dimmer — the lantern library and each unit's focus are
   `docs/fixtures-engineering.md` §"Lanterns and focus")

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

**`FX_APPLICATION`** — apply effects to fixtures (most common). `effect(id, params)` resolves a
name through the `FxRegistry`; `speedMasterUuidAt(n)` names a speed master by its 1-based index:
```kotlin
val wash = fixture<HexFixture>("front-wash-1")
wash.fx {
    dimmer(effect("SineWave", "min" to "40"), BeatDivision.HALF)
    colour(effect("ColourCycle"), BeatDivision.ONE_BAR, speedMasterUuid = speedMasterUuidAt(2))
}
setBpm(128.0)
```

**`FX_DEFINITION`** — define custom effect types. The factory returns an `Effect` the script
implements itself; to build on a built-in, look its registration up via `fxRegistry` and call its
own factory rather than reimplementing the maths:
```kotlin
registerEffect(EffectRegistration(
    id = "my-effect", name = "My Effect",
    category = "dimmer", outputType = FxOutputType.SLIDER,
    compatibleProperties = listOf("dimmer"),
    factory = { params, _, _ ->
        object : Effect {
            override val name = "My Effect"
            override val outputType = FxOutputType.SLIDER
            override fun calculate(phase: Double, context: EffectContext) =
                FxOutput.Slider(if (phase < 0.5) 255u else 0u)
        }
    },
))
```

**`GENERAL`** (`LightingScript`) — full-power scripts with DMX, fixtures, FX, coroutines. Can read fixture state but cannot register fixtures (registration is handled by DbFixtureLoader from DB patches).

### Modifying REST API
Add routes in `routes/` package using Ktor Resources for type-safe routing.

## Related Projects

- **Frontend**: `frontend/` in this repo, with its own `frontend/CLAUDE.md`. It was the separate
  `lighting-react` repo until the two were merged with both histories intact: every pre-merge
  commit keeps its hash, so the hashes the docs cite still resolve. `git log --follow` reaches a
  frontend file's history from before its move into `frontend/`.

## External Integrations

- **DMX Hardware**: ArtNet protocol over network
- **Philips Hue**: HTTP API via Ktor client
- **MCP (Claude) and Remote access**: a second, **public listener** (`mcp.port`, default
  `127.0.0.1:8414`) serving the whole desk (UI, REST, WS) plus MCP, OAuth and its sign-in page. It
  is what a tunnel points at — the desk's own supervised ngrok (Install settings → Remote access,
  the agent downloaded on first enable, never bundled: ngrok's licence) or one the operator runs,
  named by `mcp.publicUrl`. The desk is its own OAuth server; grants are machine-local and die with
  the user's sessions. The tools are the show-running tools (`ai/AiTools.kt` — no script tool),
  `describe_rig` and the show-setup tools (`ai/SetupTools.kt`: projects, patch, stage and
  rigging, the scene — its cloths' fabric and paint, and `upload_scene_image`, the one tool that
  takes file bytes, a PNG or JPEG of at most 16 MB with no remote-access gate — cue stacks and
  prompt-book markup, built from documents the model reads — and
  `render_view`, a PNG of a stage viewpoint drawn offscreen by a signed-in desk window on the desk's
  own listener, never a remote one, so Claude can check the model it built). **The port split is the security boundary**: everything on that listener is
  remote, decided by port and never by a forwarded header, and `installRemoteHardening` applies
  there — no remote bootstrap, a sign-in lockout, `Secure` cookies, an Origin check, no QR flows,
  scripts refused unless an admin allows them, and arming, firing and fixture commands likewise
  (`requireEffectsAccess`, `requireCommandsAccess` — MCP's `run_fixture_command` included). Anything mounted on it is reachable from the
  internet, and a script-capable route must call `requireScriptAccess`. See
  [docs/mcp-engineering.md](docs/mcp-engineering.md)

## Engineering Documentation

For deeper technical details, see the docs in `docs/`:

- [API Conventions](docs/api-conventions.md) - Kebab-case paths, plural collections with the list GET on the collection, one spelling for vocabulary enumerations, `?force=true` as the guard-override, and why unbounded lists are deliberate at desk scale
- [Testing](docs/testing-engineering.md) - What makes the suite ~1 min rather than ~14 (the four changes, each load-bearing), the warm/cold script-cache regimes, why `build/test-data` is pinned, the tests that assert real elapsed time, and the order-dependence detector
- [DMX Subsystem](docs/dmx-engineering.md) - Low-level DMX control architecture, ArtNet implementation, fading, transactions
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, property types, adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script compilation, caching, execution
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication, message types, update flow
- [FX System](docs/fx-engineering.md) - Tempo-synchronized effects, Master Clock, effect types, blend modes
- [Fixture Groups](docs/groups-engineering.md) - Type-safe groups, distribution strategies, multi-element fixtures
- [MCP Server](docs/mcp-engineering.md) - The public listener that carries the whole desk plus MCP, Remote access (the ngrok agent the desk downloads and supervises, and the domain as OAuth issuer), the remote hardening applied by port, the desk as its own OAuth server (PKCE, rotating refresh tokens, lockout, revocation with sessions), and why the script tool is left out
- [Cloud Sync](docs/sync-engineering.md) - Canonical JSON, UUID identity, machine-local overrides, per-project JGit working tree + snapshot flow, three-way diff + conflict sessions, GitHub OAuth + PAT auth (Phases 1–5 of the cloud-sync plan)
- [Composition Model](docs/lighting-composition-model.md) - The five layers, and §"Looks, templates and layers" for the cook step: a cue's ordered layers plus its local rows flatten to one contributor per (fixture, property) before the resolver, which is what makes within-cue precedence one rule for every attribute. Its §"A template holds a value *or* an effect" is the D7 reversal and the three rules that keep it narrow
- [Desk Screens](docs/desk-screens.md) - The two desk screens: the launcher's tray items, the `?window=` naming contract, why a desk screen must be opened at `http://localhost:8413/` (installation, Keyboard Lock and Window Management are all secure-context), and kiosk mode as a note
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

`/api/rest/update` is deliberately **not** wrapped in `adminOnly {}`: that wrapper is method-blind,
and `GET /update/status` should be readable by an operator. Actions call `requireAdmin()` per
handler, the same split `PUT /install` uses.

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
