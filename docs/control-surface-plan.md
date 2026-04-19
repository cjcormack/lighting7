# Control Surface Support — Plan & Handover

> **Document status:** Living plan. Will be converted into permanent engineering docs (`control-surface-engineering.md`, possibly updates to `lighting-composition-model.md` and `websocket-engineering.md`) once implementation lands. Update this file at the end of every session.
>
> **Not in production yet.** We're free to break the DB, skip rollback shims, and iterate on migrations loosely.

## How to use this document

This plan spans multiple sessions across two repos (Kotlin backend `lighting7` + React frontend `lighting-react`). **Read this section first** if you're picking up the work cold.

- **Current status**: see [Status](#status) — what's done, what's in flight, what's next.
- **Locked decisions**: see [Decisions](#decisions) — directions already confirmed. Don't re-open without asking.
- **Open questions**: see [Open Questions](#open-questions) — items deferred for user input. Ask before implementing.
- **Resuming work**: identify the current phase from [Status](#status), read that phase's section for entry criteria, files and verification, and do the next unchecked item. Update Status before ending the session.

Related:
- Prior-art survey and protocol analysis: [research/control-surface-prior-art.md](research/control-surface-prior-art.md).
- Composition model this plan integrates with: [lighting-composition-model.md](lighting-composition-model.md).
- Cue authoring plan (paused during surface v1): [cue-authoring-unification-plan.md](cue-authoring-unification-plan.md).

---

## Status

**Phase**: 2 complete. Phase 3 next.

**Most recent session**: 2026-04-19. Phase 2 mapping model + MIDI Learn landed.
`DaoControlSurfaceBindings` table (project-scoped, `(device_type_key, control_id, bank)` unique slot, `bank` nullable) + `BindingTarget` sealed hierarchy (`FixtureProperty` / `GroupProperty` / `CueStackGo` / `CueStackBack` / `CueStackPause` / `FireCue` / `Flash` / `Blackout` / `GrandMasterToggle` / `SetBank`) serialized as a discriminated JSON union with `type` discriminator. `ControlSurfaceBindingService` keeps a per-project in-memory cache and exposes `resolve(projectId, deviceTypeKey, controlId, activeBank)` with bank-specific > bank-agnostic precedence. `MidiLearnSessionManager` subscribes to `DeviceMatcher.events`, collects from each attached controller's `input` flow via `MidiDeviceRegistry.controllerFor`, filters with a capturable-event predicate (fader moves with value > 0, button / encoder presses, never bank buttons), and times out pending sessions after 30 s. CRUD REST at `/api/rest/project/{projectId}/surfaceBindings` (GET/POST/GET/PATCH/DELETE) with shape validation against `ControlSurfaceRegistry` + slot-conflict 409s. WebSocket messages `surfaceLearn.begin` / `surfaceLearn.cancel` / `surfaceLearn.commit` on the input side, `surfaceLearn.started` / `surfaceLearn.captured` / `surfaceLearn.committed` / `surfaceLearn.cancelled` / `surfaceLearn.error` / `surfaceBindingsChanged` on the output side; per-connection `ownedLearnSessions` scopes capture broadcasts to the originating client. State wiring: `State.controlSurfaceBindingService` and `State.midiLearnSessionManager` with `start(GlobalScope)` in `initializeShow()`; schema adds `DaoControlSurfaceBindings` to `SchemaUtils.createMissingTablesAndColumns`; project-delete cascades to bindings and invalidates the cache. 25 new tests (9 serialization, 6 resolver precedence, 10 learn session state machine), 443 total passing.

**Next actions** (for the session that picks this up):
1. Start Phase 3: `SurfaceInputRouter` that subscribes to each attached controller's `input` flow and dispatches to the existing composition layers.
2. Add `ActiveBankState` service (in-memory, per-device); wire WS `surfaceBank.set { deviceTypeKey, bank }`.
3. Extend `DirectWriteStore` with a property-level API so `(fixture, property, value)` fans out to channels via the patch.
4. Add transmit-time global scalers (`Blackout`, `GrandMasterToggle`) behind a `TransmitModifier` hook in `ArtNetController`, analogous to the `ParkManager` path.
5. Wire `FlashStateTracker` for per-binding saved-value restoration on release.
6. Manually validate on the X-Touch Compact: run the REST + WS Learn flow end to end (begin → move fader → captured → commit → `GET /surfaceBindings` shows the row) before touching Phase 3 routing.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Transport foundation: ktmidi setup, `MidiController`, per-device coroutines, input/output channels, rate limiting, delta-tracked feedback, hot-plug detection | **Complete** |
| 1 | Device profile model: Kotlin `ControlSurfaceDevice` classes + `@ControlSurfaceType` annotation + `ControlSurfaceRegistry`, X-Touch Compact profile class | **Complete** |
| 2 | Mapping model + MIDI Learn: `ControlSurfaceBinding` table, `BindingTarget` sealed types, REST/WS routes, MIDI Learn session | **Complete** |
| 3 | Inbound routing: fader → Layer 4 writes, buttons → GO/Back/Pause/FireCue/Flash/Blackout/Grand Master, app-side banks | Not started |
| 4 | Feedback & reconciliation: motor drive, LED feedback, touch suppression, soft takeover, initial sync, device-side A/B layer coordination | Not started |
| 5 | Frontend `/surfaces` route: device list, binding matrix, MIDI Learn mode, bank management, binding badges on existing views | Not started |
| 6 | cueEdit integration (depends on cue-authoring Phase 1): fader writes route through `cueEdit.*` when a session is active; surfaces participate in EditorContext | Not started |

---

## Context

The application runs from a web UI today. That's enough for programming and for screen-side show operation, but it leaves physical show-running to the mouse. A control surface is the natural next step — faders and buttons in the hands of the operator, the screen freed up for programming-oriented views.

The prior-art survey ([research/control-surface-prior-art.md](research/control-surface-prior-art.md)) pulls four architectural elements from QLC+, Bitwig, Ableton, ReaLearn, and Ardour:

1. **Transport layer** that knows nothing about application semantics (MIDI I/O, OSC I/O).
2. **Device model** describing a specific piece of hardware — its controls, their message types, their feedback capabilities.
3. **Mapping layer** binding device controls to application functions, editable at runtime.
4. **State reconciliation** keeping physical and logical state in sync (motor drive, soft takeover, touch suppression, echo suppression, initial sync).

This plan follows that four-layer model. Phases 0, 1, 2, and 4 correspond to those layers respectively; Phase 3 is the inbound event path from transport to composition model; Phase 5 is the frontend; Phase 6 closes the loop with cue-edit sessions.

The tight coupling with cue-authoring: a surface fader is functionally equivalent to dragging a slider in `FixtureContent`. During a `cueEdit` session, the frontend routes that drag into the cue's Layer 3 assignments. Surfaces should do the same. This is why we pause cue-authoring at Phase 0 and run surfaces end-to-end before resuming — the surface flow is another consumer of `EditorContext` semantics, and it's cleaner to design the composition-model touchpoints once surfaces exist to challenge them.

## Decisions

Confirmed with the user 2026-04-18:

### Composition model integration

- **Fader writes route dynamically by context.** Same model as the cue-authoring plan's `EditorContext`. When no `cueEdit` session is open, a surface fader writes **Layer 4** (sticky direct write, byte-identical to `updateChannel`). When a `cueEdit` session is open, it writes into the cue via the `cueEdit.*` messages the frontend uses.
- **Surfaces obey `EditorContext`.** A surface becomes another client of the same cue-edit session the frontend operates. Live/Blind mode applies equally — in Blind mode a fader edit persists silently into the cue without touching the live stage.
- **Submasters deferred.** v1 does not introduce a persistent fader-scaled playback layer. If concrete need emerges later, the leading candidate is "submaster = always-on `FxInstance` with the fader as its `intensityMultiplier`" — reuses the FX composition layer with no new layer in the spec.
- **Button targets in v1:** GO / Back / Pause on a cue stack; fire a specific cue by ID; Flash (hold = full, release = prior); Blackout / Grand Master toggle.
- **Flash = ephemeral Layer 4 write, released on note-off.** Press writes the property to max at Layer 4; release clears that entry, restoring whatever layer was underneath. Reuses existing direct-write plumbing; composition model stays at five layers.

### Mapping and bindings

- **Continuous binding targets in v1:** fixture property (`fixture-42.dimmer`) and group property (`front-wash.rgbColour`). Raw DMX channel is intentionally excluded — surfaces bind to logical properties, never to universe+channel. Palette / FX-trigger bindings deferred.
- **Profile format:** Kotlin classes, discovered by a registry, mirroring how fixtures are defined. Each supported device is a concrete class annotated `@ControlSurfaceType(typeKey, vendor, product)` and registered in a manual list inside `ControlSurfaceRegistry` (same pattern as [FixtureTypeRegistry.kt](../src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureTypeRegistry.kt)). The class body declares its controls via a small DSL (`motorFader`, `button`, `encoder`, `bank`) so repetitive layouts stay compact. No DB storage for profiles; the registry is the source of truth. Adding a device family = dropping a new `.kt` file into `src/main/kotlin/uk/me/cormack/lighting7/midi/devices/` and adding one line to the registry.
- **MIDI Learn ships in v1.** Profile-based binding is the default for known devices; MIDI Learn is the primary path for unknown devices and for overriding profile defaults.
- **Mapping scope: per project, in DB.** Bindings live alongside patches/cues on a project. Different projects, different rigs, different surface layouts.

### Banking

- **Both device-side and app-side banks, coordinated.** App-side banks are the source of truth (a binding is keyed by `(device, control, bank)`); physical bank switches on the device (e.g. X-Touch's A/B button) fire into the app as bank-change events, and the app can also switch banks independently via a binding of type `SetBank`.

### Transport and library

- **v1 ships USB MIDI only.** Architecture accommodates OSC, network MIDI (RTP-MIDI), and MSC behind the transport interface; none of them implemented in v1.
- **Library: `ktmidi` on the `libremidi` backend.** Cleaner API than `javax.sound.midi`; multiplatform-ready; production-grade on macOS without needing CoreMIDI4J separately. Native libs bundle per platform at jpackage time.

### Reference hardware

- **X-Touch Compact in Standard mode** is the primary reference device. Exercises touch-sensitive motor faders, encoder LED rings, illuminated buttons, device-side A/B layer switch, and 7-bit fader resolution. Other devices gain support through community-contributed profiles.

### Feedback and reconciliation

- **Motor where available, soft takeover otherwise.** Motorised surfaces drive the motor to match logical state automatically. Non-motor surfaces use "pickup mode" — ignore fader input until the physical fader crosses the logical value. Per-device-class default; not per-binding.
- **Touch suppression is mandatory** on touch-sensitive motor faders. While a fader is touched, outbound motor writes to that control are suppressed to avoid fighting the user's finger.

### Frontend shape

- **New top-level `/surfaces` route** for the mapping UI. Lists connected devices, shows per-device binding matrix, hosts MIDI Learn mode, manages banks.
- **Global Learn Mode** lives on `/surfaces`. Enter Learn mode, click a target, wiggle a physical control, binding created. No inline Learn in operator-mode views — keeps those clean.
- **Subtle binding badges on existing fixture / group / cue views** indicate bound controls (e.g. "X-Touch F3" chip on a group's dimmer bar). Helps operators orient during a show.

### Plan structure

- **Cue-authoring is paused after its Phase 0** (which has landed). Control surfaces run end-to-end through Phase 5 of this plan. Phase 6 of this plan (cueEdit integration) is gated on cue-authoring Phase 1 landing. After surface Phase 5, we either resume cue-authoring Phase 1 and then loop back for surface Phase 6, or we bundle cue-authoring Phase 1 into surface Phase 6 — decided at the time.

---

## Target experience

**Programming a rig with a surface:**

1. Plug in an X-Touch Compact. The `/surfaces` page shows it as connected with the seed profile loaded.
2. Bind fader 1 to the `front-wash` group's dimmer via MIDI Learn. The binding is scoped to bank A.
3. Open a cue for edit in Live mode. The fader automatically drives the cue's Layer 3 dimmer assignment for `front-wash`. Move the fader → stage changes → cue updates.
4. Close the editor. The same fader now writes Layer 4 direct writes. Move the fader → stage changes → fader's value is sticky under running effects.

**Running a show with a surface:**

1. Bind eight flash buttons across a performer spread.
2. Bind three buttons to GO / Back / Pause on the main stack.
3. Bind one button to Blackout.
4. Run the show entirely from the surface while the screen displays Program view for reference.

**Switching between rigs:**

1. Bank A = playback faders (one per sub-group).
2. Bank B = per-head manual control for rigging/focus.
3. Device-side A/B button or an on-screen bank switch swaps all bindings in one move. Motorised faders snap to new logical values; non-motor faders enter pickup mode until crossed.

---

## Phase 0 — Transport Foundation

**Goal**: stand up MIDI I/O with the same rigor as the existing `ArtNetController`. Per-device coroutines, conflated input/output channels, rate-limited outbound feedback, delta tracking, hot-plug detection. No mapping, no device profiles, no UI yet.

### Entry criteria
- None. This is the start of the plan.

### Exit criteria
- `ktmidi` (with `libremidi` backend) is a build dependency. Native libs bundled for macOS / Linux / Windows targets.
- `MidiController` interface lives in `src/main/kotlin/uk/me/cormack/lighting7/midi/` and parallels `DmxController`:
  - Sealed interface with one implementation per transport (Phase 0: `KtMidiController`). Future: `OscController`, `NetworkMidiController`.
  - Inbound: `input: Flow<MidiInputEvent>` structured events (NoteOn / NoteOff / CC / PitchBend / SysEx). Raw bytes parsed, velocity/value normalised to canonical ranges.
  - Outbound: `sendFeedback(message: MidiFeedbackMessage)` with per-control conflation — rapid updates to the same control coalesce to the latest value before transmit.
  - Delta tracking: the controller remembers the last sent value per `(channel, messageType, id)` triple and skips redundant transmits.
  - Rate-limited per-device transmission thread (default 60 Hz; matches the ArtNet 25 ms throttle in spirit).
- Hot-plug detection: ktmidi's device-changed events drive a `DeviceRegistry` that emits connect/disconnect notifications. Devices appear and disappear in the live list without restart.
- A `ConsoleEchoListener` test app prints every inbound event from every connected device and optionally echoes velocity back as LED brightness. Enough to validate the pipeline end-to-end with an X-Touch in Standard mode.
- Unit tests cover: input parser round-tripping for each message type; conflated outbound coalescing; delta suppression; hot-plug registry state transitions.

### Phase 0 work

- [x] Add `ktmidi-jvm` + `ktmidi-jvm-desktop` (0.11.2) to `build.gradle.kts`. Bumped Kotlin 2.1.21 → 2.2.21 and toolchain 17 → 24 (ktmidi's Panama FFM path needs Java 22+). No extra jpackage config needed — the Ktor plugin's existing distribution tasks bundle transitive native resources.
- [x] `MidiController.kt` (sealed interface) + `KtMidiController.kt` (implementation). Matches `ArtNetController`'s shape: dedicated single-thread context per device (`MidiThread-${handle.displayKey}`); per-`MidiControlKey` conflated `Channel<MidiFeedbackMessage>`; `select` loop in the thread merges a shared wake signal with a ~60 Hz ticker; delta suppression against `lastSentBytes` before calling `MidiSendTarget.send`.
  - Input events flow into a `MutableSharedFlow<MidiInputEvent>` (buffer 256, `DROP_OLDEST`). Consumers subscribe freely; no backpressure at this layer.
  - Test seams: `MidiSendTarget` / `MidiInputSource` / `MidiAccessSource` interfaces let unit tests drive the pipeline without loading the native libremidi binary.
- [x] `MidiInputEvent` sealed hierarchy: `NoteOn(channel, note, velocity: UByte)`, `NoteOff`, `ControlChange(channel, cc, value: UByte)`, `PitchBend(channel, value: UShort /*0..16383*/)`, `SysEx(bytes)`. `MidiMessageParser` handles channel-voice decode, running status, multi-packet SysEx accumulation, and swallows system real-time / system common interleaves.
- [x] `MidiFeedbackMessage` mirrors inbound structure for outbound; each exposes `controlKey: MidiControlKey` (for conflation) and `encode(): ByteArray`.
- [x] `MidiDeviceRegistry` with 1 Hz poll diff (libremidi has no native device-changed events — see Change log 2026-04-18). Emits `DeviceConnected(handle)` / `DeviceDisconnected(handle)` on a `SharedFlow<DeviceEvent>`; maintains a `StateFlow<List<MidiDeviceHandle>>`; auto-opens controllers on connect when `autoOpen` is true.
- [x] `ConsoleEchoListener.kt` integration test app under `src/test/kotlin/uk/me/cormack/lighting7/midi/` — subscribes to connect events, prints every inbound event, echoes `NoteOn` / `ControlChange` back for LED validation.
- [x] Unit tests covering parser round-trips, feedback conflation + delta suppression, device registry diff cycles. 26 new tests; all pass alongside the pre-existing suite (399 total).

### Phase 0 wiring

`State.midiRegistry` (lazy, in [State.kt](../src/main/kotlin/uk/me/cormack/lighting7/state/State.kt)) holds the registry; `State.initializeShow()` calls `midiRegistry.start(GlobalScope)` after `show.start()`. Nothing in the app consumes registry output yet — Phase 1+ picks up the `ConsoleEchoListener` baton.

### Phase 0 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiController.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/KtMidiController.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiInputEvent.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiFeedbackMessage.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiDeviceRegistry.kt`
- Updated: `build.gradle.kts` (dependency), jpackage config.

### Phase 0 verification

- Spike runs, prints events when X-Touch controls are moved.
- Unplug → reconnect the device; registry emits Disconnect then Connect without restart.
- Rapid fader movement produces coalesced outbound LED writes, not a flood.
- Unit tests pass.

---

## Phase 1 — Device Profiles

**Goal**: describe a specific piece of hardware as a Kotlin class, discovered by a registry, mirroring how fixtures work. Ship an X-Touch Compact profile class. No bindings yet — a profile is purely descriptive.

### Entry criteria
- Phase 0 exit criteria met.

### Exit criteria
- `@ControlSurfaceType(typeKey, vendor, product, portPattern)` annotation exists on classes representing a device family/mode.
- `ControlSurfaceDevice` base class (or interface) exists. Subclasses declare their controls via a small DSL in a `buildProfile()` override (or via `init { }` + DSL functions from the base) — mirrors the fixture pattern's spirit while keeping the 60+ X-Touch controls compact:
  ```kotlin
  @ControlSurfaceType("x-touch-compact-standard", vendor = "Behringer", product = "X-Touch Compact")
  class XTouchCompactStandard : ControlSurfaceDevice() {
      init {
          repeat(9) { i ->
              motorFader(id = "fader-${i+1}", cc = 1 + i, touchNote = 101 + i)
          }
          repeat(16) { i ->
              encoder(id = "enc-${i+1}", cc = 16 + i, ringCc = 48 + i, pushNote = 32 + i)
          }
          repeat(39) { i ->
              button(id = "btn-${i+1}", note = 8 + i, ledFeedback = LedFeedback.ON_OFF)
          }
          bank(id = "layer-a", inputNote = 84, name = "A")
          bank(id = "layer-b", inputNote = 85, name = "B")
      }
  }
  ```
- `ControlDescriptor` sealed hierarchy: `FaderDescriptor` (with optional `touchNote` and `motorCc`), `EncoderDescriptor` (with optional `ringCc` and `pushNote`), `ButtonDescriptor` (with `ledFeedback` capability), `BankButtonDescriptor`. Each carries a stable `controlId`, input and feedback message shapes, resolution, feedback capability.
- `ControlSurfaceRegistry` mirrors `FixtureTypeRegistry`:
  - Manual list of concrete device classes (`private val deviceClasses: List<KClass<out ControlSurfaceDevice>>`).
  - Lazy-computed `allTypes: List<DeviceTypeInfo>` (typeKey, vendor, product, controls, banks).
  - Lazy `typeKeyToClass: Map<String, KClass<out ControlSurfaceDevice>>` for instantiation by typeKey.
- X-Touch Compact profile class implemented: all 9 motor faders, 16 encoders with push, 39 buttons, A/B layer buttons.
- `DeviceMatcher` service subscribes to the Phase 0 `DeviceRegistry`. For each connected port, it finds a matching `@ControlSurfaceType` by vendor/product/portPattern and emits `DeviceAttached(port, deviceTypeKey, instance)`.
- REST route: `GET /api/rest/controlSurfaceTypes` (list registered device families — mirrors `/api/rest/fixtureTypes`).
- Unit tests: DSL builds the expected descriptor count for X-Touch, matcher identifies a mock device, controlId uniqueness enforced at registry load.

### Phase 1 work

- [x] `ControlSurfaceType.kt` — annotation (`typeKey`, `vendor`, `product`, `portPattern`).
- [x] `ControlSurfaceDevice.kt` — base class plus the DSL builder functions (`motorFader`, `fader`, `encoder`, `button`, `bank`).
- [x] `ControlDescriptor.kt` — sealed hierarchy (`FaderDescriptor`, `EncoderDescriptor`, `ButtonDescriptor`, `BankButtonDescriptor`) plus `BankDefinition` + enums `LedFeedback` / `FaderResolution` / `EncoderRingStyle`.
- [x] `ControlSurfaceRegistry.kt` — manual list + lazy maps, fail-fast validation on duplicate `typeKey` AND duplicate `controlId` within a device (stricter than [FixtureTypeRegistry.kt](../src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureTypeRegistry.kt) which silently last-writes-wins). `buildFromClasses` helper exposed internal for tests.
- [x] `devices/XTouchCompactStandard.kt` — 9 motor faders + 16 encoders with push & ring + 39 buttons + A/B layer banks.
- [x] `DeviceMatcher.kt` — subscribes to `MidiDeviceRegistry.events`, matches via `@ControlSurfaceType`, emits `DeviceAttached` / `DeviceDetached` / `UnmatchedDeviceConnected`. Attached instances surfaced via `StateFlow<Map<String, Attached>>`.
- [x] `routes/controlSurfaceTypes.kt` — `GET /api/rest/controlSurfaceTypes`. Discriminated-union `ControlDescriptorDto` with `@SerialName` tags for each descriptor subtype.
- [x] Tests — `ControlSurfaceRegistryTest` (14) + `DeviceMatcherTest` (5). Covers: descriptor counts, field wiring, regex portPattern fallback, duplicate-typeKey fail-fast, duplicate-controlId fail-fast, missing-annotation fail-fast, attach/detach/unmatched event flow, live `tick()` integration through a fake access source.

### Phase 1 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceType.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceDevice.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlDescriptor.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceRegistry.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/DeviceMatcher.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/devices/XTouchCompactStandard.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/routes/controlSurfaceTypes.kt`

### Phase 1 verification

- Plug in X-Touch → server logs `DeviceAttached(x-touch-compact-standard)`.
- `GET /api/rest/controlSurfaceTypes` returns the registered X-Touch profile with its controls and banks.
- Add a second device class with a duplicate `controlId` → registry load fails with a clear error at boot (fail-fast, same spirit as fixture-type validation).

---

## Phase 2 — Mapping Model + MIDI Learn

**Goal**: bind device controls to application targets. Add `ControlSurfaceBinding` persistence, the sealed `BindingTarget` model, REST/WS routes, and the MIDI Learn session machinery. No inbound routing yet (Phase 3) — bindings just exist in data.

### Entry criteria
- Phase 1 exit criteria met.

### Exit criteria
- `DaoControlSurfaceBinding` table exists with columns: `id`, `project_id`, `device_type_key` (string, references the `@ControlSurfaceType.typeKey` — like how `DaoPatch` stores `fixture_type_key`), `control_id`, `bank` (nullable, default = global), `target_type`, `target_payload` (JSON, shape depends on target_type), `takeover_policy` (nullable; inherits from device class default when null), `sort_order`.
- `BindingTarget` sealed types implemented:
  - `FixtureProperty(fixtureKey, propertyName)` — continuous.
  - `GroupProperty(groupName, propertyName)` — continuous.
  - `CueStackGo(stackId)` / `CueStackBack(stackId)` / `CueStackPause(stackId)` — discrete.
  - `FireCue(cueId)` — discrete.
  - `Flash(target: FixtureProperty | GroupProperty, max: UByte = 255u)` — momentary.
  - `Blackout` / `GrandMasterToggle` — global.
  - `SetBank(deviceTypeKey, bank)` — meta.
- REST routes under `/api/rest/projects/{id}/surfaceBindings`:
  - `GET` list; `GET /{id}` detail; `POST` create; `PATCH /{id}` update; `DELETE /{id}` remove.
- WebSocket messages `surfaceLearn.*`:
  - `surfaceLearn.begin { projectId, target, bank }` — server opens a Learn session; the next inbound MIDI event from any attached device that looks like an assignable control is captured.
  - `surfaceLearn.cancel { sessionId }`.
  - `surfaceLearn.captured { sessionId, deviceTypeKey, controlId }` — broadcast when capture completes; client then confirms (`surfaceLearn.commit`) or retries.
  - `surfaceLearn.commit { sessionId }` — persists the binding.
- Tests: CRUD round-trip, Learn session state machine, bank-scoped binding resolution.

### Phase 2 work

- [x] `DaoControlSurfaceBindings` table ([models/surfaceBindings.kt](../src/main/kotlin/uk/me/cormack/lighting7/models/surfaceBindings.kt)) + referrer on `DaoProject.controlSurfaceBindings` + schema registration + project-delete cascade. `bank` column is nullable so a binding can be declared global (resolves under any active bank).
- [x] `BindingTarget` sealed class with `@SerialName` discriminator + `BindingTargetJson` (classDiscriminator = "type"). `Flash` carries `target: BindingTarget` but `init {}` rejects anything other than `FixtureProperty` / `GroupProperty` and asserts `max in 0..255` — Open Question 7's enum-property exclusion is deferred to Phase 3 at the resolver level.
- [x] `ControlSurfaceBindingService` — per-project lock, `ensureLoaded` lazy DB hydrate, `resolve` with bank-specific-wins-over-global precedence. `FieldUpdate<T>` sealed two-state sentinel distinguishes "no change" from "set to null" for nullable fields in `update()`. `changes: SharedFlow<BindingChange>` is consumed by `Sockets.kt` for `surfaceBindingsChanged` broadcasts.
- [x] `MidiLearnSessionManager` — subscribes to `DeviceMatcher.events`, on each `DeviceAttached` launches a collector that reads `MidiController.input` via `MidiDeviceRegistry.controllerFor`. "Captureable" predicate: CC with value > 0 for faders; CC (any value) for encoder turns; NoteOn with velocity > 0 for encoder push + buttons; bank buttons explicitly never capture. Clock is injected so tests can drive `expireDueSessions` deterministically. `offerInput` is an internal test hook that bypasses the coroutine pipeline.
- [x] Routes in [projectSurfaceBindings.kt](../src/main/kotlin/uk/me/cormack/lighting7/routes/projectSurfaceBindings.kt). `PATCH` uses boolean `*Present` flags because kotlinx.serialization can't distinguish JSON omission from JSON-null without custom serializers.
- [x] WebSocket handlers in [plugins/Sockets.kt](../src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt). Per-connection `ownedLearnSessions: MutableSet<String>` scopes learn-event broadcasts to the originating client so two simultaneous `/surfaces` users don't race over Captured events.
- [x] Tests (25): `BindingTargetSerializationTest` (9) covers round-trips + discriminator shape + Flash validation; `ControlSurfaceBindingResolverTest` (6) uses `seedCacheForTest` to skip DB and exercises bank precedence; `MidiLearnSessionManagerTest` (10) covers state machine transitions + capturable filter + manual-clock timeouts. 418 → 443 passing.

### Phase 2 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/models/surfaceBindings.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/BindingTarget.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceBindingService.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiLearnSessionManager.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/routes/projectSurfaceBindings.kt`
- New tests: `src/test/kotlin/uk/me/cormack/lighting7/midi/BindingTargetSerializationTest.kt`, `ControlSurfaceBindingResolverTest.kt`, `MidiLearnSessionManagerTest.kt`, `FakeDatabase.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (messages + handlers + subscriptions)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt` (service + session-manager wiring + schema)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/models/projects.kt` (`controlSurfaceBindings` referrer)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/routes/projects.kt` (route mount + delete cascade)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/routes/router.kt` — no change (route mounted via `routeApiRestProjectSurfaceBindings` inside `routeApiRestProjects`)

### Phase 2 verification

- Create bindings via REST; re-read matches what was written.
- Start a Learn session via WS; move an X-Touch fader; receive `surfaceLearn.captured`; commit; binding exists in DB.
- Bindings scoped to bank=B don't resolve when bank=A is active (verified via resolver unit test — Phase 3 wires actual bank state).

---

## Phase 3 — Inbound Routing

**Goal**: physical control events produce application side effects. Faders write Layer 4 direct writes. Buttons fire GO/Back/Pause/FireCue/Flash/Blackout/Grand Master. App-side banks switchable. No feedback, no cueEdit integration yet.

### Entry criteria
- Phase 2 exit criteria met.

### Exit criteria
- `SurfaceInputRouter` subscribes to the `DeviceMatcher` input stream. For each inbound event:
  1. Resolve `(deviceTypeKey, controlId, activeBank)` to a `ControlSurfaceBinding`.
  2. Dispatch based on `BindingTarget` type.
- Fader / encoder continuous events:
  - `FixtureProperty` / `GroupProperty` targets → Layer 4 write via the existing `DirectWriteStore` + transient channel write (same path `updateChannel` uses). Group property writes fan out to members by the group's property-aggregator semantics.
  - Value mapping: 7-bit (0–127) → 0–255 via linear scale for sliders; colour components pick an axis per binding (not in v1 — groups' `rgbColour` is bound via MIDI Learn only to individual components in v1); position pan/tilt map to native unit range declared on the property.
- Button events:
  - `CueStackGo/Back/Pause` → call existing cue-stack service.
  - `FireCue` → call existing cue-apply service.
  - `Flash` on press → write property max at Layer 4 via `DirectWriteStore`; on release → clear that entry. Tracked per-binding so overlapping flashes don't clobber each other's restoration.
  - `Blackout` → toggle; when enabled, multiply final output by 0 at transmit time (new global scaler in `ArtNetController` / transmit pipeline). When disabled, restore.
  - `GrandMasterToggle` → flip global intensity scaler (starts as 1.0; toggle sets to 0.0 / 1.0). Properties in `DIMMER` / `UV` / `STROBE` category scaled at transmit.
  - `SetBank` → update active bank for the device. Bindings in the new bank take effect immediately; no reconciliation yet (Phase 4).
- App-side bank tracking: per-device, stored in session state (not DB — ephemeral).
- Device-side bank event (e.g. X-Touch A/B): emitted as a synthetic `SetBank` target from the device profile.
- Unit + integration tests: binding resolution, Layer 4 writes under an active binding, flash release restoration, bank switch changes resolver output.

### Phase 3 work

- [ ] `SurfaceInputRouter.kt` — central dispatch. Subscribes to input stream from `DeviceMatcher`.
- [ ] `ActiveBankState` service — in-memory per-device map. WS message `surfaceBank.set { deviceTypeKey, bank }` to drive it from the frontend.
- [ ] `FlashStateTracker` — per-binding saved-value registry for release restoration.
- [ ] Extend `DirectWriteStore` to support property-level writes (currently it's channel-level). Add a convenience layer that expands `(fixture, property, value)` to channel writes via the fixture patch.
- [ ] Blackout + GrandMaster global scalers: add a `TransmitModifier` hook in the transmit pipeline that `ArtNetController.sendCurrentValues()` consults before output. Parking already sits here; add this alongside.
- [ ] Tests.

### Phase 3 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ActiveBankState.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/FlashStateTracker.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt` (transmit modifiers).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/DirectWriteStore.kt` (property-level API).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (bank-switch WS handler).

### Phase 3 verification

- Bind fader 1 to `front-wash.dimmer` → move fader → Layer 4 sticky write visible on stage and under any running effect (the Phase 0 cue-authoring behaviour change).
- Bind button to `CueStackGo` → press advances the stack.
- Bind two buttons to `Flash` on different groups → overlap holds don't corrupt each other's release state.
- Bind button to `Blackout` → press → output goes dark (DMX transmit = 0); press again → restores.
- Bind button to `SetBank(deviceTypeKey, "B")` → press → bindings in bank A stop resolving, bank B bindings take effect.

---

## Phase 4 — Feedback & Reconciliation

**Goal**: close the loop. Motors drive faders to logical state; LEDs reflect button state; encoder rings reflect property position. Touch suppression and soft takeover implemented. Initial sync on device attach and bank change.

### Entry criteria
- Phase 3 exit criteria met.

### Exit criteria
- `SurfaceFeedbackPublisher` observes the composition model and pushes feedback:
  - On Layer 4 direct-write change for a bound property → if control has motor: send motor position. If LED ring: send value.
  - On cue-apply / cue fade → for bound continuous targets, the current Layer 3 contribution (resolved via the Phase 0 `LayerResolver`) drives feedback.
  - On flash-button state → LED on for active, off for idle.
  - On bank change → full resync of all controls in the new bank.
  - On device attach → full resync of all bindings for that device's current bank.
- Touch suppression: when a fader sends a `touch-on` event (note-on on the touch-note for the fader), the feedback publisher stops sending motor writes to that control until `touch-off`.
- Soft takeover (for non-motor bindings): per-control "armed" state. When a new logical value is set while the physical fader is at a different position, the control enters "pickup" mode — inbound fader events are ignored until the physical value crosses the logical value, then it re-engages. A small subscribe-able event (`surfaceControl.pickupState`) lets the frontend show an indicator.
- Rate-limited feedback: the Phase 0 controller's outbound coalescing handles the flurry; `SurfaceFeedbackPublisher` is allowed to emit at the composition-model's natural frequency (FX tick, cue-fade progress).
- Tests: motor suppression under touch, soft-takeover engage/disengage, initial-sync correctness across bank switch.

### Phase 4 work

- [ ] `SurfaceFeedbackPublisher.kt` — subscribes to: `DirectWriteStore` changes, FX composition outputs (new hook or existing listener), cue-apply events, flash-button state, bank-change events.
- [ ] Touch state tracker: map `(deviceTypeKey, controlId) → isTouched`, updated from inbound touch events.
- [ ] Soft-takeover state machine per `(deviceTypeKey, controlId)`. States: `engaged`, `pickup-awaiting-cross`. Transition on bank change / logical-value set.
- [ ] Feedback formatter: translate property values → message values for the target control (7-bit / 14-bit / LED-ring style / motor position in whatever encoding the profile declares).
- [ ] Extend `MidiFeedbackMessage` with encoder-ring styles and LED colour (if the profile supports it).
- [ ] Tests.

### Phase 4 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SoftTakeoverStateMachine.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt` (consult touch / takeover state on input).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt` — add a `CompositionListener` hook if one doesn't already exist for this.

### Phase 4 verification

- Apply a cue that sets `front-wash.dimmer = 200` → fader motor drives to 200.
- Hold the fader while the cue fades → motor stops; fader stays where user holds it; logical state still updates; on release, motor catches up.
- Switch banks → all bindings in new bank resync (motor faders snap; non-motor enter pickup).
- Move a non-motor fader across the logical value → engage event fires; further moves write live.

---

## Phase 5 — Frontend `/surfaces` route

**Goal**: user-facing surface configuration. Device list, binding matrix, MIDI Learn mode, bank management. Binding badges on existing views.

### Entry criteria
- Phase 4 exit criteria met.

### Exit criteria
- New route `/surfaces` in `lighting-react`:
  - Left column: connected devices (matched to profiles) + unmatched devices (raw MIDI enumerations).
  - Right column: for the selected device, a matrix of its controls × banks. Each cell shows the current binding or empty. Clicking an empty cell enters Learn mode for that cell.
  - Bank switcher at top: active bank indicator with click-to-switch.
  - Learn mode: modal / overlay with "move a control to bind" prompt; listens for the `surfaceLearn.captured` broadcast.
- `SurfaceDevicePanel` subscribes to a new `surfacesState` WS message: active devices, current banks, pickup states.
- Binding badges on `FixtureContent` / `GroupPropertiesSection` / `CueRow`: small "X-T F3" chip when a bound control is mapped to this element. Clicking the badge jumps to `/surfaces` with that binding selected.
- `EditorContext` has no changes in this phase — Phase 6 does the cueEdit integration.

### Phase 5 work (lighting-react)

- [ ] New route `src/routes/Surfaces.tsx`.
- [ ] New components: `SurfaceDevicePanel`, `BindingMatrix`, `LearnModeOverlay`, `BankSwitcher`.
- [ ] `src/api/surfacesApi.ts` — list devices, CRUD bindings, start/commit learn, set bank.
- [ ] `src/store/surfaces.ts` — WS state for `surfacesState`.
- [ ] Badge component `BoundControlBadge` and integration in `FixtureContent.tsx`, `GroupPropertiesSection.tsx`, cue list rows.
- [ ] Types mirror backend: `ControlSurfaceType` (the registry-exposed device family), `ControlSurfaceBinding`, `BindingTarget` discriminated union.

### Phase 5 verification

- Fresh project, plug in X-Touch → `/surfaces` shows it.
- Use the matrix to bind fader 1 to `front-wash.dimmer` via Learn → binding persists, visible on next load.
- Badge appears on `front-wash` in the Groups view.
- Bank switch changes which bindings the matrix displays as active.

---

## Phase 6 — cueEdit Integration

**Goal**: surface faders participate in cue-edit sessions. Same routing the frontend does — when a `cueEdit` session is open, fader writes go into the cue's Layer 3 via `cueEdit.setChannel`; otherwise they go to Layer 4.

### Entry criteria
- Phase 5 exit criteria met.
- Cue-authoring plan Phase 1 has landed (`CuePropertyAssignment` model + `cueEdit.*` messages exist).

### Exit criteria
- `SurfaceInputRouter` consults cue-edit session state before dispatching fader events:
  - If any client on the project has an open `cueEdit` session → fader events for `FixtureProperty` / `GroupProperty` targets route to `cueEdit.setProperty` against that cue (server-side call; no WS hop).
  - If no session → Layer 4 write as in Phase 3.
- Surface session ownership: surfaces participate in whatever cue-edit session is active on the project (server-side, no per-client scoping for MIDI). If two clients try to edit the same cue, the existing resolution (Phase 1 of cue-authoring — presumed "reject second beginEdit") applies.
- Live / Blind mode mirrors the frontend: in Blind, surface edits persist but don't hit the live stage (except via natural Layer 3 recomposition if the cue is active via a stack).
- Feedback publisher adjusts: in an active cue-edit session, feedback for bound continuous targets reflects the cue's current Layer 3 value rather than the composed live value. This makes "what you feel is what you edit" true for surfaces.
- Tests: surface fader move during an open cue-edit session writes the property assignment; re-triggering the cue reproduces the value; feedback motor matches Layer 3 during edit.

### Phase 6 work

- [ ] `CueEditSessionRegistry` (or extend whatever Phase 1 of cue-authoring landed) exposed to `SurfaceInputRouter`.
- [ ] Router branch: session-active → route via cue-edit service (server-side call into the same handler WS messages land in); session-idle → Layer 4.
- [ ] Feedback publisher: when session is active for a cue that covers a bound target, publish the Layer 3 value instead of the composed live value.
- [ ] Tests: integration test with a synthetic surface + a cue-edit session driving round-trip writes.

### Phase 6 files

- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt`
- Possibly updated: cue-authoring Phase 1's cue-edit service to expose a programmatic entry point (not just a WS handler).

### Phase 6 verification

- Open a cue for edit in Live mode → move a bound fader → cue's Layer 3 dimmer updates → stage shows the new value → close editor → re-trigger cue → reproduces the edit.
- Same in Blind → stage is unchanged during the edit; re-trigger after close reproduces.
- Feedback motor: while a session is open, motor follows the cue's Layer 3 value, not the composed live value (which might differ if other effects run).

---

## Reuse inventory (don't rebuild)

Backend:
- `ArtNetController`'s coroutine model — mirror the per-device / per-control coroutine + transmission-thread + conflated-channel pattern for `KtMidiController`.
- `DirectWriteStore` from cue-authoring Phase 0 — Layer 4 writes land here.
- `FxEngine` composition listeners — feedback subscribes here.
- `ParkManager` — the transmit-time override pattern. Blackout / Grand Master reuse this hook.
- Cue-stack service and cue-apply service — buttons call these unchanged.

Frontend:
- `EditorContext` from cue-authoring Phase 1 (when it lands) — reuse shape; surfaces don't need a separate context.
- WebSocket plumbing — surface state follows the existing pattern (`surfacesState` message).

## Out of scope

- **OSC transport** — architecture accommodates, not implemented.
- **Network MIDI / RTP-MIDI** — architecture accommodates, not implemented.
- **MSC (MIDI Show Control)** — deferred.
- **USB HID / vendor protocols** (Stream Deck, Loupedeck) — deferred.
- **Submasters** — not in v1.
- **Relative encoders** — v1 assumes absolute. Relative encodings (Mackie, binary offset, two's complement) deferred unless an X-Touch encoder mode forces the issue.
- **SysEx-driven LCD text** — deferred. (X-Touch Compact has no LCDs; if a later device has them, add then.)
- **Community profile sharing / import-export** — deferred. Device profiles are Kotlin classes in the repo; a contributor drops a `.kt` file into `midi/devices/` and adds one registry line. No runtime import/export.
- **Multi-operator concurrent surface editing** — one surface session per project; second attach still works for playback but learn is single-session.
- **Rollback / feature-flagging / read-compat shims** — not running in production.

## Open Questions

Flag these to the user before implementing.

1. **Cue-edit session ownership for surfaces (Phase 6).** Surfaces aren't WebSocket clients. When a cue-edit session is open for a cue, does a surface attach to that session automatically (server-side), or do surfaces have their own per-device session started via `/surfaces`? Current plan assumes automatic (project-scoped), matching what the frontend expects. Confirm when we get there.
2. **Grand Master scaling scope.** Which property categories get scaled by Grand Master? Proposal: `DIMMER` + `UV` + `STROBE` (intensity-like). Colour, position, settings unaffected. Confirm before Phase 3.
3. **Blackout semantics.** Does blackout kill all output, or only intensity categories? (Consoles differ — some blackout hold positions so re-engage looks intentional; others kill everything.) Proposal: intensity-only, same as Grand Master at 0.
4. **Bank switch during cue edit.** If a fader is bound in bank A and the user switches to bank B mid-edit, what happens to the cue's Layer 3 assignment that the bank-A fader was driving? Stays (edit already persisted), or reverts to pre-edit snapshot? Proposal: stays — it's already persisted.
5. **Device-side A/B layer vs app-side bank naming.** If the X-Touch sends its own A/B event, do we force app-side bank names to "A" / "B" for that device, or allow arbitrary names with the device-side event mapped explicitly? Proposal: allow arbitrary names; X-Touch profile declares that its A/B button emits `SetBank("A")` / `SetBank("B")` which the user can rename post-learn.
6. **Learn session across devices.** If two devices are attached and the user enters Learn mode, should the first movement from any device bind? Or is Learn scoped to a selected device? Proposal: scoped to a selected device in the UI.
7. **Takeover behaviour for enum / setting properties.** Fader bound to a setting (dropdown) property — does it map continuously (fader position → enum index) or is this binding type disallowed? Proposal: disallowed at bind time for enum properties; buttons only for enum settings.
8. **Hot-reconnect of a device mid-show.** When the X-Touch is unplugged and replugged, does it resume its prior bank and feedback state? Proposal: yes — bank is session state, persists across reconnect.
9. **Profile versioning.** If we rename or remove a `controlId` in a device class (e.g. refactor the X-Touch profile), how do already-persisted bindings survive? Proposal: `controlId`s are treated as a stable contract — additions are non-breaking; rename/remove requires an explicit mapping step (e.g. a companion-object `migrations: List<Pair<oldId, newId>>` the binding service consults at load, similar in spirit to Go migration directives). Lazy: leave dangling bindings marked "unresolved" in the UI until the operator rebinds.

## Handover checklist (end of every session)

- [ ] Update the [Status](#status) block — phase, most-recent-session date, next actions.
- [ ] Tick off completed bullets in the current phase's Work section.
- [ ] Append new decisions to [Decisions](#decisions) with the date.
- [ ] Append new questions raised to [Open Questions](#open-questions).
- [ ] Note deviations from plan in a **Change log** section at the bottom (create on first use).
- [ ] Commit this file alongside the code changes.

## Change log

**2026-04-19 (Phase 2 implementation)** — Three design choices worth noting:

1. **Payload serialized as text, not `json<T>`.** `DaoControlSurfaceBindings.targetPayload` is a `text` column encoded/decoded via `BindingTargetJson.encodeToString / decodeFromString` rather than Exposed's `json<T>()` helper. Reason: future `BindingTarget` variants can be added without touching any schema, and `BindingTargetJson.ignoreUnknownKeys = true` gives downgrade compatibility if an old build reads a DB written by a newer one. The resolver also carries a small `targetType` mirror column for list-view queries that don't need to parse the JSON.
2. **Per-connection `ownedLearnSessions` over global broadcast.** Learn-captured events are hot — multiple `/surfaces` clients could be listening at once. Broadcasting to all would create phantom capture hits in one tab when another tab's session captures. A per-connection `MutableSet<String>` filter on the learn-events flow keeps capture scoped to the originator. Binding-change broadcasts (`surfaceBindingsChanged`) go to all clients by design so UIs stay in sync.
3. **`FieldUpdate<T>` sealed sentinel over overloads.** The `PATCH` route needs to support nullable field updates, e.g. "clear takeoverPolicy" vs "don't touch takeoverPolicy". Overloading the service method got combinatorially messy quickly; a `FieldUpdate.NoChange` / `FieldUpdate.Set(value)` sentinel keeps the single-method surface readable and prevents silent drops of a `null` when the caller intended to clear the column. The REST layer bridges with `*Present` boolean flags because kotlinx.serialization without custom serializers can't distinguish omission from explicit null.

**2026-04-18 (same session as drafting)** — Device profile format changed from JSON-in-DB to Kotlin classes discovered by `ControlSurfaceRegistry`, mirroring `FixtureTypeRegistry` and the `@FixtureType` annotation pattern. Rationale: consistency with fixture definitions. Cascade edits across Phase 1 (removed `DaoDeviceProfile`, JSON resource seeds, and `deviceProfile` REST routes; replaced with `@ControlSurfaceType` annotation + DSL-based device class + registry + `controlSurfaceTypes` route). Binding table now references `device_type_key: String` instead of a DB profile FK.

**2026-04-18 (Phase 1 implementation)** — Two small design choices worth noting:

1. **`ControlSurfaceRegistry` fail-fast is strictly stricter than `FixtureTypeRegistry`.** `FixtureTypeRegistry` uses `.toMap()` which silently last-writes-wins on duplicate `typeKey`. For surfaces, duplicate typeKeys would break persisted bindings (bindings reference typeKeys as a stable contract — see Open Question 9), so `ControlSurfaceRegistry.buildFromClasses` throws `IllegalStateException` on duplicate typeKey *and* on duplicate `controlId` within a single device class. The logic is extracted into `internal fun buildFromClasses(classes)` so tests can exercise validation paths without mutating the live class list.
2. **`DeviceMatcher` surfaces unmatched devices as events, not silence.** The plan said "find a matching `@ControlSurfaceType` by vendor/product/portPattern and emit `DeviceAttached`". In practice the matcher also emits `UnmatchedDeviceConnected` for devices that don't match any profile — Phase 2's MIDI Learn flow needs this signal to offer the "bind this unknown device" path in the UI. Keeping the event on the Phase 1 matcher (rather than inferring it from `MidiDeviceRegistry.events` + `attached.value` in Phase 2) keeps the consumer API symmetric: every inbound `Connected` yields exactly one `SurfaceEvent` of some kind.

**2026-04-18 (Phase 0 implementation)** — Three deviations from the plan, all captured here so the next session doesn't re-litigate them:

1. **Hot-plug via polling instead of native events.** The plan said "ktmidi's device-changed events drive a `DeviceRegistry`". ktmidi's upstream docs (`canDetectStateChanges = false`) are explicit that none of its desktop backends — `LibreMidiAccess`, `RtMidiAccess`, `JvmMidiAccess` — expose device-added/removed callbacks. `MidiDeviceRegistry` now diffs `MidiAccess.inputs ∪ MidiAccess.outputs` on a 1 Hz timer and emits the same `Connected` / `Disconnected` events on its `SharedFlow`. Public contract unchanged; implementation is just polled. If a future backend grows real state-change events, the poll loop can be replaced without touching downstream consumers.
2. **Kotlin bumped 2.1.21 → 2.2.21; JVM toolchain 17 → 24.** The `ktmidi-jvm-desktop` module targets JVM 22 bytecode (uses `java.lang.foreign.Arena`, Panama FFM). Kotlin 2.1.21's max `jvmTarget` was 23; ktmidi transitively pulled in kotlin-stdlib 2.2.x which caused subtle type-resolution regressions against the 2.1.21 compiler. User accepted the short-term risk of kotlin-compiler-server regressions in exchange for a clean compile path — see the comment on the `plugins { }` block in `build.gradle.kts`. Toolchain 24 (non-LTS) emits bytecode runnable on JDK 25 (LTS).
3. **`FixtureGroup.reversed()` → `FixtureGroup.reverseOrder()`.** Collateral fallout of the toolchain bump. `FixtureGroup<T>` implements `List<GroupMember<T>>` by delegation. JDK 21 added `reversed()` to `java.util.List` with incompatible return type, so Kotlin 2.2 on JVM 21+ targets can no longer resolve `group.reversed()` to the member (which returned `FixtureGroup<T>`) — it picks the inherited Java method instead (returning a `SequencedCollection<GroupMember<T>>`). Renaming to `reverseOrder()` is the minimum-blast-radius fix; two tests and two docs snippets updated. Public API break noted; pre-production, acceptable.
