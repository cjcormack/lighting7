package uk.me.cormack.lighting7.fx

import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.DEFERRED_TARGET_TYPE
import uk.me.cormack.lighting7.models.DaoTemplate
import uk.me.cormack.lighting7.models.DaoTemplateRows
import uk.me.cormack.lighting7.models.DaoTemplates
import uk.me.cormack.lighting7.models.TargetRef
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One stored template row. [target] is null for a **generic** row, which takes its targets from
 * whatever applies the template; non-null for a **per-fixture** row, whose value is specific to
 * that head (a focus position being the case that makes the distinction worth having).
 */
data class TemplateRowEntry(
    val target: TargetRef?,
    val propertyName: String,
    /** A [TemplateIntent] in serialised form — an intent, never a literal. */
    val value: String,
) {
    val isDeferred: Boolean get() = target == null
}

/** A template's identity and contents, read out of the DB and held immutably. */
data class TemplateSnapshot(
    val templateId: Int,
    val templateUuid: UUID,
    val name: String,
    val fadeDurationMs: Long?,
    /** Rows in `sortOrder`. */
    val rows: List<TemplateRowEntry>,
)

/**
 * Templates, cached by uuid.
 *
 * Deliberately **thinner than [LookRegistry]**, and the difference is the whole point of the two
 * entities being separate. A Look is cached *expanded*: its group rows are flattened against the
 * live patch into `fixtureKey → property → literal`, which is why a patch or group change has to
 * invalidate every entry. A template has no group rows and no literals to flatten — its rows are
 * intents resolved per head by [TemplateResolver] at cook time — so there is nothing patch-shaped
 * in the cache and a repatch cannot make an entry stale. Only a template's own edit can.
 *
 * The generation re-check in [snapshot] is the same guard [LookRegistry.expanded] carries, and for
 * the same reason: an invalidation landing while a load is in flight has no entry to evict, so a
 * racing load would insert its pre-edit snapshot *after* the invalidation and serve it indefinitely
 * — a save that reported success while the rig kept showing the old value.
 */
class TemplateRegistry(
    private val loader: (UUID) -> TemplateSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(TemplateRegistry::class.java)

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
        val cache = ConcurrentHashMap<UUID, TemplateSnapshot>()
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
     * parameters name — see `createEffectWithTemplates`.
     *
     * Scoping it matters twice over. An effect naming no template gets a *constant*, which is
     * nearly every effect: those colour caches now survive template edits instead of being
     * dropped by all of them. And an effect naming template A is untouched by an edit to B,
     * where the single global counter re-resolved every colour parameter on the desk — each
     * re-resolve a [snapshot] call that misses and loads.
     *
     * A sum is safe because every component only ever increases: [Generation.epoch] on any list
     * change, a uuid's own counter on its own edit. Any invalidation that could touch
     * [templateUuids] therefore strictly increases the total, and nothing else moves it. Read off
     * a single [generation] reference, so the invariant above holds for a scoped version too.
     */
    fun versionFor(templateUuids: Collection<UUID>): Long {
        if (templateUuids.isEmpty()) return 0L
        val gen = generation
        var version = gen.epoch
        for (uuid in templateUuids) version += gen.uuidVersions[uuid] ?: 0L
        return version
    }

    fun snapshot(templateUuid: UUID): TemplateSnapshot? {
        requested += templateUuid
        repeat(MAX_FILL_ATTEMPTS) {
            val gen = generation
            gen.cache[templateUuid]?.let { return it }
            val snapshot = loader(templateUuid) ?: return null
            // The re-check is the in-flight-load guard from [LookRegistry.expanded]: an
            // invalidation landing while a load is in flight swaps the generation itself, so
            // a pre-edit snapshot inserted after this check goes into the superseded map,
            // unreachable from the new generation — it can never be served indefinitely.
            if (generation === gen) {
                return gen.cache.putIfAbsent(templateUuid, snapshot) ?: snapshot
            }
        }
        // Persistently contended — serve a fresh read without caching it rather than risk pinning
        // a stale one.
        return loader(templateUuid)
    }

    /**
     * Re-read [templateUuid] and publish it together with its version bump — the answer to a
     * template's *contents* changing.
     *
     * The read happens first, on the calling thread, for the reason spelled out in
     * [invalidateAll]: the bump is what a running effect naming this template watches, so
     * publishing it ahead of the new snapshot hands the next 50 Hz pass a miss, and
     * `loadTemplateSnapshot` opens a transaction. Callers used to do this in two steps
     * (`invalidate` then `snapshot`), which left exactly that window open for one uuid.
     *
     * A read that fails still bumps and evicts — the stored contents have changed, so serving the
     * pre-edit snapshot is not an option, and the miss it leaves behind is the two-step behaviour.
     */
    fun refresh(templateUuid: UUID) {
        val reloaded = try {
            loader(templateUuid)
        } catch (t: Throwable) {
            logger.warn("template {} could not be re-read after an edit", templateUuid, t)
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
                    (templateUuid to (old.uuidVersions[templateUuid] ?: 0L) + 1L),
            )
            for ((uuid, snapshot) in old.cache) {
                if (uuid != templateUuid) next.cache[uuid] = snapshot
            }
            if (reloaded != null) {
                requested += templateUuid
                next.cache[templateUuid] = reloaded
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
     * `loadTemplateSnapshot` opens a transaction, on the tick thread, against a size-1 pool. That
     * is the failure this re-warm exists to prevent, so it cannot be an afterthought *following*
     * the swap: the reads happen first, on the calling thread (always a route thread, via
     * `Fixtures.templateListChanged`), and the bump becomes visible together with the cache that
     * satisfies it. Same "one reference, one observation" rule the [Generation] KDoc argues for.
     *
     * A read that fails is logged and dropped rather than thrown: this was a pure memory swap
     * before, and the listener chain it runs in (`Fixtures.templateListChanged`) is unguarded, so
     * a `SQLITE_BUSY` here would abort the loop before `BroadcastSocket` ever told the clients the
     * list changed. A missed re-warm is a latency bug; a missed broadcast is a stale UI.
     */
    fun invalidateAll() {
        // Read from the generation being replaced, before anything is published.
        val before = generation
        val reloaded = LinkedHashMap<UUID, TemplateSnapshot>()
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
internal fun loadTemplateSnapshot(database: Database, templateUuid: UUID): TemplateSnapshot? =
    transaction(database) {
        val template = DaoTemplate.find { DaoTemplates.uuid eq templateUuid }.firstOrNull()
            ?: return@transaction null
        TemplateSnapshot(
            templateId = template.id.value,
            templateUuid = template.uuid,
            name = template.name,
            fadeDurationMs = template.fadeDurationMs,
            rows = template.rows
                .orderBy(DaoTemplateRows.sortOrder to SortOrder.ASC)
                .mapNotNull { row ->
                    // A deferred row legitimately has no target; a row whose discriminator names no
                    // known arm is corrupt and is dropped rather than guessed at.
                    val target = if (row.targetType == DEFERRED_TARGET_TYPE) {
                        null
                    } else {
                        TargetRef.ofOrNull(row.targetType, row.targetKey) ?: return@mapNotNull null
                    }
                    TemplateRowEntry(
                        target = target,
                        propertyName = row.propertyName,
                        value = row.value,
                    )
                },
        )
    }
