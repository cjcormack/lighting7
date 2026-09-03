package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.slf4j.LoggerFactory

// ─── DTOs (used for API serialization) ──────────────────────────────────

@Serializable
data class CueTargetDto(
    val type: String,
    val key: String,
) {
    constructor(target: TargetRef) : this(target.discriminator, target.key)

    val target: TargetRef get() = TargetRef.of(type, key)
}

/**
 * Same fixtures/groups, regardless of order — what "the same target set" means everywhere a layer
 * (programmer or cue) is matched by its targets: [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.toggle],
 * and the Record REMOVE/MERGE layer matching in `programmerRecord.kt`.
 *
 * Sorts rather than converting to a `Set`: a plain `toSet()` comparison would call `[A, A, B]` and
 * `[A, B, B]` equal, since both collapse to `{A, B}`. Targets aren't expected to repeat, but a
 * sorted-list comparison costs nothing extra and stays correct if one ever does.
 */
fun sameTargets(a: List<CueTargetDto>, b: List<CueTargetDto>): Boolean {
    val key = compareBy<CueTargetDto>({ it.type }, { it.key })
    return a.sortedWith(key) == b.sortedWith(key)
}


/**
 * One line of a cue's ordered Look composition. Wire shape of [DaoCueLayers]; see its KDoc for
 * what [targets] and [propertyMask] mean.
 *
 * [lookId] is the int PK for API traffic — the same choice `CuePresetApplicationDto.presetId` made
 * before it retired with `cue_preset_applications` in session 4, and safe for the same reason: it is
 * a real foreign key the importer rewrites. Sync carries the Look's uuid instead, because a uuid is
 * the only form that survives *inside* an opaque value.
 */
@Serializable
data class CueLayerDto(
    /**
     * What this layer applies: **exactly one** of [lookId] / [templateId] on write.
     *
     * Two ids rather than a `(kind, id)` pair because that is what a client has in hand — it picked
     * a row out of one library or the other — and because a request naming both is then a shape
     * error the route can refuse rather than a discriminator it has to trust.
     */
    val lookId: Int? = null,
    val templateId: Int? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    /** Comma-separated `PropertyMaskGroup` names; null = every property. */
    val propertyMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    val speedMasterUuid: String? = null,
    val rateSpeedMasterUuid: String? = null,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    /**
     * What the layer applies, resolved server-side on read so a cue card can label it without a
     * second fetch: kind, id, uuid and name. Ignored on write — mirroring how `health` is read-only
     * on the assignment DTOs, and the reason [lookId] / [templateId] are still the write fields.
     */
    val source: LayerSourceDto? = null,
    /**
     * The `DaoCueLayer` row id, on read only — the same read-only convention as [lookName].
     *
     * Needed because a layer is otherwise **unaddressable from a client**: `lookId` is not unique
     * (one cue may layer the same Look twice at two delays) and array position is not identity when
     * `sortOrder` is authoritative. `POST /{projectId}/cues/{cueId}/flatten` takes an optional
     * `layerId`, and without this its single-layer mode had no way to be called — found by driving
     * the route against a desk rather than by its unit tests, which read the id from the database.
     *
     * Ignored on write, and absent from `buildCueInput`'s field-by-field rebuild for the same reason
     * [source] is: a PATCH that echoed an id back would invite the server to trust it as identity.
     */
    val id: Int? = null,
)

/**
 * Layer 4 property assignment — operator-authored "this cue asserts property X = value" record.
 * See `docs/lighting-composition-model.md` §"Layer 4" for semantics (specificity, composition,
 * crossfade) and `uk.me.cormack.lighting7.fx.CueAssignmentResolver` for the canonical value parser.
 */
@Serializable
data class CuePropertyAssignmentDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
    /**
     * Move-in-dark: only meaningful when [propertyName] is `"position"`. When true and the
     * outgoing cue ends with intensity 0 on the same fixture, the resolver pre-applies this
     * position across the whole crossfade rather than linearly blending pan/tilt — the head
     * moves while dark and is already aimed when the incoming dimmer comes up. Ignored for
     * all other property categories. See `docs/lighting-composition-model.md` §"Crossfade
     * behaviour".
     */
    val moveInDark: Boolean = false,
    /**
     * Validation status of this row against the live patch. Populated server-side on read
     * (see Phase 6 dead-reference diagnostics); ignored on write — the server never trusts
     * client-supplied health. Defaults to [AssignmentHealth.Ok] so snapshots and apply-path
     * code paths that don't resolve health stay serialisable unchanged.
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
) {
    val target: TargetRef get() = TargetRef.of(targetType, targetKey)
}

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
    /** Speed master uuid (null → master 1). Uuid, not int id — see [LookEffectSpec.speedMasterUuid]. */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). See [LookEffectSpec.rateSpeedMasterUuid]. */
    val rateSpeedMasterUuid: String? = null,
) {
    val target: TargetRef get() = TargetRef.of(targetType, targetKey)
}

// ─── Cue types ──────────────────────────────────────────────────────────

enum class CueType { STANDARD, MARKER }

// ─── Cues table ─────────────────────────────────────────────────────────

object DaoCues : IntIdTable("cues") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val cueStack = reference("cue_stack_id", DaoCueStacks)
    val sortOrder = integer("sort_order").default(0)
    val autoAdvance = bool("auto_advance").default(false)
    val autoAdvanceDelayMs = long("auto_advance_delay_ms").nullable()
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val fadeCurve = varchar("fade_curve", 50).default("LINEAR")
    val cueNumber = varchar("cue_number", 20).nullable()
    /**
     * True when [cueNumber] was derived from the cue's position rather than typed by the
     * operator. Auto numbers are rewritten whenever the stack's membership or order changes
     * (see `renumberAutoCues`); explicit ones are never touched. Clearing an explicit number
     * hands the cue back to the auto scheme.
     */
    val cueNumberAuto = bool("cue_number_auto").default(false)
    val notes = text("notes").nullable()
    val cueType = varchar("cue_type", 20).default("STANDARD")
    /**
     * When true, applying this cue removes ad-hoc effects owned by *other* cues that target
     * properties covered by this cue's Layer 4 assignments. Mirrors grandMA3's `Stomp`.
     * See `docs/lighting-composition-model.md` §"Stomp".
     */
    val stomp = bool("stomp").default(false)
    /**
     * True when this cue has a pad of its own on the busk view (`/projects/:id/busk`).
     *
     * A flag on the cue rather than a pin-list table: a separate table would buy ordering and
     * cross-project pins nobody has asked for, and would need its own export/import handling,
     * where a column rides along with the cue for free. See
     * `docs/plans/completed/busking-view-plan.md` D10.
     */
    val pinnedToBusk = bool("pinned_to_busk").default(false)
    val uuid = javaUUID("uuid").autoGenerate()

    // Cue names are deliberately *not* unique. The old uniqueIndex(project, name) predates cue
    // stacks; with a project owning many stacks, two stacks may legitimately both hold a
    // "Blackout". Cues are identified by id (and by uuid across sync). The legacy index is
    // dropped in State.initDatabase. Cue *numbers* remain unique per stack via
    // uq_cue_number_per_stack.
}

class DaoCue(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCue>(DaoCues)

    var name by DaoCues.name
    var project by DaoProject referencedOn DaoCues.project
    var cueStack by DaoCueStack referencedOn DaoCues.cueStack
    var sortOrder by DaoCues.sortOrder
    var autoAdvance by DaoCues.autoAdvance
    var autoAdvanceDelayMs by DaoCues.autoAdvanceDelayMs
    var fadeDurationMs by DaoCues.fadeDurationMs
    var fadeCurve by DaoCues.fadeCurve
    var cueNumber by DaoCues.cueNumber
    var cueNumberAuto by DaoCues.cueNumberAuto
    var notes by DaoCues.notes
    var cueType by DaoCues.cueType
    var stomp by DaoCues.stomp
    var pinnedToBusk by DaoCues.pinnedToBusk
    var uuid by DaoCues.uuid
    val layers by DaoCueLayer referrersOn DaoCueLayers.cue
    val adHocEffects by DaoCueAdHocEffect referrersOn DaoCueAdHocEffects.cue
    val propertyAssignments by DaoCuePropertyAssignment referrersOn DaoCuePropertyAssignments.cue
    val triggers by DaoCueTrigger referrersOn DaoCueTriggers.cue
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
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoCueAdHocEffect(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueAdHocEffect>(DaoCueAdHocEffects)

    var cue by DaoCue referencedOn DaoCueAdHocEffects.cue
    var targetType by DaoCueAdHocEffects.targetType
    var targetKey by DaoCueAdHocEffects.targetKey

    var target: TargetRef
        get() = TargetRef.of(targetType, targetKey)
        set(value) {
            targetType = value.discriminator
            targetKey = value.key
        }

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
    var speedMasterUuid by DaoCueAdHocEffects.speedMasterUuid
    var rateSpeedMasterUuid by DaoCueAdHocEffects.rateSpeedMasterUuid
    var uuid by DaoCueAdHocEffects.uuid
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
    val moveInDark = bool("move_in_dark").default(false)
    val uuid = javaUUID("uuid").autoGenerate()
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
    var moveInDark by DaoCuePropertyAssignments.moveInDark
    var uuid by DaoCuePropertyAssignments.uuid

    var target: TargetRef
        get() = TargetRef.of(targetType, targetKey)
        set(value) {
            targetType = value.discriminator
            targetKey = value.key
        }
}

// ─── Cue Layers table ──────────────────────────────────────────────────

/**
 * One line of a cue's ordered Look composition — "apply this Look, over these targets, in this
 * position in the stack".
 *
 * Directly superseded `DaoCuePresetApplications` (deleted in session 4), which was already the ordered per-cue
 * application list carrying `sortOrder`, the timing triple and speed-master overrides. This is an
 * extension of that table rather than a new concept beside it.
 *
 * The cue's own [DaoCuePropertyAssignments] / [DaoCueAdHocEffects] are **the local layer** —
 * always exactly one, always last, so it needs no identity row here. Keeping them separate is
 * deliberate on two counts: they carry `moveInDark`, and they give a Record / Update write an
 * unambiguous destination — `(targetType, targetKey, propertyName)` with no layer dimension.
 *
 * See `docs/plans/completed/looks-and-layers-plan.md` §3.2 and `fx/CueComposer.kt` for how a stack of these
 * is flattened to one contributor per (fixture, property) before the resolver sees it.
 */
object DaoCueLayers : IntIdTable("cue_layers") {
    val cue = reference("cue_id", DaoCues)

    /**
     * What this layer applies: **exactly one** of [look] / [template] is set.
     *
     * Two nullable FKs rather than a `(kind, uuid)` pair, because the FKs are what make a layer
     * pointing at a deleted record impossible and what let `LOOK_IN_USE` / `TEMPLATE_IN_USE` be a
     * count rather than a scan.
     *
     * The invariant now has a single decision behind it — [layerSourceShape] — and a CHECK
     * constraint below that stops a malformed row reaching the disk in the first place. Before
     * that it was enforced three ways with three behaviours: a read-time `check {}` that threw
     * mid-show, a silent drop on the REST write path, and an `ImportError` that aborted a whole
     * sync pull. Two of those are now one warn-and-drop; the importer keeps its hard failure
     * because archive JSON is untrusted input with a diagnostic channel of its own.
     */
    val look = reference("look_id", DaoLooks).nullable()
    val template = reference("template_id", DaoTemplates).nullable()
    val sortOrder = integer("sort_order").default(0)
    val enabled = bool("enabled").default(true)

    /**
     * The target set this layer operates over. **One meaning serving two jobs**: when non-empty it
     * *supplies* targets to a template layer's generic rows and to a Look's deferred *effects*, and
     * *filters* every bound row and effect. That single rule is what lets the migration preserve
     * coverage exactly — a cue that referenced a palette for two fixtures must not start asserting
     * every fixture the palette covers.
     *
     * Empty means "the source's own targets, unfiltered". A Look row always names one, so a Look
     * layer is complete without targets; a generic template row is not.
     */
    val targets = json<List<CueTargetDto>>("targets", Json)

    /**
     * Comma-separated [uk.me.cormack.lighting7.fx.PropertyMaskGroup] names; null = every property.
     * This is what subsumes value-level `ref:` — "this cue's colour comes from Warm, everything
     * else local" is one `COLOUR`-masked layer, not a separate feature. Parsed with the existing
     * [uk.me.cormack.lighting7.fx.parseMaskGroups] / [uk.me.cormack.lighting7.fx.maskAllows].
     */
    val propertyMask = varchar("property_mask", 255).nullable()

    /** How this layer combines with what has accumulated beneath: OVERRIDE / MAX / MIN / MULTIPLY. */
    val blendMode = varchar("blend_mode", 50).default("OVERRIDE")

    /**
     * Linear mix of this layer over what is beneath, in `[0, 1]` (grandMA3 calls this Amount).
     * `OVERRIDE` at `1.0` — the default — is plain replacement.
     */
    val amount = double("amount").default(1.0)

    /**
     * Within-cue stomp: suppress lower layers' *effects* on the properties this layer asserts.
     * The escape hatch for the one constraint layer order cannot express — effects are Layer 3 and
     * values are Layer 4, so an effect sits above a static value regardless of layer order.
     *
     * Note today's [DaoCues.stomp] is *cross-cue*: `FxEngine.stompForCue` removes ad-hoc effects
     * owned by *other* cue ids and explicitly excludes the stomping cue's own. Within-cue stomp is
     * therefore genuinely new behaviour built on existing scaffolding, and the column lands here
     * ahead of it — the same staging [DaoCues.stomp] itself used.
     */
    val stomp = bool("stomp").default(false)

    /** Per-layer speed-master override (null → each Look effect's own → master 1). */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()

    /** Per-layer wall-clock rate-master override (null → the effect's own). */
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()

    val delayMs = long("delay_ms").nullable()
    val intervalMs = long("interval_ms").nullable()
    val randomWindowMs = long("random_window_ms").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // The exactly-one rule, stated to the database as well as to the code.
        //
        // **This only reaches a DB created after it was added.** Exposed emits CHECK constraints in
        // `CREATE TABLE` only, and SQLite cannot `ALTER TABLE ADD CONSTRAINT`, so
        // `createMissingTablesAndColumns` leaves an existing `cue_layers` unconstrained — the dev
        // desk included. That is the same one-database bet the whole no-migrations position rests
        // on (see CLAUDE.md §Database); the shipped MSI's fresh install gets the constraint, and
        // the warn-and-drop paths are what hold the line on a DB that predates it. Don't read a
        // green test run as proof the constraint fires on the operator's desk.
        check("cue_layer_exactly_one_source") {
            (look.isNotNull() and template.isNull()) or (look.isNull() and template.isNotNull())
        }
    }
}

/** Logger for the shared layer-source rule below — the model layer's only diagnostic. */
private val layerSourceLogger = LoggerFactory.getLogger("cueLayerSource")

/**
 * Which of a cue layer's two mutually exclusive referent columns is set.
 *
 * Four-valued rather than a boolean because the two malformed shapes want different words in the
 * diagnostic, and because a caller that resolves the well-formed case still has to branch on
 * *which* side it got.
 */
enum class LayerSourceShape {
    LOOK,
    TEMPLATE,
    NEITHER,
    BOTH,
    ;

    /**
     * How this shape violates the invariant, in words fit for a message — or null when it doesn't.
     *
     * Callers switch on nullness rather than on the enum so that adding a third malformed shape
     * can't leave one of them silently treating it as valid.
     */
    val problem: String? get() = when (this) {
        LOOK, TEMPLATE -> null
        NEITHER -> "neither a look nor a template"
        BOTH -> "both a look and a template"
    }
}

/**
 * The `DaoCueLayers.look`/[DaoCueLayers.template] exactly-one rule, decided in one place.
 *
 * Takes `Any?` because the rule is purely about which of the two is *present*: the read path holds
 * entities, the REST write path int ids and the importer uuid strings, and all three want the same
 * answer. Typing it would mean three overloads agreeing by convention, which is the arrangement
 * this replaced.
 */
fun layerSourceShape(look: Any?, template: Any?): LayerSourceShape = when {
    look != null && template != null -> LayerSourceShape.BOTH
    look != null -> LayerSourceShape.LOOK
    template != null -> LayerSourceShape.TEMPLATE
    else -> LayerSourceShape.NEITHER
}

/**
 * The shared *behaviour*: a malformed pair warns, naming [layer], and the caller drops the layer.
 *
 * Returns true when there is nothing to say. A layer that names neither record or both cannot be
 * composed at all, and there is no reading of it that produces light — so every path short of the
 * importer treats it as absent rather than inventing a meaning or taking the desk down mid-show.
 *
 * [layer] is a lambda so a caller that has to dereference an FK for the description doesn't pay for
 * it on the overwhelmingly common well-formed path.
 */
fun LayerSourceShape.wellFormedOrWarn(layer: () -> String): Boolean {
    val problem = problem ?: return true
    layerSourceLogger.warn("cue layer {} names {} — dropping the layer", layer(), problem)
    return false
}

class DaoCueLayer(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueLayer>(DaoCueLayers)

    var cue by DaoCue referencedOn DaoCueLayers.cue
    var look by DaoLook optionalReferencedOn DaoCueLayers.look
    var template by DaoTemplate optionalReferencedOn DaoCueLayers.template
    var sortOrder by DaoCueLayers.sortOrder
    var enabled by DaoCueLayers.enabled
    var targets by DaoCueLayers.targets
    var propertyMask by DaoCueLayers.propertyMask
    var blendMode by DaoCueLayers.blendMode
    var amount by DaoCueLayers.amount
    var stomp by DaoCueLayers.stomp
    var speedMasterUuid by DaoCueLayers.speedMasterUuid
    var rateSpeedMasterUuid by DaoCueLayers.rateSpeedMasterUuid
    var delayMs by DaoCueLayers.delayMs
    var intervalMs by DaoCueLayers.intervalMs
    var randomWindowMs by DaoCueLayers.randomWindowMs
    var uuid by DaoCueLayers.uuid

    /**
     * What this layer applies, or null when the row violates the exactly-one-set invariant.
     *
     * This used to `check {}`, on the reasoning that failing loudly at the one place that
     * dereferences the columns kept a malformed row a write-boundary bug rather than a silent hole
     * in a cue. It didn't: the throw landed on the *apply* path, so a row written by some other
     * path — the cue-copy route wrote one on every template layer — took the GO down instead of the
     * write that caused it. Now [DaoCueLayers]'s CHECK constraint is the loud half, and the two
     * readers here ([toDto], [toCookLayer]) drop the layer with a warn.
     *
     * Must run inside a transaction — it dereferences the FK for its uuid and name.
     */
    val source: LayerSource? get() {
        val look = look
        val template = template
        if (!layerSourceShape(look, template).wellFormedOrWarn { "id ${id.value}" }) return null
        return if (look != null) {
            LayerSource.look(look.id.value, look.uuid, look.name)
        } else {
            LayerSource.template(template!!.id.value, template.uuid, template.name)
        }
    }

    /**
     * True when this layer fires on a timer rather than at cue apply.
     *
     * Mirrors `CookLayer.isTimed`, and the same rule holds:
     * `randomWindowMs` alone does **not** make a layer timed — it only jitters an interval that is
     * already there.
     */
    val isTimed: Boolean get() = delayMs != null || intervalMs != null
}

/** Convert a DaoCuePropertyAssignment entity to its DTO form. Health defaults to [AssignmentHealth.Ok]. */
internal fun DaoCuePropertyAssignment.toDto() = CuePropertyAssignmentDto(
    targetType = targetType,
    targetKey = targetKey,
    propertyName = propertyName,
    value = value,
    fadeDurationMs = fadeDurationMs,
    sortOrder = sortOrder,
    moveInDark = moveInDark,
)

/** Convert a DaoCueAdHocEffect entity to its DTO form. */
internal fun DaoCueAdHocEffect.toDto() = CueAdHocEffectDto(
    targetType = targetType,
    targetKey = targetKey,
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
    sortOrder = sortOrder,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)
