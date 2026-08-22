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

/**
 * One stored Look row. [target] is null for a deferred row, which takes its targets from the
 * [uk.me.cormack.lighting7.models.DaoCueLayers] line referencing the Look.
 */
data class LookRowEntry(
    val target: TargetRef?,
    val propertyName: String,
    val value: String,
    val fadeDurationMs: Long? = null,
    val elementKey: String? = null,
) {
    val isDeferred: Boolean get() = target == null
}

/** One stored Look effect. [target] is null for a deferred effect — see [LookRowEntry]. */
data class LookEffectEntry(
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
    val editorFixtureType: String?,
    /** The positional colour list (`P1` / `P2`), feeding [PaletteCascade]'s most-specific scope. */
    val palette: List<String>,
    /** Rows in `sortOrder`. */
    val rows: List<LookRowEntry>,
    /** Effects in `sortOrder`. */
    val effects: List<LookEffectEntry>,
)

/**
 * A snapshot flattened for per-fixture lookup: `fixtureKey → canonicalPropertyName → literal`.
 *
 * Built from **bound rows only** — a deferred row names no fixture, so it cannot appear here; and
 * element-scoped rows are skipped, because this map is fixture-shaped by construction. Group rows
 * are expanded to their members first, then fixture rows overwrite, which is the same
 * fixture-beats-group specificity [CueAssignmentResolver.applySpecificity] applies, resolved once
 * here instead of per read.
 *
 * This is what serves `ref:{uuid}` resolution. The palettes → Looks migration preserves each
 * palette's uuid onto its Look, so an existing reference keeps resolving through here unchanged
 * until the grammar itself is retired.
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
 * [version] increments on every invalidation, mirroring [FxEngine.paletteVersion] / the
 * `TypedParams.invalidateColourCacheIfStale` idiom.
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

    @Volatile
    var version: Long = 0L
        private set

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
        // Bump before evicting — see [expanded]. Reversing these two lines reopens the
        // permanently-stale-cache race.
        version++
        cache.remove(lookUuid)
    }

    fun invalidateAll() {
        version++
        if (cache.isNotEmpty()) {
            cache.clear()
            logger.debug("look cache cleared")
        }
    }

    companion object {
        /** Fill attempts before giving up on caching and just serving a fresh build. */
        private const val MAX_FILL_ATTEMPTS = 3

        /**
         * Flatten a snapshot's **bound, whole-fixture** rows against the live patch. Group rows
         * first, then fixture rows, so a fixture row wins. Unknown and empty groups contribute
         * nothing — a Look that outlived a group is a dead reference on those members, reported as
         * [AssignmentHealth.MissingPaletteEntry] at resolve time rather than guessed at here.
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
            editorFixtureType = look.editorFixtureType,
            palette = look.palette,
            rows = look.rows
                .orderBy(DaoLookRows.sortOrder to SortOrder.ASC)
                .mapNotNull { row ->
                    // A deferred row legitimately has no target; a row whose discriminator names no
                    // known arm is corrupt and is dropped, as loadPaletteSnapshot did.
                    val target = if (row.targetType == DEFERRED_TARGET_TYPE) {
                        null
                    } else {
                        TargetRef.ofOrNull(row.targetType, row.targetKey) ?: return@mapNotNull null
                    }
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
                    LookEffectEntry(
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
 * too — so bridging here keeps `createInstanceFromPreset` and `resolveTargetForCue` untouched. The
 * type gets its final, non-preset name when `models/fxPresets.kt` is deleted and it has to move
 * anyway.
 *
 * The target is deliberately dropped: it is the caller's business, because a deferred effect's
 * target comes from the layer rather than from the effect.
 */
internal fun LookEffectEntry.toEffectSpec() = uk.me.cormack.lighting7.models.LookEffectSpec(
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
