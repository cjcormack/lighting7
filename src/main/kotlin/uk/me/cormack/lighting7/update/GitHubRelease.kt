package uk.me.cormack.lighting7.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The subset of GitHub's `/releases/latest` payload we read.
 *
 * `ignoreUnknownKeys = true` is not politeness — GitHub adds response fields routinely, and
 * without it a field added next month would start throwing on every already-installed desk,
 * turning "check for updates" into a permanent error on machines nobody can redeploy.
 */
internal val GITHUB_JSON = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

@Serializable
internal data class GitHubReleaseJson(
    @SerialName("tag_name") val tagName: String? = null,
    val name: String? = null,
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    @SerialName("published_at") val publishedAt: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val assets: List<GitHubAssetJson> = emptyList(),
)

@Serializable
internal data class GitHubAssetJson(
    val name: String? = null,
    val size: Long = 0,
    @SerialName("browser_download_url") val browserDownloadUrl: String? = null,
)

/** A published release, reduced to what the update path actually needs. */
data class ReleaseInfo(
    val tag: String,
    val name: String?,
    val notes: String?,
    val publishedAtMs: Long?,
    /**
     * Null when GitHub didn't supply one. Deliberately not defaulted here: this parser doesn't
     * know the repo, so any fallback it invented would be a wrong URL cached forever.
     * `UpdateService` fills it from the configured repo instead.
     */
    val htmlUrl: String?,
    val installer: ReleaseAsset?,
    val checksum: ReleaseAsset?,
)

data class ReleaseAsset(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
)

/** Release notes are shown in the desk UI; cap them so a pathological body can't bloat the DTO. */
internal const val MAX_RELEASE_NOTES_CHARS = 20_000

/**
 * Reduce GitHub's payload to a [ReleaseInfo], or null if it isn't a usable release.
 *
 * The installer is located by **scanning the asset list**, never by rebuilding the filename from
 * the version. The canonical name the workflow produces exists for humans and for disambiguating
 * architectures; string-building a URL would break the moment that convention shifted.
 */
internal fun parseRelease(json: String): ReleaseInfo? {
    val release = runCatching { GITHUB_JSON.decodeFromString<GitHubReleaseJson>(json) }.getOrNull()
        ?: return null
    val tag = release.tagName?.takeIf { it.isNotBlank() } ?: return null

    // Belt and braces: /releases/latest already excludes drafts and prereleases, but this parser
    // is also used for the by-tag path, and offering an unfinished build to a lighting desk is
    // exactly the kind of thing that should need two mistakes rather than one.
    if (release.draft || release.prerelease) return null

    val assets = release.assets.mapNotNull { asset ->
        val name = asset.name?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val url = asset.browserDownloadUrl?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ReleaseAsset(name = name, sizeBytes = asset.size, downloadUrl = url)
    }

    val installer = assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) }
    // Pair the checksum to its installer by name so a release carrying several artifacts can
    // never hand back a digest belonging to a different file.
    val checksum = installer?.let { msi ->
        assets.firstOrNull { it.name.equals("${msi.name}.sha256", ignoreCase = true) }
    }

    return ReleaseInfo(
        tag = tag,
        name = release.name?.takeIf { it.isNotBlank() },
        notes = release.body?.takeIf { it.isNotBlank() }?.take(MAX_RELEASE_NOTES_CHARS),
        publishedAtMs = release.publishedAt
            ?.let { raw -> runCatching { java.time.Instant.parse(raw).toEpochMilli() }.getOrNull() },
        htmlUrl = release.htmlUrl?.takeIf { it.isNotBlank() },
        installer = installer,
        checksum = checksum,
    )
}

private val SHA256_TOKEN = Regex("""\b[0-9a-fA-F]{64}\b""")

/**
 * Pull the digest out of a `.sha256` asset.
 *
 * Accepts the two-field `sha256sum(1)` format the workflow writes, a bare digest, CRLF endings,
 * and uppercase hex — the file is produced by a PowerShell step, and being liberal here is much
 * cheaper than debugging a BOM or a line-ending difference on someone else's Windows box.
 */
internal fun parseSha256Asset(raw: String): String? =
    SHA256_TOKEN.find(raw)?.value?.lowercase()
