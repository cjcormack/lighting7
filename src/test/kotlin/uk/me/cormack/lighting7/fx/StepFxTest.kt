package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.dmx.Fusion100SpotMkIIFixture
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LightstripRgbFixture
import uk.me.cormack.lighting7.fixture.dmx.VarytecEasymoveXl60SpotFixture
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Palette Steps and Slot Steps (effects plan session 7, D8, §3.6): an [FxOutput.Steps] resolved
 * **per head** by the target, so one effect lands each head on its own value for each step.
 *
 * The rig: a Fusion spot at 1 (pan 1, tilt 2, gobo 5 — pan 0–540°, tilt 0–210°, Gobo 1–5), a
 * Varytec spot at 21 (pan 21, tilt 22, gobo 24 — pan 0–630°, tilt 0–270°, Gobo 1–6), a hex at 41
 * (RGB 42–44, amber 45, white 46) and an RGB strip at 61.
 */
class StepFxTest {

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val fixtures: Fixtures) {
        val tx = ControllerTransaction(fixtures.controllers)
        fun head(key: String): GroupableFixture = fixtures.withTransaction(tx).untypedGroupableFixture(key)
        fun at(channel: Int): Int = tx.getValue(Universe(0, 0), channel).toInt()
    }

    private fun rig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(Fusion100SpotMkIIFixture.Mode8Ch(universe, "fusion", "Fusion", 1))
            addFixture(VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "vary", "Vary", 21))
            addFixture(HexFixture(universe, "hex", "Hex", 41))
            addFixture(LightstripRgbFixture(universe, "strip", "Strip", 61))
        }
        return Rig(controller, fixtures)
    }

    private fun step(name: String, vararg rows: Pair<String, String>) =
        PaletteStep(UUID.nameUUIDFromBytes(name.toByteArray()), name, rows.map { (p, v) -> p to parsePaletteIntent(v)!! })

    /** Downstage in centre-relative units (`pos:`) and centre-of-travel in degrees (`deg:`, P3). */
    private val positions = PaletteStepList(
        listOf(
            step("Downstage", "position" to "pos:-64,32"),
            step("Centre", "position" to "deg:270,105"),
        ),
    )

    private fun Rig.applyPosition(key: String, output: FxOutput) =
        PositionTarget(FxTargetRef.fixture(key)).applyValueToFixture(head(key), output, BlendMode.OVERRIDE)

    @Test
    fun `two position palettes land on each head's own pan and tilt, centre-relative and in degrees`() {
        val rig = rig()

        rig.applyPosition("fusion", FxOutput.Steps(0, 1, 0.0, positions))
        rig.applyPosition("vary", FxOutput.Steps(0, 1, 0.0, positions))
        assertEquals(64 to 160, rig.at(1) to rig.at(2), "pos: is the same fraction of travel on every head")
        assertEquals(64 to 160, rig.at(21) to rig.at(22))

        rig.applyPosition("fusion", FxOutput.Steps(0, 1, 1.0, positions))
        rig.applyPosition("vary", FxOutput.Steps(0, 1, 1.0, positions))
        assertEquals(128 to 128, rig.at(1) to rig.at(2), "270° of 540 and 105° of 210 is the Fusion's middle")
        assertEquals(109 to 99, rig.at(21) to rig.at(22), "the same angles are a third of the Varytec's 630° and 270°")

        rig.applyPosition("fusion", FxOutput.Steps(0, 1, 0.5, positions))
        assertEquals(96 to 144, rig.at(1) to rig.at(2), "half way between the two steps, in the head's own bytes")
    }

    @Test
    fun `a colour palette is each head's own mix, so an RGBW head and an RGB one differ`() {
        val rig = rig()
        val amber = PaletteStepList(listOf(step("Amber", "rgbColour" to "#FF9D4A;policy=extract")))

        for (key in listOf("hex", "strip")) {
            ColourTarget(FxTargetRef.fixture(key)).applyValueToFixture(rig.head(key), FxOutput.Steps(0, 0, 0.0, amber), BlendMode.OVERRIDE)
        }

        assertEquals(listOf(255, 157, 74), listOf(rig.at(61), rig.at(62), rig.at(63)), "an RGB head takes the hex whole")
        assertTrue(rig.at(46) > 0, "the hex takes the neutral on its white emitter")
        assertTrue(rig.at(42) < 255, "and out of its RGB, as a layer of the palette would put it there")
    }

    private fun Rig.applyColour(key: String, output: FxOutput) =
        ColourTarget(FxTargetRef.fixture(key)).applyValueToFixture(head(key), output, BlendMode.OVERRIDE)

    @Test
    fun `an emitter-only palette moves that emitter and leaves the colour underneath`() {
        val rig = rig()
        for ((channel, level) in listOf(42 to 10, 43 to 20, 44 to 30, 45 to 5, 46 to 40, 47 to 7)) {
            rig.tx.setValue(universe, channel, level.toUByte())
        }
        val amber = PaletteStepList(listOf(step("Amber wash", "amber" to "dmx:180")))

        rig.applyColour("hex", FxOutput.Steps(0, 0, 0.0, amber))

        assertEquals(180, rig.at(45), "the amber the palette names")
        assertEquals(listOf(10, 20, 30, 40, 7), listOf(42, 43, 44, 46, 47).map(rig::at), "RGB, white and UV as they were")
    }

    @Test
    fun `a colour palette naming an emitter a head lacks is refused whole on that head`() {
        val rig = rig()
        for ((channel, level) in listOf(61 to 1, 62 to 2, 63 to 3)) rig.tx.setValue(universe, channel, level.toUByte())
        val warm = PaletteStepList(listOf(step("Warm", "rgbColour" to "#FF0000;policy=rgbonly", "amber" to "dmx:100")))

        rig.applyColour("strip", FxOutput.Steps(0, 0, 0.0, warm))
        rig.applyColour("hex", FxOutput.Steps(0, 0, 0.0, warm))

        assertEquals(listOf(1, 2, 3), listOf(61, 62, 63).map(rig::at), "an RGB strip has no amber, so it takes none of the colour")
        assertEquals(listOf(255, 0, 0, 100), listOf(42, 43, 44, 45).map(rig::at), "the hex takes it all")
    }

    @Test
    fun `the step caches hold one entry per head and model, however many ticks build fresh fixtures`() {
        val rig = rig()
        val slots = SlotStepList(listOf("Gobo 3", "Gobo 5"))
        repeat(50) {
            // Every tick builds a new transaction and new fixture objects, as the engine does.
            val tx = ControllerTransaction(rig.fixtures.controllers)
            val head = rig.fixtures.withTransaction(tx).untypedGroupableFixture("fusion")
            SettingTarget(FxTargetRef.fixture("fusion"), "gobo").applyValueToFixture(head, FxOutput.Steps(0, 1, 0.0, slots), BlendMode.OVERRIDE)
            PositionTarget(FxTargetRef.fixture("fusion")).applyValueToFixture(head, FxOutput.Steps(0, 1, 0.5, positions), BlendMode.OVERRIDE)
        }
        assertEquals(1, slots.cachedOptionSets, "one wheel model met, one entry")
        assertEquals(1, positions.cachedHeads, "one head and property, one entry")
    }

    @Test
    fun `Slot Steps of Gobo 3, Gobo 5 lands each head's own levels`() {
        val rig = rig()
        val slots = SlotStepList(listOf("Gobo 3", "GOBO_5"))
        fun land(blend: Double) {
            for (key in listOf("fusion", "vary")) {
                SettingTarget(FxTargetRef.fixture(key), "gobo").applyValueToFixture(rig.head(key), FxOutput.Steps(0, 1, blend, slots), BlendMode.OVERRIDE)
            }
        }

        land(0.0)
        assertEquals(59 to 70, rig.at(5) to rig.at(24), "Gobo 3 is 59 on the Fusion and 70 on the Varytec")
        land(1.0)
        assertEquals(109 to 110, rig.at(5) to rig.at(24))
        land(0.4)
        assertEquals(59 to 70, rig.at(5) to rig.at(24), "a slot has no halfway: below half it is still the first")
        land(0.6)
        assertEquals(109 to 110, rig.at(5) to rig.at(24), "and from half it is the second")
    }

    @Test
    fun `a head without the slot holds what is underneath on that step`() {
        val rig = rig()
        val slots = SlotStepList(listOf("Gobo 3", "Gobo 6"))
        rig.tx.setValue(universe, 5, 9u) // the Fusion on Gobo 1, underneath
        fun land(index: Int) {
            for (key in listOf("fusion", "vary")) {
                SettingTarget(FxTargetRef.fixture(key), "gobo")
                    .applyValueToFixture(rig.head(key), FxOutput.Steps(index, index, 0.0, slots), BlendMode.OVERRIDE)
            }
        }

        land(0)
        assertEquals(59 to 70, rig.at(5) to rig.at(24))
        rig.tx.setValue(universe, 5, 9u)
        land(1)
        assertEquals(9, rig.at(5), "the Fusion has no Gobo 6, so it holds Gobo 1")
        assertEquals(124, rig.at(24), "the Varytec's Gobo 6")
    }

    @Test
    fun `a level step a head cannot take crosses from what is underneath`() {
        val rig = rig()
        val dimmers = PaletteStepList(listOf(step("Half", "dimmer" to "pct:50"), step("Zoomed", "zoom" to "pct:50")))
        rig.tx.setValue(universe, 41, 100u)

        SliderTarget(FxTargetRef.fixture("hex"), "dimmer").applyValueToFixture(rig.head("hex"), FxOutput.Steps(1, 0, 0.0, dimmers), BlendMode.OVERRIDE)
        assertEquals(100, rig.at(41), "the second palette names no dimmer: the hex holds its level")

        SliderTarget(FxTargetRef.fixture("hex"), "dimmer").applyValueToFixture(rig.head("hex"), FxOutput.Steps(1, 0, 0.5, dimmers), BlendMode.OVERRIDE)
        assertEquals(114, rig.at(41), "half way from the level underneath (100) to 50 % (128)")
    }

    @Test
    fun `every target accepts steps, so the routes and the instance take a step effect anywhere`() {
        for (target in listOf(
            SliderTarget(FxTargetRef.fixture("hex"), "dimmer"),
            ColourTarget(FxTargetRef.fixture("hex")),
            PositionTarget(FxTargetRef.fixture("fusion")),
            SettingTarget(FxTargetRef.fixture("fusion"), "gobo"),
        )) {
            assertTrue(target.accepts(FxOutputType.STEPS), "${target::class.simpleName} takes steps")
        }
    }

    // ─── The built-ins, compiled ───────────────────────────────────────────

    private companion object {
        val registry: FxRegistry by lazy {
            FxRegistry().also { FxFileLoader(FxScriptCompiler()).loadBuiltInFx(it) }
        }
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    @Test
    fun `Bounce runs the steps back`() {
        val rig = rig()
        val programmer = ProgrammerStore()
        val engine = FxEngine(
            fixtures = rig.fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
        )
        val slots = registry.createFx(SLOT_STEPS_ID, mapOf("slots" to "Gobo 1, Gobo 2, Gobo 3"))
        engine.addFx(
            FxInstance(slots, SettingTarget(FxTargetRef.fixture("vary"), "gobo"), FxTiming(cycle = 3.0)).also {
                it.priority = FxEngine.PROGRAMMER_FX_PRIORITY_BASE
                it.updateDynamics { d -> d.copy(direction = FxDirection.BOUNCE) }
            },
        )
        fun goboAt(n: Long): Int {
            engine.processBeatTick(tick(n))
            return rig.controller.currentValues.getValue(24).toInt()
        }

        // Mid-segment of each third of a three-beat cycle (24 ticks a beat).
        assertEquals(listOf(30, 50, 70), listOf(12L, 36L, 60L).map(::goboAt), "the first cycle runs forward")
        assertEquals(listOf(70, 50, 30), listOf(84L, 108L, 132L).map(::goboAt), "the second runs back")
    }

    @Test
    fun `Palette Steps reads its palettes through the template source and steps between them`() {
        val snapshots = listOf(
            step("Downstage", "position" to "pos:-64,32"),
            step("Centre", "position" to "deg:270,105"),
        ).associate { s ->
            s.paletteUuid to PaletteSnapshot(
                paletteId = 1, paletteUuid = s.paletteUuid, name = s.name, fadeDurationMs = null,
                rows = s.rows.map { (p, i) -> PaletteRowEntry(null, p, serialiseIntent(i)) },
            )
        }
        val templates = PaletteRegistry { snapshots[it] }
        val refs = snapshots.keys.joinToString(",") { serializePaletteColourRef(it) }
        val effect = registry.createFxWithPalettes(templates, PALETTE_STEPS_ID, mapOf("palettes" to refs, "fadeRatio" to "0"))
        assertEquals(FxOutputType.STEPS, effect.outputType)

        val rig = rig()
        rig.applyPosition("vary", effect.calculate(0.25))
        assertEquals(64 to 160, rig.at(21) to rig.at(22), "the first half of the cycle is the first palette")
        rig.applyPosition("vary", effect.calculate(0.75))
        assertEquals(109 to 99, rig.at(21) to rig.at(22), "the second half the second, in this head's degrees")

        val shape = paletteStepsShape(PaletteSource(templates).paletteStepsOf(refs)).getOrThrow()
        assertEquals(PaletteStepsShape(AttributeFamily.POSITION, "position", "position"), shape)
    }

    @Test
    fun `a Palette Steps list is one family, stepping an attribute every palette names`() {
        val beam = paletteStepsShape(listOf(step("Wide", "zoom" to "pct:90", "frost" to "pct:10"), step("Tight", "zoom" to "pct:10")))
        assertEquals("zoom", beam.getOrThrow().attribute, "frost is not in both, so the steps step the zoom")
        assertEquals("beam", beam.getOrThrow().fxFamily)

        val mixed = paletteStepsShape(listOf(step("Red", "rgbColour" to "#FF0000"), step("Down", "position" to "pos:0,0")))
        assertTrue(mixed.isFailure, "colour and position cannot be one list")

        val colour = paletteStepsShape(listOf(step("Red", "rgbColour" to "#FF0000"), step("UV", "uv" to "dmx:200")))
        assertEquals(PaletteStepsShape(AttributeFamily.COLOUR, "colour", "colour"), colour.getOrThrow(), "colour is one colour, whatever rows spell it")
    }

    @Test
    fun `stepsAt holds each step for its segment, then crosses to the next over the fade`() {
        val three = SlotStepList(listOf("a", "b", "c"))
        assertEquals(FxOutput.Steps(0, 1, 0.0, three), stepsAt(0.1, three, 0.5))
        assertEquals(FxOutput.Steps(0, 1, 0.5, three), stepsAt(0.25, three, 0.5), "three quarters of the first third")
        assertEquals(FxOutput.Steps(2, 0, 0.0, three), stepsAt(0.7, three, 0.0), "the last crosses back to the first")
        assertEquals(FxOutput.Steps(0, 0, 0.0, StepList.EMPTY), stepsAt(0.5, StepList.EMPTY, 0.5))
    }

    private fun serialiseIntent(intent: PaletteIntent): String = intent.serialize()
}
