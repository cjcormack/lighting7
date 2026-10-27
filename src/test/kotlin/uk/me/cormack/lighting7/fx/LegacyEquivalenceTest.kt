package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.sync.LegacyNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * fx-settings plan P3: no old FX is mapped onto its new form until the old script and the new give
 * the same output for every phase, parameter, Direction and spread. Each old script is compiled from
 * `src/test/resources/fx/legacy/`, exactly as it shipped, and run beside its new form on the
 * parameters [LegacyNames.consolidatedParameters] (session 2's level waves) or [LegacyNames.absorbFx]
 * (session 3's movements, renamed parameters and Colour Cycle's `fadeRatio`) writes for it — what
 * database steps 7 and 8, the importer, MCP and a script's `effect()` all store or run.
 *
 * The phases are each head's as the engine hands them over: the clock at 1/96 of a cycle, less the
 * head's offset for a spread over four heads, read in each Direction on an even and an odd cycle
 * ([FxDirection.apply]). Both FX are pure functions of that phase, so this is every phase a rig
 * would ask either for; a few contexts stand in for the element modes, which reach an FX only
 * through its context.
 */
class LegacyEquivalenceTest {

    private companion object {
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
        val compiler = FxScriptCompiler()

        /** The old ids, each with its file under `fx/legacy/`. */
        val LEGACY = listOf(
            "SineWave", "RampUp", "RampDown", "Ramp", "SquareWave", "Pulse",
            "DimmerStrobe", "ZoomWave", "IrisPulse", "FrostPulse", "FocusPull",
        )

        val PHASES: List<Double> = (0..96).map { it / 96.0 } + listOf(1e-12, 0.1 - 1e-12, 0.5 - 1e-12, 1.0 - 1e-12)
        val SPREADS = listOf(0.0, 0.5, 1.0, 4.0 / 3.0, 2.0)
        const val HEADS = 4
        val CONTEXTS = listOf(
            FxContext.SINGLE,
            FxContext(groupSize = 4, memberIndex = 2, distributionOffset = 0.5, hasDistributionSpread = true),
            FxContext(groupSize = 12, memberIndex = 7, numDistinctSlots = 3),
        )
    }

    /** One old script compiled as it shipped, as the factory its registration had. */
    private fun legacyFactory(id: String): FxFactory {
        val text = checkNotNull(javaClass.classLoader.getResource("fx/legacy/$id.fx.kts")) { "fx/legacy/$id.fx.kts is missing" }.readText()
        val (metadata, body) = FxFileLoader.parseFxFile(text)
        assertEquals(id, metadata.id)
        val mode = FxMode.valueOf(metadata.fxMode)
        val compiled = compiler.compile(body, mode)
        assertTrue(compiled.isSuccess, "fx/legacy/$id compiles: ${compiled.diagnostics}")
        return ScriptFxAdapter.createFactory(
            compiled, metadata.parameters.map(FxFileParameter::toParameterInfo), metadata.name, FxOutputType.valueOf(metadata.outputType),
        )
    }

    private fun legacy(id: String): Fx = legacyFactory(id)(emptyMap(), null, null)

    /** The parameter maps each row is checked over: the defaults, then a grid of its own parameters. */
    private fun grids(id: String): List<Map<String, String>> {
        val (low, high) = when (id) {
            "DimmerStrobe" -> "offValue" to "onValue"
            "FocusPull" -> "start" to "end"
            else -> "min" to "max"
        }
        val levels = listOf("0" to "255", "40" to "200", "200" to "40", "255" to "0", "17" to "17", "300" to "-4", "" to "", "x" to "y")
        val ratios = listOf("0", "0.05", "0.25", "0.5", "0.9", "1", "1.5", "", "abc")
        val curves = EasingCurve.entries.map { it.name } + listOf("", "not-a-curve", "quad_out")
        val out = ArrayList<Map<String, String>>()
        out += emptyMap()
        for ((l, h) in levels) out += mapOf(low to l, high to h)
        when (id) {
            "SquareWave" -> ratios.forEach { out += mapOf(low to "30", high to "220", "dutyCycle" to it) }
            "DimmerStrobe" -> ratios.forEach { out += mapOf(low to "30", high to "220", "onRatio" to it) }
            "Pulse", "IrisPulse", "FrostPulse" -> {
                for (a in ratios) for (h in listOf("0", "0.3", "0.6", "")) {
                    out += mapOf(low to "10", high to "240", "attackRatio" to a, "holdRatio" to h)
                }
                curves.forEach { out += mapOf(low to "10", high to "240", "curve" to it) }
            }
            "RampUp", "RampDown", "Ramp", "FocusPull" -> curves.forEach { out += mapOf(low to "10", high to "240", "curve" to it) }
        }
        // A parameter the old FX never read changes nothing on either.
        out += mapOf(low to "5", "width" to "0.9", "shape" to "SQUARE")
        return out
    }

    @Test
    fun `every level wave runs as Wave, to the DMX step`() {
        val failures = ArrayList<String>()
        for (id in LEGACY) {
            val oldFactory = legacyFactory(id)
            for (params in grids(id)) {
                val waveParams = checkNotNull(LegacyNames.consolidatedParameters(id, params)) { "$id has no Wave row" }
                val old = oldFactory(params, null, null)
                val wave = registry.createFx(LegacyNames.WAVE, waveParams)
                for (direction in FxDirection.entries) for (cycle in 0L..1L) for (spread in SPREADS) {
                    for (head in 0 until HEADS) for (phase in PHASES) for (context in CONTEXTS) {
                        val member = direction.apply(cyclePhase(phase - spread * head / HEADS), cycle)
                        val expected = old.calculate(member, context)
                        val actual = wave.calculate(member, context)
                        if (expected != actual && failures.size < 20) {
                            failures += "$id $params → $waveParams at $direction cycle $cycle spread $spread head $head " +
                                "phase $phase: old $expected, Wave $actual"
                        }
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `Wave's presets start each beam wave as its old FX did`() {
        val wave = checkNotNull(registry.getRegistration(LegacyNames.WAVE))
        for ((preset, old) in listOf("Zoom Wave" to "ZoomWave", "Iris Pulse" to "IrisPulse", "Frost Pulse" to "FrostPulse", "Focus Pull" to "FocusPull")) {
            val p = wave.presets.single { it.name == preset }
            val started = registry.withAttributeDefaults(LegacyNames.WAVE, p.parameters, p.attribute)
            val oldFx = legacy(old)
            val waveFx = registry.createFx(LegacyNames.WAVE, started)
            for (phase in PHASES) assertEquals(oldFx.calculate(phase), waveFx.calculate(phase), "$preset at $phase")
        }
        val focus = wave.presets.single { it.name == "Focus Pull" }
        assertEquals(2.0, focus.cycle)
        assertEquals(FxDirection.BOUNCE.name, focus.direction)
        assertEquals(LegacyNames.consolidatedTiming("FocusPull"), 2.0 to FxDirection.BOUNCE.name)
    }

    @Test
    fun `a Wave on a beam attribute with no levels starts where its old FX did`() {
        for ((attribute, old) in listOf("zoom" to "ZoomWave", "iris" to "IrisPulse", "frost" to "FrostPulse")) {
            val shape = if (old == "ZoomWave") "SINE" else "PULSE"
            val curve = if (old == "ZoomWave") emptyMap() else mapOf("curve" to "QUAD_OUT")
            val started = registry.withAttributeDefaults(LegacyNames.WAVE, mapOf("shape" to shape) + curve, attribute)
            val oldFx = legacy(old)
            val waveFx = registry.createFx(LegacyNames.WAVE, started)
            for (phase in PHASES) assertEquals(oldFx.calculate(phase), waveFx.calculate(phase), "Wave on $attribute at $phase")
        }
    }

    // ─── Session 3: the movements, the renamed parameters and Colour Cycle ──────────────────────────

    /** What step 8 and the importer store for an old FX: [LegacyNames.absorbFx] over a bare row. */
    private fun absorbed(fxType: String, parameters: Map<String, String>): Pair<String, Map<String, String>> {
        val stored = LegacyNames.StoredFx(
            fxType = fxType, family = "", cycle = 4.0, order = "NORMAL", phaseOffset = 0.0,
            parameters = parameters, direction = "FORWARD", runs = null, spread = 1.0,
        )
        val fx = LegacyNames.absorbFx(stored) { null }?.fx ?: stored
        // What a script or MCP runs is what is stored.
        assertEquals(fx.fxType to fx.parameters, LegacyNames.runnableFx(fxType, parameters))
        return fx.fxType to fx.parameters
    }

    /** Every phase a head is asked for, old against new, the first failures in words. */
    private fun compare(oldId: String, grids: List<Map<String, String>>, failures: MutableList<String>) {
        val oldFactory = legacyFactory(oldId)
        for (params in grids) {
            val (newId, newParams) = absorbed(oldId, params)
            val old = oldFactory(params, null, null)
            val new = registry.createFx(newId, newParams)
            for (direction in FxDirection.entries) for (cycle in 0L..1L) for (spread in SPREADS) {
                for (head in 0 until HEADS) for (phase in PHASES) for (context in CONTEXTS) {
                    val member = direction.apply(cyclePhase(phase - spread * head / HEADS), cycle)
                    val expected = old.calculate(member, context)
                    val actual = new.calculate(member, context)
                    if (expected != actual && failures.size < 20) {
                        failures += "$oldId $params → $newId $newParams at $direction cycle $cycle spread $spread head $head " +
                            "phase $phase: old $expected, new $actual"
                    }
                }
            }
        }
    }

    private val BYTES = listOf("0", "17", "128", "200", "255", "300", "-4", "", "x")
    private val CURVES = EasingCurve.entries.map { it.name } + listOf("", "not-a-curve", "sine_in_out")

    /** One parameter at a time over [values], the rest at their defaults, and every parameter at once. */
    private fun oneAtATime(names: List<String>, values: List<String>): List<Map<String, String>> =
        listOf(emptyMap<String, String>()) +
            names.flatMap { name -> values.map { mapOf(name to it) } } +
            values.map { v -> names.associateWith { v } } +
            listOf(names.mapIndexed { i, name -> name to BYTES[i % BYTES.size] }.toMap() + ("stale" to "1"))

    @Test
    fun `every movement runs as Path, to the DMX step`() {
        val failures = ArrayList<String>()
        compare("Circle", oneAtATime(listOf("panCentre", "tiltCentre", "panRadius", "tiltRadius"), BYTES), failures)
        compare("Figure8", oneAtATime(listOf("panCentre", "tiltCentre", "panRadius", "tiltRadius"), BYTES), failures)
        compare("RandomPosition", oneAtATime(listOf("panCentre", "tiltCentre", "panRange", "tiltRange"), BYTES), failures)
        val sweep = oneAtATime(listOf("startPan", "startTilt", "endPan", "endTilt"), BYTES)
        compare("Sweep", sweep + CURVES.map { mapOf("startPan" to "10", "endPan" to "240", "endTilt" to "30", "curve" to it) }, failures)
        compare("PanSweep", oneAtATime(listOf("startPan", "endPan", "tilt"), BYTES) + CURVES.map { mapOf("startPan" to "10", "curve" to it) }, failures)
        compare("TiltSweep", oneAtATime(listOf("startTilt", "endTilt", "pan"), BYTES) + CURVES.map { mapOf("endTilt" to "250", "curve" to it) }, failures)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `the renamed parameters run as they did, to the DMX step`() {
        val failures = ArrayList<String>()
        compare("Flicker", oneAtATime(listOf("min", "max"), BYTES), failures)
        compare("LightningStrike", oneAtATime(listOf("minBrightness", "maxBrightness"), BYTES), failures)
        val ratios = listOf("0", "0.05", "0.5", "1", "1.5", "-0.2", "", "abc")
        compare(
            "ColourStrobe",
            ratios.map { mapOf("onRatio" to it, "onColour" to "#ff8000;w40", "offColour" to "#000010") } + emptyMap(),
            failures,
        )
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `Colour Cycle's fadeRatio runs as between and fade, to the DMX step`() {
        val failures = ArrayList<String>()
        val lists = listOf("#ff0000,#00ff00,#0000ff", "#102030,#f0e0d0", "#ffffff", "", "#ff0000;w128,#00ff00;a40,#0000ff;uv9", "red,blue")
        val ratios = listOf("0", "0.0", "0.25", "0.5", "0.9", "1", "1.5", "-0.5", "", "abc", "NaN")
        compare("ColourCycle", lists.flatMap { colours -> ratios.map { mapOf("colours" to colours, "fadeRatio" to it) } + mapOf("colours" to colours) }, failures)
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertEquals(mapOf("colours" to "#f00,#00f", "between" to "CUT"), absorbed("ColourCycle", mapOf("colours" to "#f00,#00f", "fadeRatio" to "0")).second)
        assertEquals(mapOf("between" to "FADE", "fade" to "0.5"), absorbed("ColourCycle", mapOf("fadeRatio" to "")).second)
        assertEquals(mapOf("between" to "FADE", "fade" to "0.25"), absorbed("ColourCycle", mapOf("fadeRatio" to "0.25")).second)
    }

    private fun outcome(block: () -> FxOutput): String =
        runCatching(block).fold({ it.toString() }, { "throws ${it::class.simpleName}: ${it.message}" })

    @Test
    fun `the stateful flickers run as they did, tick for tick`() {
        val failures = ArrayList<String>()
        val levels = BYTES
        val cases = listOf(
            "CandleFlicker" to oneAtATime(listOf("baseLevel", "min", "max"), levels) +
                listOf("0", "0.5", "0.99", "1.5", "", "abc").map { mapOf("smoothing" to it) },
            "FluorescentFlicker" to oneAtATime(listOf("offLevel", "onLevel"), levels) +
                listOf("0", "100", "800", "5000", "", "abc").map { mapOf("flickerDurationMs" to it) } +
                listOf("0", "0.05", "0.3", "0.95", "2", "", "abc").map { mapOf("flickerSpeed" to it) },
        )
        for ((id, grids) in cases) {
            val oldFactory = legacyFactory(id)
            for (params in grids) {
                val (newId, newParams) = absorbed(id, params)
                assertEquals(id, newId, "$id keeps its id")
                for (context in CONTEXTS) {
                    val old = oldFactory(params, null, null) as StatefulFx
                    val new = registry.createFx(newId, newParams) as StatefulFx
                    old.initialize()
                    new.initialize()
                    for (tick in 0L until 400L) {
                        val clock = MasterClock.ClockTick(tick, tick / 24, (tick % 24).toInt(), (tick % 24) / 24.0, tick * 21)
                        val delta = if (tick == 0L) 0L else 21L
                        // A low above a high throws in Candle Flicker's clamp, old and new alike.
                        val expected = outcome { old.calculateStateful(clock, delta, context) }
                        val actual = outcome { new.calculateStateful(clock, delta, context) }
                        if (expected != actual && failures.size < 20) failures += "$id $params → $newParams at tick $tick: old $expected, new $actual"
                    }
                }
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
    }

    @Test
    fun `Path's presets start each sweep as its old FX did`() {
        val path = checkNotNull(registry.getRegistration(LegacyNames.PATH))
        for ((preset, old) in listOf("Sweep" to "Sweep", "Pan Sweep" to "PanSweep", "Tilt Sweep" to "TiltSweep")) {
            val p = path.presets.single { it.name == preset }
            val oldFx = legacy(old)
            val pathFx = registry.createFx(LegacyNames.PATH, p.parameters)
            for (phase in PHASES) assertEquals(oldFx.calculate(phase), pathFx.calculate(phase), "$preset at $phase")
            assertEquals(2.0 to FxDirection.BOUNCE.name, p.cycle to p.direction, "$preset bounces at half a bar")
            assertEquals(2.0 to FxDirection.BOUNCE.name, LegacyNames.consolidatedTiming(old))
        }
        // Circle and Figure 8 start where they did, Path's defaults being Circle's.
        for ((shape, old) in listOf("CIRCLE" to "Circle", "FIGURE_8" to "Figure8", "RANDOM" to "RandomPosition")) {
            val (_, parameters) = absorbed(old, emptyMap())
            assertEquals(shape, parameters["shape"])
            for (phase in PHASES) assertEquals(legacy(old).calculate(phase), registry.createFx(LegacyNames.PATH, parameters).calculate(phase))
        }
        assertEquals(null, LegacyNames.consolidatedTiming("Circle"))
    }

    /**
     * Colour Wave and Colour Fade stay legacy (fx-settings plan session 3): Colour Cycle draws each a
     * DMX step apart — Smooth a quarter on misses Colour Wave by a step where its cosine and Colour
     * Wave's sine floor either side of a byte, and Fade at twice the cycle misses Colour Fade's bounce,
     * whose reversed phase reads 2ε short, by a step at every turn. P3 refuses both, so neither is
     * rewritten; each stays registered, kept from every listing, and its preset starts Colour Cycle.
     */
    @Test
    fun `Colour Wave and Colour Fade stay legacy, because Colour Cycle misses each by a DMX step`() {
        val a = "#102030"
        val b = "#f0e0d0"
        val wave = registry.createFx("ColourWave", mapOf("colourA" to a, "colourB" to b))
        val smooth = registry.createFx("ColourCycle", mapOf("colours" to "$a,$b", "between" to "SMOOTH"))
        assertTrue(PHASES.any { wave.calculate(it) != smooth.calculate(cyclePhase(it + 0.25)) }, "Smooth a quarter on is not Colour Wave")
        val fade = registry.createFx("ColourFade", mapOf("fromColour" to a, "toColour" to b))
        val cycle = registry.createFx("ColourCycle", mapOf("colours" to "$a,$b", "between" to "FADE", "fade" to "1"))
        assertTrue(
            (0..192).map { it / 96.0 }.any { t ->
                fade.calculate(FxDirection.BOUNCE.apply(cyclePhase(t), kotlin.math.floor(t).toLong())) != cycle.calculate(cyclePhase(t / 2))
            },
            "Fade at twice the cycle is not Colour Fade's bounce",
        )
        for (id in listOf("ColourWave", "ColourFade")) {
            val registration = assertNotNull(registry.getRegistration(id), "$id still runs what is stored")
            assertTrue(registration.legacy, "$id is legacy")
            assertTrue(registry.getLibrary().single { it.name == id }.legacy)
            assertEquals(null, LegacyNames.absorbFx(LegacyNames.StoredFx(id, "colour", 4.0, "NORMAL", 0.0, emptyMap(), "FORWARD", null, 1.0)) { null })
            assertTrue(!LegacyNames.isRetiredFxId(id))
        }
        val presets = checkNotNull(registry.getRegistration("ColourCycle")).presets.associateBy { it.name }
        assertEquals(mapOf("colours" to "#ff0000,#0000ff", "between" to "FADE", "fade" to "1"), presets.getValue("Colour Fade").parameters)
        assertEquals(4.0, presets.getValue("Colour Fade").cycle, "the old fade's two-beat bounce, one way in four")
        assertEquals(mapOf("colours" to "#000000,#ffffff", "between" to "SMOOTH"), presets.getValue("Colour Wave").parameters)
    }
}
