package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture

/**
 * Resolves the "layer below" [FxOutput] for a given target at effect-reset time.
 *
 * Cascade (see `docs/lighting-composition-model.md` §"Overview"):
 *
 * 1. **PROGRAMMER** — sticky manual property entries (and the raw-channel sideband) held
 *    in [ProgrammerStore], unless the blind gate is engaged. Sits above cue assignments —
 *    a manual value wins over the playback state, console-style.
 * 2. **Cue property assignments** — composed by [CueAssignmentResolver] from active cues.
 * 3. **Baseline** — fixture defaults (0 for sliders, black for colour, 128 centered for
 *    pan/tilt, 0 for settings).
 *
 * (Effects sit *between* the programmer and cue assignments: the tick loop resets to this
 * cascade, applies effects on top, and [FxEngine]'s suppression pass skips any non-
 * programmer-band effect on a property with an active programmer entry — so the programmer
 * wins over effects too.)
 *
 * This object is the single read site for the cascade during a tick's effect-reset pass. Its
 * reads are allocation-free and lock-free: the cue layer is a volatile [Map] reference
 * swapped on cue apply, and the programmer layer is a pair of concurrent maps whose winning
 * slot is read without allocating (see [ProgrammerStore]).
 *
 * Layer 1 (parking) is handled separately — the caller should consult [ParkManager.isParked]
 * *before* calling [fallbackFor] and skip the property entirely for parked channels.
 */
class LayerResolver(
    private val cueAssignmentResolver: CueAssignmentResolver,
    private val programmer: ProgrammerStore,
) {
    /**
     * The Layer 4 maps, published as ONE volatile reference. They must never be
     * separate fields: readers are lock-free and consume them as a tuple — the tick loops,
     * provenance ([FxEngine.computeProvenance]), and the Update checklist
     * ([FxEngine.underlyingSources]) — and a read straddling an [applyAssignments] swap of
     * separate fields could find a key in the new [state] with no entry in the old
     * [winners]: "a cue owns this" naming no cue, or a checklist pairing one cue's value
     * with another's attribution.
     */
    class CueLayerSnapshot internal constructor(
        /**
         * Layer 4 composition output, keyed fixtureKey → propertyName → value — the shape
         * [CueAssignmentResolver.resolveIndexed] builds. The hot-path index: it lets the
         * per-tick reset path look up a Layer 4 contribution without allocating a compound
         * `CueAssignmentResolver.Key` per call, and is the map the publish-time diff walks.
         */
        val index: Map<String, Map<String, CueAssignmentResolver.PropertyValue>>,
        /**
         * Winning contributor per composed key — provenance only, never on the hot path.
         * Approximation: the highest-(priority, fadeWeight) contributor, which matches the
         * LTP winner exactly and names the dominant contributor under HTP/crossfade blends.
         */
        val winners: Map<CueAssignmentResolver.Key, Int>,
        /**
         * The winning *layer* per composed key, for the keys a Look layer produced. Same
         * selection as [winners] — it is the same winning assignment, read for a different
         * field — so the two can never disagree about which contributor won. Keys whose
         * winner was a cue's local row are absent rather than null-valued: absence already
         * means "no layer", and a null value would make "not attributable" and "attributable
         * to nothing" two spellings of one thing.
         */
        val layerWinners: Map<CueAssignmentResolver.Key, CookWinner>,
    ) {
        /**
         * [index] flattened to (targetKey, propertyName) keys, derived on first read.
         * Deliberately lazy: crossfade frames rebuild this snapshot at ~62 fps and nothing on
         * that path reads the flat form — only provenance (coalesced), the Update checklist
         * and preview/diagnostic sites do — so building it eagerly per publish duplicated the
         * whole map for nobody (sweep item C3).
         */
        val state: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue> by lazy {
            CueAssignmentResolver.flattenIndexed(index)
        }

        internal companion object {
            val EMPTY = CueLayerSnapshot(emptyMap(), emptyMap(), emptyMap())
        }
    }

    @Volatile
    private var cueLayer: CueLayerSnapshot = CueLayerSnapshot.EMPTY

    /** Replace the Layer 4 state from the current set of assignments. Called on cue apply. */
    fun applyAssignments(assignments: List<CueAssignmentResolver.Assignment>) {
        if (assignments.isEmpty()) {
            cueLayer = CueLayerSnapshot.EMPTY
            return
        }
        val winners = selectWinners(assignments)
        publish(
            assignments,
            winners = winners.mapValues { (_, a) -> a.cueId },
            layerWinners = winners.mapNotNull { (key, a) -> a.layerWinner?.let { key to it } }.toMap(),
        )
    }

    /**
     * Recompose the Layer 4 values from [assignments], carrying the previous snapshot's
     * winner maps forward — the crossfade weight-tick path (~62 fps), where the assignment
     * *set* is unchanged since the last [applyAssignments] and only per-cue weights moved.
     *
     * Only valid under that invariant, which [FxEngine] guarantees by holding its
     * `cueAssignmentsLock` across every mutation + republish pair: any change to the row set
     * republishes through [applyAssignments] first. Reuse is exact for the composed key set
     * (it derives from the rows alone, not their weights); the winner *attribution* stays
     * pinned at the weights of the last full publish rather than flipping mid-fade on a
     * (priority, fadeWeight) tie — within the "dominant contributor" approximation
     * [CueLayerSnapshot.winners] already documents, and recomputed at end-of-fade when the
     * outgoing cue's removal republishes fully.
     */
    fun reweightAssignments(assignments: List<CueAssignmentResolver.Assignment>) {
        if (assignments.isEmpty()) {
            cueLayer = CueLayerSnapshot.EMPTY
            return
        }
        val previous = cueLayer
        publish(assignments, previous.winners, previous.layerWinners)
    }

    /**
     * The one place a non-empty snapshot is composed and swapped in, so a future
     * [CueLayerSnapshot] field can't be threaded through [applyAssignments] and silently
     * missed on the ~62 fps [reweightAssignments] path.
     */
    private fun publish(
        assignments: List<CueAssignmentResolver.Assignment>,
        winners: Map<CueAssignmentResolver.Key, Int>,
        layerWinners: Map<CueAssignmentResolver.Key, CookWinner>,
    ) {
        cueLayer = CueLayerSnapshot(
            index = cueAssignmentResolver.resolveIndexed(assignments),
            winners = winners,
            layerWinners = layerWinners,
        )
    }

    private fun selectWinners(
        assignments: List<CueAssignmentResolver.Assignment>,
    ): Map<CueAssignmentResolver.Key, CueAssignmentResolver.Assignment> {
        if (assignments.isEmpty()) return emptyMap()
        val winners = HashMap<CueAssignmentResolver.Key, CueAssignmentResolver.Assignment>()
        for (a in assignments) {
            val key = CueAssignmentResolver.Key.fixture(a.targetKey, a.propertyName)
            val current = winners[key]
            // Final tiebreak on cueId keeps the attribution deterministic on an exact
            // (priority, fadeWeight) tie — the flat assignment list comes from HashMap
            // iteration, whose order can shift across republishes.
            if (current == null ||
                a.priority > current.priority ||
                (a.priority == current.priority && (
                    a.fadeWeight > current.fadeWeight ||
                    (a.fadeWeight == current.fadeWeight && a.cueId > current.cueId)
                ))
            ) {
                winners[key] = a
            }
        }
        return winners
    }

    /** Clear the Layer 4 state — equivalent to "no cue contributing". */
    fun clearAssignments() {
        cueLayer = CueLayerSnapshot.EMPTY
    }

    /**
     * The current snapshot, as one reference. A caller reading more than one of the maps —
     * or pairing them with [FxEngine.underlyingSources] — must take this once rather than
     * using the single-map accessors below, or its reads can straddle a swap.
     */
    val current: CueLayerSnapshot
        get() = cueLayer

    /** Current composition output; exposed for tests and diagnostics. */
    val currentCueLayerState: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>
        get() = cueLayer.state

    /** Winning contributor cueId per composed key — provenance / diagnostics. */
    val currentCueLayerWinners: Map<CueAssignmentResolver.Key, Int>
        get() = cueLayer.winners

    /**
     * Winning **layer** per composed key, for keys a Look layer produced — provenance.
     *
     * A key is absent when a cue's local row won it. See [CookWinner].
     */
    val currentCueLayerLayerWinners: Map<CueAssignmentResolver.Key, CookWinner>
        get() = cueLayer.layerWinners

    /**
     * Resolve the fallback [FxOutput] for the given target + fixture. Returned value is what
     * the effect's reset-to-neutral should write before the effect's own contribution blends
     * over the top.
     */
    fun fallbackFor(target: FxTarget, fixture: GroupableFixture, fixtureKey: String): FxOutput {
        // Resolve the cue layer (or baseline) first — the programmer overlays it, and a
        // partially-covering programmer contribution (one sideband channel of a colour)
        // needs the below value for the components it doesn't own.
        val below = run {
            val idx = cueLayer.index
            val cue = if (idx.isNotEmpty()) idx[fixtureKey]?.get(target.propertyName) else null
            cue?.asFxOutputFor(target) ?: target.baselineFallback(fixture)
        }
        if (programmer.blind) return below
        // Per-fixture O(1) gate: composeProgrammerOver's colour path does reflective
        // bundled-slider scans, so a busk on one fixture must not tax every other fixture
        // under a running effect each tick. Any sideband slot disables the gate — sideband
        // coverage is channel-shaped and can't be attributed to a fixture without
        // resolving the patch.
        if (!programmer.coversFixture(fixtureKey) && !programmer.hasSidebandEntries) return below
        return target.composeProgrammerOver(fixture, programmer, below)
    }

    private fun CueAssignmentResolver.PropertyValue.asFxOutputFor(target: FxTarget): FxOutput? = when (this) {
        is CueAssignmentResolver.PropertyValue.Slider ->
            if (target is SliderTarget) FxOutput.Slider(value) else null
        is CueAssignmentResolver.PropertyValue.Colour ->
            if (target is ColourTarget) FxOutput.Colour(value) else null
        is CueAssignmentResolver.PropertyValue.Position ->
            if (target is PositionTarget) FxOutput.Position(pan, tilt) else null
        is CueAssignmentResolver.PropertyValue.Setting ->
            if (target is SettingTarget) FxOutput.Slider(channelValue) else null
    }
}
