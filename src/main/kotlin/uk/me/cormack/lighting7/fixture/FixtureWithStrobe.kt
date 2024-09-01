package uk.me.cormack.lighting7.fixture

interface FixtureStrobe {
    fun fullOn()
    fun strobe(intensity: UByte)
}

interface FixtureWithStrobe {
    val strobe: FixtureStrobe
}
