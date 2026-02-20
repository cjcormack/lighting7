package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json

// ─── DTOs (used for API serialization) ──────────────────────────────────

@Serializable
data class CueTargetDto(
    val type: String,
    val key: String,
)

@Serializable
data class CuePresetApplicationDto(
    val presetId: Int,
    val targets: List<CueTargetDto>,
)

@Serializable
data class CueAdHocEffectDto(
    val targetType: String,
    val targetKey: String,
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

// ─── Cues table ─────────────────────────────────────────────────────────

object DaoCues : IntIdTable("cues") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val palette = json<List<String>>("palette", Json)

    init {
        uniqueIndex(project, name)
    }
}

class DaoCue(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCue>(DaoCues)

    var name by DaoCues.name
    var project by DaoProject referencedOn DaoCues.project
    var palette by DaoCues.palette
    val presetApplications by DaoCuePresetApplication referrersOn DaoCuePresetApplications.cue
    val adHocEffects by DaoCueAdHocEffect referrersOn DaoCueAdHocEffects.cue
}

// ─── Cue Preset Applications table ─────────────────────────────────────

object DaoCuePresetApplications : IntIdTable("cue_preset_applications") {
    val cue = reference("cue_id", DaoCues)
    val preset = reference("preset_id", DaoFxPresets)
    val targets = json<List<CueTargetDto>>("targets", Json)
}

class DaoCuePresetApplication(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCuePresetApplication>(DaoCuePresetApplications)

    var cue by DaoCue referencedOn DaoCuePresetApplications.cue
    var preset by DaoFxPreset referencedOn DaoCuePresetApplications.preset
    var targets by DaoCuePresetApplications.targets
}

// ─── Cue Ad-Hoc Effects table ──────────────────────────────────────────

object DaoCueAdHocEffects : IntIdTable("cue_ad_hoc_effects") {
    val cue = reference("cue_id", DaoCues)
    val targetType = varchar("target_type", 50)
    val targetKey = varchar("target_key", 255)
    val effectType = varchar("effect_type", 255)
    val category = varchar("category", 50)
    val propertyName = varchar("property_name", 255).nullable()
    val beatDivision = double("beat_division")
    val blendMode = varchar("blend_mode", 50)
    val distribution = varchar("distribution", 50)
    val phaseOffset = double("phase_offset").default(0.0)
    val elementMode = varchar("element_mode", 50).nullable()
    val elementFilter = varchar("element_filter", 50).nullable()
    val stepTiming = bool("step_timing").nullable()
    val parameters = json<Map<String, String>>("parameters", Json)
}

class DaoCueAdHocEffect(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueAdHocEffect>(DaoCueAdHocEffects)

    var cue by DaoCue referencedOn DaoCueAdHocEffects.cue
    var targetType by DaoCueAdHocEffects.targetType
    var targetKey by DaoCueAdHocEffects.targetKey
    var effectType by DaoCueAdHocEffects.effectType
    var category by DaoCueAdHocEffects.category
    var propertyName by DaoCueAdHocEffects.propertyName
    var beatDivision by DaoCueAdHocEffects.beatDivision
    var blendMode by DaoCueAdHocEffects.blendMode
    var distribution by DaoCueAdHocEffects.distribution
    var phaseOffset by DaoCueAdHocEffects.phaseOffset
    var elementMode by DaoCueAdHocEffects.elementMode
    var elementFilter by DaoCueAdHocEffects.elementFilter
    var stepTiming by DaoCueAdHocEffects.stepTiming
    var parameters by DaoCueAdHocEffects.parameters
}
