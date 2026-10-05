package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.BandedStrobeChannel
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureSetting
import uk.me.cormack.lighting7.fixture.dmx.DmxFixtureStrobeSettingValue
import uk.me.cormack.lighting7.fixture.dmx.DmxSlider
import uk.me.cormack.lighting7.fixture.dmx.MartinMac250Fixture
import uk.me.cormack.lighting7.fixture.dmx.WhexFixture
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.fixture.property.Strobe
import uk.me.cormack.lighting7.routes.SettingPropertyDescriptor
import uk.me.cormack.lighting7.routes.SliderPropertyDescriptor
import uk.me.cormack.lighting7.routes.StrobeBandInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every STROBE channel says what each of its bands does (fixture optics plan D12): closed, open,
 * strobe, random or pulse, a flashing band with its rate. A channel that said nothing drew lit
 * whatever it held, which is how a MAC 250 at strobe 0 — dark on the rig — drew full in the view.
 */
class StrobeBandsTest {

    private val universe = Universe(0, 0)

    private data class Owner(val where: String, val owner: Any)

    private fun owners(): List<Owner> = buildList {
        for (type in FixtureTypeRegistry.allTypes) {
            val fixture = FixtureTypeRegistry.instantiateByTypeKey(type.typeKey, universe, "probe", "Probe", 1)
            add(Owner(type.typeKey, fixture))
            if (fixture is MultiElementFixture<*>) {
                fixture.elements.forEachIndexed { i, element -> add(Owner("${type.typeKey}[$i]", element)) }
            }
        }
    }

    @Test
    fun `every STROBE slider in the library declares its bands, and they cover what composition can write`() {
        var sliders = 0
        for ((where, owner) in owners()) {
            for (p in FixturePropertyCatalogue.of(owner::class).all) {
                val value = p.classProperty.getter.call(owner)
                if (p.category != PropertyCategory.STROBE) {
                    assertTrue(p.strobeBands.isEmpty(), "$where's ${p.name} is no STROBE property but declares strobe bands")
                    continue
                }
                if (value !is DmxSlider) continue
                sliders++
                val bands = p.strobeBands
                if (bands.isEmpty()) fail("$where's ${p.name} is a STROBE slider that declares no bands")
                assertBandsWellFormed("$where's ${p.name}", bands)
                for (level in value.min.toInt()..value.max.toInt()) {
                    assertTrue(bands.any { level in it }, "$where's ${p.name}: DMX $level is in its range but in no band")
                }
            }
        }
        assertTrue(sliders >= 24, "the library's STROBE sliders: $sliders")
    }

    @Test
    fun `what each strobe writer writes lands where its bands say it should`() {
        for ((where, owner) in owners()) {
            for (p in FixturePropertyCatalogue.of(owner::class).all) {
                if (p.category != PropertyCategory.STROBE) continue
                val value = p.classProperty.getter.call(owner) as? DmxSlider ?: continue
                val bands = p.strobeBands
                fun kindAt(level: UByte): StrobeKind? = bands.firstOrNull { level.toInt() in it }?.kind
                if (value is Strobe) {
                    // Locate and fullOn() write this: the shutter open, as the bands must agree.
                    val open = value.fullOnValue.coerceIn(value.min, value.max)
                    assertEquals(StrobeKind.OPEN, kindAt(open), "$where's ${p.name}: fullOn writes $open")
                }
                val writes: ((UByte) -> UByte)? = when (value) {
                    is BandedStrobeChannel -> value::strobeLevel
                    is WhexFixture.DmxStrobe -> value::strobeLevel
                    else -> null
                }
                if (value is Strobe && writes == null) fail("$where's ${p.name}: a Strobe this test cannot read")
                if (writes == null) continue
                // strobe(0) may be "no strobe" (zeroIntensityIsFullOn, or a band starting at 0); every
                // other intensity strobes.
                val zero = kindAt(writes(0u))
                assertTrue(zero == StrobeKind.OPEN || zero?.flashes == true, "$where's ${p.name}: strobe(0) lands in $zero")
                var previousHz = 0.0
                for (intensity in 1..255) {
                    val level = writes(intensity.toUByte())
                    assertEquals(true, kindAt(level)?.flashes, "$where's ${p.name}: strobe($intensity) writes $level, in ${kindAt(level)}")
                    // A higher intensity is never a slower strobe: the MAC 250's band runs fast → slow,
                    // and its writer runs down it.
                    val hz = rateAt(bands.first { level.toInt() in it }, level.toInt())
                    assertTrue(hz >= previousHz - 1e-9, "$where's ${p.name}: strobe($intensity) is ${hz} Hz, slower than ${previousHz} Hz")
                    previousHz = hz
                }
            }
        }
    }

    @Test
    fun `every STROBE setting's options declare what their band does`() {
        for ((where, owner) in owners()) {
            for (p in FixturePropertyCatalogue.of(owner::class).all) {
                if (p.category != PropertyCategory.STROBE) continue
                val setting = p.classProperty.getter.call(owner) as? DmxFixtureSetting<*> ?: continue
                for (option in setting.sortedValues) {
                    val strobe = option as? DmxFixtureStrobeSettingValue
                        ?: fail("$where's ${p.name} ${option.name} declares no strobeKind")
                    assertRate("$where's ${p.name} ${option.name}", strobe.strobeKind, strobe.hzMin, strobe.hzMax)
                }
            }
        }
        // The library has none yet; the test head below proves the walk reads one.
        val head = TestStrobeShutterHead(universe, "probe", 1)
        val setting = head.shutter
        assertEquals(listOf(StrobeKind.CLOSED, StrobeKind.OPEN, StrobeKind.STROBE), setting.sortedValues.map { it.strobeKind })
    }

    @Test
    fun `the bands reach the descriptor, the MAC 250's past its clamp`() {
        val mac = MartinMac250Fixture.Mode4Ch(universe, "mac-1", "MAC 1", 1)
        val strobe = mac.generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().single { it.name == "strobe" }
        assertEquals(72, strobe.max, "the slider stays clamped below reset and the lamp")
        val bands = checkNotNull(strobe.strobeBands)
        assertEquals(StrobeBandInfo(0, 19, "CLOSED"), bands.first())
        assertEquals(StrobeBandInfo(50, 72, "STROBE", hzMin = 1.0, hzMax = 10.0, inverted = true), bands[2])
        assertTrue(bands.any { it.kind == "RANDOM" && it.from == 128 }, "a raw write past the clamp is described too")
        assertTrue(bands.none { 208 in it.from..it.to || 228 in it.from..it.to }, "reset and the lamp stay undeclared: they are fixture commands, and the view draws their bands open")

        val dimmer = mac.generatePropertyDescriptors().filterIsInstance<SliderPropertyDescriptor>().single { it.name == "dimmer" }
        assertNull(dimmer.strobeBands, "only a STROBE slider carries bands")
    }

    @Test
    fun `a STROBE setting's options carry their kind and rate on the wire`() {
        val head = TestStrobeShutterHead(universe, "shutter-1", 1)
        val shutter = head.generatePropertyDescriptors().filterIsInstance<SettingPropertyDescriptor>().single()
        val byName = shutter.options.associateBy { it.name }
        assertEquals("CLOSED", byName.getValue("SHUT").strobeKind)
        assertNull(byName.getValue("SHUT").hzMin)
        assertEquals("STROBE", byName.getValue("FLASH").strobeKind)
        assertEquals(2.0, byName.getValue("FLASH").hzMin)
        assertEquals(12.0, byName.getValue("FLASH").hzMax)
        assertNull(byName.getValue("FLASH").strobeInverted, "false is left off the wire")
    }

    /** A flashing band's declared rate at [level]: hzMin at `from` to hzMax at `to`, reversed where inverted. */
    private fun rateAt(band: StrobeBandSpec, level: Int): Double {
        val lo = band.hzMin ?: return 0.0
        val hi = band.hzMax ?: return 0.0
        val span = band.to - band.from
        var f = if (span > 0) (level - band.from).toDouble() / span else 0.0
        if (band.inverted) f = 1 - f
        return lo + (hi - lo) * f
    }

    private fun assertBandsWellFormed(where: String, bands: List<StrobeBandSpec>) {
        var previous = -1
        for (band in bands) {
            assertTrue(band.from in 0..255 && band.to in 0..255 && band.from <= band.to, "$where: band ${band.from}–${band.to}")
            assertTrue(band.from > previous, "$where: band ${band.from}–${band.to} overlaps or is out of DMX order")
            previous = band.to
            assertRate("$where ${band.from}–${band.to}", band.kind, band.hzMin, band.hzMax)
        }
    }

    private fun assertRate(where: String, kind: StrobeKind, hzMin: Double?, hzMax: Double?) {
        if (kind.flashes) {
            assertTrue(hzMin != null && hzMax != null, "$where: a $kind band declares its rate")
            assertTrue(hzMin > 0.0 && hzMin <= hzMax, "$where: rate $hzMin–$hzMax Hz")
        } else {
            assertTrue(hzMin == null && hzMax == null, "$where: a $kind band declares no rate")
        }
    }
}

/** What no library type has yet: a setting-backed STROBE channel (fixture optics plan D12). */
@FixtureType("test-strobe-shutter-head")
internal class TestStrobeShutterHead(
    universe: Universe,
    key: String,
    firstChannel: Int,
    private val transaction: ControllerTransaction? = null,
) : DmxFixture(universe, firstChannel, 1, key, key) {

    enum class Shutter(
        override val level: UByte,
        override val strobeKind: StrobeKind,
        override val hzMin: Double? = null,
        override val hzMax: Double? = null,
    ) : DmxFixtureStrobeSettingValue {
        SHUT(0u, StrobeKind.CLOSED),
        OPEN(64u, StrobeKind.OPEN),
        FLASH(128u, StrobeKind.STROBE, 2.0, 12.0),
    }

    override fun withTransaction(transaction: ControllerTransaction): TestStrobeShutterHead =
        TestStrobeShutterHead(universe, key, firstChannel, transaction)

    @FixtureProperty("Shutter", category = PropertyCategory.STROBE)
    val shutter = DmxFixtureSetting(transaction, universe, firstChannel, Shutter.entries.toTypedArray())
}
