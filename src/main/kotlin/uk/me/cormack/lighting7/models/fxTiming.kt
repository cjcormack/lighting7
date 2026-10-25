package uk.me.cormack.lighting7.models

import kotlinx.serialization.Serializable

/** An FX's Timing (terminology plan D22): one cycle for the whole FX, or one per head. */
@Serializable
enum class Timing {
    /** One cycle for the whole FX. */
    CYCLE,

    /** One cycle per head: the cycle multiplied by the FX's distinct slots. */
    HEAD,
}

/** A stored Timing: one cycle for the whole FX. */
const val TIMING_CYCLE = "CYCLE"

/** A stored Timing: one cycle per head. */
const val TIMING_HEAD = "HEAD"

/** The stored Timing for an FX's [timing]; null is the FX type's own default. */
fun storedTiming(timing: Timing?): String? = timing?.name

/** An FX's Timing for a stored one; null, or a value naming neither, is the type's default. */
fun timingOf(stored: String?): Timing? = Timing.entries.firstOrNull { it.name == stored }
