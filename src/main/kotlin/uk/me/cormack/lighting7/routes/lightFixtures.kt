package uk.me.cormack.lighting7.routes

import io.ktor.resources.*
import io.ktor.server.application.*
import io.ktor.server.resources.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.transactions.transaction
import uk.me.cormack.lighting7.fixture.*
import uk.me.cormack.lighting7.fixture.group.MultiElementFixture
import uk.me.cormack.lighting7.state.State

internal fun Fixture.details(): FixtureDetails {
    return when (this) {
        is DmxFixture -> {
            val channels = this.channelDescriptions().map { channel ->
                DmxFixtureChannelDetails(channel.key, channel.value)
            }

            val modeInfo = if (this is MultiModeFixtureFamily<*>) {
                ModeInfo(
                    modeName = this.mode.modeName,
                    channelCount = this.mode.channelCount
                )
            } else null

            val capabilities = detectCapabilities()

            DmxFixtureDetails(
                name = this.fixtureName,
                key = this.key,
                typeKey = this.typeKey,
                manufacturer = this.manufacturer.ifEmpty { null },
                model = this.model.ifEmpty { null },
                universe = this.universe.universe,
                firstChannel = this.firstChannel,
                channelCount = this.channelCount,
                channels = channels,
                properties = this.generatePropertyDescriptors(),
                elements = this.generateElementDescriptors(),
                mode = modeInfo,
                capabilities = capabilities
            )
        }
        is HueFixture -> {
            HueFixtureDetails(this.fixtureName, this.key, this.typeKey)
        }
    }
}

private fun DmxFixture.detectCapabilities(): List<String> {
    val caps = mutableListOf<String>()
    if (this is FixtureWithDimmer) caps.add("dimmer")
    if (this is FixtureWithColour<*>) caps.add("colour")
    if (this is FixtureWithPosition) caps.add("position")
    if (this is FixtureWithUv) caps.add("uv")
    if (this is FixtureWithStrobe) caps.add("strobe")
    if (this is MultiElementFixture<*>) caps.add("multi-element")
    return caps
}

internal fun Route.routeApiRestLightsFixtures(state: State) {
    route("/fixture") {
        get("/list") {
            call.respond(state.show.fixtures.fixtures.map(Fixture::details))
        }

        get<FixtureKey> {
            call.respond(state.show.fixtures.fixture<Fixture>(it.key).details())
        }
    }
}

@Resource("/{key}")
data class FixtureKey(val key: String)

@Serializable
sealed interface FixtureDetails {
    val name: String
    val key: String
    val typeKey: String
}

@Serializable
data class DmxFixtureChannelDetails(
    val channelNo: Int,
    val description: String,
)

@Serializable
data class DmxFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
    val manufacturer: String?,
    val model: String?,
    val universe: Int,
    val firstChannel: Int,
    val channelCount: Int,
    val channels: List<DmxFixtureChannelDetails>,
    val properties: List<PropertyDescriptor>,
    val elements: List<ElementDescriptor>?,
    val mode: ModeInfo?,
    val capabilities: List<String>
): FixtureDetails

@Serializable
data class HueFixtureDetails(
    override val name: String,
    override val key: String,
    override val typeKey: String,
): FixtureDetails

// Property Descriptor Types

@Serializable
data class ChannelRef(
    val universe: Int,
    val channelNo: Int
)

@Serializable
sealed interface PropertyDescriptor {
    val name: String
    val displayName: String
    val category: String
}

@Serializable
@kotlinx.serialization.SerialName("slider")
data class SliderPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val channel: ChannelRef,
    val min: Int = 0,
    val max: Int = 255
) : PropertyDescriptor

@Serializable
@kotlinx.serialization.SerialName("colour")
data class ColourPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val redChannel: ChannelRef,
    val greenChannel: ChannelRef,
    val blueChannel: ChannelRef,
    val whiteChannel: ChannelRef? = null,
    val amberChannel: ChannelRef? = null,
    val uvChannel: ChannelRef? = null
) : PropertyDescriptor {
    override val category: String = "colour"
}

@Serializable
@kotlinx.serialization.SerialName("position")
data class PositionPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    val panChannel: ChannelRef,
    val tiltChannel: ChannelRef,
    val panMin: Int = 0,
    val panMax: Int = 255,
    val tiltMin: Int = 0,
    val tiltMax: Int = 255
) : PropertyDescriptor {
    override val category: String = "position"
}

@Serializable
@kotlinx.serialization.SerialName("setting")
data class SettingPropertyDescriptor(
    override val name: String,
    override val displayName: String,
    override val category: String,
    val channel: ChannelRef,
    val options: List<SettingOption>
) : PropertyDescriptor

@Serializable
data class SettingOption(
    val name: String,
    val level: Int,
    val displayName: String,
    val colourPreview: String? = null
)

@Serializable
data class ElementDescriptor(
    val index: Int,
    val key: String,
    val displayName: String,
    val properties: List<PropertyDescriptor>
)

@Serializable
data class ModeInfo(
    val modeName: String,
    val channelCount: Int
)
