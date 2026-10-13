package uk.me.cormack.lighting7.perf

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.sync.SyncTrigger
import uk.me.cormack.lighting7.telemetry.ScriptCacheProbe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The desk's metric recorders (Sentry plan §3.4), written where each thing happens and read only by
 * [MetricsReporter]. A recording is an atomic increment into a structure allocated up front, so the
 * FX tick and ArtNet's send loop can make one per pass: no allocation, no lock, no Sentry. Every
 * attribute comes from the enums and universe numbers here, never a fixture key or uuid.
 */
class DeskMetrics {
    /** One universe's Art-Net frames, taken at controller construction rather than per frame. */
    class ArtNetFrames internal constructor(val universe: Universe) {
        /** How late each frame went out against its deadline. */
        val lateness = LatencyHistogram()
        internal val overruns = AtomicLong()

        /** The loop fell more than a whole interval behind and re-based its deadline. */
        fun overrun() {
            overruns.incrementAndGet()
        }
    }

    private val artNet = ConcurrentHashMap<Universe, ArtNetFrames>()

    fun artNet(universe: Universe): ArtNetFrames = artNet.computeIfAbsent(universe, ::ArtNetFrames)

    internal val artNetUniverses: Collection<ArtNetFrames> get() = artNet.values

    enum class FxLoop(val tag: String) { BEAT("beat"), WALL_CLOCK("wall_clock") }

    private val fxTicks = Array(FxLoop.entries.size) { LatencyHistogram() }

    fun fxTick(loop: FxLoop): LatencyHistogram = fxTicks[loop.ordinal]

    /** The wait for the pool's one connection, from Hikari ([DbConnectionMetrics]). */
    val dbConnectionWait = LatencyHistogram()

    enum class GcKind(val tag: String) { MINOR("minor"), MAJOR("major") }

    private val gcPauses = Array(GcKind.entries.size) { LatencyHistogram() }

    fun gcPause(kind: GcKind): LatencyHistogram = gcPauses[kind.ordinal]

    /** Sockets past the warm-up gate. */
    val wsClients = AtomicInteger()

    enum class WsDrop(val tag: String) {
        /** Skipped by a DROP_OLDEST flow while the socket was still sending an earlier frame. */
        CONFLATED("conflated"),

        /** Lost to a send that raced the connection closing. */
        CLOSED("closed"),
    }

    private val wsDropped = ConcurrentHashMap<Pair<String, WsDrop>, AtomicLong>()

    /** [type] is an outbound frame's `@SerialName`: a closed set of compiled message types. */
    fun wsDropped(type: String, reason: WsDrop, frames: Long = 1) {
        if (frames > 0) wsDropped.computeIfAbsent(type to reason) { AtomicLong() }.addAndGet(frames)
    }

    internal fun drainWsDropped(): Map<Pair<String, WsDrop>, Long> = drain(wsDropped)

    private val scriptCache = AtomicLongArray(ScriptCacheProbe.Source.entries.size)
    private val scriptsCompiled = AtomicLong()

    fun scriptCache(source: ScriptCacheProbe.Source) {
        scriptCache.incrementAndGet(source.ordinal)
        if (source == ScriptCacheProbe.Source.COMPILED) scriptsCompiled.incrementAndGet()
    }

    /** Every compile the Kotlin compiler ran since the process started; never reset. */
    val compiledTotal: Long get() = scriptsCompiled.get()

    internal fun drainScriptCache(): Map<ScriptCacheProbe.Source, Long> =
        ScriptCacheProbe.Source.entries.associateWith { scriptCache.getAndSet(it.ordinal, 0) }.filterValues { it > 0 }

    private val syncRuns = ConcurrentHashMap<Pair<String, SyncTrigger>, AtomicLong>()

    /** A finished sync run; null [outcome] is one that failed. */
    fun syncRun(outcome: SyncOutcome?, trigger: SyncTrigger) {
        syncRuns.computeIfAbsent((outcome?.name ?: FAILED) to trigger) { AtomicLong() }.incrementAndGet()
    }

    internal fun drainSyncRuns(): Map<Pair<String, SyncTrigger>, Long> = drain(syncRuns)

    /** Each GO's duration, from the call to the cue being live. */
    val cueGo = EventSamples()

    enum class BootCache(val tag: String) {
        /** Every script came from a cache. */
        WARM("warm"),

        /** The Kotlin compiler ran at least once. */
        COLD("cold"),
    }

    private val boots = Array(BootCache.entries.size) { EventSamples(capacity = 4) }

    fun boot(cache: BootCache): EventSamples = boots[cache.ordinal]

    private fun <K> drain(counters: ConcurrentHashMap<K, AtomicLong>): Map<K, Long> =
        counters.entries.associate { (key, count) -> key to count.getAndSet(0) }.filterValues { it > 0 }

    companion object {
        /** The process's recorders, which every hook writes to. */
        val global = DeskMetrics()

        private const val FAILED = "FAILED"
    }
}

/**
 * Values of a rare event, each one sent as it was rather than summarised. At most [capacity] are kept
 * between flushes, so a storm of them costs a bounded number of samples.
 */
class EventSamples(private val capacity: Int = 50) {
    private val values = ConcurrentLinkedQueue<Double>()
    private val held = AtomicInteger()

    fun record(value: Double) {
        if (held.incrementAndGet() <= capacity) values.add(value) else held.decrementAndGet()
    }

    internal fun drain(): List<Double> = buildList {
        while (true) {
            add(values.poll() ?: break)
            held.decrementAndGet()
        }
    }
}
