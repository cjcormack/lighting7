package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `@FixtureProperty(degMin =, degMax =)` on a ZOOM slider: the full beam angle at DMX min and max,
 * which the Stage view turns a zoom channel into. A zoom that declares neither is silently inert —
 * the view keeps the family's fixed angle — which is how the Source Four Revolution's went unnoticed
 * (fixture optics plan D2). [FocusRangeTest] closes the same hole for focus.
 */
class ZoomAnglesTest {

    /**
     * Stepped zooms, which a continuous angle range would misdraw. The Robe ColorSpot 575's zoom is
     * three steps, not a sweep; the fixture optics plan's session 5 makes it a setting whose options
     * carry `zoomDeg`, and removes this exemption.
     */
    private val steppedZoomTypes = setOf("robe-color-spot-575-mode-2")

    @Test
    fun `every zoom slider in the library declares its angles`() {
        val zooms = FixtureTypeRegistry.allTypes.flatMap { type ->
            type.properties.filterIsInstance<SliderPropertyDescriptor>()
                .filter { it.category == "zoom" }
                .map { type.typeKey to it }
        }
        assertTrue(
            zooms.any { (typeKey, _) -> typeKey == "etc-source4-revolution-base-frame" },
            "the Revolution's zoom is a ZOOM slider: $zooms",
        )

        for ((typeKey, slider) in zooms) {
            if (typeKey in steppedZoomTypes) continue
            val degMin = assertNotNull(slider.degMin, "$typeKey's ${slider.name} declares no angle at DMX min")
            val degMax = assertNotNull(slider.degMax, "$typeKey's ${slider.name} declares no angle at DMX max")
            assertTrue(
                degMin > 0.0 && degMax > 0.0 && degMin < 180.0 && degMax < 180.0 && degMin != degMax,
                "$typeKey's ${slider.name} range $degMin..$degMax is no zoom's",
            )
        }
    }
}
