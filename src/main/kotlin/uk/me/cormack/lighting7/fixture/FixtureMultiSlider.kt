package uk.me.cormack.lighting7.fixture

interface FixtureMultiSlider<T: FixtureSlider> {
    val sliders: Map<String, T>
}
