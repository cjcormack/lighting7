package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable

/**
 * Per-project persisted mapping from a physical control on a control-surface device to a
 * [uk.me.cormack.lighting7.midi.BindingTarget]. Keyed by
 * `(projectId, deviceTypeKey, controlId, bank)` — bank may be NULL for global bindings that
 * apply regardless of active bank.
 *
 * The target payload is stored as a JSON string (serialized via [BindingTargetJson]) rather
 * than Exposed's `json<T>` helper so the discriminated union survives future sealed-class
 * additions without column migrations.
 */
object DaoControlSurfaceBindings : IntIdTable("control_surface_bindings") {
    val project = reference("project_id", DaoProjects)

    /** References `@ControlSurfaceType.typeKey` as a stable contract — bindings survive profile refactors only as long as the typeKey doesn't change. */
    val deviceTypeKey = varchar("device_type_key", 100)

    /** Stable `ControlDescriptor.controlId` from the device profile. */
    val controlId = varchar("control_id", 100)

    /** App-side bank id (matches a [uk.me.cormack.lighting7.midi.BankDefinition.id]); null = global. */
    val bank = varchar("bank", 100).nullable()

    /** Discriminator mirror of the JSON payload for cheap list-view queries. */
    val targetType = varchar("target_type", 50)

    /** JSON serialization of the full [uk.me.cormack.lighting7.midi.BindingTarget]. */
    val targetPayload = text("target_payload")

    /** `null` = inherit device class default; otherwise one of [BindingTakeoverPolicy] names. */
    val takeoverPolicy = varchar("takeover_policy", 30).nullable()

    val sortOrder = integer("sort_order").default(0)

    init {
        uniqueIndex("uq_surface_binding_slot", project, deviceTypeKey, controlId, bank)
    }
}

class DaoControlSurfaceBinding(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoControlSurfaceBinding>(DaoControlSurfaceBindings)

    var project by DaoProject referencedOn DaoControlSurfaceBindings.project
    var deviceTypeKey by DaoControlSurfaceBindings.deviceTypeKey
    var controlId by DaoControlSurfaceBindings.controlId
    var bank by DaoControlSurfaceBindings.bank
    var targetType by DaoControlSurfaceBindings.targetType
    var targetPayload by DaoControlSurfaceBindings.targetPayload
    var takeoverPolicy by DaoControlSurfaceBindings.takeoverPolicy
    var sortOrder by DaoControlSurfaceBindings.sortOrder
}

/**
 * How a bound fader / encoder re-engages after bank switch or logical-value change.
 * Phase 4 wires the actual behaviour; Phase 2 only persists the choice.
 */
enum class BindingTakeoverPolicy {
    /** Accept inbound values immediately — the device's motor (or the operator) handles sync. */
    IMMEDIATE,

    /** Ignore inbound values until the physical control crosses the logical value. */
    PICKUP;

    companion object {
        /** Case-insensitive parse. Returns null on unknown value. */
        fun parseOrNull(value: String): BindingTakeoverPolicy? = try {
            valueOf(value.uppercase())
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
