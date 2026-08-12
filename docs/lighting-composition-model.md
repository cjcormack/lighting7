# Lighting Composition Model

This document specifies how lighting7 composes the DMX channel output sent each frame. It is the source of truth for priority rules, blending, programmer semantics, cue crossfades, and `cueEdit` session behaviour.

Related:
- Strategic plan for adopting this model: [cue-authoring-unification-plan.md](plans/completed/cue-authoring-unification-plan.md).
- The programmer redesign that introduced Layer 2 and renumbered the stack: [programmer-redesign-proposal.md](plans/programmer-redesign-proposal.md).
- Effect engine details: [fx-engineering.md](fx-engineering.md).
- DMX transport and parking: [dmx-engineering.md](dmx-engineering.md).
- Prior-art survey that drove these decisions: [research/composition-model-prior-art.md](research/composition-model-prior-art.md).

## Overview

Per frame, the DMX output for each channel is resolved by walking an ordered layer stack. The top-most non-empty contribution wins:

```
Layer 1  Parking                     absolute override per channel
Layer 2  Programmer                  sticky manual values + programmer-owned FX; blind gate
Layer 3  Effects                     tempo-synced FX, priority-ordered, blend modes
Layer 4  Cue Property Assignments    deterministic per-cue state, per-category HTP/LTP
Layer 5  Baseline / defaults         usually 0
```

Intuition: parking (Layer 1) sits on top for safety. The programmer (Layer 2) is the operator's hands — whatever was touched manually wins, console-style. Effects (Layer 3) modulate over the playback state, except where the programmer holds a property. Playbacks (Layer 4) assert state. Defaults (Layer 5) fall through.

> **Historical note.** Before the programmer redesign the manual layer ("Direct Live
> Writes") sat *below* playbacks at the old Layer 4, and Effects/Assignments were Layers
> 2/3. Code symbols that predate the renumber (`Layer3Resolver`, `Layer3Resolver.Key`)
> still name the cue-assignment layer by its old number; their KDoc says so.

## Layer 1 — Parking

The `ParkManager` holds per-channel overrides. If a channel is parked, the transmitted value is the parked value — no other layer contributes.

Implementation note: parking is applied at transmit time in `ArtNetController` (after Layer 2–4 composition has already written to the controller's `currentValues`). This is an optimisation — conceptually, parking is Layer 1 and the pre-composition pipeline can short-circuit for parked channels.

Rationale: parking protects specific channels during maintenance, rigging checks, or troubleshooting. It must be unconditional and must not be overwritten by cue-apply, effects, or manual writes.

**Releasing park does not move the output.** Whatever sits under a parked channel is unrelated to what the rig is emitting — usually 0 — so `ParkManager.unpark` / `unparkAll` first hand the parked value *down* into the programmer's channel sideband (owner `unpark`, `touched = false`) and the controller buffer, then drop the override. The hand-off happens before the entry is removed, so no transmit frame lands in a window where park is gone but the stale value underneath is still in place. Net effect: unparking settles exactly where a manual channel write of the same value would, and `park → unpark → park` is a no-op on the wire. Rigs park hard-powered fixtures hung off a dimmer, where an unpark-to-0 snap is a safety failure rather than a cosmetic one.

## Layer 2 — Programmer

The console-style programmer: a sparse per-(fixture, property) overlay of sticky manual values held in `ProgrammerStore`. It is simultaneously the live override for busking and the staging buffer that [Record](#record--include--update) serialises. **An active programmer entry wins over cue values and suppresses effects on that property** — for HTP categories too (predictability beats max-merge in a busking-first system).

Writers: web busking (`programmer.*` ops and the `updateChannel` compatibility shim), MIDI surface faders, surface flash buttons, FX preset toggles / editor previews, Locate, and the unpark hand-down.

### Ownership

Each (fixture, property) holds a small **recency-ordered stack** of per-owner slots rather than a single flat value:

- **Put**: installs or refreshes the owner's slot and moves it to the top. The most recent write wins on the wire regardless of which subsystem made it — busking over a located fixture updates the output, matching console intuition (the thing you just touched is the thing that changed).
- **Clear**: removes only the caller's own slot. If other owners still hold the property, it falls back to the **most recent surviving owner's value** (then the cue layer, then baseline). Releasing a Locate no longer wipes a busked dimmer level; toggling a preset off no longer destroys a locate or another preset's write on a shared property; releasing a flash restores the fader level underneath.
- **Read** (`get`, on the 50 Hz effect-reset hot path) returns the top of the stack — O(1) and allocation-free.

Owners: `web` (busking UI + `updateChannel` shim), `surface` (MIDI faders), `flash` (flash press/release — separate from `surface` so releasing a flash restores the fader or busked level underneath), `preset:{id}` (one per preset, previews use a synthetic negative id), `locate`, `unpark` (park-release hand-down), and `include` (cue contents loaded by [Include](#record--include--update); its slot survives underneath a later operator write, which is how Update tells an edit from an untouched include).

One deliberate exception: **all Locate targets share the single `locate` owner**, so locate-vs-locate overlap (releasing a group locate while a member is individually located) is *not* resolved by the store's fallback — same-owner writes overwrite each other. `LocateManager` keeps its own re-assert loop for that case, which also re-resolves locate values and drops stale targets on the way.

### Touched flag

Every slot carries a sticky `touched` flag — never value-diffed. `true` marks an operator edit and is what Record and the Update checklist read. `false` marks a mechanical hand-down: the `unpark` owner's slots, which must be releasable like any manual write but must never leak into a recorded cue.

### Channel sideband

Raw channels with no property-level lift live in a parallel per-channel map with the same owner-slot semantics:

- channels with **no backing property** (`updateChannel` on an unmapped channel),
- **pan/tilt axes** written as raw channels (lifting one axis to a `position` entry would freeze the other axis too),
- **every unpark hand-down** (inherently channel-shaped — lifting one channel would freeze its property's sibling channels).

Across granularities, **recency arbitrates**: a property entry and a sideband slot covering the same channel compare write sequence numbers and the newer wins — an unpark hand-down beats an older locate entry on the channel it covers. A deliberate operator property write (`web`/`surface`) additionally *absorbs* the sideband beneath its channels so a stale raw value cannot resurface when the entry clears; momentary owners (flash, locate, presets) do not absorb, so their release reveals what the sideband held.

### Effect suppression

A property with an active programmer entry (blind off) suppresses **every** effect on it — cue-owned and manual alike. The tick's reset pass paints the programmer value; the effect's apply is skipped for that (fixture, property) only, so a group effect keeps painting its other members. Clearing the entry lets the effect resume on the next tick — this is what makes Locate non-destructive (it used to remove covering effects; now they freeze under the locate and resume on release).

Exempt: effects in the reserved **programmer priority band** (`FxEngine.PROGRAMMER_FX_PRIORITY_BASE`, strictly above every cue-derived priority). These are programmer-owned FX — the busking pad creates them by passing `programmerOwned: true` on the FX add routes — and they modulate *on top of* programmer values rather than being suppressed by them. They are also exempt from the cue auto-tag in `addEffect`, so a busk started while an FX_APPLICATION script runs isn't swept by that cue's teardown. Sideband channel slots do not suppress effects — only property entries do.

### Blind

`programmer.setBlind { blind, fadeMs? }` gates the programmer's contribution out of the merge without touching the stored state: entering blind releases programmer-held properties to the layers below (cues → baseline), effects resume painting, and writes made while blind stage silently. Exiting restores exactly what was staged. `touched` flags survive the round trip.

### Fades

Clears, blind transitions, and sets accept an optional `fadeMs`, driving the per-channel `DmxController` ramp (`TickerState`). **Fades apply only to properties no running effect covers; effect-covered properties settle on the next tick and snap** — the 50 Hz tick writes covered channels every frame with fade 0 through the conflated channel changer, which would kill any ramp mid-flight. Settings (discrete wheels) always snap regardless of `fadeMs`.

### Clearing

- `programmer.clearEntry { target, propertyName, fadeMs? }` — releases every owner's slot on one property.
- `programmer.clearAll { fadeMs? }` / `POST /api/rest/programmer/clear-all` — the operator's Clear: sweeps every owner's entries (property + sideband) **and removes every effect in the programmer priority band**, then republishes everything in one pass. Also resets locate and preset-toggle bookkeeping so the toggles stay consistent with the swept store. Sideband channels with no backing property release to DMX 0 (nothing sits below them). Both counts come back — `cleared` (entries) and `effectsCleared` (band FX). Band effects are removed *before* the store sweep so the single cascade republish that follows covers both.

Deterministic release: clearing a programmer entry re-resolves the cascade below it — the property lands on the surviving owner, the cue layer, or baseline, never on a stale snapshot.

Clear also drops the include target (below): nothing is staged afterwards, so an Update offering that target would silently write nothing.

### The `sourceGroup` hint

`programmer.set` / `setColour` / `setPosition` against `targetType: "group"` fan out to member fixtures, stamping each slot with the group's name. Record uses that hint to decide whether it may re-emit a group-shaped cue row.

Clients that fan a group-scoped gesture out *themselves* — a group virtual dimmer over members with different property shapes, a Highlight release restoring per-fixture values — can pass the hint explicitly via an optional `sourceGroup` field. It is validated server-side (the named group must exist and must contain the target fixture) and dropped with a warn otherwise, so a client cannot conjure a group row out of a write that never came from a group control. The two backend fan-out sites, group Locate and group preset toggles, pass it directly.

### The include target

`ProgrammerStore.lastIncludedTarget` records what Include last loaded, and is what a bare Update writes back to. It is set by Include and by any Record that names a cue (record-then-tweak-then-Update being the obvious next gesture), cleared by Clear and by deleting the cue, and left alone by `clearEntry`, `setBlind`, and a successful Update — Update is repeatable.

It reaches the client two ways: on `programmer.state` as `lastIncluded`, and as a `programmer.includeTarget` push. That push is a **broadcast**, unlike every other programmer reply, because the programmer is shared: a second tab's Update button must offer the same target.

### Provenance

The engine maintains, per (target, property), the identity of the winning contributor — `PARKED`, `PROGRAMMER`, `EFFECT` (with `effectId`/`cueId`), or `CUE` (with the winning `cueId` **and** `cueStackId`); baseline keys are omitted. Recomputed on layer events only (programmer mutation, cue republish, effect lifecycle, park change) — never per frame — and broadcast as a full-state `provenanceState` WS message. This powers ownership colouring in the programmer sheet. Note that Update's Mode B checklist does *not* read provenance: provenance correctly names the programmer as the winner, which is precisely the answer "what am I sitting on top of?" cannot use. `FxEngine.underlyingSources` answers that from the programmer-independent Layer 3 winner map instead. Because programmer mutations reply only to the connection that made them, `provenanceState` is also the frontend's signal to re-read `programmer.state` — it is the one broadcast that fires for a write made by a MIDI surface, a locate, or another browser tab.

### Record / Include / Update

The programmer's authoring loop, exposed as three REST endpoints under `/api/rest/programmer`
(REST rather than `programmer.*` WS ops because each needs a structured reply the fire-and-forget
WS channel can't carry, and because they mutate cues).

**Mask.** All three can be scoped to the console attribute families — `INTENSITY`, `POSITION`,
`COLOUR`, `BEAM` (`fx/PropertyMask.kt`). Deliberately coarser than `PropertyCategory`: the same
physical attribute is annotated differently across heads (a gobo wheel is a `DmxFixtureSetting` on
one fixture and a plain slider on another), so a category-level mask would be fixture-dependent and
silently miss heads. Omitting the mask, or naming all four groups, means "everything".

**`record { mode, source, mask?, ... }`** writes the programmer into a cue.

- **Source** — `TOUCHED` (default) takes property entries whose winning slot is an operator edit;
  `ALL` also takes untouched slots, which today means unpark hand-downs (channel-shaped by
  construction, so `touched = false` only ever appears in the sideband); `STAGE_SNAPSHOT` captures
  composed stage state plus running effects and the live palette. Recording *from the programmer*
  rather than from composed state is the point: what you busked is what records.
- **Sideband** — a slot whose channel a property covers is lifted into a property row; one with no
  backing property cannot be (cue assignments have no channel form) and is reported as a skip
  rather than silently dropped. Element-keyed entries are skipped for the same reason: cue
  assignments resolve fixture keys, not element keys.
- **Group shape** — a `sourceGroup` hint only *nominates* a group row. It is emitted iff every
  member of the named group holds an entry for that property with an identical value. A stale or
  missing hint therefore degrades to per-fixture rows — verbose, never wrong.
- **Mode** — `CREATE` makes a cue in a stack; `MERGE` upserts; `REMOVE` deletes the rows the
  recording names; `UPDATE_EXISTING` replaces the cue's in-mask content. **Triggers and timed
  children are never touched by any mode** — they are not programmer state, and `CueTriggerManager`
  owns the timed ones. Recording into the live cue of a stack republishes its Layer 3, or the DB
  and the published layer would disagree and the next Clear would snap the rig back.

**`include { cueId, mask? }`** loads a cue's assignments and immediate FX into the programmer as
`INCLUDE`-owned slots and programmer-band effects, and returns the fixture keys for the sheet to
select (MagicQ's "Select Heads on Include"). Two details earn their keep: an FX child the cue is
already running is *not* re-spawned (there is no FX-vs-FX suppression, so a band duplicate would
double-apply), and spawned instances leave `cueId` null so `removeEffectsForCue` can't sweep the
operator's programmer out from under them when the cue stops.

**`update { targets?, mask? }`** writes back.

- **Mode A** — with an include target, only entries that *changed since Include* are written. The
  INCLUDE slot survives underneath the operator's write, so the comparison needs no extra state.
  This is also what makes Update reference-preserving before palette refs exist: a cue row stored
  as `"P1"` is parsed to a colour at include time, and because the operator didn't touch it, it is
  not written back — the ref survives. Writing everything back would harden every ref in the cue on
  the first Update after any Include.
- **Mode B** — with nothing included, the server answers with the cues the programmer is currently
  overriding, grouped by stack, and the client confirms which to write. Keys with no cue underneath
  are bucketed separately ("record a new cue instead"). A commit writes each cue only the keys it
  was actually underneath.
- Update applies MERGE semantics and never deletes. Removing content from a cue is `record REMOVE`.

**The cueEdit guard**, asymmetric because the risks are: opening a cue-edit session on the current
include target *warns*, and Include on a cue with an open session warns (Include only reads).
Record and Update targeting a cue with an open session are a **409 unless forced** — `beginEdit`
snapshots the cue's assignments and Discard restores that snapshot wholesale, so anything written
underneath would be silently reverted.

## Layer 3 — Effects

Tempo-synchronised `FxInstance`s that modulate property values each tick. Driven by the Master Clock (24 ticks/beat).

### Ordering

Deterministic. Effects sort by:

1. Explicit `priority` field on the `FxInstance` (higher priority composes later so it "wins" against earlier same-channel contributions). Manual effects default to 0; cue-owned effects get a derived priority (`stackId·1M + sort·1K + 1`); the programmer band sits above both.
2. Tie-break: cue-stack position for cue-owned effects, creation timestamp for manual / ad-hoc effects.

### Per-tick reset

At the start of each beat tick, properties touched by at least one active effect are reset to the **layer below** (not to hardcoded zero):

- If the programmer holds this property (and blind is off), reset to the programmer value — and skip the effect's apply for it (suppression, above).
- Else if Layer 4 contributes to this property, reset to the composed cue value.
- Else reset to the Layer 5 baseline.

### Blend modes

Each `FxInstance` carries a `blendMode` applied against the accumulated output at its composition point:

- `OVERRIDE` — effect replaces accumulator.
- `ADDITIVE` — `(accumulator + effect).coerceIn(0, 255)`.
- `MULTIPLY` — `(accumulator * effect) / 255`.
- `MAX` — `max(accumulator, effect)`.
- `MIN` — `min(accumulator, effect)`.

Multi-effect composition on the same property: start from the reset baseline, iterate effects in sorted order, fold `accumulator = blend(accumulator, effectOutput, effectMode)`.

### Fade envelopes

Each `FxInstance` carries an `intensityMultiplier` in `[0, 1]`. The effect's output is scaled by this multiplier before the blend. It is used for manual and scripted effect fades.

Cue transitions do **not** drive `intensityMultiplier`. On a cue change, outgoing effects are removed immediately and incoming effects start at full intensity; only Layer 4 property assignments crossfade (via per-cue fade weights). This matches Eos / grandMA / Hog 4 and avoids the drop-to-0 artefact that came from scaling OVERRIDE-blend effect outputs by a crossfade multiplier.

## Layer 4 — Cue Property Assignments

Deterministic per-cue state — the "this cue asserts property X = value" layer. Contributed by active cues via the `CuePropertyAssignment` collection.

> Code note: this layer is composed by `Layer3Resolver` / consumed via
> `LayerResolver.currentLayer3State` — the class names carry the pre-renumber "Layer 3"
> and are kept to avoid churning ~25 files; a rename to `CueAssignmentResolver` is queued
> in [followups.md](plans/followups.md).

### Composition rules by `PropertyCategory`

Each property's composition rule is declared by its category. Categories are defined in [FixtureProperty.kt](../src/main/kotlin/uk/me/cormack/lighting7/fixture/FixtureProperty.kt).

| Category | Default rule | Reasoning |
|---|---|---|
| `DIMMER`, `UV`, `STROBE` | `HTP` (max) | Intensity-like. Stacking two cues should brighten the output, matching operator expectation across every surveyed pro console. |
| `COLOUR`, `AMBER`, `WHITE` | `LTP` | Blending colour between stacked cues produces mud. Last-applied-wins matches industry convention for non-intensity. |
| `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE` | `LTP` | Two positions don't meaningfully combine. |
| `SPEED`, `SETTING`, `OTHER` | `LTP` | Discrete or control parameters. |

Fixture authors can override the category default per-property via `@FixtureProperty(composition = CompositionRule.LTP)` (or `HTP`). Rare but supported — for example, a fixture whose DIMMER-classed channel is really a shutter enum would override to LTP.

### Resolution algorithm

For each (fixture, property) pair:

1. Collect all active cues contributing an assignment to this pair. A group-level cue assignment expands to its members. A fixture-level assignment wins over a group-level assignment for the same property (**specificity rule**).
2. Read the property's composition rule (category default, or per-property override).
3. Apply the rule:
   - `LTP`: take the assignment from the highest-priority contributor, where priority is cue-stack position for stacked cues and activation time for standalone cues.
   - `HTP`: take the `max` of all contributors' values, each scaled by its cue's current fade weight (see Crossfade below).
4. Convert the resolved property value to channel values using the fixture's patch. Colour as hex, dimmer as 0–255 or 0–1, settings as enum string, pan/tilt as the native unit.

### Fade weight

Each active cue has a fade weight in `[0, 1]` tracking its crossfade progress. During a cue transition, outgoing cues fade `1 → 0` and incoming cues fade `0 → 1` over the cue fade time. The weight feeds both the crossfade interpolation for `LTP` categories and the scaled `max` for `HTP` categories.

Interaction with cue-edit sessions: when a client holds an active `cueEdit` session, surface fader writes route into the cue's Layer 4 property assignments (via `cueEdit.setProperty`) rather than the programmer. See [Cue edit sessions](#cue-edit-sessions) below.

## Layer 5 — Baseline / defaults

Per-fixture baseline values: typically 0 (blackout) for intensity-like channels, 127 (centred) for pan/tilt where the fixture profile specifies. The "rest state" seen when no other layer contributes.

## Crossfade behaviour

When a cue transitions (outgoing → incoming), each property's Layer 4 contribution crossfades according to a per-category rule:

- **Sliders** (`DIMMER`, `UV`, `STROBE`, `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE`, `AMBER`, `WHITE`, `SPEED`): linear interpolation between outgoing and incoming values, weighted by fade progress.
- **Colour** (`COLOUR`): linear interpolation in RGB space. HSV / LAB modes reserved for future.
- **Settings** (`SETTING`, `OTHER`): snap at 50% fade progress. Discrete enums don't interpolate.
- **Position** (pan / tilt pair) with `moveInDark = true` on the incoming cue: if the outgoing cue ends with intensity 0, pre-apply the new position during the outgoing fade-out rather than waiting for the incoming fade-in. Otherwise behave as a slider.

For `HTP` categories across multiple contributors, each contributor's value is scaled by its own fade weight before being folded with `max`.

Fade time source: cue-level fade time by default. The data model supports per-property fade-time override on individual assignments; authoring UX for that is out of scope for Phase 0.

## Stomp

A cue carries a `stomp: Boolean` (default `false`). When a stomping cue applies, the FX engine removes ad-hoc effects tagged with *other cue IDs* that target properties covered by this cue's Layer 4 assignments. This matches grandMA3's `Stomp` — a new cue cleanly takes over from in-flight phasers without chasing them.

Scope: stomp only removes ad-hoc effects owned by other cues. Manual (un-cued) effects are not stomped. Effects owned by this cue itself are not stomped — they co-exist with its Layer 4.

## Cue edit sessions

Operators edit cues through an active editing session, managed by `cueEdit.*` socket messages.

### Lifecycle

- `cueEdit.beginEdit { cueId, mode }` — server snapshots the cue's Layer 4 property assignments (the pre-edit baseline) and stores it for the session. In Live mode the cue is activated on stage for the session (if not already active). In Blind mode the session does not toggle stage activation.
- `cueEdit.setChannel / setProperty / setPalette / addPresetApplication / addAdHocEffect / clearAssignment` — edits auto-persist into the cue. In Live mode the server also performs the transient stage-side write for instant feedback. In Blind mode there is no transient write, but edits still propagate to stage naturally via Layer 4 re-composition if the cue is already active via the playback stack (see below).
- `cueEdit.discardChanges { cueId }` — restores the cue's Layer 4 property assignments from the session-start snapshot. Stage reflects the restored state on the next composition pass if the cue is active (in either mode). Equivalent in spirit to EOS `Release` / grandMA `Clear`.
- `cueEdit.setMode { cueId, mode }` — transitions mid-session. Live → Blind drops the session-owned stage activation but keeps the session open; if the cue is also active via the stack it remains on stage. Blind → Live activates the cue on stage for the session if it is not already active.
- `cueEdit.endEdit { cueId }` — closes the session. In Live mode, drops the session-owned stage activation (the cue stays on stage if the stack is also running it). In Blind mode, is a stage no-op. The snapshot is dropped.

### Live vs Blind

The modes differ in whether the edit session itself activates the cue on stage. They do **not** differ in whether edits to an *already-active* cue are visible — those always are, because Layer 4 re-composes each frame.

- **Live** (default on the Cues page): starting an edit activates the cue on stage. Edits reflect in real time. What you see is what you save.
- **Blind**: starting an edit does not activate the cue. Two cases:
  - *Cue is not active via the stack*: edits persist silently and become visible when the cue is next fired. Useful for preparing an upcoming cue while a different look is on stage.
  - *Cue is already active via the stack* (the common case during a running show): edits persist and are visible on stage on the next composition pass, because the cue's Layer 4 contribution recomposes with the new values. This lets the operator tweak the current live look without any separate "live override" flow, while edits to *other* inactive cues in the same session remain invisible until fired.

Naming note: the cue-edit session's Live/Blind *mode* and the programmer's **Blind** gate are different mechanisms that coexist. UI labels must distinguish them — "Blind" stays with the programmer gate (console muscle memory); the cue-edit mode toggle is relabelled "Preview edit" wherever both are visible.

## Divergences from industry consoles

For readers familiar with EOS, grandMA, Hog, MagicQ, or Avolites:

- **Two cue-authoring paths coexist**: the programmer's Record/Include/Update loop (stage-driven) and `cueEdit` sessions (a bound form over the cue document, auto-persisting with snapshot-based discard). Most consoles have only the former. See the guard below.
- **No HTP/LTP toggle on cues**: the composition rule is a property-category intrinsic, not a per-cue choice. We do not need the EOS / Hog "this cuelist is HTP for intensity" switch because the category already declares it.
- **Programmer wins HTP categories too**: MagicQ's `Programmer overrides HTP chans` setting collapsed to an always-on rule — busking-first system, predictability beats max-merge.
- **Non-tracking**: cues are complete states; there is no tracking apparatus (block/unblock/trace/cue-only).

## Worked examples

### Example 1 — parked channel under an effect

Setup: dimmer on channel 12 is parked at 128. A `Pulse` effect targets channel 12 with `blendMode: OVERRIDE` oscillating `0..255`.

Per frame:
- Layer 5 baseline: 0.
- Layer 4: empty.
- Layer 3: effect computes, say, 200.
- Layer 2: empty.
- Layer 1: parked → 128.
- Output: 128.

The effect runs and consumes cycles, but parking wins. De-parking channel 12 immediately restores effect output — and leaves 128 behind as an `unpark` sideband slot in the programmer that the effect resets to, so the channel doesn't drop through to the Layer 5 baseline of 0 on the way.

### Example 2 — programmer value over a running effect

Setup: operator drags dimmer on channel 7 to 180 (the `updateChannel` shim lifts it to a `web` programmer entry on the fixture's `dimmer` property). A `SineWave` effect targets the same property with `blendMode: ADDITIVE`.

Per frame:
- Layer 5: 0.
- Layer 4: empty.
- Layer 3: the effect is **suppressed** on this property — the programmer holds it.
- Layer 2: 180 (sticky, `touched`).
- Layer 1: not parked.
- Output: 180.

The operator's value wins outright — no wiggle on top. Clearing the entry (`programmer.clearEntry`, optionally with a fade) releases the property and the effect resumes painting on the next tick. Had the effect been in the programmer priority band (a busking-pad effect), it would have composed `180 + 30 = 210` on top instead.

### Example 3 — two cues contributing HTP dimmer

Setup: Cue A active, `dimmer = 100` on fixture F. Cue B active on top of A, `dimmer = 180` on F. Both fully faded in (weight = 1.0). The programmer holds nothing on F.

- Category `DIMMER` → `HTP`.
- Contributors: A = 100 × 1.0 = 100; B = 180 × 1.0 = 180.
- Composition: `max(100, 180)` = 180.
- Layer 4 output: 180.

While cue A is fading out (weight = 0.5), A contributes 50; `max(50, 180)` = 180. Cue B dominates smoothly without jumping. Had the operator busked F's dimmer to 40, the programmer entry would output 40 regardless — programmer beats HTP.

### Example 4 — two cues contributing LTP colour

Setup: Cue A active, `colour = #FF0000` (red) on fixture F. Cue B most recently activated, `colour = #0000FF` (blue) on F. Both fully faded in.

- Category `COLOUR` → `LTP`.
- Resolver picks B (most recent activation).
- Layer 4 output: blue.

While cue B is fading in (weight = 0.6), the resolver linearly interpolates in RGB space between A and B: `(1 - 0.6) · (255, 0, 0) + 0.6 · (0, 0, 255)` = `(102, 0, 153)` — fades through purple. Once B is fully in, output is pure blue.

### Example 5 — busk over a cue, then clear with a fade

Setup: cue 42 is on stage with `dimmer = 200` on fixture F. The operator busks F's dimmer to 60.

1. The programmer holds `F.dimmer = 60` (`web`, touched). Output: 60 — the programmer beats the cue.
2. The cue keeps recomposing underneath at 200; nothing visible changes while the entry holds.
3. `programmer.clearEntry { fadeMs: 1000 }` — the property ramps 60 → 200 over one second via the `DmxController` ramp (no effect covers it), landing exactly on the cue value.
4. Had a running effect covered `F.dimmer`, the publish would skip it and the next tick would snap the property back to effect-over-cue output — fades never fight the 50 Hz tick.

### Example 6 — blind busking

Setup: cue 42 on stage (`dimmer = 200`). Programmer blind is engaged.

1. `programmer.setBlind { blind: true }` — stage unchanged (nothing staged yet).
2. Operator busks `F.dimmer = 60`. The entry is stored (`touched`) but the output stays 200 — blind excludes the programmer from the merge.
3. `programmer.setBlind { blind: false, fadeMs: 500 }` — the staged 60 lands with a half-second fade.
