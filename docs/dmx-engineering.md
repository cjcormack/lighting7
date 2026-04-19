# DMX Subsystem Engineering Documentation

This document describes the low-level DMX control architecture in Lighting7.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md) for the
> layered composition model (parking → effects → property assignments → direct writes →
> baseline) that sits above this transport layer. Parking is Layer 1 in that model.

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
    val newValue: UByte,          // Target value (0-255)
    val fadeMs: Long,             // Fade duration in milliseconds
    val curve: EasingCurve        // Interpolation curve (default LINEAR)
)
```

Represents a channel value change request. If `fadeMs > 0`, the value transitions smoothly
over time, interpolated by the chosen [easing curve](#easing-curves).

### DmxController (Interface)

```kotlin
sealed interface DmxController {
    val universe: Universe
    val currentValues: Map<Int, UByte>
    val parkedChannels: Map<Int, UByte>

    fun setValues(valuesToSet: List<Pair<Int, ChannelChange>>)
    fun setValue(channelNo: Int, channelChange: ChannelChange)
    fun setValue(channelNo: Int, channelValue: UByte, fadeMs: Long = 0)
    fun getValue(channelNo: Int): UByte

    // Parking (Layer 1)
    fun park(channelNo: Int, value: UByte)
    fun unpark(channelNo: Int)
    fun isParked(channelNo: Int): Boolean

    // Transmit-time modifiers (Blackout / Grand Master / future)
    fun addTransmitModifier(modifier: TransmitModifier)
    fun removeTransmitModifier(modifier: TransmitModifier)

    // Wake the transmission thread immediately instead of waiting for the next 25 ms tick
    fun requestTransmit()
}
```

Sealed interface allowing for multiple implementations (currently `ArtNetController` +
`MockDmxController` for tests).

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
- Per-tick normalized position `t = stepIndex / numberOfSteps` is remapped by the
  [easing curve](#easing-curves) before interpolation

**Value interpolation:**
- `interpolatedValue = lerp(startValue, targetValue, curve(t))`
- Uses `floor()` for increasing values, `ceil()` for decreasing values, to ensure monotonic
  transitions even under curve remapping

**Fade interruption:**
- New value request cancels existing fade
- Fade resumes from current interpolated position, not from the original start value

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

Listeners receive only changed channels after each transmission — the controller compares
the post-modifier, post-park transmit buffer against `previousSentDmxData` and fires the
callback with the delta map. This is the **single observation point** for
"something changed on the wire", and it's used by:

- The WebSocket layer to push `channelState` updates to connected clients
- [`SurfaceFeedbackPublisher`](midi-control-surface-engineering.md) to drive motor faders
  and encoder rings in response to composition changes

Because every layer (parking, effects, cues, direct writes, transmit modifiers) converges
at this listener, observers never need to know *how* a value got to the wire — only that it
did.

**Threading**: called on the transmission thread. Listeners must be thread-safe and fast.

## Transmit Modifiers

Transmit modifiers are a post-composition, pre-wire transform hook:

```kotlin
fun interface TransmitModifier {
    fun modify(universe: Universe, channel: Int, value: UByte): UByte
}
```

Registered via `DmxController.addTransmitModifier(modifier)` and applied inside
`sendCurrentValues()` **after** parking has been resolved. Order of application:

```
for each channel:
    value = parkedChannels[channel] ?: currentValues[channel]
    if not parked:
        for modifier in transmitModifiers:
            value = modifier.modify(universe, channel, value)
    transmit(value)
```

**Key invariants:**

- Modifiers do **not** see parked channels — parking is an unconditional override
- Modifiers run in registration order (`CopyOnWriteArrayList` — snapshot iteration, no
  per-frame allocation)
- Modifiers are hot-path code: they must be fast and thread-safe

**Current users:**

- [`GlobalScalerState`](midi-control-surface-engineering.md) — Blackout and Grand Master
  are `TransmitModifier`s that zero intensity-category channels (DIMMER / UV / STROBE)
  when enabled. Toggling calls `requestTransmit()` so the change is visible within the
  next frame rather than up to 25 ms later.

This pattern generalises: any feature that needs to transform DMX values at transmit
time without being part of the composition layers (parking is the other canonical
example) can be a `TransmitModifier`.

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

### Blocking commit caveat

`ArtNetController.setValues()` (called by `transaction.apply()`) uses `runBlocking` internally
to wait for every per-channel coroutine to acknowledge the update before returning. This
guarantees immediate read-after-write consistency — a subsequent `getValue()` on the same
channel returns the committed value — but it means the *caller* thread blocks until every
channel coroutine has processed its payload.

In practice channel coroutines are very fast (they queue into a conflated channel and return)
so stalls are rare. However, as more writers arrive (FX tick at 50 Hz beat + 50 Hz wall-clock,
MIDI surface at up to 60 Hz, WebSocket clients), the aggregate blocking cost on the common
writer threads is something to watch. A suspend-based `setValues()` that returns a
`Deferred<Unit>` is a possible future improvement — tracked as Phase 8 of
[control-surface-plan.md](control-surface-plan.md#phase-8--non-blocking-setvalues).

## Timing Characteristics

| Parameter | Value | Notes |
|-----------|-------|-------|
| Fade tick interval | 10ms | Resolution of fade interpolation |
| Transmission throttle | 25ms | Max 40 packets/second |
| Refresh interval | 1000ms | Only if `needsRefresh=true` |
| ArtNet protocol | UDP | Unreliable, hence optional refresh |

## Parking

Parked channels are held at a fixed value by [`ParkManager`](../src/main/kotlin/uk/me/cormack/lighting7/dmx/ParkManager.kt)
and re-applied at transmit time in `ArtNetController.sendCurrentValues()` so they survive
regardless of what composition above produces. Conceptually parking is **Layer 1** — the
highest-priority layer — in the composition model; the transmit-time override is a
defence-in-depth implementation of that rule. See
[lighting-composition-model.md](lighting-composition-model.md) §"Layer 1".

The FX engine also consults `ParkManager.isParked` during effect reset to skip reset work for
fully-parked properties (an optimization; the transmit-time override would handle it anyway).

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

## Easing Curves

Fades interpolate via a pluggable `EasingCurve` enum (one `curve(t)` call per tick,
mapping a linear `[0, 1]` position into an eased position):

| Curve | Description |
|---|---|
| `LINEAR` | Constant rate (the default) |
| `SINE_IN` / `SINE_OUT` / `SINE_IN_OUT` | Sine-based ease |
| `QUAD_IN` / `QUAD_OUT` / `QUAD_IN_OUT` | Quadratic ease |
| `CUBIC_IN` / `CUBIC_OUT` / `CUBIC_IN_OUT` | Cubic ease |
| `STEP` | Jump at `t = 1.0` |
| `STEP_HALF` | Jump at `t = 0.5` |

Defined in `dmx/EasingCurve.kt`. Used by `TickerState` for DMX fades and by cue crossfades
when the property category is a slider (see
[lighting-composition-model.md §Crossfade](lighting-composition-model.md#crossfade-behaviour)).

## File Reference

| File | Purpose |
|------|---------|
| `DmxController.kt` | Sealed interface definition |
| `ArtNetController.kt` | ArtNet implementation with coroutine architecture |
| `MockDmxController.kt` | In-memory fake for tests; `getEffectiveValue()` for asserting modifier chain |
| `Universe.kt` | Universe addressing data class |
| `ChannelChange.kt` | Value change request data class (with easing curve) |
| `ChannelChangeListener.kt` | Observer interface for value changes (delta-only) |
| `ControllerTransaction.kt` | Multi-universe batching |
| `TickerState.kt` | Fade interpolation state machine (curve-aware) |
| `EasingCurve.kt` | Easing curve implementations |
| `TransmitModifier.kt` | Post-composition, pre-wire transform hook |
| `ParkManager.kt` | Layer 1 parking: persistence + in-memory state + WS broadcast |

## Dependencies

- **artnet4j 0.6.2**: Java ArtNet client library
- **kotlinx-coroutines**: Async channel and ticker APIs
