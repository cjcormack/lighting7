package uk.me.cormack.lighting7.cloud.protocol

import kotlinx.serialization.Serializable

/*
 * Cloud repos (docs/cloud-protocol.md §"Repos"): the bare git repositories the cloud hosts for an org's
 * synced projects, one per project uuid. The desk makes and lists them over [CloudPaths.REPOS], each
 * request signed over a [RepoApiPayload], and syncs with a repo's [RepoDto.url] under a git token
 * ([InstallGitTokens]).
 */

/**
 * What a repo holds, its `kind`: a show's project, or a venue's published versions. A string on the
 * wire, so a newer kind reads as one the reader does not know rather than failing the message.
 */
object RepoKind {
    const val SHOW: String = "SHOW"
    const val VENUE: String = "VENUE"
}

/** Desk → cloud, `POST` [CloudPaths.REPOS]: make an empty repo for this project. */
@Serializable
data class CreateRepoRequest(val projectUuid: String, val kind: String, val name: String)

/** One repo, as the cloud answers it. */
@Serializable
data class RepoDto(
    val repoId: String,
    /** `<cloud origin>/git/<orgId>/<repoId>.git`, by ids, so a rename moves nothing. */
    val url: String,
    val projectUuid: String,
    /** A [RepoKind]. */
    val kind: String,
    /** What the portal and the desk show; the create's, until a push's `project.json` names another. */
    val name: String,
    /** `main`'s commit, absent before the first push. */
    val headSha: String? = null,
    val sizeBytes: Long,
    /** The last push's `formatVersion.json` `formatVersion`. */
    val formatVersion: Int? = null,
    /** A [Timestamps] string. */
    val lastPushAt: String? = null,
)

/** Cloud → desk, `GET` [CloudPaths.REPOS]: the org's repos, newest push first. */
@Serializable
data class RepoList(val repos: List<RepoDto>)

/**
 * Cloud → desk, the 409 answering a create for a project that already has a repo: [code] is
 * [ErrorCode.REPO_EXISTS] and [repo] the repo the project has. Read as an [ApiError] it is one.
 */
@Serializable
data class RepoExistsError(val code: String, val message: String, val repo: RepoDto)

/**
 * The reasons the cloud's git refuses a pushed ref with: `CODE: words`, which JGit's client reports as
 * the ref's message.
 */
object PushRefusals {
    /** Every code a push refusal carries. */
    val CODES: Set<String> = setOf(
        ErrorCode.SYNC_NON_FAST_FORWARD,
        ErrorCode.SYNC_REF_PROTECTED,
        ErrorCode.SYNC_TAG_IMMUTABLE,
        ErrorCode.SYNC_FORMAT_INVALID,
        ErrorCode.SYNC_QUOTA_EXCEEDED,
        ErrorCode.SYNC_STORAGE_UNAVAILABLE,
    )

    /** The reason refusing a ref with [code], in [words]. */
    fun reason(code: String, words: String): String {
        require(code in CODES) { "$code is not a push refusal" }
        return "$code: $words"
    }

    /** The code [reason] names, or null for a reason that is not `CODE: words` with a code in [CODES]. */
    fun codeOf(reason: String?): String? {
        val code = reason?.substringBefore(": ", missingDelimiterValue = "")?.takeIf { it.isNotEmpty() } ?: return null
        return code.takeIf { it in CODES }
    }
}
