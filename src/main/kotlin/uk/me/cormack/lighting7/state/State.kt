package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.ai.AiService
import uk.me.cormack.lighting7.fx.CueTriggerManager
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.music.Music
import uk.me.cormack.lighting7.show.Show

private val logger = LoggerFactory.getLogger("State")

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

    /** Manages cue trigger lifecycle (lazy init after show is available) */
    val cueTriggerManager: CueTriggerManager by lazy {
        CueTriggerManager(show.fxEngine, this)
    }

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
            SchemaUtils.createMissingTablesAndColumns(
                DaoProjects, DaoScripts, DaoScenes, DaoFxPresets, DaoCueStacks, DaoCues,
                DaoCuePresetApplications, DaoCueAdHocEffects, DaoCueTriggers,
                DaoAiConversations, DaoCueSlots,
                DaoUniverseConfigs, DaoFixturePatches, DaoFixtureGroups, DaoFixtureGroupMembers,
                DaoParkedChannels, DaoFxDefinitions,
            )

            // Migration: drop old unique index on (project_id, name) since we now use (project_id, fixture_type, name)
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")

            // Migration: convert APPLY_PRESET triggers to preset applications with timing
            migrateApplyPresetTriggers()
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

/**
 * One-time migration: convert APPLY_PRESET triggers into preset applications with timing fields,
 * then remove the migrated trigger rows and drop the now-unused columns.
 *
 * Safe to run repeatedly — checks for the old columns before doing anything.
 */
private fun Transaction.migrateApplyPresetTriggers() {
    // Check if the old action_type column still exists
    val hasActionType = try {
        exec("SELECT action_type FROM cue_triggers LIMIT 0")
        true
    } catch (_: Exception) {
        false
    }

    if (!hasActionType) return // already migrated

    logger.info("Migrating APPLY_PRESET triggers to preset applications with timing...")

    // Find all APPLY_PRESET triggers
    data class TriggerRow(
        val id: Int, val cueId: Int, val triggerType: String,
        val delayMs: Long?, val intervalMs: Long?, val randomWindowMs: Long?,
        val presetId: Int?, val targets: String?,
    )

    val rows = mutableListOf<TriggerRow>()
    exec(
        """SELECT id, cue_id, trigger_type, delay_ms, interval_ms, random_window_ms, preset_id, targets
           FROM cue_triggers WHERE action_type = 'APPLY_PRESET'"""
    ) { rs ->
        while (rs.next()) {
            rows.add(TriggerRow(
                id = rs.getInt("id"),
                cueId = rs.getInt("cue_id"),
                triggerType = rs.getString("trigger_type"),
                delayMs = rs.getLong("delay_ms").let { if (rs.wasNull()) null else it },
                intervalMs = rs.getLong("interval_ms").let { if (rs.wasNull()) null else it },
                randomWindowMs = rs.getLong("random_window_ms").let { if (rs.wasNull()) null else it },
                presetId = rs.getInt("preset_id").let { if (rs.wasNull()) null else it },
                targets = rs.getString("targets"),
            ))
        }
    }

    for (row in rows) {
        if (row.presetId == null) continue // invalid, skip
        if (row.triggerType == "DEACTIVATION") continue // drop — paradoxical for effects

        // Map trigger timing to preset application timing fields
        val delayMs: Long? = when (row.triggerType) {
            "DELAYED" -> row.delayMs
            else -> null
        }
        val intervalMs: Long? = when (row.triggerType) {
            "RECURRING" -> row.intervalMs
            else -> null
        }
        val randomWindowMs: Long? = when (row.triggerType) {
            "RECURRING" -> row.randomWindowMs
            else -> null
        }

        val targetsJson = row.targets ?: "[]"

        exec(
            """INSERT INTO cue_preset_applications (cue_id, preset_id, targets, delay_ms, interval_ms, random_window_ms, sort_order)
               VALUES (${row.cueId}, ${row.presetId}, '$targetsJson', ${delayMs ?: "NULL"}, ${intervalMs ?: "NULL"}, ${randomWindowMs ?: "NULL"}, 0)"""
        )
    }

    // Delete all APPLY_PRESET trigger rows
    if (rows.isNotEmpty()) {
        val ids = rows.joinToString(",") { it.id.toString() }
        exec("DELETE FROM cue_triggers WHERE id IN ($ids)")
        logger.info("Migrated ${rows.size} APPLY_PRESET triggers to preset applications")
    }

    // Drop the now-unused columns
    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS action_type")
    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS preset_id")
    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS targets")

    // Make script_id required (convert nullable to non-nullable) — delete any orphaned triggers without scripts
    exec("DELETE FROM cue_triggers WHERE script_id IS NULL")

    logger.info("APPLY_PRESET trigger migration complete")
}
