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
 * The version re-check in [snapshot] is the same guard [LookRegistry.expanded] carries, and for the
 * same reason: an invalidation landing while a load is in flight has no entry to evict, so a racing
 * load would insert its pre-edit snapshot *after* the invalidation and serve it indefinitely — a
 * save that reported success while the rig kept showing the old value.
 */
class TemplateRegistry(
    private val loader: (UUID) -> TemplateSnapshot?,
) {
    private val logger = LoggerFactory.getLogger(TemplateRegistry::class.java)
    private val cache = ConcurrentHashMap<UUID, TemplateSnapshot>()

    @Volatile
    var version: Long = 0L
        private set

    fun snapshot(templateUuid: UUID): TemplateSnapshot? {
        cache[templateUuid]?.let { return it }
        repeat(MAX_FILL_ATTEMPTS) {
            val versionBefore = version
            val snapshot = loader(templateUuid) ?: return null
            if (version == versionBefore) {
                return cache.putIfAbsent(templateUuid, snapshot) ?: snapshot
            }
            cache[templateUuid]?.let { return it }
        }
        // Persistently contended — serve a fresh read without caching it rather than risk pinning
        // a stale one.
        return loader(templateUuid)
    }

    fun invalidate(templateUuid: UUID) {
        // Bump before evicting — see the class doc. Reversing these two lines reopens the
        // permanently-stale-cache race.
        version++
        cache.remove(templateUuid)
    }

    fun invalidateAll() {
        version++
        if (cache.isNotEmpty()) {
            cache.clear()
            logger.debug("template cache cleared")
        }
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
