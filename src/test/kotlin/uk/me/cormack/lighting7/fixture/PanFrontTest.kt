package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `@FixtureProperty.forwardDeg`: where a pan's head faces the body's front, when not mid-travel. */
class PanFrontTest {

    private val universe = Universe(0, 0)

    /** Every type and every head of one, with the properties its catalogue declares. */
    private fun declared(): List<Pair<String, Fixture.Property>> = buildList {
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            FixturePropertyCatalogue.of(fixture::class).all.forEach { add(type.typeKey to it) }
            if (fixture is MultiElementFixture<*>) {
                fixture.elements.forEachIndexed { i, element ->
                    FixturePropertyCatalogue.of(element::class).all.forEach { add("${type.typeKey}[$i]" to it) }
                }
            }
        }
    }

    @Test
    fun `a front is declared only on a pan with a degree range, inside that range`() {
        for ((where, property) in declared()) {
            val forward = property.forwardDeg ?: continue
            assertEquals(PropertyCategory.PAN, property.category, "$where ${property.name}: only a pan has a front")
            assertEquals(PanTiltAxis.PAN, property.axis, "$where ${property.name}: only a pan has a front")
            val min = assertNotNull(property.degMin, "$where ${property.name}: a front needs a degree range")
            val max = assertNotNull(property.degMax, "$where ${property.name}: a front needs a degree range")
            assertTrue(forward in minOf(min, max)..maxOf(min, max), "$where ${property.name}: $forward outside $min..$max")
        }
    }

    @Test
    fun `both Shehds modes face their front at 180 degrees of pan, and say so on the wire`() {
        val types = FixtureTypeRegistry.allTypes.associateBy { it.typeKey }
        for (key in listOf("shehds-led19-rgbw-24ch", "shehds-led19-rgbw-16ch")) {
            val pan = types.getValue(key).properties.filterIsInstance<SliderPropertyDescriptor>().single { it.axis == "PAN" }
            assertEquals(180.0, pan.forwardDeg, key)
        }
    }
}
