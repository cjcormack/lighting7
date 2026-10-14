package uk.me.cormack.lighting7.routes

import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.request.receive
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.requireAdmin
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoInstalls
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.plugins.InstallChangedOutMessage
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.ProjectExporter
import uk.me.cormack.lighting7.venue.VenueException
import java.nio.file.Files

/**
 * A new desk's onboarding: after the first admin is created the frontend asks about diagnostics and
 * cloud sync before the desk is used. The desk still boots on the `Default` project
 * `ProjectManager.initialize` creates — it needs a current project to run — so finishing onboarding
 * with projects imported switches to one and removes that placeholder while it is still empty.
 */
internal fun Route.routeApiRestInstallOnboarding(state: State) {
    post<InstallOnboardingFinishResource> {
        call.requireAdmin()
        val request = call.receive<FinishOnboardingRequest>()
        // Claimed and cleared in one statement, so two finishes cannot both get past it; a refused
        // switch puts the flag back.
        val claimed = transaction(state.database) {
            DaoInstalls.update({ DaoInstalls.onboardingPending eq true }) { it[onboardingPending] = false }
        } > 0
        if (!claimed) {
            call.respond(HttpStatusCode.Conflict, ErrorResponse("Onboarding is already finished", CODE_ONBOARDING_DONE))
            return@post
        }

        var removedPlaceholder: String? = null
        val openProjectId = request.openProjectId
        if (openProjectId != null) {
            val previousId = state.projectManager.currentProject.id.value
            if (previousId != openProjectId) {
                val refusal: Pair<HttpStatusCode, ErrorResponse>? = try {
                    state.projectManager.switchProject(openProjectId)
                    null
                } catch (e: IllegalArgumentException) {
                    HttpStatusCode.NotFound to ErrorResponse(e.message ?: "Project not found")
                } catch (e: IllegalStateException) {
                    HttpStatusCode.Conflict to ErrorResponse(e.message ?: "Refused")
                } catch (e: VenueException) {
                    e.status to ErrorResponse(e.message ?: "Refused", e.code)
                }
                if (refusal != null) {
                    markOnboardingPending(state)
                    call.respond(refusal.first, refusal.second)
                    return@post
                }
                // The switch has landed, so onboarding is done whatever happens to the placeholder.
                removedPlaceholder = try {
                    removeIfEmptyPlaceholder(state, previousId)
                } catch (e: Exception) {
                    logger.warn("Onboarding could not remove the placeholder project {}", previousId, e)
                    null
                }
            }
        }

        state.emitMachineEvent(InstallChangedOutMessage)
        call.respond(
            FinishOnboardingResponse(
                currentProjectId = state.projectManager.currentProject.id.value,
                removedPlaceholder = removedPlaceholder,
                onboardingPending = false,
            ),
        )
    }
}

/** Flag the install as owing onboarding. Called once, when the first admin account is created. */
internal fun markOnboardingPending(state: State) {
    transaction(state.database) {
        DaoInstall.all().firstOrNull()?.onboardingPending = true
    }
}

/**
 * Delete project [projectId] when it is an empty standalone show no repo is attached to, answering
 * its name, else null. Empty means no description or stage size and an export holding no record but
 * the speed masters every project is made with — the `Default` placeholder as the desk booted it.
 */
internal fun removeIfEmptyPlaceholder(state: State, projectId: Int): String? {
    val uuid = transaction(state.database) {
        val project = DaoProject.findById(projectId) ?: return@transaction null
        val attached = DaoSyncConfig.find { DaoSyncConfigs.project eq project.id }
            .any { !it.repoUrl.isNullOrBlank() }
        val untouched = project.description.isNullOrBlank() &&
            project.stageWidthM == null && project.stageDepthM == null && project.stageHeightM == null
        project.uuid.toString().takeIf {
            !project.isCurrent && project.kind == ProjectKind.SHOW && project.venueUuid == null && !attached && untouched
        }
    } ?: return null
    if (!isEmpty(state, projectId)) return null

    val name = transaction(state.database) {
        val project = DaoProject.findById(projectId) ?: return@transaction null
        if (project.isCurrent) return@transaction null
        deleteProjectRows(state, project)
        project.name
    } ?: return null
    deleteProjectFiles(state, uuid)
    logger.info("Onboarding removed the empty placeholder project '{}'", name)
    return name
}

private fun isEmpty(state: State, projectId: Int): Boolean {
    val staging = Files.createTempDirectory("onboarding-placeholder-")
    try {
        val keys = ProjectExporter(state).export(projectId, staging, blobs = false).liveKeys
        return keys.all { it.tableName in SEEDED_FOLDERS }
    } finally {
        staging.toFile().deleteRecursively()
    }
}

/** Export folders a project holds before anyone has made anything in it (`ensureDefaultSpeedMasters`). */
private val SEEDED_FOLDERS = setOf("speedMasters")

internal const val CODE_ONBOARDING_DONE = "ONBOARDING_DONE"

private val logger = LoggerFactory.getLogger("install.onboarding")

@Resource("/install/onboarding/finish")
data object InstallOnboardingFinishResource

@Serializable
data class FinishOnboardingRequest(
    /** The project to open once onboarding is done; null keeps the current one. */
    val openProjectId: Int? = null,
)

@Serializable
data class FinishOnboardingResponse(
    val currentProjectId: Int,
    /** The name of the empty placeholder project that was removed, if one was. */
    val removedPlaceholder: String? = null,
    val onboardingPending: Boolean,
)
