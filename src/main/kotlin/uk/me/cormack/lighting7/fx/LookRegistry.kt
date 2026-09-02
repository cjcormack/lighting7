package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoLook
import uk.me.cormack.lighting7.models.DaoLookEffects
import uk.me.cormack.lighting7.models.DaoLookRows
import uk.me.cormack.lighting7.models.DaoLooks
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import uk.me.cormack.lighting7.models.AssignmentHealth

/**
 * One stored Look row. Always bound: a Look row names its own fixture or group, and the deferred
 * half of the entity is [uk.me.cormack.lighting7.models.DaoTemplates] (sweep item B6). A row
 * whose stored target does not resolve never becomes a `LookRowEntry` — see [loadLookSnapshot].
 */
data class LookRowEntry(
    val target: TargetRef,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val elementKey: String? = null,
)

/**
 * One stored effect, as the composer reads it out of a **Look or a template**.
 *
 * Named `LookEffectEntry` until the fx-templates plan gave a template one of its own; nothing about
 * the type was ever Look-specific, so the rename is mechanical. Unlike [LookRowEntry], [target] may
 * be null: a *deferred* Look effect fans over the targets of the layer applying it, and a
 * template's effect is **always** target-less (D3), so it arrives here as the same shape.
 *
 * **Carries no source identity, deliberately, and must not grow one.**
 * [uk.me.cormack.lighting7.fx.ProgrammerLayerEffectKey] freezes a hash over the whole entry so that
 * editing an effect produces a *different* key and the stale instance retracts. A `sourceUuid`
 * field here would make every key change on the first recook after an upgrade, retracting and
 * respawning every programmer-layer effect on the desk. Where an effect came from lives on
 * [FxInstance.source] instead.
 */
data class EffectEntry(
    val target: TargetRef?,
    val effectType: String,
    val category: String,
    val propertyName: String?,
    val beatDivision: Double,
    val blendMode: String,
    val distribution: String,
    val phaseOffset: Double,
    val elementMode: String?,
    val elementFilter: String?,
    val stepTiming: Boolean?,
    val parameters: Map<String, String>,
    val speedMasterUuid: UUID?,
    val rateSpeedMasterUuid: UUID?,
) {
    val isDeferred: Boolean get() = target == null
}

/** A Look's identity and contents, read out of the DB and held immutably. */
data class LookSnapshot(
    val lookId: Int,
    val lookUuid: UUID,
    val name: String,
    /** Rows in `sortOrder`. */
    val rows: List<LookRowEntry>,
    /** Effects in `sortOrder`. */
    val effects: List<EffectEntry>,
)

/**
 * A snapshot flattened for per-fixture lookup: `fixtureKey → canonicalPropertyName → literal`.
 *
 * Element-scoped rows are skipped, because this map is fixture-shaped by construction. Group rows
 * are expanded to their members first, then fixture rows overwrite, which is the same
 * fixture-beats-group specificity [CueAssignmentResolver.applySpecificity] applies, resolved once
 * here instead of per read.
 *
 * This is what served `ref:{uuid}` resolution before the grammar retired in session 4 — the
 * palettes → Looks migration had preserved each palette's uuid onto its Look so an existing
 * reference kept resolving through here unchanged in the meantime.
 */
class ExpandedLook(
    val snapshot: LookSnapshot,
    val byFixture: Map<String, Map<String, String>>,
) {
    /**
     * [propertyName] is canonicalised here rather than trusted from the caller. Both sides of the
     * lookup have to agree, and a silent miss on `colour` vs `rgbColour` is indistinguishable from
     * "this Look doesn't cover the fixture" — so the cheap normalisation (this is a human-rate
     * path, not the tick loop) buys an API that can't be held wrong.
     */
    fun literalFor(fixtureKey: String, propertyName: String): String? =
        byFixture[fixtureKey]?.get(canonicalPropertyName(propertyName))
}

/**
 * Caches Looks in the form the composer and the reference resolver read, keyed by uuid.
 *
 * Replaces `LookRegistry` and keeps its concurrency design verbatim, because that design was
 * bought with a specific bug. Reads happen on cue apply and on programmer writes — human-rate, not
 * per tick — but they happen per row, so flattening on every read would mean re-expanding a group
 * for every member of it. The cache is filled on miss and dropped wholesale when the inputs change.
 *
 * **Two invalidation triggers, both required:**
 * - [invalidate] after a Look's contents change, so the republish that follows resolves the new
 *   values.
 * - [invalidateAll] when the patch or group membership changes, because a group row's expansion
 *   names member *fixtures*. Without it a Look silently keeps resolving against the old
 *   membership — the failure mode is a fixture added to a group not picking up the Look, which
 *   looks like a Look bug and isn't.
 *
 * [version] increments on every invalidation — twice, once either side of the eviction; see its
 * own KDoc for why both are load-bearing. [TemplateRegistry] is the same idiom one step
 * further on: it versions per template uuid ([TemplateRegistry.versionFor]), because a running
 * effect's colour cache watches its *own* references rather than the whole registry. A Look has no
 * such consumer — an expansion is read at cook, not per tick — so one counter is enough here.
 *
 * Misses are deliberately *not* cached: a dangling reference costs one small query per apply, and
 * caching the absence would need a third invalidation trigger to ever recover.
 */
class LookRegistry(
    private val fixtures: () -> Fixtures,
    private val loader: (UUID) -> LookSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(LookRegistry::class.java)
    private val cache = ConcurrentHashMap<UUID, ExpandedLook>()

    /**
     * Bumped **twice** per invalidation — once on each side of the eviction — and atomic.
     *
     * The pre-eviction bump is [expanded]'s in-flight-load guard; see its KDoc for the
     * permanently-stale-cache race that ordering closes.
     *
     * The post-eviction bump exists for the other direction, and for a different consumer:
     * `CueTriggerManager.TimedFireCook` stamps a memoised cook with this counter. Between the first
     * bump and the eviction this registry reports the **new** version while still serving the
     * **pre-edit** expansion, so a cook landing in that window would be stamped with a version it
     * then keeps matching — a recurring timed layer would republish pre-edit rows over the
     * operator's save, permanently. Bumping again once the eviction has completed makes the
     * settled version strictly greater than any version observable while stale content was still
     * reachable, which is exactly what a stamp needs.
     *
     * `AtomicLong` rather than a `@Volatile var` with `++`, for the reason
     * [uk.me.cormack.lighting7.show.Fixtures.structureVersion] gives: two concurrent invalidations
     * doing a read-modify-write can lose an update *and* momentarily move the counter backwards,
     * which is precisely how a stamped cache revalidates against content that has changed.
     */
    private val versionCounter = java.util.concurrent.atomic.AtomicLong(0L)
    val version: Long get() = versionCounter.get()

    /**
     * The flattened Look, filling the cache on a miss.
     *
     * **The version re-check is not belt-and-braces — without it a stale expansion can be cached
     * permanently.** An invalidation that lands while a load is in flight has nothing to remove
     * (the cache entry doesn't exist yet), so the racing load would insert its pre-edit snapshot
     * *after* the invalidation and every subsequent read would serve it until some unrelated
     * invalidation happened to occur. Concretely: a cue GO resolving a layer on one thread while
     * the operator saves an edit to that Look on another, after which the rig keeps showing the old
     * colour even though the save reported success.
     *
     * [invalidate] bumps [version] *before* evicting, so a bump we fail to observe here implies the
     * eviction is still to come and will drop whatever we cached.
     */
    fun expanded(lookUuid: UUID): ExpandedLook? {
        cache[lookUuid]?.let { return it }
        // Bounded rather than unbounded: invalidations are human-rate, so this settles on the first
        // pass in practice, and a defective loader can't spin here forever.
        repeat(MAX_FILL_ATTEMPTS) {
            val versionBefore = version
            val snapshot = loader(lookUuid) ?: return null
            val expanded = expand(snapshot, fixtures())
            if (version == versionBefore) {
                // putIfAbsent rather than put: a concurrent filler may have won, and handing back
                // one shared instance keeps every reader on the same map identity.
                return cache.putIfAbsent(lookUuid, expanded) ?: expanded
            }
            // Raced an invalidation: this build may predate the edit. Prefer a peer's fresh entry
            // if one landed, else load again.
            cache[lookUuid]?.let { return it }
        }
        // Persistently contended — serve a fresh build without caching it rather than risk pinning
        // a stale one.
        val snapshot = loader(lookUuid) ?: return null
        return expand(snapshot, fixtures())
    }

    fun snapshot(lookUuid: UUID): LookSnapshot? = expanded(lookUuid)?.snapshot

    /** The literal this Look holds for one fixture and property, or null when uncovered. */
    fun literalFor(lookUuid: UUID, fixtureKey: String, propertyName: String): String? =
        expanded(lookUuid)?.literalFor(fixtureKey, propertyName)

    fun invalidate(lookUuid: UUID) {
        // Bump, evict, bump. Neither bump is redundant and they are not interchangeable — see
        // [version]: the first is [expanded]'s in-flight-load guard, the second is what stops a
        // stamp taken over the still-visible pre-edit entry from surviving the eviction.
        versionCounter.incrementAndGet()
        cache.remove(lookUuid)
        versionCounter.incrementAndGet()
    }

    fun invalidateAll() {
        versionCounter.incrementAndGet()
        if (cache.isNotEmpty()) {
            cache.clear()
            logger.debug("look cache cleared")
        }
        versionCounter.incrementAndGet()
    }

    companion object {
        /** Fill attempts before giving up on caching and just serving a fresh build. */
        private const val MAX_FILL_ATTEMPTS = 3

        /**
         * Flatten a snapshot's **whole-fixture** rows against the live patch. Group rows
         * first, then fixture rows, so a fixture row wins. Unknown and empty groups contribute
         * nothing — a Look that outlived a group simply drops those members' rows, with no
         * [AssignmentHealth] diagnosis: a Look row cannot hold a reference, so there is nothing
         * left to report as unresolved.
         */
        internal fun expand(snapshot: LookSnapshot, fixtures: Fixtures): ExpandedLook {
            val byFixture = HashMap<String, MutableMap<String, String>>()

            fun put(fixtureKey: String, propertyName: String, value: String) {
                byFixture.getOrPut(fixtureKey) { HashMap() }[canonicalPropertyName(propertyName)] = value
            }

            for (entry in snapshot.rows) {
                if (entry.elementKey != null) continue
                val group = entry.target as? TargetRef.Group ?: continue
                val members = runCatching { fixtures.untypedGroup(group.key) }.getOrNull()
                    ?.fixtures
                    ?.filterIsInstance<Fixture>()
                    ?: continue
                for (member in members) put(member.key, entry.propertyName, entry.value)
            }
            for (entry in snapshot.rows) {
                if (entry.elementKey != null) continue
                val fixture = entry.target as? TargetRef.Fixture ?: continue
                put(fixture.key, entry.propertyName, entry.value)
            }

            return ExpandedLook(snapshot, byFixture)
        }
    }
}

/**
 * Read one Look, its rows and its effects into a [LookSnapshot]. Opens its own transaction, so it
 * must **not** be called from inside `FxEngine`'s `cueAssignmentsLock`.
 */
internal fun loadLookSnapshot(database: Database, lookUuid: UUID): LookSnapshot? =
    transaction(database) {
        val look = DaoLook.find { DaoLooks.uuid eq lookUuid }.firstOrNull()
            ?: return@transaction null
        LookSnapshot(
            lookId = look.id.value,
            lookUuid = look.uuid,
            name = look.name,
            rows = look.rows
                .orderBy(DaoLookRows.sortOrder to SortOrder.ASC)
                .mapNotNull { row ->
                    // A Look row is always bound, so a row whose discriminator names no known arm
                    // is dropped, as loadPaletteSnapshot did. That covers DEFERRED_TARGET_TYPE:
                    // it is not a TargetRef arm, so a deferred row written by an older database
                    // falls out here rather than reaching the composer with nothing to apply to.
                    val target = TargetRef.ofOrNull(row.targetType, row.targetKey)
                        ?: return@mapNotNull null
                    LookRowEntry(
                        target = target,
                        propertyName = row.propertyName,
                        value = row.value,
                        fadeDurationMs = row.fadeDurationMs,
                        elementKey = row.elementKey,
                    )
                },
            effects = look.effects
                .orderBy(DaoLookEffects.sortOrder to SortOrder.ASC)
                .mapNotNull { effect ->
                    val target = if (effect.targetType == DEFERRED_TARGET_TYPE) {
                        null
                    } else {
                        TargetRef.ofOrNull(effect.targetType, effect.targetKey) ?: return@mapNotNull null
                    }
                    EffectEntry(
                        target = target,
                        effectType = effect.effectType,
                        category = effect.category,
                        propertyName = effect.propertyName,
                        beatDivision = effect.beatDivision,
                        blendMode = effect.blendMode,
                        distribution = effect.distribution,
                        phaseOffset = effect.phaseOffset,
                        elementMode = effect.elementMode,
                        elementFilter = effect.elementFilter,
                        stepTiming = effect.stepTiming,
                        parameters = effect.parameters,
                        speedMasterUuid = effect.speedMasterUuid,
                        rateSpeedMasterUuid = effect.rateSpeedMasterUuid,
                    )
                },
        )
    }

/**
 * A Look effect as the generic effect spec the instance factories consume.
 *
 * [uk.me.cormack.lighting7.models.LookEffectSpec] already serves as that shared vehicle rather
 * than as anything preset-specific — a cue's ad-hoc effect is converted *into* it before spawning
 * too — so bridging here keeps `createEffectInstance` and `resolveTargetForCue` untouched. The
 * type gets its final, non-preset name when `models/fxPresets.kt` is deleted and it has to move
 * anyway.
 *
 * The target is deliberately dropped: it is the caller's business, because a deferred effect's
 * target comes from the layer rather than from the effect — and a template's effect has none at
 * all (D3), which is what lets a template effect cross this same bridge unchanged.
 */
internal fun EffectEntry.toEffectSpec() = uk.me.cormack.lighting7.models.LookEffectSpec(
    effectType = effectType,
    category = category,
    propertyName = propertyName,
    beatDivision = beatDivision,
    blendMode = blendMode,
    distribution = distribution,
    phaseOffset = phaseOffset,
    elementMode = elementMode,
    elementFilter = elementFilter,
    stepTiming = stepTiming,
    parameters = parameters,
    speedMasterUuid = speedMasterUuid?.toString(),
    rateSpeedMasterUuid = rateSpeedMasterUuid?.toString(),
)
