package uk.me.cormack.lighting7.testsupport

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

/** An executor that holds every task until [runAll]: the test decides when a background job runs. */
class HeldExecutor : AbstractExecutorService() {
    val queued = ArrayDeque<Runnable>()

    @Synchronized
    override fun execute(command: Runnable) {
        queued += command
    }

    fun runAll() {
        while (true) (synchronized(this) { queued.removeFirstOrNull() } ?: return).run()
    }

    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
