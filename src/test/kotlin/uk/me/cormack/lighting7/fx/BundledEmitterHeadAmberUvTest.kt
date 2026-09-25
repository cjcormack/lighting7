package uk.me.cormack.lighting7.fx

import org.junit.Test
import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.FixtureProperty
import uk.me.cormack.lighting7.fixture.FixtureType
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.DmxColour
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.group.FixtureElement
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.trait.WithColour
import uk.me.cormack.lighting7.models.CuePropertyAssignmentDto
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.assertEquals

/**
 * A **head** whose bundled emitters are amber and UV, not white.
 *
 * Every head in the rig today bundles white only (`LedLightbar12PixelFixture.RgbwPixel`,
 * `ShehdsLed19RgbwFixture.Zone`), so the tests beside this one pin heads through white. The code is
 * category-agnostic — the bundle comes from the class catalogue for WHITE, AMBER and UV alike — and
 * this pins that for the other two, so an RGBA+UV multi-head fixture added later is covered by
 * construction rather than by luck. `SliderTarget`'s colour-entry reader once returned nothing for
 * any head, whatever the emitter; that is the first case below.
 *
 * [RgbAuvBar] is test-only: it is not in `FixtureTypeRegistry`'s list, so no patch can name it.
 */
class BundledEmitterHeadAmberUvTest {

    /** Two heads of five channels each — R, G, B, amber, UV — from [firstChannel]. */
    @FixtureType("test-rgb-a-uv-bar")
    class RgbAuvBar(
        universe: Universe,
        key: String,
        firstChannel: Int,
        private val transaction: ControllerTransaction? = null,
    ) : DmxFixture(universe, firstChannel, 10, key, key), MultiElementFixture<RgbAuvBar.Head> {

        override fun withTransaction(transaction: ControllerTransaction): RgbAuvBar =
            RgbAuvBar(universe, key, firstChannel, transaction)

        override val elements: List<Head> = (0 until 2).map { Head(it, transaction, firstChannel + it * 5) }

        inner class Head(
            override val elementIndex: Int,
            private val headTransaction: ControllerTransaction?,
            private val headFirstChannel: Int,
        ) : FixtureElement<RgbAuvBar>, WithColour {
            override val parentFixture: RgbAuvBar get() = this@RgbAuvBar

            @FixtureProperty("RGB colour", category = PropertyCategory.COLOUR)
            override val rgbColour = DmxColour(
                headTransaction, universe, headFirstChannel, headFirstChannel + 1, headFirstChannel + 2,
            )

            @FixtureProperty("Amber", category = PropertyCategory.AMBER, bundleWithColour = true)
            val amber = DmxSlider(headTransaction, universe, headFirstChannel + 3)

            @FixtureProperty("UV", category = PropertyCategory.UV, bundleWithColour = true)
            val uv = DmxSlider(headTransaction, universe, headFirstChannel + 4)

            override fun withTransaction(transaction: ControllerTransaction): Head =
                Head(elementIndex, transaction, headFirstChannel)
        }
    }

    private val universe = Universe(0, 0)

    /** Head 1 of a bar at channel 1: R/G/B 6–8, amber 9, UV 10. */
    private val headKey = "bar.element-1"
    private val amberChannel = 9
    private val uvChannel = 10

    private class Rig(val fixtures: Fixtures, val controller: MockDmxController)

    private fun rig(): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(RgbAuvBar(universe, "bar", 1))
        }
        return Rig(fixtures, controller)
    }

    private fun colour(serialized: String) = CueAssignmentResolver.PropertyValue.Colour(parseExtendedColour(serialized))
    private fun slider(level: Int) = CueAssignmentResolver.PropertyValue.Slider(level.toUByte())

    private fun head(rig: Rig, tx: ControllerTransaction? = null): GroupableFixture =
        if (tx == null) rig.fixtures.untypedGroupableFixture(headKey)
        else rig.fixtures.withTransaction(tx).untypedGroupableFixture(headKey)

    @Test
    fun `the head's amber and UV are its bundle, beside its colour`() {
        val head = head(rig())
        assertEquals(CueAssignmentResolver.BundleRole.COLOUR, bundleRoleOf(head, "rgbColour"))
        assertEquals(CueAssignmentResolver.BundleRole.EMITTER, bundleRoleOf(head, "amber"))
        assertEquals(CueAssignmentResolver.BundleRole.EMITTER, bundleRoleOf(head, "uv"))
    }

    @Test
    fun `the programmer arbitrates a head's amber and UV by recency, both ways`() {
        val head = head(rig())
        val store = ProgrammerStore()
        val below = FxOutput.Colour(ExtendedColour.BLACK)

        fun onStage(property: String): Int =
            (SliderTarget(headKey, property).composeProgrammerOver(head, store, FxOutput.Slider(0u)) as FxOutput.Slider)
                .value.toInt()
        fun colourCopy(): ExtendedColour =
            (ColourTarget(headKey).composeProgrammerOver(head, store, below) as FxOutput.Colour).color

        store.put(ProgrammerOwner.WEB, headKey, "rgbColour", colour("#ff0000;a10;uv20"))
        store.put(ProgrammerOwner.WEB, headKey, "amber", slider(200))
        store.put(ProgrammerOwner.WEB, headKey, "uv", slider(150))
        assertEquals(200, onStage("amber"), "newer amber slider")
        assertEquals(150, onStage("uv"), "newer UV slider")
        assertEquals(200u.toUByte(), colourCopy().amber, "the colour's reader agrees")
        assertEquals(150u.toUByte(), colourCopy().uv)

        // A newer colour: the head's own sliders must see it — the reader that returned nothing for
        // any head, whatever the emitter.
        store.put(ProgrammerOwner.WEB, headKey, "rgbColour", colour("#00ff00;a30;uv40"))
        assertEquals(30, onStage("amber"), "newer colour's amber")
        assertEquals(40, onStage("uv"), "newer colour's UV")
        assertEquals(30u.toUByte(), colourCopy().amber)
        assertEquals(40u.toUByte(), colourCopy().uv)
    }

    @Test
    fun `a programmer layer's same-seq tie goes to the head's amber and UV on both readers`() {
        val head = head(rig())
        val store = ProgrammerStore()
        store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite(headKey, "rgbColour", colour("#ff0000;a10;uv20"), layerIndex = 0),
                ProgrammerStore.LayerSlotWrite(headKey, "amber", slider(200), layerIndex = 0),
                ProgrammerStore.LayerSlotWrite(headKey, "uv", slider(150), layerIndex = 0),
            ),
        )
        val sliderAmber = SliderTarget(headKey, "amber").composeProgrammerOver(head, store, FxOutput.Slider(0u))
        val sliderUv = SliderTarget(headKey, "uv").composeProgrammerOver(head, store, FxOutput.Slider(0u))
        val copy = (ColourTarget(headKey).composeProgrammerOver(head, store, FxOutput.Colour(ExtendedColour.BLACK)) as FxOutput.Colour).color

        assertEquals(FxOutput.Slider(200u), sliderAmber)
        assertEquals(FxOutput.Slider(150u), sliderUv)
        assertEquals(200u.toUByte(), copy.amber)
        assertEquals(150u.toUByte(), copy.uv)
    }

    @Test
    fun `a cue holding both lands the head's own amber and UV, in either publish order`() {
        val rig = rig()
        val rows = listOf(
            CuePropertyAssignmentDto("fixture", headKey, "rgbColour", "#ff0000;a10;uv20"),
            CuePropertyAssignmentDto("fixture", headKey, "amber", "200"),
            CuePropertyAssignmentDto("fixture", headKey, "uv", "150"),
        )
        for (ordered in listOf(rows, rows.reversed())) {
            val built = buildCueAssignmentsForCue(
                rig.fixtures, CueApplyData(cueId = 1, cueName = "both", adHocEffects = emptyList(), propertyAssignments = ordered),
            )
            val composed = CueAssignmentResolver().resolve(built)
            val composedColour = (composed.getValue(CueAssignmentResolver.Key.fixture(headKey, "rgbColour"))
                as CueAssignmentResolver.PropertyValue.Colour).value
            assertEquals(200u.toUByte(), composedColour.amber, "the colour carries the head's own amber")
            assertEquals(150u.toUByte(), composedColour.uv, "the colour carries the head's own UV")
            assertEquals(Color(255, 0, 0), composedColour.color)

            // Transmit both keys in both orders: the channels end on the emitters' values either way.
            fun publish(vararg properties: String) {
                val tx = ControllerTransaction(rig.fixtures.controllers)
                val head = head(rig, tx)
                for (property in properties) {
                    val key = CueAssignmentResolver.Key.fixture(headKey, property)
                    when (val value = composed.getValue(key)) {
                        is CueAssignmentResolver.PropertyValue.Colour ->
                            ColourTarget(headKey).resetToFallback(head, FxOutput.Colour(value.value))
                        is CueAssignmentResolver.PropertyValue.Slider ->
                            SliderTarget(headKey, property).resetToFallback(head, FxOutput.Slider(value.value))
                        else -> error("unexpected $value")
                    }
                }
                tx.apply()
            }
            publish("amber", "uv", "rgbColour")
            assertEquals(200u.toUByte(), rig.controller.getEffectiveValue(amberChannel), "colour published last")
            assertEquals(150u.toUByte(), rig.controller.getEffectiveValue(uvChannel))
            publish("rgbColour", "amber", "uv")
            assertEquals(200u.toUByte(), rig.controller.getEffectiveValue(amberChannel), "emitters published last")
            assertEquals(150u.toUByte(), rig.controller.getEffectiveValue(uvChannel))
        }
    }
}
