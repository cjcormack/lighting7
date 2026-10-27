package uk.me.cormack.lighting7.routes

import uk.me.cormack.lighting7.fx.CycleLength
import uk.me.cormack.lighting7.fx.Fx
import uk.me.cormack.lighting7.fx.FxContext
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
class RecordedFxCategoryTest {

    private fun running(id: String, outputType: FxOutputType, target: FxTarget) =
        FxInstance(
            object : Fx {
                override val name = id
                override val outputType = outputType
                override fun calculate(phase: Double, context: FxContext): FxOutput =
                    FxOutput.Steps(0, 0, 0.0, StepList.EMPTY)
            },
            target,
            FxTiming(CycleLength.ONE_BAR),
        ).apply { registrationId = id }

    @Test
    fun `Slot Steps is a beam effect whatever setting it steps`() {
        val gobo = running(SLOT_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "gobo"))
        val wheel = running(SLOT_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "colourWheel"))
        assertEquals("beam", recordedFxFamily(gobo))
        assertEquals("beam", recordedFxFamily(wheel))
    }

    @Test
    fun `Palette Steps is its palettes' family, read off the attribute it drives`() {
        assertEquals("intensity", recordedFxFamily(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SliderTarget(FxTargetRef.fixture("hex"), "strobe"))))
        assertEquals("beam", recordedFxFamily(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SettingTarget(FxTargetRef.fixture("spot"), "prism"))))
        assertEquals("intensity", recordedFxFamily(running(PALETTE_STEPS_ID, FxOutputType.STEPS, SliderTarget(FxTargetRef.fixture("hex"), "dimmer"))))
    }

    @Test
    fun `Wave keeps its library family on every attribute, a beam one included (fx-settings plan D5)`() {
        for (attribute in listOf("dimmer", "uv", "zoom", "focus")) {
            assertEquals("intensity", recordedFxFamily(running("Wave", FxOutputType.SLIDER, SliderTarget(FxTargetRef.fixture("spot"), attribute))))
        }
    }

    @Test
    fun `any other effect keeps the attribute name's category`() {
        val flicker = running("Flicker", FxOutputType.SLIDER, SliderTarget(FxTargetRef.fixture("hex"), "strobe"))
        assertEquals("controls", recordedFxFamily(flicker))
    }
}
