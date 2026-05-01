package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.state.State
import java.util.concurrent.ConcurrentHashMap

/**
 * Periodic [RemoteSyncEngine.runSync] driver. One coroutine per project with
 * `sync_configs.autoSyncEnabled = true`; ticks at `autoSyncIntervalMs` (clamped to
 * [MIN_INTERVAL_MS]).
 *
 * The scheduler never auto-applies conflict sessions — if the engine returns
 * `CONFLICTS_PENDING` the loop pauses for that project until the operator resolves the
 * session via the UI. Subsequent ticks log `AUTO_SYNC_SKIPPED` rather than spamming
 * `runSync` against an active session.
 *
 * Lifecycle: [start] is called from the application bootstrap once `State` is fully
 * constructed; [stop] runs from [State.shutdown]. [reschedule] is called by the
 * `PUT /sync/config` handler whenever a project's auto-sync settings change.
 */
class AutoSyncScheduler(
    private val state: State,
    private val engine: RemoteSyncEngine,
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val perProjectJobs = ConcurrentHashMap<Int, Job>()
    private val syncLogger get() = state.syncLogger

    /** Spawn a tick loop for every project currently configured for auto-sync. */
    fun start() {
        val configs = transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.autoSyncEnabled eq true }
                .map { it.project.id.value to (it.autoSyncIntervalMs ?: DEFAULT_INTERVAL_MS) }
        }
        for ((projectId, interval) in configs) {
            launchLoopFor(projectId, interval)
        }
    }

    /**
     * Re-evaluate [projectId]'s auto-sync settings. Cancels any existing loop, and
     * (re-)launches one if `autoSyncEnabled` is true. Idempotent — calling without a
     * change is a cheap noop after the cancel/relaunch.
     */
    fun reschedule(projectId: Int) {
        perProjectJobs.remove(projectId)?.cancel()
        val interval = transaction(state.database) {
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
            if (cfg?.autoSyncEnabled == true) cfg.autoSyncIntervalMs ?: DEFAULT_INTERVAL_MS else null
        } ?: return
        launchLoopFor(projectId, interval)
    }

    fun stop() {
        scope.cancel()
        perProjectJobs.clear()
    }

    private fun launchLoopFor(projectId: Int, intervalMs: Long) {
        val effective = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val job = scope.launch {
            // Wait one full interval before the first tick so a freshly-enabled auto-sync
            // doesn't fire mid-form-submission.
            delay(effective)
            while (isActive) {
                runOneTick(projectId)
                delay(effective)
            }
        }
        perProjectJobs[projectId] = job
    }

    private suspend fun runOneTick(projectId: Int) {
        val decision = transaction(state.database) {
            val project = DaoProject.findById(projectId) ?: return@transaction TickDecision.ProjectGone
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
            if (cfg?.autoSyncEnabled != true || !cfg.enabled) return@transaction TickDecision.Disabled
            val install = DaoInstall.all().firstOrNull() ?: return@transaction TickDecision.Disabled
            if (ConflictSession.findActive(projectId) != null) return@transaction TickDecision.SessionPending
            TickDecision.Run(
                projectUuid = project.uuid,
                installUuid = install.uuid,
                installFriendly = install.friendlyName,
            )
        }

        when (decision) {
            TickDecision.ProjectGone -> {
                // Project deleted under us; cancel and forget so we don't tick forever.
                perProjectJobs.remove(projectId)?.cancel()
                return
            }
            TickDecision.Disabled -> return
            TickDecision.SessionPending -> {
                syncLogger.info(
                    projectId, SyncLogEvent.AUTO_SYNC_SKIPPED,
                    "Auto-sync skipped — conflict session pending.",
                )
                return
            }
            is TickDecision.Run -> Unit
        }

        try {
            syncLogger.info(projectId, SyncLogEvent.AUTO_SYNC_TICK, "Auto-sync tick.")
            engine.runSync(
                projectId = projectId,
                projectUuid = decision.projectUuid,
                installUuid = decision.installUuid,
                installFriendlyName = decision.installFriendly,
            )
        } catch (e: SyncException) {
            // Engine has already logged RUN_FAILED; don't let the exception bubble out
            // and kill the loop.
            logger.warn(
                "Auto-sync tick for project {} failed with {}: {}",
                projectId, e.code, e.message,
            )
        } catch (t: Throwable) {
            // Loop continuity matters — never let an unexpected throwable kill auto-sync
            // for a project until the user notices.
            logger.warn("Auto-sync tick for project {} threw {}", projectId, t.toString())
            syncLogger.error(
                projectId, SyncLogEvent.RUN_FAILED,
                "Unexpected error: ${t.message ?: t.javaClass.simpleName}",
            )
        }
    }

    private sealed class TickDecision {
        data object Disabled : TickDecision()
        data object SessionPending : TickDecision()
        data object ProjectGone : TickDecision()
        data class Run(
            val projectUuid: java.util.UUID,
            val installUuid: java.util.UUID,
            val installFriendly: String,
        ) : TickDecision()
    }

    companion object {
        /** Minimum time between ticks; protects against an over-eager UI setting. */
        const val MIN_INTERVAL_MS: Long = 60_000
        /** Default interval when `autoSyncIntervalMs` is null but auto-sync is enabled. */
        const val DEFAULT_INTERVAL_MS: Long = 15 * 60_000

        private val logger = LoggerFactory.getLogger(AutoSyncScheduler::class.java)
    }
}
