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
    )

    private data class Key(val targetKey: String, val propertyName: String)

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
        cascade: PaletteCascade = PaletteCascade.EMPTY,
        lookRegistry: LookRegistry? = null,
        templateRegistry: TemplateRegistry? = null,
        includeTimed: Set<Int> = emptySet(),
        /**
         * How a LOOK layer's `source.uuid` becomes a [LookSnapshot]. Defaults to [lookRegistry],
         * which is what every cue path wants.
         *
         * The programmer overrides it for one case the registry cannot serve: the Look editor's
         * **live preview** is an *unsaved* draft, so it has no row to load and no uuid the registry
         * has ever heard of. Passing a resolver keeps that a layer like any other — composing above
         * the real stack, blending and masking by the same rules — instead of a second write path
         * with its own precedence.
         */
        resolveLook: (UUID) -> LookSnapshot? = { lookRegistry?.snapshot(it) },
        /**
         * How a TEMPLATE layer's `source.uuid` becomes a [TemplateSnapshot].
         *
         * Separate from [resolveLook] rather than one polymorphic resolver, so the live-preview
         * override above keeps working untouched: it substitutes an unsaved *Look*, and folding the
         * two would make every caller of that override state a template resolver it has no opinion
         * about.
         */
        resolveTemplate: (UUID) -> TemplateSnapshot? = { templateRegistry?.snapshot(it) },
    ): CookResult {
        val acc = LinkedHashMap<Key, Contribution>()

        // Indexed over the *filtered and sorted* list, so a CookWinner.index is a rank within the
        // layers that actually contribute — which is what the seq band and provenance both mean by
        // it. Disabled, amount-0 and unfired-timed layers are dropped before numbering; a look that
        // fails to load is dropped after, so one unreadable Look does not renumber the rest.
        val contributing = layers
            .filter { it.enabled && it.amount > 0.0 && (!it.isTimed || it.layerId in includeTimed) }
            .sortedBy { it.sortOrder }

        // What each contributing layer actually asserted, in rank order. Rank order rather than a
        // map keyed by layerId because "stomp" is a statement about *position*: a stomping layer
        // switches off the layers below it, and only the ordering knows which those are. A layer
        // whose Look failed to load is absent here, which is correct on both counts — it asserts
        // nothing, so it neither stomps nor needs suppressing.
        val asserted = ArrayList<LayerAssertions>(contributing.size)

        for ((index, layer) in contributing.withIndex()) {
            val content = resolveContent(layer.source, resolveLook, resolveTemplate)
            if (content == null) {
                logger.warn(
                    "cue {}: {} '{}' ({}) could not be loaded — skipping layer",
                    cueId, layer.source.kind, layer.source.name, layer.source.uuid,
                )
                continue
            }
            val keys = HashMap<String, MutableSet<String>>()
            applyLayer(fixtures, cueId, layer, index, content, cascade, acc, keys)
            asserted.add(LayerAssertions(layer.layerId, layer.stomp, keys))
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
            )
        }

        val rows = acc.values.map { c ->
            CueAssignmentResolver.Assignment(
                cueId = cueId,
                priority = priority,
                // Always 1.0: crossfade progress is applied per-cue by
                // [FxEngine.updateCueFadeWeights] at publish time, never baked into a row.
                fadeWeight = 1.0,
                targetKey = c.targetKey,
                targetIsGroup = c.targetIsGroup,
                propertyName = c.propertyName,
                category = c.category,
                compositionOverride = c.compositionOverride,
                value = c.value,
                moveInDark = c.moveInDark,
                layerWinner = c.winner,
            )
        }
        return CookResult(
            rows = rows,
            stompSuppression = buildStompSuppression(asserted),
            assertedKeys = asserted.flatMapTo(HashSet()) { layer ->
                layer.keys.entries.flatMap { (targetKey, properties) ->
                    properties.map { FxEngine.PropertyKey(targetKey, it) }
                }
            },
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
        lookRegistry: LookRegistry?,
        includeTimed: Set<Int> = emptySet(),
        /** As [cook]'s — see there for the one caller that overrides it. */
        resolveLook: (UUID) -> LookSnapshot? = { lookRegistry?.snapshot(it) },
    ): List<Triple<CookLayer, LookEffectEntry, TargetRef>> {
        val out = ArrayList<Triple<CookLayer, LookEffectEntry, TargetRef>>()
        for (layer in layers.filter { it.enabled }.sortedBy { it.sortOrder }) {
            if (layer.isTimed && layer.layerId !in includeTimed) continue
            // Same rule as [cook]: an amount-0 layer contributes nothing at all. Without this an
            // operator who muted a layer by pulling Amount to zero would still see its effects run.
            if (layer.amount <= 0.0) continue
            // Templates hold no effects at all (D7 — effects live in a Look or on a cue), so a
            // template layer contributes nothing here rather than being resolved and found empty.
            if (layer.source.isTemplate) continue
            val look = resolveLook(layer.source.uuid) ?: continue
            val layerTargets = layer.targets.map { it.target }
            for (effect in look.effects) {
                val effectTarget = effect.target
                if (effectTarget == null) {
                    if (layerTargets.isEmpty()) {
                        logger.warn(
                            "cue {}: look '{}' has a deferred effect but its layer names no targets — skipping",
                            cueId, layer.source.name,
                        )
                        continue
                    }
                    for (target in layerTargets) out.add(Triple(layer, effect, target))
                } else {
                    // A bound effect survives the layer's restriction only if the layer covers it.
                    if (layerTargets.isEmpty() || coversTarget(fixtures, layerTargets, effectTarget)) {
                        out.add(Triple(layer, effect, effectTarget))
                    }
                }
            }
        }
        return out
    }

    // ─── One layer ──────────────────────────────────────────────────────

    /**
     * What a layer actually applies, once resolved.
     *
     * The two arms differ in one place only — how a row's stored string becomes a value — which is
     * why they share [applyLayer] rather than getting a composer each. Everything else about a layer
     * (targets supplying deferred rows and filtering bound ones, mask, blend, amount, stomp,
     * specificity, assertion recording) is identical, and duplicating it is how the two would drift.
     */
    private sealed interface LayerContent {
        /** Rows in `sortOrder`, in the shape [applyLayer] consumes. */
        val rows: List<SourceRow>

        /** A Look: literals, plus its own positional colour list as the cascade's narrowest scope. */
        class OfLook(val look: LookSnapshot) : LayerContent {
            override val rows: List<SourceRow> = look.rows.map {
                SourceRow(it.target, it.propertyName, it.value, it.elementKey)
            }
        }

        /** A template: [TemplateIntent]s, resolved per head by [TemplateResolver]. */
        class OfTemplate(val template: TemplateSnapshot) : LayerContent {
            // A template has no element rows by construction — the column does not exist — so the
            // element skip in `applyLayer` simply never fires for this arm.
            override val rows: List<SourceRow> = template.rows.map {
                SourceRow(it.target, it.propertyName, it.value, elementKey = null)
            }
        }
    }

    /** One stored row, whichever kind of source it came from. */
    private class SourceRow(
        val target: TargetRef?,
        val propertyName: String,
        val value: String,
        val elementKey: String?,
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
        baseCascade: PaletteCascade,
        acc: LinkedHashMap<Key, Contribution>,
        /**
         * Collects `targetKey → properties` for everything this layer actually asserted — after the
         * mask, the property lookup and the value parse, so a masked-out or unparsable row is not
         * recorded as an assertion. See [CookResult] for the two things that read it.
         */
        asserted: MutableMap<String, MutableSet<String>>,
    ) {
        val mask = try {
            parseMaskGroups(layer.propertyMask?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() })
        } catch (e: IllegalArgumentException) {
            logger.warn("cue {}: layer on '{}' has an unparsable mask — treating as unmasked: {}", cueId, layer.source.name, e.message)
            null
        }
        val blendMode = parseLayerBlendMode(layer.blendMode, layer.source.name, cueId)
        val amount = layer.amount.coerceIn(0.0, 1.0)

        // The Look's own positional colour list is the most specific cascade scope, which is what
        // `PaletteCascade.look` is (called `preset` until session 4 — the name lagged the merge).
        // A Look row's literal may itself be "P1", which is why the cascade has to be threaded
        // through here at all rather than resolved once per cue.
        // A template has no positional colour list of its own — it is not a `PaletteCascade` scope —
        // so a template layer composes against the cue's and the global one unchanged.
        val effectivePalette = when (content) {
            is LayerContent.OfLook -> baseCascade.copy(look = content.look.palette.toPaletteColours()).effective
            is LayerContent.OfTemplate -> baseCascade.effective
        }

        // Expand the layer's target set once. Null means "unrestricted"; non-empty means the layer
        // both *supplies* targets to deferred rows and *filters* bound ones — one meaning serving
        // two jobs, and what lets the migration preserve coverage exactly.
        val layerTargets = layer.targets.map { it.target }
        val layerFixtures: List<Expanded>? =
            if (layerTargets.isEmpty()) null else expandTargets(fixtures, cueId, layer, layerTargets)

        val pending = ArrayList<Pending>()
        for (row in content.rows) {
            // Element-scoped rows are handled by the caller-side element path; they never reach the
            // per-fixture accumulator because an element is not a (fixture, property) key.
            if (row.elementKey != null) continue
            val rowTarget = row.target
            if (rowTarget == null) {
                if (layerFixtures == null) {
                    logger.warn(
                        "cue {}: {} '{}' has a row for '{}' that takes its targets from the layer, " +
                            "but the layer names none — skipping",
                        cueId, layer.source.kind, layer.source.name, row.propertyName,
                    )
                    continue
                }
                for (e in layerFixtures) {
                    pending.add(Pending(e.fixture, row.propertyName, row.value, e.groupKey))
                }
            } else {
                val rowFixtures = expandTargets(fixtures, cueId, layer, listOf(rowTarget))
                val allowed = layerFixtures?.mapTo(HashSet()) { it.fixture.key }
                for (e in rowFixtures) {
                    if (allowed != null && e.fixture.key !in allowed) continue
                    pending.add(Pending(e.fixture, row.propertyName, row.value, e.groupKey))
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
            val resolved: TemplateResolver.Resolution? = when (content) {
                is LayerContent.OfLook -> null
                is LayerContent.OfTemplate -> {
                    val intent = parseTemplateIntent(p.rawValue)
                    if (intent == null) {
                        logger.warn(
                            "cue {}: template '{}' — '{}' is not an intent for {}.{} — skipping",
                            cueId, layer.source.name, p.rawValue, p.fixture.key, p.propertyName,
                        )
                        continue
                    }
                    val resolution = TemplateResolver.resolve(p.fixture, p.propertyName, intent)
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
            }
            // **Not canonicalised for a template.** `TemplateResolver` already answered with the
            // head's own property name, and canonicalising it would undo exactly the work it did:
            // `canonicalPropertyName("colour")` is `"rgbColour"`, so the MAC 250's colour *wheel*
            // would be looked up under a name it does not have and the head would silently drop out
            // of every colour template. The Look path still canonicalises, because a stored Look row
            // may spell the property any of the three ways.
            val canonical = resolved?.propertyName ?: canonicalPropertyName(p.propertyName)
            if (mask != null && !maskAllows(mask, maskGroupForProperty(p.fixture, canonical))) continue
            val categoryInfo = uk.me.cormack.lighting7.routes.fixtureCategoryFor(p.fixture, canonical)
            if (categoryInfo == null) {
                logger.warn(
                    "cue {}: {} '{}' — property '{}' not on '{}' — skipping",
                    cueId, layer.source.kind, layer.source.name, p.propertyName, p.fixture.key,
                )
                continue
            }
            val (category, override) = categoryInfo
            val incoming = resolved?.value
                ?: CueAssignmentResolver.parseAssignmentValue(category, canonical, p.rawValue, effectivePalette)
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

    /** True when [layerTargets] covers [target] — by naming it, or by naming a group containing it. */
    private fun coversTarget(fixtures: Fixtures, layerTargets: List<TargetRef>, target: TargetRef): Boolean {
        if (target in layerTargets) return true
        val targetKeys = when (target) {
            is TargetRef.Fixture -> setOf(target.key)
            is TargetRef.Group -> runCatching { fixtures.untypedGroup(target.key) }.getOrNull()
                ?.fixtures?.filterIsInstance<Fixture>()?.mapTo(HashSet()) { it.key }
                ?: return false
        }
        if (targetKeys.isEmpty()) return false
        val layerKeys = HashSet<String>()
        for (lt in layerTargets) {
            when (lt) {
                is TargetRef.Fixture -> layerKeys.add(lt.key)
                is TargetRef.Group -> runCatching { fixtures.untypedGroup(lt.key) }.getOrNull()
                    ?.fixtures?.filterIsInstance<Fixture>()?.forEach { layerKeys.add(it.key) }
            }
        }
        return targetKeys.any { it in layerKeys }
    }

    // ─── Blending ───────────────────────────────────────────────────────

    /**
     * Parse a layer's stored blend mode, reusing the effect vocabulary ([BlendMode]) rather than
     * declaring a near-identical second enum. `ADDITIVE` therefore comes along for free; the
     * plan's four modes are the subset the UI offers.
     */
    private fun parseLayerBlendMode(raw: String, sourceName: String, cueId: Int): BlendMode =
        BlendMode.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) } ?: run {
            logger.warn("cue {}: layer on '{}' has unknown blend mode '{}' — using OVERRIDE", cueId, sourceName, raw)
            BlendMode.OVERRIDE
        }

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
