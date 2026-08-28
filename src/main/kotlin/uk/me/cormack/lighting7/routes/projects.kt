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
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.auth.adminOnly
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
        // GET / - List all projects
        routingGet {
            val projects = transaction(state.database) {
                DaoProject.all().orderBy(DaoProjects.name to SortOrder.ASC).map { it.toListDto() }
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
                val newProject = DaoProject.new {
                    name = request.name
                    description = request.description
                    isCurrent = false
                    stageWidthM = request.stageWidthM
                    stageDepthM = request.stageDepthM
                    stageHeightM = request.stageHeightM
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

                request.name?.let { project.name = it }
                request.description?.let { project.description = it }
                request.stageWidthM?.let { project.stageWidthM = it }
                request.stageDepthM?.let { project.stageDepthM = it }
                request.stageHeightM?.let { project.stageHeightM = it }

                project.toDetailDto()
            }

            if (project != null) {
                call.respond(project)
            } else {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Project not found"))
            }
        }

        // DELETE /{id} - Delete project
        delete<ProjectIdResource> { resource ->
            var deletedProjectUuid: String? = null
            val result = transaction(state.database) {
                val project = DaoProject.findById(resource.id)
                    ?: return@transaction DeleteResult.NOT_FOUND

                if (project.isCurrent) {
                    return@transaction DeleteResult.IS_CURRENT
                }
                deletedProjectUuid = project.uuid.toString()

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

                // Delete associated records in FK-safe order
                project.cues.forEach { cue ->
                    deleteCueChildren(cue)
                    cue.delete()
                }
                project.cueStacks.forEach { it.delete() }
                project.cueSlots.forEach { it.delete() }
                // Preset property assignments before their presets: the FK has no ON DELETE
                // cascade, so PostgreSQL rejects the preset delete while assignments remain.
                // Mirrors ProjectImporter.replaceFromWorkingTree, which already does this.
                // Looks after cues: deleteCueChildren drops each cue's layers, so nothing points
                // at a look by the time it goes. No DB-level cascade — SQLite doesn't enforce one
                // without a per-connection pragma.
                project.looks.forEach { look ->
                    look.rows.forEach { it.delete() }
                    look.effects.forEach { it.delete() }
                    look.delete()
                }
                // Templates after cues: deleteCueChildren already dropped any layer pointing at
                // one. No DB-level cascade on `templates.project_id`, so an un-deleted template
                // blocks project.delete() outright.
                project.templates.forEach { template ->
                    template.rows.forEach { it.delete() }
                    template.delete()
                }
                project.fixtureGroups.forEach { group ->
                    group.members.forEach { it.delete() }
                    group.delete()
                }
                project.fixturePatches.forEach { it.delete() }
                project.riggings.forEach { it.delete() }
                project.stageRegions.forEach { it.delete() }
                project.universeConfigs.forEach { it.delete() }
                project.parkedChannels.forEach { it.delete() }
                project.aiConversations.forEach { it.delete() }
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

                // Machine-local field overrides (controller IPs). Same story as the sync rows:
                // no ON DELETE cascade, so any project the operator gave a controller IP — and
                // every clone, which inherits them — would otherwise be undeletable.
                DaoMachineOverride.find { DaoMachineOverrides.project eq project.id }.forEach { it.delete() }

                state.controlSurfaceBindingService.invalidate(project.id.value)
                // No preview to clear here any more. The Look editor's live preview used to be a
                // per-project slot in a map, so deleting a project had to evict its entry; it is now
                // a single layer on the live show's programmer, and the current project cannot be
                // deleted (IS_CURRENT above). Cross-project interference is impossible by
                // construction rather than by cleanup.
                project.delete()

                DeleteResult.SUCCESS
            }

            when (result) {
                DeleteResult.SUCCESS -> {
                    // Content-addressed PDF store for this project is now unreachable.
                    deletedProjectUuid?.let { uuid ->
                        runCatching { state.promptScriptStoreRoot.resolve(uuid).toFile().deleteRecursively() }
                        // Cloud-sync working tree (`<root>/{uuid}/repo`) — remove the whole
                        // per-project dir so a delete doesn't leave an orphaned git repo behind.
                        runCatching { state.syncWorkingTreeRoot.resolve(uuid).toFile().deleteRecursively() }
                    }
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
        routeApiRestProjectPatches(state)
        routeApiRestProjectRiggings(state)
        routeApiRestProjectStageRegions(state)
        routeApiRestProjectUniverseConfigs(state)
        routeApiRestProjectPatchGroups(state)
        routeApiRestProjectShow(state)
        routeApiRestProjectPromptBooks(state)
        routeApiRestProjectSurfaceBindings(state)
        // Conversation history only. `POST /ai/chat` stays global — it drives the running
        // show rather than a stored project (see docs/api-conventions.md §"Project scoping").
        routeApiRestProjectAiConversations(state)
        // Both subtrees are admin territory, for different reasons. Cloud sync carries the
        // desk's git identity, remotes and credentials. Export/import take a caller-supplied
        // absolute filesystem path and read or write it verbatim as the desk process — the one
        // place where an authenticated caller reaches outside the app's own data directory.
        adminOnly {
            routeApiRestProjectExport(state)
            routeApiRestProjectCloudSync(state)
        }

        // POST /{id}/clone - Clone a project, whole graph. Runs through the cloud-sync
        // export/import format with freshly-minted UUIDs (see ProjectCloner) rather than a
        // bespoke table walker, so newly-synced tables are cloned without a code change here.
        post<CloneProjectResource> { resource ->
            val request = call.receive<CloneProjectRequest>()

            val result = try {
                // Export + import do file IO inside Exposed transactions; off-loading keeps
                // the Ktor worker free, matching the manual export route.
                withContext(Dispatchers.IO) {
                    ProjectCloner(state).clone(resource.id, request.name, request.description)
                }
            } catch (e: ImportError) {
                call.respond(e.status, ErrorResponse(e.message ?: "Clone failed"))
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
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val stageWidthM: Double? = null,
    val stageDepthM: Double? = null,
    val stageHeightM: Double? = null,
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
)

private fun DaoProject.toDetailDto() = ProjectDetailDto(
    id = id.value,
    name = name,
    description = description,
    isCurrent = isCurrent,
    scriptCount = scripts.count().toInt(),
    lookCount = looks.count().toInt(),
    cueCount = cues.count().toInt(),
    cueStackCount = cueStacks.count().toInt(),
    stageWidthM = stageWidthM,
    stageDepthM = stageDepthM,
    stageHeightM = stageHeightM,
)

/**
 * Range-check the project's stage dimensions. Returns the first error message, or null
 * if every field is acceptable. Width/depth/height are in metres; values must be finite
 * and fit a sane physical range.
 */
private fun validateStageDimensions(width: Double?, depth: Double?, height: Double?): String? {
    checkMetres("stageWidthM", width, 0.1, 500.0)?.let { return it }
    checkMetres("stageDepthM", depth, 0.1, 500.0)?.let { return it }
    checkMetres("stageHeightM", height, 0.1, 200.0)?.let { return it }
    return null
}

