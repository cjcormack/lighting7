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

- JDK 17+
- PostgreSQL
- Network access to ArtNet devices (for DMX output)

## Quick Start

1. **Clone and build**
   ```bash
   ./gradlew build
   ```

2. **Configure the database**

   Create a PostgreSQL database:
   ```sql
   CREATE DATABASE lighting;
   CREATE USER lighting WITH PASSWORD 'your-password';
   GRANT ALL PRIVILEGES ON DATABASE lighting TO lighting;
   ```

3. **Create local configuration**

   Copy the example config:
   ```bash
   cp example.local.conf local.conf
   ```

   Edit `local.conf` with your database credentials:
   ```hocon
   postgres {
       url = "jdbc:postgresql://localhost:5432/lighting"
       username = "lighting"
       password = "your-password"
   }

   lighting {
       projectName = "MyShow"
       initialSceneName = "startup"
   }
   ```

4. **Run the server**
   ```bash
   ./gradlew run
   ```

   The server starts on:
   - REST API: http://localhost:8413/api/rest
   - WebSocket: ws://localhost:8413/ws
   - Swagger UI: http://localhost:8413/openapi

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

- **Kotlin 1.9** / JVM 17
- **Ktor 2.3** - Web framework
- **Exposed** - Database ORM
- **PostgreSQL** - Persistence
- **artnet4j** - ArtNet protocol
- **gRPC** - Music sync service
- **Kotlin Scripting** - Embedded DSL

## License

Private project.
