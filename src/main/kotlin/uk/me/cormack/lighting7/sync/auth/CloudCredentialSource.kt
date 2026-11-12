package uk.me.cormack.lighting7.sync.auth

import uk.me.cormack.lighting7.cloud.CloudApi
import uk.me.cormack.lighting7.cloud.CloudCall
import uk.me.cormack.lighting7.cloud.CloudRepoUrls
import uk.me.cormack.lighting7.cloud.InstallSigner
import uk.me.cormack.lighting7.cloud.RepoReader
import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.cloud.protocol.InstallGitTokens
import uk.me.cormack.lighting7.sync.GitCredentials
import java.time.Instant

/**
 * Git credentials for a cloud repo (cloud-p2 plan D3). An adopted desk mints an `l7git` token with its
 * install key, a fresh one each time it is asked, which the engine does once per sync run; an
 * unadopted desk uses the developer token it was started with ([devToken], `LIGHTING7_CLOUD_TOKEN`);
 * otherwise [CloudNotAdoptedException]. Neither needs the control channel.
 *
 * [baseUrl] is `cloud.baseUrl`, null when no cloud is configured; [enabled] is `cloud.enabled`.
 * [signer] is the adoption's install id and key, null unless adopted through that cloud, and [adopted]
 * whether it would answer, without loading the key; [api] answers
 * the repo API, asked only to put a git refusal in the cloud's words ([refusal]).
 */
class CloudCredentialSource(
    private val baseUrl: String?,
    private val enabled: Boolean,
    private val signer: () -> InstallSigner?,
    private val adopted: () -> Boolean,
    private val devToken: String?,
    private val api: () -> CloudApi?,
    private val clock: () -> Instant = Instant::now,
) {
    /** Whether [url] is a repo of the configured cloud, whose credentials are this source's. */
    fun handles(url: String): Boolean = CloudRepoUrls.isCloudRepo(url, baseUrl)

    /** [url]'s org and repo ids when it is a repo of the configured cloud. */
    fun repoOf(url: String): CloudRepoUrls.CloudRepo? = CloudRepoUrls.parse(url, baseUrl)

    /** The adoption's signer, which alone may make a repo; null when not adopted or the cloud is off. */
    fun installSigner(): InstallSigner? = if (enabled) signer() else null

    /** Who may read the org's repos: the adoption, else the developer token; null when neither. */
    fun repoReader(): RepoReader? = if (!enabled) null else signer()?.let { RepoReader.Install(it) } ?: devToken?.let { RepoReader.Developer(it) }

    /** The cloud's API, null when no cloud is configured. */
    fun api(): CloudApi? = if (baseUrl != null) api.invoke() else null

    /** Whether [resolve] would answer now. */
    fun canResolve(): Boolean = enabled && (devToken != null || adopted())

    fun resolve(url: String): GitCredentials {
        check(handles(url)) { "Not a repo of this desk's cloud" }
        if (!enabled) throw CloudNotAdoptedException("The cloud is switched off on this desk (cloud.enabled = false in local.conf).")
        signer()?.let { return GitCredentials(InstallGitTokens.BASIC_USER, InstallGitTokens.mint(it.key, it.installId, baseUrl!!, clock())) }
        devToken?.let { return GitCredentials(DEV_TOKEN_USER, it) }
        throw CloudNotAdoptedException("This desk is not adopted by Lighting7 Cloud: adopt it on the Cloud tab to sync with its repos.")
    }

    /**
     * Why the cloud's git answered [status] (403 or 404) for [url], in the cloud's words: the repo API is
     * asked for the repo, and its refusal (`REPO_NOT_FOUND`, `SYNC_OFF`, `SYNC_READ_ONLY`) is the answer.
     * Without one, a 404 is `REPO_NOT_FOUND` and a 403 has no code.
     */
    suspend fun refusal(url: String, status: Int): CloudRefusal {
        val repo = repoOf(url)
        val reader = repoReader()
        val asked = if (repo != null && reader != null) api()?.getRepo(reader, repo.repoId) else null
        val error = (asked as? CloudCall.Refused)?.error
        if (error != null && error.code in EXPLAINED) return CloudRefusal(error.code, error.message)
        return when (status) {
            404 -> CloudRefusal(ErrorCode.REPO_NOT_FOUND, "The cloud has no such repo: it was deleted, or belongs to another org.")
            else -> CloudRefusal(null, "The cloud refused this desk access to the repo.")
        }
    }

    companion object {
        /** The HTTP Basic user a developer token is sent as; the cloud reads only the password. */
        const val DEV_TOKEN_USER: String = "x-dev-token"

        /** The environment variable an unadopted desk's developer token is read from, once, at startup. */
        const val DEV_TOKEN_ENV: String = "LIGHTING7_CLOUD_TOKEN"

        private val EXPLAINED = setOf(ErrorCode.REPO_NOT_FOUND, ErrorCode.SYNC_OFF, ErrorCode.SYNC_READ_ONLY)
    }
}

/** What the cloud said refusing a git request: a protocol [code], null when it gave none, and its words. */
data class CloudRefusal(val code: String?, val message: String)

/** A cloud repo, and no way to authenticate to the cloud: answered `CLOUD_NOT_ADOPTED`. */
class CloudNotAdoptedException(message: String) : RuntimeException(message)
