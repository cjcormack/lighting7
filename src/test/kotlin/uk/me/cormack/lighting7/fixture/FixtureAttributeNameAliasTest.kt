package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fx.canonicalAttributeName
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * No `@FixtureAttribute` may be named an alias. Stored rows (cue rows, Look rows, programmer entries,
 * bindings) and FX targets name a property by its Kotlin name, and [canonicalAttributeName] reads
 * `colour` / `color` / `rgbcolour` (any case) as "this head's RGB colour", rewriting it to
 * `rgbColour` at every lookup. A property *named* one of those is unreachable: its rows are
 * rewritten to a name the head does not have and silently dropped. Six colour-wheel properties, on
 * five fixture families, were once named `colour` and lost every recorded row that way
 * (`FU-LOOK-COLOUR-WHEEL-ROW`); a wheel is `colourWheel`.
 *
 * The rule is asked of [canonicalAttributeName] itself rather than of a copy of its list, so a new
 * alias is guarded the day it is added. `rgbColour` is its own canonical form and stays allowed.
 */
class FixtureAttributeNameAliasTest {

    @Test
    fun `no fixture or element attribute is named an alias, because the alias would rewrite the name`() {
        val classes = LinkedHashMap<KClass<*>, String>()
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, Universe(0, 0), "probe", "Probe", 1)
            classes.putIfAbsent(fixture::class, type.typeKey)
            if (fixture is MultiElementFixture<*>) {
                for (element in fixture.elements) classes.putIfAbsent(element::class, "${type.typeKey} element")
            }
        }
        assertTrue(classes.values.any { it.endsWith(" element") }, "the walk reaches element classes")

        val offenders = classes.flatMap { (klass, origin) ->
            FixtureAttributeCatalogue.of(klass).all
                .filter { canonicalAttributeName(it.name) != it.name }
                .map { "$origin (${klass.simpleName}).${it.name} → ${canonicalAttributeName(it.name)}" }
        }
        assertEquals(
            emptyList(), offenders,
            "a property named an alias is rewritten by canonicalAttributeName and never reached — rename it (a colour wheel is `colourWheel`)",
        )
    }

    @Test
    fun `the guard's oracle flags every spelling of the alias and nothing else`() {
        for (name in listOf("colour", "Colour", "color", "COLOR", "rgbcolour", "RGBColour")) {
            assertTrue(canonicalAttributeName(name) != name, "'$name' is an alias")
        }
        for (name in listOf("rgbColour", "colourWheel", "colourMacro", "dimmer")) {
            assertEquals(name, canonicalAttributeName(name), "'$name' is not rewritten")
        }
    }
}
