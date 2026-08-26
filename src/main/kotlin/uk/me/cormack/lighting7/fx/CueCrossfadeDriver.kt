package uk.me.cormack.lighting7.fx

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.me.cormack.lighting7.dmx.EasingCurve
import java.util.concurrent.ConcurrentHashMap

/**
 * Drives Layer 4 crossfades between an outgoing and an incoming cue, one fade per stack.
 *
 * Each tick, the per-cue fade weight is ticked through [FxEngine.updateCueFadeWeights]
 * — outgoing from 1→0, incoming from 0→1 — so sliders / colours / positions blend
 * symmetrically rather than snap-cutting. When the fade completes, outgoing Layer 4 is
 * dropped via [FxEngine.removeCueAssignments] and incoming weight is pinned at 1.0.
 *
 * Effects are NOT faded here: they are removed at the start of the cue transition (see
 * [CueStackManager.activateCueInStack]) and incoming effects start at full intensity. This
 * matches Eos / grandMA / Hog 4 and avoids the drop-to-0 bug that came from scaling
 * OVERRIDE-blend effect outputs by `intensityMultiplier`.
 *
 * The driver keeps its own per-stack table rather than living in the manager's active-stack
 * entry, because that entry is *replaced wholesale* on every activation while a fade cancelled
 * by that same activation still has assignments to drop — see [cancel].
 */
internal class CueCrossfadeDriver(
    private val fxEngine: FxEngine,
) {
    /**
     * A stack's in-flight fade.
     *
     * @property outgoingCueId the cue being faded out, held until the fade ends so a
     *   cancellation can drop its Layer 4 assignments — end-of-fade would have removed them.
     *   Cleared on normal completion so a later activation doesn't double-drop.
     */
    private class Fade {
        @Volatile var job: Job? = null

        @Volatile var outgoingCueId: Int? = null
    }

    private val fades = ConcurrentHashMap<Int, Fade>()

    /**
     * Start a fade on [stackId], replacing any in-flight one.
     *
     * The caller is expected to have called [cancel] already — activation has other teardown
     * to interleave — so this only installs the new fade.
     */
    fun start(
        stackId: Int,
        outgoingCueId: Int?,
        incomingCueId: Int?,
        durationMs: Long,
        easingCurve: EasingCurve,
        scope: CoroutineScope,
    ) {
        val fade = Fade()
        // Recorded before the launch: the coroutine clears it on completion, and a fade short
        // enough to finish on another thread first must not have its clear overwritten.
        fade.outgoingCueId = outgoingCueId
        // LAZY so the fade is fully published — entry *and* job — before a tick of it can run.
        // Started eagerly, a concurrent [cancel] could read `job == null`, cancel nothing, and
        // leave an orphaned fade to run to completion and pin a cue the newer transition has
        // already superseded back to weight 1.0.
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runFade(outgoingCueId, incomingCueId, durationMs, easingCurve)
            fade.outgoingCueId = null
        }
        fade.job = job
        fades[stackId] = fade
        job.start()
    }

    /**
     * Cancel [stackId]'s in-flight fade, if any, and drop the Layer 4 assignments of the
     * outgoing cue it was still holding — otherwise they'd linger past the cancellation,
     * frozen at whatever weight the fade had reached.
     */
    fun cancel(stackId: Int) {
        val fade = fades.remove(stackId) ?: return
        fade.job?.cancel()
        // Mutating the removed entry is harmless: the coroutine's own clear writes to the same
        // orphaned object, and nothing reads it again.
        fade.outgoingCueId?.let { fxEngine.removeCueAssignments(it) }
        fade.outgoingCueId = null
    }

    private suspend fun runFade(
        outgoingCueId: Int?,
        incomingCueId: Int?,
        durationMs: Long,
        easingCurve: EasingCurve,
    ) {
        val startTime = System.currentTimeMillis()

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            val progress = (elapsed.toDouble() / durationMs).coerceIn(0.0, 1.0)
            val easedProgress = easingCurve.apply(progress)

            // Single atomic update so the engine only republishes once per tick even when both
            // cues contributed Layer 4.
            if (outgoingCueId != null || incomingCueId != null) {
                val updates = buildMap {
                    if (outgoingCueId != null) put(outgoingCueId, 1.0 - easedProgress)
                    if (incomingCueId != null) put(incomingCueId, easedProgress)
                }
                fxEngine.updateCueFadeWeights(updates)
            }

            if (progress >= 1.0) break
            delay(CROSSFADE_TICK_MS)
        }

        // Fade complete — drop outgoing Layer 4 contributions and pin incoming to 1.0.
        if (outgoingCueId != null) {
            fxEngine.removeCueAssignments(outgoingCueId)
        }
        if (incomingCueId != null) {
            fxEngine.updateCueFadeWeights(mapOf(incomingCueId to 1.0))
        }
    }

    companion object {
        /** Tick interval for crossfade envelope updates (≈60fps). */
        const val CROSSFADE_TICK_MS = 16L
    }
}
