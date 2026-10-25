# Lighting Composition Model

This document specifies how lighting7 composes the DMX channel output sent each frame. It is the source of truth for priority rules, blending, programmer semantics and cue crossfades.

Related:
- Strategic plan for adopting this model: [cue-authoring-unification-plan.md](plans/completed/cue-authoring-unification-plan.md).
- The programmer redesign that introduced Tier 2 and renumbered the stack: [programmer-redesign-proposal.md](plans/completed/programmer-redesign-proposal.md).
- FX engine details: [fx-engineering.md](fx-engineering.md).
- DMX transport and parking: [dmx-engineering.md](dmx-engineering.md).
- Prior-art survey that drove these decisions: [research/composition-model-prior-art.md](research/composition-model-prior-art.md).

## Overview

Per frame, the DMX output for each channel is resolved by walking the ordered tiers. The top-most non-empty contribution wins:

```
Tier 1  Parking                        absolute override per channel
Tier 2  Programmer                     sticky manual values + programmer-owned FX; blind gate
Tier 3  FX                             tempo-synced FX, priority-ordered, blend modes
Tier 4  Cue attribute assignments      deterministic per-cue state, per-category HTP/LTP
Tier 5  Baseline / defaults            usually 0
```

Intuition: parking (Tier 1) sits on top for safety. The programmer (Tier 2) is the operator's hands — whatever was touched manually wins, console-style. FX (Tier 3) modulate over the playback state, except where the programmer holds an attribute. Playbacks (Tier 4) assert state. Defaults (Tier 5) fall through.

**Scenery sits beside the tiers, not in them** (stage-view plan session 8): a tab drawn, a
flown piece trimmed or a piece shown is resolved separately, never as a channel — see §"Scenery" below.

**The venue hold sits above the stack, not in it** (venue plan P5, session 6). A show on a venue whose
pinned version is behind the installed one on output is held: the stack resolves exactly as above —
park included — and every view draws the result, but the wire carries zero on every channel **but a
parked one**, because the show's addresses belong to a rig the hall no longer is — while a park is the
operator's hand on the channel, often a hard-powered fixture's dimmer held up, and goes out as parked.
It is not a tier: nothing
composes under or over it, and it changes no tier's state, so lifting it (an Update, or *Run against*)
puts the composed output on the wire at once. *Run against* leaves the installed version's trigger and
command channels reserved idle above park. Programmer Blind (Tier 2's gate) is a different thing and
is unchanged; the app header's chip reads *Blind · venue* for this one. `docs/dmx-engineering.md`
§"The hold" has the transmit-time rule.

> **Historical note.** Before the programmer redesign the manual tier ("Direct Live
> Writes") sat *below* playbacks at the old tier 4, and FX/Assignments were tiers
> 2/3. Only the archived plans in [plans/completed/](plans/completed/) still use those
> numbers; code and docs are on the numbering above.

## Tier 1 — Parking

The `ParkManager` holds per-channel overrides. If a channel is parked, the transmitted value is the parked value — no other tier contributes.

Implementation note: parking is applied at transmit time in `ArtNetController` (after tier 2–4 composition has already written to the controller's `currentValues`). This is an optimisation — conceptually, parking is Tier 1 and the pre-composition pipeline can short-circuit for parked channels.

Rationale: parking protects specific channels during maintenance, rigging checks, or troubleshooting. It must be unconditional and must not be overwritten by cue-apply, FX, or manual writes.

**Releasing park does not move the output.** Whatever sits under a parked channel is unrelated to what the rig is emitting — usually 0 — so `ParkManager.unpark` / `unparkAll` first hand the parked value *down* into the programmer's channel sideband (owner `unpark`, `touched = false`) and the controller buffer, then drop the override. The hand-off happens before the entry is removed, so no transmit frame lands in a window where park is gone but the stale value underneath is still in place. Net effect: unparking settles exactly where a manual channel write of the same value would, and `park → unpark → park` is a no-op on the wire. Rigs park hard-powered fixtures hung off a dimmer, where an unpark-to-0 snap is a safety failure rather than a cosmetic one.

## Tier 2 — Programmer

The console-style programmer: a sparse per-(fixture, attribute) overlay of sticky manual values held in `ProgrammerStore`. It is simultaneously the live override for busking and the staging buffer that [Record](#record--include--update) serialises. **An active programmer entry wins over cue values and suppresses FX on that attribute** — for HTP categories too (predictability beats max-merge in a busking-first system).

Writers: web busking (`programmer.*` ops and the `updateChannel` compatibility shim), MIDI surface faders, surface flash buttons, FX preset toggles / editor previews, Locate, and the unpark hand-down.

The AI surface is a writer here too — `set_programmer` writes plain values as `WEB`, `clear_programmer` and `set_blind` are the desk's Clear, Release and Blind, and `apply_look` and `apply_palette` (each unless `asValues`) and `create_look`'s `apply` go through `ProgrammerLayerStack.toggle` — so `get_current_state`'s `programmer` section reports the layer stack, the winning entry per (fixture, attribute), the blind flag and the include target back. A *toggle* the model cannot read is a toggle it undoes on the second attempt. The programmer's save gestures are on that surface too — `record_cue`, `record_look`, `include_into_programmer` and `update_from_programmer` — sharing the routes' orchestration (`routes/programmerSurface.kt`, `performProgrammerRecordLook`) rather than reimplementing it, since which rows an Update overwrites is decided by the include-target bookkeeping described above and not by the caller.

**There is exactly one programmer, and it is shared by every client — by design, not by omission.** A second device is a second *window onto one desk*, not a second seat: whoever signs in elsewhere sees this state and may edit it, and the three broadcast frames (`provenanceState`, `programmer.includeTarget`, `programmer.layerState`) exist to keep those windows honest. Concurrent authorship is explicitly not a scenario this desk designs for, which is what spares it a merge rule: `LayerResolver.fallbackFor` returns **one** value per (fixture, attribute) because DMX is one byte per channel, so two authors would force a "whose value wins?" policy that nothing here expresses — HTP cannot be it (see the line above), and the `Slot.seq` bands are the only extensible axis. Decided 2026-08-23; the reasoning, and two places the cost of the alternative was previously misjudged, are in [`FU-PROG-PER-USER`](plans/followups.md#fu-prog-per-user).

**There is likewise exactly one selection, and it is the desk's, not a client's.** `state/DeskSelection.kt` holds the groups and fixtures a selection-relative surface control (a `SelectionAttribute` fader, a `LocateSelection` button) and a busk-page press act on; the busk view, the programmer page and an X-Touch's select buttons all read and write this one list, and the `selection.*` WebSocket family keeps their windows honest with one `selection.state` frame per change. The argument is the programmer's, verbatim: DMX is one byte per channel, so two selections would force a "whose press wins?" policy nothing here expresses. A group and its members are two spellings of one selection — `fx/TargetCoverage.kt` is the one expansion rule, shared with the programmer layer stack's press, so a select button lit for a group means what a pad's ring means — and toggling a covered target off narrows head by head, exactly as a sibling press narrows a layer. It is transient: cleared on project switch, pruned of targets a fixture reload dropped, never persisted. Decided in `docs/plans/completed/midi-surface-plan.md` §D2.

### Ownership

Each (fixture, attribute) holds a small **recency-ordered stack** of per-owner slots rather than a single flat value:

- **Put**: installs or refreshes the owner's slot and moves it to the top. The most recent write wins on the wire regardless of which subsystem made it — busking over a located fixture updates the output, matching console intuition (the thing you just touched is the thing that changed).
- **Clear**: removes only the caller's own slot. If other owners still hold the attribute, it falls back to the **most recent surviving owner's value** (then Tier 4, then baseline). Releasing a Locate no longer wipes a busked dimmer level; toggling a preset off no longer destroys a locate or another preset's write on a shared attribute; releasing a flash restores the fader level underneath.
- **Read** (`get`, on the 50 Hz FX-reset hot path) returns the top of the stack — O(1) and allocation-free.

Owners: `web` (busking UI + `updateChannel` shim), `surface` (MIDI faders), `flash` (flash press/release — separate from `surface` so releasing a flash restores the fader or busked level underneath), `preset:{id}` (one per preset, previews use a synthetic negative id), `locate`, `unpark` (park-release hand-down), and `include` (cue contents loaded by [Include](#record--include--update); its slot survives underneath a later operator write, which is how Update tells an edit from an untouched include).

One deliberate exception: **all Locate targets share the single `locate` owner**, so locate-vs-locate overlap (releasing a group locate while a member is individually located) is *not* resolved by the store's fallback — same-owner writes overwrite each other. `LocateManager` keeps its own re-assert loop for that case, which also re-resolves locate values and drops stale targets on the way.

### Touched flag

Every slot carries a sticky `touched` flag — never value-diffed. `true` marks an operator edit and is what Record and the Update checklist read. `false` marks a mechanical hand-down: the `unpark` owner's slots, which must be releasable like any manual write but must never leak into a recorded cue.

### Channel sideband

Raw channels with no attribute-level lift live in a parallel per-channel map with the same owner-slot semantics:

- channels with **no backing attribute** (`updateChannel` on an address no fixture or head
  attribute covers),
- **pan/tilt axes** written as raw channels (lifting one axis to a `position` entry would freeze the other axis too),
- **every unpark hand-down** (inherently channel-shaped — lifting one channel would freeze its attribute's sibling channels).

Across granularities, **recency arbitrates**: an attribute entry and a sideband slot covering the same channel compare write sequence numbers and the newer wins — an unpark hand-down beats an older locate entry on the channel it covers. A deliberate operator attribute write (`web`/`surface`) additionally *absorbs* the sideband beneath its channels so a stale raw value cannot resurface when the entry clears; momentary owners (flash, locate, presets) do not absorb, so their release reveals what the sideband held.

### FX suppression

An attribute with an active programmer entry (blind off) suppresses **every** FX on it — cue-owned and manual alike. The tick's reset pass paints the programmer value; the FX's apply is skipped for that (fixture, attribute) only, so a group FX keeps painting its other members. Clearing the entry lets the FX resume on the next tick — this is what makes Locate non-destructive (it used to remove covering FX; now they freeze under the locate and resume on release).

Exempt: FX in the reserved **programmer priority band** (`FxEngine.PROGRAMMER_FX_PRIORITY_BASE`, strictly above every cue-derived priority). These are programmer-owned FX — the busking pad creates them by passing `programmerOwned: true` on the FX add routes — and they modulate *on top of* programmer values rather than being suppressed by them. They are also exempt from the cue auto-tag in `addFx`, so a busk started while an FX_APPLICATION script runs isn't swept by that cue's teardown. Sideband channel slots do not suppress FX — only attribute entries do. And an entry suppresses FX on **its own key** alone: a `pan` entry does not hold back a Circle keyed `position`, though both paint the pan channel. The rule is one pure function, `fx/FxSuppression.kt`, which the tick, provenance and the attribute-stack read all ask (fx-engineering §"Programmer suppression and the priority band").

### Blind

`programmer.setBlind { blind, fadeMs? }` gates the programmer's contribution out of the merge without touching the stored state: entering blind releases programmer-held attributes to the tiers below (cues → baseline), the FX they were holding back resume painting, and writes made while blind stage silently. Exiting restores exactly what was staged. `touched` flags survive the round trip.

**Blind holds the programmer's FX too** (show-cue-editing plan D7, §3.6). While blind, every instance in the programmer priority band is held — a `programmerOwned` add, the FX Include spawns, and every instance a programmer layer spawned — so a Blind edit of a cue with an FX puts nothing on the rig, as a Blind edit of its values never did. Cue, Look-in-cue and script FX are not the programmer's and keep painting. Held is not stopped: the tick keeps the instance's phase (a beat FX's is its master's tick; a wall-clock one's clock — a beat FX on *Seconds* included — advances every pass; a stateful one is still computed) and skips its apply, the reset pass putting the tier below on the key as it does for a suppressed FX. So leaving Blind lands it mid-phase, in step with the FX it was started beside, and **grows it from nothing over the Blind fade**, like a press (effects plan §3.5, D13): its envelope runs 0 → 1 over that fade, so a 0 s Blind fade paints it on the next tick at full — except an FX over a value the programmer holds, which lands at full at once, by §Fades' rule. Entering Blind holds it at once. A held FX is still a running one, so its key stays FX-covered while it is held: the reset pass writes the cooked value there every tick, and a cue GO under Blind lands on that key at the next tick rather than over its fade, as it would under any running FX. The rule is `fx/BlindHold.kt`, read by the tick, provenance (a held FX is never credited with a key) and the attribute stack (`heldBack`, reason `BLIND`); `FxSuppression`'s rule is unchanged, its programmer half still empty while blind. Record and Update read the band from the engine's FX list, so Record under Blind still captures the programmer's FX. The Stage view's programmer sources do not draw a held FX — an FX exists only as output — `FU-BLIND-FX-DRAWN`.

### Fades

Clears, blind transitions, and sets accept an optional `fadeMs`, driving the per-channel `DmxController` ramp (`TickerState`). **Fades apply only to attributes no running FX covers; FX-covered attributes settle on the next tick and snap** — the 50 Hz tick writes covered channels every frame with fade 0 through the conflated channel changer, which would kill any ramp mid-flight. Settings (discrete wheels) always snap regardless of `fadeMs`.

The programmer's **FX** take the same `fadeMs` (effects plan D13): a layer added or taken off — a press (`/busk/pads/{id}/press`, `/palettes/{id}/toggle` and `/looks/{id}/toggle` take an optional `fadeMs`), `programmer.removeLayer`, a cue Include's layers — grows its FX from nothing or shrinks them to nothing over it, and so do `programmer.clearTarget`'s local FX, Clear's band and leaving Blind. One rule splits them: an FX over a programmer value the same gesture moves is stopped (or lands) at once so that value ramps — a key an FX covers never fades, and the value under a shrinking FX would snap — and only the rest grow or shrink over the fade (`FxEngine.releaseFx`, Chris's call; a value fading under a fading FX is `FU-FX-VALUE-FADE-UNDER-EFFECT`). A shrinking FX is `leaving` — nobody's, removed at 0. A press with no `fadeMs` is the press as before: each row's own fade on, a snap off, FX at once. Include's own FX — a Look's, a cue's ad-hoc children — land at full, as before.

### Clearing

- `programmer.clearEntry { target, attribute, fadeMs? }` — releases every owner's slot on one attribute.
- `programmer.clearTarget { target, fadeMs? }` — a fixture's or group's *Release* (fixture-fx-sheets plan W2): every owner's slot but a layer's, on every attribute of the fixture and its heads (or every member of the group, and theirs), the sideband on their channels, and the **local** FX on it (`FxInstance.isLocalFx`: programmer band, owned by no Look, palette, layer or cue). The FX are let go first — one over a value this Release takes stopped, the rest shrinking to nothing over `fadeMs` (§Fades) — and the values then released in one sweep with one republish at `fadeMs` — `clearAll`'s order, so a key a stopped FX was painting fades rather than snapping. A local FX that also drives heads outside the target is left running and named (`partial`) — there is no "stop it on these heads only". Locate's bookkeeping is pruned as `clearEntry` prunes it.
- `programmer.clearAll { fadeMs? }` / `POST /api/rest/programmer/clear-all` — the operator's Clear: sweeps every owner's entries (attribute + sideband) **and lets go of every FX in the programmer priority band** (at `fadeMs`, by §Fades' rule: one over a programmer value stopped, the rest shrinking), then republishes everything in one pass. Also resets locate and preset-toggle bookkeeping so the toggles stay consistent with the swept store. Sideband channels with no backing attribute release to DMX 0 (nothing sits below them). Both counts come back — `cleared` (entries) and `fxCleared` (band FX). Band FX are let go *before* the store sweep so the single cascade republish that follows covers both.

Deterministic release: clearing a programmer entry re-resolves the cascade below it — the attribute lands on the surviving owner, Tier 4, or baseline, never on a stale snapshot.

Clear also drops the include target (below): nothing is staged afterwards, so an Update offering that target would silently write nothing.

### The `sourceGroup` hint

`programmer.set` / `setColour` / `setPosition` against `targetType: "group"` fan out to member fixtures, stamping each slot with the group's name. Record uses that hint to decide whether it may re-emit a group-shaped cue row.

Clients that fan a group-scoped gesture out *themselves* — a group virtual dimmer over members with different attribute shapes, a Highlight release restoring per-fixture values — can pass the hint explicitly via an optional `sourceGroup` field. It is validated server-side (the named group must exist and must contain the target fixture) and dropped with a warn otherwise, so a client cannot conjure a group row out of a write that never came from a group control. The two backend fan-out sites, group Locate and group preset toggles, pass it directly.

### The include target

`ProgrammerStore.lastIncludedTarget` records what Include last loaded, and is what a bare Update writes back to. It is set by Include and by any Record — into a cue or a Look; `record-look`'s `retarget: false`, which Make layer sends, leaves it where it was — (record-then-tweak-then-Update being the obvious next gesture), carries the stack entry a cue was included from (`entryId`, for the indicator's number — a shared cue has one per stack; Update writes the cue, whichever), cleared by Clear and by deleting the cue (removing its entry drops only the `entryId`), and left alone by `clearEntry`, `setBlind`, and a successful Update — Update is repeatable.

It reaches the client two ways: on `programmer.state` as `lastIncluded`, and as a `programmer.includeTarget` push. That push is a **broadcast**, unlike every other programmer reply, because the programmer is shared: a second tab's Update button must offer the same target.

### Provenance

The engine maintains, per (target, attribute), the identity of the winning contributor — `PARKED`, `PROGRAMMER`, `FX` (with `fxId`/`cueId`), or `CUE` (with the winning `cueId` **and** `cueStackId`); baseline keys are omitted. Recomputed on tier events only (programmer mutation, cue republish, FX lifecycle, park change) — never per frame — and broadcast as a full-state `provenanceState` WS message. This powers ownership colouring in the programmer sheet. Note that Update's Mode B checklist does *not* read provenance: provenance correctly names the programmer as the winner, which is precisely the answer "what am I sitting on top of?" cannot use. `FxEngine.underlyingSources` answers that from the programmer-independent Tier 4 winner map instead. Because programmer mutations reply only to the connection that made them, `provenanceState` is also the frontend's signal to re-read `programmer.state` — it is the one broadcast that fires for a write made by a MIDI surface, a locate, or another browser tab. Each frame carries `programmerRevision`, which crossfade weight ticks (winner maps carried forward unchanged — they cannot move the programmer) do not bump, and the client refetches only when the revision moved — so a running fade no longer drives a refetch per frame.

**Provenance names the programmer only where the engine holds the FX back** (fixture-fx-sheets plan W4). Two holds suppress nothing — a sideband slot, and an entry on a key the FX does not paint under (a `pan` entry beside a Circle on `position`) — and under a running FX that paints every channel of the held key, provenance names the FX. It asks `FxSuppression` over the engine's own snapshot, of the key the FX paints; a key it cannot attribute that way keeps its old answer.

### Reading one attribute's stack

Provenance names a winner; `programmer.keyStack` (W1, `ProvenanceService.keyStack`) names everything under it, top first, for one (target, attribute) — a group target answering per member. **Park** with its channels; the **programmer-band FX**; each **programmer slot**, owner by owner, with its value and age (a layer slot naming its layer, a sideband slot naming its channel); the **other FX**, each with `heldBack`; the **cue contributor** — value, cue, stack and Look layer from `underlyingSources` and the one Tier 4 snapshot the read takes, so a cue apply mid-read cannot pair one cue's value with another's attribution; and the **base**, `LayerResolver.baselineFor`. Every entry says whether it is on stage, walking the output order: park, FX by priority, the programmer, the cue, the base, with an on-stage full-weight Override FX, a programmer value, a cue value or a full park covering what lies below — an FX weighted to nothing (a 0 % amount, spent runs) is not on stage, and one under full amount is on stage beside what it mixes with (effects plan P3). `heldBack` is `FxSuppression`'s answer against the engine's snapshot and `BlindHold`'s against one read of blind — the tick's own — so Blind empties the programmer's half exactly as it does on the rig and holds the band instead; `heldBackReason` (`STOMP | PROGRAMMER | BLIND`) says which held it. An FX painting every channel of the key under a sibling key is on the stack too, as provenance credits it. It is a request answered to the asking socket, never broadcast: a stack is open on one sheet, and that sheet re-asks when provenance moves.

### Record / Include / Update

The programmer's authoring loop, exposed as three REST endpoints under `/api/rest/programmer`
(REST rather than `programmer.*` WS ops because each needs a structured reply the fire-and-forget
WS channel can't carry, and because they mutate cues).

**Mask.** All three can be scoped to the console attribute families — `INTENSITY`, `POSITION`,
`COLOUR`, `BEAM` (`fx/AttributeMask.kt`). Deliberately coarser than `AttributeCategory`: the same
physical attribute is annotated differently across heads (a gobo wheel is a `DmxFixtureSetting` on
one fixture and a plain slider on another), so a category-level mask would be fixture-dependent and
silently miss heads. Omitting the mask, or naming all four families, means "everything".

**`record { mode, source, mask?, ... }`** writes the programmer into a cue.

- **Source** — `TOUCHED` (default) takes attribute entries whose winning slot is an operator edit;
  `ALL` also takes untouched slots, which today means unpark hand-downs (channel-shaped by
  construction, so `touched = false` only ever appears in the sideband); `STAGE_SNAPSHOT` captures
  composed stage state plus running FX. Recording *from the programmer*
  rather than from composed state is the point: what you busked is what records.
- **Sideband** — a slot whose channel an attribute covers is lifted into an attribute row; one with no
  backing attribute cannot be (cue assignments have no channel form) and is reported as a skip
  rather than silently dropped. "Covers" is `CascadePublisher.resolveChannelCoveringKey`, which
  reaches the heads of a multi-head fixture, so a head's channel lifts to that head's attribute.
- **Elements** — an element-keyed entry (a head of a multi-head fixture) records as an **element-target
  row**: `targetType` fixture, `targetKey` the element key — the spelling an element has as a press
  target, and the one `buildCueAssignmentsForCue` resolves through `untypedGroupableFixture`. No
  column is involved, so the sync layout is unchanged. Update writes elements back the same way; only
  a palette, which names no targets, still skips them as `ELEMENT_TARGET`.
- **Group shape** — a `sourceGroup` hint only *nominates* a group row. It is emitted iff every
  member of the named group holds an entry for that attribute with an identical value. A stale or
  missing hint therefore degrades to per-fixture rows — verbose, never wrong.
- **Mode** — `CREATE` makes a cue in a stack; `MERGE` upserts; `REMOVE` deletes the rows the
  recording names; `UPDATE_EXISTING` replaces the cue's in-mask content. **Triggers and timed
  children are never touched by any mode** — they are not programmer state, and `CueTriggerManager`
  owns the timed ones. Recording into the live cue of a stack republishes its Tier 4, or the DB
  and the published Tier 4 would disagree and the next Clear would snap the rig back.
- **Look layers** — matched on their source, never their targets, since a Look lands whole. A scoped
  Record (*Selection only*) takes a Look — the cue's to replace, the programmer's to write — only
  when it lands on fixtures and every one is in scope (absolute-looks plan P5): one reaching outside
  the selection, or landing on none, stays on the cue as it is, and the programmer's is left out with
  a warning naming it.
- **Scenery** — `scenery` (default true) records what the programmer's scenery overlay **holds**,
  never what the stage merely shows, whatever the `source`, `mask` or `targets` say: scenery is
  addressed by element, not through the selection (scenery-programmer plan D3, D7). Into a cue, a
  held piece the cue has **no row for** is written only where its held state differs from what the
  cue would show without it — the piece's base, then the stack's set, then every earlier STANDARD cue
  of the stack (`sceneryBeneathCue`, the cue card's "tracked" taken over the base too) — and skipped
  when it equals it (`sceneryAlreadyTracked`). A row the cue **already has** is replaced whatever it
  says, since it is the cue's own assertion; it keeps its clock unless the held piece came from
  Include with one. `MERGE` and `UPDATE_EXISTING` replace per element (the mask that narrows a
  lighting Replace says nothing about scenery); `REMOVE` deletes the rows for the held pieces; a
  MARKER records none and warns. `routes/programmerSceneryRecord.kt` holds the rules. Recording a
  **Look** (`record-look`) writes **every** held state, since a Look asserts rather than tracks — so
  a Look recorded with only the moon held is a scenery Look, the busk pad that flies it.

**`include { cueId, mask? }`** loads a cue's assignments and immediate FX into the programmer as
`INCLUDE`-owned slots and programmer-band FX, and returns the fixture keys for the sheet to
select (MagicQ's "Select Heads on Include"). Two details earn their keep: an FX child the cue is
already running is *not* re-spawned (there is no FX-vs-FX suppression, so a band duplicate would
double-apply), and spawned instances leave `cueId` null so `removeCueFx` can't sweep the
operator's programmer out from under them when the cue stops. A cue's or Look's **own scenery
rows** go into the programmer's scenery overlay with them (D8), each replacing what the overlay held
on that element, a cue row's `transitionMs` kept beside the held state so Update writes it back on
the clock it had — never the scenery the cue only tracks, and never masked. A cue that only moves
scenery is something to include: it sets the include target.

**A Look's FX are included too** (`includeFx`, default true; `routes/programmerLookInclude.kt`):
each runs as a programmer-band FX stamped with the stored FX it realises
(`FxInstance.includedLookFx`, the Look FX's row id and the instance's settings as spawned).
Each runs on its own target, since a Look FX always names one (absolute-looks plan D2). The cue
rule holds: an FX the Look is already running where it is live (a programmer or cue layer, or an
earlier Include of it) is not run again, and says so. They are local FX, so Clear and Release
take them. Before this a Look of FX alone included nothing, and its FX could be neither
seen nor changed.

**`update { targets?, mask? }`** writes back.

- **Mode A** — with an include target, only entries that *changed since Include* are written. The
  INCLUDE slot survives underneath the operator's write, so the comparison needs no extra state.
  It is also what keeps a cue row in the *exact form it was authored*: a row stored as `"red"` is
  parsed to a colour at include time, and because the operator didn't touch it, it is not written
  back — so it stays `"red"` rather than being re-serialised as `"#ff0000"`. Writing everything back
  would rewrite every untouched row in the cue on the first Update after any Include.
- **Mode B** — with nothing included, the server answers with the cues the programmer is currently
  overriding, grouped by stack, and the client confirms which to write. Keys with no cue underneath
  are bucketed separately ("record a new cue instead"). A commit writes each cue only the keys it
  was actually underneath.
- Update applies MERGE semantics. In Mode A it also **deletes the row of a value Include staged that the
  operator has since cleared** (⌫, a Release; clearing a position or a pan or tilt clears its fine
  channels too, since no surface shows them alone): the clear takes the INCLUDE slot with it, so the store
  keeps the keys the last Include landed as slots (`ProgrammerStore.includedKeys`, read only while the
  include target names that source) and `clearedSinceInclude` names each one nothing but a layer holds
  any more, under the mask. A cleared key's own row goes; a group row covering it goes too and is split
  (`planClearedRemoval`), each member still held and without a row of its own getting the group row's
  value. An Update forgets the keys it dealt with, so a row added back later is not deleted again, and
  Clear forgets them all. An Update, an Include and a Record each move the include target's `baseline`,
  which is what the source strip re-takes its count of unwritten changes against. A key set only some other way (an element under its fixture's row) is named in a
  warning. Mode B deletes nothing; removing anything else is `record REMOVE`.
- **After a Record**, Update's value baseline is what the Record saw (`ProgrammerStore.recordedValues`,
  every touched value at that moment) rather than the `INCLUDE` slots, which still describe whatever was
  included before: a masked or scoped Record otherwise left Update writing every busked value outside
  its remit into the target. A Mode A Update folds in what it wrote, so a value put back is written
  back; an Include and Clear drop it.
- **The layer half of the count.** `ProgrammerStore.layerChangesSinceInclude` counts the layers Update
  would add, remove, move or retune in an included cue (`writeLayerStackIntoCue`'s classification), and
  rides `programmer.layerState` and `programmer.state`, because a Look of scenery alone adds a layer and
  no value. A Mode A Update and a Record into a cue rebase the stack (`rebaseLayers`): each layer keyed
  to the cue row now standing for it and the stack made the baseline, so a second Update — or an Update
  after a Record — retunes those rows rather than adding the layers again. A layer the Record left
  with no row (`ALL` and a stage snapshot flatten the stack into rows) stays in the baseline keyed to
  nothing and counts as neither added nor written.
- **Scenery** goes back in Mode A only, by the record's MERGE rules — into the cue as change rows (an
  included row with its own clock, a held piece the cue already shows skipped, a row already saying
  exactly the held state left untouched), into a Look as its scenery. Mode B writes none, for the
  reason it leaves the programmer layer stack alone: scenery held over a cue the operator never included says
  nothing about which of the named cues it belongs to. The overlay keeps a **baseline** — what the
  last Include, Record or Update left the source saying — and `programmer.sceneryState` carries
  `changedSinceInclude` against it, so the source strip's Update is enabled by a moved tab as by a
  moved value.
- **A Look's included FX** go back in Mode A only, onto the FX each came from
  (`writeIncludedFxBack`) — and only one changed since Include, judged against the settings the
  instance was spawned with rather than the stored row, because a running FX reports its whole
  parameter set, defaults filled in. The FX keeps its target: what was edited is how it runs, not
  where. An FX the operator stopped stays in the
  Look (Update deletes no FX), and what was written becomes the new baseline. The active-FX DTO
  carries `includedLookId` and `editedSinceInclude`, so the source strip's Update is enabled by an
  FX edit too.

There used to be a **cueEdit guard** here — an asymmetric one, because the risks were: Record and
Update targeting a cue with an open cue-edit session answered **409 unless forced** (`beginEdit`
snapshotted the cue's assignments and Discard restored that snapshot wholesale, so anything written
underneath was silently reverted), while Include only warned, because Include only reads. Backend
sweep item D1 retired the `cueEdit.*` family, so there is no second writer to guard against and
Record / Update have no conflict case left. `force` survives on the Record and Update request
bodies as an inert accepted field only because the frontend still sends it on every submit.

## Tier 3 — FX

Tempo-synchronised `FxInstance`s that modulate attribute values each tick. Driven by the Master Clock (24 ticks/beat).

### What an FX runs on

One fixture (or one element), one group, or a **selection** — an ordered list of fixtures and groups,
one instance spread over them in that order as a group's members are (effects plan D3). The
programmer's `+ FX` and ⌘K *Apply FX* start one over the selection's rows in grid order; Record
writes it onto a cue or a Look as one FX with its `selection` target, and Include spawns it back
as one. Only an FX may name a selection: a value row, a layer's targets and a binding name a
fixture or a group, and the write boundaries refuse anything else. A palette's FX, which names no
target, still runs one instance per target of its layer. The expansion is `docs/fx-engineering.md` §"Over a
selection".

### Ordering

Deterministic. FX sort by:

1. Explicit `priority` field on the `FxInstance` (higher priority composes later so it "wins" against earlier same-channel contributions). Manual FX default to 0; cue-owned FX get a derived priority (`stackId·1M + sort·1K + 1`); the programmer band sits above both.
2. Tie-break: cue-stack position for cue-owned FX, creation timestamp for manual / ad-hoc FX.

### Per-tick reset

At the start of each beat tick, attributes touched by at least one active FX are reset to the **tier below** (not to hardcoded zero):

- If the programmer holds this attribute (and blind is off), reset to the programmer value — and skip the FX's apply for it (suppression, above).
- Else if Tier 4 contributes to this attribute, reset to the composed cue value.
- Else reset to the Tier 5 baseline.

### Blend modes

Each `FxInstance` carries a `blendMode` applied against the accumulated output at its composition point:

- `OVERRIDE` — FX replaces accumulator.
- `ADDITIVE` — `(accumulator + fx).coerceIn(0, 255)`.
- `MULTIPLY` — `(accumulator * fx) / 255`.
- `MAX` — `max(accumulator, fx)`.
- `MIN` — `min(accumulator, fx)`.

Multi-FX composition on the same attribute: start from the reset baseline, iterate FX in sorted order, fold `accumulator = blend(accumulator, fxOutput, fxMode)`.

### Fade envelopes

Each `FxInstance` carries an **amount** (0–1, stored on every FX home — effects plan D2) and an **envelope**, `intensityMultiplier` (0–1, runtime), and its cue stack has an **FX amount** (0–1, runtime, effects plan D10 — the stack of the cue's highest hold; 100 % for anything no stack holds). Their product is the FX's **weight**, applied **after** the blend, per channel: `out = before + (blended − before) × weight`, where `before` is the accumulator the blend folded over. A Replace dimmer at 50 % sits halfway between the level underneath and the FX; an Around movement's offset is scaled, so its radius shrinks about the position underneath; a setting takes the FX from half weight up. The raw output is never scaled (that was what dropped an `OVERRIDE` to 0). See `docs/fx-engineering.md` §"Amount, direction and runs", which also covers direction and runs.

**An FX fades by amount with its cue** (effects plan D1, §3.5). On a crossfading GO the outgoing cue's FX shrink to nothing and are removed, the incoming cue's grow from nothing, and an FX both cues run — the same stored Look or palette FX on the same target with the same settings — runs on untouched, id and phase kept (P4). The envelope is an immutable ramp on the clock (`fx/FxEnvelope.kt`) set by the hold registry as holds move, so it follows the holds: a cue held elsewhere moves no envelope, a loose hold and a stack stop snap. Because the envelope weighs the contribution after the blend, an `OVERRIDE` FX fading out fades into what is underneath — never through 0, the artefact that once came from scaling the raw output. The programmer's gestures fade FX the same way over the programmer fade (D13, §Fades). See `docs/cue-stacks-engineering.md` §"Holds" and §"Crossfade".

## Tier 4 — Cue attribute assignments

Deterministic per-cue state — the "this cue asserts attribute X = value" tier. Contributed by active cues, each supplying exactly one value per (fixture, attribute) — see "Looks and layers" below for how a cue's layer stack and its own `CueAttributeAssignment` rows are cooked down to that single contributor.

> Code note: this tier is composed by `CueAssignmentResolver` / consumed via
> `LayerResolver.currentCueLayerState`.

### Composition rules by `AttributeCategory`

Each attribute's composition rule is declared by its category. Categories are defined in [FixtureAttribute.kt](../src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureAttribute.kt).

| Category | Default rule | Reasoning |
|---|---|---|
| `DIMMER`, `UV`, `STROBE` | `HTP` (max) | Intensity-like. Stacking two cues should brighten the output, matching operator expectation across every surveyed pro console. |
| `COLOUR`, `AMBER`, `WHITE` | `LTP` | Blending colour between stacked cues produces mud. Last-applied-wins matches industry convention for non-intensity. |
| `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE` | `LTP` | Two positions don't meaningfully combine. |
| `SPEED`, `SETTING`, `OTHER` | `LTP` | Discrete or control parameters. |

Fixture authors can override the category default per-attribute via `@FixtureAttribute(composition = CompositionRule.LTP)` (or `HTP`). Rare but supported — for example, a fixture whose DIMMER-classed channel is really a shutter enum would override to LTP.

### Resolution algorithm

**Each active cue contributes exactly one value per (fixture, attribute).** That is an invariant, not
an incidental property: the cook step (see "Looks and layers" below) reduces a cue's whole layer
stack plus its own rows to one contributor before the resolver ever sees it. So everything below is
about composing *across* cues.

For each (fixture, attribute) pair:

1. Collect all active cues contributing an assignment to this pair. A group-level cue assignment expands to its members. A fixture-level assignment wins over a group-level assignment for the same attribute (**specificity rule**).
2. Read the attribute's composition rule (category default, or per-attribute override).
3. Apply the rule:
   - `LTP`: take the assignment from the highest-priority contributor, where priority is cue-stack position for stacked cues and activation time for standalone cues.
   - `HTP`: take the `max` of all contributors' values, each scaled by its cue's current fade weight (see Crossfade below).
4. Convert the resolved attribute value to channel values using the fixture's patch. Colour as hex, dimmer as 0–255 or 0–1, settings as enum string, pan/tilt as the native unit.

#### What ties used to do, and why the record needs correcting

Before the cook step, a cue's own rows and all its immediate FX presets' rows were concatenated and
given the **identical** `priority` and `fadeWeight`. The result was a rule nobody chose:

- **LTP resolved first-encountered-wins**, not last. `composeLtp` uses `maxWithOrNull`, whose
  contract is "the first element having the largest value", so an incumbent was replaced only on a
  strict `>`. Cue-own beat preset, and among several presets the *earlier* `sortOrder` won — the
  reverse of what `sortOrder` implies.
- **HTP ignored ordering entirely** and took `max()`. A cue asserting `dimmer=100` over a preset
  asserting `180` resolved to 180; neither overrode the other.
- **No mid-fade cross-blending.** Both rows were scaled by the same per-cue weight, so weights never
  diverged within a cue and the `winner.fadeWeight >= 1.0` early return fired: one row won outright
  and the other was discarded.
- And it was **untested** — every multi-contributor case in `CueAssignmentResolverTest` used distinct
  priorities, so the exact-tie path never ran.

`buildCueAssignmentsForPreset`'s KDoc claimed the opposite — "the sort order alone decides
(last-write-wins for OVERRIDE blend)". That described the *FX* tier, which is a genuine
last-applied-wins sequential fold, and two mechanisms in one apply path were doing opposite things.
The cook step replaced all of it with **one rule for every category: later layers win, and the cue's
own rows win over all of them**. Across cues, HTP still governs intensity — which is the pairing an
operator is most likely to be surprised by, and why `FU-MANUAL-LAYER-PRECEDENCE` exists.

**Own rows are edited in place** (show-cue-editing plan D2, session 5). The cue editor's Values grid
draws a cue's cook — `GET /cues/{id}/cooked`, the same `buildCombinedCueLayerRows` the GO path runs —
and an edit there writes one of the cue's own rows, which beats every layer by the rule above, so the
cell shows what was typed; removing the own row lets the layer underneath win again. Each cooked row
names the row it came from **before group expansion** (`sourceTargetType` / `sourceTargetKey`,
`Assignment.sourceTarget`): a group's own row has become one row per member by the cook, and the grid
draws and edits it on the group's row from this. The write is a stored PATCH of the whole
`attributeAssignments` list and composes nothing: a live cue lands it on its next first hold (P2).

### Fade weight

Each active cue has a fade weight in `[0, 1]` tracking its crossfade progress. During a cue transition, outgoing cues fade `1 → 0` and incoming cues fade `0 → 1` over the cue fade time. The weight feeds both the crossfade interpolation for `LTP` categories and the scaled `max` for `HTP` categories.

Fade weights are per **cue**, so a cue two holds keep live is one contributor at one weight (D3):
a GO onto a cue another stack already holds does not fade it in again, and a stack moving off a
cue a press still holds does not fade it out.

### Holds and the loose band

What makes a cue a Tier 4 contributor is a **hold** (cue model plan P3, `fx/CueHolds.kt`;
`cue-stacks-engineering.md` §"Holds"): a running stack whose live entry names it
(`Stack(stackId, entryId)`), or a press with no stack behind it (`Loose(seq)`) — `/cues/{id}/apply`,
`apply_cue`, a busk cue pad, a cue slot, MIDI `FireCue` and the hand. A cue comes on with its first
hold and off with its last, and every hold in between only moves its **priority**, which is its
highest hold's:

| Hold | Priority | Ordering |
| --- | --- | --- |
| a stack's entry | `stackId × 1 000 000 + position × 1 000 + 1` | later stacks above earlier, later entries above earlier |
| a loose press | `LOOSE_BAND + seq` (`2 000 000 000 +`) | above every stack; the newest press on top (D10) |
| the programmer's own FX | `PROGRAMMER_FX_PRIORITY_BASE` and up | above every cue, loose or not |

So a busk cue pad pressed over a running stack wins every LTP attribute its cue asserts (HTP
intensity still takes the highest level), the stack composes underneath it unchanged, and letting the
press go hands the attribute back with nothing re-fired. Two presses rank newest first; pressing an
older one again makes it the newest. Priority decides only LTP: for a dimmer, both cues' levels fold
by `max` whatever their bands.

### A bundled emitter's own row beats the colour's copy

A `bundleWithColour` emitter — a Hex's white / amber / UV, a `LedLightbar12PixelFixture` head's
white — is one channel driven by **two** keys: its own slider (`white`) and the matching component
of the fixture's `rgbColour` `ExtendedColour`. Both keys are ordinary Tier 4 contributors, and both
publish paths write the channel (`ColourTarget.resetToFallback` writes the bundled W/A/UV
unconditionally), so without a rule the channel took whichever key was published last — map
iteration order.

**The rule: when Tier 4 composes both, the emitter's own value wins, outright.** Whichever cue,
stack or layer each key came from, and however each composed. Outright rather than "the
higher-ranked contributor wins" because the two keys do not compose under one rule — `UV` is HTP,
colour LTP — so a blended bucket has no single rank to compare, the same reason
`CueAssignmentResolver.fadeDurationsFor` snaps one. It is the precedence
`AttributeChannelWriter.coveringKeysByChannel` already applies (the single-channel attribute over the
aggregate), so provenance, Record and the channel sheet all name the key whose value is on the
channel. The consequence to know: a later cue that wants the emitter off must assert it (`white 0`),
not only a colour carrying `w0`.

**Where it lives: `CueAssignmentResolver.compose`** (`reconcileBundledEmitters`). After a target's
buckets compose, the colour's bundled components are replaced by their emitters' composed values, so both
keys carry one value and publish order cannot matter. Compose rather than cook because it must see
a crossfading emitter's *blended* value frame by frame; compose rather than transmit because every
Tier 4 reader goes through it — the publish diff, the FX tick's reset baseline under a running
colour FX, Clear and Blind (`publishCascadeForKeys`), provenance and preview compose — and a
transmit-side skip could not tell a Tier 4 component from a programmer one in an already-merged
fallback. A row learns its part from `Assignment.bundleRole` (`COLOUR` or `EMITTER`), read off the
class catalogue where the row is built (`buildCueAssignmentsForCue`, `CueComposer`); `cook` notes
the targets that hold both, so a crossfade frame pays nothing for the rest. The colour is the
bundle's **own** — `FixtureAttributeCatalogue.Entry.colour`, the COLOUR-category member typed as a
`Colour` — never a colour macro or preset wheel that merely shares the category.

A reconciled colour has two contributors, and **provenance names both**: the colour key's entry
keeps its own winner for the RGB and lists each emitter that replaced a component under `bundled`
(`ProvenanceEntry.bundled`, `ProvenanceEntryDto.bundled` on the wire), with that emitter's cue and
layer. The Values grid counts each as a layer observation, so a colour whose white came from
another layer reads as mixed rather than crediting the RGB's Look with it.

What is residual: when the two rows carry different per-row fades, both land the same value but the
channel's ramp time follows whichever key published last.

**The programmer keeps recency** — the newer of the two writes is what is on stage — and meets the
rule at its two edges:

- **A tie goes to the emitter.** A programmer layer stamps every slot `LAYER_SEQ_BASE + index`, so a
  Look carrying both rows puts them at one seq; `SliderTarget` and `ColourTarget.extendedComponent`
  both break that tie for the emitter.
- **One gesture is one contributor.** `ProgrammerWriter.writeAttributes` stores a batch's emitters
  after its colours, so Include of a cue (or a palette apply) holding both leaves the emitter as
  the newer write — the programmer shows what the cue plays back, whatever order its rows were in.

**Record writes the on-stage value into both rows** (`reconcileBundledEmitterEntries` in
`collectProgrammerEntries`). Playback does not arbitrate by recency, so white 200 followed by a
colour carrying W 0 shows 0 on stage and would play back 200 if both rows were recorded as-was.
Every reader of the entries gets it — Record, Update and the stage snapshot — and a recorded cue
reads honestly in the editor as a result. Update keeps its "write back only what changed since
Include" rule by judging change on the slot's own value (`RecordEntry.reconciledFrom`), so a cue
whose rows disagree is not rewritten by an Update that did not touch them; and when the operator's
newer colour overrode an included emitter, the emitter row goes back too, carrying the colour's
component — otherwise the cue would play back the old W.

## Looks, palettes and layers

A **Look** is a named, reusable bundle of attribute values and FX over *named fixtures*. A
**Palette** is a named value **or a single named FX** for exactly *one attribute family*, with
no targets of its own. A **Layer** applies either, inside a cue at a declared position in that
cue's stack — `DaoCueLayers` carries two nullable FKs (`look_id` / `palette_id`) with exactly one
set, and `DaoCueLayer.source` is the only reader.

**A Look is an absolute state** (absolute-looks plan D1): pressed or layered, it lands its own rows,
FX and scenery, never narrower and never wider. So a Look layer names no targets — the cook
ignores any an older write left (`CueComposer.LayerTargets` filters palette layers only), every stored
write drops them (`withStoredTargets`, `layerTargets`), the importer drops them from any archive, and
`state/LookLayerTargetsMigration.kt` cleared each database's once (`PRAGMA user_version` 3). A Look
layer's **mask** is a layer setting like its amount or blend (D3): no press sets one, and the layer
popover, the cue editor, the Timeline's *Settings…* and MCP do; the cook applies it to the Look's
rows. To point something at the selection, use a palette.

**Palettes were templates.** Venue-visit plan D2 renamed everything a person or Claude reads: the
copy, the `/palettes` paths, the `paletteListChanged` / `palettePressed` frames, the `PALETTE_*`
codes and the `create_palette` / `apply_palette` tools. Terminology plan session 4 renamed the
storage: the tables (`palettes`, `palette_rows`, `palette_fx`, `cue_layers.palette_id`), the sync
archive's `palettes/` folder and its DTO fields, `pal:` references and the stored `pressPalette`
binding (database step 5, `formatVersion` 38). Session 5 renamed the JSON field names and the Kotlin
and TypeScript identifiers. `sync/LegacyNames.kt` reads an archive's old names.

These were one entity until session 3, distinguished by the row's targeting mode: a **bound** row
named its own fixture, a **deferred** one took its targets from the applying layer, and one entity
served both of its predecessors' jobs. What forced the split is the most useful kind of palette — a
focus position, where eight heads aimed at one spot hold eight *different* pan/tilts, so its rows are
bound and `hasDeferredRows` could not tell it from a recorded Look.

**A Look row is now always bound**, and `validateLookRows` refuses `deferred`. So is a Look
**FX** (absolute-looks plan D2): it names a fixture, a group or a selection, and
`validateLookFx` refuses `deferred`, every problem at once. An FX to point at a selection is
an FX palette. A deferred Look FX an older desk wrote is dropped by `loadLookSnapshot` (with a
warning), by the importer, and was deleted from each database once
(`state/DeferredLookEffectsMigration.kt`, `PRAGMA user_version` 2). In the cook, `FxEntry` is a
Look FX, its target non-null; a palette's FX is a bare `StoredFx`, which
`CueComposer.fxForLayer` runs on each of the layer's targets.

Sweep item B6 removed the row-side plumbing that outlived the rule, and absolute-looks session 2 the
FX side's: there is no `isDeferred` on a Look row or a Look FX at any level any more,
`LookRowEntry.target` is non-null, and `loadLookSnapshot` drops a
`deferred` row an older database wrote rather than handing the composer a row with nothing to apply
to. The targets-supply-the-row arm of `CueComposer.applyLayer` survives — it is a *palette*
layer's now.

**A palette row holds an intent, not a literal**: a colour plus a white/amber policy, a level or
beam role as a percentage of each head's own range, a position **centre-relative** — `pos:pan,tilt`,
coarse DMX `-128..127` with 0 at the centre of travel (DMX 128), the same fraction of travel on
every head (venue-visit plan D3). A position row stored before D3 holds `deg:` travel degrees, and
it still resolves as angles through each head's annotated range — there are no migrations, so the
resolver takes both units (P3), and only an edit makes an old row `pos:`. Record into a palette and
`aim_fixtures`' `saveAsPalette` write `pos:` (`PaletteResolver.centreIntentOf`, the exact inverse),
so recording no longer needs a degree annotation, and heads at the same bytes whose degree ranges
differ now agree and record one generic row. `fx/PaletteIntent.kt` owns the grammar; `fx/PaletteResolver.kt` is the **single** implementation that turns one into
channels, and it is asked by all three consumers — `CueComposer.applyLayer` at cook,
`POST /palettes/{id}/apply` for *Apply as values*, and `POST /palettes/resolve` for the editor's
resolves-to panel. Two of those exist server-side *because* it must be one implementation: an editor
that computed its own ΔE would promise what the rig does not do.

**The one exception is a bundled emitter, which is a literal — `dmx:180`.** White, amber and UV are
rows of the closed vocabulary in their own right (family COLOUR, matching
`AttributeCategory.WHITE.family()`), and the departure is deliberate: an emitter has no range to
be a proportion of and no room to be a position in — it is one LED, its slider is 0–255 on every
head in this rig, and the operator setting it is matching a colour by eye against a rig that is
already lit. What *is* per head — whether the emitter exists at all — is answered by resolution.
The prefix stays even though the payload is already a DMX byte: a bare `180` is what the *literal*
parser reads, so the two grammars would agree on some rows and silently disagree on others.

Rows rather than fields on the colour intent, because each then writes only its own channel:
`ColourTarget.composeProgrammerOver` already arbitrates the bundled emitters per component, so a
UV-only palette sits **over** an amber wash instead of replacing it. It is also the only way to say
"UV at 200" at all — no `WhitePolicy` has ever driven UV. Two rules keep the derived and the explicit
halves from fighting over one byte: an explicit `white` or `amber` row **forces the colour row to
`rgbonly`**, refused by name at the write boundary, and UV is exempt because no policy touches it.

**A colour palette refuses as a whole.** If any of its colour rows is `Unsupported` on a head, that
head takes *none* of them — `PaletteResolver.unmetColourRequirement`, folded in by cook, apply and
the resolves-to panel alike. The rows are facets of one output: an explicit amber is in the palette
because the hex alone did not get where the operator wanted, so a head without amber taking just the
hex would put a *different* colour on stage under that palette's name. Scoped to colour on purpose
— zoom and frost are independent roles and keep their per-row skip. `PaletteDto.requiredEmitters`
is the derived form of the same fact, and what the client filters its offers on; a palette naming
no emitter requires none, so the type-agnostic degradation across an RGBWA hex, a white-only head
and a colour wheel is untouched.

A palette's attribute vocabulary is **closed** (`PaletteAttribute`), which is where "a palette
cannot carry a gobo" lives: slotted roles are per-model, so they are refused by name and live in a
recorded Look, which names a head and can hold anything that head has. The three bundled emitters
are *in* that vocabulary — they are not slotted, they mean the same thing on every head that has
one, and the only per-model question about them is presence. It has always held no
positional colour list either — and now nothing does, because that grammar is gone: an FX
parameter names a colour palette rather than indexing a list, so a palette *is* the named colour
instead of being one more scope that holds several.

**Both records copy through one route shape**: `POST /projects/{id}/looks/{lookId}/copy` and
`POST /projects/{id}/palettes/{paletteId}/copy`, each `{targetProjectId, newName?}` — the same
project under a new name is *Duplicate*, another project is *Copy to…*, and a name taken in the
target is a 409. The source is `withProject`, so a library that is not the live show can be copied
out of. A copy is a new entity (fresh uuids on the record and every child row; a palette's
`lastPressedAt` starts null), but what its rows and FX *name* travels verbatim: a fixture key,
a speed-master uuid, a `pal:` colour reference. Into another project that can land a row that
reads as unhealthy there, or an FX on a master that is not there and so runs on master 1 —
the trade both routes make rather than guessing which of the target's records was meant. Neither
re-validates contents, which already crossed the write boundary once.

### Detach and Apply as values

A press puts on a layer that tracks its record; two gestures take the other road, to values the
operator owns (apply-model plan §3.5, D1).

**Detach** (`programmer.detachLayer {layerId}`, `ProgrammerLayerStack.detach`) turns a layer into
Local without the stage moving. The stack is cooked with the layer and without it, and every key the
two disagree on — the keys it wins, and those a higher layer blends over it — is written as a touched
`WEB` value at the with-layer value. Under a Locate or a flash the value goes **beneath** that owner
(`ProgrammerStore.putBeneathTop`), so it is there when the owner lets go; a key the operator's own
`WEB` value already holds, or a raw channel slot overrides, is left alone (the write never absorbs
the sideband). A Look's scenery passes into the programmer's own scenery, under what that already
holds and where no higher Look layer sets it (`ProgrammerScenery.holdUnder`). Its FX lose their
layer key and source and run on, phase kept, as Local FX at the bottom of the Local ones
(`FxEngine.detachProgrammerLayerFx`; an FX whose runs are spent is removed, since only a layer keeps one),
in the same step as the layer comes off, so no concurrent recook retracts them. The recook that
follows moves nothing the `WEB` slots hold, and nothing is republished for them: each already
showed its value. Two things do move: a stomping layer's suppression of the FX under it ends
with it, and a detached FX now applies above every layer's (FX editor plan D5, agreed with Chris
in session 3a), so one a higher layer's FX beat on a key now wins there.

**Apply as values** is the same end with no layer ever on the stack. For a palette it is
`POST /palettes/{id}/apply`, described above. For a Look it is `POST /looks/{id}/apply`
`{targets, families?, fadeMs?}` (`ProgrammerLayerStack.applyAsValues`): the Look cooked whole on top
of the stack as the layer a press would add — the request's targets and mask read and ignored
(absolute-looks plan P6) — the keys it wins written as `WEB` values at their own fades (or `fadeMs`),
its FX started as Local FX on top of their lanes (FX editor plan §3.5), its scenery handed to the
programmer's own. Not a toggle. Both Detach and Apply as values write each value under the group its
row names (`sourceGroup`), else the group a palette layer names it through.

`POST /palettes/{id}/apply` with `write: false` is the focused-Look arm (D7): the same resolve and
fan-out, answered as `values` — each head's literal in the Look row grammar, an element by its element
key, the Fan's `write: false` shape — and nothing written, nor logged as a press. An FX palette
answers none; its FX joins a Look through `absorb-fx`.

### A press is recorded, so the desk knows what you reach for

`DaoPalettes.last_pressed_at` is stamped on every press that **applies** a palette, and the
programmer's row of palette chips is "the palettes that fit the selection and have a stamp,
newest first, up to eight". One nullable column and no second table: the family is already derived
from the rows, so a list filtered by family is a history filtered by family; a delete takes the
stamp with it; and nothing needs sweeping.

**Four doors, one recorder** — `routes/palettePress.kt`'s `PalettePressLog.record`. They are four
because the gestures genuinely differ, and they share a recorder because "was that a press?" must
not get four answers:

1. `applyPaletteToProgrammer` — *Apply as values* (a chip's ⌥click or its menu), both the value and
   the FX arm; not its `write: false` arm, which only resolves.
2. The **toggle-on** arm of `POST /palettes/{id}/toggle` — a chip's tap, the press.
3. `BuskPressService.apply`, for a palette pad that ended **on**.
4. `SurfaceActions.pressPalette`, from a MIDI button.

Two rules run through all four. **A release is not a press**: both toggle doors stamp only on the
arm that puts the layer on, because what you reached for is not undone by putting it down. And **a
refusal is not a press**: a generic palette pressed with nothing selected, a palette that is not
this project's, and an *Apply as values* that reached no head all leave the stamp where it was — the last of
those being the one door that cannot say so with a status code, since it answers 200 with
`written = 0` and a skip per head.

The frame is **`palettePressed { paletteId, lastPressedAt }`**, and it is keyed rather than a
reuse of `paletteListChanged` for `cuesRecomposed`'s reason: that signal's client bridge drops the
`PaletteList`, `Cue` and `CueList` caches, and a press happens at busking rate. This one carries
the whole of what moved, so a client patches its cached list and makes no request at all. It is
fired to **every** socket including the acting one, which is what makes the server's stamp the value
every client ends up holding — two clients reading their own clocks would order a burst of presses
differently from each other and from the next refetch. Flat rather than dotted, against the "new
messages take the dotted form" rule in `docs/websocket-engineering.md`, because the failure that
rule prevents is a *mixed* family and `paletteListChanged` beside it is flat.

The column is deliberately **absent from the sync export** (`PaletteJson` names every field it
carries and this is not one): a desk's press history is not show content, and carrying it would hand
a cloned project someone else's habits.

### A press is per target

**A Look is not pressed per target: it is pressed whole** (absolute-looks plan P1). On puts one layer
of it on top, naming no targets and no mask, whatever the door sends; off takes every layer of it off,
whatever is selected; a press never adds a second layer of a Look. A Look none of whose fixtures is
patched still goes on and lands nothing, as a cue does. Every door follows it — the programmer's
toggle, `POST /cues/{id}/layers/press`, a busk pad, a cue slot, MCP's `apply_look` and the MIDI
`ApplyLook` and `PressPad` — and the doors keep their `targets` and `families` fields, which a palette
press reads (P2). In `fx/LayerPress.kt` a Look's layer is *whole* (`Ops.whole`): its arm is "any layer
of it is on", nothing is narrowed off it or widened onto it, and in a solo bank it comes and goes
whole (§"The busk layout"). Everything below in this section is a **palette**'s.

**A palette press is per target, on both arms, and it is the exact inverse of the pad's ring.** The press
means *these targets are now this record's, or they are now nobody's*: "already on" is the record
covering **every** pressed target, counted over all of its layers, which is precisely what lights a
full ring (§"Applied state is resolved by the desk"). An off press therefore takes the pressed
targets off every layer of that record — dropping one that held exactly them, narrowing one that
held more — and an on press runs the same subtraction over the record's own layers before the new
one goes on, so **at most one layer of a record ever covers a given head**. The same reading governs
what a press *releases* from a solo bank's siblings (§"The busk layout").

Whole-set equality was the first rule on both arms, and it broke the pad in the ordinary busking
case. Red on hex-1; add hex-2 to the selection and press: the press stacked a *second* Red layer
over hex-1 instead of extending the first, so the next press removed only that one and the pad fell
back to partial on a gesture that says off. The same shape appeared between siblings — Amber on
hex-1, Blue on hex-1 and hex-2 kept both, Blue merely winning on stage, so Amber's pad stayed lit
and releasing Blue popped Amber back onto hex-1.

**A palette replaces its family; a record's top layer widens** (apply-model plan D2, D6, P3). An
*on* press of a palette takes the pressed heads off every other palette on the stack of the **same
family and kind** — value palettes off value palettes, FX palettes off FX palettes — so Blue
on Red's heads leaves Red on the rest, Red pressed back does the same to Blue, and Amber Breathe runs
over Red rather than replacing it. Looks replace nothing and are replaced by nothing: two Looks on
one head stack (`FU-PRESS-REPLACE-LOOKS`). The replaced set is found from each palette's snapshot
(`LayerPressRules.familyReplacements`, `fx/LayerPressRules.kt`), and `ProgrammerLayerStack.toggle`
asks it on every press, so every door — the toggle routes, a busk pad, the MIDI `PressPalette`,
`apply_palette` — gets it without asking, and a solo bank's
siblings run beside it, never instead (P4). Both count in the answer's `released`. A replacement
takes off only the heads the palette lands on: a per-fixture palette pressed over heads its rows do
not name replaces nothing there (`LayerPressRules.paletteHeads`). A per-fixture palette pressed with
no targets names its own rows' heads (`LayerPressRules.pressTargets`, which the arm asks too), and one already on
the stack naming none is read as naming them, so both replace and are replaced like any other. Then, when the record's newest layer is the
**top of the stack** — after the press's own replacements — and carries the press's settings (mask,
blend, amount, stomp, speed masters, Cycle override), it takes the press's targets beside
what the subtraction left it, instead of a second layer going on: Red on hex-1 and then on hex-1
and hex-2 is one layer, the same layer, its FX on hex-1 keeping their phase. With another layer pressed on top in between, it is two, so a widening
never changes what a higher layer shows. The rule is `fx/LayerPress.kt`, pure over a layer list, and
what it is handed — the replaced set, the per-fixture heads, the press's targets — is
`fx/LayerPressRules.kt`, read from the palettes and never from a store, so the programmer and a
**stored cue** find them one way: the cue editor's press (`POST /cues/{id}/layers/press`, apply-model
§3.6) is `LayerPress` over the cue's layers, written to the cue and sent nowhere. Its FX-side twin is the bare FX
press's **Local replacement** (apply-model session 2, `POST /programmer/fx`): a library FX
takes the pressed heads off every other Local FX of its family — read from the attribute it
drives, not the library family a palette's family reads — never off a layer's;
`fx-engineering.md` §"Local FX follow the selection".

**A group is its fixtures**, throughout. `{group: wash}` and the `{fixture: …}` list of its members
are one selection written two ways, so every side of every coverage question is expanded first
(`ProgrammerLayerStack.coverage`): a pad put on the wash is the pad a press with one of its heads
selected turns off *for that head*. A target the press only *partly* covers is rewritten as the
members it did not name, which costs that layer the group spelling (and an FX on it the group's
Order) — the same thing the operator would have got by picking those fixtures by
hand, which is the point. A group the press does not touch keeps its own spelling, so the ordinary
case never splits, and a group that no longer resolves stands for itself rather than covering
everything or nothing.

A **Look's** layer stays outside the subtraction altogether: it names no targets, and its rows'
landing is the cook's answer, which a coverage comparison must not guess at. A per-fixture palette's
layer naming none is different, since its rows name exactly its heads: it reads as naming them, a
subtraction rewrites it to the rest, and `appliedState` lights its ring. The exception is a press
that itself names no targets — the same gesture as the layer, so it toggles its own such layer off
and takes a sibling's off outright, there being nothing to narrow it to.

**An element is a target, and a parent covers its elements.** A multi-head fixture's element is pressed as
an ordinary fixture-typed target whose key is its element key (`{type:'fixture', key:<elementKey>}`,
the shape the programmer's element row already publishes), resolved only through
`Fixtures.untypedGroupableFixture` and never parsed. Every press door lands on one: a palette's
generic rows fan over the pressed elements and resolve on each element's own emitters, a Look records
element rows from an element selection (`targetKey` the parent, `elementKey` the element) and composes them
onto those elements, an FX palette runs on the element, and a colour's white / amber / UV half reaches
the element's bundled emitter. The one coverage question an element raises has one answer, stated once in
`TargetCoverage.covers` (busk-further plan D11): **a layer on the whole bar covers a press on its
elements; a layer on elements never covers the whole bar**, however many elements it holds, because the parent
has attributes no element does. So an element press under a whole-bar layer reads *all* and comes off — the
layer narrowed to the elements the press did not name, as a split group is narrowed to its unnamed
members — while a whole-bar press over an element layer adds, and subsumes the elements so that one layer of
a record still covers a given head. `pressWouldRelease`, `ProgrammerLayerStack.toggle`,
`TargetCoverage.narrow` and `appliedState` (which reports every element of a covered parent as *all* and
a partly covered parent as *some*) read that rule; the client keeps reading the desk's resolved
applied state and carries no copy of it.

**A parent stands for the elements that carry the attribute** (venue-visit plan S1). Where a palette
row's attribute lives only on a multi-head fixture's elements — the Shehds in 24-channel mode, whose
colour is RGBW on three `Zone` elements while the parent holds pan, tilt, dimmer, strobe and zoom —
a target naming the parent, or a group whose member it is, lands that row on the elements that carry
it, each resolving on its own emitters. `fx/PaletteFanOut.kt` is the one statement of it, asked by
every consumer that resolves a palette row onto a selection: *Apply as values* (`applyPaletteToProgrammer`),
the cook (`CueComposer.applyLayer`, so a chip's tap and a busk pad alike), a Fan over heads (the
parent stays **one step** on the curve and its value lands on every carrying element — a pixel bar over
HEADS too, which used to be skipped by name) and the editor's resolves-to panel (one row per element,
`<fixture> · Element n`). Four rules keep it narrow. **A parent that has the attribute keeps it**:
only the rows its elements alone can serve fan, so a beam palette's zoom stays on the Shehds' parent
while a colour palette reaches the zones — and *having* is presence, not resolution
(`PaletteResolver.hasAttribute`), so a parent with a pan keeps a position row — and, for a stored
degree row with no annotated range, says why — rather than handing it to its elements. **Colour is decided as a family** — a colour-family row
fans when the parent has no colour at all, the `NotACandidate` question; a parent with colour but
missing an emitter the palette names stays put and is refused there as a whole, since fanning would
put a different colour on the elements under the palette's name. **Nothing carries it → the parent**,
so the skip names the head the operator selected rather than a skip per element. **An element resolves
against its parent's fitted media** (`PaletteFanOut.mediaOf`): media is loaded per patch, and
`Fixtures.fittedMediaFor` answers nothing for an element key. A layer, an apply or a fan naming a
bar *and* one of its elements writes that element once, and a fanned element's programmer entry takes the
group its parent arrived through as its `sourceGroup` (Record still writes such entries as element rows:
collapsing to a group needs every member — the parents — to hold one). It is the value-side twin of the FX engine's
own expansion (`docs/groups-engineering.md` §"Automatic FX Expansion to Elements"), which is why an
FX palette on a parent never needed it. Before it, a colour palette on a Shehds wrote 0 on
every arm — the parent asked "have you colour?" and said no — and the client folded an element selection
back onto that parent besides; the client now sends an element by its element key
(`paletteTargetsOf`, `paletteTargetsFor`), offers an element selection the palettes the element can take,
and a whole-fixture target still means every element. An **FX** palette on a mixed group is the
one gap left in this family, and it is the FX engine's rather than the fan-out's
(`FU-TMPL-CLICK-GROUP-PARTIAL`).

**A press carries the selection's attribute mask, and lands through the layer's own
`attributeMask`** (multi-screen plan D4, D5). The desk selection is targets *and* families — a
marquee over three Colour cells is `{targets, families: [COLOUR]}` — and every press door takes
the pair beside its targets (`families` on `POST /busk/pads/{id}/press`, `/palettes/{id}/apply`,
`/palettes/{id}/toggle`, `/looks/{id}/toggle` and `/looks/{id}/apply`; the MIDI `PressPad` and `PressPalette` doors
pass the desk's — `ApplyLook` reads no selection at all). Null is every attribute. Under a mask, by
kind: a **palette** is one family, so it either lands whole or not at all — family ∉ mask is refused
by name, `PALETTE_OUTSIDE_MASK`, on every door, the same dead-pad reading as
`PALETTE_NEEDS_SELECTION`; inside the mask its layer is masked to its own family exactly as before,
the intersection being the family itself. A **Look** ignores the mask (absolute-looks plan D3): it
lands whole, and a Look layer's mask is a setting made on the layer afterwards. The `skippedFamilies`
a Look press used to answer is always empty, kept on the wire while the client reads it. A **cue**
ignores the mask too: it has no targets to be masked on. A `Sel · attribute` fader ignores it too: the control names its own
attribute, as a cell edit is not masked by a marquee elsewhere. **The mask is tested on the on arm
only**: it is a fact about what a press puts on, so a press that is taking a record off comes off
under any mask — a lit Intensity pad is still released while a Colour marquee stands, and a surface
button never reads dead. Each door asks `pressWouldRelease` (`routes/pressArm.kt`) before the
press, which is `LayerPress.wouldRelease` — the decision `LayerPress.press` itself makes — over the
programmer's stack; the cue's press asks the same function over the cue's layers. The "already on"
comparison reads coverage by targets, never the mask, so a palette on the stack pressed again under
another mask comes **off** rather than widening; pressing twice still means off. One limit worth
knowing: the cook applies a layer's `attributeMask` to its **rows**, not to its FX, so a Look
layer the operator has masked to Colour still runs its Intensity FX.

**There is one mask, the selection's, and every surface reads it** (apply-model plan D3). It is set
three ways: a marquee publishes its heads and names its columns and a rows-only click publishes its
heads and clears the mask, each a `selection.set` of the whole fact, and the selection's **family
pill is its picker** — the programmer's row C while the window follows the desk, and every busk row
that carries the selection — an *All attributes* switch over the four families, whose write is
`selection.setFamilies`: the mask alone, the desk keeping its heads. A window that has unlinked masks its own copy instead (the busk tab's), or, on the
programmer, keeps the marquee's columns as its only mask. The sheets that take a mask — Record,
Record Look, Include, Make layer and Update — **open on it**, and stay editable there: a sheet's
change is that write's own and never moves the selection, so narrowing one Record never narrows the
next press. On the desk this added one write, `selection.setFamilies` (`DeskSelection.setFamilies`,
the mask replaced and the heads kept); the press doors already read `families`, and the record
routes already took a `mask`.

> **The library has no order and no groups.** Palettes and Looks list by name, and nothing but a
> create assigns a position, because there is no position to assign. `template_groups`,
> `templates.sort_order`, `looks.sort_order` and `POST /templates/reorder` all existed to give the
> library an operator-set order and to make a cluster of pads mutually exclusive; the busk page took
> both jobs when the operator took ownership of the layout, and they went with it. Order is a pad's
> place in a bank, exclusivity is a solo bank's, and neither was ever a composition concept —
> `CueComposer` read none of it then and reads none of it now.

### The busk layout

The busk page is a thing the operator **builds**, not a view the library lays out
(`docs/plans/completed/busk-layout-plan.md`, D1–D2): a page is rows, a row is columns each with a width share
in twelfths, a column stacks banks top to bottom, and a bank holds ordered **pads** — each a
reference to exactly one palette, Look or cue (`busk_pages` → `busk_columns` → `busk_banks` →
`busk_pads`, `models/buskLayout.kt`). One record may sit on several pads. Nothing is positioned by
hand, so nothing overlaps.

**A pad is an enrichment, never content and never a guard.** Deleting a palette, Look or cue
deletes its pads inside the same transaction — the record's route sweeps them by hand, since SQLite
enforces no cascade — and the palette and Look delete guards do not count pads as a use. Sync
reads a pad whose record the archive lacks as absent, with a warning, for the same reason.

That is why the **"on *n* pages" count** on `PaletteDto` and `LookDto` is a *hint* and nothing
more. It rides in the batched `PaletteUsage` / `LookUsage` the library list already reads, is
deliberately absent from their `describe()` — that string is the delete guard's message — and
produces no 409 and no `?force=true`. It counts **pages**, not pads: two pads for one record in one
bank is still one place to go and look. The library row shows it and the delete confirm mentions it,
because the pads go silently otherwise; neither stands between a record and its delete.

**A pad can be placed without opening the busk view.** `POST /busk/banks/{bankId}/pads` appends one
pad to one bank and answers the whole page — the additive exception to the whole-page write below.
D10's argument is about *editing* a layout ("a partial document cannot say *this column is now
empty*"), and an append can never empty anything. It exists because the surfaces that place a pad
from elsewhere — the cue editor's Cue tab, the palette editor, the Look sheet, the programmer's create
sheets — hold no page document to splice and re-`PUT`, and re-`PUT`ing a stale one would clobber a
concurrent edit. The bank is addressed by **id** rather than by a position, so the address survives
any reshuffle of the page that keeps the bank alive.

**A press goes through the pad, and the bank decides the siblings** (D4,
`routes/BuskPressService.kt`). `POST /busk/pads/{id}/press` reads the pad, its record and — when the
bank is **solo** — the records on its sibling pads in one transaction, then calls the same
`ProgrammerLayerStack.toggle`
the `/palettes/{id}/toggle` and `/looks/{id}/toggle` routes call, handing it the siblings as
`releaseSiblings`. Those two routes stay for the programmer's palette strip and the AI, with no
solo siblings — a palette's family replacement is `toggle`'s own, so it runs on them too. A **cue** pad takes or lets go of a **loose hold** (§"Holds and the loose band"),
exactly as a cue slot presses: a toggle, live above every stack without being any stack's playhead,
drawn solid while the press holds it and as an outline while only a stack does.

**Solo has one meaning for every kind** (D6): pressing one pad *on* turns its siblings off. A palette
sibling under a palette press is narrowed on the pressed heads by `toggle`'s own rule — the
per-target reading the ring uses, so a sibling holding exactly the pressed heads comes off, one that
overlaps is narrowed, a disjoint one is untouched. A **Look** comes and goes whole, as a cue does
(absolute-looks plan P3): a Look sibling, which cannot be narrowed, comes off whole under any press,
and a Look press takes every layer sibling off whole. A cue sibling that is live is stopped. A **cue
press** takes its layer siblings off *wholesale* through `ProgrammerLayerStack.release` — the one
additive method the layout gave the engine, one mutation and one recook — because a cue has no
targets to narrow by. An **off** press releases nothing, whatever the kind; a stacking bank (solo
off) has no siblings at all. Nothing in the cook reads the layout: a bank changes what a press
does, never what a cue composes to.

**A Look needs no selection.** It lands whole (absolute-looks plan D1), so a press with nothing
selected and one with anything selected are the same press — which is how a cue-slot tile, which has
no selection, presses a Look (D7), and why a slot takes any Look. A **generic** palette's rows take
their targets from the press, so with none it is refused (`PALETTE_NEEDS_SELECTION`), while a
per-fixture palette names its own heads and lands on them.

**A pad has a second door, and it is the same press.** A MIDI control surface can hold a
`PressPad(padUuid)` binding, which runs the whole of the above through `BuskPressService` with the
**desk selection** — its targets *and* its attribute mask — as the press's. The service exists
precisely so the two cannot diverge: the solo rules, a palette's empty-selection and mask refusals
and the cue toggle are the *pad's* behaviour, not the HTTP endpoint's. It answers an outcome
rather than responding, because a MIDI press has nowhere to put a 400. See
`docs/midi-control-surface-engineering.md` §"The busk press, from two doors".

**The hand: one held record, picked up on any window and placed on any other.** The busk page is
where most places land, so it is documented here (multi-screen plan §3.5, D12). `state/HandState.kt`
holds a palette, a Look or a cue — never two, never a cue *stack* — and `hand.state` /
`hand.pickUp` / `hand.drop` are the whole wire. It exists because a pointer drag cannot cross an OS
window boundary: the window that saw the press keeps the pointer for the whole gesture, so the
neighbour never receives a pointer event of its own.

**There is no `hand.place`.** Every target already has a mutation with its own validation —
`POST /busk/banks/{bankId}/pads` appends a pad, `assignCueSlot` fills a slot, the palette or Look press
(`/palettes/{id}/toggle`, `/looks/{id}/toggle`) adds a layer, `patchProjectCue` through `buildCueInput` adds a cue layer — so one frame that placed
would reimplement four of them behind one name. A place is **the placing window's own mutation
followed by `hand.drop`**, and Undo is that window's inverse mutation. The desk's whole share of a
place is letting go. A control surface has no mutation of its own to make, so its one place binding,
`HandPlaceInBank(bankUuid)`, runs that same append rather than a second copy of it — and lets go
only if it succeeded, so a refused place leaves the item in the hand where the operator can put it
somewhere else.

The frame carries the record's **own summary DTO**, exactly as `BuskPadDto` does, so every window
builds the ghost's face from the frame with no lookup — which is what lets that ghost be frozen and
hookless. `pickedUpOn` is the same `SelectionSource` a selection write is stamped with, read from
the socket's announced window and never from the payload (D7).

A drop made **after** a place names the record it is letting go of, because a place is two
round-trips and another window may have picked something up in the gap; a bare drop — the chip's ×,
Escape — lets go of whatever is there.

**Project-scoped, and three things end a hold.** The ids a held record carries belong to one
project's rows, so `State`'s project collector drops it — the opposite of the windows registry,
which outlives a switch. Besides a place: a **five-minute timeout** armed on every pick-up, because
an item held for the rest of the night is a chip nobody meant to leave lit; and a **reconcile** run
from the `lookListChanged` / `paletteListChanged` / `cueListChanged` listeners, so a record deleted
while held leaves every window's chip rather than naming a row that has gone. Reconcile asks whether
the record *exists*, never whether it still looks the way it did: an edit is not a delete, and the
held face is frozen for the same reason a pad's is. A second pick-up **replaces**, and is not an
error — the operator changed their mind, and there is no other gesture that means it.

**The desk holds a showing page, and a window chooses whether to be on it.**
`state/BuskPageState.kt` holds it — transient, project-scoped, `busk.pageState` / `busk.setPage` —
so a hardware *next page* button and a tab click in the busk view are one gesture rather than two
disagreeing ones, and every *following* window moves with it. That it is one fact is the backend's
whole part in this; whether to resolve against it is the client's, and since 2026-09-16 a window can
hold a page of its own (`lib/buskPageFollow.ts`, a per-tab flag beside the selection's), so that two
screens can show different pages while sharing one selection. Nothing here changed for that. The
client's `?page=` mirrors whichever page its window is showing. It is unrelated to
`busk.layoutChanged`, which names pages whose *document* changed.

### The rig

The target band the busk view draws is a **rig** the operator builds, in the page's own shape
(`docs/plans/completed/busk-further-plan.md` D1–D3): rows of tiles, each tile a group, a fixture,
or one element of a multi-head fixture (`busk_rig_rows` → `busk_rig_tiles`, `models/buskRig.kt`), one rig per
project, whole-document `PUT /busk/rig` with the page write's three refusals (`BUSK_RIG_INVALID` /
`_IDENTITY` / `_REF`), tiles renumbered dense and answered with the ids the write minted. A tile is
an **enrichment** of its group or patch, never a guard: a group or patch delete sweeps its tiles in
the same transaction and fires `busk.rigChanged`, and the project delete and the importer's replace
sweep the rig **before** the group and patch deletes the FKs would otherwise block.

**An empty rig is today's band, and the fallback is the client's.** The server stores what the
operator built and nothing else — an empty `rows` is answered as empty — and `effectiveRig` in the
busk view draws every group then every fixture from the two lists it already holds. There is no
first-open generator and no migration. The one thing the desk keeps that agrees with that fallback
is the **rig order** (`state/BuskRigOrder.kt`): rows in order, tiles in order, a group as one step
that places its members in member order, a multi-head tile as one step (`PIPS` / `WHOLE`), one step
per element (`PER_CELL`) or `cellSplit` contiguous runs (`HALVES`), an element tile as that element — and with
no rig, every group then every fixture. It is what `selection.subselect`'s *Next* / *Prev* walk and
what a fan's `NORMAL` order means — except a fan whose one target is a group, which runs in
that group's own member order (venue-visit plan S7, `docs/groups-engineering.md` §"Member order") —
and `BuskRigOrderTest`'s fixture (`src/test/resources/busk/rigOrder.fixture.json`) is the file the
client's mirror is pinned against.

**A row is laid out the way a bank is** (2026-09-21): `busk_rig_rows` carries a `flow` — the
bank's `BuskFlow`, which gained `SCROLL` for the sideways-scrolling line every row was before it
had a flow, and which is the row's default where a bank's is `WRAP` — and a `width` share in
twelfths from `BUSK_WIDTHS`, so two half-width rows sit side by side on the band as two half-width
columns do on a page. Both are validated by the rig write (`BUSK_RIG_INVALID`), travel through sync
as fields of `BuskRigRowJson` with defaults an older archive decodes to (the writer is **v13** for
this change, because `SCROLL` widens an existing field — `docs/sync-engineering.md`), and are stated explicitly
on the REST frame (that converter encodes defaults); the client still reads an **absent** flow as
`SCROLL` and an absent width as 12, so a desk that predates the two columns keeps working, and it
sends them only where they differ from the defaults, since the desk's Json refuses a key it does
not know.

**A multi-head tile decides how it shows its elements** (D3), on the tile rather than in a mode:
`cell_mode` is `PIPS` (the whole fixture, elements as pips that select individually — the default),
`WHOLE`, `PER_CELL` or `HALVES(n)`; a tile dragged in as a single element carries its `element_key`.
Element keys stay opaque: the write boundary validates one against the live fixture's `elements`
and refuses a split on a single-head fixture or beyond its element count, and no reader parses a key.

**The sub-selection is the desk's rule** (D12). `selection.subselect {mode}` rewrites the
selection's targets — `ALL` · `ODD` · `EVEN` · `FIRST_HALF` · `SECOND_HALF` · `INVERT` · `NEXT` ·
`PREV` · `MASTERS` — over the rig order, counting **elements** where any selected fixture has them and
heads where none does, so *Odd* on a group of bars is every other element across the bars; *Next*
steps the whole selection one step of the rig, a group as a group, one element at a time when only
elements are selected, wrapping. A MIDI `SelectionCells` / `SelectionNext` / `SelectionPrev` button
reaches the same `DeskSelection.subselect`, so the chip and the surface cannot disagree
(`DeskSelectionSubselectTest`'s fixture is the client's pin).

**A fan resolves on the desk** (D9, D10). `POST /programmer/fan` takes two intents, a curve
(`LINE` · `MIRROR` · `ARROW` · `MIRROR_OUT` — the last two mirrored fans meeting at the centre, *to* at
each outer end), an order (a `SpreadOrder` name, `NORMAL` being rig
order), `runs` (that many contiguous fans) and `over` (`HEADS` or `CELLS`); interpolates in the
intent's own space — Lab for a colour, centre-relative steps for a position (both ends one unit;
a `pos:` and a `deg:` end is `FAN_INVALID`), percent for a level — and resolves
each head's literal through the same `PaletteResolver` *Apply as values* uses, writing one batched
programmer write with owner `WEB`. So Record captures it, Blind previews it and Clear releases it:
a fan is a result, not a palette — *Save as Look…* is `record-look` over the selection.
Since the editor-kit plan's session 3 the request carries `write` (default true): `false` runs the
same resolve and **answers without writing** — the programmer's focused-Look-layer arm, whose
client lands `written[].value` in the layer's draft through the ordinary Look save — and
`written[].value` is each head's **literal** in the Look row grammar rather than the intent. Two
things follow: a colour-wheel head's resolution is a wheel *slot*, answered as the slot's level
under the head's own `colourWheel` — a Look row holds it, and the cook reads a level on a
slot-backed COLOUR row as a slot (`Fixture.Attribute.settingBacked`); and the curve is spread over
the heads that can take the attribute, found in a first pass, so
a head that cannot — a par in a Position fan — is skipped and consumes no position on it (the
programmer's marquee is geometric and sweeps such heads up with the rest). Over HEADS a multi-head
fixture whose attribute lives only on its elements is still **one step**, and its value lands on every
element that carries it (venue-visit plan S1, §"A press is per target"): `written[].target.key` is then
an element's key the request never named, and the fixture no longer appears in `skipped`. An element
named beside its parent is dropped, the parent covering it.

**A fan about each head's own position** (venue-visit plan S12). `mode: ABOUT` (the default is `BETWEEN`, from → to), Position
only, takes no endpoints but an amount per axis (`fan: {pan?, tilt?}`, the outer heads' offset in
centre steps, an absent axis not fanned) and moves each head from where it starts by the amount
times `2t − 1` (`FanPlan.fanWeights`), `t` the same fraction under the same curve, order, runs
and over — so under `LINE` the fan is symmetric about the middle of the order, a lone group's member
order included, and a run of one head stays put. A head starts from the request's `base` (a
`pan,tilt` literal per head: the row a `write: false` host is editing, or the anchor an earlier
answer gave), else from **Local's own value** for it, axis by axis as output arbitrates it — the
newest of the position entry, the axis's own attribute entry and its sideband channel — which a fade
in flight, a position FX or Blind leave the output short of, else from what the desk is sending
now. A `write: false` request skips Local: its rows are another context's, so a head they lack starts
from the stage. The answer's `base` says where each head started, so a client holds the gesture's anchor and a
Live drag never fans a fan; `clamped` names the heads held at the end of travel. The sum goes
through the same `PaletteResolver`, an axis not fanned resolving to its base exactly. The surface's
`SelectionFan` encoder is the same resolve over the desk selection, owner `surface`, with the desk
holding its anchor and the rig order for the gesture (`docs/midi-control-surface-engineering.md`).

**A stage fan** is a fan in metres: `fan: {x?, y?}`, never beside `pan` or `tilt`, moves each head's
**landing point** on the jog's working plane (head height) by the amount times the same weight — `x`
towards SL, `y` upstage — and aims the head at the moved point through the jog's own step
(`routes/programmerAim.kt`'s `jogHead`; `docs/fixtures-engineering.md` §"Jogging heads across the
stage"). So the beams fan across the floor by the same metres whatever each head's mount, where a
pan fan of one amount lands a front-of-house head and an overhead one in very different places. It
is HEADS only (an element has no placement: CELLS is refused), and a head that can never be aimed —
unplaced, no degree range — is skipped before the curve is spread, so it takes no place on it. A
base carries the fine bytes a jog's does (`panFine`, `tiltFine`), the answer's `near` is each moved
head's travel degrees for the next request to send back (a client keeps each head's latest), and a
head the fan takes out of its travel is skipped `out of reach` rather than clamped. The surface fan's `ACROSS` and `DEPTH` axes are this arm.

### Applied state is resolved by the desk

A busk pad's ring asks one question — *is this record on for what I have selected?* — and the desk
answers it. `ProgrammerLayerStack.appliedState` folds the layer stack into one entry per Look or
palette. A palette's lists **every target it covers**: each covered fixture, and each group marked
`all` or `some` by how many of its heads the record holds. **A Look is on or off** (absolute-looks
plan P4): with a layer of it on the stack, its entry lists every fixture, element and group of the rig as
`all`, so a ring folded over any selection reads it lit, and with none it has no entry — "a layer of
it is on", in the vocabulary a palette's ring already reads. A surface's `ApplyLook` button and a Look
pad's LED read the entry's presence (`SurfaceFeedbackPublisher`'s `LOOK_APPLIED`), lit with or without
a selection. It rides on the `programmer.layerState` frame and
the connect snapshot as `applied`, beside the layer list rather than instead of it — the Layers pane
edits layers, the pads read coverage, and those are two questions about one stack.

The client does no expansion and no matching. For one selected target a pad is a lookup; for a
multi-selection it is *every selected target says `all`* → full ring, *none of them appears* → dark,
anything else → the partial ring. Both directions of the group rule fall out of the server expanding
once: a layer on `{group: wash}` reports the wash *and* each of its heads, and a layer on one head
reports that head plus the wash as `some`.

The ring and the press are two readings of one rule, and that is load-bearing rather than tidy: a
pad whose ring says "full" must be a pad whose next press clears the selection, or "off" means
something the operator cannot see. Because the expansion is against **live** group membership, a
patch or group edit changes what the standing layers cover without changing a layer — so
`fixturesChanged` re-sends the frame (`BroadcastSocket`), and the rings follow the rig.

This is deliberately not a client-side rule reading group membership over the wire. The desk already
owns target expansion, coverage and precedence; a second copy in the browser is a copy that drifts
and that no test on the rig can reach. Enabled-ness is ignored: a disabled layer is still on the
stack and a press still takes it off, so its ring stays lit.

### A palette holds a value *or* an FX

D7 used to say a palette holds **no** FX at all — "effects live in a Look or on a cue, never
on a layer". The fx-templates plan reverses that, because it left the most useful busking gesture
homeless: "a slow amber breathe on whatever I have selected" is one named thing, of one family,
with no targets of its own — the definition of a palette — and it was reachable only as a Look
with zero rows and one deferred FX, which lands in the Looks pool rather than the family column.

Three rules keep the reversal narrow, and each removes a way for it to complicate the cook:

- **A value *or* an FX, never both** (`palette_fx` has a unique `palette_id`, and the
  write boundary refuses a mix). A colour *and* a chase is a Look, which already holds rows plus
  FX. Which one a palette holds is its identity, like its family: `validatePaletteContents`
  refuses a PUT that would flip it, so the cook's palette arm never needs both halves.
- **One FX.** Several together is, again, what a Look with FX already is.
- **Always generic** — no `target_type` / `target_key`, unlike `look_fx`, every one of which
  names its own target (absolute-looks plan D2). A palette's FX fans over whatever the layer
  names, full stop. This is load-bearing beyond tidiness: `PaletteRegistry` caches
  snapshots on the argument that nothing patch-shaped is in the cache, so a repatch cannot make an
  entry stale — and a bound, group-targeted palette FX would be exactly that.

The family is then **derived from the FX's library `family`**, through the same
`attributeFamilyForFxFamily` map a Look's derived families use (`intensity` → Intensity, `colour` →
Colour, `position` → Position, `beam` → Beam). `controls` has no tempo and `steps` (the Steps FX)
steps another family, so both answer null and are refused by name. A Beam FX palette holds one of the beam
built-ins (effects plan D6, `fx-engineering.md` §"Beam FX"); its stored FX names no
attribute, and a press lands it on the one attribute the FX declares, skipping a head without it —
but for Slot Steps, which names the setting it steps as its `attribute`. Palette Steps is the one
FX whose family is not its library family (`steps`, which names none): it is its palettes'
family, and the write boundary checks the stored `family` against that (`fx-engineering.md` §"Step
FX").

Two things about the FX are the **registry's** answer rather than the author's, and both are
checked or filled at the boundary rather than trusted. `family` is stored, and the write boundary
refuses a row whose registration disagrees with it. `timingSource` is not stored at all and is
resolved into `PaletteFxDto` on every read — an FX's timing source belongs to the FX
*type*, so a column would be a second copy that a re-registered script could contradict. It is on
the DTO because without it `cycle` cannot be read: the field is beats for a `BEAT` FX and
*seconds* for a `WALL_CLOCK` one — or for a beat FX on *Seconds* (`seconds`, venue-visit plan D7), and a client would otherwise have to fetch the whole FX library to
tell a two-beat pulse from a two-second one.

**The cook gained no new path.** `CueComposer.fxForLayer` is the single fan-out shared by
`cookFx` and `cookAll`, and it reads the FX off the layer's content: a Look's on their own
targets, a palette's one `StoredFx` on each of the layer's. Cue-layer numbering is untouched: a
palette layer takes a rank whether it contributes rows or an FX.

The two **gestures** are the value palette's two, unchanged in meaning. A tap (and the busk pad)
posts to `toggle`, which adds a programmer layer that *tracks* the palette: the FX is spawned by
the cook, carries the palette as its `LayerSource`, and Record files it as a palette layer.
*Apply as values* (⌥click) posts to `apply`, whose FX arm mints one detached programmer-band copy
per target the press names — the FX twin of the value arm's literals. That copy deliberately carries **no**
`LayerSource` and no `programmerLayerFxKey`: without the key no recook can retract or retime it,
so retuning the palette afterwards cannot move it, and without the source both Record paths file it
as an ad-hoc cue child rather than rebuilding it as a layer that tracks. `paletteApply.kt` holds the
four-point version of that reasoning.

**An edit to a pad's running FX is the instance's alone** (fixture-fx-sheets plan D20, sessions
1 and 5). The busk view's FX tab, the fixture sheet's tray and the programmer's FX table's *Edit…* all edit the
**instance** the layer spawned, through `updateFx` (`FxEngine.updateFx`: id and phase kept), and
never the palette. The layer stack keys that instance by the `StoredFx` it was spawned from
(`ProgrammerLayerFxKey`), and `updateFx` carries the key across its swap, so the edit
**survives** every recook that leaves the palette alone — a patch, a move, an unrelated add — and
goes when the pad goes: pressing it off and on spawns the palette's settings again. The client marks
such an instance ***edited*** by comparing it with the palette's FX, and offers two ways back
to agreement. **Reset to palette** is `POST /fx/{id}/reset` (W5): the palette's current FX
re-applied to the instance in place, phase kept, and the instance **re-keyed** to the palette's
current entry under the stack's FX lock. **Update palette** is the palette's `PUT {fx}`
from the instance's settings **then** W5 on that instance — the PUT alone recooks values only and
leaves applied instances, but the next recook for any reason reads the edited palette, finds the
instance's key stale and respawns it, phase restarted; the reset re-keys it first, so it keeps
running. Every *other* applied instance of that palette still holds the old settings and still
carries the old key: it reads *edited*, and is respawned at its stack's next recook unless reset by
hand (`FU-TMPL-FX-EDIT-NO-RETIME`). A **cue's** palette-layer instance is refused by W5
(`FX_NOT_FROM_PALETTE`, 409) — the next GO respawns it — and the fixture sheet's tray shows a cue's
FX read-only.

Fan-out is the same on both gestures: `CueComposer.fxForLayer` spawns over a layer's targets *as
authored*, and the apply arm matches it, so a group selection stays one group-targeted FX with
its distribution intact rather than becoming one instance per member. The apply arm does have to test
capability itself — `FxTargetFactory` never fails by design, so `"rgbColour"` resolves to a
`ColourTarget` whether or not the head has colour — which is what `fixturesSupportAttribute` is for,
shared with the group FX route so a head and a group of one cannot disagree. It also checks the
FX's output type against the target's, the `requireOutputTypeMatch` the FX add routes make a
400: a spec naming `dimmer` for a colour FX resolves to a `SliderTarget` that discards every
frame, and *Apply as values* must report that rather than spawn it.

The gestures do diverge in one place, and it is the capability check that causes it: the layer path
has none at all, so on a group whose members disagree — hexes plus a hazer — a ⌥click skips the whole
group and says so, while a tap spawns one group-targeted FX that the capable heads follow.
The strict all-members rule is the one the group FX route already applies, so relaxing it is a
behaviour decision rather than a repair; `FU-TMPL-CLICK-GROUP-PARTIAL` holds the reasoning.

`POST /looks/{id}/absorb-fx` remains the way a *running* FX joins a Look.

A Look's rows hold **literals only**. A row holding a `ref:`-shaped value is rejected at the write
boundary, so **Looks do not nest** and resolution can never recurse. The `ref:` *value grammar* itself
retired in session 4 — nothing can author one — but that rejection stays, as an inlined shape check,
because it is the guarantee rather than a consequence of one.

There is deliberately **no stored attribute type**. Which families a Look touches is derived from
its rows via `familyForAttribute`, so the library banks by family the way the per-type palette
banks used to, and a Look can grow from one family to several with no migration. One consequence
worth noting: the old `PaletteTypeMismatch` diagnosis is gone, because "this is a POSITION bank
and that is a COLOUR attribute" is no longer a coherent complaint — a Look spanning both is entirely
legitimate. A reference that finds nothing now reports the symptom (no entry for this fixture and
attribute) rather than a cause that no longer exists.

### The cook step

A cue's layer stack plus its own local rows are flattened to **exactly one contributor per
(fixture, attribute)** *before* `CueAssignmentResolver` sees them:

```
layers in sortOrder → local rows → cook → ONE contributor per (fixture,attribute) → resolver
```

> Code note: `fx/CueComposer.kt`. The invariant — never two `Assignment`s with the same
> `(targetKey, attribute)` for one cue — is asserted by `CueComposerTest`.

**Within a cue**, composition is therefore strict ordered override: later layers win, and the local
layer wins over every layer. This holds for *every* attribute, intensity included.

**Across cues**, nothing changed. The resolver still sees one contributor per cue per key, which is
what it was written for, so all the HTP/LTP, crossfade-weighting and `moveInDark` logic described
above keeps working unaltered.

**Why cook rather than per-layer priorities.** The obvious alternative is to give each layer its own
`Assignment.priority` — and there is room, since `cueDerivedPriority` leaves 999 slots between
cues. It does not work, and the reason is decisive: `composeHtp` ignores `priority` except on an
exact value tie. Per-layer priority would give ordered override for colour and position and leave
dimmer on `max()` — precisely the category-dependent split this design exists to remove. Cooking is
the only way to get one rule for every category.

**The record this corrects.** Before layers, a cue's own rows and its presets' rows were
concatenated at the *identical* `priority` and `fadeWeight`. That made within-cue LTP
**first-in-list-wins** (`composeLtp` uses `maxWithOrNull`, which keeps the first maximal element,
so cue-own beat preset and an earlier `sortOrder` beat a later one — the reverse of what `sortOrder`
implies) while HTP was plain `max()`. Both were accidents of concatenation order rather than design,
and neither was tested: every multi-contributor case in `CueAssignmentResolverTest` used distinct
priorities, so the exact-tie path never ran. `buildCueAssignmentsForPreset`'s KDoc claimed
"last-write-wins for OVERRIDE blend", which described the *FX* tier, not this one.

**Named behaviour change.** Layered intensity is later-wins, not HTP `max()`. Stacking a dim layer
over a bright one inside one cue really does dim. HTP still governs *cross-cue* intensity, so two
active cues still add up the way every surveyed console does.

### Blending

Each layer carries a `blendMode` (`OVERRIDE`, `MAX`, `MIN`, `MULTIPLY`, `ADDITIVE` — the same
vocabulary FX use) and an `amount` in `[0, 1]`, which grandMA3 calls Amount. The layer's value
is combined with the accumulation beneath it under the blend mode, then mixed back over that
accumulation by `amount`. `OVERRIDE` at `1.0` is plain replacement and is the default. An
`amount` of 0 makes the layer contribute nothing at all, rather than contributing zero.

With nothing beneath it, a layer mixes from the blend mode's **identity**: zero for
`OVERRIDE` / `MAX` / `ADDITIVE`, full scale for `MIN` / `MULTIPLY`. That is what makes a lone dimmer
layer at amount 0.5 read as half intensity while a lone `MULTIPLY` layer at amount 1.0 reads as its
own value. Positions have no meaningful identity — halving a pan/tilt aims at a corner rather than
halfway — so a lone position layer stands at its own value whatever the amount, and interpolates
only once there is a real value beneath it. Discrete settings snap at the halfway point, the same
rule `composeLtp` uses for a Setting mid-crossfade.

`attributeMask` gives per-attribute *inclusion*: "this cue's colour comes from Warm, everything else
local" is one `COLOUR`-masked layer, not a separate feature. Per-attribute *blend overrides* are out
of scope.

### FX, and the constraint that cannot be layered away

FX do not cook — they spawn `FxInstance`s. Spawning them in layer order is sufficient:
`FxEngine.sortedFxComparator` is `compareBy(priority, composeOrder, id)` with `composeOrder`
the monotonic creation counter, and per-tick composition is a genuine sequential fold through `FxTarget.applyValue`. So
same-priority FX already resolve last-created-wins, and layer order becomes FX order for
free — no priority arithmetic, and the uniform per-cue priority stays.

One limit has to be stated rather than designed around: **FX are Tier 3 and values are
Tier 4**, so an FX sits above a static value regardless of layer order. "layer 2 sets colour
statically, layer 1 runs a colour FX" resolves to the FX winning even though layer 2 is
later. Layer order governs values-vs-values and FX-vs-FX, not the value/FX boundary.
The escape hatch is per-layer `stomp` — see [Stomp](#stomp) for the two kinds and how they differ.

### Timed layers

A layer with `delayMs` / `intervalMs` fires on a timer rather than at cue apply. Firing
**re-cooks the whole cue** with that layer included, publishing through
`CueAssignmentLayer.replaceAssignments`. It does not append the layer's rows, and the distinction is
load-bearing: appending would place two contributors on one (fixture, attribute) key inside one cue,
which is exactly the ambiguity cooking removes — and the tie between them would fall to `HashMap`
iteration order. `replaceAssignments` rather than `setAssignments` because the latter resets
an in-flight crossfade weight to fully-in.

A recurring layer is therefore idempotent on Tier 4: only its FX re-trigger.

That idempotence is what makes the fire path cheap. A recurring layer re-cooks at up to 10/s, so
`CueTriggerManager.TimedFireCook` memoises the cook **keyed on the fired-layer set** — a repeat fire
of an already-fired layer asks for a result already in hand, and the distinct sets one activation
can reach are bounded by its timed-layer count. It is deliberately not a cached "static half" with
the fired layers overlaid on top: `CueComposer.cook` ranks *all* contributing layers by `sortOrder`
together, so a timed layer below a static one has to lose to it, and `CookWinner.index` and the
stomp suppression are derived from that same shared rank. Overlaying would invert all three.

The memo's one hazard is staleness, and it is closed by a stamp rather than a listener: the patch
register's `Fixtures.structureVersion`, `LookRegistry.version`, and `PaletteRegistry.versionFor`
scoped to the palettes the cue actually names. A Look edit republishes the cue *and* bumps that
version, so the next fire re-cooks instead of replaying a pre-edit result over the operator's save.
That is also why `LookRegistry` bumps its counter on *both* sides of an eviction: between a
single pre-eviction bump and the eviction itself the registry reports the new version while still
serving pre-edit content, and a stamp taken there would match forever.

### Editing a Look

Because resolved values are cached, editing a Look re-resolves and republishes its live consumers
(`routes/lookRepublish.kt`). That is the feature's whole point: one edit moves every cue layering
it, with no cue re-fired. The order is fixed — invalidate the registry, re-resolve the programmer's
reference slots *without* publishing, replace the affected cues' rows in one pass, then publish the
programmer keys. Publishing Tier 4 first would transmit stale programmer values over it for
a frame.

Finding the affected cues is now an **indexed FK query** on `cue_layers.look_id`, which is the
structural win of the merge: the named-palette era could only scan opaque `value` text for an exact string
match.

A reference whose Look stops covering it keeps its last resolved value rather than vanishing; a
disappearing programmer entry mid-show is worse than a stale one the sheet marks broken.

### What replaced the positional palette

There used to be an ordered-colour-list palette here, referenced positionally as `P1` / `P2` / `P*`
and scoped global → stack → cue by a `PaletteCascade`. It was an earlier, unrelated sense of
"palette" — the named palette banks that became Looks were another: it parameterised *FX*
rather than describing a look, and Looks, cues and stacks each
carried a column for it.

**It is gone**, and what replaced it inverts the arrangement. An FX's colour parameter names a
**colour palette** by uuid — `pal:{uuid}`, `fx/PaletteColourSource.kt` — so the colour has an
identity and a name instead of a slot number, and retuning the palette moves every running FX
that follows it. There is no cascade, because there is nothing left to scope: a reference has one
answer wherever it is read.

Two rules that fall out of that, both enforced rather than conventional:

- **A reference is legal only in an FX parameter.** A value — a cue row, a Look row, a programmer
  entry — is a literal, and a *value's* dependency mechanism is a layer.
  `parseAssignmentValue` returns null for a `pal:`-shaped value and `validateLookRows` rejects one,
  beside its `ref:` rejection.
- **Only a generic colour palette can be named.** An FX's output is one colour applied to every
  head it targets, so `PaletteResolver.resolveColourGeneric` resolves the intent without a fixture —
  as though the head were RGBW, which makes it identical to the same palette applied as a layer on
  any RGBW/RGBWA head. A per-fixture palette holds no single colour and is refused, and so is an
  **FX** palette, which has no rows at all. The generic test is **per row** — no row naming a
  head — and not "exactly one row": a colour palette may hold a hex *and* explicit emitters, and
  both halves fold the whole colour-family row set into one colour (`resolveColourGeneric` over the
  set on the desk, `paletteRowsSwatch` in the client), so it means something exact rather than "one
  of these". An explicit emitter row overwrites what the policy derived, which cannot collide while
  the write boundary refuses that pair but is stated rather than left to argument order. A palette
  of emitters alone resolves against **black**, which is the honest reading of what an FX output
  can express: one colour per frame, and no way to say "leave RGB alone" — the layer path keeps that
  distinction, a reference cannot. The write boundary also refuses a palette's FX naming *its
  own* uuid, the one shape that would recurse in `createFxWithPalettes`.

  The other direction is allowed and useful: an **FX palette's** colour parameter may name a
  *value* colour palette, which is how "Amber Breathe" follows "Amber Key". `palette_fx` is
  therefore the third table `paletteFxReferenceCount` scans, or that value palette would stay
  deletable out from under a running FX — the exact failure the delete guard exists to prevent.

  **The cost lands on heads with no white emitter, and it is sharper than "one emitter short".**
  Under `extract` the neutral is taken out of RGB at resolve time, and `applyExtendedChannel` then
  drops the white byte on a head that has no white attribute — so that head gets the reduced RGB with
  nothing compensating, reading dimmer *and* more saturated than the hex asked for (`#FF9D4A` lands
  as `#B55300`). That is worse than the RGB-only reading, not equal to it. It is the accepted trade
  for matching the layer exactly on the common head; resolving `RGB_ONLY` there instead is a one-line
  change that inverts which class of head is exact.

## Tier 5 — Baseline / defaults

Per-fixture baseline values: typically 0 (blackout) for intensity-like channels, 127 (centred) for pan/tilt where the fixture profile specifies. The "rest state" seen when no other tier contributes.

## Scenery — beside the tiers

Cues, stacks, Looks and the programmer can move **scenery** — the scene document's elements: tabs
drawn (`open`), flown pieces trimmed (`trimM`), pieces shown or hidden (`visible`) — and none of it
goes through the five tiers above (stage-view plan session 8; the design record's §"Why scenery should
not be a virtual fixture"):

- **It is never a channel** (D12). Everything below the resolver is a DMX write; scenery is resolved
  by `show/SceneryResolver.kt` and streamed as `scenery.state`, which only the Stage view reads. A
  real DMX tab track is a fixture, patched as one.
- **It tracks** where cues do not. A cue is a complete lighting state; a scene is where the last
  change left it. A stack's scenery is every change from the top of its list down to the live cue,
  over its *set*.
- **Live Looks win**, programmer and busk layers above the Looks a live cue layers, and both above
  every cue and set — the cook's "local rows last" turned round, because a Look pressed for "the
  sofa is only there in this Look" has to be there whatever the cues said.
- **The programmer's hands win over everything** (scenery-programmer plan D1, D2). The programmer
  holds scenery of its own — a sparse, runtime-only overlay, element → the states held
  (`state/ProgrammerScenery.kt`) — and it is the resolver's top rank, above its own Looks, as its
  local values sit above its layers. It is addressed **by element, never through the desk selection**
  (D3): masks, subselect, the Fan, MIDI strips and the busk rig learn no new target kind. Clear drops
  it on the Clear fade; a project switch drops it. Blind takes both programmer ranks off stage with
  its values and *stages* them instead — `scenery.state`'s `staged` — and leaving Blind lands them.
- **Record captures what the programmer holds.** Stage-view D13 said Record never captures
  scenery, because there was nothing in the programmer to capture. The scenery-programmer plan's D7
  **narrows** D13 rather than reversing it (its session 3): Record captures what the programmer
  **holds** — never what the stage merely shows — writing into a cue a row for a held piece only
  where it differs from what that cue would show anyway (a row the cue already has is replaced), and
  into a Look every held state. The attribute mask does not govern it; the Record sheets' *Record
  scenery too* row and the requests' `scenery` flag do. Include loads a cue's or Look's own rows back
  into the programmer, with a cue row's clock, and Update writes them back (D8). §"Record / Include /
  Update" has the rules.
- **A palette never carries it** (D11, reaffirmed as the scenery-programmer plan's D9): a palette
  names no targets of its own, so it has nothing to say about a particular sofa. A Look that holds
  only scenery is the busk vehicle.
- A Look that lights nothing and carries scenery is still pressable with no selection, as a layer
  that names no targets: it asserts no value, and its pad's second press takes it off.

The resolution order, the clocks (each piece's `travelS` for every move not on a cue's own clock),
Blind's staging and the routes are in `docs/cue-stacks-engineering.md` §"Scenery".

## Crossfade behaviour

When a cue transitions (outgoing → incoming), each attribute's Tier 4 contribution crossfades according to a per-category rule:

- **Sliders** (`DIMMER`, `UV`, `STROBE`, `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE`, `AMBER`, `WHITE`, `SPEED`): linear interpolation between outgoing and incoming values, weighted by fade progress.
- **Colour** (`COLOUR`): linear interpolation in RGB space. HSV / LAB modes reserved for future.
- **Settings** (`SETTING`, `OTHER`): snap at 50% fade progress. Discrete enums don't interpolate.
- **Position** (pan / tilt pair) with `moveInDark = true` on the incoming cue: if the outgoing cue ends with intensity 0, pre-apply the new position during the outgoing fade-out rather than waiting for the incoming fade-in. Otherwise behave as a slider.

For `HTP` categories across multiple contributors, each contributor's value is scaled by its own fade weight before being folded with `max`.

### Per-row fade time

Two different things are called a fade here, and they operate at different scopes:

- The **cue-level** fade time drives the crossfade above: a weight in `[0, 1]` per cue, blending
  whole cues against each other. Everything in this section so far is that one.
- A **per-row** fade — `fadeDurationMs` on a Look row, on a palette, or on a cue's own attribute
  assignment — is a *transmit-time* instruction on one `(fixture, attribute)`: how long that value
  should take to arrive, driving the same per-channel `DmxController` ramp the programmer's
  [Fades](#fades) use.

The per-row fade survives the cook (`CueComposer`), which is what makes a **tracked** source behave
like an **applied** one: ⌥clicking a palette chip writes literals into the programmer with the
palette's fade, and tapping the same chip adds a layer — which used to snap, because the field
stopped at `SourceRow`. It belongs to the row that **won** the key, alongside `CookWinner`: one
winning row supplies the value, the attribution and the fade. A palette's fade sits on the palette
rather than the row, so every row of one palette moves together.

**Arrival, not every publish.** Only a publish that puts a source *on stage* may ramp: a cue GO
(a cue's first hold on the snap path, `CueHolds.acquire` — a GO or a loose press), a timed layer firing
(`CueTriggerManager`), and `add` / Include on the programmer's layer stack. Everything else snaps,
each for its own reason:

| Publish | Ramps? | Why not |
| --- | --- | --- |
| Cue GO / immediate apply | yes | the arrival the fade times |
| Timed layer firing | yes | the layer's rows reach the stage for the first time |
| Programmer `add` / Include | yes | a chip's tap and the busking pads — the case the item exists for |
| Cue GO **with** a cue-level crossfade | no | the crossfade *is* the transition; the two must not compound |
| Crossfade weight tick | no | ~62 fps — a ramp restarted every frame never arrives |
| Look-content edit tour | no | one drag of a colour picker would set a 2 s crossfade running |
| Record / Update rewrite of a live cue | no | an edit, not an entrance |
| Programmer `patch` / `move` | no | `amount` folds into the value, so the rig would lag the slider |
| Any removal or release | no | the key releases to what sits underneath, which the departing row does not own |

**No single source, no ramp.** The per-key fade map comes from `CueAssignmentResolver.fadeDurationsFor`,
which reads the cook rather than the winner map alone. The winner map is LTP-shaped — highest
`(priority, fadeWeight)`, exactly what `composeLtp` follows — but `composeHtp` ignores priority and
takes a weight-scaled `max` or sum, so with **more than one** contributor on an HTP key the winner is
an attribution approximation. That is fine for provenance and not fine for timing (cue A's 3 s fade
would time a value cue B supplied), so a genuinely blended HTP bucket snaps. A *single-contributor*
HTP bucket does not, and must not: `DIMMER` is HTP, so treating every HTP key as sourceless would
mean a Look's fade-up never faded.

Settings (discrete wheels) always snap, as they do for every other fade. On the programmer side one
publish carries the whole gesture — `ProgrammerWriter.republishKeys` takes a per-key fade map, so a
Look whose colour row fades over 2 s beside a snapping dimmer row still lands in one
`ControllerTransaction`. An explicit `fadeMs` from the caller (Include's, say) overrides the source's
stored default, exactly as `POST /palettes/{id}/apply` does with `request.fadeMs`.

## Stomp

There are **two** stomps, at two scopes, and they are not variants of one mechanism. The cue-level
one *removes* instances belonging to other cues; the layer-level one *suppresses* instances
belonging to lower layers of the same stack. Mixing them up is the mistake to avoid: within-cue
stomp cannot remove, for the reason spelled out below.

### Cue-level stomp — cross-cue, removal

A cue carries a `stomp: Boolean` (default `false`). When a stomping cue applies, the FX engine removes ad-hoc FX tagged with *other cue IDs* that target attributes covered by this cue's Tier 4 assignments. This matches grandMA3's `Stomp` — a new cue cleanly takes over from in-flight phasers without chasing them.

Scope: stomp only removes ad-hoc FX owned by other cues. Manual (un-cued) FX are not stomped. FX owned by this cue itself are not stomped — they co-exist with its Tier 4.

The overlap set is the union of the cue's local rows (expanded through their groups) and
`CookResult.assertedKeys` — everything the cue's **layers** asserted, group aliases included. The
layer half was missing until the within-cue work landed, and its absence meant a cue whose colour
came entirely from a layer stomped nothing on colour: `buildStompOverlapFromAssignments` reads the
cue's *local* rows alone, which was the whole of a cue's surface before the layer model and is not
any more. `buildStompOverlap` is the union and is what callers should use.

### Layer-level stomp — within-cue, suppression

A layer carries its own `stomp: Boolean`. When set, it switches off the FX of every layer
**below** it in the same stack, on every attribute it asserts. It is what makes the Tier 3/4 limit
above recoverable: "layer 2 sets colour statically, layer 1 runs a colour FX" resolves to the
FX winning, and `stomp` on layer 2 is how the operator says otherwise.

Four boundaries:

- **Strictly below.** A stomping layer never suppresses its own FX, nor anything above it.
  Within one layer the Tier 3/4 order still holds — a Look with both a colour row and a colour
  FX runs the FX.
- **Layers only.** The cue's local rows and its ad-hoc FX belong to no layer, so nothing in the
  stack is above them to switch them off.
- **Coarse.** Every attribute the stomper asserts, not only the ones a lower layer's FX is
  actually fighting over. Finer granularity is `FU-LOOK-STOMP-GRANULAR`, deliberately deferred until
  the coarse version has been used on a rig.
- **Suppression, not removal**, and this one is not a preference. Disabling the stomping layer, or
  pulling its `amount` to zero, only triggers a *recook* — so a removed `FxInstance` would be
  unrecoverable, and the operator could never undo a stomp. Suppression keeps the instance running,
  so clearing the stomp brings it back mid-phase with nothing restarted.

The mechanism: `CueComposer.cook` returns `CookResult.stompSuppression`, a
`layerId → targetKey → attributes` map, because only the cook knows which attributes each layer
asserted — the rows keep the winner per key, and a layer that asserted and then lost still asserted.
It is published in the same locked mutation as the rows (`CueAssignmentLayer.setAssignments` /
`replaceAssignments` take it as a parameter for exactly that reason), and read per tick by
`FxSuppression.isSuppressed` (the engine's tick, provenance and `programmer.keyStack` all ask it) against `FxInstance.cueLayerId` / `programmerLayerId`. The reset pass has
already painted the cooked value on the attribute, so a skipped apply *shows* that value rather than
freezing the FX's last frame.

Three details worth knowing:

- The check runs **before** the programmer-band exemption. Programmer-layer FX live in that
  band by construction, so exempting the band would make programmer stomp a no-op.
- The **programmer stack honours it too**, published from `ProgrammerLayerStack.materialise` rather
  than from `syncFx` beside the instances: `syncFx` deliberately does not rebuild on a
  mask, amount or order change, and all three move the suppression set.
- **Provenance is stomp-aware**, through the same `isLayerStomped` helper the tick loops use.
  `highestPriorityFxByKey` skips a stomped FX *for the stomped key only*, so a
  lower-priority FX on that key can still be reported — and `ProvenanceService.compute` /
  `underlyingSources` therefore name the cue rather than an FX nobody can see. Keeping one
  helper is the point: "what is painting" and "what provenance reports" have no business
  disagreeing.

## Cue edit sessions — retired

Cues were once editable in place, through a session held over the socket (`cueEdit.beginEdit` /
`setProperty` / `discardChanges` / `setMode` / `endEdit`) with a snapshot-based Discard and a
Live/Blind mode of its own. That was the second of two cue-authoring paths, and it is gone: the
frontend dropped its arm in desk-simplification session 2b, leaving no client able to open a
session, and backend sweep item D1 removed the server half.

**A cue is now read-only except through the programmer.** Authoring is Record / Include / Update
(above) for every surface — the web client, a MIDI fader, and the REST API alike. What this
removed, besides ~1,000 lines: a whole second writer to Tier 4, the 409 guards that existed to
keep the two writers apart, and the surface-feedback path that made a bound fader show the cue
being edited rather than the stage.

One naming consequence worth keeping: **Blind** now unambiguously means the programmer's Blind
gate. It used to collide with the cue-edit session's own Live/Blind mode, which is why that mode
was relabelled "Preview edit" wherever both were visible; there is no longer anything to
disambiguate it from.

## Divergences from industry consoles

For readers familiar with EOS, grandMA, Hog, MagicQ, or Avolites:

- **One cue-authoring path**, the programmer's Record/Include/Update loop — which is also what most consoles have. There were two until backend sweep item D1; see [Cue edit sessions — retired](#cue-edit-sessions--retired).
- **No HTP/LTP toggle on cues**: the composition rule is an attribute-category intrinsic, not a per-cue choice. We do not need the EOS / Hog "this cuelist is HTP for intensity" switch because the category already declares it.
- **Programmer wins HTP categories too**: MagicQ's `Programmer overrides HTP chans` setting collapsed to an always-on rule — busking-first system, predictability beats max-merge.
- **Non-tracking**: cues are complete states; there is no tracking apparatus (block/unblock/trace/cue-only).

## Worked examples

### Example 1 — parked channel under an FX

Setup: dimmer on channel 12 is parked at 128. A `Pulse` FX targets channel 12 with `blendMode: OVERRIDE` oscillating `0..255`.

Per frame:
- Tier 5 baseline: 0.
- Tier 4: empty.
- Tier 3: FX computes, say, 200.
- Tier 2: empty.
- Tier 1: parked → 128.
- Output: 128.

The FX runs and consumes cycles, but parking wins. De-parking channel 12 immediately restores FX output — and leaves 128 behind as an `unpark` sideband slot in the programmer that the FX resets to, so the channel doesn't drop through to the Tier 5 baseline of 0 on the way.

### Example 2 — programmer value over a running FX

Setup: operator drags dimmer on channel 7 to 180 (the `updateChannel` shim lifts it to a `web` programmer entry on the fixture's `dimmer` attribute). A `SineWave` FX targets the same attribute with `blendMode: ADDITIVE`.

Per frame:
- Tier 5: 0.
- Tier 4: empty.
- Tier 3: the FX is **suppressed** on this attribute — the programmer holds it.
- Tier 2: 180 (sticky, `touched`).
- Tier 1: not parked.
- Output: 180.

The operator's value wins outright — no wiggle on top. Clearing the entry (`programmer.clearEntry`, optionally with a fade) releases the attribute and the FX resumes painting on the next tick. Had the FX been in the programmer priority band (a busking-pad FX), it would have composed `180 + 30 = 210` on top instead.

### Example 3 — two cues contributing HTP dimmer

Setup: Cue A active, `dimmer = 100` on fixture F. Cue B active on top of A, `dimmer = 180` on F. Both fully faded in (weight = 1.0). The programmer holds nothing on F.

- Category `DIMMER` → `HTP`.
- Contributors: A = 100 × 1.0 = 100; B = 180 × 1.0 = 180.
- Composition: `max(100, 180)` = 180.
- Tier 4 output: 180.

While cue A is fading out (weight = 0.5), A contributes 50; `max(50, 180)` = 180. Cue B dominates smoothly without jumping. Had the operator busked F's dimmer to 40, the programmer entry would output 40 regardless — programmer beats HTP.

### Example 4 — two cues contributing LTP colour

Setup: Cue A active, `colour = #FF0000` (red) on fixture F. Cue B most recently activated, `colour = #0000FF` (blue) on F. Both fully faded in.

- Category `COLOUR` → `LTP`.
- Resolver picks B (most recent activation).
- Tier 4 output: blue.

While cue B is fading in (weight = 0.6), the resolver linearly interpolates in RGB space between A and B: `(1 - 0.6) · (255, 0, 0) + 0.6 · (0, 0, 255)` = `(102, 0, 153)` — fades through purple. Once B is fully in, output is pure blue.

### Example 5 — busk over a cue, then clear with a fade

Setup: cue 42 is on stage with `dimmer = 200` on fixture F. The operator busks F's dimmer to 60.

1. The programmer holds `F.dimmer = 60` (`web`, touched). Output: 60 — the programmer beats the cue.
2. The cue keeps recomposing underneath at 200; nothing visible changes while the entry holds.
3. `programmer.clearEntry { fadeMs: 1000 }` — the attribute ramps 60 → 200 over one second via the `DmxController` ramp (no FX covers it), landing exactly on the cue value.
4. Had a running FX covered `F.dimmer`, the publish would skip it and the next tick would snap the attribute back to FX-over-cue output — fades never fight the 50 Hz tick.

### Example 6 — blind busking

Setup: cue 42 on stage (`dimmer = 200`). Programmer blind is engaged.

1. `programmer.setBlind { blind: true }` — stage unchanged (nothing staged yet).
2. Operator busks `F.dimmer = 60`. The entry is stored (`touched`) but the output stays 200 — blind excludes the programmer from the merge.
3. `programmer.setBlind { blind: false, fadeMs: 500 }` — the staged 60 lands with a half-second fade.

With a busked Pulse as well (D7): the Pulse is in the programmer band, so step 1 holds it — the rig shows cue 42's 200 on its key while it keeps its phase — and step 3 lands it mid-phase, in step with any other Pulse on its master, at full at once: it sits over the staged 60, which a growing FX would snap rather than fade (§Fades). A Pulse over a key the programmer holds nothing on grows from nothing over the half second instead.
