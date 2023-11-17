package uk.me.cormack.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.artnet.ArtNetController
import uk.me.cormack.fixture.Fixture
import uk.me.cormack.fixture.dmx.*
import kotlin.reflect.full.declaredMemberProperties

@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable()
annotation class FixtureGroup(val name: String)

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class Fixtures {
    val raspberryPiUniverse = ArtNetController(0, 0/*, "raspberrypi.local"*/)
//    val openDmxUniverse = ArtNetController(0, 1/*, "192.168.1.215"*/)
    val lightStripUniverse =  ArtNetController(0, 2/*, address = "192.168.50.115"*/, needsRefresh = true)

    val fixturesByGroup: Map<String, List<Fixture>>
    val allFixtures : List<Fixture>

    val hex1 = HexFixture(raspberryPiUniverse, "hex1", "Hex 1", 65, 0)
    @FixtureGroup("atmosphere")
    val hex2 = HexFixture(raspberryPiUniverse, "hex2", "Hex 2", 81, 0)
    @FixtureGroup("atmosphere")
    val hex3 = HexFixture(raspberryPiUniverse, "hex3", "Hex 2", 97, 0)
    val uv1 = UVFixture(raspberryPiUniverse, "uv1", "UV 1", 161, 1)
    val uv2 = UVFixture(raspberryPiUniverse, "uv2", "UV 2", 177, 1)
    val scantastic = ScantasticFixture(raspberryPiUniverse, "scantastic", "Scantastic", 193, 0)
    val quadbar = QuadBarFixture(raspberryPiUniverse, "quadbar", "Quadbar", 225, 0)
    val starcluster = StarClusterFixture(raspberryPiUniverse, "starcluster", "Starcluster", 241, 0)
    val laser = LaswerworldCS100Fixture(raspberryPiUniverse, "laser", "Laser", 257, 0)
    @FixtureGroup("atmosphere")
    val lightstrip = LightstripFixture(lightStripUniverse, "lightstrip", "Light Strip", 0, 0)
    val movingLeft = FusionSpotFixture(raspberryPiUniverse, "moving-left", "Moving Left", 273, 0)
    val movingRight = FusionSpotFixture(raspberryPiUniverse, "moving-right", "Moving Right", 289, 0)

    init {
        val allFixtures = mutableListOf<Fixture>()
        val fixturesByGroup = mutableMapOf<String, MutableList<Fixture>>()

        Fixtures::class.declaredMemberProperties.forEach { memberProperty ->
            val member = memberProperty.get(this)
            if (member !is Fixture) {
                return@forEach
            }

            allFixtures += member

            memberProperty.annotations.filterIsInstance<FixtureGroup>().forEach {
                fixturesByGroup.getOrPut(it.name) { mutableListOf() } += member
            }
        }

        this.allFixtures = allFixtures
        this.fixturesByGroup = fixturesByGroup
    }
}
