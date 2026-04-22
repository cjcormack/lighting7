package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json
import uk.me.cormack.lighting7.fx.AssignmentHealth

@Serializable
data class FxPresetEffectDto(
    val effectType: String,
    val category: String,
    val propertyName: String? = null,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val stepTiming: Boolean? = null,
    val parameters: Map<String, String> = emptyMap(),
)

/**
 * Preset-local property assignment — operator-authored "this preset asserts property X = value"
 * record. Assignments are fanned out over each target at apply time; see
 * [uk.me.cormack.lighting7.routes.buildLayer3AssignmentsForPreset]. Value strings share the cue
 * parser ([uk.me.cormack.lighting7.fx.Layer3Resolver.parseAssignmentValue]).
 */
@Serializable
data class FxPresetPropertyAssignmentDto(
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
    /**
     * Validation status against the preset's declared `fixtureType`. Populated server-side
     * on read (see cue-authoring Phase 6); ignored on write. Default [AssignmentHealth.Ok]
     * keeps the apply-path / pseudo-cue builders working unchanged.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
)

object DaoFxPresets : IntIdTable("fx_presets") {
    val name = varchar("name", 255)
    val description = varchar("description", 1000).nullable()
    val project = reference("project_id", DaoProjects)
    val fixtureType = varchar("fixture_type", 255).nullable()
    val effects = json<List<FxPresetEffectDto>>("effects", Json)
    val palette = json<List<String>>("palette", Json).default(emptyList())

    init {
        uniqueIndex(project, fixtureType, name)
    }
}

class DaoFxPreset(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxPreset>(DaoFxPresets)

    var name by DaoFxPresets.name
    var description by DaoFxPresets.description
    var project by DaoProject referencedOn DaoFxPresets.project
    var fixtureType by DaoFxPresets.fixtureType
    var effects by DaoFxPresets.effects
    var palette by DaoFxPresets.palette
    val propertyAssignments by DaoFxPresetPropertyAssignment referrersOn DaoFxPresetPropertyAssignments.preset
}

// ─── Preset Property Assignments table ─────────────────────────────────

object DaoFxPresetPropertyAssignments : IntIdTable("fx_preset_property_assignments") {
    val preset = reference("preset_id", DaoFxPresets)
    val propertyName = varchar("property_name", 255)
    val value = text("value")
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
}

class DaoFxPresetPropertyAssignment(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxPresetPropertyAssignment>(DaoFxPresetPropertyAssignments)

    var preset by DaoFxPreset referencedOn DaoFxPresetPropertyAssignments.preset
    var propertyName by DaoFxPresetPropertyAssignments.propertyName
    var value by DaoFxPresetPropertyAssignments.value
    var fadeDurationMs by DaoFxPresetPropertyAssignments.fadeDurationMs
    var sortOrder by DaoFxPresetPropertyAssignments.sortOrder
}
