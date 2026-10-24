package uk.me.cormack.lighting7.models

/** A stored Timing (terminology plan D22): one cycle for the whole FX. */
const val TIMING_CYCLE = "CYCLE"

/** A stored Timing: one cycle per head, the cycle multiplied by the FX's distinct slots. */
const val TIMING_HEAD = "HEAD"

/** The stored Timing for an FX's `stepTiming`; null is the FX type's own default. */
fun timingOf(stepTiming: Boolean?): String? = stepTiming?.let { if (it) TIMING_HEAD else TIMING_CYCLE }

/** An FX's `stepTiming` for a stored Timing; null, or a value naming neither, is the type's default. */
fun stepTimingOf(timing: String?): Boolean? = when (timing) {
    TIMING_HEAD -> true
    TIMING_CYCLE -> false
    else -> null
}

/** The stored Timing for an FX definition's `defaultStepTiming`, which always has one. */
fun timingOf(stepTiming: Boolean): String = if (stepTiming) TIMING_HEAD else TIMING_CYCLE
