package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.models.sameTargets
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID
import uk.me.cormack.lighting7.models.LayerSource

private val logger = LoggerFactory.getLogger("ProgrammerLayerStack")

/**
 * One Look or template applied to the programmer, at a declared position in its stack.
 *
 * The in-memory twin of `DaoCueLayer`, minus the three timing columns: there is no trigger manager
 * for the programmer and a programmer layer is always immediate. Include drops a cue's timed layers
 * rather than pretending to hold them, and Update leaves them untouched.
 */
data class ProgrammerLayer(
    /** In-memory identity from [ProgrammerStore.mintLayerId]. **Not** a `DaoCueLayer` id. */
    val layerId: Int,
    /** What this layer applies — see [LayerSource]. */
    val source: LayerSource,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val targets: List<CueTargetDto> = emptyList(),
    val propertyMask: String? = null,
    val blendMode: String = "OVERRIDE",
    val amount: Double = 1.0,
    val stomp: Boolean = false,
    val speedMasterUuid: UUID? = null,
    val rateSpeedMasterUuid: UUID? = null,
    /**
     * The `DaoCueLayer` row this was minted from, when Include loaded it out of a cue. Update's
     * structural diff keys on this to tell an edited layer from a new one.
     */
    val sourceCueLayerId: Int? = null,
    /**
     * Overrides every effect's own beat division when this layer spawns them.
     *
     * Programmer-only, and deliberately not on `CookLayer`/`DaoCueLayer`: it exists because the
     * busking-pad toggle has always accepted one, and a cue layer has no equivalent gesture. No
     * shipping client sends it today; it is honoured rather than quietly dropped because the route
     * documents it.
     */
    val beatDivisionOverride: Double? = null,
) {
    internal fun toCookLayer() = CookLayer(
        source = source,
        sortOrder = sortOrder,
        enabled = enabled,
        targets = targets,
        propertyMask = propertyMask,
        blendMode = blendMode,
        amount = amount,
        stomp = stomp,
        speedMasterUuid = speedMasterUuid,
        rateSpeedMasterUuid = rateSpeedMasterUuid,
        layerId = layerId,
    )
}

/** What a recook moved, for the caller to report. */
data class ProgrammerLayerOutcome(
    val keysRepublished: Int,
    val effectsSpawned: Int,
    val effectsRetracted: Int,
    val effectsRepriorised: Int,
)

/**
 * The programmer's ordered Look-layer stack: the live, editable twin of a cue's layer list.
 *
 * ## Materialise, don't compose at read
 *
 * Every mutation re-cooks the whole stack and **materialises the result into
 * [ProgrammerOwner.LAYERS] slots**, rather than teaching the 50 Hz read path about layers.
 *
 * The reason is not the per-tick cost. `FxTarget.composeProgrammerOver` is only reached for keys a
 * running effect covers; everything *else* about the programmer is answered by cold-path coverage
 * oracles — `coversFixture` (the gate in `LayerResolver.fallbackFor`), `activePropertiesByFixture`
 * (effect suppression), `activeKeys` (provenance, blind republish, Clear), `entries` (Record and
 * the wire snapshot). Composing at read would make **every one of those** layer-aware, and each
 * needs the cooked key set — so it would mean cooking anyway, plus cooking per fixture per tick.
 * Materialising gets all of them unchanged, and keeps one composition implementation
 * ([CueComposer.cook]) rather than two.
 *
 * The cost worth knowing: a rig-wide layer makes `coversFixture` true for every fixture, defeating
 * the O(1) gate that exists to stop the colour path's bundled-slider scan taxing heads the
 * programmer isn't touching. Already reachable today via a rig-wide Locate, and it only bites for
 * keys under a running effect.
 *
 * ## Concurrency
 *
 * Three rules, in order of how expensive they are to get wrong:
 *
 * 1. **Cook outside every engine lock.** [LookRegistry.expanded] can fall through to
 *    `loadLookSnapshot`, which opens its own transaction and must never run under
 *    the Layer 4 publish lock (`CascadePublisher`'s, reached via `locked`). So a recook is always: cook → [ProgrammerStore.putLayerSlots]
 *    (lock-free) → [ProgrammerWriter.republishKeys] (which takes the lock, and is the only step
 *    that does).
 * 2. **Lock order is `layersLock` → the publish lock.** Nothing in `CascadePublisher` or
 *    `FxEngine` calls back into this class, so the reverse never arises today; it is stated so
 *    it stays that way.
 * 3. **One recook per mutation.** [ProgrammerStore.mutateLayers] serialises the list edit, then the
 *    cook happens outside it — two mutations racing therefore both cook, and the later publish
 *    wins. That is correct rather than merely tolerable: both cooked from a list that really
 *    existed, and the store's own `compute` makes each materialisation atomic per key.
 */
class ProgrammerLayerStack(
    private val fixtures: () -> Fixtures,
    private val lookRegistry: () -> LookRegistry,
    private val templateRegistry: () -> TemplateRegistry,
    private val engine: () -> FxEngine,
    private val store: ProgrammerStore,
    /**
     * Supplied lazily because `Show` builds this before `State` finishes wiring itself, and only
     * effect *spawning* needs it — `resolveTargetForCue` and `createEffectInstance` both take
     * it. Values cook with no `State` at all, which is what lets the value half be tested without
     * a database.
     */
    private val state: () -> State?,
) {
    /**
     * Guards the classify-spawn-retract sequence in [syncEffects].
     *
     * The live band state itself lives on the engine — each instance carries its
     * [ProgrammerLayerEffectKey], and [FxEngine.programmerLayerEffects] is the only record of
     * which layer effects exist (sweep item E6) — so there is no map to corrupt here. The lock
     * remains because the sequence is read-then-write: two recooks racing between the snapshot
     * and the spawns would each decide to spawn the same missing effect. Same-key twins are worse
     * than a glitch — [FxEngine.programmerLayerEffects] keeps one instance per key, so the losing
     * twin could never be retracted by a recook and would sit on stage until a band sweep.
     *
     * Separate from `ProgrammerStore`'s layer lock and taken *after* it, never around it: the cook
     * must stay outside every lock (`loadLookSnapshot` opens a transaction), so two mutations can
     * legitimately reach here concurrently.
     */
    private val effectsLock = Any()

    /**
     * The pseudo cue id a programmer cook runs under.
     *
     * [CueComposer] takes one for its log lines and stamps it on the assignments it returns, but
     * the programmer keeps only the values — the assignments never reach `cueAssignments`, so this
     * cannot collide with a real cue. Negative so it is obvious in a log line.
     */
    private val programmerCookCueId = -1

    // ── Mutations ───────────────────────────────────────────────────────────

    /** Append a layer to the top of the stack. */
    fun add(
        source: LayerSource,
        targets: List<CueTargetDto> = emptyList(),
        propertyMask: String? = null,
        blendMode: String = "OVERRIDE",
        amount: Double = 1.0,
        speedMasterUuid: UUID? = null,
        rateSpeedMasterUuid: UUID? = null,
        sourceCueLayerId: Int? = null,
        beatDivisionOverride: Double? = null,
        fadeMs: Long = 0,
    ): Pair<ProgrammerLayer, ProgrammerLayerOutcome> {
        val layer = ProgrammerLayer(
            layerId = store.mintLayerId(),
            source = source,
            sortOrder = 0, // renumbered below
            targets = targets,
            propertyMask = propertyMask,
            blendMode = blendMode,
            amount = amount,
            speedMasterUuid = speedMasterUuid,
            rateSpeedMasterUuid = rateSpeedMasterUuid,
            sourceCueLayerId = sourceCueLayerId,
            beatDivisionOverride = beatDivisionOverride,
        )
        val (next, _) = store.mutateLayers { current ->
            renumber(current + layer) to Unit
        }
        return layer to recook(next, fadeMs, arrival = true)
    }

    /**
     * The busking pad's gesture: put this Look or template on these targets, or take it off again.
     *
     * "Already on" means **a layer with the same source and the same target set** — the
     * pad's own reading, and the one that lets the same Look sit on two different target sets as two
     * independently-toggleable pads. Matching on the whole [LayerSource] rather than on an id is
     * what keeps a Look and a template that happen to share an int PK from cancelling each other.
     * The target comparison is order-insensitive ([sameTargets]): a pad re-sending its own target
     * list in a different order — the client re-derived it from a `Set`, say — must still toggle the
     * existing layer off rather than stacking a second, functionally-identical one on top.
     * Returns `"applied"`/`"removed"` and how many effects moved, which is the contract
     * `togglePresetOnTargets` had and the pads still read.
     *
     * Note a rows-only Look reports `0` either way, and a **template always does**: neither spawns
     * effects. That was true before this rewrite too, and it is why the pads' active ring cannot be
     * driven from the effect list alone.
     *
     * [propertyMask] applies to the *arriving* layer only. It is deliberately **not** part of the
     * "already on" comparison: a pad that re-pressed with a different mask should still take its
     * layer off rather than stack a second one, and the mask a template layer wants is a function of
     * the template, not of the press. A Look passes null — a Look spans families by construction.
     */
    fun toggle(
        source: LayerSource,
        targets: List<CueTargetDto>,
        propertyMask: String? = null,
        beatDivisionOverride: Double? = null,
    ): Pair<String, Int> {
        val existing = store.layers.firstOrNull {
            it.source == source && sameTargets(it.targets, targets)
        }
        return if (existing != null) {
            "removed" to remove(existing.layerId).effectsRetracted
        } else {
            "applied" to add(
                source = source,
                targets = targets,
                propertyMask = propertyMask,
                beatDivisionOverride = beatDivisionOverride,
            ).second.effectsSpawned
        }
    }

    /** Remove a layer by id. A no-op when it is already gone. */
    fun remove(layerId: Int, fadeMs: Long = 0): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            renumber(current.filterNot { it.layerId == layerId }) to Unit
        }
        return recook(next, fadeMs)
    }

    /** Move a layer to [toIndex], renumbering the whole list. */
    fun move(layerId: Int, toIndex: Int): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            val from = current.indexOfFirst { it.layerId == layerId }
            if (from < 0) return@mutateLayers current to Unit
            val moving = current[from]
            val rest = current.toMutableList().apply { removeAt(from) }
            rest.add(toIndex.coerceIn(0, rest.size), moving)
            renumber(rest) to Unit
        }
        return recook(next)
    }

    /** Change one layer's presentation fields. Null means "leave alone". */
    fun patch(
        layerId: Int,
        enabled: Boolean? = null,
        amount: Double? = null,
        propertyMask: String? = null,
        blendMode: String? = null,
        targets: List<CueTargetDto>? = null,
        stomp: Boolean? = null,
        fadeMs: Long = 0,
    ): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            current.map {
                if (it.layerId != layerId) it
                else it.copy(
                    enabled = enabled ?: it.enabled,
                    amount = amount?.coerceIn(0.0, 1.0) ?: it.amount,
                    propertyMask = propertyMask ?: it.propertyMask,
                    blendMode = blendMode ?: it.blendMode,
                    targets = targets ?: it.targets,
                    stomp = stomp ?: it.stomp,
                )
            } to Unit
        }
        return recook(next, fadeMs)
    }

    /**
     * Replace the stack with a cue's layers — Include.
     *
     * Each becomes a programmer layer carrying `sourceCueLayerId`, which is how Update's structural
     * diff later tells an edited layer from a newly added one.
     *
     * **Timed layers are dropped, not held.** A programmer layer is always immediate — there is no
     * trigger manager for the programmer — so a delayed layer has nothing to fire it here. Include
     * reports how many it skipped rather than silently flattening them to immediate, which would
     * put a chase's whole stack on stage at once; Update then leaves the cue's timed layers
     * untouched, so nothing is lost by not holding them.
     *
     * Returns the number of timed layers skipped, for the response.
     */
    internal fun installFromCue(
        layers: List<CookLayer>,
        fadeMs: Long = 0,
    ): Pair<Int, ProgrammerLayerOutcome> {
        val immediate = layers.filterNot { it.isTimed }
        val skipped = layers.size - immediate.size
        val (next, _) = store.mutateLayers { current ->
            val installed = immediate.sortedBy { it.sortOrder }.map { layer ->
                ProgrammerLayer(
                    layerId = store.mintLayerId(),
                    source = layer.source,
                    sortOrder = 0, // renumbered below
                    enabled = layer.enabled,
                    targets = layer.targets,
                    propertyMask = layer.propertyMask,
                    // The one point a *stored* blend becomes programmer state, and so the one
                    // place the lenient policy belongs on this path: a row written by another
                    // build may name a blend this one does not know, and Record writes these
                    // layers straight back out again. Canonicalising here means the stack, the
                    // UI and every subsequent write agree on the blend the desk is actually
                    // playing — and that `createCueChildren`'s strict check, which Record CREATE
                    // does not catch, can never see an unrecognised value from this direction.
                    blendMode = EffectSpecCoercion.Lenient.blendMode(layer.blendMode) {
                        "included cue layer ${layer.layerId} on '${layer.source.name}'"
                    }.name,
                    amount = layer.amount,
                    stomp = layer.stomp,
                    speedMasterUuid = layer.speedMasterUuid,
                    rateSpeedMasterUuid = layer.rateSpeedMasterUuid,
                    sourceCueLayerId = layer.layerId,
                )
            }
            renumber(installed) to Unit
        }
        return skipped to recook(next, fadeMs, arrival = true)
    }

    /**
     * Drop the whole stack without publishing.
     *
     * Called from `clearProgrammerCompletely` in the same position `resetPresetProgrammerBookkeeping`
     * held, and for the same reason: the sweep that follows releases every slot, so running this
     * class's own release path afterwards would double-release, while leaving the bookkeeping would
     * desync the stack from the slots. The band-effect sweep in that sequence takes the layers'
     * effects with it, which is why nothing is retracted here.
     */
    fun reset() {
        store.mutateLayers { emptyList<ProgrammerLayer>() to Unit }
        // No effect bookkeeping to drop: the band lives on the engine's instances, and the
        // band-effect sweep in `clearProgrammerCompletely` is what takes those.
        //
        // The suppression is cleared explicitly rather than left to the next recook, because this
        // is the one mutation that deliberately doesn't recook. It would be inert either way —
        // `mintLayerId` is monotonic for the life of the process, so no future layer can inherit
        // a stale entry — but relying on that makes a local invariant depend on a distant one.
        engine().cueLayer.setProgrammerStompSuppression(emptyMap())
    }

    /**
     * Re-cook if any layer names [lookUuid] — the programmer's half of a Look edit.
     *
     * Returns the keys whose value moved, for `republishForLookEdit` to fold into its single
     * publish. It deliberately does **not** publish here: that function composes the programmer
     * over the cue layer, so publishing before it has rebuilt the cues would transmit a
     * half-updated cascade and correct it a frame later.
     *
     * **Values only — the Look's *effects* are not re-spawned.** That matches what a Look edit does
     * to a live cue (`republishCueIfLive` says the same in as many words), and for the same reason:
     * re-spawning would restart the phase of every effect the edit did not touch, so an operator
     * nudging a colour would visibly re-trigger a chase. The consequence to know is that adding or
     * removing an *effect* on a Look does not reach layers already applied — value edits tour, effect
     * edits need the layer re-added.
     */
    fun recookIfReferences(sourceUuid: UUID): Set<CueAssignmentResolver.Key> {
        val layers = store.layers
        // Matched on the uuid alone rather than on (kind, uuid): uuids are random and unique across
        // both tables, so a caller republishing after an edit does not have to say which kind of
        // thing it edited. The template path uses this unchanged.
        if (layers.none { it.source.uuid == sourceUuid }) return emptySet()
        // Keys only. The per-key fades are deliberately dropped: `republishForLookEdit` folds these
        // into one publish across the programmer *and* every live cue, and a Look edit touring to
        // an already-applied layer is not the layer arriving — re-timing it would make every nudge
        // of a colour crossfade on stage.
        return materialise(cookStack(layers, withEffects = false).values).moved
    }

    // ── The cook ────────────────────────────────────────────────────────────

    /**
     * @param arrival whether this mutation puts a source *on stage* — [add] (so [toggle]'s applied
     *   arm) and [installFromCue]. Only an arrival lets the cooked rows' own `fadeDurationMs` ramp
     *   the publish; [patch], [move] and [remove] must not, and the reason is the same one
     *   `FxEngine.republishCueAssignments` gates on: `amount` is folded into the cooked value, so
     *   an operator dragging a layer's Amount slider over a Look with a 2 s dimmer row would
     *   restart that ramp on every drag event and the rig would never track the slider.
     */
    private fun recook(
        layers: List<ProgrammerLayer>,
        fadeMs: Long = 0,
        arrival: Boolean = false,
    ): ProgrammerLayerOutcome {
        // One cook for the whole recook (sweep item C8): the rows and the effect triples come out
        // of a single pass over the stack, so the layers resolve once and both halves see the same
        // Look snapshots.
        val cooked = cookStack(layers, withEffects = true)
        val (moved, rowFades) = materialise(cooked.values)
        val fx = syncEffects(layers, cooked)
        if (moved.isNotEmpty()) {
            val eng = engine()
            if (!arrival || rowFades.isEmpty() || fadeMs > 0) {
                // A caller-supplied `fadeMs` covers every key and wins outright: an explicit fade on
                // Include is the operator's instruction, and it overrides the source's stored
                // default exactly as `POST /templates/{id}/apply` does with `request.fadeMs`.
                eng.programmer.republishKeys(moved, fadeMs)
            } else {
                eng.programmer.republishKeys(moved, rowFades)
            }
        }
        return ProgrammerLayerOutcome(moved.size, fx.spawned, fx.retracted, fx.repriorised)
    }

    /**
     * What one [materialise] moved: the keys to publish, and the per-key fade the winning row asked
     * for. Keys asking for no fade are absent from [rowFades] — see
     * [LayerResolver.CueLayerSnapshot.fadeDurations] for the same convention on the cue side.
     */
    private data class Materialised(
        val moved: Set<CueAssignmentResolver.Key>,
        val rowFades: Map<CueAssignmentResolver.Key, Long>,
    )

    /**
     * Cook the stack.
     *
     * [withEffects] asks for the effect triples alongside the rows, out of the same pass over the
     * layers — which is what [recook] wants. [recookIfReferences] passes false: it republishes
     * values and deliberately leaves the running effects alone, so collecting them would be work
     * thrown away.
     *
     * Called **outside** [effectsLock], always. `loadLookSnapshot` opens a transaction, and the
     * class doc's rule that the cook stays outside every lock is why — the effect half used to be
     * cooked from inside that lock, which is the violation this consolidation removes.
     */
    private fun cookStack(layers: List<ProgrammerLayer>, withEffects: Boolean): CueComposer.FullCook =
        CueComposer.cookAll(
            fixtures = fixtures(),
            cueId = programmerCookCueId,
            priority = 0,
            layers = layers.map { it.toCookLayer() },
            // The programmer's *local* layer is the store's other owners — WEB, SURFACE, LOCATE and
            // the rest — which already sit above these slots. Passing them here as well would cook
            // them into the layer contribution and lose that distinction: Record could no longer
            // tell the operator's own edits from the stack's output.
            localRows = emptyList(),
            resolveLook = lookRegistry()::snapshot,
            resolveTemplate = templateRegistry()::snapshot,
            withEffects = withEffects,
        )

    /**
     * Swap a cook's values into the store. No publish.
     *
     * Also republishes the stack's within-cue stomp suppression, which has to happen here rather
     * than in [syncEffects] beside the instances it affects: only the cook knows which properties
     * each layer asserted, and `syncEffects` deliberately does not rebuild on a mask, amount or
     * order change — all three of which move the suppression set.
     */
    private fun materialise(cooked: CookResult): Materialised {
        engine().cueLayer.setProgrammerStompSuppression(cooked.stompSuppression)
        val moved = store.putLayerSlots(
            cooked.rows.map { row ->
                ProgrammerStore.LayerSlotWrite(
                    fixtureKey = row.targetKey,
                    propertyName = row.propertyName,
                    value = row.value,
                    // Absent only when a local row won, which cannot happen here — nothing is
                    // passed as a local row. Index 0 is a safe floor either way.
                    layerIndex = row.layerWinner?.index ?: 0,
                )
            }
        )
        // Only the keys that actually moved need a fade: a slot the cook left where it was is not
        // republished, so timing it would be timing nothing.
        val rowFades = if (moved.isEmpty()) {
            emptyMap()
        } else {
            cooked.rows.mapNotNull { row ->
                val fade = row.fadeDurationMs?.takeIf { it > 0 } ?: return@mapNotNull null
                CueAssignmentResolver.Key.fixture(row.targetKey, row.propertyName)
                    .takeIf { it in moved }
                    ?.let { it to fade }
            }.toMap()
        }
        return Materialised(moved, rowFades)
    }

    private data class EffectSync(val spawned: Int, val retracted: Int, val repriorised: Int)

    /**
     * Bring the live programmer-band effects into line with the stack.
     *
     * Classifies rather than rebuilds: **only a layer that is newly present, or newly gone, moves
     * an instance.** An amount, mask, target or *order* change re-ranks what is already running.
     * Respawning on a reorder would restart every effect's phase, which is exactly the glitch the
     * layer-index priority scheme exists to avoid.
     */
    private fun syncEffects(
        layers: List<ProgrammerLayer>,
        cooked: CueComposer.FullCook,
    ): EffectSync = synchronized(effectsLock) {
        val eng = engine()
        // The engine's record *is* the band: anything removed by another surface (the FX sheet's
        // own remove, a band sweep) is simply absent here, with no bookkeeping to reconcile.
        val live = eng.programmerLayerEffects()

        val desired = cooked.effects

        // Ranks come from the same cook as the rows, so an effect's band offset and its layer's
        // cooked `CookWinner.index` are one number rather than two computed apart.
        val rankOf = cooked.ranks

        val byLayerId = layers.associateBy { it.layerId }
        val wanted = HashSet<ProgrammerLayerEffectKey>(desired.size)
        val repriorities = HashMap<Long, Int>()
        // Built here, added in one `addEffects` below: a per-instance add rebuilt the engine's
        // sorted snapshots and re-broadcast the whole active-effect list once per effect, which
        // is O(N²) over a stack of any size (sweep item C7). Build order is add order, so the
        // layer-rank priorities still decide composition exactly as before.
        val spawning = mutableListOf<FxInstance>()

        for ((layer, effect, target) in desired) {
            val key = ProgrammerLayerEffectKey(layer.layerId, effect, target.key)
            // A Look holding the same effect twice on one target would collide here; the second is
            // dropped rather than spawning a second instance under the same identity — the retract
            // pass matches on the key, so only one of the twins could ever be seen.
            if (!wanted.add(key)) continue
            val priority = priorityFor(rankOf[layer.layerId] ?: 0)
            val existing = live[key]
            if (existing != null) {
                repriorities[existing.id] = priority
                continue
            }
            val override = byLayerId[layer.layerId]?.beatDivisionOverride
            spawning += build(layer, effect, target, key, priority, override) ?: continue
        }

        val spawned = eng.addEffects(spawning).size

        var retracted = 0
        for ((key, instance) in live) {
            if (key in wanted) continue
            if (eng.removeEffect(instance.id)) retracted++
        }

        val repriorised = eng.repriorityProgrammerLayerEffects(repriorities)
        EffectSync(spawned, retracted, repriorised)
    }

    /**
     * The programmer band, offset by layer rank.
     *
     * [rank] is a [CueComposer.contributingLayers] index — the same number [CueComposer.cook] puts
     * in `CookWinner.index` — so an effect's position in the band and its layer's cooked rank are
     * one value, not two that have to be kept equal.
     *
     * `sortedEffectsComparator` is `compareBy(priority, id)` with `id` a monotonic creation
     * counter, so without the offset relative order would be *spawn* order and a reorder would need
     * a respawn to express itself. Every consumer of the band tests
     * `isProgrammerFxPriority` (`>= BASE`) rather than exact equality, so an offset is invisible to
     * all of them. The band is a million wide; [FxEngine.PROGRAMMER_FX_RANK_CLAMP] is a backstop,
     * not an expected case.
     */
    private fun priorityFor(rank: Int): Int =
        FxEngine.PROGRAMMER_FX_PRIORITY_BASE + rank.coerceIn(0, FxEngine.PROGRAMMER_FX_RANK_CLAMP)

    /**
     * Build the instance a programmer layer's effect wants, or null if it can't be built.
     * The caller adds it — see the batched `addEffects` in [syncEffects].
     */
    private fun build(
        layer: CookLayer,
        effect: LookEffectEntry,
        target: TargetRef,
        key: ProgrammerLayerEffectKey,
        priority: Int,
        beatDivisionOverride: Double?,
    ): FxInstance? {
        val st = state() ?: return null
        val spec = effect.toEffectSpec()
            .let { if (beatDivisionOverride == null) it else it.copy(beatDivision = beatDivisionOverride) }
        val fxTarget = try {
            EffectSpawner.resolveTargetForCue(st, CueTargetDto(target), spec)
        } catch (e: Exception) {
            logger.warn(
                "programmer layer on '{}' — target '{}' unresolvable — skipping effect: {}",
                layer.source.name, target.key, e.message,
            )
            null
        } ?: return null

        // [createEffectInstance] gives this a **live** colour source, unlike the frozen
        // snapshot this path used to build for itself.
        //
        // The old positional colour list was captured at include time — version pinned to `0L` — so
        // an effect on a programmer layer kept the colours it was born with. A `tmpl:` reference is
        // a live dependency by design: the whole point of naming a template rather than stating a
        // colour is that retuning it moves everything that follows it, and a programmer layer is no
        // more exempt from that than a cue's.
        val instance = try {
            EffectSpawner.createEffectInstance(
                spec, fxTarget, state = st,
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            )
        } catch (e: Exception) {
            logger.warn(
                "programmer layer '{}': effect '{}' could not be created — {}",
                layer.source.name, effect.effectType, e.message,
            )
            return null
        }
        // `lookId` and the band key are the honest fields for where this came from.
        // Only a Look can own an effect (D7), so only a Look id belongs here — a template layer
        // never reaches `build` because a template holds no effects to spawn.
        instance.lookId = layer.source.id.takeUnless { layer.source.isTemplate }
        // The key is the instance's identity in the band: [syncEffects] classifies the engine's
        // live instances by it on the next recook, so an unstamped instance would be retracted
        // as unrecognised and respawned every mutation.
        instance.programmerLayerEffectKey = key
        instance.priority = priority
        return instance
    }

    private fun renumber(layers: List<ProgrammerLayer>): List<ProgrammerLayer> =
        layers.mapIndexed { index, layer ->
            if (layer.sortOrder == index) layer else layer.copy(sortOrder = index)
        }
}
