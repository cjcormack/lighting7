# Claude Code Configuration for Lighting7

A professional stage/event lighting control system built in Kotlin using Ktor. Controls physical lighting fixtures through DMX (ArtNet) and Philips Hue, with music synchronization support.

## Tech Stack

- **Kotlin 1.9.23** on JVM 17
- **Ktor 2.3.9** (web server, WebSockets, REST API)
- **PostgreSQL** with Exposed ORM and HikariCP
- **gRPC/Protocol Buffers** for track notifications
- **ArtNet4j** for DMX protocol
- **Kotlin Scripting** for embedded lighting DSL

## Building and Running

```bash
# Build
./gradlew build

# Run (starts REST on :8413, gRPC on :50051)
./gradlew run
```

### Configuration

1. Copy `example.local.conf` to `local.conf`
2. Configure PostgreSQL connection settings
3. Set project name, initial scene, and script names
4. Configure music service credentials if needed

## Project Structure

```
src/main/kotlin/uk/me/cormack/lighting7/
├── Application.kt          # Entry point
├── dmx/                    # DMX/ArtNet controllers
├── fixture/                # Fixture abstractions
│   ├── dmx/               # Specific DMX fixture types
│   └── hue/               # Philips Hue integration
├── show/                   # Show orchestration & script runner
├── state/                  # Application state management
├── models/                 # Database entities (projects, scenes, scripts)
├── routes/                 # REST API endpoints
├── plugins/                # Ktor plugins (HTTP, WebSockets, Routing)
├── scripts/                # LightingScript DSL definition
├── scriptSettings/         # Script configuration types
├── music/                  # Music service integration
└── trackServer/            # gRPC track notification server
```

## Key Concepts

### Fixtures
Fixtures represent physical lighting devices. They use trait-based composition:
- `FixtureWithDimmer` - brightness control
- `FixtureWithColour` - RGB color control
- `FixtureWithStrobe` - strobe effects
- `FixtureWithUv` - UV lighting

Add new fixtures in `fixture/dmx/` by extending the appropriate base classes and traits.

### Scenes and Chases
- **Scene** (`Mode.SCENE`): A recorded snapshot of fixture states
- **Chase** (`Mode.CHASE`): A continuous recording/playback sequence

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

## API Endpoints

- **REST API**: `http://localhost:8413/api/rest`
- **WebSocket**: `ws://localhost:8413/ws`
- **Swagger UI**: `http://localhost:8413/openapi`

### WebSocket Messages
- `channelState` - DMX channel value updates
- `universesState` - Available DMX universes
- `sceneListChanged` - Scene list modifications
- `updateChannel` - Direct channel control
- `trackDetails` - Music track info

## Database

Uses Exposed ORM with PostgreSQL. Tables auto-create on startup via `SchemaUtils.createMissingTablesAndColumns`.

Key tables:
- `DaoProjects` - Project definitions
- `DaoScenes` - Scene/chase configurations
- `DaoScripts` - Lighting script source code

## Common Development Tasks

### Adding a New Fixture Type
1. Create class in `fixture/dmx/` extending appropriate base
2. Add `@FixtureType` annotation with name
3. Implement required traits (Dimmer, Colour, etc.)
4. Use `@FixtureProperty` to annotate controllable properties

### Writing a Lighting Script
Scripts extend `LightingScript` and have access to:
- `fixtures` - Registry of all fixtures
- `controller` - DMX controller
- `show` - Show management
- Coroutine scope for async operations

### Modifying REST API
Add routes in `routes/` package using Ktor Resources for type-safe routing.

## Related Projects

- **Frontend**: `/Users/chris/Development/Personal/lighting-react/`

## External Integrations

- **DMX Hardware**: ArtNet protocol over network
- **Philips Hue**: HTTP API via Ktor client
- **Music Sync**: gRPC service on port 50051

## Engineering Documentation

For deeper technical details, see the docs in `docs/`:

- [DMX Subsystem](docs/dmx-engineering.md) - Low-level DMX control architecture, ArtNet implementation, fading, transactions
- [Fixture System](docs/fixtures-engineering.md) - Fixture abstractions, traits, property types, adding new fixtures
- [Show & Scripts](docs/show-scripts-engineering.md) - Script compilation, caching, execution, run loops, scene/chase modes
- [Scenes & Chases](docs/scenes-chases-engineering.md) - Scene recording, active tracking, chase execution
- [WebSocket Protocol](docs/websocket-engineering.md) - Real-time client communication, message types, update flow
- [Music Sync](docs/music-sync-engineering.md) - gRPC track notifications, script triggering, player state
