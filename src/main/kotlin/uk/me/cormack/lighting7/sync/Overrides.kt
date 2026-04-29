package uk.me.cormack.lighting7.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import org.jetbrains.exposed.sql.and
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

    fun getString(projectId: Int, table: String, recordUuid: UUID, field: String): String? {
        val row = findRow(projectId, table, recordUuid, field) ?: return null
        return canonicalDecode(String.serializer(), row.valueJson)
    }

    fun setString(projectId: Int, table: String, recordUuid: UUID, field: String, value: String?) {
        if (value == null) {
            findRow(projectId, table, recordUuid, field)?.delete()
            return
        }
        val encoded = canonicalEncode(String.serializer(), value).trimEnd('\n')
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
