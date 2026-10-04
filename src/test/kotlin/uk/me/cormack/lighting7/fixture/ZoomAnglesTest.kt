package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureZoomSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A ZOOM channel's angles (fixture optics plan D2), in either of its two forms: a **slider** declares
 * the full beam angle at DMX min and max (`@FixtureProperty(degMin =, degMax =)`), a **stepped zoom**
 * is a setting whose every option carries its angle (`DmxFixtureZoomSettingValue.zoomDeg`). A zoom
 * that declares neither is silently inert — the view keeps the family's fixed angle — which is how
 * the Source Four Revolution's went unnoticed. [FocusRangeTest] closes the same hole for focus.
 */
class ZoomAnglesTest {

    private val universe = Universe(0, 0)

    private fun isAngle(deg: Double) = deg > 0.0 && deg < 180.0

    @Test
    fun `every zoom in the library declares its angles, as a slider's range or a stepped zoom's options`() {
        var sliders = 0
        var stepped = 0
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            // A fixture's own properties and each of its cells': a zoom is one lens's.
            val lenses = buildList {
                add(type.typeKey to fixture)
                if (fixture is MultiElementFixture<*>) {
                    fixture.elements.forEachIndexed { i, element -> add("${type.typeKey}[$i]" to element) }
                }
            }
            for ((where, owner) in lenses) {
                for (p in FixturePropertyCatalogue.of(owner::class).all) {
                    if (p.category != PropertyCategory.ZOOM || p.fineOf != null) continue
                    when (val backing = p.classProperty.getter.call(owner)) {
                        is DmxSlider -> {
                            sliders++
                            val degMin = assertNotNull(p.degMin, "$where's ${p.name} declares no angle at DMX min")
                            val degMax = assertNotNull(p.degMax, "$where's ${p.name} declares no angle at DMX max")
                            assertTrue(
                                isAngle(degMin) && isAngle(degMax) && degMin != degMax,
                                "$where's ${p.name} range $degMin..$degMax is no zoom's",
                            )
                        }
                        is DmxFixtureSetting<*> -> {
                            stepped++
                            assertNull(p.degMin, "$where's ${p.name} is a stepped zoom: its options carry the angles")
                            assertNull(p.degMax, "$where's ${p.name} is a stepped zoom: its options carry the angles")
                            for (option in backing.sortedValues) {
                                val step = option as? DmxFixtureZoomSettingValue
                                assertNotNull(step, "$where's ${p.name} option ${option.name} declares no zoomDeg")
                                assertTrue(isAngle(step.zoomDeg), "$where's ${p.name} ${option.name} ${step.zoomDeg}° is no zoom's")
                            }
                            val angles = backing.sortedValues.map { (it as DmxFixtureZoomSettingValue).zoomDeg }.toSet()
                            assertTrue(angles.size >= 2, "$where's ${p.name} is a zoom with one angle: $angles")
                        }
                        else -> error("$where's ${p.name} is a zoom on neither a slider nor a setting: $backing")
                    }
                }
            }
        }
        assertTrue(sliders >= 1, "the Revolution's zoom is a ZOOM slider")
        assertTrue(stepped >= 1, "the Robe ColorSpot's zoom is a stepped zoom")
    }

    @Test
    fun `only a zoom setting's options carry zoomDeg on the wire`() {
        for (type in FixtureTypeRegistry.allTypes) {
            for (setting in type.properties.filterIsInstance<SettingPropertyDescriptor>()) {
                if (setting.category == "zoom") continue
                assertTrue(
                    setting.options.all { it.zoomDeg == null },
                    "${type.typeKey}'s ${setting.name} is no zoom but carries zoomDeg",
                )
            }
        }
    }

    @Test
    fun `the Robe's stepped zoom reaches the descriptor, 15, 18 and 22 degrees with and without focus correction`() {
        val robe = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
        val zoom = robe.generatePropertyDescriptors().filterIsInstance<SettingPropertyDescriptor>().single { it.name == "zoom" }
        assertEquals("zoom", zoom.category)
        assertEquals(
            listOf(0 to 15.0, 40 to 18.0, 80 to 22.0, 128 to 15.0, 170 to 18.0, 220 to 22.0),
            zoom.options.map { it.level to it.zoomDeg },
        )
    }
}
