package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import uk.me.cormack.lighting7.fx.AssignmentHealth
import uk.me.cormack.lighting7.fx.PropertyMaskGroup

/**
 * A palette's attribute type. Deliberately an alias of [PropertyMaskGroup] rather than a
 * parallel enum: the console concepts are the same four buckets, so aliasing means
 * `parseMaskGroups` recording-by-type and `maskGroupForProperty` ("does this property belong
 * in this palette?") work unchanged, with no bidirectional 4-arm mapping for every consumer
 * to cross. De-alias if a type ever needs to exist that isn't a mask group.
 */
typealias PaletteType = PropertyMaskGroup

/**
 * One stored palette row: "for this target, this property is this value".
 *
 * Deliberately the same shape as [CuePropertyAssignmentDto] minus `fadeDurationMs` and
 * `moveInDark` — both cue-*timing* concepts with no meaning in a palette. That shared shape
 * is what lets the Record path reuse
 * [uk.me.cormack.lighting7.routes.collapseRecordingToAssignments] verbatim, group-collapse
 * rule included.
 *
 * [value] is always a literal in the canonical
 * [uk.me.cormack.lighting7.fx.CueAssignmentResolver.PropertyValue.serialize] grammar. **Palettes do
 * not nest**: a `ref:` value is rejected at the write boundary, so resolving a reference can
 * never recurse.
 */
@Serializable
data class PaletteEntryDto(
    val targetType: String,
    val targetKey: String,
    val propertyName: String,
    val value: String,
    val sortOrder: Int = 0,
    /**
     * Validation status of this row against the live patch. Populated server-side on read;
     * ignored on write — the server never trusts client-supplied health. Same contract as
     * [CuePropertyAssignmentDto.health].
     */
    val health: AssignmentHealth = AssignmentHealth.Ok,
) {
    val target: TargetRef get() = TargetRef.of(targetType, targetKey)
}

/**
 * A named, typed collection of per-fixture property values that cues, FX presets and the
 * programmer can reference by *identity* rather than by value — the console palette.
 *
 * A stored assignment holds the string `ref:{uuid}` (see
 * [uk.me.cormack.lighting7.fx.paletteRefValue]) in place of a literal; it resolves per fixture
 * at build time, and editing the palette republishes every live consumer. That republish is
 * the point of the feature: one edit moves every look that references it.
 *
 * The reference is stored as the **uuid**, not the int id, because int primary keys never
 * appear in the sync export — every cross-record reference in the layout is a `{table}Uuid`
 * string and int PKs are re-minted by the DB on import, so `ref:12` would dangle after any
 * import. A uuid survives a plain import (written back verbatim) and a clone alike, because
 * [uk.me.cormack.lighting7.sync.ExportUuidRemapper] substitutes uuids across the whole JSON
 * text — including one embedded in an opaque value string. See `docs/sync-engineering.md`.
 */
object DaoPalettes : IntIdTable("palettes") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)

    /** [PaletteType] name — `INTENSITY` / `POSITION` / `COLOUR` / `BEAM`. */
    val type = varchar("type", 20)
    val notes = text("notes").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Scoped by type as well as project: "Warm" is a perfectly reasonable name for both a
        // colour and a position palette, and consoles treat the per-type banks as separate
        // namespaces.
        uniqueIndex(project, type, name)
    }
}

class DaoPalette(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPalette>(DaoPalettes)

    var project by DaoProject referencedOn DaoPalettes.project
    var name by DaoPalettes.name
    var type by DaoPalettes.type
    var notes by DaoPalettes.notes
    var sortOrder by DaoPalettes.sortOrder
    var uuid by DaoPalettes.uuid
    val entries by DaoPaletteEntry referrersOn DaoPaletteEntries.palette

    /** [type] as the enum, or null when the stored string doesn't name a known type. */
    val paletteType: PaletteType?
        get() = PaletteType.entries.firstOrNull { it.name == type }
}

// ─── Palette Entries table ─────────────────────────────────────────────

object DaoPaletteEntries : IntIdTable("palette_entries") {
    val palette = reference("palette_id", DaoPalettes)
    val targetType = varchar("target_type", 50)
    val targetKey = varchar("target_key", 255)
    val propertyName = varchar("property_name", 255)
    val value = text("value")
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoPaletteEntry(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoPaletteEntry>(DaoPaletteEntries)

    var palette by DaoPalette referencedOn DaoPaletteEntries.palette
    var targetType by DaoPaletteEntries.targetType
    var targetKey by DaoPaletteEntries.targetKey
    var propertyName by DaoPaletteEntries.propertyName
    var value by DaoPaletteEntries.value
    var sortOrder by DaoPaletteEntries.sortOrder
    var uuid by DaoPaletteEntries.uuid

    var target: TargetRef
        get() = TargetRef.of(targetType, targetKey)
        set(value) {
            targetType = value.discriminator
            targetKey = value.key
        }
}
