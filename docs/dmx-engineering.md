# DMX Subsystem Engineering Documentation

This document describes the low-level DMX control architecture in Lighting7.

## Overview

The DMX subsystem provides an abstraction layer for controlling DMX512 lighting fixtures over ArtNet protocol. It handles:

- Channel value management (512 channels per universe)
- Smooth fading between values
- Batched transmission to reduce network traffic
- Change notification for UI updates

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Higher-Level Code                            │
│                    (Fixtures, Shows, Scripts)                       │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     ControllerTransaction                           │
│              (Batches changes across universes)                     │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                        DmxController                                │
│                      (Sealed Interface)                             │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       ArtNetController                              │
│    ┌──────────────────────────────────────────────────────────┐     │
│    │  Per-Channel Coroutines (512)                            │     │
│    │  ┌─────────┐ ┌─────────┐ ┌─────────┐                     │     │
│    │  │ Chan 1  │ │ Chan 2  │ │  ...    │  → TickerState      │     │
│    │  │ Channel │ │ Channel │ │ Channel │    (fading)         │     │
│    │  └────┬────┘ └────┬────┘ └────┬────┘                     │     │
│    └───────┼───────────┼───────────┼──────────────────────────┘     │
│            │           │           │                                │
│            ▼           ▼           ▼                                │
│    ┌──────────────────────────────────────────────────────────┐     │
│    │              currentValues: ConcurrentHashMap            │     │
│    └──────────────────────────────┬───────────────────────────┘     │
│                                   │                                 │
│                                   ▼                                 │
│    ┌──────────────────────────────────────────────────────────┐     │
│    │           Transmission Thread (25ms throttle)            │     │
│    │                transmissionNeeded Channel                │     │
│    └──────────────────────────────┬───────────────────────────┘     │
└───────────────────────────────────┼─────────────────────────────────┘
                                    │
                                    ▼
                          ┌─────────────────┐
                          │  ArtNetClient   │
                          │  (artnet4j)     │
                          └────────┬────────┘
                                   │
                                   ▼
                          ┌─────────────────┐
                          │ Network (UDP)   │
                          │ Broadcast/      │
                          │ Unicast         │
                          └─────────────────┘
```

## Core Types

### Universe

```kotlin
data class Universe(
    val subnet: Int,   // ArtNet subnet (0-15)
    val universe: Int, // Universe within subnet (0-15)
)
```

ArtNet addressing uses subnet + universe to identify up to 256 universes. Each universe contains 512 DMX channels.

### ChannelChange

```kotlin
data class ChannelChange(
    val newValue: UByte,  // Target value (0-255)
    val fadeMs: Long      // Fade duration in milliseconds
)
```

Represents a channel value change request. If `fadeMs > 0`, the value transitions smoothly over time.

### DmxController (Interface)

```kotlin
sealed interface DmxController {
    val universe: Universe
    val currentValues: Map<Int, UByte>

    fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>)
    fun setValue(channelNo: Int, channelChange: ChannelChange)
    fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long = 0)
    fun getValue(channelNo: Int): UByte
}
```

Sealed interface allowing for multiple implementations (currently only ArtNet).

## ArtNetController

The primary implementation of `DmxController`. Key characteristics:

### Construction

```kotlin
ArtNetController(
    universe: Universe,
    address: String? = null,    // null = broadcast, else unicast
    needsRefresh: Boolean = false // periodic full retransmit
)
```

### Concurrency Model

The controller uses Kotlin coroutines with a channel-per-DMX-channel architecture:

1. **512 Channel Coroutines**: Each DMX channel has a dedicated coroutine with a conflated channel. This ensures:
   - Latest value always wins (no queue buildup)
   - Independent fade tracking per channel
   - Non-blocking value updates

2. **Transmission Thread**: A single dedicated thread (`ArtNetThread-{subnet}-{universe}`) handles network I/O:
   - Polls `transmissionNeeded` channel
   - Throttled to max 40 transmissions/second (25ms minimum interval)
   - Optional 1-second refresh for devices that need periodic updates

### Channel Update Flow

```
setValue(channelNo, ChannelChange)
         │
         ▼
┌────────────────────────┐
│ Validate channel (1-512)│
│ Validate value (0-255)  │
└───────────┬────────────┘
            │
            ▼
┌────────────────────────────────────────┐
│ Send ChannelUpdatePayload to channel   │
│ coroutine via channelChangeChannels    │
└───────────┬────────────────────────────┘
            │
            ▼
┌────────────────────────────────────────┐
│ Channel coroutine receives payload     │
│                                        │
│ if fadeMs == 0:                        │
│   Set value immediately                │
│ else:                                  │
│   Create TickerState for fading        │
└───────────┬────────────────────────────┘
            │
            ▼
┌────────────────────────────────────────┐
│ Update currentValues ConcurrentHashMap │
│ Signal transmissionNeeded              │
└───────────┬────────────────────────────┘
            │
            ▼
┌────────────────────────────────────────┐
│ Transmission thread wakes up           │
│ Sends ArtNet packet if values changed  │
│ Notifies listeners of changes          │
└────────────────────────────────────────┘
```

### Fading (TickerState)

When a channel change includes `fadeMs > 0`, a `TickerState` is created:

```kotlin
class TickerState(
    controller: ArtNetController,
    coroutineContext: CoroutineContext,
    channelNo: Int,
    numberOfSteps: Int,
    channelUpdatePayload: ChannelUpdatePayload
)
```

**Fade calculation:**
- Tick interval: 10ms (`fadeTickMs`)
- Number of steps: `fadeMs / fadeTickMs` (minimum 1)
- Step value: `(targetValue - startValue) / numberOfSteps`

**Value interpolation:**
- Uses `floor()` for increasing values
- Uses `ceil()` for decreasing values
- Ensures smooth monotonic transitions

**Fade interruption:**
- New value request cancels existing fade
- Fade resumes from current interpolated position

### Transmission Optimization

1. **Delta tracking**: Only values that changed since last transmission trigger listener notifications
2. **Conflated channels**: Multiple rapid updates coalesce to single transmission
3. **Throttling**: 25ms minimum between transmissions (40 Hz max)
4. **Optional refresh**: For devices that lose state, periodic full retransmit

### Listener Pattern

```kotlin
interface ChannelChangeListener {
    fun channelsChanged(changes: Map<Int, UByte>)
}
```

Listeners receive only changed channels after each transmission. Used by WebSocket layer to push updates to connected clients.

## ControllerTransaction

Batches changes across multiple universes for atomic application:

```kotlin
class ControllerTransaction(controllers: List<DmxController>)
```

### Usage

```kotlin
val transaction = ControllerTransaction(listOf(controller1, controller2))

// Queue up changes (doesn't transmit yet)
transaction.setValue(universe1, 1, 255u)
transaction.setValue(universe1, 2, 128u, fadeMs = 1000)
transaction.setValue(universe2, 1, 64u)

// Apply all changes at once
val applied = transaction.apply()

// Or use the convenience method:
transaction.use { tx ->
    tx.setValue(universe1, 1, 255u)
    tx.setValue(universe2, 1, 64u)
}
```

### Internal State

Each universe maintains:
- `currentValues`: Snapshot at transaction start + pending changes
- `valuesToSet`: Pending changes to apply

The `getValue()` method returns the pending value (what will be after `apply()`), not the actual current hardware value. This enables read-after-write consistency within a transaction.

## Timing Characteristics

| Parameter | Value | Notes |
|-----------|-------|-------|
| Fade tick interval | 10ms | Resolution of fade interpolation |
| Transmission throttle | 25ms | Max 40 packets/second |
| Refresh interval | 1000ms | Only if `needsRefresh=true` |
| ArtNet protocol | UDP | Unreliable, hence optional refresh |

## Thread Safety

- `currentValues`: `ConcurrentHashMap` for thread-safe reads
- Channel coroutines: Each channel isolated, no shared mutable state
- Listeners: Called from transmission thread, implementations must be thread-safe
- Transactions: Not thread-safe; create per-operation

## Error Handling

The transmission thread tracks consecutive errors:
- First error: Stack trace printed
- Subsequent errors: Suppressed (25ms backoff)
- After 20 consecutive errors: Controller shuts down, requires restart

## File Reference

| File | Purpose |
|------|---------|
| `DmxController.kt` | Sealed interface definition |
| `ArtNetController.kt` | ArtNet implementation with coroutine architecture |
| `Universe.kt` | Universe addressing data class |
| `ChannelChange.kt` | Value change request data class |
| `ChannelChangeListener.kt` | Observer interface for value changes |
| `ControllerTransaction.kt` | Multi-universe batching |
| `TickerState.kt` | Fade interpolation state machine |

## Dependencies

- **artnet4j 0.6.2**: Java ArtNet client library
- **kotlinx-coroutines**: Async channel and ticker APIs
