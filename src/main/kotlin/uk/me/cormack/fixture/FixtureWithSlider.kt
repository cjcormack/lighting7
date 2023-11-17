package uk.me.cormack.fixture

@FixtureProperty("Sliders")
interface FixtureWithSliders {
    fun setSlider(sliderName: String, level: UByte, fadeMs: Long = 0)
}
