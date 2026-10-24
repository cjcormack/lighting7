package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.models.timingOf
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoPalette
import uk.me.cormack.lighting7.models.DaoPaletteRow
import uk.me.cormack.lighting7.models.DaoPaletteRows
import uk.me.cormack.lighting7.models.DaoPalettes
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One stored template row. [target] is null for a **generic** row, which takes its targets from
 * whatever applies the template; non-null for a **per-fixture** row, whose value is specific to
 * that head (a focus position being the case that makes the distinction worth having).
 */
data class PaletteRowEntry(
    val target: TargetRef?,
    val propertyName: String,
    /** A [PaletteIntent] in serialised form — an intent, never a literal. */
    val value: String,
) {
    val isDeferred: Boolean get() = target == null
}

/**
 * A template's identity and contents, read out of the DB and held immutably.
 *
 * [rows] and [effect] are mutually exclusive — a template holds a value **or** an effect (D1),
 * enforced at the write boundary. Both empty means a template whose rows have all been deleted,
 * which the composer treats as asserting nothing rather than as an error.
 */
data class PaletteSnapshot(
    val paletteId: Int,
    val paletteUuid: UUID,
    val name: String,
    val fadeDurationMs: Long?,
    /** Rows in `sortOrder`. Empty for an effect template. */
    val rows: List<PaletteRowEntry>,
    /**
     * The one effect an effect template holds, null for a value template (D2). It names no target
     * (D3): the cook runs it on each target of the layer pressing it.
     */
    val effect: StoredFx? = null,
)

/**
 * The one family this template is in: its first row with a known property, else its effect's
 * library category. `DaoPalette.familyOf` is the same derivation over stored rows. The snapshot
 * holds only rows whose target parses (`loadPaletteSnapshot` drops a corrupt one), so the two can
 * differ for a template whose lowest-ordered row is corrupt, and only for a row a press would refuse.
 */
internal fun PaletteSnapshot.familyOf(): PropertyMaskGroup? =
    paletteFamily(rows.map { it.propertyName }, effect?.family)

/** The one derivation behind both `familyOf`s. */
internal fun paletteFamily(propertyNamesInOrder: List<String>, fxCategory: String?): PropertyMaskGroup? =
    propertyNamesInOrder.firstNotNullOfOrNull { PaletteProperty.ofOrNull(it)?.family }
        ?: fxCategory?.let { maskGroupForFxFamily(it) }

/**
 * Templates, cached by uuid.
 *
 * Deliberately **thinner than [LookRegistry]**, and the difference is the whole point of the two
 * entities being separate. A Look is cached *expanded*: its group rows are flattened against the
 * live patch into `fixtureKey → property → literal`, which is why a patch or group change has to
 * invalidate every entry. A template has no group rows and no literals to flatten — its rows are
 * intents resolved per head by [PaletteResolver] at cook time — so there is nothing patch-shaped
 * in the cache and a repatch cannot make an entry stale. Only a template's own edit can.
 *
 * A template's **effect** keeps that true only because it is always generic (D3): it names no
 * target, so it holds no expanded fixture keys either. A bound, group-targeted template effect
 * would put patch-shaped data back in this cache and is refused at the write boundary for exactly
 * this reason.
 *
 * The generation re-check in [snapshot] is the same guard [LookRegistry.expanded] carries, and for
 * the same reason: an invalidation landing while a load is in flight has no entry to evict, so a
 * racing load would insert its pre-edit snapshot *after* the invalidation and serve it indefinitely
 * — a save that reported success while the rig kept showing the old value.
 */
class PaletteRegistry(
    private val loader: (UUID) -> PaletteSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(PaletteRegistry::class.java)

    /**
     * The cache and the versions it belongs to, published as ONE volatile reference. They
     * must never be separate fields: `TypedParams` keys every running effect's colour cache
     * on [versionFor], and with a version bumped before the eviction, a tick landing between
     * the two writes read the *new* version paired with the *pre-edit* entry — and re-seeded
     * the stale colour under the new version, serving it until the template was edited again.
     * One reference makes "observed version V" imply "observed the cache state labelled V".
     */
    private class Generation(
        /**
         * Bumped by [invalidateAll] alone — the "some template you may name changed, and I
         * cannot say which" component of every uuid's version.
         */
        val epoch: Long,
        /**
         * Per-uuid edit counters, carried forward across generations rather than reset, so a
         * single uuid's version is monotone even across an [invalidateAll].
         */
        val uuidVersions: Map<UUID, Long>,
    ) {
        val cache = ConcurrentHashMap<UUID, PaletteSnapshot>()
    }

    @Volatile
    private var generation = Generation(epoch = 0L, uuidVersions = emptyMap())

    // Serialises invalidations (each builds the next generation from the current one);
    // reads never take it.
    private val invalidateLock = Any()

    /**
     * Every uuid [snapshot] has been asked for, whether or not it resolved — the set
     * [invalidateAll] re-warms.
     *
     * The cached keys are not that set, and the difference is the case the un-scoped epoch bump
     * exists for: [snapshot] deliberately does not cache a miss, so a uuid with no template *yet*
     * (an effect parameter naming one an import or clone is about to create) is by definition not
     * in the cache — and neither is a reference that cannot resolve at all, a per-fixture template
     * or one in the wrong family, which every list change would otherwise re-resolve from the tick
     * thread for as long as the effect runs.
     *
     * Nothing is ever removed, and that is the point: a uuid that answers null today is exactly
     * the one whose create has to reach a running effect. It is bounded by the distinct templates
     * this project's cooks and effects have named — the registry is per [uk.me.cormack.lighting7.show.Show],
     * so a project switch starts a fresh one.
     */
    private val requested: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * The version a running effect's colour cache watches, for the **specific** templates its
     * parameters name — see `createFxWithPalettes`.
     *
     * Scoping it matters twice over. An effect naming no template gets a *constant*, which is
     * nearly every effect: those colour caches now survive template edits instead of being
     * dropped by all of them. And an effect naming template A is untouched by an edit to B,
     * where the single global counter re-resolved every colour parameter on the desk — each
     * re-resolve a [snapshot] call that misses and loads.
     *
     * A sum is safe because every component only ever increases: [Generation.epoch] on any list
     * change, a uuid's own counter on its own edit. Any invalidation that could touch
     * [paletteUuids] therefore strictly increases the total, and nothing else moves it. Read off
     * a single [generation] reference, so the invariant above holds for a scoped version too.
     */
    fun versionFor(paletteUuids: Collection<UUID>): Long {
        if (paletteUuids.isEmpty()) return 0L
        val gen = generation
        var version = gen.epoch
        for (uuid in paletteUuids) version += gen.uuidVersions[uuid] ?: 0L
        return version
    }

    fun snapshot(paletteUuid: UUID): PaletteSnapshot? {
        requested += paletteUuid
        repeat(MAX_FILL_ATTEMPTS) {
            val gen = generation
            gen.cache[paletteUuid]?.let { return it }
            val snapshot = loader(paletteUuid) ?: return null
            // The re-check is the in-flight-load guard from [LookRegistry.expanded]: an
            // invalidation landing while a load is in flight swaps the generation itself, so
            // a pre-edit snapshot inserted after this check goes into the superseded map,
            // unreachable from the new generation — it can never be served indefinitely.
            if (generation === gen) {
                return gen.cache.putIfAbsent(paletteUuid, snapshot) ?: snapshot
            }
        }
        // Persistently contended — serve a fresh read without caching it rather than risk pinning
        // a stale one.
        return loader(paletteUuid)
    }

    /**
     * Re-read [paletteUuid] and publish it together with its version bump — the answer to a
     * template's *contents* changing.
     *
     * The read happens first, on the calling thread, for the reason spelled out in
     * [invalidateAll]: the bump is what a running effect naming this template watches, so
     * publishing it ahead of the new snapshot hands the next 50 Hz pass a miss, and
     * `loadPaletteSnapshot` opens a transaction. Callers used to do this in two steps
     * (`invalidate` then `snapshot`), which left exactly that window open for one uuid.
     *
     * A read that fails still bumps and evicts — the stored contents have changed, so serving the
     * pre-edit snapshot is not an option, and the miss it leaves behind is the two-step behaviour.
     */
    fun refresh(paletteUuid: UUID) {
        val reloaded = try {
            loader(paletteUuid)
        } catch (t: Throwable) {
            logger.warn("template {} could not be re-read after an edit", paletteUuid, t)
            null
        }
        synchronized(invalidateLock) {
            val old = generation
            val next = Generation(
                // Only this uuid's counter moves: an effect naming a different template keeps
                // its resolved colour, rather than re-resolving because something unrelated
                // was retuned.
                epoch = old.epoch,
                uuidVersions = old.uuidVersions +
                    (paletteUuid to (old.uuidVersions[paletteUuid] ?: 0L) + 1L),
            )
            for ((uuid, snapshot) in old.cache) {
                if (uuid != paletteUuid) next.cache[uuid] = snapshot
            }
            if (reloaded != null) {
                requested += paletteUuid
                next.cache[paletteUuid] = reloaded
            }
            generation = next
        }
    }

    /**
     * Drop every entry — the answer to a template *list* change (create / rename / delete), where
     * which snapshots are now wrong is not knowable from the event.
     *
     * The epoch bump is deliberately un-scoped for the same reason: an effect can name a uuid that
     * has no template yet, and the create that gives it one has to reach that effect's colour
     * cache. So one list change moves every scoped version by one.
     *
     * The re-warm covers every uuid anyone has *asked* for, not merely what is cached — see
     * [requested] for why those differ and why the difference is the whole point of the bump.
     *
     * **Reload first, publish once.** The bump is what every running effect's colour cache watches,
     * so publishing it over an empty cache hands the next 50 Hz pass a guaranteed miss — and
     * `loadPaletteSnapshot` opens a transaction, on the tick thread, against a size-1 pool. That
     * is the failure this re-warm exists to prevent, so it cannot be an afterthought *following*
     * the swap: the reads happen first, on the calling thread (always a route thread, via
     * `Fixtures.paletteListChanged`), and the bump becomes visible together with the cache that
     * satisfies it. Same "one reference, one observation" rule the [Generation] KDoc argues for.
     *
     * A read that fails is logged and dropped rather than thrown: this was a pure memory swap
     * before, and the listener chain it runs in (`Fixtures.paletteListChanged`) is unguarded, so
     * a `SQLITE_BUSY` here would abort the loop before `BroadcastSocket` ever told the clients the
     * list changed. A missed re-warm is a latency bug; a missed broadcast is a stale UI.
     */
    fun invalidateAll() {
        // Read from the generation being replaced, before anything is published.
        val before = generation
        val reloaded = LinkedHashMap<UUID, PaletteSnapshot>()
        for (uuid in requested) {
            try {
                loader(uuid)?.let { reloaded[uuid] = it }
            } catch (t: Throwable) {
                logger.warn("template {} could not be re-warmed after a list change", uuid, t)
            }
        }
        synchronized(invalidateLock) {
            val old = generation
            val next = Generation(epoch = old.epoch + 1, uuidVersions = old.uuidVersions)
            // Carry the reloads only if nothing invalidated while they were being read. Anything
            // that did has newer content than this, and publishing ours would pin the stale one —
            // the same trap [snapshot]'s generation re-check exists for. Two list changes racing
            // therefore publish a cold cache, which is only the pre-C4 cost, once.
            if (old === before) {
                next.cache.putAll(reloaded)
            } else {
                logger.debug("template cache re-warm discarded: invalidated while reading")
            }
            generation = next
        }
        logger.debug("template cache cleared, {} entries re-warmed", reloaded.size)
    }

    companion object {
        private const val MAX_FILL_ATTEMPTS = 3
    }
}

/**
 * Read one template and its rows. Opens its own transaction, so it must **not** be called from
 * inside `FxEngine`'s `cueAssignmentsLock` — same rule as [loadLookSnapshot].
 */
internal fun loadPaletteSnapshot(database: Database, paletteUuid: UUID): PaletteSnapshot? =
    transaction(database) {
        val template = DaoPalette.find { DaoPalettes.uuid eq paletteUuid }.firstOrNull()
            ?: return@transaction null
        PaletteSnapshot(
            paletteId = template.id.value,
            paletteUuid = template.uuid,
            name = template.name,
            fadeDurationMs = template.fadeDuration?.toMillis(),
            // A query of its own rather than `template.rows`: ordering a referrer collection the
            // caller's transaction has already loaded throws, and the Timeline's batch decides its
            // presses inside one transaction that may have read this palette's rows already.
            rows = DaoPaletteRow.find { DaoPaletteRows.palette eq template.id }
                .orderBy(DaoPaletteRows.sortOrder to SortOrder.ASC)
                .mapNotNull { row ->
                    // A deferred row legitimately has no target; a row whose discriminator names no
                    // known arm is corrupt and is dropped rather than guessed at.
                    val target = if (row.targetType == DEFERRED_TARGET_TYPE) {
                        null
                    } else {
                        TargetRef.ofOrNull(row.targetType, row.targetKey) ?: return@mapNotNull null
                    }
                    PaletteRowEntry(
                        target = target,
                        propertyName = row.attribute,
                        value = row.value,
                    )
                },
            // One more query inside the transaction this already opens, rather than a second
            // transaction: `invalidateAll`'s re-warm reads every requested template on the calling
            // thread against a size-1 pool (`FU-TMPL-REWARM-BOUND`), so the cost that matters is
            // round trips, not rows.
            effect = template.effect?.let { fx ->
                StoredFx(
                    fxType = fx.fxType,
                    family = fx.family,
                    propertyName = fx.attribute,
                    cycle = fx.cycle,
                    blendMode = fx.blendMode,
                    order = fx.order,
                    phaseOffset = fx.phaseOffset,
                    elementMode = fx.elementMode,
                    elementFilter = fx.elementFilter,
                    timing = timingOf(fx.timing),
                    parameters = fx.parameters,
                    speedMasterUuid = fx.speedMasterUuid,
                    rateSpeedMasterUuid = fx.rateSpeedMasterUuid,
                    seconds = fx.seconds,
                    amount = fx.amount,
                    direction = fx.direction,
                    runs = fx.runs,
                    spread = fx.spread,
                    wings = fx.wings,
                    segments = fx.segments,
                    parts = fx.parts,
                    uuid = fx.uuid,
                )
            },
        )
    }
