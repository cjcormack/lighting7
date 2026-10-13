package uk.me.cormack.lighting7.perf

import com.zaxxer.hikari.metrics.IMetricsTracker
import com.zaxxer.hikari.metrics.MetricsTrackerFactory
import com.zaxxer.hikari.metrics.PoolStats

/** Hikari's wait for a connection into [DeskMetrics.dbConnectionWait]: with one connection, the queue for it. */
class DbConnectionMetrics(private val metrics: DeskMetrics = DeskMetrics.global) : MetricsTrackerFactory {
    override fun create(poolName: String, poolStats: PoolStats): IMetricsTracker = object : IMetricsTracker {
        override fun recordConnectionAcquiredNanos(elapsedAcquiredNanos: Long) =
            metrics.dbConnectionWait.record(elapsedAcquiredNanos)
    }
}
