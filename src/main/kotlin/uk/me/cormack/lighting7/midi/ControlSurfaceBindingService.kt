package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoProject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistence + in-memory resolver for control-surface bindings. Bindings are cached by
 * `(projectId, deviceTypeKey, controlId, bank)` and resolved by [resolve] on each inbound
 * MIDI event, so the hot path is served from memory rather than touching the DB per event.
 *
 * Lazy-loads the cache for a project on first access. Mutations write through to the DB
 * and update the cache atomically under a per-project lock.
 *
 * Emits [BindingChange] events so WebSocket / in-process consumers can invalidate derived
 * state without re-reading the full binding list.
 */
class ControlSurfaceBindingService(
    private val database: Database,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ControlSurfaceBindingService::class.java)
    }

    /**
     * Resolved in-memory form of a persisted binding. [id] is the primary key; the remaining
     * fields mirror the DB columns with [target] deserialized from JSON.
     */
    data class ResolvedBinding(
        val id: Int,
        val projectId: Int,
        val deviceTypeKey: String,
        val controlId: String,
        val bank: String?,
        val target: BindingTarget,
        val takeoverPolicy: BindingTakeoverPolicy?,
        val sortOrder: Int,
    )

    /** Emitted on any cache-mutating operation so UIs can react. */
    sealed class BindingChange {
        abstract val projectId: Int
        data class Added(override val projectId: Int, val binding: ResolvedBinding) : BindingChange()
        data class Updated(override val projectId: Int, val binding: ResolvedBinding) : BindingChange()
        data class Removed(override val projectId: Int, val bindingId: Int) : BindingChange()
        data class Reloaded(override val projectId: Int) : BindingChange()
    }

    /**
     * Per-project cache. `byId` is an insertion-ordered map used by list / get / CRUD paths.
     * `byControl` is the hot-path index used by [resolve]: each `(deviceTypeKey, controlId)`
     * lookup key maps to at most one bank-agnostic binding and one entry per bank, so the
     * inner map is tiny and resolution is O(1) plus a bank precedence check.
     *
     * Both maps are mutated under the per-project lock in [lockFor]; reads are lock-free.
     */
    private class ProjectCache {
        val byId: MutableMap<Int, ResolvedBinding> = LinkedHashMap()
        val byControl: MutableMap<ControlKey, MutableMap<String?, ResolvedBinding>> = HashMap()
    }

    /** Lookup key for the hot-path resolver. Interned at boundary so string equality is hashmap-cheap. */
    private data class ControlKey(val deviceTypeKey: String, val controlId: String)

    private val cache = ConcurrentHashMap<Int, ProjectCache>()

    // Projects whose cache has been loaded at least once. Guards against repeat DB hits
    // on every resolve() for well-known "no bindings" projects.
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    private val _changes = MutableSharedFlow<BindingChange>(
        replay = 0,
        extraBufferCapacity = 64,
    )
    val changes: SharedFlow<BindingChange> = _changes.asSharedFlow()

    private val lockSeq = AtomicLong(0L)
    private val locks = ConcurrentHashMap<Int, Any>()
    private fun lockFor(projectId: Int): Any = locks.computeIfAbsent(projectId) {
        lockSeq.incrementAndGet()
        Object()
    }

    /** Ensure the cache is populated for [projectId]. Safe to call repeatedly. */
    fun ensureLoaded(projectId: Int) {
        if (loaded.contains(projectId)) return
        synchronized(lockFor(projectId)) {
            if (loaded.contains(projectId)) return
            val list = transaction(database) {
                DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.project eq projectId }
                    .orderBy(DaoControlSurfaceBindings.sortOrder to SortOrder.ASC)
                    .map { it.toResolved() }
            }
            val pc = ProjectCache()
            list.forEach { pc.install(it) }
            cache[projectId] = pc
            loaded.add(projectId)
            logger.info("Loaded ${list.size} control-surface bindings for project $projectId")
        }
    }

    /** All bindings for a project, insertion-ordered. */
    fun list(projectId: Int): List<ResolvedBinding> {
        ensureLoaded(projectId)
        return cache[projectId]?.byId?.values?.toList() ?: emptyList()
    }

    fun get(projectId: Int, bindingId: Int): ResolvedBinding? {
        ensureLoaded(projectId)
        return cache[projectId]?.byId?.get(bindingId)
    }

    /**
     * Resolve an inbound event from `(deviceTypeKey, controlId)` on the given [activeBank].
     * Exact-bank match wins over a bank-agnostic (bank = null) binding.
     */
    fun resolve(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        activeBank: String?,
    ): ResolvedBinding? {
        ensureLoaded(projectId)
        val byBank = cache[projectId]?.byControl?.get(ControlKey(deviceTypeKey, controlId)) ?: return null
        return byBank[activeBank] ?: byBank[null]
    }

    private fun ProjectCache.install(binding: ResolvedBinding) {
        byId[binding.id] = binding
        byControl
            .getOrPut(ControlKey(binding.deviceTypeKey, binding.controlId)) { HashMap() }[binding.bank] = binding
    }

    private fun ProjectCache.uninstall(binding: ResolvedBinding) {
        byId.remove(binding.id)
        val key = ControlKey(binding.deviceTypeKey, binding.controlId)
        val byBank = byControl[key] ?: return
        byBank.remove(binding.bank)
        if (byBank.isEmpty()) byControl.remove(key)
    }

    /**
     * Create a binding. Throws [IllegalStateException] if a binding already exists at the
     * same `(project, deviceTypeKey, controlId, bank)` slot.
     */
    fun create(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        bank: String?,
        target: BindingTarget,
        takeoverPolicy: BindingTakeoverPolicy? = null,
        sortOrder: Int = 0,
    ): ResolvedBinding {
        ensureLoaded(projectId)
        val resolved = synchronized(lockFor(projectId)) {
            val existing = cache[projectId]?.byControl
                ?.get(ControlKey(deviceTypeKey, controlId))
                ?.get(bank)
            check(existing == null) {
                "Binding already exists for $deviceTypeKey.$controlId (bank=$bank) in project $projectId"
            }
            val entity = transaction(database) {
                DaoControlSurfaceBinding.new {
                    this.project = DaoProject.findById(projectId)
                        ?: throw IllegalArgumentException("Project $projectId not found")
                    this.deviceTypeKey = deviceTypeKey
                    this.controlId = controlId
                    this.bank = bank
                    this.targetType = target.discriminator()
                    this.targetPayload = BindingTargetJson.encodeToString(target)
                    this.takeoverPolicy = takeoverPolicy?.name
                    this.sortOrder = sortOrder
                }.toResolved()
            }
            cache.getOrPut(projectId) { ProjectCache() }.install(entity)
            entity
        }
        _changes.tryEmit(BindingChange.Added(projectId, resolved))
        return resolved
    }

    /**
     * Partially update a binding. Nulls in the parameters mean "don't change"; to clear
     * [bank] or [takeoverPolicy] use [clearBank] / [clearTakeoverPolicy] separately — JVM
     * signatures can't distinguish "not provided" from "set to null" for nullable params.
     */
    fun update(
        projectId: Int,
        bindingId: Int,
        deviceTypeKey: String? = null,
        controlId: String? = null,
        target: BindingTarget? = null,
        sortOrder: Int? = null,
        bankUpdate: FieldUpdate<String?> = FieldUpdate.NoChange,
        takeoverPolicyUpdate: FieldUpdate<BindingTakeoverPolicy?> = FieldUpdate.NoChange,
    ): ResolvedBinding? {
        ensureLoaded(projectId)
        val resolved = synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return null
            val existing = pc.byId[bindingId] ?: return null
            val newDeviceTypeKey = deviceTypeKey ?: existing.deviceTypeKey
            val newControlId = controlId ?: existing.controlId
            val newBank = when (bankUpdate) {
                is FieldUpdate.NoChange -> existing.bank
                is FieldUpdate.Set -> bankUpdate.value
            }
            val slotClash = pc.byControl[ControlKey(newDeviceTypeKey, newControlId)]?.get(newBank)
            check(slotClash == null || slotClash.id == bindingId) {
                "Binding already exists for $newDeviceTypeKey.$newControlId (bank=$newBank) in project $projectId"
            }
            val entity = transaction(database) {
                val row = DaoControlSurfaceBinding.findById(bindingId) ?: return@transaction null
                if (row.project.id.value != projectId) return@transaction null
                if (deviceTypeKey != null) row.deviceTypeKey = deviceTypeKey
                if (controlId != null) row.controlId = controlId
                if (bankUpdate is FieldUpdate.Set) row.bank = bankUpdate.value
                if (target != null) {
                    row.targetType = target.discriminator()
                    row.targetPayload = BindingTargetJson.encodeToString(target)
                }
                if (takeoverPolicyUpdate is FieldUpdate.Set) {
                    row.takeoverPolicy = takeoverPolicyUpdate.value?.name
                }
                if (sortOrder != null) row.sortOrder = sortOrder
                row.toResolved()
            } ?: return null
            pc.uninstall(existing)
            pc.install(entity)
            entity
        }
        _changes.tryEmit(BindingChange.Updated(projectId, resolved))
        return resolved
    }

    /** Delete a binding. Returns true if it existed. */
    fun delete(projectId: Int, bindingId: Int): Boolean {
        ensureLoaded(projectId)
        val removed = synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return false
            val existing = pc.byId[bindingId] ?: return false
            val deletedDb = transaction(database) {
                val row = DaoControlSurfaceBinding.findById(bindingId) ?: return@transaction false
                if (row.project.id.value != projectId) return@transaction false
                row.delete()
                true
            }
            if (!deletedDb) return false
            pc.uninstall(existing)
            true
        }
        if (removed) _changes.tryEmit(BindingChange.Removed(projectId, bindingId))
        return removed
    }

    /** Drop the cache entry for a project and force reload on next access. */
    fun invalidate(projectId: Int) {
        synchronized(lockFor(projectId)) {
            cache.remove(projectId)
            loaded.remove(projectId)
        }
        _changes.tryEmit(BindingChange.Reloaded(projectId))
    }

    /** Two-state sentinel to distinguish "no change" from "set to null" in update APIs. */
    sealed class FieldUpdate<out T> {
        data object NoChange : FieldUpdate<Nothing>()
        data class Set<T>(val value: T) : FieldUpdate<T>()
    }

    /**
     * Test-only: seed the in-memory cache for a project without touching the database.
     * Marks the project as loaded so subsequent reads don't trigger a DB query. This
     * lets resolver / list / get tests run against a pure cache without an H2 backend.
     */
    internal fun seedCacheForTest(projectId: Int, bindings: List<ResolvedBinding>) {
        synchronized(lockFor(projectId)) {
            val pc = ProjectCache()
            bindings.forEach { pc.install(it) }
            cache[projectId] = pc
            loaded.add(projectId)
        }
    }

    private fun DaoControlSurfaceBinding.toResolved(): ResolvedBinding {
        val target = BindingTargetJson.decodeFromString<BindingTarget>(targetPayload)
        val policy = takeoverPolicy?.let {
            try {
                BindingTakeoverPolicy.valueOf(it)
            } catch (_: IllegalArgumentException) {
                null
            }
        }
        return ResolvedBinding(
            id = id.value,
            projectId = project.id.value,
            deviceTypeKey = deviceTypeKey,
            controlId = controlId,
            bank = bank,
            target = target,
            takeoverPolicy = policy,
            sortOrder = sortOrder,
        )
    }
}

/**
 * Return the `@SerialName` discriminator for a [BindingTarget] subtype. Read reflectively
 * from the concrete class's annotation so it always agrees with the JSON wire format —
 * renaming a `@SerialName` only requires touching the subclass declaration.
 */
internal fun BindingTarget.discriminator(): String {
    val klass = this::class
    val annotated = klass.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
    return annotated ?: klass.simpleName
        ?: error("BindingTarget subclass ${klass.java.name} has no @SerialName")
}
