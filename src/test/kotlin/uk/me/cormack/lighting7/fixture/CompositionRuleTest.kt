package uk.me.cormack.lighting7.fixture

import kotlin.test.Test
import kotlin.test.assertEquals

class CompositionRuleTest {

    @Test
    fun `HTP categories from the spec`() {
        assertEquals(CompositionRule.HTP, PropertyCategory.DIMMER.defaultComposition)
        assertEquals(CompositionRule.HTP, PropertyCategory.UV.defaultComposition)
        assertEquals(CompositionRule.HTP, PropertyCategory.STROBE.defaultComposition)
    }

    @Test
    fun `LTP categories from the spec`() {
        assertEquals(CompositionRule.LTP, PropertyCategory.COLOUR.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.AMBER.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.WHITE.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.PAN.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.TILT.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.PAN_FINE.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.TILT_FINE.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.SPEED.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.SETTING.defaultComposition)
        assertEquals(CompositionRule.LTP, PropertyCategory.OTHER.defaultComposition)
    }
}
