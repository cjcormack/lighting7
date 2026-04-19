package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Transaction
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * One-time migration: convert `cue_ad_hoc_effects` rows with `effect_type` in
 * {`StaticValue`, `StaticSetting`} into `cue_property_assignments` rows, then delete the
 * originals.
 *
 * This is the "effects were the poor person's property assignment" migration. Static effects
 * were how operators asserted a constant slider or setting value inside a cue before
 * [CuePropertyAssignmentDto] existed. With Layer 3 now doing that job natively (per
 * [docs/lighting-composition-model.md](../../../../../../docs/lighting-composition-model.md)),
 * these rows are redundant.
 *
 * ## Mapping
 *
 * | Source (`cue_ad_hoc_effects`) | Target (`cue_property_assignments`) |
 * |-------------------------------|-------------------------------------|
 * | `cue_id`                      | `cue_id`                            |
 * | `target_type`                 | `target_type`                       |
 * | `target_key`                  | `target_key`                        |
 * | `property_name`               | `property_name` (required — rows without it are skipped) |
 * | `parameters["value"]` for `StaticValue` / `parameters["level"]` for `StaticSetting` | `value` |
 * | `sort_order`                  | `sort_order`                        |
 *
 * ## Lossy cases
 *
 * The migration logs a `WARN` and skips rows that can't be converted losslessly:
 * - `property_name` is null — without a target property we can't persist as an assignment.
 * - `parameters` is missing the `value` / `level` key.
 * - `parameters[key]` is not a valid `0..255` UByte.
 *
 * Skipped rows are left in `cue_ad_hoc_effects`. They won't be picked up again on re-run
 * (idempotent check via effect_type filter) and the operator can hand-fix them or drop them
 * via the UI.
 *
 * ## Callers of `StaticValue` / `StaticSetting` *outside* cue authoring
 *
 * The busking view (lighting-react `useBuskingState.ts`) and `FxRegistry` itself still spawn
 * these effects at runtime. This migration targets *persisted* cue rows only — the effect
 * classes and registrations stay in place until busking switches to direct-write semantics.
 */
internal object LegacyStaticEffectMigration {
    private val logger: Logger = LoggerFactory.getLogger("LegacyStaticEffectMigration")
    private val json = Json { ignoreUnknownKeys = true }

    /** Row shape read from `cue_ad_hoc_effects`. */
    internal data class LegacyRow(
        val id: Int,
        val cueId: Int,
        val targetType: String,
        val targetKey: String,
        val effectType: String,
        val propertyName: String?,
        val parameters: Map<String, String>,
        val sortOrder: Int,
    )

    /** Row shape written into `cue_property_assignments`. */
    internal data class ConvertedRow(
        val cueId: Int,
        val targetType: String,
        val targetKey: String,
        val propertyName: String,
        val value: String,
        val sortOrder: Int,
    )

    internal sealed class ConversionResult {
        data class Converted(val row: ConvertedRow) : ConversionResult()
        data class Skipped(val reason: String) : ConversionResult()
    }

    /**
     * Pure conversion function — takes one legacy [row] and returns a conversion result.
     *
     * Exposed for unit testing; the DB migration wraps this with row-reading and
     * INSERT/DELETE plumbing.
     */
    internal fun convertRow(row: LegacyRow): ConversionResult {
        val valueKey = when (row.effectType) {
            "StaticValue" -> "value"
            "StaticSetting" -> "level"
            else -> return ConversionResult.Skipped("unknown effect_type '${row.effectType}'")
        }

        val propertyName = row.propertyName
            ?: return ConversionResult.Skipped("property_name is null")

        val rawValue = row.parameters[valueKey]
            ?: return ConversionResult.Skipped("parameters.$valueKey missing")

        // Validate the value parses as 0..255 so we don't poison Layer 3 at the other end.
        val parsed = rawValue.trim().toIntOrNull()
        if (parsed == null || parsed !in 0..255) {
            return ConversionResult.Skipped("parameters.$valueKey='$rawValue' not a 0..255 UByte")
        }

        return ConversionResult.Converted(ConvertedRow(
            cueId = row.cueId,
            targetType = row.targetType,
            targetKey = row.targetKey,
            propertyName = propertyName,
            value = parsed.toString(),
            sortOrder = row.sortOrder,
        ))
    }

    /**
     * Run the migration on the current [Transaction]. Reads all `StaticValue` / `StaticSetting`
     * ad-hoc effect rows, converts each via [convertRow], INSERTs converted rows, and
     * DELETEs the originals. Idempotent — becomes a no-op once the legacy rows are gone.
     *
     * @return a summary of rows converted vs skipped. Callers log at info/warn.
     */
    fun run(tx: Transaction): Summary {
        val rows = tx.readLegacyRows()
        if (rows.isEmpty()) return Summary(0, 0)

        var converted = 0
        var skipped = 0
        val convertedIds = ArrayList<Int>(rows.size)

        for (row in rows) {
            when (val result = convertRow(row)) {
                is ConversionResult.Converted -> {
                    tx.insertAssignment(result.row)
                    convertedIds.add(row.id)
                    converted++
                }
                is ConversionResult.Skipped -> {
                    logger.warn(
                        "Skipping legacy {} row id={} cue={}: {}",
                        row.effectType, row.id, row.cueId, result.reason,
                    )
                    skipped++
                }
            }
        }

        if (convertedIds.isNotEmpty()) {
            val idList = convertedIds.joinToString(",")
            tx.exec("DELETE FROM cue_ad_hoc_effects WHERE id IN ($idList)")
        }

        return Summary(converted, skipped)
    }

    data class Summary(val converted: Int, val skipped: Int)

    private fun Transaction.readLegacyRows(): List<LegacyRow> {
        val out = ArrayList<LegacyRow>()
        exec(
            """SELECT id, cue_id, target_type, target_key, effect_type, property_name, parameters, sort_order
               FROM cue_ad_hoc_effects
               WHERE effect_type IN ('StaticValue', 'StaticSetting')"""
        ) { rs ->
            while (rs.next()) {
                val paramsJson = rs.getString("parameters") ?: "{}"
                val params = try {
                    json.decodeFromString<Map<String, String>>(paramsJson)
                } catch (_: Exception) {
                    emptyMap()
                }
                out.add(LegacyRow(
                    id = rs.getInt("id"),
                    cueId = rs.getInt("cue_id"),
                    targetType = rs.getString("target_type"),
                    targetKey = rs.getString("target_key"),
                    effectType = rs.getString("effect_type"),
                    propertyName = rs.getString("property_name"),
                    parameters = params,
                    sortOrder = rs.getInt("sort_order"),
                ))
            }
        }
        return out
    }

    private fun Transaction.insertAssignment(row: ConvertedRow) {
        val escapedTargetType = row.targetType.replace("'", "''")
        val escapedTargetKey = row.targetKey.replace("'", "''")
        val escapedPropertyName = row.propertyName.replace("'", "''")
        val escapedValue = row.value.replace("'", "''")
        exec(
            """INSERT INTO cue_property_assignments
               (cue_id, target_type, target_key, property_name, value, sort_order)
               VALUES (${row.cueId}, '$escapedTargetType', '$escapedTargetKey',
                       '$escapedPropertyName', '$escapedValue', ${row.sortOrder})"""
        )
    }
}
