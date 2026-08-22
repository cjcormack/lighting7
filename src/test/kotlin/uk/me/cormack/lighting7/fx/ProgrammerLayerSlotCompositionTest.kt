package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * How a layer-materialised slot composes against the operator's own writes, across **all four**
 * [FxTarget.composeProgrammerOver] overrides.
 *
 * ## Why this file exists
 *
 * Making the programmer a Look-layer stack means adding a second kind of writer to a store whose
 * every consumer assumed one kind. The rule it has to obey is one sentence — *a local write always
 * wins, whichever happened first* — but it is enforced by two unrelated mechanisms, and nothing
 * else in the suite exercised `composeProgrammerOver` at all before this file.
 *
 * - **Stack position.** [ProgrammerStore.get] returns the stack `top` and ignores `seq` entirely,
 *   and `SliderTarget`/`SettingTarget` take the property entry's value *unconditionally*. So a
 *   layer slot has to sit at the **bottom** of a key's stack.
 * - **`seq`.** Three of the four overrides also arbitrate across *granularities* — the property
 *   entry, a Colour entry's bundled W/A/UV sliders, and the raw-channel sideband — by recency. So a
 *   layer slot also has to carry a `seq` below every real write.
 *
 * Neither mechanism covers the other, which is why both are tested here rather than one being
 * assumed to imply the other.
 *
 * ## The bug this was written against
 *
 * The obvious implementation stamps layer slots with `seq = 0`. That is wrong in a way no other
 * test would have caught, because it creates the **first `seq` tie** the store has ever been able
 * to produce (every real `putValue` bumps a monotonic counter, so two slots never shared one), and
 * the two colour-bundling sites break a tie in *opposite* directions:
 *
 * - `SliderTarget.composeProgrammerOver` sets `bestSeq` from the property entry first, then takes
 *   the bundled component only on `seq > bestSeq` — so an explicit `white` row wins a tie.
 * - `ColourTarget.extendedComponent` starts `bestSeq` at the Colour entry's, then takes the
 *   slider's own entry only on `slot.seq > bestSeq` — so the Colour entry wins a tie.
 *
 * Both write the same DMX channel. On a tie the value therefore depends on which `FxTarget` was
 * inferred last for that key — nondeterministic output, from a change that looks like a no-op.
 * `LAYER_SEQ_BASE + layerIndex` keeps layer slots mutually ordered and never tied.
 */
class ProgrammerLayerSlotCompositionTest {

    private val universe = Universe(0, 0)

    private fun rig(): Pair<Fixtures, ProgrammerStore> {
        val fixtures = Fixtures()
        fixtures.register {
            addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
        }
        return fixtures to ProgrammerStore()
    }

    private fun Fixtures.hex(): HexFixture = fixture("hex-a")

    private fun ProgrammerStore.layer(
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        layerIndex: Int = 0,
    ) = putLayerSlots(
        listOf(ProgrammerStore.LayerSlotWrite("hex-a", propertyName, value, layerIndex))
    )

    private fun ProgrammerStore.local(
        propertyName: String,
        value: CueAssignmentResolver.PropertyValue,
        owner: ProgrammerOwner = ProgrammerOwner.WEB,
    ) = putValue(owner, "hex-a", propertyName, ProgrammerValue.Hard(value))

    private fun slider(v: Int) = CueAssignmentResolver.PropertyValue.Slider(v.toUByte())
    private fun colour(c: Color, white: Int = 0) =
        CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(c, white = white.toUByte()))

    private fun sliderOut(
        fixtures: Fixtures,
        store: ProgrammerStore,
        propertyName: String,
        below: Int = 0,
    ): Int {
        val out = SliderTarget("hex-a", propertyName)
            .composeProgrammerOver(fixtures.hex(), store, FxOutput.Slider(below.toUByte()))
        return (out as FxOutput.Slider).value.toInt()
    }

    // ─── The rule: a local write wins whichever came first ───────────────

    @Test
    fun `a local write made before the layer still wins`() {
        // The order-independence half of the rule, and the one an implementation based on write
        // recency gets wrong: with a plain `put` the layer would be the most recent write and take
        // the top of the stack.
        val (fixtures, store) = rig()
        store.local("dimmer", slider(90))
        store.layer("dimmer", slider(200))

        assertEquals(90, sliderOut(fixtures, store, "dimmer"))
    }

    @Test
    fun `a local write made after the layer wins too`() {
        val (fixtures, store) = rig()
        store.layer("dimmer", slider(200))
        store.local("dimmer", slider(90))

        assertEquals(90, sliderOut(fixtures, store, "dimmer"))
    }

    @Test
    fun `with no local write the layer is what shows`() {
        val (fixtures, store) = rig()
        store.layer("dimmer", slider(200))

        assertEquals(200, sliderOut(fixtures, store, "dimmer"))
    }

    @Test
    fun `releasing the local write reveals the layer rather than the cue beneath`() {
        val (fixtures, store) = rig()
        store.layer("dimmer", slider(200))
        store.local("dimmer", slider(90))
        store.clear(ProgrammerOwner.WEB, "hex-a", "dimmer")

        assertEquals(200, sliderOut(fixtures, store, "dimmer", below = 15))
    }

    // ─── SliderTarget vs ColourTarget: the tie that must not exist ───────

    @Test
    fun `a layer's bundled white loses to an explicit local white on the slider path`() {
        val (fixtures, store) = rig()
        store.layer("rgbColour", colour(Color.RED, white = 200), layerIndex = 0)
        store.local("white", slider(40))

        assertEquals(40, sliderOut(fixtures, store, "white"))
    }

    @Test
    fun `a layer's bundled white loses to an explicit local white on the colour path too`() {
        // Same state, read through the *other* override. These two must agree: both write the same
        // DMX channel, and with a flat layer `seq` they disagreed — SliderTarget preferred the
        // explicit slider, ColourTarget preferred the Colour entry's bundled component.
        val (fixtures, store) = rig()
        store.layer("rgbColour", colour(Color.RED, white = 200), layerIndex = 0)
        store.local("white", slider(40))

        val out = ColourTarget("hex-a")
            .composeProgrammerOver(fixtures.hex(), store, FxOutput.Colour(ExtendedColour(Color.BLACK)))
        assertEquals(40, (out as FxOutput.Colour).color.white.toInt(), "agrees with the slider path")
    }

    @Test
    fun `two layers on the same key stay ordered by layer rank`() {
        // Layer slots must be mutually ordered, not merely below local writes: a later layer's
        // contribution has to beat an earlier one's on the same key. This is what a single flat
        // sentinel `seq` would have destroyed.
        val (fixtures, store) = rig()
        store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite("hex-a", "white", slider(10), layerIndex = 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "rgbColour", colour(Color.RED, white = 250), layerIndex = 1),
            )
        )

        // The colour entry is the later layer, so its bundled white wins.
        assertEquals(250, sliderOut(fixtures, store, "white"))
    }

    @Test
    fun `the slider and colour paths agree about two layers' bundled white`() {
        // **The inconsistency the seq band exists to prevent**, in its exact shape: one layer sets
        // `white` explicitly, a later layer sets `rgbColour` carrying a bundled white. Both slots
        // belong to LAYERS, so with a flat sentinel `seq` they tie — and the two overrides resolve
        // that tie in opposite directions (SliderTarget prefers the explicit row, ColourTarget
        // prefers the Colour entry). Both write DMX channel 6, so the wire value would depend on
        // which FxTarget was inferred last. Asserting the two paths *agree* is what pins it,
        // whichever of them is right.
        val (fixtures, store) = rig()
        store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite("hex-a", "white", slider(10), layerIndex = 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "rgbColour", colour(Color.RED, white = 250), layerIndex = 1),
            )
        )

        val viaSlider = sliderOut(fixtures, store, "white")
        val viaColour = (
            ColourTarget("hex-a").composeProgrammerOver(
                fixtures.hex(), store, FxOutput.Colour(ExtendedColour(Color.BLACK)),
            ) as FxOutput.Colour
            ).color.white.toInt()

        assertEquals(viaSlider, viaColour, "the two overrides must not disagree about one channel")
        assertEquals(250, viaSlider, "the later layer owns the channel")
    }

    @Test
    fun `a layer slot is never mistaken for absent`() {
        // `Long.MIN_VALUE` is the "nothing here" sentinel in every override. A layer slot stamped
        // with it would lose to any sideband value — including a stale one — instead of winning.
        val (fixtures, store) = rig()
        store.layer("dimmer", slider(200))

        val slot = store.get("hex-a", "dimmer")!!
        assertEquals(ProgrammerOwner.LAYERS, slot.owner)
        assert(slot.seq != Long.MIN_VALUE) { "must not collide with the absence sentinel" }
        assert(slot.seq < 1L) { "must sit below every real write, whose seq starts at 1" }
        assertEquals(200, sliderOut(fixtures, store, "dimmer"))
    }

    // ─── The raw-channel sideband ────────────────────────────────────────

    @Test
    fun `an older raw channel drag still beats a layer added afterwards`() {
        // The sideband is a local write like any other, so the order-independence rule covers it:
        // the operator's drag holds until they release it. Provenance names the layer that *would*
        // be showing, which is how they find out why.
        val (fixtures, store) = rig()
        store.putChannel(ProgrammerOwner.WEB, universe.universe, 1, 77u)
        store.layer("dimmer", slider(200))

        assertEquals(77, sliderOut(fixtures, store, "dimmer"))
    }

    // ─── PositionTarget and SettingTarget ───────────────────────────────

    @Test
    fun `a local setting write beats a layer's`() {
        val (fixtures, store) = rig()
        store.layer("mode", CueAssignmentResolver.PropertyValue.Setting(9u))
        store.local("mode", CueAssignmentResolver.PropertyValue.Setting(3u))

        val out = SettingTarget("hex-a", "mode")
            .composeProgrammerOver(fixtures.hex(), store, FxOutput.Slider(0u))
        assertEquals(3, (out as FxOutput.Slider).value.toInt())
    }

    @Test
    fun `a key the layer stack no longer names releases to what is below`() {
        // putLayerSlots is a whole-set replacement, so a key dropping out of the cooked set must
        // lose its slot — otherwise removing a layer would leave its values stranded on stage.
        val (fixtures, store) = rig()
        store.layer("dimmer", slider(200))
        assertEquals(200, sliderOut(fixtures, store, "dimmer"))

        val moved = store.putLayerSlots(emptyList())

        assertEquals(0, sliderOut(fixtures, store, "dimmer", below = 0))
        assertEquals(
            setOf(CueAssignmentResolver.Key.fixture("hex-a", "dimmer")), moved,
            "the caller is told which keys to republish",
        )
    }

    @Test
    fun `replacing the stack reports only the keys whose value actually moved`() {
        val (fixtures, store) = rig()
        store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite("hex-a", "dimmer", slider(200), 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "white", slider(10), 0),
            )
        )

        val moved = store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite("hex-a", "dimmer", slider(200), 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "white", slider(80), 0),
            )
        )

        assertEquals(
            setOf(CueAssignmentResolver.Key.fixture("hex-a", "white")), moved,
            "an unchanged key is not republished",
        )
        assertEquals(80, sliderOut(fixtures, store, "white"))
    }

    @Test
    fun `a layer slot is not touched, so Record does not flatten it into a row`() {
        // Record saves the layer *stack* as layers; only the operator's own rows are rows. The
        // UNPARK owner uses the same flag for the same reason.
        val (_, store) = rig()
        store.layer("dimmer", slider(200))

        assertEquals(false, store.get("hex-a", "dimmer")!!.touched)
    }

    @Test
    fun `one whole-stack replacement bumps the epoch once`() {
        // `epoch` gates the per-tick effect-suppression snapshot, so a bump per key would rebuild
        // it repeatedly against a half-applied stack.
        val (_, store) = rig()
        val before = store.epoch
        store.putLayerSlots(
            listOf(
                ProgrammerStore.LayerSlotWrite("hex-a", "dimmer", slider(200), 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "white", slider(10), 0),
                ProgrammerStore.LayerSlotWrite("hex-a", "amber", slider(20), 0),
            )
        )

        assertEquals(before + 1, store.epoch)
    }
}
