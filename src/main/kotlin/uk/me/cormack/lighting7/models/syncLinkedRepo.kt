package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Remembered set of repositories a project has ever been linked to. Populated on
 * disconnect (see the `/sync/disconnect` handler) so the operator can reconnect to a
 * previously-linked repo without going through the create-new-repo flow again.
 *
 * Reconnecting to a remembered repo is the *only* sanctioned way to attach an existing,
 * non-empty repository to an existing project: the remote already holds this project's
 * uuid + history, so the next sync classifies cleanly instead of treating it as a
 * divergent foreign history. Attaching an arbitrary existing repo stays blocked (see
 * [uk.me.cormack.lighting7.sync.RemoteSyncEngine]'s project-uuid guard).
 *
 * One row per `(project, repoUrl)`. Machine-local — never serialised to the cloud repo,
 * for the same credential-hygiene reason as [DaoSyncConfigs].
 */
object DaoSyncLinkedRepos : IntIdTable("sync_linked_repos") {
    val project = reference("project_id", DaoProjects)
    val repoUrl = varchar("repo_url", 512)
    val firstLinkedAtMs = long("first_linked_at_ms")
    val lastLinkedAtMs = long("last_linked_at_ms")

    init {
        uniqueIndex(project, repoUrl)
    }
}

class DaoSyncLinkedRepo(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSyncLinkedRepo>(DaoSyncLinkedRepos)

    var project by DaoProject referencedOn DaoSyncLinkedRepos.project
    var repoUrl by DaoSyncLinkedRepos.repoUrl
    var firstLinkedAtMs by DaoSyncLinkedRepos.firstLinkedAtMs
    var lastLinkedAtMs by DaoSyncLinkedRepos.lastLinkedAtMs
}
