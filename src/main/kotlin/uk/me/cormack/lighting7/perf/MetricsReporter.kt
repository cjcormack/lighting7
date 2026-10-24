package uk.me.cormack.lighting7.perf

import com.sun.management.GarbageCollectionNotificationInfo
import io.sentry.Sentry
import io.sentry.metrics.MetricsUnit
import io.sentry.metrics.SentryMetricsParameters
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.telemetry.ScriptCacheProbe
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import javax.management.Notification
import javax.management.NotificationEmitter
import javax.management.NotificationListener
import javax.management.openmbean.CompositeData

// Named into the telemetry package, whose loggers never reach Sentry: a failing flush must not report itself.
private val logger = LoggerFactory.getLogger("uk.me.cormack.lighting7.telemetry.MetricsReporter")

/** One value [MetricsReporter] sends. */
data class Metric(
    val kind: Kind,
    val name: String,
    val value: Double,
    val unit: String? = null,
    val attributes: Map<String, String> = emptyMap(),
) {
    enum class Kind { COUNT, GAUGE, DISTRIBUTION }
}

/** Where a flush goes: Sentry, or a test's list. */
interface MetricSink {
    val enabled: Boolean

    fun send(metric: Metric)
}

object SentryMetricSink : MetricSink {
    override val enabled: Boolean get() = Sentry.isEnabled()

    override fun send(metric: Metric) {
        val params = SentryMetricsParameters.create(metric.attributes)
        val metrics = Sentry.metrics()
        when (metric.kind) {
            Metric.Kind.COUNT -> metrics.count(metric.name, metric.value, metric.unit, params)
            Metric.Kind.GAUGE -> metrics.gauge(metric.name, metric.value, metric.unit, params)
            Metric.Kind.DISTRIBUTION -> metrics.distribution(metric.name, metric.value, metric.unit, params)
        }
    }
}

/**
 * Sends [DeskMetrics] to Sentry every 30 s (Sentry plan D7, §3.4), started and stopped with
 * telemetry by `SentryRuntime`. Each flush reads what was recorded since the last and resets it: a
 * count is the window's, a histogram is its p50, p95 and max under a `stat` attribute, a rare event
 * is each of its values, and a gauge is read as the flush runs. Nothing is sent from a window that
 * recorded nothing, and nothing at all while [MetricSink.enabled] is false.
 *
 * The flush runs on its own thread and only enqueues: the SDK batches and sends on its own executor.
 */
class MetricsReporter(
    private val metrics: DeskMetrics = DeskMetrics.global,
    private val midi: () -> MidiLatencyTracker? = { null },
    private val activeFx: () -> Int? = { null },
    private val sink: MetricSink = SentryMetricSink,
    private val intervalMs: Long = INTERVAL_MS,
) {
    private val lifecycle = Any()
    private val flushLock = Any()
    private var executor: ScheduledExecutorService? = null
    private var gcListener: GcPauseListener? = null
    private val windows = HashMap<LatencyHistogram, HistogramWindow>()

    fun start() {
        synchronized(lifecycle) {
            if (executor != null) return
            // What was recorded while telemetry was off is not this window's.
            synchronized(flushLock) { collect() }
            try {
                gcListener = GcPauseListener(metrics).also { it.register() }
                executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "metrics-reporter").apply { isDaemon = true } }
                    .also { it.scheduleAtFixedRate(::flushGuarded, intervalMs, intervalMs, TimeUnit.MILLISECONDS) }
            } catch (e: Throwable) {
                gcListener?.unregister()
                gcListener = null
                throw e
            }
        }
    }

    /** Stops flushing, and waits up to a second for a flush in progress, so none lands after Sentry closes. */
    fun stop() {
        val stopping = synchronized(lifecycle) {
            gcListener?.unregister()
            gcListener = null
            executor.also { executor = null }
        } ?: return
        stopping.shutdownNow()
        runCatching { stopping.awaitTermination(1, TimeUnit.SECONDS) }
    }

    /** One window: read and reset everything, and send it if the sink is enabled. */
    fun flush() {
        synchronized(flushLock) {
            val window = collect()
            if (sink.enabled) window.forEach(sink::send)
        }
    }

    private fun flushGuarded() {
        try {
            flush()
        } catch (e: Throwable) {
            logger.warn("Metrics flush failed: {}", e.toString())
        }
    }

    internal fun collect(): List<Metric> = buildList {
        for (frames in metrics.artNetUniverses) {
            val universe = mapOf("universe" to "${frames.universe.subnet}:${frames.universe.universe}")
            summary("artnet.frame.lateness", frames.lateness, universe)
            count("artnet.frame.overrun", frames.overruns.getAndSet(0), universe)
        }
        for (loop in DeskMetrics.FxLoop.entries) summary("fx.tick.duration", metrics.fxTick(loop), mapOf("loop" to loop.tag))
        activeFx()?.let { add(Metric(Metric.Kind.GAUGE, "fx.active_effects", it.toDouble())) }
        add(Metric(Metric.Kind.GAUGE, "ws.clients", metrics.wsClients.get().toDouble()))
        for ((key, n) in metrics.drainWsDropped()) {
            count("ws.outbound.dropped", n, mapOf("type" to key.first, "reason" to key.second.tag))
        }
        midi()?.let { tracker ->
            for (stage in MidiLatencyStage.entries) summary("midi.latency", tracker.bucket(stage), mapOf("stage" to stage.wireName))
        }
        summary("db.connection.wait", metrics.dbConnectionWait)
        for (ms in metrics.cueGo.drain()) add(Metric(Metric.Kind.DISTRIBUTION, "cue.go.duration", ms, MetricsUnit.Duration.MILLISECOND))
        for (cache in DeskMetrics.BootCache.entries) {
            for (s in metrics.boot(cache).drain()) {
                add(Metric(Metric.Kind.DISTRIBUTION, "boot.duration", s, MetricsUnit.Duration.SECOND, mapOf("cache" to cache.tag)))
            }
        }
        for ((source, n) in metrics.drainScriptCache()) {
            val result = if (source == ScriptCacheProbe.Source.COMPILED) "miss" else "hit"
            count("script.cache", n, mapOf("result" to result, "cache" to ScriptCacheProbe.tag(source)))
        }
        for ((key, n) in metrics.drainSyncRuns()) {
            count("sync.run", n, mapOf("outcome" to key.first.lowercase(), "trigger" to key.second.name.lowercase()))
        }
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        add(Metric(Metric.Kind.GAUGE, "jvm.heap.used", heap.toDouble(), MetricsUnit.Information.BYTE))
        for (kind in DeskMetrics.GcKind.entries) summary("jvm.gc.pause", metrics.gcPause(kind), mapOf("gc" to kind.tag))
    }

    private fun MutableList<Metric>.count(name: String, n: Long, attributes: Map<String, String>) {
        if (n > 0) add(Metric(Metric.Kind.COUNT, name, n.toDouble(), attributes = attributes))
    }

    private fun MutableList<Metric>.summary(name: String, histogram: LatencyHistogram, attributes: Map<String, String> = emptyMap()) {
        val window = windows.getOrPut(histogram) { HistogramWindow(histogram) }.take() ?: return
        for ((stat, nanos) in listOf("p50" to window.p50Nanos, "p95" to window.p95Nanos, "max" to window.maxNanos)) {
            add(Metric(Metric.Kind.DISTRIBUTION, name, nanos / 1_000_000.0, MetricsUnit.Duration.MILLISECOND, attributes + ("stat" to stat)))
        }
    }

    companion object {
        const val INTERVAL_MS = 30_000L
    }
}

/**
 * Each stop-the-world GC pause into [DeskMetrics.gcPause]. A ZGC cycle (`end of GC cycle`) is
 * concurrent work, not a pause, and is left out. Needs `jdk.management`, which `jlinkModules` ships.
 */
internal class GcPauseListener(private val metrics: DeskMetrics) : NotificationListener {
    private val emitters = ManagementFactory.getGarbageCollectorMXBeans().filterIsInstance<NotificationEmitter>()

    fun register() = emitters.forEach { it.addNotificationListener(this, null, null) }

    fun unregister() = emitters.forEach { runCatching { it.removeNotificationListener(this) } }

    override fun handleNotification(notification: Notification, handback: Any?) {
        if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return
        val info = GarbageCollectionNotificationInfo.from(notification.userData as? CompositeData ?: return)
        if (info.gcAction == "end of GC cycle") return
        val minor = "minor" in info.gcAction || "Minor" in info.gcName || "Young" in info.gcName
        val kind = if (minor) DeskMetrics.GcKind.MINOR else DeskMetrics.GcKind.MAJOR
        metrics.gcPause(kind).record(info.gcInfo.duration * 1_000_000L)
    }
}
