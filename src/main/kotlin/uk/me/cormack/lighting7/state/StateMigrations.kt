package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoRigging
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.models.LegacyStaticEffectMigration
import uk.me.cormack.lighting7.sync.Overrides
import java.util.UUID

private val logger = LoggerFactory.getLogger("StateMigrations")

/**
 * Runs the historical schema-evolution migrations and the singleton install bootstrap.
 *
 * The PostgreSQL-only migrations rely on `information_schema` and ALTER TABLE syntax
 * that SQLite does not accept; SQLite installs are fresh-start so the latest schema
 * produced by `createMissingTablesAndColumns` already matches the post-migration shape.
 */
internal fun Transaction.runStateMigrations(database: Database) {
    if (database.dialect is PostgreSQLDialect) {
        migrateDropShowSessions()
        migrateProjectActiveEntryFk()
        migrateApplyPresetTriggers()
        migrateTriggerTypes()
        migrateFxDefinitionsDropBuiltin()
        migrateFxPresetsFixtureTypeNotNull()
        migrateDropScriptBasedMode()
        migrateDropScenesAndChases()
        migrateDropRunLoop()
        migrateDropTrackChangedScript()
        migrateRiggingsV3()
        migrateDropSyncEnabled()

        val summary = LegacyStaticEffectMigration.run(this)
        if (summary.converted > 0 || summary.skipped > 0) {
            logger.info(
                "LegacyStaticEffectMigration: converted {} row(s), skipped {} row(s)",
                summary.converted, summary.skipped,
            )
        }
    }

    ensureInstallRow()
    migrateUniverseAddressesToOverrides()
    migrateCollapseShowIntoStacks(database)
}

/**
 * Bootstraps the singleton install identity. On first launch creates one row with `friendlyName`
 * defaulting to the system hostname (or "lighting7" if hostname lookup fails). Idempotent.
 */
private fun Transaction.ensureInstallRow() {
    if (DaoInstall.all().firstOrNull() != null) return
    val hostname = runCatching { java.net.InetAddress.getLocalHost().hostName }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "lighting7"
    DaoInstall.new {
        friendlyName = hostname
        createdAtMs = System.currentTimeMillis()
    }
    logger.info("Created install identity row with friendlyName='{}'", hostname)
}

/**
 * One-time migration: move per-install controller IPs out of `universe_configs.address` into
 * `machine_overrides`. The legacy column stays in the schema (SQLite drop is awkward) but is
 * never written after this — the source of truth is `sync/Overrides.kt`. Idempotent: only acts
 * on rows whose `address` is non-null. `setUniverseAddress` upserts so a stale partial-migration
 * row gets overwritten cleanly.
 */
private fun Transaction.migrateUniverseAddressesToOverrides() {
    val toMigrate = DaoUniverseConfig.find { DaoUniverseConfigs.address.isNotNull() }.toList()
    if (toMigrate.isEmpty()) return
    var migrated = 0
    for (config in toMigrate) {
        val ip = config.address ?: continue
        Overrides.setUniverseAddress(config.project.id.value, config.uuid, ip)
        config.address = null
        migrated++
    }
    logger.info("Migrated {} universe_configs.address row(s) into machine_overrides", migrated)
}

/**
 * One-time migration: convert APPLY_PRESET triggers into preset applications with timing fields,
 * then remove the migrated trigger rows and drop the now-unused columns.
 *
 * Safe to run repeatedly — checks for the old columns before doing anything.
 */
private fun Transaction.migrateApplyPresetTriggers() {
    var hasActionType = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'cue_triggers' AND column_name = 'action_type'"""
    ) { rs ->
        hasActionType = rs.next()
    }

    if (!hasActionType) return

    logger.info("Migrating APPLY_PRESET triggers to preset applications with timing...")

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

        val targetsJson = (row.targets ?: "[]").replace("'", "''")

        exec(
            """INSERT INTO cue_preset_applications (cue_id, preset_id, targets, delay_ms, interval_ms, random_window_ms, sort_order)
               VALUES (${row.cueId}, ${row.presetId}, '$targetsJson', ${delayMs ?: "NULL"}, ${intervalMs ?: "NULL"}, ${randomWindowMs ?: "NULL"}, 0)"""
        )
    }

    if (rows.isNotEmpty()) {
        val ids = rows.joinToString(",") { it.id.toString() }
        exec("DELETE FROM cue_triggers WHERE id IN ($ids)")
        logger.info("Migrated ${rows.size} APPLY_PRESET triggers to preset applications")
    }

    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS action_type")
    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS preset_id")
    exec("ALTER TABLE cue_triggers DROP COLUMN IF EXISTS targets")

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

    if (!hasIsBuiltin) return

    logger.info("Migrating fx_definitions: removing is_builtin column and orphaned rows...")

    exec("DELETE FROM fx_definitions WHERE project_id IS NULL")
    exec("ALTER TABLE fx_definitions ALTER COLUMN project_id SET NOT NULL")
    exec("ALTER TABLE fx_definitions DROP COLUMN is_builtin")

    logger.info("fx_definitions migration complete")
}

/**
 * One-time migration: tighten fx_presets.fixture_type to NOT NULL.
 *
 * Any legacy NULL-type row predates Phase 3's non-blank-on-write validation and is unusable;
 * the preceding DELETEs drop those orphans (and their children) so the ALTER succeeds.
 *
 * Safe to run repeatedly — checks the column's nullability before doing anything.
 */
private fun Transaction.migrateFxPresetsFixtureTypeNotNull() {
    var isNullable = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'fx_presets' AND column_name = 'fixture_type'
             AND is_nullable = 'YES'"""
    ) { rs ->
        isNullable = rs.next()
    }
    if (!isNullable) return

    logger.info("Migrating fx_presets: dropping orphan NULL-type rows and tightening fixture_type to NOT NULL...")

    exec(
        """DELETE FROM fx_preset_property_assignments
           WHERE preset_id IN (SELECT id FROM fx_presets WHERE fixture_type IS NULL)"""
    )
    exec(
        """DELETE FROM cue_preset_applications
           WHERE preset_id IN (SELECT id FROM fx_presets WHERE fixture_type IS NULL)"""
    )
    exec("DELETE FROM fx_presets WHERE fixture_type IS NULL")
    exec("ALTER TABLE fx_presets ALTER COLUMN fixture_type SET NOT NULL")

    logger.info("fx_presets.fixture_type migration complete")
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

    if (!hasMode) return

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

    if (!hasScenesTable) return

    logger.info("Migrating: dropping scenes table and related columns...")

    exec("UPDATE projects SET initial_scene_id = NULL")
    exec("DROP TABLE IF EXISTS scenes CASCADE")
    exec("ALTER TABLE projects DROP COLUMN IF EXISTS initial_scene_id")
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

    if (!hasRunLoopColumn) return

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

    if (!hasColumn) return

    logger.info("Migrating: dropping track_changed_script_id from projects...")

    exec("ALTER TABLE projects DROP COLUMN IF EXISTS track_changed_script_id")

    logger.info("Track changed script migration complete")
}

/**
 * One-time migration for formatVersion 3 (riggings + Z-up coordinate system).
 *
 * Promotes the legacy `rigging_position` string on fixture_patches into first-class
 * Rigging rows, swaps stage_y ↔ stage_z so existing v2 data lives in the new Z-up
 * frame, then drops the rigging_position column.
 *
 * Safe to run repeatedly — gated on `rigging_position` column existence. Once the
 * column is gone, the migration is a no-op.
 */
private fun Transaction.migrateRiggingsV3() {
    var hasColumn = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'fixture_patches' AND column_name = 'rigging_position'"""
    ) { rs ->
        hasColumn = rs.next()
    }
    if (!hasColumn) return

    logger.info("Migrating to v3: promoting rigging_position strings → Riggings, swapping stage_y ↔ stage_z...")

    val distinctPairs = mutableListOf<Pair<Int, String>>()
    exec(
        """SELECT DISTINCT project_id, rigging_position
           FROM fixture_patches
           WHERE rigging_position IS NOT NULL"""
    ) { rs ->
        while (rs.next()) {
            distinctPairs.add(rs.getInt("project_id") to rs.getString("rigging_position"))
        }
    }

    var linked = 0
    for ((projectId, name) in distinctPairs) {
        val project = DaoProject.findById(projectId)
            ?: error("Project $projectId referenced by fixture_patches.rigging_position no longer exists")
        val rigging = DaoRigging.new {
            this.project = project
            this.name = name
            this.sortOrder = 0
        }
        val safeName = name.replace("'", "''")
        exec(
            """UPDATE fixture_patches
               SET rigging_id = ${rigging.id.value}
               WHERE project_id = $projectId AND rigging_position = '$safeName'"""
        )
        linked++
    }

    // PostgreSQL evaluates the right-hand side of every SET clause against the pre-update
    // row, so this swaps atomically without a temp column.
    exec("UPDATE fixture_patches SET stage_y = stage_z, stage_z = stage_y")

    exec("ALTER TABLE fixture_patches DROP COLUMN rigging_position")

    logger.info(
        "v3 rigging migration complete: created {} rigging(s), swapped Y↔Z, dropped rigging_position",
        linked,
    )
}

/**
 * One-time migration: drop the now-defunct `sync_configs.enabled` column.
 *
 * Cloud sync collapsed from three controls (repoUrl / enabled / autoSync) to one:
 * "synced iff `repoUrl` is set". The `enabled` flag no longer has a meaning distinct
 * from `repoUrl != null`, so it's removed. Rows that had `repoUrl` set but `enabled =
 * false` become synced under the new model — acceptable given the collapse, and rare
 * (these are machine-local rows).
 *
 * Safe to run repeatedly — gated on the column's existence, then `DROP COLUMN IF EXISTS`.
 */
private fun Transaction.migrateDropSyncEnabled() {
    var hasColumn = false
    exec(
        """SELECT 1 FROM information_schema.columns
           WHERE table_name = 'sync_configs' AND column_name = 'enabled'"""
    ) { rs ->
        hasColumn = rs.next()
    }

    if (!hasColumn) return

    logger.info("Migrating sync_configs: dropping the defunct 'enabled' column (synced == repoUrl set)...")

    exec("ALTER TABLE sync_configs DROP COLUMN IF EXISTS enabled")

    logger.info("sync_configs.enabled migration complete")
}

/**
 * One-time migration: drop the show_sessions and show_session_entries tables.
 *
 * Show entries now belong directly to the project via the show_entries table.
 * No data migration is needed.
 *
 * Safe to run repeatedly — uses IF EXISTS guards.
 */
private fun Transaction.migrateDropShowSessions() {
    var hasTable = false
    exec(
        """SELECT 1 FROM information_schema.tables
           WHERE table_name = 'show_sessions'"""
    ) { rs ->
        hasTable = rs.next()
    }

    if (!hasTable) return

    logger.info("Migrating: dropping show_sessions and show_session_entries tables...")

    exec("DROP TABLE IF EXISTS show_session_entries CASCADE")
    exec("DROP TABLE IF EXISTS show_sessions CASCADE")

    logger.info("Show sessions migration complete")
}

/**
 * Adds a deferrable FK constraint from projects.active_entry_id to show_entries.id.
 *
 * This is a circular reference (entries also reference projects), so the FK must be deferrable
 * to allow updating a project and its entries in the same transaction.
 *
 * Safe to run repeatedly — checks for the constraint before adding.
 */
private fun Transaction.migrateProjectActiveEntryFk() {
    var hasConstraint = false
    exec(
        """SELECT 1 FROM information_schema.table_constraints
           WHERE table_name = 'projects' AND constraint_name = 'fk_project_active_entry'"""
    ) { rs ->
        hasConstraint = rs.next()
    }

    if (hasConstraint) return

    var hasTable = false
    exec(
        """SELECT 1 FROM information_schema.tables
           WHERE table_name = 'show_entries'"""
    ) { rs ->
        hasTable = rs.next()
    }

    if (!hasTable) return

    exec("""
        ALTER TABLE projects
            ADD CONSTRAINT fk_project_active_entry
            FOREIGN KEY (active_entry_id) REFERENCES show_entries(id)
            DEFERRABLE INITIALLY DEFERRED
    """.trimIndent())
}

/**
 * Collapses the old two-layer show model (`show_entries` + nullable `cues.cue_stack_id`) into the
 * new first-class model where a project directly owns an *ordered* list of cue stacks (the show)
 * and every cue belongs to a stack.
 *
 * Unlike the PostgreSQL-only migrations above, this runs for **all dialects** (it is the actual data
 * fix for the real SQLite deployment) and touches the now-deleted `show_entries` table only through
 * raw SQL. Idempotent: once there are no null `cue_stack_id` rows and `show_entries` is gone, every
 * step is a no-op.
 *
 * Steps:
 *  1. Move any standalone cues (`cue_stack_id IS NULL`) into a per-project "Unsorted" stack.
 *  2. Apply the `show_entries` order onto `cue_stacks.sort_order`; turn MARKER entries into
 *     `SEPARATOR` stacks (preserving their uuid); then densify `sort_order` per project, appending
 *     unreferenced stacks (incl. "Unsorted") after the ordered ones, by name.
 *  3. Resolve `projects.active_entry_id` → `projects.active_stack_id` (markers resolve to null).
 *  4. Drop the `show_entries` table (the inert `active_entry_id` column is left in place on SQLite).
 */
internal fun Transaction.migrateCollapseShowIntoStacks(database: Database) {
    // ── Step 1: rescue standalone cues ──────────────────────────────────────
    val standaloneByProject = linkedMapOf<Int, MutableList<Int>>()
    exec("SELECT id, project_id FROM cues WHERE cue_stack_id IS NULL") { rs ->
        while (rs.next()) {
            standaloneByProject.getOrPut(rs.getInt("project_id")) { mutableListOf() }.add(rs.getInt("id"))
        }
    }
    if (standaloneByProject.isNotEmpty()) {
        var rescued = 0
        for ((projectId, cueIds) in standaloneByProject) {
            val project = DaoProject.findById(projectId) ?: continue
            val unsorted = DaoCueStack.find {
                (DaoCueStacks.project eq project.id) and
                    (DaoCueStacks.name eq "Unsorted") and
                    (DaoCueStacks.type eq "STACK")
            }.firstOrNull() ?: DaoCueStack.new {
                this.project = project
                name = "Unsorted"
                palette = emptyList()
                loop = false
                type = "STACK"
                sortOrder = (project.cueStacks.maxOfOrNull { it.sortOrder } ?: -1) + 1
            }
            var nextSort = (unsorted.cues.maxOfOrNull { it.sortOrder } ?: -1) + 1
            for (cueId in cueIds) {
                exec("UPDATE cues SET cue_stack_id = ${unsorted.id.value}, sort_order = ${nextSort++} WHERE id = $cueId")
                rescued++
            }
        }
        logger.info("Collapse-show: rescued {} standalone cue(s) into per-project 'Unsorted' stack(s)", rescued)
    }

    // ── Steps 2-4: fold show_entries into the ordered stack collection ──────
    if (!tableExists(database, "show_entries")) return

    data class Entry(
        val id: Int, val projectId: Int, val cueStackId: Int?,
        val entryType: String, val sortOrder: Int, val label: String?, val uuid: String?,
    )

    val entries = mutableListOf<Entry>()
    exec(
        """SELECT id, project_id, cue_stack_id, entry_type, sort_order, label, uuid
           FROM show_entries ORDER BY project_id, sort_order"""
    ) { rs ->
        while (rs.next()) {
            entries.add(Entry(
                id = rs.getInt("id"),
                projectId = rs.getInt("project_id"),
                cueStackId = rs.getInt("cue_stack_id").let { if (rs.wasNull()) null else it },
                entryType = rs.getString("entry_type"),
                sortOrder = rs.getInt("sort_order"),
                label = rs.getString("label"),
                uuid = rs.getString("uuid"),
            ))
        }
    }

    // entryId → resulting stack id, for STACK entries only (used to resolve the active playhead).
    val stackEntryToStackId = mutableMapOf<Int, Int>()
    // Every stack id that came from an entry (STACK targets + created SEPARATORs) — these sort first.
    val referencedStackIds = mutableSetOf<Int>()

    for (entry in entries) {
        when (entry.entryType) {
            "STACK" -> {
                val sid = entry.cueStackId ?: continue
                val labelSql = entry.label?.let { "'${it.replace("'", "''")}'" } ?: "NULL"
                exec("UPDATE cue_stacks SET sort_order = ${entry.sortOrder}, type = 'STACK', label = $labelSql WHERE id = $sid")
                stackEntryToStackId[entry.id] = sid
                referencedStackIds.add(sid)
            }
            "MARKER" -> {
                val project = DaoProject.findById(entry.projectId) ?: continue
                val separator = DaoCueStack.new {
                    this.project = project
                    name = entry.label ?: "Separator"
                    label = entry.label
                    palette = emptyList()
                    loop = false
                    type = "SEPARATOR"
                    sortOrder = entry.sortOrder
                    entry.uuid?.let { u -> runCatching { uuid = UUID.fromString(u) } }
                }
                referencedStackIds.add(separator.id.value)
            }
        }
    }

    // Densify sort_order per project: referenced rows first (by entry order), then unreferenced
    // stacks (including any "Unsorted") appended by name.
    for (projectId in DaoProject.all().map { it.id.value }) {
        val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }
            .toList()
            .sortedWith(
                compareBy(
                    { if (it.id.value in referencedStackIds) 0 else 1 },
                    { it.sortOrder },
                    { it.name },
                )
            )
        stacks.forEachIndexed { index, stack -> if (stack.sortOrder != index) stack.sortOrder = index }
    }

    // ── Step 3: active_entry_id → active_stack_id ───────────────────────────
    if (columnExists(database, "projects", "active_entry_id")) {
        val activeEntryByProject = mutableMapOf<Int, Int>()
        exec("SELECT id, active_entry_id FROM projects WHERE active_entry_id IS NOT NULL") { rs ->
            while (rs.next()) activeEntryByProject[rs.getInt("id")] = rs.getInt("active_entry_id")
        }
        for ((projectId, entryId) in activeEntryByProject) {
            val stackId = stackEntryToStackId[entryId]  // markers → null (not runnable)
            exec("UPDATE projects SET active_stack_id = ${stackId ?: "NULL"} WHERE id = $projectId")
        }
    }

    // ── Step 4: drop show_entries ───────────────────────────────────────────
    if (database.dialect is PostgreSQLDialect) {
        exec("ALTER TABLE projects DROP CONSTRAINT IF EXISTS fk_project_active_entry")
        exec("DROP TABLE IF EXISTS show_entries CASCADE")
    } else {
        exec("DROP TABLE IF EXISTS show_entries")
    }
    logger.info("Collapse-show: migrated {} show entry/entries into ordered stacks; dropped show_entries", entries.size)
}

/** Dialect-agnostic table-existence check (SQLite `sqlite_master`, PostgreSQL `information_schema`). */
private fun Transaction.tableExists(database: Database, table: String): Boolean {
    var exists = false
    val sql = if (database.dialect is PostgreSQLDialect) {
        "SELECT 1 FROM information_schema.tables WHERE table_name = '$table'"
    } else {
        "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'"
    }
    exec(sql) { rs -> exists = rs.next() }
    return exists
}

/** Dialect-agnostic column-existence check. */
private fun Transaction.columnExists(database: Database, table: String, column: String): Boolean {
    var exists = false
    if (database.dialect is PostgreSQLDialect) {
        exec(
            "SELECT 1 FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'"
        ) { rs -> exists = rs.next() }
    } else {
        exec("PRAGMA table_info($table)") { rs ->
            while (rs.next()) {
                if (rs.getString("name") == column) { exists = true; break }
            }
        }
    }
    return exists
}
