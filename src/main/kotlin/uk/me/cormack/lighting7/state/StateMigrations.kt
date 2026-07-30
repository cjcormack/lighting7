package uk.me.cormack.lighting7.state

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.DaoCueStack
import uk.me.cormack.lighting7.models.DaoCueStacks
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.routes.renumberAutoCues
import uk.me.cormack.lighting7.models.DaoUniverseConfig
import uk.me.cormack.lighting7.models.DaoUniverseConfigs
import uk.me.cormack.lighting7.sync.Overrides
import java.util.UUID

private val logger = LoggerFactory.getLogger("StateMigrations")

/**
 * Runs the historical schema-evolution migrations and the singleton install bootstrap.
 *
 * SQLite is the only supported backend, and SQLite installs are fresh-start: the schema
 * `createMissingTablesAndColumns` produces already matches the post-migration shape, so the
 * only migrations that survive here are the ones with real data to move.
 *
 * A dozen historical column-drop / constraint migrations used to live here behind an
 * `if (database.dialect is PostgreSQLDialect)` gate, targeting the pre-SQLite development
 * database. That database no longer exists anywhere (see
 * `docs/plans/completed/windows-distribution-plan.md`), no PostgreSQL driver is on the
 * classpath, and a fresh PostgreSQL install would get the latest schema anyway — so they were
 * unreachable code that still had to be hand-refactored on every ORM bump. Removed; recoverable
 * from git history if ever needed.
 */
internal fun JdbcTransaction.runStateMigrations() {
    ensureInstallRow()
    migrateUniverseAddressesToOverrides()
    migrateCollapseShowIntoStacks()
}

/**
 * Give every STANDARD cue that has never had a number one derived from its position.
 *
 * [renumberAutoCues] otherwise only runs off a mutation (create / delete / reorder / an edited cue
 * number), so cues that predate auto-numbering stay blank until something happens to touch their
 * stack — a stack you never edit would show "—" forever. This walks every stack once at startup.
 *
 * Idempotent and cheap to repeat: once a stack's auto numbers agree with its positions
 * [renumberAutoCues] returns without writing, and stacks with no blanks are skipped outright.
 * Explicit numbers are never touched.
 *
 * **Runs one transaction per stack, deliberately outside the schema-creation transaction.** A stack
 * that fails is logged and skipped — cosmetic numbering must not take startup down with it — and
 * that promise only holds with a transaction boundary here, so a failed statement can't roll the
 * schema work back with it.
 */
internal fun backfillAutoCueNumbers(database: Database) {
    var stacksTouched = 0
    var cuesNumbered = 0L
    val stackIds = transaction(database) { DaoCueStack.all().map { it.id.value } }
    for (stackId in stackIds) {
        val delta = runCatching {
            transaction(database) {
                val stack = DaoCueStack.findById(stackId) ?: return@transaction 0L
                val before = blankStandardCueCount(stack)
                if (before == 0L) return@transaction 0L
                renumberAutoCues(stack)
                before - blankStandardCueCount(stack)
            }
        }.getOrElse { error ->
            logger.warn("Could not backfill cue numbers for stack {}: {}", stackId, error.message)
            0L
        }
        if (delta > 0L) {
            stacksTouched++
            cuesNumbered += delta
        }
    }
    if (cuesNumbered > 0) {
        logger.info(
            "Backfilled {} auto cue number(s) across {} stack(s)", cuesNumbered, stacksTouched,
        )
    }
}

/** STANDARD cues in [stack] with no cue number — `""` counts as blank, same as null. */
private fun blankStandardCueCount(stack: DaoCueStack): Long =
    DaoCues.selectAll().where {
        (DaoCues.cueStack eq stack.id) and
            (DaoCues.cueType eq CueType.STANDARD.name) and
            (DaoCues.cueNumber.isNull() or (DaoCues.cueNumber eq ""))
    }.count()

/**
 * Bootstraps the singleton install identity. On first launch creates one row with `friendlyName`
 * defaulting to the system hostname (or "lighting7" if hostname lookup fails). Idempotent.
 */
private fun JdbcTransaction.ensureInstallRow() {
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
private fun JdbcTransaction.migrateUniverseAddressesToOverrides() {
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
 * Collapses the old two-layer show model (`show_entries` + nullable `cues.cue_stack_id`) into the
 * new first-class model where a project directly owns an *ordered* list of cue stacks (the show)
 * and every cue belongs to a stack.
 *
 * This is the actual data fix for the real SQLite deployment, and touches the now-deleted
 * `show_entries` table only through raw SQL. Idempotent: once there are no null `cue_stack_id` rows
 * and `show_entries` is gone, every step is a no-op.
 *
 * Steps:
 *  1. Move any standalone cues (`cue_stack_id IS NULL`) into a per-project "Unsorted" stack.
 *  2. Apply the `show_entries` order onto `cue_stacks.sort_order`; turn MARKER entries into
 *     `SEPARATOR` stacks (preserving their uuid); then densify `sort_order` per project, appending
 *     unreferenced stacks (incl. "Unsorted") after the ordered ones, by name.
 *  3. Resolve `projects.active_entry_id` → `projects.active_stack_id` (markers resolve to null).
 *  4. Drop the `show_entries` table (the inert `active_entry_id` column is left in place).
 */
internal fun JdbcTransaction.migrateCollapseShowIntoStacks() {
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
    if (!tableExists("show_entries")) return

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
    if (columnExists("projects", "active_entry_id")) {
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
    exec("DROP TABLE IF EXISTS show_entries")
    logger.info("Collapse-show: migrated {} show entry/entries into ordered stacks; dropped show_entries", entries.size)
}

/** Table-existence check against SQLite's `sqlite_master`. */
private fun JdbcTransaction.tableExists(table: String): Boolean {
    var exists = false
    exec("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '$table'") { rs -> exists = rs.next() }
    return exists
}

/** Column-existence check via SQLite's `PRAGMA table_info`. */
private fun JdbcTransaction.columnExists(table: String, column: String): Boolean {
    var exists = false
    exec("PRAGMA table_info($table)") { rs ->
        while (rs.next()) {
            if (rs.getString("name") == column) { exists = true; break }
        }
    }
    return exists
}
