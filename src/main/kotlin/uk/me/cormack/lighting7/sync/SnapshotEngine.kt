package uk.me.cormack.lighting7.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import uk.me.cormack.lighting7.state.State
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Snapshots the current database state of a project into its cloud-sync working
 * tree as a single git commit.
 *
 * Pipeline (on `Dispatchers.IO`): ensure the repo exists, wipe non-metadata files,
 * re-export the canonical JSON via [ProjectExporter], **derive and write tombstones**
 * for any record present in `sync_state` but missing from the freshly-exported tree,
 * stage everything, and commit if anything changed.
 *
 * Tombstone derivation covers two cases:
 *  * **Local deletion since last sync** — `sync_state` row with `lastSyncedIsDeleted=false`
 *    but the live DB no longer has that UUID.
 *  * **Carry-forward** — `sync_state` row with `lastSyncedIsDeleted=true` (a previously-pulled
 *    tombstone). The wipe step nuked the on-disk tombstone before re-export; without
 *    rewriting it, this install would silently drop the deletion on its next push and
 *    a peer who never saw the deletion would resurrect the record.
 *
 * The wipe-then-re-export strategy is what makes deletions show up: without it,
 * stale `cues/{uuid}.json` files would linger after a row is removed and `git
 * status` would report no change.
 */
class SnapshotEngine(private val state: State) {

    private val workingTree = SyncWorkingTree(state)
    private val exporter = ProjectExporter(state)

    /**
     * Capture the project's current state as a git commit. Returns a
     * `noChanges = true` response without committing if the export produced no
     * tree-level diff against HEAD.
     *
     * The caller resolves the project and install identity (typically inside the
     * REST handler's `withProject` block); the engine takes plain values so its
     * `Dispatchers.IO` block doesn't have to reach back into Exposed.
     */
    suspend fun snapshot(
        projectId: Int,
        projectUuid: UUID,
        installUuid: UUID,
        installFriendlyName: String,
        message: String?,
    ): SnapshotResponse {
        val path = workingTree.pathFor(projectUuid)
        val summary = message?.takeIf { it.isNotBlank() }?.trim()
            ?: "Snapshot ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
        val shortInstall = installUuid.toString().take(8)
        val authorEmail = "$shortInstall@$INSTALL_EMAIL_DOMAIN"
        val commitMessage = "$installFriendlyName: $summary [install:$shortInstall]"

        return withContext(Dispatchers.IO) {
            workingTree.ensureInitialised(path).use { repo ->
                workingTree.cleanTrackedFiles(path)
                val exportResult = exporter.export(projectId, path)

                // Derive tombstones: anything in `sync_state` not matched by a live
                // record we just wrote gets a tombstone marker. Covers both freshly-deleted
                // DB rows and carry-forward of tombstones pulled from previous syncs.
                val syncStateKeys = transaction(state.database) {
                    DaoSyncState.find { DaoSyncStates.project eq projectId }
                        .map { RecordKey(it.tableName, it.recordUuid) }
                        .toSet()
                }
                exporter.writeTombstones(path, syncStateKeys - exportResult.liveKeys)

                if (!JGitClient.stageAll(repo)) {
                    return@withContext SnapshotResponse(
                        noChanges = true,
                        workingTreePath = path.toString(),
                        commit = null,
                    )
                }
                val commit = JGitClient.commit(repo, installFriendlyName, authorEmail, commitMessage)
                SnapshotResponse(
                    noChanges = false,
                    workingTreePath = path.toString(),
                    commit = commit,
                )
            }
        }
    }

    companion object {
        /** Domain for synthesised commit-author emails ({shortUuid}@{domain}). */
        const val INSTALL_EMAIL_DOMAIN = "lighting7.local"
    }
}

/**
 * Result of a snapshot attempt. `commit` is null iff `noChanges` is true.
 */
@Serializable
data class SnapshotResponse(
    val noChanges: Boolean,
    val workingTreePath: String,
    val commit: CommitInfo?,
)
