# FX System Engineering Documentation

This document describes the FX subsystem for tempo-synchronized lighting FX.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md) for the
> tiered composition model. FX are **Tier 3**: they sit above cue attribute
> assignments (Tier 4) and below the programmer (Tier 2) and parking (Tier 1). The per-tick reset-to-
> neutral pass resets each attribute to the **tier below** (via `LayerResolver`), not to
> hardcoded zero, so direct writes and cue state remain visible under running FX. FX
> iteration is a sorted pass (priority ascending, then compose order, then id) rather than
> undefined map iteration, making multi-FX composition on the same attribute deterministic.

## Overview

The FX system provides:
- Global tempo synchronization via Master Clock (BPM-based)
- Continuous FX (sine waves, pulses, colour cycles, position sweeps)
- Type-safe FX targeting via fixture traits
- Multiple blend modes for FX application
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
│   │                  Active FX Map                              │   │
│   │   fxId → FxInstance                                     │   │
│   └───────────────────────────┬─────────────────────────────────┘   │
│                               │                                     │
│                               ▼                                     │
│   ┌─────────────────────────────────────────────────────────────┐   │
│   │              FX Processing Loop (per tick)                  │   │
│   │                                                             │   │
│   │   0. Reset FX-controlled attributes to neutral              │   │
│   │   For each active FX:                                       │   │
│   │     1. Calculate phase based on clock + FX timing           │   │
│   │     2. Fx.calculate(phase, context) → output value          │   │
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

Tempo lives in a per-project **`SpeedMasterBank`** of `MasterClock` instances — named tempo
buses that FX *subscribe to* rather than owning speeds. **Slot 0 is always master 1**,
the global tempo that script `setBpm`/`tapTempo` and a bare AI `set_bpm` mean, and that
every FX with no explicit master resolves to. (`set_bpm` also takes an optional
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

### Family routing and follow (time signature)

Two nullable per-master settings, added for the busk view (see
`docs/plans/completed/busking-view-plan.md` §2, decisions D1–D7):

- **`family`** (`intensity` / `colour` / `position` / `beam` — the FX library's own family
  vocabulary, stored as `speed_masters.family`, pinned by `GlossaryVocabularyTest`) names the master an *apply-time
  caller* should stamp into an FX created with no explicit master. This is D1: routing is
  visible stamping at apply time, never engine resolution — a null `speedMasterUuid` keeps
  meaning master 1, everywhere, forever. `controls` is deliberately not routable; its FX
  land on master 1. Nor is `steps`: a Steps FX is stamped by the family it
  steps instead (`stepsFxFamily`) — Slot Steps by Beam's, Palette Steps by its palettes'. One master per family per project,
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
  timestamp exactly. Linking snaps the follower's FX once; unlinking does not
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
  every FX on the master stands still. Ratios above the previous one hide it (they map ahead
  of the counter and drive on the first tick), which is why 2× behaved and ½, ⅓ and ¼ did not.
  A reload that leaves the mapping alone — a rename, a family retag, a stored-tempo edit — must
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
  (`checkFollowTarget`), the carve-out an untouched `family` already has: an imported row can
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
FX reference a master by **uuid** (`FxInstance.speedMasterUuid`, null → master 1) and
the engine binds that to a runtime slot index at add/update time, re-binding when the
bank's membership changes; a deleted master's FX degrade to master 1, never stop.
Because an FX instance targets specific attributes, per-instance assignment already gives
"different speeds for different attributes". One FX driving several attributes is
`FU-SPEED-PER-ATTRIBUTE`.

### One processing pass, N timebases

No clock drives FX processing directly: every clock tick nudges the bank's **CONFLATED
wake channel**, and the engine runs one pass per wake-up over one coherent
`SpeedMasterBank.Frame` of per-master ticks. Ticks landing mid-pass collapse into a single
follow-up, so the pass rate is bounded by the fastest master, and one pass means one
`ControllerTransaction` — FX on different masters can never fight over an attribute
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
- Smooth FX interpolation
- Sub-beat timing resolution

The tick timer keeps a **fractional deadline** rather than delaying a truncated interval:
the old `toLong()` truncation ran a 120 BPM show at ~125 BPM invisibly, and with several
masters it became *relative* drift (120 vs 60 BPM truncated to a 2.05:1 ratio). Long-run
inter-master ratios are now exact; `SpeedMasterBankTest` pins this. A tempo change never
resets the tick counter, so phase stays continuous across it.

### Cycle Lengths

FX reference timing via `CycleLength` constants:

| Constant | Value | Description |
|----------|-------|-------------|
| `EIGHTH_BEAT` | 0.125 | Eighth of a beat |
| `QUARTER_BEAT` | 0.25 | Quarter beat |
| `THIRD_BEAT` | 0.333 | One-third beat |
| `HALF_BEAT` | 0.5 | Half beat |
| `ONE_BEAT` | 1.0 | One beat |
| `TWO_BEATS` | 2.0 | Two beats |
| `ONE_BAR` | 4.0 | Four beats — a new FX's default (venue-visit plan D16) |
| `TWO_BARS` | 8.0 | Eight beats |

Everything a person reads says the cycle in **beats a cycle** — `½ beat`, `1 beat`, `2 beats`,
`1 bar`, `2 bars`, `4 bars` — from one table (`SPEED_STEPS` in the frontend's `fxConstants.ts`), the
editor's segment and every chip alike; an FX on Seconds reads `4 s`. The note values the chips
once used (`1/4` for one beat) are gone (`FU-FE-FX-SPEED-SPELLING`, settled by S15).

## FX types

Since the terminology plan's session 3 each id is its name without the spaces (D10–D13): `ColourWave`
was `ColourPulse`, and `Breathe` is gone, a stored one now a Sine Wave a quarter-cycle on. Since the
fx-settings plan's session 2, **Wave** is the eleven level waves (D2): Sine Wave, Ramp Up, Ramp Down,
Ramp, Square Wave, Pulse, Dimmer Strobe, Zoom Wave, Iris Pulse, Frost Pulse and Focus Pull, which were
`Triangle`, `Strobe` and `ZoomBreathe` before that; since its session 3, **Path** is the six
movements and Colour Cycle draws Colour Fade's and Colour Wave's colours, which stay registered as
**legacy** (§"Path, Colour Cycle and the renamed parameters"). That leaves twelve built-ins to offer.
`sync/LegacyNames.kt` reads every old name (§"Old names" in `sync-engineering.md`, and §"Wave" below).

### Intensity FX (`FxOutput.Slider`)

FX that produce single 0-255 values for dimmer/slider attributes.

| FX | Description | Parameters |
|----|-------------|------------|
| `Wave` | A level wave of one `shape`: Sine, Ramp up, Ramp down, Triangle, Square or Pulse; on the dimmer, UV and the beam attributes (§"Wave") | `shape`, `low`, `high`, `width` (Square), `widthUnit` (Square and Pulse: Width, or One head), `attack` and `hold` (Pulse), `curve` (the ramps, Triangle and Pulse) |
| `Flicker` | Random variation | `low`, `high` |
| `CandleFlicker` | Organic candle or fire flicker by random walk; stateful, on the wall clock | `base`, `low`, `high`, `settle` |
| `FluorescentFlicker` | A tube striking: a burst of on/off flicker, then on; stateful, on the wall clock | `high`, `low`, `burstMs`, `speed` |
| `LightningStrike` | A flash that decays | `high`, `low` |

### Colour FX (`FxOutput.Colour`)

FX that produce RGB colour values.

| FX | Description | Parameters |
|----|-------------|------------|
| `ColourCycle` | Step through a list of literal colours, each giving way to the next by `between`: Cut, Fade (over `fade` of its step) or Smooth (a cosine across it) | `colours`, `between`, `fade` |
| `RainbowCycle` | Hue rotation | `saturation`, `brightness` |
| `ColourStrobe` | Flash colour | `onColour`, `offColour`, `width` |
| `ColourWave` | Sine between two — **legacy**: runs and edits stored ones, offered nowhere | `colourA`, `colourB` |
| `ColourFade` | Linear fade, bouncing by default — **legacy**, likewise | `fromColour`, `toColour` |
| `ColourFlicker` | Random variation | `baseColour`, `variation` |

Colour Cycle takes **literal colours only** (terminology plan D14): a `colourList` parameter — its
`colours` — refuses a palette reference at every write boundary (`colourListPaletteProblem`: the add
routes, `PUT /fx/{id}` and its frame, a Look's, a cue's and a palette's FX, the MCP tools), and
`TypedParams.colourList` drops one that reaches it anyway. Palette Steps is the FX that steps through
palettes, each landing on each head for itself. The other colour FX keep a palette reference in their
`colour` parameters, one colour for every head (D30).

### Position FX (`FxOutput.Position`)

FX that produce pan/tilt values for moving heads.

| FX | Description | Parameters |
|----|-------------|------------|
| `Path` | A movement of one `shape`: Circle, Figure 8, Random about a centre, or a Line between two ends (§"Path, Colour Cycle and the renamed parameters") | `shape`; `panCentre`, `tiltCentre`, `panSize`, `tiltSize` (Circle, Figure 8, Random); `startPan`, `startTilt`, `endPan`, `endTilt`, `curve` (Line) |

### Wave (fx-settings plan session 2)

One FX for the level waves (D2), `dimmer/Wave.fx.kts`, family `intensity`, compatible with `dimmer`,
`uv`, `zoom`, `iris`, `frost` and `focus`. Its `shape` is a `choice` — `SINE`, `RAMP_UP`, `RAMP_DOWN`,
`TRIANGLE`, `SQUARE`, `PULSE` — and each shape is the old FX's own maths, line for line, so a stored
FX moved onto Wave runs as it did (`LegacyEquivalenceTest`, below). `low` and `high` are its levels
(`low` above `high` runs the wave upside down, as Focus Pull's `start` above `end` did); `width` is a
Square's share of the cycle high, `attack` and `hold` a Pulse's, `curve` the ramps', Triangle's and a
Pulse's easing, each shown only for its shapes (`shownWhen`). The curve's default is `LINEAR`, which
Pulse's was not (`QUAD_OUT`): a stored Pulse is written with its curve, and the presets name theirs.

**Width: One head** (D8, session 4). `widthUnit`, a `choice` of `RATIO` (*Width*, the default) and
`HEAD` (*One head*), shown for Square and Pulse: under `HEAD` a Square is high for `1 /
numDistinctSlots` of the cycle and a Pulse plays its attack, hold and release within that share and
rests low for the rest, so with the heads spread over the cycle exactly one is on at a time whatever
the selection (grandMA3's Adaptive Width). It reads the context the FX already gets — the slots left
after Segments, Wings and Parts — so it is a parameter, not an engine field; on one head it is on the
whole cycle. Its preset *Chase* (Square, `HEAD`) sits with *Strobe*.

**On an attribute.** The frontmatter's `attributeDefaults` are the parameters a Wave on that attribute
starts with where its own name none — `zoom` and `focus` 64–192, `iris` and `frost` 0–160, the old
beam FX's ranges. `createFxWithPalettes` lays them under the parameters on every spawn that names
its attribute (`withAttributeDefaults`, given the attribute by `FxSpawner`, `POST /fx/add`, `PUT
/fx/{id}`, the group route and the preview), so a Wave stored on zoom without levels runs at 64–192
wherever the desk starts it; the picker and the palette editor write them out when they start one. A
script's `effect()` builds the FX before it knows the attribute, so a script's Wave on zoom runs at its
own `low` and `high` (an old `effect("ZoomWave")` still gets 64–192, through `runnableFx`). **Its family follows the
attribute** (D5): `fxAttributeFamily` (`fx/AttributeMask.kt`) is Beam on a beam attribute, else the
library family, so a Wave on zoom routes to the Beam speed master (`familyMasterUuidFor`, which now
takes the attribute), banks an FX palette under Beam, and counts as Beam in a Look's families and the
Timeline, while a Wave on `dimmer` or `uv` stays Intensity, as Sine Wave on UV always was. A stored
FX keeps its library family, `intensity` — Record files a Wave so too (`recordedFxFamily`) — and the
family is read over it. `lib/attributeFamily.ts`'s `fxAttributeFamily` is the client's twin: the
picker lists the bare Wave under Beam on a head's beam attribute, the FX editor asks a Wave on one no
*Replace it · Within it* and draws its `low` and `high` (the pair its frontmatter declares) as the
graph's level handles only where it is a level FX, and *Save as palette…* keeps the beam attribute a
running Wave drives. A project FX definition may not take the id `Wave` (`retiredIdProblem`,
`registerFx`), which would take the place of every migrated level wave in its project.

**Presets** (D4) keep the familiar names in the picker: frontmatter `presets`, each a `name`, an
`attribute`, `parameters` over the FX's defaults and, where they differ from the FX's, a `cycle` and
`direction`. Wave's are *Strobe* (Square at 10 %, on the dimmer), *Chase* (Square on for one head), *Zoom Wave*, *Iris Pulse*, *Frost
Pulse* and *Focus Pull* (Ramp up, `SINE_IN_OUT`, bouncing at half a bar). The library answers them
(`FxTypeInfo.presets`, `FxPreset`), `describe_rig` lists them beside each FX's attributes, and the picker draws each under the
family its attribute gives, on a head that has the attribute (`FxPicker`); a tap starts Wave with the
preset's parameters, cycle and Direction, and a tap on another preset of the running FX swaps its
parameters in place. A preset is no stored thing: the FX it starts is a Wave.

```
attributeDefaults:
  - attribute: zoom
    parameters: {low: 64, high: 192}
presets:
  - name: Focus Pull
    attribute: focus
    parameters: {shape: RAMP_UP, low: 64, high: 192, curve: SINE_IN_OUT}
    cycle: 2.0
    direction: BOUNCE
```

**The old ids** (D3). `LegacyNames.consolidatedParameters` is each old FX as Wave: its shape, and every
parameter it declared under Wave's name at the value its script read — absent or blank at its old
default, a level clamped as `TypedParams.ubyte` reads one, an unknown curve `LINEAR` — and a beam FX
that named no attribute the one it drove. Database step 7 (`state/FxConsolidationMigration.kt`) and
the importer store that (`consolidateFx`); a script's `effect("SineWave", "min" to "40")` and MCP's
FX tools run it (`runnableFx`, `asToday`, which also start an old Focus Pull at its half-bar Bounce);
every other write boundary refuses an old id, naming its shape (`writeProblem`). The registry does
not resolve one, so a script or FX definition that looks an old id up — `fxRegistry.getRegistration
("Pulse")`, the way a definition builds on a built-in — finds nothing; step 7 names each stored script
and definition that quotes an old id outside `effect(…)`, and any definition with the id `Wave`, in a
warning, and rewrites none. The old scripts live
on as test resources, `src/test/resources/fx/legacy/`, and `LegacyEquivalenceTest` runs each beside
Wave over every head's phase — the clock at 1/96 of a cycle, a spread over four heads, each
Direction on an even and an odd cycle — and a grid of its parameters, blank and unparsable ones
included, and finds the same output exactly. No level wave failed, so none stays registered as legacy
(P3; two colour FX do, §"Path, Colour Cycle and the renamed parameters"). One thing moves, and the log says so by owner: a beam wave stored with no attribute (a Beam FX
palette the UI made) ran on a group only where every head had its attribute, and on none of a mixed
group; migrated, it names the attribute and runs on the heads that have it, as a Wave authored there
does — the plan's one recorded exception to D3.

### Path, Colour Cycle and the renamed parameters (fx-settings plan session 3)

**Path** (`position/Path.fx.kts`, family `position`) is the six movements (D2): `shape` `CIRCLE`,
`FIGURE_8`, `RANDOM` about `panCentre` / `tiltCentre` at `panSize` / `tiltSize`, or `LINE` from
`startPan` / `startTilt` to `endPan` / `endTilt` along `curve` — each shape the old FX's maths line
for line. A Line's ends and a centred shape's centre and size are each `shownWhen` the other's
shapes, so the editor draws only what the shape reads. Its defaults are Circle's and Sweep's; its
presets are *Sweep*, *Pan Sweep* and *Tilt Sweep*, each a Line that bounces at half a bar as the
sweeps did. **The Centre question** (D12) is asked only of a shape whose centre is drawn: the client's
`hasCentre(entry, parameters)` reads the centres through their `shownWhen` (`isParamShown`), so a Line
asks nothing, starts on Override and, when the shape moves to or from a Line, the blend moves with it
(`centreFlip`: out of an *Around* shape Override; into a centred shape *Around* while the centres are
still the pinned 128, else the blend and centres it held, so an *Absolute* circle taken through a Line
comes back Absolute); a pad face never reads a Line as *around* (`isAroundSpelling`); the desk's stacking
start does the same through `shownParameters` (`routes/programmerFx.kt`).

**Colour Cycle** gains `between`, a `choice` of `CUT`, `FADE` and `SMOOTH`, and `fade`, the share of
each colour's step a Fade crosses over — the old `fadeRatio`, computed exactly as it was, so even a
ratio outside 0–1 runs as it ran. Smooth eases from each colour to the next on a cosine across the
whole step. Its presets are *Colour Fade* (two colours, Fade 100 %, a four-beat cycle Forward: the old
two-beat bounce one way in four) and *Colour Wave* (black to white, Smooth).

**Legacy.** P3 refused two mappings, so **Colour Wave** and **Colour Fade** stay registered with
`legacy: true` in their frontmatter (`FxRegistration.legacy`, the library's `legacy`): a stored one
runs and edits as before and is still written back under its own id, but the picker, the palette
editor's FX list, the FX Library page and `describe_rig` leave a legacy FX out, so nothing new is made
of one. Colour Cycle draws each a DMX step apart: Smooth a quarter-cycle on is Colour Wave's sine, but
the cosine and the sine floor either side of a byte at some phases (4 of 97 on a mixed pair), and
Fade at twice the cycle is Colour Fade's bounce but for the turn, where the bounce's reversed phase
reads 2ε short of the end and floors a step below Colour Cycle's own fall.

**Renamed parameters** (§3 of the plan): Flicker's `min` / `max` are `low` / `high`; Candle Flicker's
`baseLevel`, `min`, `max`, `smoothing` are `base`, `low`, `high`, `settle`; Fluorescent Flicker's
`offLevel`, `onLevel`, `flickerDurationMs`, `flickerSpeed` are `low`, `high`, `burstMs`, `speed`;
Lightning Strike's `minBrightness` / `maxBrightness` are `low` / `high`; Colour Strobe's `onRatio` is
`width`. Each reads its value as before; only the key moved.

**The old names.** `LegacyNames.absorbFx` is session 3's rewrite: a movement as Path through
`consolidatedParameters` (the old parameters at the values their scripts read, written out, a Pan
Sweep's `tilt` as both ends' tilt), a renamed parameter at its new name, Colour Cycle's `fadeRatio`
as `between` and `fade` (0 a cut). A row written before the renames that already holds a key under a
new name its old FX never declared — a Flicker's `low`, a Colour Cycle's `between` — loses it, since
the new script would start reading it (`writtenBefore`: database step 8, and the importer below
`formatVersion` 42); a tool's call today means it and keeps it. A movement's centre under its US
spelling is read as its centre. Database step 8 (`state/FxSiblingsMigration.kt`, `user_version`
8, backed up to `<db>.pre-fx-siblings-<utc>.db` when anything moves) and the importer store it, after
step 7's `consolidateFx`; a script's `effect("Circle", …)` and MCP's FX tools run it (`runnableFx`,
`asToday`, which start an old sweep at its half-bar Bounce, `consolidatedTiming`); every other write
boundary refuses an old id or parameter name (`writeProblem`). Step 8 names each stored script and
definition that quotes a movement's old id outside `effect(…)`, and any definition with the id `Path`,
as step 7 does, and one quoting a renamed parameter's distinctive old name (`baseLevel`, `onRatio`,
…, or a `fadeRatio` beside a Colour Cycle — Palette Steps still reads its own). Steps 7 and 8 are one `state/FxRewriteStep.kt`, configured. No FX definition may take Wave's or
Path's id (`LegacyNames.consolidatedTypeNamed`), and the FX Library page still reserves a legacy FX's
id, though it lists none (`offeredFx`). The old scripts are under `src/test/resources/fx/legacy/` with session 2's, and
`LegacyEquivalenceTest` runs each beside its new form — the stateful flickers tick for tick over 400
ticks — and finds the same output exactly, except the two above, which it pins as refused.

### Beam FX (`FxOutput.Slider`)

A Wave on `zoom`, `iris`, `frost` or `focus` is a Beam FX (above), and the picker offers it there as
its presets. Level FX other than Wave are not offered on beam sliders (`FU-FX-BEAM-ANY-SLIDER`).

The values are raw DMX, as every level FX's are. A stepped zoom (the Robe's) is a setting, so
a Wave on it writes the byte and lands on whichever step that is. To step one by name, use
Slot Steps on it (`ZOOM_18, ZOOM_26`), or Palette Steps over Beam palettes whose zoom is a percent,
which snaps to the step nearest that share of its angles, as a palette does (below).

A stored beam FX that names no attribute — an FX palette's, or any other stored without one — lands
on the one attribute its registration declares (`FxSpawner`'s `beam` arm), on a group only where
every member has it, as the dimmer arm asks of a group's dimmers; so a Beam palette's FX must
name one attribute, its own `attribute` or else the one its registration declares, never a
sentinel (`validatePaletteFx`) — Slot Steps names the setting it steps, and a Wave the beam attribute
it drives (the palette editor's *Attribute* select). A group or selection is resolved
through its first head that has the attribute (`referenceFixtureFor(target, attribute)`), so a
selection that starts on a head without zoom still drives the zooms after it. Record files an
FX on `zoom`, `iris`, `frost` or `focus` as family `beam` (`categoryFromAttributeName`; a step
FX and a Wave by `recordedFxFamily`, below — a Wave as `intensity`, its library family), so a recorded Look's families include Beam. A speed master may take `family: beam`, and a Beam FX
palette holds one of them (`PALETTE_FX_FAMILIES`).

### Step FX (`FxOutput.Steps`)

Effects plan session 7 (D8, §3.6): two built-ins step through **named things**, each resolved for the
head in hand, which no other FX output can say. Both emit `FxOutput.Steps(from, to, blend,
steps)` — two step indices, how far between them, and the `StepList` that says what a step is — and
declare `outputType: STEPS`, which every target accepts (`FxTarget.accepts`; the routes'
`requireOutputTypeMatch`, `FxInstance`'s warn and a palette press's skip all ask it).

| FX | Family | Steps through | Parameters |
|----|--------|---------------|------------|
| `PaletteSteps` | its palettes' family | generic value palettes, `pal:{uuid}` each (a `paletteList`) | `palettes`, `fadeRatio` |
| `SlotSteps` | `beam` | one setting's option names (a `slotList`) | `slots` |

The two are one family in the library, *Steps* (terminology plan D23): both register under `steps`
(`STEPS_CATEGORY`), which names no attribute family, and are offered and stored in the family of what
they step — the Family column — through `stepsFxFamily` and, on the client, `drivenCategoryOf`.

`stepsAt(phase, steps, fadeRatio)` is the timing, Colour Cycle's: equal segments, each step held for
`1 − fadeRatio` of its own and crossing to the next over the rest, the last crossing back to the
first. Amount, direction and the spread apply as to any FX, so a Backward or Bounce phase runs the
steps back.

**A step lands on its sample.** Every built-in that turns a phase into a step goes through
`stepIndex(phase, n)` (`fx/StepList.kt`; `stepsAt` and Colour Cycle), and every one that tests a
phase against an edge through `phaseBefore` (the strobes, Square Wave, the pulses, Lightning Strike).
Both allow `PHASE_EPSILON` (1e-9): a member's phase is the clock's less its offset, wrapped into the
cycle (`FxPhase`), and at a boundary that arithmetic can fall a rounding error short — a third of a
cycle comes back as 0.33333333333333326 — which put one row's step a sample late while its
neighbours stepped on time (`StepBoundaryTest`). A phase that short of a whole cycle is the next
cycle's start (`cyclePhase`). A reversed phase reaches each boundary from above, so Backward and
Bounce read `1 − p` twice that tolerance short (`FxDirection`), which lands it on the step it enters
and keeps phase 0 at the very end of the cycle.

**The target resolves a step per head** (`StepList.valueFor`, `colourFor` on the colour target):
`SliderTarget`, `ColourTarget` and `PositionTarget` blend the two steps in proportion (a byte, an
`ExtendedColour`, pan and tilt each), and `SettingTarget` snaps to the nearer — a slot has no halfway
(Slot Steps' level read off the setting the target already holds, `SlotStepList.levelFor`). A head
that cannot take a step holds what is underneath for it: nothing is written where it shows only that
step, and part-way between a step it can take and one it cannot it crosses from what is underneath
(`FxOutput.Steps.blendOn`, which reads what is underneath only then). The blend mode and Amount apply
after, as to any output.

- **Palette Steps** (`PaletteStepList`): each palette's rows, as intents, resolved on the head through
  `PaletteResolver` — the cook's, *Apply as values*' and the resolves-to panel's one answer — for the
  target's attribute: the colour family (the hex mixed for that head's emitters, then any explicit
  emitter row) on the colour target, the position row on the position target, else the row naming
  the attribute. So a centre-relative (`pos:`) and a degree (`deg:`) position palette each land on the
  head's own pan and tilt, a beam role on its own range (a stepped zoom snapping, `steppedZoomSlot`),
  and an RGBW head and an RGB one each get their own mix. **A colour step is a layer's colour**: a head
  missing an emitter the palette names takes none of it (`unmetColourRequirement`, the cook's rule),
  and what a palette does not name stays as it is underneath (`StepColour.over`) — a UV-only palette
  moves the UV and leaves the RGB, white and amber alone. A head is resolved once and cached by its
  key and attribute, re-resolved when the model under that key changes: the engine builds fresh
  fixture objects every tick, so an object is no key. Fitted media is not read, since it decides only
  a **wheel** slot and a wheel head is no colour target's member. The palettes are read through the FX's colour source, which is a
  `PaletteSource` — the one `createFxWithPalettes` passes — so `TypedParams.paletteSteps` needs
  no new factory argument, the references are pre-warmed by the same `paletteColourRefsIn` scan, and
  a palette's edit moves the running steps through the colour caches' version. An entry that is not a
  generic value palette is dropped, as a colour list drops one.
- **Slot Steps** (`SlotStepList`): each name looked up in the head's own options of the setting the
  FX drives, ignoring case, spaces, underscores and dashes (`normaliseSlotName`), so *Gobo 3* is
  `GOBO_3` on every head, at each head's own level. A head without the slot holds what is underneath on
  that step. Its levels are cached per option set (a setting's options are its model's enum), so the
  cache holds one entry per wheel model met. It declares only the `setting` sentinel: the client turns
  it into the setting chosen.

**What it is filed under.** Palette Steps registers under `family: steps`, which names no
attribute family on purpose: a stored one carries the family of its palettes (`intensity`, `colour`, `position` or
`beam`), and `paletteStepsShape` is the one statement of that shape — every palette in one family,
and the attribute it drives, the colour for colour, else the first in the palette vocabulary every
palette names (zoom, where every Beam palette names zoom and only some frost). A stored Palette Steps
that names no attribute (an FX palette's, or any other stored without one) lands on that attribute
(`FxSpawner.drivenAttributeOf`); Slot Steps names its setting as the row's `attribute`. An FX
palette's write boundary (`validatePaletteFx`) holds both: Palette Steps needs two generic value
palettes or more, all readable, and its `family` must be theirs; Slot Steps needs a
`attribute` and two slots or more. Its master is stamped by that family (the picker, the
palette editor and the MCP tools' `familyMasterFor`), so `steps` is no master's family
(`GlossaryVocabularyTest`). Record files a running step FX by `recordedFxFamily`:
Slot Steps as `beam`, Palette Steps as its palettes' family read off the attribute it drives (a strobe
in an Intensity palette is `intensity`, not `controls`).

**Neither can be forked.** `/fx/definitions` refuses a `STEPS` output (`validateCompatibleAttributes`):
a step list names this desk's palettes and settings, resolved per head by the targets, and a
definition syncs to desks that predate the output type. The FX Library draws Fork disabled on both
with that reason (`forkRefusal`).

**On the client** (`lib/stepFx.ts`, which never resolves): the picker lists Slot Steps under
Beam on a setting row and starts it on the setting's first two slots past its open one; it offers
Palette Steps in each family that has two generic value palettes or more naming an attribute the
target has (`paletteStepsOffers`), started on all of them, in name order; Slot Steps from anywhere
but a setting row starts on a gobo wheel where the head has one, else its first setting. The editor's
two parameter types are `paletteList` (`FxPaletteListPicker`: palettes in step order, by reference —
an FX palette's own family only, else one family once one is chosen) and `slotList`
(`FxSlotListPicker`: the target setting's options, a stored name the head lacks drawn dashed; on an
FX palette, which names its setting from the rig, every patched head's options of that name
(`rigSettingUnion`) and typed names besides, a dashed one on no patched head). *Save as palette…* on a
running step FX banks it as `stepFxPaletteShape` says — its palettes' family, or Beam naming
its setting.

### Stateful FX (`StatefulFx`)

Stateful FX maintain internal state that evolves over time, rather than being pure
functions of phase. They receive tick-level timing (`ClockTick` + `deltaMs`) instead of
a 0-1 phase value.

The built-in ones are Candle Flicker and Fluorescent Flicker, in the Intensity table above. Both run
on the wall clock (`timingSource: WALL_CLOCK`), so their Cycle is seconds.

Stateful FX implement the `StatefulFx` interface:
- `initialize()` — called once when added to the engine, resets state
- `calculateStateful(tick, deltaMs, context)` — called each tick instead of `calculate()`
- `calculate(phase, context)` — fallback that returns a neutral value

The FxEngine detects `StatefulFx` instances and routes to `calculateStateful()`
automatically in all 4 processing paths (fixture, multi-element, group, flat element).

### One FX, one attribute

An `FxInstance` drives one `FxTarget`, so an FX drives one attribute. The composite mode, which
computed several outputs from one phase and applied the one its `outputType` named, is gone
(terminology plan D27): its one built-in, `LightningStrike`, computed a colour it never applied, and
is now a standard Intensity FX with the same output. One FX driving several attributes is
`FU-SPEED-PER-ATTRIBUTE`.

A mismatched `FxOutput` is **silently dropped** by `FxTarget` (`if (output !is FxOutput.Slider)
return` and friends), so an FX's `compatibleAttributes` must list only attributes of its output type.

## `compatibleAttributes` must match the output type

That silent drop is the general rule, and it bit every FX in
the `position` family (sweep item A11). All seven declared `compatibleAttributes: [pan, tilt]`
against `outputType: POSITION`; `pan` and `tilt` are real `@FixtureAttribute` sliders, so every
frontend picker chose one, posted `attribute: "pan"`, and got a `SliderTarget` that discarded
the `FxOutput.Position`. No light, no error, no log, for the desk's whole life.

The contract, and the three things that now hold authors to it:

- **`FxTarget.acceptedOutputType`** — the one `FxOutputType` a target can apply. A **`POSITION`
  FX must advertise `position`**, the synthetic pan/tilt compound, and never an axis by name;
  a `COLOUR` FX must advertise `rgbColour` (or an alias); a `SLIDER` FX must advertise
  anything *but* `position` or the colour bundle.
- **`FxTargetFactory`** (`fx/FxTargetFactory.kt`) is the single place an attribute *name* becomes a
  target. It replaced four near-identical copies in the route layer, all of which had A11.
  Given the FX's output type it also coerces `pan`/`tilt` to a `PositionTarget` for a
  `POSITION` FX — a repair for Look and cue rows *already recorded* with `attribute: "pan"`,
  not a licence for dishonest metadata. A `SLIDER` FX on `pan` alone still resolves to
  `SliderTarget("pan")`, because that is a legitimate thing to ask for.
- **Reported, not swallowed.** `POST /fx/add`, `PUT /fx/{id}` and `POST /groups/{name}/fx` reject a
  mismatch with 400 (`requireOutputTypeMatch`), and `POST`/`PUT /fx/definitions` rejects a
  user definition whose `compatibleAttributes` its `outputType` cannot drive. Everywhere a throw
  would be worse than a dark light — cue and Look fire, Include, scripts, MIDI — `FxInstance`'s
  `init` logs a warn instead.

`FxRegistrationTargetCompatibilityTest` holds every built-in to this, resolving each declared
attribute through `FxTargetFactory` *without* the output-type hint, so re-adding `[pan, tilt]` goes
red. It parses frontmatter only and compiles nothing.

### Definitions heal rather than lock

`compatibleAttributes` is on **no** editing surface, and the FX edit sheet sends only
`{id, name, script}`. So validating the merged declaration on `PUT` — request field, else the
stored one — would have permanently bricked every position definition already saved with
`[pan, tilt]`: a script-only edit re-checks a stored list the operator cannot reach, 400s, and has
no in-app remedy. `normaliseCompatibleAttributes` runs first and rewrites `pan`/`tilt` → `position`
for a POSITION definition, and the `PUT` writes the normalised list back even when the request
didn't supply the field, so the first save heals the row. That one rewrite is the only thing done
silently, because it has no reading under which the author meant it; everything else is rejected
out loud.

### The frontend change A11 didn't predict

A11 said neither fix needed a frontend change. True of the FX-*add* path — `AddEditFxSheet`,
`useBuskingState` and `ConfigureFxSheet` read the attribute list off the library and needed
nothing. Not true of the *authoring* path: `FxLibrary.tsx`'s new-definition form derived
`compatibleAttributes` from the chosen **family** while `outputType` is an independent select
defaulting to `SLIDER`. That let Family = Position + the default output type write a list the
FX could never drive, and the new 400 would have fired on the desk's own create flow, naming a
field the form doesn't expose. It now derives from `outputType`, which is what actually decides the
answer.

## FX Registry

All FX (built-in and user-defined) are registered in a unified `FxRegistry`.

### Architecture

```
FxRegistry
├── register(FxRegistration)     ← Built-in FX at startup
├── register(FxRegistration)     ← User FX from fx_definitions table
├── createFx(type, params, ...)  → Fx instance
├── getLibrary()                     → List<FxTypeInfo> (for API)
├── getRegistration(type)            → FxRegistration? (lookup)
└── unregister(id)                   ← Cleanup on delete
```

### FxRegistration

Each registered FX provides:
- `id` — canonical name (e.g., "Wave")
- `aliases` — alternative names for lookup (e.g., "sine_wave", "sine")
- `name` — human-readable display name
- `family` — the library family ("intensity", "colour", "position", "beam", "steps", "controls"; `steps` is Palette Steps' and Slot Steps')
- `outputType` — primary output type
- `fxMode` — `STANDARD` or `STATEFUL`
- `defaultDirection`, `defaultCycle` — what a new instance runs at when its author names
  neither (below)
- `parameters` — schema for the API/UI
- `compatibleAttributes` — which fixture attributes this can target
- `source` — `BUILT_IN` or `USER`
- `script` — the calculate body (Kotlin script source)
- `factory` — creates an `Fx` from string parameters + the colour source (see below)

Lookup is case-insensitive with spaces and underscores stripped.

## FX Definitions

### Data Model

**Built-in FX** are stored as `.fx.kts` files in the repository under
`src/main/resources/fx/`. Each file contains YAML frontmatter for metadata followed
by the calculate body. They are loaded at startup, compiled, and registered in the
FxRegistry with `FxSource.BUILT_IN`. They appear in the FX Library UI as
read-only, and their scripts serve as real working examples for users writing custom
FX.

**User FX** are stored in the `fx_definitions` database table and managed via
the FX Library UI. They are compiled and registered on save and on startup. The
table always requires a `project_id` since every user FX belongs to a project.

```
fx_definitions table (user FX only):
├── fx_id             — canonical ID (e.g., "MyCustomPulse")
├── name              — display name (e.g., "My Custom Pulse")
├── family            — "intensity", "colour", "position", "controls" ("beam" and "steps" are
│                       the built-ins' own; the New FX sheet offers neither)
├── output_type       — SLIDER, COLOUR, or POSITION (never STEPS — refused, see Step FX)
├── fx_mode           — STANDARD or STATEFUL
├── parameters        — JSON schema [{name, type, defaultValue, description, …}] (§"Parameters")
├── compatible_attributes — JSON array of attribute names
├── script            — the calculate() body only
├── project_id        — owning project (required)
├── default_timing    — CYCLE or HEAD
└── timing_source     — BEAT or WALL_CLOCK
```

### Script Model

Scripts contain **only the calculation logic** — the body of `calculate()` or
`calculateStateful()`. All metadata (name, family,
parameters, etc.) is stored in the database/registration and managed via the UI.

**Parameter access** uses a typed accessor object (`TypedParams`) that pre-parses
string parameters according to the schema:

```kotlin
// TypedParams methods:
params.ubyte("min")          → UByte (default from schema)
params.int("count")          → Int
params.double("fade")        → Double
params.boolean("enabled")    → Boolean
params.colour("baseColor")   → ExtendedColour (resolves a palette reference, "pal:{uuid}")
params.colourList("colours") → List<ExtendedColour> (literal colours only; a palette is dropped)
params.easingCurve("curve")  → EasingCurve
params.choice("shape")       → String (one of its declared options; another reads as the default)
params.string("name")        → String
```

### Built-in FX File Format (.fx.kts)

Built-in FX are stored as `.fx.kts` files under `src/main/resources/fx/`,
organized by family:

```
src/main/resources/fx/
├── dimmer/
│   ├── Wave.fx.kts
│   ├── CandleFlicker.fx.kts    (STATEFUL)
│   └── ...
├── colour/
│   ├── ColourCycle.fx.kts
│   ├── RainbowCycle.fx.kts
│   └── ...
├── position/
│   └── Path.fx.kts
└── steps/
    ├── PaletteSteps.fx.kts
    └── SlotSteps.fx.kts
```

Each file uses YAML frontmatter in a block comment, followed by the script body:

```kotlin
/*---
id: Flicker
name: Flicker
family: intensity
outputType: SLIDER
fxMode: STANDARD
compatibleAttributes: [dimmer, uv]
parameters:
  - name: low
    type: ubyte
    default: "100"
    description: The lowest level it flickers to
  - name: high
    type: ubyte
    default: "255"
    description: The highest level it flickers to
---*/

val low = params.ubyte("low")
val high = params.ubyte("high")
…
FxOutput.Slider(value)
```

Each parameter also declares how the editor draws it — a label, a range, a unit, a pair — which
the example leaves out; see §"Parameters".

At startup, `FxFileLoader` scans the resource directory, parses each file's
frontmatter into metadata and script body, compiles the script, and registers
the FX in the FxRegistry with `source = BUILT_IN`.

Two optional frontmatter keys say what a **new** instance runs at, read as `defaultTiming` is:
`defaultDirection` (`FORWARD`, `BACKWARD` or `BOUNCE`; `FxInstance` starts its dynamics on it) and
`defaultCycle` (beats a cycle, one bar when absent). Colour Fade (legacy) declares `BOUNCE` at
`2.0` (terminology plan D24), as Wave's *Focus Pull* and Path's sweep presets do: they once
bounced by a `pingPong` parameter over a one-bar cycle, so a new one looks as it did. The library answers both (`FxTypeInfo`), and every
door that makes an FX without naming them takes them: the add routes, the programmer's bare press,
the MCP tools, the picker and the palette editor. A swap to another type in the picker or the
palette editor takes the new type's where the FX still stood at the old type's
(`swappedTiming`). A user FX definition has neither, so runs Forward at one bar.

### Parameters

A parameter is `ParameterInfo` (`fx/FxRegistry.kt`): its `name`, `type`, `defaultValue` and
`description`, and — all optional — how the editor draws it (fx-settings plan D1). The desk
declares; the editor reads, and guesses from the name and type only what a parameter leaves out —
everything, for a script FX's that declares none (`paramView` in
`frontend/src/components/fx/fxEditorModel.ts`).

| Key | Means |
|---|---|
| `label` | The editor's word for it — *Low*, *Attack*, *Pan size* |
| `min`, `max`, `step` | The range offered, in the stored value's terms: a byte's 0–255, a ratio's 0–1 |
| `unit` | How the value reads: `%` a byte level as a percent of full, `ratio` a 0–1 share as a percent, `dmx` a byte in DMX steps, `position` a byte about its axis's centre (`-128..127`), `ms` milliseconds |
| `options` | A `choice`'s values and labels, drawn as a segment |
| `role` | `main`, drawn with the FX, or `detail`, folded under *More* |
| `shownWhen` | Drawn only while another parameter holds one of the values named |
| `pair` | On a low, the high it is drawn with as one two-thumb range |

One frontmatter key is the FX's rather than a parameter's: `stepsFrom: colours` names the list
parameter whose entries are its steps, which a `STEP` spread counts (§"The spread's shape"). It must
name a `colourList`, `paletteList` or `slotList` parameter, else the FX fails to load.

In frontmatter, under the parameter's other keys:

```
  - name: min
    type: ubyte
    default: "0"
    label: Low
    min: 0
    max: 255
    step: 1
    unit: %
    pair: max
  - name: shape
    type: choice
    default: "SINE"
    options: [SINE: Sine, SQUARE: Square]
  - name: width
    type: double
    default: "0.5"
    role: detail
    shownWhen: shape = [SQUARE]
```

Every built-in declares a label for each parameter and a range and a unit for each numeric one,
its script's clamps as the range (`FxFileLoaderTest`). The ranges are the editor's, not a refusal:
a stored value outside one still runs as the script reads it.

`parameterMetadataProblems` (`fx/ParameterMetadata.kt`) is what a declaration must pass — each
name once, a known role, a known unit on a type it reads (`%` and `position` a byte, `ratio` a
double, `dmx` and `ms` any number), `min` ≤ `max`, a range only on a number, a choice's default among
its options and each option once with a value and a label, a `shownWhen` and a `pair` naming another
parameter (a pair two numbers of one type, each in one pair). A built-in that breaks one fails to
load; a script's `registerFx` throws; a user FX definition that breaks one is refused 400 by
`POST` / `PUT /fx/definitions`, every problem named.

A **`choice`** holds one of its `options`, matched ignoring case: every write boundary refuses
another (`choiceProblem`, through `routes/storedFxRules.kt`'s `fxWriteProblem`), and
`params.choice` reads one an older row holds as the declared default. An **`easingCurve`**'s options
are the desk's own curves, filled in by `GET /fx/library` (`withImpliedOptions`) so no FX has to
list them. `describe_rig`'s FX library lines carry the metadata in brackets
(`ai/RigBriefing.kt`'s `parameterBrief`).

The keys are additive in canonical JSON, so a user FX definition carrying them syncs without a
`formatVersion` change: an older reader imports the definition without them, and its next push drops
them (`docs/sync-engineering.md` §"Format versioning" says why that is no degraded record).

### Three Script Base Classes

Each `fxMode` has a dedicated base class with focused provided properties:

#### FxCalcScript (STANDARD)

For pure FX that are a function of phase:

```kotlin
// Provided: phase (Double), context (FxContext), params (TypedParams)
// Return: FxOutput (last expression)

val min = params.ubyte("min")
val max = params.ubyte("max")
val sine = (Math.sin(phase * 2 * Math.PI) + 1.0) / 2.0
val value = (min.toInt() + (max.toInt() - min.toInt()) * sine)
    .toInt().coerceIn(0, 255).toUByte()
FxOutput.Slider(value)
```

#### FxStatefulCalcScript (STATEFUL)

For FX that maintain state across ticks (e.g., CandleFlicker):

```kotlin
// Provided: tick (ClockTick), deltaMs (Long), context (FxContext),
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

### ScriptFxAdapter

`ScriptFxAdapter` bridges compiled scripts to the `Fx`/`StatefulFx`
interfaces. It:

1. Compiles the script body using the appropriate base class
2. Caches compiled results by content hash
3. On `calculate()`: creates `TypedParams` from raw params + schema, evaluates script
4. For stateful: maintains a `MutableMap<String, Any>` per instance

### FX Definitions REST API

```
GET    /api/rest/fx/library                → List all available FX
GET    /api/rest/fx/definitions/{id}       → Full definition including script
POST   /api/rest/fx/definitions            → Create new definition
PUT    /api/rest/fx/definitions/{id}       → Update (recompiles on save)
DELETE /api/rest/fx/definitions/{id}       → Delete (non-builtin only)
POST   /api/rest/fx/definitions/{id}/compile → Compile check
POST   /api/rest/fx/definitions/{id}/run   → Compile and register (live test)
```

## Blend Modes

How FX output combines with fixture's base value:

| Mode | Description | Use Case |
|------|-------------|----------|
| `OVERRIDE` | FX replaces value | Primary FX |
| `ADDITIVE` | FX added to value | Accent layers |
| `MULTIPLY` | FX multiplies value | Proportional dimming |
| `MAX` | Maximum of both | Ensure minimums |
| `MIN` | Minimum of both | Limit maximums |

### Amount, direction and runs (effects plan session 1)

Three fields of every running FX, edited in place (they are `FxDynamics` fields, so an edit
keeps the phase), stored on the four FX homes — a live instance, `look_fx`,
`cue_ad_hoc_fx` and an FX palette (`palette_fx`) — and carried by `LookFxSpec`,
`StoredFx` and `LookFxSettings` between them, so Record and Include keep them. They sync
from `formatVersion` 30 (`docs/sync-engineering.md` §"Version 30"). The desk owns every rule (the
effects plan's P2); the editor only writes the three fields.

- **Amount** (D2), 0–1: how much of the FX reaches the stage over what is underneath. It is a
  **weight applied after the blend**, per channel (P3): `out = before + (blended − before) × w`,
  where `before` is what the attribute already holds this pass — the reset baseline and any
  lower-priority FX already applied — and `w = amount × envelope × stackAmount`
  (`FxInstance.weight`, `FxEngine.weightOf`), clamped to 0–1. A Replace dimmer at 50 % therefore sits halfway between the level underneath and the
  FX, never at half the FX's raw value; an Around movement (`ADDITIVE` about 128) has its
  offset scaled, so a circle at 50 % has half its radius about the position underneath; a colour is
  weighed channel by channel, white, amber and UV included. `FxTarget.applyValueToFixture` takes the
  weight and `weigh` is the one formula. A **setting** is discrete — a slot has no halfway — so it
  takes the FX's value from half weight up and holds what is underneath below (`weighDiscrete`).
  At weight 0 nothing is written. An amount outside 0–1 is refused at every write boundary
  (`FxSpecCoercion.Strict.amountProblem`); a stored one is clamped with a warn. Amount stops at
  100 % (`FU-FX-AMPLITUDE`).
- **The envelope** is the runtime factor of the weight a fade moves (effects plan §3.5, session 4);
  a stack's FX amount (below) multiplies in beside it. It **does not scale the FX's raw output**:
  `FxOutput.scaled()` is gone, and with it the scaling that dropped an `OVERRIDE` FX to 0. It is
  `FxInstance.envelope`, an immutable `FxEnvelope` (`fx/FxEnvelope.kt`) — a level, or a ramp
  from one level to another over a duration on an `EasingCurve`, read on the clock wherever the weight
  is read (`intensityMultiplier` is its level now; setting that holds a level). A fade is one value,
  so it needs no loop of its own, an edit's swap (`updateFx`) and a split's copies carry it by
  carrying the object, and every change is one compare-and-set (`FxInstance.updateEnvelope`). What
  moves it:
  - **a cue's GO** (D1): the hold registry ramps an arriving cue's new FX `0 → 1` and a leaving
    cue's `→ 0` over the cue's fade and curve, and an FX the two cues share runs on untouched
    (P4; never a paused instance or one with a run count, which the incoming cue starts afresh) — a Look or
    palette FX by its stored row, an ad-hoc FX by its run key, edited in place at the GO
    when the incoming row's settings differ (stack-timeline plan D7, D8) —
    `docs/cue-stacks-engineering.md` §"Holds" and §"Crossfade";
  - **the programmer** (D13): a layer added or removed (a press with `fadeMs`, `programmer.removeLayer`,
    a cue Include's layers), `programmer.clearTarget`'s local FX and Clear's band, over the
    programmer fade — split by what is under each (`FxEngine.releaseFx`, Chris's call): an
    FX over a programmer value the same gesture moves lands or goes at once, so that value ramps,
    since a key an FX covers never fades and the value under a shrinking FX would snap
    (`FU-FX-VALUE-FADE-UNDER-EFFECT`). Include's own FX land at full;
  - **Blind**: leaving it grows the band from nothing over the Blind fade, by the same split (entering
    holds it at once).

  An instance let go on a fade is **leaving** (`FxInstance.leaving`): no cue, layer, Release, Record,
  Include or reload carry counts it any more, and the 50 Hz ticker-woken passes remove it once its ramp
  reaches 0 (`FxEngine.retireFinishedLeaving`, run before the pass's early returns, so a paused
  FX ends on time). A fade of 0 removes it at once. A leaving instance a cue takes back mid-fade
  (`FxEngine.reclaimFx`) is held where it stood and faded on from there; one the engine has
  already retired (`FxEnvelope.RETIRED`) cannot be taken back, which is what keeps the two from
  racing. An edit's swap shares the envelope cell with the instance it replaces, as it shares the
  dynamics, so a fade or a retirement racing the swap reaches both; a retired instance an edit swapped
  in is removed by the next sweep. The envelope is never stored and never recorded: Record takes the
  row's own amount.
- **A stack's FX amount** (D10, session 5): a cue stack's runtime weight on its FX, 0–1,
  100 % at load — the third factor of `w`. `StackFxAmounts` (`fx/StackFxAmounts.kt`) is owned by
  the show's `CueStackManager` and handed to the engine (`FxEngine.stackFxAmounts`), so a project
  switch starts every stack at full; a same-project reload carries it (`ShowCarry.stackFxAmounts`,
  restored before the reload's GOs, a stack the reload removed dropped) and a stack delete forgets
  it. Nothing is stored. **Which stack weighs an instance** is the stack of its cue's **highest**
  hold, 100 % under a loose hold (§11 question 21): `weighingStackOf` reads it off the instance's
  `priority`, which `CueHolds` restamps whenever the top hold changes and retags when a cue adopts an
  instance or takes one back — never `FxInstance.cueStackId`, the first hold's tag. A **leaving**
  instance keeps its cue and priority, so it shrinks from the FX amount of the stack that let it go rather
  than jumping to full as its fade starts. Programmer, manual and Include FX own no cue and are
  never weighed. The weight is read per pass (one map read, none at all while every stack is at
  full), and provenance is re-sent on every change of an amount (`CueStackManager.setFxAmount`), since
  a discrete output — a setting, a step — flips on stage at a weight of one half. On the wire: `GET/PUT
  /projects/{id}/cue-stacks/{sid}/fx-amount` `{amount}` (400 `STACK_FX_AMOUNT_INVALID` outside 0–1,
  404 for another project's stack, current project only, both roles — a runtime gesture on a venue),
  the `stackFxAmount.state` / `.set` frames (`docs/websocket-engineering.md` §"Stack FX amount")
  and the MIDI `stackFxAmount` fader (`docs/midi-control-surface-engineering.md`). The frontend's
  *FX amount* slider sits on the stack's row in the Show view and in the phone runner.
- **Direction** (D5): `FORWARD`, `BACKWARD` or `BOUNCE`, applied to each member's phase after the
  spread (§3.3 step 6, `FxDirection.apply`): Backward is `1 − p`, kept inside `[0, 1)` so an
  FX indexing a list by `floor(phase × n)` never reads one past its end; Bounce is Forward on
  even cycles and Backward on odd ones, counted on the member's own clock (its offset taken off). It
  reaches every phase an FX reads; a **stateful** FX (`calculateStateful`) reads no phase,
  so direction does nothing to it. The built-ins keep their shapes (P6): Ramp Down stays. The
  sweeps', Colour Fade's and Focus Pull's `pingPong` went to Bounce in the terminology plan (D24), a
  stored one rewritten at half the cycle with its phase offset, spread and runs doubled, which plays
  the same (`LegacyFxOutputTest`).
- **Runs** (D9): `runs`, at least 1, null for forever. The count runs on the FX's clock from
  its first pass with a count — a master's tick (`FxInstance.runsStartTick`) for a beat FX, the
  scaled accumulator (`runsStartScaledMs`) for a wall-clock one — so a count given to a running
  FX runs from then, and its first cycle starts at the top whatever the master's bar. A member
  runs while its own clock, the FX's less its distribution offset, is in `[0, N)`: it holds what
  is underneath before its offset comes round and after its own N, so a one-shot chase ripples in
  and out rather than showing a tail at the start. The phase functions answer `NaN` for a holding
  member (`FxInstance.isHolding`) and the engine applies nothing for it. The FX is **spent** once
  the clock has run N cycles past the largest member offset — some member's phase was computed on
  the pass and none has runs left (`runsPending` / `runsEvaluated`, tick-loop owned) — and
  `FxEngine.finishRuns` ends it after the pass's frame is out: removed, which resets what it alone
  covered, **unless a programmer layer spawned it**, which is kept `spent`, painting nothing, because
  the layer's recook would otherwise spawn it afresh and fire it again on the next unrelated
  programmer write; the layer's release removes it. A new count (`updateFx`'s `newRuns`, or
  `clearRuns` for forever) re-anchors from the next pass and wakes a spent instance. An FX that
  is **held** — Blind, a stomp, the programmer's value — still computes its phases, so its count runs
  on while it is held, as its clock does; a **paused** one is not processed and does not finish. An
  expansion with no members evaluates nothing, so a group emptied by a reload does not end an
  FX.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `amount`, `direction` and `runs`;
  `PUT /fx/{id}` and `updateFx` take all three with null meaning keep, plus `runForever: true` to
  clear a count (beside a `runs`, refused). `FxDto` (and the group and indirect DTOs) reports
  `amount`, `direction`, `runs` and `spent`; `programmer.keyStack`'s FX layers report `amount` and
  `direction`. The MCP FX schemas take the three (`AiToolSchemas.putFxDynamics`, parsed by
  `AiTools.parseFxDynamics`), and `describe_rig` and `get_current_state` say them where they are
  not the default.
- **`KeyStackLayer.onStage` weighs it**: an FX weighted to nothing (a 0 % amount, spent runs)
  is not on stage, and only a **full-weight** `OVERRIDE` covers the layers below it — under full
  amount it is a mix with what is underneath, so both are on stage (a setting covers from half weight
  up, as it writes).

### What the UI calls them: *Around* and *Within*

The live FX editor (`FxEditor`, fixture-fx-sheets plan D12, D12a) never shows the operator
`ADDITIVE` or `MULTIPLY` for the two FX where the choice is the whole point; it asks a question
in the desk's words and sends an existing spelling (plan P2 — no new field, no `formatVersion`):

- **Movement — *Centre: Around current position | Absolute*.** A position FX with a centre pair it
  draws: a Path on Circle, Figure 8 or Random, never a Line. **Around** is `ADDITIVE` with `panCentre` and `tiltCentre` at
  **128**: the position blend is `base + FX − 128`, so a shape centred on 128 orbits whatever the
  reset pass put underneath — the programmer's value, else the cue's, else the base. That is the only
  way a Circle orbits a set position; under `OVERRIDE` it circles its own 128/128. **Absolute** is
  `OVERRIDE` with the centre shown. The picker starts movement FX Around.
- **Level — *Over the level underneath: Replace it | Within it*.** The Intensity family. **Replace**
  is `OVERRIDE` (today's default); **Within** is `MULTIPLY`, so the FX runs inside the level
  underneath, scaled by it.

Reading back is by spelling, not by flag: a stored `ADDITIVE` with both centres at 128 **is**
Around, whoever stored it; `OVERRIDE` is Absolute or Replace; anything else (`MAX`, an `ADDITIVE`
with a centre of its own) presses neither and the editor's Advanced section still shows the raw
blend, which stays editable there for every FX. FX palettes ask the same Centre question
in session 5 (D16); stored palettes keep `OVERRIDE` and read as Absolute (call 6).

### Reading `blendMode` and friends out of a string

`blendMode`, `order`, `elementMode` and `elementFilter` all arrive as strings —
from a REST body, from a stored `look_fx` / `cue_ad_hoc_fx` / `cue_layers` row — and
`fx/FxSpecCoercion.kt` is the only place any of them is parsed. It offers exactly two named
policies, because there are exactly two situations:

* **`Strict`** — a request body stated the value, so an unrecognised one is the caller's bug and
  throws `IllegalArgumentException`. Used by `POST /fx/add`, `PUT /fx/{id}` and
  `POST /groups/{name}/fx`, each of which answers **400**. Note the 400 comes from the handler's
  own `catch`, not from `plugins/ErrorHandling.kt` — it registers no `IllegalArgumentException`
  clause, so a *new* strict call site needs its own.
* **`Lenient`** — a stored row is already on the desk and the cue has to fire, so an unrecognised
  value warns and takes the field's default. Used by `FxSpawner.createFxInstance` and
  `CueComposer`'s layer blend. A `null` optional field is silent; a present-but-blank one warns,
  since every column that holds these is either nullable or NOT NULL with a default, so `""` means
  a row was written wrong.

Both accept any casing and tolerate surrounding whitespace. **The strict path is stricter than it
was before sweep item E4**: `order` and `elementFilter` used to degrade an
unrecognised value to `NORMAL` / `ALL` and add the FX anyway, so a legacy or typo'd value from
a MIDI, AI or third-party caller now loses the whole request instead of one attribute. The shipped
UI only ever sends canonical names, so nothing in-tree changes.

### Attribute Reset

Before processing FX each tick, the engine resets all FX-controlled attributes to
the value of **the tier below** (not to a hardcoded neutral). This is a core invariant of
the composition model — see
[lighting-composition-model.md §Tier 3 — FX](lighting-composition-model.md#tier-3--fx).

The fallback is resolved by `LayerResolver.fallbackFor(target, fixture, key)`, which
composes:

1. **Tier 2 (programmer)** — sticky manual attribute entries and the raw-channel sideband
   held in `ProgrammerStore`, unless the blind gate is engaged. A programmer entry wins
   over everything below, and additionally **suppresses** the FX's own apply on that
   (fixture, attribute) — see below.
2. **Tier 4** — `CueAssignmentResolver`'s composed cue attribute assignment for this target, if
   any. (The class name predates the renumber.)
3. **Tier 5** — fixture baseline (0 for sliders, black for colour, 128 for pan/tilt).

### Programmer suppression and the priority band

An active programmer entry (blind off) suppresses **every** FX on its (fixture,
attribute) — cue-owned and manual alike: the reset pass paints the programmer value and the
apply loop skips that pair (per fixture, so a group FX keeps painting other members, and
per attribute, so an FX on another attribute of the same fixture keeps running). FX in the reserved
**programmer priority band** (`FxEngine.PROGRAMMER_FX_PRIORITY_BASE`, strictly above every
cue-derived priority) are exempt — they are programmer-owned FX and modulate on top of
programmer values. The suppression snapshot is rebuilt only when `ProgrammerStore.epoch`
changes, keeping the 50 Hz path allocation-flat.

Consequences:

- Manual values (`updateChannel` shim, MIDI surface faders — programmer entries) persist
  visibly under running FX, and on attributes they hold, *win outright* — the FX
  freezes until the entry clears, then resumes on the next tick.
- `MAX` and `ADDITIVE` blend modes do not ratchet upward across ticks — the reset clears
  the accumulator before each FX iteration.
- Parked channels are skipped entirely by the reset + apply pass (`ParkManager.isParked`
  check), both as an optimisation and because the transmit-time parking override would
  discard the composition result anyway.

**Blind holds the band** (show-cue-editing plan D7, §3.6). While the programmer is blind, every
instance in the band — a busk, Include's children, a programmer layer's — is held: `fx/BlindHold.kt`,
`blind && isProgrammerFxPriority(priority)`, asked by the compose pass beside `FxSuppression`
(whose rule is unchanged) against **one read of blind per pass**, the same read that empties the
suppression map, so a Blind toggle landing mid-pass cannot pair an empty map with an unheld band. A
held instance keeps its phase and skips only its apply: a beat FX's phase is its master's tick,
a wall-clock FX's clock — including a beat FX on *Seconds*, which runs on that loop —
advances on every pass before anything is applied, and a stateful FX is still computed. Leaving
Blind lands it in step with what it started beside, growing from nothing over the Blind fade like a
press — or at full at once over a value the programmer holds (effects plan §3.5;
`ProgrammerWriter.setBlind` ramps the band's envelopes before the gate opens, so no pass paints one
at full first). Provenance leaves a held FX out of its winners and the attribute stack lists it `heldBack`
with reason `BLIND`.

**Local FX sit above every layer's FX** (FX editor plan D5, §3.5). Within the band, a programmer
layer's FX sit at `BASE + layer index` (`ProgrammerLayerStack.priorityFor`, clamped at
`PROGRAMMER_FX_RANK_CLAMP`) and Local FX (`FxInstance.isLocalFx`) at
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
- **`programmer.moveFx {fxId, above}`** (`FxEngine.moveLocalFx`) re-ranks a Local FX directly
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
  That covers Record into a cue (`fxInstancesToCueChildren`) and into a Look (`writeLookFx`,
  absorb-fx too), a stage snapshot, and Update's write-back into either. A write that keeps some
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
  Record and Update give the same origin to each FX they write into a cue, naming the child it
  became (`stampWrittenFxOrigins`), so a later Include does not run that child twice and Update
  writes back onto it. Record and a Mode A Update replace an origin naming another cue, since the
  write makes theirs the cue the programmer edits; a Mode B Update into one cue only fills an
  origin naming no other cue, and one into several gives none.

**One rule, three readers** (fixture-fx-sheets plan W1, W4). The rule is
`fx/FxSuppression.kt` — a pure function of a suppression snapshot, the stomp check, the
(fixture, attribute) and the FX — and the compose pass, provenance (`ProvenanceService.compute`)
and the attribute-stack read behind `programmer.keyStack` (`ProvenanceService.keyStack`) all ask it,
against the engine's own snapshot (`FxEngine.programmerSuppression`, empty while blind). That is
what lets the fixture sheet mark an FX *held back*: the mark is the tick's answer, not a second
copy of it. Never write the rule again beside a reader.

**The programmer outranks an FX only where the engine holds it back, on the key the FX
paints.** The snapshot is `ProgrammerStore.activeAttributesByFixture` — attribute entries, each
holding back FX on **its own key** alone. Two holds suppress nothing, and provenance credits
an FX for both: a raw-channel sideband slot (an unpark hand-down, a Channels-tab write on a
head with no pan attribute, filed under `position`), and a `pan` entry under a Circle keyed
`position` (the Channels-tab pan on a head that declares pan, which `updateChannel` lifts to its
own slider). An FX painting **every** channel of a programmer-held key under a sibling key takes
the key; one painting some of them (a UV wave beside an RGBW colour entry) does not. Before W4 both
holds read *Programmer* while the Circle was what the rig showed — the plan's issue 1.

**Live edits** (W3). `updateFx` on the socket and `PUT /fx/{id}` share one parse,
`parseFxUpdate` under `applyFxUpdate` (`routes/lightFx.kt`; the FX preview runs the same parse on a
copy), and both land in `FxEngine.updateFx`, which keeps the
instance's id, phase, layer identity and fade. A type swap takes the new type's timing source
(`newTimingSource`), as the add path does — before, a beat FX swapped for a wall-clock one kept
running on the beat loop. A refusal (`FX_UPDATE_REFUSED`: an unknown blend, type or master uuid;
`FX_NOT_FOUND`; a split of an FX that is not Local, `FX_SPLIT_LAYER_FX` / `FX_SPLIT_NOT_LOCAL`)
is decided before anything moves: a 400 / 404 / 409 on REST, an `fxError` on the socket. With
`targets` naming some of its heads the edit splits them off instead (§"Local FX follow the
selection").
Its client is the live editor (`FxEditor`, session 3): every control writes the draft through
`useLivePush` at a 50 ms floor (an optional field only once the operator has set it), and the frontend toasts each `fxError` keyed per FX
(`store/fixtureFx.ts`), so a drag refused on every frame is one toast.

**Reset to palette** (W5). `POST /fx/{id}/reset` re-applies a programmer palette layer's
**current** FX to the instance it spawned, through `updateFx` — id and phase kept — with
every setting the layer would spawn it with, its overrides included, both masters set exactly
(`replaceMasters`, which can clear a master as the single-master arguments cannot). It also moves
the instance's spawn key to the palette's current entry, under the stack's FX lock, so the
next recook keeps it rather than respawning it. 409 `FX_NOT_FROM_PALETTE` for anything no
programmer palette layer started (a Look's, a manual one, a cue's palette layer, which the next GO
respawns); 409 `FX_PALETTE_GONE` when the palette holds no FX any more.

**What a recook does to an edited instance.** `ProgrammerLayerStack.syncFx` matches a live
instance by the `StoredFx` it was **spawned** from (`ProgrammerLayerFxKey`), never by its
current parameters, and `updateFx` carries that key across its swap — so an instance edited
through `updateFx` survives a patch, a move or an unrelated add. An FX palette's `PUT` recooks
values only and leaves the instance alone, but the next recook of the stack *for any reason* reads
the edited palette, whose entry no longer matches the instance's key, and respawns it from the
palette — its phase restarted, any instance edit gone. Reset is the per-instance way back. Pinned
by `FxLiveEditRoutesTest`; see `FU-TMPL-FX-EDIT-NO-RETIME`.

### Locate versus park

The Locate toggle (`routes/lightLocate.kt`) asserts its centre-and-open-white values as
programmer entries (owner `locate`). "Locate wins" is **non-destructive** since the
programmer redesign: the entries suppress every FX covering the written attributes for
as long as the locate holds, and releasing the locate lets them resume — nothing is
removed. (`ToggleLocateResponse.effectsRemoved` was dropped in Session 2, once the frontend
that read it no longer existed.)

Park sits above all of that at transmit time, so a locate cannot move a parked channel.
One filter keeps the sides honest: **`applyLocate` drops unpublishable assignments** using
`ProgrammerWriter.publishability`. That helper applies the *same two guards the cascade
publish applies*, in the same order and through the same helpers
(`inferTargetForAttribute`, then `allChannelsParked`), so the pre-filter cannot disagree
with what the write then does — which matters because `ColourTarget` scopes its
white/amber/UV channels by `bundleWithColour` while `AttributeChannelWriter.channelsFor`
enumerates them by trait. It reports `UNRESOLVED` (no DMX-backed channels) and
`PARK_MASKED` separately, and the latter surfaces as `parkMasked` on the toggle response so
the UI can distinguish "parked" from "nothing to write".

Consequences:

- A wholly parked fixture or group is a no-op locate: zero writes, `active: false`,
  `parkMasked: true`, nothing registered in `LocateManager`, and no FX is disturbed.
- Partial parking still locates: the unparked attributes are asserted (suppressing the
  FX on *those* attributes), while parked channels keep emitting their parked value.
- A target that becomes park-masked while located keeps its locate state. `LocateManager`'s
  re-assert path drops an entry only when the assert callback reports the target *stale*
  (returns null), not merely write-less, so releasing an overlapping group locate can't
  silently un-locate a parked member.
- Unparking a channel that a locate wrote to hands the *parked* value down into the
  programmer's channel sideband (see `UnparkValueSink`); the hand-down is newer than the
  locate entry, so recency arbitration keeps the output at the parked value rather than
  snapping up to the locate level.

Covered by `routes/LocateParkInteractionTest.kt`.

### FX iteration order

FX are folded in **apply order** — `priority` ascending, then `composeOrder`, then `id` —
rather than from a `ConcurrentHashMap`, beat and wall-clock alike (§"Architecture: one compose pass"). An
FX's `composeOrder` is its own id, except that a cue re-stamps the instances it carries over
into its layer order (`restampComposeOrder`) and a copy split off an FX keeps its parent's, so
same-priority FX compose in the order they were placed rather than the order they were
created; `id` breaks only what is left. Multi-FX composition on the same attribute is
therefore **deterministic** — previously the effective "last-wins" ordering was undefined. A home
spawns its FX in their stored `sortOrder`, which Record writes in this order, so the order an
operator built in the programmer is the one a cue or Look plays back
(§"Programmer suppression and the priority band").

Priority assignment:

- Manual FX: `priority = 0`
- Cue-owned FX: `priority = stackId * 1_000_000 + sortOrder * 1_000 + 1`
  (leaves gaps for future fine-tuning without renumbering)
- A programmer layer's FX: `PROGRAMMER_FX_PRIORITY_BASE + layer index`
- Local FX: `PROGRAMMER_FX_PRIORITY_BASE + LOCAL_OFFSET + localRank`, above every layer's
  (§"Programmer suppression and the priority band")

The sorted snapshot is cached in one `@Volatile` field (`sortedFx`) and rebuilt under a
`synchronized` block on every mutation. The compose pass reads it lock-free.

### Instance dynamics

The per-instance parameters that request threads may change while the compose pass reads them —
`isRunning`, `phaseOffset`, `timing`, `order`, `elementMode`,
`elementFilter`, and since the effects plan's session 1 `amount`, `direction` and `runs` — live in one immutable `FxDynamics` value behind a single atomic reference
(`FxInstance.dynamics`), not in individual fields. A tick pass reads the snapshot once per
FX and works from it throughout (the member phase functions *require* it as a parameter),
so a concurrent edit lands wholly on one pass or the next, never half-way through; writers
replace the whole value atomically, so `updateFx`'s multi-field changes are indivisible.
The cell is shared across `updateFx`'s instance swap, which is what makes a pause/resume
racing a swap unlosable. Everything else on `FxInstance` is either immutable, written only
before the instance is published to the engine (`timingSource`), an independent `@Volatile`
scalar with no cross-field coupling (`priority`, the master slots, `accumulatedScaledMs`, `spent`),
one immutable value behind an `AtomicReference` changed only by compare-and-set (the envelope,
§"Amount, direction and runs"), tick-loop-owned plain fields
(the Runs anchors and per-pass flags), or tick-written diagnostics (`lastPhase`, stored once per
pass).

Anything that persists or reports several dynamics fields together (Record, programmer
capture, cue capture) must also read them from one snapshot — the `!= default → else null`
idiom read twice across a concurrent edit records an explicit value where track-the-default
`null` was meant.

### Phase Calculation

The phase passed to each FX determines where in the cycle it evaluates. For group/multi-element
FX, `FxInstance.calculatePhaseForMember` computes, through the pure `FxPhase.beatPhaseAt` (its
wall-clock twin `wallClockPhaseAt`; §"Previewing an FX"):

```
memberPhase = (baseClock + phaseOffset - distributionOffset) % 1.0
```

The distribution offset is **subtracted** so that higher-offset members are behind in the
cycle. This makes the visual sweep flow in the natural direction (element 0 → N for NORMAL).

For the PING_PONG Order, a triangle wave remap is applied to the base clock phase before
adding offsets, causing all FX to sweep forward then backward:

```
tri = baseClock < 0.5 ? baseClock * 2 : 2 * (1 - baseClock)    // [0→1→0]
remappedClock = tri * (slots - 1) / slots                        // [0→maxOffset→0]
memberPhase = (remappedClock + phaseOffset - distOffset) % 1.0
```

The scaling to `(slots - 1) / slots` ensures the sweep reaches the last element without
wrapping back to the first (since `1.0 % 1.0 == 0.0` would alias with element 0). `slots` is the
Order's own count (`SpreadOrder.distinctSlots`), **not** the count after the spread's
shape: Spread, Wings, Segments and Parts set the members' offsets, as under every other Order, and
leave the sweep alone — so Spread 0 or Segments folding every head to one slot never scales the sweep to
nothing, and an FX that names no shape bounces bit for bit as before (P6;
`SpreadShapeTest` "ping-pong sweeps the same way whatever the shape").

Then, on that phase, the FX's **direction** and **runs** (§"Amount, direction and runs"): a
member outside its runs answers `NaN` and paints nothing, and Backward and Bounce read the phase
backward on the member's own cycles. For an FX with a run count the clock is counted from the FX's start
rather than the master's tick 0. A forever FX's phase is exactly what it was — the clock is
still split into whole cycles and a fraction in integer arithmetic — and the whole-target form
(`calculatePhase`) is the same function at offset 0 — except for a copy split off a spread
(`FxInstance.splitMember`), which makes its member's call: that member's offset and group size.

### FxContext

FX receive an `FxContext` alongside the phase, providing distribution metadata:

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

There is no static FX: a fixed level, colour, position or setting is a value on the fixture's
own attribute (the programmer, a Look, a cue), and StaticValue, StaticSetting, StaticColour and
StaticPosition were removed for that reason. The windowing they used survives as a technique for a
user FX that should read as a **step chase** — one member lit at a time — by only being "on"
for a window of each cycle. The window width is `1 / numDistinctSlots`, which accounts for
symmetric Orders where multiple members share an offset.

For standard Orders (NORMAL, CENTRE_OUT, etc.), a modular distance check determines
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

### Timing

Timing controls whether the cycle represents the **total cycle time** or the
**per-step time** for distributed FX.

| Mode | Cycle Meaning | Example (4 heads, 1-beat cycle) |
|------|----------------------|-------------------------------------|
| `timing = CYCLE` | Total cycle time | Full sweep in 1 beat (each head active for ¼ beat) |
| `timing = HEAD` | Per-step time | Each head active for 1 beat, total sweep = 4 beats |

When `timing` is `HEAD` and the FX is distributed across a group, the effective
cycle is scaled:

```
effectiveCycle = cycle × dynamics.distinctSlots(groupSize)
```

This uses `distinctSlots` rather than `groupSize` so that symmetric Orders
(CENTRE_OUT, CENTRE_IN) scale correctly — symmetric pairs share a slot — and it is the count **after the
spread's shape** (§"The spread's shape"), so Segments and Parts leave no empty steps either.

**Default values**: Each FX type declares `defaultTiming` which is used when
creating new `FxInstance`s. A step chase (a user FX windowed as above) declares `HEAD`;
every built-in is a continuous FX (Wave, Flicker, etc.) and defaults to `CYCLE` (full
cycle). The value can be overridden per-instance via the API.

## Previewing an FX

Nothing on the stage evaluates an FX for a moment it has not reached, so the FX editor's graph (FX
editor plan D7) comes from `fx/FxSampler.kt`: an FX's output on each head over a window, for a running
FX or a draft (an `FxInstance` never added to the engine), with the engine's own maths and none of its
state (P3, P4). `FxEngine.sampler(freshFx)` builds one over the engine; the preview route
(`routes/fxPreview.kt`, below) is its only caller.

**What it reuses.** The members and their offsets are the compose pass's: `FxEngine`'s expansion and
`DistributionPlan`, read through the instance's caches while they are valid and built, never stored,
when they are not. The order is the engine's own `sortedFxComparator`, so Local FX fold above
every programmer layer's FX (§"Programmer suppression and the priority band"). A step is resolved
per head by its target (`outputByte`, `outputColour`, `outputPosition`, `outputLevel` on the targets,
which `applyValueToFixture` calls too). What is held back is `FxSuppression.heldBackReason`,
the rule the attribute-stack read asks, against one snapshot of the programmer and Blind.

**What it splits out.** Two pieces were fused with state and are now pure, with the engine calling
them:

- `fx/FxPhase.kt`: a member's phase from one reading of its clock — a master tick (`beatPhaseAt`) or
  scaled milliseconds (`wallClockPhaseAt`) — its dynamics and its Runs anchor, or NaN while it holds.
  The instance keeps its writes outside: anchoring `runsStartTick` / `runsStartScaledMs`,
  `lastPhase`, and the Runs tally (`RunsTally`) the compose pass reads to end a spent FX.
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
the frame a paused FX left; *Result* is that frame, read off the head. *Result* is before Tier 1: a
row whose channels are all parked is marked `parked`, and the park is what is sent. Each attribute
is folded on its own, so where two share channels (a colour FX's bundled W, A and UV beside a `uv`
FX, `pan` beside `position`) the stage's single fold is not reproduced
(`FU-FX-PREVIEW-SHARED-CHANNELS`). *Include* `STACK` folds every running FX reaching the rows as well
as the ones asked for; `ONLY` folds those alone.

**Drafts.** A draft is ordered where adding it would put it — a Local draft at the top of its lane,
any draft after the running FX of its priority — and runs on the masters its uuids name, which only
adding it would bind to slots. An edited copy of a running FX (`FxEngine.editedCopy`) carries its id:
it sorts where that FX is, and under *Include* `STACK` it stands in for it rather than beside it.

**Clocks.** Sampling starts from one reading of every clock (`FxSampleClock`: each master's tick and
its length, each rate master's scale). A beat FX's tick at a sample is its master's tick plus the
sample's offset in that master's ticks, so it lands on the ticks the stage paints; a wall-clock FX's
is its accumulator plus the offset at its rate. A Runs FX counts from its anchor, or from the window's
start before its first pass.

**Stateful FX.** Candle Flicker and Fluorescent Flicker keep state, shared on stage by every member.
The sampler runs a fresh object per head, `initialize()`d and stepped once a sample from the window's
start, its tick count starting at 0 so the same request answers the same, and marks the FX
`representative`: its shape, not the stage's frames. Every other FX is evaluated on a fresh FX
object too (`freshFx`, `createFxWithPalettes` in production), so a sample never shares one
with the pass.

**Caps.** 48 samples to a cycle of the first FX asked for (its whole cycle, *One cycle for: Each head*
included), at most 1024 a row; at most 96 rows and 8 FX a request. Past a cap the answer is cut and
`truncated` names which. A row is a head; its samples are a byte a sample per plane — a slider or a
setting's level, RGB plus each bundled W, A and UV the head has, or pan and tilt — and a Steps FX's
own output carries the step it shows beside the steps' labels.

**Nothing changes.** The sampler never writes an instance's clocks, Runs anchors, stateful state,
envelope or `lastPhase`, or its expansion and plan caches, and never writes DMX: the one transaction it makes is
a staging one it never applies, to read a paused FX's frame off a head. `FxSamplerParityTest`
drives the engine a pass at a time and holds *Result* to the committed DMX at every sampled tick —
every Order with Spread, Wings, Segments and Parts set, Runs with Bounce, *One cycle for: Each head*, *Replace*
under *Within*, a Circle *Around* a programmer position, Amount, Palette Steps, a Seconds FX, each
element mode and filter, a held-back FX, a Local FX over a layer's, and a beat FX with a wall-clock FX
*Within* it — and checks that sampling leaves every instance field and the controller as they were.

**The route.** `POST /projects/{id}/fx/preview` asks the sampler, current project only (409
otherwise), both roles, and writes nothing: no FX is added, no instance field moves, no DMX is sent.
The project write guard counts it a runtime gesture, so an operator previews on a venue too.

- **The request**: `targets` (the selection, in order), `fx`, `include` (`STACK` | `ONLY`), `window`
  (one of `cycles` of the first FX, `beats` of its master — a wall-clock FX's rate master, master 1
  for one on its own seconds — or `seconds`; two cycles when none) and `solo` (an index into the
  first 8 of `fx`). Each `fx` entry is `{fxId}`, a running FX; `{draft}`, `POST /fx/add`'s
  body, built by `buildFxInstance` — the add's own write boundary — and never added, its `targets` the
  request's when it names none; or `{fxId, draft}`, the running FX with `draft`, `PUT /fx/{id}`'s
  body, run through `parseFxUpdate` (the parse `applyFxUpdate` and the `updateFx` frame share) and
  applied to a copy. A draft move naming some of the FX's heads is refused: a split is not previewed.
- **The rows** are the heads `targets` cover (as a press covers them; a target the rig lacks is a
  problem) that the sampled FX paint — under `STACK`, that any running FX paints too, so every FX
  reaching the selection is folded — or, with no targets, the asked-for FX's heads. They are grouped
  by fixture: fixtures in the order of their first head under the first FX's Order, each one's heads
  together in that Order, ties in rig order — the order the FX's own walks list the heads. Each row
  carries its `rigIndex`, so the editor can hold rig order, and the row cap cuts at a fixture's end,
  dropping whole fixtures (a single fixture longer than the cap is cut at it).
- **Notional heads** (FX editor plan D15): `notional: n` (1–96) draws the drafts on *n* heads the
  rig does not have — what the FX palette editor's strip shows with nothing selected. Each draft
  targets the group of a scratch register (`fx/NotionalRig.kt`): `NotionalHead`s, a channel for
  every attribute an FX palette can drive (dimmer, RGB, UV, pan and tilt, zoom, focus, iris, frost),
  never patched, on no controller, and of a type `FixtureTypeRegistry` does not list. The route runs
  the draft through an `FxEngine` of its own over that register, sharing the show's speed-master bank,
  so the members, the spread, the fold and the clocks are the engine's; what is underneath is each
  head's base (dimmer 0, black, centre). Rows are `Head 1…n`. It takes no `targets`, drafts only and
  `include: ONLY` — each named as a problem otherwise — and a draft whose attribute no notional head
  has (Slot Steps' setting) is the add's own refusal. Nothing on the rig is read or written.
- **The answer**: `window` (`samples`, `sampleMs`, the length in the first FX's master's `beats` and in
  `seconds`), `clock` (each master the FX run on with its `tickAtStart` and `msPerBeat`, or
  `seconds: true` for FX on their own clock, which carries no start time: its playhead runs from
  sample 0), `rows`, `lanes` (by family, Intensity,
  Colour, Position, Beam; each attribute's rows with `underneath` as one sample, `result`, and each
  FX's own `output` with its `heldBackReason`), `fx` (every FX folded, in apply order: its `fxId`
  and, when asked for, its `entry` in the request; per row its `offsets`, the samples its cycle
  `starts` at, the inclusive `holding` ranges and its `ladder`, null rungs for a split-off copy,
  which carries its offset but not the ranks it came from; its `slots` and `representative`) and
  `truncated`. A series is base64 bytes a plane — `level`; `red`, `green`, `blue` and the head's
  `white`, `amber`, `uv`; `pan`, `tilt` — and a Steps FX's output adds `steps` beside `stepLabels`.
- **The offset ladder** is each row's rank and rank count after Order, Segments, Wings and Parts, then
  its offset (Spread): `SpreadShaping.shape` fills it as it shapes (`SpreadShaping.Ladder`), from the
  ranks `DistributionPlan` gives it, so it is the plan's own steps. A row's offset is Spread × its
  Parts rank over the Parts count, which is how the editor's Spread handle keeps a start under the
  finger. A cycle's start is where the member's own clock with its Phase
  (`FxPhase.beatOwnCyclesAt` / `wallClockOwnCyclesAt`, the clock Bounce counts on; Runs counts it less
  Phase) crosses a whole number: where its own cycle begins. That clock is the FX's less the offset
  plus the Phase, so a larger offset starts later and a larger Phase sooner.
  Under Ping-pong, whose triangle sweep is shaped from that cycle, the turnarounds fall elsewhere.
- **One snapshot.** The rows, the samples and solo read each FX once (`FxSampler.snapshot()`), so an
  edit landing mid-request cannot order the rows by one read and sample them by another.
- **Solo** samples the solo FX alone (`ONLY`) on the same clock and redraws its family's lane: its
  attribute's *Result* as it alone leaves it, every other attribute in the lane what is underneath (D14).
- **Caps**: the sampler's (48 samples a cycle, 1024 a row, 96 rows, 8 FX), each cutting the answer and
  named in `truncated` (`SAMPLES`, `ROWS`, `FX`); and a window longer than 64 cycles, 256 beats or 600
  seconds is refused. An FX past the 8 brings no rows and is not folded under `STACK`. 400
  `FX_PREVIEW_INVALID` names every problem at once — an unreadable or refused draft, an FX not
  running, a target the rig lacks, a bad window, `include` or `solo` — in `problems`, each prefixed by where it is
  (`fx[2]: …`, `window.cycles: …`).

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

FX target fixture or element attributes via `FxTarget` subclasses. Targets can reference a single fixture, an entire group, or an ordered selection of them using `FxTargetRef`. The `applyValueToFixture` method accepts `GroupableFixture` (not `Fixture`), allowing it to work with both standalone fixtures and fixture elements:

### Target Reference Types

```kotlin
sealed class FxTargetRef {
    data class FixtureRef(val targetKey: String)              // Single fixture (or element)
    data class GroupRef(val targetKey: String)                // Fixture group
    data class SelectionRef(val selection: FxTargetsRef.Selection)  // Fixtures and groups, in order
}
```

### Over a selection (effects plan session 3)

One FX may run over an **ordered list of fixtures and groups** (effects plan D3), spread across
them in that order as a group's members are: a marquee in grid order, a busk selection in the order
pressed. Before this a Local FX over loose fixtures ran them in unison, one instance each.

- **The type is FX-only.** `models/TargetRef.kt` keeps `TargetRef` to fixture and group, and adds
  `FxTargetsRef` beside it — `Single(TargetRef)` or `Selection(items)`. A value row, a layer's
  targets and a binding hold a `TargetRef`, so they cannot hold a selection, and the write boundaries
  that parse one refuse it by name (`validateCueChildren` for a cue's rows and layers,
  `validateLookRows` for a Look's). An FX row — a cue's ad-hoc FX, a Look FX, an
  `FxEntry` — holds an `FxTargetsRef`.
- **Stored as the pair it always was.** `targetType: "selection"` and a `targetKey` holding the list's
  canonical JSON, `[{"key":"par-1","type":"fixture"},{"key":"Front wash","type":"group"}]` (keys
  alphabetical, no whitespace, `FxTargetsRef.Selection.key`), so no column was added; sync carries it
  from `formatVersion` 34. `FxTargetsRef.ofOrNull` refuses a key that is not a non-empty list of
  fixtures and groups and reads a one-item list as its one target; a cue's or a Look's write stores the
  pair in that canonical spelling (`FxTargetsRef.canonical`), so a hand-written row pairs with what
  Record writes. `target_key` is `text` on both FX tables, since a selection outgrows 255.
- **The expansion** (`FxEngine.buildSelectionExpansion`) flattens the list in order: a fixture or an element
  as itself, a group as its members in their own order. Each unit is a **member** where it has the
  attribute, else its **heads** where its elements do, else skipped; a head reached twice counts once,
  at its first place, and an item the rig has lost is skipped as a missing member is. Every unit a
  member is `GROUP_MEMBERS`, spaced evenly in selection order (so `POSITION` is selection order too);
  every unit its heads is `GROUP_ELEMENTS`, exactly as a group of multi-head fixtures, `elementMode`
  choosing per fixture or flat; a mix is `GROUP_ELEMENTS` too, a member being a unit of one head, so
  per fixture each multi-head fixture runs across its own heads beside the members. The members go through
  `DistributionPlan.of` like a group's, so the spread's shape applies unchanged. The compose pass sends a
  selection down `processGroupFx`.
- **Coverage and reach.** `fixtureKeysCoveredBy` answers the heads in order;
  `isAttributeCoveredByAny` also answers the units (the parents of heads it paints), as it does for a
  group; `getIndirectFxForFixture` lists a selection FX for every fixture it names, which
  `IndirectFxDto.targetType` marks. `getFxForFixture` and `getFxForGroup` never see one:
  the canonical key names neither. Another cue's stomp takes one off only where it asserts **every**
  item on the attribute, as it takes a group's FX off only where it asserts the group.
- **The doors.** `POST /fx/add` takes `targets` in place of `fixtureKey` — one item is that fixture or
  group alone, several a selection, every item checked against the rig at once and a trigger or a
  command refused for any item that has one. `FxDto` reports `targetType` and, for a selection,
  `targets`. Record writes a Local FX over a selection as one ad-hoc cue FX, or one Look FX,
  with its `selection` target (`fxInstancesToCueChildren`, `writeLookFx`); Include spawns it back as
  one (`FxSpawner.resolveTargetForCue`); a scoped Record or a masked Include reads it as in scope
  only when every item is (`fxTargetInScope`) and by the family of its first item that resolves
  (`Fixtures.referenceFixtureFor`). MCP's `create_cue` takes an ad-hoc FX's `targets` instead of
  `targetType` and `targetKey`, and `clear_fx` naming targets clears a selection FX only when
  every item it names is among them. A rename rewrites the keys inside a stored selection (`ai/RigKeyReferences.kt`), and a
  venue Update's dead-reference report names each item (`venue/VenueUpdate.kt`).
- **Not here**: a palette layer's FX still fans one instance per target. The programmer's bare
  FX press and the split are built on this — §"Local FX follow the selection" below.

### Local FX follow the selection (apply-model plan session 2)

MagicQ's rule (apply-model plan D5, §3.4): a bare library FX is a **Local** FX on the whole
selection — the programmer's own, in its priority band and owned by no layer, Look or cue
(`FxInstance.isLocalFx`) — and an edit follows the selection: with every head the FX runs on
selected it changes in place, phase kept; with some selected, those heads split off at the phase they
had.

**The bare FX press**, `POST /api/rest/projects/{id}/programmer/fx`
(`routes/programmerFx.kt`): `{fxType, attribute, targets, families?, parameters?}` plus
every FX field `POST /fx/add` takes, built by the same `buildFxInstance`, so one FX over
the selection as the add route makes it (a group one group FX, several targets one selection
FX). The route fixes only where it runs and whose it is:

- **The mask.** The FX's family must be in the selection's mask, else 400 `FX_OUTSIDE_MASK`;
  no targets is 400 `FX_NEEDS_SELECTION`. The family is `routes/programmerFx.kt`'s
  `fxFamily`: the family of the attribute it drives on the first head it paints
  (`familyForAttribute`, as Record and Include read an FX), so an Intensity Pulse on `uv`
  is Colour. Agreed with Chris during session 2; an FX palette's family is
  `fxAttributeFamily` over its FX's library family and the attribute it names (fx-settings plan
  D5), Intensity for one naming none.
- **The timing.** No `cycle` is one bar; a beat FX naming no master and not on *Seconds* is
  stamped with the master whose `family` is its library `family`, else master 1 (venue-visit plan
  D16) — `fx/FxTimingChoice.kt`'s `familyMasterUuidFor`, the one reading MCP's FX tools stamp with
  too. That is the library family, not the attribute's family the mask reads: a Pulse on `uv` is Colour for
  the mask and takes the Intensity master.
- **Local replacement**, the FX-side twin of a palette layer's family replacement
  (`fx/LayerPress.kt`). Before it starts, the press takes the selected heads off every other Local
  FX of its family, read the same way (a family that resolves to nothing replaces nothing): one
  whose every painted head is selected stops; one that paints some of them
  and some others is **narrowed** — rewritten as one instance per head it keeps, each where it was
  (the split below, with no edit). A layer's FX is never touched. The stops, the narrowing and
  the new FX are one engine mutation (`FxEngine.replaceFx`), so no head drops to the tier
  below between the old FX and the new; an FX another request swapped meanwhile is taken
  off its current instance. Answers `{fxId, stopped, narrowed: [{fxId, into}]}`.
- **Stacking** (FX editor plan D6, §3.6). `stack: true` skips the replacement: the FX is added over
  the selection and no other Local FX is stopped or narrowed, answered `{fxId, stopped: [],
  narrowed: []}`. A blend or Amount (`amount`) the request leaves out takes its family's stacking
  start from `fx/FxStacking.kt`: Intensity `MULTIPLY` (*Within it*) at 100 %, Position `ADDITIVE`
  (*Around current position*) at 100 % with `panCentre` and `tiltCentre` at 128 where the press
  names neither the blend nor that centre — only for an FX that declares both centres; any other
  Position FX (Palette Steps) outputs where to go, not an offset, and starts on `OVERRIDE` at 100 % —
  and Colour and Beam `OVERRIDE` (*Replace it*) at 50 %. A blend or Amount the press names wins over the table. Without `stack` the
  press is unchanged: `OVERRIDE` at 100 % when absent. MCP has no bare press, so no tool takes
  `stack`.

A new Local FX takes the top of its lane either way (§"Programmer suppression and the priority
band"), so a stacked one applies above the Local FX it shares heads with.

**The split** is `PUT /fx/{id}` or `updateFx` with `targets` (fixtures, elements and groups, as a press
names them), decided by `planSplit` against the heads the FX **paints** (`FxEngine.paintedHeads`:
an element filter's left-out elements paint nothing and are not counted):

- No `targets`, or targets covering every painted head (`TargetCoverage.covers`, so a group by its
  members and an element by itself or its fixture), is the in-place edit, id kept. Targets covering none
  are 400 `FX_UPDATE_REFUSED`.
- Covering some splits them off: `FxEngine.headCopies` rewrites the FX as **one instance per head
  it paints**, `replaceFx` swaps them in for it as one mutation, and the named heads' copies then
  take the edit through the same `updateFx` an in-place edit uses. The rest run on untouched. REST
  answers `FxSplitResponse {splitFxId, edited, rest}`; the frame answers `fxChanged(REMOVED)`
  for the FX and `fxChanged(ADDED)` per copy, the edited first.
- Only a Local FX splits: a programmer layer's is 409 `FX_SPLIT_LAYER_FX` (splitting it would
  need splitting its layer, apply-model plan P5; *Reset to palette* and *Update palette* stay its
  edits), and a cue's or a manual one outside the band is 409 `FX_SPLIT_NOT_LOCAL`.
- A change to the spread's **shape** — Order, Spread, Wings, Segments, Parts, element mode or filter —
  sent with some heads is 400 `FX_UPDATE_REFUSED`, nothing moved: a split-off head runs alone, so the
  shape would do nothing on it (agreed with Chris, session 2). Only a field that differs from what
  the FX has counts, so an editor re-sending the shape it shows still splits.

**Each copy starts where its head was** (`SplitMember`, on `FxInstance.splitMember`). A copy is the
member it was: its phase is computed by the same member call the parent's pass made for that head —
the member's distribution offset, the size of the list it was spread over and its `FxContext` —
so a copy on *One cycle for: Each head* keeps its set's `distinctSlots`-scaled cycle and a ping-pong copy its sweep
(Chris chose to carry them rather than refuse those splits), a copy with a run count runs its window from its
own offset and Bounce counts its cycles from it, and an FX reading its place in the spread still
sees it. `FxSplitTest` runs an FX on two rigs, splits one mid-run and compares every channel
on every tick. A copy also takes the parent's clocks (`accumulatedScaledMs`, the Runs anchors, the
start time), masters, timing source, *Seconds*, envelope and band, shares its FX object (as the
members did; added `carried`, so a stateful FX is not reset), and names its fixture or element with
element filter `ALL`.

- **The offset is beside the phase offset, not in it.** Every copy keeps the parent's
  `phaseOffset`, so an edit that follows the selection sends one value to every copy it covers; a
  phase offset per copy would collapse them into unison on the first edit. (The plan's §3.4 put the
  offset in the phase offset and moved the Runs anchor by it; done together those count the offset
  twice, which is part of why it is carried instead.) Stored data has no field for it (P7), so what
  stores a copy folds it in — `FxInstance.storedPhaseOffset`, read by Record into a cue
  (`captureCurrentState`, `fxInstancesToCueChildren`) and into a Look (`lookFxSettings`): a
  recorded copy keeps its phase, though not its set's step cycle or sweep.
- **Update.** A copy keeps a cue Include's origin (`programmerOrigin`). A cue Update writes each
  copy as a child of its own, one per head, so it retires the child they stand for once nothing but
  copies carries it (`splitIncludedChildren`), and each copy's origin then names its own child;
  otherwise the cue would play the FX twice. A child already on its copy's own head is never
  retired, so a second Update keeps the copies' children. A Look Include's (`includedLookFx`) is not kept: a Look's FX
  is written back whole, and a copy is the operator's busk of part of it.
- **Order.** Copies take new ids but keep the parent's `composeOrder`, which the engine sorts by
  between priority and id, so a split moves nothing in the composition order.
- **Races.** The copies take the parent's running state again at the swap, so a pause that reached
  the parent meanwhile is kept, and `updateFx` swaps by identity, so an edit racing a split
  cannot put the parent back beside its copies.
- **Clear and Release** take copies as they take any Local FX: they are in the band, and each
  names one head.

**The client's side** (apply-model plan session 6; `frontend/CLAUDE.md`'s FX paragraphs). ⌘K *Apply
FX*, the FX table's *+ FX*, a cell popup's FX band and the picker's FX section start a
library FX through `FxPicker`'s `press` arm, the bare FX press above with the door's
selection pair; a tap swaps the auditioned FX through `updateFx`, phase kept. The fixture sheet's
FX tray is not one of them: it starts on its own pick of heads through `POST /fx/add`. A focused Look
layer is the other exception: there the FX starts on `POST /fx/add` and is moved into the
Look (`absorb-fx`), since the press's replacement would take heads off other Local FX. The
live editor (`FxEditorPopover`) follows the selection: it reads which of the FX's heads the
window's pair (or a cell's batch) covers, for its words alone, and with some of a Local FX's heads
covered it holds the edit until *Apply to …*, which sends it once as `PUT /fx/{id}` with those heads
as `targets` — REST, because only REST answers the copies (`FxSplitResponse`) for the editor to
follow; the frame's `fxChanged` names none. It then sends every later move to each edited copy. A
layer's, a cue's or a manual FX is edited in place and never offered the split.

### Target Types

| Target | Attributes | Fixture Trait |
|--------|------------|---------------|
| `SliderTarget` | `dimmer`, `uv` | `WithDimmer`, `WithUv` |
| `ColourTarget` | `rgbColour` | `WithColour` |
| `PositionTarget` | `pan`, `tilt` | `WithPosition` |

### Group Targets

Create group-targeting FX using factory methods:

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
| `FX_APPLICATION` | `FxApplicationScript` | Apply FX to fixtures/groups (implicit engine) |

FX definitions are **not** a script type — they are managed as `fx_definitions`
with dedicated calculate-only script base classes (`FxCalcScript`, `FxStatefulCalcScript`). See [FX Definitions](#fx-definitions) above.

### FX_APPLICATION Scripts

Apply FX with implicit `fxEngine` — no need to pass the engine to every call.

FX are **named, not constructed**: `effect(id, params)` resolves the type through the
`FxRegistry`, so a script addresses the same vocabulary as the UI, cues and Looks, user FX
from `fx_definitions` included. Omitted parameters take the type's declared defaults. There are no
compiled-in FX classes to instantiate — the `fx/effects` package went with sweep item D7.

```kotlin
val wash = fixture<HexFixture>("front-wash-1")
val movers = group<MovingHead>("movers")

wash.fx {
    dimmer(effect("Wave", "low" to "40"), CycleLength.TWO_BEATS)
    colour(effect("ColourCycle"), CycleLength.ONE_BAR)
}

movers.fx {
    dimmer(effect("Wave", "shape" to "PULSE"), CycleLength.ONE_BEAT, order = SpreadOrder.CENTRE_OUT)
    colour(
        effect("RainbowCycle"), CycleLength.TWO_BARS,
        order = SpreadOrder.NORMAL,
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
wall-clock FX — see `FU-SPEED-RATEMASTER-STATEFUL`.

### GENERAL Scripts (LightingScript)

Full-power scripts with explicit `fxEngine` parameter:

```kotlin
fixture.applyDimmerFx(fxEngine, effect("Wave"), FxTiming(CycleLength.TWO_BEATS))
fixture.fx(fxEngine) {
    dimmer(effect("Wave", "shape" to "PULSE"), CycleLength.ONE_BEAT)
    colour(effect("ColourCycle", "colours" to "#ff0000,#00ff00,#0000ff"), CycleLength.ONE_BAR)
}
fixture.clearFx(fxEngine)

// Group with distribution
val group = fixtures.group<HexFixture>("front-wash")
group.applyDimmerFx(fxEngine, effect("Wave", "shape" to "PULSE"), order = SpreadOrder.NORMAL)
```

## Group FX Processing

When an `FxInstance` targets a group (or a selection, §"Over a selection"), the `FxEngine` expands it at processing time:

```
┌─────────────────────────────────────────────────────────────────┐
│                    Group FX Processing                           │
│                                                                  │
│   FxInstance (group target)                                      │
│        │                                                         │
│        ▼                                                         │
│   Members have target attribute directly?                        │
│     ├─ YES → Apply to each member with distribution offsets      │
│     └─ NO  → Are members MultiElementFixture?                    │
│               └─ YES → Do elements have the attribute?           │
│                         └─ YES → Check elementMode:              │
│                              ├─ WHOLE: Each parent is one unit, │
│                              │   its elements take its output    │
│                              ├─ PER_FIXTURE: For each parent,    │
│                              │   distribute across its elements  │
│                              │   (all fixtures look the same)    │
│                              └─ FLAT: Collect all elements into  │
│                                  one list, distribute across all │
│                                  (chase sweeps across everything)│
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

### Element Mode: what *Over* counts

When an FX targets an attribute only its fixtures' elements have (not the fixtures themselves) —
on a group, a selection, or one multi-element fixture — `ElementMode` decides what the spread
counts. The screen calls it **Over** (FX editor follow-ups plan D7):

| Mode | Over | Description | Example (2×4-head fixtures) | MagicQ |
|------|------|-------------|----------------------------|--------|
| `WHOLE` | Fixtures | Each fixture is one unit. Order and the spread's shape count fixtures, and every element a fixture paints takes that unit's output. | 2 units: each fixture's four heads alike, the two fixtures apart | Dup Elements |
| `FLAT` | Elements | All elements across all fixtures form one flat list. Distribution runs across the entire set. | 8 elements total (indices 0-7), chase sweeps across all heads sequentially | Include Elements |
| `PER_FIXTURE` | Each fixture | Each fixture gets the FX independently. Distribution runs within each fixture's elements. | All fixtures look the same — head #0 on fixture A matches head #0 on fixture B | Use Elements |

Under `WHOLE`, `FxTargetExpansion.whole` lists the units — `perFixture` for a group or a selection,
the fixture's own `flat` list for one fixture — and `FxDistributionPlans.whole` spreads over them;
the engine computes one output a unit (a stateful FX keyed on its first element) and writes it to
each element. The element filter keeps its meaning: it picks which elements of each fixture are
painted, filtered within the fixture, and a fixture it empties still counts as a unit, so no other
fixture's offset moves. Over one fixture, `FLAT` and `PER_FIXTURE` are the same spread; `WHOLE` is
the one that differs, so the FX DTO reports the mode wherever the FX lands on elements, and a stored
row records it over members, and on a lone fixture only when it is `WHOLE`
(`ElementMode.stored`, which Record's canonical form reads too).

**Two defaults, named apart** (D8). A **stored** FX with no mode — a cue's, a Look's, a palette's,
an archive's, through `FxSpecCoercion.Lenient` — is `ElementMode.STORED_DEFAULT`, `PER_FIXTURE`, so
no row changes meaning. An FX a door **starts** with none named — `POST /fx/add`,
`POST /programmer/fx`, `POST /groups/{name}/fx`, and MCP's FX tools, which write it into the row —
is `ElementMode.NEW_FX_DEFAULT`, `WHOLE`; the client's creation doors (the picker, a cue's draft FX,
a new FX palette) write `WHOLE` themselves. `FxDynamics`' own default, and so the script API's, is
the stored one. It has no effect when the target or its members directly have the attribute.

### Orders

| Order | Description | Distinct Slots |
|----------|-------------|---------------|
| `NORMAL` | Evenly spaced phases, element 0 → N | N |
| `UNIFIED` | All fixtures same phase (synchronized) | 1 |
| `CENTRE_OUT` | Rank-based: center fires first, radiates outward | ⌈N/2⌉ |
| `CENTRE_IN` | Rank-based: edges fire first, converges to center | ⌈N/2⌉ |
| `REVERSE` | Evenly spaced phases, element N → 0 | N |
| `PING_PONG` | NORMAL offsets + triangle phase remap for bounce | N |
| `RANDOM(seed)` | Deterministic Fisher-Yates shuffle of evenly-spaced offsets | N |
| `POSITIONAL` | Based on normalized position | N |

**Symmetric Orders** (CENTRE_OUT, CENTRE_IN) have fewer distinct offset slots
than group members because symmetric pairs share the same offset. A windowed step chase uses
`numDistinctSlots` for window width to ensure gap-free chases.

**`CENTRE_IN` is the one Order from the edges in** (terminology plan D18).
`SPLIT` ranked a member `min(i, N−1−i)`, the same distance from the nearer end as Centre out's rank
inverted, so it gave Centre in's offsets at every group size and was folded into it: the parse no longer
knows it, and database steps 4 and 5 and `sync/LegacyNames.kt` rewrite a stored one.

### The spread's shape (effects plan session 2)

The **Order** (§3.1 of the effects plan) says which head comes
first. Four more fields shape what the Order gives — **Spread**, **Wings**, **Segments** and
**Parts** (D4) — and with the Order they are the spread. All four are `FxDynamics` fields, edited
in place as the Order is (an edit keeps the phase), stored on the four FX homes beside it
(`look_fx`, `cue_ad_hoc_fx`, `palette_fx` as `spread` and `spread_wings` /
`spread_segments` / `spread_parts` — the prefix because `GROUPS` was an SQLite keyword — and a live instance), carried by
`LookFxSpec`, `StoredFx` and `LookFxSettings` so Record, Include and Update keep them, and
synced from `formatVersion` 33 (`docs/sync-engineering.md` §"Version 33").

`DistributionPlan.of` runs §3.3 over each member, after the Order (step 1):

1. **Order** gives the member a rank `r` over `R` ranks: `SpreadOrder.ranks`, the offset
   times `distinctSlots` rounded to the whole number it is — except `POSITIONAL`, whose rank is its
   position along the group times `N` (continuous, so its offset is unchanged at Spread alone).
   Segments, Wings and Parts count whole ranks, so whenever one of them acts
   (`SpreadShaping.countsRanks`) the plan takes `SpreadOrder.wholeRanks` instead, which
   snaps Position's to the nearest of its `N` slots, `round(position × (N − 1))` — evenly spaced
   heads rank 0 … N − 1 as under Normal, so two wings mirror and groups repeat whole slots. Under Spread
   alone Position stays continuous.
2. **Segments** `B`: `r ← floor(r / B)` over `ceil(R / B)` ranks (`ceil(N / B)` under an asymmetric
   Order, whose rank count is the member count): `B` neighbours **in the Order** share one phase —
   on the rig under Normal, Reverse and Position, and under Random heads beside each other in the
   shuffle, so a Random in segments of 2 is random pairs.
3. **Wings** `W`, only under an asymmetric Order — Centre out and Centre in are two mirrored
   wings already (`SpreadOrder.isMirrored`): the ranks split into `W` runs of
   `m = ceil(R / W)`; an odd run is mirrored **within its own length** (`r ← len − 1 − r`, `len` being
   `m` for every run but a short last one, so the outermost member of each wing pair shares a rank —
   seven heads in two wings are 0 1 2 3 2 1 0); over `m` ranks.
4. **Parts** `G`: `r ← r mod G` over `G` ranks — when `G` is below the rank count; otherwise the
   ranks stand, so the slot count never exceeds what the members fill. `G = 0` is off.
5. **Spread** `s`, 0–2: `offset = s × r / R`. 100 % is the Order's own; 50 % packs the heads into
   half a cycle (a tighter ripple); 200 % runs them over two. Under `spreadUnit: STEP` (below) it is
   `s × r / steps` instead, `s` 0–16.

`distinctSlots` is the rank count after step 4 (`DistributionPlan.slots`, each context's
`numDistinctSlots`, and `FxDynamics.distinctSlots` — the same count in constant time, for the
per-pass reads), so a step chase's window and *One cycle for: Each head* stay gap-free: Parts of 2 over eight heads
are two slots, the odd heads and the even heads taking turns a step each. `PING_PONG`'s turnaround is
**not** shaped: its sweep is the Order's own `(N − 1) / N` (§"Phase Calculation"), the shape setting only
the offsets. At the default shape (100 %, 1, 1, 0) the
plan does not run the shaping at all, so an FX that names no shape keeps its offsets bit for
bit (P6). The plan cache (`FxDistributionPlans`) is keyed on the shape as on the Order.

**Each head later by a step** (fx-settings plan D6, session 4). `SpreadShape.unit`, a
`SpreadUnit`, says what `spread` counts: `CYCLE`, the share of the cycle over every slot, as above, or
`STEP`, how many of the FX's **own steps** each slot starts after the one before — `offset = s × r /
steps`, on the ranks steps 2–4 left. Colour Cycle with three colours over four heads at `CYCLE` 1
starts them a quarter of a cycle apart and its colours change every third, so the edges stagger; at
`STEP` 1 they start a third apart, each head one colour behind the last, and every colour changes on
one boundary — and still does when a fifth head joins, because the desk resolves the step count at
expansion rather than the client storing a spread worked out for four heads. `STEP` may run past
`MAX_SPREAD` (eight heads over three colours is 8⁄3 of a cycle); 200 % caps only a stored `CYCLE` value.

An FX steps when its frontmatter names the list parameter its steps come from — `stepsFrom: colours`
on Colour Cycle, `palettes` on Palette Steps, `slots` on Slot Steps (`FxRegistration.stepsFrom`,
`FxTypeInfo.stepsFrom` on `GET /fx/library`) — and `Fx.stepCount` counts that list as the FX reads it
(`TypedParams.stepCount`: a colour the colour list drops is no step). `FxInstance.planSteps` asks for it
only under `STEP`, and the plan cache is keyed on the unit and the count, so a colour-list edit or a
palette that stops resolving re-plans. A `STEP` FX with no count (a stored row whose type lost its
`stepsFrom`) runs as `CYCLE`. A project FX definition declares no `stepsFrom`.

- **The unit's homes.** An `FxDynamics` field edited in place, phase kept; `spread_unit` on `look_fx`,
  `cue_ad_hoc_fx` and `palette_fx`, nullable, `STEP` or null for `CYCLE` (`SpreadUnit.STORED_DEFAULT`,
  so no row written before it changes meaning, and `SpreadUnit.canonical` writes `CYCLE` as null);
  `LookFxSpec`, `StoredFx`, `LookFxSettings` (so Record, Include, Update and the Timeline's
  `comparableFxSettings` keep it), every FX DTO, `POST /fx/add`, `PUT /fx/{id}`, the `updateFx` frame,
  `POST /programmer/fx`, the group route, the sync DTOs from `formatVersion` 43
  (`docs/sync-engineering.md` §"Version 43") and MCP's FX schemas.
- **A new step FX starts one step behind** (D7): every door that starts or creates an FX writes `STEP`
  and 1 for an FX with `stepsFrom` when the author names neither a spread nor a unit — `POST /fx/add`,
  `POST /programmer/fx` and the group route (`requestedSpreadShape`), MCP's FX tools, and on the client
  the picker, a cue's draft FX and a new FX palette (`newFxSpread`). A spread sent without a unit is
  the cycle's, as it always was.
- **Refused** at the write boundary: `STEP` on an FX with no steps (by the FX's name) and a `STEP`
  spread outside 0–16 (`spreadProblem`'s `unit` and `fxSteps`; the stored homes ask the registry,
  `FxRegistry.steps`). A stored `STEP` spread above 16 clamps on spawn.
- **A swap off a step FX** (`PUT /fx/{id}` with a new `fxType` and no unit) leaves `STEP` for the
  `CYCLE` spread that runs the heads' offsets, `spread × slots / steps` over the most slots the FX
  spreads over (`SpreadShaping.cycleSpread`, `FxEngine.spreadSlots`), capped at 200 %; a swap onto
  another FX that steps keeps `STEP`. The palette editor, which has no heads, swaps onto the cycle's
  own spread.
- The editor draws the Spread field in steps under `STEP`, its dials count the FX's steps
  (`lib/spreadShape.ts`'s `stepCountOf`), and the graph's Spread knob drags in them; choosing the unit is
  the FX settings plan's session 6.
- **`stepsFrom` is kept by the FX.** A script's `registerFx` naming one is refused unless the FX its
  factory builds answers `stepCount` (a copied built-in's does), and a stored `STEP` on an FX with no
  steps spawns as `CYCLE` in the cycle's range. MCP's reads always say a step FX's spread and unit,
  since a write naming neither starts it one step behind.

**Runs end N cycles past the largest member offset — the shaped one.** A member's own clock is
the FX's less its offset, so under Spread 50 % two runs over four heads (offsets 0, ⅛, ¼, ⅜)
hands each head back after its own two and is spent two cycles past ⅜; under Parts of 2 (offsets 0,
½, 0, ½) two cycles past ½. Amount is untouched by the shape: it weighs the apply, after the blend.

**The rule is the desk's** (P2). `frontend/src/lib/spreadShape.ts` is the editor's one copy, for its
phase-dial preview only, and `src/test/resources/fx/spreadShape.fixture.json` — generated from an
independent reference of §3.3 and read by `SpreadShapeTest` and `spreadShape.test.ts` — pins the two
to the same offsets, Random's shuffle included.

- **Refused at the write boundary** (`FxSpecCoercion.Strict.spreadProblem`, inside `problem()`
  where the Order is known): Spread outside 0–2, Wings or Segments below 1, Parts below 0, any count
  above 512, and **Wings above 1 under a mirrored Order**. `PUT /fx/{id}` checks the shape against
  the Order the FX will run under — the request's, or the one it has — so moving a winged FX
  onto Centre out is refused as surely as sending wings to one. The instance's **own** wings take
  part only when the request moves the Order: a stored row may hold wings under a mirrored Order
  (lenient on spawn, not run), and an edit of its Spread, Segments or Parts — or a frame re-sending
  the Order it already has — is not refused for them. A stored value out of range is
  clamped with a warn on spawn (`Lenient.spreadShape`), and stored wings under a mirrored Order are
  left as stored and not run.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `spread`, `wings`, `segments` and
  `parts` (and `spreadUnit`, above); `PUT /fx/{id}` and `updateFx` take each with null meaning keep (`parts: 0` turns them
  off). `FxDto` and the group and indirect DTOs report them. The MCP FX schemas take the four
  (`AiToolSchemas.putFxDynamics`), and `describe_rig` and `get_current_state` say them where they
  are not the default.
- **The editor** (D11, §4.1): a *Spread* disclosure in `FxFields` — the Shapes (FX editor plan
  D11: nine chips, each setting Order, Spread, Wings, Segments and Parts at once), Spread %, Wings
  (hidden under a mirrored Order, and set back to 1 when one is chosen), Segments, Parts, and a row of
  phase dials over eight notional heads on an FX palette and the FX's own heads live (up to sixteen,
  then *+n*) — the FX editor's inspector draws the offset ladder from the preview in their place. The
  Order select sits above it, under *Over* in *Across the heads* (fx-settings plan D9). Its summary says the Shape, else *Custom*, then the
  non-defaults: *Custom · 50 % · 2 wings*.

**Order interface properties:**

| Property | Description |
|----------|-------------|
| `hasSpread` | Whether offsets differ between members (false only for UNIFIED) |
| `usesTrianglePhase` | Whether the base clock should be triangle-remapped (true for PING_PONG) |
| `distinctSlots(groupSize)` | Number of unique offset positions for a given group size |
| `ranks(members, groupSize)` | Each member's rank over `distinctSlots` ranks — what the spread's shape works on |
| `isMirrored` | Two mirrored wings already (CENTRE_OUT, CENTRE_IN) — Wings is refused under it |

## Multi-Element Fixture Expansion

When a fixture FX targets an attribute that the parent fixture doesn't have, but its elements do (e.g. applying a colour FX to a `QuadMoverBarFixture` whose heads have `WithColour` but the parent does not), the FX engine automatically expands the FX to all elements.

This makes multi-element fixtures behave like implicit groups for FX purposes, without requiring the user to manually create a fixture group.

```
┌─────────────────────────────────────────────────────────────────┐
│              Multi-Element FX Expansion                           │
│                                                                  │
│   FxInstance (fixture target, e.g. "quad-mover-1")               │
│        │                                                         │
│        ▼                                                         │
│   Parent has target attribute?                                   │
│     ├─ YES → Apply directly to parent (normal behaviour)         │
│     └─ NO  → Is parent a MultiElementFixture?                    │
│               ├─ NO  → Skip (silent no-op)                       │
│               └─ YES → Do elements have the attribute?           │
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

### Order Support

Multi-element expansion uses the same `SpreadOrder` machinery as group FX. The `order` field on `FxInstance` (which defaults to `NORMAL`) is used to calculate per-element phase offsets based on element index and normalized position.

The REST API `AddFxRequest` includes an optional `order` field for fixture FX targeting multi-element fixtures.

### Example

```
POST /api/rest/fx/add
{
  "fxType": "RainbowCycle",
  "fixtureKey": "quad-mover-1",
  "attribute": "rgbColour",
  "order": "NORMAL"
}
```

This applies a rainbow cycle to all 4 heads of the quad mover bar, with each head offset in phase to create a chase FX across the heads.

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
POST   /api/rest/projects/{id}/speed-masters        ← { name?, bpm?, notes?, family?, followNum?, followDen? }
PUT    /api/rest/projects/{id}/speed-masters/{mid}  ← same keys, patch semantics (present keys change;
                                                      the follow pair must travel together)
DELETE /api/rest/projects/{id}/speed-masters/{mid}  → 409 SPEED_MASTER_PROTECTED (master 1)
                                                     | 409 SPEED_MASTER_IN_USE (?force=true overrides)
```

Family/follow refusal codes (see §"Family routing and follow"): `SPEED_MASTER_INVALID` (400 —
malformed family or ratio, half-sent pair), `SPEED_MASTER_CANNOT_FOLLOW` (400 — ratio on master 1),
`SPEED_MASTER_FAMILY_TAKEN` (409 — another master already holds that family), and
`SPEED_MASTER_FOLLOWER` (400 — a `bpm` sent for a follower, on POST and PUT alike; the tempo is
derived, so retune master 1 or unlink first).

### FX Management

```
GET    /api/rest/fx/active           → [FxDto...]
POST   /api/rest/fx/add              ← AddFxRequest → { fxId }
PUT    /api/rest/fx/{id}             ← UpdateFxRequest → FxDto | FxSplitResponse (with targets naming some heads)
DELETE /api/rest/fx/{id}             (remove FX)
POST   /api/rest/fx/{id}/pause
POST   /api/rest/fx/{id}/resume
GET    /api/rest/fx/fixture/{key}    → { direct: [FxDto...], indirect: [IndirectFxDto...] }
DELETE /api/rest/fx/fixture/{key}    (clear fixture FX)
POST   /api/rest/fx/clear            (clear all FX)
```

`FxDto` lives in `fx/FxDto.kt` and is the *only* FX report on any transport — the
`fxState` WebSocket frame carries the same objects. Sweep item F8 collapsed the two, so these
REST responses gained the three fields that used to be WebSocket-only: `cueStackId`,
`speedMasterIndex` and `rateSpeedMasterIndex`. Nothing was removed, and `fxType` is
`FxInstance.fxTypeId` on every transport now (the WS frame used to send the display name).
Build one with `FxEngine.fxDtos` / `FxEngine.fxDto` rather than calling `toFxDto`
directly — the engine owns the speed-master snapshot and the multi-element answer it needs.

### FX Library

```
GET  /api/rest/fx/library          → [FxTypeInfo...]
```

### AddFxRequest Format

```json
{
  "fxType": "Wave",
  "fixtureKey": "front-wash-1",
  "attribute": "dimmer",
  "cycle": 1.0,
  "blendMode": "OVERRIDE",
  "startOnBeat": true,
  "phaseOffset": 0.0,
  "parameters": {
    "low": "0",
    "high": "255"
  },
  "order": "NORMAL",
  "timing": "HEAD",
  "speedMasterUuid": "7d444840-9dc0-11d1-b245-5ffdce74fad2",
  "rateSpeedMasterUuid": null
}
```

The `speedMasterUuid` field is optional (null → master 1). It is a *uuid*, not the int id,
so a stored reference survives project clone and import. `rateSpeedMasterUuid` is its
wall-clock counterpart (null → unscaled); both are accepted on every FX and simply go
unread by the timing source that does not apply.

The `order` field is optional. When provided, it sets the Order for multi-element fixture expansion (see [Multi-Element Fixture Expansion](#multi-element-fixture-expansion)). For non-multi-element fixtures, it is ignored.

The `timing` field (`CYCLE` or `HEAD`) is optional. When provided, it overrides the FX's default timing. See [Timing](#timing).

`targets` (`[{"type": "fixture" | "group", "key": "…"}]`) replaces `fixtureKey` for one FX over a
selection, spread in that order ([Over a selection](#over-a-selection-effects-plan-session-3)): one
item is that fixture or group alone, an unknown item is a 400 naming it, and a list none of whose
heads has the attribute is refused. The spread and element fields then apply as to a group.

### UpdateFxRequest Format

```json
{
  "fxType": "Wave",
  "parameters": { "shape": "PULSE", "low": "50", "high": "200" },
  "cycle": 2.0,
  "blendMode": "ADDITIVE",
  "phaseOffset": 0.25,
  "order": "CENTRE_OUT",
  "elementMode": "FLAT",
  "timing": "CYCLE",
  "rateSpeedMasterUuid": "7d444840-9dc0-11d1-b245-5ffdce74fad2",
  "seconds": false
}
```

`targets` (`[{"type": "fixture" | "group", "key": "…"}]`) says which heads the edit is for: absent, or
naming every head the FX paints, is the in-place edit below; naming some splits them off and
answers `FxSplitResponse` instead — see [Local FX follow the selection](#local-fx-follow-the-selection-apply-model-plan-session-2).

All fields are optional. Truly immutable fields (`fxType`, `parameters`, `cycle`,
`blendMode`) trigger an atomic swap of the `FxInstance`, preserving id, start time, and — because
the dynamics cell is shared across the swap (see "Instance dynamics" above) — the running state
and any concurrently racing pause/resume. Dynamics fields (`phaseOffset`, `order`,
`elementMode`, `elementFilter`, `timing`) are updated in place as **one** atomic snapshot
replacement, so a tick pass never observes half of an update. `speedMasterUuid` /
`rateSpeedMasterUuid` are also updated in place; both follow the same null-means-no-change
convention, and to return an FX to the default, send master 1's uuid (master 1 always exists
and behaves identically to the null default). `seconds` moves the FX between *Seconds* and a
master — see [Seconds](#seconds--an-fxs-own-cycle-venue-visit-plan-d7) — and is the only way to
put a wall-clock FX back to unscaled. `amount`, `direction` and `runs` are dynamics fields, edited
in place with the phase kept; `runForever: true` clears a count, which a null `runs` (no change)
cannot say. See §"Amount, direction and runs".

## WebSocket Messages

### Client → Server

| Message | Description |
|---------|-------------|
| `fxState` | Request the current active-FX list |
| `speedMasters.state` | Request the full masters bank |
| `speedMasters.setBpm` | Set one master's BPM `{ masterUuid?, bpm }` (uuid omitted → master 1) |
| `speedMasters.tap` | Tap one master's tempo `{ masterUuid? }` |
| `removeFx` | Remove FX `{ fxId }` |
| `pauseFx` | Pause FX `{ fxId }` |
| `resumeFx` | Resume FX `{ fxId }` |
| `clearFx` | Clear all FX |
| `speedMasters.requestBeat` | Ask for one immediate beat frame — `{ masterUuid? }`, omitted uuid = master 1 |

### Server → Client

| Message | Description |
|---------|-------------|
| `fxState` | The active-FX list `{ activeFx }`, each entry the shared `FxDto` (`fx/FxDto.kt`) that `GET /fx/active` also returns — so it carries `speedMasterUuid`/`speedMasterIndex` and `rateSpeedMasterUuid`/`rateSpeedMasterIndex` alongside the full REST field set. No tempo — it used to carry `bpm`/`isClockRunning` for master 1 |
| `fxChanged` | FX change notification `{ changeType, fxId }` |
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
| `fx/SpeedMasterBank.kt` | Per-project bank of master clocks, wake channel, per-pass `Frame` |
| `models/speedMasters.kt` | Exposed DAO for speed_masters table + default-bank seeding |
| `routes/projectSpeedMasters.kt` | Speed-master CRUD API endpoints + delete guards |
| `routes/fxPreview.kt` | `POST /projects/{id}/fx/preview`: the FX editor's graph, answered from `fx/FxSampler.kt` (§"Previewing an FX") |
| `plugins/SpeedMasterSocket.kt` | `speedMasters.*` WS family (live tempo control/streaming, keyed beat frames) |
| `fx/CycleLength.kt` | Timing constants |
| `fx/Fx.kt` | Fx, StatefulFx interfaces, FxOutput types |
| `fx/FxRegistry.kt` | Unified FX registry, FxRegistration, ParameterInfo, FxTypeInfo |
| `fx/FxFileLoader.kt` | Loads and parses .fx.kts files from resources |
| `fx/ParameterMetadata.kt` | A parameter's editor metadata rules, the `choice` type's refusal, a curve's implied options (§"Parameters") |
| `fx/TypedParams.kt` | Typed parameter accessor for FX scripts |
| `fx/ScriptFxAdapter.kt` | Bridges compiled FX scripts to Fx interfaces |
| `fx/FxScriptCompiler.kt` | Compiles and caches FX calculate scripts |
| `fx/FxParamUtils.kt` | Parameter parsing utilities (parseExtendedColour, toUByteParam, etc.) |
| `fx/FxInstance.kt` | Running FX state, order, ElementMode, speed-master refs |
| `fx/FxTarget.kt` | Fixture/group attribute targeting, FxTargetRef |
| `fx/FxBlend.kt` | Blend and weight as pure functions, written through by every target and folded by the sampler |
| `fx/FxPhase.kt` | A member's phase as a pure function of one clock reading, its dynamics and its Runs anchor |
| `fx/FxSampler.kt` | Evaluates FX off the stage over a window, for the FX editor's graph (§"Previewing an FX") |
| `fx/NotionalRig.kt` | Heads the rig does not have, for previewing a draft before there is a selection (§"Previewing an FX") |
| `fx/FxTargetable.kt` | Common interface for Fixture and FixtureGroup |
| `fx/FxEngine.kt` | The compose pass and its wake sources, active-FX set, group expansion (post-E1 split) |
| `fx/CascadePublisher.kt` | Tier-4/cascade → controller transmit, the Tier 4 publish lock, channel-covering-key lookup |
| `fx/CueAssignmentLayer.kt` | Per-cue Tier 4 assignment bookkeeping, within-cue stomp registry |
| `fx/ProvenanceService.kt` | "Who owns this value" computation + broadcast, `underlyingSources` |
| `fx/ProgrammerWriter.kt` | PROGRAMMER-tier write delegation (write/clear/blind/republish) |
| `fx/FxLogThrottle.kt` | Per-cause throttled logging shared by the compose pass and publish paths |
| `fx/FxExtensions.kt` | Script DSL helpers |
| `fx/group/SpreadOrder.kt` | The Orders |
| `fx/group/GroupFxExtensions.kt` | Group FX extension functions |
| `resources/fx/**/*.fx.kts` | Every built-in FX's metadata + calculate body (14 files) |
| `fx/PaletteColourSource.kt` | Colour-palette resolution, `PaletteSource` (also Palette Steps' palette lookup), and `createFxWithPalettes` — the one spawn helper every string-named FX goes through |
| `fx/StepList.kt` | The step FX's lists, resolved per head (`SlotStepList`, `PaletteStepList`), `stepsAt` and `paletteStepsShape` |
| `fx/PaletteRegistry.kt` | Palette snapshots cached by uuid — rows, or the one FX an FX palette holds; the scoped version a running FX's colour cache watches |
| `fx/FxSpawner.kt` | Stored FX spec → live `FxInstance` on a live `FxTarget`; the one spawn path for cues, triggers, the programmer stack and Include |
| `fx/CueApply.kt` | The cue-apply domain: `CueApplyData` and its builders, cue-derived priority, Tier 4 row construction, stomp overlap |
| `fixture/trait/WithPosition.kt` | Position trait for moving heads |
| `fixture/trait/WithDimmer.kt` | Dimmer trait |
| `fixture/trait/WithColour.kt` | Colour trait |
| `fixture/trait/WithUv.kt` | UV trait |
| `fixture/group/GroupExtensions.kt` | Group property extensions |
| `scripts/ScriptType.kt` | Script type enum (GENERAL, FX_APPLICATION) |
| `scripts/scriptDef.kt` | LightingScript base class (GENERAL scripts) |
| `scripts/fxCalcScriptDef.kt` | FxCalcScript, FxStatefulCalcScript base classes |
| `scripts/fxApplicationScriptDef.kt` | FxApplicationScript base class (FX application) |
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
elements, the `WHOLE`/`FLAT`/`PER_FIXTURE` shapes, `ElementFilter` — is resolved **once per FX per
register generation**, into an `FxTargetExpansion` held on the `FxInstance`. The compose pass reads
it; it does not walk the group.

They used to walk it twice per tick per FX: once in `resolveFxFixtureKeys` for the reset
pass, and again in the `process*` pass, with every `untypedGroup` / `untypedFixture` lookup taking
`Fixtures.registerLock.read`. A 240-element group FX cost ~480 lock acquisitions and two
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
not, because every shape it selects between is built up front.

The consequence to know about: an FX whose group or fixture has been deleted now logs
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
group name, an FX type — never an FX id, which is a monotonic counter), because nothing
ever sweeps the map.

Three levels of containment, outermost first:

1. **The pass loop** (`start`) catches around each pass. Anything escaping there would cancel the
   loop's job for the rest of the process — the desk's FX would stop for good, with one line
   on the uncaught-exception handler. `CancellationException` is rethrown so `stop()` still works.
2. **Each FX's pass** is caught individually, so one bad FX doesn't cost the others their
   tick.
3. **An FX that fails `MAX_CONSECUTIVE_TICK_FAILURES` (120) passes in a row is paused** —
   `noteTickFailure`. A script FX that throws on every `calculate` is not going to come good
   on the next tick; pausing keeps the instance (phase, parameters, its row in the sheet) so the
   operator can fix the definition and hit resume, logs the pause at `error` with the exception,
   and shows up in the UI through the `isRunning` that `fxState` already streams. The threshold is
   a pass count, not a duration, so it trips faster the faster the desk is ticking — the safe
   direction, since a fault that clears itself (a fixture missing for the length of a patch
   reload) gets more real time to clear on the slower paths. Only passes the FX's **own**
   clock woke count, failing or good (a beat FX's: any master's tick; a wall-clock FX's: the
   ticker), so a beat FX's grace period does not shrink because the ticker also wakes the
   pass; a failure on another pass is logged, not counted. One good pass clears the run.

Levels 2 and 3 catch `Throwable`, not `Exception`: every built-in FX is a compiled script, so a
recursive helper in one (`StackOverflowError`) or a script class that fails to link
(`NoClassDefFoundError`) raises an `Error`, and that must not be the thing that takes the pass loop
down for the process.

Pausing also **resets the FX's attributes to the tier below**, into the pass's open
transaction. An FX can die part-way through its own targets — a group FX that throws on
member 7 has already painted 0..6 — and from the next pass on a paused FX is skipped by
`resetActiveAttributes`, so a half-applied frame would sit frozen with nothing left to move it. A
*manual* pause deliberately freezes instead: that frame is one the operator chose.

`FxTickFailureTest` covers the pause rule, the reset, and the run-clearing.

The reset pass (`resetOne`) is the one deliberate exception to "log it at warn": it logs at debug,
because the FX's own apply is about to fail on the same missing fixture and report it properly.

## Cue Integration

A cue is an ordered stack of **Look layers** plus its own local values and ad-hoc FX. The FX system supports cues via the `cueId` field on `FxInstance`.

### FxInstance.cueId

Each `FxInstance` has an optional `cueId: Int?` field (default `null`). When a cue comes on (its first
hold, `fx/CueHolds.kt`), every FX it creates is tagged with the cue's database ID. This enables:

- **Taking a cue off**: its last hold going removes its FX by `cueId` — never by stack — or, on
  a GO with a fade, sets them shrinking to nothing (leaving) for the engine to remove at 0. A cue
  brought back on before that finds its own leaving instances by `cueId` and takes them back.
- **Identification**: FX created by a cue can be distinguished from manually applied FX (which have `cueId = null`).

A layer's FX also carries `layerFxUuid`, the stored Look or palette FX row it realises:
what a GO matches a continuing FX on (effects plan P4), so the incoming cue adopts the outgoing
cue's instance — retagged to the incoming cue, id and phase kept — instead of fading one out and
another in. A cue's ad-hoc FX carries its own row's uuid there, which no other cue holds, and
its row's run key in `runUuid` (stack-timeline plan D7): a GO hands the outgoing cue's instance with
the same key, target and attribute to the incoming row, restamped with that row's uuid so *Update
stage* still reconciles by row. When the two rows' settings differ, the instance is edited in place
at the GO (`routes.alignFx`, the same edit *Update stage* makes, injected into `CueHolds` as an
`FxAligner` so `fx/` does not import `routes/`): phase kept, except that a move between Seconds
and a master restarts it. The key is written by the Show view's Timeline (`timeline/edit`'s `fxOn`,
`breakRun` and `joinRun`, `docs/cue-stacks-engineering.md` §"Timeline"), the cue copier and the
importer, and by no cue write. The `cueId`, `layerFxUuid`, `runUuid` and envelope are preserved across
atomic swaps in `FxEngine.updateFx()`, so updating an FX's parameters doesn't lose its cue
association, its run or its fade.

### Layers

A cue layer stores a **reference** to a Look or a palette (by id), and a palette's layer the
targets it operates over; a Look's names none, since a Look lands whole. At apply time the Look is
read fresh through `LookRegistry`, so editing a Look is reflected the next time the
cue is applied — and, for a *live* cue, immediately, via `republishForLookEdit`. Each layer's FX
are created as `FxInstance`s tagged with the cue id.

**FX spawn in layer order, and that alone is enough to make layer order the composition
order.** `sortedFxComparator` is `compareBy(priority, composeOrder, id)` with `composeOrder`
the monotonic creation counter (a split copy keeps its parent's, §"Local FX follow the
selection"), and per-tick composition is a genuine sequential fold through `FxTarget.applyValue`, so
same-priority FX already resolve last-created-wins. No priority arithmetic is needed and the
uniform per-cue priority stays. A palette's FX fans over the layer's targets; a Look's runs on
its own targets, whole.

The one limit worth stating: FX are Tier 3 and static values are Tier 4, so an FX sits
above a static value regardless of cue layer order. See `lighting-composition-model.md`
§"FX, and the constraint that cannot be layered away".

### Ad-Hoc FX

FX belonging to no Look are stored as full FX definitions in the `cue_ad_hoc_fx`
table. At apply time these are converted directly to `FxInstance`s tagged with the cue id.

**A cue FX is edited in draft** (show-cue-editing plan session 6, D17): the cue editor's FX tab
edits the stored row, never an instance. Its fields are the live editor's (`FxFields`) over a
draft of the stored FX (`cueFxDraftOf` / `withCueFxDraft` in the frontend's
`fxEditorModel.ts`, so Seconds, amount, direction, runs and the spread's shape round-trip as a
palette's FX does), and every change is the cue's whole `adHocFx` list on its `PATCH`.
*+ FX* runs the picker in draft: a tap stores the FX with the picker's starting spec —
`startingSpec`, the step FX's seeded lists, D16's one bar on the family master — on the editor's
own selection (one row as itself, several as one `selection` FX, its key the list's canonical
JSON), and creates no instance; a second tap swaps the stored type. Nothing reaches the stage until
the cue next comes on — its first hold spawns what the row then says — or *Update stage* sends it: a
cue PATCH republishes nothing, and marks a held cue *edited since GO* when it changes an untimed
FX (`docs/cue-stacks-engineering.md` §"Edited since GO and Update stage"). A reorder restates
every `sortOrder`, and `writeCueChildren` keeps each FX's uuid by its key (target, attribute,
type), which is how *Update stage* tells an edited FX from a new one.

### Look Delete Blocking

A Look cannot be deleted if any cue layer references it via `cue_layers` — a plain indexed FK query,
where the named-palette era could only scan opaque `value` text. The Look detail API includes `layerCount`
and the referencing cue names; the delete returns 409 with code `LOOK_IN_USE`, and `?force=true`
deletes the layers with it. A forced delete that took layers rebuilds the live cues that held them
the way a Look edit does (`republishForLookDelete`, the same body as `republishForLookEdit` given the
cue ids the delete collected before the rows went), so the Look's values leave the stage without a
re-fire — its rows, as for an edit: FX the cue spawned from the Look at GO run until the cue
stops; it announces those cues, live and dark, through `cuesRecomposed`, and fires `cueListChanged`
and `cueStackListChanged`, whose layer counts moved.

### From-State Capture

The "create from current state" operation captures the live FX engine state:

1. FX from a Look or a palette are grouped **by that record** into cue layer rows. A palette's
   row collects their targets, so one applied twice to different targets collapses into one layer
   covering both — the honest reading of a *snapshot*, since the stage cannot tell you it was two
   gestures. A Look's row names no targets: it lands whole.
2. FX with a null `lookId` are stored as individual `CueAdHocFx` rows with all FX fields
   captured.

Two things a snapshot **cannot** recover, because an `FxInstance` never carried them: a layer's
`blendMode`, `amount` and `attributeMask`, which take their DTO defaults. When the operator wants the
stack's own amounts and masks preserved they Record `TOUCHED`, which reads `ProgrammerStore.layers`
directly rather than reconstructing from the stage.

This used to key on an `FxInstance.presetId` field. Session 3a added a separate `lookId` precisely
because the two were ids in *different* tables — reading a preset id as a look id reconstructed a
cue naming whatever `DaoFxPreset` happened to share the number — and nothing stamped `presetId`
after that, so it was deleted.

What an `FxInstance` *does* carry, beyond `lookId`: `registrationId`, the canonical
`FxRegistration.id` it was built from. Any "same FX type?" test reads that. The tempting
`effect.name.replace(" ", "")` is a registration id only for built-ins, whose display name is their
id with spaces in it; a user-defined FX definition sets `id` independently of `name`.

Anything that *persists or reports* an FX type — a recorded Look or cue child, an API DTO the
client hands back on Update — goes through `FxInstance.fxTypeId`, which is `registrationId` with
the display name kept only as the last resort for a script-constructed FX that never went
through the registry. Writing the display name directly stores a string the registry cannot resolve,
and the failure surfaces much later, when the row is applied.

### Related Files

| File | Cue-related content |
|------|-------------------|
| `fx/FxInstance.kt` | `cueId` field |
| `fx/FxEngine.kt` | `cueId` preservation in `updateFx()` |
| `models/cues.kt` | `DaoCues`, `DaoCueLayers`, `DaoCueAdHocFx` tables |
| `routes/projectCues.kt` | Cue CRUD, apply, from-state endpoints |
| `models/looks.kt` | `DaoLooks`, `DaoLookRows`, `DaoLookFx` tables |
| `fx/CueComposer.kt` | The cook step, layer blending, layer-ordered FX spawning (a Look layer's FX and an FX palette's one, through the same fan-out) |
| `fx/LookRegistry.kt` | Cached per-fixture Look expansion; two invalidation triggers |
| `routes/projectLooks.kt` | Look CRUD, derived family banking, `LOOK_IN_USE` delete guard |

See `docs/cues-engineering.md` for full cue system documentation.

## Timing Source

FX can run on one of two timing sources:

| Source | Description | Tick Rate | Phase Calculation |
|--------|-------------|-----------|-------------------|
| `BEAT` | Synchronized to the FX's speed master's ticks | 24 ticks/beat (variable) | From the master's tick counter via `MasterClock.phaseForDivision()` |
| `WALL_CLOCK` | Fixed-interval timer independent of BPM | 50Hz (20ms) | Accumulated wall-clock time since FX start, scaled per pass by an optional rate master |

### When to Use WALL_CLOCK

Use wall-clock timing for FX that should feel natural and not tied to music:
- Candle/fire flicker (organic randomness)
- Fluorescent tube flicker (intermittent failures)
- Ambient atmospheric changes (slow drifts)
- Any FX where beat-sync would feel unnatural

### Architecture: one compose pass

The FxEngine has two **wake sources** and one **compose pass** (`FxEngine.composePass`):

```
FxEngine
├── SpeedMasterBank.wake (CONFLATED; any master's tick) ─┐
├── 20 ms ticker ────────────────────────────────────────┤ each sets its flag
│                                                        ▼
└── composePass(beat, ticker, frame, now)   ← at most one per 5 ms, under passLock
    ├── reset every key a running FX covers to the tier below, once
    └── fold every running FX, beat and wall-clock, in apply order, into one transaction
        ├── BEAT: phase from frame.tick(effect.speedMasterSlot)
        └── WALL_CLOCK: phase from its accumulator, scaled by its rate master
```

A wake landing within 5 ms (`MIN_PASS_INTERVAL_MS`) of the last pass's start waits out the gap,
and every wake that lands meanwhile joins the same pass, which then knows **both** clocks woke
it. The pass is mutually exclusive (`passLock`): the production driver is one coroutine, and the
lock is what keeps a test's direct pass (`FxEngineTickShims`) off a running one.

Every pass paints every running FX, whichever clock woke it:

- **A beat FX** reads its master's tick from the pass's frame, which a beat-woken pass takes
  from the bank after any re-bind. A pass the ticker woke has no new frame and reuses the last
  beat-woken pass's (`lastFrame`): a master's tick is a pure function of its clock, so the FX
  is painted where the last tick left it. A bank membership change drops the held frame, and the
  next pass takes a fresh one.
- **A wall-clock FX** advances its accumulator by the real time since the last pass, whatever
  woke it. The deltas telescope, so the cycle is exactly as long however many passes there are;
  only its resolution improves.

A pass with nothing running on a clock that woke it is **skipped**: with only beat FX running,
a ticker-woken pass would repaint them at the tick they are already on, and with only wall-clock
FX running a beat wake adds nothing but cost. So a show running one kind of FX makes the
passes it made before; the time a skipped pass covers is advanced by the next one, and a skipped
pass does not hold the next wake back for the 5 ms gap.

A removal does not take `passLock`: some removals run inside a pass (the end of an FX's runs, the leaving
sweep), and the lock is not reentrant. So a removal on another thread can land while a pass that has
already read the old list is running, and that pass commits the removed FX's output over the
removal's reset of what it uncovered. With nothing left running every later pass is skipped and the
stale frame would stay. So after its commit the pass resets, as the removal did, what the FX in its
list that are no longer in the engine (removed, or swapped by an edit or a split) leave uncovered
(`resetRemovedSince`). A removal takes the FX out of the map, then rebuilds `sortedFx`, then
resets, so a pass that finds its list still current knows any reset to come lands after its commit,
and one that does not finds the FX gone. `FxRemovalRaceTest` forces the interleavings.

What **counts** passes stays with the FX's own clock. The pass records which clocks woke it,
and:

- a **stateful** FX (`StatefulFx`: Candle Flicker, Fluorescent Flicker, a user script)
  steps only on a pass its own clock woke, handed that clock's `deltaMs` and, on the wall clock,
  the ticker's pass number (`wallClockPassNumber`). On any other pass it repaints what it last
  answered on each key (`FxInstance.statefulOutputs`), so a Candle Flicker does not flicker faster
  under a faster master. A key it has never answered for steps whatever woke the pass, so an
  FX just added paints at once;
- the tick-failure count (§"Tick-path failures") counts only passes its own clock woke;
- an FX's runs end (`finishRuns`) only on a pass its own clock woke — on any other pass a member
  outside its runs simply holds what is underneath, as it does anyway. Its count starts on the
  first pass that paints it, whichever clock woke it: a beat FX's `runsStartTick` is the
  master tick it was painted at, so a count given to a running beat FX starts from the tick the
  master is on rather than leaving the FX unpainted until the next one;
- the leaving sweep (`retireFinishedLeaving`) runs on ticker-woken passes, at 50 Hz whether or not
  anything is running.

The per-FX apply is **one code path**: `processFixtureFx` / `processGroupFx` /
`processElementKeys`, differing per FX only in the `PhaseSource` (`Beat` reads the master's
tick, `WallClock` the FX's own scaled accumulator). Expansion, distribution plans, programmer
suppression, Blind's hold (one read of Blind per pass) and parking are therefore one rule for both.

### Beat ↔ Wall-Clock interaction

A beat FX and a wall-clock FX on the **same** key **compose**, whatever the blend: one pass
resets the key once and folds both, in apply order, so a Candle Flicker *Within* (MULTIPLY) a Sine
Wave flickers inside the wave on every frame, and Add, Max and Min compose the same way.
`FxComposePassTest` pins it against committed DMX over a schedule of beat-woken, ticker-woken and
coalesced passes.

One pass is what makes this true. With a loop per clock, each reset the keys its own FX covered
and applied them in its own transaction, so on a shared key the loop that committed last won
whatever the blend: the head showed the Sine Wave or the Candle Flicker, alternating, never both.

One transaction per pass also means one write per frame per key: ArtNet output samples
`currentValues` once per `refreshIntervalMs`, and with one pass there is no second, half-composed
transaction for it to sample.

### Wall-Clock Phase Calculation

For wall-clock FX, `cycle` is reinterpreted as cycle duration in seconds. A
**rate master** (`FxInstance.rateSpeedMasterUuid`, null → unscaled) sets how fast that cycle
is consumed, at `master.bpm / 120`:

```kotlin
// once per compose pass, per FX, before any phase is read
accumulatedScaledMs += deltaMs * rateScale
// then, wherever a phase is needed
val cycleDurationMs = cycle * 1000.0
val phase = (accumulatedScaledMs % cycleDurationMs) / cycleDurationMs
```

The cycle length is **fixed** and only the accumulation rate moves. That is what makes a
mid-cycle rate change continuous: the earlier form divided a fixed `now - startedAtMs` by a
cycle length that moved under it, so retuning a rate master snapped the phase (3 s into a
4 s cycle read 0.75, and became 0.5 the instant the cycle halved). `WallClockTimingTest`
pins the continuity, and — since elapsed time is now an explicit parameter rather than a
clock reading — pins it exactly, with no jitter tolerance.

The rate master scales the FX's *internal cycle* only: cue-trigger scheduling
(`delayMs`/`intervalMs`/`randomWindowMs`) is deliberately never scaled by any master.

"Null → unscaled" needs a sentinel to be true. `SpeedMasterBank.slotFor(null)` is slot 0, which
is master 1, so binding a null `rateSpeedMasterUuid` through it made every unassigned wall-clock
FX follow master 1's tempo — exactly 1.0 at the default 120 BPM, and a rig-wide speed change
the moment anything tapped it. `FxInstance.rateMasterSlot` therefore defaults to
`FxInstance.NO_RATE_MASTER` (-1) and the engine binds it through `rateSlotFor`, not `slotFor`
(sweep item C6). The compose pass skips sampling `rateScales()` altogether when no wall-clock FX
holds a real slot, which is the usual case.

`rateSpeedMasterUuid` is settable everywhere `speedMasterUuid` is — REST add/update, look
FX, cue ad-hoc FX, per-application overrides, cue-edit, and sync. The two coexist
rather than excluding each other, so an FX whose `timingSource` changes keeps both
assignments; BEAT FX simply never read the rate scale. The MCP FX tools take both, and
*Seconds* beside them (below). **Scripts are the deliberate exception** — the script API has never
addressed a per-FX master's absence, so there was nothing to mirror.

For STATEFUL FX, `deltaMs` is computed from the wall-clock interval directly. These
FX already use `deltaMs` rather than phase, so they work naturally — and by the same
token a rate master has no effect on one that never reads its phase. Both shipped wall-clock
FX (`CandleFlicker`, `FluorescentFlicker`) are STATEFUL, so nothing in-tree currently
exercises rate scaling end to end; author a STANDARD + WALL_CLOCK definition to try it.

### Seconds — an FX's own cycle (venue-visit plan D7)

An FX's **timing** is one of two things: it follows a speed master, or it runs on *Seconds* —
its own cycle, with no master, for a slow breathe or drift that must not move when the tempo does.
It is one question for every FX, asked by one chip (`Seconds | M1 | M2…`), and stored as one
flag, `seconds`, on every FX home: a live `FxInstance`, an FX palette (`palette_fx`), a
Look's FX (`look_fx`) and a cue's ad-hoc FX (`cue_ad_hoc_fx`), with
`LookFxSpec`, `StoredFx` and `LookFxSettings` carrying it between them. It syncs from
`formatVersion` 29. The rules are `fx/FxTimingChoice.kt`'s:

- **A beat FX on Seconds** reads its `cycle` as seconds and runs on the **wall-clock
  loop** with both masters null — `FxInstance.seconds` true, `timingSource` `WALL_CLOCK`,
  `rateMasterSlot` `NO_RATE_MASTER`. That is exactly an unscaled wall-clock FX, so neither loop
  learned anything; `applyTimingChoice` is the one writer before an instance reaches the engine, and
  `FxEngine.updateFx`'s `newSeconds` the one after (a swap, since the loop changes).
- **A wall-clock FX's** cycle is seconds already. Its Seconds is having **no rate master**,
  and a master is its rate master, which scales the cycle by `bpm / 120` as above. So the instance's
  flag stays false for it — `FxInstance.registrationTimingSource` can then tell the two kinds apart on
  the wall clock — and `FxInstance.onSeconds` is the reading for either kind. A stored row may
  carry the flag for a wall-clock FX (a client's or the AI's Seconds), which spawns it with no rate
  master; a stored wall-clock row with no rate master and no flag — every row before S15 — is on
  Seconds too.
- **Seconds is free of every master**, so nothing retimes it: a cue layer's or a programmer layer's
  master override does not reach it (`FxSpawner` applies the timing after the overrides), and a
  busk or AI press's cycle override leaves its cycle alone
  (`LookFxSpec.withCycleOverride`). A wall-clock row that merely has no rate master keeps
  the rule it always had — a layer's rate override still scales it.
- **On the wire**: `POST /fx/add` and `POST /groups/{name}/fx` take `seconds`; `PUT /fx/{id}` and
  `updateFx` (one parse, `applyFxUpdate`) take `seconds: Boolean?` — `true` onto Seconds, `false`
  onto the master sent beside it (a beat FX's absent master is master 1; a wall-clock FX must
  name its rate master, since a null one *is* Seconds), and naming a master alone also leaves Seconds.
  `seconds: true` beside a master is refused on the FX routes and by the MCP FX tools
  (`secondsTimingConflict`) — two answers to one question; the stored-FX writes take Seconds and
  drop the master instead (below). A move between Seconds and a master changes the loop the FX
  runs on, so it is a swap and the FX's **phase restarts**; every other edit keeps it. On a wall-clock FX, `seconds: true` is the explicit *unscaled* that a null
  `rateSpeedMasterUuid` (which means "no change") never could say — `FU-FX-RATE-MASTER-CLEAR`, closed
  by S15. `FxDto.seconds` (and the group and indirect FX DTOs') reports `onSeconds`; a beat
  FX on Seconds reports `timingSource` `WALL_CLOCK`, the loop it runs on, so every speed label
  reads its number as seconds.
- **A stored row on Seconds keeps no master beside the flag**: the Look, palette and cue writes
  null both masters where `seconds` is set, rather than refusing the pair as the FX routes do.
- **Record and Include carry it** with the masters. Both had dropped the masters of a cue's ad-hoc
  FX until S15 — `programmerCapture` recorded none, and `CueAdHocFxDto.toFxSpec`
  (Include's adapter) passed none on — so a recorded busk fell back to master 1.
- **Blind holds it like any programmer FX** (show-cue-editing plan D7): a busk on Seconds runs on
  the wall clock, whose accumulators advance before the pass applies anything, so a held one keeps its
  place in real time and lands in step when Blind goes off — §"Programmer suppression and the
  priority band".

**A new FX is one bar** (venue-visit plan D16): four beats, on the master whose `family` matches
its family where one is tagged, else master 1. The master is **stamped as a uuid when the FX is
written**, never resolved later, so `null` still means master 1 everywhere: by the editor's picker
(`FxPicker`), the palette editor, the MCP FX tools (`AiTools.parseFxTiming`, which also
makes `cycle` optional) and the programmer's bare FX press (`POST /programmer/fx`);
the desk's two read one rule, `fx/FxTimingChoice.kt`'s `familyMasterUuidFor`. REST's
`AddFxRequest` and `AddGroupFxRequest` default an omitted cycle to one bar and stamp nothing. A **wall-clock** FX starts on its own Seconds — four
seconds — since "one bar" means nothing to an FX timed in seconds. Moving a beat FX between
Seconds and a master in the editor or the palette sheet keeps its speed: the cycle is converted
at the master's tempo (4 beats at 120 BPM is 2 s). The effects plan's sessions build on this timing
and default.

### Configuration

Set `timingSource` in the `.fx.kts` frontmatter or via the FX definitions API:

```kotlin
/*---
id: CandleFlicker
fxMode: STATEFUL
timingSource: WALL_CLOCK
---*/
```

The timing source is stored on `FxRegistration` in the `FxRegistry` and propagated to `FxInstance.timingSource` when FX are created via looks, cues, or the REST API.

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
falling back to master 1: an *FX* degrading to the global tempo is reasonable, a control
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
3. **FX Modulation**: FX that modulate other FX's parameters
4. **Custom Distribution Functions**: User-defined distribution curves via scripts
