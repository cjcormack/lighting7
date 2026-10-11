package uk.me.cormack.lighting7.routes

import io.ktor.http.*
import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.resources.*
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get as routingGet
import io.ktor.server.routing.post as routingPost
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.auth.adminOnly
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.venue.VenueException
import uk.me.cormack.lighting7.venue.VenueRigGuard
import uk.me.cormack.lighting7.venue.VenueVersions
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.ImportError
import uk.me.cormack.lighting7.sync.ProjectCloner

/**
 * Resolves a project ID string to a DaoProject.
 * Supports both numeric IDs and the "current" keyword.
 *
 * @return The resolved DaoProject or null if not found
 */
internal fun State.resolveProject(projectIdStr: String): DaoProject? {
    return transaction(database) {
        if (projectIdStr.equals("current", ignoreCase = true)) {
            projectManager.currentProject
        } else {
            projectIdStr.toIntOrNull()?.let { DaoProject.findById(it) }
        }
    }
}

/**
 * Checks if the given project is the current active project.
 */
internal fun State.isCurrentProject(project: DaoProject): Boolean {
    return transaction(database) {
        project.id == projectManager.currentProject.id
    }
}

/**
 * Resolve [projectIdStr] and run [block] with the resolved project. If no project matches,
 * responds with 404 and skips the block. The block can use plain `return` to early-exit
 * the enclosing route handler.
 */
internal suspend inline fun RoutingContext.withProject(
    state: State,
    projectIdStr: String,
    block: (DaoProject) -> Unit,
) {
    val project = state.resolveProject(projectIdStr)
    if (project == null) {
        call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
        return
    }
    block(project)
}

/**
 * Resolve [projectIdStr] and verify it is the current project before running [block].
 * Responds with 404 if the project is not found, or 409 with [conflictMessage] if it is
 * not the current project. The block can use plain `return` to early-exit the enclosing
 * route handler.
 */
internal suspend inline fun RoutingContext.withCurrentProject(
    state: State,
    projectIdStr: String,
    conflictMessage: String = "Cannot modify - not current project",
    block: (DaoProject) -> Unit,
) {
    withCurrentProject(state, projectIdStr, { conflictMessage }, block)
}

/** Overload that derives the 409 message from the resolved project (e.g. interpolating `project.name`). */
internal suspend inline fun RoutingContext.withCurrentProject(
    state: State,
    projectIdStr: String,
    crossinline conflictMessage: (DaoProject) -> String,
    block: (DaoProject) -> Unit,
) {
    withProject(state, projectIdStr) { project ->
        if (!state.isCurrentProject(project)) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse(conflictMessage(project)))
            return@withProject
        }
        block(project)
    }
}

internal fun Route.routeApiRestProjects(state: State) {
    route("/projects") {
        // Venue versions refuse every write, and venues every stored write by an operator.
        installProjectWriteGuard(state)

        // GET / - List all projects. A venue version is never listed: it is reached through its
        // venue's history (`/venues/{id}/versions`), never picked.
        routingGet {
            val projects = transaction(state.database) {
                listedProjects().orderBy(DaoProjects.name to SortOrder.ASC).map { it.toListDto() }
            }
            call.respond(projects)
        }

        // GET /current - Get current project
        routingGet("/current") {
            val project = transaction(state.database) {
                state.projectManager.currentProject.toDetailDto()
            }
            call.respond(project)
        }

        // GET /{id} - Get project by ID
        get<ProjectIdResource> { resource ->
            val project = transaction(state.database) {
                DaoProject.findById(resource.id)?.toDetailDto()
            }
            if (project != null) {
                call.respond(project)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // POST / - Create new project
        routingPost {
            val request = call.receive<CreateProjectRequest>()
            // *Add venue* (venue plan session 2): a venue is an admin's, and a version is only ever
            // made by publishing one.
            when (request.kind) {
                ProjectKind.SHOW -> Unit
                ProjectKind.VENUE -> call.requireAdmin()
                ProjectKind.VENUE_VERSION -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("A venue version is made by publishing its venue", CODE_VENUE_VERSION_READ_ONLY),
                    )
                    return@routingPost
                }
            }
            if (request.venueId != null && request.kind != ProjectKind.SHOW) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Only a show sits on a venue", VenueVersions.CODE_NOT_A_VENUE))
                return@routingPost
            }
            val stageError = validateStageDimensions(
                request.stageWidthM,
                request.stageDepthM,
                request.stageHeightM,
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@routingPost
            }
            val project = transaction(state.database) {
                // A show on a venue (venue plan session 3) pins the venue's installed version; its stage
                // size is the venue's, so the request's is not stored.
                val pin = request.venueId?.let { venueId ->
                    val venue = VenueVersions.requireVenue(venueId)
                    val installed = VenueVersions.versionsOf(venueId).firstOrNull()
                        ?: throw VenueException.conflict(
                            VenueVersions.CODE_NOTHING_PUBLISHED,
                            "${venue.name} has no published version yet: publish it before a show can sit on it",
                        )
                    venue.uuid to installed.number
                }
                val newProject = DaoProject.new {
                    name = request.name
                    description = request.description
                    isCurrent = false
                    if (pin == null) {
                        stageWidthM = request.stageWidthM
                        stageDepthM = request.stageDepthM
                        stageHeightM = request.stageHeightM
                    }
                    kind = request.kind
                    venueUuid = pin?.first
                    venueVersion = pin?.second
                }
                ensureDefaultSpeedMasters(newProject)

                newProject.toDetailDto()
            }
            call.respond(HttpStatusCode.Created, project)
        }

        // PUT /{id} - Update project
        put<ProjectIdResource> { resource ->
            val request = call.receive<UpdateProjectRequest>()
            val stageError = validateStageDimensions(
                request.stageWidthM,
                request.stageDepthM,
                request.stageHeightM,
            )
            if (stageError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(stageError))
                return@put
            }
            val project = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction null
                // A show on a venue reads its stage size from the venue (venue plan §3.2).
                if (project.isVenueBound &&
                    listOf(request.stageWidthM, request.stageDepthM, request.stageHeightM).any { it != null }
                ) {
                    throw VenueRigGuard.venueOwned(RigScope.of(project), "The stage's size")
                }

                request.name?.let { project.name = it }
                request.description?.let { project.description = it }
                request.stageWidthM?.let { project.stageWidthM = it }
                request.stageDepthM?.let { project.stageDepthM = it }
                request.stageHeightM?.let { project.stageHeightM = it }

                project.toDetailDto()
            }

            if (project != null) {
                state.show.fixtures.projectDetailsChanged(resource.id)
                call.respond(project)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // DELETE /{id} - Delete project. A venue takes its versions with it (they are its own
        // history); a version itself is refused by the write guard.
        delete<ProjectIdResource> { resource ->
            var deletedUuids: List<String> = emptyList()
            val result = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction DeleteResult.NOT_FOUND

                if (project.isCurrent) {
                    return@transaction DeleteResult.IS_CURRENT
                }
                // A show on a venue links into its pinned version's rows by id (P3), so a venue that
                // any show pins keeps its versions: deleting them would cut those shows' rigs away.
                if (project.kind == ProjectKind.VENUE) {
                    val shows = showsOnVenue(project).map { it.name }
                    if (shows.isNotEmpty()) {
                        throw VenueException.conflict(
                            CODE_VENUE_IN_USE,
                            "${project.name} is the venue of ${shows.joinToString { "'$it'" }}: " +
                                "delete those shows first (detaching a show from its venue comes in a later release)",
                        )
                    }
                }
                val snapshots = if (project.kind == ProjectKind.VENUE) {
                    VenueVersions.versionsOf(project.id.value).map { version ->
                        val snapshot = version.snapshotProject
                        version.delete()
                        snapshot
                    }
                } else {
                    emptyList()
                }
                deletedUuids = (listOf(project) + snapshots).map { it.uuid.toString() }
                snapshots.forEach { deleteProjectRows(state, it) }
                deleteProjectRows(state, project)

                DeleteResult.SUCCESS
            }

            when (result) {
                DeleteResult.SUCCESS -> {
                    deletedUuids.forEach { deleteProjectFiles(state, it) }
                    call.respond(HttpStatusCode.NoContent)
                }
                DeleteResult.NOT_FOUND -> call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
                DeleteResult.IS_CURRENT -> call.respond(
                    HttpStatusCode.Conflict,
                    ErrorResponse("Cannot delete current project")
                )
            }
        }

        // POST /{id}/set-current - Set project as current (triggers switch)
        post<SetCurrentProjectResource> { resource ->
            try {
                state.projectManager.switchProject(resource.id)
                val project = transaction(state.database) {
                    state.projectManager.currentProject.toDetailDto()
                }
                call.respond(project)
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Project not found"))
            } catch (e: VenueException) {
                // A show whose pinned venue version is not on this desk (`VENUE_MISSING`).
                call.respond(e.status, ErrorResponse(e.message ?: "Refused", e.code))
            } catch (e: Exception) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse("Failed to switch project: ${e.message}")
                )
            }
        }

        // Script, Preset, Cue, and Cue Stack endpoints are defined in separate files
        routeApiRestProjectScripts(state)
        // The FX-preset and named-palette HTTP surfaces are deliberately **not** mounted any more.
        // Both entities are superseded by looks, and their reference mechanism now resolves through
        // LookRegistry — so a palette created through the old CRUD would be invisible to every
        // consumer. Leaving the routes reachable would let an operator build data nothing can read.
        // The files survive because the record / include / make-hard halves are being rewritten
        // against layers; the tables and routes go in the retirement pass.
        routeApiRestProjectLooks(state)
        routeApiRestLookAbsorbEffects(state)
        routeApiRestProjectTemplates(state)
        routeApiRestTemplateRecord(state)
        routeApiRestProjectSpeedMasters(state)
        routeApiRestProjectCues(state)
        routeApiRestCueCooked(state)
        routeApiRestProjectCueStacks(state)
        routeApiRestProjectCueSlots(state)
        routeApiRestProjectBusk(state)
        routeApiRestProjectBuskRig(state)
        routeApiRestProgrammerSpread(state)
        routeApiRestProgrammerAim(state)
        routeApiRestProgrammerFocus(state)
        routeApiRestBuskPress(state)
        routeApiRestVenueExtract(state)
        // Before the patch routes, so `patches/hidden` is matched as itself.
        routeApiRestShowHides(state)
        routeApiRestPatchFacets(state)
        routeApiRestProjectPatches(state)
        routeApiRestProjectRiggings(state)
        routeApiRestProjectStageRegions(state)
        routeApiRestProjectStageScene(state)
        routeApiRestProjectSceneImages(state)
        routeApiRestProjectScenery(state)
        routeApiRestProjectEffects(state)
        routeApiRestProjectFixtureCommands(state)
        routeApiRestProjectUniverseConfigs(state)
        routeApiRestProjectPatchGroups(state)
        routeApiRestProjectShow(state)
        routeApiRestProjectPromptBooks(state)
        routeApiRestProjectSurfaceBindings(state)
        // Both subtrees are admin territory, for different reasons. Cloud sync carries the
        // desk's git identity, remotes and credentials. Export/import take a caller-supplied
        // absolute filesystem path and read or write it verbatim as the desk process — the one
        // place where an authenticated caller reaches outside the app's own data directory.
        adminOnly {
            routeApiRestProjectExport(state)
            // A version syncs only as a tag of its venue's repo (venue plan §3.4).
            refuseVersionSync(state) { routeApiRestProjectCloudSync(state) }
        }

        // POST /{id}/clone - Clone a project, whole graph. Runs through the cloud-sync
        // export/import format with freshly-minted UUIDs (see ProjectCloner) rather than a
        // bespoke table walker, so newly-synced tables are cloned without a code change here.
        post<CloneProjectResource> { resource ->
            val request = call.receive<CloneProjectRequest>()
            // A show on a venue clones with its pin (venue plan §3.4): the clone is a show on the same
            // version, its own rows copied and its links into the venue kept.
            val result = try {
                // Export + import do file IO inside Exposed transactions; off-loading keeps
                // the Ktor worker free, matching the manual export route.
                withContext(Dispatchers.IO) {
                    ProjectCloner(state).clone(resource.id, request.name, request.description)
                }
            } catch (e: ImportError) {
                call.respond(e.status, ErrorResponse(e.message ?: "Clone failed", e.code))
                return@post
            } catch (e: CancellationException) {
                // The clone commits inside a blocking transaction, so cancellation surfaces
                // only after the work has landed. Rethrow rather than reporting a 500 on a
                // dead call: the caller would be told a clone failed that in fact exists, and
                // its retry would collide on the name.
                throw e
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Clone failed: ${e.message}"))
                return@post
            }

            val response = transaction(state.database) {
                val newProject = DaoProject.findById(result.projectId)
                    ?: error("Cloned project ${result.projectId} vanished")
                CloneProjectResponse(
                    project = newProject.toDetailDto(),
                    scriptsCloned = newProject.scripts.count().toInt(),
                    looksCloned = newProject.looks.count().toInt(),
                    cuesCloned = newProject.cues.count().toInt(),
                    cueStacksCloned = newProject.cueStacks.count().toInt(),
                    recordsCloned = result.recordsCloned,
                    message = "Project cloned successfully",
                )
            }
            call.respond(HttpStatusCode.Created, response)
        }
    }
}

// Resource definitions
@Resource("/{id}")
data class ProjectIdResource(val id: Int)

@Resource("/{id}/set-current")
data class SetCurrentProjectResource(val id: Int)

@Resource("/{id}/clone")
data class CloneProjectResource(val id: Int)

// DTOs
@Serializable
data class ProjectListDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean,
    /** `SHOW` or `VENUE` — a list never holds a `VENUE_VERSION`. */
    val kind: ProjectKind = ProjectKind.SHOW,
    /** The venue a show sits on (venue plan session 3); absent for a standalone show and a venue. */
    val venue: ProjectVenueDto? = null,
)

/**
 * A show's venue (venue plan §3.5): the venue by its uuid — and its project id on this desk, which
 * *Open venue* switches to — its name, the version the show is pinned to, and the venue's installed
 * (latest published) version. `behind` is session 6's. [name], [id] and [installed] are null when
 * the venue is not on this desk.
 */
@Serializable
data class ProjectVenueDto(
    val uuid: String,
    val id: Int? = null,
    val name: String? = null,
    val version: Int,
    val installed: Int? = null,
)

@Serializable
data class ProjectDetailDto(
    val id: Int,
    val name: String,
    val description: String?,
    val isCurrent: Boolean,
    val scriptCount: Int,
    /** Was `fxPresetCount`, over the retired `fx_presets`. */
    val lookCount: Int,
    val cueCount: Int,
    val cueStackCount: Int,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
    /** What the project is (venue plan §3.1); a `VENUE_VERSION` is read through its venue's history. */
    val kind: ProjectKind = ProjectKind.SHOW,
    /** The venue a show sits on (venue plan session 3); absent for a standalone show and a venue. */
    val venue: ProjectVenueDto? = null,
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
    /** `VENUE` is *Add venue*, an admin's; `VENUE_VERSION` is refused. */
    val kind: ProjectKind = ProjectKind.SHOW,
    /**
     * The venue a new show sits on (venue plan session 3), by its project id: the show is pinned to
     * the venue's installed version, and the stage size is the venue's. Null is a standalone show.
     */
    val venueId: Int? = null,
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
)

@Serializable
data class CloneProjectRequest(
    val name: String,
    val description: String? = null
)

@Serializable
data class CloneProjectResponse(
    val project: ProjectDetailDto,
    val scriptsCloned: Int,
    /** Was `presetsCloned`, over the retired `fx_presets`. */
    val looksCloned: Int,
    val cuesCloned: Int,
    val cueStacksCloned: Int = 0,
    /** Total records copied across every synced table — patches, groups, universes, cue children, … */
    val recordsCloned: Int = 0,
    val message: String
)

// ErrorResponse is defined in lightFx.kt

private enum class DeleteResult { SUCCESS, NOT_FOUND, IS_CURRENT }

// Helper extensions
private fun DaoProject.toListDto() = ProjectListDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent,
    kind = kind,
    venue = venueDto(),
)

/** The stage size is the rig's: a show on a venue reads the venue's (venue plan §3.2). */
internal fun DaoProject.toDetailDto(): ProjectDetailDto {
    val stage = RigScope.of(this).stageBounds()
    return ProjectDetailDto(
        id = id.value,
        name = name,
        description = description,
        isCurrent = isCurrent,
        scriptCount = scripts.count().toInt(),
        lookCount = looks.count().toInt(),
        cueCount = cues.count().toInt(),
        cueStackCount = cueStacks.count().toInt(),
        stageWidthM = stage.widthM,
        stageDepthM = stage.depthM,
        stageHeightM = stage.heightM,
        kind = kind,
        venue = venueDto(),
    )
}

/** This show's venue, or null for a standalone show, a venue and a version. Inside a transaction. */
internal fun DaoProject.venueDto(): ProjectVenueDto? {
    if (kind != ProjectKind.SHOW) return null
    val uuid = venueUuid ?: return null
    val version = venueVersion ?: return null
    val venue = DaoProject.find { (DaoProjects.uuid eq uuid) and (DaoProjects.kind eq ProjectKind.VENUE) }.firstOrNull()
    return ProjectVenueDto(
        uuid = uuid.toString(),
        id = venue?.id?.value,
        name = venue?.name,
        version = version,
        installed = venue?.let { VenueVersions.versionsOf(it.id.value).firstOrNull()?.number },
    )
}

/** The shows pinned to [venue], any version, by name. Inside a transaction. */
internal fun showsOnVenue(venue: DaoProject): List<DaoProject> =
    DaoProject.find { (DaoProjects.venueUuid eq venue.uuid) and (DaoProjects.kind eq ProjectKind.SHOW) }
        .sortedBy { it.name }

/** A venue a show pins cannot be deleted: the show's rig links into its versions (venue plan P3). */
internal const val CODE_VENUE_IN_USE = "VENUE_IN_USE"

/**
 * Delete [project] and every row it owns, children before parents (no FK here cascades). Inside a
 * transaction; the files a project keeps outside the database are [deleteProjectFiles]'s, after the
 * commit. Shared by a project's delete and its venue's, which takes its versions with it.
 */
internal fun deleteProjectRows(state: State, project: DaoProject) {
    // Clear the show playhead before tearing down the stacks it may point at.
    project.activeStackId = null

    // Prompt book(s) before cues — anchors reference cue rows. Iterate every
    // row for the project (not just project.promptBook) so a pre-collapse
    // project with leftover extra books is fully cleaned before its cues go,
    // avoiding orphaned anchors pointing at deleted cue rows.
    DaoPromptBook.find { DaoPromptBooks.project eq project.id }.forEach { book ->
        DaoPromptBookAnchors.deleteWhere { DaoPromptBookAnchors.promptBook eq book.id }
        DaoPromptBookAnnotations.deleteWhere { DaoPromptBookAnnotations.promptBook eq book.id }
        book.delete()
    }

    // Delete associated records in FK-safe order. Busk pages first: a pad is a plain
    // FK onto a template, Look or cue with no cascade (`DaoBuskPads`), swept by hand.
    project.buskPages.forEach { deleteBuskPage(it) }
    // And the rig, for the same reason: a tile is a plain FK onto a group or a patch.
    deleteBuskRig(project)
    // Every scenery change names one of the project's elements, so this sweeps all
    // three tables before the cues, stacks and Looks that own them go.
    deleteSceneryForElements(project.stageElements.map { it.id })
    // Cue events name a cue and a patch, both going below: through the patches, so all go.
    deleteCueEventsForPatches(project.fixturePatches.map { it.id })
    // And through their owners, for a show on a venue: its cues fire venue patches and its cues,
    // stacks and Looks move venue elements, which the two sweeps above (this project's own rows)
    // never reach.
    project.cues.forEach { cue ->
        deleteCueEvents(cue.id)
        deleteCueScenery(cue.id)
        deleteCueChildren(cue)
        cue.delete()
    }
    project.cueStacks.forEach { deleteStackScenery(it.id); it.delete() }
    project.cueSlots.forEach { it.delete() }
    // Preset property assignments before their presets: the FK has no ON DELETE
    // cascade, so PostgreSQL rejects the preset delete while assignments remain.
    // Mirrors ProjectImporter.replaceFromWorkingTree, which already does this.
    // Looks after cues: deleteCueChildren drops each cue's layers, so nothing points
    // at a look by the time it goes. No DB-level cascade — SQLite doesn't enforce one
    // without a per-connection pragma.
    project.looks.forEach { look ->
        deleteLookScenery(look.id)
        look.rows.forEach { it.delete() }
        look.effects.forEach { it.delete() }
        look.delete()
    }
    // Templates after cues: deleteCueChildren already dropped any layer pointing at
    // one. No DB-level cascade on `templates.project_id`, so an un-deleted template
    // blocks project.delete() outright.
    project.templates.forEach { template ->
        template.rows.forEach { it.delete() }
        template.effects.forEach { it.delete() }
        template.delete()
    }
    project.fixtureGroups.forEach { group ->
        group.members.forEach { it.delete() }
        group.delete()
    }
    project.fixturePatches.forEach { deletePlacementsOf(it); it.delete() }
    project.riggings.forEach { it.delete() }
    project.stageRegions.forEach { it.delete() }
    project.stageViewpoints.forEach { it.delete() }
    project.stageElements.forEach { it.delete() }
    project.universeConfigs.forEach { it.delete() }
    project.parkedChannels.forEach { it.delete() }
    project.fxDefinitions.forEach { it.delete() }
    project.scripts.forEach { it.delete() }
    project.controlSurfaceBindings.forEach { it.delete() }

    // Cloud-sync rows. These FKs have no ON DELETE cascade, so PostgreSQL
    // would reject `project.delete()` for any project that ever had a
    // sync_configs row (GET /sync/config auto-creates one). Delete children
    // before parents: session conflicts → sessions, then the project-keyed
    // tables. The on-disk working tree is removed after the commit below.
    DaoSyncSession.find { DaoSyncSessions.project eq project.id }.forEach { session ->
        session.conflicts.forEach { it.delete() }
        session.delete()
    }
    DaoSyncState.find { DaoSyncStates.project eq project.id }.forEach { it.delete() }
    DaoSyncLogEntry.find { DaoSyncLogEntries.project eq project.id }.forEach { it.delete() }
    DaoSyncLinkedRepo.find { DaoSyncLinkedRepos.project eq project.id }.forEach { it.delete() }
    DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }.forEach { it.delete() }

    // A show's hides of its venue's records (venue plan session 3): keyed by uuid, FK to the project.
    DaoShowHide.find { DaoShowHides.project eq project.id }.forEach { it.delete() }
    // And its facet overrides of venue patches (session 5), keyed the same way.
    DaoShowOverride.find { DaoShowOverrides.project eq project.id }.forEach { it.delete() }

    // Machine-local field overrides (controller IPs). Same story as the sync rows:
    // no ON DELETE cascade, so any project the operator gave a controller IP — and
    // every clone, which inherits them — would otherwise be undeletable.
    DaoMachineOverride.find { DaoMachineOverrides.project eq project.id }.forEach { it.delete() }

    state.controlSurfaceBindingService.invalidate(project.id.value)
    // No preview to clear here any more. The Look editor's live preview used to be a
    // per-project slot in a map, so deleting a project had to evict its entry; it is now
    // a single layer on the live show's programmer, and the current project cannot be
    // deleted (the route's IS_CURRENT). Cross-project interference is impossible by
    // construction rather than by cleanup.
    project.delete()
}

/** Remove the files [projectUuid] kept outside the database, best-effort, once its rows are gone. */
internal fun deleteProjectFiles(state: State, projectUuid: String) {
    // Content-addressed PDF store for this project is now unreachable.
    runCatching { state.promptScriptStoreRoot.resolve(projectUuid).toFile().deleteRecursively() }
    // And its scene images, originals and derived copies alike.
    runCatching { state.sceneImages.deleteProject(projectUuid) }
    // Cloud-sync working tree (`<root>/{uuid}/repo`) — remove the whole
    // per-project dir so a delete doesn't leave an orphaned git repo behind.
    runCatching { state.syncWorkingTreeRoot.resolve(projectUuid).toFile().deleteRecursively() }
}

/** Every project a list shows: all but the venue versions, which are reached through their venue. */
internal fun listedProjects() = DaoProject.find { DaoProjects.kind neq ProjectKind.VENUE_VERSION }

/**
 * Range-check the project's stage dimensions. Returns the first error message, or null
 * if every field is acceptable. Width/depth/height are in metres; values must be finite
 * and fit a sane physical range.
 */
internal fun validateStageDimensions(width: Double?, depth: Double?, height: Double?): String? {
    checkMetres("stageWidthM", width, 0.1, 500.0)?.let { return it }
    checkMetres("stageDepthM", depth, 0.1, 500.0)?.let { return it }
    checkMetres("stageHeightM", height, 0.1, 200.0)?.let { return it }
    return null
}

