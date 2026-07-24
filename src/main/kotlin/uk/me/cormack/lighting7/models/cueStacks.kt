package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.dao.IntEntity
import org.jetbrains.exposed.dao.IntEntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.dao.id.IntIdTable
import org.jetbrains.exposed.sql.json.json

// ─── Cue stack types ──────────────────────────────────────────────────

/**
 * A row in the project's ordered stack list. `STACK` is a runnable cue stack; `SEPARATOR` is a
 * label-only divider between stacks (it has no cues and cannot be activated/advanced). Separators
 * replace the old show-level MARKER entries now that the show is just the project's ordered stacks.
 */
enum class CueStackType { STACK, SEPARATOR }

// ─── Cue Stacks table ──────────────────────────────────────────────────

object DaoCueStacks : IntIdTable("cue_stacks") {
    val name = varchar("name", 255)
    val project = reference("project_id", DaoProjects)
    val palette = json<List<String>>("palette", Json)
    val loop = bool("loop").default(false)
    /** Position within the project's ordered stack list (the show order). */
    val sortOrder = integer("sort_order").default(0)
    /** [CueStackType] as a string — `STACK` (runnable) or `SEPARATOR` (label-only divider). */
    val type = varchar("type", 20).default("STACK")
    /** Display text for a `SEPARATOR` row; null/unused for a `STACK`. */
    val label = varchar("label", 255).nullable()
    val uuid = uuid("uuid").autoGenerate()

    // The (project, name) unique index is created as a *partial* index (WHERE type='STACK') in
    // State.kt so that multiple separators can share a name — Exposed's uniqueIndex() can't express
    // a partial predicate.
}

class DaoCueStack(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueStack>(DaoCueStacks)

    var name by DaoCueStacks.name
    var project by DaoProject referencedOn DaoCueStacks.project
    var palette by DaoCueStacks.palette
    var loop by DaoCueStacks.loop
    var sortOrder by DaoCueStacks.sortOrder
    var type by DaoCueStacks.type
    var label by DaoCueStacks.label
    var uuid by DaoCueStacks.uuid
    val cues by DaoCue referrersOn DaoCues.cueStack
}
