package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
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
 * `CONFLICTS_PENDING` the loop stops running syncs for that project until the operator
 * resolves the session via the UI, logging `AUTO_SYNC_SKIPPED` once rather than spamming
 * `runSync` against an active session.
 *
 * Anything that needs a human — a conflict session, a rejected OAuth token, a missing
 * credential — puts the project's loop into [nextDelayMs] backoff instead of retrying at the
 * configured interval forever. Runs are made with [SyncTrigger.AUTO], which keeps a tick that
 * changed nothing out of the activity log entirely; both exist because at the 60s floor a
 * single stuck project otherwise generated thousands of GitHub requests and log rows a day,
 * evicting every event anyone might have wanted to read.
 *
 * "Transition-only" applies to *this class's* logging — the WARN naming the reason, and the
 * one `AUTO_SYNC_SKIPPED` row. A failing run still writes its own `RUN_FAILED` row on every
 * attempt (from [RemoteSyncEngine.withProjectLock], and from the bare-`Throwable` path
 * below), which is deliberate: "still broken, as of an hour ago" is the signal whose absence
 * caused the incident this was written for. Backoff is what makes that volume reasonable —
 * ~24 rows a day rather than ~1,440.
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

    /**
     * Projects whose auto-sync has failed at least once since it last succeeded, so the recovery
     * can still be announced afterwards.
     *
     * Deliberately *not* loop-local, unlike the backoff delay. Reconnecting GitHub calls
     * [reschedule], which relaunches the loop from scratch — and a re-connect is the most common
     * way out of a blocked spell. With the memory in the coroutine, the recovering tick had no
     * idea it was recovering, so `AUTO_SYNC_RECOVERED` could only ever fire for the rare
     * fixed-itself case, and the feed was left ending on an ERROR exactly when someone had just
     * fixed the problem. Resetting the *delay* on reschedule is wanted; forgetting the *failure*
     * is not.
     */
    private val blockedSince = ConcurrentHashMap<Int, Int>()
    private val syncLogger get() = state.syncLogger

    /** Spawn a tick loop for every project currently configured for auto-sync. */
    fun start() {
        val configs = transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.autoSyncEnabled eq true }
                // A repo-less config (autoSyncEnabled but repoUrl cleared) would only ever
                // tick to TickDecision.Disabled — don't spawn a loop for it.
                .filter { !it.repoUrl.isNullOrBlank() }
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
            // Require an attached repo too — a repo-less config would only tick to Disabled.
            if (cfg?.autoSyncEnabled == true && !cfg.repoUrl.isNullOrBlank()) {
                cfg.autoSyncIntervalMs ?: DEFAULT_INTERVAL_MS
            } else {
                null
            }
        } ?: return
        launchLoopFor(projectId, interval)
    }

    fun stop() {
        scope.cancel()
        perProjectJobs.clear()
        blockedSince.clear()
    }

    private fun launchLoopFor(projectId: Int, intervalMs: Long) {
        val effective = intervalMs.coerceAtLeast(MIN_INTERVAL_MS)
        val job = scope.launch {
            // Wait one full interval before the first tick so a freshly-enabled auto-sync
            // doesn't fire mid-form-submission.
            delay(effective)
            // Consecutive blocked ticks, kept in loop-local scope on purpose: `reschedule`
            // cancels and relaunches this coroutine, so a config change (or a fresh OAuth
            // connect, which calls it) resets the backoff for free.
            var consecutiveBlocked = 0
            while (isActive) {
                when (val result = runOneTick(projectId)) {
                    is TickResult.Blocked -> {
                        consecutiveBlocked += 1
                        val nextMs = nextDelayMs(effective, consecutiveBlocked)
                        if (consecutiveBlocked == 1) {
                            // Transition only. Repeating this every tick is how a single
                            // dead credential produced ~1,400 log lines a day.
                            logger.warn(
                                "Auto-sync for project {} is not getting through ({}); backing off, " +
                                    "next attempt in {}s.",
                                projectId, result.reason, nextMs / 1_000,
                            )
                            result.logEvent?.let { syncLogger.warn(projectId, it, result.logMessage) }
                        } else {
                            logger.debug(
                                "Auto-sync for project {} still blocked after {} attempts ({}); " +
                                    "next attempt in {}s",
                                projectId, consecutiveBlocked, result.reason, nextMs / 1_000,
                            )
                        }
                        delay(nextMs)
                    }
                    // No run happened, so there is nothing to call recovered and nothing to
                    // back off from — the loop is about to exit anyway in both cases.
                    TickResult.Idle -> delay(effective)
                    // The recovery row is written by runOneTick, which owns the cross-loop
                    // failure memory; this branch only decides when to tick next.
                    TickResult.Ran -> {
                        consecutiveBlocked = 0
                        delay(effective)
                    }
                }
            }
        }
        perProjectJobs[projectId] = job
    }

    /**
     * One tick's worth of work, classified for the loop's pacing. `internal` rather than
     * private so tests can drive the classification directly — the loop's own timing is not
     * test-driven (see `AutoSyncSchedulerTest`), which left the mapping here uncovered.
     */
    internal suspend fun runOneTick(projectId: Int): TickResult {
        val result = classifyTick(projectId)
        when (result) {
            // Counted here rather than in the loop so the count survives `reschedule`.
            is TickResult.Blocked -> blockedSince.merge(projectId, 1, Int::plus)
            TickResult.Ran -> announceRecovery(projectId)
            // Left alone: a disabled project may be re-enabled later, and a deleted one has
            // already had its entry dropped in classifyTick.
            TickResult.Idle -> Unit
        }
        return result
    }

    /**
     * Say so, once, when auto-sync starts working again after a blocked spell.
     *
     * The recovering tick is usually a quiet `NO_OP` that writes nothing, so without this the
     * activity feed would end on an ERROR indefinitely with no way to tell "fixed" from "still
     * broken" — and that is the state someone lands in immediately after doing what the UI asked
     * of them.
     */
    private fun announceRecovery(projectId: Int) {
        val failures = blockedSince.remove(projectId) ?: return
        logger.info(
            "Auto-sync for project {} recovered after {} blocked attempt(s).",
            projectId, failures,
        )
        syncLogger.info(
            projectId, SyncLogEvent.AUTO_SYNC_RECOVERED,
            "Auto-sync recovered after $failures blocked attempt(s).",
        )
    }

    private suspend fun classifyTick(projectId: Int): TickResult {
        val decision = transaction(state.database) {
            val project = DaoProject.findById(projectId) ?: return@transaction TickDecision.ProjectGone
            val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
            if (cfg?.autoSyncEnabled != true || cfg.repoUrl.isNullOrBlank()) return@transaction TickDecision.Disabled
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
                blockedSince.remove(projectId)
                return TickResult.Idle
            }
            TickDecision.Disabled -> return TickResult.Idle
            TickDecision.SessionPending -> return TickResult.Blocked(
                // Also needs a human, so it backs off the same way — and its row is written
                // once on the transition rather than once a minute, since nothing else logs
                // a skipped tick.
                reason = "conflict session pending",
                logEvent = SyncLogEvent.AUTO_SYNC_SKIPPED,
                logMessage = "Auto-sync paused — conflict session pending. " +
                    "Resolve or abort it to resume.",
            )
            is TickDecision.Run -> Unit
        }

        return try {
            engine.runSync(
                projectId = projectId,
                projectUuid = decision.projectUuid,
                installUuid = decision.installUuid,
                installFriendlyName = decision.installFriendly,
                trigger = SyncTrigger.AUTO,
            )
            TickResult.Ran
        } catch (e: SyncException) {
            // The engine already wrote the RUN_FAILED row via withProjectLock, and the
            // loop logs the transition — a per-attempt WARN here is what made a dead
            // credential indistinguishable from a busy desk in the logs.
            logger.debug("Auto-sync tick for project {} failed with {}: {}", projectId, e.code, e.message)
            TickResult.Blocked("${e.code.name}: ${e.message ?: e.code.name}")
        } catch (e: CancellationException) {
            // Not a failure: `reschedule` and `stop` cancel in-flight ticks, and every
            // config save, credential store, disconnect, reconnect and shutdown does one of
            // those. Swallowing it into the handler below wrote an ERROR row reading
            // "Unexpected error: … was cancelled" into the operator's activity feed each
            // time — a false alarm in the very log this change exists to keep readable.
            throw e
        } catch (t: Throwable) {
            // Loop continuity matters — never let an unexpected throwable kill auto-sync
            // for a project until the user notices. Nothing else writes a row for this
            // path: a network failure reaches us as a bare RuntimeException from
            // JGitClient (only 401/403 become GitAuthException), so it never passes
            // through the engine's SyncException handler.
            syncLogger.error(
                projectId, SyncLogEvent.RUN_FAILED,
                "Unexpected error: ${t.message ?: t.javaClass.simpleName}",
            )
            TickResult.Blocked(t.toString())
        }
    }

    /**
     * Outcome of one tick, as far as *pacing* is concerned. Deliberately coarser than
     * [SyncOutcome]: the loop only needs to know whether to keep the user's interval or
     * start backing off.
     */
    internal sealed class TickResult {
        /** A sync run completed. Keep the configured interval. */
        data object Ran : TickResult()

        /**
         * No run was attempted — auto-sync is off, or the project has been deleted. Neither
         * progress nor a failure, so it must not be reported as a recovery: conflating the
         * two announced "recovered after N blocked attempt(s)" for a project that had merely
         * been deleted mid-outage.
         */
        data object Idle : TickResult()

        /**
         * Can't proceed until someone intervenes. [logEvent] is set only when nothing else
         * writes an activity row for this case — a failed run already logs `RUN_FAILED` —
         * and [logMessage] is what that row says, [reason] being the terser form for the
         * server log line.
         */
        data class Blocked(
            val reason: String,
            val logEvent: String? = null,
            val logMessage: String = reason,
        ) : TickResult()
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

        /** Longest a repeatedly-failing project waits between attempts. */
        const val BACKOFF_CEILING_MS: Long = 60 * 60_000

        /**
         * How long to wait after [consecutiveBlocked] successive failed ticks: the base
         * interval doubled once per failure, capped at [BACKOFF_CEILING_MS]. From the 60s
         * floor the waits run 2m, 4m, 8m, 16m, 32m, then hourly — about an hour of
         * failures before it settles at the ceiling.
         *
         * A dead GitHub authorisation used to mean 1,440 rejected token requests a day per
         * project; this makes it ~24, while still recovering within an hour of the user
         * fixing it (sooner, since re-connecting or storing a PAT reschedules the loop
         * outright).
         *
         * The cap is never allowed below [baseMs]: someone who asked for a 6-hourly sync
         * must not find that failures *shorten* it to hourly.
         *
         * Kept pure and `internal` so the curve is testable without driving coroutines.
         */
        internal fun nextDelayMs(baseMs: Long, consecutiveBlocked: Int): Long {
            if (consecutiveBlocked <= 0) return baseMs
            val cap = maxOf(BACKOFF_CEILING_MS, baseMs)
            // Shift rather than pow, and bail out before 2^63 wraps into nonsense — a desk
            // left broken for a week reaches a high exponent.
            if (consecutiveBlocked >= Long.SIZE_BITS - 1) return cap
            val factor = 1L shl consecutiveBlocked
            if (factor > cap / baseMs.coerceAtLeast(1)) return cap
            return (baseMs * factor).coerceAtMost(cap)
        }

        private val logger = LoggerFactory.getLogger(AutoSyncScheduler::class.java)
    }
}
