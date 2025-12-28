package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.dmx.*
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.GroupBuilder
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface FixturesChangeListener {
    fun channelsChanged(universe: Universe, changes: Map<Int, UByte>)
    fun controllersChanged()
    fun fixturesChanged()
    fun sceneListChanged()
    fun sceneChanged(id: Int)
    fun trackChanged(isPlaying: Boolean, artist: String, name: String)
}

class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, ChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val fixturesByGroup: MutableMap<String, MutableList<Fixture>> = mutableMapOf()
    private val groupRegister: MutableMap<String, FixtureGroup<*>> = mutableMapOf()

    private val activeScenesLock = ReentrantReadWriteLock()
    private val activeScenes = mutableMapOf<Int, Map<Universe, Map<Int, UByte>>>()
    private val activeChases = mutableMapOf<Int, Boolean>()

    private val changeListeners: MutableList<FixturesChangeListener> = mutableListOf()

    class FixturesWithTransaction(@PublishedApi internal val baseFixtures: Fixtures, val transaction: ControllerTransaction) {
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

        /**
         * Get all registered typed fixture groups.
         */
        val groups: List<FixtureGroup<*>> get() = baseFixtures.groups.map { it.withTransaction(transaction) }

        /**
         * Get a typed fixture group by name (untyped version).
         */
        fun untypedGroup(name: String): FixtureGroup<*> = baseFixtures.untypedGroup(name).withTransaction(transaction)

        /**
         * Get a typed fixture group by name.
         */
        inline fun <reified T : Fixture> group(name: String): FixtureGroup<T> = baseFixtures.group<T>(name).withTransaction(transaction)

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

    /**
     * Get all registered typed fixture groups.
     */
    val groups: List<FixtureGroup<*>> get() = registerLock.read {
        groupRegister.values.toList()
    }

    /**
     * Get a typed fixture group by name (untyped version).
     */
    fun untypedGroup(name: String): FixtureGroup<*> = registerLock.read {
        checkNotNull(groupRegister[name]) { "Fixture group '$name' not found" }
    }

    /**
     * Get a typed fixture group by name.
     *
     * @throws IllegalStateException if the group doesn't exist or doesn't match the type
     */
    inline fun <reified T : Fixture> group(name: String): FixtureGroup<T> {
        val group = untypedGroup(name)
        return group.requireCapable()
    }

    fun recordScene(sceneId: Int, changeDetails: Map<Universe, Map<Int, UByte>>) {
        activeScenesLock.write {
            if (changeDetails.isEmpty()) {
                activeScenes.remove(sceneId)
            } else {
                activeScenes[sceneId] = changeDetails
            }
        }
        sceneChanged(sceneId)
    }

    fun recordChaseStart(sceneId: Int) {
        activeScenesLock.write {
            activeChases[sceneId] = true
        }
        sceneChanged(sceneId)
    }

    fun recordChaseStop(sceneId: Int) {
        activeScenesLock.write {
            activeChases[sceneId] = false
        }
        sceneChanged(sceneId)
    }

    fun isSceneActive(sceneId: Int): Boolean = activeScenesLock.read {
        activeScenes.containsKey(sceneId) || activeChases.getOrDefault(sceneId, false)
    }

    fun sceneListChanged() {
        changeListeners.forEach {
            it.sceneListChanged()
        }
    }

    fun sceneChanged(id: Int) {
        changeListeners.forEach {
            it.sceneChanged(id)
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

        /**
         * Register a typed fixture group.
         */
        fun <T : Fixture> addGroup(group: FixtureGroup<T>): FixtureGroup<T>

        /**
         * Create and register a typed fixture group using a DSL builder.
         */
        fun <T : Fixture> createGroup(name: String, block: GroupBuilder<T>.() -> Unit): FixtureGroup<T>
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

            override fun <T : Fixture> addGroup(group: FixtureGroup<T>): FixtureGroup<T> {
                groupRegister[group.name] = group
                return group
            }

            override fun <T : Fixture> createGroup(name: String, block: GroupBuilder<T>.() -> Unit): FixtureGroup<T> {
                val group = GroupBuilder<T>(name).apply(block).build()
                return addGroup(group)
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
                groupRegister.clear()
                activeScenes.clear()
                activeChases.clear()
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
                            sceneChanged(it)
                        }
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
