# FX (Effects) System Engineering Documentation

This document describes the effects subsystem for tempo-synchronized lighting effects.

> **See also**: [lighting-composition-model.md](lighting-composition-model.md) for the
> layered composition model. Effects are **Layer 3**: they sit above cue property
> assignments (Layer 4) and below the programmer (Layer 2) and parking (Layer 1). The per-tick reset-to-
> neutral pass resets each property to the **layer below** (via `LayerResolver`), not to
> hardcoded zero, so direct writes and cue state remain visible under running effects. Effect
> iteration is a sorted pass (priority ascending, id-ascending tie-break) rather than
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
│                               │ tickFlow (24 ticks/beat)            │
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
the global tempo every pre-bank surface (`setFxBpm`, `tapTempo`, `fxState.bpm`, `beatSync`,
the REST clock endpoints, script `setBpm`/`tapTempo`, the AI `set_bpm` tool) maps to, and
every effect with no explicit master resolves to.

Masters are persisted per project (`speed_masters` table, portable in sync — the stored bpm
is the *starting* tempo; live changes write through with a 750 ms trailing debounce).
Effects reference a master by **uuid** (`FxInstance.speedMasterUuid`, null → master 1) and
the engine binds that to a runtime slot index at add/update time, re-binding when the
bank's membership changes; a deleted master's effects degrade to master 1, never stop.
Because an FX instance targets specific properties, per-instance assignment already gives
"different speeds for different properties" — including for composites, which apply only
their primary output and so cover exactly one property per instance.

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
| `tickFlow` | `SharedFlow<ClockTick>` | Emits 24 times per beat |
| `beatFlow` | `SharedFlow<BeatEvent>` | Emits once per beat |
| `currentTick` | `ClockTick` (`@Volatile`) | Most recent tick, sampled per pass by the bank |

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
| `ONE_BAR` | 4.0 | Four beats |
| `TWO_BARS` | 8.0 | Eight beats |

## Effect Types

### Dimmer Effects (`FxOutput.Slider`)

Effects that produce single 0-255 values for dimmer/slider properties.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `SineWave` | Smooth oscillation | `min`, `max` |
| `RampUp` | Sawtooth up | `min`, `max`, `curve` |
| `RampDown` | Sawtooth down | `min`, `max`, `curve` |
| `Triangle` | Up then down | `min`, `max`, `curve` |
| `Pulse` | Attack-hold-release | `min`, `max`, `attackRatio`, `holdRatio` |
| `SquareWave` | On/off alternation | `min`, `max`, `dutyCycle` |
| `Strobe` | Quick flash | `offValue`, `onValue`, `onRatio` |
| `Flicker` | Random variation | `min`, `max` |
| `Breathe` | Smooth breathing | `min`, `max` |

### Colour Effects (`FxOutput.Colour`)

Effects that produce RGB color values.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `ColourCycle` | Step through a list of colours | `colours`, `fadeRatio` |
| `RainbowCycle` | Hue rotation | `saturation`, `brightness` |
| `ColourStrobe` | Flash colour | `onColor`, `offColor`, `onRatio` |
| `ColourPulse` | Pulse between two | `colorA`, `colorB` |
| `ColourFade` | Linear fade | `fromColor`, `toColor`, `pingPong` |
| `ColourFlicker` | Random variation | `baseColor`, `variation` |
| `StaticColour` | No animation | `color` |

### Position Effects (`FxOutput.Position`)

Effects that produce pan/tilt values for moving heads.

| Effect | Description | Parameters |
|--------|-------------|------------|
| `Circle` | Circular movement | `panCenter`, `tiltCenter`, `panRadius`, `tiltRadius` |
| `Figure8` | Figure-8 pattern | `panCenter`, `tiltCenter`, `panRadius`, `tiltRadius` |
| `Sweep` | Linear movement | `startPan`, `startTilt`, `endPan`, `endTilt`, `curve` |
| `PanSweep` | Horizontal sweep | `startPan`, `endPan`, `tilt` |
| `TiltSweep` | Vertical sweep | `startTilt`, `endTilt`, `pan` |
| `RandomPosition` | Random movement | `panCenter`, `tiltCenter`, `panRange`, `tiltRange` |
| `StaticPosition` | No movement | `pan`, `tilt` |

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

### Composite Effects (`CompositeEffect`)

**Composite effects are primary-output-only.** A composite *computes* a map of outputs for
several property types from one phase, but the engine applies exactly one of them: the entry
matching the effect's declared `outputType`. The rest are discarded.

| Effect | Description | Computes | Applies | Parameters |
|--------|-------------|----------|---------|------------|
| `LightningStrike` | Flash, with a matching colour shift on offer | SLIDER + COLOUR | SLIDER | `maxBrightness`, `minBrightness`, `flashColour`, `decayColour`, `ambientColour` |

Composite effects implement the `CompositeEffect` interface:
- `calculateComposite(phase, context)` — returns `Map<FxOutputType, FxOutput>`
- `calculate(phase, context)` — the interface default, and the engine's only entry point:
  picks the `outputType` entry out of that map

The interface earns its place for effects most naturally *written* as a coordinated set
(`LightningStrike` derives brightness and the white→blue shift from one phase), and because
`.fx.kts` definitions can declare `effectMode: COMPOSITE`. It does **not** get you two
properties from one instance: an `FxInstance` drives one `FxTarget`. Coordinating two
properties means two instances on the same speed master.

There was once an `FxInstance.compositeTargets` map and an engine branch that fanned
secondary outputs out to it. Nothing ever populated the map, so the branch was unreachable
and `LightningStrike`'s COLOUR output had always been dropped; both were deleted rather than
finished (sweep item A4). Reviving the ambition needs secondary targets on `FxInstance` *and*
an authoring surface that can name a constituent — see `FU-SPEED-PER-ATTRIBUTE` in
`docs/plans/followups.md`.

One consequence worth remembering when writing an effect: a mismatched `FxOutput` is
**silently dropped** by `FxTarget` (`if (output !is FxOutput.Slider) return` and friends), so a
composite's `compatibleProperties` must list only properties of its primary output type.
`LightningStrike` advertising `rgbColour` meant picking it produced no light and no error.

## `compatibleProperties` must match the output type

That silent drop is not a composite problem — it is the general rule, and it bit every effect in
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
- `category` — UI category ("dimmer", "colour", "position", "composite", "controls")
- `outputType` — primary output type
- `effectMode` — `STANDARD`, `STATEFUL`, or `COMPOSITE`
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
├── category          — "dimmer", "colour", "position", "composite", "controls"
├── output_type       — SLIDER, COLOUR, or POSITION
├── effect_mode       — STANDARD, STATEFUL, or COMPOSITE
├── parameters        — JSON schema [{name, type, defaultValue, description}]
├── compatible_properties — JSON array of property names
├── script            — the calculate() body only
├── project_id        — owning project (required)
├── default_step_timing
└── timing_source     — BEAT or WALL_CLOCK
```

### Script Model

Scripts contain **only the calculation logic** — the body of `calculate()`,
`calculateStateful()`, or `calculateComposite()`. All metadata (name, category,
parameters, etc.) is stored in the database/registration and managed via the UI.

**Parameter access** uses a typed accessor object (`TypedParams`) that pre-parses
string parameters according to the schema:

```kotlin
// TypedParams methods:
params.ubyte("min")          → UByte (default from schema)
params.int("count")          → Int
params.double("fadeRatio")   → Double
params.boolean("pingPong")   → Boolean
params.colour("baseColor")   → ExtendedColour (resolves a template reference, "tmpl:{uuid}")
params.colourList("colours") → List<ExtendedColour>
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
└── composite/
    └── LightningStrike.fx.kts  (COMPOSITE)
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

#### FxCompositeCalcScript (COMPOSITE)

For effects whose outputs are most naturally derived together from one phase. Only the entry
matching the definition's declared `outputType` is applied — see §"Composite Effects":

```kotlin
// Provided: phase (Double), context (EffectContext), params (TypedParams)
// Return: Map<FxOutputType, FxOutput> (last expression)

val intensity = if (phase < 0.1) 255 else ((1.0 - phase) * 255).toInt()
mapOf(
    FxOutputType.SLIDER to FxOutput.Slider(intensity.coerceIn(0, 255).toUByte()),
    FxOutputType.COLOUR to FxOutput.Colour(
        blendExtendedColours(params.colour("flashColour"), params.colour("decayColour"), phase)
    ),
)
```

### ScriptEffectAdapter

`ScriptEffectAdapter` bridges compiled scripts to the `Effect`/`StatefulEffect`/
`CompositeEffect` interfaces. It:

1. Compiles the script body using the appropriate base class
2. Caches compiled results by content hash
3. On `calculate()`: creates `TypedParams` from raw params + schema, evaluates script
4. For stateful: maintains a `MutableMap<String, Any>` per instance
5. For composite: evaluates the output map, of which only the primary entry is applied

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

### Locate versus park

The Locate toggle (`routes/lightLocate.kt`) asserts its centre-and-open-white values as
programmer entries (owner `locate`). "Locate wins" is **non-destructive** since the
programmer redesign: the entries suppress every effect covering the written properties for
as long as the locate holds, and releasing the locate lets them resume — nothing is
removed. (`ToggleLocateResponse.effectsRemoved` was dropped in Session 2, once the frontend
that read it no longer existed.)

Park sits above all of that at transmit time, so a locate cannot move a parked channel.
One filter keeps the sides honest: **`applyLocate` drops unpublishable assignments** using
`FxEngine.programmerPublishability`. That helper applies the *same two guards the cascade
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

Effects are iterated in sorted order (`priority` ascending, `id` ascending as a stable
tie-break) rather than from a `ConcurrentHashMap`. Multi-effect composition on the same
property is therefore **deterministic** — previously the effective "last-wins" ordering was
undefined.

Priority assignment:

- Manual effects: `priority = 0`
- Cue-owned effects: `priority = stackId * 1_000_000 + sortOrder * 1_000 + 1`
  (leaves gaps for future fine-tuning without renumbering)

Sorted snapshots are cached in `@Volatile` fields (`sortedBeatEffects`, `sortedWallClockEffects`)
and rebuilt under a `synchronized` block on every mutation. The tick readers are therefore
lock-free.

### Phase Calculation

The phase passed to each effect determines where in the cycle it evaluates. For group/multi-element
effects, `FxInstance.calculatePhaseForMember` computes:

```
memberPhase = (baseClock + phaseOffset - distributionOffset) % 1.0
```

The distribution offset is **subtracted** so that higher-offset members are behind in the
cycle. This makes the visual sweep flow in the natural direction (element 0 → N for LINEAR).

For PING_PONG distribution, a triangle wave remap is applied to the base clock phase before
adding offsets, causing all effects to sweep forward then backward:

```
tri = baseClock < 0.5 ? baseClock * 2 : 2 * (1 - baseClock)    // [0→1→0]
remappedClock = tri * (slots - 1) / slots                        // [0→maxOffset→0]
memberPhase = (remappedClock + phaseOffset - distOffset) % 1.0
```

The scaling to `(slots - 1) / slots` ensures the sweep reaches the last element without
wrapping back to the first (since `1.0 % 1.0 == 0.0` would alias with element 0).

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

### Static Effect Windowing

Static effects (StaticColour, StaticValue, StaticPosition) create chase patterns by only
being "on" for a window of each cycle. The window width is `1 / numDistinctSlots`, which
accounts for symmetric distributions where multiple members share an offset.

For standard distributions (LINEAR, CENTER_OUT, etc.), a modular distance check determines
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
effectiveDivision = beatDivision × distributionStrategy.distinctSlots(groupSize)
```

This uses `distinctSlots` rather than `groupSize` so that symmetric distributions
(CENTER_OUT, SPLIT) scale correctly — symmetric pairs share a slot.

**Default values**: Each effect type declares `defaultStepTiming` which is used when
creating new `FxInstance`s. Static effects (StaticColour, StaticValue, StaticPosition)
default to `true` (chase pattern), while continuous effects (SineWave, Pulse, etc.)
default to `false` (full cycle). The value can be overridden per-instance via the API.

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

Effects target fixture or element properties via `FxTarget` subclasses. Targets can reference either a single fixture or an entire group using `FxTargetRef`. The `applyValueToFixture` method accepts `GroupableFixture` (not `Fixture`), allowing it to work with both standalone fixtures and fixture elements:

### Target Reference Types

```kotlin
sealed class FxTargetRef {
    data class FixtureRef(val targetKey: String)  // Single fixture
    data class GroupRef(val targetKey: String)    // Fixture group
}
```

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
with dedicated calculate-only script base classes (`FxCalcScript`, `FxStatefulCalcScript`,
`FxCompositeCalcScript`). See [FX Definitions](#fx-definitions) above.

### FX_APPLICATION Scripts

Apply effects with implicit `fxEngine` — no need to pass the engine to every call.

```kotlin
val wash = fixture<HexFixture>("front-wash-1")
val movers = group<MovingHead>("movers")

wash.fx {
    dimmer(SineWave(), BeatDivision.HALF)
    colour(ColourCycle(), BeatDivision.ONE_BAR)
}

movers.fx {
    dimmer(Pulse(), BeatDivision.QUARTER, distribution = DistributionStrategy.CENTER_OUT)
    colour(RainbowCycle(), BeatDivision.TWO_BAR, distribution = DistributionStrategy.LINEAR)
}

setBpm(128.0)
```

### GENERAL Scripts (LightingScript)

Full-power scripts with explicit `fxEngine` parameter:

```kotlin
fixture.applyDimmerFx(fxEngine, SineWave(), FxTiming(BeatDivision.HALF))
fixture.fx(fxEngine) {
    dimmer(Pulse(), BeatDivision.QUARTER)
    colour(ColourCycle.PRIMARY, BeatDivision.WHOLE)
}
fixture.clearFx(fxEngine)

// Group with distribution
val group = fixtures.group<HexFixture>("front-wash")
group.applyDimmerFx(fxEngine, Pulse(), distribution = DistributionStrategy.LINEAR)
```

## Group Effect Processing

When an `FxInstance` targets a group, the `FxEngine` expands it at processing time:

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
| `LINEAR` | Evenly spaced phases, element 0 → N | N |
| `UNIFIED` | All fixtures same phase (synchronized) | 1 |
| `CENTER_OUT` | Rank-based: center fires first, radiates outward | ⌈N/2⌉ |
| `EDGES_IN` | Rank-based: edges fire first, converges to center | ⌈N/2⌉ |
| `REVERSE` | Evenly spaced phases, element N → 0 | N |
| `SPLIT` | Mirrored halves: both ends fire simultaneously, converging | ⌈N/2⌉ |
| `PING_PONG` | LINEAR offsets + triangle phase remap for bounce | N |
| `RANDOM(seed)` | Deterministic Fisher-Yates shuffle of evenly-spaced offsets | N |
| `POSITIONAL` | Based on normalized position | N |

**Symmetric strategies** (CENTER_OUT, EDGES_IN, SPLIT) have fewer distinct offset slots
than group members because symmetric pairs share the same offset. Static effects use
`numDistinctSlots` for window width to ensure gap-free chases.

**Strategy interface properties:**

| Property | Description |
|----------|-------------|
| `hasSpread` | Whether offsets differ between members (false only for UNIFIED) |
| `usesTrianglePhase` | Whether the base clock should be triangle-remapped (true for PING_PONG) |
| `distinctSlots(groupSize)` | Number of unique offset positions for a given group size |

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

Multi-element expansion uses the same `DistributionStrategy` machinery as group effects. The `distributionStrategy` field on `FxInstance` (which defaults to `LINEAR`) is used to calculate per-element phase offsets based on element index and normalized position.

The REST API `AddEffectRequest` includes an optional `distributionStrategy` field for fixture effects targeting multi-element fixtures.

### Example

```
POST /api/rest/fx/add
{
  "effectType": "RainbowCycle",
  "fixtureKey": "quad-mover-1",
  "propertyName": "rgbColour",
  "distributionStrategy": "LINEAR"
}
```

This applies a rainbow cycle to all 4 heads of the quad mover bar, with each head offset in phase to create a chase effect across the heads.

## REST API

### Clock Control

```
GET  /api/rest/fx/clock/status     → { bpm, isRunning }      (master 1)
POST /api/rest/fx/clock/bpm        ← { bpm: 120.0 }          (master 1)
POST /api/rest/fx/clock/tap        (tap tempo, master 1)
```

The legacy clock endpoints mean master 1 — the compatibility promise pinned by
`SocketMessageWireFormatTest`. Master CRUD is project-scoped:

```
GET    /api/rest/project/{id}/speed-masters        → [SpeedMasterDto...]  (lazily seeds 4)
GET    /api/rest/project/{id}/speed-masters/{mid}  → SpeedMasterDto
POST   /api/rest/project/{id}/speed-masters        ← { name?, bpm?, notes? }
PUT    /api/rest/project/{id}/speed-masters/{mid}  ← { name?, bpm?, notes? }
DELETE /api/rest/project/{id}/speed-masters/{mid}  → 409 SPEED_MASTER_PROTECTED (master 1)
                                                     | 409 SPEED_MASTER_IN_USE (?force=true overrides)
```

### Effect Management

```
GET    /api/rest/fx/active           → [EffectDto...]
POST   /api/rest/fx/add              ← AddEffectRequest → { effectId }
PUT    /api/rest/fx/{id}             ← UpdateEffectRequest → EffectDto
DELETE /api/rest/fx/{id}             (remove effect)
POST   /api/rest/fx/{id}/pause
POST   /api/rest/fx/{id}/resume
GET    /api/rest/fx/fixture/{key}    → { direct: [EffectDto...], indirect: [IndirectEffectDto...] }
DELETE /api/rest/fx/fixture/{key}    (clear fixture effects)
POST   /api/rest/fx/clear            (clear all effects)
```

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
  "distributionStrategy": "LINEAR",
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

### UpdateEffectRequest Format

```json
{
  "effectType": "Pulse",
  "parameters": { "min": "50", "max": "200" },
  "beatDivision": 2.0,
  "blendMode": "ADDITIVE",
  "phaseOffset": 0.25,
  "distributionStrategy": "CENTER_OUT",
  "elementMode": "FLAT",
  "stepTiming": false,
  "rateSpeedMasterUuid": "7d444840-9dc0-11d1-b245-5ffdce74fad2"
}
```

All fields are optional. Immutable fields (`effectType`, `parameters`, `beatDivision`, `blendMode`, `stepTiming`) trigger an atomic swap of the `FxInstance`, preserving id, start time, and running state. Mutable fields (`phaseOffset`, `distributionStrategy`, `elementMode`, `speedMasterUuid`, `rateSpeedMasterUuid`) are updated in place. Both master fields follow the same null-means-no-change convention; to return an effect to the default, send master 1's uuid (master 1 always exists and behaves identically to the null default).

## WebSocket Messages

### Client → Server

| Message | Description |
|---------|-------------|
| `fxState` | Request current FX state |
| `setFxBpm` | Set BPM `{ bpm: 120.0 }` — **master 1** |
| `tapTempo` | Tap for tempo — **master 1** |
| `speedMasters.state` | Request the full masters bank |
| `speedMasters.setBpm` | Set one master's BPM `{ masterUuid?, bpm }` (uuid omitted → master 1) |
| `speedMasters.tap` | Tap one master's tempo `{ masterUuid? }` |
| `removeFx` | Remove effect `{ effectId }` |
| `pauseFx` | Pause effect `{ effectId }` |
| `resumeFx` | Resume effect `{ effectId }` |
| `clearFx` | Clear all effects |
| `requestBeatSync` | Request a `beatSync` message on the next beat (e.g. after tab visibility change) |
| `speedMasters.requestBeat` | Keyed twin of `requestBeatSync` — `{ masterUuid? }`, omitted uuid = master 1 |

### Server → Client

| Message | Description |
|---------|-------------|
| `fxState` | Full FX state `{ bpm, isClockRunning, activeEffects }` — `bpm` is master 1; each effect carries `speedMasterUuid`/`speedMasterIndex` and `rateSpeedMasterUuid`/`rateSpeedMasterIndex` |
| `fxChanged` | Effect change notification `{ changeType, effectId }` |
| `beatSync` | Beat sync for frontend clock `{ beatNumber, bpm, timestampMs }` — master 1 only |
| `speedMasters.state` | Full bank `{ masters: [{ uuid, index, name, bpm, isRunning, source }] }` — sent on connect, on request, and as the reply to every `speedMasters.*` write |
| `speedMasters.changed` | One master's tempo moved `{ masterUuid, index, bpm, source, timestampMs }` — the live-BPM stream; CRUD invalidation goes via `speedMasterListChanged` instead |
| `speedMasters.beat` | One master crossed a beat boundary `{ masterUuid, index, beatNumber, bpm, timestampMs }` — the keyed analogue of `beatSync`, emitted for every master including master 1 |
| `speedMasterListChanged` | A master was created/renamed/deleted (cache-invalidation signal, never fired per tempo change) |

### Beat Sync

The `beatSync` message enables the frontend to synchronize a local beat visualization with the backend's Master Clock. It is sent:

- Every 16 beats (~8 seconds at 120 BPM) for periodic drift correction
- Immediately when BPM changes (with `beatNumber: -1` to distinguish from beat boundaries)
- On-demand when the client sends `requestBeatSync`

Between frames the client free-runs a local timer off the last `bpm` it was told. That
interpolation is load-bearing: `SocketScope.sendNextBeat` used to be set at construction and
never cleared, so the throttle was permanently bypassed and a frame went out on *every*
beat. It is consumed with `getAndSet(false)` now, making the request the one-shot it was
always documented to be.

`beatSync` is structurally master-1-only — it is wired to one `MasterClock` object, not
addressed by uuid — so **`speedMasters.beat`** is the keyed superset. Same cadence rules,
plus a `masterUuid`/`index` tag, fanned from `SpeedMasterBank.beats`: one flow for the whole
bank, fed by a per-clock `onBeat` hook wired where `onTick` already is. A subscriber binds
once and keeps working across a `load()`, because the tagging happens at emit time rather
than being captured into a per-master collector.

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
| `fx/Effect.kt` | Effect, StatefulEffect, CompositeEffect interfaces, FxOutput types |
| `fx/FxRegistry.kt` | Unified effect registry, EffectRegistration, ParameterInfo, EffectTypeInfo |
| `fx/FxFileLoader.kt` | Loads and parses .fx.kts files from resources |
| `fx/TypedParams.kt` | Typed parameter accessor for FX scripts |
| `fx/ScriptEffectAdapter.kt` | Bridges compiled FX scripts to Effect interfaces |
| `fx/FxScriptCompiler.kt` | Compiles and caches FX calculate scripts |
| `fx/EffectParamUtils.kt` | Parameter parsing utilities (parseExtendedColour, toUByteParam, etc.) |
| `fx/FxInstance.kt` | Running effect state, distributionStrategy, ElementMode, speed-master refs |
| `fx/FxTarget.kt` | Fixture/group property targeting, FxTargetRef |
| `fx/FxTargetable.kt` | Common interface for Fixture and FixtureGroup |
| `fx/FxEngine.kt` | Effect processing loop, group expansion |
| `fx/FxExtensions.kt` | Script DSL helpers |
| `fx/group/DistributionStrategy.kt` | Phase distribution strategies |
| `fx/group/GroupFxExtensions.kt` | Group effect extension functions |
| `fx/effects/DimmerEffects.kt` | Slider effect implementations |
| `fx/effects/ColourEffects.kt` | Color effect implementations |
| `fx/effects/PositionEffects.kt` | Position effect implementations |
| `fx/effects/CompositeEffects.kt` | Composite effect implementations (LightningStrike) |
| `fixture/trait/WithPosition.kt` | Position trait for moving heads |
| `fixture/trait/WithDimmer.kt` | Dimmer trait |
| `fixture/trait/WithColour.kt` | Colour trait |
| `fixture/trait/WithUv.kt` | UV trait |
| `fixture/group/GroupExtensions.kt` | Group property extensions |
| `scripts/ScriptType.kt` | Script type enum (GENERAL, FX_APPLICATION) |
| `scripts/scriptDef.kt` | LightingScript base class (GENERAL scripts) |
| `scripts/fxCalcScriptDef.kt` | FxCalcScript, FxStatefulCalcScript, FxCompositeCalcScript base classes |
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
| FxEngine processing | `Dispatchers.Default` | One pass per wake-up over a per-master tick frame |
| DMX output | Per-universe coroutine | ArtNet transmission |
| REST handlers | Ktor I/O | Request handling |
| WebSocket handlers | Ktor WebSocket | Message handling |

## Cue Integration

A cue is an ordered stack of **Look layers** plus its own local values and ad-hoc effects. The FX system supports cues via the `cueId` field on `FxInstance`.

### FxInstance.cueId

Each `FxInstance` has an optional `cueId: Int?` field (default `null`). When a cue is applied, all effects it creates are tagged with the cue's database ID. This enables:

- **Cue replacement**: When applying a new cue, all effects with any non-null `cueId` are removed first, then the new cue's effects are created with the new `cueId`.
- **Identification**: Effects created by a cue can be distinguished from manually applied effects (which have `cueId = null`).

The `cueId` is preserved across atomic swaps in `FxEngine.updateEffect()`, so updating an effect's parameters doesn't lose its cue association.

### Layers

A cue layer stores a **reference** to a Look (by id) plus the targets it operates over. At apply
time the Look is read fresh through `LookRegistry`, so editing a Look is reflected the next time the
cue is applied — and, for a *live* cue, immediately, via `republishForLookEdit`. Each layer's effects
are created as `FxInstance`s tagged with the cue id.

**Effects spawn in layer order, and that alone is enough to make layer order the composition
order.** `sortedEffectsComparator` is `compareBy(priority, id)` with `id` a monotonic creation
counter, and per-tick composition is a genuine sequential fold through `FxTarget.applyValue`, so
same-priority effects already resolve last-created-wins. No priority arithmetic is needed and the
uniform per-cue priority stays. A deferred Look effect fans over the layer's targets; a bound one
uses its own, filtered by the layer's target set when that set is non-empty.

The one limit worth stating: effects are Layer 3 and static values are Layer 4, so an effect sits
above a static value regardless of layer order. See `lighting-composition-model.md`
§"Effects, and the constraint that cannot be layered away".

### Ad-Hoc Effects

Effects belonging to no Look are stored as full effect definitions in the `cue_ad_hoc_effects`
table. At apply time these are converted directly to `FxInstance`s tagged with the cue id.

### Look Delete Blocking

A Look cannot be deleted if any cue layer references it via `cue_layers` — a plain indexed FK query,
where the named-palette era could only scan opaque `value` text. The Look detail API includes `layerCount`
and the referencing cue names; the delete returns 409 with code `LOOK_IN_USE`, and `?force=true`
deletes the layers with it.

### From-State Capture

The "create from current state" operation captures the live FX engine state:

1. Effects with a non-null `lookId` are grouped **by Look**, collecting their targets into cue layer
   rows. A Look applied twice to different targets therefore collapses into one layer covering both
   — the honest reading of a *snapshot*, since the stage cannot tell you it was two gestures.
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
| `fx/CueComposer.kt` | The cook step, layer blending, layer-ordered effect spawning |
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

### Architecture

The FxEngine runs **two independent processing loops**:

```
FxEngine
├── processBeatTickSuspend(frame)  ← SpeedMasterBank.wake (CONFLATED; any master's tick)
│   └── One pass over one Frame of per-master ticks; each BEAT effect
│       computes phase from frame.tick(effect.speedMasterSlot)
│
└── processWallClockTick()         ← 50Hz fixed-interval coroutine
    └── Processes effects where timingSource == WALL_CLOCK, with
        frame.rateScale(effect.rateMasterSlot) scaling the cycle
```

Each loop resets only the properties controlled by its own effects, preventing the two timing sources from interfering with each other.

### Beat ↔ Wall-Clock interaction

Because each loop owns its own sorted-snapshot of effects and its own reset pass, effects
on **different** properties don't interact across timing sources. Two interesting cases
on the **same** property:

1. **Beat and wall-clock effects on the same property** — both loops reset to the
   layer below (via `LayerResolver`) before applying their effects. Each loop sees the
   other loop's effect output as Layer 3 accumulator state *within its own tick*, but
   because reset always goes back to L3/L4/L5, the two loops don't compound indefinitely.
   The effective behaviour is that the last-run loop for a given frame wins for OVERRIDE,
   and ADDITIVE/MAX compose naturally.
2. **Frame alignment** — the beat loop fires on the bank's conflated wake channel (any
   master's tick), the wall-clock loop fires on a 20 ms ticker. There is no explicit
   synchronisation. Both loops write into
   the same `ControllerTransaction` instance? **No** — each loop creates its own
   transaction and applies independently. They do *not* produce two network packets,
   though: ArtNet output is a continuous stream that samples `currentValues` once per
   `refreshIntervalMs`, so any number of transactions within a frame collapse to exactly
   one packet. What survives is that the last writer before the tick wins.

**Rule of thumb**: mixing BEAT and WALL_CLOCK effects on the same property is supported
but not recommended — use one timing source per property to keep the result predictable.

### Wall-Clock Phase Calculation

For wall-clock effects, `beatDivision` is reinterpreted as cycle duration in seconds. A
**rate master** (`FxInstance.rateSpeedMasterUuid`, null → unscaled) sets how fast that cycle
is consumed, at `master.bpm / 120`:

```kotlin
// once per 50 Hz pass, per effect, before any phase is read
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

`rateSpeedMasterUuid` is settable everywhere `speedMasterUuid` is — REST add/update, look
effects, cue ad-hoc effects, per-application overrides, cue-edit, and sync. The two coexist
rather than excluding each other, so an effect whose `timingSource` changes keeps both
assignments; BEAT effects simply never read the rate scale. **Scripts and the AI tool are
the deliberate exception** — neither has ever addressed a per-effect master at all, so there
was nothing to mirror.

For STATEFUL effects, `deltaMs` is computed from the wall-clock interval directly. These
effects already use `deltaMs` rather than phase, so they work naturally — and by the same
token a rate master has no effect on one that never reads its phase. Both shipped wall-clock
effects (`CandleFlicker`, `FluorescentFlicker`) are STATEFUL, so nothing in-tree currently
exercises rate scaling end to end; author a STANDARD + WALL_CLOCK definition to try it.

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
5. **Unified single-transaction per frame**: beat + wall-clock loops currently each create
   their own `ControllerTransaction`. A shared frame-scoped transaction would reduce
   duplicate transmissions when both loops target the same universe in the same ~20 ms
   window. Control-surface plan Phase 8 (2026-04-23) landed the non-blocking commit half of
   that ticket — both tick loops now call `transaction.applySuspend()` instead of blocking
   per-channel acks — but intentionally deferred the two-loop coordination. Continuous
   ArtNet streaming has since made the duplicate-*transmission* motivation moot (one packet
   per universe per frame, always), leaving only last-writer-wins ordering; the shared-state
   machinery (`AtomicReference<FrameTransaction?>` + short mutex around open/close) isn't a
   clear win for that alone.
