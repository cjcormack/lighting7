package uk.me.cormack.lighting7.fx

/**
 * Beat division constants for common timing values.
 *
 * Values represent the number of beats for one complete effect cycle.
 * For example, QUARTER (1.0) means the effect completes in one beat,
 * HALF (2.0) means the effect takes two beats to complete.
 */
object BeatDivision {
    /** Four beats - one bar in 4/4 time */
    const val WHOLE = 4.0

    /** Two beats - half a bar */
    const val HALF = 2.0

    /** One beat - quarter note */
    const val QUARTER = 1.0

    /** Half a beat - eighth note */
    const val EIGHTH = 0.5

    /** Quarter of a beat - sixteenth note */
    const val SIXTEENTH = 0.25

    /** Eighth of a beat - thirty-second note */
    const val THIRTY_SECOND = 0.125

    /** One third of a beat - triplet */
    const val TRIPLET = 1.0 / 3.0

    /** Two bars in 4/4 time */
    const val TWO_BARS = 8.0

    /** One bar in 4/4 time */
    const val ONE_BAR = 4.0
}
