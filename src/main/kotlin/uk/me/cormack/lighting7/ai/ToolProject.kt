package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.state.State

/**
 * The project a stored-data tool acts on: the one its [field] names, else the current one. A venue
 * version answers as absent, as it is absent from `list_projects`. Writing to a project that is not
 * current touches nothing live, as the REST routes' `withProject` writes do; the caller still asks
 * [isCurrent] before reloading the running rig.
 */
internal class ToolProject(val project: DaoProject, val isCurrent: Boolean)

/** A tool argument naming no project the tool may act on; its message is the tool's answer. */
internal class ToolProjectException(message: String) : Exception(message)

/** The `projectId` schema text every project-scoped setup tool shares. */
internal const val PROJECT_ID_DESCRIPTION =
    "Act on this project (an id from list_projects) instead of the current one. A project that is not current is " +
        "read and written as stored, without switch_project and without touching the live output."

/**
 * Resolve [field] of [input] (absent or null is the current project). Throws [ToolProjectException]
 * for a malformed id or one that names no listed project.
 */
internal fun State.toolProject(input: JsonObject, field: String = "projectId"): ToolProject {
    val current = projectManager.currentProject
    val raw = input[field]
    if (raw == null || raw is JsonNull) return ToolProject(current, isCurrent = true)
    val id = (raw as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
        ?: throw ToolProjectException("$field must be a project id (an integer from list_projects)")
    val project = transaction(database) {
        DaoProject.findById(id)?.takeIf { it.kind != ProjectKind.VENUE_VERSION }
    } ?: throw ToolProjectException("Project not found: $id (list_projects lists them)")
    return ToolProject(project, isCurrent = project.id == current.id)
}
