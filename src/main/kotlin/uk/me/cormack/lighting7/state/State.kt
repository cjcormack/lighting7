package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.vendors.PostgreSQLDialect
import org.slf4j.LoggerFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
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
import uk.me.cormack.lighting7.midi.GlobalScalerStateHolder
import uk.me.cormack.lighting7.midi.MidiAccessSource
import uk.me.cormack.lighting7.midi.createPlatformKtmidiAccessSource
import uk.me.cormack.lighting7.midi.MidiDeviceRegistry
import uk.me.cormack.lighting7.midi.NoOpMidiAccessSource
import uk.me.cormack.lighting7.midi.MidiLearnSessionManager
import uk.me.cormack.lighting7.midi.SurfaceFeedbackPublisher
import uk.me.cormack.lighting7.midi.SurfaceInputRouter
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.perf.CueEditLatencyTracker
import uk.me.cormack.lighting7.perf.MidiLatencyTracker
import uk.me.cormack.lighting7.plugins.CueEditSessionRegistry
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.show.FixturesChangeListener
import uk.me.cormack.lighting7.show.Show
import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.sync.AutoSyncScheduler
import uk.me.cormack.lighting7.sync.ConflictSession
import uk.me.cormack.lighting7.sync.Overrides
import uk.me.cormack.lighting7.sync.RemoteSyncEngine
import uk.me.cormack.lighting7.sync.SyncLogger
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import uk.me.cormack.lighting7.sync.auth.CredentialStoreFactory
import uk.me.cormack.lighting7.sync.auth.oauth.BundledOAuthCredentials
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthGitHubClient
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenProvider
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthTokenStore
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import uk.co.xfactorylibrarians.coremidi4j.CoreMidiDeviceProvider

private val logger = LoggerFactory.getLogger("State")

class State(val config: ApplicationConfig) {
    // Exposed's `Database.connect()` doesn't surface the underlying datasource, so the
    // reference is kept here for [shutdown] to drain the pool.
    private var dataSource: HikariDataSource? = null
    val database = initDatabase()
    val projectManager = ProjectManager(database) { this }

    init {
        // Must run after initDatabase so the sync_session table exists.
        ConflictSession.recoverFromCrash(this)
    }

    private var projectChangedJob: Job? = null

    /**
     * Root directory under which per-project cloud-sync working trees live, one per
     * `{projectUuid}/repo/`. Defaults to `<appDataDir>/sync` and is overridable via
     * `sync.workingTreeRoot` in `local.conf` (handy for tests and for users who want
     * the repo on a different volume). Resolved once per [State] so a misconfigured
     * path fails loudly at startup.
     */
    val syncWorkingTreeRoot: Path = config.optionalString("sync.workingTreeRoot")
        ?.let { Paths.get(it) }
        ?: appDataDir().resolve("sync")

    /**
     * Cloud-sync lifecycle broadcasts. The REST sync-run handler emits into this flow;
     * each WS handler in `plugins/Sockets.kt` collects per-connection.
     */
    private val _cloudSyncEventsFlow = MutableSharedFlow<uk.me.cormack.lighting7.plugins.OutMessage>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val cloudSyncEventsFlow: SharedFlow<uk.me.cormack.lighting7.plugins.OutMessage> =
        _cloudSyncEventsFlow.asSharedFlow()

    fun emitCloudSyncEvent(message: uk.me.cormack.lighting7.plugins.OutMessage) {
        _cloudSyncEventsFlow.tryEmit(message)
    }

    /**
     * GitHub credential store for cloud sync. Holds Personal Access Tokens (per repo
     * URL) and the install-wide OAuth identity blob (under
     * [CredentialStore.OAUTH_GITHUB_DEFAULT_KEY]). Backend selected by
     * `sync.credentialStore` (default `keychain`) — see [CredentialStoreFactory] for the
     * fallback rules. Built lazily after the install row exists so the file fallback can
     * derive its encryption key from the install UUID.
     */
    val credentialStore: CredentialStore by lazy {
        val backend = config.optionalString("sync.credentialStore")
        val fallbackPath = appDataDir().resolve("credentials.enc")
        val installUuid = transaction(database) {
            DaoInstall.all().firstOrNull()?.uuid?.toString()
                ?: error("Install row missing — `ensureInstallRow` should have created it on startup.")
        }
        CredentialStoreFactory.create(backend, fallbackPath, installUuid)
    }

    /**
     * GitHub OAuth HTTP client. Null when neither `local.conf` nor the build-time
     * bundled credentials supply a complete `(clientId, clientSecret)` pair — the UI
     * then offers PAT-only auth. Owns a Ktor CIO engine; closed in [shutdown].
     *
     * Resolution order (treated as atomic pairs — we never mix clientId from one
     * source with clientSecret from another, since they belong to different GitHub
     * Apps):
     *  1. `sync.oauth.github.{clientId, clientSecret}` from `local.conf`.
     *  2. [BundledOAuthCredentials], baked in at build time by GitHub Actions for
     *     installer distributions (see `-PghOauthClientId` / `-PghOauthClientSecret`
     *     in `build.gradle.kts`).
     */
    val oauthGitHubClient: OAuthGitHubClient? by lazy {
        val pair = resolveOAuthCredentialPair() ?: return@lazy null
        OAuthGitHubClient(clientId = pair.first, clientSecret = pair.second)
    }

    private fun resolveOAuthCredentialPair(): Pair<String, String>? {
        val configId = config.optionalString("sync.oauth.github.clientId")
        val configSecret = config.optionalString("sync.oauth.github.clientSecret")
        if (!configId.isNullOrBlank() && !configSecret.isNullOrBlank()) {
            return configId to configSecret
        }
        if (!configId.isNullOrBlank() && configSecret.isNullOrBlank()) {
            logger.warn(
                "sync.oauth.github.clientId is set in local.conf but clientSecret is blank — " +
                    "ignoring local.conf and falling back to bundled credentials if present.",
            )
        }
        val bundledId = BundledOAuthCredentials.GITHUB_CLIENT_ID
        val bundledSecret = BundledOAuthCredentials.GITHUB_CLIENT_SECRET
        if (bundledId.isNotBlank() && bundledSecret.isNotBlank()) {
            return bundledId to bundledSecret
        }
        return null
    }

    /** Public base URL the user's browser hits to reach this install (for OAuth callbacks). */
    val oauthPublicBaseUrl: String
        get() = config.optionalString("sync.oauth.github.publicBaseUrl")
            ?.trimEnd('/')
            ?: "http://localhost:8413"

    /** OAuth identity blob persistence; null when [oauthGitHubClient] is null. */
    val oauthTokenStore: OAuthTokenStore? by lazy {
        oauthGitHubClient?.let { OAuthTokenStore(credentialStore) }
    }

    /**
     * Refresh-on-demand wrapper used by [AuthResolver]. The `onRefreshed` callback
     * mirrors the new expiry into the [DaoOAuthIdentities][uk.me.cormack.lighting7.models.DaoOAuthIdentities]
     * row so the UI's "expires in" badge stays accurate without polling the credential
     * store.
     */
    val oauthTokenProvider: OAuthTokenProvider? by lazy {
        val client = oauthGitHubClient ?: return@lazy null
        val store = oauthTokenStore ?: return@lazy null
        OAuthTokenProvider(
            tokenStore = store,
            client = client,
            onRefreshed = { identity ->
                transaction(database) {
                    DaoOAuthIdentity.findGithubDefault()?.let {
                        it.accessExpiresAtMs = identity.accessExpiresAtMs
                        it.refreshExpiresAtMs = identity.refreshExpiresAtMs
                    }
                }
            },
        )
    }

    /** Single resolver instance shared by the sync engine and route handlers. */
    val authResolver: AuthResolver by lazy {
        AuthResolver(credentialStore, oauthTokenStore, oauthTokenProvider)
    }

    /**
     * Activity-log writer. Single instance shared across engines, route handlers, and
     * the scheduler so all writes pass through one place (and so test coverage
     * targeting one of those callers also exercises the prune + WS-broadcast paths).
     */
    val syncLogger: SyncLogger by lazy { SyncLogger(this) }

    /**
     * Cloud-sync engine. Shared by route handlers and [autoSyncScheduler] so a single
     * per-project mutex serialises manual `Sync now` clicks against periodic auto-sync
     * ticks.
     */
    val remoteSyncEngine: RemoteSyncEngine by lazy {
        RemoteSyncEngine(this, authResolver)
    }

    /**
     * Periodic driver for [remoteSyncEngine]. Started in [Application.module] after the
     * show is up; stopped in [shutdown]. The engine's own per-project mutex prevents a
     * scheduler tick racing a manual sync.
     */
    val autoSyncScheduler: AutoSyncScheduler by lazy {
        AutoSyncScheduler(this, remoteSyncEngine)
    }

    // Stored here so [shutdown] can close JmDNS as part of the same teardown sequence
    // that drains the rest of the show; ownership lives with Application bootstrap.
    private var mdnsRegistration: Closeable? = null

    fun attachMdns(closeable: Closeable) {
        mdnsRegistration = closeable
    }

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
     * Control-surface MIDI device registry (Phase 0 of plans/completed/control-surface-plan.md).
     * Polls connected MIDI ports on a 1 Hz interval, pairs them into device handles, and
     * auto-opens a [uk.me.cormack.lighting7.midi.KtMidiController] for each.
     */
    // Rebuilds of LibreMidiAccess are driven by CoreMIDI4J notifications rather than a timer —
    // periodic recreation leaks observers into libremidi's shared Arena and eventually breaks
    // input on open controllers. See registerCoreMidiChangeListener().
    val midiRegistry: MidiDeviceRegistry by lazy {
        val access: MidiAccessSource = runCatching { createPlatformKtmidiAccessSource() }
            .getOrElse {
                logger.warn("Native MIDI backend failed to load — control surfaces disabled.", it)
                NoOpMidiAccessSource()
            }
        MidiDeviceRegistry(access = access)
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
     * Per-project [GlobalScalerStateHolder] registry (Phase 9). Lives above the [Show]
     * lifecycle so Blackout / Grand Master state survives project switches within a
     * session. On every project activation the [Show] obtains (or creates) its project's
     * holder here, and the show-scoped [uk.me.cormack.lighting7.midi.GlobalScalerState]
     * reads through to it. A previously-toggled project retains its state across an
     * A → B → A switch.
     *
     * Each holder is write-through to `project_scaler_states` so state also survives a
     * backend restart.
     */
    private val scalerHolders = java.util.concurrent.ConcurrentHashMap<Int, GlobalScalerStateHolder>()

    /**
     * Return the [GlobalScalerStateHolder] for [projectId], creating one on first access.
     * On creation, loads the persisted state from `project_scaler_states` (or defaults if
     * no row exists) and wires a write-through callback so subsequent toggles upsert the
     * row. Thread-safe; called by [Show] during construction.
     */
    fun scalerHolderFor(projectId: Int): GlobalScalerStateHolder =
        scalerHolders.computeIfAbsent(projectId) {
            GlobalScalerStateHolder(
                initial = loadProjectScalerState(projectId),
                persist = { snapshot -> saveProjectScalerState(projectId, snapshot) },
            )
        }

    private fun loadProjectScalerState(projectId: Int): ProjectScalerStateSnapshot =
        transaction(database) {
            DaoProjectScalerState.find { DaoProjectScalerStates.project eq projectId }
                .firstOrNull()
                ?.toSnapshot()
                ?: ProjectScalerStateSnapshot()
        }

    private fun saveProjectScalerState(projectId: Int, snapshot: ProjectScalerStateSnapshot) {
        transaction(database) {
            val project = DaoProject.findById(projectId) ?: return@transaction
            val existing = DaoProjectScalerState
                .find { DaoProjectScalerStates.project eq projectId }
                .firstOrNull()
            if (existing != null) {
                existing.blackout = snapshot.blackout
                existing.grandMaster = snapshot.grandMaster
            } else {
                DaoProjectScalerState.new {
                    this.project = project
                    this.blackout = snapshot.blackout
                    this.grandMaster = snapshot.grandMaster
                }
            }
        }
    }

    /**
     * Phase 6 cue-edit session registry. Each WebSocket connection that runs `cueEdit.*`
     * messages registers its [uk.me.cormack.lighting7.plugins.CueEditSessionState] here so
     * the [SurfaceInputRouter] can route fader writes into the open cue and the
     * [SurfaceFeedbackPublisher] can drive motors from the cue's Layer 3 value.
     */
    val cueEditSessionRegistry: CueEditSessionRegistry by lazy { CueEditSessionRegistry() }

    /**
     * Per-process timing histogram for [uk.me.cormack.lighting7.plugins.CueEditSessionHandler.setPropertyForSession].
     * Reset on `cueEdit.beginEdit`; snapshot frozen on `cueEdit.endEdit`. Read via
     * `GET /api/rest/perf/cueedit-histogram` — drives the
     * `MidiFloodHarness` profiling step that gates `FU-PERF-COALESCE-WRITES`.
     */
    val cueEditLatencyTracker: CueEditLatencyTracker by lazy { CueEditLatencyTracker() }

    /**
     * Per-process MIDI surface hot-path histogram registry. Buckets covering ingress (router →
     * dispatch) and egress (feedback publisher → controller) stages — see
     * [SurfaceInputRouter] / [SurfaceFeedbackPublisher] for the recording sites. Read via
     * `GET /api/rest/perf/midi-latency`; reset via `POST /api/rest/perf/midi-latency/reset`.
     */
    val midiLatencyTracker: MidiLatencyTracker by lazy { MidiLatencyTracker() }

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
            latencyTracker = midiLatencyTracker,
        )
    }

    /**
     * Central dispatch for inbound surface events (Phase 3). Subscribes to
     * [deviceMatcher] attach events and per-controller input flows, resolves bindings
     * via [controlSurfaceBindingService], and calls through to [DefaultSurfaceActions].
     * Phase 4: consults [surfaceFeedbackPublisher] for touch suppression + soft takeover.
     * Cue-edit session routing lives inside [DefaultSurfaceActions] — the router itself is
     * session-agnostic.
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
            latencyTracker = midiLatencyTracker,
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
        // Add the CoreMIDI4J notification listener BEFORE starting the registry's poll loop.
        // The poll loop calls into MidiSystem.getMidiDeviceInfo, which acquires the
        // CoreMidiDeviceProvider class lock via a ServiceLoader → JSSecurityManager path; if a
        // poll tick races registerCoreMidiChangeListener (which also wants that lock), the two
        // can deadlock under JVM-internal lock ordering. See FU-TEST-COREMIDI-INIT-DEADLOCK.
        registerCoreMidiChangeListener()
        midiRegistry.start(GlobalScope)
        deviceMatcher.start(GlobalScope)
        midiLearnSessionManager.start(GlobalScope)
        surfaceFeedbackPublisher.start(GlobalScope)
        surfaceInputRouter.start(GlobalScope)
        attachBindingHealthListener()
        // Re-attach the feedback publisher to the new show's fixture listener on project
        // switch so motor / LED drive follows the composition model of the active project.
        projectChangedJob = GlobalScope.launch {
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
     * Tear down everything [initializeShow] started: cancel the project-changed
     * collector, stop the surface stack in reverse-startup order, close the show, and
     * drain the Hikari pool. Idempotent — safe to call multiple times and even when
     * `initializeShow` was never called.
     *
     * Primary caller is `RouteIntegrationTest`; leaking the per-State GlobalScope
     * pollers between tests previously deadlocked the full suite via CoreMIDI4J
     * class-init contention. See `FU-TEST-COREMIDI-INIT-DEADLOCK`.
     */
    fun shutdown() {
        val ds = dataSource ?: return
        dataSource = null

        runCatching { autoSyncScheduler.stop() }
        runCatching { projectChangedJob?.cancel() }
        projectChangedJob = null

        runCatching { surfaceInputRouter.stop() }
        runCatching { surfaceFeedbackPublisher.stop() }
        runCatching { midiLearnSessionManager.stop() }
        runCatching { deviceMatcher.stop() }
        runCatching { midiRegistry.close() }

        runCatching { mdnsRegistration?.close() }
        mdnsRegistration = null

        runCatching { oauthGitHubClient?.close() }

        runCatching { projectManager.show.close() }

        runCatching { ds.close() }
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
                    runCatching { midiRegistry.rescan(createPlatformKtmidiAccessSource()) }
                }
            }
        } catch (t: Throwable) {
            logger.debug("CoreMIDI4J notification listener unavailable: {}", t.message)
        }
    }

    private fun initDatabase(): Database {
        val dbPath = config.optionalString("database.path")
            ?: appDataDir().resolve("lighting7.db").toString()
        val ds = HikariDataSource(HikariConfig().apply {
            driverClassName = "org.sqlite.JDBC"
            jdbcUrl = "jdbc:sqlite:$dbPath"
            // SQLite has a single writer; a multi-connection pool produces SQLITE_BUSY under load.
            maximumPoolSize = 1
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_SERIALIZABLE"
            validate()
        })
        dataSource = ds
        val database = Database.connect(ds)

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
                DaoUniverseConfigs, DaoRiggings, DaoStageRegions,
                DaoFixturePatches, DaoFixtureGroups, DaoFixtureGroupMembers,
                DaoParkedChannels, DaoFxDefinitions,
                DaoShowEntries,
                DaoControlSurfaceBindings,
                DaoProjectScalerStates,
                DaoInstalls, DaoMachineOverrides,
                DaoSyncConfigs,
                DaoSyncStates, DaoSyncSessions, DaoSyncSessionConflicts,
                DaoSyncLogEntries,
                DaoOAuthIdentities,
            )

            // Drops a legacy index name; both PG and SQLite accept `DROP INDEX IF EXISTS`.
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")

            // Partial unique index: cue_number must be unique per stack for STANDARD cues.
            // SQLite supports partial indexes with the same syntax.
            exec("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_cue_number_per_stack
                    ON cues (cue_stack_id, cue_number)
                    WHERE cue_number IS NOT NULL AND cue_type = 'STANDARD'
            """.trimIndent())

            // Historical schema-evolution migrations. These query `information_schema` and
            // use ALTER TABLE syntax that PostgreSQL accepts but SQLite does not. SQLite
            // installs are fresh-start (no PG → SQLite data import), so the latest schema
            // produced by `createMissingTablesAndColumns` already matches the post-migration
            // shape.
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
        }

        return database
    }

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

    // Swap stage_y ↔ stage_z on every patch row. PostgreSQL evaluates the right-hand
    // side of every SET clause against the pre-update row, so this swaps atomically
    // without a temp column.
    exec("UPDATE fixture_patches SET stage_y = stage_z, stage_z = stage_y")

    exec("ALTER TABLE fixture_patches DROP COLUMN rigging_position")

    logger.info(
        "v3 rigging migration complete: created {} rigging(s), swapped Y↔Z, dropped rigging_position",
        linked,
    )
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
