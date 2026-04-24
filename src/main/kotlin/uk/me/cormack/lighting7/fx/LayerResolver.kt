package uk.me.cormack.lighting7.fx

import uk.me.cormack.lighting7.fixture.GroupableFixture

/**
 * Resolves the "layer below" [FxOutput] for a given target at effect-reset time.
 *
 * Cascade (see `docs/lighting-composition-model.md` §"Overview"):
 *
 * 1. **Layer 3** — property assignments composed by [Layer3Resolver] from active cues. In
 *    Phase 0 this is always empty; Phase 1 wires real cue data in.
 * 2. **Layer 4** — sticky direct channel writes held in [DirectWriteStore].
 * 3. **Layer 5** — fixture baseline (0 for sliders, black for colour, 128 centered for
 *    pan/tilt, 0 for settings).
 *
 * This object is the single read site for the cascade during a tick's effect-reset pass. Its
 * reads are allocation-free and lock-free: Layer 3 is a volatile [Map] reference swapped on
 * cue apply, and Layer 4 is a [ConcurrentHashMap] of `Long → UByte`.
 *
 * Layer 1 (parking) is handled separately — the caller should consult [ParkManager.isParked]
 * *before* calling [fallbackFor] and skip the property entirely for parked channels.
 */
class LayerResolver(
    private val layer3: Layer3Resolver,
    private val directWrites: DirectWriteStore,
) {
    /**
     * Current Layer 3 composition output, indexed by (targetKey, propertyName). Rebuilt when
     * cues change active state (Phase 1 wires this; Phase 0 keeps it empty).
     */
    @Volatile
    private var layer3State: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue> = emptyMap()

    // Hot-path index keyed by fixtureKey → propertyName → value. Lets the per-tick reset path
    // look up a Layer 3 contribution without allocating a compound `Layer3Resolver.Key` per
    // call. Rebuilt atomically alongside `layer3State` in [applyAssignments].
    @Volatile
    private var layer3Index: Map<String, Map<String, Layer3Resolver.PropertyValue>> = emptyMap()

    /** Replace the Layer 3 state from the current set of assignments. Called on cue apply. */
    fun applyAssignments(assignments: List<Layer3Resolver.Assignment>) {
        val composed = if (assignments.isEmpty()) emptyMap() else layer3.resolve(assignments)
        layer3State = composed
        layer3Index = buildIndex(composed)
    }

    private fun buildIndex(
        composed: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue>,
    ): Map<String, Map<String, Layer3Resolver.PropertyValue>> {
        if (composed.isEmpty()) return emptyMap()
        val idx = HashMap<String, HashMap<String, Layer3Resolver.PropertyValue>>()
        for ((key, value) in composed) {
            idx.getOrPut(key.targetKey) { HashMap() }[key.propertyName] = value
        }
        return idx
    }

    /** Clear the Layer 3 state — equivalent to "no cue contributing". */
    fun clearAssignments() {
        layer3State = emptyMap()
        layer3Index = emptyMap()
    }

    /** Current snapshot; exposed for tests and diagnostics. */
    val currentLayer3State: Map<Layer3Resolver.Key, Layer3Resolver.PropertyValue>
        get() = layer3State

    /**
     * Resolve the fallback [FxOutput] for the given target + fixture. Returned value is what
     * the effect's reset-to-neutral should write before the effect's own contribution blends
     * over the top.
     */
    fun fallbackFor(target: FxTarget, fixture: GroupableFixture, fixtureKey: String): FxOutput {
        // Phase 0 hot-path fast-path: when no cues contribute Layer 3 state, skip key
        // allocation entirely and go straight to Layer 4 / Layer 5.
        val idx = layer3Index
        if (idx.isNotEmpty()) {
            val byProperty = idx[fixtureKey]
            val l3 = byProperty?.get(target.propertyName)
            if (l3 != null) {
                l3.asFxOutputFor(target)?.let { return it }
            }
        }
        return target.fallbackFromDirectWrites(fixture, directWrites)
    }

    private fun Layer3Resolver.PropertyValue.asFxOutputFor(target: FxTarget): FxOutput? = when (this) {
        is Layer3Resolver.PropertyValue.Slider ->
            if (target is SliderTarget) FxOutput.Slider(value) else null
        is Layer3Resolver.PropertyValue.Colour ->
            if (target is ColourTarget) FxOutput.Colour(value) else null
        is Layer3Resolver.PropertyValue.Position ->
            if (target is PositionTarget) FxOutput.Position(pan, tilt) else null
        is Layer3Resolver.PropertyValue.Setting ->
            if (target is SettingTarget) FxOutput.Slider(channelValue) else null
    }
}
