package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LaserworldCS1000RGBMk3Fixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fixture.dmx.Scantastic4Fixture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.KProperty1
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * [FixturePropertyCatalogue] replaced a `memberProperties` scan that ran in [Fixture]'s
 * *constructor*. These tests stand behind the two claims that makes it safe: that the catalogue
 * is genuinely shared per class, and that "per class" is the correct key.
 *
 * They exist because nothing else in the suite covers either. The scan's result was previously
 * per-instance, so no existing test could distinguish a shared catalogue from a rebuilt one, and
 * every consumer reads it through [Fixture.fixtureProperties] — which behaves identically either
 * way right up until someone caches a resolved *value* in it. The `hasColour` test below is the
 * one that would catch that.
 *
 * `FxEngineBenchmark` measures the win but asserts nothing about it; a catalogue keyed on
 * something wrong would still be fast.
 */
class FixturePropertyCatalogueTest {

    private val universe = Universe(0, 0)
    private val controller = MockDmxController(universe)

    private fun hex(key: String, firstChannel: Int) =
        HexFixture(universe, key, key, firstChannel)

    @Test
    fun `two instances of one class share a single catalogue`() {
        val a = hex("hex-a", 1)
        val b = hex("hex-b", 13)

        assertSame(
            FixturePropertyCatalogue.of(a::class),
            FixturePropertyCatalogue.of(b::class),
            "the catalogue must be per class, not per instance",
        )
        assertSame(
            a.fixtureProperties, b.fixtureProperties,
            "fixtureProperties must be the shared catalogue list, not a per-instance copy",
        )
    }

    @Test
    fun `binding a fixture to a transaction does not rebuild its catalogue`() {
        // This is the actual hot path: FxEngine builds a fresh ControllerTransaction per tick and
        // Fixtures.FixturesWithTransaction binds each fixture by *constructing a new instance*.
        // Before the catalogue that constructor re-ran a full memberProperties scan, 50x/s.
        val fixture = hex("hex-a", 1)
        val bound = fixture.withTransaction(ControllerTransaction(listOf(controller)))

        assertNotSame(fixture, bound, "withTransaction is expected to construct a new instance")
        assertSame(
            fixture.fixtureProperties, bound.fixtureProperties,
            "a transaction-bound copy must reuse the class catalogue",
        )
    }

    @Test
    fun `each mode of a multi-mode family gets its own catalogue`() {
        // The cache keys on the runtime class, and a fixture's mode *is* its class. Sharing one
        // catalogue across modes would hand a 4-channel fixture the 48-channel property set.
        val fourCh = LedLightbar12PixelFixture.Mode4ChRgbw(universe, "bar-4", "Bar 4", 1)
        val fortyEightCh = LedLightbar12PixelFixture.Mode48Ch(universe, "bar-48", "Bar 48", 100)

        assertNotSame(
            FixturePropertyCatalogue.of(fourCh::class),
            FixturePropertyCatalogue.of(fortyEightCh::class),
        )
        assertNotEqualsProperties(fourCh, fortyEightCh)
    }

    private fun assertNotEqualsProperties(a: Fixture, b: Fixture) {
        assertTrue(
            a.fixtureProperties.map { it.name }.toSet() != b.fixtureProperties.map { it.name }.toSet(),
            "two modes with identical property sets would make this test vacuous",
        )
    }

    @Test
    fun `the catalogue describes the class while values stay per instance`() {
        // Scantastic4's ScannerHead declares `colourPattern` as
        // `if (hasColour) DmxSlider(...) else null` — one class, two instance shapes. The
        // catalogue *entry* must exist for both, because it is a property of the class; only
        // reading it through the receiver may yield null.
        //
        // This is the invariant that forbids ever caching resolved values in here.
        val withColour = Scantastic4Fixture.Mode12Ch(universe, "scan-12", "Scan 12", 1)
        val withoutColour = Scantastic4Fixture.Mode17Ch(universe, "scan-17", "Scan 17", 100)

        val headWith = withColour.elements.first()
        val headWithout = withoutColour.elements.first()
        assertSame(
            FixturePropertyCatalogue.of(headWith::class),
            FixturePropertyCatalogue.of(headWithout::class),
            "both modes build the same ScannerHead class, so they must share one catalogue",
        )

        val entry = FixturePropertyCatalogue.of(headWith::class).byName["colourPattern"]
        assertNotNull(entry, "the catalogue entry is a fact of the class and must be present")

        @Suppress("UNCHECKED_CAST")
        val accessor = entry.classProperty as KProperty1<Any, *>
        assertNotNull(accessor.call(headWith), "the 12CH head really does have a colour slider")
        assertNull(accessor.call(headWithout), "the 17CH head really does not")
    }

    @Test
    fun `bundled colour sliders are indexed by category`() {
        val fixture = hex("hex-a", 1)
        val bundled = FixturePropertyCatalogue.of(fixture::class).bundledByCategory

        assertEquals(
            setOf(PropertyCategory.WHITE, PropertyCategory.AMBER, PropertyCategory.UV),
            bundled.keys,
            "HexFixture declares white, amber and uv as bundleWithColour",
        )
        assertEquals("white", bundled[PropertyCategory.WHITE]?.name)
        assertEquals("amber", bundled[PropertyCategory.AMBER]?.name)
        assertEquals("uv", bundled[PropertyCategory.UV]?.name)
        assertEquals("rgbColour", FixturePropertyCatalogue.of(fixture::class).colour?.name)

        // The empty case is the one ColourTarget relies on to skip work — a fixture with no
        // bundled sliders must produce an empty index, not a partly-populated one.
        val noBundles = LaserworldCS1000RGBMk3Fixture(universe, "laser", "Laser", 1)
        assertTrue(FixturePropertyCatalogue.of(noBundles::class).bundledByCategory.isEmpty())
    }

    @Test
    fun `element classes are catalogued even though they are not Fixtures`() {
        // FixtureElement does not extend Fixture, so elements have no `fixtureProperties` of
        // their own — the catalogue is the only route to their @FixtureProperty metadata.
        val bar = LedLightbar12PixelFixture.Mode48Ch(universe, "bar", "Bar", 1)
        val pixel = bar.elements.first()

        val white = FixturePropertyCatalogue.of(pixel::class).byName["white"]
        assertNotNull(white)
        assertTrue(white.bundleWithColour, "RgbwPixel.white is declared bundleWithColour")
        assertEquals(
            white,
            FixturePropertyCatalogue.of(pixel::class).bundledByCategory[PropertyCategory.WHITE],
        )
    }

    /**
     * A class nothing else in the suite ever catalogues, so the race below really is against a
     * *cold* key. `of` takes any [kotlin.reflect.KClass], so this needn't be a [Fixture] — and it
     * must not be one, or some other test constructing it would warm the cache first.
     */
    private class ColdClass {
        @FixtureProperty(category = PropertyCategory.DIMMER)
        val dimmer: Any? = null
    }

    @Test
    fun `concurrent first construction of one class yields one catalogue`() {
        // computeIfAbsent, not getOrPut: fixtures are constructed from request threads and the
        // tick loop at once, and two threads racing a cold class must not each run the scan.
        // getOrPut on a ConcurrentHashMap is get-then-put with no atomicity, so racing threads
        // would each build an Entry and hand back their own — which is what this catches. It
        // only catches it while the key is cold, hence [ColdClass] rather than HexFixture, which
        // every other test in this class has already catalogued by the time this one runs.
        val threads = 8
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        try {
            val results = (0 until threads).map {
                pool.submit<FixturePropertyCatalogue.Entry> {
                    start.await()
                    FixturePropertyCatalogue.of(ColdClass::class)
                }
            }
            start.countDown()
            val entries = results.map { it.get(30, TimeUnit.SECONDS) }
            assertTrue(
                entries.all { it === entries.first() },
                "every thread must observe the same Entry instance",
            )
            assertEquals(listOf("dimmer"), entries.first().all.map { it.name })
        } finally {
            pool.shutdownNow()
        }
    }
}
