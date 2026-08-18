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
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.Overrides

internal fun Route.routeApiRestProjectUniverseConfigs(state: State) {
    // GET /{projectId}/universe-configs - List universe configs
    get<ProjectUniverseConfigsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val configs = transaction(state.database) {
                DaoUniverseConfig.find { DaoUniverseConfigs.project eq project.id }
                    .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                    .map { it.toDto(project.id.value) }
            }
            call.respond(configs)
        }
    }

    // PUT /{projectId}/universe-configs/{configId} - Update a universe config
    put<ProjectUniverseConfigResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val request = call.receive<UpdateUniverseConfigRequest>()

            // Validated before the transaction so a rejected value leaves no partial write.
            val requestedInterval = request.refreshIntervalMs
            if (!request.resetRefreshInterval && requestedInterval != null &&
                requestedInterval !in ArtNetController.MIN_REFRESH_INTERVAL_MS..ArtNetController.MAX_REFRESH_INTERVAL_MS
            ) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        "refreshIntervalMs must be ${ArtNetController.MIN_REFRESH_INTERVAL_MS}-" +
                            "${ArtNetController.MAX_REFRESH_INTERVAL_MS} ms " +
                            "(DMX512 caps a universe at ~44 frames/sec)",
                    ),
                )
                return@withProject
            }

            val result = transaction(state.database) {
                val config = DaoUniverseConfig.findById(resource.configId) ?: return@transaction null
                if (config.project.id != project.id) return@transaction null

                val addressBefore = Overrides.resolveUniverseAddress(project.id.value, config.uuid)
                val controllerTypeBefore = config.controllerType

                // Address is machine-local — written through `Overrides`; the legacy `address`
                // column on DaoUniverseConfigs is retained but unused.
                request.address?.let {
                    Overrides.setUniverseAddress(project.id.value, config.uuid, it.ifBlank { null })
                }
                request.controllerType?.let { config.controllerType = it }

                if (request.resetRefreshInterval) {
                    Overrides.setUniverseRefreshIntervalMs(project.id.value, config.uuid, null)
                } else {
                    requestedInterval?.let {
                        Overrides.setUniverseRefreshIntervalMs(project.id.value, config.uuid, it)
                    }
                }

                // Only a transport change warrants tearing down and rebuilding the
                // controller; the transmit interval is re-read by the running loop every
                // frame. Compared resolved-before against resolved-after because the UI
                // sends `address` on every save, so a naive "was it present in the request"
                // check would rebuild the whole rig on an interval-only edit.
                val rebuildNeeded = Overrides.resolveUniverseAddress(project.id.value, config.uuid) != addressBefore ||
                    config.controllerType != controllerTypeBefore

                Triple(config.toDto(project.id.value), rebuildNeeded, Universe(config.subnet, config.universe))
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Universe config not found"))
                return@withProject
            }

            val (dto, rebuildNeeded, universe) = result

            if (state.isCurrentProject(project)) {
                if (rebuildNeeded) {
                    // Reload controllers so the new address takes effect at runtime
                    DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
                } else {
                    // Hot-swap: a rebuild would reconstruct the socket and 512 coroutines
                    // per universe and push a controllersChanged to every client, all to
                    // change a number the transmission loop re-reads on the next tick.
                    (state.show.fixtures.controllerOrNull(universe) as? ArtNetController)
                        ?.refreshIntervalMs = dto.refreshIntervalMs
                }
            }
            state.show.fixtures.patchListChanged()

            call.respond(dto)
        }
    }

    // DELETE /{projectId}/universe-configs/{configId} - Delete (cascades patches)
    delete<ProjectUniverseConfigResource> { resource ->
        withProject(state, resource.parent.projectId) { project ->
            val deleted = transaction(state.database) {
                val config = DaoUniverseConfig.findById(resource.configId) ?: return@transaction false
                if (config.project.id != project.id) return@transaction false

                // Drop every machine-local override for this universe before the row goes
                // away — overrides FK to the project, not the universe row, so they'd
                // otherwise linger as orphan rows keyed by a now-vanished UUID.
                Overrides.setUniverseAddress(project.id.value, config.uuid, null)
                Overrides.setUniverseRefreshIntervalMs(project.id.value, config.uuid, null)

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
                return@withProject
            }

            // Reload controllers to remove the deleted universe
            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.parkManager)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
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
    /** Effective Art-Net transmit interval: this machine's override, else the default. */
    val refreshIntervalMs: Int,
    /**
     * False when [refreshIntervalMs] is simply the default. Lets the UI distinguish
     * "25 ms because nobody set anything" from "25 ms pinned on this desk" — the whole
     * point of a machine-local override is that it is otherwise invisible.
     */
    val refreshIntervalOverridden: Boolean,
    val patchCount: Int,
)

@Serializable
data class UpdateUniverseConfigRequest(
    val address: String? = null,
    val controllerType: String? = null,
    /** New machine-local transmit interval. Ignored when [resetRefreshInterval] is true. */
    val refreshIntervalMs: Int? = null,
    /**
     * Delete the interval override and fall back to the default.
     *
     * An explicit flag because kotlinx-serialization cannot tell an absent property from an
     * explicit `null` for a `T? = null` field — both decode to `null`. [address] works
     * around that with an empty-string sentinel, which has no equivalent for an `Int`
     * short of nominating a magic number.
     */
    val resetRefreshInterval: Boolean = false,
)

// Helpers
private fun DaoUniverseConfig.toDto(projectId: Int): UniverseConfigDto {
    val overriddenInterval = Overrides.resolveUniverseRefreshIntervalMs(projectId, uuid)
    return UniverseConfigDto(
        id = id.value,
        subnet = subnet,
        universe = universe,
        controllerType = controllerType,
        address = Overrides.resolveUniverseAddress(projectId, uuid),
        refreshIntervalMs = overriddenInterval ?: ArtNetController.DEFAULT_REFRESH_INTERVAL_MS,
        refreshIntervalOverridden = overriddenInterval != null,
        patchCount = fixturePatches.count().toInt(),
    )
}
