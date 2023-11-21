package uk.me.cormack.lighting7.dao

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import kotlinx.coroutines.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.Projects
import uk.me.cormack.lighting7.models.Scripts

object DatabaseFactory {
    fun init(config: ApplicationConfig) {
        val url = config.property("postgres.url").getString()
        val username = config.property("postgres.username").getString()
        val password = config.property("postgres.password").getString()

        val database = Database.connect(createHikariDataSource(url, driver = "org.postgresql.Driver", username = username, password = password))

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(Projects)
            SchemaUtils.createMissingTablesAndColumns(Scripts)
        }
    }

    private fun createHikariDataSource(
        url: String,
        driver: String,
        username: String? = null,
        password: String? = null,
        maxPoolSize: Int = 8,
    ) = HikariDataSource(HikariConfig().apply {
        driverClassName = driver
        jdbcUrl = url
        this@apply.username = username
        this@apply.password = password
        this@apply.maximumPoolSize = maxPoolSize
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    })

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
