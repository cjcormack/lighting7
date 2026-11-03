package uk.me.cormack.lighting7.cloud

import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CloudConnectionState
import uk.me.cormack.lighting7.models.DaoCloudIdentityLinks
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoUsers
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.sync.auth.CredentialStore
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration

/**
 * The desk's side of Lighting7 Cloud (cloud-p0 plan): its configuration, its adoption
 * ([CloudAdoptionService]) and [state], which `GET /install/cloud` answers and the admin-only
 * `cloud.state` frame streams.
 *
 * Everything it does runs on its own scope, on [Dispatchers.IO] limited to two threads, so a
 * cloud that hangs can hold none of the show's (`docs/cloud-engineering.md` §"Show safety").
 * Constructing it starts nothing; [start] resumes a pending adoption, from `Application.module()`.
 */
class CloudService(
    private val database: Database,
    val config: CloudConfig,
    credentialStore: () -> CredentialStore,
    private val userChanges: Flow<Int>? = null,
    private val clock: () -> Instant = Instant::now,
    installUuid: () -> String = { transaction(database) { DaoInstall.all().first().uuid.toString() } },
    apiFactory: (baseUrl: String) -> CloudApi = { CloudApi(it) },
) {
    private val logger = LoggerFactory.getLogger(CloudService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(2) + CoroutineName("cloud"))
    private val memory = CloudMemory()
    private val started = AtomicBoolean(false)

    /** Held from reading a snapshot to publishing it, so an older one never lands after a newer one. */
    private val publishLock = Any()

    /** The error the row or the config gives, which outranks [CloudMemory.lastError]; kept for [publishMemory]. */
    @Volatile private var rowError: CloudErrorDto? = null

    /** How long the poll loop waits for an interval the cloud gave in seconds. Tests shorten it. */
    @Volatile internal var pollDelay: (Int) -> Duration = CloudAdoptionService.SECONDS

    private val apiDelegate = lazy { apiFactory(requireNotNull(config.baseUrl)) }

    val keys = InstallKeyStore(credentialStore, installUuid)

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
    )

    // Never fails construction: a desk whose cloud state cannot be read still boots, and says so.
    private val _state = MutableStateFlow(
        runCatching { snapshot() }.getOrElse {
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

    /** Resume a pending adoption and follow account changes. Idempotent. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
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
    suspend fun revoked() = adoption.revoked()

    /** The state as the database holds it now. */
    fun current(): CloudStateDto {
        refresh()
        return state.value
    }

    private fun refresh(): Unit = synchronized(publishLock) {
        runCatching { snapshot() }
            .onSuccess { _state.value = it }
            .onFailure { logger.warn("Couldn't read the cloud state", it) }
    }

    /** Republish after a change to [memory] alone, without reading the database (D11). */
    private fun publishMemory(): Unit = synchronized(publishLock) {
        _state.value = _state.value.copy(lastError = rowError ?: memory.lastError, notice = memory.notice)
    }

    private fun snapshot(): CloudStateDto {
        val row = adoption.row()
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
        return CloudStateDto(
            configured = config.configured,
            enabled = config.enabled,
            adoption = adopting,
            adopted = adopted,
            lastError = error ?: memory.lastError,
            notice = memory.notice,
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
        scope.cancel()
        if (apiDelegate.isInitialized()) runCatching { apiDelegate.value.close() }
    }
}
