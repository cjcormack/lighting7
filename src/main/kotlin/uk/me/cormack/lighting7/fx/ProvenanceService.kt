package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.models.LayerSource
import uk.me.cormack.lighting7.show.Fixtures
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Which layer produced the current winning value for one (target, property). */
enum class ProvenanceSource { PARKED, PROGRAMMER, EFFECT, CUE }

/**
 * The winning contributor for one (target, property) — the "who owns this value"
 * answer. BASELINE keys are omitted from snapshots entirely (absence = baseline).
 */
data class ProvenanceEntry(
    val targetKey: String,
    val propertyName: String,
    val source: ProvenanceSource,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val effectId: Long? = null,
    /**
     * The Look layer that won, when one did.
     *
     * Deliberately **not** a new [ProvenanceSource] arm: the source is still the cue (or, once
     * the programmer holds layers, the programmer) — the layer is *which part of it*. Adding an
     * arm would have forced every consumer's `when` to handle a case that answers the same
     * question as `CUE` does, and would have made "a cue won" and "a cue's layer won" look like
     * different kinds of event.
     */
    val layerId: Int? = null,
    /**
     * What that layer applies — a Look or a template, with its id, uuid and name.
     *
     * One value rather than the `lookId`/`lookName` pair it replaces: a layer's referent became
     * polymorphic in session 3, and a field called `lookName` holding a template's name would
     * be a lie the compiler could not find.
     */
    val layerSource: LayerSource? = null,
)

/**
 * One coalesced provenance broadcast. [programmerRevision] bumps for every trigger that could
 * have moved the programmer's value set — anything but a crossfade weight tick, whose
 * republish carries the winner maps forward unchanged ([LayerResolver.reweightAssignments]).
 * The client refetches `programmer.state` when the revision moves and skips the refetch when
 * it hasn't, which is what stops a running fade turning into ~10 refetches/s per tab.
 *
 * A *revision* rather than a per-frame flag deliberately: the broadcast flow is replay-1 +
 * DROP_OLDEST and each connection's collector does a suspending network send, so a slow tab
 * can silently skip frames mid-fade. A drained boolean would put the whole refetch obligation
 * on the one unflagged frame — dropped, the write never propagates. A monotonic counter
 * survives arbitrary frame loss: whatever frame does arrive carries the latest value.
 */
data class ProvenanceUpdate(
    val entries: List<ProvenanceEntry>,
    val programmerRevision: Long,
)

/**
 * What would own each key if the programmer weren't there — see
 * [ProvenanceService.underlyingSources].
 */
data class UnderlyingSource(
    val key: CueAssignmentResolver.Key,
    val cueId: Int?,
    val cueStackId: Int?,
    val viaEffectId: Long?,
)

/**
 * Provenance computation and broadcast — the "who owns this value" answer for every
 * (target, property) any layer covers — extracted from [FxEngine] (sweep item E1).
 *
 * Reads the engine's live effect set through the constructor suppliers rather than holding
 * the engine, so the dependency arrow runs engine → service only.
 */
class ProvenanceService internal constructor(
    private val fixtures: Fixtures,
    private val programmerStore: ProgrammerStore,
    private val layerResolver: LayerResolver,
    private val publisher: CascadePublisher,
    /** Snapshot of the engine's active effect instances. */
    private val activeEffects: () -> Collection<FxInstance>,
    /** The fixture/element keys an effect currently writes to — [FxEngine.fixtureKeysCoveredBy]'s private twin. */
    private val coverageKeys: (FxInstance) -> List<String>,
    /** The tick loops' stomp check — [CueAssignmentLayer.isLayerStomped]. */
    private val isLayerStomped: (FxInstance, String, String) -> Boolean,
    /** Which stack a cue's published assignments belong to — [CueAssignmentLayer.cueStackIdFor]. */
    private val cueStackIdFor: (Int) -> Int?,
    /**
     * Priority order for effects — the engine's tick-composition comparator, shared so the
     * winner reported here is the effect actually painting on top.
     */
    private val effectOrder: Comparator<FxInstance>,
) {
    // Conflated: recomputed on layer events only (programmer mutation, cue republish,
    // effect lifecycle, park change) — never per frame. Full-state snapshots rather than
    // diffs: the entry set is small (the union of active keys) and event-rate, so diffing
    // buys nothing over the conflation.
    //
    // Stays a replay-1 SharedFlow, unlike `ParkManager.parkStateFlow` and `FxEngine.fxStateFlow`
    // which became StateFlows for the WS connect-snapshot rule: a provenance frame is what makes
    // the client refetch `programmer.state`, and two *content-equal* snapshots are a real signal
    // — the owner of a property unchanged, its value moved. A StateFlow conflates exactly that
    // away and the refetch never fires. The connect frame is pushed explicitly from
    // `setupProgrammerSubscriptions` instead.
    private val _flow = MutableSharedFlow<ProvenanceUpdate>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Flow of full provenance snapshots for WebSocket broadcasting. */
    val flow: SharedFlow<ProvenanceUpdate> = _flow.asSharedFlow()

    // Coalesces provenance recomputes: emitUpdate is called from every layer-event site —
    // including per-MIDI-CC programmer writes and per-crossfade-tick Layer 4 republishes that
    // run while holding the publish lock — so the marker must be near-free and the
    // O(effects + keys) recompute must happen off the caller's thread, outside any lock.
    // `dirty` is flipped false *before* computing so a mutation landing mid-compute schedules
    // a fresh cycle.
    private val dirty = AtomicBoolean(false)

    // Bumped by every trigger that could have changed the programmer's value set — i.e.
    // anything but a `cueFadeOnly` weight tick — and carried on every emitted frame. Read
    // *after* compute() so a trigger landing mid-compute can only over-report "changed"
    // (a spurious refetch), never under-report: a bump the frame misses is delivered by the
    // next frame its own emitUpdate call guarantees (it either wins the `dirty` CAS or a
    // cycle is already pending), so a wrongly-skipped refetch heals within one coalescing
    // window and is never stranded.
    private val programmerRevision = AtomicLong(0)
    @Volatile private var scope: CoroutineScope? = null

    /** The current [ProvenanceUpdate.programmerRevision] — for the WS connect snapshot. */
    val currentProgrammerRevision: Long get() = programmerRevision.get()

    /** Wire the coalescing scope and seed the replay — called from [FxEngine.start]. */
    fun start(scope: CoroutineScope) {
        this.scope = scope
        // Seed the provenance replay so subscribers connecting before any layer event get
        // a (usually empty) snapshot instead of nothing.
        emitUpdate()
    }

    fun stop() {
        scope = null
    }

    /**
     * Mark provenance stale and schedule a coalesced recompute + broadcast. Called from
     * every layer-event site (programmer writes/clears/blind, Layer 4 republish, effect
     * lifecycle changes via `FxEngine.emitStateUpdate`) and by the park handlers. Cheap
     * enough to call while holding locks. Before [start] wires a scope (unit tests), the
     * recompute runs synchronously so assertions stay deterministic.
     *
     * [cueFadeOnly] may be passed as true only by a trigger that provably cannot have moved
     * the programmer's value set — today, exactly the crossfade weight-only republish
     * ([CueAssignmentLayer]'s `weightsOnly` path). Every other trigger bumps
     * [ProvenanceUpdate.programmerRevision], which is what re-arms the client refetch.
     */
    fun emitUpdate(cueFadeOnly: Boolean = false) {
        if (!cueFadeOnly) programmerRevision.incrementAndGet()
        val scope = scope
        if (scope == null) {
            val entries = compute()
            _flow.tryEmit(ProvenanceUpdate(entries, programmerRevision = programmerRevision.get()))
            return
        }
        if (dirty.compareAndSet(false, true)) {
            scope.launch(Dispatchers.Default) {
                delay(COALESCE_MS)
                dirty.set(false)
                val entries = compute()
                _flow.tryEmit(ProvenanceUpdate(entries, programmerRevision = programmerRevision.get()))
            }
        }
    }

    /**
     * Compute the winning contributor for every key any layer currently covers. Winner
     * order mirrors the output stack: park → programmer (unless blind) → highest-priority
     * running effect → cue layer. Keys nothing covers are omitted (baseline).
     */
    fun compute(): List<ProvenanceEntry> {
        val programmerKeys = if (programmerStore.blind) {
            emptySet()
        } else {
            val keys = HashSet(programmerStore.activeKeys())
            // Sideband slots drive the wire too (raw pan/tilt drags, unpark hand-downs):
            // attribute them to the property covering the channel. Channels with no
            // backing property stay unreported — there is no (target, property) to name.
            for (entry in programmerStore.channelEntries()) {
                publisher.resolveChannelCoveringKey(entry.universe, entry.channel)?.let { keys.add(it) }
            }
            keys
        }

        // Which programmer layer won each key it covers, so a programmer-won cell can name
        // *Warm Wash* rather than just "the programmer" — the same answer the cue branch below
        // gives from `cueLayerLayerWinners`. Ranks are resolved against the live layer list, and
        // `getOrNull` guards the window where the stack shrank after its slots were materialised.
        val programmerLayers = programmerStore.layers
        val programmerLayerWinners: Map<CueAssignmentResolver.Key, ProgrammerLayer> =
            if (programmerStore.blind) {
                emptyMap()
            } else {
                buildMap {
                    for ((key, rank) in programmerStore.layerWinnerRankByKey()) {
                        programmerLayers.getOrNull(rank)?.let { put(key, it) }
                    }
                }
            }

        val effectByKey = highestPriorityEffectByKey()

        // One snapshot for all three maps — this runs outside the publish lock, so
        // reading them as separate fields could straddle a concurrent cue apply. Reads the
        // nested [LayerResolver.CueLayerSnapshot.index], never the lazy flat `state`: this
        // recomputes per coalesced emit during a crossfade, and only ever needs the keys and
        // membership, so forcing the flat map would rebuild per snapshot the very duplicate
        // sweep item C3 removed from the publish path.
        val cueLayer = layerResolver.current
        val cueLayerIndex = cueLayer.index
        val cueLayerWinners = cueLayer.winners
        val cueLayerLayerWinners = cueLayer.layerWinners

        val keys = HashSet<CueAssignmentResolver.Key>(programmerKeys)
        for ((targetKey, properties) in cueLayerIndex) {
            for (propertyName in properties.keys) {
                keys.add(CueAssignmentResolver.Key.fixture(targetKey, propertyName))
            }
        }
        for ((pair, _) in effectByKey) {
            keys.add(CueAssignmentResolver.Key.fixture(pair.first, pair.second))
        }

        val entries = ArrayList<ProvenanceEntry>(keys.size)
        for (key in keys) {
            val fixture = try {
                fixtures.untypedGroupableFixture(key.targetKey)
            } catch (_: Exception) {
                continue
            }
            val target = publisher.inferTargetForProperty(fixture, key)

            val parked = target != null && publisher.allChannelsParked(target, fixture)
            val programmerActive = key in programmerKeys
            val effect = effectByKey[key.targetKey to key.propertyName]
            // A programmer entry suppresses non-band effects, so it outranks them here too;
            // a band effect modulates on top of the programmer and wins the provenance.
            val bandEffect = effect != null && FxEngine.isProgrammerFxPriority(effect.priority)

            val entry = when {
                parked -> ProvenanceEntry(key.targetKey, key.propertyName, ProvenanceSource.PARKED)
                programmerActive && (effect == null || !bandEffect) -> {
                    val layer = programmerLayerWinners[key]
                    ProvenanceEntry(
                        key.targetKey, key.propertyName, ProvenanceSource.PROGRAMMER,
                        layerId = layer?.layerId,
                        layerSource = layer?.source,
                    )
                }
                effect != null -> ProvenanceEntry(
                    key.targetKey, key.propertyName, ProvenanceSource.EFFECT,
                    cueId = effect.cueId, cueStackId = effect.cueStackId, effectId = effect.id,
                )
                cueLayerIndex[key.targetKey]?.containsKey(key.propertyName) == true -> {
                    val winningCueId = cueLayerWinners[key]
                    val layer = cueLayerLayerWinners[key]
                    ProvenanceEntry(
                        key.targetKey, key.propertyName, ProvenanceSource.CUE,
                        cueId = winningCueId,
                        cueStackId = winningCueId?.let { cueStackIdFor(it) },
                        layerId = layer?.layerId,
                        layerSource = layer?.source,
                    )
                }
                else -> continue
            }
            entries.add(entry)
        }
        entries.sortWith(compareBy({ it.targetKey }, { it.propertyName }))
        return entries
    }

    /**
     * Highest-priority running effect per `(fixtureKey, propertyName)`. Shared by
     * [compute] and [underlyingSources] so the two can't disagree about which effect is
     * driving a property.
     *
     * A **layer-stomped** effect is skipped for the key it is stomped on, and only for that key: it
     * is running, but it is not painting there, so reporting it would name a winner the operator
     * cannot see. Skipping per key rather than per instance is what lets a lower-priority effect on
     * the same key be reported instead, which is the honest answer when one exists.
     */
    private fun highestPriorityEffectByKey(
        include: (FxInstance) -> Boolean = { true },
    ): Map<Pair<String, String>, FxInstance> {
        val effectByKey = HashMap<Pair<String, String>, FxInstance>()
        for (effect in activeEffects()) {
            if (!effect.isRunning) continue
            if (!include(effect)) continue
            val propertyName = effect.target.propertyName
            for (fixtureKey in coverageKeys(effect)) {
                if (isLayerStomped(effect, fixtureKey, propertyName)) continue
                val k = fixtureKey to propertyName
                val current = effectByKey[k]
                if (current == null || effectOrder.compare(effect, current) > 0) {
                    effectByKey[k] = effect
                }
            }
        }
        return effectByKey
    }

    /**
     * What would own each of [keys] if the programmer weren't there — the "which cue am I
     * sitting on top of" question behind Update's Mode B checklist.
     *
     * This is deliberately *not* [compute]: provenance reports the programmer as the winner
     * (correctly — it is what's on stage), which is exactly the answer Mode B can't use.
     * `currentCueLayerWinners` is computed at Layer 4 publish time and knows nothing about the
     * programmer, so it already *is* "the cue underneath". Keys with no cue row fall back to
     * the highest-priority running cue-owned effect; programmer-band effects are skipped
     * because they are part of the same busk being written back, not something underneath it.
     *
     * Keys with no cue and no cue-owned effect are still returned, with nulls — the caller
     * buckets them as "programmer over baseline", which is a materially different offer to the
     * operator ("record a new cue") than "you're overriding cue 3".
     */
    fun underlyingSources(
        keys: Collection<CueAssignmentResolver.Key>,
        /**
         * The Layer 4 snapshot to attribute against. A caller that also reads the cue layer
         * itself (the Update checklist pairs `state` values with this attribution) must pass
         * the one snapshot it read, or a cue apply landing between the two reads pairs one
         * cue's value with another's attribution.
         */
        cueLayer: LayerResolver.CueLayerSnapshot = layerResolver.current,
    ): List<UnderlyingSource> {
        if (keys.isEmpty()) return emptyList()
        val cueLayerWinners = cueLayer.winners
        // Band effects are excluded from the *scan*, not filtered from its result. Filtering
        // afterwards would lose the cue underneath: band effects always outrank cue-derived
        // priorities, so a single top-priority-per-key map would only ever hold the band one,
        // and a cue driving that property through its own FX would report as unattributed.
        val effectByKey = highestPriorityEffectByKey { !FxEngine.isProgrammerFxPriority(it.priority) }
        return keys.map { key ->
            val cueId = cueLayerWinners[key]
            if (cueId != null) {
                UnderlyingSource(key, cueId, cueStackIdFor(cueId), viaEffectId = null)
            } else {
                val effect = effectByKey[key.targetKey to key.propertyName]
                    ?.takeIf { it.cueId != null }
                UnderlyingSource(key, effect?.cueId, effect?.cueStackId, effect?.id)
            }
        }
    }

    companion object {
        /** Coalescing window for provenance recomputes — see [emitUpdate]. */
        const val COALESCE_MS = 50L
    }
}
