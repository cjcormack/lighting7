package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.RobeColorSpot575Fixture
import uk.me.cormack.lighting7.fixture.dmx.UVFixture
import uk.me.cormack.lighting7.fixture.dmx.WhexFixture
import uk.me.cormack.lighting7.fx.CueAssignmentResolver
import uk.me.cormack.lighting7.fx.LocateValueResolver
import uk.me.cormack.lighting7.fx.TemplateIntent
import uk.me.cormack.lighting7.fx.TemplateResolver
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fixture optics plan's session 5, "the library optics pass": fixed lenses (D3), proportional
 * bands, a stepped zoom's readers, and the definitions that were wrong against their manuals.
 */
class LibraryOpticsTest {

    private val universe = Universe(0, 0)

    private fun robe() = RobeColorSpot575Fixture.Mode2Ch(universe, "spot-1", "Spot 1", 1)

    // ─── fieldDeg (D3) ──────────────────────────────────────────────────────

    @Test
    fun `a fixed lens declares its field on the type, and the sentinel reflects as unset`() {
        val fields = FixtureTypeRegistry.allTypes.associate { it.typeKey to it.fieldDeg }
        for (mode in listOf("5ch", "8ch", "15ch")) assertEquals(10.0, fields["fusion-100-spot-mkii-$mode"], mode)
        assertEquals(10.0, fields["imgstageline-wash-42led-13ch"])
        for (mode in listOf("8ch", "12ch", "17ch")) assertEquals(11.0, fields["scantastic-4-$mode"], mode)
        assertNull(fields["robe-color-spot-575-mode-2"], "a zoom channel's head declares no fixed field")
        assertTrue(fields.values.all { it == null || (it > 0.0 && it < 180.0) })
    }

    @Test
    fun `no type declares both a fixed field and a zoom channel`() {
        for (type in FixtureTypeRegistry.allTypes) {
            if (type.fieldDeg == null) continue
            assertTrue(type.properties.none { it.category == "zoom" }, "${type.typeKey} has a zoom and a fixed field")
        }
    }

    // ─── activeMin / activeMax ──────────────────────────────────────────────

    @Test
    fun `the Robe's iris and frost declare their proportional band, which reaches the descriptor`() {
        val sliders = robe().generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().associateBy { it.name }
        for (name in listOf("iris", "frost")) {
            val slider = assertNotNull(sliders[name], name)
            assertEquals(1, slider.activeMin, name)
            assertEquals(179, slider.activeMax, name)
        }
        assertNull(sliders.getValue("focus").activeMin, "the -1 sentinel reflects as unset")
        assertNull(sliders.getValue("focus").activeMax)
    }

    @Test
    fun `every declared band lies inside its slider and runs upwards`() {
        for (type in FixtureTypeRegistry.allTypes) {
            for (slider in type.properties.filterIsInstance<SliderPropertyDescriptor>()) {
                val lo = slider.activeMin ?: slider.min
                val hi = slider.activeMax ?: slider.max
                assertTrue(lo in slider.min..slider.max && hi in slider.min..slider.max && lo < hi, "${type.typeKey}'s ${slider.name} band $lo..$hi")
            }
        }
    }

    // ─── a stepped zoom's readers ───────────────────────────────────────────

    @Test
    fun `Locate puts a stepped zoom at its middle angle, not its focus-corrected twin`() {
        val zoom = LocateValueResolver.resolve(robe()).single { it.propertyName == "zoom" }
        assertEquals(CueAssignmentResolver.PropertyValue.Setting(RobeColorSpot575Fixture.Zoom.ZOOM_18.level), zoom.value)
    }

    @Test
    fun `a percent on a stepped zoom runs across its angles from DMX min's`() {
        fun at(pct: Double) = assertIs<CueAssignmentResolver.PropertyValue.Setting>(
            TemplateResolver.resolve(robe(), "zoom", TemplateIntent.Percent(pct), media = null).value,
        ).channelValue
        assertEquals(RobeColorSpot575Fixture.Zoom.ZOOM_15.level, at(0.0))
        assertEquals(RobeColorSpot575Fixture.Zoom.ZOOM_18.level, at(50.0))
        assertEquals(RobeColorSpot575Fixture.Zoom.ZOOM_22.level, at(100.0))
    }

    // ─── corrections against the manuals ────────────────────────────────────

    @Test
    fun `the Robe pans 530 degrees and tilts 280, as its manual says`() {
        val robe = robe()
        assertEquals(530.0, robe.fixtureProperty("pan")?.degMax)
        assertEquals(280.0, robe.fixtureProperty("tilt")?.degMax)
        assertEquals(0.0, robe.fixtureProperty("pan")?.degMin)
        assertEquals(0.0, robe.fixtureProperty("tilt")?.degMin)
    }

    @Test
    fun `the Whex strobe stays inside its band all the way to full, slow to fast`() {
        val (controller, transaction) = createTestTransaction(universe)
        val whex = WhexFixture(universe, "whex-1", "Whex 1", 1).withTransaction(transaction)
        var last = 0
        for (intensity in 0..255) {
            whex.strobe.strobe(intensity.toUByte())
            transaction.apply()
            val level = controller.getValue(8).toInt()
            assertTrue(level in WhexFixture.DmxStrobe.STROBE_MIN..255, "intensity $intensity wrote $level")
            assertTrue(level >= last, "intensity $intensity wrote $level after $last: a strobe must not slow as it rises")
            last = level
        }
        assertEquals(255, last, "full intensity is the fastest strobe")
        whex.strobe.fullOn()
        transaction.apply()
        assertEquals(0, controller.getValue(8).toInt())
    }

    @Test
    fun `every program of the Whex has its own level`() {
        val levels = WhexFixture.ProgramMode.entries.map { it.level }
        assertEquals(levels.size, levels.toSet().size, "duplicate program levels: $levels")
        assertEquals(11u.toUByte(), WhexFixture.ProgramMode.AUTO_PROGRAM_1.level)
    }

    @Test
    fun `a UV fixture's one channel is found as its dimmer`() {
        val uv = UVFixture(universe, "uv-1", "UV 1", 1)
        val property = assertNotNull(uv.fixtureProperty("dimmer"))
        assertEquals(PropertyCategory.DIMMER, property.category)
        val slider = uv.generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().single()
        assertEquals("dimmer", slider.category)
        assertTrue("dimmer" in FixtureTypeRegistry.typeInfoForKey("uv")!!.capabilities)
    }
}
