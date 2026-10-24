package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.show.Fixtures
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A removal racing a compose pass: the pass reads the effect list, another thread removes an FX
 * and resets what it covered, and the pass then commits the frame it built from the old list. With
 * nothing left running every later pass is skipped, so unless the racing pass repeats the reset
 * the removed FX's last level stays on the channel.
 *
 * The interleaving is forced, not hoped for: the FX's own `calculate` (which runs after the pass
 * has read the list and before it commits) runs the removal on another thread and waits for it.
 */
class FxRemovalRaceTest {

    private val universe = Universe(0, 0)

    private fun newRig(): Pair<MockDmxController, FxEngine> {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.register {
            addController(controller)
            addFixture(HexFixture(universe, "hex-a", "Hex A", 1))
        }
        return controller to FxEngine(fixtures = fixtures)
    }

    /** A constant 200 that runs [onFirstCalculate] inside its first pass. */
    private class RacingEffect(private val onFirstCalculate: () -> Unit) : Effect {
        override val name = "Racing"
        override val outputType = FxOutputType.SLIDER
        private var raced = false

        override fun calculate(phase: Double, context: EffectContext): FxOutput {
            if (!raced) {
                raced = true
                onFirstCalculate()
            }
            return FxOutput.Slider(200u)
        }
    }

    private fun dimmerFx(effect: Effect, property: String = "dimmer") =
        FxInstance(effect, SliderTarget("hex-a", property), FxTiming(beatDivision = 1.0)).also {
            it.timingSource = TimingSource.WALL_CLOCK
        }

    private fun MockDmxController.dimmer() = currentValues.getValue(1).toInt()
    private fun MockDmxController.uv() = currentValues.getValue(UV_CHANNEL).toInt()

    private fun assertRemovalMidPassLeavesNoStaleFrame(remove: (FxEngine) -> Unit) {
        val (controller, engine) = newRig()
        engine.addEffect(dimmerFx(RacingEffect { thread { remove(engine) }.join() }))

        assertTrue(engine.processWallClockTick(T0), "the pass ran")
        assertTrue(engine.getActiveEffects().isEmpty())
        assertEquals(0, controller.dimmer(), "the racing pass's frame stayed over the removal's reset")

        assertFalse(engine.processWallClockTick(T0 + 20), "nothing runs, so the next pass is skipped")
        assertEquals(0, controller.dimmer())
    }

    @Test
    fun `clearAllEffects during a pass leaves the dimmer at the layer below`() =
        assertRemovalMidPassLeavesNoStaleFrame { it.clearAllEffects() }

    @Test
    fun `removeEffect during a pass leaves the dimmer at the layer below`() =
        assertRemovalMidPassLeavesNoStaleFrame { it.removeEffect(it.getActiveEffects().single().id) }

    @Test
    fun `a pass whose list never held the removed FX does not repeat its reset`() {
        val (controller, engine) = newRig()
        // Added and removed while the pass runs, so the pass's list never held it; the channel is
        // then written by something else, which the pass must leave alone.
        engine.addEffect(
            dimmerFx(
                RacingEffect {
                    thread {
                        val late = engine.addEffect(dimmerFx(RacingEffect {}))
                        engine.removeEffect(late)
                    }.join()
                    controller.setValues(listOf(1 to ChannelChange(150u, 0)))
                },
                property = "uv",
            ),
        )

        assertTrue(engine.processWallClockTick(T0))
        assertEquals(150, controller.dimmer())
    }

    @Test
    fun `an FX swapped by an edit and then removed during a pass leaves the dimmer at the layer below`() =
        assertRemovalMidPassLeavesNoStaleFrame {
            val id = it.getActiveEffects().single().id
            it.updateEffect(id, newBlendMode = BlendMode.ADDITIVE)
            it.removeEffect(id)
        }

    @Test
    fun `an FX split into copies and then removed during a pass leaves the dimmer at the layer below`() =
        assertRemovalMidPassLeavesNoStaleFrame {
            val parent = it.getActiveEffects().single()
            it.replaceEffects(mapOf(parent to listOf(dimmerFx(RacingEffect {}))))
            it.removeEffects(it.getActiveEffects().map { copy -> copy.id })
        }

    @Test
    fun `a batch removal during a pass resets only what the pass painted`() {
        val (controller, engine) = newRig()
        engine.addEffect(
            dimmerFx(
                RacingEffect {
                    thread {
                        engine.addEffect(dimmerFx(RacingEffect {}))
                        engine.clearAllEffects()
                    }.join()
                    controller.setValues(listOf(1 to ChannelChange(150u, 0)))
                },
                property = "uv",
            ),
        )

        assertTrue(engine.processWallClockTick(T0))
        assertEquals(150, controller.dimmer(), "the dimmer FX came and went after the pass read its list")
        assertEquals(0, controller.uv(), "the pass painted the UV FX, so it repeats that reset")
    }

    private companion object {
        const val T0 = 1_000_000L

        /** [HexFixture.uv] of the hex patched at channel 1. */
        const val UV_CHANNEL = 7
    }
}
