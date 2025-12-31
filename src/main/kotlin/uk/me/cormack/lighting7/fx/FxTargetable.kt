package uk.me.cormack.lighting7.fx

/**
 * Interface for entities that can be targeted by FX effects.
 *
 * Both individual fixtures and fixture groups implement this interface,
 * enabling uniform FX targeting across both types.
 */
interface FxTargetable {
    /**
     * Unique identifier key for this targetable entity.
     * For fixtures, this is the fixture key.
     * For groups, this is the group name.
     */
    val targetKey: String

    /**
     * Whether this target represents a group (vs individual fixture).
     */
    val isGroup: Boolean

    /**
     * Number of individual fixtures this target expands to.
     * For fixtures, this is always 1.
     * For groups, this is the number of members.
     */
    val memberCount: Int
}
