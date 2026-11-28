# Claude Code Configuration for Lighting7

A professional stage/event lighting control system built in Kotlin using Ktor. Controls physical lighting fixtures through DMX (ArtNet) and Philips Hue.

@docs/claude/working-rules.md

**Keep this file to rules and pointers.** It loads into every session and every subagent, so a feature's design, endpoints and history go in its doc under `docs/`, with at most one line here pointing at it.

Agent sessions use the words in [docs/glossary.md](docs/glossary.md). A `planned` row's old name stays in code until the session that renames it.

## Tech Stack

- **Kotlin 2.4.10** on JVM toolchain 24 (runs on the LTS JDK 25)
- **Ktor 3.5.1** (web server, WebSockets, REST API) — version comes from the
  `io.ktor.plugin` declaration in `build.gradle.kts`; the artifacts are versionless
  and resolved from its BOM
- **SQLite** (embedded, via `sqlite-jdbc`) with Exposed ORM and HikariCP
- **Art-Net** for DMX output, sent by the desk's own ArtDMX transport (`dmx/ArtNetTransport.kt`) through one socket on 6454 (`dmx/ArtNetSocket.kt`), which `dmx/ArtNetPoller.kt` reads for controller reachability
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

The installers carry **`lighting7-link`**, the remote-session sidecar, beside the jar in
`jpackage-input`, built per OS *and* architecture by `buildLink` with a Go toolchain Gradle downloads
and checks by SHA-256 (the Mac's arm64 and amd64 both, the others amd64). `stageJpackageInput`
verifies the jar's natives and the sidecars are for one OS. `run` and the tests build the host's
and pass it as `-Dlighting7.link`. See `docs/cloud-engineering.md` §"The sidecar".

Also note `copyFrontend` is a `Sync`: it owns `src/main/resources/static/` and deletes
anything it didn't put there, so don't hand-place files in it.

### Configuration

1. Copy `example.local.conf` to `local.conf`
2. Optionally set `database.path` — empty uses `<appDataDir>/lighting7.db`
3. Set project name
4. Optionally set `dmx.mock = true` to send no Art-Net at all (a dev desk beside a live rig;
   `docs/dmx-engineering.md` §"Mock output")

### Cloud sessions

In a Claude Code cloud container, `scripts/cloud-session.sh ["repo name" …]` does the whole
stand-up: installs JDK 24 (the image has 21 and the network policy blocks foojay), resolves each
repo by its name on Lighting7 Cloud (`GET /v1/repos`), writes a pull-only `local.conf` into the
data dir (`~/lighting7-data`) with `cloud.baseUrl` the cloud's, starts `./gradlew run` in the
background with the developer token, creates an `admin` / `lighting7-dev` account, and imports each
show or venue repo — venue repos first, since a show's import needs its pinned venue version
(`VENUE_MISSING` otherwise). The session's environment must allow the cloud's host and carry a read
developer token from the portal, as `LIGHTING7_CLOUD_TOKEN` or as a network secret its proxy
injects for that host; the script fails naming the host when it cannot reach it. The script's header has the details; `sync.push = false` is in
`docs/sync-engineering.md` §"Pull-only installs".

Run Gradle there under `LC_ALL=C.UTF-8`: the image's locale is POSIX, and `compileTestKotlin`
then fails with an internal compiler error writing class files named after test names that
contain "—". The script exports it; a bare `./gradlew test` needs it too.

### Never stop or kill Gradle daemons

**The desk is usually running as `./gradlew run` in the operator's own terminal —
the app *is* a Gradle daemon.** `gradle --stop` is registry-wide: it stops every
daemon for that Gradle version, so "just clearing a wedged daemon" kills the live
show. The app's non-daemon threads (Ktor, the sync engine) then keep the
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
  each package the change touches and each package that calls into it. A change to
  `cloud-protocol/` is `./gradlew :cloud-protocol:check`: the root `test` does not reach it.
- **Sidecar:** `go vet ./... && go test ./...` in `link/` (Go as `link/go.mod` names it), which CI's
  *Link* job runs with a static build and the licence gate over the modules it links.
- **Frontend:** `npm run check:quick` in `frontend/`: the full `tsc` and build, lint (cached, so
  seconds when warm), the Vitest files affected by changes since `origin/main`, and the licence gate
  (`npm run licences`). `.githooks/pre-commit` runs it when anything buildable under `frontend/`, or
  the licence data, is staged (enable it with `git config core.hooksPath .githooks`).

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

**A dependency needs a licence entry.** `./gradlew check` (its `licenceCheck`) fails on a Java module
missing from `licences/jvm.json`; it and `npm run licences` both fail on a licence outside
`licences/allowed.json` or a stale `THIRD-PARTY-NOTICES.md`. [docs/licensing.md](docs/licensing.md)
says how to add one. The desk is MIT
(`LICENSE`); nothing GPL may join its runtime classpath, which the gate enforces.

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
one), warm or cold alike. The first run after any code change recompiles the 14 built-in FX
(editing a source file changes the classpath fingerprint, which invalidates the
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
│   │   └── attribute/     # Group attribute aggregators (GroupSlider, GroupColour)
│   ├── attribute/         # Attribute interfaces (Slider, Colour, Position, Strobe)
│   ├── trait/             # Trait interfaces (WithDimmer, WithColour, etc.)
│   └── hue/               # Philips Hue integration
├── fx/                     # FX system
│   └── group/             # Group FX distribution
├── show/                   # Show orchestration & script runner
├── state/                  # Application state management
├── models/                 # Database entities (projects, scripts, cues, looks)
├── routes/                 # REST API endpoints
├── plugins/                # Ktor plugins (HTTP, WebSockets, Routing)
└── scripts/                # LightingScript DSL definition

cloud-protocol/             # The desk–cloud wire contract, its own Gradle build (includeBuild)
```

`cloud-protocol/` is MIT, builds alone from a sparse checkout with the root files (the cloud consumes
it that way), and reads its Kotlin and serialization versions from the root `gradle.properties`; the
root `check` runs its tests. See [docs/cloud-protocol.md](docs/cloud-protocol.md).

## Key Concepts

The full write-up is [docs/key-concepts.md](docs/key-concepts.md); each subsystem's engineering doc goes deeper.

- **Fixtures** are trait-composed (`WithDimmer`, `WithColour`, `WithPosition`…) in `fixture/dmx/`. One-shot
  **triggers** (`@FixtureTrigger`) and **commands** (`@FixtureCommand`) are not attributes: no Look,
  palette, cue row, FX, programmer value or binding may hold one, and every write boundary refuses one by
  name (`TriggerIndex`, `CommandIndex`).
- **Venues** are projects of kind `VENUE`; publishing freezes the draft as a read-only `VENUE_VERSION`. A
  show on a venue pins a version and sees its rig through `RigScope`, the one place that says what the
  show's rig is: never read a show's rig around it.
- **Attributes**: a fixture's `value` is never null; a group's is null when its members differ.
- **FX** are all `.fx.kts` resources under `src/main/resources/fx/`; there is no static FX (a fixed level is
  a value). Scripts name an FX with `effect(id, params)`. Old FX ids and parameter names are read only by
  `sync/LegacyNames.kt`.
- **DMX**: handing the rig between projects never dips a kept channel to 0 (`DmxController.freeze`, `show/ShowCarry.kt`).

## API Endpoints

REST is `http://localhost:8413/api/rest`, the WebSocket `ws://localhost:8413/api`, Swagger UI
`http://localhost:8413/openapi`. Every endpoint and WebSocket message, and what each refuses, is in
[docs/api-reference.md](docs/api-reference.md), grouped by area under the headings this file once had
(§"Cue Stack Run Endpoints", §"WebSocket Messages" and so on).

## Database

Embedded SQLite through Exposed and HikariCP, `maximumPoolSize = 1` (SQLite has one writer). Tables
auto-create via `SchemaUtils.createMissingTablesAndColumns`. The schema migrations and the numbered one-shot
data steps in `PRAGMA user_version` are in [docs/database-engineering.md](docs/database-engineering.md);
read it before any schema change. Two rules from it:

- A non-additive schema change needs a migration; `CueModelMigration.kt` and `TerminologyMigration.kt` are
  the worked examples. A new one-shot step moves `SCHEMA_USER_VERSION` in `state/MigrationSupport.kt`.
  Never regenerate a frozen `src/test/resources/db/pre-*.sql`.
- A point in time is a `utcInstant()` column and an interval `duration()` (nanoseconds, never an `_ms`
  name); stamp with `nowUtc()` and send with `Instant.toIsoUtc()`.

## Database changes and cloud sync

Cloud sync (see [`docs/sync-engineering.md`](docs/sync-engineering.md))
serialises most of the project graph as canonical JSON. Adding or modifying
tables/columns has implications for sync correctness — read
`docs/sync-engineering.md` before changing the schema.

Sync's remotes are Lighting7 Cloud's repos (cloud-p2 plan), and `AuthResolver` has one source:
an adopted desk signs a short-lived git token with its install key, and an unadopted one uses the
developer token it was started with (`LIGHTING7_CLOUD_TOKEN`). A project still attached to a GitHub
repo syncs no more (`GITHUB_RETIRED`) and moves to the cloud from its working tree.

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
`sync/SyncDispositions.kt` (which `SyncCoverageTest` checks), and every table declared portable must
actually produce export output when `testsupport/RichProjectFixture.kt` is
exported. Add a table without answering the question and `./gradlew test`
fails. See `docs/sync-engineering.md` §"How to add a new table".

**A snapshot restore goes through the database too.** `GET`/`POST /projects/{id}/sync/restore` (admin
only, shows only) extract an old commit's tree, import it with `ProjectImporter.replaceInTransaction` and
snapshot the result as a new commit on top (`sync/SnapshotRestore.kt`): its review is that import rolled
back and compared record by record in today's format, so a field the exporter and importer carry is
restored without any work of the restore's own. The trailer `Restored-From: {sha}` is
`CommitInfo.restoredFrom`. `POST …/sync/restore/records` brings only chosen records back, with every
record they need, through undo's upsert with fresh row ids, as one show-history entry ⌘Z takes back and
no commit of its own. See `docs/sync-engineering.md` §"Restoring a snapshot" and §"Bringing records
back".

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

Adding a fixture type, multi-mode fixtures, the three script types and adding routes are in
[docs/key-concepts.md](docs/key-concepts.md) §"Common Development Tasks".

## Related Projects

- **Frontend**: `frontend/` in this repo, with its own `frontend/CLAUDE.md`. It was the separate
  `lighting-react` repo until the two were merged with both histories intact: every pre-merge
  commit keeps its hash, so the hashes the docs cite still resolve. `git log --follow` reaches a
  frontend file's history from before its move into `frontend/`.

## External Integrations

ArtNet, Philips Hue, MCP and remote sessions through Lighting7 Cloud, OSC in, and the cloud connector:
[docs/key-concepts.md](docs/key-concepts.md) §"External Integrations". Two rules:

- **The public listener (`mcp.port`, 8414) is the security boundary.** Everything on it is remote and
  reachable from the internet; a script-capable route must call `requireScriptAccess`.
- **The cloud is never on the show's path.** Every cloud change must leave the show running exactly as
  before with the cloud down, hung or hostile (`docs/cloud-engineering.md` §"Show safety").

## Engineering Documentation

For deeper technical details, see the docs in `docs/`:

- [Key Concepts](docs/key-concepts.md) - Fixtures, venues, attributes, scripts, DMX, FX and groups in one place, the common development tasks and the external integrations
- [API Reference](docs/api-reference.md) - Every REST endpoint and WebSocket message by area, and what each refuses
- [Database](docs/database-engineering.md) - SQLite and its pool, every schema migration and one-shot data step, and the time columns
- [Glossary](docs/glossary.md) - One word per idea, its old names and MagicQ's word; `GlossaryVocabularyTest` and `glossary.test.ts` hold the label tables to its `done` rows
- [Lighting7 Cloud](docs/cloud-engineering.md) - The desk's side of the cloud: the install key, adoption (RFC 8628's flow, polled by the backend) and unadopt, the two machine-local tables, `cloud.state`, the show-safety rules every cloud change keeps, and `FakeCloud`
- [Cloud Protocol](docs/cloud-protocol.md) - `cloud-protocol/`: the control channel's envelope and messages, the adoption API's bodies, close and error codes, the install key's signing, the golden fixtures both repos test against, and the versioning rule
- [Licensing](docs/licensing.md) - The desk's MIT licence, the licence gate, what it allows, how to add a dependency, and the audit that found artnet4j blocking MIT
- [API Conventions](docs/api-conventions.md) - Kebab-case paths, plural collections with the list GET on the collection, one spelling for vocabulary enumerations, `?force=true` as the guard-override, and why unbounded lists are deliberate at desk scale
- [Testing](docs/testing-engineering.md) - What makes the suite ~1 min rather than ~14 (the four changes, each load-bearing), the warm/cold script-cache regimes, why `build/test-data` is pinned, the tests that assert real elapsed time, and the order-dependence detector
- [DMX Subsystem](docs/dmx-engineering.md) - Low-level DMX control architecture, ArtNet implementation, fading, transactions
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, attribute types, adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script compilation, caching, execution
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication, message types, update flow
- [FX System](docs/fx-engineering.md) - Tempo-synchronized FX, Master Clock, FX types, blend modes
- [Fixture Groups](docs/groups-engineering.md) - Type-safe groups, Orders, multi-element fixtures
- [OSC](docs/osc-engineering.md) - OSC in from QLab: the fixed address set and its slugs, the explicit forms that land the same state however often they arrive, the `osc-1` receive thread, SLIP-framed TCP and their hand-off channel, replies, the allowlist, `/install/osc`, `osc.state` and the log
- [MCP Server](docs/mcp-engineering.md) - MCP through Lighting7 Cloud's relay, adding a connector, the public listener that carries remote sessions, the remote hardening applied by port and the three policy switches, the protocol and its tools, and why the script tool is left out
- [Cloud Sync](docs/sync-engineering.md) - Canonical JSON, UUID identity, machine-local overrides, per-project JGit working tree + snapshot flow, three-way diff + conflict sessions, Lighting7 Cloud's repos and their git tokens (Phases 1–5 of the cloud-sync plan, cloud-p2)
- [Prompt Book](docs/prompt-book-engineering.md) - The script store, the text index the desk reads from each PDF with PDFBox (built in the background, a cache never synced or backed up) and its states, `GET …/prompt-book/text`, the one match rule and its shared vector, and Claude's quoted places
- [Composition Model](docs/lighting-composition-model.md) - The five tiers, and §"Looks, palettes and layers" for the cook step: a cue's ordered layers plus its local rows flatten to one contributor per (fixture, attribute) before the resolver, which is what makes within-cue precedence one rule for every attribute. Its §"A palette holds a value *or* an FX" is the D7 reversal and the three rules that keep it narrow
- [Desk Screens](docs/desk-screens.md) - The two desk screens: the launcher's tray items, the `?window=` naming contract, why a desk screen must be opened at `http://localhost:8413/` (installation, Keyboard Lock and Window Management are all secure-context), and kiosk mode as a note
- [Desk Accounts](docs/desk-accounts.md) - Desk-local users, the two roles and where they're enforced, cookie sessions + live 4401 revocation, the QR password reset, and the `RESET-ADMIN` break-glass recovery
- [Windows Updates](docs/windows-updates.md) - The pinned MSI UpgradeCode, MSI version rules, `BuildInfo` in both jars, the GitHub release check, and the backend→launcher marker handshake that applies an update
- [Undo and History](docs/undo-engineering.md) - The show history: the write origin each door sets (REST, WS, MCP, surface) and the `X-Desk-Window` / `X-History-Entry` headers, the per-project record cache, the journal interceptor that diffs each commit's folders inside the commit, entries and their 1.5 s merge, labels, untracked writes rebased into the journal, caps and clear points, and the restore: planning against the cache, conflicts and back-to-here, the confirm, the upsert and its exactness check, and what an undo sets off
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

**When CI goes red on a test your change doesn't touch, check
[`docs/plans/flaky-tests-plan.md`](docs/plans/flaky-tests-plan.md)** before root-causing it. If the test is
there, re-run it once; if it isn't, add it once you have seen it fail on a commit it also passes on.

**Open followups.md when your current change might fire a listed gate** (e.g.
touching FX tick loops, ArtNet output paths, shared `AssignmentHealth` UI,
the auth gate's role prefixes, anything that adds a WS command an operator
shouldn't have, or anything that adds a 6th consumer of fixture/group attribute
lookup) — or when you want a Ready item to pick up. **Read
the index table at the top first** — one row per item with its status and gate —
and only read the body of a matching item. If a gate fires, flag it inline (or
promote the item to Ready) rather than silently working around it.

When an item lands, replace its section with a one-line row in Completed. The
narrative belongs in the commit message, and anything durable belongs in the
relevant `docs/*-engineering.md` — not in the follow-ups tracker.
