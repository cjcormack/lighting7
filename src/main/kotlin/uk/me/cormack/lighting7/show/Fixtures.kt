package uk.me.cormack.lighting7.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.IChannelChangeListener
import uk.me.cormack.lighting7.fixture.Fixture
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, IChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val fixturesByGroup: MutableMap<String, MutableList<Fixture>> = mutableMapOf()

    private val channelChangeListeners: MutableMap<String, MutableList<IChannelChangeListener>> = mutableMapOf()

    val controllers: List<DmxController> get () = registerLock.read {
        controllerRegister.values.toList()
    }
    val fixtures: List<Fixture> get() = registerLock.read {
        fixtureRegister.values.toList()
    }

//    init {
//        val raspberryPiUniverse = registerController(ArtNetController(0, 0/*, "raspberrypi.local"*/))
//
//        registerFixture(HexFixture(raspberryPiUniverse, "hex1", "Hex 1", 65, 0))
//        registerFixture(HexFixture(raspberryPiUniverse, "hex2", "Hex 2", 81, 0))
//        registerFixture(HexFixture(raspberryPiUniverse, "hex3", "Hex 2", 97, 0))
//        registerFixture(UVFixture(raspberryPiUniverse, "uv1", "UV 1", 161, 1))
//        registerFixture(UVFixture(raspberryPiUniverse, "uv2", "UV 2", 177, 1))
//        registerFixture(ScantasticFixture(raspberryPiUniverse, "scantastic", "Scantastic", 193, 0))
//        registerFixture(QuadBarFixture(raspberryPiUniverse, "quadbar", "Quadbar", 225, 0))
//        registerFixture(StarClusterFixture(raspberryPiUniverse, "starcluster", "Starcluster", 241, 0))
//        registerFixture(LaswerworldCS100Fixture(raspberryPiUniverse, "laser", "Laser", 257, 0))
//        registerFixture(FusionSpotFixture(raspberryPiUniverse, "moving-left", "Moving Left", 273, 0))
//        registerFixture(FusionSpotFixture(raspberryPiUniverse, "moving-right", "Moving Right", 289, 0))
//    }

    private fun controllerKey(subnet: Int, universe: Int): String {
        return "$subnet:$universe"
    }

    fun controller(subnet: Int, universe: Int): DmxController = registerLock.read {
        val controllerKey = controllerKey(subnet, universe)
        checkNotNull(controllerRegister[controllerKey]) { "Controller '$controllerKey' not found" }
    }

    fun untypedFixture(key: String): Fixture = registerLock.read {
        checkNotNull(fixtureRegister[key]) { "Fixture '$key' not found" }
    }

    inline fun <reified T: Fixture> fixture(key: String): T {
        return untypedFixture(key) as T
    }

    interface FixtureRegisterer {
        fun addController(controller: DmxController): DmxController
        fun <T: Fixture> addFixture(fixture: T, vararg fixtureGroups: String): T
    }

    fun register(removeUnused: Boolean = true, block: FixtureRegisterer.() -> Unit) {
        val registerer: FixtureRegisterer = object : FixtureRegisterer {
            override fun addController(controller: DmxController): DmxController {
                val controllerKey = controllerKey(controller.subnet, controller.universe)

                when (controller) {
                    is ArtNetController -> controller.registerListener(channelChangeHandlerForController(controllerKey))
                }

                controllerRegister[controllerKey] = controller
                channelChangeListeners[controllerKey] = mutableListOf()

                return controller
            }

            override fun <T : Fixture> addFixture(fixture: T, vararg fixtureGroups: String): T {
                fixtureRegister[fixture.key] = fixture

                fixtureGroups.forEach {
                    fixturesByGroup.getOrPut(it) { mutableListOf() } += fixture
                }

                return fixture
            }
        }

        registerLock.write {
            if (removeUnused) {
                controllerRegister.forEach { (controllerKey, controller) ->
                    when (controller) {
                        is ArtNetController -> controller.unregisterListener(checkNotNull(controllerChannelChangeListeners[controllerKey]))
                    }
                }

                controllerRegister.clear()
                fixtureRegister.clear()
                fixturesByGroup.clear()
            }

            block(registerer)
        }
    }

    private fun channelChangeHandlerForController(controllerKey: String): IChannelChangeListener {
        return object : IChannelChangeListener {
            override fun channelsChanged(changes: Map<Int, UByte>) {
                registerLock.read {
                    channelChangeListeners[controllerKey]?.forEach {
                        it.channelsChanged(changes)
                    }
                }
            }
        }
    }

    fun registerListener(subnet: Int, universe: Int, listener: IChannelChangeListener): Unit = registerLock.read {
        val controllerKey = controllerKey(subnet, universe)

        val controllerListeners = channelChangeListeners[controllerKey] ?: return
        if (!controllerListeners.contains(listener)) {
            controllerListeners.add(listener)
        }
    }

    fun unregisterListener(subnet: Int, universe: Int, listener: IChannelChangeListener): Unit = registerLock.read {
        val controllerKey = controllerKey(subnet, universe)

        val controllerListeners = channelChangeListeners[controllerKey] ?: return
        controllerListeners.remove(listener)
    }
}
