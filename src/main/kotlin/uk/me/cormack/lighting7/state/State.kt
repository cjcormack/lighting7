package uk.me.cormack.lighting7.state

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.config.*
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.ai.AiService
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.auth.DEFAULT_BCRYPT_COST
import uk.me.cormack.lighting7.fx.CueRunState
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

    /**
     * Desk-local accounts and sessions. Must construct after [database] (property order
     * does that) so `createMissingTablesAndColumns` has created its tables before the
     * in-memory caches load. `auth.bcryptCost` exists so tests can run at cost 4;
     * production configs should never set it.
     */
    val authService = AuthService(
        database,
        bcryptCost = config.optionalString("auth.bcryptCost")?.toIntOrNull() ?: DEFAULT_BCRYPT_COST,
    )

    /**
     * Kotlin scripting host configuration shared by every scripting host in the app. Installs
     * the on-disk compiled-script cache (see [buildScriptingHostConfiguration]) so scripts are
     * only compiled from source on the first boot after a rebuild.
     */
    val scriptingHostConfiguration = buildScriptingHostConfiguration(config)

    /**
     * Boot progress for the show init sequence. Driven forward by the background init coroutine
     * in `Application.module()`; read by `GET /api/rest/status` and the `bootProgressState` WS
     * message so clients can render a loading bar during the warm-up window.
     */
    val bootProgress = BootProgress()

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
     * Root directory for prompt-book script PDFs, stored content-addressed as
     * `{projectUuid}/{sha256}.pdf`. Defaults to `<appDataDir>/prompt-scripts` and is
     * overridable via `promptBooks.scriptStoreRoot` in `local.conf` (handy for tests).
     * The store is deliberately outside the DB and outside git sync (JSON-only).
     */
    val promptScriptStoreRoot: Path = config.optionalString("promptBooks.scriptStoreRoot")
        ?.let { Paths.get(it) }
        ?: appDataDir().resolve("prompt-scripts")

    /**
     * Content-addressed on-disk location of a prompt-book script PDF:
     * `{promptScriptStoreRoot}/{projectUuid}/{hash}.pdf`. Shared by the prompt-book
     * routes (upload/serve) and the cloud-sync layer, which copies the byte-accurate
     * file between this store and the git working tree. The bytes never round-trip
     * through the text-oriented sync machinery — see `PromptScriptRepoSync`.
     */
    fun promptScriptPath(projectUuid: String, hash: String): Path =
        promptScriptStoreRoot.resolve(projectUuid).resolve("$hash.pdf")

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
     * Machine-scoped change broadcasts — state that belongs to the desk rather than to a
     * project, so it cannot ride `FixturesChangeListener` (per-project, replaced on project
     * switch). Currently the install row; `plugins/MachineSocket.kt` collects it per connection.
     *
     * Carries ready-made messages, unlike `AuthService.userChanges`, which carries a userId
     * because the user case has to be filtered per recipient. Anything whose frame is identical
     * for every client belongs here.
     */
    private val _machineEventsFlow = MutableSharedFlow<uk.me.cormack.lighting7.plugins.OutMessage>(
        replay = 0,
        extraBufferCapacity = 32,
    )
    val machineEventsFlow: SharedFlow<uk.me.cormack.lighting7.plugins.OutMessage> =
        _machineEventsFlow.asSharedFlow()

    fun emitMachineEvent(message: uk.me.cormack.lighting7.plugins.OutMessage) {
        _machineEventsFlow.tryEmit(message)
    }

    /**
     * In-app updates for the Windows distribution.
     *
     * Its constructor reconciles the *previous* apply — reading `apply-result.properties` and
     * deciding whether the install actually took — so something has to touch it on every boot,
     * not just when someone opens the Updates tab. [startUpdateChecks] is that call.
     *
     * Lazy nonetheless, and the laziness is load-bearing in the other direction: the many tests
     * that build a `State` never start the scheduler, and constructing an HTTP client plus
     * walking the updates directory for each of them would be pure cost. [shutdown] therefore
     * checks [updateServiceDelegate] rather than reading the property, or closing would create
     * the very thing it is trying to release.
     *
     * The service gates itself: on a dev build, a non-packaged run, or a non-Windows host it
     * reports why and never touches the network. See [uk.me.cormack.lighting7.update.UpdateService].
     */
    private val updateServiceDelegate = lazy {
        uk.me.cormack.lighting7.update.UpdateService(
            dataDir = appDataDir(),
            buildInfo = uk.me.cormack.lighting7.update.BuildInfo.current,
            installKind = uk.me.cormack.lighting7.update.InstallKind.fromEnv(),
            repo = config.optionalString("update.repo") ?: "cjcormack/lighting7",
            // Overridable so the whole feature is integration-testable against an embedded stub.
            apiBase = config.optionalString("update.apiBase") ?: "https://api.github.com",
            // The hard kill-switch for a locked-down venue install. Distinct from the per-machine
            // `installs.update_check_enabled` preference below, which the UI can toggle.
            enabled = config.optionalString("update.enabled")?.toBooleanStrictOrNull() ?: true,
            autoCheckEnabledProvider = ::updateAutoCheckEnabled,
            onStateChanged = { service ->
                emitMachineEvent(
                    uk.me.cormack.lighting7.plugins.UpdateStateChangedOutMessage(
                        phase = service.currentPhase,
                        availability = service.currentAvailability,
                        latestVersion = service.latestVersion,
                        downloadedBytes = service.downloadedBytes,
                        totalBytes = service.totalBytes,
                    )
                )
            },
            liveHintProvider = ::updateLiveHint,
        )
    }

    val updateService: uk.me.cormack.lighting7.update.UpdateService by updateServiceDelegate

    /**
     * Touch the update service once the show is up: constructing it reconciles the previous
     * apply, and this starts the background check schedule. Called from `Application.module()`
     * after boot rather than from `init`, so a desk still loading its patch isn't also competing
     * for the network.
     */
    fun startUpdateChecks() {
        runCatching { updateService.scheduleAutomaticChecks() }
            .onFailure { logger.warn("Could not start update checks", it) }
    }

    /** Per-machine opt-out, stored on the install row. Defaults to on. */
    fun updateAutoCheckEnabled(): Boolean = runCatching {
        transaction(database) { DaoInstall.all().firstOrNull()?.updateCheckEnabled ?: true }
    }.getOrDefault(true)

    fun setUpdateAutoCheckEnabled(enabled: Boolean) {
        transaction(database) { DaoInstall.all().firstOrNull()?.updateCheckEnabled = enabled }
    }

    /**
     * What the rig is doing right now, so the confirm dialog can say what stopping it costs.
     * Guarded throughout: this is read while the show may still be booting, or absent entirely.
     */
    private fun updateLiveHint(): uk.me.cormack.lighting7.update.LiveHintDto = runCatching {
        val show = projectManager.showOrNull
            ?: return@runCatching uk.me.cormack.lighting7.update.LiveHintDto(showReady = false)
        uk.me.cormack.lighting7.update.LiveHintDto(
            showReady = true,
            activeStackName = activeCueStackName(),
            activeEffectCount = show.fxEngine.getActiveEffects().size,
        )
    }.getOrElse { uk.me.cormack.lighting7.update.LiveHintDto(showReady = false) }

    /** Name of the cue stack currently running, or null if none is active. */
    private fun activeCueStackName(): String? = runCatching {
        transaction(database) {
            projectManager.currentProject.activeStackId
                ?.let { DaoCueStack.findById(it)?.name }
        }
    }.getOrNull()

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
     * Refresh-on-demand wrapper used by [AuthResolver]. The `onIdentityUpdated` callback
     * mirrors the new expiry into the [DaoOAuthIdentities][uk.me.cormack.lighting7.models.DaoOAuthIdentities]
     * row so the UI's "expires in" badge stays accurate without polling the credential
     * store — and mirrors the re-auth marking, which is the only way a client learns that
     * a background refresh found the authorisation dead.
     *
     * It broadcasts too: refreshes happen off-request (an auto-sync tick, a repo listing),
     * so without a frame here an open sync page would keep rendering a stale identity until
     * something else invalidated the cache.
     */
    val oauthTokenProvider: OAuthTokenProvider? by lazy {
        val client = oauthGitHubClient ?: return@lazy null
        val store = oauthTokenStore ?: return@lazy null
        OAuthTokenProvider(
            tokenStore = store,
            client = client,
            onIdentityUpdated = { identity ->
                transaction(database) {
                    DaoOAuthIdentity.findGithubDefault()?.let {
                        it.accessExpiresAtMs = identity.accessExpiresAtMs
                        it.refreshExpiresAtMs = identity.refreshExpiresAtMs
                        it.reauthRequiredAtMs = identity.reauthRequiredAtMs
                        it.reauthReason = identity.reauthReason
                    }
                }
                emitCloudSyncEvent(
                    uk.me.cormack.lighting7.plugins.OAuthIdentityChangedOutMessage(
                        provider = DaoOAuthIdentities.PROVIDER_GITHUB,
                        connected = true,
                        login = identity.githubLogin,
                        accessExpiresAtMs = identity.accessExpiresAtMs,
                        refreshExpiresAtMs = identity.refreshExpiresAtMs,
                        reauthRequired = identity.reauthRequiredAtMs != null,
                    ),
                )
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

    /**
     * The show if it has been initialised, otherwise null. For callers that run during the
     * server-first warm-up window and must tolerate "not ready yet" instead of throwing.
     */
    val showOrNull: Show? get() = projectManager.showOrNull

    /**
     * True once the show has finished [Show.start] — fixtures loaded, FX engine running — so
     * show-dependent routes/sockets will serve correct data. This is the condition the readiness
     * gate keys on (not merely "Show object constructed", which is true partway through init while
     * fixtures are still empty). Independent of [BootProgress], which drives the loading-bar UI.
     */
    val isShowReady: Boolean get() = projectManager.showOrNull?.isStarted == true

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
        // Speed masters are read from the DB for [projectId] rather than from the live
        // bank, for the same reason the stack / cue ids are: this context is built for an
        // arbitrary project, which may not be the one currently loaded.
        val (stackIds, cueIds, speedMasterUuids) = transaction(database) {
            val stacks = DaoCueStack.find { DaoCueStacks.project eq projectId }
                .map { it.id.value }.toSet()
            val cues = DaoCue.find { DaoCues.project eq projectId }
                .map { it.id.value }.toSet()
            val masters = DaoSpeedMaster.find { DaoSpeedMasters.project eq projectId }
                .map { it.uuid }.toSet()
            Triple(stacks, cues, masters)
        }
        return BindingHealthEvaluator.Context(
            fixtures = fixtures,
            validStackIds = stackIds,
            validCueIds = cueIds,
            deviceTypes = ControlSurfaceRegistry.allTypes,
            validSpeedMasterUuids = speedMasterUuids,
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
     * [SurfaceFeedbackPublisher] can drive motors from the cue's Layer 4 value.
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
            speedMasterBankProvider = { show.speedMasterBank },
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
        // Via the delegate, not the property: reading `updateService` here would construct the
        // service purely to close it, on every test that builds a State and shuts it down.
        if (updateServiceDelegate.isInitialized()) runCatching { updateService.close() }

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
        override fun lookListChanged() {}
        override fun templateListChanged() {}
        override fun speedMasterListChanged() {}
        override fun cueListChanged() = refreshActiveProjectBindingHealth()
        override fun cueStackListChanged() = refreshActiveProjectBindingHealth()
        override fun cueSlotListChanged() {}
        override fun patchListChanged() = refreshActiveProjectBindingHealth()
        override fun riggingListChanged() {}
        override fun stageRegionListChanged() {}
        override fun showChanged(projectId: Int, activeStackId: Int?, activeStackName: String?) {}
        override fun cueRunStateChanged(runState: CueRunState) {}
        override fun promptBookChanged() {}
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
            // Table list lives in models/Schema.kt so the sync-coverage guard can assert
            // against the same set the schema is built from.
            @Suppress("DEPRECATION")
            SchemaUtils.createMissingTablesAndColumns(*ALL_TABLES.toTypedArray())

            // Drops a legacy index name; both PG and SQLite accept `DROP INDEX IF EXISTS`.
            exec("DROP INDEX IF EXISTS fx_presets_project_id_name")

            // FX definition effectIds are unique per project, not globally — a global unique
            // index made cloning or importing any project with a script-defined effect fail.
            // The replacement uniqueIndex(project, effectId) is created by
            // createMissingTablesAndColumns above.
            exec("DROP INDEX IF EXISTS fx_definitions_effect_id")

            // Prompt books collapsed to one-per-project: drop the legacy per-name unique
            // index (was uniqueIndex(project, name)). The new uniqueIndex(project) is
            // created by createMissingTablesAndColumns above.
            exec("DROP INDEX IF EXISTS prompt_books_project_id_name")

            // Cue stacks gained a SEPARATOR type; separators may share a name (e.g. two "Interval"
            // dividers), so the old full (project, name) unique index is replaced with a partial one
            // scoped to real STACK rows. Drop the legacy index name created by the previous model.
            exec("DROP INDEX IF EXISTS cue_stacks_project_id_name")
            exec("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_cue_stack_name_per_project
                    ON cue_stacks (project_id, name)
                    WHERE type = 'STACK'
            """.trimIndent())

            // Cue names are no longer unique. The (project, name) index predates cue stacks;
            // with a project owning many stacks, two stacks may legitimately both hold a
            // "Blackout". Cue numbers stay unique per stack (uq_cue_number_per_stack below).
            exec("DROP INDEX IF EXISTS cues_project_id_name")

            // Partial unique index: cue_number must be unique per stack for STANDARD cues.
            // SQLite supports partial indexes with the same syntax.
            exec("""
                CREATE UNIQUE INDEX IF NOT EXISTS uq_cue_number_per_stack
                    ON cues (cue_stack_id, cue_number)
                    WHERE cue_number IS NOT NULL AND cue_type = 'STANDARD'
            """.trimIndent())

            runStateMigrations()
        }

        // Deliberately after the schema transaction, with a transaction per stack: a stack this
        // can't number must not roll the schema work back with it. See the function's docs.
        backfillAutoCueNumbers(database)

        return database
    }

}
