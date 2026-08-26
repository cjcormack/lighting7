package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.dao.Entity
import org.jetbrains.exposed.v1.dao.load
import org.jetbrains.exposed.v1.dao.with
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.CompositionRule
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.PropertyCategory
import uk.me.cormack.lighting7.models.*
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import kotlin.reflect.KProperty1

private val logger = LoggerFactory.getLogger("cueApply")

/**
 * The cue-apply domain: the snapshot a cue is applied from, the priority it composes at, the
 * Layer 4 rows it contributes, and the stomp surface it claims.
 *
 * This lived in `routes/projectCuesHelpers.kt` until sweep item E2, which meant `CueStackManager`,
 * `CueTriggerManager` and `CueComposer` all imported `routes/` to fire a cue — the engine depending
 * on the transport that happened to call it first. What stayed behind in `projectCuesHelpers.kt` is
 * the part that really is transport: state capture for Record, the `CueDetails` response builder,
 * cue-child persistence, and `applyCue` itself, which returns a REST response type.
 *
 * `fx/` no longer *imports* `routes/` after this, but it is not yet free of it: the descriptor
 * family `detectCapabilities` inspects (`GroupSliderPropertyDescriptor` and friends) is declared in
 * terms of `routes.ChannelRef`, so the dependency survives transitively through
 * `fixture/group/GroupProperties.kt`. Relocating `PropertyDescriptor`/`ChannelRef` out of `routes/`
 * is its own job and E2 does not do it.
 */
internal data class CueApplyData(
    val cueId: Int,
    val cueName: String,
    val adHocEffects: List<CueAdHocEffectDto>,
    /**
     * The cue's ordered Look composition, resolved far enough for [CueComposer.cook] — the Look's
     * uuid is carried so the uuid-keyed [LookRegistry] needs no second DB hit at apply time.
     */
    val layers: List<CookLayer> = emptyList(),
    val propertyAssignments: List<CuePropertyAssignmentDto> = emptyList(),
    val triggers: List<CueTriggerDto> = emptyList(),
    val autoAdvance: Boolean = false,
    val autoAdvanceDelayMs: Long? = null,
    val fadeDurationMs: Long? = null,
    val fadeCurve: String = "LINEAR",
    val stomp: Boolean = false,
    val cueStackId: Int? = null,
    val sortOrder: Int = 0,
)

/**
 * Every reference [toCueApplyData] dereferences, eager-loaded in one query per relation.
 *
 * Without this a build costs a query *per layer* (each layer's Look or template, for its uuid and
 * name — see [DaoCueLayer.source]) and *per trigger* (its script, for an id). A cue with a dozen
 * layers therefore cost a dozen round-trips against a size-1 pool, on the GO path and on every
 * Look-edit republish.
 */
private val CUE_APPLY_RELATIONS: Array<KProperty1<out Entity<*>, Any?>> = arrayOf(
    DaoCue::cueStack,
    DaoCue::layers,
    DaoCueLayer::look,
    DaoCueLayer::template,
    DaoCue::adHocEffects,
    DaoCue::propertyAssignments,
    DaoCue::triggers,
    DaoCueTrigger::script,
)

/**
 * Build a [CueApplyData] snapshot from a [DaoCue] entity. Must be called inside an Exposed
 * transaction — dereferences the cue's child collections eagerly.
 *
 * **This is the only builder.** `CueStackManager.activateCueInStack` and `AiTools.applyCue` each
 * hand-rolled a near-identical one, and each in turn shipped with a field the author forgot:
 * `layers`, added later, was inert on the stack GO path — the primary firing path — while the
 * standalone apply-cue route worked. Two independent authors reproducing one omission is why a
 * field-by-field rebuild is not allowed to exist here; see `FU-CUE-APPLYDATA-ONE-BUILDER`.
 *
 * That collapse is why [CueApplyData.fadeDurationMs], [CueApplyData.fadeCurve],
 * [CueApplyData.autoAdvance] and [CueApplyData.autoAdvanceDelayMs] are populated even though only
 * `CueStackManager` reads them: leaving them at their defaults here is precisely what forced the
 * second builder into existence.
 */
internal fun buildCueApplyData(cue: DaoCue): CueApplyData =
    cue.load(*CUE_APPLY_RELATIONS).toCueApplyData()

/**
 * The batched form: one query per relation for the whole set, rather than one transaction and a
 * fresh relation load per cue. A Look edit republishing every live cue that layers it is the
 * caller that needs this — see `republishForSourceEdit`.
 *
 * Ids with no surviving cue are simply absent from the result; the caller decides what that means.
 */
internal fun buildCueApplyDataForCues(cueIds: Collection<Int>): Map<Int, CueApplyData> {
    if (cueIds.isEmpty()) return emptyMap()
    return DaoCue.find { DaoCues.id inList cueIds.toList() }
        .with(*CUE_APPLY_RELATIONS)
        .associate { it.id.value to it.toCueApplyData() }
}

/** The shared body. Assumes [CUE_APPLY_RELATIONS] are already loaded. */
private fun DaoCue.toCueApplyData(): CueApplyData = CueApplyData(
    cueId = id.value,
    cueName = name,
    adHocEffects = adHocEffects.sortedBy { it.sortOrder }.map { it.toDto() },
    layers = layers.sortedBy { it.sortOrder }.map { it.toCookLayer() },
    propertyAssignments = propertyAssignments.sortedBy { it.sortOrder }.map { it.toDto() },
    triggers = triggers.sortedBy { it.sortOrder }.map { trigger ->
        CueTriggerDto(
            triggerType = trigger.triggerType.name,
            delayMs = trigger.delayMs,
            intervalMs = trigger.intervalMs,
            randomWindowMs = trigger.randomWindowMs,
            scriptId = trigger.script.id.value,
            sortOrder = trigger.sortOrder,
        )
    },
    autoAdvance = autoAdvance,
    autoAdvanceDelayMs = autoAdvanceDelayMs,
    fadeDurationMs = fadeDurationMs,
    fadeCurve = fadeCurve,
    stomp = stomp,
    cueStackId = cueStack.id.value,
    sortOrder = sortOrder,
)

/**
 * Resolve a stored cue layer into the composer's input shape. Must run inside a transaction — it
 * dereferences the layer's Look or template for its uuid and name.
 */
internal fun DaoCueLayer.toCookLayer() = CookLayer(
    layerId = id.value,
    source = source,
    sortOrder = sortOrder,
    enabled = enabled,
    targets = targets,
    propertyMask = propertyMask,
    blendMode = blendMode,
    amount = amount,
    stomp = stomp,
    speedMasterUuid = speedMasterUuid,
    rateSpeedMasterUuid = rateSpeedMasterUuid,
    delayMs = delayMs,
    intervalMs = intervalMs,
    randomWindowMs = randomWindowMs,
)


/**
 * Derived priority for a cue-owned effect. `+1` keeps manual effects (priority 0) strictly
 * below; the magnitude gaps leave room for per-effect fine-tuning without renumbering.
 */
internal fun cueDerivedPriority(cueData: CueApplyData): Int =
    cueDerivedPriority(cueData.cueStackId, cueData.sortOrder)

/**
 * Position-only form, for recomputing the priority of *already applied* rows after a stack is
 * reordered — see [uk.me.cormack.lighting7.fx.FxEngine.repriorityCues].
 */
internal fun cueDerivedPriority(cueStackId: Int?, sortOrder: Int): Int =
    (cueStackId ?: 0) * 1_000_000 + sortOrder * 1_000 + 1

/**
 * `cueId → cueDerivedPriority` for every cue in [stack] at its current sort order. Hand the
 * result to [uk.me.cormack.lighting7.fx.FxEngine.repriorityCues] after changing the order, so
 * cues already on stage compose in the new order without needing to be re-applied.
 */
internal fun stackCuePriorities(stack: DaoCueStack): Map<Int, Int> =
    DaoCue.find { DaoCues.cueStack eq stack.id }
        .associate { it.id.value to cueDerivedPriority(stack.id.value, it.sortOrder) }

/**
 * The **cue-level** (cross-cue) stomp overlap: every `(target, property)` this cue holds, from both
 * halves of its composition.
 *
 * [buildStompOverlapFromAssignments] alone reads the cue's *local* rows, which was the whole of a
 * cue's surface before the layer model and is no longer — a cue whose colour comes entirely from a
 * layer asserted plenty and stomped nothing. [CookResult.assertedKeys] supplies the layers' half,
 * group aliases included, and the two are unioned rather than one replacing the other: cook emits
 * per-fixture rows for a group-targeted *local* row, so dropping the assignment pass would lose the
 * group keys an effect aimed at the group itself is matched on.
 */
internal fun buildStompOverlap(
    fixtures: Fixtures,
    cueData: CueApplyData,
    cooked: CookResult,
): Set<FxEngine.PropertyKey> {
    val fromAssignments = buildStompOverlapFromAssignments(fixtures, cueData)
    if (cooked.assertedKeys.isEmpty()) return fromAssignments
    if (fromAssignments.isEmpty()) return cooked.assertedKeys
    return fromAssignments + cooked.assertedKeys
}

/**
 * Build the stomp overlap set from a cue's property assignments. Group targets are expanded
 * to member keys so the resolver can filter ad-hoc effects owned by other cues that target
 * individual fixtures within the same group.
 *
 * The cue's **layers** are not visible here — see [buildStompOverlap], which is what callers want.
 */
internal fun buildStompOverlapFromAssignments(
    fixtures: Fixtures,
    cueData: CueApplyData,
): Set<FxEngine.PropertyKey> {
    if (cueData.propertyAssignments.isEmpty()) return emptySet()
    val out = HashSet<FxEngine.PropertyKey>()
    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        when (val target = assignment.target) {
            is TargetRef.Group -> {
                out.add(FxEngine.PropertyKey(target.key, canonical))
                val members = try {
                    fixtures.untypedGroup(target.key).fixtures
                } catch (_: IllegalStateException) { emptyList() }
                for (member in members) {
                    if (member is Fixture) out.add(FxEngine.PropertyKey(member.key, canonical))
                }
            }
            is TargetRef.Fixture -> {
                out.add(FxEngine.PropertyKey(target.key, canonical))
            }
        }
    }
    return out
}

// Canonical form for property names is defined in [uk.me.cormack.lighting7.fx.canonicalPropertyName]
// — shared with [PersistedFixtureReferenceValidator] so route handlers and validation
// don't drift apart on the aliasing rule.

/**
 * Fixture property lookup used when building Layer 4 assignments. Returns the resolved
 * category / composition override for [propertyName] on [fixture], or null if the name is
 * not a known annotated property.
 *
 * Handles the synthetic aliases the target-resolution code already understands:
 * `"position"` (paired PAN+TILT), `"colour"` / `"color"` / `"rgbColour"` (RGB+W/A/UV bundle).
 * For these names [fixture] is consulted only to verify the capability exists.
 *
 * **The exact name is tried before the canonical one**, and that ordering is a fix rather than a
 * micro-optimisation. [canonicalPropertyName] rewrites `colour` → `rgbColour` unconditionally, but
 * the Martin MAC 250's colour *wheel* is a property literally named `colour` — so a stored row for
 * it resolved to nothing and was dropped with a "property not on fixture" warning, on every cook.
 * That hit any recorded Look holding a wheel colour, and it is what a colour template would have hit
 * on every wheel-only head in the rig. Exact-first is safe because no fixture declares both names.
 */
internal fun fixtureCategoryFor(
    fixture: Fixture,
    propertyName: String,
): Pair<PropertyCategory, CompositionRule>? {
    if (propertyName.equals("position", ignoreCase = true)) {
        // Synthetic compound of PAN + TILT. Composition defaults to the PAN category's rule;
        // any override on the pan property is honoured.
        val panProp = fixture.fixtureProperty("pan")
        return panProp?.let { it.category to it.composition } ?: (PropertyCategory.PAN to CompositionRule.UNSET)
    }
    fixture.fixtureProperty(propertyName)?.let { return it.category to it.composition }
    val canonical = canonicalPropertyName(propertyName)
    if (canonical.equals("position", ignoreCase = true)) {
        val panProp = fixture.fixtureProperty("pan")
        return panProp?.let { it.category to it.composition } ?: (PropertyCategory.PAN to CompositionRule.UNSET)
    }
    val prop = fixture.fixtureProperty(canonical) ?: return null
    return prop.category to prop.composition
}

/**
 * Build the flat [CueAssignmentResolver.Assignment] list for a single cue's [propertyAssignments],
 * expanding group targets to per-member rows. Member rows produced by a group expansion carry
 * `targetIsGroup = true` so the resolver's specificity rule can drop them when the same cue
 * also asserts a direct fixture-level row on the same (fixtureKey, property).
 *
 * Assignments whose fixture, group, or property cannot be resolved are logged at warn and
 * skipped — missing data must not break cue apply.
 *
 * There used to be a per-member branch here: a row whose value was `ref:{uuid}` resolved **once per
 * target fixture**, taking each member's *own* property category rather than the reference fixture's,
 * because a reference is per-fixture by construction and a mixed-type group is exactly the case it
 * existed to serve. The `ref:` grammar retired in session 4, so every row is a literal and one parse
 * against the reference fixture serves the whole target — which is what the "literal rows keep the
 * single parse before the fanout" fast path always was.
 */
internal fun buildCueAssignmentsForCue(
    fixtures: Fixtures,
    cueData: CueApplyData,
): List<CueAssignmentResolver.Assignment> {
    if (cueData.propertyAssignments.isEmpty()) return emptyList()
    val priority = cueDerivedPriority(cueData)
    val out = ArrayList<CueAssignmentResolver.Assignment>(cueData.propertyAssignments.size * 2)

    for (assignment in cueData.propertyAssignments) {
        val canonical = canonicalPropertyName(assignment.propertyName)
        val target = assignment.target

        // Resolve a reference fixture for category lookup and, for groups, the member keys.
        // memberKeys is empty iff the target is a Fixture — used below as the fanout discriminator.
        val memberKeys: List<String>
        val referenceFixture: Fixture
        when (target) {
            is TargetRef.Group -> {
                val group = try {
                    fixtures.untypedGroup(target.key)
                } catch (_: IllegalStateException) {
                    logger.warn("cue {}: group '{}' missing — skipping assignment for {}", cueData.cueId, target.key, assignment.propertyName)
                    continue
                }
                val members = group.fixtures.filterIsInstance<Fixture>()
                if (members.isEmpty()) {
                    logger.warn("cue {}: group '{}' has no Fixture members — skipping assignment", cueData.cueId, target.key)
                    continue
                }
                memberKeys = members.map { it.key }
                referenceFixture = members.first()
            }
            is TargetRef.Fixture -> {
                referenceFixture = try {
                    fixtures.untypedFixture(target.key)
                } catch (_: IllegalStateException) {
                    logger.warn("cue {}: fixture '{}' missing — skipping assignment for {}", cueData.cueId, target.key, assignment.propertyName)
                    continue
                }
                memberKeys = emptyList()
            }
        }

        // Assignment.fadeWeight always 1.0 here — crossfade progress is applied per-cue by
        // [FxEngine.updateCueFadeWeights] at publish time, not baked into individual rows.
        fun row(
            key: String,
            isGroup: Boolean,
            category: PropertyCategory,
            override: CompositionRule,
            value: CueAssignmentResolver.PropertyValue,
        ) = CueAssignmentResolver.Assignment(
            cueId = cueData.cueId,
            priority = priority,
            fadeWeight = 1.0,
            targetKey = key,
            targetIsGroup = isGroup,
            propertyName = canonical,
            category = category,
            compositionOverride = override,
            value = value,
            moveInDark = assignment.moveInDark,
            // The stored row's own fade. Dropped here until sweep item B1: the column, the DTO, the
            // exporter and the cue routes all carried it, and the one hop into the composition
            // layer did not — so a cue's local row snapped while a clicked template faded.
            fadeDurationMs = assignment.fadeDurationMs,
        )

        // Until session 4 a per-fixture branch sat here: a value of `ref:{uuid}` had to be resolved
        // against the referenced Look *once per target fixture*, because a reference resolves per
        // head. A row's value is always a literal now, so one parse against the reference fixture
        // serves the whole target.
        val (category, override) = fixtureCategoryFor(referenceFixture, canonical) ?: run {
            logger.warn("cue {}: property '{}' not found on '{}' — skipping", cueData.cueId, assignment.propertyName, target.key)
            continue
        }

        val parsed = CueAssignmentResolver.parseAssignmentValue(category, canonical, assignment.value) ?: run {
            logger.warn("cue {}: invalid value '{}' for {}.{} — skipping", cueData.cueId, assignment.value, target.key, assignment.propertyName)
            continue
        }

        if (memberKeys.isEmpty()) {
            out.add(row(target.key, isGroup = false, category, override, parsed))
        } else {
            // Emit only per-member rows; the group-level key isn't a resolvable fixture at
            // publish time. Mark these as targetIsGroup=true so a direct fixture-level row
            // for the same member overrides via [CueAssignmentResolver.applySpecificity].
            for (memberKey in memberKeys) out.add(row(memberKey, isGroup = true, category, override, parsed))
        }
    }
    return out
}

/**
 * Republish Layer 4 for [cueId] from pre-built [applyData], combining the cue's own property
 * assignments with those of each immediate preset application (timed presets don't contribute
 * — matching [applyCue]). Effects are left alone: this is the Layer 4 half of an apply.
 *
 * Used by cue-edit persists and by Record/Update after they rewrite a cue that is currently
 * live. Without it the DB rows and the published layer disagree, and the next Clear would
 * snap the rig back to the cue's pre-edit values.
 */
internal fun republishCueLayer(state: State, cueId: Int, applyData: CueApplyData) {
    val engine = state.show.fxEngine
    val combined = buildCombinedCueLayerRows(state, cueId, applyData)
    if (combined.rows.isNotEmpty()) {
        engine.setCueAssignments(
            cueId, combined.rows,
            cueStackId = applyData.cueStackId,
            stompSuppression = combined.stompSuppression,
        )
    } else {
        engine.removeCueAssignments(cueId)
    }
}

/**
 * The cook [republishCueLayer] would publish: the cue's layer stack flattened with its own local
 * rows on top (timed layers don't contribute unless named in [firedTimedLayerIds] — matching
 * [applyCue]).
 *
 * Returns the whole [CookResult], not just its rows. A caller that only wants values takes `.rows`;
 * one that *publishes* must carry `.stompSuppression` with them, or a stomping layer's effect
 * suppression outlives the cook that justified it.
 *
 * Split out from [republishCueLayer] so a caller that needs to rebuild several cues can publish
 * them in one pass — see `republishForLookEdit`, where publishing per cue would take the engine
 * lock and transmit once per cue for what is a single operator edit.
 *
 * [firedTimedLayerIds] names the timed layers that have already fired, so a rebuild triggered while
 * a timed layer is live reproduces its contribution instead of dropping it. See
 * [uk.me.cormack.lighting7.fx.CueTriggerManager] for why firing re-cooks rather than appending.
 * Defaulting to null — "ask the trigger manager" — rather than to the empty set is deliberate: an
 * explicit-empty default made every caller silently retract a live timed layer's rows.
 */
internal fun buildCombinedCueLayerRows(
    state: State,
    cueId: Int,
    applyData: CueApplyData,
    firedTimedLayerIds: Set<Int>? = null,
): CookResult {
    val cueOwn = buildCueAssignmentsForCue(state.show.fixtures, applyData)
    return CueComposer.cook(
        fixtures = state.show.fixtures,
        cueId = cueId,
        priority = cueDerivedPriority(applyData),
        layers = applyData.layers,
        localRows = cueOwn,
        lookRegistry = state.show.lookRegistry,
        templateRegistry = state.show.templateRegistry,
        includeTimed = firedTimedLayerIds ?: state.cueTriggerManager.firedTimedLayerIds(cueId),
    )
}
