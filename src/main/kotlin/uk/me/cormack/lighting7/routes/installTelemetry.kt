package uk.me.cormack.lighting7.routes

import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.plugins.InstallChangedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.telemetry.TelemetrySettings

/**
 * Telemetry (Sentry plan D1): `GET` and `PUT /install/telemetry`. Machine-local, beside `/install`.
 * `GET` is open to every caller, signed in or not — the frontend reads it before sign-in to decide
 * whether to report — so it answers only what a browser needs. `PUT` is an admin's.
 * See `docs/observability-engineering.md`.
 */
internal fun Route.routeApiRestInstallTelemetry(state: State) {
    get<InstallTelemetryResource> {
        call.respond(state.telemetry.toDto())
    }

    put<InstallTelemetryResource> {
        call.requireAdmin()
        val request = call.receive<UpdateTelemetryRequest>()
        state.telemetry.setChoice(request.enabled)
        state.emitMachineEvent(InstallChangedOutMessage)
        call.respond(state.telemetry.toDto())
    }
}

@Resource("/install/telemetry")
data object InstallTelemetryResource

@Serializable
data class UpdateTelemetryRequest(val enabled: Boolean)

@Serializable
data class TelemetryDto(
    /** Whether this install reports: the admin's choice, unless `sentry.enabled = false` in local.conf. */
    val enabled: Boolean,
    /** `lighting7@<version>+<shortSha>`. */
    val release: String,
    /** `development`, `release` or `cloud`. */
    val environment: String,
    val tracesSampleRate: Double,
)

private fun TelemetrySettings.toDto() = TelemetryDto(
    enabled = enabled,
    release = release,
    environment = environment,
    tracesSampleRate = tracesSampleRate,
)
