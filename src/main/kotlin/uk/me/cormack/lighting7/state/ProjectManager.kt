package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import uk.me.cormack.lighting7.dmx.ChannelChange
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.sync.SceneImageRepoSync
import org.slf4j.LoggerFactory

/**
 * Manages project lifecycle including loading, switching, and tracking the current project.
 */
class ProjectManager(
    private val database: Database,
    private val stateProvider: () -> State
) {
    private val logger = LoggerFactory.getLogger(ProjectManager::class.java)

    private var _currentProject: DaoProject? = null
    val currentProject: DaoProject
        get() = checkNotNull(_currentProject) { "No current project set" }

    // `@Volatile`: written by the background boot coroutine (Dispatchers.IO) but read from Netty
    // request threads (readiness gate, WS warm-up) with no other happens-before edge, so a plain
    // field could let a reader keep observing a stale null after the show is available.
    @Volatile
    private var _show: Show? = null
    val show: Show
        get() = checkNotNull(_show) { "Show not initialized" }

    /** The show if [initialize] has run, otherwise null (no throw). */
    val showOrNull: Show? get() = _show

    /**
     * Project switches, as events — **replay 0 on purpose.** This is not a snapshot of "which
     * project is current": that is `projectState` on the WS side and [currentProject] here, and a
     * replayed switch event pretending to be one made every subscriber choose between re-handling
     * a switch that already happened and a `drop(1)` that swallows the first genuine one.
     */
    // `extraBufferCapacity` rather than a bare (replay = 0, buffer = 0) flow: with no buffer at
    // all, `emit` suspends until every subscriber has taken the value, which would let one wedged
    // WebSocket stall a project switch. 16 slots is more switches than can be in flight, so the
    // emit is effectively non-blocking while still never dropping an event.
    private val _projectChangedFlow = MutableSharedFlow<ProjectChangedEvent>(extraBufferCapacity = 16)
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
            // A venue version is never current (venue plan P2), so it is never the fallback either.
            DaoProject.find { DaoProjects.isCurrent eq true }.firstOrNull()
                ?: DaoProject.find { DaoProjects.kind neq ProjectKind.VENUE_VERSION }.firstOrNull()?.also { it.isCurrent = true }
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
        // A version is frozen and never loaded: its rig is read through its venue's history. The
        // routes' write guard refuses `set-current` first; this keeps MCP's `switch_project` and every
        // other caller to the same rule.
        if (newProject.kind == ProjectKind.VENUE_VERSION) {
            throw IllegalStateException("A venue version cannot be made current")
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
            // A statement, not `newProject.isCurrent = true`: reloading the project that is already
            // current (a venue's discard, a sync pull) hands in an entity whose cached flag is already
            // true, so the setter saw no change and wrote nothing — leaving no project current.
            DaoProjects.update({ DaoProjects.id eq projectId }) {
                it[isCurrent] = true
            }
            // And the entity, which `currentProject` answers with until the next switch.
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
        pruneSceneImages(project)
        return Show(
            state = stateProvider(),
            project = project,
        )
    }

    /**
     * At project load, let the scene-image store forget what no element has referenced for a week
     * (scrim plan §3.3) — an upload whose element was never saved, a cloth repainted long ago.
     * Best-effort: a failure here is a few stale files, never a project that will not load.
     */
    private fun pruneSceneImages(project: DaoProject) {
        runCatching {
            val (uuid, referenced) = transaction(database) {
                project.uuid.toString() to SceneImageRepoSync.referencedHashes(project.id.value)
            }
            val pruned = stateProvider().sceneImages.prune(uuid, referenced)
            if (pruned > 0) logger.info("Pruned {} unreferenced scene image(s) from project {}", pruned, uuid)
        }.onFailure { logger.warn("Scene image prune failed for project {}: {}", project.id.value, it.message) }
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
