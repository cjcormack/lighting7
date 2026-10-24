package uk.me.cormack.lighting7.fx

/**
 * Common cycle lengths (terminology plan D1): the length of one FX cycle, in beats of its speed
 * master. [ONE_BEAT] (1.0) completes a cycle every beat; [ONE_BAR] (4.0) every bar of 4/4.
 */
object CycleLength {
    /** Two bars of 4/4. */
    const val TWO_BARS = 8.0

    /** One bar of 4/4. */
    const val ONE_BAR = 4.0

    /** Two beats: half a bar. */
    const val TWO_BEATS = 2.0

    /** One beat. */
    const val ONE_BEAT = 1.0

    /** Half a beat. */
    const val HALF_BEAT = 0.5

    /** A third of a beat: a triplet. */
    const val THIRD_BEAT = 1.0 / 3.0

    /** A quarter of a beat. */
    const val QUARTER_BEAT = 0.25

    /** An eighth of a beat. */
    const val EIGHTH_BEAT = 0.125
}
