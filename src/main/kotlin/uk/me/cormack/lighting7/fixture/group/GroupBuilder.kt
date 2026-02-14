package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.GroupableFixture

/**
 * DSL builder for creating fixture groups.
 *
 * Provides a convenient way to construct groups with proper indexing
 * and metadata configuration.
 *
 * Example:
 * ```kotlin
 * fixtureGroup<HexFixture>("front-wash") {
 *     add(hex1)
 *     add(hex2, panOffset = -30.0)
 *     add(hex3, panOffset = 30.0)
 *     configure(symmetricMode = SymmetricMode.MIRROR)
 * }
 * ```
 *
 * @param T The fixture or element type for this group
 * @param name The group name
 */
class GroupBuilder<T : GroupableFixture> @PublishedApi internal constructor(private val name: String) {
    private val members = mutableListOf<Pair<T, MemberMetadata>>()
    private val subGroups = mutableListOf<FixtureGroup<T>>()
    private var groupMetadata = GroupMetadata()

    /**
     * Add a fixture to the group with optional metadata.
     *
     * @param fixture The fixture to add
     * @param panOffset Pan center offset in degrees
     * @param tiltOffset Tilt center offset in degrees
     * @param symmetricInvert Whether to invert effects for symmetry
     * @param tags Arbitrary tags for filtering
     */
    fun add(
        fixture: T,
        panOffset: Double = 0.0,
        tiltOffset: Double = 0.0,
        symmetricInvert: Boolean = false,
        tags: Set<String> = emptySet()
    ) {
        members.add(
            fixture to MemberMetadata(
                panOffset = panOffset,
                tiltOffset = tiltOffset,
                symmetricInvert = symmetricInvert,
                tags = tags
            )
        )
    }

    /**
     * Add a fixture to the group with tag varargs convenience.
     *
     * @param fixture The fixture to add
     * @param tags Tags for this member
     */
    fun add(fixture: T, vararg tags: String) {
        add(fixture, tags = tags.toSet())
    }

    /**
     * Add multiple fixtures with auto-calculated position offsets.
     *
     * This is useful for fixtures arranged in a line where you want
     * pan/tilt offsets calculated based on their position.
     *
     * @param fixtures The fixtures to add
     * @param panSpread Total pan spread in degrees (centered around 0)
     * @param tiltSpread Total tilt spread in degrees (centered around 0)
     */
    fun addSpread(
        fixtures: List<T>,
        panSpread: Double = 0.0,
        tiltSpread: Double = 0.0
    ) {
        val count = fixtures.size
        fixtures.forEachIndexed { idx, fixture ->
            val position = if (count > 1) idx.toDouble() / (count - 1) else 0.5
            add(
                fixture,
                panOffset = (position - 0.5) * panSpread,
                tiltOffset = (position - 0.5) * tiltSpread
            )
        }
    }

    /**
     * Add a sub-group to this group.
     *
     * Sub-groups are stored separately and their fixtures are included
     * when accessing the group's fixtures. This allows hierarchical
     * organization of fixtures.
     *
     * @param group The sub-group to add
     */
    fun addGroup(group: FixtureGroup<T>) {
        subGroups.add(group)
    }

    /**
     * Add multiple sub-groups to this group.
     *
     * @param groups The sub-groups to add
     */
    fun addGroups(groups: List<FixtureGroup<T>>) {
        subGroups.addAll(groups)
    }

    /**
     * Add multiple sub-groups to this group (vararg version).
     *
     * @param groups The sub-groups to add
     */
    fun addGroups(vararg groups: FixtureGroup<T>) {
        subGroups.addAll(groups)
    }

    /**
     * Add multiple fixtures without position offsets.
     *
     * @param fixtures The fixtures to add
     */
    fun addAll(fixtures: List<T>) {
        fixtures.forEach { add(it) }
    }

    /**
     * Add multiple fixtures without position offsets (vararg version).
     *
     * @param fixtures The fixtures to add
     */
    fun addAll(vararg fixtures: T) {
        fixtures.forEach { add(it) }
    }

    /**
     * Add fixtures symmetrically from center outward.
     *
     * For even counts: fixtures[0] and fixtures[1] are the center pair,
     * fixtures[2] and fixtures[3] are the next pair outward, etc.
     *
     * For odd counts: fixtures[0] is center, fixtures[1] and fixtures[2]
     * are the first pair outward, etc.
     *
     * @param fixtures The fixtures in symmetric order
     * @param panSpread Total pan spread for outermost pair
     * @param tiltSpread Total tilt spread for outermost pair
     */
    fun addSymmetric(
        fixtures: List<T>,
        panSpread: Double = 0.0,
        tiltSpread: Double = 0.0
    ) {
        val count = fixtures.size
        fixtures.forEachIndexed { idx, fixture ->
            val position = if (count > 1) idx.toDouble() / (count - 1) else 0.5
            val isRightSide = position > 0.5
            add(
                fixture,
                panOffset = (position - 0.5) * panSpread,
                tiltOffset = (position - 0.5) * tiltSpread,
                symmetricInvert = isRightSide
            )
        }
    }

    /**
     * Configure group-level metadata.
     *
     * @param symmetricMode How effects should be distributed symmetrically
     * @param defaultDistribution Name of the default distribution strategy
     */
    fun configure(
        symmetricMode: SymmetricMode = SymmetricMode.NONE,
        defaultDistribution: String = "LINEAR"
    ) {
        groupMetadata = GroupMetadata(symmetricMode, defaultDistribution)
    }

    /**
     * Build the fixture group.
     */
    @PublishedApi
    internal fun build(): FixtureGroup<T> {
        val indexedMembers = members.mapIndexed { idx, (fixture, meta) ->
            GroupMember(
                fixture = fixture,
                index = idx,
                normalizedPosition = if (members.size > 1)
                    idx.toDouble() / (members.size - 1)
                else 0.5,
                metadata = meta
            )
        }
        return FixtureGroup(name, indexedMembers, subGroups.toList(), groupMetadata)
    }
}

/**
 * Create a type-safe fixture group using a DSL builder.
 *
 * Example:
 * ```kotlin
 * val frontWash = fixtureGroup<HexFixture>("front-wash") {
 *     addSpread(listOf(hex1, hex2, hex3, hex4), panSpread = 120.0)
 *     configure(symmetricMode = SymmetricMode.MIRROR)
 * }
 * ```
 *
 * @param T The fixture or element type for this group
 * @param name The group name
 * @param block Configuration block
 * @return The constructed fixture group
 */
inline fun <reified T : GroupableFixture> fixtureGroup(
    name: String,
    block: GroupBuilder<T>.() -> Unit
): FixtureGroup<T> {
    return GroupBuilder<T>(name).apply(block).build()
}

/**
 * Create a fixture group from a list of fixtures/elements with automatic indexing.
 *
 * @param T The fixture or element type
 * @param name The group name
 * @param fixtures The fixtures/elements to include
 * @return The constructed fixture group
 */
fun <T : GroupableFixture> fixtureGroupOf(
    name: String,
    fixtures: List<T>
): FixtureGroup<T> {
    val builder = GroupBuilder<T>(name)
    builder.addAll(fixtures)
    return builder.build()
}

/**
 * Create a fixture group from vararg fixtures/elements with automatic indexing.
 *
 * @param T The fixture or element type
 * @param name The group name
 * @param fixtures The fixtures/elements to include
 * @return The constructed fixture group
 */
fun <T : GroupableFixture> fixtureGroupOf(
    name: String,
    vararg fixtures: T
): FixtureGroup<T> {
    return fixtureGroupOf(name, fixtures.toList())
}
