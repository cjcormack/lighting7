package uk.me.cormack.lighting7.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class CompositionRuleTest {

    @Test
    fun `HTP categories from the spec`() {
        assertEquals(CompositionRule.HTP, AttributeCategory.DIMMER.defaultComposition)
        assertEquals(CompositionRule.HTP, AttributeCategory.UV.defaultComposition)
        assertEquals(CompositionRule.HTP, AttributeCategory.STROBE.defaultComposition)
    }

    @Test
    fun `LTP categories from the spec`() {
        assertEquals(CompositionRule.LTP, AttributeCategory.COLOUR.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.AMBER.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.WHITE.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.PAN.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.TILT.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.PAN_FINE.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.TILT_FINE.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.SPEED.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.SETTING.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.OTHER.defaultComposition)
    }

    @Test
    fun `beam-shaping categories are all LTP — mechanical selections and positions`() {
        assertEquals(CompositionRule.LTP, AttributeCategory.GOBO.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.GOBO_ROTATION.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.GOBO_ROTATION_MODE.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.PRISM.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.PRISM_ROTATION.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.FOCUS.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.ZOOM.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.IRIS.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.FROST.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.SHUTTER.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.SHUTTER_ROTATION.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.LED_MACRO.defaultComposition)
        assertEquals(CompositionRule.LTP, AttributeCategory.MOVEMENT_MACRO.defaultComposition)
    }
}
