package uk.me.cormack.lighting7.history

import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.Database
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.sync.SyncDispositions
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap

/**
 * The journal's capture (undo plan §3.4), registered beside `DbTracing.StatementSpans` through
 * `META-INF/services`. Every write statement adds its tables to its transaction; before the commit the
 * folders those tables' records live in are exported again, inside the transaction, for every project
 * the [HistoryService] journals, and diffed against its cache; after the commit the diff becomes an
 * entry under the thread's [WriteOrigin]. A rollback drops it.
 *
 * Global because Exposed's interceptors are, so each [Database] registers the history it feeds. A
 * failure here never fails the write: the project's history is cleared instead, since its cache can no
 * longer be trusted.
 */
class JournalInterceptor : GlobalStatementInterceptor {

    override fun beforeExecution(transaction: Transaction, context: StatementContext) {
        if (transaction.getUserData(EXPORTING) == true) return
        if (context.statement.type !in WRITES) return
        if (services[transaction.db] == null) return
        val touched = transaction.getUserData(TOUCHED) ?: mutableSetOf<Table>().also { transaction.putUserData(TOUCHED, it) }
        touched.addAll(context.statement.targets)
    }

    override fun beforeCommit(transaction: Transaction) {
        val service = services[transaction.db] ?: return
        if (transaction.getUserData(EXPORTING) == true) return
        // Pending entity writes reach the database now, not in the DAO's own hook, which may run after
        // this one. A failed flush must fail the commit: the DAO drops a table's pending inserts before
        // running them, so its own later flush would find nothing and commit without the rows.
        transaction.flushCache()
        @Suppress("UNCHECKED_CAST") // TOUCHED's own value, put by [beforeExecution].
        val touched = transaction.removeUserData(TOUCHED) as? Set<Table> ?: return
        if (touched.isEmpty() || !service.isJournaling) return
        val folders = foldersOf(touched)
        if (folders.isEmpty()) return
        transaction.putUserData(EXPORTING, true)
        try {
            service.stage(folders, WriteOrigin.current())?.let { staged.put(transaction, Pending(service, it)) }
        } catch (e: Exception) {
            log.warn("History could not read the records a commit changed; clearing the history it would have joined", e)
            service.journaledProjects().forEach { service.clear(it, HistoryService.BACKGROUND_REASON) }
        } finally {
            transaction.removeUserData(EXPORTING)
        }
    }

    override fun afterCommit(transaction: Transaction) {
        val pending = staged.remove(transaction) ?: return
        try {
            pending.service.commit(pending.staged)
        } catch (e: Exception) {
            log.warn("History could not record a commit", e)
        }
    }

    override fun afterRollback(transaction: Transaction) {
        staged.remove(transaction)
        transaction.removeUserData(TOUCHED)
    }

    private class Pending(val service: HistoryService, val staged: HistoryService.Staged)

    companion object {
        private val log = LoggerFactory.getLogger(JournalInterceptor::class.java)

        private val TOUCHED = Key<MutableSet<Table>>()
        private val EXPORTING = Key<Boolean>()
        private val WRITES = setOf(StatementType.INSERT, StatementType.UPDATE, StatementType.DELETE, StatementType.MERGE)

        private val services = ConcurrentHashMap<Any, HistoryService>()

        /** Commits staged and not yet committed, by transaction: one at a time on the pool's one connection. */
        private val staged: MutableMap<Transaction, Pending> = Collections.synchronizedMap(IdentityHashMap())

        /** Journal [database]'s commits into [service]. */
        fun register(database: Database, service: HistoryService) {
            services[database] = service
        }

        fun unregister(database: Database) {
            services.remove(database)
        }

        /**
         * The record folders a write to [tables] can change. Every row a write changes is its own
         * statement's: the desk runs with SQLite's foreign keys off, so no delete cascades.
         */
        internal fun foldersOf(tables: Set<Table>): Set<String> =
            tables.mapNotNullTo(mutableSetOf()) { SyncDispositions.folderOf(it) }
    }
}
