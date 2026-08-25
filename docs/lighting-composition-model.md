# Lighting Composition Model

This document specifies how lighting7 composes the DMX channel output sent each frame. It is the source of truth for priority rules, blending, programmer semantics and cue crossfades.

Related:
- Strategic plan for adopting this model: [cue-authoring-unification-plan.md](plans/completed/cue-authoring-unification-plan.md).
- The programmer redesign that introduced Layer 2 and renumbered the stack: [programmer-redesign-proposal.md](plans/completed/programmer-redesign-proposal.md).
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
> 2/3. Only the archived plans in [plans/completed/](plans/completed/) still use those
> numbers; code and docs are on the numbering above.

## Layer 1 — Parking

The `ParkManager` holds per-channel overrides. If a channel is parked, the transmitted value is the parked value — no other layer contributes.

Implementation note: parking is applied at transmit time in `ArtNetController` (after Layer 2–4 composition has already written to the controller's `currentValues`). This is an optimisation — conceptually, parking is Layer 1 and the pre-composition pipeline can short-circuit for parked channels.

Rationale: parking protects specific channels during maintenance, rigging checks, or troubleshooting. It must be unconditional and must not be overwritten by cue-apply, effects, or manual writes.

**Releasing park does not move the output.** Whatever sits under a parked channel is unrelated to what the rig is emitting — usually 0 — so `ParkManager.unpark` / `unparkAll` first hand the parked value *down* into the programmer's channel sideband (owner `unpark`, `touched = false`) and the controller buffer, then drop the override. The hand-off happens before the entry is removed, so no transmit frame lands in a window where park is gone but the stale value underneath is still in place. Net effect: unparking settles exactly where a manual channel write of the same value would, and `park → unpark → park` is a no-op on the wire. Rigs park hard-powered fixtures hung off a dimmer, where an unpark-to-0 snap is a safety failure rather than a cosmetic one.

## Layer 2 — Programmer

The console-style programmer: a sparse per-(fixture, property) overlay of sticky manual values held in `ProgrammerStore`. It is simultaneously the live override for busking and the staging buffer that [Record](#record--include--update) serialises. **An active programmer entry wins over cue values and suppresses effects on that property** — for HTP categories too (predictability beats max-merge in a busking-first system).

Writers: web busking (`programmer.*` ops and the `updateChannel` compatibility shim), MIDI surface faders, surface flash buttons, FX preset toggles / editor previews, Locate, and the unpark hand-down.

**There is exactly one programmer, and it is shared by every client — by design, not by omission.** A second device is a second *window onto one desk*, not a second seat: whoever signs in elsewhere sees this state and may edit it, and the three broadcast frames (`provenanceState`, `programmer.includeTarget`, `programmer.layerState`) exist to keep those windows honest. Concurrent authorship is explicitly not a scenario this desk designs for, which is what spares it a merge rule: `LayerResolver.fallbackFor` returns **one** value per (fixture, property) because DMX is one byte per channel, so two authors would force a "whose value wins?" policy that nothing here expresses — HTP cannot be it (see the line above), and the `Slot.seq` bands are the only extensible axis. Decided 2026-08-23; the reasoning, and two places the cost of the alternative was previously misjudged, are in [`FU-PROG-PER-USER`](plans/followups.md#fu-prog-per-user).

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

The engine maintains, per (target, property), the identity of the winning contributor — `PARKED`, `PROGRAMMER`, `EFFECT` (with `effectId`/`cueId`), or `CUE` (with the winning `cueId` **and** `cueStackId`); baseline keys are omitted. Recomputed on layer events only (programmer mutation, cue republish, effect lifecycle, park change) — never per frame — and broadcast as a full-state `provenanceState` WS message. This powers ownership colouring in the programmer sheet. Note that Update's Mode B checklist does *not* read provenance: provenance correctly names the programmer as the winner, which is precisely the answer "what am I sitting on top of?" cannot use. `FxEngine.underlyingSources` answers that from the programmer-independent Layer 4 winner map instead. Because programmer mutations reply only to the connection that made them, `provenanceState` is also the frontend's signal to re-read `programmer.state` — it is the one broadcast that fires for a write made by a MIDI surface, a locate, or another browser tab.

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
  composed stage state plus running effects. Recording *from the programmer*
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
  owns the timed ones. Recording into the live cue of a stack republishes its Layer 4, or the DB
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
  It is also what keeps a cue row in the *exact form it was authored*: a row stored as `"red"` is
  parsed to a colour at include time, and because the operator didn't touch it, it is not written
  back — so it stays `"red"` rather than being re-serialised as `"#ff0000"`. Writing everything back
  would rewrite every untouched row in the cue on the first Update after any Include.
- **Mode B** — with nothing included, the server answers with the cues the programmer is currently
  overriding, grouped by stack, and the client confirms which to write. Keys with no cue underneath
  are bucketed separately ("record a new cue instead"). A commit writes each cue only the keys it
  was actually underneath.
- Update applies MERGE semantics and never deletes. Removing content from a cue is `record REMOVE`.

There used to be a **cueEdit guard** here — an asymmetric one, because the risks were: Record and
Update targeting a cue with an open cue-edit session answered **409 unless forced** (`beginEdit`
snapshotted the cue's assignments and Discard restored that snapshot wholesale, so anything written
underneath was silently reverted), while Include only warned, because Include only reads. Backend
sweep item D1 retired the `cueEdit.*` family, so there is no second writer to guard against and
Record / Update have no conflict case left. `force` survives on the Record and Update request
bodies as an inert accepted field only because the frontend still sends it on every submit.

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

Deterministic per-cue state — the "this cue asserts property X = value" layer. Contributed by active cues, each supplying exactly one value per (fixture, property) — see "Looks and layers" below for how a cue's layer stack and its own `CuePropertyAssignment` rows are cooked down to that single contributor.

> Code note: this layer is composed by `CueAssignmentResolver` / consumed via
> `LayerResolver.currentCueLayerState`.

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

**Each active cue contributes exactly one value per (fixture, property).** That is an invariant, not
an incidental property: the cook step (see "Looks and layers" below) reduces a cue's whole layer
stack plus its own rows to one contributor before the resolver ever sees it. So everything below is
about composing *across* cues.

For each (fixture, property) pair:

1. Collect all active cues contributing an assignment to this pair. A group-level cue assignment expands to its members. A fixture-level assignment wins over a group-level assignment for the same property (**specificity rule**).
2. Read the property's composition rule (category default, or per-property override).
3. Apply the rule:
   - `LTP`: take the assignment from the highest-priority contributor, where priority is cue-stack position for stacked cues and activation time for standalone cues.
   - `HTP`: take the `max` of all contributors' values, each scaled by its cue's current fade weight (see Crossfade below).
4. Convert the resolved property value to channel values using the fixture's patch. Colour as hex, dimmer as 0–255 or 0–1, settings as enum string, pan/tilt as the native unit.

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
(last-write-wins for OVERRIDE blend)". That described the *FX* layer, which is a genuine
last-applied-wins sequential fold, and two mechanisms in one `applyCue` were doing opposite things.
The cook step replaced all of it with **one rule for every category: later layers win, and the cue's
own rows win over all of them**. Across cues, HTP still governs intensity — which is the pairing an
operator is most likely to be surprised by, and why `FU-MANUAL-LAYER-PRECEDENCE` exists.

### Fade weight

Each active cue has a fade weight in `[0, 1]` tracking its crossfade progress. During a cue transition, outgoing cues fade `1 → 0` and incoming cues fade `0 → 1` over the cue fade time. The weight feeds both the crossfade interpolation for `LTP` categories and the scaled `max` for `HTP` categories.

## Looks, templates and layers

A **Look** is a named, reusable bundle of property values and effects over *named fixtures*. A
**Template** is a named value for exactly *one attribute family*, with no targets of its own. A
**Layer** applies either, inside a cue at a declared position in that cue's stack — `DaoCueLayers`
carries two nullable FKs (`look_id` / `template_id`) with exactly one set, and `DaoCueLayer.source`
is the only reader.

These were one entity until session 3, distinguished by the row's targeting mode: a **bound** row
named its own fixture, a **deferred** one took its targets from the applying layer, and one entity
served both of its predecessors' jobs. What forced the split is the most useful kind of template — a
focus position, where eight heads aimed at one spot hold eight *different* pan/tilts, so its rows are
bound and `hasDeferredRows` could not tell it from a recorded Look.

**A Look row is now always bound**, and `validateLookRows` refuses `deferred`. A Look **effect** may
still be deferred, because fanning an effect over the layer's targets is a different thing from
holding a value for nobody — and it is what makes a Look usable from a busking pad.

**A template row holds an intent, not a literal**: a colour plus a white/amber policy, a level or
beam role as a percentage of each head's own range, a position in degrees. `fx/TemplateIntent.kt`
owns the grammar; `fx/TemplateResolver.kt` is the **single** implementation that turns one into
channels, and it is asked by all three consumers — `CueComposer.applyLayer` at cook,
`POST /templates/{id}/apply` for the click gesture, and `POST /templates/resolve` for the editor's
resolves-to panel. Two of those exist server-side *because* it must be one implementation: an editor
that computed its own ΔE would promise what the rig does not do.

A template's property vocabulary is **closed** (`TemplateProperty`), which is where "a template
cannot carry a gobo" lives: slotted roles are per-model, so they are refused by name and live in a
recorded Look, which names a head and can hold anything that head has. A template also holds no
effects at all (D7). It has always held no positional colour list either — and now nothing does,
because that grammar is gone: an effect parameter names a colour template rather than indexing a
list, so a template *is* the named colour instead of being one more scope that holds several.

A Look's rows hold **literals only**. A row holding a `ref:`-shaped value is rejected at the write
boundary, so **Looks do not nest** and resolution can never recurse. The `ref:` *value grammar* itself
retired in session 4 — nothing can author one — but that rejection stays, as an inlined shape check,
because it is the guarantee rather than a consequence of one.

There is deliberately **no stored attribute type**. Which families a Look touches is derived from
its rows via `maskGroupForProperty`, so the library banks by family the way the per-type palette
banks used to, and a Look can grow from one family to several with no migration. One consequence
worth noting: the old `PaletteTypeMismatch` diagnosis is gone, because "this is a POSITION bank
and that is a COLOUR property" is no longer a coherent complaint — a Look spanning both is entirely
legitimate. A reference that finds nothing now reports the symptom (no entry for this fixture and
property) rather than a cause that no longer exists.

### The cook step

A cue's layer stack plus its own local rows are flattened to **exactly one contributor per
(fixture, property)** *before* `CueAssignmentResolver` sees them:

```
layers in sortOrder → local rows → cook → ONE contributor per (fixture,property) → resolver
```

> Code note: `fx/CueComposer.kt`. The invariant — never two `Assignment`s with the same
> `(targetKey, propertyName)` for one cue — is asserted by `CueComposerTest`.

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
"last-write-wins for OVERRIDE blend", which described the *effect* layer, not this one.

**Named behaviour change.** Layered intensity is later-wins, not HTP `max()`. Stacking a dim layer
over a bright one inside one cue really does dim. HTP still governs *cross-cue* intensity, so two
active cues still add up the way every surveyed console does.

### Blending

Each layer carries a `blendMode` (`OVERRIDE`, `MAX`, `MIN`, `MULTIPLY`, `ADDITIVE` — the same
vocabulary effects use) and an `amount` in `[0, 1]`, which grandMA3 calls Amount. The layer's value
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

`propertyMask` gives per-property *inclusion*: "this cue's colour comes from Warm, everything else
local" is one `COLOUR`-masked layer, not a separate feature. Per-property *blend overrides* are out
of scope.

### Effects, and the constraint that cannot be layered away

Effects do not cook — they spawn `FxInstance`s. Spawning them in layer order is sufficient:
`FxEngine.sortedEffectsComparator` is `compareBy(priority, id)` with `id` a monotonic creation
counter, and per-tick composition is a genuine sequential fold through `FxTarget.applyValue`. So
same-priority effects already resolve last-created-wins, and layer order becomes effect order for
free — no priority arithmetic, and the uniform per-cue priority stays.

One limit has to be stated rather than designed around: **effects are Layer 3 and values are
Layer 4**, so an effect sits above a static value regardless of layer order. "Layer 2 sets colour
statically, Layer 1 runs a colour effect" resolves to the effect winning even though Layer 2 is
later. Layer order governs values-vs-values and effects-vs-effects, not the value/effect boundary.
The escape hatch is per-layer `stomp` — see [Stomp](#stomp) for the two kinds and how they differ.

### Timed layers

A layer with `delayMs` / `intervalMs` fires on a timer rather than at cue apply. Firing
**re-cooks the whole cue** with that layer included, publishing through
`FxEngine.replaceCueAssignments`. It does not append the layer's rows, and the distinction is
load-bearing: appending would place two contributors on one (fixture, property) key inside one cue,
which is exactly the ambiguity cooking removes — and the tie between them would fall to `HashMap`
iteration order. `replaceCueAssignments` rather than `setCueAssignments` because the latter resets
an in-flight crossfade weight to fully-in.

A recurring layer is therefore idempotent on Layer 4: only its effects re-trigger.

### Editing a Look

Because resolved values are cached, editing a Look re-resolves and republishes its live consumers
(`routes/lookRepublish.kt`). That is the feature's whole point: one edit moves every cue layering
it, with no cue re-fired. The order is fixed — invalidate the registry, re-resolve the programmer's
reference slots *without* publishing, replace the affected cues' rows in one pass, then publish the
programmer keys. Publishing the cue layer first would transmit stale programmer values over it for
a frame.

Finding the affected cues is now an **indexed FK query** on `cue_layers.look_id`, which is the
structural win of the merge: the named-palette era could only scan opaque `value` text for an exact string
match.

A reference whose Look stops covering it keeps its last resolved value rather than vanishing; a
disappearing programmer entry mid-show is worse than a stale one the sheet marks broken.

### What replaced the positional palette

There used to be an ordered-colour-list palette here, referenced positionally as `P1` / `P2` / `P*`
and scoped global → stack → cue by a `PaletteCascade`. It was a third, unrelated thing also called
"palette": it parameterised *effects* rather than describing a look, and Looks, cues and stacks each
carried a column for it.

**It is gone**, and what replaced it inverts the arrangement. An effect's colour parameter names a
**colour template** by uuid — `tmpl:{uuid}`, `fx/TemplateColourSource.kt` — so the colour has an
identity and a name instead of a slot number, and retuning the template moves every running effect
that follows it. There is no cascade, because there is nothing left to scope: a reference has one
answer wherever it is read.

Two rules that fall out of that, both enforced rather than conventional:

- **A reference is legal only in an effect parameter.** A value — a cue row, a Look row, a programmer
  entry — is a literal, and a *value's* dependency mechanism is a layer.
  `parseAssignmentValue` returns null for a `tmpl:`-shaped value and `validateLookRows` rejects one,
  beside its `ref:` rejection.
- **Only a generic colour template can be named.** An effect's output is one colour applied to every
  head it targets, so `TemplateResolver.resolveColourGeneric` resolves the intent without a fixture —
  as though the head were RGBW, which makes it identical to the same template applied as a layer on
  any RGBW/RGBWA head. A per-fixture template holds no single colour and is refused.

  **The cost lands on heads with no white emitter, and it is sharper than "one emitter short".**
  Under `extract` the neutral is taken out of RGB at resolve time, and `applyExtendedChannel` then
  drops the white byte on a head that has no white property — so that head gets the reduced RGB with
  nothing compensating, reading dimmer *and* more saturated than the hex asked for (`#FF9D4A` lands
  as `#B55300`). That is worse than the RGB-only reading, not equal to it. It is the accepted trade
  for matching the layer exactly on the common head; resolving `RGB_ONLY` there instead is a one-line
  change that inverts which class of head is exact.

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

There are **two** stomps, at two scopes, and they are not variants of one mechanism. The cue-level
one *removes* instances belonging to other cues; the layer-level one *suppresses* instances
belonging to lower layers of the same stack. Mixing them up is the mistake to avoid: within-cue
stomp cannot remove, for the reason spelled out below.

### Cue-level stomp — cross-cue, removal

A cue carries a `stomp: Boolean` (default `false`). When a stomping cue applies, the FX engine removes ad-hoc effects tagged with *other cue IDs* that target properties covered by this cue's Layer 4 assignments. This matches grandMA3's `Stomp` — a new cue cleanly takes over from in-flight phasers without chasing them.

Scope: stomp only removes ad-hoc effects owned by other cues. Manual (un-cued) effects are not stomped. Effects owned by this cue itself are not stomped — they co-exist with its Layer 4.

The overlap set is the union of the cue's local rows (expanded through their groups) and
`CookResult.assertedKeys` — everything the cue's **layers** asserted, group aliases included. The
layer half was missing until the within-cue work landed, and its absence meant a cue whose colour
came entirely from a layer stomped nothing on colour: `buildStompOverlapFromAssignments` reads the
cue's *local* rows alone, which was the whole of a cue's surface before the layer model and is not
any more. `buildStompOverlap` is the union and is what callers should use.

### Layer-level stomp — within-cue, suppression

A layer carries its own `stomp: Boolean`. When set, it switches off the effects of every layer
**below** it in the same stack, on every property it asserts. It is what makes the Layer 3/4 limit
above recoverable: "layer 2 sets colour statically, layer 1 runs a colour effect" resolves to the
effect winning, and `stomp` on layer 2 is how the operator says otherwise.

Four boundaries:

- **Strictly below.** A stomping layer never suppresses its own effects, nor anything above it.
  Within one layer the Layer 3/4 order still holds — a Look with both a colour row and a colour
  effect runs the effect.
- **Layers only.** The cue's local rows and its ad-hoc effects belong to no layer, so nothing in the
  stack is above them to switch them off.
- **Coarse.** Every property the stomper asserts, not only the ones a lower layer's effect is
  actually fighting over. Finer granularity is `FU-LOOK-STOMP-GRANULAR`, deliberately deferred until
  the coarse version has been used on a rig.
- **Suppression, not removal**, and this one is not a preference. Disabling the stomping layer, or
  pulling its `amount` to zero, only triggers a *recook* — so a removed `FxInstance` would be
  unrecoverable, and the operator could never undo a stomp. Suppression keeps the instance running,
  so clearing the stomp brings it back mid-phase with nothing restarted.

The mechanism: `CueComposer.cook` returns `CookResult.stompSuppression`, a
`layerId → targetKey → properties` map, because only the cook knows which properties each layer
asserted — the rows keep the winner per key, and a layer that asserted and then lost still asserted.
It is published in the same locked mutation as the rows (`setCueAssignments` /
`replaceCueAssignments` take it as a parameter for exactly that reason), and read per tick by
`FxEngine.isSuppressed` against `FxInstance.cueLayerId` / `programmerLayerId`. The reset pass has
already painted the cooked value on the property, so a skipped apply *shows* that value rather than
freezing the effect's last frame.

Three details worth knowing:

- The check runs **before** the programmer-band exemption. Programmer-layer effects live in that
  band by construction, so exempting the band would make programmer stomp a no-op.
- The **programmer stack honours it too**, published from `ProgrammerLayerStack.materialise` rather
  than from `syncEffects` beside the instances: `syncEffects` deliberately does not rebuild on a
  mask, amount or order change, and all three move the suppression set.
- **Provenance is stomp-aware**, through the same `isLayerStomped` helper the tick loops use.
  `highestPriorityEffectByKey` skips a stomped effect *for the stomped key only*, so a
  lower-priority effect on that key can still be reported — and `computeProvenance` /
  `underlyingSources` therefore name the cue rather than an effect nobody can see. Keeping one
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
removed, besides ~1,000 lines: a whole second writer to Layer 4, the 409 guards that existed to
keep the two writers apart, and the surface-feedback path that made a bound fader show the cue
being edited rather than the stage.

One naming consequence worth keeping: **Blind** now unambiguously means the programmer's Blind
gate. It used to collide with the cue-edit session's own Live/Blind mode, which is why that mode
was relabelled "Preview edit" wherever both were visible; there is no longer anything to
disambiguate it from.

## Divergences from industry consoles

For readers familiar with EOS, grandMA, Hog, MagicQ, or Avolites:

- **One cue-authoring path**, the programmer's Record/Include/Update loop — which is also what most consoles have. There were two until backend sweep item D1; see [Cue edit sessions — retired](#cue-edit-sessions--retired).
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
