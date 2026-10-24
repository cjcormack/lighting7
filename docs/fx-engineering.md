# FX (Effects) System Engineering Documentation

This document describes the effects subsystem for tempo-synchronized lighting effects.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md) for the
> layered composition model. Effects are **Layer 3**: they sit above cue property
> assignments (Layer 4) and below the programmer (Layer 2) and parking (Layer 1). The per-tick reset-to-
> neutral pass resets each property to the **layer below** (via `LayerResolver`), not to
> hardcoded zero, so direct writes and cue state remain visible under running effects. Effect
> iteration is a sorted pass (priority ascending, then compose order, then id) rather than
> undefined map iteration, making multi-effect composition on the same property deterministic.

## Overview

The FX system provides:
- Global tempo synchronization via Master Clock (BPM-based)
- Continuous effects (sine waves, pulses, colour cycles, position sweeps)
- Type-safe effect targeting via fixture traits
- Multiple blend modes for effect application
- Real-time control via REST and WebSocket APIs

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│                          FxEngine                                   │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                     MasterClock                             │   │
│   │              (BPM, ticks, beat events)                      │   │
│   └───────────────────────────┬─────────────────────────────────┘   │
│                               │ onTick → wake channel               │
│                               ▼                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │                  Active Effects Map                         │   │
│   │   effectId → FxInstance                                     │   │
│   └───────────────────────────┬─────────────────────────────────┘   │
│                               │                                     │
│                               ▼                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │              FX Processing Loop (per tick)                  │   │
│   │                                                             │   │
│   │   0. Reset FX-controlled properties to neutral              │   │
│   │   For each active effect:                                   │   │
│   │     1. Calculate phase based on clock + effect timing       │   │
│   │     2. Effect.calculate(phase, context) → output value      │   │
│   │     3. Apply blend mode (override/additive/multiply/max)    │   │
│   │     4. Write to ControllerTransaction                       │   │
│   └─────────────────────────────────────────────────────────────┘   │
│                               │                                     │
│                               ▼                                     │
│                    ControllerTransaction                            │
│                          ↓                                          │
│                    DmxController (ArtNet)                           │
└─────────────────────────────────────────────────────────────────────┘
```

## Speed Masters

Tempo lives in a per-show **`SpeedMasterBank`** of `MasterClock` instances — named tempo
buses that effects *subscribe to* rather than owning speeds. **Slot 0 is always master 1**,
the global tempo that script `setBpm`/`tapTempo` and a bare AI `set_bpm` mean, and that
every effect with no explicit master resolves to. (`set_bpm` also takes an optional
`speedMasterUuid`, so the AI surface can retune any master, not only the global one.)
The AI reads the bank back through `get_current_state`'s `speed_masters` section, which is
where a master created mid-conversation gets its uuid — `describe_rig`'s listing is a snapshot
of when it was called.

There used to be a second, unkeyed tempo surface alongside this one — WS
`setFxBpm`/`tapTempo`/`beatSync`/`requestBeatSync`, REST `/fx/clock/*`, and `bpm` /
`isClockRunning` on the `fxState` frame — all of it master-1-only, kept for clients that
predated the bank. It is all retired: `speedMasters.*` is the whole surface, and `fxState`
carries no tempo.

Masters are persisted per project (`speed_masters` table, portable in sync — the stored bpm
is the *starting* tempo; live changes write through with a 750 ms trailing debounce).

**A cue can set masters' tempo** (venue-visit plan S16, D20): a cue's `speedMasters` list is written
to the bank as `MANUAL` tempo writes on every GO into one of its entries and every loose press of it,
never on a reload's re-GO (`fx/CueTempo.kt`, `docs/cues-engineering.md` §"Speed masters on a cue").
A tap or a typed tempo afterwards wins until the next cue that sets that master; a follower is
skipped, as it refuses every tempo write. A master a cue names is a use for the delete guard
(`cueTempoCount`).

### Usage routing and follow (time signature)

Two nullable per-master settings, added for the busk view (see
`docs/plans/completed/busking-view-plan.md` §2, decisions D1–D7):

- **`usage`** (`intensity` / `colour` / `position` / `beam` — the FX library's own family
  vocabulary, stored as `speed_masters.family`, pinned by `GlossaryVocabularyTest`) names the master an *apply-time
  caller* should stamp into an effect created with no explicit master. This is D1: routing is
  visible stamping at apply time, never engine resolution — a null `speedMasterUuid` keeps
  meaning master 1, everywhere, forever. `controls` is deliberately not routable; its effects
  land on master 1. Nor is `palette`, the Steps family's: a Steps FX is stamped by the family it
  steps instead (`stepsFxCategory`) — Slot Steps by Beam's, Palette Steps by its palettes'. One master per usage per project,
  enforced by `validateSpeedMasterSettings` (a friendly 409, not a DB index).
- **`follow_num` / `follow_den` / `follow_target_uuid`** make the master a *follower* of
  another master (null target = master 1). Following is **phase lock, not just tempo**: a
  follower has no timer of its own. `MasterClock.adoptDriven` cancels it, and every leader tick
  maps onto the follower's counter as

  ```
  followerTick = 1 + floor((leaderTick - 1) × num / den)
  ```

  so a ½ follower's beat boundaries land on every second leader beat and *stay* there, which is
  what an operator means by "follow". (The `-1`/`+1` is the clocks' 1-based numbering, where a
  beat lands on the first tick of the beat; without it a follower beats one leader tick early —
  enough to pass a rate test and obvious on two beat lamps.) The cascade runs from inside the
  leader's own tick callback, depth-first, so a follower's beat carries the leader tick's
  timestamp exactly. Linking snaps the follower's effects once; unlinking does not
  (`start(resetCounter = false)` hands the timer back where the leader left it).

  The snap is not incidental — `adoptDriven` **zeroes the counter on the transition**, and it
  has to. A master that has been free-running carries a counter of its own, the tick its new
  leader maps to is usually *below* it (a ½ follower's target is half the leader's count, and
  two clocks started together give a mapped tick roughly half the follower's), and `driveTo` is
  monotonic. Without the reset the follower would refuse every drive until its leader overtook
  it — frozen for as long as the desk had been up, silently. Zeroing lets the leader's next tick
  assign the mapped value outright.

  **Changing the ratio (or the leader) on a *live* follower snaps it too** — `load` compares the
  row's follow spec against the one the entry held and passes `adoptDriven(snap = true)` when it
  moved. The same arithmetic bites a second time otherwise: a follower driven at 2× carries twice
  its leader's count, so re-pointing it at ½ maps it to a quarter of where it stands and the
  monotonic guard freezes it until the leader's counter quadruples. That failure is silent in
  the worst way — the tempo readout keeps showing the derived bpm, because that comes from the
  *bpm*, not the clock, so the rail looks correct while the beat lamp never lights again and
  every effect on the master stands still. Ratios above the previous one hide it (they map ahead
  of the counter and drive on the first tick), which is why 2× behaved and ½, ⅓ and ¼ did not.
  A reload that leaves the mapping alone — a rename, a usage retag, a stored-tempo edit — must
  *not* snap: the counter would land on the same mapped value, but a zeroed counter reads as "no
  previous beat" and re-fires the beat already in progress, double-flashing every beat lamp on
  the desk for a text edit.

  Chains are allowed — M3 → M2 → M1 — and nested floors compose (`floor(floor(t/2)/2) ==
  floor(t/4)`), so a grandchild is aligned to the root, not merely to its parent.
  `validateSpeedMasterSettings` walks the chain at the write boundary and refuses a loop
  (`SPEED_MASTER_FOLLOW_CYCLE`) or a target that names no master
  (`SPEED_MASTER_FOLLOW_TARGET_UNKNOWN`); master 1 is refused a ratio outright
  (`SPEED_MASTER_CANNOT_FOLLOW`). Deleting a master something follows is a
  `SPEED_MASTER_IN_USE` 409 carrying `followerNames`, overridable with `?force=true` — and the
  forced path **unlinks those followers** (`unlinkFollowersOf`) rather than leaving them naming
  a master that is gone. That is what keeps the two reports of a follow agreeing: REST serves
  the stored columns while the bank serves its resolved graph, so a row left dangling would
  advertise a ratio for a master the desk is running manually — hiding TAP on the manage page
  and 400ing a ratio-chip press. One level, not transitively: force-deleting the middle of
  M3 → M2 → M1 leaves M3 manual rather than inventing a link to M1 at a ratio that no longer
  means what the operator set.

  Degradation at `load` remains the backstop for the rows that route never sees — an import, a
  hand-edited database: a dangling target, a self-follow, and every member of a cycle *plus
  anything following into one* become manual, because no timer exists anywhere upstream of them.
  For the same reason the target check is gated on the write actually touching the link
  (`checkFollowTarget`), the carve-out an untouched `usage` already has: an imported row can
  store a leader this project doesn't have, and judging a later rename on it would lock the
  operator out of every edit except the unlink.

  The **reported** bpm is still swept: `sweepFollowersLocked` walks `bindings.driveOrder`
  (leaders first, so a chain resolves in one pass) after `load` and after any tempo write, and
  writes `leader.bpm × num / den` through `MasterClock.setDerivedBpm`. That deliberately does
  *not* clamp to 20–300 the way a typed tempo does: the range guards the tick timer, a driven
  clock has no timer, and clamping 2× of 200 to 300 would make the strip's readout, the
  persisted row and `rateScales` all disagree with the master's real rate. The range comes back
  the moment the clock does: `applyClockRolesLocked` re-clamps a master on its way *out* of
  being driven — and emits the correction, so the row stops storing a tempo the write boundary
  would refuse — because it is about to run a timer at that figure again. `setBpm`/`tap` on a
  follower return `TempoWriteOutcome.RefusedFollower` (naming the leader to retune instead)
  rather than silently unlinking (D5) — a 400 (`SPEED_MASTER_FOLLOWER`) on REST, a
  `speedMasters.error` frame on WS, a distinct tool error on the AI surface.
Effects reference a master by **uuid** (`FxInstance.speedMasterUuid`, null → master 1) and
the engine binds that to a runtime slot index at add/update time, re-binding when the
bank's membership changes; a deleted master's effects degrade to master 1, never stop.
Because an FX instance targets specific properties, per-instance assignment already gives
"different speeds for different properties". One FX driving several attributes is
`FU-SPEED-PER-ATTRIBUTE`.

### One processing pass, N timebases

No clock drives effect processing directly: every clock tick nudges the bank's **CONFLATED
wake channel**, and the engine runs one pass per wake-up over one coherent
`SpeedMasterBank.Frame` of per-master ticks. Ticks landing mid-pass collapse into a single
follow-up, so the pass rate is bounded by the fastest master, and one pass means one
`ControllerTransaction` — effects on different masters can never fight over a property
within a frame. This works because **phase is a pure function of the tick counter**
(`MasterClock.phaseForDivision(tickNumber, division)` — a companion function; BPM never
enters the phase math, tempo is purely tick emission rate).

### Key Properties (per clock)

| Property | Type | Description |
|----------|------|-------------|
| `bpm` | `StateFlow<Double>` | Current tempo (20-300 BPM) |
| `isRunning` | `StateFlow<Boolean>` | Whether clock is active |
| `tickFlow` | `SharedFlow<ClockTick>` | Emits 24 times per beat; production drives off `onTick`/the wake channel, so only tests collect this flow |
| `currentTick` | `ClockTick` (`@Volatile`) | Most recent tick, sampled per pass by the bank |
| `onTick` / `onBeat` | `(() -> Unit)?` / `((BeatEvent) -> Unit)?` | Callbacks the bank wires per clock: the wake nudge and the beat fan-out. There was a `beatFlow` alongside `tickFlow`; its only consumer was the retired `beatSync` push |

### Clock Resolution

Like MIDI clock, each clock emits 24 ticks per beat. This provides:
- Smooth effect interpolation
- Sub-beat timing resolution

The tick timer keeps a **fractional deadline** rather than delaying a truncated interval:
the old `toLong()` truncation ran a 120 BPM show at ~125 BPM invisibly, and with several
masters it became *relative* drift (120 vs 60 BPM truncated to a 2.05:1 ratio). Long-run
inter-master ratios are now exact; `SpeedMasterBankTest` pins this. A tempo change never
resets the tick counter, so phase stays continuous across it.

### Beat Divisions

Effects reference timing via `BeatDivision` constants:

| Constant | Value | Description |
|----------|-------|-------------|
| `THIRTY_SECOND` | 0.125 | Eighth of a beat |
| `SIXTEENTH` | 0.25 | Quarter beat |
| `TRIPLET` | 0.333 | One-third beat |
| `EIGHTH` | 0.5 | Half beat |
| `QUARTER` | 1.0 | One beat |
| `HALF` | 2.0 | Two beats |
| `WHOLE` | 4.0 | Four beats (one bar) |
| `ONE_BAR` | 4.0 | Four beats — a new effect's default (venue-visit plan D16) |
| `TWO_BARS` | 8.0 | Eight beats |

Everything a person reads says the division in **beats a cycle** — `½ beat`, `1 beat`, `2 beats`,
`1 bar`, `2 bars`, `4 bars` — from one table (`SPEED_STEPS` in the frontend's `fxConstants.ts`), the
editor's segment and every chip alike; an effect on Seconds reads `4 s`. The note values the chips
once used (`1/4` for one beat) are gone (`FU-FE-FX-SPEED-SPELLING`, settled by S15).

## Effect Types

Since the terminology plan's session 3 each id is its name without the spaces (D10–D13): `Ramp`,
`DimmerStrobe`, `ColourWave` and `ZoomWave` were `Triangle`, `Strobe`, `ColourPulse` and `ZoomBreathe`,
and `Breathe` is gone, a stored one now Sine Wave a quarter-cycle on. `sync/LegacyNames.kt` reads the
old names (§"Old names" in `sync-engineering.md`).

### Intensity Effects (`FxOutput.Slider`)

Effects that produce single 0-255 values for dimmer/slider properties.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `SineWave` | Smooth oscillation | `min`, `max` |
| `RampUp` | Sawtooth up | `min`, `max`, `curve` |
| `RampDown` | Sawtooth down | `min`, `max`, `curve` |
| `Ramp` | Up then down | `min`, `max`, `curve` |
| `Pulse` | Attack-hold-release | `min`, `max`, `attackRatio`, `holdRatio` |
| `SquareWave` | On/off alternation | `min`, `max`, `dutyCycle` |
| `DimmerStrobe` | Quick flash on the dimmer | `offValue`, `onValue`, `onRatio` |
| `Flicker` | Random variation | `min`, `max` |
| `LightningStrike` | A flash that decays | `maxBrightness`, `minBrightness` |

### Colour Effects (`FxOutput.Colour`)

Effects that produce RGB color values.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `ColourCycle` | Step through a list of literal colours | `colours`, `fadeRatio` |
| `RainbowCycle` | Hue rotation | `saturation`, `brightness` |
| `ColourStrobe` | Flash colour | `onColor`, `offColor`, `onRatio` |
| `ColourWave` | Sine between two | `colorA`, `colorB` |
| `ColourFade` | Linear fade, bouncing by default | `fromColor`, `toColor` |
| `ColourFlicker` | Random variation | `baseColor`, `variation` |

Colour Cycle takes **literal colours only** (terminology plan D14): a `colourList` parameter — its
`colours` — refuses a palette reference at every write boundary (`colourListPaletteProblem`: the add
routes, `PUT /fx/{id}` and its frame, a Look's, a cue's and a palette's effects, the MCP tools), and
`TypedParams.colourList` drops one that reaches it anyway. Palette Steps is the FX that steps through
palettes, each landing on each head for itself. The other colour FX keep a palette reference in their
`colour` parameters, one colour for every head (D30).

### Position Effects (`FxOutput.Position`)

Effects that produce pan/tilt values for moving heads.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `Circle` | Circular movement | `panCenter`, `tiltCenter`, `panRadius`, `tiltRadius` |
| `Figure8` | Figure-8 pattern | `panCenter`, `tiltCenter`, `panRadius`, `tiltRadius` |
| `Sweep` | Linear movement, bouncing by default | `startPan`, `startTilt`, `endPan`, `endTilt`, `curve` |
| `PanSweep` | Horizontal sweep | `startPan`, `endPan`, `tilt` |
| `TiltSweep` | Vertical sweep | `startTilt`, `endTilt`, `pan` |
| `RandomPosition` | Random movement | `panCenter`, `tiltCenter`, `panRange`, `tiltRange` |

### Beam Effects (`FxOutput.Slider`)

Category `beam` (effects plan D6, session 6): each drives **one** beam property and declares only it
in `compatibleProperties`, so the picker offers it only on a head that has that property, under its
**Beam** family. Level effects are not offered on beam sliders (`FU-FX-BEAM-ANY-SLIDER`).

| Effect | Property | Description | Parameters |
|--------|----------|-------------|------------|
| `ZoomWave` | `zoom` | Sine between two zooms | `min`, `max` |
| `IrisPulse` | `iris` | Pulse's attack-hold-release | `min`, `max`, `attackRatio`, `holdRatio`, `curve` |
| `FrostPulse` | `frost` | Pulse's attack-hold-release | `min`, `max`, `attackRatio`, `holdRatio`, `curve` |
| `FocusPull` | `focus` | Sweep between two focus levels, bouncing by default | `start`, `end`, `curve` |

The values are raw DMX, as every level effect's are. A stepped zoom (the Robe's) is a setting, so
Zoom Wave on it writes the byte and lands on whichever step that is. To step one by name, use
Slot Steps on it (`ZOOM_18, ZOOM_26`), or Palette Steps over Beam palettes whose zoom is a percent,
which snaps to the step nearest that share of its angles, as a palette does (below).

A stored beam effect that names no property — an effect palette's, or any other stored without one — lands
on the one property its registration declares (`EffectSpawner`'s `beam` arm), on a group only where
every member has it, as the dimmer arm asks of a group's dimmers; so a Beam palette's effect must
name one property, its own `propertyName` or else the one its registration declares, never a
sentinel (`validateTemplateEffect`) — Slot Steps names the setting it steps. A group or selection is resolved
through its first head that has the property (`referenceFixtureFor(target, property)`), so a
selection that starts on a head without zoom still drives the zooms after it. Record files an
effect on `zoom`, `iris`, `frost` or `focus` as category `beam` (`categoryFromPropertyName`; a step
effect by `recordedEffectCategory`, below), so a recorded Look's families include Beam. A speed master may take `usage: beam`, and a Beam effect
palette holds one of them (`TEMPLATE_EFFECT_FAMILIES`).

### Step Effects (`FxOutput.Steps`)

Effects plan session 7 (D8, §3.6): two built-ins step through **named things**, each resolved for the
head in hand, which no other effect output can say. Both emit `FxOutput.Steps(from, to, blend,
steps)` — two step indices, how far between them, and the `StepList` that says what a step is — and
declare `outputType: STEPS`, which every target accepts (`FxTarget.accepts`; the routes'
`requireOutputTypeMatch`, `FxInstance`'s warn and a palette press's skip all ask it).

| Effect | Category | Steps through | Parameters |
|--------|----------|---------------|------------|
| `PaletteSteps` | its palettes' family | generic value palettes, `pal:{uuid}` each | `palettes` (`paletteList`), `fadeRatio` |
| `SlotSteps` | `beam` | one setting's option names | `slots` (`slotList`) |

The two are one family in the library, *Steps* (terminology plan D23): both register under `palette`
(`STEPS_CATEGORY`), which names no attribute family, and are offered and stored in the family of what
they step — the Category column — through `stepsFxCategory` and, on the client, `drivenCategoryOf`.

`stepsAt(phase, steps, fadeRatio)` is the timing, Colour Cycle's: equal segments, each step held for
`1 − fadeRatio` of its own and crossing to the next over the rest, the last crossing back to the
first. Size, direction and the spread apply as to any effect, so a Backward or Bounce phase runs the
steps back.

**The target resolves a step per head** (`StepList.valueFor`, `colourFor` on the colour target):
`SliderTarget`, `ColourTarget` and `PositionTarget` blend the two steps in proportion (a byte, an
`ExtendedColour`, pan and tilt each), and `SettingTarget` snaps to the nearer — a slot has no halfway
(Slot Steps' level read off the setting the target already holds, `SlotStepList.levelFor`). A head
that cannot take a step holds what is underneath for it: nothing is written where it shows only that
step, and part-way between a step it can take and one it cannot it crosses from what is underneath
(`FxOutput.Steps.blendOn`, which reads what is underneath only then). The blend mode and Size apply
after, as to any output.

- **Palette Steps** (`PaletteStepList`): each palette's rows, as intents, resolved on the head through
  `TemplateResolver` — the cook's, *Apply as values*' and the resolves-to panel's one answer — for the
  target's property: the colour family (the hex mixed for that head's emitters, then any explicit
  emitter row) on the colour target, the position row on the position target, else the row naming
  the property. So a centre-relative (`pos:`) and a degree (`deg:`) position palette each land on the
  head's own pan and tilt, a beam role on its own range (a stepped zoom snapping, `steppedZoomSlot`),
  and an RGBW head and an RGB one each get their own mix. **A colour step is a layer's colour**: a head
  missing an emitter the palette names takes none of it (`unmetColourRequirement`, the cook's rule),
  and what a palette does not name stays as it is underneath (`StepColour.over`) — a UV-only palette
  moves the UV and leaves the RGB, white and amber alone. A head is resolved once and cached by its
  key and property, re-resolved when the model under that key changes: the engine builds fresh
  fixture objects every tick, so an object is no key. Fitted media is not read, since it decides only
  a **wheel** slot and a wheel head is no colour target's member. The palettes are read through the effect's colour source, which is a
  `TemplateSource` — the one `createEffectWithTemplates` passes — so `TypedParams.paletteSteps` needs
  no new factory argument, the references are pre-warmed by the same `templateColourRefsIn` scan, and
  a palette's edit moves the running steps through the colour caches' version. An entry that is not a
  generic value palette is dropped, as a colour list drops one.
- **Slot Steps** (`SlotStepList`): each name looked up in the head's own options of the setting the
  effect drives, ignoring case, spaces, underscores and dashes (`normaliseSlotName`), so *Gobo 3* is
  `GOBO_3` on every head, at each head's own level. A head without the slot holds what is underneath on
  that step. Its levels are cached per option set (a setting's options are its model's enum), so the
  cache holds one entry per wheel model met. It declares only the `setting` sentinel: the client turns
  it into the setting chosen.

**What it is filed under.** Palette Steps registers under `category: palette`, which names no
family on purpose: a stored one carries the family of its palettes (`dimmer`, `colour`, `position` or
`beam`), and `paletteStepsShape` is the one statement of that shape — every palette in one family,
and the property it drives, the colour for colour, else the first in the palette vocabulary every
palette names (zoom, where every Beam palette names zoom and only some frost). A stored Palette Steps
that names no property (an effect palette's, or any other stored without one) lands on that property
(`EffectSpawner.drivenPropertyOf`); Slot Steps names its setting as the row's `propertyName`. An effect
palette's write boundary (`validateTemplateEffect`) holds both: Palette Steps needs two generic value
palettes or more, all readable, and its category must be their family's; Slot Steps needs a
`propertyName` and two slots or more. Its master is stamped by that family's usage (the picker, the
palette editor and the MCP tools' `usageMasterFor`), so `palette` is no usage
(`GlossaryVocabularyTest`). Record files a running step effect by `recordedEffectCategory`:
Slot Steps as `beam`, Palette Steps as its palettes' family read off the property it drives (a strobe
in an Intensity palette is `dimmer`, not `controls`).

**Neither can be forked.** `/fx/definitions` refuses a `STEPS` output (`validateCompatibleProperties`):
a step list names this desk's palettes and settings, resolved per head by the targets, and a
definition syncs to desks that predate the output type. The FX Library draws Fork disabled on both
with that reason (`forkRefusal`).

**On the client** (`lib/stepEffects.ts`, which never resolves): the picker lists Slot Steps under
Beam on a setting row and starts it on the setting's first two slots past its open one; it offers
Palette Steps in each family that has two generic value palettes or more naming a property the
target has (`paletteStepsOffers`), started on all of them, in name order; Slot Steps from anywhere
but a setting row starts on a gobo wheel where the head has one, else its first setting. The editor's
two parameter types are `paletteList` (`FxPaletteListPicker`: palettes in step order, by reference —
an effect palette's own family only, else one family once one is chosen) and `slotList`
(`FxSlotListPicker`: the target setting's options, a stored name the head lacks drawn dashed; on an
effect palette, which names its setting from the rig, every patched head's options of that name
(`rigSettingUnion`) and typed names besides, a dashed one on no patched head). *Save as palette…* on a
running step effect banks it as `stepEffectPaletteShape` says — its palettes' family, or Beam naming
its setting.

### Stateful Effects (`StatefulEffect`)

Stateful effects maintain internal state that evolves over time, rather than being pure
functions of phase. They receive tick-level timing (`ClockTick` + `deltaMs`) instead of
a 0-1 phase value.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `CandleFlicker` | Organic candle/fire flicker via random walk | `baseLevel`, `min`, `max`, `smoothing` |

Stateful effects implement the `StatefulEffect` interface:
- `initialize()` — called once when added to the engine, resets state
- `calculateStateful(tick, deltaMs, context)` — called each tick instead of `calculate()`
- `calculate(phase, context)` — fallback that returns a neutral value

The FxEngine detects `StatefulEffect` instances and routes to `calculateStateful()`
automatically in all 4 processing paths (fixture, multi-element, group, flat element).

### One FX, one attribute

An `FxInstance` drives one `FxTarget`, so an FX drives one attribute. The composite mode, which
computed several outputs from one phase and applied the one its `outputType` named, is gone
(terminology plan D27): its one built-in, `LightningStrike`, computed a colour it never applied, and
is now a standard Intensity FX with the same output. One FX driving several attributes is
`FU-SPEED-PER-ATTRIBUTE`.

A mismatched `FxOutput` is **silently dropped** by `FxTarget` (`if (output !is FxOutput.Slider)
return` and friends), so an FX's `compatibleProperties` must list only properties of its output type.

## `compatibleProperties` must match the output type

That silent drop is the general rule, and it bit every effect in
the `position` category (sweep item A11). All seven declared `compatibleProperties: [pan, tilt]`
against `outputType: POSITION`; `pan` and `tilt` are real `@FixtureProperty` sliders, so every
frontend picker chose one, posted `propertyName: "pan"`, and got a `SliderTarget` that discarded
the `FxOutput.Position`. No light, no error, no log, for the desk's whole life.

The contract, and the three things that now hold authors to it:

- **`FxTarget.acceptedOutputType`** — the one `FxOutputType` a target can apply. A **`POSITION`
  effect must advertise `position`**, the synthetic pan/tilt compound, and never an axis by name;
  a `COLOUR` effect must advertise `rgbColour` (or an alias); a `SLIDER` effect must advertise
  anything *but* `position` or the colour bundle.
- **`FxTargetFactory`** (`fx/FxTargetFactory.kt`) is the single place a property *name* becomes a
  target. It replaced four near-identical copies in the route layer, all of which had A11.
  Given the effect's output type it also coerces `pan`/`tilt` to a `PositionTarget` for a
  `POSITION` effect — a repair for Look and cue rows *already recorded* with `propertyName: "pan"`,
  not a licence for dishonest metadata. A `SLIDER` effect on `pan` alone still resolves to
  `SliderTarget("pan")`, because that is a legitimate thing to ask for.
- **Reported, not swallowed.** `POST /fx/add`, `PUT /fx/{id}` and `POST /groups/{name}/fx` reject a
  mismatch with 400 (`requireOutputTypeMatch`), and `POST`/`PUT /fx/definitions` rejects a
  user definition whose `compatibleProperties` its `outputType` cannot drive. Everywhere a throw
  would be worse than a dark light — cue and Look fire, Include, scripts, MIDI — `FxInstance`'s
  `init` logs a warn instead.

`FxRegistrationTargetCompatibilityTest` holds every built-in to this, resolving each declared
property through `FxTargetFactory` *without* the output-type hint, so re-adding `[pan, tilt]` goes
red. It parses frontmatter only and compiles nothing.

### Definitions heal rather than lock

`compatibleProperties` is on **no** editing surface, and the FX edit sheet sends only
`{id, name, script}`. So validating the merged declaration on `PUT` — request field, else the
stored one — would have permanently bricked every position definition already saved with
`[pan, tilt]`: a script-only edit re-checks a stored list the operator cannot reach, 400s, and has
no in-app remedy. `normaliseCompatibleProperties` runs first and rewrites `pan`/`tilt` → `position`
for a POSITION definition, and the `PUT` writes the normalised list back even when the request
didn't supply the field, so the first save heals the row. That one rewrite is the only thing done
silently, because it has no reading under which the author meant it; everything else is rejected
out loud.

### The frontend change A11 didn't predict

A11 said neither fix needed a frontend change. True of the FX-*add* path — `AddEditFxSheet`,
`useBuskingState` and `ConfigureEffectSheet` read the property list off the library and needed
nothing. Not true of the *authoring* path: `FxLibrary.tsx`'s new-definition form derived
`compatibleProperties` from the chosen **category** while `outputType` is an independent select
defaulting to `SLIDER`. That let Category = Position + the default output type write a list the
effect could never drive, and the new 400 would have fired on the desk's own create flow, naming a
field the form doesn't expose. It now derives from `outputType`, which is what actually decides the
answer.

## Effect Registry

All effects (built-in and user-defined) are registered in a unified `FxRegistry`.

### Architecture

```
FxRegistry
├── register(EffectRegistration)     ← Built-in effects at startup
├── register(EffectRegistration)     ← User effects from fx_definitions table
├── createEffect(type, params, ...)  → Effect instance
├── getLibrary()                     → List<EffectTypeInfo> (for API)
├── getRegistration(type)            → EffectRegistration? (lookup)
└── unregister(id)                   ← Cleanup on delete
```

### EffectRegistration

Each registered effect provides:
- `id` — canonical name (e.g., "SineWave")
- `aliases` — alternative names for lookup (e.g., "sine_wave", "sine")
- `name` — human-readable display name
- `category` — UI category ("dimmer", "colour", "position", "beam", "palette", "controls"; `palette` is the Steps family's, Palette Steps' and Slot Steps')
- `outputType` — primary output type
- `effectMode` — `STANDARD` or `STATEFUL`
- `defaultDirection`, `defaultBeatDivision` — what a new instance runs at when its author names
  neither (below)
- `parameters` — schema for the API/UI
- `compatibleProperties` — which fixture properties this can target
- `source` — `BUILT_IN` or `USER`
- `script` — the calculate body (Kotlin script source)
- `factory` — creates an `Effect` from string parameters + the colour source (see below)

Lookup is case-insensitive with spaces and underscores stripped.

## FX Definitions

### Data Model

**Built-in effects** are stored as `.fx.kts` files in the repository under
`src/main/resources/fx/`. Each file contains YAML frontmatter for metadata followed
by the calculate body. They are loaded at startup, compiled, and registered in the
FxRegistry with `EffectSource.BUILT_IN`. They appear in the FX Library UI as
read-only, and their scripts serve as real working examples for users writing custom
effects.

**User effects** are stored in the `fx_definitions` database table and managed via
the FX Library UI. They are compiled and registered on save and on startup. The
table always requires a `project_id` since every user effect belongs to a project.

```
fx_definitions table (user effects only):
├── effect_id         — canonical ID (e.g., "MyCustomPulse")
├── name              — display name (e.g., "My Custom Pulse")
├── category          — "dimmer", "colour", "position", "controls" ("beam" and "palette" are
│                       the built-ins' own; the New effect sheet offers neither)
├── output_type       — SLIDER, COLOUR, or POSITION (never STEPS — refused, see Step Effects)
├── effect_mode       — STANDARD or STATEFUL
├── parameters        — JSON schema [{name, type, defaultValue, description}]
├── compatible_attributes — JSON array of attribute names
├── script            — the calculate() body only
├── project_id        — owning project (required)
├── default_timing    — CYCLE or HEAD
└── timing_source     — BEAT or WALL_CLOCK
```

### Script Model

Scripts contain **only the calculation logic** — the body of `calculate()` or
`calculateStateful()`. All metadata (name, category,
parameters, etc.) is stored in the database/registration and managed via the UI.

**Parameter access** uses a typed accessor object (`TypedParams`) that pre-parses
string parameters according to the schema:

```kotlin
// TypedParams methods:
params.ubyte("min")          → UByte (default from schema)
params.int("count")          → Int
params.double("fadeRatio")   → Double
params.boolean("enabled")    → Boolean
params.colour("baseColor")   → ExtendedColour (resolves a palette reference, "pal:{uuid}")
params.colourList("colours") → List<ExtendedColour> (literal colours only; a palette is dropped)
params.easingCurve("curve")  → EasingCurve
params.string("name")        → String
```

### Built-in Effect File Format (.fx.kts)

Built-in effects are stored as `.fx.kts` files under `src/main/resources/fx/`,
organized by category:

```
src/main/resources/fx/
├── dimmer/
│   ├── SineWave.fx.kts
│   ├── RampUp.fx.kts
│   ├── CandleFlicker.fx.kts    (STATEFUL)
│   └── ...
├── colour/
│   ├── ColourCycle.fx.kts
│   ├── RainbowCycle.fx.kts
│   └── ...
├── position/
│   ├── Circle.fx.kts
│   └── ...
├── beam/
│   ├── ZoomWave.fx.kts
│   └── ...
└── steps/
    ├── PaletteSteps.fx.kts
    └── SlotSteps.fx.kts
```

Each file uses YAML frontmatter in a block comment, followed by the script body:

```kotlin
/*---
id: SineWave
name: Sine Wave
category: dimmer
outputType: SLIDER
effectMode: STANDARD
defaultStepTiming: false
compatibleProperties: [dimmer, uv]
parameters:
  - name: min
    type: ubyte
    default: "0"
    description: Minimum value
  - name: max
    type: ubyte
    default: "255"
    description: Maximum value
---*/

val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (Math.sin(phase * 2 * Math.PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
```

At startup, `FxFileLoader` scans the resource directory, parses each file's
frontmatter into metadata and script body, compiles the script, and registers
the effect in the FxRegistry with `source = BUILT_IN`.

Two optional frontmatter keys say what a **new** instance runs at, read as `defaultStepTiming` is:
`defaultDirection` (`FORWARD`, `BACKWARD` or `BOUNCE`; `FxInstance` starts its dynamics on it) and
`defaultBeatDivision` (beats a cycle, one bar when absent). Colour Fade, Focus Pull and the sweeps
declare `BOUNCE` at `2.0` (terminology plan D24): they once bounced by a `pingPong` parameter over a
one-bar cycle, so a new one looks as it did. The library answers both (`EffectTypeInfo`), and every
door that makes an FX without naming them takes them: the add routes, the programmer's bare press,
the MCP tools, the picker and the palette editor. A swap to another type in the picker or the
palette editor takes the new type's where the FX still stood at the old type's
(`swappedTiming`). A user FX definition has neither, so runs Forward at one bar.

### Three Script Base Classes

Each `effectMode` has a dedicated base class with focused provided properties:

#### FxCalcScript (STANDARD)

For pure effects that are a function of phase:

```kotlin
// Provided: phase (Double), context (EffectContext), params (TypedParams)
// Return: FxOutput (last expression)

val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (Math.sin(phase * 2 * Math.PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
```

#### FxStatefulCalcScript (STATEFUL)

For effects that maintain state across ticks (e.g., CandleFlicker):

```kotlin
// Provided: tick (ClockTick), deltaMs (Long), context (EffectContext),
//           params (TypedParams), state (MutableMap<String, Any>)
// Return: FxOutput (last expression)

val baseLevel = params.ubyte("baseLevel").toDouble()
val smoothing = params.double("smoothing")
val currentLevel = state.getOrPut("level") { baseLevel } as Double
val target = state.getOrPut("target") { baseLevel } as Double

// Update target periodically
val ticksSince = (state.getOrPut("ticks") { 0 } as Int) + 1
state["ticks"] = ticksSince
if (ticksSince > 3) {
    state["target"] = baseLevel + (Math.random() - 0.5) * 80
    state["ticks"] = 0
}

val newLevel = currentLevel + (target - currentLevel) * (1.0 - smoothing)
state["level"] = newLevel
FxOutput.Slider(newLevel.toInt().coerceIn(0, 255).toUByte())
```

### ScriptEffectAdapter

`ScriptEffectAdapter` bridges compiled scripts to the `Effect`/`StatefulEffect`
interfaces. It:

1. Compiles the script body using the appropriate base class
2. Caches compiled results by content hash
3. On `calculate()`: creates `TypedParams` from raw params + schema, evaluates script
4. For stateful: maintains a `MutableMap<String, Any>` per instance

### FX Definitions REST API

```
GET    /api/rest/fx/library                → List all available effects
GET    /api/rest/fx/definitions/{id}       → Full definition including script
POST   /api/rest/fx/definitions            → Create new definition
PUT    /api/rest/fx/definitions/{id}       → Update (recompiles on save)
DELETE /api/rest/fx/definitions/{id}       → Delete (non-builtin only)
POST   /api/rest/fx/definitions/{id}/compile → Compile check
POST   /api/rest/fx/definitions/{id}/run   → Compile and register (live test)
```

## Blend Modes

How effect output combines with fixture's base value:

| Mode | Description | Use Case |
|------|-------------|----------|
| `OVERRIDE` | Effect replaces value | Primary effects |
| `ADDITIVE` | Effect added to value | Accent layers |
| `MULTIPLY` | Effect multiplies value | Proportional dimming |
| `MAX` | Maximum of both | Ensure minimums |
| `MIN` | Minimum of both | Limit maximums |

### Size, direction and N-shot (effects plan session 1)

Three fields of every running effect, edited in place (they are `FxDynamics` fields, so an edit
keeps the phase), stored on the four effect homes — a live instance, `look_fx`,
`cue_ad_hoc_fx` and an effect palette (`palette_fx`) — and carried by `LookEffectSpec`,
`StoredEffect` and `LookEffectSettings` between them, so Record and Include keep them. They sync
from `formatVersion` 30 (`docs/sync-engineering.md` §"Version 30"). The desk owns every rule (the
effects plan's P2); the editor only writes the three fields.

- **Size** (D2), 0–1: how much of the effect reaches the stage over what is underneath. It is a
  **weight applied after the blend**, per channel (P3): `out = before + (blended − before) × w`,
  where `before` is what the property already holds this pass — the reset baseline and any
  lower-priority effect already applied — and `w = size × envelope × stackSize`
  (`FxInstance.weight`, `FxEngine.weightOf`), clamped to 0–1. A Replace dimmer at 50 % therefore sits halfway between the level underneath and the
  effect, never at half the effect's raw value; an Around movement (`ADDITIVE` about 128) has its
  offset scaled, so a circle at 50 % has half its radius about the position underneath; a colour is
  weighed channel by channel, white, amber and UV included. `FxTarget.applyValueToFixture` takes the
  weight and `weigh` is the one formula. A **setting** is discrete — a slot has no halfway — so it
  takes the effect's value from half weight up and holds what is underneath below (`weighDiscrete`).
  At weight 0 nothing is written. A size outside 0–1 is refused at every write boundary
  (`EffectSpecCoercion.Strict.sizeProblem`); a stored one is clamped with a warn. Size stops at
  100 % (`FU-FX-AMPLITUDE`).
- **The envelope** is the runtime factor of the weight a fade moves (effects plan §3.5, session 4);
  a stack's effect size (below) multiplies in beside it. It **does not scale the effect's raw output**:
  `FxOutput.scaled()` is gone, and with it the scaling that dropped an `OVERRIDE` effect to 0. It is
  `FxInstance.envelope`, an immutable `EffectEnvelope` (`fx/EffectEnvelope.kt`) — a level, or a ramp
  from one level to another over a duration on an `EasingCurve`, read on the clock wherever the weight
  is read (`intensityMultiplier` is its level now; setting that holds a level). A fade is one value,
  so it needs no loop of its own, an edit's swap (`updateEffect`) and a split's copies carry it by
  carrying the object, and every change is one compare-and-set (`FxInstance.updateEnvelope`). What
  moves it:
  - **a cue's GO** (D1): the hold registry ramps an arriving cue's new effects `0 → 1` and a leaving
    cue's `→ 0` over the cue's fade and curve, and an effect the two cues share runs on untouched
    (P4; never a paused instance or an N-shot, which the incoming cue starts afresh) — a Look or
    palette effect by its stored row, an ad-hoc effect by its run key, edited in place at the GO
    when the incoming row's settings differ (stack-timeline plan D7, D8) —
    `docs/cue-stacks-engineering.md` §"Holds" and §"Crossfade";
  - **the programmer** (D13): a layer added or removed (a press with `fadeMs`, `programmer.removeLayer`,
    a cue Include's layers), `programmer.clearTarget`'s local effects and Clear's band, over the
    programmer fade — split by what is under each (`FxEngine.releaseEffects`, Chris's call): an
    effect over a programmer value the same gesture moves lands or goes at once, so that value ramps,
    since a key an effect covers never fades and the value under a shrinking effect would snap
    (`FU-FX-VALUE-FADE-UNDER-EFFECT`). Include's own effects land at full;
  - **Blind**: leaving it grows the band from nothing over the Blind fade, by the same split (entering
    holds it at once).

  An instance let go on a fade is **leaving** (`FxInstance.leaving`): no cue, layer, Release, Record,
  Include or reload carry counts it any more, and the 50 Hz ticker-woken passes remove it once its ramp
  reaches 0 (`FxEngine.retireFinishedLeaving`, run before the pass's early returns, so a paused
  effect ends on time). A fade of 0 removes it at once. A leaving instance a cue takes back mid-fade
  (`FxEngine.reclaimEffects`) is held where it stood and faded on from there; one the engine has
  already retired (`EffectEnvelope.RETIRED`) cannot be taken back, which is what keeps the two from
  racing. An edit's swap shares the envelope cell with the instance it replaces, as it shares the
  dynamics, so a fade or a retirement racing the swap reaches both; a retired instance an edit swapped
  in is removed by the next sweep. The envelope is never stored and never recorded: Record takes the
  row's own size.
- **A stack's effect size** (D10, session 5): a cue stack's runtime weight on its effects, 0–1,
  100 % at load — the third factor of `w`. `StackEffectSizes` (`fx/StackEffectSizes.kt`) is owned by
  the show's `CueStackManager` and handed to the engine (`FxEngine.stackEffectSizes`), so a project
  switch starts every stack at full; a same-project reload carries it (`ShowCarry.stackEffectSizes`,
  restored before the reload's GOs, a stack the reload removed dropped) and a stack delete forgets
  it. Nothing is stored. **Which stack weighs an instance** is the stack of its cue's **highest**
  hold, 100 % under a loose hold (§11 question 21): `weighingStackOf` reads it off the instance's
  `priority`, which `CueHolds` restamps whenever the top hold changes and retags when a cue adopts an
  instance or takes one back — never `FxInstance.cueStackId`, the first hold's tag. A **leaving**
  instance keeps its cue and priority, so it shrinks from the size of the stack that let it go rather
  than jumping to full as its fade starts. Programmer, manual and Include effects own no cue and are
  never weighed. The weight is read per pass (one map read, none at all while every stack is at
  full), and provenance is re-sent on every change of a size (`CueStackManager.setEffectSize`), since
  a discrete output — a setting, a step — flips on stage at a weight of one half. On the wire: `GET/PUT
  /projects/{id}/cue-stacks/{sid}/effect-size` `{size}` (400 `STACK_EFFECT_SIZE_INVALID` outside 0–1,
  404 for another project's stack, current project only, both roles — a runtime gesture on a venue),
  the `stackEffectSize.state` / `.set` frames (`docs/websocket-engineering.md` §"Stack effect size")
  and the MIDI `stackFxAmount` fader (`docs/midi-control-surface-engineering.md`). The frontend's
  *FX size* slider sits on the stack's row in the Show view and in the phone runner.
- **Direction** (D5): `FORWARD`, `BACKWARD` or `BOUNCE`, applied to each member's phase after the
  spread (§3.3 step 6, `EffectDirection.apply`): Backward is `1 − p`, kept inside `[0, 1)` so an
  effect indexing a list by `floor(phase × n)` never reads one past its end; Bounce is Forward on
  even cycles and Backward on odd ones, counted on the member's own clock (its offset taken off). It
  reaches every phase an effect reads; a **stateful** effect (`calculateStateful`) reads no phase,
  so direction does nothing to it. The built-ins keep their shapes (P6): Ramp Down stays. The
  sweeps', Colour Fade's and Focus Pull's `pingPong` went to Bounce in the terminology plan (D24), a
  stored one rewritten at half the cycle with its phase offset, spread and runs doubled, which plays
  the same (`LegacyFxOutputTest`).
- **N-shot** (D9): `nShot`, at least 1, null for forever. The count runs on the effect's clock from
  its first pass with a count — a master's tick (`FxInstance.nShotStartTick`) for a beat effect, the
  scaled accumulator (`nShotStartScaledMs`) for a wall-clock one — so a count given to a running
  effect runs from then, and its first cycle starts at the top whatever the master's bar. A member
  runs while its own clock, the effect's less its distribution offset, is in `[0, N)`: it holds what
  is underneath before its offset comes round and after its own N, so a one-shot chase ripples in
  and out rather than showing a tail at the start. The phase functions answer `NaN` for a holding
  member (`FxInstance.isHolding`) and the engine applies nothing for it. The effect is **spent** once
  the clock has run N cycles past the largest member offset — some member's phase was computed on
  the pass and none has runs left (`nShotPending` / `nShotEvaluated`, tick-loop owned) — and
  `FxEngine.finishNShots` ends it after the pass's frame is out: removed, which resets what it alone
  covered, **unless a programmer layer spawned it**, which is kept `spent`, painting nothing, because
  the layer's recook would otherwise spawn it afresh and fire it again on the next unrelated
  programmer write; the layer's release removes it. A new count (`updateEffect`'s `newNShot`, or
  `clearNShot` for forever) re-anchors from the next pass and wakes a spent instance. An effect that
  is **held** — Blind, a stomp, the programmer's value — still computes its phases, so its count runs
  on while it is held, as its clock does; a **paused** one is not processed and does not finish. An
  expansion with no members evaluates nothing, so a group emptied by a reload does not end an
  effect.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `size`, `direction` and `nShot`;
  `PUT /fx/{id}` and `updateFx` take all three with null meaning keep, plus `runForever: true` to
  clear a count (beside an `nShot`, refused). `EffectDto` (and the group and indirect DTOs) reports
  `size`, `direction`, `nShot` and `spent`; `programmer.keyStack`'s effect layers report `size` and
  `direction`. The MCP effect schemas take the three (`AiToolSchemas.putEffectDynamics`, parsed by
  `AiTools.parseEffectDynamics`), and `describe_rig` and `get_current_state` say them where they are
  not the default.
- **`KeyStackLayer.onStage` weighs it**: an effect weighted to nothing (a 0 % size, a spent N-shot)
  is not on stage, and only a **full-weight** `OVERRIDE` covers the layers below it — under full
  size it is a mix with what is underneath, so both are on stage (a setting covers from half weight
  up, as it writes).

### What the UI calls them: *Around* and *Within*

The live effect editor (`FxEditor`, fixture-fx-sheets plan D12, D12a) never shows the operator
`ADDITIVE` or `MULTIPLY` for the two effects where the choice is the whole point; it asks a question
in the desk's words and sends an existing spelling (plan P2 — no new field, no `formatVersion`):

- **Movement — *Centre: Around current position | Absolute*.** A position effect with a centre pair
  (Circle, Figure 8, Random position). **Around** is `ADDITIVE` with `panCenter` and `tiltCenter` at
  **128**: the position blend is `base + effect − 128`, so a shape centred on 128 orbits whatever the
  reset pass put underneath — the programmer's value, else the cue's, else the base. That is the only
  way a Circle orbits a set position; under `OVERRIDE` it circles its own 128/128. **Absolute** is
  `OVERRIDE` with the centre shown. The picker starts movement effects Around.
- **Level — *Over the level underneath: Replace it | Within it*.** The dimmer category. **Replace**
  is `OVERRIDE` (today's default); **Within** is `MULTIPLY`, so the effect runs inside the level
  underneath, scaled by it.

Reading back is by spelling, not by flag: a stored `ADDITIVE` with both centres at 128 **is**
Around, whoever stored it; `OVERRIDE` is Absolute or Replace; anything else (`MAX`, an `ADDITIVE`
with a centre of its own) presses neither and the editor's Advanced section still shows the raw
blend, which stays editable there for every effect. Effect palettes ask the same Centre question
in session 5 (D16); stored palettes keep `OVERRIDE` and read as Absolute (call 6).

### Reading `blendMode` and friends out of a string

`blendMode`, `distributionStrategy`, `elementMode` and `elementFilter` all arrive as strings —
from a REST body, from a stored `look_fx` / `cue_ad_hoc_fx` / `cue_layers` row — and
`fx/EffectSpecCoercion.kt` is the only place any of them is parsed. It offers exactly two named
policies, because there are exactly two situations:

* **`Strict`** — a request body stated the value, so an unrecognised one is the caller's bug and
  throws `IllegalArgumentException`. Used by `POST /fx/add`, `PUT /fx/{id}` and
  `POST /groups/{name}/fx`, each of which answers **400**. Note the 400 comes from the handler's
  own `catch`, not from `plugins/ErrorHandling.kt` — it registers no `IllegalArgumentException`
  clause, so a *new* strict call site needs its own.
* **`Lenient`** — a stored row is already on the desk and the cue has to fire, so an unrecognised
  value warns and takes the field's default. Used by `EffectSpawner.createEffectInstance` and
  `CueComposer`'s layer blend. A `null` optional field is silent; a present-but-blank one warns,
  since every column that holds these is either nullable or NOT NULL with a default, so `""` means
  a row was written wrong.

Both accept any casing and tolerate surrounding whitespace. **The strict path is stricter than it
was before sweep item E4**: `distributionStrategy` and `elementFilter` used to degrade an
unrecognised value to `NORMAL` / `ALL` and add the effect anyway, so a legacy or typo'd value from
a MIDI, AI or third-party caller now loses the whole request instead of one attribute. The shipped
UI only ever sends canonical names, so nothing in-tree changes.

### Property Reset

Before processing effects each tick, the engine resets all FX-controlled properties to
the value of **the layer below** (not to a hardcoded neutral). This is a core invariant of
the composition model — see
[lighting-composition-model.md §Layer 3 — Effects](lighting-composition-model.md#layer-3--effects).

The fallback is resolved by `LayerResolver.fallbackFor(target, fixture, key)`, which
composes:

1. **Layer 2 (programmer)** — sticky manual property entries and the raw-channel sideband
   held in `ProgrammerStore`, unless the blind gate is engaged. A programmer entry wins
   over everything below, and additionally **suppresses** the effect's own apply on that
   (fixture, property) — see below.
2. **Layer 4** — `CueAssignmentResolver`'s composed cue property assignment for this target, if
   any. (The class name predates the renumber.)
3. **Layer 5** — fixture baseline (0 for sliders, black for colour, 128 for pan/tilt).

### Programmer suppression and the priority band

An active programmer entry (blind off) suppresses **every** effect on its (fixture,
property) — cue-owned and manual alike: the reset pass paints the programmer value and the
apply loop skips that pair (per fixture, so a group effect keeps painting other members, and
per property, so an effect on another property of the same fixture keeps running). Effects in the reserved
**programmer priority band** (`FxEngine.PROGRAMMER_FX_PRIORITY_BASE`, strictly above every
cue-derived priority) are exempt — they are programmer-owned FX and modulate on top of
programmer values. The suppression snapshot is rebuilt only when `ProgrammerStore.epoch`
changes, keeping the 50 Hz path allocation-flat.

Consequences:

- Manual values (`updateChannel` shim, MIDI surface faders — programmer entries) persist
  visibly under running effects, and on properties they hold, *win outright* — the effect
  freezes until the entry clears, then resumes on the next tick.
- `MAX` and `ADDITIVE` blend modes do not ratchet upward across ticks — the reset clears
  the accumulator before each effect iteration.
- Parked channels are skipped entirely by the reset + apply pass (`ParkManager.isParked`
  check), both as an optimisation and because the transmit-time parking override would
  discard the composition result anyway.

**Blind holds the band** (show-cue-editing plan D7, §3.6). While the programmer is blind, every
instance in the band — a busk, Include's children, a programmer layer's — is held: `fx/BlindHold.kt`,
`blind && isProgrammerFxPriority(priority)`, asked by the compose pass beside `EffectSuppression`
(whose rule is unchanged) against **one read of blind per pass**, the same read that empties the
suppression map, so a Blind toggle landing mid-pass cannot pair an empty map with an unheld band. A
held instance keeps its phase and skips only its apply: a beat effect's phase is its master's tick,
a wall-clock effect's clock — including a beat effect on *Seconds*, which runs on that loop —
advances on every pass before anything is applied, and a stateful effect is still computed. Leaving
Blind lands it in step with what it started beside, growing from nothing over the Blind fade like a
press — or at full at once over a value the programmer holds (effects plan §3.5;
`ProgrammerWriter.setBlind` ramps the band's envelopes before the gate opens, so no pass paints one
at full first). Provenance leaves a held effect out of its winners and the property stack lists it `heldBack`
with reason `BLIND`.

**Local FX sit above every layer's FX** (FX editor plan D5, §3.5). Within the band, a programmer
layer's FX sit at `BASE + layer index` (`ProgrammerLayerStack.priorityFor`, clamped at
`PROGRAMMER_FX_RANK_CLAMP`) and Local FX (`FxInstance.isLocalEffect`) at
`BASE + LOCAL_OFFSET + localRank`, `LOCAL_OFFSET = 200_000`, above the clamp — so every Local FX
applies above every layer's FX, as Local values sit above every layer.
`isProgrammerFxPriority` is unchanged, so suppression's exemption, Blind's hold, Clear and Release
read the whole band as before. **This changed what wins:** a pressed FX palette that used to apply
over a Local FX on the same key (any layer above the bottom one did) now applies under it.

- **The rank.** `FxEngine.insert` ranks every new Local FX, whichever door made it (the bare press,
  `/fx/add` with `programmerOwned`, *Apply as values*, Include's children): `localRank` is one above the
  highest Local FX sharing a key with it — a head it covers and its attribute — else 0, so a new FX
  takes the top of its lane. A head copy (a split, a narrowing) and an edit's swap keep their
  parent's priority, taken again under the rank lock at the swap, so a move made meanwhile holds. A
  layer's FX that **Detach** makes Local moves to the bottom of the Local FX, in the order it had,
  so it stays under the Local FX it was already under, as Detach writes a layer's values beneath the
  Local values already held. It also moves above every layer's FX, so where a higher layer's FX beat
  it on a key, it now wins there (agreed with Chris in session 3a). A reload (`Show.resume`) adds
  the carried FX back in apply order, so each lane's order survives it.
- **`programmer.moveFx {effectId, above}`** (`FxEngine.moveLocalEffect`) re-ranks a Local FX directly
  below `above`'s FX, or at the bottom of the Local FX for a null `above`. Every Local FX is put in one
  list in apply order, the moved one taken out and put back below `above`, and from the bottom each
  keeps its rank if it still applies after the one below it, else takes the lowest rank that does; the
  moved FX always takes that lowest rank, so moving the other one back restores the order. Only
  priorities change: nothing restarts. Answered `fxChanged(UPDATED)` per FX whose priority moved, or
  `fxError` — `FX_MOVE_NOT_LOCAL` when either FX is not Local, `FX_NOT_FOUND` when either is not running.
- **Record keeps the order** (FX editor plan D4, session 3b). Every write of a home's FX from the
  programmer numbers them bottom first in `FxEngine.inStoredApplyOrder`: two FX sharing a key (an
  attribute on a head both cover) in apply order, any others in the order they were made in — which
  for FX Include brought back is their stored order, so an Update that moved nothing writes nothing.
  That covers Record into a cue (`fxInstancesToCueChildren`) and into a Look (`writeLookEffects`,
  absorb-effects too), a stage snapshot, and Update's write-back into either. A write that keeps some
  of a home's FX (Record's Merge and Replace into a cue, Update) gives the FX it writes, new ones
  included, the places of the stored ones it wrote over, in that order, and puts any extra on top
  (`renumberInApplyOrder`): an FX it did not write keeps its place in the list, though a written one
  may move past it. A write that moves no FX renumbers nothing, gaps and ties included. Merge and
  Update into a cue write an FX Include brought back onto the child it came from (its origin's
  uuid, whatever its type is now), and any other FX onto the first child of its target, type and
  attribute that no other FX took, and Remove deletes the child that same pairing names; Replace
  pairs by that key alone, in order. Either way two stacked
  FX of one type on one head stay two children, and a second Include does not run a child twice. A writer that takes an explicit list — the
  cue and Look bodies, MCP's `create_look` and `create_cue` — stores it in the list's order
  (`inListOrder`). The spawners give the order back: a cue's GO and loose holds, a Look press,
  Include, copy and duplicate all read a home's FX by `sortOrder`, then id, and add them in that
  order, so their compose orders follow it, and FX that Include brings into the programmer take their
  Local ranks in it. Include's origin names a cue's FX by uuid, since Update may renumber them.

**One rule, three readers** (fixture-fx-sheets plan W1, W4). The rule is
`fx/EffectSuppression.kt` — a pure function of a suppression snapshot, the stomp check, the
(fixture, property) and the effect — and the compose pass, provenance (`ProvenanceService.compute`)
and the property-stack read behind `programmer.keyStack` (`ProvenanceService.keyStack`) all ask it,
against the engine's own snapshot (`FxEngine.programmerSuppression`, empty while blind). That is
what lets the fixture sheet mark an effect *held back*: the mark is the tick's answer, not a second
copy of it. Never write the rule again beside a reader.

**The programmer outranks an effect only where the engine holds it back, on the key the effect
paints.** The snapshot is `ProgrammerStore.activePropertiesByFixture` — property entries, each
holding back effects on **its own key** alone. Two holds suppress nothing, and provenance credits
an effect for both: a raw-channel sideband slot (an unpark hand-down, a Channels-tab write on a
head with no pan property, filed under `position`), and a `pan` entry under a Circle keyed
`position` (the Channels-tab pan on a head that declares pan, which `updateChannel` lifts to its
own slider). An effect painting **every** channel of a programmer-held key under a sibling key takes
the key; one painting some of them (a UV wave beside an RGBW colour entry) does not. Before W4 both
holds read *Programmer* while the Circle was what the rig showed — the plan's issue 1.

**Live edits** (W3). `updateFx` on the socket and `PUT /fx/{id}` share one parse,
`applyEffectUpdate` (`routes/lightFx.kt`), and both land in `FxEngine.updateEffect`, which keeps the
instance's id, phase, layer identity and fade. A type swap takes the new type's timing source
(`newTimingSource`), as the add path does — before, a beat effect swapped for a wall-clock one kept
running on the beat loop. A refusal (`FX_UPDATE_REFUSED`: an unknown blend, type or master uuid;
`FX_NOT_FOUND`; a split of an effect that is not Local, `FX_SPLIT_LAYER_EFFECT` / `FX_SPLIT_NOT_LOCAL`)
is decided before anything moves: a 400 / 404 / 409 on REST, an `fxError` on the socket. With
`targets` naming some of its heads the edit splits them off instead (§"Local effects follow the
selection").
Its client is the live editor (`FxEditor`, session 3): every control writes the draft through
`useLivePush` at a 50 ms floor (an optional field only once the operator has set it), and the frontend toasts each `fxError` keyed per effect
(`store/fixtureFx.ts`), so a drag refused on every frame is one toast.

**Reset to palette** (W5). `POST /fx/{id}/reset` re-applies a programmer palette layer's
**current** effect to the instance it spawned, through `updateEffect` — id and phase kept — with
every setting the layer would spawn it with, its overrides included, both masters set exactly
(`replaceMasters`, which can clear a master as the single-master arguments cannot). It also moves
the instance's spawn key to the palette's current entry, under the stack's effects lock, so the
next recook keeps it rather than respawning it. 409 `FX_NOT_FROM_PALETTE` for anything no
programmer palette layer started (a Look's, a manual one, a cue's palette layer, which the next GO
respawns); 409 `FX_PALETTE_GONE` when the palette holds no effect any more.

**What a recook does to an edited instance.** `ProgrammerLayerStack.syncEffects` matches a live
instance by the `StoredEffect` it was **spawned** from (`ProgrammerLayerEffectKey`), never by its
current parameters, and `updateEffect` carries that key across its swap — so an instance edited
through `updateFx` survives a patch, a move or an unrelated add. A palette-effect `PUT` recooks
values only and leaves the instance alone, but the next recook of the stack *for any reason* reads
the edited palette, whose entry no longer matches the instance's key, and respawns it from the
palette — its phase restarted, any instance edit gone. Reset is the per-instance way back. Pinned
by `FxLiveEditRoutesTest`; see `FU-TMPL-FX-EDIT-NO-RETIME`.

### Locate versus park

The Locate toggle (`routes/lightLocate.kt`) asserts its centre-and-open-white values as
programmer entries (owner `locate`). "Locate wins" is **non-destructive** since the
programmer redesign: the entries suppress every effect covering the written properties for
as long as the locate holds, and releasing the locate lets them resume — nothing is
removed. (`ToggleLocateResponse.effectsRemoved` was dropped in Session 2, once the frontend
that read it no longer existed.)

Park sits above all of that at transmit time, so a locate cannot move a parked channel.
One filter keeps the sides honest: **`applyLocate` drops unpublishable assignments** using
`ProgrammerWriter.publishability`. That helper applies the *same two guards the cascade
publish applies*, in the same order and through the same helpers
(`inferTargetForProperty`, then `allChannelsParked`), so the pre-filter cannot disagree
with what the write then does — which matters because `ColourTarget` scopes its
white/amber/UV channels by `bundleWithColour` while `PropertyChannelWriter.channelsFor`
enumerates them by trait. It reports `UNRESOLVED` (no DMX-backed channels) and
`PARK_MASKED` separately, and the latter surfaces as `parkMasked` on the toggle response so
the UI can distinguish "parked" from "nothing to write".

Consequences:

- A wholly parked fixture or group is a no-op locate: zero writes, `active: false`,
  `parkMasked: true`, nothing registered in `LocateManager`, and no effect is disturbed.
- Partial parking still locates: the unparked properties are asserted (suppressing the
  effects on *those* properties), while parked channels keep emitting their parked value.
- A target that becomes park-masked while located keeps its locate state. `LocateManager`'s
  re-assert path drops an entry only when the assert callback reports the target *stale*
  (returns null), not merely write-less, so releasing an overlapping group locate can't
  silently un-locate a parked member.
- Unparking a channel that a locate wrote to hands the *parked* value down into the
  programmer's channel sideband (see `UnparkValueSink`); the hand-down is newer than the
  locate entry, so recency arbitration keeps the output at the parked value rather than
  snapping up to the locate level.

Covered by `routes/LocateParkInteractionTest.kt`.

### Effect iteration order

Effects are folded in **apply order** — `priority` ascending, then `composeOrder`, then `id` —
rather than from a `ConcurrentHashMap`, beat and wall-clock alike (§"Architecture: one compose pass"). An
effect's `composeOrder` is its own id, except that a cue re-stamps the instances it carries over
into its layer order (`restampComposeOrder`) and a copy split off an effect keeps its parent's, so
same-priority effects compose in the order they were placed rather than the order they were
created; `id` breaks only what is left. Multi-effect composition on the same property is
therefore **deterministic** — previously the effective "last-wins" ordering was undefined. A home
spawns its FX in their stored `sortOrder`, which Record writes in this order, so the order an
operator built in the programmer is the one a cue or Look plays back
(§"Programmer suppression and the priority band").

Priority assignment:

- Manual effects: `priority = 0`
- Cue-owned effects: `priority = stackId * 1_000_000 + sortOrder * 1_000 + 1`
  (leaves gaps for future fine-tuning without renumbering)
- A programmer layer's effects: `PROGRAMMER_FX_PRIORITY_BASE + layer index`
- Local effects: `PROGRAMMER_FX_PRIORITY_BASE + LOCAL_OFFSET + localRank`, above every layer's
  (§"Programmer suppression and the priority band")

The sorted snapshot is cached in one `@Volatile` field (`sortedEffects`) and rebuilt under a
`synchronized` block on every mutation. The compose pass reads it lock-free.

### Instance dynamics

The per-instance parameters that request threads may change while the compose pass reads them —
`isRunning`, `phaseOffset`, `stepTiming`, `distributionStrategy`, `elementMode`,
`elementFilter`, and since the effects plan's session 1 `size`, `direction` and `nShot` — live in one immutable `FxDynamics` value behind a single atomic reference
(`FxInstance.dynamics`), not in individual fields. A tick pass reads the snapshot once per
effect and works from it throughout (the member phase functions *require* it as a parameter),
so a concurrent edit lands wholly on one pass or the next, never half-way through; writers
replace the whole value atomically, so `updateEffect`'s multi-field changes are indivisible.
The cell is shared across `updateEffect`'s instance swap, which is what makes a pause/resume
racing a swap unlosable. Everything else on `FxInstance` is either immutable, written only
before the instance is published to the engine (`timingSource`), an independent `@Volatile`
scalar with no cross-field coupling (`priority`, the master slots, `accumulatedScaledMs`, `spent`),
one immutable value behind an `AtomicReference` changed only by compare-and-set (the envelope,
§"Size, direction and N-shot"), tick-loop-owned plain fields
(the N-shot anchors and per-pass flags), or tick-written diagnostics (`lastPhase`, stored once per
pass).

Anything that persists or reports several dynamics fields together (Record, programmer
capture, cue capture) must also read them from one snapshot — the `!= default → else null`
idiom read twice across a concurrent edit records an explicit value where track-the-default
`null` was meant.

### Phase Calculation

The phase passed to each effect determines where in the cycle it evaluates. For group/multi-element
effects, `FxInstance.calculatePhaseForMember` computes, through the pure `FxPhase.beatPhaseAt` (its
wall-clock twin `wallClockPhaseAt`; §"Previewing an FX"):

```
memberPhase = (baseClock + phaseOffset - distributionOffset) % 1.0
```

The distribution offset is **subtracted** so that higher-offset members are behind in the
cycle. This makes the visual sweep flow in the natural direction (element 0 → N for NORMAL).

For PING_PONG distribution, a triangle wave remap is applied to the base clock phase before
adding offsets, causing all effects to sweep forward then backward:

```
tri = baseClock < 0.5 ? baseClock * 2 : 2 * (1 - baseClock)    // [0→1→0]
remappedClock = tri * (slots - 1) / slots                        // [0→maxOffset→0]
memberPhase = (remappedClock + phaseOffset - distOffset) % 1.0
```

The scaling to `(slots - 1) / slots` ensures the sweep reaches the last element without
wrapping back to the first (since `1.0 % 1.0 == 0.0` would alias with element 0). `slots` is the
Order's own count (`DistributionStrategy.distinctSlots`), **not** the count after the spread's
shape: Spread, Wings, Blocks and Groups set the members' offsets, as under every other Order, and
leave the sweep alone — so Spread 0 or Blocks folding every head to one slot never scales the sweep to
nothing, and an effect that names no shape bounces bit for bit as before (P6;
`SpreadShapeTest` "ping-pong sweeps the same way whatever the shape").

Then, on that phase, the effect's **direction** and **N-shot** (§"Size, direction and N-shot"): a
member outside its runs answers `NaN` and paints nothing, and Backward and Bounce read the phase
backward on the member's own cycles. For an N-shot the clock is counted from the effect's start
rather than the master's tick 0. A forever effect's phase is exactly what it was — the clock is
still split into whole cycles and a fraction in integer arithmetic — and the whole-target form
(`calculatePhase`) is the same function at offset 0 — except for a copy split off a spread
(`FxInstance.splitMember`), which makes its member's call: that member's offset and group size.

### EffectContext

Effects receive an `EffectContext` alongside the phase, providing distribution metadata:

| Field | Type | Description |
|-------|------|-------------|
| `groupSize` | `Int` | Total elements being distributed across (1 for single fixture) |
| `memberIndex` | `Int` | 0-based index of the current element |
| `distributionOffset` | `Double` | Phase offset for this member (0.0–1.0) |
| `hasDistributionSpread` | `Boolean` | Whether distribution produces different offsets (false for UNIFIED) |
| `numDistinctSlots` | `Int` | Unique offset positions — equals `groupSize` for asymmetric, fewer for symmetric |
| `trianglePhase` | `Boolean` | Whether the phase was triangle-remapped (PING_PONG) |

`basePhase(shiftedPhase)` recovers the un-shifted clock phase: `(phase + distributionOffset) % 1.0`.

### Step-Chase Windowing

There is no static effect: a fixed level, colour, position or setting is a value on the fixture's
own property (the programmer, a Look, a cue), and StaticValue, StaticSetting, StaticColour and
StaticPosition were removed for that reason. The windowing they used survives as a technique for a
user effect that should read as a **step chase** — one member lit at a time — by only being "on"
for a window of each cycle. The window width is `1 / numDistinctSlots`, which accounts for
symmetric distributions where multiple members share an offset.

For standard distributions (NORMAL, CENTRE_OUT, etc.), a modular distance check determines
which member is active:

```
base = context.basePhase(phase)                     // recover clock phase
dist = (base - distributionOffset + 1.0) % 1.0      // modular distance
active = dist < window
```

For PING_PONG (triangle phase), an absolute distance with half-window avoids floating-point
edge cases at turnaround points:

```
active = abs(base - distributionOffset) < window / 2
```

### Step Timing

Step timing controls whether the beat division represents the **total cycle time** or the
**per-step time** for distributed effects.

| Mode | Beat Division Meaning | Example (4 heads, 1-beat division) |
|------|----------------------|-------------------------------------|
| `stepTiming = false` | Total cycle time | Full sweep in 1 beat (each head active for ¼ beat) |
| `stepTiming = true` | Per-step time | Each head active for 1 beat, total sweep = 4 beats |

When `stepTiming` is enabled and the effect is distributed across a group, the effective
beat division is scaled:

```
effectiveDivision = beatDivision × dynamics.distinctSlots(groupSize)
```

This uses `distinctSlots` rather than `groupSize` so that symmetric distributions
(CENTRE_OUT, CENTRE_IN) scale correctly — symmetric pairs share a slot — and it is the count **after the
spread's shape** (§"The spread's shape"), so Blocks and Groups leave no empty steps either.

**Default values**: Each effect type declares `defaultStepTiming` which is used when
creating new `FxInstance`s. A step chase (a user effect windowed as above) declares `true`;
every built-in is a continuous effect (SineWave, Pulse, etc.) and defaults to `false` (full
cycle). The value can be overridden per-instance via the API.

## Previewing an FX

Nothing on the stage evaluates an FX for a moment it has not reached, so the FX editor's graph (FX
editor plan D7) comes from `fx/FxSampler.kt`: an FX's output on each head over a window, for a running
FX or a draft (an `FxInstance` never added to the engine), with the engine's own maths and none of its
state (P3, P4). `FxEngine.sampler(freshEffect)` builds one over the engine; the preview route
(session 1b) is its only caller.

**What it reuses.** The members and their offsets are the compose pass's: `FxEngine`'s expansion and
`DistributionPlan`, read through the instance's caches while they are valid and built, never stored,
when they are not. The order is the engine's own `sortedEffectsComparator`, so Local FX fold above
every programmer layer's FX (§"Programmer suppression and the priority band"). A step is resolved
per head by its target (`outputByte`, `outputColour`, `outputPosition`, `outputLevel` on the targets,
which `applyValueToFixture` calls too). What is held back is `EffectSuppression.heldBackReason`,
the rule the property-stack read asks, against one snapshot of the programmer and Blind.

**What it splits out.** Two pieces were fused with state and are now pure, with the engine calling
them:

- `fx/FxPhase.kt`: a member's phase from one reading of its clock — a master tick (`beatPhaseAt`) or
  scaled milliseconds (`wallClockPhaseAt`) — its dynamics and its Runs anchor, or NaN while it holds.
  The instance keeps its writes outside: anchoring `nShotStartTick` / `nShotStartScaledMs`,
  `lastPhase`, and the Runs tally (`NShotTally`) the compose pass reads to end a spent FX.
- `fx/FxBlend.kt`: the blend and weight as functions of (before, output, blend, weight) — a byte,
  RGB, a position axis (*Around* about 128) and a setting's slot (from half weight up) — and the
  weight itself, Amount × envelope × FX amount. Every target's `applyValueToFixture` writes through
  it; `FxBlendTest` pins it to the values the targets wrote before.

**The fold.** For each head and attribute: what is underneath (`LayerResolver.fallbackFor`, read
once and held across the window; a fade underneath is not drawn), then every FX painting it in apply
order, each blended over the running result and weighed by its Amount × its stack's FX amount × 1
for the envelope (a fade in or out is not drawn either). An FX that a stomp, the programmer or Blind
holds back on that key is sampled — its own output is in the answer — but left out of *Result*, and
reported `heldBack` with its reason. A member outside its Runs, a paused FX, or one that cannot be
evaluated holds; its samples repeat what it would have blended over and its `holding` ranges say
where. Where nothing running covers a key, the pass neither resets nor paints it, so the stage keeps
the frame a paused FX left; *Result* is that frame, read off the head. *Result* is before Layer 1: a
row whose channels are all parked is marked `parked`, and the park is what is sent. Each attribute
is folded on its own, so where two share channels (a colour FX's bundled W, A and UV beside a `uv`
FX, `pan` beside `position`) the stage's single fold is not reproduced
(`FU-FX-PREVIEW-SHARED-CHANNELS`). *Include* `STACK` folds every running FX reaching the rows as well
as the ones asked for; `ONLY` folds those alone.

**Drafts.** A draft is ordered where adding it would put it — a Local draft at the top of its lane,
any draft after the running FX of its priority — and runs on the masters its uuids name, which only
adding it would bind to slots. Previewing an edit to a running FX is the route's (session 1b).

**Clocks.** Sampling starts from one reading of every clock (`FxSampleClock`: each master's tick and
its length, each rate master's scale). A beat FX's tick at a sample is its master's tick plus the
sample's offset in that master's ticks, so it lands on the ticks the stage paints; a wall-clock FX's
is its accumulator plus the offset at its rate. A Runs FX counts from its anchor, or from the window's
start before its first pass.

**Stateful FX.** Candle Flicker and Fluorescent Flicker keep state, shared on stage by every member.
The sampler runs a fresh object per head, `initialize()`d and stepped once a sample from the window's
start, its tick count starting at 0 so the same request answers the same, and marks the FX
`representative`: its shape, not the stage's frames. Every other FX is evaluated on a fresh effect
object too (`freshEffect`, `createEffectWithTemplates` in production), so a sample never shares one
with the pass.

**Caps.** 48 samples to a cycle of the first FX asked for (its whole cycle, Timing *Per head*
included), at most 1024 a row; at most 96 rows and 8 FX a request. Past a cap the answer is cut and
`truncated` names which. A row is a head; its samples are a byte a sample per plane — a slider or a
setting's level, RGB plus each bundled W, A and UV the head has, or pan and tilt — and a Steps FX's
own output carries the step it shows beside the steps' labels.

**Nothing changes.** The sampler never writes an instance's clocks, Runs anchors, stateful state,
envelope or `lastPhase`, or its expansion and plan caches, and never writes DMX: the one transaction it makes is
a staging one it never applies, to read a paused FX's frame off a head. `FxSamplerParityTest`
drives the engine a pass at a time and holds *Result* to the committed DMX at every sampled tick —
every Order with Spread, Wings, Segments and Parts set, Runs with Bounce, Timing *Per head*, *Replace*
under *Within*, a Circle *Around* a programmer position, Amount, Palette Steps, a Seconds FX, each
element mode and filter, a held-back FX, a Local FX over a layer's, and a beat FX with a wall-clock FX
*Within* it — and checks that sampling leaves every instance field and the controller as they were.

## Easing Curves

The low-level DMX fading system supports easing curves via `EasingCurve`:

| Curve | Description |
|-------|-------------|
| `LINEAR` | Constant rate |
| `SINE_IN` | Slow start |
| `SINE_OUT` | Slow end |
| `SINE_IN_OUT` | Slow start and end |
| `QUAD_IN` | Quadratic slow start |
| `QUAD_OUT` | Quadratic slow end |
| `QUAD_IN_OUT` | Quadratic both |
| `CUBIC_IN` | Cubic slow start |
| `CUBIC_OUT` | Cubic slow end |
| `CUBIC_IN_OUT` | Cubic both |
| `STEP` | Jump at end |
| `STEP_HALF` | Jump at halfway |

## FX Targets

Effects target fixture or element properties via `FxTarget` subclasses. Targets can reference a single fixture, an entire group, or an ordered selection of them using `FxTargetRef`. The `applyValueToFixture` method accepts `GroupableFixture` (not `Fixture`), allowing it to work with both standalone fixtures and fixture elements:

### Target Reference Types

```kotlin
sealed class FxTargetRef {
    data class FixtureRef(val targetKey: String)              // Single fixture (or cell)
    data class GroupRef(val targetKey: String)                // Fixture group
    data class SelectionRef(val selection: EffectTargetRef.Selection)  // Fixtures and groups, in order
}
```

### Over a selection (effects plan session 3)

One effect may run over an **ordered list of fixtures and groups** (effects plan D3), spread across
them in that order as a group's members are: a marquee in grid order, a busk selection in the order
pressed. Before this a Local effect over loose fixtures ran them in unison, one instance each.

- **The type is effect-only.** `models/TargetRef.kt` keeps `TargetRef` to fixture and group, and adds
  `EffectTargetRef` beside it — `Single(TargetRef)` or `Selection(items)`. A value row, a layer's
  targets and a binding hold a `TargetRef`, so they cannot hold a selection, and the write boundaries
  that parse one refuse it by name (`validateCueChildren` for a cue's rows and layers,
  `validateLookRows` for a Look's). An effect row — a cue's ad-hoc effect, a Look effect, an
  `EffectEntry` — holds an `EffectTargetRef`.
- **Stored as the pair it always was.** `targetType: "selection"` and a `targetKey` holding the list's
  canonical JSON, `[{"key":"par-1","type":"fixture"},{"key":"Front wash","type":"group"}]` (keys
  alphabetical, no whitespace, `EffectTargetRef.Selection.key`), so no column was added; sync carries it
  from `formatVersion` 34. `EffectTargetRef.ofOrNull` refuses a key that is not a non-empty list of
  fixtures and groups and reads a one-item list as its one target; a cue's or a Look's write stores the
  pair in that canonical spelling (`EffectTargetRef.canonical`), so a hand-written row pairs with what
  Record writes. `target_key` is `text` on both effect tables, since a selection outgrows 255.
- **The expansion** (`FxEngine.buildSelectionExpansion`) flattens the list in order: a fixture or a cell
  as itself, a group as its members in their own order. Each unit is a **member** where it has the
  property, else its **heads** where its elements do, else skipped; a head reached twice counts once,
  at its first place, and an item the rig has lost is skipped as a missing member is. Every unit a
  member is `GROUP_MEMBERS`, spaced evenly in selection order (so `POSITION` is selection order too);
  every unit its heads is `GROUP_ELEMENTS`, exactly as a group of multi-head fixtures, `elementMode`
  choosing per fixture or flat; a mix is `GROUP_ELEMENTS` too, a member being a unit of one head, so
  per fixture each multi-head fixture runs across its own heads beside the members. The members go through
  `DistributionPlan.of` like a group's, so the spread's shape applies unchanged. The compose pass sends a
  selection down `processGroupEffect`.
- **Coverage and reach.** `fixtureKeysCoveredBy` answers the heads in order;
  `isPropertyCoveredByAny` also answers the units (the parents of heads it paints), as it does for a
  group; `getIndirectEffectsForFixture` lists a selection effect for every fixture it names, which
  `IndirectEffectDto.targetType` marks. `getEffectsForFixture` and `getEffectsForGroup` never see one:
  the canonical key names neither. Another cue's stomp takes one off only where it asserts **every**
  item on the property, as it takes a group's effect off only where it asserts the group.
- **The doors.** `POST /fx/add` takes `targets` in place of `fixtureKey` — one item is that fixture or
  group alone, several a selection, every item checked against the rig at once and a trigger or a
  command refused for any item that has one. `EffectDto` reports `targetType` and, for a selection,
  `targets`. Record writes a Local effect over a selection as one ad-hoc cue effect, or one Look effect,
  with its `selection` target (`fxInstancesToCueChildren`, `writeLookEffects`); Include spawns it back as
  one (`EffectSpawner.resolveTargetForCue`); a scoped Record or a masked Include reads it as in scope
  only when every item is (`effectTargetInScope`) and by the mask group of its first item that resolves
  (`Fixtures.referenceFixtureFor`). MCP's `create_cue` takes an ad-hoc effect's `targets` instead of
  `targetType` and `targetKey`, and `clear_effects` naming targets clears a selection effect only when
  every item it names is among them. A rename rewrites the keys inside a stored selection (`ai/RigKeyReferences.kt`), and a
  venue Update's dead-reference report names each item (`venue/VenueUpdate.kt`).
- **Not here**: a palette layer's effect still fans one instance per target. The programmer's bare
  effect press and the split are built on this — §"Local effects follow the selection" below.

### Local effects follow the selection (apply-model plan session 2)

MagicQ's rule (apply-model plan D5, §3.4): a bare library effect is a **Local** effect on the whole
selection — the programmer's own, in its priority band and owned by no layer, Look or cue
(`FxInstance.isLocalEffect`) — and an edit follows the selection: with every head the effect runs on
selected it changes in place, phase kept; with some selected, those heads split off at the phase they
had.

**The bare effect press**, `POST /api/rest/projects/{id}/programmer/effects`
(`routes/programmerEffects.kt`): `{effectType, propertyName, targets, families?, parameters?}` plus
every effect field `POST /fx/add` takes, built by the same `buildEffectInstance`, so one effect over
the selection as the add route makes it (a group one group effect, several targets one selection
effect). The route fixes only where it runs and whose it is:

- **The mask.** The effect's family must be in the selection's mask, else 400 `EFFECT_OUTSIDE_MASK`;
  no targets is 400 `EFFECT_NEEDS_SELECTION`. The family is `routes/programmerEffects.kt`'s
  `effectFamily`: the attribute group of the property it drives on the first head it paints
  (`maskGroupForProperty`, as Record and Include read an effect), so a dimmer-category Pulse on `uv`
  is Colour. Agreed with Chris during session 2; an effect palette's family stays its library
  category (`familyForEffectCategory`), since a palette names no property until it lands.
- **The timing.** No `beatDivision` is one bar; a beat effect naming no master and not on *Seconds* is
  stamped with the master whose `usage` is its library category, else master 1 (venue-visit plan
  D16) — `fx/EffectTiming.kt`'s `usageMasterUuidFor`, the one reading MCP's effect tools stamp with
  too. That is the category, not the property group the mask reads: a Pulse on `uv` is Colour for
  the mask and takes the dimmer master.
- **Local replacement**, the effect-side twin of a palette layer's family replacement
  (`fx/LayerPress.kt`). Before it starts, the press takes the selected heads off every other Local
  effect of its family, read the same way (a family that resolves to nothing replaces nothing): one
  whose every painted head is selected stops; one that paints some of them
  and some others is **narrowed** — rewritten as one instance per head it keeps, each where it was
  (the split below, with no edit). A layer's effect is never touched. The stops, the narrowing and
  the new effect are one engine mutation (`FxEngine.replaceEffects`), so no head drops to the layer
  below between the old effect and the new; an effect another request swapped meanwhile is taken
  off its current instance. Answers `{effectId, stopped, narrowed: [{effectId, into}]}`.
- **Stacking** (FX editor plan D6, §3.6). `stack: true` skips the replacement: the FX is added over
  the selection and no other Local FX is stopped or narrowed, answered `{effectId, stopped: [],
  narrowed: []}`. A blend or Amount (`size`) the request leaves out takes its family's stacking
  start from `fx/FxStacking.kt`: Intensity `MULTIPLY` (*Within it*) at 100 %, Position `ADDITIVE`
  (*Around current position*) at 100 % with `panCenter` and `tiltCenter` at 128 where the press
  names neither the blend nor that centre — only for an FX that declares both centres; any other
  Position FX (Palette Steps) outputs where to go, not an offset, and starts on `OVERRIDE` at 100 % —
  and Colour and Beam `OVERRIDE` (*Replace it*) at 50 %. A blend or Amount the press names wins over the table. Without `stack` the
  press is unchanged: `OVERRIDE` at 100 % when absent. MCP has no bare press, so no tool takes
  `stack`.

A new Local FX takes the top of its lane either way (§"Programmer suppression and the priority
band"), so a stacked one applies above the Local FX it shares heads with.

**The split** is `PUT /fx/{id}` or `updateFx` with `targets` (fixtures, cells and groups, as a press
names them), decided by `planSplit` against the heads the effect **paints** (`FxEngine.paintedHeads`:
an element filter's left-out cells paint nothing and are not counted):

- No `targets`, or targets covering every painted head (`TargetCoverage.covers`, so a group by its
  members and a cell by itself or its fixture), is the in-place edit, id kept. Targets covering none
  are 400 `FX_UPDATE_REFUSED`.
- Covering some splits them off: `FxEngine.headCopies` rewrites the effect as **one instance per head
  it paints**, `replaceEffects` swaps them in for it as one mutation, and the named heads' copies then
  take the edit through the same `updateEffect` an in-place edit uses. The rest run on untouched. REST
  answers `EffectSplitResponse {splitEffectId, edited, rest}`; the frame answers `fxChanged(REMOVED)`
  for the effect and `fxChanged(ADDED)` per copy, the edited first.
- Only a Local effect splits: a programmer layer's is 409 `FX_SPLIT_LAYER_EFFECT` (splitting it would
  need splitting its layer, apply-model plan P5; *Reset to palette* and *Update palette* stay its
  edits), and a cue's or a manual one outside the band is 409 `FX_SPLIT_NOT_LOCAL`.
- A change to the spread's **shape** — Order, Spread, Wings, Blocks, Groups, element mode or filter —
  sent with some heads is 400 `FX_UPDATE_REFUSED`, nothing moved: a split-off head runs alone, so the
  shape would do nothing on it (agreed with Chris, session 2). Only a field that differs from what
  the effect has counts, so an editor re-sending the shape it shows still splits.

**Each copy starts where its head was** (`SplitMember`, on `FxInstance.splitMember`). A copy is the
member it was: its phase is computed by the same member call the parent's pass made for that head —
the member's distribution offset, the size of the list it was spread over and its `EffectContext` —
so a step-timed copy keeps its set's `distinctSlots`-scaled cycle and a ping-pong copy its sweep
(Chris chose to carry them rather than refuse those splits), an N-shot copy runs its window from its
own offset and Bounce counts its cycles from it, and an effect reading its place in the spread still
sees it. `EffectSplitTest` runs an effect on two rigs, splits one mid-run and compares every channel
on every tick. A copy also takes the parent's clocks (`accumulatedScaledMs`, the N-shot anchors, the
start time), masters, timing source, *Seconds*, envelope and band, shares its effect object (as the
members did; added `carried`, so a stateful effect is not reset), and names its fixture or cell with
element filter `ALL`.

- **The offset is beside the phase offset, not in it.** Every copy keeps the parent's
  `phaseOffset`, so an edit that follows the selection sends one value to every copy it covers; a
  phase offset per copy would collapse them into unison on the first edit. (The plan's §3.4 put the
  offset in the phase offset and moved the N-shot anchor by it; done together those count the offset
  twice, which is part of why it is carried instead.) Stored data has no field for it (P7), so what
  stores a copy folds it in — `FxInstance.storedPhaseOffset`, read by Record into a cue
  (`captureCurrentState`, `fxInstancesToCueChildren`) and into a Look (`lookEffectSettings`): a
  recorded copy keeps its phase, though not its set's step cycle or sweep.
- **Update.** A copy keeps a cue Include's origin (`programmerOrigin`). A cue Update writes each
  copy as a child of its own, one per head, so it retires the child they stand for once nothing but
  copies carries it (`splitIncludedChildren`), and the copies drop the origin; otherwise the cue
  would play the effect twice. A Look Include's (`includedLookEffect`) is not kept: a Look's effect
  is written back whole, and a copy is the operator's busk of part of it.
- **Order.** Copies take new ids but keep the parent's `composeOrder`, which the engine sorts by
  between priority and id, so a split moves nothing in the composition order.
- **Races.** The copies take the parent's running state again at the swap, so a pause that reached
  the parent meanwhile is kept, and `updateEffect` swaps by identity, so an edit racing a split
  cannot put the parent back beside its copies.
- **Clear and Release** take copies as they take any Local effect: they are in the band, and each
  names one head.

**The client's side** (apply-model plan session 6; `frontend/CLAUDE.md`'s FX paragraphs). ⌘K *Apply
FX*, the FX table's *+ Effect*, a cell popup's Effects band and the picker's Effects section start a
library effect through `FxPicker`'s `press` arm, the bare effect press above with the door's
selection pair; a tap swaps the auditioned effect through `updateFx`, phase kept. The fixture sheet's
FX tray is not one of them: it starts on its own pick of heads through `POST /fx/add`. A focused Look
layer is the other exception: there the effect starts on `POST /fx/add` and is moved into the
Look (`absorb-effects`), since the press's replacement would take heads off other Local effects. The
live editor (`FxEditorPopover`) follows the selection: it reads which of the effect's heads the
window's pair (or a cell's batch) covers, for its words alone, and with some of a Local effect's heads
covered it holds the edit until *Apply to …*, which sends it once as `PUT /fx/{id}` with those heads
as `targets` — REST, because only REST answers the copies (`EffectSplitResponse`) for the editor to
follow; the frame's `fxChanged` names none. It then sends every later move to each edited copy. A
layer's, a cue's or a manual effect is edited in place and never offered the split.

### Target Types

| Target | Properties | Fixture Trait |
|--------|------------|---------------|
| `SliderTarget` | `dimmer`, `uv` | `WithDimmer`, `WithUv` |
| `ColourTarget` | `rgbColour` | `WithColour` |
| `PositionTarget` | `pan`, `tilt` | `WithPosition` |

### Group Targets

Create group-targeting effects using factory methods:

```kotlin
// Target a group instead of a single fixture
val target = SliderTarget.forGroup("front-wash", "dimmer")
val colourTarget = ColourTarget.forGroup("front-wash")
val positionTarget = PositionTarget.forGroup("moving-heads")
```

### FxTargetable Interface

Both `Fixture` and `FixtureGroup` implement `FxTargetable`:

```kotlin
interface FxTargetable {
    val targetKey: String   // Fixture key or group name
    val isGroup: Boolean    // true for groups
    val memberCount: Int    // 1 for fixtures, N for groups
}
```

## Script Types

Two script types provide focused API surfaces for different tasks:

| Type | Base Class | Purpose |
|------|-----------|---------|
| `GENERAL` | `LightingScript` | Full-power: DMX, fixtures, FX, coroutines |
| `FX_APPLICATION` | `FxApplicationScript` | Apply effects to fixtures/groups (implicit engine) |

FX effect definitions are **not** a script type — they are managed as `fx_definitions`
with dedicated calculate-only script base classes (`FxCalcScript`, `FxStatefulCalcScript`). See [FX Definitions](#fx-definitions) above.

### FX_APPLICATION Scripts

Apply effects with implicit `fxEngine` — no need to pass the engine to every call.

Effects are **named, not constructed**: `effect(id, params)` resolves the type through the
`FxRegistry`, so a script addresses the same vocabulary as the UI, cues and Looks, user effects
from `fx_definitions` included. Omitted parameters take the type's declared defaults. There are no
compiled-in effect classes to instantiate — the `fx/effects` package went with sweep item D7.

```kotlin
val wash = fixture<HexFixture>("front-wash-1")
val movers = group<MovingHead>("movers")

wash.fx {
    dimmer(effect("SineWave", "min" to "40"), BeatDivision.HALF)
    colour(effect("ColourCycle"), BeatDivision.ONE_BAR)
}

movers.fx {
    dimmer(effect("Pulse"), BeatDivision.QUARTER, distribution = DistributionStrategy.CENTRE_OUT)
    colour(
        effect("RainbowCycle"), BeatDivision.TWO_BAR,
        distribution = DistributionStrategy.NORMAL,
        // Run this one off speed master 2 rather than the global tempo (sweep item B2).
        speedMasterUuid = speedMasterUuidAt(2),
    )
}

setBpm(128.0)
```

Every apply site — `FxBuilder`, `GroupFxBuilder`, and all eight `applyXxxFx` extensions — takes
`speedMasterUuid` and `rateSpeedMasterUuid`. Null means master 1 and unscaled respectively, which
are the persisted "no explicit master" values; a uuid the bank no longer knows degrades to master 1
at bind time rather than failing the apply. Note that a rate master is inert for a STATEFUL
wall-clock effect — see `FU-SPEED-RATEMASTER-STATEFUL`.

### GENERAL Scripts (LightingScript)

Full-power scripts with explicit `fxEngine` parameter:

```kotlin
fixture.applyDimmerFx(fxEngine, effect("SineWave"), FxTiming(BeatDivision.HALF))
fixture.fx(fxEngine) {
    dimmer(effect("Pulse"), BeatDivision.QUARTER)
    colour(effect("ColourCycle", "colours" to "#ff0000,#00ff00,#0000ff"), BeatDivision.WHOLE)
}
fixture.clearFx(fxEngine)

// Group with distribution
val group = fixtures.group<HexFixture>("front-wash")
group.applyDimmerFx(fxEngine, effect("Pulse"), distribution = DistributionStrategy.NORMAL)
```

## Group Effect Processing

When an `FxInstance` targets a group (or a selection, §"Over a selection"), the `FxEngine` expands it at processing time:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Group Effect Processing                       │
│                                                                  │
│   FxInstance (group target)                                      │
│        │                                                         │
│        ▼                                                         │
│   Members have target property directly?                         │
│     ├─ YES → Apply to each member with distribution offsets      │
│     └─ NO  → Are members MultiElementFixture?                    │
│               └─ YES → Do elements have the property?            │
│                         └─ YES → Check elementMode:              │
│                              ├─ PER_FIXTURE: For each parent,    │
│                              │   distribute across its elements  │
│                              │   (all fixtures look the same)    │
│                              └─ FLAT: Collect all elements into  │
│                                  one list, distribute across all │
│                                  (chase sweeps across everything)│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Element Mode for Multi-Element Groups

When a group effect targets a property only available on elements (not the parent
fixtures), `ElementMode` determines the distribution dimension:

| Mode | Description | Example (2×4-head fixtures) |
|------|-------------|----------------------------|
| `PER_FIXTURE` | Each fixture gets the effect independently. Distribution runs within each fixture's elements. | All fixtures look the same — head #0 on fixture A matches head #0 on fixture B |
| `FLAT` | All elements across all fixtures form one flat list. Distribution runs across the entire set. | 8 elements total (indices 0-7), chase sweeps across all heads sequentially |

`ElementMode` is stored on `FxInstance` and defaults to `PER_FIXTURE`. It is only
relevant when group members are multi-element fixtures and the target property is
at the element level. It has no effect when members directly have the target property.

### Distribution Strategies

| Strategy | Description | Distinct Slots |
|----------|-------------|---------------|
| `NORMAL` | Evenly spaced phases, element 0 → N | N |
| `UNIFIED` | All fixtures same phase (synchronized) | 1 |
| `CENTRE_OUT` | Rank-based: center fires first, radiates outward | ⌈N/2⌉ |
| `CENTRE_IN` | Rank-based: edges fire first, converges to center | ⌈N/2⌉ |
| `REVERSE` | Evenly spaced phases, element N → 0 | N |
| `PING_PONG` | NORMAL offsets + triangle phase remap for bounce | N |
| `RANDOM(seed)` | Deterministic Fisher-Yates shuffle of evenly-spaced offsets | N |
| `POSITIONAL` | Based on normalized position | N |

**Symmetric strategies** (CENTRE_OUT, CENTRE_IN) have fewer distinct offset slots
than group members because symmetric pairs share the same offset. A windowed step chase uses
`numDistinctSlots` for window width to ensure gap-free chases.

**`CENTRE_IN` is the one Order from the edges in** (terminology plan D18).
`SPLIT` ranked a member `min(i, N−1−i)`, the same distance from the nearer end as Centre out's rank
inverted, so it gave Centre in's offsets at every group size and was folded into it: the parse no longer
knows it, and database steps 4 and 5 and `sync/LegacyNames.kt` rewrite a stored one.

### The spread's shape (effects plan session 2)

The strategy is the **Order** (§3.1 of the effects plan — renamed in the UI only): which head comes
first. Four more fields shape what the Order gives — **Spread**, **Wings**, **Blocks** and
**Groups** (D4) — and with the Order they are the spread. All four are `FxDynamics` fields, edited
in place as the Order is (an edit keeps the phase), stored on the four effect homes beside it
(`look_fx`, `cue_ad_hoc_fx`, `palette_fx` as `spread` and `spread_wings` /
`spread_segments` / `spread_parts` — the prefix because `GROUPS` was an SQLite keyword — and a live instance), carried by
`LookEffectSpec`, `StoredEffect` and `LookEffectSettings` so Record, Include and Update keep them, and
synced from `formatVersion` 33 (`docs/sync-engineering.md` §"Version 33").

`DistributionPlan.of` runs §3.3 over each member, after the Order (step 1):

1. **Order** gives the member a rank `r` over `R` ranks: `DistributionStrategy.ranks`, the offset
   times `distinctSlots` rounded to the whole number it is — except `POSITIONAL`, whose rank is its
   position along the group times `N` (continuous, so its offset is unchanged at Spread alone).
   Blocks, Wings and Groups count whole ranks, so whenever one of them acts
   (`SpreadShaping.countsRanks`) the plan takes `DistributionStrategy.wholeRanks` instead, which
   snaps Position's to the nearest of its `N` slots, `round(position × (N − 1))` — evenly spaced
   heads rank 0 … N − 1 as under Rig, so two wings mirror and groups repeat whole slots. Under Spread
   alone Position stays continuous.
2. **Blocks** `B`: `r ← floor(r / B)` over `ceil(R / B)` ranks (`ceil(N / B)` under an asymmetric
   Order, whose rank count is the member count): `B` neighbours **in the Order** share one phase —
   on the rig under Rig, Reverse and Position, and under Random heads beside each other in the
   shuffle, so a Random in blocks of 2 is random pairs.
3. **Wings** `W`, only under an asymmetric Order — Centre out and Centre in are two mirrored
   wings already (`DistributionStrategy.isMirrored`): the ranks split into `W` runs of
   `m = ceil(R / W)`; an odd run is mirrored **within its own length** (`r ← len − 1 − r`, `len` being
   `m` for every run but a short last one, so the outermost member of each wing pair shares a rank —
   seven heads in two wings are 0 1 2 3 2 1 0); over `m` ranks.
4. **Groups** `G`: `r ← r mod G` over `G` ranks — when `G` is below the rank count; otherwise the
   ranks stand, so the slot count never exceeds what the members fill. `G = 0` is off.
5. **Spread** `s`, 0–2: `offset = s × r / R`. 100 % is the Order's own; 50 % packs the heads into
   half a cycle (a tighter ripple); 200 % runs them over two.

`distinctSlots` is the rank count after step 4 (`DistributionPlan.slots`, each context's
`numDistinctSlots`, and `FxDynamics.distinctSlots` — the same count in constant time, for the
per-pass reads), so a step chase's window and step timing stay gap-free: groups of 2 over eight heads
are two slots, the odd heads and the even heads taking turns a step each. `PING_PONG`'s turnaround is
**not** shaped: its sweep is the Order's own `(N − 1) / N` (§"Phase Calculation"), the shape setting only
the offsets. At the default shape (100 %, 1, 1, 0) the
plan does not run the shaping at all, so an effect that names no shape keeps its offsets bit for
bit (P6). The plan cache (`FxDistributionPlans`) is keyed on the shape as on the Order.

**N-shot ends N cycles past the largest member offset — the shaped one.** A member's own clock is
the effect's less its offset, so under Spread 50 % a 2-shot over four heads (offsets 0, ⅛, ¼, ⅜)
hands each head back after its own two and is spent two cycles past ⅜; under Groups of 2 (offsets 0,
½, 0, ½) two cycles past ½. Size is untouched by the shape: it weighs the apply, after the blend.

**The rule is the desk's** (P2). `frontend/src/lib/spreadShape.ts` is the editor's one copy, for its
phase-dial preview only, and `src/test/resources/fx/spreadShape.fixture.json` — generated from an
independent reference of §3.3 and read by `SpreadShapeTest` and `spreadShape.test.ts` — pins the two
to the same offsets, Random's shuffle included.

- **Refused at the write boundary** (`EffectSpecCoercion.Strict.spreadProblem`, inside `problem()`
  where the Order is known): Spread outside 0–2, Wings or Blocks below 1, Groups below 0, any count
  above 512, and **Wings above 1 under a mirrored Order**. `PUT /fx/{id}` checks the shape against
  the Order the effect will run under — the request's, or the one it has — so moving a winged effect
  onto Centre out is refused as surely as sending wings to one. The instance's **own** wings take
  part only when the request moves the Order: a stored row may hold wings under a mirrored Order
  (lenient on spawn, not run), and an edit of its Spread, Blocks or Groups — or a frame re-sending
  the Order it already has — is not refused for them. A stored value out of range is
  clamped with a warn on spawn (`Lenient.spreadShape`), and stored wings under a mirrored Order are
  left as stored and not run.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `spread`, `wings`, `blocks` and
  `groups`; `PUT /fx/{id}` and `updateFx` take each with null meaning keep (`groups: 0` turns them
  off). `EffectDto` and the group and indirect DTOs report them. The MCP effect schemas take the four
  (`AiToolSchemas.putEffectDynamics`), and `describe_rig` and `get_current_state` say them where they
  are not the default.
- **The editor** (D11, §4.1): a *Spread* disclosure in `FxEffectFields` — Order (the Distribution
  select, moved out of Advanced), Spread %, Wings (hidden under a mirrored Order, and set back to 1
  when one is chosen), Blocks, Groups, and a row of phase dials over eight notional heads on an effect
  palette and the effect's own heads live (up to sixteen, then *+n*). Its summary says the
  non-defaults: *Rig · 50 % · 2 wings*.

**Strategy interface properties:**

| Property | Description |
|----------|-------------|
| `hasSpread` | Whether offsets differ between members (false only for UNIFIED) |
| `usesTrianglePhase` | Whether the base clock should be triangle-remapped (true for PING_PONG) |
| `distinctSlots(groupSize)` | Number of unique offset positions for a given group size |
| `ranks(members, groupSize)` | Each member's rank over `distinctSlots` ranks — what the spread's shape works on |
| `isMirrored` | Two mirrored wings already (CENTRE_OUT, CENTRE_IN) — Wings is refused under it |

## Multi-Element Fixture Expansion

When a fixture effect targets a property that the parent fixture doesn't have, but its elements do (e.g. applying a colour FX to a `QuadMoverBarFixture` whose heads have `WithColour` but the parent does not), the FX engine automatically expands the effect to all elements.

This makes multi-element fixtures behave like implicit groups for FX purposes, without requiring the user to manually create a fixture group.

```
┌─────────────────────────────────────────────────────────────────┐
│              Multi-Element Effect Expansion                       │
│                                                                  │
│   FxInstance (fixture target, e.g. "quad-mover-1")               │
│        │                                                         │
│        ▼                                                         │
│   Parent has target property?                                    │
│     ├─ YES → Apply directly to parent (normal behaviour)         │
│     └─ NO  → Is parent a MultiElementFixture?                    │
│               ├─ NO  → Skip (silent no-op)                       │
│               └─ YES → Do elements have the property?            │
│                         ├─ NO  → Skip                            │
│                         └─ YES → Expand to all elements:         │
│                                   For each element:              │
│                                     1. Create DistributionInfo   │
│                                     2. Calculate phase + offset  │
│                                     3. Apply via element key     │
└─────────────────────────────────────────────────────────────────┘
```

### Element Key Resolution

Element keys follow the convention `"parent-key.suffix"` (e.g. `"quad-mover-1.head-0"`). The `Fixtures.untypedGroupableFixture(key)` method resolves these by:

1. Checking the fixture register for a direct match
2. If not found, splitting on the last `.` to find the parent key
3. Checking if the parent implements `MultiElementFixture`
4. Searching its elements for a matching `elementKey`

### Distribution Strategy Support

Multi-element expansion uses the same `DistributionStrategy` machinery as group effects. The `distributionStrategy` field on `FxInstance` (which defaults to `NORMAL`) is used to calculate per-element phase offsets based on element index and normalized position.

The REST API `AddEffectRequest` includes an optional `distributionStrategy` field for fixture effects targeting multi-element fixtures.

### Example

```
POST /api/rest/fx/add
{
  "effectType": "RainbowCycle",
  "fixtureKey": "quad-mover-1",
  "propertyName": "rgbColour",
  "distributionStrategy": "NORMAL"
}
```

This applies a rainbow cycle to all 4 heads of the quad mover bar, with each head offset in phase to create a chase effect across the heads.

## REST API

### Clock Control

There are no `/fx/clock/*` endpoints. Tempo is project-scoped, with the masters: `PUT`
below is the "typed a number into a form" path — it sets the stored default *and*, when the
project is live, retunes the running clock. Knob-drag and tap go over
`speedMasters.setBpm`/`.tap` instead, which never touch the stored value. Master CRUD is
project-scoped too:

```
GET    /api/rest/projects/{id}/speed-masters        → [SpeedMasterDto...]  (lazily seeds 4)
GET    /api/rest/projects/{id}/speed-masters/{mid}  → SpeedMasterDto
POST   /api/rest/projects/{id}/speed-masters        ← { name?, bpm?, notes?, usage?, followNum?, followDen? }
PUT    /api/rest/projects/{id}/speed-masters/{mid}  ← same keys, patch semantics (present keys change;
                                                      the follow pair must travel together)
DELETE /api/rest/projects/{id}/speed-masters/{mid}  → 409 SPEED_MASTER_PROTECTED (master 1)
                                                     | 409 SPEED_MASTER_IN_USE (?force=true overrides)
```

Usage/follow refusal codes (see §"Usage routing and follow"): `SPEED_MASTER_INVALID` (400 —
malformed usage or ratio, half-sent pair), `SPEED_MASTER_CANNOT_FOLLOW` (400 — ratio on master 1),
`SPEED_MASTER_USAGE_TAKEN` (409 — another master already holds that usage), and
`SPEED_MASTER_FOLLOWER` (400 — a `bpm` sent for a follower, on POST and PUT alike; the tempo is
derived, so retune master 1 or unlink first).

### Effect Management

```
GET    /api/rest/fx/active           → [EffectDto...]
POST   /api/rest/fx/add              ← AddEffectRequest → { effectId }
PUT    /api/rest/fx/{id}             ← UpdateEffectRequest → EffectDto | EffectSplitResponse (with targets naming some heads)
DELETE /api/rest/fx/{id}             (remove effect)
POST   /api/rest/fx/{id}/pause
POST   /api/rest/fx/{id}/resume
GET    /api/rest/fx/fixture/{key}    → { direct: [EffectDto...], indirect: [IndirectEffectDto...] }
DELETE /api/rest/fx/fixture/{key}    (clear fixture effects)
POST   /api/rest/fx/clear            (clear all effects)
```

`EffectDto` lives in `fx/EffectDto.kt` and is the *only* effect report on any transport — the
`fxState` WebSocket frame carries the same objects. Sweep item F8 collapsed the two, so these
REST responses gained the three fields that used to be WebSocket-only: `cueStackId`,
`speedMasterIndex` and `rateSpeedMasterIndex`. Nothing was removed, and `effectType` is
`FxInstance.effectTypeId` on every transport now (the WS frame used to send the display name).
Build one with `FxEngine.effectDtos` / `FxEngine.effectDto` rather than calling `toEffectDto`
directly — the engine owns the speed-master snapshot and the multi-element answer it needs.

### Effect Library

```
GET  /api/rest/fx/library          → [EffectTypeInfo...]
```

### AddEffectRequest Format

```json
{
  "effectType": "SineWave",
  "fixtureKey": "front-wash-1",
  "propertyName": "dimmer",
  "beatDivision": 1.0,
  "blendMode": "OVERRIDE",
  "startOnBeat": true,
  "phaseOffset": 0.0,
  "parameters": {
    "min": "0",
    "max": "255"
  },
  "distributionStrategy": "NORMAL",
  "stepTiming": true,
  "speedMasterUuid": "7d444840-9dc0-11d1-b245-5ffdce74fad2",
  "rateSpeedMasterUuid": null
}
```

The `speedMasterUuid` field is optional (null → master 1). It is a *uuid*, not the int id,
so a stored reference survives project clone and import. `rateSpeedMasterUuid` is its
wall-clock counterpart (null → unscaled); both are accepted on every effect and simply go
unread by the timing source that does not apply.

The `distributionStrategy` field is optional. When provided, it sets the distribution strategy for multi-element fixture expansion (see [Multi-Element Fixture Expansion](#multi-element-fixture-expansion)). For non-multi-element fixtures, it is ignored.

The `stepTiming` field is optional. When provided, it overrides the effect's default step timing mode. See [Step Timing](#step-timing).

`targets` (`[{"type": "fixture" | "group", "key": "…"}]`) replaces `fixtureKey` for one effect over a
selection, spread in that order ([Over a selection](#over-a-selection-effects-plan-session-3)): one
item is that fixture or group alone, an unknown item is a 400 naming it, and a list none of whose
heads has the property is refused. The distribution and element fields then apply as to a group.

### UpdateEffectRequest Format

```json
{
  "effectType": "Pulse",
  "parameters": { "min": "50", "max": "200" },
  "beatDivision": 2.0,
  "blendMode": "ADDITIVE",
  "phaseOffset": 0.25,
  "distributionStrategy": "CENTRE_OUT",
  "elementMode": "FLAT",
  "stepTiming": false,
  "rateSpeedMasterUuid": "7d444840-9dc0-11d1-b245-5ffdce74fad2",
  "seconds": false
}
```

`targets` (`[{"type": "fixture" | "group", "key": "…"}]`) says which heads the edit is for: absent, or
naming every head the effect paints, is the in-place edit below; naming some splits them off and
answers `EffectSplitResponse` instead — see [Local effects follow the selection](#local-effects-follow-the-selection-apply-model-plan-session-2).

All fields are optional. Truly immutable fields (`effectType`, `parameters`, `beatDivision`,
`blendMode`) trigger an atomic swap of the `FxInstance`, preserving id, start time, and — because
the dynamics cell is shared across the swap (see "Instance dynamics" above) — the running state
and any concurrently racing pause/resume. Dynamics fields (`phaseOffset`, `distributionStrategy`,
`elementMode`, `elementFilter`, `stepTiming`) are updated in place as **one** atomic snapshot
replacement, so a tick pass never observes half of an update. `speedMasterUuid` /
`rateSpeedMasterUuid` are also updated in place; both follow the same null-means-no-change
convention, and to return an effect to the default, send master 1's uuid (master 1 always exists
and behaves identically to the null default). `seconds` moves the effect between *Seconds* and a
master — see [Seconds](#seconds--an-effects-own-cycle-venue-visit-plan-d7) — and is the only way to
put a wall-clock effect back to unscaled. `size`, `direction` and `nShot` are dynamics fields, edited
in place with the phase kept; `runForever: true` clears a count, which a null `nShot` (no change)
cannot say. See §"Size, direction and N-shot".

## WebSocket Messages

### Client → Server

| Message | Description |
|---------|-------------|
| `fxState` | Request the current active-effect list |
| `speedMasters.state` | Request the full masters bank |
| `speedMasters.setBpm` | Set one master's BPM `{ masterUuid?, bpm }` (uuid omitted → master 1) |
| `speedMasters.tap` | Tap one master's tempo `{ masterUuid? }` |
| `removeFx` | Remove effect `{ effectId }` |
| `pauseFx` | Pause effect `{ effectId }` |
| `resumeFx` | Resume effect `{ effectId }` |
| `clearFx` | Clear all effects |
| `speedMasters.requestBeat` | Ask for one immediate beat frame — `{ masterUuid? }`, omitted uuid = master 1 |

### Server → Client

| Message | Description |
|---------|-------------|
| `fxState` | The active-effect list `{ activeEffects }`, each entry the shared `EffectDto` (`fx/EffectDto.kt`) that `GET /fx/active` also returns — so it carries `speedMasterUuid`/`speedMasterIndex` and `rateSpeedMasterUuid`/`rateSpeedMasterIndex` alongside the full REST field set. No tempo — it used to carry `bpm`/`isClockRunning` for master 1 |
| `fxChanged` | Effect change notification `{ changeType, effectId }` |
| `speedMasters.state` | Full bank `{ masters: [{ uuid, index, name, bpm, isRunning, source }] }` — sent on connect, on request, and as the reply to every `speedMasters.*` write |
| `speedMasters.changed` | One master's tempo moved `{ masterUuid, index, bpm, source, timestampMs }` — the live-BPM stream; CRUD invalidation goes via `speedMasters.listChanged` instead |
| `speedMasters.beat` | One master crossed a beat boundary `{ masterUuid, index, beatNumber, bpm, timestampMs }` — every master, master 1 included |
| `speedMasters.listChanged` | A master was created/renamed/deleted (cache-invalidation signal, never fired per tempo change) |

### Beat Sync

`speedMasters.beat` lets the frontend synchronize a local beat visualization with a
master's clock. It is sent:

- Every 16 beats (~8 seconds at 120 BPM) for periodic drift correction
- On-demand when the client sends `speedMasters.requestBeat` for that master
- On the first beat after that master's tempo moves — the throttle assumes the local timer is
  merely drifting, and a retune makes it wrong about the rate instead. Without this the dot
  goes on flashing at the old tempo for up to 16 beats while the BPM readout beside it has
  already changed

Between frames the client free-runs a local timer off the last `bpm` it was told, and each
frame re-aligns it. That interpolation is load-bearing, and was not always exercised: the
retired `beatSync` had a request flag set at construction and never cleared, so its throttle
was permanently bypassed and a frame went out on *every* beat. `speedMasters.requestBeat`
consumes its request (`pendingBeatRequests.remove`), so the throttle is real.

Frames are fanned from `SpeedMasterBank.beats` — one flow for the whole bank, fed by a
per-clock `onBeat` hook wired where `onTick` already is. A subscriber binds once and keeps
working across a `load()`, because the tagging happens at emit time rather than being
captured into a per-master collector.

**Outbound, master 1 is its real uuid — not null.** Inbound messages all take a null
`masterUuid` to mean master 1, for a client with no uuid to hand. But beats are *tagged* from
the bank entry, and after the first `load()` master 1's entry holds its row uuid; null appears
only for the synthetic pre-load master. So a client can ask about master 1 with a null uuid,
and must not expect to recognise its frames by one: anything matching frames on master 1's
behalf resolves the real uuid first (`useMaster1Uuid` in `store/speedMasters.ts`). Subscribing
with null would silently never match. This bit when `BeatIndicator` moved off `beatSync`,
where the mismatch had been invisible because master 1 was the one master never using the
keyed stream.

`speedMasters.requestBeat` is the inbound half of that rule, and resolves an omitted uuid to
`SpeedMasterBank.master1Uuid()` rather than parking `null` — the pending set is matched against
the tagged frame, so a parked null would be an unsatisfiable request that also never leaves the
set. Every client hits this: an indicator mounts before its master-1 lookup resolves.

## File Reference

| File | Purpose |
|------|---------|
| `dmx/EasingCurve.kt` | Easing curve implementations |
| `dmx/ChannelChange.kt` | DMX change with curve support |
| `dmx/TickerState.kt` | Curve-aware interpolation |
| `fx/MasterClock.kt` | One speed master's tempo clock (deadline timer, tap, pure `phaseForDivision`) |
| `fx/SpeedMasterBank.kt` | Per-show bank of master clocks, wake channel, per-pass `Frame` |
| `models/speedMasters.kt` | Exposed DAO for speed_masters table + default-bank seeding |
| `routes/projectSpeedMasters.kt` | Speed-master CRUD API endpoints + delete guards |
| `plugins/SpeedMasterSocket.kt` | `speedMasters.*` WS family (live tempo control/streaming, keyed beat frames) |
| `fx/BeatDivision.kt` | Timing constants |
| `fx/Effect.kt` | Effect, StatefulEffect interfaces, FxOutput types |
| `fx/FxRegistry.kt` | Unified effect registry, EffectRegistration, ParameterInfo, EffectTypeInfo |
| `fx/FxFileLoader.kt` | Loads and parses .fx.kts files from resources |
| `fx/TypedParams.kt` | Typed parameter accessor for FX scripts |
| `fx/ScriptEffectAdapter.kt` | Bridges compiled FX scripts to Effect interfaces |
| `fx/FxScriptCompiler.kt` | Compiles and caches FX calculate scripts |
| `fx/EffectParamUtils.kt` | Parameter parsing utilities (parseExtendedColour, toUByteParam, etc.) |
| `fx/FxInstance.kt` | Running effect state, distributionStrategy, ElementMode, speed-master refs |
| `fx/FxTarget.kt` | Fixture/group property targeting, FxTargetRef |
| `fx/FxBlend.kt` | Blend and weight as pure functions, written through by every target and folded by the sampler |
| `fx/FxPhase.kt` | A member's phase as a pure function of one clock reading, its dynamics and its Runs anchor |
| `fx/FxSampler.kt` | Evaluates FX off the stage over a window, for the FX editor's graph (§"Previewing an FX") |
| `fx/FxTargetable.kt` | Common interface for Fixture and FixtureGroup |
| `fx/FxEngine.kt` | The compose pass and its wake sources, active-effect set, group expansion (post-E1 split) |
| `fx/CascadePublisher.kt` | Layer-4/cascade → controller transmit, the Layer 4 publish lock, channel-covering-key lookup |
| `fx/CueAssignmentLayer.kt` | Per-cue Layer 4 assignment bookkeeping, within-cue stomp registry |
| `fx/ProvenanceService.kt` | "Who owns this value" computation + broadcast, `underlyingSources` |
| `fx/ProgrammerWriter.kt` | PROGRAMMER-layer write delegation (write/clear/blind/republish) |
| `fx/FxLogThrottle.kt` | Per-cause throttled logging shared by the compose pass and publish paths |
| `fx/FxExtensions.kt` | Script DSL helpers |
| `fx/group/DistributionStrategy.kt` | Phase distribution strategies |
| `fx/group/GroupFxExtensions.kt` | Group effect extension functions |
| `resources/fx/**/*.fx.kts` | Every built-in effect's metadata + calculate body (30 files) |
| `fx/TemplateColourSource.kt` | Colour-palette resolution, `TemplateSource` (also Palette Steps' palette lookup), and `createEffectWithTemplates` — the one spawn helper every string-named effect goes through |
| `fx/StepList.kt` | The step effects' lists, resolved per head (`SlotStepList`, `PaletteStepList`), `stepsAt` and `paletteStepsShape` |
| `fx/TemplateRegistry.kt` | Palette snapshots cached by uuid — rows, or the one effect an effect palette holds; the scoped version a running effect's colour cache watches |
| `fx/EffectSpawner.kt` | Stored effect spec → live `FxInstance` on a live `FxTarget`; the one spawn path for cues, triggers, the programmer stack and Include |
| `fx/CueApply.kt` | The cue-apply domain: `CueApplyData` and its builders, cue-derived priority, Layer 4 row construction, stomp overlap |
| `fixture/trait/WithPosition.kt` | Position trait for moving heads |
| `fixture/trait/WithDimmer.kt` | Dimmer trait |
| `fixture/trait/WithColour.kt` | Colour trait |
| `fixture/trait/WithUv.kt` | UV trait |
| `fixture/group/GroupExtensions.kt` | Group property extensions |
| `scripts/ScriptType.kt` | Script type enum (GENERAL, FX_APPLICATION) |
| `scripts/scriptDef.kt` | LightingScript base class (GENERAL scripts) |
| `scripts/fxCalcScriptDef.kt` | FxCalcScript, FxStatefulCalcScript base classes |
| `scripts/fxApplicationScriptDef.kt` | FxApplicationScript base class (effect application) |
| `models/fxDefinitions.kt` | Exposed DAO for fx_definitions table |
| `routes/lightFx.kt` | FX REST API endpoints |
| `routes/fxDefinitions.kt` | FX definitions CRUD API endpoints |
| `routes/lightGroups.kt` | Group REST API endpoints |
| `plugins/Sockets.kt` | WebSocket message handlers |

## Threading Model

| Component | Thread | Notes |
|-----------|--------|-------|
| MasterClock (one per speed master) | `Dispatchers.Default` | Tick generation; each tick nudges the bank's conflated wake channel |
| FxEngine wake sources | `Dispatchers.Default` | Two coroutines: one forwards the bank's wake, one is the 20 ms ticker; each sets its flag and wakes the pass |
| FxEngine compose pass | `Dispatchers.Default` | One coroutine running one `composePass` per wake, at most every 5 ms, under `passLock` |
| DMX output | Per-universe coroutine | ArtNet transmission |
| REST handlers | Ktor I/O | Request handling |
| WebSocket handlers | Ktor WebSocket | Message handling |

### Target expansion is cached, not re-derived

Everything on this page about how a target expands — group members, `MultiElementFixture`
elements, the `PER_FIXTURE`/`FLAT` split, `ElementFilter` — is resolved **once per effect per
register generation**, into an `FxTargetExpansion` held on the `FxInstance`. The compose pass reads
it; it does not walk the group.

They used to walk it twice per tick per effect: once in `resolveEffectFixtureKeys` for the reset
pass, and again in the `process*` pass, with every `untypedGroup` / `untypedFixture` lookup taking
`Fixtures.registerLock.read`. A 240-element group effect cost ~480 lock acquisitions and two
fresh key lists per tick, at up to 120 Hz.

This is safe because the topology is frozen between rebuilds: `FixtureGroup.members` is an
immutable list, `allMembers` is `by lazy`, and `MultiElementFixture.elements` is bound in each
fixture's constructor. The only thing that changes any of it is `Fixtures.register {}`.

**Invalidation is pull-based, not a listener.** `Fixtures.structureVersion` is bumped inside the
register write lock (and, redundantly but cheaply, by `patchListChanged()`); the version is
stamped *inside* the cached value and re-checked on every read. That is deliberately unlike
`LookRegistry`, whose fast path returns a hit without re-validating and therefore needs a
fill-time re-check, a retry loop and `putIfAbsent` — here a value built across an invalidation is
simply rebuilt on the next read. `elementFilter` is part of the cache identity; `elementMode` is
not, because both shapes it selects between are built up front.

The consequence to know about: an effect whose group or fixture has been deleted now logs
"not found" **once per register generation** rather than on every tick.

`FxExpansionCacheTest` is what stands behind the invalidation. `FxEngineBenchmark`'s setup guards
do not — they only ever check the first expansion.

### Tick-path failures

Everything on the tick path logs through SLF4J (`logger` in `FxEngine.kt`), never `System.err`,
and every tick-path log line goes through `FxLogThrottle`: one line per distinct fault per
`LOG_THROTTLE_NANOS` (10s), with the repeats counted into the next line that gets through. Without
that, a fault on a 120 Hz pass writes thousands of lines a minute and buries the rest of the log —
and the fault worth reading about is usually the *first* one. Two rules for throttle keys:
they are compared on `nanoTime`, because an NTP step backwards on a wall clock would silence a key
until real time caught up; and they are drawn from something the **rig** bounds (a fixture key, a
group name, an effect type — never an effect id, which is a monotonic counter), because nothing
ever sweeps the map.

Three levels of containment, outermost first:

1. **The pass loop** (`start`) catches around each pass. Anything escaping there would cancel the
   loop's job for the rest of the process — the desk's effects would stop for good, with one line
   on the uncaught-exception handler. `CancellationException` is rethrown so `stop()` still works.
2. **Each effect's pass** is caught individually, so one bad effect doesn't cost the others their
   tick.
3. **An effect that fails `MAX_CONSECUTIVE_TICK_FAILURES` (120) passes in a row is paused** —
   `noteTickFailure`. A script effect that throws on every `calculate` is not going to come good
   on the next tick; pausing keeps the instance (phase, parameters, its row in the sheet) so the
   operator can fix the definition and hit resume, logs the pause at `error` with the exception,
   and shows up in the UI through the `isRunning` that `fxState` already streams. The threshold is
   a pass count, not a duration, so it trips faster the faster the desk is ticking — the safe
   direction, since a fault that clears itself (a fixture missing for the length of a patch
   reload) gets more real time to clear on the slower paths. Only passes the effect's **own**
   clock woke count, failing or good (a beat effect's: any master's tick; a wall-clock effect's: the
   ticker), so a beat effect's grace period does not shrink because the ticker also wakes the
   pass; a failure on another pass is logged, not counted. One good pass clears the run.

Levels 2 and 3 catch `Throwable`, not `Exception`: every built-in effect is a compiled script, so a
recursive helper in one (`StackOverflowError`) or a script class that fails to link
(`NoClassDefFoundError`) raises an `Error`, and that must not be the thing that takes the pass loop
down for the process.

Pausing also **resets the effect's properties to the layer below**, into the pass's open
transaction. An effect can die part-way through its own targets — a group effect that throws on
member 7 has already painted 0..6 — and from the next pass on a paused effect is skipped by
`resetActiveProperties`, so a half-applied frame would sit frozen with nothing left to move it. A
*manual* pause deliberately freezes instead: that frame is one the operator chose.

`FxTickFailureTest` covers the pause rule, the reset, and the run-clearing.

The reset pass (`resetOne`) is the one deliberate exception to "log it at warn": it logs at debug,
because the effect's own apply is about to fail on the same missing fixture and report it properly.

## Cue Integration

A cue is an ordered stack of **Look layers** plus its own local values and ad-hoc effects. The FX system supports cues via the `cueId` field on `FxInstance`.

### FxInstance.cueId

Each `FxInstance` has an optional `cueId: Int?` field (default `null`). When a cue comes on (its first
hold, `fx/CueHolds.kt`), every effect it creates is tagged with the cue's database ID. This enables:

- **Taking a cue off**: its last hold going removes its effects by `cueId` — never by stack — or, on
  a GO with a fade, sets them shrinking to nothing (leaving) for the engine to remove at 0. A cue
  brought back on before that finds its own leaving instances by `cueId` and takes them back.
- **Identification**: Effects created by a cue can be distinguished from manually applied effects (which have `cueId = null`).

A layer's effect also carries `layerEffectUuid`, the stored Look or palette effect row it realises:
what a GO matches a continuing effect on (effects plan P4), so the incoming cue adopts the outgoing
cue's instance — retagged to the incoming cue, id and phase kept — instead of fading one out and
another in. A cue's ad-hoc effect carries its own row's uuid there, which no other cue holds, and
its row's run key in `runUuid` (stack-timeline plan D7): a GO hands the outgoing cue's instance with
the same key, target and property to the incoming row, restamped with that row's uuid so *Update
stage* still reconciles by row. When the two rows' settings differ, the instance is edited in place
at the GO (`routes.alignEffect`, the same edit *Update stage* makes, injected into `CueHolds` as an
`EffectAligner` so `fx/` does not import `routes/`): phase kept, except that a move between Seconds
and a master restarts it. The `cueId`, `layerEffectUuid`, `runUuid` and envelope are preserved across
atomic swaps in `FxEngine.updateEffect()`, so updating an effect's parameters doesn't lose its cue
association, its run or its fade.

### Layers

A cue layer stores a **reference** to a Look or a palette (by id), and a palette's layer the
targets it operates over; a Look's names none, since a Look lands whole. At apply time the Look is
read fresh through `LookRegistry`, so editing a Look is reflected the next time the
cue is applied — and, for a *live* cue, immediately, via `republishForLookEdit`. Each layer's effects
are created as `FxInstance`s tagged with the cue id.

**Effects spawn in layer order, and that alone is enough to make layer order the composition
order.** `sortedEffectsComparator` is `compareBy(priority, composeOrder, id)` with `composeOrder`
the monotonic creation counter (a split copy keeps its parent's, §"Local effects follow the
selection"), and per-tick composition is a genuine sequential fold through `FxTarget.applyValue`, so
same-priority effects already resolve last-created-wins. No priority arithmetic is needed and the
uniform per-cue priority stays. A palette's effect fans over the layer's targets; a Look's runs on
its own targets, whole.

The one limit worth stating: effects are Layer 3 and static values are Layer 4, so an effect sits
above a static value regardless of layer order. See `lighting-composition-model.md`
§"Effects, and the constraint that cannot be layered away".

### Ad-Hoc Effects

Effects belonging to no Look are stored as full effect definitions in the `cue_ad_hoc_fx`
table. At apply time these are converted directly to `FxInstance`s tagged with the cue id.

**A cue effect is edited in draft** (show-cue-editing plan session 6, D17): the cue editor's FX tab
edits the stored row, never an instance. Its fields are the live editor's (`FxEffectFields`) over a
draft of the stored effect (`cueEffectDraftOf` / `withCueEffectDraft` in the frontend's
`fxEditorModel.ts`, so Seconds, size, direction, runs and the spread's shape round-trip as a
palette's effect does), and every change is the cue's whole `adHocEffects` list on its `PATCH`.
*+ Effect* runs the picker in draft: a tap stores the effect with the picker's starting spec —
`startingSpec`, the step effects' seeded lists, D16's one bar on the usage master — on the editor's
own selection (one row as itself, several as one `selection` effect, its key the list's canonical
JSON), and creates no instance; a second tap swaps the stored type. Nothing reaches the stage until
the cue next comes on — its first hold spawns what the row then says — or *Update stage* sends it: a
cue PATCH republishes nothing, and marks a held cue *edited since GO* when it changes an untimed
effect (`docs/cue-stacks-engineering.md` §"Edited since GO and Update stage"). A reorder restates
every `sortOrder`, and `writeCueChildren` keeps each effect's uuid by its key (target, property,
type), which is how *Update stage* tells an edited effect from a new one.

### Look Delete Blocking

A Look cannot be deleted if any cue layer references it via `cue_layers` — a plain indexed FK query,
where the named-palette era could only scan opaque `value` text. The Look detail API includes `layerCount`
and the referencing cue names; the delete returns 409 with code `LOOK_IN_USE`, and `?force=true`
deletes the layers with it. A forced delete that took layers rebuilds the live cues that held them
the way a Look edit does (`republishForLookDelete`, the same body as `republishForLookEdit` given the
cue ids the delete collected before the rows went), so the Look's values leave the stage without a
re-fire — its rows, as for an edit: effects the cue spawned from the Look at GO run until the cue
stops; it announces those cues, live and dark, through `cuesRecomposed`, and fires `cueListChanged`
and `cueStackListChanged`, whose layer counts moved.

### From-State Capture

The "create from current state" operation captures the live FX engine state:

1. Effects from a Look or a palette are grouped **by that record** into cue layer rows. A palette's
   row collects their targets, so one applied twice to different targets collapses into one layer
   covering both — the honest reading of a *snapshot*, since the stage cannot tell you it was two
   gestures. A Look's row names no targets: it lands whole.
2. Effects with a null `lookId` are stored as individual `CueAdHocEffect` rows with all effect fields
   captured.

Two things a snapshot **cannot** recover, because an `FxInstance` never carried them: a layer's
`blendMode`, `amount` and `propertyMask`, which take their DTO defaults. When the operator wants the
stack's own amounts and masks preserved they Record `TOUCHED`, which reads `ProgrammerStore.layers`
directly rather than reconstructing from the stage.

This used to key on an `FxInstance.presetId` field. Session 3a added a separate `lookId` precisely
because the two were ids in *different* tables — reading a preset id as a look id reconstructed a
cue naming whatever `DaoFxPreset` happened to share the number — and nothing stamped `presetId`
after that, so it was deleted.

What an `FxInstance` *does* carry, beyond `lookId`: `registrationId`, the canonical
`EffectRegistration.id` it was built from. Any "same effect type?" test reads that. The tempting
`effect.name.replace(" ", "")` is a registration id only for built-ins, whose display name is their
id with spaces in it; a user-defined FX definition sets `id` independently of `name`.

Anything that *persists or reports* an effect type — a recorded Look or cue child, an API DTO the
client hands back on Update — goes through `FxInstance.effectTypeId`, which is `registrationId` with
the display name kept only as the last resort for a script-constructed effect that never went
through the registry. Writing the display name directly stores a string the registry cannot resolve,
and the failure surfaces much later, when the row is applied.

### Related Files

| File | Cue-related content |
|------|-------------------|
| `fx/FxInstance.kt` | `cueId` field |
| `fx/FxEngine.kt` | `cueId` preservation in `updateEffect()` |
| `models/cues.kt` | `DaoCues`, `DaoCueLayers`, `DaoCueAdHocEffects` tables |
| `routes/projectCues.kt` | Cue CRUD, apply, from-state endpoints |
| `models/looks.kt` | `DaoLooks`, `DaoLookRows`, `DaoLookEffects` tables |
| `fx/CueComposer.kt` | The cook step, layer blending, layer-ordered effect spawning (a Look layer's effects and an effect palette's one, through the same fan-out) |
| `fx/LookRegistry.kt` | Cached per-fixture Look expansion; two invalidation triggers |
| `routes/projectLooks.kt` | Look CRUD, derived family banking, `LOOK_IN_USE` delete guard |

See `docs/cues-engineering.md` for full cue system documentation.

## Timing Source

Effects can run on one of two timing sources:

| Source | Description | Tick Rate | Phase Calculation |
|--------|-------------|-----------|-------------------|
| `BEAT` | Synchronized to the effect's speed master's ticks | 24 ticks/beat (variable) | From the master's tick counter via `MasterClock.phaseForDivision()` |
| `WALL_CLOCK` | Fixed-interval timer independent of BPM | 50Hz (20ms) | Accumulated wall-clock time since effect start, scaled per pass by an optional rate master |

### When to Use WALL_CLOCK

Use wall-clock timing for effects that should feel natural and not tied to music:
- Candle/fire flicker (organic randomness)
- Fluorescent tube flicker (intermittent failures)
- Ambient atmospheric changes (slow drifts)
- Any effect where beat-sync would feel unnatural

### Architecture: one compose pass

The FxEngine has two **wake sources** and one **compose pass** (`FxEngine.composePass`):

```
FxEngine
├── SpeedMasterBank.wake (CONFLATED; any master's tick) ─┐
├── 20 ms ticker ────────────────────────────────────────┤ each sets its flag
│                                                        ▼
└── composePass(beat, ticker, frame, now)   ← at most one per 5 ms, under passLock
    ├── reset every key a running effect covers to the layer below, once
    └── fold every running effect, beat and wall-clock, in apply order, into one transaction
        ├── BEAT: phase from frame.tick(effect.speedMasterSlot)
        └── WALL_CLOCK: phase from its accumulator, scaled by its rate master
```

A wake landing within 5 ms (`MIN_PASS_INTERVAL_MS`) of the last pass's start waits out the gap,
and every wake that lands meanwhile joins the same pass, which then knows **both** clocks woke
it. The pass is mutually exclusive (`passLock`): the production driver is one coroutine, and the
lock is what keeps a test's direct pass (`FxEngineTickShims`) off a running one.

Every pass paints every running effect, whichever clock woke it:

- **A beat effect** reads its master's tick from the pass's frame, which a beat-woken pass takes
  from the bank after any re-bind. A pass the ticker woke has no new frame and reuses the last
  beat-woken pass's (`lastFrame`): a master's tick is a pure function of its clock, so the effect
  is painted where the last tick left it. A bank membership change drops the held frame, and the
  next pass takes a fresh one.
- **A wall-clock effect** advances its accumulator by the real time since the last pass, whatever
  woke it. The deltas telescope, so the cycle is exactly as long however many passes there are;
  only its resolution improves.

A pass with nothing running on a clock that woke it is **skipped**: with only beat effects running,
a ticker-woken pass would repaint them at the tick they are already on, and with only wall-clock
effects running a beat wake adds nothing but cost. So a show running one kind of effect makes the
passes it made before; the time a skipped pass covers is advanced by the next one, and a skipped
pass does not hold the next wake back for the 5 ms gap.

What **counts** passes stays with the effect's own clock. The pass records which clocks woke it,
and:

- a **stateful** effect (`StatefulEffect`: Candle Flicker, Fluorescent Flicker, a user script)
  steps only on a pass its own clock woke, handed that clock's `deltaMs` and, on the wall clock,
  the ticker's pass number (`wallClockPassNumber`). On any other pass it repaints what it last
  answered on each key (`FxInstance.statefulOutputs`), so a Candle Flicker does not flicker faster
  under a faster master. A key it has never answered for steps whatever woke the pass, so an
  effect just added paints at once;
- the tick-failure count (§"Tick-path failures") counts only passes its own clock woke;
- an N-shot ends (`finishNShots`) only on a pass its own clock woke — on any other pass a member
  outside its runs simply holds what is underneath, as it does anyway. Its count starts on the
  first pass that paints it, whichever clock woke it: a beat N-shot's `nShotStartTick` is the
  master tick it was painted at, so a count given to a running beat effect starts from the tick the
  master is on rather than leaving the effect unpainted until the next one;
- the leaving sweep (`retireFinishedLeaving`) runs on ticker-woken passes, at 50 Hz whether or not
  anything is running.

The per-effect apply is **one code path**: `processFixtureEffect` / `processGroupEffect` /
`processElementKeys`, differing per effect only in the `PhaseSource` (`Beat` reads the master's
tick, `WallClock` the effect's own scaled accumulator). Expansion, distribution plans, programmer
suppression, Blind's hold (one read of Blind per pass) and parking are therefore one rule for both.

### Beat ↔ Wall-Clock interaction

A beat effect and a wall-clock effect on the **same** key **compose**, whatever the blend: one pass
resets the key once and folds both, in apply order, so a Candle Flicker *Within* (MULTIPLY) a Sine
Wave flickers inside the wave on every frame, and Add, Max and Min compose the same way.
`FxComposePassTest` pins it against committed DMX over a schedule of beat-woken, ticker-woken and
coalesced passes.

One pass is what makes this true. With a loop per clock, each reset the keys its own effects covered
and applied them in its own transaction, so on a shared key the loop that committed last won
whatever the blend: the head showed the Sine Wave or the Candle Flicker, alternating, never both.

One transaction per pass also means one write per frame per key: ArtNet output samples
`currentValues` once per `refreshIntervalMs`, and with one pass there is no second, half-composed
transaction for it to sample.

### Wall-Clock Phase Calculation

For wall-clock effects, `beatDivision` is reinterpreted as cycle duration in seconds. A
**rate master** (`FxInstance.rateSpeedMasterUuid`, null → unscaled) sets how fast that cycle
is consumed, at `master.bpm / 120`:

```kotlin
// once per compose pass, per effect, before any phase is read
accumulatedScaledMs += deltaMs * rateScale
// then, wherever a phase is needed
val cycleDurationMs = beatDivision * 1000.0
val phase = (accumulatedScaledMs % cycleDurationMs) / cycleDurationMs
```

The cycle length is **fixed** and only the accumulation rate moves. That is what makes a
mid-cycle rate change continuous: the earlier form divided a fixed `now - startedAtMs` by a
cycle length that moved under it, so retuning a rate master snapped the phase (3 s into a
4 s cycle read 0.75, and became 0.5 the instant the cycle halved). `WallClockTimingTest`
pins the continuity, and — since elapsed time is now an explicit parameter rather than a
clock reading — pins it exactly, with no jitter tolerance.

The rate master scales the effect's *internal cycle* only: cue-trigger scheduling
(`delayMs`/`intervalMs`/`randomWindowMs`) is deliberately never scaled by any master.

"Null → unscaled" needs a sentinel to be true. `SpeedMasterBank.slotFor(null)` is slot 0, which
is master 1, so binding a null `rateSpeedMasterUuid` through it made every unassigned wall-clock
effect follow master 1's tempo — exactly 1.0 at the default 120 BPM, and a rig-wide speed change
the moment anything tapped it. `FxInstance.rateMasterSlot` therefore defaults to
`FxInstance.NO_RATE_MASTER` (-1) and the engine binds it through `rateSlotFor`, not `slotFor`
(sweep item C6). The compose pass skips sampling `rateScales()` altogether when no wall-clock effect
holds a real slot, which is the usual case.

`rateSpeedMasterUuid` is settable everywhere `speedMasterUuid` is — REST add/update, look
effects, cue ad-hoc effects, per-application overrides, cue-edit, and sync. The two coexist
rather than excluding each other, so an effect whose `timingSource` changes keeps both
assignments; BEAT effects simply never read the rate scale. The MCP effect tools take both, and
*Seconds* beside them (below). **Scripts are the deliberate exception** — the script API has never
addressed a per-effect master's absence, so there was nothing to mirror.

For STATEFUL effects, `deltaMs` is computed from the wall-clock interval directly. These
effects already use `deltaMs` rather than phase, so they work naturally — and by the same
token a rate master has no effect on one that never reads its phase. Both shipped wall-clock
effects (`CandleFlicker`, `FluorescentFlicker`) are STATEFUL, so nothing in-tree currently
exercises rate scaling end to end; author a STANDARD + WALL_CLOCK definition to try it.

### Seconds — an effect's own cycle (venue-visit plan D7)

An effect's **timing** is one of two things: it follows a speed master, or it runs on *Seconds* —
its own cycle, with no master, for a slow breathe or drift that must not move when the tempo does.
It is one question for every effect, asked by one chip (`Seconds | M1 | M2…`), and stored as one
flag, `seconds`, on every effect home: a live `FxInstance`, an effect palette (`palette_fx`), a
Look's effects (`look_fx`) and a cue's ad-hoc effects (`cue_ad_hoc_fx`), with
`LookEffectSpec`, `StoredEffect` and `LookEffectSettings` carrying it between them. It syncs from
`formatVersion` 29. The rules are `fx/EffectTiming.kt`'s:

- **A beat effect on Seconds** reads its `beatDivision` as seconds and runs on the **wall-clock
  loop** with both masters null — `FxInstance.seconds` true, `timingSource` `WALL_CLOCK`,
  `rateMasterSlot` `NO_RATE_MASTER`. That is exactly an unscaled wall-clock effect, so neither loop
  learned anything; `applyTimingChoice` is the one writer before an instance reaches the engine, and
  `FxEngine.updateEffect`'s `newSeconds` the one after (a swap, since the loop changes).
- **A wall-clock effect's** division is seconds already. Its Seconds is having **no rate master**,
  and a master is its rate master, which scales the cycle by `bpm / 120` as above. So the instance's
  flag stays false for it — `FxInstance.registrationTimingSource` can then tell the two kinds apart on
  the wall clock — and `FxInstance.onSeconds` is the reading for either kind. A stored row may
  carry the flag for a wall-clock effect (a client's or the AI's Seconds), which spawns it with no rate
  master; a stored wall-clock row with no rate master and no flag — every row before S15 — is on
  Seconds too.
- **Seconds is free of every master**, so nothing retimes it: a cue layer's or a programmer layer's
  master override does not reach it (`EffectSpawner` applies the timing after the overrides), and a
  busk or AI press's beat-division override leaves its cycle alone
  (`LookEffectSpec.withBeatDivisionOverride`). A wall-clock row that merely has no rate master keeps
  the rule it always had — a layer's rate override still scales it.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `seconds`; `PUT /fx/{id}` and
  `updateFx` (one parse, `applyEffectUpdate`) take `seconds: Boolean?` — `true` onto Seconds, `false`
  onto the master sent beside it (a beat effect's absent master is master 1; a wall-clock effect must
  name its rate master, since a null one *is* Seconds), and naming a master alone also leaves Seconds.
  `seconds: true` beside a master is refused on the effect routes and by the MCP effect tools
  (`secondsTimingConflict`) — two answers to one question; the stored-effect writes take Seconds and
  drop the master instead (below). A move between Seconds and a master changes the loop the effect
  runs on, so it is a swap and the effect's **phase restarts**; every other edit keeps it. On a wall-clock effect, `seconds: true` is the explicit *unscaled* that a null
  `rateSpeedMasterUuid` (which means "no change") never could say — `FU-FX-RATE-MASTER-CLEAR`, closed
  by S15. `EffectDto.seconds` (and the group and indirect effect DTOs') reports `onSeconds`; a beat
  effect on Seconds reports `timingSource` `WALL_CLOCK`, the loop it runs on, so every speed label
  reads its number as seconds.
- **A stored row on Seconds keeps no master beside the flag**: the Look, palette and cue writes
  null both masters where `seconds` is set, rather than refusing the pair as the effect routes do.
- **Record and Include carry it** with the masters. Both had dropped the masters of a cue's ad-hoc
  effect until S15 — `programmerCapture` recorded none, and `CueAdHocEffectDto.toEffectSpec`
  (Include's adapter) passed none on — so a recorded busk fell back to master 1.
- **Blind holds it like any programmer effect** (show-cue-editing plan D7): a busk on Seconds runs on
  the wall clock, whose accumulators advance before the pass applies anything, so a held one keeps its
  place in real time and lands in step when Blind goes off — §"Programmer suppression and the
  priority band".

**A new effect is one bar** (venue-visit plan D16): four beats, on the master whose `usage` matches
its family where one is tagged, else master 1. The master is **stamped as a uuid when the effect is
written**, never resolved later, so `null` still means master 1 everywhere: by the editor's picker
(`FxPicker`), the palette editor, the MCP effect tools (`AiTools.parseEffectTiming`, which also
makes `beatDivision` optional) and the programmer's bare effect press (`POST /programmer/effects`);
the desk's two read one rule, `fx/EffectTiming.kt`'s `usageMasterUuidFor`. REST's
`AddEffectRequest` and `AddGroupFxRequest` default an omitted division to one bar and stamp nothing. A **wall-clock** effect starts on its own Seconds — four
seconds — since "one bar" means nothing to an effect timed in seconds. Moving a beat effect between
Seconds and a master in the editor or the palette sheet keeps its speed: the division is converted
at the master's tempo (4 beats at 120 BPM is 2 s). The effects plan's sessions build on this timing
and default.

### Configuration

Set `timingSource` in the `.fx.kts` frontmatter or via the FX definitions API:

```kotlin
/*---
id: CandleFlicker
effectMode: STATEFUL
timingSource: WALL_CLOCK
---*/
```

The timing source is stored on `EffectRegistration` in the `FxRegistry` and propagated to `FxInstance.timingSource` when effects are created via looks, cues, or the REST API.

### Control-surface bindings

Two `BindingTarget` variants drive the bank from hardware
(`midi/BindingTarget.kt`, dispatched by `midi/SurfaceInputRouter.kt`):

| Target | Control | Behaviour |
|--------|---------|-----------|
| `speedMasterBpm` | fader / encoder | `{ masterUuid?, minBpm, maxBpm }` — 0..127 maps across the window, clamped into the clock's 20..300. Defaults to 60..180, because spreading 128 steps over the full range gives ~2.2 BPM a step |
| `speedMasterTap` | button | `{ masterUuid? }` — taps on press, no-op on release |

Both address the master by **uuid** (null → master 1), so they survive clone and
cross-install import — unlike the older int-id cue/stack variants (`FU-SYNC-BINDING-PAYLOAD-UUIDS`).
A binding naming a deleted master reports `AssignmentHealth.MissingSpeedMaster` rather than
falling back to master 1: an *effect* degrading to the global tempo is reasonable, a control
silently retuning the whole show is not.

`SurfaceFeedbackPublisher` indexes tempo bindings separately from DMX-backed ones (a speed
master has no channel behind it) and feeds them from `SpeedMasterBank.changes`. That is not
just for encoder rings — soft takeover arms from `setLogical`, so without an index entry a
PICKUP encoder would stay ENGAGED and jump the tempo on first touch. Tap buttons get **no**
LED feedback: every other LED entry reflects a steady boolean the publisher can read back,
and a tap has no "on" state to hold.

## Future Considerations

1. **MIDI Clock Sync**: Accept external MIDI clock as a tempo source. (Binding *surface
   controls* to speed masters has landed — see "Control-surface bindings" below.)
2. **Beat Detection**: Auto-detect BPM from audio input
3. **Effect Modulation**: Effects that modulate other effects' parameters
4. **Custom Distribution Functions**: User-defined distribution curves via scripts
