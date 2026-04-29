package uk.me.cormack.lighting7.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Canonical JSON for cloud sync — every commit is content-addressable and round-trip stable, so
 * incidental key ordering or trailing-whitespace differences would surface as spurious diffs in
 * the synced repo. See `docs/sync-engineering.md` §"Canonical JSON contract".
 *
 * Settings:
 *  - `prettyPrint` + 2-space indent: human-readable diffs in `git diff`.
 *  - `explicitNulls = false`: omit null fields so adding a new optional field is forward-compatible
 *    (existing repos serialize without the field; older readers skip it).
 *  - `encodeDefaults = false`: same logic for default values; together with `explicitNulls = false`
 *    this means a fresh DTO with default state serialises to `{}`.
 *  - `ignoreUnknownKeys = true`: forward-compat — newer installs may add fields older installs
 *    haven't learned to read yet.
 *
 * Why a post-encode `sortKeys` step? `kotlinx.serialization.JsonObject` is a `LinkedHashMap` that
 * preserves insertion order, which depends on @Serializable property declaration order. Sorting
 * after the fact is the only way to guarantee bytes are stable across JVM/library versions and
 * across hand-edited reorderings.
 */
@OptIn(ExperimentalSerializationApi::class)
internal val canonicalJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    explicitNulls = false
    encodeDefaults = false
    ignoreUnknownKeys = true
}

/**
 * Serialize [value] to canonical JSON: keys sorted recursively, nulls/defaults omitted, 2-space
 * indent, trailing newline. The result is byte-stable for the same logical input.
 */
fun <T> canonicalEncode(serializer: KSerializer<T>, value: T): String {
    val element = canonicalJson.encodeToJsonElement(serializer, value)
    return canonicalJson.encodeToString(JsonElement.serializer(), sortKeys(element)) + "\n"
}

/** Round-trip parse via the same JSON instance — used by tests and by the importer. */
fun <T> canonicalDecode(serializer: KSerializer<T>, text: String): T =
    canonicalJson.decodeFromString(serializer, text)

private fun sortKeys(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(
        element.entries.sortedBy { it.key }.associate { (k, v) -> k to sortKeys(v) }
    )
    is JsonArray -> JsonArray(element.map(::sortKeys))
    else -> element
}
