# MIDI Control Surface Engineering Documentation

This document describes the MIDI control-surface subsystem: how external hardware (faders,
encoders, buttons) drives the lighting composition model and how fixture state feeds back to
LEDs and motorised faders.

Related:
- Strategic plan (phases, decisions, open questions): [control-surface-plan.md](plans/completed/control-surface-plan.md).
- The selection-relative layer, strips and the surface view: [midi-surface-plan.md](plans/completed/midi-surface-plan.md)
  (session 1 — the selection, the four selection targets, the mixed arm and the control-state
  stream — has landed; the rest is proposed).
- Composition tiers surfaces write into: [lighting-composition-model.md](lighting-composition-model.md).
- Transport layer surfaces write through: [dmx-engineering.md](dmx-engineering.md).

## Overview

Control surfaces are *another client of the composition model* — not a parallel pipeline.
A fader bound to a fixture dimmer writes the **programmer** (Tier 2 — the same `ProgrammerStore` the web UI
`updateChannel` message uses). A button bound to cue-stack GO dispatches through the same
`CueStackManager` the REST API does. Blackout and the Grand Master's level are applied as a
`TransmitModifier` on the ArtNet controller, beside parking. There is no "surface mode" in the engine.

The implementation follows four separable concerns, mirroring the phase breakdown in
[control-surface-plan.md](plans/completed/control-surface-plan.md):

1. **Transport** — MIDI I/O via ktmidi with a dedicated per-device thread and conflated
   outbound channels (pattern borrowed from `ArtNetController`).
2. **Device profile** — a Kotlin class describing the physical layout of a supported device,
   discovered reflectively at startup.
3. **Binding** — a DB row mapping `(deviceTypeKey, controlId, part, bank)` to a `Binding` (*Does · On · How*).
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
  │    └─ SurfaceDispatch: How → Op, then the function's act:               │
  │         ├─ level → ProgrammerStore on Heads.of(subject) (T2)             │
  │         │    (a head's pan / tilt as its `position`)                     │
  │         ├─ fan → SelectionFan.turn → the fan route's resolve             │
  │         ├─ fxSpeed → SelectionFxSpeed.turn → applyFxUpdate, per Local FX │
  │         ├─ select / selection → State.deskSelection                      │
  │         ├─ locate → LocateManager.toggle, per target of the subject      │
  │         ├─ clear / blind / release → the programmer's own gestures       │
  │         ├─ record / update / undo → the serial lane                      │
  │         ├─ go / back / pause / stop / goTo → CueStackManager (by uuid)   │
  │         ├─ cue → CueHolds (a loose hold)                                 │
  │         ├─ fire → EffectsService.fireFromSurface (needs the arm)         │
  │         ├─ flash → FLASH write on press, those heads cleared on release  │
  │         ├─ blackout / grandMaster → GlobalScalerState                    │
  │         ├─ fxAmount → CueStackManager.setFxAmount                        │
  │         └─ layer → ActiveBankState.setBank, the device pressed           │
  │                                                                          │
  └─────────────────────────────────────────────────────────────────────────┘

  ┌─────────────────────────────────────────────────────────────────────────┐
  │ OUTBOUND (feedback)                                                      │
  │                                                                          │
  │  A 50 Hz poll (the store's revision and Blind, the cue-layer snapshot)  │
  │  and one subscription per Signal                                         │
  │    │                                                                     │
  │    ▼                                                                     │
  │  SurfaceFeedbackPublisher                                                │
  │    ├─ index: one Entry per bound control part, by signal and by part     │
  │    ├─ a readout (Does.read): a level folds LayerResolver.localOver over  │
  │    │    its heads → one common 0..127, Mixed or Nothing                  │
  │    ├─ a light (Does.led): lit or not, from the live state                │
  │    ├─ SoftTakeoverStateMachine.setLogical(…): the jump rule              │
  │    ├─ TouchStateTracker.isTouched(displayKey, controlId) → skip motor    │
  │    ├─ ControlStateTracker ← every send site (the surfaceControls stream) │
  │    └─ MidiFeedbackMessage (ring off on Mixed / Nothing)                  │
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
| `KtMidiController.kt` | ktmidi-backed implementation. Dedicated `newSingleThreadContext("MidiThread-${displayKey}")`; per-`MidiControlKey` conflated channels; 60 Hz transmission loop with delta suppression. Logs every byte in and out as `midi-raw in` / `midi-raw out` under `uk.me.cormack.lighting7.midi.raw` (DEBUG, on by default via the package logger) — the wire itself, separate from what the parser and router make of it. |
| `KtmidiAccessSource.kt` | Wraps a ktmidi `MidiAccess` into the `MidiAccessSource` abstraction. `createPlatformKtmidiAccess()` picks `LibreMidiAccess` (native libremidi via Panama FFM) on macOS / Linux and falls back to `JvmMidiAccess` (`javax.sound.midi`) on Windows, where libremidi-panama lacks an arm64 binary and its x64 binary trips an LLP64 ABI bug. |
| `MidiInputEvent.kt` | Sealed ADT: `NoteOn` / `NoteOff` / `ControlChange` / `PitchBend` / `SysEx`. |
| `MidiFeedbackMessage.kt` | Outbound ADT with `controlKey` for conflation and `encode(): ByteArray`. |
| `MidiDevicePort.kt` | Port enumeration record. |
| `MidiDeviceRegistry.kt` | 1 Hz polling diff of `MidiAccess.inputs ∪ outputs`; emits `DeviceConnected` / `DeviceDisconnected` via `SharedFlow<DeviceEvent>`; auto-opens controllers on connect when `autoOpen = true`. |

### Device profile

| File | Purpose |
|---|---|
| `ControlSurfaceType.kt` | `@ControlSurfaceType(typeKey, vendor, product, portPattern)` annotation. |
| `ControlSurfaceDevice.kt` | Base class with a small DSL: `motorFader`, `fader`, `encoder`, `button`, `bankButton`, `bank`, `jobRegion`, `layout`. |
| `ControlDescriptor.kt` | Sealed ADT: `FaderDescriptor`, `EncoderDescriptor`, `ButtonDescriptor`, `BankButtonDescriptor`. Enums: `LedFeedback`, `FaderResolution`, `EncoderRingStyle`, `SlotRole`. `JobRegion` and its `JobSlot`s (§"Jobs"). |
| `ControlSurfaceRegistry.kt` | Reflective discovery of `@ControlSurfaceType` classes; fail-fast on duplicate `typeKey` or duplicate `controlId` within a class, and on a bad job region or layout. `placeOf(controlId, part)` is where a part sits in a job region. |
| `DeviceMatcher.kt` | Matches attached `MidiDeviceHandle`s against registered profiles by `portPattern`; emits `DeviceAttached` / `DeviceDetached` / `UnmatchedDeviceConnected` on a `SharedFlow<SurfaceEvent>`. |
| `devices/XTouchCompactStandard.kt` | Behringer X-Touch Compact (Standard mode) — 9 motor faders, 16 encoders, 39 buttons, A/B bank buttons. Reference device. |

### Binding, the map and identify

| File | Purpose |
|---|---|
| `Binding.kt` | The binding (surface-setup plan D1): `Binding(does, on, how)` — a function, its subject (`Subject`: none, the selection, a group, a fixture or element key, a job slot, or a record by uuid — `RecordKind` stack, entry, cue, Look, palette, pad, bank, page, master) and a `How` (Toggle, While held, On, Off, Toggle-hold-replaces, Replace) — at a slot with a `ControlPart` (`MAIN`, or an encoder's `PUSH`). `Spec` (where it writes, the subjects, inputs and Hows it takes, its default How), `Op`, `Acted`, `BindingJson` (`classDiscriminator = "type"`, defaults omitted), `decodeBinding` (an unreadable payload is `Does.Unknown`, kept byte for byte), `inputFor` and `inferPart`. Persisted as text in `DaoControlSurfaceBindings.targetPayload`. |
| `SurfaceFunctions.kt` | One `Does` per function (P3): its parameters, its `spec` and its `act`, so the service's refusals, dispatch and health read one definition. `Unknown` is the tolerant decode's placeholder, never written by a client. |
| `SurfaceGrammar.kt` | The one translate from a binding stored before the grammar (P4), shared by database step 15 and the archive reader (`LegacyNames.record`). See §"Before the grammar". |
| `SurfaceJobs.kt` | The jobs (D11): `JobItem`, the item codec, `JobKind` — each kind's fit, items and derivation — and `JobLive`, what a live job reads. See §"Jobs". |
| `SurfaceJobLive.kt` | The live state a job follows — the selection's heads in rig order, the busk page's pads — read off the input path and announced per signal. |
| `SurfaceStripJobs.kt` | The one translate from channel-strip rows to jobs (P4), shared by database step 17 and the archive importer. |
| `SurfaceCatalogue.kt` | The function catalogue (D15): one entry per function an editor offers — two for a grand master and a tempo, whose level or BPM makes a button of each — with its words, its parameters and their options, and the inputs, subjects and Hows its spec admits. `entryOf` is the one answer to which entry a stored binding is. Served as `GET /surface-functions`. |
| `SurfaceLabels.kt` | `SurfaceNames` (every record, group and fixture by its name, with colours, read once per map) and the labels and *Shows* sentences the editors draw: *GO · Act 1*, *The motor shows what Local holds for Front wash's dimmer…*. `controlName` names a control part for a list of where something is bound (`CueUses`). |
| `SurfaceMap.kt` | The served picture (D15): `SurfaceMaps.build` resolves every control part of a device type on every layer through the service's `resolve`, and describes each as a `ResolvedPart`, beside the type's jobs; `SurfaceMapService` keeps the current project's frames in a `StateFlow`, rebuilt off the input path on any binding, encoder-page, health, record-name or project change. |
| `SurfaceIdentify.kt` | Identify mode (D14): a window's wait for a touch. The router offers every input to it before dispatch; the next press, fader move or encoder turn is captured with its part and dropped, and the rest of that gesture on the same control with it until the control is still for 750 ms. 30 s without a touch ends the wait. |
| `ControlSurfaceBindingService.kt` | Binding and job CRUD + in-memory resolver cache keyed by `(projectId, deviceTypeKey, controlId, part, bank)`, jobs by `(deviceTypeKey, region, bank)`; a binding of the part's own beats its region's job, exact-bank over global at each. Decodes **per row**: an undecodable payload becomes `Does.Unknown` (health `unknownTarget`, re-written verbatim) rather than failing the project. Refuses what a function's spec does not admit (§"Bind-time refusals"). Emits `BindingChange` events; broadcast via `surfaceBank.bindingsChanged`. |
| `Heads.kt` | `Heads.of(subject, attribute)`: the heads a write reaches — a fixture or element key, a group's members (elements included) tagged with `sourceGroup`, each selected target so; a cell beside its parent dropped, and a fixture whose attribute lives only on its cells standing for those cells (`PaletteFanOut.headsFor`). The level write, the flash, the colour-axis refusal and the feedback read all expand through it. |
| `SelectionFxSpeed.kt` | The FX speed controls' gesture over the desk selection's Local FX (fx-speed plan D13): which Local FX paint a selected head, the lead FX, an encoder's detents and a fader's position written as whole-tick cycles through `applyFxUpdate`, and `shown` / `leadSpeed` — the `FxSpeedRing` half the feedback publisher reads. The arithmetic is `fx/FxSpeed.kt`, pinned to the editor's by `fxSpeed.fixture.json`. |
| `SelectionFan.kt` | The fan encoders' gesture over the desk selection (venue-visit plan S12): the anchor (each head's start, the rig order read once), the amount per axis, a turn read as a move from the value the ring was last shown, a new gesture's pickup at the centre, and `shown` / `amountFor` — the `FanRing` half the feedback publisher reads. |

### Inbound routing

| File | Purpose |
|---|---|
| `SurfaceInputRouter.kt` | Per-device `CoroutineName("SurfaceRouter-$displayKey")` collector. Pipeline: `profile.inputs.match → soft-takeover gate → binding resolve (with the part) → SurfaceDispatch`. |
| `ControlInputIndex.kt` | One profile's inbound MIDI indexed once: which control part each control change, note and program change addresses (a move, a press or release of a part, a touch, a layer button). The router routes by it, and identify mode reads its answer before dispatch. |
| `SurfaceDispatch.kt` | The one dispatch, `dispatch(binding, input, origin)`: turns the binding's `How` into an `Op` and keeps the holds — a While held press's release, a Toggle-hold-replaces press's pending replace — keyed by control part (`HoldKey`), in `SurfaceHolds`. The router and OSC share it. |
| `SurfaceActions.kt` | Port interface between the functions and the show services, records named by uuid. Production: `DefaultSurfaceActions` resolves `state.show.*` on every call so project switches route cleanly, reads `state.deskSelection` for the selection, expands a level or flash write through `Heads.of`, and resolves a cue / stack uuid to the current project's row before calling `CueStackManager`. Tests: `RecordingSurfaceActions`. |
| `AttributeChannelResolver.kt` | `object` owning what a continuous control **means** on an attribute, in both directions — the write (`toAttributeValue`, a 7-bit value → the programmer's `AttributeValue`) and the read (`describeAttributeRead` + `readHead`, the head's composed value for its key — `LayerResolver.localOver`, §"The readback" — → a 7-bit feedback position). Sliders scale through their own `min..max`. **Colour is one of four HSV axes** (`ColourAxis`), **hue unless the binding says otherwise**: every axis writes a whole colour built from the head's composed one, moving only its own component, and reads that component back. *Hue* keeps the head's saturation and value, the saturation floored at `MIN_SATURATION` (a quarter) so a turn on a white is visible; the ring reads the head's hue, and a head under `HUE_READ_MIN_SATURATION` — black, grey, a warm white — reads as no value rather than lighting for a colour the rig is not showing. *Hue fine* is a centred trim of ±½ coarse step — 64 leaves the head on its step, and the coarse read (which rounds) is unmoved by any fine position; the read is the offset within the step and is **circular**, because the offset is taken from the *nearest* coarse step and at the ends of the trim channel rounding decides which step that is — so a head written at 0 reads back as 0 or as 127 for one colour, and read linearly those are the whole travel apart and a group at 0 would darken its own ring. What circularity cannot fix is the value a single head reports: a motor fader let go at the very bottom can be driven to the top on touch-off — the same colour, the other spelling — which is the trim's one documented flip, confined to its extremes. It sits at the centre *within the head's tolerance* (11 at full chroma, where a written step lands a hair off it). *Saturation* is `v/127` at the head's hue and value — 0 is a white at the head's level, after which the hue and fine rings darken and the next hue turn lifts it back to the floor. *Brightness* is HSV value at the head's hue and saturation; 0 is black, and since the programmer stores RGB the hue and saturation go with it — bind the dimmer on a head that has one. A black or unread head starts from red (hue, fine, saturation) or grey (brightness). Every colour-axis write **carries the composed value's white, amber and UV** rather than zeroing them: those emitters are faders of their own now (`white` / `amber` / `uv` dispatch as sliders by name), and a hue turn that zeroed the white set beside it would be a control fighting its neighbour. A slider asked for an axis answers null in both directions, so a mixed selection reaches the heads it can and the feedback fold skips the same ones (the publisher's `LevelReader`). A read carries a tolerance from its **chroma** (not its value: a bright pastel holds hue as coarsely as a dim saturated head), and `commonValue` requires **every pair** of heads to agree, since a tolerance test is not transitive and anchoring on the first head would make the answer depend on enumeration order. The two directions live in one file so a ring can never claim a position a move would not reproduce; the old rule (one value fanned to R/G/B, red read back) is `FU-MIDI-SELECTION-COLOUR-RED-ONLY` / `FU-MIDI-ENCODER-HUE`. A flash on a colour is still grey at the binding's max — a flash is a level. Settings return null (enum bindings are button-only — Open Question 7). For attribute-value → channel resolution elsewhere (preset toggles, locate, programmer publishes), see `fx/AttributeChannelWriter` which accepts full-range `CueAssignmentResolver.AttributeValue` variants and handles Colour + Position without MIDI-7bit scaling. |
| `ActiveBankState.kt` | Ephemeral `displayKey → bank` map — per attached device, so two surfaces of one type each keep their own layer (D16) — with a `changes: SharedFlow<BankChange>` for WS broadcast. Not persisted. |
| `GlobalScalerState.kt` | Show-scoped `TransmitModifier` implementation of Blackout and the Grand Master (venue-visit plan S5, D5, D12). Walks fixtures on `fixturesChanged` and classifies channels into two packed-`Long` sets in one `AtomicReference`: **scaled** — every DIMMER and UV channel, and on a head with no dimmer its colour mix (a `DmxColour`'s RGB, WHITE and AMBER sliders), a head being a fixture or one of its elements, dimmerless only when neither it nor its parent has a dimmer — and **strobe**. The grand master multiplies a scaled channel by its level and cuts a strobe channel only at 0 (a strobe holds a rate, so scaling it would change its speed); blackout zeroes both. Colour wheels and presets are never scaled. A change only moves the modifier's own state — output is a continuous stream, so the next frame of each universe carries it (see [dmx-engineering.md §Change latency](dmx-engineering.md#change-latency)). Reads through to a project-scoped `GlobalScalerStateHolder`. `Scale7Bit.PERCENT` is a control's 0..127 both ways. |
| `GlobalScalerStateHolder.kt` | Per-project holder for blackout and the grand master's level (0–100 %, `grandMasterLevel`), with `grandMasterEnabled` the level read as `> 0`: turning it off takes the level to 0 and back on restores the last level above 0 this holder saw, or full. Lives on `State.scalerHolderFor(projectId)`, outside the `Show` lifecycle; a fresh `GlobalScalerState` facade is constructed against the same holder on every project activation. Writes through to `project_scaler_states` (machine-local, never synced) through `State`'s `LatestWinsWriter`, which writes the latest row per project at most every 250 ms — a fader dragged from a browser moves the level at frame rate. |

### Feedback and reconciliation

| File | Purpose |
|---|---|
| `SurfaceFeedback.kt` | The feedback vocabulary each function declares in: `Led` (a light or none), `Readout`, `Reading`, `Signal`, the `FeedbackSite` an index entry is built at and the `FeedbackPass` a read sees; and `Scale7Bit`, a control's 0..127 across a window (a tempo's, `PERCENT` for the grand master, `UNIT` for a stack's FX amount). |
| `SurfaceFeedbackPublisher.kt` | One `Entry` per bound control part, indexed by signal and by part, rebuilt atomically in an `AtomicReference`; a 50 Hz poll of the programmer off the transmit thread; the jump rule at `acceptInboundFader`. Defines `SurfaceFeedbackHooks` (the port the router calls — `acceptInboundFader`, `onTouch`, `onButtonRelease`). Writes `ControlStateTracker` at every send site. §"Feedback path". |
| `ControlStateTracker.kt` | The store behind `surfaceControls.state` / `.changed`: per `(displayKey, controlId)` `ControlState(value?, physical?, touched, led, ring)`, lock-free, conflated to one delta per device per 50 ms; a whole-device snapshot after every full resync. §"Control-state stream". |
| `SoftTakeoverStateMachine.kt` | The jump rule (D7): per-`(displayKey, controlId)` `Entry(state, lastPhysical, target, shown, echo)` and a `JumpPolicy` — driven, undriven or jump — judging each inbound move against what the control shows. §"One jump rule". Broadcasts `PickupStateChange` transitions. |
| `TouchStateTracker.kt` | Per-`(displayKey, controlId)` touched flag. While `true`, `SurfaceFeedbackPublisher` suppresses motor writes to that control (don't fight the user's finger). |

## Transport (KtMidiController)

Concurrency shape deliberately mirrors [ArtNetController](dmx-engineering.md):

- **One dedicated single-thread context per device** — `newSingleThreadContext("MidiThread-${handle.displayKey}")`. All outbound sends happen on this thread.
- **Per-`MidiControlKey` conflated Kotlin channels** — last-write-wins. Rapid updates to the same LED coalesce.
- **Conflated `pendingSignal`** acts as the "there's work" flag; idle ticks do no work.
- **60 Hz transmit loop** (`DEFAULT_TRANSMIT_INTERVAL_MS = 17L`) — slightly faster than the ArtNet loop's 25 ms default to keep LED response crisp. Independent of it: the ArtNet interval is now per-universe and configurable, so this is a fixed choice for surface feel rather than a value derived from the DMX rate.
- **Delta suppression** — `lastSentBytes[key]` compared byte-for-byte before `MidiSendTarget.send`.
  That cache records what we last **sent**, not what the hardware **holds**, and the two diverge
  whenever the surface's state is reset behind the desk's back. So it is bypassed where the desk
  already distrusts the hardware: `invalidateFeedbackCache(key)` for one control (a Momentary LED
  cleared by its own physical release) and `invalidateAllFeedback()` for the device, called by
  `SurfaceFeedbackPublisher.sendFullResync` whenever it re-arms pickup — attach, bank change,
  project change. Without that, a resync was a no-op for every control whose recomputed value
  happened to match the last one sent.
- **Consecutive-error backoff** matching ArtNet semantics: first error prints stack trace; > 20 consecutive errors breaks the loop.

### Input path

`KtmidiAccessSource` installs a raw-bytes listener. `MidiMessageParser` handles channel-voice decoding, running status, multi-packet SysEx accumulation, and swallows system real-time / system common interleaves. Parsed events go to a `MutableSharedFlow<MidiInputEvent>(replay = 0, extraBufferCapacity = 256, onBufferOverflow = DROP_OLDEST)` — there is no backpressure at the transport layer. If a consumer can't keep up, oldest events are dropped. For a 60 Hz fader at full tilt that's ~60 CC/sec, well within the buffer.

### Hot-plug

`MidiDeviceRegistry` diffs `MidiAccess.inputs ∪ outputs` on a 1 Hz timer — libremidi has no state-changed callbacks (`MidiAccess.canDetectStateChanges = false` for every desktop backend). Connect / disconnect events fan out on a `SharedFlow<DeviceEvent>`. Auto-open is controlled by the `autoOpen` flag; on connect the registry opens the device and wraps it in a `KtMidiController`, pushing to a `StateFlow<List<MidiDeviceHandle>>` that downstream consumers observe.

On macOS that poll sees nothing on its own, and the reason is CoreMIDI's rather than libremidi's.
CoreMIDI delivers `MIDIClientCreate`'s notification callback — and processes the setup changes
behind it, which is what makes `MIDIGetNumberOfSources()` move — on **the run loop that was current
at the process's first `MIDIClientCreate`**. A headless JVM pumps no `CFRunLoop` anywhere, so every
CoreMIDI-backed reading in the process was frozen at boot: libremidi's observer, CoreMIDI4J's device
map and `javax.sound.midi`'s own provider alike, and the CoreMIDI4J notification `State` registered
for could never be delivered. That was `FU-MIDI-HOTPLUG-UNDETECTED`: an X-Touch unplugged after
boot still read `in · out` thirty seconds later, and a replug attached nothing. The native library
*was* loaded, and CoreMIDI4J's client *did* exist — only because the poll loop's debug branch
happened to call `MidiSystem.getMidiDeviceInfo()`, since `addNotificationListener` creates no
client on macOS.

Three pieces make it work now, in this order:

1. **`CoreMidiHotPlug.ensureStarted()`** (`midi/CoreMidiHotPlug.kt`) — a process singleton that
   `State.midiRegistry`'s initialiser calls *before* it creates `LibreMidiAccess`. It starts a
   daemon thread, `coremidi-runloop`, which constructs a `CoreMidiDeviceProvider` (CoreMIDI4J's
   client, and so the process's first `MIDIClientCreate`) and then pumps that thread's run loop for
   the life of the JVM through `CFRunLoopRunInMode` over Panama. The order is the whole point: the
   first client decides the loop, and libremidi's observer is the second. It logs the path it
   resolved to once — INFO when notifications are live, WARN when they are not — where the old
   listener returned silently if the native library was missing.
2. **The CoreMIDI4J notification** → `State.onCoreMidiEnvironmentChanged` → one
   `midiRegistry.rescan(createPlatformKtmidiAccessSource())` per burst. A plug is several
   notifications and every rescan leaks one libremidi observer into its shared `Arena`, so they are
   coalesced over 250 ms. The rescan swaps the access source and re-ticks under the same mutex as
   the poll. This is the detector; the poll then sees the new list.
3. **`HotPlugFallback`** — the poll's cross-check. Each tick samples `javax.sound.midi`'s device
   list (the JDK's macOS provider re-reads `MIDIGetNumberOfSources()` per call, so with the loop
   pumped it moves the moment a surface is plugged or pulled); a change still unanswered by a
   rescan one tick later triggers exactly one rebuild, at WARN. It is a change detector, never an
   equality test between backends, so it cannot rebuild on every tick — the rebuild count is
   bounded by the number of changes seen. On Windows the registry gets no fallback: `JvmMidiAccess`
   *is* `javax.sound.midi`, live on every poll.

What is deliberately **not** here is a timer that rebuilds `LibreMidiAccess`. That was the first
attempt, and periodic recreation leaked observers until input on open controllers stopped.

`KtMidiController.onTransmissionGaveUp` is the backstop for a port libremidi still lists but can no
longer write to — 20 consecutive send failures. It is not a hot-plug detector: a send only happens
when a value changes, so a surface pulled while nothing moves is never seen there, and a replug
gives the device a fresh endpoint the stale one never writes to.

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

The `ControlSurfaceRegistry` is strictly stricter than `FixtureTypeRegistry` — it fails fast on duplicate `typeKey` *and* on duplicate `controlId` within a class, plus the job-region and layout rules below. Rationale: `controlId` is a stable contract persisted in binding rows, so a duplicate would silently break persisted bindings.

A profile may also declare **job regions** (see [Jobs](#jobs)) and a **layout**: named regions (`strips`, `right`, `master` on the X-Touch) with a `(region, col, row)` cell per control, so the Surfaces view draws any profile that has one from data rather than needing a React component per device. A profile without a layout renders as the grouped table. When a layout *is* declared the registry requires it to be complete — every declared control gets exactly one cell — because a control with no cell would simply vanish from the picture with nothing else reporting it. Both reach the frontend on `GET /api/rest/control-surface-types`.

### DeviceMatcher

`DeviceMatcher` subscribes to `MidiDeviceRegistry.events` and matches each attached `MidiDeviceHandle` against registered profiles by running `portPattern.matches(displayName)`. Emits:

- `DeviceAttached(handle, profile)` — device matched a registered `@ControlSurfaceType`
- `DeviceDetached(displayKey)` — device unplugged
- `UnmatchedDeviceConnected(handle)` — device with no matching profile; nothing binds to it until a profile matches

Every inbound `Connected` event yields exactly one `SurfaceEvent` — the matcher is the single arbiter of "is this device one we know about?".

## Jobs

Fixed per-control bindings run out fast: nine faders and sixteen encoders is not many, and every console
the composition model measures itself against solves that the same way — a region of the desk is given a
**job** (surface-setup plan D11): eight groups as channel strips, the selection's wheels on the right,
palettes on a row. MagicQ's wing modes and grandMA3's executor configurations work this way. Jobs replace
the channel strips of earlier sessions: a strip was a job for one column.

**Regions** are the profile's (`midi/ControlDescriptor.kt`'s `JobRegion`, declared with `jobRegion`): ordered
slots, each naming the control that plays each of its roles — `FADER`, `SELECT`, `ENCODER`, `FLASH`, `BUTTON`,
`PUSH` (an encoder's push part). They are kept apart from the picture's layout regions: the picture places every
control, a job region names slots. The X-Touch Compact's six:

```kotlin
jobRegion("strips", "Strips 1–8") {
    repeat(8) { i -> slot(fader = "fader-${i + 1}", select = "btn-${25 + i}", encoder = "enc-${i + 1}", flash = "btn-${i + 1}", push = "enc-${i + 1}") }
}
jobRegion("encoders", "Encoders 9–16") { repeat(8) { i -> slot(encoder = "enc-${9 + i}", push = "enc-${9 + i}") } }
jobRegion("row-2", "Buttons 9–16") { repeat(8) { i -> slot(button = "btn-${9 + i}") } }
jobRegion("row-3", "Buttons 17–24") { repeat(8) { i -> slot(button = "btn-${17 + i}") } }
jobRegion("keys", "Buttons 34–39") { repeat(6) { i -> slot(button = "btn-${34 + i}") } }
jobRegion("master", "Master strip") { slot(fader = "fader-9", select = "btn-33") }
```

`buildFromClasses` fails fast on a duplicate region id, an empty region or slot, a role naming an undeclared
control or one of the wrong kind (a fader→`FaderDescriptor`, an encoder→`EncoderDescriptor`, a push→an encoder
with a `pushNote`, the rest→`ButtonDescriptor`), a control part in two slots and a control in two regions: a
region id is a persisted contract, as a `controlId` is. The profile DTO carries `jobRegions`, each with the kinds
that fit it, so a client never judges a fit itself (P5).

**Kinds** are one enum, `JobKind` (`midi/SurfaceJobs.kt`): each says the region shapes it fits, what its items
are, and the binding each slot's role derives — `derive(role, slot, ctx)`, pure. A kind whose item is a subject
derives a template on `Subject.Slot` and fills it from the slot's item (`Binding.filled`); a kind whose item is
the function (a wheel, an encoder page, a palette) builds the binding from it.

| Kind | Fits | Items | Each slot |
|---|---|---|---|
| `groupStrips`, `fixtureStrips` | every slot a fader | groups / fixtures | fader `level(dimmer)`, select `select`, encoder `level` on the device's encoder page, flash `flash(dimmer)` |
| `headStrips` | every slot a fader | — (the selection's heads, in rig order) | as a strip, its select *Replace*: narrows the selection to that head |
| `stackStrips` | every slot a fader | stacks | fader `fxAmount`, flash `go`, select `back` |
| `masterStrips` | every slot a fader | speed masters | fader `tempo`, flash `tap` |
| `wheels` | encoders, no faders | functions (any continuous entry, on its subject) | encoder the item |
| `pagedWheels` | encoders, no faders | — (the selection's heads) | encoder `level` on the encoder page, on head *n* |
| `palettes` | buttons | palettes | `palette` on the selection |
| `buskPage` | buttons | — (the pads of the busk page showing) | `pad` on pad *n* |
| `gos` | buttons | stacks | `go` |
| `looks` | buttons | Looks | `look` |
| `encoderPages` | buttons | functions (`encoderPage` only) | the item |
| `transport` | buttons | one stack | GO, Back, Stop, Pause, Tap (master 1), Clear, by slot |
| `programmer` | buttons | — | Clear, Blind, Undo, Release and Locate on the selection, Clear selection, by slot |
| `grandMaster` | one slot with a fader | — | fader `grandMaster`, select `blackout` |

No kind derives a push: an encoder's push does nothing until bound (D4). The code's encoder page names one
attribute, so *Wheels by encoder page* is the selection's heads one an encoder on the page's attribute, and
*Encoder pages* sets one attribute a button.

**Storage**: `surface_jobs` (`models/surfaceJobs.kt`) — `uuid`, project, device type, region, layer (null for both),
kind and items, a JSON array of `JobItem`s with a gap as null, kept as text so a kind or an item this build cannot
read is kept byte for byte and derives nothing. One job a `(project, device type, region, layer)`, which the
service holds to, and so does the importer: an archived or restored job landing where another stands replaces it
(the unique index cannot, since SQLite lets two rows share a null layer). Trailing gaps are trimmed before the items
are counted. The service caches jobs beside the bindings and writes them through `putJob` / `deleteJob`,
refusing by name with `SURFACE_JOB_INVALID`: an unknown device type, region, layer or kind, a kind that does not
fit the region, too many items, an item of the wrong type, a wheel that takes no continuous input or acts on no
subject it admits. `GET /projects/{id}/surface-jobs`, `GET` / `PUT` / `DELETE
/projects/{id}/surface-jobs/{deviceTypeKey}/{region}?layer=`; `GET /surface-job-kinds` is the kinds, static per build.

**Resolution order** — `ControlSurfaceBindingService.resolve` answers the control part's own binding *across both
layers* before it looks at the job, then the job on its region across both:

1. the part's row for the exact layer
2. the part's row on both layers
3. what the job on the region derives for the part's slot and role, at the exact layer
4. what the job on both layers derives

Direct before job at **both** levels is the load-bearing part: a both-layers binding beats an exact-layer job,
which is what makes "a control bound by hand leaves the job" true regardless of layers. An exact-layer job that
derives nothing for the part — an empty slot, a role its kind leaves alone — falls through to the job on both, as
a binding does. `resolve` is the only resolution entry point — router, feedback index, the map and the takeover
lookup all come through it.

A job hit is answered with the job's id, the slot's health and no takeover policy (the control's own default), and
`job: JobHit` naming the job, region, slot and role. Each slot's health is evaluated when the job is cached and on
`invalidateHealth`, from what its roles derive; a live job's slots are always Ok. `jobBindingAt` answers what the
job would derive on a part a binding of its own beats, which the map reads for `beats`.

**Live jobs** follow state: `headStrips` and `pagedWheels` the selection's heads in rig order, `buskPage` the pads
of the page showing. `SurfaceJobLive` (`State.surfaceJobLive`) holds them as a volatile snapshot `resolve` reads
lock-free on every event, refreshed off the input path on the selection, the busk page, the rig, the layout, the
jobs and the project, and read only while the project holds a job that follows that signal. A change re-derives the
region without re-arming pickup: the publisher rebuilds its index and feeds each job-derived entry again with
`rearm = false`, and a part a job derived before and no longer does goes dark (`rederiveJobs`). The encoder page is
not one of them: a page change already rebuilds the device's index and re-arms, because the meaning moved under the
operator's hand.

*Make this control its own* (editor A) writes the binding a job's control derives at the control's own slot on the
viewed layer, through the slot write; the job keeps the rest of its controls. The map names a derived part
`from: JOB` with its `jobId`, `jobRegion`, `item` (the slot) and `jobLabel`; a part bound by hand over a job says
what it `beats`; and each frame lists the type's `jobs`, their items named by name (`SurfaceJobView`).

**Database step 17** (`state/SurfaceJobsMigration.kt`) and the archive importer turn an older desk's strip rows —
binding rows at `strip-1`…`strip-8` and `strip-master` — into jobs through one translate, `SurfaceStripJobs.convert`
(P4): per device type, layer and region, a `groupStrips` job where most strips name a group, else `fixtureStrips`,
each strip its slot's item and an empty strip a gap; a strip naming the other kind becomes its controls' own
bindings (the encoder a `pageLevel`), unless a binding of the control's own already beat it there. A strip row whose
subject cannot be read is dropped. Uuids are made from the strips', so two desks translating one archive write the
same records. A strip's takeover policy is not carried: a job's controls take their own default.

## Encoder bank

What a device's encoders on the encoder page drive is per-device session state, keyed by the device's `displayKey` (D16): `EncoderBankState`, shaped exactly like `ActiveBankState` with an `EncoderBankSelection` — an attribute name and, for a colour, a `ColourAxis` (null is hue) — where that has a bank id: one map-valued `StateFlow` for the snapshot, a lock-free `selectionFor()` for the hot path, a `changes` `SharedFlow` for the publisher. Default `dimmer`. Two selections are one bank when the attribute and the *effective* axis agree, so `(rgbColour, null)` and `(rgbColour, HUE)` are not a change and `(rgbColour, SATURATION)` is.

It moves two ways: a button bound to `encoderPage(attribute, axis?)`, which applies to **the device the button is on** (the payload names no device, so a bank button can never be stranded pointing at a surface that is not attached), or the frontend's `surfaceEncoderBank.set`. The LED lights when both halves match, so a *hue* button and a *saturation* button on one colour attribute light one at a time. A strip job puts the axis on the strip's **encoder** and nowhere else — the fader and flash are the dimmer whatever the page says.

It survives a device unplug and is **reset on project switch** (a carried reload of the same project keeps it) — unlike the active bank, which does not. The vocabulary it draws from is the patch's (`BindingHealthEvaluator.selectionAttributesOf`, the same set a level on the selection is judged against), so carrying an attribute across projects could point every encoder at an attribute the new rig has no fixture for.

A change rebuilds the feedback index — every encoder following the page now stands on different channels — and re-arms takeover on that device through the same path a bank change uses, because the meaning of the control moved under the operator's hand without them touching it.

## Binding model

A binding is a row in `DaoControlSurfaceBindings` at a slot:

```
(projectId, deviceTypeKey, controlId, part: MAIN | PUSH, bank: String?)
```

where `bank = null` means "both layers" and `bank = "layer-a"` "only while layer A is active".
Resolution: exact-bank wins over global; within a layer, at most one binding per control part.

A **part** is what a control feeds (D4): a fader's move, an encoder's turn and a button's press are
its `MAIN` part, and an encoder's push its `PUSH` part, a binding of its own that does nothing until
bound — no push writes its turn's attribute full. A part's input is `inputFor(part)`: continuous for a
turn, a button for a press or a push, nothing for a bank button. A write that names no part is placed
by `inferPart`: an encoder's push for a function only a button takes, else the main part.

The payload is a `Binding(does, on, how)` (surface-setup plan D1), stored as **text** (`BindingJson`,
`classDiscriminator = "type"`, defaults omitted) — `{"does":{"type":"go"},"on":{"type":"record",
"kind":"STACK","uuid":"…"}}` — with its function's id mirrored in `target_type`. *Does* is the function
with its own parameters; *On* is the subject; *How* how a button drives it, null for the function's
default. A record is named **by uuid only**, a typed `UUID`: a malformed one fails the decode, and the
row reads as `Does.Unknown`. Every function is one `Does` (`midi/SurfaceFunctions.kt`) carrying its
`Spec` — where it writes (Local, Playback, Master, Tempo, Desk; D8), the subjects, inputs and Hows it
takes — and its `act`, so no `when` over functions decides what a binding may be.

The `ControlSurfaceBindingService` maintains an in-memory resolver cache rebuilt on every mutation
(add/update/remove) and on binding reload after project switch. Lookup on the hot path is O(1)
`HashMap` with a fallback to the global-bank entry.

### Functions

| Function | On | Input | Effect |
|---|---|---|---|
| `level(attribute, axis?)` | selection · group · fixture | Continuous | Programmer write (owner `surface`) on every head the subject reaches (`Heads.of`), typed through `AttributeChannelResolver.surfaceWrite` on the binding's colour axis (null is hue; refused at bind time on a non-colour attribute, `BINDING_AXIS_NEEDS_COLOUR`); a head on which the attribute is not a colour skips an axis move. A head's **pan or tilt writes its `position`**, the other axis kept, as `programmer.setPosition` writes it (D5) — the key a Circle paints, so a nudge holds the Circle back (and holds the other axis against later cues). A `DmxWhiteMixColour` head takes a colour move on the colour its whites make. An empty selection drops the write with a debug log, never widened to "everything". Health `unknownAttribute` for a selection attribute no patched fixture takes |
| `pageLevel` | selection · group · fixture | Continuous | A `level` on whatever attribute and colour axis the [encoder page](#encoder-bank) of **the device it is on** names, as a strip job's encoder follows it, on any continuous control and subject. A page change rebuilds that device's index, so the readout follows the new attribute at once. Health is the subject's alone: its attribute is the device's, which no binding names |
| `flash(attribute, percent)` | selection · group · fixture | Button (While held) | Press: programmer write (owner `flash`) at `percent` of each head's own range, as a fader scales (D10), grey at the percentage on a colour. Release: clears the `flash` slot of exactly the heads the press wrote — on the selection, the heads selected at the press, not whatever is selected at the release |
| `select` | group · fixture | Button | `DeskSelection.toggle` (head-by-head, a group by its members), or `set(listOf(target))` on Replace; LED lit while the target's heads are all selected. **Toggle-hold-replaces**, the default, toggles on press and, still held at `SurfaceDispatch.SELECT_HOLD_MS` (500 ms), replaces — the fader-wing gesture. A release always cancels the pending replace, even if the binding changed or went dead meanwhile. Toggle and Replace arm no hold |
| `locate` | selection · group · fixture | Button | `toggleLocate` once per target of the subject — each selected target, or the one group or fixture: all located → all released, else the unlocated ones come up; LED lit while every target is located (the selection's while it is non-empty) |
| `fan(axis)` | selection · group | Continuous | **Fan** the subject's positions — the desk selection's, or a group's in its member order — on `PAN` or `TILT` (venue-visit plan S12), or their landing points on the stage axes `ACROSS` or `DEPTH`, through the Fan panel's own resolve (`fanIntoProgrammer`, `mode: ABOUT`, owner `surface`). `midi/SelectionFan.kt` holds a gesture per subject, so a group's fan and the selection's do not reset each other; a new gesture starts from the centre, an input further than the jump rule's eight steps from it showing the ring the centre again. Fed back by its readout (`Signal.FAN`). Refused on one fixture (D2): a fan spreads over several heads |
| `fxSpeed` | selection · group · fixture | Continuous | The **Speed** of every Local FX painting a head of the subject, each whole (fx-speed plan D13), through `midi/SelectionFxSpeed.kt`: an encoder a quarter of an octave a detent from where its ring shows the lead FX (the one on the subject's first head in rig order), a fader ×¼–×4. Fed back by its readout (`Signal.FX_LIST`), a fader's within five steps of where it was moved. Health always `ok` |
| `clear(fade)` | — | Button | The programmer's whole **Clear** (`clearProgrammerCompletely`: values, its FX, layers, Locate, scenery, tempos and the include target) over `fade` seconds, at once on the input's thread. The programmer fade is the browser's, so the binding carries its own, 0–600 s as MCP's programmer tools take (`MAX_PROGRAMMER_FADE_SECONDS`). No LED |
| `blind(fade)` | — | Button: Toggle · While held | The programmer's **Blind** (`setProgrammerBlind`, the WS frame's and MCP's one helper: its Looks' scenery, its tempos and its fires follow) over `fade` seconds. While held lets go on release only when its press put it on. LED lit while Blind (`Signal.PROGRAMMER`, which the poll moves on a Blind change) |
| `release(fade)` | selection · group · fixture | Button | `programmer.clearTarget` once per target of the subject: everything the programmer holds on it and its heads, and its Local FX, over `fade` seconds. A Local FX all of whose heads lie among several targets together is stopped first (`releaseLocalFxWithin`), as the UI's *Release n* stops one each target alone answers as partial. An empty selection releases nothing. No LED |
| `record` | record (stack) | Button | **Record** a new cue at the end of the stack (`performProgrammerRecord`, `CREATE`): every touched value, all attributes, with the programmer's FX, held scenery and held tempos — the Record sheet's defaults — named and numbered as the sheet's default would be, on the serial lane. Refused in a venue (`VENUE_RECORD_OFF`); a press on a stack the show lacks is `NotFound`. No LED. Not on OSC (OSC plan D6) |
| `update` | — | Button | **Update** Mode A (`performProgrammerUpdate` with no targets) into what Include, or the last Record, left included — a cue or a Look — on the serial lane. Refused `NOTHING_INCLUDED` when nothing is, and in a venue. No LED |
| `palette(palette)` | selection | Button | `ProgrammerLayerStack.toggle` on the **desk selection**, masked to the palette's own derived family, no solo siblings. A *generic* palette with an empty selection is dropped; a per-fixture one names its own heads. LED lit while it covers every selected target. Health `missingPalette` |
| `look` | record (Look) | Button: Toggle · On · Off | The Look **whole**, never onto the selection (absolute-looks plan P1): Toggle puts one layer of it on or takes every layer off; On and Off land that state and do nothing when it already holds (OSC plan D4). LED lit while any layer of it is on. Health `missingLook` |
| `cue` | record (cue) | Button: Toggle · On · Off | A cue's **loose hold** (`CueHolds`, cue model plan D6): toggled, or taken (`takeLooseCue`) or let go (`dropLoose`), On and Off doing nothing when it already is so. Health `missingCue` |
| `pad` | record (pad) | Button | The pad's own press through `BuskPressService`, solo siblings included, with the desk selection as the targets. LED is the pad's ring (a palette pad's covers the selection; a Look pad's is on or off; a cue pad's lit while held loose; a stack pad's while its stack runs). Health `missingPad` |
| `pickUp` | record (pad) | Button | The record on that pad into the desk's **hand**, stamped `SelectionSource.SURFACE`, replacing what was held. Health `missingPad`. No LED |
| `place` | record (bank) | Button | Place what the hand holds as a pad on that bank through the **same append** `POST /busk/banks/{bankId}/pads` makes, then let go; an empty hand is a dropped press, a refused place leaves the item in the hand. Health `missingBank`. No LED |
| `drop` | — | Button | `HandState.drop()`; a no-op on an empty hand. No LED |
| `go` · `back` · `pause` · `stop` | record (stack) | Button | GO (`CueStackManager.go`), GO BACK (`advanceStack(BACKWARD)`), cancel the follow (`pauseAutoAdvance`), or an operator's **stop** (`stopStack`, busk-stack plan D6: the stack reads stopped at once and its cues fade over its release time, dropping the desk's arm except on a stack whose release is still running). Health `missingStack` |
| `goTo` · `standby` | record (entry) | Button | GO TO one entry, starting its stack if stopped, nothing if it is already live (OSC plan D4); or arm it as the stack's next GO. The entry names its stack. A marker is refused (`NOT_A_CUE`). Health `missingCue` for an entry that has gone |
| `fxAmount` | record (stack) | Continuous | `CueStackManager.setFxAmount` — 0..127 across 0–100 %, or an amount taken whole (an OSC float, D10). Fed back as `StackFxAmountEntry`. Health `missingStack` |
| `fire(trigger)` | fixture | Button | Fire one tube of a one-shot fixture through `EffectsService.fireFromSurface` (stage-view plan session 9). **Needs the desk's arm**: an unarmed press sends nothing and is announced (`effects.skipped`); a blind programmer rehearses it. Fixture only (D2). Health `missingFixture` / `missingAttribute`. No LED |
| `blackout` | — | Button: Toggle · On · Off | `GlobalScalerState.toggleBlackout()`, or set on or off (a press finding it so does nothing). LED lit while blackout is as the button sets it |
| `grandMaster(level?)` | — | Continuous · Button | With no level: a fader or encoder sets the level, 0..127 across 0–100 % (its readout follows the level), and a button takes it to 0 and back (LED lit while above 0). With a level: a press sets it there. A level taken whole (OSC `/grandmaster`) is a percentage |
| `tempo(minBpm, maxBpm, bpm?)` | record (master) · none (master 1) | Continuous · Button | With no `bpm`: `SpeedMasterBank.setBpm` across the binding's window. With one: a press sets that tempo, 20–300 — a typed tempo's write, so on a held master it moves the hold, and a follower refuses it. A tempo taken whole (OSC `/tempo/{m}`) is BPM |
| `tap` · `tempoScale(factor)` | record (master) · none | Button | `SpeedMasterBank.tap`, or ×½ / ×2 (fx-speed plan D12; refused `OutOfRange` outside 20–300) |
| `layer(layer)` | — | Button | `ActiveBankState.setBank` for **the device the button is on** (D16): the payload names no device |
| `encoderPage(attribute, axis?)` | — | Button | `EncoderBankState.set` for the device the button is on; LED lit while that attribute **and** axis are its encoder page. Health `unknownAttribute` |
| `undo(kind)` | — | Button | `UNDO` / `REDO` / `PROGRAMMER`: the undo key for the surface's own source, `surface:{deviceKey}` (undo plan §4.8, D1, D2), through `moveHistory`, on the serial lane. A refusal moves nothing and is logged; a surface cannot confirm. No LED |
| `buskPage(step?)` | none · record (page) | Button | One page along (`step` ±1, wrapping), or that page (`BuskPageState.setByUuid`; LED lit while it shows). A page with a step, or no page without one, is refused |
| `windowFocus(window, focus)` · `windowSheet(window)` | — | Button | One `windows.viewOptions` — `{focus}` (`split` · `pads` · `rig`) or `{sheet: toggle}` — to **every** connected window named `window`. Health `missingWindow` while none has the name, re-evaluated on every registry change. No LED |
| `selection(step)` | — | Button | `CLEAR` empties the desk selection; every other step is `DeskSelection.subselect` (the Elements chip's `ALL` · `ODD` · `EVEN` · `FIRST_HALF` · `SECOND_HALF` · `INVERT` · `MASTERS`, and `NEXT` / `PREV` one step along the rig order). No LED |
| `unknown` | — | — | Never dispatched: health `unknownTarget` gates it. Produced only by the tolerant decode; re-encoded verbatim; refused from a request |

**A window-addressed function names its window by registry name**, not by row id, because a binding
has to survive the socket that minted the id (busk-further plan D14). Duplicate names are allowed
(multi-screen plan D9) and every row of the name receives the command; a name matching no connected
window is a logged no-op at press time and `missingWindow` in health. That health is the one check
that is not a database fact, so `SurfaceFeedbackPublisher` collects `WindowRegistry.windows` (by
name set) and re-runs `invalidateHealth` on every change. `FU-WINDOWS-NAME-ADDRESSED-BINDING` is the
item for when duplicate names ever matter.

**The serial lane** is `DefaultSurfaceActions`' one-at-a-time dispatcher and mutex: Undo, Redo, Record
and Update run there in press order, off the input's thread, since each opens transactions or reloads,
under the input's `WriteOrigin` so their writes are the surface's own in the history. A press queued
behind a project switch is dropped when it reaches the head of the lane. A press of another function
made while the lane is busy can land before a queued Record reads the programmer, which is accepted.
Clear acts at once: it opens no transaction and reloads nothing, and queued it would wipe what was
written after the press.

**Health** is the subject's — a fixture or group (with the attribute a level or flash writes on some
head `Heads.of` reaches, elements included, each judged as cue authoring judges a cell), a fixture's
trigger, a record by uuid — then the function's own parameters
(`BindingHealthEvaluator`). `MissingStack` and `MissingCue` name the uuid; their ids are 0.

### Before the grammar

Until session 1 of the surface-setup plan a binding was a `BindingTarget`, a 50-variant union with
the subject in the type (`FixtureAttribute`, `GroupAttribute`, `SelectionAttribute` …), on/off forms
of the toggles (`LookSet`, `BlackoutSet`, `LooseCueSet`) and cues and stacks named by int beside
their uuid. `midi/SurfaceGrammar.kt` is the one translate from that shape (P4): each variant's
function, subject and How, an on/off form as its toggle with a How of On or Off, a flash's raw max as
a percentage, a cue or stack named by int alone as its uuid where the caller can resolve it, and an
**encoder holding a button variant as its push** (`partFor`). What it cannot translate — an int that
resolves to nothing, a type this build never knew — answers null and the row keeps its bytes, reading
as `unknown`. Two callers share it:

- **Database step 15** (`state/SurfaceGrammarMigration.kt`, `PRAGMA user_version` 15) translates every
  row, its ints resolved in its own project, and drops `uq_surface_binding_slot`: the reconciliation
  has added `part` and `uq_surface_binding_part_slot` beside it, and SQLite drops an index without
  rebuilding its table. Backed up first to `<db>.pre-surface-grammar-<utc>.db` when it has a binding to
  translate.
- **The archive reader**, `LegacyNames.record("controlSurfaceBindings")`, after the older renames, for
  an archive below `formatVersion` 53; an archive's ints are another desk's, so none resolve.

### Bind-time refusals

Six rules live on `ControlSurfaceBindingService` rather than in the REST routes, because the
service is the one door every write comes through:

| Rule | Refuses | Code |
|---|---|---|
| `refuseUnknown` | an `unknown` binding from a request — it exists to be rebound, not re-sent | — |
| `refuseWrongKind` | a function the control part's input is not in (`inputFor(part)` against `Spec.inputs`): a level on a button or a push, a GO on an encoder's turn, anything on a bank button | `BINDING_WRONG_CONTROL_KIND` |
| `refuseBySpec` | a subject the function's spec does not take — a fan on one fixture, a GO on a cue — or the function's own `refusal` (a busk page with both a page and a step), and a How it does not take | `BINDING_WRONG_SUBJECT` / `BINDING_WRONG_HOW` |
| `refuseTriggerAttribute` | a `level`, `flash` or `encoderPage` naming a **one-shot trigger or its arm** (stage-view plan session 9): a trigger fires as an event, and a surface's door to one is `fire`, which needs the arm. Judged by `TriggerIndex` against the register; a target-less selection or encoder-bank name by every trigger's name. A **fixture command** (fixture optics plan session 7 — a reset, a lamp strike) is refused by the same rule through `CommandIndex`: a command runs from the fixture panel or `run_fixture_command`, never from a control | `TRIGGER_NOT_STORABLE`, `COMMAND_NOT_STORABLE` |
| `refuseAxisOnNonColour` | an axis on an attribute that is not a colour — a fixture or group by the heads it reaches (`Heads.of` and `AttributeChannelResolver.isColour`, the dispatch lookup, so it can never disagree with a move), the selection or an encoder page by the rig's colour vocabulary (`Context.colourAttributes`). A missing fixture or an unknown attribute name is health's, not this rule's | `BINDING_AXIS_NEEDS_COLOUR` |

The hand's three are the only place bindings there are — there is deliberately **no
`HandPlaceInSlot` and no layer-stack place**, because neither a cue slot nor the programmer's layer
stack has a uuid a binding could carry. A place made from a *window* is that window's own mutation
plus `hand.drop` and needs no binding at all (D12); see
`docs/lighting-composition-model.md` §"The busk layout".

`refuseWrongKind` closes `FU-MIDI-BIND-CONTROL-KIND` from the function's spec: what a part feeds is
`inputFor(part)` and what a function takes is its `Spec.inputs`. The frontend keeps no copy: its form
offers only the catalogue entries whose inputs include the part's.

The coded rules carry their code out through `BindingRefused(message, code)`, an
`IllegalArgumentException` every existing caller already maps to a 400; the routes' own
`validateRequestShape` checks only that the slot exists.

**Related non-MIDI path.** Phase 7 of `docs/plans/completed/cue-authoring-unification-plan.md` adds a
separate resolver, `fx/AttributeChannelWriter`, for attribute-value → channel
resolution without 7-bit MIDI scaling. The surface-input path above keeps using
`AttributeChannelResolver` for 7-bit input; programmer writes originating elsewhere
(preset-toggle, future REST attribute writes, "preview on selection") go through
`AttributeChannelWriter`. Both resolvers
emit the same `AttributeChannelResolver.ChannelWrite` shape, so downstream consumers
don't need to distinguish.

### The served picture (D15)

The editors draw what the desk resolves; the browser keeps no copy of the resolve order, the kind
tables or the label rules (P5). Three things serve it:

- **`surfaceMap.state`** — one frame per device type, `{projectId, deviceTypeKey, layers, jobs}`: each layer
  the profile declares, and `""`, no layer — what fires on a device on none (the rows on both layers),
  as after a restart, and a profile with no layers' only entry — → `"controlId:PART"` → a `ResolvedPart`. A part says where its
  binding comes from (`DIRECT`, `JOB` with its `jobId`, `jobRegion`, `item` and `jobLabel`, or `NONE`),
  the row's `bindingId` and the layer it is stored on (`bank`, null for both), the binding's `does`, `on`
  and `how`, its catalogue `entry`, its `takeoverPolicy`, its `label` (records by name), `subjectLabel`,
  the colour of the group or fixture it drives, `writesTo`, the `shows` sentence, and its `health` with
  `healthText`; a direct part over a job says what it `beats`. `jobs` is every job of the type with its
  items named (`SurfaceJobView`), what Editor B draws. A job's encoder on the encoder page is labelled
  at the page of the first attached device of the type — the page is a device's (D16), the map a type's. Only the current project is mapped, and only the types in use: attached now, holding a
  binding or a job in the project, or already built (so a type that drops out still gets the frame that empties
  it). The frame is sent again only when it changed; a speed master's rename rebuilds it, as a
  record's does.
- **`surfaceUses.state`** — `{projectId, uses}`, the map's reverse index (`midi/SurfaceUses.kt`), rebuilt
  with the map and sent beside it when it changed: `group:<key>`, `fixture:<key>` or `record:<KIND>:<uuid>`
  → every control part acting on it, read from the map frames so a job's parts count. A record named in a
  catalogue parameter of kind `RECORD` (a palette's press) is filed under it as well as the part's
  subject. A part reaching every layer is one use with no `layer`; one reaching some is a use per layer.
  Each says where it is (`label`, *Button 36 · A*), what it does there (`does`, the map's label) and
  `fromJob`. Editor D's *Bound on* reads it; `uses.fixture.json` pins it beside the map's fixture.
- **`GET /api/rest/surface-functions`** — `SurfaceCatalogue.entries`, static per build.
- **The slot write** — `PUT /projects/{id}/surface-bindings/slot` `{deviceTypeKey, controlId, part,
  layer, binding, takeoverPolicy?}` makes whatever the slot holds this binding (the row kept, rewritten in
  place), and `DELETE …/slot` `{deviceTypeKey, controlId, part, layer}` clears it (404 when empty). Every
  §"Bind-time refusals" rule applies. A slot write states the whole slot, so an absent `takeoverPolicy`
  is the control's default.
- **The slot preview** — `GET …/surface-bindings/slot/preview?deviceTypeKey=&controlId=&part=&layer=`
  answers `{place, replaces}` (`SurfaceUses.slotPreview`, over the type's map built at the call): `place`
  is `SurfaceUses.placeLabel` (*Encoder 9 push · A*), and `replaces` what a write there would change, a
  line each — *Replaces X.*, *Layer B keeps its own: X.*, *Beats X on Layer A; every other layer keeps
  it.*, *Takes it out of <job> (X).* — empty when nothing acts there. Editor D's heading and *Replaces …*
  read it, so the layer precedence stays on the desk (P5). 400 for an unknown type, control or layer.

`src/test/resources/surfaces/catalogue.fixture.json` and `map.fixture.json` pin both answers;
`SurfaceCatalogueTest` writes and checks them (`REGENERATE_SURFACE_FIXTURES=1` after a deliberate
change), and the frontend's surface tests read them.

### Identify mode (D14)

`surfaceIdentify.begin {deviceTypeKey?}` makes the window wait for a touch: the desk answers
`surfaceIdentify.state {sessionId, waiting: true, deadlineMs, remainingMs}`, the banner, which counts
`remainingMs` down on the browser's own clock. The next press, fader move
or encoder turn on any attached device of that type (any, with none named) is captured by
`SurfaceIdentify.offer`, which `SurfaceInputRouter.route` asks **before** it dispatches, so a bound GO
touched to identify it does not fire. The rest of the gesture on that control — its release, the fader
still moving — is swallowed too, until the control has been still for 750 ms. A fader's touch is never
captured or swallowed, so its touch-up still reaches the feedback; nor is a fader move at 0, a fader at
rest. The capture reaches the
window that waited (`surfaceIdentify.captured {sessionId, deviceTypeKey, displayKey, controlId, part}`)
and no other: the socket keeps the sessions it began (`ownedIdentifySessions`), cancels them on close,
and replaces its wait on a second `begin`. A wait not answered in 30 s ends with `surfaceIdentify.state
{waiting: false, reason: "timeout"}`; `surfaceIdentify.cancel` with `reason: "cancelled"`. A layer key is
not captured. Learn, its capture beside dispatch and its commit, went with the old screens: an editor
binds the captured part through the slot write.

## Inbound routing (SurfaceInputRouter)

Per-device coroutine `CoroutineName("SurfaceRouter-$displayKey")` collects from `MidiController.input`. Pipeline:

### 1. Match event to control part

`profile.inputs.match(event)` (`ControlInputIndex`, built once per profile; the first declared control
wins an address two claim):

| Input event | Matches |
|---|---|
| `ControlChange(cc)` | `FaderDescriptor.cc` or `EncoderDescriptor.cc` → `Move(control, value7Bit)`, the main part |
| `ControlChange(cc)` | `FaderDescriptor.touchCc` → `Touch(control, down = value > 0)` |
| `NoteOn(note)` | `ButtonDescriptor.note` → `Press(control, MAIN)` |
| `NoteOn(note)` | `EncoderDescriptor.pushNote` → `Press(control, PUSH)` |
| `NoteOn(note)` | `FaderDescriptor.touchNote` → `Touch(control, down = true)` |
| `NoteOn(note)` · `ProgramChange` | `BankButtonDescriptor` → `Layer(bankId)` (press only) |
| `NoteOff(note)` · `NoteOn(velocity 0)` | the matching `Release` or touch up |

### 2. Bank-button short-circuit

`BankButtonDescriptor` is intentionally not user-bindable — bank buttons call `ActiveBankState.setBank(displayKey, bankId)` for the device they are on directly, bypassing the binding resolver. A user who wants to switch layers from an arbitrary button binds `layer` instead.

### 3. Jump-rule gate (continuous only)

Before binding resolution, `SurfaceFeedbackHooks.acceptInboundFader(displayKey, deviceTypeKey, controlId, value7Bit)` judges the move by the control's `JumpPolicy`, read from the feedback index — §"One jump rule". A dropped move goes no further; a driven control is shown its value again.

### 4. Binding resolve

`ControlSurfaceBindingService.resolve(projectId, deviceTypeKey, controlId, activeBank, encoderBank, part) → ResolvedBinding?`. Returns null for an unbound part. A release resolves nothing: it lets go of its own part's hold whatever is bound now.

### 5. Dispatch via SurfaceDispatch

`SurfaceDispatch.dispatch(binding, input, origin)` (`midi/SurfaceDispatch.kt`) runs the binding's
function and answers an `ActionOutcome` — `Done(detail?)`, `Refused(code, message)`,
`NotFound(message)` or `Failed(message)`. The router and the OSC collector share it (OSC plan D2), so a
button and an OSC message naming one binding do one thing. An input is a `Move` (a 7-bit position), a
`Set` (a value taken whole, from OSC, D10), a `Press` or a `Release`. A press becomes an `Op` by the
binding's How, in this one place: Toggle, On, Off and Replace press as themselves; **While held**
presses on and keeps the release the function answered (`Acted.release`, else an Off), which the
part's release runs; **Toggle-hold-replaces** toggles and, still held after `SELECT_HOLD_MS`, replaces.
Holds are keyed by `HoldKey(displayKey, controlId, part)` in `SurfaceHolds`, never by binding id, so
two devices of one type each hold their own flash; a device unplugged lets go of its holds. A function
given an op it does not take is `Refused(WRONG_KIND)`, and a subject its spec does not admit (a row
written by hand) `Refused(WRONG_SUBJECT)`. The router logs at debug what is not `Done`, since a MIDI
press has no reply channel; the OSC log shows it.

Every input runs inside `SurfaceDispatch.guarded`, which turns a throw — `state.show` mid-switch
among the causes — into `Failed`, so one bad input never ends a device's collector
(`FU-MIDI-SHOW-GUARD`).

See the Functions table above. `DefaultSurfaceActions` handles its own thread coordination:
- `ProgrammerStore` is lock-free (`ConcurrentHashMap`)
- Cue stack operations dispatch onto `GlobalScope` with `OptIn(DelicateCoroutinesApi)` — the router thread never blocks on show services
- Dependencies are resolved through `state.show.*` on every call, so `ProjectManager.switchProject` doesn't leak stale references

### Flash lifecycle

Press: `AttributeChannelResolver.flashAttributeValue` types each head's value at the binding's
percentage of the head's own `min..max` (D10), so a flash at 100 % lands on a capped dimmer's cap. The
write lands as a programmer slot (owner `flash`, sideband not absorbed) on the heads the subject
reaches, and the engine publishes the composed cascade.

Release: `ProgrammerWriter.clearAttributes` pops the `flash` slot of **the heads the press wrote** and
publishes the composed cascade in one transaction — the surviving owner, the cue layer, or baseline
takes over immediately.

## Feedback path (SurfaceFeedbackPublisher)

Each function says what it shows, on its own `Does` type (surface-setup plan P3): a button function
declares `led(binding, site)` — a `Led.Light(signals, lit)` or, explicitly, `Led.None`; it is abstract,
so a new function cannot be left out of feedback silently — and a continuous one `read(binding, site)`,
a `Readout(signals, read)` answering a `Reading`: `Value(value7Bit, tolerance)`, `Mixed` or `Nothing`.
`SurfaceFeedbackTest` holds every function to declaring an LED and every continuous one to a readout.
The vocabulary is `SurfaceFeedback.kt`.

### The index

One `Entry(displayKey, control, part, binding, light | readout, jump)` per bound control part on each
attached device, resolved at the device's own layer and encoder page (D16), indexed **by signal** and
**by control part**. An encoder is two parts: its turn has a readout, its push (`pushNote`, `pushLed`) a
light, sent to the push note. The index is rebuilt on a binding, device, layer, encoder-page, fixture,
master-list, Look, palette or busk-layout change, and on nothing else; every rebuild is followed by a
resync of the devices it touched, never by a seed of its own.

Record lookups that cost a database read are made at index time, once per record: a stack's or a
cue's id for the GO and cue LEDs, and `BuskRefs` (what each pad presses, what id each page has),
called lazily and only when a bound control names a pad or a page. A light carries its record
resolved; the read at LED time is the live state alone.

### Signals

A control is re-read on its signals (`Signal`) and on nothing else:

| Signal | From | Read by |
|---|---|---|
| `PROGRAMMER` | the poll: the store's `revision`, its `blind`, the cue-layer snapshot's identity | every level |
| `SELECTION` | `DeskSelection.state`'s targets | a level on the selection, select, locate, palette and palette-pad LEDs, fan, FX speed |
| `LOCATE` | `LocateManager.activeTargets` (per show) | locate LEDs |
| `LAYERS` | `ProgrammerLayerStack.layersFlow` and `referencesRevision` (per show) | Look, palette and pad LEDs |
| `SCALER` / `GRAND_MASTER` | the scaler's on/off flows / its level (per show) | blackout and grand-master LEDs / grand-master faders |
| `TEMPO` | `SpeedMasterBank.changes` (per show) | tempo faders and encoders |
| `STACK_FX` | `StackFxAmounts.state` (per show) | FX amount faders |
| `STACK_RUN` / `CUE_HOLDS` | `cueRunStateChanged` / `cueHoldsChanged` | GO LEDs (lit while the stack runs), stack pads / cue LEDs (lit while held loose), cue pads |
| `BUSK_PAGE` | `BuskPageState.pageId` | busk page LEDs |
| `ENCODER_PAGE` | a rebuild of that device | encoder page LEDs (attribute and effective axis both) |
| `HOLDS` | `SurfaceHolds.changes`, by control part | flash LEDs, each device its own |
| `FX_LIST` / `FX_SPEED_SHOWN` | `FxEngine.fxStateFlow` (per show) / `SelectionFxSpeed.shown` (sent past the cache) | FX speed |
| `FAN` | `SelectionFan.shown` | fan encoders |

A selection change is a signal, not a rebuild: a level on the selection expands its heads again on the
next read, and keeps them until the selection moves.

### The readback (D6)

A fixture control shows, and moves from, **what Local holds for its key** — the staged value in
Blind — **else the cue layer under the programmer, without FX** (`LayerResolver.localOver`). It reads
**beneath a held flash** (the store read through `ProgrammerStore.beneath(FLASH)`) and **ignores
park**. `fallbackFor` is the same function with the programmer left out in Blind and the flash
included, so output and readback share one composition. So a motor fader holds still under a Local
FX, a held flash leaves its key's faders alone, a parked dimmer's fader shows what Local or the cue
holds, and in Blind a fader shows the value it edits. Pan and tilt read from the head's composed
`position` (D5).

`AttributeChannelResolver` turns the composed value into a position both ways: `readHead(read, value,
axis)` reads it and `toAttributeValue` / `surfaceWrite` build a move on it — a colour axis keeps the
value's white, amber and UV, the other position axis is kept from it — so a hue turn in Blind keeps
the staged emitters rather than copying the stage's. A level's reading is the one value its heads
agree on (`commonValue`, every pair within tolerance), `Mixed` when they do not, and `Nothing` with no
heads. The fan's base reads the same way (`routes/programmerFan.kt`'s `fanBase`).

None of this is on a DMX channel, so the publisher does not listen to the transmit path: a loop on the
publisher's scope polls every 20 ms, about 50 Hz, and re-reads the `PROGRAMMER` entries only when the
store's `revision`, its Blind or the cue-layer snapshot moved. It is the revision and not the `epoch`
because a put bumps the epoch before it installs the slot: a read between the two would take the old
value under the new epoch, and the revision moves once more after the install. Each level's heads keep their fixture and
their `FxTarget` from the index (or the last selection), so a read is a `localOver` per head.

### One jump rule (D7)

`SoftTakeoverStateMachine` is the one rule for every fader and ring, a `JumpPolicy` per entry
(`JumpPolicy.of(control, takeoverPolicy)`):

- **Driven** — a motor fader, an encoder with a ring, under PICKUP (the default): a move within
  `JUMP_STEPS` (8) of what it shows, or one made while it is touched, is taken; any other is dropped
  and the control is shown its value again past the transport's cache (`Verdict.RESHOW`). A fast flick
  from a stale position cannot jump the function.
- **Undriven** — a fader with no motor: picks up when it crosses the value it shows (from below or
  above, ±1), and waits again when its value diverges by more than a step, or always on a re-arming
  resync.
- **Jump** — a binding whose `takeover_policy` is IMMEDIATE: every move is taken.
- A **mixed** or absent value shows nothing and takes any move: it is meant to make the heads agree.
- After a move is taken the control shows **the position it was moved to** while the function reads
  back within the reading's tolerance, so a motor never chases its own write rounded through the
  function — an FX speed fader's readback is a stick and whole-tick rounding away, its tolerance 5.

The router asks `acceptInboundFader` before resolving the binding; the policy comes from the index
(`jumpPolicyFor`), not a resolve per move. The fan's and FX speed's own guards and the unguarded strip
encoder are gone: a strip encoder with a ring is driven like any other. Pickup transitions still go
out as `surfacePickup.changed`.

### Full resync

Two flavours. **With pickup re-armed** — device attach, a layer or encoder-page change on that device,
project change, where the physical position is stale: every undriven fader waits to pick up, and the
transport's delta cache for the device is dropped. **Without** — a binding, fixture or record-list
change (`rebuildAndResync`): the operator's faders have not moved. Either way every entry of the device
is applied — a readout through the jump rule to its motor or ring (the mixed arm darkens a ring and
drives no motor), a light to its note — and the device's whole `ControlStateTracker` snapshot is
published. It reads current state, not edge events, so a flash held when a device attaches lights at
once. Touch-off re-applies that one control: the motor catches up to the function, a tempo fader's
included.

A button's or a push's LED is re-asserted on release past the delta cache (`onButtonRelease`), for
surfaces that turn it off locally.

## Interaction with the composition model

| Surface event | Writes to | Tier | Notes |
|---|---|---|---|
| Fader → FixtureAttribute | `ProgrammerWriter.writeAttribute` | **Tier 2 (programmer)** | Sticky; wins over cues and suppresses FX on the attribute |
| Fader → GroupAttribute | `ProgrammerWriter.writeAttributes` | **Tier 2 (programmer)** | Fanned to members, slots tagged with the source group |
| Flash press | `ProgrammerWriter.writeAttribute` (owner `flash`) | **Tier 2 (programmer)** | Cleared on release; cascade republished |
| Blackout toggle | `GlobalScalerState` (`TransmitModifier`) | **post-composition mask** | Dimmer, UV, strobe, and a dimmerless head's colour mix |
| Grand Master fader / toggle | Same as Blackout | **post-composition scale** | The level multiplies the same channels; a strobe or an LED ring is cut only at 0 |
| Bank-button press | `ActiveBankState.setBank` | *(no tier — routing state)* | Swaps the binding resolution axis |
| Cue stack buttons | `CueStackManager.*` | *(Tier 4 via cue apply)* | Same path as REST / UI; uuid resolved to the project's row first |
| Fire cue | `CueHolds.toggleLooseCue` | *(Tier 4)* | |
| Fire trigger | `EffectsService.fireFromSurface` | **above composition, under park** (`TriggerOutput`) | A ~300 ms pulse on the tube's channel, only while the desk is armed; never a value, so no tier holds it |
| Fader → SelectionAttribute | `ProgrammerWriter.writeAttributes` (owner `surface`) | **Tier 2 (programmer)** | One write per selected head, a group's members tagged with `sourceGroup` — the same slot a fixed fader uses, so releasing a flash reveals it the same way |
| Select / Clear button | `State.deskSelection` | *(no tier — the desk's selection)* | What the next selection-relative move acts on |
| Locate button | `LocateManager.toggle` (owner `locate`) | **Tier 2 (programmer)** | Per target of the subject, the `POST /locate/toggle` path |
| Clear · Release · Blind | `clearProgrammerCompletely` · `clearTarget` · `setProgrammerBlind` | **Tier 2 (programmer)** | The programmer's own gestures, at the binding's fade |
| Record · Update | `performProgrammerRecord` · `performProgrammerUpdate` | *(writes the show)* | The REST routes' cores; a recorded or updated live cue republishes as it would from the sheet |
| Apply Look / Press palette | `ProgrammerLayerStack.toggle` | **Tier 2 (a programmer layer)** | The Look whole, the palette onto the selection; the same `toggle` a busk pad makes |
| Press pad | `BuskPressService` | **Tier 2, or Tier 4 for a cue pad** | The pad's whole plan — solo siblings included — through the one implementation `POST /busk/pads/{id}/press` also uses |
| Busk page buttons | `State.buskPageState` | *(no tier — a screen position)* | Moves the desk's page; every window *following* it moves too (a window may hold its own — `lib/buskPageFollow.ts`) |

**A fader always writes the programmer, and reads back from it** (§"The readback"). Phase 6 added a second destination — when a cue-edit session was open for the project, `DefaultSurfaceActions.writeFixtureAttribute` / `writeGroupAttribute` routed to `cueEdit.setProperty` (the cue layer) instead, and `SurfaceFeedbackPublisher` drove the motor from the cue's Tier 4 value rather than the stage. Backend sweep item D1 retired the `cueEdit.*` family, so both halves are gone: one write destination. See [control-surface-plan.md](plans/completed/control-surface-plan.md) §Phase 6 for what the session-routing design was.

## Selection

`state/DeskSelection.kt` is the desk's one selection — a `StateFlow<List<CueTargetDto>>` on
`State`, beside `activeBankState`, so it outlives a `Show`. **One desk, one selection,
server-owned**: the composition model's one-programmer argument applies verbatim
(`docs/lighting-composition-model.md` §"Tier 2 — Programmer"), and every client — the busk view, the
programmer page, an X-Touch's select buttons, the AI's `get_current_state` — reads and writes this
one list through the `selection.*` socket family (`docs/websocket-engineering.md`). Transient:
cleared on project switch (before the publisher rebuilds, so a stale head never enters the new
index) — a carried reload of the same project (a sync pull, a venue Update; `ShowCarry`) keeps it and
prunes it instead — pruned on `fixturesChanged` / `patchListChanged` of any target that no longer resolves
(the rest stay, the rule a press applies), never persisted.

**A group and its members are two spellings of one selection.** `fx/TargetCoverage.kt` is the one
expansion rule, lifted out of `ProgrammerLayerStack` so a select button and a busk pad agree:
`covers(target)` is true when every head the target expands to is selected, and `toggle` on a
covered target takes those heads off head-by-head — an entry the press does not touch keeps its
spelling, a group it only partly covers is respelt as the members left behind. An unresolvable
group expands to itself, so a stale entry stays comparable rather than vanishing.

The surface reaches it through every fixture function bound on the selection — `level`, `pageLevel`,
`flash`, `locate`, `fan`, `fxSpeed`, `release` — through `select`, `palette` and `pad`, and through
`selection(step)`, whose moves rewrite the selection's targets over the rig order through
`DeskSelection.subselect` (`docs/lighting-composition-model.md` §"The rig"); the router stays
selection-blind — every function calls a `SurfaceActions` method and `DefaultSurfaceActions` reads
`state.deskSelection`, which is what keeps `RecordingSurfaceActions` a complete test double.

## The showing busk page

`state/BuskPageState.kt` is `DeskSelection`'s twin for a *position*: which busk page the desk is
showing, as a `StateFlow<Int?>` on `State`, transient, cleared on project switch (reconciled instead
on a carried reload of the same project) and reconciled on `buskLayoutChanged`. It exists for the same reason the selection does — a hardware *next page*
button and a tab click in the busk view are two ways of making one gesture, so there has to be one
answer or the button and the screen disagree the moment either is used. WS family
`busk.pageState` / `busk.setPage`; the client's `?page=` mirrors it.

Three details are decisions rather than shape:

- **Ids on the wire, uuid only at the binding.** Everything else addresses a page by id (`?page=`,
  the page routes), and a binding addresses one by uuid because a binding has to survive a clone.
  `setByUuid` is the one place the two meet.
- **`step` wraps.** Next and Prev are the only page controls a surface has; a *next* that stopped at
  the last page would leave the operator with no way back but a mouse, on the one surface whose
  point is not needing one.
- **`reconcile` resolves a deleted page to `null`, not to the first page.** The busk view already
  resolves a page it cannot find against the list it fetched, so saying nothing leaves each client
  on its own fallback rather than dragging every client onto a page none of them asked for.

It shares only a namespace with `busk.layoutChanged`, which names pages whose *document* changed and
is what the client's echo suppression is written against. A page-state frame carries no layout.

## The busk press, from two doors

`routes/BuskPressService.kt` holds the whole of a pad press — reading the pad, its record and (in a
solo bank) its siblings in one transaction, then applying — and `POST /busk/pads/{padId}/press` is
its HTTP door while `PressPad` is its MIDI one. It answers an `Outcome` rather than responding,
because only one of the two callers has a reply channel: a MIDI press has nowhere to put a 400, so
a refusal is a log line there and a coded error in the route. The route's request body carries one
thing hardware cannot — `cycle` — and a surface press passes null.

The point of the extraction is that the two doors cannot diverge: the solo rules, the
empty-selection refusals and the cue toggle are the *pad's* behaviour, not the endpoint's.
`BuskPressRouteTest` drives both against one set of fixtures for exactly that reason.

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
wrong, which is the bug worth finding (`docs/plans/completed/midi-surface-plan.md` D7).

Writes come from the router coroutine, the publisher's readback poll and its subscribers,
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
| `SurfaceFeedbackPublisher` readback poll | `Dispatchers.Default`, every 20 ms | reads the store and `LayerResolver.current` lock-free; `AtomicReference<Index>` swap |
| `SurfaceFeedbackPublisher` subscribers | `Dispatchers.Default` | StateFlow / SharedFlow |
| ArtNet transmit thread | — | feedback adds nothing to it: the publisher registers no `channelsChanged` |
| `ControlSurfaceBindingService` cache mutations | per-project `Mutex` via `lockFor(projectId)` | ConcurrentHashMap reads |
| `ProgrammerStore` | any thread | lock-free `ConcurrentHashMap` slot stacks |
| Cue stack dispatch | `GlobalScope` (fire-and-forget) | service's own coordination |
| Undo, Redo, Record, Update | the serial lane: `Dispatchers.IO.limitedParallelism(1)` on `GlobalScope` | one `Mutex`, in press order |

The router thread never blocks on show services. The one continuous target that does real work on it is `SelectionFan`: a turn runs the fan route's resolve synchronously under the fan's monitor, and reads the rig order — a database transaction — once per gesture, not once per detent, as a stage fan reads its placements. Programmer store mutations are lock-free (the engine's publish serialises on its own lock). Cue stack operations and fire-cue dispatches land on `GlobalScope` so the router keeps draining MIDI events even during slow cue transitions.

## State wiring

From `State.kt`:

| Field | Lifecycle |
|---|---|
| `State.midiRegistry` | Lazy; started in `State.initializeShow()` after `show.start()` |
| `State.activeBankState` | Lazy; ephemeral, no persistence |
| `State.deskSelection` | Lazy; ephemeral — cleared on project switch (pruned on a carried same-project reload), pruned on fixture reload, no persistence |
| `State.surfaceHolds` | Lazy; ephemeral — every held part, keyed by device, control and part; cleared on a project switch, kept by a carried reload |
| `Show.globalScalerState` | Per-show; `.attach()` called in `Show.start()` after fixture load; `.detach()` in `Show.close()` |
| `State.surfaceFeedbackPublisher` | Lazy; started in `initializeShow()` **before** `surfaceInputRouter` so hooks are ready when the first inbound event arrives |
| `State.surfaceInputRouter` | Lazy; constructed with `DefaultSurfaceActions(state)` (resolves show on every call, not once at construction); `surfaceInputRouter.start(GlobalScope)` in `initializeShow()` |

`State.onProjectChanged()` re-attaches the fixture listener and triggers a full resync for every attached device so the new show's composition state drives the motors.

## WebSocket surface

### Inbound

| Message | Payload |
|---|---|
| `surfaceDevices.state` | *(request current device list)* |
| `surfaceBank.state` | *(request active banks)* |
| `surfaceEncoderBank.set` | `{ displayKey, attribute, colourAxis? }` — the device, the axis absent for hue |
| `surfaceEncoderBank.state` | *(request encoder banks)* |
| `surfaceScaler.setBlackout` | `{ enabled }` |
| `surfaceScaler.setGrandMaster` | `{ enabled }` — off is level 0, on the last level |
| `surfaceScaler.state` | *(request scaler state)* |
| `grandMaster.set` | `{ level }` — the grand master's level, 0–100 (`GrandMasterSocket.kt`) |
| `stackFxAmount.set` | `{ stackId, amount }` — a cue stack's FX amount, 0–1 (`StackFxAmountSocket.kt`) |
| `surfaceIdentify.begin` | `{ deviceTypeKey? }` — wait for a touch, captured without firing (§"Identify mode") |
| `surfaceIdentify.cancel` | `{ sessionId }` |
| `selection.set` / `.toggle` / `.setFamilies` / `.clear` | `{ targets }` / `{ target }` / `{ families? }` / — (`SelectionSocket.kt`) |
| `busk.setPage` | `{ pageId }` — the showing busk page (`BuskSocket.kt`) |

### Outbound

| Message | Payload |
|---|---|
| `surfaceDevices.state` | Aggregate of `midiRegistry.devices ∪ deviceMatcher.attached ∪ activeBankState.active` |
| `surfaceBank.state` | `{ activeBanks: Map<displayKey, bank> }` |
| `surfaceBank.changed` | `{ displayKey, deviceTypeKey?, previousBank, newBank }` — one device's switch |
| `surfaceEncoderBank.state` | `{ attributes: Map<displayKey, { attribute, colourAxis? }> }` — one frame type: it is a StateFlow, so the subscription is snapshot and broadcast both, and there is no delta frame |
| `surfaceScaler.state` | `{ blackoutEnabled, grandMasterEnabled }` |
| `grandMaster.state` | `{ projectId, level }` — connect snapshot + broadcast (`GrandMasterSocket.kt`) |
| `stackFxAmount.state` | `{ projectId, stacks: [{ stackId, amount }] }` — every stack below full; connect snapshot + broadcast (`StackFxAmountSocket.kt`) |
| `surfacePickup.changed` | `{ displayKey, controlId, state, target }` |
| `surfaceBank.bindingsChanged` | Broadcast on every binding mutation |
| `surfaceIdentify.state` / `.captured` | The window's wait and its capture, to that window only |
| `surfaceMap.state` | `{ projectId, deviceTypeKey, layers }` — the served picture, a frame per device type, sent when it changed |
| `surfaceUses.state` | `{ projectId, uses }` — what each group, fixture and record is bound on, the map's reverse index, sent when it changed |
| `surfaceControls.state` / `.changed` | `{ displayKey, controls: {controlId: ControlState} }` — whole device / conflated delta |
| `selection.state` | `{ targets }` — connect snapshot + broadcast (`SelectionSocket.kt`) |
| `busk.pageState` | `{ pageId }` — connect snapshot + broadcast; `null` means the desk has not been pointed at a page (`BuskSocket.kt`). Shares only a namespace with the `busk.layoutChanged` broadcast, which is about *documents* |

## REST surface

| Endpoint | Purpose |
|---|---|
| `GET /api/rest/control-surface-types` | Device profile metadata for the frontend |
| `GET /api/rest/projects/{projectId}/surface-bindings` | List bindings |
| `POST /api/rest/projects/{projectId}/surface-bindings` | Create binding |
| `PATCH /api/rest/projects/{projectId}/surface-bindings/{id}` | Update (uses `FieldUpdate<T>` sentinel for nullable fields). Shape-validated against the row it *becomes*, the same check `POST` runs |
| `DELETE /api/rest/projects/{projectId}/surface-bindings/{id}` | Delete |
| `PUT` / `DELETE /api/rest/projects/{projectId}/surface-bindings/slot` | The editors' one write: a control part on a layer, named in the body (§"The served picture") |
| `GET /api/rest/projects/{projectId}/surface-bindings/slot/preview` | Where a slot is and what a write there would change (§"The served picture") |
| `GET /api/rest/surface-functions` | The function catalogue |

## Known limitations

1. **`AttributeChannelResolver` is reflective and fixture-aware.** Bindings store `(fixtureKey, attribute)`. If a fixture type is refactored (attribute renamed, mode changed), existing bindings silently fail to resolve at dispatch time. There is no compile-time or load-time validation today. See Open Question 9 in [control-surface-plan.md](plans/completed/control-surface-plan.md) — proposed fix (binding reconciliation diagnostic) is tracked as Phase 7 of that plan.

2. **Blackout and the grand master are per project and machine-local.** State survives show tear-down on project switch (the `GlobalScalerStateHolder` lives on `State`, keyed by `projectId`), so blackout on project A → switch to B → switch back to A keeps blackout on, and the level a project was left at comes back with it. Each change is written through to `project_scaler_states` (at most every 250 ms), so it also survives a restart. The table is excluded from sync: another desk opening the project starts at full.

3. **The jump rule is eight steps for every driven control.** A fast flick from a position the desk did not show the encoder is refused and the ring shown its value again (§"One jump rule"); a binding that wants every move taken sets its takeover policy to *Immediate*.

4. **Enum / setting attributes are button-only.** `AttributeChannelResolver` returns empty for `DmxFixtureSetting`. Continuous-to-enum mappings are disallowed at bind time (Open Question 7). Bind-time refusals carry a machine-readable `code` (`BINDING_WRONG_CONTROL_KIND` and the rest); the slot shape errors on this route still answer 400 with a message alone.

5. **No cross-session ownership for continuous controls.** Two `/surfaces` tabs can create conflicting bindings; the last-write-wins mutation happens under a per-project mutex, so atomicity is preserved, but there's no "this surface belongs to this operator" concept.

6. **Backpressure: input events can drop.** The `MutableSharedFlow` buffer is 256 with `DROP_OLDEST`. For normal use this is several seconds of headroom; under pathological input the router may miss events silently. There is no counter / alarm.

7. **Attribute names are canonicalised in one place and not another.** `PersistedFixtureReferenceValidator.validateTargetedReference` maps `colour` → `rgbColour`, but `AttributeChannelResolver.rawAttribute` and `FixtureAttributeCatalogue.byName` do not — so a binding typed `colour` reads health `ok` and is silent at dispatch. `refuseAxisOnNonColour` deliberately uses the dispatch lookup and so cannot judge such a name either; it is left to health, which today says nothing. Pre-existing; the fix is one canonicalisation in the catalogue.

8. **The ring-off byte is an assumption.** `ringOffValue(style)` sends `0` for every `EncoderRingStyle`; if the X-Touch lights its first dot on `0` rather than darkening, that one constant changes (`docs/plans/completed/midi-surface-plan.md` §9 check 3). The `surfaceControls` stream's `ring: off` is the truth the view reads either way.

## Testing

- Test seams at every layer: `MidiSendTarget`, `MidiInputSource`, `MidiAccessSource`, `SurfaceActions`, `SurfaceFeedbackHooks` — all injectable.
- `SurfaceFeedbackPublisher.pollForTest()` runs one poll of the readback, so a test seeds the programmer store or the cue layer and reads the feedback without waiting for the loop.
- `KtMidiController.flushForTest()` drains the transmit loop deterministically without waiting on the real ticker.
- `GlobalScalerState.seedIntensityChannelsForTest(pairs)` stubs the channel classification without walking a real fixtures registry.
- `ControlSurfaceRegistry.buildFromClasses(classes)` is `internal` so tests can exercise validation paths without mutating the live class list.
- Phase-by-phase test counts (from Status): Phase 0 — 26, Phase 1 — 8 (matcher), Phase 2 — 12 (binding service + learn), Phase 3 — 45, Phase 4 — 32. **520 total** backend tests passing as of 2026-04-19.

## Not implemented (architecture accommodates)

OSC in is implemented as a fixed address set over UDP and SLIP-framed TCP, with optional replies
(`docs/osc-engineering.md`); learnable OSC addresses and feedback to a learned sender wait as
`FU-OSC-LEARN` in `docs/plans/followups.md`.

- Network MIDI / RTP-MIDI
- MSC (MIDI Show Control)
- USB HID / vendor protocols (Stream Deck, Loupedeck)
- Relative encoder encodings (Mackie, binary offset, two's complement)
- SysEx-driven LCD text
- Submasters
- Multi-operator concurrent surface editing

See [control-surface-plan.md §Out of scope](plans/completed/control-surface-plan.md#out-of-scope) for rationale.
