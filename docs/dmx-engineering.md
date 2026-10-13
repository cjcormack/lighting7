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
│    │      Transmission Thread (continuous, per-universe)      │     │
│    │       one frame every refreshIntervalMs, default 25ms     │     │
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
    suspend fun setValuesSuspend(valuesToSet: List<Pair<Int, ChannelChange>>)
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
   - Transmits **continuously**: one frame every `refreshIntervalMs`, whether or not any
     channel changed
   - Interval is per-universe and machine-local (default 25ms = 40 Hz); see
     [§Refresh interval](#refresh-interval)
   - Runs on an absolute-deadline loop, so per-frame work doesn't accumulate into drift,
     and a long stall re-bases rather than firing a catch-up burst

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

1. **Delta tracking**: Only values that changed since last transmission trigger listener
   notifications. This guard is load-bearing under continuous streaming — without it an
   idle universe would push a WebSocket channel update 40 times a second.
2. **Conflated channels**: Multiple rapid updates coalesce to a single transmitted frame
3. **Frame sampling**: Writes only touch `currentValues`; the loop samples that buffer once
   per tick, so any number of writes within one frame produce exactly one packet

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
- The show's hold is applied after both and is not a modifier — see [§The hold](#the-hold)
- Modifiers run in registration order (`CopyOnWriteArrayList` — snapshot iteration, no
  per-frame allocation)
- Modifiers are hot-path code: they must be fast and thread-safe

**Current users:**

- [`GlobalScalerState`](midi-control-surface-engineering.md) — Blackout and Grand Master
  are `TransmitModifier`s that zero intensity-category channels (DIMMER / UV / STROBE)
  when enabled. A toggle only flips the modifier's own state; the next frame of each
  universe picks it up. There is no push-to-transmit path — see
  [§Refresh interval](#refresh-interval).

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

### Suspend vs blocking commit

`DmxController` exposes two commit entry points:

- `suspend fun setValuesSuspend(values)` — hot writer paths (FX beat + wall-clock tick loops,
  MIDI surface input, script coroutines) should call this. Each per-channel update is sent
  and awaited as a child of `coroutineScope`, so the calling coroutine parks on a continuation
  rather than a thread. `ControllerTransaction.applySuspend()` fans commits across universes
  in parallel via `coroutineScope { launch { setValuesSuspend(…) } }`.
- `fun setValues(values)` / `ControllerTransaction.apply()` — convenience wrappers that
  delegate to the suspend path via `runBlocking`. Kept for callers that aren't in a coroutine
  context (tests, a few GENERAL-script apply edges).

Both variants still guarantee read-after-write consistency — a subsequent `getValue()` on the
same channel returns the committed value — because each channel's conflated consumer acks the
update before the commit returns. The suspend path just doesn't pin a carrier thread on
`runBlocking` while it waits.

The frame-transaction unification that would share a single `ControllerTransaction` between
the beat and wall-clock FX loops is not in scope for Phase 8. Continuous streaming has
since made the *packet-rate* half of that concern structurally impossible — a universe emits
exactly one frame per tick regardless of how many transactions committed — and the
coordination cost (shared `AtomicReference` + mutex around the open-close edge) isn't a clear
win for what remains. Tracked as a future consideration in
[fx-engineering.md §Future Considerations](fx-engineering.md#future-considerations).

### Benchmarking

`BenchmarkSetValues` (in `src/test/kotlin/uk/me/cormack/lighting7/dmx/`) is a track-only
micro-benchmark that times `ControllerTransaction.apply()` vs `applySuspend()` against a
4-universe rig (128 channel writes per universe per iteration). It drives an
`AsyncTestDmxController` that mirrors `ArtNetController`'s per-channel conflated consumer
+ ack-roundtrip pattern without UDP — `MockDmxController.setValuesSuspend` falls through
to the synchronous body and would silently flatter both paths.

Skipped by default; opt in with:

```
./gradlew test --tests "uk.me.cormack.lighting7.dmx.BenchmarkSetValues" -Ddmx.benchmark=true
```

Output is `[blocking]` / `[suspend]` summary lines on stdout. A fail-on-regression gate is
deferred to `FU-TEST-FX-BENCH-CI-GATE` pending a variance study.

## Timing Characteristics

| Parameter | Value | Notes |
|-----------|-------|-------|
| Fade tick interval | 10ms | Resolution of fade interpolation |
| Transmit interval | 25ms default | Per-universe, machine-local; 23-1000ms |
| Frame rate | 40 Hz default | `1000 / refreshIntervalMs`, emitted continuously |
| ArtNet protocol | UDP | Unreliable — which is *why* frames repeat |

## Refresh interval

Art-Net output is a **continuous stream**: every universe transmits one frame per
`refreshIntervalMs`, whether or not anything changed.

It did not always work that way. Transmission used to be change-driven — a 25 ms throttle,
then a block until some channel was written — so an idle universe emitted nothing at all.
That is unusual for a lighting desk, and the reason it matters is that **Art-Net is UDP with
no retransmission**: a single dropped datagram left the node holding a stale value until the
next time that channel happened to change. A blackout that silently doesn't take on one
universe, with nothing on the desk aware of it. Streaming repairs a lost frame on the next
tick.

Note this is *not* about refreshing fixtures. An Art-Net node holds the last frame it
received and clocks DMX512 out onto the physical line continuously at its own rate, so the
fixtures never see a gap regardless of what the desk does.

### Change latency

One consequence worth stating plainly: **a change reaches the wire within one
`refreshIntervalMs`, and nothing is special-cased — Blackout included.** A blackout is
materially no different to any other change in lighting state; it flips a `TransmitModifier`
and the next scheduled frame carries it, exactly like a cue go, an FX step or a fader move.
Park is the same: controllers consult the `ParkSource` at transmit time.

So a universe deliberately slowed toward the 1000 ms ceiling accepts up to a second of
latency on *everything*, which is the trade being made by slowing it — not a bug in one
control. There is deliberately no push-to-transmit path. `DmxController` briefly carried a
`requestTransmit()` hook from the change-driven era; it became a no-op the moment output
became a continuous stream and has been removed, because an out-of-band packet would push
the universe above its configured rate, which DMX512 bounds at ~44 frames/sec anyway.

### Configuring it

Per universe, on the Patches page's universe chip. Stored as a **machine-local override**
(`Overrides.FIELD_REFRESH_INTERVAL_MS` on `universeConfigs`), never synced — a rate chosen to
suit the node and fixtures physically present at one venue must not follow the show to the
next rig. See [sync-engineering.md §Machine-local data](sync-engineering.md).

Bounds are `23..1000` ms, enforced at the route (400) and clamped again in the controller
because the backing value is a SQLite row a user can hand-edit:

- **Floor (23 ms).** A full 513-slot DMX512 frame at 250 kbit/s occupies ~22.6 ms on the
  wire, so a node cannot emit more than ~44 frames/sec however fast we feed it — which is
  also where the Art-Net spec caps a controller. Below this, every extra packet is one the
  node discards, at a real network and CPU cost now that output is continuous.
- **Ceiling (1000 ms).** Nodes commonly fail-safe after ~4 s of silence; 1 Hz keeps 4x
  headroom while still allowing a deliberately slow universe.

A rate change **hot-swaps on the running controller** — the loop re-reads the volatile
period every frame — so it does not rebuild controllers, fixtures, or groups. Only an
address or controller-type change triggers `DbFixtureLoader.loadFixtures`.

Verify a configured rate against `GET /api/rest/perf/artnet-rates`, which reports observed
packets/sec per universe. A universe at 44 ms should settle around 22-23.

### Controller lifecycle

`ArtNetController.close()` cancels the transmission job, which releases the transmit thread
and the socket. **Closing replaced controllers is mandatory, not hygiene**: a controller
dropped from the register but left running keeps streaming its stale buffer to the very
universe its replacement just took over, and the node takes whichever datagram landed last.
Two sites close them — `Fixtures.register(removeUnused = true)` (fixture reload and project
switch) and `Fixtures.closeControllers()` from `Show.close()` (shutdown, which does not go
through the register path). `close()` is idempotent so the overlap is harmless.

To carry channel state across a rebuild, pass `initialValues` to the constructor rather than
calling `restoreState` afterwards: the loop starts transmitting immediately, so a
post-construction restore races the bootstrap frame and can let one all-zero frame reach the
rig.

## The hold

A show on a venue whose pinned version is behind the installed one **on output** is **held** (venue
plan P5, D7; session 6): its addresses would drive whatever the hall has hung there since. The hold is
[`OutputHold`](../src/main/kotlin/uk/me/cormack/lighting7/dmx/OutputHold.kt), one per show, handed by
`DbFixtureLoader` to every controller it builds and read by `sendCurrentValues()` once per frame:

```
for each channel:
    value = parked ?: modifiers(currentValues[channel])   // as above
    value = reserved[channel]?.guard(value) ?: value      // run-against only, below
    listeners hear value                                    // what every view draws
wire = held ? (parked ? value : 0 for each channel) : values
```

- **Above every modifier; a park gets through.** Held, every channel goes out at zero *but a parked
  one*, which goes out at its park (after its reserve). A park is the operator's hand on the channel —
  typically a hard-powered fixture's dimmer held up — and zeroing it would power-cycle that fixture
  because its show opened behind its venue. The hold used to sit above park too, on the argument that a
  v3 park on a v5 rig is as wrong as a v3 cue; the operator decided the fixture matters more.
  `DmxController.held` says the show is held; `MockDmxController.transmittedValue` is the wire for a
  test.
- **The show composes and draws as normal.** The buffer, `getValue` (park included) and the channel
  listeners — `channelState`, the Stage view, the channel views, Preview, the surfaces' feedback —
  carry on as if the hold were not there: only the bytes handed to the transport are zeroed. That is
  what lets the operator look at the show before deciding to update it.
- **Judged before a controller exists.** The loader asks `VenueHoldService.judge` (through the show's
  `RigHold`) first, so a held show never puts one frame on the rig; every load judges afresh, so an
  Update (which reloads) or a pulled pin move (the pull reloads) lifts or applies it. A publish or a
  venue pull that installs a newer version while a show is live and not held does **not** black it out
  mid-run: it carries on as if run against its pin (below) and opens held the next time it is opened.
- **Reserved channels.** *Run against vN* lifts the hold for the session (runtime only: it survives a
  reload of the same project at the same pin and installed version, and is dropped by another project,
  a moved pin, a newer version or a restart). While it runs, the **installed** version's one-shot
  trigger and arm channels and its fixture-command channels — at the venue's address and, for a unit
  the show re-addresses, at the show's — are reserved: a dedicated one goes out at its idle level
  whatever the old patch writes there (`Reserve.Fixed`), one the installed rig shares with a property
  (a MAC 250's shutter) at idle only inside a command's band (`Reserve.Band`). Unlike the hold this
  *is* what the wire carries, so the listeners hear it too. A unit the show hides counts: a hide takes
  it out of the show, not off the bar.
- **Not a transmit modifier.** A modifier runs under park and is a lighting rule; the hold is the last
  word on the wire and is not, so it is a field the transmit loop reads, beside the park view.
- Arming, firing and fixture commands are refused with `VENUE_HOLD` while the show is behind on
  output, held or running against (`state/VenueHoldService.kt`); a rehearsed fire, drawn and never
  sent, is not. Programmer Blind is unchanged — it is the programmer's gate, the hold is the show's.

Regression coverage: `OutputHoldTest` (the wire against the listeners, park through the hold, the
reserves) and `venue/VenueHoldTest` (a held show composes and sends zero but for a park, the refusals,
the reserved trigger channel under *Run against*).

## Handing the rig from one show to the next

A project switch and a **reload** (the current project again — a sync pull, a venue Update, Detach,
Extract, a venue's Discard all reload through `ProjectManager.switchProject`) tear the whole `Show`
down and build another. The rule both keep: a channel goes from its old value to its new one **without
a frame at zero** unless zero is where the next show puts it.

- **Freeze, then tear down.** `ProjectManager.shutdownShow` first freezes every outgoing controller on
  the frame it leaves (`DmxController.freeze`: the transmit loop sends that frame, park still overlaid,
  whatever the buffer does). Only then is the FX engine stopped — whose reset of every effect-covered
  property used to reach the wire for a frame or two, a dip — and the controllers closed.
- **The last frame goes first.** `ArtNetController.close` sends its final frame from its own thread as
  the cancel lands, which could follow a successor's first frame on the same universe.
  `Fixtures.register(removeUnused = true)` closes the outgoing controllers and waits for each one's
  final frame (`awaitClosed`, 250 ms at most) *outside* its lock — the final frame calls the channel
  listener, which takes the read lock — before its block can build the successors. Every patch-edit
  rebuild takes the same path.
- **A reload is asked for, never inferred.** `switchProject(id, reload = true)` is passed by the
  callers that changed the current project's rows underneath it — a sync pull
  (`RemoteSyncEngine.reloadShowIfActive`), a venue Update, Detach, Extract (REST and MCP) and a
  venue's Discard. Every other switch to the current project — `POST /set-current`, MCP's
  `switch_project` — is a clean restart: the switch rules below, with the project's own parks as
  the incoming ones, so its parked channels hold their level and everything else stops.
  `ProjectChangedEvent.reload` tells `State`'s collector which it was.
- **A switch** leaves a blackout, except on a channel the incoming project parks
  (`ParkManager.storedParks`), which stays as it was until the incoming park takes it: a channel both
  projects park goes from one park to the other. A channel only the outgoing project parks goes to zero
  with the incoming show's first frame — parks are per project, and that is the incoming project's word.
- **A reload** carries the rig across (`show/ShowCarry.kt`): the outgoing frame **as the rig shows
  it** — a parked channel at its park, and a held controller at zero bar its parks, so a reload
  that lifts a venue hold brings the new show up from dark rather than putting the held show's
  old-patch levels on the new addresses — each live stack's live cue and each armed standby, the
  `ProgrammerStore` itself (values, layers, sideband, Blind) with the `LocateManager` that pairs
  with its LOCATE slots, and every effect no cue or programmer layer owns. The successor's controllers are seeded with the frame and **frozen on it**
  (`Show.start(seedValues)`) while `Show.resume` puts the effects back, GOes each cue again (events not
  re-fired; its auto-advance timer starts again from the reload), re-arms each standby, re-cooks the programmer's layers and republishes it, publishes every patched property from
  whatever now composes it (so a property nothing drives any more releases to its base), and zeroes
  every channel no fixture covers and no programmer channel holds. Then it unfreezes, ramping every
  channel the rebuild moved from its carried value to its new one over `RESUME_FADE_MS` (2 s). A
  universe the reloaded project no longer has is left dark. Each resume step is guarded on its own, so
  one that fails is logged and the rest still run, and the freeze is always lifted. Effects restart
  their phase; cues applied outside a stack (`apply_cue`) are not brought back. The desk selection, encoder bank, busk page, hand
  and the programmer's scenery stay (`State`'s project collector skips their clears on
  `ProjectChangedEvent.reload`), each pruned of what the reload removed.

Regression coverage: `dmx/ControllerHandoverTest` (freeze, `awaitClosed`, no old frame after a
successor's first), `state/ProjectReloadCarryTest` (a reload with a cue, the programmer, an effect
and a park; a crossfaded change and a release; a dropped park handed down; standby and Locate carried;
the selection pruned; a same-id switch without `reload` a clean start; a switch between two projects
that park) and `venue/VenueHoldTest` (an Update that lifts the hold starts from the wire, not the
held composition).

## Parking

Parked channels are held at a fixed value by [`ParkManager`](../src/main/kotlin/uk/me/cormack/lighting7/dmx/ParkManager.kt)
and re-applied at transmit time in `ArtNetController.sendCurrentValues()` so they survive
regardless of what composition above produces. Conceptually parking is **Layer 1** — the
highest-priority layer — in the composition model; the transmit-time override is a
defence-in-depth implementation of that rule. See
[lighting-composition-model.md](lighting-composition-model.md) §"Layer 1".

The FX engine also consults `ParkManager.isParked` during effect reset to skip reset work for
fully-parked properties (an optimization; the transmit-time override would handle it anyway).

Unparking hands the parked value down to the layers below (direct-write store + controller
buffer) *before* removing the override, via the `UnparkValueSink` that `Show` wires into
`ParkManager` — so releasing park never moves the output and park/unpark is a safe toggle.
Regression coverage: `UnparkPreservesValueTest`.

The MCP tools `park_channel` / `unpark_channel` (`ai/AiTools.kt`) go through the same
`ParkManager` calls as the WebSocket's `parkChannel` / `unparkChannel`, so both keep these
properties; `describe_rig` and `get_current_state` list what is parked. See
[mcp-engineering.md](mcp-engineering.md) §"Tools".

## Thread Safety

- `currentValues`: `ConcurrentHashMap` for thread-safe reads
- Channel coroutines: Each channel isolated, no shared mutable state
- Listeners: Called from transmission thread, implementations must be thread-safe
- Transactions: Not thread-safe; create per-operation

## Error Handling

The transmission thread tracks consecutive errors:
- First error: Stack trace printed
- Subsequent errors: Suppressed (the next frame is the next retry)
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
