# WebSocket Protocol Engineering Documentation

This document describes the WebSocket API for real-time communication between the server and frontend clients.

## Conventions

These are the rules the socket settled on in the post-refactor sweep (backend item F5). They are
the WS counterpart of [`api-conventions.md`](api-conventions.md), and the same decision sits
behind them: **normalize hard, no aliases.** A renamed message is renamed, not dual-emitted —
there is one client, in one adjacent repo, so a compatibility alias would only be a second
spelling that never dies.

### Naming

Message names are **dotted namespaces**: `family.verb` — `speedMasters.state`,
`programmer.clearAll`, `surfaceLearn.begin`, `surfaceBank.bindingsChanged`. The family names the
client cache or subsystem the frame belongs to; it does **not** name the server component that
emits it (`speedMasters.listChanged` is fired from `BroadcastSocket.kt`'s fixtures listener, and
that is fine).

The flat `somethingHappened` names — `channelState`, `lookListChanged`, `cueRunStateChanged`,
`fixturesChanged` — are the older scheme. They are left alone deliberately: they are whole
families spelled consistently, and renaming them buys nothing but a client edit. What was *not*
left alone is a flat name sitting inside an otherwise-dotted family, because that is the one case
where a reader cannot tell which convention applies. Both were fixed:
`speedMasterListChanged` → `speedMasters.listChanged`, `surfaceBindingsChanged` →
`surfaceBank.bindingsChanged`.

New messages take the dotted form.

### Snapshot rule

**Every stateful family pushes its snapshot on connect.** A client should be able to render the
desk from the connect burst alone, without asking for anything.

The request messages (`channelState`, `fxState`, `programmer.state`, `speedMasters.state`,
`surfaceScaler.state`, …) stay, but their only remaining job is **explicit resync** — a tab
coming back from the background, a client that thinks it has drifted. They are not the way
initial state arrives, and a client that still asks on open just gets the frame twice.

Two mechanisms satisfy the rule, and only two:

- an explicit `scope.send(build…)` in the family's `setupXxxSubscriptions`, or
- a subscription to a **`StateFlow`** (or a `combine` of them), which always carries a current
  value.

A **replay-1 `MutableSharedFlow` does not count.** Its replay cache is empty until something has
happened, so the snapshot arrives only on a desk where the thing has already happened once —
which is exactly the case a fresh client cannot detect. `ParkManager.parkStateFlow` and
`FxEngine.fxStateFlow` were both this, and are both `StateFlow` now, so "nothing is parked" and
"no effects are running" are values rather than silence.

`.drop(1)` is likewise not a way to suppress a connect frame. It suppresses the first *event*,
which on a flow whose replay cache happens to be empty is the first real one — the bug that used
to leave a WebSocket's fixtures listener bound to the outgoing project after the very first
project switch.

Pure event streams — `*ListChanged` invalidations, `cloudSync*`, `programmer.entryChanged`,
`speedMasters.changed` — have no snapshot and push nothing on connect. That is not an exception to
the rule; they simply are not state.

### Reply conventions

A write arriving over the socket is answered in one of three ways, and all three are in the
codebase today:

1. **Full family snapshot.** `speedMasters.setBpm` and `.tap` both answer `speedMasters.state`.
2. **Narrow ack or delta.** `removeFx` answers `fxChanged(REMOVED, id)`; `programmer.setBlind`
   answers `programmer.blindState`.
3. **No reply at all** — the mutation lands and the family's change stream carries it back, to
   this client along with every other. `surfaceBank.set`, `surfaceScaler.setBlackout`,
   `parkChannel` and `updateChannel` all work this way.

**For new operations, pick (3).** Everything this socket controls is shared desk state, so a
unicast reply is at best redundant with the broadcast and at worst the reason two tabs disagree:
a reply-only path leaves every other client stale, which is precisely the bug that put the
programmer layer stack on a broadcast subscription rather than trusting `handleProgrammer`'s
unicast reply. If a client needs confirmation that its own frame was the cause, that belongs in
the broadcast payload, not in a second private message.

(1) remains right where a write is a *retune* of something the client is holding a live model of
and the whole family is small — the speed-master bank is four rows. (2) is right for a failure
reply, where there is no state change to broadcast; see the `surfaceLearn.error` family.

## Overview

The WebSocket API provides:
- Real-time DMX channel value updates
- Direct channel control from the UI
- Fixture change notifications

## Connection

**Endpoint**: `ws://localhost:8413/api`

**Configuration**:
- Ping period: 15 seconds
- Timeout: 15 seconds
- Serialization: JSON via kotlinx.serialization

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Frontend (React)                              │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                    WebSocket Client                             │   │
│   │                                                                 │   │
│   │  Send: ping, channelState, channelMappingState, updateChannel   │   │
│   │  Receive: channelState, channelMappingState, fxChanged, etc.    │   │
│   └─────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ WebSocket
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         Ktor WebSocket Handler                          │
│                                                                         │
│   ┌─────────────────────────────────────────────────────────────────┐   │
│   │                    SocketConnection                             │   │
│   │                   (per-client session)                          │   │
│   │                                                                 │   │
│   │   ┌──────────────────────────────────────────────────────────┐  │   │
│   │   │              FixturesChangeListener                      │  │   │
│   │   │                                                          │  │   │
│   │   │  channelsChanged() ──────► ChannelStateOutMessage        │  │   │
│   │   │  controllersChanged() ───► UniversesStateOutMessage      │  │   │
│   │   │  fixturesChanged() ──────► FixturesChangedOutMessage     │  │   │
│   │   │                      ────► ChannelMappingStateOutMessage │  │   │
│   │   │  fxPresetListChanged() ► FxPresetListChangedOutMessage │  │   │
│   │   │  cueListChanged() ─────► CueListChangedOutMessage      │  │   │
│   │   └──────────────────────────────────────────────────────────┘  │   │
│   └─────────────────────────────────────────────────────────────────┘   │
│                                                                         │
│   connections: Set<SocketConnection>                                    │
└─────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                              Fixtures                                   │
│                                                                         │
│   Broadcasts changes to all registered listeners                        │
│   (each WebSocket connection registers a listener)                      │
└─────────────────────────────────────────────────────────────────────────┘
```

## Message Types

All messages are JSON with a discriminator field for polymorphic serialization.

### Client → Server (InMessage)

#### ping

Keep-alive ping (server does nothing).

```json
{ "type": "ping" }
```

#### channelState

Request current values of all DMX channels.

```json
{ "type": "channelState" }
```

**Response**: `channelState` message with all channel values.

#### universesState

Request list of available DMX universes.

```json
{ "type": "universesState" }
```

**Response**: `universesState` message with universe list.

#### channelMappingState

Request channel-to-fixture mapping.

```json
{ "type": "channelMappingState" }
```

**Response**: `channelMappingState` message with mapping data.

Note: This is also automatically sent on connection and when fixtures change.

#### updateChannel

Directly set a DMX channel value.

```json
{
    "type": "updateChannel",
    "universe": 0,
    "id": 1,
    "level": 255,
    "fadeTime": 1000
}
```

| Field | Type | Description |
|-------|------|-------------|
| universe | Int | Universe number (within subnet 0) |
| id | Int | Channel number (1-512) |
| level | UByte | Target value (0-255) |
| fadeTime | Long | Fade duration in milliseconds |

### Server → Client (OutMessage)

#### channelState

DMX channel values (response to request or push on change).

```json
{
    "type": "channelState",
    "channels": [
        { "universe": 0, "id": 1, "currentLevel": 255 },
        { "universe": 0, "id": 2, "currentLevel": 128 },
        ...
    ]
}
```

When pushed on change, only changed channels are included.

#### universesState

List of available DMX universes.

```json
{
    "type": "universesState",
    "universes": [0, 1, 2]
}
```

#### channelMappingState

Channel-to-fixture mapping, organized by universe. Sent automatically on connection,
when fixtures change, or in response to a `channelMappingState` request.

```json
{
    "type": "channelMappingState",
    "mappings": {
        "0": {
            "1": { "fixtureKey": "hex-1", "fixtureName": "Hex 1", "description": "Dimmer" },
            "2": { "fixtureKey": "hex-1", "fixtureName": "Hex 1", "description": "Red" },
            "3": { "fixtureKey": "hex-1", "fixtureName": "Hex 1", "description": "Green" }
        }
    }
}
```

| Field | Type | Description |
|-------|------|-------------|
| mappings | Map<Int, Map<Int, Entry>> | Universe → Channel → Mapping |
| fixtureKey | String | Unique fixture identifier |
| fixtureName | String | Display name of the fixture |
| description | String | Channel description (e.g., "Dimmer", "Red") |

#### fixturesChanged

Notification that fixtures have been re-registered.

```json
{ "type": "fixturesChanged" }
```

Client should refresh fixture list via REST API.

#### fxPresetListChanged

Notification that the FX preset list has changed (preset added/updated/deleted).

```json
{ "type": "fxPresetListChanged" }
```

Client should refresh preset list via REST API.

#### cueListChanged

Notification that the cue list has changed (cue added/updated/deleted).

```json
{ "type": "cueListChanged" }
```

Client should refresh cue list via REST API.

#### userListChanged

Notification that a desk account was created, renamed, re-roled, enabled, disabled, deleted
or re-passworded. Sent to **every** socket.

```json
{ "type": "userListChanged" }
```

Client should refresh the user list via REST API. Payload-free deliberately, not just by
convention: sockets are open to operators while `/api/rest/users` is admin-only, so a body here
would leak what that gate withholds.

#### ownAccountChanged

Same trigger, but sent **only** to sockets belonging to the account that changed. Client should
re-read `GET /auth/status` — its display name or role moved, and the role decides which pages
its sidebar offers.

```json
{ "type": "ownAccountChanged" }
```

Never broadcast. If it were, one admin edit would make every connected client re-read its own
session.

#### installChanged

Notification that the install row changed (currently only its friendly name). Sent to every
socket; client should refresh `GET /install`.

```json
{ "type": "installChanged" }
```

**These three do not come from `FixturesChangeListener`.** Accounts and the install row belong to
the machine, and the diagram above is a *fixtures* diagram: its listener hangs off the per-project
`Fixtures` instance, which is torn down and re-registered on project switch. Machine-scoped state
reaches sockets through `SharedFlow`s collected per connection instead —
`AuthService.userChanges` (carrying a userId, so the collector can filter per recipient) and
`State.machineEventsFlow` (carrying ready-made messages) — both wired in
`plugins/MachineSocket.kt`. They are registered *before* the boot warm-up gate, unlike every
show-scoped subscription, because they read nothing off `state.show`.

## Connection Lifecycle

### On Connect

1. Session-cookie auth check; unauthenticated sockets are accepted and then closed `4401`
2. Machine-scoped subscriptions registered (account changes, install row) — these predate the
   warm-up gate because they read nothing off `state.show`
3. Boot progress streamed until `isShowReady`, or the socket returns on a `FAILED` boot
4. New `SocketConnection` created with unique ID and added to the global `connections` set
5. Each domain's `setupXxxSubscriptions` runs: `FixturesChangeListener` is registered, live
   subscriptions are opened, and **every stateful family's snapshot is pushed** — see
   §"Snapshot rule". The client needs to request nothing.
6. Connection ready to receive messages

The snapshot burst has no guaranteed order: the families are set up in separate coroutines, so a
client must key off message type, never position.

### Message Loop

```kotlin
for (frame in incoming) {
    val message = converter?.deserialize<InMessage>(frame)
    when (message) {
        is PingInMessage -> { /* no-op */ }
        is ChannelStateInMessage -> { /* send current values */ }
        is UpdateChannelInMessage -> { /* set channel value */ }
        // ...
    }
}
```

### On Disconnect

1. Connection removed from `connections` set
2. Listener unregistered from `Fixtures`
3. Resources cleaned up

## Real-time Updates

When DMX values change anywhere in the system:

```
ArtNetController
    │
    ▼ channelChanged
Fixtures (ChannelChangeListener)
    │
    ▼ channelsChanged
FixturesChangeListener (per WebSocket)
    │
    ▼ sendSerialized
WebSocket Client
```

Each connected client receives only the channels that changed:

```kotlin
override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {
    if (universe.subnet != 0) return  // Only subnet 0 supported

    val changeList = changes.map {
        ChannelState(universe.universe, it.key, it.value)
    }
    launch {
        sendSerialized<OutMessage>(ChannelStateOutMessage(changeList))
    }
}
```

## Subnet Limitation

Current implementation only supports subnet 0:

```kotlin
if (universe.subnet != 0) {
    return
}
```

Universe numbers are sent as-is (0-15 range within subnet 0).

## Thread Safety

- `connections`: `Collections.synchronizedSet` for thread-safe access
- Message sending: Uses `launch` for async non-blocking sends
- Listener callbacks: May be called from DMX transmission thread

## Typical Client Flow

### Initialization

```javascript
const ws = new WebSocket('ws://localhost:8413/api');

// No requests on open. The server pushes every stateful family's snapshot as part of the
// connect burst (§"Snapshot rule"), so asking only gets the frame twice. The request messages
// are for explicit resync — a tab returning from the background, say.
ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    switch (message.type) {
        case 'channelState':
            updateChannelDisplay(message.channels);
            break;
        // ...
    }
};
```

### Setting a Channel

```javascript
ws.send(JSON.stringify({
    type: 'updateChannel',
    universe: 0,
    id: 1,
    level: 255,
    fadeTime: 500
}));
```

### Keeping Alive

```javascript
setInterval(() => {
    ws.send(JSON.stringify({ type: 'ping' }));
}, 10000);
```

## Message Serialization

Uses kotlinx.serialization with polymorphic types:

```kotlin
@Serializable
sealed class InMessage

@Serializable
@SerialName("ping")
data object PingInMessage : InMessage()

@Serializable
@SerialName("updateChannel")
data class UpdateChannelInMessage(
    val universe: Int,
    val id: Int,
    val level: UByte,
    val fadeTime: Long,
) : InMessage()
```

The `@SerialName` annotation provides the JSON discriminator value.

## File Reference

| File | Purpose |
|------|---------|
| `plugins/Sockets.kt` | WebSocket configuration and message handling |
| `show/Fixtures.kt` | `FixturesChangeListener` interface |

## Configuration

In `Application.configureSockets()`:

```kotlin
install(WebSockets) {
    pingPeriod = Duration.ofSeconds(15)
    timeout = Duration.ofSeconds(15)
    maxFrameSize = Long.MAX_VALUE
    masking = false
    contentConverter = KotlinxWebsocketSerializationConverter(Json)
}
```
