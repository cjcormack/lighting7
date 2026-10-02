package uk.me.cormack.lighting7.routes

import io.ktor.serialization.kotlinx.json.DefaultJson
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.GoboPattern
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the gobo/prism metadata carried on [SettingOption] — the wire contract
 * the 3D stage view resolves patterns and prism facets from. Descriptors are
 * generated without a transaction, exactly as [uk.me.cormack.lighting7.fixture.FixtureTypeRegistry]
 * does for the `/types` route.
 */
class PropertyDescriptorDtoTest {

    private val universe = Universe(0, 0)

    private fun settingOptions(
        fixture: uk.me.cormack.lighting7.fixture.DmxFixture,
        category: String,
    ): List<SettingOption> {
        val descriptor = fixture.generatePropertyDescriptors()
            .filterIsInstance<SettingPropertyDescriptor>()
            .single { it.category == category }
        return descriptor.options
    }

    @Test
    fun `MAC 250 gobo options carry their manual pattern names, shakes share them`() {
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "Mac 1", 1)
        val byName = settingOptions(fixture, "gobo").associateBy { it.name }

        assertNull(byName.getValue("OPEN").gobo)
        assertEquals("cone", byName.getValue("CONE").gobo)
        assertEquals("cone", byName.getValue("CONE_SHAKE").gobo)
        assertEquals("beam_split", byName.getValue("DEC_BEAM").gobo)
        assertEquals("holes", byName.getValue("RND_HOLES_BLUE").gobo)
        // The wheel is physically moving through positions: no honest single pattern.
        assertNull(byName.getValue("SCROLL_CW").gobo)
    }

    @Test
    fun `MAC 250 prism options carry facets only while the prism is in the beam`() {
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "Mac 1", 1)
        val byName = settingOptions(fixture, "prism").associateBy { it.name }

        assertNull(byName.getValue("PRISM_OFF").prismFacets)
        assertNull(byName.getValue("PRISM_OFF_2").prismFacets)
        assertEquals(3, byName.getValue("ROT_CW").prismFacets)
        assertEquals(3, byName.getValue("ROT_CCW").prismFacets)
        assertEquals(3, byName.getValue("NO_ROT").prismFacets)
        assertEquals(3, byName.getValue("MACRO_1").prismFacets)
    }

    @Test
    fun `Robe rotating-wheel mode variants of one physical slot agree on the pattern`() {
        val fixture = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
        // Two gobo-category settings (static + rotating wheel); reflection
        // does not guarantee declaration order, so pick the rotating one by
        // its option names.
        val goboDescriptors = fixture.generatePropertyDescriptors()
            .filterIsInstance<SettingPropertyDescriptor>()
            .filter { it.category == "gobo" }
        assertEquals(2, goboDescriptors.size)
        val rotating = goboDescriptors.single { d -> d.options.any { it.name == "INDEX_GOBO_1" } }
        val byName = rotating.options.associateBy { it.name }

        val slot3Variants = listOf(
            "INDEX_GOBO_3", "ROTATE_GOBO_3", "SHAKE_INDEX_GOBO_3", "SHAKE_ROTATE_GOBO_3",
        )
        val patterns = slot3Variants.map { byName.getValue(it).gobo }.toSet()
        assertEquals(setOf("holes"), patterns, "all four mode variants address the same glass")

        assertNull(byName.getValue("OPEN").gobo)
        assertNull(byName.getValue("SCROLL_CW").gobo)
        assertNull(byName.getValue("RANDOM").gobo)
    }

    @Test
    fun `Robe prism macros keep the 3-facet prism in the beam`() {
        val fixture = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
        val byName = settingOptions(fixture, "prism").associateBy { it.name }

        assertNull(byName.getValue("OFF").prismFacets)
        assertEquals(3, byName.getValue("ROTATING_3_FACET").prismFacets)
        assertEquals(3, byName.getValue("MACRO_16").prismFacets)
    }

    @Test
    fun `gobo-annotated SettingOption round-trips through JSON`() {
        val json = Json
        val annotated = SettingOption(name = "CONE", level = 10, displayName = "Cone", gobo = "cone")
        val encoded = json.encodeToString(SettingOption.serializer(), annotated)
        assertTrue("\"gobo\":\"cone\"" in encoded, "wire carries the pattern name: $encoded")
        assertEquals(annotated, json.decodeFromString(SettingOption.serializer(), encoded))

        // A legacy payload without the new keys must still decode (old backend,
        // new consumer) — this is the graceful-degradation contract.
        val legacy = json.decodeFromString(
            SettingOption.serializer(),
            """{"name":"OPEN","level":0,"displayName":"Open"}""",
        )
        assertNull(legacy.gobo)
        assertNull(legacy.prismFacets)
    }

    @Test
    fun `a focus slider carries its declared range onto the wire`() {
        val fixture = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "Mac 1", 1)
        val focus = fixture.generatePropertyDescriptors()
            .filterIsInstance<SliderPropertyDescriptor>()
            .single { it.category == "focus" }
        assertEquals(2.0, focus.focusNearM)
        assertEquals(40.0, focus.focusFarM)
        assertEquals(true, focus.inverted)

        // The routes' own Json (Ktor's `json()`), and the shape `store/fixtures.ts` reads.
        val encoded = DefaultJson.encodeToString(PropertyDescriptor.serializer(), focus)
        assertTrue("\"type\":\"slider\"" in encoded, encoded)
        assertTrue("\"focusNearM\":2.0" in encoded, encoded)
        assertTrue("\"focusFarM\":40.0" in encoded, encoded)
        assertTrue("\"inverted\":true" in encoded, encoded)
        assertEquals(focus, DefaultJson.decodeFromString(PropertyDescriptor.serializer(), encoded))

        // A payload from before the range existed still decodes, with no range.
        val legacy = DefaultJson.decodeFromString(
            PropertyDescriptor.serializer(),
            """{"type":"slider","name":"focus","displayName":"Focus","category":"focus","channel":{"universe":0,"channelNo":6}}""",
        ) as SliderPropertyDescriptor
        assertNull(legacy.focusNearM)
        assertNull(legacy.focusFarM)
    }

    @Test
    fun `GoboPattern wire names are the lowercase enum names`() {
        for (pattern in GoboPattern.entries) {
            assertEquals(pattern.name.lowercase(), pattern.serialized())
        }
    }
}
