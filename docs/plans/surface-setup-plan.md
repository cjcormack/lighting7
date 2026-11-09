# Surface setup: one binding grammar, one layer rule, four editors

> **Document status: READY — written 2026-11-08 from the pages Chris and Claude agreed that day.**
> Six sessions, each one PR, each stacked on the one before while that one is unmerged, each run by
> `/next-session` (§5). Session 1 needs the busk-stack plan's session 4, merged as #400. The pages and
> the record are in [`surface-setup-design/INDEX.md`](surface-setup-design/INDEX.md): the concept page
> (`surface-setup.html`) and the code review (`surface-review.html`).
>
> This document is the engineering half. Where it and the pages disagree, this plan wins on
> behaviour and the pages on layout and copy.

## 1. Context

Chris's brief, 2026-11-08: research and propose a new UI for configuring a surface, then review the
surface code for a simpler implementation once the concept is agreed. Three problems prompted it.
The drag interface has many options but not all of them: a speed master other than master 1 is
reachable only by editing a binding. Some functions work on the selection but not on a named fixture
or group: Locate is selection-only. And the layer things apply to is inconsistent: a selection dimmer
applies to the programmer's values, while pan and tilt seem to apply to the output.

What the code says, read at `974d718`. `F/` is `frontend/src`, `K/` is
`src/main/kotlin/uk/me/cormack/lighting7`.

- **The binding.** A row in `control_surface_bindings` (`K/models/surfaceBindings.kt:19`): `project`,
  `device_type_key`, `control_id` (a control id or a strip id), `bank` (null for every bank),
  `target_payload` (a `BindingTarget` as JSON text) and `takeover_policy`, unique on
  `(project, device_type_key, control_id, bank)` (`:45`). `BindingTarget` (`K/midi/BindingTarget.kt`)
  is a sealed class of 50 variants, decoded per row with an undecodable payload kept as `Unknown`
  (`K/midi/ControlSurfaceBindingService.kt`). The subject is in the type: `FixtureAttribute`,
  `GroupAttribute` and `SelectionAttribute` are one function three times, and `LookSet`,
  `BlackoutSet` and `LooseCueSet` are on/off forms of their toggles. One row covers an encoder's turn
  and its push (`K/midi/SurfaceInputRouter.kt:404`); a push on an attribute target writes full
  (`K/midi/SurfaceDispatch.kt:139-145`).
- **Every surface write lands in the programmer** (owner `SURFACE`, or `FLASH`), on the attribute's
  own key. Pan and tilt are their own `pan` / `tilt` slider keys, which hold back no FX keyed
  `position` (`K/fx/FxSuppression.kt:73`); the position composer weighs only the `position` entry and
  the sideband (`K/fx/FxTarget.kt:719`). So under a Circle a pan move is stored and the stage keeps
  circling. The fixture sheet had this bug and fixed it with `programmer.setPosition`
  (fixture-fx-sheets plan D8).
- **The readback differs by function.** A level, colour or pan control reads the controller buffer,
  `ArtNetController.getValue`: park, else composed, before the grand master
  (`K/midi/SurfaceFeedbackPublisher.kt:1319`, `K/dmx/ArtNetController.kt:229`). So a motor or pickup
  fader on a key with a running FX chases the FX, and in Blind a fader shows the stage while it edits
  the staged value. The fan moves from the programmer's value, else the buffer
  (`K/routes/programmerFan.kt:495`). FX speed shows the lead FX. A colour-axis write builds on the
  buffer too (`K/midi/AttributeChannelResolver.kt:134`, `:503`), so a hue turn in Blind copies the
  stage's white, amber and UV into the staged entry.
- **Feedback** (`K/midi/SurfaceFeedbackPublisher.kt`, 1,567 lines) has seven entry kinds with no
  shared base, nine LED lists (`selectLeds` and `encoderBankLeds` filled and never read, `:1022`,
  `:1024`), 17 subscriptions, and LED `when`s ending `else -> null` (`:1056`, `:1256`). It runs
  synchronously on the transmit path under `Fixtures.registerLock.read` (`K/show/Fixtures.kt:747`).
  `resyncControl` has no speed-master arm, so a motorised tempo fader gets no catch-up when let go
  (`:1157`). `effectivePolicyFor` resolves the binding again on every inbound event (`:742`). There
  are six jump rules: soft takeover's crossing, its disarm on mixed, the fan's `PICKUP_STEPS`
  (`K/midi/SelectionFan.kt:226`), FX speed's `ENCODER_JUMP` (`K/midi/SelectionFxSpeed.kt:191`), the
  FX-speed fader echo, and none on a strip encoder.
- **Heads.** `SelectionWrites` looks fixtures up with `untypedFixture`, which resolves no element key,
  and keeps only whole fixtures from a group (`K/midi/SelectionWrites.kt:36`, `:42`,
  `K/show/Fixtures.kt:305`), as the fixture and group paths in `SurfaceActions` do (`:343`, `:364`).
  A selected cell, or a fixture's heads in a group, get no surface write; the Shehds' colour is
  unreachable. `toAttributeValue` handles `DmxSlider` and `DmxColour` only
  (`K/midi/AttributeChannelResolver.kt:364`), so a `DmxWhiteMixColour` head (the China 2-cell
  blinder) drops every colour move.
- **The vocabulary.** Locate, Fan, FX speed, Press palette and Press pad act on the selection only;
  Flash and Fire on a named target only; the encoder bank reaches strip encoders only
  (`K/midi/StripDerivation.kt`); no binding clears the programmer, goes Blind, releases a target or
  records. Records are named by uuid with an int beside some (`FireCue`, the stack variants), and the
  "uuid first" rule is in five places, with three private `uuidOrNull` copies.
- **Strips** share the `control_id` column (strip ids beside control ids), which costs a slot refusal
  in the service and its copy in the route (`ControlSurfaceBindingService.kt:613`,
  `K/routes/projectSurfaceBindings.kt:271`) and an unreachable `Strip` arm in dispatch. Resolution is
  the control's own row on both bank levels, then its strip's (`ControlSurfaceBindingService.kt:206`).
- **Learn** captures on its own collector beside dispatch (`K/midi/MidiLearnSessionManager.kt:220`),
  so identifying a bound control fires it; the capture carries no part.
- **Live state per type.** The active bank and the encoder bank are keyed by `deviceTypeKey`
  (`K/midi/ActiveBankState.kt:13`, `K/midi/EncoderBankState.kt:28`), and flash is held by binding id
  (`K/midi/SurfaceDispatch.kt:25`), so two identical surfaces mirror each other's layer and can
  release each other's flash (inferred; no test covers it).
- **OSC** builds `BindingTarget`s in memory from a fixed table (`K/osc/OscAddresses.kt:74`) and
  dispatches them through `SurfaceDispatch`; nothing it builds is stored. `GrandMasterLevel` and
  `SpeedMasterSetBpm` exist for it.
- **The frontend** (`F/routes/Surfaces.tsx`, `F/components/surfaces/`) mirrors the resolver
  (`F/lib/surfaceResolve.ts`), the control-kind tables (`F/lib/surfaceDrop.ts`) and the labels
  (`F/components/surfaces/targetUtils.ts`, which names stacks by id). It has three hand-written
  function catalogues that disagree: the drag library offers master 1 only for tempo
  (`SurfaceLibrary.tsx:683`), the form offers 48 kinds, and a fixture flash can be made from neither
  (`BindingTargetPicker.tsx:1270`). The phone table can't see strips (`BindingMatrix.tsx:77`, `:287`).
  The header's bank buttons send `surfaceBank.set`, so viewing a layer switches the hardware.
- **Versions.** Sync is at `formatVersion` 52 (`SUPPORTED_FORMAT_VERSION`,
  `K/sync/ProjectImporter.kt:414`); bindings travel as `ControlSurfaceBindingJson` in
  `controlSurfaceBindings/` (`K/sync/ProjectExporter.kt:976`), with old payloads renamed by
  `LegacyNames` (`K/sync/LegacyNames.kt:308`). `PRAGMA user_version` is 14
  (`K/state/BuskPadStackMigration.kt:17`). No MCP tool creates a binding;
  `K/ai/RigKeyReferences.kt` renames keys inside them.

## 2. Decisions

From the concept page's decisions and the review's, all answered with the recommendation except D12,
where Chris chose all four editors now.

| # | Decision |
|---|---|
| D1 | **Does · On · How.** Every binding is one `Binding(does, on, how)` at a slot `(device type, control, part, layer)`. *Does* is a function, with its own parameters (an attribute, an axis, a palette, a level). *On* is the subject. *How* is the behaviour. |
| D2 | **Subjects.** The desk selection, a group, a fixture (element keys included), a job's slot (D11), a record by uuid (cue stack, entry, cue, Look, palette, busk pad, busk bank, busk page, speed master), or none. Every fixture function takes the selection, a group and a fixture where it means something: level, colour axes, the other sliders, pan and tilt, fan, FX speed, follow the encoder page, select, flash, locate, release, press a palette. Fan refuses a single fixture at bind time. **Fire stays fixture only.** |
| D3 | **How.** A button is Toggle, While held, On or Off; a select is Toggle-hold-replaces, Toggle or Replace; a continuous control picks up or jumps (the existing `takeover_policy` column). The explicit on/off targets fold into their toggles. |
| D4 | **An encoder's push is its own binding**, a second part beside the turn, and does nothing until bound. No push writes full. |
| D5 | **Fixture controls write Local on the attribute's own key.** Pan and tilt write `position`, keeping the other axis, as `programmer.setPosition` does. A pan nudge therefore holds tilt against later cues; that is what lets it beat a position FX. |
| D6 | **The readback is Local first.** A fixture control shows, and moves from, what the programmer holds for its key (the staged value in Blind), else the cue layer under the programmer, without FX. It looks beneath a held flash and ignores park. A colour-axis write builds on the same value. |
| D7 | **One jump rule** for every fader and ring. A driven control (a motor fader, a ring encoder) accepts a move within J of what it shows, or while touched, and otherwise ignores it and shows its value again; an undriven fader picks up when it crosses; a mixed value accepts; after an accepted move the shown value stays the physical one while the function reads back to it. |
| D8 | **Every binding names where it writes** in one word: Local, Playback, Master, Tempo, Desk. |
| D9 | **New functions.** Clear (the programmer, at the programmer fade); Blind (Toggle or While held); Release (a subject from the programmer); Record (a new cue appended to a named stack with every touched value, all attributes, the Record sheet's defaults: the programmer's FX, held scenery and held tempos; nothing to answer); Update (written back to what Include loaded, as the programmer's Update does; refused when nothing is included). |
| D10 | **Flash** is a percentage of each head's range, as a fader scales, and takes the selection: a flash on the selection releases the heads it wrote at press, not whatever is selected at release. |
| D11 | **Jobs.** A device profile names job regions with ordered slots and roles. A region's job is stored and live: reorder its items and the controls move. A direct binding beats the job; a control bound by hand leaves it. Jobs replace strips. |
| D12 | **All four editors, now.** A: click or touch a control, then Does · On · How per part. B: region jobs. C: a mapping table on the sheet kit, replacing `BindingMatrix` and serving the phone. D: *Assign to surface…* on records. The drag library, the 48-kind form, `BindingMatrix` and the Learn overlay go. |
| D13 | **Viewing a layer in the app doesn't switch the hardware.** Switching is a binding (Switch layer) or the device's own key. |
| D14 | **Identify mode captures without firing.** While the app waits for a touch (A, D), the next press is captured and not dispatched: one press, a banner, 30 s. |
| D15 | **The desk serves the picture.** Each control part's resolved binding (`surfaceMap.state`), one function catalogue (`/surface-functions`), a write by slot, and a reverse index of what each record is bound to. The browser keeps no copy of the resolve order, the kind tables or the labels. |
| D16 | **Bindings per device type, live state per device.** Two identical surfaces share bindings; each keeps its own layer, encoder page and held flashes. |
| D17 | **Words.** On screen: *Layer A / B* (always with its letter, never bare *layer*), *Encoder page* for the encoder bank, *Writes to Local*. Identifiers in code and on the wire keep `bank` and `encoderBank` (§7). |
| D18 | **`minReader` rises with the format.** An older desk refuses a newer show rather than reading every binding as unknown, or dropping its jobs. |
| D19 | **OSC addresses don't change.** Its table builds bindings in the new grammar; `/on`, `/off` and `/press` are On, Off and Toggle, a number a value taken whole. |

The plan adds five of its own:

- **P1 — Six sessions, six stacked PRs**, in §5's order, each run by `/next-session`
  (`.claude/skills/next-session/SKILL.md`) through `/verified-ship`. A session stacks on the previous
  session's branch while that PR is open, and on `main` once it has merged. Session 1's base is
  always `main`, so its **Needs** is checked where its base is.
- **P2 — Two format bumps.** Session 1 (the binding's shape) and session 5 (the jobs folder) each take
  `main`'s `SUPPORTED_FORMAT_VERSION` plus one and raise `minReader` to it (D18). Session 4's
  functions and subjects live inside the payload: an older desk at session 1's version decodes a new
  function as `Unknown` and keeps it, so session 4 changes neither.
- **P3 — One definition per function.** A function's spec, its act, its readback, its LED decision and
  its jump policy live on one `Does` type. No `when` over functions ends in `else`; a test asserts
  every function has a spec and declares an LED (a light, or explicitly none).
- **P4 — One translate per stored shape.** Each database step that rewrites bindings calls the same
  pure function the archive reader calls through `LegacyNames`, as `FxConsolidationMigration` and
  `consolidateFx` share one.
- **P5 — The desk is the only resolver.** From session 3 on, the browser holds no copy of the resolve
  order, the kind tables or the label rules; a fixture shared by Kotlin and TypeScript tests guards the
  wire, as `spreadShape.fixture.json` does.

## 3. The model

### The binding (D1–D4, P3)

```kotlin
@Serializable data class Binding(val does: Does, val on: Subject = Subject.None, val how: How? = null)

sealed interface Subject {          // "none" | "selection" | "group" | "fixture" | "slot" | "record"
    data object None; data object Selection; data object Slot
    data class Group(val key: String); data class Fixture(val key: String)
    data class Record(val kind: RecordKind, val uuid: UUID)   // STACK, ENTRY, CUE, LOOK, PALETTE, PAD, BANK, PAGE, MASTER
}
enum class How { TOGGLE, WHILE_HELD, ON, OFF, TOGGLE_HOLD_REPLACES, REPLACE }
enum class WritesTo { LOCAL, PLAYBACK, MASTER, TEMPO, DESK }
data class Spec(val writesTo: WritesTo, val on: Set<SubjectKind>, val inputs: Set<ControlKind>,
                val hows: Set<How>, val defaultHow: How?)
sealed interface Op { Move(v7, encoder); Set(value); On; Off; Toggle; Replace }
sealed interface Does { val spec: Spec; fun act(on: Subject, op: Op, ctx: SurfaceContext): Acted }
```

The slot gains a **part**, `MAIN` (a fader's move, an encoder's turn, a button's press) or `PUSH`.
`SurfaceDispatch.dispatch(binding, input, at)` turns a How into an `Op` in one place: While held keeps
what the press wrote and undoes exactly that on release; Toggle-hold-replaces toggles and, held for
`SELECT_HOLD_MS`, replaces. Holds are keyed by `(displayKey, controlId, part)`, not binding id.

Today's variants map onto it:

| Today | Does | On | How |
|---|---|---|---|
| `FixtureAttribute` / `GroupAttribute` / `SelectionAttribute` | `level(attribute, axis?)` | fixture / group / selection | continuous |
| `Flash(inner, max)` | `flash(percent)` | the inner target's | While held |
| `SelectTarget(target, mode)` | `select` | group / fixture | Toggle-hold-replaces / Replace |
| `LocateSelection` · `SelectionFan(axis)` · `FxSpeed` | `locate` · `fan(axis)` · `fxSpeed` | selection | Toggle · continuous |
| `PressPalette(uuid)` | `palette(palette)` | selection | Toggle |
| `ApplyLook` + `LookSet` · `FireCue` + `LooseCueSet` | `look` · `cue` | record | Toggle / On / Off |
| `PressPad` · `PickUpPad` · `HandPlaceInBank` · `HandDrop` | `pad` · `pickUp` · `place` · `drop` | record (pad, bank) / none | press |
| `CueStackGo` / `Back` / `Pause` / `Stop` · `GoTo` / `Standby` · `StackFxAmount` | `go`… · `goTo` / `standby` · `fxAmount` | record (stack, entry) | press · continuous |
| `FireTrigger(fixture, trigger)` | `fire(trigger)` | fixture | press |
| `Blackout` + `BlackoutSet` · `GrandMasterToggle` / `Fader` / `Level` | `blackout` · `grandMaster(level?)` | none | Toggle / On / Off · continuous · a level a press sets |
| `SpeedMasterBpm` / `SetBpm` / `Tap` / `Scale` | `tempo(min, max)` / `tempo(bpm)` / `tap` / `tempoScale(factor)` | record (master) | continuous · press |
| `SetBank(device, bank)` · `EncoderBankSet` | `layer(layer)` · `encoderPage(attribute, axis?)` | none (the device pressed) | press |
| `Undo` / `Redo` / `ProgrammerUndo` · busk page ×3 · `BuskFocusSet` / `BuskSheetToggle` · `ClearSelection` / `SelectionNext` / `Prev` / `Cells` | `undo(kind)` · `buskPage(step or page)` · `windowFocus` / `windowSheet` (a window name) · `selection(step)` | none | press |
| `Strip(target)` | `strip` (session 1), a job (session 5) | group / fixture | — |
| `Unknown` | kept, for a newer archive | | |

`SetBank` drops its device: a layer button switches the device it is on (D16).

**What stays special:** `Unknown`; placing the hand on a bank, which drops only if the place succeeds;
the fan's anchor and FX speed's lead FX, state kept behind `act` and taking the subject instead of
reading the desk selection; Undo, Record and Update on the serial lane, since they open transactions
and reload (`K/midi/SurfaceActions.kt:286`); window functions addressed by name, with health that
moves with the window registry; Switch layer, Encoder page and Undo, which act on the device or source
the press came from.

### Heads

One `Heads.of(subject)` expands a subject to the heads a write reaches, on `TargetCoverage.expand`
(`K/fx/TargetCoverage.kt:31`) with element keys resolved by `untypedGroupableFixture` and the HEADS
rule `/programmer/fan` already has: a fixture whose attribute lives on its elements writes every
carrying element. A group member is tagged with the group as `sourceGroup`. It replaces the fixture,
group and selection write paths in `SurfaceActions`, the flash fan-out and `SelectionWrites`, and the
three colour-attribute judgements (`ControlSurfaceBindingService.kt:688`,
`BindingHealthEvaluator.kt:97`, `SurfaceFeedbackPublisher.kt:1125`) read it once.

### The readback (D6, D7)

`LayerResolver.localOver(target, fixture, key)` is `fallbackFor` (`K/fx/LayerResolver.kt:324`)
without its `if (programmer.blind) return below` (`:333`): `FxTarget.composeProgrammerOver(fixture,
store, below = the cue layer)`, reading the store beneath the `FLASH` owner. `fallbackFor` calls it, so
output and readback share one function. A continuous function's `read(on)` folds `localOver` over
`Heads.of(on)` into a `Reading` (`Value(v7, tolerance)`, `Mixed` or `None`); `commonValue`'s every-pair
rule is unchanged.

The publisher learns of a change by **polling, off the ArtNet thread**: a feedback loop at about
50 Hz compares the store's `epoch` (`K/fx/ProgrammerStore.kt:584`), its `blind`, and the identity of
the cue-layer snapshot, and re-reads only the entries whose signals moved. Other functions name their
own signals (`Selection`, `GrandMaster`, `Tempo(master)`, `StackFx(stack)`, `CueHolds`, `StackRun`,
`BuskPage`, `EncoderPage(device)`, `FxList`, `Fan`, `Layers`, `Locate`). One `apply(entry, reading)`
does the tracker, takeover, touch gate and send.

### Jobs (D11)

A profile declares job regions, generalising `strip()` (`K/midi/ControlSurfaceDevice.kt:130`): ordered
slots, each with roles (fader, select, encoder, flash, button, encoder push). They are separate from
the picture's layout regions (`K/midi/ControlDescriptor.kt:170`): the picture must place every control,
while a job region needs slots. A stored job names a region, a layer (null for both), a kind and its
ordered items. `resolve` keeps today's order, now direct then job: the control's own row at the exact
layer, then on both, then the job at the exact layer, then on both. A job kind maps each slot and role
to a `Binding` whose subject is `Slot`, filled from the slot's item. A job that follows live state
(the selection's heads, the busk page showing, the encoder page) re-derives its region on that signal
without re-arming pickup.

### Wire (D15)

```
GET  /api/rest/surface-functions                        static per build, from the specs
WS   surfaceMap.state  { projectId, deviceTypeKey, layers: { [layer]: { [`${controlId}:${part}`]: ResolvedPart } } }
     ResolvedPart = { from: direct | job | none, bindingId?, jobId?, item?, does?, on?, how?, params?,
                      label, subjectLabel?, colour?, writesTo?, health, healthText?, beats? }
PUT/DELETE /api/rest/projects/{id}/surface-bindings/slot  { deviceTypeKey, controlId, part, layer, binding, takeoverPolicy? }
WS   surfaceIdentify.begin / .cancel / .captured        replaces surfaceLearn.*
WS   surfaceUses.state  { projectId, uses: { [subjectKey]: [{ deviceTypeKey, controlId, part, layer, label }] } }
```

## 4. UX

- **Editor A** (session 3): the picture stays (`SurfacePanel`, from profile data). A click, or a touch
  on the desk in identify mode, selects a control; the side panel shows one Does · On · How block per
  part (Turn and Push for an encoder), each with its *Writes to* word, its *Layer* chips (*Both*,
  *A only*, *B only*) and a line saying what it shows. A control a job derives says *From strip 3*
  (sessions 3–4) or *From Groups as strips* (session 5) and offers *Make this control its own*.
- **Layer chips** (session 3) view a layer and switch nothing; while the viewed layer is not the
  device's own, the live state (LEDs, positions) is dimmed.
- **Editor C** (session 3): Settings › Surfaces' table view, and the only view below tablet width. One
  row per control part, grouped by region, a layer partition chip, columns Control · Layer · Does · On
  · How · Writes to · Shows · Health; a compound Binding cell edits through the same form as A; a
  marquee over On takes *Fill in order* (groups, fixtures, stacks, masters, the selection), skipping
  rows that can't take the value and saying which.
- **New functions** (session 4) appear in A and C from the catalogue with no screen work of their own.
- **Editor B** (session 5): a Jobs panel beside the picture: each region, its job, and its ordered items
  with up and down buttons; the region lit on the picture.
- **Editor D** (session 6): *Assign to surface…* in each record's menu opens a sheet that waits for a
  touch (or a click on the picture), offers the functions that fit the record and the control part,
  names what it replaces, and assigns. Each record shows *Bound on Button 36 · A*.

## 5. Implementation — six sessions

Each session is one PR. Each is run by `/next-session`, which cuts the branch, stacks it on the
previous session's branch while that PR is open (on `main` once merged), records the previous
session's PR and head SHA in the table below, and hands the session to `/verified-ship`. A session
ends with its checks green (§"Pre-commit checks" in the root `CLAUDE.md`), the docs it names written,
and its row marked *in review* with its branch. A session's PR is titled
*Surface setup: session N — title*. Never merge a later session's PR before an earlier one.

## Sessions

| Session | Title | Status | Branch | PR | Head SHA |
|---|---|---|---|---|---|
| 1 | The binding and its migration | merged | `claude/hopeful-hamilton-eancf5` | [#407](https://github.com/cjcormack/lighting7/pull/407) | bd866bb |
| 2 | Feedback | merged | `claude/happy-heisenberg-vh2hqw` | [#409](https://github.com/cjcormack/lighting7/pull/409) | 1c407f7 |
| 3 | The served picture, and editors A and C | merged | `claude/beautiful-heisenberg-fvjsu7` | [#412](https://github.com/cjcormack/lighting7/pull/412) | 6f12ce6 |
| 4 | New functions | merged | `claude/beautiful-turing-37vey4` | [#414](https://github.com/cjcormack/lighting7/pull/414) | 67f8350 |
| 5 | Jobs | in review | `claude/zen-mendel-10xked` | | |
| 6 | Assign from the thing | planned | | | |

*Status* is `planned`, `in progress`, `in review` (its PR is open) or `merged`. `/next-session`
fills *Branch* when it starts a session, and *PR* and *Head SHA* for the previous one when it starts
the next; whoever merges the last fills its own.

### Session 1 — The binding and its migration

**Needs:** cjcormack/lighting7 docs/plans/completed/busk-stack-plan.md session 4 merged

That session changed `SurfaceFeedbackPublisher`'s pad LEDs and `CueStackStop`, both of which this one
rewrites; it merged as [#400](https://github.com/cjcormack/lighting7/pull/400) before this plan was written. Session 1's base is always `main` (P1), so `/next-session` §1.5's check on `main` is the
check on its base.

Behaviour is unchanged except where a decision says otherwise: the readback stays the buffer until
session 2, and the screens keep today's shape until session 3.

- **The model (D1–D4, P3):** `Binding`, `Subject`, `How`, `Spec`, `Op` and one `Does` type per function
  in §3's table, in `K/midi/` (the `BindingTarget` sealed class goes). Records by uuid only, a typed
  `UUID` decoded by a serializer, so a malformed one decodes to `Unknown`: the int paths
  (`SurfaceActions.kt:498-530`, `BindingHealthEvaluator.kt:219-243`, `State.kt`'s `validStackIds` /
  `validCueIds`, `ControlSurfaceBindingService.withUuids`, `SurfaceFeedbackPublisher.kt:991`) and the
  three `uuidOrNull` copies go. `Strip` stays, on strip slots only, until session 5.
- **One dispatch:** `continuous` / `level` / `press` / `release` (`SurfaceDispatch.kt:28-157`), the
  router's select-hold (`SurfaceInputRouter.kt:317-336`) and `FlashStateTracker` fold into
  `dispatch(binding, input, at)`, holds keyed by control part (fixing the inferred two-device flash
  bug). The push-writes-full arm goes (D4). OSC's `level()` and `GrandMasterLevel` /
  `SpeedMasterSetBpm` become `Op.Set`.
- **Parts (D4):** the slot gains `part` (`MAIN` | `PUSH`); the router resolves an encoder's push note to
  `PUSH` (`SurfaceInputRouter.kt:404`). One event → control-part index per profile, built in
  `ControlSurfaceRegistry`, replaces `matchEvent` and Learn's `extractCapturableControlId`
  (`MidiLearnSessionManager.kt:252`). Learn's capture carries the part; `surfaceLearn.commit` takes a
  `Binding`.
- **Heads (§3):** `Heads.of(subject)` with elements; `toAttributeValue` and the reads gain
  `DmxWhiteMixColour`.
- **Position (D5):** a continuous pan or tilt write goes to `position` with the other axis kept,
  through the `setPosition` resolution the fixture sheet uses.
- **Flash (D10, the stored half):** `flash(percent)`, scaled through each head's range like a fader.
- **Spec-driven checks:** `refuseWrongKind` and `targetControlKind` (`BindingControlKind.kt`) read the
  spec's inputs; `refuseTriggerAttribute`, `refuseAxisOnNonColour`, `colourAxisOrNull`,
  `RigKeyReferences.renamed` (`K/ai/RigKeyReferences.kt:259`) and `K/routes/cueDelete.kt:117-129` read
  `binding.on`; health is subject health plus parameter checks (`BindingHealthEvaluator.evaluate`'s 48
  arms). Fan refuses a single fixture (D2).
- **Database step 15** (`K/state/SurfaceGrammarMigration.kt`, `PRAGMA user_version` 15, after step 14),
  on `AttributeNamesMigration`'s pattern, backed up to `<db>.pre-surface-grammar-<utc>.db` only when
  rows exist. It rebuilds `control_surface_bindings` from the model so `uq_surface_binding_slot`
  includes `part` (SQLite can't alter an index; step 14's `rebuildFromModel` is the precedent) and
  translates every payload through one pure function (P4): an encoder row holding a button target
  becomes its `PUSH` part, read from the profile; an int-only cue or stack row resolves to its uuid in
  the project, else becomes `Unknown` keeping its bytes; flash's raw max becomes a percentage; each
  on/off form becomes its toggle with a How. Strip rows carry across as they are.
- **Sync (P2):** `ControlSurfaceBindingJson` gains `part`; the writer and `SUPPORTED_FORMAT_VERSION` to
  `main`'s plus one (53 when written), `minReader` the same; `LegacyNames.record("controlSurfaceBindings")`
  calls the shared translate for older archives; base snapshots taken before the bump are re-hashed,
  as `PreV39BaseSyncTest` covers v39, so an untouched binding never reads as a conflict;
  `RichProjectFixture` gains a push binding, a How of On, a percentage flash and a selection subject,
  each non-default.
- **OSC (D19):** `OscAddresses.kt`'s entries build `Binding`s; the address strings and their arguments
  don't change; `OscPatternDto`'s description strings change.
- **Client, wire compatibility only:** `F/api/surfacesApi.ts`'s `BindingTarget` union becomes the new
  `SurfaceBinding` shape, with one adapter that maps it to and from the old union so the current
  screens keep working unchanged; per control, the adapter shows the `MAIN` part, else the `PUSH` part
  (which is what a migrated row is). Session 3 deletes the adapter with the screens.
- **Tests:** `BindingTargetSerializationTest` → a `Binding` serialisation test over every function;
  `BindingHealthEvaluatorTest`, `SurfaceDispatchTest`, `SelectionWritesTest` (→ `Heads.of`, with a
  selected cell, an element group and a white-mix head), `ControlSurfaceBindingBatchTest`, `…AxisTest`,
  `…HealthTest`, `…TolerantDecodeTest`, `…ResolverTest` (order cases kept) rewritten;
  `SurfaceInputRouterTest`'s push, flash and select-hold cases rewritten, the rest mechanical; a
  position write under a running Circle holding it back; two devices of one type each holding their
  own flash; a test that every function has a spec (P3); `SurfaceGrammarMigrationTest` over a frozen
  `src/test/resources/db/pre-surface-grammar.sql` holding every variant (never regenerate it); a
  `LegacyV52ArchiveTest` and a `PreV53BaseSyncTest`; `OscAddressesTest` rewritten as an address →
  behaviour table, which is the proof QLab is unaffected; `ProjectRoundTripTest`; the OSC handler,
  `SurfaceBindingRoutesTest`, `RecordingSurfaceActions`, `SocketMessageWireFormatTest`,
  `VenueAttachTest`, `McpPatchEditToolsTest`, `CueListRouteTest` and `CueSharingRoutesTest` where they
  build targets; the frontend adapter both ways.
- **Docs:** `docs/midi-control-surface-engineering.md` (the binding model, the functions table, parts,
  heads, step 15); `docs/sync-engineering.md` (the new version); `docs/osc-engineering.md` (targets);
  the root `CLAUDE.md`'s database steps list and its surface references; `frontend/docs/midi-surface-engineering.md`
  (the adapter, temporary); `FU-MANUAL-SURFACE-SETUP-S1` in `docs/plans/manual-validation.md` (§6).
- **Local:** the full `./gradlew test` (a `formatVersion` bump and a migration) and
  `npm run check:quick`.

**As built** (where the code led away from the text above):

- **The wire bridge sits on the desk, not in the client.** The current screens name stacks and cues by
  int id, and only the desk can map an int to the uuid a binding names, so `/surface-bindings` and
  `surfaceLearn.commit` take `target` in the old union's words beside `binding`, and every row answers
  `target` and `targetType` beside `binding` and `part` (`midi/SurfaceGrammar.kt`'s `legacyTarget`, with
  the project's ints). The client's half is the `SurfaceBinding` type and `bindingsForScreens` (a
  control's main part, else its push). Its tests are `SurfaceGrammarTest`'s round trip and the routes'.
- **Step 15 drops the stale index rather than rebuilding the table.** SQLite drops an index; the
  reconciliation has already added `part` and the new index, named `uq_surface_binding_part_slot` so it
  sits beside the old one until the step drops it. The backup is taken only when a row translates.
- **A tempo function with no subject is master 1**, as everywhere else in the desk; a stored binding
  names any other master as a record.
- **`buskPage` takes a page record as its subject**, or none with a step.
- `MissingStack` and `MissingCue` health carry the uuid; their ids are 0.
- A write with no part is placed by the shared rule (`inferPart`; the old union by `partFor`, the
  migration's): an encoder's push for a function only a button takes.

### Session 2 — Feedback

- **One entry (P3):** `Entry(display, control, part, binding, fn, subject, jump)` and an `Index` by
  signal and by control part replace the seven entry kinds, the nine LED lists, the per-kind resync,
  subscribe and stop arms, and the LED `when`s (`SurfaceFeedbackPublisher.kt:229-387`, `:916-1092`,
  `:1157-1257`, `:1403-1497`). A button function declares `led` (abstract: a light, or explicitly
  none); a continuous one declares `read` and `signals`. This fixes the missing tempo catch-up and the
  two-device flash LED, and drops the dead LED lists and the double seed (`:1085-1091`).
- **The readback (D6, §3):** `LayerResolver.localOver`, with `fallbackFor` calling it; reads beneath
  `FLASH`; park ignored. `AttributeChannelResolver.readHead` takes the composed value rather than a
  channel reader, and the colour-axis write builds on the same value, white, amber and UV included.
  The fan's base (`routes/programmerFan.kt:495`) reads `localOver` too.
- **Off the transmit thread:** the feedback loop polls `epoch`, `blind` and the cue-layer identity;
  the continuous path stops listening to `channelsChanged` on the transmit path. Precompute each
  head's `FxTarget` and fixture at rebuild.
- **One jump rule (D7):** a `JumpPolicy` on the entry; soft takeover's crossing becomes its undriven
  arm; the fan's `PICKUP_STEPS`, FX speed's `ENCODER_JUMP` and its fader echo rule, and the
  unguarded strip encoder go. `effectivePolicyFor` (`:742`) reads the index.
- **LEDs:** GO lit while its stack runs and a cue lit while held loose (the providers exist); encoder
  pushes indexed as buttons (`EncoderDescriptor.pushLed`) and their momentary LED re-asserted on
  release as buttons are (`:703-720`).
- **Signals, not rebuilds:** a selection change re-reads the selection's entries rather than rebuilding
  the index and resyncing every device (`:455`). One `Scale7Bit(min, max)` replaces the inline BPM
  scaling (`:1427`, `SurfaceActions.kt:897`) and the grand master's and stack FX amount's helpers.
- **Live state per device (D16):** `ActiveBankState` and `EncoderBankState` keyed by `displayKey`; a
  Switch layer or Encoder page press acts on the device it came from; `surfaceBank.*` and
  `surfaceEncoderBank.*` carry `displayKey`; the client's `BankSwitcher` keys by device.
- **Correct the record:** `GlobalScalerState`'s KDoc (`:35-37`) on what feedback sees.
- **Tests:** `SurfaceFeedbackPublisherTest` rewired to seed the store or the cue layer and drive the
  poll (15 tests seed the DMX buffer today); new: Blind shows the staged value, a programmer-only
  change re-feeds, touch-off in Blind keeps the motor where it is, a colour turn in Blind keeps the
  staged white, amber and UV, a Local FX doesn't move a motor, a held flash doesn't move its key's
  faders, a parked dimmer's fader shows Local or the cue, tempo catch-up on touch-off, two devices
  each keep their own layer and encoder page and both light a flash; `SoftTakeoverStateMachineTest`,
  `SelectionFanTest`'s pickup and `SelectionFxSpeedTest`'s jump onto the shared rule;
  `AttributeChannelResolverTest`'s `readHead` cases; the every-function-declares-an-LED test (P3).
- **Docs:** `docs/midi-control-surface-engineering.md` (feedback, the readback, the jump rule, the
  threading table); `docs/lighting-composition-model.md` (where a surface reads); `docs/websocket-engineering.md`
  (the per-device frames); `FU-MANUAL-SURFACE-SETUP-S2`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `midi`, `fx`, `plugins` and
  `routes` packages; `npm run check:quick`.

- **As built** (corrections to the above, from the code): the plan's line numbers in
  `SurfaceFeedbackPublisher.kt` are from before session 1, which had already folded the seven entry
  kinds' targets into `Does`. The signals are one enum, `Signal`, not parameterised: a signal re-reads
  every entry that follows it, and the parameterised ones (`Tempo(master)`, `StackFx(stack)`,
  `EncoderPage(device)`) are operator rate, where re-reading a handful of controls costs nothing. A
  reading with nothing to show is `Reading.Nothing`. The takeover column's PICKUP, the default, is now
  driven or undriven by the control: a motor fader, which defaulted to IMMEDIATE, is driven (a move
  within eight steps, or while touched). The poll watches the store's `revision`, not its `epoch`: a put
  bumps the epoch before it installs the slot, so the epoch alone could leave a fader on the old value;
  the revision moves again after the install. A new fan gesture keeps a guard of its own (an input more
  than eight steps from the centre shows the centre again), because the jump rule judges against what
  the ring was last shown, which a reset reaches a moment later.

### Session 3 — The served picture, and editors A and C

- **The desk (D14, D15):** `GET /api/rest/surface-functions` from the specs (id, label, group, parts,
  subjects, hows, params with their options, writes-to, a line of help); `surfaceMap.state`,
  `StateFlow`-backed, pushed on any binding, encoder-page, health or record-name change (no jobs yet:
  `from` is `direct` or the strip, `none` otherwise); `PUT` / `DELETE …/surface-bindings/slot`, the one
  write the editors use, refusing by the spec; identify mode (`surfaceIdentify.begin` / `.cancel` /
  `.captured`) per window, replacing `surfaceLearn.*`: the next press on any attached device is
  captured with its part and **not dispatched**, a banner frame while it waits, 30 s. Labels name
  records by name (no more *Go · stack 12*); `CueUses.bindings` (`K/routes/cueDelete.kt`) reads the
  same labels.
- **The fixtures (P5):** `src/test/resources/surfaces/catalogue.fixture.json` and a map fixture, read by
  a Kotlin test and a TypeScript test.
- **Client, the data:** `F/store/surfaces.ts` consumes the map and the catalogue; one `BindingForm`
  (`F/components/surfaces/BindingForm.tsx`) renders any function from the catalogue: Does grouped and
  searchable, its params, On (the selection, groups and fixtures from the visible lists, records from
  their lists), How chips, Layer chips, the *Writes to* word.
- **Editor A (D12):** `SurfaceInspector` becomes the per-part editor (§4), with *Touch a control on the
  desk* (identify) and *Make this control its own* on a strip control; `SurfacePanel` reads labels and
  health from the map, its drop zones and crosses gone; the inspector's DMX stage line and its emitter
  descriptors go.
- **Layer chips (D13):** `BankSwitcher` views and sends nothing; live state dims while the viewed layer
  is not the device's own; the `?binding=` deep link no longer moves the hardware.
- **Editor C (D12):** `F/components/surfaces/SurfaceSheet.tsx` on the sheet kit (§4). The kit gains
  `SheetColumn.accepts(row, value)` beside `takesValue` (`F/components/sheet/sheetModel.ts`) so a fill
  over mixed rows skips by value and names what it skipped; a records plan for *Fill in order* on the
  `SpreadPanel` address plan's model; live values kept out of the rows.
- **Delete:** `SurfaceLibrary` and its test, `BindingTargetPicker` and its test, `BindingMatrix`,
  `LearnModeOverlay` (its per-window session filter kept as a capture hook), `F/lib/surfaceResolve.ts`
  and `F/lib/surfaceDrop.ts` with their tests, `targetUtils`' `describeTarget` and
  `matchesBindingTarget`, `recordOptions`' naming, `F/lib/healthDescriptor.ts`, session 1's adapter,
  and the drag monitor, edit mode and placement badges in `F/routes/Surfaces.tsx`, which no longer
  needs `DeskDndProvider`.
- **Words (D17):** *Layer A / B*, *Encoder page*, *Writes to Local* on screen; glossary rows at status
  `screen`, their *Was* *bank* and *encoder bank*.
- **Tests:** the map and catalogue fixtures both sides; the slot write's refusals; identify capturing a
  bound GO without firing it, its timeout and its per-window scope; the form over a sample of the
  catalogue; the inspector's per-part editing, layer chips and identify; the sheet (grouping, fill
  with skips, dead first, the phone form); `Surfaces.test.tsx` and `SurfacePanel.test.tsx` onto map
  fixtures; the sheet kit's `accepts`.
- **Docs:** `frontend/docs/midi-surface-engineering.md` rewritten; frontend `CLAUDE.md` §"The MIDI
  surface view"; the root `CLAUDE.md`'s REST and WebSocket lists; `docs/midi-control-surface-engineering.md`
  (the wire, identify); `docs/glossary.md`; `FU-MANUAL-SURFACE-SETUP-S3`.
- **Local:** `./gradlew test` for the `midi`, `routes` and `plugins` packages, and the full
  `npm run check` (several test files deleted and the sheet kit changed).

**As built** (where the code led away from the text above):

- **The map's part** carries `does`, `on` and `how` as §3 names them, and beside them the catalogue
  `entry` it is (so a client never matches a binding to an entry), the row's `bank` (null for both),
  the `stripId` of a part a strip derives, its `takeoverPolicy` and a `shows` sentence — editor A's line
  and editor C's column, written on the desk like the label. There is no `params` (they are on `does`)
  and no `beats` until session 5. Strip slots are parts too, so the sheet edits a strip. A strip's
  encoder is labelled at the encoder page of the first attached device of the type, since the page is a
  device's and the map a type's. Only the current project is mapped: the Surfaces tab is on the rig only.
- **The catalogue is entries, not functions**: a grand master with a level and a tempo with a BPM are
  entries of their own (`grandMasterLevel`, `tempoSet`), since the parameter makes a button of each.
  `SurfaceCatalogue.entryOf` is the one answer to which entry a stored binding is.
- **Learn is gone, not renamed**: `surfaceIdentify.*` captures and the editor binds the captured part
  through the slot write, so `surfaceLearn.commit`, `learntPart` and Learn's own collector went. The
  router asks `SurfaceIdentify.offer` before it dispatches, and swallows the rest of the captured gesture
  on that control until it is still for 750 ms, so a fader moved to identify it does not write as it
  settles.
- **`surfaceBank.set` is gone** (D13): nothing in the app switches a layer, so the frame went with the
  last sender. The `expand` route went too: *Make this control its own* is a slot write of the derived
  binding on the viewed layer.
- **The bridge went with the screens**: `/surface-bindings` takes `binding` only and no row answers
  `target`; `SurfaceGrammar.legacyTarget` and `LegacyRefs`' reverse ids went with it. `lib/colourAxis.ts`
  and `components/surfaces/targetColour.ts` went too, since nothing read them once the map named the
  colour.
- **Fill in order** is a `records` plan on `SpreadPanel`, its trigger word *Fill*, and the sheet does the
  fill (`planFill`), naming each row it skipped; the kit's `SheetColumn.accepts` has a `refused` beside it
  that names the rows a commit dropped.
- **`CueUses.bindings`** names a binding by its control in the map's words: *X-Touch Compact · Button 36 ·
  Layer A* (`SurfaceLabels.controlName`), with no layer word on a profile that has none.
- **The map has a no-layer entry**, `""`, beside each layer: a device starts on no layer after a restart,
  where only the rows on both layers fire, and the tab views the device's own layer, or that one, with a
  *No layer* chip and a line saying so while the desk is on none. Only the types in use are built
  (attached, bound in the project, or built before), and a speed master's rename rebuilds the labels.
- **Identify passes a fader's touch and a fader at 0 through**: swallowing the touch-up left a fader
  touched after a capture, and a fader at 0 is at rest, which Learn skipped too. The wait carries
  `remainingMs`, counted down on the browser's clock as `effects.armed`'s is.
- **A control a strip derives has no *On* of its own** in the sheet: its subject is the strip's, so the
  cell reads it out and *Fill* skips it. *Remove* says which rows it takes off — *both layers*, or a
  layer only.

### Session 4 — New functions

- **Programmer verbs (D9):** `clear` (`ProgrammerWriter.clearAll` at the programmer fade), `blind`
  (`setBlind`; Toggle or While held; lit while blind), `release` (`programmer.clearTarget` per target
  of its subject), `record` (`performProgrammerRecord`, `K/routes/programmerSurface.kt:77`, appending
  a new cue to the named stack with all attributes and the Record sheet's defaults), `update`
  (`performProgrammerUpdate`, `:404`, Mode A onto the include target; refused and logged when nothing is
  included). Record, Update and Clear run on the serial lane with Undo.
- **Subjects (D2, D10):** `locate` on a group or fixture (per target, as the selection's does);
  `fan` on a group, its anchor per subject; `fxSpeed` on a group or fixture (the Local FX painting
  those heads); `flash` on the selection, releasing the heads it wrote; `level` following the encoder
  page on any encoder and subject.
- **No format change (P2), no new OSC address** (the OSC plan's D6 keeps Record off OSC).
- **The screens:** nothing of their own; A and C offer the new functions from the catalogue.
- **Tests:** each verb's dispatch and refusals (Record into a stack, Update with nothing included,
  Blind while held), Locate, Fan and FX speed on a group and a fixture, a flash on the selection
  released after the selection changed, follow-the-page on a selection encoder; the catalogue fixture
  updated.
- **Docs:** `docs/midi-control-surface-engineering.md`; `FU-MANUAL-SURFACE-SETUP-S4`.
- **Local:** `./gradlew compileTestKotlin`, then `./gradlew test` for the `midi`, `routes` and `fx`
  packages; `npm run check:quick`.

**As built** (where the code led away from the text above):

- **Clear, Blind and Release carry their own fade**, `fade` in seconds, 0–600 as MCP's programmer tools
  take (`MAX_PROGRAMMER_FADE_SECONDS`), default 0: the programmer fade is a browser setting
  (`lib/programmerFade.ts`), so the desk has none to read.
- **Clear is the programmer's whole Clear**, `clearProgrammerCompletely`, not `ProgrammerWriter.clearAll`:
  the latter leaves the layers, Locate, scenery, tempos and the include target, which the Clear button
  takes.
- **Following the encoder page is a function of its own**, `pageLevel` (*Level on the encoder page*),
  rather than a `level` with no attribute: every reader of `level` names its attribute. It takes any
  continuous control, a fader too, since nothing about the page needs detents.
- **Clear acts at once, not on the serial lane**: it opens no transaction and reloads nothing, so the
  lane's reason does not hold, and queued behind a busy lane it would wipe what was written after the
  press. Record and Update stay on the lane, accepting that a press made while it is busy can land
  before a queued Record reads the programmer.
- **Release over several targets also stops a Local FX all of whose heads lie among them together**
  (`ProgrammerHandler.releaseLocalFxWithin`), which each target's `clearTarget` alone answers as
  partial, as the UI's *Release n* does.
- **Blind is one helper**, `setProgrammerBlind`, which the `programmer.setBlind` frame and MCP's
  `set_blind` now call too, so a surface's Blind takes the programmer's scenery, tempos and fires with
  it. Its LED follows `Signal.PROGRAMMER`, which the poll already moves on a Blind change.
- **Record and Update are refused in a venue** (`VENUE_RECORD_OFF`), as their routes are. Update is
  refused at the press when nothing is included and checked again on the lane, where a Clear since may
  have dropped the include target. A project whose kind cannot be read refuses both. A press reaching the lane after a project switch is dropped.
- **A fan's anchor per subject is a gesture per subject**: the anchor and the amounts both, so a group's
  ring keeps its amount while the selection's turns; `SelectionFan.Shown` is only a stamp, each ring
  reading its own subject's amount. FX speed's rig order is cached per show rather than
  per selection, since it does not depend on the targets, so two subjects do not evict each other.
- **`palette` stays on the selection**: D2 lists it, but no session's text gives it a group or fixture.

### Session 5 — Jobs

- **Profiles (D11):** a `jobRegion(id, name) { slot(…roles) }` DSL generalising `strip()`;
  `XTouchCompactStandard` declares *Strips 1–8*, *Encoders 9–16*, *Buttons 9–16*, *Buttons 17–24*,
  *Buttons 34–39* and *Master strip*; `ControlSurfaceRegistry` refuses a control in two regions and an
  unknown control, as it refuses a bad strip today.
- **Job kinds:** groups as channel strips, fixtures as channel strips, the selection one head per strip,
  cue stacks as playbacks, speed masters, wheels on the selection, wheels by encoder page, palettes onto
  the selection, the busk page showing, GO per stack, Looks, encoder pages, transport for one stack,
  programmer keys, grand master and blackout; each declares the region shapes it fits and its template
  per role (the concept page's B lists what each does).
- **Storage:** `surface_jobs` (uuid, project, device type, region, layer nullable, kind, items), unique
  per (project, device type, region, layer); `GET` / `PUT` / `DELETE
  /api/rest/projects/{id}/surface-jobs/{deviceTypeKey}/{region}?layer=`; database step 16 turns strip
  rows into a groups-as-strips or fixtures-as-strips job per (device type, layer), gaps kept, and
  deletes them, through the same translate the archive reader uses (P4).
- **Resolution:** direct then job (§3 "Jobs"); the `Strip` function, strip slot ids, `refuseWrongSlot`
  and its route copy (`projectSurfaceBindings.kt:271-290`) and `deriveStripTarget` go; a live job
  re-derives its region on its signal without re-arming pickup; the map's `from: job`, `jobId`, `item`
  and `beats`.
- **Sync (P2):** a `surfaceJobs/` folder: disposition in `SyncDispositions`, exporter and importer,
  `SyncCoverageTest`, a `RichProjectFixture` job with non-default items and layer; the format and
  `minReader` to `main`'s plus one; a frozen archive of the previous version with strip rows.
- **Editor B:** the Jobs panel (§4), its items reordered with up and down buttons, × leaving a gap so
  later items keep their slots (the operator's choice) and *Add* filling the first gap; A's *From
  Groups as strips · item 3*.
- **Tests:** profile validation; each job kind's derivation; direct beating a job at each layer level;
  a live job following the selection and the busk page without re-arming pickup; step 16 over a frozen
  database; the archive translate; `ProjectRoundTripTest` and the clone; the Jobs panel.
- **Docs:** `docs/midi-control-surface-engineering.md` (jobs replace strips); `docs/sync-engineering.md`;
  frontend `docs/midi-surface-engineering.md`; the root `CLAUDE.md`; `FU-MANUAL-SURFACE-SETUP-S5`.
- **Local:** the full `./gradlew test` (a `formatVersion` bump and a migration) and
  `npm run check:quick`.

**As built** (where the code led away from the text above):

- **The database step is 17**: step 16 is venue-rig-fixes' `AutoNumberTidyMigration`, which landed
  first. It is `state/SurfaceJobsMigration.kt`, over `src/test/resources/db/pre-surface-jobs.sql`; the
  format and `minReader` are 55 (`legacy-v54/` the frozen archive).
- **The region ids** are `strips`, `encoders`, `row-2`, `row-3`, `keys` and `master`; a slot names one
  control per role, `FADER · SELECT · ENCODER · FLASH · BUTTON · PUSH`. The kind ids are `groupStrips`,
  `fixtureStrips`, `headStrips`, `stackStrips`, `masterStrips`, `wheels`, `pagedWheels`, `palettes`,
  `buskPage`, `gos`, `looks`, `encoderPages`, `transport`, `programmer` and `grandMaster`: a bare
  `groups` reads as the old name the glossary test refuses.
- **The translate makes one job per region and layer, not per device type and layer**: the master strip
  is the `master` region's job. Strips of mixed kinds keep the majority's kind (groups win a tie), and a
  strip of the other kind becomes its controls' own bindings where they have none of their own, so
  nothing it did is lost. A strip's takeover policy is not carried: a job's controls take their part's
  default. A job's uuid is derived from the strip rows it replaces, so two desks importing one archive
  agree; a strip row on a profile this desk lacks is kept, unread.
- **Wheels by encoder page** are the selection's first heads, one a wheel, each on the attribute the
  encoder page names, since the page holds one attribute; **encoder pages** are buttons each setting the
  page to its item's attribute.
- **Jobs ride the map frame** (`SurfaceMapFrame.jobs`, each item by name with its health), beside the
  `GET …/surface-jobs` list, so the panel needs no second read (P5); a write announces
  `BindingChange.JobsChanged`, which the browser's `bindingsChanged` skips. The kinds are served by
  `GET /surface-job-kinds` and pinned by `src/test/resources/surfaces/jobKinds.fixture.json`. `beats` is
  the job's phrase, and `item` is 0-based.
- **A strip id is an unknown control now**, refused as any undeclared control is; nothing replaces
  `refuseWrongSlot`. The fixture and group sheets' bound-control badges stop counting strips (session 6
  replaces them), and a rig key rename walks jobs' items as it walks bindings.
- **A live job's slot health is always fine**: its subject follows the selection or the page, so there
  is nothing stored to go missing.

### Session 6 — Assign from the thing

- **The desk:** `surfaceUses.state` (§3 "Wire"), keyed by subject (a record's kind and uuid, a group, a
  fixture), pushed with the map.
- **Doors:** *Assign to surface…* on `F/components/runner/ShowOverview.tsx` (stacks),
  `F/components/runner/CueActions.tsx` (cues), `F/components/busking/BuskPad.tsx` (pads),
  `F/components/busking/BuskPageStrip.tsx` (pages), `F/components/looks/LookDetailSheet.tsx`,
  `F/components/templates/PaletteEditor.tsx`, `F/components/speedMasters/SpeedMasterDetailSheet.tsx`,
  `F/components/fixtureSheet/FixtureSheet.tsx` and `F/components/busking/RigTile.tsx` (fixtures and
  groups), each through `F/components/osc/CopyOscAddressMenu.tsx`'s dropdown and context adapter.
- **The sheet:** identify mode with its banner and *Cancel*, or a click on the picture (the only path
  in remote mode); the functions whose subjects include the record's kind and whose inputs include the
  part's; Layer chips; *Replaces …*; Assign through the slot write.
- **Bound on:** one chip from `surfaceUses` on every door's record, replacing `BoundControlBadge` and
  `FixtureBoundControlsRow` (whose fixture and group rows disagree about strips today).
- **Tests:** each door opens the sheet; the offer filtered by record and part; cancel and timeout;
  *Bound on* updating after an assign elsewhere.
- **Docs:** frontend `docs/midi-surface-engineering.md` and `CLAUDE.md`; `FU-MANUAL-SURFACE-SETUP-S6`.
- **Local:** `./gradlew test` for the `midi` and `plugins` packages; `npm run check:quick`.

## 6. Operator checks

One row in `manual-validation.md` per merged session, on the rig with the X-Touch:

- `FU-MANUAL-SURFACE-SETUP-S1`: an existing show's bindings all still work after the upgrade; a pan
  encoder beats a running Circle; a strip encoder's push does nothing; a flash on a head whose range
  ends below 255; the blinder takes a colour move; QLab's OSC cues unchanged. Also check **Layer B's
  MIDI**: the profile maps Layer A only, so confirm a layer B binding fires.
- `FU-MANUAL-SURFACE-SETUP-S2`: a motor fader holds still under a Local FX; Blind shows and keeps the
  staged value; a held flash leaves the faders alone; the jump rule on a fast encoder flick; confirm
  the X-Touch **adopts a ring value it is sent** and what **byte darkens a ring**
  (`ringOffValue`); a push LED re-asserted on release.
- `FU-MANUAL-SURFACE-SETUP-S3`: touching a bound GO selects it without firing; viewing layer B leaves
  the desk on A; the table on a phone.
- `FU-MANUAL-SURFACE-SETUP-S4`: Record from a button makes a cue; Blind while held; Release on a group.
- `FU-MANUAL-SURFACE-SETUP-S5`: an upgraded show's strips arrive as a job; reordering its groups moves
  the strips; the busk page job follows the page buttons.
- `FU-MANUAL-SURFACE-SETUP-S6`: *Assign to surface…* from a stack card by touching a button.

## 7. Explicitly out of scope

- Renaming `bank` and `encoderBank` in code, the database and the wire (D17): the screen changes, the
  identifiers wait for a rename session.
- Bindings per device (D16 keeps them per type), and a stable per-device identity across reconnects.
- Relative encoder encodings, 14-bit faders, scribble strips and colour LEDs.
- Record, Clear and Blind over OSC (the OSC plan's D6).
- Fire on a group (D2).
- A profile editor or learnable profiles for an unmatched device (`FU-OSC-LEARN` and the unmatched
  state stay as they are).
- An LED for a sub-selection (`FU-SURFACE-SUBSELECT-LED`).
- Job regions for any device but the X-Touch Compact.
