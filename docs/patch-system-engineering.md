# Patch System Engineering

## Overview

The patch system introduces a DB-based project mode as an alternative to the existing script-based fixture configuration. Projects can use either mode, and switching between them includes automatic migration (importing runtime fixtures or generating scripts).

## Project Modes

Each project has a `mode` column (`SCRIPT_BASED` or `DB_BASED`):

- **SCRIPT_BASED** (default): Fixtures configured via Kotlin DSL `load-fixtures` scripts. Existing behavior.
- **DB_BASED**: Fixtures configured through the UI and stored in the database. The `load-fixtures` script is not used.

## Database Schema

### `universe_configs`
Stores controller configuration for each DMX universe in a DB-based project.

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
DB-stored groups for DB-based projects.

## Key Components

### DbFixtureLoader (`show/DbFixtureLoader.kt`)
Reads DB tables and calls the same `Fixtures.register {}` DSL that scripts use. This means everything downstream (FX engine, cues, channel mapping, WebSocket) works identically regardless of mode.

### FixtureImporter (`show/FixtureImporter.kt`)
Snapshots the current runtime fixtures into DB records when switching from SCRIPT_BASED to DB_BASED. Uses `FixtureTypeRegistry.typeKeyForClass()` to reverse-lookup type keys.

### ScriptGenerator (`show/ScriptGenerator.kt`)
Generates a Kotlin DSL load-fixtures script from DB records when switching from DB_BASED to SCRIPT_BASED. Uses `FixtureTypeRegistry.classNameForTypeKey()` to resolve class names.

### FixtureTypeRegistry additions
- `instantiateByTypeKey()`: Creates real fixture instances from a type key string + parameters.
- `typeKeyForClass()`: Reverse lookup from class to type key.
- `classNameForTypeKey()`: Get class simple name for a type key.
- `channelCountForTypeKey()`: Get channel count for validation.

## API Endpoints

All scoped under `/api/rest/project/{projectId}/`:

### Patches
- `GET /patches` — List all patches (enriched with type info from registry)
- `POST /patches` — Batch create (`{ universe, fixtureTypeKey, count, startChannel, keyPrefix, namePrefix, address? }`)
- `PUT /patches/{id}` — Update (displayName, key, startChannel)
- `DELETE /patches/{id}` — Delete

Universe configs are auto-created when a patch references a new universe number.

### Universe Configs
- `GET /universe-configs` — List configs
- `PUT /universe-configs/{id}` — Update (address, controllerType)
- `DELETE /universe-configs/{id}` — Delete (cascades patches)

### Mode Change
Mode is set via `PUT /project/{id}` with `{ mode: "DB_BASED" }`. Backend handles migration automatically.

## WebSocket

New message type: `patchListChanged` — broadcast when patches are created, updated, or deleted.

## Hot Reload

When patches change on the currently active project, `DbFixtureLoader.loadFixtures()` is called automatically. The `Fixtures.register(removeUnused = true)` call clears and rebuilds everything, triggering `fixturesChanged()` and `controllersChanged()` notifications to all WebSocket clients.
