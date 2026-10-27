package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fx.group.SpreadOrder

/**
 * The one place an effect spec's enum-valued fields are read out of a string.
 *
 * `blendMode` / `distribution` / `elementMode` / `elementFilter` arrive as strings from three
 * directions — a REST body, a stored `look_effects` / `cue_effects` row, a WS frame — and until
 * this object existed each direction had its own parse. Two of the four enums threw
 * (`BlendMode.valueOf`, `ElementMode.valueOf`, both case-*sensitive*) while the other two
 * silently defaulted (`SpreadOrder.fromName`, `ElementFilter.fromName`), so one bad
 * string produced a 400 or a quiet NORMAL depending purely on which field it landed in — and
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
object FxSpecCoercion {
    private val logger = LoggerFactory.getLogger(FxSpecCoercion::class.java)

    /** Parsers that answer null for an unrecognised name — the single mapping both policies share. */
    object Names {
        fun blendMode(raw: String): BlendMode? = enumByName(BlendMode.entries, raw)

        fun elementMode(raw: String): ElementMode? = enumByName(ElementMode.entries, raw)

        fun elementFilter(raw: String): ElementFilter? = ElementFilter.byName(raw)

        fun order(raw: String): SpreadOrder? = SpreadOrder.byName(raw)

        fun direction(raw: String): FxDirection? = FxDirection.byName(raw.trim())

        fun spreadUnit(raw: String): SpreadUnit? = SpreadUnit.byName(raw)

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

        fun order(raw: String): SpreadOrder =
            Names.order(raw) ?: fail("order", raw, SpreadOrder.availableOrders)

        fun direction(raw: String): FxDirection =
            Names.direction(raw) ?: fail("direction", raw, FxDirection.entries.map { it.name })

        fun spreadUnit(raw: String): SpreadUnit =
            Names.spreadUnit(raw) ?: fail("spreadUnit", raw, SpreadUnit.entries.map { it.name })

        /**
         * The message the four functions above would throw for the first unrecognised value, or
         * null when every value supplied parses. **A null argument is "absent"** and takes no
         * view — the optional fields on a stored spec are genuinely optional.
         *
         * For the write boundaries that answer with a *message* rather than an exception:
         * `validateLookFx` returns one for its 400, `ProgrammerSocket` puts one in a
         * `programmer.error` frame. They get the same verdict as a `try` around four calls
         * without either surface reimplementing the parse.
         */
        fun problem(
            blendMode: String? = null,
            order: String? = null,
            elementMode: String? = null,
            elementFilter: String? = null,
            direction: String? = null,
            amount: Double? = null,
            runs: Int? = null,
            spread: Double? = null,
            wings: Int? = null,
            segments: Int? = null,
            parts: Int? = null,
            spreadUnit: String? = null,
            fxType: String? = null,
            fxSteps: Boolean? = null,
        ): String? {
            blendMode?.let { raw ->
                Names.blendMode(raw) ?: return message("blendMode", raw, BlendMode.entries.map { it.name })
            }
            order?.let { raw ->
                Names.order(raw)
                    ?: return message("order", raw, SpreadOrder.availableOrders)
            }
            elementMode?.let { raw ->
                Names.elementMode(raw) ?: return message("elementMode", raw, ElementMode.entries.map { it.name })
            }
            elementFilter?.let { raw ->
                Names.elementFilter(raw)
                    ?: return message("elementFilter", raw, ElementFilter.entries.map { it.name })
            }
            direction?.let { raw ->
                Names.direction(raw) ?: return message("direction", raw, FxDirection.entries.map { it.name })
            }
            amount?.let { amountProblem(it)?.let { problem -> return problem } }
            runs?.let { runsProblem(it)?.let { problem -> return problem } }
            val unit = spreadUnit?.let { raw ->
                Names.spreadUnit(raw) ?: return message("spreadUnit", raw, SpreadUnit.entries.map { it.name })
            }
            if (spread != null || wings != null || segments != null || parts != null || unit != null) {
                // An absent Order is the default one, NORMAL, which every stored spec falls back to.
                val spreadOrder = order?.let { Names.order(it) } ?: SpreadOrder.NORMAL
                spreadProblem(spread, wings, segments, parts, spreadOrder, unit, fxType, fxSteps)?.let { return it }
            }
            return null
        }

        /**
         * Why the spread's shape (effects plan D4) is not one — Spread outside 0–2 (0–200 %), or 0–16
         * steps under [SpreadUnit.STEP], Wings or Segments below 1, Parts below 0 (off), any count above
         * [SpreadShape.MAX_COUNT], Wings above 1 under a mirrored Order ([SpreadOrder.isMirrored]: Centre
         * out and Centre in are two wings already), or `STEP` on an FX with no steps ([fxSteps] false,
         * fx-settings plan D6) — or null. A null field is absent and takes no part, except that [order]
         * is checked against [wings] only when wings are given; [unit] is the one the FX will run, a
         * null one `CYCLE`, and [fxSteps] null takes no view.
         */
        fun spreadProblem(
            spread: Double?,
            wings: Int?,
            segments: Int?,
            parts: Int?,
            order: SpreadOrder,
            unit: SpreadUnit? = null,
            fxType: String? = null,
            fxSteps: Boolean? = null,
        ): String? {
            if (unit == SpreadUnit.STEP && fxSteps == false) {
                return "spreadUnit STEP counts an FX's steps, and ${fxType ?: "this FX"} has none " +
                    "(Colour Cycle, Palette Steps and Slot Steps do): send CYCLE"
            }
            spread?.let {
                if (unit == SpreadUnit.STEP) {
                    if (it.isNaN() || it < 0.0 || it > SpreadShape.MAX_STEP_SPREAD) {
                        return "spread must be between 0 and 16 steps under spreadUnit STEP, got $it"
                    }
                } else if (it.isNaN() || it < 0.0 || it > SpreadShape.MAX_SPREAD) {
                    return "spread must be between 0 and 2 (0–200 %), got $it"
                }
            }
            wings?.let { if (it !in 1..SpreadShape.MAX_COUNT) return "wings must be between 1 and ${SpreadShape.MAX_COUNT}, got $it" }
            segments?.let { if (it !in 1..SpreadShape.MAX_COUNT) return "segments must be between 1 and ${SpreadShape.MAX_COUNT}, got $it" }
            parts?.let {
                if (it !in 0..SpreadShape.MAX_COUNT) {
                    return "parts must be between 0 (off) and ${SpreadShape.MAX_COUNT}, got $it"
                }
            }
            if (wings != null && wings > 1 && order.isMirrored) {
                return "wings need an asymmetric order: ${orderName(order)} is two wings already (wings must be 1)"
            }
            return null
        }

        /** [spreadProblem] as the [IllegalArgumentException] the apply routes answer 400 with. */
        fun spreadShape(shape: SpreadShape, order: SpreadOrder, fxType: String? = null, fxSteps: Boolean? = null): SpreadShape =
            shape.also {
                spreadProblem(it.spread, it.wings, it.segments, it.parts, order, it.unit, fxType, fxSteps)
                    ?.let { p -> throw IllegalArgumentException(p) }
            }

        private fun orderName(order: SpreadOrder): String = when (order) {
            SpreadOrder.CENTRE_OUT -> "Centre out"
            SpreadOrder.CENTRE_IN -> "Centre in"
            else -> order.javaClass.simpleName
        }

        /** Why [amount] is not an Amount (effects plan D2: 0.0–1.0 — `FU-FX-AMPLITUDE`), or null. */
        fun amountProblem(amount: Double): String? =
            if (amount.isNaN() || amount < 0.0 || amount > 1.0) "amount must be between 0 and 1 (0–100 %), got $amount" else null

        /** Why [runs] is not a count of runs (D9: at least one), or null. */
        fun runsProblem(runs: Int): String? =
            if (runs < 1) "runs must be at least 1 (or absent to run forever), got $runs" else null

        /** [amountProblem] / [runsProblem] as the [IllegalArgumentException] the apply routes answer 400 with. */
        fun amount(amount: Double): Double = amount.also { v -> amountProblem(v)?.let { throw IllegalArgumentException(it) } }

        fun runs(runs: Int?): Int? = runs?.also { v -> runsProblem(v)?.let { throw IllegalArgumentException(it) } }

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
            resolve(raw, context, "elementMode", ElementMode.STORED_DEFAULT, Names::elementMode)

        fun elementFilter(raw: String?, context: () -> String): ElementFilter =
            resolve(raw, context, "elementFilter", ElementFilter.ALL, Names::elementFilter)

        fun order(raw: String?, context: () -> String): SpreadOrder =
            resolve(raw, context, "order", SpreadOrder.NORMAL, Names::order)

        fun direction(raw: String?, context: () -> String): FxDirection =
            resolve(raw, context, "direction", FxDirection.FORWARD, Names::direction)

        /** A stored Amount outside 0–1 (hand-edited, or a newer desk's oversize) clamps, with a warn. */
        fun amount(raw: Double, context: () -> String): Double {
            if (raw in 0.0..1.0) return raw
            logger.warn("{}: amount {} outside 0–1 — clamping", context(), raw)
            return if (raw.isNaN()) 1.0 else raw.coerceIn(0.0, 1.0)
        }

        /** A stored count below one runs forever, with a warn. */
        fun runs(raw: Int?, context: () -> String): Int? {
            if (raw == null || raw >= 1) return raw
            logger.warn("{}: runs {} below 1 — running forever", context(), raw)
            return null
        }

        /**
         * A stored spread shape (effects plan D4) with each field clamped into its range, with a warn —
         * Spread's by [unit], and a `STEP` on an FX with no steps ([steps] false) read as `CYCLE`.
         * Wings under a mirrored Order are left as stored: [SpreadShaping] ignores them, and the
         * editor shows them so the operator can see what the row says.
         */
        fun spreadShape(
            spread: Double,
            wings: Int,
            segments: Int,
            parts: Int,
            unit: String? = null,
            steps: Boolean? = null,
            context: () -> String,
        ): SpreadShape {
            val stored = resolve(unit, context, "spreadUnit", SpreadUnit.STORED_DEFAULT, Names::spreadUnit)
            // A STEP row on an FX with no steps (an archive's, which the importer does not check) runs
            // as the cycle's spread, and so takes the cycle's range.
            val spreadUnit = if (stored == SpreadUnit.STEP && steps == false) {
                logger.warn("{}: spreadUnit STEP on an FX with no steps — running as CYCLE", context())
                SpreadUnit.CYCLE
            } else {
                stored
            }
            val ceiling = if (spreadUnit == SpreadUnit.STEP) SpreadShape.MAX_STEP_SPREAD else SpreadShape.MAX_SPREAD
            val shape = SpreadShape(
                spread = if (spread.isNaN()) 1.0 else spread.coerceIn(0.0, ceiling),
                wings = wings.coerceIn(1, SpreadShape.MAX_COUNT),
                segments = segments.coerceIn(1, SpreadShape.MAX_COUNT),
                parts = parts.coerceIn(0, SpreadShape.MAX_COUNT),
                unit = spreadUnit,
            )
            if (shape.spread != spread || shape.wings != wings || shape.segments != segments || shape.parts != parts) {
                logger.warn(
                    "{}: spread shape (spread {}, wings {}, segments {}, parts {}) out of range — clamping",
                    context(), spread, wings, segments, parts,
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
