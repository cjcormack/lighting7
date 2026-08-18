package uk.me.cormack.lighting7.sync

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import uk.me.cormack.lighting7.models.DaoMachineOverride
import uk.me.cormack.lighting7.models.DaoMachineOverrides
import uk.me.cormack.lighting7.models.DaoProject
import java.util.UUID

/**
 * Typed accessor for `DaoMachineOverrides`. Every read/write must run inside an existing Exposed
 * transaction — this object intentionally doesn't open one so callers can compose overrides into
 * larger units of work.
 *
 * `valueJson` is a canonical JSON-encoded value; the universe-config controller IP path stores a
 * quoted string. Richer per-install field shapes can plug in by encoding through `canonicalEncode`.
 *
 * Setting a value to `null` deletes the row rather than leaving a `"null"` JSON literal — this
 * keeps "no override" indistinguishable from "override cleared" downstream.
 *
 * @see docs/sync-engineering.md §"Machine-local data"
 */
object Overrides {

    /** Universe-config table identifier — matches the JSON folder name in exports. */
    const val UNIVERSE_CONFIGS = "universeConfigs"

    /** Field name for the controller IP override on `universeConfigs`. */
    const val FIELD_ADDRESS = "address"

    /** Field name for the Art-Net transmit interval override on `universeConfigs`. */
    const val FIELD_REFRESH_INTERVAL_MS = "refreshIntervalMs"

    fun getString(projectId: Int, table: String, recordUuid: UUID, field: String): String? =
        get(String.serializer(), projectId, table, recordUuid, field)

    fun setString(projectId: Int, table: String, recordUuid: UUID, field: String, value: String?) =
        set(String.serializer(), projectId, table, recordUuid, field, value)

    fun getInt(projectId: Int, table: String, recordUuid: UUID, field: String): Int? =
        get(Int.serializer(), projectId, table, recordUuid, field)

    fun setInt(projectId: Int, table: String, recordUuid: UUID, field: String, value: Int?) =
        set(Int.serializer(), projectId, table, recordUuid, field, value)

    private fun <T : Any> get(
        serializer: KSerializer<T>,
        projectId: Int,
        table: String,
        recordUuid: UUID,
        field: String,
    ): T? {
        val row = findRow(projectId, table, recordUuid, field) ?: return null
        // An override is advisory machine-local data in a SQLite row a user can hand-edit
        // or that a field's type has since changed under. A malformed one reads as "no
        // override" rather than 500ing whatever route happened to touch it.
        return runCatching { canonicalDecode(serializer, row.valueJson) }.getOrNull()
    }

    private fun <T : Any> set(
        serializer: KSerializer<T>,
        projectId: Int,
        table: String,
        recordUuid: UUID,
        field: String,
        value: T?,
    ) {
        if (value == null) {
            findRow(projectId, table, recordUuid, field)?.delete()
            return
        }
        val encoded = canonicalEncode(serializer, value).trimEnd('\n')
        val existing = findRow(projectId, table, recordUuid, field)
        if (existing != null) {
            existing.valueJson = encoded
        } else {
            val project = DaoProject.findById(projectId)
                ?: error("Project not found: $projectId")
            DaoMachineOverride.new {
                this.project = project
                this.targetTable = table
                this.recordUuid = recordUuid
                this.fieldName = field
                this.valueJson = encoded
            }
        }
    }

    fun listForProject(projectId: Int): List<MachineOverrideEntry> =
        DaoMachineOverride.find { DaoMachineOverrides.project eq projectId }
            .map {
                MachineOverrideEntry(
                    tableName = it.targetTable,
                    recordUuid = it.recordUuid.toString(),
                    fieldName = it.fieldName,
                    valueJson = it.valueJson,
                )
            }

    /** Convenience: read the controller IP override for a universe. */
    fun resolveUniverseAddress(projectId: Int, universeUuid: UUID): String? =
        getString(projectId, UNIVERSE_CONFIGS, universeUuid, FIELD_ADDRESS)

    /** Convenience: write or clear the controller IP override for a universe. */
    fun setUniverseAddress(projectId: Int, universeUuid: UUID, address: String?) {
        setString(projectId, UNIVERSE_CONFIGS, universeUuid, FIELD_ADDRESS, address)
    }

    /**
     * Convenience: read the Art-Net transmit interval override for a universe, or `null`
     * when this machine hasn't pinned one and the controller default applies.
     *
     * Machine-local rather than a synced column because the rate exists to suit the node
     * and fixtures physically present at one venue — it must not follow the show to the
     * next rig.
     */
    fun resolveUniverseRefreshIntervalMs(projectId: Int, universeUuid: UUID): Int? =
        getInt(projectId, UNIVERSE_CONFIGS, universeUuid, FIELD_REFRESH_INTERVAL_MS)

    /** Convenience: write or clear the transmit interval override for a universe. */
    fun setUniverseRefreshIntervalMs(projectId: Int, universeUuid: UUID, intervalMs: Int?) {
        setInt(projectId, UNIVERSE_CONFIGS, universeUuid, FIELD_REFRESH_INTERVAL_MS, intervalMs)
    }

    private fun findRow(
        projectId: Int,
        table: String,
        recordUuid: UUID,
        field: String,
    ): DaoMachineOverride? = DaoMachineOverride.find {
        (DaoMachineOverrides.project eq projectId) and
            (DaoMachineOverrides.targetTable eq table) and
            (DaoMachineOverrides.recordUuid eq recordUuid) and
            (DaoMachineOverrides.fieldName eq field)
    }.firstOrNull()
}

/** Wire shape returned by `GET /api/rest/projects/{id}/machine-overrides`. */
@Serializable
data class MachineOverrideEntry(
    val tableName: String,
    val recordUuid: String,
    val fieldName: String,
    val valueJson: String,
)
