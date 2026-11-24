package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.dmx.ColourSplits
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.ShowSoundLedBeamRingFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Split colours (wheel-colour-chase plan D4–D6, D8): a chase on Split holds each pair of colours'
 * split on a wheel whose two slots split, changes any other wheel at the split's middle, and holds the
 * half-way mix on an RGB head; Slot Steps on Split does the same on a colour wheel; and the preview
 * says where a chase will not do what it looks like it will.
 *
 * The rig: a hex at 1 (RGB 2–4), a LEDBEAMRING at 101 (colour wheel 108) and a MAC 250 at 201
 * (colour wheel 203, gobo wheel 204).
 */
class SplitColourChaseTest {

    private val universe = Universe(0, 0)

    private class Rig(val fixtures: Fixtures, val controller: MockDmxController, val engine: FxEngine) {
        fun at(channel: Int): Int = controller.currentValues[channel]?.toInt() ?: 0
        fun rgb(first: Int) = Triple(at(first), at(first + 1), at(first + 2))
    }

    private fun rig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            val hex = addFixture(HexFixture(universe, "hex", "Hex", 1))
            val ring = addFixture(ShowSoundLedBeamRingFixture.Mode14Ch(universe, "ring", "Ring", 101))
            val mac = addFixture(MartinMac250Fixture.Mode4Ch(universe, "mac", "MAC", 201))
            createGroup<Fixture>("rig") { addSpread(listOf(hex, ring, mac)) }
        }
        val programmer = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmer,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmer),
        )
        return Rig(fixtures, controller, engine)
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

    private val registry get() = FxSamplerParityTest.registry

    private val beat = MasterClock.TICKS_PER_BEAT.toLong()

    /** [colours], a beat each, on Split held for half of each step, the members in one phase. */
    private fun splitChase(target: FxTarget, colours: List<String>, cycle: Double = colours.size.toDouble()) =
        FxInstance(
            registry.createFx("ColourCycle", mapOf("colours" to colours.joinToString(","), "between" to "SPLIT", "fade" to "0.5")),
            target,
            FxTiming(cycle = cycle),
        ).also { it.order = SpreadOrder.UNIFIED }

    private val macWheel get() = MartinMac250Fixture.Mode4Ch(universe, "probe", "Probe", 1).colourWheel

    private fun macSplit(a: MartinMac250Fixture.Colour, b: MartinMac250Fixture.Colour): Int =
        assertNotNull(ColourSplits.levelBetween(macWheel, a, b), "${a.name}/${b.name}").toInt()

    @Test
    fun `a MAC 250 Split Chase in wheel order holds every split between its slots`() {
        val rig = rig()
        // Every slot with a colour of its own, in wheel order (open and white are both white).
        val slots = MartinMac250Fixture.Colour.entries.filter { it.continuousLevel != null && it.colourPreview != "#FFFFFF" }
        rig.engine.addFx(splitChase(ColourTarget(FxTargetRef.fixture("mac")), slots.map { it.colourPreview!! }))

        for ((k, slot) in slots.withIndex()) {
            rig.engine.processBeatTick(tick(k * beat + 6))
            assertEquals(slot.level.toInt(), rig.at(203), "step $k holds ${slot.name}")
            val next = slots.getOrNull(k + 1) ?: continue
            rig.engine.processBeatTick(tick(k * beat + 15))
            assertEquals(macSplit(slot, next), rig.at(203), "then the split of ${slot.name} and ${next.name}")
            rig.engine.processBeatTick(tick(k * beat + 22))
            assertEquals(macSplit(slot, next), rig.at(203), "held to the end of the step")
        }
    }

    @Test
    fun `two slots that are not neighbours change at the middle of their split`() {
        val rig = rig()
        val red = MartinMac250Fixture.Colour.RED
        val green = MartinMac250Fixture.Colour.GREEN
        rig.engine.addFx(splitChase(ColourTarget(FxTargetRef.fixture("mac")), listOf(red.colourPreview!!, green.colourPreview!!)))

        rig.engine.processBeatTick(tick(16))
        assertEquals(red.level.toInt(), rig.at(203), "Blue 108 lies between them, so red holds into the split")
        rig.engine.processBeatTick(tick(19))
        assertEquals(green.level.toInt(), rig.at(203), "and green from its middle, never the split")
    }

    @Test
    fun `on one beat the par holds the half-way mix, the MAC its split and the Beam Ring changes at the middle`() {
        val rig = rig()
        val red = MartinMac250Fixture.Colour.RED
        val blue = MartinMac250Fixture.Colour.BLUE_108
        rig.engine.addFx(splitChase(ColourTarget.forGroup("rig"), listOf("#ff0000", blue.colourPreview!!)))
        val ringRed = ShowSoundLedBeamRingFixture.Colour.RED.level.toInt()

        rig.engine.processBeatTick(tick(6))
        assertEquals(Triple(255, 0, 0), rig.rgb(2))
        assertEquals(red.level.toInt(), rig.at(203))
        assertEquals(ringRed, rig.at(108))

        rig.engine.processBeatTick(tick(14))
        val mix = rig.rgb(2)
        assertEquals(macSplit(red, blue), rig.at(203), "the MAC's red and Blue 108 are neighbours")
        assertEquals(ringRed, rig.at(108), "the Beam Ring cannot split, so it holds red")
        rig.engine.processBeatTick(tick(22))
        assertEquals(mix, rig.rgb(2), "the par holds one mix through the split")
        assertTrue(mix.first in 100..160 && mix.third in 80..140, "red and Blue 108 half and half: $mix")
        assertEquals(macSplit(red, blue), rig.at(203))
        assertTrue(rig.at(108) != ringRed, "past the split's middle the Beam Ring has changed")

        rig.engine.processBeatTick(tick(beat + 6))
        assertEquals(blue.level.toInt(), rig.at(203), "every head changes on the beat")
    }

    @Test
    fun `Slot Steps on Split holds a colour wheel's split, and a gobo wheel changes at the middle`() {
        val rig = rig()
        fun slotSteps(attribute: String, slots: String) = FxInstance(
            registry.createFx("SlotSteps", mapOf("slots" to slots, "between" to "SPLIT", "fade" to "0.5")),
            SettingTarget(FxTargetRef.fixture("mac"), attribute),
            FxTiming(cycle = 2.0),
        )
        rig.engine.addFx(slotSteps("colourWheel", "RED,BLUE_108"))
        rig.engine.addFx(slotSteps("gobo", "CONE,BAR"))

        rig.engine.processBeatTick(tick(6))
        assertEquals(MartinMac250Fixture.Colour.RED.level.toInt(), rig.at(203))
        assertEquals(MartinMac250Fixture.Gobo.CONE.level.toInt(), rig.at(204))
        rig.engine.processBeatTick(tick(16))
        assertEquals(macSplit(MartinMac250Fixture.Colour.RED, MartinMac250Fixture.Colour.BLUE_108), rig.at(203))
        assertEquals(MartinMac250Fixture.Gobo.CONE.level.toInt(), rig.at(204), "a gobo has no split")
        rig.engine.processBeatTick(tick(19))
        assertEquals(MartinMac250Fixture.Gobo.BAR.level.toInt(), rig.at(204))
    }

    @Test
    fun `Slot Steps keeps cutting by default`() {
        val rig = rig()
        rig.engine.addFx(
            FxInstance(
                registry.createFx("SlotSteps", mapOf("slots" to "RED,BLUE_108")),
                SettingTarget(FxTargetRef.fixture("mac"), "colourWheel"),
                FxTiming(cycle = 2.0),
            ),
        )
        rig.engine.processBeatTick(tick(22))
        assertEquals(MartinMac250Fixture.Colour.RED.level.toInt(), rig.at(203), "red to the step's edge")
        rig.engine.processBeatTick(tick(24))
        assertEquals(MartinMac250Fixture.Colour.BLUE_108.level.toInt(), rig.at(203))
    }

    // ── The preview's notes ─────────────────────────────────────────────────

    private fun notes(rig: Rig, effect: FxInstance, key: String, attribute: String): List<FxWheelNote> {
        val samples = rig.engine.sampler { registry.createFx(it.fxTypeId, it.effect.parameters) }.sample(FxSampleRequest(listOf(effect)))
        return assertNotNull(samples.attribute(attribute)?.row(key), "$key's $attribute").fx.single().notes
    }

    @Test
    fun `two colours on one slot are named`() {
        val rig = rig()
        val effect = FxInstance(
            registry.createFx("ColourCycle", mapOf("colours" to "#ff0000,#ff1000,#0000ff", "between" to "CUT")),
            ColourTarget(FxTargetRef.fixture("ring")),
            FxTiming(cycle = 12.0),
        )
        assertEquals(
            listOf<FxWheelNote>(FxWheelNote.SameSlot(listOf("#FF0000", "#FF1000"), "RED")),
            notes(rig, effect, "ring", "colourWheel"),
        )
    }

    @Test
    fun `a split the wheel cannot hold says why`() {
        val rig = rig()
        val onRing = splitChase(ColourTarget(FxTargetRef.fixture("ring")), listOf("#ff0000", "#0000ff"), cycle = 8.0)
        assertEquals(
            listOf<FxWheelNote>(FxWheelNote.NoSplit("RED", "BLUE", FxWheelNote.NoSplit.Reason.WHEEL)),
            notes(rig, onRing, "ring", "colourWheel"),
        )
        val red = MartinMac250Fixture.Colour.RED
        val green = MartinMac250Fixture.Colour.GREEN
        val onMac = splitChase(ColourTarget(FxTargetRef.fixture("mac")), listOf(red.colourPreview!!, green.colourPreview!!), cycle = 8.0)
        assertEquals(
            listOf<FxWheelNote>(FxWheelNote.NoSplit("RED", "GREEN", FxWheelNote.NoSplit.Reason.NOT_NEIGHBOURS)),
            notes(rig, onMac, "mac", "colourWheel"),
        )
        val neighbours = splitChase(ColourTarget(FxTargetRef.fixture("mac")), listOf(red.colourPreview, MartinMac250Fixture.Colour.BLUE_108.colourPreview!!), cycle = 8.0)
        assertEquals(emptyList(), notes(rig, neighbours, "mac", "colourWheel"), "neighbours split")
    }

    @Test
    fun `a step shorter than the wheel's move is too fast`() {
        val rig = rig()
        val purple = MartinMac250Fixture.Colour.PURPLE
        val yellow = MartinMac250Fixture.Colour.YELLOW
        fun chase(cycle: Double) = FxInstance(
            registry.createFx("ColourCycle", mapOf("colours" to "${purple.colourPreview},${yellow.colourPreview}", "between" to "CUT")),
            ColourTarget(FxTargetRef.fixture("mac")),
            FxTiming(cycle = cycle),
        )
        // Cut moves between indexed levels, 156 to 196: 40 of 255, at 700 ms across the wheel 110 ms.
        val tooFast = notes(rig, chase(0.25), "mac", "colourWheel").single() as FxWheelNote.TooFast
        assertEquals(110L, tooFast.needMs)
        assertTrue(tooFast.stepMs < 110L, "$tooFast")
        assertEquals(emptyList(), notes(rig, chase(4.0), "mac", "colourWheel"), "a beat a step is time enough")
    }

    @Test
    fun `a move through a split is measured on the continuous band`() {
        val rig = rig()
        val red = MartinMac250Fixture.Colour.RED
        val blue = MartinMac250Fixture.Colour.BLUE_108
        val chase = splitChase(ColourTarget(FxTargetRef.fixture("mac")), listOf(red.colourPreview!!, blue.colourPreview!!), cycle = 0.05)
        // 67 to 78 of the band's 143: 54 ms.
        val tooFast = notes(rig, chase, "mac", "colourWheel").single() as FxWheelNote.TooFast
        assertEquals(54L, tooFast.needMs)
    }

    @Test
    fun `a short split still says the wheel cannot hold it`() {
        val rig = rig()
        val red = MartinMac250Fixture.Colour.RED
        val green = MartinMac250Fixture.Colour.GREEN
        val chase = FxInstance(
            registry.createFx(
                "ColourCycle",
                mapOf("colours" to "${red.colourPreview},${green.colourPreview}", "between" to "SPLIT", "fade" to "0.01"),
            ),
            ColourTarget(FxTargetRef.fixture("mac")),
            FxTiming(cycle = 8.0),
        ).also { it.order = SpreadOrder.UNIFIED }
        assertEquals(
            listOf<FxWheelNote>(FxWheelNote.NoSplit("RED", "GREEN", FxWheelNote.NoSplit.Reason.NOT_NEIGHBOURS)),
            notes(rig, chase, "mac", "colourWheel"),
        )
    }

    @Test
    fun `a paused chase keeps its notes`() {
        val rig = rig()
        val onRing = splitChase(ColourTarget(FxTargetRef.fixture("ring")), listOf("#ff0000", "#0000ff"), cycle = 8.0)
        onRing.pause()
        assertEquals(
            listOf<FxWheelNote>(FxWheelNote.NoSplit("RED", "BLUE", FxWheelNote.NoSplit.Reason.WHEEL)),
            notes(rig, onRing, "ring", "colourWheel"),
        )
    }
}
