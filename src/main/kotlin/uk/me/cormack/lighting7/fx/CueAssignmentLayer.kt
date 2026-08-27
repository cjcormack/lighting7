package uk.me.cormack.lighting7.fx

/**
 * Per-cue Layer 4 assignment bookkeeping, extracted from [FxEngine] (sweep item E1).
 *
 * Tracks the property assignments contributed by each currently-active cue. All writes go
 * through the publish lock ([CascadePublisher.locked]) so the "mutate map + republish flat
 * snapshot" step is atomic — concurrent apply/stop calls must not publish a stale view.
 * Tick-loop reads go through [LayerResolver.fallbackFor]'s `@Volatile` snapshot and stay
 * lock-free.
 *
 * The maps are plain [HashMap] because every access is already serialised by the lock; a
 * [java.util.concurrent.ConcurrentHashMap] would add internal striping we don't need.
 *
 * This class also holds the flattened within-cue **stomp suppression** the tick loops read
 * ([isLayerStomped]) — both the cue stacks' half and the programmer stack's half, because the
 * two are one signal to the tick loops even though their layer-id spaces are unrelated (see
 * [FxInstance.cueLayerId]).
 */
class CueAssignmentLayer internal constructor(
    private val layerResolver: LayerResolver,
    private val publisher: CascadePublisher,
    /** Provenance hook — Layer 4 moves change provenance winners. */
    private val onLayerChanged: () -> Unit,
) {
    private val cueAssignments = HashMap<Int, List<CueAssignmentResolver.Assignment>>()

    // Per-cue crossfade weight in [0, 1]. Absent entries default to 1.0 (fully in), and an
    // entry reaching 1.0 is removed rather than stored. Handed to the resolver as a
    // [CueAssignmentResolver.FadeWeights] lookup, which multiplies it into each row's own
    // `fadeWeight` at compose time — it used to be folded into row copies while building the
    // flat list, which re-cooked the whole set every frame (sweep item C6). Kept separate from
    // `cueAssignments` so the stored assignment list stays constant across a crossfade; only
    // the scalar per-cue weight ticks. See [updateFadeWeights] / Phase 1b in
    // `docs/plans/completed/cue-authoring-unification-plan.md`.
    private val cueFadeWeights = HashMap<Int, Double>()

    // cueId → the stack that cue belongs to, recorded when its assignments are published.
    //
    // The Layer 4 machinery is keyed by cue alone (`cueAssignments`, and the resolver's
    // `currentCueLayerWinners: Key → cueId`), so a CUE-sourced provenance entry had nowhere to
    // read a stack from and always reported null — the wire-format asymmetry against EFFECT
    // sources that `FU-PROG-PROVENANCE-STACKID` tracked. Every caller of [setAssignments]
    // holds a stack id, so recording it here costs one map write per publish and lets both
    // provenance and `underlyingSources` answer "which stack" without touching the DB.
    // Guarded by the publish lock, like the two maps above.
    private val cueStackIds = HashMap<Int, Int>()

    // cueId → that cue's within-cue stomp suppression, straight off `CookResult`. Held per cue so
    // the cue's own lifecycle clears it: a cue that stops, or is republished with a layer's stomp
    // switched off, replaces its whole entry in the same locked mutation as its rows. Guarded by
    // the publish lock.
    private val cueStompSuppression = HashMap<Int, LayerStompSuppression>()

    // The flattened `cueLayerId → targetKey → properties` the tick loops read, rebuilt whenever
    // [cueStompSuppression] changes. Flat because `DaoCueLayer` ids are unique across every cue, so
    // no cue id is needed to disambiguate — and the hot path must not walk one map per live cue.
    @Volatile private var cueStompFlat: LayerStompSuppression = emptyMap()

    // The programmer stack's half of the same signal, keyed by `ProgrammerLayer.layerId`. A separate
    // field rather than merged into [cueStompFlat] because the two id spaces are unrelated — see
    // [FxInstance.cueLayerId].
    @Volatile private var programmerStompFlat: LayerStompSuppression = emptyMap()

    /**
     * Replace [cueId]'s Layer 4 assignments. Empty [assignments] removes the cue entirely
     * (equivalent to [removeAssignments]). The optional [weight] sets the cue's crossfade
     * weight atomically in the same publish — used by the crossfade-start path to pin the
     * incoming cue at 0 without briefly flashing its full value onto stage. A weight of 1.0
     * (the default) clears any prior entry in the fade-weight map so reapplying a cue resets
     * it to steady state. Clamped to `[0, 1]`.
     *
     * [cueStackId] records which stack the cue belongs to so CUE-sourced provenance and
     * `underlyingSources` can name it. Defaulted to null rather than required: plenty of
     * engine-level tests publish assignments for a bare cue id with no stack behind them, and
     * a null simply means "stack unknown", which is what those callers mean.
     */
    fun setAssignments(
        cueId: Int,
        assignments: List<CueAssignmentResolver.Assignment>,
        weight: Double = 1.0,
        cueStackId: Int? = null,
        /**
         * [CookResult.stompSuppression] for this publish. Passed alongside the rows rather than set
         * through a call of its own so the two cannot disagree: they are one cook's output, and a
         * publish that refreshed the rows while leaving stale suppression behind would keep a
         * layer's effects switched off after its stomper was disabled.
         *
         * Defaulted to empty, which is also what every non-layer caller means — a hand-built row
         * list belongs to no layer, so nothing can stomp it.
         */
        stompSuppression: LayerStompSuppression = emptyMap(),
        /**
         * True only when this publish is the cue **arriving** — a GO, or an immediate apply. False
         * for the cue-edit and Record/Update rewrites of an already-live cue that come through
         * `republishCueLayer`, which are edits rather than entrances. See
         * [republishAssignments].
         */
        honourRowFades: Boolean = false,
    ) {
        publisher.locked {
            if (assignments.isEmpty()) {
                val removed = cueAssignments.remove(cueId) != null
                cueFadeWeights.remove(cueId)
                cueStackIds.remove(cueId)
                if (cueStompSuppression.remove(cueId) != null) rebuildStompFlatLocked()
                if (removed) republishAssignments()
                return@locked
            }
            cueAssignments[cueId] = assignments
            if (cueStackId != null) cueStackIds[cueId] = cueStackId
            setCueStompLocked(cueId, stompSuppression)
            val clamped = weight.coerceIn(0.0, 1.0)
            if (clamped >= 1.0) {
                cueFadeWeights.remove(cueId)
            } else {
                cueFadeWeights[cueId] = clamped
            }
            republishAssignments(honourRowFades = honourRowFades)
        }
    }

    /**
     * Replace several live cues' Layer 4 rows in one locked mutation with a single republish —
     * the [FxEngine.repriorityCues] shape, for callers that rebuilt rows rather than
     * re-prioritised them. Returns the cues actually replaced — a subset of [updates]' keys, so
     * a caller reporting "these cues moved" must report this set, not what it attempted.
     *
     * **Crossfade weights are deliberately left alone**, and that is the whole reason this exists
     * rather than a loop over [setAssignments]. That function's `weight` defaults to 1.0 and
     * *clears* the cue's [cueFadeWeights] entry, so using it here would snap any in-flight
     * crossfade on an affected cue to fully-in. A Look edit touches every cue that layers it at
     * once, which makes that a likely accident rather than a theoretical one.
     *
     * Cues absent from [cueAssignments] are skipped: a cue that stopped being live between the
     * caller's scan and this call has nothing to republish.
     */
    fun replaceAssignments(
        updates: Map<Int, List<CueAssignmentResolver.Assignment>>,
        /**
         * `cueId → CookResult.stompSuppression`, for the same reason [setAssignments] takes one.
         * A cue present in [updates] but absent here has its suppression **cleared**, not left
         * alone: the caller re-cooked that cue, so an absent entry means the new cook found no
         * stomping layer.
         */
        stompSuppression: Map<Int, LayerStompSuppression> = emptyMap(),
        /**
         * True only when this publish is a source **arriving** — `CueTriggerManager` firing a timed
         * layer, whose rows appear on stage for the first time at that moment. False for
         * `republishForLookEdit`, which tours every live cue layering an edited Look and would
         * otherwise re-ramp the stage once per drag of a colour picker. See
         * [republishAssignments].
         */
        honourRowFades: Boolean = false,
    ): Set<Int> {
        if (updates.isEmpty()) return emptySet()
        val replaced = LinkedHashSet<Int>()
        publisher.locked {
            var stompChanged = false
            for ((cueId, rows) in updates) {
                if (cueId !in cueAssignments) continue
                if (rows.isEmpty()) {
                    cueAssignments.remove(cueId)
                    // Weight and stack id intentionally survive removal here too: the cue is still
                    // mid-fade as far as CueStackManager is concerned, and it owns that lifecycle.
                    if (cueStompSuppression.remove(cueId) != null) stompChanged = true
                } else {
                    cueAssignments[cueId] = rows
                    val stomp = stompSuppression[cueId] ?: emptyMap()
                    if (setCueStompEntryLocked(cueId, stomp)) stompChanged = true
                }
                replaced.add(cueId)
            }
            if (stompChanged) rebuildStompFlatLocked()
            if (replaced.isNotEmpty()) republishAssignments(honourRowFades = honourRowFades)
        }
        return replaced
    }

    /**
     * Update the crossfade weight for one or more cues atomically. Only cues present in
     * [cueAssignments] have an effect — unknown cue ids are ignored (silent no-op) because a
     * crossfade tick may fire during the tiny window between an outgoing cue's end-of-fade
     * [removeAssignments] and the next tick being cancelled.
     *
     * A single republish runs per call regardless of how many cues are updated, so crossfade
     * ticks that update both outgoing and incoming cues pay one publish pass per frame.
     *
     * Weights are clamped to `[0, 1]`. Setting a weight of exactly 1.0 (the default) clears
     * the entry — no need to accumulate stale entries once the crossfade is over.
     */
    fun updateFadeWeights(updates: Map<Int, Double>) {
        if (updates.isEmpty()) return
        publisher.locked {
            var changed = false
            var anyCompleted = false
            for ((cueId, rawWeight) in updates) {
                if (cueId !in cueAssignments) continue
                val weight = rawWeight.coerceIn(0.0, 1.0)
                val previous = cueFadeWeights[cueId] ?: 1.0
                if (previous == weight) continue
                if (weight >= 1.0) {
                    cueFadeWeights.remove(cueId)
                    anyCompleted = true
                } else {
                    cueFadeWeights[cueId] = weight
                }
                changed = true
            }
            // A weight reaching 1.0 ends that cue's fade, and it may be the *last* Layer 4
            // publish of the crossfade: the outgoing cue's removal is a silent no-op when it
            // contributed no rows (an effects-only cue), so nothing downstream is guaranteed
            // to re-resolve the winner maps a weight-only republish carries forward. Resolve
            // them here — once per fade end, not per frame.
            if (changed) republishAssignments(weightsOnly = !anyCompleted)
        }
    }

    /** Drop all Layer 4 contributions from [cueId]. */
    fun removeAssignments(cueId: Int) {
        publisher.locked {
            val removed = cueAssignments.remove(cueId) != null
            cueFadeWeights.remove(cueId)
            cueStackIds.remove(cueId)
            if (cueStompSuppression.remove(cueId) != null) rebuildStompFlatLocked()
            if (removed) {
                republishAssignments()
            }
        }
    }

    /**
     * Drop [cueId]'s within-cue stomp suppression without touching its Layer 4 rows or fade
     * weight.
     *
     * A timed layer's fire can leave a `cueStompSuppression` entry live after the cue's *effects*
     * are gone — [CueTriggerManager.deactivateTriggersForCue] cancels the firing jobs but has no
     * other reason to touch the engine, and every call site that also calls [removeAssignments]
     * for the same cue already clears the entry as a side effect of that call. This exists for the
     * cue-trigger side to close that gap itself rather than depending on every current and future
     * caller to remember the pairing. Safe to call even while the cue's rows are still fading out
     * in a crossfade: [isLayerStomped] only suppresses *live effects* tagged with this cue's layer
     * ids, and those are already removed (via [FxEngine.removeEffectsForCueStack]) before a
     * crossfade starts — the suppression entry has nothing left to affect by the time this runs.
     */
    fun clearStompSuppression(cueId: Int) {
        publisher.locked {
            if (cueStompSuppression.remove(cueId) != null) rebuildStompFlatLocked()
        }
    }

    /** Drop every cue's Layer 4 contribution — used by [FxEngine.stop] / [FxEngine.clearAllEffects]. */
    fun clearAll() {
        publisher.locked {
            if (cueStompSuppression.isNotEmpty()) {
                cueStompSuppression.clear()
                rebuildStompFlatLocked()
            }
            // Swept even when there is nothing left to republish: [replaceAssignments]
            // deliberately leaves a mid-fade cue's stack id (and weight) behind when its rows
            // empty, and a weight already at 1.0 has no map entry — so the early return below
            // could otherwise strand a stale [cueStackIds] entry forever.
            cueStackIds.clear()
            if (cueAssignments.isEmpty() && cueFadeWeights.isEmpty()) return@locked
            cueAssignments.clear()
            cueFadeWeights.clear()
            republishAssignments()
        }
    }

    // ─── Within-cue stomp suppression ───────────────────────────────────

    /** Store [cueId]'s entry and refresh the flat snapshot if it moved. */
    private fun setCueStompLocked(cueId: Int, suppression: LayerStompSuppression) {
        if (setCueStompEntryLocked(cueId, suppression)) rebuildStompFlatLocked()
    }

    /** Store [cueId]'s entry without refreshing. Returns whether anything changed. */
    private fun setCueStompEntryLocked(cueId: Int, suppression: LayerStompSuppression): Boolean =
        if (suppression.isEmpty()) {
            cueStompSuppression.remove(cueId) != null
        } else {
            cueStompSuppression.put(cueId, suppression) != suppression
        }

    /**
     * Flatten every live cue's per-layer suppression into the one map the tick loops read.
     *
     * Safe to flatten because `DaoCueLayer` ids are unique across cues — two cues can never claim
     * the same layer id, so no key collides and no cue id is needed to disambiguate.
     */
    private fun rebuildStompFlatLocked() {
        cueStompFlat = when {
            cueStompSuppression.isEmpty() -> emptyMap()
            cueStompSuppression.size == 1 -> cueStompSuppression.values.first()
            else -> HashMap<Int, Map<String, Set<String>>>().apply {
                for (perCue in cueStompSuppression.values) putAll(perCue)
            }
        }
    }

    /**
     * Publish the programmer stack's within-cue stomp suppression, replacing whatever it held.
     *
     * Called on every recook of the stack — so, unlike the cue path, there is no removal call:
     * an empty map *is* "nothing stomps any more".
     */
    fun setProgrammerStompSuppression(suppression: LayerStompSuppression) {
        programmerStompFlat = suppression
    }

    /**
     * Test seam for the programmer half, which has no behavioural read short of pumping a tick.
     * The cue half is asserted end-to-end in `FxEnginePipelineTest` instead.
     */
    internal fun programmerStompSuppressionForTest(): LayerStompSuppression = programmerStompFlat

    /**
     * Is [effect] switched off on this key by a stomping layer above it in its own stack?
     *
     * Shared by the tick loops' suppression check and by provenance's winner scan, so what is
     * *painting* and what provenance *reports* cannot disagree. Without that sharing, a stomped
     * effect would still be named the winner, and "why is this fixture this colour?" would answer
     * with an effect nobody can see.
     *
     * Gated on both snapshots being empty first, which is the overwhelmingly common case — a
     * stomping layer is an escape hatch, not everyday authoring — so the usual tick pays two
     * volatile reads and no map lookups.
     */
    fun isLayerStomped(effect: FxInstance, fixtureKey: String, propertyName: String): Boolean {
        if (cueStompFlat.isEmpty() && programmerStompFlat.isEmpty()) return false
        val cueLayerId = effect.cueLayerId
        val programmerLayerId = effect.programmerLayerId
        val layerStomp = when {
            cueLayerId != null -> cueStompFlat[cueLayerId]
            programmerLayerId != null -> programmerStompFlat[programmerLayerId]
            else -> return false
        }
        return layerStomp?.get(fixtureKey)?.contains(propertyName) == true
    }

    /** Which stack [cueId]'s currently-published assignments belong to, if it named one. */
    fun cueStackIdFor(cueId: Int): Int? = publisher.locked { cueStackIds[cueId] }

    /**
     * Snapshot the set of cue ids currently contributing Layer 4 assignments. Used by
     * `snapshot-from-live` to read each active cue's pre-expansion DB rows and preserve the
     * group-scoped shape in the captured state.
     */
    fun activeCueIds(): Set<Int> = publisher.locked {
        cueAssignments.keys.toSet()
    }

    /**
     * The published Layer 4 rows of every cue *except* those belonging to [stackId] — what a GO
     * on that stack would leave alone, since firing a cue replaces its own stack's contribution
     * and nothing else. Cues published without a stack (a cue-edit live apply) survive a stack
     * GO, so they are included.
     *
     * Used by the cue-preview compose (`routes/cuePreview.kt`) to recompose a hypothetical cue
     * set against what is already live without touching [layerResolver]'s state. Filtered in
     * here rather than by the caller so the rows and the `cueId → stackId` map are read under
     * one publish-lock acquisition and cannot disagree.
     *
     * Rows carry their stored `fadeWeight` (always 1.0 — live crossfade progress lives in
     * `cueFadeWeights` and is applied at republish time), so the result describes the settled
     * look rather than a cue caught mid-crossfade.
     */
    fun assignmentsExcludingStack(stackId: Int): List<CueAssignmentResolver.Assignment> =
        publisher.locked {
            cueAssignments.entries
                .filter { cueStackIds[it.key] != stackId }
                .flatMap { it.value }
        }

    /**
     * The Layer 4 half of [FxEngine.repriorityCues]: rewrite the composition priority of live
     * rows owned by the cues in [priorities] (`cueId → new priority`). `Assignment.priority`
     * is a val, so the rows are rebuilt by copy. Leaves [cueFadeWeights] alone so a repriority
     * mid-crossfade doesn't disturb the fade. Returns the number of rows changed.
     */
    fun repriorityAssignments(priorities: Map<Int, Int>): Int {
        var changed = 0
        publisher.locked {
            var cueLayerChanged = false
            for ((cueId, target) in priorities) {
                val rows = cueAssignments[cueId] ?: continue
                if (rows.all { it.priority == target }) continue
                cueAssignments[cueId] = rows.map {
                    if (it.priority == target) it else it.copy(priority = target)
                }
                changed += rows.count { it.priority != target }
                cueLayerChanged = true
            }
            if (cueLayerChanged) republishAssignments()
        }
        return changed
    }

    /**
     * Callers hold the publish lock. [weightsOnly] marks a crossfade weight tick: the
     * assignment set is unchanged since the last full republish, so neither the flat row list
     * nor the resolver's cook nor the winner maps are rebuilt — see
     * [LayerResolver.reweightAssignments]. Every mutation of [cueAssignments] republishes
     * with `weightsOnly = false`, under the same lock, which is what keeps that reuse valid.
     *
     * The per-cue weights ride alongside the rows as a [CueAssignmentResolver.FadeWeights]
     * lookup rather than being folded into copies of them. Copying was what forced a fresh
     * cook per frame: new row objects are a new row *set* as far as the resolver can tell.
     * [cueFadeWeights] is handed over directly and read synchronously under the lock the
     * caller already holds.
     *
     * @param honourRowFades whether the winning rows' own `fadeDurationMs` may ramp this publish.
     *   Defaults to false, and only a caller that knows this publish is a **source arriving** —
     *   a cue GO, a timed layer firing, an operator putting a Look on a pad — may pass true. Every
     *   other republish snaps, each for its own reason: a crossfade weight tick runs at ~62 fps and
     *   a ramp restarted every frame never arrives; a Look-content edit tours every live cue that
     *   layers it, so an operator dragging a colour picker would set a 2 s crossfade running per
     *   drag step; a Record/Update rewrite of a live cue is an edit, not an entrance; and a removal
     *   or a reveal releases keys to whatever sits underneath, which the row that stopped
     *   contributing has no business timing. `ProgrammerLayerStack` splits the same way, on the same
     *   arrival-versus-edit line.
     */
    private fun republishAssignments(weightsOnly: Boolean = false, honourRowFades: Boolean = false) {
        val before = layerResolver.current
        val weights = CueAssignmentResolver.FadeWeights(cueFadeWeights)
        if (cueAssignments.isEmpty()) {
            layerResolver.applyAssignments(emptyList())
        } else if (weightsOnly) {
            layerResolver.reweightAssignments(weights)
        } else {
            val flat = ArrayList<CueAssignmentResolver.Assignment>()
            for (list in cueAssignments.values) flat.addAll(list)
            layerResolver.applyAssignments(flat, weights)
        }
        // `&& !weightsOnly` belongs here rather than to the callers: a weight tick is a republish
        // no caller describes as an arrival, and the guarantee that the two fade mechanisms never
        // compound on one key should not rest on every call site remembering it.
        publisher.publishCueLayerToControllers(before, layerResolver.current, honourRowFades && !weightsOnly)
        onLayerChanged()
    }
}
