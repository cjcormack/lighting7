package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@FixtureProperty(blade =, depthMax =)` on the framing-shutter categories (fixture optics plan D4):
 * a SHUTTER slider is a blade's insertion and declares its depth at DMX max, a SHUTTER_ROTATION
 * slider its angle and declares its degrees. A blade that names no side, or declares no scale, is
 * silently inert in the Stage view — the gap [ZoomAnglesTest] and [FocusRangeTest] close for zoom
 * and focus.
 */
class ShutterBladesTest {

    private val universe = Universe(0, 0)

    @Test
    fun `the blade and its depth reflect onto the property and the descriptor, and the sentinels as unset`() {
        val rev = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        val pos = assertNotNull(rev.fixtureProperty("frame3Pos"))
        assertEquals(Blade.LEFT, pos.blade)
        assertEquals(0.5, pos.depthMax)

        val rot = assertNotNull(rev.fixtureProperty("frame3Rot"))
        assertEquals(Blade.LEFT, rot.blade)
        assertNull(rot.depthMax, "NaN reflects as unset")

        val zoom = assertNotNull(rev.fixtureProperty("zoom"))
        assertNull(zoom.blade, "Blade.NONE reflects as unset")
        assertNull(zoom.depthMax)

        val sliders = rev.generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().associateBy { it.name }
        val posDescriptor = assertNotNull(sliders["frame3Pos"])
        assertEquals("shutter", posDescriptor.category)
        assertEquals("LEFT", posDescriptor.blade)
        assertEquals(0.5, posDescriptor.depthMax)
        val rotDescriptor = assertNotNull(sliders["frame3Rot"])
        assertEquals("shutter_rotation", rotDescriptor.category)
        assertEquals("LEFT", rotDescriptor.blade)
        assertNull(rotDescriptor.depthMax)
        assertNull(sliders["zoom"]?.blade)
        assertNull(sliders["zoom"]?.depthMax)
    }

    @Test
    fun `every framing shutter in the library names its blade and declares its scale, and nothing else does`() {
        var shutterTypes = 0
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            // A fixture's own properties and each of its cells': a shutter is one blade of one gate.
            val gates = buildList {
                add(type.typeKey to fixture.fixtureProperties)
                if (fixture is MultiElementFixture<*>) {
                    fixture.elements.forEachIndexed { i, element ->
                        add("${type.typeKey}[$i]" to FixturePropertyCatalogue.of(element::class).all)
                    }
                }
            }
            for ((where, properties) in gates) {
                val insertions = properties.filter { it.category == PropertyCategory.SHUTTER && it.fineOf == null }
                val rotations = properties.filter { it.category == PropertyCategory.SHUTTER_ROTATION && it.fineOf == null }
                for (p in insertions) {
                    assertNotNull(p.blade, "$where's ${p.name} is a shutter that names no blade")
                    val depth = assertNotNull(p.depthMax, "$where's ${p.name} declares no depth at DMX max")
                    assertTrue(depth > 0.0 && depth <= 1.0, "$where's ${p.name} depth $depth is no blade's")
                }
                for (p in rotations) {
                    assertNotNull(p.blade, "$where's ${p.name} is a shutter rotation that names no blade")
                    val degMin = assertNotNull(p.degMin, "$where's ${p.name} declares no angle at DMX min")
                    val degMax = assertNotNull(p.degMax, "$where's ${p.name} declares no angle at DMX max")
                    assertTrue(degMin != degMax, "$where's ${p.name} range $degMin..$degMax does not turn")
                    assertNull(p.depthMax, "$where's ${p.name} is a rotation, not an insertion")
                }
                for ((label, group) in listOf("insertion" to insertions, "rotation" to rotations)) {
                    val repeated = group.groupBy { it.blade }.filterValues { it.size > 1 }
                    assertTrue(repeated.isEmpty(), "$where declares more than one $label for a blade: $repeated")
                }
                for (p in properties - (insertions + rotations).toSet()) {
                    if (p.fineOf != null &&
                        (p.category == PropertyCategory.SHUTTER || p.category == PropertyCategory.SHUTTER_ROTATION)
                    ) {
                        continue
                    }
                    assertNull(p.blade, "$where's ${p.name} is no framing shutter but names a blade")
                    assertNull(p.depthMax, "$where's ${p.name} is no framing shutter but declares a depth")
                }
                if (insertions.isNotEmpty() || rotations.isNotEmpty()) {
                    shutterTypes++
                    assertFalse(
                        type.acceptsLantern,
                        "${type.typeKey} drives its blades from DMX, so it takes no lantern's focus blades",
                    )
                }
            }
        }
        assertTrue(shutterTypes >= 1, "the Revolution's frames are framing shutters")
    }
}
