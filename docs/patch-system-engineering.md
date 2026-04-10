# Patch System Engineering

## Overview

The patch system manages fixture configuration through database records. Universe controllers, fixture patches, and fixture groups are stored in the database and loaded at startup via `DbFixtureLoader`, which calls the same `Fixtures.register {}` DSL used internally. This means everything downstream (FX engine, cues, channel mapping, WebSocket) works transparently.

## Database Schema

### `universe_configs`
Stores controller configuration for each DMX universe.

| Column | Type | Description |
|--------|------|-------------|
| id | serial PK | |
| project_id | FK → projects | |
| subnet | int (default 0) | ArtNet subnet |
| universe | int | Universe number |
| controller_type | varchar | "ARTNET" or "MOCK" |
| address | varchar (nullable) | IP address for ArtNet |

Unique constraint: `(project_id, subnet, universe)`

### `fixture_patches`
Maps fixture type instances to channel ranges.

| Column | Type | Description |
|--------|------|-------------|
| id | serial PK | |
| project_id | FK → projects | |
| universe_config_id | FK → universe_configs | |
| fixture_type_key | varchar | References FixtureTypeRegistry (e.g., "hex") |
| key | varchar | Unique fixture identifier (e.g., "par-1") |
| display_name | varchar | Human-readable name (e.g., "PAR 1") |
| start_channel | int | First DMX channel (1-512) |
| sort_order | int | Display ordering |

Unique constraint: `(project_id, key)`

Channel count is NOT stored — it's derived at runtime from `FixtureTypeRegistry.allTypes` via the `fixture_type_key`. This prevents data drift.

### `fixture_groups` / `fixture_group_members`
Groups for organising fixtures with position offsets.

## Key Components

### DbFixtureLoader (`show/DbFixtureLoader.kt`)
Reads DB tables and calls the same `Fixtures.register {}` DSL that the rest of the system uses. This means everything downstream (FX engine, cues, channel mapping, WebSocket) works identically.

### FixtureTypeRegistry (`fixture/FixtureTypeRegistry.kt`)
- `instantiateByTypeKey()`: Creates real fixture instances from a type key string + parameters.
- `typeKeyForClass()`: Reverse lookup from class to type key.
- `classNameForTypeKey()`: Get class simple name for a type key.
- `channelCountForTypeKey()`: Get channel count for validation.

## API Endpoints

All scoped under `/api/rest/project/{projectId}/`:

### Patches
- `GET /patches` — List all patches (enriched with type info from registry)
- `POST /patches` — Create a single patch (`{ universe, fixtureTypeKey, startChannel, key, displayName, address? }`)
- `PUT /patches/{id}` — Update (displayName, key, startChannel)
- `DELETE /patches/{id}` — Delete

Universe configs are auto-created when a patch references a new universe number.

### Universe Configs
- `GET /universe-configs` — List configs
- `PUT /universe-configs/{id}` — Update (address, controllerType)
- `DELETE /universe-configs/{id}` — Delete (cascades patches)

### Patch Groups
- `GET /patch-groups` — List groups
- `GET /patch-groups/{id}` — Group detail with ordered members
- `PUT /patch-groups/{id}` — Update (rename, reorder members)
- `DELETE /patch-groups/{id}` — Delete (removes memberships, fixtures stay)

## WebSocket

Message type: `patchListChanged` — broadcast when patches are created, updated, or deleted.

## Hot Reload

When patches change on the currently active project, `DbFixtureLoader.loadFixtures()` is called automatically. The `Fixtures.register(removeUnused = true)` call clears and rebuilds everything, triggering `fixturesChanged()` and `controllersChanged()` notifications to all WebSocket clients.
