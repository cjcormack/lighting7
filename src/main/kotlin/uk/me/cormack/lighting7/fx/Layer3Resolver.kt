package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.models.TargetRef
import java.awt.Color

/**
 * Scoped palette cascade for resolving colour palette refs (`"P1"`, `"P2"`, …) in Layer 3
 * assignment values. [effective] picks the most specific scope with a non-empty palette:
 * [preset] > [cue] > [global]. All empty → palette refs fall through to the static colour
 * parser (invalid hex → white).
 *
 * Cue-originated rows leave [preset] empty; preset-originated rows populate all three.
 */
data class PaletteCascade(
    val preset: List<ExtendedColour> = emptyList(),
    val cue: List<ExtendedColour> = emptyList(),
    val global: List<ExtendedColour> = emptyList(),
) {
    val effective: List<ExtendedColour>
        get() = when {
            preset.isNotEmpty() -> preset
            cue.isNotEmpty() -> cue
            else -> global
        }

    companion object {
        val EMPTY = PaletteCascade()
    }
}

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
     * @property targetKey the fixture key. The caller pre-expands group assignments into
     *   per-member rows; the resolver never sees a group-level [targetKey].
     * @property targetIsGroup true when this row came from a group-scoped assignment (member
     *   row produced by group expansion). Lets [applySpecificity] drop the group-derived row
     *   when the same cue also asserts a direct fixture-level row on the same (fixture,
     *   property).
     * @property propertyName property on the (resolved) fixture.
     * @property category property category — drives composition default when [compositionOverride]
     *   is [CompositionRule.UNSET].
     * @property compositionOverride per-property override from `@FixtureProperty(composition = …)`.
     *   Use [CompositionRule.UNSET] to inherit from [category].
     * @property value the asserted value, type tagged.
     * @property moveInDark if true and this is a position property, the resolver pre-applies
     *   this value across the entire crossfade (instead of linearly blending pan/tilt) when an
     *   outgoing-or-parallel cue from a different [cueId] asserts a [PropertyCategory.DIMMER]
     *   contributor with value 0 on the same target. Ignored for non-position properties. See
     *   [computeMoveInDarkArmed] / `docs/lighting-composition-model.md` §"Crossfade behaviour".
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
         *   [resolveColour] → [PropertyValue.Colour]. Palette refs (`"P1"`, `"P2"`, …) are
         *   resolved against [palette] when non-empty; an empty palette falls through to the
         *   static colour parser (`"P1"` → white). See [PaletteCascade] for the scope rules.
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
            palette: List<ExtendedColour> = emptyList(),
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
                PropertyCategory.COLOUR -> runCatching { PropertyValue.Colour(resolveColour(trimmed, palette)) }.getOrNull()
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
     * Specificity rule: when a cue asserts both a group assignment (expanded to member rows
     * with `targetIsGroup = true`) and a direct fixture-level row for the same member, the
     * fixture-level row wins. See [applySpecificity].
     *
     * Complexity: O(n) over assignments, grouped into O(distinct (key, property)) output
     * entries. Allocates one [HashMap] per call and short-lived lists per group — acceptable
     * because this runs on cue apply (rare), not per tick.
     */
    fun resolve(assignments: List<Assignment>): Map<Key, PropertyValue> {
        if (assignments.isEmpty()) return emptyMap()

        // Pre-pass: determine which fixture targets have a moveInDark Position assignment
        // armed by an outgoing dimmer asserting value 0. See [computeMoveInDarkArmed].
        val moveInDarkArmed = computeMoveInDarkArmed(assignments)

        // Group by (targetKey, propertyName), then apply specificity (fixture beats group).
        val grouped = HashMap<Key, MutableList<Assignment>>()
        for (a in assignments) {
            val key = Key.fixture(a.targetKey, a.propertyName)
            grouped.getOrPut(key) { mutableListOf() }.add(a)
        }

        val out = HashMap<Key, PropertyValue>(grouped.size)
        for ((key, contributors) in grouped) {
            val effective = applySpecificity(contributors)
            if (effective.isEmpty()) continue
            val rule = effective.first().let { it.compositionOverride.takeUnless { it == CompositionRule.UNSET } ?: it.category.defaultComposition }
            out[key] = when (rule) {
                CompositionRule.HTP -> composeHtp(effective)
                CompositionRule.LTP -> composeLtp(effective, moveInDarkArmed)
                CompositionRule.UNSET -> composeLtp(effective, moveInDarkArmed) // defensive
            }
        }
        return out
    }

    /**
     * Returns the fixture targets for which a `moveInDark`-flagged `position` row should snap
     * to the incoming value across the whole crossfade. A target arms when the flagged row is
     * accompanied by a *different* cue's [PropertyCategory.DIMMER] contributor with value 0
     * on the same target; the different-cue requirement excludes self-arming, which the LTP
     * fallback already handles. Empty set is the common case (no flagged rows).
     */
    private fun computeMoveInDarkArmed(assignments: List<Assignment>): Set<String> {
        // Collect candidate fixture targets: those with at least one moveInDark position row.
        // Tracks per-target the set of cueIds that asserted the flag, so we can exclude them
        // when scanning for an outgoing dark dimmer.
        var candidates: HashMap<String, MutableSet<Int>>? = null
        for (a in assignments) {
            if (a.moveInDark && a.propertyName.equals("position", ignoreCase = true)) {
                val map = candidates ?: HashMap<String, MutableSet<Int>>().also { candidates = it }
                map.getOrPut(a.targetKey) { HashSet() }.add(a.cueId)
            }
        }
        val candidateMap = candidates ?: return emptySet()

        val armed = HashSet<String>()
        for (a in assignments) {
            if (a.category != PropertyCategory.DIMMER) continue
            val candidateCueIds = candidateMap[a.targetKey] ?: continue
            if (a.cueId in candidateCueIds) continue
            val slider = a.value as? PropertyValue.Slider ?: continue
            if (slider.value.toInt() == 0) {
                armed.add(a.targetKey)
            }
        }
        return armed
    }

    /**
     * Key for a ([target], [propertyName]) pair. Resolver output is always
     * [TargetRef.Fixture] (group assignments are expanded upstream); pre-expansion callers
     * may use [TargetRef.Group].
     */
    data class Key(val target: TargetRef, val propertyName: String) {
        val targetKey: String get() = target.key

        companion object {
            fun fixture(fixtureKey: String, propertyName: String): Key =
                Key(TargetRef.Fixture(fixtureKey), propertyName)

            fun group(groupKey: String, propertyName: String): Key =
                Key(TargetRef.Group(groupKey), propertyName)
        }
    }

    /**
     * Within a (targetKey, propertyName) bucket, drop group-expanded rows when any direct
     * fixture-level row is present — the direct row wins. If all rows share the same origin
     * (all direct, or all from group expansion), everything passes through.
     */
    private fun applySpecificity(contributors: List<Assignment>): List<Assignment> {
        val hasFixtureLevel = contributors.any { !it.targetIsGroup }
        return if (hasFixtureLevel) contributors.filter { !it.targetIsGroup } else contributors
    }

    /**
     * HTP: combines contributors so two independent cues at full weight take the highest
     * value (classic HTP), while contributors whose weights look like a crossfade pair
     * interpolate linearly. For [PropertyValue.Slider] / [PropertyValue.Setting]:
     *
     * - `Σ fadeWeight ≤ 1.0 + ε` → treated as a crossfade partition, composed as
     *   `Σ value × fadeWeight` (linear blend, no V-dip).
     * - `Σ fadeWeight > 1.0` → classical HTP max: `max(value × fadeWeight)` per contributor.
     *
     * [PropertyValue.Colour] maxes each R/G/B/W/A/UV independently. [PropertyValue.Position]
     * falls through to LTP — HTP isn't meaningful on axis values.
     */
    private fun composeHtp(contributors: List<Assignment>): PropertyValue {
        val first = contributors.first().value
        // Epsilon tolerates floating-point sums like `0.5 + 0.5 = 1.0000000000000002`.
        val weightSum = contributors.sumOf { it.fadeWeight }
        val isCrossfadeBlend = weightSum <= 1.0 + 1e-9
        return when (first) {
            is PropertyValue.Slider -> PropertyValue.Slider(
                composeHtpScalar(contributors, isCrossfadeBlend) { (it.value as PropertyValue.Slider).value.toInt() }
            )
            is PropertyValue.Setting -> PropertyValue.Setting(
                composeHtpScalar(contributors, isCrossfadeBlend) { (it.value as PropertyValue.Setting).channelValue.toInt() }
            )
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

    /** HTP scalar composition shared by Slider and Setting — see [composeHtp]. */
    private inline fun composeHtpScalar(
        contributors: List<Assignment>,
        isCrossfadeBlend: Boolean,
        extract: (Assignment) -> Int,
    ): UByte {
        val value = if (isCrossfadeBlend) {
            var acc = 0.0
            for (a in contributors) acc += extract(a) * a.fadeWeight
            acc.toInt().coerceIn(0, 255)
        } else {
            var maxVal = 0
            for (a in contributors) {
                val scaled = (extract(a) * a.fadeWeight).toInt().coerceIn(0, 255)
                if (scaled > maxVal) maxVal = scaled
            }
            maxVal
        }
        return value.toUByte()
    }

    /**
     * LTP: the highest-priority contributor wins. Ties break on fade weight (higher = more
     * recently faded in). Fade interpolation is applied *between* the winning contributor and
     * any lower-priority contributor that is still fading: this implements the cue-crossfade
     * behaviour from `docs/lighting-composition-model.md` §"Crossfade behaviour":
     * - Sliders: linear interpolation.
     * - Colour: RGB-linear via [blendExtendedColours].
     * - Settings: snap at 50% fade progress.
     * - Position: linear pan/tilt by default. When the bucket's targetKey is in
     *   [moveInDarkArmed], snap directly to the [Assignment.moveInDark] contributor's
     *   value across the entire crossfade — the head moves while dark and is already
     *   aimed when the incoming cue's dimmer comes up. Arming is precomputed by
     *   [computeMoveInDarkArmed] from cross-property dimmer-category contributors.
     *
     * When only one contributor is present, its value is returned unchanged regardless of
     * fadeWeight — the incoming-cue fade-in is handled at the caller / cue-apply level, not here.
     */
    private fun composeLtp(contributors: List<Assignment>, moveInDarkArmed: Set<String> = emptySet()): PropertyValue {
        // Snap before the winner-by-priority logic: at fade start the outgoing cue wins on
        // fadeWeight tie-break and would short-circuit to outgoing.value, but moveInDark
        // wants the incoming position immediately.
        val firstContributor = contributors.first()
        if (firstContributor.value is PropertyValue.Position &&
            firstContributor.targetKey in moveInDarkArmed
        ) {
            val moveInDark = contributors
                .filter { it.moveInDark && it.value is PropertyValue.Position }
                .maxByOrNull { it.priority }
            if (moveInDark != null) return moveInDark.value
        }

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
