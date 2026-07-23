# Lighting7

A professional stage and event lighting control system built in Kotlin. Control DMX fixtures via ArtNet, Philips Hue smart lights, and synchronize lighting with music playback.

## Features

- **DMX Control**: Send DMX512 commands over ArtNet protocol with smooth fading
- **Philips Hue Integration**: Control Hue smart lights alongside DMX fixtures
- **Scene Management**: Save and recall lighting states
- **Chases**: Record and playback continuous lighting sequences
- **Scripting**: Write custom lighting effects using embedded Kotlin DSL
- **Music Sync**: Trigger scenes and scripts based on music track changes
- **Real-time Control**: WebSocket API for live updates and control
- **REST API**: Full programmatic access with Swagger documentation

## Requirements

- JDK 21+ on `PATH` for Gradle (the project's Kotlin toolchain pins JVM 24 — Gradle's foojay resolver auto-downloads a matching JDK if your system JDK differs).
- Network access to ArtNet devices for DMX output.
- For the in-app script editor (optional): a checkout of the [`kotlin-compiler-server`](https://github.com/JetBrains/kotlin-compiler-server) fork at `../kotlin-compiler-server` (override path with `-PkotlinCompilerServerPath=...`).
- For frontend builds: a checkout of [`lighting-react`](../lighting-react) at `../lighting-react` (override with `-PlightingReactPath=...`). The Gradle build invokes its `npm run build` and bakes the output into the JAR.

SQLite is used for persistence — there is no database server to install. The DB file is created on first launch under the platform's app data dir:

| OS      | Path                                       |
| ------- | ------------------------------------------ |
| macOS   | `~/Library/Application Support/lighting7/` |
| Windows | `%APPDATA%\lighting7\`                     |
| Linux   | `~/.config/lighting7/`                     |

Override the whole data dir (DB, logs, caches, sync trees) by setting
`LIGHTING7_DATA_DIR=/path` or passing `-Dlighting7.dataDir=/path` — handy for a
portable install or a second instance on the same machine. The path is used verbatim
(no `lighting7` leaf appended; a leading `~` is expanded). It's an env var / system
property rather than a `local.conf` key because the data dir has to be resolved *before*
any config is read — the single-instance lock is taken before Ktor loads config.

`local.conf` is loaded from the process working directory if one exists there, otherwise
from the data dir. The packaged launcher runs the backend with its working directory set
to the data dir; in dev, the project-root `local.conf` (the CWD) wins.

To relocate only the SQLite file, set `database.path` in `local.conf`. Note the
single-instance lock guards the *data dir*, not the DB file — if you move the DB outside
its data dir, keep that path unique per instance, or two instances will still corrupt it.

Only one instance may use a given data directory at a time: at startup the backend takes
an OS lock on `<dataDir>/lighting7.lock` and exits with a clear message if another
instance already holds it (SQLite is single-writer); the packaged launcher guards likewise
on `<dataDir>/launcher.lock` before spawning children. To run two instances, give each its
own `LIGHTING7_DATA_DIR`.

## Quick Start (development)

```bash
./gradlew run
```

Starts the backend on:

- Web UI: <http://localhost:8413/> (the React bundle is served from the JAR's classpath)
- REST API: <http://localhost:8413/api/rest>
- WebSocket: <ws://localhost:8413/api>
- Swagger UI: <http://localhost:8413/openapi>
- mDNS: `lighting7-<hostname>.local:8413` (so iPad / LAN clients can reach the host without typing an IP)

To override defaults (database path, mDNS name, compiler-server URL, frontend static path), drop a `local.conf` next to the working directory or in the app data dir. See [`example.local.conf`](example.local.conf) for the schema.

`./gradlew test` is the standard pre-commit check.

## Building Installers

`./gradlew packageMac` / `./gradlew packageWindows` produce double-clickable installers that bundle a trimmed JRE — target machines need **no** JDK, no Postgres, no Docker, no Node.

Pre-requisite: the `kotlin-compiler-server` fork must be checked out and clean (the `assembleCompilerServer` task patches it in place via `git`, runs `bootJar`, then reverts).

### macOS

```bash
./gradlew packageMac
```

Produces `build/installers/lighting7-1.0.0.pkg`. Double-click to install to `/Applications/lighting7.app`.

### Windows

On a Windows host:

```bash
gradlew.bat packageWindows
```

Produces `build/installers/lighting7-1.0.0.msi`. Installs to `C:\Program Files\lighting7\` with Start menu and desktop shortcuts.

### Notes

- `jpackage` is host-only — the Mac installer must be built on macOS, the Windows installer on Windows. The wrong-host invocation logs a warning and skips.
- The installer version defaults to `1.0.0` (jpackage rejects majors < 1, so `project.version = 0.0.1` can't be passed through). Override with `-PjpackageAppVersion=2.0.0`.
- The bundled JRE is ~59 MB compressed (`jlink` output of `java.se` + `jdk.zipfs` + `jdk.localedata` + `jdk.crypto.ec` + `jdk.unsupported`).

### What ships in the installer

```
lighting7.app/Contents/                     C:\Program Files\lighting7\
├── MacOS/lighting7                         ├── lighting7.exe
├── app/                                    ├── app/
│   ├── launcher.jar                        │   ├── launcher.jar
│   ├── lighting7.jar                       │   ├── lighting7.jar
│   └── kotlin-compiler-server.jar          │   └── kotlin-compiler-server.jar
└── runtime/                                └── runtime/
```

On first launch the bundled launcher:

1. Writes a default `local.conf` to the app data dir if none exists.
2. Spawns `kotlin-compiler-server.jar` on `127.0.0.1:8321` (in-app script editor backend).
3. Spawns `lighting7.jar` listening on `:8413`, working directory set to the app data dir so logs and the SQLite DB land there.
4. Polls until the backend is ready, then opens the browser to <http://localhost:8413/>.
5. Parks a system-tray icon with **Open** / **Copy LAN URL** / **View Logs** / **Quit**.

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                   React Frontend                    │
│            (lighting-react project)                 │
└─────────────────────┬───────────────────────────────┘
                      │ REST / WebSocket
                      ▼
┌─────────────────────────────────────────────────────┐
│                  Ktor Server                        │
│  ┌─────────────┐  ┌─────────────┐  ┌────────────┐  │
│  │   Routes    │  │  WebSocket  │  │   gRPC     │  │
│  │  (REST API) │  │  (realtime) │  │ (music)    │  │
│  └──────┬──────┘  └──────┬──────┘  └─────┬──────┘  │
│         └────────────────┼───────────────┘         │
│                          ▼                         │
│  ┌─────────────────────────────────────────────┐  │
│  │                    Show                      │  │
│  │         (orchestration & scripting)          │  │
│  └──────────────────────┬──────────────────────┘  │
│                         ▼                          │
│  ┌──────────────────────────────────────────────┐ │
│  │                  Fixtures                     │ │
│  │   ┌─────────────┐      ┌─────────────────┐   │ │
│  │   │ DMX Fixtures│      │  Hue Fixtures   │   │ │
│  │   └──────┬──────┘      └────────┬────────┘   │ │
│  └──────────┼──────────────────────┼────────────┘ │
└─────────────┼──────────────────────┼──────────────┘
              ▼                      ▼
     ┌────────────────┐      ┌────────────────┐
     │ ArtNetController│      │   Hue Bridge   │
     │    (UDP)        │      │   (HTTP API)   │
     └────────────────┘      └────────────────┘
              │                      │
              ▼                      ▼
     ┌────────────────┐      ┌────────────────┐
     │  DMX Fixtures  │      │   Hue Lights   │
     └────────────────┘      └────────────────┘
```

## Project Structure

```
src/main/kotlin/uk/me/cormack/lighting7/
├── Application.kt       # Entry point
├── dmx/                 # Low-level DMX/ArtNet control
├── fixture/             # Fixture abstractions
│   ├── dmx/            # DMX fixture implementations
│   └── hue/            # Philips Hue fixtures
├── show/                # Show orchestration & script execution
├── state/               # Application state management
├── models/              # Database entities
├── routes/              # REST API endpoints
├── plugins/             # Ktor plugins
├── scripts/             # Lighting script DSL
├── scriptSettings/      # Script configuration
├── music/               # Music service integration
└── trackServer/         # gRPC track notifications
```

## Writing Lighting Scripts

Scripts use an embedded Kotlin DSL:

```kotlin
// Access fixtures by name
val frontWash = fixtures.get<HexFixture>("front-wash")
val backLight = fixtures.get<QuadBarFixture>("back-light")

// Set colors with fading
frontWash.setColour(Color.BLUE, fadeMs = 2000)
backLight.dimmer = 255u

// Use coroutines for timing
delay(1000)
frontWash.setColour(Color.RED, fadeMs = 500)
```

## API Overview

### Scenes
- `GET /api/rest/scenes` - List all scenes
- `POST /api/rest/scenes` - Create scene
- `PUT /api/rest/scenes/{id}/apply` - Apply scene to fixtures

### Fixtures
- `GET /api/rest/fixtures` - List fixtures with current values
- `GET /api/rest/fixtures/{id}` - Get fixture details

### Scripts
- `GET /api/rest/scripts` - List scripts
- `POST /api/rest/scripts/{id}/run` - Execute a script

See the Swagger UI at `/openapi` for full API documentation.

## Documentation

- [DMX Engineering](docs/dmx-engineering.md) - Low-level DMX architecture and ArtNet implementation
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, and adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script execution engine and scene management
- [Scenes & Chases](docs/scenes-chases-engineering.md) - Scene recording and active state tracking
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication
- [Music Sync](docs/music-sync-engineering.md) - gRPC-based music track synchronization
- [FX System](docs/fx-engineering.md) - Tempo-synchronized effects, Master Clock, blend modes
- [Fixture Groups](docs/groups-engineering.md) - Type-safe groups, distribution strategies, group FX targeting

## Tech Stack

- **Kotlin 2.2** / JVM 24
- **Ktor 3.3** - Web framework
- **Exposed** - Database ORM
- **SQLite** - Persistence (single-file, auto-created in app data dir)
- **artnet4j** - ArtNet protocol
- **JmDNS** - Bonjour / DNS-SD advertisement
- **Kotlin Scripting** - Embedded DSL

## License

Private project.
