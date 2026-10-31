package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
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

/**
 * A cue's own *Move in dark* choice (move-in-dark plan D3), on the cue that lights the head: *Stack*
 * follows the stack's setting; *Early* and *Late* apply even on a stack that is off; *Off* leaves its
 * heads to move in the GO's fade, lit.
 */
enum class CueMoveInDarkMode { STACK, EARLY, LATE, OFF }

/**
 * A cue's setting: its [mode], a delay and a move time of its own (null is the stack's), and the
 * heads it keeps [live] — fixture or element keys, a fixture's covering its elements — which fly in
 * lit however the rest are moved. Stored on `cues`, read through [DaoCue.moveInDarkSettings].
 */
data class CueMoveInDarkSettings(
    val mode: CueMoveInDarkMode = CueMoveInDarkMode.STACK,
    val delay: Duration? = null,
    val time: Duration? = null,
    val live: List<String> = emptyList(),
) {
    /**
     * What applies to a head this cue lights on a stack set to [stack] — its *when*, delay and move
     * time, the stack's families — or null when nothing moves in the dark for it: the cue is *Off*,
     * or follows a stack that is off.
     */
    fun effective(stack: MoveInDarkSettings): MoveInDarkSettings? {
        val whenMode = when (mode) {
            CueMoveInDarkMode.STACK -> if (stack.on) stack.whenMode else return null
            CueMoveInDarkMode.EARLY -> MoveInDarkWhen.EARLY
            CueMoveInDarkMode.LATE -> MoveInDarkWhen.LATE
            CueMoveInDarkMode.OFF -> return null
        }
        return stack.copy(on = true, whenMode = whenMode, delay = delay ?: stack.delay, time = time ?: stack.time)
    }

    /** Whether the head [headKey] (an element of [parentKey], when it is one) is kept live. */
    fun keepsLive(headKey: String, parentKey: String?): Boolean = headKey in live || (parentKey != null && parentKey in live)

    /** The read, with [unknownLive] the live keys the rig lacks — kept, and reported as a row's dead target is. */
    fun toDto(unknownLive: List<String> = emptyList()): CueMoveInDarkDto = CueMoveInDarkDto(
        mode = mode.name,
        delayMs = delay?.toMillis(),
        timeMs = time?.toMillis(),
        live = live,
        unknownLive = unknownLive,
    )

    companion object {
        val DEFAULT = CueMoveInDarkSettings()

        /** The most heads one cue may keep live. */
        const val MAX_LIVE = 512

        /** The modes that move a head on a stack that is off. */
        val OWN_TIMING_MODES = setOf(CueMoveInDarkMode.EARLY, CueMoveInDarkMode.LATE)
    }
}

/**
 * A cue's `moveInDark` as `CueDetails` carries it. [unknownLive] is read only: the live keys this
 * desk's rig lacks.
 */
@Serializable
data class CueMoveInDarkDto(
    val mode: String = CueMoveInDarkMode.STACK.name,
    val delayMs: Long? = null,
    val timeMs: Long? = null,
    val live: List<String> = emptyList(),
    val unknownLive: List<String> = emptyList(),
)

/**
 * A cue write's `moveInDark` laid over [base], or every problem at once (400 `MOVE_IN_DARK_INVALID`):
 * an object of `mode` (`STACK | EARLY | LATE | OFF`), `delayMs` and `timeMs` (0–30 s, `null` the
 * stack's) and `live` (head keys). A field absent keeps [base]'s; `null` for the whole is the default
 * setting. `unknownLive`, which a read carries, is ignored, so a read can be written back.
 */
fun parseCueMoveInDark(raw: JsonElement?, base: CueMoveInDarkSettings): Result<CueMoveInDarkSettings> {
    if (raw == null) return Result.success(base)
    if (raw is JsonNull) return Result.success(CueMoveInDarkSettings.DEFAULT)
    val body = raw as? JsonObject
        ?: return Result.failure(MoveInDarkInvalid(listOf("moveInDark must be an object of mode, delayMs, timeMs and live")))
    val problems = mutableListOf<String>()
    val unknown = body.keys - CUE_MOVE_IN_DARK_FIELDS
    if (unknown.isNotEmpty()) problems += "moveInDark has no field ${unknown.sorted().joinToString()}"
    val mode = when (val value = body["mode"]) {
        null -> base.mode
        else -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { raw ->
            CueMoveInDarkMode.entries.firstOrNull { it.name == raw.uppercase() }
        } ?: base.mode.also { problems += "mode must be STACK, EARLY, LATE or OFF, not $value" }
    }
    fun duration(name: String, fallback: Duration?): Duration? {
        if (name !in body) return fallback
        val value = body[name]
        if (value is JsonNull) return null
        val ms = (value as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
        if (ms == null || ms < 0 || ms > MoveInDarkSettings.MAX.toMillis()) {
            problems += "$name must be from 0 to ${MoveInDarkSettings.MAX.toMillis()} ms, or null for the stack's, not $value"
            return fallback
        }
        return Duration.ofMillis(ms)
    }
    val delay = duration("delayMs", base.delay)
    val time = duration("timeMs", base.time)
    val live = when (val value = body["live"]) {
        null -> base.live
        is JsonNull -> emptyList()
        is JsonArray -> {
            val keys = value.mapNotNull { item ->
                val key = (item as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                if (key.isNullOrEmpty()) null.also { problems += "live must name heads by key, not $item" } else key
            }.distinct()
            if (keys.size > CueMoveInDarkSettings.MAX_LIVE) problems += "live may keep at most ${CueMoveInDarkSettings.MAX_LIVE} heads, not ${keys.size}"
            keys
        }
        else -> base.live.also { problems += "live must be an array of head keys" }
    }
    if (problems.isNotEmpty()) return Result.failure(MoveInDarkInvalid(problems))
    return Result.success(CueMoveInDarkSettings(mode, delay, time, live))
}

private val CUE_MOVE_IN_DARK_FIELDS = setOf("mode", "delayMs", "timeMs", "live", "unknownLive")

/** [this] cue's stored setting. */
var DaoCue.moveInDarkSettings: CueMoveInDarkSettings
    get() = CueMoveInDarkSettings(
        mode = CueMoveInDarkMode.entries.firstOrNull { it.name == moveInDarkMode } ?: CueMoveInDarkMode.STACK,
        delay = moveInDarkDelay,
        time = moveInDarkTime,
        live = moveInDarkLive,
    )
    set(value) {
        moveInDarkMode = value.mode.name
        moveInDarkDelay = value.delay
        moveInDarkTime = value.time
        moveInDarkLive = value.live
    }
