package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SliderAttributeDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `@FixtureAttribute.forwardDeg`: where a pan's head faces the body's front, when not mid-travel. */
class PanFrontTest {

    private val universe = Universe(0, 0)

    /** Every type and every head of one, with the properties its catalogue declares. */
    private fun declared(): List<Pair<String, Fixture.Attribute>> = buildList {
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            FixtureAttributeCatalogue.of(fixture::class).all.forEach { add(type.typeKey to it) }
            if (fixture is MultiElementFixture<*>) {
                fixture.elements.forEachIndexed { i, element ->
                    FixtureAttributeCatalogue.of(element::class).all.forEach { add("${type.typeKey}[$i]" to it) }
                }
            }
        }
    }

    @Test
    fun `a front is declared only on a pan with a degree range, inside that range`() {
        for ((where, attribute) in declared()) {
            val forward = attribute.forwardDeg ?: continue
            assertEquals(AttributeCategory.PAN, attribute.category, "$where ${attribute.name}: only a pan has a front")
            assertEquals(PanTiltAxis.PAN, attribute.axis, "$where ${attribute.name}: only a pan has a front")
            val min = assertNotNull(attribute.degMin, "$where ${attribute.name}: a front needs a degree range")
            val max = assertNotNull(attribute.degMax, "$where ${attribute.name}: a front needs a degree range")
            assertTrue(forward in minOf(min, max)..maxOf(min, max), "$where ${attribute.name}: $forward outside $min..$max")
        }
    }

    @Test
    fun `both Shehds modes face their front at 180 degrees of pan, and say so on the wire`() {
        val types = FixtureTypeRegistry.allTypes.associateBy { it.typeKey }
        for (key in listOf("shehds-led19-rgbw-24ch", "shehds-led19-rgbw-16ch")) {
            val pan = types.getValue(key).attributes.filterIsInstance<SliderAttributeDescriptor>().single { it.axis == "PAN" }
            assertEquals(180.0, pan.forwardDeg, key)
        }
    }
}
