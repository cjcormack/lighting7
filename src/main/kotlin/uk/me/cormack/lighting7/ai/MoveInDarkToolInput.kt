package uk.me.cormack.lighting7.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.me.cormack.lighting7.models.CueMoveInDarkSettings
import uk.me.cormack.lighting7.models.MoveInDarkDto
import uk.me.cormack.lighting7.models.MoveInDarkSettings
import uk.me.cormack.lighting7.models.parseCueMoveInDark

private val moveInDarkJson = Json { ignoreUnknownKeys = false }

/**
 * A tool's `moveInDark` ([moveInDarkSchema]) laid over [base], or the problem that refuses it.
 * Absent is [base] unchanged.
 */
internal fun parseToolMoveInDark(raw: JsonElement?, base: MoveInDarkSettings): Result<MoveInDarkSettings> {
    if (raw == null || raw is JsonNull) return Result.success(base)
    val dto = runCatching { moveInDarkJson.decodeFromJsonElement(MoveInDarkDto.serializer(), raw) }
        .getOrElse { return Result.failure(IllegalArgumentException("moveInDark: ${it.message}")) }
    return dto.resolve(base).recoverCatching { throw IllegalArgumentException("moveInDark: ${it.message}") }
}

/**
 * A stack's *Move in dark* setting as the tools take it (move-in-dark plan D2): every field optional,
 * an absent one at the default for a new stack (on, Early, 1 s, 2 s, all three families).
 */
internal val moveInDarkSchema = buildJsonObject {
    put("type", "object")
    put(
        "description",
        "Move in dark: when a head goes dark, the desk moves its position, colour and beam to the next cue that lights it, " +
            "so the GO only brings it up. A new stack has it on unless this says otherwise.",
    )
    put("properties", buildJsonObject {
        put("on", buildJsonObject { put("type", "boolean"); put("description", "Default true for a new stack.") })
        put("when", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("EARLY"); add("LATE") })
            put("description", "EARLY (default): move to the first cue ahead that lights the head. LATE: only to the next GO's cue.")
        })
        put("delayMs", buildJsonObject { put("type", "integer"); put("description", "How long a head must be dark first, 0–30000. Default 1000.") })
        put("timeMs", buildJsonObject { put("type", "integer"); put("description", "How long its move takes, 0–30000. Default 2000.") })
        put("families", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "string")
                put("enum", buildJsonArray { add("POSITION"); add("COLOUR"); add("BEAM") })
            })
            put("description", "Which families move. Default all three; intensity never moves.")
        })
    })
}

/** A tool's cue `moveInDark` ([cueMoveInDarkSchema]) laid over [base], or the problem that refuses it. */
internal fun parseToolCueMoveInDark(raw: JsonElement?, base: CueMoveInDarkSettings): Result<CueMoveInDarkSettings> =
    parseCueMoveInDark(raw, base).recoverCatching { throw IllegalArgumentException("moveInDark: ${it.message}") }

/** A cue's own *Move in dark* setting as the tools take it (move-in-dark plan D3); every field optional. */
internal val cueMoveInDarkSchema = buildJsonObject {
    put("type", "object")
    put(
        "description",
        "Move in dark for the heads this cue lights: STACK (default) follows the stack's setting; EARLY or LATE moves them " +
            "in the dark even on a stack that has it off; OFF lets them move in this cue's fade, lit. live keeps chosen heads " +
            "lit as they move — a fly-in — while the rest are pre-set.",
    )
    put("properties", buildJsonObject {
        put("mode", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { add("STACK"); add("EARLY"); add("LATE"); add("OFF") })
        })
        put("delayMs", buildJsonObject {
            put("type", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("integer"), kotlinx.serialization.json.JsonPrimitive("null"))))
            put("description", "How long a head must be dark first, 0–30000; null is the stack's.")
        })
        put("timeMs", buildJsonObject {
            put("type", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive("integer"), kotlinx.serialization.json.JsonPrimitive("null"))))
            put("description", "How long its move takes, 0–30000; null is the stack's.")
        })
        put("live", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject { put("type", "string") })
            put("description", "Fixture or element keys kept live (a fixture's covers its elements).")
        })
    })
}
