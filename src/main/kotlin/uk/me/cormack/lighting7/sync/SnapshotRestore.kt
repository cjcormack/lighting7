package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.eclipse.jgit.revwalk.RevWalk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.dao.flushCache
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import uk.me.cormack.lighting7.fx.parsePaletteColourRefUuid
import uk.me.cormack.lighting7.history.RecordLabeler
import uk.me.cormack.lighting7.history.RecordRestorer
import uk.me.cormack.lighting7.history.WriteOrigin
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.ProjectKind
import uk.me.cormack.lighting7.models.toIsoUtc
import uk.me.cormack.lighting7.state.State
import uk.me.cormack.lighting7.sync.dto.FormatVersionJson
import uk.me.cormack.lighting7.sync.dto.InstallsJson
import uk.me.cormack.lighting7.sync.dto.ProjectJson
import uk.me.cormack.lighting7.sync.dto.UniverseConfigJson
import uk.me.cormack.lighting7.venue.ShowBehind
import uk.me.cormack.lighting7.venue.VenueBehind
import uk.me.cormack.lighting7.venue.VenuePins
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Going back to an earlier cloud-sync snapshot (sync-restore plan §3): a new commit on top of the
 * branch whose content is the target's, made by importing the target's tree into the database and
 * snapshotting the result, so deletion markers, today's format and an older archive's reading all
 * come from the ordinary machinery (D1, D2).
 *
 * The review is the restore rolled back (D4): the project exported, the target imported in the same
 * transaction, exported again, rolled back, and the two exports compared record by record. One
 * function builds it, [compute], for the review, the token the restore checks and the restore itself
 * (P2). Both entry points run under the project's sync lock, which [RemoteSyncEngine] takes.
 */
class SnapshotRestore(private val state: State) {

    /** What a restore needs of the sync engine, under the project lock its caller already holds. */
    interface Engine {
        /** One sync run, as `doRun` makes it. */
        suspend fun sync(): SyncRunResult

        /** Reload the show, carrying it across, if [projectId] is the current project. */
        suspend fun reload(projectId: Int)
    }

    private val workingTree = SyncWorkingTree(state)
    private val importer = ProjectImporter(state)
    private val exporter = ProjectExporter(state)
    private val snapshotEngine = SnapshotEngine(state)
    private val syncLogger get() = state.syncLogger

    /** Runs between step 3's snapshot and step 4's import: how a test lands a write during a restore. */
    internal var afterBeforeSnapshot: (() -> Unit)? = null

    /** `GET …/sync/restore/{sha}`: what restoring [sha] would do. Nothing is written. */
    suspend fun review(projectId: Int, sha: String): RestoreReviewDto = withContext(Dispatchers.IO) {
        requireShow(projectId)
        if (sha.isBlank()) throw SyncException(SyncErrorCode.RESTORE_INVALID, "Name the snapshot to restore.")
        prepare(projectId, sha).use { compute(projectId, it, Mode.Review).dto }
    }

    /**
     * `POST …/sync/restore`: restore [sha] if the review now answers [token]. §3's seven steps; every
     * refusal before step 4 commits leaves the database, branch, working tree and stores as they
     * were, but for the first sync's own effect and the `Before restoring` snapshot (P3).
     */
    suspend fun restore(
        engine: Engine,
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
        sha: String,
        token: String,
        note: String?,
    ): RestoreResultDto {
        val trimmedNote = note?.trim()?.takeIf { it.isNotEmpty() }
        when {
            sha.isBlank() -> throw SyncException(SyncErrorCode.RESTORE_INVALID, "Name the snapshot to restore.")
            token.isBlank() -> throw SyncException(SyncErrorCode.RESTORE_INVALID, "A restore needs its review's token.")
            trimmedNote != null && trimmedNote.length > MAX_NOTE_LENGTH ->
                throw SyncException(SyncErrorCode.RESTORE_INVALID, "A restore's note is at most $MAX_NOTE_LENGTH characters.")
            trimmedNote != null && trimmedNote.any { it == '\n' || it == '\r' } ->
                throw SyncException(SyncErrorCode.RESTORE_INVALID, "A restore's note is one line.")
        }
        withContext(Dispatchers.IO) { requireShow(projectId) }
        val notes = mutableListOf<String>()

        // 1. Sync first (D5), so the review covers every desk's changes.
        val repoUrl = transaction(state.database) {
            DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()?.repoUrl?.takeIf { it.isNotBlank() }
        }
        var reachable = false
        if (repoUrl == null) {
            notes += "No repository is attached: the restore is on this desk only."
        } else {
            try {
                val first = engine.sync()
                if (first.outcome == SyncOutcome.CONFLICTS_PENDING) {
                    throw SyncException(
                        SyncErrorCode.SESSION_PENDING,
                        "Syncing first found ${first.conflictCount} conflict(s) (session #${first.sessionId}): resolve them before restoring.",
                    )
                }
                reachable = true
            } catch (e: SyncException) {
                when (e.code) {
                    SyncErrorCode.REPO_URL_MISSING, SyncErrorCode.SYNC_DISABLED ->
                        notes += "This project doesn't sync: the restore is on this desk only."
                    in UNREACHABLE -> notes += unreachableNote(e.message)
                    else -> throw e
                }
            } catch (e: GitTransportException) {
                notes += unreachableNote(e.message)
            } catch (e: GitAuthException) {
                notes += unreachableNote(e.message)
            } catch (e: GitPushRefusedException) {
                notes += unreachableNote(e.message)
            } catch (e: ImportError) {
                throw e.toSyncException(pulled = true)
            } catch (e: SerializationException) {
                throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Syncing first could not import the pulled show: ${e.message}", e)
            } catch (e: SQLException) {
                throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Syncing first could not import the pulled show: ${e.message}", e)
            }
        }

        return withContext(Dispatchers.IO) {
            prepare(projectId, sha).use { prepared ->
                // 2. Check (D6): the review recomputed after the first sync.
                val review = compute(projectId, prepared, Mode.Review)
                if (review.dto.noChanges) {
                    throw SyncException(SyncErrorCode.RESTORE_NO_CHANGES, "Snapshot ${prepared.target.shortSha} is the show as it is now.")
                }
                if (review.dto.token != token) {
                    throw RestoreStaleException(review.dto, "What restoring ${prepared.target.shortSha} would do has changed since it was reviewed.")
                }

                // 3. The show as it stood is a commit (D3).
                snapshotEngine.snapshot(
                    projectId, projectUuid, installUuid, installFriendlyName,
                    message = "Before restoring ${prepared.target.shortSha}",
                    logNoop = false,
                )
                val before = withRepo(prepared) { JGitClient.commitInfo(it, "HEAD") }
                    ?: error("No HEAD after the snapshot before a restore")
                val standing = withRepo(prepared) { standingAt(it, before.sha) }
                afterBeforeSnapshot?.invoke()

                // 4. Import, unless a write landed since step 3.
                val restored = try {
                    compute(projectId, prepared, Mode.Restore(standing))
                } catch (_: WriteLanded) {
                    val fresh = compute(projectId, prepared, Mode.Review)
                    throw RestoreStaleException(fresh.dto, "The show changed while it was being restored; nothing was restored.")
                }
                state.history.clear(projectId, HISTORY_REASON)
                runCatching { PromptScriptRepoSync.hydrateStore(state, projectUuid, prepared.scratch) }
                    .onFailure { logger.warn("Prompt-book PDF hydrate failed restoring project {}: {}", projectId, it.message) }
                runCatching { SceneImageRepoSync.hydrateStore(state, projectUuid, prepared.scratch) }
                    .onFailure { logger.warn("Scene image hydrate failed restoring project {}: {}", projectId, it.message) }

                // 5. The restore's own commit.
                val summary = "Restored ${prepared.target.shortSha} from ${formatWhen(prepared.target.whenMs)} UTC" +
                    (trimmedNote?.let { ": $it" } ?: "")
                val snapshot = snapshotEngine.snapshot(
                    projectId, projectUuid, installUuid, installFriendlyName,
                    message = summary,
                    trailer = "$RESTORED_FROM_TRAILER ${prepared.target.sha}",
                )
                val commit = snapshot.commit ?: withRepo(prepared) { JGitClient.commitInfo(it, "HEAD") } ?: before
                val counts = restored.dto.counts
                syncLogger.info(
                    projectId, SyncLogEvent.RESTORED,
                    "Restored ${prepared.target.shortSha} from ${formatWhen(prepared.target.whenMs)} UTC: " +
                        "${counts.back} back, ${counts.gone} gone, ${counts.changed} changed",
                )

                // 6. Reload (D8): a running show is never held by its restore.
                if (isCurrent(projectId)) {
                    val venue = restored.dto.venue
                    if (venue?.runAgainst == true && restored.pinned != null && restored.installed != null) {
                        state.venueHold.runAgainstOnLoad(projectId, restored.pinned, restored.installed)
                    }
                    engine.reload(projectId)
                }

                announce(restored)

                // 7. Push.
                var syncResult: SyncRunResult? = null
                if (reachable && state.syncPushEnabled) {
                    try {
                        val pushed = engine.sync()
                        syncResult = pushed
                        when (pushed.outcome) {
                            SyncOutcome.CONFLICTS_PENDING ->
                                notes += "Pushing found ${pushed.conflictCount} conflict(s) with another desk's changes: resolve them on the sync page."
                            SyncOutcome.MERGED -> notes += lostToMerge(projectId, restored.restoredFields)
                            else -> Unit
                        }
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The restore has committed: a push that fails, however, is the next sync's to retry.
                        logger.warn("Restore of project {} committed, but its push failed: {}", projectId, e.message)
                        notes += "The restore didn't go out (${e.message ?: e.javaClass.simpleName}): it goes out on the next sync."
                    }
                } else if (reachable) {
                    notes += "This desk doesn't push (sync.push = false): the restore stays in its working tree."
                }

                RestoreResultDto(
                    commit = commit,
                    before = before,
                    counts = counts,
                    sync = syncResult,
                    notes = notes,
                )
            }
        }
    }

    /**
     * `POST …/sync/restore/records` (sync-restore plan session 3): the [records] of [sha] the operator
     * chose, and every record they need (§"Bring back"), put back through undo's upsert under [origin]
     * as one show-history entry, *Brought back from {shortSha}*. With [dryRun] it answers the selection,
     * its addresses, what it moves on the live stage and its token, and writes nothing; otherwise the
     * selection, recomputed in the write's transaction, must answer [token], and the snapshot's binary
     * stores are hydrated once it lands. Neither commits nor reloads: the next snapshot or sync carries it.
     */
    internal suspend fun bringBack(
        projectId: Int,
        sha: String,
        records: List<BringBackRecordRef>,
        token: String?,
        dryRun: Boolean,
        origin: WriteOrigin,
        scriptsAllowed: Boolean,
    ): BringBackOutcome = withContext(Dispatchers.IO) {
        requireShow(projectId)
        if (sha.isBlank()) throw SyncException(SyncErrorCode.RESTORE_INVALID, "Name the snapshot to bring records back from.")
        if (records.isEmpty()) throw SyncException(SyncErrorCode.RESTORE_INVALID, "Choose a record to bring back.")
        if (!dryRun && token.isNullOrBlank()) throw SyncException(SyncErrorCode.RESTORE_INVALID, "Bringing records back needs their selection's token.")
        val chosen = records.map { ref ->
            val uuid = runCatching { UUID.fromString(ref.uuid) }.getOrNull()
                ?: throw SyncException(SyncErrorCode.RESTORE_INVALID, "“${ref.uuid}” is not a record's id.")
            RecordKey(ref.folder, uuid)
        }.distinct()
        val projectUuid = transaction(state.database) { DaoProject.findById(projectId)?.uuid }
            ?: throw SyncException(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, "Project $projectId not found.")
        val restorer = RecordRestorer(state)
        prepare(projectId, sha).use { prepared ->
            val label = "Brought back from ${prepared.target.shortSha}"
            if (dryRun) {
                val (now, then) = recordsAt(projectId, prepared, withNow = true)
                val selection = transaction(state.database) { selection(projectId, prepared, now!!, then, chosen) }
                if (!scriptsAllowed && selection.records.any { it.key.tableName in SCRIPT_RECORD_FOLDERS }) {
                    return@use BringBackOutcome.Refused(RecordRestorer.Outcome.ScriptsRefused)
                }
                val moves = restorer.movesFor(projectId, selection.records.associate { it.key to then.getValue(it.key) })
                return@use BringBackOutcome.Done(selection.dto(prepared, label, applied = false, moves = moves))
            }

            val (_, then) = recordsAt(projectId, prepared, withNow = false)
            origin.entryLabel = label
            var picked: Selection? = null
            val outcome = restorer.bringBack(projectId, origin, scriptsAllowed) {
                val selection = selection(projectId, prepared, exporter.exportRecords(projectId).images, then, chosen)
                if (selection.token != token) {
                    throw BringBackStaleException(
                        selection.dto(prepared, label, applied = false),
                        "What bringing these records back would do has changed since it was reviewed.",
                    )
                }
                picked = selection
                val project = DaoProject[projectId]
                selection.addresses.copies.forEach { copy ->
                    DaoMachineOverride.new {
                        this.project = project
                        targetTable = copy.table
                        recordUuid = copy.uuid
                        fieldName = copy.field
                        valueJson = copy.valueJson
                    }
                }
                selection.records.associate { it.key to then.getValue(it.key) }
            }
            val selection = picked
            if (outcome !is RecordRestorer.Outcome.Done || selection == null) return@use BringBackOutcome.Refused(outcome)
            runCatching { PromptScriptRepoSync.hydrateStore(state, projectUuid, prepared.scratch) }
                .onFailure { logger.warn("Prompt-book PDF hydrate failed bringing records back to project {}: {}", projectId, it.message) }
            runCatching { SceneImageRepoSync.hydrateStore(state, projectUuid, prepared.scratch) }
                .onFailure { logger.warn("Scene image hydrate failed bringing records back to project {}: {}", projectId, it.message) }
            BringBackOutcome.Done(selection.dto(prepared, label, applied = true, entryId = outcome.entries.firstOrNull()?.id))
        }
    }

    /**
     * The show's records as [prepared]'s target makes them — the review's import, in a transaction
     * rolled back — and, [withNow], as they stand. Every record but `project.json`.
     */
    private fun recordsAt(
        projectId: Int,
        prepared: Prepared,
        withNow: Boolean,
    ): Pair<Map<RecordKey, RecordImage>?, Map<RecordKey, RecordImage>> = try {
        transaction(state.database) {
            val project = DaoProject.findById(projectId)
                ?: throw SyncException(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, "Project $projectId not found.")
            val projectKey = RecordKey(SyncDispositions.PROJECT_FOLDER, project.uuid)
            val now = if (withNow) exporter.exportRecords(projectId).images - projectKey else null
            adjustProjectJson(prepared, project)
            importer.replaceInTransaction(projectId, prepared.scratch)
            TransactionManager.current().flushCache()
            val then = exporter.exportRecords(projectId).images - projectKey
            TransactionManager.current().rollback()
            now to then
        }
    } catch (e: ImportError) {
        throw e.toSyncException(pulled = false)
    } catch (e: SerializationException) {
        throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Snapshot ${prepared.target.shortSha} could not be read: ${e.message}", e)
    } catch (e: SQLException) {
        throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Snapshot ${prepared.target.shortSha} could not be imported: ${e.message}", e)
    }

    private class Picked(val key: RecordKey, val change: RestoreChange, val label: String, val needed: Boolean, val nowHash: String?, val thenHash: String)

    private class Selection(val records: List<Picked>, val addresses: Addresses, val addressesDto: RestoreAddressesDto?, val token: String) {
        fun dto(prepared: Prepared, label: String, applied: Boolean, entryId: String? = null, moves: List<String> = emptyList()) = BringBackDto(
            target = prepared.target,
            token = token,
            label = label,
            records = records.map {
                BringBackRecordDto(it.key.tableName, it.key.uuid.toString(), RecordLabeler.nouns(it.key.tableName).first, it.label, it.change, it.needed)
            },
            addresses = addressesDto,
            moves = moves,
            applied = applied,
            entryId = entryId,
        )
    }

    /**
     * [chosen] and every record it needs, transitively: each record [then] holds and [now] lacks that a
     * selected one names by uuid, the prompt book an anchor or note sits in, and each such record that
     * is part of a selected one — a cue's rows, layers, FX, triggers and places in stacks, an entry's
     * prompt-book anchors, and a stack's entries when the stack itself is chosen — since neither a cue
     * nor a stack names its parts. With P7's addresses for the universes and patches it brings back.
     * Refuses a record the review does not offer. Inside a transaction.
     */
    private fun selection(
        projectId: Int,
        prepared: Prepared,
        now: Map<RecordKey, RecordImage>,
        then: Map<RecordKey, RecordImage>,
        chosen: List<RecordKey>,
    ): Selection {
        for (key in chosen) {
            val thenImage = then[key]
            val refusal = when {
                key.tableName == SyncDispositions.PROJECT_FOLDER || key.tableName !in RESTORABLE_FOLDERS ->
                    "A ${key.tableName} record can't be brought back on its own."
                thenImage == null && now[key] != null ->
                    "${RecordLabeler.name(key, now.getValue(key))} was added since: taking it away is an ordinary delete."
                thenImage == null -> "Snapshot ${prepared.target.shortSha} has no ${key.tableName} record ${key.uuid}."
                now[key]?.hash == thenImage.hash -> "${RecordLabeler.name(key, thenImage)} is as the snapshot has it."
                else -> null
            }
            if (refusal != null) throw SyncException(SyncErrorCode.RESTORE_INVALID, refusal)
        }
        val chosenSet = chosen.toSet()
        val back = (then.keys - now.keys).filter { it.tableName in RESTORABLE_FOLDERS }
        val backByUuid = back.groupBy { it.uuid }
        val books = back.filter { it.tableName == RecordFolders.PROMPT_BOOKS }
        val partsOf = HashMap<RecordKey, MutableList<RecordKey>>()
        for (key in back) {
            val obj = then.getValue(key).jsonObjectOrNull() ?: continue
            for ((field, parentFolder) in PART_OF[key.tableName].orEmpty()) {
                val parent = (obj[field] as? JsonPrimitive)?.contentOrNull?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: continue
                partsOf.getOrPut(RecordKey(parentFolder, parent)) { mutableListOf() } += key
            }
        }
        val taken = LinkedHashSet(chosen)
        val queue = ArrayDeque(chosen)
        while (queue.isNotEmpty()) {
            val key = queue.removeFirst()
            val named = referencesOf(canonicalJson.parseToJsonElement(then.getValue(key).json)).flatMap { (field, uuid) ->
                val folder = REFERENCE_FOLDERS[field]
                backByUuid[uuid].orEmpty().filter { folder == null || it.tableName == folder }
            }
            val book = if (key.tableName in RecordFolders.PROMPT_BOOK_CHILDREN) books else emptyList()
            // A stack only needed for one of its entries comes back with that entry, not with all of them.
            val parts = if (key.tableName == RecordFolders.CUE_STACKS && key !in chosenSet) emptyList() else partsOf[key].orEmpty()
            for (next in named + book + parts) if (taken.add(next)) queue.addLast(next)
        }
        val order = ProjectExporter.FOLDERS.withIndex().associate { (i, folder) -> folder to i }
        val picked = taken.map { key ->
            val image = then.getValue(key)
            Picked(
                key = key,
                change = if (key in now) RestoreChange.CHANGED else RestoreChange.BACK,
                label = RecordLabeler.name(key, image),
                needed = key !in chosenSet,
                nowHash = now[key]?.hash,
                thenHash = image.hash,
            )
        }.sortedWith(compareBy({ it.needed }, { order[it.key.tableName] ?: Int.MAX_VALUE }, { it.label }, { it.key.uuid }))
        val project = DaoProject[projectId]
        val venueIds = listOfNotNull(project.venueUuid?.let { VenuePins.venueOf(it)?.id?.value })
        val addresses = addressesFor(project, then, picked.filter { it.change == RestoreChange.BACK }.mapTo(HashSet()) { it.key }, venueIds)
        val addressesDto = addresses.takeIf { it.copies.isNotEmpty() || it.missing.isNotEmpty() }
            ?.let { RestoreAddressesDto(copied = it.copies.size, missing = it.missing) }
        val basis = picked.map { listOf(it.key.tableName, it.key.uuid.toString(), it.change.name, it.nowHash.orEmpty(), it.thenHash) }
            .sortedWith(compareBy({ it[0] }, { it[1] }))
        val token = RecordHasher.sha256Hex(
            canonicalEncode(BringBackTokenBasis.serializer(), BringBackTokenBasis(prepared.target.sha, basis, addressesDto)),
        )
        return Selection(picked, addresses, addressesDto, token)
    }

    /** The show's own fields and its pin may have moved: every window's project list and the venues' cards follow. */
    private fun announce(restored: Computed) {
        val fixtures = state.showOrNull?.fixtures ?: return
        fixtures.projectDetailsChanged(restored.projectId)
        restored.venueIds.forEach { fixtures.projectDetailsChanged(it) }
    }

    /** A venue's draft, or a version, has no snapshots of its own to go back to (D9). */
    private fun requireShow(projectId: Int) {
        val kind = transaction(state.database) { DaoProject.findById(projectId)?.kind }
            ?: throw SyncException(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, "Project $projectId not found.")
        if (kind != ProjectKind.SHOW) {
            throw SyncException(
                SyncErrorCode.VENUE_DRAFT_NOT_SYNCED,
                "A venue's draft never syncs, so it has no snapshots to restore: publish or discard the draft instead.",
            )
        }
    }

    private fun unreachableNote(why: String?) =
        "Couldn't reach the repository${why?.let { " ($it)" } ?: ""}: the restore is on this desk only, and goes out on the next sync."

    /** The target, extracted to a scratch directory, which closing it deletes. */
    private class Prepared(
        val repoPath: Path,
        val scratch: Path,
        val target: CommitInfo,
        val head: CommitInfo,
        val formatVersion: Int,
        /** The target's `project.json` as committed, which each [compute] adjusts afresh. */
        val projectJson: String?,
    ) : AutoCloseable {
        override fun close() {
            runCatching { scratch.toFile().deleteRecursively() }
        }
    }

    /** Step 1 of the review: [sha] resolved to HEAD or an ancestor of it, and its tree extracted. */
    private fun prepare(projectId: Int, sha: String): Prepared {
        val unknown = { SyncException(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, "No snapshot $sha in this show's history.") }
        if (!SHA_PATTERN.matches(sha)) throw unknown()
        val projectUuid = transaction(state.database) { DaoProject.findById(projectId)?.uuid } ?: throw unknown()
        val repoPath = workingTree.pathFor(projectUuid)
        val repo = JGitClient.open(repoPath) ?: throw unknown()
        return repo.use {
            val headId = repo.resolve("HEAD") ?: throw unknown()
            val targetId = runCatching { repo.resolve(sha) }.getOrNull() ?: throw unknown()
            RevWalk(repo).use { walk ->
                val target = runCatching { walk.parseCommit(targetId) }.getOrNull() ?: throw unknown()
                if (!walk.isMergedInto(target, walk.parseCommit(headId))) throw unknown()
            }
            val installs = JGitClient.readBlob(repo, "HEAD", SnapshotEngine.INSTALLS_FILE)
                ?.let { runCatching { canonicalDecode(InstallsJson.serializer(), it).installs }.getOrNull() }
                .orEmpty()
            val target = JGitClient.commitInfo(repo, targetId.name)!!.withAttribution(installs)
            val head = JGitClient.commitInfo(repo, "HEAD")!!.withAttribution(installs)
            val scratch = Files.createTempDirectory("lighting7-restore-")
            var handedOver = false
            try {
                JGitClient.extractTree(repo, target.sha, scratch)
                val format = scratch.resolve("formatVersion.json").takeIf { Files.exists(it) }
                    ?.let { runCatching { canonicalDecode(FormatVersionJson.serializer(), Files.readString(it)).formatVersion }.getOrNull() }
                    ?: throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Snapshot ${target.shortSha} has no readable formatVersion.json.")
                if (format < MIN_SUPPORTED_FORMAT_VERSION) {
                    throw SyncException(
                        SyncErrorCode.RESTORE_TOO_OLD,
                        "Snapshot ${target.shortSha} is format v$format, older than this desk reads (v$MIN_SUPPORTED_FORMAT_VERSION).",
                    )
                }
                if (format > SUPPORTED_FORMAT_VERSION) {
                    throw SyncException(
                        SyncErrorCode.FORMAT_TOO_NEW,
                        "Snapshot ${target.shortSha} is format v$format, newer than this desk reads (v$SUPPORTED_FORMAT_VERSION).",
                    )
                }
                val projectJson = scratch.resolve("project.json").takeIf { Files.exists(it) }?.let { Files.readString(it) }
                handedOver = true
                Prepared(repoPath, scratch, target, head, format, projectJson)
            } finally {
                if (!handedOver) runCatching { scratch.toFile().deleteRecursively() }
            }
        }
    }

    private fun <T> withRepo(prepared: Prepared, block: (org.eclipse.jgit.lib.Repository) -> T): T =
        (JGitClient.open(prepared.repoPath) ?: error("The working tree at ${prepared.repoPath} lost its repo")).use(block)

    /** What the show's records were at [sha], by hash, and its `project.json`: what step 4 checks against. */
    private class Standing(val records: Map<RecordKey, String>, val projectJson: String?)

    private fun standingAt(repo: org.eclipse.jgit.lib.Repository, sha: String) = Standing(
        records = RecordHasher.fromRef(repo, sha).filterValues { !it.isDeleted }.mapValues { it.value.hash },
        projectJson = JGitClient.readBlob(repo, sha, "project.json"),
    )

    /**
     * [image]'s hash as git holds the record once committed: a script's body with its line endings as
     * the working tree's `eol=lf` stores them, which [RecordHasher] hashes. Every other record is JSON,
     * whose strings escape a carriage return, so its hash is the image's own.
     */
    private fun committedHash(key: RecordKey, image: RecordImage): String {
        if (key.tableName != SCRIPTS_FOLDER) return image.hash
        val obj = canonicalJson.parseToJsonElement(image.json).jsonObject
        val body = obj["body"]?.jsonPrimitive?.content ?: return image.hash
        if ('\r' !in body) return image.hash
        val meta = canonicalEncode(JsonElement.serializer(), JsonObject(obj - "body"))
        return RecordHasher.sha256Hex(meta + "\n" + body.replace("\r\n", "\n"))
    }

    private sealed interface Mode {
        object Review : Mode

        /** Commit the import, unless the records exported first differ from [standing]. */
        class Restore(val standing: Standing) : Mode
    }

    private class WriteLanded : RuntimeException("A write landed during the restore")

    private class Computed(
        val projectId: Int,
        /** The venues the show was on before and after, whose cards list it. */
        val venueIds: List<Int>,
        val dto: RestoreReviewDto,
        val pinned: Int?,
        val installed: Int?,
        /** The project fields the import wrote, for step 7's merge check. */
        val restoredFields: ProjectJson,
    )

    /**
     * The review (§3 steps 3–5), or step 4 of a restore: in one transaction, the project exported,
     * the target imported, exported again; rolled back for a review, committed with P7's override
     * copies for a restore.
     */
    private fun compute(projectId: Int, prepared: Prepared, mode: Mode): Computed {
        val live = isCurrent(projectId)
        val heldNow = state.venueHold.status.value.let { it.held && it.projectId == projectId }
        return try {
            transaction(state.database) {
                val project = DaoProject.findById(projectId)
                    ?: throw SyncException(SyncErrorCode.RESTORE_COMMIT_UNKNOWN, "Project $projectId not found.")
                val projectKey = RecordKey(SyncDispositions.PROJECT_FOLDER, project.uuid)
                val now = exporter.exportRecords(projectId).images
                if (mode is Mode.Restore) {
                    val standingNow = now.filterKeys { it != projectKey }.mapValues { (key, image) -> committedHash(key, image) }
                    if (standingNow != mode.standing.records || now[projectKey]?.json != mode.standing.projectJson) {
                        throw WriteLanded()
                    }
                }
                val nowVenueId = project.venueUuid?.let { VenuePins.venueOf(it)?.id?.value }
                val nameKept = adjustProjectJson(prepared, project)

                importer.replaceInTransaction(projectId, prepared.scratch)
                TransactionManager.current().flushCache()
                val then = exporter.exportRecords(projectId).images
                val judgement = ShowBehind.judge(state, projectId)
                val thenProject = DaoProject.findById(projectId)!!
                val thenVenueId = thenProject.venueUuid?.let { VenuePins.venueOf(it)?.id?.value }

                val nowJson = now[projectKey]?.let { canonicalDecode(ProjectJson.serializer(), it.json) }
                val thenJson = then[projectKey]?.let { canonicalDecode(ProjectJson.serializer(), it.json) }
                    ?: error("The restored project exported no project.json")

                val records = compareRecords(now - projectKey, then - projectKey)
                val back = records.filter { it.change == RestoreChange.BACK }.mapTo(HashSet()) { it.key }
                val addresses = addressesFor(project, then, back, listOfNotNull(nowVenueId, thenVenueId).distinct())
                if (mode is Mode.Restore) addresses.copies.forEach { copy ->
                    DaoMachineOverride.new {
                        this.project = thenProject
                        targetTable = copy.table
                        recordUuid = copy.uuid
                        fieldName = copy.field
                        valueJson = copy.valueJson
                    }
                }

                val projectDto = projectChange(nowJson, thenJson, nameKept)
                val nowPin = nowJson?.let { pinOf(it.venueUuid, it.venueVersion) }
                val thenPin = pinOf(thenJson.venueUuid, thenJson.venueVersion)
                val venue = if (nowPin == null && thenPin == null) null else {
                    val behind = if (thenPin == null) VenueBehind.NONE else judgement.behind
                    RestoreVenueDto(
                        now = nowPin,
                        then = thenPin,
                        behind = behind,
                        runAgainst = live && behind == VenueBehind.OUTPUT && !heldNow,
                    )
                }
                val addressesDto = addresses.takeIf { it.copies.isNotEmpty() || it.missing.isNotEmpty() }
                    ?.let { RestoreAddressesDto(copied = it.copies.size, missing = it.missing) }
                val noChanges = records.isEmpty() && projectDto?.differs != true && nowPin == thenPin

                val dto = RestoreReviewDto(
                    target = prepared.target,
                    head = prepared.head,
                    token = tokenOf(prepared.target.sha, records, projectDto, venue, addressesDto),
                    noChanges = noChanges,
                    counts = RestoreCounts(
                        back = records.count { it.change == RestoreChange.BACK },
                        gone = records.count { it.change == RestoreChange.GONE },
                        changed = records.count { it.change == RestoreChange.CHANGED },
                    ),
                    kinds = kindsOf(records),
                    project = projectDto,
                    venue = venue,
                    addresses = addressesDto,
                    live = live,
                    sync = syncOf(projectId),
                )
                if (mode is Mode.Review) TransactionManager.current().rollback()
                Computed(
                    projectId = projectId,
                    venueIds = listOfNotNull(nowVenueId, thenVenueId).distinct(),
                    dto = dto,
                    pinned = thenPin?.version,
                    installed = judgement.installed,
                    restoredFields = thenJson,
                )
            }
        } catch (e: ImportError) {
            throw e.toSyncException(pulled = false)
        } catch (e: SerializationException) {
            throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Snapshot ${prepared.target.shortSha} could not be read: ${e.message}", e)
        } catch (e: SQLException) {
            throw SyncException(SyncErrorCode.RESTORE_UNREADABLE, "Snapshot ${prepared.target.shortSha} could not be imported: ${e.message}", e)
        }
    }

    /**
     * The scratch `project.json` keeps the show's current *In production* (P5), and its current name
     * where another project now holds the snapshot's (P6). Answers the snapshot's name when it kept it
     * off. Inside the caller's transaction.
     */
    private fun adjustProjectJson(prepared: Prepared, project: DaoProject): String? {
        val original = prepared.projectJson ?: return null
        val path = prepared.scratch.resolve("project.json")
        val json = canonicalDecode(ProjectJson.serializer(), original)
        val taken = json.name != project.name &&
            DaoProject.find { DaoProjects.name eq json.name }.any { it.id != project.id }
        val adjusted = json.copy(
            inProduction = project.inProduction,
            name = if (taken) project.name else json.name,
        )
        Files.writeString(path, if (adjusted == json) original else canonicalEncode(ProjectJson.serializer(), adjusted))
        return json.name.takeIf { taken }
    }

    private class Listed(val key: RecordKey, val change: RestoreChange, val label: String)

    /** Step 4 of the review: *then* against *now*, by key and hash. */
    private fun compareRecords(now: Map<RecordKey, RecordImage>, then: Map<RecordKey, RecordImage>): List<Listed> {
        val out = mutableListOf<Listed>()
        for ((key, image) in then) {
            val was = now[key]
            when {
                was == null -> out += Listed(key, RestoreChange.BACK, RecordLabeler.name(key, image))
                was.hash != image.hash -> out += Listed(key, RestoreChange.CHANGED, RecordLabeler.name(key, image))
            }
        }
        for ((key, image) in now) {
            if (key !in then) out += Listed(key, RestoreChange.GONE, RecordLabeler.name(key, image))
        }
        return out
    }

    private fun kindsOf(records: List<Listed>): List<RestoreKindDto> {
        val order = ProjectExporter.FOLDERS.withIndex().associate { (i, folder) -> folder to i }
        return records.groupBy { it.key.tableName }
            .entries
            .sortedWith(compareBy({ order[it.key] ?: Int.MAX_VALUE }, { it.key }))
            .map { (folder, listed) ->
                val (noun, plural) = RecordLabeler.nouns(folder)
                RestoreKindDto(
                    folder = folder,
                    noun = noun,
                    plural = plural,
                    records = listed.sortedWith(compareBy({ it.label }, { it.key.uuid }))
                        .map { RestoreRecordDto(it.key.uuid.toString(), it.label, it.change) },
                )
            }
    }

    private class OverrideCopy(val table: String, val uuid: UUID, val field: String, val valueJson: String)

    private class Addresses(val copies: List<OverrideCopy>, val missing: List<String>)

    /**
     * P7: the machine overrides a venue holds for a universe or patch the restore makes the show's own
     * again, to be copied to the show's id, and the restored universes left with no address here.
     */
    private fun addressesFor(
        show: DaoProject,
        then: Map<RecordKey, RecordImage>,
        back: Set<RecordKey>,
        venueIds: List<Int>,
    ): Addresses {
        val candidates = back.filter { it.tableName in ADDRESSED_FOLDERS }.mapTo(HashSet()) { it.uuid }
        if (candidates.isEmpty()) return Addresses(emptyList(), emptyList())
        val existing = DaoMachineOverride.find { DaoMachineOverrides.project eq show.id }
            .mapTo(HashSet()) { Triple(it.targetTable, it.recordUuid, it.fieldName) }
        val copies = LinkedHashMap<Triple<String, UUID, String>, OverrideCopy>()
        for (venueId in venueIds) {
            for (o in DaoMachineOverride.find { DaoMachineOverrides.project eq venueId }) {
                if (o.recordUuid !in candidates) continue
                val key = Triple(o.targetTable, o.recordUuid, o.fieldName)
                if (key in existing || key in copies) continue
                copies[key] = OverrideCopy(o.targetTable, o.recordUuid, o.fieldName, o.valueJson)
            }
        }
        val addressed = existing + copies.keys
        val missing = back.filter { it.tableName == Overrides.UNIVERSE_CONFIGS }
            .filter { Triple(Overrides.UNIVERSE_CONFIGS, it.uuid, Overrides.FIELD_ADDRESS) !in addressed }
            .map { universeName(then[it]) }
            .sorted()
        return Addresses(copies.values.toList(), missing)
    }

    private fun universeName(image: RecordImage?): String {
        val json = image?.let { runCatching { canonicalDecode(UniverseConfigJson.serializer(), it.json) }.getOrNull() }
            ?: return "A universe"
        return if (json.subnet == 0) "Universe ${json.universe}" else "Universe ${json.subnet}:${json.universe}"
    }

    private fun projectChange(now: ProjectJson?, then: ProjectJson, nameKept: String?): RestoreProjectDto? {
        fun size(json: ProjectJson?) = json?.let { RestoreStageSize(it.stageWidthM, it.stageDepthM, it.stageHeightM) }
        val name = if (now?.name != then.name) RestoreText(now?.name, then.name) else null
        val description = if (now?.description != then.description) RestoreText(now?.description, then.description) else null
        val stageSize = if (size(now) != size(then)) RestoreStageSizeChange(size(now), size(then)) else null
        val originNow = now?.let { pinOf(it.detachedFromVenueUuid, it.detachedFromVersion) }
        val originThen = pinOf(then.detachedFromVenueUuid, then.detachedFromVersion)
        val origin = if (originNow != originThen) RestoreOriginChange(originNow, originThen) else null
        if (name == null && description == null && stageSize == null && origin == null && nameKept == null) return null
        return RestoreProjectDto(
            name = name,
            description = description,
            stageSize = stageSize,
            origin = origin,
            nameKept = nameKept,
        )
    }

    /** A pin or origin as the review names it, its venue's name read here. Inside a transaction. */
    private fun pinOf(venueUuid: String?, version: Int?): RestoreVenueRef? {
        val uuid = venueUuid?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
        version ?: return null
        return RestoreVenueRef(uuid = uuid.toString(), name = VenuePins.venueOf(uuid)?.name, version = version)
    }

    private fun syncOf(projectId: Int): RestoreSyncDto {
        val cfg = DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
        return RestoreSyncDto(
            repo = cfg?.repoUrl?.takeIf { it.isNotBlank() },
            lastSyncedAt = cfg?.lastSyncedAt?.toIsoUtc(),
            push = state.syncPushEnabled,
        )
    }

    /** Step 7: the project fields a diverged merge took from the remote over the restored ones. */
    private fun lostToMerge(projectId: Int, restored: ProjectJson): List<String> = transaction(state.database) {
        val project = DaoProject.findById(projectId) ?: return@transaction emptyList()
        buildList {
            if (project.name != restored.name) add("Merging with another desk's changes kept its name, “${project.name}”, over the restored “${restored.name}”.")
            if (project.description != restored.description) add("Merging with another desk's changes kept its description over the restored one.")
            if (project.stageWidthM != restored.stageWidthM || project.stageDepthM != restored.stageDepthM || project.stageHeightM != restored.stageHeightM) {
                add("Merging with another desk's changes kept its stage size over the restored one.")
            }
        }
    }

    private fun isCurrent(projectId: Int): Boolean =
        runCatching { state.projectManager.currentProject.id.value == projectId }.getOrDefault(false)

    @Serializable
    private data class BringBackTokenBasis(val sha: String, val records: List<List<String>>, val addresses: RestoreAddressesDto?)

    @Serializable
    private data class TokenBasis(
        val sha: String,
        val records: List<List<String>>,
        val project: RestoreProjectDto?,
        val venue: RestoreVenueDto?,
        val addresses: RestoreAddressesDto?,
    )

    private fun tokenOf(
        sha: String,
        records: List<Listed>,
        project: RestoreProjectDto?,
        venue: RestoreVenueDto?,
        addresses: RestoreAddressesDto?,
    ): String {
        val listed = records.map { listOf(it.key.tableName, it.key.uuid.toString(), it.change.name) }
            .sortedWith(compareBy({ it[0] }, { it[1] }, { it[2] }))
        return RecordHasher.sha256Hex(canonicalEncode(TokenBasis.serializer(), TokenBasis(sha, listed, project, venue, addresses)))
    }

    companion object {
        const val MAX_NOTE_LENGTH = 200

        /** The clear point a restore leaves in the show history. */
        const val HISTORY_REASON = "Restored a snapshot"

        private val SHA_PATTERN = Regex("^[0-9a-fA-F]{4,40}$")
        private const val SCRIPTS_FOLDER = "scripts"
        private val ADDRESSED_FOLDERS = setOf(Overrides.UNIVERSE_CONFIGS, "fixturePatches")
        private val WHEN_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC)

        /** A first sync that fails on the way to the remote: the restore goes ahead on this desk (D5). */
        private val UNREACHABLE = setOf(
            SyncErrorCode.AUTH_FAILED, SyncErrorCode.GITHUB_RETIRED, SyncErrorCode.NOT_A_CLOUD_REPO, SyncErrorCode.PUSH_REJECTED,
            SyncErrorCode.CLOUD_NOT_ADOPTED, SyncErrorCode.SYNC_OFF, SyncErrorCode.REPO_NOT_FOUND, SyncErrorCode.SYNC_QUOTA_EXCEEDED,
            SyncErrorCode.SYNC_STORAGE_UNAVAILABLE,
        )

        private val logger = LoggerFactory.getLogger(SnapshotRestore::class.java)

        /** The folder a reference field names; a `…Uuid` field not here may name a record of any folder. */
        private val REFERENCE_FOLDERS = with(RecordFolders) {
            mapOf(
                "cueUuid" to CUES, "cueStackUuid" to CUE_STACKS, "entryUuid" to CUE_STACK_ENTRIES, "lookUuid" to LOOKS,
                "paletteUuid" to PALETTES, "speedMasterUuid" to SPEED_MASTERS, "rateSpeedMasterUuid" to SPEED_MASTERS,
                "followTargetUuid" to SPEED_MASTERS, "universeConfigUuid" to UNIVERSE_CONFIGS, "riggingUuid" to RIGGINGS,
                "elementUuid" to STAGE_ELEMENTS, "seatElementUuid" to STAGE_ELEMENTS, "fixturePatchUuid" to FIXTURE_PATCHES,
                "patchUuid" to FIXTURE_PATCHES, "groupUuid" to FIXTURE_GROUPS, "scriptUuid" to SCRIPTS,
            )
        }

        /** `…Uuid` fields that name no record of the show. */
        private val NOT_REFERENCES = setOf("venueUuid", "detachedFromVenueUuid", "runUuid", "projectUuid")

        /** A record's parts, by the field that names the record they are part of, and its folder. */
        private val PART_OF: Map<String, List<Pair<String, String>>> = with(RecordFolders) {
            CUE_CHILDREN.associateWith { listOf("cueUuid" to CUES) } + mapOf(
                CUE_STACK_ENTRIES to listOf("cueUuid" to CUES, "cueStackUuid" to CUE_STACKS),
                PROMPT_BOOK_ANCHORS to listOf("entryUuid" to CUE_STACK_ENTRIES),
            )
        }

        /**
         * Every record uuid [element] names, with the field that names it: `…Uuid` fields and `…Uuids`
         * lists anywhere in it, and the palette of each `pal:` reference in a value, a list's included.
         */
        internal fun referencesOf(element: JsonElement): List<Pair<String, UUID>> {
            val out = ArrayList<Pair<String, UUID>>()
            fun uuid(text: String) = runCatching { UUID.fromString(text) }.getOrNull()
            fun walk(e: JsonElement, field: String?) {
                when (e) {
                    is JsonObject -> for ((k, v) in e) walk(v, k)
                    is kotlinx.serialization.json.JsonArray -> for (v in e) walk(v, field?.takeIf { it.endsWith("Uuids") }?.removeSuffix("s"))
                    is JsonPrimitive -> {
                        if (!e.isString) return
                        val text = e.content
                        val palettes = text.split(',').mapNotNull(::parsePaletteColourRefUuid)
                        when {
                            palettes.isNotEmpty() -> palettes.forEach { out += "paletteUuid" to it }
                            field != null && field.endsWith("Uuid") && field !in NOT_REFERENCES -> uuid(text)?.let { out += field to it }
                        }
                    }
                }
            }
            walk(element, null)
            return out
        }

        private val SCRIPT_RECORD_FOLDERS = setOf(RecordFolders.SCRIPTS, RecordFolders.FX_DEFINITIONS)

        fun formatWhen(whenMs: Long): String = WHEN_FORMAT.format(Instant.ofEpochMilli(whenMs))
    }
}

/**
 * An [ImportError] from a target's tree (or, with [pulled], from the first sync's import) as the
 * restore's codes: the pin's `VENUE_MISSING`, a uuid or kind that doesn't match as
 * `PROJECT_MISMATCH`, a target holding a folder this desk cannot apply as `FORMAT_TOO_NEW`, else
 * `RESTORE_UNREADABLE`.
 */
internal fun ImportError.toSyncException(pulled: Boolean): SyncException {
    val text = message ?: "Import failed"
    val code = when {
        code == VenuePins.CODE_VENUE_MISSING -> SyncErrorCode.VENUE_MISSING
        code == null && status == io.ktor.http.HttpStatusCode.Conflict -> SyncErrorCode.PROJECT_MISMATCH
        // A target's format is checked before its import, so its 422 is a folder this desk cannot apply.
        status == io.ktor.http.HttpStatusCode.UnprocessableEntity && !pulled -> SyncErrorCode.FORMAT_TOO_NEW
        else -> SyncErrorCode.RESTORE_UNREADABLE
    }
    return SyncException(code, if (pulled) "Syncing first could not import the pulled show: $text" else text, this)
}

/** `RESTORE_REVIEW_STALE`, carrying the review the restore would now make. */
class RestoreStaleException(val review: RestoreReviewDto, message: String) :
    SyncException(SyncErrorCode.RESTORE_REVIEW_STALE, message)

@Serializable
enum class RestoreChange { BACK, GONE, CHANGED }

@Serializable
data class RestoreCounts(val back: Int, val gone: Int, val changed: Int)

@Serializable
data class RestoreRecordDto(val uuid: String, val label: String, val change: RestoreChange)

/** One kind of record the restore changes, in the importer's folder order, its records by label. */
@Serializable
data class RestoreKindDto(
    val folder: String,
    val noun: String,
    val plural: String,
    val records: List<RestoreRecordDto>,
)

@Serializable
data class RestoreText(val now: String?, val then: String?)

@Serializable
data class RestoreStageSize(val widthM: Double?, val depthM: Double?, val heightM: Double?)

@Serializable
data class RestoreStageSizeChange(val now: RestoreStageSize?, val then: RestoreStageSize?)

/** A venue pin, or a detached show's origin: the venue's uuid, its name where this desk has it, the version. */
@Serializable
data class RestoreVenueRef(val uuid: String, val name: String? = null, val version: Int)

@Serializable
data class RestoreOriginChange(val now: RestoreVenueRef?, val then: RestoreVenueRef?)

/** The show's own fields the restore changes (D7), each `{now, then}`; [nameKept] the snapshot's name P6 left off. */
@Serializable
data class RestoreProjectDto(
    val name: RestoreText? = null,
    val description: RestoreText? = null,
    val stageSize: RestoreStageSizeChange? = null,
    val origin: RestoreOriginChange? = null,
    val nameKept: String? = null,
) {
    /** Whether a field the restore writes differs; a kept name alone is no change. */
    val differs: Boolean get() = name != null || description != null || stageSize != null || origin != null
}

/**
 * The venue pin before and after (D7), present when either side is on a venue. [behind] is the
 * restored pin's judgement; [runAgainst] that the live show will run against it rather than be held (D8).
 */
@Serializable
data class RestoreVenueDto(
    val now: RestoreVenueRef?,
    val then: RestoreVenueRef?,
    val behind: VenueBehind,
    val runAgainst: Boolean,
)

/** P7: the venue's machine overrides the restore copies to the show, and the universes left with no address. */
@Serializable
data class RestoreAddressesDto(val copied: Int, val missing: List<String>)

@Serializable
data class RestoreSyncDto(val repo: String?, val lastSyncedAt: String?, val push: Boolean)

/** `GET …/sync/restore/{sha}`'s answer (sync-restore plan §3 "The review"). */
@Serializable
data class RestoreReviewDto(
    val target: CommitInfo,
    val head: CommitInfo?,
    val token: String,
    val noChanges: Boolean,
    val counts: RestoreCounts,
    val kinds: List<RestoreKindDto>,
    val project: RestoreProjectDto?,
    val venue: RestoreVenueDto?,
    val addresses: RestoreAddressesDto?,
    val live: Boolean,
    val sync: RestoreSyncDto,
)

/** One record of a snapshot, as the review lists it: its folder and uuid. */
@Serializable
data class BringBackRecordRef(val folder: String = "", val uuid: String = "")

/** `POST …/sync/restore/records`'s body: the records chosen, and the selection's token unless [dryRun]. */
@Serializable
data class BringBackRequest(
    val sha: String = "",
    val records: List<BringBackRecordRef> = emptyList(),
    val token: String? = null,
    val dryRun: Boolean = false,
)

/** A record bringing back puts back: one chosen, or [needed] by one chosen. */
@Serializable
data class BringBackRecordDto(
    val folder: String,
    val uuid: String,
    val noun: String,
    val label: String,
    val change: RestoreChange,
    val needed: Boolean,
)

/**
 * `POST …/sync/restore/records`'s answer: what bringing the chosen records back puts back, chosen
 * first, and its [token]; [applied] once written, with the show-history entry [entryId] it made.
 */
@Serializable
data class BringBackDto(
    val target: CommitInfo,
    val token: String,
    val label: String,
    val records: List<BringBackRecordDto>,
    /** P7: the venue's overrides copied to the show, and the universes left with no address here. */
    val addresses: RestoreAddressesDto? = null,
    /** What it moves on the live stage while a cue is held, as an undo's confirm names it; a dry run's only. */
    val moves: List<String> = emptyList(),
    val applied: Boolean,
    val entryId: String? = null,
)

/** A bring-back's answer: done, or refused by the upsert as an undo is refused. */
internal sealed interface BringBackOutcome {
    data class Done(val result: BringBackDto) : BringBackOutcome
    data class Refused(val outcome: RecordRestorer.Outcome) : BringBackOutcome
}

/** `RESTORE_REVIEW_STALE` for a bring-back, carrying the selection it would now make. */
class BringBackStaleException(val selection: BringBackDto, message: String) :
    SyncException(SyncErrorCode.RESTORE_REVIEW_STALE, message)

@Serializable
data class RestoreRequest(val sha: String = "", val token: String = "", val note: String? = null)

/** `POST …/sync/restore`'s answer: the restore's commit, the show as it stood ([before], D3's undo). */
@Serializable
data class RestoreResultDto(
    val commit: CommitInfo,
    val before: CommitInfo,
    val counts: RestoreCounts,
    val sync: SyncRunResult? = null,
    val notes: List<String>,
)
