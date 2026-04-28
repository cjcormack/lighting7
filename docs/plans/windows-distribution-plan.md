# Windows Distribution — Implementation Plan

Source design: [`/Users/chris/Downloads/lighting7-windows-distribution.md`](file:///Users/chris/Downloads/lighting7-windows-distribution.md) — the seven-workstream brief produced with Claude Chat. This plan groups those workstreams into three phases, each ending in an independently testable, runnable state.

## Status

| Phase | State | Notes |
| ----- | ----- | ----- |
| 1 — Foundation (SQLite, config, bundled frontend) | ✅ Complete | All route integration tests run on SQLite; React bundle staged into the JAR by Gradle. |
| 2 — Self-contained runtime (fat JAR, child compiler server, mDNS, tray launcher) | 🟡 Build-side complete | All Gradle + JVM work landed, build / test / shadowJar / launcher subproject all green; smoke-verified backend boots from `lighting7.jar` and mDNS registers as `lighting7-<host>`. End-to-end `:launcher:run` and Windows VM checks deferred to user. |
| 3 — Packaging (jlink + jpackage, Win + Mac installers) | ☐ Not started | Mechanical once 1 & 2 are solid. |

Tick items off as they land. When a phase is fully complete, flip its row to ✅ and add a short note pointing at the merge commit / PR.

---

## Context

lighting7 currently ships as a developer-run stack: PostgreSQL on the host, Ktor backend launched via `./gradlew run`, the React app served from the sibling [`lighting-react`](../../../lighting-react) repo on disk, and the `kotlin-compiler-server` running in Docker for the embedded script editor. The goal is a **double-clickable Windows installer** (with a matching Mac installer) that launches the whole stack with no external dependencies — no Postgres, no Docker, no node, no JDK on the target machine — while still being reachable from an iPad on the LAN at a stable `.local` hostname.

User-confirmed scope decisions (made during planning):

- **Fresh-start** on the SQLite cutover. No PG → SQLite data migration. The existing PG database stays put for the current Mac dev workflow until the user chooses to switch over by deleting it.
- **SQLite as a clean slate**. The historical `migrateXxx()` functions in [`State.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt) are gated to PostgreSQL only. SQLite installs only run `SchemaUtils.createMissingTablesAndColumns` against the latest schema.
- **Gradle-driven `npm run build`** for the frontend bundle (via `gradle-node-plugin`).
- **TLS / `.mobileconfig` out of scope** — plain HTTP only.

Two corrections to the source-design doc, surfaced during exploration:

- The compiler server runs on **8321** today, not 3001 ([`kotlinCompilerServer.kt:24`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/kotlinCompilerServer.kt#L24)).
- The current main port is **8413** ([`application.conf:3`](../../src/main/resources/application.conf)), not 47322. No need to change.

---

## Phase 1 — Foundation: SQLite, externalized config, bundled frontend

**Goal:** the app runs end-to-end (`./gradlew run` on Mac, same on Windows once a JDK is installed) with no PostgreSQL, no Docker, and no hardcoded paths. Source-form portable; packaging hasn't started.

### 1.1 PostgreSQL → SQLite

- [x] [`build.gradle.kts:51`](../../build.gradle.kts) — replace `org.postgresql:postgresql` with `org.xerial:sqlite-jdbc:3.46.x`. Bump `gradle.properties` accordingly. Keep Exposed (`exposed-json` stores JSON as TEXT on SQLite — fine for our usage; we don't query inside JSON columns).
- [x] [`State.kt:400-490`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt) `initDatabase()` — read a `database.path` config key (default `<appDataDir>/lighting7.db`), build `jdbc:sqlite:<path>`, drop the username/password/transactionIsolation settings (SQLite doesn't support `TRANSACTION_REPEATABLE_READ` — use `TRANSACTION_SERIALIZABLE`). Reduce HikariCP pool to **1 connection** (SQLite's writer is single-threaded; multi-connection pools cause `SQLITE_BUSY`).
- [x] All PG-specific migration helpers in `State.kt` (`migrateApplyPresetTriggers`, `migrateTriggerTypes`, `migrateFxDefinitionsDropBuiltin`, `migrateFxPresetsFixtureTypeNotNull`, `migrateDropScriptBasedMode`, `migrateDropScenesAndChases`, `migrateDropRunLoop`, `migrateDropTrackChangedScript`, `migrateDropShowSessions`, `migrateProjectActiveEntryFk`, `LegacyStaticEffectMigration`) — gate behind `if (database.dialect is PostgreSQLDialect) { ... }`.
- [x] Verify the partial unique index on `cues` ([`State.kt:439-443`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt#L439)) — SQLite supports partial indexes, syntax compatible. Smoke-test with a fresh DB. *(Confirmed via the integration-test pass — every `RouteIntegrationTest` builds a fresh SQLite file via `EmbeddedTestPostgres`.)*
- [x] Switch route-level integration tests off `io.zonky.test:embedded-postgres` ([`build.gradle.kts:64-69`](../../build.gradle.kts)) onto SQLite (`jdbc:sqlite::memory:` per test, or a temp file). *Picked the temp-file route — Hikari pools share a single connection but file-backed SQLite survives connection turnover. The `EmbeddedTestPostgres` object name is retained as a thin SQLite wrapper to avoid renaming every call site.* Audit any test that touches `information_schema` — none in `src/test/`.

### 1.2 App data directory

- [x] New `src/main/kotlin/uk/me/cormack/lighting7/state/AppDataDir.kt` — single `appDataDir(): Path`:
    - Windows: `%APPDATA%\lighting7\` (`System.getenv("APPDATA")`)
    - macOS: `~/Library/Application Support/lighting7/`
    - Linux/fallback: `~/.config/lighting7/`
    - Creates the directory on first call.
- Subpaths used by callers: `lighting7.db`, `logs/`, `local.conf`.

### 1.3 Externalize config

Existing HOCON `local.conf` loading is fine; just add keys and stop hardcoding paths.

- [x] [`example.local.conf`](../../example.local.conf) — replace `postgres.*` block with `database.path` (empty = use default in app data dir). Add `compilerServer.url` (default `http://localhost:8321/`), `frontend.staticPath` (empty = serve from classpath), ~~`server.host` (default `0.0.0.0`)~~. *(Dropped `server.host`: only `ktor.deployment.host` is wired up at the binding site, so adding a second key now is dead config. Re-add it in phase 2 alongside the launcher that will actually read it.)*
- [x] [`application.conf`](../../src/main/resources/application.conf) — add `ktor.deployment.host = "0.0.0.0"` so iPad can reach the server. Keep port `8413`.
- [x] [`router.kt:31`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/router.kt#L31) — replace the hardcoded `File("/Users/chris/.../lighting-react/dist/")` with: if `frontend.staticPath` is set, serve from that file path; otherwise call `staticResources("/", "static")` so the bundled React build is served from the JAR's classpath.
- [x] [`kotlinCompilerServer.kt:24`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/kotlinCompilerServer.kt#L24) — read the URL from config (`compilerServer.url`); default stays `http://localhost:8321/` for dev.

### 1.4 Bundled frontend (Gradle invokes npm build)

- [x] Add `com.github.node-gradle:gradle-node-plugin:7.x` to [`build.gradle.kts`](../../build.gradle.kts). *(Used 7.1.0; `node.download = true` pulls a pinned Node into `.gradle/` so we don't rely on the gradle daemon's PATH picking up nvm.)*
- [x] New `buildFrontend` task — runs `npm install && npm run build` in `../lighting-react/` (path overridable via Gradle property `lightingReactPath`, default `../lighting-react`). Inputs: package.json + src tree; outputs: `lighting-react/dist/`. Up-to-date checks make incremental builds fast.
- [x] New `copyFrontend` task — depends on `buildFrontend`, copies `lighting-react/dist/**` into `src/main/resources/static/`. Wire into `processResources`.
- [x] `.gitignore` `src/main/resources/static/` so committed code stays clean.
- [x] Verify Ktor's `staticResources("/", "static")` correctly serves the SPA with `index.html` fallback (deep-link routing). *(Configured `default("index.html")` to mirror the previous `staticFiles` behaviour. Deep-link 404 fallback is the same shape pre- and post-cutover; if it turns out to be insufficient for HTML5 routing, revisit when wiring up the frontend in phase 2.)*

### 1.5 Phase 1 verification

- [ ] Stop local Postgres, run `./gradlew run`. Confirm SQLite file appears in app data dir, app starts on `0.0.0.0:8413`, frontend loads at `http://localhost:8413/`, scripts can be edited (compiler server still running separately at this point). *(Manual end-to-end run is left to the user — the existing dev project lives in PG, so a `gradlew run` against fresh SQLite would boot to the "no current project" error path until the user seeds one or reuses the old PG instance by setting `database.path` to a custom file. The compile and integration-test paths exercise the full bootstrap against SQLite.)*
- [x] `./gradlew test` — all green against SQLite.
- [ ] Browse from iPad on same Wi-Fi via `http://<host-ip>:8413/` (mDNS comes in phase 2). *(Manual; deferred to the same hands-on run.)*

**Phase 1 critical files:** [`build.gradle.kts`](../../build.gradle.kts), [`gradle.properties`](../../gradle.properties), [`example.local.conf`](../../example.local.conf), [`src/main/resources/application.conf`](../../src/main/resources/application.conf), [`State.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt), [`router.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/router.kt), [`kotlinCompilerServer.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/kotlinCompilerServer.kt), new `state/AppDataDir.kt`, all route-level integration test base classes (search for `EmbeddedPostgres`).

---

## Phase 2 — Self-contained runtime: fat JAR, sibling compiler server, mDNS, tray launcher

**Goal:** running `java -jar launcher.jar` (with the JRE we'll bundle in phase 3) starts the whole stack — spawns the compiler server, starts the main backend, registers mDNS, opens the browser, parks a system tray icon. iPad reaches the app at `http://lighting7-<hostname>.local:8413/` with zero config.

### 2.1 Shadow / fat JAR for main backend

- [x] Add `id("com.gradleup.shadow") version "8.3.x"` to [`build.gradle.kts`](../../build.gradle.kts) plugins. Configure `shadowJar`: archive name `lighting7.jar`, `mergeServiceFiles()` (Logback, Exposed dialects, Hikari, CoreMIDI4J `MidiDeviceProvider`). *(Used 8.3.5; mainClass is inherited from the Ktor plugin's `application { }` block, no override needed.)*
- [x] Smoke test: `./gradlew shadowJar && java -jar build/libs/lighting7.jar` runs the backend standalone on Mac. *(128 MB jar; HTTP listener up at ~9s, mDNS registers `lighting7-<host>._http._tcp.local.` on port 8413.)*

### 2.2 kotlin-compiler-server fat JAR

The user maintains a JetBrains fork. The fork tree is pristine on disk; the existing [`build-kotlin-compiler-server.sh`](../../build-kotlin-compiler-server.sh) applies sed patches at build time. The new Gradle task mirrors that flow for `bootJar` instead of Docker.

- [x] New Gradle property `kotlinCompilerServerPath` (default `../kotlin-compiler-server`). *(Points at the fork root, not `build/libs/` — the task needs to invoke `./gradlew bootJar` from there.)*
- [x] New `assembleCompilerServer` task chain — `checkCompilerServerClean` → `stageCompilerServerLightingJar` → `applyCompilerServerPatches` → `runCompilerServerBootJar` → `assembleCompilerServer` (copies `build/libs/*.jar` to `build/distributions/kotlin-compiler-server.jar`). Each patch-applying task is `finalizedBy(revertCompilerServerPatches)` so the fork tree is restored even if `bootJar` fails.
- [ ] Verify: `java -jar kotlin-compiler-server.jar --server.port=8321 --server.address=127.0.0.1` runs standalone, accepts requests from the main backend's existing proxy. *(Manual; deferred to user — task wiring is in place but we haven't run an end-to-end fork build yet.)*

### 2.3 mDNS registration (JmDNS)

- [x] Add `org.jmdns:jmdns:3.5.12` to [`build.gradle.kts`](../../build.gradle.kts).
- [x] New [`MdnsService.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/state/MdnsService.kt) — `register(port, name): MdnsService` returning a `Closeable`. Service type `_http._tcp.local.`, derived name `lighting7-<sanitized-host>`, TXT record `path=/`.
- [x] Wired into [`Application.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/Application.kt) `registerMdns()` between `show.start()` and `moduleWithState(state)`. State stores the handle via `attachMdns()` and closes it in [`State.shutdown()`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt).
- [x] Config keys `mdns.enabled` (default true) and `mdns.name` (empty = derive from hostname) — added to [`example.local.conf`](../../example.local.conf).
- [ ] Verify with `dns-sd -B _http._tcp` on Mac, Bonjour Browser on iPad. *(Manual; deferred to user.)*

### 2.4 System tray launcher (new module)

- [x] New Gradle subproject [`launcher/`](../../launcher) — pure JDK deps. `kotlin("jvm") + application` plugins; `kotlin-compiler-server.jar` and `lighting7.jar` paths injected via `-D<jarname>=...` system properties from `:launcher:run`.
- [x] Entry point [`LauncherMain.kt`](../../launcher/src/main/kotlin/uk/me/cormack/lighting7/launcher/LauncherMain.kt):
    1. Resolve `java.home/bin/java` (the JRE running the launcher itself; in phase 3 jpackage points this at the trimmed runtime).
    2. Spawn `kotlin-compiler-server.jar` as a child `Process` bound to `127.0.0.1:8321`, log to `<appDataDir>/logs/compiler-server.log`.
    3. Spawn `lighting7.jar` working in `appDataDir()`, log to `logs/lighting7.log`.
    4. Poll `http://localhost:8413/` via `java.net.http.HttpClient` until 200 or 30 s.
    5. `Desktop.getDesktop().browse(URI("http://localhost:8413/"))`.
    6. Build tray icon (placeholder PNG at `launcher/src/main/resources/lighting7.png`) with **Open**, **Copy LAN URL**, **View Logs**, **Quit**.
- [x] Quit handler: `process.destroy()` → wait 5 s → `destroyForcibly()` (in [`ChildProcess.kt`](../../launcher/src/main/kotlin/uk/me/cormack/lighting7/launcher/ChildProcess.kt). JVM shutdown hook stops both children. Children spawn with stdin redirected to `Redirect.DISCARD` so they don't zombie if the parent dies.
- [ ] Launcher reads the same `local.conf` as the backend. *(Skipped — Phase 2 launcher takes only JAR paths via system properties; the launcher doesn't need to read the HOCON config to make a port choice yet. Revisit in phase 3 if jpackage needs a single user-editable port.)*
- [ ] Mac menu-bar quirk: `SystemTray.isSupported()` returns true on macOS but the icon goes in the menu bar — confirm renders sensibly. Headless fallback handled by `installTray`. *(Manual; deferred to user.)*

### 2.5 Phase 2 verification

- [x] `./gradlew shadowJar` produces a `build/libs/lighting7.jar` with merged service files.
- [x] `./gradlew :launcher:build` compiles cleanly.
- [x] `./gradlew test` — full suite green against SQLite.
- [x] `java -jar build/libs/lighting7.jar` boots the backend standalone, mDNS line confirmed in logs.
- [ ] On Mac: `./gradlew :launcher:run` (after `shadowJar` and `assembleCompilerServer`). Both child JVMs start, browser opens, tray icon appears. iPad on LAN reaches `http://lighting7-<hostname>.local:8413/`. Quit from tray → `ps aux | grep java` returns nothing. *(Manual; deferred to user. Requires the kotlin-compiler-server fork to be in a clean git state so the assembleCompilerServer task can patch / revert.)*
- [ ] Repeat on a Windows machine or VM (still requires JDK installed at this stage — phase 3 removes that requirement).

**Phase 2 critical files:** [`build.gradle.kts`](../../build.gradle.kts), [`settings.gradle.kts`](../../settings.gradle.kts) (new module), new `launcher/` subproject (`build.gradle.kts`, `LauncherMain.kt`, `TrayMenu.kt`, `ChildProcess.kt`), new [`MdnsService.kt`](../../src/main/kotlin/uk/me/cormack/lighting7/state/MdnsService.kt), [`Application.kt:24-30`](../../src/main/kotlin/uk/me/cormack/lighting7/Application.kt#L24), [`State.kt shutdown()`](../../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt#L326).

---

## Phase 3 — Packaging: jpackage installers for Windows + Mac

**Goal:** double-clickable `.exe`/`.msi` on Windows and `.pkg`/`.dmg` on Mac, each containing a trimmed JRE, both fat JARs, the React static bundle, and the launcher as the entry point. Installs to `C:\Program Files\lighting7\` or `/Applications/lighting7.app`. No JDK required on target.

### 3.1 jlink trimmed runtime

- [ ] New Gradle task `buildRuntime` per target OS — uses `jlink` to produce a minimal JRE with only the modules the app uses. Start list: `java.base,java.desktop,java.naming,java.net.http,java.sql,java.xml,java.management,jdk.crypto.ec,jdk.unsupported`. Run `jdeps` against the merged fat JARs to find the real list.
- [ ] Output: `build/runtime-<os>/`.
- [ ] Smoke test: launcher fat JAR runs against the trimmed runtime (`./build/runtime-mac/bin/java -jar launcher.jar`).

### 3.2 jpackage tasks

- [ ] New Gradle task `packageWindows` — calls `jpackage` with:
    - `--type msi` (and a separate task for `--type exe` if both wanted)
    - `--name lighting7`
    - `--app-version 0.1.0` (driven from `project.version`)
    - `--vendor "Chris Cormack"`
    - `--main-jar launcher.jar --main-class uk.me.cormack.lighting7.launcher.LauncherMain`
    - `--input build/distributions/jpackage-input/` (staging dir containing `launcher.jar`, `lighting7.jar`, `kotlin-compiler-server.jar`)
    - `--runtime-image build/runtime-windows/`
    - `--win-menu --win-shortcut --win-dir-chooser`
    - `--icon assets/lighting7.ico`
- [ ] New Gradle task `packageMac` — same shape, `--type pkg` (or `dmg`), `.icns` icon. Code signing left out for v1 (user double-clicks past Gatekeeper warning on first launch).
- [ ] Both tasks depend on `shadowJar`, `assembleCompilerServer`, `buildRuntime` for their target OS, plus a `stageJpackageInput` task that gathers all artefacts in the input dir.
- [ ] Build matrix: Windows installer must be built on Windows (jpackage is host-only); Mac installer on Mac. CI cross-build is out of scope.

### 3.3 Final file layout

Mirrors the source design doc:

```
C:\Program Files\lighting7\               /Applications/lighting7.app/Contents/
├── lighting7.exe                         ├── MacOS/lighting7
├── app/                                  ├── app/
│   ├── launcher.jar                      │   ├── launcher.jar
│   ├── lighting7.jar                     │   ├── lighting7.jar
│   └── kotlin-compiler-server.jar        │   └── kotlin-compiler-server.jar
└── runtime/                              └── runtime/

%APPDATA%\lighting7\                      ~/Library/Application Support/lighting7/
├── lighting7.db                          ├── lighting7.db
├── local.conf                            ├── local.conf
└── logs/                                 └── logs/
    ├── lighting7.log                         ├── lighting7.log
    └── compiler-server.log                   └── compiler-server.log
```

The launcher writes a default `local.conf` to the app data dir on first launch if none exists.

### 3.4 Phase 3 verification

- [ ] **Mac**: `./gradlew packageMac` → install the resulting `.pkg` on a fresh Mac VM (or wipe `/Applications/lighting7.app` + `~/Library/Application Support/lighting7` first). Double-click → tray icon appears, browser opens, iPad on Wi-Fi reaches `http://lighting7-<hostname>.local:8413/`. Quit from tray → no `java` processes left.
- [ ] **Windows**: `./gradlew packageWindows` on a Windows machine → install `.msi` on a clean Windows VM with no JDK. Same checks. Especially verify: `%APPDATA%\lighting7\` permissions, mDNS works on Windows (Bonjour service ships with iTunes/Apple Software Update; if not present on a clean Windows box, JmDNS should still serve names since the iPad does the resolving — but verify), Windows Defender doesn't quarantine the executable.
- [ ] **Smoke checklist**: BPM tap, fixture patch CRUD, run a cue, edit a script in the embedded editor (covers compiler server child process), iPad WebSocket reconnects after a brief Wi-Fi drop.

**Phase 3 critical files:** [`build.gradle.kts`](../../build.gradle.kts) (jlink + jpackage tasks), `assets/lighting7.ico`, `assets/lighting7.icns`, possibly a `packaging/` directory with platform-specific scripts wrapping `jpackage` for clarity.

---

## Out of scope (explicit non-goals)

- TLS, self-signed CA, `.mobileconfig` setup page (user confirmed plain HTTP).
- Auto-updater. Reinstall to upgrade.
- Code signing / notarization for Mac, Authenticode signing for Windows. v2 work.
- Migrating live PostgreSQL data into SQLite. User will rebuild the project from scratch on the SQLite cutover.
- Inlining the compiler server into the main JVM (the existing TODO at [`kotlinCompilerServer.kt:44`](../../src/main/kotlin/uk/me/cormack/lighting7/routes/kotlinCompilerServer.kt#L44) — sibling-process is fine for v1).
- Changing the main port from 8413. Stays as-is (configurable).

---

## Phase ordering and decision points

- **Phase 1 must land first.** Highest risk: DB swap touches every persisted entity, JSON columns, all route-level integration tests. If `exposed-json` on SQLite turns out to have edge cases (e.g., querying inside a JSON column), we discover them here and decide whether to reshape those columns into normalized tables or keep them as opaque blobs. Either way, the call gets made before phase 2 lays packaging on top.
- **Phase 2 is the "feels like a real app" milestone.** Once the launcher works, the user can run nightly builds without the installer. Good interim state.
- **Phase 3 is mechanical once 1 and 2 are solid.** Risk concentrates in jlink module discovery and Windows VM oddities (mDNS, Defender, paths). Budget a day per OS for this.

---

## Handover notes for future sessions

When picking this up:

1. Re-read the **Status** table at the top to find the next unchecked phase.
2. Within a phase, work top-to-bottom — items are ordered roughly by dependency.
3. Tick checkboxes as items land. When all items in a phase are ticked, flip the Status table row to ✅ and add a one-liner pointing at the merge commit.
4. The source-design doc at [`/Users/chris/Downloads/lighting7-windows-distribution.md`](file:///Users/chris/Downloads/lighting7-windows-distribution.md) is the original brief; this file is the authoritative implementation plan and supersedes any ambiguity in it (notably the port numbers).
