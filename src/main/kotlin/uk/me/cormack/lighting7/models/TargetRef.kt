package uk.me.cormack.lighting7.models

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
