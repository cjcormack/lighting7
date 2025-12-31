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

Effects target fixture properties via `FxTarget` subclasses. Targets can reference either a single fixture or an entire group using `FxTargetRef`:

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
| `SliderTarget` | `dimmer`, `uvColour` | `FixtureWithDimmer`, `FixtureWithUv` |
| `ColourTarget` | `rgbColour` | `FixtureWithColour` |
| `PositionTarget` | `pan`, `tilt` | `FixtureWithPosition` |

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
│   For each group member:                                         │
│     1. Get member's index and normalizedPosition                 │
│     2. Calculate phase offset via DistributionStrategy           │
│     3. effect.calculate(phase + offset) → output                 │
│     4. Apply to member fixture                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

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

## REST API

### Clock Control

```
GET  /api/rest/fx/clock/status     → { bpm, isRunning }
POST /api/rest/fx/clock/bpm        ← { bpm: 120.0 }
POST /api/rest/fx/clock/tap        (tap tempo)
```

### Effect Management

```
GET  /api/rest/fx/active           → [EffectDto...]
POST /api/rest/fx/add              ← AddEffectRequest → { effectId }
DELETE /api/rest/fx/{id}           (remove effect)
POST /api/rest/fx/{id}/pause
POST /api/rest/fx/{id}/resume
DELETE /api/rest/fx/fixture/{key}  (clear fixture effects)
POST /api/rest/fx/clear            (clear all effects)
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
  }
}
```

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

### Server → Client

| Message | Description |
|---------|-------------|
| `fxState` | Full FX state `{ bpm, isClockRunning, activeEffects }` |
| `fxChanged` | Effect change notification `{ changeType, effectId }` |

## File Reference

| File | Purpose |
|------|---------|
| `dmx/EasingCurve.kt` | Easing curve implementations |
| `dmx/ChannelChange.kt` | DMX change with curve support |
| `dmx/TickerState.kt` | Curve-aware interpolation |
| `fx/MasterClock.kt` | Global tempo management |
| `fx/BeatDivision.kt` | Timing constants |
| `fx/Effect.kt` | Effect interface and FxOutput types |
| `fx/FxInstance.kt` | Running effect state, distributionStrategy |
| `fx/FxTarget.kt` | Fixture/group property targeting, FxTargetRef |
| `fx/FxTargetable.kt` | Common interface for Fixture and FixtureGroup |
| `fx/FxEngine.kt` | Effect processing loop, group expansion |
| `fx/FxExtensions.kt` | Script DSL helpers |
| `fx/group/DistributionStrategy.kt` | Phase distribution strategies |
| `fx/group/GroupFxExtensions.kt` | Group effect extension functions |
| `fx/effects/DimmerEffects.kt` | Slider effect implementations |
| `fx/effects/ColourEffects.kt` | Color effect implementations |
| `fx/effects/PositionEffects.kt` | Position effect implementations |
| `fixture/FixtureWithPosition.kt` | Position trait for moving heads |
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
