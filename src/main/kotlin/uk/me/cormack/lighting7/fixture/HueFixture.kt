package uk.me.cormack.lighting7.fixture

import uk.me.cormack.lighting7.fixture.hue.HueController

abstract class HueFixture(val controller: HueController, key: String, fixtureName: String, position: Int): Fixture(key, fixtureName, position)
