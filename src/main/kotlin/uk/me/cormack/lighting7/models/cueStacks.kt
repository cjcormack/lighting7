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

    /** Off makes it a *busk stack* (busk-stack plan D1): the show's playhead never reaches it. */
    val inRunningOrder = bool("in_running_order").default(true)
    /** *After the last cue: Release* (D3): a GO or follow past the last cue entry stops the stack. */
    val releaseAtEnd = bool("release_at_end").default(false)
    /** How long a deliberate stop fades the stack's light (D6); zero snaps. */
    val releaseTime = duration("release_time").default(CueStackRelease.DEFAULT_TIME)
    /** *Over the show* (D7), [CueStackOverShow] as a string: a busk stack's cues add to the show or replace it. */
    val overShow = varchar("over_show", 10).default(CueStackOverShow.ADDS.name)

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
    var inRunningOrder by DaoCueStacks.inRunningOrder
    var releaseAtEnd by DaoCueStacks.releaseAtEnd
    var releaseTime by DaoCueStacks.releaseTime
    internal var overShowStored by DaoCueStacks.overShow

    /** [overShowStored] read, an unknown value as [CueStackOverShow.ADDS]. */
    var overShow: CueStackOverShow
        get() = CueStackOverShow.parse(overShowStored) ?: CueStackOverShow.ADDS
        set(value) { overShowStored = value.name }

    /** Whether this stack's cues replace what is under them (D7): a busk stack set to Replaces. */
    val replacesShow: Boolean get() = !inRunningOrder && overShow == CueStackOverShow.REPLACES
}

/**
 * *Over the show* (busk-stack plan D7): a busk stack's cues compose with the show's by the usual rules
 * ([ADDS]), or become the floor of every key they set ([REPLACES]), what is under them blended out.
 */
enum class CueStackOverShow {
    ADDS, REPLACES;

    companion object {
        fun parse(value: String?): CueStackOverShow? = entries.firstOrNull { it.name == value }
    }
}

/** A stack's *Release time* (busk-stack plan D6): its default and the range a write may set. */
object CueStackRelease {
    val DEFAULT_TIME: java.time.Duration = java.time.Duration.ofSeconds(2)
    const val MAX_TIME_MS = 60_000L
}

const val CODE_CUE_STACK_INVALID = "CUE_STACK_INVALID"
const val CODE_CUE_STACK_IS_PLAYHEAD = "CUE_STACK_IS_PLAYHEAD"
const val CODE_CUE_STACK_NOT_IN_SHOW = "CUE_STACK_NOT_IN_SHOW"

/**
 * A stack's run settings (busk-stack plan §3 "Stack settings"): what follows its last cue and how a
 * stop fades. [resolve] is the one rule the stack routes and the MCP tools judge a write by.
 */
data class CueStackRunSettings(
    val loop: Boolean = false,
    val inRunningOrder: Boolean = true,
    val releaseAtEnd: Boolean = false,
    val releaseTimeMs: Long = CueStackRelease.DEFAULT_TIME.toMillis(),
    val overShow: CueStackOverShow = CueStackOverShow.ADDS,
) {
    companion object {
        /**
         * The request laid over [stored] (null on a create, which starts from the column defaults), or
         * every problem with the result: Loop beside Release, a release time outside 0–60 s, Replaces on a
         * stack in the running order. A write that makes a busk stack and names no [releaseAtEnd] sets it
         * unless the result loops (D3); one that puts a stack back into the running order and names no
         * [overShow] sets Adds.
         */
        fun resolve(
            stored: CueStackRunSettings?,
            loop: Boolean,
            inRunningOrder: Boolean?,
            releaseAtEnd: Boolean?,
            releaseTimeMs: Long?,
            overShow: String? = null,
        ): Result<CueStackRunSettings> {
            val base = stored ?: CueStackRunSettings()
            val inOrder = inRunningOrder ?: base.inRunningOrder
            val leaves = !inOrder && base.inRunningOrder
            val rejoins = inOrder && !base.inRunningOrder
            val parsedOverShow = overShow?.let { CueStackOverShow.parse(it) }
            val merged = CueStackRunSettings(
                loop = loop,
                inRunningOrder = inOrder,
                releaseAtEnd = releaseAtEnd ?: if (leaves) !loop else base.releaseAtEnd,
                releaseTimeMs = releaseTimeMs ?: base.releaseTimeMs,
                overShow = parsedOverShow ?: if (rejoins) CueStackOverShow.ADDS else base.overShow,
            )
            val problems = buildList {
                if (merged.loop && merged.releaseAtEnd) add("loop and releaseAtEnd cannot both be on")
                if (merged.releaseTimeMs !in 0..CueStackRelease.MAX_TIME_MS) add("releaseTimeMs must be 0–${CueStackRelease.MAX_TIME_MS}")
                if (overShow != null && parsedOverShow == null) add("overShow must be ADDS or REPLACES")
                if (merged.inRunningOrder && merged.overShow == CueStackOverShow.REPLACES) add("overShow REPLACES is for a busk stack: turn inRunningOrder off")
            }
            return if (problems.isEmpty()) Result.success(merged) else Result.failure(IllegalArgumentException(problems.joinToString("; ")))
        }
    }
}

/** The stack's run settings as stored. */
var DaoCueStack.runSettings: CueStackRunSettings
    get() = CueStackRunSettings(loop, inRunningOrder, releaseAtEnd, releaseTime.toMillis(), overShow)
    set(value) {
        loop = value.loop
        inRunningOrder = value.inRunningOrder
        releaseAtEnd = value.releaseAtEnd
        releaseTime = java.time.Duration.ofMillis(value.releaseTimeMs)
        overShow = value.overShow
    }
