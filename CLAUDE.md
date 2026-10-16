# Claude Code Configuration for Lighting7

A professional stage/event lighting control system built in Kotlin using Ktor. Controls physical lighting fixtures through DMX (ArtNet) and Philips Hue.

@docs/claude/working-rules.md

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

`./gradlew run` runs the desk alone; the Windows launcher's dev run is `./gradlew :launcher:run`,
named by path. A desk quit from its own menu (*Quit desk*) ends the build `BUILD SUCCESSFUL`.

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
background, creates an `admin` / `lighting7-dev` account, and imports each show or venue repo
through cloud sync — venue repos first, since a show's import needs its pinned venue version
(`VENUE_MISSING` otherwise). Attach each repo to the session before importing it — the session's git
proxy supplies the credentials, so the stored token is a placeholder. The script's header has the
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
apply. **Locally, check what the change touches; the full suites run once, in CI**, on every
PR (`.github/workflows/ci.yml`, see §"Git workflow"). A full local run before pushing only
repeats CI, and the desktop app's Auto-fix wakes the session if CI fails.

- **Backend:** `./gradlew compileTestKotlin`, then `./gradlew test --tests '<package>.*'` for
  each package the change touches and each package that calls into it.
- **Frontend:** `npm run check:quick` in `frontend/`: the full `tsc` and build, lint (cached, so
  seconds when warm), and the Vitest files affected by changes since `origin/main`.
  `.githooks/pre-commit` runs it when anything buildable under `frontend/` is staged (enable it
  with `git config core.hooksPath .githooks`).

**Run the full suites locally** (`./gradlew test`, `npm run check`) when CI is a slow place to
find out: a Gradle or build-config change, a sync `formatVersion` bump, a schema migration, or a
refactor across packages. A recent green run earlier in the same session is sufficient; you do
not need to re-run it just before `git commit` or `gh pr create` if nothing has changed since.

The backend job runs `./gradlew check shadowJar` rather than `test`: `check` adds the `launcher`
module's tests, and `shadowJar` the fat jar, whose native-payload verifier would otherwise first
fail on a release build. It runs on Linux under `LC_ALL=C.UTF-8` for the reason given under
§"Cloud sessions". The build cache is on (`gradle.properties`): CI restores the cache main's last
run saved, so a task whose inputs haven't changed is restored rather than re-run.

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
write disguises itself as a serialization regression. The suite runs in two forks, and each
claims its own `build/test-data/slot-N` (see `docs/testing-engineering.md` §"Two forks").

The suite takes ~2 minutes locally in its two forks (measured 2026-10-16; ~4 minutes in
one), warm or cold alike. The first run after any code change recompiles the 28 built-in FX
effects (editing a source file changes the classpath fingerprint, which invalidates the
compiled-script jar cache), and that cost lands on whichever class first builds a `Show` in
each fork, which therefore *looks* slow. It is not slow; it is first. Before optimising
anything in the test suite, read [`docs/testing-engineering.md`](docs/testing-engineering.md):
four specific changes took it from 14 minutes to 1 when it was half its current size, and each
has a guard test that explains why it is safe.

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
- **CI must be green before merging.** `.github/workflows/ci.yml` reports two required checks on every PR:
  `Backend` (`./gradlew check shadowJar`) and `Frontend` (`npm run check` in `frontend/`, split into a
  build-and-lint job and three test shards). CI is where the full suites run; the quick local
  checks in §"Pre-commit checks" come first.
- **Merge with a merge commit**, never squash or rebase-merge, and bring a stale branch up to
  date by merging `main` into it rather than rebasing it once pushed. Plan docs and commit
  messages cite SHAs (`docs/plans/*` rows, "session 3 shipped (lighting-react a3699076)"), and a
  branch commit's SHA survives only a merge. It also keeps the narrative where the plan rows say
  it lives: in the branch's commit messages. The PR description summarises and links; it is not
  the record.
- **The shipping-verb rule in [docs/claude/working-rules.md](docs/claude/working-rules.md)
  applies unchanged**, one step at a time: "commit" commits on the branch, "push" pushes the
  branch, "open a PR" opens it (`gh pr create --base main`), and only "merge" or "land" merges
  it. `/verified-ship` does commit, push and open a PR on a passing gate, never merges. Never
  enable auto-merge unless asked. PRs have no reviewers: it is a solo repo.

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
a property by name sees one, so no Look, palette, cue row, effect, programmer value, Record or busk
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
palette, cue row, effect, programmer value or binding can hold one: every write boundary refuses one
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

**The end of the night** (venue-visit plan S6): a command declares a `role` (`LAMP_ON`, `LAMP_OFF`,
`RESET`, else `OTHER`) and a strike its `restrikeMs` and `strikeSpacingMs`, so `state/RigSafetyService.kt`
can run one role over the selection or the whole rig — a strike staggered and restrike-aware — and
*Make rig safe*: lamps off, the holds waited out, a **runtime-only** blackout
(`GlobalScalerStateHolder.safeBlackout`), then *Ready to switch off* on every window until anything
sends output again (D14). *Undo* strikes those lamps again and releases the blackout, remembered per
project while the desk runs. *Quit desk* is separate, admin-only and never remote. See
`docs/fixtures-engineering.md` §"Bulk commands and Make rig safe".

**Travel time** (fixture optics plan session 8, D14) is drawn, never output: `@FixtureType(travel =
Travel(panDegPerS, tiltDegPerS, beamMs, colourMs))` is how fast a type's mechanics move, and a SPEED
slider declared `@FixtureProperty(timing = TimingRole.POSITION | BEAM | COLOUR | ALL,
timingSecondsPerStep, timingFastFrom)` is one of the fixture's own timing channels — the Revolution's
Focus (pan/tilt), Colour and Beam Timing, a move's **duration** at 1 s a step, 0 its own speed. Only the
Stage view reads them (`frontend/src/lib/travel.ts`); the desk sends every value as composed. Most
speeds are estimates (`FU-MANUAL-S8-TRAVEL`); movers' vector speed channels are deliberately not
modelled. See `docs/fixtures-engineering.md` §"Travel and timing channels".

### Venues
A **venue** is a project of kind `VENUE` (`projects.kind`: `SHOW | VENUE | VENUE_VERSION`, default
`SHOW`) holding a hall's rig — its patch, universes, riggings, regions, scene and viewpoints (venue
plan, `docs/plans/completed/venue-plan.md`). Its own rows are its **draft**; publishing freezes the draft as
the next **version**, a project of kind `VENUE_VERSION` made by `sync/KeptUuidCopy.kt` (export → a
fresh project uuid → import, every record uuid kept) and indexed by the machine-local
`venue_versions` (`venue/VenueVersions.kt`). A version is never listed (REST, MCP's `list_projects`,
the switcher), never current, and refuses every write: `routes/projectWriteGuard.kt` sits at the head
of the whole `/projects/{id}/…` subtree, so every route under it — `withProject`'s and the rest —
answers a snapshot's write 409 `VENUE_VERSION_READ_ONLY`, and an operator's stored write to a venue
403 `VENUE_ADMIN_ONLY` (opening one and the runtime gestures pass). Record is off in a venue
(`VENUE_RECORD_OFF`, REST and MCP). A venue **syncs as its own repo of published versions**
(session 4, `sync/VenueRepoSync.kt`): attached through the same Sync tab as a show's, the branch the
latest version plus `venueVersion.json`, each version a commit `vN: note` tagged `vN`; *Sync now*
materialises every tag the desk lacks as a snapshot (rebuilding `venue_versions`, which only ever
gains rows) and pushes every version the remote lacks, and a clean draft follows the newest in. The
draft never syncs (`VENUE_DRAFT_NOT_SYNCED`); a version's sync subtree is `VENUE_VERSION_READ_ONLY`.
`docs/sync-engineering.md` §"Venue repos".
What separates a version from the draft is `venue/VenueDiff.kt`, classified **changes output** or
**drawing only** — `docs/fixtures-engineering.md` §"Venue versions".

A **show on a venue** (session 3) pins `projects.venue_uuid` + `.venue_version` — both null for a
standalone show, which behaves exactly as before — and its rig is its own rows plus the pinned
version's, less its **hides** (`show_hides`, six record kinds) and less the version's Preset seating:
`RigScope` (`models/RigScope.kt`, `VenueLayer`) is the one place that says so, `RigScope.own` is
still the show's own. Keys and names are one namespace (`KEY_TAKEN_BY_VENUE`; unhiding over a show's
reuse is `KEY_TAKEN_BY_SHOW`); a write through a show to a venue's row is 409 `VENUE_OWNED`, never a
404 (`venue/VenueRigGuard.kt`) — a show makes two changes to one: it hides it, and it makes a venue
patch's **facets** its own (session 5, `show_overrides`): Address, Position, Focus, Media, each whole,
identity (name, key, type, head) still the venue's, laid over the version's patch by
`RigScope.effective` — so the loader, the Patch list, the Stage view and `describe_rig` all read the
overridden rig. Each override records the venue's value as its `base`; the pinned version's value
differing from it is **drift**, answered by *Keep mine* (base := venue's) or *Take venue's* (a revert),
and `ShowOverrides.drift` is the read session 6's Update uses (`docs/fixtures-engineering.md` §"Facet
overrides"). A hidden patch's overrides stay dormant; only patches take overrides
(`FU-VENUE-ELEMENT-OVERRIDES`); a show group may hold venue patches, a venue group never a show's (D12); a machine
override of a venue record resolves against the **venue's** id (P4), so a universe's address is the
same everywhere and is still set through a show. **Extract** moves a show's rig into a new venue,
publishes v1 and pins the show, in one transaction, its dry run that run rolled back
(`venue/VenueExtract.kt`). A show's archive (`formatVersion` 24, session 4) is its own rows, its
hides (`showHides/`) and its pin in `project.json` (`venueUuid`, `venueVersion`, a `venueRepo` hint);
the importer seeds its uuid maps from the pinned snapshot, and a show whose pinned version the desk
lacks is 409 **`VENUE_MISSING`** — on import, add-remote-project, clone and pull — naming the venue and
its repo, so a fresh desk imports the venue's repo first. A clone keeps the pin; a show with a
missing version cannot be made current and, current at startup, loads no rig
(`venue/VenuePins.kt`). **Detach** (session 7, `venue/VenueDetach.kt`, D9) flattens a show into a
standalone one: the effective rig at its **pin** — overrides applied, hides and Preset seating left
out — written into its own rows with every uuid kept, every `VenueLinks` link re-pointed onto them, the
venue's machine overrides of them **copied** to the show's id (P4), its hides, overrides and pin gone;
the venue is untouched, and a detached show syncs as a standalone show with no `formatVersion` change.
A venue's **Preset** seating (D6) is copied, never referenced: *Create* takes one
(`seatingPresetUuid`), *Replace from preset* swaps the show's for a fresh copy — the show's own row, no
`block`, the preset's uuid kept (`venue/SeatingPresets.kt`). Both are either role's: each changes only
the show. `docs/fixtures-engineering.md` §"Detach" and §"Seating presets". A show whose pin is older than the installed version is **behind** (session 6,
`venue/ShowBehind.kt`, `ProjectDto.venue.behind`): **`OUTPUT`** when an output change reaches it — one
to a patch it hides, or a re-address of one whose Address it overrides, does not — else **`DRAWING`**,
a banner. Behind on output it **opens held**: the loader judges it first
(`state/VenueHoldService.kt`) and every controller sends zero on every channel **but a parked one**
(a park gets through the hold), while the show still
composes and draws (`dmx/OutputHold.kt`); arm, fire and fixture command are 409 `VENUE_HOLD`. *Run
against* lifts the hold for that pin pair on this desk run, keeping the installed version's trigger
and command channels idle — and a publish or venue pull that puts a *running* show behind does that
for it rather than blacking it out mid-run; **Update** (`venue/VenueUpdate.kt`) moves the pin and re-points every
`VenueLinks` link in one transaction, dropping links to removed records, after a review that is the
same run rolled back. `docs/fixtures-engineering.md` §"Behind, the hold and Update". Its overrides travel as `showOverrides/{uuid}.json` at the same v24 (session 4
reserved the folder, so a session-4 desk refuses a show that carries one). A venue a show pins refuses
delete (`VENUE_IN_USE`) until its shows are detached or deleted. **Venue parks** (`formatVersion` 27): a venue's parked channels are
its rig, versioned with it, and a show on a venue runs its pinned version's parks overlaid by its own
re-parks and less its **releases** (`show_park_releases`, keyed by universe and channel, never a park's
uuid) — `models/VenueParks.kt` is the one resolution, read by the loader, a switch's hand-over
(`ParkManager.storedParks`), the reload carry, the hold and the project list's `parks` count. Unparking a venue park in a show releases
it (the version is never written), unparking a show's re-park brings the venue's back, a venue park
change is output for the hold unless the show parks or releases that channel, and a park in a venue is
an admin's (`parkError VENUE_ADMIN_ONLY`). `docs/fixtures-engineering.md` §"Venue parks". The Projects page (session 7) draws a
Venues row (each venue's card, an admin's *Extract a venue…*) over the Shows, each show card with its
venue badge, and a show's menu carries *Update venue* and *Detach*. `docs/fixtures-engineering.md`
§"Shows on a venue".

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
- **Handing the rig between shows never dips a kept channel to 0**: a project switch freezes the
  outgoing controllers on their last frame (`DmxController.freeze` — a blackout bar the channels the
  incoming project parks) before tearing anything down, and `Fixtures.register` waits for each old
  controller's final frame before building its successor. A **reload** — `switchProject(id, reload =
  true)`, passed only by a sync pull, a venue Update, Detach, Extract and a venue's Discard — carries
  the running show across (`show/ShowCarry.kt`, `Show.resume`: the frame, live cues and standbys, the
  programmer and Locate, hand-run effects) and crossfades what changed over `RESUME_FADE_MS`; every
  other same-id switch is a clean restart. Parks are per project and channel-level. See
  `docs/dmx-engineering.md` §"Handing the rig from one show to the next"

### FX System
Tempo-synchronized effects for continuous animations without complex scripts:
- **Seconds** - An effect's timing is a speed master or *Seconds*: its own cycle, `beatDivision` read as seconds, following no master (venue-visit plan D7; `fx/EffectTiming.kt`). A beat effect on Seconds runs on the wall-clock loop; a wall-clock effect's Seconds is no rate master. Stored as `seconds` on every effect home (sync `formatVersion` 29), carried by Record, Include, the effect routes and the MCP tools. A new effect is one bar on the master tagged for its family's `usage`, else master 1 (D16) — see `docs/fx-engineering.md` §"Seconds"
- **Size, Direction, N-shot** - Every running effect has a `size` (0–1: a weight applied *after* the blend, `out = before + (blended − before) × size × envelope`, so a Replace at 50 % sits halfway over what is underneath and an Around movement's radius halves), a `direction` (Forward, Backward, Bounce, applied to each member's phase) and an optional `nShot` (run N cycles, each member handing back after its own; the instance ends N cycles past the largest offset, a programmer layer's kept *spent*). `intensityMultiplier` is the envelope a fade moves, never a scale on the raw output. Stored on every effect home, synced from `formatVersion` 30 — see `docs/fx-engineering.md` §"Size, direction and N-shot"
- **The spread's shape** - The distribution strategy is the effect's **Order**, and four more fields shape it (effects plan D4, §3.3): `spread` (0–2, how much of the cycle the members cover; 1 is the Order's own offsets), `wings` (mirrored wings, only under an asymmetric Order — `CENTER_OUT`, `EDGES_IN` and `SPLIT` are two wings already and refuse it), `blocks` (neighbours sharing one phase) and `groups` (the spread repeating every N members; 0 is off), applied in `DistributionPlan.of` after the Order; `distinctSlots` is the rank count after Groups, so a step chase stays gap-free, and N-shot ends N cycles past the largest *shaped* offset. `FxDynamics` fields, edited in place; stored on every effect home, synced from `formatVersion` 33; the editor's phase dials are `frontend/src/lib/spreadShape.ts`, pinned to the desk by `src/test/resources/fx/spreadShape.fixture.json` — see `docs/fx-engineering.md` §"The spread's shape"
- **One effect over a selection** - An effect may target an ordered list of fixtures and groups (effects plan D3): `EffectTargetRef.Selection` beside `TargetRef` (`models/TargetRef.kt`), effect-only, so a value row, a layer's targets and a binding cannot hold one and their write boundaries refuse it; stored as `targetType: "selection"` with the list's canonical JSON as `targetKey` on a cue's ad-hoc effects and a Look's effects, synced from `formatVersion` 34. `FxEngine.buildSelectionExpansion` flattens it in order — a group as its members, a head named twice once — into one instance's members, distributed and shaped as a group's. `POST /fx/add` takes `targets`, Record and Include keep it one effect, MCP's ad-hoc effects take `targets`; the programmer's `+ Effect` and ⌘K *Apply FX* start one over the selection's rows — see `docs/fx-engineering.md` §"Over a selection"
- **SpeedMasterBank** - Per-show bank of named tempo buses (persisted, portable in sync); slot 0 = master 1, the global tempo the script API (`setBpm`/`tapTempo`) and a bare AI `set_bpm` mean (that tool takes an optional `speedMasterUuid` to retune any other master, and `create_speed_master` adds one). Effects subscribe via `speedMasterUuid` (null → master 1), and wall-clock effects scale their cycle via `rateSpeedMasterUuid` (null → unscaled); both are settable on every authoring surface. A master may also carry a `usage` (dimmer/colour/position/beam — the apply-time routing default, one per project) and a follow link (`follow_num`/`follow_den`/`follow_target_uuid`: the leader *drives the follower's clock*, so its beats land on the leader's rather than free-running; chains allowed, cycles refused; tempo writes on a follower are refused with `SPEED_MASTER_FOLLOWER`) — see `docs/fx-engineering.md` §"Usage routing and follow". One engine pass per (conflated) tick wake-up, however many masters tick. `SpeedMasterBank.beats` fans every master's beat boundaries into one keyed stream (`speedMasters.beat`) — master 1 included, under its real uuid once the bank has loaded. Hardware drives masters through the `speedMasterBpm` / `speedMasterTap` binding targets.
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
  CandleFlicker, FluorescentFlicker
- **Colour**: ColourCycle, RainbowCycle, ColourStrobe, ColourPulse, ColourFade, ColourFlicker
- **Position**: Circle, Figure8, Sweep, PanSweep, TiltSweep, RandomPosition
- **Beam**: ZoomBreathe, IrisPulse, FrostPulse, FocusPull — one property each (`zoom`, `iris`,
  `frost`, `focus`), offered only on a head that has it (effects plan D6)
- **Composite**: LightningStrike (dimmer; its colour half is computed but not applied)

There is no static effect. A fixed level, colour, position or setting is a **value** on the
fixture's own property — in the programmer, a Look or a cue — never an effect: StaticValue,
StaticSetting, StaticColour and StaticPosition were removed for that reason. A stored effect naming
one is skipped with a warn where a cue or Include spawns it (`EffectSpawner.createEffectInstanceOrNull`).

A **palette** may hold one effect instead of values — an *effect palette*, the busking pad for a
named effect. It is always generic (it names no target; the layer's targets are the fan-out), always
exactly one effect, and never both an effect and rows: which one a palette holds is its identity,
refused at the write boundary if a write would flip it. Its family is derived from the effect's
library `category` via `familyForEffectCategory`, so `controls` and `composite` are refused by name;
a Beam effect palette holds a beam built-in. `CueComposer` gained no new path — `effectsForLayer` takes `LayerContent.effects` and a
palette's one effect goes down the same *deferred* arm a Look's does. See
`docs/lighting-composition-model.md` §"A palette holds a value *or* an effect". A press is per
target on **both** arms and is the exact inverse of the pad's lit ring: "already on" is the record
covering every pressed target, and an off press clears them from every layer of that record. An
on press *releases* two things, both per target, not per target set, so a layer that only overlaps
the press is narrowed rather than left lit underneath: through a solo bank on a busk page, its
siblings of any kind (`ProgrammerLayerStack.toggle`'s `releaseSiblings`, resolved by the press
route, never read by the cook); and through every palette door, the other palettes of the pressed
palette's family and kind (apply-model plan D2, D6; `fx/LayerPress.kt`). A Look replaces nothing. The library itself is a flat list ordered by name. See §"The busk layout" in the same doc.

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
- `POST /api/rest/fx/add` - Add effect to a fixture (`fixtureKey`), or one effect over `targets` — fixtures and groups in order, a selection (effects plan D3), every item checked at once
- `DELETE /api/rest/fx/{id}` - Remove effect
- `POST /api/rest/fx/{id}/pause` / `resume` - Control effect
- `PUT /api/rest/fx/{id}` - Edit a running effect in place (id and phase kept); one parse with the `updateFx` frame (`applyEffectUpdate`), 400 `FX_UPDATE_REFUSED` / 404 `FX_NOT_FOUND`. `size` (0–1), `direction` (`FORWARD | BACKWARD | BOUNCE`) and `nShot` (≥ 1; `runForever: true` clears it) are dynamics fields, edited with the phase kept (effects plan session 1), and the add routes take all three. `spread` (0–2), `wings`, `blocks` (≥ 1) and `groups` (≥ 0, 0 off) are the spread's shape (effects plan session 2), dynamics fields too, the add routes taking them as well; wings above 1 under a mirrored Order — the request's or the effect's — is refused. `seconds` moves it onto *Seconds* or back to a master (D7) — `true` is also how a wall-clock effect goes back to unscaled; refused beside a master; that move restarts the effect's phase, since it changes the loop. `POST /fx/add` and `POST /groups/{name}/fx` take `seconds` too, and default an omitted `beatDivision` to one bar
- `POST /api/rest/fx/{id}/reset` - Re-apply a programmer palette layer's **current** effect to the instance it spawned, id and phase kept, and re-key it so the next recook keeps it (fixture-fx-sheets plan W5); 409 `FX_NOT_FROM_PALETTE` / `FX_PALETTE_GONE`. See `docs/fx-engineering.md` §"Programmer suppression and the priority band"
- `GET /api/rest/fx/library` - Available effect types

### Venue Endpoints
All four are **admin only** (mounted in `adminOnly`; venue plan §3.5, D3), REST only — no WS operation:
- `GET /api/rest/venues/{id}/versions` - History: every version newest first (`number`, `note`, `publishedAt`, `publishedBy`, `snapshotProjectId`), `installed`, and the draft's `{changes, outputChanges}` against it
- `GET /api/rest/venues/{id}/versions/{from}/diff/{to}` - What changed, `{output: [...], drawing: [...]}`; each side a version number, `0` the empty venue before v1, or `draft`. 404 `VENUE_VERSION_UNKNOWN`, 400 `VENUE_VERSION_INVALID`
- `POST /api/rest/venues/{id}/publish` `{note}` - Freeze the draft as the next version; 400 `VENUE_NOTE_REQUIRED` / `VENUE_NOTE_TOO_LONG` (500 characters), 409 `VENUE_DRAFT_EMPTY`. With a repo attached it publishes through it (session 4, P7): pulls first, 409 `VENUE_VERSION_TAKEN` when the remote already has the number (or refuses the push), else commits `vN: note`, tags and pushes, then makes the version here from the tagged tree; 409 `VENUE_PUBLISH_PULL_ONLY` on a `sync.push = false` desk. Fires `projectDetailsChanged`
- `DELETE /api/rest/venues/{id}/draft` - Discard the draft: the venue's rows become the installed version's again, uuids and all (name and description stay); 409 `VENUE_NOTHING_PUBLISHED`. A live venue reloads
- A show's id on any of them is 409 `NOT_A_VENUE`. *Add venue* is `POST /projects` `{kind: "VENUE"}` (admin); `ProjectDto` carries `kind`, and `FixturePatchDto` its `uuid`. Deleting a venue deletes its versions — refused 409 `VENUE_IN_USE` while a show pins it. History also answers `shows` (every show on the venue, with its version)

### Show-on-a-venue Endpoints
Venue plan sessions 3–7; REST only, no WS operation (`venue.hold` is outbound only):
- `POST /api/rest/projects/{id}/venue/extract` `{name, include?, dryRun?}` - **Admin only**: move a show's universes, patches, riggings, regions, VENUE-layer elements, seating (Fixed moves, Preset is copied), groups, camera views and parked channels into a new venue, publish v1 and pin the show — one transaction, uuids kept, links re-pointed through `VenueLinks`, the moved records' machine overrides and scene images following them. `dryRun` answers the same run rolled back. 400 `VENUE_EXTRACT_INCOMPLETE` (a part that would point out of the venue), 409 `VENUE_EXTRACT_REFUSED` / `_EMPTY`. A live show reloads
- `POST /api/rest/projects` `{…, venueId, seatingPresetUuid?}` - A new show on that venue, pinned to its installed version (409 `VENUE_NOTHING_PUBLISHED` before v1), with a copy of that version's Preset seating block as its own stalls when named (400 `SEATING_PRESET_UNKNOWN`, session 7); `ProjectDto.venue` is `{uuid, id, name, version, installed, behind, drawingChanges}`, `behind` `NONE | DRAWING | OUTPUT` (session 6). The list's venue rows carry `venueSummary` `{version, publishedAt, patches, universes, riggings, fixedSeating, shows, behind}` — the Projects page's venue card. Every list row also carries `parks` (its effective parked channels) and `safeBlackout` (*Make rig safe*'s blackout holds on it; runtime only), which the switch confirmation reads (navigation plan §4.3)
- `POST /api/rest/projects/{id}/detach` `{dryRun?}` - *Detach* (session 7, D9): the effective rig at the pin written into the show's own rows, uuids kept, links re-pointed, the venue's machine overrides copied, hides, overrides and pin cleared — one transaction, `dryRun` the same run rolled back. A behind show detaches at its pin (the answer's `behind`, `installed`). 409 `NOT_A_VENUE_SHOW` / `VENUE_MISSING` / `VENUE_DETACH_BLOCKED`. Both roles; a live show reloads, which lifts its hold
- `GET /api/rest/projects/{id}/seating/presets` + `POST …/seating/from-preset` `{elementUuid, force?}` - A venue's Preset seating at its installed version, or a show's at its pinned one, and the show's own seating; *Replace from preset* swaps every seating block the show owns for a fresh copy, moving its seat views to it (409 `STAGE_ELEMENT_IN_USE` naming one the copy has no seat for, `force` leaves it without one) and the stalls' scenery with it. 409 `NOT_A_VENUE` / `SEATING_PRESET_NAME_TAKEN`, 400 `SEATING_PRESET_UNKNOWN`. Both roles (session 7)
- `GET /api/rest/projects/{id}/venue/update?to=n` + `POST …/venue/update` `{to?, decisions, dryRun?}` - *Update* (session 6, `venue/VenueUpdate.kt`): the review — the hall's changes `{output, drawing}` beside `carryOver`, `drift` (an override the target moved under, kept unless answered `TAKE_VENUES`), `dropped` (hides, overrides and links whose record the target lacks), `decisions` (an address overlap the update introduces: `KEEP_BOTH | READDRESS_MINE | HIDE_VENUES | REMOVE_MINE`, P8), `blockers` (a key or name clash with the show's own, a show patch on a removed universe) and `deadReferences` (every key the show's cues, Looks and palettes name that the update removes) — is the update rolled back; the `POST` moves the pin and re-points every `VenueLinks` link in one transaction. 409 `VENUE_UPDATE_UNDECIDED` / `VENUE_UPDATE_BLOCKED` / `VENUE_UP_TO_DATE`, 400 `VENUE_UPDATE_INVALID`. Both roles; a live show reloads, which lifts its hold
- `POST /api/rest/projects/{id}/venue/run-against` `{on}` - Lift the hold for this session, or put it back (current project only, both roles; 409 `VENUE_NOT_BEHIND`); answers the hold. Runtime only: kept by a reload at the same pin and installed version, dropped by another project, a moved pin, a newer version or a restart
- Sync, export, import and clone take a show on a venue (session 4): `POST /projects/{id}/export` writes its own rows, hides and pin; `POST /projects/import`, `POST /cloud-sync/import` and `POST /projects/{id}/clone` answer 409 `VENUE_MISSING` (`{error, code}`) for a show whose pinned version is not on the desk, and so does `POST /projects/{id}/set-current` for one already here; `POST /projects/{id}/sync/run` refuses a pull that would pin one (`VENUE_MISSING`, before the working tree moves). A clone keeps the pin
- `PUT /api/rest/projects/{id}/patches/{pid}/hidden` `{hidden}` + `GET .../patches/hidden` - Hide a venue patch in a show, or bring it back; the hidden ones as Patch list rows. `GET /projects/{id}/hides` and `PUT .../hides/{KIND}/{uuid}` `{hidden}` do it for all six kinds (`PATCH | RIGGING | ELEMENT | REGION | GROUP | VIEWPOINT`). 409 `NOT_A_VENUE_RECORD`, `VENUE_HIDE_IN_USE` (something in the show still uses it), `KEY_TAKEN_BY_SHOW`. `FixturePatchDto`, `PatchGroupDto` and `UniverseConfigDto` carry `owner` (`SHOW | VENUE`)
- `PUT /api/rest/projects/{id}/patches/{pid}/facets/{facet}` `{value | null}` - Make one facet of a venue patch this show's own (session 5) — `ADDRESS | POSITION | FOCUS | MEDIA`, the whole facet (placements named by the venue placement's uuid), recording the pinned version's value as `base` when new — or take the venue's back with `null` (also *Take venue's*). Checked like the patch write it stands for, every problem at once: 400 `FACET_INVALID` / `FACET_UNKNOWN`; an address overlap is allowed and answered in `warnings` (P8). 409 `NOT_A_VENUE_RECORD` (a show's own patch, a standalone show, a venue), `FACET_PATCH_HIDDEN`; a version is `VENUE_VERSION_READ_ONLY`. A stored show write an operator may make. `POST .../facets/{facet}/keep-mine` is *Keep mine*: base := the venue's current value (409 `FACET_NOT_OVERRIDDEN`). Both answer `{patch, changed, warnings}`; `FixturePatchDto.facets` lists a venue patch's overridden facets `{facet, value, venue, drifted, base?, dormant}`, its fields already the overridden ones, and `universeConfigUuid` (also on `UniverseConfigDto`, as `uuid`). The patch PUT on a venue patch stays `VENUE_OWNED`, naming this route; the Stage editor's drag of one is not turned into a Position override

### Library Endpoints
- `GET /api/rest/gels` - The gel library (fixture optics plan D7), the desk's resource `gels.json` served whole: `{code, name, color, brand, estimate?}` per gel. A unit's fitted gel names one by code; see `docs/fixtures-engineering.md` §"Fitted media"
- `GET /api/rest/lanterns` - The lantern library a generic dimmer is hung with (§"Lanterns and focus" there)

### Remote Access Endpoints
- `GET/PUT /api/rest/install/tunnel` - Remote access (the ngrok tunnel), admin only, machine-local. The authtoken is **write-only** (answers say `hasAuthtoken`); every PUT field is optional, an empty `authtoken` or `domain` clears it and turns remote access off; turning it on needs a desk account, a domain and a token (`REMOTE_ACCESS_INVALID`). Live status streams as `tunnel.state`. See `docs/mcp-engineering.md` §"Remote access"
- `GET/PUT /api/rest/install/telemetry` - The install's Sentry switch, machine-local. `GET` is open to any caller, signed out included, on both listeners, answering `{enabled, release, environment, tracesSampleRate, browserDsn, replaysSessionSampleRate, replaysOnErrorSampleRate}`; `PUT {enabled}` is admin only and starts or stops Sentry live, the browsers' with it. `sentry.enabled = false` in local.conf wins. See `docs/observability-engineering.md`
- `POST /api/rest/install/onboarding/finish` `{openProjectId?}` - Admin only. A new desk's onboarding (`routes/installOnboarding.kt`): `installs.onboarding_pending` is set when the first admin is created and `GET /install` answers it as `onboardingPending`; finishing clears it, and with `openProjectId` switches to that project and deletes the project it left if that one is an empty standalone show with no repo (its export holds nothing but the default speed masters) — the `Default` the desk booted on. 409 `ONBOARDING_DONE`. The repo listing's `projectKind` is what lets onboarding import venues before their shows
- `POST /api/rest/install/quit` `{force?}` - *Quit desk* (venue-visit plan S6, D13): admin only and **never remote** — 403 `QUIT_LOCAL_ONLY` on the public listener whatever the role; 409 `RIG_NOT_SAFE` unless *Ready to switch off* holds or `force`. Answers 202, flushes the scaler writer and exits 0; the Windows launcher exits with it
- `POST /api/sentry` - The browser SDK's envelope tunnel, open to every caller on both listeners and outside the warm-up and auth gates: spooled to `<appDataDir>/sentry-browser/` only while telemetry is on, at most 1 MB, and only if its header names `sentry.browserDsn`; forwarded byte for byte by a worker only `module()` starts, and deleted when telemetry is turned off. See `docs/observability-engineering.md` §"The tunnel"

### Cue Stack Run Endpoints
- `POST /api/rest/projects/{id}/cue-stacks/{stackId}/standby` - Arm the next GO (`{entryId}`; null disarms). "Next" is server-owned — see `docs/cue-stacks-engineering.md` §"Standby". `reorder` (`{entryIds}`), `go-to` (`{entryId}`), `activate` (`{entryId?}`) and preview name **entries** too, and the run state is keyed by them (cue model plan session 2): a stack's list is `cue_stack_entries` (cue model plan session 1), each row a cue's place or a **marker**, and markers are `POST .../markers` + `PUT/DELETE .../markers/{entryId}`, never a cue (`POST /cues` refuses `cueType: "MARKER"`). A list plays in `(ordinal, uuid)` order (`ENTRY_ORDER`, a double ordinal, an insert or a drag taking the midpoint) and syncs as one record per entry, `cueStackEntries/{uuid}.json`, its number only when explicit or one a reader could not derive (sync `formatVersion` 32, cue model plan session 3). See `docs/cue-stacks-engineering.md` §"Database Tables"
- `POST /api/rest/projects/{id}/cue-stacks/{stackId}/add-cue` `{cueId, sortOrder?, insertByNumber?, cueNumber?}` - **Shares** a cue: a new entry, never a move, so one cue plays from several stacks, or twice in one (cue model plan session 4, D1, D7); editing it changes it everywhere. `PATCH .../entries/{entryId}` `{cueNumber?, autoAdvance?, autoAdvanceDelayMs?}` writes an entry's number and follow (the cue's PUT/PATCH no longer do); `DELETE .../entries/{entryId}` is *Remove from stack* — the cue stays, loose if it was its last entry, and removing the live entry moves nothing until the next GO, which fires its follower (D16). `POST .../entries/{entryId}/move` `{toStackId}` is *Move to stack…*: the same entry, uuid, explicit number, follow and anchor kept, to the end of the other stack (409 when it holds that number explicitly; show-cue-editing plan session 3). A stack delete leaves its cues (`{looseCueIds, deletedCueIds}`; `?deleteLooseCues=true` deletes the ones it left loose, D17). Deleting a cue is its own operation: `DELETE /cues/{id}` and `POST /cues/delete {cueIds, force?, dryRun?}` are 409 `CUE_IN_USE` naming every entry, pad, slot, anchor and binding until `force` (D8); `POST /cues` and Record CREATE without a stack make a loose cue. `POST /cues/{id}/duplicate` `{stackId?}` copies the cue — rows, layers, effects, hooks, notes, scenery and events — to the end of that stack, unnumbered, or loose without one (show-cue-editing plan D12), answering `{cueId, name, stackId?, entryId?}`. `CueDetails.entries` lists every place (`?entryId=` picks the one its place fields describe), prompt-book anchors are `PUT/DELETE /prompt-book/anchors/{entryId}`, and the include target carries `entryId`. See `docs/cues-engineering.md` §"Cue Stack Membership" and §"Delete"
- `GET /api/rest/projects/{id}/cue-stacks/{stackId}/preview?entryId=` - Compose an entry's cue without firing it (absent → the effective next), keeping every cue another stack or a press also holds. Layer 4 only — except `scenery`, the whole stage's scenery as the GO would land it, each element with `from` and the GO's duration; see §"Preview compose"

### Scenery Endpoints
- `PUT /api/rest/projects/{id}/cues/{cid}/scenery`, `.../cue-stacks/{sid}/scenery`, `.../looks/{lid}/scenery` - An owner's **whole** scenery list (stage-view plan session 8): `{scenery: [{elementUuid, state: {visible?, open?, trimM?}, transitionMs?}]}`, answered as stored. Each state is checked against its element's kind (`open` a drawn drape's, `trimM` a flown piece's), every problem at once; `transitionMs` is a cue's only (null moves with the cue's fade); a separator is refused (a marker is no cue, so has no scenery). Read through the owners' DTOs (`CueDetails.scenery` + `trackedScenery`, `CueStackDetails.scenery`, `LookDetails.scenery`), and every cue's own changes on the stack list, `CueStackDetails.cues[].scenery` (always encoded, batched by `cueSceneryByCue`; the cue table's Scenery column and the Prompt Book's glyph, cards and *On GO* line read it, scenery-programmer plan D13/D14 — so a cue scenery write fires `cueStackListChanged` as well as `cueListChanged`, and an element delete that swept scenery fires the cue, stack and Look lists); the Look list and busk pads' `LookDto` carries a summary, `scenery: [{elementUuid, elementName, state}]` (always encoded, batched by `lookScenerySummariesFor`), for the Looks sheet's Scenery column. Scenery **tracks** and sits **beside** the composition layers — never a channel, captured by Record only as what the programmer **holds** (the scenery-programmer plan's D7: into a cue a row only where it differs from what the cue tracks at every one of its entries, into a Look every held state; Include loads an owner's own rows back and Update writes them, D8 — `routes/programmerSceneryRecord.kt`), never on a palette — resolved by `show/SceneryResolver.kt` (the programmer's own scenery over live Looks over the live stacks' cues tracked from the top of the list, over each stack's set, over the element's base; the most recently GO'd stack wins) and streamed as `scenery.state`. A move not on a cue's own clock runs at the element's optional `params.travelS` (a DRAW/FLY drape's or a flown object's full travel, scaled by the share moved), else snaps. Deleting an element sweeps its changes. The reverse read, every owner that moves one element, is `GET .../stage-elements/{eid}/scenery` (§"Stage Scene Endpoints"). See `docs/cue-stacks-engineering.md` §"Scenery"

### Palette Endpoints
Palettes are what the desk once called templates (venue-visit plan D2). Everything a person or Claude reads says
palette — these paths, the `paletteListChanged` / `palettePressed` frames, the `PALETTE_*` codes, the
`create_palette` / `apply_palette` MCP tools — and the old API names are gone with no aliases (the UI's `/templates` route redirects). Storage keeps
*template*: the `templates` tables, the sync archive's `templates/` folder, `tmpl:` references, JSON
field names such as `templateId`, the stored `pressTemplate` binding and every code identifier.
- `GET/POST /api/rest/projects/{id}/palettes` + `GET/PUT/DELETE .../{tid}` - Palette CRUD. The list is ordered by name: a palette holds no position and no group, because a pad's place on a busk page is the only order there is. `TemplateDto` and `LookDto` both carry `buskPageCount`, the library row's "on *n* pages" hint — batched into `TemplateUsage` / `LookUsage`, kept out of their `describe()`, and never a delete guard (see `docs/lighting-composition-model.md` §"The busk layout")
- `POST .../palettes/{tid}/apply` (*Apply as values*, a chip's ⌥click → literals; `write: false` answers each head's literal as `values` and writes nothing, the focused-Look arm) and `.../toggle` (the press, a chip's tap → a tracking layer, per target on both arms: an off press clears the pressed targets from every layer of that palette). No solo siblings — those are a solo bank's — but an on press takes its heads off every other palette of its **family and kind** (value or effect) on every door, `released` counting them, and widens its own layer when that is the top of the stack (apply-model plan D2, D6, P3; `fx/LayerPress.kt`). See `docs/lighting-composition-model.md` §"A press is per target"
- `POST .../looks/{lid}/apply` `{targets, families?, fadeMs?}` - *Apply as values* for a Look: its rows as `WEB` values and its effects as Local effects, as a press followed by Detach (`programmer.detachLayer`) would leave them, with no layer; targets and mask by the toggle's rules. Not a toggle. §"Detach and Apply as values" there

### Busk Endpoints
- `GET /api/rest/projects/{id}/busk/pages` + `GET .../{pid}` - The busk layout, every page nested to the pad; a pad embeds its record's own summary DTO (`template` / `look` / `cue`) so the view needs no second fetch
- `POST /api/rest/projects/{id}/busk/pages` (appends; a duplicate name is 409 `BUSK_PAGE_NAME_TAKEN`), `PUT/DELETE .../{pid}`, `POST .../pages/reorder` (every page once, else 400)
- `PUT .../pages/{pid}/layout` - The **whole** page (`rows: [{columns: [{columnId?, width, banks: [{bankId?, name, solo, flow, pads: [{padId?, templateId|lookId|cueId}]}]}]}]`): pads without an id created, pads absent deleted, everything renumbered densely; refused as a whole with `BUSK_LAYOUT_INVALID` / `_IDENTITY` / `_REF` before touching a row; answers the page with the ids it minted
- `POST .../busk/banks/{bid}/pads` `{templateId|lookId|cueId}` - Appends **one** pad to one bank and answers the whole page. The additive exception to D10's whole-page rule — an append can never empty a column — for the surfaces that place a pad from outside the busk view (a cue's properties, the palette editor, the Look sheet, the programmer's create sheets), which hold no page document to re-`PUT`. Same `BUSK_LAYOUT_INVALID` / `_REF` codes; an unknown bank, or one in another project, is 404
- `GET/PUT /api/rest/projects/{id}/busk/rig` - The busk **rig**, the target band as the operator built it (busk-further plan D1–D3): the whole document on every write (`rows: [{rowId?, name, tiles: [{tileId?, groupId|patchId, elementKey?, cellMode, cellSplit?, label?}]}]`), refused as a whole with `BUSK_RIG_INVALID` / `_IDENTITY` / `_REF`, renumbered dense, ids minted and answered; the read embeds each tile's `GroupSummaryDto` or the patch's key, name and cells. Empty `rows` is stored and answered as empty — the show-all fallback is the client's. Current project only. A group or patch delete sweeps its tiles and fires `busk.rigChanged`
- `POST /api/rest/projects/{id}/programmer/spread` `{targets, families?, property, from, to, curve, order, parts, over, fadeMs?, seed?, write?}` - Spread, resolved on the desk (D9; editor-kit plan D3, D6): two intents (or `tmpl:{uuid}` for a colour), `curve ∈ LINE | MIRROR | ARROW | WINGS`, `order` a `DistributionStrategy` name (`LINEAR` = rig order, but a spread whose one target is a group runs in that group's member order, venue-visit plan S7), `parts` contiguous fans, `over ∈ HEADS | CELLS`; interpolates in the intent's own space (a position's two ends in one unit — `pos:`, centre-relative, or a stored `deg:` — else `SPREAD_INVALID`) and writes one literal per head into the programmer as owner `WEB`. Answers `{written, skipped, skippedFamilies}`; a property outside the mask writes nothing and names its family. Since the editor-kit plan's session 3: `write` (default true) — `false` runs the same resolve and **answers without writing**, the programmer's focused-Look-layer arm, whose client lands `written[].value` in the layer's draft; that value is each head's **literal** in the Look row grammar (`PropertyValue.serialize()`: `"0".."255"`, `"#rrggbb;w128"`, `"pan,tilt"`), not the intent — a colour-wheel head's is its slot's level under its own `colourWheel`; and the curve is spread over the heads that can take the property, found first — a head that cannot is skipped and consumes no position on it. Since venue-visit plan S1, over `HEADS` a multi-head fixture whose property lives only on its cells (the Shehds' colour) is one step whose value lands on every carrying cell, so `written[].target` can be a cell's element key the request never named; a cell named beside its parent is dropped. *Apply as values*, the cook and the resolve panel fan the same way (`fx/TemplateFanOut.kt`, `docs/lighting-composition-model.md` §"A press is per target").
- `POST /api/rest/projects/{id}/programmer/aim` `{targets, x, y, z, fadeMs?}` - Point moving heads at a stage coordinate (metres, FOH-relative Z-up): each head's pan/tilt (and fine pan/tilt) is solved from its world placement, base orientation and annotated travel (`show/FixtureAim.kt`, the inverse of the Stage view's drawing) and written into the programmer as owner `WEB`, like a spread. Answers `{written: [{target, value, panDeg, tiltDeg}], skipped: [{target, reason}]}`; a fixed head, an unplaced fixture, an axis with no degree range, a cell or a point outside the travel is skipped by name. 400 `AIM_NEEDS_SELECTION` / `AIM_INVALID` (a coordinate outside ±500 m). The `aim_fixtures` MCP tool is the same call with a `dryRun`, and a `saveAsPalette` that also records the aims as a per-fixture position palette (centre-relative `pos:`, each head's own coarse bytes) so they outlive the programmer. See `docs/fixtures-engineering.md` §"Aiming a head at a point"
- `POST /api/rest/projects/{id}/programmer/focus` `{targets, point: {x, y, z}, fadeMs?, write?}` - *Focus here* (fixture-optics plan D11): each head's FOCUS channel solved for its distance to the point, measured from its lens as the Stage view draws it (its placement through aim's maths, then the view's mover proportions to the pivot and lens, `MoverLens`), through the inverse of the Stage view's `resolveDeclaredFocusDistance` (`show/FixtureFocus.kt`; one shared vector, `src/test/resources/stage/focusInverse.fixture.json`, pins both sides), and written into the programmer as owner `WEB`. Answers `{written: [{target, value, distanceM}], skipped: [{target, reason}]}`; `write: false` answers without writing. A head with no focus channel, no declared range, no placement or a point outside its range is skipped by name; 400 `FOCUS_NEEDS_SELECTION` / `FOCUS_INVALID`. `aim_fixtures`' `focus: true` is the same solve on the aim point, for the heads aim aimed. See `docs/fixtures-engineering.md` §"Focusing a head on a point"
- `POST .../busk/pads/{padId}/press` `{targets, families?}` - The busk view's press: a palette or Look → `ProgrammerLayerStack.toggle` with the bank's siblings when the bank is solo; a cue → takes or lets go of a **loose hold** (`fx/CueHolds.kt`), live above every stack, lit from `cueHolds.state`. A generic palette pad pressed with no selection is 400 `PALETTE_NEEDS_SELECTION` (a per-fixture one lands on its own heads). Solo means pressing one turns its siblings off — a layer sibling narrowed on the pressed heads, a live cue sibling stopped, and a cue press taking layer siblings off wholesale (`release`). An off press releases nothing; an on palette press also takes its heads off the other palettes of its family and kind, solo or not, and `released` counts those too. `families` is the selection's attribute mask (absent = every attribute, an unknown name 400), tested on the **on** arm only — an off press comes off under any mask (`routes/pressArm.kt`): a palette outside it is 400 `PALETTE_OUTSIDE_MASK`, a Look lands with `propertyMask = mask ∩ its families` and answers `skippedFamilies`, or 400 `LOOK_OUTSIDE_MASK` when nothing is inside; a cue ignores it. The same `families` rides `/palettes/{id}/apply`, `/palettes/{id}/toggle`, `/looks/{id}/toggle` and `/looks/{id}/apply`, and the MIDI `PressPad` / `PressTemplate` doors pass the desk's. See `docs/lighting-composition-model.md` §"A press is per target" and §"The busk layout"
- `POST .../looks/{id}/toggle` with empty `targets` presses a Look with no deferred effect onto its own patched fixtures (400 `LOOK_NEEDS_SELECTION` / `LOOK_NO_TARGETS` otherwise); `POST .../cue-slots` takes exactly one of `lookId` / `cueId` and refuses a deferred-effect Look with 409 `CUE_SLOT_LOOK_NEEDS_SELECTION`

### Effects Endpoints
- `POST /api/rest/projects/{id}/effects/arm` `{on, seconds?}` - Arm the desk for `seconds` (default 60, 5–600) or disarm it; answers the arm as `effects.armed` carries it. Current project only. **Both roles** may arm on the desk's own listener; on the public listener it — and fire and reload — is 403 `REMOTE_EFFECTS_DISABLED` unless an admin allows it (`requireEffectsAccess`, the `requireScriptAccess` twin; Install settings → Remote access). REST rather than WS so the socket gains no operation (stage-view plan P2)
- `POST /api/rest/projects/{id}/patches/{pid}/fire` `{trigger, rehearse?}` - Fire one tube now: 409 `TRIGGER_NOT_ARMED` unarmed, 409 `TRIGGER_SPENT` on a spent tube (nothing sent), 409 `VENUE_HOLD` while the show is behind its venue on output (arming too), 400 `TRIGGER_UNKNOWN`. `rehearse` — or a blind programmer — draws it on every window and sends nothing, needing no arm and spending nothing
- `POST /api/rest/projects/{id}/patches/{pid}/reload` `{trigger?}` - Mark one tube, or every tube, loaded
- `PUT /api/rest/projects/{id}/cues/{cid}/events` - A cue's **whole** event list (`{events: [{patchId, trigger, offsetMs}]}`), every problem at once, answered as stored (`CueDetails.events`). Events fire on GO into the cue only — never tracked, never on GO TO a later cue or GO BACK, never previewed — and only while armed; unarmed they are skipped and announced, never queued. They fire on the cue's **first** hold (cue model plan D15): a GO onto a cue another stack or a press already holds fires none, and a loose press (`/cues/{id}/apply`, the AI's `apply_cue`, a busk pad, a cue slot) fires them through the same hook (`EffectsService.onCueGo`). Sync `formatVersion` 21

### Grand Master Endpoints
- `GET/PUT /api/rest/projects/{id}/grand-master` `{level}` - The live project's grand master, 0–100 % (venue-visit plan S5, D5): 400 `GRAND_MASTER_INVALID` outside 0–100, 409 when the project is not current; both roles, a runtime gesture on a venue. Applied at transmit by `midi/GlobalScalerState.kt` — every DIMMER and UV channel scaled, a dimmerless head's colour mix (RGB, WHITE, AMBER) too (D12), a STROBE cut only at 0, park untouched. Per project and machine-local: `project_scaler_states` (excluded from sync) keeps it across a switch and a restart. `grandMaster.state` / `grandMaster.set` are its WS frames, `grandMasterFader` its MIDI target beside `grandMasterToggle` (which takes it to 0 and back), and the app header's Grand Master overview panel its UI (summoned, never resident: a fader always under the pointer was too easy to move)

### Fixture Command Endpoints
- `POST /api/rest/projects/{id}/patches/{pid}/commands/{command}` - Run one fixture command (a reset, a lamp strike, a lamp off): the desk holds its level for its declared time and answers when the hold ends (`{completed}` false when cut short). 400 `COMMAND_UNKNOWN`, 409 `COMMAND_BUSY` / `COMMAND_BLIND` / `COMMAND_PARKED` / `VENUE_HOLD`; on the public listener 403 `REMOTE_COMMANDS_DISABLED` unless an admin allows it (`requireCommandsAccess`, Install settings → Remote access). Current project only; both roles locally. No WS frame — the hold is the request. MCP's `run_fixture_command` is the same call, held to the same setting
- `POST /api/rest/projects/{id}/commands` `{role, targets?}` - A bulk run (venue-visit plan S6): one role's command (`LAMP_ON` / `LAMP_OFF` / `RESET`) over `targets`, or the whole rig without them; a strike staggered by each unit's `strikeSpacingMs` and waiting out its restrike time. Answers the run at once; how it goes streams as `rigSafety.state`. Busy, parked and refused units are reported per unit. 400 `COMMAND_ROLE_UNKNOWN` / `COMMAND_NEEDS_SELECTION`, 409 `COMMAND_NO_UNITS` / `COMMAND_RUN_BUSY` / `COMMAND_BLIND` / `VENUE_HOLD`. `POST .../commands/cancel` stops what has not started (409 `COMMAND_NO_RUN`)
- `POST /api/rest/projects/{id}/rig-safe` `{targets?}` - *Make rig safe*: every lamp off (the selection's when named), the holds waited out, a runtime-only blackout, then *Ready to switch off* while nothing sends output. `POST .../rig-safe/undo` strikes those lamps, staggered, then releases the blackout (409 `RIG_SAFE_NOTHING_TO_UNDO`); `POST .../rig-safe/release` releases the blackout alone. All three and the bulk run are held to `requireCommandsAccess` remotely, both roles, current project only, runtime gestures on a venue

### Stored Locate Endpoints
- `POST /api/rest/projects/{id}/locate/store` `{targets}` - *Set locate from current* (venue-visit plan D6, D17): each unit the targets reach that pans and tilts (the fixture's own, each head's) takes the pan and tilt — coarse and fine — **the desk is sending it now** as its stored locate, `fixture_patches.locate` (`[{head?, pan, tilt, panFine?, tiltFine?}]`, `fixture/locate/StoredLocate.kt`), which `LocateValueResolver` then points Locate at instead of the centre; every other property keeps its category default. `POST …/locate/clear` `{targets}` takes it away. Both answer `{stored, skipped}`, 400 `LOCATE_NEEDS_SELECTION`, current project only, stored writes (a venue's is an admin's). On a venue patch **in a show** both write the show's **Focus** override (`FocusFacet.locate`), never the venue's row — *Clear* leaves the show with none. A located unit is re-asserted at the new point. `FixturePatchDto.locate` carries it; MCP's `get_patch` reads it and `patch_fixtures` / `place_fixtures` take a `locate` list (bytes; a venue fixture's through its Focus facet); sync `formatVersion` 31; drawing only for the venue hold. See `docs/fixtures-engineering.md` §"Stored locate"

### Stage Scene Endpoints
- `GET/POST /api/rest/projects/{id}/stage-elements` + `GET/PUT/DELETE .../{eid}` - The scene document (stage-view plan session 2): named venue and set elements — `ROOM | PROSCENIUM | FLAT | DRAPE | PLATFORM | SEATING | OBJECT`, layer `VENUE | SET`, a pose (Z is the base, except a platform's, which is its top surface, as a region's `centerZ` is), a size, a finish and `params`, a sealed `ElementParams` per kind (`models/stageScene.kt`) checked at the write boundary with every problem at once. A `PUT` is partial and the merged element is checked whole. A drape's `params.fabric` (`CANVAS | MUSLIN | SHARKSTOOTH | BOBBINET`, absent velour) and a drape's or a flat's `params.paint` (`{front?, back?}`, each a scene image's SHA-256, refused as `params.paint.front names no stored image` unless this project's store holds it or the element already did) are the scrim plan's session 1, drawn by the Stage view since session 2 (only velour pleats; the paint on both faces, its alpha cutting holes — `frontend/docs/stage-vis-engineering.md` §"Painted cloths"). An `OBJECT` with `params.shape: MODEL` is built from `params.parts` — boxes, cylinders and cones, spheres, extruded outlines and lathed profiles, each turned, coloured and optionally textured with a scene image — fitted to the element's box (`models/sceneModel.kt`, the bounds pinned on both sides by `src/test/resources/stage/modelBounds.fixture.json`): how another Claude session builds a set piece no primitive draws, through `set_scene` (whose `modelFrom` places copies). A part's texture is a referenced scene image like paint (`sceneImageHashesOf`), which is why sync is v25 — `docs/fixtures-engineering.md` §"Model objects". `StageElementDto.fullDetail` is this machine's *Full detail* override, never synced. Deleting or reshaping a seating that seat views sit in is 409 `STAGE_ELEMENT_IN_USE`, `?force=true` leaves them dangling. Stored data only, so ungated by the current project, like stage regions. See `docs/fixtures-engineering.md` §"The scene document"
- `POST /api/rest/projects/{id}/scene-images` (raw `image/png` | `image/jpeg` body) + `GET .../scene-images` + `GET .../scene-images/{hash}[?variant=display|detail|mask]` - The scene-image store (scrim plan session 1, §3.3–3.4): the images a cloth is painted with, content-addressed per project at `<appDataDir>/scene-images/{projectUuid}/{sha256}.{png|jpg}` (`state/SceneImageStore.kt`). The upload is idempotent by hash and answers `{hash, width, height, hasAlpha, mediaType}`; at most 25 MB (413 above) and 8192 px a side, the size read from the header **before** any decode; 400 `SCENE_IMAGE_INVALID`. The list is every image this machine holds (what the element sheet's thumbnails and "Image missing on this machine" read). A `GET` serves the original or a derived copy made on first request — 2048 px, 4096 px (both by successive bicubic halving, alpha kept) or the 256 px alpha mask — immutable-cached; 404 `SCENE_IMAGE_UNKNOWN`. An image no element has referenced for 7 days is pruned at project load. Under the stage-element routes' gate (any signed-in account) and stored data only, so ungated by the current project. Synced as raw bytes at `sceneImages/` from `formatVersion` 23 (`sync/SceneImageRepoSync.kt`). See `docs/fixtures-engineering.md` §"The scene document"
- `PUT /api/rest/projects/{id}/stage-elements/{eid}/display-detail` `{full}` - The per-machine *Full detail* switch (scrim plan D12): a `machine_overrides` row (`stage_elements` / `displayDetail` = `"4096"`), answered as the element's `fullDetail`; fires `stageElementListChanged`
- `GET /api/rest/projects/{id}/stage-elements/{eid}/scenery` - What moves one element (scenery-programmer plan D11): `{cues: [{stackId, cueId, label, state, transitionMs}], sets: [{stackId, name, state}], looks: [{lookId, name, state}]}`, each owner's own stored row (never what a cue only tracks), cues in show order (once per entry; a loose cue after them with `stackId` null), sets in stack order, Looks by name. Stored data, gated like the element routes. The Stage popover's and the element form's *Moves with*
- `GET/POST /api/rest/projects/{id}/stage-viewpoints` + `GET/PUT/DELETE .../{vid}` - Saved viewpoints: `ORBIT` / `EYE` (eye, target, lens) or `SEAT` (a seating element's uuid and a seat id like `F6`, optionally a target and lens). Plan, Front and Side are built in and never rows. The MCP pair is `set_scene` (upsert by name, all-or-nothing, `template: "proscenium-hall"`) and `get_scene`

### Group REST Endpoints
- `GET /api/rest/groups` - List all fixture groups, each with its `memberKeys` in the group's order (`sort_order`)
- `GET /api/rest/groups/{name}` - Get group details with members, and `distributionSteps`: each strategy's step per member by `index`, the order its effects and a spread run in (venue-visit plan S7)
- `PUT /api/rest/projects/{id}/patch-groups/{gid}` `{name?, memberOrder?}` - Rename or reorder a stored group. `memberOrder` is patch ids: the members named fill the places they held, in the order given, and a member not named (a venue patch the show hides) keeps its own (`reorderGroupMembers`). A venue's group from a show is 409 `VENUE_OWNED`. See `docs/groups-engineering.md` §"Member order"
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
- `programmer.keyStack` - What sits under one property, top first (W1): park, programmer-band effects, each owner's slot, other effects with `heldBack`, the cue contributor, the base, each with `onStage`; a group per member. Request/reply on the asking socket with a client `requestId`, never broadcast. `heldBack` is `fx/EffectSuppression.kt` and `fx/BlindHold.kt` — the rules the tick and provenance ask too, against the engine's own snapshot — and `heldBackReason` says which (`STOMP | PROGRAMMER | BLIND`). See `docs/lighting-composition-model.md` §"Reading one property's stack"
- `programmer.clearTarget` / `programmer.targetCleared` - A fixture's (heads included) or group's *Release* (W2): its local effects (`FxInstance.isLocalEffect`) stopped first, then every non-layer slot and the sideband on its channels released in one pass and one republish at `fadeMs`; a local effect that also drives heads outside it is left running and named in `partial`. Unicast reply
- `programmer.detachLayer` `{layerId}` - *Detach* (apply-model plan §3.5): the layer's values on stage become touched `WEB` values and its effects Local effects, phase kept, and it comes off with nothing on stage moving (`ProgrammerLayerStack.detach`). Answers `programmer.layerState`. An operator gesture, so `FU-AUTH-WS-PER-MESSAGE` does not fire
- `fxChanged` - Broadcast on effect add/remove/update
- `rigSafety.state` - The end of the night (venue-visit plan S6), outbound only and `StateFlow`-backed: `{projectId, ready, readySince?, blackout, undo, notOff, run?, lampsOff, quitAllowed}` — a bulk run's units with their status and time left, *Ready to switch off*, *Make rig safe*'s blackout, what *Undo* would strike, the restrike ledger, and whether this socket may quit the desk. Every operation is REST. See `docs/websocket-engineering.md` §"Rig safety"
- `groupsState` - Request/receive fixture groups state
- `clearGroupFx` - Clear all effects for a group
- `groupFxCleared` - Confirmation of group effect removal
- `paletteListChanged` / `palettePressed` - Palette CRUD (contents edits ride `cuesRecomposed`), and a press from any door stamping `lastPressedAt` — keyed `{templateId, lastPressedAt}`, so the client patches its cached list without a refetch
- `cuesRecomposed` - A Look/palette *contents* edit, or a forced Look delete that took cue layers, changed what the named `cueIds` compose to. The keyed counterpart to `lookListChanged` / `paletteListChanged`, which deliberately don't fire for a contents edit; carries every cue layering the edited record, not just the live ones re-transmitted
- `busk.layoutChanged` - Keyed `{pageIds}`: page CRUD or reorder, a whole-page layout write, a pad appended to a bank, or a palette / Look / cue / cue-stack delete that took pads off those pages. The busk view re-reads exactly those pages. The library rows' `buskPageCount` is refreshed by the *client*, not by a `paletteListChanged` the backend would have to fire on every gesture: an own write invalidates the two lists only when `recordsOnPage` actually moved, a foreign frame invalidates them wholesale. See `docs/websocket-engineering.md`
- `windows.announce` / `windows.state` / `windows.show` / `windows.rename` / `windows.fullscreen` / `windows.follow` / `windows.viewOptions` - The desk's registry of signed-in browser windows (`state/WindowRegistry.kt`), and the family that lets one screen move another. A row is keyed by the **socket**, not by the client-minted `windowId` a duplicated tab copies: `id` is minted per connection and is what everything addresses, the row lives exactly as long as its socket (announce in, `remove` in the connection's `finally`), and a re-announce replaces it in place. `windows.state` is `StateFlow`-backed, so the connect snapshot is free and arrives before this window has announced. The five commands are **rebroadcast verbatim to every socket** (multi-screen plan D11) — a `targetId` that is not this window's matches nothing, so no handler needs a session lookup, and a command to a disconnected window is lost and visible as its unchanged `view`. **Machine-scoped**: registered in the pre-warm-up band of `Sockets.kt` beside `setupMachineSubscriptions` and *not* cleared on project switch, because a window outlives one and its `view` carries the project id — a registry in the show band would announce nothing until the desk was warm. Nothing persisted, no table, no `SyncCoverageTest` row. See `docs/desk-screens.md`
- `hand.state` / `hand.pickUp` / `hand.drop` - The desk's **hand** (`state/HandState.kt`): one held
  record — a palette, a Look or a cue — picked up on any window and placed on any other, which is
  the cross-window move instead of a pointer drag (multi-screen plan D12). **There is no
  `hand.place`**: every place target already has a mutation with its own validation, so a place is
  the placing window's own mutation followed by `hand.drop`, and Undo is its inverse. `StateFlow`-backed,
  so the subscription is the snapshot; the frame carries the record's **own summary DTO**, exactly as
  `BuskPadDto` does, so every window builds the ghost's face without a lookup. `pickUp` resolves in
  the current project only and stamps `pickedUpOn` from the socket's announced window, never from the
  payload; a second pick-up replaces. Project-scoped — cleared by the project collector, unlike
  `windows.*` — plus a five-minute timeout and a reconcile on `lookListChanged` /
  `paletteListChanged` / `cueListChanged`. The MIDI doors are `PickUpPad` / `HandPlaceInBank` /
  `HandDrop`. See `docs/lighting-composition-model.md` §"The busk layout"
- `selection.subselect` `{mode}` - Rewrite the selection's targets over the **rig order** (`state/BuskRigOrder.kt`): `ALL | ODD | EVEN | FIRST_HALF | SECOND_HALF | INVERT | NEXT | PREV | MASTERS`, counting cells where a selected fixture has them and heads where none does, `NEXT`/`PREV` stepping the whole selection one step of the rig and wrapping. The Cells chip and the `SelectionCells` / `SelectionNext` / `SelectionPrev` bindings share it; answers the ordinary `selection.state`
- `busk.rigChanged` - Payload-free: the rig document changed (a write, or a group / patch delete that took tiles off). One rig per project, so no id to key on
- `stageElementListChanged` / `stageViewpointListChanged` - Payload-free: scene-element or saved-viewpoint CRUD, through REST or `set_scene`
- `venue.hold` - The live show's hold (venue plan session 6), outbound only and `StateFlow`-backed: `{projectId, held, pinned, installed, runAgainst}`. `held` while it is behind its venue on output and every controller sends zero but for a park; `runAgainst` once lifted for the session. *Run against* and Update are REST. See `docs/websocket-engineering.md` §"Venue hold"
- `effects.armed` / `effects.fired` / `effects.skipped` - The desk's one-shot effects (stage-view plan session 9), outbound only: `effects.armed` is `StateFlow`-backed (the arm, `remainingMs` at send, `rehearsal`, every spent tube on this machine), so the subscription is the snapshot; `effects.fired {fixture, trigger, label, at, rehearsed, source, cueId?}` makes every Stage view throw confetti; `effects.skipped` announces fires that did not happen (unarmed GO, a spent tube, an arm that dropped, a show held by its venue — `VENUE_HOLD`). Arm, fire and reload are REST, so no inbound frame exists. See `docs/websocket-engineering.md` §"Effects"
- `scenery.state` - The live scenery (stage-view plan session 8), `StateFlow`-backed so the subscription is the snapshot: `{projectId, elements: [{elementUuid, state, from, startedAt, elapsedMs, durationMs, source}], staged?}`, every element a scenery change names or the programmer holds (absent = its base). `source` names what holds each piece (`{kind: base | set | cue | cueLook | programmerLook | programmer, stackId?, cueId?, label?, lookId?, name?}`, its highest-tier state's); `staged` (`[{elementUuid, state, from, elapsedMs, durationMs}]`) is present only while the programmer is blind and holds a change — what leaving Blind would land, for the Stage view's programmer sources (scenery-programmer plan D4, D12; both additive). Recomputed by `SceneryService` on every GO (`onEntryLive`) and every loose hold taken or let go (`onLooseHeld` / `onLooseReleased`), stack stop, programmer layer or scenery change, blind and owner/element edit. A client animates from `elapsedMs` at receipt, never the wall-clock `startedAt`. Outbound only
- `programmer.setScenery` / `programmer.clearScenery` / `programmer.sceneryState` - The programmer's own scenery (scenery-programmer plan D1, `state/ProgrammerScenery.kt`): a runtime-only overlay, element → partial states, the resolver's top tier. `setScenery {elementUuid, state, fadeMs?}` merges states onto one element, checked against its kind (refusal: `programmer.error`, every problem named); `clearScenery {elementUuid?, fadeMs?}` lets one go, or all with no uuid. `programmer.sceneryState {projectId, elements: [{elementUuid, state}], changedSinceInclude?}` is `StateFlow`-backed (snapshot + broadcast, and the reply to both); `changedSinceInclude` counts the held pieces Update would write that the included source does not already say, against a baseline Include, a retargeting Record and a Mode A Update set. Blind stages it, Clear releases it on the Clear fade, a project switch drops it (a carried reload of the same project keeps it, less pieces whose element the reload removed); a fade above 0 is the move's clock, else the piece's `travelS`. Operator gestures, so `FU-AUTH-WS-PER-MESSAGE` is not fired. The AI's `move_scenery` writes the same overlay (no remote-access gate: scenery is drawn, never output)
- `stageRender.request` `{requestId, token, projectId, viewpoint, width, height, source, workLights, timeoutMs}` - `render_view`'s job for **one** window (stage-view plan session 4): draw a viewpoint (already the Stage view's vocabulary — a camera, a saved view's uuid, `seat:<uuid>:<id>`) offscreen and upload the PNG. `workLights` (stage-view menu plan D9) is the tool's optional boolean, always sent, default false — the room as lit; it is the capture's alone and never touches the drawing window's own work lights. The only unicast frame: it goes down the chosen socket's own queue in `StageRenderService`, and only a signed-in socket on the desk's own listener is ever attached (never one on the public listener, decided by port). Outbound only — the answer is `POST /api/rest/stage-renders/{requestId}` (raw PNG, `X-Render-Token`, the socket's own session, ≤ 4 MB) or `…/failure {reason}`, so the socket gains no inbound message and `FU-AUTH-WS-PER-MESSAGE` stays unfired. See `docs/mcp-engineering.md` §"`render_view`"
- `windows.viewOptions` `{targetId, view, options}` - One generic command for a window's per-view options (busk `focus`, `rigRows`, `sheet`; the Stage view's `viewpoint` — a camera, `orbit | eye | plan | front | side`, a saved `stage_viewpoints` row by its uuid, or an unsaved seat `seat:<seating uuid>:<seat id>` — its `source`, the vis source `output | outputProgrammer | programmer | nextGo`, and its `workLights`, `off | on` — all three per window, stage-view plan sessions 1–3 and the stage-view menu plan's D8), rebroadcast like the other four; the target applies them for that view only and re-announces. Every key rides inside `viewOptions`, never beside it: the announce's Json is bare, so a new top-level key would drop the frame. `windows.announce` carries `viewOptions?` (absent from an older client) and `windows.state` carries it back
- `windows.follow` `{targetId, on}` - Link a window to the desk selection or give it its own, from another window's Screens row (desk-follow plan D4). `windows.fullscreen`'s exact shape, rebroadcast verbatim; nothing is written server-side — the target applies it (or refuses an `on: false` in busk Rig / Pads focus) and re-announces `follows`. A window's fact, not a view's, so not a `viewOptions` entry
- `selection.state` / `selection.set` / `selection.toggle` / `selection.clear` - The desk's one shared selection (`state/DeskSelection.kt`): what a selection-relative surface control and a busk press act on. `StateFlow`-backed, so the subscription is the connect snapshot; writes reply nothing (a no-op write sends no frame). `toggle` is head-by-head: a group all of whose members are selected is "in", and toggling it off narrows the entries that covered it. The frame is the whole fact — `targets`, `families` (the attribute mask, absent = every attribute) and `source` (who moved it last, `{kind: window | surface, id?, name}`): `set` replaces all of it, `toggle` keeps the mask, `clear` drops both (multi-screen plan D2). `source` is stamped by the handler from the socket's own announced window (`SocketScope.window`, set by `windows.announce`) and is never read from a payload; `source.id` is that window's **registry row id** — the one `windows.show` addresses, not the client-minted `windowId` a duplicated tab shares. A socket that has not announced falls back to session 1's `sourceName` stub, remembered on the `SocketScope` and carrying no id (`FU-WINDOWS-RETIRE-SOURCENAME`)
- `surfaceEncoderBank.state` / `surfaceEncoderBank.set` - Which attribute each device's **strip encoders** drive (`deviceTypeKey → propertyName`, default `dimmer`). One frame type — it is a `StateFlow`, so the subscription is snapshot and broadcast both. A *strip* is one binding row whose `controlId` is a profile-declared strip id: `ControlSurfaceBindingService.resolve` answers the control's own binding across both bank levels first, then derives the strip's to the role that control plays (fader → dimmer, select → `SelectTarget(TOGGLE)`, encoder → the encoder bank's property, flash → `Flash`). See `docs/midi-control-surface-engineering.md` §"Strips" and §"Encoder bank"
- `grandMaster.state` / `grandMaster.set` - The live project's grand master, 0–100 %: `state {projectId, level}` is `StateFlow`-backed and follows the live show, so the subscription is the snapshot; `set {level}` is the Grand Master panel's fader, clamped, `CONTINUOUS` in `TraceSampling.WS_MESSAGES`. `surfaceScaler.state`'s `grandMasterEnabled` is the same level read as `> 0`. See `docs/websocket-engineering.md` §"Grand master"
- `surfaceControls.state` / `surfaceControls.changed` - Per-device control state as the hardware was told it (`value`, `physical`, `touched`, `led`, `ring`), from `ControlStateTracker`: a whole-device `.state` on connect and after every full resync, conflated `.changed` deltas at most every 50 ms per device. The Surfaces view draws exactly this and never recomputes from DMX
- `tunnel.state` - Remote access's live status (`off | installing | starting | online | error | no-binary`, with download progress, the URL, ngrok's `ERR_NGROK_…` code and whether it is retrying). Machine-scoped, **admin sockets only** (a zero-user desk's socket counts), snapshot on connect; the frame carries the state, so the client patches rather than refetches
- `cueRunStateChanged` - A cue stack's live cue, armed next, and fade timing — `activeEntryId`, `nextEntryId` and `standbyEntryId` beside the cue ids, since a stack's position is an **entry** (cue model plan P2). One frame per transition from `CueStackManager` (so REST, the MIDI surface and auto-advance all report), plus a snapshot on connect; clients animate the fade locally from `fadeElapsedMs`
- `cueHolds.state` - What holds every live cue (cue model plan P3, `fx/CueHolds.kt`), outbound only and `StateFlow`-backed (`State.cueHoldsFrame`, so it survives a project switch): `{projectId, cues: [{cueId, priority, holds: [{kind: STACK, stackId, entryId} | {kind: LOOSE, seq}]}]}`. A cue is live while anything holds it and comes off with its last hold; its priority is its highest hold's, a loose hold (`/cues/{id}/apply`, `apply_cue`, a busk cue pad, a cue slot, MIDI `FireCue`, the hand) at `LOOSE_BAND + seq`, above every stack, newest on top. The busk pad's and cue slot's rings read it — solid for a loose hold, outline for live only in a stack. See `docs/cue-stacks-engineering.md` §"Holds"

## Database

Uses Exposed ORM over an embedded SQLite file (`org.sqlite.JDBC`, pooled by HikariCP
with `maximumPoolSize = 1` — SQLite has a single writer and a larger pool produces
`SQLITE_BUSY` under load). The DB path comes from `database.path` in `local.conf`,
defaulting to `<appDataDir>/lighting7.db`. Tables auto-create on startup via
`SchemaUtils.createMissingTablesAndColumns`.

SQLite is the only supported backend. `StateMigrations.kt` is gone;
`state/InstallBootstrap.kt` holds only the install-identity row, and explains what
was removed and why.

**One schema migration exists:** `state/CueModelMigration.kt` (cue model plan D5),
which moves a stack's list off `cues` onto `cue_stack_entries`. `State.initDatabase`
calls it **before** `createMissingTablesAndColumns` — the exception to
`InstallBootstrap.kt`'s "after the schema is created", for the reason its KDoc gives
— and it runs only while `cues` still has `cue_stack_id`. It backs the database up
first (`VACUUM INTO '<db>.pre-cue-model-<utc>.db'`), then converts in one transaction
in raw SQL, rebuilding `cues` and `prompt_book_anchors` from the model.
`CueModelMigrationTest` runs it over `src/test/resources/db/pre-cue-model.sql`, the
old shape frozen; never regenerate that file from a later commit.

**One one-shot data step follows it:** `state/CueEntryUuidMigration.kt` (cue model plan
session 3), inside `initDatabase`'s transaction after the schema exists, gives every cue's
one stack entry its cue's uuid and every entry its 0-based position as its ordinal, so two
desks write the same record for the same place once entries are sync records
(`formatVersion` 32). It runs once per database, recorded in **`PRAGMA
user_version`** (1) — the database header's counter, first used here; a later one-shot
step takes the next number. It must never run twice: its KDoc says why.

Otherwise the bet stands on there being exactly one database (the dev desk), and it
expires the moment a second install exists — the Windows MSI ships an upgrade path.
Before making a non-additive schema change once anything is deployed, write a
migration; `InstallBootstrap.kt` says where it plugs in and what ordering constraint
bit last time, and `CueModelMigration.kt` is the worked example.

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
  `describe_rig` and the show-setup tools (`ai/SetupTools.kt`: projects, venues — list, extract,
  publish, a show on one — and copying between projects (`ai/VenueSetupTools.kt`), the stored-data
  ones taking a `projectId` so a show is built without switching to it; patch, stage and
  rigging, the scene — its cloths' fabric and paint, and `upload_scene_image`, the one tool that
  takes file bytes, a PNG or JPEG of at most 16 MB with no remote-access gate — cue stacks and
  prompt-book markup, built from documents the model reads — and
  `render_view`, a PNG of a stage viewpoint drawn offscreen by a signed-in desk window on the desk's
  own listener, never a remote one, so Claude can check the model it built). **The port split is the security boundary**: everything on that listener is
  remote, decided by port and never by a forwarded header, and `installRemoteHardening` applies
  there — no remote bootstrap, a sign-in lockout, `Secure` cookies, an Origin check, no QR flows,
  scripts refused unless an admin allows them, and arming, firing and fixture commands likewise
  (`requireEffectsAccess`, `requireCommandsAccess` — MCP's `run_fixture_command` included). Anything mounted on it is reachable from the
  internet, and a script-capable route must call `requireScriptAccess`. The browser's Sentry
  tunnel, `/api/sentry`, is mounted there too, signed out included: its DSN check, 1 MB cap and
  telemetry gate are all that keep it from being an open relay. See
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
- [Composition Model](docs/lighting-composition-model.md) - The five layers, and §"Looks, palettes and layers" for the cook step: a cue's ordered layers plus its local rows flatten to one contributor per (fixture, property) before the resolver, which is what makes within-cue precedence one rule for every attribute. Its §"A palette holds a value *or* an effect" is the D7 reversal and the three rules that keep it narrow
- [Desk Screens](docs/desk-screens.md) - The two desk screens: the launcher's tray items, the `?window=` naming contract, why a desk screen must be opened at `http://localhost:8413/` (installation, Keyboard Lock and Window Management are all secure-context), and kiosk mode as a note
- [Desk Accounts](docs/desk-accounts.md) - Desk-local users, the two roles and where they're enforced, cookie sessions + live 4401 revocation, the QR password reset, and the `RESET-ADMIN` break-glass recovery
- [Windows Updates](docs/windows-updates.md) - The pinned MSI UpgradeCode, MSI version rules, `BuildInfo` in both jars, the GitHub release check, and the backend→launcher marker handshake that applies an update
- [Observability](docs/observability-engineering.md) - Sentry: the install telemetry switch (`/install/telemetry`), what is sent and filtered, token scrubbing, why only `Application.module()` starts it, and tracing — where transactions start, the sampling tables (every inbound WS frame needs a row in `TraceSampling.WS_MESSAGES`), how to add a span and what is never traced, and metrics — the 30 s reporter, each metric's source and attributes, and how to add one

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
