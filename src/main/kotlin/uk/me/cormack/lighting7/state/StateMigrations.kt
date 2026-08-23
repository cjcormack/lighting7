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
import kotlinx.serialization.json.Json
import uk.me.cormack.lighting7.models.CueType
import uk.me.cormack.lighting7.models.LookEffectSpec
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
    migratePresetsAndPalettesToLooks()
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

/**
 * Collapse FX presets and named palettes into Looks, and cue preset applications into cue layers.
 *
 * **Uuid preservation is the load-bearing part.** Each Look keeps the uuid of the palette or preset
 * it came from, which buys three things at once: the migration is idempotent (a second run finds the
 * Look already there and skips), existing `ref:{uuid}` values keep resolving through
 * [uk.me.cormack.lighting7.fx.LookRegistry] with no grammar change, and a synced peer sees the same
 * record identity rather than a duplicate.
 *
 * Mapping:
 * - each palette → a Look; entries → rows with concrete targets
 * - each preset → a Look carrying its **effects** (as `look_effects` rows marked `deferred`, which
 *   still means "fan over the layer's targets") and its `palette`. Its target-less *property
 *   assignments* are **dropped with a warning** — see the note in the presets arm below.
 * - each cue preset application → a cue layer, carrying `sortOrder`, the timing triple and both
 *   speed-master overrides
 * - each cue assignment whose value is `ref:{uuid}` → folded into **one** layer per (cue, Look),
 *   `targets` = exactly the referenced targets and `propertyMask` = exactly the referenced
 *   properties, then the row deleted
 *
 * That last rule is why a layer's `targets` *filters* bound rows as well as supplying deferred
 * ones. Without the restriction, a cue that referenced a palette for two fixtures would silently
 * start asserting every fixture the palette covers.
 *
 * A name collision is resolved by suffixing, because palettes and presets had *different* unique
 * indexes — `(project, type, name)` and `(project, fixtureType, name)` — so a project may legally
 * hold a "Warm" colour palette, a "Warm" position palette and a "Warm" preset, all of which now
 * want one `(project, name)` slot.
 *
 * Raw SQL throughout rather than the DAOs. The source tables are on their way out and their DAOs go
 * with them, so reaching through Exposed here would mean this migration has to be rewritten the
 * moment they are deleted — exactly what `migrateCollapseShowIntoStacks` avoided for `show_entries`.
 */
internal fun JdbcTransaction.migratePresetsAndPalettesToLooks() {
    // Drop rows written by the pre-fix version of this migration. It read every source uuid with
    // `getString` on a BLOB column and wrote it back as a *text* literal, so those rows carry a
    // uuid that is both corrupted (bytes above 0x7F replaced with U+FFFD, unrecoverable) and
    // invisible to `DaoLooks.uuid eq …`. They are unreachable at runtime, and leaving them would
    // also defeat the idempotency check below — the repaired pass would compute the real uuid, miss
    // it in the index, and mint a duplicate "Warm (2)" Look beside the dead one.
    //
    // `typeof(uuid) <> 'blob'` names exactly those rows: every uuid written through Exposed is a
    // 16-byte blob. Safe on a fresh install, where it matches nothing.
    exec(
        "DELETE FROM cue_layers WHERE typeof(uuid) <> 'blob' " +
            "OR look_id IN (SELECT id FROM looks WHERE typeof(uuid) <> 'blob')"
    )
    exec("DELETE FROM look_rows WHERE look_id IN (SELECT id FROM looks WHERE typeof(uuid) <> 'blob')")
    exec("DELETE FROM look_effects WHERE look_id IN (SELECT id FROM looks WHERE typeof(uuid) <> 'blob')")
    exec("DELETE FROM looks WHERE typeof(uuid) <> 'blob'")

    if (!tableExists("palettes") && !tableExists("fx_presets")) return

    // uuid → look id, for wiring layers up afterwards. Also the idempotency index: a uuid already
    // present in `looks` was migrated on an earlier boot.
    val lookIdByUuid = HashMap<UUID, Int>()
    exec("SELECT id, uuid FROM looks") { rs ->
        while (rs.next()) rs.javaUuid("uuid")?.let { lookIdByUuid[it] = rs.getInt("id") }
    }
    val takenNames = HashMap<Int, MutableSet<String>>()
    exec("SELECT project_id, name FROM looks") { rs ->
        while (rs.next()) takenNames.getOrPut(rs.getInt("project_id")) { HashSet() }.add(rs.getString("name"))
    }

    fun uniqueName(projectId: Int, desired: String): String {
        val taken = takenNames.getOrPut(projectId) { HashSet() }
        if (taken.add(desired)) return desired
        for (n in 2..10_000) {
            val candidate = "$desired ($n)"
            if (taken.add(candidate)) return candidate
        }
        return desired
    }

    fun nextSortOrder(projectId: Int): Int {
        var next = 0
        exec("SELECT COALESCE(MAX(sort_order), -1) + 1 AS n FROM looks WHERE project_id = $projectId") { rs ->
            if (rs.next()) next = rs.getInt("n")
        }
        return next
    }

    var palettesMigrated = 0
    var presetsMigrated = 0

    // ── Palettes → bound Looks ──────────────────────────────────────────────
    if (tableExists("palettes")) {
        data class Pal(val id: Int, val projectId: Int, val name: String, val notes: String?, val sortOrder: Int, val uuid: UUID?)
        val palettes = mutableListOf<Pal>()
        exec("SELECT id, project_id, name, notes, sort_order, uuid FROM palettes") { rs ->
            while (rs.next()) {
                palettes.add(Pal(
                    rs.getInt("id"), rs.getInt("project_id"), rs.getString("name"),
                    rs.getString("notes"), rs.getInt("sort_order"), rs.javaUuid("uuid"),
                ))
            }
        }
        for (pal in palettes) {
            // A palette with no readable uuid cannot be identity-preserved, and migrating it under a
            // fresh one would strand every `ref:` pointing at it. Leave it for the operator.
            val palUuid = pal.uuid
            if (palUuid == null) {
                logger.warn("Looks migration: palette '{}' has an unreadable uuid — skipping", pal.name)
                continue
            }
            if (palUuid in lookIdByUuid) continue
            val name = uniqueName(pal.projectId, pal.name)
            exec(
                "INSERT INTO looks (project_id, name, notes, sort_order, palette, uuid) " +
                    "VALUES (${pal.projectId}, ${sqlText(name)}, ${sqlText(pal.notes)}, ${nextSortOrder(pal.projectId)}, " +
                    "'[]', ${sqlUuid(palUuid)})"
            )
            val lookId = lastInsertRowId() ?: continue
            lookIdByUuid[palUuid] = lookId
            exec(
                "INSERT INTO look_rows (look_id, target_type, target_key, property_name, value, " +
                    "fade_duration_ms, element_key, sort_order, uuid) " +
                    "SELECT $lookId, target_type, target_key, property_name, value, NULL, NULL, sort_order, uuid " +
                    "FROM palette_entries WHERE palette_id = ${pal.id}"
            )
            palettesMigrated++
        }
    }

    // ── Presets → Looks carrying their effects ──────────────────────────────
    //
    // Session 3 narrowed what this arm can produce, and the narrowing is deliberate rather than a
    // regression. A preset was target-less values *plus* effects with a declared fixture type — and
    // post-split those are two different entities: the values are a template (one family, an intent
    // per row, resolved per head) and the effects belong to a Look. One source record cannot become
    // both here without inventing a name for the second, guessing which family its values are in,
    // and converting DMX literals to intents with no fixture to convert them against.
    //
    // So the effects come across, because they are the substantive half and a `deferred` *effect* is
    // still exactly what it was. The property assignments are counted and logged rather than
    // written: a deferred Look *row* is refused at the write boundary now, and writing one anyway
    // would seed a database with data no editor can reach and the next save would reject.
    //
    // The uuid is still preserved, so the `cue_preset_applications` arm below still finds its Look
    // and every existing reference still resolves.
    if (tableExists("fx_presets")) {
        data class Preset(val id: Int, val projectId: Int, val name: String, val description: String?, val fixtureType: String, val effects: String, val palette: String, val uuid: UUID?)
        val presets = mutableListOf<Preset>()
        exec("SELECT id, project_id, name, description, fixture_type, effects, palette, uuid FROM fx_presets") { rs ->
            while (rs.next()) {
                presets.add(Preset(
                    rs.getInt("id"), rs.getInt("project_id"), rs.getString("name"),
                    rs.getString("description"), rs.getString("fixture_type"),
                    rs.getString("effects") ?: "[]", rs.getString("palette") ?: "[]",
                    rs.javaUuid("uuid"),
                ))
            }
        }
        for (preset in presets) {
            val presetUuid = preset.uuid
            if (presetUuid == null) {
                logger.warn("Looks migration: preset '{}' has an unreadable uuid — skipping", preset.name)
                continue
            }
            if (presetUuid in lookIdByUuid) continue
            val name = uniqueName(preset.projectId, preset.name)
            exec(
                "INSERT INTO looks (project_id, name, notes, sort_order, palette, uuid) " +
                    "VALUES (${preset.projectId}, ${sqlText(name)}, ${sqlText(preset.description)}, " +
                    "${nextSortOrder(preset.projectId)}, " +
                    "${sqlText(preset.palette)}, ${sqlUuid(presetUuid)})"
            )
            val lookId = lastInsertRowId() ?: continue
            lookIdByUuid[presetUuid] = lookId

            // The target-less value rows, counted and reported rather than written. See the arm's
            // header for why they cannot become Look rows any more; a `${preset.fixtureType}` preset
            // whose values matter should be re-authored as a template.
            var droppedAssignments = 0
            exec("SELECT COUNT(*) AS n FROM fx_preset_property_assignments WHERE preset_id = ${preset.id}") { rs ->
                if (rs.next()) droppedAssignments = rs.getInt("n")
            }
            if (droppedAssignments > 0) {
                logger.warn(
                    "Looks migration: preset '{}' had {} target-less value row(s), which are now a " +
                        "template rather than a look — its effects were migrated, the values were not. " +
                        "Re-create them at /templates (they were authored for '{}').",
                    preset.name, droppedAssignments, preset.fixtureType,
                )
            }

            // The effects blob becomes real rows. Uuids are minted here because the blob never had
            // any — the effects lived inside the preset's own record, so sync only ever saw the
            // parent's uuid.
            for ((index, effect) in parseLegacyPresetEffects(preset.effects).withIndex()) {
                exec(
                    "INSERT INTO look_effects (look_id, target_type, target_key, effect_type, category, " +
                        "property_name, beat_division, blend_mode, distribution, phase_offset, element_mode, " +
                        "element_filter, step_timing, parameters, speed_master_uuid, rate_speed_master_uuid, " +
                        "sort_order, uuid) VALUES ($lookId, 'deferred', '', " +
                        "${sqlText(effect.effectType)}, ${sqlText(effect.category)}, " +
                        "${sqlText(effect.propertyName)}, ${effect.beatDivision}, " +
                        "${sqlText(effect.blendMode)}, ${sqlText(effect.distribution)}, ${effect.phaseOffset}, " +
                        "${sqlText(effect.elementMode)}, ${sqlText(effect.elementFilter)}, " +
                        "${effect.stepTiming?.let { if (it) 1 else 0 }?.toString() ?: "NULL"}, " +
                        "${sqlText(Json.encodeToString(effect.parameters))}, " +
                        "${sqlUuid(uuidOrNull(effect.speedMasterUuid))}, " +
                        "${sqlUuid(uuidOrNull(effect.rateSpeedMasterUuid))}, " +
                        "$index, ${sqlUuid(UUID.randomUUID())})"
                )
            }
            presetsMigrated++
        }
    }

    // ── Cue preset applications → cue layers ────────────────────────────────
    var layersFromApplications = 0
    if (tableExists("cue_preset_applications")) {
        data class App(
            val id: Int, val cueId: Int, val presetUuid: UUID?, val targets: String,
            val delayMs: Long?, val intervalMs: Long?, val randomWindowMs: Long?,
            val sortOrder: Int, val speedMasterUuid: UUID?, val rateSpeedMasterUuid: UUID?,
            val uuid: UUID?,
        )
        val apps = mutableListOf<App>()
        exec(
            """SELECT a.id, a.cue_id, p.uuid AS preset_uuid, a.targets, a.delay_ms, a.interval_ms,
                      a.random_window_ms, a.sort_order, a.speed_master_uuid, a.rate_speed_master_uuid, a.uuid
               FROM cue_preset_applications a LEFT JOIN fx_presets p ON p.id = a.preset_id"""
        ) { rs ->
            while (rs.next()) {
                apps.add(App(
                    rs.getInt("id"), rs.getInt("cue_id"), rs.javaUuid("preset_uuid"),
                    rs.getString("targets") ?: "[]",
                    rs.getLong("delay_ms").takeUnless { rs.wasNull() },
                    rs.getLong("interval_ms").takeUnless { rs.wasNull() },
                    rs.getLong("random_window_ms").takeUnless { rs.wasNull() },
                    rs.getInt("sort_order"),
                    rs.javaUuid("speed_master_uuid"), rs.javaUuid("rate_speed_master_uuid"),
                    rs.javaUuid("uuid"),
                ))
            }
        }
        val existingLayerUuids = HashSet<UUID>()
        exec("SELECT uuid FROM cue_layers") { rs ->
            while (rs.next()) rs.javaUuid("uuid")?.let { existingLayerUuids.add(it) }
        }

        for (app in apps) {
            val appUuid = app.uuid ?: UUID.randomUUID()
            if (appUuid in existingLayerUuids) continue
            val lookId = app.presetUuid?.let { lookIdByUuid[it] } ?: continue
            exec(
                "INSERT INTO cue_layers (cue_id, look_id, sort_order, enabled, targets, property_mask, " +
                    "blend_mode, amount, stomp, speed_master_uuid, rate_speed_master_uuid, " +
                    "delay_ms, interval_ms, random_window_ms, uuid) VALUES (" +
                    "${app.cueId}, $lookId, ${app.sortOrder}, 1, ${sqlText(app.targets)}, NULL, " +
                    "'OVERRIDE', 1.0, 0, ${sqlUuid(app.speedMasterUuid)}, ${sqlUuid(app.rateSpeedMasterUuid)}, " +
                    "${app.delayMs ?: "NULL"}, ${app.intervalMs ?: "NULL"}, ${app.randomWindowMs ?: "NULL"}, " +
                    "${sqlUuid(appUuid)})"
            )
            layersFromApplications++
        }
    }

    // ── `ref:` cue assignments → one masked layer per (cue, Look) ───────────
    var refRowsFolded = 0
    var refLayersCreated = 0
    run {
        data class RefRow(val id: Int, val cueId: Int, val targetType: String, val targetKey: String, val propertyName: String, val lookUuid: UUID)
        val refRows = mutableListOf<RefRow>()
        exec(
            "SELECT id, cue_id, target_type, target_key, property_name, value " +
                "FROM cue_property_assignments WHERE value LIKE 'ref:%'"
        ) { rs ->
            while (rs.next()) {
                val raw = rs.getString("value") ?: continue
                // `value` is a genuine text column — the `ref:{uuid}` grammar is written as text by
                // app code — so this one really is a string read.
                val uuid = uuidOrNull(raw.removePrefix("ref:")) ?: continue
                refRows.add(RefRow(
                    rs.getInt("id"), rs.getInt("cue_id"), rs.getString("target_type"),
                    rs.getString("target_key"), rs.getString("property_name"), uuid,
                ))
            }
        }
        // One layer per (cue, Look): the targets and properties it covers are exactly the union of
        // the rows it replaces, so coverage is preserved to the row.
        for ((key, rows) in refRows.groupBy { it.cueId to it.lookUuid }) {
            val (cueId, lookUuid) = key
            val lookId = lookIdByUuid[lookUuid] ?: continue
            val targets = rows
                .map { it.targetType to it.targetKey }
                .distinct()
                .joinToString(",", "[", "]") { (type, key2) ->
                    """{"type":"$type","key":"$key2"}"""
                }
            val mask = rows.map { it.propertyName }.distinct()
                .mapNotNull { maskGroupNameForPropertyName(it) }
                .distinct()
            exec(
                "INSERT INTO cue_layers (cue_id, look_id, sort_order, enabled, targets, property_mask, " +
                    "blend_mode, amount, stomp, speed_master_uuid, rate_speed_master_uuid, " +
                    "delay_ms, interval_ms, random_window_ms, uuid) VALUES (" +
                    "$cueId, $lookId, -1, 1, ${sqlText(targets)}, " +
                    "${if (mask.isEmpty()) "NULL" else sqlText(mask.joinToString(","))}, " +
                    "'OVERRIDE', 1.0, 0, NULL, NULL, NULL, NULL, NULL, " +
                    "${sqlUuid(UUID.randomUUID())})"
            )
            refLayersCreated++
            for (row in rows) {
                exec("DELETE FROM cue_property_assignments WHERE id = ${row.id}")
                refRowsFolded++
            }
        }
        // Reference layers went in at sort_order -1 so they sit *beneath* the cue's migrated
        // preset layers, matching the old order: a preset row beat a cue row on an exact priority
        // tie, because the cue's own rows came first in the concatenated list and `composeLtp`
        // kept the first maximal element. Densify so nothing stays negative.
        if (refLayersCreated > 0) densifyCueLayerOrder()
    }

    if (palettesMigrated + presetsMigrated + layersFromApplications + refRowsFolded > 0) {
        logger.info(
            "Looks migration: {} palette(s) and {} preset(s) became looks; " +
                "{} preset application(s) became layers; {} ref: row(s) folded into {} layer(s)",
            palettesMigrated, presetsMigrated, layersFromApplications, refRowsFolded, refLayersCreated,
        )
    }
}

/** Renumber each cue's layers from 0 in their current order, so no `sort_order` stays negative. */
private fun JdbcTransaction.densifyCueLayerOrder() {
    val byCue = LinkedHashMap<Int, MutableList<Int>>()
    exec("SELECT id, cue_id FROM cue_layers ORDER BY cue_id, sort_order, id") { rs ->
        while (rs.next()) byCue.getOrPut(rs.getInt("cue_id")) { mutableListOf() }.add(rs.getInt("id"))
    }
    for (ids in byCue.values) {
        ids.forEachIndexed { index, id ->
            exec("UPDATE cue_layers SET sort_order = $index WHERE id = $id")
        }
    }
}

/**
 * The `PropertyMaskGroup` name a property belongs to, without a fixture to ask.
 *
 * A layer's mask has to be decided at migration time, when the patch may not even be loaded, so
 * this maps the *names* the property catalogue uses. Unknown names answer null and simply widen the
 * mask — an over-broad mask still resolves through the Look's own rows, whereas an over-narrow one
 * would drop coverage the old `ref:` row had.
 */
private fun maskGroupNameForPropertyName(propertyName: String): String? =
    when (propertyName.lowercase()) {
        "dimmer", "strobe" -> "INTENSITY"
        "position", "pan", "tilt", "panfine", "tiltfine" -> "POSITION"
        "colour", "color", "rgbcolour", "amber", "white", "uv" -> "COLOUR"
        else -> null
    }

/** The rowid SQLite assigned to the row just inserted on this connection. */
private fun JdbcTransaction.lastInsertRowId(): Int? {
    var id: Int? = null
    exec("SELECT last_insert_rowid() AS id") { rs -> if (rs.next()) id = rs.getInt("id") }
    return id
}

/** SQL string literal, or `NULL`. Doubles embedded quotes — the only escaping SQLite needs. */
private fun sqlText(value: String?): String =
    if (value == null) "NULL" else "'" + value.replace("'", "''") + "'"

/**
 * Read a `javaUUID` column out of a raw-SQL result.
 *
 * **Never use `getString` for these.** Exposed's `UUIDColumnType` declares `BINARY(16)` and binds a
 * 16-byte array, so on SQLite every uuid an app write produced is a **BLOB**. `ResultSet.getString`
 * on a BLOB reinterprets those bytes as text, and each byte above 0x7F becomes U+FFFD — corruption
 * that cannot be undone. That silently destroyed the uuid preservation this migration's whole
 * design rests on: idempotency, `ref:{uuid}` resolution and sync identity all key off it.
 *
 * A text value is tolerated so a row written by the pre-fix version of this migration, or by any
 * other raw-SQL path, still reads back when it happens to be well-formed.
 */
private fun java.sql.ResultSet.javaUuid(column: String): UUID? {
    val bytes = getBytes(column) ?: return null
    if (bytes.size == 16) {
        val buf = java.nio.ByteBuffer.wrap(bytes)
        return UUID(buf.long, buf.long)
    }
    return runCatching { UUID.fromString(String(bytes, Charsets.UTF_8)) }.getOrNull()
}

/**
 * A `javaUUID` column literal — a **blob** literal, not a quoted string.
 *
 * SQLite never compares a BLOB equal to a TEXT value, and Exposed binds these columns as a 16-byte
 * blob. So a text uuid here reads back fine through the DAO but is invisible to
 * `DaoLooks.uuid eq someUuid` — which is exactly the lookup `loadLookSnapshot` performs, so every
 * migrated Look would fail to load and every migrated layer would be skipped at cue apply.
 */
@Suppress("MagicNumber")
private fun sqlUuid(value: UUID?): String =
    if (value == null) {
        "NULL"
    } else {
        java.nio.ByteBuffer.allocate(16)
            .putLong(value.mostSignificantBits)
            .putLong(value.leastSignificantBits)
            .array()
            .joinToString(separator = "", prefix = "x'", postfix = "'") { "%02x".format(it) }
    }

/** Parse a uuid that reached us as text (a JSON blob field, or a `ref:` value). */
private fun uuidOrNull(value: String?): UUID? =
    value?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/**
 * Parse the legacy `fx_presets.effects` JSON blob.
 *
 * Lenient on purpose (`ignoreUnknownKeys`): the blob accumulated fields over the preset era, and a
 * migration that throws on an unrecognised one would take startup down over a field nobody reads.
 */
private fun parseLegacyPresetEffects(raw: String): List<LookEffectSpec> =
    runCatching { legacyEffectJson.decodeFromString<List<LookEffectSpec>>(raw) }
        .getOrElse {
            logger.warn("Looks migration: could not parse a preset's effects blob — skipping its effects: {}", it.message)
            emptyList()
        }

private val legacyEffectJson = Json { ignoreUnknownKeys = true }
