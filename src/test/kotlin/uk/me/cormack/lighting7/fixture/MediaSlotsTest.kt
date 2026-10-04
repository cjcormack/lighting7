package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureGoboSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.Source4RevolutionFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `@FixtureProperty(media =)` — a loadable setting (fixture optics plan D6). The rules a declaration
 * obeys, as `docs/fixtures-engineering.md` §"Fitted media" states them:
 *
 * 1. `media` sits only on a **setting-backed** property of the fixture itself, never on a slider and
 *    never on a cell: fitted media is the unit's, and a cell is no unit.
 * 2. Its option type can carry what it takes — `GEL` and `GOBO_OR_GEL` a colour
 *    ([DmxFixtureColourSettingValue]), `GOBO` and `GOBO_OR_GEL` a pattern ([DmxFixtureGoboSettingValue])
 *    — so a slot's stock content has somewhere to live and a fitted one something to replace.
 * 3. At least one option is a slot (`loadable`).
 * 4. Nothing else declares it: the library's loadable settings are exactly the ones listed below, so
 *    a new one is a decision this test records rather than an annotation nobody noticed.
 */
class MediaSlotsTest {

    private val universe = Universe(0, 0)

    @Test
    fun `media reflects onto the property and the descriptor, and the sentinel as unset`() {
        val rev = Source4RevolutionFixture.BaseFrame31Ch(universe, "rev-1", "Rev 1", 1)
        assertEquals(MediaSlot.GEL, rev.fixtureProperty("gelScroller")?.media)
        assertEquals(MediaSlot.GOBO_OR_GEL, rev.fixtureProperty("fbWheelPos")?.media)
        assertEquals(MediaSlot.GEL, rev.fixtureProperty("mediaFrame")?.media)
        assertNull(rev.fixtureProperty("fbWheelFunc")?.media, "MediaSlot.NONE reflects as unset")
        assertNull(rev.fixtureProperty("zoom")?.media)

        val settings = rev.generatePropertyDescriptors().filterIsInstance<SettingPropertyDescriptor>().associateBy { it.name }
        val scroller = assertNotNull(settings["gelScroller"])
        assertEquals("GEL", scroller.media)
        assertTrue(scroller.options.all { it.loadable == true }, "every scroller frame takes a gel, the open ones too")
        val wheel = assertNotNull(settings["fbWheelPos"])
        assertEquals("GOBO_OR_GEL", wheel.media)
        assertEquals(listOf(false, true, true, true), wheel.options.map { it.loadable }, "the open hole takes nothing")
        val frame = assertNotNull(settings["mediaFrame"])
        assertEquals("GEL", frame.media)
        assertEquals(mapOf("OUT" to false, "IN" to true), frame.options.associate { it.name to it.loadable })
        assertNull(settings["fbWheelFunc"]?.media)
        assertTrue(settings.getValue("fbWheelFunc").options.all { it.loadable == null }, "loadable is only on a loadable setting")
    }

    @Test
    fun `every loadable setting in the library obeys the rules, and nothing else declares one`() {
        val declared = mutableSetOf<Triple<String, String, MediaSlot>>()
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            for (p in fixture.fixtureProperties) {
                val media = p.media ?: continue
                val where = "${type.typeKey}'s ${p.name}"
                declared += Triple(type.typeKey, p.name, media)
                assertTrue(p.settingBacked, "$where declares media but is not a setting")
                val setting = p.classProperty.getter.call(fixture) as DmxFixtureSetting<*>
                val optionType: KClass<*> = setting.sortedValues.first()::class
                if (media.takesGel) {
                    assertTrue(optionType.isSubclassOf(DmxFixtureColourSettingValue::class), "$where takes gels but its options carry no colour")
                }
                if (media.takesGobo) {
                    assertTrue(optionType.isSubclassOf(DmxFixtureGoboSettingValue::class), "$where takes gobos but its options carry no pattern")
                }
                assertTrue(setting.sortedValues.any { it.loadable }, "$where declares media but has no slot")
            }
            if (fixture is MultiElementFixture<*>) {
                fixture.elements.forEachIndexed { i, element ->
                    for (p in FixturePropertyCatalogue.of(element::class).all) {
                        assertNull(p.media, "${type.typeKey}[$i]'s ${p.name}: a cell is no unit, so it loads nothing")
                    }
                }
            }
        }
        assertEquals(
            setOf(
                Triple("etc-source4-revolution-base-frame", "gelScroller", MediaSlot.GEL),
                Triple("etc-source4-revolution-base-frame", "fbWheelPos", MediaSlot.GOBO_OR_GEL),
                Triple("etc-source4-revolution-base-frame", "mediaFrame", MediaSlot.GEL),
            ),
            declared,
            "the library's loadable settings",
        )
    }
}
