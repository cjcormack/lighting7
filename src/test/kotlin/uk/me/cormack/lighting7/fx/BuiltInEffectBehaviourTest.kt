package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Behavioural coverage for the built-in effects **as the desk actually runs them** — compiled
 * from the `.fx.kts` resources into the [FxRegistry].
 *
 * This replaces the five `fx.effects` test classes retired with sweep item D7. Those tested
 * a parallel set of Kotlin effect classes that duplicated these resources and had drifted behind
 * them (27 classes against 28 files), and which nothing outside the test suite constructed. The
 * assertions are ported rather than rewritten, so a divergence between the two implementations
 * shows up here as a failure rather than as silence.
 *
 * Cost: one registry load, shared across every test in the class. The compiled-script cache in
 * [FxScriptCompiler] is process-wide, so this is a cache hit whenever `FxRegistryTest` or a
 * `Show` has already built the built-ins in the same JVM.
 */
class BuiltInEffectBehaviourTest {

    private companion object {
        /** Loaded once per JVM; [FxScriptCompiler]'s cache makes a second load nearly free. */
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInEffects(it) }
        }
    }

    private fun fx(id: String, vararg params: Pair<String, String>): Effect =
        registry.createEffect(id, params.toMap())

    private fun FxOutput.slider(): UByte = (this as FxOutput.Slider).value
    private fun FxOutput.colour(): Color = (this as FxOutput.Colour).color.color
    private fun FxOutput.extColour(): ExtendedColour = (this as FxOutput.Colour).color
    private fun FxOutput.position(): Pair<UByte, UByte> =
        (this as FxOutput.Position).let { it.pan to it.tilt }

    /**
     * Every declared parameter must reach the registry with its type, default and description
     * intact, and an effect created without explicit parameters must then honour those defaults.
     *
     * This is the regression guard for the frontmatter parser bug the ported assertions found:
     * `parseSimpleYaml`'s continuation branch tested indentation on the already-trimmed line, so
     * it never matched and every built-in parameter arrived as type `string` with an empty
     * default. Two things hid it. The script bodies ask for a type explicitly
     * (`params.ubyte("min")`) rather than dispatching on the declared one, so the wrong type was
     * inert; and every authoring surface sends a full parameter map, so the empty defaults were
     * never consulted. The retired `fx.effects` classes had real Kotlin defaults, which is why
     * the tests that used to cover this passed while the shipped implementation was broken.
     */
    @Test
    fun `declared parameter metadata survives frontmatter parsing`() {
        val sine = registry.getRegistration("SineWave")!!
        assertEquals(
            listOf("min" to "0", "max" to "255"),
            sine.parameters.map { it.name to it.defaultValue },
            "SineWave's declared defaults should reach the registry",
        )
        assertTrue(sine.parameters.all { it.type == "ubyte" }, "declared types should survive")
        assertTrue(
            sine.parameters.all { it.description.isNotBlank() },
            "declared descriptions should survive — the library API is the UI's only source for them",
        )

        // No parameter is blank for *any* built-in: a blank default is what the bug looked like.
        val blank = registry.allRegistrations
            .flatMap { reg -> reg.parameters.map { reg.id to it } }
            .filter { (_, p) -> p.type.isBlank() || p.defaultValue.isBlank() }
            .map { (id, p) -> "$id.${p.name}" }
        assertEquals(emptyList(), blank, "every built-in parameter should declare a type and default")

        // And the defaults are load-bearing: with no parameters at all, SineWave still spans 0-255.
        assertEquals(255.toUByte(), fx("SineWave").calculate(0.25).slider())
        assertEquals(0.toUByte(), fx("SineWave").calculate(0.75).slider())
    }

    /**
     * A stored parameter that is present but **blank** must read as "not set" and take the schema
     * default, not override it with zero.
     *
     * This is not hypothetical. While the parser was dropping defaults, `GET /fx/library` served
     * `defaultValue: ""`, the Add FX sheet seeded its form from that, and the empty map was
     * persisted. The desk's `cue_ad_hoc_effects` table holds exactly this today —
     * `Breathe {"min":"","max":""}` and `Circle {"panCenter":"", …}` — so without this rule the
     * parser fix would heal newly-added effects and leave those rows dead.
     */
    @Test
    fun `a blank stored parameter falls back to the declared default`() {
        val stored = registry.createEffect("Breathe", mapOf("min" to "", "max" to ""))
        val fresh = fx("Breathe")
        for (i in 0..20) {
            val phase = i / 20.0
            assertEquals(
                fresh.calculate(phase).slider(), stored.calculate(phase).slider(),
                "blank params should behave as absent at phase $phase",
            )
        }
        // And it is not merely equal-but-dead: the effect actually moves.
        assertTrue(
            (0..20).map { stored.calculate(it / 20.0).slider() }.distinct().size > 1,
            "a Breathe rehydrated from blank params should still breathe",
        )

        val circle = registry.createEffect(
            "Circle",
            mapOf("panCenter" to "", "tiltCenter" to "", "panRadius" to "", "tiltRadius" to ""),
        )
        assertEquals(
            fx("Circle").calculate(0.0).position(), circle.calculate(0.0).position(),
            "a Circle rehydrated from blank params should use its declared centre and radius",
        )
    }

    /**
     * The frontmatter parser's parameter-continuation branch must only claim the keys a parameter
     * has. It absorbs indented lines while a parameter entry is open, so without that restriction
     * an indented top-level key written *after* the parameter list is swallowed — and an effect
     * that silently loses `compatibleProperties` registers fine and then targets nothing.
     */
    @Test
    fun `an indented top-level key after the parameter list still parses`() {
        val (metadata, body) = FxFileLoader.parseFxFile(
            """
            /*---
            id: Probe
            name: Probe
            category: dimmer
            outputType: SLIDER
            parameters:
              - name: min
                type: ubyte
                default: "7"
                description: Minimum
              compatibleProperties: [dimmer, uv]
            ---*/
            FxOutput.Slider(0u)
            """.trimIndent(),
        )

        assertEquals(listOf("dimmer", "uv"), metadata.compatibleProperties)
        assertEquals(1, metadata.parameters.size, "the stray key must not become a parameter")
        assertEquals("7", metadata.parameters.single().default)
        assertEquals("ubyte", metadata.parameters.single().type)
        assertTrue(body.isNotBlank())
    }

    // --- Dimmer ---

    @Test
    fun `SineWave oscillates between min and max`() {
        val effect = fx("SineWave", "min" to "0", "max" to "255")

        // sin(0) = 0, normalised to 0.5 — starts at the midpoint rising
        assertEquals(127.toUByte(), effect.calculate(0.0).slider())
        // sin(pi/2) = 1
        assertEquals(255.toUByte(), effect.calculate(0.25).slider())
        // sin(pi) = 0
        assertEquals(127.toUByte(), effect.calculate(0.5).slider())
        // sin(3pi/2) = -1
        assertEquals(0.toUByte(), effect.calculate(0.75).slider())
    }

    @Test
    fun `SineWave respects min and max bounds`() {
        val effect = fx("SineWave", "min" to "50", "max" to "200")
        for (i in 0..100) {
            val phase = i / 100.0
            val value = effect.calculate(phase).slider()
            assertTrue(value >= 50u, "Value $value at phase $phase should be >= 50")
            assertTrue(value <= 200u, "Value $value at phase $phase should be <= 200")
        }
    }

    @Test
    fun `RampUp goes from min to max`() {
        val effect = fx("RampUp", "min" to "0", "max" to "255", "curve" to "LINEAR")
        assertEquals(0.toUByte(), effect.calculate(0.0).slider())
        assertEquals(127.toUByte(), effect.calculate(0.5).slider())
        assertEquals(255.toUByte(), effect.calculate(1.0).slider())
    }

    @Test
    fun `RampDown goes from max to min`() {
        val effect = fx("RampDown", "min" to "0", "max" to "255", "curve" to "LINEAR")
        assertEquals(255.toUByte(), effect.calculate(0.0).slider())
        assertEquals(127.toUByte(), effect.calculate(0.5).slider())
        assertEquals(0.toUByte(), effect.calculate(1.0).slider())
    }

    @Test
    fun `Triangle rises then falls`() {
        val effect = fx("Triangle", "min" to "0", "max" to "255", "curve" to "LINEAR")
        assertEquals(0.toUByte(), effect.calculate(0.0).slider())
        assertEquals(255.toUByte(), effect.calculate(0.5).slider())
        assertEquals(0.toUByte(), effect.calculate(1.0).slider())
    }

    @Test
    fun `Pulse follows attack-hold-release envelope`() {
        // attack 0-0.25, hold 0.25-0.75, release 0.75-1.0
        val effect = fx(
            "Pulse",
            "min" to "0", "max" to "255",
            "attackRatio" to "0.25", "holdRatio" to "0.5", "curve" to "LINEAR",
        )
        assertEquals(0.toUByte(), effect.calculate(0.0).slider())

        val duringAttack = effect.calculate(0.125).slider()
        assertTrue(duringAttack > 0u && duringAttack < 255u, "During attack should be rising, was $duringAttack")

        assertEquals(255.toUByte(), effect.calculate(0.5).slider())
        assertEquals(0.toUByte(), effect.calculate(1.0).slider())
    }

    @Test
    fun `SquareWave alternates between min and max`() {
        val effect = fx("SquareWave", "min" to "0", "max" to "255", "dutyCycle" to "0.5")
        assertEquals(255.toUByte(), effect.calculate(0.0).slider())
        assertEquals(255.toUByte(), effect.calculate(0.25).slider())
        assertEquals(255.toUByte(), effect.calculate(0.49).slider())
        assertEquals(0.toUByte(), effect.calculate(0.5).slider())
        assertEquals(0.toUByte(), effect.calculate(0.75).slider())
    }

    @Test
    fun `Strobe flashes on briefly`() {
        val effect = fx("Strobe", "offValue" to "0", "onValue" to "255", "onRatio" to "0.1")
        assertEquals(255.toUByte(), effect.calculate(0.0).slider())
        assertEquals(255.toUByte(), effect.calculate(0.05).slider())
        assertEquals(0.toUByte(), effect.calculate(0.1).slider())
        assertEquals(0.toUByte(), effect.calculate(0.5).slider())
        assertEquals(0.toUByte(), effect.calculate(0.99).slider())
    }

    @Test
    fun `Breathe stays within bounds`() {
        val effect = fx("Breathe", "min" to "0", "max" to "255")
        for (i in 0..100) {
            val value = effect.calculate(i / 100.0).slider()
            assertTrue(value <= 255u, "Breathe value $value should be <= 255")
        }
    }

    @Test
    fun `Flicker produces varying values within range`() {
        val effect = fx("Flicker", "min" to "50", "max" to "200")
        val values = mutableSetOf<UByte>()
        for (i in 0..100) {
            val phase = i / 100.0
            val value = effect.calculate(phase).slider()
            assertTrue(value >= 50u, "Flicker value $value at phase $phase should be >= 50")
            assertTrue(value <= 200u, "Flicker value $value at phase $phase should be <= 200")
            values.add(value)
        }
        assertTrue(values.size > 1, "Flicker should produce varying values")
    }

    @Test
    fun `StaticValue auto-windows for distribution`() {
        val effect = fx("StaticValue", "value" to "200")
        // Member 0 of a 4-element LINEAR distribution; its window is [0, 1/4)
        val context = EffectContext(
            groupSize = 4, memberIndex = 0, distributionOffset = 0.0,
            hasDistributionSpread = true, numDistinctSlots = 4,
        )

        assertEquals(200.toUByte(), effect.calculate(0.0, context).slider())
        assertEquals(200.toUByte(), effect.calculate(0.2, context).slider())
        assertEquals(0.toUByte(), effect.calculate(0.25, context).slider())
        assertEquals(0.toUByte(), effect.calculate(0.5, context).slider())
        assertEquals(0.toUByte(), effect.calculate(0.99, context).slider())
    }

    @Test
    fun `StaticValue with no distribution ignores phase`() {
        val effect = fx("StaticValue", "value" to "200")
        assertEquals(200.toUByte(), effect.calculate(0.0).slider())
        assertEquals(200.toUByte(), effect.calculate(0.5).slider())
        assertEquals(200.toUByte(), effect.calculate(1.0).slider())
    }

    @Test
    fun `StaticValue declares step timing by default`() {
        assertTrue(
            fx("StaticValue").defaultStepTiming,
            "StaticValue drives one element per slot, so a new instance should step",
        )
        assertTrue(
            !fx("SineWave").defaultStepTiming,
            "SineWave's beat division is the whole cycle, so a new instance should not step",
        )
    }

    // --- Colour ---

    @Test
    fun `RainbowCycle produces full hue rotation`() {
        val effect = fx("RainbowCycle", "saturation" to "1.0", "brightness" to "1.0")

        val atZero = effect.calculate(0.0).colour()
        assertEquals(255, atZero.red)
        assertEquals(0, atZero.blue)

        val atThird = effect.calculate(0.333).colour()
        assertTrue(atThird.green > atThird.red, "At 1/3, green should dominate red")
        assertTrue(atThird.green > atThird.blue, "At 1/3, green should dominate blue")

        val atTwoThirds = effect.calculate(0.666).colour()
        assertTrue(atTwoThirds.blue > atTwoThirds.red, "At 2/3, blue should dominate red")
        assertTrue(atTwoThirds.blue > atTwoThirds.green, "At 2/3, blue should dominate green")
    }

    @Test
    fun `ColourCycle steps through colours`() {
        val effect = fx("ColourCycle", "colours" to "#ff0000,#00ff00,#0000ff", "fadeRatio" to "0.0")
        assertEquals(Color.RED, effect.calculate(0.0).colour())
        assertEquals(Color.GREEN, effect.calculate(0.4).colour())
        assertEquals(Color.BLUE, effect.calculate(0.7).colour())
    }

    @Test
    fun `ColourCycle with fade produces intermediate colours`() {
        val effect = fx("ColourCycle", "colours" to "#ff0000,#0000ff", "fadeRatio" to "1.0")
        val midFade = effect.calculate(0.25).colour()
        assertTrue(midFade.red > 0, "Should have some red, was $midFade")
        assertTrue(midFade.blue > 0, "Should have some blue, was $midFade")
    }

    @Test
    fun `ColourStrobe alternates between on and off colours`() {
        val effect = fx(
            "ColourStrobe",
            "onColour" to "#ffffff", "offColour" to "#000000", "onRatio" to "0.5",
        )
        assertEquals(Color.WHITE, effect.calculate(0.0).colour())
        assertEquals(Color.WHITE, effect.calculate(0.25).colour())
        assertEquals(Color.BLACK, effect.calculate(0.5).colour())
        assertEquals(Color.BLACK, effect.calculate(0.75).colour())
    }

    @Test
    fun `ColourPulse oscillates using a sine wave`() {
        val effect = fx("ColourPulse", "colourA" to "#ff0000", "colourB" to "#0000ff")

        // sin(0) = 0 → ratio 0.5, so phase 0 is midway between the two colours
        val atStart = effect.calculate(0.0).colour()
        assertTrue(atStart.red in 1..254, "Phase 0 should be between colours, was $atStart")
        assertTrue(atStart.blue in 1..254, "Phase 0 should be between colours, was $atStart")

        val atQuarter = effect.calculate(0.25).colour()
        assertEquals(0, atQuarter.red)
        assertEquals(255, atQuarter.blue)

        val atThreeQuarters = effect.calculate(0.75).colour()
        assertEquals(255, atThreeQuarters.red)
        assertEquals(0, atThreeQuarters.blue)
    }

    @Test
    fun `ColourFade produces linear transition`() {
        val effect = fx(
            "ColourFade",
            "fromColour" to "#000000", "toColour" to "#ffffff", "pingPong" to "false",
        )

        val atStart = effect.calculate(0.0).colour()
        assertEquals(0, atStart.red)
        assertEquals(0, atStart.green)
        assertEquals(0, atStart.blue)

        val atEnd = effect.calculate(1.0).colour()
        assertEquals(255, atEnd.red)
        assertEquals(255, atEnd.green)
        assertEquals(255, atEnd.blue)

        val atMid = effect.calculate(0.5).colour()
        assertTrue(atMid.red in 120..135, "Mid should be around 128, was ${atMid.red}")
    }

    @Test
    fun `ColourFade with pingPong returns to start`() {
        val effect = fx(
            "ColourFade",
            "fromColour" to "#000000", "toColour" to "#ffffff", "pingPong" to "true",
        )
        val atStart = effect.calculate(0.0).colour()
        val atEnd = effect.calculate(1.0).colour()
        assertEquals(atStart.red, atEnd.red)
        assertEquals(atStart.green, atEnd.green)
        assertEquals(atStart.blue, atEnd.blue)
    }

    @Test
    fun `ColourFade blends white amber and UV channels`() {
        val effect = fx(
            "ColourFade",
            "fromColour" to "red;w0;a100;uv200",
            "toColour" to "blue;w255;a0;uv0",
            "pingPong" to "false",
        )
        val mid = effect.calculate(0.5).extColour()
        assertTrue(mid.white.toInt() in 120..135, "White should be ~128, was ${mid.white}")
        assertTrue(mid.amber.toInt() in 45..55, "Amber should be ~50, was ${mid.amber}")
        assertTrue(mid.uv.toInt() in 95..105, "UV should be ~100, was ${mid.uv}")
    }

    @Test
    fun `StaticColour always returns the same colour`() {
        val effect = fx("StaticColour", "colour" to "#00ffff")
        assertEquals(Color.CYAN, effect.calculate(0.0).colour())
        assertEquals(Color.CYAN, effect.calculate(0.5).colour())
        assertEquals(Color.CYAN, effect.calculate(1.0).colour())
    }

    @Test
    fun `StaticColour preserves extended channels`() {
        val output = fx("StaticColour", "colour" to "red;w128;a64;uv32").calculate(0.5).extColour()
        assertEquals(Color.RED, output.color)
        assertEquals(128u.toUByte(), output.white)
        assertEquals(64u.toUByte(), output.amber)
        assertEquals(32u.toUByte(), output.uv)
    }

    @Test
    fun `StaticColour auto-windows for distribution`() {
        val effect = fx("StaticColour", "colour" to "#ff0000")
        // Member 0 of a 12-element LINEAR distribution; its window is [0, 1/12)
        val context = EffectContext(
            groupSize = 12, memberIndex = 0, distributionOffset = 0.0,
            hasDistributionSpread = true, numDistinctSlots = 12,
        )
        assertEquals(Color.RED, effect.calculate(0.0, context).colour())
        assertEquals(Color.RED, effect.calculate(0.04, context).colour())
        assertEquals(Color.BLACK, effect.calculate(0.1, context).colour())
        assertEquals(Color.BLACK, effect.calculate(0.5, context).colour())
        assertEquals(Color.BLACK, effect.calculate(0.99, context).colour())
    }

    @Test
    fun `StaticColour with UNIFIED distribution always returns the colour`() {
        val effect = fx("StaticColour", "colour" to "#ff0000")
        val context = EffectContext(groupSize = 12, memberIndex = 5, hasDistributionSpread = false)
        assertEquals(Color.RED, effect.calculate(0.0, context).colour())
        assertEquals(Color.RED, effect.calculate(0.5, context).colour())
        assertEquals(Color.RED, effect.calculate(0.99, context).colour())
    }

    @Test
    fun `StaticColour chase fires elements in forward order`() {
        val effect = fx("StaticColour", "colour" to "#ff0000")
        val groupSize = 4

        fun contextFor(idx: Int) = EffectContext(
            groupSize = groupSize, memberIndex = idx,
            distributionOffset = idx.toDouble() / groupSize,
            hasDistributionSpread = true, numDistinctSlots = groupSize,
        )

        // The engine hands each member its own shifted phase; mirror that here.
        fun shiftedPhase(basePhase: Double, idx: Int) =
            (basePhase - idx.toDouble() / groupSize + 1.0) % 1.0

        // At each base phase exactly one member's window is open, in index order.
        val litBy = mapOf(0.1 to 0, 0.3 to 1, 0.6 to 2, 0.8 to 3)
        for ((basePhase, expectedLit) in litBy) {
            for (idx in 0 until groupSize) {
                val actual = effect.calculate(shiftedPhase(basePhase, idx), contextFor(idx)).colour()
                val expected = if (idx == expectedLit) Color.RED else Color.BLACK
                assertEquals(expected, actual, "base phase $basePhase, member $idx")
            }
        }
    }

    @Test
    fun `ColourFlicker produces variation around the base colour`() {
        val effect = fx("ColourFlicker", "baseColour" to "#808080", "variation" to "50")
        val colours = mutableSetOf<Color>()
        for (i in 0..100) {
            val colour = effect.calculate(i / 100.0).colour()
            assertTrue(colour.red in 78..178, "Red ${colour.red} should be within 50 of 128")
            assertTrue(colour.green in 78..178, "Green ${colour.green} should be within 50 of 128")
            assertTrue(colour.blue in 78..178, "Blue ${colour.blue} should be within 50 of 128")
            colours.add(colour)
        }
        assertTrue(colours.size > 1, "ColourFlicker should produce varying colours")
    }

    // --- Position ---

    @Test
    fun `Circle produces circular movement`() {
        val effect = fx(
            "Circle",
            "panCenter" to "128", "tiltCenter" to "128", "panRadius" to "50", "tiltRadius" to "50",
        )
        // cos(0) = 1, sin(0) = 0
        assertEquals(178.toUByte() to 128.toUByte(), effect.calculate(0.0).position())
        // cos(pi/2) = 0, sin(pi/2) = 1
        assertEquals(128.toUByte() to 178.toUByte(), effect.calculate(0.25).position())
        // cos(pi) = -1, sin(pi) = 0
        assertEquals(78.toUByte() to 128.toUByte(), effect.calculate(0.5).position())
    }

    @Test
    fun `Circle stays within valid DMX range`() {
        // Centre plus radius overruns 255 — the effect must clamp, not wrap.
        val effect = fx(
            "Circle",
            "panCenter" to "250", "tiltCenter" to "250", "panRadius" to "50", "tiltRadius" to "50",
        )
        for (i in 0..100) {
            val (pan, tilt) = effect.calculate(i / 100.0).position()
            assertTrue(pan <= 255u, "Pan should not exceed 255")
            assertTrue(tilt <= 255u, "Tilt should not exceed 255")
        }
    }

    @Test
    fun `Figure8 sweeps both axes`() {
        val effect = fx(
            "Figure8",
            "panCenter" to "128", "tiltCenter" to "128", "panRadius" to "50", "tiltRadius" to "30",
        )
        val positions = (0..20).map { effect.calculate(it / 20.0).position() }
        val pans = positions.map { it.first.toInt() }
        val tilts = positions.map { it.second.toInt() }
        assertTrue(pans.max() - pans.min() > 50, "Pan should have significant range")
        assertTrue(tilts.max() - tilts.min() > 30, "Tilt should have significant range")
    }

    @Test
    fun `Sweep moves from start to end without pingPong`() {
        val effect = fx(
            "Sweep",
            "startPan" to "0", "startTilt" to "0", "endPan" to "255", "endTilt" to "255",
            "curve" to EasingCurve.LINEAR.name, "pingPong" to "false",
        )
        assertEquals(0.toUByte() to 0.toUByte(), effect.calculate(0.0).position())
        assertEquals(127.toUByte() to 127.toUByte(), effect.calculate(0.5).position())
        assertEquals(255.toUByte() to 255.toUByte(), effect.calculate(1.0).position())
    }

    @Test
    fun `Sweep with pingPong returns to start`() {
        val effect = fx(
            "Sweep",
            "startPan" to "0", "startTilt" to "0", "endPan" to "255", "endTilt" to "255",
            "curve" to EasingCurve.LINEAR.name, "pingPong" to "true",
        )
        assertEquals(effect.calculate(0.0).position(), effect.calculate(1.0).position())
        assertEquals(255.toUByte() to 255.toUByte(), effect.calculate(0.5).position())
    }

    @Test
    fun `PanSweep only changes pan`() {
        val effect = fx(
            "PanSweep",
            "startPan" to "50", "endPan" to "200", "tilt" to "100",
            "curve" to EasingCurve.LINEAR.name, "pingPong" to "false",
        )
        assertEquals(50.toUByte() to 100.toUByte(), effect.calculate(0.0).position())
        assertEquals(200.toUByte() to 100.toUByte(), effect.calculate(1.0).position())
    }

    @Test
    fun `TiltSweep only changes tilt`() {
        val effect = fx(
            "TiltSweep",
            "startTilt" to "50", "endTilt" to "200", "pan" to "100",
            "curve" to EasingCurve.LINEAR.name, "pingPong" to "false",
        )
        assertEquals(100.toUByte() to 50.toUByte(), effect.calculate(0.0).position())
        assertEquals(100.toUByte() to 200.toUByte(), effect.calculate(1.0).position())
    }

    @Test
    fun `StaticPosition never changes`() {
        val effect = fx("StaticPosition", "pan" to "100", "tilt" to "150")
        assertEquals(100.toUByte() to 150.toUByte(), effect.calculate(0.0).position())
        assertEquals(100.toUByte() to 150.toUByte(), effect.calculate(0.5).position())
        assertEquals(100.toUByte() to 150.toUByte(), effect.calculate(1.0).position())
    }

    @Test
    fun `StaticPosition auto-windows for distribution`() {
        val effect = fx("StaticPosition", "pan" to "50", "tilt" to "200")
        val context = EffectContext(
            groupSize = 4, memberIndex = 0, distributionOffset = 0.0,
            hasDistributionSpread = true, numDistinctSlots = 4,
        )
        assertEquals(50.toUByte() to 200.toUByte(), effect.calculate(0.0, context).position())
        assertEquals(50.toUByte() to 200.toUByte(), effect.calculate(0.2, context).position())
        // Outside the window a position effect parks at centre rather than at zero.
        assertEquals(128.toUByte() to 128.toUByte(), effect.calculate(0.25, context).position())
        assertEquals(128.toUByte() to 128.toUByte(), effect.calculate(0.5, context).position())
        assertEquals(128.toUByte() to 128.toUByte(), effect.calculate(0.99, context).position())
    }

    @Test
    fun `RandomPosition stays within range`() {
        val effect = fx(
            "RandomPosition",
            "panCenter" to "128", "tiltCenter" to "128", "panRange" to "50", "tiltRange" to "50",
        )
        for (i in 0..100) {
            val (pan, tilt) = effect.calculate(i / 100.0).position()
            assertTrue(pan >= 78u && pan <= 178u, "Pan $pan should be within [78, 178]")
            assertTrue(tilt >= 78u && tilt <= 178u, "Tilt $tilt should be within [78, 178]")
        }
    }

    // --- Composite ---

    @Test
    fun `LightningStrike computes both SLIDER and COLOUR entries`() {
        val effect = fx("LightningStrike") as CompositeEffect
        val outputs = effect.calculateComposite(0.0)
        assertNotNull(outputs[FxOutputType.SLIDER], "Should produce SLIDER output")
        assertNotNull(outputs[FxOutputType.COLOUR], "Should produce COLOUR output")
    }

    @Test
    fun `LightningStrike flashes then decays then goes dark`() {
        val effect = fx("LightningStrike", "maxBrightness" to "255", "minBrightness" to "10")
            as CompositeEffect

        val flash = effect.calculateComposite(0.02)[FxOutputType.SLIDER]!!.slider()
        val decay = effect.calculateComposite(0.15)[FxOutputType.SLIDER]!!.slider()
        val dark = effect.calculateComposite(0.5)[FxOutputType.SLIDER]!!.slider()

        assertEquals(255.toUByte(), flash, "Flash phase should reach maxBrightness")
        assertTrue(decay < flash, "Decay brightness $decay should be below flash $flash")
        assertEquals(10.toUByte(), dark, "Dark phase should sit at minBrightness")
    }

    /**
     * Guard for the primary-output-only contract (sweep item A4). The engine's only entry point
     * into an effect is [Effect.calculate]; one [FxInstance] drives one [FxTarget], so a
     * composite's non-primary entries are computed and discarded. If a future change wants the
     * COLOUR half of a lightning strike to reach a fixture, it needs secondary targets on
     * `FxInstance` *and* an authoring surface that can name them — not a quietly reinstated
     * branch in the engine.
     */
    @Test
    fun `composite calculate yields only the primary output`() {
        val effect = fx("LightningStrike")
        assertEquals(FxOutputType.SLIDER, effect.outputType)

        val output = effect.calculate(0.02)
        assertTrue(output is FxOutput.Slider, "calculate() must yield the primary SLIDER entry")
        assertEquals(255.toUByte(), output.slider())

        // The colour half is still computed — it just has nowhere to go.
        assertNotNull((effect as CompositeEffect).calculateComposite(0.02)[FxOutputType.COLOUR])
    }

    @Test
    fun `LightningStrike colour cools from flash to decay`() {
        val effect = fx("LightningStrike") as CompositeEffect
        val flash = effect.calculateComposite(0.02)[FxOutputType.COLOUR]!!.colour()
        val decay = effect.calculateComposite(0.25)[FxOutputType.COLOUR]!!.colour()
        assertTrue(
            flash.red > decay.red,
            "Flash red ${flash.red} should exceed the bluer decay red ${decay.red}",
        )
    }

    @Test
    fun `LightningStrike respects a custom flash colour`() {
        val effect = fx("LightningStrike", "flashColour" to "#ff0000") as CompositeEffect
        val colour = effect.calculateComposite(0.02)[FxOutputType.COLOUR]!!.colour()
        assertEquals(Color.RED, colour)
    }

    // --- Stateful ---

    private fun tick(tickNumber: Long, timestampMs: Long = tickNumber * 20L) =
        MasterClock.ClockTick(
            tickNumber = tickNumber,
            beatNumber = tickNumber / MasterClock.TICKS_PER_BEAT,
            tickInBeat = (tickNumber % MasterClock.TICKS_PER_BEAT).toInt(),
            phase = (tickNumber % MasterClock.TICKS_PER_BEAT).toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = timestampMs,
        )

    @Test
    fun `CandleFlicker stays within bounds over many ticks`() {
        val effect = fx(
            "CandleFlicker",
            "baseLevel" to "180", "min" to "100", "max" to "230",
        ) as StatefulEffect
        effect.initialize()

        for (i in 0L..500L) {
            val value = effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE).slider()
            assertTrue(value >= 100u, "Tick $i: value $value below min 100")
            assertTrue(value <= 230u, "Tick $i: value $value above max 230")
        }
    }

    @Test
    fun `CandleFlicker respects custom bounds`() {
        val effect = fx(
            "CandleFlicker",
            "baseLevel" to "50", "min" to "20", "max" to "80",
        ) as StatefulEffect
        effect.initialize()

        for (i in 0L..200L) {
            val value = effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE).slider()
            assertTrue(value >= 20u, "Tick $i: value $value below min 20")
            assertTrue(value <= 80u, "Tick $i: value $value above max 80")
        }
    }

    @Test
    fun `CandleFlicker initialize resets towards the base level`() {
        val effect = fx(
            "CandleFlicker",
            "baseLevel" to "180", "min" to "100", "max" to "230",
        ) as StatefulEffect
        effect.initialize()
        for (i in 0L..100L) {
            effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE)
        }

        effect.initialize()
        val value = effect.calculateStateful(tick(0), deltaMs = 0, EffectContext.SINGLE).slider()
        assertTrue(
            value >= 150u && value <= 210u,
            "After initialize, first value $value should be near baseLevel 180",
        )
    }

    @Test
    fun `CandleFlicker produces varying output over time`() {
        val effect = fx(
            "CandleFlicker",
            "baseLevel" to "180", "min" to "100", "max" to "230", "smoothing" to "0.5",
        ) as StatefulEffect
        effect.initialize()

        val values = mutableSetOf<UByte>()
        for (i in 0L..200L) {
            values.add(effect.calculateStateful(tick(i), deltaMs = 20, EffectContext.SINGLE).slider())
        }
        assertTrue(values.size > 3, "Expected variation, got only ${values.size} distinct values: $values")
    }

    @Test
    fun `stateful fallback calculate returns a neutral value`() {
        // Nothing calls this in the engine — it exists so a caller without tick context gets a
        // defined answer rather than the effect's uninitialised state.
        assertEquals(0.toUByte(), fx("CandleFlicker").calculate(0.5).slider())
    }
}
