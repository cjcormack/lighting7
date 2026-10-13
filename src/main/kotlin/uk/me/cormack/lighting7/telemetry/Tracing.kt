package uk.me.cormack.lighting7.telemetry

import io.sentry.IScopes
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryDate
import io.sentry.SpanStatus
import io.sentry.TransactionContext
import io.sentry.TransactionOptions
import io.sentry.kotlin.SentryContext
import io.sentry.protocol.TransactionNameSource
import kotlinx.coroutines.withContext
import uk.me.cormack.lighting7.plugins.isClientErrorException
import uk.me.cormack.lighting7.venue.VenueException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Spans and transactions for the desk's own operations (Sentry plan §3.3). Every helper is inert
 * while Sentry is off: it checks [Sentry.isEnabled] and otherwise just runs its block with a null
 * span. See `docs/observability-engineering.md` §"Adding a span".
 */
object Tracing {
    /** A child of the active span around [block], or just [block] when nothing is being traced. */
    fun <T> span(op: String, description: String? = null, block: (ISpan?) -> T): T =
        childOf(activeSpan(), op, description, block)

    /** As [span], under an explicit [parent] — for work on a thread the parent's scope is not current on. */
    fun <T> childOf(parent: ISpan?, op: String, description: String? = null, block: (ISpan?) -> T): T {
        if (parent == null || parent.isNoOp || parent.isFinished) return block(null)
        return runIn(parent.startChild(op, description), block)
    }

    /**
     * [op] as its own transaction named [name] — or, inside a request or message that is already
     * traced, as a child span of it. An operation the sampler always keeps ([TraceSampling.ALWAYS_KEEP])
     * becomes its own transaction when the request around it was sampled out, so it is still kept.
     */
    fun <T> transaction(op: String, name: String, block: (ISpan?) -> T): T {
        if (!Sentry.isEnabled()) return block(null)
        val parent = Sentry.getSpan()
        if (parent != null && joins(parent, op)) return runIn(parent.startChild(op, name), block)
        val scopes = forkFor(parent)
        return scopes.makeCurrent().use { runIn(startBound(scopes, op, name), block) }
    }

    /** [transaction] for suspending work: the scope follows the coroutine across threads. */
    suspend fun <T> transactionSuspending(op: String, name: String, block: suspend (ISpan?) -> T): T {
        if (!Sentry.isEnabled()) return block(null)
        val parent = Sentry.getSpan()
        if (parent != null && joins(parent, op)) return runInSuspending(parent.startChild(op, name), block)
        val scopes = forkFor(parent)
        return withContext(SentryContext(scopes)) { runInSuspending(startBound(Sentry.getCurrentScopes(), op, name), block) }
    }

    /** [span] for suspending work. */
    suspend fun <T> spanSuspending(op: String, description: String? = null, block: suspend (ISpan?) -> T): T {
        val parent = activeSpan()
        if (parent == null || parent.isNoOp || parent.isFinished) return block(null)
        return runInSuspending(parent.startChild(op, description), block)
    }

    /** The active span, or null while Sentry is off. */
    fun activeSpan(): ISpan? = if (Sentry.isEnabled()) Sentry.getSpan() else null

    /** A transaction bound to [scopes], which must be current (or about to be) for its spans to nest. */
    fun startBound(scopes: IScopes, op: String, name: String, startAt: SentryDate? = null): ITransaction =
        scopes.startTransaction(
            TransactionContext(name, TransactionNameSource.CUSTOM, op),
            TransactionOptions().apply {
                isBindToScope = true
                if (startAt != null) startTimestamp = startAt
            },
        )

    private fun joins(parent: ISpan, op: String): Boolean = parent.isSampled == true || op !in TraceSampling.ALWAYS_KEEP

    // Fresh from the root when nothing is traced: a pooled thread's own scopes may predate a
    // telemetry off→on toggle. Inside a traced call they are the call's, forked per call.
    private fun forkFor(parent: ISpan?): IScopes =
        if (parent != null) Sentry.forkedScopes("lighting7.transaction") else Sentry.forkedRootScopes("lighting7.transaction")

    private inline fun <T> runIn(span: ISpan, block: (ISpan?) -> T): T =
        try {
            block(span).also { if (span.status == null) span.status = SpanStatus.OK }
        } catch (e: Throwable) {
            fail(span, e)
            throw e
        } finally {
            span.finish()
        }

    private suspend fun <T> runInSuspending(span: ISpan, block: suspend (ISpan?) -> T): T =
        try {
            block(span).also { if (span.status == null) span.status = SpanStatus.OK }
        } catch (e: Throwable) {
            fail(span, e)
            throw e
        } finally {
            span.finish()
        }

    private fun fail(span: ISpan, e: Throwable) {
        span.status = when {
            e is CancellationException -> SpanStatus.CANCELLED
            // A refusal the caller is answered with a 4xx for is not a fault (D12).
            isClientErrorException(e) ->
                (e as? VenueException)?.let { SpanStatus.fromHttpStatusCode(it.status.value, SpanStatus.INVALID_ARGUMENT) }
                    ?: SpanStatus.INVALID_ARGUMENT
            else -> SpanStatus.INTERNAL_ERROR.also { span.throwable = e }
        }
    }
}
