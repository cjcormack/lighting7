package uk.me.cormack.lighting7.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * Typed reference to either a single fixture or a named fixture group. Replaces the
 * stringly-typed `"fixture"` / `"group"` + `targetKey` pair inside branch logic and
 * event payloads. DTOs still serialise as two separate `targetType` / `targetKey`
 * fields — wire format is unchanged.
 */
sealed class TargetRef {
    abstract val key: String
    abstract val discriminator: String

    data class Fixture(override val key: String) : TargetRef() {
        override val discriminator: String get() = TYPE
        companion object { const val TYPE = "fixture" }
    }

    data class Group(override val key: String) : TargetRef() {
        override val discriminator: String get() = TYPE
        companion object { const val TYPE = "group" }
    }

    companion object {
        /**
         * Parse a `(targetType, targetKey)` pair from a DTO or DB row. Throws on an
         * unknown discriminator — use [ofOrNull] at untrusted-input boundaries
         * (WebSocket payload, LLM-authored JSON) where the client may send garbage.
         */
        fun of(type: String, key: String): TargetRef =
            ofOrNull(type, key) ?: throw IllegalArgumentException("Unknown target type '$type' (key='$key')")

        fun ofOrNull(type: String, key: String): TargetRef? = when (type) {
            Fixture.TYPE -> Fixture(key)
            Group.TYPE -> Group(key)
            else -> null
        }
    }
}

/**
 * What an effect targets: one fixture or group ([Single]), or an ordered [Selection] of them
 * (effects plan D3), spread in that order as a group is.
 *
 * The selection kind is effect-only, which is why it is not a third [TargetRef]: a value row, a
 * layer's targets and a binding hold a [TargetRef] and so cannot hold one. Stored and sent as the
 * same `(targetType, targetKey)` pair — `selection`, with [Selection.key] its canonical JSON — so an
 * effect row needs no new column.
 */
sealed class EffectTargetRef {
    abstract val discriminator: String
    abstract val key: String

    /** What it names, in order: the one target, or the selection's items. */
    abstract val items: List<TargetRef>

    data class Single(val target: TargetRef) : EffectTargetRef() {
        override val discriminator: String get() = target.discriminator
        override val key: String get() = target.key
        override val items: List<TargetRef> get() = listOf(target)
    }

    /** Never empty. Items keep the order given; a head named twice counts once, at its first place. */
    data class Selection(override val items: List<TargetRef>) : EffectTargetRef() {
        init {
            require(items.isNotEmpty()) { "A selection names at least one fixture or group" }
        }

        override val discriminator: String get() = TYPE

        /** `[{"key":…,"type":…},…]`: keys alphabetical, no whitespace, in selection order. */
        override val key: String by lazy {
            buildJsonArray {
                for (item in items) add(JsonObject(linkedMapOf("key" to JsonPrimitive(item.key), "type" to JsonPrimitive(item.discriminator))))
            }.toString()
        }

        companion object {
            const val TYPE = "selection"

            /** The items of a stored or sent [key], or null when it is not a non-empty list of fixtures and groups. */
            fun parseKey(key: String): List<TargetRef>? {
                val array = try {
                    Json.parseToJsonElement(key) as? JsonArray
                } catch (_: Exception) { null } ?: return null
                if (array.isEmpty()) return null
                return array.map { element ->
                    val obj = element as? JsonObject ?: return null
                    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull ?: return null
                    val itemKey = (obj["key"] as? JsonPrimitive)?.contentOrNull ?: return null
                    TargetRef.ofOrNull(type, itemKey) ?: return null
                }
            }
        }
    }

    companion object {
        /** One target as [Single], more as a [Selection]. */
        fun of(targets: List<TargetRef>): EffectTargetRef =
            targets.singleOrNull()?.let { Single(it) } ?: Selection(targets)

        fun of(type: String, key: String): EffectTargetRef =
            ofOrNull(type, key) ?: throw IllegalArgumentException("Unknown effect target '$type' (key='$key')")

        /** A one-item selection is read as its one target, so the two never differ in what they mean. */
        fun ofOrNull(type: String, key: String): EffectTargetRef? =
            if (type == Selection.TYPE) Selection.parseKey(key)?.let { of(it) }
            else TargetRef.ofOrNull(type, key)?.let { Single(it) }

        /**
         * [type] and [key] as the desk stores them — a selection's key in its canonical form, a
         * one-item selection as its target — so a row written by hand pairs with what Record writes.
         * A pair that does not parse is answered as given, for the validator to refuse.
         */
        fun canonical(type: String, key: String): Pair<String, String> =
            ofOrNull(type, key)?.let { it.discriminator to it.key } ?: (type to key)
    }
}
