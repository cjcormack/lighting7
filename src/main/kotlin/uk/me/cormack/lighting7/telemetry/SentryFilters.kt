package uk.me.cormack.lighting7.telemetry

import io.ktor.server.application.ApplicationCall
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.SentryLogEvent
import io.sentry.SentryLogEventAttributeValue
import io.sentry.protocol.User
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.plugins.isClientErrorException
import java.io.IOException
import java.nio.channels.ClosedChannelException
import java.util.concurrent.CancellationException

/**
 * Put on a log call whose failure is in an operator's own script: Sentry reports it as a warning
 * tagged `origin: user-script` rather than as a desk fault. The message must not carry the script.
 */
val USER_SCRIPT: Marker = MarkerFactory.getMarker("USER_SCRIPT")

/** Set by [withSentryCallScope]: present only on events raised while handling a request. */
internal const val LISTENER_TAG = "listener"

/** What Sentry may send, and how it is scrubbed (Sentry plan D8, D12). */
object SentryFilters {
    fun beforeSend(event: SentryEvent): SentryEvent? {
        val thrown = event.throwable
        if (thrown != null && isDisconnect(thrown)) return null
        // Only a request's: the same exception logged by background work is a fault.
        if (thrown != null && event.getTag(LISTENER_TAG) != null && isClientErrorException(thrown)) return null
        if (event.getExtra("marker")?.toString()?.contains(USER_SCRIPT.name) == true) {
            event.level = SentryLevel.WARNING
            event.setTag("origin", "user-script")
        }
        scrub(event)
        return event
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

/**
 * Runs [block] — a log call — with the request's listener and caller on the Sentry scope, so an
 * error it reports names them. The user is the desk account's id and role, nothing else.
 */
internal fun ApplicationCall.withSentryCallScope(block: () -> Unit) {
    val call = this
    Sentry.withScope { scope ->
        scope.setTag(LISTENER_TAG, if (call.isRemote) "public" else "local")
        call.authenticatedUserOrNull?.let { user ->
            scope.user = User().apply {
                id = user.uuid.toString()
                data = mapOf("role" to user.role.name)
            }
        }
        block()
    }
}
