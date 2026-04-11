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
import uk.me.cormack.lighting7.show.Show

private val logger = LoggerFactory.getLogger("State")

class State(val config: ApplicationConfig) {
    val database = initDatabase()
    val projectManager = ProjectManager(config, database) { this }

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
                DaoProjects, DaoScripts, DaoFxPresets, DaoCueStacks, DaoCues,
                DaoCuePresetApplications, DaoCueAdHocEffects, DaoCueTriggers,
                DaoAiConversations, DaoCueSlots,
                DaoUniverseConfigs, DaoFixturePatches, DaoFixtureGroups, DaoFixtureGroupMembers,
                DaoParkedChannels, DaoFxDefinitions,
            )

            // Migration: drop old unique index on (project_id, name) since we now use (project_id, fixture_type, name)
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")

            // Migration: convert APPLY_PRESET triggers to preset applications with timing
            migrateApplyPresetTriggers()

            // Migration: collapse DELAYED/RECURRING trigger types into ACTIVATION with timing fields
            migrateTriggerTypes()

            // Migration: built-in FX definitions now come from bundled .fx.kts resource files,
            // so the is_builtin column is no longer needed and project_id should be non-nullable.
            migrateFxDefinitionsDropBuiltin()

            // Migration: drop script-based configuration mode columns from projects table
            migrateDropScriptBasedMode()

            // Migration: drop scenes table and related columns (superseded by FX engine)
            migrateDropScenesAndChases()

            // Migration: drop run loop columns from projects (superseded by FX cue system)
            migrateDropRunLoop()

            // Migration: drop track changed script column from projects (music sync removed)
            migrateDropTrackChangedScript()
        }

        return database
    }

}

/**
 * One-time migration: convert APPLY_PRESET triggers into preset applications with timing fields,
 * then remove the migrated trigger rows and drop the now-unused columns.
 *
 * Safe to run repeatedly — checks for the old columns before doing anything.
 */
private fun Transaction.migrateApplyPresetTriggers() {
    // Check if the old action_type column still exists via information_schema
    var hasActionType = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'cue_triggers' AND column_name = 'action_type'"""
    ) { rs ->
        hasActionType = rs.next()
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
        if (row.presetId == null) {
            logger.warn("Skipping APPLY_PRESET trigger id=${row.id} for cue=${row.cueId}: presetId is NULL")
            continue
        }
        if (row.triggerType == "DEACTIVATION") {
            logger.warn("Dropping APPLY_PRESET trigger id=${row.id} for cue=${row.cueId}: DEACTIVATION type is paradoxical for effects")
            continue
        }

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

        // Escape single quotes in JSON to prevent SQL breakage
        val targetsJson = (row.targets ?: "[]").replace("'", "''")

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

/**
 * One-time migration: collapse DELAYED and RECURRING trigger types into ACTIVATION.
 * The timing fields (delayMs, intervalMs, randomWindowMs) are already populated,
 * so we just need to update the type enum value.
 *
 * Safe to run repeatedly — only updates rows that still have the old type values.
 */
private fun Transaction.migrateTriggerTypes() {
    exec("UPDATE cue_triggers SET trigger_type = 'ACTIVATION' WHERE trigger_type IN ('DELAYED', 'RECURRING')")
}

/**
 * One-time migration: remove the is_builtin column from fx_definitions and make project_id non-nullable.
 *
 * Built-in effects are now loaded from bundled .fx.kts resource files at startup,
 * so the database table only stores user-created definitions which always have a project.
 *
 * Safe to run repeatedly — checks for the column before doing anything.
 */
private fun Transaction.migrateFxDefinitionsDropBuiltin() {
    var hasIsBuiltin = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'fx_definitions' AND column_name = 'is_builtin'"""
    ) { rs ->
        hasIsBuiltin = rs.next()
    }

    if (!hasIsBuiltin) return // already migrated

    val logger = LoggerFactory.getLogger("State")
    logger.info("Migrating fx_definitions: removing is_builtin column and orphaned rows...")

    // Delete any rows without a project (legacy built-in definitions stored in DB)
    exec("DELETE FROM fx_definitions WHERE project_id IS NULL")

    // Make project_id non-nullable
    exec("ALTER TABLE fx_definitions ALTER COLUMN project_id SET NOT NULL")

    // Drop the is_builtin column
    exec("ALTER TABLE fx_definitions DROP COLUMN is_builtin")

    logger.info("fx_definitions migration complete")
}

/**
 * One-time migration: drop the script-based configuration mode columns from the projects table.
 *
 * All projects now use DB-based fixture configuration exclusively. The `mode` and
 * `load_fixtures_script_id` columns are no longer referenced by the ORM.
 *
 * Safe to run repeatedly — uses DROP COLUMN IF EXISTS.
 */
private fun Transaction.migrateDropScriptBasedMode() {
    var hasMode = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'projects' AND column_name = 'mode'"""
    ) { rs ->
        hasMode = rs.next()
    }

    if (!hasMode) return // already migrated

    logger.info("Migrating projects: dropping script-based configuration mode columns...")

    exec("ALTER TABLE projects DROP COLUMN IF EXISTS mode")
    exec("ALTER TABLE projects DROP COLUMN IF EXISTS load_fixtures_script_id")

    logger.info("Script-based configuration mode migration complete")
}

/**
 * One-time migration: drop scenes table and related columns from projects and scripts.
 *
 * Scenes and chases have been fully superseded by the FX engine.
 * ScriptSettings (stored on scripts.settings) was the per-scene parameter override mechanism,
 * also no longer needed.
 *
 * Safe to run repeatedly — uses IF EXISTS guards.
 */
private fun Transaction.migrateDropScenesAndChases() {
    var hasScenesTable = false
    exec(
        """SELECT 1 FROM information_schema.tables
           WHERE table_name = 'scenes'"""
    ) { rs ->
        hasScenesTable = rs.next()
    }

    if (!hasScenesTable) return // already migrated

    logger.info("Migrating: dropping scenes table and related columns...")

    // Clear FK reference from projects before dropping the table
    exec("UPDATE projects SET initial_scene_id = NULL")

    // Drop scenes table (CASCADE handles FK constraints from scenes to scripts/projects)
    exec("DROP TABLE IF EXISTS scenes CASCADE")

    // Drop the initial_scene_id column from projects
    exec("ALTER TABLE projects DROP COLUMN IF EXISTS initial_scene_id")

    // Drop the settings column from scripts (ScriptSettings mechanism)
    exec("ALTER TABLE scripts DROP COLUMN IF EXISTS settings")

    logger.info("Scenes, chases, and script settings migration complete")
}

/**
 * One-time migration: drop run loop columns from projects table.
 *
 * The run loop mechanism has been superseded by the FX cue system.
 *
 * Safe to run repeatedly — uses IF EXISTS guards.
 */
private fun Transaction.migrateDropRunLoop() {
    var hasRunLoopColumn = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'projects' AND column_name = 'run_loop_script_id'"""
    ) { rs ->
        hasRunLoopColumn = rs.next()
    }

    if (!hasRunLoopColumn) return // already migrated

    logger.info("Migrating: dropping run loop columns from projects...")

    exec("ALTER TABLE projects DROP COLUMN IF EXISTS run_loop_script_id")
    exec("ALTER TABLE projects DROP COLUMN IF EXISTS run_loop_delay_ms")

    logger.info("Run loop migration complete")
}

/**
 * One-time migration: drop track_changed_script_id column from projects table.
 *
 * Music track synchronization has been removed.
 *
 * Safe to run repeatedly — uses IF EXISTS guard.
 */
private fun Transaction.migrateDropTrackChangedScript() {
    var hasColumn = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'projects' AND column_name = 'track_changed_script_id'"""
    ) { rs ->
        hasColumn = rs.next()
    }

    if (!hasColumn) return // already migrated

    logger.info("Migrating: dropping track_changed_script_id from projects...")

    exec("ALTER TABLE projects DROP COLUMN IF EXISTS track_changed_script_id")

    logger.info("Track changed script migration complete")
}
