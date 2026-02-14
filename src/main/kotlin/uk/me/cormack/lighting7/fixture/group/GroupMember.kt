package uk.me.cormack.lighting7.fixture.group

import uk.me.cormack.lighting7.fixture.GroupableFixture
import uk.me.cormack.lighting7.fx.group.DistributionMemberInfo

/**
 * Metadata for a fixture within a group.
 *
 * @property panOffset Pan center offset in degrees for this member (for position effects)
 * @property tiltOffset Tilt center offset in degrees for this member (for position effects)
 * @property symmetricInvert Whether to invert effects for symmetry (e.g., mirror mode)
 * @property tags Arbitrary tags for filtering group members
 */
data class MemberMetadata(
    val panOffset: Double = 0.0,
    val tiltOffset: Double = 0.0,
    val symmetricInvert: Boolean = false,
    val tags: Set<String> = emptySet()
)

/**
 * A fixture or element within a group, with position and metadata.
 *
 * @param T The fixture/element type (must implement FixtureTarget)
 * @property fixture The fixture or element instance
 * @property index Zero-based position within the group
 * @property normalizedPosition Position normalized to 0.0-1.0 range across the group
 * @property metadata Additional member-specific configuration
 */
data class GroupMember<T : GroupableFixture>(
    val fixture: T,
    override val index: Int,
    override val normalizedPosition: Double,
    val metadata: MemberMetadata = MemberMetadata()
) : DistributionMemberInfo {
    /** Target key for convenience */
    val key: String get() = fixture.targetKey

    /** Display name for convenience */
    val name: String get() = fixture.displayName
}
