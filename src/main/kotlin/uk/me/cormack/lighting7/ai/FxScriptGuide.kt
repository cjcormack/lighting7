package uk.me.cormack.lighting7.ai

/**
 * What `get_fx_script_guide` answers: everything a model needs to write a custom FX with no access to
 * the desk's source — the script's scope, its outputs, the parameter schema and an example of each mode.
 * Kept beside [FxScriptTools] and held to the compiler by `McpFxScriptToolsTest`, which compiles both
 * examples.
 */
internal object FxScriptGuide {

    /** The STANDARD example, compiled by the test that guards this guide. */
    const val STANDARD_EXAMPLE = """val low = params.ubyte("low").toInt()
val high = params.ubyte("high").toInt()
val width = params.double("width").coerceIn(0.01, 1.0)
// A sharp attack and a cosine tail, the pulse `width` of the cycle wide.
val level = if (phase < width) (cos(phase / width * PI) + 1.0) / 2.0 else 0.0
FxOutput.Slider((low + (high - low) * level).roundToInt().coerceIn(0, 255).toUByte())"""

    /** The STATEFUL example, compiled by the test that guards this guide. */
    const val STATEFUL_EXAMPLE = """val key = "level-" + context.memberIndex
val rate = params.double("rate")
var level = state.getOrPut(key) { 0.0 } as Double
// Drift towards a target that moves every half second, a different one per head.
val step = tick.timestampMs / 500 + context.memberIndex * 7919
val target = ((sin(step * 12.9898) * 43758.5453) % 1.0).absoluteValue * 255.0
level += (target - level) * (rate * deltaMs / 1000.0).coerceIn(0.0, 1.0)
state[key] = level
FxOutput.Slider(level.roundToInt().coerceIn(0, 255).toUByte())"""

    val text: String = """
# Writing a custom FX for this desk

A custom FX is a short **Kotlin script body**: the desk wraps it in a function and calls it for every head the FX
runs on, many times a second, and the value of the **last expression** is that head's output. You do not write a
class, a function signature or imports. Save it with save_fx_script, then run it like any built-in: apply_fx puts
it in the programmer on fixtures or groups, preview_fx samples what it outputs, and Looks, cues and FX palettes
name it by its fxId. get_fx_script with an fxType shows any FX's script — the built-ins make good examples.

## The two modes

**STANDARD** (most FX): a pure function of where the head is in the cycle. In scope:
- `phase: Double` — 0.0 to 1.0 through the cycle, already shifted for this head by the FX's spread, order and
  direction. Don't add your own per-head offset: the spread does that, and the operator controls it.
- `context: FxContext` — `groupSize` (heads the FX is spread over), `memberIndex` (0-based, this head),
  `distributionOffset` (this head's phase offset, 0–1), `hasDistributionSpread` (false when every head is in
  step), `numDistinctSlots` (distinct offsets: fewer than groupSize under a centre-out order).
  `context.basePhase(phase)` undoes the head's offset, giving the cycle's own position.
- `params: TypedParams` — the FX's parameters, read by type (below).

**STATEFUL**: for things that evolve and are not a function of phase — flicker, random drift, decay. In scope:
- `tick: MasterClock.ClockTick` — `tickNumber` (24 a beat), `beatNumber`, `tickInBeat` (0–23), `phase` (through
  the current beat), `timestampMs` (wall clock).
- `deltaMs: Long` — milliseconds since the last call (0 on the first).
- `context`, `params` — as above.
- `state: MutableMap<String, Any>` — kept between calls. **One map is shared by every head of the FX**, so key
  each head's values by `context.memberIndex`, and cast on the way out (`state.getOrPut(key) { 0.0 } as Double`).
A stateful FX usually runs on timingSource WALL_CLOCK (every 20 ms, whatever the tempo); on BEAT it is called on
the master's ticks.

Every call must be quick and side-effect free apart from `state`: no I/O, no threads, no sleeping, no global
variables. It runs inside the desk's output loop, so a slow script costs the show frames, and one that keeps
throwing is paused by the desk.

## Outputs — the last expression must be one, matching outputType

- outputType SLIDER → `FxOutput.Slider(value: UByte)` — a level 0–255 (`200u`, or `x.toUByte()` from an Int you
  have already clamped with `coerceIn(0, 255)`). Drives dimmer, uv, strobe, zoom, iris, frost, focus or any
  slider or setting attribute.
- outputType COLOUR → `FxOutput.Colour(java.awt.Color)` for RGB, or `FxOutput.Colour(ExtendedColour(color,
  white = 0u, amber = 0u, uv = 0u))` to drive a head's extra emitters. `Color(r, g, b)` takes Ints 0–255 (or
  Floats 0–1); `Color.getHSBColor(hue, saturation, brightness)` takes Floats 0–1. Drives `rgbColour`.
- outputType POSITION → `FxOutput.Position(pan: UByte, tilt: UByte)` — 128 is the centre of each axis. Drives
  `position`. Under the blend ADDITIVE it moves heads about their current position, centred on 128.

Default imports: `kotlin.math.*` (sin, cos, PI, abs, roundToInt, absoluteValue, pow…), `java.awt.Color`, and the
desk's FX package: `FxOutput`, `ExtendedColour`, `blendExtendedColours(a, b, ratio)`, `parseExtendedColour("#ff8000;w120")`.
Fully qualified names reach the rest of the JVM, but there is nothing there an FX needs.

## Parameters

Each parameter is `{name, type, defaultValue, label?, description?, min?, max?, step?, unit?, options?, role?,
pair?, shownWhen?}`, and the operator edits it in the FX editor and on palettes. Read them with the accessor for
their type; an unset or blank value reads as its defaultValue.

| type | read with | value |
|---|---|---|
| ubyte | `params.ubyte(n)` → UByte | 0–255, a level or an axis |
| int | `params.int(n)` | whole number |
| double | `params.double(n)` | number |
| float | `params.float(n)` | number, as Float (handy for Color) |
| boolean | `params.boolean(n)` | "true" / "false" |
| string | `params.string(n)` | free text |
| choice | `params.choice(n)` → String | one of `options`, `[{value, label}]`; defaultValue must be one |
| colour | `params.colour(n)` → ExtendedColour | `#rrggbb`, optionally `;w120;a40;uv10`, or a colour palette reference |
| colourList | `params.colourList(n)` → List<ExtendedColour> | comma-separated colours |
| easingCurve | `params.easingCurve(n)` → EasingCurve | LINEAR, SINE_IN_OUT, QUAD_IN, … ; `curve.apply(t)` maps 0–1 to 0–1 |

Editor metadata, all optional: `min`/`max`/`step` on a number; `unit` — `%` (a ubyte shown as a percent of full),
`position` (a ubyte about its centre), `ratio` (a 0–1 double or float shown as a percent), `dmx`, `ms`; `role`
`main` (shown with the FX) or `detail` (folded under More); `pair` on a low naming its high, drawn as one range
(same numeric type); `shownWhen: {param, values}` to show it only while a choice holds one of those values.
Parameter names are the keys that apply_fx, Looks and cues pass in `parameters`, always as strings.

## The rest of the definition

- `fxId` — the FX's id in the library (letters, digits, `-`, `_`), unique in the show and not a built-in's. Saving
  an existing fxId replaces that definition.
- `name` — what the operator sees. `family` — the library section: intensity, colour, position, beam or controls.
- `compatibleAttributes` — the attributes the editor offers it on, matching outputType: a SLIDER FX lists slider
  or setting attributes (`dimmer`, `uv`, `zoom`…), a COLOUR FX `rgbColour`, a POSITION FX `position`.
- `timingSource` — BEAT (follows a speed master's tempo; `cycle` in beats) or WALL_CLOCK (runs in seconds).
- `defaultTiming` — CYCLE (the cycle is the whole FX) or HEAD (each head's step, for a chase that visits heads in
  turn).

## Working loop

1. Write the script. check_fx_script compiles it and names each error by `line:column` of your script.
2. save_fx_script with the whole definition: it compiles, runs the script on a few phases with its default
   parameters, and refuses — writing nothing — if it fails to compile, throws, or returns the wrong output for its
   outputType. Its answer has those sample outputs.
3. preview_fx draws it over notional heads (or real targets) without touching the stage: per head, the values it
   would output across the window. Check the shape, then apply_fx it in the programmer and record_cue or
   record_look to keep it.
Saving a new script does not change an FX that is already running: apply it again to pick up the new code.

## Example — STANDARD, SLIDER: a pulse with a cosine tail

parameters: low (ubyte, "0", unit %, pair high), high (ubyte, "255", unit %), width (double, "0.3", min 0.01, max 1,
unit ratio).
```kotlin
$STANDARD_EXAMPLE
```

## Example — STATEFUL, SLIDER, WALL_CLOCK: per-head random drift

parameters: rate (double, "2.0", min 0.1, max 20) — how quickly each head chases its target.
```kotlin
$STATEFUL_EXAMPLE
```
""".trimIndent()
}
