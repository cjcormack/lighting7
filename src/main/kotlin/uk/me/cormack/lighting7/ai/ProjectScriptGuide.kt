package uk.me.cormack.lighting7.ai

/**
 * What `get_script_guide` answers: the three project script types' scope, in the words a model with no
 * access to the desk's source needs. `McpProjectScriptToolsTest` compiles and runs every example.
 */
internal object ProjectScriptGuide {

    const val FX_APPLICATION_EXAMPLE = """val foh = group<uk.me.cormack.lighting7.fixture.dmx.GenericDimmerFixture>("FOH")
foh.dimmerFx(effect("Wave", "low" to "40"), FxTiming(CycleLength.ONE_BAR), order = SpreadOrder.NORMAL)
setBpm(120.0)"""

    const val GENERAL_EXAMPLE = """val lamps = listOf("foh-1", "foh-2").map { fixture<GenericDimmerFixture>(it) }
lamps.forEachIndexed { i, lamp -> lamp.dimmer.fadeToValue((100 + 100 * i).toUByte(), 1000) }
"set " + lamps.size + " lamps""""

    const val FX_DEFINITION_EXAMPLE = """registerFx(FxRegistration(
    id = "half-on",
    name = "Half On",
    family = "intensity",
    outputType = FxOutputType.SLIDER,
    parameters = listOf(ParameterInfo("level", "ubyte", "255", "The level in the cycle's first half")),
    compatibleAttributes = listOf("dimmer"),
    factory = { params, _, _ ->
        val level = params["level"]?.toUByteParam() ?: 255u
        object : Fx {
            override val name = "Half On"
            override val outputType = FxOutputType.SLIDER
            override val parameters get() = mapOf("level" to level.toString())
            override fun calculate(phase: Double, context: FxContext): FxOutput =
                FxOutput.Slider(if (phase < 0.5) level else 0u)
        }
    },
))"""

    /** The guide, then the classes [rig] holds: what `fixture<T>` and `group<T>` are typed by. */
    fun text(rig: List<Pair<String, Class<*>>>): String = buildString {
        append(TEXT)
        appendLine()
        appendLine()
        appendLine("## This rig's fixture classes")
        if (rig.isEmpty()) appendLine("Nothing is patched.")
        rig.groupBy({ it.second }, { it.first }).forEach { (type, keys) ->
            val traits = TRAITS.filter { it.second.isAssignableFrom(type) }.joinToString { it.first }
            appendLine("- `${type.canonicalName ?: type.name}` (${traits.ifEmpty { "no traits" }}): ${keys.joinToString()}")
        }
    }

    private val TRAITS = listOf(
        "dimmer" to uk.me.cormack.lighting7.fixture.trait.WithDimmer::class.java,
        "rgbColour" to uk.me.cormack.lighting7.fixture.trait.WithColour::class.java,
        "uv" to uk.me.cormack.lighting7.fixture.trait.WithUv::class.java,
        "pan/tilt" to uk.me.cormack.lighting7.fixture.trait.WithPosition::class.java,
        "strobe" to uk.me.cormack.lighting7.fixture.trait.WithStrobe::class.java,
    )

    private val TEXT: String = """
# Project scripts

A project script is Kotlin stored with the show (the desk's Scripts view lists it) and run on demand with
run_script. It is code inside the desk, acting on the live rig directly — it does not go through the programmer, so
Clear does not take what it does, and nothing it sets is recorded. Prefer the show tools (set_programmer, apply_fx,
Looks, cues) for anything they can do; reach for a script for what they cannot: a timed sequence, logic over the
rig, a batch of FX applied by rule. The body runs top to bottom; the value of its last expression is the run's
result. No import lines: use the default imports, or fully qualified names.

Three types, each with its own scope:

## FX_APPLICATION — apply FX by rule (most scripts)

In scope:
- `fixture<T>(key)` and `group<T>(key)` — by the keys describe_rig lists, typed by the fixture class: the end of
  this guide names each class in the rig, fully qualified, and the traits it has. A group is typed by its members'
  class (or a trait they share, where the call needs only that).
- `effect(fxType, "param" to "value", …)` — an FX from the library by id (built-in or custom), parameters as strings.
- On a fixture or a group: `dimmerFx(fx, FxTiming(cycle))`, and likewise `colourFx`, `uvFx` and `positionFx` (each
  needs the class to have that trait); a group's also take `order = SpreadOrder.NORMAL` (or `UNIFIED`,
  `CENTRE_OUT`, `CENTRE_IN`, `REVERSE`, `PING_PONG`, `RANDOM`). `cycle` is in beats: `CycleLength.QUARTER_BEAT`,
  `HALF_BEAT`, `ONE_BEAT`, `TWO_BEATS`, `ONE_BAR`, `TWO_BARS`. `clearFx()` stops a fixture's or group's FX.
- `blendMode = BlendMode.OVERRIDE | ADDITIVE | MULTIPLY | MAX | MIN`, `speedMasterUuid = speedMasterUuidAt(2)`.
- Tempo: `bpm`, `setBpm(128.0)`, `tapTempo()`.
FX started this way are manual FX: get_current_state's active_fx lists them, and clear_fx stops them.

## GENERAL — everything, with coroutines

In scope: all of FX_APPLICATION's lookups, `effect`, tempo and `speedMasterUuidAt`, plus:
- Attribute values, through the class's traits: `dimmer` and `uv` (a Slider: `value: UByte?`, `fadeToValue(v,
  fadeMs)`), `rgbColour` (`value: java.awt.Color?`, `fadeToColour(c, fadeMs)`), `pan` and `tilt`
  (Sliders, 128 the centre), `strobe` (`fullOn()`, `strobe(intensity)`). On a group they set every member.
- `fxEngine` with the apply extensions: `fixture.applyDimmerFx(fxEngine, effect("Wave"), FxTiming(CycleLength.ONE_BAR))`.
- The body runs inside `runBlocking`, so suspending calls work; keep a script short-lived — run_script waits for it.
A value written here is the base under every FX, cue and programmer value, and stays until something writes over it.

## FX_DEFINITION — register FX types in code

In scope: `registerFx(FxRegistration(…))` and `fxRegistry` (read the library; `fxRegistry.getRegistration("Wave")`).
An `FxRegistration` takes `id`, `name`, `family` (intensity, colour, position, beam, controls), `outputType`
(`FxOutputType.SLIDER | COLOUR | POSITION`), `compatibleAttributes`, `parameters` (`ParameterInfo(name, type,
defaultValue, description)`), optional `fxMode`, `timingSource`, `defaultTiming`, and `factory = { params, _, _ -> Fx }`
returning an object implementing `Fx` (`name`, `outputType`, `calculate(phase, context): FxOutput`) or `StatefulFx`.
An FX registered this way lasts until the show is reloaded. For a custom FX the operator keeps, prefer
save_fx_script: it stores the FX with the show, the editor can show it, and get_fx_script_guide covers its body,
which is exactly `calculate`'s.

## Working loop

save_script compiles the script first and stores nothing if it does not compile, naming each error at
`line:column`. run_script runs a saved script (by name) or one given inline, and answers its status (`success`,
`compileError` or `exception`), messages and result. get_script reads the show's scripts.

## Examples

FX_APPLICATION — a sine chase on group FOH:
```kotlin
$FX_APPLICATION_EXAMPLE
```

GENERAL — fade two dimmers to different levels:
```kotlin
$GENERAL_EXAMPLE
```

FX_DEFINITION — an FX on for the first half of its cycle:
```kotlin
$FX_DEFINITION_EXAMPLE
```
""".trimIndent()
}
