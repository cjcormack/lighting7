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
     * Current Layer 4 composition output, indexed by (targetKey, propertyName). Rebuilt when
     * cues change active state (Phase 1 wires this; Phase 0 keeps it empty).
     */
    @Volatile
    private var cueLayerState: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue> = emptyMap()

    // Hot-path index keyed by fixtureKey → propertyName → value. Lets the per-tick reset path
    // look up a Layer 4 contribution without allocating a compound
    // `CueAssignmentResolver.Key` per call. Rebuilt atomically alongside `cueLayerState`
    // in [applyAssignments].
    @Volatile
    private var cueLayerIndex: Map<String, Map<String, CueAssignmentResolver.PropertyValue>> = emptyMap()

    // Winning contributor per composed key — provenance only, never on the hot path.
    // Approximation: the highest-(priority, fadeWeight) contributor, which matches the LTP
    // winner exactly and names the dominant contributor under HTP/crossfade blends.
    @Volatile
    private var cueLayerWinners: Map<CueAssignmentResolver.Key, Int> = emptyMap()

    /** Replace the Layer 4 state from the current set of assignments. Called on cue apply. */
    fun applyAssignments(assignments: List<CueAssignmentResolver.Assignment>) {
        val composed = if (assignments.isEmpty()) emptyMap() else cueAssignmentResolver.resolve(assignments)
        cueLayerState = composed
        cueLayerIndex = buildIndex(composed)
        cueLayerWinners = computeWinners(assignments)
    }

    private fun computeWinners(assignments: List<CueAssignmentResolver.Assignment>): Map<CueAssignmentResolver.Key, Int> {
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
        return winners.mapValues { (_, a) -> a.cueId }
    }

    private fun buildIndex(
        composed: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>,
    ): Map<String, Map<String, CueAssignmentResolver.PropertyValue>> {
        if (composed.isEmpty()) return emptyMap()
        val idx = HashMap<String, HashMap<String, CueAssignmentResolver.PropertyValue>>()
        for ((key, value) in composed) {
            idx.getOrPut(key.targetKey) { HashMap() }[key.propertyName] = value
        }
        return idx
    }

    /** Clear the Layer 4 state — equivalent to "no cue contributing". */
    fun clearAssignments() {
        cueLayerState = emptyMap()
        cueLayerIndex = emptyMap()
        cueLayerWinners = emptyMap()
    }

    /** Current snapshot; exposed for tests and diagnostics. */
    val currentCueLayerState: Map<CueAssignmentResolver.Key, CueAssignmentResolver.PropertyValue>
        get() = cueLayerState

    /** Winning contributor cueId per composed key — provenance / diagnostics. */
    val currentCueLayerWinners: Map<CueAssignmentResolver.Key, Int>
        get() = cueLayerWinners

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
            val idx = cueLayerIndex
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
