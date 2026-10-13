package uk.me.cormack.lighting7.state

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import uk.me.cormack.lighting7.telemetry.Tracing
import uk.me.cormack.lighting7.dmx.ParkManager
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.show.DbFixtureLoader
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.show.ShowCarry
import uk.me.cormack.lighting7.sync.SceneImageRepoSync
import uk.me.cormack.lighting7.venue.VenuePins
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
        val newProjectName: String,
        /** A carried reload of the same project ([switchProject]'s `reload`), not a fresh start. */
        val reload: Boolean = false,
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
     * Switch to a different project at runtime — or, with [reload], rebuild the current one.
     *
     * [reload] is for the callers that changed the current project's rows underneath it and must
     * bring the live show onto them: a sync pull, a venue Update, Detach, Extract, a venue's
     * Discard. It carries the running show across ([ShowCarry], [Show.resume]): the rig holds its
     * levels and the live cues, the programmer and hand-run effects come back. Only valid for the
     * current project; otherwise it is an ordinary switch. Every other caller — `set-current`,
     * MCP's `switch_project` — gets a clean start even on the current project: everything stops,
     * and only the parks hold their level.
     */
    suspend fun switchProject(projectId: Int, reload: Boolean = false): Show = Tracing.transactionSuspending("project.switch", "project.switch") {
        switchProjectTraced(projectId, reload)
    }

    private suspend fun switchProjectTraced(projectId: Int, reload: Boolean): Show {
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
        // A show whose pinned venue version this desk lacks has no rig to load (venue plan §3.4): refuse
        // it rather than run its own additions without the venue they sit on. Every way in — import,
        // pull, clone — refuses such a show already, so only a hand-edited database gets here.
        transaction(database) { newProject.takeIf { VenuePins.isMissing(it) }?.let { VenuePins.missing(it) } }
            ?.let { throw it }

        val previousProjectId = _currentProject?.id?.value

        // Reloading the project that is already current — a sync pull, a venue Update, Detach,
        // Extract, a venue's Discard — rebuilds the show from the database but must not take the rig
        // through zero: the successor starts on this show's last frame and brings back what it was
        // running ([ShowCarry], [Show.resume]). Captured before the teardown stops anything.
        val carry = _show?.takeIf { reload && previousProjectId == projectId }?.let { ShowCarry.capture(it) }

        // Shutdown current show
        _show?.let { oldShow ->
            val finalFrames = Tracing.span("project.final-frame") { finalFrames(oldShow, projectId, carry) }
            Tracing.span("project.unload") { shutdownShow(oldShow, finalFrames) }
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
        _show = Tracing.span("project.load") { createShow(newProject, carry) }
        Tracing.span("project.start") { _show!!.start(seedValues = carry?.values.orEmpty()) }
        carry?.let { carried -> Tracing.span("project.resume") { _show!!.resume(carried) } }

        // Emit change event for WebSocket broadcast
        Tracing.spanSuspending("project.notify") { _projectChangedFlow.emit(
            ProjectChangedEvent(
                previousProjectId = previousProjectId,
                newProjectId = projectId,
                newProjectName = newProject.name,
                reload = carry != null,
            )
        ) }

        return _show!!
    }

    private fun createShow(project: DaoProject, carried: ShowCarry? = null): Show {
        pruneSceneImages(project)
        return Show(
            state = stateProvider(),
            project = project,
            carried = carried,
        )
    }

    /**
     * The frame each of [show]'s controllers is left on as it goes, by universe — what the rig holds
     * until the incoming show's first frame. Park is overlaid on it as ever, so the outgoing parks
     * stand to the last frame.
     *
     * - A **reload** ([carry]) leaves every universe the reloaded project still has exactly as it is:
     *   its successor starts from the same frame. A universe it no longer has goes dark.
     * - A **switch** blacks out every channel — except one the incoming project parks, which stays
     *   as it is until the incoming park takes it, so a channel both projects park goes from one
     *   park to the other without a frame at zero: a hard-powered fixture on a parked dimmer is not
     *   power-cycled by a project switch. A channel only the outgoing project parks goes to zero
     *   with the incoming show's first frame; that is the incoming project's word on it.
     */
    private fun finalFrames(show: Show, incomingProjectId: Int, carry: ShowCarry?): Map<Universe, Map<Int, UByte>> {
        val dark = (1..512).associateWith { 0u.toUByte() }
        if (carry != null) {
            val kept = DbFixtureLoader.universesOf(incomingProjectId, database)
            return show.fixtures.controllers.associate { controller ->
                controller.universe to (carry.values[controller.universe]?.takeIf { controller.universe in kept } ?: dark)
            }
        }
        val incomingParks = ParkManager.storedParks(database, incomingProjectId)
            .groupBy({ it.universe }, { it.channel })
        return show.fixtures.controllers.associate { controller ->
            val parked = incomingParks[controller.universe.universe].orEmpty()
            val current = controller.currentValues
            controller.universe to (dark + parked.associateWith { current[it] ?: 0u })
        }
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

    private fun shutdownShow(show: Show, finalFrames: Map<Universe, Map<Int, UByte>>) {
        show.beginClose()
        // 1. Pin every controller on the frame it leaves on the rig ([finalFrames]) before anything
        //    is stopped. Stopping the FX engine resets every property its effects covered, and that
        //    write used to reach the wire for a frame or two before the blackout landed — a dip on a
        //    channel the next show would have held. Frozen, the buffer can do as it likes.
        show.fixtures.controllers.forEach { controller ->
            finalFrames[controller.universe]?.let { controller.freeze(it) }
        }

        // 2. Clear all FX and stop the engine
        show.fxEngine.clearAllEffects()
        show.fxEngine.stop()

        // 3. Clear fixtures registry (unregisters listeners, clears all state). This closes every
        //    controller and waits for its last — frozen — frame to go out, so the incoming show's
        //    first frame can only follow it.
        show.fixtures.register(removeUnused = true) {
            // Empty block - just clears everything
        }

        // 4. Close show (additional cleanup)
        show.close()
    }
}
