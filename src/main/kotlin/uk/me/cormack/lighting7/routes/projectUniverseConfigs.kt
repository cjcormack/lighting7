package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.resources.delete
import io.ktor.server.response.*
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State

internal fun Route.routeApiRestProjectUniverseConfigs(state: State) {
    // GET /{projectId}/universe-configs - List universe configs
    get<ProjectUniverseConfigsResource> { resource ->
        val project = state.resolveProject(resource.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@get
        }

        val configs = transaction(state.database) {
            DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                .map { it.toDto() }
        }
        call.respond(configs)
    }

    // PUT /{projectId}/universe-configs/{configId} - Update a universe config
    put<ProjectUniverseConfigResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@put
        }

        val request = call.receive<UpdateUniverseConfigRequest>()
        val config = transaction(state.database) {
            val config = DaoUniverseConfig.findById(resource.configId) ?: return@transaction null
            if (config.project.id != project.id) return@transaction null

            request.address?.let { config.address = it.ifBlank { null } }
            request.controllerType?.let { config.controllerType = it }

            config.toDto()
        }

        if (config == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Universe config not found"))
            return@put
        }

        // Reload controllers so the new address takes effect at runtime
        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
            state.show.parkManager.applyToControllers(state.show.fixtures.controllers)
        }
        state.show.fixtures.patchListChanged()

        call.respond(config)
    }

    // DELETE /{projectId}/universe-configs/{configId} - Delete (cascades patches)
    delete<ProjectUniverseConfigResource> { resource ->
        val project = state.resolveProject(resource.parent.projectId)
        if (project == null) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            return@delete
        }

        val deleted = transaction(state.database) {
            val config = DaoUniverseConfig.findById(resource.configId) ?: return@transaction false
            if (config.project.id != project.id) return@transaction false

            // Delete patches in this universe first
            config.fixturePatches.forEach { patch ->
                DaoFixtureGroupMember.find { DaoFixtureGroupMembers.fixturePatch eq patch.id }
                    .forEach { it.delete() }
                patch.delete()
            }
            config.delete()
            true
        }

        if (!deleted) {
            call.respond(HttpStatusCode.NotFound, ErrorResponse("Universe config not found"))
            return@delete
        }

        // Reload controllers to remove the deleted universe
        if (state.isCurrentProject(project)) {
            DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database)
            state.show.parkManager.applyToControllers(state.show.fixtures.controllers)
        }
        state.show.fixtures.patchListChanged()

        call.respond(HttpStatusCode.NoContent)
    }
}

// Resources
@Resource("/{projectId}/universe-configs")
data class ProjectUniverseConfigsResource(val projectId: String)

@Resource("/{configId}")
data class ProjectUniverseConfigResource(val parent: ProjectUniverseConfigsResource, val configId: Int)

// DTOs
@Serializable
data class UniverseConfigDto(
    val id: Int,
    val subnet: Int,
    val universe: Int,
    val controllerType: String,
    val address: String?,
    val patchCount: Int,
)

@Serializable
data class UpdateUniverseConfigRequest(
    val address: String? = null,
    val controllerType: String? = null,
)

// Helpers
private fun DaoUniverseConfig.toDto() = UniverseConfigDto(
    id = id.value,
    subnet = subnet,
    universe = universe,
    controllerType = controllerType,
    address = address,
    patchCount = fixturePatches.count().toInt(),
)
