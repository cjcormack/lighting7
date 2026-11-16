package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.IntIdTable

/**
 * This desk's off-site backups (cloud-p3 plan D6), at most one row: the switch, when it backs up — each
 * night at a local time, when *Quit desk* stops it, or both — and how the last one went. Machine-local and never synced: whether this machine
 * sends a copy of itself to the cloud is a fact about the machine. No row means off, at 04:00.
 */
object DaoBackupSettingsTable : IntIdTable("backup_settings") {
    val enabled = bool("enabled").default(false)

    /** Local time, `HH:mm`. */
    val dailyAt = varchar("daily_at", 5).default("04:00")

    /** Back up each night at [dailyAt], while [enabled]. */
    val nightly = bool("nightly").default(true)

    /** Back up when *Quit desk* stops the desk, while [enabled]. */
    val onQuit = bool("on_quit").default(false)

    /** A quit asked for a backup and none has been made since; the next start makes one. */
    val quitPending = bool("quit_pending").default(false)

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
