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

**Phase**: 6 complete. Phase 7 unblocked and ready to consume `fx/AssignmentHealth.kt` + `fx/PersistedFixtureReferenceValidator.kt` (cue-authoring Phase 6, 2026-04-22).

**Most recent session**: 2026-04-23. Phase 6 cueEdit integration landed — surface fader writes now route through `cueEdit.setProperty` when a session is open, and motor feedback follows the cue's Layer 3 assignment instead of the composed live value.
Backend: new `CueEditSessionRegistry` in [CueEditSessionRegistry.kt](../src/main/kotlin/uk/me/cormack/lighting7/plugins/CueEditSessionRegistry.kt) — project-scoped view over per-connection `CueEditSessionState`s, with a `events: SharedFlow<Event>` that emits `Started` / `ModeChanged` / `Ended` / `AssignmentChanged` / `AssignmentCleared` / `AssignmentsReloaded`. Keyed by an opaque identity-equal `handle` (the WS connection passes its `AtomicReference<CueEditSessionState?>`). Threaded through every lifecycle / assignment method on `CueEditSessionHandler` via optional `registry: CueEditSessionRegistry? = null` + `handle: Any = sessionRef` parameters — handlers register on `beginEdit`, update on `setMode`, unregister on `endEdit` / disconnect, and fire assignment events from `setProperty` / `setChannel` / `clearAssignment` / `discardChanges`. Extracted `setPropertyForSession(state, session, ...)` as a programmatic entry point (bypasses sessionRef revalidation) so `DefaultSurfaceActions` can invoke it directly without a WS round-trip. State wiring: new `State.cueEditSessionRegistry` lazy; `SurfaceInputRouter` and `SurfaceFeedbackPublisher` both take a `cueEditSessionProvider: ((Int) -> CueEditSessionState?)?` that consults the registry by project id. Router branch: in `dispatchContinuous`, `FixtureProperty` / `GroupProperty` targets route through two new `SurfaceActions` methods (`writeFixturePropertyToCueEdit`, `writeGroupPropertyToCueEdit`) when a session is active; otherwise fall through to Layer 4 as before. Flash press / release stays on Layer 4 regardless — a momentary stage override is not an authoring gesture, matching frontend semantics. `DefaultSurfaceActions` serialises the 7-bit MIDI value to an assignment-string via a new `PropertyChannelResolver.serializeToAssignmentValue` helper — sliders scale through the declared `min..max` to `"0".."255"` (respects per-slider sub-ranges), colour properties emit `"#rrggbb"` grey (matches Phase 3's R/G/B fan-out), settings refuse. Publisher: `sessionAssignments` is an `AtomicReference<Map<AssignmentKey, String>>` patched incrementally on `AssignmentChanged` / `AssignmentCleared` and rebuilt from `session.snapshot` or the reloaded list on lifecycle events — no per-tick DB reads on the hot path. `computeValue7Bit` consults the cache first (with a `value7BitFromAssignment` parser that routes through `Layer3Resolver.parseAssignmentValue` and picks the primary channel's axis for Colour / Position targets) and falls back to DMX only when the cue has no assignment for the target. Lifecycle events trigger `resyncAllDevices()`; incremental events trigger `resyncEntriesMatching(key)` which walks `continuousByDisplay` and tests each entry against the changed `(targetType, targetKey, propertyName)` key. Test suite: 14 new tests (7 `CueEditSessionRegistryTest`, 4 router cueEdit branching, 3 publisher cueEdit feedback) — **693 total passing**. Existing `RecordingActions` stubs in `SurfaceInputRouterTest` / `SurfaceFeedbackPublisherTest` updated to satisfy the new `SurfaceActions` methods.

**Previous session**: 2026-04-19 (cont.). Phase 5 `/surfaces` frontend + one backend helper landed.
Backend: one new WS message pair `surfaceDevices.state` in [Sockets.kt](../src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt) — outbound shape `{ devices: [{ displayKey, displayName, typeKey, isMatched, hasInputPort, hasOutputPort, activeBank }] }` built from `midiRegistry.devices + deviceMatcher.attached + activeBankState.active`. Inbound object singleton pulls the current snapshot; a `combine()` flow on the same three sources pushes every change (`drop(1)` so only deltas, not the initial value). Fills the gap noted in Phase 4's "no WS message enumerates attached devices" — the frontend needed it to render the device panel without polling REST. Job cancelled in the same finally block as the other subscription jobs. No new tests (Phase 5 is mostly UI; the backend change is a pure fan-out over existing StateFlows and doesn't add logic).
Frontend (`lighting-react`): new route `/projects/:projectId/surfaces` in [App.tsx](../../lighting-react/src/App.tsx) + nav entry in the Setup group of [navigation.ts](../../lighting-react/src/navigation.ts). Types mirror the backend 1:1 in [surfacesApi.ts](../../lighting-react/src/api/surfacesApi.ts) — `BindingTarget` discriminated union (9 variants), `ControlDescriptor` discriminated union (4 kinds), plus the WS event payloads. The same file exports `createSurfacesWsApi(conn)` which subscribes to every `surface*` message and exposes six `subscribe*()` callbacks + the send helpers (`setBank`, `beginLearn`, `cancelLearn`, `commitLearn`, `setBlackout`, `setGrandMaster`). The WS-driven state caches the last snapshot of each stream so late subscribers get current values without an extra round-trip; on socket reopen the API re-requests the three initial snapshots (`surfaceDevices.state`, `surfaceBank.state`, `surfaceScaler.state`) so reconnect is transparent. REST for bindings lives in [store/surfaces.ts](../../lighting-react/src/store/surfaces.ts) as four RTK Query endpoints (`surfaceBindings`, `createSurfaceBinding`, `updateSurfaceBinding`, `deleteSurfaceBinding`) plus an endpoint for `controlSurfaceTypes`; a top-level `lightingApi.surfaces.subscribeBindingsChanged` handler invalidates the per-project cache on every server-side mutation, so two `/surfaces` tabs stay in sync. Hooks: `useSurfaceDevices`, `useActiveBanks`, `usePickupStates`, `useScalerState` are small `useState + useEffect` wrappers around the WS callbacks. `usePickupStates` returns a `Map<"displayKey|controlId", PickupChange>` so matrix rows can render an "awaiting pickup @ N" badge.
UI composition in [routes/Surfaces.tsx](../../lighting-react/src/routes/Surfaces.tsx): left column = device list (matched + unmatched, with bank chip + port-direction chips), right column = selected device detail (header + `BankSwitcher` + `BindingMatrix`). Global `Blackout` / `Grand Master` toggle buttons in the page header drive directly into `lightingApi.surfaces.setBlackout` / `setGrandMaster` and visually reflect the state from `useScalerState`. `BindingMatrix` ([components/surfaces/BindingMatrix.tsx](../../lighting-react/src/components/surfaces/BindingMatrix.tsx)) groups a device profile's controls into Faders / Encoders / Buttons / Bank buttons sections and renders a table row per control with: kind badge, resolved binding for the active bank (falling back to global, then to "unbound"), pickup-state indicator if present, edit/delete icons on hover, plus a `+` button to open the `CreateBindingSheet`. `CreateBindingSheet` offers both a direct REST create path and a "MIDI Learn" path — the Learn button opens `LearnModeOverlay`, which owns the session lifecycle (begin → waiting → captured → commit → committed) scoped to the active sheet's target + bank + policy; cancel and session-error are handled as dialog-closure paths. The per-control "capture pre-move" flow intentionally starts the Learn session at dialog-open, not at user action inside it, so the first physical move captures immediately (matches the frontend shape the backend `MidiLearnSessionManager` was designed around). `BindingTargetPicker` ([components/surfaces/BindingTargetPicker.tsx](../../lighting-react/src/components/surfaces/BindingTargetPicker.tsx)) is a compact form that switches between the 9 target kinds; continuous controls show fixture/group/property pickers (populated from existing `useGroupListQuery` / `usePatchListQuery` / `useProjectCueStackListQuery` so no new REST endpoints), button controls offer the full set including the recursive `Flash { target }` and direct `SetBank` / `Blackout` / `GrandMasterToggle` choices. Bank entry is a free-text input (empty = global) rather than a dropdown because the profile's bank list is available on the parent device, and per-binding bank can also be ad-hoc (future custom banks).
Badge integration: [components/surfaces/BoundControlBadge.tsx](../../lighting-react/src/components/surfaces/BoundControlBadge.tsx) is the shared chip — renders one small outline pill per matching binding with a device-key abbreviation (`x-touch-compact-standard` → `XT`) + the control's `label` from the profile, tooltip shows full details, click navigates to `/surfaces?binding=<id>` which the route consumes to auto-select the device and bank. Integrated into: `GroupPropertiesSection` (each `GroupPropertyVisualizer` receives a `nameExtra` badge via the existing slot), `FixtureContent` (new `FixtureBoundControlsRow` at the top of the properties section — renders one chip row per bound fixture-property, staying silent when nothing is bound), and `CueRow` (adds the cue's `fireCue` binding badge inline with the cue name; requires a new `cueId` prop threaded through from `RunPage.tsx`). The badge match logic unwraps `Flash` targets so flash-bound controls also show up on the underlying fixture/group.
Build: TypeScript clean across the frontend (`tsc --noEmit` passes), `vite build` passes. Backend: `compileKotlin` passes, full test suite still 520 passing (no Phase 5 tests — the changes are UI + a thin WS fan-out).
`SurfaceFeedbackPublisher` owns a reverse `packedChannelKey → List<ContinuousEntry>` index plus parallel `ledAll` / `flashLedIndex` / `blackoutLeds` / `grandMasterLeds` structures, all held in `AtomicReference`s and rebuilt on any of: device attach/detach, active bank change, binding add/update/remove/reload, fixture registration change, project change. Continuous feedback fires off the same `FixturesChangeListener.channelsChanged` callback that the WebSocket layer uses — each changed `(universe, channel)` looks up its list of bound controls and sends `ControlChangeFeedback(motorCc)` for motor faders, `ControlChangeFeedback(ringCc)` for encoders, guarded by `TouchStateTracker.isTouched` for motor suppression. LED feedback (`NoteOnFeedback(127)` / `NoteOffFeedback(0)`) drives from three sources: `FlashStateTracker.changes` (new `SharedFlow<FlashChange>` added to the tracker so press/release edges propagate), `GlobalScalerState.blackoutEnabled` / `grandMasterEnabled` combined via `flow.combine`, and full resyncs on attach / bank change. `SoftTakeoverStateMachine` is a `ConcurrentHashMap<"displayKey|controlId", Entry>` with per-control `(state, lastPhysical, target)` — `acceptInboundFader` checks whether a PICKUP-policy fader crosses its target (supporting both from-below and from-above pickup; single-step tolerance for MIDI jitter) and flips to ENGAGED on crossing. `SurfaceFeedbackPublisher.policyFor(deviceTypeKey, controlId)` derives the effective policy: explicit `BindingTakeoverPolicy` from the binding → else device-class default (motor faders = IMMEDIATE, non-motor = PICKUP). The router consults `SurfaceFeedbackHooks.acceptInboundFader` before `dispatchContinuous` so PICKUP suppression happens pre-binding-resolution; `onTouch` side-effects (catch-up resync on release) happen via the same hooks interface. New WS message `surfacePickup.changed { displayKey, controlId, state, target }` broadcasts PickupStateChange transitions so the frontend can render a pickup indicator. State wiring: `State.surfaceFeedbackPublisher` lazy, started in `initializeShow()` *before* `surfaceInputRouter` so the hooks are ready when the first inbound events arrive; router constructor takes `feedbackHooks = surfaceFeedbackPublisher`. `onProjectChanged()` re-attaches the fixture listener and triggers a full resync for every attached device so the new show's composition state drives the motors. 32 new tests (6 TouchStateTracker, 8 SoftTakeoverStateMachine, 9 SurfaceFeedbackPublisher, 2 FlashStateTracker flow-emission, 2 SurfaceInputRouter feedback-hook integration, 5 PropertyChannelResolver inverse-scale + describe), **520 total passing**.
`SurfaceInputRouter` subscribes to `DeviceMatcher.events`, attaches a per-controller input collector via `MidiDeviceRegistry.controllerFor`, and routes each `MidiInputEvent` through a three-step pipeline: match against the device profile's `ControlDescriptor` list (Fader/Encoder CC, Button note, Encoder push note, Fader touch note, BankButton), resolve against `ControlSurfaceBindingService` using `(deviceTypeKey, controlId, activeBank)`, dispatch by `BindingTarget` variant. Bank buttons short-circuit the binding lookup — they call `ActiveBankState.setBank(deviceTypeKey, bankId)` directly since `BankButtonDescriptor` is intentionally not user-bindable. `ActiveBankState` holds an ephemeral `deviceTypeKey → bank` map + `changes: SharedFlow<BankChange>` for WS broadcast + a `ConcurrentHashMap` fast-lookup on the hot path; `FlashStateTracker` is a lock-free `Set<Int>` of currently-held binding IDs so overlapping presses don't clobber each other's release semantics. Continuous events (`FixtureProperty` / `GroupProperty`) flow through `DefaultSurfaceActions`, which calls `DirectWriteStore.putProperty(fixture, propertyName, midi7Bit)` — a new API that uses `PropertyChannelResolver` to walk the fixture's `@FixtureProperty` list, match on `DmxSlider` / `DmxColour` / `DmxFixtureSetting`, and emit scaled `ChannelWrite` records. Sliders scale to each channel's native `min..max`; Colour fans the same 7-bit-scaled value to R/G/B; Settings skip silently (Open Question 7 — enum bindings are button-only). `DefaultSurfaceActions` then pushes the writes through `controller.setValue(channel, value, 0)` for immediate output. Flash press stores the max at Layer 4 (clamped by the slider's own `max`); release clears those entries and writes zero through the controller so the composition resolver takes over on the next tick. Button targets `CueStackGo` / `Back` / `Pause` / `FireCue` dispatch to `CueStackManager`; GO auto-activates an inactive stack at its first cue; Pause cancels the auto-advance timer (new `pauseAutoAdvance(stackId)` method); FireCue looks up the stack FK on the cue via a new `fireCue(state, cueId, scope)` method. Global scalers landed as `TransmitModifier` — a new interface in `dmx/TransmitModifier.kt` that `ArtNetController` applies after park resolution; `GlobalScalerState` implements it and classifies intensity channels (DIMMER / UV / STROBE) by walking fixtures on `fixturesChanged`, keyed as packed `(universe << 20) | channel` longs in an `AtomicReference<Set<Long>>`. Blackout and Grand Master are independent toggles — when either kills output, intensity channels output 0; everything else passes through. Toggle methods call `controller.requestTransmit()` on every attached controller so the change propagates within the next transmission tick, not up to 25 ms later. New WS messages: `surfaceBank.set` / `surfaceBank.state` on input; `surfaceBank.changed` / `surfaceBank.state` / `surfaceScaler.state` on both; `surfaceScaler.setBlackout` / `surfaceScaler.setGrandMaster` on input. Connection handler subscribes to `activeBankState.changes` and both scaler `StateFlow`s, broadcasts to the connection on each transition. State wiring: lazy `State.activeBankState`, `State.flashStateTracker`, `State.surfaceInputRouter` (constructed with `DefaultSurfaceActions(state)` that re-resolves `state.show.*` on every call so project switches don't leak stale references); `Show.globalScalerState.attach()` called in `Show.start()` after fixture load; `start()` / `detach()` symmetry through `Show.close()`; `surfaceInputRouter.start(GlobalScope)` in `initializeShow()`. 45 new tests (9 PropertyChannelResolver, 7 ActiveBankState, 4 FlashStateTracker, 6 GlobalScalerState, 13 SurfaceInputRouter, 4 new DirectWriteStore + 2 kotlin.coroutines-style refactor), **488 total passing**.

**See also**: [control-surface-followups.md](control-surface-followups.md) for non-blocking improvements surfaced during review (per-fader-event DB coalescing, targetType-as-enum refactor, and others).

**Next actions** (for the session that picks this up):
1. Manually validate Phase 6 end-to-end on the X-Touch Compact: open a cue for edit in Live mode via the frontend → wiggle a bound fader → cue's Layer 3 `dimmer` row updates (confirm via `GET /cues/{id}`) → stage reflects the new value → close the editor → retrigger the cue → reproduces the edit. Repeat in Blind mode to confirm the stage is unaffected during the edit and the value still persists.
2. Manually validate Phase 5 end-to-end on the X-Touch Compact: connect the device → `/surfaces` shows it. Click + on a fader row, open MIDI Learn, wiggle the physical fader → binding appears. Switch banks via the BankSwitcher → matrix rows update.
3. Phase 7 (binding validation) is ready to pick up — consume `fx/AssignmentHealth.kt` + `fx/PersistedFixtureReferenceValidator.kt` (cue-authoring Phase 6, 2026-04-22) rather than building a parallel `midi/BindingHealth`. See the Phase 7 section for the revised design.

**Per-phase tracker:**

| Phase | Summary | Status |
|-------|---------|--------|
| 0 | Transport foundation: ktmidi setup, `MidiController`, per-device coroutines, input/output channels, rate limiting, delta-tracked feedback, hot-plug detection | **Complete** |
| 1 | Device profile model: Kotlin `ControlSurfaceDevice` classes + `@ControlSurfaceType` annotation + `ControlSurfaceRegistry`, X-Touch Compact profile class | **Complete** |
| 2 | Mapping model + MIDI Learn: `ControlSurfaceBinding` table, `BindingTarget` sealed types, REST/WS routes, MIDI Learn session | **Complete** |
| 3 | Inbound routing: fader → Layer 4 writes, buttons → GO/Back/Pause/FireCue/Flash/Blackout/Grand Master, app-side banks | **Complete** |
| 4 | Feedback & reconciliation: motor drive, LED feedback, touch suppression, soft takeover, initial sync, device-side A/B layer coordination | **Complete** |
| 5 | Frontend `/surfaces` route: device list, binding matrix, MIDI Learn mode, bank management, binding badges on existing views | **Complete** |
| 6 | cueEdit integration: fader writes route through `cueEdit.*` when a session is active; feedback follows the cue's Layer 3 value; new `CueEditSessionRegistry` bridges per-connection sessions to surfaces | **Complete** |
| 7 | **Binding validation & dead-binding diagnostics**: load-time validation that `FixtureProperty` / `GroupProperty` bindings still resolve against the current patch; surface dead bindings in `/surfaces`. Consumes cue-authoring Phase 6's `fx/AssignmentHealth` + `fx/PersistedFixtureReferenceValidator` | Not started (prerequisite validator landed 2026-04-22) |
| 8 | **Non-blocking `setValues` on `DmxController`**: remove the `runBlocking` inside `ArtNetController.setValues`; move to suspend / `Deferred`-based commit; unify beat + wall-clock frame transaction | Not started |
| 9 | **Per-project `GlobalScalerState` scoping**: blackout / Grand Master per project rather than per show instance; preserve state across project switches | Not started |

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

- **Cue-authoring pause lifted (2026-04-23).** When this plan was drafted, cue-authoring was paused at its Phase 0 and surface Phase 6 was gated on cue-authoring Phase 1. Since then cue-authoring has run end-to-end through its Phase 8 in parallel: Phase 1 (`CuePropertyAssignment` model + `cueEdit.*` messages) landed 2026-04-19, Phase 6 shipped the shared `AssignmentHealth` / `PersistedFixtureReferenceValidator` in the `fx` package specifically to unblock surface Phase 7, and Phase 7 landed a property-level Layer 4 writer (`PropertyChannelWriter`) that coexists with `DirectWriteStore` rather than replacing the channel-level primitives surface Phase 3 already consumes. Both surface Phase 6 and Phase 7 are now independently pick-up-able.

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
              motorFader(id = "fader-${i+1}", cc = 1 + i, touchCc = 101 + i)
          }
          // Encoders 1–8: top horizontal row above the button block.
          repeat(8) { i ->
              encoder(id = "enc-${i+1}", cc = 10 + i, ringCc = 26 + i, pushNote = 0 + i)
          }
          // Encoders 9–16: right-side 2×4 block above the master fader.
          repeat(8) { i ->
              encoder(id = "enc-${i+9}", cc = 18 + i, ringCc = 34 + i, pushNote = 8 + i)
          }
          repeat(39) { i ->
              button(id = "btn-${i+1}", note = 16 + i, ledFeedback = LedFeedback.ON_OFF)
          }
          bank(id = "layer-a", name = "A", inputProgramChange = 0)
          bank(id = "layer-b", name = "B", inputProgramChange = 1)
      }
  }
  ```
- `ControlDescriptor` sealed hierarchy: `FaderDescriptor` (with optional `touchNote` / `touchCc` and `motorCc`), `EncoderDescriptor` (with optional `ringCc` and `pushNote`), `ButtonDescriptor` (with `ledFeedback` capability), `BankButtonDescriptor`. Each carries a stable `controlId`, input and feedback message shapes, resolution, feedback capability.
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
  - Value mapping: 7-bit (0–127) → 0–255 via linear scale for sliders; colour components pick an axis per binding (not in v1 — groups' `rgbColour` is bound via MIDI Learn only to individual components in v1); position pan/tilt map to native unit range declared on the property. **Backend-readiness note (2026-04-23):** cue-authoring Phase 8 added `WithWhite` / `WithAmber` traits + `SliderTarget.getSlider` fast paths for `"white"` / `"amber"`. White / amber fixture-property bindings now route identically to the existing `"uv"` path; the v1-scope decision is a frontend choice, not a backend gap.
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

- [x] [`SurfaceInputRouter.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt) — subscribes to `DeviceMatcher.events`; per-device collectors launched via `MidiDeviceRegistry.controllerFor`; event → descriptor match walks profile's `controls` list in O(n); dispatches via `SurfaceActions` port interface.
- [x] [`SurfaceActions.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceActions.kt) — port interface + `DefaultSurfaceActions` that resolves deps through `state.show` on every call (project-switch safe). Split from the router file so tests can inject a recording fake.
- [x] [`ActiveBankState.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/ActiveBankState.kt) — `ConcurrentHashMap` fast-lookup + `StateFlow<Map<String, String?>>` snapshot + `SharedFlow<BankChange>` for WS broadcast.
- [x] [`FlashStateTracker.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/FlashStateTracker.kt) — lock-free `Set<BindingKey>` of currently-held bindings; `pressed()` returns false on retrigger so MIDI repeats don't double-fire.
- [x] [`PropertyChannelResolver.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/PropertyChannelResolver.kt) — fixture/property → channel writes. Handles `DmxSlider` (single channel, scaled within `min..max`), `DmxColour` (3 channels), `DmxFixtureSetting` (silently skipped per Open Question 7). Exposes `scale7BitToDmx` / `scaleWithinRange` for external use.
- [x] [`DirectWriteStore.kt`](../src/main/kotlin/uk/me/cormack/lighting7/fx/DirectWriteStore.kt) — added `putProperty(fixture, propertyName, midi7Bit)` / `clearProperty(fixture, propertyName)` + group variants. Builds on the existing channel-level `put` / `clear` via `PropertyChannelResolver`.
- [x] Global scalers: [`dmx/TransmitModifier.kt`](../src/main/kotlin/uk/me/cormack/lighting7/dmx/TransmitModifier.kt) interface + `ArtNetController` / `MockDmxController` changes to apply modifiers after park resolution; `DmxController.requestTransmit()` gives scalers a push-to-transmit hook so toggles propagate immediately. `GlobalScalerState` implements the interface and classifies intensity channels at `fixturesChanged` time.
- [x] `CueStackManager` additions: [`pauseAutoAdvance(stackId)`](../src/main/kotlin/uk/me/cormack/lighting7/fx/CueStackManager.kt) cancels the active auto-advance job; [`fireCue(state, cueId, scope)`](../src/main/kotlin/uk/me/cormack/lighting7/fx/CueStackManager.kt) looks up the stack FK from DB then delegates to `activateCueInStack`.
- [x] WS messages: `surfaceBank.set` / `surfaceBank.state` / `surfaceBank.changed` + `surfaceScaler.state` / `surfaceScaler.setBlackout` / `surfaceScaler.setGrandMaster`. Handlers installed in `Sockets.kt`; subscription jobs for bank + scaler changes cleaned up in the finally block.
- [x] Show / State wiring: `Show.globalScalerState` lazy, `attach()` in `Show.start()` after fixture load, `detach()` in `Show.close()`. `State.activeBankState` / `State.flashStateTracker` / `State.surfaceInputRouter` lazy; `surfaceInputRouter.start(GlobalScope)` added to `initializeShow()`.
- [x] Tests — 45 new. `PropertyChannelResolverTest` (9), `ActiveBankStateTest` (7), `FlashStateTrackerTest` (4), `GlobalScalerStateTest` (6), `SurfaceInputRouterTest` (13, with a recording `RecordingActions` fake), `DirectWriteStoreTest` (+4 property-level). 443 → 488 passing.

### Phase 3 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/dmx/TransmitModifier.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceActions.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/ActiveBankState.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/FlashStateTracker.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/PropertyChannelResolver.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/GlobalScalerState.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt` (transmit modifiers + `requestTransmit`).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/DmxController.kt` (interface additions).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/MockDmxController.kt` (mirror implementation + `getEffectiveValue` / `transmitRequests` for tests).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/DirectWriteStore.kt` (property-level API).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/CueStackManager.kt` (`pauseAutoAdvance` + `fireCue`).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/show/Show.kt` (`globalScalerState` + attach/detach).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt` (service wiring + start).
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (bank + scaler WS messages, handlers, subscriptions).
- New tests: `src/test/kotlin/uk/me/cormack/lighting7/midi/{PropertyChannelResolverTest,ActiveBankStateTest,FlashStateTrackerTest,GlobalScalerStateTest,SurfaceInputRouterTest}.kt`
- Updated tests: `src/test/kotlin/uk/me/cormack/lighting7/fx/DirectWriteStoreTest.kt` (+4 property-level tests).

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

- [x] [`SurfaceFeedbackPublisher.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt) — subscribes to `deviceMatcher.events` / `bindingService.changes` / `bankState.changes` / `flashTracker.changes` / combined `globalScalerState.blackoutEnabled + grandMasterEnabled`. Registers as a `FixturesChangeListener` for the primary feedback source — every `channelsChanged` tick fans to bound motor faders + encoder rings via a packed-long reverse index. LED feedback drives off flash / scaler changes. Full resync fires on device attach, bank change, and project switch.
- [x] [`TouchStateTracker.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/TouchStateTracker.kt) — lock-free `ConcurrentHashMap`-derived set keyed by `"displayKey|controlId"`. Router calls `setTouched` on inbound touch notes; feedback publisher's `sendContinuousFeedback` consults `isTouched` before pushing motor writes.
- [x] [`SoftTakeoverStateMachine.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SoftTakeoverStateMachine.kt) — per-`(displayKey, controlId)` `(state, lastPhysical, target)` entries. `setLogical` transitions to `AWAITING_PICKUP` when policy is PICKUP and physical/logical diverge by > 1. `acceptInboundFader` returns false while in pickup until the value crosses the target (inclusive equality on either side). `forcePickup` unconditionally transitions — used on bank switches. Emits `PickupStateChange` on a `SharedFlow` for WS broadcast.
- [x] [`FlashStateTracker.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/FlashStateTracker.kt) — added `SharedFlow<FlashChange>` so press / release edges drive button LEDs through the feedback publisher. `clearAll` emits a release event for every held binding so LEDs don't end up stuck on.
- [x] [`SurfaceInputRouter.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt) — consults `SurfaceFeedbackHooks.acceptInboundFader` before `dispatchContinuous`, and fires `onTouch` on resolved touch events. `displayKey` threads through `route()` so per-device touch state is correctly scoped.
- [x] [`SurfaceFeedbackHooks.kt`](../src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt) — interface exposed from the publisher file. Test fakes implement it directly; the router takes a nullable instance so Phase 3 tests continue to work unchanged.
- [x] [`PropertyChannelResolver`](../src/main/kotlin/uk/me/cormack/lighting7/midi/PropertyChannelResolver.kt) — added `scaleDmxTo7Bit` / `scaleWithinRangeTo7Bit` inverse scalers + `describeFixtureProperty` (returns `List<PropertyChannel>` without reading a value). The inverse scalers use `(v * 127 + 127) / 255` for proper rounding so the round-trip is stable at the endpoints.
- [x] `MidiController` — relaxed from `sealed interface` to `interface` so test helpers can supply recording fakes. Production implementations are still constrained to the transport classes.
- [x] State / Sockets wiring — `State.surfaceFeedbackPublisher` lazy; started in `initializeShow()` before the router; `projectManager.projectChangedFlow` triggers `surfaceFeedbackPublisher.onProjectChanged()`. `Sockets.kt` adds a `pickupChangeJob` subscription that broadcasts `surfacePickup.changed` to every connection; cancelled in the `finally` block.
- [x] Tests — 32 new. `TouchStateTrackerTest` (6), `SoftTakeoverStateMachineTest` (8, including the flow-emission test), `SurfaceFeedbackPublisherTest` (9, using a recording `MidiController` fake + real `Fixtures` + `MockDmxController`), `FlashStateTrackerTest` (+2 for flow emission + clearAll-emits-releases), `SurfaceInputRouterTest` (+2 for feedback-hook integration), `PropertyChannelResolverTest` (+5 for inverse scalers + describe). 488 → 520 total passing.

### Phase 4 files

- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt` (also hosts `SurfaceFeedbackHooks`)
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/SoftTakeoverStateMachine.kt`
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/TouchStateTracker.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt` (feedback hooks + displayKey threading)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/FlashStateTracker.kt` (SharedFlow change events)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/PropertyChannelResolver.kt` (inverse scalers + `describeFixtureProperty`)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/MidiController.kt` (sealed → open interface)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt` (publisher wiring + project-change hook)
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (pickup WS message + subscription)
- New tests: `src/test/kotlin/uk/me/cormack/lighting7/midi/{TouchStateTrackerTest,SoftTakeoverStateMachineTest,SurfaceFeedbackPublisherTest}.kt`
- Updated tests: `FlashStateTrackerTest.kt`, `SurfaceInputRouterTest.kt`, `PropertyChannelResolverTest.kt`

### Phase 4 verification

- Apply a cue that sets `front-wash.dimmer = 200` → fader motor drives to the 7-bit representation of 200 (≈ 100) via the next `channelsChanged` tick. (Verified end-to-end in `channel change drives motor fader feedback`.)
- Hold the fader while the cue fades → `TouchStateTracker.isTouched` is true, `sendContinuousFeedback` short-circuits; on release, `resyncControl` drives the catch-up motor CC to the current logical value. (Verified in `touch suppression skips motor writes while fader is held`.)
- Switch banks → full resync fires for every attached device of that typeKey; motor faders pick up the new logical value via `setLogical`, non-motor faders transition to `AWAITING_PICKUP` via `forcePickup`. (Verified in `bank change triggers resync and fires LED feedback for new bank`.)
- Move a non-motor fader across the logical value → `SoftTakeoverStateMachine.acceptInboundFader` returns false until the crossing sample, then true; router drops pre-pickup events before binding resolution. (Verified in `inbound fader under PICKUP policy rejected until crossing`.)

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

### Phase 5 work (lighting-react + one backend helper)

- [x] Backend: `surfaceDevices.state` WS message pair in [Sockets.kt](../src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt). Inbound object singleton + outbound broadcast on the combined `midiRegistry.devices ∪ deviceMatcher.attached ∪ activeBankState.active` flow. Backend's device-enumeration gap identified in Phase 4.
- [x] New route `src/routes/Surfaces.tsx` mounted at `/projects/:projectId/surfaces` + a top-level `/surfaces` redirect to the current project.
- [x] New components under `src/components/surfaces/`: `BindingMatrix`, `LearnModeOverlay`, `BankSwitcher`, `BindingTargetPicker`, `BoundControlBadge`, `FixtureBoundControlsRow`. `SurfaceDevicePanel` duties handled inline in `Surfaces.tsx` (left-column device list + right-column detail). Directly-within-sheet create + edit flows live in `BindingMatrix.tsx`.
- [x] `src/api/surfacesApi.ts` — types for `BindingTarget` / `ControlDescriptor` / binding DTOs / WS events + `createSurfacesWsApi(conn)` factory registered in `lightingApi.ts` as `lightingApi.surfaces`.
- [x] `src/store/surfaces.ts` — RTK Query endpoints for binding CRUD + `controlSurfaceTypes` + WS hooks (`useSurfaceDevices`, `useActiveBanks`, `usePickupStates`, `useScalerState`). A top-level `subscribeBindingsChanged` handler invalidates the per-project `SurfaceBinding` cache on every server mutation.
- [x] `BoundControlBadge` integration: `GroupPropertiesSection` (as `nameExtra` on `GroupPropertyVisualizer`), `FixtureContent` (as `FixtureBoundControlsRow` at the top of the properties section), `CueRow` (inline with the cue name; threaded the new `cueId` prop through `RunPage.tsx`). Flash wrappers match their inner target so flash-bound controls also render on the underlying fixture/group view.
- [x] `Blackout` / `Grand Master` toolbar buttons in the `/surfaces` page header (they're the only controls global enough to warrant toolbar-level UI; per-device scaler state surfaces only via binding targets).
- [x] Nav item in the `Setup` group of `src/navigation.ts` (appears in sidebar + Cmd+K palette automatically).

### Phase 5 files

Backend:
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/plugins/Sockets.kt` (new `SurfaceDeviceInfo` + inbound/outbound messages + `buildSurfaceDevicesStateMessage` helper + subscription job).

Frontend (`lighting-react`):
- New: `src/api/surfacesApi.ts`
- New: `src/store/surfaces.ts`
- New: `src/routes/Surfaces.tsx`
- New: `src/components/surfaces/BindingMatrix.tsx`
- New: `src/components/surfaces/BindingTargetPicker.tsx`
- New: `src/components/surfaces/BankSwitcher.tsx`
- New: `src/components/surfaces/LearnModeOverlay.tsx`
- New: `src/components/surfaces/BoundControlBadge.tsx`
- New: `src/components/surfaces/FixtureBoundControlsRow.tsx`
- Updated: `src/App.tsx` (route registration)
- Updated: `src/navigation.ts` (nav item)
- Updated: `src/api/lightingApi.ts` (register `surfaces` WS API)
- Updated: `src/store/restApi.ts` (`ControlSurfaceType`, `SurfaceBinding` tags)
- Updated: `src/components/groups/GroupCard.tsx` (`groupName` prop passthrough; `BoundControlBadge` as `nameExtra` per property)
- Updated: `src/components/fixtures/FixtureContent.tsx` (`FixtureBoundControlsRow` at top)
- Updated: `src/components/runner/CueRow.tsx` (`cueId` prop + badge)
- Updated: `src/routes/RunPage.tsx` (passes `cueId` to `CueRow`)

### Phase 5 verification

- Fresh project, plug in X-Touch → `/surfaces` shows it under "connected devices" with the matched `x-touch-compact-standard` profile badge and correct port-direction chips.
- Use the matrix to bind fader 1 to `front-wash.dimmer` via Learn → binding persists across reloads and appears in `useSurfaceBindingsQuery` cache for any concurrent client.
- Badge appears on `front-wash` in the Groups view (as `nameExtra` on the dimmer visualizer). Clicking the badge navigates to `/surfaces?binding=<id>`, which auto-selects the device and its bank.
- Bank switch via `BankSwitcher` changes which bindings the matrix marks as active (the others are listed as "N other binding(s) on other banks" under the row).
- `useActiveBanks()` and the backend `ActiveBankState` stay in sync across tabs — switching bank in one tab updates the switcher in another within one WS round-trip.
- `Blackout` / `Grand Master` toolbar buttons drive `GlobalScalerState` and reflect back via `useScalerState` (persisted through server state even if one tab reloads).
- Pickup indicator: on a non-motor fader bound with `PICKUP` policy, moving the fader past the logical value clears the "pickup @ N" badge from the relevant matrix row.
- `tsc --noEmit` and `vite build` clean; backend `./gradlew compileKotlin test` clean (520 tests passing).

---

## Phase 6 — cueEdit Integration

**Goal**: surface faders participate in cue-edit sessions. Same routing the frontend does — when a `cueEdit` session is open, fader writes go into the cue's Layer 3 via `cueEdit.setChannel`; otherwise they go to Layer 4.

### Entry criteria
- Phase 5 exit criteria met.
- Cue-authoring plan Phase 1 has landed (`CuePropertyAssignment` model + `cueEdit.*` messages exist). **Met 2026-04-19.**

### Exit criteria
- `SurfaceInputRouter` consults cue-edit session state before dispatching fader events:
  - If any client on the project has an open `cueEdit` session → fader events for `FixtureProperty` / `GroupProperty` targets route to `cueEdit.setProperty` against that cue (server-side call; no WS hop).
  - If no session → Layer 4 write as in Phase 3.
- Surface session ownership: surfaces participate in whatever cue-edit session is active on the project (server-side, no per-client scoping for MIDI). If two clients try to edit the same cue, the existing resolution (Phase 1 of cue-authoring — presumed "reject second beginEdit") applies.
- Live / Blind mode mirrors the frontend: in Blind, surface edits persist but don't hit the live stage (except via natural Layer 3 recomposition if the cue is active via a stack).
- Feedback publisher adjusts: in an active cue-edit session, feedback for bound continuous targets reflects the cue's current Layer 3 value rather than the composed live value. This makes "what you feel is what you edit" true for surfaces.
- Tests: surface fader move during an open cue-edit session writes the property assignment; re-triggering the cue reproduces the value; feedback motor matches Layer 3 during edit.

### Phase 6 work

- [x] `CueEditSessionRegistry` introduced as a new project-scoped view over per-connection `CueEditSessionState`s; `CueEditSessionHandler` lifecycle methods thread it through as an optional parameter and fire events on mutation.
- [x] Router branch: session-active → route via `SurfaceActions.writeFixturePropertyToCueEdit` / `writeGroupPropertyToCueEdit`, which call the new `CueEditSessionHandler.setPropertyForSession` helper (server-side, no WS hop); session-idle → Layer 4.
- [x] Feedback publisher consults `sessionAssignments` cache built from the registry's events; `computeValue7Bit` returns the cue-assignment-derived 7-bit value when present, else falls back to DMX.
- [x] Tests: 4 router branching cases (FixtureProperty/GroupProperty × session/no-session), 3 publisher scenarios (begin-resync, AssignmentChanged, end-resync), 7 `CueEditSessionRegistry` lifecycle tests.

### Phase 6 files

- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceFeedbackPublisher.kt`
- Possibly updated: cue-authoring Phase 1's cue-edit service to expose a programmatic entry point (not just a WS handler).

### Phase 6 verification

- Open a cue for edit in Live mode → move a bound fader → cue's Layer 3 dimmer updates → stage shows the new value → close editor → re-trigger cue → reproduces the edit.
- Same in Blind → stage is unchanged during the edit; re-trigger after close reproduces.
- Feedback motor: while a session is open, motor follows the cue's Layer 3 value, not the composed live value (which might differ if other effects run).

---

## Phase 7 — Binding validation & dead-binding diagnostics

**Goal**: make "bindings silently stop working" impossible. When a fixture is renamed or a
property no longer exists, the binding is flagged in the UI instead of quietly failing at
dispatch time.

**Motivating review finding** (2026-04-19): `PropertyChannelResolver` walks a fixture's
`@FixtureProperty` list reflectively at dispatch time. If a fixture type is refactored — a
property renamed, a fixture replaced with a different mode, a patch row deleted — bindings
that reference the old shape silently return empty `ChannelWrite` lists. No log, no warning,
no UI indication. Operators discover it mid-show when a fader does nothing.

Scope is MIDI-specific but the pattern generalises: any persisted `(fixtureKey, propertyName)`
pair has the same failure mode. **The cross-cutting validator already exists** — cue-authoring
Phase 6 (landed 2026-04-22) shipped `fx/AssignmentHealth.kt` + `fx/PersistedFixtureReferenceValidator.kt`
ahead of this phase so surface Phase 7 can consume it directly rather than rebuilding. See the
revised Phase 7 design below.

### Entry criteria
- Phase 5 complete. Phase 6 not required — binding validation is independent.

### Exit criteria
- At project load and on every `fixturesChanged` event, `ControlSurfaceBindingService`
  resolves each binding's target against the current patch and tags it with a
  `BindingHealth` status.
- `BindingHealth` exposed via REST (`GET /api/rest/projects/{projectId}/surface-bindings`
  returns a `health` field per row) and WebSocket (`surfaceBindingsChanged` carries the
  health status).
- `/surfaces` UI shows dead bindings with a distinct marker (red outline + tooltip
  explaining why). Dead bindings are not silently dropped — the operator has to choose to
  delete or rebind.
- Diagnostic log on project load: `N of M surface bindings resolved cleanly; K dead`
  listing each dead binding with the missing fixture / property / group name.
- Tests: binding survives fixture rename with a dead marker; rebinding clears the marker;
  a new fixture matching the old key revives the binding (health transitions dead → ok).

### Phase 7 design

**Reuse `fx/AssignmentHealth.kt`, don't duplicate.** Cue-authoring Phase 6 shipped:

```kotlin
// fx/AssignmentHealth.kt  — already on main since 2026-04-22
sealed class AssignmentHealth {
    data object Ok : AssignmentHealth()
    data class MissingFixture(val fixtureKey: String) : AssignmentHealth()
    data class MissingGroup(val groupName: String) : AssignmentHealth()
    data class MissingProperty(val targetKey: String, val propertyName: String) : AssignmentHealth()
}
```

covering the fixture / group / property variants exactly. The earlier surface-side
`UnresolvableChannels(reason)` variant was speculative — after Phase 6 experience it
collapses into `MissingProperty` (if the property exists but the resolver can't find
channels, that's a patch/mode bug, not a binding-reference bug — handled separately).

Surface-specific additions that cue-authoring's validator doesn't cover:
- `MissingStack(stackId: Int)` — for `CueStackGo/Back/Pause` targets.
- `MissingCue(cueId: Int)` — for `FireCue` targets.
- `UnknownBank(deviceTypeKey: String, bankId: String)` — for `SetBank` targets.

Either extend the sealed class in `fx/AssignmentHealth.kt` with these variants (one
source of truth; cue-authoring consumers ignore them), or define a thin surface-side
`BindingHealth` wrapper (`sealed class BindingHealth { data class Targeted(val inner: AssignmentHealth) ; data class MissingStack(...) ; ... }`). **Preferred:** extend the
existing sealed class — cue-authoring's `DeadAssignmentsBanner` renders by variant
name, so unknown variants would just not render, no visual regression.

**Validation pass:**
- `FixtureProperty(fixtureKey, propertyName)` → call
  `PersistedFixtureReferenceValidator.validateTargetedReference(targetType = "fixture", targetKey = fixtureKey, propertyName = propertyName, fixtures)`.
- `GroupProperty(groupName, propertyName)` → same validator with `targetType = "group"`.
- `CueStackGo/Back/Pause` → stack exists in `CueStackManager.listStacks(projectId)`; else `MissingStack`.
- `FireCue` → cue exists in DAO; else `MissingCue`.
- `SetBank(deviceTypeKey, bank)` → profile exists in `ControlSurfaceRegistry` AND bank id
  is declared on the profile; else `UnknownBank`.
- `Flash(target, max)` → recurse on `target`.
- `Blackout` / `GrandMasterToggle` → always `Ok`.

**Integration:**
- New field `BindingService.Entry.health: AssignmentHealth` (or surface superset)
  computed on cache rebuild.
- Cache rebuild triggered by `fixturesChanged`, `patchListChanged`, `cueListChanged`,
  `cueStackListChanged` listeners (the validator is stateless — it takes a `Fixtures`
  snapshot per call, so no cache plumbing beyond the existing cache rebuild).
- Dispatch path in `SurfaceInputRouter` short-circuits dead bindings with a
  rate-limited warn log (mirror the 30s-per-signature throttle cue-authoring Phase 6
  uses in `applyCue`).
- Frontend: `BindingMatrix` row gets a `health` prop; dead rows render with an outline,
  tooltip explaining the failure, and a "Rebind" quick-action. Cue-authoring's
  `DeadAssignmentsBanner` is not directly reusable (different UI surface), but the
  plain-English reason-string logic can be copied.

### Phase 7 work — **Landed 2026-04-23**

- [x] Decided to extend `fx/AssignmentHealth.kt` in place with `MissingStack` /
  `MissingCue` / `UnknownBank` variants — one ADT for cue-authoring and surface
  consumers, no wrapper layer. Cue consumers simply ignore the new variants.
- [x] `BindingHealthEvaluator` pure function in `midi/BindingHealthEvaluator.kt`:
  `(BindingTarget, Context) → AssignmentHealth` where `Context` bundles the `Fixtures`
  snapshot + valid stack IDs + valid cue IDs + device-type list. Fixture / group
  variants delegate to `PersistedFixtureReferenceValidator.validateTargetedReference`;
  `Flash` recurses on its inner target.
- [x] Wired into `ControlSurfaceBindingService` via an optional
  `healthContextProvider: (projectId) → Context?` constructor arg. Cache rebuild
  (`ensureLoaded`, `create`, `update`) now runs the evaluator per binding and stores
  the result on `ResolvedBinding.health`. New `invalidateHealth(projectId)` method
  re-evaluates the cache in place and emits `BindingChange.Reloaded` iff anything
  actually changed.
- [x] `State` registers a `FixturesChangeListener` that calls `invalidateHealth` on
  `fixturesChanged` / `patchListChanged` / `cueListChanged` / `cueStackListChanged`.
  Re-attaches on project switch so the listener tracks the active show's `Fixtures`.
- [x] Extended `SurfaceBindingDto` with a `health: AssignmentHealth` field (defaults
  to `Ok`, back-compat with older payloads). The existing
  `surfaceBindingsChanged(RELOADED)` WS event already triggers a client refetch via
  RTK Query tag invalidation, so the WS payload itself didn't need a `health` field.
- [x] `SurfaceInputRouter.isDeadBinding` short-circuits dispatch for any binding
  whose `health !== Ok`; warn-logs the control id + health reason with a 30s-per-
  `(bindingId, signature)` throttle mirroring `routes/projectCues.kt`'s
  `maybeLogDeadAssignments`.
- [x] Frontend: `surfacesApi.ts` declares the `BindingHealth` TS discriminated union
  and adds `ControlSurfaceBinding.health?`. `BindingMatrix.tsx` marks dead rows with
  a destructive-styled outline, a `dead` badge carrying the reason as a tooltip,
  the reason rendered inline under the binding summary, and a new **Rebind**
  quick-action button that opens the existing edit sheet pre-populated with the
  current binding. `Surfaces.tsx` renders a header count badge ("N dead bindings")
  when any are present.
- [x] Tests: `BindingHealthEvaluatorTest` (9) covers each variant + Flash recursion;
  `ControlSurfaceBindingHealthTest` (7) covers the service-level wire-up — dead
  transition, revive, stack-delete, unloaded-project no-op, unwired-provider no-op,
  resolver keeps returning dead bindings so the router can log. `SurfaceInputRouterTest`
  grew two dead-dispatch cases. `./gradlew test` is clean.

### Phase 7 files

Backend:
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/AssignmentHealth.kt` — added
  `MissingStack` / `MissingCue` / `UnknownBank` variants to the shared sealed class.
- New: `src/main/kotlin/uk/me/cormack/lighting7/midi/BindingHealthEvaluator.kt` —
  delegates fixture/group validation to `fx/PersistedFixtureReferenceValidator.kt`
  and adds stack/cue/bank checks.
- New: `src/test/kotlin/uk/me/cormack/lighting7/midi/BindingHealthEvaluatorTest.kt`
- New: `src/test/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceBindingHealthTest.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/ControlSurfaceBindingService.kt`
  — optional `healthContextProvider` + `invalidateHealth(projectId)` + health tagged
  onto `ResolvedBinding` at cache install time.
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouter.kt` —
  `isDeadBinding` short-circuit + throttled warn-log.
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/routes/projectSurfaceBindings.kt`
  — `SurfaceBindingDto.health` field.
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt` — wires the
  `healthContextProvider` and registers a `FixturesChangeListener` that re-evaluates
  on `fixtures / patch / cue / cueStack` list changes and on project switch.
- Updated: `src/test/kotlin/uk/me/cormack/lighting7/midi/SurfaceInputRouterTest.kt`
  — dead-dispatch short-circuit cases.

Frontend:
- Updated: `src/api/surfacesApi.ts` — `BindingHealth` discriminated-union type + new
  `ControlSurfaceBinding.health?` field.
- Updated: `src/store/surfaces.ts` — re-export the new `BindingHealth` type.
- Updated: `src/components/surfaces/BindingMatrix.tsx` — dead-row outline, `dead`
  badge with tooltip, inline reason text, Rebind quick-action.
- Updated: `src/routes/Surfaces.tsx` — header dead-count badge.

### Phase 7 verification

- Rename a fixture in a patch → existing bindings transition to `MissingFixture`, UI marks
  them dead within one WS round-trip
- Click "Rebind" on a dead row → opens the create-binding sheet pre-filled with the control
  context; commit → health returns `Ok`
- Restart the backend with dead bindings → startup log enumerates them; `/surfaces` loads
  with the markers pre-applied
- Tests pass; `./gradlew test` clean

### Phase 7 open question

~~**Cross-cutting validator?**~~ — **Resolved 2026-04-22.** Cue-authoring Phase 6
shipped `fx/PersistedFixtureReferenceValidator.kt` + `fx/AssignmentHealth.kt`
specifically to be consumable here. Surface Phase 7 delegates `FixtureProperty` /
`GroupProperty` validation to the existing validator.

~~**Extend `AssignmentHealth` or wrap it?**~~ — **Resolved 2026-04-23 (Phase 7
landed).** Extended the sealed class in place with `MissingStack` / `MissingCue` /
`UnknownBank`. Cue-authoring consumers never construct those variants, and their
`DeadAssignmentsBanner`'s `describeHealth` is exhaustive over the cue-relevant
subset — the new variants are unreachable from that code path, so there's no UI
regression. Single ADT keeps serialisation and tests simple.

---

## Phase 8 — Non-blocking `setValues`

**Goal**: remove the per-commit `runBlocking` from `ArtNetController.setValues()` and
unify the beat / wall-clock `FxEngine` loops onto a single frame transaction. Reduces the
blocking cost on hot writer paths (FX ticks, MIDI surface, WebSocket) and eliminates
double-transmits when beat + wall-clock effects target the same universe in a ~20 ms
window.

**Motivating review finding** (2026-04-19): Every `transaction.apply()` today calls
`ArtNetController.setValues()`, which uses `runBlocking { … }` internally to wait for
every per-channel coroutine to acknowledge before returning. Individual acks are fast, but
three hot writer paths — beat tick (up to 24 × 300 / 60 ≈ 120 Hz), wall-clock tick (50 Hz),
MIDI surface input (up to 60 Hz) — plus bursty WebSocket writes, converge on the same
blocking primitive. The coupling is currently tolerable but is a latent scaling ceiling.

Related: the two `FxEngine` loops (beat + wall-clock) each construct their own
`ControllerTransaction` and apply independently, producing two ArtNet packets within a
~20 ms window whenever both timing sources target the same universe. The 25 ms transmit
throttle coalesces most of this, but not all.

### Entry criteria
- Phase 5 complete. Independent of Phase 6/7.

### Exit criteria
- `DmxController.setValues(…): Deferred<Unit>` (or suspend) — callers can `await()` only
  when they need read-after-write consistency.
- `ControllerTransaction.apply()` suspend variant that awaits all universes' commits in
  parallel; the blocking `apply()` stays as a convenience wrapper.
- `FxEngine` constructs **one transaction per frame** shared between beat and wall-clock
  processing where they run within the same tick window; independent transactions when
  the windows don't overlap.
- No regression in fade-in / fade-out timing, no regression in cue-apply latency.
- Micro-benchmark (new, in test sources) shows measurable improvement on a synthetic
  "burst of 512 channel writes across 4 universes" workload.
- Existing tests pass.

### Phase 8 design

**Two-step:**

1. **Add suspend `setValuesSuspend`** alongside the blocking `setValues`. Internally,
   `setValuesSuspend` sends each channel update and awaits its ack channel without
   `runBlocking`. The blocking path delegates to `runBlocking { setValuesSuspend(…) }`
   (no behaviour change for existing callers).

2. **Unify the two FxEngine loops' frame transaction** — introduce a
   `FrameTransaction` abstraction scoped to a tick-pair. Beat and wall-clock processing
   share one `ControllerTransaction` if their tick times are within a configurable fuzz
   window (default 10 ms); otherwise they operate independently. Requires coordination
   between the two loops — likely via a shared `AtomicReference<FrameTransaction?>` +
   short mutex around the open-close edge.

**Fallback:** if step 2 adds more complexity than it saves, ship step 1 and document the
double-transmit as acceptable given the 25 ms throttle coalesces most of it.

### Phase 8 work

- [ ] `ArtNetController.setValuesSuspend(values): Deferred<Unit>` — non-blocking variant
- [ ] Blocking `setValues` reimplemented as `runBlocking { setValuesSuspend(values).await() }`
- [ ] `ControllerTransaction.applySuspend()` — parallel await across universes
- [ ] Refactor `FxEngine.processBeatTick` and `processWallClockTick` to call the suspend
  variant
- [ ] Explore frame-transaction unification; accept or defer based on complexity
- [ ] Benchmark harness in `src/test/kotlin/.../dmx/BenchmarkSetValues.kt` (blocked on a
  test-side `DmxController` stub — the sealed interface is relaxed to an interface in the
  MIDI layer; do the same for `DmxController` if needed, or add a test-only production
  subclass)
- [ ] Smoke-check: start a script that adds and removes 100 effects per second. Confirm
  no visible DMX stutter, no coroutine leak (jmap / thread dump clean).

### Phase 8 files

- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/DmxController.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/ArtNetController.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/MockDmxController.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/dmx/ControllerTransaction.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/fx/FxEngine.kt`
- Updated: `docs/dmx-engineering.md`, `docs/fx-engineering.md` — remove the blocking
  caveat once step 1 lands.

### Phase 8 verification

- All existing tests pass.
- Run an FX-heavy script (a `GENERAL` script adding 50 effects in parallel) — coroutine
  thread dump shows no long-held waiters on `runBlocking`.
- Ramp a MIDI fader at full 60 Hz while a beat-synced Pulse runs on the same property —
  no dropped frames on stage; WS `channelState` updates match.

### Phase 8 open question

- Do we need read-after-write consistency in every current caller? If a few call sites can
  fire-and-forget (log a stale read, move on), they should migrate to the suspend path
  without `await()`. Audit during implementation.

---

## Phase 9 — Per-project `GlobalScalerState`

**Goal**: preserve Blackout and Grand Master state across project switches by scoping
`GlobalScalerState` to the project rather than the `Show` instance.

**Motivating review finding** (2026-04-19): `GlobalScalerState` is currently attached to
the active `Show`. `ProjectManager.switchProject` destroys the current `Show` and
constructs a new one, resetting the scaler to its defaults (blackout=false,
grandMaster=true). For an operator who has just hit Blackout and switches projects, this
is a surprise — the stage comes back up mid-switch. It also means state doesn't persist
across restarts.

v1 designed it this way deliberately (open questions 2 and 3 in Decisions above were
conservative), but Phase 5 of the frontend now surfaces blackout / grandmaster as
prominent header toggles, making the inconsistency more visible.

### Entry criteria
- Phase 5 complete. Independent of Phase 6/7/8.

### Exit criteria
- `GlobalScalerState` lifecycle is bound to the project, not the show instance.
- Blackout and Grand Master state survives project switches within a session.
- (Optional — see open question) State persists across backend restarts via a small DB row
  per project.
- `surfaceScaler.state` WS message reflects the new project's state on switch.
- Full resync re-fires on project switch so motor / LED feedback catches up.
- Tests: toggle blackout, switch project, switch back → state is preserved; restart backend
  → state restored (if persistence is in scope).

### Phase 9 design

Two options:

**A. Project-scoped instance, ephemeral state.** Move `GlobalScalerState` construction up
one level (from `Show` to `Project` or to `State.projectState`). State dies on project
delete but survives show re-creation within the same project.

**B. Project-scoped instance, persisted state.** As A, plus a single-row DB table
`project_scaler_state(project_id, blackout, grand_master)` that the service loads at
project activation and updates on every toggle. Trade-off: DB writes on every operator
toggle — small rows, infrequent, should be fine.

**Recommendation:** ship A first; add persistence only if operator feedback asks for it.

**Implementation notes:**

- `attach()` / `detach()` still follows the show lifecycle for DMX controller wiring
  (controllers belong to the show), but the `MutableStateFlow<Boolean>` state is owned by
  a project-level holder.
- On `ProjectManager.switchProject`, detach the old controllers, construct a new attach
  against the new show's controllers, but reuse the existing scaler-state holder if
  projectId matches; otherwise load from DB (option B) or init fresh (option A).

### Phase 9 work

- [ ] Refactor `GlobalScalerState` into two pieces: `GlobalScalerStateHolder`
  (project-scoped `MutableStateFlow`s) and `GlobalScalerTransmitModifier` (show-scoped
  `TransmitModifier` that reads from the holder)
- [ ] Update `State.kt` wiring — holder moves up, modifier stays in `Show`
- [ ] `ProjectManager.switchProject` hook: load / persist scaler state per project
- [ ] (Option B) migration: `project_scaler_state` table via Exposed `SchemaUtils`
- [ ] Tests: toggle → switch project → switch back → state preserved; concurrent project
  switches during pending toggle don't lose the toggle
- [ ] Update `docs/midi-control-surface-engineering.md` §Known limitations to remove the
  per-show caveat

### Phase 9 files

- Updated: `src/main/kotlin/uk/me/cormack/lighting7/midi/GlobalScalerState.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/state/State.kt`
- Updated: `src/main/kotlin/uk/me/cormack/lighting7/show/Show.kt`
- (Option B) New: migration in `src/main/kotlin/uk/me/cormack/lighting7/models/`

### Phase 9 verification

- Toggle Blackout → switch to Project B → switch back to A → Blackout still on.
- (Option B) Toggle Blackout → restart backend → state restored.
- Motor / LED feedback reflects the persisted state on project switch.
- Tests pass.

### Phase 9 open question

- **Persistence: in or out of scope for this phase?** Proposal: option A (ephemeral
  per-session) in Phase 9; option B (DB-persisted) deferred to a Phase 9.1 if operators ask
  for it. Rationale: preserving across session-restart is a behaviour change, worth a
  separate user confirmation.

---

## Reuse inventory (don't rebuild)

Backend:
- `ArtNetController`'s coroutine model — mirror the per-device / per-control coroutine + transmission-thread + conflated-channel pattern for `KtMidiController`.
- `DirectWriteStore` from cue-authoring Phase 0 — Layer 4 channel-level writes land here. Phase 3 already consumes `DirectWriteStore.putProperty` for fader dispatch; cue-authoring Phase 7 added a property-level `PropertyChannelWriter` alongside (not a replacement) for preset-toggle and the direct-write flash path — surfaces have no reason to migrate.
- `FxEngine` composition listeners — feedback subscribes here.
- `ParkManager` — the transmit-time override pattern. Blackout / Grand Master reuse this hook.
- Cue-stack service and cue-apply service — buttons call these unchanged.
- `fx/AssignmentHealth.kt` + `fx/PersistedFixtureReferenceValidator.kt` from cue-authoring Phase 6 — Phase 7 binding validation consumes these directly. See Phase 7 design.

Frontend:
- `EditorContext` from cue-authoring Phase 1 — reuse shape; surfaces don't need a separate context. (Landed 2026-04-19.)
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

**2026-04-23 (Phase 6 implementation)** — Four design choices worth noting:

1. **`CueEditSessionRegistry` as a project-scoped view, not a wholesale lifecycle owner.** The per-connection `AtomicReference<CueEditSessionState?>` already exists and drives WS semantics (one session per connection, cleared on disconnect). The simplest addition was a ConcurrentHashMap-keyed-by-identity registry that mirrors those references and exposes `activeSession(projectId)` + an events SharedFlow. Handler methods take the registry as an optional parameter (default `null`) so existing tests don't need to thread it. A different approach — moving session ownership into the registry and having WS connections be consumers — would have required reworking the disconnect cleanup path; deferred.

2. **Pre-baked `sessionAssignments` cache over per-tick DB reads.** The publisher needs "what does the cue assert for this target?" on every feedback event. The registry's `AssignmentChanged` event carries the new value string, and `Started` / `AssignmentsReloaded` carry the full list, so the cache stays consistent via incremental patches without ever opening a transaction. Three-way complexity (DB reads, transactional consistency, cache invalidation) collapses to a single `AtomicReference<Map<…, String>>` flip per event. Per-tick `computeValue7Bit` is a map lookup + a `parseAssignmentValue` call; no allocations on the no-session fast path.

3. **`setPropertyForSession` extracted as a programmatic entry point.** `CueEditSessionHandler.setProperty` validates session identity via `sessionRef.get()?.cueId == cueId`, then does the upsert. The surface router already has the validated session from the registry lookup, so the sessionRef round-trip is wasted work — and would have pulled a WS-level `AtomicReference` into `DefaultSurfaceActions`. Splitting the body out gave a clean non-WS caller path. The WS-level `setProperty` keeps its original signature and just delegates after validation.

4. **Flash still hits Layer 4 during a session.** The plan's "fader writes route dynamically by context" reads as "all continuous writes", but Flash is a momentary stage override, not an authoring gesture — it fires at press and clears at release. Persisting each press into Layer 3 (and firing a trigger on release to clear) would clutter the cue with per-flash rows the operator didn't ask for. The frontend already treats Flash as Layer 4 even in cue-edit mode; the router matches.

**2026-04-19 (Phase 5 implementation)** — Four design choices worth noting:

1. **One backend WS message added, not a whole new REST surface.** Phase 4's survey flagged that the backend had `DeviceMatcher.attached` + `midiRegistry.devices` + `activeBankState.active` but no consumer-facing way to fetch the aggregate. The instinct was a `GET /api/rest/surfaceDevices` route. Instead: one WS message pair (`surfaceDevices.state` in/out) + a `combine()` flow that pushes the full list on any delta. Reason: the UI needs live updates anyway (devices come and go, banks switch); a REST GET would still need a cache-invalidation channel, and the WS channel already exists. Fewer moving parts, one source of truth, no polling.

2. **Inline capture in the Learn overlay, not a separate "start → wiggle" two-step.** `MidiLearnSessionManager` is coroutine-based — once you `beginLearn`, the next captureable input *on any attached device of the right type* wins. The natural UX is: open the overlay, wiggle the control, see the match, confirm. So `LearnModeOverlay` kicks off the session in `useEffect` on open and consumes `surfaceLearn.captured` as the ready-to-bind signal. Side effect: closing the overlay with the X button cancels the session server-side. Phase 2's `ownedLearnSessions` filter already scopes captures to the originating client so two tabs don't race.

3. **Binding matrix shows per-control rows grouped by kind, not a raw grid.** The plan called for a "matrix of controls × banks." In practice a table with one row per control + a single "active binding (bank X)" column is more legible than an N-wide bank grid — bank switching is a top-level affordance (the `BankSwitcher`), not a per-cell axis. A secondary line under the row tells the operator "N other binding(s) on other banks" so the presence of other-bank mappings is still visible without cluttering the primary view.

4. **`BoundControlBadge` consumes the existing `nameExtra` slot on `GroupPropertyVisualizer`.** `GroupPropertyVisualizer` was already designed with a `nameExtra?: React.ReactNode` prop used by the "Virtual" dimmer label. Threading the same slot through `GroupPropertiesSection` kept the integration to three lines of code rather than refactoring every visualizer to take a new prop. For `FixtureContent`, `PropertyVisualizer` did *not* have an equivalent slot, so the simpler move was a compact chip row at the top of the properties block (`FixtureBoundControlsRow`) instead of threading `nameExtra` through every fixture property visualizer. Trade-off: fixture chips sit above the properties, group chips sit inline with them. Fine for v1; can be unified later if it grates.

**2026-04-19 (Phase 4 implementation)** — Five design choices worth noting:

1. **`SurfaceFeedbackPublisher` as the one composition-model listener.** The natural temptation is to wire up a tree of observers: one for `DirectWriteStore` changes, one for cue-fade progress, one for FX output, etc. Instead, the publisher registers exactly one `FixturesChangeListener.channelsChanged` — which fires on every DMX transmit tick where a channel changed. That single observation point covers every path in the composition model (Layer 1-4, cue fades, effects, direct writes, parks) because they all converge at the transmit boundary. No new FX engine hook needed, no coupling to cue-stack internals; if any layer contributes to an output change, the feedback fires. This keeps feedback decoupled from how the value got there.

2. **`SurfaceFeedbackHooks` as a pre-binding-resolution interface on the router.** The plan said "SurfaceInputRouter consults touch / takeover state on input." Implementing that as inline checks inside `dispatchContinuous` would have worked, but tangled the router with the publisher's internals. Extracting a small port interface (`onTouch`, `acceptInboundFader`) preserved the router's existing testability: router tests can inject a fake feedback hooks recorder, and the router consults hooks *before* it looks up the binding so takeover suppression short-circuits at the right place. The interface lives in the same file as `SurfaceFeedbackPublisher.kt` because it's only meaningful alongside the publisher; no need for a separate port file.

3. **Soft takeover uses from-below OR from-above crossing, not just from-below.** Most consoles spec pickup as "move the fader toward the target until you cross it", direction-sensitive. But that requires knowing the *direction* of the fader movement at the moment of `setLogical`, which we don't. Supporting both directions (target sits between two consecutive physical samples, regardless of direction) makes the pickup experience feel natural on either a rising or falling move. The tradeoff: if the user happens to be moving *past* the target in one direction and then reverses, pickup engages earlier than a strict "crossed the line you came from" implementation. Given the tolerance of ±1 for MIDI jitter, this is not worse than the alternative in practice.

4. **LED feedback for buttons is driven by *current state*, not edge events, at resync time.** On `sendFullResync`, the publisher reads `flashTracker.isActive(id)` / `globalScalerState.blackoutEnabled.value` directly and sends the matching `NoteOn` / `NoteOff` rather than waiting for the next state transition. That means if a Flash is held at the moment a device attaches, the button LED lights up immediately — not only on the next press. The edge-driven subscription (`flashTracker.changes.collect`) handles the steady-state case; the full-resync path handles late joiners.

5. **`MidiController` relaxed from `sealed interface` to `interface`.** Kotlin treats the `src/main` and `src/test` source sets as different modules for sealed class membership, so a recording fake in test code can't implement a sealed main-module interface. Rather than duplicate the transport scaffolding inside `main/test-helpers` or add a `@TestOnly` subclass to main, the cleanest move was to drop `sealed`. Production implementations are still constrained to the transport classes by convention — sealed never added safety here since any downstream implementation would be inside the same repo anyway.

**2026-04-19 (Phase 3 implementation)** — Four design choices worth noting:

1. **`SurfaceActions` as a port, not inline State access.** The obvious implementation is for `SurfaceInputRouter` to take `State` directly and call `state.show.cueStackManager.*` everywhere. Extracting an interface instead made testing drastically simpler (`RecordingActions` in `SurfaceInputRouterTest` records calls without needing a real show, database, or DMX controller) and the production impl is still a thin single-class file. `DefaultSurfaceActions` resolves `fixtures` / `directWriteStore` / `cueStackManager` / `globalScalerState` through `state.show` **on every call**, not once at construction — otherwise `ProjectManager.switchProject` would leak stale references into the router that was built against the old show.
2. **`TransmitModifier` alongside `ParkManager`, not a wholesale rewrite.** The plan said "analogous to the `ParkManager` path", but parks are a hard-coded built-in on the controller. The cleanest add was a new interface + modifier list that runs **after** park resolution (park still wins — matches console convention where park is the physical-override layer). This keeps Blackout / Grand Master as just another layered transform without special-casing them in `sendCurrentValues()`. Also: `GlobalScalerState` is the only initial implementor, but feedback-side effect sidechains in Phase 4 can reuse the same hook.
3. **Intensity classification keyed by packed `(universe << 20) | channel` longs.** First draft used a `Set<Pair<Int, Int>>` which allocates a `Pair` on every `modify()` lookup. The ArtNet transmit loop fires on every frame for every channel, so on a 4-universe rig that's 4×512 = 2048 map lookups/frame at 40 Hz. Packing into a `Long` is allocation-free and the existing `DirectWriteStore` already uses the same encoding — consistency win.
4. **Flash press stores channel writes at the slider's native `max`, not the raw `target.max`.** If a fixture's dimmer has `max = 200u` (say, a DmxSlider declared `max = 200u` to cap a too-hot lamp), a Flash with `max = 255` should still respect the lamp's cap. `DefaultSurfaceActions.buildFlashWrites` does `minOf(target.max, sliderMax)` to honour both. For Colour properties (where there's no per-property cap), the raw `target.max` passes through unmodified.

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
