package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.javatime.duration

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * The `targetType` discriminator marking a [DaoPaletteRows] row as *deferred*: it names no target
 * of its own and takes its targets from whatever applies it — the selection a template is applied
 * to, or the [DaoCueLayers] line referencing it.
 *
 * **Not a Look's.** A Look row and a Look effect always name their own target. The deferred half of
 * the row became [DaoPalettes] in session 3; a deferred Look *effect* is refused at the write
 * boundary, dropped on import and was deleted from this database once (absolute-looks plan D2,
 * `state/DeferredLookEffectsMigration.kt`). A stored row or effect carrying it is data from an
 * older database and is dropped on read.
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
 * moved to [DaoPalettes], which is a better home for it (one family, an intent per row, resolved
 * per head).
 *
 * [value] is always a **literal** in the canonical
 * [uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue.serialize] grammar. **Looks do
 * not nest**: a `ref:` value is rejected at the write boundary, so resolution can never recurse.
 *
 * `moveInDark` deliberately has no place here — it is a cue-*crossfade* concept, which is also
 * why this type excludes it. It lives on the cue's local layer only.
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
    /**
     * The row's target. A Look row always names one — see [DEFERRED_TARGET_TYPE].
     *
     * **Throws** on a discriminator that names no arm, [DEFERRED_TARGET_TYPE] included, because a
     * `LookRowDto` is a *client-supplied* shape and this is only safe to ask past
     * `validateLookRows`. [DaoLookRow.target] answers null instead, which is the right reading for
     * a row already in the database — the two are asymmetric on purpose.
     */
    val target: TargetRef get() = TargetRef.of(targetType, targetKey)
}

/**
 * One stored Look effect, on its own fixture, group or selection — the shape [CueAdHocFxDto]
 * has, with real columns, which is why the two record into one another field for field.
 *
 * The `delayMs` / `intervalMs` / `randomWindowMs` triple is **not** here: timing is a property of
 * the layer applying the Look, not of the Look itself, and it lives on [DaoCueLayers].
 */
@Serializable
data class LookFxDto(
    val targetType: String,
    val targetKey: String,
    val fxType: String,
    val family: String,
    val propertyName: String? = null,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
    val parameters: Map<String, String> = emptyMap(),
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
    val sortOrder: Int = 0,
) {
    /** This effect with its target spelled as the desk stores it ([FxTargetsRef.canonical]). */
    fun canonicalTarget(): LookFxDto =
        FxTargetsRef.canonical(targetType, targetKey).let { (type, key) -> copy(targetType = type, targetKey = key) }
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
    var uuid by DaoLooks.uuid
    val rows by DaoLookRow referrersOn DaoLookRows.look
    val effects by DaoLookFxEntity referrersOn DaoLookFx.look
}

// ─── Look Rows table ───────────────────────────────────────────────────

object DaoLookRows : IntIdTable("look_rows") {
    val look = reference("look_id", DaoLooks)

    /** A [TargetRef] discriminator — `fixture` or `group`, never [DEFERRED_TARGET_TYPE]. */
    val targetType = varchar("target_type", 50)

    val targetKey = varchar("target_key", 255)
    val attribute = varchar("attribute", 255)
    val value = text("value")
    val fadeDuration = duration("fade_duration").nullable()

    /**
     * The cell of a multi-element fixture this row is on, by the cell's **full** element key
     * (`"shehds-1.zone-2"`), [targetKey] being its parent. Null = the whole fixture. Every writer
     * (Record, `create_look`'s values) stores the full key and every reader resolves it through
     * `Fixtures.untypedGroupableFixture`.
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
    var attribute by DaoLookRows.attribute
    var value by DaoLookRows.value
    var fadeDuration by DaoLookRows.fadeDuration
    var elementKey by DaoLookRows.elementKey
    var sortOrder by DaoLookRows.sortOrder
    var uuid by DaoLookRows.uuid

    /**
     * The row's own target, or null when the stored discriminator names no known arm — callers
     * skip rather than guess, as `loadPaletteSnapshot` did.
     *
     * [DEFERRED_TARGET_TYPE] is one such unknown arm, and deliberately so: a Look row is always
     * bound, so a stored `deferred` row is data an older database wrote and answers null here
     * rather than getting a second, row-shaped meaning back.
     */
    val target: TargetRef?
        get() = TargetRef.ofOrNull(targetType, targetKey)
}

// ─── Look FX table ─────────────────────────────────────────────────────

object DaoLookFx : IntIdTable("look_fx") {
    val look = reference("look_id", DaoLooks)

    /**
     * An [FxTargetsRef] discriminator. An older database may hold [DEFERRED_TARGET_TYPE], which
     * nothing reads as a target; `DeferredLookEffectsMigration.kt` deleted those rows once.
     */
    val targetType = varchar("target_type", 50)

    /**
     * `text`, not a `varchar(255)`: a selection's canonical key outgrows any fixed width. Empty
     * beside [DEFERRED_TARGET_TYPE].
     */
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

    /** Speed master this effect subscribes to (null → master 1). */
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()

    /** D7's *Seconds* for a beat effect; see [LookFxSpec.seconds]. */
    val seconds = bool("seconds").default(false)

    /** Amount (effects plan D2), 0.0–1.0; see [LookFxSpec.amount]. */
    val amount = double("amount").default(1.0)

    /** Direction (D5) by name; see [LookFxSpec.direction]. */
    val direction = varchar("direction", 16).default("FORWARD")

    /** Runs (D9), null for forever; see [LookFxSpec.runs]. */
    val runs = integer("runs").nullable()

    /**
     * The spread's shape (effects plan D4); see [LookFxSpec.spread]. Groups 0 is off. The three
     * counts are `spread_`-prefixed columns: `GROUPS` is an SQLite keyword (window frames).
     */
    val spread = double("spread").default(1.0)
    val wings = integer("spread_wings").default(1)
    val segments = integer("spread_segments").default(1)
    val parts = integer("spread_parts").default(0)
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
 * that have nothing to do with presets. `DaoLookFx` gave its fields real columns; this is still
 * the shape they are handed on in.
 */
@Serializable
data class LookFxSpec(
    val fxType: String,
    val family: String,
    val propertyName: String? = null,
    val cycle: Double,
    val blendMode: String,
    val order: String,
    val phaseOffset: Double = 0.0,
    val elementMode: String? = null,
    val elementFilter: String? = null,
    val timing: Timing? = null,
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
)

/**
 * This spec with a layer's beat-division override applied — a busk press's or an AI press's speed.
 * A spec on *Seconds* (D7) keeps its own cycle: the override is beats, and a Seconds effect follows
 * no master for a beat to mean anything against.
 */
fun LookFxSpec.withCycleOverride(override: Double?): LookFxSpec =
    if (override == null || seconds) this else copy(cycle = override)

class DaoLookFxEntity(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoLookFxEntity>(DaoLookFx)

    var look by DaoLook referencedOn DaoLookFx.look
    var targetType by DaoLookFx.targetType
    var targetKey by DaoLookFx.targetKey
    var fxType by DaoLookFx.fxType
    var family by DaoLookFx.family
    var attribute by DaoLookFx.attribute
    var cycle by DaoLookFx.cycle
    var blendMode by DaoLookFx.blendMode
    var order by DaoLookFx.order
    var phaseOffset by DaoLookFx.phaseOffset
    var elementMode by DaoLookFx.elementMode
    var elementFilter by DaoLookFx.elementFilter
    var timing by DaoLookFx.timing
    var parameters by DaoLookFx.parameters
    var speedMasterUuid by DaoLookFx.speedMasterUuid
    var rateSpeedMasterUuid by DaoLookFx.rateSpeedMasterUuid
    var seconds by DaoLookFx.seconds
    var amount by DaoLookFx.amount
    var direction by DaoLookFx.direction
    var runs by DaoLookFx.runs
    var spread by DaoLookFx.spread
    var wings by DaoLookFx.wings
    var segments by DaoLookFx.segments
    var parts by DaoLookFx.parts
    var sortOrder by DaoLookFx.sortOrder
    var uuid by DaoLookFx.uuid

    /** The effect's own target, or null when the discriminator is unknown — [DEFERRED_TARGET_TYPE] among them. */
    val target: FxTargetsRef?
        get() = FxTargetsRef.ofOrNull(targetType, targetKey)
}
