package uk.me.cormack.lighting7.telemetry

import io.sentry.Breadcrumb
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.protocol.SentrySpan
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.User
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.plugins.ERROR_HANDLING_LOGGER
import uk.me.cormack.lighting7.plugins.UNMATCHED_ROUTE
import uk.me.cormack.lighting7.plugins.isClientErrorException
import java.io.IOException
import kotlin.random.Random
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException

/**
 * Put on a log call whose failure is in an operator's own script: Sentry reports it as a warning
 * tagged `origin: user-script` rather than as a desk fault. The message must not carry the script.
 */
val USER_SCRIPT: Marker = MarkerFactory.getMarker("USER_SCRIPT")

/** Set by the call scope `plugins/SentryTracing.kt` forks: present only on events raised while handling a request. */
internal const val LISTENER_TAG = "listener"

/** What Sentry may send, and how it is scrubbed (Sentry plan D8, D12). */
object SentryFilters {
    fun beforeSend(event: SentryEvent): SentryEvent? {
        val thrown = event.throwable
        if (thrown != null && isDisconnect(thrown)) return null
        // Only StatusPages' own, while answering a request: the same exception logged by a route
        // that answers 500, or by background work, is a fault.
        if (thrown != null && isStatusPagesEvent(event) && isClientErrorException(thrown)) return null
        if (event.getExtra("marker")?.toString()?.contains(USER_SCRIPT.name) == true) {
            event.level = SentryLevel.WARNING
            event.setTag("origin", "user-script")
        }
        scrub(event)
        return event
    }

    /**
     * Drops a call no route matched and an idle auto-sync run sampled out, and scrubs span
     * descriptions and data. A transaction's name is
     * a route template or a constant, never a raw path; one the scrubber would change is a naming
     * fault, and is dropped rather than sent, since the SDK gives a transaction's name no setter.
     */
    fun beforeSendTransaction(
        transaction: SentryTransaction,
        idleSyncSampleRate: Double,
        draw: Double = Random.nextDouble(),
    ): SentryTransaction? {
        val name = transaction.transaction ?: return transaction
        if (name.endsWith(UNMATCHED_ROUTE)) return null
        val trace = transaction.contexts.trace
        if (trace?.operation == TraceSampling.OP_SYNC_RUN) {
            val data = trace.data
            if (!TraceSampling.keepSyncRun(data["sync.trigger"], data["sync.outcome"], idleSyncSampleRate, draw)) return null
        }
        if (TokenScrubber.scrub(name) != name) return null
        val spans = transaction.spans
        for (i in spans.indices) spans[i] = scrubbed(spans[i])
        transaction.request?.let { r ->
            r.url = TokenScrubber.scrubOrNull(r.url)
            r.queryString = TokenScrubber.scrubOrNull(r.queryString)
        }
        return transaction
    }

    private fun scrubbed(span: SentrySpan): SentrySpan {
        span.data?.let { data ->
            span.data = data.mapValues { (key, value) ->
                when {
                    value !is String -> value
                    // An HTTP span's query arrives without its `?`, which the scrubber keys on.
                    key == "http.query" -> TokenScrubber.scrub("?$value").removePrefix("?")
                    else -> TokenScrubber.scrub(value)
                }
            }
        }
        val description = span.description ?: return span
        val clean = TokenScrubber.scrub(description)
        if (clean == description) return span
        return SentrySpan(
            span.startTimestamp, span.timestamp, span.traceId, span.spanId, span.parentSpanId,
            span.op, clean, span.status, span.origin, span.tags, span.measurements, span.data,
        )
    }

    fun beforeBreadcrumb(breadcrumb: Breadcrumb): Breadcrumb {
        breadcrumb.message = TokenScrubber.scrubOrNull(breadcrumb.message)
        for ((key, value) in breadcrumb.data.toMap()) {
            if (value is String) breadcrumb.setData(key, TokenScrubber.scrub(value))
        }
        return breadcrumb
    }

    fun beforeSendLog(log: SentryLogEvent): SentryLogEvent {
        log.body = TokenScrubber.scrub(log.body)
        log.attributes = log.attributes?.mapValues { (_, attr) ->
            val value = attr.value
            if (value is String) SentryLogEventAttributeValue(attr.type, TokenScrubber.scrub(value)) else attr
        }
        return log
    }

    private fun isStatusPagesEvent(event: SentryEvent): Boolean =
        event.getTag(LISTENER_TAG) != null && event.logger == ERROR_HANDLING_LOGGER

    /** A client that went away, or a coroutine cancelled: not a fault, anywhere in the cause chain. */
    internal fun isDisconnect(thrown: Throwable): Boolean =
        generateSequence(thrown) { it.cause.takeIf { cause -> cause !== it } }.take(16).any { t ->
            t is CancellationException ||
                t is ClosedChannelException ||
                t::class.simpleName in CLOSED_CHANNEL_TYPES ||
                (t is IOException && t.message?.lowercase()?.let { m -> DISCONNECT_MESSAGES.any { it in m } } == true)
        }

    private val CLOSED_CHANNEL_TYPES = setOf(
        "ClosedSendChannelException",
        "ClosedReceiveChannelException",
        "ClosedWriteChannelException",
        "ClosedByteChannelException",
        "ChannelWriteException",
        "ChannelReadException",
    )

    private val DISCONNECT_MESSAGES = listOf(
        "connection reset",
        "broken pipe",
        "an existing connection was forcibly closed",
        "an established connection was aborted",
    )

    private fun scrub(event: SentryEvent) {
        event.message?.let { m ->
            m.formatted = TokenScrubber.scrubOrNull(m.formatted)
            m.message = TokenScrubber.scrubOrNull(m.message)
            m.params = m.params?.map(TokenScrubber::scrub)
        }
        event.exceptions?.forEach { it.value = TokenScrubber.scrubOrNull(it.value) }
        event.breadcrumbs?.forEach(::beforeBreadcrumb)
        event.request?.let { r ->
            r.url = TokenScrubber.scrubOrNull(r.url)
            r.queryString = TokenScrubber.scrubOrNull(r.queryString)
        }
        event.transaction = TokenScrubber.scrubOrNull(event.transaction)
    }
}

/** The desk account as Sentry's user: its uuid and role, nothing else. */
internal fun sentryUser(user: AuthenticatedUser): User = User().apply {
    id = user.uuid.toString()
    data = mapOf("role" to user.role.name)
}
