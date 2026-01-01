# WebSocket Protocol Engineering Documentation

This document describes the WebSocket API for real-time communication between the server and frontend clients.

## Overview

The WebSocket API provides:
- Real-time DMX channel value updates
- Direct channel control from the UI
- Scene and fixture change notifications
- Music track information

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
│   │  Receive: channelState, channelMappingState, sceneChanged, etc. │   │
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
│   │   │  sceneListChanged() ─────► ScenesListChangedOutMessage   │  │   │
│   │   │  sceneChanged(id) ───────► ScenesChangedOutMessage       │  │   │
│   │   │  trackChanged() ─────────► TrackChangedOutMessage        │  │   │
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

#### trackDetails

Request current music track information.

```json
{ "type": "trackDetails" }
```

Triggers a handshake with the track server to get current track.

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

#### sceneListChanged

Notification that the scene list has changed (scene added/deleted).

```json
{ "type": "sceneListChanged" }
```

Client should refresh scene list via REST API.

#### sceneChanged

Notification that a scene's state has changed (active status, settings).

```json
{
    "type": "sceneChanged",
    "data": {
        "id": 1,
        "mode": "SCENE",
        "name": "Blue Wash",
        "scriptId": 5,
        "isActive": true,
        "settingsValues": {}
    }
}
```

#### fixturesChanged

Notification that fixtures have been re-registered.

```json
{ "type": "fixturesChanged" }
```

Client should refresh fixture list via REST API.

#### trackChanged

Music track information update.

```json
{
    "type": "trackChanged",
    "isPlaying": true,
    "artist": "Artist Name",
    "name": "Track Title"
}
```

## Connection Lifecycle

### On Connect

1. New `SocketConnection` created with unique ID
2. Added to global `connections` set
3. `FixturesChangeListener` registered with `Fixtures`
4. Initial `channelMappingState` sent to client
5. Connection ready to receive messages

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

ws.onopen = () => {
    // Request initial state
    ws.send(JSON.stringify({ type: 'universesState' }));
    ws.send(JSON.stringify({ type: 'channelState' }));
};

ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    switch (message.type) {
        case 'channelState':
            updateChannelDisplay(message.channels);
            break;
        case 'sceneListChanged':
            refreshSceneList();
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
