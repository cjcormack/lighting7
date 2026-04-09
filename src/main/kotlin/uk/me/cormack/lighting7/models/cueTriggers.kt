package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

// ─── Enums ─────────────────────────────────────────────────────────────

/**
 * When the trigger fires relative to the cue lifecycle.
 */
enum class TriggerType {
    /** Fires immediately when the cue is activated */
    ACTIVATION,
    /** Fires when the cue is deactivated (cleanup) */
    DEACTIVATION,
    /** Fires after a delay from activation */
    DELAYED,
    /** Fires repeatedly at an interval (with optional randomisation) */
    RECURRING,
}

// ─── DTO ────────────────────────────────────────────────────────────────

/**
 * Script trigger DTO — triggers are now exclusively for running scripts
 * at cue lifecycle events. Preset application timing has moved to
 * CuePresetApplicationDto and CueAdHocEffectDto.
 */
@Serializable
data class CueTriggerDto(
    val triggerType: String,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val scriptId: Int,
    val sortOrder: Int = 0,
)

/**
 * Response DTO with resolved script name for display.
 */
@Serializable
data class CueTriggerDetailDto(
    val triggerType: String,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val scriptId: Int,
    val scriptName: String? = null,
    val sortOrder: Int = 0,
)

// ─── Cue Triggers table (script hooks only) ──────────────────────────

object DaoCueTriggers : IntIdTable("cue_triggers") {
    val cue = reference("cue_id", DaoCues)
    val triggerType = enumerationByName<TriggerType>("trigger_type", 20)
    val delayMs = long("delay_ms").nullable()
    val intervalMs = long("interval_ms").nullable()
    val randomWindowMs = long("random_window_ms").nullable()
    val script = reference("script_id", DaoScripts)
    val sortOrder = integer("sort_order").default(0)
}

class DaoCueTrigger(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueTrigger>(DaoCueTriggers)

    var cue by DaoCue referencedOn DaoCueTriggers.cue
    var triggerType by DaoCueTriggers.triggerType
    var delayMs by DaoCueTriggers.delayMs
    var intervalMs by DaoCueTriggers.intervalMs
    var randomWindowMs by DaoCueTriggers.randomWindowMs
    var script by DaoScript referencedOn DaoCueTriggers.script
    var sortOrder by DaoCueTriggers.sortOrder
}
