package uk.me.cormack.lighting7.telemetry

import io.ktor.server.config.ApplicationConfig
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoTelemetrySettings
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.state.optionalBoolean
import uk.me.cormack.lighting7.state.optionalString
import uk.me.cormack.lighting7.update.BuildInfo

private val logger = LoggerFactory.getLogger(TelemetrySettings::class.java)

/** The `sentry { }` config block. */
data class SentryConfig(
    val dsn: String?,
    /** `false` in local.conf turns telemetry off whatever the install's choice. */
    val enabled: Boolean,
    /** Null derives it from the environment. */
    val tracesSampleRate: Double?,
    /** Null derives it from the build. */
    val environment: String?,
) {
    companion object {
        fun from(config: ApplicationConfig): SentryConfig = SentryConfig(
            dsn = config.optionalString("sentry.dsn"),
            enabled = config.optionalBoolean("sentry.enabled", default = true),
            tracesSampleRate = config.optionalString("sentry.tracesSampleRate")?.let { raw ->
                raw.toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
                    ?: null.also { logger.warn("sentry.tracesSampleRate '{}' is not a number from 0 to 1; using the default", raw) }
            },
            environment = config.optionalString("sentry.environment"),
        )
    }
}

/**
 * This install's telemetry switch (Sentry plan D1): one choice for the backend and every browser,
 * stored machine-local in `telemetry_settings`. An install that has never chosen sends from a DEV
 * build and not from a RELEASE one. Owns no Sentry state of its own — `SentryRuntime`, wired in
 * `Application.module()` only, listens through [onChange].
 */
class TelemetrySettings(
    private val database: Database,
    val config: SentryConfig,
    private val build: BuildInfo,
) {
    @Volatile
    var release: String = releaseName(build)
        private set

    /** A dev build carries no commit; `Application.module()` supplies the checkout's instead. */
    fun adoptDevCommit(shortSha: String) {
        if (build.commitSha == null) release = releaseName(build.copy(commitSha = shortSha))
    }

    val environment: String = config.environment ?: if (build.channel == BuildInfo.Channel.RELEASE) "release" else "development"
    val tracesSampleRate: Double = config.tracesSampleRate ?: if (environment == "release") 0.2 else 1.0

    /** The choice of an install that has never made one. */
    val defaultChoice: Boolean = build.channel == BuildInfo.Channel.DEV

    @Volatile
    private var stored: Boolean? = transaction(database) { DaoTelemetrySettings.all().firstOrNull()?.enabled }

    /** Called with [enabled] after every [setChoice]. */
    @Volatile
    var onChange: ((Boolean) -> Unit)? = null

    /** The admin's choice, or [defaultChoice]. */
    val choice: Boolean get() = stored ?: defaultChoice

    /** Whether telemetry runs: the choice, unless local.conf turns it off. */
    val enabled: Boolean get() = config.enabled && choice

    fun setChoice(enabled: Boolean) {
        transaction(database) {
            val row = DaoTelemetrySettings.all().firstOrNull()
            if (row == null) {
                DaoTelemetrySettings.new {
                    this.enabled = enabled
                    updatedAt = nowUtc()
                }
            } else {
                row.enabled = enabled
                row.updatedAt = nowUtc()
            }
        }
        stored = enabled
        onChange?.invoke(this.enabled)
    }

    companion object {
        /** `lighting7@<version>+<shortSha>`, the release name both Sentry projects share (D10). */
        fun releaseName(build: BuildInfo): String =
            "lighting7@${build.version}" + (build.shortSha?.let { "+$it" } ?: "")
    }
}
