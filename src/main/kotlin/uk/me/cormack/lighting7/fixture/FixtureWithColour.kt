package uk.me.cormack.lighting7.fixture

interface FixtureWithColour<T: FixtureColour<*>> {
    val rgbColour: T
}
