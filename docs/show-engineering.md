# Show Engineering Documentation

This document describes the Show system — how a project organises its performance as an ordered list of cue stacks. **A project IS a show**: there is no separate ShowSession concept. Show entries belong directly to the project.

## Overview

A **project** owns the show — an ordered list of cue stacks and section markers in performance order. It provides:
- **Ordered entries** — cue stacks and section markers in performance order
- **Active entry tracking** — which entry (and therefore which stack) is currently active (`active_entry_id`)
- **Navigation** — activate, advance (FORWARD/BACKWARD), go-to, deactivate

Key behaviours:
- The show is **active** when `projects.active_entry_id` is non-null. Activating sets it; deactivating clears it.
- `/activate` picks the first STACK entry, sets `active_entry_id`, and starts the cue stack.
- `/deactivate` clears `active_entry_id` and stops the running cue stack.
- `advance` and `go-to` only target STACK entries (MARKERs are skipped).
- `deactivatePrevious` defaults to `true` on advance (deactivates the previous stack).
- Activation delegates to `CueStackManager` for the actual cue stack lifecycle.
- Repeat `/activate` on an already-active show is a no-op (no cue stack reset) — the running show is not disrupted.

## Data Model

### Database Tables

```
projects
├── id (auto-increment PK)
├── name (varchar 50, unique)
├── description (varchar 255, nullable)
├── is_current (bool)
└── active_entry_id (integer, nullable — deferrable FK → show_entries)

show_entries
├── id (auto-increment PK)
├── project_id (FK → projects, ON DELETE CASCADE)
├── cue_stack_id (nullable FK → cue_stacks, ON DELETE SET NULL)
├── entry_type (varchar 20, default "STACK" — STACK or MARKER)
├── sort_order (integer)
└── label (varchar 255, nullable)
```

### Key Design Decisions

- **Circular FK** — `projects.active_entry_id` references `show_entries.id` while entries reference `projects`. Resolved with a `DEFERRABLE INITIALLY DEFERRED` constraint added via manual SQL in `State.kt` (`migrateProjectActiveEntryFk`).
- **Nullable `cue_stack_id`** — MARKER entries have no associated cue stack.
- **`active_entry_id` is explicit** — not derived from runtime state, survives server restart.
- **No "is active" flag** — when `active_entry_id` is non-null, the show is running. There is at most one active show per project (because there is only one show per project).

### Entry Types

- **STACK** — references a cue stack; can be activated/navigated.
- **MARKER** — inert section divider (e.g. "15 min interval"); invisible to advance/go-to.

## REST API

All endpoints under `/api/rest/project/{projectId}/show`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET    | `/`                  | Get the show state with entries |
| POST   | `/add-stack`         | `{ cueStackId, sortOrder?, label? }` |
| POST   | `/add-marker`        | `{ label, sortOrder? }` |
| PUT    | `/entries/{entryId}` | Update label/sortOrder |
| DELETE | `/entries/{entryId}` | Remove entry |
| POST   | `/reorder`           | `{ entryIds: [3, 1, 5] }` |
| POST   | `/activate`          | Activate first STACK entry's cue stack |
| POST   | `/deactivate`        | Deactivate active entry's cue stack |
| POST   | `/advance`           | `{ direction, deactivatePrevious? }` — skip MARKERs |
| POST   | `/go-to`             | `{ entryId }` — HTTP 400 if MARKER |

### DTOs

- `ShowDetails` — projectId, **activeEntryId**, entries, canEdit
- `ShowEntryDto` — id, entryType, sortOrder, label, cueStackId, cueStackName
- `ShowActivateResponse` — projectId, activeEntryId, activatedStackId, activatedStackName
- `AddStackToShowRequest` — cueStackId, sortOrder?, label?
- `AddMarkerToShowRequest` — label, sortOrder?
- `UpdateShowEntryRequest` — label?, sortOrder?
- `ReorderEntriesRequest` — entryIds: List<Int>
- `AdvanceShowRequest` — direction ("FORWARD"|"BACKWARD"), deactivatePrevious? (default true)
- `GoToShowEntryRequest` — entryId

### Activate Flow

1. If `project.activeEntryId` is non-null, short-circuit — return current state without resetting the running cue stack.
2. Resolve the project's first STACK entry (sorted by `sortOrder`).
3. Set `project.activeEntryId = firstStack.id`.
4. Call `CueStackManager.activateCueInStack` at the target stack's first STANDARD cue.
5. Broadcast `showChanged(projectId, activeEntryId, activatedStackId, activatedStackName)`.

If the show is empty (no STACK entries), activation completes without starting a cue stack — `activeEntryId` stays null and the broadcast carries null fields.

### Advance / Go-To Flow

1. Read project and resolve the target entry (next STACK for advance, the requested entry for go-to).
2. If `deactivatePrevious` (default true), deactivate the previous entry's cue stack via `CueStackManager`.
3. Activate the new entry's cue stack — delegates to `CueStackManager.activateCueInStack` starting at the first STANDARD cue.
4. Update `project.activeEntryId`.
5. Broadcast `showChanged(projectId, activeEntryId, activatedStackId, activatedStackName)`.

### Deactivate Flow

1. Clear `project.activeEntryId = null`.
2. Deactivate the previously-running cue stack via `CueStackManager`.
3. Broadcast `showChanged(projectId, null, null, null)`.

## WebSocket

### Messages

```json
{"type": "showEntriesChanged"}
```

Broadcast on entry CRUD operations (add, remove, reorder, update entry).

```json
{
  "type": "showChanged",
  "projectId": 42,
  "activeEntryId": 2,
  "activatedStackId": 10,
  "activatedStackName": "Act 1 Cues"
}
```

Broadcast on any change to `active_entry_id` — activate, deactivate, advance, go-to. Carries `projectId` rather than a session id since the show is the project. When deactivating, the entry/stack fields are all `null`.

## Implementation Files

| File | Purpose |
|------|---------|
| `models/showEntries.kt` | Exposed table object and entity for `show_entries` (`DaoShowEntries` / `DaoShowEntry`) |
| `models/projects.kt` | `DaoProjects` includes `active_entry_id`; `DaoProject` has `showEntries` referrer |
| `routes/projectShow.kt` | REST route handlers and DTOs |
| `plugins/Sockets.kt` | WebSocket message types and broadcast |
| `show/Fixtures.kt` | `FixturesChangeListener` interface (`showEntriesChanged`, `showChanged`) |
| `state/State.kt` | Table registration, drop migration for old session tables, deferrable FK migration for `projects.active_entry_id` |
