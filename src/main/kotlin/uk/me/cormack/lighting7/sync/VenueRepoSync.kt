package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.lib.Repository
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoInstall
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoVenueVersion
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.VENUE_VERSION_NOTE_MAX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.me.cormack.lighting7.models.nowUtc
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.auth.AuthResolver
import uk.me.cormack.lighting7.sync.auth.MissingCredentialsException
import uk.me.cormack.lighting7.sync.auth.oauth.OAuthReauthRequiredException
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.VenueVersionJson
import uk.me.cormack.lighting7.venue.VenueDiff
import uk.me.cormack.lighting7.venue.VenueVersionDto
import uk.me.cormack.lighting7.venue.VenueVersions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * A venue's own repo (venue plan D8, P7, §3.4): **published versions only**, one tagged commit each.
 *
 * The repo's branch holds the latest published version in the usual export layout, plus
 * `venueVersion.json` ([VenueVersionJson]: number, note, when, by whom); every version is the commit
 * tagged `vN`, its message `vN: note`. The draft — the venue project's own rows — never syncs
 * (`FU-VENUE-DRAFT-SYNC`): nothing here exports it except as the version a publish makes of it.
 *
 * Three operations, each run by its caller under the venue lock and the project's sync lock:
 *
 *  * **[exchange]** — *Sync now* and auto-sync on a venue. Fetches the branch and the tags, then
 *    materialises every tag this desk lacks as a snapshot ([materialise]: the tag's tree imported as a
 *    `VENUE_VERSION` project, record uuids kept, its `venue_versions` row from `venueVersion.json`),
 *    and pushes every version this desk has that the remote lacks, oldest first. A clean draft — one
 *    with no changes against the installed version — follows a newer version in (as a discard would);
 *    a draft with changes is left as it is. A version both sides number alike but published apart is
 *    refused as `VENUE_VERSION_TAKEN`, never overwritten. `venue_versions` rows are only ever added.
 *  * **[publish]** — the draft as the next version, with a repo attached: [exchange] first, refused as
 *    `VENUE_VERSION_TAKEN` when that brought in the number the publish was going to take; otherwise
 *    the draft is written to the tree, committed, tagged and pushed with its tag, and only once the
 *    remote has taken both is the version materialised here — from the tagged tree, as every other
 *    desk will read it. A refused push leaves nothing behind but the fetch.
 *  * **[materialiseAll]** — a venue repo just cloned as a new venue (`CloudSyncImporter`): every tag.
 *
 * A pull-only install (`sync.push = false`) exchanges without pushing, and refuses to publish a venue
 * with a repo attached (`VENUE_PUBLISH_PULL_ONLY`): a local-only `vN` would be the remote's `vN` by
 * number and not by content the moment another desk published one.
 */
class VenueRepoSync(
    private val state: State,
    private val authResolver: AuthResolver,
) {

    private val workingTree = SyncWorkingTree(state)

    /** What an [exchange] did. [draftReplaced] asks the caller to reload a live venue. */
    data class Exchange(val result: SyncRunResult, val draftReplaced: Boolean)

    /** Exchange versions with the remote. The caller holds the venue and sync locks. */
    suspend fun exchange(venueId: Int): Exchange {
        val venue = venueOf(venueId)
        val (repoUrl, branch) = attachedRepo(venueId)
            ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "No repository is attached to this venue — attach one to sync its versions.")
        val credentials = resolveCredentials(repoUrl)
        return withContext(Dispatchers.IO) {
            workingTree.ensureInitialised(workingTree.pathFor(venue.uuid)).use { repo ->
                exchangeIn(repo, venueId, venue, repoUrl, branch, credentials)
            }
        }
    }

    /**
     * Publish the draft of [venueId] as the next version with [note], signed by [publishedBy]. The
     * caller holds the venue and sync locks, and has checked the note, that the draft has changes and
     * that a repo is attached ([attachedRepo]).
     */
    suspend fun publish(venueId: Int, note: String, publishedBy: String?): VenueVersionDto {
        if (!state.syncPushEnabled) {
            throw SyncException(
                SyncErrorCode.VENUE_PUBLISH_PULL_ONLY,
                "This desk only pulls (sync.push = false), and this venue publishes to its repo: a version made here " +
                    "could never reach it. Publish from a desk that pushes.",
            )
        }
        val venue = venueOf(venueId)
        val (repoUrl, branch) = attachedRepo(venueId)
            ?: throw SyncException(SyncErrorCode.REPO_URL_MISSING, "No repository is attached to this venue.")
        val credentials = resolveCredentials(repoUrl)
        // The number this draft was going to be, before the pull: if the pull brings it in, another desk
        // published it while this draft was open, and this publish must not quietly become the next one.
        val intended = (installedNumber(venueId) ?: 0) + 1
        return withContext(Dispatchers.IO) { workingTree.ensureInitialised(workingTree.pathFor(venue.uuid)).use { repo ->
            exchangeIn(repo, venueId, venue, repoUrl, branch, credentials)
            val installed = installedNumber(venueId) ?: 0
            if (installed >= intended) {
                val taken = transaction(state.database) { VenueVersions.versionsOf(venueId).first { it.number == intended } }
                throw SyncException(
                    SyncErrorCode.VENUE_VERSION_TAKEN,
                    "v$intended was published on another desk while this draft was open (\"${taken.note}\"" +
                        (taken.publishedBy?.let { ", by $it" } ?: "") + "). It is in History now: check the draft " +
                        "against it, then publish again.",
                )
            }
            val number = installed + 1
            val version = VenueVersionJson(
                number = number,
                note = note,
                publishedAt = nowUtc().toIsoUtc(),
                publishedBy = publishedBy,
            )
            writeVersionTree(repo, sourceProjectId = venueId, venue = venue, version = version)
            commitAndTag(repo, version)
            pushVersion(repo, branch, version, credentials)
            val dto = materialise(repo, tagName(number), venueId, venue.uuid)
            recordSynced(venueId, JGitClient.head(repo)?.sha)
            logger.info("Published venue {} v{} to {}", venueId, number, repoUrl)
            dto
        } }
    }

    /**
     * Materialise every tag of [repo] — a venue repo just cloned as venue [venueId] — as its versions.
     * Blocking. The clone's own tags are fetched again into the remote namespace first, so this reads
     * exactly what [exchange] would.
     */
    suspend fun materialiseAll(venueId: Int, repo: Repository, repoUrl: String) {
        val venue = venueOf(venueId)
        val credentials = resolveCredentials(repoUrl)
        withContext(Dispatchers.IO) {
            JGitClient.fetchTags(repo, RemoteSyncEngine.REMOTE_NAME, credentials)
            for (number in versionTags(JGitClient.listRemoteTags(repo, RemoteSyncEngine.REMOTE_NAME)).keys.sorted()) {
                materialise(repo, remoteTagRef(number), venueId, venue.uuid)
            }
        }
    }

    // ── The exchange ────────────────────────────────────────────────────────────

    private fun exchangeIn(
        repo: Repository,
        venueId: Int,
        venue: VenueRef,
        repoUrl: String,
        branch: String,
        credentials: GitCredentials,
    ): Exchange {
        JGitClient.setRemote(repo, RemoteSyncEngine.REMOTE_NAME, repoUrl)
        val remoteHead = try {
            JGitClient.fetch(repo, RemoteSyncEngine.REMOTE_NAME, branch, credentials)
                ?.also { JGitClient.fetchTags(repo, RemoteSyncEngine.REMOTE_NAME, credentials) }
        } catch (e: GitAuthException) {
            throw SyncException(SyncErrorCode.AUTH_FAILED, "GitHub rejected the credentials for this venue's repo. ${e.message}", e)
        }
        val remoteRef = "refs/remotes/${RemoteSyncEngine.REMOTE_NAME}/$branch"
        if (remoteHead != null) checkRemoteIsThisVenue(repo, remoteRef, venue)

        val remoteTags = if (remoteHead == null) emptyMap() else versionTags(JGitClient.listRemoteTags(repo, RemoteSyncEngine.REMOTE_NAME))
        // A `vN` tag whose commit does not say it is vN (no `venueVersion.json`, or another number)
        // is not a version: it is left alone — never materialised, never overwritten — rather than
        // wedging every exchange after it. Its number still holds a local version of that number
        // back from being pushed, since the remote's tag is taken.
        val versionJson = remoteTags.keys.associateWith { readVersionJson(repo, remoteTagRef(it))?.takeIf { json -> json.number == it } }
        val notVersions = versionJson.filterValues { it == null }.keys.sorted()
        notVersions.forEach { logger.warn("Venue {}: the remote's tag {} is not a published version; leaving it alone", venueId, tagName(it)) }
        val local = transaction(state.database) {
            VenueVersions.versionsOf(venueId).associate { it.number to (it.note to it.publishedAt.toIsoUtc()) }
        }
        // A number both sides hold must be one publish, read the same everywhere. The note is
        // compared as this desk stores it (`materialise` keeps at most the column's width).
        for (number in remoteTags.keys.intersect(local.keys) - notVersions.toSet()) {
            val remote = versionJson.getValue(number)!!
            val (note, publishedAt) = local.getValue(number)
            if (remote.note.take(VENUE_VERSION_NOTE_MAX) != note || !sameInstant(remote.publishedAt, publishedAt)) {
                throw SyncException(
                    SyncErrorCode.VENUE_VERSION_TAKEN,
                    "This desk's v$number (\"$note\", $publishedAt) is not the remote's v$number" +
                        " (\"${remote.note}\", ${remote.publishedAt})" +
                        ": two desks published it apart. Keep one venue's history — see FU-VENUE-DRAFT-SYNC.",
                )
            }
        }

        val pulled = remoteTags.keys.minus(local.keys).minus(notVersions.toSet()).sorted()
        // Whether the draft follows a newer version in: only a draft with no changes of its own
        // against the version installed before the pull. Read only when something is pulled, since
        // it exports the draft — every auto-sync tick would otherwise pay for it. The draft's records
        // are kept, and the replace below goes ahead only if the draft still holds exactly them, so
        // an edit made while the versions were being fetched is never overwritten.
        val cleanDraft = if (pulled.isNotEmpty() && state.venueVersions.draftChanges(venueId) == 0) {
            VenueDiff.recordsOf(state, venueId)
        } else {
            null
        }
        val cleanBefore = cleanDraft != null
        for (number in pulled) materialise(repo, remoteTagRef(number), venueId, venue.uuid)

        // The local branch follows the remote's: a venue's tree only ever holds a published version,
        // so there is nothing of this desk's on it to keep but the versions pushed below.
        if (remoteHead != null) JGitClient.resetHard(repo, remoteRef)

        val unpushed = local.keys.minus(remoteTags.keys).sorted()
        val pushed = mutableListOf<Int>()
        if (state.syncPushEnabled) {
            for (number in unpushed) {
                val (snapshotId, json) = transaction(state.database) {
                    val row = VenueVersions.versionsOf(venueId).first { it.number == number }
                    row.snapshotProject.id.value to VenueVersionJson(
                        number = row.number,
                        note = row.note,
                        publishedAt = row.publishedAt.toIsoUtc(),
                        publishedBy = row.publishedBy,
                    )
                }
                writeVersionTree(repo, sourceProjectId = snapshotId, venue = venue, version = json)
                commitAndTag(repo, json)
                pushVersion(repo, branch, json, credentials)
                pushed += number
            }
        }

        val draftReplaced = cleanBefore && pulled.isNotEmpty() && run {
            val newest = installedNumber(venueId)
            newest != null && newest == pulled.last() && state.venueVersions.replaceDraftWithInstalled(venueId, ifDraftIs = cleanDraft)
        }

        val head = JGitClient.head(repo)?.sha.orEmpty()
        val parts = buildList {
            if (pulled.isNotEmpty()) {
                val draft = when {
                    draftReplaced -> "; the draft follows v${pulled.last()}"
                    !cleanBefore -> "; the draft has changes of its own and was kept"
                    installedNumber(venueId) == pulled.last() -> "; the draft changed while they were fetched and was kept"
                    else -> ""
                }
                add("Fetched ${versionList(pulled)}$draft")
            }
            if (notVersions.isNotEmpty()) add("Left ${versionList(notVersions)} on the remote alone: not published versions")
            if (pushed.isNotEmpty()) add("Pushed ${versionList(pushed)}")
            if (!state.syncPushEnabled && unpushed.isNotEmpty()) add("${RemoteSyncEngine.PUSH_DISABLED_PREFIX} ${versionList(unpushed)} kept on this desk")
        }
        val outcome = when {
            pushed.isNotEmpty() -> SyncOutcome.PUSHED
            pulled.isNotEmpty() -> SyncOutcome.FAST_FORWARDED
            else -> SyncOutcome.NO_OP
        }
        val message = parts.joinToString(". ").ifEmpty {
            installedNumber(venueId)?.let { "Already in sync (v$it)." } ?: "Already in sync: nothing published yet."
        }
        return Exchange(
            SyncRunResult(outcome, pushed = pushed.size, pulled = pulled.size, replaced = 0, headSha = head, message = message),
            draftReplaced,
        )
    }

    /** Refuse a remote that is not this venue's repo: another project's, a show's, or too new to read. */
    private fun checkRemoteIsThisVenue(repo: Repository, remoteRef: String, venue: VenueRef) {
        JGitClient.readBlob(repo, remoteRef, "formatVersion.json")?.let { json ->
            val format = canonicalDecode(FormatVersionJson.serializer(), json)
            if (format.formatVersion > SUPPORTED_FORMAT_VERSION) {
                throw SyncException(
                    SyncErrorCode.FORMAT_TOO_NEW,
                    "Repo format v${format.formatVersion} is newer than this install supports (v$SUPPORTED_FORMAT_VERSION). Upgrade lighting7 before syncing.",
                )
            }
        }
        val project = JGitClient.readBlob(repo, remoteRef, "project.json")
            ?.let { runCatching { canonicalDecode(ProjectJson.serializer(), it) }.getOrNull() }
            ?: return
        if (project.uuid != venue.uuid.toString() || project.kind != ProjectKind.VENUE) {
            throw SyncException(
                SyncErrorCode.PROJECT_MISMATCH,
                "The remote repository is not this venue's (it holds ${project.kind.name.lowercase()} ${project.uuid}). " +
                    "Attach an empty repository, or this venue's own.",
            )
        }
    }

    // ── One version ─────────────────────────────────────────────────────────────

    /**
     * Read [ref] — a version's tagged commit — as an archive and import it as venue [venueId]'s
     * version: a `VENUE_VERSION` project with a fresh uuid, every record uuid kept, and its index row,
     * in one transaction. Answers the new version.
     */
    private fun materialise(repo: Repository, ref: String, venueId: Int, venueUuid: UUID): VenueVersionDto {
        val staging = Files.createTempDirectory("venue-version-")
        try {
            JGitClient.extractTree(repo, ref, staging)
            val json = readVersionJson(staging)
                ?: throw SyncException(SyncErrorCode.VENUE_REPO_INVALID, "$ref has no venueVersion.json: it is not a published version")
            if (tagName(json.number) != ref.substringAfterLast('/')) {
                throw SyncException(SyncErrorCode.VENUE_REPO_INVALID, "$ref says it is v${json.number}")
            }
            val project = canonicalDecode(ProjectJson.serializer(), Files.readString(staging.resolve("project.json")))
            if (project.uuid != venueUuid.toString() || project.kind != ProjectKind.VENUE) {
                throw SyncException(SyncErrorCode.VENUE_REPO_INVALID, "$ref is not a version of this venue")
            }
            val publishedAt = runCatching { Instant.parse(json.publishedAt) }.getOrElse {
                throw SyncException(SyncErrorCode.VENUE_REPO_INVALID, "$ref's publishedAt is not an instant: ${json.publishedAt}")
            }
            val snapshotUuid = UUID.randomUUID()
            var dto: VenueVersionDto? = null
            KeptUuidCopy.fromArchive(
                state, staging, venueUuid,
                name = VenueVersions.snapshotName(snapshotUuid),
                kind = ProjectKind.VENUE_VERSION,
                projectUuid = snapshotUuid,
            ) { snapshot ->
                val row = DaoVenueVersion.new {
                    venueProject = DaoProject[venueId]
                    number = json.number
                    note = json.note.take(VENUE_VERSION_NOTE_MAX)
                    this.publishedAt = publishedAt
                    publishedBy = json.publishedBy?.take(100)
                    snapshotProject = snapshot
                }
                dto = VenueVersionDto(
                    number = row.number,
                    note = row.note,
                    publishedAt = row.publishedAt.toIsoUtc(),
                    publishedBy = row.publishedBy,
                    snapshotProjectId = snapshot.id.value,
                )
            }
            logger.info("Materialised venue {} v{} from {}", venueId, json.number, ref)
            return dto ?: error("Version row was not written")
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    /**
     * Replace [repo]'s working tree with [sourceProjectId] — the venue's draft, or one of its snapshots
     * — as version [version] of [venue]: the export, its project uuid the venue's, `project.json` the
     * venue's own name and kind, and `venueVersion.json`.
     */
    private fun writeVersionTree(repo: Repository, sourceProjectId: Int, venue: VenueRef, version: VenueVersionJson) {
        val tree = repo.workTree.toPath()
        val staging = Files.createTempDirectory("venue-tree-")
        try {
            ProjectExporter(state).export(sourceProjectId, staging)
            val sourceUuid = transaction(state.database) { DaoProject[sourceProjectId].uuid }
            if (sourceUuid != venue.uuid) ExportUuidRemapper.applyMapping(staging, mapOf(sourceUuid to venue.uuid))
            val projectPath = staging.resolve("project.json")
            val project = canonicalDecode(ProjectJson.serializer(), Files.readString(projectPath))
            Files.writeString(
                projectPath,
                canonicalEncode(
                    ProjectJson.serializer(),
                    project.copy(uuid = venue.uuid.toString(), name = venue.name, description = venue.description, kind = ProjectKind.VENUE),
                ),
            )
            Files.writeString(staging.resolve(VERSION_FILE), canonicalEncode(VenueVersionJson.serializer(), version))

            // The tree is replaced whole, binaries too: what a version holds is exactly its export.
            SyncWorkingTree(state).cleanTrackedFiles(tree)
            RecordHasher.BINARY_DIRS.forEach { tree.resolve(it).toFile().deleteRecursively() }
            staging.toFile().copyRecursively(tree.toFile(), overwrite = true)
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    private fun commitAndTag(repo: Repository, version: VenueVersionJson) {
        val (name, email) = installIdentity()
        JGitClient.stageAll(repo)
        val message = "${tagName(version.number)}: ${version.note}"
        JGitClient.commit(repo, name, email, message)
        // A tag left by an earlier push the remote refused names a commit that is gone.
        if (tagName(version.number) in JGitClient.listTags(repo)) JGitClient.deleteTag(repo, tagName(version.number))
        JGitClient.tag(repo, tagName(version.number), message, name, email)
    }

    /**
     * Push the branch with [version]'s tag. A refusal of either — the tag taken, or the branch moved on,
     * which only another publish does — puts the local branch back on the remote's and drops the tag,
     * and is answered as `VENUE_VERSION_TAKEN`.
     */
    private fun pushVersion(repo: Repository, branch: String, version: VenueVersionJson, credentials: GitCredentials) {
        val tag = tagName(version.number)
        val results = JGitClient.pushWithTag(repo, RemoteSyncEngine.REMOTE_NAME, branch, tag, credentials)
        val refused = results.filterValues { it.status != PushStatus.OK }
        if (refused.isEmpty() && results.size == 2) return
        JGitClient.deleteTag(repo, tag)
        val remoteRef = "refs/remotes/${RemoteSyncEngine.REMOTE_NAME}/$branch"
        when {
            repo.resolve(remoteRef) != null -> JGitClient.resetHard(repo, remoteRef)
            // An empty remote: back off the one commit made for it, when there is one to back off to.
            repo.resolve("HEAD~1") != null -> JGitClient.resetHard(repo, "HEAD~1")
        }
        throw SyncException(
            SyncErrorCode.VENUE_VERSION_TAKEN,
            "The remote refused $tag (${refused.entries.joinToString { "${it.key}: ${it.value.message ?: it.value.status}" }}): " +
                "another desk published while this one was. Sync the venue, then publish again.",
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private data class VenueRef(val uuid: UUID, val name: String, val description: String?)

    private fun venueOf(venueId: Int): VenueRef = transaction(state.database) {
        val venue = VenueVersions.requireVenue(venueId)
        VenueRef(venue.uuid, venue.name, venue.description)
    }

    private fun installedNumber(venueId: Int): Int? =
        transaction(state.database) { VenueVersions.versionsOf(venueId).firstOrNull()?.number }

    private fun installIdentity(): Pair<String, String> = transaction(state.database) {
        val install = DaoInstall.all().firstOrNull()
            ?: error("Install row missing — `ensureInstallRow` should have created it on startup.")
        install.friendlyName to "${install.uuid.toString().take(8)}@${SnapshotEngine.INSTALL_EMAIL_DOMAIN}"
    }

    private fun recordSynced(venueId: Int, sha: String?) {
        if (sha == null) return
        transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.project eq venueId }.firstOrNull()?.let {
                it.lastSyncedSha = sha
                it.lastSyncedAt = nowUtc()
            }
        }
    }

    private suspend fun resolveCredentials(repoUrl: String): GitCredentials = try {
        authResolver.resolveFor(repoUrl)
    } catch (e: MissingCredentialsException) {
        throw SyncException(SyncErrorCode.MISSING_CREDENTIALS, e.message ?: "No GitHub credentials configured for this repository.")
    } catch (e: OAuthReauthRequiredException) {
        throw SyncException(SyncErrorCode.OAUTH_REAUTH_REQUIRED, e.message ?: "GitHub OAuth re-authentication required.")
    }

    private fun readVersionJson(repo: Repository, ref: String): VenueVersionJson? =
        JGitClient.readBlob(repo, ref, VERSION_FILE)
            ?.let { runCatching { canonicalDecode(VenueVersionJson.serializer(), it) }.getOrNull() }

    private fun readVersionJson(dir: Path): VenueVersionJson? =
        dir.resolve(VERSION_FILE).takeIf { Files.exists(it) }
            ?.let { runCatching { canonicalDecode(VenueVersionJson.serializer(), Files.readString(it)) }.getOrNull() }

    companion object {
        private val logger = LoggerFactory.getLogger(VenueRepoSync::class.java)

        /** The file at a venue repo's root that says which version its commit is. */
        const val VERSION_FILE = "venueVersion.json"

        fun tagName(number: Int) = "v$number"

        private fun remoteTagRef(number: Int) = "refs/remotes/${RemoteSyncEngine.REMOTE_NAME}/tags/${tagName(number)}"

        /** `vN` tags by their number; any other tag is not a version and is ignored. */
        private fun versionTags(tags: Map<String, String>): Map<Int, String> =
            tags.mapNotNull { (name, sha) ->
                VERSION_TAG.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }?.let { it to sha }
            }.toMap()

        private val VERSION_TAG = Regex("""v(\d+)""")

        private fun versionList(numbers: List<Int>) = numbers.joinToString(", ") { tagName(it) }

        private fun sameInstant(a: String, b: String): Boolean =
            runCatching { Instant.parse(a) == Instant.parse(b) }.getOrDefault(a == b)

        /**
         * The repo attached to project [projectId] — `(url, branch)` — or null when none is. Inside or
         * outside a transaction.
         */
        fun attachedRepo(state: State, projectId: Int): Pair<String, String>? = transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?.takeIf { it.synced }
                ?.let { it.repoUrl!! to it.branch }
        }
    }

    private fun attachedRepo(venueId: Int) = attachedRepo(state, venueId)
}
