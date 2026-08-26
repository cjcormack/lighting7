package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.WindowedColour
import java.awt.Color
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The FX engine caches what each effect's target expands to
 * ([FxTargetExpansion], keyed on [Fixtures.structureVersion] + `elementFilter`) instead of
 * re-walking the group and its members on every tick. These tests are what stands behind that
 * cache being *invalidated*.
 *
 * They exist because nothing else in the suite covers it. `FxEngineBenchmark`'s setup guards
 * call `fixtureKeysCoveredBy` once, immediately after `addEffect`, on a rig that never
 * re-registers — so a cache with no invalidation at all passes every one of them.
 * `ParkSurvivesFixtureReloadTest` does re-register, but runs no effects. The shape here follows
 * `LookRegistryTest`'s "a group membership change is picked up after invalidateAll", which is
 * the same hazard one layer up.
 *
 * `Mode48Ch` is the fixture throughout because it is a [uk.me.cormack.lighting7.fixture.group.MultiElementFixture]
 * that deliberately does *not* implement `WithColour` itself — which is what pushes a group
 * colour effect down the element-expansion branch rather than the direct-member one.
 * [DistributionStrategy.UNIFIED] keeps `StaticColour` phase-independent, so "was this element
 * painted?" is a clean yes/no read off the mock controller.
 */
class FxExpansionCacheTest {

    private val universe = Universe(0, 0)

    private companion object {
        const val BAR_CHANNELS = 48
        const val PIXELS_PER_BAR = 12
        val PAINT: Color = Color(200, 40, 10)
    }

    private class Rig(
        val controller: MockDmxController,
        val fixtures: Fixtures,
        val engine: FxEngine,
    )

    /** Channel of the red component of [pixel] on `bar-$bar`. */
    private fun redChannel(bar: Int, pixel: Int) = 1 + bar * BAR_CHANNELS + pixel * 4

    private fun elementKey(bar: Int, pixel: Int) = "bar-$bar.pixel-$pixel"

    /**
     * Register [barCount] bars and the group over them, replacing whatever was there.
     *
     * The same [MockDmxController] instance is re-added each time, so channel values survive
     * the rebuild — which is what lets a test read "did the new bar get painted?" rather than
     * "did the controller get replaced?".
     */
    private fun Fixtures.patchBars(controller: MockDmxController, barCount: Int) {
        register {
            addController(controller)
            val bars = (0 until barCount).map { b ->
                addFixture(
                    LedLightbar12PixelFixture.Mode48Ch(
                        controller.universe,
                        "bar-$b",
                        "Bar $b",
                        1 + b * BAR_CHANNELS,
                    ),
                )
            }
            createGroup<LedLightbar12PixelFixture.Mode48Ch>("bars") { addSpread(bars) }
        }
    }

    private fun newRig(barCount: Int): Rig {
        val controller = MockDmxController(universe)
        val fixtures = Fixtures()
        fixtures.patchBars(controller, barCount)
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
        return Rig(controller, fixtures, engine)
    }

    private fun groupColourEffect(
        elementMode: ElementMode = ElementMode.FLAT,
        elementFilter: ElementFilter = ElementFilter.ALL,
        distribution: DistributionStrategy = DistributionStrategy.UNIFIED,
    ) = FxInstance(
        effect = WindowedColour(ExtendedColour(PAINT)),
        target = ColourTarget.forGroup("bars"),
        timing = FxTiming(beatDivision = BeatDivision.WHOLE),
        blendMode = BlendMode.OVERRIDE,
    ).apply {
        this.elementMode = elementMode
        this.elementFilter = elementFilter
        distributionStrategy = distribution
    }

    private fun tick(n: Long): MasterClock.ClockTick {
        val ticksPerBeat = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % ticksPerBeat).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / ticksPerBeat,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = 1_000_000L + n * 20L,
        )
    }

    /** Blank every channel the rig's [bars] occupy, so the next tick's writes stand alone. */
    private fun Rig.clearChannels(bars: Int) {
        for (channel in 1..bars * BAR_CHANNELS) {
            controller.setValue(channel, 0u, fadeMs = 0)
        }
    }

    /** Pixels of `bar-$bar` whose red channel is non-zero after a tick. */
    private fun Rig.paintedPixels(bar: Int): List<Int> =
        (0 until PIXELS_PER_BAR).filter { controller.getValue(redChannel(bar, it)) > 0u }

    // ---------------------------------------------------------------- invalidation

    @Test
    fun `a group gaining a member is covered after the repatch`() {
        val rig = newRig(barCount = 2)
        val effect = groupColourEffect()
        rig.engine.addEffect(effect)

        assertEquals(
            2 * PIXELS_PER_BAR,
            rig.engine.fixtureKeysCoveredBy(effect).size,
            "two 12-pixel bars expand to 24 element keys",
        )

        rig.fixtures.patchBars(rig.controller, barCount = 3)

        val covered = rig.engine.fixtureKeysCoveredBy(effect)
        assertEquals(
            3 * PIXELS_PER_BAR,
            covered.size,
            "without invalidation the pre-repatch expansion is still served — this is the hazard",
        )
        assertContains(covered, elementKey(bar = 2, pixel = 0))
    }

    @Test
    fun `a bar added by a repatch is painted on the next tick`() {
        val rig = newRig(barCount = 2)
        rig.engine.addEffect(groupColourEffect())

        rig.engine.processBeatTick(tick(0))
        assertEquals((0 until PIXELS_PER_BAR).toList(), rig.paintedPixels(bar = 0))

        rig.fixtures.patchBars(rig.controller, barCount = 3)
        rig.engine.processBeatTick(tick(1))

        // The operator-visible failure if invalidation is missed: the new bar stays dark while
        // everything else keeps running, which reads as a patch bug rather than a cache bug.
        assertEquals(
            (0 until PIXELS_PER_BAR).toList(),
            rig.paintedPixels(bar = 2),
            "the newly patched bar must be painted, not just reported as covered",
        )
    }

    @Test
    fun `a group losing a member drops those keys and keeps ticking`() {
        val rig = newRig(barCount = 3)
        val effect = groupColourEffect()
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        rig.fixtures.patchBars(rig.controller, barCount = 2)
        rig.engine.processBeatTick(tick(1))

        val covered = rig.engine.fixtureKeysCoveredBy(effect)
        assertEquals(2 * PIXELS_PER_BAR, covered.size)
        assertTrue(
            covered.none { it.startsWith("bar-2.") },
            "keys for the removed bar must be gone, not merely unreachable: $covered",
        )
        // The surviving bars keep painting — a stale cache whose element keys no longer resolve
        // makes `applyValue` throw into the per-effect catch and the effect goes silently dead.
        assertEquals((0 until PIXELS_PER_BAR).toList(), rig.paintedPixels(bar = 1))
    }

    @Test
    fun `patchListChanged alone invalidates a cached expansion`() {
        val rig = newRig(barCount = 2)
        val effect = groupColourEffect()
        rig.engine.addEffect(effect)

        // Poison the cache with a same-version "nothing to do". Only a version bump can
        // dislodge it, so this isolates the second trigger from the register rebuild.
        effect.expansion = FxTargetExpansion.none(rig.fixtures.structureVersion, effect.elementFilter)
        assertEquals(
            emptyList(),
            rig.engine.fixtureKeysCoveredBy(effect),
            "a same-version cache entry is served as-is — otherwise this test proves nothing",
        )

        rig.fixtures.patchListChanged()

        assertEquals(2 * PIXELS_PER_BAR, rig.engine.fixtureKeysCoveredBy(effect).size)
    }

    // ---------------------------------------------------------------- filter and mode

    @Test
    fun `an element filter narrows what is painted but not what is covered`() {
        val rig = newRig(barCount = 1)
        val effect = groupColourEffect(elementFilter = ElementFilter.ODD)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        // Coverage is deliberately unfiltered: the reset pass returns every element the effect
        // owns to the layer below, including the ones this filter never repaints.
        assertEquals(PIXELS_PER_BAR, rig.engine.fixtureKeysCoveredBy(effect).size)
        assertEquals(listOf(0, 2, 4, 6, 8, 10), rig.paintedPixels(bar = 0))
    }

    @Test
    fun `changing the element filter re-expands without a repatch`() {
        val rig = newRig(barCount = 1)
        val effect = groupColourEffect(elementFilter = ElementFilter.ODD)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))
        assertEquals(listOf(0, 2, 4, 6, 8, 10), rig.paintedPixels(bar = 0))

        rig.engine.updateEffect(effect.id, newElementFilter = ElementFilter.EVEN)
        rig.clearChannels(bars = 1)
        rig.engine.processBeatTick(tick(1))

        assertEquals(listOf(1, 3, 5, 7, 9, 11), rig.paintedPixels(bar = 0))
        assertEquals(
            PIXELS_PER_BAR,
            rig.engine.fixtureKeysCoveredBy(effect).size,
            "the filter moved, so coverage must not",
        )
    }

    @Test
    fun `element mode selects between the two filtered shapes of one expansion`() {
        val rig = newRig(barCount = 2)
        val effect = groupColourEffect(
            elementMode = ElementMode.FLAT,
            elementFilter = ElementFilter.FIRST_HALF,
        )
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(0))

        // FLAT filters on the global index across all 24 elements: the first bar, entire.
        assertEquals((0 until PIXELS_PER_BAR).toList(), rig.paintedPixels(bar = 0))
        assertEquals(emptyList(), rig.paintedPixels(bar = 1))

        rig.engine.updateEffect(effect.id, newElementMode = ElementMode.PER_FIXTURE)
        rig.clearChannels(bars = 2)
        rig.engine.processBeatTick(tick(1))

        // PER_FIXTURE filters within each bar, on its own 12: the first half of both.
        assertEquals(listOf(0, 1, 2, 3, 4, 5), rig.paintedPixels(bar = 0))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), rig.paintedPixels(bar = 1))
    }

    @Test
    fun `an effect naming a missing group covers nothing and survives repeated ticks`() {
        val rig = newRig(barCount = 1)
        val effect = FxInstance(
            effect = WindowedColour(ExtendedColour(PAINT)),
            target = ColourTarget.forGroup("no-such-group"),
            timing = FxTiming(beatDivision = BeatDivision.WHOLE),
        )
        rig.engine.addEffect(effect)

        repeat(50) { rig.engine.processBeatTick(tick(it.toLong())) }

        assertEquals(emptyList(), rig.engine.fixtureKeysCoveredBy(effect))
    }

    // ---------------------------------------------------------------- distribution plans

    /**
     * The per-member offsets and [EffectContext]s ([FxDistributionPlans]) are cached on the
     * instance beside the expansion and validated against it *by identity*, plus equality on
     * the distribution strategy. Those are the two ways they can go stale, and neither is
     * covered by anything above: the expansion tests all run [DistributionStrategy.UNIFIED],
     * whose offsets are zero whatever the member count.
     */
    @Test
    fun `changing the distribution strategy moves the offsets on the next tick`() {
        val rig = newRig(barCount = 2)
        val effect = groupColourEffect(distribution = DistributionStrategy.LINEAR)
        rig.engine.addEffect(effect)

        // 24 flat elements, step-timed, WHOLE (4 beats) each: one cycle is 24 × 4 × 24 = 2304
        // ticks, so each element owns a 96-tick window and tick 12 sits inside the first one.
        // Mid-window rather than on the boundary: at tick 0 the last element's offset lands
        // exactly on the window edge and whether it paints is a floating-point coin toss.
        rig.engine.processBeatTick(tick(12))
        assertEquals(listOf(0), rig.paintedPixels(bar = 0))
        assertEquals(emptyList(), rig.paintedPixels(bar = 1))

        effect.distributionStrategy = DistributionStrategy.REVERSE
        rig.clearChannels(bars = 2)
        rig.engine.processBeatTick(tick(12))

        // REVERSE puts offset 0 on the *last* element. A plan cached under LINEAR would keep
        // painting bar 0 pixel 0 — the strategy edit would appear to do nothing.
        assertEquals(emptyList(), rig.paintedPixels(bar = 0))
        assertEquals(listOf(PIXELS_PER_BAR - 1), rig.paintedPixels(bar = 1))
    }

    @Test
    fun `a repatch that grows the group rebuilds the offsets, not just the key list`() {
        val rig = newRig(barCount = 2)
        val effect = groupColourEffect(distribution = DistributionStrategy.LINEAR)
        rig.engine.addEffect(effect)
        rig.engine.processBeatTick(tick(12))

        rig.fixtures.patchBars(rig.controller, barCount = 3)
        rig.clearChannels(bars = 3)

        // 36 elements now, so one cycle is 36 × 4 × 24 = 3456 ticks and flat element 24 —
        // bar 2's first pixel — owns ticks 2304..2399. 2352 is the middle of that window.
        rig.engine.processBeatTick(tick(2352))

        // A plan still sized for 24 elements indexes out of bounds at the 25th, which the
        // per-effect catch swallows: the new bar simply never lights.
        assertEquals(listOf(0), rig.paintedPixels(bar = 2))
        assertEquals(emptyList(), rig.paintedPixels(bar = 0))
    }

    // ---------------------------------------------------------------- version contract

    @Test
    fun `structureVersion moves only for changes an expansion can see`() {
        val rig = newRig(barCount = 1)

        val afterRegister = rig.fixtures.structureVersion
        rig.fixtures.patchBars(rig.controller, barCount = 1)
        assertNotEquals(afterRegister, rig.fixtures.structureVersion, "register must bump")

        val afterRepatch = rig.fixtures.structureVersion
        rig.fixtures.patchListChanged()
        assertNotEquals(afterRepatch, rig.fixtures.structureVersion, "patchListChanged must bump")

        // Signals that provably cannot change what a target expands to. `setPatchMetadata`
        // carries only the stage-placement fields, which `DbFixtureLoader` never reads when
        // building fixtures or groups.
        val afterPatchList = rig.fixtures.structureVersion
        rig.fixtures.setPatchMetadata("bar-0", Fixtures.FixturePatchMetadata(gelCode = "L201"))
        rig.fixtures.lookListChanged()
        rig.fixtures.templateListChanged()
        rig.fixtures.riggingListChanged()
        assertEquals(afterPatchList, rig.fixtures.structureVersion)
    }

    @Test
    fun `concurrent bumps do not lose an increment`() {
        val rig = newRig(barCount = 1)
        val start = rig.fixtures.structureVersion
        val threads = 8
        val perThread = 500

        // A plain `@Volatile var` with `++` loses updates here, and can momentarily move the
        // version *backwards* — which would turn a stale expansion into a valid-looking one.
        val pool = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(1)
        try {
            repeat(threads) {
                pool.submit {
                    latch.await()
                    repeat(perThread) { rig.fixtures.patchListChanged() }
                }
            }
            latch.countDown()
            pool.shutdown()
            assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "bump workers did not finish")
        } finally {
            pool.shutdownNow()
        }

        assertEquals(start + threads * perThread, rig.fixtures.structureVersion)
    }
}
