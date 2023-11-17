package uk.me.cormack.fixture

@FixtureProperty("Dimmer")
interface FixtureWithDimmer {
    var level: UByte
    fun fadeToLevel(level: UByte, fadeMs: Long)
}
