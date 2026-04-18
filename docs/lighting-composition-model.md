# Lighting Composition Model

This document specifies how lighting7 composes the DMX channel output sent each frame. It is the source of truth for priority rules, blending, direct-write semantics, cue crossfades, and `cueEdit` session behaviour.

Related:
- Strategic plan for adopting this model: [cue-authoring-unification-plan.md](cue-authoring-unification-plan.md).
- Effect engine details: [fx-engineering.md](fx-engineering.md).
- DMX transport and parking: [dmx-engineering.md](dmx-engineering.md).
- Prior-art survey that drove these decisions: [research/composition-model-prior-art.md](research/composition-model-prior-art.md).

## Overview

Per frame, the DMX output for each channel is resolved by walking an ordered layer stack. The top-most non-empty contribution wins:

```
Layer 1  Parking                     absolute override per channel
Layer 2  Effects                     tempo-synced FX, priority-ordered, blend modes
Layer 3  Property Assignments        deterministic per-cue state, per-category HTP/LTP
Layer 4  Direct Live Writes          sticky manual channel writes
Layer 5  Baseline / defaults         usually 0
```

Intuition: parking (Layer 1) sits on top for safety. Playbacks (Layer 3) assert state. Effects (Layer 2) modulate over that state. Direct live writes (Layer 4) fill gaps not covered by a cue. Defaults (Layer 5) fall through.

## Layer 1 — Parking

The `ParkManager` holds per-channel overrides. If a channel is parked, the transmitted value is the parked value — no other layer contributes.

Implementation note: parking is applied at transmit time in `ArtNetController` (after Layer 2–4 composition has already written to the controller's `currentValues`). This is an optimisation — conceptually, parking is Layer 1 and the pre-composition pipeline can short-circuit for parked channels.

Rationale: parking protects specific channels during maintenance, rigging checks, or troubleshooting. It must be unconditional and must not be overwritten by cue-apply, effects, or direct writes.

## Layer 2 — Effects

Tempo-synchronised `FxInstance`s that modulate property values each tick. Driven by the Master Clock (24 ticks/beat).

### Ordering

Deterministic. Effects sort by:

1. Explicit `priority` field on the `FxInstance` (higher priority composes later so it "wins" against earlier same-channel contributions).
2. Tie-break: cue-stack position for cue-owned effects, creation timestamp for manual / ad-hoc effects.

The pre-refactor `ConcurrentHashMap` iteration left the order undefined. It is replaced by a sorted pass.

### Per-tick reset

At the start of each beat tick, properties touched by at least one active effect are reset to the **layer below** (not to hardcoded zero):

- If Layer 3 contributes to this property, reset to the Layer 3 composed value.
- Else if Layer 4 has a sticky direct write for this property, reset to that value.
- Else reset to the Layer 5 baseline.

This fixes the pre-refactor bug where direct writes disappeared under running effects.

### Blend modes

Each `FxInstance` carries a `blendMode` applied against the accumulated output at its composition point:

- `OVERRIDE` — effect replaces accumulator.
- `ADDITIVE` — `(accumulator + effect).coerceIn(0, 255)`.
- `MULTIPLY` — `(accumulator * effect) / 255`.
- `MAX` — `max(accumulator, effect)`.
- `MIN` — `min(accumulator, effect)`.

Multi-effect composition on the same property: start from the reset baseline, iterate effects in sorted order, fold `accumulator = blend(accumulator, effectOutput, effectMode)`.

### Fade envelopes

Each `FxInstance` carries an `intensityMultiplier` in `[0, 1]` representing its cue/stack fade weight. The effect's output is scaled by this multiplier before the blend.

## Layer 3 — Property Assignments

Deterministic per-cue state — the "this cue asserts property X = value" layer. Contributed by active cues via the `CuePropertyAssignment` collection (introduced in Phase 1).

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

## Layer 4 — Direct Live Writes

Transient writes from `updateChannel` that are not scoped to a cue-edit session. Visible when no higher layer is writing to the channel.

Layer 4 writes are **sticky** — they persist until explicitly released. Effect reset-to-neutral no longer clobbers them. They remain until:

- A new cue is triggered whose Layer 3 contribution covers the channel, or
- `clearAssignment` is called for the target, or
- A fresh `updateChannel` sets a new value for the channel.

Interaction with cue-edit sessions: when a client holds an active `cueEdit` session, `updateChannel` is replaced by `cueEdit.setChannel`, which routes the write into the cue's Layer 3 property assignments rather than Layer 4. See [Cue edit sessions](#cue-edit-sessions) below.

## Layer 5 — Baseline / defaults

Per-fixture baseline values: typically 0 (blackout) for intensity-like channels, 127 (centred) for pan/tilt where the fixture profile specifies. The "rest state" seen when no other layer contributes.

## Crossfade behaviour

When a cue transitions (outgoing → incoming), each property's Layer 3 contribution crossfades according to a per-category rule:

- **Sliders** (`DIMMER`, `UV`, `STROBE`, `PAN`, `TILT`, `PAN_FINE`, `TILT_FINE`, `AMBER`, `WHITE`, `SPEED`): linear interpolation between outgoing and incoming values, weighted by fade progress.
- **Colour** (`COLOUR`): linear interpolation in RGB space. HSV / LAB modes reserved for future.
- **Settings** (`SETTING`, `OTHER`): snap at 50% fade progress. Discrete enums don't interpolate.
- **Position** (pan / tilt pair) with `moveInDark = true` on the incoming cue: if the outgoing cue ends with intensity 0, pre-apply the new position during the outgoing fade-out rather than waiting for the incoming fade-in. Otherwise behave as a slider.

For `HTP` categories across multiple contributors, each contributor's value is scaled by its own fade weight before being folded with `max`.

Fade time source: cue-level fade time by default. The data model supports per-property fade-time override on individual assignments; authoring UX for that is out of scope for Phase 0.

## Stomp

A cue carries a `stomp: Boolean` (default `false`). When a stomping cue applies, the FX engine removes ad-hoc effects tagged with *other cue IDs* that target properties covered by this cue's Layer 3 assignments. This matches grandMA3's `Stomp` — a new cue cleanly takes over from in-flight phasers without chasing them.

Scope: stomp only removes ad-hoc effects owned by other cues. Manual (un-cued) effects are not stomped. Effects owned by this cue itself are not stomped — they co-exist with its Layer 3.

Data-model support lands in Phase 0. Authoring UX for the flag lands in a later phase.

## Cue edit sessions

Operators edit cues through an active editing session, managed by `cueEdit.*` socket messages.

### Lifecycle

- `cueEdit.beginEdit { cueId, mode }` — server snapshots the cue's Layer 3 property assignments (the pre-edit baseline) and stores it for the session. In Live mode the cue is also activated on stage. In Blind mode the stage is untouched.
- `cueEdit.setChannel / setProperty / setPalette / addPresetApplication / addAdHocEffect / clearAssignment` — edits auto-persist into the cue. In Live mode the server also performs the transient stage-side write for instant feedback. In Blind mode edits persist without any stage effect.
- `cueEdit.discardChanges { cueId }` — restores the cue's Layer 3 property assignments from the session-start snapshot. In Live mode the stage reflects the restored state on the next composition pass. Equivalent in spirit to EOS `Release` / grandMA `Clear`.
- `cueEdit.setMode { cueId, mode }` — transitions mid-session. Live → Blind stops the cue on stage but keeps the session open. Blind → Live applies the cue's currently-persisted state to the stage.
- `cueEdit.endEdit { cueId }` — closes the session. In Live mode, stops the cue or hands control back to the stack. In Blind mode, is a stage no-op. The snapshot is dropped.

### Live vs Blind

- **Live** (default on the Cues page): the cue is active on stage and edits reflect in real time. What you see is what you save.
- **Blind**: the stage is untouched; edits persist to the cue only. Useful when editing cues during a running show without disturbing the current look.

## Divergences from industry consoles

For readers familiar with EOS, grandMA, Hog, MagicQ, or Avolites:

- **Stricter Blind**: our Blind never writes to stage. Pro consoles let some active-target edits through (EOS updates active subs / effects immediately even when the operator is editing in Blind). We prioritise the simpler mental model.
- **No Update command**: auto-persist with snapshot-based discard replaces the traditional buffer + commit flow (EOS `Update`, grandMA `Store /merge`). Operators coming from pro consoles will briefly look for one — `discardChanges` is the escape hatch.
- **No HTP/LTP toggle on cues**: the composition rule is a property-category intrinsic, not a per-cue choice. We do not need the EOS / Hog "this cuelist is HTP for intensity" switch because the category already declares it.
- **No "programmer" layer above playbacks**: direct writes are Layer 4, *below* playbacks' Layer 3. Pro consoles place the programmer above playbacks. For us, direct writes are sticky but not supreme. During cue authoring, `cueEdit.setChannel` routes writes into Layer 3, which yields the effective operator behaviour — edits stick, win over effects, and persist on re-trigger.

## Worked examples

### Example 1 — parked channel under an effect

Setup: dimmer on channel 12 is parked at 128. A `Pulse` effect targets channel 12 with `blendMode: OVERRIDE` oscillating `0..255`.

Per frame:
- Layer 5 baseline: 0.
- Layer 4: empty.
- Layer 3: empty.
- Layer 2: effect computes, say, 200.
- Layer 1: parked → 128.
- Output: 128.

The effect runs and consumes cycles, but parking wins. De-parking channel 12 immediately restores effect output.

### Example 2 — direct write below a running effect

Setup: operator drags dimmer on channel 7 to 180. No cue is open for edit. A `SineWave` effect targets channel 7 with `blendMode: ADDITIVE`, output range `0..50`.

Per frame:
- Layer 5: 0.
- Layer 4: 180 (sticky).
- Layer 3: empty.
- Layer 2: reset target for this property = Layer 4 value = 180. Effect computes +30 → blended = `(180 + 30).coerceIn(0, 255)` = 210.
- Layer 1: not parked.
- Output: 210.

The direct write persists visibly — the effect wiggles on top of 180 rather than resetting the dimmer to 0.

### Example 3 — two cues contributing HTP dimmer

Setup: Cue A active, `dimmer = 100` on fixture F. Cue B active on top of A, `dimmer = 180` on F. Both fully faded in (weight = 1.0).

- Category `DIMMER` → `HTP`.
- Contributors: A = 100 × 1.0 = 100; B = 180 × 1.0 = 180.
- Composition: `max(100, 180)` = 180.
- Layer 3 output: 180.

While cue A is fading out (weight = 0.5), A contributes 50; `max(50, 180)` = 180. Cue B dominates smoothly without jumping.

### Example 4 — two cues contributing LTP colour

Setup: Cue A active, `colour = #FF0000` (red) on fixture F. Cue B most recently activated, `colour = #0000FF` (blue) on F. Both fully faded in.

- Category `COLOUR` → `LTP`.
- Resolver picks B (most recent activation).
- Layer 3 output: blue.

While cue B is fading in (weight = 0.6), the resolver linearly interpolates in RGB space between A and B: `(1 - 0.6) · (255, 0, 0) + 0.6 · (0, 0, 255)` = `(102, 0, 153)` — fades through purple. Once B is fully in, output is pure blue.

### Example 5 — cue edit session with discard

Setup: operator opens cue 42 (currently contains `dimmer = 200`, `colour = amber`) in Live mode.

1. `cueEdit.beginEdit { cueId: 42, mode: 'live' }` — server snapshots `{ dimmer: 200, colour: amber }`. Cue goes live on stage.
2. Operator sets `colour = cyan`, `dimmer = 50`. Layer 3 now holds the new values; stage shows dim cyan.
3. Operator calls `cueEdit.discardChanges { cueId: 42 }`. Server restores the snapshot; Layer 3 reverts to `{ dimmer: 200, colour: amber }`. Stage shows bright amber on the next composition pass.
4. Operator calls `cueEdit.endEdit { cueId: 42 }`. Session closes, snapshot dropped, cue deactivates (or hands back to the stack).
