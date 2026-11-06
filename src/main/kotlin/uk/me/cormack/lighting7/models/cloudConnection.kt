package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/** Where this desk is with Lighting7 Cloud. Stored by name. */
enum class CloudConnectionState {
    /** An admin started an adoption and the desk is polling for its approval. */
    ADOPTING,

    /** The cloud approved it: the desk belongs to [DaoCloudConnections.orgName]. */
    ADOPTED,
}

/**
 * This desk's adoption by Lighting7 Cloud (cloud-p0 plan D7): one row while it is adopting or
 * adopted, none otherwise. The install key's private half is never here: it is the
 * `CredentialStore` blob `cloud:install-key:<installs.uuid>` (`cloud/InstallKeyStore.kt`).
 * Machine-local, never synced, and written only on a transition (adopt, approve, unadopt, revoke),
 * never per poll (`docs/cloud-engineering.md` §"Show safety").
 *
 * What the cloud sends is stored as `text`, since the protocol bounds none of it. `adopted_by` is
 * SET_NULL in intent only: SQLite runs with `PRAGMA foreign_keys` OFF here, so
 * `AuthService.deleteUser` clears it by hand.
 */
object DaoCloudConnections : IntIdTable("cloud_connection") {
    val state = enumerationByName<CloudConnectionState>("state", 16)
    /** The `cloud.baseUrl` the adoption was made against. */
    val baseUrl = text("base_url")
    /** The cloud's id for this install, once approved. */
    val installId = text("install_id").nullable()
    val orgId = text("org_id").nullable()
    val orgName = text("org_name").nullable()
    /** The install key's public half as the protocol spells it (`InstallKeys.encodePublicKey`). */
    val publicKey = varchar("public_key", 100)
    val fingerprint = varchar("fingerprint", 100)
    val adoptedAt = utcInstant("adopted_at").nullable()
    /** The admin who started the adoption, and whose cloud account it links. */
    val adoptedBy = optReference("adopted_by_user_id", DaoUsers, onDelete = ReferenceOption.SET_NULL)

    // The pending adoption, while ADOPTING: enough to show its code again and resume polling after a restart.
    val adoptionId = text("adoption_id").nullable()
    val userCode = text("user_code").nullable()
    val verificationUri = text("verification_uri").nullable()
    val verificationUriComplete = text("verification_uri_complete").nullable()
    val pollIntervalS = integer("poll_interval_s").nullable()
    val adoptionExpiresAt = utcInstant("adoption_expires_at").nullable()

    // What may come through the cloud (cloud-p1 plan D6): off at adoption, changed only by an admin on
    // the desk's own listener, never by a message.
    /** Remote sessions through the sidecar. */
    val remoteAccess = bool("remote_access").default(false)
    /** MCP calls relayed over the control channel. */
    val mcpAccess = bool("mcp_access").default(false)
    /** Each new remote session waits for an admin at the desk to allow it (D13). */
    val approveSessions = bool("approve_sessions").default(false)
}
