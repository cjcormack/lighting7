package uk.me.cormack.lighting7.midi

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.core.eq
import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.models.AssignmentHealth
import uk.me.cormack.lighting7.models.describeAssignmentHealth
import uk.me.cormack.lighting7.models.BindingTakeoverPolicy
import uk.me.cormack.lighting7.models.DaoControlSurfaceBinding
import uk.me.cormack.lighting7.models.DaoControlSurfaceBindings
import uk.me.cormack.lighting7.models.DaoCue
import uk.me.cormack.lighting7.models.DaoCueStack
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
    /**
     * Supplies the snapshot needed to tag each cached binding with an [AssignmentHealth].
     * Called under the per-project lock during cache rebuild and on [invalidateHealth].
     * Returns null when no snapshot is available (tests, pre-show init) — bindings fall
     * back to [AssignmentHealth.Ok] in that case.
     */
    private val healthContextProvider: ((projectId: Int) -> BindingHealthEvaluator.Context?)? = null,
    /**
     * The channel strips a device profile declares, for the strip arm of [resolve]. Defaults to
     * the live registry; injectable so tests can resolve against a synthetic profile.
     */
    private val stripsFor: (deviceTypeKey: String) -> List<StripDescriptor> = { typeKey ->
        ControlSurfaceRegistry.typeFor(typeKey)?.strips.orEmpty()
    },
) {
    companion object {
        private val logger = LoggerFactory.getLogger(ControlSurfaceBindingService::class.java)
    }

    /**
     * Resolved in-memory form of a persisted binding. [id] is the primary key; the
     * remaining fields mirror the DB columns with [target] deserialized from JSON.
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
        val target: BindingTarget,
        val takeoverPolicy: BindingTakeoverPolicy?,
        val sortOrder: Int,
        val health: AssignmentHealth = AssignmentHealth.Ok,
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

    /**
     * Per device type: which strip claims each control, and the strip ids themselves. Profiles are
     * immutable for the life of the process, so this is computed once per device type rather than
     * re-scanned on every event — [resolve] is on the MIDI hot path.
     */
    private class StripIndex(val byControl: Map<String, StripControl>, val stripIds: Set<String>)

    private val stripIndexes = ConcurrentHashMap<String, StripIndex>()

    private fun stripIndexFor(deviceTypeKey: String): StripIndex =
        stripIndexes.computeIfAbsent(deviceTypeKey) { typeKey ->
            val strips = stripsFor(typeKey)
            StripIndex(stripControlsByControlId(strips), strips.mapTo(HashSet()) { it.id })
        }

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
            val raw = transaction(database) {
                DaoControlSurfaceBinding.find { DaoControlSurfaceBindings.project eq projectId }
                    .orderBy(DaoControlSurfaceBindings.sortOrder to SortOrder.ASC)
                    .map { it.toResolved() }
            }
            val context = resolveHealthContext(projectId)
            val list = raw.map { it.withHealth(context) }
            val pc = ProjectCache()
            list.forEach { pc.install(it) }
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

    /**
     * Resolve an inbound event from `(deviceTypeKey, controlId)` on the given [activeBank] and
     * [encoderBank]. This is the one resolution entry point — the input router, the
     * feedback index and the takeover-policy lookup all come through here — so the strip arm
     * below needs no second implementation anywhere.
     *
     * The order is **the control's own binding across both bank levels, then its strip's across
     * both**:
     *
     *   1. the control's row for the exact bank,
     *   2. the control's bank-agnostic row,
     *   3. the row on the strip containing the control, for the exact bank,
     *   4. that strip's bank-agnostic row.
     *
     * Direct before strip at *both* levels is the load-bearing part: a bank-agnostic strip must
     * lose to an exact-bank binding on one of its controls, which is what makes "any control can
     * still be bound on its own" true regardless of banks.
     *
     * A strip hit is answered as the strip's row with the control's id and the target that
     * control behaves as ([deriveStripTarget]), so the four derived bindings share the strip
     * row's id, takeover policy and health. Health is deliberately not re-evaluated per derived
     * target: an encoder whose bank property no selected head declares reads unbound and drops
     * its turn, which is a property of the selection rather than a dead binding.
     */
    fun resolve(
        projectId: Int,
        deviceTypeKey: String,
        controlId: String,
        activeBank: String?,
        encoderBank: EncoderBankSelection,
    ): ResolvedBinding? {
        ensureLoaded(projectId)
        val byControl = cache[projectId]?.byControl ?: return null

        byControl[ControlKey(deviceTypeKey, controlId)]?.let { byBank ->
            byBank[activeBank]?.let { return it }
            byBank[null]?.let { return it }
        }

        val onStrip = stripIndexFor(deviceTypeKey).byControl[controlId] ?: return null
        val stripByBank = byControl[ControlKey(deviceTypeKey, onStrip.strip.id)] ?: return null
        val stripBinding = stripByBank[activeBank] ?: stripByBank[null] ?: return null

        // Always the id of the control the event arrived on, even on the fallback below, so a
        // dead-binding warning names the fader the operator is pushing rather than its strip.
        val stripTarget = stripBinding.target as? BindingTarget.Strip
            ?: return stripBinding.copy(controlId = controlId)

        return stripBinding.copy(
            controlId = controlId,
            target = deriveStripTarget(onStrip.role, stripTarget.target, encoderBank),
        )
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
        refuseUnknown(target)
        refuseWrongSlot(deviceTypeKey, controlId, target)
        refuseWrongKind(deviceTypeKey, controlId, target)
        ensureLoaded(projectId)
        // Resolved once and reused for both the refusal check and the health tag below, rather
        // than rebuilding the whole snapshot (stacks/cues/masters/looks/templates/pages) twice.
        val healthContext = resolveHealthContext(projectId)
        refuseUnpressableLook(healthContext, target)
        refuseAxisOnNonColour(healthContext, target)
        val resolved = synchronized(lockFor(projectId)) {
            val existing = cache[projectId]?.byControl
                ?.get(ControlKey(deviceTypeKey, controlId))
                ?.get(bank)
            check(existing == null) {
                "Binding already exists for $deviceTypeKey.$controlId (bank=$bank) in project $projectId"
            }
            val raw = transaction(database) {
                val stored = target.withUuids(projectId)
                DaoControlSurfaceBinding.new {
                    this.project = DaoProject.findById(projectId)
                        ?: throw IllegalArgumentException("Project $projectId not found")
                    this.deviceTypeKey = deviceTypeKey
                    this.controlId = controlId
                    this.bank = bank
                    this.targetType = stored.discriminator()
                    this.targetPayload = stored.encodePayload()
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
        target?.let(::refuseUnknown)
        ensureLoaded(projectId)
        // Resolved once, before the lock, and reused for both the refusal check and the health
        // tag below — matching create()/replace() rather than holding the per-project lock across
        // the multi-query DB read this triggers.
        val healthContext = resolveHealthContext(projectId)
        target?.let {
            refuseUnpressableLook(healthContext, it)
            refuseAxisOnNonColour(healthContext, it)
        }
        val resolved = synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return null
            val existing = pc.byId[bindingId] ?: return null
            val newDeviceTypeKey = deviceTypeKey ?: existing.deviceTypeKey
            val newControlId = controlId ?: existing.controlId
            refuseWrongSlot(newDeviceTypeKey, newControlId, target ?: existing.target)
            refuseWrongKind(newDeviceTypeKey, newControlId, target ?: existing.target)
            val newBank = when (bankUpdate) {
                is FieldUpdate.NoChange -> existing.bank
                is FieldUpdate.Set -> bankUpdate.value
            }
            val slotClash = pc.byControl[ControlKey(newDeviceTypeKey, newControlId)]?.get(newBank)
            check(slotClash == null || slotClash.id == bindingId) {
                "Binding already exists for $newDeviceTypeKey.$newControlId (bank=$newBank) in project $projectId"
            }
            val raw = transaction(database) {
                val row = DaoControlSurfaceBinding.findById(bindingId) ?: return@transaction null
                if (row.project.id.value != projectId) return@transaction null
                if (deviceTypeKey != null) row.deviceTypeKey = deviceTypeKey
                if (controlId != null) row.controlId = controlId
                if (bankUpdate is FieldUpdate.Set) row.bank = bankUpdate.value
                if (target != null) {
                    val stored = target.withUuids(projectId)
                    row.targetType = stored.discriminator()
                    row.targetPayload = stored.encodePayload()
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
        val target: BindingTarget,
        val takeoverPolicy: BindingTakeoverPolicy? = null,
        val sortOrder: Int = 0,
    )

    /**
     * Delete some rows and create others as one unit — what *Fader only…* does when it turns a
     * strip binding into the four single bindings it was deriving.
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
        creates.forEach {
            refuseUnknown(it.target)
            refuseWrongSlot(it.deviceTypeKey, it.controlId, it.target)
            refuseWrongKind(it.deviceTypeKey, it.controlId, it.target)
        }
        ensureLoaded(projectId)
        // Resolved once for the whole batch — one refusal check per create still runs, but each
        // reuses this snapshot instead of rebuilding it from scratch per ApplyLook target.
        val healthContext = resolveHealthContext(projectId)
        creates.forEach {
            refuseUnpressableLook(healthContext, it.target)
            refuseAxisOnNonColour(healthContext, it.target)
        }
        val created = synchronized(lockFor(projectId)) {
            val pc = cache.getOrPut(projectId) { ProjectCache() }

            val doomed = deleteIds.map { id ->
                pc.byId[id] ?: throw IllegalArgumentException("Binding $id not found in project $projectId")
            }
            val freed = doomed.mapTo(mutableSetOf()) { ControlKey(it.deviceTypeKey, it.controlId) to it.bank }

            val claimed = mutableSetOf<Pair<ControlKey, String?>>()
            for (create in creates) {
                val slot = ControlKey(create.deviceTypeKey, create.controlId) to create.bank
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
                    val stored = create.target.withUuids(projectId)
                    DaoControlSurfaceBinding.new {
                        this.project = project
                        this.deviceTypeKey = create.deviceTypeKey
                        this.controlId = create.controlId
                        this.bank = create.bank
                        this.targetType = stored.discriminator()
                        this.targetPayload = stored.encodePayload()
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
     * No-op if the project isn't loaded, the provider is null, or the provider returns
     * null (pre-show init). Safe to call from any thread.
     */
    fun invalidateHealth(projectId: Int) {
        if (!loaded.contains(projectId)) return
        val provider = healthContextProvider ?: return
        var changed = false
        synchronized(lockFor(projectId)) {
            val pc = cache[projectId] ?: return
            val context = provider(projectId) ?: return
            // Copy entries because we mutate byId via (un)install during iteration.
            val snapshot = pc.byId.values.toList()
            for (existing in snapshot) {
                val newHealth = BindingHealthEvaluator.evaluate(existing.target, context)
                if (newHealth == existing.health) continue
                pc.uninstall(existing)
                pc.install(existing.copy(health = newHealth))
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
        val health = BindingHealthEvaluator.evaluate(target, context)
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
    internal fun seedCacheForTest(projectId: Int, bindings: List<ResolvedBinding>) {
        synchronized(lockFor(projectId)) {
            val pc = ProjectCache()
            bindings.forEach { pc.install(it) }
            cache[projectId] = pc
            loaded.add(projectId)
        }
    }

    /**
     * A client never writes [BindingTarget.Unknown]: it exists only as the decode fallback, and
     * accepting one from a request would let a `type` this build cannot dispatch be persisted
     * on purpose. [IllegalArgumentException] is what the routes already map to a 400.
     */
    private fun refuseUnknown(target: BindingTarget) {
        if (target is BindingTarget.Unknown) {
            throw IllegalArgumentException(
                "A binding target of type '${target.targetType}' cannot be created from a request",
            )
        }
    }

    /**
     * A strip id and a control id share the `control_id` column but are not interchangeable: a
     * strip binding covers four controls by derivation, so it is only meaningful on a strip, and a
     * strip slot is only meaningful holding one. Enforced **here** rather than only in the REST
     * routes because this service is the one door every write goes through — the routes, and MIDI
     * Learn's commit, which has no route validation of its own. The rest of the strip code takes
     * the invariant as given: a raw `Strip` reaching dispatch is a control that silently does
     * nothing, and health cannot see it because a `Strip`'s health only judges its group.
     *
     * The routes still run their own version first, to answer a coded 400 the frontend can branch
     * on; this is the backstop that makes the invariant true rather than merely usual.
     */
    private fun refuseWrongSlot(deviceTypeKey: String, controlId: String, target: BindingTarget) {
        val isStripSlot = controlId in stripIndexFor(deviceTypeKey).stripIds
        if (target is BindingTarget.Strip && !isStripSlot) {
            throw IllegalArgumentException(
                "'$controlId' on $deviceTypeKey is a control, not a strip — a strip binding covers a whole strip",
            )
        }
        if (target !is BindingTarget.Strip && isStripSlot) {
            throw IllegalArgumentException(
                "'$controlId' on $deviceTypeKey is a strip and takes a strip target; " +
                    "bind its controls individually instead",
            )
        }
    }

    /**
     * A control can only be given a target its own half of the dispatch will reach
     * (`FU-MIDI-BIND-CONTROL-KIND`).
     *
     * Nothing else refused this, and the failure was total silence: a `FireCue` on a fader saved,
     * read health `Ok`, and did nothing — `dispatchContinuous` has no arm for it. A `GroupProperty`
     * on a plain button, likewise. And a binding of any kind on a **bank button** can never fire at
     * all, because `SurfaceInputRouter.route` answers `ResolvedInput.BankButton` and switches the
     * bank *before* it resolves a binding.
     *
     * Here rather than in the routes for [refuseWrongSlot]'s reason — this service is the one door
     * every write comes through, MIDI Learn's commit included, which has no route validation of its
     * own. `lib/surfaceDrop.ts`'s `controlKinds` is the client mirror of the table below; keep the
     * two in step.
     *
     * **Strip slots are skipped.** A strip id names no descriptor, and a `Strip` target is validated
     * by [refuseWrongSlot] and reaches a control only by derivation, never by dispatch.
     */
    private fun refuseWrongKind(deviceTypeKey: String, controlId: String, target: BindingTarget) {
        if (controlId in stripIndexFor(deviceTypeKey).stripIds) return
        val descriptor = ControlSurfaceRegistry.typeFor(deviceTypeKey)
            ?.controls?.firstOrNull { it.controlId == controlId }
            ?: return  // Unknown control: the routes' own shape check answers that, with a better message.
        val wanted = targetControlKind(target) ?: return  // Strip / Unknown: refused by name elsewhere.
        val kinds = dispatchableKinds(descriptor)
        if (wanted in kinds) return
        throw BindingRefused(
            if (kinds.isEmpty()) {
                "'$controlId' on $deviceTypeKey is a bank button — it switches the bank before any " +
                    "binding is resolved, so a binding on it can never fire"
            } else {
                "'$controlId' on $deviceTypeKey is a ${kinds.joinToString(" and ") { it.name.lowercase() }} " +
                    "control and cannot dispatch a ${wanted.name.lowercase()} target"
            },
            CODE_BINDING_WRONG_CONTROL_KIND,
        )
    }

    /**
     * A Look whose press has no targets cannot be put on a button (D6).
     *
     * [BindingTarget.ApplyLook] presses onto the Look's **own** fixtures, and a Look with a deferred
     * effect has none to fall back on — a button has no selection to supply. Refused at bind time so
     * the operator is told at the drop rather than by a silent press; a Look that gains one
     * afterwards reads as [AssignmentHealth.LookNeedsSelection] instead.
     *
     * Judged through the health context, so the refusal and the health arm cannot disagree about
     * what "needs a selection" means. No context (tests, pre-show init) means no refusal.
     *
     * Takes an already-resolved [context] rather than a `projectId` — callers resolve it once
     * (it's a multi-query DB snapshot) and reuse it for both this check and the created/updated
     * row's health tag, rather than rebuilding it per call.
     */
    private fun refuseUnpressableLook(context: BindingHealthEvaluator.Context?, target: BindingTarget) {
        if (target !is BindingTarget.ApplyLook) return
        if (context == null) return
        val uuid = try {
            java.util.UUID.fromString(target.lookUuid)
        } catch (_: IllegalArgumentException) {
            // Malformed input, not "needs a selection" — don't reuse that code, or a client
            // branching on it (per BindingRefused's doc comment) would show the wrong fix for a
            // garbage uuid. Uncoded, like refuseUnknown / refuseWrongSlot's shape refusals, and
            // consistent with BindingHealthEvaluator mapping the same input to MissingLook rather
            // than LookNeedsSelection.
            throw IllegalArgumentException("'${target.lookUuid}' is not a Look uuid")
        }
        if (uuid in context.looksNeedingSelection) {
            throw BindingRefused(
                "This Look has a deferred effect, so it needs a selection — a button has none to give",
                CODE_BINDING_LOOK_NEEDS_SELECTION,
            )
        }
    }

    /**
     * A [ColourAxis] on a property that is not a colour has nothing to drive.
     *
     * Judged with the **dispatch** lookup — [PropertyChannelResolver.describePropertyRead] answering
     * [PropertyChannelResolver.PropertyRead.Colour] — so the refusal can never disagree with what a
     * move will do. What it does *not* judge is left to health: a fixture or group that does not
     * exist, or a property name no head declares, is not this rule's — an unknown name has an
     * unknown type, and health already says `UnknownProperty` for it. A group is refused only when
     * **no** member declares the property as a colour, since the write skips the members that do
     * not and reaches the rest, exactly as [SelectionWrites] does; a selection or encoder-bank
     * target, which names no head, is judged against the rig's colour vocabulary
     * ([BindingHealthEvaluator.Context.colourProperties]) but only for a name the rig *has*
     * ([isKnownNonColour]) — absent from both vocabularies is the unknown-name case, not a
     * refusal. A [BindingTarget.Flash] is judged for the
     * property it wraps, so a flash cannot smuggle in an axis a direct binding would be refused.
     *
     * Here rather than in the routes for [refuseWrongSlot]'s reason — one door, MIDI Learn's commit
     * included. No context (tests, pre-show init) means no refusal, as for [refuseUnpressableLook].
     */
    private fun refuseAxisOnNonColour(context: BindingHealthEvaluator.Context?, target: BindingTarget) {
        if (context == null) return
        val axis = target.colourAxisOrNull() ?: return
        fun isColour(fixture: Fixture, propertyName: String): Boolean? {
            fixture.fixtureProperty(propertyName) ?: return null
            val read = PropertyChannelResolver.describePropertyRead(fixture, propertyName)
            return read is PropertyChannelResolver.PropertyRead.Colour
        }
        fun refuse(what: String): Nothing = throw BindingRefused(
            "$what is not a colour property, so it has no ${axis.name.lowercase().replace('_', ' ')} to drive",
            CODE_BINDING_AXIS_NEEDS_COLOUR,
        )
        when (val inner = if (target is BindingTarget.Flash) target.target else target) {
            is BindingTarget.FixtureProperty -> {
                val fixture = runCatching { context.fixtures.untypedFixture(inner.fixtureKey) }.getOrNull() ?: return
                if (isColour(fixture, inner.propertyName) == false) refuse("'${inner.propertyName}' on '${inner.fixtureKey}'")
            }
            is BindingTarget.GroupProperty -> {
                val group = runCatching { context.fixtures.untypedGroup(inner.groupName) }.getOrNull() ?: return
                val verdicts = group.fixtures.filterIsInstance<Fixture>().mapNotNull { isColour(it, inner.propertyName) }
                if (verdicts.isNotEmpty() && verdicts.none { it }) refuse("'${inner.propertyName}' on '${inner.groupName}'")
            }
            // Both need the property to be **known and not a colour**, never merely absent — see
            // the "left to health" rule above. `selectionProperties` is what "known" means, and
            // is the same vocabulary health judges these two targets against.
            is BindingTarget.SelectionProperty ->
                if (isKnownNonColour(context, inner.propertyName)) refuse("'${inner.propertyName}' on the selection")
            is BindingTarget.EncoderBankSet ->
                if (isKnownNonColour(context, inner.propertyName)) refuse("'${inner.propertyName}' as an encoder bank")
            else -> Unit
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
    private fun isKnownNonColour(context: BindingHealthEvaluator.Context, propertyName: String): Boolean =
        propertyName in context.selectionProperties && propertyName !in context.colourProperties

    /**
     * Fill the uuid beside the int on a cue / stack target when the client sent only the int —
     * the REST picker and MIDI Learn both address rows by id — so every row written from here on
     * carries the form that survives a clone. Must run inside the caller's transaction. An int
     * that resolves to nothing (or to another project's row) is stored as sent, and the health
     * evaluator marks it dead exactly as before.
     */
    private fun BindingTarget.withUuids(projectId: Int): BindingTarget = when (this) {
        is BindingTarget.FireCue -> if (cueUuid != null) this else {
            val cue = DaoCue.findById(cueId)?.takeIf { it.project.id.value == projectId }
            if (cue == null) this else copy(cueUuid = cue.uuid.toString())
        }
        is BindingTarget.CueStackGo -> if (stackUuid != null) this else copy(stackUuid = stackUuidFor(stackId, projectId))
        is BindingTarget.CueStackBack -> if (stackUuid != null) this else copy(stackUuid = stackUuidFor(stackId, projectId))
        is BindingTarget.CueStackPause -> if (stackUuid != null) this else copy(stackUuid = stackUuidFor(stackId, projectId))
        else -> this
    }

    private fun stackUuidFor(stackId: Int, projectId: Int): String? =
        DaoCueStack.findById(stackId)?.takeIf { it.project.id.value == projectId }?.uuid?.toString()

    /**
     * Decode one row's payload, and keep the row when the payload cannot be read. An archive
     * written by a newer desk carries `type` discriminators this build does not know; failing
     * the whole project load for one of them left every *other* binding dead too — and, because
     * [ensureLoaded] never marked the project loaded, retried the DB read on every MIDI event.
     * The row comes back as a [BindingTarget.Unknown] with its bytes intact, which health reports
     * as `unknownTarget`: dead, visible in the list, rebindable, and re-written verbatim.
     */
    private fun DaoControlSurfaceBinding.toResolved(): ResolvedBinding {
        val target = try {
            BindingTargetJson.decodeFromString<BindingTarget>(targetPayload)
        } catch (e: SerializationException) {
            logger.warn("Binding id={} has an undecodable target (type='{}'): {}", id.value, targetType, e.message)
            BindingTarget.Unknown(targetType, targetPayload)
        } catch (e: IllegalArgumentException) {
            // A variant's own `init` guard (Flash's inner target, a BPM window) rejecting a payload
            // written under a rule this build no longer accepts.
            logger.warn("Binding id={} has an invalid target (type='{}'): {}", id.value, targetType, e.message)
            BindingTarget.Unknown(targetType, targetPayload)
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
    // An unknown target keeps naming the type it was written with, so the DB column, the list
    // DTO's `targetType` and the matrix filter all say what the row *is* rather than "unknown".
    if (this is BindingTarget.Unknown) return targetType
    val klass = this::class
    val annotated = klass.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
    return annotated ?: klass.simpleName
        ?: error("BindingTarget subclass ${klass.java.name} has no @SerialName")
}

/** The payload column: an unknown target is written back byte-for-byte. */
internal fun BindingTarget.encodePayload(): String =
    if (this is BindingTarget.Unknown) rawPayload else BindingTargetJson.encodeToString(this)
