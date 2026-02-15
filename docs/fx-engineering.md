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
│   │   For each active effect:                                   │   │
│   │     1. Calculate phase based on clock + effect timing       │   │
│   │     2. Effect.calculate(phase) → output value               │   │
│   │     3. Apply blend mode (override/additive/multiply)        │   │
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

## Blend Modes

How effect output combines with fixture's base value:

| Mode | Description | Use Case |
|------|-------------|----------|
| `OVERRIDE` | Effect replaces value | Primary effects |
| `ADDITIVE` | Effect added to value | Accent layers |
| `MULTIPLY` | Effect multiplies value | Proportional dimming |
| `MAX` | Maximum of both | Ensure minimums |
| `MIN` | Minimum of both | Limit maximums |

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

## Script Integration

### Available in Scripts

Scripts have access to:
- `fxEngine` - The FX engine instance
- `masterClock` - Quick access to clock
- `bpm` - Current BPM value
- `setBpm(bpm)` - Set tempo
- `tapTempo()` - Tap for tempo

### Extension Functions

```kotlin
// Apply to fixtures via traits
fixture.applyDimmerFx(fxEngine, SineWave(), FxTiming(BeatDivision.HALF))
fixture.applyColourFx(fxEngine, RainbowCycle(), FxTiming(BeatDivision.ONE_BAR))
fixture.applyPositionFx(fxEngine, Circle(), FxTiming(BeatDivision.TWO_BARS))

// DSL builder
fixture.fx(fxEngine) {
    dimmer(Pulse(), BeatDivision.QUARTER)
    colour(ColourCycle.PRIMARY, BeatDivision.WHOLE)
}

// Clear effects
fixture.clearFx(fxEngine)
```

### Chase Example (Group-Level Targeting)

```kotlin
// Chase effect across fixture group - single FxInstance with distribution
val group = fixtures.group<HexFixture>("front-wash")
val effectId = group.applyDimmerFx(
    fxEngine,
    Pulse(min = 0u, max = 255u),
    timing = FxTiming(BeatDivision.QUARTER),
    distribution = DistributionStrategy.LINEAR  // Auto phase offsets
)

// Query effects for this group
val activeEffects = fxEngine.getEffectsForGroup("front-wash")
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

| Strategy | Description |
|----------|-------------|
| `LINEAR` | Evenly spaced phases (chase effect) |
| `UNIFIED` | All fixtures same phase (synchronized) |
| `CENTER_OUT` | Effects radiate from center |
| `EDGES_IN` | Effects converge to center |
| `REVERSE` | Reverse linear order |
| `SPLIT` | Left/right halves mirror |
| `PING_PONG` | Back-and-forth sweep |
| `RANDOM(seed)` | Deterministic random |
| `POSITIONAL` | Based on normalized position |

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
  "distributionStrategy": "LINEAR"
}
```

The `distributionStrategy` field is optional. When provided, it sets the distribution strategy for multi-element fixture expansion (see [Multi-Element Fixture Expansion](#multi-element-fixture-expansion)). For non-multi-element fixtures, it is ignored.

### UpdateEffectRequest Format

```json
{
  "effectType": "Pulse",
  "parameters": { "min": "50", "max": "200" },
  "beatDivision": 2.0,
  "blendMode": "ADDITIVE",
  "phaseOffset": 0.25,
  "distributionStrategy": "CENTER_OUT",
  "elementMode": "FLAT"
}
```

All fields are optional. Immutable fields (`effectType`, `parameters`, `beatDivision`, `blendMode`) trigger an atomic swap of the `FxInstance`, preserving id, start time, and running state. Mutable fields (`phaseOffset`, `distributionStrategy`, `elementMode`) are updated in place.

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
| `fx/Effect.kt` | Effect interface and FxOutput types |
| `fx/FxInstance.kt` | Running effect state, distributionStrategy, ElementMode |
| `fx/FxTarget.kt` | Fixture/group property targeting, FxTargetRef |
| `fx/FxTargetable.kt` | Common interface for Fixture and FixtureGroup |
| `fx/FxEngine.kt` | Effect processing loop, group expansion |
| `fx/FxExtensions.kt` | Script DSL helpers |
| `fx/group/DistributionStrategy.kt` | Phase distribution strategies |
| `fx/group/GroupFxExtensions.kt` | Group effect extension functions |
| `fx/effects/DimmerEffects.kt` | Slider effect implementations |
| `fx/effects/ColourEffects.kt` | Color effect implementations |
| `fx/effects/PositionEffects.kt` | Position effect implementations |
| `fixture/trait/WithPosition.kt` | Position trait for moving heads |
| `fixture/trait/WithDimmer.kt` | Dimmer trait |
| `fixture/trait/WithColour.kt` | Colour trait |
| `fixture/trait/WithUv.kt` | UV trait |
| `fixture/group/GroupExtensions.kt` | Group property extensions |
| `routes/lightFx.kt` | FX REST API endpoints |
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

## Future Considerations

1. **Effect Stacking**: Multiple effects on same property with priority/mixing
2. **Effect Presets**: Saved effect configurations
3. **MIDI Clock Sync**: Accept external MIDI clock as tempo source
4. **Beat Detection**: Auto-detect BPM from audio input
5. **Effect Modulation**: Effects that modulate other effects' parameters
6. **Custom Distribution Functions**: User-defined distribution curves via scripts
