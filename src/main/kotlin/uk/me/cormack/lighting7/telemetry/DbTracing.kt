package uk.me.cormack.lighting7.telemetry

import io.sentry.ISpan
import io.sentry.SpanStatus
import org.jetbrains.exposed.v1.core.Key
import org.jetbrains.exposed.v1.core.Transaction
import org.jetbrains.exposed.v1.core.statements.GlobalStatementInterceptor
import org.jetbrains.exposed.v1.core.statements.Statement
import org.jetbrains.exposed.v1.core.statements.StatementContext
import org.jetbrains.exposed.v1.core.statements.api.PreparedStatementApi
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.statements.jdbc.JdbcPreparedStatementImpl
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * Database spans (Sentry plan D5), built from Exposed rather than a JDBC spy. Each connection the
 * desk takes while a span is active opens a `db.sql.transaction` span, whose first child is
 * `db.connection.acquire` — the wait for the pool's one connection — and which ends when Exposed
 * gives the connection back; [StatementSpans] hangs a `db.sql.query` span per statement under it.
 */
object DbTracing {
    const val OP_TRANSACTION = "db.sql.transaction"
    const val OP_ACQUIRE = "db.connection.acquire"
    const val OP_QUERY = "db.sql.query"
    private const val DB_SYSTEM = "sqlite"

    /** [dataSource], with every connection taken inside a traced span traced. */
    fun dataSource(dataSource: DataSource): DataSource = TracedDataSource(dataSource)

    /** The `db.sql.transaction` span the connection behind [transaction] belongs to, if it is traced. */
    internal fun transactionSpanOf(transaction: Transaction): ISpan? {
        val connection = (transaction as? JdbcTransaction)?.connection?.connection ?: return null
        if (!Proxy.isProxyClass(connection.javaClass)) return null
        return (Proxy.getInvocationHandler(connection) as? TracedConnection)?.span
    }

    private class TracedDataSource(private val delegate: DataSource) : DataSource by delegate {
        override fun getConnection(): Connection = traced { delegate.connection }

        override fun getConnection(username: String?, password: String?): Connection =
            traced { delegate.getConnection(username, password) }

        private fun traced(acquire: () -> Connection): Connection {
            val parent = Tracing.activeSpan()
            if (parent == null || parent.isNoOp || parent.isFinished || parent.isSampled != true) return acquire()
            val span = parent.startChild(OP_TRANSACTION, "transaction")
            span.setData("db.system", DB_SYSTEM)
            val connection = try {
                Tracing.childOf(span, OP_ACQUIRE, "pool") { acquire() }
            } catch (e: Throwable) {
                span.throwable = e
                span.finish(SpanStatus.INTERNAL_ERROR)
                throw e
            }
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
                TracedConnection(connection, span),
            ) as Connection
        }
    }

    /** Delegates everything; ends [span] when the connection is given back. */
    private class TracedConnection(private val delegate: Connection, val span: ISpan) : InvocationHandler {
        override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
            if (method.name == "rollback" && method.parameterCount == 0) span.setData("db.rollback", true)
            val result = try {
                method.invoke(delegate, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            if (method.name == "close" && method.parameterCount == 0 && !span.isFinished) span.finish(SpanStatus.OK)
            return result
        }
    }

    private class OpenStatement(val statement: Statement<*>, val span: ISpan)

    private val OPEN = Key<OpenStatement>()

    /**
     * Registered once, through `META-INF/services`. A `db.sql.query` span per statement, its
     * description the SQL with `?` for every parameter, and the rows a write changed. A query's
     * rows are read after its span ends, so they are not counted.
     */
    class StatementSpans : GlobalStatementInterceptor {
        override fun beforeExecution(transaction: Transaction, context: StatementContext) {
            if (Tracing.activeSpan() == null) return
            val parent = transactionSpanOf(transaction) ?: return
            transaction.getUserData(OPEN)?.let { open ->
                // A batch runs one statement over several contexts.
                if (open.statement === context.statement) return
                open.span.finish(SpanStatus.UNKNOWN_ERROR)
            }
            val span = parent.startChild(OP_QUERY, description(transaction, context))
            span.setData("db.system", DB_SYSTEM)
            transaction.putUserData(OPEN, OpenStatement(context.statement, span))
        }

        override fun afterExecution(
            transaction: Transaction,
            contexts: List<StatementContext>,
            executedStatement: PreparedStatementApi,
        ) {
            val open = transaction.removeUserData(OPEN) as? OpenStatement ?: return
            val changed = runCatching { (executedStatement as? JdbcPreparedStatementImpl)?.statement?.updateCount }.getOrNull()
            if (changed != null && changed >= 0) open.span.setData("db.rows_affected", changed)
            if (contexts.size > 1) open.span.setData("db.batch_size", contexts.size)
            open.span.finish(SpanStatus.OK)
        }

        override fun afterCommit(transaction: Transaction) = abandon(transaction)

        override fun afterRollback(transaction: Transaction) = abandon(transaction)

        /** A statement that threw never reaches [afterExecution]. */
        private fun abandon(transaction: Transaction) {
            (transaction.removeUserData(OPEN) as? OpenStatement)?.span?.finish(SpanStatus.INTERNAL_ERROR)
        }
    }

    internal fun description(transaction: Transaction, context: StatementContext): String =
        TokenScrubber.scrub(context.sql(transaction))
}
