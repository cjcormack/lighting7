package uk.me.cormack.lighting7.cloud

import java.net.URI

/**
 * Which repo URLs are the cloud's (cloud-p2 plan D2): `<cloud origin>/git/<orgId>/<repoId>.git`, by
 * ids, so a rename in the portal moves nothing on a desk. A URL is one when its scheme, host and port
 * are `cloud.baseUrl`'s and its path has that shape; anything else (a GitHub URL, a `file://` remote)
 * is not, and syncs as it always has. A URL carrying credentials, a query or a fragment is never one.
 */
object CloudRepoUrls {
    data class CloudRepo(val orgId: String, val repoId: String)

    private val PATH = Regex("""/git/([A-Za-z0-9_-]{1,64})/([A-Za-z0-9_-]{1,64})\.git""")

    /** [url]'s org and repo when it is a repo of the cloud at [baseUrl], else null. */
    fun parse(url: String, baseUrl: String?): CloudRepo? {
        val base = baseUrl?.let(::uriOrNull) ?: return null
        val repo = uriOrNull(url) ?: return null
        if (repo.rawUserInfo != null || repo.rawQuery != null || repo.rawFragment != null) return null
        if (origin(repo) == null || origin(repo) != origin(base)) return null
        val match = PATH.matchEntire(repo.rawPath ?: return null) ?: return null
        return CloudRepo(match.groupValues[1], match.groupValues[2])
    }

    fun isCloudRepo(url: String, baseUrl: String?): Boolean = parse(url, baseUrl) != null

    private fun uriOrNull(text: String): URI? = runCatching { URI(text) }.getOrNull()

    /** Scheme, host and port, a default port spelled as one. */
    private fun origin(uri: URI): Triple<String, String, Int>? {
        val scheme = uri.scheme?.lowercase()?.takeIf { it == "https" || it == "http" } ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = uri.port.takeUnless { it == -1 } ?: if (scheme == "https") 443 else 80
        return Triple(scheme, host, port)
    }
}
