package uk.me.cormack.lighting7.fixture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.intOrNull
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.ColourSplits
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureColourSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.SettingAttributeDescriptor
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where colour wheels split (wheel-colour-chase plan D5, P2). Every COLOUR setting's declared
 * continuous levels rise in the wheel's order and sit in one band no other indexed option starts in;
 * `src/test/resources/fixture/colourSplit.fixture.json`, made by an independent port, pins a level's
 * reading for the client too.
 */
class ColourSplitTest {

    private val universe = Universe(0, 0)

    private fun colourSettings(): List<Triple<String, String, DmxFixtureSetting<*>>> = buildList {
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            val owners = buildList {
                add(type.typeKey to fixture)
                if (fixture is MultiElementFixture<*>) fixture.elements.forEachIndexed { i, e -> add("${type.typeKey}[$i]" to e) }
            }
            for ((where, owner) in owners) {
                for (p in FixtureAttributeCatalogue.of(owner::class).all) {
                    if (p.category != AttributeCategory.COLOUR) continue
                    val setting = p.classProperty.getter.call(owner) as? DmxFixtureSetting<*> ?: continue
                    add(Triple(where, p.name, setting))
                }
            }
        }
    }

    private fun declared(setting: DmxFixtureSetting<*>) =
        setting.sortedValues.mapNotNull { o -> (o as? DmxFixtureColourSettingValue)?.continuousLevel?.let { o to it } }

    @Test
    fun `declared levels rise in wheel order, in a band no other indexed option starts in`() {
        var splitting = 0
        for ((where, name, setting) in colourSettings()) {
            val levels = declared(setting)
            if (levels.isEmpty()) continue
            assertTrue(levels.size >= 2, "$where's $name declares one continuous level, which splits with nothing")
            levels.zipWithNext().forEach { (a, b) ->
                assertTrue(a.second < b.second, "$where's $name: ${a.first.name} at ${a.second} is not below ${b.first.name} at ${b.second}")
            }
            val low = levels.first().second
            val high = levels.last().second
            val holder = setting.sortedValues.last { it.level <= low }
            val inside = setting.sortedValues.filter { it != holder && it.level in low..high }
            assertTrue(inside.isEmpty(), "$where's $name: ${inside.map { it.name }} start inside the continuous band $low–$high")
            splitting++
        }
        assertEquals(3, splitting, "the MAC 250's wheel and the Robe's two")
    }

    @Test
    fun `the MAC 250 and both Robe wheels split, and the Beam Ring, Fusion and Varytec do not`() {
        val byType = colourSettings().groupBy({ it.first.substringBefore('[') }, { it.third })
        fun splits(typeKey: String) = byType.getValue(typeKey).any(ColourSplits::splits)
        assertTrue(splits("martin-mac-250-mode-4"))
        assertTrue(byType.getValue("robe-color-spot-575-mode-2").all(ColourSplits::splits))
        assertFalse(splits("show-sound-ledbeamring-14ch"))
        for (typeKey in byType.keys.filter { it.startsWith("fusion-100-spot") || it.startsWith("varytec") }) {
            assertFalse(splits(typeKey), typeKey)
        }
    }

    @Test
    fun `two slots split only as neighbours, either way round, at the midpoint`() {
        val mac = MartinMac250Fixture.Mode4Ch(universe, "mac", "MAC", 1).colourWheel
        val red = MartinMac250Fixture.Colour.RED
        val blue = MartinMac250Fixture.Colour.BLUE_108
        assertEquals(72u.toUByte(), ColourSplits.levelBetween(mac, red, blue), "(67 + 78) / 2, rounded down")
        assertEquals(72u.toUByte(), ColourSplits.levelBetween(mac, blue, red))
        assertEquals(72u.toUByte(), ColourSplits.levelBetween(mac, red.level, blue.level), "by indexed levels")
        assertNull(ColourSplits.levelBetween(mac, red, MartinMac250Fixture.Colour.GREEN), "a slot between them")
        assertNull(ColourSplits.levelBetween(mac, red, red))
        assertNull(ColourSplits.levelBetween(mac, red, MartinMac250Fixture.Colour.SCROLL_CW), "a band that declares none")
    }

    @Test
    fun `the option descriptor carries a slot's continuous level`() {
        val robe = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)
        val wheel = robe.generateAttributeDescriptors().filterIsInstance<SettingAttributeDescriptor>().single { it.name == "colour1" }
        val byName = wheel.options.associateBy { it.name }
        assertEquals(28, byName.getValue("RED").continuousLevel)
        assertNull(byName.getValue("SCROLL_CW").continuousLevel)
    }

    @Test
    fun `every level reads as the shared vector says`() {
        val vector = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/fixture/colourSplit.fixture.json")) { "the shared vector is missing" }.readText(),
        ).jsonObject
        val wheels = vector.getValue("wheels").jsonArray
        assertEquals(3, wheels.size)
        for (entry in wheels) {
            val wheel = entry.jsonObject
            val typeKey = wheel.getValue("typeKey").jsonPrimitive.content
            val attribute = wheel.getValue("attribute").jsonPrimitive.content
            val setting = assertNotNull(colourSettings().singleOrNull { it.first == typeKey && it.second == attribute }?.third, "$typeKey $attribute")

            val options = wheel.getValue("options").jsonArray.map { it.jsonObject }
            assertEquals(setting.sortedValues.map { it.name }, options.map { it.getValue("name").jsonPrimitive.content }, "$typeKey's options")
            for ((option, pinned) in setting.sortedValues.zip(options)) {
                assertEquals(option.level.toInt(), pinned.getValue("level").jsonPrimitive.int)
                assertEquals((option as? DmxFixtureColourSettingValue)?.continuousLevel?.toInt(), pinned["continuousLevel"]?.jsonPrimitive?.intOrNull)
            }

            for (split in wheel.getValue("splits").jsonArray.map { it.jsonObject }) {
                val a = setting.valueForName(split.getValue("a").jsonPrimitive.content)
                val b = setting.valueForName(split.getValue("b").jsonPrimitive.content)
                assertEquals(split.getValue("level").jsonPrimitive.int, ColourSplits.levelBetween(setting, a, b)?.toInt(), "$typeKey ${a.name}/${b.name}")
            }

            val readings = wheel.getValue("readings").jsonArray.map { it.jsonArray }
            assertEquals(256, readings.size)
            for (reading in readings) {
                val level = reading[0].jsonPrimitive.int
                val read = ColourSplits.reading(setting, level.toUByte())
                if (reading[1] is JsonNull) {
                    assertNull(read, "$typeKey at $level is outside the band")
                    continue
                }
                assertNotNull(read, "$typeKey at $level")
                assertEquals(reading[1].jsonPrimitive.content, read.slotA, "$typeKey at $level")
                assertEquals(reading[2].jsonPrimitive.content, read.slotB, "$typeKey at $level")
                assertTrue(abs(reading[3].jsonPrimitive.double - read.share) < 1e-6, "$typeKey at $level: ${read.share}")
            }
        }
    }
}
