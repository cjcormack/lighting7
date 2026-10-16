package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.BeatDivision
import uk.me.cormack.lighting7.fx.Effect
import uk.me.cormack.lighting7.fx.EffectContext
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.FxOutput
import uk.me.cormack.lighting7.fx.FxOutputType
import uk.me.cormack.lighting7.fx.FxTarget
import uk.me.cormack.lighting7.fx.FxTargetRef
import uk.me.cormack.lighting7.fx.FxTiming
import uk.me.cormack.lighting7.fx.PALETTE_STEPS_ID
import uk.me.cormack.lighting7.fx.SLOT_STEPS_ID
import uk.me.cormack.lighting7.fx.SettingTarget
import uk.me.cormack.lighting7.fx.SliderTarget
import uk.me.cormack.lighting7.fx.StepList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The category Record files a running effect under (effects plan §3.6): a step effect's own, never
 * the `controls` its property name alone would give a strobe or a gobo.
 */
class RecordedEffectCategoryTest {

    private fun running(id: String, outputType: FxOutputType, target: FxTarget) =
        FxInstance(
            object : Effect {
                override val name = id
                override val outputType = outputType
                override fun calculate(phase: Double, context: EffectContext): FxOutput =
                    FxOutput.Steps(0, 0, 0.0, StepList.EMPTY)
            },
            target,
            FxTiming(BeatDivision.ONE_BAR),
        ).apply { registrationId = id }

    @Test
    fun `Slot Steps is a beam effect whatever setting it steps`() {
        val gobo = running(SLOT_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "gobo"))
        val wheel = running(SLOT_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "colourWheel"))
        assertEquals("beam", recordedEffectCategory(gobo))
        assertEquals("beam", recordedEffectCategory(wheel))
    }

    @Test
    fun `Palette Steps is its palettes' family, read off the property it drives`() {
        assertEquals("dimmer", recordedEffectCategory(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SliderTarget(FxTargetRef.fixture("hex"), "strobe"))))
        assertEquals("beam", recordedEffectCategory(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "prism"))))
        assertEquals("dimmer", recordedEffectCategory(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SliderTarget(FxTargetRef.fixture("hex"), "dimmer"))))
    }

    @Test
    fun `any other effect keeps the property name's category`() {
        val pulse = running("Pulse", FxOutputType.SLIDER, SliderTarget(FxTargetRef.fixture("hex"), "strobe"))
        assertEquals("controls", recordedEffectCategory(pulse))
    }
}
