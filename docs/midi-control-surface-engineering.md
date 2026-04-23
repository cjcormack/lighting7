# MIDI Control Surface Engineering Documentation

This document describes the MIDI control-surface subsystem: how external hardware (faders,
encoders, buttons) drives the lighting composition model and how fixture state feeds back to
LEDs and motorised faders.

Related:
- Strategic plan (phases, decisions, open questions): [control-surface-plan.md](control-surface-plan.md).
- Composition layers surfaces write into: [lighting-composition-model.md](lighting-composition-model.md).
- Transport layer surfaces write through: [dmx-engineering.md](dmx-engineering.md).

## Overview

Control surfaces are *another client of the composition model* — not a parallel pipeline.
A fader bound to a fixture dimmer writes **Layer 4** (the same `DirectWriteStore` the web UI
`updateChannel` message uses). A button bound to cue-stack GO dispatches through the same
`CueStackManager` the REST API does. Blackout and Grand Master are applied as `TransmitModifier`s
on the ArtNet controller, alongside parking. There is no "surface mode" in the engine.

The implementation follows four separable concerns, mirroring the phase breakdown in
[control-surface-plan.md](control-surface-plan.md):

1. **Transport** — MIDI I/O via ktmidi with a dedicated per-device thread and conflated
   outbound channels (pattern borrowed from `ArtNetController`).
2. **Device profile** — a Kotlin class describing the physical layout of a supported device,
   discovered reflectively at startup.
3. **Binding** — a DB row mapping `(deviceTypeKey, controlId, bank)` to a `BindingTarget`.
4. **Routing + reconciliation** — inbound MIDI → target action; fixture state → outbound
   feedback with touch suppression and soft-takeover.

```
  ┌─────────────────────────────────────────────────────────────────────────┐
  │ INBOUND                                                                  │
  │                                                                          │
  │  Physical control                                                        │
  │    │                                                                     │
  │    ▼                                                                     │
  │  LibreMidiAccessSource (listener)  ──▶  MidiMessageParser                │
  │    │                                      │                              │
  │    ▼                                      ▼                              │
  │  KtMidiController.input (SharedFlow<MidiInputEvent>)                    │
  │    │                                                                     │
  │    ▼                                                                     │
  │  SurfaceInputRouter                                                      │
  │    │                                                                     │
  │    ├─ matchEvent(profile, event) → ResolvedInput                         │
  │    │    (ControlDescriptor lookup via profile.controls)                  │
  │    │                                                                     │
  │    ├─ BankButton? → ActiveBankState.setBank  (short-circuit)             │
  │    │                                                                     │
  │    ├─ Continuous? → SurfaceFeedbackHooks.acceptInboundFader              │
  │    │    (soft-takeover gate; may suppress)                               │
  │    │                                                                     │
  │    ├─ ControlSurfaceBindingService.resolve(…) → ResolvedBinding?         │
  │    │                                                                     │
  │    └─ SurfaceActions dispatch by BindingTarget variant:                  │
  │         ├─ FixtureProperty / GroupProperty → DirectWriteStore (L4)       │
  │         ├─ CueStackGo / Back / Pause / FireCue → CueStackManager         │
  │         ├─ Flash → L4 write on press, clear on release                   │
  │         ├─ Blackout / GrandMasterToggle → GlobalScalerState              │
  │         └─ SetBank → ActiveBankState.setBank                             │
  │                                                                          │
  └─────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │ OUTBOUND (feedback)                                                      │
  │                                                                          │
  │  ArtNetController.sendCurrentValues                                      │
  │    │                                                                     │
  │    ▼  ChannelChangeListener (post-transmit, delta-only)                  │
  │  Fixtures.FixturesChangeListener.channelsChanged                        │
  │    │                                                                     │
  │    ▼                                                                     │
  │  SurfaceFeedbackPublisher                                                │
  │    ├─ byChannel index lookup: (universe, channel) → List<ContinuousEntry>│
  │    ├─ computeValue7Bit(entry): read live DMX → scale to 0..127           │
  │    ├─ TouchStateTracker.isTouched(displayKey, controlId) → skip motor    │
  │    ├─ SoftTakeoverStateMachine.setLogical(…) → update pickup target      │
  │    └─ sendContinuousFeedback → MidiFeedbackMessage                       │
  │                                                                          │
  │  Parallel subscribers feed other LEDs:                                   │
  │    FlashStateTracker.changes → flash LEDs                                │
  │    GlobalScalerState.blackoutEnabled + grandMasterEnabled → toggle LEDs  │
  │    ActiveBankState.changes → bank-button LEDs + full resync              │
  │    ControlSurfaceBindingService.bindingChanges → rebuild feedback index  │
  │                                                                          │
  │    ▼                                                                     │
  │  KtMidiController.sendFeedback                                           │
  │    ├─ per-MidiControlKey conflated Channel                               │
  │    ├─ 60 Hz transmit loop on dedicated thread                            │
  │    └─ delta suppression via lastSentBytes                                │
  │    ▼                                                                     │
  │  LibreMidiAccessSource.KtMidiSendTarget.send → native libremidi          │
  └─────────────────────────────────────────────────────────────────────────┘
```

## Package Layout

All code lives under `src/main/kotlin/uk/me/cormack/lighting7/midi/`.

### Transport

| File | Purpose |
|---|---|
| `MidiController.kt` | Transport interface (relaxed from `sealed` — see Phase 4 change log in plan). Exposes `input: SharedFlow<MidiInputEvent>`, `sendFeedback(MidiFeedbackMessage)`, `close()`. Test-seam interfaces: `MidiSendTarget`, `MidiInputSource`, `MidiAccessSource`. |
| `KtMidiController.kt` | ktmidi-backed implementation. Dedicated `newSingleThreadContext("MidiThread-${displayKey}")`; per-`MidiControlKey` conflated channels; 60 Hz transmission loop with delta suppression. |
| `LibreMidiAccessSource.kt` | Wraps ktmidi's `LibreMidiAccess` (native libremidi via Panama FFM) into the `MidiAccessSource` abstraction. |
| `MidiInputEvent.kt` | Sealed ADT: `NoteOn` / `NoteOff` / `ControlChange` / `PitchBend` / `SysEx`. |
| `MidiFeedbackMessage.kt` | Outbound ADT with `controlKey` for conflation and `encode(): ByteArray`. |
| `MidiDevicePort.kt` | Port enumeration record. |
| `MidiDeviceRegistry.kt` | 1 Hz polling diff of `MidiAccess.inputs ∪ outputs`; emits `DeviceConnected` / `DeviceDisconnected` via `SharedFlow<DeviceEvent>`; auto-opens controllers on connect when `autoOpen = true`. |

### Device profile

| File | Purpose |
|---|---|
| `ControlSurfaceType.kt` | `@ControlSurfaceType(typeKey, vendor, product, portPattern)` annotation. |
| `ControlSurfaceDevice.kt` | Base class with a small DSL: `motorFader`, `fader`, `encoder`, `button`, `bankButton`, `bank`. |
| `ControlDescriptor.kt` | Sealed ADT: `FaderDescriptor`, `EncoderDescriptor`, `ButtonDescriptor`, `BankButtonDescriptor`. Enums: `LedFeedback`, `FaderResolution`, `EncoderRingStyle`. |
| `ControlSurfaceRegistry.kt` | Reflective discovery of `@ControlSurfaceType` classes; fail-fast on duplicate `typeKey` or duplicate `controlId` within a class. |
| `DeviceMatcher.kt` | Matches attached `MidiDeviceHandle`s against registered profiles by `portPattern`; emits `DeviceAttached` / `DeviceDetached` / `UnmatchedDeviceConnected` on a `SharedFlow<SurfaceEvent>`. |
| `devices/XTouchCompactStandard.kt` | Behringer X-Touch Compact (Standard mode) — 9 motor faders, 16 encoders, 39 buttons, A/B bank buttons. Reference device. |

### Binding and Learn

| File | Purpose |
|---|---|
| `BindingTarget.kt` | Sealed ADT of what a control drives: `FixtureProperty`, `GroupProperty`, `CueStackGo` / `Back` / `Pause`, `FireCue`, `Flash(target, max)`, `Blackout`, `GrandMasterToggle`, `SetBank`. Serialized as JSON with `classDiscriminator = "type"` and `ignoreUnknownKeys = true`. Persisted as text in `DaoControlSurfaceBindings.targetPayload`. |
| `ControlSurfaceBindingService.kt` | Binding CRUD + in-memory resolver cache keyed by `(projectId, deviceTypeKey, controlId, bank)`; exact-bank wins over global. Emits `BindingChange` events; broadcast via `surfaceBindingsChanged`. |
| `MidiLearnSessionManager.kt` | 30-second Learn sessions; captures the first matching physical input, holds the captured descriptor until the originating client commits or cancels. Scoped to the originating client via `ownedLearnSessions` so two `/surfaces` tabs don't cross-capture. |

### Inbound routing

| File | Purpose |
|---|---|
| `SurfaceInputRouter.kt` | Per-device `CoroutineName("SurfaceRouter-$displayKey")` collector. Pipeline: `matchEvent → soft-takeover gate → binding resolve → SurfaceActions dispatch`. |
| `SurfaceActions.kt` | Port interface between router and show services. Production: `DefaultSurfaceActions` resolves `state.show.*` on every call so project switches route cleanly. Tests: `RecordingActions`. |
| `PropertyChannelResolver.kt` | `object` for **MIDI surface input only**: takes a 7-bit MIDI value and produces `List<ChannelWrite>`. Sliders scale to each channel's native `min..max`; Colour fans the 7-bit value to R/G/B; Settings return empty (enum bindings are button-only — Open Question 7). For property-value → channel resolution at the effects layer (preset-toggle Layer 4, future REST property writes, "preview on selection"), see `fx/PropertyChannelWriter` which accepts full-range `Layer3Resolver.PropertyValue` variants and handles Colour + Position without MIDI-7bit scaling. |
| `ActiveBankState.kt` | Ephemeral `deviceTypeKey → bank` map backed by a `ConcurrentHashMap` fast-lookup plus a `changes: SharedFlow<BankChange>` for WS broadcast. Not persisted. |
| `FlashStateTracker.kt` | Lock-free `Set<Int>` of currently-held binding IDs (overlapping presses don't clobber release semantics). Exposes `changes: SharedFlow<FlashChange>`. |
| `GlobalScalerState.kt` | `TransmitModifier` implementation of Blackout + Grand Master. Walks fixtures on `fixturesChanged` to classify intensity-category channels into a packed-`Long` `AtomicReference<Set<Long>>` for allocation-free hot-path lookup. On toggle, calls `DmxController.requestTransmit()` on every attached controller so the change is visible within the next frame rather than up to 25 ms later. |

### Feedback and reconciliation

| File | Purpose |
|---|---|
| `SurfaceFeedbackPublisher.kt` | Owns a reverse `packedChannelKey → List<ContinuousEntry>` index plus parallel `ledAll` / `flashLedIndex` / `blackoutLeds` / `grandMasterLeds` maps. Rebuilt atomically in an `AtomicReference` on any of: device attach/detach, binding change, bank change, fixture registration change, project change. Defines `SurfaceFeedbackHooks` (a small port the router calls before binding resolution — `acceptInboundFader`, `onTouch`). |
| `SoftTakeoverStateMachine.kt` | Per-`(displayKey, controlId)` `Entry(state, lastPhysical, target)`. PICKUP state blocks inbound until the fader crosses the target value (supports both from-below and from-above crossing; ±1 jitter tolerance). IMMEDIATE state passes through. Broadcasts `PickupStateChange` transitions. |
| `TouchStateTracker.kt` | Per-`(displayKey, controlId)` touched flag. While `true`, `SurfaceFeedbackPublisher` suppresses motor writes to that control (don't fight the user's finger). |

## Transport (KtMidiController)

Concurrency shape deliberately mirrors [ArtNetController](dmx-engineering.md):

- **One dedicated single-thread context per device** — `newSingleThreadContext("MidiThread-${handle.displayKey}")`. All outbound sends happen on this thread.
- **Per-`MidiControlKey` conflated Kotlin channels** — last-write-wins. Rapid updates to the same LED coalesce.
- **Conflated `pendingSignal`** acts as the "there's work" flag; idle ticks do no work.
- **60 Hz transmit loop** (`DEFAULT_TRANSMIT_INTERVAL_MS = 17L`) — slightly faster than the ArtNet 25 ms loop to keep LED response crisp.
- **Delta suppression** — `lastSentBytes[key]` compared byte-for-byte before `MidiSendTarget.send`.
- **Consecutive-error backoff** matching ArtNet semantics: first error prints stack trace; > 20 consecutive errors breaks the loop.

### Input path

`LibreMidiAccessSource` installs a raw-bytes listener. `MidiMessageParser` handles channel-voice decoding, running status, multi-packet SysEx accumulation, and swallows system real-time / system common interleaves. Parsed events go to a `MutableSharedFlow<MidiInputEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)` — there is no backpressure at the transport layer. If a consumer can't keep up, oldest events are dropped. For a 60 Hz fader at full tilt that's ~60 CC/sec, well within the buffer.

### Hot-plug

`MidiDeviceRegistry` diffs `MidiAccess.inputs ∪ outputs` on a 1 Hz timer — libremidi has no state-changed callbacks (`MidiAccess.canDetectStateChanges = false` for every desktop backend). Connect / disconnect events fan out on a `SharedFlow<DeviceEvent>`. Auto-open is controlled by the `autoOpen` flag; on connect the registry opens the device and wraps it in a `KtMidiController`, pushing to a `StateFlow<List<MidiDeviceHandle>>` that downstream consumers observe.

## Device profile model

Profiles are Kotlin classes, not DB rows. The registry is the source of truth. Adding a device = one `.kt` file in `midi/devices/` and one line in `ControlSurfaceRegistry.known`. Pattern mirrors `FixtureTypeRegistry`.

```kotlin
@ControlSurfaceType(
    typeKey = "x-touch-compact-standard",
    vendor = "Behringer",
    product = "X-Touch Compact",
    portPattern = "(?i)x[ _-]?touch[ _-]?compact",
)
class XTouchCompactStandard : ControlSurfaceDevice() {
    init {
        repeat(9) { i ->
            motorFader(id = "fader-${i + 1}", cc = 1 + i, touchCc = 101 + i, label = …)
        }
        // Encoders 1–8: top horizontal row above the button block.
        repeat(8) { i ->
            encoder(id = "enc-${i + 1}", cc = 10 + i, ringCc = 10 + i, pushNote = 0 + i, …)
        }
        // Encoders 9–16: right-side 2×4 block above the master fader.
        repeat(8) { i ->
            encoder(id = "enc-${i + 9}", cc = 18 + i, ringCc = 18 + i, pushNote = 8 + i, …)
        }
        repeat(39) { i ->
            button(id = "btn-${i + 1}", note = 16 + i, ledFeedback = LedFeedback.ON_OFF, …)
        }
        bank(id = "layer-a", name = "A", inputProgramChange = 0)
        bank(id = "layer-b", name = "B", inputProgramChange = 1)
    }
}
```

The `ControlSurfaceRegistry` is strictly stricter than `FixtureTypeRegistry` — it fails fast on duplicate `typeKey` *and* on duplicate `controlId` within a class. Rationale: `controlId` is a stable contract persisted in binding rows, so a duplicate would silently break persisted bindings.

### DeviceMatcher

`DeviceMatcher` subscribes to `MidiDeviceRegistry.events` and matches each attached `MidiDeviceHandle` against registered profiles by running `portPattern.matches(displayName)`. Emits:

- `DeviceAttached(handle, profile)` — device matched a registered `@ControlSurfaceType`
- `DeviceDetached(displayKey)` — device unplugged
- `UnmatchedDeviceConnected(handle)` — device with no matching profile; MIDI Learn can still bind it via its generic CC / note scheme

Every inbound `Connected` event yields exactly one `SurfaceEvent` — the matcher is the single arbiter of "is this device one we know about?".

## Binding model

A binding is a row in `DaoControlSurfaceBindings` keyed by:

```
(projectId, deviceTypeKey, controlId, bank: String?)
```

where `bank = null` means "global (applies on any bank)" and `bank = "A"` means "only when bank A is active". Resolution: exact-bank wins over global; within a bank, at most one binding per control.

The `BindingTarget` payload is stored as **text** (JSON), not `json<T>()`, so new variants can be added without schema changes. The JSON codec has `ignoreUnknownKeys = true` for forward/backward compatibility.

The `ControlSurfaceBindingService` maintains an in-memory resolver cache rebuilt on every mutation (add/update/remove) and on binding reload after project switch. Lookup on the hot path is O(1) `HashMap` with a fallback to the global-bank entry.

### BindingTarget variants

| Variant | Control type | Effect |
|---|---|---|
| `FixtureProperty(fixtureKey, propertyName)` | Continuous | Layer 4 write via `DirectWriteStore.putProperty`; channels derived through `PropertyChannelResolver` |
| `GroupProperty(groupName, propertyName)` | Continuous | Same, fanned out across group members |
| `CueStackGo(stackId)` | Button | `CueStackManager.activateAtFirstCue` or `advanceStack(FORWARD)` on press |
| `CueStackBack(stackId)` | Button | `CueStackManager.advanceStack(BACKWARD)` |
| `CueStackPause(stackId)` | Button | `CueStackManager.pauseAutoAdvance(stackId)` |
| `FireCue(cueId)` | Button | `CueStackManager.fireCue(cueId)` |
| `Flash(target, max)` | Button (momentary) | Press: Layer 4 write at `minOf(max, sliderMax)`. Release: clear entry + write 0 immediately so composition resolver takes over |
| `Blackout` | Button | `GlobalScalerState.toggleBlackout()` |
| `GrandMasterToggle` | Button | `GlobalScalerState.toggleGrandMaster()` |
| `SetBank(deviceTypeKey, bank)` | Button / bank-button | `ActiveBankState.setBank(deviceTypeKey, bank)` |

**Related non-MIDI path.** Phase 7 of `docs/cue-authoring-unification-plan.md` adds a
separate FX-layer resolver, `fx/PropertyChannelWriter`, for property-value → channel
resolution without 7-bit MIDI scaling. The surface-input path above keeps using
`PropertyChannelResolver` for 7-bit input; Layer 4 writes originating elsewhere
(preset-toggle, future REST property writes, "preview on selection") go through
`FxEngine.writeLayer4Property` which delegates to `PropertyChannelWriter`. Both resolvers
emit the same `PropertyChannelResolver.ChannelWrite` shape, so downstream consumers
don't need to distinguish.

### MIDI Learn

`MidiLearnSessionManager` implements 30-second capture sessions:

1. Client calls `beginLearn(sessionId, targetContext)` — server subscribes to every attached device's `input` flow.
2. First matching physical input (Fader CC, Button note, etc.) is captured as a `ResolvedInput`; the session transitions to `Captured`.
3. Client commits (persisting a binding) or cancels.
4. Idle sessions expire after 30 s.

Capture events are scoped to the originating WS connection via `ownedLearnSessions` so two `/surfaces` tabs don't cross-capture.

## Inbound routing (SurfaceInputRouter)

Per-device coroutine `CoroutineName("SurfaceRouter-$displayKey")` collects from `MidiController.input`. Pipeline:

### 1. Match event to control descriptor

`matchEvent(profile, event) → ResolvedInput?`:

| Input event | Matches |
|---|---|
| `ControlChange(cc)` | `FaderDescriptor.cc` or `EncoderDescriptor.cc` → `Continuous(controlId, value7Bit)` |
| `NoteOn(note)` | `ButtonDescriptor.note` → `ButtonPress(controlId)` |
| `NoteOn(note)` | `EncoderDescriptor.pushNote` → `ButtonPress(controlId)` |
| `NoteOn(note)` | `FaderDescriptor.touchNote` → `Touch(controlId, down=true)` |
| `ControlChange(cc)` | `FaderDescriptor.touchCc` → `Touch(controlId, down = value > 0)` |
| `NoteOn(note)` | `BankButtonDescriptor.inputNote` → `BankButton(bankId, pressed=true)` |
| `NoteOff(note)` | Corresponding release events |

### 2. Bank-button short-circuit

`BankButtonDescriptor` is intentionally not user-bindable — bank buttons call `ActiveBankState.setBank(deviceTypeKey, bankId)` directly, bypassing the binding resolver. A user who wants to swap banks from an arbitrary button binds `BindingTarget.SetBank` instead.

### 3. Soft-takeover gate (continuous only)

Before binding resolution, `SurfaceFeedbackHooks.acceptInboundFader(displayKey, controlId, value7Bit)` is consulted. For non-motor faders with `BindingTakeoverPolicy.PICKUP`, the `SoftTakeoverStateMachine` checks whether the physical value has crossed the current logical target (supports both from-below and from-above crossings; ±1 jitter tolerance). If not, the event is suppressed and a `surfacePickup.changed` WS message broadcasts the waiting state to the frontend. On crossing, the machine transitions to `ENGAGED` and subsequent movements flow through.

Motor faders default to `IMMEDIATE` policy (they're driven to the logical value, so there's never a mismatch to chase). `policyFor(deviceTypeKey, controlId)` is: explicit `BindingTakeoverPolicy` from the binding → else device-class default.

### 4. Binding resolve

`ControlSurfaceBindingService.resolve(projectId, deviceTypeKey, controlId, activeBank) → ResolvedBinding?`. Returns null for unbound controls.

### 5. Dispatch via SurfaceActions

See the `BindingTarget variants` table above. `DefaultSurfaceActions` is a fire-and-forget port — it handles its own thread coordination:
- `DirectWriteStore` is lock-free (`ConcurrentHashMap`)
- Cue stack operations dispatch onto `GlobalScope` with `OptIn(DelicateCoroutinesApi)` — the router thread never blocks on show services
- Dependencies are resolved through `state.show.*` on every call, so `ProjectManager.switchProject` doesn't leak stale references

### Flash lifecycle

Press: `PropertyChannelResolver.resolveFixtureProperty` computes the channel list; for each channel, `DefaultSurfaceActions.buildFlashWrites` clamps to `minOf(max, sliderMax)` so a Flash at 255 respects a fixture's configured lamp cap (`DmxSlider.max`). Writes land in `DirectWriteStore` at the slider's native `max` and are pushed directly to the controller for immediate response.

Release: `DirectWriteStore.clearProperty` returns the cleared `ChannelWrite`s; we write 0 through the controllers so the immediate output reverts. On the next tick, the composition resolver re-evaluates — Layer 3 or Layer 5 takes over.

## Feedback path (SurfaceFeedbackPublisher)

Feedback has exactly **one observation point for composition**: a `FixturesChangeListener.channelsChanged` callback attached to every `ArtNetController` via `Fixtures.registerListener`. That single listener fires on every transmit tick where a channel changed. Because every composition path (Layer 1 parking, Layer 2 FX, Layer 3 cues, Layer 4 direct writes) converges at the transmit boundary, the feedback observer sees every change regardless of origin. This is a deliberate design choice — avoiding a tree of per-layer observers keeps feedback decoupled from *how* a value got there.

### Reverse index

The publisher maintains an `AtomicReference<Index>` holding:

- `byChannel: Map<Long /*packed (universe, channel)*/, List<ContinuousEntry>>` — lookup for channel-change feedback
- `ledAll: List<ButtonEntry>` — every button LED needing resync
- `flashLedIndex: Map<Int /*bindingId*/, ButtonEntry>` — flash LED by binding
- `blackoutLeds` / `grandMasterLeds: List<ButtonEntry>` — scaler indicators

The index is rebuilt on a swap in an `AtomicReference` on any of: device attach/detach, `BindingChange`, `BankChange`, `fixturesChanged`, project change. Rebuild is infrequent; reads from the hot-path transmit listener are lock-free.

### Continuous feedback

On `channelsChanged(universe, changes)`:

1. For each changed `(universe, channel)`, look up `byChannel[packChannelKey(universe.universe, channel)]`.
2. For each `ContinuousEntry`:
   - `computeValue7Bit(entry)` reads live DMX via `DmxController.getValue`, scales to 0..127 inverse of the binding's scale mapping.
   - Feed the new logical value into `SoftTakeoverStateMachine.setLogical(displayKey, controlId, value7Bit)` so pickup policy has an up-to-date target.
   - If the target is a motor fader and `TouchStateTracker.isTouched(displayKey, controlId) == true`, **skip the motor write** — the operator's finger is on it.
   - Otherwise send `ControlChangeFeedback(channel, motorCc, value7Bit)` (fader) or `ControlChangeFeedback(channel, ringCc, value7Bit)` (encoder ring).

### Full resync

Triggered on: device attach, bank change, project change. Iterates every binding in scope:

- Continuous → compute and send current value (subject to touch gate)
- Flash buttons → `flashTracker.isActive(id)` → NoteOn(127) or NoteOff(0)
- Blackout / GrandMaster → read `StateFlow.value` → LED reflects current state
- Soft-takeover → `forcePickup` on every PICKUP-policy fader (physical position is now stale)

The resync path reads *current state*, not edge events, so if a Flash is held at the moment a device attaches, the button LED lights up immediately — not only on the next press.

### Independent LED subscribers

Parallel coroutines collect from:

- `FlashStateTracker.changes` → flash LED on/off
- `GlobalScalerState.blackoutEnabled.combine(grandMasterEnabled)` → scaler LEDs
- `ActiveBankState.changes` → bank-button LEDs + trigger full resync for affected device
- `ControlSurfaceBindingService.bindingChanges` → rebuild index

All subscribers are cancelled together on `SurfaceFeedbackPublisher.stop()`.

## Interaction with the composition model

| Surface event | Writes to | Layer | Notes |
|---|---|---|---|
| Fader → FixtureProperty | `DirectWriteStore.putProperty` | **Layer 4** | Sticky; visible under running effects |
| Fader → GroupProperty | `DirectWriteStore.putGroupProperty` | **Layer 4** | Fanned to members |
| Flash press | `DirectWriteStore.put` (ephemeral) | **Layer 4** | Cleared on release; writes 0 immediately |
| Blackout toggle | `GlobalScalerState` (`TransmitModifier`) | **post-composition mask** | Only affects intensity categories |
| Grand Master toggle | Same as Blackout | **post-composition mask** | Binary in v1; continuous fader deferred |
| Bank-button press | `ActiveBankState.setBank` | *(no layer — routing state)* | Swaps the binding resolution axis |
| Cue stack buttons | `CueStackManager.*` | *(Layer 3 via cue apply)* | Same path as REST / UI |
| Fire cue | `CueStackManager.fireCue` | *(Layer 3)* | |

Phase 6 adds a **cueEdit-aware router branch**: when a cue-edit session is open for the project, continuous writes route to `cueEdit.setProperty` (Layer 3) instead of Layer 4. This matches the frontend `EditorContext` pattern — surfaces become another client of the same session. See [control-surface-plan.md](control-surface-plan.md) §Phase 6.

## Threading model

| Component | Thread | Sync |
|---|---|---|
| `KtMidiController` transmit loop | dedicated `MidiThread-$displayKey` | per-key conflated channels |
| `SurfaceInputRouter` per-device collector | `Dispatchers.Default` (named coroutine) | lock-free reads of `Index` |
| `SurfaceFeedbackPublisher` transmit listener | ArtNet transmit thread | `AtomicReference<Index>` swap |
| `SurfaceFeedbackPublisher` subscribers | `Dispatchers.Default` | StateFlow / SharedFlow |
| `ControlSurfaceBindingService` cache mutations | per-project `Mutex` via `lockFor(projectId)` | ConcurrentHashMap reads |
| `DirectWriteStore` | any thread | lock-free `ConcurrentHashMap<Long, UByte>` |
| Cue stack dispatch | `GlobalScope` (fire-and-forget) | service's own coordination |

The router thread never blocks on show services. Property writes to `DirectWriteStore` are lock-free. Cue stack operations and fire-cue dispatches land on `GlobalScope` so the router keeps draining MIDI events even during slow cue transitions.

## State wiring

From `State.kt`:

| Field | Lifecycle |
|---|---|
| `State.midiRegistry` | Lazy; started in `State.initializeShow()` after `show.start()` |
| `State.activeBankState` | Lazy; ephemeral, no persistence |
| `State.flashStateTracker` | Lazy; ephemeral |
| `Show.globalScalerState` | Per-show; `.attach()` called in `Show.start()` after fixture load; `.detach()` in `Show.close()` |
| `State.surfaceFeedbackPublisher` | Lazy; started in `initializeShow()` **before** `surfaceInputRouter` so hooks are ready when the first inbound event arrives |
| `State.surfaceInputRouter` | Lazy; constructed with `DefaultSurfaceActions(state)` (resolves show on every call, not once at construction); `surfaceInputRouter.start(GlobalScope)` in `initializeShow()` |

`State.onProjectChanged()` re-attaches the fixture listener and triggers a full resync for every attached device so the new show's composition state drives the motors.

## WebSocket surface

### Inbound

| Message | Payload |
|---|---|
| `surfaceDevices.state` | *(request current device list)* |
| `surfaceBank.set` | `{ deviceTypeKey, bank }` |
| `surfaceBank.state` | *(request active banks)* |
| `surfaceScaler.setBlackout` | `{ enabled }` |
| `surfaceScaler.setGrandMaster` | `{ enabled }` |
| `surfaceScaler.state` | *(request scaler state)* |
| `surfaceLearn.begin` | `{ sessionId, targetContext }` |
| `surfaceLearn.cancel` | `{ sessionId }` |
| `surfaceLearn.commit` | `{ sessionId, binding }` |

### Outbound

| Message | Payload |
|---|---|
| `surfaceDevices.state` | Aggregate of `midiRegistry.devices ∪ deviceMatcher.attached ∪ activeBankState.active` |
| `surfaceBank.state` | `{ banks: Map<deviceTypeKey, bank> }` |
| `surfaceBank.changed` | Single delta event |
| `surfaceScaler.state` | `{ blackout, grandMaster }` |
| `surfacePickup.changed` | `{ displayKey, controlId, state, target }` |
| `surfaceBindingsChanged` | Broadcast on every binding mutation |
| `surfaceLearn.captured` | Captured `ResolvedInput`; per-connection filtered via `ownedLearnSessions` |

## REST surface

| Endpoint | Purpose |
|---|---|
| `GET /api/rest/controlSurfaceTypes` | Device profile metadata for the frontend |
| `GET /api/rest/projects/{projectId}/surface-bindings` | List bindings |
| `POST /api/rest/projects/{projectId}/surface-bindings` | Create binding |
| `PATCH /api/rest/projects/{projectId}/surface-bindings/{id}` | Update (uses `FieldUpdate<T>` sentinel for nullable fields) |
| `DELETE /api/rest/projects/{projectId}/surface-bindings/{id}` | Delete |

## Known limitations

1. **`PropertyChannelResolver` is reflective and fixture-aware.** Bindings store `(fixtureKey, propertyName)`. If a fixture type is refactored (property renamed, mode changed), existing bindings silently fail to resolve at dispatch time. There is no compile-time or load-time validation today. See Open Question 9 in [control-surface-plan.md](control-surface-plan.md) — proposed fix (binding reconciliation diagnostic) is tracked as Phase 7 of that plan.

2. **`GlobalScalerState` is per-show, not per-project-in-library.** Blackout and Grand Master state is attached to the active `Show`. Project switches via `ProjectManager.switchProject` will reset this because `Show` is re-created. The session-level state across project switches is deliberately ephemeral — but this means "close and reopen project" doesn't preserve operator intent. See Phase 8 of [control-surface-plan.md](control-surface-plan.md).

3. **Soft-takeover only applies to faders.** Encoders always use `IMMEDIATE` policy. If an encoder's logical value drifts (e.g. a cue changed it), the next encoder turn snaps the hardware to the new value. This matches industry practice but is worth knowing.

4. **Enum / setting properties are button-only.** `PropertyChannelResolver` returns empty for `DmxFixtureSetting`. Continuous-to-enum mappings are disallowed at bind time (Open Question 7).

5. **No cross-session ownership for continuous controls.** Two `/surfaces` tabs can create conflicting bindings; the last-write-wins mutation happens under a per-project mutex, so atomicity is preserved, but there's no "this surface belongs to this operator" concept.

6. **Backpressure: input events can drop.** The `MutableSharedFlow` buffer is 256 with `DROP_OLDEST`. For normal use this is several seconds of headroom; under pathological input the router may miss events silently. There is no counter / alarm.

## Testing

- Test seams at every layer: `MidiSendTarget`, `MidiInputSource`, `MidiAccessSource`, `SurfaceActions`, `SurfaceFeedbackHooks` — all injectable.
- `KtMidiController.flushForTest()` drains the transmit loop deterministically without waiting on the real ticker.
- `GlobalScalerState.seedIntensityChannelsForTest(pairs)` stubs the channel classification without walking a real fixtures registry.
- `ControlSurfaceRegistry.buildFromClasses(classes)` is `internal` so tests can exercise validation paths without mutating the live class list.
- Phase-by-phase test counts (from Status): Phase 0 — 26, Phase 1 — 8 (matcher), Phase 2 — 12 (binding service + learn), Phase 3 — 45, Phase 4 — 32. **520 total** backend tests passing as of 2026-04-19.

## Not implemented (architecture accommodates)

- OSC transport
- Network MIDI / RTP-MIDI
- MSC (MIDI Show Control)
- USB HID / vendor protocols (Stream Deck, Loupedeck)
- Relative encoder encodings (Mackie, binary offset, two's complement)
- SysEx-driven LCD text
- Continuous Grand Master via a bound fader (v1 is binary toggle)
- Submasters
- Multi-operator concurrent surface editing

See [control-surface-plan.md §Out of scope](control-surface-plan.md#out-of-scope) for rationale.
