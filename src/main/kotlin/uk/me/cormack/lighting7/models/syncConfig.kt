package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import uk.me.cormack.lighting7.sync.JGitClient

/**
 * Per-project cloud-sync configuration. Today only `branch` is acted on; the
 * other fields exist so the UI can collect remote details ahead of the
 * push/pull work, but the backend ignores them. `lastSyncedSha` /
 * `lastSyncedAtMs` start null and are populated once a successful push exists.
 *
 * Machine-local — never synced to the cloud repo itself (storing PATs or
 * remote URLs in synced JSON would be a credential leak waiting to happen).
 */
object DaoSyncConfigs : IntIdTable("sync_configs") {
    val project = reference("project_id", DaoProjects).uniqueIndex()
    val repoUrl = varchar("repo_url", 512).nullable()
    val branch = varchar("branch", 128).default(JGitClient.DEFAULT_BRANCH)
    val enabled = bool("enabled").default(false)
    val autoSyncEnabled = bool("auto_sync_enabled").default(false)
    val autoSyncIntervalMs = long("auto_sync_interval_ms").nullable()
    val lastSyncedSha = varchar("last_synced_sha", 64).nullable()
    val lastSyncedAtMs = long("last_synced_at_ms").nullable()
}

class DaoSyncConfig(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncConfig>(DaoSyncConfigs)

    var project by DaoProject referencedOn DaoSyncConfigs.project
    var repoUrl by DaoSyncConfigs.repoUrl
    var branch by DaoSyncConfigs.branch
    var enabled by DaoSyncConfigs.enabled
    var autoSyncEnabled by DaoSyncConfigs.autoSyncEnabled
    var autoSyncIntervalMs by DaoSyncConfigs.autoSyncIntervalMs
    var lastSyncedSha by DaoSyncConfigs.lastSyncedSha
    var lastSyncedAtMs by DaoSyncConfigs.lastSyncedAtMs
}
