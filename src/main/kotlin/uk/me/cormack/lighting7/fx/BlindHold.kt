package uk.me.cormack.lighting7.fx

/**
 * The one rule for "does Blind hold this effect?" (show-cue-editing plan D7, §3.6) — read by the tick
 * loops ([FxEngine]), by provenance ([ProvenanceService.compute]) and by the property-stack read behind
 * `programmer.keyStack` ([ProvenanceService.keyStack]).
 *
 * While the programmer is blind, every instance in the **programmer priority band**
 * ([FxEngine.PROGRAMMER_FX_PRIORITY_BASE]) is held: a `programmerOwned` add, an effect Include spawned,
 * an effect a programmer layer spawned. Cue, Look-in-cue and script effects are not the programmer's
 * and keep painting. Blind holds the programmer's effects exactly as it holds its values, so a Blind
 * edit of a cue with an effect puts nothing on the rig.
 *
 * **A hold is not a stop.** The tick keeps a held instance's phase — a beat effect's is a function of
 * its master's tick, a wall-clock one's accumulator advances every pass whether it paints or not, and
 * a stateful effect is still computed — and skips only its apply, so leaving Blind lands it mid-phase,
 * in step with the effects it started beside. The reset pass has already put the layer below on the
 * property, as for a suppressed effect, so a held key shows the cooked value.
 *
 * Separate from [FxSuppression], whose rule is unchanged: that one says what the programmer's
 * *values* and a layer's stomp hold back, and Blind empties its programmer half. This says what Blind
 * itself holds — the band that rule exempts.
 *
 * [blind] is one read of [ProgrammerStore.blind], taken once by the caller for every question it asks,
 * so a pass (or a provenance compute, or a stack read) cannot answer two effects against two states.
 */
object BlindHold {
    fun holds(blind: Boolean, priority: Int): Boolean = blind && FxEngine.isProgrammerFxPriority(priority)

    fun holds(blind: Boolean, effect: FxInstance): Boolean = holds(blind, effect.priority)
}
