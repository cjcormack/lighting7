package uk.me.cormack.lighting7.sync.auth

import uk.me.cormack.lighting7.sync.GitCredentials
import java.net.URI

/**
 * Picks JGit credentials for a repo. A repo of the configured Lighting7 Cloud is [cloud]'s alone
 * (cloud-p2 plan D3): its install-signed git token, else the developer token, else
 * [CloudNotAdoptedException]. A GitHub repo is refused with [GitHubRetiredException] (D8), and any
 * other repo with [NotACloudRepoException].
 *
 * [localRepos] lets a `file:` repo through with no credentials, which is how the tests stand a bare
 * repo in for a remote, and counts it as a GitHub repo for the move; the desk itself never sets it.
 */
class AuthResolver(
    /** The cloud's source, null when the resolver knows no cloud. */
    val cloud: CloudCredentialSource?,
    private val localRepos: Boolean = false,
) {
    /** Whether [repoUrl] is a repo of the configured cloud. */
    fun isCloudRepo(repoUrl: String): Boolean = cloud?.handles(repoUrl) == true

    /** Whether the desk syncs with [repoUrl]: a repo of the configured cloud, or a local one where those are allowed. */
    fun canSyncWith(repoUrl: String): Boolean = isCloudRepo(repoUrl) || isLocal(repoUrl)

    /** Whether [repoUrl] is a GitHub repo, which moves to the cloud: one on `github.com`, or a local one where those are allowed. */
    fun isGitHubRepo(repoUrl: String): Boolean = !isCloudRepo(repoUrl) && (isLocal(repoUrl) || hostOf(repoUrl) in GITHUB_HOSTS)

    /** Why the cloud's git answered [status] for [repoUrl], in its words; null for a URL that is not the cloud's. */
    suspend fun cloudRefusal(repoUrl: String, status: Int): CloudRefusal? =
        cloud?.takeIf { it.handles(repoUrl) }?.refusal(repoUrl, status)

    fun resolveFor(repoUrl: String): GitCredentials {
        cloud?.takeIf { it.handles(repoUrl) }?.let { return it.resolve(repoUrl) }
        if (isLocal(repoUrl)) return GitCredentials(LOCAL_USER, LOCAL_USER)
        if (isGitHubRepo(repoUrl)) {
            throw GitHubRetiredException(
                "This project still syncs with GitHub, which this desk does not: move it to the cloud from its Sync tab.",
            )
        }
        throw NotACloudRepoException(notACloudRepoMessage())
    }

    /** What the desk answers a repo that is neither its cloud's nor GitHub's. */
    fun notACloudRepoMessage(): String {
        val base = cloud?.baseUrl
        return if (base != null) {
            "This repository is not one of this desk's cloud ($base), so the desk cannot sync with it."
        } else {
            "This desk has no cloud configured (cloud.baseUrl in local.conf), so it cannot sync with this repository."
        }
    }

    private fun isLocal(repoUrl: String): Boolean = localRepos && repoUrl.startsWith("file:")

    private companion object {
        const val LOCAL_USER = "local"
        val GITHUB_HOSTS = setOf("github.com", "www.github.com")

        /** [url]'s host, lower-cased: an https or ssh URL's, or an scp-style `git@host:path`'s. */
        fun hostOf(url: String): String? {
            val trimmed = url.trim()
            if ("://" in trimmed) {
                return runCatching { URI(trimmed).host }.getOrNull()?.lowercase()
            }
            val scp = Regex("^[^@/]+@([^:/]+):").find(trimmed) ?: return null
            return scp.groupValues[1].lowercase()
        }
    }
}

/** A GitHub repo, which the desk does not sync with: answered `GITHUB_RETIRED`. */
class GitHubRetiredException(message: String) : RuntimeException(message)

/** A repo neither of this desk's cloud nor GitHub's: answered `NOT_A_CLOUD_REPO`. */
class NotACloudRepoException(message: String) : RuntimeException(message)
