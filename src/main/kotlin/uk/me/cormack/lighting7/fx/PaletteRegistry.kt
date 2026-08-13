package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteEntries
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.PaletteType
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.show.Fixtures
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One stored palette row, target-typed. */
data class PaletteEntryRow(
    val target: TargetRef,
    val propertyName: String,
    val value: String,
)

/** A palette's identity and rows, read out of the DB and held immutably. */
data class PaletteSnapshot(
    val paletteId: Int,
    val paletteUuid: UUID,
    val name: String,
    /** Null when the stored type string doesn't name a known [PaletteType] (corrupt row). */
    val type: PaletteType?,
    val entries: List<PaletteEntryRow>,
)

/**
 * A snapshot flattened for lookup: `fixtureKey → canonicalPropertyName → literal`.
 *
 * Group rows are expanded to their members, then fixture rows overwrite — the same
 * fixture-beats-group specificity the cue resolver applies
 * ([Layer3Resolver.applySpecificity]), resolved once here instead of per read.
 */
class ExpandedPalette(
    val snapshot: PaletteSnapshot,
    val byFixture: Map<String, Map<String, String>>,
) {
    /**
     * [propertyName] is canonicalised here rather than trusted from the caller. Both sides of the
     * lookup have to agree, and a silent miss on `colour` vs `rgbColour` is indistinguishable from
     * "this palette doesn't cover the fixture" — so the cheap normalisation (this is a human-rate
     * path, not the tick loop) buys an API that can't be held wrong.
     */
    fun literalFor(fixtureKey: String, propertyName: String): String? =
        byFixture[fixtureKey]?.get(canonicalPropertyName(propertyName))
}

/**
 * Caches named palettes in the flattened form the resolver reads, keyed by uuid.
 *
 * Reads happen on cue apply and on programmer writes — human-rate, not per tick — but they
 * happen per row, so flattening on every read would mean re-expanding a group for every member
 * of it. The cache is filled on miss and dropped wholesale when the inputs change.
 *
 * **Two invalidation triggers, both required:**
 * - [invalidate] after a palette's contents change, so the republish that follows resolves the
 *   new values.
 * - [invalidateAll] when the patch or group membership changes, because a group row's expansion
 *   names member *fixtures*. Without it a palette silently keeps resolving against the old
 *   membership — the failure mode is a fixture added to a group not picking up the palette, which
 *   looks like a palette bug and isn't.
 *
 * [version] increments on every invalidation, mirroring [FxEngine.paletteVersion] / the
 * `TypedParams.invalidateColourCacheIfStale` idiom, so a future palette-aware effect can cache
 * against it the way the positional palette's consumers already do.
 *
 * Misses are deliberately *not* cached: a dangling ref costs one small query per apply, and
 * caching the absence would need a third invalidation trigger to ever recover.
 */
class PaletteRegistry(
    private val fixtures: () -> Fixtures,
    private val loader: (UUID) -> PaletteSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(PaletteRegistry::class.java)
    private val cache = ConcurrentHashMap<UUID, ExpandedPalette>()

    @Volatile
    var version: Long = 0L
        private set

    /**
     * The flattened palette, filling the cache on a miss.
     *
     * **The version re-check is not belt-and-braces — without it a stale expansion can be cached
     * permanently.** An invalidation that lands while a load is in flight has nothing to remove
     * (the cache entry doesn't exist yet), so the racing load would insert its pre-edit snapshot
     * *after* the invalidation and every subsequent read would serve it until some unrelated
     * invalidation happened to occur. Concretely: a cue GO resolving a ref on one thread while the
     * operator saves an edit to that palette on another, after which the rig keeps showing the old
     * colour even though the save reported success.
     *
     * [invalidate] bumps [version] *before* evicting, so a bump we fail to observe here implies the
     * eviction is still to come and will drop whatever we cached.
     */
    fun expanded(paletteUuid: UUID): ExpandedPalette? {
        cache[paletteUuid]?.let { return it }
        // Bounded rather than unbounded: invalidations are human-rate, so this settles on the first
        // pass in practice, and a defective loader can't spin here forever.
        repeat(MAX_FILL_ATTEMPTS) {
            val versionBefore = version
            val snapshot = loader(paletteUuid) ?: return null
            val expanded = expand(snapshot, fixtures())
            if (version == versionBefore) {
                // putIfAbsent rather than put: a concurrent filler may have won, and handing back
                // one shared instance keeps every reader on the same map identity.
                return cache.putIfAbsent(paletteUuid, expanded) ?: expanded
            }
            // Raced an invalidation: this build may predate the edit. Prefer a peer's fresh entry
            // if one landed, else load again.
            cache[paletteUuid]?.let { return it }
        }
        // Persistently contended — serve a fresh build without caching it rather than risk pinning
        // a stale one.
        val snapshot = loader(paletteUuid) ?: return null
        return expand(snapshot, fixtures())
    }

    fun snapshot(paletteUuid: UUID): PaletteSnapshot? = expanded(paletteUuid)?.snapshot

    /** The literal this palette holds for one fixture and property, or null when uncovered. */
    fun literalFor(paletteUuid: UUID, fixtureKey: String, propertyName: String): String? =
        expanded(paletteUuid)?.literalFor(fixtureKey, propertyName)

    fun invalidate(paletteUuid: UUID) {
        // Bump before evicting — see [expanded]. Reversing these two lines reopens the
        // permanently-stale-cache race.
        version++
        cache.remove(paletteUuid)
    }

    fun invalidateAll() {
        version++
        if (cache.isNotEmpty()) {
            cache.clear()
            logger.debug("palette cache cleared")
        }
    }

    companion object {
        /** Fill attempts before giving up on caching and just serving a fresh build. */
        private const val MAX_FILL_ATTEMPTS = 3

        /**
         * Flatten a snapshot against the live patch. Group rows first, then fixture rows, so a
         * fixture row wins. Unknown groups and empty groups contribute nothing — a palette that
         * outlived a group is a dead reference on those members, reported as
         * [AssignmentHealth.MissingPaletteEntry] at resolve time rather than guessed at here.
         */
        internal fun expand(snapshot: PaletteSnapshot, fixtures: Fixtures): ExpandedPalette {
            val byFixture = HashMap<String, MutableMap<String, String>>()

            fun put(fixtureKey: String, propertyName: String, value: String) {
                byFixture.getOrPut(fixtureKey) { HashMap() }[canonicalPropertyName(propertyName)] = value
            }

            for (entry in snapshot.entries) {
                val group = entry.target as? TargetRef.Group ?: continue
                val members = runCatching { fixtures.untypedGroup(group.key) }.getOrNull()
                    ?.fixtures
                    ?.filterIsInstance<Fixture>()
                    ?: continue
                for (member in members) put(member.key, entry.propertyName, entry.value)
            }
            for (entry in snapshot.entries) {
                val fixture = entry.target as? TargetRef.Fixture ?: continue
                put(fixture.key, entry.propertyName, entry.value)
            }

            return ExpandedPalette(snapshot, byFixture)
        }
    }
}

/**
 * Read one palette and its entries into a [PaletteSnapshot]. Opens its own transaction, so it
 * must **not** be called from inside `FxEngine`'s `cueAssignmentsLock`.
 */
internal fun loadPaletteSnapshot(database: Database, paletteUuid: UUID): PaletteSnapshot? =
    transaction(database) {
        val palette = DaoPalette.find { DaoPalettes.uuid eq paletteUuid }.firstOrNull()
            ?: return@transaction null
        PaletteSnapshot(
            paletteId = palette.id.value,
            paletteUuid = palette.uuid,
            name = palette.name,
            type = palette.paletteType,
            entries = palette.entries
                .orderBy(DaoPaletteEntries.sortOrder to SortOrder.ASC)
                .mapNotNull { row ->
                    val target = TargetRef.ofOrNull(row.targetType, row.targetKey) ?: return@mapNotNull null
                    PaletteEntryRow(target, row.propertyName, row.value)
                },
        )
    }
