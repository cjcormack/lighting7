package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.core.java.javaUUID
import uk.me.cormack.lighting7.fx.AssignmentHealth

/**
 * The `targetType` discriminator marking a [DaoLookRows] / [DaoLookEffects] row as *deferred*:
 * it names no target of its own and takes its targets from the [DaoCueLayers] line referencing
 * the Look.
 *
 * Deliberately not a [TargetRef] arm. `TargetRef.of` must keep rejecting it, because a deferred
 * row reaching a code path that expects a resolvable target is a bug we want loud rather than
 * silently fanned out over every patched fixture. Callers test `targetType == DEFERRED_TARGET_TYPE`
 * *before* constructing a `TargetRef`.
 */
const val DEFERRED_TARGET_TYPE: String = "deferred"

/**
 * One stored Look row: "for this target, this property is this value".
 *
 * **Always bound.** A Look row names a fixture or a group; [DEFERRED_TARGET_TYPE] is rejected at the
 * write boundary. Until session 3 a deferred row was how a Look served as a template — that job
 * moved to [DaoTemplates], which is a better home for it (one family, an intent per row, resolved
 * per head) and which is why the discriminator survives here only for *effects*, where a deferred
 * target still means "fan over the layer's targets".
 *
 * [value] is always a **literal** in the canonical
 * [uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue.serialize] grammar. **Looks do
 * not nest**: a `ref:` value is rejected at the write boundary, so resolution can never recurse.
 *
 * `moveInDark` deliberately has no place here — it is a cue-*crossfade* concept, which is also
 * why [PaletteEntryDto] excluded it. It lives on the cue's local layer only.
 */
@Serializable
data class LookRowDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val elementKey: String? = null,
    val sortOrder: Int = 0,
    /**
     * Validation status of this row against the live patch. Populated server-side on read;
     * ignored on write — the server never trusts client-supplied health. Same contract as
     * [CuePropertyAssignmentDto.health].
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
) {
    /** True when this row takes its targets from the referencing layer rather than naming one. */
    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /** The row's own target, or null when [isDeferred]. */
    val target: TargetRef? get() = if (isDeferred) null else TargetRef.of(targetType, targetKey)
}

/**
 * One stored Look effect. Unifies the two near-identical shapes it replaces:
 * [LookEffectSpec] (target-less, and a JSON blob column purely to avoid DDL) and
 * [CueAdHocEffectDto] (targeted, real columns). This gets real columns *and* the deferred-target
 * convention, so one shape serves both.
 *
 * The `delayMs` / `intervalMs` / `randomWindowMs` triple is **not** here: timing is a property of
 * the layer applying the Look, not of the Look itself, and it lives on [DaoCueLayers].
 */
@Serializable
data class LookEffectDto(
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
    /** Speed master uuid (null → master 1). Uuid, not int id — see [LookEffectSpec.speedMasterUuid]. */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). See [LookEffectSpec.rateSpeedMasterUuid]. */
    val rateSpeedMasterUuid: String? = null,
    val sortOrder: Int = 0,
) {
    /** True when this effect takes its targets from the referencing layer rather than naming one. */
    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /** The effect's own target, or null when [isDeferred]. */
    val target: TargetRef? get() = if (isDeferred) null else TargetRef.of(targetType, targetKey)
}

/**
 * A named, reusable bundle of property values and effects — the one library entity replacing both
 * named palettes and FX presets.
 *
 * A Look is referenced by *identity* from a [DaoCueLayers] line, so editing it moves every cue
 * that layers it with no cue re-fired. That republish is the point of the feature.
 *
 * **No stored attribute-family type.** Which families a Look touches is derived from its rows via
 * [uk.me.cormack.lighting7.fx.maskGroupForProperty], so the library banks by family the way
 * `/palettes/:type` used to and a Look can grow from one family to several with no migration.
 * `PaletteType` was already a typealias of `PropertyMaskGroup` to keep exactly this cheap.
 *
 * The [uuid] is load-bearing beyond sync: it is what a `ref:{uuid}` value names, and the
 * palettes → Looks migration preserves each palette's uuid so existing references keep resolving.
 * See `docs/sync-engineering.md` for why every cross-record reference is a uuid rather than an
 * int PK.
 */
object DaoLooks : IntIdTable("looks") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)
    val notes = text("notes").nullable()
    val sortOrder = integer("sort_order").default(0)

    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Scoped by project and name only. The old named-palette index included `type` (per-type banks
        // were separate namespaces) and the preset index included `fixtureType`; a Look has
        // neither as identity — its families are derived and its editor hint is advisory — so
        // "Warm" is one Look per project, which is the whole point of the merge.
        uniqueIndex(project, name)
    }
}

class DaoLook(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoLook>(DaoLooks)

    var project by DaoProject referencedOn DaoLooks.project
    var name by DaoLooks.name
    var notes by DaoLooks.notes
    var sortOrder by DaoLooks.sortOrder
    var uuid by DaoLooks.uuid
    val rows by DaoLookRow referrersOn DaoLookRows.look
    val effects by DaoLookEffect referrersOn DaoLookEffects.look
}

// ─── Look Rows table ───────────────────────────────────────────────────

object DaoLookRows : IntIdTable("look_rows") {
    val look = reference("look_id", DaoLooks)

    /** A [TargetRef] discriminator, or [DEFERRED_TARGET_TYPE]. */
    val targetType = varchar("target_type", 50)

    /** Empty string when [targetType] is [DEFERRED_TARGET_TYPE]. */
    val targetKey = varchar("target_key", 255)
    val propertyName = varchar("property_name", 255)
    val value = text("value")
    val fadeDurationMs = long("fade_duration_ms").nullable()

    /**
     * Element-local suffix of a multi-element fixture's element key (`"head-0"`, `"element-1"`) —
     * everything after the parent fixture's key and the dot. Null = the whole fixture. Carried
     * over from `DaoFxPresetPropertyAssignments.elementKey` unchanged.
     */
    val elementKey = varchar("element_key", 255).nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoLookRow(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoLookRow>(DaoLookRows)

    var look by DaoLook referencedOn DaoLookRows.look
    var targetType by DaoLookRows.targetType
    var targetKey by DaoLookRows.targetKey
    var propertyName by DaoLookRows.propertyName
    var value by DaoLookRows.value
    var fadeDurationMs by DaoLookRows.fadeDurationMs
    var elementKey by DaoLookRows.elementKey
    var sortOrder by DaoLookRows.sortOrder
    var uuid by DaoLookRows.uuid

    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /**
     * The row's own target, or null when deferred *or* when the stored discriminator names no
     * known arm (a corrupt row) — callers skip rather than guess, as `loadPaletteSnapshot` did.
     */
    val target: TargetRef?
        get() = if (isDeferred) null else TargetRef.ofOrNull(targetType, targetKey)
}

// ─── Look Effects table ────────────────────────────────────────────────

object DaoLookEffects : IntIdTable("look_effects") {
    val look = reference("look_id", DaoLooks)

    /** A [TargetRef] discriminator, or [DEFERRED_TARGET_TYPE]. */
    val targetType = varchar("target_type", 50)

    /** Empty string when [targetType] is [DEFERRED_TARGET_TYPE]. */
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

    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

/**
 * One effect as the FX engine is asked to spawn it — **the shared effect wire shape**, not a
 * Look-only type despite where it lives.
 *
 * Read by `CueStackManager`, `CueTriggerManager`, `FxInstance`,
 * `ProgrammerLayerStack`, `projectLooks`, `programmerInclude`, `AiTools` and the sync DTOs: anything
 * that turns a stored effect row into a running one goes through this.
 *
 * It was called `LookEffectSpec` and lived in `models/fxPresets.kt`, where it *started* as a
 * preset's target-less effect stored as a JSON blob purely to avoid DDL. By the time FX presets were
 * retired in session 4 it had long since become the common currency, so it moved here and was
 * renamed rather than deleted with its old home — deleting it would have broken eight subsystems
 * that have nothing to do with presets. `DaoLookEffects` gave its fields real columns; this is still
 * the shape they are handed on in.
 */
@Serializable
data class LookEffectSpec(
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
    /**
     * Speed master this effect subscribes to, as the master's **uuid** (null → master 1).
     * A uuid rather than an int id for the same reason palette refs are:
     * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites uuid occurrences across the
     * whole export text — including inside this JSON blob column — so the reference survives
     * clone and import where an int PK would dangle.
     */
    val speedMasterUuid: String? = null,
    /**
     * Wall-clock rate master (null → unscaled). Only WALL_CLOCK effects read it; it sits
     * alongside [speedMasterUuid] rather than replacing it, so an effect that changes
     * timing source keeps both assignments. Uuid, not int id — same import rule.
     */
    val rateSpeedMasterUuid: String? = null,
)

class DaoLookEffect(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoLookEffect>(DaoLookEffects)

    var look by DaoLook referencedOn DaoLookEffects.look
    var targetType by DaoLookEffects.targetType
    var targetKey by DaoLookEffects.targetKey
    var effectType by DaoLookEffects.effectType
    var category by DaoLookEffects.category
    var propertyName by DaoLookEffects.propertyName
    var beatDivision by DaoLookEffects.beatDivision
    var blendMode by DaoLookEffects.blendMode
    var distribution by DaoLookEffects.distribution
    var phaseOffset by DaoLookEffects.phaseOffset
    var elementMode by DaoLookEffects.elementMode
    var elementFilter by DaoLookEffects.elementFilter
    var stepTiming by DaoLookEffects.stepTiming
    var parameters by DaoLookEffects.parameters
    var speedMasterUuid by DaoLookEffects.speedMasterUuid
    var rateSpeedMasterUuid by DaoLookEffects.rateSpeedMasterUuid
    var sortOrder by DaoLookEffects.sortOrder
    var uuid by DaoLookEffects.uuid

    val isDeferred: Boolean get() = targetType == DEFERRED_TARGET_TYPE

    /** The effect's own target, or null when deferred or the discriminator is unknown. */
    val target: TargetRef?
        get() = if (isDeferred) null else TargetRef.ofOrNull(targetType, targetKey)
}
