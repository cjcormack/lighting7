# MIDI Control Surface Engineering Documentation

This document describes the MIDI control-surface subsystem: how external hardware (faders,
encoders, buttons) drives the lighting composition model and how fixture state feeds back to
LEDs and motorised faders.

Related:
- Strategic plan (phases, decisions, open questions): [control-surface-plan.md](plans/completed/control-surface-plan.md).
- The selection-relative layer, strips and the surface view: [midi-surface-plan.md](plans/midi-surface-plan.md)
  (session 1 — the selection, the four selection targets, the mixed arm and the control-state
  stream — has landed; the rest is proposed).
- Composition layers surfaces write into: [lighting-composition-model.md](lighting-composition-model.md).
- Transport layer surfaces write through: [dmx-engineering.md](dmx-engineering.md).

## Overview

Control surfaces are *another client of the composition model* — not a parallel pipeline.
A fader bound to a fixture dimmer writes the **programmer** (Layer 2 — the same `ProgrammerStore` the web UI
`updateChannel` message uses). A button bound to cue-stack GO dispatches through the same
`CueStackManager` the REST API does. Blackout and Grand Master are applied as `TransmitModifier`s
on the ArtNet controller, alongside parking. There is no "surface mode" in the engine.

The implementation follows four separable concerns, mirroring the phase breakdown in
[control-surface-plan.md](plans/completed/control-surface-plan.md):

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
  │  KtmidiAccessSource (listener)  ─────▶  MidiMessageParser                │
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
  │         ├─ FixtureProperty / GroupProperty → ProgrammerStore (L2)        │
  │         ├─ SelectionProperty → ProgrammerStore, every selected head      │
  │         ├─ SelectTarget / ClearSelection → State.deskSelection           │
  │         ├─ LocateSelection → LocateManager.toggle, per selected target   │
  │         ├─ CueStackGo / Back / Pause / FireCue → CueStackManager         │
  │         │    (uuid first; a bare int only for a pre-v11 row)             │
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
  │    ├─ computeValue7Bit(entry): read live DMX on every channel of the     │
  │    │    entry → one common 0..127, or null when they disagree            │
  │    ├─ TouchStateTracker.isTouched(displayKey, controlId) → skip motor    │
  │    ├─ SoftTakeoverStateMachine.setLogical(…) / disarm(…) on null         │
  │    ├─ ControlStateTracker ← every send site (the surfaceControls stream) │
  │    └─ sendContinuousFeedback → MidiFeedbackMessage (ring off on null)    │
  │                                                                          │
  │  Parallel subscribers feed other LEDs:                                   │
  │    FlashStateTracker.changes → flash LEDs                                │
  │    GlobalScalerState.blackoutEnabled + grandMasterEnabled → toggle LEDs  │
  │    DeskSelection.targets → rebuild + resync (select LEDs, selection      │
  │      entries' channel sets)                                              │
  │    LocateManager.activeTargets → locate LEDs                             │
  │    ActiveBankState.changes → bank-button LEDs + full resync              │
  │    ControlSurfaceBindingService.bindingChanges → rebuild + resync        │
  │                                                                          │
  │    ▼                                                                     │
  │  KtMidiController.sendFeedback                                           │
  │    ├─ per-MidiControlKey conflated Channel                               │
  │    ├─ 60 Hz transmit loop on dedicated thread                            │
  │    └─ delta suppression via lastSentBytes                                │
  │    ▼                                                                     │
  │  KtmidiAccessSource.KtMidiSendTarget.send → native libremidi             │
  └─────────────────────────────────────────────────────────────────────────┘
```

## Package Layout

All code lives under `src/main/kotlin/uk/me/cormack/lighting7/midi/`.

### Transport

| File | Purpose |
|---|---|
| `MidiController.kt` | Transport interface (relaxed from `sealed` — see Phase 4 change log in plan). Exposes `input: SharedFlow<MidiInputEvent>`, `sendFeedback(MidiFeedbackMessage)`, `close()`. Test-seam interfaces: `MidiSendTarget`, `MidiInputSource`, `MidiAccessSource`. |
| `KtMidiController.kt` | ktmidi-backed implementation. Dedicated `newSingleThreadContext("MidiThread-${displayKey}")`; per-`MidiControlKey` conflated channels; 60 Hz transmission loop with delta suppression. |
| `KtmidiAccessSource.kt` | Wraps a ktmidi `MidiAccess` into the `MidiAccessSource` abstraction. `createPlatformKtmidiAccess()` picks `LibreMidiAccess` (native libremidi via Panama FFM) on macOS / Linux and falls back to `JvmMidiAccess` (`javax.sound.midi`) on Windows, where libremidi-panama lacks an arm64 binary and its x64 binary trips an LLP64 ABI bug. |
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
| `BindingTarget.kt` | Sealed ADT of what a control drives: `FixtureProperty`, `GroupProperty`, `CueStackGo` / `Back` / `Pause`, `FireCue` (each with a uuid beside the int), `Flash(target, max)`, `Blackout`, `GrandMasterToggle`, `SetBank`, `SpeedMasterBpm` / `Tap`, the selection family `SelectionProperty`, `SelectTarget`, `ClearSelection`, `LocateSelection`, and `Unknown` — the tolerant decode's placeholder, never written by a client. Serialized as JSON with `classDiscriminator = "type"` and `ignoreUnknownKeys = true`. Persisted as text in `DaoControlSurfaceBindings.targetPayload`. |
| `ControlSurfaceBindingService.kt` | Binding CRUD + in-memory resolver cache keyed by `(projectId, deviceTypeKey, controlId, bank)`; exact-bank wins over global. Decodes **per row**: an undecodable payload becomes `BindingTarget.Unknown` (health `unknownTarget`, re-written verbatim) rather than failing the project. Fills `cueUuid` / `stackUuid` on create and update. Emits `BindingChange` events; broadcast via `surfaceBank.bindingsChanged`. |
| `SelectionWrites.kt` | The pure fan-out behind `SelectionProperty`: one programmer write per selected head, a group's members tagged with `sourceGroup`, heads without the property skipped, an empty selection → no writes. |
| `MidiLearnSessionManager.kt` | 30-second Learn sessions; captures the first matching physical input, holds the captured descriptor until the originating client commits or cancels. Scoped to the originating client via `ownedLearnSessions` so two `/surfaces` tabs don't cross-capture. |

### Inbound routing

| File | Purpose |
|---|---|
| `SurfaceInputRouter.kt` | Per-device `CoroutineName("SurfaceRouter-$displayKey")` collector. Pipeline: `matchEvent → soft-takeover gate → binding resolve → SurfaceActions dispatch`. |
| `SurfaceActions.kt` | Port interface between router and show services. Production: `DefaultSurfaceActions` resolves `state.show.*` on every call so project switches route cleanly, reads `state.deskSelection` for the selection arms, and resolves a cue / stack uuid to the current project's row before calling `CueStackManager`. Tests: `RecordingActions`. |
| `PropertyChannelResolver.kt` | `object` for **MIDI surface input only**: takes a 7-bit MIDI value and produces `List<ChannelWrite>`. Sliders scale to each channel's native `min..max`; Colour fans the 7-bit value to R/G/B; Settings return empty (enum bindings are button-only — Open Question 7). For property-value → channel resolution elsewhere (preset toggles, locate, programmer publishes), see `fx/PropertyChannelWriter` which accepts full-range `CueAssignmentResolver.PropertyValue` variants and handles Colour + Position without MIDI-7bit scaling. |
| `ActiveBankState.kt` | Ephemeral `deviceTypeKey → bank` map backed by a `ConcurrentHashMap` fast-lookup plus a `changes: SharedFlow<BankChange>` for WS broadcast. Not persisted. |
| `FlashStateTracker.kt` | Lock-free `Set<Int>` of currently-held binding IDs (overlapping presses don't clobber release semantics). Exposes `changes: SharedFlow<FlashChange>`. |
| `GlobalScalerState.kt` | Show-scoped `TransmitModifier` implementation of Blackout + Grand Master. Walks fixtures on `fixturesChanged` to classify intensity-category channels into a packed-`Long` `AtomicReference<Set<Long>>` for allocation-free hot-path lookup. A toggle only flips the modifier's own state — output is a continuous stream, so the next frame of each universe carries it (see [dmx-engineering.md §Change latency](dmx-engineering.md#change-latency)). The `blackoutEnabled` / `grandMasterEnabled` flags read through to a project-scoped `GlobalScalerStateHolder` so state survives project switches within a session. |
| `GlobalScalerStateHolder.kt` | Per-project holder for the `MutableStateFlow<Boolean>` backing Blackout and Grand Master. Lives on `State.scalerHolderFor(projectId)`, outside the `Show` lifecycle; a fresh `GlobalScalerState` facade is constructed against the same holder on every project activation. Phase 9 of [control-surface-plan.md](plans/completed/control-surface-plan.md) — option A (ephemeral per-session); DB persistence deferred. |

### Feedback and reconciliation

| File | Purpose |
|---|---|
| `SurfaceFeedbackPublisher.kt` | Owns a reverse `packedChannelKey → List<ContinuousEntry>` index plus parallel LED lists (flash, blackout, grand master, select, locate). An entry may stand on several channels (a group's members, the selected heads) and reads null when they disagree — §"Continuous feedback". Rebuilt atomically in an `AtomicReference` on any of: device attach/detach, binding change, bank change, fixture registration change, project change, selection change. Defines `SurfaceFeedbackHooks` (a small port the router calls before binding resolution — `acceptInboundFader`, `onTouch`). Writes `ControlStateTracker` at every send site. |
| `ControlStateTracker.kt` | The store behind `surfaceControls.state` / `.changed`: per `(displayKey, controlId)` `ControlState(value?, physical?, touched, led, ring)`, lock-free, conflated to one delta per device per 50 ms; a whole-device snapshot after every full resync. §"Control-state stream". |
| `SoftTakeoverStateMachine.kt` | Per-`(displayKey, controlId)` `Entry(state, lastPhysical, target)`. PICKUP state blocks inbound until the fader crosses the target value (supports both from-below and from-above crossing; ±1 jitter tolerance). IMMEDIATE state passes through. `disarm` drops the target (ENGAGED, no target) for a control with no uniform value to arm against. Broadcasts `PickupStateChange` transitions. |
| `TouchStateTracker.kt` | Per-`(displayKey, controlId)` touched flag. While `true`, `SurfaceFeedbackPublisher` suppresses motor writes to that control (don't fight the user's finger). |

## Transport (KtMidiController)

Concurrency shape deliberately mirrors [ArtNetController](dmx-engineering.md):

- **One dedicated single-thread context per device** — `newSingleThreadContext("MidiThread-${handle.displayKey}")`. All outbound sends happen on this thread.
- **Per-`MidiControlKey` conflated Kotlin channels** — last-write-wins. Rapid updates to the same LED coalesce.
- **Conflated `pendingSignal`** acts as the "there's work" flag; idle ticks do no work.
- **60 Hz transmit loop** (`DEFAULT_TRANSMIT_INTERVAL_MS = 17L`) — slightly faster than the ArtNet loop's 25 ms default to keep LED response crisp. Independent of it: the ArtNet interval is now per-universe and configurable, so this is a fixed choice for surface feel rather than a value derived from the DMX rate.
- **Delta suppression** — `lastSentBytes[key]` compared byte-for-byte before `MidiSendTarget.send`.
- **Consecutive-error backoff** matching ArtNet semantics: first error prints stack trace; > 20 consecutive errors breaks the loop.

### Input path

`KtmidiAccessSource` installs a raw-bytes listener. `MidiMessageParser` handles channel-voice decoding, running status, multi-packet SysEx accumulation, and swallows system real-time / system common interleaves. Parsed events go to a `MutableSharedFlow<MidiInputEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)` — there is no backpressure at the transport layer. If a consumer can't keep up, oldest events are dropped. For a 60 Hz fader at full tilt that's ~60 CC/sec, well within the buffer.

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

The `ControlSurfaceRegistry` is strictly stricter than `FixtureTypeRegistry` — it fails fast on duplicate `typeKey` *and* on duplicate `controlId` within a class, plus the strip and layout rules below. Rationale: `controlId` is a stable contract persisted in binding rows, so a duplicate would silently break persisted bindings.

A profile may also declare **strips** (see [Strips](#strips)) and a **layout**: named regions (`strips`, `right`, `master` on the X-Touch) with a `(region, col, row)` cell per control, so the Surfaces view draws any profile that has one from data rather than needing a React component per device. A profile without a layout renders as the grouped table. When a layout *is* declared the registry requires it to be complete — every declared control gets exactly one cell — because a control with no cell would simply vanish from the picture with nothing else reporting it. Both reach the frontend on `GET /api/rest/control-surface-types`.

### DeviceMatcher

`DeviceMatcher` subscribes to `MidiDeviceRegistry.events` and matches each attached `MidiDeviceHandle` against registered profiles by running `portPattern.matches(displayName)`. Emits:

- `DeviceAttached(handle, profile)` — device matched a registered `@ControlSurfaceType`
- `DeviceDetached(displayKey)` — device unplugged
- `UnmatchedDeviceConnected(handle)` — device with no matching profile; MIDI Learn can still bind it via its generic CC / note scheme

Every inbound `Connected` event yields exactly one `SurfaceEvent` — the matcher is the single arbiter of "is this device one we know about?".

## Strips

Fixed per-control bindings run out fast: nine faders and sixteen encoders is not many, and every console the composition model measures itself against solves that the same way — a **channel strip**. The profile declares which controls form one column, and a *single* binding row addressed by the strip id then covers all of them.

```kotlin
repeat(8) { i ->
    strip(id = "strip-${i + 1}", fader = "fader-${i + 1}", select = "btn-${25 + i}",
          encoder = "enc-${i + 1}", flash = "btn-${i + 1}")
}
strip(id = "strip-master", fader = "fader-9", select = "btn-33")   // no encoder
```

A strip id lives in the **same `control_id` column** as a control id — no new table, no new column — so the two share one slot namespace and the registry refuses a collision. `buildFromClasses` also fails fast on a duplicate strip id, a strip naming an undeclared control, a role naming a control of the wrong kind (fader→`FaderDescriptor`, select/flash→`ButtonDescriptor`, encoder→`EncoderDescriptor`), and a control claimed by two strips. Same reasoning as the duplicate-`controlId` check: these ids are a persisted contract.

**Derivation** is the pure function `deriveStripTarget(role, target, encoderBankProperty)` in `midi/StripDerivation.kt`:

| Role | Behaves as |
|---|---|
| fader | `GroupProperty(key, "dimmer")` / `FixtureProperty(key, "dimmer")` |
| select | `SelectTarget(target, TOGGLE)` |
| encoder | the same property target on the device's current [encoder bank](#encoder-bank) |
| flash | `Flash(<the dimmer target>, 255)` |

One function, because the input router, the feedback index and the frontend inspector all have to agree about what a strip control does.

**Resolution order** — `ControlSurfaceBindingService.resolve` answers the control's own binding *across both bank levels* before it looks at the strip, then the strip across both:

1. the control's row for the exact bank
2. the control's bank-agnostic row
3. the strip's row for the exact bank
4. the strip's bank-agnostic row

Direct before strip at **both** levels is the load-bearing part: a bank-agnostic single binding beats an exact-bank strip, which is what makes "any control can still be bound on its own" true regardless of banks. `resolve` is the only resolution entry point — router, feedback index and takeover-policy lookup all come through it — so the strip arm needs no second implementation.

A strip hit is answered as the strip's row with the control's id and the derived target, so the four derived bindings share the strip row's `id`, `takeoverPolicy` and `health`. Health is deliberately **not** re-evaluated per derived target: an encoder whose bank property no selected head declares reads unbound and drops its turn, which is a property of the selection rather than a dead binding.

`BindingTarget.Strip` is stored but never dispatched, and the rest of the strip code takes that as given — a raw `Strip` reaching dispatch is a control that silently does nothing, and health cannot catch it because a `Strip`'s health only judges its group. So the slot rule is enforced **in `ControlSurfaceBindingService`**, beside `refuseUnknown`, on `create` / `update` / `replace`: that is the one door every write goes through, including MIDI Learn's commit, which has no route validation of its own. `POST` / `PATCH /surface-bindings` run their own copy first only so the frontend gets a coded 400 to branch on — `BINDING_STRIP_NEEDS_STRIP` for a `Strip` on a control id, `BINDING_CONTROL_NOT_STRIP` for anything else on a strip id.

`POST .../{bindingId}/expand` turns a strip row into the four single rows it was deriving (the inspector's *Fader only…*) in one transaction via `ControlSurfaceBindingService.replace`.

## Encoder bank

Which attribute a device's strip encoders drive is per-device session state: `EncoderBankState`, shaped exactly like `ActiveBankState` with a property name where that has a bank id — one map-valued `StateFlow` for the snapshot, a lock-free `propertyFor()` for the hot path, a `changes` `SharedFlow` for the publisher. Default `dimmer`.

It moves two ways: a button bound to `EncoderBankSet(propertyName)`, which applies to **the device the button is on** (the payload names no device, so a bank button can never be stranded pointing at a surface that is not attached), or the frontend's `surfaceEncoderBank.set`.

It survives a device unplug and is **reset on project switch** — unlike the active bank, which does not. The vocabulary it draws from is the patch's (`BindingHealthEvaluator.selectionPropertiesOf`, the same set `SelectionProperty` validates against), so carrying a property across projects could point every encoder at an attribute the new rig has no fixture for.

A change rebuilds the feedback index — every strip encoder now stands on different channels — and re-arms takeover on that type's devices through the same path a bank change uses, because the meaning of the control moved under the operator's hand without them touching it.

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
| `FixtureProperty(fixtureKey, propertyName)` | Continuous | Programmer write via `ProgrammerWriter.writeProperty` (owner `surface`); value typed through `PropertyChannelResolver.toPropertyValue` |
| `GroupProperty(groupName, propertyName)` | Continuous | Same, fanned out across group members |
| `CueStackGo(stackId)` | Button | `CueStackManager.activateAtFirstCue` or `advanceStack(FORWARD)` on press |
| `CueStackBack(stackId)` | Button | `CueStackManager.advanceStack(BACKWARD)` |
| `CueStackPause(stackId)` | Button | `CueStackManager.pauseAutoAdvance(stackId)` |
| `FireCue(cueId)` | Button | `CueStackManager.fireCue(cueId)` |
| `Flash(target, max)` | Button (momentary) | Press: programmer write (owner `flash`) at `minOf(max, sliderMax)`. Release: clear the `flash` slot — the property cascades to the surviving owner / cue layer / baseline in one publish |
| `Blackout` | Button | `GlobalScalerState.toggleBlackout()` |
| `GrandMasterToggle` | Button | `GlobalScalerState.toggleGrandMaster()` |
| `SetBank(deviceTypeKey, bank)` | Button / bank-button | `ActiveBankState.setBank(deviceTypeKey, bank)` |
| `SpeedMasterBpm(masterUuid?, minBpm, maxBpm)` | Continuous | `SpeedMasterBank.setBpm` across the binding's window |
| `SpeedMasterTap(masterUuid?)` | Button | `SpeedMasterBank.tap` |
| `SelectionProperty(propertyName)` | Continuous (press = full) | Programmer write (owner `surface`) on **every head in the desk selection** via `SelectionWrites`; an empty selection drops the write with a debug log, never widened to "everything". Health `unknownProperty` when no patched fixture can take the property on a fader |
| `SelectTarget(target, mode)` | Button | `DeskSelection.toggle` (head-by-head, a group by its members) or `set(listOf(target))` for `REPLACE`; LED lit while the target's heads are all selected. A `TOGGLE` binding toggles on press **and**, if the button is still held at `SurfaceInputRouter.SELECT_HOLD_MS` (500 ms), fires a `REPLACE` as well — the fader-wing gesture, so the LED is immediate and a hold narrows the selection to that one target. A release always cancels the pending replacement, even if the binding changed or went dead meanwhile. A `REPLACE` binding acts on press and arms no hold |
| `ClearSelection` | Button | `DeskSelection.clear()` |
| `LocateSelection` | Button | `toggleLocate` (the `POST /locate/toggle` path) once per selected target: all located → all released, else the unlocated ones come up; LED lit while the selection is non-empty and every target is located |
| `Strip(target)` | — (a strip slot) | Never dispatched: `resolve` derives it to the target of the control the event arrived on. See [Strips](#strips) |
| `EncoderBankSet(propertyName)` | Button | `EncoderBankState.setProperty` for the device the button is on; LED lit while that property is the device's encoder bank. Health `unknownProperty` when no patched fixture can take it |
| `Unknown(targetType, rawPayload)` | — | Never dispatched: health `unknownTarget` gates it. Produced only by the tolerant row decode for a `type` this build does not know; re-encoded verbatim; refused by the create / PATCH routes |

The cue and stack variants carry a **uuid beside the int** (`FireCue(cueId, cueUuid?)`,
`CueStackGo(stackId, stackUuid?)` …). The service fills it on every create and update — the REST
picker and MIDI Learn still address rows by int — and both `BindingHealthEvaluator` and
`DefaultSurfaceActions` judge a row **by the uuid alone when one is present**: a uuid that
resolves to nothing in the current project is dead, and the press is dropped rather than falling
back to an int that may name another project's row. Only a pre-v11 row with no uuid is dispatched
by its int. This is what makes a cloned or imported project keep its cue bindings
(`docs/sync-engineering.md` §"Version 11"; `FU-SYNC-BINDING-PAYLOAD-UUIDS`, first half).

**Related non-MIDI path.** Phase 7 of `docs/plans/completed/cue-authoring-unification-plan.md` adds a
separate FX-layer resolver, `fx/PropertyChannelWriter`, for property-value → channel
resolution without 7-bit MIDI scaling. The surface-input path above keeps using
`PropertyChannelResolver` for 7-bit input; programmer writes originating elsewhere
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
- `ProgrammerStore` is lock-free (`ConcurrentHashMap`)
- Cue stack operations dispatch onto `GlobalScope` with `OptIn(DelicateCoroutinesApi)` — the router thread never blocks on show services
- Dependencies are resolved through `state.show.*` on every call, so `ProjectManager.switchProject` doesn't leak stale references

### Flash lifecycle

Press: `PropertyChannelResolver.flashPropertyValue` types the press value, clamping to `minOf(max, sliderMax)` so a Flash at 255 respects a fixture's configured lamp cap (`DmxSlider.max`). The write lands as a programmer slot (owner `flash`) and the engine publishes the composed cascade — store and wire can no longer disagree.

Release: `ProgrammerWriter.clearProperty` pops the `flash` slot and publishes the composed cascade in one transaction — the surviving owner, the cue layer, or baseline takes over immediately.

## Feedback path (SurfaceFeedbackPublisher)

Feedback has exactly **one observation point for composition**: a `FixturesChangeListener.channelsChanged` callback attached to every `ArtNetController` via `Fixtures.registerListener`. That single listener fires on every transmit tick where a channel changed. Because every composition path (Layer 1 parking, Layer 2 programmer, Layer 3 FX, Layer 4 cues) converges at the transmit boundary, the feedback observer sees every change regardless of origin. This is a deliberate design choice — avoiding a tree of per-layer observers keeps feedback decoupled from *how* a value got there.

### Reverse index

The publisher maintains an `AtomicReference<Index>` holding:

- `byChannel: Map<Long /*packed (universe, channel)*/, List<ContinuousEntry>>` — lookup for channel-change feedback
- `ledAll: List<ButtonEntry>` — every button LED needing resync
- `flashLedIndex: Map<Int /*bindingId*/, ButtonEntry>` — flash LED by binding
- `blackoutLeds` / `grandMasterLeds: List<ButtonEntry>` — scaler indicators

The index is rebuilt on a swap in an `AtomicReference` on any of: device attach/detach, `BindingChange`, `BankChange`, `fixturesChanged`, project change. Rebuild is infrequent; reads from the hot-path transmit listener are lock-free.

### Continuous feedback

A `ContinuousEntry` stands on **one or more channels**: one for a fixture slider (the red axis for
a colour, symmetric with the write path that fans one value to all three), one per member for a
`GroupProperty`, one per selected head for a `SelectionProperty` — and none for a selection
binding with nothing selected, which is an entry that exists so the control reads "no selection"
rather than "unbound". The entry is indexed under every channel it stands on.

On `channelsChanged(universe, changes)`:

1. For each changed `(universe, channel)`, look up `byChannel[packChannelKey(universe.universe, channel)]`.
2. For each `ContinuousEntry` (a multi-channel entry once per tick, however many of its channels moved):
   - `computeValue7Bit(entry)` reads live DMX on every channel via `DmxController.getValue`, scales each
     through its own `min..max` to 0..127, and returns the **common value — or null when they disagree**.
   - Non-null: feed it into `SoftTakeoverStateMachine.setLogical(displayKey, controlId, value7Bit)` so
     pickup policy has an up-to-date target; if the target is a motor fader and
     `TouchStateTracker.isTouched(displayKey, controlId) == true`, **skip the motor write** — the
     operator's finger is on it; otherwise send `ControlChangeFeedback(channel, motorCc, value7Bit)`
     (fader) or `ControlChangeFeedback(channel, ringCc, value7Bit)` (encoder ring).
   - **Null — the mixed arm** (`docs/plans/midi-surface-plan.md` D10): nothing to the motor, the
     encoder ring driven to its off byte (`ringOffValue(style)`, `0` for every style until the rig
     says otherwise — §9 check 3), `SoftTakeoverStateMachine.disarm` so the next move writes through
     rather than waiting for a crossing that has nothing to cross, and the tracker reads
     `value: null, ring: off`. A turn or move writes every head, and the next tick reads uniform.

### Full resync

Two flavours. **With pickup re-armed** — device attach, bank change, project change, where the
physical position is stale: `forcePickup` on every PICKUP-policy fader. **Without** — a binding,
fixture, selection or master-list change (`rebuildAndResync`): the operator's fader has not moved,
so takeover follows `setLogical`'s own divergence rule. Both iterate every binding in scope:

- Continuous → compute and send the current value (subject to touch gate), or the mixed arm
- Flash buttons → `flashTracker.isActive(id)` → NoteOn(127) or NoteOff(0)
- Blackout / GrandMaster → read `StateFlow.value` → LED reflects current state
- Select buttons → `deskSelection.covers(target)`; Locate → every selected target located
- Then the device's whole `ControlStateTracker` snapshot is published (§"Control-state stream")

The resync path reads *current state*, not edge events, so if a Flash is held at the moment a device attaches, the button LED lights up immediately — not only on the next press.

### Independent LED subscribers

Parallel coroutines collect from:

- `FlashStateTracker.changes` → flash LED on/off
- `GlobalScalerState.blackoutEnabled.combine(grandMasterEnabled)` → scaler LEDs
- `ActiveBankState.changes` → bank-button LEDs + trigger full resync for affected device
- `ControlSurfaceBindingService.bindingChanges` → rebuild index + resync every device
- `DeskSelection.targets` → rebuild index (selection entries' channel sets) + resync every device
  (select LEDs, selection faders)
- `LocateManager.activeTargets` (per show, re-armed on project switch like the scaler) → locate LEDs

All subscribers are cancelled together on `SurfaceFeedbackPublisher.stop()`.

## Interaction with the composition model

| Surface event | Writes to | Layer | Notes |
|---|---|---|---|
| Fader → FixtureProperty | `ProgrammerWriter.writeProperty` | **Layer 2 (programmer)** | Sticky; wins over cues and suppresses effects on the property |
| Fader → GroupProperty | `ProgrammerWriter.writeProperties` | **Layer 2 (programmer)** | Fanned to members, slots tagged with the source group |
| Flash press | `ProgrammerWriter.writeProperty` (owner `flash`) | **Layer 2 (programmer)** | Cleared on release; cascade republished |
| Blackout toggle | `GlobalScalerState` (`TransmitModifier`) | **post-composition mask** | Only affects intensity categories |
| Grand Master toggle | Same as Blackout | **post-composition mask** | Binary in v1; continuous fader deferred |
| Bank-button press | `ActiveBankState.setBank` | *(no layer — routing state)* | Swaps the binding resolution axis |
| Cue stack buttons | `CueStackManager.*` | *(Layer 4 via cue apply)* | Same path as REST / UI; uuid resolved to the project's row first |
| Fire cue | `CueStackManager.fireCue` | *(Layer 4)* | |
| Fader → SelectionProperty | `ProgrammerWriter.writeProperties` (owner `surface`) | **Layer 2 (programmer)** | One write per selected head, a group's members tagged with `sourceGroup` — the same slot a fixed fader uses, so releasing a flash reveals it the same way |
| Select / Clear button | `State.deskSelection` | *(no layer — the desk's selection)* | What the next selection-relative move acts on |
| Locate button | `LocateManager.toggle` (owner `locate`) | **Layer 2 (programmer)** | Per selected target, the `POST /locate/toggle` path |

**A fader always writes the programmer.** Phase 6 added a second destination — when a cue-edit session was open for the project, `DefaultSurfaceActions.writeFixtureProperty` / `writeGroupProperty` routed to `cueEdit.setProperty` (the cue layer) instead, and `SurfaceFeedbackPublisher` drove the motor from the cue's Layer 4 value rather than the stage. Backend sweep item D1 retired the `cueEdit.*` family, so both halves are gone: one write destination, and feedback that always means the live composed DMX value. See [control-surface-plan.md](plans/completed/control-surface-plan.md) §Phase 6 for what the session-routing design was.

## Selection

`state/DeskSelection.kt` is the desk's one selection — a `StateFlow<List<CueTargetDto>>` on
`State`, beside `activeBankState`, so it outlives a `Show`. **One desk, one selection,
server-owned**: the composition model's one-programmer argument applies verbatim
(`docs/lighting-composition-model.md` §"Layer 2"), and every client — the busk view, the
programmer page, an X-Touch's select buttons, the AI's `get_current_state` — reads and writes this
one list through the `selection.*` socket family (`docs/websocket-engineering.md`). Transient:
cleared on project switch (before the publisher rebuilds, so a stale head never enters the new
index), pruned on `fixturesChanged` / `patchListChanged` of any target that no longer resolves
(the rest stay, the rule a press applies), never persisted.

**A group and its members are two spellings of one selection.** `fx/TargetCoverage.kt` is the one
expansion rule, lifted out of `ProgrammerLayerStack` so a select button and a busk pad agree:
`covers(target)` is true when every head the target expands to is selected, and `toggle` on a
covered target takes those heads off head-by-head — an entry the press does not touch keeps its
spelling, a group it only partly covers is respelt as the members left behind. An unresolvable
group expands to itself, so a stale entry stays comparable rather than vanishing.

The surface reaches it through three targets (`SelectionProperty`, `SelectTarget`,
`ClearSelection`) plus `LocateSelection`; the router stays selection-blind — every arm calls a
`SurfaceActions` method and `DefaultSurfaceActions` reads `state.deskSelection`, which is what
keeps `RecordingActions` a complete test double.

## Control-state stream

`midi/ControlStateTracker.kt` holds, per `(displayKey, controlId)`, what the hardware has been
told: `ControlState(value?, physical?, touched, led, ring)`. `value` is the fed-back 7-bit
position and null for mixed, unbound or no selection; `physical` is the last inbound fader
position (recorded in `acceptInboundFader` whether or not the move was accepted), which a
non-motor fader's operator wants to see beside its pickup target because the hardware is never
told it; `led` and `ring` are `ON | OFF | NONE`, `NONE` meaning the control has no LED- or
ring-bearing binding.

The rule that makes it trustworthy: **the publisher writes the tracker at its send sites, before
any early return.** `sendControlFeedback` records the value first, then bails for a non-motor
fader or a touched motor; `sendLed` records before it sends; `onTouch` records the flag. So the
screen sees what a non-motor fader *would* have been told and what a touched motor was spared,
and never recomputes a control from DMX — if the picture and the desk disagree, the publisher is
wrong, which is the bug worth finding (`docs/plans/midi-surface-plan.md` D7).

Writes come from the ArtNet transmit thread, the router coroutine and the publisher's subscribers,
so the store is lock-free (`ConcurrentHashMap.compute`). Changes are conflated per device: a write
marks the control dirty and wakes a flusher that waits 50 ms and emits one `Delta` per dirty device
carrying each dirty control's *current* state. A `Snapshot` is the whole device — `sendFullResync`
seeds every profile control at its unbound default (keeping `touched` and `physical`, which are
facts about the hardware), drives the device, then publishes it — so attach, bank change, project
change and every rebuild-and-resync each yield one `surfaceControls.state`. A detach publishes an
empty snapshot. `SurfaceSocket` sends the connect burst from the tracker's store with an explicit
`sendSnapshot` (the deltas are a replay-0 stream), then relays snapshots and deltas.

## Threading model

| Component | Thread | Sync |
|---|---|---|
| `KtMidiController` transmit loop | dedicated `MidiThread-$displayKey` | per-key conflated channels |
| `SurfaceInputRouter` per-device collector | `Dispatchers.Default` (named coroutine) | lock-free reads of `Index` |
| `SurfaceFeedbackPublisher` transmit listener | ArtNet transmit thread | `AtomicReference<Index>` swap |
| `SurfaceFeedbackPublisher` subscribers | `Dispatchers.Default` | StateFlow / SharedFlow |
| `ControlSurfaceBindingService` cache mutations | per-project `Mutex` via `lockFor(projectId)` | ConcurrentHashMap reads |
| `ProgrammerStore` | any thread | lock-free `ConcurrentHashMap` slot stacks |
| Cue stack dispatch | `GlobalScope` (fire-and-forget) | service's own coordination |

The router thread never blocks on show services. Programmer store mutations are lock-free (the engine's publish serialises on its own lock). Cue stack operations and fire-cue dispatches land on `GlobalScope` so the router keeps draining MIDI events even during slow cue transitions.

## State wiring

From `State.kt`:

| Field | Lifecycle |
|---|---|
| `State.midiRegistry` | Lazy; started in `State.initializeShow()` after `show.start()` |
| `State.activeBankState` | Lazy; ephemeral, no persistence |
| `State.deskSelection` | Lazy; ephemeral — cleared on project switch, pruned on fixture reload, no persistence |
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
| `surfaceEncoderBank.set` | `{ deviceTypeKey, propertyName }` |
| `surfaceEncoderBank.state` | *(request encoder banks)* |
| `surfaceScaler.setBlackout` | `{ enabled }` |
| `surfaceScaler.setGrandMaster` | `{ enabled }` |
| `surfaceScaler.state` | *(request scaler state)* |
| `surfaceLearn.begin` | `{ sessionId, targetContext }` |
| `surfaceLearn.cancel` | `{ sessionId }` |
| `surfaceLearn.commit` | `{ sessionId, bank?, target, takeoverPolicy? }` |
| `selection.set` / `.toggle` / `.clear` | `{ targets }` / `{ target }` / — (`SelectionSocket.kt`) |

### Outbound

| Message | Payload |
|---|---|
| `surfaceDevices.state` | Aggregate of `midiRegistry.devices ∪ deviceMatcher.attached ∪ activeBankState.active` |
| `surfaceBank.state` | `{ banks: Map<deviceTypeKey, bank> }` |
| `surfaceBank.changed` | Single delta event |
| `surfaceEncoderBank.state` | `{ properties: Map<deviceTypeKey, propertyName> }` — one frame type: it is a StateFlow, so the subscription is snapshot and broadcast both, and there is no delta frame |
| `surfaceScaler.state` | `{ blackout, grandMaster }` |
| `surfacePickup.changed` | `{ displayKey, controlId, state, target }` |
| `surfaceBank.bindingsChanged` | Broadcast on every binding mutation |
| `surfaceLearn.captured` | Captured `ResolvedInput`; per-connection filtered via `ownedLearnSessions` |
| `surfaceControls.state` / `.changed` | `{ displayKey, controls: {controlId: ControlState} }` — whole device / conflated delta |
| `selection.state` | `{ targets }` — connect snapshot + broadcast (`SelectionSocket.kt`) |

## REST surface

| Endpoint | Purpose |
|---|---|
| `GET /api/rest/control-surface-types` | Device profile metadata for the frontend |
| `GET /api/rest/projects/{projectId}/surface-bindings` | List bindings |
| `POST /api/rest/projects/{projectId}/surface-bindings` | Create binding |
| `PATCH /api/rest/projects/{projectId}/surface-bindings/{id}` | Update (uses `FieldUpdate<T>` sentinel for nullable fields). Shape-validated against the row it *becomes*, the same check `POST` runs |
| `DELETE /api/rest/projects/{projectId}/surface-bindings/{id}` | Delete |
| `POST /api/rest/projects/{projectId}/surface-bindings/{id}/expand` | Replace a strip binding with the individual bindings it was deriving (*Fader only…*), in one transaction |

## Known limitations

1. **`PropertyChannelResolver` is reflective and fixture-aware.** Bindings store `(fixtureKey, propertyName)`. If a fixture type is refactored (property renamed, mode changed), existing bindings silently fail to resolve at dispatch time. There is no compile-time or load-time validation today. See Open Question 9 in [control-surface-plan.md](plans/completed/control-surface-plan.md) — proposed fix (binding reconciliation diagnostic) is tracked as Phase 7 of that plan.

2. **Blackout / Grand Master state is in-memory per project.** State survives show tear-down on project switch (the `GlobalScalerStateHolder` lives on `State`, keyed by `projectId`), so toggling Blackout on project A → switching to B → switching back to A keeps Blackout on. Cross-restart persistence is **not** implemented in v1 — a backend restart resets every project's scalers to default. If operators need restart persistence, add a `project_scaler_state` DB table (option B of Phase 9 in [control-surface-plan.md](plans/completed/control-surface-plan.md)).

3. **Soft-takeover only applies to faders.** Encoders always use `IMMEDIATE` policy. If an encoder's logical value drifts (e.g. a cue changed it), the next encoder turn snaps the hardware to the new value. This matches industry practice but is worth knowing.

4. **Enum / setting properties are button-only.** `PropertyChannelResolver` returns empty for `DmxFixtureSetting`. Continuous-to-enum mappings are disallowed at bind time (Open Question 7). Bind-time refusals about strips carry a machine-readable `code` (`BINDING_STRIP_NEEDS_STRIP`, `BINDING_CONTROL_NOT_STRIP`); the older shape errors on this route still answer 400 with a message alone.

5. **No cross-session ownership for continuous controls.** Two `/surfaces` tabs can create conflicting bindings; the last-write-wins mutation happens under a per-project mutex, so atomicity is preserved, but there's no "this surface belongs to this operator" concept.

6. **Backpressure: input events can drop.** The `MutableSharedFlow` buffer is 256 with `DROP_OLDEST`. For normal use this is several seconds of headroom; under pathological input the router may miss events silently. There is no counter / alarm.

7. **The ring-off byte is an assumption.** `ringOffValue(style)` sends `0` for every `EncoderRingStyle`; if the X-Touch lights its first dot on `0` rather than darkening, that one constant changes (`docs/plans/midi-surface-plan.md` §9 check 3). The `surfaceControls` stream's `ring: off` is the truth the view reads either way.

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

See [control-surface-plan.md §Out of scope](plans/completed/control-surface-plan.md#out-of-scope) for rationale.
