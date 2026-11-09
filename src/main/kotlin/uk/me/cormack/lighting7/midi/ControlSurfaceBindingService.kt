package uk.me.cormack.lighting7.midi

import uk.me.cormack.lighting7.fixture.TriggerIndex
import uk.me.cormack.lighting7.fixture.TriggerNotStorableException
import uk.me.cormack.lighting7.fixture.CommandIndex
import uk.me.cormack.lighting7.fixture.CommandNotStorableException

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.describeAssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoProject
import uk.me.cormack.lighting7.models.DaoSurfaceJob
import uk.me.cormack.lighting7.models.DaoSurfaceJobs
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Persistence + in-memory resolver for control-surface bindings and the jobs regions take. Bindings
 * are cached by `(projectId, deviceTypeKey, controlId, part, bank)`, jobs by `(projectId,
 * deviceTypeKey, region, bank)`, and both resolved by [resolve] on each inbound MIDI event, so the
 * hot path is served from memory rather than touching the DB per event.
 *
 * Every write comes through here — the routes — so the refusals live here:
 * a binding its function's [Spec] does not admit (the subject, the How, the control part's input) is
 * refused by name, and dispatch takes what is stored as given.
 *
 * Lazy-loads the cache for a project on first access. Mutations write through to the DB
 * and update the cache atomically under a per-project lock.
 *
 * Emits [BindingChange] events so WebSocket / in-process consumers can invalidate derived
 * state without re-reading the full binding list.
 */
class ControlSurfaceBindingService(
    private val database: Database,
    /**
     * Supplies the snapshot needed to tag each cached binding with an [AssignmentHealth].
     * Called under the per-project lock during cache rebuild and on [invalidateHealth].
     * Returns null when no snapshot is available (tests, pre-show init) — bindings fall
     * back to [AssignmentHealth.Ok] in that case.
     */
    private val healthContextProvider: ((projectId: Int) -> BindingHealthEvaluator.Context?)? = null,
    /**
     * A device type's profile, for the job arm of [resolve] and the job writes. Defaults to the live
     * registry; injectable so tests can resolve against a synthetic profile.
     */
    private val profileFor: (deviceTypeKey: String) -> ControlSurfaceRegistry.DeviceTypeInfo? = ControlSurfaceRegistry::typeFor,
    /** What a live job reads now: the selection's heads, the busk page's pads. */
    private val jobLive: () -> JobLive = { JobLive.EMPTY },
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ControlSurfaceBindingService::class.java)
    }

    /**
     * Resolved in-memory form of a persisted binding. [id] is the primary key; the
     * remaining fields mirror the DB columns with [binding] decoded from JSON.
     * [health] is evaluated against the current project snapshot at cache install time
     * — see [BindingHealthEvaluator]. When no health provider is wired (tests,
     * pre-show init), [health] defaults to [AssignmentHealth.Ok].
     */
    data class ResolvedBinding(
        val id: Int,
        val projectId: Int,
        val deviceTypeKey: String,
        val controlId: String,
        val bank: String?,
        val binding: Binding,
        val takeoverPolicy: BindingTakeoverPolicy?,
        val sortOrder: Int,
        val health: AssignmentHealth = AssignmentHealth.Ok,
        val part: ControlPart = ControlPart.MAIN,
        /** The job slot this binding is derived from, when [resolve] answered a control from its region's job; [id] is then the job's. */
        val job: JobHit? = null,
    )

    /** Which job, slot and role a derived binding came from. */
    data class JobHit(val jobId: Int, val region: String, val slot: Int, val role: SlotRole, val kind: JobKind?)

    /**
     * One stored job as the cache holds it. [items] is null and [kind] null for a row this build
     * cannot read, kept byte for byte in [rawItems] and [kindId]; such a job derives nothing.
     * [health] is each slot's, evaluated at install as a binding's is.
     */
    data class StoredJob(
        val id: Int,
        val uuid: UUID,
        val projectId: Int,
        val deviceTypeKey: String,
        val region: String,
        val bank: String?,
        val kindId: String,
        val kind: JobKind?,
        val rawItems: String,
        val items: List<JobItem?>?,
        val health: List<AssignmentHealth> = emptyList(),
    ) {
        /** What slot [slot]'s [role] derives under [live] and [page], or null. */
        fun derive(slot: Int, role: SlotRole, live: JobLive, page: EncoderBankSelection): Binding? {
            val kind = kind ?: return null
            val items = items ?: return null
            return kind.derive(role, slot, JobContext(items, live, page))
        }

        fun healthAt(slot: Int): AssignmentHealth = health.getOrNull(slot) ?: AssignmentHealth.Ok
    }

    /** Emitted on any cache-mutating operation so UIs can react. */
    sealed class BindingChange {
        abstract val projectId: Int
        data class Added(override val projectId: Int, val binding: ResolvedBinding) : BindingChange()
        data class Updated(override val projectId: Int, val binding: ResolvedBinding) : BindingChange()
        data class Removed(override val projectId: Int, val bindingId: Int) : BindingChange()
        data class Reloaded(override val projectId: Int) : BindingChange()
        /** A job was written or taken off. */
        data class JobsChanged(override val projectId: Int) : BindingChange()
    }

    /**
     * Per-project cache. `byId` is an insertion-ordered map used by list / get / CRUD paths.
     * `byControl` is the hot-path index used by [resolve]: each `(deviceTypeKey, controlId, part)`
     * lookup key maps to at most one bank-agnostic binding and one entry per bank, so the
     * inner map is tiny and resolution is O(1) plus a bank precedence check.
     *
     * Both maps are mutated under the per-project lock in [lockFor]; reads are lock-free.
     */
    private class ProjectCache {
        val byId: MutableMap<Int, ResolvedBinding> = LinkedHashMap()
        val byControl: MutableMap<ControlKey, MutableMap<String?, ResolvedBinding>> = HashMap()

        /** `(deviceTypeKey, region)` → layer → job; replaced whole on a job write, read lock-free. */
        @Volatile
        var jobs: Map<JobKey, Map<String?, StoredJob>> = emptyMap()
    }

    /** Lookup key for the hot-path resolver. Interned at boundary so string equality is hashmap-cheap. */
    private data class ControlKey(val deviceTypeKey: String, val controlId: String, val part: ControlPart)

    private data class JobKey(val deviceTypeKey: String, val region: String)

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
        Any()
    }

    /** Ensure the cache is populated for [projectId]. Safe to call repeatedly. */
    fun ensureLoaded(projectId: Int) {
        if (loaded.contains(projectId)) return
        synchronized(lockFor(projectId)) {
            if (loaded.contains(projectId)) return
            val (raw, rawJobs) = transaction(database) {
                DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.project eq projectId }
                    .orderBy(DaoControlSurfaceBindings.sortOrder to SortOrder.ASC)
                    .map { it.toResolved() } to
                    DaoSurfaceJob.find { DaoSurfaceJobs.project eq projectId }.orderBy(DaoSurfaceJobs.id to SortOrder.ASC).map { it.toStored() }
            }
            val context = resolveHealthContext(projectId)
            val list = raw.map { it.withHealth(context) }
            val pc = ProjectCache()
            list.forEach { pc.install(it) }
            pc.jobs = indexJobs(rawJobs.map { it.withHealth(context) })
            cache[projectId] = pc
            loaded.add(projectId)
            val dead = list.filter { it.health !is AssignmentHealth.Ok }
            if (dead.isEmpty()) {
                logger.info("Loaded ${list.size} control-surface bindings for project $projectId (all resolved)")
            } else {
                logger.warn(
                    "Loaded {} control-surface bindings for project {}; {} dead: {}",
                    list.size, projectId, dead.size,
                    dead.joinToString(", ") { "id=${it.id}(${describeAssignmentHealth(it.health)})" },
                )
            }
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

    /** The row stored at exactly this slot — `(device type, control, part, layer)` — or null. */
    fun rowAt(projectId: Int, deviceTypeKey: String, controlId: String, part: ControlPart, bank: String?): ResolvedBinding? {
        ensureLoaded(projectId)
        return cache[projectId]?.byControl?.get(ControlKey(deviceTypeKey, controlId, part))?.get(bank)
    }

    /**
     * Resolve an inbound event on `(deviceTypeKey, controlId, part)` on the given [activeBank] and
     * [encoderBank]. This is the one resolution entry point — the input router, the feedback index,
     * the served map and the takeover-policy lookup all come through here — so the job arm below needs
     * no second implementation anywhere.
     *
     * The order is **the control part's own binding across both layers, then its region's job across
     * both** (surface-setup plan §3 "Jobs"):
     *
     *   1. the part's row for the exact layer,
     *   2. the part's row on both layers,
     *   3. what the job on the control's region derives for its slot and role, at the exact layer,
     *   4. what the job on both layers derives.
     *
     * Direct before job at *both* levels is the load-bearing part: a job on both layers loses to an
     * exact-layer binding on one of its controls, which is what makes "a control bound by hand leaves
     * the job" true regardless of layers. A job at the exact layer that derives nothing for the part —
     * an empty slot, a role its kind leaves alone — falls through to the job on both, as a binding
     * does.
     *
     * A job hit is answered with the job's id, its slot's health and no takeover policy (the
     * control's own default). A live job reads [jobLive] here, so a change of the selection or of the
     * busk page moves what its controls do without a write.
     */
    fun resolve(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        activeBank: String?,
        encoderBank: EncoderBankSelection,
        part: ControlPart = ControlPart.MAIN,
    ): ResolvedBinding? {
        ensureLoaded(projectId)
        val pc = cache[projectId] ?: return null
        pc.byControl[ControlKey(deviceTypeKey, controlId, part)]?.let { byBank ->
            byBank[activeBank]?.let { return it }
            byBank[null]?.let { return it }
        }
        return resolveJob(pc, deviceTypeKey, controlId, activeBank, encoderBank, part)
    }

    /** What the job on [controlId]'s region derives for the part, ignoring any binding of its own: what a direct binding beats. */
    fun jobBindingAt(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        activeBank: String?,
        encoderBank: EncoderBankSelection,
        part: ControlPart = ControlPart.MAIN,
    ): ResolvedBinding? {
        ensureLoaded(projectId)
        val pc = cache[projectId] ?: return null
        return resolveJob(pc, deviceTypeKey, controlId, activeBank, encoderBank, part)
    }

    private fun resolveJob(
        pc: ProjectCache,
        deviceTypeKey: String,
        controlId: String,
        activeBank: String?,
        encoderBank: EncoderBankSelection,
        part: ControlPart,
    ): ResolvedBinding? {
        val byBank = pc.jobs.takeIf { it.isNotEmpty() } ?: return null
        val place = profileFor(deviceTypeKey)?.placeOf(controlId, part) ?: return null
        val jobs = byBank[JobKey(deviceTypeKey, place.region)] ?: return null
        val live = jobLive()
        for (job in listOfNotNull(activeBank?.let { jobs[it] }, jobs[null])) {
            val binding = job.derive(place.slot, place.role, live, encoderBank) ?: continue
            return ResolvedBinding(
                id = job.id,
                projectId = job.projectId,
                deviceTypeKey = deviceTypeKey,
                controlId = controlId,
                bank = job.bank,
                binding = binding,
                takeoverPolicy = null,
                sortOrder = 0,
                health = if (job.kind?.follows != null) AssignmentHealth.Ok else job.healthAt(place.slot),
                part = part,
                job = JobHit(job.id, place.region, place.slot, place.role, job.kind),
            )
        }
        return null
    }

    // ─── Jobs ──────────────────────────────────────────────────────────────

    /** Every job of [projectId], in the order they were made. */
    fun jobs(projectId: Int): List<StoredJob> {
        ensureLoaded(projectId)
        return cache[projectId]?.jobs?.values?.flatMap { it.values }?.sortedBy { it.id }.orEmpty()
    }

    /** The job stored at exactly `(deviceTypeKey, region, bank)`, or null. */
    fun jobAt(projectId: Int, deviceTypeKey: String, region: String, bank: String?): StoredJob? {
        ensureLoaded(projectId)
        return cache[projectId]?.jobs?.get(JobKey(deviceTypeKey, region))?.get(bank)
    }

    /** Whether [projectId] holds a job that follows [signal], on any device type. */
    fun hasLiveJob(projectId: Int, signal: JobSignal): Boolean {
        ensureLoaded(projectId)
        return cache[projectId]?.jobs?.values?.any { byBank -> byBank.values.any { it.kind?.follows == signal } } == true
    }

    /**
     * Give `(deviceTypeKey, region, bank)` the job [kindId] with [items], replacing what it holds.
     * Refused with [BindingRefused] ([CODE_SURFACE_JOB_INVALID]) for an unknown device type, region,
     * layer or kind, a kind that does not fit the region, or items the kind cannot hold.
     */
    fun putJob(projectId: Int, deviceTypeKey: String, region: String, bank: String?, kindId: String, items: List<JobItem?>): StoredJob {
        val profile = profileFor(deviceTypeKey) ?: refuseJob("Unknown device type '$deviceTypeKey'")
        val jobRegion = profile.jobRegion(region) ?: refuseJob("'$region' is no job region of ${profile.product}")
        if (bank != null && profile.banks.none { it.id == bank }) refuseJob("Unknown layer '$bank' for '$deviceTypeKey'")
        val kind = JobKind.of(kindId) ?: refuseJob("Unknown job kind '$kindId'")
        if (!kind.fits(jobRegion)) refuseJob("${kind.label} does not fit ${jobRegion.name}")
        val trimmed = items.dropLastWhile { it == null }
        kind.itemsRefusal(jobRegion, trimmed)?.let(::refuseJob)
        ensureLoaded(projectId)
        val context = resolveHealthContext(projectId)
        val stored = synchronized(lockFor(projectId)) {
            val raw = transaction(database) {
                val existing = DaoSurfaceJob.find {
                    (DaoSurfaceJobs.project eq projectId) and (DaoSurfaceJobs.deviceTypeKey eq deviceTypeKey) and
                        (DaoSurfaceJobs.region eq region)
                }.firstOrNull { it.bank == bank }
                val row = existing ?: DaoSurfaceJob.new {
                    this.project = DaoProject.findById(projectId) ?: throw IllegalArgumentException("Project $projectId not found")
                    this.deviceTypeKey = deviceTypeKey
                    this.region = region
                    this.bank = bank
                }
                row.kind = kind.id
                row.items = encodeJobItems(trimmed)
                row.toStored()
            }.withHealth(context)
            val pc = cache.getOrPut(projectId) { ProjectCache() }
            pc.jobs = indexJobs(pc.jobs.values.flatMap { it.values }.filterNot { it.id == raw.id } + raw)
            raw
        }
        _changes.tryEmit(BindingChange.JobsChanged(projectId))
        return stored
    }

    /** Take the job off `(deviceTypeKey, region, bank)`. True if there was one. */
    fun deleteJob(projectId: Int, deviceTypeKey: String, region: String, bank: String?): Boolean {
        ensureLoaded(projectId)
        val removed = synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return false
            val existing = pc.jobs[JobKey(deviceTypeKey, region)]?.get(bank) ?: return false
            transaction(database) { DaoSurfaceJob.findById(existing.id)?.takeIf { it.project.id.value == projectId }?.delete() }
            pc.jobs = indexJobs(pc.jobs.values.flatMap { it.values }.filterNot { it.id == existing.id })
            true
        }
        if (removed) _changes.tryEmit(BindingChange.JobsChanged(projectId))
        return removed
    }

    private fun refuseJob(message: String): Nothing = throw BindingRefused(message, CODE_SURFACE_JOB_INVALID)

    private fun indexJobs(jobs: List<StoredJob>): Map<JobKey, Map<String?, StoredJob>> =
        jobs.groupBy { JobKey(it.deviceTypeKey, it.region) }.mapValues { (_, list) -> list.associateBy { it.bank } }

    /** Each slot's health: the first role whose derived binding is not Ok, as a binding's own would read. */
    private fun StoredJob.withHealth(context: BindingHealthEvaluator.Context?): StoredJob {
        if (context == null) return this
        val kind = kind
        val items = items
        if (kind == null || items == null) return copy(health = listOf(AssignmentHealth.UnknownTarget(kindId)))
        val region = profileFor(deviceTypeKey)?.jobRegion(region) ?: return copy(health = emptyList())
        val ctx = JobContext(items, JobLive.EMPTY, EncoderBankState.DEFAULT)
        val health = region.slots.indices.map { slot ->
            SlotRole.entries.asSequence()
                .mapNotNull { role -> kind.derive(role, slot, ctx) }
                .map { BindingHealthEvaluator.evaluate(it, context, deviceTypeKey) }
                .firstOrNull { it != AssignmentHealth.Ok } ?: AssignmentHealth.Ok
        }
        return if (health == this.health) this else copy(health = health)
    }

    private fun DaoSurfaceJob.toStored(): StoredJob {
        val kind = JobKind.of(this.kind)
        val items = decodeJobItems(this.items)
        if (kind == null || items == null) {
            logger.warn("Surface job id={} ({} on {}) cannot be read and derives nothing", id.value, this.kind, region)
        }
        return StoredJob(
            id = id.value,
            uuid = uuid,
            projectId = project.id.value,
            deviceTypeKey = deviceTypeKey,
            region = region,
            bank = bank,
            kindId = this.kind,
            kind = kind,
            rawItems = this.items,
            items = items,
        )
    }

    private fun ProjectCache.install(binding: ResolvedBinding) {
        byId[binding.id] = binding
        byControl
            .getOrPut(ControlKey(binding.deviceTypeKey, binding.controlId, binding.part)) { HashMap() }[binding.bank] = binding
    }

    private fun ProjectCache.uninstall(binding: ResolvedBinding) {
        byId.remove(binding.id)
        val key = ControlKey(binding.deviceTypeKey, binding.controlId, binding.part)
        val byBank = byControl[key] ?: return
        byBank.remove(binding.bank)
        if (byBank.isEmpty()) byControl.remove(key)
    }

    /**
     * Create a binding. [part] null places it by [inferPart]. Throws [IllegalStateException] if a
     * binding already exists at the same `(project, deviceTypeKey, controlId, part, bank)` slot.
     */
    fun create(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        bank: String?,
        binding: Binding,
        takeoverPolicy: BindingTakeoverPolicy? = null,
        sortOrder: Int = 0,
        part: ControlPart? = null,
    ): ResolvedBinding {
        val placed = part ?: partFor(deviceTypeKey, controlId, binding)
        refuse(deviceTypeKey, controlId, placed, binding)
        ensureLoaded(projectId)
        // Resolved once and reused for both the refusal check and the health tag below, rather
        // than rebuilding the whole snapshot (stacks/cues/masters/looks/palettes/pages) twice.
        val healthContext = resolveHealthContext(projectId)
        refuseAxisOnNonColour(healthContext, binding)
        refuseTriggerAttribute(healthContext, binding)
        val resolved = synchronized(lockFor(projectId)) {
            val existing = cache[projectId]?.byControl
                ?.get(ControlKey(deviceTypeKey, controlId, placed))
                ?.get(bank)
            check(existing == null) {
                "Binding already exists for $deviceTypeKey.$controlId ${placed.name} (bank=$bank) in project $projectId"
            }
            val raw = transaction(database) {
                DaoControlSurfaceBinding.new {
                    this.project = DaoProject.findById(projectId)
                        ?: throw IllegalArgumentException("Project $projectId not found")
                    this.deviceTypeKey = deviceTypeKey
                    this.controlId = controlId
                    this.part = placed.name
                    this.bank = bank
                    this.targetType = binding.functionId
                    this.targetPayload = binding.encodePayload()
                    this.takeoverPolicy = takeoverPolicy?.name
                    this.sortOrder = sortOrder
                }.toResolved()
            }
            val entity = raw.withHealth(healthContext)
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
     * A new [binding] on a control with no [part] named keeps the row's part.
     */
    fun update(
        projectId: Int,
        bindingId: Int,
        deviceTypeKey: String? = null,
        controlId: String? = null,
        binding: Binding? = null,
        sortOrder: Int? = null,
        bankUpdate: FieldUpdate<String?> = FieldUpdate.NoChange,
        takeoverPolicyUpdate: FieldUpdate<BindingTakeoverPolicy?> = FieldUpdate.NoChange,
        part: ControlPart? = null,
    ): ResolvedBinding? {
        binding?.let(::refuseUnknown)
        ensureLoaded(projectId)
        // Resolved once, before the lock, and reused for both the refusal check and the health
        // tag below — matching create()/replace() rather than holding the per-project lock across
        // the multi-query DB read this triggers.
        val healthContext = resolveHealthContext(projectId)
        binding?.let {
            refuseAxisOnNonColour(healthContext, it)
            refuseTriggerAttribute(healthContext, it)
        }
        val resolved = synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return null
            val existing = pc.byId[bindingId] ?: return null
            val newDeviceTypeKey = deviceTypeKey ?: existing.deviceTypeKey
            val newControlId = controlId ?: existing.controlId
            val newBinding = binding ?: existing.binding
            val newPart = part
                ?: if (deviceTypeKey == null && controlId == null) existing.part
                else partFor(newDeviceTypeKey, newControlId, newBinding)
            // An unreadable row stays editable — moved, re-sorted, its layer or takeover changed — and
            // is written back byte for byte; only a binding this build reads is judged by its spec.
            if (binding != null || existing.binding.does !is Does.Unknown) refuse(newDeviceTypeKey, newControlId, newPart, newBinding)
            val newBank = when (bankUpdate) {
                is FieldUpdate.NoChange -> existing.bank
                is FieldUpdate.Set -> bankUpdate.value
            }
            val slotClash = pc.byControl[ControlKey(newDeviceTypeKey, newControlId, newPart)]?.get(newBank)
            check(slotClash == null || slotClash.id == bindingId) {
                "Binding already exists for $newDeviceTypeKey.$newControlId ${newPart.name} (bank=$newBank) in project $projectId"
            }
            val raw = transaction(database) {
                val row = DaoControlSurfaceBinding.findById(bindingId) ?: return@transaction null
                if (row.project.id.value != projectId) return@transaction null
                if (deviceTypeKey != null) row.deviceTypeKey = deviceTypeKey
                if (controlId != null) row.controlId = controlId
                row.part = newPart.name
                if (bankUpdate is FieldUpdate.Set) row.bank = bankUpdate.value
                if (binding != null) {
                    row.targetType = binding.functionId
                    row.targetPayload = binding.encodePayload()
                }
                if (takeoverPolicyUpdate is FieldUpdate.Set) {
                    row.takeoverPolicy = takeoverPolicyUpdate.value?.name
                }
                if (sortOrder != null) row.sortOrder = sortOrder
                row.toResolved()
            } ?: return null
            val entity = raw.withHealth(healthContext)
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

    /** One row a [replace] call should create. */
    data class NewBinding(
        val deviceTypeKey: String,
        val controlId: String,
        val bank: String?,
        val binding: Binding,
        val takeoverPolicy: BindingTakeoverPolicy? = null,
        val sortOrder: Int = 0,
        val part: ControlPart = ControlPart.MAIN,
    )

    /**
     * Delete some rows and create others as one unit.
     *
     * Every new slot is checked free (allowing for the rows being deleted in the same call)
     * **before** the first delete, because the uniqueness check reads the cache: a failure part
     * way through would otherwise leave the cache and the DB disagreeing. One transaction, one
     * [BindingChange.Reloaded], so the feedback publisher rebuilds its index once rather than
     * once per row.
     *
     * Returns the created bindings in the order given. Throws [IllegalStateException] on a slot
     * clash and [IllegalArgumentException] for an id that is not this project's.
     */
    fun replace(projectId: Int, deleteIds: List<Int>, creates: List<NewBinding>): List<ResolvedBinding> {
        creates.forEach { refuse(it.deviceTypeKey, it.controlId, it.part, it.binding) }
        ensureLoaded(projectId)
        // Resolved once for the whole batch — one refusal check per create still runs, but each
        // reuses this snapshot instead of rebuilding it from scratch per binding.
        val healthContext = resolveHealthContext(projectId)
        creates.forEach {
            refuseAxisOnNonColour(healthContext, it.binding)
            refuseTriggerAttribute(healthContext, it.binding)
        }
        val created = synchronized(lockFor(projectId)) {
            val pc = cache.getOrPut(projectId) { ProjectCache() }

            val doomed = deleteIds.map { id ->
                pc.byId[id] ?: throw IllegalArgumentException("Binding $id not found in project $projectId")
            }
            val freed = doomed.mapTo(mutableSetOf()) { ControlKey(it.deviceTypeKey, it.controlId, it.part) to it.bank }

            val claimed = mutableSetOf<Pair<ControlKey, String?>>()
            for (create in creates) {
                val slot = ControlKey(create.deviceTypeKey, create.controlId, create.part) to create.bank
                check(claimed.add(slot)) {
                    "Two new bindings claim ${create.deviceTypeKey}.${create.controlId} (bank=${create.bank})"
                }
                if (slot in freed) continue
                val clash = pc.byControl[slot.first]?.get(slot.second)
                check(clash == null) {
                    "Binding already exists for ${create.deviceTypeKey}.${create.controlId} " +
                        "(bank=${create.bank}) in project $projectId"
                }
            }

            val raw = transaction(database) {
                for (id in deleteIds) {
                    val row = DaoControlSurfaceBinding.findById(id)
                        ?: throw IllegalArgumentException("Binding $id not found")
                    if (row.project.id.value != projectId) {
                        throw IllegalArgumentException("Binding $id is not in project $projectId")
                    }
                    row.delete()
                }
                val project = DaoProject.findById(projectId)
                    ?: throw IllegalArgumentException("Project $projectId not found")
                creates.map { create ->
                    DaoControlSurfaceBinding.new {
                        this.project = project
                        this.deviceTypeKey = create.deviceTypeKey
                        this.controlId = create.controlId
                        this.part = create.part.name
                        this.bank = create.bank
                        this.targetType = create.binding.functionId
                        this.targetPayload = create.binding.encodePayload()
                        this.takeoverPolicy = create.takeoverPolicy?.name
                        this.sortOrder = create.sortOrder
                    }.toResolved()
                }
            }

            doomed.forEach { pc.uninstall(it) }
            raw.map { it.withHealth(healthContext) }.onEach { pc.install(it) }
        }
        _changes.tryEmit(BindingChange.Reloaded(projectId))
        return created
    }

    /** Drop the cache entry for a project and force reload on next access. */
    fun invalidate(projectId: Int) {
        synchronized(lockFor(projectId)) {
            cache.remove(projectId)
            loaded.remove(projectId)
        }
        _changes.tryEmit(BindingChange.Reloaded(projectId))
    }

    /**
     * Re-evaluate [AssignmentHealth] for every cached binding of [projectId] against the
     * current [healthContextProvider] snapshot and overwrite each entry in-place. Emits a
     * single [BindingChange.Reloaded] event iff any binding's health actually changed —
     * the binding *rows* haven't moved, only their derived health. Called from listeners
     * when fixtures / patches / cues / cue stacks mutate.
     *
     * No-op if the project isn't loaded or no context can be read: no provider, pre-show init,
     * or a desk shutting down under the caller. Safe to call from any thread.
     */
    fun invalidateHealth(projectId: Int) {
        if (!loaded.contains(projectId)) return
        var changed = false
        synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return
            val context = resolveHealthContext(projectId) ?: return
            // Copy entries because we mutate byId via (un)install during iteration.
            val snapshot = pc.byId.values.toList()
            for (existing in snapshot) {
                val newHealth = BindingHealthEvaluator.evaluate(existing.binding, context, existing.deviceTypeKey)
                if (newHealth == existing.health) continue
                pc.uninstall(existing)
                pc.install(existing.copy(health = newHealth))
                changed = true
            }
            val jobs = pc.jobs.values.flatMap { it.values }
            val rejudged = jobs.map { it.withHealth(context) }
            if (rejudged != jobs) {
                pc.jobs = indexJobs(rejudged)
                changed = true
            }
        }
        if (changed) _changes.tryEmit(BindingChange.Reloaded(projectId))
    }

    private fun resolveHealthContext(projectId: Int): BindingHealthEvaluator.Context? {
        val provider = healthContextProvider ?: return null
        return try {
            provider(projectId)
        } catch (e: Exception) {
            logger.debug("Health context unavailable for project {}: {}", projectId, e.message)
            null
        }
    }

    private fun ResolvedBinding.withHealth(context: BindingHealthEvaluator.Context?): ResolvedBinding {
        if (context == null) return this
        val health = BindingHealthEvaluator.evaluate(binding, context, deviceTypeKey)
        return if (health == this.health) this else copy(health = health)
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
    internal fun seedCacheForTest(projectId: Int, bindings: List<ResolvedBinding>, jobs: List<StoredJob> = emptyList()) {
        synchronized(lockFor(projectId)) {
            val pc = ProjectCache()
            bindings.forEach { pc.install(it) }
            pc.jobs = indexJobs(jobs)
            cache[projectId] = pc
            loaded.add(projectId)
        }
    }

    /** The part a binding with no part named sits on: [inferPart] against the control the slot names. */
    private fun partFor(deviceTypeKey: String, controlId: String, binding: Binding): ControlPart =
        inferPart(profileFor(deviceTypeKey)?.control(controlId), binding.does)

    /** Every refusal that needs no project snapshot: an unknown binding, the control part's input, and the spec. */
    private fun refuse(deviceTypeKey: String, controlId: String, part: ControlPart, binding: Binding) {
        refuseUnknown(binding)
        refuseWrongKind(deviceTypeKey, controlId, part, binding)
        refuseBySpec(binding)
    }

    /**
     * A fader, encoder or flash may not drive a one-shot trigger or its arm (stage-view plan session
     * 9, D15): a trigger fires as an event, and a surface's door to one is [Does.Fire], which needs
     * the desk's arm. Without a register to judge by (no show), a name no trigger reserves passes and
     * one that does is left to health, as an unknown property would be.
     */
    private fun refuseTriggerAttribute(context: BindingHealthEvaluator.Context?, binding: Binding) {
        val attribute = (binding.does.named as? Named.Attribute)?.attribute ?: return
        val (type, key) = when (val on = binding.on) {
            is Subject.Fixture -> "fixture" to on.key
            is Subject.Group -> "group" to on.key
            else -> null to null
        }
        val refusal = if (context != null) {
            TriggerIndex.refusalLive(context.fixtures, type, key, attribute, "binding")
        } else if (type == null) {
            TriggerIndex.EMPTY.refusal(null, null, attribute, "binding")
        } else {
            null
        }
        refusal?.let { throw BindingRefused(it, TriggerNotStorableException.CODE) }
        // Nor a fixture command (fixture optics plan session 7): a fader on a reset is a reset.
        val commandRefusal = if (context != null) {
            CommandIndex.refusalLive(context.fixtures, type, key, attribute, "binding")
        } else if (type == null) {
            CommandIndex.EMPTY.refusal(null, null, attribute, "binding")
        } else {
            null
        }
        commandRefusal?.let { throw BindingRefused(it, CommandNotStorableException.CODE) }
    }

    /**
     * A client never writes [Does.Unknown]: it exists only as the decode fallback, and accepting one
     * from a request would let a function this build cannot dispatch be persisted on purpose.
     * [IllegalArgumentException] is what the routes already map to a 400.
     */
    private fun refuseUnknown(binding: Binding) {
        (binding.does as? Does.Unknown)?.let {
            throw IllegalArgumentException("A binding of type '${it.storedType}' cannot be created from a request")
        }
    }

    /**
     * A control part can only be given a function its input reaches (`FU-MIDI-BIND-CONTROL-KIND`):
     * the part's input ([inputFor]) must be in the function's [Spec.inputs]. A bank button has no
     * input at all — it switches the layer before any binding is resolved — and a fader or button
     * has no push.
     */
    private fun refuseWrongKind(deviceTypeKey: String, controlId: String, part: ControlPart, binding: Binding) {
        val descriptor = profileFor(deviceTypeKey)?.control(controlId)
            ?: return  // Unknown control: the routes' own shape check answers that, with a better message.
        val input = descriptor.inputFor(part)
        val wanted = binding.does.spec.inputs
        if (input != null && input in wanted) return
        throw BindingRefused(
            when {
                descriptor is BankButtonDescriptor ->
                    "'$controlId' on $deviceTypeKey is a layer button — it switches the layer before any " +
                        "binding is resolved, so a binding on it can never fire"
                input == null -> "'$controlId' on $deviceTypeKey has no ${part.name.lowercase()} part"
                else -> "'$controlId' ${part.name.lowercase()} on $deviceTypeKey is a ${input.name.lowercase()} input " +
                    "and cannot drive ${binding.functionId}"
            },
            CODE_BINDING_WRONG_CONTROL_KIND,
        )
    }

    /** The subject and How the function's [Spec] admits, and the function's own [Does.refusal]. */
    private fun refuseBySpec(binding: Binding) {
        val spec = binding.does.spec
        val kind = binding.on.subjectKind
        if (kind !in spec.on) {
            throw BindingRefused(
                binding.does.refusal(binding.on)
                    ?: "${binding.functionId} acts on ${spec.on.joinToString(" or ") { it.name.lowercase() }}, " +
                    "not ${kind.name.lowercase()}",
                CODE_BINDING_WRONG_SUBJECT,
            )
        }
        binding.does.refusal(binding.on)?.let { throw BindingRefused(it, CODE_BINDING_WRONG_SUBJECT) }
        val how = binding.how ?: return
        if (how !in spec.hows && how != spec.defaultHow) {
            throw BindingRefused(
                "${binding.functionId} does not take ${how.name.lowercase()}" +
                    if (spec.hows.isEmpty()) "" else "; it takes ${spec.hows.joinToString { it.name.lowercase() }}",
                CODE_BINDING_WRONG_HOW,
            )
        }
    }

    /**
     * A [ColourAxis] on a property that is not a colour has nothing to drive.
     *
     * Judged over the heads the binding reaches ([Heads.of]) with [AttributeChannelResolver.isColour],
     * the dispatch lookup, so the refusal can never disagree with what a move will do. What it does
     * *not* judge is left to health: a fixture or group that does not exist, or a property name no
     * head declares. A fixture or group is refused only when **no** head it reaches declares the
     * property as a colour, since the write skips the heads that do not and reaches the rest; the
     * selection, or an encoder page, which names no head, is judged against the rig's colour
     * vocabulary ([BindingHealthEvaluator.Context.colourAttributes]) but only for a name the rig
     * *has* ([isKnownNonColour]). No context (tests, pre-show init) means no refusal.
     */
    private fun refuseAxisOnNonColour(context: BindingHealthEvaluator.Context?, binding: Binding) {
        if (context == null) return
        val named = binding.does.named as? Named.Attribute ?: return
        val attribute = named.attribute
        val axis = named.axis ?: return
        fun refuse(what: String): Nothing = throw BindingRefused(
            "$what is not a colour attribute, so it has no ${axis.name.lowercase().replace('_', ' ')} to drive",
            CODE_BINDING_AXIS_NEEDS_COLOUR,
        )
        when (val on = binding.on) {
            is Subject.Fixture, is Subject.Group -> {
                val verdicts = Heads.of(context.fixtures, on, attribute).mapNotNull { AttributeChannelResolver.isColour(it.fixture, attribute) }
                if (verdicts.isNotEmpty() && verdicts.none { it }) refuse("'$attribute' on '${on.asTarget()?.key}'")
            }
            // Both need the property to be **known and not a colour**, never merely absent — see
            // the "left to health" rule above.
            else -> if (isKnownNonColour(context, attribute)) {
                refuse(if (binding.does is Does.EncoderPage) "'$attribute' as an encoder page" else "'$attribute' on the selection")
            }
        }
    }

    /**
     * A property some patched fixture declares that a continuous control can write, and that no
     * fixture declares as a colour — the only shape a target naming no head can be refused on.
     *
     * A name **absent from both** sets is left to health, exactly as an unknown property on a
     * fixture target is. It has to be: [BindingHealthEvaluator.Context.fixtures] is the rig of the
     * *currently loaded* show whatever project is being written (see `State.buildBindingHealthContext`),
     * so refusing on absence would reject a perfectly good colour-axis binding authored for another
     * project's rig and leave the operator no way to make it at all.
     */
    private fun isKnownNonColour(context: BindingHealthEvaluator.Context, attribute: String): Boolean =
        attribute in context.selectionAttributes && attribute !in context.colourAttributes

    /**
     * Decode one row's payload, and keep the row when the payload cannot be read. An archive
     * written by a newer desk carries functions this build does not know; failing the whole project
     * load for one of them left every *other* binding dead too. The row comes back as a
     * [Does.Unknown] with its bytes intact, which health reports as `unknownTarget`: dead, visible
     * in the list, rebindable, and re-written verbatim.
     */
    private fun DaoControlSurfaceBinding.toResolved(): ResolvedBinding {
        val binding = decodeBinding(targetPayload, targetType)
        if (binding.does is Does.Unknown) {
            logger.warn("Binding id={} has an undecodable payload (type='{}')", id.value, targetType)
        }
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
            binding = binding,
            takeoverPolicy = policy,
            sortOrder = sortOrder,
            part = ControlPart.entries.firstOrNull { it.name == part } ?: ControlPart.MAIN,
        )
    }
}
