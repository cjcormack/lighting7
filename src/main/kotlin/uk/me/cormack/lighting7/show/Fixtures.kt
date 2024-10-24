package uk.me.cormack.lighting7.show

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import uk.me.cormack.lighting7.dmx.*
import uk.me.cormack.lighting7.fixture.Fixture
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface FixturesChangeListener {
    fun channelsChanged(universe: Universe, changes: Map<Int, UByte>)
    fun controllersChanged()
    fun fixturesChanged()
    fun scenesChanged()
    fun trackChanged(isPlaying: Boolean, artist: String, name: String)
}

@OptIn(ExperimentalCoroutinesApi::class, ObsoleteCoroutinesApi::class)
class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, ChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val fixturesByGroup: MutableMap<String, MutableList<Fixture>> = mutableMapOf()

    private val activeScenesLock = ReentrantReadWriteLock()
    private val activeScenes = mutableMapOf<String, Map<Universe, Map<Int, UByte>>>()

    private val changeListeners: MutableList<FixturesChangeListener> = mutableListOf()

    class FixturesWithTransaction(private val baseFixtures: Fixtures, val transaction: ControllerTransaction) {
        val controllers: List<DmxController> get () = baseFixtures.controllers
        val fixtures: List<Fixture> get() = baseFixtures.fixtures.map { it.withTransaction(transaction) }

        var customChangedChannels: Map<Universe, Map<Int, UByte>>? = null

        fun controller(universe: Universe): DmxController = baseFixtures.controller(universe)

        fun untypedFixture(key: String): Fixture = baseFixtures.registerLock.read {
            checkNotNull(baseFixtures.fixtureRegister[key]?.withTransaction(transaction)) { "Fixture '$key' not found" }
        }

        inline fun <reified T: Fixture> fixture(key: String): T {
            return untypedFixture(key) as T
        }

        fun fixtureGroup(groupName: String): List<Fixture> = baseFixtures.fixtureGroup(groupName).map { it.withTransaction(transaction) }

        fun register(removeUnused: Boolean = true, block: FixtureRegisterer.() -> Unit) = baseFixtures.register(removeUnused, block)
    }

    fun withTransaction(transaction: ControllerTransaction): FixturesWithTransaction = FixturesWithTransaction(this, transaction)

    val controllers: List<DmxController> get () = registerLock.read {
        controllerRegister.values.toList()
    }
    val fixtures: List<Fixture> get() = registerLock.read {
        fixtureRegister.values.toList()
    }

    private fun Universe.controllerKey(): String {
        return "$subnet:$universe"
    }

    fun controller(universe: Universe): DmxController = registerLock.read {
        val controllerKey = universe.controllerKey()
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

    fun recordScene(sceneName: String, changeDetails: Map<Universe, Map<Int, UByte>>) {
        activeScenesLock.write {
            if (changeDetails.isEmpty()) {
                activeScenes.remove(sceneName)
            } else {
                activeScenes[sceneName] = changeDetails
            }
        }
        scenesChanged()
    }

    fun isSceneActive(sceneName: String): Boolean = activeScenesLock.read {
        activeScenes.containsKey(sceneName)
    }

    fun scenesChanged() {
        changeListeners.forEach {
            it.scenesChanged()
        }
    }

    fun trackChanged(isPlaying: Boolean, artist: String, name: String) {
        changeListeners.forEach {
            it.trackChanged(isPlaying, artist, name)
        }
    }

    interface FixtureRegisterer {
        fun addController(controller: DmxController): DmxController
        fun <T: Fixture> addFixture(fixture: T, vararg fixtureGroups: String): T
    }

    fun register(removeUnused: Boolean = true, block: FixtureRegisterer.() -> Unit) {
        val registerer: FixtureRegisterer = object : FixtureRegisterer {
            override fun addController(controller: DmxController): DmxController {
                val controllerKey = controller.universe.controllerKey()

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
                activeScenes.clear()
            }

            block(registerer)
        }

        changeListeners.forEach {
            it.controllersChanged()
            it.fixturesChanged()
        }
    }

    private fun channelChangeHandlerForController(controller: DmxController): ChannelChangeListener {
        return object : ChannelChangeListener {
            override fun channelsChanged(changes: Map<Int, UByte>) {
                registerLock.read {
                    changeListeners.forEach {
                        it.channelsChanged(controller.universe, changes)
                    }
                }
                val scenesToUnset = activeScenesLock.read {
                    activeScenes.filterValues {
                        it[controller.universe]?.filter { (channelNo, sceneValue) ->
                            val changeValue = changes[channelNo]
                            if (changeValue != null) {
                                changeValue != sceneValue
                            } else {
                                false
                            }
                        }?.isNotEmpty() ?: false
                    }.keys
                }
                if (scenesToUnset.isNotEmpty()) {
                    activeScenesLock.write {
                        scenesToUnset.forEach {
                            activeScenes.remove(it)
                            println("Scene no longer set $it")
                        }
                    }
                    scenesChanged()
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
