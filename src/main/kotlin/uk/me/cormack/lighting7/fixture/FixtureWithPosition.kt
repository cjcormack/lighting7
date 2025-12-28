package uk.me.cormack.lighting7.fixture

/**
 * Trait for fixtures that have pan/tilt position control.
 *
 * Typically implemented by moving head fixtures and scanners.
 */
interface FixtureWithPosition {
    /** Pan position control (horizontal movement) */
    val pan: FixtureSlider

    /** Tilt position control (vertical movement) */
    val tilt: FixtureSlider
}
