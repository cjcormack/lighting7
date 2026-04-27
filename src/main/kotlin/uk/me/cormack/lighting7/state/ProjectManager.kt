package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.show.Show

/**
 * Manages project lifecycle including loading, switching, and tracking the current project.
 */
class ProjectManager(
    private val database: Database,
    private val stateProvider: () -> State
) {
    private var _currentProject: DaoProject? = null
    val currentProject: DaoProject
        get() = checkNotNull(_currentProject) { "No current project set" }

    private var _show: Show? = null
    val show: Show
        get() = checkNotNull(_show) { "Show not initialized" }

    private val _projectChangedFlow = MutableSharedFlow<ProjectChangedEvent>(replay = 1)
    val projectChangedFlow: SharedFlow<ProjectChangedEvent> = _projectChangedFlow.asSharedFlow()

    data class ProjectChangedEvent(
        val previousProjectId: Int?,
        val newProjectId: Int,
        val newProjectName: String
    )

    /**
     * Initialize project manager on startup. Picks the project flagged `isCurrent`,
     * falling back to the first project in the DB, and auto-creates a `Default`
     * project on a brand-new install so the show can boot before the user has
     * created anything via the API.
     */
    fun initialize(): Show {
        val project = transaction(database) {
            DaoProject.find { DaoProjects.isCurrent eq true }.firstOrNull()
                ?: DaoProject.all().firstOrNull()?.also { it.isCurrent = true }
                ?: DaoProject.new {
                    name = "Default"
                    description = null
                    isCurrent = true
                }
        }

        _currentProject = project
        _show = createShow(project)
        return _show!!
    }

    /**
     * Switch to a different project at runtime.
     */
    suspend fun switchProject(projectId: Int): Show {
        val newProject = transaction(database) {
            DaoProject.findById(projectId)
                ?: throw IllegalArgumentException("Project with ID $projectId not found")
        }

        val previousProjectId = _currentProject?.id?.value

        // Shutdown current show
        _show?.let { oldShow ->
            shutdownShow(oldShow)
        }

        // Update database: clear old current, set new current
        transaction(database) {
            DaoProjects.update({ DaoProjects.isCurrent eq true }) {
                it[isCurrent] = false
            }
            newProject.isCurrent = true
        }

        // Create and start new show
        _currentProject = newProject
        _show = createShow(newProject)
        _show!!.start()

        // Emit change event for WebSocket broadcast
        _projectChangedFlow.emit(
            ProjectChangedEvent(
                previousProjectId = previousProjectId,
                newProjectId = projectId,
                newProjectName = newProject.name
            )
        )

        return _show!!
    }

    private fun createShow(project: DaoProject): Show {
        return Show(
            state = stateProvider(),
            project = project,
        )
    }

    private fun shutdownShow(show: Show) {
        // 1. Clear all FX and stop the engine
        show.fxEngine.clearAllEffects()
        show.fxEngine.stop()

        // 2. Blackout all DMX channels
        show.fixtures.controllers.forEach { controller ->
            val blackout = (1..512).map { channelNo ->
                channelNo to ChannelChange(0u, fadeMs = 0)
            }
            controller.setValues(blackout)
        }

        // 3. Clear fixtures registry (unregisters listeners, clears all state)
        show.fixtures.register(removeUnused = true) {
            // Empty block - just clears everything
        }

        // 4. Close show (additional cleanup)
        show.close()
    }
}
