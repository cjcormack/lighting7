package uk.me.cormack.lighting7.telemetry

import io.sentry.IScopes
import io.sentry.Sentry
import io.sentry.kotlin.SentryContext
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.slf4j.LoggerFactory
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * [SentryContext] for a scope that outlives a telemetry toggle (Sentry plan §3.2). A plain
 * `SentryContext()` on such a scope forks every coroutine from the scopes it was built with — no-op
 * if Sentry was off then, a closed client's after an off→on. This hands each coroutine launched on
 * the scope a `SentryContext` forked from the root as it starts, so its breadcrumbs and tags follow
 * it across threads and stay its own.
 *
 * Never on the FX engine's tick or ArtNet's send loop: a context element runs on every resumption.
 */
// CopyableThreadContextElement is both: SentryContext is built on it.
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class LiveSentryContext : AbstractCoroutineContextElement(Key), CopyableThreadContextElement<IScopes> {
    companion object Key : CoroutineContext.Key<LiveSentryContext>

    override fun copyForChild(): CopyableThreadContextElement<IScopes> = SentryContext(Sentry.forkedRootScopes("coroutine"))

    override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext = overwritingElement

    // Only ever in a scope's own context; a coroutine carries the SentryContext copyForChild made.
    override fun updateThreadContext(context: CoroutineContext): IScopes = Sentry.getCurrentScopes()

    override fun restoreThreadContext(context: CoroutineContext, oldState: IScopes) = Unit
}

/**
 * For a supervisor scope: logs at ERROR what one of its coroutines let escape, so it is reported
 * like any other fault (D8) rather than only printed. Logged under [owner], never this package's
 * logger, which stays local.
 */
fun reportingExceptionHandler(owner: Class<*>): CoroutineExceptionHandler {
    val logger = LoggerFactory.getLogger(owner)
    return CoroutineExceptionHandler { _, e ->
        if (e !is CancellationException) logger.error("Uncaught exception in a {} coroutine", owner.simpleName, e)
    }
}
