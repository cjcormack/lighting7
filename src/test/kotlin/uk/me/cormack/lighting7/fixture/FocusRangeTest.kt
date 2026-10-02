package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@FixtureProperty(focusNearM =, focusFarM =)`: the focus range a FOCUS slider declares, which the
 * Stage view turns a focus channel's DMX into a focal distance over.
 */
class FocusRangeTest {

    private val universe = Universe(0, 0)

    @Test
    fun `the declared range reflects onto the property, and NaN reflects as unset`() {
        val mac = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "Mac 1", 1)
        val macFocus = assertNotNull(mac.fixtureProperty("focus"))
        assertEquals(2.0, macFocus.focusNearM)
        assertEquals(40.0, macFocus.focusFarM)
        assertTrue(macFocus.inverted, "the MAC 250's DMX 0 is infinity")

        val rev = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        val revFocus = assertNotNull(rev.fixtureProperty("focus"))
        assertEquals(2.0, revFocus.focusNearM)
        assertEquals(40.0, revFocus.focusFarM)
        assertFalse(revFocus.inverted)

        val zoom = assertNotNull(rev.fixtureProperty("zoom"))
        assertNull(zoom.focusNearM)
        assertNull(zoom.focusFarM)
    }

    @Test
    fun `every focus slider in the library declares a usable range, and nothing else declares one`() {
        val sliders = FixtureTypeRegistry.allTypes.flatMap { type ->
            type.properties.filterIsInstance<SliderPropertyDescriptor>().map { type.typeKey to it }
        }
        val focus = sliders.filter { (_, slider) -> slider.category == "focus" }
        assertTrue(focus.size >= 5, "S4 Rev, MAC 250, ColorSpot 575 and both Fusion modes: $focus")

        for ((typeKey, slider) in sliders) {
            if (slider.category == "focus") {
                val near = assertNotNull(slider.focusNearM, "$typeKey's focus declares no near focus")
                val far = assertNotNull(slider.focusFarM, "$typeKey's focus declares no far focus")
                assertTrue(near > 0.0 && far > near, "$typeKey's focus range $near..$far is no lens's")
            } else {
                assertNull(slider.focusNearM, "$typeKey's ${slider.name} is not a focus slider")
                assertNull(slider.focusFarM, "$typeKey's ${slider.name} is not a focus slider")
            }
        }
    }
}
