package uk.me.cormack.lighting7.cloud

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.auth.AuthService
import uk.me.cormack.lighting7.cloud.protocol.Capability
import uk.me.cormack.lighting7.cloud.protocol.MAX_FRAME_BYTES
import uk.me.cormack.lighting7.cloud.protocol.Message
import uk.me.cormack.lighting7.link.LinkBinary
import uk.me.cormack.lighting7.link.LinkIn
import uk.me.cormack.lighting7.link.LinkProcess
import uk.me.cormack.lighting7.link.LinkSidecar
import uk.me.cormack.lighting7.link.LinkStatus
import uk.me.cormack.lighting7.process.PidFile
import uk.me.cormack.lighting7.state.appDataDir
import uk.me.cormack.lighting7.update.BuildInfo
import uk.me.cormack.lighting7.mcp.originOf
import uk.me.cormack.lighting7.models.CloudConnectionState
import uk.me.cormack.lighting7.models.DaoCloudConnections
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.nio.file.Path
import java.security.PrivateKey
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The desk's side of Lighting7 Cloud (cloud-p0 plan): its configuration, its adoption
 * ([CloudAdoptionService]), the control channel ([CloudChannel]) while adopted, and [state], which
 * `GET /install/cloud` answers and the admin-only `cloud.state` frame streams.
 *
 * Everything it does runs on its own scope, on two threads of its own named `cloud-N`, so a cloud
 * that hangs can hold none of the show's (`docs/cloud-engineering.md` §"Show safety"). Constructing
 * it starts nothing; [start], from `Application.module()`, resumes a pending adoption and connects
 * an adopted desk.
 */
class CloudService(
    private val database: Database,
    val config: CloudConfig,
    credentialStore: () -> CredentialStore,
    private val userChanges: Flow<Int>? = null,
    private val clock: () -> Instant = ::nowUtc,
    installUuid: () -> String = { transaction(database) { DaoInstall.all().first().uuid.toString() } },
    apiFactory: (baseUrl: String) -> CloudApi = { CloudApi(it) },
    private val deskIdentity: () -> DeskIdentity = { DeskIdentity.read(database) },
    authService: AuthService? = null,
) {
    private val logger = LoggerFactory.getLogger(CloudService::class.java)

    // Its own threads rather than a slice of Dispatchers.IO, which shares its workers with
    // Dispatchers.Default and so with the show: the safety test can then tell them apart by name.
    private val threadCount = AtomicInteger()
    private val executor = ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "cloud-${threadCount.incrementAndGet()}").apply { isDaemon = true }
    }.apply { allowCoreThreadTimeOut(true) }
    private val scope = CoroutineScope(SupervisorJob() + executor.asCoroutineDispatcher() + CoroutineName("cloud"))
    private val memory = CloudMemory()
    private val started = AtomicBoolean(false)
    private val startLock = Any()

    /** Held from reading a snapshot to publishing it, so an older one never lands after a newer one. */
    private val publishLock = Any()

    /** The error the row or the config gives, which outranks [CloudMemory.lastError]; kept for [publishMemory]. */
    @Volatile private var rowError: CloudErrorDto? = null

    /** How long the poll loop waits for an interval the cloud gave in seconds. Tests shorten it. */
    @Volatile internal var pollDelay: (Int) -> Duration = CloudAdoptionService.SECONDS

    /** How long the channel waits for the cloud at each step of a connection. Tests shorten it. */
    @Volatile internal var handshakeTimeout: Duration = 15.seconds

    /** Called on every step of the channel's work, on its thread; the show-safety test's hook, null otherwise. */
    @Volatile internal var onChannelWork: (() -> Unit)? = null

    private val apiDelegate = lazy { apiFactory(requireNotNull(config.baseUrl)) }

    /** No redirects (D12), and Ktor's own frame limit above the protocol's, so the channel sees an oversize frame and closes 4000. */
    private val wsClientDelegate = lazy {
        HttpClient(CIO) {
            followRedirects = false
            install(WebSockets) {
                maxFrameSize = 2L * MAX_FRAME_BYTES
                pingIntervalMillis = PING_INTERVAL.inWholeMilliseconds
            }
        }
    }

    /** The running channel and the adoption it is for; guarded by [publishLock]. */
    private var channelJob: Job? = null
    private var channelFor: Pair<String, String>? = null

    /** Bumped whenever the channel stops or starts, so a stopped one's last status is dropped. */
    @Volatile private var channelGeneration = 0
    private val unexpectedMessages = OnceLog()

    val keys = InstallKeyStore(credentialStore, installUuid)

    /** The keys the cloud signs desk access tokens with (D3). */
    val jwks = JwksCache()

    /** What may come through the cloud, as the row says (D6); read per bearer request, so in memory. */
    @Volatile var switches: RemoteSwitches = RemoteSwitches()
        private set

    /** Who tokens are for, while adopted through the configured and enabled cloud. */
    @Volatile private var trust: CloudTrust? = null

    /**
     * The origin the cloud serves this install's remote sessions from, from the last `welcome` that
     * named a usable one (D4): the Origin check allows it on a bearer request. Memory only.
     */
    @Volatile var remoteOrigin: String? = null
        private set

    /**
     * The command that launches the sidecar for a target and a build: the installed one, or the one
     * `cloud.link.binary` or `-Dlighting7.link` names ([LinkBinary]); none when there is none. Tests
     * replace it.
     */
    @Volatile internal var linkCommand: ((target: String, build: String) -> List<String>)? =
        LinkBinary.resolve(config.linkBinary)?.let { binary -> { target, build -> listOf(binary.toString(), "-target", target, "-build", build) } }

    /** Where the sidecar's PID is kept, so the next start stops one a killed desk left running. */
    @Volatile internal var linkPidFile: () -> PidFile? = { PidFile(appDataDir().resolve("link/lighting7-link.pid"), "a lighting7-link sidecar") }

    /** The public listener's address, once it has bound: the sidecar's target. Without it no sidecar runs (D7). */
    @Volatile private var linkTarget: String? = null

    /** The sidecar, supervised, while it is wanted; guarded by [publishLock]. */
    @Volatile internal var link: LinkProcess<LinkSidecar>? = null
        private set

    /** Whether a sidecar serves. Without one, `remote.no-sidecar` is announced. */
    val sidecarRunning: Boolean get() = link?.sidecar?.running == true

    @Volatile private var liveConnection: ChannelConnection? = null

    /** The remote sessions the broker opened, and the bearer check on the public listener (D4, D5). */
    val remoteSessions = RemoteSessions(database, authService, trust = { trust }, keys = { jwks.keys }, clock = clock)

    /** Links between cloud accounts and desk users, and the invites that make them (D12). */
    val links = CloudLinks(
        database = database,
        authService = authService,
        sessions = remoteSessions,
        adopted = { trust != null },
        send = { message, replyTo -> liveConnection?.send(message, replyTo) ?: false },
        clock = clock,
    )

    private val sidecarPort = object : SidecarPort {
        override val running: Boolean get() = sidecarRunning

        override fun send(message: LinkIn): Boolean = link?.sidecar?.send(message) ?: false
    }

    private val broker = RemoteBroker(
        sessions = remoteSessions,
        remoteAccess = { switches.remoteAccess },
        sidecar = { sidecarPort },
        toCloud = { message, replyTo -> liveConnection?.send(message, replyTo) ?: false },
        clock = clock,
    )

    private val channelHandler = object : ChannelHandler {
        override fun capabilities(): List<String> = this@CloudService.capabilities()

        override fun online(connection: ChannelConnection) {
            liveConnection = connection
            // A switch moved between the hello's list and this: say so now.
            connection.refreshCapabilities()
            links.online()
        }

        override fun offline(connection: ChannelConnection) {
            if (liveConnection === connection) liveConnection = null
        }

        override fun welcomed(welcome: Message.Welcome) {
            welcome.remoteOrigin?.let { remoteOrigin = usableOrigin(it) ?: remoteOrigin }
        }

        override fun jwks(jwks: Message.Jwks) = this@CloudService.jwks.update(jwks)

        override fun revoke(revoke: Message.Revoke) = remoteSessions.revoke(revoke)

        override fun rtcOffer(id: String, offer: Message.RtcOffer) = broker.offer(id, offer)

        override fun rtcIce(ice: Message.RtcIce) = broker.cloudIce(ice)

        override fun rtcClose(close: Message.RtcClose) = broker.cloudClose(close)

        override fun linkAccepted(id: String, accepted: Message.LinkAccepted) = links.accepted(id, accepted)

        override fun linkRefused(refused: Message.LinkRefused) = links.refused(refused)
    }

    private val adoption = CloudAdoptionService(
        database = database,
        config = config,
        keys = keys,
        api = { apiDelegate.value },
        scope = scope,
        memory = memory,
        clock = clock,
        pollDelay = { pollDelay },
        changed = ::refresh,
        memoryChanged = ::publishMemory,
        usersChanged = { users -> users.forEach { authService?.announceUserChanged(it) } },
    )

    // Never fails construction: a desk whose cloud state cannot be read still boots, and says so.
    private val _state = MutableStateFlow(
        runCatching { snapshot(adoption.row()) }.getOrElse {
            logger.warn("Couldn't read the cloud state", it)
            CloudStateDto(
                configured = config.configured,
                enabled = config.enabled,
                lastError = CloudErrorDto(CloudErrorCodes.STATE_UNREADABLE, "This desk couldn't read its cloud state: ${it.message}"),
            )
        },
    )
    val state: StateFlow<CloudStateDto> = _state.asStateFlow()

    init {
        config.problem?.let { logger.warn("Cloud not configured: {}", it) }
    }

    /**
     * Resume a pending adoption and follow account changes, once a sidecar a killed desk left running
     * has been stopped. Idempotent.
     */
    fun start() {
        synchronized(startLock) {
            if (started.get()) return
            // Before `started`, so no sidecar of this run's own can have launched; under no lock any other
            // path takes, since it may wait 3 s.
            linkCommand?.invoke("", "")?.firstOrNull()?.let { binary -> linkPidFile()?.sweepOrphan(Path.of(binary)) }
            started.set(true)
        }
        scope.launch {
            runCatching { adoption.resume() }.onFailure { logger.warn("Couldn't resume the cloud adoption", it) }
            refresh()
        }
        userChanges?.let { flow -> scope.launch { flow.collect { refresh() } } }
    }

    /** Start adopting this desk for the desk account [userId] (D5, D6). */
    suspend fun startAdoption(userId: Int) = adoption.start(userId)

    suspend fun cancelAdoption() = adoption.cancel()

    suspend fun unadopt(force: Boolean) = adoption.unadopt(force)

    /** The cloud removed this install: forget it and say so. */
    suspend fun revoked(installId: String? = null) = adoption.revoked(installId)

    /** The state as the database holds it now. */
    fun current(): CloudStateDto {
        refresh()
        return state.value
    }

    private fun refresh(): Unit = synchronized(publishLock) {
        runCatching {
            val row = adoption.row()
            reconcileChannel(row)
            reconcileRemote(row)
            reconcileLink()
            snapshot(row)
        }
            .onSuccess { _state.value = it }
            .onFailure { logger.warn("Couldn't read the cloud state", it) }
    }

    /** Republish after a change to [memory] alone, without reading the database (D11). */
    private fun publishMemory(): Unit = synchronized(publishLock) {
        val channel = memory.channel
        _state.value = _state.value.copy(
            link = linkStatus(),
            connection = channel.connection,
            retryAt = channel.retryAt?.toIsoUtc(),
            lastError = rowError ?: memory.lastError ?: channel.lastError,
            lastOnlineAt = channel.lastOnlineAt?.toIsoUtc(),
            notice = memory.notice,
            noticeAt = memory.noticeAt?.toIsoUtc(),
        )
    }

    /**
     * Run the channel exactly while the desk is adopted through the configured cloud and the cloud
     * is enabled (D13), once [start] has run. The key is read by each attempt until a read succeeds. Called on every refresh; must hold [publishLock].
     */
    private fun reconcileChannel(row: CloudConnectionRow?) {
        val wanted = row?.takeIf {
            started.get() && config.enabled && it.state == CloudConnectionState.ADOPTED && it.baseUrl == config.baseUrl && it.installId != null
        }
        val target = wanted?.let { it.baseUrl to it.installId!! }
        if (target != null && target == channelFor && channelJob?.isActive == true) return
        stopChannel()
        if (target == null) return
        val generation = ++channelGeneration
        // Read per attempt until it succeeds: a keychain still locked at boot is retried with the connection.
        var key: PrivateKey? = null
        val connection = ChannelTarget(target.first, target.second) { key ?: keys.load().also { key = it } }
        val identity = deskIdentity()
        val channel = CloudChannel(
            clock = clock,
            publish = { status -> publishChannel(generation, status) },
            onRevoked = { revoked(connection.installId) },
            connectOnce = { onOnline ->
                ChannelConnection(
                    client = wsClientDelegate.value,
                    target = connection,
                    identity = identity,
                    clock = clock,
                    handshakeTimeout = handshakeTimeout,
                    unexpected = unexpectedMessages,
                    noteThread = { onChannelWork?.invoke() },
                    handler = channelHandler,
                ).connect(onOnline)
            },
        )
        channelFor = target
        channelJob = scope.launch {
            try {
                channel.run()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("The cloud channel stopped", e)
                publishChannel(generation, ChannelStatus(CloudConnectionStatus.OFF, lastError = CloudErrorDto(CloudErrorCodes.DISCONNECTED, "The connection to the cloud stopped: ${e.message}")))
            }
        }
    }

    /**
     * Follow the row's switches and the adoption into memory (D6): a desk no longer adopted, or with
     * *Remote sessions* turned off, ends every session; a change of what is announced is announced.
     * Must hold [publishLock].
     */
    private fun reconcileRemote(row: CloudConnectionRow?) {
        val adopted = row?.takeIf {
            config.enabled && it.state == CloudConnectionState.ADOPTED && it.baseUrl == config.baseUrl &&
                it.installId != null && it.orgId != null
        }
        switches = adopted?.let { RemoteSwitches(it.remoteAccess, it.mcpAccess, it.approveSessions) } ?: RemoteSwitches()
        val next = adopted?.let { CloudTrust(it.installId!!, it.orgId!!, it.remoteAccess) }
        val previous = trust
        trust = next
        when {
            next == null -> {
                if (previous != null) jwks.clear()
                remoteSessions.endAll(RemoteSessionEnd.UNADOPTED)
                remoteOrigin = null
                links.forgetPending()
            }
            previous != null && previous.installId != next.installId -> remoteSessions.endAll(RemoteSessionEnd.UNADOPTED)
            !next.remoteAccess -> remoteSessions.endAll(RemoteSessionEnd.REMOTE_ACCESS_OFF)
        }
        liveConnection?.refreshCapabilities()
    }

    /** What the desk announces (D6): that it takes remote sessions, and the state entries for each door's switch and the sidecar. */
    fun capabilities(): List<String> = buildList {
        add(Capability.REMOTE_V1)
        val now = switches
        if (!now.remoteAccess) add(Capability.REMOTE_OFF)
        if (!now.mcpAccess) add(Capability.MCP_OFF)
        if (!sidecarRunning) add(Capability.REMOTE_NO_SIDECAR)
    }

    /**
     * The public listener bound at [host]:[port] (D7): the sidecar's target, so one may run. Called once,
     * by `startPublicListener`; a listener that is off or failed to bind never calls it.
     */
    fun publicListenerBound(host: String, port: Int) {
        val loopback = when (host) {
            "0.0.0.0", "", "::", "[::]" -> "127.0.0.1"
            else -> if (host.contains(':') && !host.startsWith("[")) "[$host]" else host
        }
        linkTarget = "http://$loopback:$port"
        refresh()
    }

    /**
     * Run the sidecar exactly while the channel runs, *Remote sessions* is on, the public listener has
     * bound and there is a sidecar to run (D11). Must hold [publishLock].
     */
    private fun reconcileLink() {
        val command = linkCommand
        val target = linkTarget
        val wanted = channelJob?.isActive == true && switches.remoteAccess && command != null && target != null
        val current = link
        if (wanted && current == null) {
            val build = BuildInfo.current.let { info -> info.version + (info.shortSha?.let { "+$it" } ?: "") }
            link = LinkProcess(scope = scope, onStatus = ::publishMemory, pidFile = linkPidFile()) { supervisor ->
                LinkSidecar(
                    command = command(target, build),
                    scope = scope,
                    newSecret = remoteSessions::newLinkSecret,
                    onMessage = broker::fromSidecar,
                    onRunning = { running ->
                        supervisor.serving(running)
                        sidecarChanged(running)
                    },
                    onEnded = supervisor::ended,
                    launcher = supervisor::launch,
                )
            }.also { it.start() }
        } else if (!wanted && current != null) {
            link = null
            current.stop()
        }
    }

    private fun linkStatus(): LinkStatus = link?.status ?: LinkStatus.OFF

    private fun sidecarChanged(running: Boolean) {
        if (!running) {
            remoteSessions.clearLinkSecret()
            broker.sidecarStopped()
        }
        liveConnection?.refreshCapabilities()
    }

    /**
     * Set what may come through the cloud (D6). Only the desk's own listener's admin route calls this:
     * no message from the cloud writes these. Null leaves a switch as it is.
     */
    fun setSwitches(remoteAccess: Boolean? = null, mcpAccess: Boolean? = null, approveSessions: Boolean? = null) {
        val updated = transaction(database) {
            DaoCloudConnections.update({ DaoCloudConnections.state eq CloudConnectionState.ADOPTED }) {
                remoteAccess?.let { v -> it[DaoCloudConnections.remoteAccess] = v }
                mcpAccess?.let { v -> it[DaoCloudConnections.mcpAccess] = v }
                approveSessions?.let { v -> it[DaoCloudConnections.approveSessions] = v }
            }
        }
        if (updated == 0) {
            throw CloudException(io.ktor.http.HttpStatusCode.Conflict, CloudErrorCodes.NOT_ADOPTED, "This desk isn't adopted.")
        }
        refresh()
    }

    /** Must hold [publishLock]. */
    private fun stopChannel() {
        if (channelJob == null && memory.channel.connection == CloudConnectionStatus.OFF && memory.channel.lastError == null) return
        channelJob?.cancel()
        channelJob = null
        channelFor = null
        channelGeneration++
        memory.channel = ChannelStatus(CloudConnectionStatus.OFF)
    }

    private fun publishChannel(generation: Int, status: ChannelStatus): Unit = synchronized(publishLock) {
        if (generation != channelGeneration) return
        memory.channel = status
        publishMemory()
    }

    private fun snapshot(row: CloudConnectionRow?): CloudStateDto {
        val error = when {
            config.problem != null -> CloudErrorDto(CloudErrorCodes.URL_INVALID, config.problem)
            row != null && config.baseUrl != null && row.baseUrl != config.baseUrl ->
                CloudErrorDto(CloudErrorCodes.URL_CHANGED, "This desk was adopted through ${row.baseUrl}, which cloud.baseUrl no longer names.")
            row?.state == CloudConnectionState.ADOPTED && !keys.exists() ->
                CloudErrorDto(CloudErrorCodes.KEY_MISSING, "This desk's install key is missing: remove it from the cloud and adopt it again.")
            else -> null
        }
        rowError = error
        val adopting = row?.takeIf { it.state == CloudConnectionState.ADOPTING }?.let {
            CloudAdoptionDto(
                userCode = it.userCode.orEmpty(),
                verificationUri = it.verificationUri.orEmpty(),
                verificationUriComplete = it.verificationUriComplete.orEmpty(),
                expiresAt = it.adoptionExpiresAt?.toIsoUtc().orEmpty(),
                fingerprint = it.fingerprint,
            )
        }
        val adopted = row?.takeIf { it.state == CloudConnectionState.ADOPTED }?.let { r ->
            val (displayName, email) = r.adoptedBy?.let(::accountOf) ?: (null to null)
            CloudAdoptedDto(
                installId = r.installId.orEmpty(),
                orgName = r.orgName.orEmpty(),
                adoptedAt = r.adoptedAt?.toIsoUtc().orEmpty(),
                adoptedBy = displayName,
                linkedEmail = email,
                fingerprint = r.fingerprint,
            )
        }
        val channel = memory.channel
        return CloudStateDto(
            configured = config.configured,
            enabled = config.enabled,
            adoption = adopting,
            adopted = adopted,
            link = linkStatus(),
            connection = channel.connection,
            retryAt = channel.retryAt?.toIsoUtc(),
            lastError = error ?: memory.lastError ?: channel.lastError,
            lastOnlineAt = channel.lastOnlineAt?.toIsoUtc(),
            notice = memory.notice,
            noticeAt = memory.noticeAt?.toIsoUtc(),
        )
    }

    /** The display name of desk account [userId] and the email of its cloud link, either null when absent. */
    private fun accountOf(userId: Int): Pair<String?, String?> = transaction(database) {
        val name = DaoUsers.selectAll().where { DaoUsers.id eq userId }.firstOrNull()?.get(DaoUsers.displayName)
        val email = DaoCloudIdentityLinks.selectAll().where { DaoCloudIdentityLinks.user eq userId }
            .firstOrNull()?.get(DaoCloudIdentityLinks.email)
        name to email
    }

    fun close() {
        // Sessions first, so a clean stop records them as DESK_STOPPED rather than as the sidecar's going.
        remoteSessions.close()
        synchronized(publishLock) { link?.also { link = null } }?.stop()
        scope.cancel()
        if (apiDelegate.isInitialized()) runCatching { apiDelegate.value.close() }
        if (wsClientDelegate.isInitialized()) runCatching { wsClientDelegate.value.close() }
        executor.shutdown()
    }

    companion object {
        private const val THREADS = 2

        /** [text] as an origin the Origin check can compare, if it is an https one (or http to this machine) and nothing more. */
        internal fun usableOrigin(text: String): String? {
            val origin = originOf(text)?.lowercase() ?: return null
            if (origin != text.trimEnd('/').lowercase()) return null
            return origin.takeIf { CloudConfig.checkBaseUrl(it) is BaseUrlCheck.Ok }
        }

        /** WebSocket pings, so a cloud that vanished without closing is noticed within a ping and its timeout. */
        private val PING_INTERVAL = 25.seconds
    }
}
