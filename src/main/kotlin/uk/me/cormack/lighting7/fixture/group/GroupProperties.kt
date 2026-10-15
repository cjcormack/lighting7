package uk.me.cormack.lighting7.fixture.group

import kotlinx.serialization.Serializable
import uk.me.cormack.lighting7.routes.ChannelRef
import uk.me.cormack.lighting7.routes.SettingOption

/**
 * Property descriptors for fixture groups.
 *
 * These are similar to individual fixture PropertyDescriptors but aggregate
 * channel references from all group members, allowing the frontend to:
 * - Read values from all member channels and compute aggregates (min/max/uniform)
 * - Update all member channels simultaneously when editing
 */
@Serializable
sealed interface GroupPropertyDescriptor {
    val name: String
    val displayName: String
    val category: String
}

/**
 * Slider property aggregated across group members.
 * Contains a list of channel refs - one per group member.
 */
@Serializable
@kotlinx.serialization.SerialName("slider")
data class GroupSliderPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val min: Int = 0,
    val max: Int = 255,
    val memberChannels: List<ChannelRef>
) : GroupPropertyDescriptor

/**
 * Colour channels for a single group member.
 */
@Serializable
data class MemberColourChannels(
    val fixtureKey: String,
    val redChannel: ChannelRef,
    val greenChannel: ChannelRef,
    val blueChannel: ChannelRef,
    val whiteChannel: ChannelRef? = null,
    val amberChannel: ChannelRef? = null,
    val uvChannel: ChannelRef? = null
)

/**
 * Colour property aggregated across group members.
 * Contains colour channel refs for each group member.
 */
@Serializable
@kotlinx.serialization.SerialName("colour")
data class GroupColourPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val memberColourChannels: List<MemberColourChannels>
) : GroupPropertyDescriptor {
    override val category: String = "colour"
}

/**
 * Position channels for a single group member.
 */
@Serializable
data class MemberWhiteMixChannels(
    val fixtureKey: String,
    val warmChannel: ChannelRef,
    val coldChannel: ChannelRef,
)

/**
 * A warm/cold white pair's colour aggregated across members — the blinder's cells, as the "all
 * heads" form of `WhiteMixPropertyDescriptor`. Only members whose pairs share their declared
 * temperatures aggregate, so one warmth means one mix on every member.
 */
@Serializable
@kotlinx.serialization.SerialName("whiteMix")
data class GroupWhiteMixPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val warmKelvin: Int,
    val coldKelvin: Int,
    val memberChannels: List<MemberWhiteMixChannels>,
) : GroupPropertyDescriptor {
    override val category: String = "colour"
}

@Serializable
data class MemberPositionChannels(
    val fixtureKey: String,
    val panChannel: ChannelRef,
    val tiltChannel: ChannelRef,
    val panMin: Int = 0,
    val panMax: Int = 255,
    val tiltMin: Int = 0,
    val tiltMax: Int = 255
)

/**
 * Position property aggregated across group members.
 * Contains pan/tilt channel refs for each group member.
 */
@Serializable
@kotlinx.serialization.SerialName("position")
data class GroupPositionPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val memberPositionChannels: List<MemberPositionChannels>
) : GroupPropertyDescriptor {
    override val category: String = "position"
}

/**
 * Setting channels for a single group member.
 */
@Serializable
data class MemberSettingChannel(
    val fixtureKey: String,
    val channel: ChannelRef
)

/**
 * Setting property aggregated across group members.
 * Contains setting channel refs for each group member.
 * Note: Settings require all members to have compatible options for editing to work.
 */
@Serializable
@kotlinx.serialization.SerialName("setting")
data class GroupSettingPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val options: List<SettingOption>,
    val memberChannels: List<MemberSettingChannel>
) : GroupPropertyDescriptor
