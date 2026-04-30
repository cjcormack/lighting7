package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.and

/**
 * Non-secret mirror of the install-wide GitHub OAuth identity. Tokens themselves live
 * in [uk.me.cormack.lighting7.sync.auth.CredentialStore] under
 * [uk.me.cormack.lighting7.sync.auth.CredentialStore.OAUTH_GITHUB_DEFAULT_KEY]; this
 * table just carries the metadata the UI needs to render "Connected as @ccormack" and
 * "expires in 7 hours" without round-tripping the secret.
 *
 * Machine-local — never synced. The `provider`/`scope` columns are placeholders for a
 * future per-project identity story; for now there's exactly one row with
 * `provider="github"`, `scope="default"`.
 */
object DaoOAuthIdentities : IntIdTable("oauth_identities") {
    val provider = varchar("provider", 32)
    val scope = varchar("scope", 64).default(DEFAULT_SCOPE)
    val githubLogin = varchar("github_login", 100)
    val githubUserId = long("github_user_id")
    val accessExpiresAtMs = long("access_expires_at_ms").nullable()
    val refreshExpiresAtMs = long("refresh_expires_at_ms").nullable()
    val connectedAtMs = long("connected_at_ms")

    init {
        uniqueIndex(provider, scope)
    }

    const val DEFAULT_SCOPE = "default"
    const val PROVIDER_GITHUB = "github"
}

class DaoOAuthIdentity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoOAuthIdentity>(DaoOAuthIdentities) {
        /** The single install-wide GitHub identity row, if connected. */
        fun findGithubDefault(): DaoOAuthIdentity? = find {
            (DaoOAuthIdentities.provider eq DaoOAuthIdentities.PROVIDER_GITHUB) and
                (DaoOAuthIdentities.scope eq DaoOAuthIdentities.DEFAULT_SCOPE)
        }.firstOrNull()
    }

    var provider by DaoOAuthIdentities.provider
    var scope by DaoOAuthIdentities.scope
    var githubLogin by DaoOAuthIdentities.githubLogin
    var githubUserId by DaoOAuthIdentities.githubUserId
    var accessExpiresAtMs by DaoOAuthIdentities.accessExpiresAtMs
    var refreshExpiresAtMs by DaoOAuthIdentities.refreshExpiresAtMs
    var connectedAtMs by DaoOAuthIdentities.connectedAtMs
}
