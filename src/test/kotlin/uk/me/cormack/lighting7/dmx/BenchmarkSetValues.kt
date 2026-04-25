package uk.me.cormack.lighting7.dmx

import kotlinx.coroutines.runBlocking
import org.junit.Assume
import uk.me.cormack.lighting7.bench.allocatedBytes
import uk.me.cormack.lighting7.bench.summarize
import kotlin.system.measureNanoTime
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * Blocking-vs-suspend `setValues` micro-benchmark for the DMX path.
 *
 * Drives a synthetic 4-universe rig of [AsyncTestDmxController]s — a coroutine-aware
 * test fake that mimics [ArtNetController]'s per-channel conflated consumer +
 * ack-roundtrip pattern without the UDP transport. Each iteration commits 128 writes
 * per universe (512 total) through a [ControllerTransaction], measured separately
 * via the blocking `apply()` and the suspend `applySuspend()` paths.
 *
 * Banked infrastructure: there is no concrete perf-sensitive DMX/FX change in flight
 * driving this. The harness exists so the next refactor of `setValues` / fan-out can
 * be calibrated against a real coroutine-aware controller — [MockDmxController] runs
 * `setValuesSuspend` synchronously and would silently flatter both paths.
 *
 * **Skipped by default.** Invoke with:
 *
 * ```
 * ./gradlew test --tests "uk.me.cormack.lighting7.dmx.BenchmarkSetValues" \
 *     -Ddmx.benchmark=true
 * ```
 *
 * Track-only: prints `[blocking]` / `[suspend]` summary lines to stdout. A
 * fail-on-regression gate is deliberately deferred to `FU-TEST-FX-BENCH-CI-GATE`
 * pending a variance study on real CI hardware.
 */
class BenchmarkSetValues {

    private companion object {
        const val BENCHMARK_FLAG = "dmx.benchmark"
        const val UNIVERSES = 4
        const val WRITES_PER_UNIVERSE = 128
        const val ITERATIONS = 2_400
        const val WARMUP = 200
        /** Channel range used per universe — 1..128 keeps writes inside the controller's 1..512 valid range. */
        const val FIRST_CHANNEL = 1
    }

    private data class Rig(
        val controllers: List<AsyncTestDmxController>,
    ) {
        fun close() = controllers.forEach { it.close() }
    }

    private fun newRig(): Rig {
        val controllers = (0 until UNIVERSES).map { AsyncTestDmxController(Universe(0, it)) }
        return Rig(controllers)
    }

    private var rig: Rig? = null

    @AfterTest
    fun tearDown() {
        rig?.close()
        rig = null
    }

    private fun stagePayload(tx: ControllerTransaction, universes: List<Universe>, iter: Int) {
        // Cycle the value mod 256 so the per-channel CONFLATED queue can't elide successive
        // writes — that would silently flatter the suspend path by skipping ack roundtrips.
        val v = (iter % 256).toUByte()
        for (universe in universes) {
            for (i in 0 until WRITES_PER_UNIVERSE) {
                tx.setValue(universe, FIRST_CHANNEL + i, ChannelChange(v, fadeMs = 0L))
            }
        }
    }

    @Test
    fun `setValues blocking vs suspend`() {
        Assume.assumeTrue(
            "Set -D$BENCHMARK_FLAG=true to run the DMX setValues benchmark",
            System.getProperty(BENCHMARK_FLAG) == "true",
        )

        val r = newRig().also { rig = it }
        val controllers = r.controllers
        val universes = controllers.map { it.universe }
        println(
            "[setup] universes=$UNIVERSES writesPerUniverse=$WRITES_PER_UNIVERSE " +
                "totalPerIter=${UNIVERSES * WRITES_PER_UNIVERSE} iterations=$ITERATIONS",
        )

        // Warm up: prime JIT, classloader, channel-coroutine scheduling.
        repeat(WARMUP) { i ->
            val tx = ControllerTransaction(controllers)
            stagePayload(tx, universes, i)
            tx.apply()
        }
        runBlocking {
            repeat(WARMUP) { i ->
                val tx = ControllerTransaction(controllers)
                stagePayload(tx, universes, i)
                tx.applySuspend()
            }
        }

        // Blocking-path measurement.
        val blockingTimings = LongArray(ITERATIONS)
        val blockingAllocBefore = allocatedBytes()
        for (i in 0 until ITERATIONS) {
            val tx = ControllerTransaction(controllers)
            stagePayload(tx, universes, i + WARMUP)
            blockingTimings[i] = measureNanoTime { tx.apply() }
        }
        val blockingAlloc = allocatedBytes()
            .takeIf { it >= 0 && blockingAllocBefore >= 0 }
            ?.let { it - blockingAllocBefore } ?: -1L
        val blockingStats = summarize("blocking", blockingTimings, blockingAlloc)

        // Suspend-path measurement. Single `runBlocking` boundary so we time the suspend
        // commit cost, not repeated `runBlocking` setup/teardown — that's the production
        // hot-path shape (FxEngine collect loops are already in a coroutine context).
        // Use raw `System.nanoTime()` because `measureNanoTime` doesn't accept a suspend block.
        val suspendTimings = LongArray(ITERATIONS)
        val suspendAllocBefore = allocatedBytes()
        runBlocking {
            for (i in 0 until ITERATIONS) {
                val tx = ControllerTransaction(controllers)
                stagePayload(tx, universes, i + WARMUP + ITERATIONS)
                val start = System.nanoTime()
                tx.applySuspend()
                suspendTimings[i] = System.nanoTime() - start
            }
        }
        val suspendAlloc = allocatedBytes()
            .takeIf { it >= 0 && suspendAllocBefore >= 0 }
            ?.let { it - suspendAllocBefore } ?: -1L
        val suspendStats = summarize("suspend", suspendTimings, suspendAlloc)

        // Track-only floor assertions — guard against catastrophic regression (a commit
        // per iter taking a full second). The ±20% regression gate is tracked under
        // FU-TEST-FX-BENCH-CI-GATE pending a variance study.
        check(blockingStats.p99Ns < 1_000_000_000L) { "blocking p99 > 1s: ${blockingStats.p99Ns} ns" }
        check(suspendStats.p99Ns < 1_000_000_000L) { "suspend p99 > 1s: ${suspendStats.p99Ns} ns" }
    }
}
