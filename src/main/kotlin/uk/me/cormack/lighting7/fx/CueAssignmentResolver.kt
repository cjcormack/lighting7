package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.AttributeCategory
import uk.me.cormack.lighting7.models.TargetRef
import java.awt.Color

/**
 * Cue-layer composition resolver — merges [CueAttributeAssignment] rows from active cues
 * into a per-(target, property) value stream that [LayerResolver] consumes.
 *
 * See `docs/lighting-composition-model.md` §"Tier 4 — Cue attribute assignments".
 */
class CueAssignmentResolver {

    /**
     * A single contributor to Layer 4 from an active cue.
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
     * @property attribute property on the (resolved) fixture.
     * @property category property category — drives composition default when [compositionOverride]
     *   is [CompositionRule.UNSET].
     * @property compositionOverride per-property override from `@FixtureAttribute(composition = …)`.
     *   Use [CompositionRule.UNSET] to inherit from [category].
     * @property value the asserted value, type tagged.
     */
    data class Assignment(
        val cueId: Int,
        val priority: Int,
        val fadeWeight: Double,
        val targetKey: String,
        val targetIsGroup: Boolean,
        val attribute: String,
        val category: AttributeCategory,
        val compositionOverride: CompositionRule = CompositionRule.UNSET,
        val value: AttributeValue,
        /**
         * Which Look layer produced [value], when a layer did — see [CookWinner].
         *
         * Composition ignores it completely: this is an attribution, not an input. Two consumers read it, and both need the *winning* layer
         * rather than the set of layers that touched the key:
         *
         * - provenance, so "why is this fixture this colour?" can answer *Warm Wash* instead of
         *   *a cue* ([LayerResolver.currentCueLayerLayerWinners]);
         * - the programmer's layer stack, which stamps `ProgrammerStore.Slot.seq` from
         *   [CookWinner.index] so layer order behaves like write order.
         *
         * Null for a cue's **local** rows and for anything built outside [CueComposer.cook] —
         * local rows belong to no layer, which is the same thing as "not attributable to one".
         */
        val layerWinner: CookWinner? = null,
        /**
         * How long this row's value should take to arrive on the rig, or null to snap.
         *
         * Composition ignores it, like [layerWinner] — it is a transmit-time instruction, not an
         * input. Read only by the publish paths, and only for the *winning* contributor per key:
         * a fade is a property of the row that decided the value, so a losing row's fade has
         * nothing to apply to. See [LayerResolver.CueLayerSnapshot.fadeDurations].
         *
         * Distinct from the per-cue `fadeDurationMs` a crossfade uses, which weights whole cues
         * against each other rather than ramping one channel; the two never run on one key at
         * once (see `FxEngine.publishCueLayerToControllers`).
         */
        val fadeDurationMs: Long? = null,
        /**
         * This row's part in a `bundleWithColour` bundle on [targetKey], or null for none:
         * [BundleRole.COLOUR] for the fixture's RGB colour, whose [ExtendedColour] carries the
         * emitters' W/A/UV, and [BundleRole.EMITTER] for a W/A/UV slider that shares its channel
         * with that component. [compose] reads it to let an emitter's composed value replace the
         * colour's copy (see [reconcileBundledEmitters]); nothing else does.
         *
         * Resolved from the class catalogue where the row is built (`buildCueAssignmentsForCue`,
         * [CueComposer]), because the resolver never sees a fixture. A hand-built row leaves it
         * null and so takes no part in the rule.
         */
        val bundleRole: BundleRole? = null,
        /**
         * The row this value came from, **before group expansion**: the cue's own assignment's
         * target, or the layer row's — a group own row's members all carry the group here, a cell
         * row its cell. Null for a row built outside the cue's cook.
         *
         * Composition ignores it, like [layerWinner]. One consumer reads it, `GET /cues/{id}/cooked`,
         * so the cue editor's Values grid can draw a group's own row on the group's row and write
         * it back there (show-cue-editing plan §3.8) — [targetKey] names a member by then.
         */
        val sourceTarget: TargetRef? = null,
        /**
         * The palette this row's value is a reference to (palette-references plan D6), or null for a
         * literal. Composition ignores it; provenance reads the winning row's.
         */
        val reference: java.util.UUID? = null,
        /**
         * The replacing stack this row folds in, or null for a row that composes by the key's rule
         * (busk-stack plan D7) — [CueHolds]' [CueStamp.replaceGroup], stamped by [CueAssignmentLayer].
         * A positive group is a stack's id; a negative one a single cue's ([ownReplaceGroup]).
         */
        val replaceGroup: Int? = null,
    )

    /** See [Assignment.bundleRole]. */
    enum class BundleRole { COLOUR, EMITTER }

    /**
     * Typed property value carrying enough information for the composition rule to merge
     * contributors. Stored at property level, not channel level — conversion to channel values
     * happens in [LayerResolver] via the fixture patch.
     */
    sealed class AttributeValue {
        data class Slider(val value: UByte) : AttributeValue()
        data class Colour(val value: ExtendedColour) : AttributeValue()
        data class Position(val pan: UByte, val tilt: UByte) : AttributeValue()
        data class Setting(val channelValue: UByte) : AttributeValue()

        /**
         * Inverse of [parseAssignmentValue] — emits the canonical string form that round-trips
         * back through the parser. Used by `snapshot-from-live` to serialise live Layer 4
         * state into [uk.me.cormack.lighting7.models.CueAttributeAssignmentDto] rows.
         *
         * Format:
         * - [Slider] / [Setting]: unsigned decimal `"0".."255"`.
         * - [Colour]: [ExtendedColour.toSerializedString] (`"#rrggbb"` + optional `w` / `a` / `uv` tags).
         * - [Position]: `"pan,tilt"` (each `"0".."255"`).
         *
         * Round-trip invariant: `parseAssignmentValue(category, name, v.serialize()) == v` for
         * every [AttributeValue] instance, provided (category, name) matches what produced it.
         * See `CueAssignmentResolverTest`.
         */
        fun serialize(): String = when (this) {
            is Slider -> value.toInt().toString()
            is Setting -> channelValue.toInt().toString()
            is Colour -> value.toSerializedString()
            is Position -> "${pan.toInt()},${tilt.toInt()}"
        }
    }

    companion object {
        /** No rows — composes to nothing at any weights. */
        val EMPTY_COOK = Cook(emptyList())

        private val EMPTY_COMPOSED = Composed(emptyMap(), emptyMap())

        /** The cue id a replacing fold's floor composes under: no cue's, and no fade weight's. */
        private const val FLOOR_CUE_ID = Int.MIN_VALUE

        /**
         * The weight a replacing stack leaves [effect] on a key with [groups] (busk-stack plan D8): the
         * product of (1 − *W*) over the groups that hold it back — 1 where none does, 0 under a group
         * fully in. A programmer-band effect is never held back.
         */
        fun replacedWeight(groups: List<ReplacingGroup>?, priority: Int, cueId: Int?, stackOf: (Int) -> Int?): Double {
            if (groups.isNullOrEmpty() || FxEngine.isProgrammerFxPriority(priority)) return 1.0
            var weight = 1.0
            for (group in groups) if (group.holdsBack(priority, cueId, stackOf)) weight *= (1.0 - group.weight)
            return weight
        }

        /**
         * Flatten a [resolveIndexed]-shaped map to per-[Key] entries. The one place the
         * nested→flat key construction lives — [resolve] and
         * [LayerResolver.CueLayerSnapshot.state] must agree on key identity, and two copies
         * of this loop would let them silently diverge.
         */
        fun flattenIndexed(
            indexed: Map<String, Map<String, AttributeValue>>,
        ): Map<Key, AttributeValue> {
            if (indexed.isEmpty()) return emptyMap()
            val out = HashMap<Key, AttributeValue>()
            for ((targetKey, attributes) in indexed) {
                for ((attribute, value) in attributes) {
                    out[Key.fixture(targetKey, attribute)] = value
                }
            }
            return out
        }

        /**
         * Parse the canonical string form of a [CueAttributeAssignment][uk.me.cormack.lighting7.models.CueAttributeAssignmentDto]
         * value into a typed [AttributeValue].
         *
         * Dispatch uses [attribute] first (to pick up the synthetic "position" property,
         * which has no dedicated [AttributeCategory] entry) and falls back to [category]:
         *
         * - `attribute == "position"` (case-insensitive): `"pan,tilt"` (each `0..255`) →
         *   [AttributeValue.Position]. This matches the `createFixtureTargetForCue` special case.
         * - [AttributeCategory.COLOUR]: hex / named / extended string consumed by
         *   [parseExtendedColour] → [AttributeValue.Colour]. A **palette reference**
         *   (`pal:{uuid}`) returns null: every reader resolves one per head before parsing
         *   ([PaletteReferences]), and letting one through would hand [parseExtendedColour] an
         *   unparseable string, which answers white.
         *   **Unless [settingBacked]**: a COLOUR property backed by a slot channel (a colour wheel,
         *   a gel scroller) stores and is written slot levels, so a plain `"0".."255"` is a
         *   [AttributeValue.Setting] there. A level is read first because the colour parse would
         *   take `"128"` for the hex shorthand `#112288`, which a slot channel cannot take — the
         *   writer drops a colour on anything but an RGB property. Anything else still parses as
         *   a colour, as before.
         * - [AttributeCategory.SETTING] / [AttributeCategory.OTHER]: `"0".."255"` → [AttributeValue.Setting].
         * - Every other category (intensity-like and axis sliders): `"0".."255"` →
         *   [AttributeValue.Slider] — **unless [settingBacked]**: a slider-category property backed
         *   by a slot channel (the Robe ColorSpot 575's stepped zoom, fixture-optics plan D2) is a
         *   [AttributeValue.Setting], because the cue layer picks its output target from the value's
         *   type and a slider target finds no slider on a setting, silently writing nothing.
         *
         * Returns `null` if the string doesn't parse for the given category — the caller should
         * log at warn and skip the assignment, never throw.
         */
        fun parseAssignmentValue(
            category: AttributeCategory,
            attribute: String,
            value: String,
            settingBacked: Boolean = false,
        ): AttributeValue? {
            val trimmed = value.trim()
            if (attribute.equals("position", ignoreCase = true)) {
                val parts = trimmed.split(",")
                if (parts.size != 2) return null
                val pan = parts[0].trim().toUByteParam() ?: return null
                val tilt = parts[1].trim().toUByteParam() ?: return null
                return AttributeValue.Position(pan, tilt)
            }
            return when (category) {
                AttributeCategory.COLOUR -> {
                    val level = if (settingBacked) trimmed.toUByteParam() else null
                    if (level != null) AttributeValue.Setting(level)
                    else if (isPaletteColourRef(trimmed)) null
                    else runCatching { AttributeValue.Colour(parseExtendedColour(trimmed)) }.getOrNull()
                }
                // Wheel-like roles read as discrete selections. Which arm a category lands
                // in is now only a labelling choice: AttributeChannelWriter resolves both
                // Setting and Slider through the same single-byte-channel path, precisely
                // because category doesn't predict the backing shape (gobo rotation is a
                // DmxFixtureSetting on the Fusion and a DmxSlider on the MAC 250).
                AttributeCategory.SETTING, AttributeCategory.OTHER,
                AttributeCategory.GOBO, AttributeCategory.GOBO_ROTATION,
                AttributeCategory.GOBO_ROTATION_MODE,
                AttributeCategory.PRISM, AttributeCategory.PRISM_ROTATION,
                AttributeCategory.LED_MACRO, AttributeCategory.MOVEMENT_MACRO ->
                    trimmed.toUByteParam()?.let { AttributeValue.Setting(it) }
                // Continuous positions: FOCUS, ZOOM, IRIS, FROST, PAN/TILT, DIMMER… — a slot level
                // where the property is backed by a setting (a stepped zoom).
                else ->
                    trimmed.toUByteParam()?.let { if (settingBacked) AttributeValue.Setting(it) else AttributeValue.Slider(it) }
            }
        }
    }

    /**
     * Per-cue crossfade weights layered on top of each row's own [Assignment.fadeWeight].
     *
     * The engine used to fold these in by `copy`-ing every fading row before composing, which
     * on a crossfade frame meant one new [Assignment] per live row at ~62 fps *and* a fresh
     * [Cook] each time, because the copies were new objects. Passing the lookup down instead
     * leaves the row set — and therefore the cook — untouched for the whole fade.
     *
     * Only cues mid-fade have an entry; a missing one means 1.0, and an empty map is the
     * steady state, which short-circuits to the row's own weight.
     */
    @JvmInline
    value class FadeWeights(private val byCue: Map<Int, Double>) {
        /** [assignment]'s effective weight for this frame. */
        fun of(assignment: Assignment): Double =
            if (byCue.isEmpty()) assignment.fadeWeight
            else assignment.fadeWeight * (byCue[assignment.cueId] ?: 1.0)

        companion object {
            /** No crossfade in flight — every row composes at its own weight. */
            val NONE = FadeWeights(emptyMap())
        }
    }

    /**
     * The weight-independent half of [resolveIndexed]: the specificity-filtered, per-target,
     * per-property contributor buckets with their composition rule already chosen.
     *
     * Every input to both is the row set alone — `targetIsGroup`, `category`,
     * `compositionOverride`, `bundleRole`, and the asserted values — so a crossfade can cook
     * once when the rows change and recompose per frame at new [FadeWeights]. See
     * [LayerResolver.reweightAssignments], which owns the cached instance and the invariant
     * that keeps it valid.
     */
    class Cook internal constructor(
        internal val targets: List<CookedTarget>,
    )

    /** One target's buckets — the outer level of [resolveIndexed]'s output. */
    internal class CookedTarget(
        val targetKey: String,
        val attributes: List<Bucket>,
        /**
         * This target's colour-and-emitters bundle, **only when it composes both halves** — null
         * otherwise, which is every target but the few that hold both, so [compose]'s per-frame
         * pass costs a null check. See [reconcileBundledEmitters].
         */
        val bundle: BundleBuckets? = null,
    )

    /** The target's [BundleRole.COLOUR] property and its [BundleRole.EMITTER] buckets. */
    internal class BundleBuckets(val colourAttribute: String, val emitters: List<Bucket>)

    /** One (target, property)'s contributors and the rule that combines them. */
    internal class Bucket(
        val attribute: String,
        val contributors: List<Assignment>,
        val rule: CompositionRule,
    ) {
        /** Whether a replacing stack's row is among [contributors] (busk-stack plan D7): [compose] folds it. */
        val replacing: Boolean = contributors.any { it.replaceGroup != null }
    }

    /**
     * One replacing stack on one key at one frame (busk-stack plan D7): its [group], the lowest
     * priority its rows on the key hold ([position]) — below which what composes is blended out — the
     * combined fade [weight] of those rows, and the cues they are, whose own effects it never holds back.
     */
    class ReplacingGroup(val group: Int, val position: Int, val weight: Double, val cueIds: Set<Int>) {
        /** The stack, for a group a stack's hold gives; null for a single cue's own ([ownReplaceGroup]). */
        val stackId: Int? get() = group.takeIf { it > 0 }

        /**
         * Whether this group holds back an effect at [priority] owned by [cueId]: it sits below the
         * group, and is neither one of its cues' nor its stack's — [stackOf] reads a priority back to
         * the stack whose rank it is in (`StackRanks.stackAt`; P3, a live or releasing stack's rank is
         * its own), so a cue the stack is fading out keeps its effects too.
         */
        fun holdsBack(priority: Int, cueId: Int?, stackOf: (Int) -> Int?): Boolean =
            priority < position && (cueId == null || cueId !in cueIds) &&
                !(stackId != null && stackOf(priority) == stackId)
    }

    /** [compose]'s values, and every key a replacing stack sets with the groups on it, low to high. */
    class Composed(
        val index: Map<String, Map<String, AttributeValue>>,
        val replaced: Map<String, Map<String, List<ReplacingGroup>>>,
    )

    /**
     * Group [assignments] by target then property, apply specificity, and pick each bucket's
     * composition rule — everything [compose] can do before it knows the frame's weights.
     *
     * Specificity rule: when a cue asserts both a group assignment (expanded to member rows
     * with `targetIsGroup = true`) and a direct fixture-level row for the same member, the
     * fixture-level row wins. See [applySpecificity].
     */
    fun cook(assignments: List<Assignment>): Cook {
        if (assignments.isEmpty()) return EMPTY_COOK

        val grouped = HashMap<String, HashMap<String, MutableList<Assignment>>>()
        for (a in assignments) {
            grouped.getOrPut(a.targetKey) { HashMap() }
                .getOrPut(a.attribute) { mutableListOf() }
                .add(a)
        }

        val targets = ArrayList<CookedTarget>(grouped.size)
        for ((targetKey, attributes) in grouped) {
            val buckets = ArrayList<Bucket>(attributes.size)
            for ((attribute, contributors) in attributes) {
                val effective = specificWithinGroups(contributors)
                if (effective.isEmpty()) continue
                val rule = effective.first().let {
                    it.compositionOverride.takeUnless { r -> r == CompositionRule.UNSET }
                        ?: it.category.defaultComposition
                }
                buckets.add(Bucket(attribute, effective, rule))
            }
            if (buckets.isNotEmpty()) targets.add(CookedTarget(targetKey, buckets, bundleOf(buckets)))
        }
        return Cook(targets)
    }

    /**
     * The bundle [reconcileBundledEmitters] must apply for one target, or null when there is
     * nothing to reconcile — no colour bucket, or no emitter bucket beside it.
     */
    private fun bundleOf(buckets: List<Bucket>): BundleBuckets? {
        var emitters: ArrayList<Bucket>? = null
        var colour: String? = null
        for (bucket in buckets) {
            val first = bucket.contributors.first()
            when (first.bundleRole) {
                BundleRole.COLOUR -> if (first.value is AttributeValue.Colour) colour = bucket.attribute
                BundleRole.EMITTER -> if (first.value is AttributeValue.Slider) {
                    val list = emitters ?: ArrayList<Bucket>(3)
                    list.add(bucket)
                    emitters = list
                }
                null -> {}
            }
        }
        val found = emitters
        return if (colour != null && found != null) BundleBuckets(colour, found) else null
    }

    /**
     * Compose a [Cook] at one frame's [weights], indexed `targetKey → attribute → value` —
     * the shape [LayerResolver]'s hot-path index wants, built directly so a publish doesn't
     * also materialise a flat [Key]-keyed duplicate of the same data (or a compound [Key] per
     * row).
     *
     * Complexity: O(n) over contributors, into O(distinct (key, property)) output entries.
     * This runs on cue apply *and once per crossfade frame (~62 fps)* — the nested maps it
     * allocates land on a hot path; keep it lean.
     */
    fun compose(cook: Cook, weights: FadeWeights): Map<String, Map<String, AttributeValue>> = composeFull(cook, weights).index

    /** [compose], with the keys a replacing stack sets ([Composed.replaced]) — what the engine publishes. */
    fun composeFull(cook: Cook, weights: FadeWeights): Composed {
        if (cook.targets.isEmpty()) return EMPTY_COMPOSED
        val out = HashMap<String, Map<String, AttributeValue>>(cook.targets.size)
        var replaced: HashMap<String, Map<String, List<ReplacingGroup>>>? = null
        for (target in cook.targets) {
            val composed = HashMap<String, AttributeValue>(target.attributes.size)
            var targetReplaced: HashMap<String, List<ReplacingGroup>>? = null
            for (bucket in target.attributes) {
                if (bucket.replacing) {
                    val groups = ArrayList<ReplacingGroup>(2)
                    composed[bucket.attribute] = composeReplaced(bucket, weights, groups)
                    if (groups.isNotEmpty()) {
                        val map = targetReplaced ?: HashMap<String, List<ReplacingGroup>>().also { targetReplaced = it }
                        map[bucket.attribute] = groups
                    }
                } else {
                    composed[bucket.attribute] = composeByRule(bucket.rule, bucket.contributors, weights)
                }
            }
            target.bundle?.let { reconcileBundledEmitters(composed, it) }
            out[target.targetKey] = composed
            targetReplaced?.let { (replaced ?: HashMap<String, Map<String, List<ReplacingGroup>>>().also { m -> replaced = m })[target.targetKey] = it }
        }
        return Composed(out, replaced ?: emptyMap())
    }

    private fun composeByRule(rule: CompositionRule, contributors: List<Assignment>, weights: FadeWeights): AttributeValue =
        when (rule) {
            CompositionRule.HTP -> composeHtp(contributors, weights)
            CompositionRule.LTP -> composeLtp(contributors, weights)
            CompositionRule.UNSET -> composeLtp(contributors, weights) // defensive
        }

    /**
     * **Replaces** (busk-stack plan D7): a replacing stack's rows are the floor of the key they set. The
     * key folds upward in priority order, one replacing group at a time: the contributors below the
     * lowest group compose by the key's rule into the floor; each group's own rows — a crossfade's or a
     * release's two among them — compose among themselves into its value *R* at their weights scaled to
     * sum to one, and the floor is blended toward *R* by the group's combined weight *W* = min(1, Σw);
     * the floor, at weight 1 since it already holds that fade, then composes by the key's rule with the
     * contributors above the group and below the next. What is above the highest group composes over
     * the last floor the same way. A group with nothing under it composes as its rows would alone.
     *
     * Each group on the key is recorded in [groups], low to high, for the effects it holds back.
     */
    private fun composeReplaced(bucket: Bucket, weights: FadeWeights, groups: MutableList<ReplacingGroup>): AttributeValue {
        val rule = bucket.rule
        val byGroup = LinkedHashMap<Int, MutableList<Assignment>>()
        val others = ArrayList<Assignment>()
        for (a in bucket.contributors) {
            val group = a.replaceGroup
            if (group == null) others += a else byGroup.getOrPut(group) { ArrayList(2) } += a
        }
        val ordered = byGroup.entries
            .map { (group, rows) -> Triple(group, rows.minOf { it.priority }, rows) }
            .sortedWith(compareBy({ it.second }, { it.first }))

        val template = bucket.contributors.first()
        var floor: AttributeValue? = null
        var floorPosition = Int.MIN_VALUE
        fun withFloor(rows: List<Assignment>): List<Assignment> {
            val value = floor ?: return rows
            return rows + template.copy(cueId = FLOOR_CUE_ID, priority = floorPosition, fadeWeight = 1.0, value = value, replaceGroup = null)
        }

        for ((group, position, rows) in ordered) {
            val segment = others.filter { it.priority in floorPosition until position }
            val below = withFloor(segment).takeIf { it.isNotEmpty() }?.let { composeByRule(rule, it, weights) }
            var sum = 0.0
            for (row in rows) sum += weights.of(row)
            val weight = sum.coerceAtMost(1.0)
            groups += ReplacingGroup(group, position, weight, rows.mapTo(HashSet()) { it.cueId })
            floor = when {
                weight <= 0.0 -> below
                below == null -> composeByRule(rule, rows, weights)
                else -> {
                    // Its rows' own blend, at weights scaled to one: the fade is [weight]'s alone.
                    val scale = if (sum < 1.0) 1.0 / sum else 1.0
                    val own = composeByRule(rule, rows.map { it.copy(fadeWeight = weights.of(it) * scale) }, FadeWeights.NONE)
                    blendTowards(below, own, weight)
                }
            }
            floorPosition = position
        }
        val above = others.filter { it.priority >= floorPosition }
        val top = withFloor(above)
        return if (top.isEmpty()) composeByRule(rule, bucket.contributors, weights) else composeByRule(rule, top, weights)
    }

    /** [from] blended toward [to] by [progress], as an LTP crossfade blends ([interpolate]), exact at either end. */
    private fun blendTowards(from: AttributeValue, to: AttributeValue, progress: Double): AttributeValue = when {
        progress >= 1.0 -> to
        progress <= 0.0 -> from
        else -> interpolate(from, to, progress)
    }

    /**
     * The crossfade's per-type blend from [from] to [to] at [progress] — an LTP crossfade's
     * ([composeLtp]) and a replacing stack's fold ([composeReplaced]) alike:
     * - Sliders: linear interpolation, from 0 when [from] is no slider.
     * - Colour: RGB-linear via [blendExtendedColours], from black when [from] is no colour.
     * - Settings: snap at 50% fade progress.
     * - Position: linear pan/tilt; [to] at once when [from] is no position.
     */
    private fun interpolate(from: AttributeValue, to: AttributeValue, progress: Double): AttributeValue = when (to) {
        is AttributeValue.Slider -> {
            val o = (from as? AttributeValue.Slider)?.value?.toInt() ?: 0
            AttributeValue.Slider((o + (to.value.toInt() - o) * progress).toInt().coerceIn(0, 255).toUByte())
        }
        is AttributeValue.Colour -> {
            val o = (from as? AttributeValue.Colour)?.value ?: ExtendedColour.BLACK
            AttributeValue.Colour(blendExtendedColours(o, to.value, progress))
        }
        is AttributeValue.Setting -> if (progress < 0.5) from else to
        is AttributeValue.Position -> {
            val o = from as? AttributeValue.Position
            if (o == null) {
                to
            } else {
                val pan = (o.pan.toInt() + (to.pan.toInt() - o.pan.toInt()) * progress).toInt().coerceIn(0, 255)
                val tilt = (o.tilt.toInt() + (to.tilt.toInt() - o.tilt.toInt()) * progress).toInt().coerceIn(0, 255)
                AttributeValue.Position(pan.toUByte(), tilt.toUByte())
            }
        }
    }

    /**
     * **A bundled emitter's own slider beats the colour's copy of it.** A Hex's white channel is
     * driven by two keys — `white`, and the W component of `rgbColour` — and the publish paths
     * write both (`ColourTarget.resetToFallback` writes the bundled channels unconditionally), so
     * without this the channel took whichever key happened to be published last. Here, where the
     * target's keys are composed, the fixture's RGB colour has each bundled component replaced by
     * its emitter's composed value, so the two keys carry one value and publish order cannot matter.
     *
     * The emitter wins **outright**: whichever cue, stack or layer each key came from, and however
     * each composed (UV is HTP, colour LTP, so "whose contributor ranks higher" has no single
     * answer for a blended bucket). It is the precedence `AttributeChannelWriter.coveringKeysByChannel`
     * already uses — the single-channel property over the aggregate — so provenance, Record and
     * the channel sheet all name the key whose value is on the channel.
     *
     * Done after composition, on the composed values, rather than in [cook]: a crossfading
     * emitter must hand the colour its *blended* value frame by frame.
     */
    private fun reconcileBundledEmitters(
        composed: HashMap<String, AttributeValue>,
        bundle: BundleBuckets,
    ) {
        val value = composed[bundle.colourAttribute] as? AttributeValue.Colour ?: return
        var colour = value.value
        for (bucket in bundle.emitters) {
            val level = (composed[bucket.attribute] as? AttributeValue.Slider)?.value ?: continue
            colour = when (bucket.contributors.first().category) {
                AttributeCategory.WHITE -> colour.copy(white = level)
                AttributeCategory.AMBER -> colour.copy(amber = level)
                AttributeCategory.UV -> colour.copy(uv = level)
                else -> colour
            }
        }
        if (colour != value.value) composed[bundle.colourAttribute] = AttributeValue.Colour(colour)
    }

    /**
     * The per-key transmit ramps for one [cook]: each key's winning row's
     * [Assignment.fadeDurationMs], **except** where no single row is the source of the composed
     * value.
     *
     * That exception is the whole point of routing this through the cook rather than reading
     * [Assignment.fadeDurationMs] straight off the winner map. [winners] is LTP-shaped — highest
     * (priority, fadeWeight), which [composeLtp] follows exactly — and under
     * [CompositionRule.HTP] with more than one contributor [composeHtp] ignores priority
     * altogether and takes a weight-scaled `max` or sum. The winner is then an *attribution*
     * approximation, which is fine for provenance and not fine for timing: cue A's 3 s fade
     * would time a value cue B supplied. So a genuinely blended HTP bucket snaps.
     *
     * A **single-contributor** HTP bucket does not snap, and must not: `composeHtp` returns that
     * one row's value, so it is the source. That case is the common one — `DIMMER` is HTP, so
     * treating every HTP bucket as sourceless would mean a Look's 2 s fade-up never faded.
     *
     * Keys asking for no ramp are absent rather than zero-valued; the publish paths read absence
     * as "snap".
     */
    fun fadeDurationsFor(cook: Cook, winners: Map<Key, Assignment>): Map<Key, Long> {
        if (winners.values.none { (it.fadeDurationMs ?: 0L) > 0L }) return emptyMap()
        val out = HashMap<Key, Long>()
        for (target in cook.targets) {
            for (bucket in target.attributes) {
                if (bucket.rule == CompositionRule.HTP && bucket.contributors.size > 1) continue
                val key = Key.fixture(target.targetKey, bucket.attribute)
                val fade = winners[key]?.fadeDurationMs ?: continue
                if (fade > 0L) out[key] = fade
            }
        }
        return out
    }

    /** One-shot [cook] + [compose] at the rows' own weights. */
    fun resolveIndexed(assignments: List<Assignment>): Map<String, Map<String, AttributeValue>> =
        compose(cook(assignments), FadeWeights.NONE)

    /**
     * [resolveIndexed] flattened to a per-[Key] map — for one-shot composition sites (cue
     * preview) and tests. The engine's publish path uses [compose] directly.
     */
    fun resolve(assignments: List<Assignment>): Map<Key, AttributeValue> =
        flattenIndexed(resolveIndexed(assignments))

    /**
     * Key for a ([target], [attribute]) pair. Resolver output is always
     * [TargetRef.Fixture] (group assignments are expanded upstream); pre-expansion callers
     * may use [TargetRef.Group].
     */
    data class Key(val target: TargetRef, val attribute: String) {
        val targetKey: String get() = target.key

        companion object {
            fun fixture(fixtureKey: String, attribute: String): Key =
                Key(TargetRef.Fixture(fixtureKey), attribute)

            fun group(groupKey: String, attribute: String): Key =
                Key(TargetRef.Group(groupKey), attribute)
        }
    }

    /**
     * [applySpecificity] within each replace group, and among the rows that compose by the key's rule
     * apart from them (busk-stack plan D7): a fixture row of a cue under a replacing stack must not drop
     * that stack's group row, which is its own floor.
     */
    private fun specificWithinGroups(contributors: List<Assignment>): List<Assignment> {
        if (contributors.none { it.replaceGroup != null }) return applySpecificity(contributors)
        return contributors.groupBy { it.replaceGroup }.values.flatMap { applySpecificity(it) }
    }

    /**
     * Within a (targetKey, attribute) bucket, drop group-expanded rows when any direct
     * fixture-level row is present — the direct row wins. If all rows share the same origin
     * (all direct, or all from group expansion), everything passes through.
     *
     * The no-group-rows fast path is load-bearing: this runs per composed key per crossfade
     * frame, and all-direct buckets (the common case) must not pay a list copy each time.
     */
    private fun applySpecificity(contributors: List<Assignment>): List<Assignment> {
        if (contributors.none { it.targetIsGroup }) return contributors
        val hasFixtureLevel = contributors.any { !it.targetIsGroup }
        return if (hasFixtureLevel) contributors.filter { !it.targetIsGroup } else contributors
    }

    /**
     * HTP: combines contributors so two independent cues at full weight take the highest
     * value (classic HTP), while contributors whose weights look like a crossfade pair
     * interpolate linearly. For [AttributeValue.Slider] / [AttributeValue.Setting]:
     *
     * - `Σ fadeWeight ≤ 1.0 + ε` → treated as a crossfade partition, composed as
     *   `Σ value × fadeWeight` (linear blend, no V-dip).
     * - `Σ fadeWeight > 1.0` → classical HTP max: `max(value × fadeWeight)` per contributor.
     *
     * [AttributeValue.Colour] maxes each R/G/B/W/A/UV independently. [AttributeValue.Position]
     * falls through to LTP — HTP isn't meaningful on axis values.
     */
    private fun composeHtp(contributors: List<Assignment>, weights: FadeWeights): AttributeValue {
        val first = contributors.first().value
        // Epsilon tolerates floating-point sums like `0.5 + 0.5 = 1.0000000000000002`.
        var weightSum = 0.0
        for (a in contributors) weightSum += weights.of(a)
        val isCrossfadeBlend = weightSum <= 1.0 + 1e-9
        return when (first) {
            is AttributeValue.Slider -> AttributeValue.Slider(
                composeHtpScalar(contributors, weights, isCrossfadeBlend) { (it.value as AttributeValue.Slider).value.toInt() }
            )
            is AttributeValue.Setting -> AttributeValue.Setting(
                composeHtpScalar(contributors, weights, isCrossfadeBlend) { (it.value as AttributeValue.Setting).channelValue.toInt() }
            )
            is AttributeValue.Colour -> {
                var r = 0; var g = 0; var b = 0
                var w = 0; var amber = 0; var uv = 0
                for (a in contributors) {
                    val c = (a.value as AttributeValue.Colour).value
                    val wf = weights.of(a)
                    r = maxOf(r, (c.color.red * wf).toInt().coerceIn(0, 255))
                    g = maxOf(g, (c.color.green * wf).toInt().coerceIn(0, 255))
                    b = maxOf(b, (c.color.blue * wf).toInt().coerceIn(0, 255))
                    w = maxOf(w, (c.white.toInt() * wf).toInt().coerceIn(0, 255))
                    amber = maxOf(amber, (c.amber.toInt() * wf).toInt().coerceIn(0, 255))
                    uv = maxOf(uv, (c.uv.toInt() * wf).toInt().coerceIn(0, 255))
                }
                AttributeValue.Colour(ExtendedColour(Color(r, g, b), w.toUByte(), amber.toUByte(), uv.toUByte()))
            }
            is AttributeValue.Position -> composeLtp(contributors, weights) // positions don't combine
        }
    }

    /** HTP scalar composition shared by Slider and Setting — see [composeHtp]. */
    private inline fun composeHtpScalar(
        contributors: List<Assignment>,
        weights: FadeWeights,
        isCrossfadeBlend: Boolean,
        extract: (Assignment) -> Int,
    ): UByte {
        val value = if (isCrossfadeBlend) {
            var acc = 0.0
            for (a in contributors) acc += extract(a) * weights.of(a)
            acc.toInt().coerceIn(0, 255)
        } else {
            var maxVal = 0
            for (a in contributors) {
                val scaled = (extract(a) * weights.of(a)).toInt().coerceIn(0, 255)
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
     * - Position: linear pan/tilt. A head moved in the dark is already where the incoming cue
     *   puts it, held there by its stack's Move in dark contributor (`MoveInDarkService`).
     *
     * When only one contributor is present, its value is returned unchanged regardless of
     * fadeWeight — the incoming-cue fade-in is handled at the caller / cue-apply level, not here.
     */
    private fun composeLtp(
        contributors: List<Assignment>,
        weights: FadeWeights,
    ): AttributeValue {
        val firstContributor = contributors.first()
        // Winner = highest priority; tie-break = highest fade weight (more recent). Hand-rolled
        // rather than `maxWithOrNull(compareBy(…))`: this runs per composed key per crossfade
        // frame, and the comparator would be a fresh capture of [weights] each time.
        var winner = firstContributor
        var winnerWeight = weights.of(firstContributor)
        for (i in 1 until contributors.size) {
            val a = contributors[i]
            val w = weights.of(a)
            if (a.priority > winner.priority || (a.priority == winner.priority && w > winnerWeight)) {
                winner = a
                winnerWeight = w
            }
        }

        // Any still-contributing contributor (fadeWeight > 0) other than the winner is treated as
        // an outgoing candidate — including steady-state weight=1.0. Crossfade start has
        // outgoing at exactly 1.0 and incoming (winner) at 0.0; the earlier `< 1.0` bound
        // would exclude the outgoing and snap-cut to the incoming value. End-of-fade is
        // guarded below by `winner.fadeWeight >= 1.0`.
        var outgoing: Assignment? = null
        for (a in contributors) {
            if (a === winner || weights.of(a) <= 0.0) continue
            if (outgoing == null || a.priority > outgoing.priority) outgoing = a
        }

        // No crossfade in flight — winner's value stands.
        if (outgoing == null || winnerWeight >= 1.0) return winner.value

        return interpolate(outgoing.value, winner.value, winnerWeight.coerceIn(0.0, 1.0))
    }
}
