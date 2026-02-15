package uk.me.cormack.lighting7.show

import uk.me.cormack.lighting7.dmx.*
import uk.me.cormack.lighting7.fixture.DmxFixture
import uk.me.cormack.lighting7.fixture.Fixture
import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fixture.group.FixtureGroup
import uk.me.cormack.lighting7.fixture.group.GroupBuilder
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

interface FixturesChangeListener {
    fun channelsChanged(universe: Universe, changes: Map<Int, UByte>)
    fun controllersChanged()
    fun fixturesChanged()
    fun sceneListChanged()
    fun sceneChanged(id: Int)
    fun presetListChanged()
    fun trackChanged(isPlaying: Boolean, artist: String, name: String)
}

class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, ChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val groupRegister: MutableMap<String, FixtureGroup<*>> = mutableMapOf()

    // Channel-to-fixture mapping: "universe:channel" -> ChannelMapping
    data class ChannelMapping(
        val fixtureKey: String,
        val fixtureName: String,
        val description: String
    )
    private val channelMappings: MutableMap<String, ChannelMapping> = mutableMapOf()

    private fun channelMappingKey(universe: Universe, channel: Int) = "${universe.universe}:$channel"

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

        /**
         * Look up a fixture or fixture element by key.
         *
         * First checks the fixture register for a direct match. If not found,
         * attempts to resolve the key as a fixture element by finding a parent
         * fixture whose key is a prefix, checking if it implements [MultiElementFixture],
         * and returning the matching element.
         *
         * @param key The fixture key or element key (e.g. "quad-mover-1.head-0")
         * @return The fixture or element, bound to this transaction
         * @throws IllegalStateException if no matching fixture or element is found
         */
        fun untypedGroupableFixture(key: String): GroupableFixture = baseFixtures.registerLock.read {
            // First, try direct fixture lookup
            baseFixtures.fixtureRegister[key]?.withTransaction(transaction)?.let { return@read it }

            // Try to resolve as an element key (e.g. "fixture-key.head-0" or "fixture-key.element-0")
            baseFixtures.resolveElement(key)?.withTransaction(transaction)
                ?: error("Fixture or element '$key' not found")
        }

        inline fun <reified T: Fixture> fixture(key: String): T {
            return untypedFixture(key) as T
        }

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

    /**
     * Look up a fixture or fixture element by key.
     *
     * First checks the fixture register for a direct match. If not found,
     * attempts to resolve the key as a fixture element by finding a parent
     * fixture whose key is a prefix, checking if it implements [MultiElementFixture],
     * and returning the matching element.
     *
     * @param key The fixture key or element key (e.g. "quad-mover-1.head-0")
     * @return The fixture or element
     * @throws IllegalStateException if no matching fixture or element is found
     */
    fun untypedGroupableFixture(key: String): GroupableFixture = registerLock.read {
        // First, try direct fixture lookup
        fixtureRegister[key]?.let { return@read it }

        // Try to resolve as an element key
        resolveElement(key) ?: error("Fixture or element '$key' not found")
    }

    /**
     * Resolve an element key to its [GroupableFixture] by searching parent fixtures.
     *
     * Element keys follow the convention "parent-key.suffix" where the suffix
     * identifies the element within the parent (e.g. "head-0", "element-1").
     *
     * Must be called within a read lock on [registerLock].
     *
     * @param elementKey The element key to resolve
     * @return The matching element, or null if not found
     */
    internal fun resolveElement(elementKey: String): GroupableFixture? {
        // Find a parent fixture whose key is a prefix of the element key
        val dotIndex = elementKey.lastIndexOf('.')
        if (dotIndex <= 0) return null

        val parentKey = elementKey.substring(0, dotIndex)
        val parent = fixtureRegister[parentKey] ?: return null

        if (parent !is MultiElementFixture<*>) return null

        // Search elements for a matching key
        return parent.elements.firstOrNull { element ->
            element.elementKey == elementKey
        }
    }

    inline fun <reified T: Fixture> fixture(key: String): T {
        return untypedFixture(key) as T
    }

    /**
     * Get all registered typed fixture groups.
     */
    val groups: List<FixtureGroup<*>> get() = registerLock.read {
        groupRegister.values.toList()
    }

    /**
     * Get channel-to-fixture mappings organized by universe.
     * Returns Map<universe, Map<channel, ChannelMapping>>
     */
    fun getChannelMappings(): Map<Int, Map<Int, ChannelMapping>> = registerLock.read {
        channelMappings.entries
            .groupBy { it.key.split(":")[0].toInt() }
            .mapValues { (_, entries) ->
                entries.associate { it.key.split(":")[1].toInt() to it.value }
            }
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

    /**
     * Get the names of all groups that contain a fixture with the given key.
     */
    fun groupsForFixture(fixtureKey: String): List<String> = registerLock.read {
        groupRegister.values
            .filter { group -> group.allMembers.any { member -> member.key == fixtureKey } }
            .map { it.name }
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

    fun presetListChanged() {
        changeListeners.forEach {
            it.presetListChanged()
        }
    }

    fun trackChanged(isPlaying: Boolean, artist: String, name: String) {
        changeListeners.forEach {
            it.trackChanged(isPlaying, artist, name)
        }
    }

    interface FixtureRegisterer {
        fun addController(controller: DmxController): DmxController
        fun <T: Fixture> addFixture(fixture: T): T

        /**
         * Register a typed fixture group.
         */
        fun <T : GroupableFixture> addGroup(group: FixtureGroup<T>): FixtureGroup<T>

        /**
         * Create and register a typed fixture group using a DSL builder.
         */
        fun <T : GroupableFixture> createGroup(name: String, block: GroupBuilder<T>.() -> Unit): FixtureGroup<T>
    }

    fun register(removeUnused: Boolean = true, block: FixtureRegisterer.() -> Unit) {
        val registerer: FixtureRegisterer = object : FixtureRegisterer {
            override fun addController(controller: DmxController): DmxController {
                val controllerKey = controller.universe.controllerKey()

                val listener = channelChangeHandlerForController(controller)
                controllerChannelChangeListeners[controllerKey] = listener

                when (controller) {
                    is ArtNetController -> controller.registerListener(listener)
                    is MockDmxController -> {} // Mock doesn't need listeners
                }

                controllerRegister[controllerKey] = controller

                return controller
            }

            override fun <T : Fixture> addFixture(fixture: T): T {
                fixtureRegister[fixture.key] = fixture

                // Build channel-to-fixture mapping for DMX fixtures
                if (fixture is DmxFixture) {
                    fixture.channelDescriptions().forEach { (channel, description) ->
                        channelMappings[channelMappingKey(fixture.universe, channel)] = ChannelMapping(
                            fixtureKey = fixture.key,
                            fixtureName = fixture.fixtureName,
                            description = description
                        )
                    }
                }

                return fixture
            }

            override fun <T : GroupableFixture> addGroup(group: FixtureGroup<T>): FixtureGroup<T> {
                groupRegister[group.name] = group
                return group
            }

            override fun <T : GroupableFixture> createGroup(name: String, block: GroupBuilder<T>.() -> Unit): FixtureGroup<T> {
                val group = GroupBuilder<T>(name).apply(block).build()
                return addGroup(group)
            }
        }

        registerLock.write {
            if (removeUnused) {
                controllerRegister.forEach { (controllerKey, controller) ->
                    when (controller) {
                        is ArtNetController -> controller.unregisterListener(checkNotNull(controllerChannelChangeListeners[controllerKey]))
                        is MockDmxController -> {} // Mock doesn't need listeners
                    }
                }

                controllerRegister.clear()
                fixtureRegister.clear()
                groupRegister.clear()
                channelMappings.clear()
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
