# Show Sessions Engineering Documentation

This document describes the Show Session system — the top-level structure for organising a performance as an ordered list of cue stacks.

## Overview

A **Show Session** represents either a theatrical show (stacks per act) or a live band setlist (stacks per song). It provides:
- **Ordered entries** — cue stacks and section markers in performance order
- **Active entry tracking** — which stack is currently active (`active_entry_id`)
- **Session type** — `SHOW` or `SETLIST` (frontend vocabulary hint only; identical API)
- **Navigation** — activate, advance (FORWARD/BACKWARD), go-to, deactivate

Key behaviours:
- `active_entry_id` is stored explicitly, not derived
- `advance` and `go-to` only target STACK entries (MARKERs are skipped)
- `deactivatePrevious` defaults to `true` on advance (deactivates the previous stack)
- Activation delegates to `CueStackManager` for the actual cue stack lifecycle

## Data Model

### Database Tables

```
show_sessions
├── id (auto-increment PK)
├── project_id (FK → projects, ON DELETE CASCADE)
├── name (varchar 255)
├── session_type (varchar 20, default "SHOW")
├── active_entry_id (integer, nullable — deferrable FK → show_session_entries)
├── created_at (long, epoch millis)
└── updated_at (long, epoch millis)

show_session_entries
├── id (auto-increment PK)
├── show_session_id (FK → show_sessions, ON DELETE CASCADE)
├── cue_stack_id (nullable FK → cue_stacks, ON DELETE SET NULL)
├── entry_type (varchar 20, default "STACK" — STACK or MARKER)
├── sort_order (integer)
└── label (varchar 255, nullable)
```

### Key Design Decisions

- **Circular FK** — `active_entry_id` references `show_session_entries` while entries reference sessions. Resolved with `DEFERRABLE INITIALLY DEFERRED` constraint.
- **Nullable `cue_stack_id`** — MARKER entries have no associated cue stack.
- **`session_type`** — frontend vocabulary hint only; `SHOW` and `SETLIST` use identical API.
- **`active_entry_id` is explicit** — not derived from runtime state, survives server restart.

### Entry Types

- **STACK** — references a cue stack; can be activated/navigated
- **MARKER** — inert section divider (e.g. "15 min interval"); invisible to advance/go-to

## REST API

All endpoints under `/api/rest/project/{projectId}/show-sessions`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/` | List all sessions with entries |
| POST | `/` | Create: `{ name, sessionType }` |
| GET | `/{sessionId}` | Get session with entries |
| PUT | `/{sessionId}` | Update name/sessionType |
| DELETE | `/{sessionId}` | Delete session and entries |
| POST | `/{sessionId}/add-stack` | `{ cueStackId, sortOrder?, label? }` |
| POST | `/{sessionId}/add-marker` | `{ label, sortOrder? }` |
| PUT | `/{sessionId}/entries/{entryId}` | Update label/sortOrder |
| DELETE | `/{sessionId}/entries/{entryId}` | Remove entry |
| POST | `/{sessionId}/reorder` | `{ entryIds: [3, 1, 5] }` |
| POST | `/{sessionId}/activate` | Activate first STACK entry's cue stack |
| POST | `/{sessionId}/deactivate` | Deactivate active entry's cue stack |
| POST | `/{sessionId}/advance` | `{ direction, deactivatePrevious? }` — skip MARKERs |
| POST | `/{sessionId}/go-to` | `{ entryId }` — HTTP 400 if MARKER |

### DTOs

- `NewShowSession` — name, sessionType (default "SHOW")
- `ShowSessionDetails` — id, name, sessionType, activeEntryId, entries, canEdit, canDelete
- `ShowSessionEntryDto` — id, entryType, sortOrder, label, cueStackId, cueStackName
- `ShowSessionActivateResponse` — sessionId, activeEntryId, activatedStackId, activatedStackName

### Activate/Advance Flow

1. Read session and resolve the target entry (first STACK for activate, next STACK for advance)
2. If `deactivatePrevious` (default true), deactivate the previous entry's cue stack via `CueStackManager`
3. Activate the new entry's cue stack — delegates to `CueStackManager.activateCueInStack` starting at the first STANDARD cue
4. Update `active_entry_id` on the session
5. Broadcast `showSessionChanged` WebSocket event

## WebSocket

### Messages

```json
{"type": "showSessionListChanged"}
```

Broadcast on session CRUD operations (create, update, delete, add/remove entries, reorder).

```json
{
  "type": "showSessionChanged",
  "sessionId": 1,
  "activeEntryId": 2,
  "activatedStackId": 10,
  "activatedStackName": "Act 1 Cues"
}
```

Broadcast on any change to `active_entry_id` — activate, deactivate, advance, go-to.

## Implementation Files

| File | Purpose |
|------|---------|
| `models/showSessions.kt` | Exposed table objects and entities |
| `routes/projectShowSessions.kt` | REST route handlers and DTOs |
| `plugins/Sockets.kt` | WebSocket message types and broadcast |
| `show/Fixtures.kt` | `FixturesChangeListener` interface extensions |
| `state/State.kt` | Table registration and deferrable FK migration |
