package uk.me.cormack.lighting7.perf

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import uk.me.cormack.lighting7.bench.allocatedBytes
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.plugins.ProvenanceStateOutMessage
import uk.me.cormack.lighting7.plugins.wireType
import uk.me.cormack.lighting7.sync.SyncOutcome
import uk.me.cormack.lighting7.sync.SyncTrigger
import uk.me.cormack.lighting7.telemetry.ScriptCacheProbe
import uk.me.cormack.lighting7.testsupport.withTestSentry
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetricsReporterTest {
    private class CapturingSink(override var enabled: Boolean = true) : MetricSink {
        val sent = CopyOnWriteArrayList<Metric>()

        override fun send(metric: Metric) {
            sent += metric
        }
    }

    /** Something in every recorder, as the desk would. */
    private fun exercise(metrics: DeskMetrics) {
        val frames = metrics.artNet(Universe(0, 1))
        repeat(40) { frames.lateness.record(it * 50_000L) }
        frames.overrun()
        DeskMetrics.FxLoop.entries.forEach { metrics.fxTick(it).record(2_000_000) }
        metrics.wsClients.set(2)
        metrics.wsDropped("provenanceState", DeskMetrics.WsDrop.CONFLATED, 3)
        metrics.dbConnectionWait.record(5_000_000)
        metrics.gcPause(DeskMetrics.GcKind.MINOR).record(1_000_000)
        metrics.cueGo.record(12.5)
        metrics.boot(DeskMetrics.BootCache.WARM).record(4.2)
        metrics.scriptCache(ScriptCacheProbe.Source.DISK)
        metrics.scriptCache(ScriptCacheProbe.Source.COMPILED)
        metrics.syncRun(SyncOutcome.NO_OP, SyncTrigger.AUTO)
        metrics.syncRun(null, SyncTrigger.MANUAL)
    }

    private fun midiWith(nanos: Long) = MidiLatencyTracker().apply { record(MidiLatencyStage.INGRESS_BUTTON, nanos) }

    @Test
    fun `nothing is sent while the sink is disabled, and what was recorded then is not sent later`() {
        val metrics = DeskMetrics()
        val sink = CapturingSink(enabled = false)
        val reporter = MetricsReporter(metrics, activeFx = { 3 }, sink = sink)
        exercise(metrics)
        reporter.flush()
        assertTrue(sink.sent.isEmpty())

        sink.enabled = true
        reporter.flush()
        assertTrue(sink.sent.none { it.kind != Metric.Kind.GAUGE }, "the disabled window was read and dropped: ${sink.sent}")
    }

    @Test
    fun `Sentry's own sink is off until Sentry is started`() {
        assertEquals(false, SentryMetricSink.enabled)
    }

    @Test
    fun `a flush reads and resets, so two windows never count the same thing twice`() {
        val metrics = DeskMetrics()
        val sink = CapturingSink()
        val midi = midiWith(3_000_000)
        val reporter = MetricsReporter(metrics, midi = { midi }, sink = sink)
        exercise(metrics)

        reporter.flush()
        val first = sink.sent.toList()
        assertEquals(1.0, first.single { it.name == "artnet.frame.overrun" }.value)
        assertEquals(3.0, first.single { it.name == "ws.outbound.dropped" }.value)
        assertEquals(listOf("p50", "p95", "max"), first.filter { it.name == "artnet.frame.lateness" }.map { it.attributes["stat"] })
        assertEquals(3, first.count { it.name == "midi.latency" })

        sink.sent.clear()
        reporter.flush()
        val second = sink.sent.map { it.name }.toSet()
        assertEquals(setOf("ws.clients", "jvm.heap.used"), second, "only gauges in a window that recorded nothing")
        assertEquals(1L, midi.bucket(MidiLatencyStage.INGRESS_BUTTON).count, "the operator's MIDI histogram is read, never reset")

        metrics.artNet(Universe(0, 1)).overrun()
        metrics.artNet(Universe(0, 1)).overrun()
        sink.sent.clear()
        reporter.flush()
        assertEquals(2.0, sink.sent.single { it.name == "artnet.frame.overrun" }.value)
    }

    @Test
    fun `every section 3_4 metric is sent, through Sentry, with nothing leaving the machine`() {
        val metrics = DeskMetrics()
        exercise(metrics)
        val names = withTestSentry { captured ->
            MetricsReporter(metrics, midi = { midiWith(1_000_000) }, activeFx = { 4 }).flush()
            captured.metrics.toList()
        }.groupBy { it.name }

        assertEquals(
            setOf(
                "artnet.frame.lateness", "artnet.frame.overrun", "fx.tick.duration", "fx.active_effects", "ws.clients",
                "ws.outbound.dropped", "midi.latency", "db.connection.wait", "cue.go.duration", "boot.duration", "script.cache",
                "sync.run", "jvm.heap.used", "jvm.gc.pause",
            ),
            names.keys,
        )
        assertEquals("gauge", names.getValue("fx.active_effects").single().type)
        assertEquals("counter", names.getValue("sync.run").first().type)
        val lateness = names.getValue("artnet.frame.lateness").first()
        assertEquals("distribution", lateness.type)
        assertEquals("millisecond", lateness.unit)
        assertEquals("0:1", lateness.attributes!!.getValue("universe").value)
        assertEquals("warm", names.getValue("boot.duration").single().attributes!!.getValue("cache").value)
    }

    @Test
    fun `attributes come from a closed set of low-cardinality values`() {
        val metrics = DeskMetrics()
        exercise(metrics)
        metrics.wsDropped(wireType(ProvenanceStateOutMessage(programmerRevision = 0, entries = emptyList())), DeskMetrics.WsDrop.CLOSED)
        val sink = CapturingSink()
        MetricsReporter(metrics, midi = { midiWith(1_000_000) }, activeFx = { 1 }, sink = sink).flush()

        val allowed = setOf("universe", "loop", "type", "reason", "stage", "stat", "gc", "cache", "result", "outcome", "trigger")
        val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-")
        for (metric in sink.sent) {
            for ((key, value) in metric.attributes) {
                assertTrue(key in allowed, "${metric.name} carries attribute '$key'")
                assertTrue(value.matches(Regex("[A-Za-z0-9_.:]{1,32}")) && !uuid.containsMatchIn(value), "${metric.name}.$key = '$value'")
            }
        }
        assertTrue(sink.sent.any { it.attributes["type"] == "provenanceState" && it.attributes["reason"] == "closed" })
    }

    @Test
    fun `a histogram window reports p50, p95 and an exact max`() {
        val histogram = LatencyHistogram()
        val window = HistogramWindow(histogram)
        repeat(95) { histogram.record(1_000_000) }
        repeat(5) { histogram.record(100_000_000) }

        val summary = assertNotNull(window.take())
        assertEquals(100, summary.count)
        assertTrue(summary.p50Nanos in 524_288..1_048_576, "p50 ${summary.p50Nanos} sits in 1 ms's bucket")
        assertEquals(100_000_000, summary.maxNanos)
        assertNull(window.take(), "a second take sees nothing new")

        histogram.reset()
        repeat(120) { histogram.record(1_000_000) }
        assertEquals(120, window.take()?.count, "a reset in between is read as all new, even once the bucket has refilled past its old count")
    }

    @Test
    fun `the real-time hooks allocate nothing per call`() {
        // The hooks themselves, not the loops around them: ArtNet's send and the FX pass allocate on
        // their own account, which would swamp anything these added.
        val metrics = DeskMetrics()
        val frames = metrics.artNet(Universe(0, 1))
        val tick = metrics.fxTick(DeskMetrics.FxLoop.BEAT)
        fun hooks(n: Int) = repeat(n) { i ->
            frames.lateness.record(i * 1_000L)
            frames.overrun()
            tick.record(i * 2_000L)
        }
        hooks(200_000)
        val before = allocatedBytes()
        hooks(1_000_000)
        val allocated = allocatedBytes() - before
        assertTrue(before >= 0, "ThreadMXBean allocation counting is unavailable")
        assertTrue(allocated < 4_096, "a million hook calls allocated $allocated bytes")
    }

    @Test
    fun `db connection wait records how long a caller queued for the one connection`() {
        val metrics = DeskMetrics()
        val file = Files.createTempFile("metrics-db", ".db")
        HikariDataSource(HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            maximumPoolSize = 1
            metricsTrackerFactory = DbConnectionMetrics(metrics)
        }).use { ds ->
            val window = HistogramWindow(metrics.dbConnectionWait)
            window.take()
            val held = ds.connection
            val waiter = thread { ds.connection.close() }
            Thread.sleep(200)
            held.close()
            waiter.join()

            val summary = assertNotNull(window.take())
            assertTrue(summary.maxNanos >= 150_000_000, "waited ${summary.maxNanos} ns")
        }
        Files.deleteIfExists(file)
    }

    @Test
    fun `a GC pause is recorded from the JVM's own notification`() {
        val metrics = DeskMetrics()
        val listener = GcPauseListener(metrics)
        listener.register()
        try {
            System.gc()
            val deadline = System.currentTimeMillis() + 5_000
            while (DeskMetrics.GcKind.entries.sumOf { metrics.gcPause(it).count } == 0L && System.currentTimeMillis() < deadline) Thread.sleep(20)
        } finally {
            listener.unregister()
        }
        assertTrue(DeskMetrics.GcKind.entries.sumOf { metrics.gcPause(it).count } > 0)
    }

    @Test
    fun `stop ends the flushes`() {
        val metrics = DeskMetrics()
        val sink = CapturingSink()
        val reporter = MetricsReporter(metrics, sink = sink, intervalMs = 20)
        reporter.start()
        val deadline = System.currentTimeMillis() + 5_000
        while (sink.sent.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        reporter.stop()
        assertTrue(sink.sent.isNotEmpty(), "flushed while running")

        sink.sent.clear()
        exercise(metrics)
        Thread.sleep(150)
        assertTrue(sink.sent.isEmpty(), "flushed after stop: ${sink.sent}")
    }

    @Test
    fun `boot samples are kept to a handful and rare events to their capacity`() {
        val samples = EventSamples(capacity = 3)
        repeat(10) { samples.record(it.toDouble()) }
        assertEquals(listOf(0.0, 1.0, 2.0), samples.drain())
        samples.record(9.0)
        assertEquals(listOf(9.0), samples.drain())
    }
}
