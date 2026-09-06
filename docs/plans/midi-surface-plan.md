# MIDI surface — a picture of the desk, a selection, and strips

> **Document status: PROPOSED — nothing has landed.** The visual design is settled and checked in
> beside this plan at [`midi-surface-design/`](midi-surface-design/INDEX.md) — three static
> artboards: the Surfaces tab in run mode and in edit-bindings mode, and the control status
> vocabulary. The live canvas at
> <https://claude.ai/code/artifact/41182cc3-bcf1-4315-966c-ba3d7b5fd797> is a convenience copy,
> private to Chris; the checked-in files are the authority. This document is the engineering half:
> the model, the decisions and their reasons, and the session split. Where wording here and the
> artboards disagree, this plan wins on behaviour and the artboards win on layout and copy.
>
> Sessions record their SHAs and amendments in the header above as they land, in the
> `busk-layout-plan.md` style.

Related:
- [midi-control-surface-engineering.md](../midi-control-surface-engineering.md) — the subsystem
  as it is: transport, profiles, bindings, routing, feedback.
- [control-surface-plan.md](completed/control-surface-plan.md) — the plan that built it, and its
  open questions 1, 4 and 7, two of which this plan finally answers.
- [lighting-composition-model.md](../lighting-composition-model.md) §"Layer 2 — Programmer" and
  §"Looks, templates and layers" — what a surface write is, and what a press is.
- [busk-layout-plan.md](busk-layout-plan.md) — the library palette, the pad press, and the
  edit-mode pattern this view copies.
- [followups.md](followups.md): `FU-LOOK-MIDI-RECALL`, `FU-BUSK-PAGE-MIDI`,
  `FU-SYNC-BINDING-PAYLOAD-UUIDS`, `FU-FE-USE-TARGET-PROPERTIES`, `FU-SPEED-SURFACE-TAP-LED`.

## 1. Context

The brief was three things about the Surfaces tab. The Blackout and GM-open buttons do not belong
on it. Bindings should be made by dragging from a library onto a picture of the attached surface,
the way the busk view is configured. And the picture should show the state of the controls.

The first is a deletion. The second and third are a new view. But drawing the picture forced the
question the current binding grammar avoids: what should the surface be able to *do*? Today
`midi/BindingTarget.kt` names twelve targets — a fixture or group property, a speed master's tempo
or tap, stack Go / Back / Pause, fire cue, flash, blackout, grand master, set bank — and every one
is **fixed**: a control is wired to one thing forever. That is the QLC+ / Lightkey / Daslight model,
and it is why nine faders and sixteen encoders run out fast. Nothing names a Look, a template, a
busk pad or a page, and nothing can select a fixture, so the surface cannot drive the programmer
the way the busk view does: press a named thing onto a selection.

A survey of the consoles the composition model already measures itself against (its
§"Divergences from industry consoles") found one shape underneath all of them. The desk keeps a
**selection**; encoders drive the attributes of whatever is selected; attribute-bank buttons page
the encoders through intensity, position, colour and beam; and a fader wing in channel mode is a
row of **strips** — fader is intensity, the button under it selects, the encoder above it is one
attribute of the current bank. grandMA3 maps MIDI remotes to executors and keeps its encoders
selection-relative; Titan's attribute select buttons choose what the wheels control and its
Triggers map MIDI onto playbacks and palettes; Eos's fader wings go into channel mode, and its OSC
surface — which can drive faders and cues — notably has no way to reach the active wheels, so
third-party tools emulate an intensity encoder instead. The X-Touch Compact physically *is* eight
strips and a right block, which is how MagicQ and Eos users map it.

So the plan is the brief plus one thing: keep fixed bindings, which are right for cues, stacks,
speed and desk actions, and add a **selection-relative layer beside them** — a desk-side
selection, selection targets, strips, and an encoder bank — so the surface reaches the programmer
through the same press the busk view uses. Looks, templates, pads and pages then fall out of the
same two rules (a press needs a selection or a fixed target set), rather than each needing a
special case.

## 2. Decisions taken

- **D1 — Blackout and GM leave the view; the targets stay.** The `ScalerToolbar` in
  `routes/Surfaces.tsx` goes. `BindingTarget.Blackout` / `GrandMasterToggle`, the `surfaceScaler.*`
  WS family, `GlobalScalerState` and the ShowBar's own toggles are untouched. The view is where the
  operator wires the desk, not where they run it.
- **D2 — one desk, one selection, server-owned.** A project-scoped, transient
  `DeskSelection` (a `StateFlow<List<CueTargetDto>>`) lives on `State`, is cleared on project
  switch, and is never persisted — the composition model's "exactly one programmer, shared by every
  client" argument applies verbatim: DMX is one byte per channel, so two selections would force a
  "whose press wins?" policy nothing expresses. WS family `selection.*`: `selection.state` is the
  snapshot on connect and the broadcast on every change (one frame type, a `StateFlow`, so the
  snapshot rule is met); `selection.set` / `.toggle` / `.clear` are the writes, with no reply
  (reply convention 3). The busk view's `useBuskingSelection` becomes a reader and writer of this;
  the programmer page's `selectionSlice` (`programmer` scope) publishes its `targetKeys` to it and
  highlights rows from it, keeping anchor and range local. The AI's `get_current_state` reports it.
- **D3 — selection-relative targets, four of them.** `SelectionProperty(propertyName)` on a fader
  or encoder writes the property on every selected target through the existing group and fixture
  paths (a group in the selection fans exactly as `writeGroupProperty` does today);
  `SelectTarget(target, mode = TOGGLE | REPLACE)` on a button; `ClearSelection`; and
  `LocateSelection`, through `LocateManager.toggle`. A `SelectionProperty` write with an empty
  selection is dropped with a debug log — the legend's "no selection" state — never widened to
  "everything".
- **D4 — a strip is one binding, declared by the profile.** `ControlSurfaceDevice` gains a
  `strip(id, fader, select, encoder?, flash?)` DSL call and `XTouchCompactStandard` declares
  `strip-1..8` (fader-*n*, btn-(24+*n*), enc-*n*, btn-*n*) and `strip-master` (fader-9, btn-33, no
  encoder). A strip binding is one row in `control_surface_bindings` with `controlId` = the strip
  id and target `Strip(target: CueTargetDto)` — a group or a fixture. Resolution is **direct
  binding first, strip second**: `ControlSurfaceBindingService.resolve` answers a control's own row
  if it has one, else derives from the strip containing that control — fader → `dimmer`, select →
  `SelectTarget(TOGGLE)`, encoder → the device's current encoder-bank property (D5), flash →
  `Flash(dimmer, 255)`. The inspector's *Fader only…* is that rule made visible: it deletes the
  strip row and creates the four single rows. No new table, no new column.
- **D5 — the encoder bank is per-device session state.** `EncoderBankState` mirrors
  `ActiveBankState`: `deviceTypeKey → propertyName`, default `dimmer`, a `StateFlow` per device for
  the snapshot rule, WS family `surfaceEncoderBank.state` / `.set`. Button target
  `EncoderBankSet(propertyName)` switches it; the LED of the button naming the active property is
  lit, the same way bank buttons light. A change rebuilds the feedback index (the strip encoders'
  primary channels moved) and re-arms takeover on every strip encoder, because their meaning just
  changed under the operator's hand. The vocabulary is the properties `PropertyChannelResolver`
  already accepts on a continuous control — sliders and colour — so `strobe` is in and enum
  settings stay out (control-surface plan open question 7 still stands).
- **D6 — records on buttons, uuid-addressed, each with one behaviour.**
  `ApplyLook(lookUuid)` always presses the Look onto **its own fixtures**, the
  `resolveLookToggleTargets` empty-targets rule; a Look with a deferred effect is refused at bind
  time (`BINDING_LOOK_NEEDS_SELECTION`) and, if it gains one later, reads as health
  `LookNeedsSelection` and the press is dropped. `PressTemplate(templateUuid)` always presses
  onto the **desk selection** through `ProgrammerLayerStack.toggle` with the template's derived
  family mask, siblingless; a generic template with an empty selection is dropped. `PressPad(padUuid)`
  runs the busk pad's own plan — record, bank, solo siblings — with the desk selection as the
  press's targets; `routes/buskPress.kt`'s plan-then-apply is extracted into `BuskPressService` so
  the route and the router share one implementation. `BuskPageNext` / `BuskPagePrev` /
  `BuskPageSet(pageUuid)` move a desk-side **current busk page** (`BuskPageState`, a `StateFlow`,
  transient; WS `busk.pageState` / `busk.setPage`), which the busk view follows instead of only its
  `?page=`; the URL keeps mirroring it. Every new variant carries a **uuid**, never an int id, for
  the reason `FU-SYNC-BINDING-PAYLOAD-UUIDS` records.
- **D7 — the screen shows what the hardware is told.** One keyed stream, `surfaceControls.state`
  (a full per-device snapshot on connect and on attach / bank change / index rebuild) and
  `surfaceControls.changed` (conflated deltas, at most ~20 Hz per device), carrying per control:
  fed-back value (0..127, or `null` for mixed / unbound), `touched`, `led` (on / off / none) and
  `ring`. Its single source is `SurfaceFeedbackPublisher`'s send sites — `sendControlFeedback`,
  `sendLed`, `onTouch`, the resync paths — plus the inbound physical value for a non-motor fader,
  which the hardware is not told but the operator wants to see beside its pickup target. The
  frontend never recomputes a control's state from DMX; if the picture and the desk disagree, the
  publisher is wrong, which is the bug worth finding.
- **D8 — the picture is data in the profile, not a React component per device.** `DeviceTypeInfo`
  gains a `layout`: named regions (`strips`, `right`, `master`) and a grid cell per control
  (`region, col, row`). `XTouchCompactStandard` declares it once. `SurfacePanel` draws any profile
  that has one and falls back to the current grouped table for a profile that does not. A second
  device is one profile file, as today.
- **D9 — edit mode is the busk view's edit mode.** *Edit bindings* replaces the inspector with the
  library palette; controls grow the pad's remove cross; a lifted **row** (group or fixture) lands
  on a strip, a lifted **chip** on one control; a drop is a `POST /surface-bindings` (or a `PATCH`
  when the control already holds one, replacing it) and saves at once; *Done* only leaves the mode.
  The palette joins `DeskDndProvider` — the app's one drag context — rather than nesting its own,
  and its rows are the busk palette's rows plus the new kinds (§4). MIDI Learn survives, as the
  path for an unmatched device and for "which button is this", reached from the inspector.
- **D10 — a mixed selection means no feedback.** When the selected heads disagree on a
  `SelectionProperty`'s value the publisher sends nothing to the motor and drives the ring to its
  off state (`EncoderRingStyle`-specific; the X-Touch's is confirmed on the rig in §9), and the
  stream reports `value: null`. A turn or move writes all of them and the state becomes uniform.
  Takeover follows the control's policy exactly as for a fixed binding: a non-motor fader arms
  PICKUP against the uniform value, and against nothing when mixed.
- **D11 — sync: additive, tolerant, formatVersion 11.** New target variants are new `type`
  discriminators inside the opaque `targetPayload` string; the exporter and importer do not change.
  What must change is the **reader**: kotlinx fails a whole load on an unknown discriminator, so
  `ControlSurfaceBindingService` decodes per row and keeps an undecodable row as health
  `UnknownTarget(type)` (dead, rebindable, never dropped) instead of refusing the project. That is
  what lets `minReader` stay at 5 while the writer bumps to 11. Folding `FU-SYNC-BINDING-PAYLOAD-UUIDS`'s
  first half — uuids on `FireCue` / `CueStack*` too — into the same bump is §11's first question.
- **D12 — no admin-only command joins the socket.** Selection, encoder bank and busk page are
  operator gestures, the same tier as `surfaceBank.set`; `FU-AUTH-WS-PER-MESSAGE`'s trigger does
  not fire. Binding writes stay on REST, behind the gate they have today.

## 3. The model

### 3.1 The target grammar

`BindingTarget` after this plan, grouped as the library groups them. Existing variants unchanged;
new ones marked ●.

| Family | Variant | Control | What a press or move does |
| --- | --- | --- | --- |
| Fixed continuous | `FixtureProperty`, `GroupProperty`, `SpeedMasterBpm` | fader, encoder | as today |
| Fixed discrete | `CueStackGo/Back/Pause`, `FireCue`, `SpeedMasterTap`, `Blackout`, `GrandMasterToggle`, `SetBank`, `Flash` | button | as today |
| Selection ● | `SelectionProperty(propertyName)` | fader, encoder | writes the property on every selected target (D3) |
| Selection ● | `SelectTarget(target, mode)` | button | toggles or replaces the selection with one group or fixture; LED lit while in the selection |
| Selection ● | `ClearSelection`, `LocateSelection` | button | as named; Locate's LED lit while its targets are located |
| Strip ● | `Strip(target)` | a strip id | fader = dimmer, select, encoder = bank property, flash (D4) |
| Desk ● | `EncoderBankSet(propertyName)` | button | switches the device's encoder bank (D5); LED lit when active |
| Record ● | `ApplyLook(lookUuid)` | button | the Look onto its own fixtures; LED from `appliedState` = ALL |
| Record ● | `PressTemplate(templateUuid)` | button | the template onto the selection; LED lit when it covers every selected head |
| Record ● | `PressPad(padUuid)` | button | the busk pad's press, solo included; LED = the pad's ring |
| Busk ● | `BuskPageNext`, `BuskPagePrev`, `BuskPageSet(pageUuid)` | button | moves the current busk page; `BuskPageSet`'s LED lit while showing |

`BindingHealthEvaluator.Context` gains valid Look, template, pad and page uuids and the
selection-capable property set; `AssignmentHealth` gains `MissingLook`, `MissingTemplate`,
`MissingPad`, `MissingPage`, `LookNeedsSelection`, `UnknownProperty` (an encoder-bank or
selection property no fixture in the patch has) and `UnknownTarget`. `targetType` on the row
keeps naming the discriminator, so the existing `BindingMatrix` filter and the list DTO need no
change to show the new kinds as dead when they are.

`SurfaceInputRouter`'s dispatch `when` grows one arm per variant; every arm calls a `SurfaceActions`
method, so `RecordingActions` keeps the router testable without a show.

### 3.2 The selection

```
DeskSelection (state/DeskSelection.kt)
  targets: StateFlow<List<CueTargetDto>>      ordered as added; a group and its member are two entries
  set(targets) / toggle(target) / clear()      one mutation, one frame
  coverage(): List<CueTargetDto>               groups expanded — ProgrammerLayerStack.coverage's rule
```

Cleared on project switch and on fixture reload where a target no longer resolves (a dropped
target is removed, the rest stay — the same rule a press applies). Not persisted: it is transient
runtime state under the sync decision tree's fourth branch, and the `SyncCoverageTest` disposition
does not apply because it is not a table.

The programmer page keeps `selectionSlice` for what only a list has — anchor, ranges, rows — and
gains an effect that publishes the `programmer` scope's `targetKeys` as `selection.set` when they
change, and one that marks rows selected from `selection.state`. A row selected from hardware
therefore lights in the list, and a marquee in the list lights the strip's select LEDs. The busk
view's `useBuskingSelection` becomes a thin hook over the `selection` RTK cache. The record and
template routes keep taking explicit `targets`: the selection is what the *clients* pass them, so
the routes do not learn about it and the AI, which already passes targets, is unchanged apart from
reading it back.

### 3.3 Strips and the profile layout

```kotlin
// ControlSurfaceDevice DSL, XTouchCompactStandard
repeat(8) { i ->
    strip(id = "strip-${i + 1}", fader = "fader-${i + 1}", select = "btn-${25 + i}",
          encoder = "enc-${i + 1}", flash = "btn-${i + 1}")
}
strip(id = "strip-master", fader = "fader-9", select = "btn-33")
layout {
    region("strips", columns = 8) { /* enc-n row 0, btn-n row 1, btn-(8+n) row 2, btn-(16+n) row 3, fader-n row 4, btn-(24+n) row 5 */ }
    region("right", columns = 2)  { /* enc-9..16 rows 0..3, btn-34..39 rows 4..6 */ }
    region("master", columns = 1) { /* layer, fader-9, btn-33 */ }
}
```

`ControlSurfaceRegistry.buildFromClasses` fails fast on a strip naming a control the profile does
not declare, a control in two strips, or a control with no layout cell when a layout is declared —
the same strictness it applies to duplicate `controlId`s, for the same reason: bindings reference
these ids as a stable contract.

`ControlSurfaceBindingService.resolve(projectId, typeKey, controlId, bank)`:

1. the control's own row for the exact bank, else global — today's rule;
2. else the strip containing `controlId`, its row for the exact bank, else global, **derived** to the
   control's role (D4);
3. else null.

Derivation is a pure function `Strip.derive(control role, encoderBankProperty)` returning a
`BindingTarget`, so the feedback index, the router and the inspector all ask the same question.

### 3.4 The encoder bank

`EncoderBankState` is `ActiveBankState` with a `String` instead of a bank id: a
`ConcurrentHashMap` fast path for the router, a `StateFlow` per device for the socket, and a
`changes` flow the publisher subscribes to alongside `bankState.changes`. It is session state,
survives reconnect of the device, and resets to `dimmer` on project switch.

### 3.5 Feedback and the control-state stream

`SurfaceFeedbackPublisher` today rebuilds an `Index` of continuous entries, LEDs, flash / scaler /
speed-master maps on every attach, binding, bank, fixture and project change. It gains:

- **Selection entries**: a `SelectionProperty` control (fixed or strip-derived) indexes against
  *every* selected fixture's primary channel for the property; `computeValue7Bit` returns the
  common value or `null` for mixed (D10). A selection change is one more rebuild trigger.
- **Strip-derived entries**: `rebuildIndex` resolves through the service's derivation, so a strip
  fader is an ordinary `ContinuousEntry` on the group's dimmer, and a strip select button an LED
  entry keyed to selection membership.
- **Record LEDs**: `ApplyLook` / `PressTemplate` / `PressPad` LEDs subscribe to the programmer's
  layer-state change (the same signal that emits `programmer.layerState`) and light from
  `appliedState` — the pad ring rule, computed once per frame for every such LED on every device.
- **The stream** (D7): a `ControlStateTracker` the publisher writes through at its send sites,
  holding the last state per `(displayKey, controlId)` and emitting conflated deltas; `SurfaceSocket`
  subscribes and sends `surfaceControls.changed`, and `sendSnapshot` sends `surfaceControls.state`
  on connect. Frontend: a `surfaceControls` RTK Query cache entry in `store/surfaces.ts`, folded
  exactly as `surfacePickups` is.

Nothing on the DMX tick path changes: the publisher's one observation point
(`FixturesChangeListener.channelsChanged`, post-transmit, delta-only) stays the one observation
point, and the tracker is written on the MIDI thread the publisher already uses.

### 3.6 Sync

`control_surface_bindings` is already portable and already carries `targetPayload` as an opaque
string; nothing in `ProjectExporter` / `ProjectImporter` changes. Per D11: writer `formatVersion`
11, `minReader` 5, and the tolerant per-row decode in `ControlSurfaceBindingService.ensureLoaded`.
`RichProjectFixture` gains one binding of each new variant with non-default fields, so
`ProjectRoundTripTest` proves the discriminators survive the trip and `ProjectCloner` remaps the
uuids they carry (the remapper substitutes uuid-shaped strings wherever they appear, which is the
whole reason D6 insists on uuids). `docs/sync-engineering.md` §"Known limitation" is narrowed to
the int-id variants, or deleted if §11's first question is answered yes.

## 4. UX — what the design draws

`midi-surface-design/INDEX.md` describes the three boards; this is the grep-able summary the
sessions build to.

- **Header row**: device chips (matched: name, `typeKey` badge, in / out; unmatched: name and an
  *unmatched* badge, click → the current `UnmatchedDeviceState` card), the dead-binding badge, the
  bank switcher as a segmented control, and *Edit bindings* / *Done* (outline / primary, `h-7
  text-xs`, the busk strip's pair). No Blackout, no GM.
- **Panel header**: `vendor · product · bank`, a **Selection** chip naming the selected targets
  with a fixture count and *Clear*, and the LED / touched / dead legend.
- **The panel**: the profile's layout (D8) drawn as strips, right block and master. Per control:
  the binding's short label under it (`Front wash · dimmer`, `4 Chorus`, `Flash Movers`,
  `Sel · pan`), `—` dashed when unbound, red when dead. Faders: cap at the fed-back value, a
  percentage above, a blue ring on the cap while touched, a dashed amber cap and percentage at the
  pickup target while waiting. Encoders: a 13-dot single-dot ring, dark when unbound or mixed, the
  centre lit when the push carries a binding. Buttons: a 3px LED bar at the top edge, dashed shell
  when unbound. Select buttons carry the group name and light while selected. The six right-block
  buttons carry the encoder-bank property names, the active one lit.
- **Inspector** (run mode, right, 360px): the clicked control — name, MIDI addressing, a
  **binding card** (strip binding: the group and what each of its four controls does; single
  binding: target, bank, takeover, other banks) with *Change target* / *Change group*, *Fader
  only…* on a strip, *MIDI Learn*, *Remove*, and a **live card** (position, touch, the stage value,
  selection membership).
- **Library** (edit mode, same slot): search; a kind row *All · Groups · Fixtures · Looks · Cues ·
  Desk*; the busk palette's family row. Rows: a **group or fixture** (row drag → strip; chips per
  continuous property and *select*); **Selection** (chips per property, *Clear*, *Locate*);
  **Encoder bank** (chips per property); a **template** (*Press*, "lands on the selection"); a
  **Look** (*Apply*; a deferred-effect Look shows "needs a selection" and will not drop); a
  **stack** (*Go · Back · Pause*); a **cue** (*Fire*); a **busk page** (*Page*, *Next page*, *Prev
  page*, one chip per pad); **Desk** (*Blackout*, *Grand master*, *Bank A / B*, *Tap M1*, *BPM*).
  A row already on the surface says "strip *n*" or "on *n* controls".
- **Drag semantics**: a chip over a control it cannot land on dims the control (a cue chip over a
  fader; a property chip over a bank button that already has a strip role is allowed and becomes a
  direct binding). A row over a strip lights the whole column dashed. The ghost is the busk pad
  ghost for a chip and the busk bank ghost for a row.
- **Below `md`**: no picture and no edit mode — the current grouped table stays as the narrow
  rendering, exactly as the busk view keeps *Done* but hides its palette.

## 5. Implementation — five sessions

Backend first, twice — the selection and the stream, then strips and the bank — because the view
cannot be built against a stream that does not exist and a strip binding that cannot resolve. Then
the view, then the records, then the desk. Each session ends green (`./gradlew test`; `npm run
check` in `lighting-react`).

### Models

| Session | Model | Effort | Why |
| --- | --- | --- | --- |
| 1 — the selection and the stream | Fable 5.1 | high | The invariant-dense one: a new `StateFlow` family under the snapshot rule, the feedback publisher's index growing a mixed-value arm, a tracker written on the MIDI thread, the tolerant decode, a format bump. Its failures are silent (a frame that arrives only after the first change; a ring left lit on a mixed selection; a `SyncCoverageTest` row that still passes because the table is unchanged while the fixture is not). |
| 2 — strips and the encoder bank | Opus 5 | xhigh | Well-shaped data with one subtle rule — direct-first resolution and its interaction with bank precedence — and a registry that must fail fast on every malformed profile. |
| 3 — the surface view | Opus 5 | xhigh | A lot of UI code against the canvas, dnd-kit under the shared provider, and two views adopting the desk selection. Fast mode is available for the visual iteration. |
| 4 — records on buttons | Opus 5 | high | Four target families that each reuse an existing press path; the risk is a press that diverges from the route's, not reasoning. |
| 5 — the first desk use | Sonnet 5 | high | Fixes from the rig. The checks are a human job. |

**Reviews run on `/code-review-lite`** or a plain Opus 5 review after every session, session 1
included — not on Fable.

### Session 1 — the selection and the stream (lighting7) — Fable 5.1, high

- `state/DeskSelection.kt` (§3.2); `plugins/SelectionSocket.kt`: `selection.state` (snapshot +
  broadcast), `selection.set` / `.toggle` / `.clear`; `docs/websocket-engineering.md` rows.
- `BindingTarget`: `SelectionProperty`, `SelectTarget`, `ClearSelection`, `LocateSelection`;
  `SurfaceActions` methods; `DefaultSurfaceActions` writes through `writeGroupProperty` /
  `writeFixtureProperty` per selected target; router arms; `BindingHealthEvaluator` arms and
  `UnknownProperty`.
- `SurfaceFeedbackPublisher`: selection entries and the mixed arm (D10); selection change as a
  rebuild trigger; select-button and locate LEDs.
- `ControlStateTracker` + `surfaceControls.state` / `.changed` (D7), including the inbound physical
  value for non-motor faders and `touched` from `onTouch`.
- D11: per-row tolerant decode, `UnknownTarget`, `formatVersion` 11; `RichProjectFixture` and
  `ProjectRoundTripTest` extended for the four new variants.
- AI: `get_current_state` reports the selection; no new tool (the AI passes targets, §3.2).
- Tests: `DeskSelectionTest` (set / toggle / clear / coverage / drop-on-reload);
  `SelectionSocketTest` (snapshot on connect before any change; one frame per mutation);
  `SurfaceInputRouterTest` arms (empty selection drops; a group in the selection fans);
  `SurfaceFeedbackPublisherTest` (uniform → value, mixed → null and ring off, turn → uniform;
  select LED follows membership); `ControlStateTrackerTest` (conflation; snapshot equals the last
  delta of every key); `ControlSurfaceBindingResolverTest` (an unknown discriminator loads as
  `UnknownTarget` and the rest of the project loads); `SyncCoverageTest` unchanged and still green.
- Docs: `midi-control-surface-engineering.md` §"Selection" and §"Control-state stream";
  `lighting-composition-model.md` §"Layer 2" gains the one-selection paragraph beside the
  one-programmer one; `sync-engineering.md` v11.

### Session 2 — strips and the encoder bank (lighting7) — Opus 5, xhigh

- `ControlSurfaceDevice`: `strip(...)` and `layout { region(...) }`; `StripDescriptor`,
  `SurfaceLayout`; `DeviceTypeInfo.strips` / `.layout`, both on the `/control-surface-types` DTO.
  `XTouchCompactStandard` declares nine strips and the layout (§3.3). Registry fail-fast rules.
- `BindingTarget.Strip`, `EncoderBankSet`; `Strip.derive`; `ControlSurfaceBindingService.resolve`
  direct-first (§3.3); `EncoderBankState` + `surfaceEncoderBank.state` / `.set`; publisher rebuild
  and takeover re-arm on bank change; encoder-bank LEDs.
- Bind-time validation on `POST/PATCH /surface-bindings`: a `Strip` target only on a strip id; a
  strip id takes only a `Strip`; `BINDING_STRIP_NEEDS_STRIP` / `BINDING_CONTROL_NOT_STRIP` 400s.
- Tests: `ControlSurfaceRegistryTest` (each malformed profile refused, message names the id);
  `StripDeriveTest` (four roles × bank property, master strip has no encoder arm);
  `ControlSurfaceBindingResolverTest` (direct beats strip; exact bank beats global at each step;
  `Fader only…` = delete strip + four creates in one transaction); `EncoderBankStateTest`;
  publisher tests (a bank change moves every strip encoder's primary channel; PICKUP re-arms).
- Docs: engineering doc §"Strips" and §"Encoder bank"; `api-conventions.md` if a code is added.

### Session 3 — the surface view (lighting-react) — Opus 5, xhigh

- `store/surfaces.ts`: `surfaceControls`, `surfaceEncoderBank` caches folded like `surfacePickups`;
  `store/selection.ts` (the desk selection cache + writes); `useBuskingSelection` over it;
  `selectionSlice` bridge (§3.2).
- `routes/Surfaces.tsx` rebuilt to the canvas: header row (D1: `ScalerToolbar` deleted),
  `SurfacePanel` from `layout` (D8; fallback to `BindingMatrix` below `md` or without a layout),
  `SurfaceInspector`, `SurfaceLibrary` under `DeskDndProvider` (D9), the busk ghosts through
  `registerDragOverlay`. Drop → create / replace binding; row → `Strip`; the eligibility dims.
- The `FU-FE-USE-TARGET-PROPERTIES` gate **fires here** — the library's property chips are the
  sixth consumer of fixture / group property lookup — so this session extracts
  `useTargetProperties` into `src/hooks/` and the five existing call sites move onto it.
- Tests: `Surfaces.test.tsx` (no scaler buttons; a matched device draws the panel; an unmatched one
  the card; dead badge count), `SurfacePanel.test.tsx` (each legend state from a `surfaceControls`
  frame), `SurfaceLibrary.test.tsx` (rows and chips from the store; slot-style dimming), a
  `slotDrop`-style pure mapping test for drop → binding request, `useBuskingSelection.test.tsx`
  against the WS cache, `selectionSlice` bridge test.
- Docs: `lighting-react/docs` gains a short engineering note for the view; `CLAUDE.md` route list.

### Session 4 — records on buttons (both repos) — Opus 5, high

- lighting7: `ApplyLook`, `PressTemplate`, `PressPad`, `BuskPageNext/Prev/Set` (D6);
  `BuskPressService` extracted from `routes/buskPress.kt` with the route as its first caller;
  `BuskPageState` + `busk.pageState` / `busk.setPage`; health variants; record LEDs from
  `appliedState`; bind-time refusal of a deferred-effect Look. `RichProjectFixture` gains one of
  each; round-trip and clone tests. `FU-LOOK-MIDI-RECALL` and `FU-BUSK-PAGE-MIDI` become Completed
  rows.
- lighting-react: library rows for templates, Looks, busk pages and pads; the busk view follows
  `busk.pageState` and writes it on tab change; the inspector's binding card for each kind.
- Tests: `SurfaceInputRouterTest` arms (Look onto own fixtures; template onto the selection and
  dropped when empty; pad press narrows a solo sibling — the `BuskPressRouteTest` cases replayed
  through the service); `BindingHealthEvaluatorTest` for every new `Missing*`; `BuskPageStateTest`.

### Session 5 — the first desk use (both repos) — Sonnet 5, high

The §9 checks on the X-Touch, with the fixes they turn up. The checks are staged in
`manual-validation.md` as `FU-MANUAL-MIDI-SURFACE` when session 5 starts, not before.

## 6. Migration

None. No table or column changes; every new target is a new discriminator in an opaque payload;
selection, encoder bank and current busk page are transient. An existing fixed binding on a
control that is now part of a strip keeps winning (D4), so a desk with today's bindings behaves
identically until the operator drops a row on a strip. The tolerant decode (D11) is what protects
an older desk *reading* a newer project, and it lands in session 1 before any new variant is
written.

## 7. Explicitly out of scope

- **Relative encoders and 14-bit faders.** Absolute 7-bit only, as today.
- **A per-user or per-client selection.** Rejected for the programmer already
  (`FU-PROG-PER-USER`); the same reasoning, the same answer.
- **Record / Include / Update from a button.** Destructive-adjacent gestures stay on screen. If an
  operator asks, it is a `RecordCue` target with a confirm on the ShowBar, not a bare button.
- **Attribute-*family* banks with several encoders per strip.** One encoder per strip is what the
  X-Touch has, so the bank names one property. A profile with three encoders per strip can declare
  three encoder roles when one exists.
- **Colour on an encoder as hue.** `SelectionProperty(colour)` fans the 7-bit value to R/G/B as
  `PropertyChannelResolver` does today. A hue wheel is a resolver change, not a surface one.
- **The programmer page's list selection model.** Anchor, ranges and rollups stay in
  `selectionSlice`; only its expanded `targetKeys` cross the wire.
- **OSC, DMX-in and keyboard triggers.** The binding grammar is transport-agnostic already; a
  second transport is a `MidiController` implementation, not a target.

## 8. Follow-ups to record

- **Tap confirmation LED** (`FU-SPEED-SURFACE-TAP-LED`): the record LEDs' subscription makes a
  one-beat tap LED a small addition; still gated on an operator asking.
- **Encoder ring styles**: `FAN` / `PAN` rings need a mixed-state rendering of their own; the
  X-Touch is `SINGLE_DOT`, so the legend draws one style. Gate: a second profile.
- **Hue on encoders** (§7).
- **Selection in the AI surface**: a `set_selection` tool once a conversation asks "select the
  movers" — today the model passes targets explicitly and that stays correct.
- **`FU-SYNC-BINDING-PAYLOAD-UUIDS` second half**: project-scoping `CueStackManager.fireCue`'s
  lookups is untouched by this plan whatever §11's first answer is.

## 9. Verification

Backend: the tests in §5 S1, S2 and S4; `ProgrammerLayerStackTest` unchanged (the engine does not
move — a press from hardware is the same `toggle` a pad makes). Frontend: §5 S3. Desk checks, to
be added to `manual-validation.md` as `FU-MANUAL-MIDI-SURFACE` when they are run:

1. Cold open with the X-Touch attached: the picture matches the panel, every LED and fader on the
   desk matches the screen without touching anything (D7's snapshot).
2. Drop *Front wash* on strip 1: the fader drives its dimmer and the motor follows a busked value;
   the select button lights on press and the busk view's target band shows the group selected; the
   encoder drives the property the lit bank button names; switching the bank moves the encoder's
   meaning and the ring redraws.
3. Select Front wash and Movers; turn *Sel · colour*: both change and the ring lights; select Movers
   only: the ring reads the movers' value; add Front wash back with a different colour: the ring
   goes dark (mixed) and a small turn writes both.
4. A non-motor fader on `Sel · dimmer` with PICKUP: the dashed target draws at the uniform value;
   crossing it engages; a mixed selection shows no target.
5. Press a colour template with no selection: nothing happens and nothing lights; with two heads
   selected: the layer goes on and the LED lights; add a third head: the LED goes off (not every
   selected head is covered) until pressed again.
6. A bound Look on a button: on and off on its own fixtures with nothing selected; a deferred-effect
   Look will not drop from the library.
7. A pad in a solo bank on a button: pressing it narrows its sibling on the selected heads exactly
   as the busk view does; *Next page* moves the busk view's tab on a second client.
8. Unplug and replug the surface mid-session: bank, encoder bank and every LED come back (control
   surface plan open question 8, now with more state to restore).
9. Import a project exported by this build into a build from before session 1: the project loads,
   the new bindings read as dead, everything else works.

## 10. Scope honesty

This is a large change — a new shared piece of desk state, four families of binding target, a
profile DSL extension, a new stream, and a view rebuilt from a table into a picture. What keeps it
bounded: `ProgrammerLayerStack`, `ProgrammerStore`, `CueStackManager`, `LocateManager` and the DMX
tick path are untouched; every press from hardware reuses a route's existing plan; no table
changes; and the view copies the busk view's edit mode rather than inventing one. The riskiest
lines are the publisher's mixed-selection arm (a ring left lit, or a motor written, on a mixed
selection is exactly the kind of bug the hardware shows and the tests do not), the direct-first
resolution's interaction with bank precedence (a global strip under an exact-bank single binding
must lose — §3.3 step 1 before step 2, at *both* bank levels), and the tolerant decode, which must
never turn one bad row into an empty binding table.

## 11. Open questions

Each has a drafted answer the sessions build unless overturned:

- **Fold `FU-SYNC-BINDING-PAYLOAD-UUIDS`'s uuid move into the v11 bump?** Drafted: yes — the
  writer bump is being paid, `FireCue` / `CueStack*` gain a `uuid` field beside the int (the int
  kept for one version so the tolerant decode never sees a row it cannot read), and the importer
  resolves by uuid first. Alternative: leave it, and accept a second bump later.
- **Does a strip's select button toggle or replace?** Drafted: toggle, with a long press to
  replace, because a fader wing's select is additive on every console surveyed and *Clear* is one
  button away. Alternative: replace, with shift-style modifier from a bound button.
- **What does the encoder bank hold when the selected fixture lacks the property?** Drafted: the
  encoder reads unbound for that strip (label `—`, ring dark), the turn is dropped; health does not
  flag it, because it is a property of the selection, not of the binding.
- **Should `SelectionProperty` write through `ProgrammerOwner.SURFACE` or a new owner?** Drafted:
  `SURFACE` — it is a fader, and releasing a flash must reveal it exactly as it reveals a fixed
  fader's value.
- **Is the desk's current busk page worth a `StateFlow`, or should `BuskPageNext` stay
  client-local?** Drafted: desk-side, because a button on the X-Touch cannot address one of two
  tablets; the URL keeps mirroring it so a bookmark still means something.
