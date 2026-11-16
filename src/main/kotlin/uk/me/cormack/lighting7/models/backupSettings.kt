package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * This desk's off-site backups (cloud-p3 plan D6), at most one row: the switch, the local time a
 * night's backup runs, and how the last one went. Machine-local and never synced: whether this machine
 * sends a copy of itself to the cloud is a fact about the machine. No row means off, at 04:00.
 */
object DaoBackupSettingsTable : IntIdTable("backup_settings") {
    val enabled = bool("enabled").default(false)

    /** Local time, `HH:mm`. */
    val dailyAt = varchar("daily_at", 5).default("04:00")

    val lastOkAt = utcInstant("last_ok_at").nullable()
    val lastFailedAt = utcInstant("last_failed_at").nullable()

    /** The last failure's code, and its words. */
    val lastCode = varchar("last_code", 64).nullable()
    val lastMessage = varchar("last_message", 500).nullable()

    /** The last backup uploaded: its id, kind and encrypted size. */
    val lastBackupId = varchar("last_backup_id", 26).nullable()
    val lastKind = varchar("last_kind", 16).nullable()
    val lastSizeBytes = long("last_size_bytes").nullable()

    val updatedAt = utcInstant("updated_at")
}
