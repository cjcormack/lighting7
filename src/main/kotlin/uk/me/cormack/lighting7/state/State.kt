package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.ai.AiService
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.music.Music
import uk.me.cormack.lighting7.show.Show

class State(val config: ApplicationConfig) {
    val database = initDatabase()
    val projectManager = ProjectManager(config, database) { this }
    val music = initMusic()

    /**
     * AI service for Claude-powered lighting control.
     * Null if no ANTHROPIC_API_KEY is configured (feature is optional).
     */
    val aiService: AiService? by lazy {
        val apiKey = config.propertyOrNull("anthropic.apiKey")?.getString()
        if (apiKey.isNullOrBlank()) null
        else AiService(this, config)
    }

    /**
     * Delegate show access through ProjectManager.
     * The show is only available after initializeShow() is called.
     */
    val show: Show get() = projectManager.show

    /**
     * Initialize the show through the project manager.
     * This finds (or migrates) the current project from the database and creates the Show.
     * Must be called explicitly after State construction.
     */
    fun initializeShow(): Show = projectManager.initialize()

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
            // Create tables - order matters for FK constraints
            // DaoProjects now includes all columns (FK columns as plain integers)
            // Note: createMissingTablesAndColumns is deprecated in favor of migration tools,
            // but is acceptable for this development/personal project setup
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(DaoProjects, DaoScripts, DaoScenes, DaoFxPresets, DaoAiConversations)

            // Migration: drop old unique index on (project_id, name) since we now use (project_id, fixture_type, name)
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")
        }

        return database
    }

    private fun initMusic(): Music {
        return Music(
            this,
            config.property("music.issuer").getString(),
            config.property("music.keyId").getString(),
            config.property("music.secret").getString().trimIndent(),
        )
    }
}
