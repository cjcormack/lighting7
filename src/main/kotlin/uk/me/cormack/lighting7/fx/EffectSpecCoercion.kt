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

        fun direction(raw: String): EffectDirection? = EffectDirection.byName(raw.trim())

        /**
         * [raw] as the name a row stores — `"backward"` as `BACKWARD` — for a value a write boundary
         * has already checked with [Strict.problem]; an unknown one is passed through unchanged.
         */
        fun canonicalDirection(raw: String): String = direction(raw)?.name ?: raw

        private fun <E : Enum<E>> enumByName(entries: List<E>, raw: String): E? =
            entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
    }

    /**
     * Rejects an unrecognised value. For request bodies, where the caller chose the string.
     *
     * **A new call site needs its own `catch`.** `plugins/ErrorHandling.kt` registers no
     * `IllegalArgumentException` clause, so this reaches the `Throwable` catch-all and answers
     * **500** with an error-level stack trace. It reads as a 400 at the apply endpoints because
     * each sits inside a handler that already catches `Exception` and responds `BadRequest` — not
     * because the type maps to one. Sweep item F6/F7 owns the envelope.
     *
     * The surfaces that *store* a spec rather than applying one mostly want [problem] instead:
     * they answer with a message, and two of them (a WS frame, an AI tool result) have no HTTP
     * status to answer with at all.
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

        fun direction(raw: String): EffectDirection =
            Names.direction(raw) ?: fail("direction", raw, EffectDirection.entries.map { it.name })

        /**
         * The message the four functions above would throw for the first unrecognised value, or
         * null when every value supplied parses. **A null argument is "absent"** and takes no
         * view — the optional fields on a stored spec are genuinely optional.
         *
         * For the write boundaries that answer with a *message* rather than an exception:
         * `validateLookEffects` returns one for its 400, `ProgrammerSocket` puts one in a
         * `programmer.error` frame. They get the same verdict as a `try` around four calls
         * without either surface reimplementing the parse.
         */
        fun problem(
            blendMode: String? = null,
            distribution: String? = null,
            elementMode: String? = null,
            elementFilter: String? = null,
            direction: String? = null,
            size: Double? = null,
            nShot: Int? = null,
            spread: Double? = null,
            wings: Int? = null,
            blocks: Int? = null,
            groups: Int? = null,
        ): String? {
            blendMode?.let { raw ->
                Names.blendMode(raw) ?: return message("blendMode", raw, BlendMode.entries.map { it.name })
            }
            distribution?.let { raw ->
                Names.distribution(raw)
                    ?: return message("distributionStrategy", raw, DistributionStrategy.availableStrategies)
            }
            elementMode?.let { raw ->
                Names.elementMode(raw) ?: return message("elementMode", raw, ElementMode.entries.map { it.name })
            }
            elementFilter?.let { raw ->
                Names.elementFilter(raw)
                    ?: return message("elementFilter", raw, ElementFilter.entries.map { it.name })
            }
            direction?.let { raw ->
                Names.direction(raw) ?: return message("direction", raw, EffectDirection.entries.map { it.name })
            }
            size?.let { sizeProblem(it)?.let { problem -> return problem } }
            nShot?.let { nShotProblem(it)?.let { problem -> return problem } }
            if (spread != null || wings != null || blocks != null || groups != null) {
                // An absent Order is the default one, LINEAR, which every stored spec falls back to.
                val order = distribution?.let { Names.distribution(it) } ?: DistributionStrategy.LINEAR
                spreadProblem(spread, wings, blocks, groups, order)?.let { return it }
            }
            return null
        }

        /**
         * Why the spread's shape (effects plan D4) is not one — Spread outside 0–2 (0–200 %), Wings or
         * Blocks below 1, Groups below 0 (off), any count above [SpreadShape.MAX_COUNT], or Wings above
         * 1 under a mirrored Order ([DistributionStrategy.isMirrored]: Centre out, Edges in and Split
         * are two wings already) — or null. A null field is absent and takes no part, except that
         * [order] is checked against [wings] only when wings are given.
         */
        fun spreadProblem(
            spread: Double?,
            wings: Int?,
            blocks: Int?,
            groups: Int?,
            order: DistributionStrategy,
        ): String? {
            spread?.let {
                if (it.isNaN() || it < 0.0 || it > SpreadShape.MAX_SPREAD) {
                    return "spread must be between 0 and 2 (0–200 %), got $it"
                }
            }
            wings?.let { if (it !in 1..SpreadShape.MAX_COUNT) return "wings must be between 1 and ${SpreadShape.MAX_COUNT}, got $it" }
            blocks?.let { if (it !in 1..SpreadShape.MAX_COUNT) return "blocks must be between 1 and ${SpreadShape.MAX_COUNT}, got $it" }
            groups?.let {
                if (it !in 0..SpreadShape.MAX_COUNT) {
                    return "groups must be between 0 (off) and ${SpreadShape.MAX_COUNT}, got $it"
                }
            }
            if (wings != null && wings > 1 && order.isMirrored) {
                return "wings need an asymmetric order: ${orderName(order)} is two wings already (wings must be 1)"
            }
            return null
        }

        /** [spreadProblem] as the [IllegalArgumentException] the apply routes answer 400 with. */
        fun spreadShape(shape: SpreadShape, order: DistributionStrategy): SpreadShape = shape.also {
            spreadProblem(it.spread, it.wings, it.blocks, it.groups, order)?.let { p -> throw IllegalArgumentException(p) }
        }

        private fun orderName(order: DistributionStrategy): String = when (order) {
            DistributionStrategy.CENTER_OUT -> "Centre out"
            DistributionStrategy.EDGES_IN -> "Edges in"
            DistributionStrategy.SPLIT -> "Split"
            else -> order.javaClass.simpleName
        }

        /** Why [size] is not a Size (effects plan D2: 0.0–1.0 — `FU-FX-AMPLITUDE`), or null. */
        fun sizeProblem(size: Double): String? =
            if (size.isNaN() || size < 0.0 || size > 1.0) "size must be between 0 and 1 (0–100 %), got $size" else null

        /** Why [nShot] is not a count of runs (D9: at least one), or null. */
        fun nShotProblem(nShot: Int): String? =
            if (nShot < 1) "nShot must be at least 1 (or absent to run forever), got $nShot" else null

        /** [sizeProblem] / [nShotProblem] as the [IllegalArgumentException] the apply routes answer 400 with. */
        fun size(size: Double): Double = size.also { v -> sizeProblem(v)?.let { throw IllegalArgumentException(it) } }

        fun nShot(nShot: Int?): Int? = nShot?.also { v -> nShotProblem(v)?.let { throw IllegalArgumentException(it) } }

        private fun fail(field: String, raw: String, valid: List<String>): Nothing =
            throw IllegalArgumentException(message(field, raw, valid))

        private fun message(field: String, raw: String, valid: List<String>): String =
            "Unknown $field '$raw' (expected one of: ${valid.joinToString(", ")})"
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

        fun direction(raw: String?, context: () -> String): EffectDirection =
            resolve(raw, context, "direction", EffectDirection.FORWARD, Names::direction)

        /** A stored Size outside 0–1 (hand-edited, or a newer desk's oversize) clamps, with a warn. */
        fun size(raw: Double, context: () -> String): Double {
            if (raw in 0.0..1.0) return raw
            logger.warn("{}: size {} outside 0–1 — clamping", context(), raw)
            return if (raw.isNaN()) 1.0 else raw.coerceIn(0.0, 1.0)
        }

        /** A stored count below one runs forever, with a warn. */
        fun nShot(raw: Int?, context: () -> String): Int? {
            if (raw == null || raw >= 1) return raw
            logger.warn("{}: nShot {} below 1 — running forever", context(), raw)
            return null
        }

        /**
         * A stored spread shape (effects plan D4) with each field clamped into its range, with a warn.
         * Wings under a mirrored Order are left as stored: [SpreadShaping] ignores them, and the
         * editor shows them so the operator can see what the row says.
         */
        fun spreadShape(spread: Double, wings: Int, blocks: Int, groups: Int, context: () -> String): SpreadShape {
            val shape = SpreadShape(
                spread = if (spread.isNaN()) 1.0 else spread.coerceIn(0.0, SpreadShape.MAX_SPREAD),
                wings = wings.coerceIn(1, SpreadShape.MAX_COUNT),
                blocks = blocks.coerceIn(1, SpreadShape.MAX_COUNT),
                groups = groups.coerceIn(0, SpreadShape.MAX_COUNT),
            )
            if (shape.spread != spread || shape.wings != wings || shape.blocks != blocks || shape.groups != groups) {
                logger.warn(
                    "{}: spread shape (spread {}, wings {}, blocks {}, groups {}) out of range — clamping",
                    context(), spread, wings, blocks, groups,
                )
            }
            return shape
        }

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
