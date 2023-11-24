package uk.me.cormack.lighting7.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.dmx.ArtNetController
import uk.me.cormack.lighting7.dmx.DmxController
import uk.me.cormack.lighting7.dmx.ChannelChangeListener
import uk.me.cormack.lighting7.fixture.Fixture
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface FixturesChangeListener {
    fun channelsChanged(subnet: Int, universe: Int, changes: Map<Int, UByte>)
    fun controllersChanged()
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, ChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val fixturesByGroup: MutableMap<String, MutableList<Fixture>> = mutableMapOf()

    private val changeListeners: MutableList<FixturesChangeListener> = mutableListOf()

    val controllers: List<DmxController> get () = registerLock.read {
        controllerRegister.values.toList()
    }
    val fixtures: List<Fixture> get() = registerLock.read {
        fixtureRegister.values.toList()
    }

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

    fun fixtureGroup(groupName: String): List<Fixture> = registerLock.read {
        checkNotNull(fixturesByGroup[groupName]) { "Fixture group '$groupName' not found" }.toList()
    }

    interface FixtureRegisterer {
        fun addController(controller: DmxController): DmxController
        fun <T: Fixture> addFixture(fixture: T, vararg fixtureGroups: String): T
    }

    fun register(removeUnused: Boolean = true, block: FixtureRegisterer.() -> Unit) {
        val registerer: FixtureRegisterer = object : FixtureRegisterer {
            override fun addController(controller: DmxController): DmxController {
                val controllerKey = controllerKey(controller.subnet, controller.universe)

                val listener = channelChangeHandlerForController(controller)
                controllerChannelChangeListeners[controllerKey] = listener

                when (controller) {
                    is ArtNetController -> controller.registerListener(listener)
                }

                controllerRegister[controllerKey] = controller

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

        changeListeners.forEach {
            it.controllersChanged()
        }
    }

    private fun channelChangeHandlerForController(controller: DmxController): ChannelChangeListener {
        return object : ChannelChangeListener {
            override fun channelsChanged(changes: Map<Int, UByte>) {
                registerLock.read {
                    changeListeners.forEach {
                        it.channelsChanged(controller.subnet, controller.universe, changes)
                    }
                }
            }
        }
    }

    fun registerListener(listener: FixturesChangeListener): Unit = registerLock.write {
        if (!changeListeners.contains(listener)) {
            changeListeners += listener
        }
    }

    fun unregisterListener(listener: FixturesChangeListener): Unit = registerLock.write {
        changeListeners.remove(listener)
    }
}
