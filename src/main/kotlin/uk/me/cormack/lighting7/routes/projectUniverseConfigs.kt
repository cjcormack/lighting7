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
import uk.me.cormack.lighting7.venue.ShowOverrides
import uk.me.cormack.lighting7.venue.VenueRigGuard

internal fun Route.routeApiRestProjectUniverseConfigs(state: State) {
    // GET /{projectId}/universe-configs - List universe configs
    get<ProjectUniverseConfigsResource> { resource ->
        withProject(state, resource.projectId) { project ->
            val configs = transaction(state.database) {
                val rig = RigScope.of(project)
                rig.universeConfigs()
                    .orderBy(DaoUniverseConfigs.universe to SortOrder.ASC)
                    .map { it.toDto(rig) }
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
                val rig = RigScope.of(project)
                val config = DaoUniverseConfig.findById(resource.configId)
                    ?.takeIf { rig.ownerOf(it.project.id) != null } ?: return@transaction null
                // A venue's universe: its controller type is the venue's (a stored field), but its
                // address and transmit interval are machine-local, keyed by the venue's id (P4) — so
                // setting them through a show sets them for the venue and every show on it, which is
                // what a node's IP on this desk is.
                if (rig.ownerOf(config.project.id) == RigOwner.VENUE &&
                    request.controllerType != null && request.controllerType != config.controllerType
                ) {
                    throw VenueRigGuard.venueOwned(rig, "Universe ${config.universe}'s controller type")
                }
                val overrideProject = rig.overrideProjectId(config.project.id)

                val addressBefore = Overrides.resolveUniverseAddress(overrideProject, config.uuid)
                val controllerTypeBefore = config.controllerType

                // Address is machine-local — written through `Overrides`; the legacy `address`
                // column on DaoUniverseConfigs is retained but unused.
                request.address?.let {
                    Overrides.setUniverseAddress(overrideProject, config.uuid, it.ifBlank { null })
                }
                request.controllerType?.let { if (it != config.controllerType) config.controllerType = it }

                if (request.resetRefreshInterval) {
                    Overrides.setUniverseRefreshIntervalMs(overrideProject, config.uuid, null)
                } else {
                    requestedInterval?.let {
                        Overrides.setUniverseRefreshIntervalMs(overrideProject, config.uuid, it)
                    }
                }

                // Only a transport change warrants tearing down and rebuilding the
                // controller; the transmit interval is re-read by the running loop every
                // frame. Compared resolved-before against resolved-after because the UI
                // sends `address` on every save, so a naive "was it present in the request"
                // check would rebuild the whole rig on an interval-only edit.
                val rebuildNeeded = Overrides.resolveUniverseAddress(overrideProject, config.uuid) != addressBefore ||
                    config.controllerType != controllerTypeBefore

                Triple(config.toDto(rig), rebuildNeeded, Universe(config.subnet, config.universe))
            }

            if (result == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Universe config not found"))
                return@withProject
            }

            val (dto, rebuildNeeded, universe) = result

            if (state.isCurrentProject(project)) {
                if (rebuildNeeded) {
                    // Reload controllers so the new address takes effect at runtime
                    DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
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
                val rig = RigScope.of(project)
                val config = VenueRigGuard.ownForWrite(
                    rig, DaoUniverseConfig.findById(resource.configId), { it.project.id }, { "Universe ${it.universe}" },
                ) ?: return@transaction null

                deleteOwnUniverse(project, config)
            }

            if (deleted == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Universe config not found"))
                return@withProject
            }
            if (deleted.first > 0) state.show.fixtures.buskRigChanged()
            if (deleted.second > 0) state.show.fixtures.cueListChanged()

            // Reload controllers to remove the deleted universe
            if (state.isCurrentProject(project)) {
                DbFixtureLoader.loadFixtures(project.id.value, state.show.fixtures, state.database, parkSource = state.show.outputSource, hold = state.show.rigHold)
            }
            state.show.fixtures.patchListChanged()

            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Delete a show's own universe [config] and everything on it, inside the caller's transaction: its
 * machine-local overrides, each of its patches as [deleteOwnPatch] deletes one, and any Address
 * override that put a venue patch on it. Answers how many busk rig tiles and cue events went, for the
 * caller's `buskRigChanged` / `cueListChanged`. The universe route's delete, and `unpatch_fixtures`'.
 */
internal fun deleteOwnUniverse(project: DaoProject, config: DaoUniverseConfig): Pair<Int, Int> {
    // Overrides FK to the project, not the universe row, so they'd otherwise linger as orphan
    // rows keyed by a now-vanished UUID.
    Overrides.setUniverseAddress(project.id.value, config.uuid, null)
    Overrides.setUniverseRefreshIntervalMs(project.id.value, config.uuid, null)

    var sweptTiles = 0
    var sweptEvents = 0
    config.fixturePatches.toList().forEach { patch ->
        val (tiles, events) = deleteOwnPatch(patch)
        sweptTiles += tiles
        sweptEvents += events
    }
    // A venue patch the show put on this universe goes back to the venue's address.
    ShowOverrides.sweepUniverse(project.id, config.uuid)
    config.delete()
    return sweptTiles to sweptEvents
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
    /** Whose the universe is in this project's rig (venue plan session 3): `SHOW` or `VENUE`. */
    val owner: RigOwner = RigOwner.SHOW,
    /** The universe's identity, as an Address facet override names it (venue plan session 5). */
    val uuid: String? = null,
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
/** As [rig] sees it: its overrides keyed by the owning project (P4), its patches the rig's on it. */
private fun DaoUniverseConfig.toDto(rig: RigScope): UniverseConfigDto {
    val overrideProject = rig.overrideProjectId(project.id)
    val overriddenInterval = Overrides.resolveUniverseRefreshIntervalMs(overrideProject, uuid)
    return UniverseConfigDto(
        id = id.value,
        subnet = subnet,
        universe = universe,
        controllerType = controllerType,
        address = Overrides.resolveUniverseAddress(overrideProject, uuid),
        refreshIntervalMs = overriddenInterval ?: ArtNetController.DEFAULT_REFRESH_INTERVAL_MS,
        refreshIntervalOverridden = overriddenInterval != null,
        patchCount = rig.patchCountOn(id),
        owner = rig.ownerOf(project.id) ?: RigOwner.SHOW,
        uuid = uuid.toString(),
    )
}
