package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.dmx.VarytecEasymoveXl60SpotFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every COLOUR option says what it puts in the beam (fixture optics plan D8): a `#RRGGBB`
 * `colourPreview`, or the `noColour` marker for a band with no single colour — a scroll, a random or
 * rainbow program, an auto change, a band handing colour to other channels. Never neither: an option
 * that says nothing drew its beam black, which is how every Varytec beam and every scroll band did.
 */
class ColourPreviewTest {

    private val universe = Universe(0, 0)
    private val hex = Regex("^#[0-9A-Fa-f]{6}$")

    @Test
    fun `every colour option in the library has a preview or is marked as a band with no single colour`() {
        var settings = 0
        var noColourBands = 0
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            val owners = buildList {
                add(type.typeKey to fixture)
                if (fixture is MultiElementFixture<*>) {
                    fixture.elements.forEachIndexed { i, element -> add("${type.typeKey}[$i]" to element) }
                }
            }
            for ((where, owner) in owners) {
                for (p in FixturePropertyCatalogue.of(owner::class).all) {
                    val setting = p.classProperty.getter.call(owner) as? DmxFixtureSetting<*> ?: continue
                    for (option in setting.sortedValues) {
                        val colour = option as? DmxFixtureColourSettingValue
                        colour?.colourPreview?.let { preview ->
                            assertTrue(hex.matches(preview), "$where's ${p.name} ${option.name} preview $preview is not #RRGGBB")
                        }
                        if (p.category != PropertyCategory.COLOUR) {
                            assertTrue(colour?.noColour != true, "$where's ${p.name} is no colour setting but marks ${option.name} noColour")
                            continue
                        }
                        if (colour == null) fail("$where's ${p.name} is a COLOUR setting whose ${option.name} carries no colour at all")
                        val previewed = colour.colourPreview != null
                        assertTrue(
                            previewed != colour.noColour,
                            "$where's ${p.name} ${option.name} must carry a preview or be marked noColour, not " +
                                if (previewed) "both" else "neither",
                        )
                        if (colour.noColour) noColourBands++
                    }
                    if (p.category == PropertyCategory.COLOUR) settings++
                }
            }
        }
        assertTrue(settings >= 10, "the library's colour wheels and presets: $settings")
        assertTrue(noColourBands >= 10, "scroll, random and rainbow bands: $noColourBands")
    }

    @Test
    fun `the marker reaches the descriptor, and only where it is set`() {
        val robe = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
        val wheel = robe.generatePropertyDescriptors().filterIsInstance<SettingPropertyDescriptor>().single { it.name == "colour1" }
        val byName = wheel.options.associateBy { it.name }
        assertEquals(true, byName.getValue("SCROLL_CW").noColour)
        assertEquals(true, byName.getValue("AUTO_RANDOM").noColour)
        assertNull(byName.getValue("SCROLL_CW").colourPreview)
        assertNull(byName.getValue("RED").noColour, "a colour carries its preview and no marker")
        assertEquals("#FF0000", byName.getValue("RED").colourPreview)
    }

    @Test
    fun `the Varytec's wheel draws a colour at every indexed position, never black`() {
        val varytec = VarytecEasymoveXl60SpotFixture.Mode11Ch(universe, "vary-1", "Vary 1", 1)
        val wheel = varytec.generatePropertyDescriptors().filterIsInstance<SettingPropertyDescriptor>().single { it.category == "colour" }
        for (option in wheel.options) {
            if (option.noColour == true) continue
            val preview = option.colourPreview
            assertTrue(preview != null && !preview.equals("#000000", ignoreCase = true), "${option.name} draws $preview")
        }
        assertEquals(listOf("RAINBOW_FORWARD", "RAINBOW_REVERSE"), wheel.options.filter { it.noColour == true }.map { it.name })
    }
}
