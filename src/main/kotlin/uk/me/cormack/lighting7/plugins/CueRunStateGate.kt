package uk.me.cormack.lighting7.plugins

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uk.me.cormack.lighting7.fx.CueRunState

/**
 * One socket's `cueRunStateChanged` frames, kept in [CueRunState.seq] order per stack: each
 * frame is its own launch, and the connect snapshot can describe a GO the listener also heard.
 *
 * A change is sent unless a later frame for its stack already went. A snapshot is sent only if
 * newer than anything sent; a change that ties a sent snapshot still goes, for its `transition`.
 */
internal class CueRunStateGate(private val send: suspend (OutMessage) -> Unit) {
    private val mutex = Mutex()
    private val lastSent = HashMap<Int, Long>()

    suspend fun change(runState: CueRunState) = offer(runState, snapshot = false)

    suspend fun snapshot(runState: CueRunState) = offer(runState, snapshot = true)

    private suspend fun offer(runState: CueRunState, snapshot: Boolean) = mutex.withLock {
        val last = lastSent[runState.stackId]
        if (last != null && (runState.seq < last || (snapshot && runState.seq == last))) return@withLock
        send(CueRunStateChangedOutMessage.of(runState))
        lastSent[runState.stackId] = runState.seq
    }
}
