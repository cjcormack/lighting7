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
    fun presetListChanged()
    fun cueListChanged()
    fun cueStackListChanged()
    fun cueSlotListChanged()
    fun patchListChanged()
    fun riggingListChanged()
    fun stageRegionListChanged()
    fun showEntriesChanged()
    fun showChanged(projectId: Int, activeEntryId: Int?, activatedStackId: Int?, activatedStackName: String?)
}

class Fixtures {
    private val registerLock = ReentrantReadWriteLock()
    private val controllerRegister: MutableMap<String, DmxController> = mutableMapOf()
    private val controllerChannelChangeListeners: MutableMap<String, ChannelChangeListener> = mutableMapOf()

    private val fixtureRegister: MutableMap<String, Fixture> = mutableMapOf()
    private val groupRegister: MutableMap<String, FixtureGroup<*>> = mutableMapOf()

    /**
     * Patch fields not consumed during fixture instantiation but needed by REST consumers,
     * cached here so [Fixture.details] can return them without re-querying the DB.
     */
    data class FixturePatchMetadata(
        val gelCode: String?,
    )
    private val patchMetadataRegister: MutableMap<String, FixturePatchMetadata> = mutableMapOf()

    // Channel-to-fixture mapping: "universe:channel" -> ChannelMapping
    data class ChannelMapping(
        val fixtureKey: String,
        val fixtureName: String,
        val description: String
    )
    private val channelMappings: MutableMap<String, ChannelMapping> = mutableMapOf()

    private fun channelMappingKey(universe: Universe, channel: Int) = "${universe.universe}:$channel"

    private val changeListeners: MutableList<FixturesChangeListener> = mutableListOf()

    class FixturesWithTransaction(@PublishedApi internal val baseFixtures: Fixtures, val transaction: ControllerTransaction) {
        val controllers: List<DmxController> get () = baseFixtures.controllers
        val fixtures: List<Fixture> get() = baseFixtures.fixtures.map { wrappedFixture(it) }

        var customChangedChannels: Map<Universe, Map<Int, UByte>>? = null

        // Per-transaction fixture wrapper cache. Without this, an FX tick that touches 168
        // fixtures ends up allocating a fresh wrapped fixture per lookup — each wrap clones
        // 10+ DMX property objects. Cached per key within the lifetime of this transaction.
        // Not thread-safe: callers must hold `baseFixtures.registerLock` for the outer lookup,
        // and a given `FixturesWithTransaction` is scoped to a single FX tick coroutine.
        private val wrappedFixtureCache = HashMap<String, Fixture>()
        private val wrappedElementCache = HashMap<String, GroupableFixture>()

        private fun wrappedFixture(fixture: Fixture): Fixture =
            wrappedFixtureCache.getOrPut(fixture.key) { fixture.withTransaction(transaction) }

        fun controller(universe: Universe): DmxController = baseFixtures.controller(universe)

        fun untypedFixture(key: String): Fixture = baseFixtures.registerLock.read {
            val base = baseFixtures.fixtureRegister[key] ?: error("Fixture '$key' not found")
            wrappedFixture(base)
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
            val base = baseFixtures.fixtureRegister[key]
            if (base != null) return@read wrappedFixture(base)

            wrappedElementCache.getOrPut(key) {
                val element = baseFixtures.resolveElement(key)
                    ?: error("Fixture or element '$key' not found")
                element.withTransaction(transaction)
            }
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

    fun patchMetadataFor(fixtureKey: String): FixturePatchMetadata? = registerLock.read {
        patchMetadataRegister[fixtureKey]
    }

    /** Used by the metadata-only PUT fast path to refresh the cache without a fixtures rebuild. */
    fun setPatchMetadata(fixtureKey: String, metadata: FixturePatchMetadata): Unit = registerLock.write {
        patchMetadataRegister[fixtureKey] = metadata
    }

    fun presetListChanged() {
        changeListeners.forEach {
            it.presetListChanged()
        }
    }

    fun cueListChanged() {
        changeListeners.forEach {
            it.cueListChanged()
        }
    }

    fun cueStackListChanged() {
        changeListeners.forEach {
            it.cueStackListChanged()
        }
    }

    fun cueSlotListChanged() {
        changeListeners.forEach {
            it.cueSlotListChanged()
        }
    }

    fun patchListChanged() {
        changeListeners.forEach {
            it.patchListChanged()
        }
    }

    fun riggingListChanged() {
        changeListeners.forEach {
            it.riggingListChanged()
        }
    }

    fun stageRegionListChanged() {
        changeListeners.forEach {
            it.stageRegionListChanged()
        }
    }

    fun showEntriesChanged() {
        changeListeners.forEach {
            it.showEntriesChanged()
        }
    }

    fun showChanged(projectId: Int, activeEntryId: Int?, activatedStackId: Int?, activatedStackName: String?) {
        changeListeners.forEach {
            it.showChanged(projectId, activeEntryId, activatedStackId, activatedStackName)
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

        fun setPatchMetadata(fixtureKey: String, metadata: FixturePatchMetadata)
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
                    is AsyncTestDmxController -> {} // Test fake doesn't need listeners
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

            override fun setPatchMetadata(fixtureKey: String, metadata: FixturePatchMetadata) {
                patchMetadataRegister[fixtureKey] = metadata
            }
        }

        registerLock.write {
            if (removeUnused) {
                controllerRegister.forEach { (controllerKey, controller) ->
                    when (controller) {
                        is ArtNetController -> controller.unregisterListener(checkNotNull(controllerChannelChangeListeners[controllerKey]))
                        is MockDmxController -> {} // Mock doesn't need listeners
                        is AsyncTestDmxController -> {} // Test fake doesn't need listeners
                    }
                }

                controllerRegister.clear()
                fixtureRegister.clear()
                groupRegister.clear()
                channelMappings.clear()
                patchMetadataRegister.clear()
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
