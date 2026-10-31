package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.duration

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
    val loop = bool("loop").default(false)
    /** Position within the project's ordered stack list (the show order). */
    val sortOrder = integer("sort_order").default(0)
    /** [CueStackType] as a string — `STACK` (runnable) or `SEPARATOR` (label-only divider). */
    val type = varchar("type", 20).default("STACK")
    /** Display text for a `SEPARATOR` row; null/unused for a `STACK`. */
    val label = varchar("label", 255).nullable()
    val uuid = javaUUID("uuid").autoGenerate()

    /** *Move in dark* (move-in-dark plan D2), read through [moveInDarkSettings]. Off by default. */
    val moveInDark = bool("move_in_dark").default(false)
    /** [MoveInDarkWhen] as a string. */
    val moveInDarkWhen = varchar("move_in_dark_when", 10).default(MoveInDarkWhen.EARLY.name)
    val moveInDarkDelay = duration("move_in_dark_delay").default(MoveInDarkSettings.DEFAULT_DELAY)
    val moveInDarkTime = duration("move_in_dark_time").default(MoveInDarkSettings.DEFAULT_TIME)
    /** The families that move, sorted and comma separated. */
    val moveInDarkFamilies = varchar("move_in_dark_families", 40)
        .default(MoveInDarkSettings.storedFamilies(MoveInDarkSettings.MOVABLE))

    // The (project, name) unique index is created as a *partial* index (WHERE type='STACK') in
    // State.kt so that multiple separators can share a name — Exposed's uniqueIndex() can't express
    // a partial predicate.
}

class DaoCueStack(id: EntityID<Int>) : IntEntity(id) {
    companion object : IntEntityClass<DaoCueStack>(DaoCueStacks)

    var name by DaoCueStacks.name
    var project by DaoProject referencedOn DaoCueStacks.project
    var loop by DaoCueStacks.loop
    var sortOrder by DaoCueStacks.sortOrder
    var type by DaoCueStacks.type
    var label by DaoCueStacks.label
    var uuid by DaoCueStacks.uuid
    internal var moveInDark by DaoCueStacks.moveInDark
    internal var moveInDarkWhen by DaoCueStacks.moveInDarkWhen
    internal var moveInDarkDelay by DaoCueStacks.moveInDarkDelay
    internal var moveInDarkTime by DaoCueStacks.moveInDarkTime
    internal var moveInDarkFamilies by DaoCueStacks.moveInDarkFamilies
}
