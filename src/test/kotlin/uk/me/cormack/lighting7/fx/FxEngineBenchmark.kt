package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import uk.me.cormack.lighting7.bench.allocatedBytes
import uk.me.cormack.lighting7.bench.summarize
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fixture.dmx.LedLightbar12PixelFixture
import uk.me.cormack.lighting7.fx.group.DistributionStrategy
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.SpeedMasterSource
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.testsupport.HueSweepColour
import uk.me.cormack.lighting7.testsupport.SineSlider
import uk.me.cormack.lighting7.testsupport.SteppedColour
import uk.me.cormack.lighting7.testsupport.WindowedSlider
import java.awt.Color
import java.util.UUID
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Per-tick allocation & latency benchmark for [FxEngine]'s hot paths.
 *
 * Six independent scenarios, each with its own rig (engine state, cue assignments and the
 * effect registry all persist per rig, so sharing one would make the numbers order-dependent):
 *
 * 1. **`beat and wall-clock tick throughput`** — the original scenario. 4 universes of
 *    [HexFixture] with a beat [SliderTarget] and a wall-clock [SliderTarget] per fixture.
 *    Deliberately frozen: it is what the 2026-04-22 baseline was measured on.
 * 2. **`group colour chase on multi-element fixtures across two masters`** — sweep item
 *    C1 (per-tick target re-expansion) and the C6 allocation bundle.
 * 3. **`crossfade republish throughput`** — sweep item C3.
 * 4. **`colour write path with bundled extended channels`** — sweep item C2's reflective
 *    property resolution. Scenario 2 was originally claimed to cover this; it does not, for
 *    the reason set out on [newColourRig].
 * 5. **`cue spawn cost`** — sweep item C7. The only scenario that measures *adding* effects
 *    rather than ticking them: scenarios 1-4 spawn in their rig setup, outside every measured
 *    window, so none of them can see this path at all. It reports both shapes side by side in
 *    one run, which is what makes it a self-contained before/after.
 * 6. **`layer stack cook cost`** — sweep item C8, and the only scenario that measures
 *    [CueComposer] rather than [FxEngine]: the cook runs per stack mutation and per cue GO,
 *    which no tick-path rig reaches. Like scenario 5 it reports both shapes in one run.
 *
 * See `docs/plans/backend-post-refactor-sweep.md` §C. Scenarios 2-4 exist because the
 * original rig touches none of those paths: measuring a C-wave "before" on scenario 1 alone
 * would be measuring code the fix does not run.
 *
 * **Skipped by default.** Takes ~30s of wall time and isn't useful on every CI run. Invoke with:
 *
 * ```
 * ./gradlew :test --tests "uk.me.cormack.lighting7.fx.FxEngineBenchmark" \
 *     -Dfx.benchmark=true
 * ```
 *
 * Track-only: the output is printed to stdout for humans to read, and the recorded baselines
 * live in `docs/testing-engineering.md` §"Opt-in harnesses". Turning it into a
 * fail-on-regression gate (±20% against a committed baseline) is `FU-TEST-FX-BENCH-CI-GATE`,
 * deferred pending a variance study on real CI hardware.
 *
 * Caveat on the wall-clock windows: [FxEngine.processWallClockTickSuspend] derives its
 * `deltaMs` from the real `System.currentTimeMillis()`, so `[wall]` and `[chase-wall]` are
 * inherently noisier run-to-run than the beat windows, which are driven by synthetic ticks.
 */
class FxEngineBenchmark {

    private companion object {
        const val BENCHMARK_FLAG = "fx.benchmark"
        const val UNIVERSES = 4
        const val FIXTURES_PER_UNIVERSE = 64
        const val HEX_CHANNELS = 12
        /** 50 Hz wall-clock. */
        const val WALL_CLOCK_TICKS = 500
        /** 10 s at 120 BPM × 24 tpb = 4 800 beat ticks. Keep the run under ~10 s wall time. */
        const val BEAT_TICKS = 2_400

        /**
         * Multi-element bars per universe. 10 × 48 ch = 480, inside the 512-channel universe.
         */
        const val BARS_PER_UNIVERSE = 10
        const val BAR_CHANNELS = 48
        const val PIXELS_PER_BAR = 12
        /** Element-heavy rig: fewer ticks than scenario 1 keeps the class inside its budget. */
        const val CHASE_BEAT_TICKS = 1_200
        const val CHASE_WALL_TICKS = 500

        /** 5 s of crossfade at `CueCrossfadeDriver.CROSSFADE_TICK_MS` (16 ms) ≈ 312 frames. */
        const val CROSSFADE_FRAMES = 312

        /** Colour rig: same fixture count as scenario 1, same tick budget as scenario 2. */
        const val COLOUR_BEAT_TICKS = 1_200

        /**
         * Spawn rig: how many effects one cue GO puts up, and how many whole spawns are
         * measured. One effect per fixture is a plausible full-rig cue; the quadratic this
         * scenario exists to catch is 1+2+…+N, so N is the only knob that matters.
         */
        const val SPAWN_EFFECTS = 168
        const val SPAWN_REPEATS = 8
        const val WARMUP_SPAWNS = 3

        /**
         * Cook rig — sweep item C8. A Look holding [COOK_LOOK_ROWS] bound rows on one group is
         * what makes the per-row group expansion and allowed-set rebuild visible; a second Look
         * carries the bound effects `coversTarget` used to re-expand for, and a template layer
         * over the same group is what pays the per-head intent parse and the double catalogue
         * walk in [TemplateResolver].
         */
        const val COOK_LOOK_ROWS = 40
        const val COOK_LOOK_EFFECTS = 24
        const val COOK_REPEATS = 200
        const val WARMUP_COOKS = 40
        const val OUTGOING_CUE = 1
        const val INCOMING_CUE = 2

        const val WARMUP_BEAT_TICKS = 200
        const val WARMUP_WALL_TICKS = 20
        const val WARMUP_FRAMES = 60

        const val MASTER_1_BPM = 120.0
        const val MASTER_2_BPM = 90.0
    }

    private fun assumeBenchmarkEnabled() = Assume.assumeTrue(
        "Set -D$BENCHMARK_FLAG=true to run the FxEngine benchmark",
        System.getProperty(BENCHMARK_FLAG) == "true",
    )

    /**
     * Synthetic tick [n] for a master running at [bpm]. Timestamps advance at that master's
     * own tick rate, which is what makes two masters in one [SpeedMasterBank.Frame] drift
     * apart the way they do in production.
     */
    private fun beatTick(n: Long, startMs: Long, bpm: Int = 120): MasterClock.ClockTick {
        val tpb = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % tpb).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / tpb,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = startMs + (n * (60_000 / (bpm * tpb))),
        )
    }

    // ─── Scenario 1: the frozen original ────────────────────────────────────

    private data class Rig(
        val controllers: List<MockDmxController>,
        val engine: FxEngine,
        val fixtures: Fixtures,
    )

    /** Scenario 1's fixture rig, shared with scenario 5 so both measure the same population. */
    private fun newRigFixtures(
        controllers: List<MockDmxController> = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) },
    ): Fixtures {
        val fixtures = Fixtures()
        fixtures.register {
            controllers.forEach { addController(it) }
            for (u in 0 until UNIVERSES) {
                val universe = controllers[u].universe
                for (f in 0 until FIXTURES_PER_UNIVERSE) {
                    val first = 1 + f * HEX_CHANNELS
                    if (first + HEX_CHANNELS - 1 > 512) break
                    addFixture(HexFixture(universe, "u${u}-hex-${f}", "U$u Hex $f", first))
                }
            }
        }
        return fixtures
    }

    private fun newRig(): Rig {
        val controllers = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) }
        val fixtures = newRigFixtures(controllers)
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )

        // Populate half the fixtures with cue-level Layer 4 dimmer assignments so
        // `resetActiveProperties` actually has Layer 4 work to do during effect reset.
        val assignments = fixtures.fixtures.filterIndexed { i, _ -> i % 2 == 0 }.map { f ->
            CueAssignmentResolver.Assignment(
                cueId = 1,
                priority = 1,
                fadeWeight = 1.0,
                targetKey = f.key,
                targetIsGroup = false,
                propertyName = "dimmer",
                category = PropertyCategory.DIMMER,
                compositionOverride = CompositionRule.UNSET,
                value = CueAssignmentResolver.PropertyValue.Slider(128u),
            )
        }
        engine.cueLayer.setAssignments(1, assignments)

        // Two effects per fixture: one beat-synced SineSlider, one wall-clock WindowedSlider.
        val allFixtures = fixtures.fixtures
        for (f in allFixtures) {
            val beatFx = FxInstance(
                effect = SineSlider(),
                target = SliderTarget(f.key, "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.MAX,
            )
            engine.addEffect(beatFx)

            val wallFx = FxInstance(
                effect = WindowedSlider(value = 80u),
                target = SliderTarget(f.key, "uv"),
                timing = FxTiming(beatDivision = 1.0),
                blendMode = BlendMode.OVERRIDE,
            ).apply { timingSource = TimingSource.WALL_CLOCK }
            engine.addEffect(wallFx)
        }
        return Rig(controllers, engine, fixtures)
    }

    @Test
    fun `beat and wall-clock tick throughput`() {
        assumeBenchmarkEnabled()

        val rig = newRig()
        println(
            "[setup] universes=$UNIVERSES fixtures=${rig.fixtures.fixtures.size} " +
                "effects=${rig.engine.getActiveEffects().size}",
        )

        // Warm up: JIT & load class paths so the measured window isn't paying for first-time
        // compilation of the hot loops.
        val warmupStart = System.currentTimeMillis()
        for (n in 0L until WARMUP_BEAT_TICKS.toLong()) rig.engine.processBeatTick(beatTick(n, warmupStart))
        for (n in 0L until WARMUP_WALL_TICKS.toLong()) rig.engine.processWallClockTick()

        // Beat-tick measurement.
        val beatTimings = LongArray(BEAT_TICKS)
        val beatStart = System.currentTimeMillis()
        val beatAllocBefore = allocatedBytes()
        for (n in 0L until BEAT_TICKS.toLong()) {
            val t = beatTick(n + WARMUP_BEAT_TICKS, beatStart)
            beatTimings[n.toInt()] = measureNanoTime { rig.engine.processBeatTick(t) }
        }
        val beatAlloc = allocatedBytes().takeIf { it >= 0 && beatAllocBefore >= 0 }
            ?.let { it - beatAllocBefore } ?: -1L
        val beatStats = summarize("beat", beatTimings, beatAlloc, sampleName = "tick")

        // Wall-clock measurement.
        val wallTimings = LongArray(WALL_CLOCK_TICKS)
        val wallAllocBefore = allocatedBytes()
        for (n in 0 until WALL_CLOCK_TICKS) {
            wallTimings[n] = measureNanoTime { rig.engine.processWallClockTick() }
        }
        val wallAlloc = allocatedBytes().takeIf { it >= 0 && wallAllocBefore >= 0 }
            ?.let { it - wallAllocBefore } ?: -1L
        val wallStats = summarize("wall", wallTimings, wallAlloc, sampleName = "tick")

        // Track-only: no hard assertion. A future pass turns these prints into a committed
        // baseline + ±20% regression gate. For now, a trivial floor assertion catches obvious
        // breakage (e.g. a tick that somehow takes a full second to run).
        check(beatStats.p99Ns < 1_000_000_000L) { "beat p99 tick > 1s: ${beatStats.p99Ns} ns" }
        check(wallStats.p99Ns < 1_000_000_000L) { "wall p99 tick > 1s: ${wallStats.p99Ns} ns" }
    }

    // ─── Scenario 2: group colour chase, multi-element, two masters ─────────

    private data class ChaseRig(
        val engine: FxEngine,
        val fixtures: Fixtures,
        val elementsPerGroup: Int,
    )

    /**
     * A rig of [LedLightbar12PixelFixture.Mode48Ch] bars in two groups, driven by two speed
     * masters.
     *
     * The fixture choice is load-bearing, not incidental:
     * - `Mode48Ch` implements `MultiElementFixture<RgbwPixel>` and deliberately does *not*
     *   implement `WithColour` itself. That is what pushes `processGroupEffect` past its
     *   direct-application branch and down the element-expansion path this scenario exists
     *   to measure (C1).
     * - `RgbwPixel` is `WithColour, WithWhite` with `bundleWithColour = true` on `white`, which
     *   drives the element branch of [PropertyChannelWriter]'s per-class property catalogue.
     *
     * This scenario does **not** measure sweep item C2, though it claimed to until 2026-08-24.
     * `RgbwPixel` is a [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement],
     * and `FixtureElement` does not extend [Fixture] — so the
     * `if (fixture is Fixture)` guard on every one of [ColourTarget]'s bundled W/A/UV helpers
     * is false here and none of them run. `Fixtures.resolveElement` likewise wraps only the
     * element, so this rig constructs no [Fixture] per tick and pays none of the constructor
     * reflection either. Scenario 4 is where C2 is measured; what remains here is C1's group
     * expansion and C6's per-member permutation.
     *
     * If a cheaper fixture were substituted here the harness would still run and still print
     * plausible numbers, while measuring none of the above. The setup guards below are what
     * stop that happening silently.
     */
    private fun newChaseRig(): ChaseRig {
        val controllers = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) }
        val fixtures = Fixtures()
        lateinit var barsA: List<LedLightbar12PixelFixture.Mode48Ch>
        lateinit var barsB: List<LedLightbar12PixelFixture.Mode48Ch>
        fixtures.register {
            controllers.forEach { addController(it) }
            val byUniverse = (0 until UNIVERSES).map { u ->
                (0 until BARS_PER_UNIVERSE).map { b ->
                    addFixture(
                        LedLightbar12PixelFixture.Mode48Ch(
                            controllers[u].universe,
                            "u${u}-bar-${b}",
                            "U$u Bar $b",
                            1 + b * BAR_CHANNELS,
                        ),
                    )
                }
            }
            barsA = byUniverse[0] + byUniverse[1]
            barsB = byUniverse[2] + byUniverse[3]
            createGroup<LedLightbar12PixelFixture.Mode48Ch>("bars-a") { addSpread(barsA) }
            createGroup<LedLightbar12PixelFixture.Mode48Ch>("bars-b") { addSpread(barsB) }
        }

        val master1 = UUID.randomUUID()
        val master2 = UUID.randomUUID()
        val speedMasters = SpeedMasterBank().apply {
            load(
                listOf(
                    SpeedMasterSnapshot(master1, 1, "Master 1", MASTER_1_BPM, SpeedMasterSource.MANUAL),
                    SpeedMasterSnapshot(master2, 2, "Master 2", MASTER_2_BPM, SpeedMasterSource.MANUAL),
                ),
            )
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = speedMasters,
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )

        // Layer 4 colour rows on every element of group A, keyed by element key — so the
        // effect-reset pass has real Layer 4 work under the chase, as scenario 1 does with
        // dimmers. `Fixtures.resolveElement` is what turns these keys back into elements.
        val elementKeys = barsA.flatMap { bar -> bar.elements.map { it.elementKey } }
        engine.cueLayer.setAssignments(
            1,
            elementKeys.map { key ->
                CueAssignmentResolver.Assignment(
                    cueId = 1,
                    priority = 1,
                    fadeWeight = 1.0,
                    targetKey = key,
                    targetIsGroup = false,
                    propertyName = "rgbColour",
                    category = PropertyCategory.COLOUR,
                    compositionOverride = CompositionRule.UNSET,
                    value = CueAssignmentResolver.PropertyValue.Colour(
                        ExtendedColour(Color(40, 0, 90), white = 20u),
                    ),
                )
            },
        )

        // Built by hand rather than via `FixtureGroup.applyColourFx`: that extension is
        // constrained to `T : WithColour`, which the multi-element parent isn't — the
        // ergonomic API can't express this rig at all.
        val flatChase = FxInstance(
            effect = HueSweepColour(),
            target = ColourTarget.forGroup("bars-a"),
            timing = FxTiming(beatDivision = BeatDivision.ONE_BAR),
            blendMode = BlendMode.OVERRIDE,
        ).apply {
            elementMode = ElementMode.FLAT
            distributionStrategy = DistributionStrategy.LINEAR
        }
        engine.addEffect(flatChase)

        // Second master, and the RANDOM strategy whose per-member Fisher-Yates C6 calls out.
        val randomChase = FxInstance(
            effect = HueSweepColour(saturation = 0.8f),
            target = ColourTarget.forGroup("bars-b"),
            timing = FxTiming(beatDivision = BeatDivision.ONE_BAR),
            blendMode = BlendMode.OVERRIDE,
        ).apply {
            elementMode = ElementMode.FLAT
            distributionStrategy = DistributionStrategy.RANDOM()
            speedMasterUuid = master2
        }
        engine.addEffect(randomChase)

        // PER_FIXTURE wall-clock effect rate-scaled by master 2 — exercises
        // `processElementKeys` under `PhaseSource.WallClock` and `SpeedMasterBank.rateScales()`
        // against a bank with more than one entry.
        val wallChase = FxInstance(
            effect = SteppedColour(
                colours = listOf(
                    ExtendedColour(Color.RED),
                    ExtendedColour(Color.GREEN),
                    ExtendedColour(Color.BLUE, white = 60u),
                ),
            ),
            target = ColourTarget.forGroup("bars-a"),
            timing = FxTiming(beatDivision = 2.0),
            blendMode = BlendMode.MAX,
        ).apply {
            elementMode = ElementMode.PER_FIXTURE
            timingSource = TimingSource.WALL_CLOCK
            rateSpeedMasterUuid = master2
        }
        engine.addEffect(wallChase)

        // The FLAT and PER_FIXTURE arms take different lists out of the cached expansion
        // (`flat` vs `perFixture`), and the PER_FIXTURE effect above never reaches the FLAT
        // one — so the wall-clock loop gets its own FLAT effect, otherwise half of what C1's
        // fix touches would be unmeasured.
        val wallFlatChase = FxInstance(
            effect = HueSweepColour(brightness = 0.7f),
            target = ColourTarget.forGroup("bars-b"),
            timing = FxTiming(beatDivision = 4.0),
            blendMode = BlendMode.MAX,
        ).apply {
            elementMode = ElementMode.FLAT
            timingSource = TimingSource.WALL_CLOCK
        }
        engine.addEffect(wallFlatChase)

        val elementsPerGroup = barsA.size * PIXELS_PER_BAR

        // Setup guards. Both element-expansion branches bail quietly — `processGroupEffect`
        // returns when the elements lack the property, `resolveEffectFixtureKeys` returns an
        // empty list — so a subtly wrong rig benchmarks nothing while still printing
        // plausible microsecond numbers. Nothing else in the suite drives a multi-element
        // fixture through the engine, so this is a live failure mode, not a hypothetical.
        val covered = engine.fixtureKeysCoveredBy(flatChase)
        check(covered.size == elementsPerGroup) {
            "group colour effect must expand to $elementsPerGroup element keys, got ${covered.size}" +
                " (first: ${covered.take(3)}) — the multi-element expansion did not fire"
        }
        check(covered.first().contains('.')) {
            "expansion resolved to fixture keys, not element keys: ${covered.first()}"
        }
        check(engine.getActiveEffects().size == 4) {
            "expected 4 chase effects, got ${engine.getActiveEffects().size}"
        }
        // Both element modes on both tick loops — the four branches C1's fix rewrites.
        check(engine.fixtureKeysCoveredBy(wallFlatChase).size == elementsPerGroup) {
            "wall-clock FLAT effect must expand to $elementsPerGroup element keys"
        }
        check(1 in engine.cueLayer.activeCueIds()) { "Layer 4 colour rows did not land" }

        // Since the engine caches each effect's expansion, the checks above only prove the
        // *first* one. Re-check past an invalidation so the numbers below can't be a cache
        // serving garbage. This does NOT prove the cache invalidates — nothing structural
        // changed here — which is `FxExpansionCacheTest`'s job.
        fixtures.patchListChanged()
        check(engine.fixtureKeysCoveredBy(flatChase).size == elementsPerGroup) {
            "expansion did not survive an invalidation"
        }
        check(engine.fixtureKeysCoveredBy(wallFlatChase).size == elementsPerGroup) {
            "wall-clock expansion did not survive an invalidation"
        }

        return ChaseRig(engine, fixtures, elementsPerGroup)
    }

    /**
     * One pass's view of both masters: slot 0 at [MASTER_1_BPM], slot 1 at [MASTER_2_BPM].
     * Slot 1's tick number advances at the ratio of the two tempos, so the masters genuinely
     * drift apart across the run rather than moving in lockstep.
     */
    private fun chaseFrame(n: Long, startMs: Long): SpeedMasterBank.Frame {
        val t1 = beatTick(n, startMs, MASTER_1_BPM.toInt())
        val t2 = beatTick((n * MASTER_2_BPM / MASTER_1_BPM).toLong(), startMs, MASTER_2_BPM.toInt())
        return SpeedMasterBank.Frame(
            arrayOf(t1, t2),
            t1.timestampMs,
        )
    }

    @Test
    fun `group colour chase on multi-element fixtures across two masters`() {
        assumeBenchmarkEnabled()

        val rig = newChaseRig()
        println(
            "[setup] universes=$UNIVERSES bars=${rig.fixtures.fixtures.size} " +
                "elements=${rig.fixtures.fixtures.size * PIXELS_PER_BAR} " +
                "elementsPerGroup=${rig.elementsPerGroup} " +
                "effects=${rig.engine.getActiveEffects().size} masters=2",
        )

        // Single `runBlocking` around each window: the production collect loops are already in
        // a coroutine context, so a `runBlocking` per tick would time the shim, not the pass.
        // Raw `System.nanoTime()` because the measured call is suspend.
        val warmupStart = System.currentTimeMillis()
        runBlocking {
            for (n in 0L until WARMUP_BEAT_TICKS.toLong()) {
                rig.engine.processBeatTickSuspend(chaseFrame(n, warmupStart))
            }
            for (n in 0L until WARMUP_WALL_TICKS.toLong()) rig.engine.processWallClockTickSuspend()
        }

        val beatTimings = LongArray(CHASE_BEAT_TICKS)
        val beatStart = System.currentTimeMillis()
        val beatAllocBefore = allocatedBytes()
        runBlocking {
            for (n in 0 until CHASE_BEAT_TICKS) {
                val frame = chaseFrame(n + WARMUP_BEAT_TICKS.toLong(), beatStart)
                val started = System.nanoTime()
                rig.engine.processBeatTickSuspend(frame)
                beatTimings[n] = System.nanoTime() - started
            }
        }
        val beatAlloc = allocatedBytes().takeIf { it >= 0 && beatAllocBefore >= 0 }
            ?.let { it - beatAllocBefore } ?: -1L
        val beatStats = summarize("chase-beat", beatTimings, beatAlloc, sampleName = "tick")

        val wallTimings = LongArray(CHASE_WALL_TICKS)
        val wallAllocBefore = allocatedBytes()
        runBlocking {
            for (n in 0 until CHASE_WALL_TICKS) {
                val started = System.nanoTime()
                rig.engine.processWallClockTickSuspend()
                wallTimings[n] = System.nanoTime() - started
            }
        }
        val wallAlloc = allocatedBytes().takeIf { it >= 0 && wallAllocBefore >= 0 }
            ?.let { it - wallAllocBefore } ?: -1L
        val wallStats = summarize("chase-wall", wallTimings, wallAlloc, sampleName = "tick")

        check(beatStats.p99Ns < 1_000_000_000L) { "chase-beat p99 tick > 1s: ${beatStats.p99Ns} ns" }
        check(wallStats.p99Ns < 1_000_000_000L) { "chase-wall p99 tick > 1s: ${wallStats.p99Ns} ns" }
    }

    // ─── Scenario 3: crossfade republish ───────────────────────────────────

    private data class CrossfadeRig(
        val engine: FxEngine,
        val fixtures: Fixtures,
        val rowsPerCue: Int,
    )

    /**
     * Two full cues of Layer 4 rows over the [HexFixture] rig, plus a mixed effect population.
     *
     * The effect mix is chosen so both halves of `republishCueAssignments` are paid:
     * - per-fixture dimmer effects *cover* the cues' dimmer rows, so those keys skip the
     *   write path — but they still cost a `resolveEffectFixtureKeys` walk per publish;
     * - a group UV effect adds a group expansion to that same walk without overlapping any
     *   cue row;
     * - the colour rows are covered by nothing, so they go through `resetToFallback` on
     *   every frame whose composed value actually moves.
     */
    private fun newCrossfadeRig(): CrossfadeRig {
        val controllers = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) }
        val fixtures = Fixtures()
        fixtures.register {
            controllers.forEach { addController(it) }
            val hexes = mutableListOf<HexFixture>()
            for (u in 0 until UNIVERSES) {
                val universe = controllers[u].universe
                for (f in 0 until FIXTURES_PER_UNIVERSE) {
                    val first = 1 + f * HEX_CHANNELS
                    if (first + HEX_CHANNELS - 1 > 512) break
                    hexes += addFixture(HexFixture(universe, "u${u}-hex-${f}", "U$u Hex $f", first))
                }
            }
            createGroup<HexFixture>("all-hexes") { addSpread(hexes) }
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )

        fun rowsFor(cueId: Int, priority: Int, level: UByte, colour: Color) =
            fixtures.fixtures.flatMap { f ->
                listOf(
                    CueAssignmentResolver.Assignment(
                        cueId = cueId,
                        priority = priority,
                        fadeWeight = 1.0,
                        targetKey = f.key,
                        targetIsGroup = false,
                        propertyName = "dimmer",
                        category = PropertyCategory.DIMMER,
                        compositionOverride = CompositionRule.UNSET,
                        value = CueAssignmentResolver.PropertyValue.Slider(level),
                    ),
                    CueAssignmentResolver.Assignment(
                        cueId = cueId,
                        priority = priority,
                        fadeWeight = 1.0,
                        targetKey = f.key,
                        targetIsGroup = false,
                        propertyName = "rgbColour",
                        category = PropertyCategory.COLOUR,
                        compositionOverride = CompositionRule.UNSET,
                        value = CueAssignmentResolver.PropertyValue.Colour(ExtendedColour(colour)),
                    ),
                )
            }

        val outgoing = rowsFor(OUTGOING_CUE, priority = 1, level = 200u, colour = Color(255, 40, 0))
        val incoming = rowsFor(INCOMING_CUE, priority = 2, level = 90u, colour = Color(0, 60, 255))
        engine.cueLayer.setAssignments(OUTGOING_CUE, outgoing)
        engine.cueLayer.setAssignments(INCOMING_CUE, incoming)

        for (f in fixtures.fixtures) {
            engine.addEffect(
                FxInstance(
                    effect = SineSlider(),
                    target = SliderTarget(f.key, "dimmer"),
                    timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                    blendMode = BlendMode.MAX,
                ),
            )
        }
        engine.addEffect(
            FxInstance(
                effect = WindowedSlider(value = 120u),
                target = SliderTarget.forGroup("all-hexes", "uv"),
                timing = FxTiming(beatDivision = BeatDivision.WHOLE),
                blendMode = BlendMode.OVERRIDE,
            ),
        )

        val live = engine.cueLayer.activeCueIds()
        check(OUTGOING_CUE in live && INCOMING_CUE in live) {
            "both cues must hold Layer 4 rows before the fade; live=$live"
        }

        return CrossfadeRig(engine, fixtures, outgoing.size)
    }

    /**
     * Weight for frame [i] of [frames]. Deliberately strictly inside `(0, 1)` and different
     * every frame: [CueAssignmentLayer.updateFadeWeights] skips unchanged weights and *removes* the
     * entry at exactly 1.0, so a lazier ramp would benchmark early returns instead of the
     * republish.
     */
    private fun fadeWeight(i: Int, frames: Int): Double = (i + 1).toDouble() / (frames + 1)

    @Test
    fun `crossfade republish throughput`() {
        assumeBenchmarkEnabled()

        val rig = newCrossfadeRig()
        println(
            "[setup] universes=$UNIVERSES fixtures=${rig.fixtures.fixtures.size} " +
                "cueRows=${rig.rowsPerCue * 2} effects=${rig.engine.getActiveEffects().size} " +
                "frames=$CROSSFADE_FRAMES",
        )

        for (i in 0 until WARMUP_FRAMES) {
            val t = fadeWeight(i, WARMUP_FRAMES)
            rig.engine.cueLayer.updateFadeWeights(mapOf(OUTGOING_CUE to 1.0 - t, INCOMING_CUE to t))
        }

        val timings = LongArray(CROSSFADE_FRAMES)
        val allocBefore = allocatedBytes()
        for (i in 0 until CROSSFADE_FRAMES) {
            val t = fadeWeight(i, CROSSFADE_FRAMES)
            timings[i] = measureNanoTime {
                rig.engine.cueLayer.updateFadeWeights(mapOf(OUTGOING_CUE to 1.0 - t, INCOMING_CUE to t))
            }
        }
        val alloc = allocatedBytes().takeIf { it >= 0 && allocBefore >= 0 }
            ?.let { it - allocBefore } ?: -1L
        val stats = summarize("crossfade", timings, alloc, sampleName = "frame")

        check(stats.p99Ns < 1_000_000_000L) { "crossfade p99 frame > 1s: ${stats.p99Ns} ns" }
    }

    // ─── Scenario 4: colour write path with bundled W/A/UV ──────────────────

    private data class ColourRig(
        val engine: FxEngine,
        val fixtures: Fixtures,
        val programmerCovered: Int,
    )

    /**
     * [ColourTarget] over real [Fixture]s carrying bundled W/A/UV sliders — sweep item C2's
     * `fixtureProperties.find {}` + `KProperty1.call` half.
     *
     * This scenario exists because **no other rig reaches that code at all**, contrary to what
     * scenario 2's docs claimed before this was written. [ColourTarget] gates all four of its
     * bundled-channel helpers on `if (fixture is Fixture)`
     * ([ColourTarget.applyValueToFixture], [ColourTarget.resetToFallback], and the compose and
     * park paths), and scenario 2's `RgbwPixel` is a
     * [FixtureElement][uk.me.cormack.lighting7.fixture.group.FixtureElement], which does *not*
     * extend [Fixture]. Elements resolve through
     * [PropertyChannelWriter]'s per-class catalogue instead, so scenario 2 measures the group
     * expansion (C1) and [DistributionStrategy.RANDOM]'s permutation (C6) — not this.
     *
     * The fixture choice is load-bearing: [HexFixture] declares `amber`, `white` **and** `uv`
     * with `bundleWithColour = true`, so each colour write pays the scan three times. A
     * fixture with no bundled sliders would run the same effects and print plausible numbers
     * while measuring nothing.
     *
     * Three of the four bundled helpers are covered:
     * - `applyExtendedChannel` — ungated, once per fixture per tick via the effect write;
     * - `setExtendedChannel` — via `resetToFallback`, which `resetActiveProperties` drives for
     *   every active property every tick, so the Layer 4 colour rows below are required;
     * - `extendedComponent` — via `composeProgrammerOver`, behind the per-fixture gate at
     *   `LayerResolver.fallbackFor`. Half the rig gets a programmer colour entry so the gate is
     *   genuinely exercised in both directions; a sideband entry is deliberately *not* planted,
     *   because any sideband slot disables the gate globally and would make the covered/uncovered
     *   split meaningless.
     *
     * `bundledChannelParked` is **not** covered: it needs a `ParkManager`, `FxEngine` takes one
     * only as a constructor argument defaulting to null, and constructing a real one needs a
     * `Database`. Pulling a DB into this class would cost more than the branch is worth.
     */
    private fun newColourRig(): ColourRig {
        val controllers = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) }
        val fixtures = Fixtures()
        fixtures.register {
            controllers.forEach { addController(it) }
            for (u in 0 until UNIVERSES) {
                val universe = controllers[u].universe
                for (f in 0 until FIXTURES_PER_UNIVERSE) {
                    val first = 1 + f * HEX_CHANNELS
                    if (first + HEX_CHANNELS - 1 > 512) break
                    addFixture(HexFixture(universe, "u${u}-hex-${f}", "U$u Hex $f", first))
                }
            }
        }
        val programmerStore = ProgrammerStore()
        val engine = FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )

        // Layer 4 colour rows on every fixture: `resetToFallback` needs something below the
        // effect to reset *to*, and the extended components must be non-zero or
        // `setExtendedChannel` writes zeros and the blend is unmeasurable.
        engine.cueLayer.setAssignments(
            1,
            fixtures.fixtures.map { f ->
                CueAssignmentResolver.Assignment(
                    cueId = 1,
                    priority = 1,
                    fadeWeight = 1.0,
                    targetKey = f.key,
                    targetIsGroup = false,
                    propertyName = "rgbColour",
                    category = PropertyCategory.COLOUR,
                    compositionOverride = CompositionRule.UNSET,
                    value = CueAssignmentResolver.PropertyValue.Colour(
                        ExtendedColour(Color(30, 10, 70), white = 25u, amber = 15u, uv = 40u),
                    ),
                )
            },
        )

        // One per-fixture colour effect. Every entry carries non-zero W/A/UV so all three
        // bundled categories resolve on every tick rather than short-circuiting on zero.
        for (f in fixtures.fixtures) {
            engine.addEffect(
                FxInstance(
                    effect = SteppedColour(
                        colours = listOf(
                            ExtendedColour(Color.RED, white = 90u, amber = 40u, uv = 20u),
                            ExtendedColour(Color.GREEN, white = 30u, amber = 120u, uv = 60u),
                            ExtendedColour(Color.BLUE, white = 60u, amber = 10u, uv = 200u),
                        ),
                    ),
                    target = ColourTarget(f.key),
                    timing = FxTiming(beatDivision = BeatDivision.HALF),
                    blendMode = BlendMode.OVERRIDE,
                ),
            )
        }

        // Programmer coverage on half the rig — opens `composeProgrammerOver` for those, leaves
        // the gate closed for the rest.
        val covered = fixtures.fixtures.filterIndexed { i, _ -> i % 2 == 0 }
        for (f in covered) {
            programmerStore.put(
                ProgrammerOwner.WEB,
                f.key,
                "rgbColour",
                CueAssignmentResolver.PropertyValue.Colour(
                    ExtendedColour(Color(10, 200, 30), white = 70u, amber = 15u, uv = 45u),
                ),
            )
        }

        // Setup guards, in scenario 2's style: every one of these has a silent-no-op failure
        // mode that still prints believable microseconds.
        val sample = fixtures.fixtures.first() as HexFixture
        val bundled = sample.fixtureProperties.filter { it.bundleWithColour }.map { it.category }.toSet()
        check(bundled == setOf(PropertyCategory.WHITE, PropertyCategory.AMBER, PropertyCategory.UV)) {
            "the rig fixture must declare all three bundled colour channels, got $bundled"
        }
        check(engine.getActiveEffects().all { it.target is ColourTarget }) {
            "every effect must be a ColourTarget, or the bundled write path is not exercised"
        }
        check(1 in engine.cueLayer.activeCueIds()) { "Layer 4 colour rows did not land" }
        check(programmerStore.coversFixture(covered.first().key)) {
            "programmer gate never opens — composeProgrammerOver would be unmeasured"
        }
        check(!programmerStore.coversFixture(fixtures.fixtures[1].key)) {
            "programmer covers the whole rig — the gate's closed arm would be unmeasured"
        }
        check(!programmerStore.hasSidebandEntries) {
            "a sideband entry disables the per-fixture gate globally; this rig must not plant one"
        }

        // The decisive guard. Everything above is structural; this one proves the bundled write
        // actually reached the wire. A rig that fails `ColourTarget`'s `fixture is Fixture` gate
        // still ticks happily, still writes RGB, and still prints believable microseconds — it
        // just silently skips the reflection this scenario exists to measure.
        engine.processBeatTick(beatTick(0, System.currentTimeMillis()))
        val first = sample.firstChannel
        for ((name, channel) in listOf("amber" to first + 4, "white" to first + 5, "uv" to first + 6)) {
            check(controllers[0].getValue(channel) > 0u) {
                "bundled $name channel $channel never written — ColourTarget's bundled branch " +
                    "did not run, so this scenario would measure nothing"
            }
        }

        return ColourRig(engine, fixtures, covered.size)
    }

    @Test
    fun `colour write path with bundled extended channels`() {
        assumeBenchmarkEnabled()

        val rig = newColourRig()
        println(
            "[setup] universes=$UNIVERSES fixtures=${rig.fixtures.fixtures.size} " +
                "colourEffects=${rig.engine.getActiveEffects().size} " +
                "programmerCovered=${rig.programmerCovered}",
        )

        val warmupStart = System.currentTimeMillis()
        for (n in 0L until WARMUP_BEAT_TICKS.toLong()) rig.engine.processBeatTick(beatTick(n, warmupStart))

        val timings = LongArray(COLOUR_BEAT_TICKS)
        val start = System.currentTimeMillis()
        val allocBefore = allocatedBytes()
        for (n in 0L until COLOUR_BEAT_TICKS.toLong()) {
            val t = beatTick(n + WARMUP_BEAT_TICKS, start)
            timings[n.toInt()] = measureNanoTime { rig.engine.processBeatTick(t) }
        }
        val alloc = allocatedBytes().takeIf { it >= 0 && allocBefore >= 0 }
            ?.let { it - allocBefore } ?: -1L
        val stats = summarize("colour-beat", timings, alloc, sampleName = "tick")

        check(stats.p99Ns < 1_000_000_000L) { "colour-beat p99 tick > 1s: ${stats.p99Ns} ns" }
    }

    /**
     * A fresh engine over [fixtures], with no effects — one cue GO's starting point, since a
     * stack GO drops the outgoing cue's effects before spawning the incoming cue's.
     */
    private fun newSpawnEngine(fixtures: Fixtures): FxEngine {
        val programmerStore = ProgrammerStore()
        return FxEngine(
            fixtures = fixtures,
            speedMasters = SpeedMasterBank(),
            programmerStore = programmerStore,
            layerResolver = LayerResolver(CueAssignmentResolver(), programmerStore),
        )
    }

    private fun spawnInstances(fixtures: Fixtures): List<FxInstance> =
        fixtures.fixtures.take(SPAWN_EFFECTS).map { f ->
            FxInstance(
                effect = SineSlider(),
                target = SliderTarget(f.key, "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.MAX,
            )
        }

    /**
     * Sweep item C7: the cost of putting a cue's effects up.
     *
     * `[spawn-each]` is the pre-C7 shape — one `addEffect` per effect, each rebuilding the
     * sorted snapshots and re-broadcasting the whole active-effect list, so the Nth add walks
     * N entries. `[spawn-batch]` is the same effects through `addEffects`: one rebuild, one
     * broadcast. Both run against a fresh engine per sample so neither inherits the other's
     * population, and both run in the same JVM in the same sitting — the only comparison at
     * this resolution that means anything (see `docs/testing-engineering.md`).
     */
    @Test
    fun `cue spawn cost`() {
        assumeBenchmarkEnabled()

        val fixtures = newRigFixtures()
        println("[setup] universes=$UNIVERSES fixtures=${fixtures.fixtures.size} spawnEffects=$SPAWN_EFFECTS")

        fun measure(label: String, spawn: (FxEngine, List<FxInstance>) -> Unit) {
            repeat(WARMUP_SPAWNS) { spawn(newSpawnEngine(fixtures), spawnInstances(fixtures)) }

            val timings = LongArray(SPAWN_REPEATS)
            val allocBefore = allocatedBytes()
            for (i in 0 until SPAWN_REPEATS) {
                // Engine and instances are built outside the timed window: the measurement is
                // the add, not the cook that precedes it (that is C8's territory).
                val engine = newSpawnEngine(fixtures)
                val instances = spawnInstances(fixtures)
                timings[i] = measureNanoTime { spawn(engine, instances) }
            }
            val alloc = allocatedBytes().takeIf { it >= 0 && allocBefore >= 0 }
                ?.let { it - allocBefore } ?: -1L
            summarize(label, timings, alloc, sampleName = "spawn")
        }

        measure("spawn-each") { engine, instances -> instances.forEach { engine.addEffect(it) } }
        measure("spawn-batch") { engine, instances -> engine.addEffects(instances) }
    }

    // ─── Scenario 6: the cook ───────────────────────────────────────────────

    private data class CookRig(
        val fixtures: Fixtures,
        val layers: List<CookLayer>,
        val resolveLook: (UUID) -> LookSnapshot?,
        val resolveTemplate: (UUID) -> TemplateSnapshot?,
    )

    /**
     * A three-layer stack over one group of [HexFixture], shaped so every part of C8 is paid.
     *
     * The group is the point: a *bound* row names the group, so the cook expands it per row, and
     * the layer's own target set is a second group reference, so the allowed-set is rebuilt per row
     * too. The effect Look's effects are bound to the fixtures inside that group, which is the
     * `coversTarget` case — a fixture the layer covers only *through* a group, so the name-match
     * fast path misses and the expansion runs. The template layer's rows are deferred, so each fans
     * over every head and pays [TemplateResolver] per head.
     *
     * No `State`, no engine, no database: [CueComposer] cooks values from plain snapshots (see its
     * `resolveLook` parameter), which is what lets this scenario be a straight function call.
     */
    private fun newCookRig(): CookRig {
        val controllers = (0 until UNIVERSES).map { MockDmxController(Universe(0, it)) }
        val fixtures = Fixtures()
        fixtures.register {
            controllers.forEach { addController(it) }
            val heads = mutableListOf<HexFixture>()
            for (u in 0 until UNIVERSES) {
                val universe = controllers[u].universe
                for (f in 0 until FIXTURES_PER_UNIVERSE) {
                    val first = 1 + f * HEX_CHANNELS
                    if (first + HEX_CHANNELS - 1 > 512) break
                    heads += addFixture(HexFixture(universe, "u${u}-hex-${f}", "U$u Hex $f", first))
                }
            }
            createGroup<HexFixture>("everything") { addSpread(heads) }
        }
        val heads = fixtures.fixtures.map { it.key }

        // Bound rows, all on the group, alternating dimmer and colour so the accumulator holds two
        // keys per head rather than overwriting one.
        val valueLook = LookSnapshot(
            lookId = 1,
            lookUuid = UUID.nameUUIDFromBytes("cook-values".toByteArray()),
            name = "Cook Values",
            rows = (0 until COOK_LOOK_ROWS).map { i ->
                LookRowEntry(
                    target = TargetRef.Group("everything"),
                    propertyName = if (i % 2 == 0) "dimmer" else "rgbColour",
                    value = if (i % 2 == 0) "${100 + i}" else "#20${"%02x".format(i * 5)}70",
                )
            },
            effects = emptyList(),
        )

        // Effects bound to individual heads. The layer names the *group*, so each of these reaches
        // `coversTarget`'s expansion arm rather than its name-match fast path.
        val effectLook = LookSnapshot(
            lookId = 2,
            lookUuid = UUID.nameUUIDFromBytes("cook-effects".toByteArray()),
            name = "Cook Effects",
            rows = emptyList(),
            effects = (0 until COOK_LOOK_EFFECTS).map { i ->
                LookEffectEntry(
                    target = TargetRef.Fixture(heads[i % heads.size]),
                    effectType = "SineWave",
                    category = "dimmer",
                    propertyName = "dimmer",
                    beatDivision = BeatDivision.HALF,
                    blendMode = "OVERRIDE",
                    distribution = "LINEAR",
                    phaseOffset = 0.0,
                    elementMode = null,
                    elementFilter = null,
                    stepTiming = null,
                    parameters = mapOf("min" to "40", "max" to "220"),
                    speedMasterUuid = null,
                    rateSpeedMasterUuid = null,
                )
            },
        )

        // Deferred template rows: each fans over every head in the layer's group.
        val template = TemplateSnapshot(
            templateId = 3,
            templateUuid = UUID.nameUUIDFromBytes("cook-template".toByteArray()),
            name = "Cook Template",
            fadeDurationMs = null,
            rows = listOf(
                TemplateRowEntry(target = null, propertyName = "rgbColour", value = "#FF9D4A;policy=extract"),
                TemplateRowEntry(target = null, propertyName = "dimmer", value = "pct:70"),
            ),
        )

        val onEverything = listOf(CueTargetDto("group", "everything"))
        val layers = listOf(
            CookLayer(
                source = LayerSource.look(valueLook.lookId, valueLook.lookUuid, valueLook.name),
                sortOrder = 0, targets = onEverything, layerId = 1,
            ),
            CookLayer(
                source = LayerSource.template(template.templateId, template.templateUuid, template.name),
                sortOrder = 1, targets = onEverything, layerId = 2,
            ),
            CookLayer(
                source = LayerSource.look(effectLook.lookId, effectLook.lookUuid, effectLook.name),
                sortOrder = 2, targets = onEverything, layerId = 3,
            ),
        )

        val looks = listOf(valueLook, effectLook).associateBy { it.lookUuid }
        val templates = mapOf(template.templateUuid to template)
        return CookRig(fixtures, layers, { looks[it] }, { templates[it] })
    }

    /**
     * Sweep item C8: the cost of cooking a layer stack.
     *
     * `[cook-split]` is the pre-C8 shape — [CueComposer.cook] for the rows, then
     * [CueComposer.cookEffects] for the effects, then [CueComposer.contributingLayers] a third time
     * to rank them. `[cook-once]` is [CueComposer.cookAll]: one pass, one sort, each Look snapshot
     * read once, the layer's targets expanded once for both halves.
     *
     * **`[cook-split]` is a historical shape, not a live one** — after C8 the programmer recook,
     * `CueStackManager.activateCueInStack` and the cue-apply route helper all take `cookAll`. It is
     * kept because both arms run the same per-layer code, so the hoists inside it move *both*
     * numbers: without the split arm the scenario could not tell "the cook got cheaper" from "the
     * consolidation helped", and it would need a historical block to say anything at all.
     *
     * The cook is not on the 50 Hz path: it runs once per stack mutation, once per cue GO and once
     * per Look edit that tours. So the number to read is not µs-per-tick but how much of an
     * operator's gesture it is — a recook is in the same budget as the publish that follows it.
     */
    @Test
    fun `layer stack cook cost`() {
        assumeBenchmarkEnabled()

        val rig = newCookRig()
        println(
            "[setup] universes=$UNIVERSES fixtures=${rig.fixtures.fixtures.size} " +
                "layers=${rig.layers.size} lookRows=$COOK_LOOK_ROWS lookEffects=$COOK_LOOK_EFFECTS",
        )

        fun measure(label: String, cook: (CookRig) -> Unit) {
            repeat(WARMUP_COOKS) { cook(rig) }

            val timings = LongArray(COOK_REPEATS)
            val allocBefore = allocatedBytes()
            for (i in 0 until COOK_REPEATS) {
                timings[i] = measureNanoTime { cook(rig) }
            }
            val alloc = allocatedBytes().takeIf { it >= 0 && allocBefore >= 0 }
                ?.let { it - allocBefore } ?: -1L
            summarize(label, timings, alloc, sampleName = "cook")
        }

        measure("cook-split") { r ->
            CueComposer.cook(
                fixtures = r.fixtures, cueId = 1, priority = 1, layers = r.layers,
                localRows = emptyList(), resolveLook = r.resolveLook, resolveTemplate = r.resolveTemplate,
            )
            CueComposer.cookEffects(r.fixtures, 1, r.layers, r.resolveLook)
            CueComposer.contributingLayers(r.layers)
                .withIndex()
                .associate { (index, layer) -> layer.layerId to index }
        }
        measure("cook-once") { r ->
            CueComposer.cookAll(
                fixtures = r.fixtures, cueId = 1, priority = 1, layers = r.layers,
                localRows = emptyList(), resolveLook = r.resolveLook, resolveTemplate = r.resolveTemplate,
            )
        }
    }
}
