package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.routes.CommandPropertyDescriptor
import uk.me.cormack.lighting7.routes.TriggerPropertyDescriptor
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `@FixtureCommand` across the library (fixture optics plan session 7, D13): what every declaration
 * must hold, and that the hazards the plan names are commands and nothing else.
 */
class FixtureCommandsTest {

    /** Every type with commands, built at channel 1 for introspection, with its commands. */
    private val library: List<Triple<String, DmxFixture, List<ResolvedCommand>>> =
        FixtureTypeRegistry.allTypes.mapNotNull { type ->
            val fixture = FixtureTypeRegistry.introspectionInstanceForTypeKey(type.typeKey) ?: return@mapNotNull null
            val commands = FixtureCommands.of(fixture)
            if (commands.isEmpty()) null else Triple(type.typeKey, fixture, commands)
        }

    @Test
    fun `the hazards the plan names are commands, dedicated or shared as their channel says`() {
        fun commandsOf(typeKey: String) = library.single { it.first == typeKey }.third.associateBy { it.name }
        // Dedicated: no property covers the channel, so the desk owns it.
        mapOf(
            "etc-source4-revolution-base-frame" to setOf("reset", "resetScroller", "resetPanTilt", "resetFrontModule", "resetRearModule"),
            "robe-color-spot-575-mode-2" to setOf(
                "lampOn", "lampOff", "reset", "resetPanTilt", "resetColour", "resetGobo", "resetDimmer", "resetFocusZoomFrost", "resetIrisPrism",
            ),
            "varytec-easymove-xl-60-spot-11ch" to setOf("reset"),
            "shehds-led19-rgbw-24ch" to setOf("reset"),
            "shehds-led19-rgbw-16ch" to setOf("reset"),
        ).forEach { (typeKey, names) ->
            val commands = commandsOf(typeKey)
            assertEquals(names, commands.keys, typeKey)
            assertTrue(commands.values.all { it.dedicated }, "$typeKey's command channel is the desk's own")
        }
        // Shared: a property drives the channel between commands.
        mapOf(
            "martin-mac-250-mode-4" to setOf("lampOn", "lampOff", "reset"),
            "fusion-100-spot-mkii-5ch" to setOf("reset"),
            "fusion-100-spot-mkii-15ch" to setOf("reset"),
            "gear4music-orbit-70-13ch" to setOf("reset"),
            "slender-beam-bar-quad-27ch" to setOf("reset"),
        ).forEach { (typeKey, names) ->
            val commands = commandsOf(typeKey)
            assertEquals(names, commands.keys, typeKey)
            assertTrue(commands.values.none { it.dedicated }, "$typeKey's command shares its channel with a property")
        }
        // The Revolution's reset holds a band for the manual's three seconds plus margin; "Reset scroller" is 147–152.
        val scroller = commandsOf("etc-source4-revolution-base-frame").getValue("resetScroller")
        assertEquals(3_500, scroller.spec.holdMs)
        assertEquals(147..152, scroller.bandMin.toInt()..scroller.bandMax.toInt())
        assertEquals(12, scroller.channelNo)
    }

    @Test
    fun `every declaration is sound`() {
        assertTrue(library.size >= 10, "the plan's eight families, ten types: ${library.map { it.first }}")
        for ((typeKey, fixture, commands) in library) {
            val propertyChannels = FixtureCommands.propertyChannelsOf(fixture)
            for (c in commands) {
                assertTrue(c.spec.label.isNotBlank() && c.spec.description.isNotBlank(), "$typeKey ${c.name} needs a label and a description")
                assertTrue(c.spec.holdMs in 500..FixtureCommands.MAX_HOLD_MS, "$typeKey ${c.name} holds ${c.spec.holdMs} ms")
                assertTrue(c.spec.confirm, "$typeKey ${c.name}: every command in the library asks first")
                assertTrue(c.channelNo in fixture.firstChannel until fixture.firstChannel + fixture.channelCount, "$typeKey ${c.name} is off the fixture's footprint")
                for (pre in c.alongside) {
                    assertTrue(pre.channelNo in propertyChannels, "$typeKey ${c.name}: a precondition sets a property's channel, not ${pre.channelNo}")
                    assertTrue(pre.channelNo != c.channelNo, "$typeKey ${c.name}: a precondition on its own channel")
                }
            }
            for ((channel, onChannel) in commands.groupBy { it.channelNo }) {
                assertEquals(1, onChannel.map { it.idleLevel }.distinct().size, "$typeKey ch $channel: one idle level")
                val sorted = onChannel.sortedBy { it.bandMin }
                sorted.zipWithNext().forEach { (a, b) ->
                    assertTrue(a.bandMax < b.bandMin, "$typeKey ch $channel: ${a.name} and ${b.name} overlap")
                }
            }
        }
    }

    @Test
    fun `a shared channel's property offers no option inside a command's band`() {
        // The five RESET options this session removed must stay removed: a setting option in a band would
        // put the command back in every Look picker.
        for ((typeKey, fixture, commands) in library) {
            for (c in commands.filterNot { it.dedicated }) {
                for (prop in fixture.fixtureProperties) {
                    val setting = runCatching { prop.classProperty.call(fixture) }.getOrNull() as? DmxFixtureSetting<*> ?: continue
                    if (setting.channelNo != c.channelNo) continue
                    val inBand = setting.sortedValues.filter { it.level in c.bandMin..c.bandMax }
                    assertTrue(inBand.isEmpty(), "$typeKey's ${prop.name} still offers $inBand inside ${c.name}'s band")
                }
            }
        }
    }

    @Test
    fun `no head of a multi-head fixture covers a command channel`() {
        // The stored-row strip and the band guard judge fixture-level properties only.
        for ((typeKey, fixture, commands) in library) {
            if (fixture !is MultiElementFixture<*>) continue
            for (element in fixture.elements) {
                for (prop in FixturePropertyCatalogue.of(element::class).all) {
                    @Suppress("UNCHECKED_CAST")
                    val channel = when (val v = (prop.classProperty as KProperty1<Any, *>).call(element)) {
                        is uk.me.cormack.lighting7.fixture.dmx.DmxSlider -> v.channelNo
                        is DmxFixtureSetting<*> -> v.channelNo
                        else -> continue
                    }
                    assertTrue(commands.none { it.channelNo == channel }, "$typeKey: head ${element.elementKey}'s ${prop.name} covers a command channel")
                }
            }
        }
    }

    @Test
    fun `no command name is any type's property or trigger name`() {
        // A generic row is refused by every command name there is, so a property of the same name
        // anywhere in the library would become unstorable on every generic row.
        val propertyNames = FixtureTypeRegistry.allTypes.flatMap { t -> t.properties.filterNot { it is CommandPropertyDescriptor || it is TriggerPropertyDescriptor }.map { it.name } }.toSet()
        val elementNames = FixtureTypeRegistry.allTypes.flatMap { t -> t.elementGroupProperties.orEmpty().map { it.name } }.toSet()
        for (name in FixtureCommands.allReservedNames) {
            assertFalse(propertyNames.any { it.equals(name, ignoreCase = true) }, "'$name' is a command and a property")
            assertFalse(elementNames.any { it.equals(name, ignoreCase = true) }, "'$name' is a command and a head property")
            assertFalse(FixtureTriggers.allReservedNames.any { it.equals(name, ignoreCase = true) }, "'$name' is a command and a trigger")
        }
    }

    @Test
    fun `commands reach the descriptor list last, after the controls`() {
        for ((typeKey, fixture, commands) in library) {
            val descriptors = fixture.generatePropertyDescriptors()
            val listed = descriptors.filterIsInstance<CommandPropertyDescriptor>()
            assertEquals(commands.map { it.name }, listed.map { it.name }, typeKey)
            assertTrue(descriptors.takeLast(listed.size).all { it is CommandPropertyDescriptor || it is TriggerPropertyDescriptor }, "$typeKey: commands come last")
            val first = listed.first()
            val command = commands.first()
            assertEquals(command.spec.holdMs, first.holdMs)
            assertEquals(command.dedicated, first.dedicated)
            assertEquals(command.channelNo, first.channel.channelNo)
        }
    }

    @Test
    fun `a dedicated command channel is named in the DMX sheet, a shared one keeps its property's name`() {
        val rev = library.single { it.first == "etc-source4-revolution-base-frame" }.second
        assertEquals("5 commands", rev.channelDescriptions().getValue(12))
        val vary = FixtureTypeRegistry.introspectionInstanceForTypeKey("varytec-easymove-xl-60-spot-11ch")!!
        assertEquals("Reset (command)", vary.channelDescriptions().getValue(11))
        val mac = library.single { it.first == "martin-mac-250-mode-4" }.second
        assertFalse(mac.channelDescriptions().getValue(1).contains("(command)"))
    }

    @Test
    fun `shared bands are listed per property for the stored-row strip`() {
        assertEquals(
            mapOf("strobe" to listOf(208..217, 228..237, 248..255)),
            FixtureCommands.sharedBandsForTypeKey("martin-mac-250-mode-4").mapValues { e -> e.value.map { it.range }.sortedBy { it.first } },
        )
        assertEquals(listOf(251..255), FixtureCommands.sharedBandsForTypeKey("fusion-100-spot-mkii-15ch").getValue("motorMode").map { it.range })
        assertEquals(listOf(200..255), FixtureCommands.sharedBandsForTypeKey("gear4music-orbit-70-13ch").getValue("program").map { it.range })
        assertTrue(FixtureCommands.sharedBandsForTypeKey("etc-source4-revolution-base-frame").isEmpty(), "a dedicated channel has no property to strip")
    }
}
