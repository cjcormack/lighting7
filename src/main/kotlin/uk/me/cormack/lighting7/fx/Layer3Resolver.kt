package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import java.awt.Color

/**
 * Layer 3 composition resolver — merges [CuePropertyAssignment] rows from active cues into
 * a per-(target, property) value stream that [LayerResolver] consumes.
 *
 * Phase 0: the resolver has full HTP / LTP / fade-weight / specificity logic, but no real
 * assignments exist in the system yet (Phase 1 introduces the `cue_property_assignments`
 * table). Callers pass an empty list and receive an empty map. The full logic is unit-tested
 * with synthetic inputs per the spec's Worked Examples.
 *
 * See `docs/lighting-composition-model.md` §"Layer 3".
 */
class Layer3Resolver {

    /**
     * A single contributor to Layer 3 from an active cue.
     *
     * @property cueId id of the contributing cue — used for logging / stomp overlap.
     * @property priority cue-stack position or activation-order tie-break. Higher priority wins
     *   LTP, and — for HTP — priority matters only if values happen to tie.
     * @property fadeWeight crossfade progress in `[0, 1]`. 1 = fully in, 0 = fully out.
     * @property targetKey fixture or group key.
     * @property targetIsGroup true when [targetKey] refers to a group. Group assignments are
     *   expanded to member fixtures by the caller before resolution.
     * @property propertyName property on the (resolved) fixture.
     * @property category property category — drives composition default when [compositionOverride]
     *   is [CompositionRule.UNSET].
     * @property compositionOverride per-property override from `@FixtureProperty(composition = …)`.
     *   Use [CompositionRule.UNSET] to inherit from [category].
     * @property value the asserted value, type tagged.
     * @property moveInDark if true and this is a position property, the resolver may pre-apply
     *   this value during an outgoing cue's fade-out when intensity reaches 0.
     */
    data class Assignment(
        val cueId: Int,
        val priority: Int,
        val fadeWeight: Double,
        val targetKey: String,
        val targetIsGroup: Boolean,
        val propertyName: String,
        val category: PropertyCategory,
        val compositionOverride: CompositionRule = CompositionRule.UNSET,
        val value: PropertyValue,
        val moveInDark: Boolean = false,
    )

    /**
     * Typed property value carrying enough information for the composition rule to merge
     * contributors. Stored at property level, not channel level — conversion to channel values
     * happens in [LayerResolver] via the fixture patch.
     */
    sealed class PropertyValue {
        data class Slider(val value: UByte) : PropertyValue()
        data class Colour(val value: ExtendedColour) : PropertyValue()
        data class Position(val pan: UByte, val tilt: UByte) : PropertyValue()
        data class Setting(val channelValue: UByte) : PropertyValue()

        /**
         * Inverse of [parseAssignmentValue] — emits the canonical string form that round-trips
         * back through the parser. Used by `snapshot-from-live` to serialise live Layer 3
         * state into [uk.me.cormack.lighting7.models.CuePropertyAssignmentDto] rows.
         *
         * Format:
         * - [Slider] / [Setting]: unsigned decimal `"0".."255"`.
         * - [Colour]: [ExtendedColour.toSerializedString] (`"#rrggbb"` + optional `w` / `a` / `uv` tags).
         * - [Position]: `"pan,tilt"` (each `"0".."255"`).
         *
         * Round-trip invariant: `parseAssignmentValue(category, name, v.serialize()) == v` for
         * every [PropertyValue] instance, provided (category, name) matches what produced it.
         * See `Layer3ResolverTest`.
         */
        fun serialize(): String = when (this) {
            is Slider -> value.toInt().toString()
            is Setting -> channelValue.toInt().toString()
            is Colour -> value.toSerializedString()
            is Position -> "${pan.toInt()},${tilt.toInt()}"
        }
    }

    companion object {
        /**
         * Parse the canonical string form of a [CuePropertyAssignment][uk.me.cormack.lighting7.models.CuePropertyAssignmentDto]
         * value into a typed [PropertyValue].
         *
         * Dispatch uses [propertyName] first (to pick up the synthetic "position" property,
         * which has no dedicated [PropertyCategory] entry) and falls back to [category]:
         *
         * - `propertyName == "position"` (case-insensitive): `"pan,tilt"` (each `0..255`) →
         *   [PropertyValue.Position]. This matches the `createFixtureTargetForCue` special case.
         * - [PropertyCategory.COLOUR]: hex / named / extended string consumed by
         *   [parseExtendedColour] → [PropertyValue.Colour].
         * - [PropertyCategory.SETTING] / [PropertyCategory.OTHER]: `"0".."255"` → [PropertyValue.Setting].
         * - Every other category (intensity-like and axis sliders): `"0".."255"` →
         *   [PropertyValue.Slider].
         *
         * Returns `null` if the string doesn't parse for the given category — the caller should
         * log at warn and skip the assignment, never throw.
         */
        fun parseAssignmentValue(
            category: PropertyCategory,
            propertyName: String,
            value: String,
        ): PropertyValue? {
            val trimmed = value.trim()
            if (propertyName.equals("position", ignoreCase = true)) {
                val parts = trimmed.split(",")
                if (parts.size != 2) return null
                val pan = parts[0].trim().toUByteParam() ?: return null
                val tilt = parts[1].trim().toUByteParam() ?: return null
                return PropertyValue.Position(pan, tilt)
            }
            return when (category) {
                PropertyCategory.COLOUR -> runCatching { PropertyValue.Colour(parseExtendedColour(trimmed)) }.getOrNull()
                PropertyCategory.SETTING, PropertyCategory.OTHER ->
                    trimmed.toUByteParam()?.let { PropertyValue.Setting(it) }
                else ->
                    trimmed.toUByteParam()?.let { PropertyValue.Slider(it) }
            }
        }
    }

    /**
     * Resolve a flat list of assignments to a per-(targetKey, propertyName) composed value.
     *
     * Specificity rule: a fixture-level assignment wins over a group-level assignment for the
     * same (fixture, property). The caller is responsible for expanding group assignments to
     * member fixtures before calling [resolve]; the resolver treats [Assignment.targetIsGroup]
     * as an advisory flag and deduplicates purely by [Assignment.targetKey].
     *
     * Complexity: O(n) over assignments, grouped into O(distinct (key, property)) output
     * entries. Allocates one [HashMap] per call and short-lived lists per group — acceptable
     * because this runs on cue apply (rare), not per tick.
     */
    fun resolve(assignments: List<Assignment>): Map<Key, PropertyValue> {
        if (assignments.isEmpty()) return emptyMap()

        // Group by (targetKey, propertyName), then apply specificity (fixture beats group).
        val grouped = HashMap<Key, MutableList<Assignment>>()
        for (a in assignments) {
            val key = Key(a.targetKey, a.propertyName)
            grouped.getOrPut(key) { mutableListOf() }.add(a)
        }

        val out = HashMap<Key, PropertyValue>(grouped.size)
        for ((key, contributors) in grouped) {
            val effective = applySpecificity(contributors)
            if (effective.isEmpty()) continue
            val rule = effective.first().let { it.compositionOverride.takeUnless { it == CompositionRule.UNSET } ?: it.category.defaultComposition }
            out[key] = when (rule) {
                CompositionRule.HTP -> composeHtp(effective)
                CompositionRule.LTP -> composeLtp(effective)
                CompositionRule.UNSET -> composeLtp(effective) // defensive
            }
        }
        return out
    }

    /** Key for a resolved composition entry. */
    data class Key(val targetKey: String, val propertyName: String)

    /**
     * If both a fixture-level and a group-level assignment target the same (key, property),
     * the fixture-level wins. In Phase 0 the caller is expected to pre-expand group assignments
     * to per-member rows marked [Assignment.targetIsGroup] = false, so this is usually a no-op.
     */
    private fun applySpecificity(contributors: List<Assignment>): List<Assignment> {
        val hasFixtureLevel = contributors.any { !it.targetIsGroup }
        return if (hasFixtureLevel) contributors.filter { !it.targetIsGroup } else contributors
    }

    /**
     * HTP: output = max over contributors of (value × fadeWeight), per sub-channel.
     *
     * For [PropertyValue.Slider] / [PropertyValue.Setting] this is the natural max. For
     * [PropertyValue.Colour] we max each of R/G/B/W/A/UV independently (the "highest takes
     * precedence on each channel" reading), which keeps behaviour consistent if an HTP override
     * is ever applied to COLOUR. Typical COLOUR use stays LTP.
     *
     * For [PropertyValue.Position], HTP is not musically meaningful — we fall back to the
     * highest-priority contributor to avoid arithmetic on axis values.
     */
    private fun composeHtp(contributors: List<Assignment>): PropertyValue {
        val first = contributors.first().value
        return when (first) {
            is PropertyValue.Slider -> {
                var maxVal = 0
                for (a in contributors) {
                    val v = (a.value as PropertyValue.Slider).value.toInt()
                    val scaled = (v * a.fadeWeight).toInt().coerceIn(0, 255)
                    if (scaled > maxVal) maxVal = scaled
                }
                PropertyValue.Slider(maxVal.toUByte())
            }
            is PropertyValue.Setting -> {
                var maxVal = 0
                for (a in contributors) {
                    val v = (a.value as PropertyValue.Setting).channelValue.toInt()
                    val scaled = (v * a.fadeWeight).toInt().coerceIn(0, 255)
                    if (scaled > maxVal) maxVal = scaled
                }
                PropertyValue.Setting(maxVal.toUByte())
            }
            is PropertyValue.Colour -> {
                var r = 0; var g = 0; var b = 0
                var w = 0; var amber = 0; var uv = 0
                for (a in contributors) {
                    val c = (a.value as PropertyValue.Colour).value
                    val wf = a.fadeWeight
                    r = maxOf(r, (c.color.red * wf).toInt().coerceIn(0, 255))
                    g = maxOf(g, (c.color.green * wf).toInt().coerceIn(0, 255))
                    b = maxOf(b, (c.color.blue * wf).toInt().coerceIn(0, 255))
                    w = maxOf(w, (c.white.toInt() * wf).toInt().coerceIn(0, 255))
                    amber = maxOf(amber, (c.amber.toInt() * wf).toInt().coerceIn(0, 255))
                    uv = maxOf(uv, (c.uv.toInt() * wf).toInt().coerceIn(0, 255))
                }
                PropertyValue.Colour(ExtendedColour(Color(r, g, b), w.toUByte(), amber.toUByte(), uv.toUByte()))
            }
            is PropertyValue.Position -> composeLtp(contributors) // positions don't combine
        }
    }

    /**
     * LTP: the highest-priority contributor wins. Ties break on fade weight (higher = more
     * recently faded in). Fade interpolation is applied *between* the winning contributor and
     * any lower-priority contributor that is still fading: this implements the cue-crossfade
     * behaviour from `docs/lighting-composition-model.md` §"Crossfade behaviour":
     * - Sliders: linear interpolation.
     * - Colour: RGB-linear via [blendExtendedColours].
     * - Settings: snap at 50% fade progress.
     * - Position: linear pan/tilt (moveInDark handled upstream at cue apply time).
     *
     * When only one contributor is present, its value is returned unchanged regardless of
     * fadeWeight — the incoming-cue fade-in is handled at the caller / cue-apply level, not here.
     */
    private fun composeLtp(contributors: List<Assignment>): PropertyValue {
        // Winner = highest priority; tie-break = highest fade weight (more recent).
        val winner = contributors.maxWithOrNull(
            compareBy<Assignment>({ it.priority }, { it.fadeWeight })
        ) ?: return contributors.first().value

        // Any still-contributing contributor (fadeWeight > 0) other than the winner is treated as
        // an outgoing candidate — including steady-state weight=1.0. Crossfade start has
        // outgoing at exactly 1.0 and incoming (winner) at 0.0; the earlier `< 1.0` bound
        // would exclude the outgoing and snap-cut to the incoming value. End-of-fade is
        // guarded below by `winner.fadeWeight >= 1.0`.
        val outgoing = contributors
            .filter { it !== winner && it.fadeWeight > 0.0 }
            .maxByOrNull { it.priority }

        // No crossfade in flight — winner's value stands.
        if (outgoing == null || winner.fadeWeight >= 1.0) return winner.value

        val progress = winner.fadeWeight.coerceIn(0.0, 1.0)

        return when (val w = winner.value) {
            is PropertyValue.Slider -> {
                val o = (outgoing.value as? PropertyValue.Slider)?.value?.toInt() ?: 0
                val blended = (o + (w.value.toInt() - o) * progress).toInt().coerceIn(0, 255)
                PropertyValue.Slider(blended.toUByte())
            }
            is PropertyValue.Colour -> {
                val o = (outgoing.value as? PropertyValue.Colour)?.value ?: ExtendedColour.BLACK
                PropertyValue.Colour(blendExtendedColours(o, w.value, progress))
            }
            is PropertyValue.Setting -> {
                // Discrete — snap at 50% fade progress.
                if (progress < 0.5) outgoing.value else w
            }
            is PropertyValue.Position -> {
                val o = outgoing.value as? PropertyValue.Position
                if (o == null) {
                    w
                } else {
                    val pan = (o.pan.toInt() + (w.pan.toInt() - o.pan.toInt()) * progress).toInt().coerceIn(0, 255)
                    val tilt = (o.tilt.toInt() + (w.tilt.toInt() - o.tilt.toInt()) * progress).toInt().coerceIn(0, 255)
                    PropertyValue.Position(pan.toUByte(), tilt.toUByte())
                }
            }
        }
    }
}
