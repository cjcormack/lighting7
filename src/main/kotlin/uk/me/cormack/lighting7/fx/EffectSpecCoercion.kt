package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.group.DistributionStrategy

/**
 * The one place an effect spec's enum-valued fields are read out of a string.
 *
 * `blendMode` / `distribution` / `elementMode` / `elementFilter` arrive as strings from three
 * directions — a REST body, a stored `look_effects` / `cue_effects` row, a WS frame — and until
 * this object existed each direction had its own parse. Two of the four enums threw
 * (`BlendMode.valueOf`, `ElementMode.valueOf`, both case-*sensitive*) while the other two
 * silently defaulted (`DistributionStrategy.fromName`, `ElementFilter.fromName`), so one bad
 * string produced a 400 or a quiet LINEAR depending purely on which field it landed in — and
 * `"override"` in lower case was a 400 while `"linear"` was fine.
 *
 * There are exactly two legitimate policies, and they are named rather than implied:
 *
 * - **[Strict]** — a request is stating a value, so an unrecognised one is the caller's bug and
 *   earns an [IllegalArgumentException] naming the valid set.
 * - **[Lenient]** — a stored row is *already* on the desk, and a cue must still fire. An
 *   unrecognised value logs at warn and falls back to the field's default.
 *
 * Both accept any casing and tolerate surrounding whitespace, which the strict path did not
 * before. That is deliberately more permissive: no client was relying on `"Override"` being
 * rejected.
 */
object EffectSpecCoercion {
    private val logger = LoggerFactory.getLogger(EffectSpecCoercion::class.java)

    /** Parsers that answer null for an unrecognised name — the single mapping both policies share. */
    object Names {
        fun blendMode(raw: String): BlendMode? = enumByName(BlendMode.entries, raw)

        fun elementMode(raw: String): ElementMode? = enumByName(ElementMode.entries, raw)

        fun elementFilter(raw: String): ElementFilter? = ElementFilter.byName(raw)

        fun distribution(raw: String): DistributionStrategy? = DistributionStrategy.byName(raw)

        private fun <E : Enum<E>> enumByName(entries: List<E>, raw: String): E? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }

    /**
     * Rejects an unrecognised value. For request bodies, where the caller chose the string.
     *
     * **A new call site needs its own `catch`.** `plugins/ErrorHandling.kt` registers no
     * `IllegalArgumentException` clause, so this reaches the `Throwable` catch-all and answers
     * **500** with an error-level stack trace. It reads as a 400 at the three call sites that
     * exist because each sits inside a handler that already catches `Exception` and responds
     * `BadRequest` — not because the type maps to one. Sweep item F6/F7 owns the envelope.
     */
    object Strict {
        fun blendMode(raw: String): BlendMode =
            Names.blendMode(raw) ?: fail("blendMode", raw, BlendMode.entries.map { it.name })

        fun elementMode(raw: String): ElementMode =
            Names.elementMode(raw) ?: fail("elementMode", raw, ElementMode.entries.map { it.name })

        fun elementFilter(raw: String): ElementFilter =
            Names.elementFilter(raw) ?: fail("elementFilter", raw, ElementFilter.entries.map { it.name })

        fun distribution(raw: String): DistributionStrategy =
            Names.distribution(raw) ?: fail("distributionStrategy", raw, DistributionStrategy.availableStrategies)

        private fun fail(field: String, raw: String, valid: List<String>): Nothing =
            throw IllegalArgumentException(
                "Unknown $field '$raw' (expected one of: ${valid.joinToString(", ")})"
            )
    }

    /**
     * Falls back to the field's default, logging once per parse. For values already persisted:
     * a spec written by an older build, or hand-edited, must not stop a cue from firing.
     *
     * [context] names the row for the log — `"cue 12 layer on 'CUE'"`, `"look effect 'SineWave'"` —
     * since the raw string alone rarely says which of hundreds of stored specs is at fault. It is
     * a lambda because `CueComposer.cook` calls this once per layer per cook, on every cue
     * transition and every programmer recompose; the happy path must not build a string it throws
     * away.
     */
    object Lenient {
        fun blendMode(raw: String?, context: () -> String): BlendMode =
            resolve(raw, context, "blendMode", BlendMode.OVERRIDE, Names::blendMode)

        fun elementMode(raw: String?, context: () -> String): ElementMode =
            resolve(raw, context, "elementMode", ElementMode.PER_FIXTURE, Names::elementMode)

        fun elementFilter(raw: String?, context: () -> String): ElementFilter =
            resolve(raw, context, "elementFilter", ElementFilter.ALL, Names::elementFilter)

        fun distribution(raw: String?, context: () -> String): DistributionStrategy =
            resolve(raw, context, "distributionStrategy", DistributionStrategy.LINEAR, Names::distribution)

        /**
         * **Null is absent; blank is a fault.** An optional field left unset is nobody's mistake
         * and takes the default silently. A *present* empty string is not the same thing —
         * `DaoCueLayers.blend_mode` is NOT NULL with a stored default, so `""` there means a row
         * got written wrong, and the warn is the operator's only clue that the blend they see in
         * the UI is not the blend the desk is playing. Blank therefore falls through to the parse
         * and warns like any other unrecognised value.
         */
        private fun <T> resolve(
            raw: String?,
            context: () -> String,
            field: String,
            default: T,
            parse: (String) -> T?,
        ): T {
            if (raw == null) return default
            return parse(raw) ?: run {
                logger.warn("{}: unknown {} '{}' — using {}", context(), field, raw, default)
                default
            }
        }
    }
}
