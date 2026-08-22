package uk.me.cormack.lighting7.testsupport

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.json.json
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.DaoCues
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoProjects
import uk.me.cormack.lighting7.models.LookEffectSpec
import uk.me.cormack.lighting7.models.TargetRef

/**
 * The **pre-v5 schema**: FX presets, named palettes and cue preset applications, as they existed
 * before the looks-and-layers migration replaced all three.
 *
 * These tables were production models until session 4 deleted them, along with
 * `models/fxPresets.kt` and `models/palettes.kt`. They live here now because exactly one thing still
 * needs to speak the old shape: `StateMigrations.migratePresetsAndPalettesToLooks`, whose whole job
 * is reading a database written by an older desk. A migration you cannot test against the schema it
 * migrates *from* is a migration you cannot test at all — and this one is why:
 * `LooksMigrationTest`'s own doc records the two uuid-handling bugs it caught after they had already
 * reached a real desk database.
 *
 * They are deliberately **not** in `ALL_TABLES`, so a fresh database never has them. The migration
 * guards every read on `sqlite_master` (see `tableExists`), so it correctly no-ops on such a
 * database; a test that wants the legacy shape calls [createLegacyTables] to build it.
 *
 * Copied verbatim from the deleted production models rather than simplified. A simplified fixture
 * would test the migration against a schema no desk ever had, which is the one thing this must not
 * do — the column *types* are the point. `uuid` in particular is `javaUUID`, which Exposed stores as
 * a 16-byte BLOB; reading it as text is precisely the bug that shipped.
 */
object LegacySchema {
    /** Create the pre-v5 tables in the current transaction's database. */
    fun createLegacyTables() {
        SchemaUtils.create(
            DaoPalettes,
            DaoPaletteEntries,
            DaoFxPresets,
            DaoFxPresetPropertyAssignments,
            DaoCuePresetApplications,
        )
    }

    /** Drop them again, for a test that needs a post-migration database in the same class. */
    fun dropLegacyTables() {
        SchemaUtils.drop(
            DaoCuePresetApplications,
            DaoFxPresetPropertyAssignments,
            DaoFxPresets,
            DaoPaletteEntries,
            DaoPalettes,
        )
        TransactionManager.current().commit()
    }
}

// ─── Palettes ───────────────────────────────────────────────────────────

object DaoPalettes : IntIdTable("palettes") {
    val project = reference("project_id", DaoProjects)
    val name = varchar("name", 255)

    /** `PropertyMaskGroup` name — `INTENSITY` / `POSITION` / `COLOUR` / `BEAM`. */
    val type = varchar("type", 20)
    val notes = text("notes").nullable()
    val sortOrder = integer("sort_order").default(0)
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        // Scoped by type as well as project: "Warm" was a reasonable name for both a colour and a
        // position palette, and the per-type banks were separate namespaces.
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
}

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

// ─── FX presets ─────────────────────────────────────────────────────────

object DaoFxPresets : IntIdTable("fx_presets") {
    val name = varchar("name", 255)
    val description = varchar("description", 1000).nullable()
    val project = reference("project_id", DaoProjects)
    val fixtureType = varchar("fixture_type", 255)

    /**
     * Typed as [LookEffectSpec] because that is the same class, renamed. It was
     * `FxPresetEffectDto` and lived beside this table; by the time presets retired it had become the
     * shared effect wire shape, so it moved to `models/looks.kt` instead of being deleted. The JSON
     * an old database holds in this column parses identically.
     */
    val effects = json<List<LookEffectSpec>>("effects", Json)
    val palette = json<List<String>>("palette", Json).default(emptyList())
    val uuid = javaUUID("uuid").autoGenerate()

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
    var uuid by DaoFxPresets.uuid
    val propertyAssignments by DaoFxPresetPropertyAssignment referrersOn DaoFxPresetPropertyAssignments.preset
}

object DaoFxPresetPropertyAssignments : IntIdTable("fx_preset_property_assignments") {
    val preset = reference("preset_id", DaoFxPresets)
    val propertyName = varchar("property_name", 255)
    val value = text("value")
    val fadeDurationMs = long("fade_duration_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
    val elementKey = varchar("element_key", 255).nullable()
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoFxPresetPropertyAssignment(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoFxPresetPropertyAssignment>(DaoFxPresetPropertyAssignments)

    var preset by DaoFxPreset referencedOn DaoFxPresetPropertyAssignments.preset
    var propertyName by DaoFxPresetPropertyAssignments.propertyName
    var value by DaoFxPresetPropertyAssignments.value
    var fadeDurationMs by DaoFxPresetPropertyAssignments.fadeDurationMs
    var sortOrder by DaoFxPresetPropertyAssignments.sortOrder
    var elementKey by DaoFxPresetPropertyAssignments.elementKey
    var uuid by DaoFxPresetPropertyAssignments.uuid
}

// ─── Cue preset applications ────────────────────────────────────────────

object DaoCuePresetApplications : IntIdTable("cue_preset_applications") {
    val cue = reference("cue_id", DaoCues)
    val preset = reference("preset_id", DaoFxPresets)
    val targets = json<List<CueTargetDto>>("targets", Json)
    val delayMs = long("delay_ms").nullable()
    val intervalMs = long("interval_ms").nullable()
    val randomWindowMs = long("random_window_ms").nullable()
    val sortOrder = integer("sort_order").default(0)
    val speedMasterUuid = javaUUID("speed_master_uuid").nullable()
    val rateSpeedMasterUuid = javaUUID("rate_speed_master_uuid").nullable()
    val uuid = javaUUID("uuid").autoGenerate()
}

class DaoCuePresetApplication(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCuePresetApplication>(DaoCuePresetApplications)

    var cue by uk.me.cormack.lighting7.models.DaoCue referencedOn DaoCuePresetApplications.cue
    var preset by DaoFxPreset referencedOn DaoCuePresetApplications.preset
    var targets by DaoCuePresetApplications.targets
    var delayMs by DaoCuePresetApplications.delayMs
    var intervalMs by DaoCuePresetApplications.intervalMs
    var randomWindowMs by DaoCuePresetApplications.randomWindowMs
    var sortOrder by DaoCuePresetApplications.sortOrder
    var speedMasterUuid by DaoCuePresetApplications.speedMasterUuid
    var rateSpeedMasterUuid by DaoCuePresetApplications.rateSpeedMasterUuid
    var uuid by DaoCuePresetApplications.uuid
}
