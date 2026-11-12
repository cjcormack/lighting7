package uk.me.cormack.lighting7.sync

import uk.me.cormack.lighting7.cloud.protocol.ErrorCode
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.CloudNotAdoptedException
import uk.me.cormack.lighting7.sync.auth.MissingCredentialsException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException

/**
 * The cloud's sync refusals as [SyncErrorCode]s (cloud-p2 plan D4). `SYNC_NON_FAST_FORWARD` never
 * reaches here: [JGitClient] reads it as a non-fast-forward, which the engine pulls, merges and
 * retries, and `SYNC_TAG_IMMUTABLE` on a venue's publish is [SyncErrorCode.VENUE_VERSION_TAKEN].
 */
internal object CloudSyncCodes {
    /** A push the cloud refused for good. A refusal a correct desk never earns is [SyncErrorCode.PUSH_REJECTED]. */
    fun ofPushRefusal(code: String): SyncErrorCode = when (code) {
        ErrorCode.SYNC_QUOTA_EXCEEDED -> SyncErrorCode.SYNC_QUOTA_EXCEEDED
        ErrorCode.SYNC_STORAGE_UNAVAILABLE -> SyncErrorCode.SYNC_STORAGE_UNAVAILABLE
        else -> SyncErrorCode.PUSH_REJECTED
    }

    /** A cloud repo's git answering 403 or 404, by the code the cloud gave; none is the credentials'. */
    fun ofGitRefusal(code: String?): SyncErrorCode = when (code) {
        ErrorCode.SYNC_OFF -> SyncErrorCode.SYNC_OFF
        ErrorCode.REPO_NOT_FOUND -> SyncErrorCode.REPO_NOT_FOUND
        else -> SyncErrorCode.AUTH_FAILED
    }

    /** [message], the cloud's words, followed by what to do about [code]. */
    fun explained(code: SyncErrorCode, message: String): String {
        val next = when (code) {
            SyncErrorCode.SYNC_QUOTA_EXCEEDED -> "Ask the org's owner for more storage."
            SyncErrorCode.SYNC_STORAGE_UNAVAILABLE -> "The cloud cannot store it just now: sync again later."
            SyncErrorCode.SYNC_OFF -> "Ask the org's owner to turn cloud sync on."
            SyncErrorCode.REPO_NOT_FOUND -> "The cloud repo was deleted: sync to a new one."
            else -> null
        }
        return listOfNotNull(message.trim().trimEnd('.').takeIf { it.isNotEmpty() }?.plus("."), next).joinToString(" ")
    }
}

/**
 * A remote's credential and refusal failures as [SyncException]s, the one translation the engine, a
 * venue's repo and the importer share, so a cloud repo's refusal reads the same from every door.
 */
internal class SyncFailures(private val authResolver: AuthResolver) {
    /** [repoUrl]'s credentials, a missing or unusable one as its [SyncErrorCode]. */
    suspend fun credentialsFor(repoUrl: String): GitCredentials = try {
        authResolver.resolveFor(repoUrl)
    } catch (e: MissingCredentialsException) {
        throw SyncException(SyncErrorCode.MISSING_CREDENTIALS, e.message ?: "No GitHub credentials configured for this repository.")
    } catch (e: OAuthReauthRequiredException) {
        throw SyncException(SyncErrorCode.OAUTH_REAUTH_REQUIRED, e.message ?: "GitHub OAuth re-authentication required.")
    } catch (e: CloudNotAdoptedException) {
        throw SyncException(SyncErrorCode.CLOUD_NOT_ADOPTED, e.message ?: "This desk is not adopted by Lighting7 Cloud.")
    }

    /** A remote's refusal of [repoUrl]'s credentials, in GitHub's words ([github]) or the cloud's. */
    fun authFailed(repoUrl: String?, e: GitAuthException, github: String): SyncException =
        if (repoUrl != null && authResolver.isCloudRepo(repoUrl)) {
            SyncException(
                SyncErrorCode.AUTH_FAILED,
                "The cloud refused this desk's credentials (${e.message}). Check the desk is still adopted on the Cloud tab.",
                e,
            )
        } else {
            SyncException(SyncErrorCode.AUTH_FAILED, "$github ${e.message}", e)
        }

    /**
     * [e] as the [SyncException] it means, when it is a cloud refusal (cloud-p2 plan D4): a push refused
     * for good, or a cloud repo's git answering 404 or 403, put in the cloud's words. Null otherwise.
     */
    suspend fun cloudRefusalOf(e: Throwable): SyncException? = when (e) {
        is GitPushRefusedException -> CloudSyncCodes.ofPushRefusal(e.code).let {
            SyncException(it, CloudSyncCodes.explained(it, "The cloud refused the push: ${e.message}"), e)
        }
        is GitTransportException -> {
            val url = e.url
            val status = e.status
            if (url == null || status == null) null
            else authResolver.cloudRefusal(url, status)?.let { refusal ->
                val code = CloudSyncCodes.ofGitRefusal(refusal.code)
                SyncException(code, CloudSyncCodes.explained(code, refusal.message), e)
            }
        }
        else -> null
    }
}
