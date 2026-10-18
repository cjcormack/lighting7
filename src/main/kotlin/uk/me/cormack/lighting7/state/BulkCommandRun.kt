package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.me.cormack.lighting7.fixture.CommandRole

/**
 * One command role run over a set of units (venue-visit plan S6, D19): every lamp off, every reset,
 * or a staggered strike. Owns the order and the timing and nothing else — starting a unit's command
 * is [start]'s, which `RigSafetyService` points at the show's `CommandOutput`, so every refusal a
 * single command meets (`COMMAND_BUSY`, a park, the venue hold, Blind) is met per unit here too.
 *
 * - **Lamp off and reset run together**: every unit is started at once and the run ends when the
 *   last hold does.
 * - **A strike is staggered** ([CommandRole.LAMP_ON]): one unit at a time, the next waiting the
 *   struck unit's own `strikeSpacingMs`. A unit whose lamp went off less than its restrike time ago
 *   ([readyAt]) is reported [Status.WAITING] with the time it can be struck, and struck then; units
 *   ready sooner go first. A unit the desk refuses consumes no spacing.
 *
 * [cancel] stops what has not started — a queued or waiting unit becomes [Status.CANCELLED] — and
 * leaves a hold in flight to finish, since a half-held command is worse than a finished one.
 */
class BulkCommandRun(
    val id: String,
    val role: CommandRole,
    val purpose: Purpose,
    units: List<BulkUnit>,
    /** How many of the units the request named have no command of [role]. */
    val withoutCommand: Int,
    val startedAtMs: Long,
    /** Wall clock, millis. */
    private val clock: () -> Long,
    /** When [fixtureKey]'s lamp can next be struck, or null — read afresh before every strike. */
    private val readyAt: (fixtureKey: String) -> Long?,
    private val start: (BulkUnit) -> Start,
    /** Called after every change of state, under no lock of this run's. */
    private val onChange: () -> Unit,
) {
    /** Why a run was started — what the banner and its end do with it. */
    enum class Purpose { BULK, MAKE_SAFE, UNDO }

    enum class Status { QUEUED, WAITING, RUNNING, DONE, CUT_SHORT, SKIPPED, CANCELLED }

    /** One unit's command, as the run needs it. */
    data class BulkUnit(
        val fixtureKey: String,
        val fixtureName: String,
        val command: String,
        val restrikeMs: Long = 0,
        val strikeSpacingMs: Long = 0,
        /** A lamp-off's: how long after it the fixture's mains can be cut. */
        val coolDownMs: Long = 0,
    )

    /** What starting one unit's command came to. */
    sealed interface Start {
        /** Held until [endsAtMs]; [completed] answers true when the hold ran its full time. */
        data class Started(val endsAtMs: Long, val completed: Deferred<Boolean>) : Start
        data class Refused(val code: String, val message: String) : Start
    }

    data class UnitState(
        val unit: BulkUnit,
        val status: Status,
        /** [Status.WAITING]: when it can be struck. [Status.RUNNING]: when its hold ends. */
        val untilMs: Long? = null,
        val code: String? = null,
        val message: String? = null,
    )

    private val lock = Any()
    private val states: Array<UnitState> = units.map { UnitState(it, Status.QUEUED) }.toTypedArray()
    private val cancelRequested = CompletableDeferred<Unit>()

    @Volatile var finished: Boolean = false
        private set

    val cancelled: Boolean get() = cancelRequested.isCompleted

    fun snapshot(): List<UnitState> = synchronized(lock) { states.toList() }

    fun cancel() {
        cancelRequested.complete(Unit)
    }

    /** Run it to the end: every unit started, refused or cancelled, and every hold ended. */
    suspend fun execute() {
        try {
            coroutineScope {
                if (role == CommandRole.LAMP_ON) staggered() else together()
            }
        } finally {
            finished = true
            onChange()
        }
    }

    private suspend fun together() = coroutineScope {
        for (i in states.indices) {
            if (cancelled) {
                set(i, Status.CANCELLED)
                continue
            }
            val hold = strike(i) ?: continue
            launch { settle(i, hold) }
        }
    }

    private suspend fun staggered() = coroutineScope {
        val pending = states.indices.toMutableList()
        var nextAllowed = clock()
        while (pending.isNotEmpty()) {
            if (cancelled) {
                pending.forEach { set(it, Status.CANCELLED) }
                break
            }
            val now = clock()
            for (i in pending) {
                val ready = readyOf(i)
                if (ready > now) set(i, Status.WAITING, untilMs = ready) else set(i, Status.QUEUED)
            }
            val slot = maxOf(now, nextAllowed)
            val next = pending.firstOrNull { readyOf(it) <= slot } ?: pending.minBy { readyOf(it) }
            if (!waitUntil(maxOf(nextAllowed, readyOf(next)))) continue
            // A lamp-off may have landed while this unit waited: its restrike starts again.
            if (readyOf(next) > clock()) continue
            pending.remove(next)
            val hold = strike(next) ?: continue
            nextAllowed = clock() + states[next].unit.strikeSpacingMs
            launch { settle(next, hold) }
        }
    }

    private fun readyOf(i: Int): Long = readyAt(states[i].unit.fixtureKey) ?: 0L

    /** Wait until [atMs]; false when [cancel] came first. */
    private suspend fun waitUntil(atMs: Long): Boolean {
        val wait = atMs - clock()
        if (wait <= 0) return !cancelled
        return withTimeoutOrNull(wait) { cancelRequested.await() } == null
    }

    private fun strike(i: Int): Deferred<Boolean>? =
        when (val result = start(states[i].unit)) {
            is Start.Started -> {
                set(i, Status.RUNNING, untilMs = result.endsAtMs)
                result.completed
            }
            is Start.Refused -> {
                set(i, Status.SKIPPED, code = result.code, message = result.message)
                null
            }
        }

    private suspend fun settle(i: Int, hold: Deferred<Boolean>) {
        set(i, if (hold.await()) Status.DONE else Status.CUT_SHORT)
    }

    private fun set(i: Int, status: Status, untilMs: Long? = null, code: String? = null, message: String? = null) {
        val changed = synchronized(lock) {
            val next = states[i].copy(status = status, untilMs = untilMs, code = code, message = message)
            if (next == states[i]) false else { states[i] = next; true }
        }
        if (changed) onChange()
    }
}
