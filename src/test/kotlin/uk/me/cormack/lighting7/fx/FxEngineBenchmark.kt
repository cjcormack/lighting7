package uk.me.cormack.lighting7.fx

import com.sun.management.ThreadMXBean
import org.junit.Assume
import uk.me.cormack.lighting7.dmx.MockDmxController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.fixture.dmx.HexFixture
import uk.me.cormack.lighting7.fx.effects.SineWave
import uk.me.cormack.lighting7.fx.effects.StaticValue
import uk.me.cormack.lighting7.show.Fixtures
import java.lang.management.ManagementFactory
import kotlin.system.measureNanoTime
import kotlin.test.Test

/**
 * Per-tick allocation & latency benchmark for [FxEngine]'s hot path.
 *
 * Exercises a synthetic rig of 4 universes × 64 HexFixtures each (256 fixtures, ≈3 000 DMX
 * channels), with both BEAT and WALL_CLOCK effects running, plus a handful of cue-level Layer 3
 * assignments. Pumps ticks through [FxEngine.processBeatTick] / [FxEngine.processWallClockTick]
 * and reports allocation bytes/tick and p50/p99 tick duration.
 *
 * **Skipped by default.** Takes ~10s of wall time and isn't useful on every CI run. Invoke with:
 *
 * ```
 * ./gradlew test --tests "uk.me.cormack.lighting7.fx.FxEngineBenchmark" \
 *     -Dfx.benchmark=true
 * ```
 *
 * Phase 5 ships this as a track-only harness: the output is printed to stdout for humans to
 * read. Turning it into a fail-on-regression gate (±20% against a committed baseline) is a
 * follow-up once we have a week or two of baseline numbers to judge variance from.
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
        /** Effects per fixture: one beat-synced dimmer + a wall-clock dimmer on the same target. */
        const val EFFECTS_PER_FIXTURE = 2
    }

    private data class Rig(
        val controllers: List<MockDmxController>,
        val engine: FxEngine,
        val fixtures: Fixtures,
    )

    private fun newRig(): Rig {
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
        val directWriteStore = DirectWriteStore()
        val engine = FxEngine(
            fixtures = fixtures,
            masterClock = MasterClock(),
            directWriteStore = directWriteStore,
            layerResolver = LayerResolver(Layer3Resolver(), directWriteStore),
        )

        // Populate half the fixtures with cue-level Layer 3 dimmer assignments so
        // `resetActiveProperties` actually has Layer 3 work to do during effect reset.
        val assignments = fixtures.fixtures.filterIndexed { i, _ -> i % 2 == 0 }.map { f ->
            Layer3Resolver.Assignment(
                cueId = 1,
                priority = 1,
                fadeWeight = 1.0,
                targetKey = f.key,
                targetIsGroup = false,
                propertyName = "dimmer",
                category = PropertyCategory.DIMMER,
                compositionOverride = CompositionRule.UNSET,
                value = Layer3Resolver.PropertyValue.Slider(128u),
            )
        }
        engine.setCueAssignments(1, assignments)

        // Two effects per fixture: one beat-synced SineWave, one wall-clock StaticValue.
        val allFixtures = fixtures.fixtures
        for (f in allFixtures) {
            val beatFx = FxInstance(
                effect = SineWave(),
                target = SliderTarget(f.key, "dimmer"),
                timing = FxTiming(beatDivision = BeatDivision.QUARTER),
                blendMode = BlendMode.MAX,
            )
            engine.addEffect(beatFx)

            val wallFx = FxInstance(
                effect = StaticValue(value = 80u),
                target = SliderTarget(f.key, "uv"),
                timing = FxTiming(beatDivision = 1.0),
                blendMode = BlendMode.OVERRIDE,
            ).apply { timingSource = TimingSource.WALL_CLOCK }
            engine.addEffect(wallFx)
        }
        return Rig(controllers, engine, fixtures)
    }

    private fun beatTick(n: Long, startMs: Long): MasterClock.ClockTick {
        val tpb = MasterClock.TICKS_PER_BEAT.toLong()
        val tickInBeat = (n % tpb).toInt()
        return MasterClock.ClockTick(
            tickNumber = n,
            beatNumber = n / tpb,
            tickInBeat = tickInBeat,
            phase = tickInBeat.toDouble() / MasterClock.TICKS_PER_BEAT,
            timestampMs = startMs + (n * (60_000 / (120 * tpb))),
        )
    }

    private fun allocatedBytes(): Long {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean ?: return -1L
        if (!bean.isThreadAllocatedMemorySupported) return -1L
        if (!bean.isThreadAllocatedMemoryEnabled) bean.isThreadAllocatedMemoryEnabled = true
        @Suppress("DEPRECATION")
        val tid = Thread.currentThread().id
        return bean.getThreadAllocatedBytes(tid)
    }

    private data class Stats(val p50Ns: Long, val p99Ns: Long, val meanNs: Long, val bytesPerTick: Long, val tickCount: Int)

    private fun summarize(label: String, timings: LongArray, allocBytes: Long): Stats {
        val sorted = timings.copyOf().also { it.sort() }
        val p50 = sorted[sorted.size / 2]
        val p99 = sorted[((sorted.size * 99) / 100).coerceAtMost(sorted.size - 1)]
        val mean = sorted.sum() / sorted.size
        val perTick = if (allocBytes >= 0) allocBytes / sorted.size else -1L
        println(
            "[$label] ticks=${sorted.size} p50=${p50 / 1_000}µs p99=${p99 / 1_000}µs " +
                "mean=${mean / 1_000}µs allocBytes/tick=$perTick",
        )
        return Stats(p50, p99, mean, perTick, sorted.size)
    }

    @Test
    fun `beat and wall-clock tick throughput`() {
        Assume.assumeTrue(
            "Set -D$BENCHMARK_FLAG=true to run the FxEngine benchmark",
            System.getProperty(BENCHMARK_FLAG) == "true",
        )

        val rig = newRig()
        println(
            "[setup] universes=$UNIVERSES fixtures=${rig.fixtures.fixtures.size} " +
                "effects=${rig.engine.getActiveEffects().size}",
        )

        // Warm up: JIT & load class paths so the measured window isn't paying for first-time
        // compilation of the hot loops.
        val warmupStart = System.currentTimeMillis()
        for (n in 0L until 200L) rig.engine.processBeatTick(beatTick(n, warmupStart))
        for (n in 0L until 20L) rig.engine.processWallClockTick()

        // Beat-tick measurement.
        val beatTimings = LongArray(BEAT_TICKS)
        val beatStart = System.currentTimeMillis()
        val beatAllocBefore = allocatedBytes()
        for (n in 0L until BEAT_TICKS.toLong()) {
            val t = beatTick(n + 200L, beatStart)
            beatTimings[n.toInt()] = measureNanoTime { rig.engine.processBeatTick(t) }
        }
        val beatAlloc = allocatedBytes().takeIf { it >= 0 && beatAllocBefore >= 0 }
            ?.let { it - beatAllocBefore } ?: -1L
        val beatStats = summarize("beat", beatTimings, beatAlloc)

        // Wall-clock measurement.
        val wallTimings = LongArray(WALL_CLOCK_TICKS)
        val wallAllocBefore = allocatedBytes()
        for (n in 0 until WALL_CLOCK_TICKS) {
            wallTimings[n] = measureNanoTime { rig.engine.processWallClockTick() }
        }
        val wallAlloc = allocatedBytes().takeIf { it >= 0 && wallAllocBefore >= 0 }
            ?.let { it - wallAllocBefore } ?: -1L
        val wallStats = summarize("wall", wallTimings, wallAlloc)

        // Track-only: no hard assertion. A future pass turns these prints into a committed
        // baseline + ±20% regression gate. For now, a trivial floor assertion catches obvious
        // breakage (e.g. a tick that somehow takes a full second to run).
        check(beatStats.p99Ns < 1_000_000_000L) { "beat p99 tick > 1s: ${beatStats.p99Ns} ns" }
        check(wallStats.p99Ns < 1_000_000_000L) { "wall p99 tick > 1s: ${wallStats.p99Ns} ns" }
    }
}
