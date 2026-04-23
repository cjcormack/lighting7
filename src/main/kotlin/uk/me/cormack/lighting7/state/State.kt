package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.ai.AiService
import uk.me.cormack.lighting7.fx.CueTriggerManager
import uk.me.cormack.lighting7.midi.ActiveBankState
import uk.me.cormack.lighting7.midi.BindingHealthEvaluator
import uk.me.cormack.lighting7.midi.ControlSurfaceBindingService
import uk.me.cormack.lighting7.midi.ControlSurfaceRegistry
import uk.me.cormack.lighting7.midi.DefaultSurfaceActions
import uk.me.cormack.lighting7.midi.DeviceMatcher
import uk.me.cormack.lighting7.midi.FlashStateTracker
import uk.me.cormack.lighting7.midi.LibreMidiAccessSource
import uk.me.cormack.lighting7.midi.MidiDeviceRegistry
import uk.me.cormack.lighting7.midi.MidiLearnSessionManager
import uk.me.cormack.lighting7.midi.SurfaceFeedbackPublisher
import uk.me.cormack.lighting7.midi.SurfaceInputRouter
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.plugins.CueEditSessionRegistry
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.dmx.Universe
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

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
     * Control-surface MIDI device registry (Phase 0 of control-surface-plan.md).
     * Polls connected MIDI ports on a 1 Hz interval, pairs them into device handles, and
     * auto-opens a [uk.me.cormack.lighting7.midi.KtMidiController] for each.
     */
    // Rebuilds of LibreMidiAccess are driven by CoreMIDI4J notifications rather than a timer —
    // periodic recreation leaks observers into libremidi's shared Arena and eventually breaks
    // input on open controllers. See registerCoreMidiChangeListener().
    val midiRegistry: MidiDeviceRegistry by lazy {
        MidiDeviceRegistry(access = LibreMidiAccessSource())
    }

    /**
     * Control-surface device matcher (Phase 1). Subscribes to [midiRegistry] events,
     * matches each connected handle against [uk.me.cormack.lighting7.midi.ControlSurfaceRegistry],
     * and exposes attach / detach / unmatched events for Phase 2+ consumers.
     */
    val deviceMatcher: DeviceMatcher by lazy {
        DeviceMatcher(midiRegistry)
    }

    /**
     * Control-surface binding persistence + cache. Owns the in-memory resolver keyed by
     * `(projectId, deviceTypeKey, controlId, bank)`. The health-context provider
     * assembles a [BindingHealthEvaluator.Context] on each cache rebuild so bindings
     * whose target is now stale surface as non-Ok
     * [uk.me.cormack.lighting7.fx.AssignmentHealth] rather than silently dropping at
     * dispatch time.
     */
    val controlSurfaceBindingService: ControlSurfaceBindingService by lazy {
        ControlSurfaceBindingService(
            database = database,
            healthContextProvider = { projectId -> buildBindingHealthContext(projectId) },
        )
    }

    /**
     * Build a snapshot for [BindingHealthEvaluator]: current [Fixtures], valid cue / stack
     * IDs for [projectId], and the device-type profile list. Returns null if the show
     * isn't initialized yet — callers treat that as "leave existing health unchanged",
     * which keeps newly-loaded bindings marked [AssignmentHealth.Ok] until the show comes
     * up and [ControlSurfaceBindingService.invalidateHealth] is fired.
     */
    private fun buildBindingHealthContext(projectId: Int): BindingHealthEvaluator.Context? {
        val fixtures = try {
            projectManager.show.fixtures
        } catch (_: Exception) {
            return null
        }
        val (stackIds, cueIds) = transaction(database) {
            val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }
                .map { it.id.value }.toSet()
            val cues = DaoCue.find { DaoCues.project eq projectId }
                .map { it.id.value }.toSet()
            stacks to cues
        }
        return BindingHealthEvaluator.Context(
            fixtures = fixtures,
            validStackIds = stackIds,
            validCueIds = cueIds,
            deviceTypes = ControlSurfaceRegistry.allTypes,
        )
    }

    /**
     * MIDI Learn session coordinator. Subscribes to [deviceMatcher] attach events and routes
     * inbound controller events into pending learn sessions.
     */
    val midiLearnSessionManager: MidiLearnSessionManager by lazy {
        MidiLearnSessionManager(
            deviceMatcher = deviceMatcher,
            controllerLookup = midiRegistry::controllerFor,
        )
    }

    /**
     * Active bank per device type (Phase 3). Ephemeral in-memory map mutated by the router
     * on device-side bank buttons and by WS `surfaceBank.set`.
     */
    val activeBankState: ActiveBankState by lazy { ActiveBankState() }

    /**
     * Per-binding flash press tracker (Phase 3). Keyed by `bindingId`; a press that's
     * already active is ignored so MIDI retriggers don't double-apply.
     */
    val flashStateTracker: FlashStateTracker by lazy { FlashStateTracker() }

    /**
     * Phase 6 cue-edit session registry. Each WebSocket connection that runs `cueEdit.*`
     * messages registers its [uk.me.cormack.lighting7.plugins.CueEditSessionState] here so
     * the [SurfaceInputRouter] can route fader writes into the open cue and the
     * [SurfaceFeedbackPublisher] can drive motors from the cue's Layer 3 value.
     */
    val cueEditSessionRegistry: CueEditSessionRegistry by lazy { CueEditSessionRegistry() }

    /**
     * Phase 4 feedback driver. Observes the composition model + flash / scaler state and
     * pushes motor / ring / LED feedback back to attached surfaces. Also hosts touch and
     * soft-takeover state consulted by [surfaceInputRouter].
     */
    val surfaceFeedbackPublisher: SurfaceFeedbackPublisher by lazy {
        SurfaceFeedbackPublisher(
            deviceMatcher = deviceMatcher,
            controllerLookup = midiRegistry::controllerFor,
            bindingService = controlSurfaceBindingService,
            bankState = activeBankState,
            flashTracker = flashStateTracker,
            projectIdProvider = { projectManager.currentProject.id.value },
            fixturesProvider = { show.fixtures },
            globalScalerStateProvider = { show.globalScalerState },
            cueEditSessionProvider = { projectId ->
                cueEditSessionRegistry.activeSession(projectId)?.session
            },
            cueEditEvents = cueEditSessionRegistry.events,
        )
    }

    /**
     * Central dispatch for inbound surface events (Phase 3). Subscribes to
     * [deviceMatcher] attach events and per-controller input flows, resolves bindings
     * via [controlSurfaceBindingService], and calls through to [DefaultSurfaceActions].
     * Phase 4: consults [surfaceFeedbackPublisher] for touch suppression + soft takeover.
     */
    val surfaceInputRouter: SurfaceInputRouter by lazy {
        SurfaceInputRouter(
            deviceMatcher = deviceMatcher,
            controllerLookup = midiRegistry::controllerFor,
            bindingService = controlSurfaceBindingService,
            bankState = activeBankState,
            flashTracker = flashStateTracker,
            projectIdProvider = { projectManager.currentProject.id.value },
            actions = DefaultSurfaceActions(this),
            feedbackHooks = surfaceFeedbackPublisher,
            cueEditSessionProvider = { projectId ->
                cueEditSessionRegistry.activeSession(projectId)?.session
            },
        )
    }

    /**
     * Initialize the show through the project manager.
     * This finds (or migrates) the current project from the database and creates the Show.
     * Must be called explicitly after State construction.
     */
    @OptIn(DelicateCoroutinesApi::class)
    fun initializeShow(): Show {
        val show = projectManager.initialize()
        midiRegistry.start(GlobalScope)
        deviceMatcher.start(GlobalScope)
        midiLearnSessionManager.start(GlobalScope)
        surfaceFeedbackPublisher.start(GlobalScope)
        surfaceInputRouter.start(GlobalScope)
        registerCoreMidiChangeListener()
        attachBindingHealthListener()
        // Re-attach the feedback publisher to the new show's fixture listener on project
        // switch so motor / LED drive follows the composition model of the active project.
        GlobalScope.launch {
            projectManager.projectChangedFlow.collect {
                surfaceFeedbackPublisher.onProjectChanged()
                attachBindingHealthListener()
                // Patch / cue / stack row identities flip on project switch; re-evaluate
                // cached binding health against the new show.
                controlSurfaceBindingService.invalidateHealth(projectManager.currentProject.id.value)
            }
        }
        return show
    }

    /**
     * Refresh binding health on every cached binding for the current project whenever
     * the fixture / patch / cue / cue-stack lists mutate. Re-registered on project
     * switch so the listener follows the active show's [Fixtures] instance.
     */
    private var bindingHealthFixtures: Fixtures? = null
    private val bindingHealthListener = object : FixturesChangeListener {
        override fun channelsChanged(universe: Universe, changes: Map<Int, UByte>) {}
        override fun controllersChanged() {}
        override fun fixturesChanged() = refreshActiveProjectBindingHealth()
        override fun presetListChanged() {}
        override fun cueListChanged() = refreshActiveProjectBindingHealth()
        override fun cueStackListChanged() = refreshActiveProjectBindingHealth()
        override fun cueSlotListChanged() {}
        override fun patchListChanged() = refreshActiveProjectBindingHealth()
        override fun showEntriesChanged() {}
        override fun showChanged(projectId: Int, activeEntryId: Int?, activatedStackId: Int?, activatedStackName: String?) {}
    }

    private fun attachBindingHealthListener() {
        bindingHealthFixtures?.unregisterListener(bindingHealthListener)
        val fixtures = try {
            show.fixtures
        } catch (_: Exception) {
            null
        }
        fixtures?.registerListener(bindingHealthListener)
        bindingHealthFixtures = fixtures
    }

    private fun refreshActiveProjectBindingHealth() {
        val projectId = try {
            projectManager.currentProject.id.value
        } catch (_: Exception) {
            return
        }
        controlSurfaceBindingService.invalidateHealth(projectId)
    }

    // CoreMIDI4J pushes midiSystemUpdated callbacks on macOS plug/unplug. We turn each one
    // into a single access-source rebuild on GlobalScope (the callback runs on a CoreMIDI
    // thread). On non-macOS the native dylib won't load and this is a no-op.
    @OptIn(DelicateCoroutinesApi::class)
    private fun registerCoreMidiChangeListener() {
        try {
            if (!CoreMidiDeviceProvider.isLibraryLoaded()) return
            CoreMidiDeviceProvider.addNotificationListener {
                GlobalScope.launch {
                    runCatching { midiRegistry.rescan(LibreMidiAccessSource()) }
                }
            }
        } catch (t: Throwable) {
            logger.debug("CoreMIDI4J notification listener unavailable: {}", t.message)
        }
    }

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
                DaoProjects, DaoScripts, DaoFxPresets, DaoFxPresetPropertyAssignments,
                DaoCueStacks, DaoCues,
                DaoCuePresetApplications, DaoCueAdHocEffects, DaoCuePropertyAssignments, DaoCueTriggers,
                DaoAiConversations, DaoCueSlots,
                DaoUniverseConfigs, DaoFixturePatches, DaoFixtureGroups, DaoFixtureGroupMembers,
                DaoParkedChannels, DaoFxDefinitions,
                DaoShowEntries,
                DaoControlSurfaceBindings,
            )

            // Migration: drop old unique index on (project_id, name) since we now use (project_id, fixture_type, name)
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")

            // Partial unique index: cue_number must be unique per stack for STANDARD cues
            exec("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_cue_number_per_stack
                    ON cues (cue_stack_id, cue_number)
                    WHERE cue_number IS NOT NULL AND cue_type = 'STANDARD'
            """.trimIndent())

            // Migration: merge show sessions into projects (entries now belong to project directly)
            migrateDropShowSessions()

            // Deferrable FK from projects.active_entry_id → show_entries.id
            // (circular reference: entries also reference projects, so this FK must be deferrable)
            migrateProjectActiveEntryFk()

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

            // Migration: convert legacy StaticValue / StaticSetting ad-hoc effects into
            // first-class CuePropertyAssignment rows (Layer 3). Idempotent — becomes a no-op
            // once the rows are gone.
            val summary = LegacyStaticEffectMigration.run(this)
            if (summary.converted > 0 || summary.skipped > 0) {
                logger.info(
                    "LegacyStaticEffectMigration: converted {} row(s), skipped {} row(s)",
                    summary.converted, summary.skipped,
                )
            }
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

    if (!hasTable) return // already migrated

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

    if (hasConstraint) return // already exists

    // Only add if the show_entries table exists (avoid error on first run before SchemaUtils)
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
