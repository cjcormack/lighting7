package uk.me.cormack.lighting7.models

import org.jetbrains.exposed.v1.dao.IntEntity
import org.jetbrains.exposed.v1.dao.IntEntityClass
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.dao.id.EntityID
import org.jetbrains.exposed.v1.core.dao.id.IntIdTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import java.util.UUID

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
 * speed master. One row per master per project; `master_index` 1 is the global master that the
 * script API's `setBpm`/`tapTempo`, the AI `set_bpm` tool, and every FX instance with no
 * explicit master all resolve to.
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

    /**
     * Effect-library category (`dimmer` / `colour` / `position`) this master is the apply-time
     * default for; null routes nothing. Named `usageCategory` in Kotlin because "usage" already
     * means reference-counting in this codebase (`SpeedMasterUsage`, the delete guard); the
     * column itself is `usage`. Unique per project, enforced by [validateSpeedMasterSettings]
     * rather than a partial index — the check needs a friendly 409 with a code, and
     * `friendlyConstraintMessage` would render a constraint hit as a codeless generic.
     */
    val usageCategory = varchar("usage", 16).nullable()

    /**
     * Time-signature numerator/denominator: both null = manual tempo, both positive = this
     * master follows [followTargetUuid] at `num/den`. The leader *drives this master's clock*
     * (see `SpeedMasterBank`), so the ratio is a rate and a phase relationship, not just a
     * tempo derivation. Master 1 itself is refused a ratio at the write boundary.
     */
    val followNum = integer("follow_num").nullable()
    val followDen = integer("follow_den").nullable()

    /**
     * Which master this one follows, or null for master 1 — null is what every row written
     * before follow targets existed holds, and "the global master" is the right reading of it.
     * Stored as the leader's **uuid**, not its int id or index, for the reason the whole file
     * gives: ids are re-minted on import and indices are editable, uuids survive both.
     *
     * Chains are allowed (M3 → M2 → M1) and cycles are not: [validateSpeedMasterSettings]
     * walks the chain at the write boundary, and `SpeedMasterBank.load` degrades any cycle or
     * dangling target that reaches it anyway (an import, a hand-edited row) to manual. A
     * forced delete of a leader unlinks its followers rather than leaving one dangling.
     */
    val followTargetUuid = javaUUID("follow_target_uuid").nullable()
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
    var usageCategory by DaoSpeedMasters.usageCategory
    var followNum by DaoSpeedMasters.followNum
    var followDen by DaoSpeedMasters.followDen
    var followTargetUuid by DaoSpeedMasters.followTargetUuid
    var uuid by DaoSpeedMasters.uuid

    /** [source] as the enum, defaulting to MANUAL when the stored string is unrecognised. */
    val sourceEnum: SpeedMasterSource
        get() = SpeedMasterSource.entries.firstOrNull { it.name == source } ?: SpeedMasterSource.MANUAL

    /**
     * The follow ratio, or null when this master is manual — see [speedMasterFollowRatioOrNull]
     * for the (single) rule. Every read path (bank snapshot, DTO, export, the PUT's
     * carried-forward values) goes through this.
     */
    val followRatio: Pair<Int, Int>?
        get() = speedMasterFollowRatioOrNull(masterIndex, followNum, followDen)

    /**
     * Who this master follows: its stored target, or master 1 when the row names none — and
     * null when it isn't following at all, so a leader stored on a manual row (an unlink that
     * left the column set, an import) is never mistaken for a live link. Read through this
     * rather than the raw column, the same way [followRatio] guards the pair.
     */
    val followTarget: UUID?
        get() = if (followRatio == null) null else followTargetUuid
}

/**
 * THE rule for whether a stored num/den pair is a live follow ratio: non-null only when BOTH
 * values are set and positive — a half-written or hand-edited row degrades to "manual" rather
 * than dividing by zero — and always null on master 1, which every chain ultimately roots at.
 * One function so the DAO getter and `SpeedMasterBank.followRatioOf` cannot drift
 * (`validateSpeedMasterSettings` enforces the same shape at the write boundary, with errors
 * instead of degradation).
 */
fun speedMasterFollowRatioOrNull(masterIndex: Int, num: Int?, den: Int?): Pair<Int, Int>? {
    if (masterIndex == 1) return null
    if (num == null || den == null) return null
    if (num <= 0 || den <= 0) return null
    return num to den
}

/** How many masters a project starts with. A visible bank of four, per the console research. */
const val DEFAULT_SPEED_MASTER_COUNT = 4

/**
 * The categories a speed master's `usage` may name — the effect library's own `category`
 * vocabulary (busking-view plan D7), so apply-time routing compares like with like and no
 * parallel enum needs keeping in step. `controls` is deliberately excluded (a settings slider
 * has no tempo), and so is `composite` (spans families); effects in either category route to
 * master 1 via a null `speedMasterUuid`. Pinned against the shipped `.fx.kts` files by
 * `SpeedMasterUsageVocabularyTest`.
 */
val SPEED_MASTER_USAGES: Set<String> = setOf("dimmer", "colour", "position")

// The write-boundary half of the speed-master error vocabulary (shared by REST and the WS
// socket). The delete-guard half (CODE_SPEED_MASTER_PROTECTED / _IN_USE) follows the house
// convention and lives in routes/projectSpeedMasters.kt — grep both files for the full set.

/** Error code for a tempo write (typed or tapped) refused because the master follows master 1. */
const val CODE_SPEED_MASTER_FOLLOWER = "SPEED_MASTER_FOLLOWER"

/** Error code for claiming a usage another master in the project already holds (409). */
const val CODE_SPEED_MASTER_USAGE_TAKEN = "SPEED_MASTER_USAGE_TAKEN"

/** Error code for putting a follow ratio on master 1, the root every chain ends at. */
const val CODE_SPEED_MASTER_CANNOT_FOLLOW = "SPEED_MASTER_CANNOT_FOLLOW"

/**
 * Error code for a follow link that would close a loop — following yourself, or following a
 * master that (directly or through its own leader) follows you. Chains are legal; cycles are
 * the one shape that has no tempo.
 */
const val CODE_SPEED_MASTER_FOLLOW_CYCLE = "SPEED_MASTER_FOLLOW_CYCLE"

/** Error code for a follow target uuid that names no master in this project. */
const val CODE_SPEED_MASTER_FOLLOW_TARGET_UNKNOWN = "SPEED_MASTER_FOLLOW_TARGET_UNKNOWN"

/** Error code for a malformed usage string or follow-ratio pair. */
const val CODE_SPEED_MASTER_INVALID = "SPEED_MASTER_INVALID"

/** Error code for a well-formed uuid that names no master (WS tempo writes). */
const val CODE_SPEED_MASTER_UNKNOWN = "SPEED_MASTER_UNKNOWN"

/**
 * Canonicalise a client-supplied usage string: trim, lowercase, `color` → `colour`. Blank (or
 * null) is null — "routes nothing". Validation against [SPEED_MASTER_USAGES] is separate, in
 * [validateSpeedMasterSettings]; this only normalises spelling.
 */
fun normaliseSpeedMasterUsage(raw: String?): String? {
    val trimmed = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    return if (trimmed == "color") "colour" else trimmed
}

/**
 * Rejection from [validateSpeedMasterSettings], split so callers map to 400 vs 409 without
 * parsing strings. [code] is the machine-readable error code a client branches on.
 */
sealed interface SpeedMasterSettingsError {
    val message: String
    val code: String

    /** Malformed settings — a 400. */
    data class Invalid(override val message: String, override val code: String) : SpeedMasterSettingsError

    /** Settings that collide with another master — a 409. */
    data class Conflict(override val message: String, override val code: String) : SpeedMasterSettingsError
}

/**
 * The write boundary for a master's routing/follow settings — one place shared by REST and any
 * future script surface. Call with the *resulting* (post-write) values so create and update
 * share the implementation; [usage] must already be normalised via [normaliseSpeedMasterUsage].
 * [excludeId] is the row being edited (null on create). Must be called inside a transaction —
 * the uniqueness check queries the project's masters.
 *
 * [checkFollowTarget] is the same "only what this request actually sends goes through the
 * write-boundary rules" carve-out the usage check already has: an update that merely carries
 * the stored target forward passes false, because a target that names no master here (an
 * imported row, a hand-edited one) is upstream of this write and would
 * otherwise 400 every later PUT on the row — rename and notes edits included — while the bank
 * has already degraded the link to manual. Create, and any write that actually touches the
 * link, leaves it true.
 */
fun validateSpeedMasterSettings(
    project: DaoProject,
    masterIndex: Int,
    usage: String?,
    followNum: Int?,
    followDen: Int?,
    followTargetUuid: UUID? = null,
    excludeId: Int? = null,
    checkFollowTarget: Boolean = true,
): SpeedMasterSettingsError? {
    if ((followNum == null) != (followDen == null)) {
        return SpeedMasterSettingsError.Invalid(
            "followNum and followDen must be set together (both null unlinks)",
            CODE_SPEED_MASTER_INVALID,
        )
    }
    if ((followNum != null && followNum <= 0) || (followDen != null && followDen <= 0)) {
        return SpeedMasterSettingsError.Invalid(
            "Follow ratio must be a pair of positive integers",
            CODE_SPEED_MASTER_INVALID,
        )
    }
    if (followNum != null && masterIndex == 1) {
        return SpeedMasterSettingsError.Invalid(
            "Master 1 is the global master and cannot follow another master",
            CODE_SPEED_MASTER_CANNOT_FOLLOW,
        )
    }
    if (followNum != null && checkFollowTarget) {
        validateFollowTarget(project, followTargetUuid, excludeId)?.let { return it }
    }
    if (usage != null) {
        if (usage !in SPEED_MASTER_USAGES) {
            return SpeedMasterSettingsError.Invalid(
                "Unknown usage '$usage' — must be one of ${SPEED_MASTER_USAGES.sorted().joinToString(", ")}",
                CODE_SPEED_MASTER_INVALID,
            )
        }
        val holder = DaoSpeedMaster
            .find { (DaoSpeedMasters.project eq project.id) and (DaoSpeedMasters.usageCategory eq usage) }
            .firstOrNull { it.id.value != excludeId }
        if (holder != null) {
            return SpeedMasterSettingsError.Conflict(
                "'$usage' is already routed to ${holder.name}",
                CODE_SPEED_MASTER_USAGE_TAKEN,
            )
        }
    }
    return null
}

/**
 * The follow-target half of [validateSpeedMasterSettings]: the named leader must exist, and
 * the chain above it must not come back to the master being written.
 *
 * A null [targetUuid] alongside a ratio means master 1 — the pre-follow-target spelling, and
 * the reading the column's default deserves. Master 1 can never follow, so a walk that reaches
 * it terminates; the `visited` set is belt-and-braces for a cycle that predates this write
 * (a hand-edited or imported row), which is upstream and not this write's
 * fault — the bank degrades it to manual at load. [excludeId] is the row being edited, and is
 * null on create, where a cycle is impossible because the row does not exist yet.
 *
 * Must be called inside a transaction.
 */
private fun validateFollowTarget(
    project: DaoProject,
    targetUuid: UUID?,
    excludeId: Int?,
): SpeedMasterSettingsError? {
    val masters = DaoSpeedMaster.find { DaoSpeedMasters.project eq project.id }.toList()
    val byUuid = masters.associateBy { it.uuid }
    val master1 = masters.firstOrNull { it.masterIndex == 1 }

    // A *named* target that isn't there resolves to nothing, never to master 1: the elvis
    // shorthand for this reads the same and quietly turns "follows a master that no longer
    // exists" into "follows master 1", which is a link the caller never asked for.
    fun resolve(uuid: UUID?): DaoSpeedMaster? = if (uuid == null) master1 else byUuid[uuid]

    fun leaderOf(row: DaoSpeedMaster): DaoSpeedMaster? =
        if (row.followRatio == null) null else resolve(row.followTargetUuid)

    val target = resolve(targetUuid)
    if (target == null) {
        return SpeedMasterSettingsError.Invalid(
            if (targetUuid == null) {
                "This project has no master 1 for it to follow"
            } else {
                "Follow target names no speed master in this project"
            },
            CODE_SPEED_MASTER_FOLLOW_TARGET_UNKNOWN,
        )
    }
    if (target.id.value == excludeId) {
        return SpeedMasterSettingsError.Invalid(
            "A speed master cannot follow itself",
            CODE_SPEED_MASTER_FOLLOW_CYCLE,
        )
    }

    val visited = mutableSetOf(target.id.value)
    var current: DaoSpeedMaster? = leaderOf(target)
    while (current != null) {
        if (current.id.value == excludeId) {
            return SpeedMasterSettingsError.Invalid(
                "${target.name} already follows this master — a follow chain cannot loop",
                CODE_SPEED_MASTER_FOLLOW_CYCLE,
            )
        }
        if (!visited.add(current.id.value)) break
        current = leaderOf(current)
    }
    return null
}

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
