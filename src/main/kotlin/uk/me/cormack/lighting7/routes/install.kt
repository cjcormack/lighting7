package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.state.State

/**
 * The install table holds exactly one row per machine — see `state/State.kt` `ensureInstallRow`
 * for the bootstrap. This route exposes the row for display ("which machine am I on?") and for
 * renaming the friendly name.
 */
internal fun Route.routeApiRestInstall(state: State) {
    get<InstallResource> {
        val dto = transaction(state.database) {
            DaoInstall.all().firstOrNull()?.toDto()
        }
        if (dto == null) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Install row missing"))
            return@get
        }
        call.respond(dto)
    }

    put<InstallResource> {
        val request = call.receive<UpdateInstallRequest>()
        val trimmed = request.friendlyName.trim()
        if (trimmed.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("friendlyName must not be blank"))
            return@put
        }
        if (trimmed.length > 100) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse("friendlyName must be 100 characters or fewer"))
            return@put
        }
        val dto = transaction(state.database) {
            val install = DaoInstall.all().firstOrNull() ?: return@transaction null
            install.friendlyName = trimmed
            install.toDto()
        }
        if (dto == null) {
            call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Install row missing"))
            return@put
        }
        call.respond(dto)
    }
}

@Resource("/install")
data object InstallResource

@Serializable
data class InstallDto(
    val uuid: String,
    val friendlyName: String,
    val createdAtMs: Long,
)

@Serializable
data class UpdateInstallRequest(
    val friendlyName: String,
)

private fun DaoInstall.toDto() = InstallDto(
    uuid = uuid.toString(),
    friendlyName = friendlyName,
    createdAtMs = createdAtMs,
)
