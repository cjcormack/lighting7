package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.javatime.duration

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
    constructor(target: FxTargetsRef) : this(target.discriminator, target.key)

    val target: TargetRef get() = TargetRef.of(type, key)

    /** [target], or a selection (effects plan D3) — only an effect's target may be one. */
    val fxTarget: FxTargetsRef get() = FxTargetsRef.of(type, key)
}

/**
 * Same fixtures/groups, regardless of order — what "the same target set" means everywhere a layer
 * (programmer or cue) is matched by its targets: [uk.me.cormack.lighting7.fx.ProgrammerLayerStack.toggle],
 * and the Record REMOVE/MERGE layer matching in `programmerRecord.kt`.
 *
 * This is a comparison of what the two lists *say*, not of the heads they cover: a group and its
 * members are two different target sets by this rule even though they light the same fixtures.
 * That is the right answer for Record, which is matching a layer against the one it was recorded
 * from, and it is why `toggle` asks this question first and then asks
 * `ProgrammerLayerStack.coverage` a second, group-blind one — the pads are about heads.
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
 * what [targets] and [attributeMask] mean.
 *
 * [lookId] is the int PK for API traffic — the same choice `CuePresetApplicationDto.presetId` made
 * before it retired with `cue_preset_applications` in session 4, and safe for the same reason: it is
 * a real foreign key the importer rewrites. Sync carries the Look's uuid instead, because a uuid is
 * the only form that survives *inside* an opaque value.
 */
@Serializable
data class CueLayerDto(
    /**
     * What this layer applies: **exactly one** of [lookId] / [paletteId] on write.
     *
     * Two ids rather than a `(kind, id)` pair because that is what a client has in hand — it picked
     * a row out of one library or the other — and because a request naming both is then a shape
     * error the route can refuse rather than a discriminator it has to trust.
     */
    val lookId: Int? = null,
    val paletteId: Int? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    /** Comma-separated `AttributeFamily` names; null = every property. */
    val attributeMask: String? = null,
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
     * on the assignment DTOs, and the reason [lookId] / [paletteId] are still the write fields.
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
    /**
     * The stored layer's uuid, on read only, as `CueAdHocFxDto.uuid` is: ignored on write but
     * by the Timeline's `restore`, which puts a cue back exactly as an edit found it.
     */
    val uuid: String? = null,
)

/**
 * Layer 4 property assignment — operator-authored "this cue asserts property X = value" record.
 * See `docs/lighting-composition-model.md` §"Tier 4 — Cue attribute assignments" for semantics (specificity, composition,
 * crossfade) and `uk.me.cormack.lighting7.fx.CueAssignmentResolver` for the canonical value parser.
 */
@Serializable
data class CueAttributeAssignmentDto(
    val targetType: String,
    val targetKey: String,
    val attribute: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val sortOrder: Int = 0,
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
data class CueAdHocFxDto(
    val targetType: String,
    val targetKey: String,
    val fxType: String,
    val family: String,
    val attribute: String? = null,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    val parameters: Map<String, String> = emptyMap(),
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    val sortOrder: Int = 0,
    /** Speed master uuid (null → master 1). Uuid, not int id — see [LookFxSpec.speedMasterUuid]. */
    val speedMasterUuid: String? = null,
    /** Wall-clock rate master (null → unscaled). See [LookFxSpec.rateSpeedMasterUuid]. */
    val rateSpeedMasterUuid: String? = null,
    /**
     * On *Seconds* (venue-visit plan D7): the effect follows no master. A beat effect's
     * `cycle` is then its own cycle in seconds; a wall-clock effect's is seconds already, and
     * it is on Seconds whenever it has no rate master, flag or not. The masters are null beside it.
     * See `fx/FxTimingChoice.kt`.
     */
    val seconds: Boolean = false,
    /**
     * Amount (effects plan D2), 0.0–1.0: how much of the effect reaches the stage over what is
     * underneath, weighted after the blend (P3). Refused outside that range at the write boundary.
     */
    val amount: Double = 1.0,
    /**
     * Direction (D5): `FORWARD`, `BACKWARD` or `BOUNCE`, each member's cycle read that way. A stored
     * FX's field, absent Forward as its archive reads it; the doors that make a new FX (the picker,
     * the palette editor, MCP) write its type's own Direction (`FxRegistration.defaultDirection`).
     */
    val direction: String = "FORWARD",
    /** Runs (D9): stop after this many cycles, at least 1; null runs forever. */
    val runs: Int? = null,
    /**
     * The spread's shape (effects plan D4): Spread 0.0–2.0 (1.0 the Order's own offsets), Wings and
     * Segments at least 1, Parts 0 (off) or more — see `fx/SpreadShape.kt`. Refused out of range, or
     * wings under a mirrored Order, at the write boundary.
     */
    val spread: Double = 1.0,
    val wings: Int = 1,
    val segments: Int = 1,
    val parts: Int = 0,
    /** What [spread] counts (fx-settings plan D6): `STEP`, the FX's own steps; null is `CYCLE`. */
    val spreadUnit: String? = null,
    /**
     * The stored effect's uuid, on read only, so a client can name one effect across writes
     * (show-cue-editing plan §3.4): a write keeps the uuid of every effect it keeps. Ignored by a
     * body's write (the REST and MCP cue writers), like [CueLayerDto.id] — an effect is matched by
     * its target, property and type, and a body that echoed a uuid back must not be trusted as
     * identity. Null where no stored effect stands behind the DTO (an AI tool's input, a capture of
     * a fresh FX); the desk's own capture of an FX Include brought back carries its cue child's,
     * which Record's Merge and Update write it onto.
     */
    val uuid: String? = null,
    /**
     * The effect's run key (stack-timeline plan D7), on read only like [uuid]: rows of neighbouring
     * cues that share one draw as one run. Ignored on write (P2) — only the timeline, the cue copier
     * and the importer set it, so no other door can break a run. Null for an effect in no run.
     */
    val runUuid: String? = null,
) {
    /** A fixture, a group, or a selection of them (effects plan D3). */
    val target: FxTargetsRef get() = FxTargetsRef.of(targetType, targetKey)

    /** This effect with its target spelled as the desk stores it ([FxTargetsRef.canonical]). */
    fun canonicalTarget(): CueAdHocFxDto =
        FxTargetsRef.canonical(targetType, targetKey).let { (type, key) -> copy(targetType = type, targetKey = key) }
}

// ─── Cue types ──────────────────────────────────────────────────────────

/**
 * The wire vocabulary for a row of a stack's list: `STANDARD` is a cue entry, `MARKER` a marker
 * entry. A cue itself has no type since the cue model plan's session 1 — a marker is an entry, not
 * a cue (D12) — but the stack list, the cue forms and today's sync archive all still say it this
 * way, so the word survives as how an entry's kind is read out (`CueStackEntryKind`).
 */
enum class CueType { STANDARD, MARKER }

// ─── Cues table ─────────────────────────────────────────────────────────

/**
 * What a cue *is* (cue model plan D2): its name, fade, notes and stomp, with its layers, values,
 * effects, hooks, scenery and events as children. Where it plays — its stack, position, number
 * and follow — is its entry's (`DaoCueStackEntries`), since the startup migration
 * `state/CueModelMigration.kt` moved those columns off this table.
 */
object DaoCues : IntIdTable("cues") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val fadeDuration = duration("fade_duration").nullable()
    val fadeCurve = varchar("fade_curve", 50).default("LINEAR")
    val notes = text("notes").nullable()
    /**
     * When true, applying this cue removes ad-hoc effects owned by *other* cues that target
     * properties covered by this cue's Layer 4 assignments. Mirrors grandMA3's `Stomp`.
     * See `docs/lighting-composition-model.md` §"Stomp".
     */
    val stomp = bool("stomp").default(false)
    /** The speed masters this cue sets on GO, in order (venue-visit plan S16) — see `cueSpeedMasters.kt`. */
    val speedMasters = json<List<CueSpeedMasterDto>>("speed_masters", Json).default(emptyList())
    val uuid = javaUUID("uuid").autoGenerate()

    /** *Move in dark* on the cue that lights a head (move-in-dark plan D3), read through [moveInDarkSettings]. */
    val moveInDarkMode = varchar("move_in_dark_mode", 10).default(CueMoveInDarkMode.STACK.name)
    /** Null is the stack's. */
    val moveInDarkDelay = duration("move_in_dark_delay").nullable()
    val moveInDarkTime = duration("move_in_dark_time").nullable()
    /** The heads kept live, by key. */
    val moveInDarkLive = json<List<String>>("move_in_dark_live", Json).default(emptyList())

    // Cue names are deliberately *not* unique: two stacks may both hold a "Blackout". Cues are
    // identified by id (and by uuid across sync); numbers are an entry's, unique per stack through
    // `uq_entry_number_per_stack`.
}

class DaoCue(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCue>(DaoCues)

    var name by DaoCues.name
    var project by DaoProject referencedOn DaoCues.project
    var fadeDuration by DaoCues.fadeDuration
    var fadeCurve by DaoCues.fadeCurve
    var notes by DaoCues.notes
    var stomp by DaoCues.stomp
    var speedMasters by DaoCues.speedMasters
    var uuid by DaoCues.uuid
    internal var moveInDarkMode by DaoCues.moveInDarkMode
    internal var moveInDarkDelay by DaoCues.moveInDarkDelay
    internal var moveInDarkTime by DaoCues.moveInDarkTime
    internal var moveInDarkLive by DaoCues.moveInDarkLive
    val layers by DaoCueLayer referrersOn DaoCueLayers.cue
    val adHocFx by DaoCueAdHocFxEntity referrersOn DaoCueAdHocFx.cue
    val attributeAssignments by DaoCueAttributeAssignment referrersOn DaoCueAttributeAssignments.cue
    val triggers by DaoCueTrigger referrersOn DaoCueTriggers.cue
}

// ─── Cue Ad-Hoc FX table ───────────────────────────────────────────────

object DaoCueAdHocFx : IntIdTable("cue_ad_hoc_fx") {
    val cue = reference("cue_id", DaoCues)
    val targetType = varchar("target_type", 50)

    /** `text`, not a `varchar(255)`: a selection's canonical key outgrows any fixed width. */
    val targetKey = text("target_key")
    val fxType = varchar("fx_type", 255)
    val family = varchar("family", 50)
    val attribute = varchar("attribute", 255).nullable()
    val cycle = double("cycle")
    val blendMode = varchar("blend_mode", 50)
    val order = varchar("spread_order", 50)
    val phaseOffset = double("phase_offset").default(0.0)
    val elementMode = varchar("element_mode", 50).nullable()
    val elementFilter = varchar("element_filter", 50).nullable()
    val timing = varchar("timing", 8).nullable()
    val parameters = json<Map<String, String>>("parameters", Json)
    val delay = duration("delay").nullable()
    val interval = duration("interval").nullable()
    val randomWindow = duration("random_window").nullable()
    val sortOrder = integer("sort_order").default(0)
    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()

    /** D7's *Seconds* for a beat effect; see [CueAdHocFxDto.seconds]. */
    val seconds = bool("seconds").default(false)

    /** Amount (effects plan D2), 0.0–1.0; see [LookFxSpec.amount]. */
    val amount = double("amount").default(1.0)

    /** Direction (D5) by name; see [LookFxSpec.direction]. */
    val direction = varchar("direction", 16).default("FORWARD")

    /** Runs (D9), null for forever; see [LookFxSpec.runs]. */
    val runs = integer("runs").nullable()

    /**
     * The spread's shape (effects plan D4); see [LookFxSpec.spread]. Parts 0 is off. The three
     * counts are `spread_`-prefixed columns: `GROUPS` is an SQLite keyword (window frames).
     */
    val spread = double("spread").default(1.0)
    val wings = integer("spread_wings").default(1)
    val segments = integer("spread_segments").default(1)
    val parts = integer("spread_parts").default(0)
    /** [LookFxSpec.spreadUnit]: `STEP`, or null for `CYCLE` — every row written before it existed. */
    val spreadUnit = varchar("spread_unit", 8).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    /** The run key (stack-timeline plan D7), null for an effect in no run; see [CueAdHocFxDto.runUuid]. */
    val runUuid = javaUUID("run_uuid").nullable()
}

class DaoCueAdHocFxEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueAdHocFxEntity>(DaoCueAdHocFx)

    var cue by DaoCue referencedOn DaoCueAdHocFx.cue
    var targetType by DaoCueAdHocFx.targetType
    var targetKey by DaoCueAdHocFx.targetKey

    /** A fixture, a group, or a selection of them (effects plan D3). */
    var target: FxTargetsRef
        get() = FxTargetsRef.of(targetType, targetKey)
        set(value) {
            targetType = value.discriminator
            targetKey = value.key
        }

    var fxType by DaoCueAdHocFx.fxType
    var family by DaoCueAdHocFx.family
    var attribute by DaoCueAdHocFx.attribute
    var cycle by DaoCueAdHocFx.cycle
    var blendMode by DaoCueAdHocFx.blendMode
    var order by DaoCueAdHocFx.order
    var phaseOffset by DaoCueAdHocFx.phaseOffset
    var elementMode by DaoCueAdHocFx.elementMode
    var elementFilter by DaoCueAdHocFx.elementFilter
    var timing by DaoCueAdHocFx.timing
    var parameters by DaoCueAdHocFx.parameters
    var delay by DaoCueAdHocFx.delay
    var interval by DaoCueAdHocFx.interval
    var randomWindow by DaoCueAdHocFx.randomWindow
    var sortOrder by DaoCueAdHocFx.sortOrder
    var speedMasterUuid by DaoCueAdHocFx.speedMasterUuid
    var rateSpeedMasterUuid by DaoCueAdHocFx.rateSpeedMasterUuid
    var seconds by DaoCueAdHocFx.seconds
    var amount by DaoCueAdHocFx.amount
    var direction by DaoCueAdHocFx.direction
    var runs by DaoCueAdHocFx.runs
    var spread by DaoCueAdHocFx.spread
    var wings by DaoCueAdHocFx.wings
    var segments by DaoCueAdHocFx.segments
    var parts by DaoCueAdHocFx.parts
    var spreadUnit by DaoCueAdHocFx.spreadUnit
    var uuid by DaoCueAdHocFx.uuid
    var runUuid by DaoCueAdHocFx.runUuid
}

// ─── Cue Property Assignments table ────────────────────────────────────

object DaoCueAttributeAssignments : IntIdTable("cue_attribute_assignments") {
    val cue = reference("cue_id", DaoCues)
    val targetType = varchar("target_type", 50)
    val targetKey = varchar("target_key", 255)
    val attribute = varchar("attribute", 255)
    val value = text("value")
    val fadeDuration = duration("fade_duration").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoCueAttributeAssignment(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueAttributeAssignment>(DaoCueAttributeAssignments)

    var cue by DaoCue referencedOn DaoCueAttributeAssignments.cue
    var targetType by DaoCueAttributeAssignments.targetType
    var targetKey by DaoCueAttributeAssignments.targetKey
    var attribute by DaoCueAttributeAssignments.attribute
    var value by DaoCueAttributeAssignments.value
    var fadeDuration by DaoCueAttributeAssignments.fadeDuration
    var sortOrder by DaoCueAttributeAssignments.sortOrder
    var uuid by DaoCueAttributeAssignments.uuid

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
 * The cue's own [DaoCueAttributeAssignments] / [DaoCueAdHocFx] are **the local layer** —
 * always exactly one, always last, so it needs no identity row here. Keeping them separate gives a
 * Record / Update write an unambiguous destination — `(targetType, targetKey, attribute)` with no
 * layer dimension.
 *
 * See `docs/plans/completed/looks-and-layers-plan.md` §3.2 and `fx/CueComposer.kt` for how a stack of these
 * is flattened to one contributor per (fixture, property) before the resolver sees it.
 */
object DaoCueLayers : IntIdTable("cue_layers") {
    val cue = reference("cue_id", DaoCues)

    /**
     * What this layer applies: **exactly one** of [look] / [palette] is set.
     *
     * Two nullable FKs rather than a `(kind, uuid)` pair, because the FKs are what make a layer
     * pointing at a deleted record impossible and what let `LOOK_IN_USE` / `PALETTE_IN_USE` be a
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
    val palette = reference("palette_id", DaoPalettes).nullable()
    val sortOrder = integer("sort_order").default(0)
    val enabled = bool("enabled").default(true)

    /**
     * A palette layer's target set. **One meaning serving two jobs**: when non-empty it *supplies*
     * targets to the palette's generic rows and to its *effect*, and *filters* its bound rows. Empty
     * means "the palette's own rows' targets"; a generic palette is not complete without targets.
     *
     * A Look's layer names none (absolute-looks plan D1): a Look lands whole, a stored write naming
     * targets for one is refused (`LOOK_LAYER_TARGETED`), the importer drops them, and
     * `LookLayerTargetsMigration.kt` cleared those this database held.
     */
    val targets = json<List<CueTargetDto>>("targets", Json)

    /**
     * Comma-separated [uk.me.cormack.lighting7.fx.AttributeFamily] names; null = every property.
     * This is what subsumes value-level `ref:` — "this cue's colour comes from Warm, everything
     * else local" is one `COLOUR`-masked layer, not a separate feature. Parsed with the existing
     * [uk.me.cormack.lighting7.fx.parseFamilies] / [uk.me.cormack.lighting7.fx.maskAllows].
     */
    val attributeMask = varchar("attribute_mask", 255).nullable()

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

    val delay = duration("delay").nullable()
    val interval = duration("interval").nullable()
    val randomWindow = duration("random_window").nullable()
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
            (look.isNotNull() and palette.isNull()) or (look.isNull() and palette.isNotNull())
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
    PALETTE,
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
        LOOK, PALETTE -> null
        NEITHER -> "neither a look nor a palette"
        BOTH -> "both a look and a palette"
    }
}

/**
 * The `DaoCueLayers.look`/[DaoCueLayers.palette] exactly-one rule, decided in one place.
 *
 * Takes `Any?` because the rule is purely about which of the two is *present*: the read path holds
 * entities, the REST write path int ids and the importer uuid strings, and all three want the same
 * answer. Typing it would mean three overloads agreeing by convention, which is the arrangement
 * this replaced.
 */
fun layerSourceShape(look: Any?, template: Any?): LayerSourceShape = when {
    look != null && template != null -> LayerSourceShape.BOTH
    look != null -> LayerSourceShape.LOOK
    template != null -> LayerSourceShape.PALETTE
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
    var palette by DaoPalette optionalReferencedOn DaoCueLayers.palette
    var sortOrder by DaoCueLayers.sortOrder
    var enabled by DaoCueLayers.enabled
    var targets by DaoCueLayers.targets
    var attributeMask by DaoCueLayers.attributeMask
    var blendMode by DaoCueLayers.blendMode
    var amount by DaoCueLayers.amount
    var stomp by DaoCueLayers.stomp
    var speedMasterUuid by DaoCueLayers.speedMasterUuid
    var rateSpeedMasterUuid by DaoCueLayers.rateSpeedMasterUuid
    var delay by DaoCueLayers.delay
    var interval by DaoCueLayers.interval
    var randomWindow by DaoCueLayers.randomWindow
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
        val template = palette
        if (!layerSourceShape(look, template).wellFormedOrWarn { "id ${id.value}" }) return null
        return if (look != null) {
            LayerSource.look(look.id.value, look.uuid, look.name)
        } else {
            LayerSource.palette(template!!.id.value, template.uuid, template.name)
        }
    }

    /**
     * True when this layer fires on a timer rather than at cue apply.
     *
     * Mirrors `CookLayer.isTimed`, and the same rule holds:
     * `randomWindow` alone does **not** make a layer timed — it only jitters an interval that is
     * already there.
     */
    val isTimed: Boolean get() = delay != null || interval != null
}

/** Convert a DaoCueAttributeAssignment entity to its DTO form. Health defaults to [AssignmentHealth.Ok]. */
internal fun DaoCueAttributeAssignment.toDto() = CueAttributeAssignmentDto(
    targetType = targetType,
    targetKey = targetKey,
    attribute = attribute,
    value = value,
    fadeDurationMs = fadeDuration?.toMillis(),
    sortOrder = sortOrder,
)

/** Convert a DaoCueAdHocFxEntity entity to its DTO form. */
internal fun DaoCueAdHocFxEntity.toDto() = CueAdHocFxDto(
    targetType = targetType,
    targetKey = targetKey,
    fxType = fxType,
    family = family,
    attribute = attribute,
    cycle = cycle,
    blendMode = blendMode,
    order = order,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    timing = timingOf(timing),
    parameters = parameters,
    delayMs = delay?.toMillis(),
    intervalMs = interval?.toMillis(),
    randomWindowMs = randomWindow?.toMillis(),
    sortOrder = sortOrder,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
    seconds = seconds,
    amount = amount,
    direction = direction,
    runs = runs,
    spread = spread,
    wings = wings,
    segments = segments,
    parts = parts,
    spreadUnit = spreadUnit,
    uuid = uuid.toString(),
    runUuid = runUuid?.toString(),
)
