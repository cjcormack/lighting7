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
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

/**
 * Layer 3 property assignment — operator-authored "this cue asserts property X = value" record.
 * See `docs/lighting-composition-model.md` §"Layer 3" for semantics (specificity, composition,
 * crossfade) and `uk.me.cormack.lighting7.fx.Layer3Resolver` for the canonical value parser.
 */
@Serializable
data class CuePropertyAssignmentDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
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
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
)

// ─── Cue types ──────────────────────────────────────────────────────────

enum class CueType { STANDARD, MARKER }

// ─── Cues table ─────────────────────────────────────────────────────────

object DaoCues : IntIdTable("cues") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val palette = json<List<String>>("palette", Json)
    val updateGlobalPalette = bool("update_global_palette").default(false)
    val cueStack = reference("cue_stack_id", DaoCueStacks).nullable()
    val sortOrder = integer("sort_order").default(0)
    val autoAdvance = bool("auto_advance").default(false)
    val autoAdvanceDelayMs = long("auto_advance_delay_ms").nullable()
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val fadeCurve = varchar("fade_curve", 50).default("LINEAR")
    val cueNumber = varchar("cue_number", 20).nullable()
    val notes = text("notes").nullable()
    val cueType = varchar("cue_type", 20).default("STANDARD")
    /**
     * When true, applying this cue removes ad-hoc effects owned by *other* cues that target
     * properties covered by this cue's Layer 3 assignments. Mirrors grandMA3's `Stomp`. Phase 0
     * lands the column and resolver hook; Phase 1 wires real Layer 3 data in as the overlap
     * source. See `docs/lighting-composition-model.md` §"Stomp".
     */
    val stomp = bool("stomp").default(false)

    init {
        uniqueIndex(project, name)
    }
}

class DaoCue(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCue>(DaoCues)

    var name by DaoCues.name
    var project by DaoProject referencedOn DaoCues.project
    var palette by DaoCues.palette
    var updateGlobalPalette by DaoCues.updateGlobalPalette
    var cueStack by DaoCueStack optionalReferencedOn DaoCues.cueStack
    var sortOrder by DaoCues.sortOrder
    var autoAdvance by DaoCues.autoAdvance
    var autoAdvanceDelayMs by DaoCues.autoAdvanceDelayMs
    var fadeDurationMs by DaoCues.fadeDurationMs
    var fadeCurve by DaoCues.fadeCurve
    var cueNumber by DaoCues.cueNumber
    var notes by DaoCues.notes
    var cueType by DaoCues.cueType
    var stomp by DaoCues.stomp
    val presetApplications by DaoCuePresetApplication referrersOn DaoCuePresetApplications.cue
    val adHocEffects by DaoCueAdHocEffect referrersOn DaoCueAdHocEffects.cue
    val propertyAssignments by DaoCuePropertyAssignment referrersOn DaoCuePropertyAssignments.cue
    val triggers by DaoCueTrigger referrersOn DaoCueTriggers.cue
}

// ─── Cue Preset Applications table ─────────────────────────────────────

object DaoCuePresetApplications : IntIdTable("cue_preset_applications") {
    val cue = reference("cue_id", DaoCues)
    val preset = reference("preset_id", DaoFxPresets)
    val targets = json<List<CueTargetDto>>("targets", Json)
    val delayMs = long("delay_ms").nullable()
    val intervalMs = long("interval_ms").nullable()
    val randomWindowMs = long("random_window_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
}

class DaoCuePresetApplication(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCuePresetApplication>(DaoCuePresetApplications)

    var cue by DaoCue referencedOn DaoCuePresetApplications.cue
    var preset by DaoFxPreset referencedOn DaoCuePresetApplications.preset
    var targets by DaoCuePresetApplications.targets
    var delayMs by DaoCuePresetApplications.delayMs
    var intervalMs by DaoCuePresetApplications.intervalMs
    var randomWindowMs by DaoCuePresetApplications.randomWindowMs
    var sortOrder by DaoCuePresetApplications.sortOrder
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
    val delayMs = long("delay_ms").nullable()
    val intervalMs = long("interval_ms").nullable()
    val randomWindowMs = long("random_window_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
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
    var delayMs by DaoCueAdHocEffects.delayMs
    var intervalMs by DaoCueAdHocEffects.intervalMs
    var randomWindowMs by DaoCueAdHocEffects.randomWindowMs
    var sortOrder by DaoCueAdHocEffects.sortOrder
}

// ─── Cue Property Assignments table ────────────────────────────────────

object DaoCuePropertyAssignments : IntIdTable("cue_property_assignments") {
    val cue = reference("cue_id", DaoCues)
    val targetType = varchar("target_type", 50)
    val targetKey = varchar("target_key", 255)
    val propertyName = varchar("property_name", 255)
    val value = text("value")
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
}

class DaoCuePropertyAssignment(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCuePropertyAssignment>(DaoCuePropertyAssignments)

    var cue by DaoCue referencedOn DaoCuePropertyAssignments.cue
    var targetType by DaoCuePropertyAssignments.targetType
    var targetKey by DaoCuePropertyAssignments.targetKey
    var propertyName by DaoCuePropertyAssignments.propertyName
    var value by DaoCuePropertyAssignments.value
    var fadeDurationMs by DaoCuePropertyAssignments.fadeDurationMs
    var sortOrder by DaoCuePropertyAssignments.sortOrder
}
