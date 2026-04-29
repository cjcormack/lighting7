package uk.me.cormack.lighting7.routes

import io.ktor.resources.Resource
import io.ktor.server.application.call
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.Overrides

/**
 * Read-only listing of machine-local overrides for a project. The only writable consumer today is
 * the universe-configs UI (`PUT /universe-configs` routes through `Overrides`); this endpoint
 * exists for inspection. Edit endpoints will land with the dedicated machine-overrides UI.
 */
internal fun Route.routeApiRestProjectMachineOverrides(state: State) {
    get<ProjectMachineOverridesResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val overrides = transaction(state.database) {
                Overrides.listForProject(project.id.value)
            }
            call.respond(overrides)
        }
    }
}

@Resource("/{projectId}/machine-overrides")
data class ProjectMachineOverridesResource(val projectId: String)
