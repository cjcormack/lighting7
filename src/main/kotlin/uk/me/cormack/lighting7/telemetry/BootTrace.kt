package uk.me.cormack.lighting7.telemetry

import io.sentry.IScopes
import io.sentry.ISpan
import io.sentry.ITransaction
import io.sentry.Sentry
import io.sentry.SentryInstantDate
import io.sentry.SpanStatus
import io.sentry.kotlin.SentryContext
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * The `app.boot` transaction (Sentry plan §3.3): from `module()`'s first line until the show is
 * ready or has failed, one span per boot phase. Sentry starts after `State`, so what ran before it
 * is recorded with its own timestamps.
 */
class BootTrace private constructor(private val scopes: IScopes, private val transaction: ITransaction) {
    /** For the boot coroutine: spans started on it nest under this transaction, on any thread. */
    val context: CoroutineContext get() = SentryContext(scopes)

    /** Runs [block] with this transaction's scope current, for a boot that runs on the module's own thread. */
    fun <T> onThisThread(block: () -> T): T = scopes.makeCurrent().use { block() }

    /** A span for work that has already happened, between [start] and [end]; [children] records what ran inside it. */
    fun recorded(
        op: String,
        description: String,
        start: Instant,
        end: Instant,
        parent: ISpan = transaction,
        children: (ISpan) -> Unit = {},
    ) {
        val span = parent.startChild(op, description, SentryInstantDate(start))
        children(span)
        span.finish(SpanStatus.OK, SentryInstantDate(end))
    }

    fun finish(error: Throwable? = null) {
        if (error != null) {
            transaction.throwable = error
            transaction.finish(SpanStatus.INTERNAL_ERROR)
        } else {
            transaction.finish(SpanStatus.OK)
        }
    }

    companion object {
        const val OP_PHASE = "boot.phase"

        /** The boot's transaction, started at [startedAt]; null while Sentry is off. */
        fun start(startedAt: Instant): BootTrace? {
            if (!Sentry.isEnabled()) return null
            val scopes = Sentry.forkedRootScopes("app.boot")
            return BootTrace(scopes, Tracing.startBound(scopes, TraceSampling.OP_BOOT, "app.boot", SentryInstantDate(startedAt)))
        }
    }
}
