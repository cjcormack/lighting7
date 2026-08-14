package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID

/**
 * How a speed master's current BPM was last set. Display-only — nothing branches on it; the
 * masters strip shows "TAP" so an operator knows a tempo was tapped rather than typed.
 */
enum class SpeedMasterSource {
    MANUAL,
    TAP,
}

/**
 * A named tempo bus that FX instances *subscribe to* rather than owning speeds — the console
 * speed master. One row per master per project; `master_index` 1 is the global master that
 * every pre-existing entry point (`setFxBpm`, `tapTempo`, `fxState.bpm`, `beatSync`) maps to,
 * and every FX instance with no explicit master resolves to.
 *
 * The stored [bpm] is the master's *starting* tempo: the live value is owned by the runtime
 * bank and written through on change, so an export carries whatever the master was last set
 * to, and an import starts the clock there. The table is portable show content (like
 * palettes, unlike the scaler states) — a preset or cue effect references a master by
 * `speedMasterUuid`, and that reference must survive clone and import, which only works if
 * the masters travel with the show.
 *
 * References are stored as the **uuid**, never the int id: int primary keys are re-minted on
 * import, and [uk.me.cormack.lighting7.sync.ExportUuidRemapper] rewrites uuid-valued fields
 * across the whole export text — including inside the `fx_presets.effects` JSON blob — so a
 * uuid reference survives where an int would dangle. See `docs/sync-engineering.md` and the
 * same rationale on [DaoPalettes].
 */
object DaoSpeedMasters : IntIdTable("speed_masters") {
    val project = reference("project_id", DaoProjects)

    /** 1-based display index; 1 is the protected global master. Portable and stable across import. */
    val masterIndex = integer("master_index")
    val name = varchar("name", 255)

    /** Starting tempo; the live value is the runtime bank's and is written through on change. */
    val bpm = double("bpm").default(120.0)

    /**
     * [SpeedMasterSource] name — `MANUAL` / `TAP`. Named `bpmSource` in Kotlin because a
     * plain `source` collides with `ColumnSet.source`; the column itself is `source`.
     */
    val bpmSource = varchar("source", 10).default(SpeedMasterSource.MANUAL.name)
    val notes = text("notes").nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    init {
        uniqueIndex(project, masterIndex)
        uniqueIndex(project, name)
    }
}

class DaoSpeedMaster(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoSpeedMaster>(DaoSpeedMasters)

    var project by DaoProject referencedOn DaoSpeedMasters.project
    var masterIndex by DaoSpeedMasters.masterIndex
    var name by DaoSpeedMasters.name
    var bpm by DaoSpeedMasters.bpm
    var source by DaoSpeedMasters.bpmSource
    var notes by DaoSpeedMasters.notes
    var uuid by DaoSpeedMasters.uuid

    /** [source] as the enum, defaulting to MANUAL when the stored string is unrecognised. */
    val sourceEnum: SpeedMasterSource
        get() = SpeedMasterSource.entries.firstOrNull { it.name == source } ?: SpeedMasterSource.MANUAL
}

/** How many masters a project starts with. A visible bank of four, per the console research. */
const val DEFAULT_SPEED_MASTER_COUNT = 4

/**
 * Seed the default bank if [project] has no masters yet. Runs at project create and lazily at
 * `Show` start — the lazy path is what covers projects that predate speed masters and freshly
 * imported ones whose export carried no masters. Idempotent; must be called inside a
 * transaction. Returns the project's masters, seeded or pre-existing, ordered by index.
 */
fun ensureDefaultSpeedMasters(project: DaoProject): List<DaoSpeedMaster> {
    val existing = DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }
        .orderBy(DaoSpeedMasters.masterIndex to SortOrder.ASC)
        .toList()
    if (existing.isNotEmpty()) return existing
    return (1..DEFAULT_SPEED_MASTER_COUNT).map { index ->
        DaoSpeedMaster.new {
            this.project = project
            masterIndex = index
            name = "Master $index"
        }
    }
}
