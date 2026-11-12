package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.cloud.CloudCall
import uk.me.cormack.lighting7.cloud.RepoCreated
import uk.me.cormack.lighting7.cloud.protocol.CreateRepoRequest
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.RepoDto
import uk.me.cormack.lighting7.cloud.protocol.RepoKind
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.sync.auth.CloudCredentialSource
import java.util.UUID

/**
 * The org's cloud repos as the Sync tab, *Import from the cloud* and onboarding use them (cloud-p2 plan
 * D2, D6): making a project's repo, listing the org's, and the names a desk shows for them, cached in
 * `sync_configs.repo_name`. Every refusal is a [SyncException]: `CLOUD_NOT_ADOPTED` without a way to
 * reach the cloud, `SYNC_OFF`, `AUTH_FAILED`, `REPO_NOT_FOUND`, `CLOUD_REFUSED` for any other refusal in
 * the cloud's words, and `CLOUD_UNREACHABLE` only when it does not answer.
 */
class CloudRepos(private val database: Database, private val cloud: CloudCredentialSource?) {

    /** Make [projectUuid]'s repo, or find the one it has (`REPO_EXISTS`); only an adopted desk may. */
    suspend fun create(projectUuid: UUID, kind: ProjectKind, name: String): RepoCreated {
        val source = cloud ?: throw notConfigured()
        val api = source.api() ?: throw notConfigured()
        val signer = source.installSigner()
            ?: throw SyncException(SyncErrorCode.CLOUD_NOT_ADOPTED, "Adopt this desk on the Cloud tab to sync it to the cloud.")
        val repoKind = if (kind == ProjectKind.VENUE) RepoKind.VENUE else RepoKind.SHOW
        return answer(api.createRepo(signer, CreateRepoRequest(projectUuid.toString(), repoKind, name)))
    }

    /** [list], refused as [create] would be on a desk that cannot make a repo. */
    suspend fun listForCreate(): List<CloudRepoRow> {
        cloud?.installSigner()
            ?: throw if (cloud == null) notConfigured() else SyncException(SyncErrorCode.CLOUD_NOT_ADOPTED, "Adopt this desk on the Cloud tab to sync it to the cloud.")
        return list()
    }

    /** The org's repos, each with whether a project here syncs it, in the cloud's order; caches their names. */
    suspend fun list(): List<CloudRepoRow> {
        val source = cloud ?: throw notConfigured()
        val api = source.api() ?: throw notConfigured()
        val reader = source.repoReader()
            ?: throw SyncException(SyncErrorCode.CLOUD_NOT_ADOPTED, "Adopt this desk on the Cloud tab to list its org's repos.")
        val repos = answer(api.listRepos(reader)).repos
        return withContext(Dispatchers.IO) {
            transaction(database) {
                val configs = DaoSyncConfig.all().filter { !it.repoUrl.isNullOrBlank() }
                repos.map { repo ->
                    val config = configs.firstOrNull { sameRepo(source, it.repoUrl!!, repo.url) }
                    if (config != null && config.repoName != repo.name) config.repoName = repo.name
                    CloudRepoRow.of(repo, config?.project?.id?.value)
                }
            }
        }
    }

    /** [url]'s name as the cloud has it, or null when it is not a cloud repo or the cloud does not say. */
    suspend fun nameOf(url: String): String? {
        val source = cloud?.takeIf { it.handles(url) } ?: return null
        val api = source.api() ?: return null
        val reader = source.repoReader() ?: return null
        val repoId = source.repoOf(url)?.repoId ?: return null
        return (api.getRepo(reader, repoId) as? CloudCall.Ok)?.value?.name
    }

    /**
     * Ask the cloud for [url]'s name and cache it, within [NAME_TIMEOUT_MS]; null, and nothing cached,
     * when it is not a cloud repo or the cloud does not say in time. Never throws: a name is a nicety
     * on a write that has already happened.
     */
    suspend fun refreshName(url: String): String? = try {
        withTimeout(NAME_TIMEOUT_MS) { nameOf(url) }?.also { withContext(Dispatchers.IO) { recordName(url, it) } }
    } catch (e: TimeoutCancellationException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.warn("Could not refresh the cloud repo name for {}", url, e)
        null
    }

    /** Cache [name] for every project here syncing [url]. */
    fun recordName(url: String, name: String?) {
        if (name.isNullOrBlank()) return
        val source = cloud ?: return
        transaction(database) {
            DaoSyncConfig.all()
                .filter { !it.repoUrl.isNullOrBlank() && sameRepo(source, it.repoUrl!!, url) && it.repoName != name }
                .forEach { it.repoName = name }
        }
    }

    private fun sameRepo(source: CloudCredentialSource, a: String, b: String): Boolean {
        val left = source.repoOf(a) ?: return a == b
        return left == source.repoOf(b)
    }

    private fun <T> answer(call: CloudCall<T>): T = when (call) {
        is CloudCall.Ok -> call.value
        is CloudCall.Unreachable -> throw SyncException(
            SyncErrorCode.CLOUD_UNREACHABLE,
            "Lighting7 Cloud did not answer (${call.message}). Try again once this desk can reach it.",
        )
        is CloudCall.Refused -> {
            val words = call.error?.message?.trim()?.trimEnd('.')?.takeIf { it.isNotEmpty() }
            val code = when {
                call.error?.code == ErrorCode.SYNC_OFF -> SyncErrorCode.SYNC_OFF
                call.status == 401 || call.status == 403 -> SyncErrorCode.AUTH_FAILED
                call.error?.code == ErrorCode.REPO_NOT_FOUND || call.status == 404 -> SyncErrorCode.REPO_NOT_FOUND
                else -> SyncErrorCode.CLOUD_REFUSED
            }
            val message = when (code) {
                SyncErrorCode.AUTH_FAILED -> "The cloud refused this desk${words?.let { ": $it" } ?: ""}. Check the desk is still adopted on the Cloud tab."
                SyncErrorCode.REPO_NOT_FOUND -> CloudSyncCodes.explained(code, words ?: "The cloud has no such repo")
                SyncErrorCode.CLOUD_REFUSED -> "The cloud refused (HTTP ${call.status}${words?.let { ": $it" } ?: ""})."
                else -> CloudSyncCodes.explained(code, words ?: "The org does not have cloud sync")
            }
            throw SyncException(code, message)
        }
    }

    private fun notConfigured() = SyncException(
        SyncErrorCode.CLOUD_NOT_ADOPTED,
        "No Lighting7 Cloud is configured on this desk (cloud.baseUrl in local.conf).",
    )

    private companion object {
        const val NAME_TIMEOUT_MS = 3_000L
        val log = LoggerFactory.getLogger(CloudRepos::class.java)
    }
}

/** One of the org's repos as `GET /cloud-sync/repos` answers it (cloud-p2 plan D6). */
@Serializable
data class CloudRepoRow(
    val repoId: String,
    val url: String,
    val name: String,
    /** `SHOW` or `VENUE`: onboarding imports venues first. */
    val kind: String,
    val projectUuid: String,
    val headSha: String? = null,
    val sizeBytes: Long,
    val formatVersion: Int? = null,
    val lastPushAt: String? = null,
    /** Whether a project on this desk already syncs it, and which. */
    val syncedHere: Boolean,
    val projectId: Int? = null,
) {
    companion object {
        fun of(repo: RepoDto, projectId: Int?) = CloudRepoRow(
            repoId = repo.repoId, url = repo.url, name = repo.name, kind = repo.kind, projectUuid = repo.projectUuid,
            headSha = repo.headSha, sizeBytes = repo.sizeBytes, formatVersion = repo.formatVersion,
            lastPushAt = repo.lastPushAt, syncedHere = projectId != null, projectId = projectId,
        )
    }
}
