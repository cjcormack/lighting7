package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * OAuth clients that registered themselves with the desk's MCP authorization server
 * (RFC 7591 dynamic client registration) — claude.ai, Claude Code, `mcp-remote`.
 *
 * Machine-local and never synced: a client id is minted by *this* desk's registration endpoint
 * and means nothing to another one. Persisted rather than held in memory because claude.ai keeps
 * the client id it was given, and a desk restart that forgot it would make every connector fail
 * until it was removed and re-added.
 *
 * Every client is a public client (`token_endpoint_auth_method = none`, PKCE required), so there
 * is no secret column. [redirectUris] is newline-separated; each was checked against the
 * allowlist in `mcp/McpAuthService.kt` at registration.
 */
object DaoMcpOAuthClients : IntIdTable("mcp_oauth_clients") {
    val clientId = varchar("client_id", 64).uniqueIndex()
    val clientName = varchar("client_name", 200)
    val redirectUris = text("redirect_uris")
    val createdAt = utcInstant("created_at")
}

class DaoMcpOAuthClient(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoMcpOAuthClient>(DaoMcpOAuthClients)

    var clientId by DaoMcpOAuthClients.clientId
    var clientName by DaoMcpOAuthClients.clientName
    var redirectUris by DaoMcpOAuthClients.redirectUris
    var createdAt by DaoMcpOAuthClients.createdAt
}

/**
 * One approved "Claude may control the lights as <user>" — the MCP server's equivalent of a
 * session row, listed under Connected apps in Profile → Devices.
 *
 * A grant holds the SHA-256 of its current access token and current refresh token, never the
 * raw values (the `user_sessions` rule, for the same reason: a copied SQLite file must not hold a
 * usable credential). Refresh tokens rotate on every use; [previousRefreshHash] is the one just
 * retired, kept so that presenting it again — which only a thief replaying a stolen token would
 * do — revokes the whole grant (OAuth 2.1 §4.3.1 refresh-token reuse detection).
 *
 * Machine-local, like `user_sessions`: a grant is a credential for this desk. The CASCADE on
 * `user_id` is documentation only (SQLite runs with foreign keys off here); `McpAuthService`
 * revokes grants itself when an account's credentials move.
 */
object DaoMcpOAuthGrants : IntIdTable("mcp_oauth_grants") {
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE)
    val clientId = varchar("client_id", 64)
    /** Copied from the client at grant time, so the Connected apps list survives a client prune. */
    val clientName = varchar("client_name", 200)
    val accessTokenHash = varchar("access_token_hash", 64).uniqueIndex()
    val accessExpiresAt = utcInstant("access_expires_at")
    val refreshTokenHash = varchar("refresh_token_hash", 64).uniqueIndex()
    val previousRefreshHash = varchar("previous_refresh_hash", 64).nullable()
    val refreshExpiresAt = utcInstant("refresh_expires_at")
    val createdAt = utcInstant("created_at")
    val lastUsedAt = utcInstant("last_used_at")
    val revokedAt = utcInstant("revoked_at").nullable()
}

class DaoMcpOAuthGrant(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoMcpOAuthGrant>(DaoMcpOAuthGrants)

    var user by DaoUser referencedOn DaoMcpOAuthGrants.user
    var clientId by DaoMcpOAuthGrants.clientId
    var clientName by DaoMcpOAuthGrants.clientName
    var accessTokenHash by DaoMcpOAuthGrants.accessTokenHash
    var accessExpiresAt by DaoMcpOAuthGrants.accessExpiresAt
    var refreshTokenHash by DaoMcpOAuthGrants.refreshTokenHash
    var previousRefreshHash by DaoMcpOAuthGrants.previousRefreshHash
    var refreshExpiresAt by DaoMcpOAuthGrants.refreshExpiresAt
    var createdAt by DaoMcpOAuthGrants.createdAt
    var lastUsedAt by DaoMcpOAuthGrants.lastUsedAt
    var revokedAt by DaoMcpOAuthGrants.revokedAt
}
