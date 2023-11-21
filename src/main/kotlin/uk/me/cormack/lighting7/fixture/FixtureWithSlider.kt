package uk.me.cormack.lighting7.fixture

@FixtureProperty("Sliders")
interface FixtureWithSliders {
    fun setSlider(sliderName: String, level: UByte, fadeMs: Long = 0)
}
