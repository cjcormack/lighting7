# FX (Effects) System Engineering Documentation

This document describes the effects subsystem for tempo-synchronized lighting effects.

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

## Master Clock

The `MasterClock` provides a global tempo reference that all effects synchronize to.

### Key Properties

| Property | Type | Description |
|----------|------|-------------|
| `bpm` | `StateFlow<Double>` | Current tempo (20-300 BPM) |
| `isRunning` | `StateFlow<Boolean>` | Whether clock is active |
| `tickFlow` | `SharedFlow<ClockTick>` | Emits 24 times per beat |
| `beatFlow` | `SharedFlow<BeatEvent>` | Emits once per beat |

### Clock Resolution

Like MIDI clock, the Master Clock emits 24 ticks per beat. This provides:
- Smooth effect interpolation
- Beat-quantized effect starts
- Sub-beat timing resolution

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
| `ColourCycle` | Step through palette | `colours`, `fadeRatio` |
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

Composite effects produce outputs for multiple property types simultaneously,
enabling coordinated multi-property animations from a single effect.

| Effect | Description | Output Types | Parameters |
|--------|-------------|--------------|------------|
| `LightningStrike` | Flash + colour shift | SLIDER + COLOUR | `maxBrightness`, `minBrightness`, `flashColour`, `decayColour`, `ambientColour` |

Composite effects implement the `CompositeEffect` interface:
- `outputTypes: Set<FxOutputType>` — declares all output types produced
- `calculateComposite(phase, context)` — returns `Map<FxOutputType, FxOutput>`
- Secondary outputs are routed to targets in `FxInstance.compositeTargets`

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
- `factory` — creates an `Effect` from string parameters + palette suppliers

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
params.colour("baseColor")   → ExtendedColour (resolves palette refs like "P1")
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

For effects that produce multiple output types simultaneously:

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
5. For composite: evaluates and returns the output map

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
their neutral value (0 for sliders, black for colours, center for positions). This prevents
blend modes like `MAX` and `ADDITIVE` from accumulating across ticks — without the reset,
a `MAX` blend would read the previous tick's result as the base value, causing values to
ratchet upward and never decrease.

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
GET  /api/rest/fx/clock/status     → { bpm, isRunning }
POST /api/rest/fx/clock/bpm        ← { bpm: 120.0 }
POST /api/rest/fx/clock/tap        (tap tempo)
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
  "stepTiming": true
}
```

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
  "stepTiming": false
}
```

All fields are optional. Immutable fields (`effectType`, `parameters`, `beatDivision`, `blendMode`, `stepTiming`) trigger an atomic swap of the `FxInstance`, preserving id, start time, and running state. Mutable fields (`phaseOffset`, `distributionStrategy`, `elementMode`) are updated in place.

## WebSocket Messages

### Client → Server

| Message | Description |
|---------|-------------|
| `fxState` | Request current FX state |
| `setFxBpm` | Set BPM `{ bpm: 120.0 }` |
| `tapTempo` | Tap for tempo |
| `removeFx` | Remove effect `{ effectId }` |
| `pauseFx` | Pause effect `{ effectId }` |
| `resumeFx` | Resume effect `{ effectId }` |
| `clearFx` | Clear all effects |
| `requestBeatSync` | Request a `beatSync` message on the next beat (e.g. after tab visibility change) |

### Server → Client

| Message | Description |
|---------|-------------|
| `fxState` | Full FX state `{ bpm, isClockRunning, activeEffects }` |
| `fxChanged` | Effect change notification `{ changeType, effectId }` |
| `beatSync` | Beat sync for frontend clock `{ beatNumber, bpm, timestampMs }` |

### Beat Sync

The `beatSync` message enables the frontend to synchronize a local beat visualization with the backend's Master Clock. It is sent:

- Every 16 beats (~8 seconds at 120 BPM) for periodic drift correction
- Immediately when BPM changes (with `beatNumber: -1` to distinguish from beat boundaries)
- On-demand when the client sends `requestBeatSync`

## File Reference

| File | Purpose |
|------|---------|
| `dmx/EasingCurve.kt` | Easing curve implementations |
| `dmx/ChannelChange.kt` | DMX change with curve support |
| `dmx/TickerState.kt` | Curve-aware interpolation |
| `fx/MasterClock.kt` | Global tempo management |
| `fx/BeatDivision.kt` | Timing constants |
| `fx/Effect.kt` | Effect, StatefulEffect, CompositeEffect interfaces, FxOutput types |
| `fx/FxRegistry.kt` | Unified effect registry, EffectRegistration, ParameterInfo, EffectTypeInfo |
| `fx/FxFileLoader.kt` | Loads and parses .fx.kts files from resources |
| `fx/TypedParams.kt` | Typed parameter accessor for FX scripts |
| `fx/ScriptEffectAdapter.kt` | Bridges compiled FX scripts to Effect interfaces |
| `fx/FxScriptCompiler.kt` | Compiles and caches FX calculate scripts |
| `fx/EffectParamUtils.kt` | Parameter parsing utilities (parseExtendedColour, toUByteParam, etc.) |
| `fx/FxInstance.kt` | Running effect state, distributionStrategy, ElementMode, compositeTargets |
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
| MasterClock | `Dispatchers.Default` | Tick generation |
| FxEngine processing | `Dispatchers.Default` | Effect calculation |
| DMX output | Per-universe coroutine | ArtNet transmission |
| REST handlers | Ktor I/O | Request handling |
| WebSocket handlers | Ktor WebSocket | Message handling |

## Cue Integration

Cues bundle a colour palette with FX state (preset applications + ad-hoc effects) into a named snapshot. The FX system supports cues via the `cueId` field on `FxInstance`.

### FxInstance.cueId

Each `FxInstance` has an optional `cueId: Int?` field (default `null`). When a cue is applied, all effects it creates are tagged with the cue's database ID. This enables:

- **Cue replacement**: When applying a new cue, all effects with any non-null `cueId` are removed first, then the new cue's effects are created with the new `cueId`.
- **Identification**: Effects created by a cue can be distinguished from manually applied effects (which have `cueId = null`).

The `cueId` is preserved across atomic swaps in `FxEngine.updateEffect()`, so updating an effect's parameters doesn't lose its cue association.

### Preset Applications

Cues store **references** to FX presets (by ID) plus the targets they should be applied to. At apply time, the preset is read fresh from the database, so edits to a preset are always reflected when the cue is next applied. Each preset's effects are created as `FxInstance`s tagged with the cue ID.

### Ad-Hoc Effects

Effects that were manually applied (not from a preset) are stored as full effect definitions in the `cue_ad_hoc_effects` table. At apply time, these are converted directly to `FxInstance`s tagged with the cue ID.

### Preset Delete Blocking

A preset cannot be deleted if any cue references it via `cue_preset_applications`. The preset detail API includes `cueUsageCount` (number of cue preset application rows referencing the preset) and `cannotDeleteReason` when deletion is blocked.

### From-State Capture

The "create from current state" operation captures the live FX engine state:

1. Effects with a non-null `presetId` are grouped by preset, collecting their targets into `CuePresetApplication` rows.
2. Effects with a null `presetId` are stored as individual `CueAdHocEffect` rows with all effect fields captured.

### Related Files

| File | Cue-related content |
|------|-------------------|
| `fx/FxInstance.kt` | `cueId` field |
| `fx/FxEngine.kt` | `cueId` preservation in `updateEffect()` |
| `models/cues.kt` | `DaoCues`, `DaoCuePresetApplications`, `DaoCueAdHocEffects` tables |
| `routes/projectCues.kt` | Cue CRUD, apply, from-state endpoints |
| `routes/projectFxPresets.kt` | Preset delete blocking, `cueUsageCount` |

See `docs/cues-engineering.md` for full cue system documentation.

## Timing Source

Effects can run on one of two timing sources:

| Source | Description | Tick Rate | Phase Calculation |
|--------|-------------|-----------|-------------------|
| `BEAT` | Synchronized to the Master Clock's BPM-based ticks | 24 ticks/beat (variable) | Based on beat position via `MasterClock.phaseForDivision()` |
| `WALL_CLOCK` | Fixed-interval timer independent of BPM | 50Hz (20ms) | Based on elapsed wall-clock time since effect start |

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
├── processBeatTick()       ← MasterClock.tickFlow (24 ticks/beat)
│   └── Processes effects where timingSource == BEAT
│
└── processWallClockTick()  ← 50Hz fixed-interval coroutine
    └── Processes effects where timingSource == WALL_CLOCK
```

Each loop resets only the properties controlled by its own effects, preventing the two timing sources from interfering with each other.

### Wall-Clock Phase Calculation

For STANDARD wall-clock effects, `beatDivision` is reinterpreted as cycle duration in seconds:

```kotlin
val cycleDurationMs = (beatDivision * 1000.0).toLong()
val elapsed = System.currentTimeMillis() - startedAtMs
val phase = (elapsed % cycleDurationMs).toDouble() / cycleDurationMs
```

For STATEFUL effects, `deltaMs` is computed from the wall-clock interval directly. These effects already use `deltaMs` rather than phase, so they work naturally.

### Configuration

Set `timingSource` in the `.fx.kts` frontmatter or via the FX definitions API:

```kotlin
/*---
id: CandleFlicker
effectMode: STATEFUL
timingSource: WALL_CLOCK
---*/
```

The timing source is stored on `EffectRegistration` in the `FxRegistry` and propagated to `FxInstance.timingSource` when effects are created via presets, cues, or the REST API.

## Future Considerations

1. **MIDI Clock Sync**: Accept external MIDI clock as tempo source
2. **Beat Detection**: Auto-detect BPM from audio input
3. **Effect Modulation**: Effects that modulate other effects' parameters
4. **Custom Distribution Functions**: User-defined distribution curves via scripts
