package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.dmx.Universe
import uk.me.cormack.lighting7.fx.FxInstance
import uk.me.cormack.lighting7.fx.ProgrammerStore

/**
 * What a **same-project reload** carries from the show being replaced into its successor
 * (`ProjectManager.switchProject` with the current project's id: a sync pull, a venue Update, Detach,
 * Extract, Attach, a venue's Discard). Those rebuild the whole [Show] from the database, because what changed
 * underneath can be anything; without a carry the rig went dark and stayed dark until the operator
 * started everything again. With one, the successor starts on the outgoing show's last frame
 * ([values]) and [Show.resume] brings back what was running, crossfading whatever moved.
 *
 * Taken by [capture] before the outgoing show is torn down — its effects stop with it.
 */
class ShowCarry internal constructor(
    /**
     * Each controller's frame as the rig is showing it — a parked channel at its park, and a **held**
     * controller (a show behind its venue, `dmx/OutputHold.kt`) at zero on every other channel —
     * which is the successor's starting frame. So a park the reload drops (a pull that unparked it)
     * starts from the parked value, exactly as an unpark hands it down, rather than from whatever sat
     * in the buffer under it; and a reload that lifts a hold (an Update, Detach) brings the new
     * show up from dark rather than putting the held show's old-patch levels on the new addresses.
     */
    val values: Map<Universe, Map<Int, UByte>>,
    /** Each live stack and its live entry, as (stackId, entryId), to GO again (cue model plan P2). */
    val liveEntries: List<Pair<Int, Int>>,
    /** Each armed standby, stack → entry, to arm again after the GOs (which consume it). */
    val standbys: Map<Int, Int>,
    /** Each stack's effect size below full (effects plan D10), set before the GOs so their effects arrive weighed. */
    val stackEffectSizes: Map<Int, Double>,
    /** The loosely held cues, oldest press first, to take again in that order (cue model plan §3.4). */
    val looseCues: List<Int>,
    /** The programmer itself — values, layers, sideband, Blind — handed over whole. */
    val programmerStore: ProgrammerStore,
    /**
     * Which targets are located, and what each asserted: the bookkeeping for the store's LOCATE
     * slots, so they travel together and the next Locate press releases rather than re-asserts.
     */
    val locateManager: LocateManager,
    /**
     * The effects nothing else will bring back: neither a cue's (its GO respawns it) nor a programmer
     * layer's (the layer's recook does). What the operator ran by hand, Include's ad-hoc children, a
     * script's. The instances themselves move to the successor's engine; their phase restarts.
     */
    val effects: List<FxInstance>,
) {
    companion object {
        fun capture(show: Show): ShowCarry = ShowCarry(
            values = show.fixtures.controllers.associate { controller ->
                val buffer = if (controller.held) emptyMap() else controller.currentValues
                controller.universe to (1..512).associateWith { channel ->
                    show.parkManager.getParkedValue(controller.universe.universe, channel) ?: buffer[channel] ?: 0u
                }
            },
            liveEntries = show.cueStackManager.getActiveStackIds().mapNotNull { stackId ->
                show.cueStackManager.getActiveEntryId(stackId)?.let { stackId to it }
            },
            standbys = show.cueStackManager.runState.armedStandbys(),
            stackEffectSizes = show.cueStackManager.effectSizes.snapshot(),
            looseCues = show.cueStackManager.holds.looseCueIdsInPressOrder(),
            programmerStore = show.programmerStore,
            locateManager = show.locateManager,
            // A leaving instance is on its way out, and the successor has no fade to finish it.
            effects = show.fxEngine.getActiveEffects().filter {
                it.cueId == null && it.cueStackId == null && it.programmerLayerEffectKey == null && !it.leaving
            },
        )
    }
}
