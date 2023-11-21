package uk.me.cormack.lighting7.fixture

@FixtureProperty("Dimmer")
interface FixtureWithDimmer {
    var level: UByte
    fun fadeToLevel(level: UByte, fadeMs: Long)
}
