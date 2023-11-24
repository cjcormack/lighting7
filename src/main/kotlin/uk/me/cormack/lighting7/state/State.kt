package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.Projects
import uk.me.cormack.lighting7.models.Scripts
import uk.me.cormack.lighting7.show.Show

class State(val config: ApplicationConfig) {
    val database = initDatabase()
    val show = initShow()

    private fun initDatabase(): Database {
        val url = config.property("postgres.url").getString()
        val username = config.property("postgres.username").getString()
        val password = config.property("postgres.password").getString()
        val database = Database.connect(
            HikariDataSource(HikariConfig().apply {
                driverClassName = "org.postgresql.Driver"
                jdbcUrl = url
                this@apply.username = username
                this@apply.password = password
                this@apply.maximumPoolSize = 8
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_REPEATABLE_READ"
                validate()
            })
        )

        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(Projects)
            SchemaUtils.createMissingTablesAndColumns(Scripts)
        }

        return database
    }

    private fun initShow(): Show {
        val runLoopEnabled = config.property("lighting.runLoop.enabled").getString().toBoolean()

        val runLoopScriptName = if (runLoopEnabled) {
            config.property("lighting.runLoop.scriptName").getString()
        } else {
            null
        }

        return Show(
            this,
            config.property("lighting.projectName").getString(),
            config.property("lighting.loadFixturesScriptName").getString(),
            config.property("lighting.initialSceneScriptName").getString(),
            runLoopScriptName,
            config.property("lighting.runLoop.delayMs").getString().toLong(),
        )
    }
}
