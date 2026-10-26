package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.EasingCurve
import uk.me.cormack.lighting7.sync.LegacyNames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * fx-settings plan P3: no level wave is mapped onto Wave until the old script and Wave give the same
 * output for every phase, parameter, Direction and spread. Each old script is compiled from
 * `src/test/resources/fx/legacy/`, exactly as it shipped, and run beside Wave on the parameters
 * [LegacyNames.waveParameters] writes for it — what database step 7, the importer, MCP and a
 * script's `effect()` all store or run.
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
                val waveParams = checkNotNull(LegacyNames.waveParameters(id, params)) { "$id has no Wave row" }
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
        assertEquals(LegacyNames.waveTiming("FocusPull"), 2.0 to FxDirection.BOUNCE.name)
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
}
