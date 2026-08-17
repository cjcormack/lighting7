package uk.me.cormack.lighting7.update

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.io.Closeable

private val logger = LoggerFactory.getLogger("GitHubReleaseClient")

/** Outcome of asking GitHub what the latest release is. */
sealed interface ReleaseCheckResult {
    data class Found(val release: ReleaseInfo, val etag: String?) : ReleaseCheckResult

    /** 304 — nothing changed since the stored ETag. The cached release still stands. */
    data object NotModified : ReleaseCheckResult

    /** 404 — the repo has no published release yet. Not an error; there is simply nothing. */
    data object NoRelease : ReleaseCheckResult

    data class Failed(val code: UpdateErrorCode, val message: String, val retryAfterMs: Long? = null) :
        ReleaseCheckResult
}

/**
 * Reads `/releases/latest` and downloads release assets.
 *
 * Deliberately its own [HttpClient] rather than reusing
 * [uk.me.cormack.lighting7.sync.auth.oauth.OAuthGitHubClient]: that one is constructed only when
 * `sync.oauth.github.clientId`/`clientSecret` are configured, so on a desk that never connected
 * to GitHub it is null — and the update check must work regardless of whether cloud sync was
 * ever set up. The *style* is borrowed (CIO, `expectSuccess = false`, the API-version header);
 * the instance is not.
 *
 * Unauthenticated throughout. No token is sent even if one exists: `releases/latest` on a public
 * repo needs none, and attaching a user's credential to a redirect that leaves api.github.com
 * for objects.githubusercontent.com would leak it.
 */
class GitHubReleaseClient(
    private val repo: String,
    private val apiBase: String,
) : Closeable {

    private val client = HttpClient(CIO) {
        // Errors are inspected, not thrown: a rate-limit response is a legitimate answer to
        // "what's the latest version", not an exception.
        expectSuccess = false
        // Asset download URLs 302 from github.com to objects.githubusercontent.com. CIO follows
        // redirects by default; this makes the reliance explicit rather than incidental.
        followRedirects = true
    }

    suspend fun fetchLatestRelease(etag: String?): ReleaseCheckResult {
        val url = "$apiBase/repos/$repo/releases/latest"
        return try {
            val response = client.get(url) {
                header("Accept", "application/vnd.github+json")
                header("X-GitHub-Api-Version", "2022-11-28")
                // Conditional request: a 304 is cheap and (per GitHub's docs) does not count
                // against the primary rate limit.
                etag?.let { header("If-None-Match", it) }
            }

            when (response.status) {
                HttpStatusCode.NotModified -> ReleaseCheckResult.NotModified
                HttpStatusCode.NotFound -> ReleaseCheckResult.NoRelease
                HttpStatusCode.OK -> {
                    val parsed = parseRelease(response.bodyAsText())
                        ?: return ReleaseCheckResult.Failed(
                            UpdateErrorCode.NETWORK,
                            "GitHub returned a release payload this version could not read.",
                        )
                    ReleaseCheckResult.Found(parsed, response.headers["ETag"])
                }
                HttpStatusCode.Forbidden, HttpStatusCode.TooManyRequests -> {
                    // 403 with the remaining count at zero is the rate limit, not a permissions
                    // problem. Reported as a distinct code so the UI can say "try later" rather
                    // than "something went wrong".
                    val remaining = response.headers["X-RateLimit-Remaining"]?.toLongOrNull()
                    val resetAtSec = response.headers["X-RateLimit-Reset"]?.toLongOrNull()
                    if (remaining == 0L || resetAtSec != null) {
                        ReleaseCheckResult.Failed(
                            UpdateErrorCode.RATE_LIMITED,
                            "GitHub's rate limit has been reached. The next check will succeed shortly.",
                            retryAfterMs = resetAtSec?.let { it * 1000 - System.currentTimeMillis() }
                                ?.takeIf { it > 0 },
                        )
                    } else {
                        ReleaseCheckResult.Failed(
                            UpdateErrorCode.NETWORK,
                            "GitHub refused the request (HTTP ${response.status.value}).",
                        )
                    }
                }
                else -> ReleaseCheckResult.Failed(
                    UpdateErrorCode.NETWORK,
                    "GitHub returned HTTP ${response.status.value}.",
                )
            }
        } catch (e: CancellationException) {
            // Must be rethrown, never converted into a Failed result. On the JVM
            // CancellationException *is* an Exception, so the generic catch below would otherwise
            // swallow it and report a shutdown or a user-pressed Cancel as a network error.
            throw e
        } catch (e: Exception) {
            logger.warn("Update check against $url failed", e)
            ReleaseCheckResult.Failed(
                UpdateErrorCode.NETWORK,
                "Could not reach GitHub: ${e.message ?: e::class.simpleName}",
            )
        }
    }

    /**
     * Small text assets (the `.sha256`).
     *
     * Read through a channel and stopped at [maxBytes] rather than buffered whole and truncated
     * afterwards — the URL comes straight out of GitHub's response, and an asset named
     * `<installer>.msi.sha256` that turned out to be hundreds of megabytes would otherwise be
     * pulled entirely into memory on a lighting desk before the cap ever applied.
     */
    suspend fun fetchTextAsset(url: String, maxBytes: Int = 8 * 1024): String? = try {
        val response = client.get(url) { header("Accept", "application/octet-stream") }
        if (response.status != HttpStatusCode.OK) {
            logger.warn("Checksum asset $url returned HTTP ${response.status.value}")
            null
        } else {
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(maxBytes)
            var read = 0
            while (read < maxBytes) {
                val n = channel.readAvailable(buffer, read, maxBytes - read)
                if (n <= 0) break
                read += n
            }
            String(buffer, 0, read)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Could not fetch checksum asset $url", e)
        null
    }

    /**
     * Stream an asset, handing each chunk to [onChunk] as it arrives.
     *
     * Chunked rather than buffered because the installer is hundreds of megabytes: the caller
     * writes to disk and feeds a running digest in the same pass. Hashing afterwards would mean
     * a second full read of a file that size on a machine that may be running a show.
     */
    suspend fun streamAsset(
        url: String,
        onChunk: suspend (ByteArray, Int) -> Unit,
    ): DownloadOutcome = try {
        client.prepareGet(url) { header("Accept", "application/octet-stream") }.execute { response ->
            if (response.status != HttpStatusCode.OK) {
                DownloadOutcome.Failed(
                    UpdateErrorCode.DOWNLOAD_FAILED,
                    "Download returned HTTP ${response.status.value}.",
                )
            } else {
                val channel: ByteReadChannel = response.bodyAsChannel()
                val buffer = ByteArray(1 shl 16)
                while (!channel.isClosedForRead) {
                    val read = channel.readAvailable(buffer, 0, buffer.size)
                    if (read <= 0) break
                    onChunk(buffer, read)
                }
                DownloadOutcome.Complete
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Download of $url failed", e)
        DownloadOutcome.Failed(
            UpdateErrorCode.DOWNLOAD_FAILED,
            "Download failed: ${e.message ?: e::class.simpleName}",
        )
    }

    override fun close() {
        runCatching { client.close() }
    }
}

sealed interface DownloadOutcome {
    data object Complete : DownloadOutcome
    data class Failed(val code: UpdateErrorCode, val message: String) : DownloadOutcome
}
