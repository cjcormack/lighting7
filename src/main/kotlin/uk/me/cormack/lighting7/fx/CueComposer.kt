package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.awt.Color
import java.util.UUID
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.models.LayerSourceKind

private val logger = LoggerFactory.getLogger("CueComposer")

/**
 * One line of a cue's layer composition, resolved far enough to compose: what the layer applies plus
 * the layer's own operating parameters.
 *
 * [source] carries the uuid alongside the int id so [LookRegistry] and [TemplateRegistry] — both
 * uuid-keyed — need no second DB hit at apply time, and carries the *kind* because a layer can apply
 * either a Look or a template and the two resolve differently.
 */
internal data class CookLayer(
    val source: LayerSource,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    val propertyMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    val speedMasterUuid: UUID? = null,
    val rateSpeedMasterUuid: UUID? = null,
    val delayMs: Long? = null,
    val intervalMs: Long? = null,
    val randomWindowMs: Long? = null,
    /**
     * This *layer's* row id, which is what the fired-timed-layer set is keyed on.
     *
     * Distinct from `source.id` because one cue may legitimately layer the same Look twice — a
     * chase built from one Look at two delays is the obvious case. Keying the fired set on the
     * source id would make firing either of them include both, so the second layer's contribution
     * would appear at the first layer's delay.
     *
     * Defaults to `source.id` purely so a hand-built [CookLayer] in a test stays a one-liner; every
     * real layer comes from `DaoCueLayer.toCookLayer`, which passes the row id.
     */
    val layerId: Int = source.id,
) {
    /** True when this layer fires on a timer rather than at cue apply. */
    val isTimed: Boolean get() = delayMs != null || intervalMs != null
}

/**
 * Which layer produced a cooked row, for the consumers that need to name it rather than just use
 * its value.
 *
 * [index] is the position in the **sortOrder-sorted, enabled** layer list — not the position in the
 * caller's array, and not [layerId]. Two consumers depend on that specific number:
 *
 * - `ProgrammerLayerStack` stamps `ProgrammerStore.Slot.seq` as `LAYER_SEQ_BASE + index`, which is
 *   what makes layer order behave like write order in `FxTarget.composeProgrammerOver`'s
 *   cross-granularity recency comparison.
 * - provenance reports it so the operator can be told *which* layer won a key, rather than only
 *   that "a cue" or "the programmer" did.
 *
 * Public, unlike [CookLayer] beside it, only because it rides on the public
 * [CueAssignmentResolver.Assignment]. Nothing outside this module is expected to construct one.
 */
data class CookWinner(
    val index: Int,
    val layerId: Int,
    /** What the winning layer applies — a Look or a template, named. */
    val source: LayerSource,
)

/**
 * Which of a layer's *own* effects a higher stomping layer has switched off:
 * `layerId → targetKey → property names`.
 *
 * Read per tick by `FxEngine.isSuppressed` against an instance's `cueLayerId` /
 * `programmerLayerId`. The key space is the layer's, not the cue's — and the two id spaces
 * (`DaoCueLayer` row ids and `ProgrammerStore.mintLayerId`'s counter) are held in **separate** maps
 * on the engine rather than risking a collision in one.
 *
 * Public only because it appears in [FxEngine]'s signatures; it is a plain `Map` alias.
 */
typealias LayerStompSuppression = Map<Int, Map<String, Set<String>>>

/**
 * Everything one [CueComposer.cook] produced: the Layer 4 rows, plus the two derived signals that
 * **cannot be recovered from the rows afterwards**.
 *
 * Both come from the same fact — which `(target, property)` pairs each layer *asserted* — and the
 * rows keep only the winner per key. A layer that asserted a key and then lost it to a higher layer
 * still asserted it, and both signals depend on that:
 *
 * - [stompSuppression], because a stomping layer suppresses lower layers' effects on every property
 *   it asserts, won or not.
 * - [assertedKeys], because the *cue-level* (cross-cue) stomp overlap has to cover the whole
 *   surface the cue holds. Before the layer model that surface simply was the cue's local rows,
 *   which is why `buildStompOverlapFromAssignments` on its own stomped nothing on a cue whose
 *   colour came entirely from a layer.
 *
 * Returning them together rather than leaving `cook` to yield rows and offering the rest through a
 * second entry point is deliberate: a caller that publishes rows without the suppression map
 * silently reinstates the bug this type exists to fix, and there would be nothing to notice it.
 */
internal data class CookResult(
    val rows: List<CueAssignmentResolver.Assignment>,
    /** Empty unless some contributing layer has [CookLayer.stomp] set. */
    val stompSuppression: LayerStompSuppression,
    /**
     * Every `(targetKey, propertyName)` any contributing layer asserted — fixture keys, plus the
     * key of the group a row arrived *through*, because a group-targeted effect is matched on the
     * group's own name by [FxEngine.stompForCue] and would otherwise never overlap.
     */
    val assertedKeys: Set<FxEngine.PropertyKey>,
)

/**
 * Flattens a cue's ordered layer stack plus its local rows to **exactly one contributor per
 * (fixture, property)**, before [CueAssignmentResolver] ever sees it.
 *
 * ```
 * layers in sortOrder → local rows → cook → ONE contributor per (fixture,property) → resolver
 * ```
 *
 * ### Why cook rather than per-layer priorities
 *
 * The obvious alternative is to give each layer a distinct [CueAssignmentResolver.Assignment.priority]
 * — and there is even room, since `cueDerivedPriority` leaves 999 slots between cues. It does not
 * work, and the reason is decisive: **`composeHtp` ignores `priority` except on exact value ties.**
 * Per-layer priority would give ordered override for colour and position and leave dimmer on
 * `max()` — the exact category-dependent split this design exists to remove. Cooking is the only
 * way to get one rule for every category.
 *
 * What it buys:
 * - **Within-cue** = strict ordered override (plus blend / amount), independent of
 *   [PropertyCategory], explainable in one sentence.
 * - **Cross-cue** = untouched. All existing HTP/LTP, crossfade weighting and `moveInDark` logic
 *   keeps working, because the resolver still sees one contributor per cue per key — which is what
 *   it was written for.
 *
 * ### The constraint that cannot be layered away
 *
 * Effects are Layer 3 and values are Layer 4, so an effect sits above a static value regardless of
 * layer order. "Layer 2 sets colour statically, Layer 1 runs a colour effect" resolves to the
 * effect winning even though Layer 2 is later. Layer order governs values-vs-values and
 * effects-vs-effects, not the value/effect boundary.
 *
 * The escape hatch is per-layer [CookLayer.stomp], and it works by **suppression, not removal**:
 * [CookResult.stompSuppression] names the lower layers' `(target, property)` pairs the engine must
 * skip painting, and the reset pass has already put the cooked value there. Removal would be
 * unrecoverable — disabling the stomping layer, or pulling its amount to zero, only triggers a
 * recook, and a recook has no instance left to bring back.
 *
 * See `docs/plans/completed/looks-and-layers-plan.md` §3.3 and `docs/lighting-composition-model.md`.
 */

internal object CueComposer {

    /**
     * The accumulator entry — everything an [CueAssignmentResolver.Assignment] needs except the
     * cue id and priority, which are uniform across a cook.
     */
    private data class Contribution(
        val targetKey: String,
        val propertyName: String,
        val targetIsGroup: Boolean,
        val category: PropertyCategory,
        val compositionOverride: CompositionRule,
        val value: CueAssignmentResolver.PropertyValue,
        val moveInDark: Boolean = false,
        /** Which layer last wrote this key. Null once a local row has overlaid it. */
        val winner: CookWinner? = null,
        /**
         * The winning row's fade, or null to snap. Belongs to the *winner*, not the key: a MAX
         * blend that kept the value beneath still had the later layer decide the outcome, and
         * [winner] already records that reading.
         */
        val fadeDurationMs: Long? = null,
    )

    private data class Key(val targetKey: String, val propertyName: String)

    /**
     * The layers of a cue that actually contribute, in rank order.
     *
     * The one definition of "contributing", and deliberately so: it is asked three times —
     * [cook] (which numbers [CookWinner.index] off it), [cookEffects], and
     * `ProgrammerLayerStack.syncEffects` (which turns the same rank into an effect priority via
     * `priorityFor`). Those ranks have to agree, and while the predicate was written out three times
     * they only agreed by inspection: the programmer's copy omitted the timed clause. Harmless
     * there *only* because `ProgrammerLayer` carries no timing columns at all, so every layer it
     * cooks is immediate — which is exactly the kind of agreement that survives until someone gives
     * the programmer a timed layer.
     *
     * Three exclusions, each meaning "asserts nothing at all":
     * - **disabled** — the operator switched the layer off;
     * - **amount 0** — muted by pulling Amount to zero, which must silence its effects too, not
     *   just scale its values to nothing;
     * - **timed and unfired** — a timed layer asserts nothing until [CueTriggerManager] fires it,
     *   at which point its `layerId` arrives in [includeTimed] and the cue is re-cooked whole.
     *
     * A template layer is *not* excluded, and since the fx-templates plan it needs no special case
     * anywhere else either: it contributes values *or* one effect, and both halves are cooked from
     * the same [LayerContent] the Look arm uses.
     */
    fun contributingLayers(layers: List<CookLayer>, includeTimed: Set<Int> = emptySet()): List<CookLayer> =
        layers
            .filter { it.enabled && it.amount > 0.0 && (!it.isTimed || it.layerId in includeTimed) }
            .sortedBy { it.sortOrder }

    /**
     * Cook [layers] and [localRows] into one contributor per (fixture, property).
     *
     * [localRows] are the cue's own Layer 4 rows, already built by `buildCueAssignmentsForCue` —
     * so they arrive with `moveInDark` and group expansion already applied. They are overlaid **last and unconditionally**: the local layer
     * always wins.
     *
     * Timed layers ([CookLayer.isTimed]) are excluded here and contribute at fire time, matching
     * how timed preset applications behaved. `CueTriggerManager` re-cooks the whole cue with the
     * fired layers included rather than appending their rows, because appending would put two
     * contributors on one key and reopen exactly the ambiguity this function removes.
     *
     * **Invariant:** the returned list never holds two entries with the same
     * `(targetKey, propertyName)`. Enforced by construction (one map slot per key) and asserted by
     * `CueComposerTest`.
     *
     * Each returned row carries the layer that produced it in
     * [CueAssignmentResolver.Assignment.layerWinner]. **That cannot be recovered from the output
     * order**, which is why it rides on the row: the accumulator is keyed by (target, property) and
     * a key keeps the *insertion* position of whichever layer wrote it first, so a key introduced by
     * layer 1 and overwritten by layer 2 still sits where layer 1 put it.
     *
     * Output order is insertion order, not hash order. That matters: [CueAssignmentResolver.composeLtp]
     * breaks an exact `(priority, fadeWeight)` tie by taking the first maximal element in list
     * order, so a `HashMap` here would make cross-cue ties vary between republishes.
     */
    fun cook(
        fixtures: Fixtures,
        cueId: Int,
        priority: Int,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment>,
        /**
         * How a LOOK layer's `source.uuid` becomes a [LookSnapshot] — in production
         * `LookRegistry::snapshot`. A resolver rather than the registry itself because that is the
         * only thing cooking ever asks of it, so a test can pass a plain map lookup.
         *
         * Required, with no `{ null }` default, because failing to resolve is not an error here —
         * an unloadable layer is dropped with a warning — so an omitted resolver would cook a cue
         * to darkness and say only that each of its layers could not be loaded.
         */
        resolveLook: (UUID) -> LookSnapshot?,
        /** How a TEMPLATE layer's `source.uuid` becomes a [TemplateSnapshot]; see [resolveLook]. */
        resolveTemplate: (UUID) -> TemplateSnapshot?,
        includeTimed: Set<Int> = emptySet(),
    ): CookResult = cookInternal(
        fixtures, cueId, priority, layers, localRows, resolveLook, resolveTemplate, includeTimed,
        collectEffects = null,
    ).values

    /**
     * Everything one [cookAll] produced: the value half, the effect half, and the layer ranks the
     * two agree on.
     */
    internal data class FullCook(
        val values: CookResult,
        /** As [cookEffects] returns them; empty when the cook was asked for values only. */
        val effects: List<Triple<CookLayer, EffectEntry, TargetRef>>,
        /**
         * `layerId → rank` among the contributing layers — the same number [CookWinner.index]
         * carries, so a caller turning a rank into an effect priority cannot disagree with the rows.
         */
        val ranks: Map<Int, Int>,
    )

    /**
     * [cook] and [cookEffects] in **one pass over the layer stack** — sweep item C8.
     *
     * The programmer re-cooks on every stack mutation and needs both halves each time. Asking for
     * them separately walked the stack twice, sorted [contributingLayers] three times (once in each
     * cook plus once more to rank the effects) and read every layer's [LookSnapshot] out of the
     * registry twice — the second read being a genuine hazard, not just waste, since a Look edited
     * between the two would put rows and effects from different versions of it on stage together.
     *
     * [withEffects] false yields the values and the ranks with [FullCook.effects] empty, for a
     * caller that republishes values and deliberately leaves the running effects alone.
     */
    fun cookAll(
        fixtures: Fixtures,
        cueId: Int,
        priority: Int,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment>,
        resolveLook: (UUID) -> LookSnapshot?,
        resolveTemplate: (UUID) -> TemplateSnapshot?,
        includeTimed: Set<Int> = emptySet(),
        withEffects: Boolean = true,
    ): FullCook = cookInternal(
        fixtures, cueId, priority, layers, localRows, resolveLook, resolveTemplate, includeTimed,
        collectEffects = if (withEffects) ArrayList() else null,
    )

    /**
     * The one cook. [collectEffects] non-null asks for the effect triples alongside the rows, out of
     * the same resolved [LayerContent] the rows came from.
     */
    private fun cookInternal(
        fixtures: Fixtures,
        cueId: Int,
        priority: Int,
        layers: List<CookLayer>,
        localRows: List<CueAssignmentResolver.Assignment>,
        resolveLook: (UUID) -> LookSnapshot?,
        resolveTemplate: (UUID) -> TemplateSnapshot?,
        includeTimed: Set<Int>,
        collectEffects: MutableList<Triple<CookLayer, EffectEntry, TargetRef>>?,
    ): FullCook {
        val acc = LinkedHashMap<Key, Contribution>()

        // A CookWinner.index is a rank within the layers that actually contribute — which is what
        // the seq band and provenance both mean by it. A look that fails to load is dropped *after*
        // numbering, so one unreadable Look does not renumber the rest.
        val contributing = contributingLayers(layers, includeTimed)

        // What each contributing layer actually asserted, in rank order. Rank order rather than a
        // map keyed by layerId because "stomp" is a statement about *position*: a stomping layer
        // switches off the layers below it, and only the ordering knows which those are. A layer
        // whose Look failed to load is absent here, which is correct on both counts — it asserts
        // nothing, so it neither stomps nor needs suppressing.
        val asserted = ArrayList<LayerAssertions>(contributing.size)

        // Accumulated in the loop below rather than by a second pass over `contributing`: the ranks
        // are exactly the `(index, layer)` pairs it already walks. Stamped **before** the
        // unresolvable-layer skip, because a layer that failed to load still holds its rank — the
        // whole point of numbering ahead of the skip.
        val ranks = HashMap<Int, Int>(contributing.size)

        for ((index, layer) in contributing.withIndex()) {
            ranks[layer.layerId] = index
            val content = resolveContent(layer.source, resolveLook, resolveTemplate)
            if (content == null) {
                logger.warn(
                    "cue {}: {} '{}' ({}) could not be loaded — skipping layer",
                    cueId, layer.source.kind, layer.source.name, layer.source.uuid,
                )
                continue
            }
            // Expanded once and handed to both halves — see [LayerTargets].
            val layerTargets = LayerTargets(fixtures, cueId, layer)
            val keys = HashMap<String, MutableSet<String>>()
            applyLayer(fixtures, cueId, layer, index, content, acc, keys, layerTargets)
            asserted.add(LayerAssertions(layer.layerId, layer.stomp, keys))
            // Off the content already resolved above, and off [LayerContent.effects] rather than a
            // cast to the Look arm: a template layer contributes its one effect through exactly
            // this fan-out (D5), so there is no kind to test here any more.
            if (collectEffects != null) {
                effectsForLayer(fixtures, cueId, layer, content.effects, layerTargets, collectEffects)
            }
        }

        // The local layer always wins. Fixture-level rows beat group-derived ones for the same key,
        // the same specificity rule [CueAssignmentResolver.applySpecificity] applies — resolved here
        // because after cooking there is only one contributor left to carry the flag.
        for (row in localRows.sortedBy { if (it.targetIsGroup) 0 else 1 }) {
            acc[Key(row.targetKey, row.propertyName)] = Contribution(
                targetKey = row.targetKey,
                propertyName = row.propertyName,
                targetIsGroup = row.targetIsGroup,
                category = row.category,
                compositionOverride = row.compositionOverride,
                value = row.value,
                moveInDark = row.moveInDark,
                // Explicitly null: a local row belongs to no layer, and leaving a layer's winner
                // in place here would report the overwritten layer as the reason for the value.
                winner = null,
                // Pass-through, not null: the overlay must not silently discard a fade the caller
                // put on its own row, the way it deliberately discards the layer attribution.
                fadeDurationMs = row.fadeDurationMs,
            )
        }

        val rows = acc.values.map { c ->
            CueAssignmentResolver.Assignment(
                cueId = cueId,
                priority = priority,
                // Always 1.0: crossfade progress is applied per-cue by
                // [CueAssignmentLayer.updateFadeWeights] at publish time, never baked into a row.
                fadeWeight = 1.0,
                targetKey = c.targetKey,
                targetIsGroup = c.targetIsGroup,
                propertyName = c.propertyName,
                category = c.category,
                compositionOverride = c.compositionOverride,
                value = c.value,
                moveInDark = c.moveInDark,
                layerWinner = c.winner,
                fadeDurationMs = c.fadeDurationMs,
            )
        }
        return FullCook(
            values = CookResult(
                rows = rows,
                stompSuppression = buildStompSuppression(asserted),
                assertedKeys = asserted.flatMapTo(HashSet()) { layer ->
                    layer.keys.entries.flatMap { (targetKey, properties) ->
                        properties.map { FxEngine.PropertyKey(targetKey, it) }
                    }
                },
            ),
            effects = collectEffects ?: emptyList(),
            ranks = ranks,
        )
    }

    /** One contributing layer's identity, stomp flag and asserted `targetKey → properties`. */
    private class LayerAssertions(
        val layerId: Int,
        val stomp: Boolean,
        val keys: Map<String, Set<String>>,
    )

    /**
     * Turn rank-ordered assertions into per-layer suppression: for every stomping layer, every
     * layer **below** it loses its effects on every property the stomper asserts.
     *
     * Three boundaries, each deliberate:
     *
     * - **Strictly below.** A stomping layer does not suppress its own effects. Within one layer the
     *   Layer 3/4 order still holds — a Look holding both a colour row and a colour effect runs the
     *   effect — and a layer that switched off its own effect would have no way to express itself.
     * - **Layers only.** The cue's local rows and its ad-hoc effects belong to no layer, so a layer
     *   never stomps them. Local rows already beat every layer on values, and an ad-hoc effect sits
     *   alongside them rather than under the stack.
     * - **Coarse.** Every property the stomper asserts, not only the ones a lower layer's effect
     *   happens to be fighting over. `FU-LOOK-STOMP-GRANULAR` is the finer version, deliberately
     *   left until this one has been used on a rig.
     */
    private fun buildStompSuppression(asserted: List<LayerAssertions>): LayerStompSuppression {
        if (asserted.none { it.stomp }) return emptyMap()
        val out = HashMap<Int, MutableMap<String, MutableSet<String>>>()
        for ((rank, stomper) in asserted.withIndex()) {
            if (!stomper.stomp) continue
            if (stomper.keys.isEmpty()) continue
            for (below in asserted.subList(0, rank)) {
                val into = out.getOrPut(below.layerId) { HashMap() }
                for ((targetKey, properties) in stomper.keys) {
                    into.getOrPut(targetKey) { HashSet() }.addAll(properties)
                }
            }
        }
        return out
    }

    /**
     * The ordered (layer, effect, target) triples a cue's layers spawn, in layer order.
     *
     * **Spawning in this order is sufficient** — no priority arithmetic needed.
     * [FxEngine.sortedEffectsComparator] is `compareBy(priority, id)` with `id` a monotonic
     * creation counter, and per-tick composition is a genuine sequential fold through
     * [FxTarget.applyValue]. So same-priority effects already resolve last-created-wins, and layer
     * order becomes effect order for free.
     *
     * A deferred effect fans over the layer's targets; a bound effect uses its own target,
     * filtered by the layer's target set when that set is non-empty — the same rule rows follow.
     */
    fun cookEffects(
        fixtures: Fixtures,
        cueId: Int,
        layers: List<CookLayer>,
        /** How a LOOK layer's `source.uuid` becomes a [LookSnapshot]; see [cook]'s own parameter. */
        resolveLook: (UUID) -> LookSnapshot?,
        /**
         * How a TEMPLATE layer's `source.uuid` becomes a [TemplateSnapshot].
         *
         * Required rather than defaulted, and that is the point. This is the only cook the
         * timed-fire path uses ([CueTriggerManager]), so a default of `{ null }` would leave a
         * template effect firing on GO and silently *not* on a delayed or recurring layer — an
         * asymmetry that reads as a timing bug rather than a missing argument.
         */
        resolveTemplate: (UUID) -> TemplateSnapshot?,
        includeTimed: Set<Int> = emptySet(),
    ): List<Triple<CookLayer, EffectEntry, TargetRef>> {
        val out = ArrayList<Triple<CookLayer, EffectEntry, TargetRef>>()
        for (layer in contributingLayers(layers, includeTimed)) {
            val content = resolveContent(layer.source, resolveLook, resolveTemplate) ?: continue
            effectsForLayer(fixtures, cueId, layer, content.effects, LayerTargets(fixtures, cueId, layer), out)
        }
        return out
    }

    /**
     * One layer's effect triples, appended to [out].
     *
     * Shared by [cookEffects] and [cookAll] so the two cannot disagree about which effects a layer
     * spawns — the combined cook exists precisely to stop the programmer asking twice, and a second
     * copy of this fan-out would be a new way for the two answers to drift. Since the fx-templates
     * plan it is shared by the two *layer kinds* as well (D5): it takes [effects] rather than a
     * [LookSnapshot], so a template's one target-less effect goes down the same deferred arm a
     * Look's does and neither can acquire a fan-out of its own.
     */
    private fun effectsForLayer(
        fixtures: Fixtures,
        cueId: Int,
        layer: CookLayer,
        /** The layer's content's effects: a Look's, in `sortOrder`, or a template's single one. */
        effects: List<EffectEntry>,
        /** The layer's own target set, expanded once — see [LayerTargets]. */
        layerTargets: LayerTargets,
        out: MutableList<Triple<CookLayer, EffectEntry, TargetRef>>,
    ) {
        val refs = layerTargets.refs
        for (effect in effects) {
            val effectTarget = effect.target
            if (effectTarget == null) {
                if (refs.isEmpty()) {
                    logger.warn(
                        "cue {}: {} '{}' has a deferred effect but its layer names no targets — skipping",
                        cueId, layer.source.kind, layer.source.name,
                    )
                    continue
                }
                for (target in refs) out.add(Triple(layer, effect, target))
            } else {
                // A bound effect survives the layer's restriction only if the layer covers it.
                if (refs.isEmpty() || coversTarget(fixtures, layerTargets, effectTarget)) {
                    out.add(Triple(layer, effect, effectTarget))
                }
            }
        }
    }

    // ─── One layer ──────────────────────────────────────────────────────

    /**
     * What a layer actually applies, once resolved.
     *
     * The two arms differ in one place only — how a row's stored string becomes a value — which is
     * why they share [applyLayer] rather than getting a composer each. Everything else about a layer
     * (targets supplying a template's generic rows and filtering bound ones, mask, blend, amount,
     * stomp, specificity, assertion recording) is identical, and duplicating it is how the two would
     * drift.
     */
    private sealed interface LayerContent {
        /** Rows in `sortOrder`, in the shape [applyLayer] consumes. */
        val rows: List<SourceRow>

        /**
         * The effects this content contributes, in the shape [effectsForLayer] consumes.
         *
         * On the interface rather than reached by a cast to [OfLook], which is what it was before
         * a template could hold one: the cast *was* the "templates hold no effects" rule, and with
         * that rule reversed (D5) both arms answer here and the cook has no kind to test.
         */
        val effects: List<EffectEntry>

        /** A Look: literal values and its own effects. */
        class OfLook(val look: LookSnapshot) : LayerContent {
            override val rows: List<SourceRow> = look.rows.map {
                // A Look carries its fade **per row**, so two rows of one Look can move at
                // different speeds — the reason [SourceRow] holds the field rather than
                // [LayerContent] doing.
                SourceRow(it.target, it.propertyName, it.value, it.elementKey, it.fadeDurationMs)
            }

            /** Bound or deferred, in `sortOrder`; [effectsForLayer] tells the two apart. */
            override val effects: List<EffectEntry> = look.effects
        }

        /** A template: [TemplateIntent]s, resolved per head by [TemplateResolver]. */
        class OfTemplate(val template: TemplateSnapshot) : LayerContent {
            // A template has no element rows by construction — the column does not exist — so the
            // element skip in `applyLayer` simply never fires for this arm.
            override val rows: List<SourceRow> = template.rows.map {
                // A template's fade sits on the template, not the row (see
                // `DaoTemplates.fadeDurationMs`): it is one gesture, so every row it writes moves
                // together. Copying it onto each row is what lets `applyLayer` treat both arms alike.
                SourceRow(it.target, it.propertyName, it.value, elementKey = null, template.fadeDurationMs)
            }

            /**
             * Zero or one, and always target-less (D3), so [effectsForLayer]'s deferred arm fans it
             * over the layer's targets — which is the whole of what an effect template means.
             *
             * D1 makes this and [rows] mutually exclusive, so a template layer contributes to one
             * half of the cook or the other, never both.
             */
            override val effects: List<EffectEntry> = listOfNotNull(template.effect)
        }
    }

    /** One stored row, whichever kind of source it came from. */
    private class SourceRow(
        /**
         * Null only for a **generic template row** — a Look row is always bound (sweep item B6),
         * so [LayerContent.OfLook] never produces one.
         */
        val target: TargetRef?,
        val propertyName: String,
        val value: String,
        val elementKey: String?,
        /**
         * How long this row's value should take to arrive, or null to snap.
         *
         * Carried through the cook because a *tracked* source has to fade like an *applied* one:
         * clicking a template writes literals into the programmer with the template's fade
         * (`applyTemplateToProgrammer`), and ⌥clicking the same chip adds a layer that tracks it —
         * which used to snap, because the field stopped here (sweep item B1).
         */
        val fadeDurationMs: Long?,
    )

    private fun resolveContent(
        source: LayerSource,
        resolveLook: (UUID) -> LookSnapshot?,
        resolveTemplate: (UUID) -> TemplateSnapshot?,
    ): LayerContent? = when (source.kind) {
        LayerSourceKind.LOOK -> resolveLook(source.uuid)?.let { LayerContent.OfLook(it) }
        LayerSourceKind.TEMPLATE -> resolveTemplate(source.uuid)?.let { LayerContent.OfTemplate(it) }
    }

    /** A single (fixture, property) contribution a layer wants to make, before blending. */
    private class Pending(
        val fixture: Fixture,
        val propertyName: String,
        val rawValue: String,
        /** The group this contribution arrived through, or null when the row named the fixture. */
        val groupKey: String?,
        /** The originating row's [SourceRow.fadeDurationMs]. */
        val fadeDurationMs: Long?,
        /**
         * The originating row's parsed [TemplateIntent], for a template layer; null for a Look.
         *
         * Parsed once **per row** rather than once per head (sweep item C8): the string belongs to
         * the row, so a template row fanned over a 24-head group used to parse the same value 24
         * times. Non-null exactly when the layer is a template — a row whose value is not an
         * intent is dropped whole, before it fans out.
         */
        val intent: TemplateIntent?,
    ) {
        val isGroupOrigin: Boolean get() = groupKey != null
    }

    private fun applyLayer(
        fixtures: Fixtures,
        cueId: Int,
        layer: CookLayer,
        /** Rank among the contributing layers — see [CookWinner.index]. */
        layerIndex: Int,
        content: LayerContent,
        acc: LinkedHashMap<Key, Contribution>,
        /**
         * Collects `targetKey → properties` for everything this layer actually asserted — after the
         * mask, the property lookup and the value parse, so a masked-out or unparsable row is not
         * recorded as an assertion. See [CookResult] for the two things that read it.
         */
        asserted: MutableMap<String, MutableSet<String>>,
        /** The layer's own target set, expanded once by the caller — see [LayerTargets]. */
        layerTargets: LayerTargets,
    ) {
        val mask = try {
            parseMaskGroups(layer.propertyMask?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() })
        } catch (e: IllegalArgumentException) {
            logger.warn("cue {}: layer on '{}' has an unparsable mask — treating as unmasked: {}", cueId, layer.source.name, e.message)
            null
        }
        val blendMode = parseLayerBlendMode(layer.blendMode, layer.source.name, cueId)
        val amount = layer.amount.coerceIn(0.0, 1.0)

        // The layer's targets both *supply* targets to a template's generic rows and *filter* bound
        // ones — one meaning serving two jobs, and what lets the migration preserve coverage exactly.
        val layerFixtures: List<Expanded>? = layerTargets.expanded

        // Memoised out of the row loop (sweep item C8): the rows of one source overwhelmingly name
        // the same handful of targets, so a 60-row Look re-expanded the same group 60 times — and
        // this also collapses the "group missing" warning to one line per target rather than one
        // per row. The allowed-set moved further out still, onto [LayerTargets], because the effect
        // half asks the same question of the same targets.
        val rowExpansions = HashMap<TargetRef, List<Expanded>>()

        val pending = ArrayList<Pending>()
        for (row in content.rows) {
            // Element-scoped rows are handled by the caller-side element path; they never reach the
            // per-fixture accumulator because an element is not a (fixture, property) key.
            if (row.elementKey != null) continue
            // A template row's intent is the row's, not the head's — parse it here and drop the
            // whole row if it is not one, rather than repeating the parse and the warning per head.
            val intent = if (content is LayerContent.OfTemplate) {
                parseTemplateIntent(row.value) ?: run {
                    logger.warn(
                        "cue {}: template '{}' — '{}' is not an intent for '{}' — skipping row",
                        cueId, layer.source.name, row.value, row.propertyName,
                    )
                    continue
                }
            } else {
                null
            }
            val rowTarget = row.target
            if (rowTarget == null) {
                // A **generic template row** — the only kind that reaches this arm now that a Look
                // row is always bound (sweep item B6). The message still names `source.kind` rather
                // than hard-coding "template": the arm is reached through a nullable field, and a
                // warning that lies about which entity it came from is worse than one word of
                // generality.
                if (layerFixtures == null) {
                    logger.warn(
                        "cue {}: {} '{}' has a row for '{}' that takes its targets from the layer, " +
                            "but the layer names none — skipping",
                        cueId, layer.source.kind, layer.source.name, row.propertyName,
                    )
                    continue
                }
                for (e in layerFixtures) {
                    pending.add(Pending(e.fixture, row.propertyName, row.value, e.groupKey, row.fadeDurationMs, intent))
                }
            } else {
                val rowFixtures = rowExpansions.getOrPut(rowTarget) {
                    expandTargets(fixtures, cueId, layer, listOf(rowTarget))
                }
                for (e in rowFixtures) {
                    if (layerFixtures != null && e.fixture.key !in layerTargets.keys) continue
                    pending.add(Pending(e.fixture, row.propertyName, row.value, e.groupKey, row.fadeDurationMs, intent))
                }
            }
        }

        // Group-origin contributions first, then fixture-origin, so a source holding both a group
        // row and a fixture row for one member resolves fixture-wins — the same rule
        // [LookRegistry.expand] applies. `sortedBy` is stable, so row order survives within each
        // bucket.
        for (p in pending.sortedBy { if (it.isGroupOrigin) 0 else 1 }) {
            // A template row resolves per head **first**, because resolution is what decides which
            // property carries the value on this head — the MAC 250's colour is a wheel called
            // `colour`, which `canonicalPropertyName` rewrites to `rgbColour` and then misses. So
            // the resolved name is what the mask, the category lookup and the accumulator key use.
            val pendingIntent = p.intent
            val resolved: TemplateResolver.Resolution? =
                if (pendingIntent == null) {
                    null
                } else {
                    val resolution = TemplateResolver.resolve(p.fixture, p.propertyName, pendingIntent)
                    if (!resolution.isSupported) {
                        // **Debug, not warn.** A head that cannot take the intent is the normal
                        // case for a template pointed at a mixed rig — a PAR with no pan is not a
                        // fault, and warning per head per cook would bury the real problems. The
                        // editor's "resolves to" panel is where an operator is told, before saving.
                        logger.debug(
                            "cue {}: template '{}' — {} cannot take {} ({})",
                            cueId, layer.source.name, p.fixture.key, p.propertyName, resolution.note,
                        )
                        continue
                    }
                    resolution
                }
            // **Not canonicalised for a template.** `TemplateResolver` already answered with the
            // head's own property name, and canonicalising it would undo exactly the work it did:
            // `canonicalPropertyName("colour")` is `"rgbColour"`, so the MAC 250's colour *wheel*
            // would be looked up under a name it does not have and the head would silently drop out
            // of every colour template. The Look path still canonicalises, because a stored Look row
            // may spell the property any of the three ways.
            val canonical = resolved?.propertyName ?: canonicalPropertyName(p.propertyName)
            if (mask != null && !maskAllows(mask, maskGroupForProperty(p.fixture, canonical))) continue
            val categoryInfo = fixtureCategoryFor(p.fixture, canonical)
            if (categoryInfo == null) {
                logger.warn(
                    "cue {}: {} '{}' — property '{}' not on '{}' — skipping",
                    cueId, layer.source.kind, layer.source.name, p.propertyName, p.fixture.key,
                )
                continue
            }
            val (category, override) = categoryInfo
            val incoming = resolved?.value
                ?: CueAssignmentResolver.parseAssignmentValue(category, canonical, p.rawValue)
            if (incoming == null) {
                logger.warn(
                    "cue {}: {} '{}' — invalid value '{}' for {}.{} — skipping",
                    cueId, layer.source.kind, layer.source.name, p.rawValue, p.fixture.key, p.propertyName,
                )
                continue
            }

            // Recorded here, past every skip above, so an assertion means "this layer really put a
            // value on that key". The group alias goes in alongside the fixture key rather than
            // instead of it: within-cue suppression is checked per member (see
            // `FxEngine.processGroupEffect`), the cross-cue overlap on the effect's own target.
            asserted.getOrPut(p.fixture.key) { HashSet() }.add(canonical)
            p.groupKey?.let { asserted.getOrPut(it) { HashSet() }.add(canonical) }

            val key = Key(p.fixture.key, canonical)
            val below = acc[key]?.value
            acc[key] = Contribution(
                targetKey = p.fixture.key,
                propertyName = canonical,
                targetIsGroup = p.isGroupOrigin,
                category = category,
                compositionOverride = override,
                value = blend(below, incoming, blendMode, amount),
                // The *last* layer to write a key owns it, blend mode notwithstanding: a MAX layer
                // that kept the value beneath still decided the outcome, so it is the honest answer
                // to "why is this fixture like this?".
                winner = CookWinner(layerIndex, layer.layerId, layer.source),
                fadeDurationMs = p.fadeDurationMs,
            )
        }
    }

    /**
     * One expanded target: the fixture, and the group it came through if it came through one.
     *
     * Carries the group's *key* rather than the old boolean because the stomp overlap needs to name
     * it — an effect whose target is a group is matched on the group's own name.
     */
    private class Expanded(val fixture: Fixture, val groupKey: String?)

    /**
     * A layer's own target set, expanded **once per layer** and shared by both halves of the cook.
     *
     * The layer's targets serve three jobs — they supply targets to a template's generic rows,
     * filter bound ones, and decide whether a bound *effect* survives ([coversTarget]) — and each of
     * the three used to expand them for itself, so a Look layer with rows and effects walked its
     * groups twice per cook on top of the per-row rebuild (sweep item C8).
     *
     * Both derived forms are computed **on first ask and only then**. A layer whose effects are all
     * deferred never expands at all, which is what keeps a bare [cookEffects] from paying
     * for a Look that holds no bound effect — and, since [expandTargets] warns about a missing
     * group, keeps it from warning about one it had no reason to look up.
     *
     * Plain nullable fields rather than `by lazy`: this object is already the once-per-layer
     * allocation, and two `Lazy` delegates would add two more per layer for nothing.
     */
    private class LayerTargets(
        private val fixtures: Fixtures,
        private val cueId: Int,
        private val layer: CookLayer,
    ) {
        val refs: List<TargetRef> = layer.targets.map { it.target }

        private var expandedCache: List<Expanded>? = null

        /**
         * The layer's targets as fixtures, or null when the layer names none.
         *
         * Null is "unrestricted" — a different statement from naming targets that all resolved to
         * nothing, which yields an empty list and restricts everything away.
         */
        val expanded: List<Expanded>?
            get() {
                if (refs.isEmpty()) return null
                return expandedCache
                    ?: expandTargets(fixtures, cueId, layer, refs).also { expandedCache = it }
            }

        private var keysCache: Set<String>? = null

        /**
         * [expanded] as a fixture-key set — the allowed-set for bound rows and the coverage set for
         * bound effects, which are the same question asked twice.
         */
        val keys: Set<String>
            get() = keysCache
                ?: (expanded?.mapTo(HashSet()) { it.fixture.key } ?: emptySet<String>())
                    .also { keysCache = it }
    }

    /**
     * Expand targets to fixtures, remembering the originating group. Missing groups and fixtures are
     * logged at warn and skipped — stale data must not break cue apply.
     */
    private fun expandTargets(
        fixtures: Fixtures,
        cueId: Int,
        layer: CookLayer,
        targets: List<TargetRef>,
    ): List<Expanded> {
        val out = ArrayList<Expanded>()
        for (target in targets) {
            when (target) {
                is TargetRef.Group -> {
                    val group = try {
                        fixtures.untypedGroup(target.key)
                    } catch (_: IllegalStateException) {
                        logger.warn("cue {}: layer on '{}' — group '{}' missing — skipping", cueId, layer.source.name, target.key)
                        continue
                    }
                    val members = group.fixtures.filterIsInstance<Fixture>()
                    if (members.isEmpty()) {
                        logger.warn("cue {}: layer on '{}' — group '{}' has no Fixture members — skipping", cueId, layer.source.name, target.key)
                        continue
                    }
                    for (member in members) out.add(Expanded(member, target.key))
                }
                is TargetRef.Fixture -> {
                    val fixture = try {
                        fixtures.untypedFixture(target.key)
                    } catch (_: IllegalStateException) {
                        logger.warn("cue {}: layer on '{}' — fixture '{}' missing — skipping", cueId, layer.source.name, target.key)
                        continue
                    }
                    out.add(Expanded(fixture, null))
                }
            }
        }
        return out
    }

    /**
     * True when the layer covers [target] — by naming it, or by naming a group containing it.
     *
     * [LayerTargets] is what makes this cheap: the layer's targets are expanded once for the whole
     * cook, and [LayerTargets.keys] is not built at all unless a bound effect actually reaches past
     * the name match below.
     */
    private fun coversTarget(
        fixtures: Fixtures,
        layerTargets: LayerTargets,
        target: TargetRef,
    ): Boolean {
        // Kept ahead of the expansion, and not only as a shortcut: a layer naming a *missing* group
        // with a bound effect on that same group is covered by this check and by nothing below it,
        // where the expansion answers "no members" and would drop the effect here instead of
        // letting the spawner report the missing group.
        if (target in layerTargets.refs) return true
        val targetKeys = when (target) {
            is TargetRef.Fixture -> setOf(target.key)
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
                ?.fixtures?.filterIsInstance<Fixture>()?.mapTo(HashSet()) { it.key }
                ?: return false
        }
        if (targetKeys.isEmpty()) return false
        return targetKeys.any { it in layerTargets.keys }
    }

    // ─── Blending ───────────────────────────────────────────────────────

    /**
     * Parse a layer's stored blend mode, reusing the effect vocabulary ([BlendMode]) rather than
     * declaring a near-identical second enum. `ADDITIVE` therefore comes along for free; the
     * plan's four modes are the subset the UI offers.
     */
    private fun parseLayerBlendMode(raw: String, sourceName: String, cueId: Int): BlendMode =
        EffectSpecCoercion.Lenient.blendMode(raw) { "cue $cueId layer on '$sourceName'" }

    /**
     * Combine [incoming] over [below] under [mode], then mix that result back over [below] by
     * [amount] — the grandMA3 Amount shape.
     *
     * When nothing is beneath, [below] stands in as the mode's **identity**: zero for
     * OVERRIDE / MAX / ADDITIVE, full-scale for MIN / MULTIPLY. That is what makes a lone dimmer
     * layer at amount 0.5 read as half intensity (mix from 0) while a lone MULTIPLY layer at
     * amount 1.0 reads as its own value (mix from 255), both of which are what an operator expects.
     *
     * Positions have no meaningful identity — halving a pan/tilt aims at a corner rather than
     * halfway — so a lone position layer stands at its own value whatever the amount, and only
     * interpolates once there is a real value beneath it.
     */
    private fun blend(
        below: CueAssignmentResolver.PropertyValue?,
        incoming: CueAssignmentResolver.PropertyValue,
        mode: BlendMode,
        amount: Double,
    ): CueAssignmentResolver.PropertyValue {
        val base = below?.takeIf { it::class == incoming::class } ?: identityFor(incoming, mode)
        val combined = combine(base, incoming, mode)
        return mix(base, combined, amount)
    }

    private fun identityFor(
        like: CueAssignmentResolver.PropertyValue,
        mode: BlendMode,
    ): CueAssignmentResolver.PropertyValue {
        val full = mode == BlendMode.MIN || mode == BlendMode.MULTIPLY
        return when (like) {
            is CueAssignmentResolver.PropertyValue.Slider ->
                CueAssignmentResolver.PropertyValue.Slider(if (full) 255u else 0u)
            is CueAssignmentResolver.PropertyValue.Setting ->
                CueAssignmentResolver.PropertyValue.Setting(if (full) 255u else 0u)
            is CueAssignmentResolver.PropertyValue.Colour ->
                CueAssignmentResolver.PropertyValue.Colour(
                    if (full) ExtendedColour(Color(255, 255, 255), 255u, 255u, 255u)
                    else ExtendedColour(Color(0, 0, 0), 0u, 0u, 0u)
                )
            // No identity — see [blend].
            is CueAssignmentResolver.PropertyValue.Position -> like
        }
    }

    private fun combine(
        base: CueAssignmentResolver.PropertyValue,
        incoming: CueAssignmentResolver.PropertyValue,
        mode: BlendMode,
    ): CueAssignmentResolver.PropertyValue {
        if (mode == BlendMode.OVERRIDE) return incoming
        fun scalar(a: Int, b: Int): Int = when (mode) {
            BlendMode.MAX -> maxOf(a, b)
            BlendMode.MIN -> minOf(a, b)
            BlendMode.MULTIPLY -> a * b / 255
            BlendMode.ADDITIVE -> a + b
            BlendMode.OVERRIDE -> b
        }.coerceIn(0, 255)

        return when (incoming) {
            is CueAssignmentResolver.PropertyValue.Slider -> {
                val a = (base as CueAssignmentResolver.PropertyValue.Slider).value.toInt()
                CueAssignmentResolver.PropertyValue.Slider(scalar(a, incoming.value.toInt()).toUByte())
            }
            // Discrete channels have no arithmetic meaning, but they *are* single-byte channel
            // values — the resolver's own comment notes the Setting/Slider split is only a
            // labelling choice — so they combine numerically rather than being a special case.
            is CueAssignmentResolver.PropertyValue.Setting -> {
                val a = (base as CueAssignmentResolver.PropertyValue.Setting).channelValue.toInt()
                CueAssignmentResolver.PropertyValue.Setting(scalar(a, incoming.channelValue.toInt()).toUByte())
            }
            is CueAssignmentResolver.PropertyValue.Colour -> {
                val a = (base as CueAssignmentResolver.PropertyValue.Colour).value
                val b = incoming.value
                CueAssignmentResolver.PropertyValue.Colour(
                    ExtendedColour(
                        Color(
                            scalar(a.color.red, b.color.red),
                            scalar(a.color.green, b.color.green),
                            scalar(a.color.blue, b.color.blue),
                        ),
                        scalar(a.white.toInt(), b.white.toInt()).toUByte(),
                        scalar(a.amber.toInt(), b.amber.toInt()).toUByte(),
                        scalar(a.uv.toInt(), b.uv.toInt()).toUByte(),
                    )
                )
            }
            // Axis values don't combine — MAX of two positions is a third position nobody asked
            // for. Ordered override is the only sane rule, and it is what LTP does anyway.
            is CueAssignmentResolver.PropertyValue.Position -> incoming
        }
    }

    private fun mix(
        base: CueAssignmentResolver.PropertyValue,
        combined: CueAssignmentResolver.PropertyValue,
        amount: Double,
    ): CueAssignmentResolver.PropertyValue {
        if (amount >= 1.0) return combined
        fun lerp(a: Int, b: Int): Int = (a + (b - a) * amount).toInt().coerceIn(0, 255)
        return when (combined) {
            is CueAssignmentResolver.PropertyValue.Slider -> {
                val a = (base as CueAssignmentResolver.PropertyValue.Slider).value.toInt()
                CueAssignmentResolver.PropertyValue.Slider(lerp(a, combined.value.toInt()).toUByte())
            }
            // Discrete — snap at the halfway point, the same rule [CueAssignmentResolver.composeLtp]
            // uses for a Setting mid-crossfade. Interpolating an enum index is meaningless.
            is CueAssignmentResolver.PropertyValue.Setting -> if (amount < 0.5) base else combined
            is CueAssignmentResolver.PropertyValue.Colour -> {
                val a = (base as CueAssignmentResolver.PropertyValue.Colour).value
                CueAssignmentResolver.PropertyValue.Colour(blendExtendedColours(a, combined.value, amount))
            }
            is CueAssignmentResolver.PropertyValue.Position -> {
                val a = base as CueAssignmentResolver.PropertyValue.Position
                CueAssignmentResolver.PropertyValue.Position(
                    lerp(a.pan.toInt(), combined.pan.toInt()).toUByte(),
                    lerp(a.tilt.toInt(), combined.tilt.toInt()).toUByte(),
                )
            }
        }
    }
}
