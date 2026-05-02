package uk.me.cormack.lighting7.routes

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

// Tri-state JSON-element extractors for partial-update PUTs: a missing key
// and an explicit JSON null both yield Kotlin null; callers that need to
// distinguish "absent" from "null" guard with `"key" in body` first.

internal fun JsonElement?.nullableString(): String? =
    if (this == null || this is JsonNull) null else jsonPrimitive.content

internal fun JsonElement?.nullableInt(): Int? =
    if (this == null || this is JsonNull) null else jsonPrimitive.int

internal fun JsonElement?.nullableLong(): Long? =
    if (this == null || this is JsonNull) null else jsonPrimitive.long

internal fun JsonElement?.nullableDouble(): Double? =
    if (this == null || this is JsonNull) null else jsonPrimitive.double

/**
 * Range-check a stage coordinate. Coordinates are FOH-relative metres (see
 * `docs/fixtures-engineering.md`); ±500 m is generous for any real venue,
 * tight enough to catch unit mistakes (mm, pixels). Returns the first error
 * message or null. The `isFinite()` guard rejects NaN explicitly because
 * `NaN < min` and `NaN > max` are both false — a NaN would otherwise pass
 * the range comparison.
 */
internal fun checkStageCoord(name: String, v: Double?): String? {
    if (v == null) return null
    if (!v.isFinite()) return "$name must be a finite number"
    if (v < -500.0 || v > 500.0) return "$name must be between -500.0 and 500.0 metres"
    return null
}

/** Range-check an angle field (degrees). Same NaN-guard rationale as [checkStageCoord]. */
internal fun checkAngle(name: String, v: Double?, min: Double, max: Double): String? {
    if (v == null) return null
    if (!v.isFinite()) return "$name must be a finite number"
    if (v < min || v > max) return "$name must be between $min and $max degrees"
    return null
}
