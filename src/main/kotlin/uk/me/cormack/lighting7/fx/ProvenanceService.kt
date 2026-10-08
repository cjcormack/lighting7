package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.fixture.FixturePropertyCatalogue
import uk.me.cormack.lighting7.fixture.GroupableFixture
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
    /**
     * For a cue-won **colour** whose W / A / UV came from a bundled emitter's own row, one entry per
     * such emitter, naming *its* winner — empty otherwise.
     *
     * Layer 4 lets an emitter's row replace the colour's copy of it
     * (`CueAssignmentResolver.reconcileBundledEmitters`), so a colour can carry bytes from two
     * contributors: RGB from the one [cueId] / [layerId] name, the emitter's component from
     * another. One winner per key cannot say that, and "why is this fixture this colour?" would
     * credit the colour's cue with a white it never asserted.
     */
    val bundled: List<BundledProvenance> = emptyList(),
)

/** One bundled emitter's contribution to a reconciled colour — see [ProvenanceEntry.bundled]. */
data class BundledProvenance(
    /** The emitter property (`white`, `amber`, `uv`) whose own row supplied the component. */
    val propertyName: String,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    val layerId: Int? = null,
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
 * One property's whole stack, top first — what `programmer.keyStack` answers (fixture-fx-sheets
 * plan W1). See [ProvenanceService.keyStack].
 */
data class KeyStack(
    val targetKey: String,
    val propertyName: String,
    /** The programmer is blind: its slots are listed but none is on stage, and nothing is held back by it. */
    val blind: Boolean,
    val layers: List<KeyStackLayer>,
)

/** One parked channel and the value park holds it at. */
data class ParkedChannelValue(val universe: Int, val channel: Int, val value: UByte)

/**
 * One layer of a [KeyStack]. Which fields are set follows [kind]; every one carries [onStage] —
 * whether it contributes to what the rig is showing on this property now.
 */
data class KeyStackLayer(
    val kind: Kind,
    val onStage: Boolean,
    /** The literal this layer holds, where it holds one (a programmer slot, the cue's value, the base). */
    val value: CueAssignmentResolver.PropertyValue? = null,
    /** [Kind.PARK]: the property's parked channels. */
    val parkedChannels: List<ParkedChannelValue> = emptyList(),
    /** [Kind.PROGRAMMER]: the slot's owner, and its age at the read. */
    val owner: ProgrammerOwner? = null,
    val ageMs: Long? = null,
    /** [Kind.PROGRAMMER]: set when this is a raw-channel sideband slot rather than a property entry. */
    val channel: Pair<Int, Int>? = null,
    /** The two effect kinds: the running instance. */
    val effect: FxInstance? = null,
    /**
     * The two effect kinds: [EffectSuppression.isSuppressed]'s answer for this key, against the
     * engine's own snapshot — the effect is running but not painting here.
     */
    val heldBack: Boolean = false,
    val cueId: Int? = null,
    val cueStackId: Int? = null,
    /** The layer that produced this one: a programmer layer's id, or a cue layer's (`DaoCueLayer`). */
    val layerId: Int? = null,
    val layerSource: LayerSource? = null,
) {
    enum class Kind {
        PARK,

        /** An effect in the programmer priority band — it modulates on top of the programmer. */
        PROGRAMMER_EFFECT,

        /** One owner's programmer slot (or a sideband slot on one of the property's channels). */
        PROGRAMMER,

        /** Any other effect — a cue's, a manual one. Held back while the programmer holds the key. */
        EFFECT,

        /** What the cues compose to on this key. */
        CUE,

        /** The fixture default ([LayerResolver.baselineFor]). */
        BASE,
    }
}

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
    /**
     * The engine's programmer-suppression snapshot ([FxEngine.programmerSuppression]) — the very
     * map its tick reads, empty while blind. Provenance and [keyStack] ask
     * [EffectSuppression] against it rather than rebuilding a coverage map of their own, so "the
     * programmer holds this effect back" has one answer on the rig and on the wire (W1, W4).
     */
    private val programmerSuppression: () -> Map<String, Set<String>>,
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
     *
     * **The programmer outranks an effect only where the engine holds that effect back**
     * (fixture-fx-sheets plan W4), and both ask [EffectSuppression.heldBackByProgrammer] over one
     * snapshot to find out — of the key the effect *paints*, since that is the key the tick asks
     * about. The effect is the key's own, or, for a key the programmer holds with none of its own,
     * one painting **every** channel of the key under a sibling key ([siblingPaintings]).
     *
     * That second arm is the Channels tab's pan. `updateChannel` lifts a raw pan write to a `pan`
     * entry on a head that declares pan as a property, and keeps it in the sideband (filed under
     * `position`) on one that does not. A Circle is keyed `position` either way, so the engine never
     * holds it back for either — a `pan` entry is not a `position` entry, and a sideband slot
     * suppresses nothing — and the Circle is what is on stage. Before W4 both read *Programmer*.
     */
    fun compute(): List<ProvenanceEntry> {
        // One snapshot, the engine's own: empty while blind, so neither half below can name the
        // programmer under blind, exactly as before.
        val suppressing = programmerSuppression()
        val propertyKeys: Set<CueAssignmentResolver.Key> = buildSet {
            for ((fixtureKey, properties) in suppressing) {
                for (propertyName in properties) add(CueAssignmentResolver.Key.fixture(fixtureKey, propertyName))
            }
        }
        // Sideband slots drive the wire too (raw pan/tilt drags, unpark hand-downs): attribute
        // each to the property covering its channel, remembering which channels, so an effect
        // painting one of them through any key can be found. Channels with no backing property
        // stay unreported — there is no (target, property) to name.
        val sidebandChannels: Map<CueAssignmentResolver.Key, List<Pair<Int, Int>>> =
            if (programmerStore.blind) {
                emptyMap()
            } else {
                val out = HashMap<CueAssignmentResolver.Key, MutableList<Pair<Int, Int>>>()
                for (entry in programmerStore.channelEntries()) {
                    val key = publisher.resolveChannelCoveringKey(entry.universe, entry.channel) ?: continue
                    out.getOrPut(key) { ArrayList(1) }.add(entry.universe to entry.channel)
                }
                out
            }
        val programmerKeys = HashSet<CueAssignmentResolver.Key>(propertyKeys).apply { addAll(sidebandChannels.keys) }

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

        val siblings = siblingPaintings(programmerKeys, propertyKeys, sidebandChannels, effectByKey)

        val entries = ArrayList<ProvenanceEntry>(keys.size)
        for (key in keys) {
            val fixture = try {
                fixtures.untypedGroupableFixture(key.targetKey)
            } catch (_: Exception) {
                continue
            }
            val target = publisher.inferTargetForProperty(fixture, key)

            val parked = target != null && publisher.allChannelsParked(target, fixture)
            // The effect painting this key: its own, or one painting all of it under a sibling key.
            val painting: Painting? = effectByKey[key.targetKey to key.propertyName]
                ?.let { Painting(key.targetKey, key.propertyName, it) }
                ?: siblings[key]
            val effect = painting?.effect
            val programmerWins = key in programmerKeys && (
                painting == null || EffectSuppression.heldBackByProgrammer(
                    suppressing, painting.fixtureKey, painting.propertyName, painting.effect.priority,
                )
            )

            val entry = when {
                parked -> ProvenanceEntry(key.targetKey, key.propertyName, ProvenanceSource.PARKED)
                programmerWins -> {
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
                        bundled = bundledProvenance(fixture, key, cueLayer),
                    )
                }
                else -> continue
            }
            entries.add(entry)
        }
        entries.sortWith(compareBy({ it.targetKey }, { it.propertyName }))
        return entries
    }

    /** An effect and the key it paints, which need not be the key being asked about — see [compute]. */
    private data class Painting(val fixtureKey: String, val propertyName: String, val effect: FxInstance)

    /**
     * For each programmer-held key with no running effect of its own, the top effect that paints
     * **every** channel the programmer holds there under some other key — a Circle on `position`
     * over a `pan` entry. Every channel, not any: a white effect on a bundled emitter paints one
     * channel of an RGBW colour entry and leaves the rest the programmer's, so the colour stays
     * the programmer's.
     *
     * Walked from the effects' side, so a rig-wide programmer layer costs nothing here unless an
     * effect shares its channels: only a key found on an address some effect paints is ever
     * resolved to its own channels.
     */
    private fun siblingPaintings(
        programmerKeys: Set<CueAssignmentResolver.Key>,
        propertyKeys: Set<CueAssignmentResolver.Key>,
        sidebandChannels: Map<CueAssignmentResolver.Key, List<Pair<Int, Int>>>,
        effectByKey: Map<Pair<String, String>, FxInstance>,
    ): Map<CueAssignmentResolver.Key, Painting> {
        if (programmerKeys.isEmpty() || effectByKey.isEmpty()) return emptyMap()
        val painted = HashMap<Pair<Int, Int>, MutableSet<Painting>>()
        for ((paintKey, effect) in effectByKey) {
            val fixture = try {
                fixtures.untypedGroupableFixture(paintKey.first)
            } catch (_: Exception) {
                continue
            }
            val painting = Painting(paintKey.first, paintKey.second, effect)
            for (write in PropertyChannelWriter.channelsFor(fixture, paintKey.second)) {
                painted.getOrPut(write.universe.universe to write.channel) { HashSet(1) }.add(painting)
            }
        }
        val candidates = HashSet<CueAssignmentResolver.Key>()
        for ((universe, channel) in painted.keys) {
            for (key in publisher.resolveChannelPropertyKeys(universe, channel)) {
                if (key in programmerKeys && (key.targetKey to key.propertyName) !in effectByKey) candidates += key
            }
        }
        val out = HashMap<CueAssignmentResolver.Key, Painting>()
        for (key in candidates) {
            val held: List<Pair<Int, Int>> = if (key in propertyKeys) {
                val fixture = try {
                    fixtures.untypedGroupableFixture(key.targetKey)
                } catch (_: Exception) {
                    continue
                }
                PropertyChannelWriter.channelsFor(fixture, key.propertyName).map { it.universe.universe to it.channel }
            } else {
                sidebandChannels[key] ?: continue
            }
            if (held.isEmpty()) continue
            var common: Set<Painting>? = null
            for (address in held) {
                val here = painted[address] ?: emptySet()
                common = common?.intersect(here) ?: here
                if (common.isEmpty()) break
            }
            common?.maxWithOrNull { a, b -> effectOrder.compare(a.effect, b.effect) }?.let { out[key] = it }
        }
        return out
    }

    /**
     * The emitters whose own Layer 4 rows replaced components of [key]'s colour — exactly the pairs
     * `CueAssignmentResolver.reconcileBundledEmitters` reconciled, found the same way: [key] is the
     * bundle's colour on this class, and the emitter's key is composed beside it.
     */
    private fun bundledProvenance(
        fixture: GroupableFixture,
        key: CueAssignmentResolver.Key,
        cueLayer: LayerResolver.CueLayerSnapshot,
    ): List<BundledProvenance> {
        if (bundleRoleOf(fixture, key.propertyName) != CueAssignmentResolver.BundleRole.COLOUR) return emptyList()
        val composed = cueLayer.index[key.targetKey] ?: return emptyList()
        val bundled = FixturePropertyCatalogue.of(fixture::class).bundledByCategory.values
        return bundled.mapNotNull { emitter ->
            if (composed[emitter.name] !is CueAssignmentResolver.PropertyValue.Slider) return@mapNotNull null
            val emitterKey = CueAssignmentResolver.Key.fixture(key.targetKey, emitter.name)
            val cueId = cueLayer.winners[emitterKey]
            val layer = cueLayer.layerWinners[emitterKey]
            BundledProvenance(
                propertyName = emitter.name,
                cueId = cueId,
                cueStackId = cueId?.let { cueStackIdFor(it) },
                layerId = layer?.layerId,
                layerSource = layer?.source,
            )
        }
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

    /**
     * Every layer under one property, top first: park, programmer-band effects, programmer slots,
     * other effects (each with `heldBack`), the cue contributor, the base — the read behind the
     * fixture sheet's stack (fixture-fx-sheets plan W1, D5). Null for a key that names no fixture.
     *
     * Each part is asked of the code that decides it, against **one** snapshot of each input:
     * - held back is [EffectSuppression.isSuppressed] over the engine's suppression snapshot and
     *   its stomp check — the tick's own question, so the sheet cannot mark an effect held back
     *   that is painting, or the reverse;
     * - the cue contributor is [underlyingSources] against the one Layer 4 snapshot this read
     *   takes, which is also where its value and layer come from, so a cue apply landing mid-read
     *   cannot pair one cue's value with another's attribution;
     * - the base is [LayerResolver.baselineFor].
     *
     * [KeyStackLayer.onStage] walks the output order — park, effects by priority, the programmer,
     * the cue, the base — with an on-stage OVERRIDE effect, a programmer value, a cue value or a
     * full park covering everything below it. The layers are then *listed* in the plan's order,
     * which puts the programmer's own effects above its slots and the others below: on a key the
     * programmer holds, those are exactly the ones it is holding back.
     *
     * Cold path: on a socket that asked. A group's members go through [keyStacks], which takes
     * every snapshot once for all of them.
     */
    fun keyStack(fixtureKey: String, propertyName: String): KeyStack? =
        keyStacks(listOf(fixtureKey), propertyName).singleOrNull()

    /**
     * [keyStack] for several keys at once — a group target's members — against **one** snapshot of
     * every input, taken once per request rather than once per member: the suppression map, the
     * Layer 4 snapshot, the effect list with each effect's coverage, the sideband, and the cue
     * attribution ([underlyingSources] asked once for every key). The per-effect channel walk the
     * sibling rule needs is memoised across members, so a group of N heads under E effects resolves
     * each painted (key, property) once, not N times. A key naming no fixture is left out.
     */
    fun keyStacks(fixtureKeys: List<String>, propertyName: String): List<KeyStack> {
        val read = KeyStackRead(
            blind = programmerStore.blind,
            held = programmerSuppression(),
            cueLayer = layerResolver.current,
            now = System.currentTimeMillis(),
            effects = activeEffects().map { it to coverageKeys(it) },
            channelEntries = programmerStore.channelEntries(),
            programmerLayers = programmerStore.layers,
        )
        val keys = fixtureKeys.map { CueAssignmentResolver.Key.fixture(it, propertyName) }
        val underlying = underlyingSources(keys, read.cueLayer).associateBy { it.key }
        return keys.mapNotNull { key -> keyStackOf(key, read, underlying.getValue(key)) }
    }

    /** The inputs one [keyStacks] request reads, each taken once. */
    private inner class KeyStackRead(
        val blind: Boolean,
        val held: Map<String, Set<String>>,
        val cueLayer: LayerResolver.CueLayerSnapshot,
        val now: Long,
        val effects: List<Pair<FxInstance, List<String>>>,
        val channelEntries: List<ProgrammerStore.ChannelEntryView>,
        val programmerLayers: List<ProgrammerLayer>,
    ) {
        private val painted = HashMap<Pair<String, String>, Set<Pair<Int, Int>>>()

        /** The channels (fixture/element key, property) drives — memoised for the request. */
        fun channelsOf(fixtureKey: String, propertyName: String): Set<Pair<Int, Int>> =
            painted.getOrPut(fixtureKey to propertyName) {
                val fixture = try {
                    fixtures.untypedGroupableFixture(fixtureKey)
                } catch (_: Exception) {
                    return@getOrPut emptySet()
                }
                PropertyChannelWriter.channelsFor(fixture, propertyName).mapTo(HashSet()) { it.universe.universe to it.channel }
            }
    }

    private fun keyStackOf(key: CueAssignmentResolver.Key, read: KeyStackRead, underlying: UnderlyingSource): KeyStack? {
        val fixtureKey = key.targetKey
        val propertyName = key.propertyName
        val fixture = try {
            fixtures.untypedGroupableFixture(fixtureKey)
        } catch (_: Exception) {
            return null
        }
        val blind = read.blind
        val cueLayer = read.cueLayer
        val target = publisher.inferTargetForProperty(fixture, key)
        val channels = PropertyChannelWriter.channelsFor(fixture, propertyName)

        // ── Park ──
        val parkedChannels = channels.mapNotNull { write ->
            publisher.parkedValue(write.universe.universe, write.channel)
                ?.let { ParkedChannelValue(write.universe.universe, write.channel, it) }
        }
        val fullyParked = target != null && publisher.allChannelsParked(target, fixture)

        // ── Effects on the key, top first: its own, and any painting all of its channels under a
        // sibling key ([siblingPaintings]' rule) — a Circle on `position` is on a `pan` row's stack.
        // Held back is asked of the key each one paints, the tick's own question. ──
        val channelSet = channels.mapTo(HashSet()) { it.universe.universe to it.channel }
        val paintings = ArrayList<Painting>()
        for ((effect, covered) in read.effects) {
            if (effect.target.propertyName == propertyName && fixtureKey in covered) {
                paintings += Painting(fixtureKey, propertyName, effect)
                continue
            }
            if (channelSet.isEmpty()) continue
            for (paintKey in covered) {
                if (read.channelsOf(paintKey, effect.target.propertyName).containsAll(channelSet)) {
                    paintings += Painting(paintKey, effect.target.propertyName, effect)
                    break
                }
            }
        }
        paintings.sortWith { a, b -> effectOrder.compare(b.effect, a.effect) }
        val onKey = paintings.map { it.effect }
        val heldBack = paintings.associate {
            it.effect to EffectSuppression.isSuppressed(read.held, it.fixtureKey, it.propertyName, it.effect, isLayerStomped)
        }

        // ── Programmer: property slots (most recent first) and sideband slots on its channels ──
        val slots = programmerStore.slotsFor(fixtureKey, propertyName)
        val sideband = read.channelEntries
            .filter { (it.universe to it.channel) in channelSet }
            .map { it to it.slots.first() }
        val programmerLayers = read.programmerLayers
        val now = read.now

        // ── The cue contributor, from the request's one Layer 4 snapshot ──
        val cueValue = cueLayer.index[fixtureKey]?.get(propertyName)
        val cueLayerWinner = cueLayer.layerWinners[key]

        // ── Base ──
        val base = target?.let { layerResolver.baselineFor(it, fixture).asPropertyValueFor(it) }

        // ── On stage: the output order, top first ──
        var covered = fullyParked
        val effectOnStage = HashMap<FxInstance, Boolean>()
        for (effect in onKey) {
            val on = !covered && effect.isRunning && heldBack[effect] != true
            effectOnStage[effect] = on
            if (on && effect.blendMode == BlendMode.OVERRIDE) covered = true
        }
        // Within the programmer, recency arbitrates across granularities (`ProgrammerStore.Slot.seq`):
        // a sideband slot newer than the top property slot is what its channel carries. On a
        // one-channel property such a slot is the whole value, so the property slot is not on stage;
        // on a wider one (a pan byte under a `position` entry) both are, each on its own channels.
        val programmerVisible = !covered && !blind
        val topSlot = slots.firstOrNull()
        val newerSideband = sideband.filter { (_, slot) -> topSlot == null || slot.seq > topSlot.seq }
        val sidebandTakesAll = channels.size == 1 && newerSideband.isNotEmpty()
        val topSlotOnStage = topSlot != null && programmerVisible && !sidebandTakesAll
        val sidebandOnStage = sideband.associate { (entry, slot) ->
            (entry.universe to entry.channel) to (programmerVisible && newerSideband.any { it.second === slot })
        }
        if (topSlotOnStage || (programmerVisible && sidebandTakesAll)) covered = true
        val cueOnStage = cueValue != null && !covered
        if (cueOnStage) covered = true
        val baseOnStage = !covered

        // ── Listed in the plan's order ──
        val layers = ArrayList<KeyStackLayer>()
        if (parkedChannels.isNotEmpty()) {
            layers += KeyStackLayer(KeyStackLayer.Kind.PARK, onStage = true, parkedChannels = parkedChannels)
        }
        fun effectLayer(kind: KeyStackLayer.Kind, effect: FxInstance) = KeyStackLayer(
            kind = kind,
            onStage = effectOnStage[effect] == true,
            effect = effect,
            heldBack = heldBack[effect] == true,
            cueId = effect.cueId,
            cueStackId = effect.cueStackId,
            layerId = effect.programmerLayerId ?: effect.cueLayerId,
            layerSource = effect.source,
        )
        for (effect in onKey) {
            if (FxEngine.isProgrammerFxPriority(effect.priority)) {
                layers += effectLayer(KeyStackLayer.Kind.PROGRAMMER_EFFECT, effect)
            }
        }
        for ((index, slot) in slots.withIndex()) {
            val layer = programmerStore.layerRankOf(slot)?.let { programmerLayers.getOrNull(it) }
            layers += KeyStackLayer(
                kind = KeyStackLayer.Kind.PROGRAMMER,
                onStage = index == 0 && topSlotOnStage,
                value = slot.value.resolved,
                owner = slot.owner,
                ageMs = (now - slot.writtenAtMs).coerceAtLeast(0),
                layerId = layer?.layerId,
                layerSource = layer?.source,
            )
        }
        for ((entry, slot) in sideband) {
            layers += KeyStackLayer(
                kind = KeyStackLayer.Kind.PROGRAMMER,
                onStage = sidebandOnStage[entry.universe to entry.channel] == true,
                value = slot.value.resolved,
                owner = slot.owner,
                ageMs = (now - slot.writtenAtMs).coerceAtLeast(0),
                channel = entry.universe to entry.channel,
            )
        }
        for (effect in onKey) {
            if (!FxEngine.isProgrammerFxPriority(effect.priority)) {
                layers += effectLayer(KeyStackLayer.Kind.EFFECT, effect)
            }
        }
        if (cueValue != null) {
            layers += KeyStackLayer(
                kind = KeyStackLayer.Kind.CUE,
                onStage = cueOnStage,
                value = cueValue,
                cueId = underlying.cueId,
                cueStackId = underlying.cueStackId,
                layerId = cueLayerWinner?.layerId,
                layerSource = cueLayerWinner?.source,
            )
        }
        if (base != null) {
            layers += KeyStackLayer(KeyStackLayer.Kind.BASE, onStage = baseOnStage, value = base)
        }
        return KeyStack(fixtureKey, propertyName, blind, layers)
    }

    /** An [FxOutput] in the literal grammar of [target]'s property. */
    private fun FxOutput.asPropertyValueFor(target: FxTarget): CueAssignmentResolver.PropertyValue = when (this) {
        is FxOutput.Slider ->
            if (target is SettingTarget) CueAssignmentResolver.PropertyValue.Setting(value)
            else CueAssignmentResolver.PropertyValue.Slider(value)
        is FxOutput.Colour -> CueAssignmentResolver.PropertyValue.Colour(color)
        is FxOutput.Position -> CueAssignmentResolver.PropertyValue.Position(pan, tilt)
    }

    companion object {
        /** Coalescing window for provenance recomputes — see [emitUpdate]. */
        const val COALESCE_MS = 50L
    }
}
