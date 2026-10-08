package uk.me.cormack.lighting7.fx

/**
 * The one rule for "does this effect paint `(fixtureKey, propertyName)` this tick?" — read by the
 * tick loops ([FxEngine]), by provenance ([ProvenanceService.compute]) and by the property-stack
 * read behind `programmer.keyStack` ([ProvenanceService.keyStack]).
 *
 * It exists as a pure function rather than as the engine's private helper it was because three
 * readers ask it, and the second rule a reader wrote for itself would drift from the engine's: the
 * fixture sheet's *held back* mark is only honest if it is computed by the code that holds the
 * effect back (fixture-fx-sheets plan W1, W4).
 *
 * Every input is passed in, so the answer depends on nothing but its arguments: [held] is one
 * programmer-suppression snapshot ([FxEngine.programmerSuppression] — empty while blind), and
 * [isLayerStomped] is [CueAssignmentLayer.isLayerStomped]. A caller wanting several answers to agree
 * takes the snapshot **once** and asks every question against it.
 */
object EffectSuppression {
    /**
     * Two independent reasons, and the order matters:
     *
     * 1. **Within-cue / within-stack stomp** — a higher layer with `stomp` set asserts this
     *    property, so this layer's effect is switched off on it. Checked *first*, and deliberately
     *    outside the programmer-band exemption below: a programmer layer's effects live in that band
     *    by construction, so exempting the band would make programmer stomp a no-op.
     * 2. **Programmer suppression** — [heldBackByProgrammer].
     *
     * The reset pass has already put the layer below on the property, so a skipped apply *shows the
     * cooked value* rather than freezing the effect's last frame. That is what makes suppression
     * recoverable where removal would not be: the instance keeps running, and clearing the stomp or
     * the programmer entry brings it back with its phase intact.
     */
    fun isSuppressed(
        held: Map<String, Set<String>>,
        fixtureKey: String,
        propertyName: String,
        effect: FxInstance,
        isLayerStomped: (FxInstance, String, String) -> Boolean,
    ): Boolean {
        if (isLayerStomped(effect, fixtureKey, propertyName)) return true
        return heldBackByProgrammer(held, fixtureKey, propertyName, effect.priority)
    }

    /**
     * The programmer half alone: the programmer holds `(fixtureKey, propertyName)` in [held], so an
     * effect at [priority] must not paint over it — unless it sits in the programmer priority band,
     * whose effects modulate *on top of* programmer values rather than fighting them.
     *
     * [held] is [ProgrammerStore.activePropertiesByFixture]'s answer, which names **property**
     * entries only, each holding back effects on its own key alone: a raw-channel sideband slot
     * holds a value but suppresses nothing, and a `pan` entry does not hold back a Circle keyed
     * `position`. Provenance reads this same predicate, of the key an effect paints, to decide
     * whether a programmer value or an effect is what is on stage (W4).
     */
    fun heldBackByProgrammer(
        held: Map<String, Set<String>>,
        fixtureKey: String,
        propertyName: String,
        priority: Int,
    ): Boolean {
        if (held.isEmpty()) return false
        if (FxEngine.isProgrammerFxPriority(priority)) return false
        return held[fixtureKey]?.contains(propertyName) == true
    }
}
