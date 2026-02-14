package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.dmx.ControllerTransaction
import uk.me.cormack.lighting7.fixture.FixtureTarget
import uk.me.cormack.lighting7.fixture.GroupableFixture

/**
 * Symmetric mode for group effects.
 */
enum class SymmetricMode {
    /** No symmetry - effects apply as configured */
    NONE,

    /** Mirror left/right halves (common for stage wash) */
    MIRROR,

    /** Effects radiate outward from center */
    CENTER_OUT,

    /** Effects converge toward center from edges */
    EDGES_IN
}

/**
 * Group-level metadata for effect behavior.
 *
 * @property symmetricMode How effects should be distributed symmetrically
 * @property defaultDistribution Default distribution strategy for this group
 */
data class GroupMetadata(
    val symmetricMode: SymmetricMode = SymmetricMode.NONE,
    val defaultDistributionName: String = "LINEAR"
)

/**
 * A type-safe, position-aware group of fixtures or elements sharing common capabilities.
 *
 * FixtureGroup provides compile-time type safety for fixture operations by
 * parameterizing the group with the fixture/element type or capability interface.
 * This allows operations like effect application to be type-checked at compile time.
 *
 * Groups can contain either standalone [Fixture][uk.me.cormack.lighting7.fixture.Fixture]
 * instances or [FixtureElement] components from multi-element fixtures.
 *
 * Groups support hierarchical composition through [subGroups], allowing you to
 * create groups of groups. Sub-groups are automatically flattened when accessing
 * [fixtures] or iterating.
 *
 * Groups also provide position-aware operations, with each member having an index
 * and normalized position (0.0-1.0) that can be used for phase-distributed effects.
 *
 * @param T The capability bound - all members must be at least this type
 * @property name The group name for identification
 * @property members Direct fixture members of this group
 * @property subGroups Child groups whose fixtures are included when flattening
 * @property metadata Group-level configuration
 */
class FixtureGroup<T : GroupableFixture>(
    val name: String,
    @PublishedApi internal val members: List<GroupMember<T>>,
    val subGroups: List<FixtureGroup<T>> = emptyList(),
    val metadata: GroupMetadata = GroupMetadata()
) : List<GroupMember<T>> by members, FixtureTarget {

    // FixtureTarget implementation
    override val targetKey: String get() = name
    override val displayName: String get() = name
    override val isGroup: Boolean get() = true
    override val memberCount: Int get() = allMembers.size

    /**
     * All members including those from sub-groups, with recalculated indices.
     *
     * This flattens the hierarchy into a single list of members with proper
     * indexing and normalized positions.
     */
    val allMembers: List<GroupMember<T>> by lazy {
        val combined = members + subGroups.flatMap { it.allMembers }
        reindexMembers(combined)
    }

    /** All fixtures in the group including sub-groups (convenience accessor without member wrapper) */
    val fixtures: List<T> get() = allMembers.map { it.fixture }

    /**
     * Flatten this group to get all leaf targets.
     *
     * Returns all fixtures from this group and all sub-groups recursively.
     *
     * @return List of all leaf FixtureTarget instances
     */
    fun flatten(): List<FixtureTarget> = allMembers.map { it.fixture }

    /**
     * Flatten and filter to a specific type.
     *
     * Recursively flattens the group and returns only targets matching the specified type.
     *
     * @return List of all leaf targets matching type R
     */
    inline fun <reified R : FixtureTarget> flattenAs(): List<R> {
        return flatten().filterIsInstance<R>()
    }

    /**
     * Safely narrow this group to a more specific capability type.
     *
     * Use this when you know the group contains members with additional capabilities
     * beyond the declared type, and you want to access those capabilities safely.
     *
     * @return The group cast to the more specific type, or null if any member doesn't support it
     */
    inline fun <reified R : GroupableFixture> asCapable(): FixtureGroup<R>? {
        return asCapableInternal(R::class.java)
    }

    @PublishedApi
    internal fun <R : GroupableFixture> asCapableInternal(clazz: Class<R>): FixtureGroup<R>? {
        val allFixturesCapable = allMembers.all { clazz.isInstance(it.fixture) }
        if (!allFixturesCapable) return null

        @Suppress("UNCHECKED_CAST")
        val castMembers = members.map { member ->
            GroupMember(
                fixture = member.fixture as R,
                index = member.index,
                normalizedPosition = member.normalizedPosition,
                metadata = member.metadata
            )
        }
        val castSubGroups = subGroups.mapNotNull { it.asCapableInternal(clazz) }
        if (castSubGroups.size != subGroups.size) return null

        return FixtureGroup(name, castMembers, castSubGroups, metadata)
    }

    /**
     * Require this group supports a capability, throwing if not.
     *
     * Use this when you need to assert that the group has specific capabilities.
     *
     * @return The group cast to the more specific type
     * @throws IllegalStateException if any member doesn't support the capability
     */
    inline fun <reified R : GroupableFixture> requireCapable(): FixtureGroup<R> {
        return asCapable()
            ?: throw IllegalStateException(
                "Group '$name' does not support ${R::class.simpleName}. " +
                "Not all members implement the required interface."
            )
    }

    /**
     * Create a transaction-bound copy of this group.
     *
     * All fixture operations on the returned group will be performed within
     * the given transaction context.
     *
     * @param transaction The transaction to bind to
     * @return A new group with all members bound to the transaction
     */
    @Suppress("UNCHECKED_CAST")
    override fun withTransaction(transaction: ControllerTransaction): FixtureGroup<T> {
        val boundMembers = members.map { member ->
            member.copy(fixture = member.fixture.withTransaction(transaction) as T)
        }
        val boundSubGroups = subGroups.map { it.withTransaction(transaction) }
        return FixtureGroup(name, boundMembers, boundSubGroups, metadata)
    }

    /**
     * Get a subset of this group by filter.
     *
     * The resulting group will have members re-indexed starting from 0,
     * with normalized positions recalculated. This operates on all members
     * including those from sub-groups (flattening the hierarchy).
     *
     * @param predicate Filter function for members
     * @return A new group containing only matching members
     */
    fun filter(predicate: (GroupMember<T>) -> Boolean): FixtureGroup<T> {
        val filtered = allMembers.filter(predicate)
        val reindexed = reindexMembers(filtered)
        return FixtureGroup("$name-filtered", reindexed, emptyList(), metadata)
    }

    /**
     * Get every Nth member (for sparse chase effects).
     *
     * @param n Select every Nth member
     * @param offset Starting offset (0 to n-1)
     * @return A new group with every Nth member
     */
    fun everyNth(n: Int, offset: Int = 0): FixtureGroup<T> {
        return filter { (it.index + offset) % n == 0 }
    }

    /**
     * Get the left half of the group (normalized position < 0.5).
     */
    fun leftHalf(): FixtureGroup<T> = filter { it.normalizedPosition < 0.5 }

    /**
     * Get the right half of the group (normalized position >= 0.5).
     */
    fun rightHalf(): FixtureGroup<T> = filter { it.normalizedPosition >= 0.5 }

    /**
     * Reverse the group order.
     *
     * The resulting group will have members in reverse order with
     * normalized positions inverted. This operates on all members
     * including those from sub-groups (flattening the hierarchy).
     */
    fun reversed(): FixtureGroup<T> {
        val reversedMembers = allMembers.reversed().mapIndexed { newIdx, member ->
            member.copy(
                index = newIdx,
                normalizedPosition = 1.0 - member.normalizedPosition
            )
        }
        return FixtureGroup("$name-reversed", reversedMembers, emptyList(), metadata)
    }

    /**
     * Get members matching any of the specified tags.
     */
    fun withTags(vararg tags: String): FixtureGroup<T> {
        val tagSet = tags.toSet()
        return filter { member -> member.metadata.tags.any { it in tagSet } }
    }

    /**
     * Get members matching all of the specified tags.
     */
    fun withAllTags(vararg tags: String): FixtureGroup<T> {
        val tagSet = tags.toSet()
        return filter { member -> tagSet.all { it in member.metadata.tags } }
    }

    /**
     * Split the group into two at the specified normalized position.
     *
     * @param splitPoint Normalized position (0.0-1.0) to split at
     * @return Pair of (left, right) groups
     */
    fun splitAt(splitPoint: Double): Pair<FixtureGroup<T>, FixtureGroup<T>> {
        val left = filter { it.normalizedPosition < splitPoint }
        val right = filter { it.normalizedPosition >= splitPoint }
        return left to right
    }

    /**
     * Get the center portion of the group.
     *
     * @param margin Margin from edges (0.0-0.5)
     * @return Group containing only center members
     */
    fun center(margin: Double = 0.25): FixtureGroup<T> {
        return filter { it.normalizedPosition >= margin && it.normalizedPosition <= (1.0 - margin) }
    }

    /**
     * Get the edge members of the group.
     *
     * @param margin How far from edges to include (0.0-0.5)
     * @return Group containing only edge members
     */
    fun edges(margin: Double = 0.25): FixtureGroup<T> {
        return filter { it.normalizedPosition < margin || it.normalizedPosition > (1.0 - margin) }
    }

    private fun reindexMembers(memberList: List<GroupMember<T>>): List<GroupMember<T>> {
        return memberList.mapIndexed { newIdx, member ->
            member.copy(
                index = newIdx,
                normalizedPosition = if (memberList.size > 1)
                    newIdx.toDouble() / (memberList.size - 1)
                else 0.5
            )
        }
    }

    override fun toString(): String = "FixtureGroup(name='$name', size=$size)"
}
