package uk.me.cormack.lighting7.state

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** How a quit's wait for its backup ended. */
internal enum class QuitWaitEnd { ENDED, SKIPPED, CAPPED }

/** Wait for [backup] to end, [skip] to complete or [cap] to pass, whichever is first. */
internal suspend fun awaitQuitBackup(backup: Job, skip: Deferred<Unit>, cap: Duration): QuitWaitEnd =
    withTimeoutOrNull(cap) {
        select {
            backup.onJoin { QuitWaitEnd.ENDED }
            skip.onAwait { QuitWaitEnd.SKIPPED }
        }
    } ?: QuitWaitEnd.CAPPED

/** A quit begun: whether it waits for a backup, and [proceed], which starts the wait and the exit. */
internal class QuitStart(val backingUp: Boolean, private val go: () -> Unit) {
    /** Call once the answer has been sent: the grace before the exit is counted from here. */
    fun proceed() = go()
}

/**
 * *Quit desk*'s sequence: start the backup it waits for (if any), then, on [QuitStart.proceed], wait
 * for it at most [backupCap] and, once the answer has had time to reach the browser, [exit]. Only the
 * first call quits.
 */
internal class DeskQuit(
    private val startBackup: () -> Job?,
    private val exit: () -> Unit,
    private val backupCap: Duration = QUIT_BACKUP_CAP,
    /** Long enough after a backup that a window polling each second sees how it ended. */
    private val graceAfterBackup: Duration = 2500.milliseconds,
    private val grace: Duration = 500.milliseconds,
) {
    private val logger = LoggerFactory.getLogger(DeskQuit::class.java)
    private val skip = CompletableDeferred<Unit>()
    private val proceeded = AtomicBoolean(false)
    private var quitting = false
    private var backup: Job? = null

    /** A second call starts nothing, and only ends the wait for the backup when [skipBackup]. */
    fun quit(skipBackup: Boolean): QuitStart {
        if (skipBackup) skip.complete(Unit)
        val started: Job?
        synchronized(this) {
            if (quitting) return QuitStart(!skipBackup && backup?.isActive == true) {}
            quitting = true
            started = if (skipBackup) null else runCatching { startBackup() }
                .onFailure { logger.warn("Quit desk: couldn't start the backup; quitting without one", it) }
                .getOrNull()
            backup = started
        }
        return QuitStart(started != null) {
            if (proceeded.compareAndSet(false, true)) exitAfter(started)
        }
    }

    private fun exitAfter(started: Job?) {
        Thread({
            val waited = started?.let {
                logger.info("Quit desk: backing up first")
                runBlocking { awaitQuitBackup(it, skip, backupCap) }.also { end ->
                    if (end == QuitWaitEnd.CAPPED) logger.warn("Quit desk: the backup ran past {}; quitting, the rest stays staged", backupCap)
                }
            }
            Thread.sleep((if (waited != null && waited != QuitWaitEnd.SKIPPED) graceAfterBackup else grace).inWholeMilliseconds)
            exit()
        }, "quit-desk").start()
    }

    companion object {
        val QUIT_BACKUP_CAP: Duration = 15.minutes
    }
}
