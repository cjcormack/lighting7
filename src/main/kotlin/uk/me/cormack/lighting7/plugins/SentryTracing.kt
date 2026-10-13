package uk.me.cormack.lighting7.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.routing.PathSegmentConstantRouteSelector
import io.ktor.server.routing.PathSegmentOptionalParameterRouteSelector
import io.ktor.server.routing.PathSegmentParameterRouteSelector
import io.ktor.server.routing.PathSegmentTailcardRouteSelector
import io.ktor.server.routing.PathSegmentWildcardRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import io.ktor.util.pipeline.PipelinePhase
import io.sentry.BaggageHeader
import io.sentry.EventProcessor
import io.sentry.Hint
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryBaseEvent
import io.sentry.SentryEvent
import io.sentry.SentryTraceHeader
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.kotlin.SentryContext
import io.sentry.protocol.SentryTransaction
import io.sentry.protocol.TransactionNameSource
import kotlinx.coroutines.withContext
import kotlinx.serialization.serializer
import uk.me.cormack.lighting7.auth.AuthenticatedUser
import uk.me.cormack.lighting7.auth.authenticatedUserOrNull
import uk.me.cormack.lighting7.mcp.isRemote
import uk.me.cormack.lighting7.telemetry.LISTENER_TAG
import uk.me.cormack.lighting7.telemetry.TraceSampling
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.telemetry.sentryUser
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/**
 * Incoming traffic as Sentry transactions (Sentry plan D3, D4). Inert while Sentry is off, so the
 * test suite, which never starts it, passes straight through.
 *
 * Each HTTP call (bar a WebSocket upgrade) runs under a scope forked fresh from the root, which puts
 * the `listener` tag and the caller on anything reported while handling it. A call under `/api/`,
 * `/mcp`, `/oauth/` or `/.well-known/` is also an `http.server` transaction, continued from the
 * caller's `sentry-trace` / `baggage` and named by its route template — never the raw path, which
 * can carry a token. Installed first in `moduleWithState`, so its phase runs before StatusPages'
 * and the errors StatusPages logs are reported under the call's scope.
 */
fun Application.installSentryTracing() {
    insertPhaseBefore(ApplicationCallPipeline.Setup, SentryTracingPhase)
    intercept(SentryTracingPhase) {
        if (!Sentry.isEnabled() || call.request.header(HttpHeaders.Upgrade) != null) {
            proceed()
            return@intercept
        }
        val scopes = Sentry.forkedRootScopes("ktor.call")
        scopes.configureScope { it.addEventProcessor(CallScope(call)) }
        withContext(SentryContext(scopes)) {
            if (!isTracedPath(call.request.path())) {
                proceed()
                return@withContext
            }
            val transaction = startCallTransaction(call)
            call.attributes.put(CallTransactionKey, transaction)
            try {
                proceed()
                val status = call.response.status()?.value
                if (status != null) {
                    transaction.setData("http.response.status_code", status)
                    transaction.status = SpanStatus.fromHttpStatusCode(status, SpanStatus.UNKNOWN)
                } else {
                    transaction.status = SpanStatus.OK
                }
            } catch (e: Throwable) {
                transaction.status = if (e is CancellationException) SpanStatus.CANCELLED else SpanStatus.INTERNAL_ERROR
                transaction.throwable = e
                throw e
            } finally {
                transaction.finish()
            }
        }
    }
    monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
        val transaction = call.attributes.getOrNull(CallTransactionKey) ?: return@subscribe
        val template = routeTemplate(call.route)
        // The UI's catch-all answers any path no API route took: that is a miss, not a route.
        if (template.startsWith("/{")) return@subscribe
        transaction.setName("${call.request.httpMethod.value} $template", TransactionNameSource.ROUTE)
    }
}

/** A transaction not named by a route by the time it finishes; `beforeSendTransaction` drops it. */
internal const val UNMATCHED_ROUTE = "<unmatched>"

private val SentryTracingPhase = PipelinePhase("SentryTracing")
private val CallTransactionKey = AttributeKey<ITransaction>("lighting7.sentryTransaction")

private fun startCallTransaction(call: ApplicationCall): ITransaction {
    val scopes = Sentry.getCurrentScopes()
    val context = scopes.continueTrace(
        call.request.header(SentryTraceHeader.SENTRY_TRACE_HEADER),
        withoutSamplingInputs(call.request.headers.getAll(BaggageHeader.BAGGAGE_HEADER)),
    ) ?: TransactionContext(UNMATCHED_ROUTE, TraceSampling.OP_HTTP_SERVER)
    context.name = "${call.request.httpMethod.value} $UNMATCHED_ROUTE"
    context.operation = TraceSampling.OP_HTTP_SERVER
    context.setTransactionNameSource(TransactionNameSource.ROUTE)
    val transaction = scopes.startTransaction(context, TransactionOptions().apply { isBindToScope = true })
    transaction.setData("http.request.method", call.request.httpMethod.value)
    return transaction
}

/**
 * The caller's baggage less `sentry-sample_rand` and `sentry-sample_rate`, from which the SDK would
 * otherwise draw this call's sampling number — letting any caller, the public listener's included,
 * choose to be sampled. The trace is still continued; the desk draws its own number.
 */
internal fun withoutSamplingInputs(baggage: List<String>?): List<String>? = baggage?.map { header ->
    header.split(',').filterNot { it.substringBefore('=').trim().lowercase() in SAMPLING_INPUTS }.joinToString(",")
}

private val SAMPLING_INPUTS = setOf("sentry-sample_rand", "sentry-sample_rate")

/** Whether a request path is traced: the API and MCP, not the UI's files, boot polling or render uploads. */
internal fun isTracedPath(path: String): Boolean {
    val p = path.trimEnd('/')
    if (p == "/api/rest/status" || p.startsWith("/api/rest/stage-renders/")) return false
    return p.startsWith("/api/") || p == "/mcp" || p.startsWith("/mcp/") || p.startsWith("/oauth/") || p.startsWith("/.well-known/")
}

/** `/api/rest/projects/{projectId}/cues/{cueId}`: path segments only, every parameter by its name. */
internal fun routeTemplate(route: RoutingNode): String {
    val segments = generateSequence(route) { it.parent }.toList().asReversed().mapNotNull { node ->
        when (val selector = node.selector) {
            is PathSegmentConstantRouteSelector -> selector.value
            is PathSegmentParameterRouteSelector,
            is PathSegmentOptionalParameterRouteSelector,
            is PathSegmentTailcardRouteSelector,
            -> selector.toString()
            is PathSegmentWildcardRouteSelector -> "*"
            else -> null
        }
    }
    return "/" + segments.joinToString("/")
}

/**
 * The call's listener and caller, read when an event is captured: both are only known once the
 * public listener's hardening and the auth gate have run, after this plugin's phase.
 */
private class CallScope(private val call: ApplicationCall) : EventProcessor {
    override fun process(event: SentryEvent, hint: Hint): SentryEvent = event.also(::apply)

    override fun process(transaction: SentryTransaction, hint: Hint): SentryTransaction = transaction.also(::apply)

    private fun apply(event: SentryBaseEvent) {
        event.setTag(LISTENER_TAG, if (call.isRemote) "public" else "local")
        if (event.user == null) call.authenticatedUserOrNull?.let { event.user = sentryUser(it) }
    }
}

private val wsTypeNames = ConcurrentHashMap<Class<*>, String>()

/** A frame's `type`, as the client sent it. */
internal fun wsMessageType(message: InMessage): String =
    wsTypeNames.getOrPut(message.javaClass) { serializer(message.javaClass).descriptor.serialName }

/**
 * Handles one inbound frame as a `websocket.message` transaction named by its type, carrying the
 * socket's user — when Sentry is on and the type is a gesture (`TraceSampling.WS_MESSAGES`); a
 * continuous stream or a snapshot request is handled with no Sentry work at all. The scope is
 * forked fresh per message. Outbound frames are never traced.
 */
internal suspend fun SocketScope.traceMessage(message: InMessage, handle: suspend () -> Unit) =
    traceWsMessage(message, user, remote, handle)

internal suspend fun traceWsMessage(message: InMessage, user: AuthenticatedUser?, remote: Boolean, handle: suspend () -> Unit) {
    if (!Sentry.isEnabled()) return handle()
    val type = wsMessageType(message)
    if (!TraceSampling.isTracedWsMessage(type)) return handle()
    val scopes = Sentry.forkedRootScopes("websocket.message")
    user?.let { caller -> scopes.configureScope { it.user = sentryUser(caller) } }
    withContext(SentryContext(scopes)) {
        val transaction = Tracing.startBound(Sentry.getCurrentScopes(), TraceSampling.OP_WS_MESSAGE, type)
        // A tag on the transaction only: on the scope it would make the 4xx drop treat this
        // message's errors as a request's.
        transaction.setTag(LISTENER_TAG, if (remote) "public" else "local")
        try {
            handle()
            transaction.status = SpanStatus.OK
        } catch (e: Throwable) {
            transaction.status = if (e is CancellationException) SpanStatus.CANCELLED else SpanStatus.INTERNAL_ERROR
            transaction.throwable = e
            throw e
        } finally {
            transaction.finish()
        }
    }
}
