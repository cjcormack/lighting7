package uk.me.cormack.lighting7.sync

import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSyncConfig
import uk.me.cormack.lighting7.models.DaoSyncConfigs
import uk.me.cormack.lighting7.models.DaoSyncState
import uk.me.cormack.lighting7.models.DaoSyncStates
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's `sync_state` rows: the last tree this install and the remote agreed on, one
 * `(hash, isDeleted)` per record. It is the three-way diff's base and the snapshot's tombstone
 * source, so a project with a repo and no rows is not "nothing agreed yet" but a hole: every
 * record both sides hold with different content is a conflict ([ThreeWayDiff]'s `base == null`
 * arm) and a local deletion writes no tombstone ([SnapshotEngine]).
 */
internal object SyncStateBookkeeping {

    private val logger = LoggerFactory.getLogger(SyncStateBookkeeping::class.java)

    /**
     * Replace [projectId]'s rows with one per record in the tree at [sha]. Skipped when every
     * row is already at [sha] (the NO_OP / repeat-snapshot case).
     */
    fun bootstrapAt(database: Database, projectId: Int, repo: Repository, sha: String) {
        val alreadyAtSha = transaction(database) {
            val rows = DaoSyncState.find { DaoSyncStates.project eq projectId }
            val any = rows.firstOrNull()
            any != null && rows.all { it.lastSyncedSha == sha }
        }
        if (alreadyAtSha) return

        val snapshots = RecordHasher.fromRef(repo, sha)
        transaction(database) {
            DaoSyncState.find { DaoSyncStates.project eq projectId }.forEach { it.delete() }
            writeRows(projectId, sha, snapshots)
        }
    }

    /** One row per record in [snapshots], all at [sha]. Must be called inside a transaction. */
    private fun writeRows(projectId: Int, sha: String, snapshots: Map<RecordKey, RecordSnapshot>) {
        val project = DaoProject.findById(projectId) ?: return
        for ((key, snap) in snapshots) {
            DaoSyncState.new {
                this.project = project
                this.tableName = key.tableName
                this.recordUuid = key.uuid
                this.lastSyncedSha = sha
                this.lastSyncedHash = snap.hash
                this.lastSyncedIsDeleted = snap.isDeleted
            }
        }
    }

    /**
     * Seed the rows from `sync_configs.lastSyncedSha` when the project has none. That watermark
     * names the last agreed tree on every path that sets it — the clone a remote project was
     * imported from, the remote tip a pull-only run kept, the HEAD a push or merge left — so it
     * is exactly what a bootstrap there would have recorded.
     *
     * The repair for a project imported before [CloudSyncImporter] seeded its rows: until its
     * first sync that reached a terminal bootstrap it had none, and a pull-only desk whose
     * every run ended local-ahead never got any. Runs before a snapshot derives tombstones.
     * A watermark the working tree lacks (a re-created repo) seeds nothing.
     */
    fun seedIfMissing(database: Database, projectId: Int, repo: Repository) {
        val watermark = transaction(database) {
            if (!DaoSyncState.find { DaoSyncStates.project eq projectId }.empty()) return@transaction null
            DaoSyncConfig.find { DaoSyncConfigs.project eq projectId }.firstOrNull()
                ?.lastSyncedSha?.takeIf { it.isNotBlank() }
        } ?: return
        if (!commitExists(repo, watermark)) {
            // Once per project and watermark: a snapshot runs on every auto-sync tick.
            if (warnedMissing.add(projectId to watermark)) {
                logger.warn(
                    "Project {} has no sync_state and its last-synced commit {} is not in the working tree; " +
                        "its next diverged sync has no base.",
                    projectId, watermark,
                )
            }
            return
        }
        val snapshots = RecordHasher.fromRef(repo, watermark)
        // Insert only while still empty, in the transaction that checks: the manual snapshot route
        // does not hold the sync engine's project lock, so a sync may have bootstrapped meanwhile,
        // at a newer commit this older watermark must not overwrite.
        val seeded = transaction(database) {
            if (!DaoSyncState.find { DaoSyncStates.project eq projectId }.empty()) return@transaction false
            writeRows(projectId, watermark, snapshots)
            true
        }
        // A watermark tree with no records seeds nothing and is walked again next time; say so only
        // when rows were written.
        if (seeded && snapshots.isNotEmpty()) {
            logger.info("Seeded sync_state for project {} from its last-synced commit {}", projectId, watermark)
        }
    }

    private val warnedMissing: MutableSet<Pair<Int, String>> = ConcurrentHashMap.newKeySet()

    private fun commitExists(repo: Repository, sha: String): Boolean = runCatching {
        val id = repo.resolve(sha) ?: return false
        RevWalk(repo).use { it.parseCommit(id) }
        true
    }.getOrDefault(false)
}
