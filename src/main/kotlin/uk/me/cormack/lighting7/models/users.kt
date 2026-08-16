package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID

/** What a desk account is allowed to do. Stored by name via `enumerationByName`. */
enum class UserRole {
    /** User management, reset tokens, and install-level settings — plus everything OPERATOR can do. */
    ADMIN,

    /** All lighting control and all project content; no account or install administration. */
    OPERATOR,
}

/**
 * Desk-local user accounts (multi-user-auth plan). Users belong to the **machine**,
 * not to any project: they are never exported, imported, cloned, or synced to the
 * cloud repo. The `uuid` is the stable identifier for any future cross-referencing
 * (e.g. per-user attribution); the int id is for FK plumbing only. `username` is
 * stored lowercase and unique — the unique index doubles as the race guard for
 * first-admin setup. Machine-local; never synced.
 */
object DaoUsers : IntIdTable("users") {
    val uuid = javaUUID("uuid").autoGenerate()
    val username = varchar("username", 64).uniqueIndex()
    val displayName = varchar("display_name", 100)
    val role = enumerationByName<UserRole>("role", 16)

    /** BCrypt modular crypt string — always exactly 60 chars for the `$2a$` family. */
    val passwordHash = varchar("password_hash", 60)
    val disabled = bool("disabled").default(false)
    val createdAtMs = long("created_at_ms")
    val passwordChangedAtMs = long("password_changed_at_ms")
    val lastLoginAtMs = long("last_login_at_ms").nullable()
}

class DaoUser(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoUser>(DaoUsers)

    var uuid by DaoUsers.uuid
    var username by DaoUsers.username
    var displayName by DaoUsers.displayName
    var role by DaoUsers.role
    var passwordHash by DaoUsers.passwordHash
    var disabled by DaoUsers.disabled
    var createdAtMs by DaoUsers.createdAtMs
    var passwordChangedAtMs by DaoUsers.passwordChangedAtMs
    var lastLoginAtMs by DaoUsers.lastLoginAtMs
}
