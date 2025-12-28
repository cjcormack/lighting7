package uk.me.cormack.lighting7.state

import io.ktor.server.config.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.Show

/**
 * Manages project lifecycle including loading, switching, and tracking the current project.
 */
class ProjectManager(
    private val config: ApplicationConfig,
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
     * Initialize project manager on startup.
     * Finds current project from DB, falls back to config for migration.
     */
    fun initialize(): Show {
        val project = transaction(database) {
            // Try to find a project marked as current
            var current = DaoProject.find { DaoProjects.isCurrent eq true }.firstOrNull()

            if (current == null) {
                // Migration: find by config project name and mark as current
                val configProjectName = config.property("lighting.projectName").getString()
                current = DaoProject.find { DaoProjects.name eq configProjectName }.firstOrNull()

                if (current != null) {
                    // Migrate: set script/scene references from config if not already set
                    migrateProjectFromConfig(current)
                    current.isCurrent = true
                } else {
                    throw IllegalStateException(
                        "No current project found and config project '$configProjectName' not found in database"
                    )
                }
            }

            current
        }

        _currentProject = project
        _show = createShow(project)
        return _show!!
    }

    /**
     * Migrate an existing project to use FK references based on config values.
     */
    private fun migrateProjectFromConfig(project: DaoProject) {
        val loadFixturesScriptName = config.property("lighting.loadFixturesScriptName").getString()
        val initialSceneName = config.propertyOrNull("lighting.initialSceneName")?.getString()
        val trackChangedScriptName = config.propertyOrNull("lighting.trackChangedScriptName")?.getString()
        val runLoopScriptName = config.propertyOrNull("lighting.runLoop.scriptName")?.getString()

        // Find and set load fixtures script
        if (project.loadFixturesScriptId == null && loadFixturesScriptName.isNotEmpty()) {
            val script = DaoScript.find {
                (DaoScripts.project eq project.id) and (DaoScripts.name eq loadFixturesScriptName)
            }.firstOrNull()
            if (script != null) {
                project.loadFixturesScriptId = script.id.value
            }
        }

        // Find and set initial scene
        if (project.initialSceneId == null && !initialSceneName.isNullOrEmpty()) {
            val scene = DaoScene.find {
                (DaoScenes.project eq project.id) and (DaoScenes.name eq initialSceneName)
            }.firstOrNull()
            if (scene != null) {
                project.initialSceneId = scene.id.value
            }
        }

        // Find and set track changed script
        if (project.trackChangedScriptId == null && !trackChangedScriptName.isNullOrEmpty()) {
            val script = DaoScript.find {
                (DaoScripts.project eq project.id) and (DaoScripts.name eq trackChangedScriptName)
            }.firstOrNull()
            if (script != null) {
                project.trackChangedScriptId = script.id.value
            }
        }

        // Find and set run loop script
        if (project.runLoopScriptId == null && !runLoopScriptName.isNullOrEmpty()) {
            val script = DaoScript.find {
                (DaoScripts.project eq project.id) and (DaoScripts.name eq runLoopScriptName)
            }.firstOrNull()
            if (script != null) {
                project.runLoopScriptId = script.id.value
            }
        }

        // Set run loop delay from config
        val runLoopDelay = config.propertyOrNull("lighting.runLoop.delayMs")?.getString()?.toLongOrNull()
        if (runLoopDelay != null) {
            project.runLoopDelayMs = runLoopDelay
        }
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
        val runLoopEnabled = config.propertyOrNull("lighting.runLoop.enabled")?.getString()?.toBoolean() ?: false

        // Get script/scene names from FK references, falling back to config for compatibility
        val loadFixturesScriptName = transaction(database) {
            project.loadFixturesScript?.name
        } ?: config.property("lighting.loadFixturesScriptName").getString()

        val initialSceneName = transaction(database) {
            project.initialScene?.name
        }

        val trackChangedScriptName = transaction(database) {
            project.trackChangedScript?.name
        }

        val runLoopScriptName = if (runLoopEnabled) {
            transaction(database) {
                project.runLoopScript?.name
            } ?: config.propertyOrNull("lighting.runLoop.scriptName")?.getString()
        } else {
            null
        }

        val runLoopDelay = project.runLoopDelayMs

        return Show(
            state = stateProvider(),
            project = project,
            loadFixturesScriptName = loadFixturesScriptName,
            initialSceneName = initialSceneName,
            runLoopScriptName = runLoopScriptName,
            trackChangedScriptName = trackChangedScriptName,
            runLoopDelay = runLoopDelay
        )
    }

    private fun shutdownShow(show: Show) {
        // 1. Stop all running scenes
        show.stopAllScenes()

        // 2. Clear all FX and stop the engine
        show.fxEngine.clearAllEffects()
        show.fxEngine.stop()

        // 3. Blackout all DMX channels
        show.fixtures.controllers.forEach { controller ->
            val blackout = (1..512).map { channelNo ->
                channelNo to ChannelChange(0u, fadeMs = 0)
            }
            controller.setValues(blackout)
        }

        // 4. Clear fixtures registry (unregisters listeners, clears all state)
        show.fixtures.register(removeUnused = true) {
            // Empty block - just clears everything
        }

        // 5. Close show (additional cleanup)
        show.close()
    }
}
