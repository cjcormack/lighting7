package uk.me.cormack.lighting7.testsupport

import io.sentry.SentryEvent
import io.sentry.SentryMetricsEvent
import io.sentry.Sentry
import io.sentry.SentryOptions
import io.sentry.protocol.SentryTransaction
import io.sentry.transport.NoOpTransport
import uk.me.cormack.lighting7.telemetry.SentryFilters
import uk.me.cormack.lighting7.telemetry.TraceSampling
import java.util.concurrent.CopyOnWriteArrayList

/** What Sentry would have sent, after the desk's own filters. */
class CapturedSentry {
    val events = CopyOnWriteArrayList<SentryEvent>()
    val transactions = CopyOnWriteArrayList<SentryTransaction>()
    val metrics = CopyOnWriteArrayList<SentryMetricsEvent>()
}

/**
 * Runs [block] with Sentry started in this process, its transport a no-op: nothing leaves the
 * machine. Every transaction is sampled, through the desk's own sampler, filters and scrubber.
 */
fun <T> withTestSentry(block: (CapturedSentry) -> T): T {
    val captured = CapturedSentry()
    Sentry.init { options ->
        options.dsn = "https://key@sentry.invalid/1"
        options.setTransportFactory { _, _ -> NoOpTransport.getInstance() }
        options.isSendClientReports = false
        options.isEnableUncaughtExceptionHandler = false
        options.isEnableShutdownHook = false
        options.tracesSampleRate = 1.0
        options.tracesSampler = SentryOptions.TracesSamplerCallback { TraceSampling.sample(it, 1.0) }
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> SentryFilters.beforeSend(event)?.also(captured.events::add) }
        options.beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { tx, _ ->
            SentryFilters.beforeSendTransaction(tx, 1.0)?.also(captured.transactions::add)
        }
        options.metrics.beforeSend = SentryOptions.Metrics.BeforeSendMetricCallback { metric, _ -> metric.also(captured.metrics::add) }
    }
    try {
        return block(captured)
    } finally {
        Sentry.close()
    }
}
