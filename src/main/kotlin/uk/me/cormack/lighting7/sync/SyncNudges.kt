package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.channels.Channel

/**
 * The hand-off from the cloud's `sync.changed` to [AutoSyncScheduler] (cloud-p2 plan D5): [offer] runs
 * on the channel's thread, never blocks and never touches the database; [take] waits on the
 * scheduler's coroutine for what was offered. Conflated per repo URL, so a flood of nudges for one repo
 * is one, and bounded: past [capacity] distinct URLs a nudge is dropped, which auto-sync's own timer
 * covers.
 */
class SyncNudges(private val capacity: Int = DEFAULT_CAPACITY) {
    private val pending = LinkedHashSet<String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Whether [url] is now waiting to be taken. */
    fun offer(url: String): Boolean {
        if (url.length > MAX_URL_CHARS) return false
        val waiting = synchronized(pending) {
            url in pending || (pending.size < capacity && pending.add(url))
        }
        if (waiting) wake.trySend(Unit)
        return waiting
    }

    /** Every URL offered since the last take, oldest first, once at least one has been. */
    suspend fun take(): List<String> {
        while (true) {
            wake.receive()
            val urls = synchronized(pending) { pending.toList().also { pending.clear() } }
            if (urls.isNotEmpty()) return urls
        }
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 64
        const val MAX_URL_CHARS: Int = 2048
    }
}
