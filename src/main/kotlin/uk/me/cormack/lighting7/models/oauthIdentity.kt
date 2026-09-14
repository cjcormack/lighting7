package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq

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
    val accessExpiresAt = utcInstant("access_expires_at").nullable()
    val refreshExpiresAt = utcInstant("refresh_expires_at").nullable()
    val connectedAt = utcInstant("connected_at")

    /**
     * When GitHub last rejected our refresh token outright, i.e. the moment this identity
     * became unusable and only a re-connect can fix it. Null means "no known problem" —
     * cleared on a successful refresh or re-connect.
     *
     * Mirrored from the authoritative copy in the credential blob
     * ([uk.me.cormack.lighting7.sync.auth.oauth.StoredOAuthIdentity]) so the UI can say
     * "reconnect required" without touching secret material.
     */
    val reauthRequiredAt = utcInstant("reauth_required_at").nullable()

    /** GitHub's reason for the rejection, shown verbatim to the user. Null iff [reauthRequiredAt] is. */
    val reauthReason = varchar("reauth_reason", 300).nullable()

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
    var accessExpiresAt by DaoOAuthIdentities.accessExpiresAt
    var refreshExpiresAt by DaoOAuthIdentities.refreshExpiresAt
    var connectedAt by DaoOAuthIdentities.connectedAt
    var reauthRequiredAt by DaoOAuthIdentities.reauthRequiredAt
    var reauthReason by DaoOAuthIdentities.reauthReason
}
