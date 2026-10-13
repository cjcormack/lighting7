package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass

/**
 * This install's telemetry choice (Sentry): at most one row. Machine-local and never synced — it
 * is a consent decision about this computer, not part of a show. No row means "never chosen", and
 * the build's default applies (`telemetry/TelemetrySettings.kt`). See
 * `docs/observability-engineering.md`.
 */
object DaoTelemetrySettingsTable : IntIdTable("telemetry_settings") {
    val enabled = bool("enabled")
    val updatedAt = utcInstant("updated_at")
}

class DaoTelemetrySettings(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoTelemetrySettings>(DaoTelemetrySettingsTable)

    var enabled by DaoTelemetrySettingsTable.enabled
    var updatedAt by DaoTelemetrySettingsTable.updatedAt
}
