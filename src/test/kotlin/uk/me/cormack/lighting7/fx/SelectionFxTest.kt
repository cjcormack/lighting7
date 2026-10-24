package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fx.group.SpreadOrder
import uk.me.cormack.lighting7.models.FxTargetsRef
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One effect over a selection (effects plan D3, §3.4): the selection's fixtures and groups flattened
 * in order into one instance's members, a group contributing its own members, a head named twice
 * counted once at its first place, and the members distributed as a group's are.
 */
class SelectionFxTest {

    private val universe = Universe(0, 0)

    private class Rig(val controller: MockDmxController, val fixtures: Fixtures, val engine: FxEngine)

    private val pars = (0 until 8).map { "par-$it" }
    private val groupMembers = (0 until 4).map { "g-$it" }

    private fun newRig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            for ((i, key) in pars.withIndex()) addFixture(HexFixture(universe, key, key, 1 + i * 12))
            val members = groupMembers.mapIndexed { i, key -> addFixture(HexFixture(universe, key, key, 97 + i * 12)) }
            createGroup<HexFixture>("four") { addSpread(members) }
            addFixture(LedLightbar12PixelFixture.Mode48Ch(universe, "bar", "Bar", 200))
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, fixtures, engine)
    }

    /** Paints its phase, never 0. */
    private class PhaseSlider : Fx {
        override val name = "Phase Slider"
        override val outputType = FxOutputType.SLIDER
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Slider((10 + (phase * 200).toInt()).toUByte())
    }

    private class Solid : Fx {
        override val name = "Solid"
        override val outputType = FxOutputType.COLOUR
        override fun calculate(phase: Double, context: FxContext): FxOutput =
            FxOutput.Colour(ExtendedColour(java.awt.Color(200, 40, 10)))
    }

    private fun selection(vararg items: TargetRef) = FxTargetsRef.Selection(items.toList())

    private fun dimmerOver(sel: FxTargetsRef.Selection, strategy: SpreadOrder = SpreadOrder.NORMAL) =
        FxInstance(
            effect = PhaseSlider(),
            target = SliderTarget(FxTargetRef.SelectionRef(sel), "dimmer"),
            timing = FxTiming(cycle = 1.0),
            blendMode = BlendMode.OVERRIDE,
        ).apply { order = strategy }

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

    /** A hex's dimmer is its first channel. */
    private fun Rig.dimmerOf(key: String): Int {
        val first = pars.indexOf(key).takeIf { it >= 0 }?.let { 1 + it * 12 } ?: (97 + groupMembers.indexOf(key) * 12)
        return controller.getValue(first).toInt()
    }

    @Test
    fun `eight loose pars and a group of four are one instance of twelve members in order`() {
        val rig = newRig()
        val effect = dimmerOver(selection(*pars.map { TargetRef.Fixture(it) }.toTypedArray(), TargetRef.Group("four")))
        rig.engine.addFx(effect)

        assertEquals(1, rig.engine.getActiveFx().size, "one instance")
        val order = pars + groupMembers
        assertEquals(order, rig.engine.fixtureKeysCoveredBy(effect))

        // NORMAL over twelve: member i sits i/12 of a cycle behind member 0, so at the top of the
        // clock every member after the first paints a little less than the one before it.
        rig.engine.processBeatTick(tick(0))
        val levels = order.drop(1).map { rig.dimmerOf(it) }
        assertEquals(levels.sortedDescending(), levels, "the spread runs in selection order: $levels")
        assertEquals(levels.size, levels.toSet().size, "every member has its own phase: $levels")
    }

    @Test
    fun `a head named twice counts once at its first place`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Group("four"), TargetRef.Fixture("g-1"), TargetRef.Fixture("par-2")))
        rig.engine.addFx(effect)

        assertEquals(groupMembers + "par-2", rig.engine.fixtureKeysCoveredBy(effect))
    }

    @Test
    fun `the selection's own order is the spread's, not the rig's`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-5"), TargetRef.Fixture("par-1"), TargetRef.Fixture("par-3")))
        rig.engine.addFx(effect)

        assertEquals(listOf("par-5", "par-1", "par-3"), rig.engine.fixtureKeysCoveredBy(effect))
        rig.engine.processBeatTick(tick(0))
        assertTrue(rig.dimmerOf("par-1") > rig.dimmerOf("par-3"), "par-1 is second, par-3 third")
    }

    @Test
    fun `a fixture whose heads carry the property contributes its heads, beside a fixture that carries it`() {
        val rig = newRig()
        val effect = FxInstance(
            effect = Solid(),
            target = ColourTarget(FxTargetRef.SelectionRef(selection(TargetRef.Fixture("par-0"), TargetRef.Fixture("bar")))),
            timing = FxTiming(cycle = 1.0),
            blendMode = BlendMode.OVERRIDE,
        )
        rig.engine.addFx(effect)

        val heads = (0 until 12).map { "bar.pixel-$it" }
        assertEquals(listOf("par-0") + heads, rig.engine.fixtureKeysCoveredBy(effect))
        assertTrue(rig.engine.isMultiElementExpanded(effect))
    }

    @Test
    fun `an item the rig has lost is skipped and the rest still run`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Group("gone"), TargetRef.Fixture("nope"), TargetRef.Fixture("par-0")))
        rig.engine.addFx(effect)

        assertEquals(listOf("par-0"), rig.engine.fixtureKeysCoveredBy(effect))
    }

    @Test
    fun `a selection effect reaches each fixture it names, as a group effect reaches its members`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-3"), TargetRef.Group("four")))
        rig.engine.addFx(effect)

        assertEquals(listOf(effect.id), rig.engine.getIndirectFxForFixture("par-3").map { it.id })
        assertEquals(listOf(effect.id), rig.engine.getIndirectFxForFixture("g-2").map { it.id })
        assertTrue(rig.engine.getIndirectFxForFixture("par-4").isEmpty())
        assertTrue(rig.engine.getFxForFixture("par-3").isEmpty(), "not a direct effect of any one fixture")
    }

    @Test
    fun `removing a selection effect hands every member back to the level underneath`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-0"), TargetRef.Group("four")))
        rig.engine.addFx(effect)
        rig.engine.processBeatTick(tick(3))
        assertTrue(rig.dimmerOf("g-3") > 0)

        rig.engine.removeFx(effect.id)
        for (key in listOf("par-0") + groupMembers) assertEquals(0, rig.dimmerOf(key), key)
    }

    @Test
    fun `another cue's stomp takes a selection effect off only where it asserts every item`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-0"), TargetRef.Group("four"))).apply { cueId = 7 }
        rig.engine.addFx(effect)

        assertEquals(0, rig.engine.stompForCue(8, setOf(FxEngine.PropertyKey("par-1", "dimmer"))), "not one of its items")
        assertEquals(0, rig.engine.stompForCue(8, setOf(FxEngine.PropertyKey("four", "dimmer"))), "one item of two")
        assertEquals(
            0,
            rig.engine.stompForCue(8, setOf(FxEngine.PropertyKey("par-0", "colour"), FxEngine.PropertyKey("four", "colour"))),
            "another property",
        )
        assertEquals(
            1,
            rig.engine.stompForCue(8, setOf(FxEngine.PropertyKey("par-0", "dimmer"), FxEngine.PropertyKey("four", "dimmer"))),
        )
        assertTrue(rig.engine.getActiveFx().isEmpty())
    }

    @Test
    fun `naming every item clears a selection effect, naming some does not`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-0"), TargetRef.Group("four")))
        rig.engine.addFx(effect)

        assertEquals(0, rig.engine.removeSelectionFxWithin(listOf(TargetRef.Group("four"))))
        assertEquals(0, rig.engine.removeFxForFixture("par-0"), "the selection's key names no fixture")
        assertEquals(1, rig.engine.removeSelectionFxWithin(listOf(TargetRef.Group("four"), TargetRef.Fixture("par-0"))))
        assertTrue(rig.engine.getActiveFx().isEmpty())
    }

    @Test
    fun `the report names the selection and its items`() {
        val rig = newRig()
        val effect = dimmerOver(selection(TargetRef.Fixture("par-0"), TargetRef.Group("four")))
        rig.engine.addFx(effect)

        val dto = rig.engine.fxDto(effect)
        assertEquals("selection", dto.targetType)
        assertEquals(listOf("fixture" to "par-0", "group" to "four"), dto.targets!!.map { it.type to it.key })
        assertTrue(dto.order != null, "a selection reports its order, as a group does")
    }

    @Test
    fun `a selection key round-trips and refuses what is not a list of fixtures and groups`() {
        val sel = selection(TargetRef.Fixture("par-0"), TargetRef.Group("four"))
        assertEquals("""[{"key":"par-0","type":"fixture"},{"key":"four","type":"group"}]""", sel.key)
        assertEquals(sel, FxTargetsRef.ofOrNull("selection", sel.key))
        assertEquals(FxTargetsRef.Single(TargetRef.Group("four")), FxTargetsRef.ofOrNull("group", "four"))
        for (bad in listOf("", "[]", "{}", """[{"key":"x"}]""", """[{"key":"x","type":"selection"}]""", "not json")) {
            assertNull(FxTargetsRef.ofOrNull("selection", bad), bad)
        }
        assertEquals(FxTargetsRef.Single(TargetRef.Fixture("x")), FxTargetsRef.of(listOf(TargetRef.Fixture("x"))))
    }
}
