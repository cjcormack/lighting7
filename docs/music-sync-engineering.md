# Music Sync Engineering Documentation

This document describes the music synchronization system that triggers lighting changes based on music playback.

## Overview

The music sync system uses gRPC to receive track change notifications from an external music player client. When the track changes, configured scripts can be triggered to update lighting accordingly.

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    External Music Player Client                         │
│                  (macOS app, iOS app, or other)                         │
│                                                                         │
│   Monitors music playback and sends track changes via gRPC              │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │ gRPC
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                           TrackServer                                   │
│                         (port 50051)                                    │
│                                                                         │
│   ┌──────────────────────────────────────────────────────────────────┐  │
│   │                    TrackDetailsService                           │  │
│   │                                                                  │  │
│   │   NotifyCurrentTrack(TrackDetails) ──────► show.trackChanged()   │  │
│   │                                                                  │  │
│   │   PlayerStateNotifier() ◄──────────────── show.trackStateFlow    │  │
│   │   (streaming response)                    (PING, HANDSHAKE)      │  │
│   └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────┬───────────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                               Show                                      │
│                                                                         │
│   trackChanged(details)                                                 │
│       │                                                                 │
│       ├── Update currentTrack                                           │
│       │                                                                 │
│       ├── If track changed && trackChangedScriptName configured:        │
│       │       evalScriptByName(trackChangedScriptName)                  │
│       │                                                                 │
│       └── Notify fixtures.trackChanged(isPlaying, artist, title)        │
│               │                                                         │
│               └── WebSocket broadcast to all clients                    │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

## gRPC Protocol

### Proto Definition

```protobuf
service TrackNotify {
    // Client sends track details when track changes
    rpc NotifyCurrentTrack (TrackDetails) returns (google.protobuf.Empty) {}

    // Server streams state updates to client (ping/handshake)
    rpc PlayerStateNotifier (google.protobuf.Empty) returns (stream TrackState) {}
}

enum PlayerState {
    HANDSHAKE = 0;  // Request current track info
    PING = 1;       // Keep-alive
    PLAYING = 2;    // Track is playing
    PAUSED = 3;     // Track is paused
}

message TrackState {
    PlayerState playerState = 1;
}

message TrackDetails {
    PlayerState playerState = 1;
    string title = 2;
    string artist = 3;
}
```

### NotifyCurrentTrack

Called by the music player client when:
- Track changes
- Playback state changes (play/pause)

```kotlin
override suspend fun notifyCurrentTrack(request: TrackDetails): Empty {
    show.trackChanged(request)
    return Empty.getDefaultInstance()
}
```

### PlayerStateNotifier

Streaming response that allows the server to send state to the client:

```kotlin
override fun playerStateNotifier(request: Empty): Flow<TrackState> {
    return show.trackStateFlow
}
```

The server uses this to:
- Send PING every 5 seconds (keep-alive)
- Send HANDSHAKE to request current track details

## Track Change Handling

In `Show.kt`:

```kotlin
fun trackChanged(newTrackDetails: TrackDetails) {
    // Check if track actually changed (not just play/pause)
    val trackHasChanged = currentTrackLock.write {
        val hasChanged = currentTrack?.artist != newTrackDetails.artist ||
                         currentTrack?.title != newTrackDetails.title
        currentTrack = newTrackDetails
        hasChanged
    }

    // Trigger script if configured and track changed
    if (trackHasChanged && trackChangedScriptName?.isNotEmpty() == true) {
        evalScriptByName(trackChangedScriptName)
    }

    // Notify WebSocket clients
    fixtures.trackChanged(
        newTrackDetails.playerState == PlayerState.PLAYING,
        newTrackDetails.artist,
        newTrackDetails.title
    )
}
```

## Configuration

In `local.conf`:

```hocon
lighting {
    trackChangedScriptName = "track-changed"  // Optional
}
```

The `Show` class receives this at construction:

```kotlin
class Show(
    // ...
    val trackChangedScriptName: String?,  // Script to run on track change
    // ...
)
```

## Script Access

Scripts can access current track information:

```kotlin
// In LightingScript
val currentTrack: TrackDetails?

// In script
if (currentTrack != null) {
    println("Now playing: ${currentTrack.artist} - ${currentTrack.title}")

    // Change lighting based on track
    when {
        currentTrack.artist.contains("Rock") -> {
            fixture<HexFixture>("front").rgbColour.value = Color.RED
        }
        currentTrack.title.contains("Chill") -> {
            fixture<HexFixture>("front").rgbColour.value = Color.BLUE
        }
    }
}
```

## Keep-Alive Mechanism

The server sends periodic pings to maintain the connection:

```kotlin
// In Show.start()
val pingTicker = ticker(5_000)  // Every 5 seconds
GlobalScope.launch {
    launch(newSingleThreadContext("TrackServerPing")) {
        while(coroutineContext.isActive) {
            select<Unit> {
                pingTicker.onReceiveCatching {
                    _trackStateFlow.emit(trackState {
                        playerState = PlayerState.PING
                    })
                }
            }
        }
    }
}
```

## Requesting Track Details

The server can request the current track from the client:

```kotlin
suspend fun requestCurrentTrackDetails() {
    _trackStateFlow.emit(trackState {
        playerState = PlayerState.HANDSHAKE
    })
}
```

This is triggered when a WebSocket client sends a `trackDetails` request.

## WebSocket Integration

Track changes are broadcast to WebSocket clients:

```kotlin
// In Fixtures
fun trackChanged(isPlaying: Boolean, artist: String, name: String) {
    changeListeners.forEach {
        it.trackChanged(isPlaying, artist, name)
    }
}

// In WebSocket listener
override fun trackChanged(isPlaying: Boolean, artist: String, name: String) {
    launch {
        sendSerialized<OutMessage>(TrackChangedOutMessage(isPlaying, artist, name))
    }
}
```

## Server Lifecycle

### Startup

```kotlin
// In Application.kt or main
val trackServer = TrackServer(50051, show)
trackServer.start()
```

### Shutdown Hook

```kotlin
Runtime.getRuntime().addShutdownHook(
    Thread {
        println("*** shutting down gRPC server since JVM is shutting down")
        this@TrackServer.stop()
        println("*** server shut down")
    },
)
```

## Thread Safety

| Resource | Protection |
|----------|------------|
| `currentTrack` | `ReentrantReadWriteLock` |
| `trackStateFlow` | `MutableSharedFlow` (coroutine-safe) |
| gRPC calls | Handled by gRPC framework |

## External Client Requirements

A music player client needs to:

1. Connect to gRPC server at `localhost:50051`
2. Call `NotifyCurrentTrack` when track changes or play/pause occurs
3. Subscribe to `PlayerStateNotifier` stream
4. Respond to `HANDSHAKE` by sending current track
5. Handle `PING` to confirm connection is alive

## Example Client Flow

```
Client                              Server
  │                                    │
  │──── Connect to PlayerStateNotifier ────►
  │                                    │
  │◄──────────── PING ─────────────────│  (every 5s)
  │                                    │
  │◄──────────── HANDSHAKE ────────────│  (request current)
  │                                    │
  │── NotifyCurrentTrack(PLAYING, ─────►
  │      "Artist", "Title")            │
  │                                    │
  │                           trackChanged() triggers script
  │                           WebSocket broadcast
  │                                    │
  │◄──────────── PING ─────────────────│
  │                                    │
  │── NotifyCurrentTrack(PAUSED, ──────►
  │      "Artist", "Title")            │
  │                                    │
```

## File Reference

| File | Purpose |
|------|---------|
| `trackServer/server.kt` | gRPC server and service implementation |
| `proto/trackNotify.proto` | Protocol buffer definitions |
| `show/Show.kt` | Track change handling and script triggering |
| `music/Music.kt` | Music service (stub for future Apple Music integration) |

## Future: Apple Music Integration

The `music/` package and `musicAuth` routes contain stubs for direct Apple Music API integration. Configuration in `local.conf`:

```hocon
music {
    issuer = ""      # Apple Music API issuer
    keyId = ""       # Key ID
    secret = """     # Private key
        -----BEGIN PRIVATE KEY-----
        ...
        -----END PRIVATE KEY-----
    """
}
```

This would allow the server to query music metadata directly rather than relying on an external client.
