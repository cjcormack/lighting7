package uk.me.cormack.lighting7.fixture

interface FixtureSlider {
    var value: UByte
    fun fadeToValue(value: UByte, fadeMs: Long)
}
