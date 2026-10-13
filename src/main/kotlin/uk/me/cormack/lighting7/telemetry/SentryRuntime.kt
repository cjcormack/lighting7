package uk.me.cormack.lighting7.telemetry

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.filter.Filter
import ch.qos.logback.core.spi.FilterReply
import io.sentry.Breadcrumb
import io.sentry.ILogger
import io.sentry.IScopes
import io.sentry.ScopeType
import io.sentry.SendCachedEnvelopeFireAndForgetIntegration
import io.sentry.SendFireAndForgetEnvelopeSender
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.SentryOptions
import io.sentry.logback.SentryAppender
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.perf.MetricsReporter
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

private val logger = LoggerFactory.getLogger(SentryRuntime::class.java)

/** What every event is tagged with (Sentry plan D11). */
data class InstallTags(val installUuid: String, val machine: String, val packaged: Boolean)

/**
 * Sentry for this process: started and stopped as [TelemetrySettings] says, never constructed by
 * `State` or `moduleWithState`, so the test suite never meets it. See
 * `docs/observability-engineering.md`.
 */
class SentryRuntime(
    private val settings: TelemetrySettings,
    private val cacheDir: Path,
    @Volatile private var install: InstallTags,
    /** Runs while Sentry does: started after init, stopped before close. */
    private val metrics: MetricsReporter? = null,
) {
    private val switcher = Executors.newSingleThreadExecutor { r -> Thread(r, "sentry-switch").apply { isDaemon = true } }

    private class Running(val rootScopes: IScopes, val appender: SentryAppender)

    @Volatile private var running: Running? = null
    @Volatile private var bootPhase: String? = null
    @Volatile private var project: Map<String, String>? = null

    /**
     * The switch: starts or stops Sentry off the caller's thread. Stopping flushes for up to two
     * seconds, then deletes the disk cache — turning telemetry off withdraws consent for what is
     * queued too.
     */
    fun apply(enabled: Boolean) {
        try {
            switcher.execute { if (enabled) start() else stop(purgeQueue = true) }
        } catch (_: RejectedExecutionException) {
            // Closed: the desk is stopping, and Sentry with it.
        }
    }

    /** Synchronous [apply], for boot and shutdown; gives up waiting after ten seconds rather than hold either. */
    fun applyNow(enabled: Boolean) {
        try {
            switcher.submit { if (enabled) start() else stop() }.get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            if (e is InterruptedException) Thread.currentThread().interrupt()
            logger.warn("Sentry didn't {} in time: {}", if (enabled) "start" else "stop", e.toString())
        }
    }

    fun setInstall(tags: InstallTags) {
        install = tags
        configureGlobal { applyInstall(it) }
    }

    /** The boot phase until the show is ready; null removes the tag. */
    fun setBootPhase(phase: String?) {
        bootPhase = phase
        configureGlobal { applyBootPhase(it) }
    }

    fun setProject(uuid: String, kind: String) {
        project = mapOf("uuid" to uuid, "kind" to kind)
        configureGlobal { applyProject(it) }
    }

    fun close() {
        applyNow(false)
        switcher.shutdown()
    }

    private fun start() {
        if (running != null) return
        val dsn = settings.config.dsn
        if (dsn == null) {
            logger.info("Telemetry is on but sentry.dsn is empty; not starting Sentry")
            return
        }
        try {
            Sentry.init { options -> configure(options, dsn) }
            // On this thread the current scopes are the root, so the run's session sits on the root
            // isolation scope and every thread's fork of it shares the one session.
            val rootScopes = Sentry.getCurrentScopes()
            configureGlobal { scope ->
                applyInstall(scope)
                applyBootPhase(scope)
                applyProject(scope)
            }
            rootScopes.startSession()
            running = Running(rootScopes, attachAppender())
            // Metrics failing to start costs metrics, never the rest of Sentry.
            try {
                metrics?.start()
            } catch (e: Throwable) {
                logger.warn("Couldn't start metrics: {}", e.toString())
            }
            logger.info("Sentry started: {} ({})", settings.release, settings.environment)
        } catch (e: Exception) {
            // WARN, not ERROR: an ERROR here would go round through the appender.
            logger.warn("Couldn't start Sentry: {}", e.message)
            runCatching { Sentry.close() }
        }
    }

    private fun stop(purgeQueue: Boolean = false) {
        val r = running ?: return
        running = null
        metrics?.stop()
        detachAppender(r.appender)
        runCatching { r.rootScopes.endSession() }
        runCatching { Sentry.close() }
        if (purgeQueue) purgeDiskCache(cacheDir)
        logger.info("Sentry stopped")
    }

    // `isSendDefaultPii` is deprecated for `dataCollection`, but configuring any of that turns most
    // collection on (cookies, bodies, file paths); `false` here is the narrow, explicit choice.
    @Suppress("DEPRECATION")
    private fun configure(options: SentryOptions, dsn: String) {
        options.dsn = dsn
        options.release = settings.release
        options.environment = settings.environment
        options.serverName = install.machine
        options.isSendDefaultPii = false
        options.cacheDirPath = cacheDir.toString()
        options.maxCacheItems = 300
        resendDiskCache(options)
        options.shutdownTimeoutMillis = 2_000
        options.flushTimeoutMillis = 2_000
        options.maxBreadcrumbs = 200
        options.isEnableUncaughtExceptionHandler = true
        options.tracesSampleRate = settings.tracesSampleRate
        options.tracesSampler = SentryOptions.TracesSamplerCallback { TraceSampling.sample(it, settings.tracesSampleRate) }
        options.beforeSendTransaction = SentryOptions.BeforeSendTransactionCallback { tx, _ -> SentryFilters.beforeSendTransaction(tx, settings.idleSyncSampleRate) }
        // No outbound host is ours (D6): GitHub and Hue never get trace headers.
        options.setTracePropagationTargets(emptyList())
        options.logs.isEnabled = true
        // Nothing in 8.60.0 reads `metrics.isEnabled`; MetricsReporter, run only while this is, is the switch (D7).
        options.metrics.isEnabled = true
        options.logs.beforeSend = SentryOptions.Logs.BeforeSendLogCallback(SentryFilters::beforeSendLog)
        options.beforeSend = SentryOptions.BeforeSendCallback { event, _ -> SentryFilters.beforeSend(event) }
        options.beforeBreadcrumb = SentryOptions.BeforeBreadcrumbCallback { crumb, _ -> SentryFilters.beforeBreadcrumb(crumb) }
        // The SDK only hands its own logger anything in debug mode; at ERROR that is a failing transport.
        options.isDebug = true
        options.setDiagnosticLevel(SentryLevel.ERROR)
        options.setLogger(SdkErrorsAsBreadcrumbs)
    }

    private fun configureGlobal(block: (io.sentry.IScope) -> Unit) {
        if (running == null && !Sentry.isEnabled()) return
        Sentry.configureScope(ScopeType.GLOBAL) { block(it) }
    }

    private fun applyInstall(scope: io.sentry.IScope) {
        val tags = install
        scope.setTag("install", tags.installUuid)
        // Logs read the scope's attributes, not its tags.
        scope.setAttribute("install", tags.installUuid)
        scope.setTag("machine", tags.machine)
        scope.setTag("packaged", tags.packaged.toString())
    }

    private fun applyBootPhase(scope: io.sentry.IScope) {
        val phase = bootPhase
        if (phase == null) scope.removeTag("boot_phase") else scope.setTag("boot_phase", phase)
    }

    private fun applyProject(scope: io.sentry.IScope) {
        project?.let { scope.setContexts("project", it) }
    }

    private fun attachAppender(): SentryAppender {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val appender = SentryAppender().apply {
            this.context = context
            name = APPENDER_NAME
            setMinimumLevel(Level.INFO)
            setMinimumEventLevel(Level.ERROR)
            setMinimumBreadcrumbLevel(Level.INFO)
            addFilter(LocalOnlyFilter)
            // Sentry is already initialised at a higher priority, so the appender's own init is ignored.
            start()
        }
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).addAppender(appender)
        return appender
    }

    private fun detachAppender(appender: SentryAppender) {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).detachAppender(appender)
        appender.stop()
    }

    /** Sentry's own logging and this package's stay local, so a failing transport cannot feed itself. */
    internal object LocalOnlyFilter : Filter<ILoggingEvent>() {
        override fun decide(event: ILoggingEvent): FilterReply =
            if (isLocalOnly(event.loggerName)) FilterReply.DENY else FilterReply.NEUTRAL

        fun isLocalOnly(loggerName: String): Boolean =
            loggerName.startsWith("io.sentry") || loggerName.startsWith(TELEMETRY_PACKAGE)
    }

    /** Sentry's own errors — a failing transport, mostly — become breadcrumbs, never log lines (D15). */
    internal object SdkErrorsAsBreadcrumbs : ILogger {
        override fun log(level: SentryLevel, message: String, vararg args: Any?) =
            record(level) { if (args.isEmpty()) message else String.format(message, *args) }

        override fun log(level: SentryLevel, message: String, throwable: Throwable?) =
            record(level) { message + (throwable?.message?.let { ": $it" } ?: "") }

        override fun log(level: SentryLevel, throwable: Throwable?, message: String, vararg args: Any?) =
            record(level) { (if (args.isEmpty()) message else String.format(message, *args)) + (throwable?.message?.let { ": $it" } ?: "") }

        override fun isEnabled(level: SentryLevel?): Boolean = level != null && level >= SentryLevel.ERROR

        private inline fun record(level: SentryLevel, text: () -> String) {
            if (!isEnabled(level)) return
            runCatching {
                val crumb = Breadcrumb().apply {
                    category = "sentry.sdk"
                    this.level = SentryLevel.WARNING
                    message = text().take(500)
                }
                Sentry.configureScope(ScopeType.GLOBAL) { it.addBreadcrumb(crumb) }
            }
        }
    }

    companion object {
        const val APPENDER_NAME = "SENTRY"

        /**
         * Sends what an offline run left in the disk cache, each time Sentry starts (D15). Only
         * Android's SDK registers this by default; on the JVM the cache is written and never read.
         */
        internal fun resendDiskCache(options: SentryOptions) {
            options.addIntegration(SendCachedEnvelopeFireAndForgetIntegration(SendFireAndForgetEnvelopeSender { options.cacheDirPath }))
        }

        /** Deletes everything the SDK queued under [cacheDir]; only after `Sentry.close`. */
        internal fun purgeDiskCache(cacheDir: Path) {
            cacheDir.toFile().listFiles()?.forEach { it.deleteRecursively() }
        }

        private const val TELEMETRY_PACKAGE = "uk.me.cormack.lighting7.telemetry"
    }
}
