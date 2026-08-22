package uk.me.cormack.lighting7.fx

import org.slf4j.LoggerFactory
import uk.me.cormack.lighting7.models.CueTargetDto
import uk.me.cormack.lighting7.models.TargetRef
import uk.me.cormack.lighting7.routes.TogglePresetTarget
import uk.me.cormack.lighting7.routes.createInstanceFromPreset
import uk.me.cormack.lighting7.routes.resolveTargetForCue
import uk.me.cormack.lighting7.show.Fixtures
import uk.me.cormack.lighting7.state.State
import java.util.UUID

private val logger = LoggerFactory.getLogger("ProgrammerLayerStack")

/**
 * One Look applied to the programmer, at a declared position in its stack.
 *
 * The in-memory twin of `DaoCueLayer`, minus the three timing columns: there is no trigger manager
 * for the programmer and a programmer layer is always immediate. Include drops a cue's timed layers
 * rather than pretending to hold them, and Update leaves them untouched.
 */
data class ProgrammerLayer(
    /** In-memory identity from [ProgrammerStore.mintLayerId]. **Not** a `DaoCueLayer` id. */
    val layerId: Int,
    val lookId: Int,
    val lookUuid: UUID,
    val lookName: String,
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
    /**
     * True for the editor's live-preview layer: at most one, always last, never recorded.
     *
     * Successor to `presetPreviewStates` — see [ProgrammerLayerStack.installPreview].
     */
    val isPreview: Boolean = false,
) {
    internal fun toCookLayer() = CookLayer(
        lookId = lookId,
        lookUuid = lookUuid,
        lookName = lookName,
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
 *    `FxEngine.cueAssignmentsLock`. So a recook is always: cook → [ProgrammerStore.putLayerSlots]
 *    (lock-free) → [FxEngine.republishProgrammerKeys] (which takes the lock, and is the only step
 *    that does).
 * 2. **Lock order is `layersLock` → `cueAssignmentsLock`.** Nothing in `FxEngine` calls back into
 *    this class, so the reverse never arises today; it is stated so it stays that way.
 * 3. **One recook per mutation.** [ProgrammerStore.mutateLayers] serialises the list edit, then the
 *    cook happens outside it — two mutations racing therefore both cook, and the later publish
 *    wins. That is correct rather than merely tolerable: both cooked from a list that really
 *    existed, and the store's own `compute` makes each materialisation atomic per key.
 */
class ProgrammerLayerStack(
    private val fixtures: () -> Fixtures,
    private val lookRegistry: () -> LookRegistry,
    private val engine: () -> FxEngine,
    private val store: ProgrammerStore,
    /**
     * Supplied lazily because `Show` builds this before `State` finishes wiring itself, and only
     * effect *spawning* needs it — `resolveTargetForCue` and `createInstanceFromPreset` both take
     * it. Values cook with no `State` at all, which is what lets the value half be tested without
     * a database.
     */
    private val state: () -> State?,
) {
    /**
     * Live programmer-layer effects, keyed by the layer and the effect within it.
     *
     * Held here rather than being re-derived from the engine because the key needs the
     * `LookEffectEntry` that produced the instance, which the instance does not keep. Entries whose
     * instance has since been removed by anything else — `removeProgrammerBandEffects`, the FX
     * sheet's own remove — are dropped on the next recook rather than resurrected.
     */
    private val effectInstances = HashMap<EffectKey, Long>()

    /**
     * Guards [effectInstances] and the spawn/retract sequence.
     *
     * Separate from `ProgrammerStore`'s layer lock and taken *after* it, never around it: the cook
     * must stay outside every lock (`loadLookSnapshot` opens a transaction), so two mutations can
     * legitimately reach here concurrently and the map would otherwise be corrupted by the plain
     * `HashMap` it is. Holding this across the whole classify-spawn-retract pass is also what stops
     * two recooks each deciding to spawn the same effect.
     */
    private val effectsLock = Any()

    private data class EffectKey(val layerId: Int, val effect: LookEffectEntry, val targetKey: String)

    /**
     * The pseudo cue id a programmer cook runs under.
     *
     * [CueComposer] takes one for its log lines and stamps it on the assignments it returns, but
     * the programmer keeps only the values — the assignments never reach `cueAssignments`, so this
     * cannot collide with a real cue. Negative so it is obvious in a log line.
     */
    private val programmerCookCueId = -1

    /**
     * The unsaved draft behind the preview layer, when one is installed.
     *
     * The Look editor previews contents that have no row in the database — that is the whole point
     * of a live preview — so the registry cannot resolve them. Holding the snapshot here and passing
     * a resolver to [CueComposer.cook] keeps the preview an ordinary layer: it composes above the
     * real stack under the same blending, masking and ordering rules, rather than being a second
     * write path with its own precedence to reason about.
     */
    @Volatile
    private var previewSnapshot: LookSnapshot? = null

    /** Synthetic uuid for the preview layer. Never stored, never collides with a real Look. */
    private val previewLookUuid: UUID = UUID(0L, 0L)

    /** Resolve a layer's Look, preferring the unsaved preview draft. */
    private fun resolveLook(uuid: UUID): LookSnapshot? =
        if (uuid == previewLookUuid) previewSnapshot else lookRegistry().snapshot(uuid)

    // ── Mutations ───────────────────────────────────────────────────────────

    /** Append a layer to the top of the stack. */
    fun add(
        lookId: Int,
        lookUuid: UUID,
        lookName: String,
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
            lookId = lookId,
            lookUuid = lookUuid,
            lookName = lookName,
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
            // A preview layer is always last, so a real layer is inserted beneath it.
            val (real, preview) = current.partition { !it.isPreview }
            renumber(real + layer + preview) to Unit
        }
        return layer to recook(next, fadeMs)
    }

    /**
     * The busking pad's gesture: put this Look on these targets, or take it off again.
     *
     * "Already on" means **a non-preview layer with the same Look and the same target set** — the
     * pad's own reading, and the one that lets the same Look sit on two different target sets as two
     * independently-toggleable pads. Returns `"applied"`/`"removed"` and how many effects moved,
     * which is the contract `togglePresetOnTargets` had and the pads still read.
     *
     * Note a rows-only Look reports `0` either way: it spawns no effects. That was true before this
     * rewrite too, and it is why the pads' active ring cannot be driven from the effect list alone.
     */
    fun toggle(
        lookId: Int,
        lookUuid: UUID,
        lookName: String,
        targets: List<CueTargetDto>,
        beatDivisionOverride: Double? = null,
    ): Pair<String, Int> {
        val existing = store.layers.firstOrNull {
            !it.isPreview && it.lookId == lookId && it.targets == targets
        }
        return if (existing != null) {
            "removed" to remove(existing.layerId).effectsRetracted
        } else {
            "applied" to add(
                lookId = lookId,
                lookUuid = lookUuid,
                lookName = lookName,
                targets = targets,
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

    /** Move a layer to [toIndex] among the non-preview layers, renumbering the whole list. */
    fun move(layerId: Int, toIndex: Int): ProgrammerLayerOutcome {
        val (next, _) = store.mutateLayers { current ->
            val (real, preview) = current.partition { !it.isPreview }
            val from = real.indexOfFirst { it.layerId == layerId }
            if (from < 0) return@mutateLayers current to Unit
            val moving = real[from]
            val rest = real.toMutableList().apply { removeAt(from) }
            rest.add(toIndex.coerceIn(0, rest.size), moving)
            renumber(rest + preview) to Unit
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
     * diff later tells an edited layer from a newly added one. The **preview layer survives**: it
     * belongs to the editor sheet the operator has open, not to the cue being included.
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
            val preview = current.filter { it.isPreview }
            val installed = immediate.sortedBy { it.sortOrder }.map { layer ->
                ProgrammerLayer(
                    layerId = store.mintLayerId(),
                    lookId = layer.lookId,
                    lookUuid = layer.lookUuid,
                    lookName = layer.lookName,
                    sortOrder = 0, // renumbered below
                    enabled = layer.enabled,
                    targets = layer.targets,
                    propertyMask = layer.propertyMask,
                    blendMode = layer.blendMode,
                    amount = layer.amount,
                    stomp = layer.stomp,
                    speedMasterUuid = layer.speedMasterUuid,
                    rateSpeedMasterUuid = layer.rateSpeedMasterUuid,
                    sourceCueLayerId = layer.layerId,
                )
            }
            renumber(installed + preview) to Unit
        }
        return skipped to recook(next, fadeMs)
    }

    /**
     * Install (or replace, or clear) the single preview layer — the editor's live preview.
     *
     * Successor to `swapPresetPreviewSlot`, and it keeps that function's contract:
     *
     * - the decide-clear-install sequence is atomic, here by [ProgrammerStore.mutateLayers] rather
     *   than `ConcurrentHashMap.compute`;
     * - **an equal request is a no-op that preserves the existing layer's identity.** Not cosmetic:
     *   the editor debounces at 80 ms and its trailing tick re-sends an identical payload, so
     *   re-materialising would restart any fade in flight;
     * - a null [lookUuid] removes the entry rather than storing an empty one.
     *
     * The per-project keying is gone, and that is a strengthening rather than a loss: `Show` is
     * per project and both preview routes sit behind `withCurrentProject`, so cross-project
     * interference is now impossible by construction rather than by a map key.
     */
    fun installPreview(
        snapshot: LookSnapshot?,
        targets: List<CueTargetDto> = emptyList(),
        propertyMask: String? = null,
    ): ProgrammerLayerOutcome {
        val (next, unchanged) = store.mutateLayers { current ->
            val existing = current.firstOrNull { it.isPreview }
            if (snapshot == null || (snapshot.rows.isEmpty() && snapshot.effects.isEmpty())) {
                if (existing == null) return@mutateLayers current to true
                previewSnapshot = null
                return@mutateLayers renumber(current.filterNot { it.isPreview }) to false
            }
            if (existing != null &&
                previewSnapshot == snapshot &&
                existing.targets == targets &&
                existing.propertyMask == propertyMask
            ) {
                // Identical request: return the list containing the *same instance*, and skip the
                // recook entirely.
                return@mutateLayers current to true
            }
            previewSnapshot = snapshot
            val preview = ProgrammerLayer(
                layerId = existing?.layerId ?: store.mintLayerId(),
                lookId = snapshot.lookId,
                lookUuid = previewLookUuid,
                lookName = snapshot.name,
                sortOrder = 0,
                targets = targets,
                propertyMask = propertyMask,
                isPreview = true,
            )
            renumber(current.filterNot { it.isPreview } + preview) to false
        }
        if (unchanged) return ProgrammerLayerOutcome(0, 0, 0, 0)
        return recook(next)
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
        previewSnapshot = null
        synchronized(effectsLock) { effectInstances.clear() }
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
    fun recookIfReferences(lookUuid: UUID): Set<CueAssignmentResolver.Key> {
        val layers = store.layers
        if (layers.none { it.lookUuid == lookUuid }) return emptySet()
        return materialise(layers)
    }

    // ── The cook ────────────────────────────────────────────────────────────

    private fun recook(layers: List<ProgrammerLayer>, fadeMs: Long = 0): ProgrammerLayerOutcome {
        val moved = materialise(layers)
        val fx = syncEffects(layers)
        if (moved.isNotEmpty()) engine().republishProgrammerKeys(moved, fadeMs)
        return ProgrammerLayerOutcome(moved.size, fx.spawned, fx.retracted, fx.repriorised)
    }

    /** Cook the stack to values and swap them into the store. No publish. */
    private fun materialise(layers: List<ProgrammerLayer>): Set<CueAssignmentResolver.Key> {
        val cookLayers = layers.map { it.toCookLayer() }
        val rows = CueComposer.cook(
            fixtures = fixtures(),
            cueId = programmerCookCueId,
            priority = 0,
            layers = cookLayers,
            // The programmer's *local* layer is the store's other owners — WEB, SURFACE, LOCATE and
            // the rest — which already sit above these slots. Passing them here as well would cook
            // them into the layer contribution and lose that distinction: Record could no longer
            // tell the operator's own edits from the stack's output.
            localRows = emptyList(),
            lookRegistry = lookRegistry(),
            resolveLook = ::resolveLook,
        )
        return store.putLayerSlots(
            rows.map { row ->
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
    private fun syncEffects(layers: List<ProgrammerLayer>): EffectSync = synchronized(effectsLock) {
        val eng = engine()
        val liveIds = eng.getActiveEffects().mapTo(HashSet()) { it.id }
        // Anything removed behind our back (the FX sheet's own remove, a band sweep) is forgotten
        // rather than treated as live and never respawned.
        effectInstances.values.retainAll { it in liveIds }

        val cookLayers = layers.map { it.toCookLayer() }
        val desired = CueComposer.cookEffects(
            fixtures(), programmerCookCueId, cookLayers, lookRegistry(), resolveLook = ::resolveLook,
        )

        // Rank each layer among the ones that contribute, matching CookWinner.index.
        val rankOf = cookLayers
            .filter { it.enabled && it.amount > 0.0 }
            .sortedBy { it.sortOrder }
            .withIndex()
            .associate { (index, layer) -> layer.layerId to index }

        val byLayerId = layers.associateBy { it.layerId }
        var spawned = 0
        val wanted = HashSet<EffectKey>(desired.size)
        val repriorities = HashMap<Long, Int>()

        for ((layer, effect, target) in desired) {
            val key = EffectKey(layer.layerId, effect, target.key)
            // A Look holding the same effect twice on one target would collide here; the second is
            // dropped rather than spawning an untracked instance nothing can ever retract.
            if (!wanted.add(key)) continue
            val priority = priorityFor(rankOf[layer.layerId] ?: 0)
            val existing = effectInstances[key]
            if (existing != null) {
                repriorities[existing] = priority
                continue
            }
            val palette = resolveLook(layer.lookUuid)?.palette ?: emptyList()
            val override = byLayerId[layer.layerId]?.beatDivisionOverride
            val id = spawn(layer, effect, target, priority, palette, override) ?: continue
            effectInstances[key] = id
            spawned++
        }

        var retracted = 0
        val gone = effectInstances.keys.filterNot { it in wanted }
        for (key in gone) {
            effectInstances.remove(key)?.let { if (eng.removeEffect(it)) retracted++ }
        }

        val repriorised = eng.repriorityProgrammerLayerEffects(repriorities)
        EffectSync(spawned, retracted, repriorised)
    }

    /**
     * The programmer band, offset by layer rank.
     *
     * `sortedEffectsComparator` is `compareBy(priority, id)` with `id` a monotonic creation
     * counter, so without the offset relative order would be *spawn* order and a reorder would need
     * a respawn to express itself. Every consumer of the band tests
     * `isProgrammerFxPriority` (`>= BASE`) rather than exact equality, so an offset is invisible to
     * all of them. The band is a million wide; the clamp is a backstop, not an expected case.
     */
    private fun priorityFor(rank: Int): Int =
        FxEngine.PROGRAMMER_FX_PRIORITY_BASE + rank.coerceIn(0, 100_000)

    private fun spawn(
        layer: CookLayer,
        effect: LookEffectEntry,
        target: TargetRef,
        priority: Int,
        lookPalette: List<String>,
        beatDivisionOverride: Double?,
    ): Long? {
        val st = state() ?: return null
        val spec = effect.toEffectSpec()
            .let { if (beatDivisionOverride == null) it else it.copy(beatDivision = beatDivisionOverride) }
        val fxTarget = try {
            resolveTargetForCue(st, TogglePresetTarget(target), spec)
        } catch (_: Exception) {
            null
        } ?: return null

        // Snapshot suppliers, as Include uses: a programmer layer is not a live cue, so the
        // cue-scoped palette supplier has nothing to resolve against. The Look's own colour list is
        // the most specific scope and falls back to the global one.
        val snapshot = lookPalette.takeIf { it.isNotEmpty() }?.toPaletteColours()
            ?: engine().getPalette()
        val instance = try {
            createInstanceFromPreset(
                spec, fxTarget, presetId = null, state = st,
                paletteSupplier = { snapshot },
                paletteVersionSupplier = { 0L },
                overrideSpeedMasterUuid = layer.speedMasterUuid,
                overrideRateSpeedMasterUuid = layer.rateSpeedMasterUuid,
            )
        } catch (e: Exception) {
            logger.warn(
                "programmer layer '{}': effect '{}' could not be created — {}",
                layer.lookName, effect.effectType, e.message,
            )
            return null
        }
        // `presetId` stays null on purpose. The toggle route used to pass the *Look* id there,
        // which made `captureCurrentState` reconstruct a preset application naming whatever
        // `DaoFxPreset` shared the number. `lookId` and `programmerLayerId` are the honest fields.
        instance.lookId = layer.lookId
        instance.programmerLayerId = layer.layerId
        instance.priority = priority
        return engine().addEffect(instance)
    }

    private fun renumber(layers: List<ProgrammerLayer>): List<ProgrammerLayer> =
        layers.mapIndexed { index, layer ->
            if (layer.sortOrder == index) layer else layer.copy(sortOrder = index)
        }
}
