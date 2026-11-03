package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/** How a desk account came to be linked to a cloud account. Stored by name. */
enum class CloudLinkVia {
    /** The admin who adopted the desk, linked to the cloud account that approved it. */
    ADOPTION,
}

/**
 * Which cloud account each desk account is (cloud-p0 plan D7): at most one link per desk user.
 * Adoption writes one, for the admin who started it; nothing reads links for authorisation until
 * P1. Unadopting deletes them all. Machine-local, never synced.
 *
 * The CASCADE on `user_id` is not enforced (SQLite runs with `PRAGMA foreign_keys` OFF), so
 * `AuthService.deleteUser` deletes a user's link by hand.
 */
object DaoCloudIdentityLinks : IntIdTable("cloud_identity_links") {
    val user = reference("user_id", DaoUsers, onDelete = ReferenceOption.CASCADE).uniqueIndex()
    val cloudAccountId = text("cloud_account_id")
    val email = text("email")
    val linkedAt = utcInstant("linked_at")
    val linkedVia = enumerationByName<CloudLinkVia>("linked_via", 16)
}
