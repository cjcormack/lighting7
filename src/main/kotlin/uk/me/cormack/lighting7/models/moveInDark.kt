package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.fx.AttributeFamily
import java.time.Duration

/**
 * A stack's *Move in dark* setting (move-in-dark plan D2): whether dark heads are moved ahead of the
 * cue that lights them, *when* (the first cue ahead that lights the head, or only the next GO's), how
 * long a head must be dark first, how long its move takes and which families move.
 *
 * Stored on `cue_stacks` and read through [DaoCueStack.moveInDarkSettings]. The column defaults are
 * [OFF], which is what every stack that existed before the setting reads as; every door that creates
 * a stack writes [NEW_STACK] unless told otherwise.
 */
data class MoveInDarkSettings(
    val on: Boolean,
    val whenMode: MoveInDarkWhen,
    val delay: Duration,
    val time: Duration,
    val families: Set<AttributeFamily>,
) {
    fun toDto(): MoveInDarkDto = MoveInDarkDto(
        on = on,
        `when` = whenMode.name,
        delayMs = delay.toMillis(),
        timeMs = time.toMillis(),
        families = families.sortedBy { it.ordinal }.map { it.name },
    )

    companion object {
        val DEFAULT_DELAY: Duration = Duration.ofSeconds(1)
        val DEFAULT_TIME: Duration = Duration.ofSeconds(2)

        /** The families a move may name, and the default set. Intensity never moves (D5). */
        val MOVABLE: Set<AttributeFamily> = setOf(AttributeFamily.POSITION, AttributeFamily.COLOUR, AttributeFamily.BEAM)

        /** The upper bound of a delay or a move time. */
        val MAX: Duration = Duration.ofSeconds(30)

        val OFF = MoveInDarkSettings(false, MoveInDarkWhen.EARLY, DEFAULT_DELAY, DEFAULT_TIME, MOVABLE)
        val NEW_STACK = OFF.copy(on = true)

        /** The stored comma list's set, unknown names dropped. */
        fun parseFamilies(stored: String): Set<AttributeFamily> =
            stored.split(',').mapNotNull { name -> MOVABLE.firstOrNull { it.name == name.trim() } }.toSet()

        /** [families] as stored: sorted, comma separated. */
        fun storedFamilies(families: Set<AttributeFamily>): String =
            families.sortedBy { it.name }.joinToString(",") { it.name }
    }
}

/** *Early*: the first entry ahead that lights the head. *Late*: the next GO's only (D5). */
enum class MoveInDarkWhen { EARLY, LATE }

/**
 * The wire's `moveInDark`, on `NewCueStack` and `CueStackDetails`, MCP's `create_cue_stack` and
 * `build_cue_stack`. Every field is optional on a write: an absent one keeps what [base] holds —
 * the stored setting on a `PUT`, [MoveInDarkSettings.NEW_STACK] on a create.
 */
@Serializable
data class MoveInDarkDto(
    val on: Boolean? = null,
    val `when`: String? = null,
    val delayMs: Long? = null,
    val timeMs: Long? = null,
    val families: List<String>? = null,
) {
    /**
     * This write laid over [base], or every problem at once (400 `MOVE_IN_DARK_INVALID`): a delay or
     * a move time outside 0–30 s, an unknown `when`, an empty or unknown family.
     */
    fun resolve(base: MoveInDarkSettings): Result<MoveInDarkSettings> {
        val problems = mutableListOf<String>()
        val whenMode = `when`?.let { raw ->
            MoveInDarkWhen.entries.firstOrNull { it.name == raw.uppercase() }
                ?: null.also { problems += "when must be EARLY or LATE, not '$raw'" }
        } ?: base.whenMode
        fun duration(name: String, ms: Long?, fallback: Duration): Duration {
            if (ms == null) return fallback
            if (ms < 0 || ms > MoveInDarkSettings.MAX.toMillis()) {
                problems += "$name must be from 0 to ${MoveInDarkSettings.MAX.toMillis()} ms, not $ms"
                return fallback
            }
            return Duration.ofMillis(ms)
        }
        val delay = duration("delayMs", delayMs, base.delay)
        val time = duration("timeMs", timeMs, base.time)
        val families = families?.let { names ->
            if (names.isEmpty()) problems += "families must name at least one of POSITION, COLOUR, BEAM"
            val unknown = names.filter { name -> MoveInDarkSettings.MOVABLE.none { it.name == name.uppercase() } }
            if (unknown.isNotEmpty()) problems += "families may be POSITION, COLOUR or BEAM, not ${unknown.joinToString()}"
            names.mapNotNull { name -> MoveInDarkSettings.MOVABLE.firstOrNull { it.name == name.uppercase() } }.toSet()
        } ?: base.families
        if (problems.isNotEmpty()) return Result.failure(MoveInDarkInvalid(problems))
        return Result.success(MoveInDarkSettings(on ?: base.on, whenMode, delay, time, families))
    }
}

/** A refused [MoveInDarkDto], naming every problem. */
class MoveInDarkInvalid(val problems: List<String>) : IllegalArgumentException(problems.joinToString("; "))

const val CODE_MOVE_IN_DARK_INVALID = "MOVE_IN_DARK_INVALID"

/** [this] stack's stored setting. */
var DaoCueStack.moveInDarkSettings: MoveInDarkSettings
    get() = MoveInDarkSettings(
        on = moveInDark,
        whenMode = MoveInDarkWhen.entries.firstOrNull { it.name == moveInDarkWhen } ?: MoveInDarkWhen.EARLY,
        delay = moveInDarkDelay,
        time = moveInDarkTime,
        families = MoveInDarkSettings.parseFamilies(moveInDarkFamilies),
    )
    set(value) {
        moveInDark = value.on
        moveInDarkWhen = value.whenMode.name
        moveInDarkDelay = value.delay
        moveInDarkTime = value.time
        moveInDarkFamilies = MoveInDarkSettings.storedFamilies(value.families)
    }
