package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.javatime.duration

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import uk.me.cormack.lighting7.sync.JGitClient

/**
 * Per-project cloud-sync configuration. A project is *synced* iff `repoUrl` is set —
 * there is no separate `enabled` flag; attaching a repo enables sync and disconnecting
 * clears it (see the `/sync/disconnect` handler). `autoSyncEnabled` defaults on for
 * every newly-attached repo; `lastSyncedSha` / `lastSyncedAt` start null and are
 * populated once a successful push exists.
 *
 * Machine-local — never synced to the cloud repo itself (storing PATs or
 * remote URLs in synced JSON would be a credential leak waiting to happen).
 */
object DaoSyncConfigs : IntIdTable("sync_configs") {
    val project = reference("project_id", DaoProjects).uniqueIndex()
    val repoUrl = varchar("repo_url", 512).nullable()
    val branch = varchar("branch", 128).default(JGitClient.DEFAULT_BRANCH)
    val autoSyncEnabled = bool("auto_sync_enabled").default(false)
    val autoSyncInterval = duration("auto_sync_interval").nullable()
    val lastSyncedSha = varchar("last_synced_sha", 64).nullable()
    val lastSyncedAt = utcInstant("last_synced_at").nullable()
}

class DaoSyncConfig(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncConfig>(DaoSyncConfigs)

    var project by DaoProject referencedOn DaoSyncConfigs.project
    var repoUrl by DaoSyncConfigs.repoUrl
    var branch by DaoSyncConfigs.branch
    var autoSyncEnabled by DaoSyncConfigs.autoSyncEnabled
    var autoSyncInterval by DaoSyncConfigs.autoSyncInterval
    var lastSyncedSha by DaoSyncConfigs.lastSyncedSha
    var lastSyncedAt by DaoSyncConfigs.lastSyncedAt

    /** A project is synced iff a repository is attached. */
    val synced: Boolean get() = !repoUrl.isNullOrBlank()
}
